package dev.etdmnet.core

import java.util.UUID

/**
 * Stable, opaque peer identifier.
 *
 * Must be unique within a session. Generated either from a high-entropy random
 * source (UUID-style) or derived from a public key. The bytes are compared
 * lexicographically for deterministic tie-breaking.
 */
@JvmInline
value class PeerId(val value: String) : Comparable<PeerId> {
    init {
        require(value.isNotEmpty()) { "PeerId cannot be empty" }
        require(value.length <= 128) { "PeerId too long: ${value.length}" }
    }

    override fun compareTo(other: PeerId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        fun random(prefix: String = "peer"): PeerId = PeerId("$prefix-${UUID.randomUUID()}")
    }
}
