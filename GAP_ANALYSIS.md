# Requestr — Codebase vs. Vision Gap Analysis

**Date:** 2026-08-20  
**Build:** v3.4 (build 7)  
**Comparison Against:** `MODERNIZATION_OF_INTERCEPTION.md`, `Requestr-Feature-Vision-V3.md`, `requestr_v3.1_stub_elimination_plan.md`

---

## Executive Summary

| Document | Scope | Codebase Coverage |
|----------|-------|-------------------|
| **Modernization of Interception** | 46 sections covering full interception fabric | **~22% implemented** — Only VPN_TUN + basic HTTP/1.1 parsing + SNI extraction work. MITM, Frida correlation, WebView, CDP, HTTP/2/3, WebSocket, DNS, QUIC, rules engine, correlation engine — all missing. |
| **Requestr Feature Vision V3** | 25 major features + 6 phases | **~35% implemented** — API Testr (composer, auth, environments, assertions), Tools (18 tools with workspaces), Music (local only), basic Flow UI. Missing: AI Chat, WebSocket/SSE inspectors, GraphQL, HAR import, schema reconstruction, diff view, chaos testing, workspace system, plugin architecture, local API, CLI. |
| **v3.1 Stub Elimination Plan** | 3 critical stub fixes + 11 lacking features | **Part A/B: ~60% done** — Theme collision (A1) partially fixed; Permissions (A2) real; Logo (A3) real; Save/Star (A4) real. Settings stubs (B1-B13): Theme/Accent (B1) fixed; PiP (B2) removed; Typography/Surface/Border (B3) removed; Haptic (B4) real; Gesture (B5) real; Tablet (B6) removed; Density (B7) real; Animation (B8) real; Traffic&Network (B9) real toggles but engine ignores live changes; Advanced (B10) removed; Cert (B11) partial; Duplicates (B12) fixed; Theme icon (B13) fixed. **Part C (11 features): 4/11 done** — C1 (DataStore) ✅, C2 (Chaining) ✅, C3 (Assertions) ✅, C4 (WS) ❌, C5 (GraphQL) ❌, C6 (Analytics) ❌, C7 (Diff) ❌, C8 (Undo) ❌, C9 (Shortcuts) ❌, C10 (A11y) ❌, C11 (Tablet) ❌. |

---

## Detailed Gap Matrix: MODERNIZATION_OF_INTERCEPTION.md

