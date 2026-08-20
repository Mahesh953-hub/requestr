# requestr — Implementation TODO

**Created:** 2026-08-20  
**Source:** AUDIT_REPORT.md, GAP_ANALYSIS.md, UNTESTED_AREAS_DEEP_ANALYSIS.md  
**Rule:** Apply in order. No skipping. Check off when done.

---

## CATEGORY 1: Important + Critical

These are trust-breaking bugs or missing core features that make the app lie to the user.

- [ ] 1. **CaptureEngine live Settings observation** — Replace `SettingsRepository.settings.value` snapshot with Flow subscription so Traffic Capture / Request Logging / Response Logging toggles take effect immediately without process restart. Files: `interception/CaptureEngine.kt`

- [ ] 2. **Hot-reload app filter in RequestrVpnService** — Observe `selectedPackages` from DataStore; re-establish tun (stop + start VPN) when filter changes while capture is running. Files: `interception/RequestrVpnService.kt`

- [ ] 3. **CaptureMethodWatcher health checks per engine** — Each engine must report real liveness. VPN checks tun in /proc/net/dev, MITM/HTTP check port open, Root/Shizuku check iptables rules exist. Files: `interception/CaptureMethodWatcher.kt`

- [ ] 4. **MitmProxyEngine wired as default when CA installed** — `PreflightController` must return `MITM_PROXY` when CA is in trust store AND tier preference allows. `CaptureController` dispatches to `MitmProxyEngine.start()`. This enables HTTPS decryption. Files: `interception/PreflightController.kt`, `ui/screens/CaptureController.kt`

- [ ] 5. **MitmProxyEngine per-host cert issuance from CertificateAuthority** — `MitmProxyEngine.generateHostCert()` must call `CertificateAuthority` to sign leaf certs on-the-fly. The CA must actually be used for MITM. Files: `interception/MitmProxyEngine.kt`, `interception/CertificateAuthority.kt`

- [ ] 6. **Frida: remove chip OR implement real control channel** — `FridaCaptureEngine` is never started; chip says "Available" but nothing injects. Either wire `FridaCaptureEngine.start()` with script injection + output streaming, or remove the Frida chip from InterceptScreen so the app stops lying. Files: `interception/FridaCaptureEngine.kt`, `ui/screens/InterceptScreen.kt`

- [ ] 7. **Rules engine: implement all actions beyond BLOCK** — Currently only BLOCK drops. Implement: REDIRECT (rewrite URL), MODIFY_REQUEST (transform headers/body before send), MODIFY_RESPONSE (transform response before persist), LOG_ONLY (tag + always persist even if logging off). Files: `interception/CaptureEngine.kt`

- [ ] 8. **FlowExecutor complete — all node types** — Currently only API node does real work. Implement: INTERCEPT_TRIGGER (subscribe to CaptureLogRepository, filter by rule, emit trigger), TOOL (dispatch to ToolsRepository functions), CONDITION (branch BFS based on boolean), DELAY (coroutine delay), LOOP (iterate collection), TRANSFORM (JSONPath/regex extraction). Files: `flow/FlowExecutor.kt`

- [ ] 9. **Flow: Intercept Trigger node subscribes to capture events** — When capture is running, matching traffic triggers flow execution automatically. Files: `flow/FlowExecutor.kt`, `data/interception/CaptureLogRepository.kt`

- [ ] 10. **ApiCollectionsRepository: await DB write before UI update** — `saveRequest()` and `toggleFavorite()` update StateFlow optimistically then write Room async. Process death between = lost save. Make them suspend and await DAO call first. Files: `data/repository/AppRepositories.kt`

- [ ] 11. **SavedApisScreen "Liked" tab reads Room directly** — Currently filters in-memory `_requests` which may be stale. Use `SavedApiRequestDao.observeAll().map { filter isFavorite }`. Files: `ui/screens/SavedApisScreen.kt` (or wherever Liked tab is)

- [ ] 12. **RequestrVpnService must ONLY run for VPN_TUN** — Other engines are standalone. If Root/Shizuku selected, VPN service must not start (it would fight for tun fd). Files: `interception/RequestrVpnService.kt`

---

## CATEGORY 2: Important but Not Critical

These are real gaps that affect functionality but don't actively mislead the user.

