╔══════════════════════════════════════════════════════════════════════════╗
║  requestr — v2.1 REVISION SPEC (Bug Fixes + New Features)                ║
║  NATIVE ANDROID APP · targets API 28–37 · Kotlin/Compose                 ║
║  Package: com.kailashhh.requestr                                         ║
╚══════════════════════════════════════════════════════════════════════════╝

## HOW TO READ THIS DOCUMENT

This is a **post-build QA pass** on a real, already-implemented codebase —
not a greenfield spec. The user has tested v2.1 on-device and is reporting
actual observed bugs with screenshots as evidence. Every item below is
written as: **What's broken → Why it's broken (likely root cause) → What
correct behavior must be.**

This document assumes you (the implementing agent) have access to the
actual `requestr` source tree. Locate the relevant files/composables/
services for each bug before making changes — do not guess-patch blind.
If the actual architecture differs from what's assumed below, adapt the
fix to the real code, but the **end-user-observable behavior described
here is non-negotiable** — that's what was tested and found broken.

Cross-reference documents (still valid, read alongside this):
- `requestr_stitch_prompt.md` — v1 UI/screen detail reference
- `requestr_ai_build_spec.md` — v2 native architecture + interception spec

**Golden rule for this pass: remove every hardcoded/demo/stub value in the
UI layer and replace it with real state from actual persistence/services.
If a screen shows data, that data must come from Room DB, DataStore,
SharedPreferences, or a live service — never a hardcoded sample.**

---

## §1 — HOME SCREEN: Setup Wizard Missing + Demo Data

### Bug 1.1 — No cold-boot setup wizard runs on first install
**Observed:** App opens directly to Home screen showing hardcoded name
"Kailash" — no name entry, no permission requests, no theme selection
ever appeared.

**Root cause (likely):** Either the wizard composable/activity exists but
its "has completed onboarding" check is defaulting to `true`/is missing
entirely, or the wizard was built but never wired as the actual app entry
point (`MainActivity`'s start destination isn't conditionally routing to
it).

**Required fix:**
- On very first app launch (check a persisted boolean flag —
  `DataStore<Preferences>` key `onboarding_completed`, default `false`),
  the **first screen shown, before any other UI renders**, must be the
  Setup Wizard. No Home screen content should be reachable or visible
  underneath it.
