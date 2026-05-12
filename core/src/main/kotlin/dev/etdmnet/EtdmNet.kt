package dev.etdmnet

import dev.etdmnet.core.Clock
import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.Role
import dev.etdmnet.core.SystemClock
import dev.etdmnet.runtime.HostSnapshot
import dev.etdmnet.runtime.Session
import dev.etdmnet.transport.EncodedTransport
import dev.etdmnet.transport.RawPeerLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level entry point for `etdmnet`.
 *
 * Anyone can "host" — host is **elected** among joined peers. So [join] is the
 * only call most applications need; if the elected host disappears, another
 * peer takes over transparently.
 *
 * Typical usage:
 * ```kotlin
 * val transport: RawPeerLink = WebRtcTransport.connect(roomId = "lobby-1", ...)
 * val net = EtdmNet.join(roomId = "lobby-1", transport = transport)
 *
 * net.role.collect { role -> println("I am now $role") }
 * net.messages.collect { msg -> handle(msg.from, msg.payload) }
 *
 * net.broadcast(myBytes)      // CLIENT → sends to current host
 * net.publishAsHost(myBytes)  // HOST   → sends to all peers
 * ```
 */
object EtdmNet {
    fun newPeerId(prefix: String = "player"): PeerId = PeerId.random(prefix)

    /**
     * Convenience: probe the local NAT/operator and report whether this peer
     * can realistically be elected ETDM host.
     *
     * Apps should call this once when the user enters the multiplayer lobby
     * and gate their "Host a Room" button on the result. See
     * [dev.etdmnet.eligibility.HostEligibility] for the full API.
     *
     * ```kotlin
     * val report = EtdmNet.checkHostEligibility()
     * if (report.verdict == HostVerdict.INELIGIBLE) {
     *     ui.disableHostButton(report.reason)
     * }
     * ```
     */
    suspend fun checkHostEligibility(
        stunServers: List<String> = dev.etdmnet.eligibility.HostEligibility.DEFAULT_STUN_SERVERS,
        timeoutMillis: Long = 3000L,
    ): dev.etdmnet.eligibility.EligibilityReport =
        dev.etdmnet.eligibility.HostEligibility.probe(stunServers, timeoutMillis)

    /**
     * Low-level session factory. Most apps should use [join] instead — it
     * wraps the session in [EtdmHost] with auto-tick and StateFlow observers.
     * Exposed for advanced use cases (custom tick loops, testing, etc.).
     */
    fun createSession(
        sessionId: String,
        link: RawPeerLink,
        clock: Clock = SystemClock,
        config: EtdmConfig = EtdmConfig(),
    ): Session = Session(
        sessionId = sessionId,
        transport = EncodedTransport(link),
        clock = clock,
        config = config,
    )

    /**
     * Start a peer in [roomId]. Returns a running [EtdmHost] — call [EtdmHost.close]
     * when the player leaves.
     *
     * @param scope coroutine scope that owns the tick loop. If null, an internal
     *  supervisor scope is created and cancelled on [EtdmHost.close].
     */
    fun join(
        roomId: String,
        transport: RawPeerLink,
        scope: CoroutineScope? = null,
        config: EtdmConfig = EtdmConfig(),
        clock: Clock = SystemClock,
    ): EtdmHost {
        val session = Session(
            sessionId = roomId,
            transport = EncodedTransport(transport),
            clock = clock,
            config = config,
        )
        return EtdmHost(session, transport, scope)
    }
}

/**
 * A running ETDM-Net peer.
 *
 * Methods on this class are thread-safe. The internal tick loop runs on
 * [Dispatchers.Default]; observers receive callbacks on that dispatcher.
 */
class EtdmHost internal constructor(
    private val session: Session,
    private val rawLink: RawPeerLink,
    externalScope: CoroutineScope?,
) : AutoCloseable {

    private val ownedScope: CoroutineScope? =
        if (externalScope == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    private val scope: CoroutineScope = externalScope ?: ownedScope!!

    private val _role = MutableStateFlow(Role.CLIENT)
    /** Current role of *this* peer: `HOST`, `BACKUP_HOST`, or `CLIENT`. */
    val role: StateFlow<Role> = _role.asStateFlow()

    private val _host = MutableStateFlow<HostSnapshot?>(null)
    /** Snapshot of who the runtime believes is the current host. */
    val host: StateFlow<HostSnapshot?> = _host.asStateFlow()

    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())
    /** Currently connected peers (does not include self). */
    val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 256)
    /** Application messages delivered to this peer. */
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    /** This peer's identifier (stable for the life of [EtdmHost]). */
    val localPeerId: PeerId get() = session.localPeerId

    /** `true` if the runtime currently elects us as host. */
    val isHost: Boolean get() = _role.value == Role.HOST

    private var tickJob: Job? = null

    init {
        tickJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val outcome = session.tick()
                _role.value = outcome.role
                _host.value = outcome.host
                _peers.value = rawLink.connectedPeers()
                for (msg in outcome.deliveredApplicationMessages) {
                    _messages.emit(IncomingMessage(msg.sender, msg.payload))
                }
                delay(session.config.tickIntervalMs)
            }
        }
    }

    /**
     * Send [payload] to the current host. If *we* are the host, the message
     * is delivered locally on the next tick. Returns `false` if no host is
     * elected yet.
     */
    fun broadcast(payload: ByteArray): Boolean {
        if (isHost) {
            _messages.tryEmit(IncomingMessage(localPeerId, payload))
            return true
        }
        return session.sendToHost(payload)
    }

    /**
     * Send [payload] to every other peer. Intended for the elected host to
     * broadcast game state. No-op if we are not currently the host (use
     * [forcePublish] to bypass the check).
     */
    fun publishAsHost(payload: ByteArray): Int {
        if (!isHost) return 0
        return forcePublish(payload)
    }

    /** Send [payload] to all peers regardless of role. */
    fun forcePublish(payload: ByteArray): Int {
        var n = 0
        for (peer in rawLink.connectedPeers()) {
            if (peer == localPeerId) continue
            session.sendApplication(peer, payload)
            n++
        }
        return n
    }

    /** Send [payload] to a specific peer. */
    fun sendTo(target: PeerId, payload: ByteArray) {
        session.sendApplication(target, payload)
    }

    /** Stop the tick loop and release the underlying transport's scope. */
    override fun close() {
        tickJob?.cancel()
        tickJob = null
        ownedScope?.cancel()
    }

    data class IncomingMessage(val from: PeerId, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IncomingMessage) return false
            return from == other.from && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * from.hashCode() + payload.contentHashCode()
    }
}
