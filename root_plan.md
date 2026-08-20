# REQUESTR INTERCEPTOR — COMPLETE ROOT + NON-ROOT FIX PLAN

---

## PART 0 — THE INVISIBLE GAP: WHAT IS ACTUALLY BROKEN

Before any code, you must understand the single architectural failure. Everything else flows from it.

### The gap in one sentence

**The app captures encrypted bytes and throws them away, then claims it captured traffic.**

### The chain of bugs

**Bug 1 — `CaptureEngine.ingest()` discards TLS streams (CaptureEngine.kt:106)**
```kotlin
if (stream.isTls) return // not inspecting TLS streams further (no MITM yet)
```
This is preceded by SNI extraction (lines 72-103) which persists one "undecoded" row, then `clientToServer.reset()` and `serverToClient.reset()` wipe the buffers. Every subsequent TLS byte on that stream is dropped forever. The comment literally says "no MITM yet" — the developer knew.

**Bug 2 — The VPN tier has no MITM proxy in its pipeline (RequestrVpnService.kt:140-149)**
The VPN service creates a `PacketPump` that reads the tun and feeds `CaptureEngine`. There is no `MitmProxyEngine` anywhere in this chain. The tun delivers **encrypted** bytes. `CaptureEngine` is designed for **plaintext** HTTP. It correctly refuses to fake-decrypt. So the encrypted bytes die here.

**Bug 3 — `wasDecrypted = false` is hardcoded (CaptureEngine.kt:265)**
Every persisted entity has `wasDecrypted = false`. This is a lie by omission — it means the UI can never show "decrypted" state because the field is never set true anywhere.

**Bug 4 — The tier resolver's default is non-MITM (PreflightController.kt:103-108)**
```kotlin
else -> {
    method = CaptureMethod.VPN_TUN   // portless, no MITM
    tier = ActiveTier.VPN
    port = 0
}
```
Unless you have root, Shizuku, or manually set `recordMode == "PROXY"`, you land here. This is the path 90%+ of users hit, and it cannot decrypt.

**Bug 5 — The CA is generated but never presented to any client in VPN mode (MitmProxyEngine.kt only started for MITM_PROXY)**
`CertificateAuthority` makes a real CA. `MitmProxyEngine.generateHostCert()` signs per-host leaves. But `MitmProxyEngine.start()` is only called from `PreflightController.startCapture()` when `method == MITM_PROXY` (line 149-153). In VPN mode the CA sits on disk, unused. No proxy presents a cert. The browser never trusts requestr. TLS is never terminated on-device.

**Bug 6 — `CaptureMethodWatcher` watches packets, not decryption (CaptureMethodWatcher.kt:72-78)**
```kotlin
val currentPacketCount = state.packetCount.toLong()
val receivedTraffic = currentPacketCount > lastPacketCount
val isHealthy = receivedTraffic && methodOk
```
It gates HEALTHY on raw packet count. You can capture 5000 encrypted packets and the watcher says HEALTHY. There is no `decryptedTransactionCount` anywhere in `InterceptionState`. The watcher cannot distinguish "working" from "silently failing."

**Bug 7 — Rules are persisted but never enforced on the wire (CaptureEngine.kt:203-242)**
`checkRules()` matches and logs. `persistTransaction()` reads the action but only uses it to decide whether to persist a row. `BLOCK` returns early from persistence but does NOT stop the packet from reaching the server. The 5 seeded rules in `CaptureLogRepository.seedDefaultRules()` are decorative.

**Bug 8 — The MITM proxy exists but is unreachable on the default path**
`MitmProxyEngine.kt` is genuinely good code: per-host cert generation via BouncyCastle, TLS termination via JSSE, upstream TLS, bidirectional relay. It's 255 lines of working MITM. The bug is it's never started unless you explicitly select MITM_PROXY mode. The capability is there; the wiring is not.

