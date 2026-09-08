# Todos (platform) snapshot

Exported from the platform todo list at handoff time.

## Completed (earlier phases)
- e2465 — Compare RichResponseInspector with the live ApiTestr response viewer — completed
- 805d8 — Harvest missing response-inspection features into ApiTestrScreen — completed
- ae127 — Remove the dead response inspector and four confirmed dead or stale blocks — completed
- 60ee5 — Correct remaining actionable audit drift — completed
- 535c0 — Review the diff and report source-level verification without a build — completed
- 59ad7 — Read the interceptor architecture dossier end to end — completed
- 81128 — Map every proposed interception capability to current source and tests — completed
- 1765c — Audit app-wide tab responsiveness and current design-system constraints — completed
- d8210 — Draft architecture and UI design artifacts for missing capabilities — completed
- 6faed — Synthesize and submit the full implementation plan for approval — completed

## S0 (interceptor runtime foundation)
- 6ab46 — S0.1 Vendor the focused pinned tun2proxy fork and document the patch baseline — in_progress
- dc2d3 — S0.2 Add stable native/Kotlin JNI bridge events and configuration — pending
- 77ef4 — S0.3 Switch Room to bundled SQLite with WAL and migration compatibility — pending
- 8cc9b — S0.4 Add process-wide socket protection across all egress paths — pending
- 59608 — S0.5 Add CI foundation and downgrade-safe database backup/restore — pending
- d5c8e — Verify, review, test, and open the S0 runtime-foundation PR — pending

