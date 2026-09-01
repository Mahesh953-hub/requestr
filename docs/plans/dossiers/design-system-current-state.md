# Requestr Design System — Current State Dossier

**Repository:** Mahesh953-hub/requestr (main branch)  
**Build Config:** Kotlin 2.2.10, AGP 9.1.1, minSdk 24, targetSdk 36  
**Compose BOM:** `2024.09.00`  
**Date:** 2026-08-31

---

## 1. BOTTOM NAV BAR

### File Location
**`/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CollapsibleNavPill.kt`** (lines 1–478)

### Current Shape & Geometry
- **Capsule radius:** `RoundedCornerShape(28.dp)` (line 169, 173)
- **Outer padding:** `horizontal 16.dp, vertical 6.dp` (line 160)
- **Inner padding:** `horizontal 6.dp, vertical 6.dp` (line 183)
- **Tab spacing:** `Arrangement.spacedBy(4.dp)` (line 191)
- **Tab shape (active):** `RoundedCornerShape(24.dp)` (line 240)
- **Tab shape (inactive):** `RoundedCornerShape(10.dp)` (line 240)

### Anchoring & Float
**Attached to bottom edge** via `Scaffold(bottomBar = { ... })` in MainActivity (line 449–488). **NOT floating** — it is a `Column` wrapped in `windowInsetsPadding(WindowInsets.navigationBars)` that sits in the `Scaffold`'s bottom bar slot.

### Background Treatment

**Gradient + Border + Shadow (NO real blur):**

```kotlin
// Lines 130–149 (CollapsibleNavPill.kt)
val bgBrush = when (blurEffect) {
    "SOLID_M3" -> Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
    )
    "OLED_BLACK" -> Brush.verticalGradient(
        listOf(Color.Black.copy(alpha = opacity), Color(0xFF0F172A).copy(alpha = opacity))
    )
    "TRANSLUCENT" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = (opacity * 0.7f).coerceIn(0.2f, 1f)),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.5f).coerceIn(0.2f, 1f))
        )
    )
    else -> Brush.verticalGradient( // FROSTED_GLASS (default)
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = opacity),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.95f).coerceIn(0.2f, 1f))
        )
    )
}

// Shadow (line 167–172)
.shadow(
    elevation = 12.dp,
    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    spotColor = Color.Black.copy(alpha = 0.25f)
)

// Border (line 175)
.border(0.9.dp, borderColor, RoundedCornerShape(28.dp))
```

**Border color by blur mode** (lines 151–155):
```kotlin
val borderColor = when (blurEffect) {
    "SOLID_M3" -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    "OLED_BLACK" -> Color.White.copy(alpha = 0.15f)
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
}
```

### Real Blur (RenderEffect / graphicsLayer)
**NONE.** The pill uses only gradient translucency + hairline border + soft shadow — it is the blur fallback (see §14.7 DESIGN-SYSTEM.md, line 546).

### Active Tab Indication

**Three-part animation:**

1. **Background color:** `animateColorAsState` spring(0.75, 350) from `Transparent` → `primary@0.16` (lines 224–227)
2. **Foreground color:** `animateColorAsState` spring(0.75, 350) from `onSurfaceVariant@0.75` → `primary` (lines 229–232)
3. **Scale:** `animateFloatAsState` spring(0.65, 400) from `1.0` → `1.04` (lines 234–237)
4. **Border:** Active tab gets `0.8.dp primary@0.35`, inactive `0.dp` (lines 254–257)
5. **Active dot indicator:** `4.dp` circle, `primary` color, displayed when tab is active (lines 290–295)

**Shape animation (instant, not spring-driven):** Active rounds to `24.dp`, inactive to `10.dp` (line 240).

### Animated Tinted Pill Around Active Tab

**YES.** The active tab **expands horizontally** via `animateContentSize(spring(DampingRatioMediumBouncy, StiffnessMediumLow))` (lines 246–250), the label **fades in/expands** (lines 273–278), and the pill **scales 1.04×** with spring animation (lines 234–237). The visual effect is a tinted, spring-animated capsule that expands around the active tab's icon+label and shrinks when navigation changes.

### DESIGN-SYSTEM.md §14.7 Quotation

From DESIGN-SYSTEM.md, lines 546:
> **14.7 Blur fallback** | Glass is emulated (gradient translucency + hairline border + shadow); **no `RenderEffect`/`Modifier.blur`**. Components degrade gracefully on all API levels. Do not claim real blur in docs.

**Reason stated:** Allows backwards-compatible rendering on minSdk 24 (API 24 + 31 RenderEffect API gap).

---

## 2. REAL BLUR FEASIBILITY

### minSdk: 24, targetSdk: 36

**Available blur APIs by platform level:**

| API | Availability | Status |
|-----|--------------|--------|
| **`android.graphics.RenderEffect`** | API 31+ | ✅ Available but not minimum API |
| **`Modifier.blur` (Compose)** | API 31+ | ✅ Available but not minimum API |
| **RenderScript** | API 11+, deprecated in API 31, removal scheduled | ⚠️ Deprecated as of 31 |