**Bug 9 — No HTTP/2, WebSocket, QUIC, SSE parsing of decrypted streams**
`CaptureEngine` only handles HTTP/1.x. Even if you feed it decrypted bytes, HTTP/2 (multiplexed, HPACK-compressed), WebSocket frames, and QUIC are not parsed. `MODERNIZATION_OF_INTERCEPTION.md` documents all of these as "Add."

### Summary of who blocks what

| Blocker | File:Line | Effect |
|---|---|---|
| TLS streams discarded | `CaptureEngine.kt:106` | Encrypted bytes die after SNI |
| No MITM in VPN pipeline | `RequestrVpnService.kt:140` | Never terminates TLS |
| Default tier is non-MITM | `PreflightController.kt:103` | Most users never decrypt |
| CA never presented in VPN mode | `MitmProxyEngine` only for MITM_PROXY | Browser never trusts requestr |
| Watcher gates on packet count | `CaptureMethodWatcher.kt:72` | "ACTIVE" lies about decryption |
| Rules not enforced on wire | `CaptureEngine.kt:203` | BLOCK/MOCK are no-ops |
| `wasDecrypted` hardcoded false | `CaptureEngine.kt:265` | UI can never show decrypted |

**The libraries are NOT missing.** BouncyCastle (`bouncycastle-bcpkix`, `bouncycastle-bcprov` — both in `build.gradle.kts:93-94`) and JSSE (`javax.net.ssl.SSLContext` — built into Android) are both present and correctly used in `MitmProxyEngine`. The failure is **wiring**, not dependencies.

---

## PART 1 — ROOT USER: COMPLETE FIX (THE FULL POWER)

Root removes the hardest Android limitation. You can redirect **every** app's traffic (including HTTPS CONNECT) to a local proxy and install the CA into the **system** trust store so **all** apps trust it. This is the enterprise-grade path.

### 1.1 System CA installation (the foundation)

`CertificateAuthority.kt` already generates a real self-signed CA. Add `installToSystem()`:

```kotlin
// CertificateAuthority.kt — add this function
suspend fun installToSystem(context: Context): InstallResult = withContext(Dispatchers.IO) {
    val material = getOrCreate(context)
    val pem = exportPem(context)
    val certHash = getSubjectHash(context)

    // Android 14+ uses the Conscrypt APEX module, not /system/etc/security/cacerts
    val apexDir = "/apex/com.android.conscrypt/cacerts"
    val legacyDir = "/system/etc/security/cacerts"
    val targetDir = if (File(apexDir).exists()) apexDir else legacyDir
    val targetFile = "$targetDir/${certHash}.0"

    val script = """
        CA_SRC=/data/local/tmp/requestr_ca.pem
        CA_DST=$targetFile
        [ -f "$CA_SRC" ] || cp "$CA_SRC" "$CA_SRC" 2>/dev/null
        # Write the PEM to temp first
        cat > "$CA_SRC" <<'EOF'
$pem
EOF
        # Handle Android 14+ APEX bind-mount
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
        echo "CA installed to $CA_DST"
    """.trimIndent()

    File("/data/local/tmp/requestr_ca.pem").writeText(pem)
    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
    val exit = proc.waitFor()
    val err = proc.errorStream.bufferedReader().use { it.readText() }
    if (exit == 0) InstallResult.Success(targetFile)
    else InstallResult.Failure("exit=$exit: $err")
}

sealed class InstallResult {
    data class Success(val path: String) : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}
```

**Why this matters:** Once the CA is in the system store, **every app on the device trusts it** — Chrome, banking apps, everything. This is the key enabler.

**Verification:**
```bash
su -c "ls /system/etc/security/cacerts/ | grep requestr"
# or on Android 14+:
su -c "ls /apex/com.android.conscrypt/cacerts/ | grep requestr"
```

### 1.2 Make the MITM proxy the primary observation point

The core architectural change. The tun gives you encrypted bytes; the MITM proxy gives you plaintext. Make the proxy primary.

