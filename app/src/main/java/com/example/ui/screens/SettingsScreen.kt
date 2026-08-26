package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.example.data.interception.CaptureLogRepository
import com.example.data.repository.SettingsRepository
import com.example.data.interception.CaState
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import com.example.interception.CapabilityProbe
import com.example.interception.CertificateAuthority
import kotlinx.coroutines.launch
import com.example.ui.components.BadgePill
import com.example.ui.components.CdpCard
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.RuntimeInstrumentationCard
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
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

    val capabilities by InterceptionStateRepository.capabilities.collectAsState()
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
    val capturedCount by remember(settingsContext) {
        CaptureLogRepository.observeCount(settingsContext)
    }.collectAsState(initial = 0)
    val caSummary by produceState<Triple<String, String, String>?>(initialValue = null) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Settings & Config",
            onBack = onBack
        )

        LazyColumn(
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

            // Section 2: Theme — Fix 01
            item {
                SettingsSectionHeader("Theme")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            // Appearance Selector — System / Light / Dark
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

            // Accent Color Grid
            item {
                AccentColorGrid(
                    currentAccent = settings.selectedAccent,
                    appearanceMode = settings.appearanceMode,
                    onAccentSelected = SettingsRepository::updateAccent
                )
            }

            // B3: the old "Typography / Surfaces / Borders / Icons / States" placeholder
            // list (TokenPlaceholders) rendered five inert labels with zero interactivity,
            // zero backing UserSettingsState fields, and zero rendering hook anywhere in the
            // app's theme wrapper. Removed per the plan's stub-or-remove standard — there was
            // no underlying capability to attach real behavior to.

            // Re-run Setup Wizard — Fix 03
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

            // Display Behavior
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Display Behavior") }
            // Picture-in-Picture removed (B2): no video/camera surface exists anywhere in this
            // app's feature set — only an audio-only mini-player with no PiP manifest support —
            // so the toggle had no applicable target behavior and was removed per the plan's
            // stub-or-remove directive rather than kept as a dead switch.
            // B6: "Table Mode" removed — zero consumer anywhere in the codebase, and it
            // directly overlaps with the not-yet-built adaptive/multi-pane tablet layout
            // (Part C roadmap item #9). Per the plan, this must stay removed until #9 is
            // actually implemented, then reintroduced as a real, functional toggle.
            item {
                SelectPreference(
                    "Compact / Comfortable Density",
                    settings.densityMode,
                    "Control information density across the app",
                    listOf("COMPACT" to "Compact", "COMFORTABLE" to "Comfortable")
                ) { SettingsRepository.updateDensityMode(it) }
            }

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

            // Card Corner Radius (moved from old Section 3)
            item { Spacer(modifier = Modifier.height(8.dp)) }
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
            // (header top offset, header/nav opacity, blur styles, FAB spacing, hidden items)
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SubsectionTitle("Navigation & Header Glass Modifiers") }
            item {
                NavigationAndHeaderModifiersCard(
                    settings = settings,
                    onUpdateNavOpacity = { SettingsRepository.updateNavBarOpacity(it) },
                    onUpdateNavBlurStyle = { SettingsRepository.updateNavBarBlurEffect(it) },
                    onToggleHiddenNavItem = { SettingsRepository.toggleNavHiddenItem(it) },
                    onUpdateHeaderTopPadding = { SettingsRepository.updateHeaderTopPadding(it) },
                    onUpdateHeaderOpacity = { SettingsRepository.updateHeaderOpacity(it) },
                    onUpdateHeaderBlurStyle = { SettingsRepository.updateHeaderBlurStyle(it) },
                    onUpdateFabBottomSpacing = { SettingsRepository.updateFabBottomSpacing(it) }
                )
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

            // Section 5.5: Local REST API & CLI
            item {
                SettingsSectionHeader("LOCAL REST API & CLI")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    // Compose-observable: collectAsState() on the StateFlow so the label
                    // and Switch recompose immediately when start()/stop() flip the flag,
                    // instead of reading the old plain @Volatile field which never
                    // triggered recomposition.
                    val apiServerRunning by com.example.api.LocalApiServer.isRunningFlow.collectAsState()
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Terminal, contentDescription = "Terminal", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Embedded HTTP API Server", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Exposes captures, flows, tools, rules, and sessions on 127.0.0.1:9876 for CLI and automation access.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(if (apiServerRunning) "Server running on port 9876" else "Server stopped", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                if (apiServerRunning) {
                                    Text("curl http://127.0.0.1:9876/api/ping", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Switch(
                                checked = apiServerRunning,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        com.example.api.LocalApiServer.start(9876)
                                    } else {
                                        com.example.api.LocalApiServer.stop()
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("CLI module available at cli/build/libs/cli-all.jar. Run with: java -jar cli-all.jar --api-base http://127.0.0.1:9876 captures", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Section 5.4b: SDK Pairing (SDK & CONNECT scope, Task 6) -- the bearer token
            // gating `/api/observe`, which the embedded `requestr-sdk` (RequestrObserver) and the
            // MV3 browser extension both need before their POSTs are accepted. The token itself
            // is only ever revealed here, and only while "Show Pairing Token" is on.
            item {
                SettingsSectionHeader("SDK PAIRING")
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surface) {
                    val pairingTokenValue by interceptionDataStore.pairingToken.collectAsState(initial = null)
                    val showPairingToken by interceptionDataStore.showPairingToken.collectAsState(initial = false)
                    val clipboardManager = LocalClipboardManager.current
                    var copiedSnackbar by remember { mutableStateOf(false) }

                    // Auto-disable the reveal 5 minutes after it was turned on -- mirrors the
                    // LocalApiServer-side revealUntil check so the UI and the API agree.
                    LaunchedEffect(showPairingToken) {
                        if (showPairingToken) {
                            kotlinx.coroutines.delay(com.example.data.prefs.SettingsDataStore.PAIRING_REVEAL_DURATION_MS)
                            interceptionDataStore.setShowPairingToken(false)
                        }
                    }

                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Security, contentDescription = "Pairing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Third-Party App / Extension Pairing", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            "A third-party app's embedded requestr-sdk, or the browser extension, must present this bearer token on every /api/observe call. Reveal it here to copy it into that app/extension's setup.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Show Pairing Token", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Switch(
                                checked = showPairingToken,
                                onCheckedChange = { checked ->
                                    settingsScope.launch { interceptionDataStore.setShowPairingToken(checked) }
                                }
                            )
                        }

                        if (showPairingToken) {
                            if (pairingTokenValue != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(pairingTokenValue!!))
                                        copiedSnackbar = true
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        pairingTokenValue!!,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                                if (copiedSnackbar) {
                                    Text("Copied to clipboard", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                                Text(
                                    "Auto-hides in 5 minutes. GET /api/pairing-info returns this token + port ${com.example.api.LocalApiServer.port} while this toggle is on.",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "No pairing token yet -- start the Embedded HTTP API Server above to generate one.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                settingsScope.launch {
                                    runCatching { com.example.api.LocalApiServer.regeneratePairingToken() }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Regenerate Token", fontSize = 12.sp)
                        }
                    }
                }
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
                                com.example.data.repository.ChatRepository.saveProviderConfig(
                                    providerConfig.copy(
                                        baseUrl = urlInput.trim(),
                                        model = modelInput.trim(),
                                        apiKey = keyInput,
                                    )
                                )
                                android.widget.Toast.makeText(settingsContext, "AI provider saved.", android.widget.Toast.LENGTH_SHORT).show()
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
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val plugins = com.example.plugin.PluginManager.plugins.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Extension, contentDescription = "Plugin", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Installed Plugins", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (plugins.value.isEmpty()) {
                            Text("No plugins loaded", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            plugins.value.values.forEach { entry ->
                                val p = entry.plugin
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${p.name} v${p.version}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                        Text(p.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${p.author} · ${entry.permissions.size} permissions", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Switch(
                                        checked = entry.enabled,
                                        onCheckedChange = { on ->
                                            com.example.plugin.PluginManager.setPluginEnabled(p.id, on)
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("External plugins: /sdcard/requestr/plugins/*.jar", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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

                        // Regenerate CA button
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                settingsScope.launch {
                                    runCatching {
                                        CertificateAuthority.regenerate(settingsContext)
                                        android.widget.Toast.makeText(settingsContext, "CA regenerated — re-export and re-install", android.widget.Toast.LENGTH_SHORT).show()
                                    }
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

// ─── Fix 01 composables ────────────────────────────────────────────────

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        modes.forEach { (mode, label, icon) ->
            val isSelected = currentMode == mode
            val bgColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else Color.Transparent,
                animationSpec = com.example.ui.theme.scaledTween(200),
                label = "bg"
            )
            val iconTint by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = com.example.ui.theme.scaledTween(200),
                label = "iconTint"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun AccentColorGrid(
    currentAccent: String,
    appearanceMode: String,
    onAccentSelected: (String) -> Unit
) {
    val displayName = when (appearanceMode) {
        "LIGHT" -> "Light"
        "DARK" -> "Dark"
        else -> "System"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Accent Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(modifier = Modifier.height(10.dp))
    // Single horizontally-scrollable row of all 10 accents in M6 declaration order
    // (INDIGO → OBSIDIAN). Selected swatch grows to 40dp with an onSurface ring + check;
    // unselected stays 32dp. Matches settings-main-dark.html .accentrow overflow-x:auto.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppAccent.values().forEach { accent ->
            val selected = currentAccent.equals(accent.name, ignoreCase = true)
            val swatchSize by animateDpAsState(if (selected) 40.dp else 32.dp, tween(200), label = "accentSwatch")
            Box(
                modifier = Modifier
                    .size(swatchSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.primary)
                    .then(
                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .clickable { onAccentSelected(accent.name) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
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

@Composable
private fun NavigationAndHeaderModifiersCard(
    settings: com.example.data.models.UserSettingsState,
    onUpdateNavOpacity: (Float) -> Unit,
    onUpdateNavBlurStyle: (String) -> Unit,
    onToggleHiddenNavItem: (String) -> Unit,
    onUpdateHeaderTopPadding: (Int) -> Unit,
    onUpdateHeaderOpacity: (Float) -> Unit,
    onUpdateHeaderBlurStyle: (String) -> Unit,
    onUpdateFabBottomSpacing: (Int) -> Unit
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
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Header Top Offset (Status Bar Padding)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${settings.headerTopPaddingDp} dp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Pulls top floating capsule headers downward so they don't collide with the notification panel.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = settings.headerTopPaddingDp.toFloat(),
                    onValueChange = { onUpdateHeaderTopPadding(it.toInt()) },
                    valueRange = 0f..40f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 2. Header Background Opacity & Blur ──
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Header Glass Opacity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${(settings.headerBackgroundOpacity * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = settings.headerBackgroundOpacity,
                    onValueChange = { onUpdateHeaderOpacity(it) },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Header Glass Effect",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // DESIGN-RECORD §11: write canonical keys (not main's legacy keys)
                    listOf(
                        "FROSTED_GLASS" to "Frosted Glass",
                        "SOLID_M3" to "Material 3",
                        "TRANSLUCENT" to "Clear Tint",
                        "OLED_BLACK" to "OLED Black"
                    ).forEach { (styleKey, styleLabel) ->
                        val isSel = settings.headerBlurStyle == styleKey
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
                                .clickable { onUpdateHeaderBlurStyle(styleKey) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = styleLabel,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 3. Navigation Bar Opacity & Blur ──
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bottom Nav Pill Opacity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${(settings.navBarBackgroundOpacity * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Fine-tune the iOS-style glassmorphism blur and transparency of the floating navigation pill.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = settings.navBarBackgroundOpacity,
                    onValueChange = { onUpdateNavOpacity(it) },
                    valueRange = 0.3f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Nav Pill Glass Blur Style",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // DESIGN-RECORD §11: write canonical keys (not main's legacy keys)
                    listOf(
                        "FROSTED_GLASS" to "Frosted Glass",
                        "SOLID_M3" to "Material 3",
                        "OLED_BLACK" to "OLED Black",
                        "TRANSLUCENT" to "Translucent"
                    ).forEach { (effectKey, effectLabel) ->
                        val isSel = settings.navBarBlurEffect == effectKey
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
                                .clickable { onUpdateNavBlurStyle(effectKey) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = effectLabel,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 4. FAB & Action Spacing from Bottom ──
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FAB & Bottom Elevation Spacing",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${settings.fabBottomSpacingDp} dp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Ensures action buttons & FABs float comfortably above the bottom navigation pill and screen bezels.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = settings.fabBottomSpacingDp.toFloat(),
                    onValueChange = { onUpdateFabBottomSpacing(it.toInt()) },
                    valueRange = 48f..140f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

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
