package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.data.interception.InterceptionStateRepository
import com.example.data.prefs.SettingsDataStore
import com.example.interception.CapabilityProbe
import com.example.interception.CaptureMethodWatcher
import com.example.interception.PreflightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the Intercept screen should surface after a start attempt.
 *
 * Every value here corresponds to a real blocking condition returned by [PreflightController];
 * none of them are cosmetic. v2.1's FAB flipped a boolean and claimed capture had started even
 * though no VPN existed (APP_v2.md Bug 3.4 / 3.5).
 */
sealed interface CaptureStartPrompt {
    /** CA is genuinely absent from the trust store; the install intent must be launched. */
    data class CaInstallRequired(val installIntent: Intent) : CaptureStartPrompt

    /** "Selected apps" mode is active but no package was chosen. */
    data object AppSelectionRequired : CaptureStartPrompt

    /** Pre-flight failed for a real reason, surfaced verbatim. */
    data class Failed(val reason: String) : CaptureStartPrompt
}

/**
 * Drives the capture lifecycle from the UI: pre-flight -> VPN consent -> start, and stop.
 *
 * Held deliberately thin — all decisions live in [PreflightController] so the same gate applies
 * regardless of entry point (FAB, Home CTA, deeplink).
 */
class CaptureController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val launchConsent: (Intent) -> Unit,
    private val onPrompt: (CaptureStartPrompt?) -> Unit,
) {

    private val watcher = CaptureMethodWatcher()

    /**
     * Runs the full gate and starts capture only if every precondition genuinely passes.
     * Any blocking condition is reported through [onPrompt] instead of being swallowed.
     */
    fun requestStart() {
        scope.launch {
            when (val result = PreflightController.run(context)) {
                is PreflightController.Result.CaRequired -> {
                    onPrompt(CaptureStartPrompt.CaInstallRequired(result.installIntent))
                }

                PreflightController.Result.AppSelectionRequired -> {
                    onPrompt(CaptureStartPrompt.AppSelectionRequired)
                }

                is PreflightController.Result.Failed -> {
                    InterceptionStateRepository.markError(result.reason)
                    onPrompt(CaptureStartPrompt.Failed(result.reason))
                }

                is PreflightController.Result.Ready -> {
                    // Fix 04: transition NEVER_CONFIGURED → CONFIGURED_IDLE so the
                    // HomeScreen power module and InterceptScreen header stop showing
                    // "Set up Interception" after the user has granted permissions.
                    InterceptionStateRepository.markConfiguredIdle(result.port)
                    onPrompt(null)
                    val consent = result.consentIntent
                    if (consent != null) {
                        // System VPN dialog still owed. Capture starts from the result callback,
                        // never before the user has actually granted consent.
                        launchConsent(consent)
                    } else {
                        startCaptureAndWatcher(result.method, result.port)
                    }
                }
            }
        }
    }

    /** Called once the system VPN consent dialog returns OK. */
    fun onConsentGranted() {
        val state = InterceptionStateRepository.state.value
        startCaptureAndWatcher(state.captureMethod, state.port)
    }

    private fun startCaptureAndWatcher(method: com.example.data.interception.CaptureMethod, port: Int) {
        PreflightController.startCapture(context)
        watcher.start(method, port)
    }

    /** Called when the user declines VPN consent — recorded as a real error, not ignored. */
    fun onConsentDenied() {
        InterceptionStateRepository.markError("VPN permission was declined, so capture cannot start.")
        onPrompt(CaptureStartPrompt.Failed("VPN permission was declined, so capture cannot start."))
    }

    fun requestStop() {
        watcher.stop()
        PreflightController.stopCapture(context)
    }

    /** Re-probes the trust store after the CA install activity returns. Never assumes success. */
    fun reprobeAfterCaInstall() {
        scope.launch {
            val state = CapabilityProbe.probeCaInstalled()
            InterceptionStateRepository.updateCapabilities(ca = state)
            SettingsDataStore.get(context).setCaInstalledHint(
                state == com.example.data.interception.CaState.USER_STORE ||
                    state == com.example.data.interception.CaState.SYSTEM_STORE
            )
        }
    }

    fun refreshCapabilities() {
        scope.launch { CapabilityProbe.probeAll(context) }
    }
}

/** Wires a [CaptureController] with the activity-result launchers it needs. */
@Composable
fun rememberCaptureController(
    onPrompt: (CaptureStartPrompt?) -> Unit,
): CaptureController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<CaptureController?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            controller?.onConsentGranted()
        } else {
            controller?.onConsentDenied()
        }
    }

    val created = remember(context, scope) {
        CaptureController(
            context = context,
            scope = scope,
            launchConsent = { intent -> consentLauncher.launch(intent) },
            onPrompt = onPrompt,
        ).also { controller = it }
    }
    return created
}
