package dev.etdmnet.runtime

import dev.etdmnet.core.PeerId
import dev.etdmnet.core.Role

/**
 * Authoritative session state shared by every peer.
 *
 * The triple `(hostId, hostEpoch, lastCommittedTick)` is the only state that
 * needs to be globally agreed upon for the multiplayer game to remain
 * consistent across host migrations. Every wire message references it; every
 * accepted [HostMigration] bumps `hostEpoch` strictly upwards.
 */
data class HostSnapshot(
    val hostId: PeerId?,
    val hostEpoch: Long,
    val lastCommittedTick: Long,
    val backupHostId: PeerId? = null,
)

/** Outcome of a tick of the runtime, surfaced to the application layer. */
data class TickOutcome(
    val role: Role,
    val host: HostSnapshot,
    val deliveredApplicationMessages: List<dev.etdmnet.core.PeerMessage.Application>,
    val sentMessages: Int,
    val migrated: Boolean,
)
