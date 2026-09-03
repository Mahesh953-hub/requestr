### Summary

This plan closes every dossier item that source verification shows is missing or partial in `Mahesh953-hub/requestr` at `vorflux/fix-audit-gaps` (`b71d61f`), in dependency order: relay concurrency and persistence foundation, search and session lifecycle, the two-sided rule engine, protocol message persistence and the inspector/export surface, replay, the 15-screen UI program with the responsive-tab invariant, and the test/CI/device harness that measures the required numeric gates. Audit contradictions are resolved against source; audit percentages and effort-hour figures are discarded. No stubs, no legacy-preserving compromises, no flags over unfinished UI.

---

### Disposition matrix

Legend: **Keep** = no change beyond tests; **Harden** = same file, stronger behaviour/bounds/tests; **Migrate** = move/reshape into a new home or schema; **Rewrite** = decompose and re-implement; **New** = does not exist.

| Component (path) | Disposition | Evidence / reason |
|---|---|---|
| `interception/tun/TunRelay.kt`, `PacketPump.kt`, `native/tun2proxy-bridge` | Harden | Works; needs pooled MTU ring buffer + per-flow caps (no caps today) |
| `interception/RequestrVpnService.kt` | Harden | `onRevoke` at 591 keep; add `setUnderlyingNetworks` + network callback for Wi-Fi→LTE; no such calls exist today |
| `AndroidManifest.xml` FGS declaration | Keep | Line 71 `specialUse` + subtype property is correct; only drop unused `FOREGROUND_SERVICE_DATA_SYNC` (line 18) |
| `interception/MitmProxyEngine.kt` (1,625 lines) | Rewrite | `launch { handleClient }` at line 177, unbounded; inline `runBlocking` DB writes; must become bounded pool + per-connection state machine, split into files |
| `interception/HttpProxyShared.kt`, `HttpProxyEngine.kt` | Harden | Share the relay pool and the throttle shaper; keep parsing/forwarding logic |
| `interception/gateway/GatewayEngine.kt` | Keep | LAN proxy sharing — a *different* feature from the dossier gateway rule; name collision documented, not renamed |
| `interception/CertificateAuthority.kt`, `RemoteCertificateMirror` | Harden | Exists but gated by default-off `remote_cert_mirror_enabled` (`MitmProxyEngine.kt:275`); becomes the automatic IP-CONNECT cert-sniff path |
| `interception/ConnectControl.kt` (+ `PinningFailureRegistry`) | Harden | Disposition set gains Suspend; failures surface in diagnostics screen |
| `interception/http/HttpParser.kt`, `Http2Parser.kt`, `Http2Session.kt`, `WebSocketParser.kt`, `SseParser.kt`, `grpc/ProtobufDecoder.kt`, `quic/*` | Keep + fuzz | Parsing is correct incl. chunked trailers (`HttpParser.kt:269–308`); add corpora, not rewrites |
| `interception/http/HttpBodyDecoder.kt` | Harden | `MAX_STORED_BODY_BYTES = 2 MiB` truncation only; becomes inline-limit + spill decision |
| `interception/RuleEnforcer.kt` (772 lines, first-match) | Rewrite | No ordering, no provenance, no group substitution; decompose into `interception/rules/` |
| `data/models/AppModels.kt` `RuleAction` (UI-only, dead `BREAKPOINT`) | Migrate | Duplicate of `data/local/Entities.kt:214`; collapse to one persisted enum + UI presentation mapping |
| `data/local/Entities.kt` `InterceptRuleEntity` | Migrate | Add ordering/priority/folder/action-config; keep `crypto*` columns |
| `interception/NetworkConditionInjector.kt` | Harden | Latency/jitter/loss-as-failure only; add token-bucket bandwidth + presets |
| `interception/ChaosInjector.kt` | Keep | Bounded and tested already |
| `scripting/JsScriptEngine.kt` | Harden | Rhino interpretive mode with instruction cap + deadline exists; add `context.shared`, off-relay dispatcher, kill switch |
| `data/local/RequestrDatabase.kt` (v18) + `Daos.kt` | Migrate | v19 then v20; `Daos.kt:50` `LIKE` search replaced by FTS5 path |
| `data/interception/TrafficExporter.kt`, `data/export/CodeExporter.kt` | Harden | `toHar` is single-entry; snippets are 4 languages (curl/python/js/go) — gate needs ≥5 plus session HAR + HAR import |
| `interception/packet/PacketCaptureEngine.kt` | Harden | Real PCAP writer exists; add UID/app attribution metadata + Wireshark gate test |
| `ui/screens/InterceptScreen.kt` (5,095 lines) | Rewrite | Splits into capture workspace / inspector / filters-sessions per design program |
| `ui/components/CommonComponents.kt` `RequestrSegmentedTabs` (1474) | Keep | Correct for ≤4 equal-weight segments; not the scrolling tab surface |
| Material3 `TabRow` at `ApiTestrScreen.kt:463/834`, `FlowScreen.kt:57` | Migrate | Move to the new responsive tab row |
| `ui/components/Http3DetailSheet.kt`, `TlsFailureDetailSheet.kt`, `RelayHealthCard.kt`, `RelayHealthDetailSheet.kt`, `StackFingerprintPanel.kt`, `CdpCard.kt`, `DiffView.kt` | Keep | Already-built honest-state surfaces; become viewer/diagnostics content |
| Relay pool + backpressure metrics source | New | Zero `Semaphore`/`limitedParallelism` in `app/src`, `sdk/`, `native/` |
| Body spill store (`filesDir/captures/...`, hash, first-N-KB) | New | No such path exists |
| FTS5 index + query surface | New | No `Fts3`/`Fts4`/`MATCH` anywhere |
| Session pruning/quota policy | New | No age/size caps |
| `websocket_messages`, `sse_events`, `grpc_messages`, `transaction_headers` tables | New | Entity list in `RequestrDatabase.kt:20–41` has none |
| Rule provenance (`appliedRules`) + interceptor badges | New | Zero `appliedRules` matches |
| Access gate (allow-only > bypass > block > suspend) | New | No such concepts in source |
| `MirrorEngine` (Host/SNI/CN strategies, pre-TLS) | New | Zero `MirrorEngine`/mirror-action matches |
| Breakpoint engine + queue | New | Only marketing chip `HomeScreen.kt:1094` and dead UI enum |
| Replay concurrency ceiling + queue | New | `ConnectReplay.kt` has no cap |
| `RequestrResponsiveTabRow` | New | Invariant unimplemented |
| CI workflows, macrobenchmark module, instrumentation/soak/fuzz harness, screenshot tests | New | No `.github/workflows`; `androidTest` holds only `ExampleInstrumentedTest.kt`; roborazzi dep with no tests |

