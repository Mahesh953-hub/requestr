# requestr Master Feature-Addition Plan
### Synthesized from 20 reference-repo audits (19 external clones + chucker's own engine, previously audited)

**Status:** Planning document only. No application code is included or implied to be
copy-pasted verbatim — every item below cites the exact source file/class in the
reference repo and the exact requestr file/line it plugs into, but implementation is
left to a future engineering pass.

**Method:** 19 independent read-only research passes each cloned one external repo,
read its actual source (not its README marketing), cross-checked every claim against
requestr's real code by grep/read, and produced a report at
`/tmp/research-reports/<slug>.md` with an explicit `{LOW|MODERATE|HIGH} ADD` or
`IGNORE` verdict per feature, citing concrete classes/files on both sides. chucker
itself was audited directly in a prior phase (`HTTPS-INTERCEPTION-ENGINE-REPORT.md`,
already pushed to `docs/report/` in this repo) rather than re-spawned as a 20th task,
since it had already been read end-to-end.

This document is the **review-gate output**: every `ADD` verdict from all 19 reports
was re-checked against requestr's confirmed architecture before being included here.
Duplicate recommendations from multiple repos pointing at the same gap were merged.
Vague or doc-only items were explicitly downgraded (see "Review Gate Log" below). No
recommendation in this document is a stub, a marketing claim, or a "would be nice"
guess — every one names a real class in the source repo and a real file/line in
requestr.

---

## 0. The 9 standing architectural gaps (for reference)

These were confirmed in the prior direct-code-read audit (`engine-report-requestr.md`)
and used as the checklist every research pass was instructed to test against:

1. `MitmProxyEngine` and `CaptureEngine`/`TcpStream` are disconnected —
   `TcpStream.decryptedByMitm` (`TcpStream.kt:81`) is declared and gated
   (`CaptureEngine.kt:299`) but never set `true` anywhere in the codebase.
2. `MitmProxyEngine` only parses HTTP/1.x (`HttpParser`); HTTP/2, WebSocket, and gRPC
   on the MITM leg fall back to blind raw relay.
3. The Frida pinning-bypass prototype (`FridaClient`/`FridaCaptureEngine`) is
   non-functional (hardcoded target, misused `--codeshare`, `TrustAllCerts` never
   wired into a live `SSLContext`).
4. No non-root / no-system-CA way for a single target app to trust requestr's cert
   without the target app's own developer cooperation.
5. JA3 is always `null` on the MITM path — JSSE consumes the raw ClientHello before
   requestr's code can inspect it.
6. QUIC/HTTP-3 bodies are undecodable beyond the keyless Initial packet —
   `TlsKeyLogRegistry.lookup()` is never populated by the MITM engine.
7. Export format completeness (found to be largely a *non-issue* per research-001 —
   requestr's `TrafficExporter`/`CodeExporter`/`HarImporter` already exceed most peers;
   the real residual gap turned out to be in `WorkspaceExporter`'s lossy
   saved-request export, not captured-traffic export).
8. UI/UX ergonomics of the transaction inspector.
9. ApiTestr feature completeness (collections, environment variables, chaining,
   import/export, auth helpers, scripting).

Two **new** gaps were discovered during this research pass, confirmed by direct code
read, and are added to the checklist because they are concrete data-correctness /
capability bugs, not aspirational features:

10. **`HttpParser.tryParseResponse()` force-decodes every response body as UTF-8
    regardless of `Content-Encoding`** — gzip'd JSON (most real API traffic) is
    captured and displayed as garbled bytes. (source: `loggerinterceptor-ihsanbal.md`)
11. **Every MITM rule action except `BLOCK` still dials the real upstream server** —
    there is no way to short-circuit a request with a synthesized mock response
    without also making the real network call first. (source:
    `loggerinterceptor-ihsanbal.md`, `rorointercept.md`)

---

## 1. HIGH ADD — ship these first

### 1.1 Fix the VPN/MITM disconnect (closes Gap #1) — two alternative approaches found

**Approach A — proxy-output bridge (recommended, lower risk):**
`reqable/tun2proxy` (Rust, Tokio, `ipstack` crate) issues a literal
`CONNECT host:port HTTP/1.1` on a configured upstream proxy for every TUN-terminated
TCP flow — byte-for-byte what `MitmProxyEngine.handleClient` already parses on its own
`ServerSocket`. Routing requestr's VPN tun fd through tun2proxy configured with
`--proxy http://127.0.0.1:<MitmProxyEngine.port>` would feed every VPN-captured flow
into the already-working MITM decrypt pipeline — fixing the disconnect by routing
around the dead `decryptedByMitm` field entirely, not patching it. Cost: requestr has
zero NDK/Rust/JNI toolchain today, so this requires adding
`externalNativeBuild`/`cargo`/`cbindgen` to the build. (source: `tun2proxy.md`)

**Approach B — in-process interceptor-chain wiring (deeper refactor):**
`MegatronKing/NetBare-Android`'s `HttpVirtualGateway` builds one ordered interceptor
chain where `HttpSSLCodecInterceptor` (JSSE MITM TLS termination) sits directly between
the TUN relay and `Http2SniffInterceptor`/`Http2DecodeInterceptor` — decrypted bytes
flow via `chain.process(buffer)` straight into the next parsing stage, in-process, with
no separate disconnected proxy tier at all. Applied to requestr this means refactoring
`RequestrVpnService`/`TunRelay` to hold a live reference to `MitmProxyEngine`'s decrypt
output and feed it directly into `CaptureEngine.ingest()`, rather than relying on a
shared-schema/Room-only connection between the two engines.
(source: `netbare.md`)

**Recommendation:** attempt Approach A first (bounded integration cost, reuses
`MitmProxyEngine` as-is); fall back to Approach B if the Rust/NDK toolchain proves too
costly for the team.

### 1.2 Real HTTP/2 (HPACK) + WebSocket parsing on the MITM leg (closes Gap #2)

