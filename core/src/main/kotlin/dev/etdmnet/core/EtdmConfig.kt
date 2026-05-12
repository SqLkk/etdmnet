package dev.etdmnet.core

/**
 * ETDM-Net runtime configuration.
 *
 * The default values reproduce the parameter set used in the thesis
 * (`lambda = 0.3`, `alpha = 2.0`, hysteresis `eta = 0.1`, proposal timeout
 * 5 ticks, heartbeat timeout 15 ticks) so that simulator and Kotlin runtime
 * remain comparable.
 */
data class EtdmConfig(
    /** EWMA learning rate. Must be in (0, 1]. */
    val lambda: Double = 0.3,
    /** Sigmoid sharpness for the confidence transform. */
    val alpha: Double = 2.0,
    /** Hysteresis margin for candidate switching. */
    val eta: Double = 0.1,
    /** Tick interval target in milliseconds. */
    val tickIntervalMs: Long = 100L,
    /** Number of ticks to wait for ACCEPT before giving up a PROPOSE. */
    val proposeTimeoutTicks: Int = 5,
    /** Number of ticks without a HEARTBEAT before declaring the host dead. */
    val heartbeatTimeoutTicks: Int = 15,
    /** HEARTBEAT emission interval (ticks). */
    val heartbeatIntervalTicks: Int = 1,
    /** Periodic HELLO interval used during discovery. */
    val helloIntervalTicks: Int = 3,
    /** Minimum confidence required before a peer is host-eligible. */
    val minHostConfidence: Double = 0.55,
    /** Number of consecutive ticks the leading peer must stay on top. */
    val hostStabilityTicks: Int = 3,
    /** Weights used by [HealthSample.score]. */
    val weights: HealthWeights = HealthWeights(),
) {
    init {
        require(lambda in 0.0..1.0 && lambda > 0.0) { "lambda must be in (0, 1]" }
        require(alpha > 0.0) { "alpha must be positive" }
        require(eta >= 0.0) { "eta must be non-negative" }
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
        require(proposeTimeoutTicks > 0) { "proposeTimeoutTicks must be positive" }
        require(heartbeatTimeoutTicks > proposeTimeoutTicks) {
            "K1: heartbeatTimeoutTicks must exceed proposeTimeoutTicks"
        }
        require(heartbeatIntervalTicks in 1..heartbeatTimeoutTicks)
        require(helloIntervalTicks in 1..heartbeatTimeoutTicks)
        require(minHostConfidence in 0.0..1.0)
        require(hostStabilityTicks > 0)
    }
}

/**
 * Linear weights that compose a [HealthSample] into a scalar quality signal in
 * the range `[-1, +1]` consumed by the EWMA scorer. All weights must be
 * non-negative; only their relative magnitude matters.
 */
data class HealthWeights(
    val rtt: Double = 1.0,
    val loss: Double = 1.5,
    val jitter: Double = 0.5,
    val battery: Double = 0.5,
    val nat: Double = 0.5,
    val reachability: Double = 2.0,
) {
    init {
        require(rtt >= 0 && loss >= 0 && jitter >= 0 && battery >= 0 && nat >= 0 && reachability >= 0) {
            "Health weights must be non-negative"
        }
    }

    val total: Double = rtt + loss + jitter + battery + nat + reachability

    init {
        require(total > 0.0) { "At least one health weight must be positive" }
    }
}
