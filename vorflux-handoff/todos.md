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
