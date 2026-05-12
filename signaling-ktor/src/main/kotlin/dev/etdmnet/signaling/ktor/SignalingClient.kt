package dev.etdmnet.signaling.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.URLProtocol
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Ktor WebSocket client for the bundled etdmnet signaling server.
 *
 * Usage:
 * ```kotlin
 * val client = SignalingClient(
 *     url = "wss://signaling.example.com/ws/v1",
 *     room = "lobby-1",
 *     peerId = "alice",
 * )
 * client.onPeers      = { peers -> ... }
 * client.onPeerJoined = { peer  -> ... }
 * client.onSignal     = { from, payload -> ... }
 * client.start()
 * client.send(target = "bob", payload = ...)
 * ```
 */
class SignalingClient(
    private val url: String,
    private val room: String,
    private val peerId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    var onPeers: ((List<String>) -> Unit)? = null
    var onPeerJoined: ((String) -> Unit)? = null
    var onPeerLeft: ((String) -> Unit)? = null
    var onSignal: ((from: String, payload: SignalPayload) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
    private val outbound = Channel<SignalingClientMessage>(Channel.BUFFERED)
    private val httpClient = HttpClient(OkHttp) { install(WebSockets) }
    private var sessionJob: Job? = null

    fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            try {
                val parsed = parseUrl(url)
                httpClient.webSocket(
                    method = io.ktor.http.HttpMethod.Get,
                    host = parsed.host,
                    port = parsed.port,
                    path = parsed.path,
                    request = { url.protocol = parsed.scheme },
                ) {
                    send(Frame.Text(json.encodeToString(
                        SignalingClientMessage.serializer(),
                        SignalingClientMessage.Hello(room, peerId),
                    )))
                    val sendJob = launch {
                        for (msg in outbound) {
                            send(Frame.Text(json.encodeToString(SignalingClientMessage.serializer(), msg)))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val msg = runCatching {
                                json.decodeFromString(SignalingServerMessage.serializer(), frame.readText())
                            }.getOrNull() ?: continue
                            dispatch(msg)
                        }
                    } finally {
                        sendJob.cancel()
                    }
                }
            } catch (t: Throwable) {
                onError?.invoke(t)
            }
        }
    }

    fun send(target: String, payload: SignalPayload) {
        outbound.trySend(SignalingClientMessage.Signal(room, target, payload))
    }

    override fun close() {
        scope.launch { outbound.trySend(SignalingClientMessage.Bye(room, peerId)) }
        outbound.close()
        sessionJob?.cancel()
        httpClient.close()
    }

    private fun dispatch(msg: SignalingServerMessage) {
        when (msg) {
            is SignalingServerMessage.Peers -> onPeers?.invoke(msg.peers)
            is SignalingServerMessage.PeerJoined -> onPeerJoined?.invoke(msg.peerId)
            is SignalingServerMessage.PeerLeft -> onPeerLeft?.invoke(msg.peerId)
            is SignalingServerMessage.Signal -> onSignal?.invoke(msg.from, msg.payload)
            is SignalingServerMessage.Error -> onError?.invoke(RuntimeException(msg.reason))
        }
    }

    private data class ParsedUrl(val scheme: URLProtocol, val host: String, val port: Int, val path: String)

    private fun parseUrl(raw: String): ParsedUrl {
        // Minimal ws:// / wss:// parser sufficient for our use case.
        val isSecure = raw.startsWith("wss://")
        val scheme = if (isSecure) URLProtocol.WSS else URLProtocol.WS
        val withoutScheme = raw.substringAfter("://")
        val hostPort = withoutScheme.substringBefore("/")
        val path = "/" + withoutScheme.substringAfter("/", "")
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":", if (isSecure) "443" else "80").toIntOrNull()
            ?: if (isSecure) 443 else 80
        return ParsedUrl(scheme, host, port, path)
    }
}