| Section | Title | Spec Requirement | Codebase Status | Verdict |
|---------|-------|------------------|-----------------|---------|
| 1 | Explicit HTTP Proxy | Forward proxy with CONNECT, TLS interception | `HttpProxyEngine.kt` exists, handles CONNECT tunnels, logs plaintext HTTP. **Never started** — no UI to select proxy mode. | **STUB** (engine exists, not wired) |
| 2 | MITM HTTPS Proxy | Terminate TLS, per-host certs, expose TLS diagnostics | `MitmProxyEngine.kt` fully implemented (BouncyCastle cert gen, dual TLS). **Never started** — `CaptureMethod.MITM_PROXY` never selected. | **STUB** (engine exists, not wired) |
| 3 | Transparent Proxy | Explicit / Transparent / Reverse proxy modes | Only VPN_TUN (transparent via tun) and HTTP_PROXY (explicit) engines exist. Reverse proxy not built. | **PARTIAL** |
| 4 | Android Local VPN | Per-app filter, selected/excluded domains, capture/routing rules, stats | `RequestrVpnService` + `PacketPump` + app filter (UID-based). Shows packet count. **Missing:** domain filters, routing rules, bypassed/failed counters. | **PARTIAL** |
| 5 | Frida Runtime Instrumentation | Java/Kotlin/JNI/Native/SSL/OkHttp/Cronet/WebView hooking | `FridaCaptureEngine.kt` exists but **no control channel** — only reference scripts in `TrafficInterceptorRepository`. Chip shows "Running" if port 27042 open. | **STUB** |
| 6 | Runtime Correlation | Correlate Frida events with HTTP requests (stack trace → REQ-ID) | **Zero correlation logic.** Frida output logged to logcat only. No correlation engine. | **MISSING** |
| 7 | Android WebView | Distinguish Native / WebView / Browser network | **No WebView detection.** All tun traffic attributed to app UID only. | **MISSING** |
| 8 | Native Networking | Classify Java / JNI / Native / Custom / Unknown | `CapturedTrafficEntity` has `trafficSource` enum but **always NATIVE**. No classification logic. | **MISSING** |
| 9 | OkHttp and Cronet | Detect HTTP stack, show in inspector | **No stack detection.** `ProtocolHint` enum exists but never populated. | **MISSING** |
| 10 | Browser DevTools Protocol (CDP) | Chrome/Chromium CDP bridge | **No CDP implementation.** No browser bridge. | **MISSING** |
| 11 | CDP Network Interception | requestWillBeSent, responseReceived, WS frames | **MISSING** |
| 12 | Browser Request Control | Continue/Modify/Fail/Fulfill/Block/Delay | **MISSING** |
| 13 | Browser Extension | Chrome extension ↔ local bridge | **MISSING** (desktop/browser concern) |
| 14 | Service Worker Observation | Identify SW / Cache / Network fetch | **MISSING** |
| 15 | Browser JS Runtime | Correlate fetch/XHR/WS/SSE/beacon with network | **MISSING** |
| 16 | Browser Storage | Cookies/LocalStorage/SessionStorage/IndexedDB/CacheStorage | **MISSING** |
| 17 | HTTP/1.1 | Method, headers, body, keep-alive, chunked, compression, redirects, timing | `HttpParser` + `CaptureEngine` handle HTTP/1.x requests/responses, Content-Length, chunked. **Works for plaintext only.** | **WORKING** (plaintext) |
| 18 | HTTP/2 | Multiplexed streams, stream IDs, HPACK, timing | **No HTTP/2 frame parser.** TLS streams drained after SNI. | **MISSING** |
| 19 | HTTP/3 and QUIC | UDP → QUIC → TLS 1.3 → HTTP/3, connection diagnostics | **No QUIC parser.** UDP 443 packets seen but opaque. | **MISSING** |
| 20 | WebSocket | Persistent connection, frames, direction, opcode, payload, handshake | **No WS frame parser.** HTTP upgrade not detected. | **MISSING** |
| 21 | Server-Sent Events | Event stream (event/id/retry/data), raw + parsed | **No SSE parser.** | **MISSING** |
| 22 | WebTransport | QUIC + HTTP/3 architecture | **MISSING** |
| 23 | DNS Observation | Hostname, query type, resolver, answer, TTL, IPv4/6, timing, DoH/DoT | **No DNS capture.** Tun sees DNS packets but not parsed. | **MISSING** |
| 24 | Packet-Level Diagnostics | Source/dest, protocol, packets, bytes, RTT, retransmissions, TLS handshake | `PacketPump` tracks packets/bytes/flows. **No RTT/retransmission/TLS handshake timing.** | **PARTIAL** |
| 25 | Connection Graph | Navigable DNS → TLS → HTTP/2 → REQ-IDs | **No graph.** Flat list only. | **MISSING** |
| 26 | Initiator Graph | Button → JS → fetch → SW → HTTP → Response → DOM | **MISSING** |
| 27 | TLS and Trust Diagnostics | Version, cipher, cert chain, issuer, validity, hostname, ALPN, handshake timing, trust state, pinning diagnostics | `TlsDecoder` extracts SNI only. `MitmProxyEngine` would expose full TLS but not running. | **STUB** (SNI only) |
| 28 | Traffic Classification | Source (Browser/Native/WebView/Proxy/VPN) + Protocol (h1/h2/h3/WS/SSE) | `TrafficSource` + `ProtocolHint` enums exist in model but **never populated** (always NATIVE/UNKNOWN). | **STUB** |
| 29 | Rules Engine | WHEN host/path conditions → actions (Log/Tag/Block/Delay/Redirect/Mock/Modify/Save/Trigger Flow) | `InterceptRuleEntity` + DAO exist. Rules saved to Room. **`CaptureEngine` never reads/applies them.** | **STUB** |
| 30 | Response Mocking | Convert endpoint → mock with controlled status/latency | `RuleAction.MOCK_RESPONSE` enum exists. **No mock server logic.** | **MISSING** |
| 31 | Chaos Testing | Fault injection (latency, timeouts, errors, malformed, rate limits) | **MISSING** |
| 32 | HAR Import | Import .har → requests/responses/headers/cookies/timing → Open in Interceptr/API Testr/Flow/Tool | **MISSING** |
| 33 | API Discovery | Traffic → hosts/endpoints/methods/status distribution/content types/auth patterns | **MISSING** |
| 34 | API Schema Reconstruction | Infer JSON Schema/OpenAPI/TS/Python/Pydantic from responses | **MISSING** |
| 35 | Search | Semantic (status:500, method:POST, host:api.example.com, duration:>1000, content-type:json) | `CaptureLogRepository.search(query)` exists but **only SQL LIKE on host/url**. No structured query parser. | **PARTIAL** |
| 36 | Replay | Replay/Duplicate/Edit & Replay/Open in API Testr/Export/Save with parameterization ({{BASE_URL}}, {{TOKEN}}) | `TrafficExporter.toCurl()` works. **No replay engine.** | **PARTIAL** |
| 37 | Diff | Request/Response diff (raw + structural JSON) | **MISSING** |
| 38 | Environment System | Local/Dev/Staging/Prod/Custom + variables + secret store | `EnvironmentRepository` + `substitute()` + unified DataStore **WORKING**. | **WORKING** |
| 39 | AI Integration | Inspect capture, explain request, summarize response, identify patterns, generate tests/schemas/tools/flows, compare responses | **No AI integration.** No chat, no tool generation, no schema inference. | **MISSING** |
| 40 | Scrape → Tool | URL/schema/capture → endpoint extraction → param detection → schema inference → tool definition | **MISSING** |
| 41 | Flow Integration | Interceptr events → Flow triggers (status==500 → save/generate/notify/run test) | `FlowNodeType.INTERCEPT_TRIGGER` exists but **no trigger logic.** Flow executor doesn't connect to capture events. | **STUB** |
| 42 | Correlation Engine | Dedicated subsystem: DNS, Socket, TLS, HTTP, WS, SSE, Browser, Runtime, Flow events | **MISSING** |
| 43 | Capability Matrix | 34 rows across Browser/Android/Network/TLS/Automation | See matrix below | **MOSTLY MISSING** |
| 44 | Recommended Architecture | Observation Core → Network/Runtime/Browser → Correlation Engine → Interceptr → API Testr/Tools/AI → Flow | **Only VPN_TUN → PacketPump → CaptureEngine → Room → InterceptScreen exists.** | **INCOMPLETE** |
| 45 | Trace → Understand → Reproduce | 500 reqs → hosts/endpoints/auth flow/runtime origins/relationships/graph/schemas/workflows → Create Collection/Flow/Tool/Schema/Replay/Export | **MISSING** |
| 46 | Final Principle | "Interception fabric, not HTTP proxy with buttons" | Currently: **VPN proxy with buttons** | **NOT ACHIEVED** |

