
Now I have a comprehensive picture. Let me write the detailed implementation plan directly here — no file, no commit, no push.

Let me synthesize everything I've learned:

1. The TODO.md says almost everything is done (all checkboxes marked [x])
2. The AUDIT_REPORT.md (v3.4) says the opposite — 12 BROKEN, 14 STUB, 5 PARTIALLY WORKING
3. The v5_plan.md / root_plan.md describes the actual state vs. the TODO's claims
4. The requestr_v5_plan.md (the new file) describes 26 missing features for v5

The key insight: **TODO.md is lying.** It marks items as done that the AUDIT_REPORT and the actual code prove are NOT done. This is the "stub" pattern the v3.1 spec warns about — "Claims success without the underlying operation actually having been verified."

Let me verify this claim by checking the actual code state against the TODO claims:

TODO #1 "CaptureEngine live Settings observation (was already correct)" — but AUDIT_REPORT finding #6 says CaptureEngine reads `SettingsRepository.settings.value` (stale snapshot). And the code at CaptureEngine.kt:195 confirms `val settings = SettingsRepository.settings.value`. So TODO #1 is FALSE — it's marked done but the code still uses the snapshot.

TODO #3 "CaptureMethodWatcher health checks (already fully implemented)" — but the code at CaptureMethodWatcher.kt:72 gates on packetCount, not decryption. And AUDIT_REPORT finding #4 says it "watches nothing." So TODO #3 is FALSE.

TODO #4 "MitmProxyEngine wired as default when CA installed (PreflightController already resolves MITM_PROXY)" — but PreflightController.kt:103-108 defaults to VPN_TUN, NOT MITM_PROXY. So TODO #4 is FALSE.

TODO #5 "MitmProxyEngine per-host cert issuance from CertificateAuthority" — the code DOES do this (MitmProxyEngine.kt:169-198 calls CertificateAuthority.getOrCreate). This one is actually TRUE.

TODO #6 "Frida: chip now shows 'Detected (CLI only)'" — need to verify.

TODO #7 "Rules engine: implement all actions beyond BLOCK" — but CaptureEngine.kt:203-242 only LOGS the action, doesn't enforce on wire. BLOCK returns from persistence but doesn't stop the packet. So TODO #7 is FALSE.

So the pattern is clear: **TODO.md is marking items as done that are NOT done.** This is the exact "stub" pattern the v3.1 spec defines.

Now let me write the comprehensive implementation plan. I'll structure it as:

1. The real state vs. TODO's claims (the lie)
2. Phase 1: Fix the TODO lies + make the interceptor actually work (root)
3. Phase 2: Non-root traditional path
4. Phase 3: v5 features from requestr_v5_plan.md
5. Implementation order with concrete file changes

Let me write this now.




Now I have the full picture. Let me write the detailed implementation plan directly here.

---

# REQUESTR v5 — DETAILED IMPLEMENTATION PLAN

## 0. THE FOUNDATIONAL PROBLEM: `TODO.md` IS LYING

Before any code, you must understand this. `TODO.md` marks **every single item as `[x]` done**. The AUDIT_REPORT (v3.4) and the actual source code prove otherwise. This is the exact "stub" pattern the v3.1 spec defines: *"Claims success without the underlying operation actually having been verified."*

Here is the verification of each TODO claim against the actual code:

| TODO # | TODO claims | Actual code state | Verdict |
|---|---|---|---|
| #1 | "CaptureEngine live Settings observation (was already correct — StateFlow.value IS live)" | `CaptureEngine.kt:195` reads `SettingsRepository.settings.value` — a **stale snapshot**, not live. Toggles don't take effect until process death. | **FALSE** |
| #3 | "CaptureMethodWatcher health checks (already fully implemented with per-method probes)" | `CaptureMethodWatcher.kt:72` gates HEALTHY on `packetCount`. There is no `decryptedTransactionCount` anywhere. Cannot distinguish working from silently failing. | **FALSE** |
| #4 | "MitmProxyEngine wired as default when CA installed (PreflightController already resolves MITM_PROXY)" | `PreflightController.kt:103-108` defaults to `VPN_TUN` (portless, no MITM). MITM_PROXY only when `recordMode == "PROXY"` **and** CA installed. | **FALSE** |
| #5 | "MitmProxyEngine per-host cert issuance from CertificateAuthority" | `MitmProxyEngine.kt:169-198` DOES call `CertificateAuthority.getOrCreate`. | **TRUE** |
| #6 | "Frida: chip now shows 'Detected (CLI only)'" | Need to verify in InterceptScreen. Likely true (honest label). | **LIKELY TRUE** |
| #7 | "Rules engine: implement all actions beyond BLOCK" | `CaptureEngine.kt:203-242` only **logs** the action. `BLOCK` returns from persistence but does NOT stop the packet reaching the server. No wire enforcement anywhere. | **FALSE** |
| #8 | "FlowExecutor complete" | `FlowExecutor.kt` only handles API node. Other nodes are no-ops. | **FALSE** |
| #12 | "RequestrVpnService only for VPN_TUN (already correct in dispatch)" | `PreflightController.startCapture()` DOES dispatch correctly per method. | **TRUE** |

**Conclusion:** TODO #1, #3, #4, #7, #8 are marked done but are NOT done. This is the single biggest problem — the project's own source-of-truth is unreliable, which is why the same class of bug survived two QA passes.

### The actual bug chain (9 bugs, all wiring, no missing libraries)

1. **`CaptureEngine.kt:106`** — `if (stream.isTls) return` discards all TLS bytes after SNI extraction
2. **`RequestrVpnService.kt:140-149`** — VPN tier has no MITM proxy; tun delivers encrypted bytes to a plaintext engine
3. **`CaptureEngine.kt:265`** — `wasDecrypted = false` hardcoded; UI can never show decrypted state
4. **`PreflightController.kt:103-108`** — default tier is non-MITM VPN_TUN
5. **`MitmProxyEngine` only started for MITM_PROXY** — CA generated but never presented in VPN mode
6. **`CaptureMethodWatcher.kt:72`** — gates HEALTHY on packet count, not decryption
7. **`CaptureEngine.kt:203-242`** — rules matched but never enforced on the wire
8. **No HTTP/2/WebSocket/QUIC/SSE parsing of decrypted streams**
9. **`TODO.md` lies about all of the above**

**The libraries are NOT missing.** BouncyCastle (`bouncycastle-bcpkix`, `bouncycastle-bcprov` — `build.gradle.kts:93-94`) and JSSE (`javax.net.ssl.SSLContext`) are both present and correctly used in `MitmProxyEngine`. The failure is **wiring**.

---

## PHASE 1 — FIX THE LIES + MAKE ROOT INTERCEPTION ACTUALLY WORK

### Step 0: Fix `TODO.md` first (so it stops lying)

Mark #1, #3, #4, #7, #8 as `[ ]` (not done). Add a note: *"Verified against source — these were marked done but are NOT done. See AUDIT_REPORT findings #1, #2, #4, #6, #15."* A TODO that lies is worse than no TODO.

### Step 1: System CA installation (`CertificateAuthority.kt`)

Add `installToSystem()`. Android 14+ uses the Conscrypt APEX module at `/apex/com.android.conscrypt/cacerts`, not `/system/etc/security/cacerts`.

```kotlin
// CertificateAuthority.kt — add
sealed class InstallResult {
    data class Success(val path: String) : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}

suspend fun installToSystem(context: Context): InstallResult = withContext(Dispatchers.IO) {
    val material = getOrCreate(context)
    val pem = exportPem(context)
    val certHash = getSubjectHash(context)
    val apexDir = "/apex/com.android.conscrypt/cacerts"
    val legacyDir = "/system/etc/security/cacerts"
    val targetDir = if (File(apexDir).exists()) apexDir else legacyDir
    val targetFile = "$targetDir/${certHash}.0"

    val script = """
        CA_SRC=/data/local/tmp/requestr_ca.pem
        CA_DST=$targetFile
        cat > "$CA_SRC" <<'REQUESTR_EOF'
$pem
REQUESTR_EOF
        if [ -d "$apexDir" ]; then
            mount -t tmpfs tmpfs "$apexDir" 2>/dev/null
            cp /system/etc/security/cacerts/* "$apexDir/" 2>/dev/null
        fi
        mount -o rw,remount "$targetDir" 2>/dev/null || mount -o rw,remount /system 2>/dev/null
        cp -f "$CA_SRC" "$CA_DST"
        chown root:root "$CA_DST"
        chmod 644 "$CA_DST"
        chcon u:object_r:system_file:s0 "$CA_DST" 2>/dev/null
        mount -o ro,remount "$targetDir" 2>/dev/null || mount -o ro,remount /system 2>/dev/null
        restorecon -R "$targetDir" 2>/dev/null
    """.trimIndent()

    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
    val exit = proc.waitFor()
    if (exit == 0) InstallResult.Success(targetFile)
    else InstallResult.Failure("exit=$exit")
}
```

