package dev.etdmnet.codec

import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage
import dev.etdmnet.core.Role
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Stable binary codec for ETDM-Net control and application messages.
 *
 * This is the boundary a real Android/WebRTC/Nearby adapter should use:
 * Session works with [PeerMessage], the real network sends [ByteArray]. The
 * codec is deliberately small, deterministic and dependency-free so the first
 * app integration does not need kotlinx.serialization, protobuf or reflection.
 */
object PeerMessageCodec {
    private const val MAGIC = 0x4554444D // "ETDM"
    private const val CODEC_VERSION = 1

    private const val TYPE_HELLO = 1
    private const val TYPE_HEALTH_REPORT = 2
    private const val TYPE_PROPOSE = 3
    private const val TYPE_ACCEPT = 4
    private const val TYPE_CONFIRM = 5
    private const val TYPE_REJECT = 6
    private const val TYPE_HEARTBEAT = 7
    private const val TYPE_HOST_CLAIM = 8
    private const val TYPE_APPLICATION = 9

    fun encode(message: PeerMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(CODEC_VERSION)
            out.writeInt(typeOf(message))
            out.writeInt(message.protocolVersion)
            out.writeString(message.sessionId)
            out.writePeerId(message.sender)
            out.writeLong(message.sentAtTick)
            when (message) {
                is PeerMessage.Hello -> {
                    out.writeLong(message.epoch)
                    out.writeNullablePeerId(message.claimedHost)
                }
                is PeerMessage.HealthReport -> {
                    out.writePeerId(message.target)
                    out.writeDouble(message.confidence)
                    out.writeHealthSample(message.sample)
                }
                is PeerMessage.Propose -> {
                    out.writePeerId(message.target)
                    out.writeLong(message.k)
                    out.writeLong(message.nonceSrc)
                    out.writeInt(message.proposedRole.ordinal)
                }
                is PeerMessage.Accept -> {
                    out.writePeerId(message.target)
                    out.writeLong(message.k)
                    out.writeLong(message.nonceSrc)
                    out.writeLong(message.nonceDst)
                }
                is PeerMessage.Confirm -> {
                    out.writePeerId(message.target)
                    out.writeLong(message.k)
                    out.writeLong(message.nonceDst)
                }
                is PeerMessage.Reject -> {
                    out.writePeerId(message.target)
                    out.writeLong(message.k)
                    out.writeString(message.reason)
                }
                is PeerMessage.Heartbeat -> {
                    out.writeLong(message.hostEpoch)
                    out.writePeerId(message.hostId)
                    out.writeNullablePeerId(message.backupHostId)
                    out.writeLong(message.gameTick)
                }
                is PeerMessage.HostClaim -> {
                    out.writeNullablePeerId(message.previousHost)
                    out.writeLong(message.previousEpoch)
                    out.writeLong(message.newEpoch)
                    out.writeDouble(message.claimantConfidence)
                }
                is PeerMessage.Application -> {
                    out.writePeerId(message.target)
                    out.writeByteArray(message.payload)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PeerMessage? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != CODEC_VERSION) return null
                val type = input.readInt()
                val protocolVersion = input.readInt()
                val sessionId = input.readString()
                val sender = input.readPeerId()
                val sentAtTick = input.readLong()
                when (type) {
                    TYPE_HELLO -> PeerMessage.Hello(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        epoch = input.readLong(),
                        claimedHost = input.readNullablePeerId(),
                    )
                    TYPE_HEALTH_REPORT -> PeerMessage.HealthReport(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        confidence = input.readDouble(),
                        sample = input.readHealthSample(),
                    )
                    TYPE_PROPOSE -> PeerMessage.Propose(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        k = input.readLong(),
                        nonceSrc = input.readLong(),
                        proposedRole = Role.entries[input.readInt()],
                    )
                    TYPE_ACCEPT -> PeerMessage.Accept(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        k = input.readLong(),
                        nonceSrc = input.readLong(),
                        nonceDst = input.readLong(),
                    )
                    TYPE_CONFIRM -> PeerMessage.Confirm(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        k = input.readLong(),
                        nonceDst = input.readLong(),
                    )
                    TYPE_REJECT -> PeerMessage.Reject(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        k = input.readLong(),
                        reason = input.readString(),
                    )
                    TYPE_HEARTBEAT -> PeerMessage.Heartbeat(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        hostEpoch = input.readLong(),
                        hostId = input.readPeerId(),
                        backupHostId = input.readNullablePeerId(),
                        gameTick = input.readLong(),
                    )
                    TYPE_HOST_CLAIM -> PeerMessage.HostClaim(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        previousHost = input.readNullablePeerId(),
                        previousEpoch = input.readLong(),
                        newEpoch = input.readLong(),
                        claimantConfidence = input.readDouble(),
                    )
                    TYPE_APPLICATION -> PeerMessage.Application(
                        protocolVersion = protocolVersion,
                        sessionId = sessionId,
                        sender = sender,
                        sentAtTick = sentAtTick,
                        target = input.readPeerId(),
                        payload = input.readByteArrayChecked(),
                    )
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun typeOf(message: PeerMessage): Int = when (message) {
        is PeerMessage.Hello -> TYPE_HELLO
        is PeerMessage.HealthReport -> TYPE_HEALTH_REPORT
        is PeerMessage.Propose -> TYPE_PROPOSE
        is PeerMessage.Accept -> TYPE_ACCEPT
        is PeerMessage.Confirm -> TYPE_CONFIRM
        is PeerMessage.Reject -> TYPE_REJECT
        is PeerMessage.Heartbeat -> TYPE_HEARTBEAT
        is PeerMessage.HostClaim -> TYPE_HOST_CLAIM
        is PeerMessage.Application -> TYPE_APPLICATION
    }

    private fun DataOutputStream.writePeerId(peerId: PeerId) = writeString(peerId.value)

    private fun DataOutputStream.writeNullablePeerId(peerId: PeerId?) {
        writeBoolean(peerId != null)
        if (peerId != null) writePeerId(peerId)
    }

    private fun DataInputStream.readPeerId(): PeerId = PeerId(readString())

    private fun DataInputStream.readNullablePeerId(): PeerId? {
        return if (readBoolean()) readPeerId() else null
    }

    private fun DataOutputStream.writeHealthSample(sample: HealthSample) {
        writeDouble(sample.rttMs)
        writeDouble(sample.lossRate)
        writeDouble(sample.jitterMs)
        writeDouble(sample.batteryPercent)
        writeBoolean(sample.directReachable)
        writeBoolean(sample.packetDeliveredThisTick)
        writeBoolean(sample.natFriendly)
    }

    private fun DataInputStream.readHealthSample(): HealthSample {
        return HealthSample(
            rttMs = readDouble(),
            lossRate = readDouble(),
            jitterMs = readDouble(),
            batteryPercent = readDouble(),
            directReachable = readBoolean(),
            packetDeliveredThisTick = readBoolean(),
            natFriendly = readBoolean(),
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= MAX_STRING_BYTES) { "String too large: ${encoded.size}" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val bytes = readByteArrayChecked(MAX_STRING_BYTES)
        return bytes.decodeToString()
    }

    private fun DataOutputStream.writeByteArray(value: ByteArray) {
        require(value.size <= MAX_PAYLOAD_BYTES) { "Payload too large: ${value.size}" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readByteArrayChecked(limit: Int = MAX_PAYLOAD_BYTES): ByteArray {
        val size = readInt()
        require(size in 0..limit) { "Invalid byte array size: $size" }
        return ByteArray(size).also { readFully(it) }
    }

    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_PAYLOAD_BYTES = 256 * 1024
}
