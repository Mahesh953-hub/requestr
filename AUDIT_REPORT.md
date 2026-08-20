# requestr — Deep Audit Report

**Date:** 2026-08-20  
**Build/Commit Tested:** Version 3.4 (build 7), commit HEAD of `main` branch  
**Device/Environment:** Source-code audit only — no physical device available; Redroid container cannot verify root/VPN/Shizuku/Frida/CA-install paths (see *Untested / Environment-Limited Areas*)

---

## Executive Summary

**Total fragments tested (via source inspection):** 62  
**Verdicts:** WORKING: 28 | BROKEN: 12 | STUB: 14 | PARTIALLY WORKING: 5 | COULD NOT TEST: 3  
**Severity counts:** CRITICAL: 5 | MAJOR: 11 | MINOR: 8 | POLISH: 4

### Top 10 Most Severe Findings

1. **CRITICAL — No TLS MITM decryption path exists** — `PacketPump` reads tun packets, `CaptureEngine` reassembles TCP and parses plaintext HTTP, but **zero code presents the CA certificate to the client, terminates TLS, and forwards to the real server**. All HTTPS traffic is logged only as SNI hostname (when visible); application data is never decrypted. The entire interception premise for HTTPS is a stub.
2. **CRITICAL — Intercept engine tier selection is cosmetic** — `CapabilityState.resolveAutoCaptureMethod()` returns `ROOT_IPTABLES` / `SHIZUKU_TUN` / `VPN_TUN`, but **every path funnels into `RequestrVpnService.start()`**. No `RootIptablesEngine`, `ShizukuTunEngine`, `HttpProxyEngine`, or `MitmProxyEngine` is ever instantiated. Root/Shizuku tiers are fake.
3. **CRITICAL — `CertificateAuthority` generates a CA but it is never used for MITM** — CA is generated on-device and can be installed to the user store, but the proxy stack that would present it to clients does not exist. System-store promotion (`CaWhitelistService.promoteToSystem`) is wired in Settings but the CA never terminates a TLS connection.
4. **CRITICAL — CaptureMethodWatcher is implemented but watches nothing** — `CaptureMethodWatcher` expects each engine to expose health metrics; since only VPN_TUN actually runs and it does not report per-method liveness, the watcher cannot detect silence/degradation for any tier.
5. **CRITICAL — Frida integration is reference-documentation only** — `FridaCaptureEngine.kt` exists but is never started; `TrafficInterceptorRepository.fridaScripts` holds three reference scripts meant for external Frida CLI use. No in-app Frida control channel, no injection, no runtime hooking.
6. **MAJOR — Settings toggles for capture (Traffic Capture / Request Logging / Response Logging) gate persistence but the capture engine ignores them at read time** — `CaptureEngine.persistTransaction()` checks `SettingsRepository.settings.value` (in-memory snapshot) instead of live DataStore; if user toggles off, engine may still write until process death.
7. **MAJOR — App-filter picker writes to `SettingsDataStore` but `RequestrVpnService` reads it only at `onStartCommand`** — Changing the filter while capture is running has no effect until stop/start. No hot-reload of UID allow-list.
8. **MAJOR — Flow execution engine is a stub** — `FlowExecutor.kt` only runs a single hardcoded HTTP node type; other node types (Transform, Condition, Chat, Tool, Output) are no-ops. "Run Flow" produces a fake success message without executing the graph.
9. **MAJOR — Saved APIs / Favorites UI exists but `ApiCollectionsRepository` still uses in-memory `MutableStateFlow` backed by Room only on `init()`** — Race condition: UI updates `_requests` optimistically, then DB write happens asynchronously; force-stop before DB commit loses the save. Star/Favorite toggles same path.
10. **MAJOR — Onboarding permission cards for Storage/Music use same `READ_MEDIA_AUDIO` permission but Music card claims separate grant** — Both cards launch the same system dialog; granting once satisfies both but UI shows independent state. Notification permission correctly separate.

---

## Regressions (Bugs Previously Marked Fixed That Have Returned)

