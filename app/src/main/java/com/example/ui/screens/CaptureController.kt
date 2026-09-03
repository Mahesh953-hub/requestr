package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.api.LanDashboardServer
import com.example.data.interception.InterceptionState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.ui.components.BadgePill
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
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

    // S1 task 13: when the watcher degrades, name the trust cause if a fresh diagnostics report
    // says the CA is not trusted. `cachedReport()` never scans -- it only reads a report that a
    // screen (or a previous check) already built, so this stays cheap on the watcher loop.
    private val watcher = CaptureMethodWatcher(
        trustHeadline = {
            com.example.interception.tls.TrustDiagnostics.get(context).cachedReport()
                ?.takeIf { !it.caTrusted }?.headline
        },
    )
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

// ═══════════════════════════════════════════════════════════════════
// Designed capture-state UI (PHASE-B-REBRAND-NO-MOCKUP-COVERAGE §Capture controller).
//
// The controller above is pure lifecycle plumbing; the composables below are the designed
// surfaces for the states it produces. InterceptScreen currently renders every
// [CaptureStartPrompt] through the generic ConfirmActionDialog -- swapping that `when` block
// for [CaptureStartPromptDialog] gives each prompt a severity pill, an icon and the exact remedy
// (Install now / Choose apps / Retry) using DESIGN-SYSTEM §15's error vocabulary.
// ═══════════════════════════════════════════════════════════════════

/** Severity vocabulary for capture prompts — maps 1:1 to DESIGN-SYSTEM §15 semantic colours. */
enum class CapturePromptSeverity(val label: String, val colorArgb: Long) {
    /** A required setup step is missing; nothing is broken. */
    SETUP("SETUP", 0xFFF59E0B),
    /** A real failure the user must acknowledge or retry. */
    ERROR("ERROR", 0xFFEF4444),
}

/** Presentation model for one [CaptureStartPrompt], computed by [capturePromptCopy]. */
data class CapturePromptCopy(
    val severity: CapturePromptSeverity,
    val title: String,
    val message: String,
    val confirmLabel: String,
)

/**
 * Pure mapping from a pre-flight prompt to the words the dialog shows. Kept free of Compose so it
 * is unit-testable; the copy is the single source of truth for both the dialog and TalkBack.
 */
fun capturePromptCopy(prompt: CaptureStartPrompt): CapturePromptCopy = when (prompt) {
    is CaptureStartPrompt.CaInstallRequired -> CapturePromptCopy(
        severity = CapturePromptSeverity.SETUP,
        title = "Install the requestr CA certificate",
        message = "HTTPS traffic cannot be decrypted until the requestr root certificate is " +
            "trusted by this device. Android will ask you to name the certificate and confirm " +
            "your screen lock.",
        confirmLabel = "Install now",
    )

    CaptureStartPrompt.AppSelectionRequired -> CapturePromptCopy(
        severity = CapturePromptSeverity.SETUP,
        title = "Choose what to capture",
        message = "Capture is limited to selected apps, but no app is selected yet. Pick at " +
            "least one app, or switch to \"All apps\".",
        confirmLabel = "Choose apps",
    )

    is CaptureStartPrompt.Failed -> CapturePromptCopy(
        severity = CapturePromptSeverity.ERROR,
        title = "Capture could not start",
        message = prompt.reason.ifBlank { "Pre-flight failed without a reason. Try again." },
        confirmLabel = "Retry",
    )
}

/** Presentation model for an [InterceptionStatus] in the capture banner. */
data class CaptureStatusPresentation(
    val label: String,
    val colorArgb: Long,
    val description: String,
)

/**
 * Pure mapping from the single-source-of-truth [InterceptionStatus] to banner copy and colour.
 * Colours: green ACTIVE, amber ARMED, red ERROR, neutral otherwise (matches the wordmark dot and
 * the drawer status card so the three can never disagree).
 */
fun captureStatusPresentation(status: InterceptionStatus): CaptureStatusPresentation = when (status) {
    InterceptionStatus.NEVER_CONFIGURED -> CaptureStatusPresentation(
        label = "NOT SET UP", colorArgb = 0xFF64748B,
        description = "Interception has never been configured on this device.",
    )
    InterceptionStatus.CONFIGURED_IDLE -> CaptureStatusPresentation(
        label = "IDLE", colorArgb = 0xFF64748B,
        description = "Ready to capture. Nothing is being recorded right now.",
    )
    InterceptionStatus.ARMED -> CaptureStatusPresentation(
        label = "ARMED", colorArgb = 0xFFF59E0B,
        description = "Pre-flight passed. Waiting for VPN consent to begin capturing.",
    )
    InterceptionStatus.ACTIVE -> CaptureStatusPresentation(
        label = "LIVE", colorArgb = 0xFF10B981,
        description = "Capturing traffic.",
    )
    InterceptionStatus.ERROR -> CaptureStatusPresentation(
        label = "ERROR", colorArgb = 0xFFEF4444,
        description = "Capture stopped because of an error.",
    )
}

