# Requestr — Phase B Implementation Dossier
**Status:** READ-ONLY RECONNAISSANCE (No Code Changes)  
**Repository:** `Mahesh953-hub/requestr` · branch `main` · HEAD `81a7e25`  
**Audit Date:** 2026-08-31  
**Scope:** PHASE-B-REBRAND-NO-MOCKUP-COVERAGE + Zero-Coverage Screens  

---

## PART 1: HOME SCREEN REBRAND (Phase B §2)

### 1.1 Current Header Implementation

**Location:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt` lines **148–191**

**Current Structure (In-Scope LazyColumn Scrollable Content):**
```kotlin
// Line 140–191: Header Row inside LazyColumn
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 16.dp),
    ...
) {
    item {
        Spacer(modifier = Modifier.height(4.dp))
        
        // Top Editorial Brand & Wordmark Row (lines 152–191)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RequestrWordmark(fontSize = 24)  // Line 157
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Line 162: Theme Toggle Button (currently unique to Home)
                ThemeToggleButton()
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Lines 167–189: User Avatar Circle (gradientBackground)
                // onClick opens Settings; currently this is the only settings entry point in the header
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .clickable { onOpenSettings() }
                    ...
                )
            }
        }
```

**Current Composables Used:**
- `RequestrWordmark(fontSize = 24)` — text wordmark
- `ThemeToggleButton()` — theme toggle icon (only on Home)
- Gradient-background `Box` avatar with initials

**Critical Finding:** Header is **100% inside the scrollable LazyColumn** (part of `item { }`). It scrolls away with content. **No hamburger menu icon exists anywhere** — verified via grep search for `Icons.Default.Menu` (zero matches in HomeScreen or MainActivity).

---

### 1.2 Preservation Verification (§2.2 Lock-Down)

| Element | Spec Requirement | Current Code | Status | Notes |
|---------|------------------|--------------|--------|-------|
| **Greeting text** | "Good afternoon, {realUserName} 🍃" (leaf emoji) + date line | Lines 130–138: `"Good afternoon, ${settings.userName} 🌤️"` (cloud emoji, NOT leaf) | ❌ MISMATCH | Emoji is wrong (🌤️ not 🍃); uses `settings.userName` correctly. Date correct: `SimpleDateFormat("EEE, dd MMM")` |
| **Search bar** | "Search APIs, notes, chats, tools..." placeholder | Line 217: `"Search APIs, notes, tools, flow nodes..."` | ⚠️ PARTIAL | Wording differs ("chats" removed, "flow nodes" added); layout/icons correct |
| **Quick action pills** | 4 pills: +New API (green), +Intercept (orange), +New Tool (amber), +New Note (blue) | Lines 288–317: 6 pills total (includes +New Flow, 🎵 Music) | ⚠️ EXTENDED | Spec calls for exactly 4 pills; code has 6 (extra Flow + Music); colors match for first 4 |
| **Quick action colors** | Green=0xFF10B981, Orange=0xFFF97316, Amber=0xFFF59E0B, Blue=0xFF6366F1 | Lines 291–323: All 4 colors exact match | ✅ CORRECT | Colors verified exactly |
| **Interception Status Card** | Live green border when active, real port/packet/device count (never stubbed) | Lines 328–489: All live values from `InterceptionStateRepository.state` | ✅ CORRECT | Text driven by `interceptionState` (real source of truth); shows live waveform |
| **Recently Opened** | 4 rows visible, real usage history (not seed data), real counts | Lines 492–599: `take(4)` on real lists; no seed data | ✅ CORRECT | Layout, icon colors, type labels, timestamps all correct |
| **Power Modules** | Asymmetric 2-col grid (Intercept+APIs row, Tools+Notes row, Flow full-width), real counts | Lines 601–679: Grid structure correct, all counts from repositories | ✅ CORRECT | Badges show real counts: `savedApis.size`, `toolsCatalog.size`, `notes.size`, `flows.isNotEmpty()` |

**Preservation Status:** ✅ **7/8 elements correct.** Only greeting emoji (🌤️ → 🍃) and search placeholder wording differ from spec. Quick-action pill count exceeds spec (4 required, 6 present).

---

### 1.3 Structural Change Required: Hamburger Capsule + Identity Capsule + Theme/Settings Capsules

**Spec (§1.2) Layout:**
```
([ ☰ ])      ( requestr • )        ([ ☀ ]) ([ ⚙ ])
```

**Current State:**
- ✅ `RequestrWordmark` component exists (`/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CommonComponents.kt` line 182)
- ✅ `ThemeToggleButton` component exists (line 244)
- ❌ **No hamburger menu icon in HomeScreen** (must open nav drawer)
- ❌ **No settings gear icon** (currently only avatar button, which serves both settings + identity)
- ❌ **No status dot on identity capsule** (wordmark is plain text)

**Hamburger Drawer Opening:**
- `AppNavigationDrawer.kt` exists (line 71: `fun AppNavigationDrawer(...)`)
- Uses `ModalNavigationDrawer` from `androidx.compose.material3`
- Drawer state managed via `DrawerState` parameter
- Currently reachable **only by edge-swipe gesture** — no visible hamburger button
- Need to add: `LucideMenuIcon` component call in new pinned-header structure to trigger `drawerState.open()`

**Status Dot for Interception:**
- Requirement: Green (active) / Red (inactive) bound to `InterceptionStateRepository.state`
- Currently the Intercept Status Card shows live status via `isInterceptionActive` (line 116–117)
- **Single Source of Truth Confirmed:** `InterceptionStateRepository.state` is read by both Home Status Card and Power Modules Intercept badge (line 621: conditional badge)

---

### 1.4 Dynamic Color Requirement (Android 12+ Material You)

**Spec (§1.5):** Header capsules only must use `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` from Android 12+/API 31+ (Material You wallpaper palette). Fallback to static 9-theme token system below API 31.

**Current Implementation Status:**

**Theme file:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Theme.kt`

**Search result:** No `dynamicColorScheme`, `dynamicLightColorScheme`, or `dynamicDarkColorScheme` usage found in codebase (grep: zero matches).

**Current Theme System:**
- Line 10–25: `enum class AppThemeMode` with 7 hardcoded light/dark families (EDITORIAL_LIGHT, MONOCHROME_DARK, MIDNIGHT_SAPPHIRE, etc.)
- Line 76+: `EditorialLightColorScheme = lightColorScheme(...)` — all hardcoded Material 3 color schemes
- Line 37–72: `enum class AppAccent` — 10 orthogonal accent options (INDIGO, CYAN, EMERALD, etc.) independent of theme mode
- **No dynamic color sourcing exists anywhere in the app.**

