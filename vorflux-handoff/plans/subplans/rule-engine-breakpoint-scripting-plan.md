### Summary

Replace requestr's flat, first-match-wins `intercept_rules` model with a typed, ordered, folder-able rule library evaluated through an eight-slot staged pipeline (Gateway → Mirror → Rewrite → Breakpoint → Script → Mock → BodyCrypto → Shaping) from an immutable snapshot, so no rule evaluation touches the database on a relay thread. Add the four capabilities that do not exist today — Host/SNI/CN mirroring, a bounded breakpoint queue with a request/response editor, an out-of-process scripting host with a JSON contract and kill switch, and a bounded replay engine with persisted results plus a diff workspace — plus token-bucket throttling and explicit abuse/safety controls. Every existing rule behavior is preserved through a data-carrying migration and parity tests before the flat JSON paths are deleted.

All paths are relative to `/code/Mahesh953-hub/requestr`. Branch `vorflux/fix-audit-gaps` at `b71d61f`. New Kotlin lives under `app/src/main/java/com/example/interception/rules/` unless stated otherwise; tests mirror source paths under `app/src/test/java/com/example/` per the convention in `docs/plans/dossiers/master-plan-remaining.md`.

**Migration numbering.** The database is at `version = 18` (`RequestrDatabase.kt:98`) with `MIGRATION_8_9 … MIGRATION_17_18` registered. The persistence/protocol subplan claims v19–v21. This plan therefore uses **N** and **N+1** for its two migrations, where N is the next free version after that subplan lands (v22 if it lands first, v19 if this plan lands first). Both follow the discipline of `MIGRATION_17_18`: the hand-written SQL must mirror the `@Entity` exactly — same columns, same NOT NULL-ness, same primary key, same indices — or Room schema validation fails.

**Cross-plan dependency.** Task 4 writes provenance rows. If the persistence subplan's `applied_rules` table has landed, use it; otherwise Task 4 creates it with the same shape. This is decision 2 in the summary and must be resolved before Task 4 starts.

---

### Tasks

#### 1. [parallel] Typed rule domain model, matcher, and Moshi serialization

Pure Kotlin, no database and no relay wiring, so it is testable in isolation.

**Create**
- `interception/rules/RuleModel.kt` — `enum class RuleStage { GATEWAY, MIRROR, REWRITE, BREAKPOINT, SCRIPT, MOCK, BODY_CRYPTO, THROTTLE, CHAOS, OBSERVE }`; `data class InterceptorRule(id, name, stage, enabled, sortOrder, folderId, matcher, config, createdAt, updatedAt, legacyRuleId)`; `data class RuleFolder(id, name, sortOrder, enabled, collapsed, createdAt)`.
- `interception/rules/RuleMatcherSpec.kt` — `data class RuleMatcherSpec(hostGlobs, urlRegex, methods, portRange, ipCidrs, headerMatchers, appPackages)`; a `FlowKey(host, port, url, method, headers, clientUid, appPackage, sourceIp)` input type; `matches(FlowKey)` on the *compiled* form only. Note that `ConnectControl.evaluate` already receives `clientUid` and `sourceIp` but ignores them — the matcher makes them usable.
- `interception/rules/RuleConfig.kt` — `sealed interface RuleConfig` with the ten variants:
  - `GatewayConfig(mode: GatewayMode, suspendTimeoutMs, showBlockedInList)`, `enum class GatewayMode { ALLOW_ONLY, BYPASS, BLOCK, SUSPEND, FORCE_DECRYPT }`.
  - `MirrorConfig(targetHost, targetPort, hostPolicy, sniPolicy, cnPolicy)` with `enum class NamePolicy { PRESERVE, USE_CONNECT_ADDRESS, USE_MIRROR_ADDRESS }`.
  - `RewriteConfig(redirection: RedirectSpec?, replaceRequest: MessageReplacement?, replaceResponse: MessageReplacement?, requestModifications: List<Modification>, responseModifications: List<Modification>)`; `RedirectSpec(targetUrl, preserveHost, directoryMatch)`; `MessageReplacement(statusCode?, statusMessage?, headers, body: BodySource)`; `sealed interface BodySource { Empty, Text(value), File(path) }`; `sealed interface Modification { SetHeader, RemoveHeader, RegexHeader(name, pattern, replacement), SetQueryParam, RemoveQueryParam, RegexBody(pattern, replacement) }` — `$1`-style group references preserved.
  - `BreakpointConfig(phase: REQUEST|RESPONSE|BOTH, timeoutMs)`, `ScriptConfig(source, timeoutMs, maxInstructions, variables)`, `MockConfig(statusCode, statusMessage, headers, body: BodySource)`, `BodyCryptoConfig(direction, scope, keyAlias, algorithm)`, `ThrottleConfig(presetKey?, downlinkBps, uplinkBps, latencyMs, jitterMs, lossPercent)`, `ChaosConfig(failRate, corruptBodyRate, delayMs)`, `ObserveConfig`.
- `interception/rules/RuleConfigJson.kt` — Moshi adapters (`@JsonClass(generateAdapter = true)` + a polymorphic adapter keyed on `stage`). `moshi-kotlin` and `moshi-kotlin-codegen` are already wired in `app/build.gradle.kts:234,266`.
- `interception/rules/CompiledRule.kt` — `RuleSnapshot(revision, rules: List<CompiledRule>)`; `CompiledRule` holds the pre-compiled `Regex`, pre-parsed CIDR ranges, lowercased host globs, and the already-decoded `RuleConfig`. Immutable; no lazy state.
- `interception/rules/RuleValidation.kt` — one validator returning typed errors, replacing the scattered checks in `ui/screens/ReplacementValueUiHelpers.kt` (`ruleConfigError`, `rulePatternError`, `replacementValueSpecFor`, `isBodyCryptoAction`, `savedRuleConfigError`).

