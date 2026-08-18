# requestr — Stitch Prompt
## Full App Design Brief · Dust by Dust

---

## 🎯 App Identity

**Name:** requestr  
**Tagline:** *your api. your tools. your world.*  
**Target:** Android 5+ to Android 17  
**User:** Power-user — scraper, API hacker, reverse engineer  
**Theme:** Dark glassmorphic. Deep navy/midnight base. Electric blue accents. Occasional neon cyan highlights. High information density but clean. Feels like a terminal that grew up.  
**Typography:** Monospace for code/data (`JetBrains Mono`). Sans-serif for UI (`Sora` or `DM Sans`). Bold weight headers.  
**Dynamic Color:** Supported on Android 12+. Fallback to default electric blue.  
**Corner Radius:** Configurable (default: 16dp rounded). Consistent across all cards and sheets.  
**Icons:** Outlined, sharp-edged. Consistent stroke weight.

---

## 🚀 Onboarding (First Launch Only)

**Screen 1 — Welcome**
- Full screen dark background with subtle animated noise/grain texture
- Center: `requestr` wordmark in large monospace font, glowing blue
- Tagline below: *"your api. your tools. your world."* in small muted text
- Bottom: `Get Started →` button (pill shape, blue gradient fill)

**Screen 2 — What's Your Name?**
- Large heading: `"What should we call you?"`
- Single centered text input, underline style, monospace font, blinking cursor
- Below input: small muted hint — *"Used for greetings and personalization"*
- `Continue →` button at bottom

**Screen 3 — Permissions**
- Heading: `"requestr needs a few things"`
- List of permission cards, each with icon + title + short description + status badge:
  - 📁 Storage — *"Save and load your API collections and notes"* → `Grant`
  - 🔔 Notifications — *"Background request alerts and ongoing task status"* → `Grant`
  - 🌐 Network — *"Required for API testing and chat"* → `Auto-granted`
- Each row has a rounded card with subtle border. Granted = green dot. Pending = amber dot.
- `Start Using requestr →` CTA at bottom (disabled until required permissions granted)

**Screen 4 — Choose Your Theme**
- Heading: `"Pick your vibe"`
- 3 large swipeable theme cards (preview mockup of the app in each):
  - **Midnight** — deep navy + electric blue (default)
  - **Obsidian** — pure black + neon cyan
  - **Dusk** — dark purple-gray + amber
- Selected card scales up slightly with glowing border
- `Done` button. Can be changed in Settings later.

---

## 🏠 Home Screen

**Top Section — Greeting Bar**
- Left: greeting text (time-aware):
  - `"Good morning, {name} ☀️"` / `"Good evening, {name} 🌙"` etc.
  - Sub-line: current date in format `"Thu, 02 Apr"` — muted, smaller
