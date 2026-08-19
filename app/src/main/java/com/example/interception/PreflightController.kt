package com.example.interception

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.data.interception.ActiveTier
import com.example.data.interception.CapabilityState
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.CaptureTierPreference
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Enforces the strict pre-flight sequence before capture may start (APP_v2.md Bug 3.4).
 *
 * Order is non-negotiable:
 *  1. CA certificate must genuinely be installed — re-probed, never trusted from cache.
 *  2. Capture tier resolved by real capability, priority Root > Shizuku > VPN.
 *  3. An app selection must exist (explicit list, or "all apps" chosen).
 *  4. Only then may VPN consent be requested and capture begin.
 */
object PreflightController {

    sealed interface Result {
        /** Everything passed; [consentIntent] is non-null when the system dialog is still needed. */
        data class Ready(
            val tier: ActiveTier,
            val method: CaptureMethod,
            val port: Int,
            val consentIntent: Intent?,
        ) : Result

        /** CA missing — caller must run the install flow and re-probe. */
        data class CaRequired(val installIntent: Intent) : Result

        /** No apps chosen and "all apps" not selected. */
        data object AppSelectionRequired : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Runs the full gate. Returns the first blocking condition rather than starting capture,
     * so the UI can surface exactly what is missing.
     */
    suspend fun run(context: Context): Result {
        val prefs = SettingsDataStore.get(context)

        // Step 1 — real CA check. Probe the trust store; the cached hint is never sufficient.
        val caState = CapabilityProbe.probeCaInstalled()
        InterceptionStateRepository.updateCapabilities(ca = caState)
        val caInstalled = caState.let {
            it == com.example.data.interception.CaState.USER_STORE ||
                it == com.example.data.interception.CaState.SYSTEM_STORE
        }
        prefs.setCaInstalledHint(caInstalled)

        if (!caInstalled) {
            return Result.CaRequired(CertificateAuthority.buildInstallIntent(context))
        }

        // Step 3 (checked before consent so we never prompt for VPN then fail) — app selection.
        val filterMode = prefs.appFilterMode.first()
        val selected = prefs.selectedPackages.first()
        if (filterMode == AppFilterMode.SELECTED_APPS && selected.isEmpty()) {
            return Result.AppSelectionRequired
        }

        // Step 2 — resolve the tier from genuine probes, honouring a forced-VPN preference.
        CapabilityProbe.probeAll(context)
        val capabilities: CapabilityState = InterceptionStateRepository.capabilities.value
        val tierPreference = prefs.captureTierPreference.first()

        val tier = when (tierPreference) {
            CaptureTierPreference.FORCE_VPN_ONLY -> ActiveTier.VPN
            CaptureTierPreference.AUTO -> capabilities.resolveAutoTier()
        }

        val port = prefs.proxyPort.first()
        val method = capabilities.resolveAutoCaptureMethod()

        // Step 4 — VPN consent. Non-null intent means the caller must launch it first.
        val consent = VpnService.prepare(context)

        InterceptionStateRepository.markArmed(tier = tier, port = port, method = method)
        return Result.Ready(tier = tier, method = method, port = port, consentIntent = consent)
    }

    /** Starts capture. Only call after [run] returned [Result.Ready] with consent satisfied. */
    fun startCapture(context: Context) {
        RequestrVpnService.start(context)
    }

    fun stopCapture(context: Context) {
        RequestrVpnService.stop(context)
    }
}
