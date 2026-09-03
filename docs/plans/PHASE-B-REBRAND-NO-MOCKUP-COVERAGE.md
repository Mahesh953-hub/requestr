# Phase B — Rebrand / Redesign Screens With No Literal Mockup Coverage

**Status: PENDING (read-only audit; no code has been changed, committed, or pushed)**
**Repo:** `Mahesh953-hub/requestr` · branch `main` · HEAD `81a7e25`

## 1. Executive Summary

Phase A (see `plan-requestr-phaseA.md`) covers every screen where a literal HTML mockup exists —
54 files that can be ported near-verbatim into Compose. Phase B covers the remainder:

- **`home/`** — the one `docs/designs/` subfolder with **no HTML mockup at all**, only 2 reference
  screenshots (`home-section_prototype.png`, `home-section_prototype_detailed.png`) plus a written
  modification spec (`home_rebranding.md`). This is a real, well-specified rebrand, just not
  encoded as literal markup — it belongs in Phase B by construction, not because it's low-priority.
- **Screens/components with zero design coverage of any kind** in `docs/designs/` — enumerated in
  §3 below. These have no mockup, no screenshot, no written spec; Phase B's job for these is to
  apply the same design language (tokens, nav/header rules, identity) that governs everything else,
  not to invent an unrelated look.

**This document does not mention any specific hosting/tooling platform by name** and describes UI
structure conceptually, per the audit's constraints.

## 2. Home Screen Rebrand (the only mockup-adjacent Phase B item)

Source: `docs/designs/home/home_rebranding.md` (356 lines, fully read) + both reference
screenshots (viewed). This is a **modification spec, not a rebuild** — it explicitly preserves the
current Home layout pixel-for-pixel except for one structural change plus two behaviors tied to it.

### 2.1 What changes (in scope)

**§1 — Header conversion (the only structural change).** Today, `HomeScreen.kt` renders its header
as a single `Row` **inside the scrollable content** (confirmed by direct code read, lines ~148–191):
a text wordmark (`RequestrWordmark`), a theme-toggle icon, and a circular avatar that opens Settings
on tap. There is **no hamburger/menu icon anywhere in `HomeScreen.kt` or `MainActivity.kt`**
(confirmed via `grep` for `Icons.Default.Menu` and variants — zero matches); the nav drawer
(`ModalNavigationDrawer` in `AppNavigationDrawer.kt`) is reachable only by swipe gesture. The
mockup's target replaces this with:

- **Three (really four, grouped as three) independently-floating, individually-pinned glass
  capsules** — not children of one shared bar/background:
  - Left: a **hamburger capsule** that opens the nav drawer (currently missing entirely).
  - Center: an **identity capsule** — "requestr" wordmark + a live status dot (green=active,
    red=inactive) bound to the real interception state — not the current plain text wordmark.
  - Right: **two separate small circular capsules** — theme toggle and settings gear — explicitly
    "two distinct capsules, not one capsule containing two icons." (Today there is a theme toggle
    and an avatar that doubles as the settings entry point — this needs to become gear-icon-only per
    the mockup, with the avatar/name concept dropped from the header.)
- All three capsule groups are **pinned/fixed** — they do not scroll with content and do not
  collapse/react to scroll direction (this is explicitly different from the bottom nav pill's
  scroll-collapse behavior). Content scrolls underneath/around them. Today's header scrolls away
  with the rest of the `LazyColumn` — this must change to a pinned overlay.
- **No shared filled background behind the three groups** — confirmed against the reference
  screenshots: each capsule/button renders as its own visually separate floating shape with the page
  background genuinely visible between them (not glued together, not sitting on one continuous
  toolbar surface). This is the Home-specific instance of the global transparency requirement (see
  §5) and reuses the same glass-surface treatment already implemented for the bottom nav pill
  (`CollapsibleNavPill.kt`) rather than inventing a second, slightly different glass style.
