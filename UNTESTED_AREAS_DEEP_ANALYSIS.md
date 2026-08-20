# Deep Analysis: Untouched / Environment-Limited Areas — Gap, Issue & Bug Inventory

**Scope:** This file catalogues every gap, issue, and bug found in the codebase areas that **cannot be verified** in a source-only audit (no physical device with root/VPN/Shizuku/Frida/CA). These are the "untested" items from the audit report that require hardware-specific validation — but the *code* for them reveals clear defects regardless.

Each entry includes: **Location**, **Issue**, **Severity**, **Why It Matters**, and **Fix Sketch**.

---

## 1. Interception Engines — All Non-VPN Tiers Are Dead Code

### 1.1 `RootIptablesEngine` — Never Instantiated
**Location:** `interception/RootIptablesEngine.kt`  
**Issue:** Class is fully implemented (probe, start, stop, verify, rule installation/cleanup) but **zero call sites exist** in the codebase. `PreflightController.run()` returns `CaptureMethod.ROOT_IPTABLES` when `RootState.GRANTED`, but `CaptureController.startCaptureAndWatcher()` unconditionally calls `PreflightController.startCapture(context)` → `RequestrVpnService.start()`. The root engine is never started.  
**Severity:** CRITICAL  
**Why It Matters:** User sees "Root (Granted)" chip + tier "Root capture" in UI, but traffic is still captured via VPN_TUN. Transparent iptables REDIRECT rules are never applied.  
**Fix Sketch:** `CaptureController` must dispatch on `CaptureMethod`: `ROOT_IPTABLES` → `RootIptablesEngine.start()`, `SHIZUKU_TUN` → `ShizukuTunEngine.start()`, `MITM_PROXY` → `MitmProxyEngine.start()`, etc.

---

### 1.2 `ShizukuTunEngine` — Never Instantiated
**Location:** `interception/ShizukuTunEngine.kt`  
**Issue:** Identical to Root — implemented but never started. `isAvailable()` probes Shizuku binder + permission correctly, `start()/stop()` install iptables via Shizuku shell, but no call path reaches it.  
**Severity:** CRITICAL  
**Why It Matters:** Shizuku users (non-root, elevated ADB) get VPN dialog instead of transparent Shizuku capture.  
**Fix Sketch:** Same dispatch as above.

---

### 1.3 `HttpProxyEngine` — Never Instantiated
**Location:** `interception/HttpProxyEngine.kt`  
**Issue:** Real HTTP forward proxy on local port (handles CONNECT tunnels for HTTPS, logs plaintext HTTP). Never started. `CaptureMethod.HTTP_PROXY` returned by `CapabilityState.resolveAutoCaptureMethod()` only when... nothing — the logic never produces HTTP_PROXY because no tier exposes it.  
**Severity:** MAJOR  
**Why It Matters:** Some users may want explicit proxy mode (e.g., configure app proxy to 127.0.0.1:8080) — not available.  
**Fix Sketch:** Add `CaptureMethod.HTTP_PROXY` as a tier option; wire to engine.

---

### 1.4 `MitmProxyEngine` — The Missing TLS Decryption Path (Most Critical Gap)
**Location:** `interception/MitmProxyEngine.kt`  
**Issue:** **This is the only engine that can decrypt HTTPS.** It:
- Generates per-host leaf certs signed by `CertificateAuthority` (BouncyCastle)
- Terminates client TLS with per-host cert
- Re-establishes upstream TLS to real server (trust-all)
- Relays plaintext bidirectionally

**But:** It is **never instantiated**. `CaptureMethod.MITM_PROXY` is never selected. `CertificateAuthority` generates a valid CA and can install to user store, but the proxy that would *use* it for MITM does not run.  
**Severity:** CRITICAL — **This is the single biggest lie in the app.** The CA install flow works; the wordmark dot turns green; the capture list shows rows — but **every HTTPS transaction has empty request/response bodies and no headers** (only SNI hostname logged).  
**Why It Matters:** The app's core value proposition ("intercept HTTPS") is a stub.  
**Fix Sketch:** 
1. `PreflightController` must return `MITM_PROXY` when CA is installed + tier preference allows MITM.
2. `CaptureController` must start `MitmProxyEngine` instead of `RequestrVpnService` when method = `MITM_PROXY`.
3. `PacketPump`/`CaptureEngine` currently only handles VPN_TUN raw packets. MITM proxy needs its own socket accept loop (already in `MitmProxyEngine.start()`). These are two **separate capture pipelines** — don't try to merge them.