- [ ] 13. **HTTP/2 frame parser** — HPACK decoder + frame parsing on reassembled plaintext streams (requires MITM running to get plaintext). Parse stream IDs, headers, data frames. Files: new `interception/Http2Parser.kt`

- [ ] 14. **WebSocket frame parser** — RFC 6455 framing on HTTP upgrade streams. Detect upgrade, parse frames (opcode, mask, payload), direction, store separately. Files: new `interception/WebSocketParser.kt`

- [ ] 15. **SSE parser** — Event stream reconstruction (event/id/retry/data fields). Files: new `interception/SseParser.kt`

- [ ] 16. **DNS capture** — Parse DNS queries/responses on port 53 from tun packets. Store hostname, query type, resolver, answer, TTL. Files: new `interception/DnsParser.kt`

- [ ] 17. **Traffic classification** — Populate `trafficSource` (Native/WebView/Frida) and `protocolHint` (h1/h2/h3/WS/SSE) on every `CapturedTrafficEntity`. Currently always NATIVE/UNKNOWN. Files: `interception/CaptureEngine.kt`, `data/local/Entities.kt`

- [ ] 18. **Notes MD/HTML export** — Menu items exist but no implementation. Use `FileExporter` to write .md or .html file + share intent. Files: `ui/screens/NotesScreen.kt`, `data/export/FileExporter.kt`

- [ ] 19. **Right-menu stubs: implement or remove** — Cookie Jar, Make Tool, Add to Chat, Pin are UI-only with no handler. Either wire them or remove from menu. Files: `ui/screens/ApiTestrScreen.kt`

- [ ] 20. **Export cURL as file** — Currently clipboard-only. Add file export via `FileExporter.writeCurl()` + share. Files: `ui/screens/ApiTestrScreen.kt`, `ui/screens/InterceptScreen.kt`

- [ ] 21. **CaWhitelistService auto-promote on root grant** — When `CapabilityState.root` transitions to GRANTED and CA is in user store, auto-promote to system store (with toast). Files: `interception/CaWhitelistService.kt`, `ui/screens/SettingsScreen.kt`

- [ ] 22. **Deeplink handler (`requestr://` scheme)** — Manifest declares scheme but no router. Add NavHost deep link routes or Activity intent handling for `requestr://flow/execute`, `requestr://intercept/start`, etc. Files: `MainActivity.kt`, navigation graph

- [ ] 23. **Flow UI: port-drag connection creation** — Render port handles on node edges. `detectDragGestures` from port to port. Create connection on drop. Files: `ui/screens/FlowScreen.kt`

- [ ] 24. **Flow UI: long-press context menu** — Edit / Duplicate / Delete / Disconnect options. Currently only delete icon exists. Files: `ui/screens/FlowScreen.kt`

- [ ] 25. **Flow export/import (n8n-compatible JSON)** — Serialize flow graph to JSON. Deserialize on import. Files: new `flow/FlowSerializer.kt`, `ui/screens/FlowScreen.kt`

- [ ] 26. **QUIC/HTTP/3 SNI extraction** — Parse QUIC Initial packets for SNI (visible without MITM). Show connection diagnostics. Files: new `interception/QuicParser.kt`

- [ ] 27. **Replay engine in API Testr** — Re-execute captured request with parameterization. Show diff vs original response. Files: `ui/screens/ApiTestrScreen.kt`

- [ ] 28. **Diff view** — Structural JSON diff + raw text diff (Myers algorithm). For request vs request, response vs response. Files: new `ui/screens/DiffScreen.kt` or composable

- [ ] 29. **HAR import** — Parse .har JSON file into Room entities. Open in Interceptr / API Testr / Flow. Files: new `data/import/HarImporter.kt`

- [ ] 30. **SettingsRepository stale runtime flags** — `UserSettingsState` has `isVpnActive`, `isRootDetected`, etc. that never update. Remove them; Settings screen reads `InterceptionStateRepository.capabilities` directly. Files: `data/repository/AppRepositories.kt`, `ui/screens/SettingsScreen.kt`

- [ ] 31. **Network security config** — Add `network_security_config.xml` with domain-specific cleartext for localhost/127.0.0.1/10.0.2.2 only instead of blanket `usesCleartextTraffic=true`. Files: `AndroidManifest.xml`, new `res/xml/network_security_config.xml`