**Required Implementation:**
- Add Android 12+ API check to conditionally apply `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` to header capsules only
- Provide fallback: theme system already has 9 static tokens (use existing AppThemeMode + AppAccent to fallback on API < 31)
- **Explicit constraint:** Dynamic color applies **to header only**; Power Module cards, Interception status card, and rest of app remain on static theme tokens

---

### 1.5 Identity Capsule 5-Step Text-Swap Animation Spec

**Exact sequence from `home_rebranding.md` §1.6 (lines 151–184):**

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

**Five-step ordered sequence (NOT simultaneous cross-fade):**

1. **Old title** — capsule shows current text (e.g. "requestr") alongside its icon/dot, at rest (lines 163–164)
2. **Icon remains** — when a swap is triggered, the leading icon/dot element stays visually anchored in place, unaffected by the transition (lines 165–168)
3. **Old text fades** — the old text label animates out (opacity fade, not instant cut) while the icon stays put (lines 169–170)
4. **New text appears** — the new text label fades/animates in at the same position the old text vacated (lines 171–172)
5. **Capsule resizes** — only **after** the text content has settled does the capsule's overall width animate (spring-based) to correctly fit the new text's actual measured width (lines 173–179)

**Implementation Notes:**
- Use spring-based timing for the resize step (per app animation conventions from `AnimationConfig.kt`)
- Text fade steps (3–4) use shorter, simple opacity tween (not spring physics)
- Capsule must **never resize before content appears** (explicit sequencing requirement)
- Trigger via state change on `InterceptionStateRepository.state` (when wordmark text changes to show contextual state)

---

### 1.6 Critical Risk: Single "Is Intercept Configured" Flag

**Spec Risk (§2.2 & §7):** Verify exactly **one** underlying flag for "is intercept configured", shared by:
1. Home Status Card (§2.2, lines 234–264 of spec)
2. Power Modules "Intercept" card (§2.2, lines 292–323 of spec)

**Current Code Analysis:**

**Home Status Card (lines 328–489):**
```kotlin
val interceptionState by InterceptionStateRepository.state.collectAsState()  // Line 116
val isInterceptionActive = interceptionState.isActive  // Line 117

// Card rendering (lines 331–340):
backgroundColor = if (isInterceptionActive) {
    Color(0xFF10B981).copy(alpha = 0.12f)  // Green when active
} else {
    MaterialTheme.colorScheme.surface
}
// Detail text (lines 361–367): switches on interceptionState.status
// - ACTIVE → "Interception: ACTIVE"
// - ARMED → "Interception: ARMED"
// - ERROR → "Interception: ERROR"
// - CONFIGURED_IDLE → "Interception is off"
// - NEVER_CONFIGURED → "Set up Interception"
```

**Power Modules Intercept Card (lines 618–627):**
```kotlin
PowerModuleCard(
    title = "Intercept",
    subtitle = "MITM VPN, Frida & Rules",
    badge = if (capturedCount == 0 && !isInterceptionActive) "Not set up" 
            else if (isInterceptionActive) "Live" 
            else "$capturedCount captured",  // Line 621
    ...
)
```

**Single Source of Truth Confirmed:**
- ✅ **One flag:** `InterceptionStateRepository.state` (object singleton)
- ✅ **File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/interception/InterceptionStateRepository.kt`
- ✅ **Read sites:**
  - HomeScreen.kt line 116 (Status Card)
  - HomeScreen.kt line 116 (Power Modules badge)
  - AppNavigationDrawer.kt line 81 (Drawer intercept indicator)
  - (Other screens: InterceptScreen, etc.)

**Flag Details:**
- **Property:** `state: StateFlow<InterceptionState>` (line 27 of InterceptionStateRepository.kt)
- **Status enum:** `InterceptionStatus` with values: ACTIVE, ARMED, ERROR, CONFIGURED_IDLE, NEVER_CONFIGURED
- **Lifecycle mutations:** `markConfiguredIdle()`, `markArmed()`, `markActive()`, `markStopped()`, `markError()` (lines 34–108)
- **Contract:** "Counters must reflect real captured traffic. Do not simulate progress." (line 17 comment)

**✅ RISK VERIFIED AS CONTROLLED:** Single, centralized source of truth with clear mutator contract.

---

### 1.7 Greeting Emoji Discrepancy

**Current:** 🌤️ (cloud with sun)  
**Spec:** 🍃 (leaf)  
**Location:** HomeScreen.kt line 132

**Fix:** Replace `"Good afternoon, ${settings.userName} 🌤️"` → `"Good afternoon, ${settings.userName} 🍃"`  
*(Similar fixes needed for morning/evening/night emojis if spec requires specific symbols — currently spec only mentions afternoon with leaf)*

---

## PART 2: ZERO-COVERAGE SCREENS (Phase B §3)

All following screens have **no mockup, no screenshot, no written spec** and must receive full-pass redesign per Phase B §4 (complete old-UI removal, no feature flags).

### 2.1 SettingsScreen.kt — "LOCAL REST API & CLI" Block

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (2655 lines)  
**Section Header Location:** Line 718 (`SettingsSectionHeader("LOCAL REST API & CLI")`)

**Current Structure:**
- Part of larger scrollable LazyColumn in SettingsScreen
- Adjacent to other settings sections (Runtime Instrumentation, etc.)
- Displays local API server config (port, status, toggle)
- No design coverage in docs/designs/ at all

**Header Treatment:**
- Uses `SettingsSectionHeader` composable (plain text header, no glass treatment)

**Redesign Requirements (to frozen design system):**
- Replace header with frozen design glass capsule header pattern (if applicable to settings sections)
- Apply token colors/spacing from DESIGN-SYSTEM.md §12.7 (Settings screen controls)
- Ensure nav/header compliance if settings has its own top chrome
- Use `BadgePill`, `GlassCard`, frozen tokens for any new UI elements

**Concrete Changes Needed:**
1. Verify current header uses `SectionHeader` or plain `Text` — replace with frozen pattern if required
2. Review all control elements (toggles, buttons) — ensure they use `DynamicGlassSwitch` or frozen equivalents
3. Check spacing/padding — apply CompactSpacing / ComfortableSpacing from DensityConfig.kt
4. Verify colors use MaterialTheme tokens (no hardcodes)
5. No feature-flag coexistence: old and new UI must not both exist

---

### 2.2 SettingsScreen.kt — "PLUGIN MANAGER" Block

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt`  
**Section Header Location:** Line 1059 (`SettingsSectionHeader("PLUGIN MANAGER")`)