**Verification:** `su -c "ls /apex/com.android.conscrypt/cacerts/ | grep requestr"` (Android 14+) or `su -c "ls /system/etc/security/cacerts/ | grep requestr"` (older).

### Step 2: Make MITM the default tier (`PreflightController.kt`)

**Change `run()` default (lines 103-108):**
```kotlin
else -> {
    if (caInstalled) {
        method = CaptureMethod.MITM_PROXY
        tier = ActiveTier.PROXY
        port = prefs.proxyPort.first()
    } else {
        method = CaptureMethod.VPN_TUN
        tier = ActiveTier.VPN
        port = 0
    }
}
```

**Change `startCapture()` MITM_PROXY branch (lines 149-153):**
```kotlin
CaptureMethod.MITM_PROXY -> {
    val mitmPort = mitmProxy.start(context, port)
    if (capabilities.isRootUsable) {
        rootEngine.start(mitmPort, android.os.Process.myUid())
    }
}
```

**Change `startCapture()` ROOT_IPTABLES branch:**
```kotlin
CaptureMethod.ROOT_IPTABLES -> {
    val mitmPort = mitmProxy.start(context, port)
    rootEngine.start(mitmPort, android.os.Process.myUid())
}
```

**Add UDP:443 redirect to `RootIptablesEngine.kt` (after line 57):**
```kotlin
add("iptables -t nat -A OUTPUT -p udp --dport 443 -j REDIRECT --to-port $port")
```

### Step 3: Feed decrypted bytes to the log (`MitmProxyEngine.kt`)

Add a `CaptureLogRepository` callback and parse the decrypted stream:

```kotlin
// Constructor change
class MitmProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onDecrypted: ((host: String, requestBytes: ByteArray, responseBytes: ByteArray, statusCode: Int) -> Unit)? = null,
)

// In handleClient(), after upstreamSsl.startHandshake():
val t1 = Thread {
    val reqBytes = relayAndCapture(decryptedInput, upstreamOutput, host)
    onDecrypted?.invoke(host, reqBytes, ByteArray(0), 0)
}
val t2 = Thread { relay(upstreamInput, decryptedOutput, "←") }
```

Use the existing `HttpParser.tryParseRequest` / `tryParseResponse` on the **decrypted** byte arrays — they work on plaintext, no changes needed.

### Step 4: Remove the TLS discard (`CaptureEngine.kt` + `TcpStream.kt`)

**`CaptureEngine.kt:106`:**
```kotlin
if (stream.isTls && !stream.decryptedByMitm) return
```

**`TcpStream.kt` — add field:**
```kotlin
/** True when this stream's bytes arrived already decrypted by the MITM proxy. */
var decryptedByMitm: Boolean = false
```

The MITM feed sets it true; the raw tun feed leaves it false.

### Step 5: Wire the watcher to decryption (`InterceptionState.kt` + `InterceptionStateRepository.kt` + `CaptureMethodWatcher.kt`)

**`InterceptionState.kt` — add field:**
```kotlin
val decryptedTransactionCount: Int = 0,
```

**`InterceptionStateRepository.kt` — add mutator:**
```kotlin
fun addDecryptedTransactions(count: Int) {
    _state.update { it.copy(decryptedTransactionCount = it.decryptedTransactionCount + count) }
}
```

**`CaptureMethodWatcher.kt:72-78` — gate on decryption:**
```kotlin
val currentDecrypted = state.decryptedTransactionCount
val receivedTraffic = currentDecrypted > lastDecryptedCount
lastDecryptedCount = currentDecrypted
val isHealthy = receivedTraffic && methodOk
```

