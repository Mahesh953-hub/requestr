package com.example.data.interception

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit fix (capture start/stop state desync): unit tests for the [InterceptionStateRepository]
 * invariants [InterceptionStateRepository.runStop] and [InterceptionStateRepository.runStart].
 *
 * Pure functions over [InterceptionStateRepository]'s own [MutableStateFlow] — no Context, no
 * real engine I/O — which is exactly the seam this fix introduced so the invariant itself (not
 * [com.example.interception.PreflightController]'s real per-tier engine calls) is testable.
 */
class InterceptionStateRepositoryTest {

    @After
    fun tearDown() {
        InterceptionStateRepository.resetAll()
    }

    // ---- runStop ----

    @Test
    fun `runStop converges to CONFIGURED_IDLE after a clean teardown`() {
        InterceptionStateRepository.markActive(sessionId = 1L, tier = ActiveTier.PROXY, port = 8080)

        InterceptionStateRepository.runStop { /* pretend teardown succeeded */ }

        val state = InterceptionStateRepository.state.value
        assertEquals(InterceptionStatus.CONFIGURED_IDLE, state.status)
        assertEquals(ActiveTier.NONE, state.tier)
        assertNull(state.sessionId)
    }

    @Test
    fun `runStop still converges to CONFIGURED_IDLE even when teardown throws`() {
        // This is the exact bug: MITM_PROXY/ROOT_IPTABLES/SHIZUKU_TUN/HTTP_PROXY had no
        // guaranteed markStopped() call at all, so a stuck ACTIVE state after the first stop
        // was reproducible on device. The fix must converge regardless of how teardown behaves.
        InterceptionStateRepository.markActive(sessionId = 2L, tier = ActiveTier.ROOT, port = 8080)

        val thrown = runCatching {
            InterceptionStateRepository.runStop { throw IllegalStateException("iptables -D failed") }
        }

        assertTrue("the teardown exception must still propagate, not be swallowed", thrown.isFailure)
        val state = InterceptionStateRepository.state.value
        assertEquals(InterceptionStatus.CONFIGURED_IDLE, state.status)
        assertEquals(ActiveTier.NONE, state.tier)
    }

    @Test
    fun `runStop from ARMED (never actually started) still converges cleanly`() {
        InterceptionStateRepository.markArmed(tier = ActiveTier.SHIZUKU, port = 8080)

        InterceptionStateRepository.runStop { }

        assertEquals(InterceptionStatus.CONFIGURED_IDLE, InterceptionStateRepository.state.value.status)
    }

    // ---- runStart ----

    @Test
    fun `runStart returns true and does not touch state when the engine reports success`() {
        InterceptionStateRepository.markArmed(tier = ActiveTier.PROXY, port = 8080)

        val started = InterceptionStateRepository.runStart("MITM_PROXY") {
            // Real engines call markActive() themselves on success; runStart must not
            // second-guess that by also mutating state on a true result.
            InterceptionStateRepository.markActive(sessionId = 3L, tier = ActiveTier.PROXY, port = 8080)
            true
        }

        assertTrue(started)
        assertEquals(InterceptionStatus.ACTIVE, InterceptionStateRepository.state.value.status)
    }

    @Test
    fun `runStart converts a false return into a clean ERROR instead of a stuck ARMED state`() {
        // Reproduces RootIptablesEngine/ShizukuTunEngine.start() returning false (a real
        // su/binder failure) with nothing above them to notice -- previously left the ARMED
        // state PreflightController.run() had already set stuck forever.
        InterceptionStateRepository.markArmed(tier = ActiveTier.ROOT, port = 8080)

        val started = InterceptionStateRepository.runStart("ROOT_IPTABLES") { false }

        assertFalse(started)
        val state = InterceptionStateRepository.state.value
        assertEquals(InterceptionStatus.ERROR, state.status)
        assertTrue(state.errorText!!.contains("ROOT_IPTABLES"))
    }

    @Test
    fun `runStart converts a thrown exception into a clean ERROR instead of a stuck ARMED state`() {
        // Reproduces MitmProxyEngine/HttpProxyEngine's ServerSocket(port) throwing
        // IOException (e.g. address already in use) with nothing above them to catch it.
        InterceptionStateRepository.markArmed(tier = ActiveTier.PROXY, port = 8080)

        val started = InterceptionStateRepository.runStart("MITM_PROXY") {
            throw java.io.IOException("Address already in use")
        }

        assertFalse(started)
        val state = InterceptionStateRepository.state.value
        assertEquals(InterceptionStatus.ERROR, state.status)
        assertTrue(state.errorText!!.contains("Address already in use"))
    }

    @Test
    fun `runStart never swallows the failing engine's own exception as a silent no-op`() {
        InterceptionStateRepository.markArmed(tier = ActiveTier.PROXY, port = 8080)

        val started = InterceptionStateRepository.runStart("HTTP_PROXY") {
            throw RuntimeException()
        }

        // No message on the exception -- must still fall back to a real, non-blank reason
        // rather than an empty/null errorText that would render nothing in the UI.
        assertFalse(started)
        assertTrue(InterceptionStateRepository.state.value.errorText!!.isNotBlank())
    }
}
