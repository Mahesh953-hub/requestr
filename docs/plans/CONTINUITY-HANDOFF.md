# Continuity Handoff — requestr Master Plan + Phased Design Implementation

> **Purpose:** This file is the single source of truth for the next agent (or a
> resumed instance of the same agent) to continue this task with **zero loss of
> context, plans, sub-agent briefings, or review feedback**. It was written by the
> main agent at the point where Phase B work was about to begin. Read it end to end
> before doing anything else.

---

## 0. TL;DR — where the previous agent left off

| Work item | Status |
|---|---|
| Task 1 — master plan completion | ✅ **COMPLETE and fully gated** |
| Task 2 — design-plan Phase A (apply existing mockups) | ✅ **COMPLETE and fully gated** |
| Task 3 — design-plan Phase B (rebrand + zero-coverage screens) + design-system app-wide | ⏳ **NOT STARTED** — prep only (see §5) |

### Percentages (best effort, honest)

- Phase 1 (master plan): **100%**
- Phase 2 (Phase A): **100%**
- Phase 3 (Phase B + design system): **~5%** (spec questions answered + settings contract landed; zero UI/motion/theme implementation)

**Overall: roughly 55–60% of total effort.**

### Critical facts for the resuming agent

1. **Four Phase B build sub-agents were spawned on 2026-09-01 and then CANCELLED
   before producing any code.** Their full task descriptions are preserved verbatim
   in §8 so they can be re-dispatched identically.
2. The only uncommitted work in the tree right now is the **Phase B glass-mode
   settings contract** (3 files, 66 insertions, compiles clean) — see §5.
3. The user has said **"don't start coding until I say"** — a resuming agent must
   not begin implementation until told to proceed, unless instructed otherwise.

---

## 1. Repository & branch

- Repo: `Mahesh953-hub/requestr` → checked out at `/code/Mahesh953-hub/requestr`
- Branch: **`vorflux/master-plan-and-phased-design-implementation`** (tracking, pushed to origin)
- Created from `main` at `a53c706`. **No PR exists yet.**

### Commit log (newest first)

```
71bdc23 fix: close the review findings — fail-closed crypto, honest lifecycle ownership, and a cURL importer that refuses instead of guessing
5545086 feat: design-plan Phase A — apply the existing mockups across Interceptr, Saved APIs, Tools, API Testr and Reqy Assistant
4cbb04e feat: complete the master plan — wire deferred items, add AES body crypto, finish the LOW polish tier
ef84075 fix(build): unblock release R8 by suppressing Rhino's java.beans references
a53c706 docs: add master-plan implementation status hand-off
94c105a Merge pull request #15 from Mahesh953-hub/vorflux/master-plan-remaining-buildout
```

### Current uncommitted changes (the ONLY uncommitted work)

```
 M app/src/main/java/com/example/data/models/AppModels.kt
 M app/src/main/java/com/example/data/prefs/UnifiedDataStore.kt
 M app/src/main/java/com/example/data/repository/AppRepositories.kt
```

66 insertions / 3 deletions across those three files. This is the **glass-mode
settings contract** described in §5. It compiles clean (`:app:compileDebugKotlin`
green). It has **not** been committed. Decide, per the user's next instruction,
whether to commit it before or after Phase B implementation.

---

## 2. The overall task (the user's verbatim scope — three sequential tasks)

Repository `Mahesh953-hub/requestr`. Work **phase by phase in this exact order**:
(1) master plan completion, (2) design-plan Phase 1 (called "Phase A" throughout),
(3) design-plan Phase 2 (called "Phase B") plus the design system app-wide. **Do not
skip ahead.**

- **TASK 1 — Finish the master plan.** Read
  `docs/plans/MASTER-PLAN-IMPLEMENTATION-STATUS.md` end to end and implement every
  remaining/unfinished item. For any item that genuinely requires real
  hardware/network capability a Redroid/emulator sandbox cannot provide (real VPN
  tunnel to live internet, real root device, real Frida/Shizuku host interaction,
  physical sensors), **do not fake it** — explicitly mark that item's status as
  **"not possible in emulator"** in the status doc with a short reason, and skip only
  that item. Implement everything else fully. No new/latest features until the
  master plan is fully green aside from emulator-blocked items.
- **TASK 2 — Implement the phased design plan.** In `docs/plans`, find the two-phase
  design plan and implement **Phase 1 completely, then Phase 2 completely, in
  order**. Every screen/flow/state must be implemented, wired up, and working, with
  no illogical errors, bugs, or visual/functional gaps. **Explicitly allowed and
  encouraged to rewrite any existing code that is broken, weak, or behaves oddly —
  do not patch around bad code, replace it.**
- **TASK 3 — Apply the design system app-wide (part of Phase 1/2).** Floating
  pill-shaped bottom nav bar with Liquid Glass blur + refraction + background
  parallax; user-selectable glass modes (Liquid Glass, Clear Glass, Tune Glass);
  tinted pill expands around the active tab's icon + text smoothly. Floating icons
  become locked into the header; greeting fades/scrolls away on scroll; stats pill
  compresses then slides out. Animations/physics: `screen_transitions`
  (lateral slide-and-fade), `scrolling_physics` (kinetic + elastic overscroll),
  `micro_interactions` (Material ripple), `glassmorphism` (real-time blur +
  refraction), `theme_switching` (instantaneous inversion, pastel ↔ deep olive).

### WORKFLOW REQUIREMENT — applies to EVERY phase/fix, no exceptions

After completing each phase (master-plan, Phase A, Phase B) **AND after any
subsequent fix**: (1) commit and push; (2) build **both a debug APK and a release
APK** (release must actually succeed, using the existing checked-in fallback signing
config if no override secrets are provided); (3) verify with a **real emulator smoke
test** (launch, navigate key flows, screenshot/recording evidence) before moving to
the next phase. At the end, produce a clear status report of what was implemented,
what was marked not-possible-in-emulator and why, and remaining known issues.

---

## 3. What the user decided directly (via `ask_user`) — DO NOT relitigate

These are product decisions, not technical guesses:

1. **Sensitive response headers → "Mask by default, with a per-value reveal."**
   `Set-Cookie`, `Cookie`, `Authorization`, `Proxy-Authorization` mask by default
   with a per-header tap-to-reveal eye. Reveal state is per-header, ephemeral, and
   must NOT persist across restarts or navigation. Copy actions must copy the REAL
   value (not the mask) with a code comment so nobody "fixes" it. Masked rendering
   must not leak length. (Already implemented this session.)
2. **Binary-file input for the Base64 tool → "Defer it and note it as an undesigned
   gap."** (Recorded for the PR description.)
3. **"Clear Glass" → rename the existing translucent mode.** Clear Glass is the
   existing `TRANSLUCENT` mode relabelled; it is NOT a new stochastic key. (See §5.)
4. **"Tune Glass" → user-tunable sliders.** Real controls for blur strength, tint
   opacity, border and shadow intensity. NOT content-adaptive, NOT a perf preset.
   (See §5.)

---

## 4. What is COMPLETE (in detail)

### Phase 1 — master plan completion → commit `4cbb04e`

- Deferred items wired, AES body crypto added, LOW polish tier finished.
- Only **VPN/tun tunnel establishment** was marked "not possible in emulator" (the
  `VpnService.establish()` call fails with `Cannot create interface` on Redroid; no
  VPN platform feature; `tun0` is never created; `/dev/net/tun` existing and the
  consent dialog appearing are non-signals).
- PROXY-tier capture genuinely works (HTTP + HTTPS-CONNECT, port 9000, traffic
  persisted and visible).
- Full gating done: commit+push, debug+release APK built, emulator smoke test PASSED.

### Phase 2 / Phase A — apply existing mockups → commits `5545086` + `71bdc23`

- Applied existing mockups across Interceptr, Saved APIs, Tools, API Testr, Reqy
  Assistant.
- `71bdc23` is the review-simplify-smoke-test follow-up commit. Its headline fixes:

  | Area | Fix |
  |---|---|
  | AES body crypto | **failed open → fails closed** (502 instead of forwarding plaintext on failure) |
  | API-key logging | raw logging wrote Gemini keys to logcat; now strips query+fragment; second leak in error string closed |
  | Workspace export | now round-trips crypto fields, node `flowId`/`createdAt`, async-polling fields, all flows + pipelines |
  | Capture lifecycle | Stop keyed by real owning engine (`CaptureLifecycleOwner`), not row attribution |
  | Tier truth | `CaptureMethod.engineTier()`/`.engineLifecycleOwner()` single source of truth (`ROOT_FRIDA` deliberately throws) |
  | cURL import | `-XPOST` no longer becomes GET; `-X TRACE` reported not downgraded; unknown flag arg no longer becomes URL (`VALUE_FLAGS` table wired) |
  | Image gallery | persists decoded bytes separately (gzip images were stored still-compressed and never displayed) |
  | CaTrustGate | docs rewritten honestly; now invalidates on app-initiated CA changes |
  | Jitter clamp | no-op clamp fixed (range narrowed before sampling) |
  | UI reachability | FORM/MULTIPART raw editor was ~900px off-screen; redirect badge invisible (clipped); Reqy screenshot attach always failed (missing `<cache-path>`) |

