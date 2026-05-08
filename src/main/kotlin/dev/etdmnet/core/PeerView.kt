package dev.etdmnet.core

/**
 * Per-peer view kept by the local runtime.
 *
 * The view captures everything the local [dev.etdmnet.runtime.Session] needs
 * to make host-election decisions about a single remote peer:
 *
 *  * an EWMA-smoothed quality score and its sigmoid confidence,
 *  * the most recent successful interaction tick (used by the heartbeat
 *    timeout that powers fail-over),
 *  * the running protocol session counter `k` used to filter stale messages
 *    after a migration, and
 *  * the remote peer's most recent self-reported [Role] and host epoch.
 */
class PeerView(
    val peerId: PeerId,
    config: EtdmConfig,
) {
    val score: EwmaScore = EwmaScore(config.lambda, config.alpha)

    var role: Role = Role.CLIENT
        private set

    var lastSeenTick: Long = 0
        private set

    var lastSample: HealthSample? = null
        private set

    var advertisedHostEpoch: Long = 0
        private set

    var lastHandshakeK: Long = 0
        internal set

    val confidence: Double get() = score.confidence

    val raw: Double get() = score.raw

    fun observe(sample: HealthSample, tick: Long, weights: HealthWeights) {
        val obs = sample.score(weights)
        score.update(obs)
        if (sample.packetDeliveredThisTick) lastSeenTick = tick
        lastSample = sample
    }

    fun observeMissed() {
        score.update(-1.0)
    }

    fun markRole(newRole: Role, epoch: Long) {
        role = newRole
        if (epoch > advertisedHostEpoch) advertisedHostEpoch = epoch
    }

    fun markSeen(tick: Long) {
        lastSeenTick = tick
    }

    fun decay(amount: Double = 0.1) {
        score.decay(amount)
    }
}
