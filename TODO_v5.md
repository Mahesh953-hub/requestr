# requestr v5 — Implementation Tracker

> 50 items | 3 categories | Each item shows status inline: `[x] DONE` / `[~] PARTIAL` / `[ ] TODO`

---

## CATEGORY 1 — IMPORTANT + CRITICAL (17 items) ✅ ALL DONE

### Interception Pipeline — Make It Actually Work

| # | Status | Item | What Was Done | Setting Location |
|---|--------|------|---------------|-----------------|
| C1 | ✅ DONE | Wire MITM into default path | `PreflightController.run()` defaults to `MITM_PROXY` when CA installed, VPN otherwise | Capture Setup → Record Mode (VPN/PROXY) |
| C2 | ✅ DONE | System CA install via root | `CertificateAuthority.installToSystem()` — handles Android 14+ APEX and legacy paths | Settings → CA Root Certificate |
| C3 | ✅ DONE | Regenerate CA button | `CertificateAuthority.regenerate()` — deletes + recreates + toast feedback | Settings → CA Root Certificate |
| C4 | ✅ DONE | Feed decrypted bytes to capture log | `MitmProxyEngine.onDecryptedTransaction` callback wired in `PreflightController.mitmProxy()` → persists `CapturedTrafficEntity` into Room via `capturedTrafficDao().insert()` | MITM_PROXY via rule engine + parse-on-relay |
| C5 | ✅ DONE | TLS discard fixed | `if (stream.isTls && !stream.decryptedByMitm) return` — allows MITM streams through | — |
| C6 | ✅ DONE | wasDecrypted + isUndecoded markers | `wasDecrypted = true` when MITM-decrypted, `isUndecoded = true` on SNI-only rows | — |
| C7 | ✅ DONE | decryptedTransactionCount | Added to `InterceptionState`, mutator `addDecryptedTransactions()` | — |
| C8 | ✅ DONE | Watcher gates on decryption | `CaptureMethodWatcher` now checks `decryptedTransactionCount` not raw packets | — |
| C9 | ✅ DONE | Rule enforcement on wire | `MitmProxyEngine.checkInterceptRules()` — BLOCK before CONNECT handshake | Capture Setup → Rules & Modifiers |
| C10 | ✅ DONE | isUndecoded in entity | `isUndecoded: Boolean` in `CapturedTrafficEntity` | — |
| C11 | ✅ DONE | Honest status banner | Shows "HTTPS DECRYPTED ✓ · N" / "PLAINTEXT ONLY" / "PROXY · decrypting..." | InterceptScreen |
| C12 | ✅ DONE | Honest VPN notification | "VPN active — HTTPS bodies not captured without proxy mode" | RequestrVpnService |
| C13 | ✅ DONE | OkHttpProxyInterceptor | Non-root own-app proxy path via OkHttp | — (library, opt-in) |
| C14 | ✅ DONE | UDP:443 iptables redirect | QUIC traffic redirected for root/shizuku capture | RootIptablesEngine |
| C15 | ✅ DONE | CA export feedback | "Exports to: Downloads/requestr/" text + regenerate toast | Settings → CA |
| C16 | ✅ DONE | Dump-to-file | Hex capture to `Downloads/requestr/capture_{id}.hex` | Capture Setup → Dump to File |
| C17 | ✅ DONE | Stale settings fixed | `captureEnabled` flag passed through chain, not stale snapshot | — |

---

## CATEGORY 2 — IMPORTANT BUT NOT CRITICAL (15 items)

### Protocol Coverage