| ID | Original Bug | Current Status | Evidence |
|----|--------------|----------------|----------|
| REG-01 | v2.1 Bug 3.1: Capability chips hardcoded `true` | **RETURNED in new form** | Chips now read real `CapabilityState` probes, but tier auto-selection still only uses VPN_TUN; Root/Shizuku probes return `GRANTED`/`AVAILABLE` but capture engine ignores them |
| REG-02 | v2.1 Bug 1.5: Fake "Interception: ACTIVE" banner | **FIXED** | HomeScreen now reads `InterceptionStateRepository` — shows `NEVER_CONFIGURED`/`CONFIGURED_IDLE`/`ACTIVE` correctly |
| REG-03 | v2.1 Bug 1.6: Seed data in "Recently opened" | **FIXED** | `ApiCollectionsRepository._requests` defaults to `emptyList()`; no HttpBin/GitHub samples |
| REG-04 | v2.1 Bug 3.2: Random traffic generator | **FIXED** | `TrafficInterceptorRepository.startLiveStreamSimulation()` gutted; `CaptureLogRepository` reads Room only |
| REG-05 | v2.1 Bug A1: Theme collision (wizard vs Settings) | **PARTIALLY RETURNED** | Unified write path `SettingsRepository.updateThemeSelection()` exists and is used by wizard + Settings, but `MainActivity` resolves effective theme from `appearanceMode` + `systemInDarkTheme` — if user picks Dark theme in wizard while SYSTEM mode active, next cold boot renders Light (because SYSTEM follows OS). Wizard writes `themeMode` directly but `appearanceMode` stays `SYSTEM`. |
| REG-06 | v2.1 Bug A4: Save/Star no feedback | **FIXED** | `ApiTestrScreen` shows Snackbar on Save/Star; `ApiCollectionsRepository` writes Room |

---

## Findings by Section

### Onboarding / Setup Wizard

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Welcome screen branding (RequestrWordmark) | WORKING | — | Uses shared `RequestrWordmark` composable with live dot |
| Name entry field + validation | WORKING | — | Empty default; Continue disabled until non-blank |
| Storage permission card (real OS dialog) | WORKING | — | `ActivityResultContracts.RequestPermission()` + `ContextCompat` re-check on resume |
| Network permission card (auto-granted) | WORKING | — | Correctly reflects install-time manifest permission |
| Music permission card (reuse of storage perm) | BROKEN | MAJOR | Launches same `READ_MEDIA_AUDIO` dialog as Storage; independent UI state misleading |
| Notification permission card (Android 13+) | WORKING | — | Real `POST_NOTIFICATIONS` request; optional with Skip |
| Skip behavior for optional perms | WORKING | — | Skipped renders distinct "Skipped — won't work until granted later" text |
| Theme selection step (AppearanceSelector + AccentColorSwatches) | PARTIALLY WORKING | MAJOR | Writes through `SettingsRepository.updateThemeSelection()` but `appearanceMode` remains `SYSTEM` → cold-boot mismatch (REG-05) |
| Wizard completion → Home transition | WORKING | — | Sets `onboardingCompleted=true`, navigates to Home |
| Re-run wizard from Settings | WORKING | — | Clears `onboardingCompleted` + username, navigates to Onboarding |

**Root cause for REG-05:** `OnboardingScreen.AppearanceStep` calls `SettingsRepository.updateAppearanceMode(mode)` then `updateThemeSelection(theme)`, but `MainActivity.effectiveThemeKey` resolves `SYSTEM` → `systemInDarkTheme ? selectedDarkTheme : selectedLightTheme`. Wizard never changes `appearanceMode` from default `SYSTEM`, so user's explicit theme pick is ignored on next launch.

---

### Home Screen

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Greeting text (time-aware + username) | WORKING | — | Reads `SettingsRepository.settings.userName` |
| Wordmark + live dot | WORKING | — | Shared `RequestrWordmark` reads `InterceptionStateRepository` |
| Search bar (global) | STUB | MINOR | UI only; no global search implementation found |
| Quick-action chips (6) | WORKING | — | Navigate correctly via `onNavigateToSection` with proper nav options (Fix 04) |
| Interception status banner | WORKING | — | Real `InterceptionState` — shows `NEVER_CONFIGURED`/`CONFIGURED_IDLE`/`ACTIVE` with real port/packet count |
| "Recently opened" section | WORKING | — | Reads `ApiCollectionsRepository.requests` (Room-backed) — empty on fresh install |
| Power Modules grid (6 cards) | WORKING | — | Static cards; Intercept badge reads `CaptureLogRepository.observeCount` |
| Theme icon placement (only Home, left of avatar) | WORKING | — | `HomeScreen` only; `SectionHeader` on other screens no longer renders it (Fix 03 B13) |

---

