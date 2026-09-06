# START HERE — S5 (Inspector, export, replay, Report Server, API-Testr protocols)

**As of 2026-09-06, S0-S4 of `plans/v1-interceptor-architecture.md` are COMPLETE on `main`.**
The next agent should work **S5 (tasks 36-41 + 58-62)** directly. Create branch
`vorflux/interceptor-s5-export-replay` from `main`, implement one commit per task, append an
"S5 task N — done" block to `vorflux-handoff/todos.md` after each commit (same format as the
existing S4 blocks), run the full suite at the end, and open the PR. This file is the ground
truth for what already exists; do not re-do it.

## Shipped slices (PR references)

| Slice | Tasks | PR | Branch (merged) |
|---|---|---|---|
| S0 runtime + CI | foundations | [#18](https://github.com/Mahesh953-hub/requestr/pull/18) | `vorflux/interceptor-s0-runtime` |
| S1 transport | 1-6 | [#19](https://github.com/Mahesh953-hub/requestr/pull/19), [#20](https://github.com/Mahesh953-hub/requestr/pull/20) | `vorflux/interceptor-s1-transport`, `vorflux/interceptor-s1-transport-2` |
| S2 persistence | 7-15 | [#21](https://github.com/Mahesh953-hub/requestr/pull/21) | `vorflux/interceptor-s2-persistence` |
| S3 protocol + search | 16-23, 42-57 | [#22](https://github.com/Mahesh953-hub/requestr/pull/22) | `vorflux/interceptor-s3-protocol` |
| S4 rule engine | 24-35 | [#23](https://github.com/Mahesh953-hub/requestr/pull/23) | `vorflux/interceptor-s4-rules` (merge commit `c47241c`) |

S6 (UI program) and S7 (advanced capture tiers) are NOT started.

## What S4 delivered (the APIs S5 builds on)

- **Typed rule model**: `interception/rules/` — `InterceptorRule`/`RuleConfig` (v23 schema,
  `InterceptorRuleEntity`), `RuleSnapshotStore` (`RuleSnapshotStore.current`), `RulePipeline`
  (single `RuleDecision` per flow), `FlowAuditTrail` (per-stage MATCHED/APPLIED/FAILED/
  REFUSED_SAFETY records persisted to `applied_rules`), stages under `rules/stages/` in the fixed
  order GATEWAY → MIRROR → REWRITE → BREAKPOINT → SCRIPT → MOCK → BODY_CRYPTO → CHAOS → THROTTLE.
  Legacy `intercept_rules` table and `RuleEnforcer` are GONE (v24); `RuleEnforcer` survives only
  as a test oracle under `app/src/test/java/com/example/interception/RuleEnforcer.kt`.
- **Safety (D3)**: `InterceptorSafetyGate` — mutating stages refuse until the one-time
  acknowledgement, can be scoped to the capture selection, kill switch via
  `InterceptorFlags` / notification.
- **Scripting**: out-of-process host in `:scripthost` (`scripting/host/`), Rhino with instruction
  ceiling + wall-clock deadline, `onRequest`/`onResponse`, persistent `context.shared`.
- **HTTP/2 rules**: every terminated h2 stream passes through `Http2StreamInterceptor.plan()`
  (`http/Http2StreamInterceptor.kt`); intercepted streams are held as whole messages, mutated via
  the engine's shared `runRequestStages`/`runResponseStages`, and re-framed by `Http2Messages`
  with recomputed `content-length`. Client↔upstream stream-id mapping in `Http2ProxyConnection`.
  The engine h2 branch wires `Http2RuleInterceptor` + `Http2Session.onTransaction` with a
  per-stream `Record` (audit trail, rules' request view, BLOCK suppression).
- **Flags**: `InterceptorFlags.breakpoints`, `InterceptorFlags.scriptHost` (both default on).
- **Envelope v2 (already half of task 41)**: `LocalApiServer` `/api/rules`, `LanDashboardServer`
  `/dash/rules`, and `WorkspaceExporter` speak the typed **v2** rule envelope and accept/project
  v1 on import (`RuleApiJson`, `LegacyRuleProjection`). Task 41's remaining work is the Report
  Server itself + moving any non-rule export envelope fields to `schemaVersion: 2`.

## S5 task list (verbatim in `plans/v1-interceptor-architecture.md` lines ~230-243)

- **36** [after 18,20] HAR rewrite — `data/export/HarWriter.kt` + `HarReader.kt` (HAR 1.2,
  ordered duplicate headers, cookies, postData, real timings, round-trip into normalized tables).
- **37** [after 36] All 20 snippet targets in `data/export/SnippetTargets.kt` + fix the `toGo`
  Kotlin interpolation bug.
- **38** [after 12] PCAP/PCAPng export surfaces (format selection, UID-attribution display,
  share wiring).
- **39** [after 7,24,26] Replay queue — `interception/replay/{ReplayQueue,ReplayJobRunner}.kt`,
  255-connection ceiling + FIFO overflow, `replay_jobs`/`replay_results` (tables already exist
  since v23), cancel/drain, reuse `ConnectReplay.kt` + `computeReplaySchedule`. Flag `replayQueue`.
- **40** [after 39] Diff engine — `ReplayDiff.kt` on `ui/components/DiffView.kt`.
- **41** [after 36] Report Server + API envelope v2 — `report/{ReportServer,ReportRenderer}.kt`
  (loopback default, bearer token, lifecycle-tied); envelope v2 remainder (see above).
- **58** [after 14] `protocolPreference: RequestProtocol` on saved API-Testr requests — needs a
  **v25 migration** on `SavedApiRequestEntity` (plumb through repository, CRUD, export/import,
  duplication, cURL).
- **59** [after 18,58] Cronet H3 send path behind one `ApiTestrSendClient` seam; `H3` hard-fails
  explicitly when H3 cannot be negotiated; `AUTO` records `protocolDowngradeReason`. Flag
  `apiTestrH3`; gate G26.
- **60** [after 42,59] API-Testr protocol UI (AUTO/H1/H2/H3 selector + complete result states).
- **61** [after 36] Full cURL serialization (ordered duplicate headers, `-b`, multipart `-F`,
  binary sidecar files, POSIX quoting; round-trip through `CurlParser.kt`). Gate G30.
- **62** [after 41] Report Server one-click repeat (exactly one upstream request per click,
  through the task-39 replay runner; 401 without token; loopback-only). Gate G30.

S5 exit verification (master plan line ~348): "export a session to HAR and cURL, re-import both,
diff against source rows, open the report in a browser and click repeat once, and send the same
API-Testr request under AUTO/H1/H2/H3 comparing recorded protocol, timings and assertion results."
Gates: **G4** (HAR round-trip), **G12**, **G22**, **G26** (H3), **G30** (repeat/cURL).

## Build / test playbook (proven during S4)

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 ANDROID_HOME=/opt/android-sdk
# compile (never run two Gradle builds concurrently with a background test job):
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin \
  -x cargoBuildTun2ProxyBridge --console=plain -q
# focused tests (XML at app/build/test-results/testDebugUnitTest/ is overwritten per run):
timeout 260 ./gradlew :app:testDebugUnitTest -x cargoBuildTun2ProxyBridge --console=plain -q \
  --tests 'com.example.interception.SomeTest'
# full suite in background, ending the log with EXIT=<code>:
(./gradlew :app:testDebugUnitTest -x cargoBuildTun2ProxyBridge --console=plain -q > /var/tmp/runN.log 2>&1; echo "EXIT=$?" >> /var/tmp/runN.log)
bash tools/ci/check-schema-drift.sh   # must stay clean; regenerate schemas if you add v25
git -c user.name="vorflux" -c user.email="vorflux@users.noreply.github.com" commit
```

- Kotlin backtick test names must not contain `;`.
- Robolectric pattern: `@RunWith(RobolectricTestRunner::class) @Config(sdk = [Build.VERSION_CODES.Q])`,
  `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`.
- Known flake: `ToolsRepositoryRoomTest :: a custom tool persisted in Room from a prior session
  survives init and is executable` — "Condition not met within 3000ms" under full-suite load;
  passes in isolation. Do not chase it.
- Baseline on `main` after PR #23: **2,644 unit tests** (one flaky failure as above).
- Shared h2 test fixtures live in `app/src/test/java/com/example/interception/http/Http2TestOrigins.kt`
  (`H2ClientRecorder`, `FakeH1Origin`, `FakeH2Origin`, `H2ProxyHarness`).
