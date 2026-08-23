package com.example.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.0 — focused coverage for the ported 10-accent theme engine (AppAccent + build{Light,Dark}
 * ColorScheme), added alongside the port from docs/legacy-main/theme-10accent (Theme.kt.txt,
 * Color.kt.txt) into Color.kt/Theme.kt. Pure-Kotlin/Compose-graphics surface (no Android
 * framework calls), so this runs as a plain JUnit test with no Robolectric needed --
 * consistent with other non-Android-dependent unit tests in this module (e.g.
 * MarkdownChecklistTest).
 *
 * Scope guard: this only exercises the new engine (AppAccent, buildLightColorScheme,
 * buildDarkColorScheme, AppThemeMode.accent). MainActivity/SettingsScreen/OnboardingScreen/
 * AppNavigationDrawer are not wired to this axis yet and are covered separately.
 */
class AccentThemeTest {

    @Test
    fun `fromKey resolves every AppAccent entry by its own name case-insensitively`() {
        AppAccent.values().forEach { accent ->
            assertEquals(accent, AppAccent.fromKey(accent.name))
            assertEquals(accent, AppAccent.fromKey(accent.name.lowercase()))
            assertEquals(accent, AppAccent.fromKey(accent.id))
        }
    }

    @Test
    fun `fromKey maps every legacy AppThemeMode name to its documented accent`() {
        assertEquals(AppAccent.ORANGE, AppAccent.fromKey("EDITORIAL_LIGHT"))
        assertEquals(AppAccent.OBSIDIAN, AppAccent.fromKey("MONOCHROME_DARK"))
        assertEquals(AppAccent.SAPPHIRE, AppAccent.fromKey("MIDNIGHT_SAPPHIRE"))
        assertEquals(AppAccent.EMERALD, AppAccent.fromKey("KYOTO_MATCHA"))
        assertEquals(AppAccent.LIME, AppAccent.fromKey("CYBER_TERMINAL"))
        assertEquals(AppAccent.ORANGE, AppAccent.fromKey("DUSK_TERRACOTTA"))
        assertEquals(AppAccent.AMBER, AppAccent.fromKey("SOLAR_AMBER"))
    }

    @Test
    fun `fromKey falls back to INDIGO for an unknown key`() {
        assertEquals(AppAccent.INDIGO, AppAccent.fromKey("not-a-real-key"))
        assertEquals(AppAccent.INDIGO, AppAccent.fromKey(""))
    }

    @Test
    fun `every legacy AppThemeMode entry maps to a sensible accent via the compatibility bridge`() {
        assertEquals(AppAccent.ORANGE, AppThemeMode.EDITORIAL_LIGHT.accent)
        assertEquals(AppAccent.OBSIDIAN, AppThemeMode.MONOCHROME_DARK.accent)
        assertEquals(AppAccent.SAPPHIRE, AppThemeMode.MIDNIGHT_SAPPHIRE.accent)
        assertEquals(AppAccent.EMERALD, AppThemeMode.KYOTO_MATCHA.accent)
        assertEquals(AppAccent.LIME, AppThemeMode.CYBER_TERMINAL.accent)
        assertEquals(AppAccent.ORANGE, AppThemeMode.DUSK_TERRACOTTA.accent)
        assertEquals(AppAccent.AMBER, AppThemeMode.SOLAR_AMBER.accent)
        // Every legacy entry must resolve to *some* real AppAccent -- never throw, never null.
        AppThemeMode.values().forEach { mode ->
            assertTrue(AppAccent.values().contains(mode.accent))
        }
    }

    @Test
    fun `buildLightColorScheme sets primary to the accent's primary token and stays on the Universal light canvas`() {
        AppAccent.values().forEach { accent ->
            val scheme = buildLightColorScheme(accent)
            assertEquals(accent.primary, scheme.primary)
            assertEquals(accent.containerLight, scheme.primaryContainer)
            assertEquals(UniversalLightBackground, scheme.background)
            assertEquals(UniversalLightSurface, scheme.surface)
        }
    }

    @Test
    fun `buildDarkColorScheme sets primary to the accent's bright token and stays on the Universal dark canvas`() {
        AppAccent.values().forEach { accent ->
            val scheme = buildDarkColorScheme(accent)
            assertEquals(accent.bright, scheme.primary)
            assertEquals(accent.containerDark, scheme.primaryContainer)
            assertEquals(UniversalDarkBackground, scheme.background)
            assertEquals(UniversalDarkSurface, scheme.surface)
        }
    }

    @Test
    fun `light and dark schemes for the same accent render different primaries and canvases`() {
        val light = buildLightColorScheme(AppAccent.INDIGO)
        val dark = buildDarkColorScheme(AppAccent.INDIGO)
        assertNotEquals(light.primary, dark.primary)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.surface, dark.surface)
    }

    @Test
    fun `all 10 accents are present with unique ids and unique primary colors`() {
        val accents = AppAccent.values()
        assertEquals(10, accents.size)
        assertEquals(accents.size, accents.map { it.id }.toSet().size)
        assertEquals(accents.size, accents.map { it.primary }.toSet().size)
    }
}
