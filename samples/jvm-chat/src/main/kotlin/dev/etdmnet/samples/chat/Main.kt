package dev.etdmnet.samples.chat

import dev.etdmnet.EtdmNet
import dev.etdmnet.transport.webrtc.WebRtcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 50-line cross-platform chat over etdmnet.
 *
 * Usage:
 * ```
 * ./gradlew :samples:jvm-chat:run --args="<signaling-ws-url> <room> <name>"
 * # e.g.
 * ./gradlew :samples:jvm-chat:run --args="ws://localhost:8080/ws/v1 lobby alice"
 * ```
 * Then type lines and press Enter. Everyone in the room sees the message.
 * The HOST role is auto-elected and migrates on disconnect — no central
 * server is involved in message delivery.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) {
        "Usage: <signaling-ws-url> <room-id> <display-name>"
    }
    val (signalingUrl, room, name) = args

    val peerId = EtdmNet.newPeerId(name)
    println("[etdmnet] my peer id: ${peerId.value}")

    val transport = WebRtcTransport.connect(
        signalingUrl = signalingUrl,
        roomId = room,
        localPeerId = peerId,
    )
    val net = EtdmNet.join(roomId = room, transport = transport)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    scope.launch { net.role.collect { println("[etdmnet] role → $it") } }
    scope.launch { net.peers.collect { println("[etdmnet] peers: ${it.map { p -> p.value }}") } }
    scope.launch {
        net.messages.collect { msg ->
            println("[${msg.from.value}] ${String(msg.payload, Charsets.UTF_8)}")
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[etdmnet] shutting down…")
        net.close()
        transport.close()
        scope.cancel()
    })

    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        // Host broadcasts to everyone; non-hosts send to the host who relays
        // by re-broadcasting in onMessage below. For a chat, hosts simply
        // forward client messages by re-publishing.
        if (net.isHost) {
            net.forcePublish(line.toByteArray(Charsets.UTF_8))
        } else {
            net.broadcast(line.toByteArray(Charsets.UTF_8))
        }
    }
}
