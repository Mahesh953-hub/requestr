package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B — the two new theme families (Pastel Light, Deep Olive Dark), the `AppThemeMode.fromKey`
 * key resolver, and the composed `buildColorScheme(themeMode, accent)` engine. Pure Kotlin /
 * Compose-graphics, so plain JUnit (same convention as AccentThemeTest).
 *
 * Contrast thresholds follow WCAG 2.x: 4.5:1 for body text roles, 3:1 for UI-component /
 * large-text roles (on-primary labels are 12sp+ bold in this app).
 */
class ThemeFamiliesTest {

    private fun assertContrastAtLeast(min: Float, fg: Color, bg: Color, what: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$what contrast ${"%.2f".format(ratio)} < $min", ratio >= min)
    }

    /** Body-text pairings every family must satisfy at 4.5:1. */
    private fun assertBodyTextReadable(scheme: ColorScheme, name: String) {
        assertContrastAtLeast(4.5f, scheme.onBackground, scheme.background, "$name onBackground/background")
        assertContrastAtLeast(4.5f, scheme.onSurface, scheme.surface, "$name onSurface/surface")
        assertContrastAtLeast(4.5f, scheme.onSurfaceVariant, scheme.surfaceVariant, "$name onSurfaceVariant/surfaceVariant")
        assertContrastAtLeast(4.5f, scheme.onPrimaryContainer, scheme.primaryContainer, "$name onPrimaryContainer/primaryContainer")
        assertContrastAtLeast(4.5f, scheme.onSecondaryContainer, scheme.secondaryContainer, "$name onSecondaryContainer/secondaryContainer")
        assertContrastAtLeast(4.5f, scheme.onTertiaryContainer, scheme.tertiaryContainer, "$name onTertiaryContainer/tertiaryContainer")
        assertContrastAtLeast(4.5f, scheme.onErrorContainer, scheme.errorContainer, "$name onErrorContainer/errorContainer")
        assertContrastAtLeast(4.5f, scheme.error, scheme.surface, "$name error text on surface")
        assertContrastAtLeast(4.5f, scheme.inverseOnSurface, scheme.inverseSurface, "$name inverseOnSurface/inverseSurface")
    }

    /** UI-component pairings at 3:1 (button labels, badges, filled icons). */
    private fun assertComponentsReadable(scheme: ColorScheme, name: String) {
        assertContrastAtLeast(3f, scheme.onPrimary, scheme.primary, "$name onPrimary/primary")
        assertContrastAtLeast(3f, scheme.onSecondary, scheme.secondary, "$name onSecondary/secondary")
        assertContrastAtLeast(3f, scheme.onTertiary, scheme.tertiary, "$name onTertiary/tertiary")
        assertContrastAtLeast(3f, scheme.onError, scheme.error, "$name onError/error")
        assertContrastAtLeast(3f, scheme.primary, scheme.background, "$name primary on background")
    }

    // ── enum ──

    @Test
    fun `there are nine theme families, three light and six dark`() {
        assertEquals(9, AppThemeMode.values().size)
        assertEquals(
            listOf(AppThemeMode.EDITORIAL_LIGHT, AppThemeMode.KYOTO_MATCHA, AppThemeMode.PASTEL_LIGHT),
            AppThemeMode.lightThemes()
        )
        assertEquals(6, AppThemeMode.darkThemes().size)
        assertTrue(AppThemeMode.DEEP_OLIVE_DARK in AppThemeMode.darkThemes())
    }

    @Test
    fun `new families carry the delta-spec identities`() {
        assertEquals("Pastel Light", AppThemeMode.PASTEL_LIGHT.displayName)
        assertTrue(AppThemeMode.PASTEL_LIGHT.isLight)
        assertEquals(Color(0xFFFAFBF7), AppThemeMode.PASTEL_LIGHT.sampleBg)
        assertEquals(Color(0xFF9B8B7E), AppThemeMode.PASTEL_LIGHT.samplePrimary)

        assertEquals("Deep Olive Dark", AppThemeMode.DEEP_OLIVE_DARK.displayName)
        assertFalse(AppThemeMode.DEEP_OLIVE_DARK.isLight)
        assertEquals(Color(0xFF0D0F09), AppThemeMode.DEEP_OLIVE_DARK.sampleBg)
        assertEquals(Color(0xFF8FA880), AppThemeMode.DEEP_OLIVE_DARK.samplePrimary)
    }

    @Test
    fun `every family maps to a real accent through the compatibility bridge`() {
        AppThemeMode.values().forEach { mode -> assertTrue(mode.accent in AppAccent.values()) }
        assertEquals(AppAccent.OBSIDIAN, AppThemeMode.PASTEL_LIGHT.accent)
        assertEquals(AppAccent.LIME, AppThemeMode.DEEP_OLIVE_DARK.accent)
    }

