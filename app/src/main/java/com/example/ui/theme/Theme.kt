package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(val displayName: String, val isLight: Boolean, val samplePrimary: Color, val sampleBg: Color) {
    EDITORIAL_LIGHT("Editorial Light", true, EditorialLightAccent, EditorialLightBackground),
    MONOCHROME_DARK("Obsidian Dark", false, MonochromeDarkAccent, MonochromeDarkBackground),
    MIDNIGHT_SAPPHIRE("Midnight Sapphire", false, MidnightPrimary, MidnightBackground),
    KYOTO_MATCHA("Kyoto Matcha", true, KyotoMatchaPrimary, KyotoMatchaBackground),
    CYBER_TERMINAL("Cyber Terminal", false, CyberTerminalPrimary, CyberTerminalBackground),
    DUSK_TERRACOTTA("Dusk Terracotta", false, DuskTerracottaPrimary, DuskTerracottaBackground),
    SOLAR_AMBER("Solar Amber", false, SolarAmberPrimary, SolarAmberBackground),
    // Phase B delta spec (e) — two new families. Pastel Light is a warm paper canvas with
    // taupe ink; Deep Olive Dark is a near-black olive canvas with a moss primary.
    PASTEL_LIGHT("Pastel Light", true, PastelLightAccent, PastelLightBackground),
    DEEP_OLIVE_DARK("Deep Olive Dark", false, DeepOliveDarkAccent, DeepOliveDarkBackground);

    // v6.0 — compatibility bridge to the new orthogonal accent axis (AppAccent below). Lets
    // a future caller derive a sensible AppAccent from a legacy AppThemeMode identity with
    // no change to this enum's existing entries/fields/behavior — every current call site
    // (values(), .name, .isLight, .samplePrimary, .sampleBg, MyApplicationTheme(themeMode))
    // keeps compiling and rendering exactly as before.
    val accent: AppAccent get() = AppAccent.fromKey(name)

    companion object {
        /**
         * Single source of truth for resolving a persisted theme key (`themeMode`,
         * `selectedLightTheme`, `selectedDarkTheme`) to an [AppThemeMode]. Accepts the enum
         * name (any case) plus the short legacy aliases that older builds persisted
         * ("LIGHT", "OBSIDIAN", "MIDNIGHT", "MATCHA", "TERMINAL", "DUSK", "AMBER"). Returns
         * null for unknown keys so callers pick their own fallback instead of silently
         * rendering the wrong family (a stored light key must never resolve to a dark theme).
         */
        fun fromKey(key: String?): AppThemeMode? {
            if (key.isNullOrBlank()) return null
            val k = key.trim().uppercase()
            values().firstOrNull { it.name == k }?.let { return it }
            return when (k) {
                "LIGHT", "EDITORIAL" -> EDITORIAL_LIGHT
                "OBSIDIAN", "OBSIDIAN_DARK", "MONOCHROME" -> MONOCHROME_DARK
                "MIDNIGHT", "SAPPHIRE" -> MIDNIGHT_SAPPHIRE
                "MATCHA", "KYOTO" -> KYOTO_MATCHA
                "TERMINAL", "CYBER" -> CYBER_TERMINAL
                "DUSK", "TERRACOTTA" -> DUSK_TERRACOTTA
                "AMBER", "SOLAR" -> SOLAR_AMBER
                "PASTEL", "PASTEL_LIGHT_THEME" -> PASTEL_LIGHT
                "OLIVE", "DEEP_OLIVE", "OLIVE_DARK" -> DEEP_OLIVE_DARK
                else -> null
            }
        }

        /** Light families, in picker order. */
        fun lightThemes(): List<AppThemeMode> = values().filter { it.isLight }

        /** Dark families, in picker order. */
        fun darkThemes(): List<AppThemeMode> = values().filter { !it.isLight }
    }
}

