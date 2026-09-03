# Master Plan — Implementation Status

> **Status: COMPLETE**, with exactly one item marked **🚫 not possible in emulator**.
> Every HIGH, MODERATE and LOW item is now implemented, wired to a real user-facing
> surface, and covered by passing unit tests. The three previously-deferred "implemented
> but unwired" items (§1.10, §2.18, `.p12` import) are wired. §2.14(b) and the whole §3 LOW
> polish batch are built. See [Emulator-blocked items](#emulator-blocked-items) for the single
> capability that cannot be verified or exercised on this Redroid sandbox, and
> [Remaining known issues](#remaining-known-issues) for the honest residue.

This document records what shipped, where the code lives, and what remains. It is the
hand-off artifact for the work spanning PR #15 (and the earlier Phase 1-3 / runtime
instrumentation phases that this buildout completes), updated by the master-plan-completion
pass on 2026-08-31.

---

## Delivery map (master plan § → shipped code)

Legend: ✅ fully implemented & wired · 🧪 implemented + tested, not wired · ⚠️ partial · ❌ not built · 🚫 not possible in emulator

### HIGH ADD (§1)

| § | Item | State | Where |
|---|------|-------|-------|
| 1.1 | VPN/MITM disconnect (Approach A: tun2proxy CONNECT bridge) | ✅ code · 🚫 unverifiable here | `native/tun2proxy-bridge/` (Rust crate), `app/src/main/java/com/example/interception/tun/{NativeTun2Proxy,Tun2ProxyBridge,Tun2ProxyConfig}.kt`. Code complete + 34 Rust unit tests green, but the tunnel **cannot be established on Redroid** — see [Emulator-blocked items](#emulator-blocked-items) |
| 1.2 | Real HTTP/2 (HPACK) + WebSocket parsing on the MITM leg | ✅ | `app/src/main/java/com/example/interception/http/{Http2Parser,Http2Session,WebSocketParser}.kt` |
| 1.3 | CA-free per-app capture tier via Frida OkHttp hook | ✅ | `app/src/main/java/com/example/runtime/frida/{FridaOkHttpAdapter,FridaRuntimeWiring,OkHttpBuilderHookScript}.kt`, `FridaClient.kt` armed-gate |
| 1.4 | gzip decompression (Gap #10) | ✅ | `app/src/main/java/com/example/interception/http/HttpParser.kt` + `HttpBodyDecoder` (Phase 1-3) |
| 1.5 | `MOCK_RESPONSE` rule action (Gap #11) | ✅ | `app/src/main/java/com/example/interception/{MockFixtureLoader,CaptureEngine}.kt` (`RuleAction.MOCK_RESPONSE` branch) |
| 1.6 | Postman Collection v2.1 import/export | ✅ | `app/src/main/java/com/example/data/export/PostmanCollectionCodec.kt` (Phase 1-3) |
| 1.7 | cURL shell-escaping fix | ✅ | `app/src/main/java/com/example/data/export/CodeExporter.kt` `escapeShell()` (Phase 1-3) |
| 1.8 | Executable pre/post-response scripts | ✅ | `app/src/main/java/com/example/scripting/{JsScriptEngine,ApiTestrScriptRunner,PmPrelude,PmBridgeBase,PmTestResult,DynamicVariables}.kt` |
| 1.9 | OkHttp-mock unit tests | ✅ | `app/build.gradle.kts` (`mockwebserver`), `ApiTestrRepositoryFormBodyTest`, `ApiTestrRepositoryApiKeyLocationTest` |
| 1.10 | Remote-cert-mirroring leaf regeneration | ✅ | `CertificateAuthority.issueMirroredLeaf` + `RemoteCertificateMirror`, now wired as a **discrete opt-in**: `SettingsDataStore.remoteCertMirrorEnabled` (key `remote_cert_mirror_enabled`), read at `MitmProxyEngine.kt:275`, toggled from the "REMOTE CERT MIRRORING (EXPERT)" section in `SettingsScreen.kt`. Off by default so §1.5's "a mock never dials upstream" guarantee still holds |
| 1.11 | Embedded JS scripting for live MITM rewriting | ✅ | `RuleEnforcer` script-rewrite action + `CaptureEngine` `SCRIPT_REWRITE` branch (subsumes §2.1 richer matcher) |

### MODERATE ADD (§2)

| § | Item | State | Where |
|---|------|-------|-------|
| 2.1 | Richer rule model | ✅ (via §1.11) | Script-rewrite engine subsumes the AND/NOT/regex matcher per plan note |
| 2.2 | gRPC capture `ClientInterceptor` (sdk) | ✅ | `sdk/src/main/java/com/requestr/sdk/GrpcInterceptor.kt` |
| 2.3 | WebSocket decorator (sdk) | ✅ | `sdk/src/main/java/com/requestr/sdk/WebSocketInterceptor.kt` |
| 2.4 | Bulk time-range export | ✅ | `app/src/main/java/com/example/data/local/Daos.kt` (`getSessionRangePageBeforeCursor`, `getRangePageBeforeCursor`, `countInSessionRange`) + `LocalApiServer.exportCaptures` |
| 2.5 | Non-root UID attribution | ✅ | `app/src/main/java/com/example/interception/ProcNetUidResolver.kt` |
| 2.6 | Pinning-failure whitelist/fallback | ✅ | `ConnectControl.kt` (`PinningFailureRegistry.recordFailure`/`shouldBypass`, `Disposition.AllowOpaque`) |
| 2.7 | Binary/non-UTF-8 body detection | ✅ | `HttpParser.kt` + `HttpBodyDecoder.isProbablyUtf8/isBinary` (Phase 1-3) |
| 2.8 | Per-saved-request mock mode | ✅ | `ApiTestrScreen.kt` mock editor, `Entities.kt` `mockEnabled` |
| 2.9 | Real multipart/form-data | ✅ | `ApiTestrScreen.kt` formData editor + `ApiTestrRepository` `FormBody`/`MultipartBody` |
| 2.10 | Auto Content-Type on export | ✅ | `ApiTestrRepository.generateCodeSnippet`/`exportHeaders` |
| 2.11 | Nested JSON path extraction | ✅ | `app/src/main/java/com/example/flow/JsonFieldExtractor.kt` `extractNested` (Phase 1-3) |
| 2.12 | Halt-on-failure | ✅ | `AppRepositories.kt` `runCollectionTests(haltOnFailure=...)` |
| 2.13 | API-key placement (Header vs Query) | ✅ | `ApiTestrRepository.kt` `ApiKeyLocation` branch |
| 2.14(a) | Local live-MITM mocking | ✅ | `MockFixtureLoader` + `MOCK_RESPONSE` short-circuit (never dials upstream) |
| 2.14(b) | Per-URL AES body crypto | ✅ | `interception/RequestCryptoManager.kt` (AES/GCM/NoPadding, IV prepended to ciphertext, keys from AndroidKeyStore via an injectable `keyProvider`), new `RuleAction.BODY_ENCRYPT_AES`/`BODY_DECRYPT_AES` (`Entities.kt:169`), new `InterceptRuleEntity` fields `cryptoKeyAlias`/`cryptoAlgorithm`/`cryptoDirection`/`cryptoScope`, applied in `RuleEnforcer.applyBodyCrypto`, edited from `RulesConfigBottomSheet` in `InterceptScreen.kt`. A blank key alias on a crypto rule is a **surfaced configuration error** (Save is blocked), never a silent skip |
| 2.15 | Wildcard SAN + HAR parity | ✅ | `HostSanGenerator` + `ExpiringLruCertCache` (LRU cap + TTL) |
| 2.16 | Browser dashboard for `LocalApiServer` | ✅ | `app/src/main/java/com/example/api/LanDashboardServer.kt` (bearer-auth, no wildcard CORS, localhost default + LAN opt-in) |
| 2.17 | NSC "trust own build" tip | ⚠️ | Doc/surface only — deliberately. See [Remaining known issues](#remaining-known-issues) #1 for why the app's own `network_security_config.xml` is **not** loosened, and what replaced the old `Fix #31` TODO |
| 2.18 | Local SQLite DB browser | ✅ | `TargetAppDatabaseBrowser.kt` now surfaced as the "TARGET APP DATABASE BROWSER" section in `SettingsScreen.kt`, switching on the existing `CapabilityProbe`/`InterceptionStateRepository` root `StateFlow` (no new probe). All five root states (`GRANTED`/`AVAILABLE`/`DENIED`/`UNAVAILABLE`/`UNKNOWN`) render distinctly; `GRANTED` gets a full app → database → table picker paged via `LIMIT/OFFSET` guarded by `isSafeSqlIdentifier`, with `DisposableEffect` cleanup of cached DB copies. On this emulator the live state is `UNAVAILABLE` (the app's uid cannot exec `/system/xbin/su`), so only the non-`GRANTED` branches are exercisable here |
| 2.19 | `ipstack` relay | ✅ | via §1.1 tun2proxy bridge |
| 2.20 | Virtual DNS + UDP-over-TCP | ✅ | `native/tun2proxy-bridge/src/config.rs` (`WireDnsMode::Virtual`, `WireUdpGw`, `udpgw` feature) |
| 2.21 | API-error-message granularity | ✅ | `ApiTestrRepository.kt` `MappedError` (UnknownHost/Connect/SocketTimeout) |

### LOW ADD (§3) — now complete

Previously shipped: keyset-cursor pagination (`Daos.kt`), `sdk-noop` module, `ChaosInjector`,
bounded `ExpiringLruCertCache`, wildcard SAN, async polling (same-origin guard + credential
strip), richer dynamic variables, SOCKS4/4a/5/5h (via tun2proxy), `.p12` **export**.

`.p12` **import with password** — ✅ wired: `P12ImportPasswordDialog` in `SettingsScreen.kt`
(masked field + show/hide, Import/Cancel, inline error) calling `CaP12Exchange.importP12`, with a
new `data/repository/P12ImportErrorClassifier.kt` distinguishing wrong-password vs corrupt-file vs
unsupported-content vs persist-failure. Success bumps the CA refresh trigger and re-probes, so the
CA state visibly flips.

The thirteen previously-unbuilt items (referred to as C1–C13 in the working notes):

| # | §3 item | State | Where |
|---|---------|-------|-------|
| C1 | Image gallery for captured image responses | ✅ | new `ui/screens/ImageGalleryScreen.kt` — grid + tap-to-preview, decodes the real stored bytes via `CapturedTrafficDao.getById`, routes back to the owning transaction. Reachable from the "Capture setup" sheet |
| C2 | Weak-network simulation (latency/jitter/loss) | ✅ | new `interception/NetworkConditionInjector.kt` (pure, injectable RNG + `sleep`), persisted via `SettingsDataStore.networkCondition`, applied live in `HttpProxyShared.forwardRequest`/`handleConnectAttempt`, `LazyUpstreamConnection.connect`, and therefore in `HttpProxyEngine`, `MitmProxyEngine` and `gateway/GatewayEngine`. Controls in `SettingsScreen.kt`. Loss is modeled as a failed connection attempt, not silent byte-dropping — see that file's KDoc for why that is the only faithful model above the OS TCP stack |
| C3 | Large-body render gate | ✅ | pre-existing size gating in `CaptureBodySection`, now meaningful because binary bytes are actually persisted (2 MiB cap, `HttpBodyDecoder.MAX_STORED_BODY_BYTES`) |
| C4 | Content-type-gated / binary body rendering | ✅ | `HttpBodyDecoder.forStorage` + new `CapturedTrafficEntity` BLOB columns; the traffic detail Body tab renders three distinct states (image / hex dump with "Copy as Base64" + Export-Share / exceeded-cap explanation). This also fixed a real bug where binary bodies rendered as "(Empty)" |
| C5 | Aggregate stats | ✅ | new `ui/screens/StatsDashboardScreen.kt` rendering the existing pure `computeCaptureStats` (totals, error count, latency min/avg/max, average size, status-code distribution, method mix, top hosts) |
| C6 | Multi-key sort | ✅ | new `SortOptionsBottomSheet` (pick/reorder/direction across 5 keys) applied through the existing pure `sortCaptures`, with a live entry chip (e.g. "Time↓") and a new pure `describeSortSpecs` + tests |
| C7 | Repeat/replay dialog | ✅ | new `RepeatReplayDialog` from `CapturedDetailBottomSheet` — resends via `ApiTestrRepository.executeRequest` on jittered timing from the existing pure `computeReplaySchedule`, cancellable mid-run via a real `Job`, off the UI thread, per-attempt status/latency/error list. Distinct from the pre-existing CONNECT-tunnel replay, which only replays TCP/TLS handshakes |
| C8 | INTERNET-permission app prefilter | ✅ | `TrafficInterceptorRepository.internetOnlyFilterEnabled`/`totalInstalledAppCount`/`filteredOutAppCount` surfaced in both `AppFilterBottomSheet` (`InterceptScreen.kt`) and a "VPN APP PICKER" section in `SettingsScreen.kt`, with "Showing N of M apps (X hidden)" and a split empty state (still-loading vs filtered-to-nothing, with one-tap "Show all apps") |
| C9 | Launcher-label fallback | ✅ | `resolveAppDisplayLabel` in new `data/repository/AppUtils.kt` + `AppModels` fallback chain, unit-tested |
| C10 | `{{var}}` live-rule templating | ✅ | new `interception/RuleTemplateExpander.kt` — escaped `\{{literal}}`, `{{$guid}}`-style dynamic generators via the shared `DynamicVariables` table, `{{request.host/path/method/url/header.NAME/query.NAME}}` built-ins, legacy `{{TIMESTAMP}}`/`{{RANDOM}}`/`{{UUID}}`, then the rule's own `"variables"` map; **unknown tokens are left intact** so a typo is visibly diagnosable. Applied by `RuleEnforcer` to `addHeaders` values and `bodyReplace` for `MODIFY_REQUEST`/`MODIFY_RESPONSE`/`MOCK_RESPONSE`, reusing the exact `"variables"` config key `SCRIPT_REWRITE` already accepted rather than inventing a parallel variable store |
| C11 | Copy/paste cURL for live traffic | ✅ | new `data/export/CurlParser.kt` wired into the existing "Paste cURL" action, hardened against the quoting/continuation cases the old inline parser mishandled |
| C12 | Per-CONNECT CA re-check | ✅ | new `interception/CaTrustGate.kt` (`isTrusted()` with a cached probe + `invalidate()`), consulted per CONNECT by `MitmProxyEngine`; when the CA is untrusted the engine degrades to blind relay and records `disposition == "ALLOWED_OPAQUE"` with a non-null `failureReason`, which the UI now surfaces as an amber "CA NOT TRUSTED" badge plus an "Install requestr's CA" action in both the connections list and the detail sheet |
| C13 | Basic-Auth show/hide | ✅ | verified already correctly implemented in `ApiTestrScreen.kt`; no change needed |

---

## Bugs found and fixed during this completion pass

These were pre-existing defects discovered while wiring the above — several only became
visible once the features were exercised on a real device:

1. **Cleartext HTTP through the proxy tier was completely broken.** `HttpProxyShared.forwardRequest`
   dialed upstream with `URL(url).openConnection() as HttpURLConnection`, which **is** subject to
   Android's Network Security Config. Since the app sets `usesCleartextTraffic="false"` and a
   `base-config cleartextTrafficPermitted="false"`, every plain-HTTP request through the proxy died
   in ~1 ms with `502 X-Proxy-Error: Cleartext HTTP traffic to <host> not permitted`. Fixed by
   dialing the upstream leg over a raw `Socket` (or `SSLSocket` for `https://` targets), which is
   not governed by NSC — **without** loosening `network_security_config.xml`, preserving the
   correct distinction between "the app as an HTTP client" (must not use cleartext) and "the app as
   an interception proxy" (must relay cleartext to arbitrary hosts).
2. **`HTTP/1.1 502 OK`.** The proxy's status line hardcoded the reason phrase `OK`. Replaced with
   `HttpProxyShared.reasonPhraseFor(statusCode)`.
3. **Binary response bodies were never persisted at all.** `HttpBodyDecoder.decode()` correctly
   blanks binary bodies for text rendering, but the real bytes sat unused in
   `ParsedHttpMessage.rawBodyBytes` and nothing wrote them to storage — so `requestSizeBytes`/
   `responseSizeBytes` were `0` for binary bodies and C1/C3/C4 would have been structurally
   hollow. Fixed with Room BLOB columns + `HttpBodyDecoder.forStorage`, folded into the existing
   `MIGRATION_14_15` (schema `app/schemas/…/15.json`) rather than adding a second migration.
4. **`GatewayEngine` never called `HttpBodyDecoder`** (raw `String(bytes, UTF_8)`, no binary
   detection) and **`PacketPump`** had an independent `"<binary N bytes>"` text marker. Both now go
   through the same storage contract, so the UI needs no per-transport special cases.
5. **`MockFixtureLoader` read `file://` fixtures as UTF-8 text only**, corrupting any binary
   fixture. Now binary-safe, with a clear error (not an empty body) for a missing/unreadable file.
6. **Release build could not be produced at all.** `:app:minifyReleaseWithR8` failed with five
   missing `java.beans.*` classes referenced from Rhino's `JavaToJSONConverters`; `java.beans` does
   not exist on Android/ART and requestr never reaches that code path. Fixed with targeted
   `-dontwarn`/`-keep` rules in `app/proguard-rules.pro` (commit `ef84075`).

---

## Emulator-blocked items

The task brief allows exactly one disposition for a capability that a Redroid/emulator sandbox
genuinely cannot provide: mark it, explain it, skip only it. **One item qualifies.**

### 🚫 VPN / tun tunnel establishment — not possible in emulator

`RequestrVpnService.establish()` cannot create a tun interface on Redroid. The app surfaces this
as a clean `ERROR` state reading *"Capture failed to start: Cannot create interface"* — that is
correct, honest behaviour and **must not be "fixed"**. Evidence that this is environmental rather
than an app defect:

- The string `Cannot create interface` appears **nowhere** in this repository (`*.kt`, `*.java`,
  `*.rs`). It is the Android framework's own `IllegalStateException` from `Vpn.establish()`.
- `pm list features | grep -i vpn` returns **nothing** — the container advertises no VPN feature.
- `dumpsys vpn` → `Can't find service: vpn`.
- `ip -o link show` inside the container shows only `lo` and `eth0`; `tun0` is never created.
- Redroid has a uid-attribution bug that breaks the AppOps pre-consent check outright:
  `E AppOps: Bad call made by uid 10024. Package "com.kailashhh.requestr" does not belong to uid
  10024` → `SecurityException` at `Vpn.doesPackageHaveAppop` → `isVpnServicePreConsented` →
  `Vpn.prepare` → `VpnManagerService.prepareVpn`. The app's real uid is `10087`.
- Two misleading non-signals, recorded so nobody re-litigates this: `/dev/net/tun` **does** exist
  (`crw-rw-rw- root root 10, 200`), and the `com.android.vpndialogs/.ConfirmDialog` consent dialog
  **does** appear (twice). Neither means the tunnel came up.

Affected code (complete, unit-tested, unchanged): `interception/RequestrVpnService.kt` (notably
line 172's documented `UnsupportedOperationException` at establish time, line 191's
`builder.establish()`, and line 337's tear-down/re-establish on network change),
`interception/tun/**`, `interception/PacketPump.kt`'s tun path, and `native/tun2proxy-bridge/`
(whose 34 Rust unit tests do pass — the Rust relay logic is verified, only the Android-side tunnel
cannot be opened here).

**Everything downstream of the tunnel is still exercised**, because the **HTTP-proxy tier works
fully on this emulator** and shares the parsing/rules/persistence/UI stack: proxy capture reaches
`CAPTURING :9000`, `HttpProxy: Proxy listening on port 9000`, port 9000 confirmed listening in the
container netns, and a real HTTPS `CONNECT` to `httpbin.org` was captured and rendered.

### Related capabilities absent from this sandbox (feature code unaffected)

Not master-plan items in their own right, but they bound what can be *demonstrated* here. Each was
probed live, not assumed:

| Capability | Live probe result |
|---|---|
| Outbound internet / DNS / TCP | ✅ works — `INTERNET&VALIDATED`; a live API Testr call to `https://httpbin.org/get` returned `200 OK`, `173 ms`, `H2` |
| ICMP to public IPs | ❌ blocked by the sandbox egress filter (100 % loss even while TCP/UDP/DNS all work) — **never use ping-to-IP as a connectivity test here**; that false negative previously caused a wrong "no internet" diagnosis |
| App-level `su` (root tier) | ❌ `/system/xbin/su` is `-rwsr-x--- root shell`, so the app's uid cannot exec it. The live probe correctly reads `RootState.UNAVAILABLE` |
| Shizuku tier | ❌ `Shizuku: Not installed` |
| Frida tier | ❌ `Frida: Not running` |
| CA installed in the system store | ❌ `CA: Not installed` — which makes C12's untrusted/blind-relay branch the **default** path here, so that branch is well exercised |
| Physical sensors | ❌ Redroid |

---

## Historical: the PR #15 test-stabilisation changes (files → code)

Kept for the record. These are the changes made and committed in the earlier continuation session
(commit `d62ab56`, PR #15), specifically the fixes that took the suite from failing to green:

1. `native/tun2proxy-bridge/src/state.rs`
   - Added crate-wide `TEST_SERIALIZATION_LOCK` (`Mutex<()>`, `#[cfg(test)]`) and a
     poison-recovering `lock_test_state()` helper.
   - `mod tests`: removed the module-local `TEST_LOCK`; every `TEST_LOCK.lock().unwrap()`
     (11 sites) became `super::lock_test_state()`.
   - **Why:** `lib.rs`'s FFI tests mutate the same process-global statics **without** taking
     `state`'s lock, so parallel runs corrupted `wait_for_*` assertions and one panic poisoned
     the mutex, cascading `PoisonError` into the rest. Single shared lock + poison recovery
     made it deterministic.
2. `native/tun2proxy-bridge/src/lib.rs`
   - `mod tests`: removed local `TEST_LOCK`; 12 sites now use `crate::state::lock_test_state()`.
   - Trimmed a trailing blank line at EOF (flagged by `git diff --check`).
3. `app/src/test/java/com/example/data/local/CapturedTrafficDaoExportTest.kt`
   - Switched from the shared file-backed singleton `RequestrDatabase.get(context)` to a
     per-test `Room.inMemoryDatabaseBuilder` (`@Before`/`@After`), eliminating cross-test
     row leakage.
   - Corrected the "long out-of-range run" fixture: the old timestamps `190..120` were all
     inside `[100,200]`, so the query legitimately returned all 10 rows; replaced with a
     genuinely out-of-range run `[250,240,230,220,210]` ahead of in-range `[190,180,130,120]`.
4. `app/src/test/java/com/example/api/LocalApiServerExportTest.kt`
   - Moved session IDs `9001/9002/9003` → `7000001/7000002/7000003` (the `9xxx` range
     collides with `Http3EndToEndFixtureTest`'s `9001–9008` on the shared file-backed DB).
   - Scoped the two HAR tests with an explicit `sessionId=` query param and corrected the
     same bad fixture data as above.
5. `.gitignore`
   - Added `sdk-noop/build/` so the new no-op module's compiled artifacts are not committed.

(Apologies note: the export-test failures were originally mis-diagnosed as "only a test-isolation
bug"; isolation was real, but two fixtures were also self-contradictory, and the first
`LocalApiServerExportTest` session-ID fix accidentally collided with the HTTP/3 fixture — all
caught and corrected above.)

---

## Verification

### PR #15 baseline (historical)

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 1100 tests, 0 failed |
| `./gradlew :sdk:assembleDebug :sdk:testDebugUnitTest :sdk-noop:testDebugUnitTest` | BUILD SUCCESSFUL |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL (universal APK, `librequestr_tun2proxy.so` packaged) |
| `cd native/tun2proxy-bridge && cargo test --lib` | 34 passed (5× repeated in parallel) |
| `git diff --check` | clean |

Launch-smoke evidence: `/code/.generated_artifacts/recordings/requestr-master-plan-smoke.mp4`,
`/code/.generated_artifacts/images/requestr-master-plan-launch.png`.

### This completion pass (2026-08-31)

Recorded in the pull request for this branch: full unit suite across `:app`, `:sdk` and `:sdk-noop`;
`:app:assembleDebug` **and** `:app:assembleRelease` (the latter only possible after the R8 fix
above, and gated by the repo's `verifyReleaseSigning` task asserting the locked `upload` alias and
certificate SHA-256); `cargo test --lib` for the Rust bridge; the browser-extension test runner; and
`:cli:build`. On-device evidence captured on Redroid (Android 15, SDK 35):

- `/code/.generated_artifacts/images/network-verified-httpbin-200.png` — live `https://httpbin.org/get`
  returning `200 OK` / `H2` / `173 ms` through API Testr, proving real outbound networking.
- `/code/.generated_artifacts/images/proxy-capture-working.png` — `CAPTURING :9000 · HTTP Proxy` with
  a captured `GET /get 200 OK · httpbin.org` row, proving the proxy tier end to end.
- `/code/.generated_artifacts/images/vpn-tun-not-possible-redroid.png` — the honest `ERROR` state
  behind the 🚫 marking above.

---

## Remaining known issues

Nothing on the master plan is unbuilt. These are the honest residual caveats:

1. **§2.17 stays ⚠️ deliberately.** `app/src/main/res/xml/network_security_config.xml` still sets
   `base-config cleartextTrafficPermitted="false"` and permits cleartext only for `localhost`,
   `127.0.0.1`, `10.0.2.2`, `10.0.3.2` and `0.0.0.0`, and the manifest still sets
   `android:usesCleartextTraffic="false"`. The old `Fix #31 (TODO)` comment in that file asked to
   "restrict cleartext to local proxy/mitm only"; it has been rewritten to `Fix #31 (RESOLVED)`
   because the concern is now moot — the proxy's upstream leg no longer goes through the
   NSC-governed `HttpURLConnection` path at all (see Bugs fixed #1), so the config can stay strict
   *and* the proxy can still relay cleartext. What remains genuinely doc-only is the user-facing
   guidance on making a *target* app trust a debug-build CA; that is a property of the app under
   test, not something requestr can change from its own process.
2. **Root-gated surfaces cannot be exercised end-to-end here.** §2.18's `GRANTED` branch and the
   root/Shizuku/Frida capture tiers are code-complete and unit-tested, but this sandbox reports
   `RootState.UNAVAILABLE`, Shizuku not installed and Frida not running, so only their
   non-privileged branches were driven on-device.
3. ~~**`TunRelay.RelayStats` has no per-reason drop counters**~~ **SHIPPED.** Per-reason
   counters now live in `TunRelay.kt:72–92` (`evictionCount`, `dropProtectFail`,
   `dropUpstreamOpen`, `dropUpstreamWrite`, `dropUnrelayableProto`,
   `dropTcpUpstreamWriteFail`) — an honest partition of `relayErrors`/`unrelayableCount`.
   The fifth mockup category (tun-MTU over-length) and TCP EAGAIN are deliberately absent
   (documented, lines 75–80).
4. ~~**`ChaosInjector`'s KDoc is stale.**~~ **RESOLVED.** KDoc at `ChaosInjector.kt:5–27`
   now correctly documents the full live wiring (`RuleEnforcer.parseChaosConfig`,
   `applyChaosToRequest`, `applyChaosToResponse`, `CHAOS_ATTRIBUTION_HEADER`).

### Reference
- Full plan: `docs/plans/MASTER-FEATURE-ADDITION-PLAN.md`
- PR: https://github.com/Mahesh953-hub/requestr/pull/15
