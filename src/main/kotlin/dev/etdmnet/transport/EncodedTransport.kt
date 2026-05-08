package dev.etdmnet.transport

import dev.etdmnet.codec.PeerMessageCodec
import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage

/**
 * Bridges a real byte-oriented [RawPeerLink] into the object-oriented
 * [Transport] interface used by [dev.etdmnet.runtime.Session].
 */
class EncodedTransport(
    private val link: RawPeerLink,
) : Transport {
    override val localPeerId: PeerId get() = link.localPeerId

    override fun knownPeers(): Set<PeerId> = link.connectedPeers()

    override fun sample(target: PeerId): HealthSample? = link.sample(target)

    override fun send(message: PeerMessage): Boolean {
        val bytes = PeerMessageCodec.encode(message)
        val target = directedTarget(message)
        return if (target != null) {
            if (target == localPeerId) true else link.send(target, bytes)
        } else {
            var delivered = false
            for (peer in link.connectedPeers()) {
                delivered = link.send(peer, bytes) || delivered
            }
            delivered
        }
    }

    override fun receive(): List<PeerMessage> {
        return link.receive().mapNotNull { packet ->
            val decoded = PeerMessageCodec.decode(packet.bytes) ?: return@mapNotNull null
            if (decoded.sender == packet.from) decoded else null
        }
    }

    override fun onPeerLost(callback: (PeerId) -> Unit) = link.onPeerLost(callback)

    private fun directedTarget(message: PeerMessage): PeerId? = when (message) {
        is PeerMessage.Accept -> message.target
        is PeerMessage.Application -> message.target
        is PeerMessage.Confirm -> message.target
        is PeerMessage.Propose -> message.target
        is PeerMessage.Reject -> message.target
        is PeerMessage.HealthReport -> message.target
        is PeerMessage.Heartbeat,
        is PeerMessage.Hello,
        is PeerMessage.HostClaim,
        -> null
    }
}