Two independent reference implementations confirm the same fix is buildable:
- `NetBare-Android`'s `Http2SniffInterceptor`/`Http2DecodeInterceptor` do ALPN
  negotiation + HPACK-capable HTTP/2 decoding directly after TLS termination.
- `wanghongenpin/proxypin`'s `Http2Codec` (`h2_codec.dart`) and WebSocket frame parser
  do the same in Dart, over the same "decrypt then parse" architecture.

Concretely: extend `MitmProxyEngine.inspectClientToServer`/`inspectServerToClient` to
negotiate `h2` via ALPN during the TLS handshake, and add an HPACK decoder stage before
falling back to `HttpParser`'s HTTP/1.x-only path — replacing the current blind-relay
fallback (`MitmProxyEngine.kt:429`) for anything that isn't plain HTTP/1.x.
(sources: `netbare.md`, `proxypin.md`)

### 1.3 A working CA-free per-app capture tier (mitigates Gap #3/#4 for cooperating or rooted setups)

Three independent repos converge on the same working mechanism — hooking
`okhttp3.OkHttpClient$Builder.build()` inside the *target app's own process* and
splicing in an `Interceptor`, which runs **after** the target app's own TLS stack has
already decrypted the response, needing zero CA/VPN/proxy at all and being immune to
certificate pinning by construction (since it never touches the TLS layer):
- `Familyye/HttpInterceptor` — a working LSPosed/Xposed module doing exactly this
  (`ClientBuilderHook.kt`), confirmed functional in ~250 lines.
- `siyujie/OkHttpLoggerInterceptor-hook` — the same mechanism as an Xposed module
  (`OkHttpHooker`/`MyInterceptor`), with more complete duplex body/header capture
  (binary detection, gzip awareness, response-body reconstruction).

Concretely: port this mechanism as a **new Frida builtin script** in
`FridaCaptureEngine.kt`, replacing the currently broken `trustmanager` script (which
defines `TrustAllCerts` but never wires it into a live `SSLContext`) and the broken
`FridaClient.kt:89` (hardcoded `com.android.shell` target, misused `--codeshare`).
requestr's own `RuntimeInstrumentationEngine.kt` already documents an
"adapter pattern for Frida/Xposed/SDK" via `InstrumentationAdapter` with no Xposed
adapter implemented — this closes that empty extension point directly, giving
requestr a 5th, pinning-immune capture tier for OkHttp-based target apps.
(sources: `httpinterceptor-familyye.md`, `okhttploggerinterceptor-hook.md`)

### 1.4 Fix gzip decompression in the capture parser (closes new Gap #10 — data correctness bug)

`HttpParser.tryParseResponse()` force-decodes every response body as UTF-8 with no
`Content-Encoding` check at all. `ihsanbal/LoggingInterceptor`'s `Printer.getResponseBody()`
shows the exact fix: check the header, and if `gzip`, wrap the body stream in a
`GzipSource` before charset-decoding. Without this, the majority of real-world gzip'd
JSON API traffic requestr captures today is stored and displayed as garbled bytes —
this is the single highest-value, lowest-effort fix in this entire plan.
(source: `loggerinterceptor-ihsanbal.md`)

### 1.5 A real `MOCK_RESPONSE` rule action (closes new Gap #11)

Traced end-to-end: `MitmProxyEngine.handleParsedRequest`/`handleParsedResponse` dial
the real upstream server for every rule action except `BLOCK`; even `MODIFY_RESPONSE`
only rewrites *after* the real round trip completes. `ihsanbal/LoggingInterceptor`'s
`enableMock()`/`BufferListener` mechanism proves the "skip the real call, synthesize a
response instead" pattern requestr is missing. requestr's own
`{"statusCode","addHeaders","bodyReplace"}` schema already used by
`RuleEnforcer.applyResponseModification` is already ~90% of the schema a `MOCK_RESPONSE`
action would need — this is mostly wiring a short-circuit branch, not new schema design.
(source: `loggerinterceptor-ihsanbal.md`, corroborated by `rorointercept.md`'s
first-class `MockResponse` action in its own rule model)

### 1.6 Postman Collection v2.1 import/export for ApiTestr (closes part of Gap #9)

Confirmed by grep: zero Postman-format code exists anywhere in requestr today, and the
only saved-request export path (`WorkspaceExporter.exportWorkspace()`) is lossy —
it drops headers/body/auth/query-params/formData/chainExtractions/assertions.
`tangcent/easy-api`'s `PostmanCollection`/`PostmanItem`/`PostmanRequest`/`PostmanUrl`/
`PostmanHeader`/`PostmanBody`/`PostmanQuery`/`PostmanFormParam` schema
(`channel/postman/model/PostmanModels.kt`) is a ready-made, industry-standard Kotlin
data-class shape to port as a new `PostmanCollectionCodec`, fixing the lossy export and
adding true Postman-collection import (batch-populating `SavedApiRequestEntity` rows
via the existing merge-import pattern in `WorkspaceExporter.importWorkspace()`).
`salesforce-misc/ReVoman` independently corroborates the value of this from the
import side — its primary input format is a Postman collection, and its `Rundown`
report model is a useful reference for what a "run a whole collection" summary should
capture. (sources: `easy-api.md`, `revoman.md`)

### 1.7 Correct shell-escaping for "Copy as cURL" (closes a confirmed bug, part of Gap #9)

Confirmed by reading `ApiTestrRepository.generateCodeSnippet`'s cURL branch
(`data/repository/ApiTestrRepository.kt:351-355`): today a header value containing a
backtick/`$()`/double-quote, or a body value containing an apostrophe (e.g.
`O'Brien`), produces a broken or shell-injectable cURL command when pasted into a
terminal. `easy-api`'s `escapeShell()` (`channel/curl/CurlFormatter.kt:237-239`) is
POSIX-correct and directly portable. (source: `easy-api.md`)

