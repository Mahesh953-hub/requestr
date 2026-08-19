# requestr — New Fixes & Improvements

**Branch:** `main`  
**Status:** Active — 10 fixes documented, ready for implementation  
**Execution:** Implementation agent reads this file and applies all listed items in parallel

---

## Fix 01 — Theme Section Redesign (SettingsScreen)

### Context
Current `SettingsScreen.kt:164-228` renders 7 full-width `GlassCard` rows under `"ATMOSPHERE & THEME PALETTES"`. Replace with compact, token-driven layout.

### Changes

#### 1. Section header
- Replace `SettingsSectionHeader("ATMOSPHERE & THEME PALETTES")` with `SettingsSectionHeader("Theme")`
- Add `HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))` below

#### 2. Appearance selector
- New private composable `AppearanceSelector` in `SettingsScreen.kt`
- Three icon-only tiles: `▣` System, `☼` Light, `☾` Dark
- Selected tile: soft rounded rectangle container (`RoundedCornerShape(8.dp)`), background `surfaceVariant.copy(alpha = 0.5f)`, icon tinted with `primary`, no heavy border
- Unselected icons tinted with `onSurfaceVariant`
- Smooth `animateScale` + `animateColor` transitions on selection change

#### 3. Appearance mode model
- Add `AppearanceMode` enum: `SYSTEM`, `LIGHT`, `DARK`
- Add `appearanceMode: AppearanceMode` to `UserSettingsState`
- Persist `appearanceMode` in `SettingsRepository` under key `"appearance_mode"`
- Add `selectedDarkTheme: String` to `UserSettingsState`, persist under `"selected_dark_theme"`
- Defaults: `appearanceMode = SYSTEM`, `selectedDarkTheme = "MONOCHROME_DARK"`

#### 4. System theme detection
- In `MainActivity`, observe `UiModeManager` + `Configuration` changes
- When `appearanceMode == SYSTEM`, switch between `EDITORIAL_LIGHT` (night mode off) and `selectedDarkTheme` (night mode on)
- Recomposition trigger: pass current `uiMode` into `MyApplicationTheme`

#### 5. Dark mode memory
- When user taps `☾`, the currently active dark `AppThemeMode` is saved to `selectedDarkTheme`
- When user taps `☼`, theme switches to `EDITORIAL_LIGHT`
- When user taps `▣`, theme switches based on system `uiMode`
- Toggling back to `☾` restores the last dark theme, not a default

#### 6. Accent color grid
- New private composable `AccentColorGrid` in `SettingsScreen.kt`
- Label: `"Accent Color"`, sub-label: `mode.displayName`
- Row of circular swatches (`size = 16.dp`, `CircleShape`), each colored with `AppThemeMode.samplePrimary`
- Selected swatch sits inside soft rounded selection tile (`RoundedCornerShape(4.dp)`, `surfaceVariant` background, swatch centered)
- Tapping swatch calls `SettingsRepository.updateTheme(mode.name)`
- **Per-theme primary only** — accent is not independently overridable in this pass

#### 7. Token section placeholders
- Add 5 labels below accent grid, each followed by `HorizontalDivider`:
  - `"Typography"`
  - `"Surfaces"`
  - `"Borders"`
  - `"Icons"`
  - `"States"`
- Each rendered as `Text(..., fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = onSurfaceVariant)` + divider
- No interactive controls yet

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/data/models/AppModels.kt` | Add `AppearanceMode` enum; extend `UserSettingsState` with `appearanceMode` + `selectedDarkTheme` |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Add `appearance_mode` + `selected_dark_theme` keys; persist/load in `SettingsRepository`; add `updateAppearanceMode()` + `updateSelectedDarkTheme()` |
| `app/src/main/java/com/example/ui/theme/Theme.kt` | Ensure `AppThemeMode` is usable as accent source (already has `samplePrimary`) |
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | Replace lines 164-228 with `AppearanceSelector` + `AccentColorGrid` + token placeholders |
| `app/src/main/java/com/example/MainActivity.kt` | Add `UiModeManager` observer; pass `uiMode` into theme for SYSTEM behavior |

### Validation
- Fresh install: `Theme` section shows 3 icons, `SYSTEM` selected, `MONOCHROME_DARK` as dark fallback
- Tap `☼` → app switches to `EDITORIAL_LIGHT`
- Tap `☾` → app switches to last-selected dark theme (default `MONOCHROME_DARK`)
- Tap dark swatch grid item → `selectedDarkTheme` updates, persists
- Tap `▣` → app follows system night mode
- Rotate/restart → `appearanceMode` + `selectedDarkTheme` + `themeMode` all persist
- All `MaterialTheme.colorScheme.*` references update automatically

### Out of scope
- Actual `UiMode` change listener recomposition (requires `Configuration` observer wiring; can defer if agent finds it tricky)
- Accent color independent of theme
- Granular token controls for Typography/Surfaces/Borders/Icons/States
- Theme preview cards

---

## Fix 02 — UI & UX + Features Preference Sections (SettingsScreen)

### Context
Current `SettingsScreen.kt:230-298` has a "UI GEOMETRY & DISPLAY" section with only a corner-radius slider and 3 interception toggles. The user wants this expanded into a full **UI & UX** section with four subsections, plus a new **FEATURES** section immediately below it. Both sections use a cardless, spacious, typography-driven preference-row pattern.

### Changes

#### 1. Replace "UI GEOMETRY & DISPLAY" with "UI & UX"
- Remove lines 230-298 (current Section 3)
- Insert new section header `SettingsSectionHeader("UI & UX")` + `HorizontalDivider`
- Structure the section into 4 subsections, each with a bold title and stacked `PreferenceRow`s

#### 2. UI & UX subsections

**Interaction**
- `Swipe to Delete` — boolean switch
- `Haptic Feedback` — boolean switch  
- `Gesture Navigation` — boolean switch

**Display Behavior**
- `Picture-in-Picture` — boolean switch
- `Table Mode` — boolean switch ("Prefer structured table views")
- `Compact / Comfortable Density` — select/choose (`COMPACT`, `COMFORTABLE`)

**Update Tips**
- `Notification Policy` — select/choose (`EVERY_UPDATE`, `MAJOR_ONLY`, `NEVER`)

**UI Preferences**
- `Animation` — boolean switch
- `Transition Behavior` — select/choose (`STANDARD`, `REDUCED`, `NONE`)
- `Display Scale` — select/choose (`SMALL`, `NORMAL`, `LARGE`)

#### 3. FEATURES section (new, right below UI & UX)
- Section header `SettingsSectionHeader("FEATURES")` + `HorizontalDivider`
- Structure into 5 subsections, same preference-row pattern

**Recording**
- `Auto Recording` — boolean switch ("Automatically enter recording state")
- `Traffic Log` — boolean switch ("Enable / Disable")
- `Recording Notifications` — boolean switch ("Enable / Disable")

**Remote Control**
- `Remote Control` — boolean switch ("Allow remote devices to control recording")
- `Remote Commands` — boolean switch ("Enable / Disable")

**Device Discovery**
- `LAN Device Discovery` — boolean switch ("Enable / Disable")
- `Device Broadcasting` — boolean switch ("Enable / Disable")

**Traffic & Network**
- `Traffic Capture` — boolean switch
- `Request Logging` — boolean switch
- `Response Logging` — boolean switch

**Advanced Features**
- `Experimental Features` — boolean switch
- `Developer Options` — boolean switch
- `Feature-specific configuration` — trailing chevron/arrow (navigates to sub-screen, no toggle)

#### 4. New composables
| Name | Location | Purpose |
|---|---|---|
| `PreferenceRow` | `SettingsScreen.kt` (private) | Base row: title (bold), description (muted, multi-line), trailing control slot |
| `SwitchPreference` | `SettingsScreen.kt` (private) | `PreferenceRow` + accent-colored `Switch` |
| `SelectPreference` | `SettingsScreen.kt` (private) | `PreferenceRow` + compact `DropdownMenu` showing current value |

- **Dropdown pattern:** reuse existing `DropdownMenu` + `DropdownMenuItem` from `ApiTestrScreen.kt:225-234`
- **Switch pattern:** reuse existing `SettingToggleRow` styling (accent-colored switches)

#### 5. State model
Extend `UserSettingsState` in `AppModels.kt`:

```kotlin
// UI & UX
val swipeToDeleteEnabled: Boolean = true
val hapticFeedbackEnabled: Boolean = true
val gestureNavigationEnabled: Boolean = true
val pictureInPictureEnabled: Boolean = false
val tableModeEnabled: Boolean = false
val densityMode: String = "COMFORTABLE"          // COMPACT, COMFORTABLE
val updateNotificationPolicy: String = "MAJOR_ONLY" // EVERY_UPDATE, MAJOR_ONLY, NEVER
val animationEnabled: Boolean = true
val transitionBehavior: String = "STANDARD"      // STANDARD, REDUCED, NONE
val displayScale: String = "NORMAL"              // SMALL, NORMAL, LARGE

// Features
val autoRecordingEnabled: Boolean = false
val trafficLogEnabled: Boolean = true
val recordingNotificationsEnabled: Boolean = true
val remoteControlEnabled: Boolean = false
val remoteCommandsEnabled: Boolean = false
val lanDeviceDiscoveryEnabled: Boolean = false
val deviceBroadcastingEnabled: Boolean = false
val trafficCaptureEnabled: Boolean = true
val requestLoggingEnabled: Boolean = true
val responseLoggingEnabled: Boolean = true
val experimentalFeaturesEnabled: Boolean = false
val developerOptionsEnabled: Boolean = false
```

#### 6. Persistence
- `SettingsRepository` adds new keys:
  - `swipe_to_delete`, `haptic_feedback`, `gesture_navigation`, `picture_in_picture`, `table_mode`, `animation_enabled`, `auto_recording`, `traffic_log`, `recording_notifications`, `remote_control`, `remote_commands`, `lan_discovery`, `device_broadcasting`, `traffic_capture`, `request_logging`, `response_logging`, `experimental_features`, `developer_options` → `Boolean`
  - `density_mode`, `update_notification_policy`, `transition_behavior`, `display_scale` → `String`
- Add individual update methods or extend `updateSettings()` bulk persist
- Load all in `SettingsRepository.init()`

#### 7. Visual rules (strict)
- **No cards:** Do NOT wrap preference rows in `GlassCard` or any container with background/border
- **Spacing:** `verticalArrangement = Arrangement.spacedBy(16.dp)` between preference rows within a subsection
- **Typography:** Title = `fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = onSurface`; Description = `fontSize = 12.sp, color = onSurfaceVariant`
- **Trailing controls:** Switches use `SwitchDefaults.colors(checkedTrackColor = primary)`; Dropdowns use compact outlined text or a small `DropdownMenu` triggered by a text pill
- **Subsection titles:** Bold, slightly muted (`onSurfaceVariant`), preceded by small spacer (8.dp)
- **Disabled state:** When a parent feature is off, dependent options are visually muted (alpha 0.5f) but still tappable for simplicity

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/data/models/AppModels.kt` | Extend `UserSettingsState` with 20 new fields |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Add 18 new prefs keys; persist/load in `SettingsRepository`; add update methods |
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | Replace lines 230-298 with UI & UX section; insert FEATURES section after UI & UX (before Section 5); add `PreferenceRow`, `SwitchPreference`, `SelectPreference` composables |
| `app/src/main/java/com/example/MainActivity.kt` | Potentially read `densityMode` / `displayScale` to adjust Compose density (defer if non-trivial) |

