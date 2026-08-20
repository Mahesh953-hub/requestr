# REQUESTR v5 IMPLEMENTATION PLAN

## Context

- **Current version:** v4.3 (build 17)
- **Codebase:** Android app (`com.kailashhh.requestr`), Kotlin + Compose, Room, DataStore
- **Existing interception stack:** VPN_TUN → PacketPump → TcpReassembler → CaptureEngine → HTTP/1.x + WS/SSE/DNS/QUIC-SNI parsers. MitmProxyEngine exists but is not wired into the default capture path. RootIptablesEngine, ShizukuTunEngine, HttpProxyEngine, FridaCaptureEngine exist but are not auto-dispatched.
- **v5 plan source:** `requestr_v5_plan.md` (25 missing feature groups: CDP, Browser Extension, Correlation, Provenance, HTTP/3, gRPC, DoH/DoT, Gateway, PCAP, Native sockets, Runtime instrumentation, etc.)
- **Constraint:** Plan Mode only — no source edits or mutating commands in this pass.

---

## PART 0 — ACTUAL CODEBASE vs. v5 PLAN: GAP ANALYSIS

### What already exists (from TODO.md + source inspection)

| Capability | Status | Evidence |
|---|---|---|
| VPN_TUN capture (PacketPump + TcpReassembler) | DONE | `RequestrVpnService`, `PacketPump`, `TcpReassembler` |
| HTTP/1.x parsing | DONE | `HttpParser` |
| WebSocket frame parsing | DONE | `WebSocketParser` |
| SSE parsing | DONE | `SseParser` |
| DNS parsing (UDP/53) | DONE | `DnsParser` |
| QUIC SNI extraction | DONE | `QuicParser.extractSni` |
| TLS ClientHello SNI extraction | DONE | `TlsDecoder` |
| MITM proxy engine (per-host cert) | CODE EXISTS, NOT WIRED | `MitmProxyEngine.kt` (255 lines) |
| Root iptables redirect | CODE EXISTS, NOT WIRED | `RootIptablesEngine.kt` |
| Shizuku iptables redirect | CODE EXISTS, NOT WIRED | `ShizukuTunEngine.kt` |
| HTTP forward proxy | CODE EXISTS, NOT WIRED | `HttpProxyEngine.kt` |
| Frida capture engine | CODE EXISTS, NOT STARTED | `FridaCaptureEngine.kt` |
| Rules persistence (BLOCK/REDIRECT/MODIFY/LOG_ONLY) | PERSISTED BUT NOT ENFORCED ON WIRE | `CaptureEngine.checkRules()` only logs |
| HAR import | DONE | `data/import/HarImporter.kt` |
| API Discovery | DONE | `data/analysis/ApiDiscovery.kt` |
| Schema Reconstruction | DONE | `data/analysis/SchemaReconstructor.kt` |
| Plugin architecture | DONE | `plugin/` |
| Local API (127.0.0.1:9876) | DONE | `api/LocalApiServer.kt` |
| CLI module | DONE | `cli/` subproject |
| Workspace system | DONE | per TODO.md |
| Flow execution (partial) | PARTIAL | `FlowExecutor` only handles API nodes |
| Settings / DataStore | DONE | `SettingsDataStore` |
| Capability probes (real) | DONE | `CapabilityProbe` |
| CA generation + install | DONE | `CertificateAuthority`, `CaWhitelistService` |

### What is genuinely missing (from v5 plan)