---

### 1.5 `FridaCaptureEngine` — Reference Scripts Only, No Control Channel
**Location:** `interception/FridaCaptureEngine.kt`  
**Issue:** `isAvailable()` checks for `frida-server` via `su -c which frida-server` (wrong — frida-server runs as root but is not necessarily in PATH; should probe TCP 27042 like `CapabilityProbe.probeFrida()`). `start()` writes script to file, runs `frida -p <pid> -l <script> --no-pause` via `su`. **No in-app Frida management UI exists** — the `TrafficInterceptorRepository.fridaScripts` holds 3 reference scripts meant for *external* Frida CLI use. The "Frida" capability chip shows "Running" if port 27042 open, but nothing injects scripts or streams output into the app.  
**Severity:** CRITICAL  
**Why It Matters:** Frida is advertised as a capture tier; chip says "Available" but zero integration.  
**Fix Sketch:** Either (a) build full Frida RPC client (complex) or (b) remove Frida chip and `FridaCaptureEngine` until real integration exists.

---

## 2. Capture Pipeline — VPN_TUN Path Issues

### 2.1 `RequestrVpnService` Hardcodes VPN_TUN Only
**Location:** `interception/RequestrVpnService.kt`  
**Issue:** `onStartCommand()` always establishes tun, runs `PacketPump`, updates `InterceptionStateRepository` with `tier = VPN`, `method = VPN_TUN`. **No branch for other tiers.** Even if `CaptureController` dispatched to `RootIptablesEngine`, the VPN service would still start and fight for the tun fd.  
**Severity:** CRITICAL  
**Fix Sketch:** `RequestrVpnService` should only run for `VPN_TUN`. Other engines (`RootIptablesEngine`, `ShizukuTunEngine`, `MitmProxyEngine`, `HttpProxyEngine`) are **standalone** — they don't use VpnService.

---

### 2.2 `PacketPump` + `CaptureEngine` Only Handle Plaintext HTTP
**Location:** `interception/PacketPump.kt`, `interception/CaptureEngine.kt`  
**Issue:** 
- `CaptureEngine.ingest()` detects TLS via `TlsDecoder.looksLikeTls()` → extracts SNI → **clears buffers and returns**. No decryption.
- Plaintext HTTP/1.x parsed via `HttpParser` → request/response paired → persisted.
- **No HTTP/2 frame parsing** (ALPN negotiation happens in TLS; without MITM, we only see opaque TLS records).
- **No WebSocket frame capture**.
- **No QUIC/HTTP/3 visibility** (UDP 443 — tun sees packets but no parser).
**Severity:** MAJOR (expected limitation without MITM, but should be documented)  
**Why It Matters:** Modern traffic (h2, h3, WebSocket) is invisible. User sees only legacy HTTP/1.x.  
**Fix Sketch:** 
- HTTP/2: Need HPACK decoder + frame parser on reassembled TLS stream (requires MITM to get plaintext frames).
- WebSocket: Add frame parser on plaintext HTTP upgrade streams.
- QUIC: Parse long/short headers for SNI (visible in Initial packets) — doable without MITM.

---

### 2.3 `CaptureEngine` Reads Settings Snapshot, Not Live Flow
**Location:** `interception/CaptureEngine.kt:117`  
**Issue:** `persistTransaction()` reads `SettingsRepository.settings.value` (in-memory `MutableStateFlow` snapshot). If user toggles "Traffic Capture" / "Request Logging" / "Response Logging" off in Settings while capture is running, the engine **may continue writing** until process death because the snapshot hasn't refreshed.  
**Severity:** MAJOR  
**Why It Matters:** Settings toggles appear to work but don't take effect immediately.  
**Fix Sketch:** `CaptureEngine` should observe `SettingsDataStore` flows directly (or `SettingsRepository.settings` is already a StateFlow — just ensure the engine subscribes to it, not `.value`). Actually, `SettingsRepository.settings` IS a StateFlow — but `persistTransaction()` calls `.value` (snapshot). Change to `settings.first()` or collect in engine scope.

