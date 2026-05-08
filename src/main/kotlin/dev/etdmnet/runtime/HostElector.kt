package dev.etdmnet.runtime

import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerView

/**
 * Picks the best-eligible peer (including the local one) to act as host.
 *
 * The elector is deliberately stateless across calls except for its hysteresis
 * bookkeeping: only when a candidate maintains a strictly higher confidence
 * than the incumbent for [EtdmConfig.hostStabilityTicks] consecutive ticks does
 * the elector recommend a switch. This implements the same hysteresis rule
 * (`eta`) that protects the thesis discovery protocol from oscillation.
 */
class HostElector(private val config: EtdmConfig) {
    private var leadingCandidate: PeerId? = null
    private var leadingTicks: Int = 0

    /**
     * Compute the recommended host given the current view of the world.
     *
     * @param localPeerId the local peer's id; included as a candidate
     * @param localConfidence local self-confidence (typically the running
     *   average of locally observed sample scores)
     * @param peers remote peers with their EWMA-derived confidence
     * @param currentHost the host id currently agreed upon, if any
     * @param currentHostHealthy whether [currentHost] is still considered live
     */
    fun recommend(
        localPeerId: PeerId,
        localConfidence: Double,
        peers: Map<PeerId, PeerView>,
        currentHost: PeerId?,
        currentHostHealthy: Boolean,
    ): PeerId? {
        if (currentHost != null && currentHostHealthy) {
            // Stickiness: keep host until it fails or another peer wins for `hostStabilityTicks`.
            val incumbentConfidence = if (currentHost == localPeerId) {
                localConfidence
            } else {
                peers[currentHost]?.confidence ?: 0.0
            }
            val challenger = bestCandidate(localPeerId, localConfidence, peers, exclude = currentHost)
            if (challenger == null) {
                leadingCandidate = null
                leadingTicks = 0
                return currentHost
            }
            if (challenger.confidence < incumbentConfidence + config.eta) {
                leadingCandidate = null
                leadingTicks = 0
                return currentHost
            }
            if (leadingCandidate == challenger.peerId) {
                leadingTicks += 1
            } else {
                leadingCandidate = challenger.peerId
                leadingTicks = 1
            }
            return if (leadingTicks >= config.hostStabilityTicks) {
                leadingCandidate.also {
                    leadingCandidate = null
                    leadingTicks = 0
                }
            } else currentHost
        }

        // No incumbent (or incumbent is unhealthy): pick the best eligible peer immediately.
        val best = bestCandidate(localPeerId, localConfidence, peers, exclude = null)
        leadingCandidate = null
        leadingTicks = 0
        return best?.peerId ?: currentHost
    }

    private fun bestCandidate(
        localPeerId: PeerId,
        localConfidence: Double,
        peers: Map<PeerId, PeerView>,
        exclude: PeerId?,
    ): Candidate? {
        val candidates = mutableListOf<Candidate>()
        if (localPeerId != exclude && localConfidence >= config.minHostConfidence) {
            candidates += Candidate(localPeerId, localConfidence)
        }
        for ((id, view) in peers) {
            if (id == exclude) continue
            if (view.confidence < config.minHostConfidence) continue
            candidates += Candidate(id, view.confidence)
        }
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.confidence }.thenBy { it.peerId },
        )
    }

    private data class Candidate(val peerId: PeerId, val confidence: Double)
}