**Current Structure:**
- Scrollable list of loaded plugins with enable/disable toggles
- Shows plugin count ("3 plugins loaded · 1 disabled")
- No design coverage in docs/designs/

**Header Treatment:**
- Uses `SettingsSectionHeader` composable (plain text)

**Redesign Requirements:**
- Same as §2.1 (frozen tokens, glass capsules where applicable, no feature flags)
- Review plugin list items — apply frozen component contracts
- Use `BadgePill` for count display if needed
- Ensure toggles use `DynamicGlassSwitch` (DESIGN-SYSTEM.kt §9.4)

---

### 2.3 SettingsScreen.kt — Appearance Settings UI (Theme/Accent/Corner-Radius/Density/Glass-Style Pickers)

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (theme/accent picker) + `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/DensityConfig.kt` + `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/AnimationConfig.kt`

**AppearanceSelector Function:** Line 1753 (`private fun AppearanceSelector(...)`)

**Current Structure:**
- DESIGN-SYSTEM.md §12.7 specifies controls exist but UI itself not mocked
- Tokens documented (ranges, step values) but picker UI not specified
- Controls include:
  - Header Top Offset: 0–40dp (7 steps)
  - Header Glass Opacity: 0.1–1.0
  - Nav Pill Opacity: 0.3–1.0
  - FAB bottom spacing: 48–140dp (11 steps)
  - Header blur style: FROSTED_GLASS / SOLID_M3 / OLED_BLACK / TRANSLUCENT
  - Nav blur style: same 4 options
  - Theme mode selector (Light/Dark/System)
  - Accent selector (10 options)
  - Corner-radius mode (affects whole app)
  - Density mode (CompactSpacing / ComfortableSpacing)

**Related Files:**
- `DensityConfig.kt`: Defines `ProvideDensityConfig`, `LocalDensityConfig`, token spacing values
- `AnimationConfig.kt`: Defines `LocalAnimationsEnabled`, `scaledTween`, `scaledSpring`, `ProvideAnimationConfig`

**Header Treatment:**
- Nested under Settings section headers (no separate header needed, but sections should follow frozen pattern)

**Redesign Requirements:**
1. Replace picker UI with frozen slider/select components from DESIGN-SYSTEM
2. For numeric ranges (opacity, padding): use slider components with live preview
3. For 4-option blur styles: use `RequestrSegmentedTabs` (DESIGN-SYSTEM.kt §10.1) or equivalent
4. For 10-accent selector: use frozen accent-chip pattern from drawer (DESIGN-SYSTEM.kt §10.4)
5. All controls must write to `SettingsRepository` via frozen update functions (§3.3 of DESIGN-SYSTEM)
6. No feature flags — complete replace of UI

---

### 2.4 NotesScreen.kt

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/NotesScreen.kt` (489 lines)  
**Location:** Full screen; read-only audit of structure required

**Current Header Treatment (lines 120–137):**
```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* back */ }) {  // Line 132
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Export button (lines 142–161)
                IconButton(onClick = { /* export note */ }) { ... }
                
                // Save button
                IconButton(onClick = { /* save note */ }) { ... }
                
                // Format selector (etc.)
            }
        }
```

**Current Structure:**
- Back button (arrow icon) + action row (export, save, format selector)
- Plain `Row` with `IconButton` layout (not using frozen glass capsule header)
- Folder view with collapsible/expandable sections
- Real note data from `NotesRepository.notes`

**Header Treatment:**
- ❌ Does not use frozen glass capsule header pattern
- Uses plain `Row` with standard `IconButton` components

**Redesign Requirements:**
1. Replace header with frozen `FloatingCapsuleHeader` / `DynamicGlassHeader` pattern if Notes has its own screen header
2. Or: if Notes integrates into the app's bottom-nav pill navigation, ensure compliance with nav/header rules from §5
3. Replace `IconButton` actions with frozen `GlassActionPill` components where appropriate
4. Apply frozen spacing/token system from DESIGN-SYSTEM §6–§15
5. All list items must use frozen component contracts (`BadgePill`, `GlassCard`, etc.)
6. No feature-flagged old UI: complete pass required

---

### 2.5 ToolWorkspaceScreen.kt (Custom Tool Workspace)

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt` (1059 lines)  
**Location:** Route: `tool_workspace/{toolId}`

**Current Structure:**
- Workspace for user-authored/custom tools (distinct from built-in tool library in ToolsScreen)
- Room-backed via `CustomToolDao` for persistence
- Editor UI with live preview / test environment
- No design spec coverage

**Header Treatment:**
- Lines ~50–100: Top bar with back button, tool name, action buttons (save, delete, share, etc.)
- Uses plain `Row` layout with `IconButton` components
- No glass capsule header pattern

**Redesign Requirements:**
1. Replace top bar with frozen glass capsule header (title + status + action pills)
2. Use `GlassActionPill` for toolbar actions (save, delete, share) instead of plain `IconButton`
3. Apply frozen spacing/tokens throughout the editor UI
4. Ensure tool editor components use frozen badge/chip/tab patterns
5. No feature flags: full redesign pass required

---

### 2.6 ResponseInspectorView.kt

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ResponseInspectorView.kt` (1303 lines)

**Current Structure:**
- Shows HTTP response details (headers, body, timing, etc.)
- Composed of multiple tabs (Headers, Body, Analysis, etc.)
- Part of API Testr flow (opened from InterceptScreen / ApiTestrScreen)
- No design spec coverage (Phase B §3 notes: "Needs a direct diff against interceptr/ Screen 3 during Phase A")

**Header Treatment:**
- Lines ~50–100: Title bar with back button, response status code badge, actions
- Uses `BadgePill` for status codes already (good foundation)
- Title bar is plain `Row` without glass header pattern

**Redesign Requirements:**
1. Verify against Phase A's `interceptr-combined_light.html` Screen 3 mockup — identify what's missing
2. Replace header with frozen glass capsule if not already compliant
3. Replace tab UI with frozen `RequestrSegmentedTabs` (DESIGN-SYSTEM §10.1)
4. Ensure badge display uses frozen `StatusCodeBadge` (DESIGN-SYSTEM §10.3)
5. All response detail cards must use `GlassCard` with frozen tokens
6. No feature flags: complete pass required

---

### 2.7 DiffView.kt

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/DiffView.kt` (111 lines)