---

## Capability Matrix (Modernization §43) vs. Implementation

| Layer | Technique | Spec Status | Codebase Status |
|-------|-----------|-------------|-----------------|
| Browser | CDP | Add | ❌ MISSING |
| Browser | DevTools Network | Add | ❌ MISSING |
| Browser | Extension | Add | ❌ MISSING |
| Browser | Service Worker | Add | ❌ MISSING |
| Browser | JavaScript Runtime | Add | ❌ MISSING |
| Browser | WebSocket | Add | ❌ MISSING |
| Browser | SSE | Add | ❌ MISSING |
| Browser | Storage | Add | ❌ MISSING |
| Android | Local VPN | Existing | ✅ WORKING (basic) |
| Android | MITM Proxy | Existing | 🔴 STUB (engine built, not wired) |
| Android | Explicit Proxy | Existing/Extend | 🔴 STUB (engine built, not wired) |
| Android | Frida | Existing | 🔴 STUB (engine built, no control) |
| Android | WebView | Add | ❌ MISSING |
| Android | Runtime Correlation | Add | ❌ MISSING |
| Android | Native Networking | Add | ❌ MISSING |
| Android | OkHttp Observation | Add/Extend | ❌ MISSING |
| Android | Cronet Observation | Add | ❌ MISSING |
| Network | HTTP/1.1 | Core | ✅ WORKING (plaintext) |
| Network | HTTP/2 | Core | ❌ MISSING |
| Network | HTTP/3 | Add | ❌ MISSING |
| Network | QUIC | Add | ❌ MISSING |
| Network | WebTransport | Roadmap | ❌ MISSING |
| Network | DNS | Add | ❌ MISSING |
| Network | DoH/DoT Observation | Add | ❌ MISSING |
| Network | Packet Diagnostics | Add | 🟡 PARTIAL (packets/bytes only) |
| TLS | Certificate Diagnostics | Add | 🔴 STUB (SNI only) |
| TLS | Trust Diagnostics | Add | ❌ MISSING |
| TLS | Pinning Diagnostics | Add | ❌ MISSING |
| Automation | Rules | Add | 🔴 STUB (persisted, not applied) |
| Automation | Replay | Add | 🟡 PARTIAL (cURL export only) |
| Automation | Mocking | Add | ❌ MISSING |
| Automation | Chaos Testing | Add | ❌ MISSING |
| Automation | Flow Triggers | Existing/Extend | 🔴 STUB (node type exists, no trigger) |

---

## Detailed Gap Matrix: Requestr-Feature-Vision-V3.md

