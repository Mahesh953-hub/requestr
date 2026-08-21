### Summary

Four subsystems in the runtime/browser/diagnostics scope are documented as done but are shells: the Cronet and WebView instrumentation adapters only do `Class.forName` presence checks, HTTP stack detection is a User-Agent `when` block that collapses to `UNKNOWN`, connection lineage joins HTTP rows by a 30-second host window because there is no `connectionId` column, and the CDP browser bridge intercepts only the request stage of one tab with no bodies and no reconnect. This plan replaces all four with real capture paths: nine ordered, independently-implementable work items with exact files, grounding evidence, new unit tests, and gradle verification.

Baseline to preserve: `./gradlew :app:assembleDebug --no-configuration-cache` is currently BUILD SUCCESSFUL on branch `backup` at `0db606c`. Every work item below names its own verification commands; all gradle invocations must pass `--no-configuration-cache` because `gradle.properties` sets `org.gradle.configuration-cache=true`.

---

### Confirmed decisions (no open blockers)

All four questions that previously gated W1/W2/W3/W6 are resolved. They are recorded here with the evidence behind them so the reasoning stays legible during implementation.

**DEC-1 — Cronet artifact: `org.chromium.net:cronet-embedded:143.7445.0`.** Confirmed.

| Option | Size | Proxy support | Runtime requirement |
|---|---|---|---|
| **`org.chromium.net:cronet-embedded:143.7445.0`** (chosen) | AAR is 13,712,811 bytes; `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libcronet.143.0.7445.0.so` at 4.1–7.2 MB each | `CronetEngine.Builder.setProxyOptions(ProxyOptions)` plus `org.chromium.net.ProxyOptions` and `org.chromium.net.Proxy` all present (verified by `javap` on the AAR's `classes.jar`) | none — self-contained |
| `com.google.android.gms:play-services-cronet:18.1.1` | AAR is 92,405 bytes | pulls `cronet-api:141.7340.3`, where `setProxyOptions` exists on `Builder` but the `ProxyOptions`/`Proxy` classes are **absent** from that AAR — so proxy routing is not usable | Google Play services; `CronetProviderInstaller.installProvider(Context)` must succeed |
| `org.chromium.net:cronet-api:143.7445.0` only | 84,776 bytes | API present, no implementation | some provider must be installed at runtime, else `CronetEngine.Builder.build()` fails |

Rationale: proxy routing *is* W2, and `cronet-embedded` is the only artifact where the three classes it needs actually exist. The app targets rooted/Redroid devices with no GMS-availability guarantee, so a Play-services-gated provider would leave the adapter dead on exactly the devices the tool is used on. Version gating matters and is not interchangeable: `setProxyOptions`/`ProxyOptions`/`Proxy` are all present in `143.7445.0`; `141.7340.3` has the `Builder` method but not the classes; `119.6045.31` has no `setProxyOptions` at all. Do not downgrade the version to save size — it silently removes the capability.

*Size implication, stated explicitly.* `app/build/outputs/apk/debug/app-debug.apk` is 28,044,378 bytes today. Adding `cronet-embedded` puts all four ABIs' `libcronet` into the artifact, so expect roughly +13.7 MB on the **debug** APK, which is the variant used for on-device verification and is accepted.

*Release-variant implication — there is one, and it is worth acting on.* The four `libcronet.so` ABI slices are the entire size cost; the `classes.jar` is only 651,111 bytes. Two consequences for a release build:
- A universal release APK carries all four slices. An ABI split (`splits { abi { isEnable = true } }`) or a per-variant `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }` reduces the delivered size to one slice, and an Android App Bundle does this automatically since the Play delivery model ships only the device's ABI. Dropping `x86`/`x86_64` would, however, break Cronet on emulators and on x86 Redroid, so any filtering must keep those ABIs in the debug variant.
- `cronet-embedded` ships an 11,121-byte `proguard.txt` consumed automatically as a consumer ProGuard file, so R8 keep-rules for the JNI entry points come along for free. No manual keep rules are expected, but a release build with minification enabled should be smoke-tested against a real Cronet request before anyone claims the release variant works.

Configuring ABI splits, bundle packaging, or minified-release verification is **out of scope for this plan** — flagged here so the release cost is a known, sized decision rather than a surprise. This plan changes only what `assembleDebug` produces.

**DEC-2 — WebView host surface: option (b), an offscreen `WebView` driven from a URL field in the Settings card.** Confirmed for this session.

There is no WebView anywhere in the app today: a repo-wide grep finds only `WebViewAdapter`, the unused `TrafficSource.WEBVIEW` enum entry (`app/src/main/java/com/example/data/models/AppModels.kt:202`), and the `pkg.contains("android.webkit")` heuristic in `TrafficClassifier.kt:21-22`. Real WebView instrumentation therefore requires requestr to own a WebView instance. Option (b) delivers real `shouldInterceptRequest` capture, real proxy routing, and real cookie visibility with zero new navigation surface.

*Deliberate follow-up, not a gap.* Option (a) — a full in-app inspector browser screen as a twelfth `Screen` route beside the eleven in `MainActivity.kt:261-279` — is intentionally deferred, not overlooked. The reason is sequencing, not feasibility: a new full-screen destination needs its own design cycle (navigation entry, address bar, tab affordances, per-request drill-in), and gating the *capture* work on that cycle would leave the Cronet/WebView adapters presence-only shells for another release. Option (b) proves the capture path end to end first; option (a) then becomes a pure presentation layer over an already-working `WebViewInstrumentation`, with no rework of the capture code. Option (c), a bottom-sheet inspector inside `InterceptScreen.kt`, was not chosen because it mixes a browsing surface into a traffic-list screen.

**DEC-3 — Add `CaptureTier.RUNTIME`.** Confirmed. `enum class CaptureTier { NONE, VPN, SHIZUKU, ROOT, SDK, GATEWAY }` at `app/src/main/java/com/example/data/local/Entities.kt:61` is persisted through `RequestrTypeConverters.captureTierToName` as a `String`, so adding a constant costs no migration. In-process instrumentation and third-party SDK reports are genuinely different planes with different trust and different blind spots; folding them into one tier is the kind of blurring that made the existing trackers untrustworthy. Distinct plane, distinct tier. Every row written by `RuntimeTrafficSink` (W1) uses `CaptureTier.RUNTIME`, and the Settings/Intercept filters gain it as a selectable value.

**DEC-4 — Lineage uses exact `connectionId` joins only; the 30-second window is removed from the lineage path.** Confirmed, with two binding conditions recorded in W6. A time window that silently attaches the wrong HTTP row fabricates a causal chain the data does not support, which is worse than reporting an unattached row. See W6 for the full treatment; the transport scope has been told the fallback is deleted at the lineage call site and must not be reintroduced there.

**Design artifacts.** A design subagent has been dispatched for the WebView inspector surface and the expanded Runtime Instrumentation / Browser Bridge Settings cards. W2, W3, and W9 are written assuming those mockups are attached in the Design tab and **treat them as the visual spec** — match the attached mockups for layout, labels, control placement, and status affordances rather than inventing UI. Where a mockup and a line in this plan disagree on presentation, the mockup wins; where they disagree on behaviour or data, this plan wins.
---

### Tasks

**1. [parallel] Extend the runtime event contract so adapters can carry bodies, sizes, timings, and connection identity**

*Files:* `app/src/main/java/com/example/runtime/RuntimeInstrumentationEngine.kt` (modify).

*Why:* `InstrumentationEvent` today is `(adapter, eventType, method, url, headers, statusCode, errorMessage, stackFrames, timestamp)` — there is no body, no size, no latency, and no connection id. W2 and W3 both capture real bodies (`UrlRequest.Callback.onReadCompleted(UrlRequest, UrlResponseInfo, ByteBuffer)` and `WebResourceResponse` respectively), so without this change they would have nowhere to put them. Likewise `drainToCorrelationEngine()` currently hardcodes `connectionId = null`, which W6 needs to stop doing.

*Change:*
- Add to `InstrumentationEvent`: `requestBody: String? = null`, `responseBody: String? = null`, `requestSizeBytes: Long = 0`, `responseSizeBytes: Long = 0`, `latencyMs: Long = 0`, `protocol: String? = null`, `connectionId: String? = null`, `host: String? = null`. All defaulted, so `OkHttpAdapter`'s existing construction at `HttpStackAdapters.kt:38-46` keeps compiling unchanged.
- In `drainToCorrelationEngine()`, pass `connectionId = e.connectionId` instead of the literal `null` (line 105).
- Add `private val sink = mutableListOf<RuntimeCaptureSink>()` plus `fun registerSink(sink: RuntimeCaptureSink)`, and call every sink from `drainToCorrelationEngine()` alongside the existing `CorrelationEngine.ingest`. Define `interface RuntimeCaptureSink { fun onEvents(events: List<InstrumentationEvent>) }` in the same file next to `InstrumentationAdapter`.
- Add `val eventCounts: StateFlow<Map<String, Int>>` (adapter name → cumulative events drained) backed by a `MutableStateFlow`, incremented in `drainToCorrelationEngine()`. Reason: the Settings card currently only shows `adapterNames()` and a state enum; per-adapter counts are what make "this adapter is really capturing" visible. Follow the existing StateFlow rationale comment at lines 39-45.
- Change `start(context)`'s state assignment (lines 68-69) so `FAILED` is only set when *no* adapter started, and add `val adapterStatuses: StateFlow<Map<String, String>>` carrying each adapter's own one-line status string; add `fun status(): String` to `InstrumentationAdapter` with a default implementation returning `if (isActive()) "active" else "inactive"`. This is what lets W2/W3 report "engine built, listener attached" vs "no Cronet provider installed" instead of a single global FAILED.

*New file:* `app/src/main/java/com/example/runtime/RuntimeTrafficSink.kt` — a `RuntimeCaptureSink` implementation that maps each event with a non-null `url` into a `CapturedTrafficEntity` and inserts it via `RequestrDatabase.get(context).capturedTrafficDao().insert(...)` on `Dispatchers.IO`. Mirror the field mapping already used by `LocalApiServer.receiveObserverEvents()` at `app/src/main/java/com/example/api/LocalApiServer.kt:304-318` (host/path derived with `java.net.URI`, `wasDecrypted = true`), and gate request/response bodies on `SettingsRepository.settings.value.requestLoggingEnabled` / `responseLoggingEnabled` exactly as `MitmProxyEngine.emitTransaction` does at `MitmProxyEngine.kt:437-450`. Session id: reuse `LocalApiServer`'s existing `sdkSessionId()` approach — a single long-lived session row — with `CaptureTier.RUNTIME` per decision DEC-3. Add the `RUNTIME` constant to `enum class CaptureTier` in `app/src/main/java/com/example/data/local/Entities.kt:61`; no migration is needed because `RequestrTypeConverters.captureTierToName` persists it as a `String`. Also add `RUNTIME` to whatever tier filters the Settings/Intercept surfaces expose, so runtime-captured rows are selectable rather than silently lumped in with `SDK`.

*Wiring:* in `app/src/main/java/com/example/MainActivity.kt`, immediately before the existing `RuntimeInstrumentationEngine.start(applicationContext)` call at line ~195, register the sink.

*Tests:* new `app/src/test/java/com/example/runtime/RuntimeInstrumentationEngineTest.kt`
- a fake `InstrumentationAdapter` returning two events; assert `pollEvents()` returns both and `eventCounts` reflects 2 per adapter after a drain.
- assert one active + one inactive adapter yields `ACTIVE`, and all-inactive yields `FAILED`.
- assert `InstrumentationEvent` defaults are backwards compatible by constructing one with only the pre-existing arguments.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`.

**2. [after 1] Real Cronet instrumentation: requestr-owned engine, RequestFinishedInfo listener, body capture, proxy routing**

*Files:* `app/build.gradle.kts` + `gradle/libs.versions.toml` (add dependency), `app/src/main/java/com/example/runtime/http/HttpStackAdapters.kt` (rewrite `CronetAdapter`), new `app/src/main/java/com/example/runtime/http/CronetInstrumentation.kt`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (card copy + new controls).

*Grounding:* `CronetAdapter.start()` is literally
```kotlin
active = try { Class.forName("org.chromium.net.CronetEngine"); true } catch (_: ClassNotFoundException) { false }
```
and `override fun pollEvents(): List<InstrumentationEvent> = emptyList()` (`HttpStackAdapters.kt:56-68`). `TODO_v5.md:52` admits "Cronet/WebView adapters remain presence-detection only — no native hook exists". No `org.chromium.net` entry exists in `app/build.gradle.kts` or `gradle/libs.versions.toml`. `settings.gradle.kts` declares `google()` unrestricted in `dependencyResolutionManagement`, so `org.chromium.net:*` resolves.

*API surface verified by `javap` on `cronet-api-143.7445.0.aar`'s `classes.jar`:*
- `CronetEngine.Builder(Context)`, `.enableQuic(boolean)`, `.enableHttp2(boolean)`, `.enableBrotli(boolean)`, `.enableHttpCache(int, long)`, `.setUserAgent(String)`, `.setStoragePath(String)`, `.setProxyOptions(org.chromium.net.ProxyOptions)`, `.build()`, `.buildExperimental()`
- `CronetEngine.addRequestFinishedListener(RequestFinishedInfo.Listener)` / `removeRequestFinishedListener(...)`, `newUrlRequestBuilder(String, UrlRequest.Callback, Executor)`, `getVersionString()`, `startNetLogToFile(String, boolean)`, `stopNetLog()`, `shutdown()`, `getActiveRequestCount()`
- `RequestFinishedInfo`: `getUrl()`, `getAnnotations(): Collection<Object>`, `getMetrics(): Metrics`, `getFinishedReason(): int` (`SUCCEEDED`/`FAILED`/`CANCELED`), `getResponseInfo(): UrlResponseInfo`, `getException(): CronetException`
- `RequestFinishedInfo.Metrics`: `getRequestStart/DnsStart/DnsEnd/ConnectStart/ConnectEnd/SslStart/SslEnd/SendingStart/SendingEnd/ResponseStart/RequestEnd(): Date`, `getSocketReused(): boolean`, `getTtfbMs()`, `getTotalTimeMs()`, `getSentByteCount()`, `getReceivedByteCount()`
- `UrlResponseInfo`: `getHttpStatusCode()`, `getHttpStatusText()`, `getAllHeadersAsList(): List<Map.Entry<String,String>>`, `getAllHeaders(): Map<String,List<String>>`, `getNegotiatedProtocol()`, `getProxyServer()`, `getReceivedByteCount()`, `wasCached()`, `getUrlChain(): List<String>`
- `UrlRequest.Builder`: `setHttpMethod`, `addHeader`, `setUploadDataProvider`, `addRequestAnnotation(Object)`, `setRequestFinishedListener(RequestFinishedInfo.Listener)`, `build()`
- `UrlRequest.Callback`: `onRedirectReceived`, `onResponseStarted`, `onReadCompleted(UrlRequest, UrlResponseInfo, ByteBuffer)`, `onSucceeded`, `onFailed`, `onCanceled`
- `org.chromium.net.ProxyOptions(List<org.chromium.net.Proxy>)` + `getProxyList()`; there is **no** `ProxyOptions.Builder`
- `org.chromium.net.Proxy(int scheme, String host, int port, Proxy.Callback)` with `static final int HTTP` / `HTTPS`

*Change — `CronetInstrumentation.kt`:*
- `object CronetInstrumentation` holding the requestr-owned engine. `fun engine(context: Context): CronetEngine?` builds once and caches: `CronetEngine.Builder(context).enableQuic(true).enableHttp2(true).enableBrotli(true).setStoragePath(<cacheDir subdir>).enableHttpCache(HTTP_CACHE_DISK, N)`, then — when `InterceptionStateRepository.state.value.port > 0` — `.setProxyOptions(ProxyOptions(listOf(Proxy(Proxy.HTTP, "127.0.0.1", port, callback))))` so Cronet traffic goes through the same MITM proxy `OkHttpProxyInterceptor` already targets (`OkHttpProxyInterceptor.kt` reads the port the same way). Wrap construction in `runCatching` and record the failure message as the adapter status — a missing provider is a real runtime outcome, reported, not swallowed.
- A `RequestFinishedInfo.Listener` subclass over a dedicated single-thread `Executor` that, on `onRequestFinished(info)`, builds one `InstrumentationEvent(adapter = "Cronet", eventType = "response_ready", ...)` filling `method` (from the annotation set in the request builder), `url = info.url`, `statusCode = info.responseInfo?.httpStatusCode`, `headers` flattened from `getAllHeadersAsList()`, `protocol = info.responseInfo?.negotiatedProtocol`, `requestSizeBytes = metrics.sentByteCount`, `responseSizeBytes = metrics.receivedByteCount`, `latencyMs = metrics.totalTimeMs`, `errorMessage = info.exception?.message`, and `connectionId` from the canonical key builder added in W5. Also emit an `eventType = "exception"` event when `getFinishedReason() == FAILED`.
- A bounded, thread-safe queue exactly mirroring the proven OkHttp pattern: `companion`-style `private const val MAX_QUEUED_EVENTS = 500`, `private val pendingEvents = ConcurrentLinkedQueue<InstrumentationEvent>()`, `fun drainEvents(): List<InstrumentationEvent>`, and a `while (pendingEvents.size > MAX_QUEUED_EVENTS) pendingEvents.poll()` bound — the same shape as `OkHttpProxyInterceptor.kt`'s companion object.
- `fun newRequest(url: String, method: String, headers: Map<String,String>, body: ByteArray?): CronetCall` — a real request-execution helper that wires `UrlRequest.Builder.setRequestFinishedListener(listener)` plus a `UrlRequest.Callback` whose `onReadCompleted` accumulates the `ByteBuffer` into a `ByteArrayOutputStream` so `responseBody` is genuinely captured (the finished-listener alone never sees the body). This is what makes the adapter capture bodies rather than metadata only.

*Change — `CronetAdapter`:* `start(context)` calls `CronetInstrumentation.engine(context)`, sets `active = engine != null`, attaches the listener, and sets `status()` to `"engine ${engine.versionString} · listener attached · proxy=127.0.0.1:$port"` or the captured failure reason. `pollEvents()` returns `CronetInstrumentation.drainEvents()`. `stop()` calls `removeRequestFinishedListener` and leaves the engine cached.

*Change — API Testr integration (this is what generates real Cronet traffic):* `app/src/main/java/com/example/data/repository/ApiTestrRepository.kt` already adds `OkHttpProxyInterceptor` to its two client builders at lines 48 and 60. Add an engine selector so a request can be executed through `CronetInstrumentation` instead of OkHttp, defaulting to OkHttp. Without a real caller the adapter would be another no-op; with it, sending an API Testr request on the Cronet engine produces genuine events, rows, and correlation entries.

*Change — Settings:* in the `"RUNTIME INSTRUMENTATION"` card (header at `SettingsScreen.kt:723`), replace the dishonest line 736 string `"No adapter reported active (Cronet/WebView only detect class presence)."` and the line 749 OkHttp-scope caveat with the per-adapter `adapterStatuses` + `eventCounts` rows from W1, and add a Cronet engine row showing `getVersionString()`, whether proxying is on, and the live drained-event count. Follow the dispatched design mockups for this card's layout and status affordances — they are the visual spec for the expanded Runtime Instrumentation card. The behavioural requirement is fixed regardless of presentation: no string in this card may claim capture that is not happening, which is precisely the defect line 736 encodes today.

*Tests:* new `app/src/test/java/com/example/runtime/http/CronetEventMappingTest.kt` — test the pure mapping function (`RequestFinishedInfo`-shaped inputs → `InstrumentationEvent`) by extracting it as an internal function taking plain values (url, status, header list, sent/received bytes, total ms, negotiated protocol, exception message) so it is testable without a device or a real Cronet provider. Cover: 200 with headers and both byte counts; a failed request producing `eventType = "exception"` with the message preserved; header list with duplicate names collapsing deterministically. Plus a queue test asserting `drainEvents()` empties the queue and that queueing 600 events keeps at most 500.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`, `./gradlew :app:assembleDebug --no-configuration-cache`, then confirm the APK still builds and note the new size delta versus the recorded 28,044,378-byte baseline.

**3. [after 1] Real WebView instrumentation: requestr-hosted WebView, shouldInterceptRequest, MAIN-world fetch/XHR bridge, proxy override, cookies**

*Files:* `gradle/libs.versions.toml` + `app/build.gradle.kts` (add `androidx.webkit`), `app/src/main/java/com/example/runtime/http/HttpStackAdapters.kt` (rewrite `WebViewAdapter`), new `app/src/main/java/com/example/runtime/webview/WebViewInstrumentation.kt`, new `app/src/main/java/com/example/runtime/webview/webview_hook.js` under `app/src/main/assets/`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`.

*Grounding:* `WebViewAdapter.start()` is `Class.forName("android.webkit.WebView")` with `pollEvents()` returning `emptyList()` (`HttpStackAdapters.kt:70-84`). No WebView exists in the app (see decision DEC-2). `androidx.webkit` is not a dependency.

*API surface verified by `javap` on `androidx.webkit:webkit:1.14.0` (latest published is 1.17.0, `lastUpdated 20260812171216`; its POM deps are `kotlin-stdlib:2.1.20`, `jspecify:1.0.0`, `androidx.core:core:1.1.0`, `androidx.annotation:annotation:1.8.1`):*
- `ProxyController.getInstance().setProxyOverride(ProxyConfig, Executor, Runnable)` / `clearProxyOverride(Executor, Runnable)`
- `ProxyConfig.Builder().addProxyRule(String)`, `addProxyRule(String, String)`, `addBypassRule(String)`, `addDirect()`, `bypassSimpleHostnames()`, `removeImplicitRules()`, `setReverseBypassEnabled(boolean)`, `build()`
- `WebViewCompat.addDocumentStartJavaScript(WebView, String, Set<String>): ScriptHandler`, `addWebMessageListener(WebView, String, Set<String>, WebMessageListener)`, `removeWebMessageListener(WebView, String)`, `getCurrentWebViewPackage(Context)`
- `WebViewFeature` gates: `PROXY_OVERRIDE`, `PROXY_OVERRIDE_REVERSE_BYPASS`, `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`, `SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`, `GET_COOKIE_INFO`
- `CookieManagerCompat`, `ServiceWorkerControllerCompat`, `JavaScriptReplyProxy`

*Change — three genuinely different capture layers, all real:*
1. **Network layer (`WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`).** Executes the request ourselves through the existing OkHttp client (which already carries `OkHttpProxyInterceptor`, per `ApiTestrRepository.kt:48`), records method/url/request headers/status/response headers/body bytes/latency into a bounded `ConcurrentLinkedQueue<InstrumentationEvent>`, and returns a `WebResourceResponse` built from the fetched bytes so the page still renders. This yields real bodies for every subresource, including ones the JS hook cannot see.
2. **In-page layer (MAIN-world script).** `WebViewCompat.addDocumentStartJavaScript(webView, <hook js>, setOf("*"))` gated on `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)`, paired with `WebViewCompat.addWebMessageListener(webView, "requestrBridge", setOf("*"), listener)` gated on `WEB_MESSAGE_LISTENER`. The asset script patches `window.fetch` and `XMLHttpRequest.prototype.open/send` to post `{url, method, headers, status, requestBody, responseBody, durationMs, initiator}` back over the bridge. This captures request bodies and same-document XHR that `shouldInterceptRequest` cannot reconstruct. Note the JSON payload shape here should match the `browser-extension/` `/api/observe` schema (`{"url","method","headers","statusCode","bodySize","durationMs","timestamp"}`, consumed at `LocalApiServer.kt:287-318`) so the two planes stay diff-able — but this hook posts to the in-process bridge, not over HTTP.
3. **Transport routing + trust.** When `InterceptionStateRepository.state.value.port > 0` and `WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)`, call `setProxyOverride(ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").removeImplicitRules().build(), executor, callback)` so WebView TLS traffic is decrypted by `MitmProxyEngine` and lands in Room through the existing path. Clear the override in `stop()`. Note in the plan that `app/src/main/res/xml/network_security_config.xml` already permits cleartext for `localhost`/`127.0.0.1` only, and that WebView trusting requestr's CA depends on the existing `CertificateAuthority`/`CaWhitelistService` install path — no new trust mechanism is invented.
4. **Cookies.** `CookieManager.getInstance().getCookie(url)`, plus `CookieManagerCompat` when `GET_COOKIE_INFO` is supported, exposed as a `StateFlow<List<String>>` for the Settings card.

*Change — `WebViewAdapter`:* `start(context)` must run on the main thread (WebView construction requirement) — post to `Handler(Looper.getMainLooper())` and report readiness through `status()`. `active` becomes true only once a WebView instance exists and the client is installed. `pollEvents()` drains the shared queue. `status()` reports which `WebViewFeature`s resolved true and which WebView package/version `getCurrentWebViewPackage` returned, so an unsupported device produces a real diagnostic rather than a silent false.

*Change — host surface:* per confirmed decision DEC-2, option (b): the Settings card gets a URL text field and a Load button that drives the adapter's WebView, created offscreen, attached to no layout, `loadUrl` only. No new navigation destination is added in this session. Follow the dispatched design mockups for the card's field placement, button labelling, and how per-request capture status is surfaced; the mockups are the visual spec. The full in-app inspector screen (option (a)) is a deliberate follow-up recorded in DEC-2, not a gap — the capture code written here is designed so that screen becomes a presentation layer over `WebViewInstrumentation` with no rework, so keep the adapter's public surface (start/stop, `loadUrl`, event `StateFlow`s, cookie `StateFlow`) free of Settings-specific assumptions.

*Tests:* new `app/src/test/java/com/example/runtime/webview/WebViewCaptureMappingTest.kt` — unit-test the two pure mappers: (i) bridge JSON string → `InstrumentationEvent`, covering a full payload, a payload missing `status`, and malformed JSON returning null without throwing; (ii) the intercept-layer mapper from (method, url, request headers, status, response headers, body size, latency) → `InstrumentationEvent`. Also test the `ProxyConfig`-rule string builder (port 0 → no override requested; port 8080 → rule `"127.0.0.1:8080"`) as a pure function so it needs no WebView.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`, `./gradlew :app:assembleDebug --no-configuration-cache`.

**4. [parallel] Replace single-signal stack detection with multi-signal confidence scoring plus on-device calibration**

*Files:* `app/src/main/java/com/example/runtime/http/HttpStackDetector.kt` (rewrite the report path, keep the enum), new `app/src/main/java/com/example/runtime/http/StackFingerprinter.kt`, new `app/src/main/java/com/example/runtime/http/StackSignalStore.kt`, `app/src/main/java/com/example/interception/CaptureEngine.kt` (feed signals), `app/src/main/java/com/example/ui/screens/SettingsScreen.kt:758-796` (render the new verdict).

*Grounding:* the whole of `detectFromHeaders` is a `when` over the `User-Agent` value; `if (ua.isEmpty()) return HttpStack.UNKNOWN`; and the KDoc at `HttpStackDetector.kt:26-34` states Volley "ships NO distinct default User-Agent of its own ... indistinguishable from raw HttpURLConnection traffic by header alone". `detectAvailableOnClasspath()` only answers a question about requestr's own process.

*Confidence model to follow:* `app/src/main/java/com/example/interception/ProtocolDetector.kt:15-17` — `enum class Confidence { DETECTED, LIKELY, POSSIBLE, UNKNOWN }` and `data class Detection(val protocol: Protocol, val confidence: Confidence, val reason: String)`. Mirror this exactly: `data class StackVerdict(val stack: HttpStack, val confidence: Confidence, val reason: String, val signals: List<StackSignal>)`.

*Signals, each grounded in something requestr genuinely has:*

| Signal | Source in this repo | Discriminating value (verified) |
|---|---|---|
| JA3 fingerprint | `TlsClientHello.ja3Hash()` at `app/src/main/java/com/example/interception/tls/TlsSession.kt:70-76`, persisted as `TlsSessionEntity.ja3Hash` by `CaptureEngine.kt:185` | one hash per (stack, device TLS provider) pair; matched against the calibration table below |
| ALPN offer | `TlsClientHello.alpn` (undecoded path) / `TlsSessionEntity.negotiatedProtocol` | Cronet offers h3/h2; OkHttp offers h2+http/1.1; `HttpURLConnection`/Volley on Android offer http/1.1 (no h2) |
| HTTP/2 SETTINGS values | `Http2Parser.parseFrames` + `FRAME_SETTINGS = 0x4` (`Http2Parser.kt:26`) already parsed in `CaptureEngine.processH2Direction` (`CaptureEngine.kt:336-385`) | OkHttp 4.x sends exactly `INITIAL_WINDOW_SIZE(7) = 16777216` and nothing else — verified by disassembling `Http2Connection.<init>` in the cached `okhttp-4.10.0.jar` (`bipush 7; ldc int 16777216; Settings.set`), with ids confirmed by reflection: `HEADER_TABLE_SIZE=1, ENABLE_PUSH=2, MAX_CONCURRENT_STREAMS=4, MAX_FRAME_SIZE=5, MAX_HEADER_LIST_SIZE=6, INITIAL_WINDOW_SIZE=7`. Cronet's SETTINGS set differs in both membership and values, so the frame's `(id, value)` multiset is a strong fingerprint. |
| Pseudo-header order | `Http2Parser.H2Headers.pseudoHeaders` (`Http2Parser.kt:74-80`) | stacks emit `:method/:path/:authority/:scheme` in library-specific order |
| Request header order + casing | HTTP/1.x path only. **Blocker to design around:** `HttpParser` lowercases every header name (`app/src/main/java/com/example/interception/http/HttpParser.kt:56,122` — `.trim().lowercase()`), and `CapturedTrafficEntity.requestHeaders` is a `Map<String,String>` whose Moshi TypeConverter deserialises into a `LinkedHashTreeMap` that **sorts keys** (verified empirically: `{"zeta","alpha","mid"}` reads back as `[zeta, alpha, mid]` insertion-ordered on write but the class is a tree map, so ordering through Room is not trustworthy). Therefore order/casing must be captured **at parse time** into the new `StackSignalStore`, never recovered from Room. | order is highly stack-specific; must be sampled live |
| Cache-validation shape | `HurlStack`/`HttpHeaderParser` in `volley-1.2.1-sources.jar`: `getCacheHeaders` emits `If-None-Match` + `If-Modified-Since` from Volley's own disk cache, `HurlStack.executeRequest` calls `connection.setUseCaches(false)` (line 220), and `Request.getBodyContentType()` defaults to `application/x-www-form-urlencoded; charset=UTF-8` while `JsonRequest` uses `application/json; charset=utf-8` | **this is the real Volley-vs-HttpURLConnection discriminator the current code gives up on**: Volley disables the platform cache and supplies its own validators, so a `Dalvik/...` UA request carrying `If-None-Match`/`If-Modified-Since` with no platform `Cache-Control` handshake, and/or Volley's exact default content-type strings, is Volley; raw `HttpURLConnection` with `useCaches` left on behaves differently |
| Accept-Encoding / Connection defaults | `okhttp3.internal.http.BridgeInterceptor` disassembly shows it sets `Accept-Encoding: gzip`, `Connection: Keep-Alive`, `Host`, `User-Agent` in a fixed order | separates OkHttp from platform stacks |
| Connection reuse | `TcpConnectionEntity.bytesSent/bytesReceived` and Cronet's `RequestFinishedInfo.Metrics.getSocketReused()` | pooling behaviour differs per stack |
| Runtime stack frames | `RuntimeEvent.stackFrames` (`ObservationEvent.kt:76`) fed by W1-W3 adapters | when present this is `DETECTED`, not a heuristic — an event that came out of `CronetInstrumentation` *is* Cronet |
| Classpath presence | existing `detectAvailableOnClasspath()` | narrows the candidate set for requestr's own process |

*Calibration — how UNKNOWN stops being the answer:* add `suspend fun calibrate(context: Context): CalibrationTable` to `StackFingerprinter`. It issues one identical request per stack **actually present on the classpath** (OkHttp always; Cronet after W2; `HttpURLConnection` always via `java.net.URL.openConnection`; Volley/Ktor only if `classExists` says so) against requestr's own `LocalApiServer` loopback endpoint (`/api/ping`, already served at 127.0.0.1:9876 per `LocalApiServer`), captures each one's JA3/ALPN/SETTINGS/header-order fingerprint through the same `StackSignalStore`, and persists the table. Subsequent classification compares observed fingerprints against this device-specific table. This makes the verdict evidence-based on the real device rather than a hardcoded assumption table, and it is what lets a custom User-Agent still resolve to a stack.

*Scoring:* each matched signal contributes a weight; the verdict is `DETECTED` when a runtime-adapter frame or an exact calibrated JA3+SETTINGS match exists, `LIKELY` at two or more independent agreeing signals, `POSSIBLE` at one, and `UNKNOWN` only when zero signals were collected for that request — and in that last case `reason` must name which signals were absent and why (for example "HTTP/1.1 plaintext, no TLS row, header order not sampled").

*API changes:* keep `HttpStack`, `detectFromHeaders` (as one signal contributor, not the entry point), and `detectAvailableOnClasspath`. Change `analyzeSession` to return a `StackDetectionReport` extended with `verdictsByStack: Map<HttpStack, Int>`, `confidenceHistogram: Map<Confidence, Int>`, `calibrated: Boolean`, and `unresolvedReasons: List<String>`.

*Tests:* new `app/src/test/java/com/example/runtime/http/StackFingerprinterTest.kt`
- OkHttp SETTINGS frame bytes (`INITIAL_WINDOW_SIZE = 16777216` only) → OkHttp at `DETECTED`/`LIKELY`; a different SETTINGS multiset → not OkHttp.
- `Dalvik/...` UA + `If-None-Match` + Volley's default content-type → Volley, with `reason` naming the cache-validator signal; the same UA with none of those → `HTTP_URL_CONNECTION`. This is the regression test that the old code's documented give-up is actually gone.
- a custom `User-Agent` plus a calibrated JA3 match → the calibrated stack, not `UNKNOWN`.
- zero signals → `UNKNOWN` with a non-empty `reason`.
- pseudo-header-order signal built from a real `Http2Parser.decodeHeaders` result, reusing the `headersFrame(...)` helper style already in `app/src/test/java/com/example/interception/grpc/GrpcParserTests.kt:21-30`.

*Note for the implementer:* `app/build.gradle.kts` already sets `testOptions { unitTests { isReturnDefaultValues = true } }`, which is why tests touching `android.util.Log` do not throw "not mocked" — keep using plain JVM tests rather than Robolectric where possible.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`.

**5. [parallel] Define one canonical connection identity and an alias registry every plane writes**

*Files:* new `app/src/main/java/com/example/correlation/ConnectionKey.kt`, `app/src/main/java/com/example/interception/tcp/TcpStream.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/MitmProxyEngine.kt`, `app/src/main/java/com/example/interception/gateway/GatewayEngine.kt`, `app/src/main/java/com/example/api/LocalApiServer.kt`.

*Grounding — three incompatible conventions exist today:*
- `TcpStream.kt:31`: `val key: String get() = "$protocol|$srcIp:$srcPort>$dstIp:$dstPort"`, with `keyFrom(...)` producing the same string; its only caller is `TcpReassembler.kt:130`. `CaptureEngine.kt:121` writes `TcpConnectionEntity(connectionId = stream.key, ...)` and `CaptureEngine.kt:178-180` writes `TlsSessionEntity(tlsSessionId = stream.key, connectionId = stream.key, ...)`.
- `PacketPump.kt:246`: `val connectionId = "quic|$src:$sPort>$dst:$dPort"`, also reused as `dstConnectionId` at line 288.
- `MitmProxyEngine.kt:611`: `val connectionId = "$host:$port"`, used for both `tlsSessionId` and `connectionId`.
- `RequestrVpnService.kt:253` ingests DNS events with `connectionId = null` (documented: DNS precedes the TCP connection).

*Change:*
- `object ConnectionKey` with `fun forTcp(srcIp, srcPort, dstIp, dstPort): String`, `fun forQuic(...)`, `fun forHostPort(host, port): String`, and a `parse(key): Parsed?` returning transport + endpoints. The canonical format stays byte-identical to `TcpStream.key`'s existing `"$protocol|$src:$sPort>$dst:$dPort"` so **no existing rows or keys change meaning** — this is a consolidation, not a renaming.
- Make `TcpStream.key`/`keyFrom` and `PacketPump`'s QUIC id delegate to `ConnectionKey` so there is exactly one string-format definition.
- New `object ConnectionAliasRegistry` (same file) mapping a plane-local id to the canonical key: `register(alias: String, canonical: String)`, `resolve(alias: String): String?`. `MitmProxyEngine.handleClient` has the client `Socket` in hand (`MitmProxyEngine.kt:141`, `client.getInputStream()`), so it can build the real canonical key from `client.inetAddress`/`client.port` plus the CONNECT target and register `"$host:$port"` as an alias for it. The same applies to `GatewayEngine` and to `LocalApiServer.receiveObserverEvents`, which can register the browser/SDK `requestId` as an alias.
- `MitmProxyEngine.persistMitmTlsSession` keeps `tlsSessionId = "$host:$port"` (it is the primary key of existing rows) but sets `connectionId` to the canonical key, which is exactly the field `ConnectionLineage` joins on. Also note the standing gap the docstring records — `ja3Hash = null` because "JA3 needs the raw ClientHello, which SSLSocket already consumed" (`MitmProxyEngine.kt:624`) — and resolve it by capturing the first client bytes before the `SSLSocket` handshake and running `TlsDecoder.parseClientHello` on them, so MITM'd rows finally carry a JA3 for W4 to use.

*Overlap with the transport plan — read before starting.* `/code/.plans/plan-transport.md` task 12 also threads `connectionId` through the persist paths, but it deliberately **keeps two conventions** (`stream.key` for `CaptureEngine`, `"$host:$port"` for `MitmProxyEngine`) and reasons that "the engine never sees the raw 5-tuple". That premise is checkable and false: `MitmProxyEngine.handleClient(context, client: Socket)` holds the accepted client socket (`MitmProxyEngine.kt:141`), so `client.inetAddress`/`client.port` plus the parsed CONNECT target give the full 5-tuple. W5 is therefore the *systematic* version of that task, not a duplicate: transport's task 12 populates the column, W5 unifies the key format and adds the alias registry so the two conventions collapse into one canonical value with `"$host:$port"` retained only as a registered alias. Existing `tlsSessionId` primary keys are untouched, so no rows are orphaned. Sequence W5 after transport's task 12 and treat it as a refinement commit on the same files.

*Tests:* new `app/src/test/java/com/example/correlation/ConnectionKeyTest.kt`
- `ConnectionKey.forTcp("10.0.0.2", 41234, "1.2.3.4", 443)` equals the literal legacy string `"6|10.0.0.2:41234>1.2.3.4:443"` (locks the format so no row is orphaned).
- `TcpStream(...).key` equals `ConnectionKey.forTcp(...)` for the same tuple.
- QUIC key format matches `PacketPump`'s existing `"quic|..."` literal.
- `parse()` round-trips both forms and returns null for garbage.
- alias registry: registering then resolving returns the canonical key; unknown alias returns null.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`.

**6. [after 5] Make ConnectionLineage deterministic: exact connectionId joins, no time window, no SNI fallback**

*Files:* `app/src/main/java/com/example/correlation/graph/ConnectionLineage.kt`, `app/src/main/java/com/example/data/local/Daos.kt`, `app/src/main/java/com/example/diagnostics/VisibilityAnalyzer.kt`, and every `CapturedTrafficEntity(` producer.

*Grounding:* `ConnectionLineage.kt:39` — `private const val HTTP_WINDOW_MS = 30_000L`; line 234 — `db.capturedTrafficDao().getByHostInWindow(sessionId, host, anchorTs, anchorTs + HTTP_WINDOW_MS)`; lines 209-211 — `getById(connectionId)` then `getLatestBySniHost(sessionId, dnsHostname)`. The DAO comments admit both: `Daos.kt:30-40` ("CapturedTrafficEntity has no connectionId column ... so the join has to be by host + a timestamp window instead") and `Daos.kt:308-313` ("MitmProxyEngine keys tlsSessionId as \"$host:$port\" ... we can't always reconstruct the MITM key").

*Dependency — do not resolve here:* the transport-scope plan owns adding `connectionId: String?` to `CapturedTrafficEntity`, the Room migration, and the new schema version. `RequestrDatabase.kt` is currently `version = 8` with `.fallbackToDestructiveMigration(dropAllTables = true)`; this plan must **not** pick a version number. W6 assumes the column exists and is indexed.

*Change:*
- Populate `connectionId` at every producer, using `ConnectionKey`/`ConnectionAliasRegistry` from W5: `CaptureEngine.kt` lines 195, 490, 594, 667 (all have `stream` in scope, so `stream.key`); `MitmProxyEngine.kt:439` (`emitTransaction`, canonical key from the registered alias); `GatewayEngine.kt:245`; `LocalApiServer.kt:304`; `HarImporter.kt:60` (imported HAR entries have no connection — leave null explicitly and let the lineage report "imported, no transport observed", which is a true statement about a HAR file rather than a fabricated hop). If the transport plan's task 12 has already landed these assignments, W6 only reconciles them onto the canonical key from W5 instead of writing them fresh.
- Add `@Query`-based `getByConnectionId(sessionId, connectionId)` to `CapturedTrafficDao` and stop using `getByHostInWindow` from the lineage path entirely.

  *Resolved in W6's favour — the 30-second window is removed from the lineage path (decision DEC-4).* The transport plan's task 12 proposed keeping `getByHostInWindow` as a fallback inside `httpSteps()` ("try `getByConnectionId` first; fall back to `getByHostInWindow` only when empty, and mark fallback-sourced steps' `detail` as window-matched", keeping `HTTP_WINDOW_MS = 30_000L`). That fallback is **not** implemented. A time window that silently attaches the wrong HTTP row to a connection fabricates a causal chain the data does not support, which is strictly worse than reporting that a row could not be attached. So: exact `connectionId`-keyed joins only. Rows whose `connectionId` is genuinely null (HAR imports, SDK `/api/observe` reports) are never window-guessed onto a connection; they surface through an `unattachedHttpRows` count on `LineageChain` plus a `MISSING`-kind step stating that those rows carry no transport identity.

  The DAO method `getByHostInWindow` may **remain in `Daos.kt`** if another caller legitimately uses it (audit callers before deciding; if the lineage path was its only caller, delete it). The binding constraint is narrower than the method's existence: **no code path reachable from `ConnectionLineage.build` may call it, and no `LineageStep` may ever be produced from its results.** The transport scope has been told the fallback is deleted at the lineage call site and must not be reintroduced there, so a later transport commit re-adding it is a regression, not a merge resolution.

  Two conditions attach to this decision and are requirements of W6, not commentary:

  1. **`unattachedHttpRows` must be user-visible, not just a struct field.** Honesty that only exists in a data class is not honesty the user benefits from. Surface both the count and the reason wherever lineage is presented — the `VisibilityAnalyzer` output rendered in the Settings "OBSERVATION REPORT" section (`SettingsScreen.kt:996`) and any lineage/provenance display fed by `ProvenanceGraph`. The presentation must state the count and *why* those rows are unattached (no `connectionId` recorded by the producing plane — HAR import, SDK report, or a producer that has not been threaded), so a non-zero value reads as a specific, diagnosable condition rather than a vague warning. Wire this into the same `LineageChain` → analyzer → UI path that already carries `limitingReasons`; do not add a parallel reporting channel.
  2. **A routinely non-zero `unattachedHttpRows` is a bug in `connectionId` threading, not an acceptable steady state.** Once transport's task 12 threading has landed and W5/W6 are in, every row produced by a plane that *has* a connection (`CaptureEngine`, `MitmProxyEngine`, `GatewayEngine`, `LocalApiServer`'s proxied path) must carry a `connectionId`, so the expected count for those planes is **zero**. Only two sources are legitimately expected to be unattached: `HarImporter.kt:60` (an imported HAR file genuinely has no observed transport) and SDK `/api/observe` reports (an out-of-process reporter with no visibility of the local 5-tuple). If the count is non-zero for any other source, the correct response is to find the producer that is writing null and fix it — never to widen matching or reinstate a window to make the number look better. State this in the code comment on `unattachedHttpRows` as well, so the next reader does not mistake a high count for normal behaviour. The W6 test suite asserts the zero-for-attached-planes expectation directly (see *Tests* below).
- Rewrite `httpSteps(...)` to take `connectionId` and use the exact join; drop `HTTP_WINDOW_MS` entirely.
- Rewrite `findTlsFor(...)` to a single exact lookup on `TlsSessionEntity.connectionId` (already indexed per the entity's `indices`), then delete `getLatestBySniHost` and its comment. The MITM leg now matches because W5 writes the canonical `connectionId` there.
- **`TlsHandshakeState.FAILED` is owned by the transport plan, not here.** `/code/.plans/plan-transport.md` task 11 already covers setting it from real signals (fatal alert records, RST during handshake, and per-`startHandshake()` `SSLHandshakeException` catches in `MitmProxyEngine`), and its task 10 adds the `failureReason`/`failedAt` columns. W6 therefore only *consumes* the result: give `LineageStep` a structured `kind` and surface `failureReason` in `detail`. Do not implement the FAILED writers here — that would be a duplicate edit to `MitmProxyEngine.handleClient` and `CaptureEngine.ingest`.
- Update `VisibilityAnalyzer.analyze` (`VisibilityAnalyzer.kt:52-61`) — its per-connection loop currently calls `ConnectionLineage.build` and pattern-matches on step *labels* (`it.label.startsWith("TLS handshake")`, `it.label.startsWith("No HTTP").not()`). Replace the string matching with a structured field on `LineageStep` (add `kind: LineageStepKind` enum: `DNS`, `TRANSPORT`, `TLS`, `HTTP`, `MISSING`) so the analyzer reads a type instead of parsing prose. Drop the now-false FAILED caveat string from `limitingReasons`.
- **Carry `unattachedHttpRows` out to the UI (condition 1 of DEC-4).** Aggregate the per-chain counts in `VisibilityAnalyzer.analyze` into a total on its report, alongside the reason, and render it in the Settings "OBSERVATION REPORT" section (`SettingsScreen.kt:996`) next to the existing report fields. The rendered text must give the count and the cause (rows whose producing plane recorded no `connectionId`), and should name the two expected sources — HAR imports and SDK reports — so a zero-vs-non-zero reading is actionable. Reuse the existing `limitingReasons` rendering pattern rather than introducing a second mechanism. This bullet is what makes DEC-4's honesty user-facing; a change that adds the field to `LineageChain` but stops short of the Settings render does not satisfy the decision. If the dispatched design mockups cover the observation-report card, follow them for placement and wording.
- `ProvenanceGraph.kt` needs no logic change (it delegates to `resolveHostFor` and `build`), but re-verify its `HostGroup` grouping still compiles against the new `LineageStep`.

*Tests:* new `app/src/test/java/com/example/correlation/graph/ConnectionLineageTest.kt` using an in-memory Room database (Robolectric is already a test dependency)
- TCP + TLS + two HTTP rows all sharing one canonical `connectionId` → a four-step chain in timestamp order, with both HTTP rows attached.
- an HTTP row for the same host but a *different* `connectionId`, timestamped 1 ms after the handshake → **not** attached. Under the old 30-second window it would have been; this is the regression test proving the heuristic is gone.
- a MITM-persisted TLS row (alias-keyed `tlsSessionId`, canonical `connectionId`) → matched by the exact join with no SNI fallback.
- a connection with a `FAILED` TLS row → a step with `success = false`.
- a connection with no HTTP rows → one `MISSING`-kind step, not an empty list.
- **`unattachedHttpRows` accounting (condition 1 of DEC-4).** Two null-`connectionId` rows plus one properly-keyed row → the chain attaches exactly one HTTP step and reports `unattachedHttpRows == 2`; assert the accompanying reason text names the missing-`connectionId` cause so the UI has something specific to render. A null-`connectionId` row must never appear as an attached step regardless of how close its timestamp is to the handshake.
- **Zero-unattached expectation for threaded planes (condition 2 of DEC-4).** Insert one row per plane that *does* have a connection — `CaptureEngine`-shaped, `MitmProxyEngine`-shaped (alias-keyed), and `GatewayEngine`-shaped — each carrying a canonical `connectionId`, and assert `unattachedHttpRows == 0`. This is the test that fails loudly if a producer regresses to writing null, which is the intended signal: the fix is the producer, never widening the match.
- **HAR/SDK rows stay unattached by design.** A `HarImporter`-shaped row and an SDK `/api/observe`-shaped row, both null-`connectionId`, count toward `unattachedHttpRows` and produce no attached step. This pins the two legitimate sources so a future reader can distinguish "expected unattached" from "threading bug".

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`. Coordinate with the transport plan before running `assembleDebug`, since the Room entity change lands there.

**7. [parallel] Give HttpEvent a first-class initiator and delete the pseudo-header hack**

*Files:* `app/src/main/java/com/example/correlation/ObservationEvent.kt`, `app/src/main/java/com/example/browser/CdpNetworkAdapter.kt`, `app/src/main/java/com/example/correlation/CorrelationEngine.kt`.

*Grounding:* `CdpNetworkAdapter.kt:121` is
```kotlin
val requestHeaders = req.headers + ("X-Requestr-Initiator" to req.initiatorDescription)
```
and its KDoc at line 26 admits "a cleaner design would add a dedicated `initiator: String?` field to [HttpEvent], but that ripples through every other producer of `HttpEvent`". The ripple is small: a repo-wide grep for `HttpEvent(` finds exactly one construction site outside the declaration — `CdpNetworkAdapter.kt:124`.

*Change:*
- Add to `HttpEvent`: `val initiator: String? = null`, `val initiatorStackFrames: List<StackFrame> = emptyList()`, `val requestBody: String? = null`, `val responseBody: String? = null`. Defaults keep the single existing producer compiling.
- `CdpNetworkAdapter.emit(...)`: drop the pseudo-header, pass `initiator = req.initiatorDescription` and `initiatorStackFrames = InitiatorResolver.resolveStack(initiatorJson)` (that function already exists at `InitiatorResolver.kt` and returns frames only when `type == "script"`). Keep the `PendingRequest` buffer but store the raw initiator `JSONObject` alongside the description so the stack survives to emit time.
- Keep `"X-Requestr-Error"` on the `loadingFailed` path or promote it to a real field for symmetry — either is fine, but do not leave one hack while removing the other; recommend moving it to the existing `HttpEvent`-adjacent error handling.
- `CorrelationEngine.buildCorrelationKey` (in `CorrelationEngine.kt`) uses `CorrelationKey(event.host, null, event.url, event.timestamp / 1000)` for `HttpEvent`; no change needed, but confirm nothing keys off `requestHeaders` contents.

*Tests:* new `app/src/test/java/com/example/browser/CdpNetworkAdapterTest.kt`
- feed a synthetic `Network.requestWillBeSent` + `responseReceived` pair through the adapter's handler with a fake client and assert the emitted `HttpEvent` has `initiator` set and `requestHeaders` containing **no** key starting with `X-Requestr-` — the regression test that the hack is gone.
- assert a `"script"`-type initiator yields non-empty `initiatorStackFrames` and that a `"parser"`-type yields an empty list with a non-null `initiator` string.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`.

**8. [after 7] Multi-target CDP client with flattened session routing and reconnect-with-backoff**

*Files:* `app/src/main/java/com/example/browser/CdpClient.kt`, `app/src/main/java/com/example/browser/BrowserBridgeManager.kt`, `app/src/main/java/com/example/browser/CdpNetworkAdapter.kt`, `app/src/main/java/com/example/browser/CdpFetchAdapter.kt`, `app/src/main/java/com/example/browser/ServiceWorkerObserver.kt`, `app/src/main/java/com/example/browser/BrowserStorageObserver.kt`.

*Grounding:* `discoverPageTarget()` does `GET http://$host:$port/json/list` and picks the **first** `type == "page"` target with a non-empty `webSocketDebuggerUrl`. `send(method, params, callback)` writes `{"id":N,"method":...,"params":...}` with no `sessionId` field. `dispatch(text)` resolves `pending[id]` or fans out to `eventListeners[method]` with no per-target demultiplexing; `Target.*` is never used. `onClosed` sets `DISCONNECTED` for code 1000 and `FAILED` otherwise, with no retry — all documented in the class KDoc as scope caveats.

*Change (protocol shape confirmed against the CDP reference):*
- Connect to the **browser-level** endpoint (`/json/version`'s `webSocketDebuggerUrl`) rather than a page target, then `Target.setDiscoverTargets {"discover": true}` and `Target.setAutoAttach {"autoAttach": true, "waitForDebuggerOnStart": false, "flatten": true}`. With `flatten: true`, each attached child reports its `sessionId` on `Target.attachedToTarget`; subsequent commands carry that `sessionId` at the top level of the message. Handle `Target.detachedFromTarget` and `Target.targetDestroyed` for teardown.
- `send(method, params, sessionId: String? = null, callback)` — add the optional `sessionId` and include it in the JSON when non-null. `dispatch` must read the inbound `sessionId` and route events to per-session listener maps: change `eventListeners` to key on `(sessionId, method)` with a wildcard bucket for browser-level events.
- Expose `val targets: StateFlow<Map<String, TargetInfo>>` (sessionId → title/url/type) so the Settings card can list every attached tab.
- Reconnect: on `onFailure`/non-1000 `onClosed`, schedule a retry with exponential backoff (e.g. 1 s → 2 s → 4 s → 8 s → 16 s, capped, with a jitter term) on the manager's existing `CoroutineScope(Dispatchers.Default + SupervisorJob())`. Expose attempt count and next-retry delay as state so the UI shows "reconnecting in 4 s (attempt 3)" instead of a bare FAILED. Cancel the retry loop on explicit `disconnect()`.
- `BrowserBridgeManager` currently holds one instance each of `networkAdapter`, `fetchAdapter`, `serviceWorkerObserver`, `storageObserver`. Change to per-session maps keyed by `sessionId`, created on `Target.attachedToTarget` and torn down on detach/destroy. `originFromTargetInfo(...)` becomes per-target, driven from that target's own url.
- `eventsIngested` stays a total but add a per-target breakdown for the UI.

*Tests:* new `app/src/test/java/com/example/browser/CdpClientRoutingTest.kt` — test the message-construction and dispatch logic as pure functions over JSON strings, with no socket:
- `buildMessage("Network.enable", params, sessionId = "S1")` contains `"sessionId":"S1"`; with `sessionId = null` it contains no `sessionId` key.
- `dispatch` of an event carrying `"sessionId":"S1"` invokes only the S1 listener, not the S2 listener.
- `dispatch` of a browser-level event with no `sessionId` invokes only the browser-level listener.
- `Target.attachedToTarget` then `Target.detachedFromTarget` adds and removes the target from the state map.
- backoff schedule is a pure function: attempt 1..6 produces the expected capped delays and never returns a negative value.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`.

**9. [after 8] Response-stage Fetch with real bodies and enforced MODIFY_RESPONSE, real IndexedDB records and DOMStorage change events, and the Settings card that shows it all**

*Files:* `app/src/main/java/com/example/browser/CdpFetchAdapter.kt`, `app/src/main/java/com/example/browser/BrowserStorageObserver.kt`, `app/src/main/java/com/example/browser/CdpNetworkAdapter.kt`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (lines 806-927).

*Grounding:* `CdpFetchAdapter.start()` sends `Fetch.enable` with `{"patterns":[{"urlPattern":"*","requestStage":"Request"}]}` — request stage only — and the rule dispatch at line 113 is `RuleAction.MODIFY_RESPONSE, RuleAction.LOG_ONLY -> { continueRequest; allowedCount++ }`, i.e. MODIFY_RESPONSE is a silent no-op, exactly as the KDoc at line 33 admits. `CdpNetworkAdapter` hardcodes `requestSize = 0, responseSize = 0` and never calls `Network.getResponseBody`. `BrowserStorageObserver`'s KDoc admits `IndexedDB.requestData` record contents and `DOMStorage.domStorageItemAdded/Removed/Updated` are not implemented.

*Change — response-stage Fetch:*
- `Fetch.enable` with two patterns: `{"urlPattern":"*","requestStage":"Request"}` and `{"urlPattern":"*","requestStage":"Response"}`. Distinguish the stage inside `Fetch.requestPaused` by the presence of `responseStatusCode`/`responseErrorReason` in the event params (the documented discriminator).
- Response stage: call `Fetch.getResponseBody` (valid only while paused in the response stage; returns `{body, base64Encoded}`) to capture the real body, then either `Fetch.continueResponse` (unchanged, or with modified status/headers) or `Fetch.fulfillRequest` when a `MODIFY_RESPONSE` rule matched. Reuse `RuleEnforcer.applyResponseModification(response, config)` (`RuleEnforcer.kt:84-100`, which already handles `statusCode`, `addHeaders`, `bodyReplace`) so CDP enforcement uses the same rule semantics as `MitmProxyEngine.kt:412-416`. Skip body fetch for redirect status codes, which have no body.
- Feed the captured bodies back into `CdpNetworkAdapter`'s emitted `HttpEvent` via the new `responseBody`/`responseSize` fields from W7, so browser rows stop reporting size 0.
- Rename/extend the counters: `allowedCount`/`blockedCount`/`modifiedCount` become `@Volatile` fields plus a `responseModifiedCount` and `bodiesCapturedCount`, all exposed as a `StateFlow` so the Settings card can render them (today they exist but nothing displays them).

*Change — storage:*
- `IndexedDB.requestDatabaseNames` (already implemented) → for each database, `IndexedDB.requestDatabase {securityOrigin, databaseName}` to enumerate object stores, then `IndexedDB.requestData {securityOrigin, databaseName, objectStoreName, indexName:"", skipCount:0, pageSize:N}` to read real records. Extend `StorageSnapshot` with `indexedDbRecords: Map<String, List<String>>` (store → rendered records) and a `hasMore` flag from the response.
- Subscribe to `DOMStorage.domStorageItemAdded`, `domStorageItemRemoved`, `domStorageItemUpdated`, and `domStorageItemsCleared` (the `DOMStorage.enable` call already exists) and apply each delta to the in-memory `localStorage` map so it is live rather than snapshot-only. Keep `refreshStorage()` for an explicit resync.

*Change — Settings card:* the "BROWSER BRIDGE (CDP)" card (header at `SettingsScreen.kt:807`) currently shows one connection dot, one host:port field, one switch, an event count, service-worker count, and a storage one-liner. Extend it with: a per-target list from W8's `targets` flow (title, url, type, per-target event count); reconnect state ("reconnecting in Ns, attempt N"); the Fetch counters including `responseModifiedCount` and `bodiesCapturedCount`; and an IndexedDB record count next to the existing database-name count. Reuse the existing `SettingsSectionHeader` + `GlassCard` + `collectAsState` idiom already used throughout the file; the status-dot colours at lines ~830-840 (`0xFF10B981` connected, `0xFFF59E0B` connecting, `colorScheme.error` failed) stay as-is. Update the footer text at line 921 if the multi-target change makes it inaccurate. The dispatched design mockups for the Browser Bridge card are the visual spec for the per-target list, reconnect state, and counter layout; match them for structure and labels while keeping the behavioural contract above. As with the Runtime card, no counter or label may imply a capability that is not wired — in particular `responseModifiedCount` must only be non-zero once W9's response-stage path actually fulfils a modification.

*Tests:* new `app/src/test/java/com/example/browser/CdpFetchAdapterTest.kt`
- a `Fetch.requestPaused` params object **without** `responseStatusCode` classifies as request stage; **with** it classifies as response stage.
- a `MODIFY_RESPONSE` rule at the response stage produces a `Fetch.fulfillRequest` command whose params carry the modified status/headers/body — the regression test that MODIFY_RESPONSE is no longer a no-op.
- a `LOG_ONLY` rule at the response stage produces `Fetch.continueResponse`, not `fulfillRequest`.
- a 302 response skips the `getResponseBody` call.
- base64-encoded body decoding round-trips.
Extend `app/src/test/java/com/example/browser/` with a storage test: applying `domStorageItemAdded` then `domStorageItemUpdated` then `domStorageItemRemoved` deltas to a snapshot yields the expected final map; `IndexedDB.requestData` response JSON parses into the expected record list.

*Verification:* `./gradlew :app:compileDebugKotlin --no-configuration-cache`, `./gradlew :app:testDebugUnitTest --no-configuration-cache`, `./gradlew :app:assembleDebug --no-configuration-cache`.

---

### Testing

**Per-item focused tests** are listed inline above. All new tests go under `app/src/test/java/com/example/` alongside the existing `ExampleUnitTest.kt`, `interception/CaptureEngineParserTests.kt`, `interception/grpc/GrpcParserTests.kt`, and `interception/quic/{QpackDecoderTest,QuicCryptoTest,QuicParserTest}.kt`. Test dependencies already present in `app/build.gradle.kts`: `junit`, `androidx.junit`, `kotlinx-coroutines-test`, `robolectric`, `roborazzi`, `androidx-compose-ui-test-junit4`. Keep new tests plain JVM where possible; `isReturnDefaultValues = true` in `testOptions` is what stops `android.util.Log` calls from throwing.

**Integration verification, in this order:**
1. `./gradlew :app:compileDebugKotlin --no-configuration-cache` after every item.
2. `./gradlew :app:testDebugUnitTest --no-configuration-cache` — full suite, not just the new classes, to catch fallout in `CaptureEngineParserTests` from the `HttpParser`/`CaptureEngine` edits in W4/W6.
3. `./gradlew :app:assembleDebug --no-configuration-cache` — must stay BUILD SUCCESSFUL. Record the new APK size against the 28,044,378-byte baseline; if W2 lands `cronet-embedded`, expect roughly +13.7 MB before packaging effects.
4. On-device smoke checks (a real emulator/device is required for these; they are not unit-testable): Settings → Runtime Instrumentation shows Cronet and WebView as active with non-zero event counts after driving one request through each; the traffic list contains the resulting rows; Settings → Browser Bridge lists more than one tab when two are open in a `--remote-debugging-port=9222` Chrome, and a `MODIFY_RESPONSE` rule visibly alters a page response.
5. Lineage check: run a capture, open the Connections bottom sheet → Provenance tab in `InterceptScreen.kt`, and confirm each chain's HTTP hop belongs to that connection rather than any same-host request within 30 seconds.

---

### Risk and integration notes

**Shared files other scope agents will also touch — coordinate before editing:**

| File | This plan's edits | Collision risk |
|---|---|---|
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | three cards: RUNTIME INSTRUMENTATION (723), HTTP Stack Detection (758-796), BROWSER BRIDGE (806-927) | High — 1858 lines, every scope wants a card here. Land edits card-by-card, not as one rewrite. |
| `app/src/main/java/com/example/data/local/Entities.kt` | `CaptureTier.RUNTIME` constant (DEC-3) only | Transport plan adds `CapturedTrafficEntity.connectionId` here. |
| `app/src/main/java/com/example/data/local/Daos.kt` | add `getByConnectionId`; delete `getLatestBySniHost`; remove all lineage-path calls to `getByHostInWindow` (keep the DAO method only if another caller needs it — see DEC-4) | Transport plan may add queries for the same DAO. |
| `app/src/main/java/com/example/data/local/RequestrDatabase.kt` | **no edit** — this plan never sets a schema version | Transport plan owns the bump from the current `version = 8` and the migration. |
| `app/src/main/java/com/example/correlation/ObservationEvent.kt` | new `HttpEvent` fields (W7) | Additive with defaults; only one existing producer. |
| `app/src/main/java/com/example/correlation/CorrelationEngine.kt` | read-only verification that nothing keys off `requestHeaders` | SDK plan may add producers. |
| `app/src/main/java/com/example/runtime/http/HttpStackAdapters.kt` | rewrite `CronetAdapter` and `WebViewAdapter`; `OkHttpAdapter` untouched | SDK plan touches `OkHttpProxyInterceptor`, which `OkHttpAdapter` drains. |
| `app/src/main/java/com/example/interception/MitmProxyEngine.kt` | canonical `connectionId`, real JA3 capture, `FAILED` handshake state | Transport/decryption plan owns the CONNECT path — high collision risk on W5/W6. |
| `app/src/main/java/com/example/interception/CaptureEngine.kt` | populate `connectionId` at four `CapturedTrafficEntity(` sites (195, 490, 594, 667); feed W4 signals | Transport plan owns the tun/packet path. |
| `app/src/main/java/com/example/ui/screens/InterceptScreen.kt` | read-only — verify the Provenance tab still renders after `LineageStep` gains `kind` | App-level plan owns this screen. |
| `browser-extension/` | **out of scope** | The MV3 JS SDK belongs to the SDK plan; this plan owns only the in-app Kotlin CDP side. Keep the W3 bridge payload shape aligned with `/api/observe`'s existing schema so the two planes remain comparable. |

**Other risks:**
- **APK size (W2) — accepted for debug, sized for release.** `cronet-embedded:143.7445.0` adds four `libcronet` ABIs at 4.1–7.2 MB each, roughly +13.7 MB on the 28,044,378-byte debug APK. Per decision DEC-1 this is accepted for the debug variant, which is what on-device verification uses. For a release variant the mitigation is an ABI split, per-variant `abiFilters`, or an App Bundle (which ships only the device's ABI automatically); any filtering must retain `x86`/`x86_64` in debug or Cronet breaks on emulators and x86 Redroid. The AAR's 11,121-byte `proguard.txt` is applied automatically as a consumer ProGuard file, so no manual R8 keep rules are expected — but a minified release build should be smoke-tested against a live Cronet request before anyone claims the release variant works. Configuring splits/bundles/minified verification is out of scope here; it is flagged, not silently added.
- **WebView thread affinity (W3).** `WebView` must be constructed and driven on the main thread; `InstrumentationAdapter.start(context)` is called from `MainActivity` (main thread) today but the engine's poll loop runs on `Dispatchers.Default`, so all WebView interaction must be posted to the main looper. Getting this wrong yields a hard crash, not a degraded capture.
- **`ProxyController.setProxyOverride` is process-wide**, not per-WebView. Enabling it affects every WebView in requestr's process. Currently there are none besides the adapter's, but note it.
- **Room ordering is not a fingerprint source (W4).** Header order/casing must be sampled at parse time: `HttpParser.kt:56,122` lowercases names, and the Moshi `Map<String,String>` converter deserialises into `LinkedHashTreeMap` (a tree map). Any design that tries to read ordering back out of `CapturedTrafficEntity.requestHeaders` will silently produce wrong verdicts.
- **`CorrelationEngine` needs two planes to correlate.** `processCorrelation` requires `events.size >= 2` **and** `bySource.size >= 2`, and the key uses `timestamp / 1000`. Runtime-only or browser-only events will not appear as correlated requests; that is existing behaviour and W1's Room sink is what makes single-plane runtime captures visible regardless.
- **VPN_TUN blackhole is not this scope.** `AUDIT_REPORT.md`'s 2026-08-21 addendum and `TODO_v5.md` disclose that `PacketPump`'s tun `output: FileOutputStream` is never written, so the VPN_TUN tier drops device traffic. W3's proxy-override path deliberately routes WebView through the MITM proxy rather than depending on tun relay, so it is unaffected — but on-device end-to-end capture testing for other planes will be limited until the transport plan lands that fix.
- **`AUDIT_REPORT.md` is stale** (snapshot of `main` @ v3.4). Use `TODO_v5.md` as the maintained tracker when cross-checking claims.
