package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.example.data.interception.CaptureLogRepository
import com.example.data.models.AppPackageItem
import com.example.data.repository.SettingsRepository
import com.example.data.repository.AppUtils
import com.example.data.repository.P12ImportErrorClassifier
import com.example.data.repository.P12ImportFailureKind
import com.example.data.repository.TrafficInterceptorRepository
import com.example.data.interception.CaState
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import com.example.interception.CapabilityProbe
import com.example.interception.CertificateAuthority
import com.example.interception.CaP12Exchange
import com.example.interception.TargetAppDatabaseBrowser
import com.example.interception.isSafeSqlIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.components.BadgePill
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.CdpCard
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.RuntimeInstrumentationCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.DynamicGlassSwitch
import com.example.ui.components.RequestrSegmentedTabs
import com.example.data.models.UserSettingsState
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.GlassStyleOption
import com.example.ui.theme.GlassStyles
import com.example.ui.theme.GlassTuneRanges
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReRunWizard: () -> Unit = {}
) {
    val settings by SettingsRepository.settings.collectAsState()
    // Real capability state and a real CA read; v2.1 showed a hardcoded serial/fingerprint
    // and six diagnostics rows that always claimed success (APP_v2.md Bug 3.1 / 10).
    val settingsContext = LocalContext.current
    val settingsScope = rememberCoroutineScope()

    // Task 13: workspace export/import — WorkspaceExporter was complete and real but had zero
    // call sites anywhere in the app. This launcher reads the picked file and runs it through
    // WorkspaceExporter.importWorkspace, surfacing a real summary (including errors) rather
    // than swallowing them.
    val workspaceImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        settingsScope.launch {
            val json = runCatching {
                settingsContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (json == null) {
                android.widget.Toast.makeText(settingsContext, "Could not read the selected file.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = runCatching {
                com.example.data.export.WorkspaceExporter.importWorkspace(settingsContext, json)
            }.getOrNull()
            if (result == null) {
                android.widget.Toast.makeText(settingsContext, "Import failed: file is not a valid workspace export.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val message = if (result.errors.isNotEmpty()) {
                "${result.summary}: ${result.errors.joinToString("; ")}"
            } else {
                result.summary
            }
            android.widget.Toast.makeText(settingsContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // `.p12` CA import (master plan §3 LOW-ADD): CaP12Exchange.importP12 is complete and
    // unit-tested (CaP12ExchangeTest) but had no UI counterpart to the existing .p12 export
    // button. This launcher only reads the picked file's bytes; the password prompt (rendered
    // next to the CA export row, below) drives the actual import via CaP12Exchange.
    var p12ImportPendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var p12ImportPendingFileName by remember { mutableStateOf<String?>(null) }
    var p12ImportPickError by remember { mutableStateOf<String?>(null) }
    val p12ImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            // User backed out of the picker — a real, distinct state, not a crash or a silent no-op.
            p12ImportPickError = "No file selected — the picker was cancelled."
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            settingsContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            p12ImportPickError = "Could not read the selected file — it may be unreadable or empty."
            p12ImportPendingBytes = null
            return@rememberLauncherForActivityResult
        }
        p12ImportPickError = null
        p12ImportPendingBytes = bytes
        p12ImportPendingFileName = runCatching {
            settingsContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    val capabilities by InterceptionStateRepository.capabilities.collectAsState()

    // VPN app picker observability (master plan §3 LOW-ADD, C8) — mirrors the same
    // repository-level filter state InterceptScreen.kt's picker reads, so this screen's toggle
    // and counts stay in sync with whatever that screen last loaded (and vice versa).
    val appPickerInternetOnly by TrafficInterceptorRepository.internetOnlyFilterEnabled.collectAsState()
    val appPickerTotal by TrafficInterceptorRepository.totalInstalledAppCount.collectAsState()
    val appPickerFilteredOut by TrafficInterceptorRepository.filteredOutAppCount.collectAsState()
    val appPickerApps by TrafficInterceptorRepository.installedApps.collectAsState()
    // B9: the port editable here MUST be the same value PreflightController/InterceptScreen
    // actually read (SettingsDataStore), not the separate, unconsumed SettingsRepository copy
    // that previously made this field a no-op on the real capture engine.
    val interceptionDataStore = remember(settingsContext) { com.example.data.prefs.SettingsDataStore.get(settingsContext) }
    val realProxyPort by interceptionDataStore.proxyPort.collectAsState(initial = com.example.data.prefs.SettingsDataStore.DEFAULT_PROXY_PORT)
    // B9(d): capture-tier preference and "remember last app filter" are both real
    // SettingsDataStore values PreflightController.run() actually reads at the moment
    // interception starts (see PreflightController.kt lines ~65-78) but neither previously
    // had ANY Settings-page UI control — surface both here so they're not silently
    // un-editable defaults forever.
    val captureTierPref by interceptionDataStore.captureTierPreference.collectAsState(
        initial = com.example.data.prefs.CaptureTierPreference.AUTO
    )
    val rememberLastAppFilter by interceptionDataStore.rememberLastAppFilter.collectAsState(initial = true)
    // §3 (C2) — weak-network simulation. Collected from the Flow (not a local-only toggle) so
    // this reflects process death and any external change; the three text inputs below are
    // `remember(networkCondition.*)`-keyed so an external update overwrites in-progress local
    // edits the same way `proxyPortInput` already does for `realProxyPort` above.
    val networkCondition by interceptionDataStore.networkCondition.collectAsState(
        initial = com.example.interception.NetworkConditionInjector.NetworkCondition.NONE
    )
    var networkLatencyInput by remember(networkCondition.latencyMs) { mutableStateOf(networkCondition.latencyMs.toString()) }
    var networkJitterInput by remember(networkCondition.jitterMs) { mutableStateOf(networkCondition.jitterMs.toString()) }
    var networkLossInput by remember(networkCondition.packetLossPercent) { mutableStateOf(networkCondition.packetLossPercent.toString()) }
    val capturedCount by remember(settingsContext) {
        CaptureLogRepository.observeCount(settingsContext)
    }.collectAsState(initial = 0)
    // `.p12` import (§3 LOW-ADD) and "Regenerate CA" both replace the active CA keystore on
    // disk; bumping this key forces caSummary's produceState block to re-read the new
    // certificate instead of continuing to show the one that was active when Settings first
    // composed, so a successful import/regenerate is visibly reflected without leaving the screen.
    var caRefreshTrigger by remember { mutableStateOf(0) }
    val caSummary by produceState<Triple<String, String, String>?>(initialValue = null, caRefreshTrigger) {
        value = runCatching {
            val cert = CertificateAuthority.getOrCreate(settingsContext).certificate
            val fp = java.security.MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            Triple(
                cert.subjectX500Principal.name,
                cert.serialNumber.toString(16).uppercase(),
                fp
            )
        }.getOrNull()
    }
    LaunchedEffect(Unit) { CapabilityProbe.probeAll(settingsContext) }

    var userNameInput by remember(settings.userName) { mutableStateOf(settings.userName) }
    var proxyPortInput by remember(realProxyPort) { mutableStateOf(realProxyPort.toString()) }
    var upstreamProxyInput by remember { mutableStateOf("") }

    // B11: state for the "Install to System Store" button (root/Shizuku CA promotion).
    var promoteToSystemInProgress by remember { mutableStateOf(false) }
    var promoteToSystemResult by remember { mutableStateOf<String?>(null) }

    // B9(c): "Auto-start interceptor on launch" / "Enforce TLS v1.3 Strict Ciphers" /
    // "Follow HTTP 3xx Redirects" local-only toggles removed from the INTERCEPTION &
    // PROXY ENGINE card. Investigated real wiring targets: CaptureController.requestStart()
    // has no "auto-start on app launch" hook anywhere; MitmProxyEngine (the only TLS
    // termination code in the codebase) is never instantiated by any live capture path
    // (RequestrVpnService only drives PacketPump, which never touches TLS ciphers), so
    // there is no real cipher-suite enforcement point to attach "Strict TLS v1.3" to; and
    // HTTP redirect-following is already a real, separately-wired per-request toggle in
    // ApiTestrScreen (passed into ApiTestrRepository.executeRequest) — this card's copy
    // was a third, disconnected instance of the same concept with no adjacent real field
    // in UserSettingsState. No genuinely real target existed for any of the three, so per
    // the plan's stub-or-remove standard they are removed rather than left as switches
    // that flip with zero effect.

    // Settings is the primary scroll container here: bind it to the app-wide parallax layer.
    val settingsListState = rememberLazyListState()
    DriveParallaxFrom(settingsListState)

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader(
            title = "Settings & Config",
            onBack = onBack
        )

        LazyColumn(
            state = settingsListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Profile & Identity
            item {
                SettingsSectionHeader("OPERATOR & IDENTITY")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Operator Alias / Username", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = {
                                userNameInput = it
                                SettingsRepository.updateUserName(it)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // Section 2: Appearance — Phase B (theme family × accent × glass, all live)
            item {
                SettingsSectionHeader("Appearance")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            // Appearance mode — System / Light / Dark
            item {
                AppearanceSelector(
                    currentMode = settings.appearanceMode,
                    onModeSelected = { mode ->
                        SettingsRepository.updateAppearanceMode(mode)
                        // v3.1 B1: switching the appearance-mode family (System/Light/Dark)
                        // recalls that family's remembered theme rather than always
                        // re-writing a hardcoded value — LIGHT restores selectedLightTheme,
                        // DARK restores selectedDarkTheme, and this call never mutates
                        // either "remembered" slot itself (see updateThemeSelection doc).
                        when (mode) {
                            "LIGHT" -> SettingsRepository.updateTheme(settings.selectedLightTheme)
                            "DARK" -> SettingsRepository.updateTheme(settings.selectedDarkTheme)
                            "SYSTEM" -> { /* theme follows system via MainActivity's isSystemInDarkTheme() check */ }
                        }
                    }
                )
            }

            // Theme family picker — all nine families, filtered by the appearance mode.
            item {
                ThemeFamilyPicker(
                    settings = settings,
                    onThemeSelected = { theme -> SettingsRepository.updateThemeSelection(theme) }
                )
            }

            // Accent Color Grid
            item {
                AccentColorGrid(
                    currentAccent = settings.selectedAccent,
                    appearanceMode = settings.appearanceMode,
                    onAccentSelected = SettingsRepository::updateAccent
                )
            }

            // Live preview of the effective theme family × accent, rendered through the
            // same buildColorScheme() the app theme wrapper uses.
            item {
                ThemeLivePreviewCard(settings = settings)
            }

            // Density — reads/writes the same densityMode LocalAppSpacing is derived from.
            item {
                DensitySelector(
                    currentMode = settings.densityMode,
                    onModeSelected = { SettingsRepository.updateDensityMode(it) }
                )
            }

            // v6.0 transplant (manifest §5.10): LiveRoundnessSettingCard replaces the
            // basic corner-radius slider with presets, live preview, and themed slider.
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("UI Roundness & Geometry") }
            item {
                LiveRoundnessSettingCard(
                    cornerRadiusDp = settings.cornerRadiusDp,
                    onCornerRadiusChange = { SettingsRepository.updateCornerRadius(it) }
                )
            }

            // v6.0 transplant (manifest §5.10): Navigation & Header glass modifiers
            // (header top offset, header/nav opacity, glass styles, Tune Glass sliders,
            // FAB spacing, hidden items)
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Navigation & Header Glass") }
            item {
                NavigationAndHeaderModifiersCard(
                    settings = settings,
                    onUpdateNavOpacity = { SettingsRepository.updateNavBarOpacity(it) },
                    onUpdateNavBlurStyle = { SettingsRepository.updateNavBarBlurEffect(it) },
                    onToggleHiddenNavItem = { SettingsRepository.toggleNavHiddenItem(it) },
                    onUpdateHeaderTopPadding = { SettingsRepository.updateHeaderTopPadding(it) },
                    onUpdateHeaderOpacity = { SettingsRepository.updateHeaderOpacity(it) },
                    onUpdateHeaderBlurStyle = { SettingsRepository.updateHeaderBlurStyle(it) },
                    onUpdateFabBottomSpacing = { SettingsRepository.updateFabBottomSpacing(it) },
                    onUpdateGlassTuneBlur = { SettingsRepository.updateGlassTuneBlur(it) },
                    onUpdateGlassTuneTintOpacity = { SettingsRepository.updateGlassTuneTintOpacity(it) },
                    onUpdateGlassTuneBorderOpacity = { SettingsRepository.updateGlassTuneBorderOpacity(it) },
                    onUpdateGlassTuneShadowDp = { SettingsRepository.updateGlassTuneShadowDp(it) }
                )
            }

            // B3: the old "Typography / Surfaces / Borders / Icons / States" placeholder
            // list (TokenPlaceholders) rendered five inert labels with zero interactivity,
            // zero backing UserSettingsState fields, and zero rendering hook anywhere in the
            // app's theme wrapper. Removed per the plan's stub-or-remove standard — there was
            // no underlying capability to attach real behavior to.

            // Re-run Setup Wizard — Fix 03
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item {
                SettingRow(
                    title = "Re-run Setup Wizard",
                    description = "Restart the onboarding flow to re-configure your alias, theme, and permissions.",
                    onClick = {
                        settingsScope.launch {
                            SettingsRepository.setOnboardingCompleted(false)
                            SettingsRepository.updateUserName("")
                        }
                        onReRunWizard()
                    }
                )
            }

            // Section 3: UI & UX — Fix 02
            item {
                SettingsSectionHeader("UI & UX")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            // Interaction
            item { SubsectionTitle("Interaction") }
            item { SwitchPreference("Swipe to Delete", settings.swipeToDeleteEnabled, "Swipe items left or right to remove them") { SettingsRepository.updateSwipeToDelete(it) } }
            item { SwitchPreference("Haptic Feedback", settings.hapticFeedbackEnabled, "Short vibration on touch interactions") { SettingsRepository.updateHapticFeedback(it) } }
            item { SwitchPreference("Gesture Navigation", settings.gestureNavigationEnabled, "Allow edge-swipe to open the navigation drawer (disable if it conflicts with your system back gesture)") { SettingsRepository.updateGestureNavigation(it) } }

            // Display Behavior: density moved up into the Appearance section (DensitySelector)
            // so every visual preference lives in one place.
            // Picture-in-Picture removed (B2): no video/camera surface exists anywhere in this
            // app's feature set — only an audio-only mini-player with no PiP manifest support —
            // so the toggle had no applicable target behavior and was removed per the plan's
            // stub-or-remove directive rather than kept as a dead switch.
            // B6: "Table Mode" removed — zero consumer anywhere in the codebase, and it
            // directly overlaps with the not-yet-built adaptive/multi-pane tablet layout
            // (Part C roadmap item #9). Per the plan, this must stay removed until #9 is
            // actually implemented, then reintroduced as a real, functional toggle.

            // Update Tips
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Update Tips") }
            item {
                SelectPreference(
                    "Notification Policy",
                    settings.updateNotificationPolicy,
                    "When to notify about app updates",
                    listOf("EVERY_UPDATE" to "Every Update", "MAJOR_ONLY" to "Major Only", "NEVER" to "Never")
                ) { SettingsRepository.updateNotificationPolicy(it) }
            }

            // UI Preferences
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("UI Preferences") }
            item { SwitchPreference("Animation", settings.animationEnabled, "Enable animated transitions throughout the app") { SettingsRepository.updateAnimationEnabled(it) } }
            item {
                SelectPreference(
                    "Transition Behavior",
                    settings.transitionBehavior,
                    "Animation style for screen transitions",
                    listOf("STANDARD" to "Standard", "REDUCED" to "Reduced", "NONE" to "None")
                ) { SettingsRepository.updateTransitionBehavior(it) }
            }
            item {
                SelectPreference(
                    "Display Scale",
                    settings.displayScale,
                    "Adjust the overall UI scaling",
                    listOf("SMALL" to "Small", "NORMAL" to "Normal", "LARGE" to "Large")
                ) { SettingsRepository.updateDisplayScale(it) }
            }

            // FEATURES section — Fix 02
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item {
                SettingsSectionHeader("FEATURES")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            // Recording
            item { SubsectionTitle("Recording") }
            item { SwitchPreference("Auto Recording", settings.autoRecordingEnabled, "Automatically enter recording state") { SettingsRepository.updateAutoRecording(it) } }
            // Complaint #4 fix: the old "Traffic Log" toggle here was pure dead UI -- nothing
            // ever read settings.trafficLogEnabled to gate any behaviour. It sat right next to
            // the genuinely-wired "Traffic Capture" toggle below (Traffic & Network section,
            // which really does gate persistence via RequestrVpnService -> CaptureEngine), so
            // the two easily-confused switches made it look like traffic logging could be
            // turned on/off from two different places when only one of them actually did
            // anything. Removed rather than wired up, since the real behaviour the user wants
            // already exists under "Traffic Capture".
            item { SwitchPreference("Recording Notifications", settings.recordingNotificationsEnabled, "Show persistent notification during recording") { SettingsRepository.updateRecordingNotifications(it) } }

            // Remote Control
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Remote Control") }
            item { SwitchPreference("Remote Control", settings.remoteControlEnabled, "Allow remote devices to control recording") { SettingsRepository.updateRemoteControl(it) } }
            item { SwitchPreference("Remote Commands", settings.remoteCommandsEnabled, "Accept command requests from paired devices") { SettingsRepository.updateRemoteCommands(it) } }

            // Device Discovery
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Device Discovery") }
            item { SwitchPreference("LAN Device Discovery", settings.lanDeviceDiscoveryEnabled, "Discover other requestr instances on local network") { SettingsRepository.updateLanDiscovery(it) } }
            item { SwitchPreference("Device Broadcasting", settings.deviceBroadcastingEnabled, "Broadcast this device's presence on LAN") { SettingsRepository.updateDeviceBroadcasting(it) } }

            // Traffic & Network
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Traffic & Network") }
            item { SwitchPreference("Traffic Capture", settings.trafficCaptureEnabled, "When off, no captured transaction is written to the traffic log at all") { SettingsRepository.updateTrafficCapture(it) } }
            item { SwitchPreference("Request Logging", settings.requestLoggingEnabled, "Store request headers and body for each captured transaction") { SettingsRepository.updateRequestLogging(it) } }
            item { SwitchPreference("Response Logging", settings.responseLoggingEnabled, "Store response headers and body for each captured transaction") { SettingsRepository.updateResponseLogging(it) } }

            // B10: "Advanced Features" section (Experimental Features, Developer Options)
            // removed entirely. Audited both items individually: zero real backing
            // implementation exists for either (no feature-flag gate reads
            // experimentalFeaturesEnabled anywhere; no debug/dev-tools surface reads
            // developerOptionsEnabled anywhere). The "ECOSYSTEM & DEEP LINKS" card below
            // was checked as a candidate relocation target for "genuinely real advanced
            // capability" per the plan's wording, but it is purely static/informational
            // (URI scheme description only, no interactive deeplink management UI) — there
            // was nothing genuinely real to relocate, so per the plan's fallback both
            // toggles are removed rather than left as an unverified catch-all bucket.

            // Section 4: Network & Proxy Engine
            item {
                SettingsSectionHeader("INTERCEPTION & PROXY ENGINE")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text("Proxy Listening Port (Localhost 127.0.0.1)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = proxyPortInput,
                                onValueChange = {
                                    proxyPortInput = it
                                    it.toIntOrNull()?.let { p ->
                                        // Write to BOTH stores: SettingsDataStore is the value
                                        // PreflightController/InterceptScreen actually consume
                                        // when arming capture; SettingsRepository is kept for the
                                        // diagnostics-tab display (Section 7) so it doesn't fall
                                        // out of sync with what the user just typed.
                                        SettingsRepository.updateProxyPort(p)
                                        settingsScope.launch { interceptionDataStore.setProxyPort(p) }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }

                        // B9(c): three local, never-persisted toggles removed here — see
                        // the state-declaration comment above for the full rationale.

                        // B9(d): default capture-tier preference — real value read by
                        // PreflightController.run() at the moment interception starts
                        // (Root > Shizuku > VPN auto-probe, or force the rootless VPN
                        // path for baseline testing). Previously defined in
                        // SettingsDataStore with zero Settings-page UI anywhere.
                        // 6-tier visual grid (settings-main-dark.html .tiergrid): VPN maps
                        // to FORCE_VPN_ONLY; every other tier maps to AUTO (auto-probe).
                        // The backing CaptureTierPreference enum (frozen in SettingsDataStore,
                        // consumed read-only by PreflightController) is unchanged.
                        ProxyTierTypeSelector(
                            currentTierPref = captureTierPref,
                            onTierSelected = { pref ->
                                settingsScope.launch {
                                    interceptionDataStore.setCaptureTierPreference(pref)
                                }
                            }
                        )

                        // B9(d): remember-last-app-filter — real value read by
                        // PreflightController.run() alongside appFilterMode/selectedPackages
                        // (InterceptScreen.kt's "All apps" / "Selected apps" picker). When on,
                        // the last app-filter selection is reused on the next capture; when
                        // off, nothing currently forces a re-prompt (no re-ask UI exists yet),
                        // but the flag itself is real and consumed by the pre-flight sequence.
                        SettingToggleRow(
                            label = "Remember Last App Filter",
                            checked = rememberLastAppFilter
                        ) { checked ->
                            settingsScope.launch { interceptionDataStore.setRememberLastAppFilter(checked) }
                        }
                    }
                }
            }

            // Section 4.5 (T2): Gateway Mode — lets OTHER devices on the same LAN/hotspot
            // configure this device as their HTTP proxy and have THEIR traffic captured
            // through the same pipeline as on-device captures. Deliberately separate from
            // Section 4 above: Gateway mode is NOT gated by PreflightController's
            // CaptureMethod/ActiveTier resolution (which only covers this device's own
            // traffic) — it is toggled directly via GatewayEngine.start()/stop(), same
            // pattern as the "LOCAL REST API & CLI" LocalApiServer card below.
            item {
                SettingsSectionHeader("GATEWAY MODE")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    val gatewayRunning by com.example.interception.gateway.GatewayEngine.isRunningFlow.collectAsState()
                    val gatewayRemoteClients by com.example.interception.gateway.GatewayEngine.remoteClientsFlow.collectAsState()
                    val gatewayModeEnabledPref by interceptionDataStore.gatewayModeEnabled.collectAsState(initial = false)
                    val gatewayPortPref by interceptionDataStore.gatewayPort.collectAsState(
                        initial = com.example.data.prefs.SettingsDataStore.DEFAULT_GATEWAY_PORT
                    )
                    var gatewayPortInput by remember(gatewayPortPref) { mutableStateOf(gatewayPortPref.toString()) }
                    val gatewayLanIp = remember(settingsContext, gatewayRunning) {
                        com.example.interception.gateway.GatewayEngine.getLanIpAddress(settingsContext)
                    }

                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Let another device on this Wi-Fi/hotspot use this phone as its HTTP proxy. That device's traffic is captured into the same traffic log below, tagged \"Gateway\".",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    if (gatewayRunning) "Running" else "Stopped",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (gatewayRunning) {
                                        gatewayLanIp?.let { "Configure proxy: $it:${com.example.interception.gateway.GatewayEngine.port}" }
                                            ?: "No LAN address found -- connect to Wi-Fi/hotspot first"
                                    } else {
                                        "Not listening"
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (gatewayRunning && gatewayLanIp != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = gatewayRunning,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val portToUse = gatewayPortInput.toIntOrNull() ?: gatewayPortPref
                                        com.example.interception.gateway.GatewayEngine.start(settingsContext, portToUse)
                                    } else {
                                        com.example.interception.gateway.GatewayEngine.stop()
                                    }
                                    settingsScope.launch { interceptionDataStore.setGatewayModeEnabled(checked) }
                                }
                            )
                        }

                        Column {
                            Text("Gateway Port", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = gatewayPortInput,
                                onValueChange = {
                                    gatewayPortInput = it
                                    it.toIntOrNull()?.let { p ->
                                        settingsScope.launch { interceptionDataStore.setGatewayPort(p) }
                                    }
                                },
                                enabled = !gatewayRunning,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Remote clients observed: ${gatewayRemoteClients.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (gatewayRunning && gatewayLanIp != null) {
                                val clipboardManager = LocalClipboardManager.current
                                val proxyConfigString = "$gatewayLanIp:${com.example.interception.gateway.GatewayEngine.port}"
                                Row(
                                    modifier = Modifier.clickable {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(proxyConfigString))
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy $proxyConfigString", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        if (gatewayRemoteClients.isNotEmpty()) {
                            Text(
                                gatewayRemoteClients.joinToString(", "),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            "HTTP requests from that device are fully inspected. HTTPS (CONNECT) traffic passes through encrypted -- it shows up as a connection but its content isn't decrypted unless the remote device installs requestr's CA, the same way on-device MITM capture works.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Section 5: Deep Links & Trigger-to-Action Ecosystem
            item {
                SettingsSectionHeader("ECOSYSTEM & DEEP LINKS")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Link, contentDescription = "Link", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("requestr:// URI Handler Scheme", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Enables Telegram-like inter-app triggers, n8n automation links, and terminal hooks.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(modifier = Modifier.height(10.dp))

                        val deepLinks = listOf(
                            "requestr://api/run?id=req-1" to "Trigger API payload",
                            "requestr://flow/execute?id=flow-1" to "Run n8n Automation Chain",
                            "requestr://note/create?title=Log" to "New quick note"
                        )

                        deepLinks.forEach { (scheme, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(scheme, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Section 5.5: Local REST API & CLI — server control, endpoint discovery, CLI usage
            // and the SDK/extension pairing token (previously a separate "SDK PAIRING" card)
            // in one place, all driven by LocalApiServer / SettingsDataStore state.
            item {
                SettingsSectionHeader("LOCAL REST API & CLI")
                LocalApiAndCliCard(interceptionDataStore = interceptionDataStore)
            }

            // Task 12: AI Provider — configures the OpenAI-compatible endpoint ChatRepository
            // calls from the Chats section. Before this card existed there was no writer
            // anywhere for the provider fields, so callProvider() was permanently unreachable
            // and the app was always in local-mock mode. name/baseUrl/model/isActive persist to
            // the chat_providers Room table (task 11); the API key never does -- it goes through
            // SecureKeyStore (raw AndroidKeyStore AES/GCM), keyed by provider id.
            item {
                SettingsSectionHeader("AI PROVIDER")
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                    val providerConfig by com.example.data.repository.ChatRepository.providerConfig.collectAsState()
                    var urlInput by remember(providerConfig.baseUrl) { mutableStateOf(providerConfig.baseUrl) }
                    var modelInput by remember(providerConfig.model) { mutableStateOf(providerConfig.model) }
                    var keyInput by remember(providerConfig.apiKey) { mutableStateOf(providerConfig.apiKey) }
                    var keyVisible by remember { mutableStateOf(false) }

                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.VpnKey, contentDescription = "AI Provider", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Chat Completion Endpoint", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (providerConfig.isConfigured) "Configured — the Chats section will call this provider." else "Not configured — the Chats section runs in local mode (no network calls).",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Base URL", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            placeholder = { Text("https://api.openai.com/v1/chat/completions", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Model", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            singleLine = true,
                            placeholder = { Text("gpt-3.5-turbo", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("API Key (stored in AndroidKeyStore, never in Room/DataStore in plaintext)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            singleLine = true,
                            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Default.Visibility else Icons.Default.Language,
                                        contentDescription = if (keyVisible) "Hide key" else "Show key",
                                    )
                                }
                            },
                            placeholder = { Text("sk-…", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                // Wait for the real Room + Keystore outcome before telling the
                                // user anything was saved -- an optimistic "AI provider saved."
                                // Toast fired before the writes complete/fail is exactly the
                                // defect this call site was flagged for (see saveProviderConfig's
                                // kdoc in ChatRepository).
                                settingsScope.launch {
                                    when (val result = com.example.data.repository.ChatRepository.saveProviderConfig(
                                        providerConfig.copy(
                                            baseUrl = urlInput.trim(),
                                            model = modelInput.trim(),
                                            apiKey = keyInput,
                                        )
                                    )) {
                                        is com.example.data.repository.SaveProviderResult.Success ->
                                            android.widget.Toast.makeText(settingsContext, "AI provider saved.", android.widget.Toast.LENGTH_SHORT).show()
                                        is com.example.data.repository.SaveProviderResult.Failure ->
                                            android.widget.Toast.makeText(settingsContext, result.reason, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save Provider")
                        }
                    }
                }
            }


            // Section 5.5b: Runtime Instrumentation (R1+R3) — v6.1 Phase A / Domain B.
            // The card itself is ui/components/RuntimeInstrumentationCard.kt (per-adapter rows,
            // offscreen WebView capture control, HTTP-stack fingerprint verdicts with per-signal
            // evidence drill-in), rendered from the one authoritative state source,
            // RuntimeInstrumentationStateProvider. This screen owns no derivation and no engine
            // lifecycle: the instrumentation engine is still started by MainActivity, and the
            // offscreen WebView instance stays owned by WebViewAdapter.
            item {
                SettingsSectionHeader("RUNTIME INSTRUMENTATION")
                val runtimeState by com.example.runtime.RuntimeInstrumentationStateProvider.runtimeState
                    .collectAsState(initial = null)
                val stackSessionId = InterceptionStateRepository.state.collectAsState().value.sessionId
                val fingerprintState by produceState<com.example.data.models.StackFingerprintState>(
                    initialValue = com.example.data.models.StackFingerprintState.unavailable(
                        "Classifying captured requests…",
                    ),
                    stackSessionId,
                ) {
                    value = com.example.runtime.RuntimeInstrumentationStateProvider
                        .loadFingerprintState(settingsContext, stackSessionId)
                }
                var webViewCaptureUrl by remember { mutableStateOf("") }
                var expandedVerdictStack by remember { mutableStateOf<String?>(null) }

                val rs = runtimeState
                if (rs == null) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                        Text(
                            "Reading runtime instrumentation state…",
                            modifier = Modifier.padding(14.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    RuntimeInstrumentationCard(
                        state = rs,
                        fingerprint = fingerprintState,
                        webViewUrlInput = webViewCaptureUrl,
                        onWebViewUrlInputChange = { webViewCaptureUrl = it },
                        onStartWebViewCapture = {
                            if (webViewCaptureUrl.isNotBlank()) {
                                com.example.runtime.webview.WebViewInstrumentation.loadUrl(webViewCaptureUrl)
                            }
                        },
                        onStopWebViewCapture = {
                            com.example.runtime.webview.WebViewInstrumentation.stopCapture()
                        },
                        expandedVerdictStack = expandedVerdictStack,
                        onToggleVerdictStack = { stack ->
                            expandedVerdictStack = if (expandedVerdictStack == stack) null else stack
                        },
                    )
                }
            }

            // Section 5.5c: Browser Bridge (B1-B7) — v6.1 Phase A / Domain B. The card is
            // ui/components/CdpCard.kt (multi-target attach rows, stage-split rule-enforcement
            // tiles, live storage streams, service workers, reconnect/backoff with stale
            // last-known targets), rendered from RuntimeInstrumentationStateProvider.cdpState.
            // BrowserBridgeManager keeps sole ownership of the CdpClient lifecycle; this screen
            // only raises connect/disconnect and refresh-storage intents.
            item {
                SettingsSectionHeader("BROWSER BRIDGE (CDP)")
                val cdpCardState by com.example.runtime.RuntimeInstrumentationStateProvider.cdpState
                    .collectAsState(initial = null)
                var cdpHostPortInput by remember {
                    mutableStateOf(com.example.browser.BrowserBridgeManager.DEFAULT_HOST_PORT)
                }
                var expandedCdpTarget by remember { mutableStateOf<String?>(null) }

                val cs = cdpCardState
                if (cs == null) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                        Text(
                            "Reading browser bridge state…",
                            modifier = Modifier.padding(14.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CdpCard(
                        state = cs,
                        hostPortInput = cdpHostPortInput,
                        onHostPortInputChange = { cdpHostPortInput = it },
                        onToggleConnection = { checked ->
                            if (checked) {
                                com.example.browser.BrowserBridgeManager.connect(cdpHostPortInput)
                            } else {
                                com.example.browser.BrowserBridgeManager.disconnect()
                            }
                        },
                        onRefreshStorage = { com.example.browser.BrowserBridgeManager.refreshStorage() },
                        expandedTargetSessionId = expandedCdpTarget,
                        onToggleTarget = { sessionId ->
                            expandedCdpTarget = if (expandedCdpTarget == sessionId) null else sessionId
                        },
                    )
                }
            }

            // Section 5.6: Plugin Manager
            item {
                SettingsSectionHeader("PLUGIN MANAGER")
                PluginManagerCard()
            }

            // Section 5.7: Device Capabilities
            item {
                SettingsSectionHeader("DEVICE CAPABILITIES")
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val caps = capabilities
                        Text("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                        Text("Arch: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                        Text("Root: ${if (caps.isRootUsable) "✓ available" else "✗ unavailable"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (caps.isRootUsable) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Shizuku: ${if (caps.isShizukuUsable) "✓ available" else "✗ unavailable"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (caps.isShizukuUsable) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("CA installed: ${if (caps.isCaInstalled) "✓ yes" else "✗ no"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (caps.isCaInstalled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Capability Matrix:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("VPN: ✓ | MITM: ${if (caps.isCaInstalled) "✓" else "✗"} | System CA: ${if (caps.isRootUsable) "✓" else "✗"} | Runtime: ${if (caps.isShizukuUsable) "conditional" else "✗"}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // S2 task 17: persisted database recovery diagnostics rows (DbBackup metadata) plus
            // this process's live persistence health -- what happened to the data on the last
            // few opens, in the user's own words rather than a log file.
            item {
                SettingsSectionHeader("DATABASE RECOVERY")
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                    val persistenceHealth by InterceptionStateRepository.persistence.collectAsState()
                    val recoveryEvents by produceState<List<com.example.data.persistence.DbBackup.RecoveryEvent>>(initialValue = emptyList(), persistenceHealth.migration) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { com.example.data.persistence.DbBackup(settingsContext).readRecoveryEvents() }.getOrDefault(emptyList())
                        }
                    }
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Schema v${com.example.data.local.RequestrDatabase.SCHEMA_VERSION}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            persistenceHealth.headline ?: "Persistence healthy: ${persistenceHealth.committedWrites} write(s) committed this session, none dropped or failed.",
                            fontSize = 11.sp,
                            color = if (persistenceHealth.hasDataLoss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (persistenceHealth.orphanedSpillFilesRemoved > 0) {
                            Text("Orphan sweep removed ${persistenceHealth.orphanedSpillFilesRemoved} unreferenced body file(s) at start.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (recoveryEvents.isEmpty()) {
                            Text("No backup, restore or wipe events recorded.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            recoveryEvents.asReversed().take(5).forEach { ev ->
                                Text(
                                    "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ev.at))}  ${ev.kind}: ${ev.detail}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Section 5.8: Observation Report (D3) — honest fraction-of-traffic-observed
            // summary for the active/most-recent capture session, computed by
            // VisibilityAnalyzer from real persisted rows (no synthetic/demo numbers).
            item {
                SettingsSectionHeader("OBSERVATION REPORT")
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                    val diagSessionId = InterceptionStateRepository.state.collectAsState().value.sessionId
                    val report by produceState<com.example.diagnostics.ObservationReport?>(initialValue = null, diagSessionId) {
                        value = if (diagSessionId != null) {
                            runCatching { com.example.diagnostics.VisibilityAnalyzer.analyze(settingsContext, diagSessionId) }.getOrNull()
                        } else {
                            null
                        }
                    }
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = "Observation", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("What did requestr actually see?", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val r = report
                        when {
                            diagSessionId == null -> {
                                Text("No active/recorded capture session yet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            r == null -> {
                                Text("Analyzing session $diagSessionId…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            r.totalConnections == 0 -> {
                                Text("Session ${r.sessionId}: no TCP/QUIC connections recorded yet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> {
                                Text(
                                    "Session ${r.sessionId} · ${r.totalConnections} connection(s), ${r.udpFlowCount} UDP flow(s)",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "TLS decrypted: ${r.tlsDecrypted}/${r.tlsTotal} (${"%.0f".format(r.tlsDecryptedFraction * 100)}%) · SNI-only: ${r.tlsSniOnly} · reported FAILED: ${r.tlsFailedReported}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "HTTP decoded: ${r.httpDecrypted}/${r.httpTotal} · undecoded: ${r.httpUndecoded} · plaintext: ${r.httpPlaintext}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface
                                )
                                // W6/DEC-4: lineage joins HTTP rows on an exact connectionId, so rows
                                // without one belong to no connection. Show that count instead of
                                // letting it silently deflate the coverage figures below.
                                Text(
                                    "HTTP rows with no connection identity: ${r.unattachedHttpRows}/${r.httpTotal} (excluded from per-connection coverage)",
                                    fontSize = 11.sp,
                                    color = if (r.unattachedHttpRows > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Connections reaching HTTP: ${r.connectionsWithHttpDecoded}/${r.totalConnections} (${"%.0f".format(r.httpCoverageFraction * 100)}%) · reaching TLS: ${r.connectionsWithTlsObserved}/${r.totalConnections}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "DNS: ${r.dnsResponses} response(s), ${r.dnsResolvedWithIps} resolved to an IP (${"%.0f".format(r.dnsResolutionFraction * 100)}%) of ${r.dnsQueries} query(ies)",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface
                                )
                                if (r.limitingReasons.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Why not more:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                    r.limitingReasons.forEach { reason ->
                                        Text("• $reason", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 6: Root CA Certificate Details
            item {
                SettingsSectionHeader("CA ROOT CERTIFICATE & TRUST STORE")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val summary = caSummary
                        if (summary == null) {
                            Text("Reading certificate...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(summary.first, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Serial: ${summary.second}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("SHA-256: ${summary.third}", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        // Trust-store state from a real probe, not an assumption.
                        Text(
                            text = when (capabilities.ca) {
                                CaState.SYSTEM_STORE -> "Trust store: installed (system)"
                                CaState.USER_STORE -> "Trust store: installed (user)"
                                CaState.NOT_INSTALLED -> "Trust store: NOT installed"
                                CaState.UNKNOWN -> "Trust store: checking..."
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (capabilities.isCaInstalled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    // Launches the real KeyChain install intent for the actual CA.
                                    settingsScope.launch {
                                        runCatching {
                                            settingsContext.startActivity(
                                                CertificateAuthority.buildInstallIntent(settingsContext)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Install CA", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    settingsScope.launch {
                                        runCatching {
                                            val pem = CertificateAuthority.exportPem(settingsContext)
                                            val target = com.example.data.export.FileExporter.writeCaPem(settingsContext, pem)
                                                ?: error("Failed to write requestr-ca.pem to Downloads/requestr/")
                                            com.example.data.export.FileExporter.shareFile(
                                                settingsContext, target,
                                                "application/x-pem-file",
                                                "requestr Root CA (PEM)"
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(".pem", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
                            }
                        }

                        // Additional CA export formats: .0 (system hash), .cert (DER), .p12 (PKCS#12)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Export as .0 (Android system trust store format)
                            Button(
                                onClick = {
                                    settingsScope.launch {
                                        runCatching {
                                            val pem = CertificateAuthority.exportPem(settingsContext)
                                            val hash = CertificateAuthority.getSubjectHash(settingsContext)
                                            val target = com.example.data.export.FileExporter.writeCaSystemHash(settingsContext, pem, hash)
                                                ?: error("Failed to write $hash.0 to Downloads/requestr/")
                                            com.example.data.export.FileExporter.shareFile(
                                                settingsContext, target,
                                                "application/x-x509-ca-cert",
                                                "requestr CA ($hash.0)"
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(".0", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Export as .cert (DER binary)
                            Button(
                                onClick = {
                                    settingsScope.launch {
                                        runCatching {
                                            val der = CertificateAuthority.exportDer(settingsContext)
                                            val target = com.example.data.export.FileExporter.writeCaDer(settingsContext, der)
                                                ?: error("Failed to write requestr-ca.cert to Downloads/requestr/")
                                            com.example.data.export.FileExporter.shareFile(
                                                settingsContext, target,
                                                "application/x-x509-ca-cert",
                                                "requestr Root CA (DER)"
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(".cert", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Export as .p12 (PKCS#12 with keypair)
                            Button(
                                onClick = {
                                    settingsScope.launch {
                                        runCatching {
                                            val p12 = CertificateAuthority.exportP12(settingsContext)
                                            val target = com.example.data.export.FileExporter.writeCaP12(settingsContext, p12)
                                                ?: error("Failed to write requestr-ca.p12 to Downloads/requestr/")
                                            com.example.data.export.FileExporter.shareFile(
                                                settingsContext, target,
                                                "application/x-pkcs12",
                                                "requestr Root CA (PKCS#12)"
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(".p12", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Text(
                            "Exports to: Downloads/requestr/",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        // `.p12` import (master plan §3 LOW-ADD) — the export half above already
                        // existed; CaP12Exchange.importP12 is complete + unit-tested but had no
                        // file-picker/password UI. Placed right next to the export row per the
                        // task's "add the import counterpart right next to it" instruction.
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                p12ImportPickError = null
                                p12ImportLauncher.launch("*/*")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Icon(imageVector = Icons.Default.Upload, contentDescription = "Upload", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import .p12 (with password)", color = MaterialTheme.colorScheme.onTertiaryContainer, fontSize = 11.sp)
                        }
                        val pickError = p12ImportPickError
                        if (pickError != null && p12ImportPendingBytes == null) {
                            Text(pickError, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }

                        // Regenerate CA button
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                settingsScope.launch {
                                    runCatching {
                                        CertificateAuthority.regenerate(settingsContext)
                                        android.widget.Toast.makeText(settingsContext, "CA regenerated — re-export and re-install", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    caRefreshTrigger++
                                    CapabilityProbe.probeAll(settingsContext)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Regenerate CA", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                        }

                        // Auto-promote to system store when root/Shizuku transitions to
                        // GRANTED and the CA is in the user store. Previously the user had to
                        // manually tap the button. Now we observe the capability state and
                        // trigger automatically (with a result toast either way).
                        var autoPromoteTriggered by remember { mutableStateOf(false) }
                        LaunchedEffect(capabilities.root, capabilities.shizuku, capabilities.ca) {
                            if (autoPromoteTriggered) return@LaunchedEffect
                            if (capabilities.ca == CaState.SYSTEM_STORE) return@LaunchedEffect
                            val canAutoPromote = capabilities.root == RootState.GRANTED ||
                                capabilities.shizuku == ShizukuState.GRANTED
                            if (canAutoPromote) {
                                autoPromoteTriggered = true
                                runCatching {
                                    com.example.interception.CaWhitelistService.promoteToSystem(settingsContext)
                                }.onSuccess { newState ->
                                    if (newState == CaState.SYSTEM_STORE) {
                                        android.widget.Toast.makeText(
                                            settingsContext,
                                            "CA auto-promoted to system trust store",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.onFailure { e ->
                                    android.widget.Toast.makeText(
                                        settingsContext,
                                        "CA auto-promotion failed: ${e.message ?: e::class.java.simpleName}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                CapabilityProbe.probeAll(settingsContext)
                            }
                        }

                        // B11: CaWhitelistService.promoteToSystem() button as manual fallback.
                        val canPromote = capabilities.root == RootState.GRANTED ||
                            capabilities.shizuku == ShizukuState.GRANTED
                        if (canPromote && capabilities.ca != CaState.SYSTEM_STORE) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    settingsScope.launch {
                                        promoteToSystemInProgress = true
                                        promoteToSystemResult = null
                                        runCatching {
                                            com.example.interception.CaWhitelistService.promoteToSystem(settingsContext)
                                        }.onSuccess { newState ->
                                            promoteToSystemResult = if (newState == CaState.SYSTEM_STORE) {
                                                "Installed to system trust store"
                                            } else {
                                                "Install did not complete — still: $newState"
                                            }
                                        }.onFailure { e ->
                                            promoteToSystemResult = "Failed: ${e.message ?: "unknown error"}"
                                        }
                                        // Re-probe the real trust store; never trust the call's
                                        // return value alone as proof of installation.
                                        CapabilityProbe.probeAll(settingsContext)
                                        promoteToSystemInProgress = false
                                    }
                                },
                                enabled = !promoteToSystemInProgress,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(imageVector = Icons.Default.Security, contentDescription = "Security", tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (promoteToSystemInProgress) "Installing..." else "Install to System Store",
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            val resultText = promoteToSystemResult
                            if (resultText != null) {
                                Text(resultText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Section 6b (§1.10): remote-cert mirror opt-in -- a deliberately non-default,
            // expert setting. Placed right after the CA root cert section since it changes what
            // metadata ends up baked into the per-host leaf certs that section is about.
            item {
                SettingsSectionHeader("REMOTE CERT MIRRORING (EXPERT)")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    val remoteCertMirrorEnabled by interceptionDataStore.remoteCertMirrorEnabled.collectAsState(initial = false)
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mirror real cert metadata into generated leafs",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (remoteCertMirrorEnabled) "On" else "Off (default)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (remoteCertMirrorEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = remoteCertMirrorEnabled,
                                onCheckedChange = { checked ->
                                    settingsScope.launch { interceptionDataStore.setRemoteCertMirrorEnabled(checked) }
                                }
                            )
                        }
                        Text(
                            "When ON: the first time requestr MITMs a FORCE_MITM host, it opens a " +
                                "real upstream TLS connection to that host to copy its actual " +
                                "certificate metadata (subject, SANs, validity) into the generated " +
                                "per-host leaf cert -- so the leaf you inspect looks like the real " +
                                "site's cert instead of a generic requestr-issued one.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Trade-off: that means a genuine outbound network call to the target " +
                                "host on first contact, per host -- which is exactly why this is " +
                                "OFF by default and must stay off for any host you're mocking or " +
                                "testing offline (a mocked/offline host has nothing real to mirror, " +
                                "so this would either fail or leak a real connection attempt you " +
                                "didn't intend). Leave this off unless you specifically need the " +
                                "generated cert to carry real upstream metadata.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Section 6c (§3 C2): weak-network simulation -- master enable switch, three clamped
            // numeric inputs, and one-tap presets. Off by default; see NetworkConditionUiHelpers.kt
            // for why the clamp maxima and preset numbers are what they are.
            item {
                SettingsSectionHeader("WEAK NETWORK SIMULATION")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Simulate a weak connection",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (networkCondition.enabled) "On" else "Off (default)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (networkCondition.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = networkCondition.enabled,
                                onCheckedChange = { checked ->
                                    settingsScope.launch {
                                        interceptionDataStore.setNetworkCondition(
                                            sanitizedNetworkCondition(
                                                enabled = checked,
                                                latencyMs = networkCondition.latencyMs,
                                                jitterMs = networkCondition.jitterMs,
                                                packetLossPercent = networkCondition.packetLossPercent,
                                            )
                                        )
                                    }
                                }
                            )
                        }
                        Text(
                            "Applies only to the HTTP-proxy, MITM-proxy, and gateway relay tiers -- " +
                                "it has no effect on the VPN-tun packet path or Frida-based interception, " +
                                "which don't run through this relay code at all. Simulated packet loss " +
                                "surfaces as a failed connection (a 502 for plain HTTP, a CONNECT tunnel " +
                                "that never opens for HTTPS) -- not literal dropped bytes mid-stream, " +
                                "since that isn't reproducible above the OS's own TCP stack.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = networkLatencyInput,
                            onValueChange = { networkLatencyInput = it },
                            label = { Text("Latency (ms, 0-$MAX_NETWORK_LATENCY_MS)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )
                        OutlinedTextField(
                            value = networkJitterInput,
                            onValueChange = { networkJitterInput = it },
                            label = { Text("Jitter (± ms, 0-$MAX_NETWORK_JITTER_MS)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )
                        OutlinedTextField(
                            value = networkLossInput,
                            onValueChange = { networkLossInput = it },
                            label = { Text("Packet loss (%, 0-100)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )

                        Button(
                            onClick = {
                                val sanitized = sanitizedNetworkCondition(
                                    enabled = true,
                                    latencyMs = parseClampedNonNegativeIntInput(networkLatencyInput, MAX_NETWORK_LATENCY_MS),
                                    jitterMs = parseClampedNonNegativeIntInput(networkJitterInput, MAX_NETWORK_JITTER_MS),
                                    packetLossPercent = parseClampedPercentInput(networkLossInput),
                                )
                                networkLatencyInput = sanitized.latencyMs.toString()
                                networkJitterInput = sanitized.jitterMs.toString()
                                networkLossInput = sanitized.packetLossPercent.toString()
                                settingsScope.launch { interceptionDataStore.setNetworkCondition(sanitized) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply")
                        }

                        Text(
                            "Presets (tap to apply and enable):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        NETWORK_CONDITION_PRESETS.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                                    .clickable {
                                        networkLatencyInput = preset.condition.latencyMs.toString()
                                        networkJitterInput = preset.condition.jitterMs.toString()
                                        networkLossInput = preset.condition.packetLossPercent.toString()
                                        settingsScope.launch { interceptionDataStore.setNetworkCondition(preset.condition) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Column {
                                    Text(preset.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(preset.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Section 7: Diagnostics
            item {
                SettingsSectionHeader("SECURITY TIERS DIAGNOSTICS")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Every row below reports a REAL probe result. v2.1 hardcoded all six to
                        // "true" with invented version strings, so an unrooted device with no
                        // Shizuku still showed six green ticks (APP_v2.md Bug 3.1).
                        DiagnosticRow(
                            "VPN capture tier (VpnService)",
                            "Always available",
                            true
                        )
                        DiagnosticRow(
                            "Local proxy port",
                            "Port ${settings.proxyPort}",
                            true
                        )
                        DiagnosticRow(
                            "CA trust store",
                            when (capabilities.ca) {
                                CaState.SYSTEM_STORE -> "Installed (system store)"
                                CaState.USER_STORE -> "Installed (user store)"
                                CaState.NOT_INSTALLED -> "Not installed"
                                CaState.UNKNOWN -> "Not probed"
                            },
                            capabilities.isCaInstalled
                        )
                        DiagnosticRow(
                            "Root access",
                            when (capabilities.root) {
                                RootState.GRANTED -> "Granted (su returned uid=0)"
                                RootState.AVAILABLE -> "su present, not confirmed"
                                RootState.DENIED -> "Denied by root manager"
                                RootState.UNAVAILABLE -> "No su binary (not rooted)"
                                RootState.UNKNOWN -> "Not probed"
                            },
                            capabilities.root == RootState.GRANTED
                        )
                        DiagnosticRow(
                            "Shizuku service",
                            when (capabilities.shizuku) {
                                ShizukuState.GRANTED -> "Bound and permitted"
                                ShizukuState.PERMISSION_DENIED -> "Running, permission denied"
                                ShizukuState.NOT_RUNNING -> "Installed, not running"
                                ShizukuState.NOT_INSTALLED -> "Not installed"
                                ShizukuState.UNKNOWN -> "Not probed"
                            },
                            capabilities.shizuku == ShizukuState.GRANTED
                        )
                        DiagnosticRow(
                            "frida-server (127.0.0.1:27042)",
                            when (capabilities.frida) {
                                FridaState.RUNNING -> "Reachable"
                                FridaState.NOT_RUNNING -> "Not reachable"
                                FridaState.UNKNOWN -> "Not probed"
                            },
                            capabilities.frida == FridaState.RUNNING
                        )
                    }
                }
            }

            // Section 7b: VPN app picker (master plan §3 LOW-ADD, C8) — makes the
            // INTERNET-permission prefilter an observable, user-controlled setting instead of a
            // silent, invisible change. The picker itself lives in InterceptScreen.kt (not owned
            // here); this toggle/count display shares the same repository-level filter state
            // (`TrafficInterceptorRepository.internetOnlyFilterEnabled`), so flipping it here
            // takes effect the next time the capture-setup app picker loads.
            item {
                SettingsSectionHeader("VPN APP PICKER")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingToggleRow(
                            "Hide apps without INTERNET permission",
                            appPickerInternetOnly,
                            onCheckedChange = { enabled ->
                                TrafficInterceptorRepository.setInternetOnlyFilter(enabled)
                                settingsScope.launch { TrafficInterceptorRepository.loadInstalledApps(settingsContext, enabled) }
                            }
                        )
                        Text(
                            "An app with no INTERNET permission can't make network requests, so " +
                                "it has nothing for the interceptor to ever capture. On: fewer, " +
                                "more relevant apps in the capture-setup picker. Off: every " +
                                "launchable app is shown, unfiltered.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        if (appPickerTotal == 0) {
                            Text(
                                "Not loaded yet — open Interceptr's capture-setup sheet once, or " +
                                    "tap refresh below, to see live counts.",
                                fontSize = 11.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            AppMetaInfoRow("Launchable apps on this device", "$appPickerTotal")
                            AppMetaInfoRow("Hidden by this filter", "$appPickerFilteredOut")
                            AppMetaInfoRow("Shown in picker", "${appPickerApps.size}")
                        }
                        Button(
                            onClick = { settingsScope.launch { TrafficInterceptorRepository.loadInstalledApps(settingsContext, appPickerInternetOnly) } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh app list", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Section 7c: Target app database browser (master plan §2.18) — rooted-device-only
            // feature. Gated on the SAME capability probe used by Interceptr's "Capture setup"
            // sheet (`capabilities.root`, refreshed via `CapabilityProbe.probeAll`), never a
            // second independent root check.
            item {
                SettingsSectionHeader("TARGET APP DATABASE BROWSER")
                TargetAppDatabaseBrowserSection(
                    root = capabilities.root,
                    installedApps = appPickerApps,
                    context = settingsContext,
                    scope = settingsScope,
                    onProbeAgain = { settingsScope.launch { CapabilityProbe.probeAll(settingsContext) } }
                )
            }

            // Section 8: Backup & Privacy
            item {
                SettingsSectionHeader("BACKUP & PRIVACY")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        // Deletes the real persisted rows rather than an in-memory list.
                        onClick = { settingsScope.launch { CaptureLogRepository.clearAll(settingsContext) } },
                        enabled = capturedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (capturedCount == 0) "Log empty" else "Clear $capturedCount",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            settingsScope.launch {
                                runCatching {
                                    val items = com.example.data.interception.CaptureLogRepository.getAllTraffic(settingsContext)
                                    val json = org.json.JSONArray(
                                        items.map { item ->
                                            org.json.JSONObject().apply {
                                                put("id", item.id)
                                                put("method", item.method.name)
                                                put("url", item.url)
                                                put("host", item.host)
                                                put("statusCode", item.statusCode)
                                                put("requestHeaders", org.json.JSONObject(item.requestHeaders))
                                                put("responseHeaders", org.json.JSONObject(item.responseHeaders))
                                                put("timestamp", item.timestamp)
                                            }
                                        }
                                    ).toString(2)
                                    val target = com.example.data.export.FileExporter.writeJson(settingsContext, json)
                                        ?: error("Failed to write traffic-log JSON to Downloads/requestr/")
                                    com.example.data.export.FileExporter.shareFile(
                                        settingsContext, target,
                                        "application/json",
                                        "requestr Traffic Log"
                                    )
                                }
                            }
                        },
                        enabled = capturedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Workspace export/import — WorkspaceExporter.exportWorkspace/importWorkspace
                // were complete and real but had zero call sites anywhere in the app.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            settingsScope.launch {
                                runCatching {
                                    val json = com.example.data.export.WorkspaceExporter.exportWorkspace(settingsContext)
                                    val target = com.example.data.export.FileExporter.writeJson(settingsContext, json)
                                        ?: error("Failed to write workspace JSON to Downloads/requestr/")
                                    com.example.data.export.FileExporter.shareFile(
                                        settingsContext, target,
                                        "application/json",
                                        "requestr Workspace"
                                    )
                                }.onFailure { e ->
                                    android.widget.Toast.makeText(settingsContext, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Upload, contentDescription = "Export Workspace", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Workspace", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { workspaceImportLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = "Import Workspace", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Workspace", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }

            // Section 9: MANDATORY App Metadata Block (Fix #3 / Item #3)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RequestrWordmark(fontSize = 20)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("RELEASE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(10.dp))

                        AppMetaInfoRow("Application Name", "requestr")
                        AppMetaInfoRow("Package ID", "com.kailashhh.requestr")
                        AppMetaInfoRow("Version", "${com.example.BuildConfig.VERSION_NAME} (build ${com.example.BuildConfig.VERSION_CODE}, API ${android.os.Build.VERSION.SDK_INT})")
                        AppMetaInfoRow("Core Engine", "OkHttp 4.12.0 + Conscrypt APEX")
                        AppMetaInfoRow("Architecture", "arm64-v8a, armeabi-v7a, x86_64")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(90.dp)) }
        }
    }

    // `.p12` import password prompt — rendered as a dialog overlay regardless of scroll
    // position, since the "Import .p12" button that triggers it lives inside the scrolling
    // LazyColumn above.
    P12ImportPasswordDialog(
        pendingBytes = p12ImportPendingBytes,
        fileName = p12ImportPendingFileName,
        onDismiss = {
            p12ImportPendingBytes = null
            p12ImportPendingFileName = null
        },
        onImported = {
            p12ImportPendingBytes = null
            p12ImportPendingFileName = null
            caRefreshTrigger++
            settingsScope.launch { CapabilityProbe.probeAll(settingsContext) }
        },
        context = settingsContext,
        scope = settingsScope,
    )
}

/**
 * Master plan §3 LOW-ADD — `.p12` CA import password prompt.
 *
 * [CaP12Exchange.importP12] is complete and unit-tested ([CaP12ExchangeTest]); this dialog is
 * the missing UI half: a masked (show/hide-toggleable) password field, clear validation, and
 * honest, distinct error messaging per [P12ImportErrorClassifier] for wrong-password vs
 * corrupt-file vs unsupported-content, rather than one generic "import failed" toast.
 */
@Composable
private fun P12ImportPasswordDialog(
    pendingBytes: ByteArray?,
    fileName: String?,
    onDismiss: () -> Unit,
    onImported: () -> Unit,
    context: android.content.Context,
    scope: CoroutineScope,
) {
    if (pendingBytes == null) return

    var password by remember(pendingBytes) { mutableStateOf("") }
    var showPassword by remember(pendingBytes) { mutableStateOf(false) }
    var importing by remember(pendingBytes) { mutableStateOf(false) }
    var errorMessage by remember(pendingBytes) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        icon = {
            Icon(imageVector = Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text("Import CA (.p12)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text(
                    "File: ${fileName ?: "(unknown name)"} · ${pendingBytes.size} bytes\n\n" +
                        "Enter the password protecting this PKCS#12 bundle. Importing replaces requestr's " +
                        "active CA — you'll need to re-install the new CA afterwards.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !importing,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                val err = errorMessage
                if (err != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !importing,
                onClick = {
                    if (password.isEmpty()) {
                        errorMessage = "Enter the bundle's password first."
                        return@TextButton
                    }
                    importing = true
                    errorMessage = null
                    scope.launch {
                        val passwordChars = password.toCharArray()
                        val result = withContext(Dispatchers.IO) {
                            CaP12Exchange.importP12(context, pendingBytes, passwordChars)
                        }
                        java.util.Arrays.fill(passwordChars, '\u0000')
                        result.onSuccess {
                            android.widget.Toast.makeText(
                                context, "CA imported — this app now trusts and signs with the imported CA.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            importing = false
                            onImported()
                        }.onFailure { e ->
                            val kind = P12ImportErrorClassifier.classify(e.message)
                            errorMessage = P12ImportErrorClassifier.userMessage(kind, e.message)
                            importing = false
                        }
                    }
                }
            ) {
                Text(if (importing) "Importing..." else "Import", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !importing, onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

/** Small "tap to pick from a list" control shared by the three DB-browser pickers below. */
@Composable
private fun LabeledDropdown(
    label: String,
    selected: String?,
    placeholder: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected ?: placeholder,
                    fontSize = 12.sp,
                    fontFamily = if (selected != null) FontFamily.Monospace else FontFamily.Default,
                    color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                if (options.isEmpty()) {
                    DropdownMenuItem(text = { Text("(none)", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = { expanded = false })
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}

/**
 * Master plan §2.18 — rooted target-app SQLite database browser UI.
 *
 * Gated on [root] ([RootState]) — the SAME [CapabilityProbe]/[InterceptionStateRepository]
 * state Interceptr's "Capture setup" sheet already surfaces, never a second independent check.
 * On this project's own reference emulator the real probe result is [RootState.UNAVAILABLE]
 * (no `su` binary at all), which is why that branch below is written as the most complete,
 * reassuring explanation rather than an afterthought — a blank/crashed-looking screen on the
 * single most common real-world outcome would be worse than a slightly sparse GRANTED branch
 * nobody without a rooted device ever sees.
 */
@Composable
private fun TargetAppDatabaseBrowserSection(
    root: RootState,
    installedApps: List<AppPackageItem>,
    context: android.content.Context,
    scope: CoroutineScope,
    onProbeAgain: () -> Unit,
) {
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var databases by remember { mutableStateOf<List<String>?>(null) }
    var selectedDb by remember { mutableStateOf<String?>(null) }
    var localFile by remember { mutableStateOf<java.io.File?>(null) }
    var tables by remember { mutableStateOf<List<String>?>(null) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var pageOffset by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<TargetAppDatabaseBrowser.QueryResult?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pageSize = 50

    // Never leave a stale cache copy behind: the previous local copy is deleted the moment a
    // new one replaces it, and the last one is deleted if this section leaves composition.
    DisposableEffect(localFile) {
        val fileToClean = localFile
        onDispose { fileToClean?.let { TargetAppDatabaseBrowser.cleanup(it) } }
    }

    fun resetBelowApp() {
        databases = null; selectedDb = null; localFile = null; tables = null
        selectedTable = null; pageOffset = 0; result = null; browserError = null
    }
    fun resetBelowDb() {
        tables = null; selectedTable = null; pageOffset = 0; result = null
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (root) {
                RootState.UNAVAILABLE -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Root access unavailable", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "This device has no `su` binary — it isn't rooted (or is running a " +
                                    "locked-down build). Browsing another app's private SQLite databases " +
                                    "needs root to read files under /data/data/<package>/databases/, which " +
                                    "isn't accessible any other way. This isn't a bug — most real devices " +
                                    "report exactly this. Install Magisk (or use a rooted AVD image) and " +
                                    "tap \"Re-check\" below to unlock this feature.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                RootState.AVAILABLE -> {
                    Text("Root present, not confirmed", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "A `su` binary exists on this device, but requestr hasn't confirmed it can " +
                            "actually run as root (the binary may be present but restricted to the shell " +
                            "user, or a root manager hasn't granted this app access yet). Tap \"Re-check\" " +
                            "after granting access in Magisk/KernelSU.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RootState.DENIED -> {
                    Text("Root access denied", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Text(
                        "A root manager (Magisk/KernelSU) is present but explicitly denied requestr " +
                            "root access. Open your root manager's app list, grant requestr access, then " +
                            "tap \"Re-check\" below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RootState.UNKNOWN -> {
                    Text("Not probed yet", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Root access hasn't been checked yet this session. Tap \"Re-check\" to probe it.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RootState.GRANTED -> {
                    Text("Root granted", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF10B981))
                    Text(
                        "Pick a target app, then one of its databases, then a table to browse its rows " +
                            "read-only. Nothing here ever writes back to the target app's storage.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LabeledDropdown(
                        label = "TARGET APP",
                        selected = selectedPkg?.let { pkg -> installedApps.find { it.packageName == pkg }?.appName ?: pkg },
                        placeholder = if (installedApps.isEmpty()) "No apps loaded — use the VPN app picker section above first" else "Choose an app",
                        options = installedApps.map { it.packageName },
                        enabled = installedApps.isNotEmpty() && !busy
                    ) { pkg ->
                        selectedPkg = pkg
                        resetBelowApp()
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { TargetAppDatabaseBrowser.listDatabases(pkg) }
                            r.onSuccess { databases = it }.onFailure { browserError = it.message ?: "Failed to list databases" }
                            busy = false
                        }
                    }

                    val dbList = databases
                    if (selectedPkg != null && dbList != null) {
                        if (dbList.isEmpty()) {
                            Text("No databases found under this app's databases/ directory.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LabeledDropdown(
                                label = "DATABASE",
                                selected = selectedDb,
                                placeholder = "Choose a database",
                                options = dbList,
                                enabled = !busy
                            ) { dbName ->
                                resetBelowDb()
                                selectedDb = dbName
                                busy = true
                                scope.launch {
                                    val copyResult = withContext(Dispatchers.IO) { TargetAppDatabaseBrowser.copyDatabaseLocally(context, selectedPkg!!, dbName) }
                                    copyResult.onSuccess { file ->
                                        localFile = file
                                        val tablesResult = withContext(Dispatchers.IO) { TargetAppDatabaseBrowser.listTables(file) }
                                        tablesResult.onSuccess { tables = it }.onFailure { browserError = it.message ?: "Failed to list tables" }
                                    }.onFailure { browserError = it.message ?: "Failed to copy database" }
                                    busy = false
                                }
                            }
                        }
                    }

                    val tableList = tables
                    if (selectedDb != null && tableList != null) {
                        if (tableList.isEmpty()) {
                            Text("This database has no user tables.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LabeledDropdown(
                                label = "TABLE",
                                selected = selectedTable,
                                placeholder = "Choose a table",
                                options = tableList,
                                enabled = !busy
                            ) { table ->
                                selectedTable = table
                                pageOffset = 0
                                result = null
                                if (!isSafeSqlIdentifier(table)) {
                                    browserError = "Invalid table identifier: $table"
                                } else {
                                    val file = localFile
                                    if (file != null) {
                                        busy = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                TargetAppDatabaseBrowser.query(file, "SELECT * FROM \"$table\" LIMIT $pageSize OFFSET 0")
                                            }
                                            r.onSuccess { result = it }.onFailure { browserError = it.message ?: "Query failed" }
                                            busy = false
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (busy) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Working…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    val currentResult = result
                    if (currentResult != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Text(
                            "${currentResult.rows.size} row(s) · rows ${pageOffset + 1}–${pageOffset + currentResult.rows.size} · " +
                                "${currentResult.columns.size} column(s)",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Wide/large cell values are truncated for display only — never mutated,
                        // never re-used to build another query.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Row {
                                currentResult.columns.forEach { col ->
                                    Text(
                                        col.take(40),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.widthIn(min = 90.dp).padding(end = 8.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            currentResult.rows.forEach { row ->
                                Row {
                                    row.forEach { cell ->
                                        val display = (cell ?: "NULL").let { if (it.length > 200) it.take(200) + "…" else it }
                                        Text(
                                            display,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (cell == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(min = 90.dp).padding(end = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(
                                enabled = pageOffset > 0 && !busy,
                                onClick = {
                                    val newOffset = (pageOffset - pageSize).coerceAtLeast(0)
                                    val file = localFile
                                    val table = selectedTable
                                    if (file != null && table != null) {
                                        busy = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                TargetAppDatabaseBrowser.query(file, "SELECT * FROM \"$table\" LIMIT $pageSize OFFSET $newOffset")
                                            }
                                            r.onSuccess { result = it; pageOffset = newOffset }.onFailure { browserError = it.message }
                                            busy = false
                                        }
                                    }
                                }
                            ) { Text("‹ Prev page", fontSize = 11.sp) }
                            TextButton(
                                enabled = currentResult.rows.size == pageSize && !busy,
                                onClick = {
                                    val newOffset = pageOffset + pageSize
                                    val file = localFile
                                    val table = selectedTable
                                    if (file != null && table != null) {
                                        busy = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                TargetAppDatabaseBrowser.query(file, "SELECT * FROM \"$table\" LIMIT $pageSize OFFSET $newOffset")
                                            }
                                            r.onSuccess { result = it; pageOffset = newOffset }.onFailure { browserError = it.message }
                                            busy = false
                                        }
                                    }
                                }
                            ) { Text("Next page ›", fontSize = 11.sp) }
                        }
                    }
                }
            }

            val err = browserError
            if (err != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            Button(
                onClick = onProbeAgain,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-check root access", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun AppMetaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun DiagnosticRow(name: String, status: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isOk) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(status, color = if (isOk) Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Appearance composables (Phase B) ─────────────────────────────────

/**
 * Mirrors RequestrApp()'s theme resolution exactly: SYSTEM follows the OS dark flag and
 * picks the remembered per-family slot; LIGHT / DARK render `themeMode` as-is. Pure so it
 * can be unit-tested and so the Settings preview can never disagree with what the app draws.
 */
internal fun resolveEffectiveThemeKey(
    appearanceMode: String,
    themeMode: String,
    selectedLightTheme: String,
    selectedDarkTheme: String,
    systemInDarkTheme: Boolean,
): String = when (appearanceMode.uppercase()) {
    "SYSTEM" -> if (systemInDarkTheme) selectedDarkTheme else selectedLightTheme
    else -> themeMode
}

/** Which theme families the picker should offer for an appearance mode. */
internal fun themeFamiliesFor(appearanceMode: String): List<AppThemeMode> = when (appearanceMode.uppercase()) {
    "LIGHT" -> AppThemeMode.lightThemes()
    "DARK" -> AppThemeMode.darkThemes()
    else -> AppThemeMode.lightThemes() + AppThemeMode.darkThemes()
}

@Composable
private fun AppearanceSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit
) {
    val modes = listOf(
        Triple("SYSTEM", "System", Icons.Outlined.SettingsBrightness),
        Triple("LIGHT", "Light", Icons.Filled.LightMode),
        Triple("DARK", "Dark", Icons.Outlined.DarkMode)
    )
    Text("Appearance mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    Spacer(modifier = Modifier.height(8.dp))
    // Theme colours here are read straight from MaterialTheme with no animateColorAsState:
    // when the theme flips, this row must flip in the same frame as the rest of the app.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        modes.forEach { (mode, label, icon) ->
            val isSelected = currentMode.equals(mode, ignoreCase = true)
            val bgColor = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
            val fg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bgColor)
                    .then(
                        if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                        else Modifier
                    )
                    .clickable { onModeSelected(mode) }
                    .semantics { contentDescription = "Appearance mode $label" + if (isSelected) ", selected" else "" }
                    .padding(vertical = 10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = when (currentMode.uppercase()) {
            "LIGHT" -> "Always light. Your light theme below is what renders."
            "DARK" -> "Always dark. Your dark theme below is what renders."
            else -> "Follows your device's dark-mode setting and remembers a separate theme for light and dark."
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 15.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * Nine-family theme picker. Swatches are painted with the family's real canvas
 * (background / surface / outline) plus the user's current accent through the same
 * [buildColorScheme] the app theme uses, so a swatch is a truthful thumbnail of the result.
 * Selection routes through the unified `updateThemeSelection` write path.
 */
@Composable
private fun ThemeFamilyPicker(
    settings: UserSettingsState,
    onThemeSelected: (AppThemeMode) -> Unit,
) {
    val accent = remember(settings.selectedAccent) { AppAccent.fromKey(settings.selectedAccent) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val activeKey = resolveEffectiveThemeKey(
        settings.appearanceMode, settings.themeMode, settings.selectedLightTheme, settings.selectedDarkTheme, systemDark
    )
    val activeTheme = AppThemeMode.fromKey(activeKey)
    val selectedLight = AppThemeMode.fromKey(settings.selectedLightTheme)
    val selectedDark = AppThemeMode.fromKey(settings.selectedDarkTheme)
    val isSystem = settings.appearanceMode.equals("SYSTEM", ignoreCase = true)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "${AppThemeMode.values().size} families",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(10.dp))

    @Composable
    fun familyRow(label: String?, themes: List<AppThemeMode>, selected: AppThemeMode?) {
        if (label != null) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            themes.forEach { theme ->
                ThemeSwatchCard(
                    theme = theme,
                    scheme = remember(theme, accent) { com.example.ui.theme.buildColorScheme(theme, accent) },
                    selected = theme == selected,
                    activeNow = theme == activeTheme,
                    onClick = { onThemeSelected(theme) }
                )
            }
        }
    }

    if (isSystem) {
        familyRow("LIGHT  ·  used when the device is in light mode", AppThemeMode.lightThemes(), selectedLight)
        Spacer(modifier = Modifier.height(12.dp))
        familyRow("DARK  ·  used when the device is in dark mode", AppThemeMode.darkThemes(), selectedDark)
    } else {
        val families = themeFamiliesFor(settings.appearanceMode)
        val selected = if (settings.appearanceMode.equals("LIGHT", ignoreCase = true)) selectedLight else selectedDark
        familyRow(null, families, selected)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ThemeSwatchCard(
    theme: AppThemeMode,
    scheme: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    activeNow: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val ringWidth = if (selected) 2.dp else 1.dp
    Column(
        modifier = Modifier
            .width(96.dp)
            .semantics {
                contentDescription = buildString {
                    append(theme.displayName)
                    append(if (theme.isLight) ", light theme" else ", dark theme")
                    if (selected) append(", selected")
                    if (activeNow) append(", active now")
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.background)
                .border(ringWidth, ringColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            // Miniature "card on canvas": surface panel with an outline, a primary pill and
            // two text lines in the family's own ink colours.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.surface)
                    .border(0.5.dp, scheme.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.primary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurface.copy(alpha = 0.85f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.8f))
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = theme.displayName,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (activeNow) {
            Text(
                text = "Active now",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AccentColorGrid(
    currentAccent: String,
    appearanceMode: String,
    onAccentSelected: (String) -> Unit
) {
    val selectedAccent = AppAccent.fromKey(currentAccent)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Accent Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(selectedAccent.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(modifier = Modifier.height(10.dp))
    // Single horizontally-scrollable row of all 10 accents in M6 declaration order
    // (INDIGO → OBSIDIAN). Selected swatch grows to 40dp with an onSurface ring + check;
    // unselected stays 32dp. Matches settings-main-dark.html .accentrow overflow-x:auto.
    // The grow animation is gated through scaledTween so Animation off / Transition
    // Behaviour NONE really disables it.
    val swatchSpec = com.example.ui.theme.scaledTween<Dp>(200)
    val isDarkCanvas = when (appearanceMode.uppercase()) {
        "LIGHT" -> false
        "DARK" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppAccent.values().forEach { accent ->
            val selected = accent == selectedAccent
            val swatchSize by animateDpAsState(if (selected) 40.dp else 32.dp, swatchSpec, label = "accentSwatch")
            // Show the variant that will actually be used as `primary` on the active canvas.
            val swatchColor = if (isDarkCanvas) accent.bright else accent.primary
            Box(
                modifier = Modifier
                    .size(swatchSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(swatchColor)
                    .then(
                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .clickable { onAccentSelected(accent.name) }
                    .semantics { contentDescription = accent.displayName + if (selected) ", selected" else "" },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.Check, null,
                        tint = com.example.ui.theme.readableInkOn(swatchColor, darkInk = Color(0xFF0B0F17)),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * Live preview of the effective theme family × accent. Everything inside is drawn through a
 * nested MaterialTheme whose colorScheme is `buildColorScheme(effectiveTheme, accent)` — the
 * identical function the app-level theme wrapper composes with — so this card can only show
 * colours the rest of the app is (or will be, on the next frame) painting.
 */
@Composable
private fun ThemeLivePreviewCard(settings: UserSettingsState) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val key = resolveEffectiveThemeKey(
        settings.appearanceMode, settings.themeMode, settings.selectedLightTheme, settings.selectedDarkTheme, systemDark
    )
    val theme = AppThemeMode.fromKey(key)
    val accent = AppAccent.fromKey(settings.selectedAccent)
    if (theme == null) {
        Text(
            text = "Stored theme key \"$key\" is not a known family — pick a theme above to repair it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    val scheme = remember(theme, accent) { com.example.ui.theme.buildColorScheme(theme, accent) }
    val radius = settings.cornerRadiusDp.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Preview", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "${theme.displayName} · ${accent.displayName}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(radius))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header capsule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("requestr", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            // Body card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((radius.value * 0.75f).dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape((radius.value * 0.75f).dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Body text on surface", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Secondary text uses onSurfaceVariant.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Primary", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Container", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Variant", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Error text", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun DensitySelector(
    currentMode: String,
    onModeSelected: (String) -> Unit,
) {
    val options = listOf("COMPACT" to "Compact", "COMFORTABLE" to "Comfortable")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Density", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "Tightens list spacing and card padding across Intercept and Saved APIs.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        RequestrSegmentedTabs(
            items = options.map { it.first },
            selectedItem = options.firstOrNull { it.first.equals(currentMode, ignoreCase = true) }?.first ?: "COMFORTABLE",
            onItemSelected = onModeSelected,
            label = { key -> options.first { it.first == key }.second }
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * 6-tier capture-method selector (settings-main-dark.html .tiergrid, flex-wrap).
 *
 * The backing [com.example.data.prefs.CaptureTierPreference] enum (frozen in SettingsDataStore,
 * consumed read-only by PreflightController) has only two values: AUTO (probe Root → Shizuku →
 * VPN, pick the best available) and FORCE_VPN_ONLY (always the rootless VPN path). The six
 * visual tiers below surface the capture methods requestr supports; selecting any non-VPN tier
 * maps to AUTO (auto-probe) while VPN maps to FORCE_VPN_ONLY. Root is the visual stand-in for
 * AUTO because it heads the probe chain, so an AUTO-backed preference highlights Root.
 *
 * A session-scoped [selectedLabel] override keeps the highlight on the tier the user actually
 * tapped instead of snapping back to Root whenever the backing value resolves to AUTO — the
 * override is not persisted because the frozen enum cannot distinguish which non-VPN tier was
 * chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyTierTypeSelector(
    currentTierPref: com.example.data.prefs.CaptureTierPreference,
    onTierSelected: (com.example.data.prefs.CaptureTierPreference) -> Unit,
) {
    val tiers = listOf(
        ProxyTierVisual("VPN", 0xFF3B82F6, com.example.data.prefs.CaptureTierPreference.FORCE_VPN_ONLY),
        ProxyTierVisual("Proxy", 0xFF06B6D4, com.example.data.prefs.CaptureTierPreference.AUTO),
        ProxyTierVisual("Root", 0xFF10B981, com.example.data.prefs.CaptureTierPreference.AUTO),
        ProxyTierVisual("Shizuku", 0xFF8B5CF6, com.example.data.prefs.CaptureTierPreference.AUTO),
        ProxyTierVisual("Frida", 0xFFF43F5E, com.example.data.prefs.CaptureTierPreference.AUTO),
        ProxyTierVisual("LSPosed", 0xFFF59E0B, com.example.data.prefs.CaptureTierPreference.AUTO),
    )
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    val effectiveLabel = selectedLabel ?: when (currentTierPref) {
        com.example.data.prefs.CaptureTierPreference.FORCE_VPN_ONLY -> "VPN"
        com.example.data.prefs.CaptureTierPreference.AUTO -> "Root"
    }
    Text("Default Capture Tier", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        "Auto probes Root → Shizuku → VPN and picks the best available. VPN forces the rootless path.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tiers.forEach { tier ->
            val on = tier.label == effectiveLabel
            val tierColor = Color(tier.color)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (on) tierColor.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                    .border(
                        1.dp,
                        if (on) tierColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable {
                        selectedLabel = tier.label
                        onTierSelected(tier.backing)
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(tierColor)
                )
                Text(
                    tier.label,
                    fontSize = 10.5.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) tierColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

private data class ProxyTierVisual(
    val label: String,
    val color: Long,
    val backing: com.example.data.prefs.CaptureTierPreference,
)

// B3: TokenPlaceholders() (Typography/Surfaces/Borders/Icons/States inert label list)
// removed — see call-site comment above for rationale.

// ─── Fix 02 + Fix 03 composables ──────────────────────────────────────

@Composable
private fun SubsectionTitle(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun PreferenceRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SwitchPreference(
    title: String,
    checked: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit
) {
    PreferenceRow(
        title = title,
        description = description
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SelectPreference(
    title: String,
    currentValue: String,
    description: String,
    options: List<Pair<String, String>>,
    onValueSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.find { it.first == currentValue }?.second ?: currentValue

    PreferenceRow(
        title = title,
        description = description
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                color = if (value == currentValue) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onValueSelected(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ====================================================================
// v6.0 transplant (manifest §5.10, Lane A) — ported from main's SettingsScreen
// ====================================================================

@Composable
private fun LiveRoundnessSettingCard(
    cornerRadiusDp: Int,
    onCornerRadiusChange: (Int) -> Unit
) {
    val presets = listOf(
        4 to "Sharp",
        10 to "Crisp",
        16 to "Modern",
        22 to "Organic",
        28 to "Pill"
    )

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with numeric indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "App Corner Curvature",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Live system-wide radius scale",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$cornerRadiusDp dp",
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            // Live Interactive Preview Block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(cornerRadiusDp.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        RoundedCornerShape(cornerRadiusDp.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(maxOf(2.dp, (cornerRadiusDp * 0.4).dp)))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Live Preview Card",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(cornerRadiusDp.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    Text(
                        "Components, cards, and modal sheets dynamically adjust their geometry to this radius in real-time.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp
                    )
                }
            }

            // Presets Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (presetDp, label) ->
                    val isSelected = cornerRadiusDp == presetDp
                    val chipShape = if (isSelected) RoundedCornerShape(20.dp) else RoundedCornerShape(8.dp)
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.5.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = chipShape
                            )
                            .clickable { onCornerRadiusChange(presetDp) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "$label ($presetDp)",
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Slider
            Slider(
                value = cornerRadiusDp.toFloat(),
                onValueChange = { onCornerRadiusChange(it.toInt()) },
                valueRange = 4f..32f,
                steps = 27,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─── Glass modifiers (Phase B) ────────────────────────────────────────

/**
 * Shared slider row used by every glass control so title / live value / description / track
 * colours are identical across the card. [enabled]=false greys the track and shows [disabledReason].
 */
@Composable
private fun GlassSliderRow(
    title: String,
    valueText: String,
    description: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                valueText,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (description != null) {
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        if (!enabled && disabledReason != null) {
            Text(disabledReason, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary, lineHeight = 15.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title, $valueText" }
        )
    }
}

/**
 * The one glass-style picker. Both the header and the nav pill render this, reading labels from
 * [GlassStyles.options], so the two surfaces can never show different names for the same key.
 * Selection compares canonical keys so a legacy persisted value ("CLEAR_GLASS", "M3", "OLED")
 * still highlights the right chip.
 */
@Composable
private fun GlassStyleChipRow(
    title: String,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    val selected: GlassStyleOption = GlassStyles.option(selectedKey)
    val sdk = android.os.Build.VERSION.SDK_INT
    Text(title, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlassStyles.options.forEach { option ->
            val isSel = option.key == selected.key
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(option.key) }
                    .semantics { contentDescription = "$title ${option.label}" + if (isSel) ", selected" else "" }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = option.label,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(selected.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
    if (selected.needsBlurApi && !GlassStyles.hasRealBlur(sdk)) {
        Text(
            text = "This device runs Android ${android.os.Build.VERSION.RELEASE} (API $sdk). Real backdrop blur needs " +
                "API ${GlassStyles.REAL_BLUR_MIN_API}+, so ${selected.label} renders its gradient fallback here — " +
                "tint, border and shadow still apply.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.tertiary,
            lineHeight = 15.sp
        )
    }
}

/** Colourful, hard-edged backdrop the Tune Glass preview blurs — hard edges make blur visible. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassBackdrop(
    area: Size,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    ink: Color,
) {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(primary, tertiary),
            start = Offset.Zero,
            end = Offset(area.width, area.height)
        ),
        size = area
    )
    drawCircle(color = secondary, radius = area.height * 0.42f, center = Offset(area.width * 0.22f, area.height * 0.30f))
    drawCircle(color = ink.copy(alpha = 0.85f), radius = area.height * 0.30f, center = Offset(area.width * 0.72f, area.height * 0.68f))
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = area.height * 0.16f, center = Offset(area.width * 0.55f, area.height * 0.22f))
    // "Text" lines
    val lineH = area.height * 0.05f
    for (i in 0 until 4) {
        val y = area.height * (0.15f + i * 0.2f)
        drawRect(
            color = ink.copy(alpha = 0.9f),
            topLeft = Offset(area.width * 0.38f, y),
            size = Size(area.width * (0.5f - i * 0.08f), lineH)
        )
    }
}

/**
 * Live Tune Glass preview: a nav-pill-shaped capsule floating over a busy backdrop. The capsule
 * draws its own copy of the backdrop (offset so it lines up) under `Modifier.blur(blurDp)`, then
 * the tint layer, border and shadow the four sliders control. `Modifier.blur` is a no-op below
 * API 31, so on Android 7–11 this preview honestly shows an un-blurred capsule — exactly what the
 * real nav pill and header will render on that device.
 */
@Composable
private fun GlassTunePreview(
    blurDp: Int,
    tintOpacity: Float,
    borderOpacity: Float,
    shadowDp: Int,
    cornerRadiusDp: Int,
) {
    val previewHeight = 132.dp
    val capsuleHeight = 56.dp
    val sidePad = 16.dp
    val capsuleShape = RoundedCornerShape(28.dp)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val ink = MaterialTheme.colorScheme.onBackground
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val hasRealBlur = GlassStyles.hasRealBlur(android.os.Build.VERSION.SDK_INT)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .drawBehind { drawGlassBackdrop(size, primary, secondary, tertiary, ink) }
            .semantics {
                contentDescription = "Tune Glass preview: blur $blurDp dp" +
                    (if (hasRealBlur) "" else " (no real blur on this Android version)") +
                    ", tint ${(tintOpacity * 100).toInt()} percent, border ${(borderOpacity * 100).toInt()} percent, shadow $shadowDp dp"
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = sidePad)
                .height(capsuleHeight)
                .shadow(elevation = shadowDp.dp, shape = capsuleShape, clip = false)
                .clip(capsuleShape)
        ) {
            // Backdrop copy, aligned to the outer backdrop and blurred by the user's radius.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurDp.dp, BlurredEdgeTreatment.Unbounded)
                    .drawBehind {
                        val dx = sidePad.toPx()
                        val dy = (previewHeight.toPx() - capsuleHeight.toPx()) / 2f
                        translate(left = -dx, top = -dy) {
                            drawGlassBackdrop(Size(size.width + dx * 2, previewHeight.toPx()), primary, secondary, tertiary, ink)
                        }
                    }
            )
            // Tint + border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surface.copy(alpha = tintOpacity))
                    .border(1.dp, outline.copy(alpha = borderOpacity), capsuleShape)
            )
            // Capsule content: four "tab" dots, first one active
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(width = if (i == 0) 36.dp else 18.dp, height = 18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (i == 0) primary.copy(alpha = 0.9f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Tune Glass sliders — only composed when at least one surface (header / nav pill) has
 * TUNE_GLASS selected, because these four values are read by nothing else. Slider ranges are
 * [GlassTuneRanges], the exact clamps the repository setters enforce.
 */
@Composable
private fun TuneGlassPanel(
    settings: UserSettingsState,
    appliesTo: List<String>,
    onUpdateBlur: (Int) -> Unit,
    onUpdateTintOpacity: (Float) -> Unit,
    onUpdateBorderOpacity: (Float) -> Unit,
    onUpdateShadowDp: (Int) -> Unit,
) {
    val sdk = android.os.Build.VERSION.SDK_INT
    val hasRealBlur = GlassStyles.hasRealBlur(sdk)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tune Glass", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "Applies to: ${appliesTo.joinToString(" + ")}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        GlassTunePreview(
            blurDp = settings.glassTuneBlurDp,
            tintOpacity = settings.glassTuneTintOpacity,
            borderOpacity = settings.glassTuneBorderOpacity,
            shadowDp = settings.glassTuneShadowDp,
            cornerRadiusDp = settings.cornerRadiusDp
        )
        GlassSliderRow(
            title = "Blur strength",
            valueText = "${settings.glassTuneBlurDp} dp",
            description = "Radius of the backdrop blur behind the glass.",
            value = settings.glassTuneBlurDp.toFloat(),
            onValueChange = { onUpdateBlur(it.toInt()) },
            valueRange = GlassTuneRanges.blurDp.first.toFloat()..GlassTuneRanges.blurDp.last.toFloat(),
            steps = GlassTuneRanges.blurDp.last - GlassTuneRanges.blurDp.first - 1,
            enabled = hasRealBlur,
            disabledReason = "Blur has no effect on Android ${android.os.Build.VERSION.RELEASE} (API $sdk) — it needs API " +
                "${GlassStyles.REAL_BLUR_MIN_API}+. Your saved value (${settings.glassTuneBlurDp} dp) is kept for newer devices."
        )
        GlassSliderRow(
            title = "Tint opacity",
            valueText = "${(settings.glassTuneTintOpacity * 100).toInt()}%",
            description = "How much of the surface colour sits over the blur. Lower is clearer.",
            value = settings.glassTuneTintOpacity,
            onValueChange = onUpdateTintOpacity,
            valueRange = GlassTuneRanges.tintOpacity
        )
        GlassSliderRow(
            title = "Border opacity",
            valueText = "${(settings.glassTuneBorderOpacity * 100).toInt()}%",
            description = "Strength of the 1 dp hairline around the glass.",
            value = settings.glassTuneBorderOpacity,
            onValueChange = onUpdateBorderOpacity,
            valueRange = GlassTuneRanges.borderOpacity
        )
        GlassSliderRow(
            title = "Shadow",
            valueText = "${settings.glassTuneShadowDp} dp",
            description = "Drop-shadow elevation under the glass.",
            value = settings.glassTuneShadowDp.toFloat(),
            onValueChange = { onUpdateShadowDp(it.toInt()) },
            valueRange = GlassTuneRanges.shadowDp.first.toFloat()..GlassTuneRanges.shadowDp.last.toFloat(),
            steps = GlassTuneRanges.shadowDp.last - GlassTuneRanges.shadowDp.first - 1
        )
    }
}

/** Surfaces currently set to Tune Glass, in display order. Pure for unit testing. */
internal fun tuneGlassSurfaces(headerBlurStyle: String, navBarBlurEffect: String): List<String> = buildList {
    if (GlassStyles.canonicalKey(headerBlurStyle) == GlassStyles.TUNE_GLASS) add("Header")
    if (GlassStyles.canonicalKey(navBarBlurEffect) == GlassStyles.TUNE_GLASS) add("Nav pill")
}

@Composable
private fun NavigationAndHeaderModifiersCard(
    settings: UserSettingsState,
    onUpdateNavOpacity: (Float) -> Unit,
    onUpdateNavBlurStyle: (String) -> Unit,
    onToggleHiddenNavItem: (String) -> Unit,
    onUpdateHeaderTopPadding: (Int) -> Unit,
    onUpdateHeaderOpacity: (Float) -> Unit,
    onUpdateHeaderBlurStyle: (String) -> Unit,
    onUpdateFabBottomSpacing: (Int) -> Unit,
    onUpdateGlassTuneBlur: (Int) -> Unit,
    onUpdateGlassTuneTintOpacity: (Float) -> Unit,
    onUpdateGlassTuneBorderOpacity: (Float) -> Unit,
    onUpdateGlassTuneShadowDp: (Int) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. Header Top Offset ──
            GlassSliderRow(
                title = "Header Top Offset",
                valueText = "${settings.headerTopPaddingDp} dp",
                description = "Pulls the floating capsule header down so it doesn't collide with the status bar.",
                value = settings.headerTopPaddingDp.toFloat(),
                onValueChange = { onUpdateHeaderTopPadding(it.toInt()) },
                valueRange = 0f..40f,
                steps = 7
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 2. Header glass ──
            Column {
                GlassSliderRow(
                    title = "Header Glass Opacity",
                    valueText = "${(settings.headerBackgroundOpacity * 100).toInt()}%",
                    description = null,
                    value = settings.headerBackgroundOpacity,
                    onValueChange = onUpdateHeaderOpacity,
                    valueRange = 0.1f..1.0f
                )
                Spacer(modifier = Modifier.height(6.dp))
                GlassStyleChipRow(
                    title = "Header glass style",
                    selectedKey = settings.headerBlurStyle,
                    onSelect = onUpdateHeaderBlurStyle
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 3. Nav pill glass ──
            Column {
                GlassSliderRow(
                    title = "Bottom Nav Pill Opacity",
                    valueText = "${(settings.navBarBackgroundOpacity * 100).toInt()}%",
                    description = "Transparency of the floating navigation pill.",
                    value = settings.navBarBackgroundOpacity,
                    onValueChange = onUpdateNavOpacity,
                    valueRange = 0.3f..1.0f
                )
                Spacer(modifier = Modifier.height(6.dp))
                GlassStyleChipRow(
                    title = "Nav pill glass style",
                    selectedKey = settings.navBarBlurEffect,
                    onSelect = onUpdateNavBlurStyle
                )
            }

            // ── 3b. Tune Glass sliders — only when a surface actually uses TUNE_GLASS ──
            val tuneSurfaces = tuneGlassSurfaces(settings.headerBlurStyle, settings.navBarBlurEffect)
            if (tuneSurfaces.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TuneGlassPanel(
                    settings = settings,
                    appliesTo = tuneSurfaces,
                    onUpdateBlur = onUpdateGlassTuneBlur,
                    onUpdateTintOpacity = onUpdateGlassTuneTintOpacity,
                    onUpdateBorderOpacity = onUpdateGlassTuneBorderOpacity,
                    onUpdateShadowDp = onUpdateGlassTuneShadowDp
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 4. FAB & Action Spacing from Bottom ──
            GlassSliderRow(
                title = "FAB & Bottom Elevation Spacing",
                valueText = "${settings.fabBottomSpacingDp} dp",
                description = "Keeps action buttons and FABs floating above the navigation pill.",
                value = settings.fabBottomSpacingDp.toFloat(),
                onValueChange = { onUpdateFabBottomSpacing(it.toInt()) },
                valueRange = 48f..140f,
                steps = 11
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 5. Hidden Navigation Items ──
            Column {
                Text(
                    "Customize Visible Navigation Items",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Tap to toggle visibility of tools in the bottom navigation pill and side drawer.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val allNavItems = listOf(
                    "home" to "Home",
                    "intercept" to "Interceptr",
                    "apis" to "API Testr",
                    "tools" to "Tools",
                    "notes" to "Notes",
                    "flow" to "Flow",
                    "music" to "Music",
                    "chat" to "Reqy Assistant"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allNavItems.forEach { (itemKey, itemLabel) ->
                        val isHidden = settings.navBarHiddenItems.contains(itemKey)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (!isHidden) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (!isHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onToggleHiddenNavItem(itemKey) }
                                .semantics { contentDescription = "$itemLabel, " + if (isHidden) "hidden" else "visible" }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!isHidden) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = itemLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (!isHidden) FontWeight.Bold else FontWeight.Normal,
                                    color = if (!isHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Local REST API & CLI (Phase B) ───────────────────────────────────

/** One route the embedded server actually serves (mirrors LocalApiServer.route's `when`). */
internal data class LocalApiEndpoint(val method: String, val path: String, val bearer: Boolean, val summary: String)

/**
 * Route table shown in Settings. Kept in the exact order LocalApiServer.route matches them.
 * `bearer` = true means the route rejects calls without `Authorization: Bearer <pairing token>`.
 */
internal val localApiEndpoints: List<LocalApiEndpoint> = listOf(
    LocalApiEndpoint("GET", "/api/ping", false, "Liveness check, returns status + API version"),
    LocalApiEndpoint("GET", "/api/status", false, "Server, interception and capture status"),
    LocalApiEndpoint("GET", "/api/pairing-info", false, "Pairing token + port — only while the reveal toggle is on"),
    LocalApiEndpoint("GET", "/api/captures", false, "Recent captured transactions"),
    LocalApiEndpoint("GET", "/api/captures/export", false, "Bulk export (from/to/cursor/limit/format)"),
    LocalApiEndpoint("GET", "/api/captures/{id}", false, "One capture with full body"),
    LocalApiEndpoint("GET", "/api/flows", false, "Saved flows"),
    LocalApiEndpoint("POST", "/api/flows", false, "Create a flow"),
    LocalApiEndpoint("POST", "/api/flows/{id}/execute", false, "Run a flow"),
    LocalApiEndpoint("DELETE", "/api/flows/{id}", false, "Delete a flow"),
    LocalApiEndpoint("GET", "/api/tools", false, "Built-in + plugin tools"),
    LocalApiEndpoint("POST", "/api/tools/execute", false, "Run a tool with input"),
    LocalApiEndpoint("GET", "/api/rules", true, "Intercept rules"),
    LocalApiEndpoint("POST", "/api/rules", true, "Create an intercept rule"),
    LocalApiEndpoint("PUT", "/api/rules/{id}", true, "Update an intercept rule"),
    LocalApiEndpoint("DELETE", "/api/rules/{id}", true, "Delete an intercept rule"),
    LocalApiEndpoint("POST", "/api/observe", true, "Ingest a transaction from the SDK / browser extension"),
    LocalApiEndpoint("POST", "/api/keylog", true, "Ingest TLS key-log lines"),
    LocalApiEndpoint("POST", "/api/plugin/execute", false, "Invoke a plugin tool"),
)

/** "m:ss" countdown text for the pairing-token reveal window. Never negative. */
internal fun formatRevealCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Colour for an HTTP method badge, reusing the app's method token palette. */
private fun methodBadgeColor(method: String): Long = when (method.uppercase()) {
    "GET" -> 0xFF22C55E
    "POST" -> 0xFF3B82F6
    "PUT" -> 0xFFF59E0B
    "DELETE" -> 0xFFEF4444
    else -> 0xFF64748B
}

@Composable
private fun CopyableCommandRow(label: String, command: String, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable {
                    clipboard.setText(AnnotatedString(command))
                    onCopied(label)
                }
                .semantics { contentDescription = "Copy $label" }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun LocalApiAndCliCard(interceptionDataStore: com.example.data.prefs.SettingsDataStore) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val running by com.example.api.LocalApiServer.isRunningFlow.collectAsState()
    // LocalApiServer.port is the value the socket is bound to; start() below always uses the
    // same default, so the URL shown here is the URL the server answers on.
    val port = com.example.api.LocalApiServer.port
    val baseUrl = "http://127.0.0.1:$port"

    val pairingToken by interceptionDataStore.pairingToken.collectAsState(initial = null)
    val showToken by interceptionDataStore.showPairingToken.collectAsState(initial = false)
    val revealUntil by interceptionDataStore.pairingTokenRevealUntil.collectAsState(initial = 0L)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var regenerateResult by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var endpointsExpanded by remember { mutableStateOf(false) }

    // Reveal window: tick once a second while revealed and flip the toggle off at the same
    // instant the server stops answering /api/pairing-info (revealUntil). Because the deadline
    // is the persisted timestamp — not a fresh 5-minute delay — leaving and re-entering Settings
    // cannot extend the window.
    LaunchedEffect(showToken, revealUntil) {
        if (!showToken) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (revealUntil <= now) {
                interceptionDataStore.setShowPairingToken(false)
                break
            }
            kotlinx.coroutines.delay(1_000L)
        }
    }
    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            kotlinx.coroutines.delay(2_000L)
            copiedLabel = null
        }
    }

    if (confirmRegenerate) {
        ConfirmActionDialog(
            title = "Regenerate pairing token?",
            message = "Every SDK integration and browser extension paired with the current token will be rejected (401) until you paste the new one into it.",
            confirmLabel = "Regenerate",
            onConfirm = {
                confirmRegenerate = false
                scope.launch {
                    regenerateResult = runCatching { com.example.api.LocalApiServer.regeneratePairingToken() }
                        .fold(
                            onSuccess = { "New token generated. Reveal it below to copy it." },
                            onFailure = { "Could not regenerate: ${it.message ?: it.javaClass.simpleName}" }
                        )
                }
            },
            onDismiss = { confirmRegenerate = false }
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Server ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Embedded HTTP API Server", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                BadgePill(text = if (running) "RUNNING" else "STOPPED", color = if (running) 0xFF10B981 else 0xFF64748B)
            }
            Text(
                "Serves captures, flows, tools and rules over plain HTTP on the loopback interface only " +
                    "(127.0.0.1 — not reachable from Wi-Fi). Off by default; it does not restart itself after the app restarts.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (running) "Listening on $baseUrl" else "Server stopped",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = if (running) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (running) "Port $port · ${localApiEndpoints.size} routes · ${localApiEndpoints.count { it.bearer }} need the pairing token"
                        else "Turn on to bind port $port",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                DynamicGlassSwitch(
                    checked = running,
                    onCheckedChange = { checked ->
                        if (checked) com.example.api.LocalApiServer.start(port) else com.example.api.LocalApiServer.stop()
                    },
                    modifier = Modifier.semantics { contentDescription = "Local API server " + if (running) "on" else "off" }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Quick start ──
            Text("Quick start", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            CopyableCommandRow("Ping from a terminal on this device (Termux / adb shell)", "curl $baseUrl/api/ping") { copiedLabel = it }
            CopyableCommandRow("Reach it from your computer over USB first", "adb forward tcp:$port tcp:$port") { copiedLabel = it }
            CopyableCommandRow(
                "Authenticated call (rules, observe, keylog)",
                "curl -H \"Authorization: Bearer <pairing token>\" $baseUrl/api/rules"
            ) { copiedLabel = it }

            // ── Endpoints ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { endpointsExpanded = !endpointsExpanded }
                    .semantics { contentDescription = if (endpointsExpanded) "Collapse endpoint list" else "Expand endpoint list" }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Endpoints (${localApiEndpoints.size})", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(if (endpointsExpanded) "Hide" else "Show", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }
            if (endpointsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    localApiEndpoints.forEach { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString("$baseUrl${ep.path}"))
                                    copiedLabel = "${ep.method} ${ep.path}"
                                }
                                .semantics { contentDescription = "${ep.method} ${ep.path}, ${ep.summary}" + if (ep.bearer) ", needs pairing token" else "" },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(60.dp)) {
                                BadgePill(text = ep.method, color = methodBadgeColor(ep.method))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(ep.path, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface)
                                    if (ep.bearer) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(Icons.Default.Security, contentDescription = "Needs pairing token", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(11.dp))
                                    }
                                }
                                Text(ep.summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "Tap a route to copy its full URL. Routes marked with a shield need Authorization: Bearer <pairing token>.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── CLI ──
            Text("Command-line client", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "The requestr CLI lives in this repository's cli/ module (build it with ./gradlew :cli:fatJar → cli/build/libs/cli-4.0-all.jar). " +
                    "Commands: captures, flows, tools, tool exec <id> <input>, rules, plugin <name> <json>, status, ping. " +
                    "Routes marked with a shield need the pairing token: pass --token <token> or export REQUESTR_TOKEN so it stays out of your shell history.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            CopyableCommandRow("After adb forward, from your computer", "java -jar cli-4.0-all.jar --api-base http://localhost:$port captures") { copiedLabel = it }
            CopyableCommandRow("Authenticated route via the CLI", "REQUESTR_TOKEN=<pairing token> java -jar cli-4.0-all.jar --api-base http://localhost:$port rules") { copiedLabel = it }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Pairing token ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SDK / extension pairing token", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "Bearer token the embedded requestr-sdk and the browser extension must send on /api/observe, /api/keylog and every /api/rules call. " +
                    "It is generated the first time the server starts and only ever shown here.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reveal token", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (showToken) "Hides automatically in ${formatRevealCountdown(revealUntil - now)}"
                        else "Also unlocks GET /api/pairing-info for ${com.example.data.prefs.SettingsDataStore.PAIRING_REVEAL_DURATION_MS / 60_000} minutes",
                        fontSize = 10.sp,
                        color = if (showToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                DynamicGlassSwitch(
                    checked = showToken,
                    onCheckedChange = { checked -> scope.launch { interceptionDataStore.setShowPairingToken(checked) } },
                    modifier = Modifier.semantics { contentDescription = "Reveal pairing token " + if (showToken) "on" else "off" }
                )
            }
            if (showToken) {
                val token = pairingToken
                if (token != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(token))
                                copiedLabel = "Pairing token"
                            }
                            .semantics { contentDescription = "Copy pairing token" }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            token,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            lineHeight = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                } else {
                    Text(
                        "No pairing token yet — turn the server on once to generate it.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { confirmRegenerate = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Regenerate token", fontSize = 12.sp)
                }
                if (copiedLabel != null) {
                    Text("Copied: $copiedLabel", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (regenerateResult != null) {
                Text(
                    regenerateResult!!,
                    fontSize = 10.5.sp,
                    color = if (regenerateResult!!.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Plugin Manager (Phase B) ─────────────────────────────────────────

@Composable
private fun PluginManagerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugins by com.example.plugin.PluginManager.plugins.collectAsState()
    val pluginDir = remember { com.example.plugin.PluginManager.externalPluginDir() }
    var rescanResult by remember { mutableStateOf<com.example.plugin.PluginScanResult?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    var unloadTarget by remember { mutableStateOf<com.example.plugin.PluginEntry?>(null) }

    unloadTarget?.let { entry ->
        ConfirmActionDialog(
            title = "Unload ${entry.plugin.name}?",
            message = "Its hooks stop running immediately. The file stays in ${pluginDir.path}; it will load again on the next app start or rescan.",
            confirmLabel = "Unload",
            onConfirm = {
                com.example.plugin.PluginManager.unregisterPlugin(entry.plugin.id)
                unloadTarget = null
            },
            onDismiss = { unloadTarget = null }
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Installed Plugins", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                val activeCount = plugins.values.count { it.enabled }
                BadgePill(text = "$activeCount / ${plugins.size} ACTIVE", color = if (activeCount > 0) 0xFF10B981 else 0xFF64748B)
            }
            Text(
                "Plugins can rewrite requests and responses, decode custom content types and add tools. " +
                    "Switching one off takes effect immediately and is remembered across app restarts and rescans.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            if (plugins.isEmpty()) {
                Text("No plugins loaded.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                plugins.values.sortedWith(compareBy({ it.source != com.example.plugin.PluginSource.BUILTIN }, { it.plugin.name })).forEach { entry ->
                    val p = entry.plugin
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("v${p.version}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    BadgePill(
                                        text = if (entry.source == com.example.plugin.PluginSource.BUILTIN) "BUILT-IN" else "EXTERNAL",
                                        color = if (entry.source == com.example.plugin.PluginSource.BUILTIN) 0xFF64748B else 0xFF8B5CF6
                                    )
                                }
                                Text(p.description, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                                Text("by ${p.author} · ${p.id}", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            DynamicGlassSwitch(
                                checked = entry.enabled,
                                onCheckedChange = { on -> com.example.plugin.PluginManager.setPluginEnabled(p.id, on) },
                                modifier = Modifier.semantics { contentDescription = "${p.name} " + if (entry.enabled) "enabled" else "disabled" }
                            )
                        }
                        if (entry.permissions.isEmpty()) {
                            Text("Requests no permissions.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                entry.permissions.forEach { perm ->
                                    BadgePill(text = perm.displayName, color = 0xFFF59E0B, modifier = Modifier.semantics { contentDescription = "${perm.displayName}: ${perm.description}" })
                                }
                            }
                        }
                        if (entry.source == com.example.plugin.PluginSource.EXTERNAL) {
                            TextButton(onClick = { unloadTarget = entry }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("Unload", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text("External plugins", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Drop a .jar or .dex that lists its Plugin class in META-INF/services/com.example.plugin.Plugin into the folder below, then rescan. " +
                    "The folder is scanned once at app start.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            Text(pluginDir.path, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        rescanning = true
                        scope.launch {
                            // The scanner reports its own counts (files seen, loaded, already
                            // loaded, failed) — nothing is inferred from before/after sizes.
                            val result = withContext(Dispatchers.IO) {
                                com.example.plugin.PluginManager.scanExternalPlugins(context)
                            }
                            rescanResult = result
                            rescanning = false
                        }
                    },
                    enabled = !rescanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (rescanning) "Scanning…" else "Rescan folder", fontSize = 12.sp)
                }
                rescanResult?.let { r ->
                    Text(
                        r.summary(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}
