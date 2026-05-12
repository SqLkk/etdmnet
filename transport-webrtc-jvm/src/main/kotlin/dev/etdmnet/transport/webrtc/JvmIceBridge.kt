package dev.etdmnet.transport.webrtc

import dev.etdmnet.turn.IceServerSpec
import dev.etdmnet.turn.TurnConfig
import dev.onvoid.webrtc.RTCIceServer

/**
 * Convert a platform-neutral [IceServerSpec] into the `webrtc-java` ICE server
 * type expected by [WebRtcTransport].
 */
fun IceServerSpec.toJvmIceServer(): RTCIceServer = RTCIceServer().apply {
    urls.add(this@toJvmIceServer.url)
    this@toJvmIceServer.username?.let { username = it }
    this@toJvmIceServer.credential?.let { password = it }
}

/** Materialize a full [TurnConfig] into the form [WebRtcTransport] consumes. */
fun TurnConfig.toJvmIceServers(): List<RTCIceServer> = toList().map { it.toJvmIceServer() }
