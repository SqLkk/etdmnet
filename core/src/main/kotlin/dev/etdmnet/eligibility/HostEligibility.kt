package dev.etdmnet.eligibility

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Detected NAT category for a peer.
 *
 * Library users (or wrapping apps) can decide host eligibility from this:
 *   - [OPEN], [FULL_CONE], [RESTRICTED_CONE] → great host candidate
 *   - [PORT_RESTRICTED]                      → usually OK, may fail with strict peers
 *   - [SYMMETRIC]                            → cannot host without a TURN relay
 *   - [BLOCKED]                              → no UDP egress at all (corporate / hotspot)
 *   - [UNKNOWN]                              → probe failed (timeout, no STUN servers reachable)
 */
enum class NatType {
    OPEN,
    FULL_CONE,
    RESTRICTED_CONE,
    PORT_RESTRICTED,
    SYMMETRIC,
    BLOCKED,
    UNKNOWN;
}

/**
 * Verdict for whether the local peer is fit to be elected ETDM host.
 *
 * Apps should surface [reason] to the user in their lobby UI.
 */
enum class HostVerdict {
    /** Excellent host — direct P2P will work for almost any remote peer. */
    EXCELLENT,
    /** Good host — direct P2P works for most peers; may need TURN for very strict NATs. */
    GOOD,
    /** Marginal — can host but expect ~50% of strangers to fail without TURN. */
    MARGINAL,
    /** Not eligible — cannot accept inbound connections; should never be elected host. */
    INELIGIBLE,
    /** Probe failed; eligibility unknown. App may retry or fall back to UNKNOWN policy. */
    UNKNOWN;
}

/**
 * Full result of an eligibility probe.
 *
 * @property nat                detected NAT type
 * @property verdict            human-actionable verdict
 * @property publicAddress      public IP:port observed via STUN (null if probe failed)
 * @property reason             short Turkish-friendly explanation suitable for UI ("Operatörünüz çok katı NAT kullanıyor…")
 * @property hostScoreHint      0..100 score the app can advertise so other peers prefer better hosts
 * @property probeMillis        how long the probe took
 */
data class EligibilityReport(
    val nat: NatType,
    val verdict: HostVerdict,
    val publicAddress: String?,
    val reason: String,
    val hostScoreHint: Int,
    val probeMillis: Long,
)

/**
 * Quick STUN-based NAT detector. Pure JVM (UDP `DatagramSocket`), so it works
 * on plain JVM and on Android (Android allows UDP from the main process when
 * `INTERNET` permission is granted).
 *
 * Usage:
 * ```kotlin
 * val report = HostEligibility.probe()
 * if (report.verdict == HostVerdict.INELIGIBLE) {
 *     ui.showError(report.reason)   // disable "Oda Kur" button
 * }
 * ```
 */
object HostEligibility {

    /** Default STUN servers used by the probe. */
    val DEFAULT_STUN_SERVERS: List<String> = listOf(
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun.cloudflare.com:3478",
    )

    private const val STUN_MAGIC_COOKIE = 0x2112A442.toInt()
    private const val BINDING_REQUEST = 0x0001.toShort()
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020.toShort()
    private const val ATTR_MAPPED_ADDRESS = 0x0001.toShort()
    private val rng = SecureRandom()

    /**
     * Probe the local NAT.
     *
     * @param stunServers list of `host:port` STUN servers; defaults to public Google + Cloudflare.
     * @param timeoutMillis hard cap for the whole probe (recommend 2-4 seconds for UI flows).
     */
    suspend fun probe(
        stunServers: List<String> = DEFAULT_STUN_SERVERS,
        timeoutMillis: Long = 3000L,
    ): EligibilityReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val outcome = withTimeoutOrNull(timeoutMillis) {
            runProbe(stunServers)
        } ?: ProbeOutcome(NatType.UNKNOWN, publicAddress = null)

