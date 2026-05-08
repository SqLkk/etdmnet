package dev.etdmnet

import dev.etdmnet.core.Clock
import dev.etdmnet.core.EtdmConfig
import dev.etdmnet.core.PeerId
import dev.etdmnet.core.SystemClock
import dev.etdmnet.runtime.Session
import dev.etdmnet.transport.EncodedTransport
import dev.etdmnet.transport.RawPeerLink

/** Public entry point for game integrations. */
object EtdmNet {
    fun newPeerId(prefix: String = "player"): PeerId = PeerId.random(prefix)

    fun createSession(
        sessionId: String,
        link: RawPeerLink,
        clock: Clock = SystemClock,
        config: EtdmConfig = EtdmConfig(),
    ): Session {
        return Session(
            sessionId = sessionId,
            transport = EncodedTransport(link),
            clock = clock,
            config = config,
        )
    }
}