---

### 2.4 `RequestrVpnService` App Filter Not Hot-Reloadable
**Location:** `interception/RequestrVpnService.kt:91-99`  
**Issue:** Filter mode + selected packages read **once** at `onStartCommand()`. Changing filter in Settings while capture is running has **zero effect** until stop/start.  
**Severity:** MAJOR  
**Fix Sketch:** Observe `SettingsDataStore.selectedPackages` + `appFilterMode` in `RequestrVpnService` scope; call `builder.addAllowedApplication`/`removeAllowedApplication` dynamically (VpnService.Builder supports multiple calls before `establish()` but not after — need to re-establish tun or use `ParcelFileDescriptor` level filtering). Simpler: restart VPN service on filter change (stop + start).

---

### 2.5 `CaptureMethodWatcher` Health Checks Are No-Ops for Non-VPN Tiers
**Location:** `interception/CaptureMethodWatcher.kt:120-170`  
**Issue:** `checkMethodHealth()` switches on `CaptureMethod`:
- `VPN_TUN` → checks `/proc/net/dev` for tun
- `HTTP_PROXY` / `MITM_PROXY` → checks proxy port accepting connections
- `ROOT_IPTABLES` → checks iptables rules exist + proxy port
- `ROOT_FRIDA` → checks frida-server port 27042
- `SHIZUKU_TUN` → checks Shizuku binder alive

**But** since only `VPN_TUN` ever runs, all other branches are dead code. If Root/Shizuku engines were started, their health checks would run — but they'd check **proxy port** (which Root/Shizuku engines don't open — they use iptables REDIRECT to a port that... what listens on that port? The MITM proxy? But MITM proxy isn't started either).  
**Severity:** MAJOR (architectural inconsistency)  
**Fix Sketch:** Each engine must expose its actual health surface:
- Root/Shizuku iptables → need a local proxy listener (MITM or HTTP) on the redirect port
- Frida → Frida server health is separate from script injection health

---

## 3. CA Certificate — Generated But Never Used for MITM

### 3.1 `CertificateAuthority` Works But Is Orphaned
**Location:** `interception/CertificateAuthority.kt`  
**Issue:** Generates valid X.509 CA (RSA 2048, SHA256withRSA, CA:true basicConstraints, keyUsage=keyCertSign|cRLSign). Persists to PKCS#12 in app-private storage. `buildInstallIntent()` launches KeyChain for user-store install. `exportPem()` writes PEM for manual system-store install.  
**But:** The only consumer is `CaWhitelistService.promoteToSystem()` (copies to `/system/etc/security/cacerts/`) and `RootModuleGenerator` (bundles CA in Magisk/KSU module). **No engine consumes the CA for MITM cert issuance** except `MitmProxyEngine.generateHostCert()` — which is never called.  
**Severity:** CRITICAL (same as 1.4)

---

### 3.2 `CaWhitelistService.promoteToSystem()` Not Auto-Triggered
**Location:** `interception/CaWhitelistService.kt`  
**Issue:** Wired to a Settings screen button ("Promote to System Store"). **Never auto-runs when root/Shizuku is granted.** User must manually click.  
**Severity:** MINOR (UX)  
**Fix Sketch:** Observe `CapabilityState.root` / `shizuku` → when transitions to `GRANTED` and CA is in user store, auto-promote (with user consent toast).

---

### 3.3 `RootModuleGenerator` Produces Module But No Flash UI
**Location:** `interception/RootModuleGenerator.kt`  
**Issue:** Generates correct Magisk/KSU/KSU Next/nAPatch module ZIP with `post-fs-data.sh` (CA install) + `service.sh` (iptables rules). Saved to exports folder. **No UI to "Flash Module" or copy to Magisk Manager.** User must manually navigate to file and flash.  
**Severity:** MINOR (UX)  
**Fix Sketch:** Add "Open in Magisk" intent or share sheet targeting Magisk Manager.

---

## 4. Settings / DataStore — Race Conditions & Stale Reads