**`PreflightController.kt` — change `run()` default (lines 103-108):**
```kotlin
else -> {
    // With CA installed, MITM proxy can decrypt HTTPS. VPN tun is the plaintext fallback.
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

**`PreflightController.kt` — change `startCapture()` MITM_PROXY branch (lines 149-153):**
```kotlin
CaptureMethod.MITM_PROXY -> {
    val mitmPort = mitmProxy.start(context, port)
    // On root devices, redirect all OUTPUT traffic to the local MITM port
    if (capabilities.isRootUsable) {
        rootEngine.start(mitmPort, android.os.Process.myUid())
    }
}```

**`PreflightController.kt` — change `startCapture()` ROOT_IPTABLES branch:**
```kotlin
CaptureMethod.ROOT_IPTABLES -> {
    val mitmPort = mitmProxy.start(context, port)
    val uid = android.os.Process.myUid()
    rootEngine.start(mitmPort, uid)   // installs iptables REDIRECT rules
}```

**`RootIptablesEngine.kt` — redirect both HTTP and HTTPS (already correct at lines 55-57, verify):**
```kotlin
add("iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-port $port")
add("iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port $port")
```
Also add UDP:443 for QUIC later (Part 3):
```kotlin
add("iptables -t nat -A OUTPUT -p udp --dport 443 -j REDIRECT --to-port $port")
```

### 1.3 Feed decrypted bytes to the log (the missing piece)

`MitmProxyEngine.handleClient()` relays bytes but never logs them. Add a `CaptureLogRepository` callback and parse the decrypted stream:

```kotlin
// MitmProxyEngine.kt — constructor change
class MitmProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onDecrypted: ((host: String, request: String, response: String, statusCode: Int) -> Unit)? = null,
)

// MitmProxyEngine.kt — in handleClient(), after upstreamSsl.startHandshake():
val t1 = Thread {
    val reqBytes = relayAndCapture(decryptedInput, upstreamOutput, host)
    // reqBytes now contains DECRYPTED request bytes
    val reqStr = String(reqBytes, Charsets.UTF_8)
    onDecrypted?.invoke(host, reqStr, "", 0)
}
val t2 = Thread { relay(upstreamInput, decryptedOutput, "←") }
```

Use the existing `HttpParser.tryParseRequest` / `tryParseResponse` on the **decrypted** stream — they work on plaintext byte arrays, no changes needed.

### 1.4 Remove the TLS discard in CaptureEngine

```kotlin
// CaptureEngine.kt line 106 — change to:
if (stream.isTls && !stream.decryptedByMitm) return
```

Add to `TcpStream.kt`:
```kotlin
/** True when this stream's bytes arrived already decrypted by the MITM proxy. */
var decryptedByMitm: Boolean = false
```

The MITM feed sets it true; the raw tun feed leaves it false. Streams from the MITM proxy pass through HTTP parsing; streams straight from the tun still get the old SNI-only treatment.

### 1.5 Wire the watcher to decryption, not packet count

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

**`CaptureMethodWatcher.kt` — gate HEALTHY on decryption:**
```kotlin
val currentDecrypted = state.decryptedTransactionCount
val receivedTraffic = currentDecrypted > lastDecryptedCount
lastDecryptedCount = currentDecrypted
val isHealthy = receivedTraffic && methodOk
```

Now if packets flow but zero transactions are decrypted, the watcher reports DEGRADED. This fixes "capture works but shows nothing."

### 1.6 Apply intercept rules on the wire

`CaptureEngine.checkRules()` matches rules but `persistTransaction()` only logs the action. Move enforcement into `MitmProxyEngine.handleClient()`:

```kotlin
when (ruleAction) {
    BLOCK -> { client.close(); return }              // don't relay
    REDIRECT -> { upstream = Socket(newHost, newPort) }
    MODIFY_REQUEST -> { rewrite request bytes before relaying }
    MODIFY_RESPONSE -> { rewrite response bytes before sending to client }
    LOG_ONLY -> { /* persist as usual */ }
}
```

This makes the 5 seeded rules in `CaptureLogRepository.seedDefaultRules()` actually functional.

### 1.7 Honest UI for capability

The `InterceptionState.methodLabel` already exists (`"${captureMethod.displayName} · port $port"`). Surface decryption status next to it. Add a decryption indicator in `InterceptScreen.kt`'s status card (around line 320):

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

### 1.8 Verification checklist (root)

- [ ] `su -c "ls /system/etc/security/cacerts/"` shows the requestr CA (or `/apex/com.android.conscrypt/cacerts/` on Android 14+)
- [ ] `su -c "iptables -t nat -L OUTPUT -n"` shows REDIRECT rules for :80, :443, :443/udp
- [ ] Chrome visit to `https://httpbin.org/json` → traffic log shows full JSON response body
- [ ] `wasDecrypted = true` on persisted entities (no longer hardcoded false)
- [ ] `decryptedTransactionCount` increments (not just `packetCount`)
- [ ] Watcher reports HEALTHY (not DEGRADED) when decryption is working
- [ ] Force-stop app → reopen → capture resumes, iptables rules intact
- [ ] Intercept rules (BLOCK) actually prevent the request from reaching the server
- [ ] The status card shows "HTTPS DECRYPTED ✓"

