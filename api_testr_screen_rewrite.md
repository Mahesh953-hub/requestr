╔══════════════════════════════════════════════════════════════════════════╗
║  requestr — API TESTR SCREEN — REFINED REWRITE PROMPT                    ║
║  Scope: Request-builder screen only (per attached reference screenshots) ║
║  Package: com.kailashhh.requestr                                         ║
╚══════════════════════════════════════════════════════════════════════════╝

## SCOPE NOTE

This prompt covers **only** the API Testr request-builder screen shown in
the two reference screenshots. It explicitly **excludes**:
- The bottom navigation bar (out of scope — do not touch)
- The second/lower pill row (`Query Headers Body Auth Scripts Chain Tests` —
  the duplicate tab row visible below the JSON block in the reference
  images) — out of scope, do not touch

Theme and color tokens are **locked** — use the app's existing theme
system exactly as-is (the reference screenshots' warm ivory/orange
Light-theme styling is illustrative of the existing design language, not
a new palette to introduce). Do not introduce new colors outside the
established theme tokens except where explicitly called out below (e.g.
method-type coloring).

---

## §1 — Top Bar

**Layout (left to right):** Screen title `"API Testr"` (or the active
tab's context, per existing app convention) — then, right-aligned, four
icon buttons: **Star, Save, More (⋯), Settings**.

**Icons:** All four must be **Lucide icon set**, outline style, matching
the rest of the app's established icon system (per prior spec: rounded
strokes, consistent weight). **No emoji anywhere on this screen, for any
icon.** Where this document references an icon by emoji shorthand
(🪄, 📜, etc.) below, that is shorthand for "find/use the semantically
equivalent Lucide icon" — never render the literal emoji character.

**Interaction — hover/press treatment:** Every icon button on this
screen (all four top-bar icons, plus every icon introduced later in this
doc) must use a **dynamic pill-outline treatment on press/hover**,
inspired by iOS Dynamic Island's fluid capsule morphing: on touch-down,
a soft rounded-rect/pill outline expands around the icon with a smooth
spring-based scale+fade transition (not an instant Material ripple).
This is a **shared, reusable interaction component** — implement it once
as a common modifier/wrapper (e.g. `Modifier.dynamicIconPress()`) and
apply it to every icon-button on this screen (and this component should
be reused for consistency anywhere else in the app icon-buttons appear,
though this prompt's scope is this screen only).

**Star / Save / More / Settings — functional behavior:**
- **Star**: toggles favorite status on the current request (same
  underlying favorite mechanism as previously specced — real DB field,
  real reflection in the Favorites filter elsewhere in the app)
- **Save**: explicitly commits the current request state as a named,
  permanent saved request (distinct from the automatic draft
  persistence described in §2 — see the Title/Draft behavior note below
  for how these two interact)
- **More (⋯)**: opens the existing right-menu bottom sheet (Reset,
  Clear, Rename, Import cURL, Export cURL, Code Snippet, Cookie Jar,
  Environment, Make Tool, Add to Chat, Pin — as already specced
  previously; unchanged by this prompt)
- **Settings**: opens this screen's mini-settings sheet (as already
  specced previously; unchanged by this prompt)

---

## §2 — Request Title Row

**Layout:** Editable title text (default label `"New Request"` for an
unsaved/fresh request) + a pencil icon (Lucide) immediately after it.

**Behavior — read carefully, this is precise:**
- **The moment a request screen is opened** (whether it's a brand-new
  request or an existing saved one being reopened), its current full
  state is immediately written to the database as a **draft** — this
  happens automatically, with no user action required, the instant the
  screen is opened. This draft-write is entirely independent of the
  pencil icon and independent of the Save button.
- **Tapping the pencil icon** does exactly one thing: makes the title
  text inline-editable. It has no other side effect. Committing the
  title edit (e.g. on focus-loss or a confirm action) updates the
  title field on that same draft record — it does not itself trigger a
  full "save as permanent" action.
- **The Save button (§1)** performs the explicit "this is now a real,
  intentionally-saved request" action — e.g. promoting the draft's
  status, or however the app's existing saved-vs-draft distinction is
  modeled — per whatever save/draft data model already exists in the
  codebase. Do not invent a new draft/save architecture if one already
  exists; wire this behavior into it.