### 4.1 `ApiCollectionsRepository` Optimistic UI + Async DB Write Race
**Location:** `data/repository/AppRepositories.kt:373-392` (`saveRequest`), `405-418` (`toggleFavorite`)  
**Issue:** `_requests` (in-memory StateFlow) updated **optimistically** → UI reflects instantly. DB write (`savedApiRequestDao.upsert`) launched on `repoScope` (IO dispatcher) **asynchronously**. If process dies (swipe away from recents, crash, force-stop) before DB commit, the save is lost. Star/favorite same path.  
**Severity:** MAJOR  
**Why It Matters:** User sees "Saved" Snackbar, reopens app → request gone.  
**Fix Sketch:** Either (a) await DB write before updating StateFlow (suspend `saveRequest`), or (b) use `Room`'s `Flow` as single source of truth (already done for `observeAll()` — but `_requests` is separate). Simplest: make `saveRequest`/`toggleFavorite` suspend and `co_await` the DAO call before `_requests.value = ...`.

---

### 4.2 `SavedApisScreen` "Liked" Tab Filters Stale In-Memory Flow
**Location:** Not directly visible but inferred from `ApiCollectionsRepository._requests`  
**Issue:** "Liked" tab filters `_requests.value.filter { it.isFavorite }`. Since `_requests` is updated optimistically then refreshed from Room Flow, there's a window where DB has `isFavorite=true` but `_requests` hasn't re-emitted → tab shows empty.  
**Severity:** MINOR  
**Fix Sketch:** "Liked" tab should use `SavedApiRequestDao.observeAll().map { it.filter { it.isFavorite } }` directly, not `_requests`.

---

### 4.3 `SettingsRepository` Holds Runtime Flags That Never Update
**Location:** `data/repository/AppRepositories.kt:568-593`  
**Issue:** `UserSettingsState` includes `isVpnActive`, `isRootDetected`, `isShizukuAvailable`, `isFridaReady`, `isLsposedInstalled` — all default `false`, **never updated** by `CapabilityProbe` (which writes to `InterceptionStateRepository.capabilities`). Settings screen reads these for "Diagnostics" but they're stale.  
**Severity:** MINOR (UI confusion)  
**Fix Sketch:** Remove these from `UserSettingsState`; Settings screen should read `InterceptionStateRepository.capabilities` directly (already done for capability chips).

---

## 5. Flow / Automation — Execution Engine Is a Stub

### 5.1 `FlowExecutor` Only Executes `API` Node Type
**Location:** `flow/FlowExecutor.kt:85-93`  
**Issue:** `executeNode()` switches on `FlowNodeType`:
- `API` → real HTTP call via injected `httpExecutor`
- `INTERCEPT_TRIGGER` → returns `"trigger:..."` string literal
- `TRANSFORM` → runs `JsonFieldExtractor` on input (works)
- `CONDITION` → naive string match on "status == 200" or "contains <keyword>"
- `TEXT` → returns configData
- `TOOL` → returns `"tool:..."` string literal
- `OUTPUT` → returns first upstream input

**No data passing between nodes** except via `outputs` map keyed by node ID. But:
- `TRANSFORM` output is the extracted JSON field (string)
- `CONDITION` output is "true"/"false" string
- `TOOL` output is literal — **no tool execution**
- `INTERCEPT_TRIGGER` never connects to real capture events
**Severity:** CRITICAL — "Run Flow" produces fake success message without executing the graph.  
**Fix Sketch:** 
- Implement `INTERCEPT_TRIGGER` → subscribe to `CaptureLogRepository.observeTraffic` + filter by rule
- Implement `TOOL` → dispatch to `ToolsRepository` functions
- Implement `CONDITION` → branch BFS queue based on boolean result
- Add `CHAT` / `AI` node types per spec

---

### 5.2 `FlowRepository` In-Memory Only — No Persistence
**Location:** `data/repository/AppRepositories.kt:116-230`  
**Issue:** `_nodes` and `_connections` are `MutableStateFlow` with **hardcoded seed data** (4 nodes, 3 connections). No Room entities, no DAOs. Positions lost on restart.  
**Severity:** MAJOR  
**Fix Sketch:** Add `FlowNodeEntity`, `FlowConnectionEntity`, DAOs; back StateFlow from Room.

---

