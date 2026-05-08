package dev.etdmnet.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthSampleTest {
    private val w = HealthWeights()

    @Test fun `perfect link scores near +1`() {
        val s = HealthSample(rttMs = 10.0, lossRate = 0.0, jitterMs = 1.0).score(w)
        assertTrue(s > 0.6, "perfect link should score high, got $s")
    }

    @Test fun `broken link scores near -1`() {
        val s = HealthSample(
            rttMs = 800.0, lossRate = 0.9, jitterMs = 200.0,
            batteryPercent = 0.05, directReachable = false,
            packetDeliveredThisTick = false, natFriendly = false,
        ).score(w)
        assertTrue(s < -0.5, "broken link should score low, got $s")
    }

    @Test fun `score is bounded`() {
        for (rtt in listOf(0.0, 50.0, 200.0, 2000.0)) {
            for (loss in listOf(0.0, 0.1, 0.5, 1.0)) {
                val s = HealthSample(rttMs = rtt, lossRate = loss, jitterMs = 10.0).score(w)
                assertTrue(s in -1.0..1.0, "out of bounds: $s for rtt=$rtt loss=$loss")
            }
        }
    }
}

class EwmaScoreTest {
    @Test fun `ewma smooths transient drops`() {
        val s = EwmaScore(lambda = 0.3, alpha = 2.0)
        repeat(20) { s.update(1.0) }
        val before = s.confidence
        s.update(-1.0)
        val after = s.confidence
        assertTrue(after < before, "single bad sample should reduce confidence")
        assertTrue(after > 0.5, "single bad sample must not flip confidence: $after")
    }

    @Test fun `confidence converges`() {
        val s = EwmaScore(lambda = 0.5, alpha = 2.0)
        repeat(50) { s.update(1.0) }
        assertTrue(s.confidence > 0.85, "confidence too low: ${s.confidence}")

        val t = EwmaScore(lambda = 0.5, alpha = 2.0)
        repeat(50) { t.update(-1.0) }
        assertTrue(t.confidence < 0.15, "confidence too high: ${t.confidence}")
    }

    @Test fun `reset returns to neutral`() {
        val s = EwmaScore(lambda = 0.3, alpha = 2.0)
        repeat(10) { s.update(1.0) }
        s.reset()
        assertEquals(0.5, s.confidence, 1e-9)
        assertEquals(0.0, s.raw, 1e-9)
    }
}
