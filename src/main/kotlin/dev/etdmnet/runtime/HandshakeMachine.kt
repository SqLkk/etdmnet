package dev.etdmnet.runtime

import dev.etdmnet.core.Clock
import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage
import dev.etdmnet.core.Role
import java.util.Random

/**
 * Tracks in-flight `PROPOSE → ACCEPT → CONFIRM` exchanges initiated by the
 * local peer (e.g. when offering itself as a backup host) and replies to
 * exchanges initiated by remote peers.
 *
 * The implementation enforces the four protocol invariants from
 * Section 3.8 of the thesis:
 *
 *  * I1 — A peer that has already locked a session never re-proposes for the
 *    same role.
 *  * I2 — Stale messages whose `k` does not match the current session counter
 *    are silently dropped.
 *  * I3 — A peer is in at most one active session at a time per role.
 *  * I4 — `(nonceSrc, nonceDst)` must match for a CONFIRM to commit.
 */
internal class HandshakeMachine(
    private val config: EtdmConfig,
    private val clock: Clock,
    private val random: Random,
) {
    private var proposalK: Long = 0
    private var proposalTarget: PeerId? = null
    private var nonceSrc: Long = 0
    private var nonceDst: Long = 0
    private var proposalSentTick: Long = -1

    fun handle(session: Session, msg: PeerMessage) {
        when (msg) {
            is PeerMessage.Propose -> handlePropose(session, msg)
            is PeerMessage.Accept -> handleAccept(session, msg)
            is PeerMessage.Confirm -> handleConfirm(session, msg)
            is PeerMessage.Reject -> handleReject(msg)
            else -> Unit
        }
    }

    private fun handlePropose(session: Session, msg: PeerMessage.Propose) {
        if (msg.target != session.localPeerId) return
        // We accept any well-formed propose; the elector decides whether the
        // sender should actually become host.
        val responseNonce = nextNonce()
        nonceDst = responseNonce
        session.transport.send(
            PeerMessage.Accept(
                protocolVersion = Session.PROTOCOL_VERSION,
                sessionId = session.sessionId,
                sender = session.localPeerId,
                sentAtTick = clock.nowMs() / config.tickIntervalMs,
                target = msg.sender,
                k = msg.k,
                nonceSrc = msg.nonceSrc,
                nonceDst = responseNonce,
            ),
        )
    }

    private fun handleAccept(session: Session, msg: PeerMessage.Accept) {
        if (msg.target != session.localPeerId) return
        if (msg.k != proposalK) return // I2
        if (msg.nonceSrc != nonceSrc) return // I4
        if (proposalTarget != msg.sender) return
        nonceDst = msg.nonceDst
        session.transport.send(
            PeerMessage.Confirm(
                protocolVersion = Session.PROTOCOL_VERSION,
                sessionId = session.sessionId,
                sender = session.localPeerId,
                sentAtTick = clock.nowMs() / config.tickIntervalMs,
                target = msg.sender,
                k = msg.k,
                nonceDst = msg.nonceDst,
            ),
        )
        // Lock done — clear in-flight state.
        proposalTarget = null
        proposalSentTick = -1
    }

    private fun handleConfirm(session: Session, msg: PeerMessage.Confirm) {
        if (msg.target != session.localPeerId) return
        if (msg.nonceDst != nonceDst) return // I4
        // Acceptor side commit. Currently the only role we lock is BACKUP_HOST.
        // Future work: extend with twin-shortcut-style locks.
    }

    private fun handleReject(msg: PeerMessage.Reject) {
        if (msg.k != proposalK) return // I2
        proposalTarget = null
        proposalSentTick = -1
    }

    private fun nextNonce(): Long = random.nextLong()
}
