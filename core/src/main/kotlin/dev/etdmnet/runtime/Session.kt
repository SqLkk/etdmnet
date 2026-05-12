package dev.etdmnet.runtime

import dev.etdmnet.core.Clock
import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage
import dev.etdmnet.core.PeerView
import dev.etdmnet.core.Role
import dev.etdmnet.transport.Transport
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The local ETDM-Net runtime for a single peer.
 *
 * `Session` is the top-level entry point used by the application:
 *
 *  * [tick] is invoked by the application loop (or the optional internal
 *    scheduler) once every [EtdmConfig.tickIntervalMs]; it pumps the
 *    transport, updates EWMA scores, runs host-election, drives the
 *    PROPOSE/ACCEPT/CONFIRM handshake, and emits HEARTBEATs.
 *  * [sendApplication] enqueues a payload for delivery to a peer (or to the
 *    current host) on the next tick.
 *  * [stateFlow]-style observers may register callbacks via [onRoleChange]
 *    and [onHostChange].
 *
 * The runtime is deliberately single-threaded: it owns its mutable state and
 * is only touched from the application's network thread. Inbound messages and
 * outbound [sendApplication] calls are buffered through lock-free queues, so
 * the application may produce work from any thread.
 */
class Session(
    val sessionId: String,
    val transport: Transport,
    val clock: Clock,
    val config: EtdmConfig = EtdmConfig(),
    private val random: java.util.Random = SecureRandom(),
) {
    private val protocolVersion = PROTOCOL_VERSION

    val localPeerId: PeerId get() = transport.localPeerId

    var role: Role = Role.CLIENT
        private set

    var host: HostSnapshot = HostSnapshot(hostId = null, hostEpoch = 0L, lastCommittedTick = 0L)
        private set

    private val peers: MutableMap<PeerId, PeerView> = mutableMapOf()
    private val pendingOutbound = ConcurrentLinkedQueue<PeerMessage.Application>()
    private val handshake = HandshakeMachine(config, clock, random)
    private val elector = HostElector(config)

    private var currentTick: Long = 0
    private var lastHelloTick: Long = -config.helloIntervalTicks.toLong()
    private var lastHeartbeatTick: Long = -config.heartbeatIntervalTicks.toLong()
    private var localObservation: Double = 1.0

    private val roleListeners = mutableListOf<(Role) -> Unit>()
    private val hostListeners = mutableListOf<(HostSnapshot) -> Unit>()

    fun onRoleChange(listener: (Role) -> Unit) { roleListeners += listener }

    fun onHostChange(listener: (HostSnapshot) -> Unit) { hostListeners += listener }

    /** Snapshot of the per-peer view; useful for diagnostics and tests. */
    fun snapshot(): Map<PeerId, PeerView> = peers.toMap()

    fun sendApplication(target: PeerId, payload: ByteArray) {
        pendingOutbound.add(
            PeerMessage.Application(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                sender = localPeerId,
                sentAtTick = currentTick,
                target = target,
                payload = payload,
            ),
        )
    }

    /** Send a payload to whoever the runtime currently believes is the host. */
    fun sendToHost(payload: ByteArray): Boolean {
        val target = host.hostId ?: return false
        if (target == localPeerId) return false
        sendApplication(target, payload)
        return true
    }

    /**
     * Advance the runtime by one tick. Returns the application messages that
     * were delivered to the local peer in this tick.
     */
    fun tick(): TickOutcome {
        currentTick += 1
        val initialHost = host

        // 1. Sample the transport for known peers and update EWMA.
        observePeers()

        // 2. Drain inbound messages and react.
        val deliveredApp = mutableListOf<PeerMessage.Application>()
        for (msg in transport.receive()) {
            if (msg.protocolVersion != protocolVersion) continue
            if (msg.sessionId != sessionId) continue
            if (msg.sender == localPeerId) continue
            handleInbound(msg, deliveredApp)
        }

        // 3. Run timers (handshake timeouts, heartbeat liveness).
        val migrated = checkLivenessAndElect()

        // 4. Emit periodic traffic.
        var sent = emitPeriodic()

        // 5. Flush application payloads.
        sent += flushApplication()

        if (host != initialHost) hostListeners.forEach { it(host) }

        return TickOutcome(
            role = role,
            host = host,
            deliveredApplicationMessages = deliveredApp,
            sentMessages = sent,
            migrated = migrated,
        )
    }

    private fun observePeers() {
        val knownNow = transport.knownPeers()
        for (id in knownNow) {
            if (id == localPeerId) continue
            val view = peers.getOrPut(id) { PeerView(id, config) }
            val sample = transport.sample(id)
            if (sample == null) {
                view.observeMissed()
            } else {
                view.observe(sample, currentTick, config.weights)
            }
        }
        // Decay scores for peers we no longer hear from at all.
        for ((id, view) in peers) {
            if (id !in knownNow) view.decay(amount = 0.05)
        }
        // Self-confidence: we cannot directly observe how our peers perceive us,
        // so we use the average confidence our peers have accumulated about *their*
        // links as a symmetric proxy. With symmetric links every peer ends up with
        // the same self-confidence, which makes [HostElector]'s lexicographic
        // tie-break a deterministic convergence rule across all peers.
        localObservation = if (peers.isEmpty()) {
            0.5
        } else {
            peers.values.map { it.confidence }.average()
        }
    }

    private fun handleInbound(
        msg: PeerMessage,
        deliveredApp: MutableList<PeerMessage.Application>,
    ) {
        val view = peers.getOrPut(msg.sender) { PeerView(msg.sender, config) }
        view.markSeen(currentTick)

        when (msg) {
            is PeerMessage.Hello -> {
                if (msg.epoch >= host.hostEpoch && msg.claimedHost != null) {
                    if (msg.epoch > host.hostEpoch) {
                        adoptHost(msg.claimedHost, msg.epoch, sourceTick = currentTick)
                    }
                }
            }
            is PeerMessage.HealthReport -> {
                // Future: gossip-style cross-peer reputation. For now we trust local samples.
            }
            is PeerMessage.Heartbeat -> {
                if (msg.hostEpoch > host.hostEpoch) {
                    adoptHost(msg.hostId, msg.hostEpoch, sourceTick = msg.gameTick, backupHostId = msg.backupHostId)
                } else if (msg.hostEpoch == host.hostEpoch && msg.hostId == host.hostId) {
                    host = host.copy(
                        lastCommittedTick = maxOf(host.lastCommittedTick, msg.gameTick),
                        backupHostId = msg.backupHostId,
                    )
                    updateLocalRole()
                }
            }
            is PeerMessage.HostClaim -> {
                if (msg.newEpoch > host.hostEpoch) {
                    adoptHost(msg.sender, msg.newEpoch, sourceTick = currentTick)
                }
            }
            is PeerMessage.Propose,
            is PeerMessage.Accept,
            is PeerMessage.Confirm,
            is PeerMessage.Reject,
            -> handshake.handle(this, msg)
            is PeerMessage.Application -> {
                if (msg.target == localPeerId) deliveredApp += msg
            }
        }
    }

    private fun checkLivenessAndElect(): Boolean {
        val incumbent = host.hostId
        val incumbentHealthy = when {
            incumbent == null -> false
            incumbent == localPeerId -> true
            else -> {
                val view = peers[incumbent]
                view != null &&
                    (currentTick - view.lastSeenTick) <= config.heartbeatTimeoutTicks &&
                    view.confidence >= config.minHostConfidence * 0.5
            }
        }

                if (incumbent == localPeerId && incumbentHealthy) return false

        val backup = host.backupHostId
        val recommended = if (!incumbentHealthy && backup != null && backup != incumbent && isHostEligible(backup)) {
            backup
        } else elector.recommend(
            localPeerId = localPeerId,
            localConfidence = localObservation,
            peers = peers,
            currentHost = incumbent,
            currentHostHealthy = incumbentHealthy,
        )

        if (recommended == null) return false
        if (recommended == incumbent && incumbentHealthy) return false

        // Migration!
        val newEpoch = host.hostEpoch + 1
        val previousHost = incumbent
        adoptHost(recommended, newEpoch, sourceTick = currentTick)
        if (recommended == localPeerId) {
            transport.send(
                PeerMessage.HostClaim(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    sender = localPeerId,
                    sentAtTick = currentTick,
                    previousHost = previousHost,
                    previousEpoch = host.hostEpoch - 1,
                    newEpoch = newEpoch,
                    claimantConfidence = localObservation,
                ),
            )
        }
        return true
    }

    private fun emitPeriodic(): Int {
        var sent = 0
        if (currentTick - lastHelloTick >= config.helloIntervalTicks) {
            transport.send(
                PeerMessage.Hello(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    sender = localPeerId,
                    sentAtTick = currentTick,
                    epoch = host.hostEpoch,
                    claimedHost = host.hostId,
                ),
            )
            lastHelloTick = currentTick
            sent += 1
        }
        if (role == Role.HOST && currentTick - lastHeartbeatTick >= config.heartbeatIntervalTicks) {
            host = host.copy(backupHostId = selectBackupHost())
            transport.send(
                PeerMessage.Heartbeat(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    sender = localPeerId,
                    sentAtTick = currentTick,
                    hostEpoch = host.hostEpoch,
                    hostId = localPeerId,
                    backupHostId = host.backupHostId,
                    gameTick = currentTick,
                ),
            )
            lastHeartbeatTick = currentTick
            sent += 1
        }
        return sent
    }

    private fun flushApplication(): Int {
        var sent = 0
        while (true) {
            val msg = pendingOutbound.poll() ?: break
            if (transport.send(msg)) sent += 1
        }
        return sent
    }

    private fun adoptHost(newHost: PeerId, newEpoch: Long, sourceTick: Long, backupHostId: PeerId? = null) {
        if (newEpoch < host.hostEpoch) return
        val previous = host
        host = host.copy(
            hostId = newHost,
            hostEpoch = newEpoch,
            lastCommittedTick = maxOf(host.lastCommittedTick, sourceTick),
            backupHostId = backupHostId?.takeIf { it != newHost }
                ?: previous.backupHostId?.takeIf { it != newHost }
                ?: previous.hostId?.takeIf { it != newHost },
        )
        peers[newHost]?.markRole(Role.HOST, newEpoch)
        if (previous.hostId != null && previous.hostId != newHost) {
            peers[previous.hostId]?.markRole(Role.CLIENT, newEpoch)
        }
        updateLocalRole()
    }

    private fun isHostEligible(peerId: PeerId): Boolean {
        if (peerId == localPeerId) return localObservation >= config.minHostConfidence
        return (peers[peerId]?.confidence ?: 0.0) >= config.minHostConfidence * 0.5
    }

    private fun selectBackupHost(): PeerId? {
        val hostId = host.hostId
        return peers.values
            .asSequence()
            .filter { it.peerId != hostId }
            .filter { it.confidence >= config.minHostConfidence * 0.5 }
            .maxWithOrNull(compareBy<PeerView> { it.confidence }.thenBy { it.peerId })
            ?.peerId
    }

    private fun updateLocalRole() {
        val previousRole = role
        role = when (localPeerId) {
            host.hostId -> Role.HOST
            host.backupHostId -> Role.BACKUP_HOST
            else -> Role.CLIENT
        }
        if (role != previousRole) roleListeners.forEach { it(role) }
    }

    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}
