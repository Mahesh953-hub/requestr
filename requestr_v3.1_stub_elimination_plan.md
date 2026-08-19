╔══════════════════════════════════════════════════════════════════════════╗
║  requestr — v3.1 REVISION SPEC                                           ║
║  "REMOVE ALL STUBS" PASS + LACKING-FEATURES ROADMAP                      ║
║  NATIVE ANDROID APP · targets API 28–37 · Kotlin/Compose                 ║
║  Package: com.kailashhh.requestr                                         ║
╚══════════════════════════════════════════════════════════════════════════╝

## ⚠️ MANDATORY DIRECTIVE — READ BEFORE TOUCHING CODE

**This is the second QA pass on this codebase. The first pass (v2.1) found
demo/stub UI pretending to be real functionality. This pass found the SAME
CLASS OF BUG survived, spread across new areas (Setup Wizard, Settings,
API Testr, Certificate management). This is now the single highest-priority
directive in this project:**

> **A stub is not an acceptable deliverable state for ANY feature in this
> app, at ANY point going forward. If a control is rendered on screen, it
> must be backed by real state, perform a real action, and produce a real,
> visible, correct result. If a feature cannot be completed in this pass,
> it must be REMOVED from the UI entirely (not shown greyed-out, not shown
> "coming soon" unless explicitly designated as such in this doc) rather
> than shipped as a fake control that misleads the user into thinking it
> works.**

**Definition of a stub, for this document's purposes:** any UI element
(toggle, button, chip, slider, dropdown) that:
- Changes its own visual state on tap but does not change any real
  underlying app behavior, OR
- Reads from a hardcoded/sample value instead of live device/app state, OR
- Claims success ("Granted," "Exported," "Saved," "Active") without the
  underlying operation actually having been verified to succeed, OR
- Exists in the UI with no wired handler at all (visually present,
  functionally inert)

**For every single item below, the implementing agent must, before
marking it done: (1) trigger the control on a real device, (2) verify
the real-world side effect occurred (permission dialog actually appeared
and system permission state actually changed; file actually exists on
disk after export; root shell command actually executed; etc.), (3) only
then consider it fixed. UI compiling and rendering correctly is not
sufficient evidence of completion — this exact false confidence is what
produced this bug list twice in a row.**