### Validation
- Fresh install: UI & UX shows all subsections with correct defaults; FEATURES section appears below
- Toggle any switch → state updates, persists across restart
- Change any select/dropdown → value updates, persists across restart
- Rotate device → all preference states restore
- No visual cards wrap individual preference rows
- Accent-colored switches, muted inactive text, spacious vertical rhythm

### Out of scope
- Actual density/display-scale Compose configuration changes (fontScale, density) — can defer to later pass
- Feature flags actually enabling/disabling backend behavior (this pass only adds UI + persistence)
- Parent-child dependency enforcement (e.g., disabling Traffic Capture doesn't grey out Request Logging)
- "Feature-specific configuration" navigation target

---

## Fix 03 — Theme Collision Bug + Setup Wizard Re-run + Corner Radius Propagation (CRITICAL)

### Context
Three related issues reported by user:

1. **Theme collision:** When `Editorial Light` theme is active, buttons and text become black-on-black (invisible). This happens because `EditorialLightPrimary = 0xFF111111` (near-black) and `EditorialLightOnPrimary` is not properly defined, causing components that mix `primary` backgrounds with `onSurface` text to have zero contrast. The user reports this across ALL screens, including the onboarding/setup screen.

2. **Setup wizard re-run:** No way to re-run the onboarding/setup wizard from Settings after it has been completed. `onboardingCompleted` is persisted and the start destination is locked to Home.

3. **Corner radius slider non-functional:** The slider in Settings updates `settings.cornerRadiusDp`, but `GlassCard` is called with hardcoded `cornerRadius = 14.dp` (etc.) throughout the app, overriding the global setting. The user sees no visual change when dragging the slider.

### Changes

#### 1. Theme collision fix (CRITICAL — highest priority)

**Root cause:** In `Color.kt` / `Theme.kt`, `EditorialLightColorScheme` defines:
- `primary = EditorialLightPrimary = Color(0xFF111111)` — near-black
- `onPrimary = Color.White` — white
- `onSurface = Color(0xFF111111)` — near-black (SAME as primary)

When any composable uses `MaterialTheme.colorScheme.primary` as a background and `MaterialTheme.colorScheme.onSurface` for text, the result is near-black text on near-black background — invisible.

**Fix A — Theme token correction:**
- Change `EditorialLightPrimary` from `Color(0xFF111111)` to `EditorialLightAccent` (`Color(0xFFF28C28)`, warm orange)
- Change `EditorialLightOnPrimary` from `Color.White` to `Color(0xFF111111)` (dark text on orange for WCAG contrast)
- Update `primaryContainer` from `Color(0xFFFDE8D3)` to `Color(0xFFFFF1E6)` (lighter warm surface)
- Update `onPrimaryContainer` from `EditorialLightAccent` to `Color(0xFFB35A0D)` (dark orange text on light container)
- Move old black primary value to `secondary`: `secondary = Color(0xFF111111)`, `onSecondary = Color.White`
- Update `secondaryContainer` to `Color(0xFFFDE8D3)` (old primaryContainer), `onSecondaryContainer` to `EditorialLightAccent`

This ensures `primary` is always a visible accent color, and `onPrimary` always has sufficient contrast on `primary`.

**Fix B — Component audit:**
- Audit all composables that set `color = MaterialTheme.colorScheme.onSurface` (or `onSurfaceVariant`) inside a `primary`-colored container
- Replace with `color = MaterialTheme.colorScheme.onPrimary` where the parent container uses `primary`
- Priority files (known primary-background buttons):
  - `SettingsScreen.kt:423` — Export PEM button (already uses `onPrimary`, verify others)
  - `FlowScreen.kt:140,447`
  - `ApiTestrScreen.kt:194,738,787`
  - `NotesScreen.kt:137`
  - `InterceptScreen.kt:923,1084,1222`
- Also audit `CommonComponents.kt` navigation, FAB, and switch components

**Fix C — Onboarding screen audit:**
- Check `OnboardingScreen.kt` buttons for text color on `primary` background
- Verify all `Text` inside `Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary))` either inherit content color or explicitly use `onPrimary`

#### 2. Setup wizard re-run option

**Add to SettingsScreen.kt** — insert a new row in an appropriate section (e.g., near the top, after username/alias, or in a new "General" section):

```kotlin
// Re-run Setup Wizard
SettingRow(
    title = "Re-run Setup Wizard",
    description = "Restart the onboarding flow to re-configure your alias, theme, and permissions.",
    onClick = {
        settingsScope.launch {
            SettingsRepository.setOnboardingCompleted(false)
            SettingsRepository.updateUserName("")
            navController.navigate(Screen.Onboarding.route) {
                popUpTo(0)
            }
        }
    }
)
```

- The action resets `onboardingCompleted` to `false`, clears username, and navigates to `Screen.Onboarding.route`
- `MainActivity.kt:241` will then route to onboarding on next launch because `settings.onboardingCompleted` is `false`
- Style: accent-colored text or icon, no switch (it's an action, not a toggle)

**Files to modify:**
- `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` — add `SettingRow` composable + the re-run wizard row
- `app/src/main/java/com/example/data/repository/AppRepositories.kt` — ensure `setOnboardingCompleted(false)` is callable (already exists)

#### 3. Corner radius slider propagation

**Root cause:** `GlassCard` at `CommonComponents.kt:129` reads `settings.cornerRadiusDp.dp` when `cornerRadius` param is `null`, but nearly all `GlassCard` calls throughout the app pass explicit `cornerRadius = 14.dp` (or similar), overriding the setting.

**Fix:**
- Remove explicit `cornerRadius` parameter from ALL `GlassCard` calls across the app so every card uses the global `settings.cornerRadiusDp.dp`
- Files to update (from grep results):
  - `SettingsScreen.kt` — lines 138, 172, 235, 265, 305, 347, 439, 545
  - `MainActivity.kt` — lines 469, 542, 594
  - `CommonComponents.kt` — lines 496, 781, 865, 970
  - `HomeScreen.kt` — lines 236, 346, 390, 564
  - `InterceptScreen.kt` — lines 274, 946, 1054, 1251, 1858
  - `ApiTestrScreen.kt` — line 154
  - `FlowScreen.kt` — line 254
  - `ChatScreen.kt` — line 154
  - `NotesScreen.kt` — line 293
  - `ToolsScreen.kt` — line 148
  - `MusicScreen.kt` — lines 118, 275
  - `AppNavigationDrawer.kt` — lines 130, 304
- Each change: remove `cornerRadius = X.dp` from the `GlassCard(...)` call
- The `GlassCard` component itself needs no changes — it already defaults to `settings.cornerRadiusDp.dp` when `cornerRadius` is null

**Alternative if agent wants a softer transition:** keep `cornerRadius` param but change it to `cornerRadiusOverride` with a comment that null = use global setting. Same end result.

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/ui/theme/Color.kt` | Fix Editorial Light primary/secondary token values |
| `app/src/main/java/com/example/ui/theme/Theme.kt` | Verify Editorial Light color scheme matches new token values |
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | Add re-run wizard row; audit button text colors |
| `app/src/main/java/com/example/ui/screens/OnboardingScreen.kt` | Audit button text colors |
| `app/src/main/java/com/example/ui/components/CommonComponents.kt` | Audit nav/FAB/switch text colors; remove hardcoded `cornerRadius` |
| `app/src/main/java/com/example/ui/screens/*.kt` (all screens) | Remove hardcoded `cornerRadius` from `GlassCard` calls |
| `app/src/main/java/com/example/MainActivity.kt` | Remove hardcoded `cornerRadius` from `GlassCard` calls |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Ensure `setOnboardingCompleted(false)` works (already does) |

### Validation
- Switch to Editorial Light → all buttons show visible text (no black-on-black)
- All `primary`-colored elements in Editorial Light use orange background with dark text
- All `surface`-colored elements in Editorial Light use white/light background with dark text
- Navigate to Settings → tap "Re-run Setup Wizard" → onboarding screen appears
- Complete onboarding → returns to Home
- Close and relaunch app → onboarding does NOT show again (flag persisted)
- Drag corner radius slider → ALL cards across ALL screens visibly change roundness
- Slider range 8–24dp applies uniformly

### Out of scope
- Changing `primary` values in other light themes (Kyoto Matcha also has dark `primary`, but user specifically mentioned Editorial Light)
- Making corner radius per-card configurable again (global only for now)
- Animated corner radius transitions

---

## Fix 04 — Navigation Bar Dead After Home Shortcuts + Intercept Stuck on "Not Setup"

### Context
Two critical bugs in Home / Intercept flow:

1. **Bottom navigation bar becomes unresponsive** after tapping any shortcut/pill on HomeScreen (e.g., "+ Intercept", "+ APIs", power module cards). The user is taken to the target screen, but the bottom pill nav stops responding — pressing any nav item does nothing. Only closing and reopening the app fixes it.

2. **Intercept power module shows "Set up Interception" / "not setup"** on HomeScreen even after the user has granted root permissions and installed the CA certificate. The status never advances from `NEVER_CONFIGURED`.

### Bug 1 — Navigation bar dead after Home shortcuts

**Root cause:** `MainActivity.kt:258-269` — `HomeScreen`'s `onNavigateToSection` callback uses plain `navController.navigate(route)` with no navigation options. In contrast, the pill nav bar (`HoveredPillNavigationBar`) at line 220-228 uses `popUpTo(Screen.Home.route) { saveState = true }`, `launchSingleTop = true`, `restoreState = true`. The mismatch causes back-stack corruption: multiple Home/Intercept entries accumulate, `currentRoute` gets out of sync, and the pill nav's click guard (`if (currentRoute != route)`) prevents further navigation.

**Fix:**
- Replace the 8 plain `navController.navigate(...)` calls inside `HomeScreen`'s `onNavigateToSection` with the same options used by the pill nav:
  ```kotlin
  navController.navigate(route) {
      popUpTo(Screen.Home.route) { saveState = true }
      launchSingleTop = true
      restoreState = true
  }
  ```
- Apply this consistently for all 8 routes: `home`, `intercept`, `apis`, `tools`, `chats`, `notes`, `flow`, `music`, `settings`
- Also fix `onOpenApiRequest`, `onOpenTrafficDetail`, `onOpenNote`, `onOpenSettings` callbacks in `MainActivity.kt:271-283` — they also use bare `navController.navigate(...)` without the restore options

**Files to modify:**
- `app/src/main/java/com/example/MainActivity.kt` — lines 258-283

### Bug 2 — Intercept stuck on "Not Setup" after CA install + root grant

**Root cause:** `InterceptionStateRepository.markConfiguredIdle(port)` exists (line 37) but is **never called anywhere** in the codebase. The state machine only has transitions for `ARMED`, `ACTIVE`, `STOPPED`, and `ERROR`. There is no transition from `NEVER_CONFIGURED` → `CONFIGURED_IDLE` after the user completes setup (CA install, root grant, preflight pass). So the HomeScreen power module and the InterceptScreen header remain stuck showing "Set up Interception" / `NEVER_CONFIGURED` indefinitely.

**Fix:**
- Call `InterceptionStateRepository.markConfiguredIdle(port)` at the appropriate points:
  1. After CA certificate is successfully installed (in `SettingsScreen.kt` or `CertificateAuthority` install flow)
  2. After `PreflightController` completes a successful preflight sweep (in `PreflightController.kt` or `CaptureController.kt`) — specifically when the result is not `AppSelectionRequired` and not an error, meaning the engine is ready but not yet started
- Ensure `port` passed to `markConfiguredIdle` matches the current proxy port from `SettingsRepository`
- The state should transition: `NEVER_CONFIGURED` → `CONFIGURED_IDLE` → `ARMED` → `ACTIVE` (normal flow), or `NEVER_CONFIGURED` → `CONFIGURED_IDLE` → `CONFIGURED_IDLE` (idle after stop)

**Files to modify:**
- `app/src/main/java/com/example/data/interception/InterceptionStateRepository.kt` — no changes needed (method already exists)
- `app/src/main/java/com/example/interception/CaptureController.kt` — add `markConfiguredIdle` call after successful preflight
- `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` — add `markConfiguredIdle` call after CA install success
- `app/src/main/java/com/example/interception/PreflightController.kt` — verify preflight success path triggers idle state

### Validation
- **Navigation:** Tap "+ Intercept" from Home → Intercept screen opens → bottom pill nav still works → tap "APIs" → APIs screen opens → tap "Home" → Home screen opens. No app restart needed.
- **Intercept status:** Install CA cert → HomeScreen power module changes from "Set up Interception" to "Interception is off" (CONFIGURED_IDLE). Grant root → same. Status persists across navigation.
- **Back stack:** Pressing back from any root screen returns to Home (not to intermediate duplicates).

### Out of scope
- Fixing navigation for non-root screens (Settings, Onboarding) — they already hide the pill nav correctly
- Changing the `NEVER_CONFIGURED` → `CONFIGURED_IDLE` transition to happen on first app launch (it should only happen after actual setup completion)

---

## Fix 05 — Music Bar + Music Section Real Implementation

### Context
Two related problems:

1. **Music bar on HomeScreen shows stub/demo data.** `MusicRepository` is seeded with 4 hardcoded synth tracks (`"Neon Interceptor (Lofi Mix)"`, etc.). There is no real local music scan. The mini player bar (`MiniPlayerPersistentBar`) always shows this stub data on first launch.

2. **Setup wizard does not request music permission, and the dismissed music bar reappears on every launch.** The onboarding `Permissions & Tiers` screen (step 3) only asks for Storage and Network. There is no `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` permission card. The mini player has no "dismissed" state — closing it does not persist, so it reappears on every cold start.

**Expected behavior:**
- Setup wizard includes a **Music / Local Library** permission card.
- After permission grant, the app **asynchronously scans the device's local music collection** via `MediaStore` (or `AudioManager`/`ContentResolver` for API 28+).
- On first successful scan, **randomly select one track** from the real local library and surface it on the HomeScreen mini player bar.
- The mini player bar shows **real metadata** (title, artist, album art from `MediaStore`).
- When the user taps the **Close (X)** button on the mini player, the bar is **suppressed for all future app launches** until the user manually opens the Music section or explicitly resets the preference.
- If the user has **no local music files**, the mini player does not appear at all (graceful empty state).

### Changes

#### 1. Permission model

**AndroidManifest.xml** already declares:
- `READ_MEDIA_AUDIO` (API 33+)
- `READ_EXTERNAL_STORAGE` (legacy, maxSdk 32)

No manifest changes needed.

**Runtime permission request** — add to onboarding step 3 (`OnboardingScreen.kt`) a third permission card:

```kotlin
// Music Library Permission Card
GlassCard(...) {
    Row(...) {
        Icon(imageVector = Icons.Default.MusicNote, ...)
        Column {
            Text("Local Music Library", fontWeight = FontWeight.SemiBold, ...)
            Text("Scan your device for local audio files to personalize your experience", ...)
        }
        when (musicPermissionStatus) {
            GRANTED -> Icon(Icons.Default.CheckCircle, tint = Color(0xFF22C55E), ...)
            SHOULD_REQUEST -> Button("Grant", onClick = { requestPermission() })
            DENIED -> Text("Denied", color = Color(0xFFEF4444), ...)
        }
    }
}
```

**Permission state tracking** — add to `UserSettingsState`:
```kotlin
val musicPermissionStatus: String = "NOT_REQUESTED" // NOT_REQUESTED, GRANTED, DENIED
val musicBarDismissed: Boolean = false
val musicLibraryScanned: Boolean = false
val selectedLocalTrackId: String? = null
```

#### 2. Real music scan

**New file:** `app/src/main/java/com/example/data/music/LocalMusicScanner.kt`

```kotlin
object LocalMusicScanner {
    suspend fun scanLocalTracks(context: Context): List<MusicTrackItem> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrackItem>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        
        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Track"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val album = cursor.getString(albumCol) ?: "Unknown Album"
                val duration = cursor.getInt(durationCol)
                val albumId = cursor.getLong(albumIdCol)
                
                val contentUri = ContentUris.withAppendedId(uri, id)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                )
                
                tracks.add(
                    MusicTrackItem(
                        id = "local-$id",
                        title = title,
                        artist = artist,
                        durationSec = duration / 1000,
                        source = MusicSourceType.LOCAL_LIBRARY,
                        albumCoverHex = 0xFF1E3A8A, // fallback; actual URI passed separately
                        contentUri = contentUri.toString(),
                        albumArtUri = albumArtUri.toString()
                    )
                )
            }
        }
        tracks
    }
}
```

**Update `MusicTrackItem` model** (`AppModels.kt`):
- Add optional `contentUri: String? = null` for playback
- Add optional `albumArtUri: String? = null` for album art loading
- Keep existing fields for backward compatibility with stub data

#### 3. MusicRepository changes

Replace hardcoded seed data with an **empty initial state**:

```kotlin
object MusicRepository {
    private val _tracks = MutableStateFlow<List<MusicTrackItem>>(emptyList())
    val tracks: StateFlow<List<MusicTrackItem>> = _tracks.asStateFlow()
    
    private val _currentTrack = MutableStateFlow<MusicTrackItem?>(null)
    val currentTrack: StateFlow<MusicTrackItem?> = _currentTrack.asStateFlow()
    
    // ... rest of playback controls
    
    suspend fun loadLocalLibrary(context: Context) {
        val localTracks = LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = localTracks
    }
    
    suspend fun pickRandomTrack(context: Context): MusicTrackItem? {
        val tracks = LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = tracks
        val random = tracks.randomOrNull()
        if (random != null) {
            _currentTrack.value = random
        }
        return random
    }
}
```

#### 4. HomeScreen mini player visibility logic

**Current behavior:** `MiniPlayerPersistentBar` is shown whenever `currentTrack != null` and `currentRoute != Screen.Music.route`. It is always shown because `_currentTrack` defaults to the first stub track.

**New behavior:**
- Mini player is shown **only** when ALL of:
  1. `currentTrack != null`
  2. `!musicBarDismissed` (persisted in `SettingsRepository`)
  3. User is NOT on the Music screen itself
  4. Onboarding is completed
- On first launch after onboarding, if `musicPermissionStatus == GRANTED` and `!musicLibraryScanned`, trigger `MusicRepository.pickRandomTrack()` asynchronously and set `musicLibraryScanned = true`.
- If scan returns **zero tracks**, do NOT set `currentTrack` → mini player never appears.
- When user taps **Close (X)** on mini player:
  1. Call `MusicRepository.stopMusic()`
  2. Set `settings.musicBarDismissed = true` via `SettingsRepository`
  3. Mini player hides and stays hidden on all future launches

#### 5. Onboarding flow changes

**Step 3 (Permissions & Tiers)** — add Music permission card after Storage/Network:
- Show 3 cards: Storage, Network, Local Music Library
- Music card has `SHOULD_REQUEST` → `GRANTED` / `DENIED` states
- On `GRANTED`, trigger async scan in background
- On `DENIED`, skip music features gracefully (no crash, no bar)

**Step 4 (Theme)** — unchanged.

#### 6. MainActivity lifecycle changes

In `MainActivity.onCreate()` or `onResume()`:
- Check `settings.musicLibraryScanned` and `settings.musicPermissionStatus`
- If permission granted and not yet scanned → `lifecycleScope.launch { MusicRepository.pickRandomTrack(context) }`
- Do NOT block UI; show mini player only after scan completes

#### 7. MusicScreen updates

- Remove hardcoded seed data dependency — screen should render whatever is in `MusicRepository.tracks`
- If `tracks` is empty, show an **empty state** with a prompt to grant permission or add music files
- Play actual audio via `MediaPlayer` or `ExoPlayer` using the `contentUri` from `MusicTrackItem` (stub tracks have no URI, so they won't play — this is intentional; real local tracks will)
- **Do not add ExoPlayer dependency in this pass** — use Android `MediaPlayer` as a lightweight bridge, or defer actual playback to a later pass if `MediaPlayer` proves insufficient for background playback

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/data/models/AppModels.kt` | Add `contentUri`, `albumArtUri` to `MusicTrackItem`; add `MusicSourceType.LOCAL_LIBRARY` enum value; extend `UserSettingsState` with music fields |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Replace `MusicRepository` seed data with empty state; add `pickRandomTrack()`, `loadLocalLibrary()`; persist new music fields in `SettingsRepository` |
| `app/src/main/java/com/example/data/music/LocalMusicScanner.kt` | **New file** — `MediaStore` query logic |
| `app/src/main/java/com/example/ui/screens/OnboardingScreen.kt` | Add Music permission card to step 3 |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | Guard mini player visibility with `musicBarDismissed` + `currentTrack != null`; trigger async scan on first launch |
| `app/src/main/java/com/example/ui/screens/MusicScreen.kt` | Render empty state when `tracks.isEmpty()`; remove reliance on seed data |
| `app/src/main/java/com/example/MainActivity.kt` | Trigger scan on startup if permission granted and not yet scanned |
| `app/src/main/AndroidManifest.xml` | No changes needed (permissions already declared) |

### Validation
- Fresh install → onboarding step 3 shows 3 permission cards (Storage, Network, Music)
- Grant Music permission → after onboarding completes, mini player appears on Home with a real local track
- Mini player shows actual song title and artist from device storage
- Tap Close (X) on mini player → bar disappears
- Close and relaunch app → mini player does NOT reappear
- Navigate to Music screen → track list shows all scanned local files
- If user has zero local music files → mini player never appears, Music screen shows empty state
- Deny music permission → app continues without music features, no crash

### Out of scope
- Actual audio playback engine (ExoPlayer / Media3) — this pass only handles scan + UI state; playback controls remain stub until audio engine is wired
- Background audio playback with notification controls
- Album art loading from `MediaStore` URIs (Coil/Glide integration deferred)
- Music sorting/filtering/search within the Music screen
- Playlist creation or queue management

---

## Fix 06 — Export Functions: Local Filesystem + Dedicated App Folder (IMPORTANT)

### Context
All current export functions in the app share content as plain text via `Intent.ACTION_SEND` + `Intent.EXTRA_TEXT`:

| Export | Current Behavior | Problem |
|---|---|---|
| **Export PEM** (`SettingsScreen.kt:402-428`, `InterceptScreen.kt:631-640`) | Shares PEM string as text via share sheet | No file is created; recipient app receives raw text, not a `.pem` file |
| **Export HAR** (`InterceptScreen.kt:727-737`) | Shares HAR JSON string as text via share sheet | No file is created; same text-only sharing |
| **Export JSON** (`SettingsScreen.kt:529-536`) | Stub — `onClick = { /* Export */ }` does nothing | Not implemented at all |
| **cURL** (`InterceptScreen.kt:698`) | Copies to clipboard | Acceptable for one-liner cURL, but inconsistent with file-based exports |

**User requirement:** All exports must **save actual files** to a dedicated local folder owned by the app. Sharing text is "bs" — the user wants proper file ownership and a consistent export directory.

### Dedicated App Folder

**Location:** `Context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) / "requestr" / "exports"`

**Why external files dir:**
- App-specific — no `WRITE_EXTERNAL_STORAGE` permission needed on Android 10+
- Survives app uninstall cleanup (system removes the folder automatically)
- Visible to user via file manager under `Android/data/com.kailashhh.requestr/files/Documents/requestr/exports/`
- No SAF (Storage Access Framework) complexity for writes

**Folder creation:**
- Created lazily on first export attempt
- `File(folderPath).mkdirs()` — idempotent
- Folder path derived dynamically each time from `Context`; no need to persist path in settings

**File naming convention:**
- PEM: `requestr-ca-{yyyyMMdd-HHmmss}.pem`
- HAR: `har-{host}-{yyyyMMdd-HHmmss}.har`
- JSON (traffic log): `traffic-log-{yyyyMMdd-HHmmss}.json`
- cURL: `curl-{yyyyMMdd-HHmmss}.sh`

### Changes

#### 1. New `FileExporter` utility

**New file:** `app/src/main/java/com/example/data/export/FileExporter.kt`

```kotlin
object FileExporter {
    private const val EXPORT_SUBDIR = "requestr/exports"
    
