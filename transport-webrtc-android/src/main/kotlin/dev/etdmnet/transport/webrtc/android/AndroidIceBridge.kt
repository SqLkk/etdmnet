package dev.etdmnet.transport.webrtc.android

import dev.etdmnet.turn.IceServerSpec
import dev.etdmnet.turn.TurnConfig
import org.webrtc.PeerConnection

/**
 * Convert a platform-neutral [IceServerSpec] into the Android WebRTC SDK's
 * `IceServer` type expected by [AndroidWebRtcTransport].
 */
fun IceServerSpec.toAndroidIceServer(): PeerConnection.IceServer {
    val b = PeerConnection.IceServer.builder(url)
    username?.let { b.setUsername(it) }
    credential?.let { b.setPassword(it) }
    return b.createIceServer()
}

/** Materialize a full [TurnConfig] into the form [AndroidWebRtcTransport] consumes. */
fun TurnConfig.toAndroidIceServers(): List<PeerConnection.IceServer> =
    toList().map { it.toAndroidIceServer() }
