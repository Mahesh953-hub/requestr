# Requestr — Frozen Design System (Plan v3, Task 7)

> **Status: FROZEN.** This document is the implementation-ready, ownership-locked
> contract for the v6 reusable design system and shared data interfaces. It is
> produced by plan v3 task 7 and is the single source of truth that all parallel
> UI lanes (A–D) implement against. Tokens, component APIs, and the shared data
> contracts below do not change without a prerequisite PR from the integration/data
> owner. Provenance: `DESIGN-RECORD.md` + `TRANSPLANT-MANIFEST.md` (plan task 5),
> semantic transplant (plan task 6, PR #7 head `7563010`).

---

## 1. Purpose & scope

- Freeze the exact visual/animation tokens and component APIs of the accepted
  main-lineage UX so lane implementers recreate it against backup APIs without
  re-reading main history or improvising values.
- Lock ownership of every shared visual component and shared data file so no two
  parallel lanes edit the same writable path (gate: ownership table has no
  overlapping writable path).
- Cover accessibility axes explicitly: **light/dark**, **dynamic accents**,
  **RTL**, **font scaling**, **TalkBack**, **reduced motion**, and **blur
  fallback** (see §14).
- Functional conflicts: **backup behavior wins**. This document never overrides a
  backup data/runtime contract. Data-layer behavior is included only where a
  visual component depends on it.

---

## 2. Ownership table

One integration/data owner holds the three frozen shared data files and makes any
required interface change for all consumers via a small prerequisite PR. Four UI
lanes own disjoint sets of Compose files. `BadgePill` and `CommonComponents.kt`
belong to the shared-shell lane after this PR merges.

| Owner lane | Writable paths (Compose / data) | Consumes (read-only) |
|---|---|---|
| **Integration/data owner** (frozen) | `data/repository/AppRepositories.kt`, `data/models/AppModels.kt`, `data/prefs/UnifiedDataStore.kt` | — |
| **Lane A — shared shell / runtime** | `ui/theme/**`, `ui/components/CommonComponents.kt` (incl. `BadgePill`), `ui/components/CollapsibleNavPill.kt`, `ui/components/AppNavigationDrawer.kt`, `MainActivity.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/OnboardingScreen.kt`, `ui/screens/SettingsScreen.kt`, runtime/CDP UI/state files | frozen data files, `BadgePill` |
| **Lane B — API / Tools** | `ui/screens/ApiTestrScreen.kt`, `ui/screens/ResponseInspectorView.kt` (new), `ui/screens/ToolsScreen.kt`, `ui/screens/ToolWorkspaceScreen.kt`, `data/repository/ApiTestrRepository.kt` (WS client), lane-local tests/files | frozen data files, `BadgePill`, shared glass components |
| **Lane C — Flow / Music / Reqy** | `ui/screens/FlowScreen.kt`, `ui/screens/MusicScreen.kt`, `ui/components/TrackArtworkView.kt` (new), `ui/screens/ChatScreen.kt` (rename/redesign only), lane-local tests/files | frozen data files, `BadgePill`, shared glass components |
| **Lane D — interception** | `ui/screens/InterceptScreen.kt`, extracted HTTP/3/TLS/relay UI components, lane-local UI models/tests, approved relay-counter additions | frozen data files, `BadgePill`, core QUIC interfaces (read-only) |

**Rules:**
- No lane edits a frozen data file (`AppRepositories.kt`, `AppModels.kt`,
  `UnifiedDataStore.kt`). A shared change discovered later becomes a small
  prerequisite PR from the integration/data owner, merged before affected lane
  work resumes.
- No lane duplicates a shared component (`BadgePill`, glass components, nav pill,
  segmented tabs, filter chips, glass switch). Lanes consume them read-only.
- Lane-specific badge variants (`MethodBadge`, `StatusCodeBadge`,
  `ProtocolBadge`) remain in `CommonComponents.kt` (Lane A) but are consumed
  read-only by Lane B/D.

---

## 3. Frozen shared data files & interface list

These three files are frozen after this PR. The interfaces below are the complete
contract lanes depend on; everything required by the accepted UX is already
present (added during the task-6 transplant).

### 3.1 `data/prefs/UnifiedDataStore.kt`

**Keys (`object AppSettingsKeys`)** consumed by Lane A shell + Settings:
- `NAV_BAR_OPACITY` (`floatPreferencesKey("nav_bar_opacity")`)
- `NAV_BAR_BLUR_EFFECT` (`stringPreferencesKey("nav_bar_blur_effect")`)
- `NAV_BAR_HIDDEN_ITEMS` (`stringSetPreferencesKey("nav_bar_hidden_items")`)
- `HEADER_TOP_PADDING` (`intPreferencesKey("header_top_padding")`)
- `HEADER_OPACITY` (`floatPreferencesKey("header_opacity")`)
- `HEADER_BLUR_STYLE` (`stringPreferencesKey("header_blur_style")`)
- `FAB_BOTTOM_SPACING` (`intPreferencesKey("fab_bottom_spacing")`)
- Pre-existing keys retained: `THEME_MODE`, `APPEARANCE_MODE`,
  `SELECTED_DARK_THEME`, `SELECTED_LIGHT_THEME`, `CORNER_RADIUS`, `DENSITY_MODE`,
  `ANIMATION_ENABLED`, `TRANSITION_BEHAVIOR`, `GESTURE_NAVIGATION`,
  `HAPTIC_FEEDBACK`, `DISPLAY_SCALE`, capture/gateway/pairing keys.
- **Rejected:** `TRAFFIC_LOG` is NOT re-added (complaint #4 removal stands).

**Functions:**
- `canonicalGlassStyle(raw: String?): String` — maps legacy + canonical glass
  keys to the four supported component keys (see §13). Pure-Kotlin; covered by
  `GlassStyleKeysTest`.
- `unifiedDataStore` DataStore extension (read/collect).

### 3.2 `data/models/AppModels.kt`

**`UserSettingsState`** fields consumed by Lane A (visual-preference axis, safe
defaults, additive — backup `appearanceMode`/`selectedAccent` semantics
unchanged):
- `selectedAccent: String = "INDIGO"` — orthogonal accent axis.
- `navBarBackgroundOpacity: Float = 0.92f`
- `navBarBlurEffect: String = "FROSTED_GLASS"`
- `navBarHiddenItems: Set<String> = emptySet()`
- `headerTopPaddingDp: Int = 8`
- `headerBackgroundOpacity: Float = 0.90f`
- `headerBlurStyle: String = "FROSTED_GLASS"`
- `fabBottomSpacingDp: Int = 84`
- Pre-existing: `appearanceMode`, `selectedDarkTheme`, `selectedLightTheme`,
  `themeMode`, `cornerRadiusDp`, `densityMode`, `animationEnabled`,
  `transitionBehavior`, `gestureNavigationEnabled`, `hapticFeedbackEnabled`,
  capture/music fields.

**`FlowPipelineItem`** (Lane C): `id`, `name`, `nodeCount`, `triggerType`,
`status` — Room-persisted via `FlowPipelineEntity` (NOT in-memory seeds).

### 3.3 `data/repository/AppRepositories.kt`

**`SettingsRepository`** (object) update functions consumed by Lane A:
- `updateAccent(accent: String)`, `updateAppearanceMode(mode: String)`,
  `updateSelectedDarkTheme`, `updateSelectedLightTheme`
- `updateNavBarOpacity(opacity: Float)`
- `updateNavBarBlurEffect(effect: String)` (canonicalizes via
  `canonicalGlassStyle`)
- `updateNavBarHiddenItems(items: Set<String>)`
- `updateHeaderTopPadding(paddingDp: Int)`
- `updateHeaderOpacity(opacity: Float)`
- `updateHeaderBlurStyle(style: String)` (canonicalizes)
- `updateFabBottomSpacing(spacingDp: Int)`
- `updateCornerRadius`, `updateDensityMode`, `updateAnimationEnabled`,
  `updateTransitionBehavior`, `updateGestureNavigation`,
  `updateHapticFeedback`, `updateSettings(newSettings)`.
- `settings: StateFlow<UserSettingsState>` — the single read surface.

**`FlowRepository`** (Lane C): `allFlows`, `activeFlowId`, `setActiveFlow`,
`setFlowStatus`, `createNewFlow`, `createPipeline`, `deleteFlow`, `updateNode`,
`updateNodePosition`, plus backup-preserved `replaceGraph` import,
`FlowRunSummary`, `FlowExecutor` branching, `EditFlowNodeBottomSheet` derivation.

**Private accent key:** `SELECTED_ACCENT` lives in `SettingsRepository`
(intentionally, scoped to `AppModels.kt` + `AppRepositories.kt`) — not in
`AppSettingsKeys`.

---

## 4. Token conventions

- All values are as-authored; unit suffixes preserved (`dp`, `sp`).
- Springs are `spring(dampingRatio, stiffness)`; tweens are `tween(ms, easing)`.
- Color tokens referencing `MaterialTheme.colorScheme.*` must resolve to the
  universal palettes (§6) — do not hardcode equivalents.
- `0xFF…` literals are exact and preserved verbatim.
- Components marked "gated" read `LocalAnimationsEnabled` and hold static values
  when disabled (§5).
- Every new component must be RTL-safe, font-scale-safe, and carry TalkBack
  semantics where it is icon-only or state-bearing (§14).

---

## 5. Animation system & reduced motion

Source: `ui/theme/AnimationConfig.kt` — byte-identical in backup, no transplant
needed. Lane A owns this file.

| Token | Value / behavior |
|---|---|
| `LocalAnimationsEnabled` | `staticCompositionLocalOf { true }` |
| `LocalAnimationDurationScale` | `compositionLocalOf { 1f }` |
| `scaledTween(durationMillis, delayMillis)` | Disabled or scale ≤ 0 → `tween(0)` (instant). Reduced (0.4) → duration scaled `(duration * scale)` (delay scaled too). |
| `scaledSpring(dampingRatio, stiffness)` | Disabled or scale ≤ 0 → `tween(0)`. Reduced → `stiffness / scale.coerceAtLeast(0.15f)`. |
| `ProvideAnimationConfig(animationsEnabled, transitionBehavior)` | `transitionBehavior.uppercase()`: `"NONE"` → `0f`; `"REDUCED"` → `0.4f`; else `1f`. |
| Motion policy | STANDARD = full; REDUCED = 0.4× with faster springs; NONE = zero-duration. |

**Rule:** replace raw `animate*AsState`/`tween`/`spring` with
`scaledTween`/`scaledSpring`. Gate infinite animations (spinners, pulses,
waveform bars, equalizers, vinyl rotation) behind `LocalAnimationsEnabled` —
hold static frames when disabled. Haptics must never be the only feedback for a
state change (§14).

---

## 6. Theme tokens

### 6.1 Universal palettes (already in backup — `ui/theme/Color.kt`, Lane A)

**UniversalLight**

| Role | Hex |
|---|---|
| Background | `0xFFF8FAFC` |
| Surface | `0xFFFFFFFF` |
| SurfaceVariant | `0xFFF1F5F9` |
| Border | `0xFFE2E8F0` |
| BorderVariant | `0xFFCBD5E1` |
| TextPrimary | `0xFF0F172A` |
| TextSecondary | `0xFF475569` |
| TextMuted | `0xFF94A3B8` |

**UniversalDark**

| Role | Hex |
|---|---|
| Background | `0xFF090D16` |
| Surface | `0xFF101726` |
| SurfaceVariant | `0xFF182238` |
| Border | `0xFF263550` |
| BorderVariant | `0xFF334155` |
| TextPrimary | `0xFFF8FAFC` |
| TextSecondary | `0xFF94A3B8` |
| TextMuted | `0xFF64748B` |

### 6.2 Ten accents (already in backup — `AppAccent` enum, Lane A)

| Accent | Regular | Bright | Container Light | Container Dark |
|---|---|---|---|---|
| INDIGO | `0xFF6366F1` | `0xFF818CF8` | `0xFFEEF2FF` | `0xFF1E1B4B` |
| CYAN | `0xFF06B6D4` | `0xFF22D3EE` | — | — |
| EMERALD | `0xFF10B981` | `0xFF34D399` | — | — |
| ORANGE | `0xFFF97316` | `0xFFFB923C` | — | — |
| LIME | `0xFF84CC16` | `0xFFA3E635` | — | — |
| VIOLET | `0xFF8B5CF6` | `0xFFA78BFA` | — | — |
| ROSE | `0xFFF43F5E` | `0xFFFB7185` | — | — |
| AMBER | `0xFFF59E0B` | `0xFFFBBF24` | — | — |
| SAPPHIRE | `0xFF3B82F6` | `0xFF60A5FA` | — | — |
| OBSIDIAN | `0xFF64748B` | `0xFF94A3B8` | — | — |

### 6.3 Theme resolution glue (Lane A — `ui/theme/Theme.kt`, `MainActivity.kt`)

- `MyApplicationTheme(isDarkTheme: Boolean, accent: AppAccent)` overload used by
  the app shell (preserve backup's `MyApplicationTheme(themeMode)` + per-family
  `selectedLightTheme`/`selectedDarkTheme` semantics as fallback).
- `AppAccent.fromKey` extra mapping `"DYNAMIC_LIME" → LIME` (legacy key
  compatibility) and display-name tweaks ("Dynamic Cyan", "Dynamic Lime").
- App-shell resolution derives `isDarkTheme` from `appearanceMode` + system state
  (backup's SYSTEM-mode per-family logic is the fallback). A stored boolean
  `IS_DARK_THEME` is **not** added — backup `appearanceMode` already covers
  light/dark/system resolution.
- Reads `selectedAccent` from `SettingsRepository.settings`.

---

## 7. Density & spacing

Source: `ui/theme/DensityConfig.kt` — byte-identical in backup, Lane A owns.

| Mode | Top padding | Horizontal padding | Bottom padding |
|---|---|---|---|
| CompactSpacing | `4.dp` | `8.dp` | `4.dp` |
| ComfortableSpacing | `8.dp` | `14.dp` | `8.dp` |

Provided via `ProvideDensityConfig(densityMode)`. Transplanted components must
consume these where a main component hardcoded one of these values.

---

## 8. Navigation pill (Lane A — `ui/components/CollapsibleNavPill.kt`)

### 8.1 Container (`CollapsibleNavPill`)

| Token | Value |
|---|---|
| Outer padding | `horizontal 16.dp`, `vertical 6.dp` |
| Capsule radius | `RoundedCornerShape(28.dp)` |
| Shadow | `elevation 12.dp`, `ambientColor primary@0.10`, `spotColor Black@0.25` |
| Border | `0.9.dp` |
| Inner padding | `horizontal 6.dp`, `vertical 6.dp` |
| Tab spacing | `Arrangement.spacedBy(4.dp)` |
| Opacity source | `settings.navBarBackgroundOpacity.coerceIn(0.2f, 1f)` |

### 8.2 Background brush by blur mode

| Mode key | Brush |
|---|---|
| `"SOLID_M3"` | verticalGradient(`surface` → `surfaceVariant`) |
| `"OLED_BLACK"` | verticalGradient(`Black@opacity` → `0xFF0F172A@opacity`) |
| `"TRANSLUCENT"` | verticalGradient(`surface@(opacity*0.7)` → `surfaceVariant@(opacity*0.5)`), coerced `0.2f..1f` |
| else `"FROSTED_GLASS"` | verticalGradient(`surface@opacity` → `surfaceVariant@(opacity*0.95)`), coerced `0.2f..1f` |

### 8.3 Border color by blur mode

| Mode | Border |
|---|---|
| `"SOLID_M3"` | `outlineVariant@0.6` |
| `"OLED_BLACK"` | `White@0.15` |
| else | `outline@0.35` |

### 8.4 Tab (`NavPillTab`)

| Token | Value |
|---|---|
| Active bg | `primary@0.16` (else `Transparent`), `animateColorAsState spring(0.75, 350)` |
| Active fg | `primary` (else `onSurfaceVariant@0.75`), `animateColorAsState spring(0.75, 350)` |
| Active scale | `1.04` (else `1.0`), `animateFloatAsState spring(0.65, 400)` |
| Shape | active `RoundedCornerShape(24.dp)` / inactive `10.dp` (instant) |
| Size animation | `animateContentSize spring(DampingRatioMediumBouncy, StiffnessMediumLow)` |
| Border | active `0.8.dp primary@0.35`, else `0.dp` |
| Padding | active `h13 v8` / inactive `h10 v8` |
| Icon | `19.dp` |
| Label | `12.sp`, `FontWeight.Bold`, SansSerif |
| Label enter | `fadeIn(spring(0.75,400)) + expandHorizontally(spring(0.75,400))` |
| Label exit | `fadeOut(spring(0.75,400)) + shrinkHorizontally(spring(0.75,400))` |
| Label spacing | `Spacer 6.dp` before label, `4.dp` before dot |
| Active dot | `4.dp` circle, `primary` |
| Haptic | `HapticFeedbackType.LongPress` on navigate (only when `!isActive`) |

**Hit-target caveat:** ≈ 35dp touch height (below 48dp recommendation) —
documented known limitation; finalize at judgment (task 9).

### 8.5 Hidden items

Inactive destinations filtered by `settings.navBarHiddenItems` via
`remember(hiddenItems)`. Default list: Home, Intercept, APIs, Tools, Flow, Music.

---

## 9. Glass headers & shared glass components (Lane A — `CommonComponents.kt`)

### 9.1 Frosted-glass emulation (no real blur)

Glass is **emulated**: vertical-gradient translucency + hairline border + soft
shadow. There is deliberately **no `RenderEffect`/`Modifier.blur`** — this is the
blur fallback (§14.7). All §9 components share the `when (blurStyle)` brush +
border logic from §8.2/§8.3 (opacity `0.2..1`; `headerBackgroundOpacity` for
header items). `GlassActionPill`/header use `outline@0.30` for the else-branch
border (nav pill uses `0.35`).

### 9.2 `GlassActionPill`

| Token | Value |
|---|---|
| Size | `38.dp` default (param `size`) |
| Icon size | `18.dp` |
| Radius | `RoundedCornerShape(14.dp)` |
| Shadow | `elevation 6.dp`, `ambient primary@0.08`, `spot Black@0.15` |
| Border | `0.8.dp` |
| Press scale | `0.90` via `animateFloatAsState spring(0.6, 600)` (`detectTapGestures`) |
| Badge dot | `8.dp` circle TopEnd, `padding 3.dp`, `primary` (shown when `badgeCount > 0`) |
| Haptic | `HapticFeedbackType.LongPress` on tap |

### 9.3 `FloatingCapsuleHeader` / `DynamicGlassHeader` alias

| Token | Value |
|---|---|
| Top padding | `(6 + settings.headerTopPaddingDp).dp` (row: start 16, end 16, top topPadding, bottom 6) |
| Title capsule radius | `RoundedCornerShape(18.dp)` |
| Title capsule shadow | `elevation 6.dp`, `ambient primary@0.08`, `spot Black@0.12` |
| Title capsule border | `0.8.dp` |
| Title capsule padding | `horizontal 14.dp`, `vertical 8.dp`; `animateContentSize spring(MediumBouncy, MediumLow)` |
| Title icon | `16.dp`, tint `primary`, spacer 6dp |
| Title text | `14.5.sp` Bold, `letterSpacing -0.2.sp`, SansSerif, lowercase, ellipsis max 1 line |
| Wordmark variant | `RequestrWordmark(fontSize = 17)` when title equals "requestr" (ignoreCase) |
| Title cross-fade | `AnimatedContent`: `fadeIn(tween(180, delay 60)) togetherWith fadeOut(tween(100))` |
| Status dot | `6.dp` circle, `statusDotColor ?: primary`, spacer 6dp (hidden for wordmark) |
| Trailing capsules | `Arrangement.spacedBy(8.dp)`, right side |
| Capsule grouping | leading action + title capsule in `weight(1f, fill=false)` row, `spacedBy(8.dp)` |

### 9.4 `DynamicGlassSwitch`

| Token | Value |
|---|---|
| Track | `46.dp × 26.dp`, `RoundedCornerShape(13.dp)` |
| Track bg | checked `primary@0.95` / unchecked `surfaceVariant@0.65` |
| Border | `0.6.dp`; checked `primary@0.8` / unchecked `outline@0.4` |
| Thumb | `20.dp` white (`onPrimary` semantics); offset `2.dp → 22.dp` via `animateDpAsState spring(0.7, 500)` |
| Haptic | `HapticFeedbackType.LongPress` on toggle |

### 9.5 `LucideMenuIcon`

Canvas-drawn 3-line menu icon (`Icons`-free), tint param — the glass drawer
button.

---

## 10. Tabs, segments, filters, badges

### 10.1 `RequestrSegmentedTabs` (Lane A — `CommonComponents.kt`)

| Token | Value |
|---|---|
| Container radius | `RoundedCornerShape(26.dp)` |
| Container bg | `surfaceVariant@0.45` |
| Container border | `0.8.dp outline@0.2` |
| Container padding | `h4 v4` (params) |
| Item spacing | `4.dp` |
| Item shape | `animateDpAsState(24.dp / 10.dp, tween(200))` |
| Item bg | `animateColorAsState(primary / Transparent, tween(200))` |
| Item fg | `animateColorAsState(onPrimary / onSurfaceVariant, tween(200))` |
| Item border | selected `1.dp primary@0.5`, else `0.dp` |
| Item padding | `h12 v7` |
| Icon | `15.dp`, spacer 5dp |
| Label | `12.sp`, selected Bold / unselected Medium, max 1 line |
| Haptic | `HapticFeedbackType.TextHandleMove` on select |
| Equal weight | optional `equalWeight` param |

### 10.2 `RequestrFilterChip` (Lane A — `CommonComponents.kt`)

| Token | Value |
|---|---|
| Shape | selected `RoundedCornerShape(24.dp)` / unselected `10.dp` (instant) |
| Bg | selected `primary` / unselected `surfaceVariant@0.5` |
| Fg | selected `onPrimary` / unselected `onSurfaceVariant` |
| Border | `0.8.dp`; selected `primary` / unselected `outline@0.25` |
| Padding | `h12 v6` |
| Icon | `14.dp`, spacer 4dp |
| Label | `11.sp`, selected Bold / unselected Medium |
| Count badge | circle pill; selected `onPrimary@0.25` / unselected `primary@0.15`; text `9.sp` Bold |
| Haptic | `HapticFeedbackType.TextHandleMove` |

### 10.3 Badges (Lane A — `CommonComponents.kt`)

| Badge | Tokens |
|---|---|
| `BadgePill` (shared, frozen this PR) | `Surface`, radius `6.dp`; bg `Color(color).copy(alpha=0.15f)`; padding `h6 v2`; `10.sp` `FontWeight.Bold`; fg `Color(color)`; optional `modifier`. Canonical small status pill for all lanes. |
| `MethodBadge` | radius `6.dp`; bg `methodColor@0.16`; border `1.dp @0.5`; padding `h7 v2`; `11.sp` Monospace |
| `StatusCodeBadge` | radius `6.dp`; bg `codeColor@0.15`; border `1.dp @0.5`; `11.sp` Monospace |
| `ProtocolBadge` | radius `4.dp`; bg `surfaceVariant`; `10.sp` Monospace |
| Drawer LIVE badge | `5.dp` dot `InterceptorActiveGreen 0xFF22C55E`; pill radius `12.dp`, bg `0xFF22C55E@0.18`, border `0.8.dp @0.4`; text `9.sp` |

**Do not create duplicate private badge composables in lanes.** `BadgePill` is
the single shared pill; lane-specific badges consume it or the variants above.

### 10.4 Drawer theme & accent switcher (Lane A — `AppNavigationDrawer.kt`)

| Token | Value |
|---|---|
| Item shape | selected `RoundedCornerShape(24.dp)` / unselected `12.dp` |
| Selected container | `NavigationDrawerItemDefaults.colors(selectedContainerColor = primaryContainer@0.65)` |
| Light/Dark toggle | radius `20.dp`; segment shapes `18.dp`/`8.dp`; padding `10/4`; `11.sp` |
| Accent chips | dot `8.dp` + `displayName` `11.sp`; shape `20.dp`/`10.dp`; padding `h10 v6`; border `1.dp`/`0.5.dp` |
| Drawer labels | Home, Interceptr, API Testr, Tools, Notes, Flow, Reqy Assistant, Music |
| Reject | main's hardcoded `gesturesEnabled = false` — keep backup `settings.gestureNavigationEnabled` |

---

## 11. Press interactions & haptic matrix

| Component | Press visual | Haptic |
|---|---|---|
| Nav pill tab | scale 1.04 + bg/fg spring colors | LongPress on navigate |
| GlassActionPill | scale 0.90 spring(0.6, 600) | LongPress on tap |
| DynamicGlassSwitch | thumb spring(0.7, 500) | LongPress on toggle |
| RequestrSegmentedTabs | bg/fg/shape tween(200) | TextHandleMove |
| RequestrFilterChip | instant colors | TextHandleMove |
| DynamicIconPress | scale 1.08 `spring(MediumBouncy, Medium)`; border 0.5; bg 0.12; radius `6→12.dp`; padding `0→2.dp` | component-defined |

All haptics go through `rememberAppHapticFeedback()` (gated once behind
`hapticFeedbackEnabled`).

---

## 12. Screen-family tokens

### 12.1 Home (Lane A — `HomeScreen.kt`)

| Component | Tokens |
|---|---|
| `HomeQuickActionPill` | capsule `RoundedCornerShape(20.dp)`, bg `surfaceVariant@0.7`, border `1.dp outline@0.35`, icon `16.dp`, label `12.5.sp` |
| `PulsingInterceptionIndicator` | `14.dp` dot; `scale 0.85→1.25` `tween(900, FastOutSlowInEasing)` + reverse; active green `0xFF10B981`, else `TextMuted`. **Gated.** |
| `LiveWaveformGraphic` | 3 bars, durations `600/450/700.ms`, width `2.5.dp`, green `0xFF10B981`. Gated. |
| `PowerModuleCard` | icon tile `36.dp` radius `10.dp`; header gradient `accent@0.16 → surface@0.95`; badge chip radius `6.dp`; chips `9.sp` |
| `RecentItemCard` | GlassCard row; icon tile `36.dp` radius `10.dp`; timestamp chip |

Keep backup `MainSectionCard`/`QuickActionChip`/search row as base.

### 12.2 Music (Lane C — `MusicScreen.kt`, `TrackArtworkView.kt`)

| Component | Tokens |
|---|---|
| `LockedBigPlayerCard` | artwork `80.dp` radius `18.dp`; vinyl spinner `tween(12000, LinearEasing)` (gated); wave `800.ms`; pulse `0.98→1.05` `1000.ms` (gated); source badge radius `6.dp` `primary@0.14`; "PLAYING" badge `0xFF10B981@0.18`; waveform Canvas `32 bars`, height `28.dp` |
| `MiniLiveEqualizer` | bars `400/550/350.ms` (gated) |
| `TrackArtworkView` (new) | default `48.dp` radius `12.dp`; Coil `SubcomposeAsyncImage` `crossfade(true)`, `ContentScale.Crop`; fallback `linearGradient(albumCoverHex → primary@0.85 → tertiary@0.7)`; icon `MusicNote` `White@0.92`, `size*0.45`; border `1.dp outline@0.3`; uri order `albumArtUri → contentUri → filePath` |
| Keep | backup `MusicSourceType.LOCAL`-only semantics; do **not** port `SYNTH_LOFI/AMBIENT_CYBER/CHILL_CODING` (no producers) |

### 12.3 Flow (Lane C — `FlowScreen.kt`)

| Component | Tokens |
|---|---|
| `PipelinePillItem` | capsule `RoundedCornerShape(20.dp)`, `animateColorAsState primary` (active) / `surfaceVariant`; name `12.sp` Bold (active) / Medium; count `"(n)"` `10.sp` alpha `0.8` |
| Multi-flow UI | `FlowPipelineItem` Room-persisted (new table/DAO + migration v10→v11), not in-memory seeds |
| Keep | backup `updateNode`, `EditFlowNodeBottomSheet`, CONDITION→true/false port derivation, `replaceGraph` import, `FlowRunSummary`, `FlowExecutor` branching |

### 12.4 API Testr (Lane B — `ApiTestrScreen.kt`, `ApiTestrRepository.kt`)

| Component | Tokens |
|---|---|
| `SendFloatingBar` | bg `verticalGradient(alpha 0.0 → 0.85 → 1.0)`; padding `16/8`; extra `fabBottomSpacingDp` bottom; Surface `height 46.dp`, `RoundedCornerShape(16.dp)`, `shadowElevation 8.dp`; states: Send / Sending (spinner) / Connect WS / Disconnect WS (`0xFFEF4444`); haptic LongPress |
| Snackbar offset | `fabBottomSpacingDp + 54.dp` (spacer `+64.dp` above bar) |
| `TitleAndUrlCard` | rocket tilt `-18°` `spring(MediumBouncy, Low)`, scale `1.15` `spring(MediumBouncy, Medium)` on follow-redirect; bg `tween(200)`; method menu arrow `180°` `spring(MediumBouncy, Low)`; title `20.sp` ExtraBold; dashed/solid underline `2.dp`; radius `18.dp` |
| Tab sets | `AuthSettingsTab`, `HttpRequestBodyTab`, `ScriptsTabContent`, `WsConsoleTabContent`/`WsStreamComposerTab` + `LiveJsonAndRawBlock` |
| WS client | `WsMessage`, `WsConnectionStatus`, `WebSocketSession` (OkHttp) — new to backup `ApiTestrRepository` |
| Keep | backup request execution, Room-backed saved APIs, chain extraction, exports via MediaStore |

### 12.5 Tools (Lane B — `ToolsScreen.kt`)

| Component | Tokens |
|---|---|
| `RedesignedToolCard` | radius `16.dp`; icon tile `24.dp`; title `13.5.sp`; subtitle `10.5.sp` |
| `ToolCategorySelector` | capsule `20.dp` chips, `12.sp` labels |
| Keep | backup `CustomToolWorkspace` + Room-backed custom tools (`CustomToolDao`) |

### 12.6 Onboarding (Lane A — `OnboardingScreen.kt`)

| Component | Tokens |
|---|---|
| `DynamicIslandProgressPill` | radius `22.dp`; shadow `12.dp` spot `Black@0.3`; bg `0xFF0F172A@0.92`; border `0.8.dp accent@0.45`; pulse dot `8.dp`, `tween(900, FastOutSlowInEasing)` alpha `0.4→1.0` (gated); text `"Setup Phase 0$currentStep"` `12.sp`; outer step pill `accent.bright@0.6` border `1.2.dp` |
| Steps | Identity / Theme / Diagnostics; accent picker consumes backup `AppAccent` axis |
| Keep | backup permission rows and appearance-step semantics |

### 12.7 Settings — `NavigationAndHeaderModifiersCard` (Lane A — `SettingsScreen.kt`)

| Control | Range / steps | Key written |
|---|---|---|
| Header Top Offset | `0f..40f`, `steps = 7` | `HEADER_TOP_PADDING` |
| Header Glass Opacity | `0.1f..1.0f` | `HEADER_OPACITY` |
| Nav Pill Opacity | `0.3f..1.0f` | `NAV_BAR_OPACITY` |
| FAB bottom spacing | `48f..140f`, `steps = 11` | `FAB_BOTTOM_SPACING` |
| Header blur style | `FROSTED_GLASS` / `SOLID_M3` / `OLED_BLACK` / `TRANSLUCENT` | `HEADER_BLUR_STYLE` |
| Nav blur style | `FROSTED_GLASS` / `SOLID_M3` / `OLED_BLACK` / `TRANSLUCENT` | `NAV_BAR_BLUR_EFFECT` |

`LiveRoundnessSettingCard`: live corner-radius preview slider.

---

## 13. Canonical glass-style keys

Single set of **canonical component keys** used by all glass components:
`FROSTED_GLASS` (default), `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`.

Settings writes canonical keys; display labels stay user-friendly
(`FROSTED_GLASS` → "Frosted Glass", `SOLID_M3` → "Material 3",
`OLED_BLACK` → "OLED Black", `TRANSLUCENT` → "Translucent").

`canonicalGlassStyle(raw)` in `UnifiedDataStore.kt` migrates legacy stored values:
- Header: `FROSTED→FROSTED_GLASS`, `M3_MINIMAL→SOLID_M3`, `CLEAR_GLASS→TRANSLUCENT`, `DEEP_DARK→OLED_BLACK`
- Nav: `FROSTED→FROSTED_GLASS`, `M3→SOLID_M3`, `OLED→OLED_BLACK`, `TRANSLUCENT→TRANSLUCENT`
- Unknown/blank → `FROSTED_GLASS`

Covered by `GlassStyleKeysTest` (every stored value maps to a supported brush
branch — no silent `else` fallback for a known key).

---

## 14. Accessibility & cross-config coverage matrix

Every token/component above must satisfy these axes. Lane implementers verify
each in lane tests (task 11–14) and the judge re-checks (task 9).

| Axis | Requirement | Owner verification |
|---|---|---|
| **14.1 Light / dark** | All colors resolve through `MaterialTheme.colorScheme` + universal palettes (§6). No hardcoded light-only/dark-only values in shared components. Accent containers (INDIGO) differ per mode. | Lane A previews/screenshot states light+dark; `AccentThemeTest` |
| **14.2 Dynamic accents** | All accent-driven fills/tints read `MaterialTheme.colorScheme.primary`/`primaryContainer` derived from `AppAccent`; `0xFF…` status literals (green/red/amber) are semantic and accent-independent. | Lane A `AccentThemeTest` + accent picker round-trip |
| **14.3 RTL** | Layouts use `Arrangement.spacedBy`/`weight`/`paddingStart`-safe APIs; avoid absolute `Start` assumptions where list order flips. Glass header trailing capsules use end-anchored arrangement. Badge dots use `TopEnd`. | Lane tests with RTL locale config |
| **14.4 Font scaling** | No fixed-height containers around labels. Pill/tab/card text uses `sp` and wraps/ellipsizes (`maxLines`, `TextOverflow.Ellipsis`). `BadgePill`/badges must not clip at large font scales. | Lane tests at 1.3/2.0 font scale |
| **14.5 TalkBack** | Icon-only pills (`GlassActionPill`, nav tabs, quick actions) carry `contentDescription`. Switches carry label + `stateDescription`. Haptics are never the only feedback. `BadgePill` is read as part of its parent row's semantics, not separately announced when decorative. | Lane accessibility tests |
| **14.6 Reduced motion** | Infinite animations (pulse/vinyl/waveform/equalizer) gated behind `LocalAnimationsEnabled`; springs/tweens via `scaledSpring`/`scaledTween`. NONE = instant; REDUCED = 0.4× faster springs. | Lane animation-gating tests |
| **14.7 Blur fallback** | Glass is emulated (gradient translucency + hairline border + shadow); **no `RenderEffect`/`Modifier.blur`**. Components degrade gracefully on all API levels. Do not claim real blur in docs. | Lane screenshot states; documented in §9.1 |

---

## 15. State vocabulary

Shared component states lanes must implement for empty/loading/error/offline/
permission surfaces (consistent tokens, lane-owned content):

| State | Pattern | Tokens |
|---|---|---|
| **Empty** | centered icon + message + optional CTA | icon `48.dp` `onSurfaceVariant@0.4`; message `14.sp` `onSurfaceVariant`; CTA pill (§9.2) |
| **Loading** | gated spinner or skeleton | spinner `scaledTween`/infinite gated; skeleton `surfaceVariant@0.5` blocks |
| **Error** | `BadgePill`/status color + message + retry | error red `0xFFEF4444`; retry `GlassActionPill` |
| **Offline** | banner + disabled action surfaces | amber `0xFFF59E0B`; actions `onSurfaceVariant@0.4` |
| **Permission** | rationale + system-prompt button | backup permission rows (Onboarding/Music); button `primary` |

---

## 16. Caveats & known limitations

- **Nav-pill hit target ≈ 35dp** (below 48dp recommendation) — documented;
  finalize at judgment (task 9).
- **Glass is emulated** — gradient translucency + hairline border + shadow; no
  real blur. Do not claim `RenderEffect` blur.
- **Infinite animations are gated** — hold static frames when
  `LocalAnimationsEnabled == false`; reduced motion scales via
  `scaledSpring`/`scaledTween`; NONE is instant.
- **Settings keys vs component keys** are canonicalized via `canonicalGlassStyle`
  (§13) before release.
- **Functional regressions never ported**: raw-file exports (MediaStore instead),
  in-memory flow pipelines (Room instead), fabricated music sources/note seeds
  (keep backup honest-data semantics), `TRAFFIC_LOG` (not re-added), destructive
  Room migration (backup v10 + `MIGRATION_8_9` wins), removed `file_paths.xml`
  downloads root (restored).
- **Frozen files**: `AppRepositories.kt`, `AppModels.kt`, `UnifiedDataStore.kt`
  are owned by the integration/data owner and frozen to all lanes after this PR.
  Shared changes discovered later become a small prerequisite PR merged before
  affected lane work resumes.

---

## 17. Gate checklist (plan v3 task 7)

- [x] Implementation-ready tokens recorded for nav pills, glass headers,
      tabs/segments, cards/sheets, status pills, typography, spacing, radii,
      icons, states, and animation/reduced-motion rules (§5–§15).
- [x] Shared `BadgePill` extracted to `CommonComponents.kt`; duplicate private
      version removed. `BadgePill` + `CommonComponents.kt` owned by Lane A after
      merge.
- [x] Ownership of `AppRepositories.kt`, `AppModels.kt`, `UnifiedDataStore.kt`
      resolved: one integration/data owner; all required interface changes
      present (§3); files frozen to all lanes (§2, §16).
- [x] Every token/interface mapped to owner and target Compose/data files (§2,
      §8–§12).
- [x] Light/dark, dynamic accents, RTL, font scaling, TalkBack, reduced motion,
      and blur fallback covered (§14).
- [x] Compilation + focused state tests pass before merge.
