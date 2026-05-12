# coturn deployment recipe

This directory contains everything needed to run a production-grade TURN/STUN
server for `etdmnet` on a small VPS (1 vCPU / 1 GB RAM is sufficient for a few
hundred concurrent relay allocations).

## What you get

- `docker-compose.yml` — host-mode coturn 4.6
- `turnserver.conf` — production-leaning config (REST auth, TLS, quotas)

## One-time setup

```bash
# 1. Generate a long random secret (keep it on the server only)
openssl rand -hex 32 > .turn-secret
chmod 600 .turn-secret

# 2. Point a DNS record at the box, e.g. turn.example.com → <public IPv4>

# 3. Issue TLS certificates (Let's Encrypt is fine):
sudo certbot certonly --standalone -d turn.example.com
mkdir -p certs
sudo cp /etc/letsencrypt/live/turn.example.com/fullchain.pem certs/
sudo cp /etc/letsencrypt/live/turn.example.com/privkey.pem  certs/
sudo chown -R $USER certs

# 4. Export environment variables (or put them in .env next to compose file)
cat > .env <<EOF
TURN_REALM=turn.example.com
TURN_SECRET=$(cat .turn-secret)
TURN_EXTERNAL_IP=<your public IPv4>
EOF

# 5. Open the firewall
# UDP/TCP 3478 (TURN), TCP 5349 (TURNS), UDP 49152-65535 (relay allocations)
```

## Run

```bash
docker compose up -d
docker compose logs -f coturn   # verify it's listening
```

## Smoke test from your laptop

```bash
# Replace USERNAME and CREDENTIAL with values from your backend issuer.
turnutils_uclient -v -t -u USERNAME -w CREDENTIAL turn.example.com
```

## Issuing time-limited credentials from your backend

`use-auth-secret` enables coturn's REST-style time-limited credentials. You
**never** put `TURN_SECRET` in your app — instead your backend computes:

```
username  = "<expiry_unix>:<userId>"     # expiry: now() + ttl
credential = HMAC_SHA1(TURN_SECRET, username) | base64
```

### Reference issuer (Kotlin server)

```kotlin
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun issueTurnCredentials(
    userId: String,
    secret: String,
    ttlSeconds: Long = 4 * 60 * 60,
    realm: String,
): Map<String, Any> {
    val expiry = System.currentTimeMillis() / 1000 + ttlSeconds
    val username = "$expiry:$userId"
    val mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA1"))
    }
    val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
    return mapOf(
        "username"   to username,
        "credential" to credential,
        "ttlSeconds" to ttlSeconds,
        "urls"       to listOf(
            "turn:$realm:3478?transport=udp",
            "turn:$realm:3478?transport=tcp",
            "turns:$realm:5349?transport=tcp",
        ),
    )
}
```

### Reference issuer (Python / FastAPI)

```python
import base64, hmac, hashlib, time

def issue_turn_credentials(user_id: str, secret: str, realm: str, ttl: int = 4 * 60 * 60):
    expiry = int(time.time()) + ttl
    username = f"{expiry}:{user_id}"
    credential = base64.b64encode(
        hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()
    ).decode()
    return {
        "username":   username,
        "credential": credential,
        "ttlSeconds": ttl,
        "urls": [
            f"turn:{realm}:3478?transport=udp",
            f"turn:{realm}:3478?transport=tcp",
            f"turns:{realm}:5349?transport=tcp",
        ],
    }
```

### Consuming credentials in your app

```kotlin
val creds: TurnCredentials = api.fetchTurnCredentials()   // your HTTP call
val ice: TurnConfig = creds.toTurnConfig()

val transport = WebRtcTransport.connect(
    signalingUrl = ...,
    roomId       = ...,
    localPeerId  = ...,
    iceServers   = ice.toJvmIceServers(),
)
```

## Capacity planning

- Each active TURN allocation uses ~5–20 KB/s × the game's bitrate.
- coturn handles 1000+ concurrent allocations on 1 vCPU comfortably.
- Bandwidth is the real cost: budget for 1.5× your peak game traffic, since
  relay flows both ways through the server.
- For 10k+ concurrent users, run multiple coturn instances behind GeoDNS.

## Hardening checklist

- [ ] Firewall: only UDP/TCP 3478, TCP 5349, UDP 49152–65535 open
- [ ] `no-cli` set (don't expose telnet)
- [ ] Real TLS certs at `/etc/coturn/certs/`
- [ ] Long-term `TURN_SECRET` (≥ 256 bits, from `openssl rand`)
- [ ] Secret rotated when staff with access leaves
- [ ] Backend issues short-TTL credentials (4 h or less)
- [ ] Monitor `/var/lib/coturn` for unusual allocation counts