### Step 6: Enforce rules on the wire (`MitmProxyEngine.kt`)

Move rule enforcement from `CaptureEngine.persistTransaction()` (which only logs) into `MitmProxyEngine.handleClient()` (which controls the wire):

```kotlin
when (ruleAction) {
    BLOCK -> { client.close(); return }
    REDIRECT -> { upstream = Socket(newHost, newPort) }
    MODIFY_REQUEST -> { rewrite request bytes before relaying }
    MODIFY_RESPONSE -> { rewrite response bytes before sending to client }
    LOG_ONLY -> { /* persist as usual */ }
}
```

### Step 7: Honest UI (`InterceptScreen.kt` status card, ~line 320)

Add a decryption indicator:
```kotlin
Text(
    text = when {
        interceptionState.decryptedTransactionCount > 0 ->
            "HTTPS DECRYPTED ✓  ·  ${interceptionState.decryptedTransactionCount} transactions"
        interceptionState.captureMethod == CaptureMethod.VPN_TUN ->
            "PLAINTEXT ONLY  ·  HTTPS bodies not captured"
        else -> "DECRYPTING..."
    },
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    color = if (interceptionState.decryptedTransactionCount > 0) InterceptorActiveGreen else TextMuted
)
```

### Step 8: Verification (root)

- [ ] `su -c "ls /apex/com.android.conscrypt/cacerts/ | grep requestr"` (Android 14+)
- [ ] `su -c "iptables -t nat -L OUTPUT -n"` shows REDIRECT for :80, :443/tcp, :443/udp
- [ ] Chrome → `https://httpbin.org/json` → traffic log shows full JSON body
- [ ] `wasDecrypted = true` on persisted entities
- [ ] `decryptedTransactionCount` increments
- [ ] Watcher reports HEALTHY
- [ ] BLOCK rule actually prevents the request reaching the server
- [ ] Status card shows "HTTPS DECRYPTED ✓"

---

## PHASE 2 — NON-ROOT: THE TRADITIONAL PATH (Requable/HttpCanary model)

Non-root users cannot redirect another app's traffic. The honest model:

### 2.1 Honest VPN tier notification (`RequestrVpnService.kt:257`)
```kotlin
.setContentText("Local VPN active · HTTPS bodies not captured without proxy mode")
```

### 2.2 Honest undecoded rows (`Entities.kt` + `CaptureEngine.kt`)

**`Entities.kt` — add field to `CapturedTrafficEntity`:**
```kotlin
/** True when this row is an SNI-only observation (no body decrypted). */
val isUndecoded: Boolean = false,
```

**`CaptureEngine.kt:79-95` — mark undecoded rows:**
```kotlin
val entity = CapturedTrafficEntity(
    // ... existing fields ...
    wasDecrypted = false,
    isUndecoded = true,
)
```

### 2.3 OkHttp proxy interceptor for own apps (`OkHttpProxyInterceptor.kt` — new file)

This is the non-root path Requable/HttpCanary use. Your own apps connect to the local MITM proxy, which terminates TLS with a CA-signed cert. Works without root, without VPN consent, without iptables.

```kotlin
package com.example.interception

import android.content.Context
import com.example.data.interception.InterceptionStateRepository
import okhttp3.Interceptor
import java.net.Proxy
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.security.SecureRandom

class OkHttpProxyInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val port = InterceptionStateRepository.state.value.port
        if (port <= 0) return chain.proceed(chain.request())
        val proxy = java.net.Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
        val client = chain.client().newBuilder()
            .proxy(proxy)
            .sslSocketFactory(sslSocketFactory(context))
            .build()
        return client.newCall(chain.request()).execute()
    }

    private fun sslSocketFactory(context: Context): SSLSocketFactory {
        val caMaterial = kotlinx.coroutines.runBlocking { CertificateAuthority.getOrCreate(context) }
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry("requestr-ca", caMaterial.keyPair.private, "".toCharArray(),
            arrayOf(caMaterial.certificate))
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "".toCharArray())
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore as KeyStore?)
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        return sslContext.socketFactory
    }
}
```