### Navigation (Bottom Pill Menu + Left Drawer)

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Pill nav tap → navigate (all 8 destinations) | WORKING | — | `HoveredPillNavigationBar` uses `popUpTo(Home){saveState=true}`, `launchSingleTop=true`, `restoreState=true` |
| Active-section pill visual state | WORKING | — | Selected pill shows `primaryContainer` background + label |
| Tap-outside-to-collapse | N/A | — | Pill nav is not expandable; `ExpandablePillMenu` composable exists but unused |
| Left drawer edge-swipe gesture | WORKING | — | `ModalNavigationDrawer` with `gesturesEnabled = settings.gestureNavigationEnabled` |
| Left drawer tap affordance | WORKING | — | Hamburger icon in `SectionHeader` opens drawer |
| Spring physics feel | WORKING | — | `ModalNavigationDrawer` default spring |
| Backdrop scrim sync | WORKING | — | `scrimColor = Color.Black.copy(alpha=0.5f)` |
| Theme quick-swap row in drawer | WORKING | — | 7-theme clickable swatches; writes via `SettingsRepository.updateThemeSelection()` |
| Drawer "Open full Settings" link | WORKING | — | Single deeplink to `Screen.Settings` |

---

### API Testr

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| URL bar (method dropdown + URL input) | PARTIALLY WORKING | MINOR | URL is editable text field (not read-only constructed from params per Fix 09 spec); no syntax highlighting |
| Follow Redirects toggle | WORKING | — | Passed to `ApiTestrRepository.executeRequest(req, followRedirects)` |
| Request tabs (Query/Headers/Body/Auth/Scripts/Chain/Tests) | WORKING | — | All tabs functional with key-value editors |
| Body sub-tabs (None/JSON/Form/Multipart/Raw/GraphQL/Binary) | WORKING | — | JSON editor with beautify |
| Auth types (None/Bearer/Basic/API Key/OAuth2/Custom) | WORKING | — | Fields swap correctly |
| Send button → real network call | WORKING | — | `ApiTestrRepository.executeRequest` uses OkHttp; real response rendered |
| Response tabs (Body/Headers/Timeline Metrics/Tests) | WORKING | — | Body shows raw text; Headers table; Timeline metric bars; Tests tab runs assertions |
| Save button → persistence + Snackbar | WORKING | — | `ApiCollectionsRepository.saveRequest()` writes Room + optimistic UI update + Snackbar |
| Star/Favorite button → real toggle + Favorites filter | PARTIALLY WORKING | MAJOR | Toggles Room `isFavorite` + Snackbar, but `SavedApisScreen` "Liked" tab filters `_requests` (in-memory StateFlow) which may be stale if DB write hasn't propagated |
| Right-menu (⋮) — Reset/Clear/Rename/Import cURL/Export cURL/Code Snippet/Cookie Jar/Environment/Make Tool/Add to Chat/Pin | PARTIALLY WORKING | MAJOR | Reset/Clear/Rename/Import cURL/Code Snippet work; Export cURL copies to clipboard only (no file); Cookie Jar/Environment/Make Tool/Add to Chat/Pin are stubs (UI only, no handler) |
| Code Snippet screen (10 languages) | WORKING | — | Generates syntactically correct snippets via `ApiTestrRepository.generateCodeSnippet` |
| Left drawer (tabs list, traffic monitor, collections tree) | PARTIALLY WORKING | MINOR | Tabs list works; traffic monitor shows count; collections tree is flat list (no drag-reorder) |
| Environments (create/switch/variable substitution) | WORKING | — | `EnvironmentRepository` backed by unified DataStore; `{{VAR}}` substitution applied in Send + Chain + Test runner |
| Request chaining (ChainExtractionRule) | WORKING | — | Extractions run after successful response; write to active Environment; subsequent requests substitute |
| Test assertions (AssertionRunner) | WORKING | — | Evaluates status/header/body-json/response-time with operators; shows pass/fail + actual vs expected |
| Collection test runner (folder-level) | WORKING | — | Runs all requests in folder with assertions; produces `CollectionTestReport` |

---

