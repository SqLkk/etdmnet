package dev.etdmnet.transport.webrtc.android

import android.content.Context
import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.signaling.ktor.SignalPayload
import dev.etdmnet.signaling.ktor.SignalingClient
import dev.etdmnet.transport.RawPacket
import dev.etdmnet.transport.RawPeerLink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Android WebRTC transport for etdmnet. Mirrors [WebRtcTransport][dev.etdmnet.transport.webrtc.WebRtcTransport]
 * on the JVM side so phones and desktops can join the same room.
 *
 * Usage:
 * ```kotlin
 * val transport = AndroidWebRtcTransport.connect(
 *     context = applicationContext,
 *     signalingUrl = "wss://signaler.example.com/ws/v1",
 *     roomId = "lobby-1",
 *     localPeerId = EtdmNet.newPeerId("phone"),
 * )
 * val net = EtdmNet.join("lobby-1", transport)
 * ```
 */
class AndroidWebRtcTransport private constructor(
    override val localPeerId: PeerId,
    private val signaling: SignalingClient,
    private val iceServers: List<PeerConnection.IceServer>,
    appContext: Context,
) : RawPeerLink, AutoCloseable {

    private val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory = run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private data class PeerEntry(
        val pc: PeerConnection,
        @Volatile var channel: DataChannel? = null,
        val pendingRemoteCandidates: MutableList<IceCandidate> = mutableListOf(),
        @Volatile var remoteDescSet: Boolean = false,
    )

    private val peers = ConcurrentHashMap<PeerId, PeerEntry>()
    private val inbox = ConcurrentLinkedQueue<RawPacket>()
    private val lostListeners = mutableListOf<(PeerId) -> Unit>()

    // ── Optional lobby-level callbacks (fired before WebRTC handshake completes) ──

    /** Fired when the signaling socket reports the current peer list (on Hello). */
    var onSignalingPeers: ((Set<PeerId>) -> Unit)? = null
    /** Fired when a new peer joins the signaling room (before the WebRTC channel opens). */
    var onSignalingPeerJoined: ((PeerId) -> Unit)? = null
    /** Fired when a peer leaves the signaling room. */
    var onSignalingPeerLeft: ((PeerId) -> Unit)? = null
    /** Fired when our WebRTC DataChannel to [peer] becomes OPEN. */
    var onPeerChannelOpen: ((PeerId) -> Unit)? = null
    /** Signaling socket connectivity status (true = connected). */
    var onSignalingConnected: ((Boolean) -> Unit)? = null

    init {
        signaling.onPeers = { list ->
            val set = list.map { PeerId(it) }.toSet()
            set.forEach { ensurePeer(it) }
            onSignalingPeers?.invoke(set)
            onSignalingConnected?.invoke(true)
        }
        signaling.onPeerJoined = {
            val p = PeerId(it)
            ensurePeer(p)
            onSignalingPeerJoined?.invoke(p)
        }
        signaling.onPeerLeft = {
            val p = PeerId(it)
            dropPeer(p)
            onSignalingPeerLeft?.invoke(p)
        }
        signaling.onSignal = { from, payload -> handleSignal(PeerId(from), payload) }
        signaling.onError = { onSignalingConnected?.invoke(false) }
        signaling.start()
    }

    override fun connectedPeers(): Set<PeerId> = peers.entries
        .filter { (_, e) -> e.channel?.state() == DataChannel.State.OPEN }
        .map { it.key }
        .toSet()

    override fun sample(target: PeerId): HealthSample? {
        val entry = peers[target] ?: return null
        val open = entry.channel?.state() == DataChannel.State.OPEN
        return HealthSample(
            rttMs = if (open) 60.0 else Double.NaN,
            lossRate = if (open) 0.01 else 1.0,
            jitterMs = if (open) 8.0 else 0.0,
            directReachable = open,
            packetDeliveredThisTick = open,
            natFriendly = open,
        )
    }

    override fun send(target: PeerId, bytes: ByteArray): Boolean {
        val ch = peers[target]?.channel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return runCatching {
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        }.isSuccess
    }

    override fun receive(): List<RawPacket> {
        val out = mutableListOf<RawPacket>()
        while (true) out += (inbox.poll() ?: break)
        return out
    }

    override fun onPeerLost(callback: (PeerId) -> Unit) { lostListeners += callback }

    override fun close() {
        for ((_, e) in peers) {
            runCatching { e.channel?.close() }
            runCatching { e.pc.close() }
        }
        peers.clear()
        runCatching { signaling.close() }
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
    }

    private fun ensurePeer(remote: PeerId) {
        if (remote == localPeerId) return
        peers.computeIfAbsent(remote) { createEntry(remote) }
        val entry = peers[remote] ?: return
        if (localPeerId.value < remote.value && entry.channel == null) {
            val init = DataChannel.Init().apply { ordered = true }
            val ch = entry.pc.createDataChannel("etdmnet", init)
            attachChannel(remote, ch)
            createAndSendOffer(remote, entry)
        }
    }

    private fun dropPeer(remote: PeerId) {
        val entry = peers.remove(remote) ?: return
        runCatching { entry.channel?.close() }
        runCatching { entry.pc.close() }
        lostListeners.forEach { it(remote) }
    }

    private fun createEntry(remote: PeerId): PeerEntry {
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.send(
                    target = remote.value,
                    payload = SignalPayload(
                        sdpType = "candidate",
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel) { attachChannel(remote, dc) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: error("Failed to create PeerConnection for $remote")
        return PeerEntry(pc)
    }

    private fun attachChannel(remote: PeerId, ch: DataChannel) {
        val entry = peers[remote] ?: return
        entry.channel = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (ch.state()) {
                    DataChannel.State.OPEN -> onPeerChannelOpen?.invoke(remote)
                    DataChannel.State.CLOSED -> lostListeners.forEach { it(remote) }
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val arr = ByteArray(buffer.data.remaining())
                buffer.data.get(arr)
                inbox.add(RawPacket(remote, arr))
            }
        })
    }

    private fun createAndSendOffer(remote: PeerId, entry: PeerEntry) {
        entry.pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                entry.pc.setLocalDescription(SimpleSdpObserver(), desc)
                signaling.send(remote.value, SignalPayload(sdpType = "offer", sdp = desc.description))
            }
        }, MediaConstraints())
    }

    private fun handleSignal(from: PeerId, payload: SignalPayload) {
        val entry = peers.computeIfAbsent(from) { createEntry(from) }
        when (payload.sdpType) {
            "offer" -> {
                val desc = SessionDescription(SessionDescription.Type.OFFER, payload.sdp ?: return)
                entry.pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        entry.remoteDescSet = true
                        flushPendingCandidates(entry)
                        entry.pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                entry.pc.setLocalDescription(SimpleSdpObserver(), answer)
                                signaling.send(from.value, SignalPayload(sdpType = "answer", sdp = answer.description))
                            }
                        }, MediaConstraints())
                    }
                }, desc)
            }
            "answer" -> {
                val desc = SessionDescription(SessionDescription.Type.ANSWER, payload.sdp ?: return)
                entry.pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        entry.remoteDescSet = true
                        flushPendingCandidates(entry)
                    }
                }, desc)
            }
            "candidate" -> {
                val c = IceCandidate(payload.sdpMid ?: "", payload.sdpMLineIndex ?: 0, payload.candidate ?: return)
                if (entry.remoteDescSet) entry.pc.addIceCandidate(c) else entry.pendingRemoteCandidates += c
            }
        }
    }

    private fun flushPendingCandidates(entry: PeerEntry) {
        for (c in entry.pendingRemoteCandidates) entry.pc.addIceCandidate(c)
        entry.pendingRemoteCandidates.clear()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        val DEFAULT_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )

        fun connect(
            context: Context,
            signalingUrl: String,
            roomId: String,
            localPeerId: PeerId,
            iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS,
        ): AndroidWebRtcTransport {
            val signaling = SignalingClient(
                url = signalingUrl,
                room = roomId,
                peerId = localPeerId.value,
            )
            return AndroidWebRtcTransport(localPeerId, signaling, iceServers, context.applicationContext)
        }
    }
}