**Tests** — `test/java/com/example/interception/rules/RuleMatcherSpecTest.kt`, `RuleConfigJsonTest.kt`, `RuleValidationTest.kt`: host glob and CIDR matching including IPv6, invalid regex surfaces as a validation error not a crash, every `RuleConfig` variant round-trips through Moshi, unknown stage names decode to `ObserveConfig` (mirroring the existing "unknown `RuleAction` decodes to `LOG_ONLY`" contract in `RequestrTypeConverters`).

#### 2. [parallel] Legacy-to-typed converter

The single source of truth for converting a flat row into a typed rule. Used by the DB migration (Task 3), by `WorkspaceExporter` import (Task 14), and by `LocalApiServer` v1 request bodies (Task 14) — one implementation, not three.

**Create**
- `interception/rules/LegacyRuleConverter.kt` — `convert(legacy: InterceptRuleEntity): ConversionResult`, where `ConversionResult` is `Converted(InterceptorRule)` or `Unconvertible(reason)`. Mapping: `BLOCK`→`Gateway(BLOCK)`, `FORCE_MITM`→`Gateway(FORCE_DECRYPT)`, `LOG_ONLY`→`Observe`, `REDIRECT`→`Rewrite(redirection)`, `MODIFY_REQUEST`/`MODIFY_RESPONSE`→`Rewrite(modifications)`, `MOCK_RESPONSE`→`Mock`, `SCRIPT_REWRITE`→`Script`, `CHAOS`→`Chaos`, `BODY_ENCRYPT_AES`/`BODY_DECRYPT_AES`→`BodyCrypto`. `domain` becomes a host glob, `pattern` becomes `urlRegex`, `createdAt` order becomes `sortOrder`, `cryptoKeyAlias`/`cryptoAlgorithm`/`cryptoDirection`/`cryptoScope` become `BodyCryptoConfig` fields, and the flat `replacementValue` JSON (`addHeaders`/`removeHeaders`/`bodyReplace`/`statusCode`/`variables`, plus `script` for scripts and the chaos keys) is decoded into the matching typed config. `file://` body values become `BodySource.File`. Malformed `replacementValue` converts to the stage's empty config and records a warning, matching today's fail-open behavior in `RuleEnforcer.applyRequestModification`.

