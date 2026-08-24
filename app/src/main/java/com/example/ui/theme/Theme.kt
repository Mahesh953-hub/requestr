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
    SOLAR_AMBER("Solar Amber", false, SolarAmberPrimary, SolarAmberBackground);

    // v6.0 — compatibility bridge to the new orthogonal accent axis (AppAccent below). Lets
    // a future caller derive a sensible AppAccent from a legacy AppThemeMode identity with
    // no change to this enum's existing entries/fields/behavior — every current call site
    // (values(), .name, .isLight, .samplePrimary, .sampleBg, MyApplicationTheme(themeMode))
    // keeps compiling and rendering exactly as before.
    val accent: AppAccent get() = AppAccent.fromKey(name)
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

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.EDITORIAL_LIGHT,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.EDITORIAL_LIGHT -> EditorialLightColorScheme
        AppThemeMode.MONOCHROME_DARK -> MonochromeDarkColorScheme
        AppThemeMode.MIDNIGHT_SAPPHIRE -> MidnightSapphireColorScheme
        AppThemeMode.KYOTO_MATCHA -> KyotoMatchaColorScheme
        AppThemeMode.CYBER_TERMINAL -> CyberTerminalColorScheme
        AppThemeMode.DUSK_TERRACOTTA -> DuskTerracottaColorScheme
        AppThemeMode.SOLAR_AMBER -> SolarAmberColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
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
 * v6.0 — orthogonal accent-axis overload. Not yet wired to any call site (MainActivity still
 * resolves [AppThemeMode] via the overload above); this is the engine that MainActivity's
 * `selectedAccent`-driven resolution will call in a follow-up change.
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