/**
 * v6.0 — 10 Rich Dynamic Accents for Android 17 / Pixel-style dynamic coloring, ported
 * verbatim from docs/legacy-main/theme-10accent/Theme.kt.txt (main's orthogonal accent-axis
 * theme model). This is an independent axis from [AppThemeMode] / the app's light-dark
 * appearance mode: picking an accent never selects a light/dark family and never touches
 * selectedLightTheme/selectedDarkTheme. Wiring MainActivity/Settings/Onboarding/
 * AppNavigationDrawer to actually read/write this axis is a separate change — this enum plus
 * [buildLightColorScheme]/[buildDarkColorScheme] below are the engine those call sites will
 * bind to.
 */
enum class AppAccent(
    val id: String,
    val displayName: String,
    val primary: Color,
    val bright: Color,
    val containerLight: Color,
    val containerDark: Color
) {
    INDIGO("INDIGO", "Electric Indigo", AccentIndigo, AccentIndigoBright, AccentIndigoContainerLight, AccentIndigoContainerDark),
    CYAN("CYAN", "Dynamic Cyan", AccentCyan, AccentCyanBright, AccentCyanContainerLight, AccentCyanContainerDark),
    EMERALD("EMERALD", "Emerald Mint", AccentEmerald, AccentEmeraldBright, AccentEmeraldContainerLight, AccentEmeraldContainerDark),
    ORANGE("ORANGE", "Sunset Orange", AccentOrange, AccentOrangeBright, AccentOrangeContainerLight, AccentOrangeContainerDark),
    LIME("LIME", "Dynamic Lime", AccentLime, AccentLimeBright, AccentLimeContainerLight, AccentLimeContainerDark),
    VIOLET("VIOLET", "Royal Violet", AccentViolet, AccentVioletBright, AccentVioletContainerLight, AccentVioletContainerDark),
    ROSE("ROSE", "Crimson Rose", AccentRose, AccentRoseBright, AccentRoseContainerLight, AccentRoseContainerDark),
    AMBER("AMBER", "Solar Amber", AccentAmber, AccentAmberBright, AccentAmberContainerLight, AccentAmberContainerDark),
    SAPPHIRE("SAPPHIRE", "Deep Sapphire", AccentSapphire, AccentSapphireBright, AccentSapphireContainerLight, AccentSapphireContainerDark),
    OBSIDIAN("OBSIDIAN", "Slate Obsidian", AccentObsidian, AccentObsidianBright, AccentObsidianContainerLight, AccentObsidianContainerDark);

    companion object {
        fun fromKey(key: String): AppAccent {
            return values().find { it.name.equals(key, ignoreCase = true) || it.id.equals(key, ignoreCase = true) }
                ?: when (key.uppercase()) {
                    "EDITORIAL_LIGHT" -> ORANGE
                    "MONOCHROME_DARK" -> OBSIDIAN
                    "MIDNIGHT_SAPPHIRE" -> SAPPHIRE
                    "KYOTO_MATCHA" -> EMERALD
                    "CYBER_TERMINAL" -> LIME
                    "DYNAMIC_LIME" -> LIME
                    "DUSK_TERRACOTTA" -> ORANGE
                    "SOLAR_AMBER" -> AMBER
                    "PASTEL_LIGHT" -> OBSIDIAN
                    "DEEP_OLIVE_DARK" -> LIME
                    else -> INDIGO
                }
        }
    }
}

// Fix 03 — Editorial Light (primary → warm orange, was near-black causing
// black-on-black text). Old primary moved to secondary for dark-semantic uses.
private val EditorialLightColorScheme = lightColorScheme(
    primary = EditorialLightPrimary,            // 0xFFF28C28 warm orange
    onPrimary = Color(0xFF111111),             // dark text on orange
    primaryContainer = Color(0xFFFFF1E6),      // lighter warm surface
    onPrimaryContainer = Color(0xFFB35A0D),    // dark orange text
    secondary = Color(0xFF111111),             // old primary as secondary
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDE8D3),    // warm cream container
    onSecondaryContainer = EditorialLightAccent,
    tertiary = Color(0xFF2B2D42),
    background = EditorialLightBackground,
    onBackground = Color(0xFF111111),
    surface = EditorialLightSurface,
    onSurface = Color(0xFF111111),
    surfaceVariant = EditorialLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF555555),
    outline = EditorialLightBorder,
    outlineVariant = Color(0xFFDAD6CA)
)