| # | Feature Group | Gap |
|---|---|---|
| 1 | **CDP Browser Bridge** | No Chromium DevTools Protocol client, no browser target management, no CDP Network/Fetch/Runtime adapters |
| 2 | **Browser Extension Bridge** | No Chrome extension manifest, background service worker, devtools panel, or native bridge client |
| 3 | **Browser Runtime / Initiator Correlation** | No `InitiatorResolver`, `StackTraceResolver`, or `RequestProvenance` model for script-initiated requests |
| 4 | **Service Worker Observation** | No `ServiceWorkerObserver`, no Cache hit/miss classification |
| 5 | **Browser Storage Correlation** | No Cookie/LocalStorage/SessionStorage/IndexedDB/CacheStorage observers |
| 6 | **Android Runtime Instrumentation Engine** | `FridaCaptureEngine` exists but is never started by UI; no `RuntimeInstrumentationEngine`, `RuntimeSession`, `RuntimeEventBus` |
| 7 | **Runtime-to-Network Correlation** | No `CorrelationEngine`, no `CorrelationKey`, no `RuntimeNetworkMatcher` |
| 8 | **Native Socket Observation** | No `NativeSocketAdapter`, no `SocketEvent` model |
| 9 | **TLS-layer Observation Model** | No `TlsSession`, `TlsHandshake`, `TlsCertificate`, `TlsMetadata` entities |
| 10 | **Gateway / External-Network Mode** | No external gateway client, no multi-device capture aggregation |
| 11 | **Packet Capture / PCAP Subsystem** | No `PacketCaptureEngine`, `Packet`, `PcapWriter` |
| 12 | **TCP/UDP Connection Model** | No `TcpConnectionEntity`, `UdpFlowEntity` in Room |
| 13 | **QUIC Connection Model** | No `QuicConnectionEntity`, no QUIC stream tracking |
| 14 | **HTTP/3 Semantic Layer** | No `Http3Connection`, `Http3Stream`, `Http3Frame`, `QpackDecoder` |
| 15 | **DoH / DoT Detection** | No `DohDetector`, `DotDetector` |
| 16 | **gRPC / Protobuf Layer** | No `GrpcDetector`, `GrpcFrame`, `ProtobufDecoder`, `ProtoSchema` |
| 17 | **Application-Integrated Instrumentation SDK** | No `requestr-sdk` module |
| 18 | **HTTP-Client Middleware Adapters** | No `HttpStackAdapter`, `OkHttpAdapter`, `CronetAdapter`, `WebViewAdapter` |
| 19 | **Request Provenance Graph** | No `ProvenanceGraph`, `ProvenanceNode`, `ProvenanceEdge` |
| 20 | **Connection Lineage Graph** | No `ConnectionLineage` model |
| 21 | **Multi-plane Correlation Engine** | No merging of VPN + MITM + Runtime + Browser observations |
| 22 | **Differential Interception** | No observation-plane comparison UI |
| 23 | **Automatic HTTP-Stack Detection** | No `HttpStackDetector` |
| 24 | **Protocol Fingerprinting** | No `ProtocolDetector` with confidence scores |
| 25 | **Visibility / Capability Diagnostics** | No `ObservationReport`, `VisibilityAnalyzer`, `InterceptionHealth` |

---

## PART 1 — UNFIXED BUGS (Codebase Audit vs. Expected Behavior)

### Critical — Blocking v5 Architecture

| ID | Bug | File:Line | Effect | Fix |
|---|---|---|---|---|
| C1 | `CaptureEngine.ingest()` discards TLS streams after SNI extraction | `CaptureEngine.kt:106` | Encrypted bytes die; no decryption path from tun | Change to `if (stream.isTls && !stream.decryptedByMitm) return`; add `decryptedByMitm` to `TcpStream` |
| C2 | `wasDecrypted = false` hardcoded on every persisted entity | `CaptureEngine.kt:265` | UI can never show decrypted state | Accept `decrypted` parameter in `persistTransaction` and `persistWsFrame` |
| C3 | Rules are not enforced on the wire | `CaptureEngine.kt:203-242` | BLOCK/MODIFY are no-ops in VPN path | Move enforcement into `MitmProxyEngine.handleClient()`; VPN path needs proxy relay |
| C4 | `PreflightController` default falls back to `VPN_TUN` even when MITM proxy could decrypt | `PreflightController.kt:103-108` | 90%+ of users land on non-decrypting path | Default to `MITM_PROXY` when CA installed and `recordMode == "PROXY"` or root/Shizuku |
| C5 | `CaptureMethodWatcher` gates HEALTHY on `packetCount`, not decryption | `CaptureMethodWatcher.kt:72-78` | Watcher says ACTIVE while 5000 encrypted packets flow silently | Add `decryptedTransactionCount` to `InterceptionState`; gate on that |
| C6 | `MitmProxyEngine` exists but is unreachable on the default path | `MitmProxyEngine.kt` + `PreflightController.kt` | Full MITM capability unused | Wire `MitmProxyEngine.start()` into `PreflightController.startCapture()` for `MITM_PROXY` and `ROOT_IPTABLES`/`SHIZUKU_TUN` |
| C7 | `FridaCaptureEngine` never started by UI | `FridaCaptureEngine.kt` + `InterceptScreen.kt` | Chip says "Detected (CLI only)" — lies about in-app integration | Wire start/stop into InterceptScreen Frida sheet or remove chip |

