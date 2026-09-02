package com.example.ui.screens

import com.example.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B — pure helpers behind the Settings Appearance / Glass / Local API / Plugin blocks.
 * Same pattern as the other *HelpersTest files in this package: the composables are thin over
 * these functions, so the decision logic is covered without Robolectric.
 */
class SettingsAppearanceHelpersTest {

    // ── theme resolution (must mirror RequestrApp in MainActivity) ──

    @Test
    fun `SYSTEM mode picks the remembered per-family slot from the OS dark flag`() {
        assertEquals(
            "DEEP_OLIVE_DARK",
            resolveEffectiveThemeKey("SYSTEM", "EDITORIAL_LIGHT", "PASTEL_LIGHT", "DEEP_OLIVE_DARK", systemInDarkTheme = true)
        )
        assertEquals(
            "PASTEL_LIGHT",
            resolveEffectiveThemeKey("SYSTEM", "EDITORIAL_LIGHT", "PASTEL_LIGHT", "DEEP_OLIVE_DARK", systemInDarkTheme = false)
        )
        // case-insensitive
        assertEquals("PASTEL_LIGHT", resolveEffectiveThemeKey("system", "X", "PASTEL_LIGHT", "Y", systemInDarkTheme = false))
    }

    @Test
    fun `LIGHT and DARK modes render themeMode and ignore the OS flag`() {
        assertEquals("KYOTO_MATCHA", resolveEffectiveThemeKey("LIGHT", "KYOTO_MATCHA", "PASTEL_LIGHT", "DEEP_OLIVE_DARK", systemInDarkTheme = true))
        assertEquals("SOLAR_AMBER", resolveEffectiveThemeKey("DARK", "SOLAR_AMBER", "PASTEL_LIGHT", "DEEP_OLIVE_DARK", systemInDarkTheme = false))
    }

    @Test
    fun `theme picker offers only the matching family in LIGHT or DARK and all nine in SYSTEM`() {
        assertEquals(AppThemeMode.lightThemes(), themeFamiliesFor("LIGHT"))
        assertEquals(AppThemeMode.darkThemes(), themeFamiliesFor("DARK"))
        val system = themeFamiliesFor("SYSTEM")
        assertEquals(AppThemeMode.values().size, system.size)
        assertEquals(AppThemeMode.values().toSet(), system.toSet())
        // light families are listed first in SYSTEM mode
        assertTrue(system.take(AppThemeMode.lightThemes().size).all { it.isLight })
        assertTrue(themeFamiliesFor("LIGHT").all { it.isLight })
        assertTrue(themeFamiliesFor("DARK").none { it.isLight })
    }

    // ── Tune Glass visibility ──

    @Test
    fun `tune glass panel applies to exactly the surfaces set to TUNE_GLASS`() {
        assertEquals(emptyList<String>(), tuneGlassSurfaces("FROSTED_GLASS", "OLED_BLACK"))
        assertEquals(listOf("Header"), tuneGlassSurfaces("TUNE_GLASS", "FROSTED_GLASS"))
        assertEquals(listOf("Nav pill"), tuneGlassSurfaces("SOLID_M3", "TUNE_GLASS"))
        assertEquals(listOf("Header", "Nav pill"), tuneGlassSurfaces("TUNE_GLASS", "TUNE_GLASS"))
        // legacy alias still counts
        assertEquals(listOf("Header"), tuneGlassSurfaces("tune", "LIQUID_GLASS"))
    }

    // ── Local API card ──

    @Test
    fun `reveal countdown formats minutes and seconds, rounds up, never negative`() {
        assertEquals("5:00", formatRevealCountdown(5 * 60 * 1000L))
        assertEquals("4:59", formatRevealCountdown(4 * 60 * 1000L + 59_000L))
        assertEquals("0:01", formatRevealCountdown(1L))
        assertEquals("0:01", formatRevealCountdown(1000L))
        assertEquals("0:00", formatRevealCountdown(0L))
        assertEquals("0:00", formatRevealCountdown(-5000L))
        assertEquals("1:05", formatRevealCountdown(65_000L))
    }

    @Test
    fun `endpoint table has unique method-path pairs and marks exactly the bearer-protected routes`() {
        val pairs = localApiEndpoints.map { it.method to it.path }
        assertEquals(pairs.size, pairs.toSet().size)
        val bearer = localApiEndpoints.filter { it.bearer }.map { "${it.method} ${it.path}" }.toSet()
        assertEquals(
            setOf("GET /api/rules", "POST /api/rules", "PUT /api/rules/{id}", "DELETE /api/rules/{id}", "POST /api/observe", "POST /api/keylog"),
            bearer
        )
        assertTrue(localApiEndpoints.all { it.path.startsWith("/api/") })
        assertTrue(localApiEndpoints.all { it.method in setOf("GET", "POST", "PUT", "DELETE") })
        assertTrue(localApiEndpoints.all { it.summary.isNotBlank() })
    }
}
