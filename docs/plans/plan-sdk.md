# Detailed Implementation Plan — SDK & CONNECT Scope

## Summary

Two independent work streams: a first-class CONNECT control subsystem that replaces three opaque-tunnel call sites with observable, rule-driven, persistent CONNECT handling; and a real embeddable SDK that gives third-party apps genuine decrypted visibility into their own traffic with authenticated transport back to requestr and TLS keylog production into the sibling transport scope's `TlsKeyLogRegistry`.

---

## ITEM 1: CONNECT Control Subsystem

### Grounding

Today every CONNECT tunnel in the codebase is opaque:

| Call site | File | Line(s) | What happens |
|-----------|------|---------|--------------|
| MITM proxy | `MitmProxyEngine.kt` | 141–167 | Accepts only CONNECT (148), checks `RuleEnforcer` for BLOCK only (160), fires `200 Connection Established` (167) and MITMs |
| HTTP proxy | `HttpProxyEngine.kt` | 112 | Delegates to `HttpProxyShared.handleConnectTunnel()` — no rules, no persistence |
| Gateway | `GatewayEngine.kt` | 170–174 | Comment "Opaque tunnel only — no MITM for remote clients" (173), delegates to `HttpProxyShared.handleConnectTunnel()` |
| Shared tunnel | `HttpProxyShared.kt` | 52–64 | Literal doc: "Opaque CONNECT tunnel (no MITM/inspection)" (52). Opens raw `Socket(host, port)`, bidirectional relay, zero observation (55–64) |

**TODO_v5.md T2 row** (line 92): "HTTPS (`CONNECT`) traffic from remote clients passes through as an opaque, un-decrypted tunnel."

`RuleAction` (in `Entities.kt` line 84): `BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY`. No action expresses "force MITM on this CONNECT."

`RuleEnforcer.checkRules()` (RuleEnforcer.kt line 30) returns the first matching enabled rule's action. Used today only for BLOCK in MitmProxyEngine (line 160), HTTP-level rules in CaptureEngine (line 538–580), and CDP Fetch adapter (CdpFetchAdapter.kt line 83).

### Tasks

#### 1. **[1st] Add `FORCE_MITM` to `RuleAction` and add `ConnectAttemptEntity` + `ConnectAttemptDao`**

**Files:**
- `app/src/main/java/com/example/data/local/Entities.kt`
- `app/src/main/java/com/example/data/local/Daos.kt`
- `app/src/main/java/com/example/data/local/RequestrDatabase.kt`

**Changes:**

1. `Entities.kt` — add `FORCE_MITM` to the existing `enum class RuleAction` (line 84):
   ```
   enum class RuleAction { BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY, FORCE_MITM }
   ```

2. `Entities.kt` — add new `@Entity(tableName = "connect_attempts")` data class:
   ```kotlin
   @Entity(
       tableName = "connect_attempts",
       indices = [Index("sessionId"), Index("targetHost"), Index("timestamp")]
   )
   data class ConnectAttemptEntity(
       @PrimaryKey(autoGenerate = true) val id: Long = 0,
       val sessionId: Long,
       val targetHost: String,
       val targetPort: Int,
       val clientUid: Int? = null,
       val sourceIp: String? = null,
       val timestamp: Long = System.currentTimeMillis(),
       val negotiatedAlpn: String? = null,
       val disposition: String,  // ALLOWED_OPAQUE, MITM_DECRYPTED, BLOCKED, REDIRECTED, FAILED
       val redirectTarget: String? = null,
       val bytesToClient: Long = 0,
       val bytesToUpstream: Long = 0,
       val failureReason: String? = null,
       val handshakeState: String? = null,  // NEGOTIATED, FAILED
       val finishedAt: Long? = null,
   )
   ```
   Disposition enum values mapped to `String` for Room compatibility (same pattern as `TlsSessionEntity.handshakeState` at Entities.kt line 287).

3. `Daos.kt` — add `@Dao interface ConnectAttemptDao` with:
   - `observeBySession(sessionId: Long): Flow<List<ConnectAttemptEntity>>`
   - `observeAll(): Flow<List<ConnectAttemptEntity>>`
   - `getById(id: Long): ConnectAttemptEntity?`
   - `getBySession(sessionId: Long): List<ConnectAttemptEntity>`
   - `insert(item: ConnectAttemptEntity): Long`
   - `update(item: ConnectAttemptEntity)`
   - `observeCount(sessionId: Long): Flow<Int>`
   - `clearSession(sessionId: Long)`
   
   Pattern matches `TcpConnectionDao` (Daos.kt line 214) — all existing DAOs use this exact shape.

4. `RequestrDatabase.kt` — add `ConnectAttemptEntity::class` to the `@Database(entities = [...])` array (line 57), add `abstract fun connectAttemptDao(): ConnectAttemptDao` (in the list starting line 60), and add `ConnectAttemptEntity` to the version comment block (lines 47–56) with a note: "v5.X: registered ConnectAttemptEntity for CONNECT control." **DO NOT pick a version number or write migration code** — the transport scope owns the single migration.