---

### File boundaries

New code lands in new files so parallel tasks never share writable paths:

- `app/src/main/java/com/example/interception/relay/` — `RelayPool.kt`, `RelayMetrics.kt`, `ConnectionStateMachine.kt`, `BufferPool.kt`
- `app/src/main/java/com/example/interception/rules/` — `RuleModel.kt`, `RuleMatcher.kt`, `RuleOrder.kt`, `AccessGate.kt`, `MirrorEngine.kt`, `RewriteEngine.kt`, `MockEngine.kt`, `ThrottleShaper.kt`, `BreakpointEngine.kt`, `RuleProvenance.kt`, `ScriptHost.kt`
- `app/src/main/java/com/example/data/persistence/` — `TrafficWriteQueue.kt`, `BodyStore.kt`, `SessionPruner.kt`, `DbBackup.kt`
- `app/src/main/java/com/example/data/search/` — extend with `TrafficFtsDao.kt`, `FtsIndexer.kt` (keep `TrafficSearch.kt` as the query parser)
- `app/src/main/java/com/example/data/local/` — `Entities` additions, `Migration18To19.kt`, `Migration19To20.kt`
- `app/src/main/java/com/example/ui/components/RequestrResponsiveTabRow.kt`
- `app/src/main/java/com/example/ui/screens/interceptor/` — `CaptureWorkspaceScreen.kt`, `TransactionInspectorScreen.kt`, `SessionWorkspaceScreen.kt`, `RuleLibraryScreen.kt`, `RuleEditor*.kt` (Gateway/Mirror/Rewrite/Mock/Throttle), `BreakpointWorkspaceScreen.kt`, `WebSocketViewerScreen.kt`, `SseViewerScreen.kt`, `GrpcViewerScreen.kt`, `Http3ViewerScreen.kt`, `CaptureDiagnosticsScreen.kt`
- `benchmark/` — new Gradle module (macrobenchmark) added to `settings.gradle.kts`
- `.github/workflows/ci.yml`, `tools/harness/` (mitmproxy comparison, tcpdump timing diff, fuzz corpora)

