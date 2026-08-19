package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.data.interception.CapabilityState
import com.example.data.interception.CaState
import com.example.data.interception.CaptureLogRepository
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import com.example.data.interception.TrafficExporter
import com.example.data.local.RuleAction as PersistedRuleAction
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.SettingsDataStore
import com.example.interception.CapabilityProbe
import com.example.interception.CertificateAuthority
import com.example.data.models.ApiRequestItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import com.example.data.models.RuleAction
import com.example.data.models.TrafficRuleItem
import com.example.data.models.CertificateDetails
import com.example.data.repository.FridaScript
import com.example.data.repository.TrafficInterceptorRepository
import com.example.ui.components.ContextActionItem
import com.example.ui.components.ContextualActionMenuDialog
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.SearchInputField
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusCodeBadge
import com.example.ui.components.formatByteCount
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptScreen(
    onSendToTestr: (ApiRequestItem) -> Unit,
    onMiniSettings: () -> Unit,
    onNavigateFullSettings: () -> Unit
) {
    val screenContext = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Real interception state: single source of truth shared with the wordmark dot.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    // Real capability results. Every field is UNKNOWN until an actual probe runs.
    val capabilities by InterceptionStateRepository.capabilities.collectAsState()

    // The traffic log now comes exclusively from Room, written only by the real capture engine.
    // v2.1 read an in-memory list that was seeded with fake rows and topped up by a Random
    // generator (APP_v2.md Bug 3.2), so a fresh install looked like it had captured traffic.
    val capturedTraffic by remember(screenContext) {
        CaptureLogRepository.observeTraffic(screenContext)
    }.collectAsState(initial = emptyList())

    val installedApps by TrafficInterceptorRepository.installedApps.collectAsState()
    val trafficRules by TrafficInterceptorRepository.trafficRules.collectAsState()

    // Persisted capture settings. These are the same values the VpnService reads, so what the
    // picker shows is exactly what gets applied.
    val settings = remember(screenContext) { SettingsDataStore.get(screenContext) }
    val appFilterMode by settings.appFilterMode.collectAsState(initial = AppFilterMode.ALL_APPS)
    val selectedPackages by settings.selectedPackages.collectAsState(initial = emptySet())
    val configuredPort by settings.proxyPort.collectAsState(initial = SettingsDataStore.DEFAULT_PROXY_PORT)

    // isCapturing is now derived from the real engine status rather than a free-floating boolean.
    val isCapturing = interceptionState.isActive
    val isArmed = interceptionState.isArmed
    val vpnPort = if (interceptionState.port > 0) interceptionState.port else configuredPort
    val vpnPacketsCount = interceptionState.packetCount
    val vpnBytesTransferred = interceptionState.bytesTransferred

    // Pre-flight outcome to surface (CA missing, no apps chosen, real failure reason).
    var startPrompt by remember { mutableStateOf<CaptureStartPrompt?>(null) }
    val captureController = rememberCaptureController(onPrompt = { startPrompt = it })

    // CA install result: the trust store is always re-probed afterwards, never assumed.
    val caInstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { captureController.reprobeAfterCaInstall() }

    // Probe real device capabilities when the screen is first shown, so chips reflect
    // this device rather than optimistic defaults. Also enumerate genuinely installed apps.
    LaunchedEffect(Unit) {
        CapabilityProbe.probeAll(screenContext)
        TrafficInterceptorRepository.loadInstalledApps(screenContext)
    }
    val fridaScripts = TrafficInterceptorRepository.fridaScripts

    var searchQuery by remember { mutableStateOf("") }
    var selectedMethodFilter by remember { mutableStateOf<HttpMethod?>(null) }

    var selectedItemForDetail by remember { mutableStateOf<CapturedTrafficItem?>(null) }
    var itemForContextMenu by remember { mutableStateOf<CapturedTrafficItem?>(null) }
    var showAppFilterSheet by remember { mutableStateOf(false) }
    var showRulesConfigSheet by remember { mutableStateOf(false) }
    var showFridaSheet by remember { mutableStateOf(false) }
    var showCertSheet by remember { mutableStateOf(false) }
    var showRootShizukuSheet by remember { mutableStateOf(false) }
    var showCaptureSetupSheet by remember { mutableStateOf(false) }

    val filteredTraffic = remember(capturedTraffic, searchQuery, selectedMethodFilter) {
        val baseFiltered = if (selectedMethodFilter == null) capturedTraffic
            else capturedTraffic.filter { it.method == selectedMethodFilter }
        if (searchQuery.isNotBlank()) {
            com.example.data.search.TrafficSearch.filter(baseFiltered, searchQuery)
        } else baseFiltered
    }

    // FAB colour is driven by the real engine status, and the three states are genuinely
    // distinct (APP_v2.md Bug 3.5): green = actively capturing, yellow/amber = armed but not
    // yet streaming, muted = idle/off.
    val fabColor by animateColorAsState(
        targetValue = when {
            isCapturing -> InterceptorActiveGreen
            isArmed -> InterceptorIdleAmber
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "fabColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Decluttered header (APP_v2.md Bug 3.7): title plus a single setup entry point.
            // The four capability chips and the app/rule/clear actions moved into the
            // "Capture setup" sheet rather than competing for the top bar.
            SectionHeader(
                title = "Interceptr",
                onMiniSettings = onMiniSettings,
                actions = {
                    IconButton(onClick = { showCaptureSetupSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Capture setup",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            // Live status readout. Every value here is real: status, tier, port and the packet
            // counter written by the capture engine.
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (interceptionState.status) {
                                            InterceptionStatus.ACTIVE -> InterceptorActiveGreen
                                            InterceptionStatus.ARMED -> InterceptorIdleAmber
                                            InterceptionStatus.ERROR -> InterceptorInactiveRed
                                            else -> TextMuted
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (interceptionState.status) {
                                    InterceptionStatus.ACTIVE ->
                                        "CAPTURING :$vpnPort - ${interceptionState.methodLabel}"
                                    InterceptionStatus.ARMED -> "ARMED :$vpnPort - waiting for traffic"
                                    InterceptionStatus.ERROR -> "ERROR"
                                    InterceptionStatus.CONFIGURED_IDLE -> "IDLE - not capturing"
                                    InterceptionStatus.NEVER_CONFIGURED -> "NOT CONFIGURED"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "$vpnPacketsCount pkts / ${formatByteCount(vpnBytesTransferred)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // A real error is shown verbatim instead of being swallowed.
                    interceptionState.errorText?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error,
                            fontSize = 10.sp,
                            color = InterceptorInactiveRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Search and Fast Method Filters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SearchInputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "status:500 method:POST host:... duration:>1000",
                    onClear = { searchQuery = "" }
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedMethodFilter == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedMethodFilter = null }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "ALL (${capturedTraffic.size})",
                            color = if (selectedMethodFilter == null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HttpMethod.values().forEach { method ->
                        val isSelected = selectedMethodFilter == method
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedMethodFilter = if (isSelected) null else method }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = method.name,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Traffic List
            if (filteredTraffic.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Real empty states (APP_v2.md Bug 3.3). Three genuinely different cases:
                    // filters hid everything, capture is running but nothing arrived yet, or
                    // nothing has ever been captured.
                    val hasFilter = searchQuery.isNotBlank() || selectedMethodFilter != null
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (hasFilter) Icons.Default.FilterList else Icons.Default.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when {
                                hasFilter -> "No captured request matches this filter."
                                isCapturing -> "Capturing on port $vpnPort. No HTTP transaction decoded yet."
                                else -> "Nothing captured yet."
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = when {
                                hasFilter -> "Clear the search box or the method filter to see the full log."
                                isCapturing -> "Traffic appears here as soon as the engine decodes a request."
                                else -> "Tap the button below to start recording traffic."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        // Point explicitly at the FAB when the user has never captured anything,
                        // so the empty screen tells them what to do next.
                        if (!hasFilter && !isCapturing) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Start here",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTraffic, key = { it.id }) { item ->
                        TrafficItemCard(
                            item = item,
                            onClick = { selectedItemForDetail = item },
                            onLongClick = { itemForContextMenu = item }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }

        // Bottom-right capture FAB (APP_v2.md Bug 3.5).
        // Colour encodes the real engine state and the icon cross-fades between states.
        // A slow scale-pulse runs only while genuinely capturing.
        val pulseTransition = rememberInfiniteTransition(label = "fabPulse")
        val pulseScale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isCapturing) 1.08f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fabPulseScale"
        )

        FloatingActionButton(
            onClick = {
                // Never a bare boolean flip: starting runs the full pre-flight gate, and
                // stopping tears down the real foreground service.
                if (isCapturing || isArmed) {
                    captureController.requestStop()
                } else {
                    captureController.requestStart()
                }
            },
            containerColor = fabColor,
            contentColor = if (isCapturing || isArmed) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 20.dp)
                .scale(pulseScale)
                .size(56.dp)
        ) {
            Crossfade(targetState = isCapturing || isArmed, label = "fabIcon") { running ->
                Icon(
                    // Telegraph/radio metaphor rather than a generic play/stop glyph.
                    imageVector = if (running) Icons.Default.SettingsInputAntenna else Icons.Default.Sensors,
                    contentDescription = if (running) "Stop capture" else "Start capture",
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }

    // Pre-flight outcomes. Each one is a real blocking condition, surfaced with the exact
    // remedy rather than a silent no-op.
    when (val prompt = startPrompt) {
        is CaptureStartPrompt.CaInstallRequired -> {
            ConfirmActionDialog(
                title = "Install the requestr CA certificate",
                message = "HTTPS traffic cannot be decrypted until the requestr root certificate " +
                    "is trusted by this device. Android will ask you to name the certificate and " +
                    "confirm your screen lock.",
                confirmLabel = "Install now",
                onConfirm = {
                    startPrompt = null
                    caInstallLauncher.launch(prompt.installIntent)
                },
                onDismiss = { startPrompt = null }
            )
        }

        CaptureStartPrompt.AppSelectionRequired -> {
            ConfirmActionDialog(
                title = "Choose what to capture",
                message = "Capture is limited to selected apps, but no app is selected yet. " +
                    "Pick at least one app, or switch to \"All apps\".",
                confirmLabel = "Choose apps",
                onConfirm = {
                    startPrompt = null
                    showAppFilterSheet = true
                },
                onDismiss = { startPrompt = null }
            )
        }

        is CaptureStartPrompt.Failed -> {
            ConfirmActionDialog(
                title = "Capture could not start",
                message = prompt.reason,
                confirmLabel = "OK",
                onConfirm = { startPrompt = null },
                onDismiss = { startPrompt = null }
            )
        }

        null -> Unit
    }

    // Capture setup sheet: the chips and controls that used to crowd the top bar.
    if (showCaptureSetupSheet) {
        CaptureSetupBottomSheet(
            capabilities = capabilities,
            port = vpnPort,
            appFilterMode = appFilterMode,
            selectedCount = selectedPackages.size,
            trafficCount = capturedTraffic.size,
            onOpenApps = {
                showCaptureSetupSheet = false
                showAppFilterSheet = true
            },
            onOpenRules = {
                showCaptureSetupSheet = false
                showRulesConfigSheet = true
            },
            onOpenCert = {
                showCaptureSetupSheet = false
                showCertSheet = true
            },
            onOpenFrida = {
                showCaptureSetupSheet = false
                showFridaSheet = true
            },
            onOpenPrivileges = {
                showCaptureSetupSheet = false
                showRootShizukuSheet = true
            },
            onReprobe = { captureController.refreshCapabilities() },
            onClearTraffic = {
                // Clears the real persisted rows; the Room Flow drives the list back to empty.
                screenScope.launch { CaptureLogRepository.clearAll(screenContext) }
                showCaptureSetupSheet = false
            },
            onDismiss = { showCaptureSetupSheet = false }
        )
    }

    // Frida sheet. Read-only: frida-server runs outside the app, so we report the probe result.
    if (showFridaSheet) {
        FridaScriptsBottomSheet(
            scripts = fridaScripts,
            fridaState = capabilities.frida,
            isProbing = capabilities.isProbing,
            onReprobe = { captureController.refreshCapabilities() },
            onDismiss = { showFridaSheet = false }
        )
    }

    // MITM CA sheet, backed by the real generated CA and a real trust-store probe.
    if (showCertSheet) {
        MitmCertificateBottomSheet(
            caState = capabilities.ca,
            isProbing = capabilities.isProbing,
            onInstall = {
                // Launches the genuine KeyChain install intent, then re-probes on return.
                screenScope.launch {
                    caInstallLauncher.launch(CertificateAuthority.buildInstallIntent(screenContext))
                }
            },
            onReprobe = { captureController.reprobeAfterCaInstall() },
            onExportPem = {
                screenScope.launch {
                    runCatching {
                        val pem = CertificateAuthority.exportPem(screenContext)
                        val file = com.example.data.export.FileExporter.writePem(screenContext, pem)
                        com.example.data.export.FileExporter.shareFile(
                            screenContext, file,
                            "application/x-pem-file",
                            "requestr Root CA (PEM)"
                        )
                    }
                }
            },
            onDismiss = { showCertSheet = false }
        )
    }

    // Root & Shizuku status sheet. Read-only: both privileges are granted outside the app.
    if (showRootShizukuSheet) {
        SystemIntegrationBottomSheet(
            rootState = capabilities.root,
            shizukuState = capabilities.shizuku,
            isProbing = capabilities.isProbing,
            onReprobe = { captureController.refreshCapabilities() },
            onDismiss = { showRootShizukuSheet = false }
        )
    }

    // Long-press contextual actions (APP_v2.md Bug 3.8).
    // "Block this domain" is disabled from a REAL query against the rules table, not a static
    // dimmed style, so the row only looks unavailable when a block rule genuinely exists.
    itemForContextMenu?.let { item ->
        val alreadyBlocked by produceState(initialValue = false, item.host) {
            value = CaptureLogRepository.hasBlockRule(screenContext, item.host)
        }

        ContextualActionMenuDialog(
            title = "${item.method.name} ${item.path}",
            subtitle = item.url,
            onDismiss = { itemForContextMenu = null },
            actions = listOf(
                ContextActionItem(
                    label = "Open detail",
                    icon = Icons.Default.Description,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { selectedItemForDetail = item }
                ),
                ContextActionItem(
                    label = "Send to API Testr",
                    icon = Icons.Default.Send,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val apiItem = ApiRequestItem(
                            id = UUID.randomUUID().toString(),
                            name = "${item.method} ${item.path}",
                            method = item.method,
                            url = item.url,
                            headers = item.requestHeaders.map { KeyValueItem(it.key, it.value) },
                            bodyType = if (item.requestBody.isNotBlank()) RequestBodyType.JSON else RequestBodyType.NONE,
                            bodyContent = item.requestBody
                        )
                        onSendToTestr(apiItem)
                    }
                ),
                ContextActionItem(
                    label = "Copy as cURL",
                    icon = Icons.Default.ContentCopy,
                    onClick = {
                        // Real clipboard write of a genuinely runnable command.
                        clipboardManager.setText(AnnotatedString(TrafficExporter.toCurl(item)))
                    }
                ),
                ContextActionItem(
                    label = "Add intercept rule from this",
                    icon = Icons.Default.AutoAwesome,
                    onClick = {
                        screenScope.launch {
                            CaptureLogRepository.addRuleFromTransaction(
                                context = screenContext,
                                item = item,
                                action = PersistedRuleAction.MODIFY_RESPONSE,
                            )
                        }
                    }
                ),
                ContextActionItem(
                    label = if (alreadyBlocked) "Domain already blocked" else "Block this domain",
                    icon = Icons.Default.Block,
                    iconTint = if (alreadyBlocked) null else InterceptorInactiveRed,
                    // Genuinely non-interactive when a rule exists, per the real DB check above.
                    enabled = !alreadyBlocked,
                    onClick = {
                        screenScope.launch {
                            CaptureLogRepository.addBlockRule(screenContext, item.host)
                        }
                    }
                ),
                ContextActionItem(
                    label = "Export as HAR",
                    icon = Icons.Default.Share,
                    onClick = {
                        runCatching {
                            val har = TrafficExporter.toHar(item)
                            val host = try {
                                android.net.Uri.parse(item.url).host ?: "unknown"
                            } catch (_: Exception) { "unknown" }
                            val file = com.example.data.export.FileExporter.writeHarBlocking(screenContext, har, host)
                            com.example.data.export.FileExporter.shareFile(
                                screenContext, file,
                                "application/json",
                                "requestr HAR export"
                            )
                        }
                    }
                ),
                ContextActionItem(
                    label = "Delete entry",
                    icon = Icons.Default.Delete,
                    iconTint = InterceptorInactiveRed,
                    onClick = {
                        // Deletes the real persisted row; the list updates from the Room Flow.
                        screenScope.launch {
                            CaptureLogRepository.deleteEntry(screenContext, item.id)
                        }
                    }
                )
            )
        )
    }

    // App picker. Mode and selection are persisted in DataStore, so the choice survives
    // process death and is the same set the VpnService applies via addAllowedApplication.
    if (showAppFilterSheet) {
        AppFilterBottomSheet(
            installedApps = installedApps,
            filterMode = appFilterMode,
            selectedPackages = selectedPackages,
            onDismiss = { showAppFilterSheet = false },
            onFilterModeChange = { mode ->
                screenScope.launch { settings.setAppFilterMode(mode) }
            },
            onToggleApp = { pkg, selected ->
                screenScope.launch {
                    val next = if (selected) selectedPackages + pkg else selectedPackages - pkg
                    settings.setSelectedPackages(next)
                }
            },
            onSelectAll = {
                screenScope.launch {
                    settings.setSelectedPackages(installedApps.map { it.packageName }.toSet())
                }
            },
            onDeselectAll = {
                screenScope.launch { settings.setSelectedPackages(emptySet()) }
            }
        )
    }

    // Rules & Modifiers Sheet
    if (showRulesConfigSheet) {
        RulesConfigBottomSheet(
            rules = trafficRules,
            onDismiss = { showRulesConfigSheet = false },
            onToggleRule = { id -> TrafficInterceptorRepository.toggleRule(id) },
            onDeleteRule = { id -> TrafficInterceptorRepository.deleteRule(id) },
            onAddRule = { rule -> TrafficInterceptorRepository.addRule(rule) }
        )
    }

    // Full Request/Response Detail Sheet
    selectedItemForDetail?.let { item ->
        CapturedDetailBottomSheet(
            item = item,
            onDismiss = { selectedItemForDetail = null },
            onSendToTestr = {
                val apiItem = ApiRequestItem(
                    id = UUID.randomUUID().toString(),
                    name = "${item.method} ${item.path}",
                    method = item.method,
                    url = item.url,
                    headers = item.requestHeaders.map { KeyValueItem(it.key, it.value) },
                    bodyType = if (item.requestBody.isNotBlank()) RequestBodyType.JSON else RequestBodyType.NONE,
                    bodyContent = item.requestBody
                )
                selectedItemForDetail = null
                onSendToTestr(apiItem)
            }
        )
    }
}

@Composable
fun EngineChip(
    label: String,
    isActive: Boolean,
    isUnavailable: Boolean = false,
    onClick: () -> Unit
) {
    // Three visually distinct states so "granted" can never be confused with
    // "unavailable" or "still checking" (APP_v2.md Bug 3.1).
    val background = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        else -> Color.Transparent
    }
    val borderColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isUnavailable -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val textColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isUnavailable -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridaScriptsBottomSheet(
    scripts: List<FridaScript>,
    fridaState: FridaState,
    isProbing: Boolean,
    onReprobe: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Frida Dynamic Instrumentation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Read-only status, not a switch.
            // v2.1 rendered a "SSL Pinning Universal Bypass" toggle here, implying the app could
            // enable Frida hooks itself. It cannot: frida-server must be pushed and run on the
            // device out-of-band. Flipping it only changed a local boolean, which is why the
            // chip always read as bypassed (APP_v2.md Bug 3.1). We report the real probe result.
            CapabilityStatusCard(
                title = "frida-server on 127.0.0.1:27042",
                description = when (fridaState) {
                    FridaState.RUNNING -> "Reachable. SSL-pinning bypass scripts can be attached with the Frida CLI from your workstation."
                    FridaState.NOT_RUNNING -> "Not reachable. Push and start frida-server on this device (requires root), then re-check."
                    FridaState.UNKNOWN -> "Not probed yet."
                },
                statusLabel = when (fridaState) {
                    FridaState.RUNNING -> "Running"
                    FridaState.NOT_RUNNING -> "Not running"
                    FridaState.UNKNOWN -> "Unknown"
                },
                isPositive = fridaState == FridaState.RUNNING,
                isNegative = fridaState == FridaState.NOT_RUNNING
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onReprobe,
                enabled = !isProbing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isProbing) "Checking..." else "Re-check frida-server", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))
            // Reference snippets only. requestr does not inject these; run them via the Frida
            // CLI against frida-server. Labelled accordingly so the list does not imply
            // in-app injection that is not implemented.
            Text("REFERENCE SCRIPTS (run via Frida CLI)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts) { script ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(script.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(script.framework, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(script.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitmCertificateBottomSheet(
    caState: CaState,
    isProbing: Boolean,
    onInstall: () -> Unit,
    onReprobe: () -> Unit,
    onExportPem: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val certContext = LocalContext.current

    // Real certificate metadata, read from the CA that actually exists in app storage.
    // v2.1 displayed a hardcoded serial/fingerprint that matched no real key
    // (APP_v2.md Bug 3.1), so users could not compare it against the installed certificate.
    val material by produceState<CertificateSummary?>(initialValue = null) {
        value = runCatching {
            val ca = CertificateAuthority.getOrCreate(certContext)
            val cert = ca.certificate
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            CertificateSummary(
                subject = cert.subjectX500Principal.name,
                serial = cert.serialNumber.toString(16).uppercase(),
                validFrom = fmt.format(cert.notBefore),
                validTo = fmt.format(cert.notAfter),
                fingerprint = fingerprint
            )
        }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("MITM Root CA Certificate", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Trust-store status from a genuine trust-store probe.
            CapabilityStatusCard(
                title = "Trust store status",
                description = when (caState) {
                    CaState.SYSTEM_STORE -> "Installed in the system store. HTTPS from most apps can be decrypted."
                    CaState.USER_STORE -> "Installed in the user store. Apps that opt out of user CAs will still fail to decrypt."
                    CaState.NOT_INSTALLED -> "Not installed. HTTPS traffic cannot be decrypted until this certificate is trusted."
                    CaState.UNKNOWN -> "Not probed yet."
                },
                statusLabel = when (caState) {
                    CaState.SYSTEM_STORE -> "System store"
                    CaState.USER_STORE -> "User store"
                    CaState.NOT_INSTALLED -> "Not installed"
                    CaState.UNKNOWN -> "Unknown"
                },
                isPositive = caState == CaState.SYSTEM_STORE || caState == CaState.USER_STORE,
                isNegative = caState == CaState.NOT_INSTALLED
            )

            Spacer(modifier = Modifier.height(10.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val summary = material
                    if (summary == null) {
                        Text("Reading certificate...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Subject: ${summary.subject}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Serial: ${summary.serial}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(
                            "Valid: ${summary.validFrom} -> ${summary.validTo}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "SHA-256: ${summary.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Install certificate", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onReprobe,
                    enabled = !isProbing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(if (isProbing) "Checking..." else "Re-check", fontSize = 12.sp)
                }
                Button(
                    onClick = onExportPem,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Export PEM", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "System-store installation requires root and a writable /system. " +
                    "Export the PEM and place it in the system trust store manually.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Real certificate metadata read from the generated CA. */
data class CertificateSummary(
    val subject: String,
    val serial: String,
    val validFrom: String,
    val validTo: String,
    val fingerprint: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemIntegrationBottomSheet(
    rootState: RootState,
    shizukuState: ShizukuState,
    isProbing: Boolean,
    onReprobe: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Root & Shizuku Privileges", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            // These are read-only status readouts, not switches.
            // v2.1 rendered Switches here, implying the app could grant itself root or Shizuku
            // access. It cannot: both are granted outside the app (su manager prompt, Shizuku
            // app + ADB pairing). Flipping a switch only ever changed a local boolean, which is
            // why the chips always looked "on" (APP_v2.md Bug 3.1). We now report what the
            // real probe found and offer a re-check instead.
            CapabilityStatusCard(
                title = "Magisk / KernelSU root access",
                description = when (rootState) {
                    RootState.GRANTED -> "Granted. su responded with uid=0, so transparent iptables redirection and system trust-store injection are possible."
                    RootState.AVAILABLE -> "An su binary exists but access is not confirmed yet. Approve the request in your root manager, then re-check."
                    RootState.DENIED -> "The su request was denied. Grant requestr in your root manager (Magisk/KernelSU), then re-check."
                    RootState.UNAVAILABLE -> "No su binary found. This device is not rooted, so root-tier capture is unavailable."
                    RootState.UNKNOWN -> "Not probed yet."
                },
                statusLabel = when (rootState) {
                    RootState.GRANTED -> "Granted"
                    RootState.AVAILABLE -> "Available"
                    RootState.DENIED -> "Denied"
                    RootState.UNAVAILABLE -> "Unavailable"
                    RootState.UNKNOWN -> "Unknown"
                },
                isPositive = rootState == RootState.GRANTED,
                isNegative = rootState == RootState.UNAVAILABLE || rootState == RootState.DENIED
            )

            CapabilityStatusCard(
                title = "Shizuku ADB service",
                description = when (shizukuState) {
                    ShizukuState.GRANTED -> "Granted. Elevated ADB permissions are available for non-root proxy setup."
                    ShizukuState.PERMISSION_DENIED -> "Shizuku is running but permission was denied. Grant requestr inside the Shizuku app, then re-check."
                    ShizukuState.NOT_RUNNING -> "Shizuku is installed but not running. Start it via ADB pairing or wireless debugging, then re-check."
                    ShizukuState.NOT_INSTALLED -> "Shizuku is not installed. Install the Shizuku app to enable this tier."
                    ShizukuState.UNKNOWN -> "Not probed yet."
                },
                statusLabel = when (shizukuState) {
                    ShizukuState.GRANTED -> "Granted"
                    ShizukuState.PERMISSION_DENIED -> "Denied"
                    ShizukuState.NOT_RUNNING -> "Not running"
                    ShizukuState.NOT_INSTALLED -> "Not installed"
                    ShizukuState.UNKNOWN -> "Unknown"
                },
                isPositive = shizukuState == ShizukuState.GRANTED,
                isNegative = shizukuState == ShizukuState.NOT_INSTALLED ||
                    shizukuState == ShizukuState.PERMISSION_DENIED
            )

            Button(
                onClick = onReprobe,
                enabled = !isProbing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isProbing) "Checking..." else "Re-check privileges", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "Capture tier is chosen automatically: root first, then Shizuku, then VPN.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Read-only capability readout. Three visually distinct states, never a fake toggle. */
@Composable
private fun CapabilityStatusCard(
    title: String,
    description: String,
    statusLabel: String,
    isPositive: Boolean,
    isNegative: Boolean
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                isPositive -> InterceptorActiveGreen.copy(alpha = 0.18f)
                                isNegative -> InterceptorInactiveRed.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            isPositive -> InterceptorActiveGreen
                            isNegative -> InterceptorInactiveRed
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrafficItemCard(
    item: CapturedTrafficItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MethodBadge(method = item.method)
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusCodeBadge(statusCode = item.statusCode, statusMessage = item.statusMessage)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(protocol = item.protocol)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${item.latencyMs}ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.path.ifBlank { "/" },
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )

            Text(
                text = "${item.appName} · ${item.host}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


/**
 * Capture setup sheet.
 *
 * The four capability chips and the app/rule/clear controls used to crowd the top bar
 * (APP_v2.md Bug 3.7). They live here now, each chip reporting a genuine probe result and
 * opening the sub-screen that can actually change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSetupBottomSheet(
    capabilities: CapabilityState,
    port: Int,
    appFilterMode: AppFilterMode,
    selectedCount: Int,
    trafficCount: Int,
    onOpenApps: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenCert: () -> Unit,
    onOpenFrida: () -> Unit,
    onOpenPrivileges: () -> Unit,
    onReprobe: () -> Unit,
    onClearTraffic: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Capture setup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                text = "Capture tier is chosen automatically: root first, then Shizuku, then VPN.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Each chip reflects the REAL probe result. "Unavailable"/"Not running" render in a
            // visually distinct style so they can never be mistaken for "granted".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EngineChip(
                    label = when (capabilities.ca) {
                        CaState.SYSTEM_STORE -> "CA: System store"
                        CaState.USER_STORE -> "CA: User store"
                        CaState.NOT_INSTALLED -> "CA: Not installed"
                        CaState.UNKNOWN -> "CA: Checking..."
                    },
                    isActive = capabilities.isCaInstalled,
                    isUnavailable = capabilities.ca == CaState.NOT_INSTALLED,
                    onClick = onOpenCert
                )
                EngineChip(
                    label = when (capabilities.root) {
                        RootState.GRANTED -> "Root: Granted"
                        RootState.AVAILABLE -> "Root: Available"
                        RootState.DENIED -> "Root: Denied"
                        RootState.UNAVAILABLE -> "Root: Unavailable"
                        RootState.UNKNOWN -> "Root: Checking..."
                    },
                    isActive = capabilities.root == RootState.GRANTED,
                    isUnavailable = capabilities.root == RootState.UNAVAILABLE ||
                        capabilities.root == RootState.DENIED,
                    onClick = onOpenPrivileges
                )
                EngineChip(
                    label = when (capabilities.shizuku) {
                        ShizukuState.GRANTED -> "Shizuku: Granted"
                        ShizukuState.PERMISSION_DENIED -> "Shizuku: Denied"
                        ShizukuState.NOT_RUNNING -> "Shizuku: Not running"
                        ShizukuState.NOT_INSTALLED -> "Shizuku: Not installed"
                        ShizukuState.UNKNOWN -> "Shizuku: Checking..."
                    },
                    isActive = capabilities.shizuku == ShizukuState.GRANTED,
                    isUnavailable = capabilities.shizuku == ShizukuState.NOT_INSTALLED ||
                        capabilities.shizuku == ShizukuState.PERMISSION_DENIED,
                    onClick = onOpenPrivileges
                )
                EngineChip(
                    label = when (capabilities.frida) {
                        FridaState.RUNNING -> "Frida: Running"
                        FridaState.NOT_RUNNING -> "Frida: Not running"
                        FridaState.UNKNOWN -> "Frida: Checking..."
                    },
                    isActive = capabilities.frida == FridaState.RUNNING,
                    isUnavailable = capabilities.frida == FridaState.NOT_RUNNING,
                    onClick = onOpenFrida
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SetupRow(
                icon = Icons.Default.Apps,
                title = "Apps to capture",
                // Real summary of the persisted selection.
                subtitle = if (appFilterMode == AppFilterMode.ALL_APPS) "All apps"
                else "$selectedCount app(s) selected",
                onClick = onOpenApps
            )
            SetupRow(
                icon = Icons.Default.Tune,
                title = "Rules & modifiers",
                subtitle = "Block, redirect or rewrite matched traffic",
                onClick = onOpenRules
            )
            SetupRow(
                icon = Icons.Default.Security,
                title = "CA certificate",
                subtitle = "Generate, install and verify the MITM root CA",
                onClick = onOpenCert
            )
            SetupRow(
                icon = Icons.Default.Bolt,
                title = "Root & Shizuku",
                subtitle = "Check elevated capture privileges",
                onClick = onOpenPrivileges
            )
            SetupRow(
                icon = Icons.Default.Refresh,
                title = if (capabilities.isProbing) "Re-checking capabilities..." else "Re-check capabilities",
                subtitle = "Probe root, Shizuku, CA trust store and Frida again",
                onClick = onReprobe
            )
            SetupRow(
                icon = Icons.Default.ClearAll,
                title = "Clear capture log",
                // Real row count so the action states exactly what it will delete.
                subtitle = if (trafficCount == 0) "Nothing captured yet"
                else "Delete all $trafficCount captured transaction(s)",
                onClick = onClearTraffic,
                enabled = trafficCount > 0,
                tint = InterceptorInactiveRed
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Local proxy port: $port",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SetupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            else tint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterBottomSheet(
    installedApps: List<com.example.data.models.AppPackageItem>,
    filterMode: AppFilterMode,
    selectedPackages: Set<String>,
    onDismiss: () -> Unit,
    onFilterModeChange: (AppFilterMode) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pm = LocalContext.current.packageManager
    var appQuery by remember { mutableStateOf("") }

    val allApps = filterMode == AppFilterMode.ALL_APPS
    val visibleApps = remember(installedApps, appQuery) {
        if (appQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Apps to capture",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // "All apps" is a real mode stored in DataStore, and while it is on the individual
            // checkboxes below are genuinely disabled and greyed out (APP_v2.md Bug 3.6)
            // rather than remaining tappable but ignored.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onFilterModeChange(
                            if (allApps) AppFilterMode.SELECTED_APPS else AppFilterMode.ALL_APPS
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("All apps", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Capture every app's traffic. Turn off to choose specific apps.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = allApps,
                    onCheckedChange = { checked ->
                        onFilterModeChange(if (checked) AppFilterMode.ALL_APPS else AppFilterMode.SELECTED_APPS)
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            SearchInputField(
                query = appQuery,
                onQueryChange = { appQuery = it },
                placeholder = "Search installed apps...",
                onClear = { appQuery = "" }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Real selected count from the persisted set.
                Text(
                    text = if (allApps) "All apps selected"
                    else "${selectedPackages.size} of ${installedApps.size} selected",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Select all",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (allApps) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !allApps) { onSelectAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Clear",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (allApps) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !allApps) { onDeselectAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (installedApps.isEmpty()) {
                // Real empty state: the package query genuinely returned nothing yet.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Reading installed apps...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        val checked = allApps || selectedPackages.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !allApps) {
                                    onToggleApp(app.packageName, !selectedPackages.contains(app.packageName))
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Real launcher icon from PackageManager, so the list is recognisable.
                            val iconPainter = remember(app.packageName) {
                                runCatching {
                                    BitmapPainter(
                                        pm.getApplicationIcon(app.packageName)
                                            .toBitmap(96, 96)
                                            .asImageBitmap()
                                    )
                                }.getOrNull()
                            }
                            if (iconPainter != null) {
                                Image(
                                    painter = iconPainter,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .alpha(if (allApps) 0.45f else 1f)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    // Greyed out while "All apps" is on, matching the disabled checkbox.
                                    color = if (allApps) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = app.packageName,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (allApps) 0.4f else 1f
                                    ),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Checkbox(
                                checked = checked,
                                // Genuinely non-interactive in "All apps" mode.
                                enabled = !allApps,
                                onCheckedChange = { value -> onToggleApp(app.packageName, value) },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesConfigBottomSheet(
    rules: List<TrafficRuleItem>,
    onDismiss: () -> Unit,
    onToggleRule: (String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onAddRule: (TrafficRuleItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rules & Modifiers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rules, key = { it.id }) { rule ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(rule.urlPattern, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                Text("Action: ${rule.action.name}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDeleteRule(rule.id) }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturedDetailBottomSheet(
    item: CapturedTrafficItem,
    onDismiss: () -> Unit,
    onSendToTestr: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MethodBadge(method = item.method)
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusCodeBadge(statusCode = item.statusCode, statusMessage = item.statusMessage)
                }

                Row {
                    IconButton(onClick = onSendToTestr) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Send to Testr", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(item.url, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)

            Spacer(modifier = Modifier.height(4.dp))

            // Source + Protocol badges
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BadgePill(text = item.trafficSource.label, color = 0xFF64748B)
                BadgePill(text = item.protocolHint.shortLabel.uppercase(), color = 0xFF0EA5E9)
                if (item.tlsVersion.isNotBlank()) {
                    BadgePill(text = item.tlsVersion, color = 0xFF10B981)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                listOf("Overview", "Headers", "Body", "Timing", "TLS", "Raw").forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, fontSize = 11.sp, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                LazyColumn {
                    when (selectedTab) {
                        0 -> item { // Overview
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DetailRow("Method", item.method.name)
                                DetailRow("URL", item.url)
                                DetailRow("Host", item.host)
                                DetailRow("Path", item.path)
                                DetailRow("Status", "${item.statusCode} ${item.statusMessage}")
                                DetailRow("Protocol", item.protocol)
                                DetailRow("Latency", "${item.latencyMs}ms")
                                DetailRow("Req Size", formatBytes(item.requestSize))
                                DetailRow("Res Size", formatBytes(item.responseSize))
                                DetailRow("App", item.appName)
                                DetailRow("Source", item.trafficSource.label)
                                DetailRow("Proto", item.protocolHint.label)
                                item.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                                    DetailRow("Tags", tags.joinToString(", "))
                                }
                            }
                        }
                        1 -> item { // Headers
                            Column {
                                Text("REQUEST:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                item.requestHeaders.forEach { (k, v) ->
                                    Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("RESPONSE:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                item.responseHeaders.forEach { (k, v) ->
                                    Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                                // JWT detection — check Authorization header
                                val authHeader = item.requestHeaders["Authorization"]
                                    ?: item.requestHeaders["authorization"]
                                if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    com.example.ui.components.JwtDecoder(token = authHeader.removePrefix("Bearer ").trim())
                                }
                            }
                        }
                        2 -> item { // Body
                            Column {
                                if (item.responseBody.isNotBlank()) {
                                    Text("RESPONSE BODY:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(item.responseBody, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                if (item.requestBody.isNotBlank()) {
                                    Text("REQUEST BODY:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                    Text(item.requestBody, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                                if (item.requestBody.isBlank() && item.responseBody.isBlank()) {
                                    Text("(Empty)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                        3 -> item { // Timing
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimingBar("DNS", item.dnsMs)
                                TimingBar("Connect", item.connectMs)
                                TimingBar("TLS", item.tlsMs)
                                TimingBar("Wait (TTFB)", item.waitMs)
                                TimingBar("Download", item.downloadMs)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                Text("Total: ${item.latencyMs}ms", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        4 -> item { // TLS
                            if (item.tlsVersion.isBlank()) {
                                Text("No TLS data available", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DetailRow("TLS Version", item.tlsVersion)
                                    DetailRow("Cipher", item.tlsCipher)
                                    DetailRow("ALPN", item.alpnProtocol.ifBlank { "—" })
                                    DetailRow("Handshake", if (item.tlsMs >= 0) "${item.tlsMs}ms" else "—")
                                    DetailRow("Protocol", item.protocolHint.label)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                    Text("Certificate:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    Text("Issued by requestr CA", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Trust: User store", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }
                        5 -> item { // Raw
                            Text(
                                text = buildString {
                                    append("${item.method.name} ${item.url} ${item.protocol}\n")
                                    item.requestHeaders.forEach { (k, v) -> append("$k: $v\n") }
                                    if (item.requestBody.isNotBlank()) append("\n${item.requestBody}\n")
                                    append("\n--- Response ${item.statusCode} ---\n")
                                    item.responseHeaders.forEach { (k, v) -> append("$k: $v\n") }
                                    if (item.responseBody.isNotBlank()) append("\n${item.responseBody}")
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // ── Actions row: Replay + Export ───────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        onDismiss()
                        onSendToTestr()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Replay", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ── Detail sheet helpers ──────────────────────────────────────────────

@Composable
private fun BadgePill(text: String, color: Long) {
    Surface(
        color = Color(color).copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(color),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:  ",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TimingBar(label: String, ms: Long) {
    val hasData = ms >= 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (hasData) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.width(90.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (hasData) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = (ms.toFloat() / 200f).coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (hasData) "${ms}ms" else "\u2014",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (hasData) FontWeight.SemiBold else FontWeight.Normal,
            color = if (hasData) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
