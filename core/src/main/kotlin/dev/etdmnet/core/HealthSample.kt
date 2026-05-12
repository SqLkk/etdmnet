package dev.etdmnet.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Per-peer link quality observation captured each tick by the transport layer.
 *
 * All fields are normalized so that "lower is better" for [rttMs], [lossRate]
 * and [jitterMs], "higher is better" for [batteryPercent], and the boolean
 * channels (NAT reachability) are encoded explicitly.
 */
data class HealthSample(
    /** Smoothed round-trip time in milliseconds. Use [Double.NaN] when unknown. */
    val rttMs: Double,
    /** Smoothed packet loss in `[0,1]`. */
    val lossRate: Double,
    /** RTT variation in milliseconds. */
    val jitterMs: Double,
    /** Local battery level in `[0,1]`. */
    val batteryPercent: Double = 1.0,
    /** Whether the transport managed a direct (non-relay) connection. */
    val directReachable: Boolean = true,
    /** Whether at least one packet was actually delivered this tick. */
    val packetDeliveredThisTick: Boolean = true,
    /** True when the peer's NAT type is permissive (e.g. cone NAT, IPv6). */
    val natFriendly: Boolean = true,
) {
    init {
        require(lossRate in 0.0..1.0) { "lossRate must be in [0,1]" }
        require(batteryPercent in 0.0..1.0) { "batteryPercent must be in [0,1]" }
        if (!rttMs.isNaN()) require(rttMs >= 0) { "rttMs must be non-negative" }
        require(jitterMs >= 0) { "jitterMs must be non-negative" }
    }

    /**
     * Map this sample to a scalar observation in `[-1,+1]`. The mapping is
     * deliberately monotone so that EWMA scores remain interpretable: a value
     * close to `+1` means the link is excellent, `-1` means the link is broken.
     */
    fun score(weights: HealthWeights): Double {
        val rttPart = if (rttMs.isNaN()) 0.0 else 1.0 - clamp01(rttMs / 600.0)
        val lossPart = 1.0 - 2.0 * clamp01(lossRate)
        val jitterPart = 1.0 - clamp01(jitterMs / 200.0)
        val batteryPart = 2.0 * clamp01(batteryPercent) - 1.0
        val natPart = if (natFriendly) 1.0 else -0.5
        val reachPart = when {
            !packetDeliveredThisTick -> -1.0
            directReachable -> 1.0
            else -> -0.25
        }
        val sum =
            weights.rtt * (2.0 * rttPart - 1.0) +
                weights.loss * lossPart +
                weights.jitter * (2.0 * jitterPart - 1.0) +
                weights.battery * batteryPart +
                weights.nat * natPart +
                weights.reachability * reachPart
        val normalized = sum / weights.total
        return clamp(normalized, -1.0, 1.0)
    }
}

/**
 * EWMA scorer with sigmoid-based confidence transform.
 *
 * Mirrors the thesis equations B.1 and B.2 so that the Kotlin runtime is
 * directly comparable to the Python simulator used in the staged proof.
 */
class EwmaScore(
    private val lambda: Double,
    private val alpha: Double,
) {
    init {
        require(lambda in 0.0..1.0 && lambda > 0.0)
        require(alpha > 0.0)
    }

    var raw: Double = 0.0
        private set

    var confidence: Double = 0.5
        private set

    var samples: Int = 0
        private set

    fun update(observation: Double) {
        require(observation in -1.0..1.0) { "observation out of range: $observation" }
        raw = (1.0 - lambda) * raw + lambda * observation
        confidence = sigmoid(raw)
        samples += 1
    }

    fun decay(amount: Double = 0.05) {
        require(amount >= 0)
        raw = max(-1.0, raw - amount)
        confidence = sigmoid(raw)
    }

    fun reset() {
        raw = 0.0
        confidence = 0.5
        samples = 0
    }

    private fun sigmoid(s: Double): Double = 1.0 / (1.0 + exp(-alpha * s))
}

private fun clamp01(v: Double): Double = clamp(v, 0.0, 1.0)

private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
