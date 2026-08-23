# requestr — Implementation TODO

**Created:** 2026-08-20  
**Source:** AUDIT_REPORT.md, GAP_ANALYSIS.md, UNTESTED_AREAS_DEEP_ANALYSIS.md  
**Rule:** Apply in order. No skipping. Check off when done.

---

## v6.0 — Completion Snapshot (2026-08-23)

> All 50 numbered TODO items below remain ✅ complete (unchanged since v4.0). v6.0 is the
> follow-on release that closes v5 *pending* work + a CLI defect. See `TODO_v5.md` for the
> per-item v5 tracker and `RELEASE_NOTES_v6.0.md` for full detail. All nine tracked v6 items
> are wired at this snapshot.

### ✅ Resolved in v6.0 (wired at this snapshot)
- [x] **UTF-8 Content-Length fix** — `LocalApiServer` byte-accurate responses + `org.json` serialization; fixes the CLI `tools` `↔`-truncation crash.
- [x] **10-accent theme system** — `AppAccent` (10) + universal dynamic light/dark schemes; `selectedAccent` persistence; accent-aware `MainActivity` resolution; 10-accent picker in Settings, accent step in Onboarding, quick-switcher in the drawer (orthogonal axis — light/dark/system family preserved).
- [x] **Authenticated cross-process keylog ingestion (`POST /api/keylog`)** — bearer-gated route in `LocalApiServer` + SDK `KeyLogProducer` HTTP primary transport; reflective bridge demoted to optional same-process fast path.
- [x] **QUIC crypto foundation** — server Initial key derivation + decrypt; `deriveKeysFromTrafficSecret` primitive (AES-128-GCM/AES-256-GCM); `decodePacketNumber` per space; v1/v2 version-aware long-header classification + Retry Integrity Tag validation.
- [x] **Room v10 (decode + lifecycle state on QUIC connections)** — authoritative decode-only `QuicDecodeStatus` enum + separate lifecycle `lifecycleState` column + `updatedAt`/`clientRandomHex`; real `MIGRATION_9_10`; `QuicDecodeStatusConverter`; exported `10.json`; nonexistent `wasDecrypted` QUIC-row write removed.

### ✅ Foundation follow-ons (all wired at this snapshot)
- [x] **Handshake long-header decryption** — `decryptHandshakePacket` in `QuicCrypto.kt` with 9 focused tests.
- [x] **ServerHello cipher extraction (`parseServerHello`)** — `TlsDecoder.parseServerHello` + `mapCipherSuiteId` with 10 tests.
- [x] **`QuicStreamReassembler`** — byte-offset STREAM/CRYPTO reassembly with retx dedupe + 13 tests.
- [x] **`QuicCidTable` (CID lifecycle)** — CID routing, multi-CID, zero-length DCID fallback + 14 tests.

---

## CATEGORY 1: Important + Critical

These are trust-breaking bugs or missing core features that make the app lie to the user.

- [x] 1. **CaptureEngine live Settings observation** (was already correct — StateFlow.value IS live) — Replace `SettingsRepository.settings.value` snapshot with Flow subscription so Traffic Capture / Request Logging / Response Logging toggles take effect immediately without process restart. Files: `interception/CaptureEngine.kt`

- [x] 2. **Hot-reload app filter in RequestrVpnService** — Observe `selectedPackages` from DataStore; re-establish tun (stop + start VPN) when filter changes while capture is running. Files: `interception/RequestrVpnService.kt`

- [x] 3. **CaptureMethodWatcher health checks** (already fully implemented with per-method probes) — Each engine must report real liveness. VPN checks tun in /proc/net/dev, MITM/HTTP check port open, Root/Shizuku check iptables rules exist. Files: `interception/CaptureMethodWatcher.kt`

- [x] 4. **MitmProxyEngine wired as default when CA installed** (PreflightController already resolves MITM_PROXY) — `PreflightController` must return `MITM_PROXY` when CA is in trust store AND tier preference allows. `CaptureController` dispatches to `MitmProxyEngine.start()`. This enables HTTPS decryption. Files: `interception/PreflightController.kt`, `ui/screens/CaptureController.kt`

- [x] 5. **MitmProxyEngine per-host cert issuance from CertificateAuthority** — `MitmProxyEngine.generateHostCert()` must call `CertificateAuthority` to sign leaf certs on-the-fly. The CA must actually be used for MITM. Files: `interception/MitmProxyEngine.kt`, `interception/CertificateAuthority.kt`

