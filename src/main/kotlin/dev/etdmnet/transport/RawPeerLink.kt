package dev.etdmnet.transport

import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId

/**
 * Real-network byte transport contract.
 *
 * Implement this in your game with WebRTC DataChannel, Android Nearby
 * Connections, local UDP, BLE, or any other peer-to-peer data plane. ETDM-Net
 * will encode/decode its own control messages and use [sample] every tick to
 * score the link.
 */
interface RawPeerLink {
    val localPeerId: PeerId

    fun connectedPeers(): Set<PeerId>

    fun sample(target: PeerId): HealthSample?

    fun send(target: PeerId, bytes: ByteArray): Boolean

    fun receive(): List<RawPacket>

    fun onPeerLost(callback: (PeerId) -> Unit)
}

data class RawPacket(
    val from: PeerId,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawPacket) return false
        return from == other.from && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * from.hashCode() + bytes.contentHashCode()
}