### 1.8 Executable pre/post-response scripts for ApiTestr (closes part of Gap #9 — dead UI)

Confirmed: the pre/post-response script fields already exist in requestr's own UI and
DB models (`AppModels.kt`, `ApiTestrScreen.kt`) but `ApiTestrRepository.executeRequest()`
never executes them — this is literally dead UI today. `salesforce-misc/ReVoman` runs
real Postman-script-shaped JS (via GraalJS, `pm.environment.set()`/`pm.test()`) against
each request/response in its chain. ReVoman itself is not embeddable (needs JDK 25,
GraalJS/Truffle, Spring — none Android-compatible), so this is a **design donor only**:
implement the actual execution using a lightweight embeddable JS engine appropriate for
Android (e.g. a small ECMAScript interpreter), matching ReVoman's `pm.*`-style API
surface just closely enough to wire up the existing dead fields.
(source: `revoman.md`)

### 1.9 OkHttp-mock-backed unit tests for `ApiTestrRepository`/`OkHttpProxyInterceptor` (closes a confirmed zero-coverage gap)

Confirmed by grep: `app/src/main/java/com/example/interception/OkHttpProxyInterceptor.kt`
and `ApiTestrRepository.kt` (which takes `OkHttpClient` as a DI-ready constructor
param) have **zero** test files, and `app/build.gradle.kts` has no OkHttp-mocking test
dependency at all — while the `sdk` module already uses real `MockWebServer`.
`gmazzo/okhttp-client-mock`'s `MockInterceptor` + `Rule.Builder` is a small,
purpose-built `testImplementation`-only library to finally close this gap.
(source: `okhttp-client-mock.md`)

### 1.10 Remote-cert-mirroring leaf regeneration on handshake failure (hardens Gap #4's MITM path)

`wanghongenpin/proxypin`'s `CertificateManager.generateByRemoteCert()` fetches the
real upstream leaf certificate and mirrors its exact fields (SANs, validity window,
key usage) into the locally-minted MITM leaf, rather than requestr's current
single-exact-hostname-SAN generation (`MitmProxyEngine.generateHostCert()`). This
reduces handshake failures against apps/libraries that validate more than just the
hostname (e.g. checking SAN completeness or issuer chain shape) before requestr's own
pinning-bypass tooling is even relevant. (source: `proxypin.md`)

### 1.11 Embedded JS scripting engine for live MITM request/response rewriting (extends the rule engine, distinct subsystem from 1.8)

`proxypin`'s `ScriptInterceptor` lets a user write JS that runs against every
intercepted request/response passing through the *live* MITM engine (as opposed to
1.8's ApiTestr-only manual testing scripts). This is a genuinely more powerful rewrite
mechanism than requestr's current fixed `MODIFY_REQUEST`/`MODIFY_RESPONSE` JSON-schema
actions in `RuleEnforcer`, and would subsume most of the "richer rule condition/action
model" MODERATE ADD below (§2.1) if implemented at HIGH-ADD depth. (source: `proxypin.md`)

---

## 2. MODERATE ADD

### 2.1 Richer rule condition/action model for `RuleEnforcer`/`ConnectControl`

Two repos independently show a materially richer rule schema than requestr's current
host-substring-or-full-URL-regex matching:
- `equisde/RoRoIntercept`'s `Models.kt` (`ProxyRule`/`RuleCondition`/`MatchType`/
  `RuleAction`/`ModifyAction`/`SearchReplace`/`MockResponse`) supports multi-condition
  AND matching, NOT-* negation, and regex/case-sensitive/replace-all search-replace for
  both headers and body.
- `gmazzo/okhttp-client-mock`'s composable matcher DSL (`Matcher`/`Rule.Builder`:
  method/path/header/query/body matchers, `OrMatcher`/`NotMatcher` composition) is a
  clean reference for the matcher-composition API shape.
This is complementary to, and a smaller step than, the full JS-scripting-engine option
in §1.11 — implement whichever fits the team's appetite; both close the same gap.
(sources: `rorointercept.md`, `okhttp-client-mock.md`)

### 2.2 gRPC capture via a dedicated `ClientInterceptor` (sdk module, sidesteps Gap #1/#2 for the opt-in path)

`openflocon/Flocon`'s `FloconGrpcBaseInterceptor` (+ `JsonFormat`/Gson formatters)
captures gRPC calls via gRPC's own `ClientInterceptor` API on already-deserialized
proto messages — entirely avoiding the HTTP/2+HPACK wire-parsing problem for apps that
embed requestr's `sdk` module voluntarily. Add an analogous `GrpcInterceptor` class to
requestr's `sdk` module alongside `RequestrOkHttpInterceptor`/`CronetInterceptor`.
(source: `flocon.md`)

### 2.3 WebSocket capture decorator for the `sdk` module

`Flocon`'s `FloconWebSocket` decorator instruments a `WebSocketListener` from inside
the embedding app. requestr's `sdk` module currently has zero WebSocket instrumentation
— add an analogous wrapper alongside the gRPC interceptor above. (source: `flocon.md`)

### 2.4 Bulk, time-range-filtered network-log export endpoint

`Flocon`'s `NetworkExportEndpoint.kt` supports bulk, filterable export. requestr's
`/api/captures` endpoint (`LocalApiServer.kt`) caps at 50 rows and its HAR export is
single-entry only — add a bulk/time-range-filtered variant. (source: `flocon.md`)

### 2.5 Non-root per-app UID attribution via `/proc/net` scanning

`NetBare-Android`'s `UidDumper` resolves the owning app UID for a captured connection
by scanning `/proc/net/tcp`/`/proc/net/tcp6` — no root required. requestr's
`ConnectControl.kt:39` already carries an unused `clientUid` parameter with no
populating logic anywhere; wiring `UidDumper`'s technique in would let the existing
per-app allow/deny/redirect rule matching actually use per-app attribution on the
VPN_TUN tier (which today can only filter by the VPN's own `addAllowedApplication`
API, not observe which app owns an already-captured flow). (source: `netbare.md`)