// 2. Monochrome Obsidian Dark
private val MonochromeDarkColorScheme = darkColorScheme(
    primary = MonochromeDarkPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF282830),
    onPrimaryContainer = MonochromeDarkAccent,
    secondary = MonochromeDarkAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF382312),
    onSecondaryContainer = Color(0xFFFFB26B),
    tertiary = Color(0xFF94A3B8),
    background = MonochromeDarkBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = MonochromeDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = MonochromeDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA0A0A8),
    outline = MonochromeDarkBorder,
    outlineVariant = Color(0xFF33333D)
)

// 3. Midnight Sapphire
private val MidnightSapphireColorScheme = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = MidnightPrimaryBright,
    secondary = MidnightAccentCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFA5B4FC),
    tertiary = Color(0xFF38BDF8),
    background = MidnightBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = MidnightSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = MidnightBorder,
    outlineVariant = Color(0xFF2E4163)
)

// 4. Kyoto Matcha
private val KyotoMatchaColorScheme = lightColorScheme(
    primary = KyotoMatchaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EEDD),
    onPrimaryContainer = KyotoMatchaPrimaryBright,
    secondary = KyotoMatchaAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFEDF4E8),
    onSecondaryContainer = Color(0xFF1E3F19),
    tertiary = Color(0xFF4A7C59),
    background = KyotoMatchaBackground,
    onBackground = Color(0xFF1A2619),
    surface = KyotoMatchaSurface,
    onSurface = Color(0xFF1A2619),
    surfaceVariant = KyotoMatchaSurfaceVariant,
    onSurfaceVariant = Color(0xFF4C5D4B),
    outline = KyotoMatchaBorder,
    outlineVariant = Color(0xFFBAC6B3)
)

// 5. Cyber Terminal
private val CyberTerminalColorScheme = darkColorScheme(
    primary = CyberTerminalPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0D3320),
    onPrimaryContainer = CyberTerminalPrimaryBright,
    secondary = CyberTerminalAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF003B46),
    onSecondaryContainer = Color(0xFF70EEFF),
    tertiary = Color(0xFF00FF88),
    background = CyberTerminalBackground,
    onBackground = Color(0xFFE0FFF0),
    surface = CyberTerminalSurface,
    onSurface = Color(0xFFE0FFF0),
    surfaceVariant = CyberTerminalSurfaceVariant,
    onSurfaceVariant = Color(0xFF70A88B),
    outline = CyberTerminalBorder,
    outlineVariant = Color(0xFF264837)
)

// 6. Dusk Terracotta
private val DuskTerracottaColorScheme = darkColorScheme(
    primary = DuskTerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF522318),
    onPrimaryContainer = DuskTerracottaPrimaryBright,
    secondary = DuskTerracottaAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF421D15),
    onSecondaryContainer = Color(0xFFFFCBB8),
    tertiary = Color(0xFFE76F51),
    background = DuskTerracottaBackground,
    onBackground = Color(0xFFFDECE8),
    surface = DuskTerracottaSurface,
    onSurface = Color(0xFFFDECE8),
    surfaceVariant = DuskTerracottaSurfaceVariant,
    onSurfaceVariant = Color(0xFFA8898F),
    outline = DuskTerracottaBorder,
    outlineVariant = Color(0xFF593F4A)
)

// 7. Solar Amber
private val SolarAmberColorScheme = darkColorScheme(
    primary = SolarAmberPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF543800),
    onPrimaryContainer = SolarAmberPrimaryBright,
    secondary = SolarAmberAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A1A0C),
    onSecondaryContainer = Color(0xFFFFB59D),
    tertiary = Color(0xFFFFB300),
    background = SolarAmberBackground,
    onBackground = Color(0xFFFFF8E7),
    surface = SolarAmberSurface,
    onSurface = Color(0xFFFFF8E7),
    surfaceVariant = SolarAmberSurfaceVariant,
    onSurfaceVariant = Color(0xFFA69680),
    outline = SolarAmberBorder,
    outlineVariant = Color(0xFF4D3F2E)
)

