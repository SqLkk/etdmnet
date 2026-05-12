# etdmnet

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Status: Beta](https://img.shields.io/badge/status-beta-orange)
![Version: 0.4.0-beta](https://img.shields.io/badge/version-0.4.0--beta-blueviolet)

**Data-relay-free peer-to-peer multiplayer for Kotlin** — let any player (phone or
PC) become the host, with automatic host election and migration powered by the
ETDM (EWMA-Based Topology and Distributed Migration) protocol.

> ⚠️ **Beta release.** The API surface is stable enough to consume; we do not
> recommend the `1.0` label until TURN integration ships as a first-class
> module and the iOS transport lands. Pin to a specific version in production.

## ⚠️ Without TURN, you will lose users

`etdmnet` connects peers directly over WebRTC. That is fast and cheap, but
**Symmetric NAT** — used by a meaningful fraction of mobile carriers, especially
on CGNAT — cannot be traversed with STUN alone. In practice this means:

* On Wi‑Fi ↔ Wi‑Fi: typically >95% connect success with STUN only.
* On mobile ↔ mobile across carriers: often **60–80%** with STUN only.
* With a TURN server in the ICE list: effectively **~100%** (TURN is a
  guaranteed relay fallback).

If your app targets a real audience on mobile data, **deploy TURN**. The
library already accepts a custom `iceServers` list — see
[TURN integration](#turn-integration-recommended) below. Suggested options:

* Self-host [`coturn`](https://github.com/coturn/coturn) on a small VPS
* Cloudflare Calls TURN
* Twilio Network Traversal Service

Without TURN, advertise `etdmnet` as “LAN / co-op / Wi‑Fi multiplayer”, not
“global mobile multiplayer”. Use the bundled
[host eligibility probe](#host-eligibility-api) to detect Symmetric NAT and
gate the host-creation flow.


> **Why?** Kotlin/Android multiplayer libraries today push every packet through
> a relay server. That's fine for hobby games but turns into a real bill when
> you have hundreds of concurrent players. `etdmnet` keeps the data plane
> peer-to-peer — the only thing that ever touches a server is a tiny SDP/ICE
> handshake (a few KB per join). Game traffic flows phone↔phone over WebRTC
> DataChannels, even on mobile networks.

> **Important terminology:** `etdmnet` is **data-relay-free**, not serverless.
> A signaling server is still required for rendezvous and SDP/ICE exchange.
> Without signaling, peers cannot discover each other on the public internet.

## Status

* ✅ Pure-Kotlin core, 9/9 unit tests green
* ✅ JVM WebRTC transport (Windows / macOS / Linux)
* ✅ Android WebRTC transport (opt-in)
* ✅ Runnable Ktor signaling server (Docker-friendly, ~50 MB RAM)
* ✅ Auto host election & migration (ETDM-Net protocol, MSc thesis)
* 🧪 iOS transport (Swift bridge, 0.4 preview — see `transport-webrtc-ios-bridge/`)
* ✅ TURN integration (first-class `:turn-bundled` module + `deploy/coturn/` recipe)

## Modules

| Module                       | What it gives you                                       |
| ---------------------------- | ------------------------------------------------------- |
| `:core`                      | `EtdmNet.join(...)`, `EtdmHost`, host election runtime  |
| `:signaling-ktor`            | Ktor WebSocket signaling protocol + client              |
| `:signaling-server`          | Runnable signaling server (`./gradlew :signaling-server:run`) |
| `:transport-webrtc-jvm`      | Desktop/PC WebRTC transport (`webrtc-java`)             |
| `:transport-webrtc-android`  | Android WebRTC transport (opt-in via Gradle property)   |
| `:samples:jvm-chat`          | 50-line cross-platform chat demo                        |

## Quick start (PC ↔ PC)

1. **Start a signaling server** (locally or any cheap VPS):

   ```bash
   ./gradlew :signaling-server:run
   # listens on :8080, path /ws/v1
   ```

2. **Run two chat clients** in separate terminals:

   ```bash
   ./gradlew :samples:jvm-chat:run --args="ws://localhost:8080/ws/v1 lobby alice"
   ./gradlew :samples:jvm-chat:run --args="ws://localhost:8080/ws/v1 lobby bob"
   ```

3. Type lines. Messages flow **directly between the two JVMs over WebRTC**.
   Watch the role logs: one peer becomes `HOST`, the other `CLIENT`. Kill the
   host — the other peer transparently takes over.

## Quick start (Android ↔ Android or Android ↔ PC)

In your Android app's `build.gradle.kts`:

```kotlin
// Choose one of the two repositories below:

repositories {
    mavenCentral()                             // once 0.5 lands on Central

    // OR — available right now from GitHub Packages:
    maven {
        url = uri("https://maven.pkg.github.com/SqLkk/etdmnew")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull
                ?: System.getenv("GITHUB_TOKEN")    // a PAT with read:packages
        }
    }

    // OR — zero-setup via JitPack (auto-built from the git tag):
    maven("https://jitpack.io")
}

dependencies {
    // Coordinates when consumed from GitHub Packages / future Maven Central:
    implementation("dev.etdmnet:etdmnet-core:0.4.0-beta")
    implementation("dev.etdmnet:etdmnet-transport-webrtc-android:0.4.0-beta")
    implementation("dev.etdmnet:etdmnet-signaling-ktor:0.4.0-beta")
    implementation("dev.etdmnet:etdmnet-turn-bundled:0.4.0-beta")   // recommended

    // OR — coordinates when consumed from JitPack:
    // implementation("com.github.SqLkk.etdmnew:core:v0.4.0-beta")
}
```

In your code:

```kotlin
val transport = AndroidWebRtcTransport.connect(
    context      = applicationContext,
    signalingUrl = "wss://your-signaler.example.com/ws/v1",
    roomId       = "lobby-42",
    localPeerId  = EtdmNet.newPeerId("phone"),
)
val net = EtdmNet.join(roomId = "lobby-42", transport = transport)

// Observe
lifecycleScope.launch { net.role.collect    { role  -> /* HOST / CLIENT */ } }
lifecycleScope.launch { net.peers.collect   { peers -> /* who's online */ } }
lifecycleScope.launch { net.messages.collect { msg  -> handle(msg.from, msg.payload) } }

// Send
if (net.isHost) net.publishAsHost(stateBytes)   // host → everyone
else            net.broadcast(actionBytes)      // client → current host
```

A PC peer using `:transport-webrtc-jvm` joining the same `roomId` will appear
in `net.peers` and exchange messages identically. Host election treats phones
and PCs uniformly — whichever peer has the best link quality wins.

## How it works

```
┌──────────┐                        ┌──────────┐
│ Phone A  │                        │ Phone B  │
│ (HOST)   │◄══════ WebRTC ═══════► │ (CLIENT) │
└────┬─────┘   DataChannel (P2P)    └─────┬────┘
     │                                    │
     │      ┌─────────────────┐           │
     └─────►│ Signaling       │◄──────────┘
            │ Server (Ktor)   │
            │ ─ rendezvous    │
            │ ─ SDP/ICE relay │
            │ ─ NEVER sees    │
            │   game data     │
            └─────────────────┘
```

The server is **only** consulted during the WebRTC handshake (typically <1 KB
per join). All game state and inputs flow over the direct DataChannels. If the
elected host disconnects, ETDM's EWMA-scored health samples drive a new
election; the new host inherits the session within a few ticks.

## Production readiness checklist

If you plan to publish a real game, treat these as required:

1. Deploy signaling on a reliable public endpoint (`wss://...`).
2. Add TURN servers for strict/symmetric NAT users.
3. Run host-eligibility probe in lobby and block weak hosts.
4. Keep at least one non-mobile candidate (desktop or Wi-Fi) available as host fallback.

Without TURN, a significant fraction of mobile users behind strict CGNAT will
fail to connect to some peers. This is a WebRTC/NAT reality, not ETDM logic.

## TURN integration (recommended)

> **New in 0.3 / 0.4:** the dedicated `:turn-bundled` module gives you a
> platform-neutral builder API (`TurnConfig`) plus a ready-to-run coturn
> docker-compose deployment under [`deploy/coturn/`](deploy/coturn/) — with
> a worked HMAC-SHA1 credential-issuer example in
> [`deploy/coturn/README.md`](deploy/coturn/README.md).

### Quick start with `TurnConfig`

```kotlin
import dev.etdmnet.turn.TurnConfig
import dev.etdmnet.transport.webrtc.toJvmIceServers   // JVM
// import dev.etdmnet.transport.webrtc.android.toAndroidIceServers  // Android

val turn = TurnConfig.builder()
    .addPublicStuns()
    .addTurn("turn.example.com", username = creds.username, credential = creds.credential)
    .addTurns("turn.example.com", username = creds.username, credential = creds.credential)
    .build()

val iceServers = turn.toJvmIceServers()   // or .toAndroidIceServers()
```

`TurnCredentials` (returned by your backend's HMAC-SHA1 issuer — see the
recipe in `deploy/coturn/README.md`) has a one-call `.toTurnConfig()`.

### Manual ICE server lists (still supported)

`etdmnet` also accepts raw ICE server lists. Add TURN alongside STUN:

JVM transport:

```kotlin
val turn = RTCIceServer().apply {
    urls.add("turn:turn.example.com:3478?transport=udp")
    username = "turn-user"
    credential = "turn-pass"
}

val transport = WebRtcTransport.connect(
    signalingUrl = "wss://signal.example.com/ws/v1",
    roomId = "lobby-42",
    localPeerId = EtdmNet.newPeerId("pc"),
    iceServers = WebRtcTransport.DEFAULT_ICE_SERVERS + turn,
)
```

Android transport:

```kotlin
val turn = PeerConnection.IceServer.builder("turn:turn.example.com:3478?transport=udp")
    .setUsername("turn-user")
    .setPassword("turn-pass")
    .createIceServer()

val transport = AndroidWebRtcTransport.connect(
    context = applicationContext,
    signalingUrl = "wss://signal.example.com/ws/v1",
    roomId = "lobby-42",
    localPeerId = EtdmNet.newPeerId("phone"),
    iceServers = AndroidWebRtcTransport.DEFAULT_ICE_SERVERS + turn,
)
```

Suggested TURN providers:

* Self-hosted `coturn` (lowest cost, full control)
* Cloudflare Calls TURN
* Twilio Network Traversal Service

## Host eligibility API

Use this probe before allowing a player to create a room:

```kotlin
val report = EtdmNet.checkHostEligibility()
if (report.verdict == HostVerdict.INELIGIBLE) {
    showMessage(report.reason)
    disableCreateRoom()
}
```

The report includes NAT type, verdict, human-readable reason, and a `0..100`
host-score hint.

## Platform scope (important)

Current transport support:

* JVM desktop/server: supported
* Android: supported (opt-in module)
* iOS: not yet implemented

So, today this repository is best described as **Kotlin/JVM + Android ready**,
not full KMP-native transport complete.

## KMP and native WebRTC note

If your app is Kotlin Multiplatform, keep transport dependencies target-scoped
(`androidMain`, `jvmMain`) and avoid expecting one shared native binary setup
to work for all targets automatically. Native classifier handling differs by
platform and packaging strategy.

## Building

* JDK 17+
* Gradle 9.x

```bash
gradle build              # JVM modules
gradle :core:test         # unit tests
```

Android transport is opt-in (requires AGP + Android SDK):

```bash
gradle build -Petdmnet.includeAndroid=true
```

## Public API surface

The library exposes a deliberately small surface:

```kotlin
object EtdmNet {
    fun newPeerId(prefix: String = "player"): PeerId
    suspend fun checkHostEligibility(
        stunServers: List<String> = HostEligibility.DEFAULT_STUN_SERVERS,
        timeoutMillis: Long = 3000L,
    ): EligibilityReport
    fun join(
        roomId: String,
        transport: RawPeerLink,
        scope: CoroutineScope? = null,
        config: EtdmConfig = EtdmConfig(),
        clock: Clock = SystemClock,
    ): EtdmHost
}

class EtdmHost : AutoCloseable {
    val localPeerId: PeerId
    val role: StateFlow<Role>            // HOST / BACKUP_HOST / CLIENT
    val host: StateFlow<HostSnapshot?>
    val peers: StateFlow<Set<PeerId>>
    val messages: SharedFlow<IncomingMessage>
    val isHost: Boolean

    fun broadcast(payload: ByteArray): Boolean      // CLIENT → host
    fun publishAsHost(payload: ByteArray): Int      // HOST   → all
    fun forcePublish(payload: ByteArray): Int
    fun sendTo(target: PeerId, payload: ByteArray)
    override fun close()
}
```

Everything else (`Session`, `HostElector`, `PeerMessage`, transport internals)
is implementation detail — touch only if you want to plug in a custom
transport.

## Publishing (Maven)

### Local validation

```bash
gradle publishToMavenLocal
```

This writes signed-or-unsigned artifacts to `~/.m2/repository/dev/etdmnet/…`.
No credentials required.

### Private repo (Nexus / Artifactory / GitHub Packages)

Set the following environment variables, then `gradle publish`:

* `MAVEN_REPO_URL`
* `MAVEN_REPO_USERNAME`
* `MAVEN_REPO_PASSWORD`

### Maven Central (Sonatype OSSRH + GPG signing)

The Gradle build wires the OSSRH staging endpoint **and** GPG signing automatically
when these environment variables are present:

| Variable           | What it is                                     |
| ------------------ | ---------------------------------------------- |
| `OSSRH_USERNAME`   | Sonatype JIRA username (or user token name)    |
| `OSSRH_PASSWORD`   | Sonatype JIRA password (or user token value)   |
| `SIGNING_KEY`      | ASCII-armored PGP private key (single line OK) |
| `SIGNING_PASSWORD` | Passphrase for that PGP key                    |

The shipped GitHub Actions workflow [`.github/workflows/publish.yml`](.github/workflows/publish.yml)
publishes the three JVM modules to Sonatype on every `v*` tag push. After it
succeeds, log in to `s01.oss.sonatype.org` and **Close + Release** the staging
repository to promote artifacts to Central.

> The Android transport (`:transport-webrtc-android`) requires AGP and is
> published from a build with `-Petdmnet.includeAndroid=true`. It is not yet
> wired into the standard release tag flow.

## Writing your own transport

Implement [`RawPeerLink`](core/src/main/kotlin/dev/etdmnet/transport/RawPeerLink.kt).
The interface is intentionally tiny: 5 methods. You can build adapters for
Bluetooth LE, local UDP, libp2p, Nearby Connections, etc., and the rest of the
stack stays unchanged.

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for the
full text. You can use, modify and redistribute `etdmnet` in commercial or
open-source projects as long as you retain the copyright and license notice.

## Citation

Based on the MSc thesis *"EWMA-Based Topology and Distributed Migration for
Peer-Hosted Multiplayer"*. Citation BibTeX will be added on first release.