- Full gating done: commit+push, debug+release APK built, emulator smoke test PASSED.
- **Phase A review was run** (`pa-review`) — 1 review iteration consumed; all MUST-FIX
  items closed except **M4** (see §9 outstanding). One simplify pass (`pa-simplify`)
  was run.

### Verification totals (the numbers the next agent must not regress)

- `:app` unit tests: **1567** (was 1156 at branch point) — but see §9 `M4` which will
  add more.
- `:sdk`: 50. `:sdk-noop`: 6. **Total 1623, 0 failures.**
- Rust (`native/tun2proxy-bridge`): **34/34**.
- Browser extension: **10/10 groups, ALL CHECKS PASSED**.
- `:cli:build`: green. Debug + release APK both build; release signing verified
  against the locked identity (`alias='upload'`,
  SHA-256 `CB:08:8B:DE:A4:85:3C:D9:06:A2:CF:EF:4A:61:B4:FB:59:96:C6:75:78:A6:BE:DE:97:18:17:32:C5:DA:6E:D8`).

### Test reports published (do NOT lose the titles)

| Title | Status |
|---|---|
| `Master plan completion — on-device smoke test` | passed (report_id=1, version=1) |
| `Design-plan Phase A — on-device smoke test` | passed (report_id=1, version=1) |

To keep ONE report, re-submit with the exact same title; a different title starts a
separate report. Phase B has **no** report yet.

---

## 5. Phase B prep that is ALREADY LANDED (uncommitted, compiles clean)

The main agent resolved two spec ambiguities with the user, then landed the **shared
glass-mode settings contract** so the Phase B sub-agents would not collide on
`UnifiedDataStore.kt` / `AppModels.kt` / `AppRepositories.kt`. This is the only
uncommitted work right now.

### 5.1 `canonicalGlassStyle()` — six canonical values

`data/prefs/UnifiedDataStore.kt` now returns:

```
FROSTED_GLASS, SOLID_M3, TRANSLUCENT, OLED_BLACK, LIQUID_GLASS, TUNE_GLASS
```

- **`TRANSLUCENT` is "Clear Glass"** (relabelled). The legacy `CLEAR_GLASS →
  TRANSLUCENT` alias stays. Do NOT add a distinct `CLEAR_GLASS` canonical branch —
  that would silently repoint every install that stored the legacy `CLEAR_GLASS`
  string at a different-looking mode.
- `LIQUID_GLASS` = real blur + refraction of content passing underneath.
- `TUNE_GLASS` = same renderer, driven by the four user values below.

### 5.2 Four persisted Tune-Glass values on `UserSettingsState`

`data/models/AppModels.kt` gained (with clamped setters already written on the
settings repository in `data/repository/AppRepositories.kt`):

| Field | Range (setter clamps) | Default | Setter |
|---|---|---|---|
| `glassTuneBlurDp` | 0..48 | 24 | `updateGlassTuneBlur` |
| `glassTuneTintOpacity` | 0.15..1 | 0.90 | `updateGlassTuneTintOpacity` |
| `glassTuneBorderOpacity` | 0..1 | 0.35 | `updateGlassTuneBorderOpacity` |
| `glassTuneShadowDp` | 0..24 | 6 | `updateGlassTuneShadowDp` |

Defaults deliberately match `FROSTED_GLASS` so first selecting Tune Glass changes
nothing on screen (user tunes outward from familiar). **Only `TUNE_GLASS` reads these
four values.** The persistence keys `GLASS_TUNE_BLUR_DP`, `GLASS_TUNE_TINT_OPACITY`,
`GLASS_TUNE_BORDER_OPACITY`, `GLASS_TUNE_SHADOW_DP` were added to
`UnifiedDataStore.kt` and wired into the bulk persist path.

### 5.3 Blur tiering (already decided)

Real blur is API 31+. On API 24–30 fall back to the existing gradient-and-hairline
treatment. Liquid Glass and Tune Glass remain SELECTABLE on old devices and must look
deliberate there, just without true refraction. Do NOT hide them, do NOT fake
refraction with a static image.

### 5.4 Greeting emoji

Home greeting emoji is **🍃** (leaf), not 🌙 and not ☀️.

### 5.5 Compose Foundation trap (verified by unzipping the AAR)

Foundation is **1.7.2**. `rememberOverscrollEffect` **does not exist** in 1.7.2
(1.8+ API). Use `LocalOverscrollConfiguration` / `OverscrollConfiguration`. Do NOT
bump the Compose BOM to "fix" a missing-API error.

---

## 6. The plan documents (authoritative, checked in)