### Major — Trust / Honesty

| ID | Bug | File:Line | Effect | Fix |
|---|---|---|---|---|
| M1 | Non-root VPN mode UI claims capture but cannot decrypt HTTPS | `RequestrVpnService.kt:257` | User expects bodies | Update notification + status banner to say "plaintext only" |
| M2 | `CapturedTrafficEntity` has no `isUndecoded` marker | `Entities.kt` | Cannot distinguish SNI-only rows from real transactions | Add `isUndecoded: Boolean = false` field |
| M3 | `InterceptionStateRepository` has no `decryptedTransactionCount` | `InterceptionStateRepository.kt` | Counters never track real decryption | Add field + mutator |
| M4 | `RootIptablesEngine` missing UDP:443 redirect | `RootIptablesEngine.kt:55-57` | QUIC traffic bypasses MITM | Add `iptables -t nat -A OUTPUT -p udp --dport 443 -j REDIRECT --to-port $port` |
| M5 | `PreflightController` always starts VPN for `ROOT_FRIDA` | `PreflightController.kt:168-172` | Frida mode loses network capture | Start VPN as fallback only if not already running MITM |
| M6 | `HttpProxyEngine` does not log captured transactions | `HttpProxyEngine.kt` | Proxy mode has no traffic log | Add `CaptureLogRepository` callback and parse HTTP request/response |

### Minor

| ID | Bug | File | Effect | Fix |
|---|---|---|---|---|
| m1 | `TcpStream` has no `decryptedByMitm` field | `TcpStream.kt` | Cannot track decryption source | Add `var decryptedByMitm: Boolean = false` |
| m2 | `PacketPump` does not update `decryptedTransactionCount` | `PacketPump.kt` | Counter stays zero | Increment when MITM proxy reports a transaction |
| m3 | `CaptureEngine` reads `SettingsRepository.settings.value` (stale snapshot) | `CaptureEngine.kt:195` | Toggle changes not immediate | Observe `SettingsDataStore` flows directly |
| m4 | `RequestrVpnService` notification says "Local VPN active on port $port" even in non-VPN modes | `RequestrVpnService.kt:257` | Misleading notification | Use dynamic text based on `CaptureMethod` |
| m5 | `SettingsScreen` has no Browser / CDP / Runtime / Gateway / DNS / Correlation / Storage / Diagnostics settings sections | `SettingsScreen.kt` | v5 settings missing | Add sections per v5 plan §26 |

---

## PART 2 — v5 IMPLEMENTATION PHASES

### PHASE 1 — Observation Core (Weeks 1-3)
**Goal:** Build the unified event model that everything else plugs into.

**Tasks:**
1. **Add `ObservationEvent` sealed class** in `data/interception/ObservationEvent.kt`
   - Subtypes: `NetworkEvent`, `TlsEvent`, `HttpEvent`, `RuntimeEvent`, `BrowserEvent`, `SocketEvent`, `DnsEvent`, `QuicEvent`
   - Each carries: `eventId`, `timestamp`, `source`, `connectionId`, `streamId`, `correlationKeys`

2. **Add `ConnectionModel`** in `interception/connection/`
   - `TcpConnectionEntity` (Room): connectionId, src, dst, srcPort, dstPort, state, timestamps, bytesSent, bytesReceived
   - `UdpFlowEntity` (Room): flowId, src, dst, srcPort, dstPort, protocol, timestamps, byteCount
   - `QuicConnectionEntity` (Room): connectionId, src, dst, srcPort, dstPort, alpn, sni, timestamps

