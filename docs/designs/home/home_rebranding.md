╔══════════════════════════════════════════════════════════════════════════╗
║  requestr — HOME SCREEN MODIFICATION PROMPT                              ║
║  Scope: Header (FloatingHeader) + full Home layout preservation spec     ║
║  Package: com.kailashhh.requestr                                         ║
╚══════════════════════════════════════════════════════════════════════════╝

## READ THIS FIRST — WHAT THIS DOCUMENT IS FOR

This is a **modification spec**, not a rebuild-from-scratch spec. The two
attached reference screenshots show the current/target prototype of the
Home screen. Every item's **position, icon, width, spacing, alignment,
color, inner text, inner icon, and live-data behavior** shown in these
screenshots must be **preserved exactly** unless a section below
explicitly instructs a change. Where this document is silent on an
element, the reference screenshots are the source of truth for that
element — do not "improve," resize, recolor, or reposition anything not
explicitly called out here.

The **only** section receiving a real structural change is the **header**
(§1) — converting it from a traditional app-bar model to a floating,
individually-pinned capsule system (FloatingHeader). Everything else on
this screen (§2 onward) is a **lock-down/preservation spec**: describing
exactly what already exists so it survives this and any future pass
unmodified.

---

## §1 — HEADER: FloatingHeader Conversion (The Actual Change)

### 1.1 — Conceptual shift (do not skip this — it changes the architecture)

**Old model:** traditional toolbar + title + actions — one continuous
bar-shaped surface spanning the screen width, with items laid out inside
it as children of a single container.

**New model:** **Navigation control + identity capsule + contextual
actions** — three (or more, per §1.4) **independently floating pill
capsules**, each individually pinned, not children of one shared bar
container. There is no bar. There is no shared background surface
connecting them. Each capsule is its own separate floating element with
its own edges, its own shadow, its own blur — positioned in a row, but
structurally and visually independent of its neighbors.

### 1.2 — Layout (exact, per your notation)

```
([ ☰ ])      ( requestr • )        ([ ☀ ]) ([ ⚙ ])
```

- **Left**: single circular/pill capsule containing the hamburger menu
  icon (`☰`) — opens the navigation drawer
- **Center**: the identity capsule — contains the `requestr` wordmark +
  the live status dot (`•`) immediately after it (per prior logo spec:
  green when interception active, red when inactive — this dot's
  behavior is unchanged by this document, still bound to the same
  `InterceptionState` source of truth established previously)
- **Right**: two separate small circular/pill capsules side by side —
  theme toggle (`☀`/moon icon depending on current theme) and settings
  gear (`⚙`) — these are **two distinct capsules**, not one capsule
  containing two icons (per the reference screenshots showing them as
  clearly separate rounded-square buttons with a visible gap between
  them)
- Horizontal distribution matches the reference screenshots exactly:
  left capsule near the left edge, center capsule genuinely centered
  in the available width, right capsules grouped near the right edge —
  with **consistent breathing-room margins from the screen edges on all
  sides** (per your note: "nothing is visually glued to the screen
  edges" — every capsule maintains real margin/padding from the screen
  boundary, never touching or flush against it)

### 1.3 — "Locked" behavior (confirmed scope)

Each of the three capsule groups is **individually pinned/fixed** in its
position on screen:
- They **do not scroll** with the page content beneath them
- They **do not hide, collapse, or react** to scroll direction/velocity
  (this is explicitly different behavior from the bottom navigation
  pill's scroll-collapse behavior specified in the prior nav-pill
  document — the header does not adopt that pattern; it stays put,
  always, in every state)
- Page content (greeting, search bar, quick actions, interception card,
  recently opened, power modules) scrolls **underneath and around**
  these capsules exactly as it does beneath the bottom nav pill (§1.5
  of the prior nav spec's surface-treatment logic applies here too —
  same "floating over scrollable content" relationship, just applied to
  the top of the screen instead of the bottom)