// Phase B delta spec (e) §8 — Pastel Light. Every role is set deliberately; the canvas is
// warm paper (#FAFBF7) and ink is warm charcoal (#3A3530, 11.6:1 on the canvas). The taupe
// primary (#9B8B7E) is a mid-tone, so onPrimary is deep warm ink (#231F1C, ~5:1) rather than
// white (which would only reach ~3.3:1).
private val PastelLightColorScheme = lightColorScheme(
    primary = PastelLightPrimary,
    onPrimary = PastelLightInkDeep,
    primaryContainer = PastelLightPrimaryContainer,
    onPrimaryContainer = PastelLightOnPrimaryContainer,
    inversePrimary = PastelLightPrimaryBright,
    secondary = PastelLightSecondary,
    onSecondary = PastelLightInkDeep,
    secondaryContainer = PastelLightSecondaryContainer,
    onSecondaryContainer = PastelLightOnSecondaryContainer,
    tertiary = PastelLightTertiary,
    onTertiary = PastelLightInkDeep,
    tertiaryContainer = PastelLightTertiaryContainer,
    onTertiaryContainer = PastelLightOnTertiaryContainer,
    background = PastelLightBackground,
    onBackground = PastelLightInk,
    surface = PastelLightSurface,
    onSurface = PastelLightInk,
    surfaceVariant = PastelLightSurfaceVariant,
    onSurfaceVariant = PastelLightInkMuted,
    surfaceTint = PastelLightPrimary,
    inverseSurface = Color(0xFF2E2A26),
    inverseOnSurface = PastelLightSurfaceVariant,
    error = PastelLightError,
    onError = Color.White,
    errorContainer = PastelLightErrorContainer,
    onErrorContainer = PastelLightOnErrorContainer,
    outline = PastelLightBorder,
    outlineVariant = PastelLightBorderStrong,
    scrim = Color.Black,
    surfaceBright = PastelLightSurface,
    surfaceDim = Color(0xFFE3E0DA),
    surfaceContainer = Color(0xFFF2EFEA),
    surfaceContainerHigh = Color(0xFFECE8E2),
    surfaceContainerHighest = Color(0xFFE6E1DA),
    surfaceContainerLow = Color(0xFFF7F5F1),
    surfaceContainerLowest = Color.White
)

// Phase B delta spec (e) §9 — Deep Olive Dark. Canvas is near-black olive (#0D0F09), text is
// warm off-white (#F0EDE9). The moss primary (#8FA880) is a mid-tone too, so onPrimary is the
// canvas ink (#0D0F09, ~7.4:1) — white would only reach ~2.6:1 and fail body-text contrast.
private val DeepOliveDarkColorScheme = darkColorScheme(
    primary = DeepOliveDarkPrimary,
    onPrimary = DeepOliveDarkBackground,
    primaryContainer = DeepOliveDarkPrimaryContainer,
    onPrimaryContainer = DeepOliveDarkPrimaryBright,
    inversePrimary = Color(0xFF5B7350),
    secondary = DeepOliveDarkSecondary,
    onSecondary = DeepOliveDarkBackground,
    secondaryContainer = DeepOliveDarkSecondaryContainer,
    onSecondaryContainer = DeepOliveDarkOnSecondaryContainer,
    tertiary = DeepOliveDarkTertiary,
    onTertiary = DeepOliveDarkBackground,
    tertiaryContainer = DeepOliveDarkTertiaryContainer,
    onTertiaryContainer = DeepOliveDarkOnTertiaryContainer,
    background = DeepOliveDarkBackground,
    onBackground = DeepOliveDarkText,
    surface = DeepOliveDarkSurface,
    onSurface = DeepOliveDarkText,
    surfaceVariant = DeepOliveDarkSurfaceVariant,
    onSurfaceVariant = DeepOliveDarkTextMuted,
    surfaceTint = DeepOliveDarkPrimaryBright,
    inverseSurface = DeepOliveDarkText,
    inverseOnSurface = DeepOliveDarkSurfaceVariant,
    error = DeepOliveDarkError,
    onError = DeepOliveDarkOnError,
    errorContainer = DeepOliveDarkErrorContainer,
    onErrorContainer = DeepOliveDarkOnErrorContainer,
    outline = DeepOliveDarkBorder,
    outlineVariant = DeepOliveDarkBorderStrong,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C302B),
    surfaceDim = DeepOliveDarkBackground,
    surfaceContainer = DeepOliveDarkSurface,
    surfaceContainerHigh = DeepOliveDarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF262B27),
    surfaceContainerLow = Color(0xFF121410),
    surfaceContainerLowest = Color(0xFF080A06)
)

