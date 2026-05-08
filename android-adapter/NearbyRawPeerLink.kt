// ─────────────────────────────────────────────────────────────────────────────
// NearbyRawPeerLink.kt
//
// KOPYALA → oyunun Android modülüne (app/src/main/kotlin/... veya java/...)
//
// Gerekli Gradle bağımlılıkları (oyunun app/build.gradle.kts):
//   implementation("com.google.android.gms:play-services-nearby:19.3.0")
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
//   implementation("com.github.SqLkk:etdmnew:0.1.0")   ← JitPack
//
// AndroidManifest.xml izinleri (Nearby Connections için):
//   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
//   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
//   <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
//   <uses-permission android:name="android.permission.BLUETOOTH"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
//   <!-- Android 12+ -->
//   <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
//   <!-- Android 13+ -->
//   <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"/>
// ─────────────────────────────────────────────────────────────────────────────

package /* oyununun paketi */ com.yourgame.network

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.transport.RawPacket
import dev.etdmnet.transport.RawPeerLink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android Nearby Connections adapter for RawPeerLink.
 *
 * Strateji P2P_STAR: bir peer host, diğerleri spoke. Oyun lobisi
 * için uygundur; salt-data trafiği relay GEÇMEKSİZİN nokta-nokta
 * akar çünkü Nearby Connections kendisi bir P2P WiFi-Direct katmanı
 * üzerinden çalışır.
 *
 * Kullanım:
 *   val link = NearbyRawPeerLink(context, PeerId.random("player"))
 *   link.start(gameSessionId)        // lobide
 *   val session = EtdmNet.createSession(gameSessionId, link)
 *   // ... game loop calls session.tick() ...
 *   link.stop()                      // oyun bitince
 */
class NearbyRawPeerLink(
    context: Context,
    override val localPeerId: PeerId,
) : RawPeerLink {

    private val client = Nearby.getConnectionsClient(context)

    // endpointId (Nearby) ↔ PeerId (etdmnet)
    private val endpointToPeer = ConcurrentHashMap<String, PeerId>()
    private val peerToEndpoint = ConcurrentHashMap<PeerId, String>()

    // Bağlantı kuruldu ama PeerId henüz bildirilmedi (handshake bekleniyor)
    private val pendingEndpoints = ConcurrentHashMap<String, Boolean>()

    private val inbox = ConcurrentLinkedQueue<RawPacket>()
    private val lostCallbacks = CopyOnWriteArrayList<(PeerId) -> Unit>()

    // Basit RTT izleme: ping mesajı gönderildiğinde timestamp kaydediyoruz.
    // etdmnet'in kendi Heartbeat mekanizması zaten latency proxy görevi görür;
    // bu sadece HealthSample için hammadde.
    private val rttEstimates = ConcurrentHashMap<PeerId, Double>()
    private val lossCounters = ConcurrentHashMap<PeerId, Pair<Int, Int>>() // sent / delivered

    // ─────────────────────────────────────────────────────────────────────────
    // RawPeerLink API
    // ─────────────────────────────────────────────────────────────────────────

    override fun connectedPeers(): Set<PeerId> = peerToEndpoint.keys.toSet()

    override fun sample(target: PeerId): HealthSample? {
        if (target !in peerToEndpoint) return null
        val (sent, delivered) = lossCounters[target] ?: Pair(1, 1)
        val lossRate = if (sent == 0) 0.0 else 1.0 - delivered.toDouble() / sent
        return HealthSample(
            rttMs = rttEstimates[target] ?: 80.0,
            lossRate = lossRate.coerceIn(0.0, 1.0),
            jitterMs = (rttEstimates[target] ?: 80.0) * 0.15,
            batteryPercent = 1.0,   // gerçek uygulamada BatteryManager'dan al
            directReachable = true,
            packetDeliveredThisTick = true,
            natFriendly = true,
        )
    }

    override fun send(target: PeerId, bytes: ByteArray): Boolean {
        val endpointId = peerToEndpoint[target] ?: return false
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
        lossCounters.compute(target) { _, v -> Pair((v?.first ?: 0) + 1, v?.second ?: 0) }
        return true
    }

    override fun receive(): List<RawPacket> {
        val out = mutableListOf<RawPacket>()
        while (true) out += inbox.poll() ?: break
        return out
    }

    override fun onPeerLost(callback: (PeerId) -> Unit) {
        lostCallbacks += callback
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun start(gameId: String) {
        val advOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()

        client.startAdvertising(
            localPeerId.value,   // endpointName = PeerId so remote can parse it
            gameId,
            connectionLifecycleCallback,
            advOptions,
        ).addOnFailureListener { Log.e(TAG, "Advertising failed: $it") }

        client.startDiscovery(gameId, endpointDiscoveryCallback, discOptions)
            .addOnFailureListener { Log.e(TAG, "Discovery failed: $it") }

        Log.i(TAG, "NearbyRawPeerLink started for $localPeerId in session $gameId")
    }

    fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        endpointToPeer.clear()
        peerToEndpoint.clear()
        pendingEndpoints.clear()
        inbox.clear()
        lossCounters.clear()
        rttEstimates.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nearby callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found endpoint $endpointId (${info.endpointName}), requesting connection…")
            client.requestConnection(localPeerId.value, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { Log.w(TAG, "requestConnection failed: $it") }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint $endpointId during discovery")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept; the ETDM handshake handles authentication.
            client.acceptConnection(endpointId, payloadCallback)
            pendingEndpoints[endpointId] = true
            // Peer's PeerId is encoded in the endpointName.
            runCatching { PeerId(info.endpointName) }.getOrNull()?.let { remotePeerId ->
                endpointToPeer[endpointId] = remotePeerId
                peerToEndpoint[remotePeerId] = endpointId
                pendingEndpoints.remove(endpointId)
                Log.i(TAG, "Connection initiated with $remotePeerId ($endpointId)")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val peerId = endpointToPeer[endpointId]
                    Log.i(TAG, "Connected to $peerId ($endpointId)")
                }
                else -> {
                    Log.w(TAG, "Connection failed for $endpointId: ${result.status}")
                    cleanup(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = endpointToPeer[endpointId]
            Log.i(TAG, "Disconnected from $peerId ($endpointId)")
            cleanup(endpointId)
            peerId?.let { id -> lostCallbacks.forEach { it(id) } }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val peerId = endpointToPeer[endpointId] ?: return
            inbox.add(RawPacket(peerId, bytes))
            // Count as delivered for loss estimation
            lossCounters.compute(peerId) { _, v -> Pair(v?.first ?: 0, (v?.second ?: 0) + 1) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Could use transfer timing for RTT heuristic. Skipped for brevity.
        }
    }

    private fun cleanup(endpointId: String) {
        val peerId = endpointToPeer.remove(endpointId)
        if (peerId != null) peerToEndpoint.remove(peerId)
        pendingEndpoints.remove(endpointId)
    }

    companion object {
        private const val TAG = "NearbyRawPeerLink"
    }
}