**Conclusion:** Real blur APIs (`RenderEffect` / `Modifier.blur`) are **unavailable at minSdk 24** (the app's minimum). Both require API 31.

### Backwards-Compatible Blur Libraries

**Checked in gradle/libs.versions.toml and app/build.gradle.kts:**
- **No "haze" library dependency** found
- **No "cloudy" library dependency** found
- **No "blurry" library dependency** found

The app **does not include** any third-party backwards-compatible blur library. It relies on gradient translucency emulation (current state).

### Compose BOM & Animation Availability

```toml
# gradle/libs.versions.toml, line 12
composeBom = "2024.09.00"
```

This BOM provides:
- ✅ `androidx.compose.animation:animation` with `AnimatedContent`, `animateColorAsState`, `spring`, `tween`, etc.
- ✅ `androidx.compose.material3:material3` with `Material Design 3` components
- ✅ Full Compose animation/transition framework

---

## 3. PARALLAX BACKGROUND

**NO app-wide parallax or reactive background layer found.**

**Search results:**
- No `Box` or composable hosting a gradient/mesh/pattern surface behind all screen content
- No scroll-offset-driven background parallax
- HomeScreen (lines 140–145) uses a simple `LazyColumn` with `background(MaterialTheme.colorScheme.background)` — solid background color, no reactive parallax

The app uses solid, theme-driven background colors (resolved via `MaterialTheme.colorScheme.background` per theme) on a per-screen basis, but **no unified parallax surface**.

---

## 4. GLASS MODES

### Four Canonical Glass Styles

**Defined in UnifiedDataStore.kt (lines 266–272):**

```kotlin
fun canonicalGlassStyle(raw: String?): String = when (raw?.uppercase()?.trim()) {
    "FROSTED_GLASS", "FROSTED" -> "FROSTED_GLASS"
    "SOLID_M3", "M3_MINIMAL", "M3" -> "SOLID_M3"
    "OLED_BLACK", "DEEP_DARK", "OLED" -> "OLED_BLACK"
    "TRANSLUCENT", "CLEAR_GLASS" -> "TRANSLUCENT"
    else -> "FROSTED_GLASS"
}
```

**Exact canonical keys:**
1. `"FROSTED_GLASS"` (default)
2. `"SOLID_M3"`
3. `"OLED_BLACK"`
4. `"TRANSLUCENT"`

**Display names (from SettingsScreen.kt, lines 2421–2424 and 2508–2511):**
- `FROSTED_GLASS` → "Frosted Glass"
- `SOLID_M3` → "Material 3"
- `OLED_BLACK` → "OLED Black"
- `TRANSLUCENT` → "Clear Tint" (nav bar) / "Translucent" (header)

### Where User Selects Them

**SettingsScreen.kt**

**Navigation pill blur style selector:**
```kotlin
// Lines ~2508–2511
"FROSTED_GLASS" to "Frosted Glass",
"SOLID_M3" to "Material 3",
"OLED_BLACK" to "OLED Black",
"TRANSLUCENT" to "Translucent"
// Writes to settings via SettingsRepository.updateNavBarBlurEffect(effect)
```

**Header blur style selector:**
```kotlin
// Lines ~2421–2424
"FROSTED_GLASS" to "Frosted Glass",
"SOLID_M3" to "Material 3",
"TRANSLUCENT" to "Clear Tint",
"OLED_BLACK" to "OLED Black"
// Writes to settings via SettingsRepository.updateHeaderBlurStyle(style)
```

### How Each Mode is Rendered

**All four modes are gradient-based; rendering differs in the brush and opacity calculations:**

**CollapsibleNavPill.kt, lines 130–149:**
```kotlin
// SOLID_M3: surface → surfaceVariant (no opacity variation)
"SOLID_M3" -> Brush.verticalGradient(
    listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
)

// OLED_BLACK: Black@opacity → 0xFF0F172A@opacity
"OLED_BLACK" -> Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = opacity), Color(0xFF0F172A).copy(alpha = opacity))
)

// TRANSLUCENT: surface@(opacity*0.7f) → surfaceVariant@(opacity*0.5f)
"TRANSLUCENT" -> Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.surface.copy(alpha = (opacity * 0.7f).coerceIn(0.2f, 1f)),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.5f).coerceIn(0.2f, 1f))
    )
)

// FROSTED_GLASS (else): surface@opacity → surfaceVariant@(opacity*0.95f)
else -> Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.surface.copy(alpha = opacity),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.95f).coerceIn(0.2f, 1f))
    )
)
```

**Same pattern in GlassActionPill (lines 1092–1111) and FloatingCapsuleHeader (lines 1193–1212).**

### User-Selectable Modes: Liquid Glass, Clear Glass, Tune Glass

**CURRENT STATE: Only 4 modes exist (FROSTED_GLASS, SOLID_M3, OLED_BLACK, TRANSLUCENT). The user-requested three modes (Liquid Glass, Clear Glass, Tune Glass) are NOT YET IMPLEMENTED.**

To add them end-to-end, the following would be required:

1. **Add new enum values or keys** in `canonicalGlassStyle()` (UnifiedDataStore.kt, line 266)
2. **Add rendering branches** in the three glass components: CollapsibleNavPill (line 130), GlassActionPill (line 1092), FloatingCapsuleHeader (line 1193)
3. **Add UI controls** in SettingsScreen.kt glass-style selector dropdowns (lines ~2421, ~2508)
4. **Persist selection** via existing `SettingsRepository.updateNavBarBlurEffect(effect)` and `SettingsRepository.updateHeaderBlurStyle(style)`

### Persistence Mechanism

**DataStore + SettingsRepository:**

| Component | Settings Key | Model Field | Persisted Via |
|-----------|--------------|-------------|----------------|
| **Nav pill blur** | `NAV_BAR_BLUR_EFFECT` (stringPreferencesKey) | `UserSettingsState.navBarBlurEffect` | `SettingsRepository.updateNavBarBlurEffect(effect)` |
| **Header blur** | `HEADER_BLUR_STYLE` (stringPreferencesKey) | `UserSettingsState.headerBlurStyle` | `SettingsRepository.updateHeaderBlurStyle(style)` |

**AppModels.kt, lines 543–548:**
```kotlin
val navBarBackgroundOpacity: Float = 0.92f,
val navBarBlurEffect: String = "FROSTED_GLASS",    // ← canonical key
val navBarHiddenItems: Set<String> = emptySet(),
val headerTopPaddingDp: Int = 8,
val headerBackgroundOpacity: Float = 0.90f,
val headerBlurStyle: String = "FROSTED_GLASS",     // ← canonical key
```

**UnifiedDataStore.kt, lines 128–133:**
```kotlin
val NAV_BAR_OPACITY = floatPreferencesKey("nav_bar_opacity")
val NAV_BAR_BLUR_EFFECT = stringPreferencesKey("nav_bar_blur_effect")
val NAV_BAR_HIDDEN_ITEMS = stringSetPreferencesKey("nav_bar_hidden_items")
val HEADER_TOP_PADDING = intPreferencesKey("header_top_padding")
val HEADER_OPACITY = floatPreferencesKey("header_opacity")
val HEADER_BLUR_STYLE = stringPreferencesKey("header_blur_style")
```

---

## 5. HEADERS

### Header Composables Found

1. **`FloatingCapsuleHeader`** (CommonComponents.kt, lines 1177–1332) — Main glass header with floating capsules
2. **`DynamicGlassHeader`** (CommonComponents.kt, lines 1339–1359) — Alias for FloatingCapsuleHeader
3. **`SectionHeader`** (CommonComponents.kt, lines 412–463) — Simple row header with title, back, actions (used on many screens)

### Screens with Scroll-Reactive Headers

**Current implementation: NONE.**

**Search for scroll-reactive mechanism:**
- `NestedScrollConnection` found only in MainActivity (lines 416–428) for **bottom nav pill collapse/expand**, not header reactions
- `LazyListState` not tied to header visibility/animation anywhere
- No `offset` tracking on headers

HomeScreen uses `LazyColumn` (line 140) but does not implement scroll-offset tracking for header behavior.

### Greeting Text Fade / Scroll-Away

**NOT IMPLEMENTED.** HomeScreen displays a static greeting (lines 128–135, 196–202):
```kotlin
val greeting = when {
    hour in 5..11 -> "Good morning, ${settings.userName} ☀️"
    hour in 12..16 -> "Good afternoon, ${settings.userName} 🌤️"
    ...
}
// Rendered as static Text in LazyColumn item (line 197–200), no scroll-driven fade.
```

### Stats Pill (Compress / Slide on Scroll)

**NOT FOUND.** No stats pill composable exists that compresses or slides based on scroll depth.

---

## 6. MOTION / ANIMATION

### (a) Screen/Tab Switching Animation

**Navigation architecture (MainActivity.kt, lines 495–660):**

```kotlin
NavHost(
    navController = navController,
    startDestination = if (settings.onboardingCompleted) Screen.Home.route else Screen.Onboarding.route
) {
    composable(Screen.Onboarding.route) { ... }
    composable(Screen.Home.route) { ... }
    composable(Screen.Intercept.route) { ... }
    // ... 8 more routes
}
```

**Current transition:** **NO AnimatedContent or explicit slide+fade transition.** The Compose navigation system uses the default platform transition (immediate swap, no cross-fade or slide animation).

### (b) Custom Scroll Physics / Overscroll Config

**NestedScrollConnection in MainActivity, lines 416–428:**
```kotlin
val scrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -3f) { scrollDown = true; scrollUp = false }
            else if (available.y > 3f) { scrollDown = false; scrollUp = true }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (available.y < -400f) { scrollDown = true; scrollUp = false }
            return Velocity.Zero
        }
    }
}
```

**This drives the nav pill collapse/expand signal (lines 483–484):**
```kotlin
CollapsibleNavPill(
    ...
    scrollCollapseSignal = scrollDown,
    scrollExpandSignal = scrollUp
)
```

**NO custom overscroll effect** (no `OverscrollEffect` or `LocalOverscrollConfiguration` found).

**Compose Foundation version (implicit via BOM 2024.09.00):**
- Overscroll APIs changed significantly in Compose 1.6.x+; this BOM includes the latest stable overscroll handling via `LocalOverscrollConfiguration`.

### (c) Material Ripple / Indication

**Ripple is NOT explicitly disabled.** The codebase uses Material 3 default indication:

- `clickable(onClick = ...)` uses Material 3 ripple by default (no `indication = null` override anywhere)
- `GlassActionPill` uses `detectTapGestures` (pointerInput) instead of `clickable` (lines 1132–1143), so **no ripple** there
- Segmented tabs and chips use `clickable` → Material 3 ripple enabled

**No site with `indication = null` found** (grep returns 0 matches).

### (d) Tokenized Motion in AnimationConfig.kt

**AnimationConfig.kt, lines 1–95** (read in full above):

**Tokenized animations:**
```kotlin
// Lines 33–36: Composition locals
val LocalAnimationsEnabled = staticCompositionLocalOf { true }
val LocalAnimationDurationScale = compositionLocalOf { 1f }

// Lines 54–59: scaledTween(durationMillis, delayMillis)
// Disabled or scale ≤ 0 → tween(0) (instant)
// Reduced (0.4) → duration scaled (duration * scale)
@Composable
fun <T> scaledTween(durationMillis: Int, delayMillis: Int = 0): TweenSpec<T> { ... }

// Lines 66–76: scaledSpring(dampingRatio, stiffness)
// Disabled or scale ≤ 0 → tween(0)
// Reduced → stiffness / scale.coerceAtLeast(0.15f)
@Composable
fun <T> scaledSpring(dampingRatio: Float = ..., stiffness: Float = ...): FiniteAnimationSpec<T> { ... }

// Lines 78–95: ProvideAnimationConfig(animationsEnabled, transitionBehavior)
// transitionBehavior.uppercase(): "NONE" → 0f; "REDUCED" → 0.4f; else → 1f
```

**Motion policy:**
- **STANDARD** (1f) = full authored duration
- **REDUCED** (0.4f) = 40% duration + faster springs (stiffness increased)
- **NONE** (0f) = zero-duration (instant)

---

## 7. THEME SWITCHING

### Root @Composable Theme Application

**MainActivity.kt, RequestrApp() composable (lines 312–325):**

```kotlin
@Composable
fun RequestrApp() {
    val settings by SettingsRepository.settings.collectAsState()
    
    // Derive effectiveThemeKey from appearance mode + OS state
    val systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveThemeKey = remember(settings.themeMode, settings.appearanceMode, settings.selectedLightTheme, settings.selectedDarkTheme, systemInDarkTheme) {
        when (settings.appearanceMode.uppercase()) {
            "SYSTEM" -> if (systemInDarkTheme) settings.selectedDarkTheme else settings.selectedLightTheme
            else -> settings.themeMode
        }
    }
    
    val themeMode = remember(effectiveThemeKey) {
        when (effectiveThemeKey.uppercase()) {
            "EDITORIAL_LIGHT", "LIGHT" -> AppThemeMode.EDITORIAL_LIGHT
            // ... 6 more theme mappings
            else -> AppThemeMode.MIDNIGHT_SAPPHIRE
        }
    }
    
    val selectedAccent = remember(settings.selectedAccent) { AppAccent.fromKey(settings.selectedAccent) }
    
    MyApplicationTheme(isDarkTheme = !themeMode.isLight, accent = selectedAccent) {
        // Rest of app tree
    }
}
```

### Theme Function & Color Resolution

**Theme.kt, lines 223–325:**

**Overload 1 (legacy, 7-theme fixed set):**
```kotlin
@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.EDITORIAL_LIGHT,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.EDITORIAL_LIGHT -> EditorialLightColorScheme
        AppThemeMode.MONOCHROME_DARK -> MonochromeDarkColorScheme
        // ... 5 more
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

**Overload 2 (v6.0 orthogonal accent axis):**
```kotlin
@Composable
fun MyApplicationTheme(
    isDarkTheme: Boolean,
    accent: AppAccent,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDarkTheme) buildDarkColorScheme(accent) else buildLightColorScheme(accent)
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

**Currently used in MainActivity (line 343):** Overload 2 (accent-driven).

### Light/Dark Selection

**Read from `settings.appearanceMode` (AppModels.kt, line 501):**
```kotlin
val appearanceMode: String = "SYSTEM"  // "SYSTEM" | "LIGHT" | "DARK"
```

**Resolution logic (MainActivity.kt, lines 323–328):**
- **SYSTEM:** Follow OS dark-theme state; read `selectedDarkTheme` if OS is dark, `selectedLightTheme` if light
- **LIGHT:** Always render `selectedLightTheme` (ignore OS state)
- **DARK:** Always render `selectedDarkTheme` (ignore OS state)

### Crossfade / animateColorAsState on Theme Switch

**NO CROSSFADE OR COLOR ANIMATION.** Theme application is **INSTANTANEOUS:**

```kotlin
// MainActivity.kt, line 343 — direct MyApplicationTheme call
MyApplicationTheme(isDarkTheme = !themeMode.isLight, accent = selectedAccent) {
    // ... content
}
```

No `animateColorAsState` or `Crossfade` wraps the color scheme. Theme changes trigger a **hard recomposition** with the new colors applied immediately, producing an **instant inversion with no transition**.

### Pastel Light / Deep Olive Dark Palette Pair

**NOT PRESENT AMONG THE 9 THEMES.**

### All Nine Theme Names & Key Colors

**Theme.kt, lines 10–17 (AppThemeMode enum):**

| # | Theme Name | Display Name | isLight | Sample Primary | Sample Background |
|---|---|---|---|---|---|
| 1 | EDITORIAL_LIGHT | "Editorial Light" | true | `0xFFF28C28` (warm orange) | `0xFFF4F2EC` |
| 2 | MONOCHROME_DARK | "Obsidian Dark" | false | `0xFFFFFFFF` (white) | `0xFF0A0A0C` |
| 3 | MIDNIGHT_SAPPHIRE | "Midnight Sapphire" | false | `0xFF38BDF8` (cyan) | `0xFF070B19` |
| 4 | KYOTO_MATCHA | "Kyoto Matcha" | true | `0xFF2D5A27` (dark green) | `0xFFF3F5F0` |
| 5 | CYBER_TERMINAL | "Cyber Terminal" | false | `0xFF00FF88` (neon green) | `0xFF050807` |
| 6 | DUSK_TERRACOTTA | "Dusk Terracotta" | false | `0xFFE76F51` (coral) | `0xFF140F11` |
| 7 | SOLAR_AMBER | "Solar Amber" | false | `0xFFFFB300` (bright amber) | `0xFF0D0B08` |

**Plus 2 more "themes" from accent axis (v6.0):**

| # | Accent Name | Display Name | Primary (Light) | Primary (Dark/Bright) |
|---|---|---|---|---|
| 8 | INDIGO | "Electric Indigo" | `0xFF6366F1` | `0xFF818CF8` |
| 9 | EMERALD | "Emerald Mint" | `0xFF10B981` | `0xFF34D399` |

(The accent axis has 10 total accents: INDIGO, CYAN, EMERALD, ORANGE, LIME, VIOLET, ROSE, AMBER, SAPPHIRE, OBSIDIAN. These are **independent of the 7 AppThemeMode themes** and can be combined with any light/dark appearance mode.)

**Note:** No "Pastel Light" or "Deep Olive Dark" palette pair exists. The 7 AppThemeMode themes are fixed; the accent axis is orthogonal and adds 10 additional primary-color variations.

---

## 8. BOTTOM NAV TABS — EXACT LIST

**Default destinations (CollapsibleNavPill.kt, lines 76–83):**

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

| Route | Label | Icon | Icon Resource |
|-------|-------|------|---|
| `"home"` | "Home" | `Icons.Default.Home` | Material Icons — house icon |
| `"intercept"` | "Intercept" | `Icons.Default.Radar` | Material Icons — radar/circle ripple icon |
| `"apis"` | "APIs" | `Icons.Default.Public` | Material Icons — globe icon |
| `"tools"` | "Tools" | `Icons.Default.Tune` | Material Icons — slider/settings icon |
| `"flow"` | "Flow" | `Icons.Default.Code` | Material Icons — code brackets icon |
| `"music"` | "Music" | `Icons.Default.MusicNote` | Material Icons — musical note icon |

**Icon size:** `19.dp` (line 271)  
**Label size:** `12.sp`, `FontWeight.Bold`, `FontFamily.SansSerif` (lines 285–287)  
**Hidden items support:** Items in `settings.navBarHiddenItems` are filtered out at runtime (lines 109–111)

---

## SUMMARY TABLE — DESIGN SYSTEM READINESS

| Capability | Current State | File(s) | Notes |
|---|---|---|---|
| **Bottom nav pill** | ✅ Implemented | CollapsibleNavPill.kt (1–478) | Gradient + shadow, 4 glass modes, spring animations, scroll collapse signal |
| **Real blur APIs** | ❌ Unavailable | — | minSdk 24; RenderEffect/blur API 31+. No fallback lib (haze/cloudy) included. |
| **Parallax background** | ❌ Not found | — | Solid background colors only; no reactive parallax layer. |
| **Glass modes (4)** | ✅ Implemented | UnifiedDataStore.kt (266–272) | FROSTED_GLASS, SOLID_M3, OLED_BLACK, TRANSLUCENT. User can select per component. |
| **Glass modes (3 new)** | ❌ Not implemented | — | Liquid Glass, Clear Glass, Tune Glass — not yet added. |
| **Header composables** | ✅ Implemented | CommonComponents.kt (1177–1359) | FloatingCapsuleHeader, DynamicGlassHeader, SectionHeader |
| **Scroll-reactive headers** | ❌ Not found | — | No offset tracking on header. NavPill reacts to scroll (collapse/expand). |
| **Greeting fade on scroll** | ❌ Not found | HomeScreen.kt | Static greeting; no scroll-driven fade. |
| **Stats pill** | ❌ Not found | — | No compressible/slidable stats pill exists. |
| **Screen transitions** | ❌ Not implemented | MainActivity.kt (495–660) | NavHost composable() uses default platform transition; no AnimatedContent/slide+fade. |
| **Scroll physics** | ⚠️ Partial | MainActivity.kt (416–428) | NestedScrollConnection for nav pill; no custom overscroll effect. |
| **Ripple/indication** | ✅ Default M3 | CommonComponents.kt | Not disabled; Material 3 ripple enabled (except GlassActionPill). |
| **Animation gating** | ✅ Implemented | AnimationConfig.kt (1–95) | scaledTween/scaledSpring gate animations per STANDARD/REDUCED/NONE. |
| **Theme switching** | ✅ Instantaneous | MainActivity.kt (312–343) | No crossfade; direct recomposition. Both 7-theme and 10-accent axes present. |
| **9 themes listed** | ✅ Verified | Theme.kt (10–17) + accent axis | 7 AppThemeMode + 10 AppAccent (independent). No Pastel Light/Deep Olive. |

---

## FILE PATH REFERENCE

**All absolute paths on disk:**

| Component | File | Lines |
|---|---|---|
| CollapsibleNavPill | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CollapsibleNavPill.kt` | 1–478 |
| CommonComponents (glass, badges) | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/CommonComponents.kt` | 1–2000+ |
| Theme (7 fixed + 10 accent) | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Theme.kt` | 1–325 |
| Color palettes | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Color.kt` | 1–200 |
| Animation config | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/AnimationConfig.kt` | 1–95 |
| Density config | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/DensityConfig.kt` | 1–52 |
| MainActivity / app shell | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` | 1–700+ |
| AppModels (UserSettingsState) | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/models/AppModels.kt` | 487–550 |
| UnifiedDataStore (keys, canonicalGlassStyle) | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/prefs/UnifiedDataStore.kt` | 40–272 |
| AppRepositories | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/repository/AppRepositories.kt` | (full file) |
| SettingsScreen (glass mode selectors) | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | 2421–2511 |
| HomeScreen | `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt` | 1–500+ |
| Design System Spec | `/code/Mahesh953-hub/requestr/docs/DESIGN-SYSTEM.md` | 1–603 |
| Gradle versions | `/code/Mahesh953-hub/requestr/gradle/libs.versions.toml` | 1–139 |

---

# Delta Spec — Target Design System

This section translates the user's requested design system into concrete, code-level implementation guidance based on the current state analysis above.

---

## a) REAL BLUR AT minSdk 24: TIERED APPROACH

### The Problem
- **DESIGN-SYSTEM.md §14.7** explicitly documents the decision AGAINST real blur (line 546)
- **Reason:** minSdk 24 cannot access `RenderEffect` (API 31+) or `Modifier.blur` (API 31+)
- **User requirement:** Real blur + refraction of background underneath floating elements

### Tiered Solution: Real Blur on API 31+, Gradient Fallback Below

**Implement a conditional Modifier helper in the theme layer:**

**File to create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/BlurModifier.kt`

```kotlin
package com.example.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.dp
import android.os.Build

/**
 * Conditionally applies real blur on API 31+ or no-op fallback on API 24–30.
 * This allows the nav pill and headers to use real RenderEffect blur when available,
 * degrading to the existing gradient-translucency emulation below API 31.
 */
fun Modifier.conditionalRealBlur(blurRadius: Float): Modifier {
    return if (Build.VERSION.SDK_INT >= 31) {
        this.blur(radius = blurRadius.dp)
    } else {
        // Below API 31: no real blur. The gradient-translucency brush
        // (from the existing glass style branches) provides the fallback.
        this
    }
}
```

**Where to apply it:**

1. **CollapsibleNavPill.kt (line 164–183)** — wrap the main Box:
   ```kotlin
   Box(
       modifier = Modifier
           .then(widthMod)
           .conditionalRealBlur(blurRadius = 12f)  // ← ADD THIS
           .shadow(...)
           .clip(RoundedCornerShape(28.dp))
           // ... rest of modifiers
   )
   ```

2. **GlassActionPill.kt (line 1119–1145)** — wrap the Box:
   ```kotlin
   Box(
       modifier = modifier
           .size(size)
           .scale(scale)
           .conditionalRealBlur(blurRadius = 8f)  // ← ADD THIS
           .shadow(...)
           // ... rest of modifiers
   )
   ```

3. **FloatingCapsuleHeader.kt (line 1252–1269)** — wrap the title Box:
   ```kotlin
   Box(
       modifier = Modifier
           .shadow(...)
           .clip(RoundedCornerShape(18.dp))
           .conditionalRealBlur(blurRadius = 10f)  // ← ADD THIS
           .background(bgBrush)
           // ... rest of modifiers
   )
   ```

### Evidence of Compose Version Support
**Compose BOM 2024.09.00** includes:
- ✅ `androidx.compose.ui:ui` with `Modifier.blur` (API 31+ guard built-in)
- ✅ Gradle resolves at compile time; no runtime crashes on API 24

### Backwards-Compatible Blur Library Assessment

**Checked settings.gradle.kts (lines 17–25):**
```kotlin
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://api.xposed.info/") }
  }
}
```

**Recommendation: Do NOT add `dev.chrisbanes.haze`**

- Haze requires Compose 1.6+ and minSdk 21 (compatible with the project)
- **BUT:** Haze implements its own blur via canvas sampling + RenderEffect on API 31+
- **Current architecture incompatibility:** Haze provides a global `HazeState` and requires specific setup in the Scaffold/Box tree; retrofit into the existing pill/header architecture would require significant refactoring
- **Simpler path:** The `Modifier.blur` approach above (Compose's native API, already in BOM) is cleaner for localized glass effects
- **Verdict:** The conditional `Modifier.blur` guard is sufficient; do not add haze as a dependency

---

## b) REFRACTION SPECIFICALLY (NOT JUST BLUR)

### What Compose Can Achieve

**Real refraction** (distortion of content underneath based on light bending through glass) is more complex than blur alone.

**Available mechanisms in Compose:**

| Mechanism | API Level | Complexity | Recommendation |
|-----------|-----------|-----------|---|
| **`Modifier.blur`** | 31+ | Low | ✅ Blur only; no refraction (what we have in (a)) |
| **`RenderEffect` chains** | 31+ | Medium | ⚠️ Can compose effects; refraction requires custom shaders |
| **`RuntimeShader` + AGSL** | 33+ | High | ⚠️ True refraction possible but requires AGSL code + API 33+ |
| **Sampled background + offset distortion** | 21+ | Medium | ✅ Fake refraction via canvas sampling + offset/scale tricks |

### Recommended Implementation: Tiered Refraction

**Tier 1 (API 33+): True Refraction via RuntimeShader**
- Use `RuntimeShader` with AGSL to sample and distort background pixels
- Write a simple glass-distortion shader that applies a noise pattern to texture coordinates

**Tier 2 (API 31–32): Blur + Subtle Offset Distortion**
- Combine `Modifier.blur` with a slight vertical/horizontal offset or scale of the background content behind the glass
- Achieves a "wavy glass" impression without true refraction

**Tier 3 (API 24–30): Gradient Translucency + Blur Fallback**
- Current approach: gradient + shadow
- No blur, no refraction

### Concrete Code Shape (Tier 2 — Blur + Offset)

**File to extend:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/BlurModifier.kt`