### 2.6 Automatic pinning-failure whitelist/fallback

`NetBare-Android`'s `SSLWhiteList` mechanism: when a handshake with a MITM'd
connection fails (pinning rejection), automatically add that host to a
tunnel-without-MITM whitelist for the rest of the session instead of repeatedly
retrying and failing. Complements — does not replace — the real pinning-bypass work
in §1.3. (source: `netbare.md`)

### 2.7 Binary/non-UTF-8 body detection before storing/displaying a body

Port `LoggingInterceptor`'s `isProbablyUtf8()` check, applied at the same point as the
gzip fix in §1.4, to avoid corrupting/bloating storage with force-UTF8-decoded binary
payloads (images, protobufs) that should instead be flagged as binary and hex-viewed.
(source: `loggerinterceptor-ihsanbal.md`)

### 2.8 Per-saved-request mock mode in ApiTestr

`LoggingInterceptor`'s `BufferListener`/`enableMock()` pattern applied to ApiTestr
specifically: add mock fields to `ApiRequestItem` so `ApiTestrRepository.executeRequest()`
can return a saved canned response for local UI dev/demos without a live backend,
instead of always dialing out. Distinct from the live-traffic `MOCK_RESPONSE` rule
action in §1.5 — this is scoped to the manual API-testing tool.
(source: `loggerinterceptor-ihsanbal.md`)

### 2.9 Real multipart/form-data body construction for ApiTestr

Confirmed by grep: `RequestBodyType.MULTIPART` and `ApiRequestItem.formData` are
declared but referenced nowhere in `ApiTestrRepository.kt`/`ApiTestrScreen.kt` — today
selecting MULTIPART silently sends the raw `bodyContent` string with a null media
type. Two repos converge on the same fix shape: `easy-api`'s `PostmanFormParam`
(key/value/type/src) + `CurlFormatter`'s multipart branch, and
`RotBolt/PostmanAndroidClient`'s multipart body-construction pattern. Implement using
OkHttp's `MultipartBody` in `ApiTestrRepository.executeRequest`, and wire the UI to
populate `formData`. (sources: `easy-api.md`, `postmanandroidclient.md`)

### 2.10 Auto Content-Type header on cURL/snippet export

`easy-api`'s `CurlFormatter.buildHeaders` always emits a `Content-Type` header derived
from the body type even when the user never typed one — requestr's cURL export omits
it today, producing commands many strict APIs reject. Small fix alongside §1.7 in the
same `generateCodeSnippet` cURL branch. (source: `easy-api.md`)

### 2.11 Real nested JSON path extraction for chain variables

Confirmed: requestr's current `JsonFieldExtractor` (used by `ChainExtractionRule`)
only matches top-level keys and self-documents as "not real JSONPath". `ReVoman`'s
`pm.response.json()` resolution shows the shape of a real nested-path extractor to
port in. (source: `revoman.md`)

### 2.12 Halt-on-failure execution control for the ApiTestr collection runner

Confirmed: `ApiCollectionsRepository.runCollectionTests` runs every step
unconditionally today. `ReVoman`'s execution-control model (halt-on-failure, skip
predicates) is a direct reference for adding the same to requestr's runner.
(source: `revoman.md`)

### 2.13 API Key auth placement toggle (Header vs Query Param)

`PostmanAndroidClient` lets the user choose whether an API-key auth value is sent as a
header or a query parameter. requestr's `AuthType.API_KEY` handling should get the
same toggle. (source: `postmanandroidclient.md`)

### 2.14 Local request mocking + per-URL AES body crypto (live MITM path)

`proxypin` supports (a) answering a matched request locally without ever contacting
the real server, and (b) per-URL AES encrypt/decrypt of a body via
`RequestCryptoManager`/`CryptoRule` for APIs that wrap their JSON payloads in
app-specific encryption. Neither exists in requestr's `RuleEnforcer` today; (a)
overlaps conceptually with the `MOCK_RESPONSE` action in §1.5 (same underlying
short-circuit mechanism, applied at the live-MITM layer rather than the rule-action
enum) and (b) is a net-new capability with no requestr equivalent.
(source: `proxypin.md`)

### 2.15 Full HAR 1.2 export/import parity check + wildcard SAN on generated leaf certs

`proxypin`'s `har.dart` and requestr's own `TrafficExporter`/`HarImporter` should be
diffed field-by-field in a follow-up pass to confirm full HAR 1.2 parity (requestr's
own export was independently confirmed superior to most peers in this batch, so this
is a verification task, not an assumed gap). Separately, confirmed by direct code
read: requestr's `MitmProxyEngine.generateHostCert()` (~line 745) issues only a single
exact-hostname SAN, while `RoRoIntercept`'s `CertificateManager.generateServerCertificate()`
adds a wildcard SAN (`*.` + parent domain) plus an IP SAN when relevant — improving
HTTP/2 connection-coalescing compatibility across subdomains. (sources: `proxypin.md`,
`rorointercept.md`)

### 2.16 Live NanoHTTPD-style browser dashboard for `LocalApiServer`

Confirmed by grep: `LocalApiServer.kt` (748 lines) is JSON-only with no human-viewable
UI at all. `RoRoIntercept`'s `WebServerService.kt` (NanoHTTPD, 2s auto-refresh,
color-coded methods, in-browser rules CRUD, request/response detail view, served over
LAN) is a cleanly separable feature to mirror `LocalApiServer`'s existing routes
(`/api/captures`, `/api/flows`, `/api/rules`) as a browser-renderable dashboard,
requiring no changes to the existing capture/decrypt pipeline. (source: `rorointercept.md`)

### 2.17 Documented "trust my own app's build" NSC opt-in tip (downgraded from the source report's MODERATE — see Review Gate Log §4.1)

