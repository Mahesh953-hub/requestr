package com.example.interception

import androidx.test.core.app.ApplicationProvider
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureLifecycleOwner
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Audit fix (stop routes to the wrong engine for native-bridge VPN sessions):
 * [PreflightController.stopCapture] used to be keyed by [CaptureMethod], which doubles as
 * row-attribution. A native-bridge VPN session is owned by [RequestrVpnService] but genuinely
 * attributes captured rows to [CaptureMethod.MITM_PROXY] (see [RequestrVpnService]'s
 * `startCapture()`), so routing a *stop* off that same field hit
 * `CaptureMethod.MITM_PROXY -> InterceptionStateRepository.runStop { mitmProxy?.stop() }` --
 * tearing down this controller's unrelated singleton [PreflightController]-owned MITM proxy
 * while the real owning [RequestrVpnService] (and its live tun interface) stayed running, and
 * `runStop`'s `finally` unconditionally converged the shared state to `CONFIGURED_IDLE` anyway --
 * the FAB confidently showed "stopped" while capture kept running.
 *
 * [CaptureLifecycleOwner] exists for exactly one purpose -- deciding which `.stop()` to call --
 * so it, not [CaptureMethod], is now what [PreflightController.stopCapture] is keyed by.
 */
@RunWith(RobolectricTestRunner::class)
class PreflightControllerStopRoutingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        InterceptionStateRepository.resetAll()
    }

    @Test
    fun `native-bridge VPN session stop tears down RequestrVpnService, not the proxy`() {
        // Exactly the native-bridge state RequestrVpnService.startCapture() produces: tier=VPN
        // and lifecycleOwner=VPN_SERVICE (RequestrVpnService still owns the session) while
        // captureMethod=MITM_PROXY (only for row attribution) -- the precise conflation this fix
        // exists to stop [PreflightController.stopCapture] from mis-routing on.
        InterceptionStateRepository.markActive(
            sessionId = 42L,
            tier = ActiveTier.VPN,
            port = 8080,
            method = CaptureMethod.MITM_PROXY,
            lifecycleOwner = CaptureLifecycleOwner.VPN_SERVICE,
        )

        // Mirrors CaptureController.requestStop()'s exact call shape: read lifecycleOwner, not
        // captureMethod, off the live state.
        PreflightController.stopCapture(context, InterceptionStateRepository.state.value.lifecycleOwner)

        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedService
        assertNotNull("expected RequestrVpnService to receive a stop Intent", startedIntent)
        assertEquals(RequestrVpnService::class.java.name, startedIntent!!.component?.className)
        assertEquals(RequestrVpnService.ACTION_STOP, startedIntent.action)
        assertNull(
            "no second Intent should have been dispatched -- the proxy singleton must never be touched",
            shadowApp.nextStartedService,
        )

        // The old bug: routing off captureMethod (MITM_PROXY) hit runStop { mitmProxy?.stop() },
        // whose `finally` unconditionally called markStopped() -- confidently flipping the FAB to
        // "stopped" even though the real owning RequestrVpnService was never told to stop. The
        // fix's VPN_SERVICE branch only fires a fire-and-forget stop Intent; convergence to
        // CONFIGURED_IDLE is RequestrVpnService's own job from inside its real teardown (see
        // RequestrVpnServiceLifecycleTest), so state must still read ACTIVE right here.
        assertEquals(InterceptionStatus.ACTIVE, InterceptionStateRepository.state.value.status)
    }

    @Test
    fun `standalone MITM proxy session stop still converges synchronously via runStop`() {
        // Regression guard for the legitimate case: a real PROXY-tier session (no VPN service
        // involved at all) must still converge to CONFIGURED_IDLE right away, same as before
        // this fix -- only the *routing key* changed, not this tier's behaviour.
        InterceptionStateRepository.markActive(
            sessionId = 7L,
            tier = ActiveTier.PROXY,
            port = 8080,
            method = CaptureMethod.MITM_PROXY,
            lifecycleOwner = CaptureLifecycleOwner.MITM_PROXY_ENGINE,
        )

        PreflightController.stopCapture(context, InterceptionStateRepository.state.value.lifecycleOwner)

        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        assertNull(
            "a standalone proxy session must never dispatch an Intent to RequestrVpnService",
            shadowApp.nextStartedService,
        )
        assertEquals(InterceptionStatus.CONFIGURED_IDLE, InterceptionStateRepository.state.value.status)
    }
}