---

### Tasks

**Slice S1 — Foundation: bounded relay + persistence spine**

1. **Bounded relay dispatcher and metrics.** Add `interception/relay/RelayPool.kt` exposing a dispatcher built with `Dispatchers.IO.limitedParallelism(n)` where `n = min(64, max(32, 2 × availableProcessors))`, plus an acquire/release `Semaphore` for in-flight connections and exponential accept-loop backoff (100 ms ceiling) when saturated. Replace `MitmProxyEngine.kt:173–177` accept/launch with pool submission; route `HttpProxyShared`/`HttpProxyEngine`/`GatewayEngine` client handling through the same pool. Emit counters (active, queued, rejected, saturation events, mean hold time) from `RelayMetrics.kt` and bind them to the existing `RelayHealthCard`/`RelayHealthDetailSheet` state model. Flag `relayPoolBounded` (default on in debug, off in release until gate G1 passes).

2. **[after 1] Per-connection state machine and buffer caps.** Extract the relay body of `MitmProxyEngine` into `relay/ConnectionStateMachine.kt` (states: accepted → protocol-detected → tls-established → relaying → draining → closed) and `relay/BufferPool.kt` (pooled MTU+headroom buffers for tun reads; per-flow, per-direction cap configurable 512 KB–2 MB, default 1 MB, with spill hand-off above the cap). `MitmProxyEngine.kt` retains only orchestration; protocol detection runs before TLS work as the dossier requires.

