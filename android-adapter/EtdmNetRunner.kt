// ─────────────────────────────────────────────────────────────────────────────
// EtdmNetRunner.kt
//
// KOPYALA → oyunun Android modülüne
//
// Session tick döngüsünü bir Kotlin coroutine içinde çalıştırır.
// Activity/Fragment'tan StateFlow ile gözlemlenebilir.
// ─────────────────────────────────────────────────────────────────────────────

package /* oyununun paketi */ com.yourgame.network

import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.Role
import dev.etdmnet.runtime.HostSnapshot
import dev.etdmnet.runtime.Session
import dev.etdmnet.runtime.TickOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Oyunun "ağ yöneticisi" katmanında şu şekilde kullanılır:
 *
 * ```kotlin
 * class NetworkManager(context: Context) {
 *
 *     private val peerId = EtdmNet.newPeerId("player")
 *     private val link   = NearbyRawPeerLink(context, peerId)
 *     private val session = EtdmNet.createSession("room-123", link)
 *     private val runner  = EtdmNetRunner(session, lifecycleScope)
 *
 *     fun joinLobby() {
 *         link.start("room-123")
 *         runner.start()
 *     }
 *
 *     fun leaveGame() {
 *         runner.stop()
 *         link.stop()
 *     }
 * }
 * ```
 *
 * ViewModel veya Composable'da gözlemlemek için:
 * ```kotlin
 * runner.hostState.collectAsState()
 * runner.roleState.collectAsState()
 * ```
 */
class EtdmNetRunner(
    val session: Session,
    private val scope: CoroutineScope,
    private val config: EtdmConfig = session.config,
) {
    private var job: Job? = null

    private val _hostState = MutableStateFlow<HostSnapshot?>(null)
    val hostState: StateFlow<HostSnapshot?> get() = _hostState

    private val _roleState = MutableStateFlow(Role.CLIENT)
    val roleState: StateFlow<Role> get() = _roleState

    private val _appMessages = MutableStateFlow<List<ByteArray>>(emptyList())
    val appMessages: StateFlow<List<ByteArray>> get() = _appMessages

    val localPeerId: PeerId get() = session.localPeerId

    /** Game loop coroutine'ini başlatır. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val outcome: TickOutcome = session.tick()
                _hostState.value = outcome.host
                _roleState.value = outcome.role
                if (outcome.deliveredApplicationMessages.isNotEmpty()) {
                    _appMessages.value = outcome.deliveredApplicationMessages.map { it.payload }
                }
                delay(config.tickIntervalMs)
            }
        }
    }

    /** Game loop coroutine'ini durdurur. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Host isen tüm peer'lara oyun durumu gönder. */
    fun broadcastGameState(payload: ByteArray) {
        if (_roleState.value != Role.HOST) return
        for (peer in session.snapshot().keys) {
            session.sendApplication(peer, payload)
        }
    }

    /** Client isen host'a gönder (input, vs). */
    fun sendToHost(payload: ByteArray) = session.sendToHost(payload)

    /** Belirli bir peer'a gönder. */
    fun sendTo(target: PeerId, payload: ByteArray) = session.sendApplication(target, payload)
}