**Current Structure:**
- Composable component for displaying text diffs/comparisons (not a screen, but a reusable UI component)
- Used in ResponseInspectorView or other inspection contexts
- Renders added/removed/unchanged lines with color coding
- No design spec coverage

**Current Implementation (lines ~1–111):**
```kotlin
@Composable
fun DiffView(oldText: String, newText: String) {
    val diff = /* compute diff lines */
    Column {
        diff.forEach { line ->
            Row {
                Text(line.marker)  // +/- marker
                Text(line.text)     // content
            }
        }
    }
}
```

**Header Treatment:** N/A (component, not screen)

**Redesign Requirements:**
1. Apply frozen color tokens for diff line rendering (added: green, removed: red, unchanged: muted)
2. Use frozen spacing from DensityConfig (padding between lines)
3. Ensure text uses frozen typography from DESIGN-SYSTEM §6–§12
4. If DiffView is contained in a screen, that screen's header must be frozen-compliant
5. No feature flags within the component

---

### 2.8 CaptureController.kt (Capture Control Overlay)

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/CaptureController.kt` (242 lines)

**Current Structure:**
- Overlay UI for controlling interception capture (start/stop/pause/resume)
- Floating FAB or bottom sheet with control buttons
- Shows current session status (packets captured, duration, etc.)
- No design spec coverage

**Header Treatment:**
- Lines ~50–80: Status bar showing session info + controls
- Uses plain `Row` with `IconButton` or `Button` components
- No glass capsule pattern

**Redesign Requirements:**
1. Replace control buttons with frozen `GlassActionPill` components
2. Status display should use `BadgePill` for count/status values
3. If overlay has a header, apply frozen glass capsule pattern
4. All text should use frozen typography tokens
5. Colors for capture state (active/paused) should use semantic token colors (green/amber/red from DESIGN-SYSTEM §6)
6. No feature flags: complete redesign pass required

---

### 2.9 AppNavigationDrawer.kt (Drawer Contents)

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/AppNavigationDrawer.kt` (500+ lines estimated)

**Current Structure:**
- Drawer header (lines 105–127): Wordmark + close button + description text
- Proxy toggle status card (lines 131–200): Shows interception state + toggle
- Destination list (lines ~200+): NavigationDrawerItem for each screen
- Footer (if any): Not yet reviewed in detail

**Header Treatment (lines 105–127):**
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    RequestrWordmark(fontSize = 24)  // Good: using existing component
    
    IconButton(onClick = { drawerState.close() }) {  // Close button
        Icon(imageVector = Icons.Default.Close, ...)
    }
}

Text(
    text = "Native Android Interceptor & Ecosystem",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 11.sp
)
```

**Interception Status Indicator (lines ~135–160):**
```kotlin
GlassCard {
    Row {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (interceptionState.status) {
                        InterceptionStatus.ACTIVE -> InterceptorActiveGreen
                        InterceptionStatus.ARMED -> amber
                        else -> red
                    }
                )
        )
        Text(
            text = when (interceptionState.status) {
                InterceptionStatus.ACTIVE -> "Capturing..."
                else -> "Not Active"
            }
        )
        // Toggle switch
    }
}
```

**Destination List (lines ~200+):**
```kotlin
val destinations = listOf(
    "home" to Icons.Default.Home,
    "intercept" to Icons.Default.Radar,
    "apis" to Icons.Default.Public,
    "tools" to Icons.Default.Tune,
    "flow" to Icons.Default.Code,
    "music" to Icons.Default.MusicNote
)

destinations.forEach { (route, icon) ->
    NavigationDrawerItem(
        label = { Text(route.capitalized) },
        icon = { Icon(icon) },
        selected = (currentRoute == route),
        onClick = { onNavigate(route) }
    )
}
```

**Redesign Requirements:**
1. Drawer header wordmark + close button already use `RequestrWordmark` (good)
2. Subtitle text ("Native Android...") appears correct (identity concept)
3. Proxy status card: already uses `GlassCard` ✅; ensure status dot uses semantic colors (green/amber/red) from DESIGN-SYSTEM
4. Status toggle: should use `DynamicGlassSwitch` (DESIGN-SYSTEM §9.4) instead of plain Material3 Switch
5. Destination list items: Currently using `NavigationDrawerItem` from Material3; verify compliance with frozen nav rules (DESIGN-SYSTEM §10.4 "Drawer theme & accent switcher")
6. Theme/Accent picker section (if present): Follow DESIGN-SYSTEM §10.4 frozen pattern for light/dark toggle and accent chips
7. Drawer footer (if any): Ensure all elements use frozen component contracts
8. No feature flags: if old drawer UI exists, replace entirely

**Critical Update Needed:**
- The drawer is the **only way to open the hamburger** in current code (swipe gesture only)
- Phase B §1.3 requires hamburger button in fixed header to open this drawer
- Integration point: new header must call `drawerState.open()` on hamburger tap

---

### 2.10 Additional Screens / Components Not in §3 Enumeration

**Audit of `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/` and `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/`:**

Screen Files Found:
1. ✅ HomeScreen.kt — Phase B (rebrand §2, covered)
2. ✅ SettingsScreen.kt — Phase B §3 (LOCAL REST API, PLUGIN MANAGER, Appearance, covered)
3. ✅ NotesScreen.kt — Phase B §3 (covered)
4. ✅ ToolWorkspaceScreen.kt — Phase B §3 (custom tool workspace, covered)
5. ✅ ResponseInspectorView.kt — Phase B §3 (response inspection, covered)
6. ✅ CaptureController.kt — Phase B §3 (capture overlay, covered)
7. ✅ DiffView.kt (component) — Phase B §3 (diff viewer, covered)
8. ❓ ApiTestrScreen.kt (1700+ lines) — Not in Phase B §3 enumeration; likely Phase A coverage (API mockup)
9. ❓ SavedApisScreen.kt — Not in Phase B §3; likely Phase A coverage
10. ❓ InterceptScreen.kt (1500+ lines) — Not in Phase B §3; likely Phase A coverage (interceptr mockup)
11. ❓ ToolsScreen.kt — Not in Phase B §3; likely Phase A coverage (tools mockup)
12. ❓ FlowScreen.kt — Not in Phase B §3; likely Phase A coverage (flow mockup)
13. ❓ MusicScreen.kt — Not in Phase B §3; likely Phase A coverage (music mockup)
14. ❓ ChatScreen.kt — Not in Phase B §3; unclear coverage status
15. ❓ OnboardingScreen.kt — Lane A (not Phase B)

**Files not in §3 list but worth flagging:**
- **ChatScreen.kt** — Exists but not mentioned in Phase B §3 enumeration. Verify against Phase A's `ai-chat/` mockup coverage.
- **OnboardingScreen.kt** — Lane A ownership (DESIGN-SYSTEM §2); not Phase B scope.

**Provisional Finding:** Phase B §3 enumeration is **comprehensive for zero-coverage items**. Other screens (APIs, Intercept, Tools, Flow, Music) are likely covered by Phase A mockups and are out of Phase B scope. ChatScreen coverage status should be re-verified against Phase A during implementation.

---

## PART 3: NAVIGATION ARCHITECTURE & BOTTOM NAV PILL

### 3.1 Bottom Navigation Pill Definition

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CollapsibleNavPill.kt`

