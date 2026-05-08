package dev.etdmnet.runtime

import dev.etdmnet.EtdmNet
import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.Role
import dev.etdmnet.core.VirtualClock
import dev.etdmnet.transport.RawPacket
import dev.etdmnet.transport.RawPeerLink
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EncodedTransportSessionTest {
    private val cfg = EtdmConfig(
        heartbeatTimeoutTicks = 8,
        proposeTimeoutTicks = 3,
        hostStabilityTicks = 2,
        helloIntervalTicks = 2,
    )

    @Test fun `sessions converge over byte transport`() {
        val bus = ByteBus()
        val sessions = listOf("alice", "bob", "carol", "dave").map { newSession(it, bus) }
        bus.setAllLinks(HealthSample(35.0, 0.0, 5.0))

        repeat(50) { sessions.forEach { it.tick() } }

        val hosts = sessions.map { it.host.hostId }.toSet()
        assertEquals(1, hosts.size)
        val agreedHost = assertNotNull(hosts.single())
        assertEquals(Role.HOST, sessions.first { it.localPeerId == agreedHost }.role)
        assertTrue(sessions.any { it.role == Role.BACKUP_HOST }, "one survivor should be marked backup host")
    }

    @Test fun `application payload crosses encoded transport`() {
        val bus = ByteBus()
        val alice = newSession("alice", bus)
        val bob = newSession("bob", bus)
        bus.setAllLinks(HealthSample(25.0, 0.0, 3.0))
        repeat(30) { listOf(alice, bob).forEach { it.tick() } }

        alice.sendApplication(bob.localPeerId, "move:up".encodeToByteArray())
        alice.tick()
        val delivered = bob.tick().deliveredApplicationMessages

        assertEquals(1, delivered.size)
        assertEquals("move:up", delivered.single().payload.decodeToString())
    }

    @Test fun `backup takes over after byte link partition`() {
        val bus = ByteBus()
        val sessions = listOf("alice", "bob", "carol", "dave").map { newSession(it, bus) }
        bus.setAllLinks(HealthSample(30.0, 0.0, 4.0))
        repeat(60) { sessions.forEach { it.tick() } }

        val originalHost = sessions.first { it.role == Role.HOST }
        val originalEpoch = originalHost.host.hostEpoch
        bus.isolate(originalHost.localPeerId)
        bus.links.keys.filter { it.first == originalHost.localPeerId || it.second == originalHost.localPeerId }
            .forEach { bus.links.remove(it) }

        repeat(90) { sessions.forEach { it.tick() } }

        val survivors = sessions.filter { it !== originalHost }
        val hosts = survivors.map { it.host.hostId }.toSet()
        assertEquals(1, hosts.size, "survivors disagree on host: ${survivors.map { it.localPeerId to it.host }}")
        val newHost = assertNotNull(hosts.single())
        assertTrue(newHost != originalHost.localPeerId)
        assertTrue(survivors.first { it.localPeerId == newHost }.host.hostEpoch > originalEpoch)
    }

    private fun newSession(id: String, bus: ByteBus): Session {
        return EtdmNet.createSession(
            sessionId = "game-byte",
            link = BytePeerLink(PeerId(id), bus),
            clock = VirtualClock(),
            config = cfg,
        )
    }

    private class ByteBus(
        private val random: Random = Random(0xE7DB17),
    ) {
        var lossRate: Double = 0.0
        val links: MutableMap<Pair<PeerId, PeerId>, HealthSample> = mutableMapOf()
        private val inboxes: MutableMap<PeerId, ConcurrentLinkedQueue<RawPacket>> = mutableMapOf()
        private val isolated: MutableSet<PeerId> = mutableSetOf()

        fun register(peerId: PeerId) {
            inboxes.getOrPut(peerId) { ConcurrentLinkedQueue() }
        }

        fun isolate(peerId: PeerId) { isolated += peerId }

        fun connectedPeers(self: PeerId): Set<PeerId> {
            if (self in isolated) return emptySet()
            return inboxes.keys.filter { it != self && it !in isolated && links[self to it] != null }.toSet()
        }

        fun sample(from: PeerId, target: PeerId): HealthSample? = links[from to target]

        fun send(from: PeerId, target: PeerId, bytes: ByteArray): Boolean {
            if (from in isolated || target in isolated) return false
            if (links[from to target] == null) return false
            if (random.nextDouble() < lossRate) return false
            inboxes[target]?.add(RawPacket(from, bytes.copyOf())) ?: return false
            return true
        }

        fun receive(peerId: PeerId): List<RawPacket> {
            val inbox = inboxes[peerId] ?: return emptyList()
            val out = mutableListOf<RawPacket>()
            while (true) out += inbox.poll() ?: break
            return out
        }

        fun setAllLinks(sample: HealthSample) {
            for (a in inboxes.keys) {
                for (b in inboxes.keys) {
                    if (a != b) links[a to b] = sample
                }
            }
        }
    }

    private class BytePeerLink(
        override val localPeerId: PeerId,
        private val bus: ByteBus,
    ) : RawPeerLink {
        init { bus.register(localPeerId) }

        override fun connectedPeers(): Set<PeerId> = bus.connectedPeers(localPeerId)

        override fun sample(target: PeerId): HealthSample? = bus.sample(localPeerId, target)

        override fun send(target: PeerId, bytes: ByteArray): Boolean = bus.send(localPeerId, target, bytes)

        override fun receive(): List<RawPacket> = bus.receive(localPeerId)

        override fun onPeerLost(callback: (PeerId) -> Unit) = Unit
    }
}
