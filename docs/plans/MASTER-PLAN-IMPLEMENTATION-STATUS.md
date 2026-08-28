# Master Plan — Implementation Status

> **Status: ALMOST COMPLETED.** All HIGH-priority and most MODERATE-priority items are
> implemented, wired, and covered by passing unit tests. The LOW-priority tier is
> partially done. A small set of capabilities is implemented + unit-tested but
> deliberately **unwired**, and a few items remain genuinely unbuilt — all enumerated in
> [Pending implementation](#pending-implementation) below for the next agent.

This document records what shipped, where the code lives, and what remains. It is the
hand-off artifact for the work spanning PR #15 (and the earlier Phase 1-3 / runtime
instrumentation phases that this buildout completes).

---

## Delivery map (master plan § → shipped code)

Legend: ✅ fully implemented & wired · 🧪 implemented + tested, not wired · ⚠️ partial · ❌ not built

### HIGH ADD (§1)

| § | Item | State | Where |
|---|------|-------|-------|
| 1.1 | VPN/MITM disconnect (Approach A: tun2proxy CONNECT bridge) | ✅ | `native/tun2proxy-bridge/` (Rust crate), `app/src/main/java/com/example/interception/tun/{NativeTun2Proxy,Tun2ProxyBridge,Tun2ProxyConfig}.kt` |
| 1.2 | Real HTTP/2 (HPACK) + WebSocket parsing on the MITM leg | ✅ | `app/src/main/java/com/example/interception/http/{Http2Parser,Http2Session,WebSocketParser}.kt` |
| 1.3 | CA-free per-app capture tier via Frida OkHttp hook | ✅ | `app/src/main/java/com/example/runtime/frida/{FridaOkHttpAdapter,FridaRuntimeWiring,OkHttpBuilderHookScript}.kt`, `FridaClient.kt` armed-gate |
| 1.4 | gzip decompression (Gap #10) | ✅ | `app/src/main/java/com/example/interception/http/HttpParser.kt` + `HttpBodyDecoder` (Phase 1-3) |
| 1.5 | `MOCK_RESPONSE` rule action (Gap #11) | ✅ | `app/src/main/java/com/example/interception/{MockFixtureLoader,CaptureEngine}.kt` (`RuleAction.MOCK_RESPONSE` branch) |
| 1.6 | Postman Collection v2.1 import/export | ✅ | `app/src/main/java/com/example/data/export/PostmanCollectionCodec.kt` (Phase 1-3) |
| 1.7 | cURL shell-escaping fix | ✅ | `app/src/main/java/com/example/data/export/CodeExporter.kt` `escapeShell()` (Phase 1-3) |
| 1.8 | Executable pre/post-response scripts | ✅ | `app/src/main/java/com/example/scripting/{JsScriptEngine,ApiTestrScriptRunner,PmPrelude,PmBridgeBase,PmTestResult,DynamicVariables}.kt` |
| 1.9 | OkHttp-mock unit tests | ✅ | `app/build.gradle.kts` (`mockwebserver`), `ApiTestrRepositoryFormBodyTest`, `ApiTestrRepositoryApiKeyLocationTest` |
| 1.10 | Remote-cert-mirroring leaf regeneration | 🧪 | `CertificateAuthority.issueMirroredLeaf` + `RemoteCertificateMirror` implemented + tested; CONNECT-time wire **reverted** (see §deferred) |
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
| 2.14(b) | Per-URL AES body crypto | ❌ | Not built |
| 2.15 | Wildcard SAN + HAR parity | ✅ | `HostSanGenerator` + `ExpiringLruCertCache` (LRU cap + TTL) |
| 2.16 | Browser dashboard for `LocalApiServer` | ✅ | `app/src/main/java/com/example/api/LanDashboardServer.kt` (bearer-auth, no wildcard CORS, localhost default + LAN opt-in) |
| 2.17 | NSC "trust own build" tip | ⚠️ | Lightweight doc/surface only |
| 2.18 | Local SQLite DB browser | 🧪 | `TargetAppDatabaseBrowser.kt` implemented + tested, not surfaced in UI |
| 2.19 | `ipstack` relay | ✅ | via §1.1 tun2proxy bridge |
| 2.20 | Virtual DNS + UDP-over-TCP | ✅ | `native/tun2proxy-bridge/src/config.rs` (`WireDnsMode::Virtual`, `WireUdpGw`, `udpgw` feature) |
| 2.21 | API-error-message granularity | ✅ | `ApiTestrRepository.kt` `MappedError` (UnknownHost/Connect/SocketTimeout) |

### LOW ADD (§3) — partially implemented

✅ Shipped: keyset-cursor pagination (Daos), `sdk-noop` module, `ChaosInjector`, bounded
`ExpiringLruCertCache`, wildcard SAN, async polling (with same-origin guard + credential
strip), richer dynamic variables, SOCKS4/4a/5/5h (via tun2proxy), `.p12` **export**.

🧪 Unwired: `.p12` **import** (with password) — `CaP12Exchange.kt`.

❌ Not built: image gallery, weak-network simulation, large-body render gate, content-type-gated
rendering, aggregate stats, multi-key sort, repeat/replay dialog, INTERNET-permission prefilter,
launcher-label fallback, `{{var}}` live-rule templating, copy/paste-cURL for live traffic,
per-CONNECT CA re-check, Basic-Auth show/hide.

---

## This session's concrete changes (files → code)

These are the changes made and committed in this continuation session (commit `d62ab56`,
PR #15), specifically the fixes that took the suite from failing to green:

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

## Verification (all green)

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 1100 tests, 0 failed |
| `./gradlew :sdk:assembleDebug :sdk:testDebugUnitTest :sdk-noop:testDebugUnitTest` | BUILD SUCCESSFUL |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL (universal APK, `librequestr_tun2proxy.so` packaged) |
| `cd native/tun2proxy-bridge && cargo test --lib` | 34 passed (5× repeated in parallel) |
| `git diff --check` | clean |

Launch-smoke evidence: `/code/.generated_artifacts/recordings/requestr-master-plan-smoke.mp4`,
`/code/.generated_artifacts/images/requestr-master-plan-launch.png`.

---

## Pending implementation (next agent)

### Deferred — implemented + tested, need wiring (do first; each is a self-contained UI/slice)
1. **§1.10 remote-cert mirror regen** — `CertificateAuthority.issueMirroredLeaf` +
   `RemoteCertificateMirror` are complete; CONNECT-time probing was reverted because probing
   every first-seen `FORCE_MITM` host re-opens a real upstream connection for mock/offline
   traffic (violating §1.5's "mock never dials" guarantee). Wire it as a **discrete opt-in**.
2. **§2.18 local DB browser** — `TargetAppDatabaseBrowser` is complete; add a rooted UI slice.
3. **`.p12` import-with-password** — `CaP12Exchange` is complete; add the import UI.

### Not built
4. **§2.14(b) per-URL AES body crypto** — no `RequestCryptoManager`/`CryptoRule` exists; net-new.
5. **§3 LOW polish batch** — see the "Not built" list under LOW ADD.

### Reference
- Full plan: `docs/plans/MASTER-FEATURE-ADDITION-PLAN.md`
- PR: https://github.com/Mahesh953-hub/requestr/pull/15
