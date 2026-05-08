package dev.etdmnet.signaling

import dev.etdmnet.core.PeerId

/**
 * Minimal rendezvous interface.
 *
 * ETDM-Net targets a relay-free data plane, but two peers must still exchange
 * an initial connection descriptor (an SDP offer/answer for WebRTC, or a
 * pre-shared QR code, deep link, BLE advertisement, etc.). This is the
 * narrowest contract that makes that possible without forcing a TURN-like
 * always-on server.
 *
 * Reference implementations:
 *  * `QrSignaling` — out-of-band exchange via QR codes / deep links.
 *  * `LanSignaling` — mDNS/Bonjour discovery on a shared Wi-Fi.
 *  * `EphemeralSignaling` — short-lived rendezvous; the connector is dropped
 *    once a peer's first HELLO is received.
 */
interface Signaling {
    /** Publish our descriptor under [sessionId]. */
    fun publish(sessionId: String, peerId: PeerId, descriptor: ByteArray)

    /** Fetch all known descriptors for [sessionId]. */
    fun lookup(sessionId: String): Map<PeerId, ByteArray>

    /** Notify the signaler that the data-plane is up and the descriptor can be retired. */
    fun release(sessionId: String, peerId: PeerId)
}
