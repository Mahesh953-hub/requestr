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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import com.example.data.interception.TrafficExporter
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.TlsSessionEntity
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
import com.example.ui.components.BadgePill
import com.example.ui.components.GlassCard
import com.example.ui.components.Http3DetailSheet
import com.example.ui.components.Http3RowIndicator
import com.example.ui.components.Http3RowSubtitle
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.RelayHealthCard
import com.example.ui.components.RelayHealthDetailSheet
import com.example.ui.components.SearchInputField
import com.example.ui.components.TlsFailureDetailSheet
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusCodeBadge
import com.example.ui.components.http3RowState
import com.example.ui.components.formatByteCount
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.LocalAnimationsEnabled
import com.example.ui.theme.LocalAppSpacing
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.scaledTween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // B4: real tactile confirmation for start/stop capture, gated once behind
    // settings.hapticFeedbackEnabled via the shared helper.
    val performHaptic = rememberAppHapticFeedback()

    // Real interception state: single source of truth shared with the wordmark dot.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    // Real capability results. Every field is UNKNOWN until an actual probe runs.
    val capabilities by InterceptionStateRepository.capabilities.collectAsState()

    // Task 14: relay health card -- see RequestrVpnService.activePump's doc for why this is a
    // cross-instance StateFlow bridge rather than a static singleton. collectAsStateWithLifecycle
    // (never a plain @Volatile var), per the plan's explicit requirement and the
    // LocalApiServer.isRunning lesson in /memory/testing/requestr/known-issues.md.
    val activePumpForHealth by com.example.interception.RequestrVpnService.activePumpState.collectAsStateWithLifecycle()
    val relayHealthStats by remember(activePumpForHealth) {
        activePumpForHealth?.relay?.stats
            ?: kotlinx.coroutines.flow.MutableStateFlow(com.example.interception.tun.TunRelay.RelayStats())
    }.collectAsStateWithLifecycle()
    val icmpUnrelayableCount = activePumpForHealth?.icmpRelay?.unrelayableCount ?: 0
    val icmpLastPathUsed by remember(activePumpForHealth) {
        activePumpForHealth?.icmpRelay?.lastPathUsed
            ?: kotlinx.coroutines.flow.MutableStateFlow(com.example.interception.tun.IcmpRelay.LastPathUsed.NONE_YET)
    }.collectAsStateWithLifecycle()
    var showRelayHealthSheet by remember { mutableStateOf(false) }

    // Task 14 / Lane D: derive the three-state RelayHealthState (healthy / degraded / down) from the
    // live counters so the extracted RelayHealthCard / RelayHealthDetailSheet can switch exhaustively.
    val relayHealthState by remember(relayHealthStats, activePumpForHealth, icmpUnrelayableCount, icmpLastPathUsed) {
        mutableStateOf(
            com.example.data.models.toRelayHealthState(
                pumpRunning = activePumpForHealth != null,
                stats = relayHealthStats,
                icmpUnrelayableCount = icmpUnrelayableCount,
                icmpLastPathUsed = icmpLastPathUsed,
            )
        )
    }

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
    val captureRecordMode by settings.captureRecordMode.collectAsState(initial = "VPN")
    val enhancedMode by settings.enhancedMode.collectAsState(initial = false)
    val loggingEnabled by settings.loggingEnabled.collectAsState(initial = false)
    val printUndecoded by settings.printUndecoded.collectAsState(initial = true)
    val dumpToFile by settings.dumpToFile.collectAsState(initial = false)
    val pcapEnabled by settings.pcapEnabled.collectAsState(initial = false)

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
    // M1: connection model (TCP/UDP/QUIC) surfaced via a dedicated sheet, mirroring the pattern
    // used for the other capture-related sheets on this screen.
    var showConnectionsSheet by remember { mutableStateOf(false) }

    // Paste-cURL: reuses ApiTestrRepository.parseCurl (read-only call into that file) to build a
    // real ApiRequestItem from a pasted command, then routes it to API Testr the same way a
    // captured transaction's "Send to API Testr" action does.
    var showPasteCurlSheet by remember { mutableStateOf(false) }

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
        animationSpec = scaledTween(300),
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
                    IconButton(onClick = { showConnectionsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.SettingsInputAntenna,
                            contentDescription = "Connections",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showPasteCurlSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste cURL",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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

                        // Honest decryption status
                        val decryptText = when {
                            interceptionState.decryptedTransactionCount > 0 ->
                                "HTTPS DECRYPTED ✓ · ${interceptionState.decryptedTransactionCount} transactions"
                            interceptionState.captureMethod == CaptureMethod.VPN_TUN ->
                                "PLAINTEXT ONLY · HTTPS bodies not captured"
                            interceptionState.tier == ActiveTier.PROXY ->
                                "PROXY · decrypting..."
                            else -> ""
                        }
                        if (decryptText.isNotEmpty()) {
                            Text(
                                text = decryptText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = if (interceptionState.decryptedTransactionCount > 0) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

            Spacer(modifier = Modifier.height(4.dp))

            // Task 14, surface 1: compact VPN relay health readout, bound to TunRelay.stats via
            // the activePumpForHealth bridge above. Only meaningful for the VPN_TUN tier (the
            // relay this describes doesn't exist for PROXY/GATEWAY/MITM), and only relevant if
            // this build's own honesty disclosure is read alongside it: implemented but NOT
            // device-verified end-to-end (Redroid has no kernel TUN datapath -- see AUDIT_REPORT.md).
            RelayHealthCard(
                state = relayHealthState,
                onExpand = { showRelayHealthSheet = true },
            )

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
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.listItemSpacing)
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
        // v3.1 B8: infinite pulse can't be duration-scaled to 0 without flickering
        // forever, so skip it entirely and hold a static scale when animations are off.
        val animationsEnabled = LocalAnimationsEnabled.current
        val pulseTransition = rememberInfiniteTransition(label = "fabPulse")
        val animatedPulseScale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isCapturing) 1.08f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fabPulseScale"
        )
        val pulseScale = if (animationsEnabled) animatedPulseScale else 1f

        FloatingActionButton(
            onClick = {
                // Never a bare boolean flip: starting runs the full pre-flight gate, and
                // stopping tears down the real foreground service.
                performHaptic(HapticFeedbackType.LongPress)
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

    // ── Debug log: shows every interception state change in real-time ──
    var expandedLog by remember { mutableStateOf(false) }
    val logEntries = remember { mutableStateListOf<String>() }

    // Watch state changes and append log entries
    LaunchedEffect(interceptionState) {
        val entry = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] " +
            "status=${interceptionState.status} tier=${interceptionState.tier} method=${interceptionState.captureMethod} " +
            "port=${interceptionState.port} active=${interceptionState.activeConnections} " +
            "err=${interceptionState.errorText ?: "—"}"
        if (logEntries.lastOrNull() != entry) logEntries.add(entry)
        if (logEntries.size > 25) logEntries.removeAt(0)
    }

    if (loggingEnabled && logEntries.isNotEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 80.dp, bottom = 80.dp)
        ) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { expandedLog = !expandedLog }
                    .padding(6.dp)
            ) {
                Column {
                    Text("📋 Interception Log", color = Color(0xFF4ADE80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (expandedLog) {
                        logEntries.takeLast(12).forEach { entry ->
                            Text(entry, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 1)
                        }
                    } else {
                        Text(logEntries.last(), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1)
                    }
                }
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
            captureRecordMode = captureRecordMode,
            enhancedMode = enhancedMode,
            loggingEnabled = loggingEnabled,
            printUndecoded = printUndecoded,
            dumpToFile = dumpToFile,
            pcapEnabled = pcapEnabled,
            onRecordModeChange = { mode -> screenScope.launch { settings.setCaptureRecordMode(mode) } },
            onEnhancedModeChange = { on -> screenScope.launch { settings.setEnhancedMode(on) } },
            onLoggingChange = { on -> screenScope.launch { settings.setLoggingEnabled(on) } },
            onPrintUndecodedChange = { on -> screenScope.launch { settings.setPrintUndecoded(on) } },
            onDumpToFileChange = { on -> screenScope.launch { settings.setDumpToFile(on) } },
            onPcapEnabledChange = { on -> screenScope.launch { settings.setPcapEnabled(on) } },
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
            onExportPcap = {
                // T1: hand the last-written .pcap (from PacketCaptureEngine, populated by
                // RequestrVpnService/PacketPump while a capture ran) to the system share sheet.
                // Complaint #5 fix: PacketCaptureEngine now writes via MediaStore, so this is an
                // ExportTarget (a content:// Uri on API 29+) rather than a plain File.
                com.example.interception.packet.PacketCaptureEngine.getLastExport()?.let { target ->
                    com.example.data.export.FileExporter.shareFile(
                        screenContext, target,
                        "application/vnd.tcpdump.pcap",
                        "requestr PCAP capture"
                    )
                }
            },
            hasPcapFile = com.example.interception.packet.PacketCaptureEngine.getLastExport() != null,
            onDismiss = { showCaptureSetupSheet = false }
        )
    }

    // M1: Connections sheet -- TCP/UDP/QUIC connection rows persisted by the capture engine for
    // the current (or most recent) session. Genuinely empty until a capture has run.
    if (showConnectionsSheet) {
        ConnectionsBottomSheet(
            context = screenContext,
            sessionId = interceptionState.sessionId,
            onDismiss = { showConnectionsSheet = false }
        )
    }

    // Task 14, surface 1 detail: expanded relay-health bottom sheet.
    if (showRelayHealthSheet) {
        RelayHealthDetailSheet(
            state = relayHealthState,
            onDismiss = { showRelayHealthSheet = false },
            onStartCapture = { captureController.requestStart() },
            onCaptureSetup = { showCaptureSetupSheet = true },
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
                        val target = com.example.data.export.FileExporter.writeCaPem(screenContext, pem)
                            ?: error("Failed to write requestr-ca.pem to Downloads/requestr/")
                        com.example.data.export.FileExporter.shareFile(
                            screenContext, target,
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
                            val target = com.example.data.export.FileExporter.writeHarBlocking(screenContext, har, host)
                                ?: error("Failed to write HAR export to Downloads/requestr/")
                            com.example.data.export.FileExporter.shareFile(
                                screenContext, target,
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

    // Rules & Modifiers Sheet — reads/writes Room-persisted intercept_rules table
    if (showRulesConfigSheet) {
        RulesConfigBottomSheet(
            context = screenContext,
            onDismiss = { showRulesConfigSheet = false },
        )
    }

    if (showPasteCurlSheet) {
        PasteCurlDialog(
            onDismiss = { showPasteCurlSheet = false },
            onParsed = { apiItem ->
                showPasteCurlSheet = false
                onSendToTestr(apiItem)
            },
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

/**
 * Paste a raw `curl ...` command and route the parsed request straight to API Testr. Parsing
 * itself is delegated to the existing, out-of-scope [com.example.data.repository.ApiTestrRepository.parseCurl]
 * (called read-only, never edited) so this dialog is purely a thin UI wrapper + validation.
 */
@Composable
private fun PasteCurlDialog(
    onDismiss: () -> Unit,
    onParsed: (ApiRequestItem) -> Unit,
) {
    var curlText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste cURL", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Paste a curl command to send it to API Testr.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = curlText,
                    onValueChange = { curlText = it; error = null },
                    label = { Text("curl -X POST ...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = curlText.trim()
                    if (trimmed.isEmpty() || !trimmed.contains("curl")) {
                        error = "Enter a curl command."
                        return@Button
                    }
                    runCatching { com.example.data.repository.ApiTestrRepository.parseCurl(trimmed) }
                        .onSuccess { onParsed(it) }
                        .onFailure { error = it.message ?: "Could not parse this curl command." }
                },
            ) {
                Text("Send to API Testr")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
                    Icon(imageVector = Icons.Default.Security, contentDescription = "Security", tint = MaterialTheme.colorScheme.primary)
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
                    FridaState.RUNNING -> "frida-server detected on port 27042. Inject requestr's reflection-only OkHttp capture hook into a target app below."
                    FridaState.NOT_RUNNING -> "Not reachable. Push and start frida-server on this device (requires root), then re-check."
                    FridaState.UNKNOWN -> "Not probed yet."
                },
                statusLabel = when (fridaState) {
                    FridaState.RUNNING -> "Detected"
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
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isProbing) "Checking..." else "Re-check frida-server", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // In-app injection: requestr injects its own reflection-only OkHttp Builder.build
            // capture hook (see com.example.runtime.frida.FridaOkHttpAdapter) into a target app,
            // feeding real request/response events into the runtime-instrumentation engine. This
            // makes the Frida capture tier reachable end to end (the old sheet could only report
            // the probe result and told the user to run Frida CLI from a workstation).
            val context = LocalContext.current
            var targetPackage by remember { mutableStateOf("") }
            var targetPid by remember { mutableStateOf("") }
            var injectBusy by remember { mutableStateOf(false) }
            var injectResult by remember { mutableStateOf<String?>(null) }
            val injectScope = rememberCoroutineScope()

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = targetPackage,
                onValueChange = { targetPackage = it },
                label = { Text("Target package (spawn, e.g. com.example.app)", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = targetPid,
                onValueChange = { targetPid = it },
                label = { Text("Target PID (attach, optional)", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val pkg = targetPackage.trim()
                    val pid = targetPid.trim().toIntOrNull()
                    if (pkg.isEmpty() && pid == null) {
                        injectResult = "Enter a target package or PID."
                        return@Button
                    }
                    injectBusy = true
                    injectResult = null
                    injectScope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val target = pid?.let { com.example.runtime.frida.FridaClient.FridaTarget.Pid(it) }
                                    ?: com.example.runtime.frida.FridaClient.FridaTarget.Spawn(pkg)
                                com.example.runtime.frida.FridaRuntimeWiring.registerAndStart(context, target)
                            }
                        }
                        injectBusy = false
                        injectResult = outcome.fold(
                            onSuccess = { adapter ->
                                if (adapter.isActive()) "Armed — ${adapter.status()}" else "Not armed — ${adapter.status()}"
                            },
                            onFailure = { "Injection failed: ${it.message}" },
                        )
                    }
                },
                enabled = !injectBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(if (injectBusy) "Injecting…" else "Inject OkHttp capture hook", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            injectResult?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Icon(imageVector = Icons.Default.Description, contentDescription = "Description", tint = MaterialTheme.colorScheme.primary)
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
                Icon(imageVector = Icons.Default.Security, contentDescription = "Security", modifier = Modifier.size(16.dp))
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
                    Icon(imageVector = Icons.Default.Bolt, contentDescription = "Quick action", tint = MaterialTheme.colorScheme.primary)
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
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
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
    // B4: long-press context menus get a tactile confirmation too.
    val performHaptic = rememberAppHapticFeedback()
    val shape = RoundedCornerShape(14.dp)
    // Task 14 / Lane D: H3 row indicator state for HTTP/3 / QUIC rows (null for H2/H1.1/etc.).
    val h3State = http3RowState(item.protocolHint, item.statusCode)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    performHaptic(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(LocalAppSpacing.current.cardPadding)
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
                    // T2: rows captured by GatewayEngine on behalf of a remote LAN device
                    // (sourceIp != null on the entity) are tagged TrafficSource.GATEWAY by
                    // CaptureLogRepository.toUiModel() -- surface that distinctly here so
                    // it's visually obvious in the list, not just in the detail sheet.
                    if (item.trafficSource == com.example.data.models.TrafficSource.GATEWAY) {
                        BadgePill(text = "GATEWAY", color = 0xFFF59E0B)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    // Task 10: rows reported by a third-party app's embedded requestr-sdk
                    // (LocalApiServer.receiveObserverEvents, under CaptureTier.SDK) are tagged
                    // TrafficSource.SDK by CaptureLogRepository.toUiModel() -- same pattern as
                    // the GATEWAY badge above, distinct cyan color to match the SDK's identity.
                    if (item.trafficSource == com.example.data.models.TrafficSource.SDK) {
                        BadgePill(text = "SDK", color = 0xFF06B6D4)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    ProtocolBadge(protocol = item.protocol)
                    if (h3State != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Http3RowIndicator(state = h3State)
                    }
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

            if (h3State != null) {
                Http3RowSubtitle(state = h3State, alpn = item.alpnProtocol)
            }
        }
    }
}


/**
 * Task 14 / Lane D: RelayHealthCompactCard + RelayHealthDetailSheet were extracted to
 * [com.example.ui.components.RelayHealthCard] and [com.example.ui.components.RelayHealthDetailSheet]
 * and augmented with the full healthy / degraded / down mockup grid (per-reason drop bars, flow-table
 * pressure, down-state Start-capture CTA). They are consumed above via those public composables.
 */

/**
 * M1: Connections sheet.
 *
 * Surfaces the TCP/UDP/QUIC connection rows the capture engine persists into Room
 * ([com.example.data.local.TcpConnectionEntity], [com.example.data.local.UdpFlowEntity],
 * [com.example.data.local.QuicConnectionEntity]) -- this is the UI half of M1, proving the
 * entities are not just defined but genuinely written and readable.
 *
 * Falls back to "every connection ever recorded" when there's no active session id, so the
 * sheet still shows real history right after a capture stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsBottomSheet(
    context: android.content.Context,
    sessionId: Long?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    // Task 14 / Lane D: the FAILED TLS row the user tapped, opening TlsFailureDetailSheet; and the
    // QUIC row the user tapped, opening Http3DetailSheet. Both are local to this sheet because the
    // rows that surface them live here.
    var selectedTlsFailure by remember { mutableStateOf<TlsSessionEntity?>(null) }
    var selectedHttp3Connection by remember { mutableStateOf<com.example.data.local.QuicConnectionEntity?>(null) }

    // Task 14: the cross-instance StateFlow bridge -- see RequestrVpnService.activePump's doc.
    // collectAsStateWithLifecycle (not the plain collectAsState() used elsewhere in this file)
    // per the plan's explicit requirement, matching the LocalApiServer.isRunning lesson in
    // /memory/testing/requestr/known-issues.md: a StateFlow, never a bare @Volatile var.
    val activePump by com.example.interception.RequestrVpnService.activePumpState.collectAsStateWithLifecycle()
    val keyLogEntryCount by com.example.interception.tls.TlsKeyLogRegistry.entryCount.collectAsStateWithLifecycle()

    // TunRelay.stats only exists on the live PacketPump instance, so the flow bound here must
    // change whenever activePump itself changes (no capture running -> a fixed empty default).
    val relayStats by remember(activePump) {
        activePump?.relay?.stats ?: kotlinx.coroutines.flow.MutableStateFlow(com.example.interception.tun.TunRelay.RelayStats())
    }.collectAsStateWithLifecycle()

    // PacketPump.quicConnectionState is a plain in-memory map, not a StateFlow (see its own doc
    // for why) -- polled on a short interval rather than observed, same honesty as the rest of
    // this sheet: this is live-only state, reset to empty the moment activePump goes null.
    val quicLiveList by produceState(initialValue = emptyList<com.example.interception.PacketPump.QuicLiveSummary>(), activePump) {
        while (true) {
            value = activePump?.quicLiveStateSnapshot() ?: emptyList()
            kotlinx.coroutines.delay(1000L)
        }
    }
    val quicLiveMap = remember(quicLiveList) { quicLiveList.associateBy { it.connectionId } }

    val tcpConnections by remember(sessionId) {
        if (sessionId != null) com.example.data.interception.ConnectionsRepository.observeTcpConnections(context, sessionId)
        else com.example.data.interception.ConnectionsRepository.observeAllTcpConnections(context)
    }.collectAsState(initial = emptyList())

    val udpFlows by remember(sessionId) {
        if (sessionId != null) com.example.data.interception.ConnectionsRepository.observeUdpFlows(context, sessionId)
        else com.example.data.interception.ConnectionsRepository.observeAllUdpFlows(context)
    }.collectAsState(initial = emptyList())

    val quicConnections by remember(sessionId) {
        if (sessionId != null) com.example.data.interception.ConnectionsRepository.observeQuicConnections(context, sessionId)
        else com.example.data.interception.ConnectionsRepository.observeAllQuicConnections(context)
    }.collectAsState(initial = emptyList())

    val tlsSessions by remember(sessionId) {
        if (sessionId != null) com.example.data.interception.ConnectionsRepository.observeTlsSessions(context, sessionId)
        else com.example.data.interception.ConnectionsRepository.observeAllTlsSessions(context)
    }.collectAsState(initial = emptyList())

    val connectAttempts by remember(sessionId) {
        if (sessionId != null) com.example.data.interception.ConnectionsRepository.observeConnectAttempts(context, sessionId)
        else com.example.data.interception.ConnectionsRepository.observeAllConnectAttempts(context)
    }.collectAsState(initial = emptyList())

    var selectedConnectAttempt by remember { mutableStateOf<ConnectAttemptEntity?>(null) }

    // D1: multi-connection provenance graph, grouped by the host each connection actually
    // dialed (DNS answer / TLS SNI / raw dest IP, see ConnectionLineage.resolveHostFor). This is
    // a one-shot suspend read (not a Flow) since it recomputes the whole grouping+lineage on
    // every load rather than incrementally -- refreshed by bumping [provenanceRefreshKey].
    var provenanceRefreshKey by remember { mutableIntStateOf(0) }
    var expandedConnectionId by remember { mutableStateOf<String?>(null) }
    val provenanceGraph by produceState<com.example.correlation.graph.ProvenanceGraphResult?>(
        initialValue = null,
        sessionId,
        provenanceRefreshKey,
    ) {
        value = if (sessionId != null) {
            com.example.correlation.graph.ProvenanceGraph.build(context, sessionId)
        } else {
            null
        }
    }
    val expandedLineage by produceState<com.example.correlation.graph.LineageChain?>(
        initialValue = null,
        sessionId,
        expandedConnectionId,
    ) {
        val connId = expandedConnectionId
        value = if (sessionId != null && connId != null) {
            com.example.correlation.graph.ProvenanceGraph.lineageFor(context, sessionId, connId)
        } else {
            null
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
                Text("Connections", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                if (sessionId != null) "Live 5-tuple state from the running capture session"
                else "No active session -- showing every connection ever recorded",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                listOf(
                    "TCP (${tcpConnections.size})",
                    "UDP (${udpFlows.size})",
                    "QUIC (${quicConnections.size})",
                    "TLS (${tlsSessions.size})",
                    "CONNECT (${connectAttempts.size})",
                    "Provenance (${provenanceGraph?.hostGroups?.size ?: 0})",
                ).forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, fontSize = 11.sp, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                when (selectedTab) {
                    0 -> {
                        if (tcpConnections.isEmpty()) {
                            EmptyConnectionsState("No TCP connections recorded yet. Start a capture and browse to see live 5-tuples here.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(tcpConnections) { conn ->
                                    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text("${conn.srcIp}:${conn.srcPort} → ${conn.destIp}:${conn.destPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                BadgePill(text = conn.state, color = if (conn.state == "CLOSED") 0xFF64748B else 0xFF10B981)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "sent ${conn.bytesSent}B · recv ${conn.bytesReceived}B · retx ${conn.retransmissions}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        if (udpFlows.isEmpty()) {
                            EmptyConnectionsState("No UDP flows recorded yet.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(udpFlows) { flow ->
                                    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text("${flow.srcIp}:${flow.srcPort} → ${flow.destIp}:${flow.destPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                BadgePill(text = flow.transportProtocol.uppercase(), color = 0xFF0EA5E9)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("${flow.byteCount}B observed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        if (quicConnections.isEmpty()) {
                            EmptyConnectionsState(
                                "No QUIC connections recorded yet. Identity comes from the Initial packet's " +
                                    "ClientHello; HTTP/3 decoding additionally needs this connection's TLS traffic " +
                                    "secrets in the key-log registry. Rows below report the authoritative decode " +
                                    "status -- detected only, observed without keys, partially decoded, or fully " +
                                    "decoded -- and say which key material was missing when it was."
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(quicConnections) { conn ->
                                    // Task 14 / gap closure: the badge is driven by the authoritative,
                                    // Room-persisted QuicDecodeStatus column that PacketPump writes from the
                                    // live QuicConnectionDecoder -- so it survives process death and is never
                                    // an inference from ALPN. The live snapshot only adds strictly-live detail
                                    // (transport state, which traffic secrets resolved vs. missed).
                                    val live = quicLiveMap[conn.connectionId]
                                    val decoded = conn.decodeStatus == com.example.data.local.QuicDecodeStatus.FULLY_DECODED
                                    val partiallyDecoded = conn.decodeStatus == com.example.data.local.QuicDecodeStatus.PARTIALLY_DECODED
                                    val keysMissing = conn.decodeStatus == com.example.data.local.QuicDecodeStatus.OBSERVED_NO_KEYS
                                    val badgeText = when {
                                        decoded -> "HTTP/3"
                                        partiallyDecoded -> "HANDSHAKE"
                                        keysMissing -> "NO KEYS"
                                        else -> "QUIC"
                                    }
                                    val badgeColor = when {
                                        decoded -> 0xFF10B981
                                        partiallyDecoded -> 0xFFF59E0B
                                        keysMissing -> 0xFFEF4444
                                        else -> 0xFF64748B
                                    }
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        onClick = { selectedHttp3Connection = conn },
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text(conn.sniHost ?: "${conn.destIp}:${conn.destPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    BadgePill(text = badgeText, color = badgeColor)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    BadgePill(text = "v${conn.quicVersion?.toString(16) ?: "?"}", color = 0xFF8B5CF6)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "${conn.srcIp}:${conn.srcPort} → ${conn.destIp}:${conn.destPort}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                buildString {
                                                    append("state=")
                                                    append(live?.state?.name ?: conn.lifecycleState)
                                                    append(" · ")
                                                    append(conn.decodeStatus.name)
                                                    live?.cipherSuite?.let { append(" · ").append(it) }
                                                    if (decoded) {
                                                        append(" · ")
                                                        append(live?.decodedHttp3Exchanges ?: 0)
                                                        append(" HTTP/3 exchange(s) decoded")
                                                    }
                                                    if (live?.keyUpdatesPromoted?.let { it > 0 } == true) {
                                                        append(" · key updates ").append(live.keyUpdatesPromoted)
                                                    }
                                                },
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (keysMissing) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                buildString {
                                                    val resolved = conn.keylogLabels
                                                    if (!resolved.isNullOrBlank()) {
                                                        append("keys resolved: ").append(resolved)
                                                    } else {
                                                        append("no traffic secrets resolved for this connection")
                                                    }
                                                    val missing = live?.missingKeyLabels.orEmpty()
                                                    if (missing.isNotEmpty()) append(" · missing: ").append(missing.joinToString(","))
                                                    if (live?.cipherSuiteUnavailable == true) append(" · no ServerHello observed")
                                                    append(" · registry holds $keyLogEntryCount entr")
                                                    append(if (keyLogEntryCount == 1) "y" else "ies")
                                                },
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        if (tlsSessions.isEmpty()) {
                            EmptyConnectionsState("No TLS sessions recorded yet. In MITM/proxy mode this shows negotiated cipher + cert; in VPN mode it shows ClientHello SNI/JA3 only.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(tlsSessions) { tls ->
                                    // Task 14: a third badge state, FAILED (red), alongside the existing
                                    // DECRYPTED (green) / SNI/JA3 ONLY (gray) -- tls.handshakeState is real
                                    // (task 11 sets it from actual RST/alert signals), previously unread here.
                                    val failed = tls.handshakeState == "FAILED"
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        onClick = if (failed) { { selectedTlsFailure = tls } } else null,
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            if (failed) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(3.dp)
                                                        .fillMaxHeight()
                                                        .background(Color(0xFFEF4444))
                                                )
                                            }
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                    Text(tls.sniHost ?: tls.connectionId, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                    BadgePill(
                                                        text = when {
                                                            failed -> "FAILED"
                                                            tls.wasDecrypted -> "DECRYPTED"
                                                            else -> "SNI/JA3 ONLY"
                                                        },
                                                        color = when {
                                                            failed -> 0xFFEF4444
                                                            tls.wasDecrypted -> 0xFF10B981
                                                            else -> 0xFF64748B
                                                        },
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    listOfNotNull(
                                                        tls.version,
                                                        tls.cipherSuite,
                                                        tls.negotiatedProtocol?.let { "alpn=$it" },
                                                    ).joinToString(" · ").ifEmpty { "no handshake metadata" },
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                // Task 14: render the real failureReason (task 11) beneath the
                                                // version/cipher/alpn line, styled like the existing cert:/JA3:
                                                // rows below -- this is the row-level treatment the plan asks
                                                // for; a full handshake-stage-rail detail sheet (per the
                                                // tls-failed-detail-* mockups) would need per-stage timestamps
                                                // TlsSessionEntity does not store, so it is out of scope here.
                                                if (failed && tls.failureReason != null) {
                                                    Text(
                                                        "failed: ${tls.failureReason}",
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = Color(0xFFEF4444),
                                                    )
                                                }
                                                if (tls.peerCertSubject != null) {
                                                    Text(
                                                        "cert: ${tls.peerCertSubject}",
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                if (tls.ja3Hash != null) {
                                                    Text(
                                                        "JA3: ${tls.ja3Hash}",
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    4 -> {
                        if (connectAttempts.isEmpty()) {
                            EmptyConnectionsState("No CONNECT (HTTPS tunnel) attempts recorded yet. Browse to an HTTPS site while capturing to see rule-driven CONNECT dispositions here.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(connectAttempts, key = { it.id }) { conn ->
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(10.dp)
                                                .clickable { selectedConnectAttempt = conn }
                                        ) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text("${conn.targetHost}:${conn.targetPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                BadgePill(text = conn.disposition, color = connectDispositionColor(conn.disposition))
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                clientIdentityFor(context, conn.clientUid) ?: conn.sourceIp ?: "local",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                listOfNotNull(
                                                    "sent ${conn.bytesToUpstream}B · recv ${conn.bytesToClient}B",
                                                    conn.negotiatedAlpn?.let { "alpn=$it" },
                                                ).joinToString(" · "),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(conn.timestamp)),
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    5 -> {
                        val graph = provenanceGraph
                        if (sessionId == null) {
                            EmptyConnectionsState("Provenance needs an active capture session -- start a capture to see the DNS→TCP→TLS→HTTP chain for each connection.")
                        } else if (graph == null) {
                            EmptyConnectionsState("Building provenance graph...")
                        } else if (graph.hostGroups.isEmpty()) {
                            EmptyConnectionsState("No connections recorded yet for this session.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(graph.hostGroups, key = { it.host }) { group ->
                                    GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text(group.host, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                                BadgePill(text = "${group.connections.size} conn", color = 0xFF6366F1)
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            group.connections.forEach { node ->
                                                val isExpanded = expandedConnectionId == node.connectionId
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            expandedConnectionId = if (isExpanded) null else node.connectionId
                                                        }
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                        Text(
                                                            "${node.transport} · ${node.connectionId}",
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Icon(
                                                            imageVector = if (isExpanded) Icons.Default.ArrowDownward else Icons.Default.PlayArrow,
                                                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                            modifier = Modifier.size(14.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    if (isExpanded) {
                                                        val lineage = expandedLineage
                                                        if (lineage == null || lineage.connectionId != node.connectionId) {
                                                            Text("Loading lineage...", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        } else {
                                                            Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                                                                lineage.steps.forEach { step ->
                                                                    Row(verticalAlignment = Alignment.Top) {
                                                                        BadgePill(
                                                                            text = step.plane.name,
                                                                            color = if (step.success) 0xFF10B981 else 0xFF64748B,
                                                                        )
                                                                        Spacer(modifier = Modifier.width(6.dp))
                                                                        Column {
                                                                            Text(step.label, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                                                            Text(step.detail, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                        }
                                                                    }
                                                                    Spacer(modifier = Modifier.height(6.dp))
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedConnectAttempt?.let { attempt ->
        ConnectAttemptDetailSheet(
            item = attempt,
            onDismiss = { selectedConnectAttempt = null },
        )
    }

    // Task 14 / Lane D: FAILED TLS row → TlsFailureDetailSheet with the inferred handshake-stage
    // rail and remedy block. The remedy buttons surface the guidance and dismiss; deeper action
    // wiring (CA-install flow, bypass-rule creation) is deliberately deferred so this UI lane does
    // not invent new controller/rule APIs against the frozen shared data files.
    selectedTlsFailure?.let { entity ->
        val detail = com.example.data.models.toTlsFailureDetail(entity)
        if (detail != null) {
            TlsFailureDetailSheet(
                detail = detail,
                onDismiss = { selectedTlsFailure = null },
                onPrimaryAction = { selectedTlsFailure = null },
                onSecondaryAction = { selectedTlsFailure = null },
            )
        }
    }

    // Task 14 / Lane D: QUIC row → Http3DetailSheet. The detail state is derived from the persisted
    // QuicConnectionEntity + the live summary snapshot; the action buttons surface the mockup remedy
    // and dismiss (deeper wiring — force-TCP rule, CA install — is deferred to avoid inventing new
    // controller APIs against frozen shared files in this UI lane).
    selectedHttp3Connection?.let { conn ->
        val h3State = com.example.data.models.toHttp3DetailState(conn, quicLiveMap[conn.connectionId])
        Http3DetailSheet(
            state = h3State,
            onDismiss = { selectedHttp3Connection = null },
            onPrimaryAction = { selectedHttp3Connection = null },
            onSecondaryAction = { selectedHttp3Connection = null },
        )
    }
}

@Composable
private fun EmptyConnectionsState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
}

/** Disposition -> badge color, mirroring BadgePill's ESTABLISHED/CLOSED green/red convention. */
private fun connectDispositionColor(disposition: String): Long = when (disposition) {
    "MITM_DECRYPTED" -> 0xFF10B981
    "ALLOWED_OPAQUE" -> 0xFFF59E0B
    "BLOCKED" -> 0xFFEF4444
    "REDIRECTED" -> 0xFF8B5CF6
    "FAILED" -> 0xFF64748B
    else -> 0xFF64748B
}

/** Best-effort UID -> package/app-label resolution for the CONNECT tab's "client" column. */
private fun clientIdentityFor(context: android.content.Context, uid: Int?): String? {
    if (uid == null) return null
    return try {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        val pkg = packages?.firstOrNull() ?: return "uid $uid"
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        "uid $uid"
    }
}

/**
 * Detail sheet for a single CONNECT attempt (Task 5 "Print"): negotiated ALPN, handshake state,
 * redirect target if redirected, failure reason if failed, timestamps, and a Replay action
 * (Task 5 "Iter") that re-issues the CONNECT via [com.example.interception.ConnectReplay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectAttemptDetailSheet(
    item: ConnectAttemptEntity,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var targetHost by remember(item.id) { mutableStateOf(item.targetHost) }
    var targetPort by remember(item.id) { mutableStateOf(item.targetPort.toString()) }
    var forceMitm by remember(item.id) { mutableStateOf(false) }
    var replaying by remember(item.id) { mutableStateOf(false) }
    var replayResult by remember(item.id) { mutableStateOf<com.example.interception.ConnectReplay.ReplayResult?>(null) }
    var repeatCount by remember(item.id) { mutableStateOf("3") }
    var repeatJitterMs by remember(item.id) { mutableStateOf("0") }
    var repeating by remember(item.id) { mutableStateOf(false) }
    var repeatResults by remember(item.id) { mutableStateOf<List<com.example.interception.ConnectReplay.ReplayResult>?>(null) }

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
                Text("CONNECT ${item.targetHost}:${item.targetPort}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            BadgePill(text = item.disposition, color = connectDispositionColor(item.disposition))

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Target", "${item.targetHost}:${item.targetPort}")
                DetailRow("Client", clientIdentityFor(context, item.clientUid) ?: item.sourceIp ?: "local")
                DetailRow("ALPN", item.negotiatedAlpn ?: "—")
                DetailRow("Handshake", item.handshakeState ?: "—")
                if (item.redirectTarget != null) {
                    DetailRow("Redirect target", item.redirectTarget)
                }
                if (item.failureReason != null) {
                    DetailRow("Failure reason", item.failureReason)
                }
                DetailRow("Sent", "${item.bytesToUpstream}B")
                DetailRow("Received", "${item.bytesToClient}B")
                DetailRow("Started", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(item.timestamp)))
                item.finishedAt?.let {
                    DetailRow("Finished", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(it)))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            Text("Replay", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = targetHost,
                onValueChange = { targetHost = it },
                label = { Text("Target host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = targetPort,
                onValueChange = { input -> targetPort = input.filter { it.isDigit() } },
                label = { Text("Target port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Force MITM", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = forceMitm, onCheckedChange = { forceMitm = it })
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    val port = targetPort.toIntOrNull() ?: item.targetPort
                    replaying = true
                    replayResult = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            com.example.interception.ConnectReplay.replay(context, targetHost, port, forceMitm)
                        }
                        replayResult = result
                        replaying = false
                    }
                },
                enabled = !replaying,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (replaying) "Replaying..." else "Replay", fontSize = 13.sp)
            }

            replayResult?.let { result ->
                Spacer(modifier = Modifier.height(10.dp))
                GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(result.disposition, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${result.latencyMs}ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        result.alpn?.let {
                            Text("alpn=$it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        result.error?.let {
                            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            Text("Repeat replay", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = repeatCount,
                    onValueChange = { input -> repeatCount = input.filter { it.isDigit() } },
                    label = { Text("Count") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = repeatJitterMs,
                    onValueChange = { input -> repeatJitterMs = input.filter { it.isDigit() } },
                    label = { Text("Max jitter (ms)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    val port = targetPort.toIntOrNull() ?: item.targetPort
                    val count = repeatCount.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val jitter = repeatJitterMs.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    repeating = true
                    repeatResults = null
                    scope.launch {
                        val results = withContext(Dispatchers.IO) {
                            com.example.interception.ConnectReplay.replayRepeated(
                                context, targetHost, port, forceMitm, count, jitter,
                            )
                        }
                        repeatResults = results
                        repeating = false
                    }
                },
                enabled = !repeating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Repeat", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (repeating) "Replaying..." else "Replay ${repeatCount.toIntOrNull()?.coerceAtLeast(1) ?: 1}x", fontSize = 13.sp)
            }

            repeatResults?.let { results ->
                Spacer(modifier = Modifier.height(10.dp))
                val successCount = results.count { it.disposition == "ALLOWED_OPAQUE" }
                Text(
                    "$successCount/${results.size} succeeded",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    results.forEachIndexed { index, result ->
                        GlassCard(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            ) {
                                Text("#${index + 1} ${result.disposition}", fontSize = 11.sp)
                                Text("${result.latencyMs}ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
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
    captureRecordMode: String,
    enhancedMode: Boolean,
    loggingEnabled: Boolean,
    printUndecoded: Boolean,
    dumpToFile: Boolean,
    pcapEnabled: Boolean,
    onRecordModeChange: (String) -> Unit,
    onEnhancedModeChange: (Boolean) -> Unit,
    onLoggingChange: (Boolean) -> Unit,
    onPrintUndecodedChange: (Boolean) -> Unit,
    onDumpToFileChange: (Boolean) -> Unit,
    onPcapEnabledChange: (Boolean) -> Unit,
    onOpenApps: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenCert: () -> Unit,
    onOpenFrida: () -> Unit,
    onOpenPrivileges: () -> Unit,
    onReprobe: () -> Unit,
    onClearTraffic: () -> Unit,
    onExportPcap: () -> Unit,
    hasPcapFile: Boolean,
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
                text = "Capture tier is chosen automatically: root first, then Shizuku, then VPN -- unless " +
                    "\"Force VPN Only\" is selected under Settings \u2192 Default Capture Tier, which always uses VPN.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Record mode: VPN or Proxy ──────────────────────
            Text("Record Mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("VPN" to "Routes all traffic through a local VPN tunnel", "PROXY" to "Starts a local HTTP proxy server").forEach { (mode, desc) ->
                    val active = captureRecordMode == mode
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onRecordModeChange(mode) }
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(mode, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text(desc, fontSize = 10.sp, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Complaint #1 fix: PROXY mode silently resolves to one of two different engines
            // (HttpProxyEngine or MitmProxyEngine) depending on whether the CA is installed --
            // previously this choice was entirely invisible to the user. Root/Shizuku also
            // override Record Mode entirely (PreflightController.run()), so surface the real
            // resolution here rather than letting "PROXY" imply a single fixed engine.
            Spacer(modifier = Modifier.height(4.dp))
            val resolvedMethodNote = when {
                capabilities.isRootUsable -> "Root is available, so capture will actually run as: Root iptables (Record Mode is ignored while root is usable)."
                capabilities.isShizukuUsable -> "Shizuku is available, so capture will actually run as: Shizuku Tun (Record Mode is ignored while Shizuku is usable)."
                captureRecordMode == "PROXY" && capabilities.isCaInstalled -> "Will actually run as: MITM Proxy (CA installed, so HTTPS is decrypted)."
                captureRecordMode == "PROXY" -> "Will actually run as: HTTP Proxy (CA not installed, so HTTPS bodies cannot be decrypted -- install the CA under Certificate to upgrade to MITM Proxy)."
                capabilities.isCaInstalled -> "Will actually run as: MITM Proxy (CA installed; VPN Record Mode auto-upgrades to MITM when a CA is present)."
                else -> "Will actually run as: VPN Tun."
            }
            Text(
                text = resolvedMethodNote,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Enhanced mode ──────────────────────────────────
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enhanced Mode", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Runs an extra protocol-fingerprint pass on captured traffic (adds x-protocol-* headers, viewable in a row's Headers tab)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enhancedMode, onCheckedChange = onEnhancedModeChange)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Logging ────────────────────────────────────────
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Logging Overlay", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Show debug header + state transition logs on screen", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = loggingEnabled, onCheckedChange = onLoggingChange)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Print Undecoded ────────────────────────────────
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Print Undecoded", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Show TLS/TCP entries in capture list without decryption", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = printUndecoded, onCheckedChange = onPrintUndecodedChange)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Dump to file ────────────────────────────────────
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dump to File", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Save raw hex capture to Downloads/requestr/ for external analysis", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dumpToFile, onCheckedChange = onDumpToFileChange)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── PCAP export ─────────────────────────────────────
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PCAP Export", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Save raw packets to Downloads/requestr/*.pcap for Wireshark analysis", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = pcapEnabled, onCheckedChange = onPcapEnabledChange)
            }

            Spacer(modifier = Modifier.height(14.dp))

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
            SetupRow(
                icon = Icons.Default.Share,
                title = "Share PCAP capture",
                // T1: hasPcapFile reflects PacketCaptureEngine.getLastFile() != null, i.e.
                // whether any .pcap has ever been written this app-session (independent of
                // whether capture is running right now -- you can share a finished capture).
                subtitle = if (hasPcapFile) "Send the last .pcap to Wireshark, email, etc."
                else "No .pcap captured yet -- enable PCAP Export above and run a capture",
                onClick = onExportPcap,
                enabled = hasPcapFile
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (captureRecordMode == "PROXY") {
                Text(
                    text = "Proxy port: $port",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "VPN mode — no port needed. TUN interface captures all IP traffic.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dao = remember { com.example.data.local.RequestrDatabase.get(context).interceptRuleDao() }
    val rules by dao.observeAll().collectAsState(initial = emptyList())
    var showAddForm by remember { mutableStateOf(false) }

    // Add Rule form state
    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleDomain by remember { mutableStateOf("") }
    var ruleAction by remember { mutableStateOf(com.example.data.local.RuleAction.BLOCK) }
    var ruleEnabled by remember { mutableStateOf(true) }

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
            // Header
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
                Row {
                    IconButton(onClick = { showAddForm = !showAddForm }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Rule",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Add Rule Form (expandable)
            if (showAddForm) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("New Rule", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

                        // Rule Name
                        OutlinedTextField(
                            value = ruleName,
                            onValueChange = { ruleName = it },
                            label = { Text("Rule Name", fontSize = 11.sp) },
                            placeholder = { Text("e.g., Block Ads", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )

                        // URL Pattern (regex/glob)
                        OutlinedTextField(
                            value = rulePattern,
                            onValueChange = { rulePattern = it },
                            label = { Text("URL Pattern (regex)", fontSize = 11.sp) },
                            placeholder = { Text("e.g., .*(ads|tracker|analytics).*", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )

                        // Domain (for quick block checks)
                        OutlinedTextField(
                            value = ruleDomain,
                            onValueChange = { ruleDomain = it },
                            label = { Text("Target Domain (optional)", fontSize = 11.sp) },
                            placeholder = { Text("e.g., ads.example.com", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )

                        // Action selector
                        Text("Action", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.example.data.local.RuleAction.values().forEach { action ->
                                val selected = ruleAction == action
                                FilterChip(
                                    selected = selected,
                                    onClick = { ruleAction = action },
                                    label = { Text(action.name.replace("_", " "), fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }

                        // Enabled toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enabled", fontSize = 12.sp)
                            Switch(checked = ruleEnabled, onCheckedChange = { ruleEnabled = it })
                        }

                        // Save button
                        Button(
                            onClick = {
                                if (ruleName.isNotBlank() && rulePattern.isNotBlank()) {
                                    // Capture the current field values into locals BEFORE resetting
                                    // the form. scope.launch(Dispatchers.IO) only schedules the
                                    // coroutine -- it does not run synchronously -- so if the
                                    // mutableStateOf fields were read lazily inside the coroutine
                                    // body, the form-reset lines below would race ahead and clear
                                    // them first, causing every inserted rule to be saved with
                                    // blank name/pattern/domain and the default BLOCK action.
                                    val nameToSave = ruleName.trim()
                                    val patternToSave = rulePattern.trim()
                                    val domainToSave = ruleDomain.trim()
                                    val actionToSave = ruleAction
                                    val enabledToSave = ruleEnabled
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        dao.insert(
                                            com.example.data.local.InterceptRuleEntity(
                                                name = nameToSave,
                                                pattern = patternToSave,
                                                domain = domainToSave,
                                                action = actionToSave,
                                                enabled = enabledToSave,
                                            )
                                        )
                                    }
                                    // Reset form
                                    ruleName = ""
                                    rulePattern = ""
                                    ruleDomain = ""
                                    ruleAction = com.example.data.local.RuleAction.BLOCK
                                    ruleEnabled = true
                                    showAddForm = false
                                }
                            },
                            enabled = ruleName.isNotBlank() && rulePattern.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Rule")
                        }
                    }
                }
            }

            // Rules list
            if (rules.isEmpty()) {
                Text(
                    "No rules configured. Tap + to add one.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rules, key = { it.id }) { rule ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(rule.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                        if (!rule.enabled) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("DISABLED", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Text(rule.pattern, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, maxLines = 1)
                                    if (rule.domain.isNotBlank()) {
                                        Text("Domain: ${rule.domain}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        "Action: ${rule.action.name}",
                                        fontSize = 10.sp,
                                        color = when (rule.action) {
                                            com.example.data.local.RuleAction.BLOCK -> MaterialTheme.colorScheme.error
                                            com.example.data.local.RuleAction.LOG_ONLY -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // Toggle enabled
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { newVal ->
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                dao.update(rule.copy(enabled = newVal))
                                            }
                                        },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    // Delete
                                    IconButton(
                                        onClick = {
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) { dao.delete(rule) }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Content-type-gated body rendering for [CapturedDetailBottomSheet]'s Body tab. Renders JSON
 * pretty-printed (falling back to raw text on a parse failure), skips inline rendering for
 * images/unrenderable binary bodies with an explanatory note instead, and gates any body over
 * [MAX_RENDERABLE_BODY_BYTES] behind a "Show anyway" button so a multi-MB response body never
 * hangs the detail sheet's layout pass.
 */
@Composable
private fun CaptureBodySection(label: String, body: String, contentType: String?, labelColor: Color) {
    var showAnyway by remember(body) { mutableStateOf(false) }
    val kind = remember(contentType, body) { detectBodyRenderKind(contentType, body) }
    val bodyBytes = remember(body) { body.toByteArray(Charsets.UTF_8).size }

    Text("$label:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = labelColor)
    when {
        kind == BodyRenderKind.IMAGE -> {
            Text(
                "Image response (${contentType ?: "unknown type"}, $bodyBytes bytes) -- not rendered inline here; see the image gallery.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        kind == BodyRenderKind.UNRENDERABLE_BINARY -> {
            Text(
                "Binary body (${contentType ?: "unknown type"}, $bodyBytes bytes) -- not rendered.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        shouldGateBodyRendering(bodyBytes) && !showAnyway -> {
            Text(
                "Body is ${bodyBytes / 1024}KB -- larger than ${MAX_RENDERABLE_BODY_BYTES / 1024}KB, not rendered by default.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Button(onClick = { showAnyway = true }) { Text("Show anyway", fontSize = 11.sp) }
        }
        else -> {
            val displayText = if (kind == BodyRenderKind.JSON) prettyPrintJsonOrFallback(body) else body
            Text(displayText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
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
                                    CaptureBodySection(
                                        label = "RESPONSE BODY",
                                        body = item.responseBody,
                                        contentType = contentTypeOf(item.responseHeaders),
                                        labelColor = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (item.requestBody.isNotBlank()) {
                                    CaptureBodySection(
                                        label = "REQUEST BODY",
                                        body = item.requestBody,
                                        contentType = contentTypeOf(item.requestHeaders),
                                        labelColor = MaterialTheme.colorScheme.secondary,
                                    )
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
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Replay", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ── Detail sheet helpers ──────────────────────────────────────────────
// BadgePill extracted to CommonComponents.kt (shared, owned by Lane A after task 7).

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
