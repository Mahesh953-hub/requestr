package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for [RelativeTimeFormatter], the pill on Home's "Recently opened" rows. Pure
 * JVM: `now` is injected, so every bucket edge is exercised deterministically.
 */
class RelativeTimeFormatterTest {

    private val now = 1_800_000_000_000L
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(elapsed: Long) = RelativeTimeFormatter.format(now - elapsed, now)

    @Test
    fun `mockup examples render exactly as the design shows`() {
        assertEquals("3m ago", ago(3 * minute))
        assertEquals("5m ago", ago(5 * minute))
        assertEquals("12m ago", ago(12 * minute))
        assertEquals("25m ago", ago(25 * minute))
    }

    @Test
    fun `under one minute is just now, including zero and a future timestamp`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59 * second + 999))
        assertEquals("just now", RelativeTimeFormatter.format(now + 5 * minute, now))
    }

    @Test
    fun `minute boundaries`() {
        assertEquals("1m ago", ago(minute))
        assertEquals("1m ago", ago(2 * minute - 1))
        assertEquals("59m ago", ago(hour - 1))
    }

    @Test
    fun `hour boundaries`() {
        assertEquals("1h ago", ago(hour))
        assertEquals("23h ago", ago(day - 1))
    }

    @Test
    fun `day boundaries`() {
        assertEquals("1d ago", ago(day))
        assertEquals("6d ago", ago(7 * day - 1))
    }

    @Test
    fun `week boundaries`() {
        assertEquals("1w ago", ago(7 * day))
        assertEquals("4w ago", ago(30 * day - 1))
    }

    @Test
    fun `month boundaries`() {
        assertEquals("1mo ago", ago(30 * day))
        assertEquals("12mo ago", ago(365 * day - 1))
    }

    @Test
    fun `year boundary and beyond`() {
        assertEquals("1y ago", ago(365 * day))
        assertEquals("3y ago", ago(3 * 365 * day + 40 * day))
    }
}