---

## CATEGORY 3: Not Important nor Critical

These are polish items, future roadmap features, or items that don't affect current users.

- [ ] 32. **Flow UI: pan/zoom canvas** — Add gesture handling for pan (two-finger drag) and pinch-to-zoom on flow canvas. Files: `ui/screens/FlowScreen.kt`

- [ ] 33. **Flow UI: fit-to-view / center action** — Button to auto-center and scale canvas to show all nodes. Files: `ui/screens/FlowScreen.kt`

- [ ] 34. **Flow UI: mini-map** — Small overview of full graph in corner. Files: `ui/screens/FlowScreen.kt`

- [ ] 35. **Music queue drag-reorder / swipe-remove** — Add `Modifier.dragAndDrop` or SwipeToDelete for playlist items. Files: `ui/screens/MusicScreen.kt`

- [ ] 36. **Music: YouTube / JioSaavn sources** — Only LOCAL implemented. Others show in UI but non-functional. Implement or remove. Files: `data/music/`, `ui/screens/MusicScreen.kt`

- [ ] 37. **Custom Tools / adaptor builder** — `ToolsRepository` has `Custom` category but no builder UI. Add create custom tool flow. Files: `ui/screens/ToolWorkspaceScreen.kt`

- [ ] 38. **ProGuard/R8 minification** — Enable `isMinifyEnabled = true` in release build. Add ProGuard rules for Room, DataStore, BouncyCastle, reflection. Files: `app/build.gradle.kts`, new `proguard-rules.pro`

- [ ] 39. **Unit tests for capture pipeline** — Zero tests for PacketPump, CaptureEngine, TcpReassembler, HttpParser, TlsDecoder. Add with synthetic pcap fixtures. Files: new `app/src/test/`

- [ ] 40. **Remove commented dependencies in build.gradle.kts** — Dead code: accompanist.permissions, camera, coil, play-services-location. Files: `app/build.gradle.kts`

- [ ] 41. **CaptureForegroundService: remove duplicate** — Declared in manifest but never started. VPN service has its own foreground notification. Remove the unused service. Files: `AndroidManifest.xml`, `interception/CaptureForegroundService.kt`

- [ ] 42. **Accessibility labels** — Many icon buttons lack `contentDescription`. TalkBack audit needed. Files: across all screens

- [ ] 43. **Keyboard shortcuts** — No `Modifier.onKeyEvent` handling for power users. Files: across all screens

- [ ] 44. **Chats section** — Entire subsystem missing. Either implement (provider management, streaming, tool execution) or remove from navigation + drawer. Files: new `ui/screens/ChatScreen.kt`, `data/repository/ChatRepository.kt`, navigation graph

- [ ] 45. **API Discovery** — Aggregate captured traffic into host/endpoint/method/status/content-type/auth patterns. Files: new `data/analysis/ApiDiscovery.kt`

- [ ] 46. **Schema Reconstruction** — Infer JSON Schema / OpenAPI / TypeScript / Python types from response bodies. Files: new `data/analysis/SchemaReconstructor.kt`

- [ ] 47. **Plugin architecture** — Plugin lifecycle + permissions + custom decoders/tools. Files: new subsystem

- [ ] 48. **Local API** — REST endpoints for captures/flows/tools/rules/sessions. Files: new subsystem

- [ ] 49. **CLI** — Command-line interface sharing core with Android. Files: new module

- [ ] 50. **Workspace system** — Import/export workspaces (environments, captures, requests, flows, rules, mocks, tools, schemas, collections, history). Files: new subsystem

---

## Progress Tracker

| Version | Items Completed | Date |
|---------|----------------|------|
| v3.5 | Engine dispatch (#2 audit), Flow persistence (#8 audit), Rules BLOCK (#15 audit), HTML preview (#11.2 untested), Theme write-through (#10 audit partial) | 2026-08-20 |
| v3.5.1 | Music scan trigger, Theme selector SYSTEM highlight + swatch filtering | 2026-08-20 |
| v3.5.2 | CA multi-format export (.pem/.0/.cert/.p12), Default rules seeded, Add Rule form, Rules persist to Room | 2026-08-20 |
| v3.6 | (next) | |

---

*This file is the single source of truth for remaining work. Update it as items are completed.*