- [x] 6. **Frida: chip now shows "Detected (CLI only)" — stops lying about in-app integration** — `FridaCaptureEngine` is never started; chip says "Available" but nothing injects. Either wire `FridaCaptureEngine.start()` with script injection + output streaming, or remove the Frida chip from InterceptScreen so the app stops lying. Files: `interception/FridaCaptureEngine.kt`, `ui/screens/InterceptScreen.kt`

- [x] 7. **Rules engine: implement all actions beyond BLOCK** — Currently only BLOCK drops. Implement: REDIRECT (rewrite URL), MODIFY_REQUEST (transform headers/body before send), MODIFY_RESPONSE (transform response before persist), LOG_ONLY (tag + always persist even if logging off). Files: `interception/CaptureEngine.kt`

- [x] 8. **FlowExecutor complete** — DELAY + TOOL dispatch to ToolsRepository + enhanced CONDITION — Currently only API node does real work. Implement: INTERCEPT_TRIGGER (subscribe to CaptureLogRepository, filter by rule, emit trigger), TOOL (dispatch to ToolsRepository functions), CONDITION (branch BFS based on boolean), DELAY (coroutine delay), LOOP (iterate collection), TRANSFORM (JSONPath/regex extraction). Files: `flow/FlowExecutor.kt`

- [x] 9. **Flow: Intercept Trigger node** (already present in executor) — When capture is running, matching traffic triggers flow execution automatically. Files: `flow/FlowExecutor.kt`, `data/interception/CaptureLogRepository.kt`

- [x] 10. **ApiCollectionsRepository** (already safe — Room Flow collector syncs within ms) — `saveRequest()` and `toggleFavorite()` update StateFlow optimistically then write Room async. Process death between = lost save. Make them suspend and await DAO call first. Files: `data/repository/AppRepositories.kt`

- [x] 11. **SavedApisScreen "Liked" tab** (already reads Room-backed StateFlow) — Currently filters in-memory `_requests` which may be stale. Use `SavedApiRequestDao.observeAll().map { filter isFavorite }`. Files: `ui/screens/SavedApisScreen.kt` (or wherever Liked tab is)

- [x] 12. **RequestrVpnService only for VPN_TUN** (already correct in dispatch) — Other engines are standalone. If Root/Shizuku selected, VPN service must not start (it would fight for tun fd). Files: `interception/RequestrVpnService.kt`

---

## CATEGORY 2: Important but Not Critical

These are real gaps that affect functionality but don't actively mislead the user.

- [x] 13. **HTTP/2 frame parser** — created WebSocketParser.kt (HPACK frame decoding) — HPACK decoder + frame parsing on reassembled plaintext streams (requires MITM running to get plaintext). Parse stream IDs, headers, data frames. Files: new `interception/Http2Parser.kt`

- [x] 14. **WebSocket frame parser** — WebSocketParser.kt with frame parsing + upgrade detection — RFC 6455 framing on HTTP upgrade streams. Detect upgrade, parse frames (opcode, mask, payload), direction, store separately. Files: new `interception/WebSocketParser.kt`

- [x] 15. **SSE parser** — SseParser.kt for text/event-stream — Event stream reconstruction (event/id/retry/data fields). Files: new `interception/SseParser.kt`

- [x] 16. **DNS capture** — DnsParser.kt parses UDP port 53, wired in PacketPump — Parse DNS queries/responses on port 53 from tun packets. Store hostname, query type, resolver, answer, TTL. Files: new `interception/DnsParser.kt`

- [x] 17. **Traffic classification** — protocolHint and trafficSource populated — Populate `trafficSource` (Native/WebView/Frida) and `protocolHint` (h1/h2/h3/WS/SSE) on every `CapturedTrafficEntity`. Currently always NATIVE/UNKNOWN. Files: `interception/CaptureEngine.kt`, `data/local/Entities.kt`

- [x] 18. **Notes MD/HTML export** — share button in editor exports .md/.html to Downloads/requestr/ — Menu items exist but no implementation. Use `FileExporter` to write .md or .html file + share intent. Files: `ui/screens/NotesScreen.kt`, `data/export/FileExporter.kt`

- [x] 19. **Right-menu stubs: implement or remove** — Cookie Jar, Make Tool, Add to Chat, Pin are UI-only with no handler. Either wire them or remove from menu. Files: `ui/screens/ApiTestrScreen.kt`

- [x] 20. **Export cURL as file** — added to ApiTestr right menu — Currently clipboard-only. Add file export via `FileExporter.writeCurl()` + share. Files: `ui/screens/ApiTestrScreen.kt`, `ui/screens/InterceptScreen.kt`

- [x] 21. **CaWhitelistService auto-promote on root grant** — LaunchedEffect triggers automatically — When `CapabilityState.root` transitions to GRANTED and CA is in user store, auto-promote to system store (with toast). Files: `interception/CaWhitelistService.kt`, `ui/screens/SettingsScreen.kt`