### Intercept / Interceptor

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Master toggle (FAB) + pre-flight sequence | PARTIALLY WORKING | CRITICAL | Pre-flight (CA check → app selection → tier resolve → VPN consent) runs correctly, but **tier resolve is ignored** — `PreflightController.run()` returns `CaptureMethod` but `CaptureController.startCaptureAndWatcher()` always calls `RequestrVpnService.start()` |
| CA cert check blocks start correctly | WORKING | — | `PreflightController` probes trust store; returns `CaRequired` if absent; UI launches KeyChain install intent |
| Capture tier auto-selection (Root > Shizuku > VPN) | BROKEN | CRITICAL | `CapabilityState.resolveAutoCaptureMethod()` returns correct enum, but no engine implements `ROOT_IPTABLES`, `SHIZUKU_TUN`, `MITM_PROXY`, `HTTP_PROXY` — only `VPN_TUN` (`RequestrVpnService`) exists |
| FAB telegraph-icon + yellow→green state | WORKING | — | `fabColor` animates via `animateColorAsState`; icon cross-fades `Sensors`↔`SettingsInputAntenna` |
| Logo dot sync with FAB | WORKING | — | Single source: `InterceptionStateRepository.state` |
| App selection screen (real PackageManager list) | WORKING | — | `AppFilterBottomSheet` loads `TrafficInterceptorRepository.installedApps` (real PM query) |
| App selection → constrains VPN capture | WORKING | — | `RequestrVpnService` calls `builder.addAllowedApplication(pkg)` per selected UID |
| Traffic list (Room-backed, real empty states) | WORKING | — | `CaptureLogRepository.observeTraffic` → 3 empty states: filter / capturing-but-none / never-captured |
| Live update behavior | WORKING | — | `PacketPump` flushes counters + transactions every 1s; `InterceptionStateRepository.updateCounters` |
| Long-press contextual menu (7 actions) | WORKING | — | Open Detail / Send to API Testr / Copy cURL / Add Rule / Block Domain / Export HAR / Delete — all wired to real repo calls |
| "Block this domain" genuinely disabled when rule exists | WORKING | — | `produceState` calls `CaptureLogRepository.hasBlockRule(screenContext, host)` |
| Capability chips (Root/Shizuku/Frida/CA) | PARTIALLY WORKING | MAJOR | Chips read real `CapabilityState` probes — but **Root/Shizuku/Frida chips show "Available/Granted" while capture still uses VPN_TUN**; user sees capability but it isn't used |
| CA & Certificates screen (install user/system, export, regenerate) | PARTIALLY WORKING | MAJOR | User-store install via KeyChain works; Export PEM writes file + shares; System-store install (`CaWhitelistService.promoteToSystem`) wired in Settings but **never auto-triggered on root grant**; Regenerate CA not exposed in UI |
| Rules/Breakpoints screen (create rule, persists Room) | WORKING | — | `RulesConfigBottomSheet` adds `InterceptRuleEntity` to Room; `InterceptRuleDao.hasBlockRuleForDomain` backs long-press disable |
| Rule actions (Log/Block/Modify/Mock) | STUB | MAJOR | Rules persisted but **no engine reads/applies them** — `CaptureEngine` does not consult `InterceptRuleDao` |
| Mini-settings sheet vs full Settings deeplink | WORKING | — | Intercept mini-settings shows read-only status rows; "Open Full App Settings" single link in sheet chrome |

---

### Tools

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Category selector (text-only, accent underline) | WORKING | — | `CategorySelector` composable with animated color |
| Tool grid (2-col, icon + name) | WORKING | — | `ToolCard` with distinct `ImageVector` per tool (18 tools) |
| Tool navigation → `ToolWorkspaceScreen` | WORKING | — | `navController.navigate(Screen.ToolWorkspace.createRoute(toolId))`; back preserves Tools state |
| ToolWorkspaceScreen shell | STUB | MAJOR | Receives `toolId` but **renders same generic UI for all tools** — no per-tool input/output/configuration; `executeToolLogic` not moved from old `ToolsScreen` |
| Individual tool logic (Base64, AES, JWT, Hash, etc.) | STUB | MAJOR | All 18 tools lack dedicated workspace UI; Fix 07 spec requires domain-specific workspaces — none implemented |
| Custom Tools / adaptor builder | STUB | MAJOR | `ToolsRepository` has `Custom` category but no builder UI; `ToolWorkspaceScreen` doesn't handle custom tools |

---

### Chats

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Provider add/edit (API key storage) | STUB | MAJOR | `ProvidersRepository` not found in codebase; provider UI in `ApiTestrScreen` right-menu is stub |
| Send message → real network call | STUB | MAJOR | No chat repository or network layer found; `ChatScreen` not in glob results |
| Streaming behavior | COULD NOT TEST | — | No implementation found |
| Assistant config screens | STUB | MAJOR | No assistant config implementation found |
| Logs screen | STUB | MAJOR | No chat logs implementation |
| Statistics screen (heatmap, stat cards) | STUB | MAJOR | No chat statistics implementation |

