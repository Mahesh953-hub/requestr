package com.example.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Design system — glassmorphism base layer. Covers the pure parallax maths and the
 * [ParallaxState] reducer (attach / detach / continuity / lateral position).
 *
 * Compose snapshot state works on the plain JVM, so no Robolectric is needed.
 */
class ParallaxStateTest {

    private class FakeSource(var offset: Float) : ParallaxScrollSource {
        override fun currentScrollOffsetPx(): Float = offset
    }

    /** Minimal frame clock: every frame is 16ms later than the previous one. */
    private class StepFrameClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance)
    }

    // ---- parallaxShiftPx ----------------------------------------------------------------

    @Test
    fun `shift is zero at rest`() {
        assertEquals(0f, parallaxShiftPx(0f, 0.3f, 120f))
    }

    @Test
    fun `shift is approximately factor times offset near rest`() {
        // tanh(x) ~ x for small x: 10px * 0.3 = 3px, saturation ceiling far away at 120px.
        assertNear(3f, parallaxShiftPx(10f, 0.3f, 120f), tolerance = 0.05f)
    }

    @Test
    fun `shift never exceeds the maximum`() {
        assertTrue(parallaxShiftPx(1_000_000f, 0.3f, 120f) <= 120f)
        assertNear(120f, parallaxShiftPx(1_000_000f, 0.3f, 120f), tolerance = 0.01f)
    }

    @Test
    fun `shift is monotonic in the offset`() {
        var previous = -1f
        for (offset in 0..5000 step 25) {
            val shift = parallaxShiftPx(offset.toFloat(), 0.3f, 120f)
            assertTrue("offset $offset: $shift < $previous", shift >= previous)
            previous = shift
        }
    }

    @Test
    fun `shift follows the sign of the offset`() {
        assertTrue(parallaxShiftPx(-200f, 0.3f, 120f) < 0f)
        assertNear(-parallaxShiftPx(200f, 0.3f, 120f), parallaxShiftPx(-200f, 0.3f, 120f))
    }

    @Test
    fun `shift is zero until the layer has been configured`() {
        assertEquals(0f, parallaxShiftPx(500f, 0.3f, 0f))
        assertEquals(0f, parallaxShiftPx(500f, 0f, 120f))
    }

    // ---- lateral ------------------------------------------------------------------------

    @Test
    fun `lateral fraction spans the tab bar evenly`() {
        assertEquals(0f, lateralFractionForTab(0, 6))
        assertEquals(1f, lateralFractionForTab(5, 6))
        assertNear(0.4f, lateralFractionForTab(2, 6))
    }

    @Test
    fun `lateral fraction is centred for a single tab or an unknown index`() {
        assertEquals(0.5f, lateralFractionForTab(0, 1))
        assertEquals(0.5f, lateralFractionForTab(-1, 6))
        assertEquals(0.5f, lateralFractionForTab(6, 6))
    }

    @Test
    fun `right-most tab pushes the layer left and left-most pushes right`() {
        assertTrue(lateralShiftPx(1f, 24f) < 0f)
        assertTrue(lateralShiftPx(0f, 24f) > 0f)
        assertNear(0f, lateralShiftPx(0.5f, 24f))
        assertNear(-12f, lateralShiftPx(1f, 24f))
        assertNear(12f, lateralShiftPx(0f, 24f))
    }

    // ---- lazy list estimate -------------------------------------------------------------

    @Test
    fun `estimate is exact while the first item is visible`() {
        assertEquals(137f, estimateLazyScrollOffsetPx(0, 137, 300f, 16))
    }

    @Test
    fun `estimate never goes negative`() {
        assertEquals(0f, estimateLazyScrollOffsetPx(0, -5, 300f, 16))
    }

    @Test
    fun `estimate counts average item size plus spacing for items scrolled past`() {
        // 3 items of ~200px with 16px spacing scrolled past, plus 50px into the fourth.
        assertEquals(3 * (200f + 16f) + 50f, estimateLazyScrollOffsetPx(3, 50, 200f, 16))
    }

    // ---- ParallaxState reducer ----------------------------------------------------------

    @Test
    fun `unconfigured state reports zero shift`() {
        val state = ParallaxState()
        state.attach(FakeSource(800f))
        assertNear(0f, state.verticalShiftPx())
    }

    @Test
    fun `configure is idempotent and exposes the tokens`() {
        val state = ParallaxState()
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        assertEquals(120f, state.maxVerticalShiftPx)
        assertEquals(36f, state.maxLateralShiftPx)
    }

    @Test
    fun `bound source drives a negative shift as content scrolls down`() {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        val source = FakeSource(0f)
        state.attach(source)
        assertTrue(state.isDriven)
        assertNear(0f, state.verticalShiftPx())

        source.offset = 10f
        assertNear(-3f, state.verticalShiftPx(), tolerance = 0.05f)
        assertEquals(10f, state.scrollOffsetPx)
    }

    @Test
    fun `detach freezes the layer where it was`() {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        val source = FakeSource(200f)
        state.attach(source)
        val before = state.verticalShiftPx()

        state.detach(source)
        assertFalse(state.isDriven)
        assertEquals(before, state.verticalShiftPx())
        assertEquals(200f, state.scrollOffsetPx)
    }

    @Test
    fun `detaching a source that is no longer active is ignored`() {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        val old = FakeSource(500f)
        val new = FakeSource(0f)
        state.attach(old)
        state.attach(new)
        // The old screen disposes at the end of the transition, after the new one attached.
        state.detach(old)
        assertTrue(state.isDriven)
        assertEquals(0f, state.scrollOffsetPx)
    }

    @Test
    fun `first attach is continuous with the resting layer too`() {
        // Cold start: nothing bound, layer at rest. Binding a list that is already scrolled
        // must not jump the background either; the settle animation brings it into place.
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        state.attach(FakeSource(600f))
        assertNear(0f, state.verticalShiftPx())
        assertTrue(state.continuityCorrectionPx > 50f)
    }

    @Test
    fun `attach is visually continuous and parks the difference as a correction`() = runTest {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        val home = FakeSource(600f)
        state.attach(home)
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 0)) }
        val visibleOnHome = state.verticalShiftPx()
        assertTrue(visibleOnHome < -50f)

        val tools = FakeSource(0f)
        state.attach(tools)
        // Same frame: nothing moved.
        assertNear(visibleOnHome, state.verticalShiftPx())
        // The whole difference is the correction; the raw shift for a fresh list is zero.
        assertNear(visibleOnHome, state.continuityCorrectionPx)
    }

    @Test
    fun `attach bumps the generation only when the source actually changes`() {
        val state = ParallaxState()
        val source = FakeSource(0f)
        assertEquals(0, state.attachGeneration)
        state.attach(source)
        assertEquals(1, state.attachGeneration)
        state.attach(source)
        assertEquals(1, state.attachGeneration)
        state.attach(FakeSource(0f))
        assertEquals(2, state.attachGeneration)
    }

    @Test
    fun `settling the correction returns the layer to its true position`() = runTest {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        state.attach(FakeSource(600f))
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 0)) }
        val tools = FakeSource(0f)
        state.attach(tools)
        assertTrue(state.continuityCorrectionPx != 0f)

        // tween(0) is what the background passes when animations are disabled: snap.
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 0)) }
        assertNear(0f, state.continuityCorrectionPx)
        assertNear(0f, state.verticalShiftPx())
    }

    @Test
    fun `settling animates the correction monotonically to zero`() = runTest {
        val state = ParallaxState(verticalFactor = 0.3f)
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        state.attach(FakeSource(600f))
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 0)) }
        state.attach(FakeSource(0f))
        val start = state.continuityCorrectionPx
        assertTrue(start < 0f)
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 400)) }
        assertNear(0f, state.continuityCorrectionPx)
        assertNear(0f, state.verticalShiftPx())
    }

    @Test
    fun `settling with nothing to settle is a no-op`() = runTest {
        val state = ParallaxState()
        withContext(StepFrameClock()) { state.settleCorrection(tween(durationMillis = 0)) }
        assertEquals(0f, state.continuityCorrectionPx)
    }

    @Test
    fun `lateral tab position maps to fraction and shift`() {
        val state = ParallaxState()
        state.configure(maxVerticalShiftPx = 120f, maxLateralShiftPx = 36f)
        assertEquals(0.5f, state.lateralFraction)
        state.setLateralTabPosition(0, 6)
        assertEquals(0f, state.lateralFraction)
        assertNear(18f, state.lateralShiftPx())
        state.setLateralTabPosition(5, 6)
        assertEquals(1f, state.lateralFraction)
        assertNear(-18f, state.lateralShiftPx())
    }

    @Test
    fun `default tokens are subtle`() {
        assertTrue(ParallaxDefaults.VerticalFactor in 0.2f..0.4f)
        assertTrue(ParallaxDefaults.MaxVerticalShift.value <= 48f)
        assertTrue(ParallaxDefaults.MaxLateralShift.value <= 16f)
    }
}