### 5.3 No Port-Drag Connection UI
**Location:** `ui/screens/FlowScreen.kt`  
**Issue:** Nodes have `inputPorts` / `outputPorts` arrays but **no visual ports, no drag-to-connect gesture**. Connections only added via `FlowRepository.addConnection()` (no UI).  
**Severity:** MAJOR  
**Fix Sketch:** Render port handles on node edges; `detectDragGestures` from port → port; create `FlowConnectionItem` on drop.

---

### 5.4 Missing Flow Features Per Spec
| Feature | Status |
|---------|--------|
| Pan/zoom canvas | STUB — Canvas draws grid but no gesture handling |
| Fit to view / center | STUB — Not implemented |
| Mini-map | STUB — Not implemented |
| Long-press context menu (Edit/Duplicate/Delete/Disconnect) | STUB — Node has delete icon only |
| Export (n8n-compatible JSON) | STUB — No implementation |
| Import (round-trip) | STUB — No implementation |
| Deeplink-triggered execution (`requestr://flow/execute`) | STUB — Manifest declares scheme but no handler |

---

## 6. Chats Section — Entirely Missing

**Location:** N/A — no `ChatScreen.kt`, `ProvidersRepository`, `ChatRepository`, or chat-related data layer in source tree.  
**Issue:** Navigation graph declares `Screen.Chats` but no composable exists. All chat features (provider add/edit, send message, streaming, assistant config, logs, statistics) are **stubs** — UI may navigate to a blank screen or crash.  
**Severity:** CRITICAL (shipping missing feature)  
**Fix Sketch:** Either implement or remove from navigation + drawer.

---

## 7. Tools — Workspace Implemented But Custom Tools Missing

### 7.1 `ToolWorkspaceScreen` — 18 Tools Have Domain-Specific UIs ✅
**Location:** `ui/screens/ToolWorkspaceScreen.kt`  
**Status:** **Actually implemented!** Each of the 18 tools in `ToolsRepository.getToolsCatalog()` has a dedicated `@Composable` workspace (Base64, URL Encoder, Hex, HTML Entity, Morse, XOR, SHA-256/1/MD5, HMAC, CRC32, AES, JSON/SQL Formatter, IP Calculator, JWT Decoder, UUID/Random/Lorem Generators). They use `ToolsRepository` functions correctly.  
**Note:** This contradicts the audit report's "STUB" verdict — the workspaces **are real**.

### 7.2 Custom Tools / Adaptor Builder — Missing
**Location:** `ToolsRepository` has `ToolCategory.CUSTOM` but no builder UI. `ToolWorkspaceScreen` doesn't handle custom tools (falls to `PlaceholderWorkspace`).  
**Severity:** MINOR  
**Fix Sketch:** Add "Create Custom Tool" flow: name, description, input/output schema, inline JS/Kotlin script (using `javax.script` or WASM), persist to Room.

---

## 8. Onboarding / Permissions — Real Bugs

### 8.1 Theme Collision (REG-05)
**Location:** `ui/screens/OnboardingScreen.kt` (AppearanceStep), `data/repository/AppRepositories.kt:622-633` (`updateThemeSelection`)  
**Issue:** Wizard calls `SettingsRepository.updateAppearanceMode(mode)` then `updateThemeSelection(theme)`. But `updateThemeSelection` **never touches `appearanceMode`** (by design — comment says "picking a root theme must never silently flip SYSTEM/LIGHT/DARK"). So wizard writes theme to `themeMode` + `selectedLightTheme`/`selectedDarkTheme`, but `appearanceMode` stays `SYSTEM`. On cold boot, `MainActivity.effectiveThemeKey` resolves `SYSTEM` → follows OS theme → user's explicit pick ignored.  
**Severity:** MAJOR  
**Fix Sketch:** Wizard must either (a) set `appearanceMode = LIGHT` or `DARK` when user picks non-SYSTEM theme, or (b) write `themeMode` + keep `appearanceMode=SYSTEM` but document that theme only applies when appearanceMode matches. Option (a) is correct UX.

---