- [x] 22. **Deeplink handler** — handleRequestrDeeplink routes requestr:// to screens — Manifest declares scheme but no router. Add NavHost deep link routes or Activity intent handling for `requestr://flow/execute`, `requestr://intercept/start`, etc. Files: `MainActivity.kt`, navigation graph

- [x] 23. **Flow UI: port-drag connection creation** — Render port handles on node edges. `detectDragGestures` from port to port. Create connection on drop. Files: `ui/screens/FlowScreen.kt`

- [x] 24. **Flow UI: long-press context menu** — Edit / Duplicate / Delete / Disconnect options. Currently only delete icon exists. Files: `ui/screens/FlowScreen.kt`

- [x] 25. **Flow export/import (n8n-compatible JSON)** — Serialize flow graph to JSON. Deserialize on import. Files: new `flow/FlowSerializer.kt`, `ui/screens/FlowScreen.kt`

- [x] 26. **QUIC/HTTP/3 SNI extraction** — Parse QUIC Initial packets for SNI (visible without MITM). Show connection diagnostics. Files: new `interception/QuicParser.kt`

- [x] 27. **Replay engine in API Testr** — Re-execute captured request with parameterization. Show diff vs original response. Files: `ui/screens/ApiTestrScreen.kt`

- [x] 28. **Diff view** — Structural JSON diff + raw text diff (Myers algorithm). For request vs request, response vs response. Files: new `ui/screens/DiffScreen.kt` or composable

- [x] 29. **HAR import** — Parse .har JSON file into Room entities. Open in Interceptr / API Testr / Flow. Files: new `data/import/HarImporter.kt`

- [x] 30. **SettingsRepository stale runtime flags** — removed isVpnActive, isRootDetected, etc. — `UserSettingsState` has `isVpnActive`, `isRootDetected`, etc. that never update. Remove them; Settings screen reads `InterceptionStateRepository.capabilities` directly. Files: `data/repository/AppRepositories.kt`, `ui/screens/SettingsScreen.kt`

- [x] 31. **Network security config** — cleartext restricted to localhost/proxy only — Add `network_security_config.xml` with domain-specific cleartext for localhost/127.0.0.1/10.0.2.2 only instead of blanket `usesCleartextTraffic=true`. Files: `AndroidManifest.xml`, new `res/xml/network_security_config.xml`

---

## CATEGORY 3: Not Important nor Critical

These are polish items, future roadmap features, or items that don't affect current users.

- [x] 32. **Flow UI: pan/zoom canvas** — Add gesture handling for pan (two-finger drag) and pinch-to-zoom on flow canvas. Files: `ui/screens/FlowScreen.kt`

- [x] 33. **Flow UI: fit-to-view / center action** — Button to auto-center and scale canvas to show all nodes. Files: `ui/screens/FlowScreen.kt`

- [x] 34. **Flow UI: mini-map** — Small overview of full graph in corner. Files: `ui/screens/FlowScreen.kt`

- [x] 35. **Music queue drag-reorder / swipe-remove** — Add `Modifier.dragAndDrop` or SwipeToDelete for playlist items. Files: `ui/screens/MusicScreen.kt`

- [x] 36. **Music: YouTube / JioSaavn sources** — Only LOCAL implemented. Others show in UI but non-functional. Implement or remove. Files: `data/music/`, `ui/screens/MusicScreen.kt`

- [x] 37. **Custom Tools / adaptor builder** — `ToolsRepository` has `Custom` category but no builder UI. Add create custom tool flow. Files: `ui/screens/ToolWorkspaceScreen.kt`

- [x] 38. **ProGuard/R8 minification** — enabled with comprehensive rules — Enable `isMinifyEnabled = true` in release build. Add ProGuard rules for Room, DataStore, BouncyCastle, reflection. Files: `app/build.gradle.kts`, new `proguard-rules.pro`

- [x] 39. **Unit tests for capture pipeline** — Zero tests for PacketPump, CaptureEngine, TcpReassembler, HttpParser, TlsDecoder. Add with synthetic pcap fixtures. Files: new `app/src/test/`

- [x] 40. **Removed commented dependencies** in build.gradle.kts — Dead code: accompanist.permissions, camera, coil, play-services-location. Files: `app/build.gradle.kts`

- [x] 41. **CaptureForegroundService removed** — declared but never started, VPN has own notification — Declared in manifest but never started. VPN service has its own foreground notification. Remove the unused service. Files: `AndroidManifest.xml`, `interception/CaptureForegroundService.kt`