/**
 * The fixed canvas [ColorScheme] for a theme family — what the family looks like with its
 * own native primary, before any user accent is applied. Public so Settings can render
 * honest theme swatches and so tests can assert every role's contrast.
 */
fun themeColorScheme(themeMode: AppThemeMode): ColorScheme = when (themeMode) {
    AppThemeMode.EDITORIAL_LIGHT -> EditorialLightColorScheme
    AppThemeMode.MONOCHROME_DARK -> MonochromeDarkColorScheme
    AppThemeMode.MIDNIGHT_SAPPHIRE -> MidnightSapphireColorScheme
    AppThemeMode.KYOTO_MATCHA -> KyotoMatchaColorScheme
    AppThemeMode.CYBER_TERMINAL -> CyberTerminalColorScheme
    AppThemeMode.DUSK_TERRACOTTA -> DuskTerracottaColorScheme
    AppThemeMode.SOLAR_AMBER -> SolarAmberColorScheme
    AppThemeMode.PASTEL_LIGHT -> PastelLightColorScheme
    AppThemeMode.DEEP_OLIVE_DARK -> DeepOliveDarkColorScheme
}

/**
 * Theme switching is intentionally instantaneous: this is a plain `MaterialTheme(...)` call
 * with no Crossfade / animateColorAsState around the scheme, so a new [themeMode] recomposes
 * every colorScheme reader in a single frame with no intermediate blended colors.
 */
@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.EDITORIAL_LIGHT,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = themeColorScheme(themeMode),
        typography = Typography,
        content = content
    )
}

// ==========================================
// v6.0 — orthogonal accent-axis engine (10-accent theme port)
// ==========================================
// Ported verbatim from docs/legacy-main/theme-10accent/Theme.kt.txt. These build a
// ColorScheme dynamically from an [AppAccent] on the Universal light/dark canvas (Color.kt),
// independent of the 7 fixed AppThemeMode ColorSchemes above. Nothing below this point
// changes the existing AppThemeMode-keyed `when` or its 7 private ColorSchemes; both engines
// coexist so existing callers keep compiling and rendering unchanged.

/**
 * Builds a dynamic Light [ColorScheme] where the canvas is the clean off-white Universal
 * light base and primary is the chosen [accent].
 */
fun buildLightColorScheme(accent: AppAccent): ColorScheme {
    return lightColorScheme(
        primary = accent.primary,
        onPrimary = Color.White,
        primaryContainer = accent.containerLight,
        onPrimaryContainer = accent.primary,
        secondary = accent.bright,
        onSecondary = Color.White,
        secondaryContainer = accent.containerLight,
        onSecondaryContainer = accent.primary,
        tertiary = accent.primary,
        onTertiary = Color.White,
        background = UniversalLightBackground,
        onBackground = UniversalLightTextPrimary,
        surface = UniversalLightSurface,
        onSurface = UniversalLightTextPrimary,
        surfaceVariant = UniversalLightSurfaceVariant,
        onSurfaceVariant = UniversalLightTextSecondary,
        outline = UniversalLightBorder,
        outlineVariant = UniversalLightBorderVariant
    )
}

/**
 * Builds a dynamic Dark [ColorScheme] where the canvas is the deep ambient Universal
 * obsidian-dark base and primary is the chosen [accent].
 */
fun buildDarkColorScheme(accent: AppAccent): ColorScheme {
    return darkColorScheme(
        primary = accent.bright,
        onPrimary = Color(0xFF090D16),
        primaryContainer = accent.containerDark,
        onPrimaryContainer = accent.bright,
        secondary = accent.primary,
        onSecondary = Color.White,
        secondaryContainer = accent.containerDark,
        onSecondaryContainer = accent.bright,
        tertiary = accent.bright,
        onTertiary = Color(0xFF090D16),
        background = UniversalDarkBackground,
        onBackground = UniversalDarkTextPrimary,
        surface = UniversalDarkSurface,
        onSurface = UniversalDarkTextPrimary,
        surfaceVariant = UniversalDarkSurfaceVariant,
        onSurfaceVariant = UniversalDarkTextSecondary,
        outline = UniversalDarkBorder,
        outlineVariant = UniversalDarkBorderVariant
    )
}