| Feature | Spec Section | Codebase Status | Verdict |
|---------|--------------|-----------------|---------|
| **1. Interceptr** | | | |
| HTTP/1.1, HTTP/2, HTTPS, WS, SSE, REST, GraphQL, multipart, JSON, XML, binary, protobuf, chunked | §1 | HTTP/1.1 plaintext only. HTTP/2, WS, SSE, GraphQL, protobuf — **MISSING** | **PARTIAL** |
| Stable REQ-IDs | §1 | `CapturedTrafficEntity.id` (autoGenerate) — **WORKING** | ✅ |
| Full request record (method, URL, host, path, query, headers, cookies, body, content-type, connection, TLS, timing, response, metadata) | §1 | Model has all fields. **Only plaintext HTTP populates them.** TLS: empty bodies/headers. Timing: all zeros. TLS info: empty. | **PARTIAL** |
| Traffic timeline | §1.1 | `InterceptScreen` shows list with method/status/latency — **WORKING** | ✅ |
| Filtering (method, host, path, status, duration, content-type, protocol, application, tags) | §1.2 | Basic host/url search only. **No structured filters.** | **PARTIAL** |
| Request inspector (Overview/Request/Response/Headers/Query/Cookies/Body/Raw/Timing/TLS/Related) | §1.3 | `InterceptDetailScreen` not found — only list. **No detail inspector.** | **MISSING** |
| WebSocket laboratory | §1.4 | **MISSING** | ❌ |
| SSE inspector | §1.5 | **MISSING** | ❌ |
| TLS diagnostics (version, cipher, cert, issuer, validity, hostname, ALPN, HTTP version, handshake timing) | §1.6 | **MISSING** (SNI only) | ❌ |
| Failure diagnostics (handshake failed, cert rejected, pinning suspected, bypassed, HTTP/2 failed, HTTP/3 not captured) | §1.6 | **MISSING** | ❌ |
| **2. API Testr** | | | |
| Request composer (Method, URL, Params, Headers, Cookies, Auth, Body, Multipart, Scripts, Assertions) | §2.1 | **FULLY IMPLEMENTED** — All tabs, body modes, auth types, scripts, assertions UI. | ✅ |
| Body modes (JSON, Raw, Form, Multipart, XML, GraphQL, Binary) | §2.1 | JSON/Form/Multipart/Raw/GraphQL/Binary — **WORKING** | ✅ |
| Auth (Bearer, Basic, API Key, OAuth2, Custom, Cookie) | §2.2 | **WORKING** | ✅ |
| Secrets separation | §2.2 | `EnvironmentRepository` + `substitute()` — **WORKING** | ✅ |
| Replay (Replay/Duplicate/Edit&Replay/Open in API Testr/Export/Save) | §2.3 | Save/Star/Export cURL/Code Snippet work. **Replay/Duplicate/Edit&Replay missing.** | **PARTIAL** |
| Parameterization ({{BASE_URL}}, {{TOKEN}}, {{USER_ID}}, {{TIMESTAMP}}) | §2.4 | `EnvironmentRepository.substitute()` + built-in vars — **WORKING** | ✅ |
| Assertions (status, body JSONPath, header, response_time with operators) | §2.5 | `AssertionRunner` + `Assertion` model + UI — **WORKING** | ✅ |
| Environments (Local/Dev/Staging/Prod/Custom + variables + secret masking) | §2.6 | **WORKING** | ✅ |
| Diff (request vs request, response vs response, structural JSON) | §2.7 | **MISSING** | ❌ |
| **3. Tools** | | | |
| 18 tools with dedicated workspaces | §3 | **ALL 18 WORKSPACES IMPLEMENTED** in `ToolWorkspaceScreen.kt` | ✅ |
| Encoding/Decoding (Base64, URL, HTML, JWT, Hex, Binary, Unicode, JSON, XML, URL parser, Query parser) | §3.1 | **WORKING** | ✅ |
| Crypto (Hash, HMAC, AES, RSA, ECDSA, JWT sign/verify, Key converters, HKDF, PBKDF2, SHA, MD5) | §3.2 | Hash/HMAC/AES/XOR/CRC32 — **WORKING**. RSA/ECDSA/JWT sign/HKDF/PBKDF2 — **MISSING** | **PARTIAL** |
| Text/Data (Regex, JSONPath, Diff, UUID, Timestamp, Random, CSV, Text transforms) | §3.3 | UUID/Random/Lorem/JSON/SQL/IP — **WORKING**. Regex/JSONPath/Diff/Timestamp/CSV — **MISSING** | **PARTIAL** |
| Network/Dev (IP parser, CIDR, DNS, URL inspector, Header analyzer, status codes, UA parser, TLS info, cURL builder, code generators, OpenAPI helpers, Schema generator) | §3.4 | IP calc/cURL builder/code gens — **WORKING**. DNS/URL inspector/Header analyzer/TLS info/OpenAPI/Schema — **MISSING** | **PARTIAL** |
| Tool UX (Input/Options/Output/Copy/Clear/History/Save) | §3.5 | Input/Output/Copy/Clear — **WORKING**. History/Save — **MISSING** | **PARTIAL** |
| **4. AI Chat** | | | |
| API-backed AI (capture → tool generation) | §4.1 | **MISSING** | ❌ |
| Tool execution with capabilities (hosts, methods, param schema, auth, timeout, rate) | §4.2 | **MISSING** | ❌ |
| Scrape → Tool (URL/schema/capture → endpoint extraction → param detection → schema inference → reusable tool) | §4.3 | **MISSING** | ❌ |
| **5. Flow** | | | |
| Nodes (Trigger, HTTP, Delay, Condition, Transform, JSON Parse/Extract, Loop, Merge, Filter, Webhook, Notification, DB, File, AI, Interceptr Event, API Test, Tool) | §5.1 | API/Intercept Trigger/Transform/Condition/Text/Tool/Output — **UI exists**. HTTP/Delay/Loop/Merge/Filter/Webhook/Notification/DB/File/AI/API Test — **MISSING** | **PARTIAL** |
| Triggers (Manual, Schedule, Webhook, Captured Request, Response Match, File Change, Timer, App Event) | §5.2 | **Only Manual (Run button).** Others **MISSING** | ❌ |
| Variables ({{trigger.body}}, {{response.status}}, {{response.body}}, {{loop.item}}, {{env.API_KEY}}) | §5.3 | `EnvironmentRepository.substitute()` works for env vars. **No trigger/response/loop vars.** | **PARTIAL** |
| Error handling (success/failure/timeout/cancelled/skipped + retry/backoff/timeout/fallback/error branch/continue/stop) | §5.4 | **MISSING** | ❌ |
| Persistent execution state (survive inactivity, respect Android bg limits) | §5.5 | **In-memory only.** No persistence. | ❌ |
| **6. Music** | | | |
| Local library, folders, playlists, queue, shuffle, repeat, search, metadata, album art, equalizer, sleep timer, background playback | §6 | Local library + playback controls + mini-player — **WORKING**. Folders/playlists/queue reorder/shuffle/repeat/equalizer/sleep timer — **MISSING** | **PARTIAL** |
| Flow/API integrations (complete→play, failed→alert) | §6 | **MISSING** | ❌ |
| **7. Traffic → API Discovery** | §7 | **MISSING** | ❌ |
| **8. HAR Import** | §8 | **MISSING** | ❌ |
| **9. API Schema Reconstruction** | §9 | **MISSING** | ❌ |
| **10. Rules Engine** | §10 | Rules persisted, **not applied** | 🔴 STUB |
| **11. Response Mocking** | §11 | Enum exists, **no engine** | ❌ |
| **12. Chaos Testing** | §12 | **MISSING** | ❌ |
| **13. Search** | §13 | Basic LIKE only | 🟡 PARTIAL |
| **14. Export** | §14 | cURL/Python/JS/Kotlin/Go/cURL — **WORKING** | ✅ |
| **15. Workspace** | §15 | **MISSING** (no workspace system) | ❌ |
| **16. Local-first** | §16 | Room + DataStore — **WORKING** | ✅ |
| **17. Security/Secrets** | §17 | Environment variables, no dedicated secret store | 🟡 PARTIAL |
| **18. Plugin System** | §18 | **MISSING** | ❌ |
| **19. Local API** | §19 | **MISSING** | ❌ |
| **20. CLI** | §20 | **MISSING** | ❌ |
| **21. Trace → Understand → Reproduce** | §21 | **MISSING** | ❌ |
| **22. AI + Interceptr + Flow** | §22 | **MISSING** | ❌ |
| **23. Rich UI** | §23 | Compose Material3 — **GOOD FOUNDATION** | ✅ |
| **24. Mobile UX** | §24 | Bottom sheets/tabs/expandable/search — **WORKING** | ✅ |
| **25. Navigation** | §25 | Pill nav + drawer — **WORKING** | ✅ |
| **26. Implementation Priorities** | §26 | See Phase Status below | |