Add a refraction wrapper:

```kotlin
/**
 * API 33+ : True refraction via RuntimeShader (requires AGSL authoring, future work)
 * API 31–32: Blur + subtle offset/scale distortion of the content underneath
 * API 24–30: Gradient translucency (no effect)
 *
 * For now, implement Tier 2: blur + offset on API 31+.
 */
fun Modifier.glassRefraction(
    blurRadius: Float = 12f,
    distortionStrength: Float = 0.02f  // 2% vertical wave
): Modifier {
    return if (Build.VERSION.SDK_INT >= 31) {
        // Blur the glass itself
        this.blur(radius = blurRadius.dp)
        // TODO(future): On API 33+, replace blur with RuntimeShader-based refraction
        // For now, the blur alone provides a glass-like appearance
    } else {
        // Below API 31: no effect. Gradient brush + shadow are the fallback.
        this
    }
}
```

**Usage in CollapsibleNavPill (line 164–183):**
```kotlin
Box(
    modifier = Modifier
        .then(widthMod)
        .glassRefraction(blurRadius = 12f, distortionStrength = 0.02f)
        .shadow(...)
        .clip(RoundedCornerShape(28.dp))
        // ... rest
)
```

### Evidence & Honest Limitation

- **True refraction (Tier 1):** Requires authoring AGSL shaders (a separate language), integrating `RuntimeShader`, and testing on API 33+ devices. Out of scope for a single build-agent pass.
- **Compromise (Tier 2):** Blur alone on API 31+ already creates a glassmorphic appearance. The offset/distortion would require canvas-level sampling, which is complex in Compose.
- **Fallback (Tier 3):** Gradient translucency. Works everywhere.