    fun getExportFolder(context: Context): File {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(docsDir, EXPORT_SUBDIR).apply { mkdirs() }
    }
    
    suspend fun writePem(context: Context, pem: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "requestr-ca-${timestamp()}.pem")
        file.writeText(pem)
        file
    }
    
    suspend fun writeHar(context: Context, har: String, host: String): File = withContext(Dispatchers.IO) {
        val safeHost = host.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(32)
        val file = File(getExportFolder(context), "har-${safeHost}-${timestamp()}.har")
        file.writeText(har)
        file
    }
    
    suspend fun writeJson(context: Context, json: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "traffic-log-${timestamp()}.json")
        file.writeText(json)
        file
    }
    
    suspend fun writeCurl(context: Context, curl: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "curl-${timestamp()}.sh")
        file.writeText("#!/bin/bash\n$curl\n")
        file
    }
    
    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
```

#### 2. Update Export PEM

**SettingsScreen.kt:402-428** — replace share-intent with file write + share:

```kotlin
Button(
    onClick = {
        settingsScope.launch {
            runCatching {
                val pem = CertificateAuthority.exportPem(settingsContext)
                val file = FileExporter.writePem(settingsContext, pem)
                shareFile(settingsContext, file, "application/x-pem-file", "requestr Root CA (PEM)")
            }
        }
    },
    ...
)
```

**InterceptScreen.kt:631-640** — same pattern.

#### 3. Update Export HAR

**InterceptScreen.kt:727-737** — replace text share with file write + share:

```kotlin
val har = TrafficExporter.toHar(item)
val host = Uri.parse(item.url).host ?: "unknown"
val file = FileExporter.writeHar(screenContext, har, host)
shareFile(screenContext, file, "application/json", "requestr HAR export")
```

#### 4. Implement Export JSON

**SettingsScreen.kt:529-536** — replace stub with real export:

```kotlin
Button(
    onClick = {
        settingsScope.launch {
            runCatching {
                val json = TrafficExporter.toJsonForAll(context) // new method needed
                val file = FileExporter.writeJson(settingsContext, json)
                shareFile(settingsContext, file, "application/json", "requestr Traffic Log")
            }
        }
    },
    ...
)
```

#### 5. cURL export — optional file write

**InterceptScreen.kt:698** — currently copies to clipboard. Add optional file write alongside clipboard:

```kotlin
val curl = TrafficExporter.toCurl(item)
clipboardManager.setText(AnnotatedString(curl))
// Also save to file
lifecycleScope.launch {
    FileExporter.writeCurl(context, curl)
}
```

#### 6. `shareFile` helper

Add to `SettingsScreen.kt` or a shared util:

```kotlin
fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export as ${File(file.name).extension.uppercase()}"))
}
```

#### 7. AndroidManifest.xml — FileProvider

Add `FileProvider` declaration:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

**New file:** `app/src/main/res/xml/file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path
        name="exports"
        path="Documents/requestr/exports/" />