/**
 * v6.0 — orthogonal accent-axis overload on the Universal canvas. Only the light/dark bit of
 * the chosen theme family survives here; the family's own canvas colors are ignored. Prefer
 * the `(themeMode, accent)` overload below, which keeps the family canvas AND the accent.
 */
@Composable
fun MyApplicationTheme(
    isDarkTheme: Boolean,
    accent: AppAccent,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDarkTheme) buildDarkColorScheme(accent) else buildLightColorScheme(accent)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// ==========================================
// Phase B — composed engine: theme-family canvas × user accent
// ==========================================

/** WCAG relative luminance (sRGB) of an opaque color, 0f (black) .. 1f (white). */
fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** WCAG contrast ratio between two opaque colors, always >= 1f. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Picks whichever of [darkInk] / [lightInk] reads better on [background]. Used for the
 * on-primary / on-secondary / on-tertiary roles when a user accent (which may be as bright as
 * Dynamic Lime or as dark as Deep Sapphire) is laid over a theme family's canvas, so no
 * combination ever produces white-on-lime or ink-on-navy.
 */
fun readableInkOn(background: Color, darkInk: Color, lightInk: Color = Color.White): Color =
    if (contrastRatio(background, darkInk) >= contrastRatio(background, lightInk)) darkInk else lightInk

/**
 * Builds the [ColorScheme] the app should actually render for a (theme family, accent) pair:
 * the family's canvas roles (background / surface / surfaceVariant / outline / text / error /
 * inverse / surfaceContainer*) come from [themeColorScheme]; the accent replaces the
 * primary / secondary / tertiary roles, their containers, surfaceTint and inversePrimary.
 *
 * Light families take the accent's `primary`; dark families take its `bright` variant, with
 * the canvas background used as ink on it so the pairing stays coherent with the family.
 * Pure and allocation-cheap, so it is safe to call inside a `remember(themeMode, accent)`.
 */
fun buildColorScheme(themeMode: AppThemeMode, accent: AppAccent): ColorScheme {
    val base = themeColorScheme(themeMode)
    return if (themeMode.isLight) {
        val primary = accent.primary
        val ink = readableInkOn(primary, darkInk = base.onBackground)
        base.copy(
            primary = primary,
            onPrimary = ink,
            primaryContainer = accent.containerLight,
            onPrimaryContainer = accent.primary,
            inversePrimary = accent.bright,
            secondary = accent.bright,
            onSecondary = readableInkOn(accent.bright, darkInk = base.onBackground),
            secondaryContainer = accent.containerLight,
            onSecondaryContainer = accent.primary,
            tertiary = accent.primary,
            onTertiary = ink,
            tertiaryContainer = accent.containerLight,
            onTertiaryContainer = accent.primary,
            surfaceTint = primary
        )
    } else {
        val primary = accent.bright
        val ink = readableInkOn(primary, darkInk = base.background)
        base.copy(
            primary = primary,
            onPrimary = ink,
            primaryContainer = accent.containerDark,
            onPrimaryContainer = accent.bright,
            inversePrimary = accent.primary,
            secondary = accent.primary,
            onSecondary = readableInkOn(accent.primary, darkInk = base.background),
            secondaryContainer = accent.containerDark,
            onSecondaryContainer = accent.bright,
            tertiary = accent.bright,
            onTertiary = ink,
            tertiaryContainer = accent.containerDark,
            onTertiaryContainer = accent.bright,
            surfaceTint = primary
        )
    }
}

/**
 * Composed overload — theme family canvas × user accent. This is the overload MainActivity
 * should call (`MyApplicationTheme(themeMode = themeMode, accent = selectedAccent)`) so that
 * all nine families actually render instead of collapsing to the Universal light/dark canvas.
 * Still a bare `MaterialTheme(...)`: switching is instantaneous by construction.
 */