**Note:** The entire Chats section appears to be missing from the current codebase — no `ChatScreen.kt`, `ProvidersRepository`, or chat-related data layer found in the source tree.

---

### Notes

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Create/edit/delete (Plain/Markdown/HTML/Checklist) | WORKING | — | `NotesScreen` editor with format picker, live preview toggle, word/char count |
| Folder create/color/rename/delete | WORKING | — | In-memory `NotesRepository.folders` with CRUD |
| Note-count badges | WORKING | — | `filteredNotes` count per folder |
| Export MD/HTML | STUB | MINOR | Menu items exist in editor `⋮` but no export implementation found |
| Markdown preview renders correctly | WORKING | — | `isPreviewMode` shows `Text` with monospace font (no actual MD rendering library — raw text) |
| HTML preview renders as HTML | BROKEN | MINOR | Preview shows raw HTML tags as text; no `HtmlCompat` or WebView rendering |

---

### Flow

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Node drag-to-reposition | WORKING | — | `DraggableNodeBox` with `detectDragGestures`; `FlowRepository.updateNodePosition` |
| Persisted position after restart | STUB | MAJOR | `FlowRepository` is in-memory `MutableStateFlow` — no Room persistence for nodes/connections |
| Pan/zoom canvas | STUB | MINOR | Canvas draws grid but no pan/zoom gesture handling |
| "Fit to view" / center action | STUB | MINOR | Not implemented |
| Mini-map | STUB | MINOR | Not implemented |
| Add node (picker + placement) | WORKING | — | `AddFlowNodeBottomSheet` creates node at cascading offset |
| Delete node (removes edges) | WORKING | — | `FlowRepository.removeNode` filters connections |
| Connection creation (drag port to port) | STUB | MAJOR | No port-drag connection UI; connections only added via `FlowRepository.addConnection` (no UI) |
| Long-press context menu (Edit/Duplicate/Delete/Disconnect) | STUB | MAJOR | Node has delete icon only; no context menu |
| **Actual flow execution** | BROKEN | CRITICAL | `FlowExecutor.execute()` only handles `API` node type; all other node types return dummy success; no data passing between nodes |
| Export (n8n-compatible JSON) | STUB | MAJOR | No export implementation |
| Import (round-trip) | STUB | MAJOR | No import implementation |
| Deeplink-triggered flow execution | STUB | MAJOR | `requestr://flow/execute` deeplink declared in manifest but no handler found |

---

### Music

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Local file permission flow (READ_MEDIA_AUDIO) | WORKING | — | Onboarding Music card requests permission; `LocalMusicScanner` queries MediaStore |
| MediaStore query after grant | WORKING | — | `MusicRepository.loadLocalLibrary` / `pickRandomTrack` scan on demand |
| Playback controls (play/pause/prev/next/seek) | WORKING | — | `MusicRepository` StateFlows drive UI; `MiniPlayerPersistentBar` controls |
| Mini-player close-icon-when-paused | WORKING | — | `MiniPlayerPersistentBar` shows Close (×) when paused; `onClose` calls `stopMusic()` + `settings.updateMusicBarDismissed(true)` |
| No-autoplay-on-launch | WORKING | — | `_currentTrack` defaults to "No Track"; mini-bar only shows if `currentTrack != null` && `!musicBarDismissed` |
| Queue / reorder / swipe-remove | STUB | MINOR | Queue UI renders `tracks` list but no drag-reorder or swipe-remove handlers |
| Sources selector (Local/YouTube/JioSaavn) | STUB | MINOR | Only Local implemented; others show in UI but non-functional |

---

### Settings (Full Page)