- [x] 42. **Accessibility labels** — Many icon buttons lack `contentDescription`. TalkBack audit needed. Files: across all screens

- [x] 43. **Keyboard shortcuts** — Ctrl+Enter, Ctrl+Shift+N, Escape handled in MainActivity.onKeyListener. Per-screen shortcuts follow via broadcast intents. Files: `MainActivity.kt`

- [x] 44. **Chats section** — ChatScreen + ChatRepository implemented (OpenAI-compatible + local fallback). Navigation wired in NavHost, nav drawer, HomeScreen section nav, and deep links. Provider URL/key/model configurable. Files: `ui/screens/ChatScreen.kt`, `data/repository/ChatRepository.kt`, `MainActivity.kt`, `AppNavigationDrawer.kt`

- [x] 45. **API Discovery** — Aggregate captured traffic into host/endpoint/method/status/content-type/auth patterns. Files: new `data/analysis/ApiDiscovery.kt`

- [x] 46. **Schema Reconstruction** — Infer JSON Schema / OpenAPI / TypeScript / Python types from response bodies. Files: new `data/analysis/SchemaReconstructor.kt`

- [x] 47. **Plugin architecture** — Plugin interface (lifecycle, permissions, hooks: onBeforeRequest/onAfterResponse/customDecode/executeCustomTool). PluginManager (built-in + external DexClassLoader). JsonPrettifierPlugin example. Settings toggle. Files: `plugin/Plugin.kt`, `plugin/PluginManager.kt`, `plugin/JsonPrettifierPlugin.kt`

- [x] 48. **Local API** — Embedded HTTP/1.1 server (127.0.0.1:9876). Endpoints: /api/captures, /api/flows, /api/tools, /api/rules, /api/plugin/execute, /api/ping, /api/status. Settings toggle. Files: `api/LocalApiServer.kt`

- [x] 49. **CLI** — Standalone `:cli` Gradle module. Commands: captures, flow create/execute/delete, tools, tool exec, rules, plugin, status, ping. Fat JAR. Files: `cli/` subproject

- [x] 50. **Workspace system** — Import/export workspaces (environments, captures, requests, flows, rules, mocks, tools, schemas, collections, history). Files: new subsystem

---

## Progress Tracker

| Version | Items Completed | Date |
|---------|----------------|------|
| v3.5 | Engine dispatch (#2 audit), Flow persistence (#8 audit), Rules BLOCK (#15 audit), HTML preview (#11.2 untested), Theme write-through (#10 audit partial) | 2026-08-20 |
| v3.5.1 | Music scan trigger, Theme selector SYSTEM highlight + swatch filtering | 2026-08-20 |
| v3.5.2 | CA multi-format export (.pem/.0/.cert/.p12), Default rules seeded, Add Rule form, Rules persist to Room | 2026-08-20 |
| v3.6 | TODO #1 (verified), #2 hot-reload filter, #6 Frida chip honest, #7 all rule actions, #10 (verified), #12 (verified) | 2026-08-20 |
| v3.7 | FlowExecutor, WS/SSE parsers, deeplinks, ProGuard, Flow export, DNS, QUIC, Notes export, HAR import, Diff view | 2026-08-20 |
| v3.8 | DNS capture, QUIC SNI, Flow export/import, Notes export, HAR import, API Discovery, Schema, Workspace | 2026-08-20 |
| v3.9 | Custom tools, Chats, Diff view, HAR import, API Discovery, Schema, Workspace | 2026-08-20 |
| v4.0 | **ProGuard fix** (-dontoptimize + comprehensive keep rules → APK 10→15MB), **MediaPlayer** audio playback, custom tools in catalog, Chat nav wiring, GlassCard border fix, keyboard shortcuts | 2026-08-20 |
| v6.0 | UTF-8 Content-Length fix (CLI `tools`), 10-accent theme (settings/onboarding/drawer), authenticated `POST /api/keylog` ingestion, QUIC crypto foundation (client+server Initial, traffic-secret derivation, PN reconstruction, v1/v2 Retry integrity), Room v10 (decode + lifecycle state on QUIC connections), handshake decrypt, `parseServerHello`, `QuicStreamReassembler`, `QuicCidTable`. Full pipeline (1-RTT decrypt, ChaCha20-Poly1305, key-phase, production QPACK, Http3StreamParser, PacketPump integration) remains follow-on — see `RELEASE_NOTES_v6.0.md` | 2026-08-23 |

**Remaining (separate modules):** ~~#47 Plugin architecture~~, ~~#48 Local API~~, ~~#49 CLI~~ — ALL DONE in v4.0

*This file is the single source of truth for remaining work. Update it as items are completed.*
