# Wire protocol — etdmnet 0.4

This document specifies the **byte-level** protocol used by `etdmnet` peers so
non-Kotlin implementations (currently: Swift / iOS) can interoperate with
JVM and Android peers in the same room.

The Kotlin reference implementations are authoritative; this document
describes their observable behavior.

## 1. Signaling (WebSocket / JSON)

- Transport: WebSocket (`ws://` or `wss://`)
- Default path: `/ws/v1`
- Content: UTF-8 JSON text frames.

### Handshake (client → server)

```json
{ "t": "Hello", "room": "<room>", "peerId": "<unique-id>" }
```

### Server → client

```json
{ "t": "Peers", "peers": ["peer-a", "peer-b"] }
{ "t": "PeerJoined", "peerId": "peer-c" }
{ "t": "PeerLeft",   "peerId": "peer-b" }
{ "t": "Signal", "from": "peer-a", "to": "peer-self",
  "payload": { "sdpType":"offer", "sdp":"<sdp text>" } }
{ "t": "Signal", "from": "peer-a", "to": "peer-self",
  "payload": { "candidate": "<ice candidate>", "sdpMid":"0", "sdpMLineIndex":0 } }
```

### Client → server

```json
{ "t": "Signal", "from":"peer-self", "to":"peer-a", "payload": { … } }
```

Glare rule: when two peers learn about each other simultaneously, **the peer
with the lexicographically greater `peerId` initiates the offer**.

## 2. WebRTC peer connection

- One `RTCPeerConnection` per remote peer (full mesh).
- ICE servers supplied by the application; see `:turn-bundled`.
- `iceTransportPolicy = all`, `bundlePolicy = max-bundle`,
  `rtcpMuxPolicy = require`, SDP semantics `unified-plan`,
  `continualGatheringPolicy = gatherContinually`.
- A single DataChannel:
  - label: `"etdm"`
  - ordered: `true`
  - reliable: `true` (no `maxRetransmits` / `maxPacketLifeTime`)
  - negotiated: `false` (created by the initiator, opened on the other side
    via `ondatachannel`).

## 3. ETDM frame format

Every payload on the `etdm` DataChannel is a single self-describing frame.

```
+---------+--------+----------+--------------------+----------+-------------------+
| len:u32 | tag:u8 | fromLen:u16 | from:UTF-8 bytes  | bodyLen:u32 | body: raw bytes |
+---------+--------+-------------+-------------------+-------------+-----------------+
```

All multi-byte fields are **little-endian** (matches Kotlin's
`ByteBuffer.LITTLE_ENDIAN` and Swift's default `Data` little-endian helpers).

`len` covers every byte after itself (tag + fromLen + from + bodyLen + body).

### Tag values

| tag  | meaning                              | body schema                                   |
|------|--------------------------------------|-----------------------------------------------|
| 0x01 | application payload                  | opaque (game/chat/state) bytes               |
| 0x02 | ETDM control (host election sample)  | see §4                                        |

Implementations MUST drop frames whose tag they don't recognize.

## 4. Host election sample (tag 0x02)

```
+----------+-------------+----------+------------+---------------+
| score:f32| confidence:f32 | bandwidth:u32 | latencyMs:u32 | flags:u8 |
+----------+----------------+---------------+----------------+----------+
```

- `score`, `confidence` — IEEE-754 little-endian 32-bit floats in `[0,1]`.
- `bandwidth` — measured downlink in kbps; `0` means unknown.
- `latencyMs` — most recent peer-RTT in milliseconds; `0` means unknown.
- `flags`:
  - bit 0 (`0x01`) — `BATTERY_OK` (>= 30%)
  - bit 1 (`0x02`) — `WIFI` (set when not on cellular)
  - bit 2 (`0x04`) — `WANTS_HOST` (peer is willing to host)

Peers compute their own EWMA on receipt and choose host = peer with highest
exponentially-weighted score over the last 30 seconds; ties are broken by
lexicographic `peerId` order.

## 5. Conformance test vectors

`transport-webrtc-ios-bridge/Tests/EtdmNetIOSTests/EtdmFrameTests.swift`
and `core/src/test/kotlin/.../PeerMessageCodecTest.kt` (when present) MUST
agree on the encoding of every test vector.

A minimal vector:

```
encode(tag = 0x01, from = "phone-1", payload = "hello mesh".utf8)
  → 1c 00 00 00            // len = 28 (0x1C)
    01                     // tag = APP
    07 00                  // fromLen = 7
    70 68 6f 6e 65 2d 31   // "phone-1"
    0a 00 00 00            // bodyLen = 10
    68 65 6c 6c 6f 20 6d 65 73 68   // "hello mesh"
```

## 6. Versioning

This document tracks the on-the-wire shape across all transports. Breaking
changes bump the **minor version** of etdmnet (e.g. 0.3 → 0.4). Tag bytes
above `0x7F` are reserved for future use.