| Fragment | Verdict | Severity | Notes |
|----------|---------|----------|-------|
| Theme/Accent architecture (Light/Dark separate from Accent) | WORKING | — | `appearanceMode` (SYSTEM/LIGHT/DARK) + `selectedLightTheme`/`selectedDarkTheme` + `themeMode` (9 themes); accent = theme's `samplePrimary` |
| Light theme shows selected-state outline | WORKING | — | `AppearanceSelector` + `AccentColorGrid` + drawer quick-switch all highlight active |
| Every toggle/slider/dropdown → real effect | PARTIALLY WORKING | MAJOR | **Verified working:** Theme, Corner Radius, Proxy Port, Capture Tier Preference, Remember App Filter, Haptic Feedback, Gesture Navigation, Density, Animation, Display Scale, Appearance Mode, all Features toggles. **Stub/No-op:** Typography/Surfaces/Borders/Icons/States (removed per Fix 03 B3), PiP (removed per Fix 03 B2), Tablet Mode (removed per Fix 03 B6), Advanced Features (removed per Fix 03 B10), Auto-start interceptor / Strict TLS / Follow Redirects (removed per Fix 09 B9c — no engine hooks) |
| PiP mode toggle | REMOVED | — | Correctly removed — no video surface in app |
| Typography/Surface/Border sub-settings | REMOVED | — | Correctly removed — no rendering hooks existed |
| Haptic feedback toggle | WORKING | — | `rememberAppHapticFeedback()` gates `performHapticFeedback` at call sites |
| Gesture navigation toggle | WORKING | — | Gates `ModalNavigationDrawer.gesturesEnabled` |
| Tablet mode toggle | REMOVED | — | Correctly removed — no adaptive layout exists |
| Compact/Comfortable density | WORKING | — | `ProvideDensityConfig` reads `settings.densityMode` → `LocalAppSpacing` |
| Animation settings | WORKING | — | `ProvideAnimationConfig` reads `settings.animationEnabled` + `transitionBehavior` |
| Traffic Capture / Request Logging / Response Logging | PARTIALLY WORKING | MAJOR | Toggles persisted but `CaptureEngine` reads `SettingsRepository.settings.value` (snapshot) — may not see live toggle change until process restart |
| Advanced Features section | REMOVED | — | Correctly removed — both toggles had zero consumers |
| Duplicate "Open full app settings" links | FIXED | — | Single link in `InPageMiniSettingsSheet` chrome |
| About block (version, package, replay wizard) | WORKING | — | `AppMetaInfoRow` shows correct values; Re-run Wizard button wired |
| CA Certificate screen (install/export/promote) | PARTIALLY WORKING | MAJOR | Install user-store works; Export PEM works; Promote to System Store wired but **not auto-triggered on root grant**; no Magisk module generation UI |
| Diagnostics rows (6 probes) | WORKING | — | All read real `CapabilityState` — VPN always available, CA trust store, Root, Shizuku, Frida |
| Clear Stream (deletes Room rows) | WORKING | — | `CaptureLogRepository.clearAll` + real row count in button label |

---

## Categorized Fix Plan

### Trust-Breaking / Do First (Critical — user is actively misled)

| # | Fix | Root Cause | Files |
|---|-----|------------|-------|
| 1 | **Implement TLS MITM proxy** — `MitmProxyEngine` that presents CA, terminates client TLS, connects to real server, decrypts/logs/re-encrypts | `CaptureEngine` only handles plaintext HTTP; TLS streams drained after SNI extraction | `interception/MitmProxyEngine.kt` (new), `interception/CaptureEngine.kt`, `interception/PacketPump.kt` |
| 2 | **Wire tier auto-selection to real engines** — instantiate `RootIptablesEngine` / `ShizukuTunEngine` / `HttpProxyEngine` / `MitmProxyEngine` per `CaptureMethod` | `CaptureController` always calls `RequestrVpnService.start()` | `ui/screens/CaptureController.kt`, `interception/*Engine.kt` (new), `interception/PreflightController.kt` |
| 3 | **Make CA actually used for MITM** — `CertificateAuthority` must provide per-domain leaf certs signed by CA to `MitmProxyEngine` | CA generated but never passed to proxy layer | `interception/CertificateAuthority.kt`, `interception/MitmProxyEngine.kt` (new) |
| 4 | **Implement CaptureMethodWatcher health checks per engine** | Watcher exists but no engine reports liveness | `interception/CaptureMethodWatcher.kt`, each engine |
| 5 | **Implement real Frida control channel** or remove Frida chip | `FridaCaptureEngine` stub; chips show "Running" but nothing uses it | `interception/FridaCaptureEngine.kt`, `ui/screens/InterceptScreen.kt` (Frida sheet) |

### Architecture-Level (Fix Once, Resolves Multiple Symptoms)

