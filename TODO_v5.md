# requestr v5 — Implementation Tracker

> 50 items | 3 categories | commit-per-fix | build-per-commit

---

## CATEGORY 1 — IMPORTANT + CRITICAL (17 items)

### Interception Pipeline — Make It Actually Work

- [x] **C1** — Wire `MitmProxyEngine` into default capture path. CA installed → MITM_PROXY auto-selected. File: `PreflightController.kt`
- [ ] **C2** — System CA installation via root. `CertificateAuthority.installToSystem()`. Handles Android 14+ APEX path. File: `CertificateAuthority.kt`
- [ ] **C3** — Regenerate CA button in Settings + Intercept screen. File: `SettingsScreen.kt`, `InterceptScreen.kt`
- [ ] **C4** — Feed decrypted bytes from `MitmProxyEngine` to `CaptureLogRepository`. Parse HTTP on decrypted stream. File: `MitmProxyEngine.kt`
- [ ] **C5** — Remove TLS discard: `if (stream.isTls && !stream.decryptedByMitm) return`. Add `decryptedByMitm` to `TcpStream`. File: `CaptureEngine.kt`, `TcpStream.kt`
- [ ] **C6** — Set `wasDecrypted = true` on MITM-decrypted entities. File: `CaptureEngine.kt`
- [ ] **C7** — Add `decryptedTransactionCount` to `InterceptionState`. Mutator `addDecryptedTransactions()`. File: `InterceptionState.kt`, `InterceptionStateRepository.kt`
- [ ] **C8** — Gate `CaptureMethodWatcher` HEALTHY on decryption, not packet count. File: `CaptureMethodWatcher.kt`
- [ ] **C9** — Enforce intercept rules on the wire in `MitmProxyEngine` (BLOCK/MODIFY/REDIRECT). File: `MitmProxyEngine.kt`
- [ ] **C10** — Add `isUndecoded: Boolean` to `CapturedTrafficEntity`. Mark SNI-only rows honestly. File: `Entities.kt`, `CaptureEngine.kt`
- [ ] **C11** — Honest status banner in `InterceptScreen`: "HTTPS DECRYPTED ✓" / "PLAINTEXT ONLY" based on real capability. File: `InterceptScreen.kt`
- [ ] **C12** — Honest VPN notification: "HTTPS bodies not captured without proxy mode". File: `RequestrVpnService.kt`
- [ ] **C13** — `OkHttpProxyInterceptor` for non-root own-app proxy path. File: new `interception/OkHttpProxyInterceptor.kt`
- [ ] **C14** — UDP:443 iptables redirect for QUIC capture. File: `RootIptablesEngine.kt`
- [ ] **C15** — CA export feedback: show toast with file path when exported. File: `SettingsScreen.kt`
- [ ] **C16** — Dump-to-file: ensure raw hex files write correctly to Downloads/requestr/. Verify file creation. File: `CaptureEngine.kt`
- [ ] **C17** — Fix `CaptureEngine` stale snapshot: observe `SettingsDataStore` flows directly instead of `.value`. File: `CaptureEngine.kt`

---

## CATEGORY 2 — IMPORTANT BUT NOT CRITICAL (15 items)

### Protocol Coverage

- [ ] **P1** — HTTP/2 frame parser: HEADERS, DATA, SETTINGS, PRIORITY with HPACK decoder. File: new `interception/http/Http2Parser.kt`
- [ ] **P2** — HTTP/3 QUIC semantic layer: `Http3Connection`, `Http3Stream`, `QpackDecoder`. File: new `interception/http3/`
- [ ] **P3** — gRPC/Protobuf detector: `GrpcDetector`, `ProtobufDecoder`. File: new `interception/grpc/`
- [ ] **P4** — DoH/DoT detection: `DohDetector`, `DotDetector`. File: new `interception/dns/`
- [ ] **P5** — Protocol fingerprinting engine with confidence scores. File: new `interception/ProtocolDetector.kt`

### Runtime Instrumentation

- [ ] **R1** — `RuntimeInstrumentationEngine` with adapter pattern. File: new `runtime/`
- [ ] **R2** — Frida client: `FridaClient`, `FridaSession`, `FridaScriptRepository`. Wire start/stop into UI. File: new `runtime/frida/`
- [ ] **R3** — HTTP client adapters: `OkHttpAdapter`, `CronetAdapter`, `WebViewAdapter`. File: new `runtime/http/`

### Correlation

- [ ] **C1** — `ObservationEvent` sealed class with subtypes. File: new `correlation/ObservationEvent.kt`
- [ ] **C2** — `CorrelationEngine`: join events from multiple planes. File: new `correlation/CorrelationEngine.kt`

### API Testr Screen Rewrite

- [ ] **A1** — Top bar: Star, Save, More, Settings with dynamic pill-outline press. File: `ApiTestrScreen.kt`
- [ ] **A2** — Title row with auto-draft persistence on open. File: `ApiTestrScreen.kt`
- [ ] **A3** — Method dropdown with per-method coloring. File: `ApiTestrScreen.kt`
- [ ] **A4** — WS mode: Connect/Disconnect, Messages tab, status indicator. File: `ApiTestrScreen.kt`
- [ ] **A5** — Query/Headers: key-value table + JSON block + raw text editor with two-way sync. File: `ApiTestrScreen.kt`

---

## CATEGORY 3 — NOT IMPORTANT NOR CRITICAL (18 items)

### Browser Bridge

- [ ] **B1** — CDP client: WebSocket to Chromium devtools. File: new `browser/`
- [ ] **B2** — CDP Network adapter: capture requests/responses from browser. File: new `browser/CdpNetworkAdapter.kt`
- [ ] **B3** — CDP Fetch adapter: allow/block/modify via Fetch domain. File: new `browser/CdpFetchAdapter.kt`
- [ ] **B4** — Initiator correlation: `InitiatorResolver`, `StackTraceResolver`. File: new `browser/`
- [ ] **B5** — Service Worker observation. File: new `browser/ServiceWorkerObserver.kt`
- [ ] **B6** — Browser storage correlation. File: new `browser/BrowserStorageObserver.kt`
- [ ] **B7** — Chrome extension bridge. File: new `browser-extension/`

### Advanced Transport

- [ ] **T1** — PCAP subsystem: `PacketCaptureEngine`, `PcapWriter`. File: new `interception/packet/`
- [ ] **T2** — Gateway mode: external network interception. File: new `interception/gateway/`

### Models & Storage

- [ ] **M1** — `TcpConnectionEntity`, `UdpFlowEntity`, `QuicConnectionEntity` in Room. File: `Entities.kt`
- [ ] **M2** — `TlsSessionEntity`, `TlsHandshake`, `TlsMetadata` entities. File: new `interception/tls/`

### Provenance & Diagnostics

- [ ] **D1** — `ProvenanceGraph`: causal chain visualization. File: new `correlation/graph/`
- [ ] **D2** — `ConnectionLineage`: DNS→TCP→TLS→HTTP chain. File: new `correlation/graph/`
- [ ] **D3** — `VisibilityAnalyzer` + `ObservationReport`. File: new `diagnostics/`
- [ ] **D4** — Device Capabilities page in Settings. File: `SettingsScreen.kt`

### SDK

- [ ] **S1** — Requestr Observer SDK module. File: new `requestr-sdk/`
- [ ] **S2** — HTTP stack auto-detection. File: new `runtime/http/HttpStackDetector.kt`

---

## PROGRESS LOG

| Build | Version | Items Done | Key Changes |
|-------|---------|-----------|-------------|
| - | v4.3→v4.4 | 0/50 | Starting v5 implementation |
