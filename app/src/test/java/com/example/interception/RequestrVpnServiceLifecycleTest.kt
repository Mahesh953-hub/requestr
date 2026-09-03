package com.example.interception

import android.os.ParcelFileDescriptor
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureLifecycleOwner
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.interception.net.SocketProtector
import java.net.DatagramSocket
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * - [`stopCapture calls cleanup before markStopped in real runtime order`] (SHOULD-FIX, code
 *   review) replaces a prior version of this test that read `RequestrVpnService.kt`'s source text
 *   off disk and asserted substring ordering of the `cleanup()`/`markStopped()` calls -- brittle
 *   (breaks on an unrelated rename/reformat, and proves nothing about actual runtime behavior).
 *
 *   An earlier attempt at replacing it swapped in a `Dispatchers.Unconfined`
 *   [RequestrVpnService.scope] and asserted what a concurrent [InterceptionStateRepository.state]
 *   collector observed at the moment `markStopped()` fired, reasoning that Unconfined resumes a
 *   waiting collector synchronously, mid-stack, inside `_state.update {}`. That turned out to be
 *   false: Unconfined dispatches through a thread-local event loop, and a *nested* resume
 *   triggered from inside an already-running Unconfined coroutine is queued rather than run
 *   immediately -- so the collector only ever observed the state after `stopCapture`'s entire
 *   launched block (cleanup, markStopped, and stopSelf) had already finished, regardless of which
 *   statement ran first inside it. Confirmed empirically: that version of the test passed even
 *   with the two statements deliberately swapped back to the buggy order.
 *
 *   [RequestrVpnService.stopCaptureStepRecorder] is a narrower, honest seam instead: it records,
 *   in real invocation order, which of the two real production calls (`cleanup()` /
 *   `markStopped()`) actually ran first, at runtime -- not by reading source text, and without
 *   relying on any coroutine-dispatcher scheduling nuance. Reverting the production ordering
 *   fails this test deterministically every run (verified).
 */
@RunWith(RobolectricTestRunner::class)
class RequestrVpnServiceLifecycleTest {

    private var protectorRegistration: SocketProtector.Registration? = null

    @After
    fun tearDown() {
        protectorRegistration?.let(SocketProtector::clear)
        InterceptionStateRepository.resetAll()
    }

    private fun installProtector(service: RequestrVpnService) {
        protectorRegistration = SocketProtector.install(object : SocketProtector.Backing {
            override fun protectSocket(socket: Socket) = true
            override fun protectDatagramSocket(socket: DatagramSocket) = true
            override fun protectFd(fd: Int) = true
        })
        service.socketProtectorRegistration = protectorRegistration
    }

    @Test
    fun `stopCapture converges tunInterface to null and state to CONFIGURED_IDLE`() {
        val service = Robolectric.buildService(RequestrVpnService::class.java).create().get()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        writeEnd.close() // only need a genuinely non-null, closeable fd -- not a real tun.
        service.tunInterface = readEnd

        InterceptionStateRepository.markActive(
            sessionId = 1L,
            tier = ActiveTier.VPN,
            port = 8080,
            lifecycleOwner = CaptureLifecycleOwner.VPN_SERVICE,
        )

        service.stopCapture("test stop")

        runBlocking {
            withTimeout(5_000) {
                InterceptionStateRepository.state.first { it.status == InterceptionStatus.CONFIGURED_IDLE }
            }
        }

        assertNull("tunInterface must be torn down once capture has stopped", service.tunInterface)
    }

    @Test
    fun `onDestroy clears the active socket protector`() {
        val controller = Robolectric.buildService(RequestrVpnService::class.java).create()
        val service = controller.get()
        installProtector(service)
        assertEquals(true, SocketProtector.isInstalled)

        controller.destroy()

        assertEquals(false, SocketProtector.isInstalled)
        protectorRegistration = null
    }

