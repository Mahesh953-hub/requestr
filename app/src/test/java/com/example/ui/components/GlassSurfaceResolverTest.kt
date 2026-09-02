package com.example.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glass rendering contract (DESIGN-SYSTEM §14.7): tier selection, the six glass modes, and the
 * single source of truth that replaced the three duplicated `when (blurStyle)` blocks.
 *
 * Pure Kotlin (enums + Compose-graphics Color values) — plain JUnit, no Robolectric.
 */
class GlassSurfaceResolverTest {

    private val lightPalette = GlassPalette(
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE7E0EC),
        outline = Color(0xFF79747E),
        outlineVariant = Color(0xFFCAC4D0),
        primary = Color(0xFF6750A4)
    )
    private val darkPalette = GlassPalette(
        surface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F),
        primary = Color(0xFFD0BCFF)
    )
    /** Compose packs sRGB alpha into 8 bits, so alpha round-trips within 1/255. */
    private val ALPHA_EPS = 1f / 255f + 1e-6f

    private val defaultTune = GlassTuneValues(blurDp = 24, tintOpacity = 0.90f, borderOpacity = 0.35f, shadowDp = 6)
    private val loudTune = GlassTuneValues(blurDp = 48, tintOpacity = 0.15f, borderOpacity = 1f, shadowDp = 24)

    private fun resolve(
        mode: String,
        role: GlassRole = GlassRole.HEADER,
        opacity: Float = 0.90f,
        palette: GlassPalette = lightPalette,
        tune: GlassTuneValues = defaultTune,
        realBlur: Boolean = true
    ) = resolveGlassSurface(mode, opacity, role, palette, tune, realBlur)

    // ─── Tier ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `real blur tier starts at API 31`() {
        assertEquals(GlassRenderTier.GRADIENT_FALLBACK, glassRenderTier(24))
        assertEquals(GlassRenderTier.GRADIENT_FALLBACK, glassRenderTier(30))
        assertEquals(GlassRenderTier.REAL_BLUR, glassRenderTier(31))
        assertEquals(GlassRenderTier.REAL_BLUR, glassRenderTier(35))
        assertEquals(31, GLASS_REAL_BLUR_MIN_SDK)
    }

    // ─── Mode keys ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `six modes resolve from canonical keys and legacy aliases`() {
        assertEquals(GlassMode.FROSTED_GLASS, GlassMode.fromKey("FROSTED_GLASS"))
        assertEquals(GlassMode.FROSTED_GLASS, GlassMode.fromKey("frosted"))
        assertEquals(GlassMode.SOLID_M3, GlassMode.fromKey("M3_MINIMAL"))
        assertEquals(GlassMode.OLED_BLACK, GlassMode.fromKey("DEEP_DARK"))
        assertEquals(GlassMode.TRANSLUCENT, GlassMode.fromKey("CLEAR_GLASS"))
        assertEquals(GlassMode.LIQUID_GLASS, GlassMode.fromKey("liquid"))
        assertEquals(GlassMode.TUNE_GLASS, GlassMode.fromKey("TUNE"))
        assertEquals(GlassMode.FROSTED_GLASS, GlassMode.fromKey(null))
        assertEquals(GlassMode.FROSTED_GLASS, GlassMode.fromKey("garbage"))
        assertEquals(6, GlassMode.entries.size)
    }

    // ─── Frozen legacy tokens ──────────────────────────────────────────────────────────────

    @Test
    fun `frosted keeps frozen tint and role-specific border alpha`() {
        val header = resolve("FROSTED_GLASS", GlassRole.HEADER, opacity = 0.90f)
        assertEquals(lightPalette.surface.copy(alpha = 0.90f), header.tintTop)
        assertEquals(0.90f * 0.95f, header.tintBottom.alpha, ALPHA_EPS)
        assertEquals(lightPalette.surfaceVariant.copy(alpha = 1f), header.tintBottom.copy(alpha = 1f))
        assertEquals(lightPalette.outline.copy(alpha = 0.30f), header.borderColor)

        val nav = resolve("FROSTED_GLASS", GlassRole.NAV, opacity = 0.92f)
        assertEquals(lightPalette.surface.copy(alpha = 0.92f), nav.tintTop)
        assertEquals(lightPalette.outline.copy(alpha = 0.35f), nav.borderColor)
        assertNull(nav.shadowElevationDp)
    }

    @Test
    fun `translucent keeps the two different multiplier pairs per role`() {
        val nav = resolve("TRANSLUCENT", GlassRole.NAV, opacity = 1f)
        assertEquals(0.7f, nav.tintTop.alpha, ALPHA_EPS)
        assertEquals(0.5f, nav.tintBottom.alpha, ALPHA_EPS)

        val header = resolve("TRANSLUCENT", GlassRole.HEADER, opacity = 1f)
        assertEquals(0.65f, header.tintTop.alpha, ALPHA_EPS)
        assertEquals(0.45f, header.tintBottom.alpha, ALPHA_EPS)

        // Floor of 0.2 preserved from the original brush blocks.
        val faint = resolve("TRANSLUCENT", GlassRole.HEADER, opacity = 0.2f)
        assertEquals(0.2f, faint.tintBottom.alpha, ALPHA_EPS)
    }

    @Test
    fun `solid m3 and oled black are opaque-style with no blur in any tier`() {
        val m3 = resolve("SOLID_M3")
        assertEquals(lightPalette.surface, m3.tintTop)
        assertEquals(lightPalette.surfaceVariant, m3.tintBottom)
        assertEquals(lightPalette.outlineVariant.copy(alpha = 0.6f), m3.borderColor)
        assertFalse(m3.hasBlur)

        val oled = resolve("OLED_BLACK", opacity = 0.8f)
        assertEquals(Color.Black.copy(alpha = 0.8f), oled.tintTop)
        assertEquals(Color(0xFF0F172A).copy(alpha = 0.8f), oled.tintBottom)
        assertEquals(Color.White.copy(alpha = 0.15f), oled.borderColor)
        assertFalse(oled.hasBlur)
        assertEquals(0f, oled.refraction, 0f)
    }

    @Test
    fun `opacity is coerced into 0_2 to 1`() {
        assertEquals(0.2f, resolve("FROSTED_GLASS", opacity = 0f).tintTop.alpha, ALPHA_EPS)
        assertEquals(1f, resolve("FROSTED_GLASS", opacity = 3f).tintTop.alpha, ALPHA_EPS)
    }

    // ─── Blur per tier ─────────────────────────────────────────────────────────────────────

    @Test
    fun `blur radius is layered under legacy modes only on the real blur tier`() {
        assertEquals(GLASS_FROSTED_BLUR_DP, resolve("FROSTED_GLASS", realBlur = true).blurRadiusDp, 0f)
        assertEquals(0f, resolve("FROSTED_GLASS", realBlur = false).blurRadiusDp, 0f)
        assertEquals(GLASS_CLEAR_BLUR_DP, resolve("TRANSLUCENT", realBlur = true).blurRadiusDp, 0f)
        assertEquals(0f, resolve("TRANSLUCENT", realBlur = false).blurRadiusDp, 0f)
        // Tint is identical across tiers — only the blur differs.
        assertEquals(resolve("FROSTED_GLASS", realBlur = true).tintTop, resolve("FROSTED_GLASS", realBlur = false).tintTop)
    }

    // ─── Liquid Glass ──────────────────────────────────────────────────────────────────────

    @Test
    fun `liquid glass refracts and thins its tint only when real blur exists`() {
        val real = resolve("LIQUID_GLASS", opacity = 1f, realBlur = true)
        assertEquals(GLASS_FROSTED_BLUR_DP, real.blurRadiusDp, 0f)
        assertEquals(GLASS_LIQUID_REFRACTION, real.refraction, 0f)
        assertEquals(0.45f, real.tintTop.alpha, ALPHA_EPS)
        assertEquals(0.30f, real.tintBottom.alpha, ALPHA_EPS)
        assertEquals(GLASS_LIQUID_HIGHLIGHT_ALPHA, real.highlightAlpha, 0f)

        val fallback = resolve("LIQUID_GLASS", opacity = 1f, realBlur = false)
        assertFalse(fallback.hasBlur)
        assertEquals(0f, fallback.refraction, 0f)
        assertEquals(0.80f, fallback.tintTop.alpha, ALPHA_EPS)
        assertEquals(0.70f, fallback.tintBottom.alpha, ALPHA_EPS)
        assertTrue(fallback.tintTop.alpha > real.tintTop.alpha)
    }

    @Test
    fun `liquid glass border follows theme darkness`() {
        assertEquals(lightPalette.outline.copy(alpha = 0.40f), resolve("LIQUID_GLASS", palette = lightPalette).borderColor)
        assertEquals(Color.White.copy(alpha = 0.28f), resolve("LIQUID_GLASS", palette = darkPalette).borderColor)
        assertFalse(lightPalette.isDark)
        assertTrue(darkPalette.isDark)
    }

    // ─── Tune Glass isolation ──────────────────────────────────────────────────────────────

    @Test
    fun `tune glass is the only mode that reads the tune values`() {
        for (mode in GlassMode.entries.filter { it != GlassMode.TUNE_GLASS }) {
            val a = resolve(mode.key, tune = defaultTune)
            val b = resolve(mode.key, tune = loudTune)
            assertEquals("mode $mode must ignore tune values", a, b)
        }
        val tuned = resolve("TUNE_GLASS", tune = loudTune)
        val default = resolve("TUNE_GLASS", tune = defaultTune)
        assertNotEquals(tuned, default)
    }

    @Test
    fun `tune glass maps every slider onto the spec`() {
        val spec = resolve("TUNE_GLASS", opacity = 0.5f, tune = loudTune, realBlur = true)
        assertEquals(48f, spec.blurRadiusDp, 0f)
        assertEquals(0.15f, spec.tintTop.alpha, ALPHA_EPS)
        assertEquals(0.15f, spec.tintBottom.alpha, ALPHA_EPS) // 0.15 * 0.95 floored at 0.15
        assertEquals(lightPalette.outline.copy(alpha = 1f), spec.borderColor)
        assertEquals(24f, spec.shadowElevationDp!!, 0f)
        assertEquals(GLASS_LIQUID_REFRACTION, spec.refraction, 0f)
        // The header/nav opacity slider does not leak into Tune Glass.
        assertEquals(spec.tintTop, resolve("TUNE_GLASS", opacity = 1f, tune = loudTune).tintTop)
    }

    @Test
    fun `tune glass without real blur has no refraction and keeps its shadow`() {
        val spec = resolve("TUNE_GLASS", tune = defaultTune, realBlur = false)
        assertFalse(spec.hasBlur)
        assertEquals(0f, spec.refraction, 0f)
        assertEquals(0f, spec.highlightAlpha, 0f)
        assertEquals(6f, spec.shadowElevationDp!!, 0f)
    }

    @Test
    fun `tune values are clamped to the settings ranges`() {
        val settings = com.example.data.models.UserSettingsState(
            glassTuneBlurDp = 99,
            glassTuneTintOpacity = 0f,
            glassTuneBorderOpacity = 2f,
            glassTuneShadowDp = -3
        )
        val tune = GlassTuneValues.from(settings)
        assertEquals(48, tune.blurDp)
        assertEquals(0.15f, tune.tintOpacity, 0f)
        assertEquals(1f, tune.borderOpacity, 0f)
        assertEquals(0, tune.shadowDp)
    }
}