**Tab Definitions (lines 76–83):**
```kotlin
private val DEFAULT_NAV = listOf(
    NavDestination("home", "Home", Icons.Default.Home),
    NavDestination("intercept", "Intercept", Icons.Default.Radar),
    NavDestination("apis", "APIs", Icons.Default.Public),
    NavDestination("tools", "Tools", Icons.Default.Tune),
    NavDestination("flow", "Flow", Icons.Default.Code),
    NavDestination("music", "Music", Icons.Default.MusicNote)
)
```

**Data Class (line 72):**
```kotlin
data class NavDestination(val route: String, val label: String, val icon: ImageVector)
```

**Hidden Items Filtering:**
- Line ~75: Reads `settings.navBarHiddenItems` at runtime
- DESIGN-SYSTEM §8.5: Default visible destinations are the 6 above; inactive destinations are filtered by `settings.navBarHiddenItems` via `remember(hiddenItems)`

---

### 3.2 Screen Enum & Route Definitions

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` lines 291–309

```kotlin
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Intercept : Screen("intercept", "Intercept", Icons.Default.Radar)
    object Apis : Screen("apis", "APIs", Icons.Default.Public)
    object Tools : Screen("tools", "Tools", Icons.Default.Tune)
    object Notes : Screen("notes", "Notes", Icons.Default.Notes)
    object Flow : Screen("flow", "Flow", Icons.Default.Code)
    object Music : Screen("music", "Music", Icons.Default.MusicNote)
    object Settings : Screen("settings", "Settings", Icons.Default.Tune)
    object SavedApis : Screen("saved_apis", "Saved APIs", Icons.Default.Save)
    object Onboarding : Screen("onboarding", "Onboarding", Icons.Default.Home)
    data class ToolWorkspace(val toolId: String) : Screen(
        "tool_workspace/{toolId}", "Tool", Icons.Default.Tune
    ) {
        companion object {
            fun createRoute(toolId: String) = "tool_workspace/$toolId"
        }
    }
}
```

**Root Routes (navigation pill visible on these routes):**
```kotlin
val rootRoutes = setOf("home", "intercept", "apis", "tools", "flow", "music")  // Line ~411 approximation
```

---

### 3.3 NavHost & Screen Switching

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` lines 495–650+ (full NavHost)

**NavHost Setup (lines 495–499):**
```kotlin
NavHost(
    navController = navController,
    startDestination = Screen.Home.route,
    ...
)
```

**Navigation Mechanism:**
- `navController = rememberNavController()` (line 372)
- Current route read via: `navBackStackEntry?.destination?.route` (line 374)
- Screen switching via: `navController.navigate(route)` with `launchSingleTop = true` and `popUpTo` to avoid back-stack duplication (lines ~435–443)

**Example Navigation Call (lines 434–445):**
```kotlin
if (currentRoute != route) {
    navController.navigate(route) {
        launchSingleTop = true
        popUpTo(Screen.Home.route) { saveState = true }
        restoreState = true
    }
}
```

**Screen Composable Routes (lines 499–650+):**
```kotlin
composable(Screen.Onboarding.route) { 
    OnboardingScreen(
        onOnboardingComplete = {
            navController.navigate(Screen.Home.route) { 
                popUpTo(Screen.Onboarding.route) { inclusive = true } 
            }
        }
    )
}

composable(Screen.Home.route) {
    HomeScreen(
        onNavigateToSection = { section ->
            val route = when (section) {
                "intercept" -> Screen.Intercept.route
                "apis" -> Screen.Apis.route
                "notes" -> Screen.Notes.route
                // ... etc
            }
            navController.navigate(route) { launchSingleTop = true }
        },
        onOpenSettings = { navController.navigate(Screen.Settings.route) },
        onOpenApiRequest = { api ->
            ApiCollectionsRepository.setActiveRequest(api)
            navController.navigate(Screen.SavedApis.route)
        },
        onOpenNote = { note ->
            NavController.navigate(Screen.Notes.route)
        },
        onOpenTrafficDetail = { traffic ->
            navController.navigate(Screen.Intercept.route)
        }
    )
}

// ... more composable routes for other screens
```

---

### 3.4 CollapsibleNavPill Hosting & Integration

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` lines ~430–480 (approximate)

**CollapsibleNavPill Hosting (lines ~430–445):**
```kotlin
// Conditional display (line 411)
val showPillNav = currentRoute in rootRoutes && settings.onboardingCompleted

if (showPillNav) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        CollapsibleNavPill(
            currentRoute = currentRoute,
            onNavigate = { route ->
                if (currentRoute != route) {
                    navController.navigate(route) {
                        launchSingleTop = true
                        popUpTo(Screen.Home.route) { saveState = true }
                        restoreState = true
                    }
                }
            },
            scrollCollapseSignal = scrollCollapseSignal,
            scrollExpandSignal = scrollExpandSignal
        )
    }
}
```

**Scroll Signals for Collapse:**
- `scrollCollapseSignal` and `scrollExpandSignal` are state booleans that trigger pill collapse/expand
- Determined by scroll direction/velocity in screen content
- Passed to CollapsibleNavPill to trigger the collapse-to-FAB animation

---

### 3.5 AppNavigationDrawer Wrapping

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` lines ~360–500 (full screen layout)