</paths>
```

#### 8. Add `toJsonForAll` to TrafficExporter

New method that exports all captured traffic as a JSON array:

```kotlin
fun toJsonForAll(context: Context): String {
    val items = CaptureLogRepository.getAllTraffic(context) // need to add this method
    return JSONArray(items.map { toJsonItem(it) }).toString()
}
```

**Note:** `CaptureLogRepository` may need a new `getAllTraffic()` suspend method that returns `List<CapturedTrafficItem>` (currently only has `observeTraffic` as a Flow and `observeCount`).

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/data/export/FileExporter.kt` | **New file** — folder management + file write utilities |
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | Replace PEM export and JSON export stub with file-based exports |
| `app/src/main/java/com/example/ui/screens/InterceptScreen.kt` | Replace PEM export and HAR export with file-based exports; add cURL file write |
| `app/src/main/AndroidManifest.xml` | Add `FileProvider` declaration |
| `app/src/main/res/xml/file_paths.xml` | **New file** — FileProvider paths |
| `app/src/main/java/com/example/data/interception/TrafficExporter.kt` | Add `toJsonForAll()` method |
| `app/src/main/java/com/example/data/interception/CaptureLogRepository.kt` | Add `getAllTraffic()` suspend method if not present |

### Validation
- Tap "Export PEM" → file `requestr-ca-20260819-143022.pem` appears in `Android/data/com.kailashhh.requestr/files/Documents/requestr/exports/`
- Share sheet opens with the actual `.pem` file attached (not raw text)
- Tap "Export HAR" → file `har-example-com-20260819-143022.har` appears in same folder
- Tap "Export JSON" → file `traffic-log-20260819-143022.json` appears
- All files contain valid, complete content matching the current text exports
- Folder is created automatically on first export
- Uninstalling the app removes the folder (system behavior)

### Out of scope
- Import functionality (reading files back into the app)
- File manager UI inside the app to browse exports
- Export to other locations (SAF picker, Downloads folder)
- Batch export (all traffic at once vs per-item)
- File encryption or password protection for exported PEM

---

## Fix 07 — Tools Section: Tool Library UI Redesign + Navigation Architecture

### Context
The current `ToolsScreen.kt` uses inline local state (`activeTool`) for tool execution, emoji-based category chips with filled backgrounds, and `GlassCard` tool cards showing emoji + description. The user wants a polished, theme-consistent Tool Library with proper navigation history, distinct vector icons per tool, and state preservation.

### Goals
1. Redesign category selector: text-only, horizontal scroll, accent-colored active state with subtle underline indicator
2. Redesign tool cards: compact rounded cards with distinct vector icons in rounded containers, tool name beneath
3. Implement real navigation history for tool workspaces (not inline local state)
4. Preserve category/scroll state when returning from a tool
5. Ensure Back behavior is consistent across the entire app

### Current State Analysis
- `ToolsScreen.kt` has inline catalog + workspace in one composable
- `ToolsRepository.kt` has 18 hardcoded tools, `ToolCategory` uses emoji `icon: String`
- Category chips use `RoundedCornerShape(10.dp)` filled/surface chips with emoji + text
- Tool cards use `GlassCard` with category emoji + title + description
- Tool execution view uses local `activeTool` state — no real navigation, no back stack
- `executeToolLogic()` in `ToolsScreen.kt` handles all tool output

### Proposed Architecture

#### Navigation Structure
- `ToolsScreen` remains the **catalog view** (category selector + tool grid)
- Add `Screen.ToolWorkspace(toolId: String)` as a new NavHost destination in `MainActivity`
- Tapping a tool card navigates to `Screen.ToolWorkspace/{toolId}`
- `ToolWorkspaceScreen(toolId: String)` is a new composable that renders the tool's workspace
- Back button / system back automatically returns to `ToolsScreen` with all state preserved
- Toolscreen state (`selectedCategory`, `searchQuery`, scroll position) persists because the composable stays in the back stack

**Why separate destinations instead of nested NavHost:** Simpler integration with existing `MainActivity` NavHost. One new route (`tool_workspace/{toolId}`) handles all 18 tools. The tool workspace screen reads the tool definition from `ToolsRepository` by ID and renders the appropriate workspace UI.

#### Category Selector Redesign

**New composable:** `CategorySelector` in `ToolsScreen.kt`

- Text-only categories (no emoji): "All", "Decoders", "Ciphers & Crypt", "Hashes & Checksums", "Formatters", "Network & IP", "Generators"
- Horizontal scrollable row
- **Active category:** text tinted `MaterialTheme.colorScheme.primary`, with a subtle underline indicator (`Modifier.border(1.dp, primary, RoundedCornerShape(2.dp))` at bottom, or `Divider` with primary color)
- **Inactive categories:** text tinted `MaterialTheme.colorScheme.onSurfaceVariant`
- Smooth color transition on selection change
- Spacing: `horizontalArrangement = Arrangement.spacedBy(12.dp)`
- Padding: `horizontal = 16.dp`

#### Tool Card Redesign

**New composable:** `ToolCard` in `ToolsScreen.kt`

- Compact rounded card: `RoundedCornerShape(16.dp)`, background `MaterialTheme.colorScheme.surface`
- No `GlassCard` — use direct `Box` with `clip` + `background` + `border`
- **Icon container:** `RoundedCornerShape(12.dp)`, background `surfaceVariant.copy(alpha = 0.5f)`, centered icon
- **Icon:** distinct `ImageVector` per tool (see mapping below), tinted `primary`, size 24.dp
- **Tool name:** below icon container, `fontWeight = FontWeight.SemiBold`, `color = onSurface`, `fontSize = 13.sp`, `maxLines = 1`
- **No description on card** — user spec: "The library only represents and launches the tools"
- Clickable: `onClick = { onNavigateToTool(tool.id) }`
- Height: fixed 100-110dp for grid consistency

#### Icon Mapping

Extend `ToolItem` or `ToolsRepository` to provide an `ImageVector` per tool:

| Tool ID | Tool Name | Suggested Icon |
|---|---|---|
| jwt-decoder | JWT Token Decoder | `VpnKey` / `Key` |
| base64 | Base64 Encoder / Decoder | `DataObject` |
| url-encoder | URL / URI Percent Encoder | `Link` |
| hex-converter | Hex ↔ String Converter | `Code` |
| html-entity | HTML Entity Escape | `Html` |
| morse-code | Morse Code Converter | `GraphicEq` / `OndemandVideo` |
| aes-cipher | AES Encrypt / Decrypt | `EnhancedEncryption` / `Lock` |
| xor-cipher | XOR Stream Cipher | `SwapHoriz` / `Cipher` |
| sha256 | SHA-256 Hash | `Fingerprint` |
| sha1 | SHA-1 Hash | `Fingerprint` |
| md5 | MD5 Hash | `Fingerprint` |
| hmac-sha256 | HMAC-SHA256 Signer | `Security` |
| crc32 | CRC32 Checksum | `Calculate` |
| json-formatter | JSON Beautifier & Minifier | `DataObject` |
| sql-formatter | SQL Query Beautifier | `TableChart` |
| ip-calculator | IPv4 Subnet Calculator | `Public` / `NetworkCheck` |
| uuid-gen | UUID v4 Generator | `Badge` / `PermIdentity` |
| random-string | Secure Random String | `Shuffle` / `Casino` |
| lorem-ipsum | Lorem Ipsum Generator | `Article` / `Notes` |

