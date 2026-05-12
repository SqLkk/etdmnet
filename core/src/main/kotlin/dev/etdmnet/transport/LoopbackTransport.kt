package dev.etdmnet.transport

import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Deterministic in-memory bus used by the test suite and by simulations that
 * mirror the lossy synchronous-round model from the thesis.
 *
 * A single [Bus] instance is shared by every [LoopbackTransport]: each peer
 * registers itself, and `send` enqueues a copy of the message into the
 * recipient's inbox iff a per-link Bernoulli draw permits it.
 *
 * Two knobs control the simulated channel:
 *
 *  * [lossRate] — probability of dropping any individual message;
 *  * [partition] — boolean predicate `(from, to) -> isolated`. When the
 *    predicate returns `true` the message is dropped *before* the loss draw
 *    so that fail-over scenarios are perfectly reproducible.
 *
 * The bus is thread-safe (concurrent send / receive) but tests should run it
 * single-threaded so that the [random] seed makes runs reproducible.
 */
class Bus(
    private val random: Random = Random(0xE7DAC001),
) {
    var lossRate: Double = 0.0
    var partition: (PeerId, PeerId) -> Boolean = { _, _ -> false }

    private val inboxes: MutableMap<PeerId, ConcurrentLinkedQueue<PeerMessage>> = mutableMapOf()
    private val onLost: MutableList<(PeerId) -> Unit> = mutableListOf()
    private val isolated: MutableSet<PeerId> = mutableSetOf()

    fun register(peerId: PeerId) {
        inboxes.getOrPut(peerId) { ConcurrentLinkedQueue() }
    }

    fun unregister(peerId: PeerId) {
        inboxes.remove(peerId)
        isolated.remove(peerId)
        onLost.forEach { it(peerId) }
    }

    fun isolate(peerId: PeerId) { isolated += peerId }

    fun rejoin(peerId: PeerId) { isolated -= peerId }

    fun deliver(message: PeerMessage): Boolean {
        if (message.sender in isolated) return false
        var delivered = false
        for ((peerId, inbox) in inboxes) {
            if (peerId == message.sender) continue
            if (peerId in isolated) continue
            if (partition(message.sender, peerId)) continue
            if (random.nextDouble() < lossRate) continue
            inbox.add(message)
            delivered = true
        }
        return delivered
    }

    fun drain(peerId: PeerId): List<PeerMessage> {
        val inbox = inboxes[peerId] ?: return emptyList()
        val out = mutableListOf<PeerMessage>()
        while (true) {
            val m = inbox.poll() ?: break
            out += m
        }
        return out
    }

    fun knownPeers(self: PeerId): Set<PeerId> {
        if (self in isolated) return emptySet()
        return inboxes.keys.asSequence()
            .filter { it != self && it !in isolated && !partition(self, it) }
            .toSet()
    }

    fun subscribeLost(callback: (PeerId) -> Unit) { onLost += callback }
}

/**
 * In-memory [Transport] implementation that talks to a shared [Bus].
 *
 * Each [LoopbackTransport] tracks per-peer simulated link parameters in
 * [linkProfile]. Tests mutate the profile to simulate a Wi-Fi → cellular
 * handover, a NAT becoming hostile, etc.
 */
class LoopbackTransport(
    override val localPeerId: PeerId,
    private val bus: Bus,
) : Transport {

    init { bus.register(localPeerId) }

    private val linkProfile: MutableMap<PeerId, HealthSample> = mutableMapOf()

    /** Update the simulated link health to [target]. */
    fun setLink(target: PeerId, sample: HealthSample) {
        linkProfile[target] = sample
    }

    /** Drop the simulated link to [target] (samples become null). */
    fun dropLink(target: PeerId) {
        linkProfile.remove(target)
    }

    override fun knownPeers(): Set<PeerId> = bus.knownPeers(localPeerId)

    override fun sample(target: PeerId): HealthSample? = linkProfile[target]

    override fun send(message: PeerMessage): Boolean = bus.deliver(message)

    override fun receive(): List<PeerMessage> = bus.drain(localPeerId)

    override fun onPeerLost(callback: (PeerId) -> Unit) { bus.subscribeLost(callback) }
}
