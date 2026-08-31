package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTestrRedirectModeTest {

    @Test
    fun `default state follows redirects, matching the screen's old default of true`() {
        assertTrue(RedirectFollowState().shouldFollow)
    }

    @Test
    fun `a send follows redirects when always-follow is on or a once-tap is armed`() {
        assertTrue(RedirectFollowState(alwaysFollow = true, onceArmed = false).shouldFollow)
        assertTrue(RedirectFollowState(alwaysFollow = false, onceArmed = true).shouldFollow)
        assertTrue(RedirectFollowState(alwaysFollow = true, onceArmed = true).shouldFollow)
        assertFalse(RedirectFollowState(alwaysFollow = false, onceArmed = false).shouldFollow)
    }

    @Test
    fun `tap toggles once-armed without touching always-follow`() {
        val start = RedirectFollowState(alwaysFollow = false, onceArmed = false)
        val armed = start.withOnceToggled()
        assertTrue(armed.onceArmed)
        assertFalse(armed.alwaysFollow)

        val disarmed = armed.withOnceToggled()
        assertFalse(disarmed.onceArmed)
        assertFalse(disarmed.alwaysFollow)
    }

    @Test
    fun `long-press toggles always-follow without touching once-armed`() {
        val start = RedirectFollowState(alwaysFollow = true, onceArmed = true)
        val toggled = start.withAlwaysToggled()
        assertFalse(toggled.alwaysFollow)
        assertTrue(toggled.onceArmed)
    }

    @Test
    fun `a send disarms a one-shot follow and leaves always-follow alone`() {
        val armedOnceOnly = RedirectFollowState(alwaysFollow = false, onceArmed = true)
        assertEquals(RedirectFollowState(alwaysFollow = false, onceArmed = false), armedOnceOnly.afterSend())

        val armedAndAlways = RedirectFollowState(alwaysFollow = true, onceArmed = true)
        assertEquals(RedirectFollowState(alwaysFollow = true, onceArmed = false), armedAndAlways.afterSend())
    }

    @Test
    fun `a send leaves an unarmed state unchanged`() {
        val notArmed = RedirectFollowState(alwaysFollow = true, onceArmed = false)
        assertEquals(notArmed, notArmed.afterSend())
    }
}
