package dev.etdmnet.runtime

import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.Role
import dev.etdmnet.core.VirtualClock
import dev.etdmnet.transport.Bus
import dev.etdmnet.transport.LoopbackTransport
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FourPeerSessionTest {
    private val cfg = EtdmConfig(
        heartbeatTimeoutTicks = 8,
        proposeTimeoutTicks = 3,
        hostStabilityTicks = 2,
        helloIntervalTicks = 2,
    )

    private fun newSession(id: String, bus: Bus): Session {
        val transport = LoopbackTransport(PeerId(id), bus)
        return Session(
            sessionId = "game-1",
            transport = transport,
            clock = VirtualClock(),
            config = cfg,
            random = Random(id.hashCode().toLong()),
        )
    }

    private fun setMutualLinks(sessions: List<Session>, sample: HealthSample) {
        for (s in sessions) {
            val t = s.transport as LoopbackTransport
            for (other in sessions) if (other !== s) t.setLink(other.localPeerId, sample)
        }
    }

    @Test fun `four peers converge on a host`() {
        val bus = Bus()
        val sessions = listOf("alice", "bob", "carol", "dave").map { newSession(it, bus) }
        setMutualLinks(sessions, HealthSample(rttMs = 30.0, lossRate = 0.0, jitterMs = 5.0))

        repeat(40) { sessions.forEach { it.tick() } }

        val hosts = sessions.map { it.host.hostId }.toSet()
        assertEquals(1, hosts.size, "all peers must agree on a single host, got $hosts")
        val host = hosts.single()
        assertNotNull(host)
        val hostSession = sessions.first { it.localPeerId == host }
        assertEquals(Role.HOST, hostSession.role)
        assertTrue(sessions.any { it.role == Role.BACKUP_HOST }, "one peer should be ready as backup host")
        for (s in sessions) if (s !== hostSession) {
            assertTrue(s.role == Role.CLIENT || s.role == Role.BACKUP_HOST)
        }
    }

    @Test fun `host migration on Wi-Fi to cellular handover`() {
        val bus = Bus()
        val sessions = listOf("alice", "bob", "carol", "dave").map { newSession(it, bus) }
        setMutualLinks(sessions, HealthSample(rttMs = 25.0, lossRate = 0.0, jitterMs = 5.0))

        // Phase 1: converge.
        repeat(40) { sessions.forEach { it.tick() } }
        val originalHost = sessions.first { it.role == Role.HOST }
        val originalEpoch = originalHost.host.hostEpoch

        // Phase 2: original host loses uplink (Wi-Fi died, falling back to cellular —
        // but we simulate the worst case where it cannot reach the bus at all).
        bus.isolate(originalHost.localPeerId)
        // Also drop its link samples so its peers stop boosting its score.
        for (s in sessions) {
            val t = s.transport as LoopbackTransport
            t.dropLink(originalHost.localPeerId)
        }

        // Phase 3: let migration happen.
        repeat(80) { sessions.forEach { it.tick() } }

        val survivors = sessions.filter { it !== originalHost }
        val newHosts = survivors.map { it.host.hostId }.toSet()
        assertEquals(1, newHosts.size, "survivors must agree on a new host, got $newHosts")
        val newHost = newHosts.single()
        assertNotNull(newHost)
        assertTrue(newHost != originalHost.localPeerId, "host must actually migrate")
        val newHostSession = survivors.first { it.localPeerId == newHost }
        assertEquals(Role.HOST, newHostSession.role)
        assertTrue(
            newHostSession.host.hostEpoch > originalEpoch,
            "host epoch must strictly increase across migration",
        )
    }

    @Test fun `runtime tolerates 30 percent loss`() {
        val bus = Bus()
        bus.lossRate = 0.3
        val sessions = listOf("alice", "bob", "carol", "dave").map { newSession(it, bus) }
        setMutualLinks(sessions, HealthSample(rttMs = 60.0, lossRate = 0.3, jitterMs = 15.0))

        repeat(120) { sessions.forEach { it.tick() } }

        val hosts = sessions.map { it.host.hostId }.toSet()
        assertEquals(1, hosts.size, "must converge under 30% loss, got $hosts")
    }
}
