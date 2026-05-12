package dev.etdmnet.transport.webrtc

import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.signaling.ktor.SignalPayload
import dev.etdmnet.signaling.ktor.SignalingClient
import dev.etdmnet.transport.RawPacket
import dev.etdmnet.transport.RawPeerLink
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * JVM (Desktop/PC) WebRTC transport. Implements [RawPeerLink] so any peer —
 * mobile or desktop — can join the same etdmnet session.
 *
 * Build with:
 * ```kotlin
 * val transport = WebRtcTransport.connect(
 *     signalingUrl = "wss://signaler.example.com/ws/v1",
 *     roomId       = "lobby-1",
 *     localPeerId  = EtdmNet.newPeerId("desktop"),
 * )
 * val net = EtdmNet.join("lobby-1", transport)
 * ```
 */
class WebRtcTransport private constructor(
    override val localPeerId: PeerId,
    private val signaling: SignalingClient,
    iceServers: List<RTCIceServer>,
) : RawPeerLink, AutoCloseable {

    private val factory = PeerConnectionFactory()
    private val rtcConfig = RTCConfiguration().apply { this.iceServers.addAll(iceServers) }

    private data class PeerEntry(
        val pc: RTCPeerConnection,
        @Volatile var channel: RTCDataChannel? = null,
        val pendingRemoteCandidates: MutableList<RTCIceCandidate> = mutableListOf(),
        @Volatile var remoteDescSet: Boolean = false,
    )

    private val peers = ConcurrentHashMap<PeerId, PeerEntry>()
    private val inbox = ConcurrentLinkedQueue<RawPacket>()
    private val lostListeners = mutableListOf<(PeerId) -> Unit>()

    init {
        signaling.onPeers = { list -> list.forEach { ensurePeer(PeerId(it)) } }
        signaling.onPeerJoined = { ensurePeer(PeerId(it)) }
        signaling.onPeerLeft = { dropPeer(PeerId(it)) }
        signaling.onSignal = { from, payload -> handleSignal(PeerId(from), payload) }
        signaling.start()
    }

    override fun connectedPeers(): Set<PeerId> = peers.entries
        .filter { (_, e) -> e.channel?.state == RTCDataChannelState.OPEN }
        .map { it.key }
        .toSet()

    override fun sample(target: PeerId): HealthSample? {
        val entry = peers[target] ?: return null
        val open = entry.channel?.state == RTCDataChannelState.OPEN
        return HealthSample(
            rttMs = if (open) 50.0 else Double.NaN,
            lossRate = if (open) 0.01 else 1.0,
            jitterMs = if (open) 5.0 else 0.0,
            directReachable = open,
            packetDeliveredThisTick = open,
            natFriendly = open,
        )
    }

    override fun send(target: PeerId, bytes: ByteArray): Boolean {
        val ch = peers[target]?.channel ?: return false
        if (ch.state != RTCDataChannelState.OPEN) return false
        return runCatching {
            ch.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
        }.isSuccess
    }

    override fun receive(): List<RawPacket> {
        val out = mutableListOf<RawPacket>()
        while (true) out += (inbox.poll() ?: break)
        return out
    }

    override fun onPeerLost(callback: (PeerId) -> Unit) {
        lostListeners += callback
    }

    override fun close() {
        for ((_, e) in peers) {
            runCatching { e.channel?.close() }
            runCatching { e.pc.close() }
        }
        peers.clear()
        runCatching { signaling.close() }
        runCatching { factory.dispose() }
    }

    // ── Peer lifecycle ──────────────────────────────────────────────────

    private fun ensurePeer(remote: PeerId) {
        if (remote == localPeerId) return
        peers.computeIfAbsent(remote) { createEntry(remote) }
        // Tie-break: lexicographically smaller peer creates the offer.
        val entry = peers[remote] ?: return
        if (localPeerId.value < remote.value && entry.channel == null) {
            // We are the offerer — create channel and offer.
            val ch = entry.pc.createDataChannel("etdmnet", RTCDataChannelInit().apply {
                ordered = true
            })
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
        lateinit var entry: PeerEntry
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
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
            override fun onDataChannel(dataChannel: RTCDataChannel) {
                attachChannel(remote, dataChannel)
            }
        })
        entry = PeerEntry(pc)
        return entry
    }

    private fun attachChannel(remote: PeerId, ch: RTCDataChannel) {
        val entry = peers[remote] ?: return
        entry.channel = ch
        ch.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (ch.state == RTCDataChannelState.CLOSED) {
                    lostListeners.forEach { it(remote) }
                }
            }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val arr = ByteArray(buffer.data.remaining())
                buffer.data.get(arr)
                inbox.add(RawPacket(remote, arr))
            }
        })
    }

    private fun createAndSendOffer(remote: PeerId, entry: PeerEntry) {
        entry.pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                entry.pc.setLocalDescription(description, noOpSetObserver())
                signaling.send(remote.value, SignalPayload(sdpType = "offer", sdp = description.sdp))
            }
            override fun onFailure(error: String) {}
        })
    }

    private fun handleSignal(from: PeerId, payload: SignalPayload) {
        val entry = peers.computeIfAbsent(from) { createEntry(from) }
        when (payload.sdpType) {
            "offer" -> {
                val desc = RTCSessionDescription(RTCSdpType.OFFER, payload.sdp ?: return)
                entry.pc.setRemoteDescription(desc, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        entry.remoteDescSet = true
                        flushPendingCandidates(entry)
                        entry.pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                            override fun onSuccess(description: RTCSessionDescription) {
                                entry.pc.setLocalDescription(description, noOpSetObserver())
                                signaling.send(from.value, SignalPayload(sdpType = "answer", sdp = description.sdp))
                            }
                            override fun onFailure(error: String) {}
                        })
                    }
                    override fun onFailure(error: String) {}
                })
            }
            "answer" -> {
                val desc = RTCSessionDescription(RTCSdpType.ANSWER, payload.sdp ?: return)
                entry.pc.setRemoteDescription(desc, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        entry.remoteDescSet = true
                        flushPendingCandidates(entry)
                    }
                    override fun onFailure(error: String) {}
                })
            }
            "candidate" -> {
                val candidate = RTCIceCandidate(
                    payload.sdpMid ?: "",
                    payload.sdpMLineIndex ?: 0,
                    payload.candidate ?: return,
                )
                if (entry.remoteDescSet) entry.pc.addIceCandidate(candidate)
                else entry.pendingRemoteCandidates += candidate
            }
        }
    }

    private fun flushPendingCandidates(entry: PeerEntry) {
        for (c in entry.pendingRemoteCandidates) entry.pc.addIceCandidate(c)
        entry.pendingRemoteCandidates.clear()
    }

    private fun noOpSetObserver() = object : SetSessionDescriptionObserver {
        override fun onSuccess() {}
        override fun onFailure(error: String) {}
    }

    companion object {
        /** Default Google STUN servers — adequate for most non-symmetric NATs. */
        val DEFAULT_ICE_SERVERS: List<RTCIceServer> = listOf(
            RTCIceServer().apply { urls.add("stun:stun.l.google.com:19302") },
            RTCIceServer().apply { urls.add("stun:stun1.l.google.com:19302") },
        )

        /** One-call constructor. Connects to signaling and starts listening. */
        fun connect(
            signalingUrl: String,
            roomId: String,
            localPeerId: PeerId,
            iceServers: List<RTCIceServer> = DEFAULT_ICE_SERVERS,
        ): WebRtcTransport {
            val signaling = SignalingClient(
                url = signalingUrl,
                room = roomId,
                peerId = localPeerId.value,
            )
            return WebRtcTransport(localPeerId, signaling, iceServers)
        }
    }
}
