# Mockup-to-Screen Inventory — Authoritative Placement Map

**Date:** 2026-09-02 · **Branch:** `vorflux/master-plan-and-phased-design-implementation` · **HEAD:** `4ef9b02`
**Repo:** `Mahesh953-hub/requestr`

> **Purpose:** the single, authoritative map from **every** design mockup in `docs/designs/`
> to the exact Kotlin screen/component it must drive, with a precise APPLIED / PARTIAL /
> NOT APPLIED / NOT-A-SCREEN verdict and `file:line` evidence for each.
>
> **This is an analysis deliverable — no code was changed.**

## 0. Current state matters — read this before the tables

Two earlier dossiers describe this work as **PENDING** (`PHASE-A-APPLY-EXISTING-MOCKUPS.md` at
HEAD `81a7e25`, and `phase-a-verification.md` at `main`). Both are **stale**. The work they
describe has since shipped on this branch:

| Commit | What it did |
|---|---|
| `5545086` | Phase A — apply existing mockups (Interceptr, Saved APIs, Tools, API Testr, Reqy Assistant) |
| `71bdc23` | Phase A review fixes (fail-closed crypto, lifecycle, cURL importer, etc.) |
| `88ee80c` | simplify/core review fixes |
| `b4489a4` | **Phase B — app-wide design system, Home rebrand, zero-coverage screens** |
| `4ef9b02` | version bump to 6.1 (current HEAD) |

`phase-a-verification.md` is still correct as a *recon* reference and for the per-screen header-
compliance table; its "Domain B never implemented" claim is already corrected in that file's own §1.
Everything below was re-verified directly against the **current** files on disk.

### Mockup counts (reconciled)

