package com.example.ui.screens

import android.content.Intent
import android.os.Build
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionState
import com.example.data.interception.InterceptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for the pure presentation mappings behind [CaptureStartPromptDialog] and
 * [CaptureStateBanner]. Robolectric only because [CaptureStartPrompt.CaInstallRequired] carries
 * an android [Intent]; nothing here touches a Context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CaptureControllerPresentationTest {

    @Test
    fun `each prompt maps to a severity and the exact remedy label`() {
        val ca = capturePromptCopy(CaptureStartPrompt.CaInstallRequired(Intent("android.settings.SETTINGS")))
        assertEquals(CapturePromptSeverity.SETUP, ca.severity)
        assertEquals("Install now", ca.confirmLabel)
        assertEquals("Install the requestr CA certificate", ca.title)

        val apps = capturePromptCopy(CaptureStartPrompt.AppSelectionRequired)
        assertEquals(CapturePromptSeverity.SETUP, apps.severity)
        assertEquals("Choose apps", apps.confirmLabel)

        val failed = capturePromptCopy(CaptureStartPrompt.Failed("VPN permission was declined, so capture cannot start."))
        assertEquals(CapturePromptSeverity.ERROR, failed.severity)
        assertEquals("Retry", failed.confirmLabel)
        assertEquals("VPN permission was declined, so capture cannot start.", failed.message)
    }

    @Test
    fun `a blank failure reason gets honest fallback copy instead of an empty dialog`() {
        val failed = capturePromptCopy(CaptureStartPrompt.Failed("   "))
        assertTrue(failed.message.isNotBlank())
    }

    @Test
    fun `severity colours follow the design-system semantic palette`() {
        assertEquals(0xFFF59E0B, CapturePromptSeverity.SETUP.colorArgb)
        assertEquals(0xFFEF4444, CapturePromptSeverity.ERROR.colorArgb)
    }

    @Test
    fun `status presentation uses green for ACTIVE, amber for ARMED, red for ERROR`() {
        assertEquals(0xFF10B981, captureStatusPresentation(InterceptionStatus.ACTIVE).colorArgb)
        assertEquals("LIVE", captureStatusPresentation(InterceptionStatus.ACTIVE).label)
        assertEquals(0xFFF59E0B, captureStatusPresentation(InterceptionStatus.ARMED).colorArgb)
        assertEquals(0xFFEF4444, captureStatusPresentation(InterceptionStatus.ERROR).colorArgb)
        assertEquals(
            captureStatusPresentation(InterceptionStatus.NEVER_CONFIGURED).colorArgb,
            captureStatusPresentation(InterceptionStatus.CONFIGURED_IDLE).colorArgb,
        )
    }

    @Test
    fun `status detail only reports fields the engine actually populated`() {
        assertEquals("", captureStatusDetail(InterceptionState()))
        assertEquals("", captureStatusDetail(InterceptionState(status = InterceptionStatus.CONFIGURED_IDLE, port = 8080)))

        val armed = InterceptionState(
            status = InterceptionStatus.ARMED, tier = ActiveTier.VPN,
            captureMethod = CaptureMethod.VPN_TUN, port = 8888,
        )
        assertEquals("VPN Tun · port 8888", captureStatusDetail(armed))

        val active = armed.copy(status = InterceptionStatus.ACTIVE, packetCount = 42, decryptedTransactionCount = 3)
        assertEquals("VPN Tun · port 8888 · 42 pkts · 3 decrypted", captureStatusDetail(active))

        val error = InterceptionState(status = InterceptionStatus.ERROR, errorText = "tun0 closed by system")
        assertEquals("tun0 closed by system", captureStatusDetail(error))
        assertEquals("", captureStatusDetail(InterceptionState(status = InterceptionStatus.ERROR, errorText = "  ")))
    }
}
