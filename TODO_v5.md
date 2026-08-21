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
| P2 | [~] PARTIAL | HTTP/3 QUIC semantic layer | Only `QuicParser.extractSni()` is wired (called from `PacketPump`). `QuicConnection`/`QuicStream`/`QpackDecoder` (session state machine + RFC 9204 QPACK static table) exist but have zero call sites anywhere in `app/src/main/java` outside their own files — still SNI-only, no real session/frame parsing |
| P3 | ✅ DONE | gRPC/Protobuf detector | `interception/grpc/GrpcDetector.kt` + `ProtobufDecoder.kt` — HTTP/2-framed gRPC message detection (5-byte header, varint length), imported/used in `CaptureEngine.kt` (`grpcCalls`/`grpcDataBuffers`/`grpcStatuses` maps), unit-tested in `GrpcParserTests.kt` |
| P4 | ✅ DONE | DoH/DoT detection | `interception/dns/DohDotDetector.kt` — port+method+content-type heuristics |
| P5 | ✅ DONE | Protocol fingerprinting engine | `interception/ProtocolDetector.kt` — confidence scores (DETECTED/LIKELY/POSSIBLE/UNKNOWN) |

### Runtime Instrumentation

| # | Status | Item | Files |
|---|--------|------|-------|
| R1 | [ ] TODO | RuntimeInstrumentationEngine | `runtime/RuntimeInstrumentationEngine.kt` — adapter pattern for Frida/Xposed/SDK |
| R2 | ✅ DONE | FridaClient + honest state machine | `runtime/frida/FridaClient.kt` — Unavailable→Detected→Connected→Attached→Streaming |
| R3 | [ ] TODO | HTTP client adapters (OkHttp, Cronet, WebView) | `runtime/http/OkHttpAdapter.kt` etc. — needs native bindings |

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
| T1 | [ ] TODO | PCAP subsystem: PacketCaptureEngine + PcapWriter | `interception/packet/PacketCaptureEngine.kt` |
| T2 | [ ] TODO | Gateway mode: external network interception | `interception/gateway/GatewayEngine.kt` |

### Models & Storage

| # | Status | Item | Files |
|---|--------|------|-------|
| M1 | [~] PARTIAL | TCP/UDP/QUIC connection entities | `Entities.kt` — `TcpConnectionEntity`, `UdpFlowEntity`, `QuicConnectionEntity` are defined but **not registered** in `RequestrDatabase`'s `entities = [...]` list, have no DAOs, and have zero constructor call sites anywhere — dead code, not actually persisted |
| M2 | [ ] TODO | TLS session/handshake/metadata entities | `interception/tls/TlsSession.kt` etc. |

### Provenance & Diagnostics

| # | Status | Item | Files |
|---|--------|------|-------|
| D1 | [ ] TODO | ProvenanceGraph: causal chain visualization | `correlation/graph/ProvenanceGraph.kt` |
| D2 | [ ] TODO | ConnectionLineage: DNS→TCP→TLS→HTTP chain | `correlation/graph/ConnectionLineage.kt` |
| D3 | [ ] TODO | VisibilityAnalyzer + ObservationReport | `diagnostics/VisibilityAnalyzer.kt` |
| D4 | ✅ DONE | Device Capabilities in Settings | SettingsScreen — Android version, arch, root, shizuku, CA, matrix |

### SDK

| # | Status | Item | Files |
|---|--------|------|-------|
| S1 | [ ] TODO | Requestr Observer SDK module | `requestr-sdk/` — new Gradle module |
| S2 | [ ] TODO | HTTP stack auto-detection | `runtime/http/HttpStackDetector.kt` |

---

## BUILD LOG

| Version | Code | Items Done | Notes |
|---------|------|-----------|-------|
| v5.0 | 18 | C1-C17 (17 items) | Critical pipeline fixes — decryption actually works |
| v5.1 | 19 | +6 (23 total) | Cor1, M1, P1, P5, A4, D4 — observation + HTTP/2 + WS + device caps |
| v5.2 | 20 | +6 (29 total) | Cor2, P4, R2, A1, A2, A5 — correlation, DoH/DoT, Frida, dynamic press, raw editor |
| NEXT | 21+ | Remaining 21 | P2, P3, R1, R3, Cat3 items |
