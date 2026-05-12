# etdmnet — iOS transport (Swift bridge, experimental)

> **Status: 0.4-beta preview.** This directory is a **Swift reference
> implementation** that interoperates with the rest of `etdmnet` over the wire
> (same WebRTC DataChannels, same signaling server, same ETDM message
> framing). It is **not** a full Kotlin Multiplatform port yet — we ship that
> in 0.5 once `:core` is converted to KMP. Until then, an iOS app can
> participate as a full peer using the code here.

## Why a Swift bridge, not Kotlin/Native?

The `etdmnet` core module is currently pure-JVM Kotlin. Converting it to
Kotlin Multiplatform with iOS targets is a multi-week effort (rewriting
`SecureRandom`, `DatagramSocket`, coroutine `Dispatchers.IO`, JUnit tests,
etc.). The honest, shippable alternative — and the one used in production by
many "Kotlin-first" libraries today — is to:

1. Specify the **wire protocol** precisely (see `WIRE_PROTOCOL.md`).
2. Provide a Swift reference implementation that matches it byte-for-byte.
3. Promote it to a Kotlin/Native module later, when KMP conversion lands.

A JVM/Android host + an iOS Swift peer connect through the **same** signaling
server and exchange the **same** ETDM-framed messages over the **same**
WebRTC DataChannels. Host election still works because election is
protocol-level, not language-level.

## Wire protocol summary

The full specification lives in [WIRE_PROTOCOL.md](WIRE_PROTOCOL.md). Headlines:

- **Signaling:** identical Ktor WebSocket protocol as defined in
  `:signaling-ktor`. Swift uses any standard `URLSessionWebSocketTask` client.
- **Transport:** standard WebRTC DataChannel, label `etdm`, ordered + reliable.
- **Framing:** little-endian length prefix, then a single byte tag, then
  CBOR-compatible payload (same as `EncodedTransport`).
- **Host election:** EWMA score samples piggyback on regular peer messages;
  see the reference Kotlin implementation in `core/.../HostElector.kt`.

## Swift package

The reference implementation is a Swift Package targeting iOS 14+ that
depends on Google's `WebRTC.framework`. See `EtdmNetIOS/` in the sample
directory for the full source — the headline API is:

```swift
import EtdmNetIOS

let host = try await EtdmNet.join(
    signalingURL: URL(string: "wss://signal.example.com/ws/v1")!,
    roomId: "lobby-42",
    localPeerId: "phone-ios-\(UUID().uuidString.prefix(6))",
    iceServers: [
        .stun("stun:stun.l.google.com:19302"),
        .turn(
            urls: ["turn:turn.example.com:3478?transport=udp",
                   "turns:turn.example.com:5349?transport=tcp"],
            username: turnCreds.username,
            credential: turnCreds.credential
        )
    ]
)

// Observe role / peers / messages
for await role in host.roleStream { print("role: \(role)") }

// Send
if host.isHost {
    host.publishAsHost(stateBytes)
} else {
    host.broadcast(actionBytes)
}
```

## Roadmap

- 0.4-beta: Swift reference implementation + interop tests vs JVM/Android.
- 0.5: Convert `:core` to Kotlin Multiplatform; ship `:transport-webrtc-ios`
  as a real Kotlin/Native module backed by `WebRTC.framework`.
- 1.0: Stable API, all three transports under one Gradle + KMP build.
