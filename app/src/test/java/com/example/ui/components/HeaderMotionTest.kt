package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scroll-reactive header maths (DESIGN-SYSTEM §14.7): the two-stage stats pill compression
 * (delta spec (g): `scrollOffset / 60f`) and the greeting fade. Pure Kotlin, plain JUnit.
 */
class HeaderMotionTest {

    private val eps = 1e-5f

    // ─── StatsPillMotion ───────────────────────────────────────────────────────────────────

    @Test
    fun `at rest the pill is uncompressed and fully visible`() {
        val frame = StatsPillMotion.resolve(0f)
        assertEquals(0f, frame.compression, eps)
        assertEquals(0f, frame.slide, eps)
        assertEquals(1f, frame.scale, eps)
        assertEquals(1f, frame.labelAlpha, eps)
        assertEquals(1f, frame.alpha, eps)
        assertEquals(0f, frame.translationFraction, eps)
        assertTrue(frame.isVisible)
    }

    @Test
    fun `stage one compresses by offset over 60 and keeps the pill in place`() {
        val half = StatsPillMotion.resolve(30f)
        assertEquals(0.5f, half.compression, eps)
        assertEquals(0f, half.slide, eps)
        assertEquals(0.5f, half.labelAlpha, eps)
        assertEquals(1f - 0.5f * (1f - StatsPillMotion.COMPRESSED_SCALE), half.scale, eps)
        assertEquals(1f, half.alpha, eps)

        val full = StatsPillMotion.resolve(60f)
        assertEquals(1f, full.compression, eps)
        assertEquals(0f, full.slide, eps)
        assertEquals(StatsPillMotion.COMPRESSED_SCALE, full.scale, eps)
        assertEquals(0f, full.labelAlpha, eps)
    }

    @Test
    fun `stage two slides the compressed pill out between 60 and 140`() {
        val mid = StatsPillMotion.resolve(100f)
        assertEquals(1f, mid.compression, eps)
        assertEquals(0.5f, mid.slide, eps)
        assertEquals(0.5f, mid.alpha, eps)
        assertEquals(-0.5f, mid.translationFraction, eps)
        assertTrue(mid.isVisible)

        val gone = StatsPillMotion.resolve(140f)
        assertEquals(1f, gone.slide, eps)
        assertEquals(0f, gone.alpha, eps)
        assertFalse(gone.isVisible)

        val further = StatsPillMotion.resolve(5000f)
        assertEquals(gone, further)
    }

    @Test
    fun `negative offsets (overscroll) clamp to rest`() {
        assertEquals(StatsPillMotion.resolve(0f), StatsPillMotion.resolve(-80f))
    }

    @Test
    fun `custom thresholds are honoured and degenerate ones cannot divide by zero`() {
        val frame = StatsPillMotion.resolve(50f, compressEndPx = 100f, slideEndPx = 200f)
        assertEquals(0.5f, frame.compression, eps)
        assertEquals(0f, frame.slide, eps)

        val degenerate = StatsPillMotion.resolve(10f, compressEndPx = 0f, slideEndPx = 0f)
        assertTrue(degenerate.compression in 0f..1f)
        assertTrue(degenerate.slide in 0f..1f)
    }

    @Test
    fun `stage one never translates and stage two never compresses further`() {
        // The slide is applied as a placement offset (so the backdrop blur samples the right
        // content); this pins that no translation leaks into the compression stage and that the
        // compression is already complete before any slide starts.
        for (px in 0..60) {
            val f = StatsPillMotion.resolve(px.toFloat())
            assertEquals("no slide at $px", 0f, f.translationFraction, eps)
        }
        for (px in 60..200) {
            val f = StatsPillMotion.resolve(px.toFloat())
            assertEquals("fully compressed at $px", 1f, f.compression, eps)
            assertEquals("alpha tracks slide at $px", 1f - f.slide, f.alpha, eps)
        }
    }

    // ─── HeaderScrollMotion ────────────────────────────────────────────────────────────────

    @Test
    fun `greeting fades linearly over the collapse distance`() {
        assertEquals(1f, HeaderScrollMotion.greetingAlpha(0f), eps)
        assertEquals(0.5f, HeaderScrollMotion.greetingAlpha(60f), eps)
        assertEquals(0f, HeaderScrollMotion.greetingAlpha(120f), eps)
        assertEquals(0f, HeaderScrollMotion.greetingAlpha(999f), eps)
        assertEquals(1f, HeaderScrollMotion.greetingAlpha(-10f), eps)
    }

    @Test
    fun `greeting slides up as it fades`() {
        assertEquals(0f, HeaderScrollMotion.greetingTranslation(0f), eps)
        assertEquals(-HeaderScrollMotion.GREETING_MAX_TRANSLATION_PX / 2f, HeaderScrollMotion.greetingTranslation(60f), eps)
        assertEquals(-HeaderScrollMotion.GREETING_MAX_TRANSLATION_PX, HeaderScrollMotion.greetingTranslation(120f), eps)
    }

    @Test
    fun `lazy list offset is exact on the first item and pinned once it scrolls off`() {
        assertEquals(0f, HeaderScrollMotion.offsetFromLazyList(0, 0, 140f), eps)
        assertEquals(37f, HeaderScrollMotion.offsetFromLazyList(0, 37, 140f), eps)
        assertEquals(140f, HeaderScrollMotion.offsetFromLazyList(0, 900, 140f), eps)
        assertEquals(140f, HeaderScrollMotion.offsetFromLazyList(3, 12, 140f), eps)
    }
}