- Right: avatar circle (initials of user's name, accent color fill) + tapping it opens profile/settings

**Search Bar**
- Full-width rounded search bar below greeting
- Placeholder: `"Search APIs, notes, chats..."` with 🔍 icon
- Tapping opens global search overlay (searches across all sections)

**Quick Actions Row — Horizontal scrollable chips**
- `+ New API` · `+ New Note` · `+ New Chat` · `+ New Flow` · `🎵 Music`
- Each chip: rounded pill, icon + label, subtle glassmorphic background

**Recents Section**
- Heading: `"Recently opened"` with `See all →` link
- Horizontal scrollable row of cards (each ~160dp wide):
  - **API card**: method badge (GET/POST in color) + endpoint domain + folder name + timestamp
  - **Note card**: note title + first line preview + folder tag
  - **Chat card**: assistant name + last message snippet + provider badge
- Long press on any card: context menu (Open, Pin, Delete)

**Pinned Section** (only shown if user has pinned items)
- Heading: `"Pinned"` with edit icon
- 2-col grid of pinned collection/folder cards
- Each card: colored left border + icon + name + item count
- Tap to open that folder directly

**Sections Grid — Main Navigation**
- 2-col grid of large navigation cards (each ~full width minus padding, tall):
  - 🌐 **APIs** — *"Test, save and organize"* — shows count of saved APIs
  - 🔧 **Tools** — *"Decoders, ciphers, formatters"* — shows count of tools
  - 💬 **Chats** — *"Your AI interfaces"* — shows active provider count
  - 📝 **Notes** — *"Markdown, HTML, plain"* — shows note count
  - 🗺️ **Flow** — *"Node-based automation"* — shows flow count
  - 🎵 **Music** — *"Mood refresher"* — shows now-playing if active
- Each card: large icon top-left, title, subtitle, stat badge, subtle gradient background unique to each section

**Bottom Navigation Bar**
- 5 items: `Home` · `APIs` · `Tools` · `Chats` · `More`
- Active item: filled icon + accent color label
- `More` opens a bottom sheet with Notes, Flow, Music, Settings

---

## 🌐 Section 1 — APIs (API Testr)

### APIs Home / Collection Browser

**Header**
- Title: `"APIs"` left-aligned, large bold
- Right icons: `🔍 Search` · `📁 New Folder` · `+ New Request`

**Tabs Row** — horizontal scrollable:
- `All` · `Saved` · `Recent` · `Favorites` · `{folder names...}`

**Content**
- Folder rows: colored left bar + folder icon + name + count badge + `⋮` menu
- Under each folder (expandable): list of saved API cards
  - Method badge (GET=green, POST=blue, PUT=amber, DELETE=red, PATCH=purple, WS=cyan)
  - Endpoint URL (truncated, domain colored)
  - Last used timestamp
  - Status dot (saved=white, ongoing=pulsing blue, unsaved=amber)

**FAB** — bottom right: `+` opens bottom sheet:
- New Request (blank)
- Import cURL
- Import from file
- Paste raw URL

---

### API Testr — Single Request Screen

**Top Bar**
- Left: hamburger `☰` — opens left drawer
- Center: tab title (editable, e.g. `"testapi.com"`)
- Right: `▷ Send` icon · `💾 Save` icon · `⋮ More`

**URL Bar**
- Method dropdown pill: `GET ▾` — colored per method
- URL input field: full-width, monospace font, syntax-colored (scheme in muted, domain in accent, path in white)
- Right of URL: `((·))` WebSocket/HTTP2 toggle icon

**Request Tabs** — horizontal scrollable underline tabs:
- `Query (n)` · `Headers (n)` · `Body` · `Auth` · `Settings` · `Scripts`
- Active tab: accent underline. Count badges on tabs that have data.

**Tab Content Areas:**

*Query / Headers tab:*
- Toolbar row: `✨ Auto-format` · `{}  JSON view` · `📋 Copy all` · `📄 Paste` · `🗑️ Clear`
- Key-value table:
  - Checkbox (enabled/disabled per row)
  - Key cell (editable, monospace)
  - Value cell (editable, monospace)
  - `−` remove button
  - Last row always empty for new entry
- Selecting a value text: floating mini-toolbar appears: `Decode` · `Encrypt` · `Copy` · `Hash`

*Body tab:*
- Sub-tabs: `None` · `JSON` · `Form` · `Multipart` · `Raw` · `GraphQL` · `Binary`
- JSON/Raw: syntax-highlighted code editor with line numbers, monospace, fold arrows
- Form: key-value table (same as headers)

*Auth tab:*
- Type selector: `None` · `Bearer` · `Basic` · `API Key` · `OAuth2` · `Custom`
- Fields change per type

*Scripts tab:*
- Pre-request and post-response script editors
- Syntax highlighted JS/Python

**Bottom Toggle Bar — fixed at bottom:**
- Two large pill tabs: `↑ Request` (accent when active) · `↓ Response`
- Swipe up from Response area or tap to switch

**Response Panel (when toggled):**
- Sub-tabs: `Headers (n)` · `Body` · `Metrics`
- Top-right badges: protocol badge (`h2` / `h1` / `ws`) + status badge (`200` green / `4xx` red / `5xx` red)
- Body sub-tabs: `Text` · `Hex` · `Preview` (renders HTML/JSON tree)
- Syntax-highlighted response body with line numbers
- Toolbar: `✨ Beautify` · `🔍 Search` · `≡ Wrap` · `📋 Copy` · `⬇ Save`
- Selecting text in response: floating mini-toolbar: `Decode` · `Decrypt` · `Copy` · `Use in request`
- Metrics tab: timeline bar (DNS → Connect → TLS → Request → Response), size, time breakdown

---

### Left Drawer (swipe right or ☰)

**Header:**
- App logo + `requestr` wordmark
- User avatar + name
- `+ New Tab` button

**Tabs Section:**
- Label: `"Open Tabs"` + tab count
- List of open request tabs:
  - Request icon (method color) + domain name (truncated) + status dot
  - Active tab: highlighted row with accent left border
  - Dots: green = saved, amber = unsaved, blue pulsing = running
- Traffic monitor row: `((·)) Traffic (n)` — shows intercepted traffic count

**Collections Section:**
- Collapsible folder tree
- Drag to reorder

**Bottom of Drawer:**
- `⚙️ API Settings` deeplink

---

### Right Menu (⋮ More)

Bottom sheet with options:
- `↺ Reset` — reset to last saved state
- `🧹 Clear` — clear all fields
- `✏️ Rename` — rename this tab
- `📥 Import cURL` — paste cURL, auto-parse
- `📤 Export cURL` — copy as cURL
- `</> Code Snippet` — export code
- `🍪 Cookie Jar` — view/edit cookies
- `√x Environment` — switch/edit env vars
- `🔧 Make Tool` — save this as a custom tool
- `💬 Add to Chat` — use as chat provider
- `📌 Pin` — pin to home

---

### Code Snippet Screen

- Back `←` button
- Title: `"Code Snippet"`
- Top icons: `⚙️` options · `≡ wrap` · `⬇ save` · `📋 copy`
- Language dropdown: `Python - Requests ▾`
  - Options: Python (requests, httpx, aiohttp), JS (fetch, axios), Kotlin, Swift, Dart, Go, PHP, Ruby, cURL, Java (OkHttp)
- Syntax-highlighted code block with line numbers, full screen
- Monospace font, dark background

---

## 🔧 Section 2 — Tools

### Tools Home

**Header:**
- Title: `"Tools"`
- Right: `🔍 Search` · `⭐ Favorites`

**Category Tabs** — horizontal scrollable, underline style:
- `All` · `Decoders` · `Encrypt/Decrypt` · `Hashing` · `Signatures` · `Formatters` · `Web` · `Network` · `Custom`

**Content — 2-col grid of tool cards:**
Each card:
- Icon (rounded square, accent background)
- Tool name
- Short description (1 line, muted)
- Optional `★` favorite indicator

**Tools by Category:**

*Decoders:*
- URL Encoder/Decoder
- Base32 / Base58 / Base64 Encoder/Decoder
- GZip / Deflate / Brotli Encoder/Decoder
- JWT Decoder (header + payload + signature split view)
- URI Parser
- HTML Entity Encoder/Decoder
- Unicode Escape/Unescape
- Hex Encoder/Decoder
- Morse Code Encoder/Decoder

*Encrypt/Decrypt:*
- AES Encrypt/Decrypt (mode: ECB/CBC/GCM/CFB/OFB, key size: 128/192/256, IV/key/pass inputs, padding: PKCS5/PKCS7/NoPadding, output: Base64/Hex)
- RSA Encrypt/Decrypt (public/private key input, PKCS1/OAEP)
- DES / 3DES
- ChaCha20
- Blowfish
- RC4
- XOR Cipher

*Hashing:*
- MD5 / SHA1 / SHA256 / SHA512 / SHA3
- HMAC (key + algorithm selector)
- PBKDF2
- bcrypt (hash + verify)
- CRC32 / Adler32
- Hash comparison tool (input two hashes → match/no match)

*Signatures:*
- HMAC Signature Maker (key + payload + algo)
- JWT Signer/Verifier (secret or RS256 key)
- RSA Sign/Verify

*Formatters:*
- JSON Formatter / Minifier / Validator
- XML Formatter
- HTML Formatter
- CSS Formatter / Minifier
- SQL Formatter
- YAML ↔ JSON converter
- Protobuf decoder (paste hex/base64 proto bytes)
- Diff Tool (compare two text inputs)

*Web:*
- SSL/TLS Certificate Inspector (enter domain → show cert chain, expiry, SANs)
- HTTP Headers Analyzer
- DNS Lookup
- IP Info
- CORS Tester
- Regex Tester (pattern + test string + match highlights)
- Live Code Preview (HTML + CSS + JS → rendered iframe-like preview)

*Network:*
- Ping
- Port Scanner
- WebSocket Tester
- Static Server Tool (port stepper, directory picker, toggle accessible on internet)
- Localhost Tunnel

*Custom Tools:*
- Grid of user-created tools
- Each custom tool card shows: tool name + trigger API + last used
- `+ Create Tool` button: opens tool adaptor builder
  - Adaptor builder: select a saved API from collection → configure input fields (what the user types becomes which request param) → configure output (which JSON field to extract and display) → name and save

---

### Individual Tool Screen (shared pattern)

- Back `←` + tool name as title
- Tool-specific input section (text areas, key fields, mode selectors, toggles)
- `Execute / Process` button (accent color, full width, at bottom or prominent)
- Output section below: syntax-highlighted result, copy button, share button
- For decrypt tools: IV field, key field, mode/padding dropdowns — all clearly labeled

---

## 💬 Section 3 — Chats

### Chats Home

**Header:**
- Title: `"Chats"`
- Right: `🔍 Search chats` · `📋 Chat History` · `+ New Chat`

**Left Drawer (☰):**
- User avatar + name + greeting (time-aware)
- `Search Chats` row
- `Chat History` row
- `New Message` button (full width, bordered pill)
- `Default Assistant` row (currently selected assistant name + avatar)
- Assistant personality quick-pick: row of emoji/icon circles (each = different assistant preset)
- Bottom: `+ Add Assistant`

**Chats List:**
- Grouped by date: `Today` · `Yesterday` · `This Week` · `Older`
- Each chat row: assistant avatar + assistant name + last message preview + timestamp
- Swipe left: delete. Swipe right: pin.

---

### New Chat / Chat Screen

**Header:**
- `☰` left menu
- Center: chat title (editable) + sub-line: `"{Assistant} / {Model} ({Provider})"` — muted small
- Right: `≡ Prompts` icon · `😊+ New Assistant` icon

**Chat Area:**
- User messages: right-aligned, rounded bubble, accent-tinted background
- AI messages: left-aligned, flat card with assistant avatar + name above, full-width
- Below each AI message: `📋 Copy` · `↺ Retry` · `🔊 TTS` · `🖼️ Render` · `⋮ More`
- Token stats line below AI message: `↑ 185 tokens · ↓ 10 tokens · ⚡ 434 tok/s · ⏱ 0.0s` — muted, tiny
- Streaming: text types in progressively, cursor blinking at end

**Input Bar (bottom):**
- Multi-line text input: `"Chat with AI..."` placeholder
- Left of input: provider logo icon (tappable → switch assistant/provider)
- Right of input: `🔍 Web Search toggle` · `+ Attach` · `↑ Send` (filled circle when has text)

**Response Template System (unique to requestr):**
- When setting up a chat source, a `"Response Format"` picker appears:
  - `OpenAI Compatible` — extracts `choices[0].message.content`
  - `Gemini` — extracts `candidates[0].content.parts[0].text`
  - `Anthropic` — extracts `content[0].text`
  - `Grok / xAI` — OpenAI-compatible
  - `OpenRouter` — OpenAI-compatible
  - `Streaming (SSE)` — parses `data:` chunks
  - `Raw` — shows full JSON response unparsed
  - `Custom` — user defines JSON path extractor (e.g. `data.result.message`)

---

### Providers Screen

**Header:**
- `←` back · Title: `"Providers"` · `📥 Import` · `+ Add`

**Search bar:** `"Search providers..."`

**2-col grid of provider cards:**
Each card:
- Provider logo/icon
- Provider name
- `Enabled` (green badge) or `Disabled` (amber badge)
- Model count badge (blue pill)
- Disabled cards: subtle red/pink tint background
- `⋯` drag handle top-right

**Add Provider bottom sheet:**
- Template tabs: `OpenAI` · `Google` · `Claude` · `Grok` · `OpenRouter` · `Custom`
- Fields: Enable toggle · Name · API Key · Base URL · API Path · Response API checkbox
- `Cancel` · `Add` buttons

---

### Assistant Config Screen

- Back `←` + `"Default Assistant"` title
- Large avatar circle (initials) at top, centered
- Settings rows (Rikka-style, each with icon + title + description + `>`):
  - `⚙ Basic Settings` — *"Name, avatar, model, parameters"*
  - `💬 Prompts` — *"System prompts, message templates, regex rules"*
  - `🧩 Extensions` — *"Quick messages, prompt injections, lorebooks"*
  - `🧠 Memory` — *"Assistant memory and recent chat references"*
  - `{...} Custom Request` — *"Custom headers and body parameters"*
  - `🔧 MCP` — *"MCP server tools for this assistant"*
  - `📖 Local Tools` — *"JS engine, clipboard, TTS, Ask User"*

---

### Logs Screen

- Back `←` · Title: `"Logs"` · `🗑️ Clear all`
- List of request log cards:
  - Method badge (POST=blue, GET=green)
  - Full URL (monospace, truncated with expand)
  - Status badge (200=green, 4xx/5xx=red) + response time
  - Timestamp (right-aligned)
  - Tap to expand: full request + response details
  - Long press: copy URL, open in API Testr

---

### Statistics Screen

- Back `←` · Title: `"Statistics"`
- **Chat Heatmap** — GitHub contribution graph style, weeks as columns, days as rows, intensity by message count, accent color gradient
- **2-col stat cards:**
  - Total Conversations
  - Total Messages
  - Input Tokens
  - Output Tokens
  - API Requests Made
  - Tools Used
  - App Launch Count

---

## 📝 Section 4 — Notes

### Notes Home

**Header:**
- `"Notes ▾"` — tapping opens workspace/notebook switcher dropdown
- Right: `≡ Sort` · `⊟ Layout toggle (grid/list)` · `⚙ Settings`

**Search bar:** `"Search notes..."` full width, rounded

**Folders Section:**
- Each folder row: colored accent label (user-set color) + folder name + note count in parens + `⋮` menu + `>` to open
- Colors are vivid and distinct per folder
- `⋮` menu: Rename, Change Color, Delete, Move notes

**"Others" / Ungrouped:**
- Collapsible section header with `▾` toggle
- 2-col card grid below:
  - Each card: timestamp (small, muted) + title (bold) + preview text (2 lines, muted)
  - Card tap → open note editor
  - Long press → context menu: Pin, Move to folder, Delete, Duplicate

**Bottom Bar:**
- `📄 Notes` (active) · `+` FAB (centered, large, dark filled circle) · `☑ Lists`
- FAB tap → bottom sheet: `Blank Note` · `Markdown Note` · `HTML Note` · `Checklist`

---

### Note Editor Screen

**Header:**
- `←` back (auto-saves)
- Center: note title (editable inline, tapping activates)
- Right: `⋮ More` (Export MD, Export HTML, Share, Move, Delete)

**Format Toggle Bar (below header):**
- `Plain` · `Markdown` · `HTML` — segmented control
- When Markdown: formatting toolbar appears: `B` · `I` · `H1/H2` · `—` · `[]` · `🔗` · `\`` · code block

**Editor:**
- Full screen text area, monospace for MD/HTML modes, serif/sans for plain
- Markdown: live preview toggle (split-screen or toggle between edit/preview)
- HTML: syntax highlighted editor + `▷ Preview` button opens rendered preview overlay

**Bottom bar:**
- `⌨ Keyboard` dismiss · word count · character count

---

## 🗺️ Section 5 — Flow (Mindmap / Node Editor)

### Flow Home

**Header:**
- `"Flow"` title
- Right: `+ New Flow` · `📥 Import`

**Flow cards grid:**
- Each card: flow name + node count + last edited + thumbnail preview (mini node graph)
- Long press: Duplicate, Delete, Export

---

### Flow Canvas (ComfyUI-style)

**Canvas:**
- Infinite dark canvas (near-black, subtle dot grid pattern)
- Pinch to zoom, two-finger pan
- Mini-map in bottom-right corner (toggle)
- Undo/Redo buttons top-center (floating pill)

**Nodes — rectangular, not circles:**
- Header bar: node type icon + node name (colored per type)
- Body: configurable fields (inputs, dropdowns, text areas)
- Left side ports: input connectors (circles on left edge, labeled)
- Right side ports: output connectors (circles on right edge, labeled)
- Selected node: glowing accent border
- Node types:
  - 🌐 **API Node** — select a saved API, configure params, output = response body
  - 🔧 **Tool Node** — pick from Tools section, input/output fields
  - 💬 **Chat Node** — AI provider + prompt template, output = AI response
  - 🔀 **Condition Node** — if/else logic with expression input
  - 📝 **Text Node** — static text/template input
  - 📤 **Output Node** — display result, save to note, send notification
  - 🔄 **Transform Node** — JSON path extractor, regex, format converter
  - ⏱️ **Trigger Node** — manual, scheduled, webhook

**Connections:**
- Bezier curves between ports, accent color
- Drag from output port → hover near input port → snaps and connects
- Tap connection line → delete option

**Long press on canvas:** context menu:
- Add Node (opens node picker sheet)
- Paste node
- Select all

**Bottom Toolbar (floating pill):**
- `← Back` · `⊟ Row/Grid toggle` · `✏ Edit mode` · `📝 Annotation` · `🔤 Rename` · `▷ Run Flow`

**Node Picker Sheet (when adding):**
- Search bar
- Grid of node type cards with icon + name + description

---

## 🎵 Section 6 — Music

### Music Screen

**Layout:**
- Dark full-screen player aesthetic
- Background: blurred album art (if available) as full-screen backdrop with heavy dark overlay

**Top:**
- `←` back / close
- `"Music"` title
- `⋮` More (EQ, sleep timer, settings)

**Now Playing (center):**
- Album art: large rounded square card, subtle shadow, slightly tilted/floating
- Song title: large, bold
- Artist: muted, smaller
- Source badge: `YouTube Music` / `Local` / `JioSaavn` etc.

**Progress Bar:**
- Thin accent-colored scrubber
- Current time (left) · Total time (right)

**Controls:**
- Row: `⏮ Prev` · `◁◁ Rewind 10s` · `▶/⏸ Play/Pause` (large center circle) · `▷▷ Forward 10s` · `⏭ Next`
- Below: `🔀 Shuffle` · `🔁 Repeat` (off/one/all)

**Queue Section (scrollable below):**
- `"Up Next"` label
- List of queued tracks: thumbnail + title + artist + duration
- Drag handle to reorder
- Swipe to remove from queue

**Sources Selector:**
- Bottom sheet when tapping source badge
- Options: Local Files, YouTube Music, JioSaavn
- Each with toggle enable/disable

**Mini Player (when music is playing and user is in another section):**
- Persistent bottom bar just above the nav bar
- Album art thumbnail + song title + artist
- `◁◁` · `▶/⏸` · `▷▷` controls
- Tap to expand back to full player

---

## ⚙️ Section 7 — Settings

### Settings Home

**Header:**
- `←` back · `"Settings"` title

**User Card (top):**
- Avatar circle + user name + `✏ Edit` icon
- Tap to edit name/avatar

**Sections (each a clearly labeled group with contained rows):**

**🎨 Appearance**
- Theme: `Midnight` / `Obsidian` / `Dusk` / `System` — visual swatches
- Accent Color: color picker wheel
- Dynamic Color (Android 12+): toggle
- Corner Roundness: slider (0 = sharp → 24dp = pill)
- Font: `Sora` / `DM Sans` / `System` selector
- Code Font: `JetBrains Mono` / `Fira Code` / `Source Code Pro`
- Font Size: slider (Small / Medium / Large / XL)
- UI Density: `Compact` / `Normal` / `Comfortable`

**🌐 API Testr**
- Default HTTP Method: dropdown
- Request Timeout (ms): number input
- Follow Redirects: toggle
- SSL Verification: toggle
- Auto-beautify Response: toggle
- Default Code Snippet Language: dropdown
- Save Request History: toggle + history limit
- Text Selection Tools: toggle (decode/encrypt on select)

**💬 Chats**
- Default Provider: dropdown (lists configured providers)
- Default Model: dropdown
- Streaming: toggle
- Show Token Stats: toggle
- Auto-scroll to bottom: toggle
- Message font size: slider
- TTS Voice: system voice picker
- Chat History Limit: number input

**📝 Notes**
- Default Note Format: `Plain` / `Markdown` / `HTML`
- Auto-save interval (seconds): slider
- Show word count: toggle
- Markdown preview mode: `Split` / `Toggle`
- Note card preview lines: `1` / `2` / `3`

**🔧 Tools**
- Favorite tools (reorder list)
- Default AES mode: `CBC` / `GCM` / `ECB`
- Default hash algorithm: dropdown
- Show tool descriptions in grid: toggle

**🗺️ Flow**
- Canvas theme: `Dark` / `Darker` / `Midnight`
- Show dot grid: toggle
- Auto-save flows: toggle
- Default connection style: `Bezier` / `Step` / `Straight`
- Show mini-map by default: toggle

**🎵 Music**
- Default source: `Local` / `YouTube Music` / `JioSaavn`
- Audio quality preference: `Normal` / `High` / `Best Available`
- Crossfade: slider (0–12s)
- Show mini player: toggle
- Sleep timer default: number input (minutes)

**💾 Data**
- Export all settings: button → exports JSON file
- Import settings: button → pick file
- Export all data (APIs + Notes + Flows): button → zip file
- Import data: button
- Clear all data: destructive button (confirmation dialog)
- Clear cache: button + shows cache size

**🔔 Notifications**
- Request completion alerts: toggle
- Background flow alerts: toggle
- Notification sound: toggle

**ℹ️ About**
- App version + build number
- Changelog
- Licenses
- Source / GitHub link
- `requestr` — *your api. your tools. your world.*

---

## 🔗 In-Page Mini Settings (Per Section)

Each section has a small `⚙` gear icon in its header that opens a **bottom sheet** with 2–3 key settings for that section only, and a `"More settings →"` row at the bottom that deeplinks directly to that section's full settings page.

**Example — APIs mini settings:**
- SSL Verification: toggle
- Auto-beautify Response: toggle
- `More API Settings →` (opens Settings > API Testr)

**Example — Notes mini settings:**
- Default format: segmented (Plain / MD / HTML)
- Auto-save: toggle
- `More Note Settings →`

---

## 🎨 Global Design Tokens

```
Background:      #0A0E1A  (deep midnight navy)
Surface:         #111827  (card/sheet background)
Surface variant: #1C2333  (elevated surface)
Border:          #1E2D45  (subtle borders)
Accent primary:  #3B82F6  (electric blue)
Accent bright:   #60A5FA  (highlight/hover)
Accent cyan:     #06B6D4  (secondary accent, WS/live)
Success:         #22C55E  (GET, 200, saved)
Warning:         #F59E0B  (PUT, unsaved, disabled)
Error:           #EF4444  (DELETE, 4xx/5xx)
Purple:          #8B5CF6  (PATCH)
Text primary:    #F1F5F9
Text secondary:  #94A3B8
Text muted:      #475569
Code font:       JetBrains Mono
UI font:         Sora
Border radius:   16dp (configurable)
Card elevation:  subtle shadow + 1px border
Glass effect:    background blur + 10% white overlay
```

---

## 📱 Navigation Architecture

**Bottom Nav (always visible):**
`Home` · `APIs` · `Tools` · `Chats` · `More ···`

**`More` bottom sheet:**
`Notes` · `Flow` · `Music` · `Settings` · `Logs`

**Back stack:** standard Android back stack. Each section maintains its own back stack independently.

**Deeplinks (for mini settings redirects):**
- `requestr://settings/apis`
- `requestr://settings/chats`
- `requestr://settings/notes`
- `requestr://settings/tools`
- `requestr://settings/flow`
- `requestr://settings/music`
- `requestr://settings/appearance`

---

*End of requestr Stitch Prompt — v1.0*
*Built for: Google Stitch AI app generation*
*Audience: Power users — scrapers, API hackers, reverse engineers*