### 8.2 Music Permission Card Duplicates Storage Permission
**Location:** `ui/screens/OnboardingScreen.kt`  
**Issue:** Both Storage and Music cards request `READ_MEDIA_AUDIO`. Granting once satisfies both, but UI shows independent state. Music card claims separate grant.  
**Severity:** MAJOR (UX confusion)  
**Fix Sketch:** Merge into single "Media & Files" card, or make Music card check `SettingsRepository.settings.musicPermissionStatus` and skip if already granted.

---

### 8.3 Onboarding `OnboardingScreen.kt` Line 555 Bug
**Location:** `ui/screens/OnboardingScreen.kt:555`  
**Issue:** `SettingsRepository.settings.value.selectedDarkTheme` — reads snapshot, not flow. If user hasn't picked a dark theme yet, this is default "MONOCHROME_DARK". But `updateAppearanceMode("DARK")` was called earlier. The theme picker uses `selectedDarkTheme` as the default selection — but it's the default, not the user's pick. Circular.  
**Severity:** MINOR

---

## 9. API Testr — Right-Menu Stubs

### 9.1 Right-Menu Items Non-Functional
**Location:** `ui/screens/ApiTestrScreen.kt` (right-menu handler)  
**Issue:** Items: **Reset/Clear/Rename/Import cURL/Code Snippet** work. **Export cURL** copies to clipboard only (no file). **Cookie Jar / Environment / Make Tool / Add to Chat / Pin** — UI only, no handler.  
**Severity:** MAJOR  
**Fix Sketch:** Implement or remove.

---

### 9.2 Export cURL as File
**Location:** `data/interception/TrafficExporter.toCurl()` exists but only used for clipboard.  
**Fix Sketch:** Use `FileExporter` to write `.sh` file + share intent.

---

## 10. Intercept Rules — Persisted But Not Applied

### 10.1 Rules Engine Not Wired to Capture Pipeline
**Location:** `data/local/Entities.kt:70-82` (`InterceptRuleEntity`), `data/local/Daos.kt:81-112` (`InterceptRuleDao`), `interception/CaptureEngine.kt`  
**Issue:** Rules saved to Room. `InterceptRuleDao.getEnabledRules()` exists. **But `CaptureEngine.ingest()` never calls it.** No rule matching, no action (Log/Block/Modify/Mock).  
**Severity:** MAJOR  
**Fix Sketch:** In `CaptureEngine.ingest()`, after HTTP parse but before persist: `val rules = interceptRuleDao.getEnabledRules(); rules.forEach { if (matches(request, it)) applyAction(it, request, response) }`.

---

### 10.2 Rule Actions Are Enum Only — No Execution Logic
**Location:** `data/models/AppModels.kt:266-273` (`RuleAction`), `data/local/Entities.kt:78` (`action: RuleAction`)  
**Issue:** `BLOCK`, `MODIFY_REQUEST`, `MODIFY_RESPONSE`, `BREAKPOINT`, `MOCK_RESPONSE` are enum values with **zero execution code**.  
**Severity:** MAJOR  
**Fix Sketch:** Implement each action in `CaptureEngine` or a separate `RuleEngine`.

---

## 11. Notes — Export & Rendering Gaps

### 11.1 HTML Export Missing
**Location:** `ui/screens/NotesScreen.kt` (editor `⋮` menu)  
**Issue:** Menu items for "Export MD" / "Export HTML" exist but **no implementation**.  
**Severity:** MINOR  
**Fix Sketch:** Use `FileExporter` + markdown-to-HTML (or write raw MD/HTML).

---

### 11.2 HTML Preview Shows Raw Tags
**Location:** `ui/screens/NotesScreen.kt:190-201`  
**Issue:** `isPreviewMode` renders `Text(editContent, fontFamily = FontFamily.Monospace)` — no HTML parsing.  
**Severity:** MINOR  
**Fix Sketch:** Use `HtmlCompat.fromHtml()` + `AnnotatedString` or `WebView`.

---

## 12. Music — Queue Management Stub

### 12.1 No Drag-Reorder / Swipe-Remove
**Location:** `ui/screens/MusicScreen.kt:274-305`  
**Issue:** Playlist renders `tracks` list but **no `ItemTouchHelper` or Compose drag/swipe handlers**.  
**Severity:** MINOR  
**Fix Sketch:** Add `Modifier.dragAndDrop` or `SwipeToDelete` from accompanist.

