package com.example.interception

import android.os.ParcelFileDescriptor
import com.example.data.interception.ActiveTier
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Lifecycle-race fix regression tests: [RequestrVpnService.stopCapture] used to call
 * [InterceptionStateRepository.markStopped] *before* `cleanup()` (private, so not referenced
 * directly here) actually tore [RequestrVpnService.tunInterface] down. A UI collector reacting to
 * the state converging to [InterceptionStatus.CONFIGURED_IDLE] (e.g. re-enabling the Start
 * button) could therefore still observe a genuinely non-null `tunInterface` -- exactly the window
 * a fast stop-then-start double-tap needed to hit `startCapture()`'s `tunInterface != null` guard
 * and silently no-op the restart, leaving nothing capturing with no error surfaced.
 *
 * Two tests, deliberately for different reasons:
 * - [`stopCapture converges tunInterface to null and state to CONFIGURED_IDLE`] exercises the
 *   real, unmodified `stopCapture(reason)` production code path end-to-end (via a real, seeded
 *   [ParcelFileDescriptor] standing in for "a tun genuinely established" -- deliberately not
 *   [android.net.VpnService.Builder.establish], impossible on this emulator, a documented Redroid
 *   limitation, and out of scope for this fix) and proves the *outcome* invariant: cleanup and the
 *   state transition both genuinely happen.
 * - [`stopCapture calls cleanup before markStopped in source order`] is a deliberate, explicit
 *   source-order check standing in for the interleaving itself. `stopCapture`'s two calls are
 *   consecutive statements with no suspension point between them, so the real race window is a
 *   couple of nanoseconds -- verified empirically (reverting the fix and re-running a
 *   collector-based race test several times with `--rerun-tasks` still passed every time, because
 *   any collector coroutine's real dispatch latency dwarfs that window). `scope` is also a
 *   hardcoded `Dispatchers.IO` `CoroutineScope`, not an injectable test dispatcher, so this
 *   interleaving cannot be forced deterministically without widening this fix's scope. The source
 *   check is what actually catches a regression reliably, every time.
 */
@RunWith(RobolectricTestRunner::class)
class RequestrVpnServiceLifecycleTest {

    @After
    fun tearDown() {
        InterceptionStateRepository.resetAll()
    }

    @Test
    fun `stopCapture converges tunInterface to null and state to CONFIGURED_IDLE`() {
        val service = Robolectric.buildService(RequestrVpnService::class.java).create().get()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        writeEnd.close() // only need a genuinely non-null, closeable fd -- not a real tun.
        service.tunInterface = readEnd

        InterceptionStateRepository.markActive(sessionId = 1L, tier = ActiveTier.VPN, port = 8080)

        service.stopCapture("test stop")

        runBlocking {
            withTimeout(5_000) {
                InterceptionStateRepository.state.first { it.status == InterceptionStatus.CONFIGURED_IDLE }
            }
        }

        assertNull("tunInterface must be torn down once capture has stopped", service.tunInterface)
    }

    @Test
    fun `stopCapture calls cleanup before markStopped in source order`() {
        val source = File("src/main/java/com/example/interception/RequestrVpnService.kt").readText()
        val body = source
            .substringAfter("internal fun stopCapture(reason: String) {")
            .substringBefore("\n    private fun cleanup()")
        val cleanupIndex = body.indexOf("cleanup()")
        val markStoppedIndex = body.indexOf("InterceptionStateRepository.markStopped()")
        assertTrue("expected to find both calls inside stopCapture's body", cleanupIndex >= 0 && markStoppedIndex >= 0)
        assertTrue(
            "cleanup() must run before markStopped() inside stopCapture(reason) -- otherwise a UI " +
                "collector reacting to the state going idle can observe it while tunInterface is " +
                "still genuinely non-null, letting a fast stop-then-start double-tap hit " +
                "startCapture()'s running-guard and silently no-op the restart",
            cleanupIndex < markStoppedIndex,
        )
    }
}