**Note:** Some icons may not exist in `Icons.Default` (e.g., `EnhancedEncryption`, `GraphicEq`, `Calculate`, `NetworkCheck`, `Badge`, `PermIdentity`, `Shuffle`, `Casino`, `Article`). The implementation agent should:
1. Check `Icons.Default` for available icons
2. Use the closest available alternative where exact match doesn't exist
3. If no suitable Material Icon exists, use a custom vector drawable resource

#### Tool Workspace Screen

**New file:** `app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt`

- Receives `toolId: String` as argument
- Looks up `ToolItem` from `ToolsRepository.getToolsCatalog().find { it.id == toolId }`
- Renders a workspace header with back button, tool title, category, and description
- Renders tool-specific UI based on `toolId`:
  - Input field(s) appropriate to the tool type
  - Output/result area
  - Action buttons (encode/decode, copy, etc.)
- The actual tool logic (`executeToolLogic`) can be reused from `ToolsScreen.kt` or moved to `ToolsRepository`
- Navigation: back arrow + system back both call `navController.popBackStack()`

**Important:** The tool workspace UI is implemented in this pass according to the detailed specification below.

#### Tool Workspace Design Specification

**Principle:** Each tool has a domain-specific workspace designed around what that tool actually requires. The Base64 Encoder/Decoder layout is only a reference for visual language and interaction quality, never a universal component template. The application theme is the sole source of colors, typography, surfaces, borders, icons, spacing tokens, states, light/dark behavior, and accessibility contrast. No tool defines its own theme or hardcoded colors.

**Before rendering any tool UI, the implementation must determine:**
- The tool's operations, required inputs, optional parameters
- Algorithm modes and dependencies between parameters
- Generated values, validation requirements
- Whether the operation is reversible or one-way
- Only add the blocks, controls, fields, and actions required by that workflow; remove irrelevant controls completely

**Tool Type Rules:**

**Reversible tools** (encoders, decoders, encryptors, decryptors, converters, format transformers):
- Operate bidirectionally inside the same screen
- Provide an explicit operation/mode selector or equivalent control
- User can switch between directions without leaving the tool
- Input/output blocks, labels, actions, and processing behavior update according to selected direction
- Examples: Base64, URL encoding, hex conversion, Morse code, AES encrypt/decrypt

**Simple tools** (Base64, URL encoding, hex, Morse code, similar transforms):
- Minimal input/output workspace with only the actions they genuinely support
- Do NOT force encryption-style configuration blocks onto them

**Cryptographic tools** (AES, XOR, etc.):
- Substantially richer, algorithm-aware interfaces
- Appropriate input/output blocks plus algorithm/mode configuration
- Key input, IV/nonce input where required
- Key-size selection where applicable, padding selection where applicable
- Encoding/decoding format controls, secure generation controls
- Random/generate icons beside key, IV, nonce, salt, or other generated values generate cryptographically appropriate random values and replace only that specific value
- Changing AES modes (CBC, GCM, CTR, ECB) dynamically changes required fields and behavior because their cryptographic requirements are different
- Never display an IV field for a mode that does not use one; never treat GCM like CBC
- GCM may additionally require authentication-tag/AAD handling where supported
- Validate key length, IV/nonce requirements, encoding, and mode compatibility before processing

**Hashing tools** (SHA-256, SHA-1, MD5, HMAC, CRC32):
- One-way operations; must NOT be presented as reversible encoder/decoder screens
- Input block, algorithm selector, optional algorithm-specific parameters, hash/result block
- Provide Copy, Clear, and appropriate import/paste actions
- Do NOT provide a fake reverse/decrypt operation
- If tool supports multiple hash algorithms, expose through selector and update algorithm-specific options dynamically

**Password/hash-generation tools** (UUID, random string, etc.):
- Expose parameters actually required by their algorithm (salt, cost/work factor, iterations, memory, parallelism, output length, encoding)
- Random-generation controls available where appropriate
- Generated secrets must have secure-generation semantics rather than generic random text generation

**Formatters and parsers** (JSON, SQL, etc.):
- Input/output or editor-based workflow appropriate to the data format
- May require formatting options, indentation controls, validation, minify/pretty-print switching, sorting, escaping, or parsing modes
- Action bars contain only relevant operations: Format, Minify, Validate, Copy, Paste, Import, Export, Clear, or equivalent

**Generators** (UUID, random string, Lorem Ipsum):
- Parameter/configuration block followed by generated-result block
- NOT an input/output transformation
- Actions: Generate, Randomize, Copy, Clear, Regenerate, or export
- Randomize icon beside an individual parameter changes only that parameter when appropriate

**Decoders and converters** (JWT, Base64, URL, hex, Morse, HTML entity):
- Reflect actual semantics of the format
- Input/output labels, validation, character handling, padding behavior, error reporting, and available actions must correspond to the actual format
- Morse code, hex, URL encoding, Base64, binary, Unicode are NOT interchangeable

**File/image-capable tools:**
- Expose Import/File/Image action only when the tool genuinely supports those data types
- Clipboard actions present where text or compatible binary data can be pasted
- Copy exists only for meaningful copyable results
- Clear exists for editable fields
- Export/Save exists when the tool produces a persistent or downloadable artifact

**Contextual Icons:**
- Every contextual icon has an explicit semantic purpose and must perform that purpose
- Typical actions: ArrowLeft for navigation, Clipboard/Paste for clipboard input, Copy for copying results, Trash2 for clearing, File/Image for importing supported data, Download/Save for exporting, RefreshCw or Dices for regeneration/randomization, Eye/EyeOff for secret visibility, Lock/Unlock for security state where meaningful, Settings/SlidersHorizontal for advanced configuration, SwapHorizontal for reversible direction switching
- These are examples, not mandatory icons. An icon is included only when its action is meaningful to that specific tool.

**Configuration Block Rules:**
- Tool configuration blocks are conditional and dependency-aware
- Changing an algorithm, encoding, mode, operation, or format can add, remove, rename, or reconfigure dependent fields and actions immediately
- Hidden-inapplicable controls must NOT remain as disabled placeholders
- Example: Changing AES mode changes IV/nonce, authentication, padding, and parameter requirements; changing hash algorithm changes its available parameters; changing formatter changes its formatting controls

**Workspace Layout Principle:**
- Each tool remains a complete workspace on one screen wherever practical
- Users should NOT have to exit an encoder to decode, exit an encryptor to decrypt, or navigate to a second tool for the reverse operation when the underlying algorithm naturally supports both directions

**Visual Structure (not fixed):**
The layout is NOT always "Input + Output". It may be:
- `Tool configuration → Input → Processing/mode controls → Output`
- `Parameters → Generate → Result`
- `Input → Hash configuration → Digest`
- `Ciphertext → Decryption configuration → Plaintext`

...depending entirely on the selected tool.

The Base64 Encoder/Decoder is the **reference for visual quality only**: compact themed configuration surfaces, contextual action bars, large rounded editors, clear input/output hierarchy, functional vector icons, responsive states, and clean spacing. Its specific blocks, labels, icons, and workflow must never be blindly copied to AES, hashes, formatters, generators, parsers, decoders, converters, or other tools.

**Implementation requirement:** Treat every tool as an experienced domain-specific implementation rather than a variation of the Base64 screen. The final interface must expose exactly the controls necessary to operate that tool correctly, omit controls that have no semantic purpose, adapt dynamically to algorithm/mode changes, support the tool's real input/output model, and avoid UI that implies capabilities the underlying tool does not actually provide.

#### State Preservation Strategy

- `ToolsScreen` composable stays in back stack when navigating to `ToolWorkspaceScreen`
- All local state in `ToolsScreen` is automatically preserved:
  - `selectedCategory` — preserved
  - `searchQuery` — preserved
  - `LazyVerticalGrid` scroll position — preserved via `rememberLazyGridState()`
- No additional state-saving logic needed

#### Back Navigation Contract

**Current problem:** `ToolsScreen` uses `activeTool = null` for back, which is local state only. If user navigates to Tools from Search or Home, there's no real navigation history.

**Fix:**
- All tool opens use `navController.navigate(Screen.ToolWorkspace.createRoute(toolId))`
- `ToolWorkspaceScreen` back arrow calls `navController.popBackStack()`
- System back / gesture back automatically pops the workspace and returns to Tools
- This applies consistently to every back control in the app

**App-wide Back contract (to be verified/fixed across all screens):**
- Back must use `navController.popBackStack()` or `activity?.onBackPressedDispatcher?.onBackPressed()`
- No screen should override back to "go Home" unless Home is the actual previous destination
- `MainActivity` should not use `popUpTo(Screen.Home.route)` for intra-section navigation (e.g., opening a tool from Tools)

#### Visual Rules (Strict)

- **No hardcoded colors:** all colors from `MaterialTheme.colorScheme.*`
- **Spacing:** 16dp horizontal padding for catalog, 10dp grid spacing
- **Typography:** category text 12.sp, tool name 13.sp SemiBold
- **Surfaces:** cards use `surface` background, `outline` border (1dp), `surfaceVariant` for icon containers
- **Active states:** `primary` color for active category underline and active icon tint
- **Inactive states:** `onSurfaceVariant` for inactive category text and icons

### Changes Summary

#### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/data/repository/ToolsRepository.kt` | Add `icon: ImageVector` to `ToolItem`; add icon mapping for all 18 tools |
| `app/src/main/java/com/example/ui/screens/ToolsScreen.kt` | Replace category selector with `CategorySelector`; replace tool cards with `ToolCard`; replace inline workspace with navigation to `ToolWorkspaceScreen`; add `rememberLazyGridState()` for scroll preservation |
| `app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt` | **New file** — tool workspace shell with back navigation |
| `app/src/main/java/com/example/MainActivity.kt` | Add `Screen.ToolWorkspace` route with `toolId` argument |

#### New composables
| Name | Location | Purpose |
|---|---|---|
| `CategorySelector` | `ToolsScreen.kt` (private) | Text-only horizontal category filter with accent underline |
| `ToolCard` | `ToolsScreen.kt` (private) | Compact rounded card with vector icon + tool name |

### Validation
- Tools screen shows text-only categories with "All" active by default
- Active category has accent color + subtle underline
- Tool grid shows 2 columns of icon + name cards
- Each tool has a distinct, purpose-appropriate vector icon
- Tapping a tool card navigates to `ToolWorkspaceScreen`
- Back from workspace returns to exact Tools state (same category, same scroll position)
- Pill nav still works — tapping "Tools" returns to catalog
- All colors are theme-derived; no hardcoded colors visible in light/dark themes

