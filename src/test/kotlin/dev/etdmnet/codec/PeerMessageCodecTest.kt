package dev.etdmnet.codec

import dev.etdmnet.core.HealthSample
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.PeerMessage
import dev.etdmnet.core.Role
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PeerMessageCodecTest {
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test fun `all protocol messages round trip`() {
        val messages = listOf(
            PeerMessage.Hello(1, "game", alice, 10, epoch = 2, claimedHost = alice),
            PeerMessage.HealthReport(
                1,
                "game",
                alice,
                11,
                target = bob,
                confidence = 0.71,
                sample = HealthSample(43.0, 0.02, 7.0, 0.84, true, true, false),
            ),
            PeerMessage.Propose(1, "game", alice, 12, bob, k = 3, nonceSrc = 100, proposedRole = Role.BACKUP_HOST),
            PeerMessage.Accept(1, "game", bob, 13, alice, k = 3, nonceSrc = 100, nonceDst = 200),
            PeerMessage.Confirm(1, "game", alice, 14, bob, k = 3, nonceDst = 200),
            PeerMessage.Reject(1, "game", bob, 15, alice, k = 4, reason = "busy"),
            PeerMessage.Heartbeat(1, "game", alice, 16, hostEpoch = 5, hostId = alice, backupHostId = bob, gameTick = 90),
            PeerMessage.HostClaim(1, "game", bob, 17, previousHost = alice, previousEpoch = 5, newEpoch = 6, claimantConfidence = 0.82),
        )

        for (message in messages) {
            assertEquals(message, PeerMessageCodec.decode(PeerMessageCodec.encode(message)))
        }
    }

    @Test fun `application payload round trip preserves bytes`() {
        val message = PeerMessage.Application(
            protocolVersion = 1,
            sessionId = "game",
            sender = alice,
            sentAtTick = 20,
            target = bob,
            payload = byteArrayOf(1, 2, 3, 4, 127, -1),
        )

        val decoded = PeerMessageCodec.decode(PeerMessageCodec.encode(message))
        val app = assertIs<PeerMessage.Application>(decoded)
        assertEquals(message.protocolVersion, app.protocolVersion)
        assertEquals(message.sessionId, app.sessionId)
        assertEquals(message.sender, app.sender)
        assertEquals(message.target, app.target)
        assertContentEquals(message.payload, app.payload)
    }

    @Test fun `invalid bytes are dropped`() {
        assertNull(PeerMessageCodec.decode(byteArrayOf(1, 2, 3)))
    }
}