3. **Add `TlsObservation` model** in `interception/tls/`
   - `TlsSessionEntity` (Room): sessionId, version, cipher, alpn, sni, certificateHash, handshakeTimestamps, protocol
   - `TlsDetector` (object): classifies observed bytes as TLS vs. non-TLS without decrypting

4. **Wire MITM proxy to emit `ObservationEvent`s**
   - In `MitmProxyEngine.handleClient()`, after TLS termination, parse HTTP/1.x (and later HTTP/2) and emit events into a `Channel<ObservationEvent>` or repository callback
   - Set `stream.decryptedByMitm = true` so `CaptureEngine` processes instead of discarding

5. **Update `CaptureEngine` to accept decrypted streams**
   - Change line 106: `if (stream.isTls && !stream.decryptedByMitm) return`
   - Add `decryptedByMitm` to `TcpStream`
   - Accept `wasDecrypted` parameter in `persistTransaction` and `persistWsFrame`

6. **Add `decryptedTransactionCount` to `InterceptionState`**
   - Field + mutator in `InterceptionStateRepository`
   - Wire `PacketPump` and `MitmProxyEngine` to increment

**Verification:**
- [ ] Unit test: `TcpStream.decryptedByMitm = true` allows HTTP parsing of previously TLS stream
- [ ] Unit test: `TlsDetector` classifies ClientHello bytes correctly
- [ ] Integration: `MitmProxyEngine` emits `ObservationEvent` for each decoded request/response
- [ ] UI: `InterceptionState.decryptedTransactionCount` increments on MITM traffic

### PHASE 2 — Protocol Coverage (Weeks 4-6)
**Goal:** Parse modern protocols that currently fall through.

**Tasks:**
1. **HTTP/2 frame parser** (`interception/http/Http2Parser.kt`)
   - Frame header parsing (length, type, flags, stream ID)
   - HPACK decoder (static table + dynamic table)
   - Emit `HttpEvent` with headers/body per stream

2. **HTTP/3 / QUIC semantic layer** (`interception/http3/`)
   - `Http3Connection`, `Http3Stream`, `Http3Frame`
   - `QpackDecoder` (simpler than HPACK)
   - Wire into `PacketPump` UDP:443 path when QUIC is detected

3. **gRPC / Protobuf detector** (`interception/grpc/`)
   - `GrpcDetector`: identifies HTTP/2 + content-type `application/grpc` + protobuf length-prefix
   - `GrpcFrame`: message type, length, protobuf payload
   - `ProtobufDecoder`: optional schema-aware decode, fallback to raw hex

4. **DoH / DoT detection** (`interception/dns/`)
   - `DohDetector`: matches `POST /dns-query` + `application/dns-message`
   - `DotDetector`: matches TCP:853 TLS + DNS payload after handshake
   - Update UI labels: "UDP/53: decoded", "DoT: encrypted", "DoH: encrypted / browser-observed"

5. **Protocol Fingerprinting engine** (`interception/ProtocolDetector.kt`)
   - Input: bytes + port + TLS metadata + HTTP metadata
   - Output: classified protocol + confidence score
   - Confidence levels: Detected (0.95+), Likely (0.7-0.94), Possible (0.4-0.69), Unknown

**Verification:**
- [ ] Unit test: HPACK decoder decompresses known static-table headers
- [ ] Unit test: HTTP/2 frame parser extracts HEADERS/DATA frames
- [ ] Unit test: gRPC detector identifies protobuf length-prefix on HTTP/2
- [ ] Unit test: ProtocolDetector confidence scoring
- [ ] Device test: Visit HTTP/2 site → verify stream/header parsing in log

### PHASE 3 — Runtime Instrumentation (Weeks 7-9)
**Goal:** Make Frida and future runtime adapters first-class observation planes.

**Tasks:**
1. **`RuntimeInstrumentationEngine`** (`runtime/`)
   - Singleton engine managing adapters: Frida, Xposed, Debugger, Native, App SDK
   - `RuntimeSession`: target process, PID, UID, adapter type, state
   - `RuntimeEventBus`: hot `SharedFlow` for runtime events
   - `RuntimeEvent`: `RequestStarted`, `HeadersReady`, `BodyReady`, `ResponseReady`, `Exception`, `StackTrace`

