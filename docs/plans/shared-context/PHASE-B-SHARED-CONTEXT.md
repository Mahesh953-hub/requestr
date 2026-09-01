# Phase B + Design System — Shared Context for Build Agents

Read this file completely before you touch anything. It is the same briefing every
agent in this wave gets, so it explains the rules, the boundaries, and the facts that
have already been established so nobody re-litigates them.

Repo: `Mahesh953-hub/requestr`, checked out at `/code/Mahesh953-hub/requestr`,
branch `vorflux/master-plan-and-phased-design-implementation` (already checked out).

## 0. Git rules — absolute

Do **not** create branches, commit, stage, push, rebase, or stash. Leave every change
uncommitted in the working tree. The orchestrator owns all git operations. Multiple
agents share this one checkout, so a commit from you would capture other agents'
half-finished work.

## 1. Where the authority lives

In order of precedence:

1. **The user's own design-system brief**, reproduced verbatim in §5 below. This wins
   over every document if they disagree.
2. `/code/.plans/dossiers/design-system-current-state.md` — 1609 lines. Sections 1–8
   audit the current state with exact file/line references; the "Delta Spec — Target
   Design System" half resolves seven engineering questions (a)–(g) with concrete code
   shapes, hex palettes, and API-availability evidence. **This is your primary
   implementation reference for anything glass, motion, parallax, overscroll, or theme.**
3. `/code/.plans/dossiers/phase-b-implementation.md` — 1139 lines. Part 1 is the Home
   rebrand, Part 2 the zero-coverage screens, Part 3 navigation architecture.
4. `docs/plans/PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` — the checked-in audit (202 lines).
5. `docs/DESIGN-SYSTEM.md` — the frozen token contract.
6. `docs/designs/home/home_rebranding.md` + the two reference PNGs in that folder.

Where a dossier and an audit doc disagree, the dossier wins: it was written later and
against real code. Where a dossier names a file, **verify the file exists before trusting
it** — an earlier dossier in this project invented three filenames that do not exist.

## 2. Already-settled facts — do NOT re-open these

- **Phase 1 and Phase A are already landed and green** (commits `4cbb04e` and `5545086`).
  1499 `:app` tests + 50 `:sdk` + 6 `:sdk-noop` pass, and both the debug and release APKs
  build. Do not "fix" Phase A work; if you believe something there is wrong, report it
  rather than rewriting it.
- **Compose Foundation is pinned at 1.7.2.** `OverscrollConfiguration` and
  `LocalOverscrollConfiguration` exist. `rememberOverscrollEffect` **does not** — it is a
  1.8+ API and using it will not compile. This was verified by unzipping the resolved AAR,
  so do not take a doc comment's word over it.
- **Real blur is API 31+ only.** `minSdk` is 24. Use `Modifier.blur` / `RenderEffect` above
  API 31 and the documented gradient-translucency fallback below it. No third-party haze
  library is needed or wanted.
- **`DESIGN-SYSTEM.md` §14.7 currently documents "no real blur" as the contract.** This
  wave deliberately supersedes that for API 31+. Update §14.7 rather than leaving the doc
  contradicting the code.
- **The bottom nav pill is correctly ONE shared capsule**, not separate per-tab capsules.
  Only the **header** is specified as multiple independently-floating capsules. Do not
  "fix" the nav pill into separate capsules.
- **`gesturesEnabled` divergence is decided.** `navigation_showcase_dark.html` implies a
  hardcoded `false`; the code uses `settings.gestureNavigationEnabled` at
  `AppNavigationDrawer.kt:90`, matching `DESIGN-SYSTEM.md` §10.4. **Keep the user toggle.**
  It is documented as a deliberate divergence.
- **Existing glass modes** are `FROSTED_GLASS` (default), `SOLID_M3`, `OLED_BLACK`,
  `TRANSLUCENT`. The user's Liquid Glass / Clear Glass / Tune Glass are **net-new** and sit
  alongside these — do not delete the existing four.
- **Interception state has exactly one source of truth**: `InterceptionStateRepository.state`.
  Home's status card and the Power Modules "Intercept" card must both read it. Never add a
  second "is intercept configured" flag.
- **Onboarding stays Identity / Theme / Diagnostics.** Settled earlier.

## 3. Build rules — important, we share one Gradle project

Every build needs the environment sourced first, because non-interactive shells do not
read `~/.bashrc`:

```bash
cd /code/Mahesh953-hub/requestr && . ~/.requestr-env && ./gradlew <task> --no-daemon
```

**Allowed:** `:app:compileDebugKotlin --no-daemon`, and test runs narrowed with `--tests`.