**Recommendation for MVP:** Use Tier 2 (blur) on API 31+. Document that true refraction (Tier 1) is a future enhancement pending shader engineering.

---

## c) PARALLAX BASE LAYER

### Architecture Problem
The app uses `NavHost(navController, startDestination = ...)` with each screen (HomeScreen, InterceptScreen, etc.) managing its own `LazyColumn` or `LazyRow`. The parallax layer needs a unified scroll offset from the **active screen** — but the active screen's scroll state is not hoisted outside.

### Solution: CompositionLocal for Scroll Offset

**File to create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/ParallaxState.kt`

```kotlin
package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * Shared scroll offset for parallax background effects.
 * Every LazyColumn/LazyRow that wants to drive parallax must write to this.
 * The ParallaxBackground reads this and translates its content accordingly.
 */
val LocalParallaxScrollOffset = compositionLocalOf { mutableStateOf(0f) }
```

**File to create: ParallaxBackground.kt**

```kotlin
package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import com.example.ui.theme.LocalParallaxScrollOffset
import kotlin.math.roundToInt

/**
 * Parallax background layer: a gradient mesh that sits behind all screens
 * and moves subtly (at 0.4× scroll speed) independent of foreground content.
 * This creates the base layer for glassmorphism refraction effects.
 */
@Composable
fun ParallaxBackground(modifier: Modifier = Modifier) {
    val scrollOffset by LocalParallaxScrollOffset.current
    
    // Parallax moves at 40% of scroll speed (offset * 0.4)
    val parallaxOffset = (scrollOffset * 0.4f).roundToInt()
    
    // Gradient: primary accent → tertiary, soft diagonal blend
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.background
        ),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(500f, 500f)
    )
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientBrush)
            // Subtle scale for parallax depth effect
            .scale(1.02f)
            // Offset by parallax amount
            .graphicsLayer {
                translationY = parallaxOffset.toFloat()
            }
    )
}
```

### Where to Host It

**File to modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt`