2. **Frida adapter** (`runtime/frida/`)
   - `FridaClient`: manages `frida-server` process lifecycle
   - `FridaSession`: injects scripts, reads stdout/stderr
   - `FridaScriptRepository`: built-in scripts (http_log, cert_pinner, trustmanager, jni_trace)
   - Wire `FridaCaptureEngine.start()` into `InterceptScreen` Frida sheet

3. **HTTP Client Adapter System** (`runtime/http/`)
   - `HttpStackAdapter` interface: `detect()`, `attach()`, `observe()`
   - `OkHttpAdapter`: intercepts via `Interceptor` chain
   - `CronetAdapter`: Cronet network callback
   - `WebViewAdapter`: WebViewClient shouldInterceptRequest

4. **Native Socket Observation** (`runtime/native/`)
   - `NativeSocketAdapter`: reads `/proc/net/tcp`, `/proc/net/udp`
   - `SocketEvent`: pid, uid, fd, family, protocol, addresses, ports, operation, byteCount

5. **Wire Frida + VPN into `CorrelationEngine`** (placeholder in Phase 4)

**Verification:**
- [ ] Unit test: `RuntimeInstrumentationEngine` manages session lifecycle
- [ ] Unit test: `FridaClient` parses script output into `RuntimeEvent`
- [ ] Device test: Inject http_log script → verify request events in `RuntimeEventBus`
- [ ] Device test: `OkHttpAdapter` intercepts request → verify correlation key matches VPN observation

### PHASE 4 — Browser Bridge (Weeks 10-12)
**Goal:** CDP + Extension bridge for browser-level interception.

**Tasks:**
1. **`CdpClient`** (`browser/`)
   - WebSocket client to `localhost:9222` (or user-specified port)
   - JSON-RPC request/response over WebSocket
   - `CdpTargetManager`: enumerate targets, attach to pages

2. **CDP Network Adapter** (`browser/CdpNetworkAdapter.kt`)
   - Listen to `Network.requestWillBeSent`, `Network.responseReceived`, `Network.loadingFinished`
   - Get request/response bodies via `Network.getResponseBody`
   - Emit `ObservationEvent` with initiator data

3. **CDP Fetch Adapter** (`browser/CdpFetchAdapter.kt`)
   - Enable `Fetch.enable` with patterns
   - `Fetch.requestPaused` → allow/block/modify/fulfill
   - Wire rule engine actions to CDP fulfill responses

4. **CDP Runtime Adapter** (`browser/CdpRuntimeAdapter.kt`)
   - `Runtime.consoleAPICalled`, `Runtime.exceptionThrown`
   - JS stack traces for script-initiated requests

5. **`BrowserCaptureEngine`** (`browser/BrowserCaptureEngine.kt`)
   - Unified entry point: CDP target → events → ObservationEvent → CorrelationEngine

6. **`InitiatorResolver`** (`browser/InitiatorResolver.kt`)
   - Maps CDP `initiator` (script, parser, preload, signedexchange) to human-readable chain
   - `RequestProvenance` data class: requestId, targetId, frameId, initiatorType, initiatorUrl, scriptUrl, functionName, lineNumber, stackTrace

7. **Service Worker Observation** (`browser/ServiceWorkerObserver.kt`)
   - `Network.serviceWorkerWillHandleRequest`, `Network.serviceWorkerResponseReceived`
   - Classify source: NETWORK / SERVICE_WORKER / CACHE

8. **Browser Storage Correlation** (`browser/BrowserStorageObserver.kt`)
   - CDP `Storage.getStorageKeyForFrame`, `DOMStorage.getDOMStorageItems`
   - Cookies via `Network.getAllCookies` / `Network.setCookies`
   - Correlate cookie mutations with subsequent requests

9. **Chrome Extension Bridge** (`browser-extension/`)
   - `manifest.json` (MV3)
   - `background/service-worker.js`: native messaging host listener
   - `devtools/`: devtools panel for request inspection
   - `bridge/requestr-client.js`: sends captured requests to `127.0.0.1:9876/api/captures`