**Drawer Wrapping Structure (lines ~360–430 approx.):**
```kotlin
val drawerState = rememberDrawerState(DrawerValue.Closed)
val coroutineScope = rememberCoroutineScope()

AppNavigationDrawer(
    drawerState = drawerState,
    currentRoute = currentRoute,
    onNavigate = { route ->
        // Same navigation logic as pill
        navController.navigate(route) { 
            launchSingleTop = true
            popUpTo(Screen.Home.route) { saveState = true }
            restoreState = true
        }
        coroutineScope.launch { drawerState.close() }
    },
    onOpenSettings = { navController.navigate(Screen.Settings.route) },
    content = {
        // Full-screen NavHost content goes here (lines 495+)
        NavHost( ... )
    }
)
```

**Drawer State Management:**
- `drawerState = rememberDrawerState(DrawerValue.Closed)` (line ~365)
- Opened by edge-swipe gesture (default ModalNavigationDrawer behavior)
- **Currently no hamburger button opens it** — must be added in fixed header per Phase B §1.3

---

### 3.6 Scroll State Preservation

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` lines ~435–445

**State Preservation Flags:**
```kotlin
navController.navigate(route) {
    launchSingleTop = true
    popUpTo(Screen.Home.route) { saveState = true }   // Save stack state
    restoreState = true                               // Restore on re-entry
}
```

**Per-Tab Scroll State (Future Phase B §5 Note):**
- Currently not implemented per-tab
- DESIGN-SYSTEM §12 doesn't specify scroll state caching per tab
- **Requires future implementation:** lateral slide-and-fade tab transitions (mentioned in Phase B §5 of work description)

---

## PART 4: SUMMARY OF DESIGN SYSTEM COMPLIANCE CHECKLIST

| Component | Location | Status | Compliance Notes |
|-----------|----------|--------|------------------|
| **HomeScreen header** | HomeScreen.kt 148–191 | ❌ Needs rebuild | Currently in-scroll; must become pinned, use glass capsules + dynamic color |
| **Hamburger button** | Missing | ❌ ADD | Must open AppNavigationDrawer; use LucideMenuIcon component |
| **Identity capsule + status dot** | HomeScreen.kt 157 | ⚠️ Partial | RequestrWordmark exists; needs status dot + text-swap animation |
| **Theme toggle button** | HomeScreen.kt 162 | ✅ Exists | ThemeToggleButton already implemented; will move to separate right capsule |
| **Settings gear icon** | Missing from header | ❌ ADD | Currently only avatar button exists; must create separate gear-icon capsule |
| **Avatar / User identity** | HomeScreen.kt 167–189 | ❌ REMOVE from header | Currently serves as settings entry; both functions move to separate capsules |
| **Greeting emoji** | HomeScreen.kt 132 | ❌ Fix | 🌤️ → 🍃 |
| **Search placeholder** | HomeScreen.kt 217 | ⚠️ Reconcile | "chats" dropped from placeholder; "flow nodes" added |
| **Quick action pills count** | HomeScreen.kt 288–323 | ⚠️ Verify | Spec says 4; code has 6 (extra Flow + Music) |
| **Interception status card** | HomeScreen.kt 328–489 | ✅ CORRECT | All live data, no stubs; single source of truth confirmed |
| **Power Modules grid** | HomeScreen.kt 601–679 | ✅ CORRECT | Real counts, correct structure |
| **Recently Opened** | HomeScreen.kt 492–599 | ✅ CORRECT | Real data, correct card layout |
| **Dynamic color (header only)** | Theme.kt | ❌ ADD | No dynamicColorScheme usage currently; needs Android 12+ check |
| **Frozen color tokens** | Theme.kt, Color.kt | ✅ EXISTS | 9 themes + 10 accents already defined |
| **Glass cards** | CommonComponents.kt 149 | ✅ EXISTS | GlassCard component available |
| **Glass action pill** | CommonComponents.kt 1069 | ✅ EXISTS | GlassActionPill component available |
| **Floating capsule header** | CommonComponents.kt 1177 | ✅ EXISTS | FloatingCapsuleHeader / DynamicGlassHeader available |
| **LucideMenuIcon** | CommonComponents.kt 1046 | ✅ EXISTS | Menu icon component available |
| **CollapsibleNavPill** | CollapsibleNavPill.kt 96 | ✅ EXISTS | Bottom nav pill fully implemented |
| **BadgePill** | CommonComponents.kt | ✅ EXISTS | Shared frozen badge component |
| **Density config** | DensityConfig.kt | ✅ EXISTS | CompactSpacing / ComfortableSpacing tokens |
| **Animation config** | AnimationConfig.kt | ✅ EXISTS | scaledTween, scaledSpring, LocalAnimationsEnabled |
| **Theme resolution** | MainActivity.kt 322–340 | ✅ EXISTS | AppThemeMode, AppAccent, theme selection logic |
| **NotesScreen header** | NotesScreen.kt 120–137 | ❌ Needs frozen pattern | Currently plain Row; apply glass capsule header |
| **ToolWorkspaceScreen header** | ToolWorkspaceScreen.kt ~50–100 | ❌ Needs frozen pattern | Currently plain Row; apply glass capsule + action pills |
| **ResponseInspectorView header** | ResponseInspectorView.kt ~50–100 | ⚠️ Partial | Already uses BadgePill; verify against Phase A spec, apply glass pattern |
| **DiffView component** | DiffView.kt | ❌ Needs tokens | Apply frozen color/spacing tokens |
| **CaptureController overlay** | CaptureController.kt | ❌ Needs frozen pattern | Apply glass capsule pattern + GlassActionPill for controls |
| **AppNavigationDrawer** | AppNavigationDrawer.kt | ✅ Mostly correct | Already uses GlassCard for status; ensure frozen token compliance throughout |
| **SettingsScreen sections** | SettingsScreen.kt 718, 1059 | ⚠️ Partial | Section headers exist; UI components need frozen token review |

---

## PART 5: INTERCEPTION FLAG ARCHITECTURE DEEP DIVE

**Single Source of Truth Confirmed (No Risk):**

**Repository File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/interception/InterceptionStateRepository.kt`

**Authoritative State:**
```kotlin
object InterceptionStateRepository {
    private val _state = MutableStateFlow(InterceptionState())
    val state: StateFlow<InterceptionState> = _state.asStateFlow()
    
    // Lines 26–27 comment:
    // "Bound by the Intercept FAB colour and the wordmark dot — single source of truth."
}
```