**Tests** — `test/java/com/example/interception/rules/LegacyRuleConverterTest.kt`: one case per legacy action, one per malformed-JSON path, one asserting `file://` fixture references survive, and one asserting `createdAt DESC` ordering (today's `observeAll` order) is preserved as ascending `sortOrder`.

#### 3. [after 1, 2] Migration N: typed rule tables, folders, and backfill

**Create**
- `data/local/RuleEntities.kt` — `@Entity(tableName = "interceptor_rules", indices = [Index("stage"), Index("enabled"), Index("sortOrder"), Index("folderId")]) InterceptorRuleEntity(id, name, stage TEXT, enabled, sortOrder, folderId, matcherJson, configJson, createdAt, updatedAt, legacyRuleId)` and `@Entity(tableName = "rule_folders") RuleFolderEntity`.
- `data/local/InterceptorRuleDao.kt` — `observeAllOrdered()`, `observeByFolder()`, `getEnabledOrdered()` with an explicit `ORDER BY sortOrder ASC, id ASC` (fixing the missing `ORDER BY` in today's `InterceptRuleDao.getEnabledRules()`), `observeEnabledOrdered(): Flow<List<InterceptorRuleEntity>>`, `countEnabled()`, `reorder(id, sortOrder)`, `setEnabled(id, enabled)`, folder CRUD, and `insertAll` for import.

**Modify**
- `data/local/RequestrDatabase.kt` — register the two entities plus Task 9/11 entities, bump `version` to N, add `MIGRATION_<N-1>_<N>` to the `addMigrations(...)` list at line 149.
- `data/local/RequestrTypeConverters.kt` — converters for `RuleStage` (by name, unknown → `OBSERVE`).

**Migration body.** Create both tables with SQL that mirrors the entities exactly, then read every `intercept_rules` row inside the migration, run `LegacyRuleConverter`, and insert the typed rows in `createdAt` order. Follow `MIGRATION_16_17` (chat backfill) for the data-carrying pattern and `MIGRATION_17_18` (recent_activity) for the exact-mirror discipline. `intercept_rules` is **not** dropped here.

**Tests** — `test/java/com/example/data/local/MigrationRulesTypedTest.kt` (modeled on `Migration14To15Test`/`Migration17To18Test`): open at v18 with seeded rows covering all 11 actions, migrate, assert every row landed with the right stage, config, and `sortOrder`, assert `legacyRuleId` linkage, and assert Room's schema validation passes on reopen. Plus `InterceptorRuleDaoTest.kt` for ordering and folder queries.

#### 4. [after 3] Snapshot store, pipeline, provenance, and relay cutover

This is the task that removes `runBlocking` from the relay path and makes evaluation happen once per flow.

**Create**
- `interception/rules/RuleSnapshotStore.kt` — process singleton with `@Volatile var current: RuleSnapshot`. `start(scope, dao)` collects `observeEnabledOrdered()` and recompiles into a new immutable snapshot on every emission, incrementing `revision`. Relay threads only ever read `current`. Includes a `replaceForTest(snapshot)` seam.
- `interception/rules/RulePipeline.kt` — `evaluate(snapshot, flowKey): RuleDecision`. `RuleDecision` is immutable and holds the resolved per-slot outcomes: `gateway: GatewayOutcome`, `mirror: MirrorConfig?`, `rewrite: ResolvedRewrite`, `breakpoint: BreakpointConfig?`, `scripts: List<ScriptConfig>`, `mock: MockConfig?`, `bodyCrypto: BodyCryptoConfig?`, `throttle: ThrottleConfig?`, `chaos: ChaosConfig?`, plus `matchedRules: List<RuleRef>` and `revision`. Precedence rules, all enforced here and unit-tested: gateway resolves by mode precedence `ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT` regardless of list order; rewrite takes at most one redirection (first match), then replacements, then every matching modification in list order; mirror/breakpoint/mock/bodyCrypto/throttle/chaos take the first match; scripts accumulate in list order. Disabled folders disable their rules.
- `interception/rules/FlowAuditTrail.kt` — mutable per-flow accumulator; `record(ruleRef, outcome: APPLIED|SKIPPED_PRECEDENCE|REFUSED_SAFETY|FAILED, detail)`; `toRecords()`. Also `AppliedRuleRecord(ruleId, ruleName, stage, outcome, detail, atMs)`.
- `interception/rules/RuleMetadataHeaders.kt` — the new attribution header names (`x-requestr-breakpoint`, `x-requestr-replay-id`, `x-requestr-mirror`), added explicitly to `RuleEnforcer.METADATA_HEADER_KEYS`. Keep the closed allow-list; do **not** switch to an `x-requestr-` prefix match.

**Modify**
- `interception/RuleEnforcer.kt` — delete `getMatchingRule` and `checkRules` (the only DB-touching functions). Keep and re-target every byte-level applier to typed configs: `applyRequestModification`/`applyResponseModification` take `RewriteConfig`, `buildMockResponse` takes `MockConfig`, `applyScriptRewrite` moves to Task 10's client, `applyBodyCrypto*` takes `BodyCryptoConfig`, chaos functions take `ChaosConfig`. `MAX_CHAOS_DELAY_MS`, `CHAOS_ATTRIBUTION_HEADER`, `SCRIPT_ERROR_HEADER`, `scriptErrorHeaderValue`, the `CryptoApplyResult`/`ChaosApplyResult` sealed types, and `buildCryptoFailureResponse` all stay as-is.
- `interception/ConnectControl.kt` — `evaluate` takes a `RuleDecision` instead of a `RequestrDatabase`; `Disposition` gains `Suspend(timeoutMs)` and `Mirror(target, hostPolicy, sniPolicy, cnPolicy)`. `PinningFailureRegistry.shouldBypass` keeps overriding `FORCE_DECRYPT` exactly as today.
- `interception/MitmProxyEngine.kt` — evaluate once in `handleParsedRequest` (replacing line 916), stash the `RuleDecision` on the per-connection state alongside `pending`, and reuse it in `handleParsedResponse` (deleting the second lookup at line 1079). Replace the two `when (rule?.action)` blocks with ordered slot application. `emitTransaction` (line 1158) gains the audit trail and persists provenance.
- `interception/CaptureEngine.kt` — replace the 11-branch `when` (lines 657–737) and the `checkRules`/`getMatchingRule`/`applyRequestModification`/`applyResponseModification` calls (lines 829–883) with the same pipeline. Preserve the existing `forcePersist` logic (lines 737–738) and the documented MITM-only restriction for script/chaos/body-crypto stages.
- `interception/HttpProxyShared.kt` — `handleConnectAttempt` handles the two new `Disposition` cases: `Suspend` holds the socket open without writing a response until `suspendTimeoutMs`, then closes (simulating a timeout, per the dossier); `Mirror` dials the mirror target.
- `data/local/Entities.kt` + provenance persistence — write `AppliedRuleRecord`s into the `applied_rules` table (owned by the persistence subplan, created here if absent) keyed by traffic id.

**Retire** — the `runBlocking` DAO reads; the duplicate per-flow rule lookup.

**Parity tests (the gate before any flat path is deleted)** — `test/java/com/example/interception/rules/LegacyParityTest.kt` plus updates to the existing suite so each of the 11 behaviors is asserted byte-identical before and after: `RuleEnforcerTest`, `RuleEnforcerTemplatingTest`, `RuleEnforcerBinaryFixtureTest`, `RuleEnforcerBodyCryptoTest`, `RuleEnforcerBodyReplacedFlagTest`, `RuleEnforcerScriptRewriteTest`, `ChaosConfigBoundsTest`, `ConnectControlTest`, `MitmProxyEngineChaosTest`, `MitmProxyEngineMockResponseTest`, `MitmProxyEngineCryptoFailClosedTest`, `MitmProxyEngineSerializeConsistencyTest`, `HttpProxyEngineConnectTest`, `CdpFetchAdapterTest`, `LocalApiServerObserveAndRulesTest`, `WorkspaceExporterTest`. New tests: precedence resolution per slot, snapshot revision changes on DAO emission, zero DAO calls during evaluation (assert with a DAO test double that throws), and provenance rows written for every applied stage.

#### 5. [after 4] Gateway stage

**Create** — `interception/rules/stages/AccessControlStage.kt`. Named `AccessControlStage`, **not** `GatewayEngine`: `interception/gateway/GatewayEngine.kt` (344 lines) is LAN gateway mode — a proxy for other devices on the hotspot that deliberately sits outside `PreflightController`'s tier state machine and writes to a `CaptureTier.GATEWAY` session. The KDoc must say so. The user-facing label stays "Gateway".

Behavior: `ALLOW_ONLY` means only matching flows proceed and everything else is blocked (so the presence of any enabled allow-only rule flips the default); `BYPASS` passes through untouched and un-decrypted, and wins over `FORCE_DECRYPT`; `BLOCK` returns the existing `HTTP/1.1 403 Blocked by requestr` and still emits a capture row when `showBlockedInList` is set (today's MITM path already emits before returning); `SUSPEND` holds the connection without a response until `suspendTimeoutMs`, then closes; `FORCE_DECRYPT` maps to `Disposition.ForceMitm`. Fail-open: an evaluation error allows the flow and records `FAILED`.

**Tests** — `test/java/com/example/interception/rules/stages/AccessControlStageTest.kt`: mode precedence with all five modes matching one flow; allow-only inverts the default; blocked flow never reaches the server (assert via `MockWebServer.requestCount == 0`) and shows a blocked badge; suspend produces a client-visible timeout and does not leak the socket.

#### 6. [after 4] Mirror stage with Host/SNI/CN policies

**Create** — `interception/rules/stages/MirrorStage.kt`. Resolves host A → host B before TLS and returns a `MirrorPlan(dialHost, dialPort, hostHeaderValue, sniName, certCommonNameSource)` from the three independent `NamePolicy` values.

**Modify**
- `interception/MitmProxyEngine.kt` CONNECT path (~lines 280–470) — apply the plan before `certCache.getOrCompute(host) { generateHostCertOrMirror(...) }` so the leaf is signed for the name the client expects; key the cert cache on the resolved CN, not the CONNECT host, so mirrored and unmirrored entries do not collide in `ExpiringLruCertCache`; pass `sniName` to the upstream handshake; use `dialHost`/`dialPort` for `LazyUpstreamConnection`; keep `sniHost` correct for `persistMitmTlsSession`/`persistFailedMitmTlsSession` (lines 1514, 1557).
- `interception/LazyUpstreamConnection.kt` — accept an explicit SNI name so the mirror target can be dialed with a different SNI than its hostname. It is already the single dial seam (`connectionFactory`, `preferredAlpnProtocols`, `onFirstConnect`).
- `interception/RemoteCertificateMirror.kt` — reuse `fetchRemoteLeaf(host, port)` against the **mirror** host when `cnPolicy = USE_MIRROR_ADDRESS`. No behavior change to `profileFrom` validity clamping.

**Tests** — `test/java/com/example/interception/rules/stages/MirrorStageTest.kt` for all nine policy combinations, and `MitmProxyEngineMirrorTest.kt` (following `MitmProxyEngineMitmHandshakeTest` and `MitmProxyEngineLazyUpstreamTest`): the client handshakes against a leaf whose CN/SAN match the policy, the `Host` header matches the policy, and bytes reach the mirror target. Also assert cert-cache keys do not collide between mirrored and direct flows.

#### 7. [after 4] Rewrite stage

**Create** — `interception/rules/stages/RewriteStage.kt`. Fixed order: redirection → replacement → modification. At most one redirection per request. `RedirectSpec.preserveHost` decides whether the `Host` header keeps the original value; `directoryMatch` maps a path prefix onto the target's path prefix. Replacement builds a whole request or response from status/headers/body (`Empty`/`Text`/`File`). Modifications delete or set headers, apply regex header replacement with `$group` references, add or remove query params, and apply regex body replacement.

**Modify**
- `interception/RuleEnforcer.kt` — the existing `applyRequestModification`/`applyResponseModification` become the typed modification appliers, keeping `RuleTemplateExpander.expand` for `{{var}}` and `MockFixtureLoader.resolve`/`applyTo` for `file://` bodies.
- `interception/MitmProxyEngine.kt` — redirection replaces the current inline `REDIRECT` branch (which overwrites `request.url`, re-derives `request.host` via `java.net.URI`, and rewrites `headers["host"]`). Content-Length recomputation stays in `bodyBytesFor` (line 1398).

**Tests** — `RewriteStageTest.kt`: two matching redirections apply only one; ordering (a modification never sees the pre-replacement message); `$1` group references; `preserveHost` both ways; directory match; body regex on binary bodies is refused rather than corrupting bytes. Integration `MitmProxyEngineRewriteTest.kt`: the redirected request switches host and keeps the body, the server accepts the mutated request (assert via `MockWebServer.takeRequest()`), and `Content-Length` matches the mutated body.

#### 8. [after 4] Mock stage

**Create** — `interception/rules/stages/MockStage.kt` wrapping the existing `RuleEnforcer.buildMockResponse` semantics: default 200/empty body, content-type auto-detection (`looksLikeJson()` → `application/json; charset=utf-8`, binary → `application/octet-stream`, else `text/plain; charset=utf-8`), always-recomputed `content-length`, lowercased header names, `replacedBodyBytes` for binary, `bodyReplaced = true`.

**Modify** — `browser/CdpFetchAdapter.kt` shares the mock schema today and must consume `MockConfig` instead of parsing `replacementValue`.

**Tests** — extend `MitmProxyEngineMockResponseTest` and `CdpFetchAdapterTest` to the typed config; assert the mocked flow never opens an upstream connection and that a fixture-backed binary mock emits correct `content-length`.

#### 9. [after 4] Breakpoint queue and editor backend

**Create**
- `interception/rules/stages/BreakpointStage.kt` — decides whether a flow pauses, for which phase.
- `interception/breakpoint/BreakpointRegistry.kt` — `Semaphore(MAX_PAUSED_FLOWS = 8)` acquired **before** pausing. A flow that cannot get a slot within `SLOT_WAIT_MS` proceeds unpaused and records `SKIPPED_PRECEDENCE` (fail-open — a full queue must never wedge new traffic). Each paused flow gets a `CompletableFuture<BreakpointAction>`; `sealed interface BreakpointAction { Resume, Replace(message), Abort }`. Mandatory auto-resume at `BreakpointConfig.timeoutMs` (default 30_000, hard ceiling enforced by the safety caps). `invalidate(flowId, reason)` completes with `Abort` and releases the slot; called when the client socket or `LazyUpstreamConnection` closes. `resumeAll()` for the kill switch. All waits are on the flow's own relay thread, which is already one thread per connection in `MitmProxyEngine`, so a pause cannot block other flows.
- `data/local/BreakpointEntities.kt` — `@Entity(tableName = "breakpoint_paused_flows") PausedFlowEntity(flowId PK, ruleId, phase, requestSnapshot, responseSnapshot, pausedAt, timeoutAt)` and its DAO, so the queue survives process death and the UI can render it from Room rather than in-memory state.

**Modify**
- `interception/MitmProxyEngine.kt` — pause points after rewrite and before script, on both request and response sides. On `Replace`, re-serialize and recompute `Content-Length` through the existing `bodyBytesFor` path. On `Abort`, close without forwarding and emit a capture row marked aborted. Add `x-requestr-breakpoint` to the metadata allow-list.
- `interception/RequestrVpnService.kt` — extend the existing foreground notification with a paused-flow count and a "resume all" action, so a pause cannot be stranded by backgrounding.

**Tests** — `BreakpointRegistryTest.kt`: slot exhaustion is fail-open; auto-resume fires at the timeout; `Abort` releases the slot; disconnect invalidation completes the future exactly once; `resumeAll` drains. `MitmProxyEngineBreakpointTest.kt`: two concurrent flows, one paused — the other completes normally, and the paused one auto-resumes and produces correct final wire bytes. Combined case: breakpoint + rewrite + throttle on one flow produce the expected bytes and timing.

#### 10. [after 4] Isolated scripting host, JSON contract, and console data

**Create**
- `app/src/main/AndroidManifest.xml` — declare `ScriptHostService` with `android:process=":scripthost"`, `android:exported="false"`.
- `scripting/host/ScriptHostService.kt` — the remote-process service. Receives a `ScriptRequestEnvelope`, runs it through the existing `JsScriptEngine`, replies with a `ScriptResponseEnvelope`. Must preserve the two non-negotiable Rhino settings already documented in `JsScriptEngine`: `cx.optimizationLevel = -1` (ART cannot load Rhino's generated bytecode) and `cx.wrapFactory.isJavaPrimitiveWrap = false` (otherwise `typeof` reports "object" and `===` comparisons in `pm.expect` fail).
- `scripting/host/ScriptEnvelopes.kt` — Moshi models. Request: `{ phase: "onRequest"|"onResponse", flowId, scriptId, request{url, method, headers, body, bodyBase64, bodyTruncated}, response{...}, shared{}, env{} }`. Response: `{ ok, aborted, request{...}, response{...}, shared{}, console[], tests[], error{message, backtrace} }`. Bodies over `MAX_SCRIPT_BODY_BYTES` (2 MiB, aligned with `HttpBodyDecoder.MAX_STORED_BODY_BYTES`) are passed truncated with `bodyTruncated = true`, and a body mutation returned against a truncated body is **refused** (fail-closed on the body, fail-open on the flow) with a `FAILED` audit record — silently writing a truncated body to the wire is not acceptable.
- `scripting/IsolatedScriptClient.kt` — in-app side. Binds lazily, one in-flight call per flow, per-call deadline = `ScriptConfig.timeoutMs` (ceiling 10 s). On deadline: unbind, `Process.killProcess(remotePid)`, record `FAILED`, let the flow continue unmodified. Circuit breaker: after 3 consecutive host failures the Script stage is disabled and a banner is raised. `killSwitch()` for the global safety control.
- `interception/rules/stages/ScriptStage.kt` — runs matching scripts in list order; a `null`/`None` return aborts the flow; `context.shared` is threaded from the `onRequest` reply into the `onResponse` request for the same flow.
- `scripting/ScriptConsoleLog.kt` — bounded ring buffer of `ScriptLogEntry(flowId, scriptId, level, message, backtrace, atMs)` fed from `consoleOutput`, plus `PmTestResult` rows, exposed as a `Flow` for the console UI.

**Modify**
- `scripting/PmPrelude.kt` — add the `onRequest(context, request)` / `onResponse(context, response)` lifecycle and `context.shared`, keeping the existing `pm.*` surface and `templateString` precedence (`DynamicVariables` → env → non-shadowed globals) unchanged.
- `scripting/PmBridgeBase.kt` — add a shared-context accessor. `GlobalVariableStore` is process-wide, so in the remote process it must be seeded from and returned to the envelope rather than assumed to be the app's store.
- `interception/RuleEnforcer.kt` — `applyScriptRewrite` becomes a thin call into `IsolatedScriptClient`; `SCRIPT_ERROR_HEADER` and `scriptErrorHeaderValue` keep their current behavior (error header added *after* serialization so it reaches the persisted row but never the origin).

**Tests** — `ScriptEnvelopesTest.kt` (round-trip, binary body base64, truncation flag), `IsolatedScriptClientTest.kt` (Robolectric: infinite loop returns within the deadline and the flow proceeds; three failures open the circuit breaker; kill switch unbinds), extend `JsScriptEngineTest` for the new prelude entry points, `ScriptStageTest.kt` (shared context survives request→response; `null` return aborts; scripts run in list order). Integration `MitmProxyEngineScriptTest.kt`: a mutating script produces byte-correct wire output, and a hanging script does not delay a second concurrent flow beyond its own timeout.

#### 11. [after 4] Replay engine, persisted results, and diff

**Create**
- `data/local/ReplayEntities.kt` — `@Entity ReplayJobEntity(id, sourceFlowIds, iterations, intervalMs, jitterMs, concurrency, proxyTarget, status: PENDING|RUNNING|COMPLETED|FAILED|CANCELLED, queuedCount, createdAt, completedAt)` and `ReplayResultEntity(id, jobId, sourceFlowId, attemptNumber, capturedTrafficId, statusCode, latencyMs, error, startedAt)`; DAOs with observing queries.
- `replay/ReplayEngine.kt` — `MAX_CONCURRENCY = 255`, `Semaphore(255)`, FIFO `LinkedBlockingQueue<ReplayTask>`, `AtomicInteger active`; the 256th task queues and the queue depth is observable. Reuses `computeReplaySchedule` (already pure and unit-tested in `CapturedTrafficHelpersTest`) for interval/jitter, and `ApiTestrRepository.executeRequest` as the sending client. Each attempt inserts a `captured_traffic` row tagged with `x-requestr-replay-id` provenance so results re-enter the capture list. Cancellable per job.
- `replay/FlowDiffer.kt` — pure diff over status line, headers (order- and duplicate-aware), and bodies. Returns `List<DiffSegment(kind: EQUAL|ADDED|REMOVED|CHANGED, leftRange, rightRange, text)`. Renders at most `DIFF_WINDOW_BYTES = 100_000` per side, reports how much was elided, and never loads a spilled body file into memory beyond the window.

**Modify**
- `ui/screens/InterceptScreen.kt` — `RepeatReplayDialog` (line 4395) and `ReplayAttemptResult` (line 4371) become job-backed: the dialog submits a `ReplayJobEntity` and observes results, instead of holding `countText`/`intervalText`/`jitterText` and a local `replayJob` on `Dispatchers.IO`.

**Keep** — `interception/ConnectReplay.kt` unchanged. It is a CONNECT/TLS-handshake probe with its own `ReplayResult` disposition vocabulary aligned to `ConnectAttemptEntity`, not an HTTP replayer. Document the distinction in its KDoc so the two are not conflated.

**Tests** — `ReplayEngineTest.kt`: 100 flows at concurrency 255 complete with one result row and one capture row per attempt; a 256-task burst queues the overflow FIFO and `queuedCount` is observable; cancellation stops pending tasks; schedule delays match `computeReplaySchedule`. `FlowDifferTest.kt`: identical flows produce all-`EQUAL`; header reorder is detected; a 5 MB body diff stays within the render window and reports elision.

#### 12. [after 4] Throttling: token buckets and presets

**Create**
- `interception/rules/stages/ThrottleShaper.kt` — per-flow uplink and downlink `TokenBucket`s plus latency, jitter, and loss. Applied inside the relay copy loops (`MitmProxyEngine.inspectClientToServer`/`inspectServerToClient`, `HttpProxyShared.relay`) and at connect time.
- `interception/TokenBucket.kt` — `TokenBucket(capacityBytes, refillBytesPerSec, nowNanos: () -> Long = System::nanoTime)`; `acquire(bytes): Long` returns the milliseconds the caller must sleep. Injectable clock so accuracy tests are deterministic.
- `interception/NetworkConditionPresets.kt` — the dossier preset table: `OFFLINE`, `BAD_3G` (400 kbps / 400 ms / 2% loss), `GOOD_3G` (1.6 Mbps / 150 ms / 0.1%), `REGULAR_4G` (4 Mbps / 50 ms), `LTE` (10 Mbps / 30 ms), `WIFI` (20 Mbps / 10 ms), `FIVE_G` (50 Mbps / 5 ms).

**Modify**
- `interception/NetworkConditionInjector.kt` — extend `NetworkCondition` with `downlinkBps` and `uplinkBps` (default 0 = unlimited) so the existing global `SettingsDataStore.networkCondition` path keeps working untouched. Keep the documented stance that loss is modeled as connection failure (`SimulatedPacketLossException`) rather than mid-stream byte dropping, and keep `shouldDrop` not consulting `random` when disabled so existing deterministic tests still pass.
- Precedence: a per-rule `ThrottleConfig` **overrides** the global setting for matching flows; non-matching flows keep the global condition. Document this in both places.

**Chaos** — the existing `applyChaosToRequest`/`applyChaosToResponse` joint-probability behavior (P(fail)=f, P(corrupt)=(1−f)·c, P(delay)=(1−f)·(1−c)) is preserved exactly, now driven by `ChaosConfig` and evaluated after throttle in the same slot.

**Tests** — `TokenBucketTest.kt` with a fake clock: exact delay arithmetic, burst capacity, zero-rate = offline. `ThrottleShaperTest.kt`: uplink and downlink are shaped independently; per-rule overrides global. `ThrottleAccuracyTest.kt`: a 10 MB `MockWebServer` download through the shaper completes within ±20% of `10 MiB / bandwidth + latency` for at least three presets. Extend `ChaosConfigBoundsTest` and `MitmProxyEngineChaosTest` to typed configs.

#### 13. [after 3] Abuse and safety controls

**Create**
- `interception/rules/InterceptorSafetyGate.kt` — `requireAcknowledgement()` blocks enabling any mutating stage (`REWRITE`, `BREAKPOINT`, `SCRIPT`, `MOCK`, `BODY_CRYPTO`, `CHAOS`) until a one-time acknowledgement is recorded; `allowMutation(flowKey): Boolean` returns false for flows from packages outside `SettingsDataStore.selectedPackages` when `appFilterMode` restricts capture, and the pipeline records `REFUSED_SAFETY` instead of mutating; `disableAllMutatingRules()` is the kill switch, which also calls `BreakpointRegistry.resumeAll()` and `IsolatedScriptClient.killSwitch()`.
- `interception/rules/RuleLimits.kt` — `MAX_ENABLED_RULES = 200`, `MAX_PAUSED_FLOWS = 8`, `MAX_REPLAY_CONCURRENCY = 255`, `MAX_SCRIPT_TIMEOUT_MS = 10_000`, reusing the existing `MAX_CHAOS_DELAY_MS = 30_000`. Enforced in `RuleValidation` and again at snapshot compile time, so a hand-edited import cannot exceed them.

**Modify**
- `data/prefs/SettingsDataStore.kt` + `UnifiedDataStore.kt` — add `MUTATING_RULES_ACKNOWLEDGED` and `MUTATING_RULES_ACTIVE` keys, following the existing pattern used by `remoteCertMirrorEnabled`.
- `interception/RequestrVpnService.kt` — persistent notification text and the kill-switch action while any mutating rule is enabled.

**Tests** — `InterceptorSafetyGateTest.kt`: enabling a mutating rule without acknowledgement fails validation; an out-of-filter package is observed but never mutated and the refusal is in the audit trail; the kill switch disables mutating rules, resumes paused flows, and leaves observe/gateway rules alone; each limit rejects the 201st rule / 9th pause / 256th replay connection / an 11 s script timeout.

#### 14. [after 5, 6, 7, 8, 9, 10, 11, 12, 13] Consumer cutover, Migration N+1, and flat-path retirement

Runs only when the Task 4 parity tests are green.

**Modify**
- `api/LocalApiServer.kt` — `GET /api/rules` (line ~699), `ruleToJson` (line 738), `POST /api/rules` (line ~749, currently `RuleAction.valueOf(...)` defaulting to `LOG_ONLY`), and `PUT` (line ~791) emit and accept the typed envelope with `schemaVersion: 2`. v1 flat bodies are accepted on input by delegating to `LegacyRuleConverter`; only v2 is emitted.
- `api/LanDashboardServer.kt` — same typed shape.
- `data/export/WorkspaceExporter.kt` — export the typed rules and folders (replacing the flat `interceptRules` array built from `getEnabledRules()` at line 46); import accepts `schemaVersion` 1 or 2, routing 1 through `LegacyRuleConverter`; keep the crypto-action handling at lines 215–216 and extend `ImportResult` with folder counts.
- `data/models/AppModels.kt`, `data/repository/ApiTestrRepository.kt`, `data/interception/CaptureLogRepository.kt` — drop `RuleAction` references.

**Migration N+1** — `DROP TABLE intercept_rules`; remove `InterceptRuleEntity`, `RuleAction`, `InterceptRuleDao`, and `RequestrTypeConverters.ruleActionToName` from the schema. Keep `hasBlockRuleForDomain`/`observeHasBlockRuleForDomain` semantics by reimplementing them against `interceptor_rules` (`stage = 'GATEWAY'` and `configJson` mode `BLOCK`) so their existing callers keep working.

**Retire** — `InterceptRuleEntity`, `RuleAction`, `InterceptRuleDao`, `RuleEnforcer.getMatchingRule`/`checkRules`, the `org.json.JSONObject` config parsing in `RuleEnforcer`, the flat form state in `ui/screens/InterceptScreen.kt::RulesConfigBottomSheet` (line 3596), and the now-superseded helpers in `ui/screens/ReplacementValueUiHelpers.kt` and `RuleTemplateUiHelpers.kt` (keeping whatever the typed editors still use, and keeping `ReplacementValueUiHelpersTest` coverage for what survives).

**Tests** — `MigrationRulesDropLegacyTest.kt` (v18 → N → N+1 end to end, asserting no data loss and clean schema validation); updated `LocalApiServerObserveAndRulesTest` and `WorkspaceExporterTest` covering v1-in/v2-out round-trips; a test asserting a v1 workspace file exported by the current release still imports with identical behavior.

#### 15. [after 4] Rule-evaluation performance harness and p95 gate

**Create**
- `test/java/com/example/BenchmarkStats.kt` — percentile helper (p50/p95/p99) over a `LongArray` of nanosecond samples, with its own unit test. Nothing like this exists today; grep for `p95`/`benchmark` in `app/src/test` returns only incidental `System.nanoTime()` uses.
- `test/java/com/example/interception/rules/RuleEvaluationBenchmarkTest.kt` — builds a 200-rule snapshot spanning all ten stages with realistic regexes, generates synthetic `FlowKey`s, runs 1,000 warmup then 5,000 sampled `RulePipeline.evaluate` calls, and asserts **p95 < 1 ms**. Evaluation is pure once the snapshot is compiled, so this needs no Android framework. Also asserts snapshot compile time for 200 rules stays under a stated budget, since compilation moved off the hot path.
- `androidTest/java/com/example/interception/rules/RuleEvaluationDeviceBenchmark.kt` — the ART-side counterpart at 50 flows/s sustained, since the JVM unit-test result is indicative but not the shipping runtime. `app/src/androidTest` currently holds only `ExampleInstrumentedTest.kt`, so this establishes the pattern.

#### 16. [after 5, 6, 7, 8, 9, 10, 11, 12, 13] Rule UI surfaces

Split out of `ui/screens/InterceptScreen.kt` (5,095 lines) rather than grown inside it. New files under `ui/screens/rules/`: `RuleLibraryScreen.kt` (ordered list, drag reorder, folders, per-rule enable, import/export, stage chips), `GatewayRuleEditor.kt`, `MirrorRuleEditor.kt`, `RewriteRuleEditor.kt`, `MockRuleEditor.kt`, `ThrottleRuleEditor.kt`, `BreakpointRuleEditor.kt`, `ScriptRuleEditor.kt`, `BodyCryptoRuleEditor.kt`, `ChaosRuleEditor.kt`; plus `ui/screens/breakpoint/BreakpointWorkspaceScreen.kt`, `ui/screens/scripting/ScriptConsoleScreen.kt`, `ui/screens/replay/ReplayQueueScreen.kt`, and `ui/screens/diff/FlowDiffScreen.kt`. `TrafficItemCard` (line 1740) and the detail sheet gain applied-rule badges sourced from provenance rows.

Every editor ships complete — no partial editors and no "coming soon" states. `RulesConfigBottomSheet` is deleted once the library screen covers it.

Mockups already exist in `/code/.plans/designs/` for rule-library, gateway/mirror/rewrite/mock/throttle editors, and breakpoint-workspace, and "Rules applied" badges appear in capture-workspace and session-workspace. **No mockups exist** for the script console, replay queue/results, or diff workspace, and the rule-library mockup shows only five stage chips. The main agent must dispatch a design subagent for those gaps before this task starts.

**Tests** — helper-level unit tests for reorder/folder logic and per-editor validation binding (following `ReplacementValueUiHelpersTest`); no Compose UI tests are assumed since none exist in the repo today.

---

### Testing

**Run** — `./gradlew :app:testDebugUnitTest` (the repo's stated baseline is ~1,100 passing tests). Device benchmark and any instrumented tests via `:app:connectedDebugAndroidTest`.

**Focused verification per task** is listed inline above. The cross-cutting gates:

1. **Parity before deletion.** Task 4's `LegacyParityTest` plus the 16 updated existing suites must prove all 11 legacy behaviors are byte-identical through the new pipeline. Task 14 (retirement) does not start until they are green.
2. **Migration integrity.** `MigrationRulesTypedTest` (v18 → N, all 11 actions seeded, typed output asserted) and `MigrationRulesDropLegacyTest` (v18 → N → N+1, no data loss, Room schema validation clean on reopen).
3. **No DB on the relay path.** A test double DAO that throws on any call is installed, a flow is evaluated, and the assertion is that nothing threw — proving `RulePipeline.evaluate` never touches Room.
4. **Per-stage end-to-end in the capture list** (the dossier's Phase 3 criterion), each against `MockWebServer`: redirect switches host and keeps the body; replace-response swaps the status; modify-header regex replaces the value; a blocked flow shows the blocked badge and `requestCount == 0`; every applied stage appears in the flow's provenance badges.
5. **Wire validity.** For every rewrite case, `MockWebServer.takeRequest()` must succeed and the recorded `Content-Length` must equal the mutated body length.
6. **Performance.** `RuleEvaluationBenchmarkTest` asserts p95 < 1 ms over 5,000 samples on a 200-rule snapshot, with the ART counterpart in `androidTest` at 50 flows/s.
7. **Shaping accuracy.** `ThrottleAccuracyTest` asserts a 10 MB transfer lands within ±20% of the preset's predicted duration for at least three presets, with `TokenBucketTest` proving the arithmetic deterministically against a fake clock.
8. **Isolation and liveness.** A hanging script returns within its deadline and does not delay a second concurrent flow; a paused flow blocks only itself and auto-resumes at its timeout; the breakpoint slot pool is fail-open when exhausted.
9. **Replay at scale.** 100 flows at concurrency 255 complete with correct per-attempt capture rows and no OOM; a 256-task burst queues the overflow FIFO with an observable queue depth.
10. **Safety.** Mutating rules cannot be enabled without acknowledgement; out-of-filter packages are observed but never mutated; the kill switch disables mutating rules, resumes every paused flow, and kills the script host.
11. **Compatibility.** A workspace file and an `/api/rules` payload produced by the current release still import with identical resulting behavior after the migration.
