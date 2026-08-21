# requestr — App-Layer Honesty Pass (Search, Flow, Notes, Tools, Chat, Music, Deep Links)

> **TL;DR:** Every UI affordance in the app layer that is currently dead, fake, or labelled with a stale `TODO` gets either a real implementation or an honest removal, verified line-by-line against the code on branch `backup` rather than trusted from `AUDIT_REPORT.md`.

## Method

I re-read every file the audit implicated instead of carrying its findings forward. `AUDIT_REPORT.md` is a snapshot of `main` @ v3.4 and its own addendum (lines 378-402) says so. Roughly half of its app-layer findings are already fixed; a different set of real defects it never mentioned are still live.

## What is genuinely still broken

| Area | Evidence | Verdict |
|---|---|---|
| Home global search | `HomeScreen.kt:98,184-189` — `grep -n searchQuery` returns only those 4 lines. No filter, no results, no navigation. | Dead UI |
| Flow import | `FlowScreen.kt:171-187` fires `ACTION_GET_CONTENT` with no result handling. `FlowSerializer.importFromJson` has zero call sites. | Fake button |
| Flow node edit | `nodeForEdit` (`FlowScreen.kt:116`) is written at lines 244/306 and never read. | Dead end |
| Flow condition routing | `FlowExecutor.kt:64-66` enqueues all downstream nodes; `buildAdjacency` (200-206) discards `fromPort`. | Branching impossible |
| Flow condition evaluation | `FlowExecutor.kt:140-168` is substring matching on lowercased config; `not empty` branch is unreachable; `else -> "true"`. | Fake logic |
| Flow result banner | `FlowScreen.kt:215-231` always renders green + `CheckCircle`, even on failure. | Misleading |
| Flow port/id round-trip | `toFlowNodeItem()` drops ports (`AppRepositories.kt:260`); `toFlowConnectionItem()` drops `id` (269-272) while `toEntity()` computes one (264). | Data loss |
| Keyboard shortcuts | `MainActivity.kt:131-155` broadcasts `SEND_REQUEST`/`NEW_REQUEST`; no `registerReceiver` exists anywhere in `app/src/main`. | No-op |
| Deep-link drain | `MainActivity.kt:345-358` drains the queue once inside `LaunchedEffect(Unit)` and `break`s — links arriving while open are never handled, which is exactly what Fix #22 promises. | Broken |
| Deep-link actions | `SettingsScreen.kt:646-651` advertises `intercept/toggle`, `api/run`, `flow/execute`, `note/create`; the handler (`MainActivity.kt:223-247`) only navigates. `"flow/execute"` as a `uri.host` is unreachable (host cannot contain `/`). | Advertised-but-fake |
| Markdown / checklist preview | `NotesScreen.kt:243-256` renders Markdown as monospace raw text; HTML path calls `spanned.toString()`, which strips every span. | Missing |
| Custom tool workspace | `ToolWorkspaceScreen.kt:157` → `PlaceholderWorkspace` "🚧 Coming Soon"; every `custom-*` id lands there. | Dead end |
| Custom tool visibility | `ToolsScreen.kt:74` calls `getToolsCatalog()` plainly; `remember(searchQuery, selectedCategory)` doesn't key on it, so a new tool doesn't appear. | Broken |
| Music no-op buttons | `MusicScreen.kt:115` `onClick = { /* Refresh local tracks */ }`, `:276` `onClick = { /* Scan Audio */ }`. | No-op |
| Music fake sources | `MusicSourceType` has `SYNTH_LOFI`/`AMBIENT_CYBER`/`CHILL_CODING`; `grep YouTube\|JioSaavn` = 0 hits; only `LOCAL` is ever produced. | Fabricated |
| Chat provider config | `ChatRepository.kt:27-29` are plain `var`s; nothing in the app writes them. `ChatScreen.kt:95` reads a plain `var` in composition (won't recompose). | Unreachable feature |
| Notes/tools/chat persistence | `NotesRepository` (`AppRepositories.kt:41-101`) and `ToolsRepository.customTools` (:382) are in-memory; chat history too. | Lost on restart |
| Workspace export | `WorkspaceExporter.exportWorkspace`/`importWorkspace` are complete and real, with **zero call sites** app-wide. | Orphaned |
| Capture settings in service | `CaptureEngine.kt:499,502-503,602-605` read `SettingsRepository.settings.value`, but `SettingsRepository.init()` is only called from `MainActivity.onCreate()`. A service-only process falls back to the hardcoded `true` defaults. | Real gap |
| Tools badge | `HomeScreen.kt:468` says `"35+ tools"`; catalog has 19. | Dishonest copy |

## What the audit claims but is already fixed (excluded, with proof)

- **Flow pan/zoom, mini-map, port-drag, context menu, export** — all real: `FlowScreen.kt:363-370` (`rememberTransformableState`), `650-713` (mini-map Canvas), `612-644` (`detectDragGestures` + drop hit-test), `508-534` (`DropdownMenu`), `129-152` (`FlowSerializer.exportToJson` → `FileExporter`).
- **Flow node persistence (#8)** — `FlowRepository` is Room-backed (`AppRepositories.kt:154-272`).
- **`FlowExecutor` handling only API nodes** — `executeNode` (`FlowExecutor.kt:85-99`) covers all 8 types.
- **Saved APIs favourites race (#9/#12)** — `ApiCollectionsRepository` collects `savedApiRequestDao().observeAll()`; the stale StateFlow is gone. Only residual: the `catch` at `:606-609` logs "reverting" but never reverts.
- **API Testr stub menu items (#13)** — Cookie Jar / Make Tool / Add to Chat / Pin are absent from the menu entirely (`ApiTestrScreen.kt:1012-1080` has exactly six real items).
- **cURL clipboard-only export (#14)** — file export exists at `:1052-1080`.
- **Onboarding theme collision (REG-05) and duplicate permission card (#11)** — both fixed at `OnboardingScreen.kt:208-232` and `:452-463`.
- **CA auto-promote (#21)** — implemented at `SettingsScreen.kt:1249-1271`; only the `(TODO)` label at 1246 is stale.
- **VPN app-filter hot reload (#2)** — real observer at `RequestrVpnService.kt:178-198`; only the `(TODO)` label is stale.
- **All 19 built-in tools have workspaces** — `ToolWorkspaceScreen.kt:124-156` covers every catalogue id.
- **Notes export (#18), WebSocket (#14), SSE (#15), DNS (#16), rule actions (#7)** — all really implemented; the `(TODO)` prefixes on those comments are labels on working code.

## Stale TODO labels to remove (not features to build)

`MainActivity.kt:122,131,214,345` · `SettingsScreen.kt:1246` · `ToolsScreen.kt:124` · `NotesScreen.kt:121` · `FlowScreen.kt:127,347` · `MusicScreen.kt:289` · `ToolWorkspaceScreen.kt:158,705,709,714` · `CaptureEngine.kt:263,277,535,544` · `PacketPump.kt:168` · `RequestrVpnService.kt:180` · `AppModels.kt:392` · `AppRepositories.kt:797` · `ChatRepository.kt:17` · `ChatScreen.kt:55` · `WorkspaceExporter.kt:17`

Each becomes a plain descriptive comment once the corresponding feature is confirmed real. The user's requirement is zero `TODO` comments left behind, so this is mandatory cleanup, not cosmetics.

## Decisions I made without asking

- **Music sources:** delete `SYNTH_LOFI`, `AMBIENT_CYBER`, `CHILL_CODING` from `MusicSourceType` rather than build streaming backends. Nothing produces them; keeping them is fabricated content.
- **`PlaceholderWorkspace`:** delete it after adding a real custom-tool workspace. No id can reach it afterwards.
- **Keyboard shortcuts:** replace the broadcast hack with an in-process `SharedFlow` that `ApiTestrScreen` collects. Implicit broadcasts are the wrong mechanism on modern Android and would need a manifest receiver for nothing.
- **Flow condition config:** a small structured expression evaluator (`left op right`, ops `==`/`!=`/`contains`/`matches`/`empty`/`notEmpty`, left side resolvable as `status`, `body`, or a JSON key via the existing `JsonFieldExtractor`) plus `true`/`false` output ports. This keeps `configData` a plain string so no Room change is needed.

## Resolved decisions (2026-08-21)

All four blocking questions are answered. Nothing is blocked.

| # | Decision | Effect on scope |
|---|---|---|
| Q1 | **New Room tables** for notes, folders, custom tools, chat messages, chat providers | Five table+column requests. The transport scope owns the version number and the `Migration` objects; the tables must arrive as a real migration, never a destructive wipe |
| Q2 | **Keystore-backed storage for the API key only**; URL, model, and other non-secret config go to Room/DataStore | New `data/prefs/SecureKeyStore.kt`. `chat_providers` has no key column |
| Q3 | **Skip** Cookie Jar / Make Tool / Add to Chat / Pin | Verified absent from the UI with nothing stub-shaped left behind — recorded as out of scope with evidence, not silently dropped |
| Q4 | **Wire** `WorkspaceExporter` into BACKUP & PRIVACY and fix its three honesty bugs | No delete branch. Orphaned working code is exactly the pattern being eliminated |

### Q2 storage mechanism — confirmed

The decision originally named `EncryptedSharedPreferences`. That API (`androidx.security:security-crypto`) is **deprecated upstream** with no 1:1 replacement, so naming it would have shipped the same class of defect this pass eliminates.

**Confirmed 2026-08-21: raw `AndroidKeyStore` + `Cipher("AES/GCM/NoPadding")`, zero new dependencies.** `minSdk = 24` clears the API 23 floor for `KeyGenParameterSpec`, and the app already calls `java.security.KeyStore` in 7 places. `tink-android:1.18.0` and `security-crypto:1.1.0` are recorded as **rejected**, not as live options — neither gets added.

Because this is hand-rolled rather than a library, the plan sets three non-negotiables: a fresh random IV per write, GCM auth-tag failures surfaced as "key unreadable, re-enter it" instead of a silent empty string, and the plaintext key never logged. The test asserting the on-disk value differs from the plaintext is what proves encryption actually happens.

### Cross-scope handoff — CA keystore password

`CertificateAuthority.kt:42` hardcodes the PKCS#12 password protecting the CA private key (persisted by `persist()` at `:127-136`). Same class of problem as a plaintext API key. **Routed to the SDK/decryption scope; no action in this plan.** Recorded so it survives between scopes.

### Q3 — the evidence

The API Testr overflow menu at `ApiTestrScreen.kt:1012-1080` contains exactly six items, all backed by real code. `grep -rn "Cookie\|cookieJar\|Add to Chat\|Make Tool" ApiTestrScreen.kt` returns nothing. No dead entry, no disabled button, no "Coming Soon" label.

## Marker sweep is exhaustive, not just the five G2 labels

Every `TODO`/`FIXME`/`Coming Soon`/`Placeholder` marker in this scope's 16 files was re-grepped. **13 real marker sites** exist and each is assigned to a batch; the full table with line numbers is in `/code/.plans/plan-app.md`. Notable additions beyond the previously catalogued set:

- `FlowScreen.kt:347` — KDoc reading `Implements TODO items #23, #24, #32, #33, #34.` It is a reference to *completed* work, but it still trips the final grep, so it must be reworded.
- `ToolWorkspaceScreen.kt:705, 709, 714` — the `PlaceholderWorkspace` function and its `"— Coming Soon"` text, plus its only call site at `:158`.
- `interception/CaptureEngine.kt:544` — `// For now, log + persist with a tag …` inside the `RuleAction.REDIRECT` branch, which does real work. Provisional wording on working code; reword during Batch G.

**24 benign matches must not be touched** — they are Compose `placeholder =` text-field parameters, listed individually in the detailed plan. `HttpParser.kt:196`'s `for now` belongs to the sibling CONNECT/decryption scope.

## Risk notes

Six files are large and shared with sibling plan scopes: `InterceptScreen.kt` (2968), `SettingsScreen.kt` (1858), `ApiTestrScreen.kt` (1561), `AppRepositories.kt` (1033), `MainActivity.kt` (901), `CaptureEngine.kt` (743). Work items are batched per file so each file is opened once. `CaptureEngine.kt`, `RequestrVpnService.kt`, `PacketPump.kt`, `SettingsDataStore.kt`, and `data/local/*` are owned by the transport-core plan.

**Hard ordering constraint:** Batch G (the capture-settings plumbing plus its six comment fixes) must land **after** the transport scope's VPN_TUN relay/NAT rewrite. Those three files are being heavily rewritten there, and landing G first means it gets clobbered or has to be reapplied inside a much larger refactor. G is scoped to roughly a dozen changed lines plus three new DataStore accessors, described as a diff against current code, so reapplying it is cheap if a conflict still happens.