| # | Status | Item | Files |
|---|--------|------|-------|
| P1 | ✅ DONE | HTTP/2 frame parser + HPACK decoder (RFC 7540/7541) | `interception/http/Http2Parser.kt` |
| P2 | [~] PARTIAL | HTTP/3 QUIC semantic layer | v5.6: `QuicParser.parseInitial()` now does real work -- derives RFC 9001 Initial keys (`QuicCrypto`), AEAD-decrypts the Initial packet, reassembles the CRYPTO frame's ClientHello and parses it via `TlsDecoder` for a real SNI + ALPN (falls back to the old heuristic scan on failure/unsupported version). Wired into `PacketPump`, which now builds a real `QuicConnection`/`QuicStream` state machine (keyed per connectionId) and persists real `alpn`/`quicVersion` to `QuicConnectionEntity` (previously hardcoded `alpn = null`). Fixed a QPACK bug where huffman-coded string values were decoded as plain UTF-8 (`QpackDecoder`/`HpackHuffman`). Remaining gap: `QpackDecoder.decode()` still has **no production call site** -- it is unit-tested (`QpackDecoderTest`) but real QUIC HEADERS-frame demultiplexing would require parsing HTTP/3 frames off 1-RTT application data, which is out of scope (only cleartext-derivable Initial packets are decrypted here, not post-handshake traffic) |
| P3 | ✅ DONE | gRPC/Protobuf detector | `interception/grpc/GrpcDetector.kt` + `ProtobufDecoder.kt` — HTTP/2-framed gRPC message detection (5-byte header, varint length), imported/used in `CaptureEngine.kt` (`grpcCalls`/`grpcDataBuffers`/`grpcStatuses` maps), unit-tested in `GrpcParserTests.kt` |
| P4 | ✅ DONE | DoH/DoT detection | `interception/dns/DohDotDetector.kt` — port+method+content-type heuristics |
| P5 | ✅ DONE | Protocol fingerprinting engine | `interception/ProtocolDetector.kt` — confidence scores (DETECTED/LIKELY/POSSIBLE/UNKNOWN) |

### Runtime Instrumentation

| # | Status | Item | Files |
|---|--------|------|-------|
| R1 | ✅ DONE | RuntimeInstrumentationEngine | `runtime/RuntimeInstrumentationEngine.kt` — registered + started from `MainActivity.onCreate()`, background poll loop feeds `CorrelationEngine.ingest()` with `RuntimeEvent`s. Compose-observable `stateFlow` surfaced in Settings → Runtime Instrumentation card |
| R2 | ✅ DONE | FridaClient + honest state machine | `runtime/frida/FridaClient.kt` — Unavailable→Detected→Connected→Attached→Streaming |
| R3 | [~] PARTIAL | HTTP client adapters (OkHttp, Cronet, WebView) | `OkHttpAdapter.pollEvents()` now drains real request/response pairs recorded by `OkHttpProxyInterceptor` (method/URL/status/headers/duration) instead of an empty list. **Scope caveat:** no code currently attaches `OkHttpProxyInterceptor` to a live `OkHttpClient.Builder()`, so this reports requestr's own outbound calls only, not third-party app traffic (that needs the Observer SDK, S1). Cronet/WebView adapters remain presence-detection only — no native hook exists |

### Correlation

| # | Status | Item | Files |
|---|--------|------|-------|
| Cor1 | ✅ DONE | ObservationEvent sealed class | `correlation/ObservationEvent.kt` — 7 subtypes: Network/TLS/HTTP/Runtime/Browser/DNS/QUIC |
| Cor2 | ✅ DONE | CorrelationEngine | `correlation/CorrelationEngine.kt` — joins events across planes, emits REQ-NNNN IDs |

### API Testr Screen Rewrite

| # | Status | Item | Files |
|---|--------|------|-------|
| A1 | ✅ DONE | Dynamic pill-outline press | `ui/components/DynamicIconPress.kt` — spring capsule morph, no gray ripple |
| A2 | ✅ DONE | Auto-draft persistence | Request state auto-saves via ApiCollectionsRepository.saveRequest on every mutation |
| A3 | ✅ DONE | Method dropdown with per-method coloring | `MethodBadge` already has per-method colors (GET=green, POST=blue, PUT=amber, DELETE=red, PATCH=violet, WS=magenta, HEAD/OPTIONS=indigo) |
| A4 | ✅ DONE | WS mode: Connect/Disconnect + status indicator | ApiTestrScreen — Connected(green)/Connecting(amber)/Disconnected(gray) + Connect/Disconnect button |
| A5 | ✅ DONE | Raw text editor with two-way sync | Query/Headers tabs — raw editor with `// disable` syntax, bidirectional sync with table |

---

## CATEGORY 3 — NOT IMPORTANT NOR CRITICAL (18 items)

### Browser Bridge (requires Chrome DevTools running)