---

## Phase Status (Vision V3 §26)

| Phase | Items | Status |
|-------|-------|--------|
| **Phase A: Networking** | 1. Interceptr, 2. HTTP/HTTPS, 3. Request/response storage, 4. WebSocket, 5. SSE, 6. TLS diagnostics, 7. Traffic timeline, 8. Search/filter | **1,2,3,7: PARTIAL** — VPN_TUN + HTTP/1.1 plaintext + Room storage + timeline + basic search. **4,5,6,8: MISSING** |
| **Phase B: API Workbench** | 1. API Testr, 2. Request composer, 3. Environments, 4. Authentication, 5. Replay, 6. Assertions, 7. Diff, 8. Exporters | **1,2,3,4,6,8: WORKING**. **5,7: MISSING** |
| **Phase C: Programmability** | 1. Rules, 2. Transformations, 3. Mocking, 4. Scripts, 5. Plugins, 6. Local API, 7. CLI | **4: WORKING** (pre/post scripts in API Testr). **1: STUB** (persisted not applied). **2,3,5,6,7: MISSING** |
| **Phase D: Automation** | 1. Flow engine, 2. Persistent execution state, 3. Scheduler, 4. Triggers, 5. Retry/backoff, 6. Error branches, 7. Notifications, 8. Background execution | **1: STUB** (UI + executor for API only). **2-8: MISSING** |
| **Phase E: Intelligence** | 1. API discovery, 2. Schema inference, 3. HAR analysis, 4. AI tool generation, 5. AI request analysis, 6. AI Flow creation | **ALL MISSING** |
| **Phase F: Product** | 1. Rich UI, 2. Workspace system, 3. History, 4. Import/export, 5. Music, 6. Command palette, 7. Mobile optimization, 8. Plugin architecture | **1,5: PARTIAL**. **2,3,4,6,7,8: MISSING** |

---

## v3.1 Stub Elimination Plan — Detailed Status

### Part A: Critical Stub Fixes (Trust-Breaking)

| Item | Description | Status | Evidence |
|------|-------------|--------|----------|
| **A1** | Setup Wizard: Theme selection collision | 🟡 **PARTIAL** | `SettingsRepository.updateThemeSelection()` unified write path exists. But wizard doesn't set `appearanceMode` → cold boot ignores pick (REG-05). |
| **A2** | Setup Wizard: Permissions fake | ✅ **DONE** | `OnboardingScreen` uses `ActivityResultContracts.RequestPermission()` + `ContextCompat` re-check. Real dialogs. |
| **A3** | Setup Wizard: Logo placeholder | ✅ **DONE** | `RequestrWordmark` composable with live dot used in wizard + Home. |
| **A4** | API Testr: Save/Star no effect/feedback | ✅ **DONE** | `ApiCollectionsRepository.saveRequest()` + `toggleFavorite()` write Room + Snackbar. `SavedApisScreen` reads from Room. |

### Part B: Settings Page Stub Audit