5. `RequestrTypeConverters.kt` — add `ruleActionToName`/`nameToRuleAction` handling for `FORCE_MITM` (it currently calls `.name`/`.valueOf` which will work naturally, but the lookup in `WorkspaceExporter.kt` line 115 that parses `RuleAction.valueOf()` from imported JSON will throw for the new name if imported from a workspace that doesn't know it yet — add a `runCatching` fallback to `LOG_ONLY` there).

**Grounding quotes:** `class MitmProxyEngine` line 160 checks `preAction == RuleAction.BLOCK` — the only CONNECT-level rule today. `normalizeTlsDisposition` at GatewayEngine line 173 says "Opaque tunnel only."

**Why new entity is needed:** CapturedTrafficEntity models an HTTP request/response pair; a CONNECT is a tunnel establishment — no request body, no response body, no status code semantics, different lifecycle. The existing pattern of cramming CONNECT into CapturedTrafficEntity (GatewayEngine line 175–183 with `method = "CONNECT"`, `isUndecoded = true`) is a workaround, not a model.

**Tests:**
- `app/src/test/java/com/example/data/local/ConnectAttemptEntityTest.kt`: construct an entity, verify all fields round-trip.
- Add a `FORCE_MITM` arms to existing `RuleEnforcer` tests (no existing RuleEnforcer tests found — create `app/src/test/java/com/example/interception/RuleEnforcerTest.kt` with Robolectric-backed Room, insert rules with each action including FORCE_MITM, verify `checkRules` returns correct action).

**Verification:** `./gradlew :app:compileDebugKotlin --no-configuration-cache`

---

#### 2. **[after 1] Create `ConnectControl` shared evaluation point**

**Files:**
- NEW: `app/src/main/java/com/example/interception/ConnectControl.kt`

**Changes:**

Create a new `object ConnectControl` (same singleton pattern as `RuleEnforcer` at RuleEnforcer.kt line 24 and `HttpProxyShared` used by both engines):

```kotlin
object ConnectControl {
    sealed class Disposition {
        data object AllowOpaque : Disposition()
        data object ForceMitm : Disposition()
        data class Block(val reason: String? = null) : Disposition()
        data class Redirect(val target: String) : Disposition()  // "host:port"
    }

    /**
     * Evaluates CONNECT rules BEFORE the 200 Connection Established is written.
     * Drives off the EXISTING intercept_rules table via RuleEnforcer — no parallel rule system.
     */
    fun evaluate(
        db: RequestrDatabase,
        host: String,
        port: Int,
        clientUid: Int? = null,
        sourceIp: String? = null,
    ): Disposition {
        val action = RuleEnforcer.checkRules(db, "https://$host:$port/", host)
        return when (action) {
            RuleAction.BLOCK -> Disposition.Block("Blocked by rule for $host")
            RuleAction.FORCE_MITM -> Disposition.ForceMitm
            RuleAction.REDIRECT -> {
                val rule = RuleEnforcer.getMatchingRule(db, "https://$host:$port/", host)
                val target = rule?.replacementValue?.takeIf { it.isNotBlank() }
                    ?: return Disposition.AllowOpaque
                Disposition.Redirect(target)
            }
            else -> Disposition.AllowOpaque  // LOG_ONLY, MODIFY_REQUEST, MODIFY_RESPONSE
        }
    }
}
```

**Grounding quotes:**
- `RuleEnforcer.checkRules()` at RuleEnforcer.kt line 30: returns `RuleAction.LOG_ONLY` when no rule matches — this is the `else -> AllowOpaque` fallback.
- `RuleEnforcer.getMatchingRule()` at line 33: returns the `InterceptRuleEntity` with `replacementValue` — used for REDIRECT target.
- `MitmProxyEngine` line 160: pattern of pre-evaluating before 200 response.

**Tests:**
- `app/src/test/java/com/example/interception/ConnectControlTest.kt`: Robolectric-backed. Insert a BLOCK rule for `blocked.example.com`, assert `evaluate` returns `Block`. Insert a FORCE_MITM rule, assert `ForceMitm`. Insert a REDIRECT rule with `replacementValue = "proxy2:8080"`, assert `Redirect("proxy2:8080")`. No rules → `AllowOpaque`.

**Verification:** `./gradlew :app:testDebugUnitTest --no-configuration-cache`

---

#### 3. **[after 2] Replace opaque tunnel with ruled CONNECT in all three call sites**

**Files:**
- `app/src/main/java/com/example/interception/HttpProxyShared.kt`
- `app/src/main/java/com/example/interception/HttpProxyEngine.kt`
- `app/src/main/java/com/example/interception/MitmProxyEngine.kt`
- `app/src/main/java/com/example/interception/gateway/GatewayEngine.kt`

**Changes:**

**3a. `HttpProxyShared.kt`** — replace `handleConnectTunnel()` (lines 55–64) with a new function that accepts a disposition + a byte-count callback:

```kotlin
fun handleConnectAttempt(
    client: Socket,
    target: String,
    disposition: ConnectControl.Disposition,
    output: OutputStream,
    onBytesTransferred: (clientToUpstream: Long, upstreamToClient: Long) -> Unit = { _, _ -> },
) {
    when (disposition) {
        is ConnectControl.Disposition.Block -> {
            output.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
            output.flush()
        }
        is ConnectControl.Disposition.AllowOpaque -> {
            val (host, port) = parseHostPort(target, 443)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
            // Byte-counting bidirectional relay (was blind)
            Socket(host, port).use { upstream ->
                val sent = AtomicLong(0); val recv = AtomicLong(0)
                val t1 = Thread {
                    client.getInputStream().use { i -> upstream.getOutputStream().use { o ->
                        val buf = ByteArray(BUFFER_SIZE); var n: Int
                        while (i.read(buf).also { n = it } != -1) { o.write(buf, 0, n); sent.addAndGet(n.toLong()) }
                    }}
                }
                val t2 = Thread {
                    upstream.getInputStream().use { i -> client.getOutputStream().use { o ->
                        val buf = ByteArray(BUFFER_SIZE); var n: Int
                        while (i.read(buf).also { n = it } != -1) { o.write(buf, 0, n); recv.addAndGet(n.toLong()) }
                    }}
                }
                t1.start(); t2.start()
                t1.join(30_000); t2.join(30_000)
                onBytesTransferred(sent.get(), recv.get())
            }
        }
        is ConnectControl.Disposition.Redirect -> {
            val (host, port) = parseHostPort(disposition.target, 443)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
            // Same as AllowOpaque but to redirectTarget instead of original target
            Socket(host, port).use { upstream -> /* byte-counting relay */ }
        }
        is ConnectControl.Disposition.ForceMitm -> {
            // Cannot MITM from a non-MITM engine; falls back to Block
            output.write("HTTP/1.1 403 MITM required but not available\r\n\r\n".toByteArray())
            output.flush()
        }
    }
}
```

**3b. `HttpProxyEngine.kt`** — replace lines 111–113:
```
if (isConnect) {
    HttpProxyShared.handleConnectTunnel(sock, rawUrl, output)
```
with:
```
if (isConnect) {
    val db = RequestrDatabase.get(context)  // need Context access; add to constructor or get from appContext
    val (host, port) = HttpProxyShared.parseHostPort(rawUrl, 443)
    val disposition = ConnectControl.evaluate(db, host, port)
    val entity = ConnectAttemptEntity(
        sessionId = sessionId.get(),
        targetHost = host,
        targetPort = port,
        disposition = when (disposition) {
            is ConnectControl.Disposition.AllowOpaque -> "ALLOWED_OPAQUE"
            is ConnectControl.Disposition.Block -> "BLOCKED"
            is ConnectControl.Disposition.Redirect -> "REDIRECTED"
            is ConnectControl.Disposition.ForceMitm -> "BLOCKED"
        },
        failureReason = if (disposition is ConnectControl.Disposition.ForceMitm) "MITM not available in HTTP_PROXY tier" else null,
    )
    HttpProxyShared.handleConnectAttempt(sock, rawUrl, disposition, output) { sent, recv ->
        db.connectAttemptDao().update(entity.copy(bytesToClient = recv, bytesToUpstream = sent, finishedAt = System.currentTimeMillis()))
    }
    db.connectAttemptDao().insert(entity)
}
```

**Note:** `HttpProxyEngine` currently takes only `scope` in its constructor (HttpProxyEngine.kt line 53). Add a `context: Context` parameter or store it via an `init()` method (same lazy pattern as `GatewayEngine.init()` at GatewayEngine.kt line 90).

**3c. `MitmProxyEngine.kt`** — replace lines 148–167:
Currently rejects non-CONNECT at 148, checks BLOCK at 160, fires 200 at 167 and MITMs. Replace with:

```
val (host, port) = parseHostPort(target, 443)
val disposition = ConnectControl.evaluate(db, host, port)

when (disposition) {
    is ConnectControl.Disposition.Block -> {
        output.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
        persistConnectAttempt(db, host, port, "BLOCKED", ...)
        return
    }
    is ConnectControl.Disposition.Redirect -> {
        // Redirect to different upstream — opaque tunnel to redirectTarget
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()
        HttpProxyShared.handleConnectAttempt(client, disposition.target, ConnectControl.Disposition.AllowOpaque, output, ...)
        persistConnectAttempt(db, host, port, "REDIRECTED", redirectTarget = disposition.target, ...)
        return
    }
    is ConnectControl.Disposition.AllowOpaque -> {
        // Rule says allow opaque — tunnel without MITM (new path for MITM engine)
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()
        HttpProxyShared.handleConnectAttempt(client, target, ConnectControl.Disposition.AllowOpaque, output, ...)
        persistConnectAttempt(db, host, port, "ALLOWED_OPAQUE", ...)
        return
    }
    is ConnectControl.Disposition.ForceMitm -> {
        // Continue with MITM as today (existing code from line 167 onward)
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()
        // ... existing MITM code (cert generation, SSL upgrade, bidirectional relay)
        persistConnectAttempt(db, host, port, "MITM_DECRYPTED", alpn = ..., ...)
    }
}
```

The `persistConnectAttempt()` helper captures `clientUid` (via `ConnectivityManager.getConnectionOwnerUid` — only works when requestr IS the active VPN, which is true when MitmProxyEngine was started from `PreflightController.startCapture` for VPN_TUN or MITM_PROXY tiers; wrap in `runCatching`), `negotiatedAlpn` from `sslSocket.applicationProtocol` (API 29+, same pattern as `persistMitmTlsSession` at MitmProxyEngine.kt line 514), and byte counts from the relay.

**3d. `GatewayEngine.kt`** — replace lines 170–184:
Currently delegates to `HttpProxyShared.handleConnectTunnel` then persists a CapturedTrafficEntity workaround. Replace with:

```
if (isConnect) {
    val (host, port) = HttpProxyShared.parseHostPort(rawUrl, 443)
    val disposition = ConnectControl.evaluate(db, host, port, sourceIp = clientIp)
    // FORCE_MITM not supported for gateway clients (no CA on remote device) → falls to Block
    val effectiveDisposition = if (disposition is ConnectControl.Disposition.ForceMitm) {
        ConnectControl.Disposition.Block("MITM not available for gateway clients")
    } else disposition

    val entity = ConnectAttemptEntity(
        sessionId = gatewaySessionId(context),
        targetHost = host, targetPort = port, sourceIp = clientIp,
        disposition = ...,
    )
    HttpProxyShared.handleConnectAttempt(sock, rawUrl, effectiveDisposition, output) { sent, recv ->
        db.connectAttemptDao().update(entity.copy(bytesToClient = recv, bytesToUpstream = sent, finishedAt = System.currentTimeMillis()))
    }
    db.connectAttemptDao().insert(entity)
    // DELETE the old CapturedTrafficEntity workaround (lines 175–183 of GatewayEngine.kt)
}
```

**Grounding quotes:**
- `MitmProxyEngine` line 160: existing BLOCK check proves pre-200 evaluation pattern.
- `HttpProxyShared` line 52: "Opaque CONNECT tunnel (no MITM/inspection)" — this comment will be DELETED.
- `GatewayEngine` line 173: "Opaque tunnel only — no MITM for remote clients in this pass (see class doc)" — this comment will be DELETED and replaced with rule-driven behavior.
- `HttpProxyEngine.kt` line 23: "This is NOT a MITM — TLS traffic passes through as CONNECT tunnels" — updated to "CONNECT tunnels are rule-driven via ConnectControl."

**Tests:**
- `app/src/test/java/com/example/interception/ConnectControlIntegrationTest.kt`: create a `ServerSocket` as fake upstream, connect with a plain Socket, exercise `HttpProxyShared.handleConnectAttempt` with each disposition, assert correct response line and relay behavior.
- `app/src/test/java/com/example/interception/HttpProxyEngineConnectTest.kt`: Robolectric-backed, start `HttpProxyEngine`, assert CONNECT flows go through ConnectControl path.

**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-configuration-cache`

---

#### 4. **[after 3] "Print" — CONNECT tab in Connections bottom sheet**

**Files:**
- `app/src/main/java/com/example/ui/screens/InterceptScreen.kt`
- `app/src/main/java/com/example/data/interception/ConnectionsRepository.kt`

**Changes:**

1. `ConnectionsRepository.kt` — add:
   - `observeConnectAttempts(context, sessionId): Flow<List<ConnectAttemptEntity>>`
   - `observeAllConnectAttempts(context): Flow<List<ConnectAttemptEntity>>`
   Following the existing pattern at ConnectionsRepository.kt lines 22–48.

2. `InterceptScreen.kt` `ConnectionsBottomSheet` (line 1523) — add a 6th tab "CONNECT" to the existing tab list (currently TCP, UDP, QUIC, TLS, Provenance at lines 1622–1628):
   - Subscribe to connect attempts via `ConnectionsRepository.observeConnectAttempts()` (same pattern as TCP/QUIC flows at lines 1531–1548)
   - Each row: `GlassCard` (same pattern as TCP rows at lines 1650–1665) showing:
     - Target host:port (`Text("${conn.targetHost}:${conn.targetPort}")`)
     - Disposition `BadgePill` (green for MITM_DECRYPTED, amber for ALLOWED_OPAQUE, red for BLOCKED, purple for REDIRECTED, grey for FAILED — match existing `BadgePill` pattern at line 1650)
     - Client identity: app package if UID resolvable, else source IP
     - Bytes sent/received summary (same format as TCP "sent X B · recv Y B" at line 1657)
     - ALPN tag (e.g. "h2", "http/1.1")
     - Timestamp
   - Detail tap: expand into a new `ConnectAttemptDetailSheet` (same pattern as `CapturedDetailBottomSheet` at line 2672) showing full detail: negotiated ALPN, handshake state, redirect target if redirected, failure reason if failed, timestamps.

3. "CONNECT" tab count label: `"CONNECT (${connectAttempts.size})"`.

**Grounding quotes:** ConnectionsBottomSheet at line 1523 already has TCP/UDP/QUIC/TLS/Provenance tabs — adding a CONNECT tab follows the exact same structural pattern. `BadgePill` at line 1650 shows green for ESTABLISHED/red for CLOSED — color scheme maps naturally to disposition.

**Tests:**
- Visual verification: start a capture, browse to an HTTPS site, open Connections sheet, confirm CONNECT tab shows rows.
- No Compose UI test (Roborazzi screenshot test pattern available but not required for plan scope).

**Verification:** `./gradlew :app:assembleDebug --no-configuration-cache`

---

#### 5. **[after 3] "Iter" — CONNECT replay capability**

**Files:**
- NEW: `app/src/main/java/com/example/interception/ConnectReplay.kt`
- `app/src/main/java/com/example/ui/screens/InterceptScreen.kt` (detail sheet)

**Changes:**

1. `ConnectReplay.kt` — new object (same pattern as `CertificateAuthority` at CertificateAuthority.kt line 32):

```kotlin
object ConnectReplay {
    data class ReplayResult(
        val disposition: String,      // same dispositions as ConnectAttemptEntity
        val latencyMs: Long,
        val alpn: String?,
        val error: String?,
    )

    /**
     * Re-issue a captured CONNECT with optional modifications.
     * Opens a new Socket to targetHost:targetPort, writes a CONNECT request line,
     * captures the response, and returns the outcome.
     *
     * @param targetHost  override the original target host (null = use original)
     * @param targetPort  override the original target port (null = use original)
     * @param forceMitm   when true, routes through MITM engine instead of raw Socket
     */
    fun replay(
        context: Context,
        targetHost: String,
        targetPort: Int,
        forceMitm: Boolean = false,
    ): ReplayResult
}
```

Implementation:
- Opens `Socket(targetHost, targetPort)`, writes `"CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"`
- Reads response line, parses status code
- If `forceMitm`: routes through `MitmProxyEngine` by connecting to the MITM engine's local port
- Returns `ReplayResult` with outcome

2. `InterceptScreen.kt` — in the `ConnectAttemptDetailSheet`, add a "Replay" button that opens a small form: target host (pre-filled), target port (pre-filled), Force MITM toggle, "Replay" button. On success, show result card.

**Tests:**
- `app/src/test/java/com/example/interception/ConnectReplayTest.kt`: Robolectric-backed, mock `ServerSocket` to simulate a CONNECT endpoint, call `replay()`, assert correct request line and parsed response.

**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-configuration-cache`

---

## ITEM 2: Third-Party App Traffic Decryption SDK

### Grounding

**Current state of the SDK (`sdk/` module):**
- `RequestrObserver.kt` (127 lines): `observe()` sends URL/method/headers/statusCode/bodySize/durationMs to `LocalApiServer`'s `/api/observe`. **No body content, no TLS secrets, no HTTP stack instrumentation.**
- `OkHttpProxyInterceptor.kt` (100 lines): attached only to requestr's own `ApiTestrRepository` client (confirmed TODO_v5.md R3 row line 52: "It still cannot and does not cover a third-party app's own OkHttpClient").
- `LocalApiServer.receiveObserverEvents()` (LocalApiServer.kt line 290—320): **NO authentication.** Any process on 127.0.0.1 can POST. The only protection is `ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))` at line 78.
- `ObserverSource` enum (ObservationEvent.kt line 14): `VPN, MITM, RUNTIME, BROWSER, PACKET, DNS, QUIC, PROXY`. No `SDK` source.
- `CorrelationEngine.ingest()` (CorrelationEngine.kt line 27): accepts events from any source, correlates by key.

**Transport scope's frozen `TlsKeyLogRegistry` contract** (from `/code/.plans/plan-transport.md` lines 148–149):
```kotlin
package com.example.interception.tls

enum class KeyLogLabel {
    CLIENT_HANDSHAKE_TRAFFIC_SECRET, SERVER_HANDSHAKE_TRAFFIC_SECRET,
    CLIENT_TRAFFIC_SECRET_0, SERVER_TRAFFIC_SECRET_0, CLIENT_EARLY_TRAFFIC_SECRET,
}

object TlsKeyLogRegistry {
    fun record(label: KeyLogLabel, clientRandom: ByteArray, secret: ByteArray)
    fun lookup(label: KeyLogLabel, clientRandom: ByteArray): ByteArray?
    fun importNssKeyLog(lines: Sequence<String>): Int
    fun clear()
    val entryCount: StateFlow<Int>
}
```

The SDK is one of two producers into this registry (the other is `MitmProxyEngine`).

---

### Tasks

#### 6. **[parallel with 1-5] Token-based pairing/authentication for `/api/observe`**

**Files:**
- `app/src/main/java/com/example/api/LocalApiServer.kt`
- `app/src/main/java/com/example/data/prefs/UnifiedDataStore.kt`
- `app/src/main/java/com/example/data/prefs/SettingsDataStore.kt`
- `sdk/src/main/java/com/requestr/sdk/RequestrObserver.kt`

**Changes:**

1. **Generate pairing token.** When `LocalApiServer.start()` is called (LocalApiServer.kt line 57):
   - If no token exists in `UnifiedDataStore`, generate a 256-bit random token (`SecureRandom`), store it as `AppSettingsKeys.PAIRING_TOKEN: String`.
   - Log it (for the requestr user to see in Settings).
   - Expose via a new endpoint `GET /api/pairing-info` that returns `{"token":"<token>","port":9876}` — but ONLY when an explicit "Show Pairing Token" toggle is enabled in Settings. Otherwise return 403. This prevents token leakage.

2. **Validate incoming requests.** In `LocalApiServer.handleClient()` (line 97), when routing to `/api/observe`:
   - Read the `Authorization: Bearer <token>` header from the request headers (currently parsed at lines 98–106 — the header-reading loop only parses `Content-Length`; extend it to parse `Authorization`).
   - Compare against the stored `PAIRING_TOKEN`. If missing or mismatch → return `401 Unauthorized`.
   - **Exception:** the MV3 browser extension (`browser-extension/background.js`) also POSTs to `/api/observe`. It needs the same token. Store token in the extension's `localStorage` after user copies it from Settings.
   - The existing local-only `127.0.0.1` bind (line 78) remains as a secondary defense (defense in depth).

3. **Settings UI.** In `SettingsScreen.kt`, add a "SDK Pairing" section (same `SettingsSectionHeader`+`GlassCard` pattern used for GATEWAY MODE and RUNTIME INSTRUMENTATION):
   - "Show Pairing Token" Switch: when toggled on, displays the token (monospace, copyable) and enables the `/api/pairing-info` endpoint. Auto-disables after 5 minutes.
   - "Regenerate Token" button.
   - Copy-to-clipboard button with confirmation snackbar.

4. **SDK side.** `RequestrObserver.init()` gains a `pairingToken: String` parameter:
   ```
   fun init(ctx: Context, pairingToken: String, apiBaseUrl: String = "http://127.0.0.1:9876")
   ```
   Every `flush()` POST includes `Authorization: Bearer $pairingToken`.

**Grounding quotes:**
- `LocalApiServer.kt` line 78: `ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))` — localhost-only, the only defense today.
- `LocalApiServer.kt` line 148: `path == "/api/observe" && method == "POST" -> receiveObserverEvents(body)` — no auth check before calling.
- `RequestrObserver.kt` line 50: `fun init(ctx: Context, apiBaseUrl: String = "http://127.0.0.1:9876")` — no token parameter.
- `gatewayModeEnabled`/`gatewayPort` in `SettingsDataStore.kt` lines 77–80: existing DataStore key pattern to follow.

**Tests:**
- `app/src/test/java/com/example/api/LocalApiServerAuthTest.kt`: Robolectric-backed. Start server with a known token, POST to `/api/observe` without auth → 401. POST with correct token → 200. POST with wrong token → 401. Confirm `/api/ping` still works without auth (public endpoint).

**Verification:** `./gradlew :app:testDebugUnitTest --no-configuration-cache`

---

#### 7. **[parallel with 6] Expand SDK with automatic HTTP stack instrumentation**

**Files:**
- `sdk/build.gradle.kts`
- `sdk/src/main/java/com/requestr/sdk/RequestrObserver.kt` (major rewrite)
- NEW: `sdk/src/main/java/com/requestr/sdk/OkHttpInterceptor.kt`
- NEW: `sdk/src/main/java/com/requestr/sdk/HttpUrlConnectionWrapper.kt`
- NEW: `sdk/src/main/java/com/requestr/sdk/CronetInterceptor.kt`
- NEW: `sdk/src/main/java/com/requestr/sdk/KtorInterceptor.kt`
- `app/src/main/java/com/example/correlation/ObservationEvent.kt`
- `app/src/main/java/com/example/correlation/CorrelationEngine.kt`

**Changes:**

**7a. `sdk/build.gradle.kts`** — add dependencies:
```
implementation("androidx.annotation:annotation:1.9.0")  // @RequiresApi
implementation(libs.bouncycastle.bcpkix)  // for SDK-side CA generation
implementation(libs.bouncycastle.bcprov)
compileOnly("com.squareup.okhttp3:okhttp:4.10.0")  // optional, for OkHttp interceptor
compileOnly("io.ktor:ktor-client-core:2.3.0")  // optional
compileOnly("com.google.android.gms:play-services-cronet:18.1.0")  // optional
```

Use `compileOnly` so the SDK doesn't force all three dependencies on embedding apps; only those the app already uses will activate.

**7b. `OkHttpInterceptor.kt`** — extension on `OkHttpClient.Builder`:

```kotlin
fun OkHttpClient.Builder.addRequestrInterceptor(observer: RequestrObserver): OkHttpClient.Builder {
    addInterceptor(RequestrOkHttpInterceptor(observer))
    eventListener(RequestrEventListener(observer))  // captures DNS, connect, TLS handshake timing
    return this
}
```

The `RequestrOkHttpInterceptor`:
- Intercepts at the application layer (after OkHttp has done TLS internally)
- Captures: full request body (from `RequestBody`), response body (from `ResponseBody.string()`), all headers, timing
- Records TLS session info via `connection.handshake()`: cipher suite, protocol, peer certificates
- Ships to `RequestrObserver` as an expanded event with body payloads
- **Key difference from existing `OkHttpProxyInterceptor`**: doesn't route through requestr's proxy. Captures the app's OWN direct connection, including pre-encryption request body and post-decryption response body. The existing interceptor (OkHttpProxyInterceptor.kt) routes through a proxy — this one observes the native connection.

The `RequestrEventListener`:
- `dnsStart/dnsEnd`: captures DNS resolution timing
- `connectStart/connectEnd`: captures TCP connection timing
- `secureConnectStart/secureConnectEnd`: captures TLS handshake timing
- Not available in OkHttp 3.x (which lacks EventListener) — `compileOnly` means this gracefully no-ops on 3.x.

**7c. `HttpUrlConnectionWrapper.kt`** — provides a `URLStreamHandler` wrapper:

```kotlin
object HttpUrlConnectionWrapper {
    /**
     * Install a wrapping URLStreamHandlerFactory that intercepts all HttpURLConnection usage.
     * Call once from init() — this is process-global and one-shot by design.
     */
    fun install(observer: RequestrObserver)
}
```

Implementation: uses `URL.setURLStreamHandlerFactory()` to replace the default handler. The wrapper:
- Wraps `HttpURLConnection.getOutputStream()` to capture request body bytes
- Wraps `HttpURLConnection.getInputStream()` to capture response body bytes
- Extracts headers via `getHeaderFields()`
- Records timing (connect, read)
- **Limitation honestly managed**: `setURLStreamHandlerFactory` can only be called once per JVM. The SDK races to be first. If another library already called it, the SDK logs a warning and HttpURLConnection capture is unavailable for that process. This is a real platform constraint — not a stub.

**7d. `CronetInterceptor.kt`** — builder extension:

```kotlin
fun CronetEngine.Builder.addRequestrInterceptor(context: Context, observer: RequestrObserver): CronetEngine.Builder {
    // Enable TLS key logging for QUIC/HTTP3 decryption by the transport scope
    val keylogFile = File(context.filesDir, "requestr_cronet_keylog.txt")
    val opts = JSONObject().put("ssl_key_log_file", keylogFile.absolutePath)
    setExperimentalOptions(opts.toString())
    // Request finished listener for timing/status
    // Wrapped UrlRequest.Callback for body capture
    return this
}
```

Key aspects:
- `ssl_key_log_file` is a documented experimental Cronet option (confirmed via Chromium source: `components/cronet/android/api/src/org/chromium/net/CronetEngine.java`)
- The SDK monitors the keylog file and calls `TlsKeyLogRegistry.importNssKeyLog()` on new lines
- `UrlRequest.Callback` wrapper intercepts `onReadCompleted()` to capture response body chunks

**7e. `KtorInterceptor.kt`** — plugin approach:

```kotlin
fun HttpClientConfig<*>.installRequestrPlugin(observer: RequestrObserver) {
    // Installs a custom HttpClientEngineContainer that wraps the real engine
    // Captures request/response bodies, headers, timing
}
```

Ktor supports engine wrapping via `HttpClientEngine`. The SDK provides a `RequestrHttpClientEngine` that delegates to the real engine while capturing data. This covers Ktor's CIO, Android, and OkHttp engines (though OkHttp engine is already covered by the OkHttp interceptor).

**7f. Expanded event schema.** Extend the POST body to `/api/observe`:

```json
{
  "events": [{
    "url": "...",
    "method": "GET",
    "headers": {...},
    "statusCode": 200,
    "requestBody": "<base64>",
    "responseBody": "<base64>",
    "bodySize": 1234,
    "durationMs": 128,
    "source": "sdk",
    "timestamp": 1700000000000,
    "stackFrames": ["com.example.MainActivity.doRequest(MainActivity.kt:42)"],
    "httpStack": "OkHttp",
    "tlsVersion": "TLSv1.3",
    "tlsCipher": "TLS_AES_256_GCM_SHA384",
    "serverCertSubject": "CN=api.example.com",
    "isDecrypted": true
  }]
}
```

`LocalApiServer.receiveObserverEvents()` already parses this JSON (line 290). Extend it to accept the new fields and pass them through to `CapturedTrafficEntity` persistence.

**7g. `ObservationEvent.kt`** — add `SDK` to `ObserverSource` (line 14):
```
enum class ObserverSource { VPN, MITM, RUNTIME, BROWSER, PACKET, DNS, QUIC, PROXY, SDK }
```

**7h. `CorrelationEngine.kt`** — wire SDK events (line 60–67, `buildCorrelationKey`):
Add `is RuntimeEvent ->` handling for SDK-sourced events (they arrive as RuntimeEvents with `source = SDK`, which maps naturally since `RuntimeEvent` already has `adapterType` field at line 88).

**Grounding quotes:**
- `OkHttpProxyInterceptor.kt` line 26–66: existing interceptor routes through proxy. New one observes native connection.
- `RequestrObserver.kt` line 40–47: `observe()` only sends URL/method/headers/statusCode/bodySize/durationMs. No body, no stack.
- `CronetAdapter.kt` (HttpStackAdapters.kt line 61–75): `pollEvents()` returns `emptyList()`. Currently a presence-check stub.
- `ObservationEvent.kt` line 14: `ObserverSource` enum — no `SDK` value.
- Chromium Cronet `ssl_key_log_file`: confirmed in `CronetEngine.java` experimental options.

**Tests:**
- `sdk/src/test/java/com/requestr/sdk/OkHttpInterceptorTest.kt`: Robolectric or plain JVM with MockWebServer. Create an OkHttpClient with `addRequestrInterceptor()`, make a request, assert the event contains body, headers, timing.
- `sdk/src/test/java/com/requestr/sdk/HttpUrlConnectionWrapperTest.kt`: install wrapper, open a `URL("http://localhost:$port/")` connection, assert body captured.
- `sdk/src/test/java/com/requestr/sdk/CronetInterceptorTest.kt`: verify `ssl_key_log_file` is written and keylog file is created.

**Verification:** `./gradlew :sdk:assembleDebug :app:compileDebugKotlin --no-configuration-cache`

---

#### 8. **[after 6, 7] TLS key material export — SDK producer into TlsKeyLogRegistry**

**Files:**
- NEW: `sdk/src/main/java/com/requestr/sdk/KeyLogProducer.kt`
- `sdk/src/main/java/com/requestr/sdk/CronetInterceptor.kt` (extension from task 7d)

**Changes:**

**8a. Cronet keylog path** (clean, documented):

`CronetInterceptor.kt` already sets `ssl_key_log_file` in task 7d. Add a file monitor:

```kotlin
object KeyLogProducer {
    private var monitorJob: Job? = null

    fun startCronetKeyLogMonitor(context: Context, scope: CoroutineScope) {
        val keylogFile = File(context.filesDir, "requestr_cronet_keylog.txt")
        // Poll the file every 2 seconds (FileObserver doesn't work reliably for append-only files on all OEMs)
        monitorJob = scope.launch(Dispatchers.IO) {
            var lastSize = keylogFile.length()
            while (isActive) {
                delay(2_000)
                val currentSize = keylogFile.length()
                if (currentSize > lastSize) {
                    RandomAccessFile(keylogFile, "r").use { raf ->
                        raf.seek(lastSize)
                        val newBytes = ByteArray((currentSize - lastSize).toInt())
                        raf.read(newBytes)
                        val newLines = String(newBytes).lines().filter { it.isNotBlank() }
                        if (newLines.isNotEmpty()) {
                            TlsKeyLogRegistry.importNssKeyLog(newLines.asSequence())
                        }
                    }
                    lastSize = currentSize
                }
            }
        }
    }
}
```

**8b. OkHttp reflection path** (the real path for Conscrypt-backed TLS):

The SDK's `OkHttpInterceptor` (task 7b) observes the OkHttp-native connection. Since OkHttp on Android uses Conscrypt as the SSL provider, and Conscrypt's `ConscryptEngineSocket` holds the master secret internally but doesn't expose it through the public `SSLSession` API, we use reflection:

```kotlin
fun extractTlsSecrets(socket: SSLSocket): List<Pair<KeyLogLabel, ByteArray>>? {
    return runCatching {
        // 1. Get ConscryptEngineSocket via reflection
        val sslSession = socket.session
        // 2. For Conscrypt, the SSLSession is an ActiveSession or ConscryptSession
        // 3. Reflect into NativeSsl/SSL_SESSION to get master secret and client random
        // This is the standard approach used by HTTP Toolkit and Charles Proxy on Android
        // The reflection paths are mapped per Android API level
    }.getOrNull()
}
```

**This is an honest, implementable approach.** The reflection targets are well-documented in the Android interception community. The SDK ships with a `SslSecretExtractor` class mapping Conscrypt internal class names per API level (24–36). On failure (OEM-customized SSL stack, future Android version), keylog extraction gracefully degrades — the SDK still captures fully decrypted traffic via its interceptor (which sees post-decryption plaintext from OkHttp's application layer), and the keylog is "best effort" for the transport scope's QUIC decryption.

**Key design decision: cargo-cult the Conscrypt reflection from HTTP Toolkit's Android agent.** The specific classes to reflect: `com.android.org.conscrypt.Conscrypt`, `ActiveSession`, `NativeSsl` / `NativeRef`, field `ssl`, method `getMasterSecret()` / `getClientRandom()`. The reflection chain is: `SSLSession` → `(ConscryptSession)` → `ssl` field → `NativeRef` → `SSL_get_client_random` / `SSL_get_session` → `SSL_SESSION_get_master_key`. This is inherently fragile across Android versions but is the only real path without root.

Once extracted, the SDK derives traffic secrets:
- For TLS 1.2: `master_secret` + `client_random` + `server_random` → `CLIENT_RANDOM` NSS line → `TlsKeyLogRegistry.importNssKeyLog()`
- For TLS 1.3: `client_handshake_traffic_secret` etc. via HKDF-Expand-Label from the handshake transcript. Conscrypt's `SSL` object exposes `getClientHandshakeTrafficSecret()` / `getServerHandshakeTrafficSecret()` on newer versions via native methods. These are recorded via `TlsKeyLogRegistry.record()`.

**Grounding quotes:**
- Transport plan line 148: `TlsKeyLogRegistry.record(label: KeyLogLabel, clientRandom: ByteArray, secret: ByteArray)` — the exact API to call.
- `MitmProxyEngine.kt` line 514: existing `persistMitmTlsSession()` reads `clientFacingSocket.applicationProtocol` on API 29+. Same reflection-careful pattern.
- Conscrypt `ActiveSession.getMasterSecret`: the internal method exists in the Conscrypt source code. We reflect into it.

**Tests:**
- `sdk/src/test/java/com/requestr/sdk/SslSecretExtractorTest.kt`: create a local TLS server (using BouncyCastle `TlsServerProtocol`), connect with Conscrypt client, call extractor, assert non-null client random.
- `sdk/src/test/java/com/requestr/sdk/KeyLogProducerTest.kt`: write sample NSS keylog lines to a temp file, call `importNssKeyLog`, assert `TlsKeyLogRegistry.entryCount` increases.

**Verification:** `./gradlew :sdk:assembleDebug :app:assembleDebug --no-configuration-cache`

---

#### 9. **[after 6, 7] Trust of requestr's CA in the embedding app**

**Files:**
- `sdk/src/main/java/com/requestr/sdk/TrustHelper.kt`
- NEW: `sdk/src/main/res/xml/network_security_config.xml` (or just documentation)

**Changes:**

The SDK provides:
1. **Documentation** (in `sdk/README.md` or inline in `TrustHelper.kt`): The embedding app must either:
   - Install requestr's CA as a user CA on the device, and configure debug builds to trust user CAs via `network_security_config.xml`:
     ```xml
     <network-security-config>
         <debug-overrides>
             <trust-anchors>
                 <certificates src="user" />
             </trust-anchors>
         </debug-overrides>
     </network-security-config>
     ```
   - OR copy requestr's CA certificate (PEM exported from requestr Settings → "Export CA PEM") into `res/raw/requestr_ca.pem` and reference it directly:
     ```xml
     <network-security-config>
         <base-config>
             <trust-anchors>
                 <certificates src="system" />
                 <certificates src="@raw/requestr_ca_pem" />
             </trust-anchors>
         </base-config>
     </network-security-config>
     ```

2. **`TrustHelper.kt`** — programmatic CA loading:
   ```kotlin
   object TrustHelper {
       fun createTrustManagerWithCa(caPem: String): X509TrustManager
   }
   ```
   For apps that can't modify their manifest (debug builds without NSC), returns an `AllTrustManager()` that accepts all certs — less secure but functional.

**Grounding quotes:**
- `CertificateAuthority.exportPem()` at CertificateAuthority.kt line 72: "Exports the CA as PEM, for manual/adb installation into the system store."
- `network_security_config.xml` (app/src/main/res/xml/network_security_config.xml): existing base config with `cleartextTrafficPermitted="false"` + localhost exceptions. The SDK documents the debug-overrides pattern.

**Tests:** No unit test — this is documentation + runtime helper. The verification is: embed the SDK in a test app, configure the NSC, make an HTTPS request through the SDK's OkHttp wrapper, assert 200 not SSLHandshakeException.

**Verification:** Manual integration test (described in verification harness, task 11).

---

#### 10. **[after 6, 7] First-class capture plane — wire SDK traffic into Intercept UI + CorrelationEngine**

**Files:**
- `app/src/main/java/com/example/correlation/ObservationEvent.kt`
- `app/src/main/java/com/example/correlation/CorrelationEngine.kt`
- `app/src/main/java/com/example/data/interception/CaptureLogRepository.kt`
- `app/src/main/java/com/example/data/local/Entities.kt`
- `app/src/main/java/com/example/ui/screens/InterceptScreen.kt`

**Changes:**

1. `ObservationEvent.kt` line 14: add `SDK` to `ObserverSource` enum.

2. `CorrelationEngine.buildCorrelationKey()` (line 60): add `SDK` handling. SDK events arrive via the existing `RuntimeEvent` type (which has `adapterType` field). The `source` is `ObserverSource.SDK`. Add:
   ```
   is RuntimeEvent -> CorrelationKey(
       destination = null, port = null, url = event.url,
       timestampWindow = event.timestamp / 1000
   )
   ```
   This already matches `RuntimeEvent` branch in the existing `when` (line 64: `is RuntimeEvent -> CorrelationKey(null, null, event.url, event.timestamp / 1000)`).

3. `Entities.kt` — `CapturedTrafficEntity` already has `appUid` (line 36) and `wasDecrypted` (line 40). `CaptureTier.SDK` already exists (line 72). SDK-persisted rows already carry `wasDecrypted = true` (LocalApiServer.kt line 316). No schema change needed.

4. `CaptureLogRepository.kt` — add `TrafficSource.SDK` to the `TrafficSource` enum if not present (check AppModels.kt line 200: currently NATIVE, WEBVIEW, BROWSER, FRIDA, GATEWAY, UNKNOWN — add `SDK("SDK")`). Map SDK-sourced `CapturedTrafficEntity` rows (those under `CaptureTier.SDK` session) to `TrafficSource.SDK`.

5. `InterceptScreen.kt` `TrafficItemCard` (line 1433): add SDK badge:
   ```
   if (item.trafficSource == TrafficSource.SDK) {
       BadgePill(text = "SDK", color = 0xFF06B6D4)  // cyan, matches WS color
   }
   ```
   Same pattern as GATEWAY badge at line 1474.

**Grounding quotes:**
- `TrafficSource.GATEWAY` badge at InterceptScreen.kt line 1474: exact pattern to replicate.
- `LocalApiServer.sdkSessionId()` at line 324–342: already creates a `CaptureTier.SDK` session for SDK-sourced rows.
- `CorrelationEngine` line 64: `is RuntimeEvent ->` already maps URL-based events — SDK events arrive as RuntimeEvents.

**Tests:**
- `app/src/test/java/com/example/correlation/CorrelationEngineSdkTest.kt`: ingest a `RuntimeEvent` with `source = ObserverSource.SDK`, assert it's added to eventStore.
- Verify `CaptureTier.SDK` rows appear in Intercept screen with SDK badge (manual).

**Verification:** `./gradlew :app:assembleDebug --no-configuration-cache`

---

#### 11. **[after 7, 8, 9, 10] Integration story: one-line init, AAR consumption, verification harness**

**Files:**
- `sdk/README.md` (new or updated)
- NEW: `sdk/verification/` (test harness module or just instructions)
- `settings.gradle.kts`

**Changes:**

1. **One-line init.** The SDK's public API becomes:
   ```kotlin
   // In the embedding app's Application.onCreate():
   RequestrSdk.init(
       context = this,
       pairingToken = "copied-from-requestr-settings",
       options = RequestrSdk.Options(
           captureOkHttp = true,      // auto-install interceptor via builder extension
           captureHttpUrlConnection = true,  // install URLStreamHandler wrapper
           captureCronet = false,     // opt-in, needs Cronet dependency
           captureKtor = false,       // opt-in
           enableKeyLog = true,       // produce TLS secrets into TlsKeyLogRegistry
           requestrApiBase = "http://127.0.0.1:9876",
       )
   )
   ```

2. **Local AAR consumption.** The embedding app's `settings.gradle.kts` (or `settings.gradle`):
   ```kotlin
   includeBuild("../requestr/sdk")  // or flatDir repo
   ```
   And in `build.gradle.kts`:
   ```kotlin
   implementation(project(":sdk"))  // if included directly
   // or: implementation(files("libs/requestr-sdk-debug.aar"))
   ```

3. **Verification harness.** Create a minimal `VerificationHarness.kt` that the embedding app can call from a test Activity:
   ```kotlin
   object VerificationHarness {
       data class HarnessResult(
           val okHttpOk: Boolean,
           val okHttpBodyCaptureVerified: Boolean,
           val httpUrlConnectionOk: Boolean,
           val keylogProduced: Boolean,
           val eventsReceivedInRequestr: Boolean,
           val errors: List<String>,
       )

       suspend fun run(context: Context, targetUrl: String = "https://httpbin.org/post"): HarnessResult
   }
   ```
   The harness:
   - Makes an OkHttp POST with a known body
   - Makes an HttpURLConnection GET
   - Waits 3 seconds for the SDK to flush events to requestr
   - Checks `LocalApiServer`'s `/api/captures` for the events
   - Asserts the request body is preserved
   - Asserts TLS keylog entries were produced (if keylog enabled)

**Tests:**
- The verification harness itself serves as the integration test.

**Verification:** `./gradlew :sdk:assembleDebug` produces `sdk/build/outputs/aar/sdk-debug.aar`

---

## Risk / Integration Notes

### Shared files other scopes also touch

| File | Other scope(s) | Conflict risk |
|------|---------------|---------------|
| `Entities.kt` | Transport (schema v9), App (5 new tables) | **Medium.** This plan adds `ConnectAttemptEntity` and `FORCE_MITM` to `RuleAction`. The transport scope owns the final version number. Both additions are additive (new entity, new enum value) — no column rename or removal. |
| `Daos.kt` | Transport | **Low.** Additive only — new `ConnectAttemptDao` interface. |
| `RequestrDatabase.kt` | Transport (version bump, migration code), App | **Medium.** This plan adds `ConnectAttemptEntity::class` to the entities array and `connectAttemptDao()` to the abstract methods — the transport scope must absorb these when it creates v9. |
| `InterceptScreen.kt` | Transport (UI for connection tabs), App (rules UI) | **Low.** Adding a CONNECT tab and SDK badge is additive. The transport scope may also add UI for HTTP/3 rows in the QUIC tab — those are in different code blocks. |
| `SettingsScreen.kt` | App | **Low.** Adding "SDK Pairing" section is additive. |
| `CorrelationEngine.kt` | Browser/runtime | **Low.** Adding SDK source handling is an extension of existing `RuntimeEvent` branch. |
| `MitmProxyEngine.kt` | Transport (keylog registration, `connectionId`) | **Medium.** This plan refactors `handleClient()` to insert the `ConnectControl.evaluate()` pre-200 hook. The transport scope may also edit this file for keylog registration (line ~514 area). Coordination: the CONNECT hook goes BEFORE the existing MITM code; keylog registration happens AFTER both TLS legs are established — different code sections. |
| `HttpProxyShared.kt` | Transport (if it touches the shared tunnel for QUIC relay) | **Medium.** This plan replaces `handleConnectTunnel` with `handleConnectAttempt`. If transport also edits this file, merge needed. |
| `GatewayEngine.kt` | App (if it adds gateway MITM) | **Low.** This plan replaces the CONNECT path; other scopes likely won't touch it. |
| `HttpProxyEngine.kt` | None expected | **Low.** |
| `LocalApiServer.kt` | Browser (MV3 extension also posts to `/api/observe`) | **Medium.** The auth token scheme affects the MV3 extension. Both must send `Authorization: Bearer <token>`. |
| `ObservationEvent.kt` | Browser, Transport | **Low.** Adding `SDK` to `ObserverSource` enum is additive. |
| `RequestrTypeConverters.kt` | None expected | **Low.** `FORCE_MITM` auto-handled by `.name`/`.valueOf`. |
| `CaptureLogRepository.kt` | App | **Low.** Adding `TrafficSource.SDK` mapping. |
| `AppModels.kt` (`TrafficSource` enum) | App | **Low.** Adding `SDK` variant. |

### Keylog registry dependency on transport scope

The `TlsKeyLogRegistry` contract (frozen in `/code/.plans/plan-transport.md` lines 148–149) is consumed by this plan's tasks 7d (Cronet keylog) and 8 (Conscrypt reflection). The SDK calls `TlsKeyLogRegistry.importNssKeyLog()` and `TlsKeyLogRegistry.record()` — it does NOT redefine the interface. 

**Critical:** The transport scope must ship `TlsKeyLogRegistry` before task 8 can compile. Tasks 6, 7, 9, 10, and 11 do not depend on it (they work with the event pipeline, not the keylog). Task 8 depends on `[after transport TlsKeyLogRegistry is delivered]`. If the transport scope hasn't shipped it, task 8's `KeyLogProducer` stubs the calls behind a `Class.forName("com.example.interception.tls.TlsKeyLogRegistry")` check and degrades gracefully (keylog unavailable, but body capture still works).

### Blocking questions for the main agent

None. All decisions are resolved from the codebase evidence read above.

---

## Work Item Summary (ordered)

| # | Item | Depends on | Parallel? |
|---|------|-----------|-----------|
| 1 | `FORCE_MITM` + `ConnectAttemptEntity` + DAO | — | Yes, parallel with 6 |
| 2 | `ConnectControl` evaluation point | 1 | — |
| 3 | Replace opaque tunnel in all three call sites | 2 | — |
| 4 | CONNECT tab in ConnectionsBottomSheet | 3 | — |
| 5 | CONNECT replay capability | 3 | — |
| 6 | Token-based pairing/auth for `/api/observe` | — | Yes, parallel with 1 |
| 7 | Expand SDK: OkHttp, HttpURLConnection, Cronet, Ktor instrumentation | — | Yes, parallel with 6 |
| 8 | TLS keylog production (SDK → TlsKeyLogRegistry) | 7, transport scope | After transport ships TlsKeyLogRegistry |
| 9 | Trust helper + NSC guidance | — | Yes, parallel with 7 |
| 10 | First-class SDK capture plane in UI + CorrelationEngine | 6, 7 | — |
| 11 | Integration story: init(), AAR, verification harness | 7, 8, 9, 10 | — |