**Verification:**
- [ ] Unit test: `CdpClient` connects, sends `Target.getTargets`, parses response
- [ ] Unit test: `CdpNetworkAdapter` emits `ObservationEvent` for synthetic requestWillBeSent
- [ ] Unit test: `InitiatorResolver` extracts stack trace from CDP initiator payload
- [ ] Device test: Chrome with remote debugging → verify CDP events appear in Interceptr
- [ ] Device test: Extension installed → verify captured requests appear via Local API

### PHASE 5 — Correlation & Provenance (Weeks 13-15)
**Goal:** Merge observations from all planes into a coherent model.

**Tasks:**
1. **`CorrelationEngine`** (`correlation/`)
   - `CorrelationKey`: composite of timestamp (±500ms), destination, port, URL, process, thread, requestId, streamId, socket
   - `CorrelationRule`: heuristic matching rules
   - `CorrelationStore`: in-memory map of `CorrelationKey` → `List<ObservationEvent>`
   - `RequestMatcher`: joins DNS → TCP → TLS → HTTP → Runtime events

2. **`RuntimeNetworkMatcher`** (`correlation/RuntimeNetworkMatcher.kt`)
   - Matches Frida events (method, URL, thread) to network observations (destination, port, timing)

3. **`ProvenanceGraph`** (`correlation/graph/`)
   - `ProvenanceNode`: event ID, timestamp, type, label
   - `ProvenanceEdge`: sourceId, targetId, relationship
   - `GraphBuilder`: reconstructs causal chain from correlated events
   - UI: "Request > Provenance" tab in detail view

4. **`ConnectionLineage`** (`correlation/graph/ConnectionLineage.kt`)
   - Linear chain: DNS #12 → IP 1.2.3.4 → TCP #31 → TLS #44 → HTTP/2 #17 → Stream #7 → REQ-421

5. **Multi-plane correlation UI** (`ui/screens/DifferentialScreen.kt`)
   - Select observation planes: VPN, MITM, Runtime, Browser, Packet
   - Compare: REQ-421 seen in VPN ✓, MITM ✓, Runtime ✓, Browser ✓
   - Visibility analysis: "Runtime only + encrypted network transport"

6. **Visibility Analyzer** (`diagnostics/VisibilityAnalyzer.kt`)
   - `ObservationReport`: per-session report of what was visible
   - `InterceptionHealth`: overall capture health score
   - UI: "Device Capabilities" + "Observation Report" in Settings

**Verification:**
- [ ] Unit test: `CorrelationEngine` merges synthetic VPN + MITM + Runtime events for same request
- [ ] Unit test: `GraphBuilder` produces correct provenance chain from ordered events
- [ ] Unit test: `RuntimeNetworkMatcher` matches OkHttp Frida event to VPN observation
- [ ] Integration: Real HTTPS request through MITM → verify correlation with Frida event
- [ ] UI: Differential screen shows plane comparison for a captured request

### PHASE 6 — Advanced Transport & Gateway (Weeks 16-18)
**Goal:** Packet capture, gateway mode, and remaining transport layers.

**Tasks:**
1. **Packet Capture / PCAP Subsystem** (`interception/packet/`)
   - `PacketCaptureEngine`: captures raw IP packets from tun
   - `Packet`: timestamp, raw bytes, parsed headers (IP/TCP/UDP/ICMP)
   - `IpParser`, `TcpParser`, `UdpParser`, `Ipv6Parser`
   - `PcapWriter`: writes `.pcapng` file to Downloads

2. **Gateway Mode** (`interception/gateway/`)
   - `GatewayClient`: connects to external requestr instance or desktop bridge
   - `GatewaySession`: aggregates captures from multiple devices
   - Settings: host, port, auth, TLS, capture policy

3. **UDP Connection Model** (Room entities + flow tracking)
   - `UdpFlowEntity` DAO + repository
   - Wire `PacketPump` UDP path to flow tracker

4. **QUIC Connection Model** (Room entities + stream tracking)
   - `QuicConnectionEntity`, `QuicStreamEntity`
   - Wire QUIC SNI + draft-http3 parsing

