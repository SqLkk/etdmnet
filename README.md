# etdmnet

A relay-free, ETDM-inspired peer-to-peer runtime for 4-player mobile games on
the JVM and Android. The library implements the host-election, host-migration
and link-scoring layer; it is **transport-agnostic** and ships with an
in-memory `LoopbackTransport` for unit testing and for replaying the
synchronous-round model from the parent thesis.

> **Not a TURN, not a relay.** `etdmnet` does not forward bytes for you. It
> sits *above* a real peer-to-peer transport (WebRTC DataChannels, raw UDP,
> Android Nearby Connections, BLE mesh, …) and decides *who* should be the
> session host, *when* to fail over, and *with what confidence*.

## What problem does it solve?

In a typical 4-player mobile game one device acts as the authoritative host.
On mobile networks, that device may at any moment:

- hand off Wi-Fi → cellular and lose its public address,
- enter a hostile NAT,
- run out of battery,
- or simply walk out of range.

Without a dedicated TURN relay the session needs to *re-elect* a host
on-device. `etdmnet` does that with the same machinery the parent thesis uses
for its discovery protocol: an EWMA-smoothed link-quality score
($\lambda = 0.3$), a sigmoid confidence transform ($\alpha = 2.0$),
hysteresis ($\eta = 0.1$), and a strictly-monotone *epoch* counter that
prevents split-brain after a migration.

## Architecture at a glance

```
Application
    │  sendApplication / sendToHost / onRoleChange / onHostChange
    ▼
┌──────────────── runtime.Session ────────────────┐
│                                                 │
│  HostElector  ──►  HostSnapshot(epoch, hostId)  │
│  HandshakeMachine (PROPOSE / ACCEPT / CONFIRM)  │
│  EwmaScore per remote peer                      │
│                                                 │
└─────────────────────────────────────────────────┘
                    │  PeerMessage (sealed)
                    ▼
              transport.Transport
              ├── LoopbackTransport (tests / sim)
              ├── WebRtcTransport (planned)
              └── NearbyTransport (planned)
                    │
                    ▼
              signaling.Signaling
              (QR / mDNS / out-of-band, never a relay)
```

## Quickstart

```kotlin
val bus = Bus()                                // shared in-process bus
val transport = LoopbackTransport(PeerId("alice"), bus)
val session = Session(
    sessionId = "game-1",
    transport = transport,
    clock = SystemClock,
    config = EtdmConfig(),                     // thesis defaults
)

session.onRoleChange { role -> println("now $role") }
session.onHostChange { snap -> println("host=${snap.hostId}@${snap.hostEpoch}") }

// Game loop:
while (running) {
    val outcome = session.tick()
    for (msg in outcome.deliveredApplicationMessages) handle(msg)
    if (session.role == Role.HOST) sendWorldState()
    Thread.sleep(EtdmConfig().tickIntervalMs)
}
```

## Mapping to the thesis

| Thesis symbol            | `EtdmConfig` field            | Default |
|--------------------------|-------------------------------|---------|
| EWMA learning rate $\lambda$ | `lambda`                  | 0.3     |
| Sigmoid sharpness $\alpha$   | `alpha`                   | 2.0     |
| Hysteresis margin $\eta$     | `eta`                     | 0.1     |
| Propose timeout $T$          | `proposeTimeoutTicks`     | 5       |
| Heartbeat timeout $T_{hb}$   | `heartbeatTimeoutTicks`   | 15      |
| Hello interval               | `helloIntervalTicks`      | 3       |

The invariant $T_{hb} > T$ (Lemma K1) is enforced in `EtdmConfig.init`.

## Testing

```bash
# Java 17 toolchain is required (configured via gradle.properties).
gradle test
```

The test suite covers:

- `HealthSampleTest` — bounded, monotone link-quality mapping,
- `EwmaScoreTest` — smoothing and convergence,
- `HostElectorTest` — hysteresis, stability ticks, fail-over,
- `FourPeerSessionTest` — full simulation: 4 peers converge, then the host
  is isolated (Wi-Fi → cellular handover) and migration completes with a
  strictly-greater epoch, also under 30 % packet loss.