**Forbidden:** `assembleDebug`, `assembleRelease`, `build`, `clean`, unfiltered
`testDebugUnitTest`, and anything touching adb, the emulator, or scrcpy. The orchestrator
runs those at the phase gate. Concurrent full builds serialise on the configuration cache
and file locks and produce failures that look like real compile errors but are not.

Two hard-won gotchas:

- **Never judge a Gradle run by piped output.** `./gradlew ... | tail` masks a real
  `BUILD FAILED` behind a zero exit code. Check the exit status directly.
- If you hit a KSP incremental-cache error or an unresolved reference in a file you do not
  own, that is almost certainly a sibling agent mid-write. Wait for their build to finish,
  delete `app/build/kspCaches/debug`, and retry once before concluding anything.

## 4. Testing conventions in this repo

- Unit tests live in `app/src/test/java/...`, JUnit4.
- Anything touching `org.json.*` needs `@RunWith(RobolectricTestRunner::class)` — the plain
  `android.jar` stub silently no-ops instead of failing, so a test can pass while asserting
  nothing. See `JsonFieldExtractorTest` for the established pattern.
- Room tests use `Room.inMemoryDatabaseBuilder`, never the shared file-backed singleton.
- Extract pure logic into a small helper file and unit-test that, rather than trying to
  test a composable. Every helper file added in earlier waves followed this pattern
  (`CapturedTrafficHelpers.kt`, `ReplacementValueUiHelpers.kt`, `SavedApisGrouping.kt`, …)
  and it is what makes this codebase testable at all. Follow it.
- **Do not write tests that read their own source file off disk to assert on source
  ordering.** One such test exists from an earlier wave and is being reconsidered; do not
  add more.

## 5. The user's design-system brief, verbatim

> **Bottom nav bar:** Floating pill-shaped modal bar anchored to the bottom edge; Liquid
> Glass blur that refracts background elements passing underneath; background parallax
> effect moving subtly independent of foreground content as a base layer for glassmorphism
> refraction; user-selectable glass modes **Liquid Glass, Clear Glass, Tune Glass**; tinted
> pill shape expands around the active tab's icon + text smoothly on selection.
>
> **Headers:** Floating icons become locked/attached as part of the header. On scroll down
> the greeting text fades/scrolls away; the stats pill subtly compresses or slides out of
> view depending on scroll depth, to maximize screen real estate.
>
> **Animations and physics (app-wide):**
> - `screen_transitions`: lateral slide-and-fade when switching bottom tabs (spatial awareness)
> - `scrolling_physics`: kinetic scrolling with elastic overscroll/bounce at top and bottom of lists
> - `micro_interactions`: standard Material ripple on cards/buttons/list items
> - `glassmorphism`: real-time blur + refraction of background content underneath floating
>   elements (nav bar, stats pill)
> - `theme_switching`: instantaneous color inversion Light to Dark, pastel to deep olive
>   tones, seamless

Two readings worth stating explicitly, because they are easy to get wrong:

- "**Instantaneous** … seamless" means **do not animate the theme swap**. No `Crossfade`, no
  `animateColorAsState` on theme colors. Theme switching is already instantaneous in this
  codebase; the job is to add the pastel/deep-olive palette pair and confirm nothing
  animates it.
- "Floating icons become **locked/attached as part of the header**" means the header's icons
  stop scrolling with content and become a pinned overlay. It does **not** mean merging them
  onto one filled bar — the transparency requirement below still holds.

## 6. The transparency requirement — hard, global

The floating header and the bottom nav pill must **never** sit on a traditional filled
block or shared toolbar surface. Each header capsule is its own floating glass shape with
genuinely transparent page background visible between them. The space between the nav/header
and the content beneath stays fully transparent, with no filled shape tied to the pills.