- Wizard screens, in strict order:
  1. **Name entry** — text field, no default/placeholder value pretending
     to be a real name. Empty until user types. `Continue` disabled until
     non-empty.
  2. **Permissions** — request only what's actually needed for the
     features being enabled: `POST_NOTIFICATIONS` (Android 13+),
     storage/media access for Notes+Music (scoped storage / `READ_MEDIA_AUDIO`
     on Android 13+, `READ_EXTERNAL_STORAGE` fallback below), VPN consent
     is requested contextually later when the user first activates
     interception (not here — don't front-load a permission the user
     hasn't chosen to use yet). Each permission row shows real granted/
     denied state via `ActivityResultContracts.RequestPermission`
     callback, not a static checkmark.
  3. **Theme selection** — see §2. Selecting a theme here must actually
     persist it (see Bug 2.1).
  4. Final confirmation screen → sets `onboarding_completed = true` in
     DataStore → navigates to real Home.
- If the app is killed mid-wizard and reopened, it must resume the wizard
  (flag still `false`), not skip to Home.
- Provide a **Settings → "Replay Setup Wizard"** action for testing/
  re-onboarding (does not reset saved APIs/notes/data, only re-runs the
  wizard flow and lets the user change name/theme).

### Bug 1.2 — Theme resets on app restart
**Observed:** Selecting a theme (in the broken wizard or wherever theme
selection currently lives) does not persist across app restarts.

**Root cause (likely):** Theme state is held only in a `remember {}` /
in-memory `ViewModel` state with no `DataStore`/`SharedPreferences` write-
through, so it's lost when the process dies.

**Required fix:**
- Theme choice (which of the 9 total: Light, Dark, + 7 named themes — see
  §2) must be written to `DataStore<Preferences>` immediately on
  selection, and read on app cold-start **before** the first Compose
  frame renders (hoist theme state above `setContent {}` using a
  synchronous or `runBlocking` initial read, or a splash-gated approach
  that waits for the DataStore value before rendering real UI — avoid a
  visible "flash of wrong theme" on launch).
- Verify: force-stop the app from Android system settings (not just
  backgrounding it), reopen — theme must be identical to last selection.

### Bug 1.3 — Music keeps playing with no user action
**Observed:** Music is audibly playing on Home screen despite the user
never opening Music or pressing play.

**Root cause (likely):** Either a demo/placeholder `MediaPlayer`/
`ExoPlayer` instance is being initialized and auto-started somewhere in
app init code (leftover test code), or the Music service is a foreground
service that's incorrectly set to auto-start on `Application.onCreate()`.

**Required fix:**
- Audio playback must **only** begin as a direct result of explicit user
  action (tapping play on a track, in the Music section). No
  autoplay-on-launch, anywhere, ever.
- Audit `Application` class and any `Service`/`WorkManager` initializers
  for auto-start logic tied to music playback and remove it.
- The Music mini-bar (see §7) must not render at all if `playbackState ==
  IDLE/STOPPED` and nothing has ever been played this session.

### Bug 1.4 — Home screen is visually dense/cluttered
**Observed:** Screenshot 1 shows a Home screen packed with: greeting,
search bar, 4 quick-action chips, an "Interception: ACTIVE" banner,
horizontally-scrolling "Recently opened" cards, and a 6-card "Power
Modules" grid — all stacked with little breathing room, reading as
overwhelming rather than premium.

**Required fix — Home screen redesign:**
- Reduce to this vertical rhythm, each section with **generous padding**
  (minimum 24dp between major sections, not the ~12dp implied in the
  screenshot):
  1. **Header row**: wordmark (top-left, per new logo spec §8) + avatar
     (top-right) — no separate greeting text row cluttering below it;
     merge greeting into a single elegant line under the wordmark, e.g.
     *"Good afternoon, {name}"* in a lighter/smaller weight, no emoji
     clutter (drop the 🌤️ — the editorial wordmark direction in §8 does
     not pair with emoji)
  2. **Search bar** — full width, generous height, minimal border
  3. **Quick actions** — keep, but cap visible chips to 3 + an overflow
     `···` rather than letting a 4th chip cut off awkwardly at the
     screen edge (as seen in screenshot 1, "+ Ne..." is cut off)
  4. **Interception status card** — see Bug 1.5, must reflect real state,
     and only render prominently if interception has EVER been used this
     install (otherwise show a smaller, quieter "Set up Interception →"
     prompt instead of a fake "ACTIVE" card)
  5. **Recently opened** — see Bug 1.6, real data only
  6. **Power Modules grid** — keep the 2×3 grid concept but simplify
     card content: icon + title + one live stat, drop the redundant
     subtitle line to reduce density (e.g. "Intercept / 8 captured" is
     enough; "MITM VPN, Frida & Rules" subtitle can be cut or moved to a
     tooltip/long-press)

### Bug 1.5 — "Interception: ACTIVE" banner is fake/always-on
**Observed:** Home shows a green "Interception: ACTIVE — VPN & Proxy
running on port 8080 · 8 packets" card unconditionally.

**Required fix:**
- This card's visibility and content must be driven by the **actual**
  `VpnService` connection state (`Service.onStartCommand`/bound service
  state observable via `Flow<InterceptionState>` from a repository, not
  a hardcoded UI value).
- States to render distinctly:
  - **Never configured**: no card, or a minimal "Set up Interception →"
    CTA
  - **Configured but off**: quiet card, "Interception is off · Tap to
    start" — muted colors, no green
  - **Active**: green, live packet count that increments in real time
    from the actual capture session, real port number from actual config
    (not hardcoded `8080` if the user changed it in settings)
- Packet count of "8" must never appear unless 8 packets were genuinely
  captured in the current/last session — pull from the real Room DB
  session record.

### Bug 1.6 — "Recently opened" shows fabricated sample data
**Observed:** Cards for "HttpBin JSON Echo," "GitHub User Profile," "Post
JSON..." appear even on what should be a fresh/test install.

**Required fix:**
- This section must query the real `SavedApiRequest`/activity-log table
  for the N most recently opened/executed items (APIs, notes, chats —
  whatever the user has actually interacted with), ordered by
  `last_opened_at DESC`.
- **If the user has never created or opened anything, this section must
  not render at all** (not an empty-state placeholder with fake sample
  cards — just omit the section entirely, or show a single small
  "Nothing yet — start by creating an API request" prompt with a direct
  create action).
- Any seed/sample data used during development (HttpBin, GitHub examples)
  must be stripped from production builds entirely — these are fine as
  local dev fixtures but must never ship or appear on a real device.

---

## §2 — THEMES: Light Theme Missing + 7 New Themes + Persistence

### Bug 2.1 — Light theme doesn't exist / theme selection is non-functional
Already covered persistence in Bug 1.2. This section defines the actual
theme content required.

**Required: 9 total themes** (Light + Dark as "universal" baseline, plus
7 named editorial themes), each following the same premium/editorial
density and contrast quality as the Light spec below — **no theme should
look like a generic Material default palette**.

**Light (baseline)**
```
Background:  #F4F2EC   (warm ivory/cream)
Surface:     #FFFFFF   (card white/off-white)
Primary:     #111111   (near-black text)
Accent:      #F28C28   (warm orange)
Border:      #E5E1D8
Text muted:  #6B6862
Feel: editorial, premium, soft, minimalist
```

**Dark (baseline)**
```
Background:  #0A0E1A   (deep midnight navy — from v1 spec, retained)
Surface:     #111827
Primary:     #F1F5F9
Accent:      #3B82F6   (electric blue)
Border:      #1E2D45
Text muted:  #94A3B8
```

**7 additional named themes — each must specify background / surface /
primary text / accent / border, following the same "rich editorial"
quality bar as Light:**

1. **Obsidian** — pure black `#000000` bg, `#0D0D0D` surface, white text,
   neon cyan accent `#06B6D4` — for OLED/battery-conscious users
2. **Dusk** — deep purple-gray `#1A1625` bg, `#241E33` surface, off-white
   text, amber accent `#F59E0B`
3. **Terracotta** — warm dark clay `#231A16` bg, `#2E2119` surface, cream
   text `#F4E9DD`, burnt-orange accent `#D97444` — editorial/earthy
4. **Sage** — muted dark green `#161D19` bg, `#1F2924` surface, soft
   white text, sage-green accent `#7FA88C`
5. **Rosewood** — deep maroon-brown `#1F1416` bg, `#2A1B1E` surface,
   blush-white text, dusty rose accent `#C97B84`
6. **Slate Editorial** — cool graphite `#1A1D21` bg, `#23272C` surface,
   crisp white text, steel-blue accent `#5B7C99` — closest "quiet
   luxury" companion to the Light theme's restraint, in dark form
7. **Parchment** — light theme variant, aged-paper `#EDE6D6` bg, `#FAF7ED`
   surface, dark-brown text `#3B2F2A`, deep red accent `#8C3A2B` — a
   second light option for users who want light-but-not-cold

**Implementation requirement:**
- All 9 themes defined as Compose `ColorScheme`/custom theme data classes
  in one central theme file — no scattered hardcoded colors in individual
  composables (audit for any `Color(0xFF...)` literals outside the theme
  definitions and replace with `MaterialTheme.colorScheme.*` or the
  app's custom theme token references)
- Theme picker UI (in Settings, and in the setup wizard) shows a live
  preview swatch per theme (small mock card showing bg/surface/accent
  together), not just a color dot or name

---

## §3 — INTERCEPTOR SCREEN: Full Rebuild of Fake State

Screenshot 2 shows the core problem: every capability chip (`Frida
(Active)`, `MITM CA Root`, `Root (Granted)`, `Shizuku`) renders as if
granted/active, and the traffic list shows entries even though nothing
real is being captured.

### Bug 3.1 — Capability chips show fake/always-true state
**Root cause:** Chips are almost certainly bound to hardcoded `true`
values or static sample UI state rather than actual runtime checks.

**Required fix — each chip must run a real check:**
- **Root**: probe for `su` binary via standard methods (check common
  paths: `/system/bin/su`, `/system/xbin/su`, etc., AND attempt an actual
  `Runtime.exec("su")` handshake via `libsu`) — chip shows `Root:
  Available` (tap to request) / `Root: Granted` / `Root: Denied`, never
  a static "Granted"
- **Shizuku**: use the real Shizuku SDK (`Shizuku.pingBinder()`,
  `Shizuku.checkSelfPermission()`) — chip reflects actual binder
  connection + permission state
- **MITM CA Root**: actually check whether requestr's CA cert is present
  in the relevant trust store (user store via
  `KeyStore.getInstance("AndroidCAStore")` enumeration, or system store
  path check if root available) — chip shows `Not Installed` / `User
  Store` / `System Store`, tapping opens the CA install flow (§ from
  v2 architecture doc), never shows installed if it isn't
- **Frida**: actually attempt to ping `frida-server` on the expected port
  (default 27042) via a lightweight TCP handshake — chip shows `Not
  Running` / `Running` based on real response, never "Active" by default
- Chips with unmet prerequisites should be visually distinct
  (outlined/muted, not filled/accent) so the difference between
  "available and on" vs "not available" is immediately scannable — do
  not use the same visual treatment for both states (this was the exact
  bug: everything looked identically "on")

### Bug 3.2 — Traffic list shows fake entries when nothing is running
**Required fix:**
- The captured-traffic list must be a direct, live-observed `Flow` off
  the Room DB table that the actual proxy/VPN engine writes to. If that
  table is empty, **the list is empty** — render an empty state (see Bug
  3.3), never sample rows.
- Any seed data used for UI development must be stripped from the
  production data source / behind a `BuildConfig.DEBUG`-gated fixture
  loader that never runs in release builds.

### Bug 3.3 — Wrong / missing empty-state messaging
**Required fix:**
- When interception has never been started (or is currently stopped and
  the log is empty): show a clear, centered empty state:
  *"Nothing captured yet. Tap the button below to start recording
  traffic."* — with a subtle down-arrow or visual pointer toward the FAB
  described in Bug 3.5.
- Remove any placeholder rows, sample requests, or "demo" visual filler
  from this state entirely.

### Bug 3.4 — No real pre-flight checks before starting interception
**Observed/required:** Currently, nothing appears to gate starting
interception on actual readiness.

**Required fix — strict pre-flight sequence when the user taps Start:**
1. **Check CA certificate installation status.** If not installed (in
   any store), **do not start** — instead open the CA install flow
   (from v2 spec §3) and block the start action with a clear message:
   *"Install the requestr CA certificate before starting interception."*
   Only after the app detects a successful install (re-check, don't just
   trust a "done" button) does Start become available.
2. **Determine capture method automatically, in strict priority order:
   Root > Shizuku > Local VPN fallback.**
   - If root is available and granted → use root-tier capture (iptables/
     system CA store path, per v2 spec Tier 3)
   - Else if Shizuku is available and permission granted → use
     Shizuku-tier capture (per v2 spec Tier 6)
   - Else → fall back to plain rootless `VpnService` capture (Tier 1) —
     this must always work as the guaranteed baseline
   - Show the user which tier was auto-selected (small label: *"Using:
     Root capture"* / *"Using: VPN capture (no root)"*) so it's never a
     silent mystery which engine is actually running
3. **Require app selection before start** (see Bug 3.6) — cannot start
   with zero apps selected and no "All apps" mode chosen.
4. Only after all checks pass does the VPN consent system dialog
   (`VpnService.prepare()`) fire, followed by actual capture start.

### Bug 3.5 — FAB / start-stop control redesign
**Required fix (from user's new-feature request):**
- Replace whatever start/stop control currently exists with a single
  **FAB positioned bottom-right**, sitting just above the bottom
  navigation (not overlapping it), styled as a "telegraph key" icon
  concept — the user's own words describe wanting a distinct pressable/
  toggle-key visual metaphor rather than a generic play/stop icon; use a
  radio/signal-dish or key-toggle style icon (Lucide's `radio` or a
  custom telegraph-key glyph), not a plain ▶/■ icon
- **Color states:**
  - **Yellow** = armed/ready but not yet capturing (pre-flight passed,
    waiting for user to actually tap and confirm, OR mid pre-flight-check
    transitional state)
  - **Green** = actively capturing
  - Tapping toggles between start/stop, with the color transition
    animated (not an instant color swap — a smooth cross-fade/scale
    pulse)
- **This FAB's state must also drive the logo dot** (§8) — when
  interception is active, the wordmark's signature dot (top-left, always
  visible per logo spec) turns green; when inactive, red. This is a
  single shared state source (`InterceptionState` flow), not two
  independently-set UI values that could drift out of sync.

### Bug 3.6 — App selection (target app / all apps) missing real function
**Required fix:**
- Before/at start, present an **app picker**: full list of installed
  apps (via `PackageManager.getInstalledApplications`, respecting
  Android 11+ package visibility — declare `QUERY_ALL_PACKAGES` per v2
  spec §5), each with app icon + name + checkbox
- Top of list: **"All apps"** toggle — when on, individual checkboxes
  are disabled/greyed (visually showing they're moot while "all" is
  selected, not just ignored)
- Selection must persist to the actual VPN/proxy engine's per-app filter
  config (UID-based filtering via `VpnService.Builder.addAllowedApplication()`
  / `addDisallowedApplication()`), not just be a UI-only selection that
  the backend ignores

### Bug 3.7 — Unwanted pills / clutter in top bar
**Observed:** Screenshot 2's top bar has a dense row of icons (grid,
sliders, lines, moon, gear) plus a full capability-chip row — too much
persistent chrome.

**Required fix:**
- Move all but the single most essential control into the section's own
  settings sheet (small `⚙` icon opens Intercept-specific settings — per
  the mini-settings pattern from v1 spec)
- Top bar keeps only: screen title, a single settings icon (bottom-right
  per user's nav redesign — see §5, settings icons move to the nav
  drawer/pill-menu's designated spot, not scattered per-screen), and
  live status text (e.g. capture engine tier + packet count)
- Capability chips (Root/Shizuku/Frida/CA) move into the dedicated "Root
  & Frida Tools" / "CA & Certificates" sub-screens (already specced in
  v2 §4) rather than permanently occupying Home-of-section real estate

### Bug 3.8 — Long-press context menu on captured traffic rows
**New feature, required:**
- Long-pressing any row in the traffic list opens a **contextual action
  menu**: dimmed background scrim, rounded rectangular surface anchored
  near the press point (or bottom-sheet style on smaller screens),
  actions stacked vertically:
  - `Open Detail`
  - `Send to API Testr`
  - `Copy as cURL`
  - `Add Intercept Rule from this`
  - `Block this domain` (disabled/reduced-opacity if a block rule
    already exists for that domain — demonstrating the "disabled actions
    rendered with reduced opacity" requirement)
  - `Delete entry`
  - `Share (export as HAR)`
- Disabled actions must be genuinely non-interactive (not just visually
  dimmed while still clickable) — gate with real state checks (e.g.
  actually query whether a block rule exists for that domain before
  disabling "Block this domain")

---

## §4 — BOTTOM NAVIGATION: Broken Tap Handling

### Bug 4.1 — Navigation taps unreliable/inconsistent
**Observed:** Taps sometimes do nothing, sometimes navigate to the wrong
(previous) screen, regardless of repeated tapping.

**Root cause (likely — check these in order):**
1. **Navigation state race condition**: if using Jetpack Navigation
   Compose, check whether `navController.currentBackStackEntryAsState()`
   is being read correctly to determine the "selected" tab, and whether
   `navController.navigate(route) { launchSingleTop = true;
   restoreState = true; popUpTo(startDestination) { saveState = true } }`
   is correctly configured — a common bug source is missing
   `launchSingleTop`, causing duplicate back-stack entries that make
   subsequent taps appear to "go back" instead of navigating forward.
2. **Overlapping touch targets**: if this is a custom bottom bar (not
   standard `NavigationBar`), check for overlapping `Box`/`Modifier`
   click regions — z-index or size mismatches between the visual icon
   and its actual clickable bounds can cause taps to hit the wrong
   target or nothing at all.
3. **Debounce/recomposition issue**: check whether rapid recomposition
   (e.g. from the fake "live" packet counter or theme state) is causing
   the nav bar to recompose mid-tap and drop the touch event — if so,
   stabilize the nav bar's own recomposition scope (hoist state properly,
   use `remember`/stable keys) so it isn't rebuilding every frame.
4. **Given this is being fully replaced per §5** (expandable pill menu),
   this bug may become moot — but if any interim testing build still
   uses the old bar, it must be fixed or removed, not left half-working.

**Required fix:** Navigation must be 1:1 reliable — every tap on a valid
destination navigates there immediately, single tap, no double-tap-
needed, no wrong-screen jumps, every single time. This is treated as a
release blocker, not a polish item.

---

## §5 — NEW: Bottom-Anchored Expandable Pill Navigation

Replaces the standard bottom nav bar entirely.

**Collapsed state:** Single circular FAB-style pill, bottom-center,
showing a `✦` (or app-appropriate glyph) icon, floating above content,
minimal footprint.

**Expanded state (on tap):** Staggered upward reveal — pills animate in
sequence (not all at once; slight delay per pill, e.g. 40–60ms stagger)
from the collapsed anchor point, stacking vertically upward:

```
        [ notes ]
        [ apis   ]
        [ interceptor ]
        [ All      ]  ← active/current section, filled/accent style
             ↑
            [ ✦ ]   ← anchor, now shows close/× or morphs
```

**Behavior requirements:**
- The **currently active section's pill is always visually distinct**
  (filled/accent background vs outlined for others) whether collapsed
  (shown as the anchor's mini-label or color) or expanded
- Tapping any pill navigates immediately AND collapses the menu back to
  its anchor state
- Tapping the anchor again while expanded collapses without navigating
- Tapping anywhere outside the expanded pill stack also collapses it
  (scrim or outside-tap-to-dismiss)
- Include entries for: Home, APIs, Tools, Chats, Notes, Flow, Intercept,
  Music — if this is more than fits as single pills, group logically or
  make the stack scrollable within a max height, but do not silently
  drop sections from navigation
- Spring-based animation (not linear) for both the stagger-reveal and
  collapse — matches the "immersive, richest experience" quality bar
  from the user's closing note
- This nav element must not suffer Bug 4.1's issues — build fresh with
  clean click-target hygiene and stable recomposition scope from the
  start

---

## §6 — NEW: Gesture-Driven Left Navigation Drawer

**Trigger:** Edge-swipe from left screen edge (standard
`ModalNavigationDrawer` gesture in Compose, or custom
`Modifier.pointerInput` drag detector if more control is needed over the
spring physics) — plus a tappable affordance (small edge indicator or
icon) for discoverability, since edge-swipe alone is not always
discoverable.

**Animation:** Horizontal translation with spring physics (`spring(
dampingRatio = Spring.DampingRatioMediumBouncy, stiffness =
Spring.StiffnessLow)` or tuned equivalent — should feel elastic/alive,
not linear/mechanical), subtle backdrop scrim fade-in synced to drawer
position (not a hard cut).

**Contents:**
- User avatar + name (tap → profile/name edit)
- Full navigation list (same destinations as the pill menu — this drawer
  is the "everything" navigation surface, the pill menu is the "quick
  access" surface; both must stay in sync, no destination exists in one
  but not the other)
- **Theme switcher** — inline, quick-swap between the 9 themes without
  leaving the drawer (small swatch row), separate from the full Settings
  theme page which offers the same choice with more detail/preview
- **Settings icon/entry** — direct link to full Settings
- Bottom of drawer: app version number, small footer

---

## §7 — MUSIC SECTION FIXES

### Bug 7.1 — Mini-bar has no close action
**Observed:** When paused, the mini-bar's secondary icon area has no way
to fully stop/dismiss playback and close the bar.

**Required fix (per user's exact spec):**
- When music is **playing**: mini-bar shows standard `prev / play-pause /
  next` controls
- When music is **paused**: replace the `next` icon specifically with a
  `close (×)` icon — tapping it stops playback entirely and dismisses
  the mini-bar from view (not just visually hides it — actually stop the
  `MediaSession`/`ExoPlayer` instance, release resources appropriately)
- When playback resumes (user taps play again before closing), the icon
  reverts back to `next`

### Bug 7.2 — Music permission flow missing
**Required fix:**
- Music section must explicitly request the correct permission for
  reading local audio depending on API level: `READ_MEDIA_AUDIO`
  (Android 13+/API 33+) or `READ_EXTERNAL_STORAGE` (API 28–32)
- If the broader "Manage All Files" access (used elsewhere in the app,
  e.g. for traffic log export) does not surface local music files for
  some reason (scoped storage edge case, MediaStore indexing delay,
  etc.), the Music section must **explicitly and separately** prompt:
  *"requestr needs access to your music library to show local songs"*
  with a direct `Grant Access` button — do not silently fail to a
  confusing empty state without explaining why
- For now, per user's note, Music is **local-file playback only** — no
  need to build streaming source integration in this pass; keep the
  Sources Selector UI (from v1 spec) present but only "Local Files"
  functional, other sources visibly marked "Coming soon" rather than
  appearing broken

---

## §8 — LOGO / WORDMARK REDESIGN

Full letter-by-letter construction spec for the `requestr.` wordmark,
positioned **top-left on Home and as the primary brand mark app-wide**
(app icon derives from the same visual language — see note below).

**Construction (per-letter):**
- **r** — soft entry stroke, slightly calligraphic, refined upward
  shoulder; light but confident weight
- **e** — compact editorial form, narrow counter, graceful terminal;
  delicate rather than geometric
- **q** — signature letter of the wordmark; rounded elegant bowl with a
  long, slightly dramatic descender — this is the personality anchor,
  should be the most visually distinctive letterform
- **u** — smooth continuous curve, relatively low contrast, acts as a
  quiet bridge between letters (no competing flourish)
- **e** — repeats the first e's construction exactly, for rhythm — do
  not introduce a variant form here
- **s** — high-contrast, slightly italic, graceful upper curve — second
  personality anchor alongside the **q**
- **t** — slim vertical stem, refined crossbar, slightly extended for
  ending tension
- **r** (final) — smaller, elegant terminal r, finishes upward rather
  than a hard stop
- **.** — mandatory, small, perfectly circular, optically balanced
  against the lowercase letterforms — **the only saturated/colored
  element in the wordmark**. This dot is **live UI, not static branding**:
  - Green when interception is active
  - Red when inactive
  - Bound to the exact same `InterceptionState` source as the Intercept
    FAB (Bug 3.5) — single source of truth, always in sync
  - Small idle animation acceptable (gentle pulse when active) but must
    not distract at the size it renders on-screen

**Overall character:** high-contrast serif, slightly italic, narrow
proportions, generous letter-rhythm, soft curves paired with sharp
hairlines, minimal geometric interference. Should read as belonging on a
"luxury magazine cover" — quietly expensive, explicitly **not** a
conventional tech/SaaS wordmark treatment (no geometric sans, no bold
block lettering, no gradient fills).

**Implementation note:** this is a custom letterform design, not a font
selection — the specificity here (per-letter contrast/terminal/stroke
direction) means this should be produced as a **vector asset (SVG)**
crafted to spec (by a designer or design-capable image-generation pass),
then imported as a drawable/vector resource — do not attempt to fake this
level of custom letterform control by picking an existing Google Font and
calling it done. If an existing high-quality editorial serif (e.g. a
display cut of a serif with italic swash characteristics) gets
reasonably close, it may serve as a temporary placeholder, but flag it
explicitly as "placeholder, not final wordmark" rather than shipping it
silently as final.

**App icon:** should derive from this same wordmark language — likely
the lowercase **q** (the signature letter) isolated as an icon mark, or
the full dot-accented wordmark compressed into an icon-safe crop —
produce as a separate deliverable once the wordmark vector exists, not
guessed independently.

---

## §9 — ICONS: Lucide-Style System-Wide

**Required fix:** Audit every icon currently in the app (nav, buttons,
chips, list items, everything) and standardize on **Lucide-style outline
icons with rounded strokes** — consistent stroke width (recommend 1.5–2px
at standard render size), rounded line caps/joins, no mixed icon families
(no Material filled icons sitting next to outline icons — pick one
family and apply it everywhere). Use the actual Lucide icon set
(`lucide-icons`, available as SVGs to convert to Android vector drawables,
or via a Compose Lucide icon library port if one is being used) rather
than approximating the style with a different icon pack.

---

## §10 — DETAILED SETTINGS PAGE (Full Rebuild)

Bug 1.7 (implicit in user's complaint: *"the setting has the least
settings options which are not even capable of handling it"*) — Settings
must be rebuilt as the **full, real control surface** for the entire app,
matching the depth already specced in v1 §Settings, PLUS:

**Structure (top to bottom):**
1. **Profile block** (top): avatar, name (editable inline), member-since/
   install-date if tracked
2. **Appearance**: full 9-theme picker with live preview swatches (not
   just names), accent override (if a theme allows secondary accent
   tuning), corner roundness slider, font choice, code font choice, font
   size, UI density — all as specced in v1, verified functional (not
   stubbed)
3. **Intercept**: default capture tier preference (auto vs force-VPN-
   only for testing), default proxy port, CA cert management shortcut,
   default app-filter behavior (remember last selection vs always ask)
4. **APIs**: as specced in v1
5. **Chats**: as specced in v1
6. **Notes**: as specced in v1
7. **Tools**: as specced in v1
8. **Flow**: default canvas theme, auto-save toggle, plus new **Export**
   settings (default export format — JSON/n8n-compatible — see §11)
9. **Music**: source preferences, permission status/re-request shortcut
10. **Data**: export/import/clear, as specced in v1
11. **Notifications**: as specced in v1
12. **Deeplinks / Automation**: new section (§11) — manage registered
    deeplink actions, view automation trigger log
13. **About block** (bottom, exactly as user requested): app name
    (`requestr.` wordmark, small), full version string (e.g. `v2.1.0
    (build 214)`), package name (`com.kailashhh.requestr`), a small
    credits/changelog link, "Replay Setup Wizard" action from Bug 1.1

Every row in every section must be **functionally wired**, not visual-
only — this entire page was called out as the least-capable part of the
app, so treat every toggle/slider/dropdown here as requiring a real
backing state + real effect, verified by testing after implementation,
not assumed complete because the UI renders.

---

## §11 — FLOW SECTION: Rebuild + Real Editing + Export

Screenshot 3 shows: nodes that can't be moved, a third connected node
that's cut off/inaccessible off-screen with no way to pan to it, and an
overall "sticky," under-informative canvas.

### Bug 11.1 — Nodes are immutable (can't drag/move)
**Required fix:** Every node on the canvas must support drag-to-
reposition via standard `pointerInput`/`detectDragGestures`, updating the
node's stored `(x, y)` position in the flow's persisted state (Room DB
or file-backed JSON) in real time as it's dragged, not just visually
during the gesture with no persistence.

### Bug 11.2 — Can't reach/see the third node
**Required fix:**
- Canvas must support pan (single-finger drag on empty canvas space) and
  pinch-zoom, with **no artificial bounds that trap the viewport** away
  from existing nodes
- Add a **"Fit to view" / "Center graph"** action (icon button, e.g. in
  the canvas toolbar) that automatically pans/zooms to fit all nodes in
  the current flow within the viewport — solves exactly this "I can't
  find my third node" problem on demand
- The mini-map mentioned in v1/v2 specs (bottom-right canvas overlay)
  should also be verified functional here — if it exists but isn't
  rendering/working, fix it as part of this pass; it directly prevents
  this exact bug

### Bug 11.3 — Canvas feels "sticky" / lacks information
**Required fix:**
- Verify touch/drag responsiveness — canvas pan and node-drag gestures
  should feel 1:1 with finger movement, no lag, no gesture conflict
  between "trying to pan canvas" vs "trying to drag a node" (hit-test
  node bounds correctly so a drag starting on a node moves the node, a
  drag starting on empty canvas pans the view — these must not fight
  each other or feel ambiguous)
- Add lightweight on-canvas information: node connection count, a small
  "N nodes · M connections" status readout in the toolbar, and hover/
  press feedback on nodes (subtle scale or glow on touch-down) so the
  canvas feels responsive and alive rather than static

### Bug 11.4 — Add/delete controls not working properly
**Required fix:**
- `+` (add node) must reliably open the node-type picker (per v1 spec)
  and place a new node at a sensible default position (center of current
  viewport, not always the same fixed canvas coordinate, which would
  stack new nodes on top of each other over repeated use)
- Delete: each node needs a real, reliable delete action — either a
  small `×` on the node itself (as partially visible in screenshot 3's
  "Intercept Trigger" node) that actually removes the node AND its
  connected edges (not leaving orphaned connection lines), or via the
  long-press contextual menu pattern (consistent with Bug 3.8's pattern
  — reuse the same contextual menu component here for consistency:
  `Edit`, `Duplicate`, `Delete`, `Disconnect all`)

### Bug 11.5 — NEW: Export capability
**Required fix:**
- Add an **Export** action (toolbar icon, e.g. `⬇`) that serializes the
  current flow graph to a portable JSON format
- Given the explicit "n8n-like" framing (both in the existing "Flow &
  Automation (n8n Engine)" screen title seen in screenshot 3 and the
  user's new automation request in §12), structure the export format to
  be **conceptually compatible with n8n's node/connection JSON shape**
  where reasonable (nodes array with type/position/parameters, a
  connections map) — this does not require full n8n format compliance,
  but should not invent an incompatible ad-hoc shape when a sensible
  precedent exists
- Export via standard Android share sheet (`Intent.ACTION_SEND`) or save
  to device via Storage Access Framework
- Add a matching **Import** action alongside it — export without import
  is only half useful, and the user's broader automation vision (§12)
  implies flows should be shareable/portable

---

## §12 — NEW: Deeplink Ecosystem + Trigger→Action Automation

This is described by the user as *"the most wanting system — an
ecosystem"* — treat this as the highest-priority new feature in this
revision, not a minor addition.

### 12.1 — Deeplink System (Telegram-style in-app task execution)
- Define a `requestr://` URI scheme (already established in v2 spec §
  Navigation Architecture for settings deeplinks — extend that same
  scheme system here to cover **actions**, not just navigation)
- Deeplinks should be able to **perform tasks**, not just open screens:
  - `requestr://action/run-api?id={apiId}` — executes a saved API
    request immediately
  - `requestr://action/start-intercept?apps={packageList}` — starts
    interception with a pre-set app filter
  - `requestr://action/run-flow?id={flowId}` — triggers a saved
    automation flow
  - `requestr://action/new-note?title={title}&body={body}` — creates a
    note directly
  - `requestr://action/run-tool?tool={toolId}&input={input}` — runs a
    tool (e.g. a decoder) and returns/displays the result
- These deeplinks must be real Android intent-filters (`<intent-filter>`
  with `android:scheme="requestr"` in the manifest, handled by a router
  Activity/composable that parses the URI and dispatches to the correct
  action handler) — testable via `adb shell am start -a
  android.intent.action.VIEW -d "requestr://action/run-api?id=123"`
- Document the full deeplink schema in **Settings → Deeplinks /
  Automation** (per §10.12) so the user can see and reference all
  available actions

### 12.2 — Trigger → Action Automation Engine (n8n-style, for Flow + APIs)
- Extend the Flow canvas's existing node system (already specced: API
  Node, Tool Node, Chat Node, Condition Node, Text Node, Output Node,
  Transform Node, Trigger Node — plus the newer Intercept Trigger node
  seen in screenshot 3) into a genuine **trigger-to-action execution
  engine**, not just a visual diagram that looks like automation without
  functioning as one
- **Trigger types** (what starts a flow):
  - Manual (user taps Run)
  - Intercept pattern match (as shown in screenshot 3: `.*/auth/login.*`
    — when a captured request's URL matches this pattern, fire the flow)
  - Scheduled (cron-like interval, via `WorkManager`)
  - Deeplink-invoked (per 12.1)
  - Webhook-style (app runs a tiny local listener the user can hit
    externally, if feasible — flag as stretch/optional if execution
    complexity is high, but do not silently drop it without noting the
    tradeoff)
- **Action execution**: each downstream node in a triggered flow must
  actually execute its defined behavior in sequence (respecting
  connections/edges as the execution graph), passing data between nodes
  (e.g. Intercept Trigger's captured request → Extract node's JSONPath
  applied to it → Output node displaying/saving the result) — this
  requires a real **flow execution runtime**, not just persisted visual
  state; this is the single most architecturally significant new
  component in this revision and should be planned/prototyped carefully
  rather than bolted on quickly
- Execution history/log per flow (so the user can see "this flow ran 4
  times today, here's what happened each time") — reuse patterns from
  the Intercept traffic log and Chats logs screens for visual consistency

---

## §13 — GLOBAL QUALITY BAR (Closing Directive)

Per the user's closing note, this revision is not just bug-fixing — it's
raising the entire app's execution quality:

- **Immersive animation throughout**: every state transition (screen
  navigation, theme switch, node drag, FAB toggle, drawer open/close,
  pill menu expand/collapse) should use intentional motion (spring
  physics preferred over linear easing for anything interactive/
  gesture-driven, per §5/§6) — audit for any instant/jarring state
  changes and replace with animated transitions
- **"Pixel-like blocks and themes"**: maintain the dense-but-organized
  editorial density established in the design tokens (v1 §9, refined
  themes in §2 here) — richness through considered detail (live stats,
  real data, subtle motion) rather than through visual clutter (the
  opposite of Bug 1.4's problem)
- **No feature ships half-working.** Every bug in this document was
  found by the user actually using the app — before considering any item
  in this spec "done," it must be verified against real device state,
  real permissions, real data, not just "the UI compiles and renders."
- This is explicitly meant to feel like **one coherent ecosystem** (the
  user's own words: *"not just aligned shit in a box"*) — cross-section
  consistency (shared contextual menu component, shared theme tokens,
  shared InterceptionState source of truth for both the FAB and the logo
  dot, shared deeplink system tying every section together) is the
  measure of success for this revision, more than any single screen's
  individual polish.

---

*End of requestr v2.1 Revision Spec*
*Read alongside: requestr_stitch_prompt.md (v1) and
requestr_ai_build_spec.md (v2 architecture)*
*Package: com.kailashhh.requestr*