`reqable/android-certificate-trust` is a single-attribute Android manifest-merge
recipe (`networkSecurityConfig` + `<certificates src="user"/>`) that only helps when
the *target app's own developer* rebuilds their app with it — it does not give
requestr a way to inspect an arbitrary non-cooperating third-party app. Ship this as a
documentation/UI tip attached to `CertificateAuthority.buildInstallIntent()`
("if you're testing your own app, add this one line instead of installing a device
CA") rather than an engineering change. (source: `android-certificate-trust.md`)

### 2.18 Local SQLite database browser for a target app (new capability, not a gap closure)

`AndroidSnooper`'s `DatabaseReader.kt`/`DatabaseDataReader.kt`/`dbreader/model/*`
enumerate and page through an app's own SQLite tables. requestr has no local-storage
inspector for *any* app today, but already runs privileged root-shell commands on its
`ROOT_IPTABLES` tier (`CertificateAuthority.kt:391`, `su -c ...`). Reusing that
existing root-exec capability to pull and open a *target* app's databases read-only
would extend requestr's scope from "network only" to "network + local storage" on
rooted devices — a genuinely new capability, not a fix to an existing gap, so it is
scoped as MODERATE rather than HIGH. (source: `androidsnooper.md`)

### 2.19 `ipstack`-grade TCP/IP relay to replace `TunRelay`'s hand-rolled selector loop

Independent of the proxy-bridge fix in §1.1 Approach A, `tun2proxy`'s `ipstack` crate
is a more complete/robust userspace TCP/IP stack than requestr's hand-rolled
`java.nio.channels`-based `TunRelay`. If Approach A is adopted, this becomes largely
redundant (tun2proxy would replace `TunRelay` wholesale); listed separately in case the
team prefers to keep the JVM-only VPN tier and only harden it. (source: `tun2proxy.md`)

### 2.20 Virtual DNS and UDP-over-TCP gatewaying from tun2proxy

`tun2proxy --dns virtual` maps DNS answers to a virtual IP range so all resolution
stays observable/interceptable inside the tool itself, and its `udpgw` feature
(`src/udpgw.rs`) gateways UDP-over-TCP for proxies that can't carry raw UDP. Both are
useful complements if §1.1 Approach A is adopted; neither is standalone-valuable
without it. (source: `tun2proxy.md`)

### 2.21 API-error-message granularity for ApiTestr

`PostmanAndroidClient`'s `ErrorHelperImpl`/`ErrorMessage` maps `ConnectException`,
`SocketTimeoutException`, HTTP status codes, and unknown-host failures to specific
user-facing messages; `ApiTestrRepository.executeRequest()` catches all `IOException`
generically as "Network Failure" today. Note: the source repo's own companion
`ConnectivityChecker`/`OfflineException` pre-flight check is confirmed dead code in
the source repo itself (never thrown/called) — only the error-mapping half is a
legitimate reference. (source: `postmanandroidclient.md`)

---

## 3. LOW ADD

- **Content-type-gated body rendering** (image/video/binary vs text, instead of always
  dumping raw monospace) — `jhomlala/alice`'s `_BodyDataColumn`. (`alice.md`)
- **Display-time JSON pretty-printing with a safe fallback** for the transaction
  inspector — `alice`. (`alice.md`)
- **Large-body (>100KB) render gate** to avoid Compose UI jank on huge captured bodies
  — `alice`'s `_LargeTextBody`. (`alice.md`)
- **Multi-key sort** for the transaction list (`AliceCallsListSortOption` enum
  pattern). (`alice.md`)
- **Aggregate stats/dashboard screen** (`AliceStatsPage` pattern) summarizing capture
  session totals. (`alice.md`)
- **Keyset-cursor pagination** for `CapturedTrafficDao.observeAll()`/`observeBySession()`
  (currently unbounded `SELECT * ... ORDER BY timestamp DESC` with no LIMIT/OFFSET) —
  `AndroidSnooper`'s `findAllSortByDateAfter` pattern. (`androidsnooper.md`)
- **No-op module swap for the `sdk` module** (`Snooper-Okhttp-No-Op`-style same-named
  empty-body counterpart module) so embedding apps can strip interceptor code from
  production APKs at the build-variant level instead of only runtime-gating it.
  (`androidsnooper.md`)
- **Chaos/fault injection rule action** (latency + probabilistic error injection) —
  `Flocon`'s `BadQuality.kt`, as a new `RuleEnforcer` action. (`flocon.md`)
- **Automatic image-response gallery** aggregating captured image responses —
  `Flocon`'s `NetworkImageRepository`. (`flocon.md`)
- **Classloader-based feature-detection guard** before installing any hook, so the new
  Xposed/Frida capture tier (§1.3) degrades gracefully across OkHttp versions —
  `Familyye/HttpInterceptor`. (`httpinterceptor-familyye.md`)
- **Wire-protocol-agnostic capture via post-reassembly `Response`/`ResponseBody`** and
  a **pre-arm sticky buffer** for requests captured before the hook fully installs —
  both from `Familyye/HttpInterceptor`, supporting details for the §1.3 tier.
  (`httpinterceptor-familyye.md`)
- **Reflection-only, no-compile-time-dependency technique** for the Frida payload in
  §1.3, avoiding a hard OkHttp version coupling in the injected script —
  `siyujie/OkHttpLoggerInterceptor-hook`'s `OkCompat.java`. (`okhttploggerinterceptor-hook.md`)
- **File-backed mock response body** — let a `MODIFY_RESPONSE`/`MODIFY_REQUEST`
  rule's `replacementValue` reference a local file path instead of requiring an inline
  JSON string, for large/binary fixtures — `ladeiko/HTTPInterceptor`.
  (`httpinterceptor-ladeiko.md`)
- **INTERNET-permission pre-filter** for the VPN app-picker list (fewer irrelevant
  apps shown) — `reqable/installed_apps`. (`installed-apps.md`)
- **Launcher-activity label fallback** for mislabelled apps in the app-picker —
  `reqable/installed_apps`. (`installed-apps.md`)
- **Bounded/expiring cert cache** for `MitmProxyEngine.certCache` (currently an
  unbounded `ConcurrentHashMap` with no eviction) — corroborated by two independent
  repos: `NetBare-Android`'s `SSLEngineFactory` cache and `RoRoIntercept`'s
  `LinkedHashMap`-based LRU cache capped at 200 entries. (`netbare.md`, `rorointercept.md`)
- **CA `.p12` export-and-import round trip** — `proxypin`'s
  `CertificateManager.importPkcs12()`. (`proxypin.md`)
- **Weak-network simulation** (latency/jitter/packet-loss injection) —
  `proxypin`'s `network_condition.dart`, overlapping in spirit with Flocon's
  chaos-injection item above; pick one reference implementation. (`proxypin.md`)
- **`{{var}}` environment templating for the live rewrite-rule engine** — distinct
  from ApiTestr's own already-working `{{VAR}}` substitution (confirmed present,
  `easy-api.md`); `proxypin`'s `EnvironmentManager` pattern applied to
  `RuleEnforcer`/live-MITM rewrite rules specifically, which have no templating today.
  (`proxypin.md`)