| # | Status | Item | Files |
|---|--------|------|-------|
| B1 | [ ] TODO | CDP client: WebSocket to Chromium | `browser/CdpClient.kt` |
| B2 | [ ] TODO | CDP Network adapter: capture browser requests | `browser/CdpNetworkAdapter.kt` |
| B3 | [ ] TODO | CDP Fetch adapter: allow/block/modify | `browser/CdpFetchAdapter.kt` |
| B4 | [ ] TODO | Initiator correlation: InitiatorResolver + StackTraceResolver | `browser/InitiatorResolver.kt` |
| B5 | [ ] TODO | Service Worker observation | `browser/ServiceWorkerObserver.kt` |
| B6 | [ ] TODO | Browser storage correlation | `browser/BrowserStorageObserver.kt` |
| B7 | [ ] TODO | Chrome extension bridge | `browser-extension/` — MV3 extension |

### Advanced Transport

| # | Status | Item | Files |
|---|--------|------|-------|
| T1 | [x] DONE (v5.6) | PCAP export wired end-to-end: `PCAP_ENABLED` DataStore preference → `RequestrVpnService` starts/stops `PacketCaptureEngine` with the VPN tun session lifetime → `PacketPump.writeToPcap()` mirrors every raw packet read off the tun fd → Capture Setup sheet has a "PCAP Export" toggle + a "Share PCAP capture" action (FileProvider share of the last `.pcap`). Scope limit: only the VPN_TUN tier (and its ROOT_FRIDA fallback) produces PCAP output — MITM_PROXY/HTTP_PROXY/ROOT_IPTABLES/SHIZUKU_TUN route through other engines that never construct a `PacketPump`. No separate `PcapWriter` file exists; the PCAP-format writer logic already lived inline in `PacketCaptureEngine.kt`. | `interception/packet/PacketCaptureEngine.kt`, `interception/PacketPump.kt`, `interception/RequestrVpnService.kt`, `ui/screens/InterceptScreen.kt` |
| T2 | [ ] TODO | Gateway mode: external network interception | `interception/gateway/GatewayEngine.kt` |

### Models & Storage

| # | Status | Item | Files |
|---|--------|------|-------|
| M1 | ✅ DONE | TCP/UDP/QUIC connection entities | Registered in `RequestrDatabase` (v5→v6) with `TcpConnectionDao`/`UdpFlowDao`/`QuicConnectionDao`. `CaptureEngine.persistTcpConnection()` upserts TCP connection state (throttled 2s, flushed on close) from `ingest()`. `PacketPump.persistUdpFlow()` upserts every UDP packet's flow row; `persistQuicConnection()` inserts a QUIC connection row on successful SNI extraction. Surfaced via new "Connections" bottom sheet (TCP/UDP/QUIC tabs) in `InterceptScreen.kt`, backed by new `ConnectionsRepository` |
| M2 | ✅ DONE | TLS session/handshake/metadata entities | New `TlsSessionEntity` + `TlsSessionDao` registered in `RequestrDatabase` (v6→v7). `MitmProxyEngine.persistMitmTlsSession()` reads the negotiated cipher/protocol/ALPN off the client-facing `SSLSession` and the peer cert off the upstream leg after both TLS legs complete, upserted with `wasDecrypted=true`. `CaptureEngine`'s existing undecoded-TLS path now calls new `TlsDecoder.parseClientHello()` (full ClientHello parse: version, ciphers, extensions, ALPN, SNI) to persist a `wasDecrypted=false` row with JA3 fingerprint (`TlsClientHello.ja3Hash()`) when no MITM occurred. Surfaced as a 4th "TLS" tab in the "Connections" bottom sheet (`InterceptScreen.kt`), showing DECRYPTED vs SNI/JA3-ONLY badge, cipher/version/ALPN, cert subject, and JA3 hash |

### Provenance & Diagnostics