/** Secondary line for the banner: method · port · counters, only from real state fields. */
fun captureStatusDetail(state: InterceptionState): String {
    val parts = mutableListOf<String>()
    if (state.isActive || state.isArmed) {
        parts += state.captureMethod.displayName
        if (state.port > 0) parts += "port ${state.port}"
    }
    if (state.isActive) {
        parts += "${state.packetCount} pkts"
        if (state.decryptedTransactionCount > 0) parts += "${state.decryptedTransactionCount} decrypted"
    }
    if (state.status == InterceptionStatus.ERROR) {
        state.errorText?.takeIf { it.isNotBlank() }?.let { parts += it }
    }
    return parts.joinToString(" · ")
}

/** Upper bound for the scrollable message region in [CaptureStartPromptDialog]. */
private val PROMPT_MESSAGE_MAX_HEIGHT = 220.dp

/**
 * Designed replacement for the generic ConfirmActionDialog `when` block in InterceptScreen.
 *
 * @param onInstallCa launch the CA install intent (caller owns the ActivityResult launcher).
 * @param onChooseApps open the app-filter sheet.
 * @param onRetry re-run the pre-flight; typically `controller.requestStart()`.
 */
@Composable
fun CaptureStartPromptDialog(
    prompt: CaptureStartPrompt,
    onDismiss: () -> Unit,
    onInstallCa: (Intent) -> Unit,
    onChooseApps: () -> Unit,
    onRetry: () -> Unit,
) {
    val copy = remember(prompt) { capturePromptCopy(prompt) }
    val accent = Color(copy.severity.colorArgb)
    val icon = when (prompt) {
        is CaptureStartPrompt.CaInstallRequired -> Icons.Default.Security
        CaptureStartPrompt.AppSelectionRequired -> Icons.Default.Apps
        is CaptureStartPrompt.Failed -> Icons.Default.ErrorOutline
    }
    val onConfirm: () -> Unit = when (prompt) {
        is CaptureStartPrompt.CaInstallRequired -> ({ onDismiss(); onInstallCa(prompt.installIntent) })
        CaptureStartPrompt.AppSelectionRequired -> ({ onDismiss(); onChooseApps() })
        is CaptureStartPrompt.Failed -> ({ onDismiss(); onRetry() })
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = accent.copy(alpha = 0.45f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    BadgePill(text = copy.severity.label, color = copy.severity.colorArgb)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                // `Failed.reason` is a verbatim platform string of unbounded length (e.g. the
                // VpnService "Cannot create interface" failure on Redroid). Only the message
                // scrolls, inside a bounded region, so the header above and "Not now"/Retry
                // below are always on screen; a one-line message simply wraps at its natural
                // height because this is a max bound, not a fixed one. 220dp keeps the whole
                // dialog well under the 720x1600 viewport with room for the IME/nav bars.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PROMPT_MESSAGE_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = copy.message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Not now",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = copy.confirmLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { onConfirm() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact capture-state strip: semantic dot + status pill + method/port/counter detail, with a
 * Retry pill when the state is ERROR. Reads nothing itself — pass the collected
 * [InterceptionStateRepository.state] so this can never disagree with the FAB or the drawer.
 *
 * @param onRetry shown only in the ERROR state; typically `controller.requestStart()`.
 */
@Composable
fun CaptureStateBanner(
    state: InterceptionState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val presentation = remember(state.status) { captureStatusPresentation(state.status) }
    val detail = remember(state) { captureStatusDetail(state) }
    val accent = Color(presentation.colorArgb)
    val isError = state.status == InterceptionStatus.ERROR
    // ERROR is the one state whose detail line carries a verbatim, unbounded platform message
    // (InterceptionState.errorText) and this banner is the only place it is shown without
    // opening a sheet. The line is clamped to 2 lines by default so the strip stays a strip,
    // but when the text actually overflows (measured via onTextLayout, not guessed) a
    // "Show full error" toggle appears and expands it in place, so the full reason is always
    // reachable. Keyed on the error text so a new error re-collapses.
    var errorExpanded by remember(state.errorText) { mutableStateOf(false) }
    var errorOverflows by remember(state.errorText) { mutableStateOf(false) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = if (state.status == InterceptionStatus.ERROR) 0.5f else 0.25f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BadgePill(text = presentation.label, color = presentation.colorArgb)
                    Text(
                        text = presentation.description,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (detail.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        color = if (isError) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isError && errorExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (isError && !errorExpanded) errorOverflows = result.hasVisualOverflow
                        }
                    )
                    if (isError && (errorOverflows || errorExpanded)) {
                        Text(
                            text = if (errorExpanded) "Show less" else "Show full error",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { errorExpanded = !errorExpanded }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
            if (isError && onRetry != null) {
                GlassActionPill(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Retry capture",
                    onClick = onRetry,
                    tint = accent,
                    size = 32.dp,
                    iconSize = 16.dp,
                )
            }
        }
    }
}