### 2.4 Non-root honest status banner (`InterceptScreen.kt`)
```kotlin
Text(
    text = when {
        capabilities.isRootUsable -> "ROOT · HTTPS DECRYPTED ✓"
        capabilities.isShizukuUsable -> "SHIZUKU · HTTPS DECRYPTED ✓"
        captureRecordMode == "PROXY" && capabilities.isCaInstalled -> "PROXY · HTTPS DECRYPTED ✓"
        else -> "VPN · HTTPS bodies not captured (plaintext only)"
    },
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    color = when {
        capabilities.isRootUsable || capabilities.isShizukuUsable -> InterceptorActiveGreen
        else -> TextMuted
    }
)
```

### 2.5 Verification (non-root)

- [ ] Plaintext HTTP request/response bodies persisted
- [ ] HTTPS rows show SNI + `isUndecoded = true`
- [ ] Status banner honestly says "HTTPS bodies not captured (plaintext only)"
- [ ] `decryptedTransactionCount` stays 0 (honest)
- [ ] Own apps using `OkHttpProxyInterceptor` get full HTTPS decryption
- [ ] Force-stop → reopen → VPN resumes, traffic log persists

---

## PHASE 3 — v5 FEATURES (from `requestr_v5_plan.md`)

The v5 plan identifies **26 missing feature groups**. The implementation order from §28 of that plan is:

### PHASE 3.1 — Foundation (do first, others depend on these)

**3.1.1 Capability model** — `CapabilityProbe` already exists but needs a `DeviceCapabilities` data class that aggregates: Android version, architecture, root type, Shizuku, VPN permission, developer options, USB debugging, ADB connectivity, CA installed, system CA access, IPv6, network state, battery restrictions, background execution. File: `diagnostics/CapabilityDetector.kt`.

**3.1.2 Observation event model** — Create `ObservationEvent` sealed class with subtypes for every observation plane: `NetworkEvent`, `RuntimeEvent`, `BrowserEvent`, `PacketEvent`. Each carries: timestamp, source plane, request ID (if correlated), payload. File: `correlation/ObservationEvent.kt`.

**3.1.3 Connection model** — Create `TcpConnectionEntity` and `UdpFlowEntity` and `QuicConnectionEntity` in `Entities.kt`:
```kotlin
@Entity(tableName = "tcp_connections")
data class TcpConnectionEntity(
    @PrimaryKey val connectionId: String,
    val source: String, val destination: String,
    val sourcePort: Int, val destinationPort: Int,
    val state: String, val createdAt: Long,
    val closedAt: Long? = null,
    val bytesSent: Long = 0, val bytesReceived: Long = 0,
    val retransmissions: Int = 0,
)
```
This is the foundation for correlation. The lineage is: TCP connection → TLS session → HTTP connection → HTTP stream → Request.

**3.1.4 Correlation engine** — `correlation/CorrelationEngine.kt`. Joins events from multiple planes using combinations of: timestamp, process, package, destination, port, URL, connection, thread, request ID, stream ID, socket, headers. Never depend on one signal. Each correlated request gets a `REQ-NNNN` ID.

### PHASE 3.2 — Protocol layers

**3.2.1 TCP/UDP** — `PacketPump` already parses IPv4/IPv6/TCP/UDP. Extract into `packet/` package: `PacketCaptureEngine.kt`, `Packet.kt`, `PacketParser.kt`, `IpParser.kt`, `TcpParser.kt`, `UdpParser.kt`, `Ipv6Parser.kt`, `PcapWriter.kt`, `PacketRepository.kt`. Add pcap file writing (`.pcap` format) for external analysis.

**3.2.2 TLS observation layer** — `interception/tls/TlsDetector.kt`, `TlsSession.kt`, `TlsHandshake.kt`, `TlsCertificate.kt`, `TlsMetadata.kt`, `TlsRepository.kt`. Track: version, cipher, ALPN, server name, certificate chain, handshake timestamps, connection ID, protocol. **Critical distinction:** "HTTP decrypted" and "TLS observed but HTTP opaque" must be different states.

**3.2.3 HTTP/1.1** — `HttpParser.kt` already works. Move to `http/Http1Parser.kt`.

**3.2.4 HTTP/2** — `http/Http2Parser.kt`. HPACK decoder (RFC 7541) with static+dynamic tables. Frame parser for HEADERS, DATA, SETTINGS, PRIORITY, GOAWAY, RST_STREAM. Stream ID tracking. This requires MITM running to get plaintext. Add dependency: `implementation("org.apache.httpcomponents:httpclient-android")` or a lightweight HPACK lib.

