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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.api.LanDashboardServer
import com.example.data.interception.InterceptionStateRepository
import com.example.data.prefs.SettingsDataStore
import com.example.interception.CapabilityProbe
import com.example.interception.CaptureMethodWatcher
import com.example.interception.PreflightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
    private var methodSwitchObserverJob: Job? = null

    init {
        startMethodSwitchObserver()
    }

    /**
     * Spans every capture method (VPN/Proxy/Root/Shizuku), unlike [RequestrVpnService]'s
     * `filterObserverJob` which only watches app-filter changes and only exists inside the
     * VPN-tier service. Before this, changing "Record Mode" (VPN/PROXY) or "Default Capture
     * Tier" (AUTO/FORCE_VPN_ONLY) while capture was ACTIVE or ARMED silently wrote the new
     * preference to DataStore and had zero effect on the running engine — the old tier/method
     * kept running until the user manually stopped and restarted capture (complaint #3: no
     * method-switch-while-active state machine).
     *
     * Mirrors the same drop(1)+stop/delay/start template already proven by
     * [RequestrVpnService.filterObserverJob], but lives here so it applies regardless of which
     * engine [PreflightController.run] resolves to.
     */
    private fun startMethodSwitchObserver() {
        methodSwitchObserverJob?.cancel()
        methodSwitchObserverJob = scope.launch {
            val prefs = SettingsDataStore.get(context)
            combine(
                prefs.captureRecordMode,
                prefs.captureTierPreference,
            ) { mode, tierPref -> Pair(mode, tierPref) }
                .drop(1) // skip the initial emission = current state
                .collect { (_, _) ->
                    val state = InterceptionStateRepository.state.value
                    if (state.isActive || state.isArmed) {
                        android.util.Log.i(
                            "CaptureController",
                            "Capture mode/tier preference changed while ${state.status}; restarting capture with the new resolution",
                        )
                        requestStop()
                        delay(500)
                        requestStart()
                    }
                }
        }
    }

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
                    // BUGFIX: Do NOT call markConfiguredIdle here — it wipes the ARMED
                    // state set by PreflightController.run(), causing the FAB to think
                    // nothing is happening and re-trigger start() infinitely. The ARMED
                    // state is the correct transition into consent flow.
                    //
                    // When the user denies consent, onConsentDenied() marks the error.
                    // When granted, onConsentGranted() → VPN starts → markActive().
                    onPrompt(null)
                    val consent = result.consentIntent
                    android.util.Log.i("CaptureController", "Ready: method=${result.method} port=${result.port} consent=${consent != null}")
                    if (consent != null) {
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
        PreflightController.startCapture(context, method, port)
        watcher.start(method, port)
    }

    /** Called when the user declines VPN consent — recorded as a real error, not ignored. */
    fun onConsentDenied() {
        InterceptionStateRepository.markError("VPN permission was declined, so capture cannot start.")
        onPrompt(CaptureStartPrompt.Failed("VPN permission was declined, so capture cannot start."))
    }

    fun requestStop() {
        watcher.stop()
        // Routes off lifecycleOwner, not captureMethod: a native-bridge VPN session is owned by
        // RequestrVpnService but attributes rows to CaptureMethod.MITM_PROXY, so the two must
        // not be conflated for "which stop() to call" (see CaptureLifecycleOwner's KDoc).
        val currentOwner = InterceptionStateRepository.state.value.lifecycleOwner
        PreflightController.stopCapture(context, currentOwner)
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

    /**
     * Starts the LAN dashboard (master plan §2.16) — a browsable dashboard separate from
     * [com.example.api.LocalApiServer]'s localhost-only pairing API. [lanBinding] controls
     * whether the server binds `127.0.0.1` (default) or `0.0.0.0` (explicit opt-in for other
     * devices on the LAN); auth (pairing token) applies either way. Deliberately not tied to
     * capture start/stop: a user may want the dashboard open to review already-captured traffic
     * or edit rules even while capture itself is stopped.
     */
    fun startDashboard(port: Int = LanDashboardServer.DEFAULT_PORT, lanBinding: Boolean = false) {
        LanDashboardServer.start(context, port, lanBinding)
    }

    fun stopDashboard() {
        LanDashboardServer.stop()
    }

    val isDashboardRunning: Boolean get() = LanDashboardServer.isRunning

    /** Cancels the mode/tier-preference observer. Called when the owning composable leaves. */
    fun dispose() {
        methodSwitchObserverJob?.cancel()
        methodSwitchObserverJob = null
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

    DisposableEffect(created) {
        onDispose { created.dispose() }
    }

    return created
}