---

## PART 2 — NON-ROOT USER: THE TRADITIONAL PATH

Non-root users cannot redirect another app's traffic. They must use the **traditional interception model** that Requable and HttpCanary use: a local proxy that the user's own apps connect to, plus VPN-based capture for plaintext.

### 2.1 The non-root limitation (be honest)

On unrooted Android, **an app cannot decrypt another app's TLS traffic**. The options are:
1. **Your own apps** — inject OkHttp with a custom `SSLSocketFactory` that trusts your CA. Full decryption.
2. **VPN tun** — capture plaintext HTTP + TLS SNI only. HTTPS bodies are opaque.
3. **Proxy mode** — set `http.proxyHost`/`http.proxyPort` system properties. Only apps that honor them are captured.

There is **no library that magically decrypts another app's TLS without root.** What exists is BouncyCastle (cert signing) and JSSE (TLS termination) — both present in this codebase.

### 2.2 The non-root architecture

```
                    REQUESTR (non-root)
                           |
        +------------------+------------------+
        |                                     |
   OWN APPS (OkHttp)                    VPN TUN
        |                              (plaintext + SNI)
   ┌────┴────┐                              |
   │ MITM    │                     CaptureEngine
   │ Proxy   │                        │
   │ (local) │                   HTTPS → SNI only
   └────┬────┘                   HTTP  → full body
        |
   HttpParser
        |
   Room (captured_traffic)
```

### 2.3 Make the VPN tier honest about what it captures

**`RequestrVpnService.kt` — change the notification text (line 257):**
```kotlin
.setContentText("Local VPN active on port $port - HTTPS bodies not captured without proxy mode")
```

**`CaptureEngine.kt` — make the "undecoded" row honest (lines 79-95):**
The current code persists an "undecoded" row with SNI + hex dump. Keep this — it's genuinely useful information. But label it clearly as undecoded, and never count it as a captured transaction:
```kotlin
// Add a distinct marker so the UI can show "undecoded" differently from "captured"
val entity = CapturedTrafficEntity(
    // ... existing fields ...
    wasDecrypted = false,
    isUndecoded = true,   // NEW field
)
```

**`Entities.kt` — add field to `CapturedTrafficEntity`:**
```kotlin
/** True when this row is an SNI-only observation (no body decrypted). */
val isUndecoded: Boolean = false,
```

### 2.4 Non-root proxy mode for your own apps

This is the path Requable/HttpCanary use. Add an `OkHttpProxyInterceptor`:

```kotlin
// new file: interception/OkHttpProxyInterceptor.kt
package com.example.interception

import android.content.Context
import com.example.data.interception.InterceptionStateRepository
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.Proxy
import java.net.ProxyAddress
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.SecureRandom

/**
 * OkHttp interceptor that routes traffic through requestr's local MITM proxy.
 *
 * This is the non-root path: your own apps connect to the local proxy,
 * which terminates TLS with a CA-signed cert and forwards to the real server.
 * Works without root, without VPN consent, without iptables.
 *
 * Usage:
 *   val client = OkHttpClient.Builder()
 *       .addInterceptor(OkHttpProxyInterceptor(context))
 *       .build()
 */
class OkHttpProxyInterceptor(private val context: Context) : Interceptor {
    @Throws(Exception::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val port = InterceptionStateRepository.state.value.port
        if (port <= 0) return chain.proceed(chain.request())  // proxy not running

        val proxy = java.net.Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
        val client = chain.client().newBuilder()
            .proxy(proxy)
            .sslSocketFactory(sslSocketFactory(context))
            .build()
        return client.newCall(chain.request()).execute()
    }

    private fun sslSocketFactory(context: Context): SSLSocketFactory {
        // Trust requestr's CA so the MITM proxy's leaf certs are accepted
        val caMaterial = kotlinx.coroutines.runBlocking {
            CertificateAuthority.getOrCreate(context)
        }
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

**`build.gradle.kts` — add OkHttp if not present (already present at line 113):**
```kotlin
implementation(libs.okhttp)           // already there
implementation(libs.logging.interceptor)  // already there
```

### 2.5 Non-root honest status banner

In `InterceptScreen.kt` status card, show the actual capability:

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

### 2.6 Verification checklist (non-root)

- [ ] `CaptureEngine` persists plaintext HTTP request/response bodies
- [ ] HTTPS rows show SNI hostname + `isUndecoded = true`
- [ ] Status banner honestly says "HTTPS bodies not captured (plaintext only)"
- [ ] `decryptedTransactionCount` stays 0 (honest — no decryption happening)
- [ ] Watcher reports DEGRADED (honest — no decryption happening) OR the VPN tier is excluded from watcher gating
- [ ] Own apps using `OkHttpProxyInterceptor` get full HTTPS decryption
- [ ] Force-stop app → reopen → VPN resumes, traffic log persists

---

## PART 3 — ADVANCED / ENTERPRISE (months of work)

### 3.1 HTTP/2 with HPACK

`CaptureEngine` only parses HTTP/1.x. After MITM termination you get plaintext HTTP/2 frames. You need:

- **HPACK decoder** (RFC 7541) — header compression with static+dynamic tables.
- **Frame parser** — parse `HEADERS`, `DATA`, `SETTINGS`, `PRIORITY` frames; track stream IDs.

Add as a dependency:
```kotlin
// build.gradle.kts
implementation("org.apache.httpcomponents:httpclient-android")
// or a lightweight HPACK-only lib
```

Then in `MitmProxyEngine`, after TLS termination, detect `HTTP/2` via the `h2` ALPN and route to an H2 parser instead of `HttpParser`.

### 3.2 WebSocket

`WebSocketParser` exists but is only triggered from `CaptureEngine` for plaintext WS. After MITM termination, WS connections are just plaintext TCP on port 443 — feed them to the same `WebSocketParser.parseFrames()`. `MODERNIZATION_OF_INTERCEPTION.md` §20 is the spec.

### 3.3 QUIC / HTTP/3

QUIC runs over UDP, so iptables REDIRECT on TCP won't catch it. Options:

- **Redirect UDP:443 too** — `iptables -t nat -A OUTPUT -p udp --dport 443 -j REDIRECT --to-port 8080`. Then your MITM proxy must speak QUIC (needs a QUIC stack — `quiche` or `quic-go` via JNI, or the new Android QUIC API).
- **SNI-only fallback** — `QuicParser.extractSni()` already does this. For full decryption you need TLS 1.3 key export, which requires the client to cooperate (QUIC doesn't allow passive decryption the way TCP TLS does).

Realistic enterprise approach: **QUIC SNI extraction + HTTP/3 plaintext via a QUIC-aware proxy**. `MODERNIZATION_OF_INTERCEPTION.md` §19 has the diagnostics spec.

### 3.4 Frida runtime instrumentation

`FridaCaptureEngine` exists but is never started. With root you can:

1. Push `frida-server` to `/data/local/tmp/` and start it.
2. Inject the `httpLogHook` script into target apps.
3. Correlate Frida events (which `OkHttp` method fired the request) with the network capture.

`MODERNIZATION_OF_INTERCEPTION.md` §5-6 has the correlation spec. The Frida output gives you **runtime context** — "this request came from `RealCall.execute()` in `com.example.app.NetworkRepo`" — which raw packet capture can never provide.

### 3.5 Correlation engine

`MODERNIZATION_OF_INTERCEPTION.md` §42 is the spec. Build a `CorrelationEngine` that joins:

```
DNS event → TCP connection → TLS session → HTTP/2 stream → HTTP request → Frida runtime event
```

Each gets a `REQ-NNNN` ID. The UI shows the full chain: "Button click → fetch() → Service Worker → TLS #18 → HTTP/2 stream #7 → REQ-421 → Response → DOM update."

### 3.6 Browser extension + CDP

`MODERNIZATION_OF_INTERCEPTION.md` §10-16. For Chrome:
- Build a Chrome extension that communicates via native messaging.
- Use CDP `Network.requestWillBeSent` / `responseReceived` events.
- This gives you **browser-level** interception (modify, block, mock, delay) in addition to network-level.

### 3.7 Rules engine with real enforcement

`MODERNIZATION_OF_INTERCEPTION.md` §29. Actions: Log, Tag, Block, Delay, Redirect, Mock, Modify Request, Modify Response, Save Capture, Trigger Flow. All enforceable now that the MITM proxy controls the wire.

### 3.8 Chaos testing

`MODERNIZATION_OF_INTERCEPTION.md` §31. Inject faults through the MITM proxy: `5% → 500`, `10% → +500ms`, `2% → timeout`. Trivial once you control the relay — modify bytes in `MitmProxyEngine.relay()` based on rules.

---

## PART 4 — THE TARGET ARCHITECTURE

```
                         REQUESTR
                              |
                      OBSERVATION CORE
                              |
       +----------------------+----------------------+
       |                      |                      |
    NETWORK                RUNTIME                BROWSER
       |                      |                      |
  ┌────┴────┐          ┌─────┴─────┐          ┌────┴────┐
  │ VPN TUN │          │   Frida   │          │   CDP   │
  │ MITM    │          │   Java    │          │  Ext    │
  │ Proxy   │          │   JNI     │          │  SW     │
  │ QUIC    │          │  OkHttp   │          │         │
  └────┬────┘          └───────────┘          └─────────┘
       |                      |                      |
       +----------------------+----------------------+
                              |
                     DECRYPTED PLAINTEXT
                              |
       +----------------------+----------------------+
       |                      |                      |
    HTTP/1.1                HTTP/2                WebSocket
    HTTP/3                  SSE                   WebTransport
       |                      |                      |
       +----------------------+----------------------+
                              |
                      CORRELATION ENGINE
                              |
                     INTERCEPTR (the log)
                              |
       +----------------------+----------------------+
       |                      |                      |
    API Testr               Tools                  AI Flow
```

---

## PART 5 — THE 3-LINE ANSWER

1. **The libraries exist.** BouncyCastle + JSSE are in `MitmProxyEngine` and working. Nothing is missing.

2. **The pipeline is broken.** The tun feeds encrypted bytes to `CaptureEngine`, which correctly discards them. Root fixes this by: installing the CA to the system store → starting the MITM proxy → iptables REDIRECT :443 to the proxy → the proxy terminates TLS → `HttpParser` reads plaintext → Room persists it. Non-root fixes this by: honest VPN tier (plaintext + SNI only) + `OkHttpProxyInterceptor` for your own apps.

3. **Start with Part 1 Step 1.1** (system CA install) + **Step 1.3** (feed decrypted bytes to the log). That alone makes HTTPS bodies appear for root users. Then Part 2 for non-root honesty, Part 3 for protocol coverage.

The hardest part isn't code — it's the Android routing limitation on unrooted devices, which you handle by being honest in the UI rather than by faking it — but for that, the requestr must have the capabilities to deliver the promise of "can do" for rooted devices.