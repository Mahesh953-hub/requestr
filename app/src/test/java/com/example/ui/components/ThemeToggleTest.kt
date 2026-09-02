package com.example.ui.components

import com.example.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header theme toggle derives its cycle from [AppThemeMode] (no hard-coded list), flips
 * light ↔ dark onto the remembered family, and keeps every family reachable via long-press.
 * Pure Kotlin, plain JUnit.
 */
class ThemeToggleTest {

    @Test
    fun `tap flips light to the remembered dark family and back`() {
        val dark = ThemeToggle.flip("PASTEL_LIGHT", selectedLightTheme = "PASTEL_LIGHT", selectedDarkTheme = "DEEP_OLIVE_DARK")
        assertEquals(AppThemeMode.DEEP_OLIVE_DARK, dark)
        val light = ThemeToggle.flip(dark.name, selectedLightTheme = "PASTEL_LIGHT", selectedDarkTheme = "DEEP_OLIVE_DARK")
        assertEquals(AppThemeMode.PASTEL_LIGHT, light)
    }

    @Test
    fun `every family flips to the opposite side`() {
        for (mode in AppThemeMode.values()) {
            val target = ThemeToggle.flip(mode.name, "EDITORIAL_LIGHT", "MONOCHROME_DARK")
            assertEquals("flipping from $mode must change side", !mode.isLight, target.isLight)
        }
    }

    @Test
    fun `a remembered key on the wrong side or unknown falls back to the first family of that side`() {
        assertEquals(AppThemeMode.darkThemes().first(), ThemeToggle.flip("EDITORIAL_LIGHT", "EDITORIAL_LIGHT", "KYOTO_MATCHA"))
        assertEquals(AppThemeMode.darkThemes().first(), ThemeToggle.flip("EDITORIAL_LIGHT", null, "nope"))
        assertEquals(AppThemeMode.lightThemes().first(), ThemeToggle.flip("SOLAR_AMBER", "DUSK_TERRACOTTA", "SOLAR_AMBER"))
        assertEquals(AppThemeMode.lightThemes().first(), ThemeToggle.flip("SOLAR_AMBER", "", null))
    }

    @Test
    fun `legacy aliases resolve before flipping`() {
        // "OBSIDIAN" is the persisted alias of MONOCHROME_DARK; "MATCHA" of KYOTO_MATCHA.
        assertEquals(AppThemeMode.KYOTO_MATCHA, ThemeToggle.flip("OBSIDIAN", "MATCHA", "OBSIDIAN"))
        assertFalse(ThemeToggle.isLight("OBSIDIAN"))
        assertTrue(ThemeToggle.isLight("MATCHA"))
    }

    @Test
    fun `unknown current theme is treated as the default dark family`() {
        assertFalse(ThemeToggle.isLight(null))
        assertFalse(ThemeToggle.isLight("garbage"))
        assertEquals(AppThemeMode.PASTEL_LIGHT, ThemeToggle.flip("garbage", "PASTEL_LIGHT", null))
    }

    @Test
    fun `long-press walks each side as a ring and stays on that side`() {
        for (mode in AppThemeMode.values()) {
            val next = ThemeToggle.nextInFamily(mode.name)
            assertEquals("$mode → $next must stay on the same side", mode.isLight, next.isLight)
        }
        val lights = AppThemeMode.lightThemes()
        assertEquals(lights[1], ThemeToggle.nextInFamily(lights[0].name))
        assertEquals(lights[0], ThemeToggle.nextInFamily(lights.last().name))
    }

    @Test
    fun `every AppThemeMode is reachable from the toggle including the two new families`() {
        val reached = mutableSetOf<AppThemeMode>()
        var current = AppThemeMode.EDITORIAL_LIGHT
        repeat(AppThemeMode.lightThemes().size) { reached += current; current = ThemeToggle.nextInFamily(current.name) }
        current = ThemeToggle.flip(current.name, null, null)
        repeat(AppThemeMode.darkThemes().size) { reached += current; current = ThemeToggle.nextInFamily(current.name) }
        assertEquals(AppThemeMode.values().toSet(), reached)
        assertTrue(AppThemeMode.PASTEL_LIGHT in reached)
        assertTrue(AppThemeMode.DEEP_OLIVE_DARK in reached)
        assertEquals(9, AppThemeMode.values().size)
    }
}