@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode,
    accent: AppAccent,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = buildColorScheme(themeMode, accent),
        typography = Typography,
        content = content
    )
}

// ==========================================
// Phase B — glass style catalogue (single source of truth for labels)
// ==========================================

/**
 * One selectable glass style. [key] is the canonical persisted value (the same set
 * `canonicalGlassStyle()` in UnifiedDataStore.kt normalises legacy keys to); [label] is the
 * only user-facing name for it — both the header picker and the nav-pill picker in Settings
 * read this list, so the two can never drift apart again ("Clear Tint" vs "Translucent").
 * [needsBlurApi] marks styles whose defining effect (real backdrop blur) only exists on
 * Android 12+ (API 31); below that they fall back to a static gradient.
 */
data class GlassStyleOption(
    val key: String,
    val label: String,
    val description: String,
    val needsBlurApi: Boolean,
)

object GlassStyles {
    const val FROSTED_GLASS = "FROSTED_GLASS"
    const val SOLID_M3 = "SOLID_M3"
    const val TRANSLUCENT = "TRANSLUCENT"
    const val OLED_BLACK = "OLED_BLACK"
    const val LIQUID_GLASS = "LIQUID_GLASS"
    const val TUNE_GLASS = "TUNE_GLASS"

    /** Minimum Android API level with a real `Modifier.blur` (RenderEffect). */
    const val REAL_BLUR_MIN_API = 31

    val options: List<GlassStyleOption> = listOf(
        GlassStyleOption(FROSTED_GLASS, "Frosted Glass", "Soft frosted surface over the content, the default look.", needsBlurApi = false),
        GlassStyleOption(SOLID_M3, "Material 3", "Opaque Material surface, no transparency.", needsBlurApi = false),
        GlassStyleOption(TRANSLUCENT, "Clear Glass", "Thin clear tint, content shows through with no frost.", needsBlurApi = false),
        GlassStyleOption(OLED_BLACK, "OLED Black", "Pure black surface for OLED panels.", needsBlurApi = false),
        GlassStyleOption(LIQUID_GLASS, "Liquid Glass", "Live backdrop blur with a specular highlight (Android 12+).", needsBlurApi = true),
        GlassStyleOption(TUNE_GLASS, "Tune Glass", "Your own blur, tint, border and shadow — adjust with the sliders below.", needsBlurApi = true),
    )

    /** Resolves a persisted key (canonical or legacy alias) to its option; unknown -> Frosted Glass. */
    fun option(key: String?): GlassStyleOption = options.first { it.key == canonicalKey(key) }

    /** User-facing label for a persisted key. */
    fun label(key: String?): String = option(key).label

    /**
     * Mirrors `canonicalGlassStyle()` in UnifiedDataStore.kt so Settings can compare a stored
     * legacy value ("CLEAR_GLASS", "M3", "OLED", ...) against a canonical chip key.
     */
    fun canonicalKey(key: String?): String = when (key?.trim()?.uppercase()) {
        "FROSTED_GLASS", "FROSTED" -> FROSTED_GLASS
        "SOLID_M3", "M3_MINIMAL", "M3" -> SOLID_M3
        "OLED_BLACK", "DEEP_DARK", "OLED" -> OLED_BLACK
        "TRANSLUCENT", "CLEAR_GLASS" -> TRANSLUCENT
        "LIQUID_GLASS", "LIQUID" -> LIQUID_GLASS
        "TUNE_GLASS", "TUNE" -> TUNE_GLASS
        else -> FROSTED_GLASS
    }

    /** True when this device can render the real blur the given style is defined by. */
    fun hasRealBlur(sdkInt: Int): Boolean = sdkInt >= REAL_BLUR_MIN_API
}

/**
 * Slider ranges for Tune Glass. These are the exact clamp ranges the repository setters
 * (`SettingsRepository.updateGlassTune*`) enforce, surfaced so the sliders never offer a
 * value that would be silently clamped away.
 */
object GlassTuneRanges {
    val blurDp: IntRange = 0..48
    val tintOpacity: ClosedFloatingPointRange<Float> = 0.15f..1f
    val borderOpacity: ClosedFloatingPointRange<Float> = 0f..1f
    val shadowDp: IntRange = 0..24
}
