# Phase A — Apply Existing Mockups Exactly As Designed

**Status: PENDING (read-only audit; no code has been changed, committed, or pushed)**
**Repo:** `Mahesh953-hub/requestr` · branch `main` · HEAD `81a7e25`

## 1. Executive Summary

`docs/designs/` contains **62 mockup files** (54 HTML + 8 PNG) produced by a prior design pass. A
prior *implementation* attempt merged most of this work, but one entire design domain was never
built, and several other mockups were only partially cross-checked against the shipped code. Phase
A's job is to close every gap where a **literal, pixel/markup-level mockup already exists** — i.e.
where "design" work is done and only "port it into Compose exactly as drawn" work remains. Anything
without a literal mockup (the `home/` rebrand, and screens with zero design coverage) is out of
scope here and is covered instead by `plan-requestr-phaseB.md`.

**The single biggest finding:** the **Runtime CDP + Instrumentation** redesign (12 of the 36
root-level mockups: `runtime-cdp-*` and `runtime-instrumentation-*`) was never implemented. The
existing `SettingsScreen.kt` "RUNTIME INSTRUMENTATION" and "BROWSER BRIDGE (CDP)" sections are
real, functioning, but are the **old, plain UI** — none of the composables the approved
implementation plan called for (`RuntimeInstrumentationCard`, `CdpCard`, `WebViewCaptureControl`,
`StackFingerprintPanel`) exist anywhere in the codebase, and no merge commit for this work exists in
`git log` (unlike the other three domains, which all have merge commits). This is also the mockup
set with the deepest **backbone** gap: it needs new state models for multi-tab CDP sessions,
per-signal fingerprint evidence, live storage-stream events, and rule-enforcement counters before
any UI can be wired — see §5.