## S1–S8 (approved interceptor architecture slices)
- cbaea — Execute S1–S8 approved interceptor architecture slices — in_progress
  - S0 closed on branch `vorflux/interceptor-s0-runtime` (PR #18, incl. the ci.yml
    header-check wiring `d37adf8`).
  - S1 task 6 (`LocalGateway` multi-protocol listener) — done on
    `vorflux/interceptor-s1-transport`: SOCKS5 (+info) / SOCKS4(a) / HTTP CONNECT /
    absolute-form / transparent TLS-by-SNI / transparent HTTP-by-Host entries;
    `MitmProxyEngine` + `HttpProxyEngine` demoted to `GatewaySessionHandler`s; bridge
    `+info` username encoding fixed to match vendored `socks.rs`; VPN tier routes via
    SOCKS5 `Tun2ProxyConfig.forLocalGateway`. `SO_ORIGINAL_DST` is exposed as the
    `OriginalDstResolver` hook and gets its implementation in task 48.
  - S1 tasks 7–13 — done on `vorflux/interceptor-s1-transport-2` (PR #20, merged):
    - 7 bounded relay pool + `ConnectionStateMachine` + buffer pool + relay metrics
      (`interception/relay/*`, flag `relayPoolBounded`).
    - 8 `CertificateEngine` rewrite (`LeafProfile`, `ClientCertStore`, upstream trust policy,
      `CertificateAuthority.getOrCreate` Mutex race fix).
    - 9 `quic/UdpPolicyRouter` + SOCKS5 UDP ASSOCIATE (flag `quicTeePolicy`, G25 unit-tested;
      physical-device QUIC runs still owed).
    - 10 `uid/UidAttributor` ladder (+ tracked-session API for the tee).
    - 11 VPN lifecycle hardening (`VpnNetworkHandoff`, `VpnDnsPlan`, service hardening,
      `FOREGROUND_SERVICE_DATA_SYNC` removed).
    - 12 `packet/PcapNgWriter` + `PacketTeeRecorder` (flag `pcapng`, `pcap_format` setting).
      Finding: the vendored tee always reports `session_id == 0`; flow identity is recovered
      from the IP/transport header and matched to the attributor's client tuple.
    - 13 `tls/TrustDiagnostics` + `MutualTlsSignals`, `PinningFailureRegistry.snapshot()`,
      watcher DEGRADED reason, CA-sheet findings card.
    - Not verifiable in this environment (Redroid has no tun): G9/G10/G14, network handoff,
      tee/PCAPng on device, real trust-store scan. Unit suites cover the logic.
  - S2 tasks 14–19 — done on `vorflux/interceptor-s2-persistence` (PR pending review):
    - 14 Room v19 (`transaction_headers`, `transaction_timings`, `applied_rules`, body-reference
      columns, `Migration18To19` + test, schema `19.json`).
    - 15 `data/persistence/PersistenceQueue` single-writer actor (capacity 500, drop-oldest with
      metric, two-phase write, batch listeners); all eleven direct insert sites routed through it.
    - 16 `data/persistence/BodyStore` file spill for over-limit bodies (flag `bodySpill`; inline
      limit 1 MiB, spill ceiling 32 MiB, SHA-256 + preview, orphan sweep).
    - 17 recovery semantics: `PersistenceHealth` failure table, `DbBackup` recovery log,
      `PersistenceHealthBanner`, Settings "DATABASE RECOVERY".
    - 18 measured timings: `interception/relay/PhaseTimings` (relay DNS/connect/TLS/send/wait/
      receive, h1 + h2), `data/repository/CallPhaseTimings` (OkHttp EventListener for API-Testr);
      the `latency * 0.15…` fabrications and fake `ApiResponseData` defaults are gone. This is
      the one plan-mandated edit to the frozen `AppModels.kt` (`ApiResponseData` timing fields
      are now nullable `Long?`, `receiveTimeMs`/`connectionReused` added).
    - 19 `data/persistence/SessionPruner`: per-session cap 5,000 oldest-first, 7-day session age
      cap, 512 MiB spill cap, 1 GiB DB ceiling; single-transaction cascade; quota exhaustion
      pauses capture (`PersistenceHealth.pauseCapture`, queue refuses transactions) and resumes
      once under the caps. Quota controls in Settings arrive with task 46 (D10).
    - Gates: G2 spill threshold unit-tested (`BodySpillThresholdTest`; macrobenchmark heap
      sample still owed); G15 v18→v19 row preservation, downgrade restore and orphan sweep
      unit-tested (v20–v24 arrive in S3); G8 (timings within 10 ms of tcpdump) needs the
      physical lane -- Redroid has no tun, so relay timings were exercised through
      `MitmProxyEngineTimingTest`/`ConnectionTimingTrackerTest` only.
  - S2 merged as PR #21.
  - S3 task 20 — done on `vorflux/interceptor-s3-protocol`:
    - Room v20 (`websocket_messages`, `sse_events`, `grpc_calls`, `grpc_messages`,
      `http2_stream_events`; `Migration19To20` + test, schema `20.json`, `SCHEMA_VERSION = 20`).
    - `data/persistence/CaptureSink` (parent handle + protocol children); `PersistenceQueue`
      implements it via `PersistenceOp.AppendChildren` (children of a dead parent are refused and
      counted as orphaned). `interception/persist/ProtocolRows` builds every child row.
    - Both engines write children under ONE parent: MITM relay (`MitmProxyEngine` with
      `sink = PersistenceQueue`) and tun path (`CaptureEngine`). WebSocket messages hang off the
      101 upgrade row, SSE events off the carrying response, gRPC messages off one row per call
      (`protocol = "gRPC"`, `statusCode` NULL until the trailers, `grpc_calls` upsert; no
      synthetic `x-grpc-*` header on the new path). Flag `protocolChildren` (default on);
      off = the pre-v20 flat `WS→`/`WS←` and `x-grpc-*` rows.
    - Ordered headers: `ParsedHttpMessage.headerLines` (raw casing, duplicates) + `trailers`
      (HTTP/1.1 chunked trailers parsed), `H2Headers.entries` ordered list (maps stay
      last-wins), `Http2Session` request/response trailers + bounded frame timeline
      (`MAX_FRAME_EVENTS = 512`). `transaction_headers` rows are written for every transaction
      (REQUEST/RESPONSE/*_PSEUDO/*_TRAILER kinds).
    - Fixed while here: the tun path never parsed WS frames after the handshake (frames were
      only inspected while an HTTP request was pending); a declined upgrade (non-101) now
      leaves the stream as plain HTTP.
    - Known limits: WS text > 4,096 chars and binary > 2 MiB are truncated with
      `payloadTruncated` (no per-message BodyStore spill yet); SSE on the HTTP/1.x relay is
      persisted when the response framing completes (`ProtocolRows.sseEvents(firstIndex)`
      supports incremental appends once a streaming parser lands); the tun path only sees
      client-side bytes unless the return path is mirrored into the reassembler.
    - UI: with `protocolChildren` on, WS/gRPC per-message rows disappear from the traffic list
      (the parent row stays); the viewers arrive with UI tasks 11–13.
  - S3 task 21 — done on `vorflux/interceptor-s3-protocol`:
    - Room v21 (`Migration20To21` + test, schema `21.json`, `SCHEMA_VERSION = 21`, 13 migrations):
      one new nullable column `captured_traffic.legacyBackfill` (`"v21-ws"` / `"v21-grpc"`)
      stamped on the parents the backfill synthesizes.
    - Backfill runs inside the migration transaction: flat `WS→`/`WS←` rows are grouped by
      `(sessionId, connectionId, wsUrl)` into ONE synthesized 101 parent (`GET`, `WEBSOCKET`,
      timestamp = first frame, sizes = sums) with `websocket_messages` children (opcode from the
      `" (OPCODE)"` URL suffix, `frameIndex` in timestamp/id order); flat `x-grpc-*` rows are
      grouped by `(sessionId, connectionId, url, x-grpc-stream-id)` into one parent per call
      (`statusCode` stays NULL -- never recorded) with `grpc_messages` children (payload re-parsed
      from the hex dump, `payloadTruncated` when the dump was cut, `decodedText` from
      `x-protobuf-decoded`) and a `grpc_calls` row (last `x-grpc-status` seen). Converted flat
      rows are deleted; nothing is invented.
    - Idempotent: `Migration20To21.backfill(db)` re-run is a no-op (test proves it); live v20
      parents (no synthetic headers) are never touched or stamped.
    - Fixture built from the real legacy writers (`MitmProxyEngine(protocolChildren = false)`
      for WS, `Http2Session` harness for gRPC) inserted through the real type converters.
    - Known limits: MITM-relay flat rows carried `connectionId = "host:port"`, so several legacy
      WS connections to one endpoint in a session fold into one parent (tun rows keep their
      5-tuple key and stay separate); a spilled legacy body imports its stored preview with
      `payloadTruncated = 1`.
  - S3 task 22 — done on `vorflux/interceptor-s3-protocol`:
    - Room v22 (`Migration21To22` + test, schema `22.json`, `SCHEMA_VERSION = 22`, 14 migrations):
      new Room table `search_presets` (unique `name`, `query`, three-condition `conditions` +
      `combineWithOr`) via `SearchPresetDao`; the FTS index is NOT a Room entity.
    - `FtsSchema`: `CREATE VIRTUAL TABLE traffic_fts USING fts5(traffic_id UNINDEXED, url, host,
      headers_text, body_text, tokenize='unicode61')` with `rowid = captured_traffic.id`, three
      triggers (insert / delete / update of the indexed columns) and eager population from
      `captured_traffic` + `websocket_messages.payloadText` + `sse_events` + `grpc_messages.decodedText`
      the first time the index is created. Runs from a Room open callback in both driver modes
      and best-effort at the end of `MIGRATION_21_22`.
    - FTS5 exists only on `BundledSQLiteDriver` (fresh installs); Robolectric/framework SQLite has
      no `fts5` module, so `FtsSchema.ensure` logs and sets `FtsSchema.available = false` and the
      app falls back to a bound `LIKE` query over the same columns (+ children via EXISTS). A
      database migrated on the framework driver gets its index on the next bundled-driver open.
    - `TrafficSearchCompiler`: one grammar for both paths -- `key:value`, quoted phrases
      (`body:"rate limit"`), `AND` (implicit), `OR`, `NOT` / `-term`, parentheses; keys
      `status` (`5xx`, `>=`, `<`, ...), `method`, `host`, `path`, `duration`, `ct`/`content-type`,
      `proto`, `source`/`src`, `tag`, `header:`/`body:` (column-scoped), free text -> `MATCH`
      (`"term"*`, FTS operators quoted so user text is literal) or the `LIKE` fallback; every
      value is a bound argument (`SimpleSQLiteQuery` through `TrafficFtsDao.search`);
      `compileConditions` implements the three-condition + AND/OR preset model. NULL body/status
      columns are coalesced so `NOT` behaves like the in-memory filter.
    - Children written through `PersistenceQueue.AppendChildren` append their text to the parent's
      `body_text` (`FtsIndexer.appendChildText`, capped at 16 KiB per append) so a WS/SSE/gRPC
      parent is found by message content. Binary bodies index only url/host/headers.
    - `TrafficSearch.filter` (in-memory, flag off or already-loaded lists) now parses with the
      same compiler, so `OR`/`NOT`/parens work there too; `status:5xx` no longer silently matches
      everything.
    - Flag `InterceptorFlags.ftsSearch` (default on): traffic-list search and Home search become
      DB-backed (`CaptureLogRepository.searchTraffic`, Home shows a new "Traffic" result type that
      opens the Intercept tab). Off -> legacy in-memory filtering, Home shows no traffic hits.
    - Tests: `TrafficSearchCompilerTest` (grammar, per-key SQL, injection, LIKE fallback shape,
      in-memory parity), `TrafficFtsIndexTest` (bundled driver: MATCH on url/host/header/body,
      children, delete/update triggers, binary bodies, LIKE == FTS hits, `ensure` populate +
      idempotent, presets), `Migration21To22Test` (framework driver: presets + unique index,
      no FTS5, LIKE fallback query runs), `GlobalSearchTest` Traffic arm.
  - S3 task 23 — done on `vorflux/interceptor-s3-protocol` (decision D7, gate G11):
    - Decision: in-house `Http2Parser`/`HpackDecoder`/`Http2Session` stay; `h2Netty` NOT armed (flag not declared);
      task 57 builds termination on them. Record: `plans/decisions/D7-http2-conformance.md`.
    - Evidence: nghttp2 HPACK corpus (`http2jp/hpack-test-case`, 3 profiles, vendored gzipped
      under `app/src/test/resources/hpack-test-case/`, MIT) -- 9,918/9,918 blocks byte-exact;
      16 malformed vectors reported via `H2Headers.error` without throwing; frame/header fuzzer
      (`Http2FuzzTest`, fixed seed, `Http2Session` desync probe) 4M iterations 0 failures;
      WS/gRPC/protobuf/SSE fuzzer (`ProtocolFramingFuzzTest`) 1M iterations 0 failures.
      Harness entry point `tools/harness/fuzz/run.sh` (+ README); same classes run in CI's
      `:app:testDebugUnitTest`.
    - Hardening: bounded HPACK integers, index validation, strict Huffman (`decodeStrict`; EOS,
      padding rules), table-size-update ordering + `SETTINGS_HEADER_TABLE_SIZE` bound
      (`setMaxHeaderTableSize`), PADDED/PRIORITY checks, reserved stream-id bit masked, header
      octets decoded as UTF-8 with ISO-8859-1 fallback, `H2Headers.error` (task 57 answers with
      `GOAWAY(COMPRESSION_ERROR)`), `Http2Session.hpackErrorCount`; `WebSocketParser` no longer
      crashes on a 64-bit length with the reserved MSB set.
    - Not covered: mitmproxy side-by-side comparator (device lane, with G27); HPACK encoder
      round-trip (task 57); WS/SSE/gRPC conformance corpora (task 55).
  - S3 task 57 — done on `vorflux/interceptor-s3-protocol` (gate G27):
    - The MITM proxy now TERMINATES HTTP/2 instead of forwarding decrypted h2 bytes. Flag
      `InterceptorFlags.h2Terminate` (default on): on -> the client-side TLS offers ALPN
      `h2, http/1.1` and an h2 client is served by `Http2ProxyConnection`; off -> only
      `http/1.1` is offered so apps fall back to HTTP/1.1 through the proxy. The observe-only
      byte-forwarding path (`inspectH2ClientToServer` / `inspectH2ServerToClient`) is deleted, not
      kept as a fallback (`MitmProxyEngineH2TerminationCutoverTest` asserts on the source).
    - New (all `com.example.interception.http`): `Http2Endpoint` (server- or client-role RFC 9113
      connection: preface, SETTINGS handshake + ACK, PING, GOAWAY, RST_STREAM, PRIORITY ignored,
      PUSH_PROMISE -> PROTOCOL_ERROR, CONTINUATION assembly with PADDED/PRIORITY stripped,
      MAX_CONCURRENT_STREAMS -> REFUSED_STREAM, header-list-size bound, per-connection and
      per-stream error mapping); `HpackEncoder` (dynamic table, never-indexed `authorization` /
      `proxy-authorization` / short `cookie`, Huffman when shorter, size-update emission;
      `HpackHuffman.encode`); `Http2FlowControl` (send windows with a 30 s wait timeout ->
      `FlowControlException` -> RST FLOW_CONTROL_ERROR; receive side re-credits in half-window
      batches); `Http2Translator` (header validation, h2 request -> HTTP/1.1 head incl. cookie
      folding + `Host` from `:authority`, HTTP/1.1 response -> h2 headers/trailers, chunked
      framing, bounded `Http1ResponseReader`); `Http2ProxyConnection` (client leg + upstream leg;
      upstream dialled once at the first HEADERS with ALPN preference `h2, http/1.1`; negotiated
      `h2` -> client-role `Http2Endpoint` re-encodes every stream on a new connection, ordered
      headers preserved; `http/1.1` -> `Http1UpstreamBridge` serialises streams onto one
      keep-alive connection in arrival order; dial failure -> `:status 502` per stream).
    - `LazyUpstreamConnection.Connected.alpn` carries the negotiated upstream protocol
      (`handshakeApplicationProtocol`, API 24+).
    - Persistence unchanged: `Http2Session` is fed the frames read from / written to the CLIENT
      endpoint (`onFrameRead` / `onFrameWritten` hooks), so transactions, gRPC messages, timings
      and the v20 children rows are written exactly as before.
    - Tests: `Http2EndpointTest` (24: handshake, settings validation, flow control both sides,
      CONTINUATION, padded/priority, trailers, RST/GOAWAY, concurrency limit, malformed
      requests -> per-stream vs connection errors), `HpackEncoderTest` (7: round-trip, dynamic
      table, sensitive fields, size updates, Huffman all octets, nghttp2 corpus re-encode 6,651
      blocks), `Http2FlowControlTest` (8), `Http2TranslatorTest` (16), `Http2ProxyConnectionTest`
      (11: h1 and h2 upstreams, gRPC end-to-end, 502, RST/GOAWAY relay, large bodies, trailers),
      `MitmProxyEngineH2TerminationCutoverTest` (3).
    - Known limits: HTTP/1.1 client -> h2 upstream translation exists in `Http2Translator`
      (`requestToH2` / `responseToHttp1`) but is NOT wired (h1 clients still get an h1-only
      upstream); a request body destined for an HTTP/1.1 upstream is buffered (FlowBuffer) until
      END_STREAM rather than streamed; a client read thread blocked in upstream `writeData` while
      the upstream read thread blocks in client `writeData` is broken only by the 30 s flow-control
      timeout; the receive side never reports a peer overrun (it re-credits as it consumes), so
      overrun detection is unit-level only; interception rules on translated h2 = task 35;
      mitmproxy side-by-side comparator still pending the device lane; `Http2Session` merges
      CONTINUATION after HEADERS padding (pre-existing, endpoint strips padding first so the
      observer is unaffected on the terminated path).
  - S4 task 24 — done on `vorflux/interceptor-s4-rules` (interceptor plan §4 v23):
    - Room v23 (`Migration22To23` + test, schema `23.json`, `SCHEMA_VERSION = 23`, 15 migrations):
      new tables `interceptor_rules` (stage-keyed `configJson`, `matcherJson`, explicit
      `sortOrder`, optional `folderId`, `legacyRuleId` back-link), `rule_folders`,
      `breakpoint_paused_flows` (task 31), `replay_jobs` + `replay_results` (S5 task 39; results
      CASCADE with their job). DDL hand-written to match the exported schema column-for-column.
    - Data-carrying backfill inside the migration transaction: every `intercept_rules` row is read
      via cursor, converted by `LegacyRuleConverter`, inserted typed. Evaluation order preserved
      (legacy `getEnabledRules()` had no ORDER BY = rowid scan = `createdAt ASC, id ASC` ->
      ascending `sortOrder`). Idempotent by `legacyRuleId`; unconvertible rows are logged and left
      in place. `intercept_rules` is NOT dropped/altered -- read-only until v24 (task 34, after G16).
    - Typed model (`com.example.interception.rules`): `RuleStage` (declaration order = pipeline
      order GATEWAY -> MIRROR -> REWRITE -> BREAKPOINT -> SCRIPT -> MOCK -> BODY_CRYPTO -> THROTTLE
      -> CHAOS, + OBSERVE; unknown name -> OBSERVE, `MUTATING` set for D3), `InterceptorRule`,
      `RuleFolder`, `RuleMatcherSpec` (host globs, url regex, methods, port range, CIDRs, header
      matchers, app packages, `combine` ALL|ANY -- ANY exists only for legacy OR parity),
      `FlowKey`, sealed `RuleConfig` per stage (`GatewayConfig` ALLOW_ONLY/BYPASS/BLOCK/SUSPEND/
      FORCE_DECRYPT, `MirrorConfig` with independent Host/SNI/CN `NamePolicy`, `RewriteConfig`
      redirection -> replacement -> ordered `Modification` steps, `BreakpointConfig`,
      `ScriptConfig`, `MockConfig`, `BodyCryptoConfig`, `ThrottleConfig`, `ChaosConfig`,
      `ObserveConfig`), `BodySource` Empty/Text/File(`file://`).
    - `RuleConfigJson`: Moshi codegen leaves + three hand-written polymorphic envelopes (`stage`,
      `type`, `kind`). Unknown/missing stage -> `ObserveConfig` (a newer build's row never breaks
      snapshot compile); known stage with malformed body -> `JsonDataException` (hard error).
    - `LegacyRuleConverter` (shared by migration, import, local API): one mapping per legacy
      `RuleAction`, matcher `domain` -> `*domain*` glob + `pattern` -> `urlRegex`, OR-combined.
      Parity notes encoded as warnings, not silent changes: MODIFY_RESPONSE `removeHeaders` was
      never applied by `RuleEnforcer` (not converted, warned); REDIRECT is a bare URL not JSON;
      CHAOS clamps identical to `parseChaosConfig`; crypto direction from the ACTION (column is
      informational); malformed `replacementValue` -> stage's empty config (legacy fail-open).
    - DAOs: `InterceptorRuleDao` (every ordered query `ORDER BY sortOrder ASC, id ASC`;
      `getEnabledOrdered`/`observeEnabledOrdered` feed the task-25 snapshot; `reorderAll`,
      `setEnabled`, `disableStages` kill switch for task 33, `moveToFolder`, typed
      `hasBlockRuleForDomain` for the task-34 UI repoint), `RuleFolderDao` (`deleteAndUnfile`),
      `PausedFlowDao`, `ReplayJobDao`, `ReplayResultDao`. `RequestrTypeConverters`:
      `ruleStageToName`/`nameToRuleStage`.
    - Tests: `RuleConfigJsonTest` (14), `LegacyRuleConverterTest` (16: one per action, malformed
      paths, `file://`, ordering, removeHeaders warning), `Migration22To23Test` (3: all 11 actions
      seeded on v22, order/stage/config/legacyRuleId asserted, idempotent re-run, Room reopen +
      legacy DAO still answers), `InterceptorRuleDaoTest` (10: ordering, folders, kill switch,
      unknown-stage degrade, FK cascade). `tools/ci/check-schema-drift.sh` green with `23.json`.
    - Not in this task: nothing reads `interceptor_rules` at runtime yet -- `RuleEnforcer` +
      `intercept_rules` remain the live path until task 26 cuts the engines over to `RulePipeline`.
  - S4 task 25 — done on `vorflux/interceptor-s4-rules`:
    - `RuleSnapshotStore` (process singleton, `com.example.interception.rules`): `@Volatile current:
      RuleSnapshot` (one load, no lock, no allocation on relay threads) + `snapshots: StateFlow` for
      UI/diagnostics. `start(scope, db)` combines `InterceptorRuleDao.observeEnabledOrdered()` with
      `RuleFolderDao.observeAll()`, `distinctUntilChanged`, recompiles on `Dispatchers.Default` and
      publishes with the next `revision`. Idempotent `start`, `stop`, generation guard so a
      stopped collector can never publish a stale revision, `publish(rules, folders)` for imports,
      `replaceForTest` / `resetForTest` seams. Started from `RequestrApplication.onCreate` via
      `startFromApplication` right after `RequestrDatabase.initAsync`, so the snapshot is warm
      before task 26's pipeline runs. `startFromApplication` is a no-op once `resetForTest` has run:
      Robolectric instantiates `RequestrApplication` for every test, and its collector (bound to the
      real on-disk DB) raced test collectors for the singleton -- that race was the cause of the
      intermittent `RuleSnapshotStoreTest` timeouts seen while landing this task, not Room
      invalidation.
    - `RuleSnapshotCompiler.compile(rules, folders, revision)` -> immutable `RuleSnapshot`
      (`rules` in `sortOrder ASC, id ASC`, `byStage`/`forStage`, `findById`, `issues`,
      `compileDurationNanos`; `RuleSnapshot.EMPTY` at revision 0). Folder semantics: disabled folder
      excludes its rules; dangling `folderId` excludes + `SnapshotIssue`. Undecodable
      `configJson`/`matcherJson` -> excluded + issue (never crashes). Stage taken from the config.
      `CompiledRule` carries a `RuleRef(id, name, stage)` for audit rows.
    - `CompiledMatcher.compile(spec)`: one predicate per constrained dimension; `ALL` = every
      predicate (none = catch-all), `ANY` = at least one (none = matches nothing). Host globs
      (`HostGlob`: `*`/`?`, literal otherwise, case-insensitive, anchored), `urlRegex`
      `containsMatchIn` IGNORE_CASE, methods upper-cased, inclusive port range, `CidrBlock`
      (IPv4/IPv6, bare address = full prefix, literal-only -- never resolves a hostname; matched
      against `upstreamIp` else `sourceIp`), header matchers all-of (name case-insensitive,
      presence-only when regex null), app packages exact. Invalid regex/CIDR -> always-false
      predicate + `errors` (reproduces legacy `runCatching { Regex(...) }.getOrDefault(false)`, so a
      backfilled row with a broken `pattern` still matches on `domain` under `ANY`).
    - Tests: `CompiledMatcherTest` (13, pure JVM; includes a grid parity check of converted legacy
      matchers against a re-implementation of `RuleEnforcer.getMatchingRule`'s predicate incl. an
      invalid regex), `RuleSnapshotStoreTest` (11: compiler ordering/bucketing, folder enablement +
      dangling folder, broken regex kept / undecodable dropped, stage from config; store reactivity
      over StateFlow-backed fake DAOs whose every other method throws -- revision bump on every
      emission, `distinctUntilChanged` coalescing, idempotent start, stop, folder toggles republish;
      Room in-memory: compiles the live table on start, evaluation with the DB closed,
      `replaceForTest` then live revisions move past it; `startFromApplication` disarmed; `publish`).
    - Not in this task: nothing evaluates against the snapshot yet (task 26); `RuleLimits`
      enforcement at compile time is task 33.
  - S4 task 26 — done on `vorflux/interceptor-s4-rules`:
    - `RulePipeline.evaluate(snapshot, key)` (pure, allocation-light, no DAO on the hot path):
      one pass over `snapshot.rules` in `sortOrder ASC, id ASC` into a `RuleDecision` with one slot
      per stage. Gateway precedence `ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT`
      regardless of list order (`GatewayOutcome.Pass/Apply/ImpliedBlock`; losers recorded as
      `Skipped("precedence: X outranks Y")`); ALLOW_ONLY rules present but none matching ->
      `ImpliedBlock` naming the allow-list. Mirror/mock/body-crypto/throttle/chaos are first-match
      (later matches `Skipped(PRECEDENCE_FIRST_MATCH)`); rewrite composes (`ResolvedRewrite`: first
      redirection, first request/response replacement, all modifications in order); scripts and
      observe accumulate. `evaluateConnect(snapshot, host, port, ...)` for the CONNECT phase.
      `RuleDecision.mutates` is the hook task 33 uses for the safety gate.
    - `FlowAuditTrail` (per-exchange provenance; `Phase` CONNECT/REQUEST/RESPONSE, `Outcome`
      MATCHED/APPLIED/SKIPPED_PRECEDENCE/REFUSED_SAFETY/FAILED) -> `AppliedRuleEntity` rows through
      the new `CaptureSink.appliedRules(parent, rows)` (`PersistenceQueue` -> `appendAppliedRules`)
      and the `x-requestr-rules` badge (`RuleMetadataHeaders.rulesBadge`: `stage:ruleId,...` of
      APPLIED/FAILED rules) on the persisted row's response headers only -- never on the wire.
    - Stages under `interception/rules/stages/`: `RewriteStage` (redirect + request/response
      modifications with `$1`/`${name}` variables, Content-Length via `bodyReplaced`), `MockStage`,
      `BodyCryptoStage` (fail-closed synthetic 502 as before), `ChaosStage` (request leg rolls fail
      only, response leg corrupt/delay), `ScriptStage` (Rhino via `ScriptRewriteEngine`, error header
      stamped per legacy), `AccessControlStage` (task 27's CONNECT disposition, landed early because
      `ConnectControl` needed it). `RuleMetadataHeaders` replaces `RuleEnforcer.METADATA_HEADER_KEYS`
      (aliased until task 34 deletes the enforcer).
    - Engine cut-over: `MitmProxyEngine` (`PendingExchange` carries the request-time decision + trail
      to the response leg; gateway block honours `showBlockedInList`; `Mirror`/`Suspend`
      dispositions on CONNECT -- opaque mirror relay now, full MITM mirror is task 28),
      `CaptureEngine.persistTransaction` (observe rules force-persist even with capture off),
      `CdpFetchAdapter` (block / mock / redirect / rewrite / continue), `ConnectControl.decide` ->
      `Verdict(decision, disposition, trail)` reading `RuleSnapshotStore.current` (the `db`
      parameter is gone), `HttpProxyEngine` + `GatewayEngine` + `HttpProxyShared.suspendFlow`.
      All engines take a `ruleSnapshot: () -> RuleSnapshot` seam for tests.
    - G16 parity: `LegacyParityTest` (16) converts every one of the 11 legacy `RuleAction`s through
      `LegacyRuleConverter` and asserts the new stages produce a field-identical `ParsedHttpMessage`
      (url/host/status/headers/body/bodyReplaced/isBodyBinary/replacedBodyBytes) to `RuleEnforcer`
      -- including seeded chaos outcomes, script error headers, crypto round-trips and fail-closed
      502s. Other tests: `RulePipelineTest` (15, incl. a DAO-that-throws zero-DB check),
      `FlowAuditTrailTest` (7), `AccessControlStageTest` (12), `MitmProxyEngineProvenanceTest` (3:
      `applied_rules` rows + badge through `RecordingCaptureSink`). Legacy engine tests keep their
      `intercept_rules` fixtures and sync them into the snapshot via `LegacyRuleTestSupport`.
    - Behaviour change worth knowing: a MODIFY_RESPONSE rule with an empty `{}` config is now a
      no-op (CDP resumes the response instead of re-fulfilling it); `CdpFetchAdapterTest` updated.
    - Not in this task: throttle/breakpoint/script-host stages (30-32), the safety gate (33), legacy
      table drop + `LocalApiServer`/dashboard/exporter/UI cut-over (34), h2 event rules (35).
  - S4 task 27 — done on `vorflux/interceptor-s4-rules`:
    - `AccessControlStage.disposition(decision, host, port, trail, pinningBypass)` turns the CONNECT
      decision into a `ConnectControl.Disposition`: `Block` (gateway BLOCK, or ALLOW_ONLY implied
      block "Blocked: <host> is not in the allow-list"), `Suspend(timeoutMs, ruleName)` (parks the
      flow, no relay permit, socket closed after the rule's `suspendTimeoutMs`), `ForceMitm` (refused
      with `REFUSED_SAFETY` when the host is on the pinning bypass list), `Mirror` (task 28's
      target + `NamePolicy` triple, default port 443), `Redirect` (URL or host:port target), else
      `AllowOpaque`. Precedence is the pipeline's (`ALLOW_ONLY > BYPASS > BLOCK > SUSPEND >
      FORCE_DECRYPT`; block > mirror > redirect across stages). `ConnectControl.decide` ->
      `Verdict(decision, disposition, trail)`; `evaluate` keeps the old shape minus `db`.
    - Wired in `HttpProxyEngine`, `GatewayEngine` (persists SUSPENDED / REDIRECTED) and
      `MitmProxyEngine.handleClient`; `HttpProxyShared.suspendFlow(client, timeoutMs, sleep)` runs
      on the CONNECT's own thread. Request-phase BLOCK in `MitmProxyEngine` answers the legacy
      `403 Blocked by requestr` bytes, never dials, never enqueues; `showBlockedInList` persists
      the 403 with provenance.
    - Tests: `AccessControlStageTest` (12), `HttpProxySharedSuspendTest` (5: sleep + close without
      writing, zero timeout, close on interrupt, no relay permit / no dial through
      `handleConnectAttempt`, pool still free afterwards), `MitmProxyEngineGatewayBlockTest` (4:
      legacy 403 bytes hidden by default, `showBlockedInList` row + `gateway:<id>` badge, ALLOW_ONLY
      implied block vs allow-listed dial, BLOCK > FORCE_DECRYPT and BYPASS > BLOCK).
  - S4 task 28 — done on `vorflux/interceptor-s4-rules`:
    - `interception/rules/stages/MirrorStage.kt`: `plan(connectHost, connectPort, clientSni,
      config, rule)` -> `Plan(dialHost, dialPort, sniName, hostHeader?, certAuthority, certPort,
      certSni)` from the three independent `NamePolicy` axes (PRESERVE / USE_CONNECT_ADDRESS /
      USE_MIRROR_ADDRESS); `applyHostHeader(request, plan, trail)` (case-preserving, leaves
      `request.host`/`url` = the client's view); `stampAttribution(response, plan)` ->
      `x-requestr-mirror: rule="<name>"; target=<host:port>`.
    - `MitmProxyEngine` CONNECT path: the plan is resolved once after the ClientHello peek and
      before leaf issuance. `CertificateEngine.Request(connectAuthority/port/sni)` takes the plan's
      cert inputs, so the leaf is signed for the name the policy dictates and the cert cache is
      keyed on that resolved identity (`identity:port|mode|tag`) -- mirrored and direct flows never
      share an entry unless they resolve to the same CN, and `USE_MIRROR_ADDRESS` + upstream sniffing
      fetches the *mirror's* leaf (`RemoteCertificateMirror` unchanged). `LazyUpstreamConnection`
      gained `tlsServerName` (SNI + hostname the upstream chain is verified against; defaults to the
      dial host) and dials `plan.dialHost:dialPort`. `inspectClientToServer` ->
      `handleParsedRequest(mirror)` applies the Host policy before rewrites; `PendingExchange.mirror`
      lets the response leg stamp the attribution header and the provenance row.
      `persistMitmTlsSession`/`persistFailedMitmTlsSession` keep the CONNECT host:port.
    - Without FORCE_DECRYPT the MIRROR disposition stays an opaque relay to the mirror
      (`HttpProxyShared`/`GatewayEngine`, persisted as REDIRECTED) -- there is no TLS to re-point.
    - Tests: `MirrorStageTest` (8; all 27 policy combinations, SNI fallback, authority formatting,
      Host header application + trail, attribution), `MitmProxyEngineMirrorTest` (4, real sockets +
      requestr CA like `MitmProxyEngineLazyUpstreamTest`: client CONNECTs `app.example`, leaf CN per
      policy, mirror loopback server records SNI + Host per policy, bytes round-trip, captured row
      carries `x-requestr-mirror`; plus a direct `LazyUpstreamConnection(tlsServerName)` SNI check
      since SunJSSE omits server_name for single-label names like `localhost`).
  - S4 task 29 — done on `vorflux/interceptor-s4-rules`:
    - Stage code (`RewriteStage`/`MockStage`/`BodyCryptoStage`) landed with task 26; this task adds
      the dedicated suites: `RewriteStageTest` (23: no-op paths, ordered Set/Remove/Regex header
      mods with `$1`/`${name}` capture groups written back under the original key, query-param
      set/remove/encode with fragment kept, RegexBody + ReplaceBody text/empty/binary `file://`,
      broken fixture and invalid regex fail only their step, replacement before modifications,
      redirect url/host/Host + `preserveHost` + `directoryMatch`, multi-rule snapshot order with
      per-rule variables and `{{request.*}}` built-ins, wire-level Content-Length recompute and
      stale Content-Encoding/Transfer-Encoding dropped via the engine serializer),
      `MockStageTest` (10: lower-cased headers, JSON/text/octet-stream inference, UTF-8 byte
      length, templating with request built-ins, text/binary fixtures byte-exact, broken fixture
      -> empty body, serialized mock re-parses), `BodyCryptoStageTest` (12: scope gating, GCM
      IV+tag layout, cipher over the *inflated* gzip body with identity output, decrypt restores
      text + inflates gzip'd ciphertext, wrong key/garbage/blank alias/undecodable `br` fail
      closed leaving the message untouched, 502 failure response, two-rule round trip).
    - Hardening found by the tests: `BodyCryptoStage.applyWith` now catches *any* exception from
      the key provider / cipher (a locked AndroidKeyStore throws runtime exceptions) and returns
      `Failed` instead of propagating -- a crypto rule never forwards an untransformed body.
  - S4 task 30 — done on `vorflux/interceptor-s4-rules`:
    - `interception/rules/stages/TokenBucket.kt`: debt-based token bucket (`rateBytesPerSecond`,
      `capacityBytes` = rate/10 clamped to 1 KiB..256 KiB, injectable clock/sleep) and
      `ThrottledOutputStream` that slices writes into capacity-sized takes and flushes between them.
    - `interception/rules/stages/ThrottleStage.kt`: `NetworkConditionPresets` (GPRS / 2G / 3G / 4G /
      5G / custom, bits-per-second), `ThrottleStage.resolve` (explicit numbers win over `presetKey`),
      `Shaper` (downlink/uplink buckets + latency/jitter/loss override of the global dial condition),
      `shaperFor(rule, config, trail)` records APPLIED `throttle <desc>` or SKIPPED_PRECEDENCE for an
      all-zero config.
    - Wiring: `HttpProxyShared.handleConnectAttempt(throttle=)` wraps both `StructuredRelay.copy`
      legs and uses the shaper's dial condition for AllowOpaque/Redirect/Mirror;
      `MitmProxyEngine.handleClient` wraps `decryptedOutput` (client leg) and
      `LazyUpstreamConnection.wrapOutput` (upstream leg); `GatewayEngine` now goes through
      `ConnectControl.decide` so plaintext/opaque tunnels are shaped too;
      `NetworkCondition.downlinkBps/uplinkBps` added. The exchange trail also gets the throttle
      attribution so the persisted row shows it.
    - Tests: `ThrottleStageTest` (15 -- G7 ±20 % on a 10 MB virtual-clock download per preset, a
      real-clock 1 MB run, slicing, presets), `ThrottleRelayTest` (2 -- real loopback relay paced at
      4 Mbit/s within tolerance; uplink unaffected by a downlink-only shaper), `ChaosStageTest` (12).
  - S4 task 31 — done on `vorflux/interceptor-s4-rules`:
    - `interception/breakpoint/BreakpointRegistry.kt`: `Semaphore(MAX_PAUSED_FLOWS = 8)` acquired
      before pausing, `SLOT_WAIT_MS = 250` fail-open (`PauseResult.Skipped` -> the stage records
      SKIPPED_PRECEDENCE and forwards unchanged), one `CompletableFuture<BreakpointAction>` per flow
      awaited on the flow's own relay thread, mandatory auto-resume at `BreakpointConfig.timeoutMs`,
      `resume/replace/abort`, `invalidate(flowId)` + `invalidateConnection(key)` (client leg EOF and
      relay drain deadline), `resumeAll()` (kill switch / notification), `Listener` hooks.
      `BreakpointAction { Resume, Replace(message), Abort(reason), AutoResume }`.
    - `interception/breakpoint/BreakpointPersistence.kt`: mirrors the queue into
      `breakpoint_paused_flows` (`PausedFlowDao`; startup `deleteAll()` sweep; bounded snapshot).
    - `interception/rules/stages/BreakpointStage.kt`: phase gating (`REQUEST`/`RESPONSE`/`BOTH`),
      `pause(...)` -> `Forward(action, changed, actionName)` / `Aborted` / `Skipped`,
      `applyReplacement` (url/host/status/headers/body, `bodyReplaced = true` so the serializer
      recomputes Content-Length), `abortResponse` (499 `Aborted At Breakpoint`, `connection: close`),
      `stamp` = `x-requestr-breakpoint: rule="<name>"; action=<resumed|auto-resumed|replaced|aborted>`.
    - `MitmProxyEngine`: pause points after rewrite / before scripts on both legs; request-leg stamp is
      applied AFTER serialization (persisted row only, never leaks to the origin); response-leg stamp
      reaches the client; Abort short-circuits (request) or replaces the emitted response (response)
      and skips scripts/crypto/chaos; constructor seam `breakpoints: BreakpointRegistry`;
      `breakpointsEnabled` sampled from `InterceptorFlags.breakpoints` at `start()`.
    - `InterceptorFlags.breakpoints` (+ `scriptHost` for task 32), default true.
    - `RequestrVpnService`: health line shows `N paused at breakpoint`, notification gains a
      "Resume all" action (`ACTION_RESUME_ALL_BREAKPOINTS`) while anything is paused, `stopCapture`
      resumes everything and detaches the Room mirror.
    - Tests: `BreakpointRegistryTest` (9 -- slot exhaustion fail-open, auto-resume, Abort releases
      slot, invalidation completes exactly once, per-connection invalidation, resumeAll drains,
      Replace hand-off, listener counts, ordering), `BreakpointStageTest` (7),
      `MitmProxyEngineBreakpointTest` (6, real MITM tunnel -- G21: two concurrent flows with one
      paused, the other completes and the paused one auto-resumes with byte-identical forwarding;
      Replace with recomputed Content-Length; Abort answers 499 without dialling; response-phase
      breakpoint + rewrite + throttle in one decision; flag off; client close invalidates).
  - S4 task 32 — done on `vorflux/interceptor-s4-rules`:
    - `AndroidManifest.xml`: `.scripting.host.ScriptHostService` declared with
      `android:process=":scripthost"`, `android:exported="false"`.
    - `scripting/host/ScriptContract.kt`: Moshi envelopes (`ScriptRequestEnvelope { phase, flowId,
      scriptId, source, timeoutMs, maxInstructions, variables, shared, globals, message,
      responseTimeMs }`, `ScriptResponseEnvelope { ok, aborted, message, shared, globals, console,
      tests, error, pid }`, `MessageDto` with text `body` / base64 `bodyBase64` and
      `bodyTruncated` at `MAX_SCRIPT_BODY_BYTES` = 2 MiB), Messenger keys, and the one pure
      `execute(envelope)` both sides share (Rhino `optimizationLevel = -1`,
      `isJavaPrimitiveWrap = false` via `JsScriptEngine`). `MessageDto.applyTo` refuses a body
      mutation against a truncated body (fail-closed on the body, fail-open on the flow).
    - `scripting/host/ScriptHostService.kt`: `Messenger` over a `HandlerThread`, 4 worker threads,
      `MSG_HELLO` (pid) / `MSG_RUN` -> `MSG_RESULT` correlated by call id.
    - `scripting/host/ScriptHostClient.kt`: `Transport` seam (`MessengerTransport` binds lazily with
      `BIND_AUTO_CREATE`, correlates replies, unbinds + `Process.killProcess(remotePid)` on `kill`);
      per-call deadline = `timeoutMs` (ceiling 10 s) + 500 ms IPC grace; a missed deadline or dead
      binder kills the host and the flow continues unmodified with a FAILED audit record; circuit
      breaker opens after 3 consecutive host failures (`reset()` closes it); `killSwitch()`.
    - `ScriptStage`: `ScriptRunner.run(ScriptCall)` -> `ScriptOutcome(error, aborted)`;
      `apply(..., flowId, shared)` runs scripts in list order, a `null` return from
      `onRequest`/`onResponse` aborts the flow (`Result.abortedBy`, `abortResponse` = 499
      `Aborted By Script` with `x-requestr-script-error: aborted by script; rule="<name>"`);
      `HostRunner(client)` builds the envelope, applies the reply, writes `context.shared` back and
      mirrors `pm.globals`; the in-process runner stays as the fallback (flag `scriptHost` off).
    - `PmPrelude`: `context.shared` (seeded from `__bridge.sharedJson()`), `__phase`, and the
      `EPILOGUE` that dispatches `onRequest(context, request)` / `onResponse(context, response)`
      and reports `ABORT` for a `null` return. `JsScriptEngine.eval(..., epilogue)` returns the
      epilogue value in `EvalResult.value`. `PmBridgeBase` gains `phase`, `seedShared`,
      `sharedJson`, `sharedPut`, `sharedContext`.
    - `MitmProxyEngine`: `PendingExchange.scriptShared` + `flowId` thread the shared context from
      the request leg into the response leg; script abort short-circuits (request) or replaces the
      emitted response (response) and skips crypto/chaos; `start()` installs `HostRunner` when
      `InterceptorFlags.scriptHost`, `stop()` restores the in-process runner; constructor seam
      `scriptHostClient`.
    - `scripting/ScriptConsoleLog.kt`: bounded ring buffer (500) of `ScriptLogEntry(flowId,
      scriptId, level, message, backtrace, atMs)` fed by both runners.
    - G6: `interception/rules/bench/BenchmarkStats.kt` (nearest-rank percentiles) +
      `RuleSnapshotBenchmark` (200-rule mixed snapshot, 1,000 warm-up + 5,000 sampled).
      `RuleEvaluationBenchmarkTest` measured p95 = 0.038 ms on the dev box (budget 1 ms);
      androidTest `RuleEvaluationDeviceBenchmark` runs the identical harness on device.
    - Tests: `ScriptEnvelopesTest` (10 -- round trip, base64 binary byte-exact, truncation flag,
      execute mutations / untouched body / refused truncated mutation, lifecycle + shared, globals
      round trip, script error, runaway loop cut by the engine deadline), `ScriptHostTimeoutTest`
      (9 -- hanging host returns within the deadline and is killed, 10 s ceiling, breaker after 3,
      healthy call resets the count, kill switch, dead binder, real infinite loop, HostRunner
      fail-open / mutate + shared), `ScriptStageTest` (8 -- list order, shared request->response,
      phase gating, null aborts and stops the list, error stamps + continues, runner seam),
      `JsScriptEngineTest` +4 (epilogue), `MitmProxyEngineScriptTest` (3, real MITM tunnel -- G20:
      mutating lifecycle script byte-correct on both legs with shared context; hanging host cut at
      the deadline on both legs with the concurrent flow unaffected and FAILED audit records;
      aborting hook answers 499 without dialling the origin), `RuleEvaluationBenchmarkTest` (3).
  - S4 task 33 — done on `vorflux/interceptor-s4-rules`:
    - `interception/rules/RuleLimits.kt`: `MAX_ENABLED_RULES = 200`, `MAX_PAUSED_FLOWS = 8`,
      `MAX_REPLAY_CONCURRENCY = 255`, `MAX_SCRIPT_TIMEOUT_MS = 10_000`, `MAX_CHAOS_DELAY_MS = 30_000`,
      `MAX_BREAKPOINT_TIMEOUT_MS = 10 min`. `ChaosConfig.MAX_DELAY_MS`, `BreakpointRegistry.MAX_PAUSED_FLOWS`
      and `ScriptHostClient.MAX_TIMEOUT_MS` now alias these (RuleConfig no longer imports `RuleEnforcer`).
    - `interception/rules/RuleValidation.kt`: `validate(rule, enabledRules, acknowledged)` -> `Problem(field,
      message)` list (blank name, matcher errors, 201st enabled rule, mutating stage without the
      acknowledgement, per-config bounds: script timeout/instructions, chaos rates/delay, breakpoint
      timeout, throttle, mirror host); `clampForSnapshot(config)` is the compile-time backstop.
      `RuleSnapshotCompiler.compile` now excludes every enabled rule past the 200th (issue per rule)
      and clamps script/chaos/breakpoint configs (issue per clamp) so imports/direct writes cannot
      exceed the caps. Task 34 wires `validate` into the API/UI/import writers when they cut over.
    - `interception/rules/InterceptorSafetyGate.kt`: `Policy(acknowledged, restrictToSelection,
      selectedPackages)` kept current from `SettingsDataStore` by `bind(context, scope)` (called from
      `MitmProxyEngine.start`, released by `stop`); `allowMutation(appPackage, clientUid)` -> `Verdict`
      (unattributed flows are allowed -- the VPN tier already enforces the selection); `screen(decision,
      phase, trail, ...)` empties every mutating slot (`RuleDecision.observeOnly()`, new
      `RuleDecision.mutatingRules`) and records `REFUSED_SAFETY` per mutating rule; `disableAllMutatingRules
      (dao, breakpoints, scriptHost)` = `InterceptorRuleDao.disableStages(MUTATING)` + `BreakpointRegistry.
      resumeAll()` + `ScriptHostClient.killSwitch()`. Fail-open/fail-closed per stage is declared in the
      class doc (GATEWAY and BODY_CRYPTO fail-closed; MIRROR/REWRITE/SCRIPT/THROTTLE/CHAOS/BREAKPOINT
      fail-open; MOCK fails open to origin only when the fixture cannot be built).
    - `interception/rules/ReplayPermits.kt`: `Semaphore(255)` the S5 replay queue takes before dialling.
    - `MitmProxyEngine`: constructor seam `safetyGate` (default `InterceptorSafetyGate.shared`);
      `clientUidForSafety` resolves the uid once per connection (only when the policy restricts) and
      threads it `handleClient -> inspectClientToServer -> handleParsedRequest(clientUid)`; both legs
      screen the decision before any stage runs.
    - `SettingsDataStore` / `UnifiedDataStore`: `MUTATING_RULES_ACKNOWLEDGED` + `MUTATING_RULES_ACTIVE`
      keys, `mutatingRulesAcknowledged` / `mutatingRulesActive` flows and setters.
    - `RequestrVpnService`: `ACTION_DISABLE_MUTATING_RULES` notification action ("Disable mutating rules")
      shown while any mutating rule is enabled; the health line says "N mutating rules MODIFYING TRAFFIC";
      `startMutatingRulesWatch` observes the DAO and mirrors the state into `mutatingRulesActive`.
    - `SettingsScreen`: "TRAFFIC MODIFICATION (DEVELOPER MODE)" card -- acknowledgement switch, warning
      while a mutating rule is enabled, and the same kill switch as the notification.
    - Tests: `RuleValidationTest` (7), `InterceptorSafetyGateTest` (14 -- unacknowledged mutating rule
      fails validation for every mutating stage while observe/gateway/mirror/throttle pass; runtime
      backstop refuses without the acknowledgement; out-of-selection package observed but never
      mutated with one REFUSED_SAFETY record per mutating rule and gateway/throttle/observe intact;
      selected/unattributed flows untouched; uid -> packages incl. shared uids; kill switch against
      Room disables exactly the 6 mutating stages, resumes 3 paused flows, kills the host, leaves the
      4 others enabled; engine-level: refused MOCK dials upstream while the same snapshot mocks a
      selected uid, refused response rewrite forwards origin bytes with REFUSED_SAFETY provenance;
      201st rule rejected + excluded, 9th pause refused, 256th replay permit refused, 11 s script
      timeout rejected, constants aliased).
  - S4 task 34 — done on `vorflux/interceptor-s4-rules`:
    - Room v24 (`RequestrDatabase.SCHEMA_VERSION = 24`, `ALL_MIGRATIONS` = 16 entries): `Migration23To24.kt`
      re-runs `Migration22To23.backfill` (idempotent by `legacyRuleId`, carries any straggler rows an install
      parked on v23 still wrote) and then `DROP TABLE IF EXISTS intercept_rules`. `InterceptRuleEntity`,
      `InterceptRuleDao`, the `RuleAction` Room enum and its type converters are gone from `data/local`;
      `app/schemas/.../24.json` committed, schema-drift check clean.
    - `interception/rules/LegacyInterceptRule.kt`: non-Room DTO (`LegacyInterceptRule` + `LegacyRuleAction`
      with `fromNameOrLogOnly`/`fromNameOrNull`, `toJson()`, `isLegacyShape(json)`, `fromJson`) -- the ONLY
      remaining v1 vocabulary, used as converter input by the v23 backfill, v1 workspace imports, v1 flat
      API bodies and the (still flat) quick-add form.
    - `interception/rules/RuleApiJson.kt`: the `schemaVersion: 2` envelope `{schemaVersion, rules[], folders[]}`
      shared by `LocalApiServer`, `LanDashboardServer` and `WorkspaceExporter`; `parseNewRule` accepts a v1 flat
      body (→ `LegacyRuleConverter`) or a typed body; `applyUpdate` merges partial PUTs for both dialects.
      Each rule JSON also carries the v1 projection (`domain`/`pattern`/`action`/`replacementValue`,
      `LegacyRuleProjection.kt`) so the MV3 extension (`channel.mjs` already unwraps `json.rules`) and the LAN
      dashboard page keep working; stages with no v1 equivalent project `action = <stage>` (fail-open no-op
      for v1 readers).
    - `LocalApiServer` GET/POST/PUT/DELETE `/api/rules` and `LanDashboardServer` `/dash/rules` cut over to
      `InterceptorRuleDao` + `RuleApiJson` (sortOrder = max + 1, `RuleValidation` with `countEnabled`);
      dashboard JS unwraps the envelope and shows the stage.
    - `WorkspaceExporter`: exports `schemaVersion: 2`, `interceptorRules` (every rule, typed) + `ruleFolders`;
      import accepts v2 (folders first, `folderId` remapped) and v1 (`interceptRules` → `LegacyRuleConverter`);
      the crypto-alias import gate now keys off `BodyCryptoConfig.keyAlias` (imported disabled + warning);
      `ImportResult.foldersImported` added (defaulted, summary says "in N folders").
    - `CaptureLogRepository`: `observeHasBlockRule`/`hasBlockRule` → `InterceptorRuleDao.*HasBlockRuleForDomain`;
      `addBlockRule`, `addRuleFromTransaction(stage = REWRITE)` and `seedDefaultRules` write typed rules
      (GATEWAY/BLOCK with `*domain*` + escaped-URL matcher, OBSERVE for the log seeds) -- identical to what the
      v23 backfill produces for the old defaults.
    - UI: dead `data/models/AppModels.kt` `RuleAction`/`TrafficRuleItem` and `TrafficInterceptorRepository`'s
      in-memory rule list deleted. `RulesConfigBottomSheet` lists `interceptor_rules` (stage badge, matcher
      summary, config detail, NO EFFECT via `RuleValidation`; new `ui/screens/TypedRuleUiHelpers.kt`); its
      quick-add form stays flat and saves through `LegacyRuleConverter` until sub-plan task 16 lands the typed
      editors. `CapturedTrafficHelpers`/`ReplacementValueUiHelpers`/`RuleTemplateUiHelpers` take
      `LegacyRuleAction`.
    - `RuleEnforcer.kt` removed from `app/src/main`; a frozen copy (minus the DB readers) lives at
      `app/src/test/java/com/example/interception/RuleEnforcer.kt` as the G16 parity oracle used by
      `LegacyParityTest`/`CompiledMatcherTest`. The six `RuleEnforcer*Test` classes were deleted -- their
      behaviours are covered by the `rules/stages/*StageTest` suites and the parity test.
    - Tests: `MigrationRulesDropLegacyTest` (4: v18 → 24 in one hop with schema validation and no data loss,
      straggler carry without duplicates, empty drop, Room opens a v18 file at v24 and `hasBlockRuleForDomain`
      answers); `LocalApiServerObserveAndRulesTest` (+2: v1 flat and v2 typed POST both come back typed; partial
      PUT toggles, DELETE removes, 404 after); `WorkspaceExporterTest` (+2: typed field-drift round trip, v1 file
      imports identically to the v23 migration, folders round-trip and re-file); `RequestrDatabaseBuilderTest`/
      `RecoverySemanticsTest` pins bumped to v24/16 migrations; engine tests seed through the test-only
      `LegacyRuleTestDao` (`db.legacyRuleDao()` in `LegacyRuleTestSupport.kt`) which converts and writes to
      `interceptor_rules`.
  - S4 task 35 — done on `vorflux/interceptor-s4-rules`:
    - `Http2StreamInterceptor` (new seam in `interception/http`): `plan(streamId, headers)` returns `null` for
      frame-by-frame passthrough (observe-only, throttle-only, safety-refused, empty decision -- gRPC/SSE keep
      streaming) or a `Plan` whose `onRequest` (per-connection single-thread rule executor, never the client
      reader) yields `Forward(request)`/`Respond(response)` and whose `onResponse` (upstream reader thread) rewrites
      the whole origin message. `Plan.interceptsResponse` keeps request-only work from buffering the response.
    - `Http2ProxyConnection`: held streams are collected as `InterceptedStream` (FlowBuffer, cap →
      `RST ENHANCE_YOUR_CALM` + `onAbandoned`), re-framed with fresh HEADERS/DATA/trailers toward either
      upstream; client↔upstream stream-id mapping (`upstreamIds`/`clientIds`, `openUpstream`/`forgetUpstream`)
      so a stream released after a later passthrough stream gets a fresh, monotonically increasing upstream id
      (ids coincide when nothing is intercepted); `Http1UpstreamBridge.submit(streamId, request, responseHook)`
      collects the h1 response before the hook runs; client RST/upstream RST/connection close abandon the plan.
    - `Http2Messages` (new codec): `request`/`response` (moved from `Http2Session`), `encodeRequest`/
      `encodeResponse` -- `reconcile` keeps untouched repeated fields, collapses edited names, drops removed,
      appends new lower-cased; recomputes `content-length` from the framed body after every mutation; drops
      connection-specific fields; a rule-set `Host` becomes `:authority` while redirects with preserveHost keep
      the original authority; bodyless responses (HEAD/204/304/1xx) keep the declared length and frame no body.
    - `Http2RuleInterceptor` (new): evaluates `RulePipeline` + `InterceptorSafetyGate` on the probe request,
      drives `MitmProxyEngine.runRequestStages`/`runResponseStages` (extracted from `handleParsedRequest`/
      `handleParsedResponse` so h1 and h2 share one stage sequence: gateway, mirror Host, rewrite, breakpoint,
      script, mock, body-crypto, chaos, throttle attribution; response: mirror stamp, rewrite, breakpoint, script,
      body-crypto fail-closed, chaos delay) and hands the engine a per-stream `Record` (audit trail, decision,
      rules' request view, `suppressed` for BLOCK without `showBlockedInList`) consumed by the h2 branch's
      `Http2Session.onTransaction`/`emitGrpcCallEnd(appliedRules)`. Breakpoints on h2 use the connection key
      `h2-<id>` and are invalidated when the client leg closes.
    - `RewriteStage.SetHeader`/`RemoveHeader` now match header names case-insensitively as their KDoc promised
      (h1 maps are lower-cased, h2 keeps wire casing; the h2 parity test caught the exact-key removal).
    - Tests: `Http2MessagesTest` (12), `Http2ProxyConnectionRulesTest` (7: Respond without dialing, Forward with
      recomputed Content-Length over h1, h1 response hook over chunked origin, held-stream id remap over h2,
      h2 response hook with DATA+trailers, client reset abandons, throwing plan → RST INTERNAL_ERROR),
      `MitmProxyEngineH2RulesTest` (6, Robolectric, real engine: BLOCK 403 not persisted / persisted with trail
      when `showBlockedInList`, MOCK body + content-length, REWRITE request+response re-framed and persisted
      post-rule, safety-gate refusal leaves the flow untouched with `REFUSED_SAFETY`, unmatched → passthrough);
      shared fixtures in `http/Http2TestOrigins.kt` (`H2ClientRecorder`, `FakeH1Origin`, `FakeH2Origin`,
      `H2ProxyHarness`). No observe-only h2 forwarding remains: every terminated h2 stream goes through `plan()`.

## Program status — 2026-09-06

  - **S0-S4 COMPLETE on `main`.** S4 (tasks 24-35) merged as
    [PR #23](https://github.com/Mahesh953-hub/requestr/pull/23) (branch `vorflux/interceptor-s4-rules`,
    merge commit `c47241c`). Earlier slices: S0 PR #18, S1 PRs #19/#20, S2 PR #21, S3 PR #22.
  - **Next: S5** (tasks 36-41 + 58-62 — HAR, snippets, PCAP/PCAPng export, replay queue + diff,
    Report Server + envelope v2 remainder, API-Testr protocol preference + Cronet H3 + cURL
    serialization + one-click repeat). Read **`vorflux-handoff/START-HERE-s5.md`** first; branch
    from `main` as `vorflux/interceptor-s5-export-replay`.
  - Note for task 41: the typed **v2 rule envelope** half already shipped in S4 task 34
    (`RuleApiJson` v2 with v1 projection on `LocalApiServer`, `LanDashboardServer`,
    `WorkspaceExporter`); only the Report Server and remaining non-rule envelope fields are left.
  - Note for task 31 (done): implemented as `BreakpointRegistry`/`BreakpointStage`/
    `BreakpointPersistence` under `interception/breakpoint/`, not a `BreakpointEngine.kt`.
  - Test baseline after PR #23: 2,644 unit tests; only known flake is `ToolsRepositoryRoomTest`
    (3 s timing, passes in isolation).