- `docs/plans/MASTER-PLAN-IMPLEMENTATION-STATUS.md` (master plan status — Task 1 source)
- `docs/plans/PHASE-A-APPLY-EXISTING-MOCKUPS.md` (Phase A spec)
- `docs/plans/PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` (Phase B spec)
- `docs/plans/CONTINUITY-HANDOFF.md` (this file)
- `docs/DESIGN-SYSTEM.md` (design contract — **§14.7 currently documents "no real
  blur" and MUST be updated when glass lands**)
- `docs/designs/` — **62 mockups (54 HTML + 8 PNG)** — the acceptance reference.

### The agent's working dossiers (NOT in git — `/code/.plans/` — read them)

- `/code/.plans/dossiers/master-plan-remaining.md` — Task 1 recon (⚠ contains 3
  INVENTED file names: `TransactionDetailScreen.kt`, `TransactionListScreen.kt`,
  `AppPickerScreen.kt`; real equivalents are `CapturedDetailBottomSheet` in
  `InterceptScreen.kt`, the traffic list in `InterceptScreen.kt` +
  `CapturedTrafficHelpers.kt`, and app-picker logic in `TrafficInterceptorRepository.kt`)
- `/code/.plans/dossiers/phase-a-verification.md` — Phase A recon; §4 has the
  per-screen header-compliance table (HomeScreen = NON-COMPLIANT, 7 others NEEDS
  VERIFICATION). NOTE: its claim that Domain B (Runtime CDP + Instrumentation) was
  never implemented is STALE — Domain B landed in `d1b4476` via PR #13.
- `/code/.plans/dossiers/phase-b-implementation.md` — Phase B implementation plan
  (Part 1 Home rebrand, Part 2 zero-coverage screens, Part 3 navigation architecture)
- `/code/.plans/dossiers/design-system-current-state.md` — current-state + "Delta
  Spec — Target Design System" (the design-system engineering spec)

### Shared-context briefings the agent wrote for sub-agents (NOT in git)

- `/code/.plans/PHASE1-SHARED-CONTEXT.md`
- `/code/.plans/PHASE-A-SHARED-CONTEXT.md`
- `/code/.plans/PHASE-B-SHARED-CONTEXT.md` — **= 342 lines, includes an ADDENDUM with
  all of §5's locked decisions.** A resuming agent should re-dispatch using this file
  plus the verbatim task descriptions in §8.

---

## 7. The Phase B plan (wave structure the agent intended)

Phase B = design-plan Phase 2 (rebrand + zero-coverage screens) **plus** Task 3
(design-system app-wide). Dependency-driven, two waves:

- **Wave 1 — design-system foundation** (the primitives Home's header capsules depend on):
  create `BlurModifier.kt`, `ParallaxState.kt`, `ParallaxBackground.kt`,
  `StatsCompressiblePill.kt`; modify `CollapsibleNavPill.kt`, `CommonComponents.kt`,
  `Theme.kt`, `Color.kt`, `SettingsScreen.kt` (appearance/local-API/plugin blocks,
  glass pickers, Tune Glass sliders), `MainActivity.kt`. Also the deferred
  per-screen header-compliance sweep (dossier §4).
- **Wave 2 — Home rebrand + zero-coverage screens**: per
  `docs/plans/PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` and
  `/code/.plans/dossiers/phase-b-implementation.md`. Home header moves from a plain
  `Row` at `HomeScreen.kt:148-191` inside the `LazyColumn` to pinned floating glass
  capsules (hamburger; identity capsule with live status dot; separate theme + gear
  capsules; header-only Material You dynamic colour, API 31+, static 9-theme
  fallback; 5-step text-swap animation). Search placeholder → "Search APIs, notes,
  chats, tools...". Quick-action pills 4 (spec) vs 5-6 (code). Zero-coverage screens:
  Local REST API & CLI settings block, Plugin Manager block, Appearance UI,
  `NotesScreen.kt`, `ToolWorkspaceScreen.kt`, `DiffView.kt`, `CaptureController.kt`
  overlay, `AppNavigationDrawer.kt` contents. Complete old-UI removal per screen.

### Mockup file mapping (each agent must open its own, Phase A skipped this)

- Nav pill + header: `docs/designs/nav_header_pill/nav_header_pill_editorial_light.html`,
  `docs/designs/nav_header_pill/nav_header_pill_obsidian_dark.html`
- Navigation showcase: `docs/designs/navigation/navigation_showcase_dark.html`,
  `docs/designs/navigation/navigation_showcase_light.html`
- Home rebrand: `docs/designs/home/home_rebranding.md`,
  `docs/designs/home/home-section_prototype.png`,
  `docs/designs/home/home-section_prototype_detailed.png`
- Other subfolders in `docs/designs/`: `ai-chat`, `api-testr`, `branding`, `flow`,
  `interceptr`, `music`, `onboarding`, `saved_apis`, `tools`; root-level 36 files are
  `http3-*`, `relay-health-*`, `runtime-cdp-*`, `runtime-instrumentation-*`,
  `tls-*` detail/row mockups.

### File-ownership lanes (so concurrent agents never collide)

The four Phase B agents were assigned **exclusive, disjoint file ownership**:

| Agent | Owns exclusively |
|---|---|
| `ds-a-glass` | `BlurModifier.kt`(new), `StatsCompressiblePill.kt`(new), `CollapsibleNavPill.kt`, `CommonComponents.kt`, `docs/DESIGN-SYSTEM.md` |
| `ds-b-motion` | `ParallaxState.kt`(new), `ParallaxBackground.kt`(new), `MainActivity.kt` |
| `ds-c-theme-settings` | `Theme.kt`, `Color.kt`, `SettingsScreen.kt` |
| `ds-d-screens` | `NotesScreen.kt`, `ToolWorkspaceScreen.kt`, `DiffView.kt`, `AppNavigationDrawer.kt`, `CaptureController.kt`, + 2 strictly-scoped deprecation edits in `ChatScreen.kt`/`ApiTestrScreen.kt` |

`UnityDataStore.kt` / `AppModels.kt` / `AppRepositories.kt` had their Phase-B changes
pre-landed by the main agent (§5) precisely so no agent touches them.

---

## 8. The four Phase B sub-agent task descriptions — VERBATIM

These are the exact `add_task` payloads the previous main agent dispatched on
2026-09-01. **Re-dispatch them identically** (same `task_id`, same `description`,
same `instructions`=empty unless noted, `agent_type=build`) when resuming. Nothing
here is to be paraphrased away — the file-ownership lanes and "do not commit" rules
are load-bearing for concurrent safety.

### 8.1 `ds-a-glass` — glass rendering + floating header

> You are implementing the GLASS RENDERING + FLOATING HEADER half of a design system
> for the Android app `requestr`, repo `Mahesh953-hub/requestr`, checked out and ready
> at `/code/Mahesh953-hub/requestr`.
>
> Branch `vorflux/master-plan-and-phased-design-implementation` is checked out. There
> are already-landed uncommitted changes in the working tree from the main agent (a
> glass-mode settings contract). Leave them alone; build on them. Do NOT commit, do NOT
> push, do NOT create branches. The main agent commits.
>
> ### FIRST: read these, they are mandatory and they will save you from real traps
>
> 1. `/code/.plans/PHASE-B-SHARED-CONTEXT.md` — read the whole thing, especially the
>    ADDENDUM at the end which contains decisions locked with the user.
> 2. `/code/.plans/dossiers/design-system-current-state.md` — 1609 lines of verified
>    current-state analysis plus a "Delta Spec — Target Design System" section. This is
>    your primary technical reference.
> 3. `docs/DESIGN-SYSTEM.md` — the existing checked-in contract. You MUST update §14.7,
>    which currently documents "no real blur" as the intended behaviour. That stops
>    being true because of your work.
> 4. The mockups you are implementing against — actually open and read them:
>    - `docs/designs/nav_header_pill/nav_header_pill_editorial_light.html`
>    - `docs/designs/nav_header_pill/nav_header_pill_obsidian_dark.html`
>    - `docs/designs/navigation/navigation_showcase_dark.html`
>    - `docs/designs/navigation/navigation_showcase_light.html`
>
> Phase A shipped without anyone visually diffing against the mockups. Do not repeat
> that. The mockups are the acceptance reference.
>
> ### YOUR FILES — you own these exclusively, touch NOTHING else
>
> Three sibling agents are working in the same checkout concurrently on different
> files. Staying inside your lane is not optional; editing outside it will corrupt
> their work.
>
> - CREATE `app/src/main/java/com/example/ui/components/BlurModifier.kt`
> - CREATE `app/src/main/java/com/example/ui/components/StatsCompressiblePill.kt`
> - MODIFY `app/src/main/java/com/example/ui/components/CollapsibleNavPill.kt`
> - MODIFY `app/src/main/java/com/example/ui/components/CommonComponents.kt`
> - MODIFY `docs/DESIGN-SYSTEM.md` (§14.7 and the glass-mode list only)
> - CREATE test files under `app/src/test/java/...` for your own code
>
> Explicitly NOT yours: `MainActivity.kt`, `Theme.kt`, `Color.kt`, `SettingsScreen.kt`,
> `HomeScreen.kt`, `AppNavigationDrawer.kt`, `NotesScreen.kt`, `UnifiedDataStore.kt`,
> `AppModels.kt`, `AppRepositories.kt`.
>
> ### WHAT TO BUILD
>
> #### 1. Tiered blur primitive (`BlurModifier.kt`)
>
> A reusable modifier/helper giving real blur on API 31+ via `Modifier.blur` /
> `RenderEffect`, and falling back on API 24–30 to the gradient-and-hairline treatment
> that already ships. The app's `minSdk` is 24 and `compileSdk` 36.
>
> Requirements: the fallback must look deliberate, not broken. A user on Android 11
> should see a coherent design, not an obviously-missing effect. Expose the tier so
> callers can adapt, and so tests can assert behaviour per tier. Do not fake
> refraction with a static image on the fallback tier.
>
> #### 2. Refraction for Liquid Glass
>
> Real-time blur PLUS refraction of the background content passing underneath floating
> elements (the nav bar and the stats pill). Implement refraction as blur combined
> with a slight offset/scale distortion sampled from the content behind — API 31+ only.
> An AGSL `RuntimeShader` version is explicitly OUT of scope and deferred; do not
> attempt it.
>
> The visible goal: as content scrolls underneath the floating nav bar, you can see it
> distort through the glass. If you cannot see that on screen, it is not done.
>
> #### 3. Six glass modes in the renderers
>
> The canonical values are `FROSTED_GLASS`, `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`,
> `LIQUID_GLASS`, `TUNE_GLASS`. `canonicalGlassStyle()` in
> `data/prefs/UnifiedDataStore.kt` already returns all six — read it.
>
> - `TRANSLUCENT` is what the user calls "Clear Glass". Same key, new label. Do not add
>   a `CLEAR_GLASS` canonical branch; that would silently repoint existing installs.
> - `TUNE_GLASS` renders like Liquid Glass but sources its numbers from settings:
>   `settings.glassTuneBlurDp`, `glassTuneTintOpacity`, `glassTuneBorderOpacity`,
>   `glassTuneShadowDp`. These already exist on `UserSettingsState`. Only `TUNE_GLASS`
>   may read them.
>
> There are currently THREE separate `when (blurStyle)` brush blocks — two in
> `CommonComponents.kt` (around lines 1134 and 1235) and one in `CollapsibleNavPill.kt`
> (around line 131) — plus matching border-colour blocks. They are near-duplicates.
> Adding two modes to three duplicated blocks means six new branches that can drift.
> Consolidate them into ONE shared source of truth that all three call sites use. A
> sibling agent is NOT touching these blocks, so this consolidation is safe.
>
> #### 4. Nav bar: floating pill with an expanding tinted active tab
>
> The nav bar must be a floating pill-shaped bar anchored to the bottom edge, with
> Liquid Glass blur refracting what passes underneath. Nav has 6 tabs (Home, Intercept,
> APIs, Tools, Flow, Music) defined around `CollapsibleNavPill.kt:76-83`.
>
> The key interaction: on selection, a tinted pill shape **expands smoothly around the
> active tab's icon AND its text label**. Animate the expansion — it must not snap.
> Existing `NavPillTab` active tint is alpha `0.16f`; the delta spec raises it to
> `0.20f` with an accompanying shadow animation.
>
> Note for your own on-device sanity: the active tab renders wider than the others,
> which shifts every other tab's x-bounds. That is expected, and it is exactly why this
> must be animated rather than a hard layout jump.
>
> #### 5. Headers: floating icons become locked into the header
>
> Currently-floating icons must become locked/attached as part of the header itself.
> Then, driven by scroll:
> - the greeting text fades and scrolls away as the user scrolls down;
> - the stats pill subtly compresses, then slides out of view at deeper scroll — a
>   two-stage response to scroll depth, not a single binary hide.
>
> `StatsCompressiblePill.kt` is where the compressing stats pill lives. The header work
> is in `CommonComponents.kt` (`FloatingCapsuleHeader` / `DynamicGlassHeader`).
>
> Existing frozen header tokens you must respect: top padding
> `(6 + settings.headerTopPaddingDp).dp`; capsule radius `RoundedCornerShape(18.dp)`;
> title capsule padding `horizontal 14.dp, vertical 8.dp`; title text `14.5.sp` Bold
> lowercase, ellipsis, max 1 line; wordmark variant when the title is "requestr"
> (ignoreCase); status dot `6.dp`; shadow elevation `6.dp` with ambient `primary@0.08`
> and spot `Black@0.12`; border `0.8.dp`.
>
> Your header must accept a scroll offset from the caller rather than reaching for a
> global — a sibling agent owns the screen-level scroll/parallax plumbing and
> `HomeScreen` is owned by yet another agent. Design the API so they can drive it, and
> write down the exact signature you settled on in your final report so the main agent
> can relay it.
>
> #### 6. Deferred per-screen header-compliance sweep
>
> `/code/.plans/dossiers/phase-a-verification.md` §4 has a per-screen header-compliance
> table: `HomeScreen` is confirmed NON-COMPLIANT and 7 other screens are marked NEEDS
> VERIFICATION. Verify those 7 and bring them into compliance **only where doing so does
> not require editing a file owned by another agent**. For any screen you cannot touch
> because of ownership, report precisely what is non-compliant and what the fix is, so
> the main agent can route it. Do not silently skip them.
>
> ### HARD CONSTRAINTS
>
> - **Compose Foundation is 1.7.2.** `rememberOverscrollEffect` does NOT exist in it
>   (1.8+ API) — verified by unzipping the actual AAR. If you hit a missing-API compile
>   error, do NOT bump the Compose BOM; find the 1.7.2-era API.
> - **Concurrency**: sibling agents are building in this same tree. You may run
>   `./gradlew :app:compileDebugKotlin --no-daemon` and `--tests`-filtered test runs.
>   You may NOT run `assembleDebug`, `assembleRelease`, `build`, `clean`, or an
>   unfiltered `testDebugUnitTest`, and you may NOT touch adb, the emulator or scrcpy.
>   If you see a bizarre unresolved-reference error in a file you never touched, it is
>   probably a sibling mid-write — re-run before believing it. If KSP caches corrupt,
>   clear `app/build/kspCaches/debug`.
> - A `| tail` pipe HIDES a real `BUILD FAILED`. Always check the exit status directly.
> - Environment: every Gradle command needs
>   `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env && ./gradlew ... --no-daemon`.
>   Non-interactive shells do not source `.bashrc`.
> - You are explicitly ALLOWED AND ENCOURAGED to rewrite existing code that is broken,
>   weak or behaves oddly. Do not patch around bad code — replace it. This is a direct
>   instruction from the user.
>
> ### DEFINITION OF DONE
>
> Every mode, state and interaction actually implemented and wired up, with no visual
> or functional gaps, and no illogical behaviour. Unit tests for the logic you can test
> on the JVM (tier selection, mode-to-token resolution, compression maths, Tune Glass
> value plumbing). `:app:compileDebugKotlin` green. `docs/DESIGN-SYSTEM.md` §14.7
> updated so it no longer claims there is no real blur.
>
> Report back: what you built, the exact API signature other agents must use to drive
> your header scroll behaviour, the header-compliance findings for screens you could not
> touch, and anything you deliberately did not do and why.

### 8.2 `ds-b-motion` — motion, physics, parallax

> You are implementing the ANIMATION, PHYSICS AND PARALLAX half of a design system for
> the Android app `requestr`, repo `Mahesh953-hub/requestr`, checked out and ready at
> `/code/Mahesh953-hub/requestr`.
>
> Branch `vorflux/master-plan-and-phased-design-implementation` is checked out. There
> are already-landed uncommitted changes in the working tree from the main agent (a
> glass-mode settings contract). Leave them alone. Do NOT commit, do NOT push, do NOT
> create branches. The main agent commits.
>
> ### FIRST: read these, they are mandatory
>
> 1. `/code/.plans/PHASE-B-SHARED-CONTEXT.md` — the whole thing, especially the
>    ADDENDUM at the end with decisions locked with the user.
> 2. `/code/.plans/dossiers/design-system-current-state.md` — verified current-state
>    analysis plus a "Delta Spec — Target Design System" section. Your primary
>    technical reference.
> 3. `/code/.plans/dossiers/phase-b-implementation.md` Part 3, which covers navigation
>    architecture.
> 4. `docs/DESIGN-SYSTEM.md` — the existing checked-in contract.
> 5. The mockups: `docs/designs/navigation/navigation_showcase_dark.html` and
>    `docs/designs/navigation/navigation_showcase_light.html`. Actually open them —
>    Phase A shipped without anyone diffing against the mockups and that will not
>    repeat.
>
> ### YOUR FILES — you own these exclusively, touch NOTHING else
>
> Three sibling agents are working in this same checkout concurrently on different
> files. Editing outside your lane will corrupt their work.
>
> - CREATE `app/src/main/java/com/example/ui/components/ParallaxState.kt`
> - CREATE `app/src/main/java/com/example/ui/components/ParallaxBackground.kt`
> - MODIFY `app/src/main/java/com/example/MainActivity.kt`
> - CREATE test files under `app/src/test/java/...` for your own code
>
> Explicitly NOT yours: `CommonComponents.kt`, `CollapsibleNavPill.kt`,
> `BlurModifier.kt`, `StatsCompressiblePill.kt`, `Theme.kt`, `Color.kt`,
> `SettingsScreen.kt`, `HomeScreen.kt`, `AppNavigationDrawer.kt`, `NotesScreen.kt`,
> `UnifiedDataStore.kt`, `AppModels.kt`, `AppRepositories.kt`.
>
> If you need a change inside another agent's file to finish your work, do NOT make it.
> Report exactly what you need and where, and the main agent will route it.
>
> ### WHAT TO BUILD
>
> #### 1. `screen_transitions` — lateral slide-and-fade between bottom tabs
>
> Switching bottom-nav tabs must animate as a lateral slide combined with a fade, so
> the user retains spatial awareness of where they moved. Direction must reflect the
> actual movement: moving right along the tab bar slides one way, moving left slides
> the other. A transition that always slides the same direction destroys the spatial
> cue and is not done.
>
> Nav has 6 tabs (Home, Intercept, APIs, Tools, Flow, Music). There is an 11-route
> screen enum around `MainActivity.kt:291-309`, and `CollapsibleNavPill` is hosted
> around `MainActivity.kt:430-445`. Note the routes outnumber the tabs — decide
> deliberately how non-tab routes transition, and say what you decided and why.
>
> #### 2. `scrolling_physics` — kinetic scrolling with elastic overscroll
>
> Lists must scroll kinetically with an elastic overscroll bounce at BOTH the top and
> the bottom.
>
> **Critical API trap, verified empirically by unzipping the actual AAR:** Compose
> Foundation here is **1.7.2**. You must use `LocalOverscrollConfiguration` /
> `OverscrollConfiguration`. **`rememberOverscrollEffect` does NOT exist in 1.7.2** —
> it is a 1.8+ API and referencing it will not compile. Do NOT resolve a missing-API
> error by bumping the Compose BOM; that would ripple across the whole app.
> `OverscrollConfiguration` is deprecated-but-present; use it and note that in a
> comment.
>
> This must apply app-wide, not just on one screen. Since you cannot edit most screen
> files, implement it as something provided once at the top level (a `CompositionLocal`
> provided in `MainActivity`) so screens inherit it without being individually edited.
> If any screen overrides it locally in a way that defeats this, report the file and
> line rather than editing it.
>
> #### 3. `glassmorphism` background parallax
>
> A background parallax layer that moves subtly and independently of the foreground
> content, acting as the base layer that the glassmorphism refraction has something to
> refract. Without this layer the glass has nothing interesting behind it and the
> whole effect reads as flat grey.
>
> - `ParallaxState.kt` holds the state and exposes it via a `CompositionLocal` so
>   screens and the glass components can read the current parallax offset without
>   being individually rewired.
> - `ParallaxBackground.kt` is the composable drawing the layer.
> - "Subtle" is the operative word: this is a base layer, not a feature. If it draws
>   attention to itself it is wrong.
>
> A sibling agent is building the glass renderer that consumes this. Design the
> `CompositionLocal` API cleanly and state its exact signature in your final report so
> the main agent can relay it.
>
> #### 4. `micro_interactions` — standard Material ripple
>
> Standard Material ripple on cards, buttons and list items. Use the platform ripple
> rather than a bespoke one. Where you find `clickable` without an indication, or an
> explicit `indication = null` that suppresses ripple on something that should have it,
> fix it in files you own and report the ones you do not own.
>
> #### 5. `theme_switching` — instantaneous, seamless
>
> Light-to-dark switching must be an instantaneous colour inversion with no crossfade,
> no flicker, no white flash, and no visible relayout. A sibling agent owns `Theme.kt`
> and the new palettes; you own the `MainActivity` side of it. Make sure nothing at the
> activity level (window background, `enableEdgeToEdge`, system-bar appearance, or a
> recomposition boundary) causes a flash on switch. If you find a flash caused by
> something in `Theme.kt`, report it — do not edit that file.
>
> ### HARD CONSTRAINTS
>
> - **Concurrency**: sibling agents are building in this same tree. You may run
>   `./gradlew :app:compileDebugKotlin --no-daemon` and `--tests`-filtered test runs.
>   You may NOT run `assembleDebug`, `assembleRelease`, `build`, `clean`, or an
>   unfiltered `testDebugUnitTest`, and you may NOT touch adb, the emulator or scrcpy.
>   A bizarre unresolved-reference error in a file you never touched is probably a
>   sibling mid-write — re-run before believing it. If KSP caches corrupt, clear
>   `app/build/kspCaches/debug`.
> - A `| tail` pipe HIDES a real `BUILD FAILED`. Always check the exit status directly.
> - Environment: `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env &&
>   ./gradlew ... --no-daemon`. Non-interactive shells do not source `.bashrc`.
> - You are explicitly ALLOWED AND ENCOURAGED to rewrite existing code that is broken,
>   weak or behaves oddly. Do not patch around bad code — replace it. Direct instruction
>   from the user.
>
> ### DEFINITION OF DONE
>
> All five motion behaviours implemented and actually wired up, no gaps, no illogical
> behaviour. Unit tests for what is JVM-testable (transition direction resolution from
> a tab-index pair, parallax offset maths, state reducers). `:app:compileDebugKotlin`
> green.
>
> Report back: what you built, the exact `CompositionLocal` signatures sibling agents
> must use, every change you need in a file you do not own (file + line + what change),
> and anything you deliberately did not do and why.

### 8.3 `ds-c-theme-settings` — theming + appearance/settings blocks

> You are implementing the THEMING + APPEARANCE SETTINGS half of a design system for
> the Android app `requestr`, repo `Mahesh953-hub/requestr`, checked out and ready at
> `/code/Mahesh953-hub/requestr`.
>
> Branch `vorflux/master-plan-and-phased-design-implementation` is checked out. There
> are already-landed uncommitted changes in the working tree from the main agent (a
> glass-mode settings contract you will build on). Leave them alone. Do NOT commit, do
> NOT push, do NOT create branches. The main agent commits.
>
> ### FIRST: read these, they are mandatory
>
> 1. `/code/.plans/PHASE-B-SHARED-CONTEXT.md` — the whole thing, especially the
>    ADDENDUM at the end. It contains decisions locked directly with the user which you
>    must not relitigate.
> 2. `/code/.plans/dossiers/design-system-current-state.md` — verified current-state
>    analysis plus a "Delta Spec — Target Design System" section. Your primary
>    technical reference.
> 3. `/code/.plans/dossiers/phase-b-implementation.md` Part 2, which covers the
>    zero-coverage screens including the Appearance settings UI, the Local REST API &
>    CLI settings block and the Plugin Manager block — all three are YOURS.
> 4. `docs/plans/PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` — the checked-in plan document.
> 5. `docs/DESIGN-SYSTEM.md`.
> 6. Mockups — actually open them:
>    `docs/designs/nav_header_pill/nav_header_pill_editorial_light.html` and
>    `nav_header_pill_obsidian_dark.html`. Phase A shipped without anyone visually
>    diffing against the mockups; that will not repeat.
>
> ### YOUR FILES — you own these exclusively, touch NOTHING else
>
> Three sibling agents are working in this same checkout concurrently. Editing outside
> your lane will corrupt their work.
>
> - MODIFY `app/src/main/java/com/example/ui/theme/Theme.kt`
> - MODIFY `app/src/main/java/com/example/ui/theme/Color.kt`
> - MODIFY `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
> - CREATE test files under `app/src/test/java/...` for your own code
>
> Explicitly NOT yours: `MainActivity.kt`, `CommonComponents.kt`,
> `CollapsibleNavPill.kt`, `BlurModifier.kt`, `HomeScreen.kt`,
> `AppNavigationDrawer.kt`, `NotesScreen.kt`, `ToolWorkspaceScreen.kt`,
> `UnifiedDataStore.kt`, `AppModels.kt`, `AppRepositories.kt`.
>
> If you need a change in another agent's file, do NOT make it — report the file, the
> line and the change needed. The main agent will route it.
>
> ### THE SETTINGS CONTRACT IS ALREADY LANDED — build on it, do not redefine it
>
> The main agent has already written and compiled these, specifically so you do not
> have to touch `UnifiedDataStore.kt` / `AppModels.kt` / `AppRepositories.kt`:
>
> - `canonicalGlassStyle()` in `data/prefs/UnifiedDataStore.kt` now returns six
>   canonical values: `FROSTED_GLASS`, `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`,
>   `LIQUID_GLASS`, `TUNE_GLASS`.
> - Four persisted Tune Glass values on `UserSettingsState` with clamping setters
>   already written on the settings repository: `glassTuneBlurDp` (0..48, default 24)
>   via `updateGlassTuneBlur`; `glassTuneTintOpacity` (0.15..1, default 0.90) via
>   `updateGlassTuneTintOpacity`; `glassTuneBorderOpacity` (0..1, default 0.35) via
>   `updateGlassTuneBorderOpacity`; `glassTuneShadowDp` (0..24, default 6) via
>   `updateGlassTuneShadowDp`.
>
> Read those files to confirm the exact names before using them.
>
> ### WHAT TO BUILD
>
> #### 1. Two new theme modes
>
> `AppThemeMode` (`Theme.kt:10`) currently has 7 entries, each with a `ColorScheme`
> resolved in a `when` around `Theme.kt:228-234`. Add two more, per the delta spec:
>
> - **Pastel** — background `0xFFFAFBF7`, primary `0xFF9B8B7E`
> - **Deep Olive** — background `0xFF0D0F09`, primary `0xFF8FA880`
>
> These are the two poles of the user's requested "pastel to deep olive" inversion.
> Build complete, coherent `ColorScheme`s — not just two colours with the rest
> inherited from an unrelated theme. Every role (surface, surfaceVariant, onSurface,
> outline, error, and so on) needs a deliberate value that actually reads well and
> passes sane contrast for body text. A theme where text is unreadable on some surface
> is not done.
>
> #### 2. Instantaneous, seamless theme switching
>
> Light-to-dark must be an instantaneous colour inversion: no crossfade, no flicker, no
> white flash, no visible relayout. Verify nothing in `Theme.kt` introduces an animated
> colour transition or a recomposition boundary that flashes. A sibling agent owns the
> `MainActivity` side; if you find the flash originates there, report it rather than
> editing.
>
> #### 3. Glass-mode pickers — relabel and extend
>
> There are glass-style pickers in `SettingsScreen.kt` around lines 3309-3312 and 3396.
> They currently offer four modes. They must offer six:
>
> - `FROSTED_GLASS` → "Frosted Glass"
> - `SOLID_M3` → "Material 3"
> - `TRANSLUCENT` → **"Clear Glass"** (this is a RELABEL — the user chose to rename the
>   existing translucent mode rather than add a new key. Do NOT introduce a
>   `CLEAR_GLASS` canonical value; the legacy `CLEAR_GLASS → TRANSLUCENT` alias must
>   keep working, otherwise every install that already stored that legacy string
>   silently jumps to a different-looking mode.)
> - `OLED_BLACK` → "OLED Black"
> - `LIQUID_GLASS` → "Liquid Glass"
> - `TUNE_GLASS` → "Tune Glass"
>
> There appear to be two near-duplicate picker blocks (one for the header style, one for
> the nav style). Do not add two modes to two duplicated lists that can then drift —
> consolidate to one source of truth for the label mapping.
>
> #### 4. Tune Glass sliders
>
> The user chose Tune Glass to mean **user-tunable sliders**. Build four real controls —
> blur strength, tint opacity, border intensity, shadow intensity — wired to the four
> setters above.
>
> Show them **only when Tune Glass is the selected style**. Sliders that are visible but
> do nothing because another mode is active are worse than no sliders at all. Give the
> user live feedback so they can see what they are tuning as they drag.
>
> Also handle the honest edge: on API 24–30 there is no real blur (a sibling agent
> implements a gradient fallback), so the blur-strength slider cannot do what it says on
> those devices. Surface that honestly rather than presenting a dead control as working.
>
> #### 5. Appearance settings UI — currently ZERO design coverage
>
> Per the Phase B plan this whole settings area has no design coverage and needs
> building out properly, not just having sliders bolted on. Bring it up to the same
> standard as the rest of the app: coherent grouping, section headers, real descriptions
> of what each control does. Read Part 2 of the phase-b-implementation dossier for the
> specifics.
>
> #### 6. Local REST API & CLI settings block — ZERO design coverage
>
> Build this out per the dossier. The app embeds a local API server and ships a
> companion CLI; this settings block is how the user controls and discovers it. There is
> existing pairing-token machinery in `UnifiedDataStore.kt` (`PAIRING_TOKEN`,
> `SHOW_PAIRING_TOKEN`, `PAIRING_TOKEN_REVEAL_UNTIL`, with a documented 5-minute
> auto-disable of the reveal) — read it, wire the UI to what actually exists, and do not
> invent capabilities the backend does not have.
>
> #### 7. Plugin Manager block — ZERO design coverage
>
> Build this out per the dossier. Same rule: wire it to what actually exists in the
> codebase. If the backing capability is absent, an honestly-disabled control with a
> reason beats a control that pretends to work. This codebase has an established
> precedent for that pattern.
>
> ### HARD CONSTRAINTS
>
> - **Compose Foundation is 1.7.2.** `rememberOverscrollEffect` does NOT exist (1.8+
>   API), verified by unzipping the actual AAR. If you hit a missing-API compile error,
>   do NOT bump the Compose BOM.
> - Existing theme detail worth knowing: there is a second, independent `AppAccent` axis
>   (`Theme.kt:37`) separate from `AppThemeMode`. Understand how the two compose before
>   adding modes, and do not break the accent engine.
> - **Concurrency**: sibling agents are building in this same tree. You may run
>   `./gradlew :app:compileDebugKotlin --no-daemon` and `--tests`-filtered test runs.
>   You may NOT run `assembleDebug`, `assembleRelease`, `build`, `clean`, or an
>   unfiltered `testDebugUnitTest`, and you may NOT touch adb, the emulator or scrcpy. A
>   bizarre unresolved-reference error in a file you never touched is probably a
>   sibling mid-write — re-run before believing it. If KSP caches corrupt, clear
>   `app/build/kspCaches/debug`.
> - A `| tail` pipe HIDES a real `BUILD FAILED`. Always check the exit status directly.
> - Environment: `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env &&
>   ./gradlew ... --no-daemon`.
> - `SettingsScreen.kt` is large. Read the region you are changing before changing it.
> - You are explicitly ALLOWED AND ENCOURAGED to rewrite existing code that is broken,
>   weak or behaves oddly. Do not patch around bad code — replace it. Direct instruction
>   from the user.
>
> ### DEFINITION OF DONE
>
> Nine themes all rendering coherently and readably, six glass modes selectable, Tune
> Glass sliders live and conditional, and all three zero-coverage settings blocks
> actually built and wired. No gaps, no dead controls presented as working. Unit tests
> for what is JVM-testable (label mapping, canonicalisation round-trips, clamping,
> contrast sanity of the new schemes). `:app:compileDebugKotlin` green.
>
> Report back: what you built, every change you need in a file you do not own (file +
> line + change), anything you found already broken, and anything you deliberately did
> not do and why.

### 8.4 `ds-d-screens` — zero-design-coverage screens

> You are implementing the ZERO-DESIGN-COVERAGE SCREENS for the Android app `requestr`,
> repo `Mahesh953-hub/requestr`, checked out and ready at `/code/Mahesh953-hub/requestr`.
>
> Branch `vorflux/master-plan-and-phased-design-implementation` is checked out. There
> are already-landed uncommitted changes in the working tree from the main agent. Leave
> them alone. Do NOT commit, do NOT push, do NOT create branches. The main agent commits.
>
> ### FIRST: read these, they are mandatory
>
> 1. `/code/.plans/PHASE-B-SHARED-CONTEXT.md` — the whole thing, especially the
>    ADDENDUM at the end with decisions locked directly with the user.
> 2. `/code/.plans/dossiers/phase-b-implementation.md` — **Part 2 is your
>    specification** (zero-coverage screens) and Part 3 covers navigation architecture.
>    This is your primary reference.
> 3. `docs/plans/PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` — the checked-in plan document.
> 4. `docs/DESIGN-SYSTEM.md` — the design contract every screen must comply with.
>
> These screens are called "zero design coverage" because there is no mockup for them.
> That does NOT mean invent freely — it means derive their design from
> `docs/DESIGN-SYSTEM.md` and from how the already-designed screens look, so they land
> as part of the same app rather than as visible outliers.
>
> ### YOUR FILES — you own these exclusively, touch NOTHING else
>
> Three sibling agents are working in this same checkout concurrently on the
> design-system primitives. Editing outside your lane will corrupt their work.
>
> - MODIFY `app/src/main/java/com/example/ui/screens/NotesScreen.kt`
> - MODIFY `app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt`
> - MODIFY `app/src/main/java/com/example/ui/components/DiffView.kt`
> - MODIFY `app/src/main/java/com/example/ui/components/AppNavigationDrawer.kt`
> - MODIFY `app/src/main/java/com/example/ui/screens/CaptureController.kt`
> - MODIFY `app/src/main/java/com/example/ui/screens/ChatScreen.kt` — **ONLY** the two
>   deprecation fixes described below, nothing else
> - MODIFY `app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` — **ONLY** the
>   one deprecation fix described below, nothing else
> - CREATE test files under `app/src/test/java/...` for your own code
>
> Explicitly NOT yours: `MainActivity.kt`, `Theme.kt`, `Color.kt`, `SettingsScreen.kt`,
> `HomeScreen.kt`, `CommonComponents.kt`, `CollapsibleNavPill.kt`, `BlurModifier.kt`,
> `StatsCompressiblePill.kt`, `InterceptScreen.kt`, `UnifiedDataStore.kt`,
> `AppModels.kt`, `AppRepositories.kt`.
>
> If you need a change in another agent's file, do NOT make it — report the file, the
> line and the change needed. The main agent will route it.
>
> ### WHAT TO BUILD
>
> #### 1. `NotesScreen.kt` — bring up to the design system
>
> Currently has no design coverage. Make it a first-class screen: correct floating
> glass header, correct scroll behaviour, correct spacing and typography tokens, correct
> empty state, correct list/card treatment. Every state must be handled — empty, single
> item, many items, long content, and whatever editing states exist.
>
> #### 2. `ToolWorkspaceScreen.kt` — custom workspace
>
> Per the dossier. Same standard.
>
> #### 3. `DiffView.kt`
>
> Per the dossier. Diff rendering has real states worth getting right: no differences,
> additions only, deletions only, both, very long lines, and binary/undiffable content.
> Handle them all rather than only the happy path.
>
> #### 4. `CaptureController.kt` overlay
>
> Per the dossier. This is the capture overlay. Note it is a live, stateful surface —
> it must look right in every capture state, including the error state. A prior session
> confirmed the app correctly surfaces a clear ERROR state when VPN capture cannot start
> (which is a genuine emulator limitation, not a bug) — make sure that error state is
> designed, not an afterthought.
>
> #### 5. `AppNavigationDrawer.kt` contents
>
> Per the dossier. Two specific things already decided that you must honour:
>
> - **Do NOT hardcode `gesturesEnabled = false`.** The `navigation_showcase_dark.html`
>   mockup implies it, but the deliberate decision is to keep the user toggle
>   `settings.gestureNavigationEnabled` (around `AppNavigationDrawer.kt:90`), which
>   matches `docs/DESIGN-SYSTEM.md` §10.4.
> - The **"Environments" row must remain honestly disabled.** It lives in the
>   **Reqy Assistant's own side drawer** (`ChatScreen.kt:1476`, KDoc 1364–1376),
>   *not* `AppNavigationDrawer.kt`. There is genuinely no Environments screen in
>   this codebase. Do not wire it to something unrelated and do not silently
>   delete it — a disabled row with a clear reason is the intended behaviour.
>   Other drawer cross-links (Home, Collections, Flows) already navigate for
>   real; keep them working.
>
> #### 6. Three deprecation fixes — strictly scoped
>
> These are new in this branch and must not ride into the PR unaddressed. Change ONLY
> these, and nothing else, in these two files:
>
> - `ChatScreen.kt:762` — `Icons.Filled.Reply` → the `Icons.AutoMirrored.Filled`
>   equivalent. Trivial.
> - `ApiTestrScreen.kt:2516` — `Icons.Filled.FormatListBulleted` → the
>   `Icons.AutoMirrored.Filled` equivalent. Trivial.
> - `ChatScreen.kt:1222` — a deprecated `resume(value, onCancellation)` continuation
>   overload. **This one deserves real thought, not a mechanical swap.** It sits in the
>   PixelCopy screenshot path that was only just fixed this session, and the deprecated
>   overload's cancellation semantics are exactly what bites when the coroutine is
>   cancelled mid-capture. Understand what the current code does on cancellation,
>   migrate to the non-deprecated API, and make sure a cancelled screenshot cannot leak
>   a bitmap or resume twice. Resuming a continuation twice throws. Add a test if you
>   can reach it.
>
> Line numbers may have shifted; locate by symbol, not by trusting the number.
>
> ### COMPLETE OLD-UI REMOVAL
>
> The plan requires complete removal of the old UI per screen — not old and new code
> paths coexisting behind a flag, and not dead composables left behind. When you replace
> a screen's UI, delete what it replaced. If deleting something looks unsafe because
> another file you do not own still references it, report that rather than leaving
> orphaned code silently in place.
>
> ### HARD CONSTRAINTS
>
> - **Compose Foundation is 1.7.2.** `rememberOverscrollEffect` does NOT exist (1.8+
>   API), verified by unzipping the actual AAR. If you hit a missing-API compile error,
>   do NOT bump the Compose BOM.
> - Sibling agents are concurrently building the glass/header/motion primitives you
>   would ideally consume. They may not exist yet while you work. Do not block on them
>   and do not create your own competing versions of them. Where you need a primitive
>   that is not there yet, use the existing shipped components, keep your usage in one
>   obvious place per screen, and list in your report exactly where the new primitive
>   should be swapped in.
> - **Concurrency**: you may run `./gradlew :app:compileDebugKotlin --no-daemon` and
>   `--tests`-filtered test runs. You may NOT run `assembleDebug`, `assembleRelease`,
>   `build`, `clean`, or an unfiltered `testDebugUnitTest`, and you may NOT touch adb,
>   the emulator or scrcpy. A bizarre unresolved-reference error in a file you never
>   touched is probably a sibling mid-write — re-run before believing it. If KSP caches
>   corrupt, clear `app/build/kspCaches/debug`.
> - A `| tail` pipe HIDES a real `BUILD FAILED`. Always check the exit status directly.
> - Environment: `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env &&
>   ./gradlew ... --no-daemon`.
> - You are explicitly ALLOWED AND ENCOURAGED to rewrite existing code that is broken,
>   weak or behaves oddly. Do not patch around bad code — replace it. Direct instruction
>   from the user.
>
> ### A PATTERN WORTH KNOWING BEFORE YOU START
>
> The last two on-device test rounds on this branch found that the most common defect
> class here was **features that were implemented correctly but physically unreachable**:
> a root `Column` with no `verticalScroll` so content below the fold could never be
> reached; a chip `Row` with no `horizontalScroll` hiding 6 of 11 options; an
> unconstrained `LazyColumn` inside a plain `Column` pushing a toggle ~900px off-screen;
> a badge offset outward into a clipped corner so it was never visible. Unit tests were
> green for all of it.
>
> So as you build: for every scrollable region confirm it can actually scroll, for every
> constrained child inside a `Column` confirm it is height-bounded, and mentally check
> the smallest supported screen (test device is 720x1600). Reachability is part of done.
>
> ### DEFINITION OF DONE
>
> Every screen and every state implemented, wired up and working, with no illogical
> errors, bugs, or visual/functional gaps. Old UI removed. Unit tests for what is
> JVM-testable. `:app:compileDebugKotlin` green.
>
> Report back: what you built per screen, where the sibling agents' new primitives
> should be swapped in, every change you need in a file you do not own, what you
> deliberately did not do and why, and specifically what you found about the
> `ChatScreen.kt:1222` cancellation semantics.

---

## 9. Review / reflection feedback still to address (carry-forward)

These are findings the previous agent received but did NOT yet action. The next agent
must action them. Sources: internal review agent (`pa-review`), simplify agent
(`pa-simplify`), and reflection worker feedback.

### MUST-FIX (outstanding)

- **M4 — Add-Rule form still creates enabled silent no-op rules.** Blank
  `MODIFY_REQUEST`, `MODIFY_RESPONSE` and `CHAOS` configs are explicitly accepted;
  blank `SCRIPT_REWRITE` becomes `null`; the resulting enabled rule's engine path makes
  no change. Files: `ReplacementValueUiHelpers.kt:64-103` and `:135-157`,
  `InterceptScreen.kt:4091-4128`. **Blank `MOCK_RESPONSE` must stay valid** (it
  produces the documented empty `200 OK`). This was deliberately held back because
  `InterceptScreen.kt` was owned by another agent at the time; now safe to dispatch.
  Natural owner: `p1-e-wave3-ui` (a prior build agent) or a fresh agent.

### SHOULD-FIX / NIT (outstanding)

- **Three deprecation warnings, new in this branch, unfixed** — `Icons.Filled.Reply`
  (`ChatScreen.kt:762`), `Icons.Filled.FormatListBulleted` (`ApiTestrScreen.kt:2516`),
  and the deprecated `resume(value, onCancellation)` overload (`ChatScreen.kt:1222`).
  These were explicitly assigned to `ds-d-screens` (see §8.4 item 6). **The
  `ChatScreen.kt:1222` continuation overload is the one that matters** — it sits in the
  PixelCopy screenshot path and its cancellation semantics can resume-twice or leak a
  bitmap on a cancelled coroutine. Do not let it ride into the PR unaddressed.
- **Memory file missed by the earlier correction pass** —
  `/memory/testing/requestr/test-plan-detailed.md` lines ~77-81 still carried the
  DISPROVEN claim "the container has no outbound internet … verified via `ping 8.8.8.8`
  = 100% packet loss". The main agent fixed it on 2026-09-01 in the `/memory` volume
  (NOT in git — `/memory` is a shared volume, so this fix is not part of any commit).
  Do not let a future agent regress to `ping`-based connectivity conclusions.

### Feedback the review gave about PROCESS (must carry into Phase B)

1. **Phase A never visually diffed against the 62 mockups** — the smoke test exercised
   behaviours but was never given a path under `docs/designs/`, so it proved features
   work, not that they match the design. **For Phase B testing: pass the mockup paths
   and explicitly require screenshot-vs-mockup validation.** (§5 mockup mapping above.)
2. **Do not fabricate statistics in user-facing questions.** A prior draft question said
   "roughly a fifth of active devices" with no basis. Drop unsupported figures.
3. **Keep the reflection workflow tight** — surface genuine unknowns via `ask_user`, but
   don't re-ask decisions already settled in the dossiers/briefing.

### Already-resolved review items (do NOT re-open)

For the record, `pa-review`'s MUST-FIX M1 (stop routed to wrong engine), M2 (AES
fail-open), M3 (API-key logging), M5 (workspace export drops crypto fields), and
SHOULD-FIX S1-S7 were ALL closed in `71bdc23`, each with an empirical red/green cycle.
A separate `pa-simplify` pass removed ~140 net production lines and consolidated
duplicated helpers. Details in `71bdc23`'s commit body and §4 table.

### Resolved surfaced finding

- **`ResponseInspectorView.kt` dead-code finding — RESOLVED.** The user approved
  HARVEST-then-REMOVE. Hex view, in-body search/highlighting, parsed Set-Cookies,
  per-header/copy-all, and export/share were ported into ApiTestr's inline response viewer;
  tested pure helpers moved to `ResponseInspectorHelpers.kt`; the dead inspector was deleted.
  Original finding: `62e5a42a-3f2f-478c-adce-eb505ca64826`, idempotency key
  `requestr-dead-response-inspector`. Follow-up status-sync finding:
  `b4777c7c-264e-4428-9933-7581b4338860`. The repo status is Resolved; the original platform
  entry may remain Pending because the current tooling cannot mutate an existing finding status.

---

## 10. Outstanding work — ordered next steps

1. **Re-confirm the user has said "go"** before any Phase B code (they paused it).
2. Decide/commit the §5 glass contract (currently uncommitted).
3. Re-dispatch the 4 Phase B agents (§8) in Wave 1. Collect their reported API
   signatures and cross-file change requests; route cross-file needs yourself.
4. Wave 2: Home rebrand + handler in `HomeScreen.kt` (owned by the main agent or a
   dedicated agent — the 4 lanes above deliberately leave `HomeScreen.kt` unowned,
   because Home's header capsules consume Wave 1 primitives).
5. Dispatch MUST-FIX **M4** (Add-Rule blank configs) — do not forget it.
6. Re-run the **full verification** of the whole tree after Phase B lands, in this order:
   1. `./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :sdk-noop:testDebugUnitTest --continue --no-daemon`
   2. `cargo test --lib` in `native/tun2proxy-bridge` (expect 34/34)
   3. `node test/run-all.mjs` in `browser-extension` (expect 10/10)
   4. `./gradlew :cli:build :app:assembleDebug :app:assembleRelease --no-daemon`
7. Run `simplify` + `review` on the Phase B diff (max 2 review iterations total; **1
   already consumed** on Phase A).
8. Final `testing` subagent + Phase B test report (new title, e.g. "Design-plan Phase B
   — on-device smoke test").
9. **Create the PR** — first `gh pr list --repo Mahesh953-hub/requestr --head
   vorflux/master-plan-and-phased-design-implementation --json number,title,url`, then
   `vflux_exec pr create --repo-url https://github.com/Mahesh953-hub/requestr --title <t>
   --body-file <path> --head vorflux/master-plan-and-phased-design-implementation`
   (never inline `--body`; do not pass `--base`).

### PR description must document (carry-forward list)

- The single "not possible in emulator" item (VPN/tun) with reasons.
- The `gesturesEnabled` divergence (`navigation_showcase_dark.html` implies hardcoded
  `false`; we keep the user toggle `settings.gestureNavigationEnabled`, matching
  `DESIGN-SYSTEM.md` §10.4).
- The `set-cookie` redaction decision (mask by default, per-value reveal).
- Binary Base64 input deferred as an undesigned gap.
- The omitted "N environments" count on Saved APIs collection cards (cannot be sourced
  honestly — `ApiFolderItem` has no environment field; `EnvironmentRepository` models
  one global list).
- The honestly-disabled "Environments" drawer row in the Reqy Assistant drawer
  (`ChatScreen.kt:1476`) — not `AppNavigationDrawer.kt` (no such screen exists).
- The onboarding reconciliation decision (keep Identity/Permissions/Appearance;
  VPN/CA setup remains outside onboarding, in InterceptScreen/Settings).
- cURL importer behaviour changes: unknown short flags refused by name; `-F`/`-T`
  consumed and discarded; unknown long flags fall through backstopped by absolute-URL.
- A `## Testing` section (a PR without one is not review-ready).
- An `## Out-of-Scope Feedback` section recording the resolved `ResponseInspectorView.kt`
  finding when this work is described in a PR.

---

## 11. Environment, toolchain, and hard gotchas (do not relearn these)

- Env file: `. ~/.requestr-env` before every Gradle command. Non-interactive shells do
  NOT source `.bashrc`.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`, `ANDROID_HOME=/opt/android-sdk`,
  `ANDROID_NDK_HOME=/opt/android-sdk/ndk/28.2.13676358`, `PATH` includes
  `$JAVA_HOME/bin`, cmdline-tools, platform-tools, `~/.cargo/bin`.
- JDK 17, Rust 1.98.0, cargo-ndk 4.1.2, Compose BOM 2024.09.00, Foundation 1.7.2,
  AGP 9.1.1, Kotlin 2.2.10, `minSdk 24`, `compileSdk 36`, `targetSdk 36`,
  `applicationId com.kailashhh.requestr`, `versionCode 28`, `versionName "6.0"`.
- Release signing fallback chain (Gradle prop → env → literal default):
  `RELEASE_STORE_FILE`→`${rootDir}/requestr-release.jks`, `RELEASE_STORE_PASSWORD`→
  `"requestr"`, `RELEASE_KEY_ALIAS`→`"upload"`, `RELEASE_KEY_PASSWORD`→`"requestr"`.
  Checked-in keystores: `my-upload-key.jks`, `requestr-release.jks`. A
  `verifyReleaseSigning` task asserts alias `upload` + locked cert SHA-256 before
  release assembly. Errors are pre-existing baseline, not new breakage.
- **Redroid emulator** (`redroid/redroid:15.0.0-latest`, SDK 35), adb device
  `localhost:5555`. **Always pin `-s localhost:5555`** (an `emulator-5554` also shows).
  Display 720x1600. `/code/uidump.py` (Python uiautomator driver) preferred over
  `/code/adb-helpers.sh`. scrcpy 4.0 at `/opt/scrcpy`, wrapper `/usr/local/bin/scrcpy`.
- **Redroid network**: run `/code/fix-redroid-net.sh` after ANY container restart
  (`docker exec redroid ip route replace default via 172.17.0.1 dev eth0`). **NEVER use
  `ping 8.8.8.8` as a connectivity test** — ICMP to public IPs is blocked by the
  sandbox egress; it shows 100% loss even when DNS/TCP/TLS all work. Use
  `dumpsys connectivity` (look for `VALIDATED`) or a real TCP request.
- **VPN/tun capture genuinely cannot start on Redroid** (`Cannot create interface`; no
  `pm list features` VPN entry; `dumpsys vpn` missing; only `lo`+`eth0`). The app's
  ERROR state for this is correct and must not be "fixed". Use PROXY record mode for
  capture-complex feature verification. PROXY capture works (port 9000, HTTP +
  HTTPS-CONNECT).
- Reaching the in-app proxy from the host: `PID=$(docker inspect -f '{{.State.Pid}}'
  redroid)` then `sudo nsenter -t $PID -n curl -sSi -x http://127.0.0.1:9000 <url>`.
- Recording: `ffmpeg -f x11grab -video_size 1920x1080 -i :1 -c:v libx264 -preset
  ultrafast <out>.mp4` (background job, kill to finalize).
- **Concurrent Gradle hazard**: multiple agents share ONE working tree. Build agents
  are forbidden from full builds; allowed: `:app:compileDebugKotlin --no-daemon` and
  `--tests`-filtered runs. `| tail` masks `BUILD FAILED` — check exit status directly.
  KSP cache corruption symptoms: clear `app/build/kspCaches/debug`. One transient
  `TunRelayTest` flake was seen and fixed.
- **Only ONE agent may own a Room schema bump per wave.** Current schema version: 17
  (schemas checked in at `app/schemas/com.example.data.local.RequestrDatabase/{15,16,17}.json`).
- Nav-pill coordinate gotcha: active tab renders wider, shifting other tabs' x-bounds —
  re-dump before every nav tap.

### Repository facts

- 192 Kotlin files under `app/src/main/java`, ~67,322 LOC. Modules: `app`,
  `browser-extension`, `cli`, `native/tun2proxy-bridge`, `sdk`, `sdk-noop`.
- Screens in `app/src/main/java/com/example/ui/screens/`: `ApiTestrScreen.kt`,
  `CaptureController.kt`, `CapturedTrafficHelpers.kt`, `ChatScreen.kt`, `FlowScreen.kt`,
  `HomeScreen.kt`, `InterceptScreen.kt`, `MusicScreen.kt`, `NotesScreen.kt`,
  `OnboardingScreen.kt`, `SavedApisScreen.kt`,
  `SettingsScreen.kt`, `ToolWorkspaceScreen.kt`, `ToolsScreen.kt`, plus
  `ImageGalleryScreen.kt`, `StatsDashboardScreen.kt`, `NetworkConditionUiHelpers.kt`,
  `ReplacementValueUiHelpers.kt`, `RuleTemplateUiHelpers.kt`, `SavedApisGrouping.kt`,
  `ApiTestrBodyToolbarHelpers.kt`, `ApiTestrRedirectMode.kt`.
- Rule editor is `RulesConfigBottomSheet`, at `InterceptScreen.kt:3669-4276`.
- Nav: 6 tabs (Home, Intercept, APIs, Tools, Flow, Music), defined
  `CollapsibleNavPill.kt:76-83`; 11-route screen enum `MainActivity.kt:291-309`;
  `CollapsibleNavPill` hosted ~`MainActivity.kt:430-445`.
- Drawer cross-links navigate for real (Home, Collections, Flows); **Environments row is
  honestly disabled** in the Reqy Assistant drawer (`ChatScreen.kt:1476`), not
  `AppNavigationDrawer.kt` (no Environments screen exists).

---

## 12. How to resume (concrete)

1. Read this file end to end, then `/code/.plans/PHASE-B-SHARED-CONTEXT.md`, then the
   four dossiers in `/code/.plans/dossiers/`.
2. Confirm with the user that coding may proceed (they paused it explicitly).
3. Commit the §5 glass contract (or fold it into the first Phase B commit — your call;
   the branch already has a clean history).
4. Re-dispatch §8's four agents exactly. Collect their reports; relay cross-file needs.
5. Follow §10 items 4–9 through to the PR.

### Concurrency note for the resuming agent

The previous four sub-agent tasks were cancelled (not failed) — there are no partial
sub-agent results to recover and no orphaned sub-agent edits beyond the main agent's own
§5 contract. Verify with `git status` (should show only the 3 §5 files) before fanning
out again.
