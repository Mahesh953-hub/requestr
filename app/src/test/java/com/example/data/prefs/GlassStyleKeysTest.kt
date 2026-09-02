package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v6.0 transplant (plan task 6, DESIGN-RECORD §11) — verifies every stored glass-style
 * value maps to a supported canonical component key so no known setting silently falls
 * through the glass component `else → FROSTED_GLASS` branch. Canonical keys are:
 * `FROSTED_GLASS`, `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`.
 *
 * Pure-Kotlin (no Android framework calls), so this runs as a plain JUnit test.
 */
class GlassStyleKeysTest {

    @Test
    fun `canonical keys pass through unchanged`() {
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("FROSTED_GLASS"))
        assertEquals("SOLID_M3", canonicalGlassStyle("SOLID_M3"))
        assertEquals("OLED_BLACK", canonicalGlassStyle("OLED_BLACK"))
        assertEquals("TRANSLUCENT", canonicalGlassStyle("TRANSLUCENT"))
    }

    @Test
    fun `legacy header values map to canonical keys`() {
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("FROSTED"))
        assertEquals("SOLID_M3", canonicalGlassStyle("M3_MINIMAL"))
        assertEquals("TRANSLUCENT", canonicalGlassStyle("CLEAR_GLASS"))
        assertEquals("OLED_BLACK", canonicalGlassStyle("DEEP_DARK"))
    }

    @Test
    fun `legacy nav values map to canonical keys`() {
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("FROSTED"))
        assertEquals("SOLID_M3", canonicalGlassStyle("M3"))
        assertEquals("OLED_BLACK", canonicalGlassStyle("OLED"))
        assertEquals("TRANSLUCENT", canonicalGlassStyle("TRANSLUCENT"))
    }

    @Test
    fun `case-insensitive and trimmed`() {
        assertEquals("SOLID_M3", canonicalGlassStyle(" m3 "))
        assertEquals("OLED_BLACK", canonicalGlassStyle("oled"))
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("frosted"))
    }

    @Test
    fun `unknown and blank fall back to frosted glass`() {
        assertEquals("FROSTED_GLASS", canonicalGlassStyle(null))
        assertEquals("FROSTED_GLASS", canonicalGlassStyle(""))
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("   "))
        assertEquals("FROSTED_GLASS", canonicalGlassStyle("UNKNOWN_STYLE"))
    }

    @Test
    fun `every canonical key is one of the six supported values`() {
        val supported = setOf("FROSTED_GLASS", "SOLID_M3", "OLED_BLACK", "TRANSLUCENT")
        listOf(null, "", "FROSTED", "M3_MINIMAL", "CLEAR_GLASS", "DEEP_DARK",
            "M3", "OLED", "TRANSLUCENT", "FROSTED_GLASS", "SOLID_M3", "OLED_BLACK",
            "garbage", "  m3  ").forEach { raw ->
            assert(supported.contains(canonicalGlassStyle(raw))) {
                "canonicalGlassStyle($raw) = ${canonicalGlassStyle(raw)} not in supported set"
            }
        }
    }
}