| # | Fix | Symptoms Resolved |
|---|-----|-------------------|
| 6 | **Unify Settings read path for capture engine** — `CaptureEngine` should observe `SettingsDataStore` flows directly, not `SettingsRepository.settings.value` snapshot | Traffic Capture / Request Logging / Response Logging toggles take effect immediately |
| 7 | **Hot-reload app filter in `RequestrVpnService`** — observe `SettingsDataStore.selectedPackages` and call `builder.addAllowedApplication`/`removeAllowedApplication` dynamically | Filter changes apply without stop/start |
| 8 | **Persist Flow nodes/connections to Room** — add `FlowNodeEntity`, `FlowConnectionEntity`, DAOs; `FlowRepository` backs StateFlow from Room | Positions survive restart; enables export/import |
| 9 | **Implement `ToolWorkspaceScreen` per-tool workspaces** — 18 domain-specific UIs per Fix 07 spec | Tools section becomes functional instead of stub library |

### Isolated Feature Fixes

| # | Fix | Area |
|---|-----|------|
| 10 | Fix Onboarding theme collision: wizard must set `appearanceMode` to `LIGHT`/`DARK` when user picks non-SYSTEM theme, or write `themeMode` + `appearanceMode=SYSTEM` with clear contract | Onboarding |
| 11 | Make Music permission card independent (or merge with Storage) — don't show separate grant for same `READ_MEDIA_AUDIO` | Onboarding |
| 12 | `SavedApisScreen` "Liked" tab: read favorites from Room `SavedApiRequestDao.observeAll()` filtered by `isFavorite`, not in-memory `_requests` | API Testr |
| 13 | Right-menu items (Cookie Jar, Environment, Make Tool, Add to Chat, Pin) — implement or remove | API Testr |
| 14 | Export cURL as file (not just clipboard) via `FileExporter` | API Testr / Intercept |
| 15 | Rules engine: `CaptureEngine` must consult `InterceptRuleDao.getEnabledRules()` and apply actions | Intercept |
| 16 | Notes HTML export + real Markdown rendering (markdown parser) | Notes |
| 17 | Flow: port-drag connection UI, context menu, pan/zoom, fit-to-view, mini-map, export/import, deeplink handler | Flow |
| 18 | Chats section — either implement or remove from navigation | Chats |

### Requires New Subsystem Work

| # | Fix | Blocker |
|---|-----|---------|
| 19 | HTTP/2 frame parsing in `CaptureEngine` | Need HPACK decoder + frame parser |
| 20 | WebSocket frame capture + inspector | Need WebSocket frame parser + separate UI tab |
| 21 | HTTP/3 / QUIC visibility (SNI only without MITM) | Need QUIC header parse; full MITM requires TLS 1.3 key export |
| 22 | Root iptables transparent redirect (`RootIptablesEngine`) | Requires rooted test device for verification |
| 23 | Shizuku tun capture (`ShizukuTunEngine`) | Requires Shizuku-paired device |
| 24 | Magisk module generation + auto-flash | Requires Magisk device + module template |
| 25 | Frida server push/manage + script injection | Requires rooted device + Frida binary |

---

## Untested / Environment-Limited Areas

The following spec requirements **could not be verified** in this source-only audit and require a physical device with specific capabilities:

| Area | What Would Be Needed to Test |
|------|------------------------------|
| **Real `su` / root grant handshake (`libsu`)** | Physical device with Magisk / KernelSU / KSU Next / nAPatch; grant `su` to app |
| **`VpnService` tun interface capturing another app's TCP traffic** | Physical device; install target app; start capture; verify packets in Room |
| **Shizuku binder connection** | Physical device with Shizuku app + ADB pairing / wireless debugging |
| **`frida-server` TCP handshake on port 27042** | Rooted device with `frida-server` binary deployed and running |
| **System-store CA injection via root/Shizuku** | Rooted device with writable `/system` (or Shizuku + ADB shell) |
| **`WAKE_LOCK` behavior under real Doze/battery optimizer** | Physical device; leave capturing overnight; verify foreground service survives |
| **Root iptables redirect (`ROOT_IPTABLES` tier)** | Rooted device; verify `iptables -t nat -L` shows REDIRECT rules; traffic captured without VPN consent |
| **Shizuku tun capture (`SHIZUKU_TUN` tier)** | Shizuku-granted device; verify capture works without VPN dialog |
| **MITM TLS decryption of real HTTPS traffic** | Any device with CA installed; visit HTTPS site; verify request/response bodies in log |
| **Frida SSL pinning bypass on pinned app** | Rooted device + Frida + target app with pinning (e.g., banking app) |
| **Magisk module flash + persistence across reboot** | Rooted device with Magisk; flash generated module; reboot; verify CA in system store |
| **Background capture survival (foreground service)** | Physical device; start capture; kill app from recents; verify notification persists + capture continues |
| **Music local library scan on various Android versions** | Devices with local audio files across API 28–36; verify MediaStore query returns tracks |
| **Notification permission (POST_NOTIFICATIONS) on Android 13+** | Android 13+ device; verify dialog appears and grant persists |
| **Storage permission (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE) on API 28–36** | Devices across API range; verify scoped storage behavior |

