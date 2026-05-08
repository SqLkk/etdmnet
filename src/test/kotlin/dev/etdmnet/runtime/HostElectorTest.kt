package dev.etdmnet.runtime

import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerView
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HostElectorTest {
    private val cfg = EtdmConfig(hostStabilityTicks = 3)
    private val local = PeerId("local")
    private val a = PeerId("peerA")
    private val b = PeerId("peerB")

    private fun view(id: PeerId, conf: Double): PeerView {
        val v = PeerView(id, cfg)
        // Drive the EWMA score toward the desired confidence by pushing the
        // matching observation repeatedly: with constant `obs`, raw converges to obs.
        val rawTarget = -kotlin.math.ln(1.0 / conf - 1.0) / cfg.alpha
        val obs = rawTarget.coerceIn(-1.0, 1.0)
        repeat(40) { v.score.update(obs) }
        return v
    }

    @Test fun `picks max confidence when no incumbent`() {
        val e = HostElector(cfg)
        val peers = mapOf(a to view(a, 0.8), b to view(b, 0.7))
        val pick = e.recommend(local, localConfidence = 0.6, peers = peers, currentHost = null, currentHostHealthy = false)
        assertEquals(a, pick)
    }

    @Test fun `keeps incumbent unless challenger dominates for stabilityTicks`() {
        val e = HostElector(cfg)
        val incumbent = a
        val peers = mapOf(a to view(a, 0.6), b to view(b, 0.85))
        // Tick 1
        var pick = e.recommend(local, 0.5, peers, currentHost = incumbent, currentHostHealthy = true)
        assertEquals(incumbent, pick)
        // Tick 2
        pick = e.recommend(local, 0.5, peers, currentHost = incumbent, currentHostHealthy = true)
        assertEquals(incumbent, pick)
        // Tick 3 — finally switch
        pick = e.recommend(local, 0.5, peers, currentHost = incumbent, currentHostHealthy = true)
        assertEquals(b, pick)
    }

    @Test fun `unhealthy incumbent triggers immediate switch`() {
        val e = HostElector(cfg)
        val peers = mapOf(a to view(a, 0.6), b to view(b, 0.9))
        val pick = e.recommend(local, 0.5, peers, currentHost = a, currentHostHealthy = false)
        assertEquals(b, pick)
    }

    @Test fun `hysteresis prevents oscillation on near-tie`() {
        val e = HostElector(cfg)
        val peers = mapOf(a to view(a, 0.70), b to view(b, 0.72))
        repeat(10) {
            val pick = e.recommend(local, 0.5, peers, currentHost = a, currentHostHealthy = true)
            assertEquals(a, pick, "should not flip on near-tie within eta=${cfg.eta}")
        }
    }
}