- **Copy-as-cURL / paste-a-cURL-to-populate-a-request** for the live MITM transaction
  view (ApiTestr already has both directions; this extends the same convenience to
  captured live traffic) — `proxypin`. (`proxypin.md`)
- **Advanced repeat/replay dialog** (repeat count + jittered interval) for replaying a
  captured request — `proxypin`'s `CustomRepeatDialog`. (`proxypin.md`)
- **Richer dynamic/fake variables** for ApiTestr (~20 built-in generators vs
  requestr's current 3 hardcoded ones) — `ReVoman`. (`revoman.md`)
- **Async polling for in-progress operations** in ApiTestr (only a manual retry button
  exists today) — `ReVoman`'s `PollingConfig`. (`revoman.md`)
- **Per-CONNECT CA-trust re-check with graceful tunnel-without-MITM fallback** —
  `RoRoIntercept`'s `handleConnect()` re-checks `isCertificateInstalled()` on every
  CONNECT and degrades to raw relay if the CA is no longer trusted, vs requestr's
  `PreflightController` which only gates once at session start. (`rorointercept.md`)
- **Wildcard SAN on generated leaf certificates** — see §2.15 (`rorointercept.md`),
  listed here for completeness of the LOW-ADD ledger.
- **SOCKS4/4a/5/5h + HTTP proxy auth (basic + digest) client support** — comes along
  "for free" with `tun2proxy` if §1.1 Approach A is adopted. (`tun2proxy.md`)
- **Basic Auth password show/hide toggle** in ApiTestr's `AuthSettingsTab` (currently
  always plaintext, zero `visualTransformation`) — `PostmanAndroidClient`.
  (`postmanandroidclient.md`)

---

## 4. Review Gate Log — downgrades and rejections applied by the orchestrator

Per the tracker's review-gate procedure, every `ADD` verdict from all 19 reports was
cross-checked against requestr's confirmed architecture before inclusion above. Items
changed from their source report's original verdict:

1. **`android-certificate-trust`'s "trust my own app's build" recipe** — the source
   report scored this `MODERATE ADD`. Downgraded to a **documentation-only LOW/MODERATE
   item** (§2.17) because it is a single manifest attribute + doc tip, not an
   engineering change, and it explicitly does NOT solve the "arbitrary non-cooperating
   third-party app" framing of Gap #4 that motivated flagging this repo as
   high-priority in the first place. This was the repo the user specifically asked to
   have "precisely determined" for exactly this distinction — the honest answer is
   that it only helps a developer testing their *own* app.
2. **Merged duplicate recommendations** rather than listing them twice:
   - Postman import/export: `easy-api` (HIGH) + `ReVoman` (MODERATE, environment-import
     angle) → merged into §1.6.
   - Multipart body construction: `easy-api` (MODERATE) + `PostmanAndroidClient`
     (LOW-MODERATE) → merged into §2.9.
   - Bounded/expiring MITM cert cache: `NetBare-Android` (LOW) + `RoRoIntercept` (LOW)
     → merged into one LOW item in §3.
   - CA-free per-app capture tier / pinning-bypass fix: `Familyye/HttpInterceptor`
     (2×HIGH) + `OkHttpLoggerInterceptor-hook` (HIGH) → merged into §1.3 as one item
     with two corroborating source implementations.
   - Real HTTP/2 parsing on the MITM leg: `NetBare-Android` (HIGH) + `proxypin` (HIGH)
     → merged into §1.2.
3. **`proxypin`'s `{{var}}` templating claim** was checked against `easy-api.md`'s
   explicit confirmation that ApiTestr's `{{VAR}}` substitution already works — this
   is NOT a contradiction: `proxypin`'s gap is in the *live rewrite-rule engine*
   (`RuleEnforcer`), a different subsystem from ApiTestr's manual request builder.
   Re-scoped explicitly in §3 to avoid a false "already exists" rejection.
4. **`installed-apps`'s Dart-API staleness bug** (its own `excludeSystemApps`/
   `withIcon`/`packageNamePrefix` params are documented but silently ignored by the
   native handler) was explicitly excluded — it's a bug in the *source* repo, not
   something to import into requestr.
5. **No item was rejected outright as "vague/stub"** — every research pass was
   instructed to cite concrete files/classes on both sides, and spot-checking against
   the underlying reports confirmed all `ADD` verdicts above meet that bar. The
   `IGNORE` ledger below lists everything that did NOT qualify, with the reason each
   subagent gave, confirmed by the orchestrator as non-actionable or already-superior.

---

## 5. IGNORE ledger — full traceability of what did NOT qualify, and why