## Android game integration

### 1. Add as a local module (no internet needed)

**`settings.gradle.kts`** of your game project:
```kotlin
include(":etdmnet")
project(":etdmnet").projectDir = File("../TEZ/new_lib/etdmnet")
// adjust the path to wherever etdmnet lives on your machine
```

**`app/build.gradle.kts`**:
```kotlin
dependencies {
    implementation(project(":etdmnet"))
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

That's it — Android Studio compiles the library directly from source. No JitPack, no JAR export, changes you make to etdmnet reflect immediately.

### 2. Copy the Android adapter

Copy these two files from `android-adapter/` to your game's Android source tree and adjust the `package` line:

| File | Purpose |
|------|---------|
| `NearbyRawPeerLink.kt` | Implements `RawPeerLink` via Google Nearby Connections (WiFi Direct / BLE, no relay) |
| `EtdmNetRunner.kt` | Coroutine game loop, exposes `StateFlow<Role>` + `StateFlow<HostSnapshot>` |

### 3. Wire it up in your NetworkManager

```kotlin
class NetworkManager(context: Context, private val scope: CoroutineScope) {

    private val peerId  = EtdmNet.newPeerId("player")
    private val link    = NearbyRawPeerLink(context, peerId)
    private val session = EtdmNet.createSession(sessionId = "room-123", link = link)
    val runner          = EtdmNetRunner(session, scope)

    fun joinLobby() {
        link.start("room-123")  // starts advertising + discovery
        runner.start()          // starts tick loop at 100 ms
    }

    fun leaveGame() {
        runner.stop()
        link.stop()
    }
}
```

### 4. React to host changes in UI

```kotlin
lifecycleScope.launch {
    runner.roleState.collect { role ->
        when (role) {
            Role.HOST        -> enableHostUI()
            Role.BACKUP_HOST -> showBackupBadge()
            Role.CLIENT      -> enableClientUI()
        }
    }
}

lifecycleScope.launch {
    runner.appMessages.collect { payloads ->
        payloads.forEach { bytes -> applyGameState(bytes) }
    }
}
```

### 5. Send game data

```kotlin
// In host game loop
if (runner.roleState.value == Role.HOST) {
    runner.broadcastGameState(gameState.toBytes())
}

// In client input handler
runner.sendToHost(playerInput.toBytes())
```

### 6. Test with 4 Android Studio emulators

1. Open **Device Manager** → create 4 Pixel emulators (API 33+, with Google Play).
2. Boot all 4. Android Studio shows them in the **Running Devices** tab.
3. In **Run → Edit Configurations** duplicate your run config 4 times, each targeting a different emulator.
4. On emulators Nearby Connections uses the host machine's virtual WiFi bridge — all 4 share the same network segment, so P2P works without any router.
5. Launch all 4 copies. Watch logs for `NearbyRawPeerLink: Connected to …` lines, then look for `onHostChange` callbacks settling on the same `hostId`.

> **Tip — Inspect in real time:** `adb -s emulator-5554 logcat -s NearbyRawPeerLink EtdmNet`

### 7. Required Android permissions

Add to `AndroidManifest.xml` (inside `<manifest>`):
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"/>
<!-- Android 13+ -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"/>
```

Request permissions at runtime before calling `link.start()`:
```kotlin
ActivityCompat.requestPermissions(
    activity,
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    ),
    REQUEST_CODE_PERMISSIONS,
)
```

## Roadmap

1. `WebRtcTransport` adapter (Android + JVM via google/webrtc).
2. `NearbyTransport` adapter for Android Nearby Connections.
3. Cross-peer reputation gossip (uses the existing `HealthReport` message).
4. Epoch-stamped state-snapshot delivery so a freshly-elected host can
   reconstruct game state from the previous host's last heartbeat.

## License

TBD by the author.
