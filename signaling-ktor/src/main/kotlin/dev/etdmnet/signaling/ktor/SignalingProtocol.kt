package dev.etdmnet.signaling.ktor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol for the bundled etdmnet signaling server.
 *
 * The server **only** forwards SDP/ICE between peers in the same room. It
 * never sees, inspects, or stores application data. Once two peers have
 * exchanged offer/answer/candidates, all game traffic flows P2P through
 * the WebRTC DataChannel.
 */
object SignalingProtocol {
    const val VERSION: Int = 1
}

@Serializable
sealed class SignalingClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val room: String, val peerId: String, val version: Int = SignalingProtocol.VERSION) : SignalingClientMessage()

    @Serializable
    @SerialName("signal")
    data class Signal(val room: String, val target: String, val payload: SignalPayload) : SignalingClientMessage()

    @Serializable
    @SerialName("bye")
    data class Bye(val room: String, val peerId: String) : SignalingClientMessage()
}

@Serializable
sealed class SignalingServerMessage {
    @Serializable
    @SerialName("peers")
    data class Peers(val room: String, val peers: List<String>) : SignalingServerMessage()

    @Serializable
    @SerialName("peer_joined")
    data class PeerJoined(val room: String, val peerId: String) : SignalingServerMessage()

    @Serializable
    @SerialName("peer_left")
    data class PeerLeft(val room: String, val peerId: String) : SignalingServerMessage()

    @Serializable
    @SerialName("signal")
    data class Signal(val room: String, val from: String, val payload: SignalPayload) : SignalingServerMessage()

    @Serializable
    @SerialName("error")
    data class Error(val reason: String) : SignalingServerMessage()
}

/**
 * Opaque WebRTC signaling payload. The signaling server does not interpret
 * any of these fields — they are forwarded byte-for-byte.
 */
@Serializable
data class SignalPayload(
    /** `"offer"`, `"answer"`, or `"candidate"`. */
    val sdpType: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)