Listed for completeness so no repo's audit is "lost". Two repos produced zero
actionable items:

- **`MujurID/Charles-Hack-Crack-Keygen`** — confirmed to contain only a license
  keygen (RC5 cipher) and a bytecode patcher targeting Charles's own obfuscated
  classes; zero TLS/CA/proxy/MITM source code exists anywhere in the repo.
- **`parkingwang/okhttp3-loginterceptor`** — a dormant (2017), 392-line logging
  library; every feature it offers (JSON pretty-print, request/response ID
  correlation) is already matched or exceeded by requestr's existing
  `ToolsRepository.formatJson()`, structured `RecordedOkHttpCall` model, and
  richer per-phase timing breakdown.

Per-repo notable `IGNORE`s from the remaining 17 (full detail in each
`/tmp/research-reports/<slug>.md`):

- **chucker** — architecturally identical pattern to requestr's own
  `OkHttpProxyInterceptor` (own-process only, no TLS/VPN/CA code whatsoever, confirmed
  in the prior deep audit). Nothing new to add; already the subject of the dedicated
  comparison report pushed to `docs/report/HTTPS-INTERCEPTION-ENGINE-REPORT.md`.
- **alice** — TLS/MITM/VPN/CA/pinning/QUIC/JA3: not applicable, Flutter client-side
  interceptor only. Export/HAR: requestr's existing exporters already superior.
  Shake-to-open UI, ObjectBox storage, Dio/Chopper client adapters: no host-app
  integration point in a standalone app like requestr.
- **AndroidSnooper** — core interceptor/storage/formatter/share features all already
  matched or exceeded by requestr's `sdk` module and `UniversalSyntaxHighlighter`;
  shake-gesture trigger has no integration point in a standalone app.
- **Flocon** — pinning bypass, non-root trust, JA3, QUIC, plugin architecture, ADB
  desktop-companion transport, DB/SharedPrefs/deeplink explorer, JSON-tree diff view,
  request-mocking engine: either not applicable to Flocon's opt-in-SDK model, or
  requestr already has an equivalent/superior feature.
- **Familyye/HttpInterceptor** — operates entirely above OkHttp's already-negotiated
  connection (matches the same architectural limitation as chucker); its
  `adb shell am broadcast` control plane and JA3/QUIC gaps: not relevant, source repo
  doesn't reach that layer.
- **NetBare-Android** — CA install flow already equivalent to requestr's `KeyChain`
  approach; IPv6 support strictly worse (IPv4-only); MTU handling unsophisticated;
  QUIC/HTTP-3 requestr already ahead (hand-rolled QUIC stack); threading/pooling
  model already comparable; JA3/CDP/rule-engine/ApiTestr out of scope for a capture
  library.
- **OkHttpLoggerInterceptor-hook** — static header/query injection feature and Xposed
  bootstrap boilerplate: no plausible requestr use case.