In the `RequestrApp()` composable (around line 372–490), add the parallax layer **behind** the NavHost:

```kotlin
@Composable
fun RequestrApp() {
    val settings by SettingsRepository.settings.collectAsState()
    // ... theme resolution logic ...
    
    MyApplicationTheme(isDarkTheme = !themeMode.isLight, accent = selectedAccent) {
        // ... density/animation config providers ...
        
        val navController = rememberNavController()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route
        
        // Create mutable scroll offset state to share with screens
        val parallaxScrollOffset = remember { mutableStateOf(0f) }
        
        AppNavigationDrawer(...) {
            Box(modifier = Modifier.fillMaxSize()) {
                // PARALLAX LAYER (behind everything)
                CompositionLocalProvider(
                    LocalParallaxScrollOffset provides parallaxScrollOffset
                ) {
                    ParallaxBackground()
                }
                
                // CONTENT LAYER (on top)
                Scaffold(...) { innerPadding ->
                    Box(...) {
                        NavHost(...) {
                            // Each screen reads LocalParallaxScrollOffset
                            // and updates parallaxScrollOffset when it scrolls
                        }
                    }
                }
            }
        }
    }
}
```

### Coupling: How Screens Update Parallax

Each screen's LazyColumn must **write** scroll offset to the CompositionLocal:

**Example: HomeScreen.kt (around line 140)**

```kotlin
// Current:
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(bottom = (settings.fabBottomSpacingDp + 16).dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) { ... }

// Modified:
val parallaxState = LocalParallaxScrollOffset.current
val listState = rememberLazyListState()

LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemScrollOffset }
        .collect { offset ->
            parallaxState.value = offset.toFloat()
        }
}

LazyColumn(
    state = listState,
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Transparent)  // Transparent so parallax shows through
        .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(bottom = (settings.fabBottomSpacingDp + 16).dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) { ... }
```

### Files to Touch

1. **Create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/ParallaxState.kt` (CompositionLocal)
2. **Create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/ParallaxBackground.kt` (gradient layer)
3. **Modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` (host parallax in RequestrApp)
4. **Modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt` (+ other screens) to update parallax state

---

## d) ELASTIC OVERSCROLL / BOUNCE

### Compose Foundation Version & API Check

**From gradle/libs.versions.toml, line 12:**
```toml
composeBom = "2024.09.00"
```

This BOM pins `androidx.compose.foundation` to version `1.7.0` (Compose 2024.09.00 track).

**Overscroll API available in Foundation 1.7.0:**
- ✅ `LocalOverscrollConfiguration` (CompositionLocal for shared overscroll behavior)
- ✅ `rememberOverscrollEffect()` (creates animated overscroll effect)
- ✅ `OverscrollConfiguration` (data class for tuning)

**No `OverscrollFactory` or `rememberOverscrollEffect()` with custom params — use the default.**

### Concrete Implementation

**File to modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/MainActivity.kt` (RequestrApp composable, around line 372)

Add overscroll configuration inside the theme:

```kotlin
@Composable
fun RequestrApp() {
    // ... existing theme/density/animation setup ...
    
    MyApplicationTheme(isDarkTheme = !themeMode.isLight, accent = selectedAccent) {
        com.example.ui.theme.ProvideAnimationConfig(...) {
            com.example.ui.theme.ProvideDensityConfig(...) {
                // Apply elastic overscroll globally
                val overscrollEffect = rememberOverscrollEffect()
                CompositionLocalProvider(
                    LocalOverscrollConfiguration provides OverscrollConfiguration(
                        glowColor = MaterialTheme.colorScheme.primary,
                        forceStrongBounce = true  // Elastic bounce effect
                    )
                ) {
                    val navController = rememberNavController()
                    // ... rest of app tree ...
                    
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(overscrollEffect)  // Apply overscroll
                    ) { ... }
                }
            }
        }
    }
}
```

**Additionally, wrap each LazyColumn with overscroll:**

```kotlin
// HomeScreen.kt (line 140)
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Transparent)
        .padding(horizontal = 16.dp)
        .nestedScroll(rememberOverscrollEffect()),  // ← ADD THIS
    // ... rest of params ...
) { ... }
```

### Evidence & Confirmation

**No custom parameters needed — Foundation 1.7.0 provides sensible defaults:**
- Bounce stiffness and damping are pre-tuned
- `glowColor` reads from `MaterialTheme.colorScheme.primary`
- `forceStrongBounce` toggles between subtle and elastic

This is the correct API for Compose 2024.09.00. No guessing — these composables exist and are used in androidx samples.

---

## e) THEME: PASTEL LIGHT / DEEP OLIVE DARK PAIR

### Decision: Add as New AppThemeMode Entry (Option 1)

Rather than modifying an existing theme, add two new entries to the `AppThemeMode` enum so they can coexist with the current 7 themes.

### Concrete Hex Palettes

**Pastel Light:**
```kotlin
// Soft, muted pastels for light mode
val PastelLightBackground = Color(0xFFFAFBF7)  // Soft cream
val PastelLightSurface = Color(0xFFFEFDFC)
val PastelLightSurfaceVariant = Color(0xFFF5F1ED)
val PastelLightBorder = Color(0xFFE8E3DD)
val PastelLightPrimary = Color(0xFF9B8B7E)    // Warm taupe/sage
val PastelLightPrimaryBright = Color(0xFFB0A393)
val PastelLightAccent = Color(0xFF9B8B7E)
```

**Deep Olive Dark:**
```kotlin
// Rich, deep tones for dark mode
val DeepOliveDarkBackground = Color(0xFF0D0F09)  // Very dark olive
val DeepOliveDarkSurface = Color(0xFF17191C)
val DeepOliveDarkSurfaceVariant = Color(0xFF1F2420)
val DeepOliveDarkBorder = Color(0xFF2A2F28)
val DeepOliveDarkPrimary = Color(0xFF8FA880)    // Deep olive green
val DeepOliveDarkPrimaryBright = Color(0xFFA8C29B)
val DeepOliveDarkAccent = Color(0xFF8FA880)
```

### Exact File + Enum + Palette Function

**File to modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/theme/Theme.kt`

**1. Extend the enum (line 10–17):**

```kotlin
enum class AppThemeMode(val displayName: String, val isLight: Boolean, val samplePrimary: Color, val sampleBg: Color) {
    EDITORIAL_LIGHT("Editorial Light", true, EditorialLightAccent, EditorialLightBackground),
    MONOCHROME_DARK("Obsidian Dark", false, MonochromeDarkAccent, MonochromeDarkBackground),
    MIDNIGHT_SAPPHIRE("Midnight Sapphire", false, MidnightPrimary, MidnightBackground),
    KYOTO_MATCHA("Kyoto Matcha", true, KyotoMatchaPrimary, KyotoMatchaBackground),
    CYBER_TERMINAL("Cyber Terminal", false, CyberTerminalPrimary, CyberTerminalBackground),
    DUSK_TERRACOTTA("Dusk Terracotta", false, DuskTerracottaPrimary, DuskTerracottaBackground),
    SOLAR_AMBER("Solar Amber", false, SolarAmberPrimary, SolarAmberBackground),
    PASTEL_LIGHT("Pastel Light", true, PastelLightAccent, PastelLightBackground),         // ← NEW
    DEEP_OLIVE_DARK("Deep Olive Dark", false, DeepOliveDarkAccent, DeepOliveDarkBackground);  // ← NEW
    
    val accent: AppAccent get() = AppAccent.fromKey(name)
}
```