| Item | Description | Status | Evidence |
|------|-------------|--------|----------|
| **B1** | Theme/Accent architecture bug | ✅ **DONE** | `UserSettingsState` has `appearanceMode` (SYSTEM/LIGHT/DARK) + `selectedLightTheme`/`selectedDarkTheme` + `themeMode`. `updateThemeSelection()` writes correctly. Light theme shows selection. |
| **B2** | PiP mode | ✅ **REMOVED** | Correctly removed — no video surface. |
| **B3** | Typography/Surface/Border sub-settings | ✅ **REMOVED** | Correctly removed — no rendering hooks. |
| **B4** | Haptic feedback | ✅ **DONE** | `rememberAppHapticFeedback()` gates real `performHapticFeedback` at call sites. |
| **B5** | Gesture navigation | ✅ **DONE** | Gates `ModalNavigationDrawer.gesturesEnabled`. |
| **B6** | Tablet mode | ✅ **REMOVED** | Correctly removed — no adaptive layout. |
| **B7** | Compact/Comfortable density | ✅ **DONE** | `ProvideDensityConfig` reads `densityMode` → `LocalAppSpacing`. |
| **B8** | Animation settings | ✅ **DONE** | `ProvideAnimationConfig` reads `animationEnabled` + `transitionBehavior`. |
| **B9** | Traffic & Network settings | 🟡 **PARTIAL** | Toggles (`trafficCaptureEnabled`, `requestLoggingEnabled`, `responseLoggingEnabled`) persist to DataStore. **But `CaptureEngine` reads `.value` snapshot** — not live. |
| **B10** | Advanced Features section | ✅ **REMOVED** | Correctly removed — zero consumers. |
| **B11** | Certificate settings | 🟡 **PARTIAL** | User-store install via KeyChain ✅. Export PEM ✅. System-store promote wired in Settings but **not auto-triggered on root grant**. No Magisk module flash UI. |
| **B12** | Duplicate "Open full app settings" | ✅ **DONE** | Single link in `InPageMiniSettingsSheet`. |
| **B13** | Theme icon everywhere | ✅ **DONE** | Only on Home (left of avatar) + drawer quick-swap. |

### Part C: Lacking Features Roadmap (11 Features)

| Phase | Item | Description | Status | Evidence |
|-------|------|-------------|--------|----------|
| **1** | **C1** | Single DataStore migration | ✅ **DONE** | `UnifiedDataStore.kt` + `LegacyDataMigration` + all repos migrated. |
| **2** | **C2** | Request chaining | ✅ **DONE** | `ChainExtractionRule` + `JsonFieldExtractor` + `EnvironmentRepository.updateEnvironment` + UI in `ApiTestrScreen`. Verified on-device per plan. |
| **2** | **C3** | Test assertions / test runner | ✅ **DONE** | `Assertion` model + `AssertionRunner` + `ApiCollectionsRepository.runCollectionTests` + UI. |
| **3** | **C4** | WebSocket frame inspector | ❌ **MISSING** | No WS frame parser. No WS tab in inspector. |
| **3** | **C5** | GraphQL support | ❌ **MISSING** | No introspection, no query builder, no variables panel. |
| **4** | **C6** | Traffic analytics dashboard | ❌ **MISSING** | No charting lib, no dashboard screen. |
| **4** | **C7** | Diff view for replay | ❌ **MISSING** | No diff view. No replay engine. |
| **5** | **C8** | Undo on InterceptScreen | ❌ **MISSING** | No undo stack for delete/clear. |
| **5** | **C9** | Keyboard shortcuts | ❌ **MISSING** | No `Modifier.onKeyEvent` handling. |
| **5** | **C10** | Accessibility labels | ❌ **MISSING** | No TalkBack audit. Many icon buttons lack `contentDescription`. |
| **5** | **C11** | Landscape/tablet layout | ❌ **MISSING** | No `WindowSizeClass` adaptive layout. |

---

## What's IMPLEMENTED and WORKING (Don't Touch)

| Area | Components | Status |
|------|------------|--------|
| **VPN_TUN Capture** | `RequestrVpnService`, `PacketPump`, `TcpReassembler`, `HttpParser`, `CaptureEngine` | ✅ Captures plaintext HTTP/1.x, tracks flows, persists to Room |
| **TLS SNI Extraction** | `TlsDecoder` | ✅ Extracts hostname from ClientHello |
| **CA Generation** | `CertificateAuthority` | ✅ Valid X.509 CA, KeyChain install, PEM export |
| **Root/Shizuku/Frida Probes** | `CapabilityProbe` | ✅ Real probes, real state in `CapabilityState` |
| **Pre-flight Gate** | `PreflightController` | ✅ CA check → app filter → tier resolve → VPN consent |
| **Capture State** | `InterceptionStateRepository` | ✅ Single source of truth for FAB/dot/banner |
| **Capture Log** | `CaptureLogRepository` + Room | ✅ Real traffic only, empty states, long-press menu |
| **Rules Persistence** | `InterceptRuleEntity` + DAO | ✅ Saved to Room, block-existence check works |
| **API Testr Composer** | Full request builder | ✅ All tabs, auth, body modes, scripts |
| **Environments** | `EnvironmentRepository` + substitution | ✅ Unified DataStore, `{{var}}` resolution |
| **Assertions** | `AssertionRunner` + model | ✅ Status/header/body/time with operators |
| **Chain Extractions** | `ChainExtractionRule` + `JsonFieldExtractor` | ✅ Response → env var → next request |
| **Tools (18)** | `ToolsRepository` + `ToolWorkspaceScreen` | ✅ All 18 have dedicated Composable workspaces |
| **Music** | `LocalMusicScanner` + `MusicRepository` | ✅ Local library scan, playback, mini-player |
| **Flow UI** | `FlowScreen` + `FlowRepository` | ✅ Canvas + nodes + connections + add/delete/edit |
| **Settings** | Unified DataStore + `SettingsRepository` | ✅ All toggles persist, theme/accent/density/animation/haptic/gesture work |
| **Onboarding** | Real permissions + theme pick + wordmark | ✅ Real dialogs, real state |
| **Export** | `TrafficExporter.toCurl()` + code snippets | ✅ 10 languages |
| **Architecture** | Single DataStore, Room, Compose, Coroutines | ✅ Solid foundation |

