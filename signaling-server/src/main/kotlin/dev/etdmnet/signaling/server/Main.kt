package dev.etdmnet.signaling.server

import dev.etdmnet.signaling.ktor.SignalingClientMessage
import dev.etdmnet.signaling.ktor.SignalingServerMessage
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Bundled etdmnet signaling server.
 *
 * Forwards SDP/ICE between peers in the same room. **Does not handle game
 * data** — game traffic flows P2P through WebRTC after signaling completes.
 *
 * Run:
 * ```
 * ./gradlew :signaling-server:run
 * ```
 * or build a fat jar and run anywhere with JRE 17+.
 */
fun main(args: Array<String>) {
    val port = (System.getenv("PORT") ?: args.firstOrNull() ?: "8080").toInt()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json {
                ignoreUnknownKeys = true
                classDiscriminator = "t"
            })
        }
        signalingRoutes()
    }.start(wait = true)
}

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }

private fun Application.signalingRoutes() {
    val rooms = ConcurrentHashMap<String, ConcurrentHashMap<String, DefaultWebSocketSession>>()

    routing {
        webSocket("/ws/v1") {
            var joinedRoom: String? = null
            var joinedPeer: String? = null
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = runCatching {
                        json.decodeFromString(SignalingClientMessage.serializer(), frame.readText())
                    }.getOrNull() ?: continue

                    when (msg) {
                        is SignalingClientMessage.Hello -> {
                            joinedRoom = msg.room
                            joinedPeer = msg.peerId
                            val room = rooms.computeIfAbsent(msg.room) { ConcurrentHashMap() }
                            // Send current peer list to the newcomer.
                            val existing = room.keys.toList()
                            sendJson(SignalingServerMessage.Peers(msg.room, existing))
                            // Announce newcomer to existing peers.
                            val joined = SignalingServerMessage.PeerJoined(msg.room, msg.peerId)
                            for ((_, session) in room) session.sendJson(joined)
                            room[msg.peerId] = this
                        }
                        is SignalingClientMessage.Signal -> {
                            val room = rooms[msg.room] ?: continue
                            val target = room[msg.target] ?: continue
                            val from = joinedPeer ?: continue
                            target.sendJson(SignalingServerMessage.Signal(msg.room, from, msg.payload))
                        }
                        is SignalingClientMessage.Bye -> {
                            // handled in finally
                            break
                        }
                    }
                }
            } finally {
                val r = joinedRoom
                val p = joinedPeer
                if (r != null && p != null) {
                    val room = rooms[r]
                    room?.remove(p)
                    val left = SignalingServerMessage.PeerLeft(r, p)
                    room?.values?.forEach { it.sendJson(left) }
                    if (room?.isEmpty() == true) rooms.remove(r)
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketSession.sendJson(msg: SignalingServerMessage) {
    send(Frame.Text(json.encodeToString(SignalingServerMessage.serializer(), msg)))
}
