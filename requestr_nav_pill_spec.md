╔══════════════════════════════════════════════════════════════════════════╗
║  requestr — BOTTOM NAVIGATION: COLLAPSE-TO-ICON+LABEL PILL               ║
║  Full Behavior, Animation, Vector & Texture Specification                ║
║  Scope: Bottom navigation only (per attached reference: "Concept 3")     ║
║  Package: com.kailashhh.requestr                                         ║
╚══════════════════════════════════════════════════════════════════════════╝

## SCOPE & SOURCE

This document fully specifies the bottom navigation pattern shown in the
attached reference image ("Concept 3 — Collapse to Icon + Label
(Center)"), covering both documented states: **Without Music** (5-step
sequence) and **With Music Playing** (5-step sequence, music mini-player
docked above the pill). This supersedes any prior bottom-navigation
description in earlier specs (the "expandable pill menu with staggered
upward reveal" concept from the v2.1 doc) — **this collapse-to-icon+label
pattern is the current, correct design direction for bottom navigation.**
Treat earlier staggered-reveal nav specs as superseded for this
component.

---

## §0 — Surface Treatment (Foundational, Applies to Every State)

**Confirmed: floating glassmorphic pill, no containing bar.**

- The pill (in any state — full, collapsed, or mid-transition) is a
  **self-contained floating element** with no full-width background
  strip/container behind it. Screen content (cards, lists, the
  Interception status card, "Recently opened" section, etc.) scrolls
  and renders **underneath and around** the pill freely — the pill does
  not sit inside a docked bottom bar container the way a standard
  Material `NavigationBar` would.
- **Pill surface material**: translucent, frosted-glass (glassmorphic)
  treatment — background blur applied to whatever content is currently
  positioned behind/beneath the pill, with a semi-transparent tint
  layer on top (matching the app's established glassmorphic design
  language: ~10–16% white/light overlay on dark themes, equivalent
  subtle tint on light themes, per existing design tokens). Content
  behind the pill should be visibly, softly blurred through it — not
  a flat solid-color pill, and not a fully see-through/no-blur pill
  either.
- **"Strong pill" quality**: despite being translucent, the pill must
  read as a clearly defined, confident UI element — achieved via:
  - A crisp, subtle border/stroke around the pill's full outline
    (hairline weight, low-opacity accent or neutral border color per
    theme tokens — not a hard black/white line)
  - A soft drop shadow beneath the pill (low blur radius, low opacity,
    just enough to lift it off the content behind it — reinforcing that
    it's a floating layer, not painted onto the background)
  - Sufficient internal contrast (icon/label colors) to stay legible
    against varying content scrolling behind the blur, at all times
- **No scrim/dimming overlay** on the rest of the screen at any pill
  state — the area outside the pill remains fully visible and fully
  interactive throughout (this is explicitly NOT a modal/bottom-sheet
  pattern; the user can keep scrolling, tapping other content, etc.,
  with the pill present in any state).
- **Corner radius**: full pill/stadium shape (fully rounded ends) in
  every state — full-width-pill, collapsed icon+label, and everything
  between — maintaining shape-family consistency across the whole
  transition (this becomes relevant to the morph animation in §3).

---

## §1 — State Definitions (Both Contexts)

### Context A — Without Music

**State 1: Full Pill (At Rest)**
- Full-width floating pill (per reference: spans most of the screen
  width with side margins, standard bottom-safe-area positioning above
  system nav/gesture area)
- Contains all navigation destinations as icon+label pairs, arranged
  horizontally: **Home, Intercept, APIs, Tools, Flow, Music**
- Current/active destination visually distinguished (per reference:
  filled icon + accent-colored label + accent-tinted background chip
  behind that specific icon+label pair, e.g. "Home" shown active with a
  green icon+background treatment in the reference)
- Inactive destinations: outline-style icons, muted/neutral label color,
  no background chip

**State 2: Swipe Up / Scroll Up (transitional, gesture-driven)**
- Triggered by an upward swipe/scroll gesture performed **on the pill
  itself** (not a page-content scroll — a direct gesture on the nav
  pill area)
- This is the **live, gesture-tracked mid-transition state** — the pill
  visually compresses in real time as the user's finger moves, tracking
  the drag distance/velocity (not a fixed-duration auto-animation at
  this stage — the collapse progress is driven directly by gesture
  position, per the reference's upward-arrow indicator showing the
  swipe direction)
- Other nav items progressively fade/shrink out during this drag as the
  pill narrows toward its collapsed form, with the active item (Home)
  remaining as the visual anchor point the pill is collapsing toward

**State 3: Collapsed (Icon + Label)**
- End state of the collapse gesture (or the resting collapsed state,
  reached either by completing the swipe gesture past a commit
  threshold, or — see §2 — automatically after a scroll-driven trigger)
- Pill has contracted to a small, centered capsule containing **only
  the current/active screen's icon + label** (e.g. "🏠 Home") — all
  other destination icons are hidden, not just visually deprioritized
- Positioned centered horizontally at the same bottom-anchored vertical
  position as the full pill was
- Same glassmorphic surface treatment, just at a much smaller footprint

**State 4: Tap to Expand**
- User taps the collapsed icon+label capsule
- This triggers the **expand animation** (see §3) — the pill grows back
  outward from its collapsed center-point back to the full navigation
  pill
- Reference shows a visible tap/press interaction cue (finger cursor)
  on the "Home" collapsed pill at this step

**State 5: Expanded Again**
- Full pill restored, identical in content/layout to State 1
- User may now tap **any** destination in the expanded pill (not just
  the one that was active when it collapsed) to navigate elsewhere —
  per the reference's explicit note: *"Full navigation pill is back.
  Navigate to any other screen."*
- After a navigation selection (or after a timeout/re-collapse trigger,
  see §2), the pill will naturally re-enter State 2→3 collapse behavior
  again on subsequent scroll

### Context B — With Music Playing

Identical 5-state structure and behavior to Context A, with one
addition present in **every state**: a **music mini-player row**
docked immediately **above** the navigation pill (per reference: shows
album art thumbnail, track title "Night Drive," artist "Kavinsky", and
play/pause + skip controls), in its own separate translucent pill/
capsule, visually distinct from but adjacent to the nav pill below it.

**Critical behavioral requirement (per reference's explicit callout in
the "How it works" panel):** *"Music mini player is independent and
always visible if playing."* This means:
- The music mini-player's visibility and layout are **entirely
  decoupled** from the navigation pill's collapse/expand state — it
  does not collapse, shrink, or hide when the nav pill collapses to its
  icon+label state, and it does not need to wait for the nav pill to be
  expanded to be visible
- As long as music is actively playing (or paused-but-not-dismissed,
  per the existing Music mini-bar close-icon behavior from prior specs),
  this mini-player row remains visible and fully interactive
  (play/pause, skip) regardless of what state the navigation pill below
  it is currently in
- These are **two independent floating elements** stacked vertically
  with a small consistent gap between them, not one merged component —
  they must be built as separate composables/components with
  independent state, even though they're visually adjacent

---

## §2 — Collapse Trigger Conditions

Two distinct ways the pill enters its collapsed state — both must be
implemented, and must feel consistent with each other:

1. **Direct manual gesture** (per §1 State 2): user swipes up directly
   on the pill itself. This is a deliberate, explicit user action.
2. **Content-scroll-driven auto-collapse**: when the user scrolls the
   underlying screen content (e.g. scrolling down through Home's
   "Recently opened" cards, or down a long list in APIs/Tools/etc.),
   the pill should **automatically collapse** to the icon+label state
   once scroll velocity/distance crosses a reasonable threshold — this
   maximizes content viewing area during active scrolling, then the
   pill re-expands automatically once scrolling stops/reverses upward
   past a small threshold (standard "hide-on-scroll-down, show-on-
   scroll-up" pattern, but landing on the *collapsed icon+label* state
   rather than fully hidden/off-screen — since fully hiding wasn't
   what's specified, and the reference explicitly keeps a persistent
   collapsed anchor visible at all times, never fully gone)
3. Both trigger paths converge on the exact same State 3 visual result
   and the exact same State 4 (tap-to-expand) re-entry path — implement
   via one shared state machine (`NavPillState: Expanded | Collapsing |
   Collapsed | Expanding`) driven by either gesture source, not two
   separate implementations that could produce inconsistent results.

---

## §3 — Morph Animation (Core Visual Mechanic)

This is the single most important animation in this component — it must
read as one continuous **shape-morph**, not a fade-out-then-fade-in of
two different components.

**Collapse (Expanded → Collapsed):**
- The pill's bounding shape animates its **width** from full-pill-width
  down to the small collapsed-capsule width, with the **center point
  remaining anchored** at the horizontal center of the screen throughout
  (per reference: the collapsed pill is always centered, regardless of
  where "Home" happened to sit in the full pill's layout — this means
  the shrink animation isn't simply "shrink toward wherever the active
  item currently is," it specifically converges toward center)
- All non-active destination icon+label pairs **fade out and slide/
  compress inward** toward center as the width animation progresses —
  their opacity and horizontal position should be tied to the same
  animation progress value (0.0 = full pill, 1.0 = fully collapsed) so
  they visually "feed into" the collapse rather than abruptly
  disappearing partway through
- The active item's icon+label (the one surviving into the collapsed
  state) **does not disappear and reappear** — it should be understood
  as the one persistent element that the whole pill collapses around,
  smoothly repositioning to dead-center as the shape shrinks
- Corner radius stays fully rounded/stadium-shaped throughout (per §0),
  so at every intermediate frame the shape still reads as "a pill," just
  progressively narrower — never a rectangle mid-transition
- **Timing/easing**: spring-based (matching the app's established
  animation philosophy from prior specs — spring physics preferred over
  linear easing for interactive/gesture-driven elements). For the
  gesture-driven manual-swipe path (§1 State 2), the animation progress
  should be **directly driven by drag position** while the finger is
  down, then **settle via spring** to the nearest resting state
  (fully expanded or fully collapsed, based on drag distance/velocity
  past a commit threshold) on release — standard "gesture-tracked drag
  + spring-settle on release" pattern (comparable to how iOS sheet-
  dismiss gestures behave). For the scroll-driven auto-collapse path,
  since there's no direct finger-on-pill drag to track, use a smooth
  spring-animated transition triggered by the scroll-state change,
  not a gesture-tracked one.

**Expand (Collapsed → Expanded, via tap):**
- Reverse of the collapse animation — width grows back outward from
  center, other destination icon+labels fade/expand back in from center
  outward, reaching full pill width
- **Not gesture-tracked** (this is a discrete tap, not a drag) — pure
  spring-animated transition, tap-triggered, no intermediate drag state
- Reference explicitly notes: *"Smooth scale & fade animation"* in the
  "How it works" panel — confirms both scale (width/size) and fade
  (opacity of the appearing/disappearing icon+label pairs) are
  simultaneously animated properties, not sequential (i.e. don't fully
  finish the scale animation before starting the fade, or vice versa —
  they run concurrently, driven by the same underlying progress value)

---

## §4 — Vector & Icon Specification

**Icon set (per reference's icon legend row):**
| Destination | Icon (Lucide equivalent) |
|---|---|
| Home | `home` |
| Intercept | `radio` / `target` (concentric-circle "scope" glyph, per reference's icon rendering — use whichever Lucide icon matches this concentric-ring visual most closely, consistent with the "telegraph/radio" icon direction from prior Intercept FAB specs) |
| APIs | `globe` |
| Tools | `sliders-horizontal` (the horizontal-sliders/equalizer glyph shown in reference) |
| Flow | `code-2` / the `< >` bracket glyph shown in reference |
| Music | `music` (single note glyph) |

**Active-state icon rendering**: filled/solid variant of the icon where
Lucide provides one, or the outline variant rendered with full accent-
color fill applied as a background treatment (per reference: Home's
active state shows a green circular/rounded background chip behind the
icon, with the icon itself appearing to invert to a light color against
that fill) — implement as: active icon sits inside a small filled
accent-colored rounded shape (its own mini-pill/circle), inactive icons
render as plain outline glyphs with no background shape.

**Label typography**: small, consistent with the app's established UI
font (per design tokens — Sora or equivalent), active label uses accent
color + slightly bolder weight, inactive labels use muted/secondary
text color, regular weight.

**Collapsed-state icon+label**: identical icon+label rendering to that
destination's active-state appearance in the full pill (same accent
fill treatment, same label styling) — just presented alone, centered,
without the surrounding row of other destinations.

---

## §5 — Texture & Material Detail

- **Blur radius**: sufficiently strong to obscure fine detail of
  content behind the pill (text should become illegible/abstracted
  through the blur, per standard iOS/Android glassmorphic blur
  conventions — not a light blur that leaves background text readable
  through the pill, which would undermine legibility of the pill's own
  content)
- **Tint layer**: theme-aware — must correctly adapt across all 9 themes
  established in prior specs (Light, Dark, Obsidian, Dusk, Terracotta,
  Sage, Rosewood, Slate Editorial, Parchment), using each theme's
  surface/glass tint tokens rather than a single hardcoded tint value
  that would look wrong on light backgrounds vs. dark ones
- **Border**: hairline (≈1px at standard density), theme's border-color
  token at reduced opacity — visible enough to define the pill's edge
  against varied backgrounds without reading as a heavy outline
- **Shadow**: soft, diffuse, low-opacity, positioned to suggest the pill
  floats slightly above the content plane (consistent with the app's
  established card-elevation treatment elsewhere, scaled appropriately
  for a floating nav element rather than a static card)
- **Status bar/system chrome area** (top of reference screenshots,
  showing time/signal/battery): unrelated to this component, not in
  scope — included in the reference only as incidental phone-frame
  context

---

## §6 — Interaction Summary Table (Quick Reference)

| Trigger | From State | To State | Animation Driver |
|---|---|---|---|
| Swipe up on pill | Expanded | Collapsed | Gesture-tracked drag + spring-settle |
| Scroll down on page content | Expanded | Collapsed | Auto spring transition |
| Scroll up / stop scrolling | Collapsed | Expanded | Auto spring transition |
| Tap collapsed icon+label | Collapsed | Expanded | Tap-triggered spring transition |
| Tap any destination (any state) | — | Navigates + settles to Expanded at destination | Standard navigation, per §1 State 5 |

---

## §7 — Explicit Non-Goals / Things NOT to Build Here

- Do not reintroduce the earlier "staggered upward reveal, expand into
  a vertical stack of pills" concept from prior specs — this document's
  horizontal collapse/expand pattern **replaces** that design direction
  entirely for bottom navigation
- Do not add a dimming scrim behind the pill in any state (per §0)
- Do not make the music mini-player collapse, hide, or tie its
  visibility to the nav pill's state in any way (per §1 Context B's
  explicit independence requirement)
- Do not fully hide/remove the pill from screen at any point during
  scroll — the collapsed icon+label state is the minimum persistent
  form; there is no "fully off-screen" state in this spec

---

*End of Bottom Navigation — Collapse-to-Icon+Label Pill Specification*
*Package: com.kailashhh.requestr*
*Source reference: "Concept 3 — Collapse to Icon + Label (Center)"
attached design mockup*
