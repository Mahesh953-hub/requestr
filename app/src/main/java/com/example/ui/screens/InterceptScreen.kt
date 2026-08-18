package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.SearchInputField
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusCodeBadge
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptScreen(
    onSendToTestr: (ApiRequestItem) -> Unit,
    onMiniSettings: () -> Unit,
    onNavigateFullSettings: () -> Unit
) {
    val isCapturing by TrafficInterceptorRepository.isCapturing.collectAsState()
    val capturedTraffic by TrafficInterceptorRepository.capturedTraffic.collectAsState()
    val installedApps by TrafficInterceptorRepository.installedApps.collectAsState()
    val trafficRules by TrafficInterceptorRepository.trafficRules.collectAsState()

    val isVpnActive by TrafficInterceptorRepository.isVpnActive.collectAsState()
    val vpnPort by TrafficInterceptorRepository.vpnPort.collectAsState()
    val vpnPacketsCount by TrafficInterceptorRepository.vpnPacketsCount.collectAsState()
    val vpnBytesTransferred by TrafficInterceptorRepository.vpnBytesTransferred.collectAsState()
    val isShizukuGranted by TrafficInterceptorRepository.isShizukuPermissionGranted.collectAsState()
    val isRootAvailable by TrafficInterceptorRepository.isRootAvailable.collectAsState()
    val isSslPinningBypassed by TrafficInterceptorRepository.isSslPinningBypassed.collectAsState()
    val certDetails by TrafficInterceptorRepository.certificateDetails.collectAsState()
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

    val filteredTraffic = remember(capturedTraffic, searchQuery, selectedMethodFilter) {
        capturedTraffic.filter { item ->
            (selectedMethodFilter == null || item.method == selectedMethodFilter) &&
                    (searchQuery.isBlank() || item.url.contains(searchQuery, ignoreCase = true) || item.host.contains(searchQuery, ignoreCase = true) || item.appName.contains(searchQuery, ignoreCase = true))
        }
    }

    val fabColor by animateColorAsState(
        targetValue = if (isCapturing) InterceptorActiveGreen else InterceptorIdleAmber,
        animationSpec = tween(300),
        label = "fabColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            SectionHeader(
                title = "HTTP(S) Interceptor",
                onMiniSettings = onMiniSettings,
                actions = {
                    IconButton(onClick = { showAppFilterSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = "Select Apps",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showRulesConfigSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Rules & Modifiers",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { TrafficInterceptorRepository.clearTraffic() }) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear Traffic",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            // Security & Intercept Engine Control Banner
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                cornerRadius = 14.dp,
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
                                    .background(if (isVpnActive && isCapturing) InterceptorActiveGreen else InterceptorInactiveRed)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isVpnActive) "LOCAL VPN :$vpnPort (ONLINE)" else "LOCAL VPN PAUSED",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$vpnPacketsCount pkts",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = isVpnActive,
                                onCheckedChange = { TrafficInterceptorRepository.toggleVpn() },
                                modifier = Modifier.scale(0.7f),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Tool chips: Frida, MITM Cert, Root, Shizuku
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EngineChip(
                            label = if (isSslPinningBypassed) "🛡️ Frida (Active)" else "🛡️ Frida SSL",
                            isActive = isSslPinningBypassed,
                            onClick = { showFridaSheet = true }
                        )

                        EngineChip(
                            label = "📜 MITM CA Root",
                            isActive = certDetails.isInstalledSystem,
                            onClick = { showCertSheet = true }
                        )

                        EngineChip(
                            label = if (isRootAvailable) "⚡ Root (Granted)" else "⚡ Root Mode",
                            isActive = isRootAvailable,
                            onClick = { showRootShizukuSheet = true }
                        )

                        EngineChip(
                            label = if (isShizukuGranted) "⛩️ Shizuku (Ready)" else "⛩️ Shizuku",
                            isActive = isShizukuGranted,
                            onClick = { showRootShizukuSheet = true }
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
                    placeholder = "Filter by URL, host, endpoint...",
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
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isCapturing) "Listening for packets on 127.0.0.1:$vpnPort..." else "Interceptor is paused",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap the telegraph toggle FAB below to ${if (isCapturing) "pause" else "start"} live recording",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
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

        // Bottom-Right Telegraph Toggle FAB
        FloatingActionButton(
            onClick = { TrafficInterceptorRepository.toggleCapture() },
            containerColor = fabColor,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 20.dp)
                .size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = if (isCapturing) "Stop Interceptor" else "Start Interceptor",
                modifier = Modifier.size(26.dp)
            )
        }
    }

    // Frida Script Settings Sheet
    if (showFridaSheet) {
        FridaScriptsBottomSheet(
            scripts = fridaScripts,
            isBypassed = isSslPinningBypassed,
            onToggleBypass = { TrafficInterceptorRepository.toggleSslPinningBypass() },
            onDismiss = { showFridaSheet = false }
        )
    }

    // MITM CA Certificate Sheet
    if (showCertSheet) {
        MitmCertificateBottomSheet(
            details = certDetails,
            onRegenerate = { TrafficInterceptorRepository.regenerateCaCertificate() },
            onDismiss = { showCertSheet = false }
        )
    }

    // Root & Shizuku System Integration Sheet
    if (showRootShizukuSheet) {
        SystemIntegrationBottomSheet(
            isRoot = isRootAvailable,
            isShizuku = isShizukuGranted,
            onToggleRoot = { TrafficInterceptorRepository.toggleRootMode() },
            onToggleShizuku = { TrafficInterceptorRepository.toggleShizukuPermission() },
            onDismiss = { showRootShizukuSheet = false }
        )
    }

    // Long-Press Contextual Action Menu Dialog
    itemForContextMenu?.let { item ->
        ContextualActionMenuDialog(
            title = "${item.method.name} ${item.path}",
            subtitle = item.url,
            onDismiss = { itemForContextMenu = null },
            actions = listOf(
                ContextActionItem(
                    label = "Replay in API Testr",
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
                    label = "Copy as cURL Command",
                    icon = Icons.Default.ContentCopy,
                    onClick = { /* copy curl */ }
                ),
                ContextActionItem(
                    label = "Copy Request URL",
                    icon = Icons.Default.ContentCopy,
                    onClick = { /* copy url */ }
                ),
                ContextActionItem(
                    label = "Create Mock Rule from Response",
                    icon = Icons.Default.AutoAwesome,
                    onClick = {
                        TrafficInterceptorRepository.addRule(
                            TrafficRuleItem(
                                id = UUID.randomUUID().toString(),
                                name = "Mock for ${item.path}",
                                isEnabled = true,
                                urlPattern = ".*${item.path}.*",
                                method = item.method,
                                action = RuleAction.MOCK_RESPONSE,
                                mockStatusCode = item.statusCode,
                                mockResponseBody = item.responseBody
                            )
                        )
                    }
                ),
                ContextActionItem(
                    label = "Export as HAR Entry",
                    icon = Icons.Default.Share,
                    onClick = { /* export HAR */ }
                )
            )
        )
    }

    // App Filter Bottom Sheet
    if (showAppFilterSheet) {
        AppFilterBottomSheet(
            installedApps = installedApps,
            onDismiss = { showAppFilterSheet = false },
            onToggleApp = { pkg -> TrafficInterceptorRepository.toggleAppInterception(pkg) },
            onSelectAll = { TrafficInterceptorRepository.setAllAppsEnabled(true) },
            onDeselectAll = { TrafficInterceptorRepository.setAllAppsEnabled(false) }
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridaScriptsBottomSheet(
    scripts: List<FridaScript>,
    isBypassed: Boolean,
    onToggleBypass: () -> Unit,
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
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
                        Text("SSL Pinning Universal Bypass", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Hooks TrustManagerImpl, OkHttp CertificatePinner & Conscrypt", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isBypassed,
                        onCheckedChange = { onToggleBypass() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("AVAILABLE INJECTION SCRIPTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                        cornerRadius = 10.dp,
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
    details: CertificateDetails,
    onRegenerate: () -> Unit,
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
                    Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("MITM Root CA Certificate", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Common Name: ${details.commonName}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Organization: ${details.organization}", fontSize = 11.sp)
                    Text("Serial: ${details.serialNumber}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("Valid: ${details.validFrom} -> ${details.validTo}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Text("SHA-256: ${details.sha256Fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRegenerate,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Regenerate CA", fontSize = 12.sp)
                }
                Button(
                    onClick = { /* Export Magisk zip */ },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Magisk Module", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemIntegrationBottomSheet(
    isRoot: Boolean,
    isShizuku: Boolean,
    onToggleRoot: () -> Unit,
    onToggleShizuku: () -> Unit,
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
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
                        Text("Magisk / KernelSU Root Access", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Allows transparent iptables redirection and system-level CA trust store injection", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isRoot, onCheckedChange = { onToggleRoot() })
                }
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
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
                        Text("Shizuku ADB Service", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Elevated ADB permissions for non-root proxy setup without VPN overhead", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isShizuku, onCheckedChange = { onToggleShizuku() })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterBottomSheet(
    installedApps: List<com.example.data.models.AppPackageItem>,
    onDismiss: () -> Unit,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
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
                    text = "Select Interception Target Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassCard(
                    modifier = Modifier.weight(1f),
                    cornerRadius = 10.dp,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onSelectAll
                ) {
                    Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Text("Select All Apps", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                GlassCard(
                    modifier = Modifier.weight(1f),
                    cornerRadius = 10.dp,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onDeselectAll
                ) {
                    Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Text("Clear Selection", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                items(installedApps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleApp(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.appName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                        Checkbox(
                            checked = app.isInterceptEnabled,
                            onCheckedChange = { onToggleApp(app.packageName) },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
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
                        cornerRadius = 12.dp,
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
    onSendToTestr: () -> Unit
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
                listOf("Response Body", "Request Body", "Headers", "Waterfall").forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, fontSize = 12.sp, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                LazyColumn {
                    when (selectedTab) {
                        0 -> item {
                            Text(
                                text = item.responseBody.ifBlank { "(Empty Response Body)" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        1 -> item {
                            Text(
                                text = item.requestBody.ifBlank { "(Empty Request Body)" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        2 -> item {
                            Column {
                                Text("REQUEST HEADERS:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                item.requestHeaders.forEach { (k, v) ->
                                    Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("RESPONSE HEADERS:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                item.responseHeaders.forEach { (k, v) ->
                                    Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        3 -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("DNS Resolution: 8ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("TCP Connect: 18ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("TLS Handshake (TLSv1.3): 24ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("Server Processing (TTFB): 36ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("Total Latency: ${item.latencyMs}ms", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