        val elapsed = System.currentTimeMillis() - started
        outcome.toReport(elapsed)
    }

    // ────────────────────────── internals ──────────────────────────

    private data class ProbeOutcome(
        val nat: NatType,
        val publicAddress: String?,
    ) {
        fun toReport(elapsedMs: Long): EligibilityReport {
            val (verdict, score, reason) = when (nat) {
                NatType.OPEN -> Triple(
                    HostVerdict.EXCELLENT, 100,
                    "Açık internet bağlantısı — host olmaya çok uygun.",
                )
                NatType.FULL_CONE -> Triple(
                    HostVerdict.EXCELLENT, 90,
                    "NAT türü uygun (Full Cone) — host olabilirsiniz.",
                )
                NatType.RESTRICTED_CONE -> Triple(
                    HostVerdict.GOOD, 75,
                    "NAT türü iyi (Restricted Cone) — çoğu oyuncu bağlanabilir.",
                )
                NatType.PORT_RESTRICTED -> Triple(
                    HostVerdict.MARGINAL, 55,
                    "NAT biraz katı (Port-Restricted). Bazı oyuncular bağlanamayabilir.",
                )
                NatType.SYMMETRIC -> Triple(
                    HostVerdict.INELIGIBLE, 5,
                    "Operatörünüz çok katı NAT (Symmetric) kullanıyor. " +
                        "Host olamazsınız; başka bir oyuncunun host olmasını bekleyin " +
                        "ya da Wi-Fi'ye bağlanın.",
                )
                NatType.BLOCKED -> Triple(
                    HostVerdict.INELIGIBLE, 0,
                    "UDP trafiği engellenmiş. Bu ağda P2P çalışmaz; " +
                        "Wi-Fi'ye geçin veya mobil veriyi deneyin.",
                )
                NatType.UNKNOWN -> Triple(
                    HostVerdict.UNKNOWN, 30,
                    "Ağ kontrolü tamamlanamadı (zaman aşımı). Yine de deneyebilirsiniz.",
                )
            }
            return EligibilityReport(
                nat = nat,
                verdict = verdict,
                publicAddress = publicAddress,
                reason = reason,
                hostScoreHint = score,
                probeMillis = elapsedMs,
            )
        }
    }

    private fun runProbe(stunServers: List<String>): ProbeOutcome {
        // Step 1: bind a socket on an ephemeral port and ask the first STUN server
        //         what our public mapping looks like.
        val socketA = try {
            DatagramSocket()
        } catch (_: Exception) {
            return ProbeOutcome(NatType.BLOCKED, null)
        }
        socketA.soTimeout = 800

        val server1 = parseServers(stunServers).firstOrNull()
            ?: return ProbeOutcome(NatType.UNKNOWN, null).also { socketA.close() }

        val mappingA = stunQuery(socketA, server1)
        if (mappingA == null) {
            socketA.close()
            return ProbeOutcome(NatType.BLOCKED, null)
        }

        // If public address equals local address, we're directly on the internet.
        if (isSameEndpoint(socketA.localSocketAddress, mappingA)) {
            socketA.close()
            return ProbeOutcome(NatType.OPEN, mappingA.toCanonical())
        }

        // Step 2: ask a *different* STUN server from the SAME socket. If the public
        //         port changes, we are behind a Symmetric NAT — disqualify as host.
        val server2 = parseServers(stunServers).drop(1).firstOrNull()
        if (server2 != null) {
            val mappingB = stunQuery(socketA, server2)
            if (mappingB != null && mappingB.port != mappingA.port) {
                socketA.close()
                return ProbeOutcome(NatType.SYMMETRIC, mappingA.toCanonical())
            }
        }

        // Step 3: heuristic for cone-NAT subclass. Without RFC 5780 CHANGE-REQUEST
        //         support (rarely deployed on public STUN today) we can't fully
        //         distinguish full/restricted/port-restricted, so report the
        //         most common case for consumer routers.
        socketA.close()
        return ProbeOutcome(NatType.PORT_RESTRICTED, mappingA.toCanonical())
    }

    private fun parseServers(list: List<String>): List<InetSocketAddress> =
        list.mapNotNull { hp ->
            val idx = hp.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val host = hp.substring(0, idx)
            val port = hp.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (_: Exception) {
                null
            }
        }

    private fun isSameEndpoint(local: java.net.SocketAddress, mapped: InetSocketAddress): Boolean {
        val l = local as? InetSocketAddress ?: return false
        return l.port == mapped.port && !l.address.isAnyLocalAddress &&
            l.address.hostAddress == mapped.address.hostAddress
    }

    private fun InetSocketAddress.toCanonical(): String =
        "${address.hostAddress}:${port}"

    /** Build a STUN Binding Request, send it, return the mapped address (or null). */
    private fun stunQuery(socket: DatagramSocket, server: InetSocketAddress): InetSocketAddress? {
        val txId = ByteArray(12).also(rng::nextBytes)
        val req = buildBindingRequest(txId)
        return try {
            socket.send(DatagramPacket(req, req.size, server))
            val buf = ByteArray(512)
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            parseMappedAddress(buf, pkt.length, txId)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBindingRequest(txId: ByteArray): ByteArray {
        val out = ByteArray(20)
        // Message type (BINDING_REQUEST)
        out[0] = (BINDING_REQUEST.toInt() ushr 8).toByte()
        out[1] = BINDING_REQUEST.toInt().toByte()
        // Length (no attributes)
        out[2] = 0; out[3] = 0
        // Magic cookie
        out[4] = (STUN_MAGIC_COOKIE ushr 24).toByte()
        out[5] = (STUN_MAGIC_COOKIE ushr 16).toByte()
        out[6] = (STUN_MAGIC_COOKIE ushr 8).toByte()
        out[7] = STUN_MAGIC_COOKIE.toByte()
        System.arraycopy(txId, 0, out, 8, 12)
        return out
    }

    private fun parseMappedAddress(buf: ByteArray, len: Int, txId: ByteArray): InetSocketAddress? {
        if (len < 20) return null
        // Validate header magic cookie + transaction id
        val msgLen = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
        if (20 + msgLen > len) return null
        for (i in 0 until 12) {
            if (buf[8 + i] != txId[i]) return null
        }

        var off = 20
        val end = 20 + msgLen
        while (off + 4 <= end) {
            val type = (((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)).toShort()
            val attrLen = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
            val payload = off + 4
            if (payload + attrLen > end) return null

            when (type) {
                ATTR_XOR_MAPPED_ADDRESS -> return parseXorMapped(buf, payload, attrLen, txId)
                ATTR_MAPPED_ADDRESS -> return parsePlainMapped(buf, payload, attrLen)
            }
            // Attributes are 4-byte aligned
            off = payload + ((attrLen + 3) and 0x3.inv())
        }
        return null
    }

    private fun parseXorMapped(buf: ByteArray, off: Int, len: Int, txId: ByteArray): InetSocketAddress? {
        if (len < 8) return null
        val family = buf[off + 1].toInt() and 0xff
        val xport = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
        val port = xport xor (STUN_MAGIC_COOKIE ushr 16)
        return when (family) {
            0x01 -> { // IPv4
                if (len < 8) return null
                val addr = ByteArray(4)
                for (i in 0 until 4) {
                    val cookieByte = (STUN_MAGIC_COOKIE ushr (24 - i * 8)) and 0xff
                    addr[i] = ((buf[off + 4 + i].toInt() and 0xff) xor cookieByte).toByte()
                }
                InetSocketAddress(InetAddress.getByAddress(addr), port)
            }
            0x02 -> { // IPv6
                if (len < 20) return null
                val addr = ByteArray(16)
                for (i in 0 until 16) {
                    val mask = if (i < 4)
                        (STUN_MAGIC_COOKIE ushr (24 - i * 8)) and 0xff
                    else
                        txId[i - 4].toInt() and 0xff
                    addr[i] = ((buf[off + 4 + i].toInt() and 0xff) xor mask).toByte()
                }
                InetSocketAddress(InetAddress.getByAddress(addr), port)
            }
            else -> null
        }
    }

    private fun parsePlainMapped(buf: ByteArray, off: Int, len: Int): InetSocketAddress? {
        if (len < 8) return null
        val family = buf[off + 1].toInt() and 0xff
        val port = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
        return when (family) {
            0x01 -> {
                val addr = ByteArray(4)
                System.arraycopy(buf, off + 4, addr, 0, 4)
                InetSocketAddress(InetAddress.getByAddress(addr), port)
            }
            0x02 -> {
                if (len < 20) return null
                val addr = ByteArray(16)
                System.arraycopy(buf, off + 4, addr, 0, 16)
                InetSocketAddress(InetAddress.getByAddress(addr), port)
            }
            else -> null
        }
    }
}