    // ── fromKey ──

    @Test
    fun `fromKey round-trips every enum name in any case`() {
        AppThemeMode.values().forEach { mode ->
            assertEquals(mode, AppThemeMode.fromKey(mode.name))
            assertEquals(mode, AppThemeMode.fromKey(mode.name.lowercase()))
            assertEquals(mode, AppThemeMode.fromKey("  ${mode.name}  "))
        }
    }

    @Test
    fun `fromKey accepts the legacy short aliases MainActivity used to hardcode`() {
        assertEquals(AppThemeMode.EDITORIAL_LIGHT, AppThemeMode.fromKey("LIGHT"))
        assertEquals(AppThemeMode.MONOCHROME_DARK, AppThemeMode.fromKey("OBSIDIAN"))
        assertEquals(AppThemeMode.MIDNIGHT_SAPPHIRE, AppThemeMode.fromKey("MIDNIGHT"))
        assertEquals(AppThemeMode.KYOTO_MATCHA, AppThemeMode.fromKey("MATCHA"))
        assertEquals(AppThemeMode.CYBER_TERMINAL, AppThemeMode.fromKey("TERMINAL"))
        assertEquals(AppThemeMode.DUSK_TERRACOTTA, AppThemeMode.fromKey("DUSK"))
        assertEquals(AppThemeMode.SOLAR_AMBER, AppThemeMode.fromKey("AMBER"))
        assertEquals(AppThemeMode.PASTEL_LIGHT, AppThemeMode.fromKey("PASTEL"))
        assertEquals(AppThemeMode.DEEP_OLIVE_DARK, AppThemeMode.fromKey("OLIVE"))
    }

    @Test
    fun `fromKey returns null for unknown or blank keys instead of guessing a family`() {
        assertNull(AppThemeMode.fromKey(null))
        assertNull(AppThemeMode.fromKey(""))
        assertNull(AppThemeMode.fromKey("   "))
        assertNull(AppThemeMode.fromKey("NOT_A_THEME"))
    }

    @Test
    fun `a light key never resolves to a dark family`() {
        listOf("PASTEL_LIGHT", "EDITORIAL_LIGHT", "KYOTO_MATCHA", "LIGHT", "PASTEL").forEach { key ->
            val mode = AppThemeMode.fromKey(key)
            assertNotNull(key, mode)
            assertTrue("$key resolved to dark ${mode!!.name}", mode.isLight)
        }
    }

    // ── canvas schemes ──

    @Test
    fun `Pastel Light canvas uses the delta-spec hex values`() {
        val s = themeColorScheme(AppThemeMode.PASTEL_LIGHT)
        assertEquals(Color(0xFFFAFBF7), s.background)
        assertEquals(Color(0xFFFEFDFC), s.surface)
        assertEquals(Color(0xFFF5F1ED), s.surfaceVariant)
        assertEquals(Color(0xFFE8E3DD), s.outline)
        assertEquals(Color(0xFFD9CFC5), s.outlineVariant)
        assertEquals(Color(0xFF9B8B7E), s.primary)
        assertEquals(Color(0xFFF0E8E1), s.primaryContainer)
        assertEquals(Color(0xFF3A3530), s.onBackground)
        assertEquals(Color(0xFF3A3530), s.onSurface)
        assertEquals(Color(0xFF6B625D), s.onSurfaceVariant)
    }

    @Test
    fun `Deep Olive Dark canvas uses the delta-spec hex values`() {
        val s = themeColorScheme(AppThemeMode.DEEP_OLIVE_DARK)
        assertEquals(Color(0xFF0D0F09), s.background)
        assertEquals(Color(0xFF17191C), s.surface)
        assertEquals(Color(0xFF1F2420), s.surfaceVariant)
        assertEquals(Color(0xFF2A2F28), s.outline)
        assertEquals(Color(0xFF3A403A), s.outlineVariant)
        assertEquals(Color(0xFF8FA880), s.primary)
        assertEquals(Color(0xFF2A3520), s.primaryContainer)
        assertEquals(Color(0xFFF0EDE9), s.onBackground)
        assertEquals(Color(0xFFF0EDE9), s.onSurface)
        assertEquals(Color(0xFFB7BFB3), s.onSurfaceVariant)
    }

    @Test
    fun `new family canvases meet body-text and component contrast`() {
        listOf(AppThemeMode.PASTEL_LIGHT, AppThemeMode.DEEP_OLIVE_DARK).forEach { mode ->
            val s = themeColorScheme(mode)
            assertBodyTextReadable(s, mode.name)
            assertComponentsReadable(s, mode.name)
        }
    }