**3.2.5 HTTP/3 / QUIC** — `http3/Http3Parser.kt`, `Http3Connection.kt`, `Http3Stream.kt`, `Http3Frame.kt`, `QpackDecoder.kt`, `Http3Repository.kt`. QUIC runs over UDP — redirect UDP:443 too (done in Step 2 above). Full HTTP/3 needs a QUIC stack (`quiche` or `quic-go` via JNI). SNI-only fallback via existing `QuicParser.kt`.

### PHASE 3.3 — Runtime instrumentation

**3.3.1 Runtime engine** — `runtime/RuntimeInstrumentationEngine.kt`, `RuntimeSession.kt`, `RuntimeEvent.kt`, `RuntimeEventBus.kt`, `RuntimeAdapter.kt`, `RuntimeCapability.kt`, `RuntimeTarget.kt`, `RuntimeSessionManager.kt`. Adapter pattern: Frida adapter, Xposed-compatible adapter, debugger adapter, native adapter, app SDK adapter.

**3.3.2 Frida** — `runtime/frida/FridaClient.kt`, `FridaSession.kt`, `FridaScript.kt`, `FridaScriptRepository.kt`, `FridaEventParser.kt`, `FridaProcessManager.kt`. Push frida-server to `/data/local/tmp/`, start it, inject scripts, stream output. **Crucial rule:** UI must expose capability states: Unavailable → Detected → Connected → Attached → Streaming → Failed → Stopped. Never say "Frida: Available" when nothing is connected.

**3.3.3 HTTP client adapters** — `runtime/http/HttpStackAdapter.kt` (interface with `detect()`, `attach()`, `observe()`), `HttpStackDetector.kt`, `OkHttpAdapter.kt`, `CronetAdapter.kt`, `WebViewAdapter.kt`, `BrowserFetchAdapter.kt`. Classify traffic as OkHttp / Cronet / URLConnection / WebView / Browser Fetch / Native / Unknown. Confidence levels: Detected / Likely / Possible / Unknown. Never claim certainty unless evidence supports it.

**3.3.4 Runtime-to-network correlation** — `correlation/CorrelationEngine.kt`, `CorrelationEvent.kt`, `CorrelationKey.kt`, `CorrelationRule.kt`, `CorrelationStore.kt`, `RequestMatcher.kt`, `RuntimeNetworkMatcher.kt`. Matching signals: timestamp, process, package, destination, port, URL, connection, thread, request ID, stream ID, socket, headers. Never depend on one signal.

### PHASE 3.4 — Browser

**3.4.1 CDP Browser Bridge** — `browser/BrowserBridge.kt`, `CdpClient.kt`, `CdpConnection.kt`, `CdpMessage.kt`, `CdpEvent.kt`, `CdpRequest.kt`, `CdpResponse.kt`, `CdpTarget.kt`, `CdpTargetManager.kt`, `CdpNetworkAdapter.kt`, `CdpFetchAdapter.kt`, `CdpRuntimeAdapter.kt`, `BrowserCaptureEngine.kt`, `BrowserInterceptionController.kt`, `BrowserCapabilityDetector.kt`. WebSocket connection to Chromium's devtools port. CDP Network domain: requestWillBeSent, responseReceived, loadingFinished, loadingFailed. CDP Fetch domain: pause matching requests and continue/fail/fulfill. Data: `data/browser/BrowserTargetEntity.kt`, `BrowserRequestEntity.kt`, `BrowserEventEntity.kt`, `BrowserSessionEntity.kt`. Repository: `data/repository/BrowserRepository.kt`.

**Critical rule:** Do not make the UI say "Browser interception: ON" until CDP socket connected → Target discovered → Network.enable succeeded. UI should say: CONNECTED / NETWORK: ACTIVE / RUNTIME: ACTIVE / FETCH: ACTIVE.

