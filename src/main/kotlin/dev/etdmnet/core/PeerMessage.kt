package dev.etdmnet.core

/**
 * ETDM-Net wire protocol messages.
 *
 * The encoding is deliberately payload-agnostic: each message is a sealed
 * class that the application serializes via the chosen [dev.etdmnet.transport.Transport].
 * For the reference loopback transport these are passed by reference; on
 * the wire they should be encoded with a stable, length-prefixed format.
 *
 * The protocol field is versioned. Peers MUST drop messages whose
 * [protocolVersion] does not match. This keeps `epoch`-based migration honest
 * even if two peers ship slightly different SDK versions.
 */
sealed interface PeerMessage {
    val protocolVersion: Int
    val sessionId: String
    val sender: PeerId
    val sentAtTick: Long

    /** Periodic announcement used during discovery. */
    data class Hello(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val epoch: Long,
        val claimedHost: PeerId?,
    ) : PeerMessage

    /** Encrypted health summary for a single observed peer. */
    data class HealthReport(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val confidence: Double,
        val sample: HealthSample,
    ) : PeerMessage

    /** PROPOSE — opening message of the three-way handshake. */
    data class Propose(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val k: Long,
        val nonceSrc: Long,
        val proposedRole: Role,
    ) : PeerMessage

    /** ACCEPT — receiver confirms it consents to the role. */
    data class Accept(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val k: Long,
        val nonceSrc: Long,
        val nonceDst: Long,
    ) : PeerMessage

    /** CONFIRM — proposer locks the session. */
    data class Confirm(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val k: Long,
        val nonceDst: Long,
    ) : PeerMessage

    /** REJECT — receiver declines a PROPOSE. */
    data class Reject(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val k: Long,
        val reason: String,
    ) : PeerMessage

    /**
     * HEARTBEAT — emitted by the current host every
     * [EtdmConfig.heartbeatIntervalTicks] ticks. The heartbeat carries the
     * latest committed [hostEpoch] so that late joiners and recovering peers
     * can resync without a separate handshake.
     */
    data class Heartbeat(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val hostEpoch: Long,
        val hostId: PeerId,
        val backupHostId: PeerId?,
        val gameTick: Long,
    ) : PeerMessage

    /** HOST_CLAIM — emitted by a backup that has decided to take over. */
    data class HostClaim(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val previousHost: PeerId?,
        val previousEpoch: Long,
        val newEpoch: Long,
        val claimantConfidence: Double,
    ) : PeerMessage

    /** Application payload — opaque to ETDM-Net. */
    data class Application(
        override val protocolVersion: Int,
        override val sessionId: String,
        override val sender: PeerId,
        override val sentAtTick: Long,
        val target: PeerId,
        val payload: ByteArray,
    ) : PeerMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Application) return false
            return protocolVersion == other.protocolVersion &&
                sessionId == other.sessionId &&
                sender == other.sender &&
                sentAtTick == other.sentAtTick &&
                target == other.target &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = protocolVersion
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + sender.hashCode()
            result = 31 * result + sentAtTick.hashCode()
            result = 31 * result + target.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}

/** Logical role a peer can hold in the session. */
enum class Role { CLIENT, BACKUP_HOST, HOST }