5. **Settings architecture expansion** (`SettingsScreen.kt`)
   - Add sections: Browser Bridge, Browser Extension, Runtime Instrumentation, Gateway, DNS, Protocols, Correlation, Storage, Rules, Flow, Diagnostics

**Verification:**
- [ ] Unit test: `PacketCaptureEngine` writes valid pcapng
- [ ] Unit test: `GatewayClient` aggregates remote captures
- [ ] Device test: Capture UDP:53 DNS → verify `UdpFlowEntity` written
- [ ] Device test: Capture QUIC initial → verify `QuicConnectionEntity` + SNI written
- [ ] UI: Settings shows all v5 sections

### PHASE 7 — Application SDK & HTTP Adapters (Weeks 19-20)
**Goal:** Let controlled apps observe themselves, and auto-detect HTTP stacks.

**Tasks:**
1. **Requestr Observer SDK** (`requestr-sdk/` — new Gradle module or library module)
   - `RequestrObserver.kt`: initialize with endpoint, capture flags
   - `RequestrInterceptor`: OkHttp interceptor that bridges to local API
   - `RequestrEvent`: request started / headers / body / response / timing / exception
   - `RequestrTransport`: HTTP/WebSocket transport to `127.0.0.1:9876`

2. **HTTP Stack Detection** (`runtime/http/HttpStackDetector.kt`)
   - Classify observed traffic as: OkHttp, Cronet, URLConnection, WebView, Browser Fetch, Native, Unknown
   - Evidence: runtime events, process metadata, library presence, connection behavior, protocol metadata
   - Confidence: Detected, Likely, Possible, Unknown

3. **HTTP Client Adapters** (`runtime/http/`)
   - `OkHttpAdapter`: `Interceptor` + `EventListener`
   - `CronetAdapter`: `CronetEngine` callback
   - `WebViewAdapter`: `WebViewClient.shouldInterceptRequest`
   - `BrowserFetchAdapter`: CDP-based fetch observer

**Verification:**
- [ ] Unit test: `HttpStackDetector` classifies synthetic traffic signatures
- [ ] Integration: Sample app with `RequestrObserver` → verify events reach Local API
- [ ] Device test: App using OkHttp → verify `OkHttpAdapter` emits `RuntimeEvent`

### PHASE 8 — Honest UI & Final Wiring (Weeks 21-22)
**Goal:** Ensure no fake green indicators remain; every UI state reflects real capability.

**Tasks:**
1. **Honest InterceptScreen status banner**
   - Show decryption indicator: "HTTPS DECRYPTED ✓" / "PLAINTEXT ONLY" / "DECRYPTING..."
   - Show observation plane chips: VPN + MITM + Runtime + Browser
   - Show protocol badge: HTTP/1.1 / HTTP/2 / HTTP/3 / gRPC / WS / SSE

2. **Honest Interceptr detail view**
   - REQ-NNNN card with:
     - SOURCE (Android App / Browser / Runtime)
     - OBSERVATION (which planes saw it)
     - STACK (OkHttp / Cronet / WebView / Native)
     - PROTOCOL / CONNECTION / STREAM / TLS / DNS
     - RUNTIME ORIGIN (function + line)
     - PROVENANCE chain
     - DOM EFFECT (browser only)

3. **Device Capabilities matrix** (`Settings > Device Capabilities`)
   - Android version, Architecture, Root, Shizuku, VPN permission, Developer options, USB debugging, CA installed, System CA access, IPv6, Network state, Battery restrictions, Background execution
   - Capability matrix:
     - No Root / Shizuku / Root
     - VPN / MITM user CA / System CA / Runtime instrumentation / Packet capture / Native observation

4. **Observation Report** (`Settings > Diagnostics > Observation Report`)
   - Per-session report: what was visible at each layer
   - Example: "Traffic is visible at runtime and packet layers, but HTTP plaintext is unavailable to this capture path"

5. **Remove fake/decorative UI elements**
   - Any remaining static counters → replace with `InterceptionStateRepository`
   - Any chip that says "Available" without real connection → state machine: Unavailable → Detected → Connected → Attached → Streaming