---

### 12.2 YouTube / JioSaavn Sources Non-Functional
**Location:** `data/models/AppModels.kt:359-364` (`MusicSourceType`)  
**Issue:** Enum has `SYNTH_LOFI`, `AMBIENT_CYBER`, `CHILL_CODING`, `LOCAL`. Only `LOCAL` implemented (`LocalMusicScanner`). Others show in UI but do nothing.  
**Severity:** MINOR  
**Fix Sketch:** Remove from enum or implement.

---

## 13. Settings — Removed Toggles Correctly Cleaned Up

### 13.1 Removed: PiP, Typography/Surfaces/Borders/Icons/States, Tablet Mode, Advanced Features
**Location:** `ui/screens/SettingsScreen.kt`  
**Status:** ✅ Correctly removed — no rendering hooks existed.  
**Note:** The audit report "REMOVED" verdicts are correct.

---

## 14. Environment / Deep Links — Declared But Unhandled

### 14.1 `requestr://` Scheme in Manifest, No Router
**Location:** `AndroidManifest.xml:53-58`  
**Issue:** `<data android:scheme="requestr" />` declared. **No `NavDeepLinkController` or composable handling `requestr://` routes.** Deeplinks from external apps (share, NFC, QR) will launch `MainActivity` but not navigate.  
**Severity:** MAJOR  
**Fix Sketch:** Add `NavHost` deep link routes or `Activity` intent filter handling.

---

### 14.2 `requestr://flow/execute` Deeplink Declared, No Handler
**Location:** `AndroidManifest.xml` (implied by spec), `FlowScreen`  
**Issue:** Spec mentions deeplink-triggered flow execution. No code handles it.  
**Severity:** MINOR  

---

## 15. Interception State — Watcher Status Not Propagated to Foreground Service

### 15.1 `CaptureForegroundService` Reads Watcher Status But Never Started
**Location:** `interception/CaptureForegroundService.kt`, `interception/RequestrVpnService.kt`  
**Issue:** `CaptureForegroundService` declared in manifest (`foregroundServiceType="dataSync"`) but **never started** by `RequestrVpnService`. The VPN service has its own `startForegroundNotification()` (line 200-237). Two competing foreground notifications.  
**Severity:** MINOR (duplicate notification)  
**Fix Sketch:** Remove `CaptureForegroundService` or have `RequestrVpnService` start it instead of own notification.

---

## 16. DataStore Migration — Edge Cases

### 16.1 `LegacyDataMigration.migrateInterceptionDataStore()` Reads Protobuf Directly
**Location:** `data/prefs/UnifiedDataStore.kt:191-210`  
**Issue:** Uses `PreferenceDataStoreFactory.create(produceFile = { legacyFile })` to read old DataStore file. **Fragile** — depends on internal DataStore file format (`preferences_pb`). If DataStore version changes, migration breaks.  
**Severity:** MINOR (one-time migration)  
**Fix Sketch:** Accept risk — migration runs once per install.

---

## 17. Network Security Config — Cleartext Allowed

### 17.1 `android:usesCleartextTraffic="true"` in Manifest
**Location:** `AndroidManifest.xml:38`  
**Issue:** Allows HTTP (non-TLS) for all domains. Needed for local proxy/mitm but **weakens security** for app's own network calls (e.g., API Testr requests to HTTP endpoints).  
**Severity:** MINOR (acceptable for dev tool)  
**Fix Sketch:** Add `network_security_config.xml` with domain-specific cleartext for `127.0.0.1`, `10.0.2.2`, localhost only.

---

## 18. ProGuard / R8 — Minify Disabled

### 18.1 `isMinifyEnabled = false` in Release Build
**Location:** `app/build.gradle.kts:44`  
**Issue:** Release APK not minified/obfuscated. Increases size, exposes symbols.  
**Severity:** MINOR  
**Fix Sketch:** Enable minify + add ProGuard rules for reflection-heavy code (Room, DataStore, BouncyCastle, Frida reflection).

---

## 19. Dependencies — Unused / Commented Out