    @Test
    fun `onRevoke clears the active socket protector through stop cleanup`() {
        val service = Robolectric.buildService(RequestrVpnService::class.java).create().get()
        installProtector(service)

        service.onRevoke()
        runBlocking {
            withTimeout(5_000) {
                while (SocketProtector.isInstalled) kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(false, SocketProtector.isInstalled)
        protectorRegistration = null
    }

    @Test
    fun `onStartCommand promotes to foreground before the first DataStore read`() {
        // S1 task 11: the companion start() now uses startForegroundService(), so the service
        // must call startForeground() inside the system's window -- synchronously in
        // onStartCommand -- and never behind a suspending SettingsDataStore read. Under
        // Robolectric establish() returns null, so the launched startCapture() fails and cleans
        // up on its own; the ordering seam is what this test is about.
        val controller = Robolectric.buildService(RequestrVpnService::class.java).create()
        val service = controller.get()
        val steps = java.util.concurrent.CopyOnWriteArrayList<String>()
        val settingsRead = java.util.concurrent.CountDownLatch(1)
        service.lifecycleStepRecorder = { step ->
            steps += step
            if (step == "settingsRead") settingsRead.countDown()
        }

        service.onStartCommand(android.content.Intent(service, RequestrVpnService::class.java), 0, 1)

        assertEquals("startForeground must have run synchronously inside onStartCommand", "startForeground", steps.first())
        assertNotNull(
            "a foreground notification must be posted before any DataStore read",
            org.robolectric.Shadows.shadowOf(service).lastForegroundNotification,
        )
        assertTrue(settingsRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("startForeground", "settingsRead"), steps.take(2))
        // Let the launched start attempt finish (establish() is null under Robolectric, so it
        // ends in fail() -> ERROR) before the next test resets the repository.
        runBlocking {
            runCatching {
                withTimeout(5_000) {
                    InterceptionStateRepository.state.first { it.status == InterceptionStatus.ERROR }
                }
            }
        }
        assertNull(service.tunInterface)
    }

    @Test
    fun `health line reports relay counters and degraded network honestly`() {
        val service = Robolectric.buildService(RequestrVpnService::class.java).create().get()
        val idle = com.example.interception.relay.RelayMetrics.Snapshot(
            active = 0, peakActive = 0, queued = 0, peakQueued = 0, admitted = 0, rejected = 0, evicted = 0,
            completed = 0, failed = 0, bytesToUpstream = 0, bytesToClient = 0, rejectReasons = emptyMap(),
        )
        assertEquals("0 active \u00b7 up 0 B / down 0 B", service.healthLine(idle))
        val busy = idle.copy(active = 12, queued = 3, rejected = 2, bytesToUpstream = 1_536, bytesToClient = 3L shl 20)
        assertEquals("12 active \u00b7 3 queued \u00b7 up 1.5 KB / down 3.0 MB \u00b7 2 shed", service.healthLine(busy))
        assertEquals("2.0 GB", RequestrVpnService.formatBytes(2L shl 30))
    }

    @Test
    fun `stopCapture calls cleanup before markStopped in real runtime order`() {
        val service = Robolectric.buildService(RequestrVpnService::class.java).create().get()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        writeEnd.close() // only need a genuinely non-null, closeable fd -- not a real tun.
        service.tunInterface = readEnd

        InterceptionStateRepository.markActive(
            sessionId = 1L,
            tier = ActiveTier.VPN,
            port = 8080,
            lifecycleOwner = CaptureLifecycleOwner.VPN_SERVICE,
        )

        val callOrder = mutableListOf<String>()
        service.stopCaptureStepRecorder = { step -> callOrder.add(step) }

        service.stopCapture("test stop")

        runBlocking {
            withTimeout(5_000) {
                InterceptionStateRepository.state.first { it.status == InterceptionStatus.CONFIGURED_IDLE }
            }
        }

        assertEquals(
            "cleanup() (which nulls out tunInterface) must have genuinely run, at runtime, " +
                "before markStopped() emits CONFIGURED_IDLE -- otherwise a collector reacting to " +
                "that emission (e.g. re-enabling the Start button) can still see a stale non-null " +
                "tunInterface and a fast stop-then-start double-tap silently no-ops the restart",
            listOf("cleanup", "markStopped"),
            callOrder,
        )
    }
}