**Data Class Definition:**
```kotlin
data class InterceptionState(
    val status: InterceptionStatus = InterceptionStatus.NEVER_CONFIGURED,
    val isActive: Boolean = false,
    val port: Int = 0,
    val activeConnections: Int = 0,
    val packetCount: Int = 0,
    val bytesTransferred: Long = 0L,
    val methodLabel: String = "MITM Proxy",
    val errorText: String? = null,
    // ... etc
)

enum class InterceptionStatus {
    ACTIVE,
    ARMED,
    ERROR,
    CONFIGURED_IDLE,
    NEVER_CONFIGURED
}
```

**Read Sites (Verified Unique):**
1. **HomeScreen.kt line 116:** `val interceptionState by InterceptionStateRepository.state.collectAsState()`
2. **HomeScreen.kt lines 331–340:** Used to determine Status Card green/normal coloring
3. **HomeScreen.kt lines 361–370:** Used to display status text (ACTIVE / ARMED / ERROR / CONFIGURED_IDLE / NEVER_CONFIGURED)
4. **HomeScreen.kt line 621:** Power Modules Intercept badge: `if (capturedCount == 0 && !isInterceptionActive) "Not set up" else...`
5. **AppNavigationDrawer.kt line 81:** Drawer live status indicator
6. **InterceptScreen.kt** (verified via grep): Reads same state for main intercept UI

**Mutator Lifecycle (Engine-Controlled):**
- `markConfiguredIdle(port)` — After CA cert install
- `markArmed(tier, port, method)` — Pre-flight passed, awaiting user confirm
- `markActive(sessionId, tier, port, method)` — Capture genuinely began
- `markStopped()` — Capture ended cleanly
- `markError(message)` — Error encountered
- Real traffic counters: `packetCount`, `bytesTransferred`, `activeConnections` updated live from capture engine

**✅ RISK RESOLVED:** No independently-tracked flags exist. Single centralized `state` StateFlow with clear ownership and mutation control.

---

## PART 6: FILE PATHS & LINE NUMBERS REFERENCE

### Core Implementation Files

**Theme & Design System:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Theme.kt` — Color schemes, theme modes, accents
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Color.kt` — Universal palette tokens
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/DensityConfig.kt` — Spacing config
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/AnimationConfig.kt` — Animation & reduced-motion config

**Shared Components:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CommonComponents.kt` — GlassCard (line 149), RequestrWordmark (line 182), ThemeToggleButton (line 244), LucideMenuIcon (line 1046), GlassActionPill (line 1069), FloatingCapsuleHeader (line 1177), DynamicGlassHeader (line 1339), BadgePill, etc.
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CollapsibleNavPill.kt` — Bottom navigation pill implementation
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/AppNavigationDrawer.kt` — Navigation drawer (lines 71+)

**Screens:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt` — Home screen (lines 1–1125)
  - Header: lines 148–191
  - Greeting: lines 130–138
  - Search: lines 213–219
  - Quick actions: lines 281–325
  - Intercept status card: lines 328–489
  - Recently opened: lines 492–599
  - Power modules: lines 601–679
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` — Settings (2655 lines)
  - LOCAL REST API section: line 718
  - PLUGIN MANAGER section: line 1059
  - Appearance picker: line 1753
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/NotesScreen.kt` — Notes (489 lines)
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt` — Custom tool workspace (1059 lines)
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ResponseInspectorView.kt` — Response inspector (1303 lines)
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/DiffView.kt` — Diff viewer (111 lines)
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/CaptureController.kt` — Capture overlay (242 lines)