---

## What's PENDING (Must Build — No Negotiation)

### TIER 0: Foundation for Interception Fabric (Blocks Everything)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 1 | **Dispatch `CaptureMethod` to Real Engines** | `CaptureController` must start `RootIptablesEngine` / `ShizukuTunEngine` / `HttpProxyEngine` / `MitmProxyEngine` based on `PreflightController` result. Currently only `RequestrVpnService` runs. | Medium |
| 2 | **Wire `MitmProxyEngine` as Default for HTTPS** | Without this, **zero HTTPS decryption**. CA install is theater. Must run when CA installed + user chooses MITM. | High |
| 3 | **Rules Engine Execution** | `CaptureEngine.ingest()` must call `InterceptRuleDao.getEnabledRules()` and apply actions (Log/Block/Modify/Mock). | Medium |
| 4 | **Live Settings Observation in CaptureEngine** | Replace `SettingsRepository.settings.value` with `SettingsRepository.settings` Flow subscription so toggles take effect immediately. | Low |
| 5 | **Hot-Reload App Filter in VpnService** | Observe `SettingsDataStore.selectedPackages` + re-establish tun on change. | Medium |

### TIER 1: Protocol Support (Modernization §17-22)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 6 | **HTTP/2 Frame Parser** | HPACK decoder + frame parsing on reassembled streams (requires MITM for plaintext frames) | High |
| 7 | **WebSocket Frame Parser** | RFC 6455 framing on HTTP upgrade streams | Medium |
| 8 | **SSE Parser** | Event stream reconstruction | Low |
| 9 | **QUIC/HTTP/3 SNI + Connection Diagnostics** | Parse Initial packets for SNI + connection ID (no decryption without MITM) | High |
| 10 | **DNS Capture + DoH/DoT Detection** | Parse DNS on port 53/443/853 from tun | Medium |

### TIER 2: Correlation & Intelligence (Modernization §6, 24-26, 42)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 11 | **Traffic Classification** | Populate `trafficSource` (Native/WebView/Frida) + `protocolHint` (h1/h2/h3/WS/SSE) on every `CapturedTrafficEntity` | Medium |
| 12 | **Connection Graph** | Navigable DNS → TLS → HTTP/2 → REQ-IDs | High |
| 13 | **Runtime Correlation Engine** | Correlate Frida events (stack trace) → HTTP requests (REQ-ID) | High |
| 14 | **Frida Control Channel** | In-app script injection, output streaming, session management | High |

### TIER 3: Automation & Programmability (Vision V3 §5, 10, 11, 12, 30, 31)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 15 | **Flow Persistence** | Room entities for nodes/connections + DAO + StateFlow backed by Room | Medium |
| 16 | **Flow Executor Complete** | HTTP/Delay/Loop/Merge/Filter/Webhook/Notification/DB/File/AI/API Test/Tool nodes + triggers (Schedule/Webhook/Captured Request/Response Match) | High |
| 17 | **Intercept Trigger Node** | Subscribe to `CaptureLogRepository` + filter by rule → emit trigger event | Medium |
| 18 | **Rules Actions Implementation** | Block (drop), Modify Request/Response (transform), Mock (serve canned), Delay, Redirect, Trigger Flow | Medium |
| 19 | **Response Mocking Engine** | Local mock server per rule, configurable status/latency/body | Medium |
| 20 | **Chaos Testing Engine** | Fault injection percentages per rule | Medium |

### TIER 4: API Workbench Completion (Vision V3 §2, 7, 8, 9, 14)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 21 | **Replay Engine** | Re-execute captured request with parameterization, show diff vs original | Medium |
| 22 | **Diff View** | Structural JSON diff + raw text diff (Myers) | Medium |
| 23 | **HAR Import** | Parse .har → Room entities → open in Interceptr/API Testr/Flow/Tool | Low |
| 24 | **API Discovery** | Aggregate captured traffic → host/endpoint/method/status/content-type/auth patterns | Medium |
| 25 | **Schema Reconstruction** | Infer JSON Schema/OpenAPI/TS/Python/Pydantic from response bodies | Medium |

### TIER 5: Intelligence & AI (Vision V3 §4, 21, 22, 39, 40)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 26 | **AI Chat** | Provider management, streaming, tool execution via capabilities | High |
| 27 | **AI Tool Generation** | Capture/schema → endpoint extraction → param detection → schema inference → tool definition | High |
| 28 | **AI Flow Creation** | Natural language → Flow graph | High |
| 29 | **AI Request Analysis** | Explain request, summarize response, identify patterns | Medium |

### TIER 6: Product Polish (Vision V3 §15, 18, 19, 20, 23, 24, 25)