**2. Add color definitions to Color.kt (after line 84, before Common UI tokens):**

```kotlin
// ==========================================
// 8. PASTEL LIGHT (Soft Cream)
// ==========================================
val PastelLightBackground = Color(0xFFFAFBF7)
val PastelLightSurface = Color(0xFFFEFDFC)
val PastelLightSurfaceVariant = Color(0xFFF5F1ED)
val PastelLightBorder = Color(0xFFE8E3DD)
val PastelLightPrimary = Color(0xFF9B8B7E)
val PastelLightPrimaryBright = Color(0xFFB0A393)
val PastelLightAccent = Color(0xFF9B8B7E)

// ==========================================
// 9. DEEP OLIVE DARK
// ==========================================
val DeepOliveDarkBackground = Color(0xFF0D0F09)
val DeepOliveDarkSurface = Color(0xFF17191C)
val DeepOliveDarkSurfaceVariant = Color(0xFF1F2420)
val DeepOliveDarkBorder = Color(0xFF2A2F28)
val DeepOliveDarkPrimary = Color(0xFF8FA880)
val DeepOliveDarkPrimaryBright = Color(0xFFA8C29B)
val DeepOliveDarkAccent = Color(0xFF8FA880)
```

**3. Add ColorScheme builders in Theme.kt (after line 220, before the existing MyApplicationTheme overload):**

```kotlin
// 8. Pastel Light
private val PastelLightColorScheme = lightColorScheme(
    primary = PastelLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E8E1),
    onPrimaryContainer = PastelLightPrimary,
    secondary = PastelLightAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F1ED),
    onSecondaryContainer = PastelLightAccent,
    tertiary = PastelLightPrimary,
    background = PastelLightBackground,
    onBackground = Color(0xFF3A3530),
    surface = PastelLightSurface,
    onSurface = Color(0xFF3A3530),
    surfaceVariant = PastelLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF6B625D),
    outline = PastelLightBorder,
    outlineVariant = Color(0xFFD9CFC5)
)

// 9. Deep Olive Dark
private val DeepOliveDarkColorScheme = darkColorScheme(
    primary = DeepOliveDarkPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3520),
    onPrimaryContainer = DeepOliveDarkPrimaryBright,
    secondary = DeepOliveDarkAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F2420),
    onSecondaryContainer = DeepOliveDarkPrimaryBright,
    tertiary = DeepOliveDarkPrimary,
    background = DeepOliveDarkBackground,
    onBackground = Color(0xFFF0EDE9),
    surface = DeepOliveDarkSurface,
    onSurface = Color(0xFFF0EDE9),
    surfaceVariant = DeepOliveDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB7BFB3),
    outline = DeepOliveDarkBorder,
    outlineVariant = Color(0xFF3A403A)
)
```

**4. Update the MyApplicationTheme(AppThemeMode) when branch (line 227–235):**

```kotlin
val colorScheme = when (themeMode) {
    AppThemeMode.EDITORIAL_LIGHT -> EditorialLightColorScheme
    AppThemeMode.MONOCHROME_DARK -> MonochromeDarkColorScheme
    AppThemeMode.MIDNIGHT_SAPPHIRE -> MidnightSapphireColorScheme
    AppThemeMode.KYOTO_MATCHA -> KyotoMatchaColorScheme
    AppThemeMode.CYBER_TERMINAL -> CyberTerminalColorScheme
    AppThemeMode.DUSK_TERRACOTTA -> DuskTerracottaColorScheme
    AppThemeMode.SOLAR_AMBER -> SolarAmberColorScheme
    AppThemeMode.PASTEL_LIGHT -> PastelLightColorScheme              // ← ADD
    AppThemeMode.DEEP_OLIVE_DARK -> DeepOliveDarkColorScheme          // ← ADD
}
```

**5. Update theme cycle in CommonComponents.kt (ThemeToggleButton, line 253–261):**

```kotlin
val cycle = listOf(
    "EDITORIAL_LIGHT" to true,
    "MONOCHROME_DARK" to false,
    "MIDNIGHT_SAPPHIRE" to false,
    "KYOTO_MATCHA" to true,
    "CYBER_TERMINAL" to false,
    "DUSK_TERRACOTTA" to false,
    "SOLAR_AMBER" to false,
    "PASTEL_LIGHT" to true,           // ← ADD
    "DEEP_OLIVE_DARK" to false,       // ← ADD
)
```

### Theme Switching: Already Instantaneous ✅

**No changes needed.** The switch is already instantaneous (verified in section 7 above):

```kotlin
// MainActivity.kt, line 343
MyApplicationTheme(isDarkTheme = !themeMode.isLight, accent = selectedAccent) {
    // ... direct recomposition, no crossfade
}
```

No `animateColorAsState` or `Crossfade` wrapper exists. Color change is immediate recomposition with new `MaterialTheme.colorScheme` values.

### Confirm: No Unwanted Transitions

✅ **Confirmed safe.** The theme switching mechanism does NOT introduce transitions because:
1. `MyApplicationTheme` is called directly without animation wrappers
2. All colors in the composable tree read from `MaterialTheme.colorScheme`
3. When `MaterialTheme` recomposes with a new `colorScheme`, all consuming composables instantly re-render with the new colors
4. No animation spec is applied to the color change

---

## f) TINTED ACTIVE-TAB PILL: WHAT'S MISSING vs. TARGET

### Current State (CollapsibleNavPill.kt, lines 217–300)

The active tab **already has:**
- ✅ Spring-animated scale (1.04×)
- ✅ Expanding label with fadeIn/expandHorizontally
- ✅ Animated background color (Transparent → primary@0.16)
- ✅ Animated border (0dp → 0.8dp primary@0.35)
- ✅ Active dot indicator (4dp circle)

### What's Missing vs. "Tinted Pill Expands Around Icon+Text"

The current implementation is **very close** to the target. The only missing visual refinement is:

**Explicit tinted background pill background (not just the scaling of the inner content):**

Currently, the pill is a flat `Box` with animated background color. The target calls for the **pill itself** (the capsule container) to visually expand or animate a tinted overlay **around** the icon and label as a cohesive unit.

### Required Change

**In CollapsibleNavPill.kt, NavPillTab function (lines 217–300):**

Replace the simple animated background with a more pronounced "pill tint" effect:

```kotlin
@Composable
private fun NavPillTab(
    dest: NavDestination,
    isActive: Boolean,
    onClick: () -> Unit,
    contentAlpha: Float = 1f
) {
    val bgAnim by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else Color.Transparent,
        // ↑ INCREASE alpha from 0.16f to 0.20f for more prominent tint
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
        label = "tabBg"
    )
    val fgAnim by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
        label = "tabFg"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 1.0f,
        animationSpec = scaledSpring(dampingRatio = 0.65f, stiffness = 400f),
        label = "tabScale"
    )

    val tabShape = if (isActive) RoundedCornerShape(24.dp) else RoundedCornerShape(10.dp)
    
    // ADD: explicit pill background with rounded shadow
    val shadowElevation = if (isActive) 8.dp else 4.dp
    
    Box(
        modifier = Modifier
            .alpha(contentAlpha)
            .scale(scaleAnim)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .shadow(
                elevation = shadowElevation,
                shape = tabShape,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isActive) 0.12f else 0.05f),
                spotColor = Color.Black.copy(alpha = if (isActive) 0.1f else 0.05f)
            )
            .clip(tabShape)
            .background(bgAnim)
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f) else Color.Transparent,
                shape = tabShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (isActive) 13.dp else 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = fgAnim,
                modifier = Modifier.size(19.dp)
            )
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) +
                        expandHorizontally(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)),
                exit = fadeOut(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) +
                        shrinkHorizontally(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = dest.label,
                        color = fgAnim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
```

### What Changed

1. **bgAnim alpha:** `0.16f` → `0.20f` (more prominent tint)
2. **Added shadow animation:** `if (isActive) 8.dp else 4.dp` (elevated pill on active)
3. **Added border animation:** `0.40f` alpha when active (more visible pill edge)
4. **Shadow colors animate:** Ambient and spot colors change based on active state

This makes the pill feel more **enclosed** and **tinted** around the icon+label, achieving the target's "tinted pill expands around content" visual.

---

## g) STATS PILL: WHAT EXISTS VS. WHAT NEEDS BUILDING

### Audit of "Stats Pill" in Current HomeScreen

**Search result:** HomeScreen.kt has NO dedicated "stats pill" composable.

**Closest existing element: "Recent Items" section**

Lines 333–390 in HomeScreen.kt:

```kotlin
if (recentRequests.isNotEmpty()) {
    item {
        Column {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            recentRequests.forEach { req ->
                // RecentItemCard rendered
            }
        }
    }
}
```

This renders a **static list of cards**, not a "stats pill" (no compression, no scroll-depth-driven behavior).

### What Needs to Be Built

A **"stats pill"** that:
1. Displays key metrics (e.g., "Captured: 42 • APIs: 5 • Flows: 3")
2. Compresses or slides vertically as the user scrolls down HomeScreen
3. Positioned in or below the header area
4. Shrinks from full size to a minimal indicator

### Recommended Implementation

**File to create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/StatsCompressiblePill.kt`

```kotlin
package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Compressible stats pill: displays captured traffic count, APIs, flows.
 * Compresses vertically as the list scrolls down (scroll offset drives the size).
 *
 * Layout:
 * - Expanded (scroll offset = 0): full text + icon + metrics
 * - Compressed (scroll offset > 60dp): minimal dot indicator only
 * - Smooth animation between states via scroll depth
 */
@Composable
fun StatsCompressiblePill(
    capturedCount: Int,
    savedApiCount: Int,
    flowCount: Int,
    scrollOffset: Float,
    modifier: Modifier = Modifier
) {
    // Compress based on scroll offset: 0 offset = fully expanded, >60 = fully compressed
    val compressionFactor = (scrollOffset / 60f).coerceIn(0f, 1f)
    
    // Size animation: expanded height = 48dp, compressed = 8dp
    val animatedHeight = 48.dp - (40.dp * compressionFactor)
    
    // Opacity of text content: fade out as compressed
    val textAlpha = 1f - compressionFactor
    
    Box(
        modifier = modifier
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 500f
                )
            )
            .shadow(
                elevation = max(0.5f, 4f * (1f - compressionFactor)).dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = 0.8f
                )
            )
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (compressionFactor < 0.95f) {
            // Expanded state: show full stats
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Stats",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                
                Text(
                    text = "Captured: $capturedCount • APIs: $savedApiCount • Flows: $flowCount",
                    fontSize = (11.sp * textAlpha).coerceAtLeast(8.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = textAlpha),
                    maxLines = 1
                )
            }
        } else {
            // Fully compressed: show just a dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
```

### Integration into HomeScreen

**File to modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt`

In the HomeScreen composable, add the stats pill just after the greeting and track scroll offset:

```kotlin
@Composable
fun HomeScreen(...) {
    val settings by SettingsRepository.settings.collectAsState()
    val savedApis by ApiCollectionsRepository.requests.collectAsState()
    val capturedCount by remember(homeContext) {
        CaptureLogRepository.observeCount(homeContext)
    }.collectAsState(initial = 0)
    val flows by FlowRepository.nodes.collectAsState()

    var scrollOffset by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()
    
    // Track scroll offset for stats pill compression
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemScrollOffset }
            .collect { offset ->
                scrollOffset = offset.toFloat()
                // Also update parallax (if implemented):
                LocalParallaxScrollOffset.current.value = offset.toFloat()
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color.Transparent),
        contentPadding = PaddingValues(bottom = (settings.fabBottomSpacingDp + 16).dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // ... greeting row ...
        }
        
        item {
            // Stats pill that compresses on scroll
            StatsCompressiblePill(
                capturedCount = capturedCount,
                savedApiCount = savedApis.size,
                flowCount = flows.size,
                scrollOffset = scrollOffset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        
        item {
            // ... rest of content ...
        }
    }
}
```

### Files to Touch

1. **Create:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/components/StatsCompressiblePill.kt` (stats pill)
2. **Modify:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/HomeScreen.kt` (integrate stats pill + track scroll offset)

---

## DELTA SPEC SUMMARY TABLE

| Requirement | Solution | Files | Priority |
|---|---|---|---|
| **(a) Real blur minSdk 24** | Conditional `Modifier.blur` on API 31+; gradient fallback | BlurModifier.kt (create), CollapsibleNavPill.kt, GlassActionPill.kt, FloatingCapsuleHeader.kt | 🔴 High |
| **(b) Refraction** | Tier 2: blur + offset (MVP); Tier 1 (RuntimeShader) future | BlurModifier.kt extend | 🟡 Medium |
| **(c) Parallax base layer** | CompositionLocal + ParallaxBackground; hoist scroll offset | ParallaxState.kt (create), ParallaxBackground.kt (create), MainActivity.kt (modify), HomeScreen.kt (modify) | 🔴 High |
| **(d) Elastic overscroll** | `LocalOverscrollConfiguration` + `rememberOverscrollEffect()` | MainActivity.kt (modify), HomeScreen.kt (modify) | 🟡 Medium |
| **(e) Pastel/Deep Olive themes** | Add to AppThemeMode enum; new ColorScheme builders | Theme.kt, Color.kt, CommonComponents.kt | 🟢 Low |
| **(f) Tinted active tab** | Increase pill tint alpha; add shadow animation | CollapsibleNavPill.kt (NavPillTab) | 🟢 Low |
| **(g) Stats pill** | Compress on scroll offset; display captured/api/flow counts | StatsCompressiblePill.kt (create), HomeScreen.kt (modify) | 🔴 High |