- **Dynamic system-color adaptation — header capsules only.** On Android 12+/API 31+, the header
  capsule tint must derive from Android Dynamic Color (wallpaper-extracted Material You palette),
  explicitly **not** the hardcoded purple/blue/grey seen in the reference screenshots — those
  colors are illustrative of the current fixed-theme prototype only. Below API 31 or where dynamic
  color is unsupported, fall back to the app's existing 9-theme static token system. This dynamic
  adaptation applies **only to the header** — Power Module card colors and the Interception status
  card explicitly stay on the static theme-token system; extending dynamic color app-wide is called
  out as "a separate, explicit decision" if ever wanted, and is out of scope here.
- **Identity-capsule text-swap animation**, exact 5-step ordered sequence (not a simultaneous
  cross-fade): (1) old title at rest → (2) icon stays anchored, no fade/move/resize → (3) old text
  opacity-fades out → (4) new text fades in at the same position → (5) capsule resizes, spring-based,
  only after the new text has settled (must never resize before content appears).

### 2.2 What must NOT change (explicit preservation, verified against both screenshots)

- **Greeting** — "Good afternoon, {realUserName} 🍃" (time-aware, real username, exact leaf emoji)
  plus a small muted date line. Confirmed already correct in code (`settings.userName`, dynamic
  `greeting`/`currentDateStr`) — do not touch.
- **Search bar** — full-width, "Search APIs, notes, chats, tools..." placeholder, leading search
  icon, trailing filter/sliders icon opening an advanced filter panel. Scrolls normally (not part of
  the fixed header). Current code's placeholder differs slightly ("Search APIs, notes, tools, flow
  nodes...") — reconcile wording only, layout is already correct.
- **Quick Actions row** — 4 pills (+New API/green, +Intercept/orange, +New Tool/amber, +New
  Note/blue), confirmed present and matching in code (`HomeQuickActionPill` calls at lines ~288+).
- **Interception Status Card** — green-tinted when active, "Interception: ACTIVE" + "Live" badge +
  waveform, real port/packet-count line, HTTPS-decryption/filtering/device-count line, chevron to
  full Intercept screen. The spec explicitly reconfirms a standing requirement: this card was
  previously flagged as ever showing a fake/always-on state, and that fix must remain in place — all
  values must stay live, never stubbed. Its "not set up" state must be driven by the **same**
  underlying flag as the Power Modules "Intercept" card's "Not set up" badge — the spec explicitly
  calls out the risk of two independently-tracked "is intercept configured" flags disagreeing.
  Needs verification that only one source of truth exists in code today.
- **Recently Opened** — real usage-history rows only (never seed/sample data in production), 4 rows
  visible before scroll/collapse, each with type-colored icon, "{Type} • {SUBTYPE}" label, title,
  metadata, relative timestamp, chevron.
- **Power Modules grid** — asymmetric 2-column grid (Intercept+APIs row, Tools+Notes row, Flow
  full-width row), each card driven by real counts/state (saved API count, tool count, doc count,
  flow-graph-active state) — reconfirmed screenshot-matching in both PNGs viewed.
- **Bottom navigation** — explicitly out of scope for this document; governed entirely by the
  separate `nav_header_pill`/`navigation` mockups (Phase A item).

### 2.3 Summary of the only real changes

Per the spec's own §9: header conversion (2.1's structural change), dynamic color sourcing for the
header only, and the identity-capsule text-swap animation. Everything else in this section is
lock-down/preservation, matching the reference screenshots "pixel-for-pixel in layout terms."

## 3. Screens/Components With Zero Design Coverage

Enumerated by reading `app/src/main/java/com/example/ui/` against every folder in `docs/designs/`.
None of the following appear in any mockup, screenshot, or written spec:

| Area | File(s) | Why it's Phase B |
|---|---|---|
| Local REST API & CLI settings block | `SettingsScreen.kt` (section header "LOCAL REST API & CLI," seen adjacent to the Runtime Instrumentation block, e.g. "Server running on port 9876") | No mockup describes this section at all — Domain B's mockups only cover Runtime Instrumentation and Browser Bridge (CDP), not this adjacent block. |
| Plugin Manager settings block | `SettingsScreen.kt` (section header "PLUGIN MANAGER," e.g. "3 plugins loaded · 1 disabled") | Same as above — visible in the raw mockup text extraction but not itself a described/redesigned surface; it is co-located with Domain B content, not part of it. |
| Appearance settings beyond theme tokens (corner-radius, density, glass-style picker UI itself) | `SettingsScreen.kt`, `theme/DensityConfig.kt`, `theme/AnimationConfig.kt` | `DESIGN-SYSTEM.md` documents the *tokens* (9 themes, 10 accents, 4 glass styles) but no mockup shows the *settings UI* for picking them. |
| Notes | `NotesScreen.kt` | No `notes/` folder exists in `docs/designs/` at all; Notes is referenced only as a data type inside Home's "Recently opened" rows and Power Modules ("3 docs" / "Markdown, HTML & checklists"), never as its own screen mockup. |
| Custom Tool Workspace beyond the `tools/` mockup's 3 screens | `ToolWorkspaceScreen.kt` (backup `CustomToolWorkspace`, Room-backed `CustomToolDao`) | The `tools/` mockup covers the tool library grid and two reusable detail patterns (single-field, dual-tab); it does not cover the custom/user-authored tool workspace. |
| ApiTestr response inspector beyond the request-composer mockup | Inline viewer in `ApiTestrScreen.kt` | Harvested Hex/search/Set-Cookies/copy/export behavior is documented in the gaps audit; later design work should target the live inline viewer, not recreate the deleted standalone inspector. |
| Diff viewer | `DiffView.kt` | No mockup references a diff/comparison UI anywhere in `docs/designs/`. |
| Capture Controller overlay | `CaptureController.kt` | No mockup shows a capture-control overlay; only the Interceptr traffic list's capturing-status card (which *is* covered) references capture state. |
| Nav drawer content beyond the shared chrome spec | `AppNavigationDrawer.kt` (drawer *contents* — the list of destinations, any drawer-specific header/footer) | `nav_header_pill/`/`navigation/` cover the floating header and bottom nav pill mechanics, not the drawer's own content layout — except where `ai-chat`'s Screen 3 (its own in-context drawer) or `home_rebranding.md`'s hamburger-opens-drawer requirement already specify a piece of it. |

This enumeration should be treated as a **starting list, not final** — it was built by cross-referencing
the `ui/` package against every `docs/designs/` folder name and section title found during this
audit; a full line-by-line diff per file (done for Home, partially done for others in Phase A's
table) may surface additional gaps once Phase A implementation begins.

## 4. Approach for Zero-Coverage Screens

Since no exact mockup exists for the screens in §3, apply — in this order of authority:

1. `docs/DESIGN-SYSTEM.md`'s frozen tokens (theme palettes, accent colors, glass-style keys, spacing
   conventions, `CollapsibleNavPill`/`GlassActionPill`/`BadgePill` component contracts).
2. The `nav_header_pill/`/`navigation/` shared-chrome spec (§5 below) for any header/nav element
   these screens include.
3. `branding/logos_and_naming_concepts.html`'s identity concepts, where a screen needs its own mark
   or naming treatment (e.g., if Notes or Plugin Manager ever need a distinct visual identity the
   way Reqy Assistant does).

**Complete old-UI removal is required per screen** — no screen should ship with old and new UI
coexisting behind a feature flag or partial overlay; each screen in §3 gets a full pass, not a
patch.

## 5. Mandatory Nav / Header Transparency Requirement (Global)

This is a **hard requirement** across all of Phase B (and, per Phase A §6, anywhere Phase A also
touches nav/header) — restated here in full because it governs the Home rebrand (§2.1) directly:

The dynamic bottom nav pill and floating header must **never** have a traditional filled
block/container behind or around the pill group. Each pill/capsule is an individually
glass-effected, floating element (blurred/translucent tint, hairline border, soft diffuse shadow —
**no real blur/`RenderEffect`**, per `DESIGN-SYSTEM.md` §14.7's documented fallback), and the area
between the dynamic nav/header and the screen content beneath must remain fully transparent, with
no filled shape tied to the pills. Corroborating sources:

- `home_rebranding.md` §1.4 — "no parent filled screen behind them," genuinely empty transparent
  space between the header's capsules.
- `navigation_showcase_dark.html` — 3 header states (root wordmark / active-intercept / child-screen
  back button) and 2 bottom-pill states (expanded 4-slot / collapsed FAB circle), all floating,
  none on a shared bar.
- `DESIGN-SYSTEM.md` §8/§9 — the existing glass-emulation contract (`CollapsibleNavPill`,
  `FloatingCapsuleHeader`/`DynamicGlassHeader`) that this requirement extends to every screen.

**Verification note:** the bottom nav pill (`CollapsibleNavPill.kt`) already satisfies this — it
renders one shared glass capsule containing all tab slots (correct: the mockups show the *bottom
pill* as one continuous capsule, only the *header* as separate capsules). The **header** is the
piece that needs per-screen verification; Home is the one confirmed gap (§2.1) found during this
audit. Every other screen's header should be checked against this requirement as part of its own
Phase B or Phase A work, not assumed compliant.

## 6. Phased Steps

1. **Home rebrand** (§2) — highest-confidence, most fully-specified Phase B item; land first.
2. **Zero-coverage screens** (§3) — sequence by usage frequency / dependency on other in-flight
   work: Notes and Appearance-settings-UI first (self-contained, no cross-screen dependencies);
   Local REST API & CLI / Plugin Manager settings blocks next (co-located with Domain B work in
   Phase A, so sequence them close together to avoid re-touching `SettingsScreen.kt` twice); Custom
   Tool Workspace, Response Inspector remainder, Diff Viewer, Capture Controller overlay, and Nav
   Drawer content last, once the §3 diff-against-Phase-A pass (done during Phase A implementation)
   confirms exactly what's left uncovered.
3. Re-run the §5 transparency check on every screen touched by either phase as a final sweep.

## 7. Open Questions / Risks

1. **Interception "single source of truth" risk (§2.2):** confirm there is exactly one underlying
   flag for "is intercept configured," shared by the Home status card and the Power Modules
   "Intercept" card — not two independently-tracked flags that could disagree. This is a
   pre-existing risk called out by the spec itself, not newly introduced by the rebrand.
2. **`gesturesEnabled` conflict** (same conflict as Phase A §7.1): the navigation mockups' hardcoded
   side-swipe-disable constraint vs. the shipped user-toggleable behavior. Recommendation carries
   over unchanged: keep the current toggleable behavior; document the divergence.
3. **Home header's dynamic-color scope:** confirm implementation does not accidentally extend
   dynamic color beyond the header capsules (explicitly out of scope per §1.5 of the source spec)
   into Power Module cards or the Interception status card.
4. **§3's enumeration is provisional.** Treat it as a checklist to re-verify once Phase A lands (some
   items — e.g. Response Inspector, Nav Drawer content — may turn out to be more fully covered by
   Phase A's interceptr/nav work than this pass could confirm without a line-by-line diff).
5. **Branding concept choice not yet made.** `branding/logos_and_naming_concepts.html` presents 3
   concepts (one marked "RECOMMENDED") but no confirmed decision exists in code or docs; if any
   Phase B screen needs a new mark, that choice should be made explicitly and recorded, not
   defaulted silently.

All items in this document are **PENDING** — no implementation work has started under this audit.