    @Test
    fun `new family canvases set every surface-container role deliberately, not to the M3 defaults`() {
        listOf(AppThemeMode.PASTEL_LIGHT, AppThemeMode.DEEP_OLIVE_DARK).forEach { mode ->
            val s = themeColorScheme(mode)
            val containers = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            // Monotonic tonal ladder: light families get darker as elevation rises, dark families lighter.
            val lum = containers.map { relativeLuminance(it) }
            if (mode.isLight) {
                assertEquals("$mode container ladder should darken", lum.sortedDescending(), lum)
            } else {
                assertEquals("$mode container ladder should lighten", lum.sorted(), lum)
            }
            // surfaceTint is the family primary tint, scrim is black.
            assertEquals(Color.Black, s.scrim)
            assertTrue(s.surfaceTint != Color.Unspecified)
        }
    }

    @Test
    fun `themeColorScheme returns a light scheme for light families and a dark scheme for dark ones`() {
        AppThemeMode.values().forEach { mode ->
            val s = themeColorScheme(mode)
            val bgIsLight = relativeLuminance(s.background) > 0.5f
            assertEquals("${mode.name} background lightness disagrees with isLight", mode.isLight, bgIsLight)
        }
    }

    // ── contrast helpers ──

    @Test
    fun `contrastRatio matches WCAG reference points`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
        // #767676 on white is the well-known 4.54:1 boundary colour.
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), Color.White), 0.02f)
        // Symmetric.
        assertEquals(contrastRatio(Color.Red, Color.Blue), contrastRatio(Color.Blue, Color.Red), 0.0001f)
    }

    @Test
    fun `readableInkOn picks dark ink on bright accents and light ink on dark accents`() {
        assertEquals(Color(0xFF111111), readableInkOn(AccentLime, darkInk = Color(0xFF111111)))
        assertEquals(Color.White, readableInkOn(Color(0xFF1E3A8A), darkInk = Color(0xFF111111)))
        // Ties and near-ties are deterministic: dark ink wins when it is at least as readable.
        assertEquals(Color(0xFF111111), readableInkOn(Color(0xFF808080), darkInk = Color(0xFF111111)))
    }

    // ── composed engine ──

    @Test
    fun `buildColorScheme keeps the family canvas and swaps in the accent`() {
        AppThemeMode.values().forEach { mode ->
            val base = themeColorScheme(mode)
            AppAccent.values().forEach { accent ->
                val s = buildColorScheme(mode, accent)
                assertEquals("$mode/$accent background", base.background, s.background)
                assertEquals("$mode/$accent surface", base.surface, s.surface)
                assertEquals("$mode/$accent surfaceVariant", base.surfaceVariant, s.surfaceVariant)
                assertEquals("$mode/$accent outline", base.outline, s.outline)
                assertEquals("$mode/$accent onSurface", base.onSurface, s.onSurface)
                assertEquals("$mode/$accent error", base.error, s.error)
                val expectedPrimary = if (mode.isLight) accent.primary else accent.bright
                assertEquals("$mode/$accent primary", expectedPrimary, s.primary)
                assertEquals("$mode/$accent primaryContainer", if (mode.isLight) accent.containerLight else accent.containerDark, s.primaryContainer)
                assertEquals("$mode/$accent surfaceTint", expectedPrimary, s.surfaceTint)
            }
        }
    }

    @Test
    fun `every family times every accent keeps body text and on-primary labels readable`() {
        AppThemeMode.values().forEach { mode ->
            AppAccent.values().forEach { accent ->
                val s = buildColorScheme(mode, accent)
                val name = "${mode.name}×${accent.name}"
                assertContrastAtLeast(4.5f, s.onBackground, s.background, "$name onBackground/background")
                assertContrastAtLeast(4.5f, s.onSurface, s.surface, "$name onSurface/surface")
                assertContrastAtLeast(3f, s.onPrimary, s.primary, "$name onPrimary/primary")
                assertContrastAtLeast(3f, s.onSecondary, s.secondary, "$name onSecondary/secondary")
                assertContrastAtLeast(3f, s.onTertiary, s.tertiary, "$name onTertiary/tertiary")
            }
        }
    }

    @Test
    fun `buildColorScheme is pure - same inputs give equal schemes`() {
        val a = buildColorScheme(AppThemeMode.DEEP_OLIVE_DARK, AppAccent.INDIGO)
        val b = buildColorScheme(AppThemeMode.DEEP_OLIVE_DARK, AppAccent.INDIGO)
        assertEquals(a.primary, b.primary)
        assertEquals(a.background, b.background)
        assertEquals(a.onPrimary, b.onPrimary)
    }

    @Test
    fun `existing Universal accent engine is untouched by the composed engine`() {
        val light = buildLightColorScheme(AppAccent.INDIGO)
        assertEquals(UniversalLightBackground, light.background)
        val dark = buildDarkColorScheme(AppAccent.INDIGO)
        assertEquals(UniversalDarkBackground, dark.background)
    }
}