Cross-reference documents (still valid, read alongside this):
- `requestr_stitch_prompt.md` — v1 UI/screen detail reference
- `requestr_ai_build_spec.md` — v2 native architecture + interception spec
- `requestr_v2.1_revision_spec.md` — first bug-fix pass (some items in
  that document were reportedly fixed — see release summary below for
  confirmed-done list; do not re-verify those unless a regression is
  found, focus effort on this document's new findings)

**Confirmed done as of v3.0 release** (per user-provided release notes,
GitHub `v3.0` tag): all 6 capture engines, JWT decoder, Flow executor,
replay button, background service, semantic search, 6-tab inspector,
code exporters, environments, traffic classification, CA whitelist, root
module generator, capture watcher. Treat these as working baselines
unless this pass's testing reveals a regression.

**Explicitly deprioritized:** Crash reporting / Firebase Crashlytics
wiring (item #12 from the pending list) — skip entirely for this pass,
do not implement.

---

## PART A — CRITICAL STUB FIXES (First Priority — Trust-Breaking Bugs)

These are bugs where the app actively lies to the user about its own
state. Fix these before any new feature work — a user cannot trust any
part of this app while these exist, because they can't tell real
controls from fake ones anywhere else either.

### A1 — Setup Wizard: Theme selection collision bug
**Observed:** Theme collision bug on the setup screen (exact symptom not
fully detailed by user beyond "theme collision" — likely: selecting a
theme during setup doesn't match what actually renders afterward, or two
theme states fight each other, e.g. system dark-mode detection
overriding the user's explicit wizard selection, or the wizard's local
preview theme leaking into the persisted app theme incorrectly).

**Required fix:**
- Audit every place theme state is read or written: wizard-local preview
  state, `DataStore` persisted value, any `isSystemInDarkTheme()` check,
  and the root `MaterialTheme`/custom theme provider at app root.
- There must be **exactly one source of truth** for the active theme,
  read once at app root, above `setContent {}` or via a gated splash
  state (per v2.1 spec Bug 1.2 — re-verify this was actually implemented
  correctly, since a "collision" strongly suggests it wasn't, or was
  implemented but a second competing theme-state write path was
  introduced elsewhere, e.g. in the new Settings work).
- Selecting a theme in the wizard must write to the exact same
  persistence key/mechanism that Settings' theme picker writes to —
  if these are two different code paths writing to two different
  storage locations, that IS the collision bug. Unify to one write path.
- Test explicitly: select a theme in wizard → finish wizard → app shows
  that theme → force-stop app → reopen → same theme. Then: change theme
  in Settings → force-stop → reopen → Settings' choice persists and
  wizard's earlier choice does not "come back."

### A2 — Setup Wizard: Permissions are entirely fake
**Observed:** Tapping "Grant" on any permission row simply flips that
row's own UI state to "granted" — no Android system permission dialog is
ever triggered. A "Skip" option additionally appears below, compounding
the fakeness (skip implies the permission flow is optional theater, not
a differentiator from actually running the real permission request).

**Required fix — rebuild this screen from scratch with zero shortcuts:**
- Each permission row's "Grant" action must call the real Android
  permission API: `ActivityResultContracts.RequestPermission()` (or
  `RequestMultiplePermissions()` for a batch) launched from the hosting
  Activity/Fragment, triggering the actual OS permission dialog.
- The row's displayed state (`Not Granted` / `Granted` / `Denied` /
  `Denied — Don't ask again`) must be derived from the **real callback
  result** of that system dialog, cross-checked against
  `ContextCompat.checkSelfPermission()` on screen resume (in case the
  user backgrounds the app and changes permissions via system Settings
  mid-flow) — never set client-side without that real confirmation.
- If the user denies a permission required for a feature to function,
  do not silently allow "Skip" to look identical to "Granted." If Skip
  must exist at all (optional permissions only — e.g. notifications, if
  the app can reasonably function without them), it must visually and
  behaviorally read as **"Skipped — this feature won't work until
  granted later in Settings,"** not sit as an unlabeled button that
  quietly bypasses the row with no consequence shown.
- Permissions that are **not optional** for baseline app function should
  not offer Skip at all — block wizard progression with a clear message
  instead ("requestr needs storage access to save your API collections —
  please grant to continue") plus a way to open system App Info settings
  directly if the user wants to grant manually.
- List exactly which permissions this screen must request (align with
  actual features, not a padded list): storage/media (`READ_MEDIA_AUDIO`
  Android 13+, `READ_EXTERNAL_STORAGE` below), notifications
  (`POST_NOTIFICATIONS` Android 13+). VPN consent (`VpnService.prepare()`)
  is correctly NOT part of this screen (per v2.1 spec — remains
  contextual, requested when interception is first started) — do not
  add it here.

### A3 — Setup Wizard: Logo is placeholder/unfinished
**Observed:** User considers the current wizard logo treatment
unacceptable for the app's first impression.

**Required fix:**
- The Setup Wizard's welcome/branding screen must use the **final
  `requestr.` wordmark** as specced in full letter-by-letter detail in
  `requestr_v2.1_revision_spec.md` §8 (soft-entry `r`, editorial `e`,
  signature bowled-descender `q`, bridging `u`, repeated `e`, high-
  contrast italic `s`, tension-ending `t`, terminal `r`, and the
  mandatory live-state dot).
- If the final vector wordmark asset does not yet exist, this is now
  **blocking** — do not proceed with placeholder text-in-a-system-font
  standing in for it silently. Either: (a) produce/import the actual
  SVG-to-vector-drawable wordmark asset before continuing this pass, or
  (b) if genuinely not yet possible in this work session, use a
  reasonable high-quality editorial italic serif as an explicitly
  temporary stand-in AND surface this to the user directly in the PR/
  commit notes as "wordmark placeholder — real asset pending," never
  presented as finished.
- On the wizard's welcome screen specifically, the dot's "live" behavior
  (green=active/red=inactive interception state) is moot before
  onboarding completes (interception can't be active yet) — render the
  dot in a neutral/default state here (e.g. red/inactive, since
  interception genuinely is inactive at this point — this is still
  "real," not fake, since it reflects true state, just an early one).

---

### A4 — API Testr: Save / Star have no real effect or feedback
**Observed:** Save and Star (favorite) controls in the API Testr screen
show no confirmation, no persistence hint, and it's unclear whether
they do anything at all.

**Required fix:**
- **Save**: tapping Save must write the current request configuration
  (method, URL, headers, query params, body, auth, scripts — full
  state per the v1 API Testr spec) to the real persistence layer (Room
  DB `SavedApiRequest` entity or equivalent). On success, show a real
  confirmation — a `Snackbar`/toast ("Saved to {folder name}") — and
  update any visible "unsaved changes" indicator (dot/asterisk on the
  tab, per v1 spec's tab status dots) to reflect saved state. If save
  fails (e.g. storage error), show a real error message, not silence.
- **Star/Favorite**: tapping star must toggle a real `isFavorite`
  boolean field on the saved request's DB record, and that state must
  be what actually populates the "Favorites" filter/tab elsewhere in
  the APIs section (per v1 spec's `Favorites` tab) — if that tab is
  currently also stub/empty regardless of starred items, fix that
  linkage too, it's the same underlying bug.
- Verify end-to-end: star a request → navigate to APIs home → Favorites
  tab → starred request appears there. Save a request → force-stop app
  → reopen → request still exists in its folder with all fields intact.

---

## PART B — SETTINGS PAGE: FULL STUB AUDIT

The user's message lists a long chain of settings that render but do
nothing. Treat **every single settings row below** as requiring the
same fix pattern: **real backing state (DataStore/Room/SharedPreferences)
+ real effect on app behavior + verified on-device before considered
done.** Where a setting genuinely cannot be implemented meaningfully in
this pass (e.g. requires a deeper subsystem not yet built), it must be
**removed from the UI**, not left as a switch that flips with no effect.

### B1 — Theme/Accent architecture bug (root-level, high priority)
**Observed / required correction to the mental model:**
- **Light and Dark are root-level themes.** They are not accent-derived,
  not sub-options of anything — they are the two fundamental modes,
  alongside the 7 named themes from v2.1 spec §2 (Obsidian, Dusk,
  Terracotta, Sage, Rosewood, Slate Editorial, Parchment) — **9 total
  root-level themes, full stop.**
- **Accent color is a property applied ON TOP of whichever root theme is
  selected — it must never change or override which root theme is
  active.** Currently, per the user, the architecture appears inverted
  (accent selection is somehow driving/changing the theme) — this is
  backwards and must be corrected structurally, not just visually.
- Correct data model: `selectedTheme: ThemeId` (one of the 9) +
  `accentOverride: Color?` (nullable — null means "use this theme's
  default accent," non-null means the user picked a custom accent to
  layer onto their chosen root theme). These are two independent,
  orthogonal settings. Changing accent must never change
  `selectedTheme`. Changing `selectedTheme` may reset `accentOverride`
  to null (reasonable — new theme, back to its default accent) but the
  reverse must never happen.
- **Light theme selection state bug**: user reports the Light theme
  option shows no selection outline/indicator even when it IS the
  active theme — meaning the picker UI can't correctly show Light as
  "currently selected." This is almost certainly a symptom of the same
  root-cause architecture bug above (if accent state is incorrectly
  entangled with theme identity, an equality check like `selectedTheme
  == ThemeId.Light` may be failing due to some accent-related field
  polluting the comparison, e.g. comparing entire theme objects
  including a currently-mismatched accent field rather than comparing
  theme IDs alone). Fix the data model first (per above); this
  symptom should resolve as a direct consequence. Verify explicitly
  afterward: select Light theme → reopen theme picker → Light shows
  selected-state outline/highlight correctly.

### B2 — PiP (Picture-in-Picture) mode doesn't work
**Required fix:** If PiP is meant to apply to a specific screen (likely
a video/preview context, or possibly intended for the Music mini-player
or a live traffic monitor overlay — clarify against actual intended use
in the codebase/prior specs), implement using the real
`PictureInPictureParams`/`enterPictureInPictureMode()` Android API,
declared correctly in the manifest (`android:supportsPictureInPicture=
"true"`, `android:resizeableActivity="true"` on the relevant Activity).
If, on inspection, PiP has no clear applicable use case in this app's
actual feature set, **remove the toggle entirely** rather than leave a
switch with no target behavior — do not implement PiP just to satisfy
having a working toggle if there's nothing for it to actually do.

### B3 — Typography / Surface / Border theme sub-settings are stubs
**Required fix:** These must actually restyle the app's rendered
`Typography`/`Shapes`/border-drawing tokens in the real Compose
`MaterialTheme` (or custom theme wrapper), not just record a preference
value that nothing reads. Specifically:
- **Typography** setting(s) must change the actual `TextStyle`/font
  family/weight applied app-wide (tie into the font choice setting
  already specced in v1 — verify that setting is ALSO real and not
  itself a stub, since "Typography" here may be a duplicate/overlapping
  control that needs consolidating rather than two separate stub
  controls doing the same conceptual thing).
- **Surface** setting must change actual card/sheet/surface background
  treatment (e.g. elevation, blur/glass intensity per the glassmorphic
  design language) — verify visually that changing this setting
  produces a visible difference on a real card in a real screen.
- **Border** setting must change actual border width/opacity/color
  rendering on cards, inputs, chips — same verification standard.
- If any of these three don't have a clear, currently-existing rendering
  hook to attach to (i.e. the app doesn't currently vary these
  attributes anywhere), that's a sign the setting was added before the
  underlying capability existed — either build the underlying capability
  now, or remove the setting until it does.

### B4 — Haptic feedback is a stub
**Required fix:** Wire real `HapticFeedback` calls (Compose's
`LocalHapticFeedback.current.performHapticFeedback(...)`, or
`View.performHapticFeedback()` if in a View-interop context) to actual
interaction points app-wide — button presses, FAB toggle (the
Intercept start/stop FAB is a natural strong candidate), successful
save/export actions, node connections in Flow, long-press context menu
open. The Settings toggle for this must genuinely enable/disable these
real calls (check the toggle's persisted state before firing haptic
feedback at each call site — a simple shared "hapticsEnabled" flow
checked once per call site, not re-plumbed per screen ad hoc).

### B5 — Gesture navigation setting — undefined behavior
**Observed:** User does not know what this setting is supposed to do,
correctly identifying it as meaningless in its current state.

**Required fix:** Define its actual scope before implementing anything:
most plausibly, this should toggle between the new gesture-driven left
drawer (v2.1 spec §6 — edge-swipe to open) being **enabled vs. requiring
only the tap-affordance/icon** (for users who find edge-swipe gestures
prone to accidental triggering, a common real complaint with edge-swipe
drawers conflicting with system back-gesture navigation on gesture-nav
Android devices). If this is the intended scope, implement it as exactly
that toggle, wired to actually disable the `pointerInput` drag detector
when off. If a different scope was originally intended, document what it
actually does in the setting's subtitle text so it's never ambiguous
again — every settings row must have a one-line description stating
its real, specific effect (this app already does this pattern correctly
elsewhere, e.g. the Rikka-referenced assistant settings — apply that
same descriptive-subtitle discipline here).

### B6 — Tablet mode — undefined behavior
**Required fix:** Same treatment as B5. Most plausibly this should
toggle whether the app applies an adaptive/multi-pane layout on larger
screens (tablets, foldables unfolded) vs. forcing the phone-width
single-pane layout regardless of screen size. This overlaps directly
with pending item #9 (Landscape/tablet layout) in the roadmap (Part C)
— if that feature isn't built yet, this setting has nothing to control
and **must be removed until #9 is actually implemented**, then
reintroduced as a real, functional toggle at that point. Do not ship a
tablet-mode toggle with no tablet-mode layout behind it.

### B7 — Compact / Comfortable density settings — no effect
**Required fix:** These must actually change real spacing/padding
tokens app-wide (e.g. list item height, card padding, section gaps) —
tie into a `Density` enum (`Compact` / `Normal` / `Comfortable`) read by
shared spacing constants/modifiers used throughout the component
library, not a value that's stored and never read. Verify visually: a
list screen (e.g. Intercept traffic log, or APIs collection list) must
visibly show tighter/looser row spacing when this setting changes.

### B8 — Animation settings in UI Preferences — stub
**Required fix:** Must actually gate whether Compose animations
(`animateContentSize`, `AnimatedVisibility`, spring-based transitions
specced throughout v2.1 — pill nav stagger, drawer spring-physics, FAB
color cross-fade, node drag, theme transitions) run at full effect vs.
reduced/instant. Respect this as a real accessibility-adjacent setting:
if set to "Reduced" or "Off," transitions should either skip animation
entirely (instant state change) or use much shorter/simpler duration —
implement via a shared `LocalAnimationsEnabled`/duration-scale
composition local read by the app's animation utility functions, not
per-screen reimplementation.

### B9 — "Traffic & Network" traffic capture setting — no defined effect
**Required fix:** Clarify against the real Intercept engine settings
(v2.1 §3, §10.3): if this is meant to be the **default capture tier
preference** (auto-select Root>Shizuku>VPN vs. force a specific tier for
testing) or **default proxy port** or **default app-filter behavior**
(remember last selection vs. always re-ask) — these were already
specced in v2.1 §10.3 as required Settings content. If this "Traffic &
Network" section is a separate, redundant, or ambiguous duplicate of
that already-specced content, consolidate into one clearly-scoped
section rather than maintaining two settings surfaces for the same
underlying capture-engine configuration. Whatever the final scope, it
must control real values actually read by the real Intercept pre-flight
sequence (v2.1 Bug 3.4) at the moment interception starts.

### B10 — "Advanced Features" section — stub
**Required fix:** Audit every item currently inside this section
individually against this document's stub definition. Items with no
real backing implementation must be removed from the UI. Items that
represent genuinely real advanced capability (e.g. deeplink management
from v2.1 §12, root/Frida tooling shortcuts) should be relocated to
their properly-scoped section (Intercept settings, Automation settings)
rather than dumped into a catch-all "Advanced" bucket with unclear
individual function — a settings page section should never be a junk
drawer of unverified toggles.

### B11 — Certificate settings never function
**Required fix — full rebuild of CA cert settings screen:**
- **Export never works**: tapping export must produce a real `.crt`/
  `.pem` file via the Android share sheet (`Intent.ACTION_SEND`) or SAF
  file write — verify the resulting file is a valid, openable
  certificate (test by actually opening/importing the exported file
  somewhere, e.g. into a browser's cert import flow, to confirm it's
  not a corrupt or empty file).
- **Root is present in UI but never actually used**: per v2.1 spec §3.1
  and §3.4, root availability must be genuinely probed
  (`su`-handshake via libsu) and, when granted, genuinely used for
  system-trust-store CA installation and root-tier capture — if the
  current build shows a "Root" option/hint in cert settings but the
  actual install-to-system-store code path was never wired to fire on
  a real root shell command, that's exactly this bug. Trace the full
  path from "user taps Install to System Store" through to an actual
  `su -c "cp ... /system/etc/security/cacerts/"`-equivalent operation
  (or the correct modern APEX-path equivalent for Android 14+, per v2
  spec's noted path difference) executing and being verified to have
  succeeded (re-read the target location after write to confirm the
  cert is actually there, don't just assume the shell command "probably
  worked").
- **Hints never show**: any contextual hint text (e.g. explaining why
  interception isn't working without CA install, or explaining the
  user-store vs. system-store distinction) that's supposed to appear
  conditionally must actually be wired to the real state it's supposed
  to reflect — audit for hint `Text` composables that are either always
  hidden, always shown regardless of state, or entirely disconnected
  from a `when`/conditional on real cert-store-check state.

### B12 — "Open full app settings" appears multiple times
**Required fix:** Audit every screen/mini-settings-sheet across the app
(per the v1 "in-page mini settings → deeplink to full settings" pattern)
for duplicate or redundant "Open full app settings" entries — there
should be exactly **one** such deeplink affordance per mini-settings
context (e.g. Intercept's mini-settings sheet has one link to full
Settings' Intercept section; it should not additionally show a second,
generic "Open full app settings" link alongside it pointing somewhere
less specific). Consolidate to one clear, correctly-scoped deeplink per
context, matching the architecture already defined in v1/v2.1 — this is
a navigation-architecture cleanup, not a new feature.

### B13 — Theme icon appears everywhere — must be scoped to one location
**Required fix:** Per explicit instruction: the theme-switch icon/
affordance must **only** appear in exactly one place: **on the Home
screen, positioned immediately to the left of the profile/avatar icon**
(top-right area of Home, per the v2.1 §1.4 Home redesign's header row:
wordmark left, avatar right — the theme icon slots between them,
immediately adjacent to the avatar). Remove this icon from every other
screen/toolbar/settings-sheet where it currently also appears (the
gesture-drawer's inline quick theme-swap row from v2.1 §6 is a
**different, intentional, already-specced surface** — that one stays;
this bug is about the icon proliferating into places it was never
supposed to be, e.g. per-section top bars, redundant settings rows,
etc.). One canonical entry point on Home, one inline quick-swap in the
drawer — nothing else.

---

## PART C — LACKING FEATURES ROADMAP (Combined Single-Pass Plan)

The user has asked for a plan to implement **all 11 remaining pending
features in one go** (excluding #12, Crash reporting, explicitly
skipped). Below is a sequenced plan — ordered by dependency and risk,
not by the original list's numbering — so the implementing agent has a
concrete build order rather than 11 disconnected tickets.

**Sequencing logic:** infrastructure/foundation items first (things
other features depend on), then self-contained features roughly ordered
easy→hard, with UX-polish items last since they benefit from being
applied across a more feature-complete app rather than being built then
immediately disrupted by later structural changes.

### Phase 1 — Foundation (do first, other items depend on these)

**C1. Single DataStore migration** *(originally #11)*
- Consolidate all scattered `SharedPreferences` usage across the app
  into one unified `DataStore<Preferences>` (or `DataStore<MyProtoType>`
  if moving to Proto DataStore for stronger typing — recommended given
  the number of distinct settings domains this app now has: theme,
  accent, density, animations, haptics, intercept defaults, music
  source, flow defaults, etc.)
- This directly unblocks correct fixing of Part A/B's theme-collision
  and settings-stub bugs — if settings are currently split across
  multiple inconsistent storage mechanisms, that fragmentation is
  itself a likely contributor to state bugs like A1/B1. **Do this
  before or alongside Part A/B fixes, not after** — fixing stubs on top
  of a fragmented storage layer risks re-introducing the same class of
  bug.
- Migration must preserve existing user data (write a one-time migration
  routine reading old `SharedPreferences` keys and writing them into the
  new unified store on first launch post-update, then never reading the
  old location again).

### Phase 2 — Core Testing/Automation Features

**C2. Request chaining** *(originally #3)*
- Extract variables from a response (JSONPath or simple key-path
  selector UI on the response body, similar to the existing Flow
  Transform node's extraction pattern — reuse that extraction logic
  rather than building a second, different one)
- Store extracted values as named variables scoped to the current
  Environment (ties into the already-confirmed-working Environments
  feature — extend it, don't parallel-build a separate variable system)
- Allow referencing `{{variableName}}` syntax in a subsequent request's
  URL/headers/body fields within the same collection/chain
- UI: a "Chain" indicator/link between two saved requests showing the
  extraction→injection relationship explicitly (not just implicit
  variable-name matching with no visual trace)

**C3. Test assertions / test runner** *(originally #2)*
- New data model: `Assertion` (target: status code / header value /
  body JSONPath value / response time; operator: equals / contains /
  greater-than / less-than / matches-regex; expected value)
- Attach one or more assertions to a saved request
- "Run Tests" action executes the request, evaluates each assertion
  against the real response, shows pass/fail per assertion with actual
  vs. expected values displayed
- Test runner mode: run all assertions across a folder/collection in
  sequence, summary report (X passed / Y failed) — this naturally
  composes with C2 (chained requests can each carry their own
  assertions)

### Phase 3 — Protocol-Level Features

**C4. WebSocket frame inspector** *(originally #1)*
- Parse raw WS frames per RFC 6455 framing (FIN bit, opcode, mask,
  payload length variants, masking key) from the byte stream already
  being captured by the existing intercept engines
- UI: dedicated frame-by-frame view within the request inspector (extend
  the existing 6-tab inspector with a WS-specific tab that only appears
  when the connection is a WS upgrade) — show frame direction (sent/
  received), opcode type (text/binary/ping/pong/close), payload preview,
  timestamp
- This is explicitly binary-protocol work — allocate real testing time
  against actual WS traffic (e.g. a test WebSocket echo server) rather
  than assuming correctness from code review alone, since framing bugs
  are easy to get subtly wrong (mask handling in particular)

**C5. GraphQL support** *(originally #4)*
- Detect GraphQL requests (POST with `query`/`mutation` body shape, or
  user-designated endpoint) in both API Testr (manual) and Intercept
  (captured) contexts
- Schema introspection: if the target endpoint supports the standard
  `__schema` introspection query, fetch and cache it, use it to power
  autocomplete in a query builder
- Query builder UI: structured query construction (select fields from
  introspected schema) as an alternative to raw text query editing (raw
  editing must remain available regardless — don't force the builder)
- Variables panel: key-value JSON editor for GraphQL variables, sent
  alongside the query per GraphQL's standard request shape

### Phase 4 — Analytics & Comparison Views

**C6. Traffic analytics dashboard** *(originally #5)*
- Requires a charting library — evaluate and adopt one Compose-
  compatible option (e.g. Vico, or a lightweight custom Canvas-based
  chart set matching the app's editorial design language rather than a
  generic Material chart look) — pick one and use it consistently,
  don't mix charting approaches across this dashboard
- Metrics to surface, computed from real captured traffic data (Room DB
  query aggregations, not fabricated): throughput over time
  (requests/sec or bytes/sec, windowed), top-talkers (grouped by
  domain, sorted by request count or total bytes), latency percentiles
  (p50/p90/p99 response time, computed from real captured timing data)
- This is a read-only analysis view over existing captured data — no
  new capture-side work needed, purely a query + visualization layer

**C7. Diff view for replay** *(originally #6)*
- When replaying a saved/captured request (using the already-confirmed-
  working Replay button), show a side-by-side (or unified, user
  preference) diff between the original captured response and the new
  replay response
- Diff at both the raw-text level (line diff, standard algorithm e.g.
  Myers diff) and structurally where applicable (JSON body: highlight
  changed/added/removed keys specifically, not just raw text line
  diff, which is noisy for reformatted-but-equivalent JSON) — implement
  structural diff as the default for JSON content-type responses,
  fall back to text diff otherwise

### Phase 5 — UX Polish (apply last, across the now-more-complete app)

**C8. Undo on InterceptScreen** *(originally #7)*
- Standard undo-stack pattern for destructive actions on this screen
  (delete captured entry, delete rule, clear all) — a `Snackbar` with
  an "Undo" action following the delete, holding the deleted item in
  memory briefly before actually committing the deletion to the DB
  (or a soft-delete flag reverted on undo) — apply consistently to
  every destructive action already present on this screen per the
  v2.1 long-press context menu spec (§3.8)

**C9. Keyboard shortcuts** *(originally #8)*
- Scope to contexts where a physical/Bluetooth keyboard is plausible
  (tablet users, Chromebook/DeX users — ties naturally to C11's tablet
  work) — define a real shortcut map (e.g. `Ctrl+S` save in API Testr,
  `Ctrl+Enter` send request, `Ctrl+F` search/filter) using Compose's
  `Modifier.onKeyEvent`/`onPreviewKeyEvent` at appropriate scopes
- Document the shortcut map somewhere discoverable in-app (Settings, or
  a `?` help overlay) — a shortcut nobody can discover has low value

**C10. Accessibility labels** *(originally #10)*
- Full audit pass: every interactive composable (icons, buttons, FABs,
  chips without visible text, images) needs a correct, specific
  `contentDescription` — not a generic placeholder ("button", "icon")
  but a real description of that control's actual function ("Start
  traffic interception", "Delete saved request", "Toggle dark theme")
- Verify with TalkBack enabled on a real device for at least the core
  navigation flow (Home → each major section → back) as a sanity check,
  not just presence-of-string-resource as the completion bar

**C11. Landscape / tablet layout** *(originally #9)*
- Adaptive layout using `WindowSizeClass` (Compact/Medium/Expanded)
  to drive real layout changes on larger screens/landscape orientation:
  multi-pane where it makes sense (e.g. APIs collection list + detail
  side-by-side on Expanded width, rather than single-pane push-
  navigation which wastes space on a tablet)
- This is the feature that B6's "Tablet mode" setting should end up
  controlling once built — implement this first, then wire B6's toggle
  to something real (or, if adaptive layout should simply always apply
  automatically based on `WindowSizeClass` with no user toggle needed
  at all, then B6's setting should be **removed** rather than kept as
  a redundant manual override — decide this explicitly rather than
  defaulting to "keep the toggle just in case")

---

## PART D — VERIFICATION CHECKLIST (Required Before Calling Any of This Done)

For every item in Parts A, B, and C, before marking complete:

1. ☐ Built and run on a **real physical device** (not emulator-only,
   given how much of this involves real permissions, root, VPN, and
   system cert stores that behave differently or not at all on emulators)
2. ☐ The real-world side effect was independently verified (permission
   dialog appeared and system state changed; file exists on disk with
   valid content; DB record exists with correct values; root shell
   command's target file was actually modified; etc.) — not inferred
   from the UI alone
3. ☐ App was **force-stopped and reopened** after the change to confirm
   persistence survives process death, for anything settings/state-
   related
4. ☐ No other screen/flow regressed as a result of the fix (spot-check
   adjacent functionality, especially anything sharing the newly-
   unified DataStore from C1)
5. ☐ If a feature could not be completed to this standard in this pass,
   it was **removed from the UI**, not left rendered in a broken/stub
   state

---

## IMPLEMENTATION NOTES — Design decisions recorded during Part C build-out

**C2 (Request chaining) — Environments picker/editor UI: not built, by
design.** The plan text for C2 requires: (a) extraction of variables from
a response into named variables scoped to the active Environment, (b)
`{{variableName}}` referencing in a subsequent request's URL/headers/body,
and (c) a visible "Chain" indicator/link between two saved requests. It
does **not** require a dedicated Environments-list picker/editor UI — the
app's existing `EnvironmentRepository.active()` / `.substitute()` call
path already resolves and applies `{{var}}` templates against whichever
Environment is currently active, so extraction-writes-to-active-Environment
plus substitution-reads-from-active-Environment satisfies the spec without
a separate picker screen. Building one would be new scope beyond what C2
asks for. Verified end-to-end on-device (real network round trip, not a
mock): a producer request (`GET https://httpbin.org/uuid`) extracted a
UUID into `{{myUuid}}`; a consumer request
(`GET https://postman-echo.com/get?token={{myUuid}}`) substituted the
exact extracted value and received a real `200 OK` echoing it back; the
Saved APIs screen showed "⬆ Feeds variables to: ..." / "⬇ Uses variables
from: ..." indicator pills on the two cards; the Environment variable and
both saved requests (including `chainExtractions`) survived a force-stop
+ relaunch; Settings (C1's unified DataStore) showed no regression after
the Room schema bump.

---

*End of requestr v3.1 Revision Spec*
*Read alongside: requestr_stitch_prompt.md (v1), requestr_ai_build_spec.md
(v2 architecture), requestr_v2.1_revision_spec.md (first bug-fix pass)*
*Package: com.kailashhh.requestr*
*Directive: REMOVE ALL STUBS. Real state, real effect, real verification
— or the control does not ship.*
