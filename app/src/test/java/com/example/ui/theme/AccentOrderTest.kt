package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.0 Lane A — accent swatch order (M6) and color-token fidelity.
 *
 * The approved mockups (settings-main-dark.html, onboarding-step-3-dark.html) render all 10
 * accent swatches in the enum's declaration order and pin each swatch to a specific hex color.
 * Both the Settings `AccentColorGrid` and the Onboarding `AppearanceStep` iterate
 * `AppAccent.values()` directly, so the enum declaration order *is* the rendered row order —
 * this test locks that contract so a future re-order cannot silently diverge from the mockups.
 *
 * Pure Kotlin (enum + Compose-graphics Color values) — runs as plain JUnit, no Robolectric.
 */
class AccentOrderTest {

    @Test
    fun `enum declaration order matches the M6 mockup swatch order`() {
        val expected = listOf(
            AppAccent.INDIGO,
            AppAccent.CYAN,
            AppAccent.EMERALD,
            AppAccent.ORANGE,
            AppAccent.LIME,
            AppAccent.VIOLET,
            AppAccent.ROSE,
            AppAccent.AMBER,
            AppAccent.SAPPHIRE,
            AppAccent.OBSIDIAN,
        )
        assertEquals(expected, AppAccent.values().toList())
    }

    @Test
    fun `exactly 10 accents exist`() {
        assertEquals(10, AppAccent.values().size)
    }

    @Test
    fun `each accent primary color matches the frozen mockup hex`() {
        // settings-main-dark.html .swatch backgrounds — the frozen M6 token set.
        assertEquals(Color(0xFF6366F1), AppAccent.INDIGO.primary)
        assertEquals(Color(0xFF06B6D4), AppAccent.CYAN.primary)
        assertEquals(Color(0xFF10B981), AppAccent.EMERALD.primary)
        assertEquals(Color(0xFFF97316), AppAccent.ORANGE.primary)
        assertEquals(Color(0xFF84CC16), AppAccent.LIME.primary)
        assertEquals(Color(0xFF8B5CF6), AppAccent.VIOLET.primary)
        assertEquals(Color(0xFFF43F5E), AppAccent.ROSE.primary)
        assertEquals(Color(0xFFF59E0B), AppAccent.AMBER.primary)
        assertEquals(Color(0xFF3B82F6), AppAccent.SAPPHIRE.primary)
        assertEquals(Color(0xFF64748B), AppAccent.OBSIDIAN.primary)
    }

    @Test
    fun `every accent round-trips through fromKey by id`() {
        AppAccent.values().forEach { accent ->
            assertEquals(accent, AppAccent.fromKey(accent.id))
            assertEquals(accent, AppAccent.fromKey(accent.id.lowercase()))
        }
    }

    @Test
    fun `accent ids are unique`() {
        val ids = AppAccent.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all accent primaries are distinct`() {
        val primaries = AppAccent.values().map { it.primary }
        assertEquals(primaries.size, primaries.toSet().size)
    }

    @Test
    fun `INDIGO is the first and default accent`() {
        assertEquals(AppAccent.INDIGO, AppAccent.values().first())
        // fromKey default fallback is INDIGO for unknown keys.
        assertEquals(AppAccent.INDIGO, AppAccent.fromKey("__unknown__"))
    }

    @Test
    fun `proxy tier colors reuse frozen accent primary tokens`() {
        // The 6-tier selector (settings-main-dark.html .tiergrid) uses accent/status colors
        // that must remain consistent with the DESIGN-SYSTEM §3 accent and status palettes.
        // VPN=3B82F6(SAPPHIRE primary), Proxy=06B6D4(CYAN primary), Root=10B981(EMERALD/green),
        // Shizuku=8B5CF6(VIOLET primary), Frida=F43F5E(ROSE primary), LSPosed=F59E0B(AMBER primary).
        assertEquals(Color(0xFF3B82F6), AppAccent.SAPPHIRE.primary)
        assertEquals(Color(0xFF06B6D4), AppAccent.CYAN.primary)
        assertEquals(Color(0xFF10B981), AppAccent.EMERALD.primary)
        assertEquals(Color(0xFF8B5CF6), AppAccent.VIOLET.primary)
        assertEquals(Color(0xFFF43F5E), AppAccent.ROSE.primary)
        assertEquals(Color(0xFFF59E0B), AppAccent.AMBER.primary)
    }

    @Test
    fun `proxy tier color set is a 6-element subset of accent primaries`() {
        val tierColors = setOf(
            Color(0xFF3B82F6),
            Color(0xFF06B6D4),
            Color(0xFF10B981),
            Color(0xFF8B5CF6),
            Color(0xFFF43F5E),
            Color(0xFFF59E0B),
        )
        val accentPrimaries = AppAccent.values().map { it.primary }.toSet()
        // every tier color must be an existing accent primary
        tierColors.forEach { c ->
            assertTrue("tier color $c must be an accent primary", accentPrimaries.contains(c))
        }
        assertEquals(6, tierColors.size)
    }
}