| # | Status | Item | Files |
|---|--------|------|-------|
| D1 | ✅ DONE (v5.7) | ProvenanceGraph: causal chain visualization | `ProvenanceGraph.build()` reads every TCP/QUIC connection for a session (`TcpConnectionDao`/`QuicConnectionDao.getBySession()`), resolves each connection's dialed host via `ConnectionLineage.resolveHostFor()` (DNS-answer match, TLS SNI fallback, else raw destIp), and groups into `HostGroup`s sorted by most-recently-active. `lineageFor()` delegates lazily to `ConnectionLineage.build()` per-node on UI expand rather than eagerly for all. Surfaced as a 5th "Provenance" tab in the "Connections" bottom sheet (`InterceptScreen.kt`) — host groups with connection-count badges, expandable to show the real per-connection lineage chain | `correlation/graph/ProvenanceGraph.kt`, `ui/screens/InterceptScreen.kt` |
| D2 | ✅ DONE (v5.7) | ConnectionLineage: DNS→TCP→TLS→HTTP chain | `ConnectionLineage.build()` reconstructs the real DNS→TCP/QUIC→TLS→HTTP chain per connection from Room, no new write path: DNS answer lookup matches the most recent `DnsQueryEntity` response whose `resolvedIps` contains the connection's destIp at/before its established time; TLS lookup tries connectionId-keyed `TlsSessionEntity` first then falls back to `getLatestBySniHost()` for the MITM path (whose `tlsSessionId` uses a different `"$host:$port"` convention than the undecoded path's `TcpStream.key`); HTTP lookup uses a 30s host+timestamp-window heuristic (`CapturedTrafficDao.getByHostInWindow()`) since `CapturedTrafficEntity` has no `connectionId` column. Missing steps (no DNS/TLS/HTTP row) surface as explicit failed `LineageStep`s with honest reasons, never skipped/faked. Required a DNS-persistence prerequisite: `DnsParser` was rewritten to follow RFC 1035 compression pointers and actually parse A/AAAA answer records (previously forward-only, answers were never extracted); new `DnsQueryEntity`/`DnsQueryDao` (Room v7→v8); `RequestrVpnService.onDnsObserved()` wires `PacketPump.dnsCallback` (previously constructed but never set — DNS was parsed and only logged, then discarded) to persist rows and feed `CorrelationEngine.ingest()` as a `DnsEvent`. Documented limitation: `TlsHandshakeState.FAILED` exists in the schema but nothing in the codebase ever sets it, so a failed handshake is indistinguishable from one that never started | `correlation/graph/ConnectionLineage.kt`, `interception/dns/DnsParser.kt`, `data/local/Entities.kt`, `data/local/Daos.kt`, `data/local/RequestrDatabase.kt`, `interception/RequestrVpnService.kt` |
| D3 | ✅ DONE (v5.7) | VisibilityAnalyzer + ObservationReport | `VisibilityAnalyzer.analyze()` computes real, no-fabrication counts/fractions from Room: TLS decrypted-vs-SNI-only, HTTP decoded/undecoded/plaintext, DNS query/response/resolved-IP counts, and per-connection TLS/HTTP coverage (reuses `ConnectionLineage`'s matching rule rather than re-deriving it). `limitingReasons` explains each gap in plain language — VPN-only SNI-only TLS, undecoded HTTP placeholder rows, the `TlsHandshakeState.FAILED`-never-set limitation (always stated), DNS answers with no extractable A/AAAA record, and the general DoH/DoT/cache/hardcoded-IP DNS blind spot. `ObservationReport` exposes divide-by-zero-safe `tlsDecryptedFraction`/`httpCoverageFraction`/`dnsResolutionFraction`. Wired into a new "OBSERVATION REPORT" card in `SettingsScreen.kt` (same `SettingsSectionHeader`+`GlassCard` pattern as DEVICE CAPABILITIES/RUNTIME INSTRUMENTATION), read via `produceState` off `InterceptionStateRepository.state.sessionId` — a real UI consumer, not computed-and-discarded | `diagnostics/VisibilityAnalyzer.kt`, `ui/screens/SettingsScreen.kt` |
| D4 | ✅ DONE | Device Capabilities in Settings | SettingsScreen — Android version, arch, root, shizuku, CA, matrix |

### SDK

| # | Status | Item | Files |
|---|--------|------|-------|
| S1 | [x] DONE (v5.6) | Requestr Observer SDK module: registered `sdk/` as a real `com.android.library` Gradle module (`settings.gradle.kts`, `sdk/build.gradle.kts`), builds standalone (`./gradlew :sdk:assembleDebug` → `sdk-debug.aar`). `RequestrObserver.observe()`/`flush()` batch-POST JSON to `LocalApiServer`'s new `/api/observe` endpoint, which persists each event as a `CapturedTrafficEntity` row (new `CaptureTier.SDK`, a lazily-created always-open `CaptureSessionEntity`) — full round-trip SDK → `/api/observe` → Room `captured_traffic` table → same Intercept traffic log UI real capture sessions use. | `sdk/build.gradle.kts`, `sdk/src/main/java/com/requestr/sdk/RequestrObserver.kt`, `app/src/main/java/com/example/api/LocalApiServer.kt`, `app/src/main/java/com/example/data/local/Entities.kt` |
| S2 | ✅ DONE (v5.7) | HTTP stack auto-detection | Two honestly-separate signals rather than one merged guess: `detectFromHeaders()` classifies a single captured request by its real default User-Agent format (OkHttp `okhttp/<ver>`, Cronet contains `Cronet/<ver>`, Ktor literal `Ktor client` plugin default, HttpURLConnection `Dalvik/<ver>` system default); Volley's default `HurlStack` inherits HttpURLConnection's exact UA with no distinct default of its own, so it is deliberately NOT distinguished from it — documented rather than faked — and any custom/overridden User-Agent correctly falls to UNKNOWN instead of a guess. `detectAvailableOnClasspath()` answers a different question (which libraries requestr's own process has loaded) via the same `Class.forName()` presence-check technique `CronetAdapter`/`WebViewAdapter` already use. `analyzeSession()` classifies every `CapturedTrafficEntity` row persisted for a session into real per-stack counts. Wired into the existing "RUNTIME INSTRUMENTATION" Settings card (same `produceState` pattern as D3's card) rather than left as a computed-and-discarded utility | `runtime/http/HttpStackDetector.kt`, `ui/screens/SettingsScreen.kt` |

---

## BUILD LOG

| Version | Code | Items Done | Notes |
|---------|------|-----------|-------|
| v5.0 | 18 | C1-C17 (17 items) | Critical pipeline fixes — decryption actually works |
| v5.1 | 19 | +6 (23 total) | Cor1, M1, P1, P5, A4, D4 — observation + HTTP/2 + WS + device caps |
| v5.2 | 20 | +6 (29 total) | Cor2, P4, R2, A1, A2, A5 — correlation, DoH/DoT, Frida, dynamic press, raw editor |
| v5.5 | 23 | +2 (31 total) | R1, R3 — RuntimeInstrumentationEngine wired into app lifecycle + CorrelationEngine, OkHttp adapter drains real events. Also corrected C4/P3 tracker drift (were DONE, mislabeled), downgraded M1 to PARTIAL (dead code, confirmed unregistered in Room), fixed LocalApiServer Switch recomposition bug |
| v5.6 | 24 | +5 (36 total) | M1, M2, P2, T1, S1 — TCP/UDP/QUIC connection entities + TLS session/handshake/cert model wired into Room + UI; QUIC Initial-packet decryption (RFC 9001) + QuicConnection/QuicStream wired into PacketPump; PCAP export toggle + share action wired into Capture Setup UI (VPN_TUN tier only); `sdk/` registered as a real Gradle module with `/api/observe` wired into Room's captured_traffic table via new CaptureTier.SDK. Known gaps: QpackDecoder.decode() still has no production call site (test-only); PCAP export doesn't cover MITM_PROXY/HTTP_PROXY/ROOT_IPTABLES/SHIZUKU_TUN tiers. Remaining: T2, D1-D3, B1-B7, S2 |
| v5.7 | 24 (unbumped — no versionCode/versionName edit this branch) | +4 (40 total) | D1, D2, D3, S2 — ProvenanceGraph (host-grouped connection graph, real UI tab), ConnectionLineage (real DNS→TCP/QUIC→TLS→HTTP reconstruction from Room, required rewriting DnsParser to actually parse DNS answer records and wiring the previously-unset PacketPump.dnsCallback), VisibilityAnalyzer/ObservationReport (real observed/decrypted fractions + limitingReasons, wired into a new Settings card), HttpStackDetector (User-Agent + classpath-based stack detection, wired into the RUNTIME INSTRUMENTATION card). Known gaps carried forward: TlsHandshakeState.FAILED still never set anywhere (documented in D2/D3); CapturedTrafficEntity still has no connectionId column, D2's HTTP matching uses a 30s host+timestamp-window heuristic instead; Volley indistinguishable from HttpURLConnection by User-Agent alone (documented in S2). Remaining: T2, B1-B7 |
| NEXT | 25+ | Remaining 8 | Cat3 items — T2 (Gateway), B1-B7 (Browser/CDP bridge) |