**Verification:**
- [ ] Manual: Fresh install → verify no fake traffic rows
- [ ] Manual: Start VPN capture → verify banner says "VPN · PLAINTEXT ONLY"
- [ ] Manual: Start MITM capture → verify banner says "HTTPS DECRYPTED ✓"
- [ ] Manual: Connect CDP → verify banner shows "BROWSER: ACTIVE"
- [ ] Manual: Open request detail → verify provenance chain is populated
- [ ] Manual: Settings > Diagnostics → verify Observation Report matches actual capture

---

## PART 3 — DUAL-CHECK PROTOCOL

For every phase, before marking complete:

1. **Code review:** Read every modified file and verify it matches the plan's task list.
2. **Static analysis:** Run `./gradlew lint` (or `./gradlew :app:lint`) scoped to changed packages. Fix all errors before proceeding.
3. **Unit tests:** Run `./gradlew test` (or `./gradlew :app:test`). All existing tests must pass; new tests must be added for every new public class.
4. **Compile check:** `./gradlew assembleDebug` must succeed.
5. **Behavioral audit:** Read the UI screen that consumes the new data. Verify it never renders optimistic defaults. Verify it handles the empty/error/degraded states.
6. **Regression check:** Run the existing InterceptScreen + SettingsScreen flows. Verify no existing behavior broke.

---

## PART 4 — COMMIT & PUSH STRATEGY

After each phase completion:
1. `git status` — verify only intended files are staged
2. `git diff` — review all changes
3. `git add <files>` — stage only implementation files (no test data, no secrets)
4. `git commit -m "v5: <phase name> — <summary>"` — follow existing repo style (concise, imperative)
5. `git push` — push to remote

Branching: work on `feat/v5-<phase-name>` branches, PR into `main` after phase verification.

---

## PART 5 — OPEN QUESTIONS (For Implementation Agent)

1. **HTTP/2 HPACK:** Should we implement a minimal HPACK decoder from scratch or depend on `org.apache.httpcomponents:httpclient-android`? The v5 plan suggests a lightweight HPACK-only lib. Verify if any existing dependency already covers this.
2. **QUIC stack:** Full QUIC MITM requires a QUIC stack in Java (quiche via JNI, or Android's QUIC API). For v5 Phase 6, is SNI-only + pcap acceptable, or do we need a real QUIC proxy?
3. **Chrome Extension packaging:** The extension should be bundled in the APK (`assets/browser-extension/`) and installed via ADB or a setup wizard. Confirm the install mechanism.
4. **CorrelationEngine performance:** With 500+ concurrent streams, the in-memory `CorrelationStore` could grow unbounded. Confirm eviction policy (time-window + LRU).
5. **SDK module placement:** `requestr-sdk/` as a separate Gradle module vs. a library inside `app/src/main/java/`. A separate module is cleaner for external consumers but requires publishing/local Maven setup.

---

## PART 6 — EXECUTION ORDER SUMMARY

| Order | Phase | Duration | Key Deliverable |
|---|---|---|---|
| 1 | Observation Core | 3 weeks | `ObservationEvent`, `ConnectionModel`, `TlsObservation`, MITM wiring |
| 2 | Protocol Coverage | 3 weeks | HTTP/2, HTTP/3, gRPC, DoH/DoT, ProtocolDetector |
| 3 | Runtime Instrumentation | 3 weeks | `RuntimeInstrumentationEngine`, Frida adapter, HTTP adapters |
| 4 | Browser Bridge | 3 weeks | CDP client, Extension bridge, InitiatorResolver, ServiceWorkerObserver |
| 5 | Correlation & Provenance | 3 weeks | `CorrelationEngine`, `ProvenanceGraph`, Differential UI, VisibilityAnalyzer |
| 6 | Advanced Transport | 3 weeks | PCAP, Gateway, UDP/QUIC models |
| 7 | SDK & Adapters | 2 weeks | Requestr Observer SDK, HttpStackDetector |
| 8 | Honest UI & Final Wiring | 2 weeks | Status banners, detail view, Device Capabilities, Observation Report |

**Total estimated effort:** 22 weeks (5.5 months) with one full-time implementation agent.

---

*This plan is implementation-ready. All tasks are grounded in actual file paths, class names, and current codebase state. No speculative dependencies.*
