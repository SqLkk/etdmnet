package dev.etdmnet.core

/**
 * Monotonic clock abstraction.
 *
 * The session is driven by ticks. In production a wall clock provides
 * monotonic milliseconds; in tests a [VirtualClock] advances deterministically.
 */
interface Clock {
    fun nowMs(): Long
}

/** Real monotonic clock backed by [System.nanoTime]. */
object SystemClock : Clock {
    private val origin = System.nanoTime()
    override fun nowMs(): Long = (System.nanoTime() - origin) / 1_000_000L
}

/** Deterministic clock for unit tests and replayable simulation. */
class VirtualClock(private var current: Long = 0L) : Clock {
    override fun nowMs(): Long = current

    fun advance(ms: Long) {
        require(ms >= 0) { "VirtualClock cannot move backwards" }
        current += ms
    }

    fun set(ms: Long) {
        require(ms >= current) { "VirtualClock cannot move backwards" }
        current = ms
    }
}
