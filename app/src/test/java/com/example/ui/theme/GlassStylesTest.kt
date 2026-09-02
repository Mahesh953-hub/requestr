package com.example.ui.theme

import com.example.data.prefs.canonicalGlassStyle
import com.example.ui.components.glassModeDisplayName
import com.example.data.repository.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B — the glass-style catalogue that both Settings pickers read, and the Tune Glass slider
 * ranges versus the repository clamps they must match.
 */
class GlassStylesTest {

    @After
    fun resetTuneValues() {
        // SettingsRepository is a process singleton; put the four Tune values back to defaults so
        // no other test observes a mutated state. persist() is a no-op without an app context.
        SettingsRepository.updateGlassTuneBlur(24)
        SettingsRepository.updateGlassTuneTintOpacity(0.90f)
        SettingsRepository.updateGlassTuneBorderOpacity(0.35f)
        SettingsRepository.updateGlassTuneShadowDp(6)
    }

    @Test
    fun `catalogue lists exactly the six canonical styles in picker order`() {
        assertEquals(
            listOf("FROSTED_GLASS", "SOLID_M3", "TRANSLUCENT", "OLED_BLACK", "LIQUID_GLASS", "TUNE_GLASS"),
            GlassStyles.options.map { it.key }
        )
    }

    @Test
    fun `labels are the locked ADDENDUM names - TRANSLUCENT is labelled Clear Glass`() {
        assertEquals("Frosted Glass", GlassStyles.label("FROSTED_GLASS"))
        assertEquals("Material 3", GlassStyles.label("SOLID_M3"))
        assertEquals("Clear Glass", GlassStyles.label("TRANSLUCENT"))
        assertEquals("OLED Black", GlassStyles.label("OLED_BLACK"))
        assertEquals("Liquid Glass", GlassStyles.label("LIQUID_GLASS"))
        assertEquals("Tune Glass", GlassStyles.label("TUNE_GLASS"))
        // There is no CLEAR_GLASS canonical key — it is a legacy alias of TRANSLUCENT.
        assertEquals("TRANSLUCENT", GlassStyles.canonicalKey("CLEAR_GLASS"))
        assertEquals("Clear Glass", GlassStyles.label("CLEAR_GLASS"))
        assertFalse(GlassStyles.options.any { it.key == "CLEAR_GLASS" })
    }

    @Test
    fun `labels agree with the renderer-side glassModeDisplayName for every key and alias`() {
        GlassStyles.options.forEach { option ->
            assertEquals(option.key, glassModeDisplayName(option.key), option.label)
        }
        listOf("FROSTED", "M3", "M3_MINIMAL", "OLED", "DEEP_DARK", "CLEAR_GLASS", "LIQUID", "TUNE", "bogus", null).forEach { alias ->
            assertEquals("alias $alias", glassModeDisplayName(alias), GlassStyles.label(alias))
        }
    }

    @Test
    fun `labels are unique so two chips can never read the same`() {
        assertEquals(GlassStyles.options.size, GlassStyles.options.map { it.label }.toSet().size)
    }

    @Test
    fun `canonicalKey agrees with UnifiedDataStore canonicalGlassStyle for every alias`() {
        listOf(
            "FROSTED_GLASS", "FROSTED", "SOLID_M3", "M3_MINIMAL", "M3", "OLED_BLACK", "DEEP_DARK", "OLED",
            "TRANSLUCENT", "CLEAR_GLASS", "LIQUID_GLASS", "LIQUID", "TUNE_GLASS", "TUNE", "garbage", "", "frosted", " m3 "
        ).forEach { key ->
            assertEquals("alias $key", canonicalGlassStyle(key), GlassStyles.canonicalKey(key))
        }
        assertEquals("FROSTED_GLASS", GlassStyles.canonicalKey(null))
    }

    @Test
    fun `only the two blur-based styles are flagged as needing API 31`() {
        assertEquals(setOf("LIQUID_GLASS", "TUNE_GLASS"), GlassStyles.options.filter { it.needsBlurApi }.map { it.key }.toSet())
        assertEquals(31, GlassStyles.REAL_BLUR_MIN_API)
        assertFalse(GlassStyles.hasRealBlur(24))
        assertFalse(GlassStyles.hasRealBlur(30))
        assertTrue(GlassStyles.hasRealBlur(31))
        assertTrue(GlassStyles.hasRealBlur(34))
    }

    @Test
    fun `every option has a non-empty description`() {
        GlassStyles.options.forEach { assertTrue(it.key, it.description.isNotBlank()) }
    }

    @Test
    fun `slider ranges equal the repository clamps`() {
        SettingsRepository.updateGlassTuneBlur(-5)
        assertEquals(GlassTuneRanges.blurDp.first, SettingsRepository.settings.value.glassTuneBlurDp)
        SettingsRepository.updateGlassTuneBlur(999)
        assertEquals(GlassTuneRanges.blurDp.last, SettingsRepository.settings.value.glassTuneBlurDp)

        SettingsRepository.updateGlassTuneTintOpacity(0f)
        assertEquals(GlassTuneRanges.tintOpacity.start, SettingsRepository.settings.value.glassTuneTintOpacity, 0.0001f)
        SettingsRepository.updateGlassTuneTintOpacity(2f)
        assertEquals(GlassTuneRanges.tintOpacity.endInclusive, SettingsRepository.settings.value.glassTuneTintOpacity, 0.0001f)

        SettingsRepository.updateGlassTuneBorderOpacity(-1f)
        assertEquals(GlassTuneRanges.borderOpacity.start, SettingsRepository.settings.value.glassTuneBorderOpacity, 0.0001f)
        SettingsRepository.updateGlassTuneBorderOpacity(5f)
        assertEquals(GlassTuneRanges.borderOpacity.endInclusive, SettingsRepository.settings.value.glassTuneBorderOpacity, 0.0001f)

        SettingsRepository.updateGlassTuneShadowDp(-1)
        assertEquals(GlassTuneRanges.shadowDp.first, SettingsRepository.settings.value.glassTuneShadowDp)
        SettingsRepository.updateGlassTuneShadowDp(100)
        assertEquals(GlassTuneRanges.shadowDp.last, SettingsRepository.settings.value.glassTuneShadowDp)
    }

    @Test
    fun `in-range tune values are stored unchanged`() {
        SettingsRepository.updateGlassTuneBlur(12)
        SettingsRepository.updateGlassTuneTintOpacity(0.5f)
        SettingsRepository.updateGlassTuneBorderOpacity(0.2f)
        SettingsRepository.updateGlassTuneShadowDp(10)
        val s = SettingsRepository.settings.value
        assertEquals(12, s.glassTuneBlurDp)
        assertEquals(0.5f, s.glassTuneTintOpacity, 0.0001f)
        assertEquals(0.2f, s.glassTuneBorderOpacity, 0.0001f)
        assertEquals(10, s.glassTuneShadowDp)
    }
}