- Net effect: a user can open a request, look at it, close the app, and
  reopen — their in-progress state (including any title edit) persists
  as a draft, with or without ever pressing Save. Save is for explicit,
  intentional permanent-save actions (e.g. filing it into a named
  collection/folder), not a prerequisite for state not being lost.

---

## §3 — Method + URL Row

**Method dropdown:**
- Tapping the current method (e.g. `POST`) opens a **rich rounded dropdown
  menu** listing all HTTP methods the app supports (GET, POST, PUT,
  DELETE, PATCH, HEAD, OPTIONS, plus WS for WebSocket — per existing app
  scope).
- **Method-type coloring**: each method in this dropdown, and the
  currently-selected method shown collapsed in the row, is **colored**
  per method (consistent with the color coding already established
  elsewhere in the app — GET=green, POST=blue/accent, PUT=amber,
  DELETE=red, PATCH=purple, etc.) — this is an intentional exception to
  the "no new colors" rule stated in the scope note, since method-color-
  coding is already an established app-wide convention, not a new
  introduction.
- Visual treatment of the dropdown itself: rounded card/menu surface
  (matching the rich rounded-menu style implied by the Content-Type
  dropdown in reference Image 2), with generous row padding and a subtle
  selected-state indicator (checkmark, per Image 2's reference) on the
  currently active method.

**URL input field:** Standard text input, syntax-aware coloring
consistent with existing API Testr styling (scheme/domain/path visually
distinguished, per prior specs).

**Rocket icon (Follow Redirects toggle) — precise interaction:**
- **Single press/tap**: toggles Follow Redirects on/off. When ON, the
  icon renders in its accent/colored active state (filled or accent-
  tinted, per existing toggle-icon conventions in the app). When OFF, it
  renders in its default/muted outline state.
- **Long press**: opens an expanded options popover/sheet for
  redirect-related settings beyond the simple on/off (e.g. max redirect
  count, whether to follow cross-origin redirects, whether to preserve
  method on 307/308 vs. switch to GET on 301/302/303 per HTTP spec
  nuance) — implement this options set based on what's technically
  meaningful for redirect handling; if the existing networking layer
  (OkHttp/Ktor per the app's established stack) already exposes
  specific redirect-control knobs, surface those specific options here
  rather than inventing arbitrary ones.

---

## §4 — WebSocket (WS) Method — Adaptive Screen Behavior

When **WS** is selected as the method, the screen must adapt
meaningfully rather than presenting a nonsensical HTTP-shaped UI for a
persistent-connection protocol. Since this wasn't pre-specified by the
user, apply the following considered design — implement as described,
but treat this section as slightly more open to reasonable engineering
judgment than the rest of this document if a genuine technical
constraint requires a small deviation:

- **Send button relabels and changes behavior**: becomes **Connect**
  (rather than a one-shot Send). Tapping it opens the WebSocket
  connection. Once connected, the same button position shows
  **Disconnect** (visually distinct — e.g. a connected/live accent
  color state vs. the idle Connect state), and tapping it closes the
  connection.
- **Tab row adapts**: the existing Query/Headers/Body/Auth/Scripts tab
  set is not fully meaningful for WS (no request body in the HTTP sense,
  though Headers/Query remain relevant for the initial upgrade
  handshake, and Auth remains relevant). Add/swap in a **Messages** tab
  — becomes the primary tab for WS, showing a chat-like scrollable log
  of sent/received frames (direction-indicated, timestamped), with a
  message-compose input pinned at the bottom of that tab for sending new
  frames while connected (disabled/greyed when not connected).
  Query/Headers/Auth tabs remain available (relevant to the handshake);
  Body and Scripts may remain present but are less central for this
  mode — do not hide them entirely (a user may still want pre-connect
  scripts), just don't force them to the front.
- **Connection status must be persistently visible** while on this
  screen with WS selected — a small status indicator (Connected /
  Disconnected / Connecting, with appropriate color per existing status-
  color conventions) near the method/URL row, not just implied by the
  button state alone.
- **The response panel concept adapts**: rather than a single
  request→response pair, the "response" side effectively becomes the
  live Messages log described above — do not force the existing
  Request/Response toggle pattern (from the HTTP flow) onto WS mode if
  it doesn't fit; the Messages tab replacing that toggle is the correct
  adaptation.

---

## §5 — Query / Headers Tabs — Key-Value Table + JSON Block + Raw Editor

**Key-value table** (unchanged in structure from prior specs): checkbox
per row (enabled/disabled), Key cell, Value cell, delete-row icon, empty
trailing row for new entries. Syntax-highlighted, monospace values where
appropriate.

**Below the table, the big JSON-named block**: this block **live-
reflects** the current Query or Header key-value data (whichever tab is
active) as formatted JSON, read-only display by default (per Image 1's
reference).

**Toolbar row above the JSON block — four icons, precise behavior:**

1. **Beautify (🪄 → use Lucide's wand/sparkles-equivalent icon)** —
   reformats/pretty-prints the JSON block's current display (indentation,
   spacing). Purely a display-formatting action on the JSON block.
2. **`{}` icon (Lucide's braces/JSON-equivalent icon)** — copies the
   query/header values to clipboard **as JSON** (beautified or non-
   beautified, matching whatever the block's current display state is
   — i.e. if the user just tapped Beautify, the copy reflects that
   formatting; if not, it copies the compact form).
3. **Copy (Lucide's copy icon)** — copies the query/header values to
   clipboard in **flat key=value form**, one per line, with no
   surrounding quotes or braces (e.g. `API_Key=v4_auth_key`, `Endpoint=
   /people` — per the user's exact spec: "without quotes and braces,
   like key=value").
4. **Delete (Lucide's trash icon)** — **clears the entire query/header
   list in one action** (both the key-value table AND the JSON block
   reflecting it go empty). This is a full clear, not a per-row action
   (per-row deletion already exists via each row's own delete icon in
   the table) — confirm with a lightweight confirmation (e.g. a brief
   undo-snackbar per the app's existing undo pattern) rather than an
   irreversible silent wipe, consistent with the app's established
   "no destructive action without recovery" pattern elsewhere (long-
   press context menus, etc.).

**Fifth icon — Raw Text Editor toggle (📜 → use Lucide's file-text/
scroll-equivalent icon):**
- Tapping this icon **does not replace or hide the key-value table**.
  The table stays exactly where it is, fully intact and independently
  editable.
- Instead, a **separate raw text-editor area appears directly below the
  JSON block**, stacked/attached beneath it (per the user's exact
  spec: *"it changes the big block with editable text-editor... the
  raw-text editor stays down below to table and sticked to big block"*)
  — meaning: **Table (top, unaffected) → JSON display block (middle) →
  Raw text editor (new, appears below, attached to the JSON block)**.
- **Raw editor syntax** (exact, non-negotiable per spec):
  - One query parameter or header per line
  - Key and value separated by `:` (e.g. `API_Key: v4_auth_key`)
  - A line prefixed with `//` represents a **disabled** entry — it
    remains visible in the raw editor (rendered in a visually muted/
    commented style, e.g. dimmed text color, consistent with how
    disabled rows are shown in the key-value table via their checkbox
    state) but is **never included in the actual outgoing request**
    until the user removes the `//` prefix (uncommenting it) to enable it.
- **Two-way sync requirement**: the raw editor and the key-value table
  are two views of the **same underlying data** — editing in one must
  reflect in the other in real time (add a line in the raw editor →
  a new row appears in the table; toggle a row's checkbox in the table
  → the corresponding raw-editor line gains/loses its `//` prefix
  automatically). This is a live bidirectional sync, not a one-way
  export/import — implement via a single shared state model that both
  views render from and write to, not two independent data copies that
  could drift out of sync.
- The raw editor uses monospace font + basic syntax highlighting (key
  vs. separator vs. value vs. disabled/commented-line styling), matching
  the syntax-highlighting requirement stated for this screen generally
  (§7).

---

## §6 — Body Tab (per reference Image 2)

**Content-Type dropdown**, positioned above the body editor:
- Rounded dropdown menu (rich UI, consistent rounded-menu treatment with
  §3's method dropdown), listing: `application/json`,
  `application/x-www-form-urlencoded`, `multipart/form-data`,
  `text/plain`, `other` (per Image 2's exact reference options — extend
  with any additional types already supported elsewhere in the app's
  existing Body-tab implementation, if more than these five already
  exist in the codebase; do not reduce below what's already supported).
- **No method-style coloring on this dropdown** — per explicit
  instruction, Content-Type options render in the app's standard
  neutral/theme text color, **except the currently-selected option**,
  which gets a clear selected-state visual treatment (accent-colored
  checkmark + subtle accent-tinted row background, per Image 2's
  reference showing the checked `application/json` row highlighted) —
  this selected-state highlighting is not "coloring the option" in the
  method-dropdown sense, it's a standard selection-state indicator and
  should use the app's existing selected-row treatment pattern.

**Body editor area**: below the Content-Type dropdown, a full text
editor for the body content, syntax-highlighted appropriately to the
selected Content-Type (JSON syntax highlighting for
`application/json`, form-encoded key-value highlighting for
`x-www-form-urlencoded`, etc. — per §7's general syntax-highlighting
requirement). For `multipart/form-data`, this should present the
existing app's established multipart editing UI (key-value + file-
attachment rows, per prior API Testr specs) rather than a flat text
editor, since multipart isn't meaningfully editable as raw text in a
useful way.

---

## §7 — Syntax Highlighting (Applies Across This Entire Screen)

**Non-negotiable, per explicit instruction**: syntax highlighting must
be present and correct in:
- The Query/Headers key-value table's Value cells (where structured
  values like JSON snippets, URLs, or tokens appear)
- The JSON display block (§5)
- The raw text editor (§5) — key/separator/value/disabled-line
  distinct styling
- The Body editor (§6) — content-type-appropriate highlighting

Use the app's existing syntax-highlighting mechanism/library if one is
already established elsewhere in the codebase (e.g. already used in the
Response panel's Body viewer, per prior specs) — apply that same
mechanism here for consistency rather than introducing a second,
different highlighting approach for this screen.

---

## §8 — Send Button: Animation + Auto-Navigation

**On tap:**
1. The Send button (or Connect button, if WS mode per §4) must show a
   **distinct "sending" animation state** — not just a static spinner
   swapped in, but a considered micro-interaction: e.g. the button's
   label text transitions to `"Sending..."` with an animated ellipsis or
   a subtle pulsing/shimmer effect on the button surface, paired with a
   smooth icon transition (e.g. the send/rocket icon morphing into a
   loading indicator) — this should feel like a deliberately designed
   moment, matching the app's established "immersive animation
   throughout" quality bar from prior specs, not a generic
   `CircularProgressIndicator` dropped into a button.
2. **Immediately upon initiating the send** (not waiting for the
   response to arrive), the screen **automatically navigates to/reveals
   the Response section** (per the existing Request/Response toggle
   pattern from prior API Testr specs), and that Response area shows a
   clear **"Request is being sent..."** in-progress state (distinct from
   both the empty/never-sent state and the loaded-response state) — so
   the user sees immediate feedback that their action registered, before
   the real network response has actually returned.
3. Once the real response arrives, this in-progress state transitions
   (animated, not an abrupt cut) into the actual response content
   (headers/body/metrics, per existing Response panel specs).
4. If the request fails (network error, timeout, etc.), the same
   Response area must show a clear, real error state — not silently
   revert to the empty state or leave the "being sent" indicator stuck
   indefinitely.

---

## §9 — Explicit Exclusions (Reconfirmed)

Do not modify, redesign, or comment on:
- The bottom navigation bar
- The second/duplicate tab pill row visible in the reference screenshots
  below the JSON block (`Query Headers Body Auth Scripts Chain`)

These exist in the reference screenshots only as incidental context and
are handled separately from this screen's scope.

---

*End of API Testr Refined Rewrite Prompt*
*Package: com.kailashhh.requestr*
*Scope: Request-builder screen only — theme/colors locked to existing
app tokens except where explicitly noted (method-type coloring)*