**Second finding — a global hard constraint, not a screen-specific gap:** every mockup in
`nav_header_pill/` and `navigation/` (and the `home/` rebrand doc, which is Phase B but shares this
constraint) requires the floating header and bottom nav pill to render as **individually
glass-effected floating capsules with no shared filled background/toolbar surface behind or between
them** — the page background must show through fully in the gaps. The existing `CollapsibleNavPill`
composable already matches this for the **bottom nav** (one glass capsule containing all tab
slots, exactly as the mockups show — this is correct, not a violation). The **header**, however,
is a different story per screen (see the per-screen table below and Phase B's Home entry) — this
must be checked and enforced on every screen Phase A touches.

## 2. Reconciled Mockup Inventory

| Category | Count | Notes |
|---|---|---|
| Root-level HTML (dark/light state variants) | 36 | Domains A–D of `implementation-plan.md` |
| Subfolder HTML | 18 | 12 subfolders: ai-chat(1), api-testr(2), branding(1), flow(3), interceptr(1), music(1), nav_header_pill(2), navigation(2), onboarding(2), saved_apis(2), tools(1) |
| PNG reference screenshots | 8 | ai-chat(1), api-testr(1), flow(1), home(2, **Phase B**), interceptr(2), music(1) |
| **Total** | **62** | 54 HTML + 8 PNG |

Root-level 36 = `http3-detail-*`(6) + `http3-row-indicators-*`(2) + `relay-health-*`(10) +
`runtime-cdp-*`(6) + `runtime-instrumentation-*`(6) + `tls-failed-detail-*`(4) +
`tls-rows-mixed-*`(2). Split across two manifests: `design-plan.json` covers 24 (Domains A/C/D —
HTTP/3, Relay Health, TLS Failure), `design-plan-runtime.md` covers the remaining 12 (Domain B —
Runtime CDP/Instrumentation), which explicitly states it was written by "a sibling agent" so as not
to collide with `design-plan.json`.

Of the 8 PNGs, only the 2 in `home/` have **no accompanying HTML** — every other PNG is a
supporting photo-realistic reference alongside an HTML mockup that remains the literal
source-of-truth for markup/CSS. `home/` is therefore the only subfolder that is pure Phase B.

## 3. Mapping Table

| Mockup group | Target app file(s) | Current status | Notes |
|---|---|---|---|
| `http3-detail-*`, `http3-row-indicators-*` (Domain A, 8 files) | `Http3DetailSheet.kt`, `Http3RowIndicator.kt`, `data/models/Http3State.kt`, `interception/quic/QuicDetailProvider.kt` | **Implemented, merge commit exists.** Wired into `InterceptScreen.kt` (`Http3RowIndicator(state = h3State)` line 1553, `Http3DetailSheet(` line 2145). | Re-verify visually against all 4 dark/light state pairs; low risk. |
| `relay-health-*` (Domain C, 10 files) | `RelayHealthCard.kt`, `RelayHealthDetailSheet.kt` | **Implemented, merge commit exists.** Wired at `InterceptScreen.kt` lines 436/800. | **Backbone gap:** `TunRelay.RelayStats` has no per-reason drop counters (EAGAIN, no-route, checksum, MTU, flow-cap, ICMPv6) that the degraded-detail mockup's per-reason bars need. Flagged in `implementation-plan.md` itself; still open. |
| `tls-failed-detail-*`, `tls-rows-mixed-*` (Domain D, 6 files) | `TlsFailureDetailSheet.kt`, `data/models/TlsFailureDetail.kt`, `interception/tls/TlsFailureDetailProvider.kt` | **Implemented, merge commit exists.** Wired at `InterceptScreen.kt` line 2130. | Accepted limitation (per `judgment-v2.md` N4): `TlsSessionEntity` has no per-stage timestamps, so per-stage timing is inferred/ordered, not measured. Keep as documented, non-blocking. |
| `runtime-cdp-*`, `runtime-instrumentation-*` (Domain B, 12 files) | **Missing:** `RuntimeInstrumentationCard.kt`, `CdpCard.kt`, `WebViewCaptureControl.kt`, `StackFingerprintPanel.kt`, `data/models/RuntimeInstrumentationState.kt`, `runtime/RuntimeInstrumentationStateProvider.kt`; modifies `SettingsScreen.kt` | **NOT implemented.** `SettingsScreen.kt` (lines ~953–1290) has a real but plain "RUNTIME INSTRUMENTATION" / "BROWSER BRIDGE (CDP)" section: live `RuntimeInstrumentationEngine.stateFlow`, `BrowserBridgeManager.connectionState`, `HttpStackDetector`/`StackFingerprinter` confidence histogram — functional, but none of the mockup's UI. | **Highest priority, highest complexity item in Phase A.** See §5 for the concrete backbone work needed before UI. |
| `ai-chat/reqy-assistant-light.html` (4-screen combined) | `ChatScreen.kt` | Renamed/redesigned once already (`a5b0844`) as "Reqy Assistant." AI-provider API key storage confirmed **already Keystore-backed** (`SecureKeyStore.kt`, task 12: `AndroidKeyStore` AES/GCM, wired through `ChatRepository.kt`) — this resolves what was flagged as an open backbone question. | Needs a full pass against the mockup's 4 screens (Chat / Settings / Drawer / Attachment Menu) — provider chip list completeness (must include Ollama Local / HuggingFace Free / Groq Free / +Custom, not a short hardcoded list), attachment sheet's 5 real actions (Image/File/Code/Request-Response/Screenshot), long-press lock-panel on user bubbles, "no naming modal on new chat" removal. Not yet individually verified line-by-line against current `ChatScreen.kt`. |
| `api-testr/api_testr_{dark,light}.html` | `ApiTestrScreen.kt` | Single-screen mockup pair (dark/light of the same "New Request" composer); text length (~1.3K chars) suggests it does **not** include a Response-viewer screen — that content lives in `interceptr/` instead (see below). | Verify method dropdown (GET/POST/PUT/DELETE/PATCH/HEAD/WS, with WS driving adaptive layout), tabs (Query/Headers/Body/Auth/Scripts/Chain), JSON toolbar (beautify/copy-as-JSON/copy-as-flat/clear-all/raw-editor toggle with `//`-disables-row sync), autosave-draft indicator, redirect icon's tap-vs-long-press behavior. |
| `branding/logos_and_naming_concepts.html` | N/A — reference doc | Not a screen mockup. 3 logo/name concepts (requestr "Kinetic Request Route" — **recommended**; ReqPulse; HexaFlow). | Informational only for Phase A; carry into Phase B as the identity reference for any screen needing a new mark (e.g. Reqy's placeholder glyph, per ai-chat mockup, deliberately NOT the requestr wordmark). No decision required to land Phase A. |
| `flow/` (3 files: All-Flows picker, landscape editor, portrait list) | `FlowScreen.kt` | **Partially implemented.** `FlowRepository.activeFlowId` + `FlowPipelineItem` (Room-persisted, multi-flow) confirmed present — the backbone for "one active flow, several stored flows" exists. **Not confirmed:** a dedicated "All Flows" picker screen, or a List View / Flow View mode toggle — grep of `FlowScreen.kt` found neither `AllFlowsPicker` nor a `ListView`/`FlowView` toggle symbol. | Needs verification of whether the picker + view-mode split exists under different names, or needs building. Docked landscape sidebar (not swipe drawer) and the two-port TRUE/FALSE branching validate-node pattern also need direct verification against `FlowCanvas`/node composables (`FlowScreen.kt` lines 559+, 1248+). |
| `interceptr/interceptr-combined_light.html` (3 screens) | `InterceptScreen.kt`, `ResponseInspectorView.kt` | **Mostly implemented** (traffic list, row tap → detail, Summary/Raw/Headers/Body tabs, connection/TLS/cert drill-ins all present in some form per DESIGN-SYSTEM.md's Lane D). **Gap found:** the mockup's per-row "VPN relay icon" (distinct from the lock/HTTPS icon and status icon, meaning "this connection was routed through requestr's VPN engine") was not found at row level — the only `Icons.Default.SettingsInputAntenna` usages in `InterceptScreen.kt` are on the header's "Connections" button and a capturing-state icon, not a per-row indicator. | **Backbone question, not just a UI gap:** does the traffic-row/connection model currently expose a boolean distinguishing "routed via requestr VPN" per row? If not, this needs a small model addition before the row icon can be wired. Also flag the mockup's open design question on redacting `set-cookie` header values by default (with reveal) — unresolved, needs a product decision (see §7). |
| `music/music_section_light.html` | `MusicScreen.kt`, `TrackArtworkView.kt` | **Mostly implemented** per DESIGN-SYSTEM.md §12.2 (`LockedBigPlayerCard`, `MiniLiveEqualizer`, `TrackArtworkView` all ported; `MusicSourceType.LOCAL`-only). | **Discrepancy to reconcile:** the HTML mockup's text explicitly says "Import MP3 removed per instruction," but the companion PNG (`music-section_prototype.png`) still shows a visible "+ Import MP3" pill in the Up Next header. Treat the **HTML as authoritative** for Phase A (it is the literal, current-intent markup); the PNG is an older/stale reference image. Verify current code doesn't still expose an Import MP3 action, and if it does, remove it as part of this port. |
| `nav_header_pill/*.html` (2 files) + `navigation/*.html` (2 files) | `CollapsibleNavPill.kt`, `AppNavigationDrawer.kt` | **Bottom nav pill: implemented correctly** — `CollapsibleNavPill.kt` already renders one shared glass capsule (rounded 28dp, gradient+border+shadow, no blur) containing all nav tab slots, collapsing to a single FAB circle on scroll-down — this matches the mockups' "expanded 4-slot" / "collapsed FAB circle" states exactly. **Header states 1–3** (root wordmark / active-intercept capsule / child-screen back capsule) need a per-screen check — see Phase B's Home entry for the one confirmed gap (Home's header). | **Conflict found and must be surfaced, not silently resolved:** `navigation_showcase_dark.html`'s "MANDATORY CONSTRAINTS" comment says side-swipe-to-open-drawer must be "completely disabled (`gesturesEnabled = false`)." Current code (`AppNavigationDrawer.kt` line 90) instead does `gesturesEnabled = settings.gestureNavigationEnabled` — a user-configurable toggle — which matches `docs/DESIGN-SYSTEM.md` §10.4's explicit decision to *reject* the hardcoded-`false` approach. **Recommendation: keep the existing user-toggle behavior** (it is the more recent, deliberately-decided frozen contract) and treat the mockup's comment as superseded; record this as a resolved conflict, not a TODO. |
| `onboarding/*.html` (2 files) | `OnboardingScreen.kt` | **Content mismatch found.** Current code (per DESIGN-SYSTEM.md §12.6) implements `DynamicIslandProgressPill` with steps "Identity / Theme / Diagnostics." The mockup describes different step content: VPN Service Permission (granted), Install requestr Root CA (required for MITM inspection on Android 7+), primary action "Install CA Certificate & Continue." | These are likely two different onboarding concerns (first-run identity/theme setup vs. VPN+CA setup for interception) that may need to coexist as separate steps/screens, or the mockup may supersede the current step set. Needs a product decision before implementation — flag in §7. |
| `saved_apis/*.html` (2 files) | `SavedApisScreen.kt` | Current screen renders a flat `LazyColumn` of requests (`ApiCollectionsRepository.requests`), test-report content, and favorite/delete actions. Mockup describes a **Collections-grouped** list (e.g. "GitHub REST API v3 — 14 endpoints · 2 environments," "Stripe Payment Intents — 8 endpoints · Webhooks configured") with per-collection endpoint counts and environment counts. | Needs verification of whether `ApiCollectionsRepository` already models "collections" as a grouping concept, or whether the current screen is a flatter list that needs restructuring into the mockup's grouped-card layout. |
| `tools/tools-section-with-independate-tools-light.html` (3 screens) | `ToolsScreen.kt`, `ToolWorkspaceScreen.kt`, `JwtDecoder.kt` | **Mostly implemented** per DESIGN-SYSTEM.md §12.5: `ToolCategorySelector` + `LazyVerticalGrid` for the tool library, `RedesignedToolCard` for individual entries — confirmed present (`ToolsScreen.kt` lines 111/118/371). | Needs verification of the two reusable detail-screen patterns the mockup specifies: (a) single-field pattern (JWT Decoder: independently-scrollable input/output, live-decode vs explicit-decode-button policy per tool type, "Signature not decodable, future Verify mode" note) and (b) dual pixel-tab pattern (Base64/AES/XOR: spring-animated mode switch, no auto-reprocess on switch, swap-input-output action). Also flag: binary-file drag/drop or file-picker input for Base64 is explicitly **not shown** in the static mockup and is called out as "a real gap to design" — treat as an open question, not an implicit requirement (§7). |

## 4. Suggested Implementation Order

Reuse the already-approved dependency ordering from `implementation-plan.md` / `judgment-v2.md`
(APPROVED) for the root-level 36, since three of its four domains are already merged and the
fourth (Domain B) is the reason this audit exists:

1. **Domain B — Runtime CDP + Instrumentation** (backbone work first, per §5, then UI). This is the
   only domain with no existing merge commit and the largest surface area; start it first so it is
   not once again the item that slips.
2. **Subfolder screens with the smallest gaps**, in parallel with Domain B: `api-testr` (verify
   only), `tools` (verify + build the two reusable detail patterns), `music` (small
   reconciliation), `saved_apis` (verify/restructure).
3. **`ai-chat`** — moderate scope (4 screens, keystore backbone already resolved, mainly a UI/content
   completeness pass).
4. **`flow`** — needs the picker/list-view-toggle backbone question resolved before its 3-screen UI
   work can be scoped precisely.
5. **`interceptr`** — the VPN-relay-row-icon backbone question and the set-cookie redaction product
   decision (§7) should be resolved before this lands, since both affect the data model, not just
   layout.
6. **`nav_header_pill` / `navigation`** — mostly verification; land last as a cross-cutting pass once
   every other screen's header is settled, so the global transparency requirement (§ Executive
   Summary) can be checked screen-by-screen in one sweep instead of piecemeal.
7. **`onboarding`** — blocked on the product decision about step-content reconciliation (§7).

## 5. Backbone-First Sub-Steps for Domain B (Runtime CDP + Instrumentation)

Before any of the four missing composables can be built, the following state-model work is needed
(none of it currently exists per `BrowserBridgeManager.kt` / `CdpClient.kt` inspection):

1. **Multi-target CDP session model** — `runtime-cdp-connected-dark.html` shows 4 simultaneously
   attached tabs, each with title/URL/request-count and per-tab expandable network activity
   (e.g. a `Fetch.paused` request shown inline). Needs a `List<CdpTarget>` (or similar) state shape,
   not a single connection-state value.
2. **Rule-enforcement counters** — Blocked/Modified/Redirected counts, split by request-stage vs
   response-stage, need their own counters exposed from wherever fetch interception already enforces
   rules.
3. **Live storage-stream events** — Cookies (with per-domain counts), `localStorage` (live key/value
   diffs, e.g. "auth.token set · 2s ago"), IndexedDB (per-DB store/record counts) all need a
   streamable event model, not a point-in-time snapshot.
4. **Service worker registration list** — scope + status (active/activated) per registered origin.
5. **Fingerprint evidence, per-signal** — the existing `HttpStackDetector`/`StackFingerprinter`
   histogram is aggregate-only (a single confidence % per candidate stack). The mockup requires
   each candidate to expose its **individual signal breakdown** (JA3 raw string + hash + match
   verdict, ALPN offers + match verdict, HTTP/2 SETTINGS values + match verdict, HPACK header
   ordering + match verdict, cache-validation shape + match verdict) as separately inspectable,
   drill-in evidence — this is a materially richer data shape than what exists today.
6. Only after 1–5 are designed should `RuntimeInstrumentationCard.kt`, `CdpCard.kt`,
   `WebViewCaptureControl.kt`, and `StackFingerprintPanel.kt` be built against them.

This mirrors the original `implementation-plan.md`'s Domain B write-up almost exactly — the
difference is this audit confirms with certainty (via `grep`/git-log, not inference) that none of
it ever shipped, so it must be re-scheduled rather than assumed done.

## 6. Global Requirement: Nav / Header Transparency

Applies to **every** screen touched in Phase A, not just `nav_header_pill`/`navigation` themselves:
the floating header and bottom nav pill must never sit on a shared filled bar/toolbar surface —
each capsule (or the single shared bottom-nav capsule, which is intentionally one glass surface
containing all tab slots) is independently glass-effected, and the page background must show
through fully in the gaps between capsules. Reference `nav_header_pill/` and `navigation/` mockups
when implementing or reviewing any screen's header. Any screen whose header currently renders as a
plain `Row` scrolling with page content (rather than a pinned, glass-treated capsule) is out of
compliance — confirmed true today for Home (see Phase B), not yet checked for every other screen in
this Phase A list; add that check to each screen's acceptance criteria.

## 7. Open Questions / Risks

1. **`gesturesEnabled` conflict (resolved by recommendation, not by omission):** `navigation_showcase_dark.html`
   mandates hardcoded-disabled side-swipe; current code and `DESIGN-SYSTEM.md` §10.4 keep it
   user-toggleable. Recommend keeping current behavior; flag explicitly in the PR/review that this
   diverges from the navigation mockup's stated constraint, so it isn't "silently" decided.
2. **`set-cookie` redaction (interceptr Response Viewer):** the mockup itself calls this "a genuine
   open question" — should sensitive headers be masked by default with a reveal action? No answer
   given. Needs a product decision before or during the interceptr work.
3. **Onboarding step-content mismatch:** mockup's VPN-permission/CA-install steps vs. shipped
   Identity/Theme/Diagnostics steps — needs reconciliation (replace, extend, or treat as two
   sequential onboarding flows) before implementation.
4. **Flow's All-Flows-picker / List-View↔Flow-View toggle:** not confirmed to exist under any name;
   needs a direct code read of `FlowScreen.kt`'s remaining ~1780 lines (only sampled so far) before
   scoping.
5. **Interceptr VPN-relay-row icon:** needs a small data-model check (does the connection/traffic
   row type already know "routed via requestr VPN" as a distinct flag?) before the icon can be
   wired — likely a one-line model addition, but must be confirmed, not assumed.
6. **`saved_apis` collections grouping:** needs confirmation of whether `ApiCollectionsRepository`
   already has a "collection" grouping concept or whether this requires a data-model change in
   addition to the UI change.
7. **Tools' binary-file Base64 input:** explicitly flagged by the mockup itself as unshown/undesigned
   — decide whether it's in scope for this pass or deferred.
8. **Music Import-MP3 discrepancy:** HTML mockup says removed; PNG reference still shows it. Confirm
   current shipped behavior and align to the HTML mockup (source of truth for Phase A) during
   implementation, not before.

All items in this document are **PENDING** — no implementation work has started under this audit.
