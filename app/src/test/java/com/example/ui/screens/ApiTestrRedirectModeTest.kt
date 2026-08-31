package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTestrRedirectModeTest {

    @Test
    fun `default state follows redirects, matching the screen's old default of true`() {
        assertTrue(shouldFollowRedirects(RedirectFollowState()))
    }

    @Test
    fun `always-follow on, once not armed - follows`() {
        val state = RedirectFollowState(alwaysFollow = true, onceArmed = false)
        assertTrue(shouldFollowRedirects(state))
    }

    @Test
    fun `always-follow off, once not armed - does not follow`() {
        val state = RedirectFollowState(alwaysFollow = false, onceArmed = false)
        assertFalse(shouldFollowRedirects(state))
    }

    @Test
    fun `always-follow off, once armed - follows for this send`() {
        val state = RedirectFollowState(alwaysFollow = false, onceArmed = true)
        assertTrue(shouldFollowRedirects(state))
    }

    @Test
    fun `always-follow on, once armed - still follows`() {
        val state = RedirectFollowState(alwaysFollow = true, onceArmed = true)
        assertTrue(shouldFollowRedirects(state))
    }

    @Test
    fun `tap toggles once-armed without touching always-follow`() {
        val start = RedirectFollowState(alwaysFollow = false, onceArmed = false)
        val armed = toggleOnceArmed(start)
        assertTrue(armed.onceArmed)
        assertFalse(armed.alwaysFollow)

        val disarmed = toggleOnceArmed(armed)
        assertFalse(disarmed.onceArmed)
        assertFalse(disarmed.alwaysFollow)
    }

    @Test
    fun `long-press toggles always-follow without touching once-armed`() {
        val start = RedirectFollowState(alwaysFollow = true, onceArmed = true)
        val toggled = toggleAlwaysFollow(start)
        assertFalse(toggled.alwaysFollow)
        assertTrue(toggled.onceArmed)
    }

    @Test
    fun `consuming once after send disarms a one-shot follow`() {
        val armed = RedirectFollowState(alwaysFollow = false, onceArmed = true)
        val consumed = consumeOnceAfterSend(armed)
        assertFalse(consumed.onceArmed)
        assertFalse(consumed.alwaysFollow)
    }

    @Test
    fun `consuming once after send is a no-op when not armed`() {
        val notArmed = RedirectFollowState(alwaysFollow = true, onceArmed = false)
        assertEquals(notArmed, consumeOnceAfterSend(notArmed))
    }

    @Test
    fun `consuming once after send never touches always-follow`() {
        val armedAndAlways = RedirectFollowState(alwaysFollow = true, onceArmed = true)
        val consumed = consumeOnceAfterSend(armedAndAlways)
        assertTrue(consumed.alwaysFollow)
        assertFalse(consumed.onceArmed)
    }
}