- No capsule's position, size, or content ever changes as a result of
  scroll — the only thing that changes a capsule's appearance is a
  direct interaction with that specific capsule (tap, or the identity
  capsule's content-swap animation per §1.6)

### 1.4 — "No parent filled screen behind them" (surface treatment)

- There is **no shared background bar/strip** behind the three capsule
  groups — confirmed no full-width filled rectangle/toolbar surface
  exists at all. Each capsule has its own independent floating
  glassmorphic surface (blurred, translucent, matching the same
  material language already established for the bottom nav pill: soft
  frosted-glass blur of content behind it, subtle tint overlay, hairline
  border, soft diffuse shadow lifting it off the content plane) — reuse
  that exact shared surface-treatment component/style if one already
  exists from the nav pill work, rather than building a second,
  slightly-different glass treatment for the header.
- Between the capsules, the actual page background (whatever content is
  scrolled beneath) is fully visible — there is genuinely empty,
  transparent space between the left capsule and the center capsule, and
  between the center capsule and the right capsule group, not a
  continuous surface with icons floating on top of it.

### 1.5 — Dynamic system-color adaptation (critical, explicit requirement)

> *"That means the same header automatically adapts to the user's
> Android wallpaper/theme. Do not hardcode the purple/blue/grey seen in
> the screenshots."*

- The header's capsule tint/surface color must derive from **Android's
  Dynamic Color system** (Material You / `dynamicLightColorScheme()` /
  `dynamicDarkColorScheme()`, available Android 12+/API 31+, sourced
  from the user's wallpaper-extracted color palette) where the device
  supports it.
- **The specific colors visible in the reference screenshots (the warm
  cream background, the purple/blue/grey tones on the Power Module
  cards, etc.) are illustrative of the current prototype's fixed theme
  only — for the header specifically, these are explicitly NOT to be
  hardcoded.** The header must re-tint itself to match whatever dynamic
  color the system provides, per-device, per-wallpaper.
- **Fallback for devices below Android 12 / dynamic color unavailable**:
  fall back to the app's existing static theme token system (the 9
  themes established in prior specs — Light, Dark, Obsidian, etc.) —
  the header should use that theme's accent/surface tokens as the
  fallback tint source, exactly as every other themed surface in the
  app already does.
- **This dynamic-color requirement applies to the header only, in this
  document's scope** — do not extend dynamic-color sourcing to the rest
  of the Home screen (Power Module card colors, Interception status
  card color, etc.) as part of this change; those remain on the
  existing static theme-token system, unchanged, per the "everything
  else is locked" instruction at the top of this document. If a future
  pass wants app-wide dynamic color, that is a separate, explicit
  decision to be specified on its own — do not infer or expand scope
  here.

### 1.6 — Identity capsule text-swap animation (exact sequence)

The center capsule's content is not always static "requestr •" — it is
capable of swapping its displayed text (e.g. to show contextual state,
a section name, or any other identity-capsule content the app defines
elsewhere) via this **exact, ordered animation sequence** — implement
precisely in this order, not as a simplified simultaneous cross-fade:

```
Old title
    ↓
icon remains
    ↓
old text fades
    ↓
new text appears
    ↓
capsule resizes
```

1. **Old title** — capsule shows current text (e.g. "requestr")
   alongside its icon/dot, at rest
2. **Icon remains** — when a swap is triggered, the leading icon/dot
   element stays visually anchored in place, unaffected by the
   transition (it does not fade, move, or resize during this step —
   confirming it is not part of what's being swapped)
3. **Old text fades** — the old text label animates out (opacity fade,
   not an instant cut) while the icon stays put
4. **New text appears** — the new text label fades/animates in at the
   same position the old text vacated
5. **Capsule resizes** — only **after** the text content has settled
   does the capsule's overall width animate (spring-based, consistent
   with the app's established animation philosophy) to correctly fit
   the new text's actual measured width — this is explicitly the
   **last** step, not something that happens concurrently with the
   text fade, so the capsule doesn't visually "jump" to a new size
   before the new content has actually appeared inside it
- Use spring-based timing for the resize step (per established app
  animation conventions); the text fade steps (3–4) can use a shorter,
  simple opacity-tween duration since they're not gesture-driven and
  don't need spring physics, just a clean, quick cross-fade sequenced
  correctly rather than simultaneously.

---

## §2 — GREETING SECTION (Preserve Exactly — No Changes)

- Two-line large bold greeting: `"Good afternoon, kailash 🍃"` — time-
  aware greeting text (already established behavior), user's real name
  (already established — no hardcoded/default name per prior fixes),
  leaf emoji exactly as shown
- Date line below: `"Thu, 20 Aug"` — small, muted/secondary text color,
  real current date
- Position: directly below the header row, left-aligned, standard
  screen-edge padding (not capsule-style — this is regular page content,
  scrolls normally beneath the fixed header)

---

## §3 — SEARCH BAR (Preserve Exactly)

- Full-width rounded search bar, placeholder text: `"Search APIs, notes,
  chats, tools..."`
- Leading search icon (magnifying glass, Lucide style)
- Trailing icon: sliders/filter icon (Lucide `sliders-horizontal`
  equivalent) — opens advanced filter/sort panel per the annotation in
  reference Image 1 ("Advanced Filters — Open filter/sort panel for
  refining search results")
- This bar is regular scrollable page content, not part of the fixed
  header — confirmed by its position moving normally with scroll,
  unlike §1's capsules

---

## §4 — QUICK ACTIONS ROW (Preserve Exactly)

Four horizontally-arranged pill buttons, each with icon + label, exact
per reference:
- `+ New API` — globe icon, green/accent tint
- `+ Intercept` — concentric-circle/radio icon, orange tint
- `+ New Tool` — sliders icon, amber/orange tint
- `+ New Note` — lines/document icon, blue/indigo tint

Each button: rounded pill shape, icon color matching its category's
established color coding (consistent with Power Module card icons in
§7), label text in standard dark/theme text color. Spacing between
pills matches reference exactly. This row scrolls with page content
(not fixed/pinned — distinct from the header capsules).

---

## §5 — INTERCEPTION STATUS CARD (Preserve Exactly — Live Data, No Stubs)

**Per your explicit instruction: no stubs. Every value shown in this
card must be real, live data — this is a standing requirement, not new
to this document, but reconfirmed here since this card is being
preserved verbatim:**

- Card border/background: green-tinted (active state color), rounded
  corners, full-width, sits below the quick actions row
- Left: circular status indicator icon (concentric rings, green,
  pulsing/live per the small waveform graphic shown to the right of
  "Live" badge)
- Header row: `"Interception: ACTIVE"` (bold) + `"Live"` badge (small
  green pill) + live waveform/pulse graphic
- Detail line 1: `"MITM Proxy · Port {realPort} · {realPacketCount}
  packets"` — port number and packet count must be the actual live
  values from the real interception service/session, never hardcoded
  (per prior audit findings — this exact card was previously flagged as
  fake/always-on; that fix stands, this document does not reintroduce
  the bug by treating "0 packets" or "Port 8080" as fixed display text
  — these are real current values that happen to currently read that
  way, and must update live as real values change)
- Detail line 2: lock icon + `"HTTPS Decryption"` · funnel icon +
  `"Filtering On"` · person icon + `"{realDeviceCount} Device(s)
  Connected"` — same real-data requirement applies to every value here
- Right: circular arrow button — navigates to the full Intercept screen
- **Not-set-up state**: per the Power Modules "Intercept" card in §7
  showing `"Not set up"` — confirm this status card and that Power
  Module badge reflect the **same underlying real state** (single
  source of truth, not two independently-tracked "is intercept
  configured" flags that could disagree with each other)

---

## §6 — RECENTLY OPENED SECTION (Preserve Exactly — Real Data, No Stubs)

- Header row: `"Recently opened"` (bold) + collapse/expand chevron +
  `"See all"` link (navigates to full Recently Opened page per
  annotation)
- List of real recently-interacted-with items (already established
  requirement from prior specs: this section must query real usage
  data, never show seed/sample content in production) — each row:
  - Leading icon matching item type (note/API/tool icon, color-coded
    per category)
  - Type label line: `"{Type} • {SUBTYPE}"` (e.g. `"Note • MARKDOWN"`,
    `"API • COLLECTION"`, `"Tool • DECODER"`, `"Note • CHECKLIST"`) —
    small, muted, uppercase subtype
  - Title (bold) + inline metadata tags/preview (muted, small)
  - Right side: relative timestamp pill (e.g. `"3m ago"`) + chevron to
    open
- Exactly 4 rows visible before scrolling/collapsing, per reference —
  preserve this visible-count behavior
- Collapse behavior: tapping the chevron collapses this section (per
  reference Image 1 showing both expanded and implicitly collapsible
  states) — preserve existing collapse/expand interaction

---

## §7 — POWER MODULES GRID (Preserve Exactly — Real Stats, No Stubs)

2-column grid (Intercept + APIs sharing a row, Tools + Notes sharing a
row, Flow spanning full width as the final row) — preserve this exact
grid arrangement, not a uniform even grid.

For each card, preserve exactly: icon illustration, title, subtitle
description, status/stat badge (top-right), and the row of small
capability-icon chips at the bottom of the card:

- **Intercept** (purple/lavender tint): satellite-dish icon,
  `"Not set up"` badge (orange/warm tint when not configured — must
  reflect real configuration state, see §5's single-source-of-truth
  note), subtitle `"MITM VPN, Frida & Rules"`, bottom chips: Live
  Traffic / Rules Engine / Breakpoints / SSL Decrypt
- **APIs** (sage/teal tint): globe icon, `"{realSavedCount} saved"`
  badge (real count from DB, currently reading "0 saved" because that
  is the real current count, not a hardcoded zero), subtitle `"Test &
  organize endpoints"`, bottom chips: Collections / Environments /
  History / Import
- **Tools** (peach/tan tint): wrench icon, `"{realToolCount}+ tools"`
  badge (real count), subtitle `"Decoders, ciphers & hashes"`
- **Notes** (rose/pink tint): notepad+pencil icon, `"{realDocCount}
  docs"` badge (real count), subtitle `"Markdown, HTML & checklists"`
- **Flow** (periwinkle/blue tint, full-width card): map/globe icon,
  `"Graph active"` badge (must reflect real state — whether a flow
  graph genuinely exists/is active, not a hardcoded "active" label),
  subtitle `"Node-based automation"`

Every badge value in this grid is a real, live-queried number/state —
this document reconfirms the standing no-stub requirement for this
section exactly as it applies everywhere else in the app.

---

## §8 — BOTTOM NAVIGATION (Out of Scope — Already Specced Separately)

The bottom navigation pill visible in both reference screenshots is
governed entirely by the separate, already-delivered nav-pill
specification document (Collapse-to-Icon+Label pattern). **Do not
modify, reinterpret, or re-describe it here** — this document's scope is
the header (§1) and the lock-down of Home's body content (§2–§7) only.

---

## §9 — SUMMARY OF WHAT ACTUALLY CHANGES VS. WHAT'S LOCKED

**Changes (this document's actual scope):**
- Header converts from single-bar model to three independently-floating,
  individually-pinned glassmorphic capsules (§1.1–§1.4)
- Header capsule tint sources from Android Dynamic Color per-device,
  with static-theme-token fallback (§1.5)
- Identity capsule gains the exact 5-step text-swap + resize animation
  sequence (§1.6)

**Locked / preserved exactly, no modification (§2–§8):**
- Every icon, label, color tint, badge value source, spacing, alignment,
  and grid arrangement described in §2 through §8, matching the
  reference screenshots pixel-for-pixel in layout terms
- All live-data/no-stub requirements already standing from prior specs,
  reconfirmed rather than changed, for the Interception card, Recently
  Opened list, and Power Modules grid
- The bottom navigation pill (governed by its own separate spec, wholly
  out of scope here)

---