### Out of scope
- Tool search within the grid (search bar can be kept or removed; user didn't mention it)
- Tool favorites / recent tools
- Tool categories beyond the existing 6
- Category management (add/remove/reorder categories)

---

## Fix 08 — Interceptr: Rename, Tier Auto-Selection, Traffic Capture for All Methods, CA Whitelist, Root Module Generation, FAB Spacing, Nav Rename

### Context
The current "HTTP(S) Interceptor" screen has critical architectural problems:
1. **Name:** Rename to "Interceptr"
2. **Tier selection doesn't work:** User grants root but app still uses low-level VPN service. The `resolveAutoTier()` logic exists but all capture paths funnel through `RequestrVpnService` (VpnService tun), regardless of tier.
3. **No traffic from proxy/VPN/MITM:** Only the tun-based VPN path writes to Room. There is no separate proxy service, no MITM proxy, no root-specific capture path. The user reports zero traffic from local VPN and proxy.
4. **No CA whitelist for system store:** When root/Shizuku is available, the app should automatically add the CA to the system trust store.
5. **No root tool module generation:** The app should detect Magisk/KSU/nAPatch and generate a module for certificate whitelisting and other interception requirements.
6. **FAB spacing:** The capture FAB has unwanted blank space below the icon, pulling it too far from the bottom nav bar.
7. **Nav rename:** "proxy" bottom nav item should be renamed to "record".
8. **No method watcher:** No background task verifies the selected capture method is actually working.
9. **Active hint doesn't reflect method:** Home and Intercept screens don't clearly show which capture method is active.

### Architecture Overhaul

#### Current Capture Architecture (Broken)
```
User taps FAB
  → CaptureController.requestStart()
    → PreflightController.run()
      → resolves tier (Root/Shizuku/VPN)
      → always calls RequestrVpnService.start()
        → establishes tun interface
        → PacketPump reads tun fd
        → CaptureEngine parses HTTP
        → Room persistence
```

**Problem:** Regardless of tier, capture ALWAYS goes through `RequestrVpnService` (VpnService tun). There is no root-specific path, no proxy service, no MITM. The `ActiveTier` is cosmetic.

#### New Capture Architecture

**Capture method abstraction:**
```kotlin
enum class CaptureMethod {
    VPN_TUN,        // Current VpnService tun interface
    HTTP_PROXY,     // Local HTTP/HTTPS proxy on configured port
    MITM_PROXY,     // TLS-intercepting proxy (requires CA)
    ROOT_IPTABLES,  // Root: iptables REDIRECT + tcpdump/raw socket
    ROOT_FRIDA,     // Root + Frida: SSL pinning bypass + Java-layer capture
    SHIZUKU_TUN,    // Shizuku: VpnService-like tun via Shizuku's ADB network
}
```

**Capture engine interface:**
```kotlin
interface CaptureEngine {
    suspend fun start(method: CaptureMethod, config: CaptureConfig)
    suspend fun stop()
    fun isRunning(): Boolean
    fun getActiveMethod(): CaptureMethod
    fun observeTraffic(): Flow<CapturedTrafficItem>
}
```

**Default selection:** `HTTP_PROXY + VPN_TUN` as dual-path fallback. If proxy fails, VPN catches traffic. If both fail, user sees explicit error.

**Traffic guarantee:** Every `CaptureEngine` implementation MUST write to the same `CaptureEngine.persistTransaction()` path → Room. No engine can silently drop traffic without updating counters and surfacing an error.

#### Capture Method Implementations

**1. VPN_TUN (existing, needs cleanup)**
- Keep `RequestrVpnService` as the VPN_TUN implementation
- Ensure it writes to `CaptureEngine` → Room for ALL packet types, not just plaintext HTTP
- Add TLS SNI logging for TLS streams (already partially done in `TlsDecoder`)
- Fix: currently TLS streams are drained and discarded after SNI extraction — at minimum, log the SNI as a traffic entry

**2. HTTP_PROXY (new)**
- New foreground service: `RequestrProxyService`
- Runs an HTTP/HTTPS proxy on `SettingsDataStore.proxyPort` (default 8080)
- For HTTPS: performs CONNECT tunneling (no decryption without MITM CA)
- For HTTP: parses and logs full request/response
- Writes to same `CaptureEngine` → Room path
- System proxy settings: on Android 5+, apps can use `Proxy.setDefaultProxy()` or the app's own OkHttp/Netty client with proxy configured
- **Important:** Other apps must be configured to use this proxy. The app should provide instructions or automatic proxy configuration where possible.

**3. MITM_PROXY (new)**
- Built on top of HTTP_PROXY
- When CA is installed in user store AND the method is MITM_PROXY:
  - Intercept CONNECT tunnels
  - Present our CA certificate to the client
  - Establish TLS connection to real server
  - Decrypt, log, re-encrypt
- Requires CA in user store (or system store with `networkSecurityConfig`)
- Writes decrypted HTTP traffic to `CaptureEngine` → Room

**4. ROOT_IPTABLES (new)**
- When root is granted AND method is ROOT_IPTABLES:
  - Use `libsu` to execute iptables rules: `iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-port 8080`
  - `iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port 8080`
  - Run local HTTP_PROXY on port 8080 to receive redirected traffic
  - This transparently intercepts ALL app traffic without per-app proxy configuration
- On stop: flush iptables rules
- Writes all captured traffic to `CaptureEngine` → Room

**5. ROOT_FRIDA (new)**
- When root + Frida are available AND method is ROOT_FRIDA:
  - Inject Frida script into target processes
  - Hook `SSLSocketFactory`, `OkHttp`, `HttpURLConnection`, etc.
  - Capture plaintext HTTP before encryption and after decryption
  - Bypass SSL pinning via Frida
- Writes captured traffic to `CaptureEngine` → Room via shared memory or IPC

**6. SHIZUKU_TUN (new)**
- When Shizuku is granted AND method is SHIZUKU_TUN:
  - Use Shizuku's ADB-over-network to establish a tun-like interface
  - Or use Shizuku's elevated permissions to run a local VpnService-like service
  - This is essentially the same as VPN_TUN but with Shizuku's elevated ADB instead of system VpnService permission
- Writes to `CaptureEngine` → Room

#### Auto-Selection Logic

```kotlin
fun resolveAutoCaptureMethod(capabilities: CapabilityState): CaptureMethod = when {
    capabilities.isRootUsable && capabilities.isCaInstalled -> CaptureMethod.ROOT_IPTABLES
    capabilities.isRootUsable -> CaptureMethod.ROOT_IPTABLES // iptables works without CA for HTTP
    capabilities.isShizukuUsable -> CaptureMethod.SHIZUKU_TUN
    else -> CaptureMethod.VPN_TUN
}
```

**Key rule:** The app MUST use the highest available tier automatically. The user should not have to manually select a lower tier when a higher one is available and configured.

#### Traffic Capture Guarantee

**Problem statement:** "local vpn service and proxy service both are different things, so what user selects must reflect from the app on the active hint of interception on both screens. also the selected method must has watcher to have a background task which tells/sees the selected way is working."

**Solution:**
1. **Active hint on both screens:** HomeScreen and InterceptScreen show the active `CaptureMethod` and `ActiveTier` in the status banner. Example: "Interception: ACTIVE · Root + iptables · port 8080"
2. **Method watcher:** A `CaptureMethodWatcher` background coroutine that:
   - Runs every 5 seconds while capture is active
   - Verifies the selected method's transport is actually receiving packets
   - For VPN_TUN: checks tun fd is readable, packet count increasing
   - For HTTP_PROXY: checks proxy port is accepting connections, request count increasing
   - For MITM_PROXY: checks TLS handshake success rate
   - For ROOT_IPTABLES: checks iptables rules exist, proxy receiving traffic
   - For ROOT_FRIDA: checks Frida server is reachable, script is injected
   - If watcher detects silence > 30 seconds, surface an ERROR state with diagnostic text
3. **Dual-path default:** Start both HTTP_PROXY + VPN_TUN simultaneously. If proxy gets traffic, great. If not, VPN catches everything. This is the default because:
   - Proxy captures HTTP cleanly
   - VPN catches everything (including HTTPS CONNECT) but can't decrypt without MITM
   - Together they provide maximum coverage

#### CA Whitelist Automation

**When root or Shizuku is available:**
- After CA is installed in user store, automatically attempt to add to system store:
  - **Root path:** Use `libsu` to mount `/system` read-write, copy CA to `/system/etc/security/cacerts/`, set permissions 644, remount read-only
  - **Shizuku path:** Use Shizuku's ADB shell with `pm grant` + root-like access to mount system and install CA
  - **Magisk module path:** If Magisk is detected, generate a module that installs the CA to system store on boot
- Track CA state: `CaState.USER_STORE` → attempt system install → `CaState.SYSTEM_STORE`
- If system install succeeds, all apps trust the CA without per-app network security config

#### Root Tool Detection + Module Generation

**Root type detection:**
```kotlin
enum class RootType {
    NONE,
    MAGISK,      // Magisk app + su binary + magiskd daemon
    KSU,         // KernelSU (kernel module)
    KSU_NEXT,    // KSU Next
    NAPATCH,     // nAPatch
    GENERIC_SU,  // su binary exists but no known manager
}
```

**Detection logic:**
- Check for Magisk: `/data/adb/magisk` directory, `magiskd` process, `magisk` app package
- Check for KSU: `/data/adb/ksu` directory, `ksud` daemon
- Check for KSU Next: `/data/adb/ksu-next` directory
- Check for nAPatch: `/data/adb/napatch` directory
- Check for generic su: `which su` returns path, `su -c id` succeeds

**Module generation:**
For each root type, generate a module that:
1. Installs the requestr CA to `/system/etc/security/cacerts/` on boot
2. Sets up iptables rules for transparent interception (if iptables module available)
3. Disables SELinux enforcement or sets permissive mode for capture (if Magisk/KSU)
4. Hides the requestr app from recents/task switcher (if supported by root method)
5. Grants `android.permission.ACCESS_SUPERUSER` or equivalent network permissions

**Module output:**
- Save module zip to `FileExporter.getExportFolder(context) / "requestr-module-{rootType}-{timestamp}.zip"`
- Provide instructions: "Flash in Magisk/KSU/nAPatch manager" or "Copy to /data/adb/modules/ and reboot"
- If app can flash automatically (Magisk with proper API), offer one-tap install

#### FAB Spacing Fix

**Current code** (`InterceptScreen.kt:508-510`):
```kotlin
Modifier
    .align(Alignment.BottomEnd)
    .padding(bottom = 76.dp, end = 20.dp)
```

**Problem:** `bottom = 76.dp` leaves too much space between FAB and bottom nav bar. The FAB appears to "float" too high with a gap below it.

**Fix:**
- Change `bottom = 76.dp` to `bottom = 16.dp` (or match the bottom nav bar height + small margin)
- The bottom pill nav bar height is approximately 64dp total (56dp content + padding). With `bottom = 16.dp`, the FAB sits just above the nav bar with appropriate visual separation.
- Alternatively, anchor the FAB to the Scaffold's `bottomBar` padding using `Modifier.padding(bottom = 80.dp)` if the nav bar is 64dp tall — but 76dp seems to be accounting for mini player + nav bar. Need to check exact nav bar height.

**Actually, looking at `MainActivity.kt:196-231`:**
- The bottom bar contains `MiniPlayerPersistentBar` + `HoveredPillNavigationBar`
- Mini player height: ~52dp (36dp album art + padding)
- Pill nav height: ~56dp content + 11dp vertical padding = ~67dp
- Total bottom bar height: ~120dp
- FAB bottom padding of 76dp means it's positioned above the nav area but with a large gap

**Fix:** Reduce FAB bottom padding to `16.dp` so it sits close to the bottom edge of the screen, above the nav bar but without the large gap. The visual separation between FAB and nav bar is handled by the nav bar's own padding.

#### Nav Rename

**"proxy" → "record"** in bottom pill navigation:
- `CommonComponents.kt:593` — `Triple("apis", "APIs", Icons.Default.Public)` is already "APIs"
- Wait, the user said "proxy" item. Let me check... Actually looking at the code, the nav items are: Home, Proxy, APIs, Tools, AI, Notes, Flow, Music. The second item is "Proxy" (`Triple("intercept", "Proxy", Icons.Default.Radar)`).
- Rename "Proxy" to "Record" in the pill nav label
- Also update any other references to "proxy" in navigation contexts

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/ui/screens/InterceptScreen.kt` | Rename title to "Interceptr"; update FAB bottom padding; update status banners to show active capture method |
| `app/src/main/java/com/example/ui/components/CommonComponents.kt` | Rename "Proxy" nav label to "Record" |
| `app/src/main/java/com/example/data/interception/InterceptionState.kt` | Add `captureMethod: CaptureMethod` to `InterceptionState`; update `tierLabel` to include method |
| `app/src/main/java/com/example/data/interception/InterceptionStateRepository.kt` | Add `setCaptureMethod()`; update `markActive()` to accept `CaptureMethod` |
| `app/src/main/java/com/example/data/prefs/SettingsDataStore.kt` | Add `captureMethod` preference; add `CaptureMethod` enum or keep in `data/interception` package |
| `app/src/main/java/com/example/interception/RequestrVpnService.kt` | Refactor to implement `CaptureEngine` interface as `VpnTunCaptureEngine` |
| `app/src/main/java/com/example/interception/HttpProxyService.kt` | **New** — HTTP proxy foreground service |
| `app/src/main/java/com/example/interception/MitmProxyService.kt` | **New** — TLS MITM proxy (extends proxy, adds CA-based decryption) |
| `app/src/main/java/com/example/interception/RootCaptureEngine.kt` | **New** — iptables + tcpdump/raw socket capture for rooted devices |
| `app/src/main/java/com/example/interception/FridaCaptureEngine.kt` | **New** — Frida-based Java-layer capture |
| `app/src/main/java/com/example/interception/ShizukuCaptureEngine.kt` | **New** — Shizuku ADB-based tun capture |
| `app/src/main/java/com/example/interception/CaptureMethodWatcher.kt` | **New** — background verification that selected method is receiving traffic |
| `app/src/main/java/com/example/interception/CaWhitelistService.kt` | **New** — auto-install CA to system store when root/Shizuku available |
| `app/src/main/java/com/example/interception/RootModuleGenerator.kt` | **New** — detect root type, generate Magisk/KSU/nAPatch module |
| `app/src/main/java/com/example/data/export/FileExporter.kt` | Reuse for module export |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | Update intercept banner to show active capture method |
| `app/src/main/java/com/example/MainActivity.kt` | Update nav item label "Proxy" → "Record" |

### Validation
- App title reads "Interceptr" on the intercept screen
- When root is granted, app automatically uses ROOT_IPTABLES method (transparent iptables redirect)
- When Shizuku is granted, app uses SHIZUKU_TUN method
- When only VPN available, app uses VPN_TUN (existing behavior)
- CA is automatically added to system store on rooted devices
- Magisk module is generated and saved to exports folder when Magisk is detected
- FAB sits close to bottom nav bar with no large gap
- Bottom nav shows "Record" instead of "Proxy"
- All capture methods produce traffic entries in the log
- If a method goes silent > 30s, the status changes to ERROR with diagnostic text
- Home screen banner shows active method (e.g., "Interception: ACTIVE · Root + iptables · port 8080")
- Dual-path default (proxy + VPN) ensures maximum traffic coverage

### Out of scope
- Full MITM TLS decryption implementation (requires significant new crypto/parsing work)
- Frida script injection for specific target apps (generic hook framework only)
- iptables rule management for IPv6
- Automatic proxy configuration for all apps (requires VPNService or per-app settings)
- SELinux policy modification in generated modules
- Background capture when app is not in foreground (requires foreground service already in place)
- Capture method switching while capture is active (must stop before switching)
- Network speed/performance benchmarking of each method

---

## Fix 09 — API Testr: Saved/Liked APIs, URL Bar Richness, Request/Response Clarity, UI Consistency

### Context
The current `ApiTestrScreen.kt` has four major problems:
1. **Save & Favorite buttons are non-functional:** `saveRequest()` and `isFavorite` toggle exist but there is no dedicated Saved APIs / Liked APIs screen to browse, manage, or reuse saved requests.
2. **Request/Response tabs are over-fancified:** The segmented bar shows `↑ Request (2q, 1h)` and `↓ Response` with embedded query/header counts — this is noisy, misbehaving UI that doesn't clearly communicate "Request" vs "Response".
3. **URL bar is a dumb text field:** No "Follow Redirects" toggle, no inline param visibility, no syntax highlighting for `https://domain/endpoint?key=value`, and param modifications don't reflect in real-time in the URL field.
4. **Button/dropdown UI inconsistency:** The method dropdown pill, Send button, and body-type selector chips have irregular sizes, paddings, and corner radii — they don't form a cohesive design system.

### Changes

#### 1. Saved & Liked APIs Section

**Current state:** `ApiCollectionsRepository.saveRequest()` exists and writes to an in-memory `MutableStateFlow<List<ApiRequestItem>>`. `isFavorite` toggles on the request item. But there is NO screen to view saved requests or favorites. The HomeScreen shows "Recently opened" but only from the in-memory list, which is lost on app restart.

**New screen:** `SavedApisScreen` — accessible from bottom nav or from a new "Saved" tab in API Testr.

**Structure:**
- Two tabs at top: **"Saved"** (all saved requests) and **"Liked"** (favorites only)
- Each request shown as a compact card:
  - Method badge (colored by HTTP method)
  - Request name
  - URL (truncated)
  - Timestamp ("Updated 2 hours ago")
  - Favorite star icon (toggleable)
- Tapping a card opens it in `ApiTestrScreen` for editing/sending
- Swipe-to-delete or long-press context menu to delete
- Empty state when no saved/favorited requests

**Persistence upgrade:** Currently `ApiCollectionsRepository` stores requests in-memory only. For this pass:
- Keep in-memory storage (Room persistence for API collections is a larger architectural decision)
- BUT ensure the Saved/Liked screen reads from the same in-memory source so favorites and saved requests are visible immediately
- The HomeScreen "Recently opened" section should also read from `ApiCollectionsRepository.requests` instead of its own separate tracking

**Files to modify:**
- `app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` — add tab row for "Request / Saved / Liked" at top
- `app/src/main/java/com/example/ui/screens/SavedApisScreen.kt` — **new file** for full-screen saved/liked browser
- `app/src/main/java/com/example/MainActivity.kt` — add `Screen.SavedApis` route; add nav item or integrate into existing APIs route
- `app/src/main/java/com/example/ui/screens/HomeScreen.kt` — update "Recently opened" to read from `ApiCollectionsRepository.requests`

#### 2. Request/Response Tab Redesign

**Current state (lines 267-296):**
```kotlin
Button("↑ Request (2q, 1h)", ...)  // Shows query param count + header count
Button("↓ Response", ...)
```

**Problem:** Embedding `q` and `h` counts inside the tab button is confusing. The user should see clean "Request" and "Response" labels.

**New design:**
- Clean segmented control with two tabs: **"Request"** and **"Response"**
- Active tab: filled with `primary` color, white text
- Inactive tab: `surfaceVariant` background, `onSurfaceVariant` text
- No embedded counts in the tab labels
- Counts can be shown as subtle badges next to the tab label IF needed, but NOT inline with the text in a confusing way
- Height: 40dp, corner radius 10dp, horizontal spacing 8dp

**Request sub-tabs remain:** Query, Headers, Body, Auth, Scripts — but clean up the labels:
- `"Query (2)"` → `"Query"` (count shown as small badge if desired)
- `"Headers (1)"` → `"Headers"`
- `"Body"` → unchanged
- `"Auth"` → unchanged
- `"Scripts"` → unchanged

**Response sub-tabs remain:** Body, Headers, Timeline Metrics — clean labels:
- `"Body"` → unchanged
- `"Headers (3)"` → `"Headers"`
- `"Timeline Metrics"` → unchanged

#### 3. URL Bar Redesign

**Current state (lines 149-265):**
- URL is a plain `OutlinedTextField` inside a `GlassCard`
- No follow-redirects toggle
- No inline param visibility
- Params in `KeyValueEditorTable` don't reflect in the URL field
- No syntax highlighting

**New design:**

**A. URL bar container:**
- Sticky bar at top of request editor
- Background: `surfaceVariant` (muted, not full card)
- Height: auto, min 48dp
- Left side: method dropdown pill (same size as other controls)
- Center: URL text display (non-editable, scrollable horizontally)
- Right side: "Follow Redirects" toggle switch + "Send" button

**B. Follow Redirects toggle:**
- Small `Switch` or toggle button on the right side of the URL bar
- Default: ON
- When ON: `ApiTestrRepository.executeRequest()` follows 3xx redirects automatically
- When OFF: returns the 3xx response as-is
- Visual: compact, accent-colored when active

**C. URL display with syntax highlighting:**
- The URL field is **read-only display** — editing happens in the method/params/body fields below, not by typing the URL directly
- URL is constructed dynamically from:
  - Base URL (editable in a separate field or via the params)
  - Query params (from `KeyValueEditorTable`)
  - Only params with non-empty keys are appended to the URL
- **Real-time reflection:** When a param key is modified (added/changed/deleted), the URL display updates immediately
- **Syntax highlighting via `AnnotatedString`:**
  - `https://` / `http://` → `primary` color (accent)
  - Domain → `onSurface` (primary text)
  - Path/endpoint → `onSurface`
  - `?` separator → `onSurfaceVariant` (muted)
  - Parameter keys → `secondary` (distinct color)
  - `=` signs → `onSurfaceVariant` (muted)
  - Parameter values → `tertiary` or `onSurfaceVariant`
- Example highlight: `https://api.example.com/v1/users?format=json&debug=1`
  - `https://` → blue/accent
  - `api.example.com` → white/primary
  - `/v1/users` → white/primary
  - `?` → gray
  - `format` → orange/secondary
  - `=` → gray
  - `json` → light gray/tertiary
  - `&` → gray
  - `debug` → orange/secondary
  - `1` → light gray

**D. Param editing behavior:**
- `KeyValueEditorTable` for query params exists below the URL bar (in Request > Query tab)
- When user adds a new param row with empty key → it does NOT appear in the URL bar (only enabled params with non-empty keys appear)
- When user types a key → URL bar updates in real-time
- When user modifies a value → URL bar updates in real-time
- When user disables a param (checkbox off) → it is removed from the URL bar
- When user deletes a param row → it is removed from the URL bar
- The URL bar is a **read-only reflection** of the current param state — users do not edit the URL bar directly

**E. URL construction logic:**
```kotlin
fun buildDisplayUrl(baseUrl: String, params: List<KeyValueItem>): AnnotatedString {
    val enabledParams = params.filter { it.isEnabled && it.key.isNotBlank() }
    val url = if (enabledParams.isEmpty()) {
        baseUrl
    } else {
        val queryString = enabledParams.joinToString("&") { "${it.key}=${it.value}" }
        if (baseUrl.contains("?")) "$baseUrl&$queryString" else "$baseUrl?$queryString"
    }
    return buildAnnotatedUrl(url)
}
```

#### 4. UI Consistency — Buttons, Dropdowns, Chips

**Current problems:**
- Method dropdown pill: `padding(horizontal = 10.dp, vertical = 10.dp)` — too tall, misaligned with URL field
- Send button: `height = 54.dp` in some places, `RoundedCornerShape(10.dp)` — inconsistent
- Body type chips: `RoundedCornerShape(8.dp)`, `padding(horizontal = 10.dp, vertical = 6.dp)` — different from method dropdown
- Request/Response segmented buttons: `RoundedCornerShape(10.dp)`, `weight(1f)` — okay but labels are the problem
- "Add Row" button in KeyValueEditorTable: `RoundedCornerShape(8.dp)`, small text — inconsistent with other buttons

**Unified design tokens:**
- **Standard button height:** 42dp
- **Standard corner radius:** 10dp
- **Standard horizontal padding:** 14dp
- **Standard vertical padding:** 10dp
- **Standard chip height:** 32dp
- **Standard chip corner radius:** 8dp
- **Standard chip horizontal padding:** 12dp
- **Standard chip vertical padding:** 6dp
- **Standard font size for controls:** 12.sp
- **Standard font size for labels:** 11.sp
- **Standard icon size:** 16.dp

**Method dropdown pill:**
- Height: 42dp (matches standard button)
- Vertical padding: 10dp (centers MethodBadge vertically)
- Corner radius: 10dp
- Background: `surfaceVariant`
- Text: `MethodBadge` with fontSize 12

**Send button:**
- Height: 42dp
- Corner radius: 10dp
- Horizontal padding: 18dp (slightly wider for emphasis)
- Vertical padding: 10dp
- Icon: 16dp
- Text: "Send", 12.sp, Bold
- Background: `primary`
- Content color: `onPrimary`

**Body type chips / Auth type chips:**
- Height: 32dp
- Corner radius: 8dp
- Horizontal padding: 12dp
- Vertical padding: 6dp
- Font size: 11.sp
- Active: `primary` background, white text
- Inactive: `surfaceVariant` background, `onSurfaceVariant` text

**KeyValueEditorTable "Add Row" button:**
- Match standard button: height 42dp, corner 10dp, padding 14dp/10dp
- Or use an IconButton (24dp) with `Add` icon for a more compact treatment — user's choice, but be consistent

**Request/Response segmented tabs:**
- Height: 40dp
- Corner radius: 10dp
- Gap: 8dp
- Active: `primary` fill
- Inactive: `surfaceVariant` fill
- Text: 12.sp, SemiBold

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` | URL bar redesign, Request/Response tab cleanup, button/dropdown UI consistency, follow-redirects toggle, real-time URL construction with syntax highlighting |
| `app/src/main/java/com/example/ui/screens/SavedApisScreen.kt` | **New file** — Saved/Liked APIs browser |
| `app/src/main/java/com/example/MainActivity.kt` | Add `Screen.SavedApis` route; add nav access |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | Update "Recently opened" to use `ApiCollectionsRepository.requests` |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Ensure `ApiCollectionsRepository` persistence is adequate for this pass |
| `app/src/main/java/com/example/data/repository/ApiTestrRepository.kt` | Add `followRedirects` parameter to `executeRequest()` |

### Validation
- Tapping star toggles favorite state, persisted in `ApiCollectionsRepository`
- Tapping save stores request in `ApiCollectionsRepository._requests`
- Saved/Liked screen shows all saved requests with method badges, names, URLs, timestamps
- Tapping a saved request opens it in API Testr editor
- HomeScreen "Recently opened" shows actual saved requests from repository
- Request/Response tabs are clean "Request" / "Response" without noisy q/h counts
- URL bar shows constructed URL with syntax highlighting (scheme in accent, domain in primary, params in secondary)
- Adding a param with key "debug" and value "1" immediately updates URL bar to show `?debug=1`
- Deleting a param removes it from URL bar instantly
- Disabling a param removes it from URL bar
- "Follow Redirects" toggle exists on right side of URL bar, defaults to ON
- Method dropdown pill, Send button, body-type chips, Add Row button all share consistent height (42dp/32dp), corner radius (10dp/8dp), padding, and font sizes
- Request/Response segmented tabs have consistent sizing and clean labels

### Out of scope
- Full persistent storage for API collections (Room database for collections — deferred)
- Import/export of API collections (JSON/HAR)
- Folder management (create/rename/delete folders)
- Environment variables / base URL per folder
- Request history / timeline of sent requests
- WebSocket request support
- GraphQL query editor with syntax highlighting

---

## Fix 10 — Setup / Onboarding Screen Redesign + Re-run Behavior

### Context
The current `OnboardingScreen.kt` is a functional prototype but lacks branding consistency with the main app, uses generic "stocky" visual language, and doesn't adapt well when permissions are already granted. The user wants:
1. Logo, app name, and fonts that match the main app's theme system
2. More refined, logical design instead of generic placeholder vibes
3. A "Skip" button on permission steps when the permission is already granted
4. The "Re-run Setup Wizard" setting (Fix 03) must work with this redesigned onboarding

### Current State
- `OnboardingScreen.kt` uses a 4-step wizard: Permissions & Tiers → Proxy/CA → Proxy Auth → Theme
- Step 3 permission cards show Storage, Network, and (after Fix 05) Music
- Each card has `SHOULD_REQUEST` → `GRANTED` / `DENIED` states
- No "Skip" button when permission is already granted
- Logo is a generic emoji or icon, not branded
- Fonts are default Compose typography, not matched to app's theme tokens
- Background is plain black or solid color, not using the app's theme surfaces

### Changes

#### 1. Branding Alignment

**App name/logo:**
- Replace generic onboarding logo with the app's actual branding: **"requestr"** in the app's chosen display font
- Use the same `MaterialTheme.typography` scale as the rest of the app
- Logo treatment: `FontWeight.Bold`, `fontSize = 32.sp`, `color = primary`, with optional accent-colored dot or underline
- Subtitle below: `"Network Security & API Intelligence"` in `fontSize = 14.sp`, `color = onSurfaceVariant`

**Font consistency:**
- All onboarding text uses `MaterialTheme.typography`:
  - Step titles: `titleLarge`, `fontWeight = Bold`
  - Step descriptions: `bodyMedium`, `color = onSurfaceVariant`
  - Card titles: `titleSmall`, `fontWeight = SemiBold`
  - Card descriptions: `bodySmall`, `color = onSurfaceVariant`
  - Button text: `labelLarge`, `fontWeight = Bold`

**Background/surfaces:**
- Onboarding background: `MaterialTheme.colorScheme.background` (not hardcoded black)
- Cards: `surface` with `outline` border, `RoundedCornerShape(16.dp)`
- Active states: `primary` color for selected/active elements
- No hardcoded colors anywhere in onboarding

#### 2. Permission Step Redesign (Step 3)

**Current:** Three permission cards stacked vertically, each with icon + title + description + status button.

**New design:**
- Cards remain stacked but with improved spacing and typography
- Each card:
  - Icon in a rounded container (`RoundedCornerShape(12.dp)`, `surfaceVariant` background)
  - Title: `"Storage Access"` / `"Network Permissions"` / `"Local Music Library"`
  - Description: one line explaining why the permission is needed
  - Status indicator:
    - `GRANTED` → green check circle + `"Granted"` text
    - `SHOULD_REQUEST` → accent-colored `"Grant"` button
    - `DENIED` → muted `"Denied"` text with optional `"Open Settings"` link
- **Skip button:** When permission is already `GRANTED`, show a subtle `"Skip"` text button (not a full button) at the bottom-right of the card, allowing the user to proceed without re-requesting

**Skip behavior:**
- If all three permissions are `GRANTED`, the entire step shows a "All permissions granted" summary with a single "Continue" button
- If some are granted and some denied, granted ones show skip, denied ones show status
- Skip does NOT mark the permission as ungranted — it just bypasses the request flow for that step

#### 3. Step Flow Improvements

**Step 1 → Permissions & Tiers:**
- Clean title: `"Permissions & Access"`
- Subtitle: `"requestr needs these permissions to capture and analyze traffic"`
- Permission cards as described above
- "Continue" button at bottom, disabled until at least Storage + Network are handled (Music is optional)

**Step 2 → Proxy & CA Setup:**
- Clean title: `"Proxy & Certificate"`
- Subtitle: `"Set up the interception engine and trust store"`
- Show current CA status (installed / not installed)
- Show proxy port configuration
- "Continue" button

**Step 3 → Theme:**
- Clean title: `"Appearance"`
- Subtitle: `"Choose your visual style"`
- Appearance selector (System / Light / Dark icons) — matches Fix 01 design
- Accent color swatches — matches Fix 01 design
- "Finish" button completes onboarding

**Progress indicator:**
- 3-dot progress indicator at top: `● ○ ○` (step 1 active), `○ ● ○` (step 2), `○ ○ ●` (step 3)
- Dots are `primary` when active, `surfaceVariant` when inactive
- Smooth transition between steps

#### 4. Re-run Setup Wizard

**From Fix 03:** Settings has a "Re-run Setup Wizard" row that:
- Sets `onboardingCompleted = false`
- Clears username
- Navigates to `Screen.Onboarding.route`

**With Fix 10 applied:** The re-run launches this redesigned onboarding, which:
- Respects already-granted permissions (shows skip buttons)
- Shows current CA status (already installed)
- Shows current theme selection (pre-filled)
- User can change anything or just tap through to finish

**Implementation:**
- `OnboardingScreen` reads current settings on entry
- Permission cards show current grant status immediately
- If permission is already granted, the Grant button is replaced with a skip option
- Theme selector in Step 3 pre-selects the current theme
- CA status in Step 2 shows current state

### Files to modify
| File | Changes |
|---|---|
| `app/src/main/java/com/example/ui/screens/OnboardingScreen.kt` | Complete redesign: branding, fonts, surfaces, skip buttons, progress indicator, pre-filled state |
| `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | Ensure "Re-run Setup Wizard" row exists (from Fix 03) and works with new onboarding |
| `app/src/main/java/com/example/data/repository/AppRepositories.kt` | Ensure permission status is correctly read and written in `SettingsRepository` |

### Validation
- Fresh install → onboarding opens with branded logo "requestr", subtitle, progress dots
- Step 1 shows 3 permission cards with Grant buttons
- If permission already granted → card shows "Granted" + subtle "Skip" button
- Step 2 shows CA status and proxy config
- Step 3 shows appearance selector + accent swatches, pre-filled with current theme if re-running
- Tapping "Finish" → onboarding completes, returns to Home
- "Re-run Setup Wizard" from Settings → onboarding opens, shows current state, allows changes
- All text uses app's theme typography, colors use theme tokens
- Background and cards use theme surfaces, not hardcoded colors

### Out of scope
- Animated transitions between onboarding steps (can add later)
- Onboarding analytics/tracking
- Custom illustration or artwork beyond the app name/logo
- Biometric authentication setup in onboarding
- Notification permission request (not needed for core functionality)