- **RoRoIntercept** — no VPN tier at all (nothing to donate a wiring pattern from);
  HTTP/1.x-only with zero HTTP/2 (requestr's own parser already ahead here); zero
  frida/xposed/pinning code; no JA3 code; no keylog/SSLKEYLOGFILE concept at all;
  export limited to the CA cert only (requestr's exporters already far ahead).
- **proxypin** — does NOT solve the Flutter/BoringSSL trust gap either (points users
  to a separate Magisk module, functionally identical to requestr's own
  `installToSystem()`); byte-exact issuer DN encoding already present in requestr;
  cross-platform engine/UI separation not a portable pattern for a single-platform app.
- **easy-api** — its Gradle/IDE-plugin source-code-scanning mechanism does not
  transfer to requestr (there is no source to scan in a manual API tester); only the
  Postman/OpenAPI schema *shapes* transfer, captured in §1.6.
- **PostmanAndroidClient** — collections/folders, environment/variable substitution,
  request history+replay, response-viewer ergonomics, Postman JSON/cURL/env-file
  import-export, cookie jar, and pre/post-response scripting: requestr already has
  equivalents (several confirmed superior); OAuth2 token management is a real gap but
  not sourced from this specific repo, so not claimed as an ADD from it (flagged as an
  aside, not a verdict).
- **ReVoman** — is a design donor only; the library itself (JDK 25, GraalJS/Truffle,
  http4k, Spring) is explicitly not embeddable in an Android app.
- **HTTPInterceptor (ladeiko)** — a tiny, years-old, single-process iOS interceptor;
  its headline mechanism (request/response modification) turned out to already be a
  solved, tested, cross-engine feature in requestr (`RuleEnforcer.applyRequestModification`/
  `applyResponseModification`, tested in `CdpFetchAdapterTest.kt`), not an unimplemented
  gap as the user's original note suspected.
- **LoggingInterceptor (ihsanbal)** — its Logcat-batching (`BatchingSink`/`LogSink`)
  and box-drawing/`enableAndroidStudioV3LogsHack` formatting exist purely to solve
  Logcat line-interleaving, a problem requestr's Compose-based transaction UI (with
  syntax highlighting and a hex viewer) doesn't have.
- **okhttp-client-mock** — corrected a stale claim from the prior audit: requestr
  actually has 90+ test files already; the real, narrower gap is specifically
  `ApiTestrRepository`/`OkHttpProxyInterceptor` having zero coverage (captured in §1.9).
- **tun2proxy** — reqable's public fork has zero diff from upstream `tun2proxy/tun2proxy`
  (one release behind, zero "reqable" string occurrences); the real reqable-specific
  engineering lives in their closed-source app, not this public repo, so no
  fork-specific feature could be claimed.
- **installed_apps (reqable)** — no caching/memoization exists anywhere in the plugin
  to adopt; requestr's existing app-picker is already more disciplined in several ways
  (sorted list, consistent system-app flag, lazy icon loading).
- **android-certificate-trust (reqable)** — see the Review Gate Log §4 item 1 above;
  downgraded rather than ignored, but the "arbitrary third-party app without
  cooperation" capability the user asked to verify is confirmed absent.

---

## 6. Suggested phasing (not a commitment, a starting point for triage)

**Phase 1 — data-correctness and low-risk fixes (do first, small/contained diffs):**
§1.4 (gzip decompression), §1.7 (cURL shell-escaping), §2.7 (binary-body detection),
§2.10 (auto Content-Type on export), §3's bounded cert cache, §2.15's wildcard SAN.

**Phase 2 — rule-engine and mock capabilities:**
§1.5 (`MOCK_RESPONSE` action), §2.1 (richer rule model), §2.8 (ApiTestr mock mode),
§2.14 (local mocking + per-URL crypto on the live MITM path).

**Phase 3 — ApiTestr completeness:**
§1.6 (Postman import/export), §1.8 (pre/post-response scripts), §2.9 (multipart),
§2.11 (real JSON path extraction), §2.12 (halt-on-failure), §2.13 (API key
placement), §2.21 (error granularity), §3's ApiTestr LOW items.

**Phase 4 — the two hard architectural fixes (highest effort, highest payoff):**
§1.1 (VPN/MITM disconnect — pick Approach A or B), §1.2 (real HTTP/2/WebSocket
parsing on the MITM leg). Everything in §2.19/§2.20 (tun2proxy DNS/UDP features) is
downstream of choosing §1.1 Approach A.

**Phase 5 — new capture tier and cert hardening:**
§1.3 (CA-free per-app capture tier via Frida/Xposed), §1.10 (remote-cert mirroring),
§2.5 (non-root UID attribution), §2.6 (pinning-failure whitelist).

**Phase 6 — sdk module expansion and UI/UX polish:**
§2.2/§2.3 (gRPC/WebSocket capture in `sdk`), §2.4 (bulk export endpoint), §2.16
(browser dashboard), §2.18 (local DB browser), all remaining §3 LOW items.

---

## 7. Appendix — full source repo list

| # | Repo | One-line outcome |
|---|---|---|
| 0 | [ChuckerTeam/chucker](https://github.com/ChuckerTeam/chucker) | already deeply audited in a prior phase; own-process interceptor, no new adds |
| 1 | [jhomlala/alice](https://github.com/jhomlala/alice) | Flutter-only client logger; 5 UI-polish LOW/MODERATE adds |
| 2 | [jainsahab/AndroidSnooper](https://github.com/jainsahab/AndroidSnooper) | in-app interceptor, no MITM; pagination + no-op-module + DB-browser adds |
| 3 | [openflocon/Flocon](https://github.com/openflocon/Flocon) | opt-in KMP SDK; gRPC/WebSocket/export/chaos/gallery adds |
| 4 | [Familyye/HttpInterceptor](https://github.com/Familyye/HttpInterceptor) | working LSPosed OkHttp-hook capture tier; feeds the new §1.3 capture tier |
| 5 | [MegatronKing/NetBare-Android](https://github.com/MegatronKing/NetBare-Android) | working TLS-in-TUN wiring pattern; feeds §1.1/§1.2 |
| 6 | [parkingwang/okhttp3-loginterceptor](https://github.com/parkingwang/okhttp3-loginterceptor) | IGNORE — fully superseded already |
| 7 | [siyujie/OkHttpLoggerInterceptor-hook](https://github.com/siyujie/OkHttpLoggerInterceptor-hook) | Xposed hook confirms the §1.3 pinning-immune mechanism |
| 8 | [equisde/RoRoIntercept](https://github.com/equisde/RoRoIntercept) | smaller than user's framing suggested; rule model + web dashboard genuinely useful |
| 9 | [wanghongenpin/proxypin](https://github.com/wanghongenpin/proxypin) | Flutter trust gap unsolved; real HTTP/2/WS parsing + JS rewrite are the big adds |
| 10 | [MujurID/Charles-Hack-Crack-Keygen](https://github.com/MujurID/Charles-Hack-Crack-Keygen) | IGNORE — license keygen/patcher only, no proxy source |
| 11 | [tangcent/easy-api](https://github.com/tangcent/easy-api) | Postman schema + cURL escaping fix, both HIGH ADD |
| 12 | [RotBolt/PostmanAndroidClient](https://github.com/RotBolt/PostmanAndroidClient) | mostly false gaps; 4 small confirmed adds |
| 13 | [salesforce-misc/ReVoman](https://github.com/salesforce-misc/ReVoman) | not embeddable; design donor for scripting/chaining/polling |
| 14 | [ladeiko/HTTPInterceptor](https://github.com/ladeiko/HTTPInterceptor) | tiny iOS repo; confirmed requestr's modify-rules already solved this |
| 15 | [ihsanbal/LoggingInterceptor](https://github.com/ihsanbal/LoggingInterceptor) | gzip decode + MOCK_RESPONSE, both HIGH ADD |
| 16 | [gmazzo/okhttp-client-mock](https://github.com/gmazzo/okhttp-client-mock) | test-only lib closing a real zero-coverage gap |
| 17 | [reqable/tun2proxy](https://github.com/reqable/tun2proxy) | Rust CONNECT-bridge is the most promising fix for the VPN/MITM disconnect |
| 18 | [reqable/installed_apps](https://github.com/reqable/installed_apps) | narrow utility; 2 small LOW adds |
| 19 | [reqable/android-certificate-trust](https://github.com/reqable/android-certificate-trust) | debug-only manifest trick; does not solve arbitrary-third-party-app trust |

---

*This document was produced by reading actual source code in every referenced repo
(not READMEs/marketing copy) and cross-checking every claim against requestr's real
files/line numbers before inclusion. No stub, placeholder, or unverified claim was
knowingly included. Full per-repo detail, including every rejected/`IGNORE`d
sub-feature, remains available in the individual research reports referenced
throughout this document for anyone who wants to audit a specific verdict further.*
