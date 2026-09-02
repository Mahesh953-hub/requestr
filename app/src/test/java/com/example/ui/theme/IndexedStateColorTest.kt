package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [resolveIndexedStateColor], the multi-state sibling of `resolveStateColor`.
 *
 * The property that matters for the design system's "theme switching is instantaneous" rule is
 * that an element sitting *at* a state resolves to that state's colour exactly — no residual
 * blend — so re-reading the theme's colours produces the new palette on the very next frame.
 */
class IndexedStateColorTest {

    private val idle = Color(0xFF222222)
    private val armed = Color(0xFFFFAA00)
    private val active = Color(0xFF00CC66)
    private val ramp = listOf(idle, armed, active)

    @Test
    fun `an integral position returns that colour exactly`() {
        assertEquals(idle, resolveIndexedStateColor(ramp, 0f))
        assertEquals(armed, resolveIndexedStateColor(ramp, 1f))
        assertEquals(active, resolveIndexedStateColor(ramp, 2f))
    }

    @Test
    fun `a fractional position blends only its two neighbours`() {
        val mid = resolveIndexedStateColor(ramp, 0.5f)

        // Between idle and armed: red rises towards armed, and it is not either endpoint.
        assertTrue(mid.red > idle.red)
        assertTrue(mid.red < armed.red)
        assertEquals(idle.green + (armed.green - idle.green) * 0.5f, mid.green, 0.02f)
    }

    @Test
    fun `a fractional position in the upper segment ignores the first colour`() {
        val upper = resolveIndexedStateColor(ramp, 1.5f)

        assertEquals(armed.green + (active.green - armed.green) * 0.5f, upper.green, 0.02f)
        assertTrue(upper.red < armed.red)
    }

    @Test
    fun `out of range positions clamp to the ends rather than wrapping`() {
        assertEquals(idle, resolveIndexedStateColor(ramp, -5f))
        assertEquals(active, resolveIndexedStateColor(ramp, 99f))
    }

    @Test
    fun `a single colour ramp always resolves to that colour`() {
        assertEquals(armed, resolveIndexedStateColor(listOf(armed), 0f))
        assertEquals(armed, resolveIndexedStateColor(listOf(armed), 1f))
        assertEquals(armed, resolveIndexedStateColor(listOf(armed), -1f))
    }

    @Test
    fun `an empty ramp is a programming error and throws rather than rendering transparent`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveIndexedStateColor(emptyList(), 0f)
        }
    }

    @Test
    fun `swapping the ramp at rest changes the colour with no residual blend`() {
        // This is the theme-swap case: the progress scalar is unchanged, only the palette moved.
        val darkRamp = listOf(Color(0xFF101010), armed, active)
        val lightRamp = listOf(Color(0xFFF0F0F0), armed, active)

        assertEquals(darkRamp[0], resolveIndexedStateColor(darkRamp, 0f))
        assertEquals(lightRamp[0], resolveIndexedStateColor(lightRamp, 0f))
    }
}