**3.4.2 Browser extension bridge** — Chrome extension (`browser-extension/manifest.json`, `background/service-worker.js`, `devtools/devtools.js`, `popup/popup.js`, `bridge/requestr-client.js`). Android side: `browser/BrowserExtensionBridge.kt`. Uses existing Local API on 127.0.0.1:9876. Extension functions: capture current page, capture selected request, send to API Testr, save request, create Flow, create Tool, send to AI, export HAR, open Interceptr.

**3.4.3 Initiator correlation** — `browser/InitiatorResolver.kt`, `StackTraceResolver.kt`, `BrowserRequestCorrelator.kt`. CDP Network domain exposes request initiators and JS stack. `RequestProvenance` data class: requestId, targetId, frameId, initiatorType, initiatorUrl, scriptUrl, functionName, lineNumber, columnNumber, stackTrace.

**3.4.4 Service Worker observation** — `browser/ServiceWorkerObserver.kt`, `CacheObserver.kt`, `BrowserStorageObserver.kt`. Distinguish: NETWORK / SERVICE_WORKER / CACHE. CDP Network domain includes service-worker metadata.

**3.4.5 Browser storage correlation** — `browser/CookieObserver.kt`, `LocalStorageObserver.kt`, `SessionStorageObserver.kt`, `IndexedDbObserver.kt`, `CacheStorageObserver.kt`. Correlate storage with requests. Example: LOGIN → Set-Cookie → CookieStore mutation → GET /profile → Cookie attached. UI: "Request Context — Cookies used: session_id — Storage touched: localStorage.auth — Service Worker: none".

### PHASE 3.5 — Graphs and diagnostics

**3.5.1 Provenance graph** — `correlation/graph/ProvenanceGraph.kt`, `ProvenanceNode.kt`, `ProvenanceEdge.kt`, `GraphBuilder.kt`, `GraphRepository.kt`. For browser: Button click → JS function → fetch() → Service Worker → HTTP/2 stream → TLS → Socket → Server. For Android: UI action → Kotlin function → OkHttp → TLS → Socket → VPN → Server. UI: Request > Provenance.

**3.5.2 Connection lineage** — `correlation/graph/ConnectionLineage.kt`, `ConnectionLineageBuilder.kt`. Different from provenance. Provenance answers "Why did this happen?" Lineage answers "How did this request travel?" Example: DNS #12 → IP 1.2.3.4 → TCP #31 → TLS #44 → HTTP/2 #17 → Stream #7 → REQ-421.

**3.5.3 Differential interception** — `diagnostics/DifferentialAnalyzer.kt`. Compare observation planes: VPN / MITM / Runtime / Browser / Packet. For each request, show which planes saw it: REQ-421 (VPN ✓ MITM ✓ Runtime ✓ Browser ✓, correlation 100%) vs REQ-422 (VPN ✓ MITM ✗ Runtime ✓ Browser -, visibility: Runtime only + encrypted network transport). This is a diagnostic page.

**3.5.4 Visibility diagnostics** — `diagnostics/VisibilityAnalyzer.kt`, `ObservationReport.kt`, `InterceptionHealth.kt`. Every capture session gets an Observation Report: TARGET / NETWORK (✓ VPN, ✓ TCP, ✓ DNS) / TLS (✓ handshake metadata, ✗ plaintext) / HTTP (✗ decrypted) / RUNTIME (✓ Frida connected) / BROWSER (N/A) / RESULT ("Traffic is visible at the runtime and packet layers, but HTTP plaintext is unavailable to this capture path"). No fake green indicators.

### PHASE 3.6 — Advanced protocols

**3.6.1 gRPC/Protobuf** — `interception/grpc/GrpcDetector.kt`, `GrpcFrame.kt`, `GrpcParser.kt`, `ProtobufDecoder.kt`, `ProtoSchema.kt`, `GrpcRepository.kt`. HTTP/2 → gRPC → protobuf. UI: Protocol: gRPC, Service: UserService, Method: GetUser, Request: protobuf, Response: protobuf. Unknown protobuf fields displayed structurally when possible.

**3.6.2 DoH/DoT detection** — `interception/dns/DnsDetector.kt`, `DohDetector.kt`, `DotDetector.kt`, `DnsTransport.kt`. DNS / DoT / DoH classification. UI: "DNS visibility: UDP/53: decoded, DoT: encrypted, DoH: encrypted / browser-observed". Never falsely claim DNS capture is universal.