3. **[parallel] Room v19 schema.** Add to `captured_traffic`: `requestBodyFilePath`, `responseBodyFilePath`, `requestBodyHash`, `responseBodyHash`, `requestBodyTotalBytes`, `responseBodyTotalBytes`, `dnsMs`, `connectMs`, `tlsMs`, `waitMs`, `downloadMs`, `appliedRules`. Add entities `TransactionHeaderEntity` (`transaction_headers`), `WebSocketMessageEntity` (`websocket_messages`), `SseEventEntity` (`sse_events`), `GrpcMessageEntity` (`grpc_messages`) — each with a real `@ForeignKey(onDelete = CASCADE)` to `captured_traffic` and Room `indices` (the audit's inline `INDEX(...)` SQL is invalid SQLite and is corrected to separate `CREATE INDEX` statements). Register in `RequestrDatabase.kt`, bump 18 → 19, add `Migration18To19.kt`, export `app/schemas/*/19.json`.

4. **[after 3] Migration safety, backup, rollback.** Add `data/persistence/DbBackup.kt`: one-shot pre-migration file copy to `databases/requestr-pre-v19.bak`, retained until the first clean post-migration launch. Enable WAL in the Room builder and `fallbackToDestructiveMigrationOnDowngrade()` so an APK rollback cannot brick the DB; on detecting a downgrade wipe, restore the backup if present and report it in diagnostics. Document failure semantics: migration failure rolls back and stays on v18 with a visible error; spill-file write failure sets the truncated flag and keeps the row; quota exceeded pauses capture with a user-visible event.

5. **[after 3] Body spill store.** Add `data/persistence/BodyStore.kt`: bodies at or below the inline limit (1 MB) stay in the BLOB columns; above it, stream to `filesDir/captures/<sessionId>/<trafficId>.<req|res>.bin` while recording SHA-256, total byte count, and a first-64-KB preview in the row; hard memory ceiling stays 2 MB per direction. Rework `HttpBodyDecoder.kt`'s `clampForStorage`/`MAX_STORED_BODY_BYTES` into "inline limit + spill decision" and keep the existing truncated-state UI as the fallback for spill failures. Add a startup orphan sweep and delete spill files when the owning row or session is deleted. Flag `bodySpill`.

6. **[after 1,3] Write queue and backpressure.** Add `data/persistence/TrafficWriteQueue.kt`: bounded channel (capacity 500) drained by a single-writer coroutine on a dedicated dispatcher, two-phase write discipline (request row inserted on parse, response completion patched on finish), and a saturation policy that drops-oldest-with-metric rather than blocking a relay. Replace every relay-path `runBlocking { dao.insert(...) }` (`CaptureEngine.persistWsFrame`, `MitmProxyEngine` transaction callbacks, `GatewayEngine.persistTransaction`) with queue submission so a Room write can never block `onDecryptedTransaction`.

7. **[after 2,6] VPN/service hardening.** In `RequestrVpnService.kt` call `setUnderlyingNetworks` and register a `ConnectivityManager.NetworkCallback` so a Wi-Fi→LTE handover keeps the tunnel; keep `specialUse` FGS and `onRevoke`; publish relay/queue/spill health into the foreground notification and diagnostics state. Remove the unused `FOREGROUND_SERVICE_DATA_SYNC` permission from `AndroidManifest.xml`.

**Slice S2 — Index, sessions, lifecycle**

8. **[after 3] Room v20 and FTS5 index.** Add `Migration19To20.kt` creating a standalone (not external-content) FTS5 virtual table `traffic_fts(traffic_id UNINDEXED, url, host, headers_text, body_text, tokenize='unicode61')` plus insert/update/delete triggers on `captured_traffic`, and rule ordering columns (task 13). Room cannot declare FTS5 entities, so queries go through `TrafficFtsDao.kt` using `@RawQuery`/`SupportSQLiteQuery`; note in KDoc that Room schema validation ignores the undeclared virtual table and `MigrationTestHelper` therefore still validates. Index body text only from the inline body or the spill preview, never the whole spilled file. Flag `ftsSearch`.

9. **[after 8] Query language over the index.** Extend `data/search/TrafficSearch.kt` so its existing `status:`/`method:`/`host:`/`duration:`/`protocol:`/`source:` grammar compiles into FTS `MATCH` plus SQL predicates instead of in-memory filtering, and support 3-condition AND/OR composition, header and body search, and saved filter presets. Replace the `Daos.kt:50` `LIKE` query with the indexed path (keep it as the fallback when `ftsSearch` is off).

10. **[after 3,5] Session pruning and quotas.** Add `data/persistence/SessionPruner.kt`: per-session transaction cap (default 5,000, oldest-first), session age cap, total spill-directory size cap, and a global DB size ceiling; pruning cascades to headers/WS/SSE/gRPC rows, FTS rows, and spill files in one transaction. Surface quota state and a "capture paused: quota" event to the UI.

11. **[after 9,10] Session workspace UI.** Build `ui/screens/interceptor/SessionWorkspaceScreen.kt` from `designs/session-workspace-light.html`: filters, saved searches, session list with per-session flow counts, switcher, archive/delete, and quota indicators.

**Slice S3 — Rule engine (two-sided)**

12. **[parallel] Single rule model.** Add `interception/rules/RuleModel.kt` as the one source of truth: extend the persisted `RuleAction` (`data/local/Entities.kt:214`) with `MIRROR`, `BREAKPOINT`, `THROTTLE`, `SUSPEND`, `ALLOW_ONLY`, `BYPASS`; delete the duplicate UI enum in `data/models/AppModels.kt:381–388` and map presentation (label/colour) from the persisted enum; keep `TrafficRuleItem` as a view model over the entity.

13. **[after 8,12] Rule ordering, priority, folders.** Add `orderIndex`, `priority`, `folderId`, `actionConfig` to `InterceptRuleEntity` plus a `rule_folders` table in `Migration19To20.kt`; replace `RuleEnforcer.getMatchingRule`'s first-match-over-unordered-list with `rules/RuleMatcher.kt` + `RuleOrder.kt` (deterministic order by priority then `orderIndex` then id, all matches collected rather than the first).

14. **[after 13] Rule provenance.** Add `rules/RuleProvenance.kt` recording every rule that touched a flow (rule id, name, action, phase, outcome, mutation summary) into the new `appliedRules` column via the write queue; render interceptor badges on capture rows and a provenance list in the inspector.

15. **[after 13] Access gate.** Add `rules/AccessGate.kt` implementing the dossier priority chain allow-only > bypass > block > suspend at CONNECT/first-request time, wired into `ConnectControl.Disposition` (new `Suspend`) and consumed by the relay state machine so a suspended flow parks without holding a relay slot.

16. **[after 13,2] Mirror engine.** Add `rules/MirrorEngine.kt` mapping host A→B with independent Host / SNI / CN strategies (preserve | CONNECT address | mirror address), applied before TLS establishment in `ConnectionStateMachine`, and reusing `RemoteCertificateMirror`/`CertificateAuthority.issueMirroredLeaf` for leaf issuance.

17. **[after 13] Rewrite engine.** Add `rules/RewriteEngine.kt` executing strictly redirection → replacement → modification, with regex capture-group substitution (`$1`, `${name}`) in URL, header, and body rewrites, query-parameter edits, and unconditional `Content-Length` recomputation. Move the header/body/template helpers out of `RuleEnforcer.kt` (keeping `RuleTemplateExpander` and `MockFixtureLoader` behaviour) and leave `RuleEnforcer` as a thin deprecated delegate deleted at the end of the slice.

18. **[after 13,17] Mock engine.** Move `buildMockResponse` into `rules/MockEngine.kt` with status/header/body/delay control, fixture indirection, and mock-hit provenance.

19. **[after 1,13] Throttle shaper.** Add `rules/ThrottleShaper.kt`: a token-bucket byte-rate limiter applied per flow and per session on the relay copy loops, plus named presets (GPRS/2G/3G/4G/5G/custom) layered on the existing `NetworkConditionInjector` latency/jitter/loss model. Both a global setting and a per-rule `THROTTLE` action bind to the same shaper.

20. **[after 2,13] Breakpoint engine.** Add `rules/BreakpointEngine.kt`: request- and response-phase pauses served from a bounded parking pool (one waiter per paused flow, never a relay slot), a queue of paused flows with edit/resume/abort/timeout, and hard isolation so a paused flow blocks only itself. Flag `breakpoints`, subject to blocking question 1.

21. **[after 17,20] Script host hardening.** Add `rules/ScriptHost.kt` around `scripting/JsScriptEngine.kt`: `onRequest`/`onResponse` contract with JSON-serialized flow objects, a persistent `context.shared` store, execution on a bounded non-relay dispatcher, per-script timeout plus global kill switch, and error surfacing through provenance instead of the current `scriptErrorHeaderValue` only. Fill the `responseGetResponseTimeMs()` zero with the real timing from task 24.

22. **[after 12,14,15,16,17,18,19,20] Rule library and editors UI.** Build `RuleLibraryScreen.kt` plus `RuleEditorGatewayScreen.kt`, `RuleEditorMirrorScreen.kt`, `RuleEditorRewriteScreen.kt`, `RuleEditorMockScreen.kt`, `RuleEditorThrottleScreen.kt`, and `BreakpointWorkspaceScreen.kt` from the matching `designs/*-light.html` files: drag-ordering, folders, enable/disable, per-type config with live preview against sampled flows, and rule import/export.

**Slice S4 — Protocol persistence, inspector, export**

23. **[after 3,6] Protocol message persistence.** Feed `WebSocketMessageEntity` (direction, opcode, text/binary payload, assembled-fragment flag, timestamp), `SseEventEntity`, and `GrpcMessageEntity` (stream id, direction, compressed flag, message index, payload, trailer status) from `CaptureEngine.kt` (WS at 392–411, SSE at 374, gRPC at 118/493/510) and the `MitmProxyEngine` WS mode path, all through the write queue. Keep the existing per-frame `CapturedTrafficEntity` rows as the parent-flow summary rather than deleting the current behaviour, but stop creating one summary row per frame — frames become child rows of the upgraded flow.

24. **[after 2,6] Real timing breakdown.** Instrument DNS, connect, TLS, wait (TTFB), and download phases in the connection state machine and persist them into the v19 timing columns; expose them to the inspector, HAR export, and the script host.

25. **[after 23] Protocol viewers.** Build `WebSocketViewerScreen.kt`, `SseViewerScreen.kt`, `GrpcViewerScreen.kt`, `Http3ViewerScreen.kt` from the design files, reusing `Http3DetailSheet.kt`/`Http3RowIndicator.kt` content and virtualizing message lists to hit the 1,000-message gate.

26. **[after 23,24,14] Protocol-adaptive inspector.** Build `TransactionInspectorScreen.kt` from `designs/inspector-light.html` with tabs Overview, Headers, Body, Cookies, Timing, TLS, Messages (WS/SSE/gRPC), Provenance, Raw, Diff — tab set adapts to the flow's protocol, uses the new responsive tab row, and reuses `DiffView.kt`, `TlsFailureDetailSheet.kt`, and existing body viewers.

27. **[after 24] Export parity.** Extend `data/interception/TrafficExporter.kt` to full-session HAR 1.2 with real timing fields and add HAR import (round-trip into capture rows); extend `data/export/CodeExporter.kt` from 4 to at least 6 languages (add Kotlin/OkHttp, Java, PHP or Node/axios) with per-language snapshot tests; add UID/app attribution metadata alongside PCAP output in `packet/PacketCaptureEngine.kt`.

28. **[after 26,27] Capture workspace and diagnostics screens.** Build `CaptureWorkspaceScreen.kt` (configurable dense columns: id, method, host, path, status, protocol/ALPN, duration, size, app, device, rule badges; live counters; row → inspector) and `CaptureDiagnosticsScreen.kt` (CA trust state, pinning failures from `PinningFailureRegistry`, relay health, spill/quota state, QUIC policy, remediation actions), retiring the corresponding parts of `InterceptScreen.kt`.

**Slice S5 — Replay and the tab program**

29. **[after 1] Replay concurrency ceiling.** Add a 255-concurrent-connection ceiling with an overflow FIFO queue and cancel/drain semantics to `interception/ConnectReplay.kt` (`replay`, `replayRepeated`), sharing `RelayMetrics` so replay pressure is visible. Flag `replayQueue`.

30. **[parallel] `RequestrResponsiveTabRow`.** Add `ui/components/RequestrResponsiveTabRow.kt`: `TabSpec(label, icon?, badge?, contentDescription?)`, intrinsic-width tabs, `maxLines = 1` with no wrap, `horizontalScroll` enabled only when content overflows, a visible trailing fade/peek gradient at both overflowing edges, animated indicator using existing `scaledTween`/`scaledSpring` tokens, all four glass modes, `Role.Tab` semantics with `selected`, and auto-downgrade to equal-weight when `scrollable = false`. Document it in `docs/DESIGN-SYSTEM.md` §10 next to `RequestrSegmentedTabs`.

31. **[after 30] Tab migration.** Move Material3 `TabRow` usages at `ApiTestrScreen.kt:463`, `ApiTestrScreen.kt:834`, and `FlowScreen.kt:57–58` to the new component; keep `RequestrSegmentedTabs` at `ApiTestrScreen.kt:420`, `NotesScreen.kt:489`, `SettingsScreen.kt:2962` where ≤4 equal-weight segments are correct; every new interceptor screen with more than four tabs uses the responsive row.

32. **[after 11,22,25,26,28,31] Screen program completion and `InterceptScreen` retirement.** Verify all 15 design sections have a real screen, wire navigation, delete the superseded blocks of `InterceptScreen.kt` (5,095 lines) so no duplicate implementation remains, and expand `SettingsScreen.kt` with capture-tier selection, CA management, throttle presets, and script limits.

**Slice S6 — Test, CI, and device infrastructure (starts with S1, gates every slice)**

33. **[parallel] CI pipeline.** Add `.github/workflows/ci.yml`: `:app:testDebugUnitTest`, `:app:lint`, `cargo test --lib` for `native/tun2proxy-bridge`, `:app:assembleDebug`/`assembleRelease` (keeping the R8 `java.beans.*` rules), Room schema-export drift check (fail if `app/schemas/*/N.json` is missing or dirty), and required migration tests. Cache Gradle and Cargo.

34. **[after 33] Unit and migration test expansion.** Follow the existing `MigrationXToYTest` Robolectric pattern for `Migration18To19Test`, `Migration19To20Test`, plus FTS trigger sync, spill/orphan sweep, pruning cascade, write-queue saturation, rule ordering/priority, provenance, access-gate priority chain, mirror strategies, rewrite ordering and group substitution, throttle token bucket, breakpoint isolation, script timeout/kill switch, replay queue, and HAR round-trip.

35. **[after 30] Screenshot and accessibility tests.** Activate the unused roborazzi dependency: golden tests for `RequestrResponsiveTabRow` and each new screen at font scales 1.0 / 1.3 / 2.0 and at narrow/medium/wide widths, asserting single-line labels, no wrap, scroll enabled on overflow, and visible trailing fade; TalkBack semantics tests for icon-only tabs and rule editors.

36. **[after 33] Benchmark and soak module.** Add a `benchmark/` macrobenchmark module to `settings.gradle.kts` with a local HTTP/1.1+HTTP/2 load generator (MockWebServer for unit scope, an on-device server for macrobenchmark), CPU/heap sampling, a 60-minute soak scenario, and a 1,000-WS-message render benchmark.

37. **[after 33] Conformance and comparison harness.** Add `tools/harness/`: nghttp2 fuzz corpus runner against `Http2Parser`/`HpackDecoder`, WS/SSE/gRPC malformed-frame corpora, a mitmproxy side-by-side capture comparator, and a tcpdump timing-delta comparator feeding the 10 ms gate.

38. **[after 34,35,36,37] Device matrix and go/no-go policy.** Define the device lane: physical devices on API 26/30/34/35 run VPN/tun capture, CPU, soak, throttle, and timing gates; emulator lanes run proxy-mode capture, unit, migration, and screenshot gates only. Record the rule that an emulator VPN/tun failure (Redroid `Cannot create interface`, empty `pm list features | grep -i vpn`, AppOps uid mismatch) is never a release blocker, and that ICMP is blocked so ping is never a connectivity check. Depends on blocking question 3.

---

### Testing

**Quantitative gates** — every gate is a named, automated check; a slice does not ship until its gates pass, and a feature flag flips to on only after its gate passes.

| Gate | Target | How it is measured |
|---|---|---|
| G1 relay bound | 32–64 relay tasks; 100 concurrent connections → ≤ 64 active, remainder queued, none dropped silently | `RelayPoolBoundTest` (unit) + macrobenchmark with 100 parallel clients reading `RelayMetrics` |
| G2 body spill | spill threshold 1–2 MB; 3 MB body spills to file with hash + preview; heap stays bounded | `BodySpillThresholdTest` + macrobenchmark heap sample |
| G3 per-flow buffers | 512 KB–2 MB per direction, enforced, never exceeded under 20 concurrent flows | `BufferPoolCapTest` + soak heap dump assertion |
| G4 replay | 255 concurrent max with excess queued and drained; 100 flows replayed | `ReplayCeilingTest` + device replay run |
| G5 WS render | 1,000 WebSocket messages render in < 2 s | macrobenchmark frame/timing trace on `WebSocketViewerScreen` |
| G6 rule eval | p95 < 1 ms at 50 flows/s | `RuleMatcherBenchmark` with 200 rules, p95 from 10 k evaluations |
| G7 throttle | preset accuracy within ±20 % on a 10 MB download | device download timed against preset rate |
| G8 timings | within 10 ms of external capture deltas | tcpdump comparator in `tools/harness/` |
| G9 Phase-0 CPU | ≤ 20 % CPU at 20 concurrent flows | macrobenchmark CPU sampling on physical device |
| G10 soak | 60 minutes, no OOM, no FGS death on API 34 | soak scenario + logcat assertions on API-34 device |
| G11 protocol conformance | nghttp2 fuzz corpus and malformed WS/SSE/gRPC corpora produce no crash and no desync; mitmproxy comparison shows no missing or mis-decoded flows | harness runs in CI (corpus) and device lane (mitmproxy) |
| G12 export parity | HAR export→import round-trip preserves method, URL, headers, body, and timings; PCAP opens in Wireshark with UID attribution; ≥ 5 snippet languages produce runnable output | `HarRoundTripTest`, snippet snapshot tests, manual Wireshark check recorded in the device lane |
| G13 tab invariant | labels single-line, intrinsic width, never wrapped; scroll and trailing fade appear only on overflow; holds at font scale 1.0/1.3/2.0 across widths | roborazzi golden set + semantics tests |
| G14 lifecycle | survives Wi-Fi→LTE handover and resumes after swipe-kill | device instrumentation test toggling networks and restarting the service |
| G15 migration safety | v18→v19→v20 preserve all rows; downgrade restores from backup; orphan spill files are swept | migration tests + a downgrade simulation test |

**Integration verification per slice:** S1 — capture a live session on a physical device with the pool bounded and confirm no relay stall while Room writes lag. S2 — search body text across a 100 k-row session and confirm pruning holds caps. S3 — apply one rule of each type to the same flow and confirm provenance lists them in execution order. S4 — export a session to HAR, re-import it, and diff against the source rows. S5 — replay 100 captured flows while browsing the workspace, and run the golden tab set. S6 — the full gate table green on the device matrix before the program is considered complete.

---

### Blocking questions (for the user, via the main agent)

1. **Breakpoint and script exposure on mobile.** Reqable gates these behind abuse controls. Options: (a) ship both enabled by default; (b) ship behind an explicit developer opt-in with a per-session pause cap and a global kill switch — *recommended*, keeps the engine testable while limiting abuse; (c) build the engines in S3 but expose the UI in a later release; (d) request-phase breakpoints only. Affects S3 scope and the design program's breakpoint workspace.
2. **UDP/443 (QUIC) default policy.** Options: (a) drop at the bridge with a visible "QUIC blocked → HTTP/2 fallback" badge, matching Reqable — *recommended*, matches the dossier's D.1 stance; (b) tee and passively decode only when keys are available; (c) user setting defaulting to drop. Affects the HTTP/3 viewer's honest-state content and G11.
3. **Device lab for the hardware gates.** G7–G10 and G14 need real devices on API 26/30/34/35. Options: (a) Firebase Test Lab — *recommended* if remote hardware is acceptable; (b) a self-hosted device attached to CI; (c) emulator proxy-mode only, deferring hardware gates and explicitly marking them unverified. Affects task 38 and whether S1 can close.
