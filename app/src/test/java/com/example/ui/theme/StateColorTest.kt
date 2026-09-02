package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers the pure core behind [animateStateColor]: theme colours are resolved at read
 * time, so swapping the endpoints (a theme change) must be reflected immediately at any
 * progress value while the progress itself keeps animating the state change.
 */
class StateColorTest {

    private val inactive = Color(0xFF101010)
    private val active = Color(0xFFFF6600)

    @Test
    fun `progress 0 returns the inactive colour exactly`() {
        assertEquals(inactive, resolveStateColor(inactive, active, 0f))
    }

    @Test
    fun `progress 1 returns the active colour exactly`() {
        assertEquals(active, resolveStateColor(inactive, active, 1f))
    }

    @Test
    fun `progress is clamped to the 0 to 1 range`() {
        assertEquals(inactive, resolveStateColor(inactive, active, -0.5f))
        assertEquals(active, resolveStateColor(inactive, active, 1.7f))
    }

    @Test
    fun `mid progress is a lerp between the two endpoints`() {
        assertEquals(lerp(inactive, active, 0.5f), resolveStateColor(inactive, active, 0.5f))
    }

    @Test
    fun `swapping endpoints mid animation returns the new palette immediately`() {
        val newInactive = Color(0xFFF5F5F5)
        val newActive = Color(0xFF0066FF)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            val before = resolveStateColor(inactive, active, p)
            val after = resolveStateColor(newInactive, newActive, p)
            assertEquals(lerp(newInactive, newActive, p), after)
            assertNotEquals("progress=$p should not retain the old palette", before, after)
        }
    }
}