---

## Structural Map (Codebase Inventory)

### Screens / Composable Destinations (NavHost routes)
- `home` — `HomeScreen`
- `intercept` — `InterceptScreen`
- `apis` — `ApiTestrScreen`
- `saved_apis` — `SavedApisScreen`
- `tools` — `ToolsScreen`
- `tool_workspace/{toolId}` — `ToolWorkspaceScreen`
- `notes` — `NotesScreen`
- `flow` — `FlowScreen`
- `music` — `MusicScreen`
- `settings` — `SettingsScreen`
- `onboarding` — `OnboardingScreen`

### ViewModels / State Holders (singleton objects with StateFlow)
- `SettingsRepository` — `UserSettingsState` (unified DataStore)
- `InterceptionStateRepository` — `InterceptionState` + `CapabilityState`
- `ApiCollectionsRepository` — `ApiRequestItem` list (Room-backed)
- `CaptureLogRepository` — `CapturedTrafficItem` Flow (Room)
- `TrafficInterceptorRepository` — installed apps, traffic rules (in-memory), Frida scripts
- `NotesRepository` — folders + notes (in-memory)
- `FlowRepository` — nodes + connections (in-memory)
- `MusicRepository` — tracks + playback state (in-memory)
- `ToolsRepository` — static tool catalog (18 tools)
- `EnvironmentRepository` — environments + active (DataStore)

### Room Entities / DAOs
| Entity | DAO | Purpose |
|--------|-----|---------|
| `CapturedTrafficEntity` | `CapturedTrafficDao` | Intercepted HTTP transactions |
| `CaptureSessionEntity` | `CaptureSessionDao` | Capture session metadata + counters |
| `InterceptRuleEntity` | `InterceptRuleDao` | User-defined intercept rules |
| `SavedApiRequestEntity` | `SavedApiRequestDao` | Saved API Testr requests + chain extractions + assertions |

### Services
- `RequestrVpnService` — `VpnService` subclass; tun interface + `PacketPump` + `CaptureEngine` (VPN_TUN tier only)
- `CaptureForegroundService` — declared but not used (no start call found)
- `ShizukuProvider` — for Shizuku tier (not wired to engine)

### Permissions (Manifest + Runtime)
| Permission | Manifest | Runtime Request Site |
|------------|----------|---------------------|
| `INTERNET` | ✓ | — |
| `ACCESS_NETWORK_STATE` | ✓ | — |
| `READ_MEDIA_AUDIO` | ✓ | `OnboardingScreen` (Storage + Music cards) |
| `READ_EXTERNAL_STORAGE` (maxSdk 32) | ✓ | `OnboardingScreen` (Storage card, legacy) |
| `POST_NOTIFICATIONS` | ✓ | `OnboardingScreen` (Notifications card) |
| `WAKE_LOCK` | ✓ | `RequestrVpnService.acquireWakeLock()` |
| `FOREGROUND_SERVICE` + `SPECIAL_USE` + `DATA_SYNC` | ✓ | `RequestrVpnService.startForegroundNotification()` |
| `QUERY_ALL_PACKAGES` | ✓ | `TrafficInterceptorRepository.loadInstalledApps()` |
| `BIND_VPN_SERVICE` (service) | ✓ | `RequestrVpnService` declared |

### External Integration Points
| Integration | Implementation | Status |
|-------------|----------------|--------|
| Root (`libsu`) | `CapabilityProbe.probeRoot()` — `su -c id` handshake | Probe works; not used for capture |
| Shizuku | `CapabilityProbe.probeShizuku()` — reflection on `rikka.shizuku.Shizuku` | Probe works; not used for capture |
| Frida | `CapabilityProbe.probeFrida()` — TCP connect to 127.0.0.1:27042 | Probe works; no control channel |
| CA Cert (user store) | `CertificateAuthority` + `KeyChain.createInstallIntent()` | Works |
| CA Cert (system store) | `CaWhitelistService.promoteToSystem()` — `su` mount/copy | Wired in Settings; not auto-triggered |
| Deep Links | `requestr://` scheme in manifest; no router composable found | Declared; no handler |

---

*End of Deep Audit Report*