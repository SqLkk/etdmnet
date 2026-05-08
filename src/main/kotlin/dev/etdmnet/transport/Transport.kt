package dev.etdmnet.transport

import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage

/**
 * Pluggable transport layer. ETDM-Net does not own the bytes-on-the-wire
 * problem; the application provides an implementation that may be backed by
 * WebRTC DataChannels, raw UDP, BLE mesh, or — in tests — an in-memory
 * loopback bus.
 *
 * The contract is intentionally thin and allocation-light:
 *
 *  * [send] is best-effort and non-blocking. Implementations MUST drop a
 *    message rather than block the caller.
 *  * [receive] returns all messages observed since the previous call. The
 *    runtime polls this once per tick.
 *  * [sample] is queried each tick to update the EWMA score for [target].
 *  * [knownPeers] reports the set of peers the transport currently believes
 *    are reachable. The runtime will not attempt to score peers it has never
 *    heard of via [knownPeers] or [receive].
 */
interface Transport {
    val localPeerId: PeerId

    fun knownPeers(): Set<PeerId>

    fun sample(target: PeerId): dev.etdmnet.core.HealthSample?

    fun send(message: PeerMessage): Boolean

    fun receive(): List<PeerMessage>

    /** Optional liveness signal raised when a peer's link is permanently lost. */
    fun onPeerLost(callback: (PeerId) -> Unit)
}