This is corroborated three ways: `home_rebranding.md` §1.4 ("no parent filled screen behind
them"), `navigation_showcase_dark.html`, and `DESIGN-SYSTEM.md` §8/§9.

## 7. Accessibility and settings gating — do not skip this

The app already has real user settings that this work must honour, not bypass:

- `LocalAnimationsEnabled` and the animation duration scale in `theme/AnimationConfig.kt`.
  **Every** animation you add must be gated through the existing `scaledTween` helper (or an
  equivalent that respects the same settings), so a user who has turned animations off or
  down actually gets that. An animation that ignores the setting is a bug.
- `theme/DensityConfig.kt` for density, and the corner-radius token.
- If a user disables animations, parallax and the tinted-pill expansion must settle
  instantly at their end state, not jump or flicker.

Also: blur and refraction are expensive. Anything running per-frame under a scrolling list
must not allocate on every frame, and the API-31 blur path must degrade to the fallback
cleanly rather than dropping frames on a weak device.

## 8. Honesty rules — the theme of this whole branch

Earlier waves removed a lot of UI that lied: an "Autosaved as draft" label with no autosave
behind it, a status badge showing "0" for rows that have no status code, a traffic list that
stayed empty because nothing was ever persisted, drawer links that showed a Toast instead of
navigating, drop-reason counters that were never incremented.

Hold the same line:

- **Never render a fabricated value.** If the data to back a mockup element does not exist,
  leave the element out and say so in your report. Do not show a zero, a placeholder, or an
  invented count. A real precedent: the Saved APIs mockup shows an "N environments" count and
  it was deliberately omitted because environments are modelled as one global list, not per
  collection — there is no honest number to display.
- **Never ship a control wired to nothing.** No Toast stand-ins, no disabled-looking button
  that silently does nothing. If a destination genuinely does not exist, an explicitly
  disabled row with a reason is acceptable; a fake one is not.
- **You are encouraged to rewrite code that is broken, weak, or behaves oddly.** Do not patch
  around bad code — replace it. This is an explicit instruction from the user.
- **Complete old-UI removal per screen.** No screen ships with old and new UI coexisting
  behind a flag or a partial overlay.

## 9. Screen real estate reality

The test device is **720x1600**. Three separate bugs in earlier waves were content that
existed but was physically unreachable: two bottom sheets with no `verticalScroll` on their
root `Column`, and an 11-item chip `Row` with no `horizontalScroll` that hid six options.

So: any container you add that can exceed the viewport gets a scroll modifier, and any
horizontal row of chips that can exceed the width gets `horizontalScroll`. Check your own
work against that height before you call it done.

## 10. Files the design-system wave owns exclusively

Earlier waves were forbidden from touching these precisely so one coherent pass could own
them. That pass is this one.

To create: `BlurModifier.kt`, `ParallaxState.kt`, `ParallaxBackground.kt`,
`StatsCompressiblePill.kt`.

To modify: `CollapsibleNavPill.kt`, `GlassActionPill.kt`, `FloatingCapsuleHeader.kt`,
`MainActivity.kt`, `HomeScreen.kt`, `Theme.kt`, `Color.kt`, `CommonComponents.kt`.

This wave also owns the **deferred per-screen header-compliance sweep**. The dossier's §4
table marks `HomeScreen` as confirmed non-compliant and seven other screens as "needs
verification" — verify each one rather than assuming.

Your own task message names the subset you own. Touching a file outside your subset is how
two agents overwrite each other, so if you believe you need one, say so in your report
instead of taking it.

## 11. Key coordinates already established

- 6 bottom-nav tabs: Home, Intercept, APIs, Tools, Flow, Music. Icon 19dp, label 12sp Bold
  SansSerif, defined at `CollapsibleNavPill.kt:76–83`. The pill is hosted around
  `MainActivity.kt:430–445` and collapses to a single FAB circle on scroll-down.
- The 11-route screen enum is at `MainActivity.kt:291–309`.
- Home's header is currently a plain `Row` at `HomeScreen.kt:148–191`, **inside** the
  scrollable `LazyColumn` — that is the structural change.
- There is no hamburger anywhere today; the drawer is swipe-only. The hamburger capsule is
  net-new.
- Greeting emoji is 🍃 (not 🌙). Search placeholder should read
  "Search APIs, notes, chats, tools..." — the code currently says
  "Search APIs, notes, tools, flow nodes...".
- Quick-action pills: the spec says 4, the code has 5–6. Reconcile to the spec.
- `SettingsScreen.kt` section headers sit at lines 718 and 1059.
- Active-tab tint currently `0.16f`; target `0.20f` plus a shadow animation in `NavPillTab`.

## 12. Emulator capability table — for anything you claim about runtime behaviour

| Capability | Available | Note |
|---|---|---|
| Outbound internet / DNS / TCP | yes | verified with a real request |
| ICMP to public IPs | **no** | sandbox egress filter — `ping` gives a false negative, never use it |
| HTTP proxy-tier capture | yes | port 9000, verified end to end |
| VPN / tun capture | **no** | `establish()` fails "Cannot create interface"; confirmed Redroid limitation, documented, not a bug |
| App-level `su` | no | live probe reads `RootState.UNAVAILABLE` |
| Shizuku host | no | not installed |
| Frida host | no | not running |
| Dynamic color (Material You) | API 31+ | Redroid is SDK 35, so the dynamic path is exercisable |
| Physical sensors | no | Redroid |

## 13. What to put in your final report

- What you implemented, per item, with file and line references.
- Anything you deliberately did **not** implement, and the honest reason — especially any
  mockup element you left out because no real data backs it.
- Any place you had to diverge from a doc, and which doc you followed instead.
- Files you touched outside your assigned subset, if any, and why.
- Your verification: the exact `compileDebugKotlin` result and the exact filtered test
  results, with counts.
- Anything you found that looks wrong but was out of your scope.

---

# ADDENDUM — decisions locked by the main agent (2026-09-01)

These were settled with the user directly. They are not open questions. Do not
relitigate them; if you believe one is wrong, report it back rather than
silently doing something else.

## Glass modes — the contract is ALREADY LANDED in the working tree

The main agent has already written the shared contract so you do not collide on
`UnifiedDataStore.kt`, `AppModels.kt` or `AppRepositories.kt` for these fields.
**It compiles. Build on it. Do not redefine any of it.**

What exists now:

- `canonicalGlassStyle()` in `data/prefs/UnifiedDataStore.kt` accepts six
  canonical values: `FROSTED_GLASS`, `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`,
  and the two new ones **`LIQUID_GLASS`** and **`TUNE_GLASS`**.
- **"Clear Glass" is NOT a new key.** The user chose to rename the existing
  translucent mode. So `TRANSLUCENT` stays the key and its user-facing LABEL
  becomes **"Clear Glass"**. The legacy `CLEAR_GLASS → TRANSLUCENT` alias stays
  exactly as it is. Anyone who adds a distinct `CLEAR_GLASS` canonical value is
  reintroducing a bug: every install that already stored the legacy string
  would silently jump to a different-looking mode.
- Four persisted Tune Glass values on `UserSettingsState`, with clamping
  setters already written on the settings repository:
  `glassTuneBlurDp` (0..48, default 24), `glassTuneTintOpacity` (0.15..1,
  default 0.90), `glassTuneBorderOpacity` (0..1, default 0.35),
  `glassTuneShadowDp` (0..24, default 6). Setters:
  `updateGlassTuneBlur`, `updateGlassTuneTintOpacity`,
  `updateGlassTuneBorderOpacity`, `updateGlassTuneShadowDp`.
  Defaults deliberately match `FROSTED_GLASS` so selecting Tune Glass for the
  first time changes nothing on screen — the user tunes outward from a familiar
  starting point instead of being dropped somewhere jarring.
- **Only `TUNE_GLASS` reads those four values.** Every other mode ignores them,
  so a user can tune, switch away to compare, and switch back without loss.

## Tune Glass behaviour

The user chose **user-tunable sliders**: Tune Glass exposes real controls for
blur strength, tint opacity, border and shadow intensity. It is NOT
content-adaptive and NOT a performance preset. It renders through the same path
as Liquid Glass but sources its four numbers from settings instead of tokens.
Whoever owns the Settings appearance UI must build these four sliders and show
them only when Tune Glass is the selected style — dead sliders that do nothing
because another mode is active are worse than no sliders.

## Blur tiering (unchanged, already decided)

Real blur is API 31+. On API 24–30 fall back to the gradient-and-hairline
treatment that already ships. Liquid Glass and Tune Glass remain SELECTABLE on
older devices and must still look deliberate there — just without true
refraction. Do not hide the modes, and do not fake refraction with a static
image.

## Mockups are the acceptance reference — actually open them

Phase A shipped without anyone visually diffing the result against the 62
mockups in `docs/designs/`. That will not repeat. Read the mockup files for
your own screens before you write code, and match them.

- Nav pill + header: `docs/designs/nav_header_pill/nav_header_pill_editorial_light.html`,
  `docs/designs/nav_header_pill/nav_header_pill_obsidian_dark.html`
- Navigation showcase: `docs/designs/navigation/navigation_showcase_dark.html`,
  `docs/designs/navigation/navigation_showcase_light.html`
- Home rebrand: `docs/designs/home/home_rebranding.md`,
  `docs/designs/home/home-section_prototype.png`,
  `docs/designs/home/home-section_prototype_detailed.png`

Note `navigation_showcase_dark.html` implies drawer `gesturesEnabled = false`
hardcoded. We are deliberately KEEPING the user toggle
(`settings.gestureNavigationEnabled`, `AppNavigationDrawer.kt:90`), which matches
`DESIGN-SYSTEM.md` §10.4. Do not hardcode it to false.

## Compose Foundation is 1.7.2 — a real API trap

Elastic overscroll must use `LocalOverscrollConfiguration` /
`OverscrollConfiguration`. **`rememberOverscrollEffect` does NOT exist in 1.7.2**
(it is a 1.8+ API). This was verified by unzipping the actual AAR. Using it will
not compile. Do not "fix" a compile error by bumping the Compose BOM.

## Greeting emoji

The Home greeting emoji is **🍃**, not 🌙 and not ☀️.
