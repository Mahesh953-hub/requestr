package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 10 Rich Dynamic Accents for Android 17 / Pixel-style dynamic coloring.
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

/**
 * Backward compatibility enum for legacy code.
 */
enum class AppThemeMode(val displayName: String, val isLight: Boolean, val samplePrimary: Color, val sampleBg: Color) {
    EDITORIAL_LIGHT("Editorial Light", true, AccentOrange, UniversalLightBackground),
    MONOCHROME_DARK("Obsidian Dark", false, AccentObsidianBright, UniversalDarkBackground),
    MIDNIGHT_SAPPHIRE("Midnight Sapphire", false, AccentSapphireBright, UniversalDarkBackground),
    KYOTO_MATCHA("Kyoto Matcha", true, AccentEmerald, UniversalLightBackground),
    CYBER_TERMINAL("Dynamic Lime", false, AccentLimeBright, UniversalDarkBackground),
    DUSK_TERRACOTTA("Dusk Terracotta", false, AccentOrangeBright, UniversalDarkBackground),
    SOLAR_AMBER("Solar Amber", false, AccentAmberBright, UniversalDarkBackground);

    val accent: AppAccent get() = AppAccent.fromKey(name)
}

/**
 * Builds a dynamic Light ColorScheme where canvas is clean off-white / light and primary is the chosen accent.
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
 * Builds a dynamic Dark ColorScheme where canvas is deep ambient obsidian dark and primary is the chosen accent.
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

@Composable
fun MyApplicationTheme(
    isDarkTheme: Boolean = true,
    accent: AppAccent = AppAccent.INDIGO,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDarkTheme) buildDarkColorScheme(accent) else buildLightColorScheme(accent)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    MyApplicationTheme(
        isDarkTheme = !themeMode.isLight,
        accent = themeMode.accent,
        content = content
    )
}