**3.6.3 External gateway mode** — `gateway/` package. Phone → Wi-Fi → Requestr Gateway → Internet. Can be another Requestr instance, desktop bridge, or controlled gateway. Settings: Host, Port, Authentication, TLS, Capture policy. UI: Gateway CONNECTED, Devices: 3, Traffic: 12,482 requests. Useful for testing multiple devices and comparing behavior.

**3.6.4 Packet capture / PCAP** — `interception/packet/PacketCaptureEngine.kt`, `Packet.kt`, `PacketParser.kt`, `IpParser.kt`, `TcpParser.kt`, `UdpParser.kt`, `Ipv6Parser.kt`, `PcapWriter.kt`, `PacketRepository.kt`. Features: IPv4, IPv6, TCP, UDP, ICMP metadata, DNS, TLS, QUIC. UI: Connections, Packets, Protocols. **Do not dump raw packets into the Interceptr request table.** Have separate: Interceptr (HTTP), Packet Inspector, Connection Inspector.

### PHASE 3.7 — Settings architecture (from v5 plan §26)

Create sub-screens:
```
Settings
├── Capture      (Request logging, Response logging, Body capture, Max body size, Header capture, Cookie capture, Binary capture, PCAP capture)
├── VPN          (Enabled apps, Excluded apps, IPv4, IPv6, DNS, Routes, Bypass, Auto-start)
├── MITM         (Enable MITM, CA, Certificate generation, Host certificate cache, TLS diagnostics, HTTP/1.1, HTTP/2)
├── Certificates (CA management, install to system/user store, export, regenerate)
├── Runtime      (Frida, Target, Auto attach, Runtime events, Stack traces, HTTP adapters)
├── Browser      (CDP, Port, Auto-connect, Network, Fetch, Runtime, Service Worker, Storage, WebSocket, SSE)
├── Gateway      (Host, Port, Authentication, TLS, Capture policy)
├── DNS          (UDP/53, DoT, DoH classification)
├── Protocols    (HTTP/1.1, HTTP/2, HTTP/3, WebSocket, SSE, gRPC, QUIC)
├── Correlation  (Enable, Timestamp window, Strict matching, Heuristic matching, Store unmatched events)
├── Storage      (Workspace, export/import)
├── Rules        (Intercept rules management)
├── Flow         (Flow automation settings)
└── Diagnostics  (Device capabilities, Observation report, Differential analysis)
```

### PHASE 3.8 — Device capabilities page (from v5 plan §27)

`Settings > Device Capabilities` — show: Android version, Architecture, Root, Shizuku, VPN permission, Developer options, USB debugging, ADB connectivity, CA installed, System CA access, IPv6, Network state, Battery restrictions, Background execution.

**Capability matrix (dynamic, not hardcoded):**

| | No Root | Shizuku | Root |
|---|---|---|---|
| VPN | ✓ | ✓ | ✓ |
| MITM user CA | ✓ | ✓ | ✓ |
| System CA | - | conditional | ✓ |
| Runtime instrumentation | conditional | conditional | ✓ |
| Packet capture | ✓ | ✓ | ✓ |
| Native observation | - | conditional | ✓ |

---

## FINAL TARGET STATE (from v5 plan §30)

The Interceptr should show, for each request:

```
REQ-000421
SOURCE: Android App
OBSERVATION: VPN + MITM + RUNTIME
STACK: OkHttp
PROTOCOL: HTTP/2
CONNECTION: H2 #17
STREAM: 7
TLS: TLS 1.3
DNS: api.example.com → 1.2.3.4
RUNTIME ORIGIN: UserRepository.getUser()
THREAD: OkHttp Dispatcher
REQUEST: GET /api/user
RESPONSE: 200
PROVENANCE: UI → Repository → OkHttp → TLS → HTTP/2 → VPN → Internet
```

For a browser request:

```
REQ-000991
TARGET: Chrome / Tab 4
FRAME: main
INITIATOR: JavaScript
SCRIPT: app.js:1821
FUNCTION: loadMessages()
RUNTIME: fetch()
SERVICE WORKER: sw.js
CACHE: MISS
PROTOCOL: HTTP/2
REQUEST: GET /api

...

---
**This plan has almost all fixed but due to a technical error — it couldn't get done!**