- **54 HTML** + **8 PNG** = **62** mockup files (matches the Phase A count).
- Plus **1 written rebrand spec** (`home/home_rebranding.md`) that is "mockup-adjacent" — it has
  no HTML but governs Home precisely (it is Home's source of truth, alongside its 2 PNGs).
- Governance/decisions docs that are **NOT screen mockups** (recorded for completeness, not
  mapped as screens): `implementation-plan.md`, `design-plan.json`, `design-plan-runtime.md`,
  `judgment-v2.md`.

> Note on counts in the task brief: "relay-health (12 variants)" is actually **10**
> (5 states × dark/light), and "http3-detail (7 variants)" is **6 detail + 2 row-indicator**
> files (8 total). The `find` listing below is authoritative.

### Status legend

| Status | Meaning |
|---|---|
| **APPLIED** | The composable reproduces the mockup (theme-pair variants count once each). |
| **PARTIAL** | Most of the mockup is built, but a specific visual/structural delta remains. |
| **NOT APPLIED** | The mockup exists but the code does not reproduce it (or the divergence is a recorded decision). |
| **NOT-A-SCREEN** | Reference/judge doc (branding concepts), not a screen mockup. |

---

## 1. Complete mapping table (a)

Evidence column uses `File.kt:line`. "Wired" = where the screen hosts the component.

### 1.1 Root-level state/detail mockups (36 files)

| Mockup file | Target Kotlin screen/component | Status | Evidence | Divergence notes |
|---|---|---|---|---|
| `http3-detail-decoded-dark.html` / `-light.html` | `Http3DetailSheet.kt` (state `Http3DetailState.Decoded`) | APPLIED | `Http3DetailSheet.kt:52,93`; wired `InterceptScreen.kt:2461` | Matches "Fully decoded" layout (QUIC connection block + decoded headers/body tabs). |
| `http3-detail-quic-only-dark.html` / `-light.html` | `Http3DetailSheet.kt` (state `Http3DetailState.QuicOnly`) | APPLIED | `Http3State.kt:60,117`; `Http3DetailSheet.kt:52` | QUIC-detected-no-frames state is a first-class variant. |
| `http3-detail-undecrypted-dark.html` / `-light.html` | `Http3DetailSheet.kt` (state `Http3DetailState.Undecrypted`) | APPLIED | `Http3DetailSheet.kt:132`; `Http3State.kt:47,106` | "Observed, no keys · WHAT REQUESTR CAN SEE / TO MAKE THIS DECODABLE" replicated. |
| `http3-row-indicators-dark.html` / `-light.html` | `Http3RowIndicator.kt` | APPLIED | `Http3RowIndicator.kt:26`; wired `InterceptScreen.kt:1816` | Row subtitle states (v1/alpn/h3 · decoded/observed) present. |
| `relay-health-healthy-compact-*` (dark/light) | `RelayHealthCard.kt` | APPLIED | `RelayHealthCard.kt:54`; wired `InterceptScreen.kt:455` | Compact readout under the header. |
| `relay-health-healthy-detail-*` (dark/light) | `RelayHealthDetailSheet.kt` | APPLIED | `RelayHealthDetailSheet.kt:59`; wired `InterceptScreen.kt:854` | Relayed/dropped, flows-by-transport, NAT family blocks present. |
| `relay-health-degraded-detail-*` (dark/light) | `RelayHealthDetailSheet.kt` + `RelayHealthState.dropReasons()` | APPLIED | `RelayHealthDetailSheet.kt:233` (`DropReasonRow`); `RelayHealthState.kt:67,77-98` | **Divergence:** per-reason bars are backed by real counters, but reason list is an *honest* subset — "Packet exceeds tun MTU 1500, no fragment" is intentionally **omitted** (no drop site in this relay) per `RelayHealthState.kt:73-75`; some labels differ from the mockup (`"Flow table at cap — entry evicted"` vs mockup `"…new flow rejected"`). |
| `relay-health-down-compact-*` (dark/light) | `RelayHealthCard.kt` (NOT ACTIVE state) | APPLIED | `RelayHealthCard.kt:54` | "RELAY DOWN · 0 flows · NOT ACTIVE" empty state. |
| `relay-health-down-detail-*` (dark/light) | `RelayHealthDetailSheet.kt` (down state) | APPLIED | `RelayHealthDetailSheet.kt:59` | Down-detail variant. |
| `runtime-cdp-connected-*` (dark/light) | `CdpCard.kt` | APPLIED | `CdpCard.kt:69`; wired `SettingsScreen.kt:1022` | Multi-target attach rows, rule-enforcement tiles, storage streams, service workers all present. |
| `runtime-cdp-disconnected-*` (dark/light) | `CdpCard.kt` (disconnected empty state) | APPLIED | `CdpCard.kt:69` | Host:port input + empty/disconnected state. |
| `runtime-cdp-reconnecting-*` (dark/light) | `CdpCard.kt` (`ReconnectBlock`) | APPLIED | `CdpCard.kt:231` | Backoff attempt/schedule + stale-target marking. |
| `runtime-instrumentation-healthy-*` (dark/light) | `RuntimeInstrumentationCard.kt` + `StackFingerprintPanel.kt` | APPLIED | `RuntimeInstrumentationCard.kt:47`; `StackFingerprintPanel.kt:53`; wired `SettingsScreen.kt:975` | Adapter rows + per-signal fingerprint evidence drill-in. |
| `runtime-instrumentation-cronet-unavailable-*` (dark/light) | `RuntimeInstrumentationCard.kt` (adapter DEGRADED state) | APPLIED | `RuntimeInstrumentationCard.kt:46-135` | Adapter failure reason + DEGRADED pill. |
| `runtime-instrumentation-webview-active-*` (dark/light) | `RuntimeInstrumentationCard.kt` + `WebViewCaptureControl.kt` | APPLIED | `WebViewCaptureControl.kt:47` | Offscreen WebView capture URL/start/stop + captured rows. |
| `tls-failed-detail-unknown-ca-*` (dark/light) | `TlsFailureDetailSheet.kt` | APPLIED | `TlsFailureDetailSheet.kt:58`; wired `InterceptScreen.kt:2446` | Handshake stage rows, session facts, "Install requestr CA / Bypass this host". |
| `tls-failed-detail-handshake-failure-*` (dark/light) | `TlsFailureDetailSheet.kt` (handshake-failure variant) | APPLIED | `TlsFailureDetailSheet.kt:58` | Per-stage timing is **inferred/ordered**, not measured (`judgment-v2.md` N4, accepted). |
| `tls-rows-mixed-dark.html` / `-light.html` | `InterceptScreen.kt` (TLS/Connections tab) → `TlsFailureDetailSheet.kt` | APPLIED | `InterceptScreen.kt:2189,2242-2280,2446` | DECRYPTED / FAILED / SNI-JA3-ONLY row states; FAILED row opens the detail sheet. |

### 1.2 Subfolder screen mockups (18 HTML + 8 PNG + 1 written spec)

| Mockup file | Target Kotlin screen/component | Status | Evidence | Divergence notes |
|---|---|---|---|---|
| `ai-chat/reqy-assistant-light.html` (4 screens) | `ChatScreen.kt` (`ReqyDrawer`, `ReqySettingsScreen`, `AttachmentSheet`, `UserMessageLockPanel`) | APPLIED | providers `ChatProviderCatalog.kt:16-107` (8 kinds); attachment `ChatScreen.kt:320-353`; lock panel `:605-612`; no-naming `:223`; drawer `:1379`; own Settings `:383` | Minor: drawer "Environments" item has no destination in-app (honestly inert, `ChatScreen.kt:1372-1374`). |
| `ai-chat/ai-chat-section_prototype.png` | (raster) | — reference | — | Companion HTML is authoritative; PNG is photo-real reference only. |
| `api-testr/api_testr_light.html` / `_dark.html` | `ApiTestrScreen.kt` | APPLIED | tabs `:429-444`; WS adaptive `:268`; JSON toolbar `:879,2577,2592,2608`; redirect tap/long-press `:403-404,2138-2139` | **Divergence:** mockup label "Autosaved as draft · unsaved changes" replaced by a real dirty-check ("Unsaved changes") — no real autosave path exists (`:393-395,1994-1998`). Honest, documented. |
| `api-testr/api-testr-section_prototype.png` | (raster) | — reference | — | Companion HTML authoritative. |
| `branding/logos_and_naming_concepts.html` | N/A | **NOT-A-SCREEN** | — | 3 logo/naming concepts (requestr "Kinetic Request Route" recommended; ReqPulse; HexaFlow). Identity reference only; **brand concept choice still not made in code.** |
| `flow/flow_all_flows_picker_light.html` | `FlowScreen.kt` (inline "Pipeline Selector Strip") | PARTIAL | strip `FlowScreen.kt:255-270`; `PipelinePillItem` `:1254` | **Divergence:** mockup is a *dedicated* picker screen ("4 flows · 1 active", Active/Other Flows cards with node count · trigger · run count · status). Code collapses this to a horizontal pill strip with no per-flow metadata cards. |
| `flow/flow_flow_editor_landscape_light.html` | `FlowScreen.kt` (canvas, `viewMode=0`) | PARTIAL | canvas + true/false branch ports `:687-690,906`; toggle `:200-202` | **Divergence:** the mockup's **docked landscape sidebar** (Overview/Flows/Executions/Credentials/Environments/Variables/Schedules/Webhooks/Logs + profile footer) is **not implemented** — no orientation/sidebar code exists (grep for `landscape`/`sidePanel`/`Orientation` returns nothing). |
| `flow/flow_list_view_portrait_light.html` | `FlowScreen.kt` (node-sequence list, `viewMode=1`) | PARTIAL | list `:366-…` (`viewMode==0` gate `:366`); node rows | **Divergence:** node list + List/Flow toggle present, but the portrait list's **category filter pills** (All/Trigger/Action/Logic/Transform/Network/Data) and **"Search nodes, tools, docs…"** bar are **absent**. |
| `flow/flow-section-combined_prototype.png` | (raster) | — reference | — | Companion HTMLs authoritative. |
| `home/home_rebranding.md` (+ 2 PNGs) | `HomeScreen.kt` | APPLIED | pinned capsules `:483-511`; identity swap `:532`; status dot `:540`; dynamic color `:217`; 4 quick actions `:420-439`; search placeholder `:352`; status card `:819`; power grid `:1058` | Full rebrand landed (Phase B). See §2 for the one residual nuance. |
| `home/home-section_prototype.png` | (raster) | — reference | — | **No companion HTML.** Served by `home_rebranding.md` + this PNG. |
| `home/home-section_prototype_detailed.png` | (raster) | — reference | — | **No companion HTML.** Served by `home_rebranding.md` + this PNG. |
| `interceptr/interceptr-combined_light.html` (3 screens) | `InterceptScreen.kt` + `ResponseInspectorView.kt` | APPLIED | header `:384`; capture status `:428-438`; **VPN relay row icon `:1826-1830`**; set-cookie per-header reveal `:4800-4820`; `ResponseInspectorView.kt:416-466` | Q2 blocker resolved: `item.routedViaVpnRelay` drives the per-row VPN icon. Response tab set is a **superset** of mockup (adds Set-Cookies/Metrics/Tests). |
| `interceptr/interceptr-request-and-response-opened_prototype.png` | (raster, dark) | — reference | — | Companion HTML authoritative (Screen 2 detail). |
| `interceptr/requestr-traffic-rows_light.png` | (raster) | — reference | — | Companion HTML authoritative (Screen 1 rows). |
| `music/music_section_light.html` | `MusicScreen.kt` | APPLIED | `LockedBigPlayerCard.kt`-equiv `MusicScreen.kt:357`; `TrackArtworkView.kt`; `MiniLiveEqualizer.kt` `:808,889`; delete track `:291,342` | "Import MP3 removed" honored — grep for `Import`/`mp3` = zero. Overflow/transport/scrubber present. |
| `music/music-section_prototype.png` | (raster) | — reference | — | PNG is **stale** (still shows "+ Import MP3"); HTML is authoritative. |
| `nav_header_pill/nav_header_pill_editorial_light.html` / `_obsidian_dark.html` | `CommonComponents.kt` header capsules + `CollapsibleNavPill.kt` | APPLIED | `FloatingCapsuleHeader.kt:1255`; `HeaderMenuCapsule:1060`; `HeaderIdentityCapsule:1124`; `HeaderThemeCapsule:1089`; `HeaderIconCapsule:1031`; nav `CollapsibleNavPill.kt:76-83` | 3 header states map to: Home identity capsule (requestr/interceptr swap), screen `SectionHeader` titles, Reqy header. See §3. |
| `navigation/navigation_showcase_dark.html` / `_light.html` | `CollapsibleNavPill.kt` + `FloatingCapsuleHeader` + `AppNavigationDrawer.kt` | APPLIED (1 documented conflict) | collapsed/expanded pill `CollapsibleNavPill.kt:76-83`; drawer `gesturesEnabled = settings.gestureNavigationEnabled` `AppNavigationDrawer.kt:94`; pill disabled on Reqy/Settings `MainActivity.kt:748-759` | **Conflict (resolved):** mockup mandates `gesturesEnabled = false`; code keeps the user toggleable setting (design-system §10.4 decision). Mockup visual shows **4 slots** ("Home/Intercept/API Testr/Tools"); shipped pill has **6 tabs** (Home/Intercept/APIs/Tools/Flow/Music). Flag, don't silently drop either. |
| `onboarding/onboarding_editorial_light.html` / `_obsidian_dark.html` | `OnboardingScreen.kt` | PARTIAL (recorded decision) | steps `OnboardingScreen.kt:106,195-241` (Identity/Permissions/Appearance) | **Decision (kept):** mockup's VPN-permission + "Install requestr Root CA" steps are **not** in onboarding; VPN/CA setup lives in `InterceptScreen`/`Settings` (more contextually right). Onboarding governs *layout/styling* only. |
| `saved_apis/saved_apis_editorial_light.html` / `_obsidian_dark.html` | `SavedApisScreen.kt` | APPLIED | grouped cards `:153-162,313-334` (`groupRequestsIntoCollections`) | Q3 restructure landed: per-collection header + endpoint/env counts + expand/collapse. |
| `tools/tools-section-with-independate-tools-light.html` (3 screens) | `ToolsScreen.kt` + `ToolWorkspaceScreen.kt` + `JwtDecoder.kt` | APPLIED | library `ToolsScreen.kt:108,113,120,145`; pixel-tab `ToolWorkspaceScreen.kt:219-234,270-278,325-353`; JWT `JwtDecoder.kt:19`, dispatch `:183` | Binary-file Base64 input deferred (recorded product decision — "undesigned gap"). |

---

## 2. GAPS — every mockup that is NOT fully applied (b)

Only these items have a **real visual/structural delta** left. Everything else in §1 is APPLIED.

| # | Mockup | Target | Status | Exact delta to close |
|---|---|---|---|---|
| G1 | `flow/flow_all_flows_picker_light.html` | `FlowScreen.kt` | PARTIAL | Add a **dedicated All-Flows picker** (header "4 flows · 1 active", "Active" vs "Other Flows" sections, per-flow cards showing `node count · trigger · run count · status badge` + "Open"). Today it is a bare horizontal pill strip (`FlowScreen.kt:255-270`). |
| G2 | `flow/flow_flow_editor_landscape_light.html` | `FlowScreen.kt` | PARTIAL | Implement the **docked landscape sidebar** (Overview/Flows/Executions/Credentials/Environments/Variables/Schedules/Webhooks/Logs/Help, + profile footer) when the device is landscape; it must be docked layout, not a swipe drawer. No orientation handling exists. |
| G3 | `flow/flow_list_view_portrait_light.html` | `FlowScreen.kt` | PARTIAL | Add the **node-list category filter pills** (All/Trigger/Action/Logic/Transform/Network/Data) and the **"Search nodes, tools, docs…"** bar over the node list. |
| G4 | `navigation/navigation_showcase_dark/light.html` | `CollapsibleNavPill.kt` | PARTIAL | Decide and record the **tab-count discrepancy**: mockup shows 4 slots ("Home / Intercept / API Testr / Tools"), shipped pill has **6** (adds Flow, Music; labels "APIs" not "API Testr"). Resolve (keep 6 tabs as the real product, or conform) — do **not** silently ignore. |
| G5 | `onboarding/onboarding_*.html` | `OnboardingScreen.kt` | PARTIAL | Record (already in code comments/plans, but not centralized here) that **VPN-permission + Install-Root-CA onboarding steps are intentionally not implemented** in onboarding; they live in Intercept/Settings. No code change unless product reverses the decision. |
| G6 | `branding/logos_and_naming_concepts.html` | N/A | NOT-A-SCREEN | **Brand concept choice is unmade** (3 concepts, one "RECOMMENDED"). No screen currently consumes a chosen mark — Reqy uses its own Assistant mark, not the requestr wordmark (correct per ai-chat mockup). If a future Phase B screen needs a mark, make this decision explicitly first. |
| G7 | `music/music-section_prototype.png` | (raster) | stale-reference | PNG still shows "+ Import MP3" but HTML says "removed per instruction." **No action** — the stale PNG must never be used as the acceptance reference; `music_section_light.html` governs. (Listed here so nobody "fixes" by re-adding Import MP3.) |
| G8 | `relay-health-degraded-detail-*` | `RelayHealthDetailSheet.kt` | honest-subset | Mockup lists 5 drop reasons incl. "Packet exceeds tun MTU 1500, no fragment"; code must **not** fabricate that bar (no drop site in this relay). If desired, add a real MTU-drop counter to `TunRelay.RelayStats` first — that is a Lane-D backbone item, not a UI delta. |

No other mockup has an un-shipped delta. In particular, the two items that were open blockers at
Phase A's audit time are now **closed**:

- **Interceptr per-row VPN relay icon** (audit Q2) → resolved: `InterceptScreen.kt:1826-1830` renders
  `SettingsInputAntenna` when `item.routedViaVpnRelay`.
- **Domain B (Runtime CDP/Instrumentation)** → implemented (`RuntimeInstrumentationCard.kt:47`,
  `CdpCard.kt:69`, `WebViewCaptureControl.kt:47`, `StackFingerprintPanel.kt:53`), wired
  `SettingsScreen.kt:975,1022`, with all five backbone state models in
  `data/models/RuntimeInstrumentationState.kt`.
- **Relay per-reason drop counters** → implemented as `RelayHealthState.dropReasons()`
  (`RelayHealthState.kt:77-98`) + `RelayDropReason` `:67`.

---

## 3. Source-of-truth — ONE mockup per main screen (c)

If two mockups could describe one screen, this section states **which single one governs**.
Theme light/dark pairs are one mockup for this purpose.

| Screen / slot | Single governing mockup | Target file | Why |
|---|---|---|---|
| **Home** | `home/home_rebranding.md` (spec) **+** `home/home-section_prototype.png` / `home-section_prototype_detailed.png` (pixel reference) | `HomeScreen.kt` | The written spec *is* the rebrand; the 2 PNGs are its pixel source-of-truth. No HTML exists. |
| **Interceptr / traffic** | `interceptr/interceptr-combined_light.html` (its 3 screens) | `InterceptScreen.kt` + `ResponseInspectorView.kt` | Single combined mockup covers rows, opened-row detail, and response viewer. |
| **API Testr** | `api-testr/api_testr_light.html` (`api_testr_dark.html` is the dark version) | `ApiTestrScreen.kt` | One screen, two themes. |
| **Saved APIs** | `saved_apis/saved_apis_editorial_light.html` (`…_obsidian_dark` = dark) | `SavedApisScreen.kt` | One screen, two themes. |
| **Tools** | `tools/tools-section-with-independate-tools-light.html` | `ToolsScreen.kt` (library) + `ToolWorkspaceScreen.kt` (detail patterns) + `JwtDecoder.kt` | 3 screens in one mockup. |
| **Notes** | **none** (zero coverage — Phase B) | `NotesScreen.kt` | No `notes/` mockup; styled from `DESIGN-SYSTEM.md` tokens + shared chrome. |
| **Flow** | `flow/flow_flow_editor_landscape_light.html`, `flow/flow_list_view_portrait_light.html`, `flow/flow_all_flows_picker_light.html` (three distinct views + picker) | `FlowScreen.kt` | No single file covers all three; each is the source for its own view. |
| **Chat / Reqy Assistant** | `ai-chat/reqy-assistant-light.html` (4 screens) | `ChatScreen.kt` | Single combined mockup (New Chat / Settings / Side Drawer / Attachment). |
| **Music** | `music/music_section_light.html` | `MusicScreen.kt` | HTML governs; `music-section_prototype.png` is stale. |
| **Settings** (whole screen) | no single file — composed of: `runtime-cdp-*` + `runtime-instrumentation-*` (Domain B), plus Phase B zero-coverage blocks | `SettingsScreen.kt` | Runtime/CDP/Instrumentation blocks come from 12 Domain-B mockups; LOCAL REST API & CLI / PLUGIN MANAGER / APPEARANCE blocks are Phase B (no mockup, styled by tokens). |
| **Onboarding** | `onboarding/onboarding_editorial_light.html` (dark = `…_obsidian_dark`) | `OnboardingScreen.kt` | Governs style; content intentionally diverged (see G5). |
| **Nav pill (bottom)** | `navigation/navigation_showcase_dark.html` + `nav_header_pill/*.html` | `CollapsibleNavPill.kt` | Expanded 4-slot / collapsed-FAB mechanics + glass capsule treatment. |
| **Floating header** | `nav_header_pill/nav_header_pill_editorial_light.html` (+ dark) and `navigation/navigation_showcase_dark.html` header states | `CommonComponents.kt` (`FloatingCapsuleHeader`/`SectionHeader`; Home uses `ScrollReactiveHeader`) | 3 header states (root wordmark / active-intercept / child back-capsule) + transparency requirement. |
| **Root-level detail sheets** | each `http3-detail-*`, `relay-health-*`, `runtime-cdp-*`, `runtime-instrumentation-*`, `tls-*` group governs its own detail component (see §1.1) | `Http3DetailSheet.kt`, `RelayHealthCard.kt`/`RelayHealthDetailSheet.kt`, `CdpCard.kt`, `RuntimeInstrumentationCard.kt`, `TlsFailureDetailSheet.kt` | State/detail variants, not full screens. |

---

## 4. Cross-reference — decisions already made (do NOT re-litigate)

This inventory re-verifies, it does not re-decide. The following are already locked and must not be
reopened while closing §2's gaps:

- **`gesturesEnabled`**: keep the user toggle; the navigation mockup's hardcoded `false` is
  superseded (`AppNavigationDrawer.kt:94`, design-system §10.4). (Phase A §7.1 / Phase B §7.2.)
- **Set-Cookie/Cookie/Authorization masking**: mask-by-default with per-header reveal, ephemeral,
  copy real value. **Implemented** `InterceptScreen.kt:4800-4820`. (Continuity Handoff §3.1.)
- **Onboarding content**: keep Identity/Permissions/Appearance; do **not** move VPN/CA into
  onboarding. (phase-a-verification §Q8.)
- **Base64 binary-file input**: deferred — "undesigned gap"; do **not** add it as a mockup
  requirement. (Continuity Handoff §3.2, Phase A §7.)
- **Music Import MP3**: removed; HTML is authoritative over the stale PNG. (Phase A §3 row 11.)
- **Branding concept**: still **un-chosen**; choose explicitly (and record) before any screen
  needs a new mark. (Phase B §7.5.)
- **Header transparency (global)**: floating capsules, no shared filled bar, page background
  shows through. Bottom nav pill = one shared glass capsule (correct); header = separate capsules.
  **All screens now host a glass header** — `SectionHeader` → `FloatingCapsuleHeader`
  (`CommonComponents.kt:511,519`) on Intercept/ApiTestr/Tools/Flow/Music/Chat/Settings; direct
  `FloatingCapsuleHeader` on Notes/RecentlyOpened/SavedApis/ToolWorkspace; `ScrollReactiveHeader`
  on Home (`HomeScreen.kt:483`).
- **Domain B** and the **relay per-reason counters** are done — the two "backbone" blockers from the
  Phase-A audit are no longer blockers (see §2 closing note).

---

## 5. PNG roster (explicit "no HTML" flag)

| PNG | Has companion HTML/spec? | Note |
|---|---|---|
| `home/home-section_prototype.png` | **No HTML** — has `home_rebranding.md` spec | Pair of PNGs + spec govern Home together. |
| `home/home-section_prototype_detailed.png` | **No HTML** — has `home_rebranding.md` spec | Same. |
| all other 6 PNGs (ai-chat, api-testr, flow, interceptr×2, music) | Yes — HTML mockup in same folder | Raster references only; the HTML is the literal source of truth. `music-section_prototype.png` is additionally **stale**. |

No PNG's contents were guessed for mapping: the 6 PNGs with sibling HTMLs are governed by those
HTMLs; the 2 Home PNGs are governed by `home_rebranding.md` (whose §1–§9 fully specify the header
and lock-track the body).