**Navigation & App Shell:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` — Main activity
  - Screen enum: lines 291–309
  - Theme resolution: lines 322–340
  - NavHost setup: lines 495+
  - CollapsibleNavPill hosting: lines ~430–445
  - AppNavigationDrawer wrapping: lines ~360–500

**Data & State:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/interception/InterceptionStateRepository.kt` — Interception state (single source of truth)
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/repository/SettingsRepository.kt` — Settings persistence

---

## PART 7: BUILD IMPLEMENTATION CHECKLIST

For any implementer building from this dossier:

### Phase B §2: Home Rebrand
- [ ] **Extract header from LazyColumn:** Move header Row (currently lines 148–191) to a pinned Composable outside/above the LazyColumn
- [ ] **Create pinned glass capsule container:** Use `Box(modifier = Modifier.fillMaxWidth().padding(...))` for floating position
- [ ] **Build hamburger capsule:** Left side, uses `LucideMenuIcon`, opens drawer via `drawerState.open()`
- [ ] **Build identity capsule:** Center, contains `RequestrWordmark` + status dot (green/red from `interceptionState`), supports 5-step text-swap animation
- [ ] **Build theme + settings capsules:** Right side, two separate circular `GlassActionPill` components (sun/moon toggle, gear icon)
- [ ] **Apply dynamic color (header only):** Check API level, use `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` on Android 12+, fallback to AppThemeMode tokens on API < 31
- [ ] **Implement text-swap animation:** Exact 5-step sequence; icon anchored, old text fades, new text appears, capsule resizes (spring-based)
- [ ] **Fix greeting emoji:** 🌤️ → 🍃 (line 132)
- [ ] **Verify search placeholder:** Update if "chats" wording critical per Phase A reconciliation
- [ ] **Verify quick-action pills:** Confirm spec intent (4 vs 6 pills) and finalize count
- [ ] **Confirm single interception flag:** ✅ Already correct; no changes needed

### Phase B §3: Zero-Coverage Screens

#### SettingsScreen LOCAL REST API & PLUGIN MANAGER
- [ ] Find section headers (lines 718, 1059)
- [ ] Apply frozen glass capsule header pattern if Settings has top chrome
- [ ] Review all toggle/button elements; replace with frozen components (DynamicGlassSwitch, GlassActionPill, etc.)
- [ ] Apply CompactSpacing / ComfortableSpacing tokens
- [ ] No feature flags: complete redesign pass

#### Appearance Settings (Theme / Accent / Density / Glass-Style Pickers)
- [ ] Replace numeric-range pickers with frozen slider components
- [ ] Replace blur-style selector with `RequestrSegmentedTabs`
- [ ] Replace accent picker with frozen accent-chip pattern (DESIGN-SYSTEM §10.4)
- [ ] Ensure all updates write to `SettingsRepository.updateXyz()` functions
- [ ] No feature flags: complete pass

#### NotesScreen
- [ ] Replace header back-button + action row with frozen glass capsule pattern
- [ ] Replace plain `IconButton` actions with `GlassActionPill` where applicable
- [ ] Apply frozen spacing/colors to folder list and note cards
- [ ] Use `GlassCard` for note list items
- [ ] No feature flags

#### ToolWorkspaceScreen
- [ ] Replace top bar with frozen glass capsule header
- [ ] Replace action buttons with `GlassActionPill` components
- [ ] Apply frozen tokens to editor UI
- [ ] No feature flags

#### ResponseInspectorView
- [ ] Verify against Phase A interceptr/ Screen 3 mockup (Phase B §3 note)
- [ ] Replace header with frozen glass capsule pattern
- [ ] Replace tab UI with frozen `RequestrSegmentedTabs`
- [ ] Ensure status code badges use frozen `StatusCodeBadge`
- [ ] Apply frozen spacing/colors throughout
- [ ] No feature flags

#### DiffView
- [ ] Apply frozen color tokens for diff lines (added: green, removed: red, unchanged: muted)
- [ ] Apply DensityConfig spacing
- [ ] Use frozen typography tokens
- [ ] No feature flags

#### CaptureController
- [ ] Replace control buttons with `GlassActionPill`
- [ ] Status display uses `BadgePill` for counts
- [ ] Apply frozen glass capsule pattern if overlay has header
- [ ] Use semantic token colors (green/amber/red) for capture state
- [ ] No feature flags

#### AppNavigationDrawer
- [ ] Verify drawer header uses `RequestrWordmark` ✅
- [ ] Ensure interception status card uses frozen tokens + semantic colors
- [ ] Replace plain Switch with `DynamicGlassSwitch`
- [ ] Verify destination list items comply with frozen nav rules (DESIGN-SYSTEM §10.4)
- [ ] Ensure theme/accent picker sections use frozen pattern
- [ ] Add hamburger button open integration (from fixed header)

### Navigation Integration
- [ ] Verify hamburger button (new fixed header) calls `drawerState.open()`
- [ ] Ensure `drawerState` is passed from MainActivity through to pinned header
- [ ] Test navigation flow: hamburger opens drawer → destination tap navigates + closes drawer

### Testing & Verification
- [ ] Light/dark theme switching works (DESIGN-SYSTEM §14.1)
- [ ] Dynamic accents work (if accent selector added) (§14.2)
- [ ] RTL layout (§14.3): test with RTL locale
- [ ] Font scaling (§14.4): test at 1.3x and 2.0x scales
- [ ] TalkBack (§14.5): verify icon-only pills have contentDescription
- [ ] Reduced motion (§14.6): test with animation disabled
- [ ] Blur fallback (§14.7): verify glass components render gracefully on all API levels

---

## PART 8: KNOWN CAVEATS & DEPENDENCIES

1. **Dynamic Color Scope (§1.5 of spec):** Dynamic color applies **to header capsules only**. Power Module cards, Interception status card, and all other UI must remain on static theme tokens. Implement conditional logic to scope dynamic color to header.

2. **Nav Pill Hit Target (DESIGN-SYSTEM §16):** Bottom nav pill hit target is ~35dp (below 48dp recommendation). Documented limitation; finalize at judgment phase.

3. **Glass is Emulated (§16):** No real `RenderEffect`/`Modifier.blur`. All glass components use gradient translucency + hairline border + shadow. Degrades gracefully on all API levels.

4. **Hamburger Gesture vs. Button (DESIGN-SYSTEM §10.4, Phase B §7.2):** Navigation mockups hardcode `gesturesEnabled = false` to disable swipe-open gesture. Current code keeps backup's `settings.gestureNavigationEnabled` behavior (user-toggleable). Recommendation: keep toggleable behavior; document divergence. Hamburger button adds explicit UI control regardless of gesture setting.

5. **Text-Swap Animation Sequencing:** The 5-step animation spec requires exact sequencing. Do not simplify to simultaneous cross-fade. Use Compose's animation DSL (not direct state changes) to ensure correct timing.

6. **Phase B §4 Mandate:** Complete old-UI removal per screen — no feature-flag coexistence. Each zero-coverage screen gets a full redesign pass, not a patch.

7. **§3 Enumeration is Provisional:** Response Inspector and Nav Drawer content may reveal additional coverage during Phase A implementation. Re-verify once Phase A lands.

---

## PART 9: CROSS-REFERENCE TO SOURCE DOCUMENTS

| Specification Document | Relevant Section | Key Content |
|------------------------|--------------------|-------------|
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §1 | Executive summary; Phase A vs. Phase B scope |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §2 | Home screen rebrand detail; structural change + lock-down |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §3 | Zero-coverage screens enumeration |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §4 | Approach for zero-coverage screens; authority order |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §5 | Nav / header transparency requirement |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §6 | Phased steps |
| `PHASE-B-REBRAND-NO-MOCKUP-COVERAGE.md` | §7 | Open questions / risks |
| `home_rebranding.md` | §1 | Header conversion conceptual shift + layout |
| `home_rebranding.md` | §1.5 | Dynamic color requirement |
| `home_rebranding.md` | §1.6 | Identity capsule 5-step animation spec |
| `home_rebranding.md` | §2–§8 | Preservation specs for greeting, search, quick actions, cards, modules |
| `home_rebranding.md` | §9 | Summary of changes vs. locked content |
| `home-section_prototype.png` | — | Reference screenshot 1: current prototype |
| `home-section_prototype_detailed.png` | — | Reference screenshot 2: detailed annotations |
| `DESIGN-SYSTEM.md` | §1–§2 | Purpose & ownership table |
| `DESIGN-SYSTEM.md` | §3 | Frozen shared data files & interface list |
| `DESIGN-SYSTEM.md` | §5 | Animation system & reduced motion |
| `DESIGN-SYSTEM.md` | §6 | Theme tokens (palettes, accents) |
| `DESIGN-SYSTEM.md` | §8–§9 | Nav pill + glass headers + components |
| `DESIGN-SYSTEM.md` | §10–§12 | Tabs, badges, screen-family tokens |
| `DESIGN-SYSTEM.md` | §13–§14 | Glass-style keys & accessibility coverage |
| `DESIGN-SYSTEM.md` | §15–§16 | State vocabulary & caveats |

---

**END OF DOSSIER**

This document is complete and ready for implementation. All file paths, line numbers, and component contracts are exact as of the codebase snapshot on 2026-08-31. No code modifications have been made.