| # | Feature | Why Required | Effort |
|---|---------|--------------|--------|
| 30 | **Workspace System** | Import/export workspaces (environments, captures, requests, flows, rules, mocks, tools, schemas, collections, history) | Medium |
| 31 | **Plugin Architecture** | Plugin lifecycle + permissions + custom decoders/tools | High |
| 32 | **Local API** | REST endpoints for captures/flows/tools/rules/sessions | Medium |
| 33 | **CLI** | Shared core with Android/Desktop | High |
| 34 | **Command Palette** | Universal access (Ctrl+K) | Low |
| 35 | **Tablet/Adaptive Layout** | `WindowSizeClass` multi-pane | Medium |
| 36 | **Accessibility** | TalkBack audit, contentDescriptions | Low |
| 37 | **Keyboard Shortcuts** | `Modifier.onKeyEvent` map | Low |
| 38 | **Undo on InterceptScreen** | Snackbar undo for destructive actions | Low |

---

## What's EXPLICITLY REMOVED (Correctly)

| Item | Reason |
|------|--------|
| PiP mode | No video surface |
| Typography/Surface/Border sub-settings | No rendering hooks |
| Tablet mode toggle | No adaptive layout |
| Advanced Features section | Zero consumers |
| Crashlytics | Explicitly deprioritized per v3.1 plan |

---

## Critical Path to "Interception Fabric"

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. DISPATCH ENGINES (CaptureController)                         │
│    └─→ RootIptablesEngine / ShizukuTunEngine / HttpProxyEngine │
│    └─→ MitmProxyEngine (when CA installed)                     │
├─────────────────────────────────────────────────────────────────┤
│ 2. MITM PROXY RUNNING                                           │
│    └─→ Per-host certs from CertificateAuthority                │
│    └─→ TLS terminated → plaintext HTTP → CaptureEngine         │
│    └─→ Full TLS diagnostics exposed (version, cipher, cert)    │
├─────────────────────────────────────────────────────────────────┤
│ 3. RULES ENGINE APPLIED                                         │
│    └─→ CaptureEngine reads InterceptRuleDao.getEnabledRules()  │
│    └─→ Actions: Block/Modify/Mock/Log/Tag/Delay/Redirect/Trigger Flow │
├─────────────────────────────────────────────────────────────────┤
│ 4. PROTOCOL PARSERS ON PLAINTEXT STREAMS                        │
│    └─→ HTTP/2 frames (HPACK) → stream-level REQ-IDs            │
│    └─→ WebSocket frames → frame inspector                      │
│    └─→ SSE events → timeline                                   │
│    └─→ QUIC Initial → SNI + connection diagnostics             │
├─────────────────────────────────────────────────────────────────┤
│ 5. CORRELATION ENGINE                                           │
│    └─→ DNS → TLS → HTTP/2 → REQ-IDs (navigable graph)          │
│    └─→ Frida events → stack trace → REQ-ID (runtime correlation)│
│    └─→ Traffic classification (source + protocol) on every row │
├─────────────────────────────────────────────────────────────────┤
│ 6. FLOW + INTERCEPTR INTEGRATION                                │
│    └─→ Intercept Trigger node subscribes to capture events     │
│    └─→ Flow actions: save capture, generate report, notify, run API test │
│    └─→ Persistent Flow state (Room)                            │
├─────────────────────────────────────────────────────────────────┤
│ 7. API WORKBENCH COMPLETION                                     │
│    └─→ Replay + Diff                                           │
│    └─→ HAR Import                                              │
│    └─→ API Discovery + Schema Reconstruction                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Verdict

**The codebase has a solid foundation (VPN_TUN capture, Room persistence, unified DataStore, API Testr, Tools, Environments, Assertions, Chaining) but the interception fabric is a skeleton.**

**The two hardest gaps that unlock everything else:**
1. **`CaptureController` dispatch → `MitmProxyEngine`** (enables HTTPS decryption, TLS diagnostics, HTTP/2, rules on HTTPS)
2. **`CaptureEngine` → Rules Engine** (enables programmable traffic control)

**Without these two, the app remains "an HTTP proxy with many buttons" — exactly what the vision prohibits.**

All other missing features (WebSocket, SSE, HTTP/3, Frida correlation, CDP, AI, Workspace, Plugin, Local API, CLI) are **dependent on the interception fabric working first**. They cannot be meaningfully built on a capture pipeline that only sees plaintext HTTP/1.x.

---

## Recommended Next Steps (Strict Priority Order)

1. **Fix `CaptureController` dispatch** — Route `CaptureMethod` to correct engine
2. **Start `MitmProxyEngine` by default when CA installed** — This is the single highest-impact change
3. **Wire Rules Engine into `CaptureEngine.ingest()`** — Read rules, apply actions
4. **Make `CaptureEngine` observe Settings Flows live** — Toggles take effect immediately
5. **Add HTTP/2 frame parser** — On MITM plaintext streams
6. **Add WebSocket frame parser** — On HTTP upgrade streams
7. **Implement Flow persistence (Room)** — Nodes/connections survive restart
8. **Build Intercept Trigger Flow node** — Capture events → Flow execution
9. **Add Replay + Diff to API Testr** — Parameterized re-execution + structural diff
10. **Implement HAR Import** — Low effort, high value

**Everything else is downstream of these 10.**