### 19.1 Commented Dependencies in `app/build.gradle.kts`
```kotlin
// implementation(libs.accompanist.permissions)
// implementation(libs.androidx.camera.camera2)
// implementation(libs.androidx.camera.core)
// implementation(libs.androidx.camera.lifecycle)
// implementation(libs.androidx.camera.view)
// implementation(libs.coil.compose)
// implementation(libs.play.services.location)
```
**Issue:** Dead code in build file.  
**Severity:** POLISH  
**Fix Sketch:** Remove or uncomment if needed.

---

## 20. Testing — No Instrumented Tests for Capture Pipeline

### 20.1 Zero Tests for PacketPump, CaptureEngine, TcpReassembler, HttpParser, TlsDecoder
**Location:** No test files found in `app/src/androidTest` or `app/src/test` for interception package.  
**Issue:** Core capture logic (parsing, reassembly, TLS detection) has **zero automated tests**.  
**Severity:** MAJOR (regression risk)  
**Fix Sketch:** Add unit tests with synthetic packet captures (pcap fixtures).

---

## Summary: Top 10 Fixes That Would Unblock Real Use

| # | Fix | Enables |
|---|-----|---------|
| 1 | Dispatch `CaptureMethod` to real engines (`RootIptablesEngine`, `ShizukuTunEngine`, `MitmProxyEngine`, `HttpProxyEngine`) | Tier selection becomes real |
| 2 | Start `MitmProxyEngine` when CA installed + user chooses MITM | **HTTPS decryption** (core value prop) |
| 3 | Wire `CaptureEngine` to `InterceptRuleDao` + implement rule actions | Rules actually work |
| 4 | Make `CaptureEngine` observe Settings flows live | Toggles take effect immediately |
| 5 | Hot-reload app filter in `RequestrVpnService` | Filter changes without restart |
| 6 | Persist Flow nodes/connections to Room | Survive restart, enable export/import |
| 7 | Implement `INTERCEPT_TRIGGER` + `TOOL` + `CONDITION` in `FlowExecutor` | Flow automation works |
| 8 | Await DB write in `ApiCollectionsRepository.saveRequest`/`toggleFavorite` | Saves survive process death |
| 9 | Fix Onboarding theme collision (set `appearanceMode` on pick) | Theme persists across boots |
| 10 | Remove Frida chip/engine or build real Frida RPC client | Stop lying about Frida support |

---

## Environment-Limited Verification Checklist (Requires Physical Device)

The following **cannot be confirmed** without hardware. Code *looks* correct but runtime behavior is unknown:

| Area | What to Verify on Device |
|------|--------------------------|
| `RootIptablesEngine.isAvailable()` | `su -c which iptables` returns path; `su -c id` returns uid=0 |
| `RootIptablesEngine.start()` | `iptables -t nat -L OUTPUT -n` shows REDIRECT rules; traffic captured without VPN dialog |
| `ShizukuTunEngine.start()` | Shizuku binder alive; iptables rules applied via Shizuku shell; tun packets received |
| `RequestrVpnService` tun establishment | `builder.establish()` returns non-null `ParcelFileDescriptor`; `PacketPump` reads packets |
| `WAKE_LOCK` under Doze | Capture survives screen-off + 30min; foreground service not killed |
| `CertificateAuthority.buildInstallIntent()` | KeyChain dialog appears; CA appears in Settings → Security → User credentials |
| `CaWhitelistService.promoteToSystem()` (root) | CA file appears in `/system/etc/security/cacerts/<hash>.0`; `chcon` correct |
| `CaWhitelistService.promoteToSystem()` (Shizuku) | Same via Shizuku shell; no root required |
| `RootModuleGenerator` module flash | Magisk/KSU Manager installs module; reboot → CA in system store; iptables rules active |
| `MitmProxyEngine` TLS termination | HTTPS site visited → request/response bodies visible in capture list; `wasDecrypted=true` |
| `FridaCaptureEngine` script injection | `frida -p <pid> -l <script>` hooks target; output appears in logcat/app UI |
| `LocalMusicScanner` | MediaStore query returns tracks on API 28-36; playback works via `MediaPlayer`/`ExoPlayer` |
| `POST_NOTIFICATIONS` (Android 13+) | Dialog appears on first capture start; grant persists |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | Scoped storage behavior correct across API levels |

---

*End of Deep Analysis — Untouched / Environment-Limited Areas*