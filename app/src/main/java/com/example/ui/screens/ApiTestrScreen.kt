package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionResult
import com.example.data.models.AssertionTarget
import com.example.data.models.AuthType
import com.example.data.models.ChainExtractionRule
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.ApiTestrRepository
import com.example.data.repository.AssertionRunner
import com.example.data.repository.SettingsRepository
import com.example.data.repository.ToolsRepository
import com.example.data.repository.WsConnectionStatus
import com.example.data.repository.WsMessage
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusCodeBadge
import com.example.ui.components.rememberAppHapticFeedback
import com.example.ui.theme.MethodDelete
import com.example.ui.theme.MethodGet
import com.example.ui.theme.MethodHead
import com.example.ui.theme.MethodOptions
import com.example.ui.theme.MethodPatch
import com.example.ui.theme.MethodPost
import com.example.ui.theme.MethodPut
import com.example.ui.theme.MethodWs
import kotlinx.coroutines.launch

/**
 * Returns color token specifically for each HTTP Method badge and background tint.
 */
fun getMethodColor(method: HttpMethod): Color {
    return when (method) {
        HttpMethod.GET -> MethodGet
        HttpMethod.POST -> MethodPost
        HttpMethod.PUT -> MethodPut
        HttpMethod.DELETE -> MethodDelete
        HttpMethod.PATCH -> MethodPatch
        HttpMethod.HEAD -> MethodHead
        HttpMethod.OPTIONS -> MethodOptions
        HttpMethod.WS -> MethodWs
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiTestrScreen(
    onMiniSettings: () -> Unit,
    onNavigateToSaved: () -> Unit = {},
    onSendToIntercept: (ApiRequestItem) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val appContext = LocalContext.current
    val currentRequest by ApiCollectionsRepository.currentActiveRequest.collectAsState()
    val userSettings by SettingsRepository.settings.collectAsState()

    var reqState by remember(currentRequest) { mutableStateOf(currentRequest) }
    var responseData by remember { mutableStateOf<ApiResponseData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Active WebSocket session state
    val wsSession = remember(reqState.url, reqState.headers) {
        ApiTestrRepository.getOrCreateWsSession(reqState.url, reqState.headers)
    }
    val wsMessages by wsSession.messages.collectAsState()
    val wsStatus by wsSession.status.collectAsState()
    val wsError by wsSession.error.collectAsState()
    var wsDraftMessage by remember { mutableStateOf("{\"type\":\"ping\"}") }

    // Auto-save draft on changes
    LaunchedEffect(reqState) {
        ApiCollectionsRepository.saveRequest(reqState)
    }

    var testResults by remember { mutableStateOf<List<AssertionResult>?>(null) }

    var selectedViewMode by remember { mutableIntStateOf(0) } // 0: Request, 1: Response
    var followRedirects by remember { mutableStateOf(true) }
    var requestTab by remember { mutableIntStateOf(0) } // 0: Query, 1: Headers, 2: Body, 3: Auth, 4: Scripts, 5: Chain, 6: Tests, 7: WS
    var responseTab by remember { mutableIntStateOf(0) } // 0: Body, 1: Headers, 2: Metrics, 3: Tests

    var showMethodDropdown by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSnippetModal by remember { mutableStateOf(false) }
    var showImportCurlModal by remember { mutableStateOf(false) }
    var showRedirectSheet by remember { mutableStateOf(false) }
    var selectedSnippetLang by remember { mutableStateOf("Python - Requests") }

    val snackbarHostState = remember { SnackbarHostState() }
    fun showSavedSnackbar(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    val performHaptic = rememberAppHapticFeedback()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Main Scrollable Content Area with top padding so it scrolls smoothly behind floating header
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 58.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ==========================================
            // 1. TITLE CARD & URL BAR
            // ==========================================
            TitleAndUrlCard(
                reqState = reqState,
                followRedirects = followRedirects,
                isDark = isDark,
                onTitleChanged = { newTitle -> reqState = reqState.copy(name = newTitle) },
                onMethodChanged = { newMethod -> reqState = reqState.copy(method = newMethod) },
                onUrlChanged = { newUrl ->
                    val parsed = ApiTestrRepository.parseUrlToQueryParams(newUrl)
                    reqState = reqState.copy(
                        url = newUrl,
                        queryParams = if (parsed.isNotEmpty() || newUrl.contains("?")) parsed else reqState.queryParams
                    )
                },
                onToggleRedirects = {
                    followRedirects = !followRedirects
                    performHaptic(HapticFeedbackType.TextHandleMove)
                    showSavedSnackbar(if (followRedirects) "Follow redirects: Enabled" else "Follow redirects: Disabled")
                },
                onLongPressRedirects = {
                    showRedirectSheet = true
                    performHaptic(HapticFeedbackType.LongPress)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 2. VIEW MODE SWITCHER (Request / Response)
            // PixelOS Dynamic Pill UX: Selected tab is full-rounded pill (24.dp),
            // Unselected tab is polished almost-rounded (12.dp)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isReqActive = selectedViewMode == 0
                val reqShape = if (isReqActive) RoundedCornerShape(24.dp) else RoundedCornerShape(12.dp)
                val reqBg by animateColorAsState(
                    targetValue = if (isReqActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    label = "reqBg"
                )
                val reqColor by animateColorAsState(
                    targetValue = if (isReqActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "reqColor"
                )

                Surface(
                    onClick = {
                        selectedViewMode = 0
                        performHaptic(HapticFeedbackType.TextHandleMove)
                    },
                    shape = reqShape,
                    color = reqBg,
                    border = if (!isReqActive) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Request",
                            fontSize = 12.5.sp,
                            fontWeight = if (isReqActive) FontWeight.Bold else FontWeight.Medium,
                            color = reqColor
                        )
                    }
                }

                val isRespActive = selectedViewMode == 1
                val respShape = if (isRespActive) RoundedCornerShape(24.dp) else RoundedCornerShape(12.dp)
                val respBg by animateColorAsState(
                    targetValue = if (isRespActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    label = "respBg"
                )
                val respColor by animateColorAsState(
                    targetValue = if (isRespActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "respColor"
                )

                Surface(
                    onClick = {
                        selectedViewMode = 1
                        performHaptic(HapticFeedbackType.TextHandleMove)
                    },
                    shape = respShape,
                    color = respBg,
                    border = if (!isRespActive) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (reqState.method == HttpMethod.WS) "Live Frames (${wsMessages.size})" else "Response",
                            fontSize = 12.5.sp,
                            fontWeight = if (isRespActive) FontWeight.Bold else FontWeight.Medium,
                            color = respColor
                        )
                        if (responseData != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (responseData!!.isSuccess) Color(0xFF22C55E) else Color(0xFFEF4444))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedViewMode == 0) {
                // ==========================================
                // 3. PRIMARY REQUEST TABS (Query, Headers, Body, Auth, etc.)
                // ==========================================
                val tabs = if (reqState.method == HttpMethod.WS) {
                    listOf(
                        "Query" to reqState.queryParams.count { it.isEnabled },
                        "Headers" to reqState.headers.count { it.isEnabled },
                        "WS Payload" to 0,
                        "Auth" to (if (reqState.authType != AuthType.NONE) 1 else 0),
                        "Scripts" to 0
                    )
                } else {
                    listOf(
                        "Query" to reqState.queryParams.count { it.isEnabled },
                        "Headers" to reqState.headers.count { it.isEnabled },
                        "Body" to 0,
                        "Auth" to (if (reqState.authType != AuthType.NONE) 1 else 0),
                        "Scripts" to 0,
                        "Chain" to reqState.chainExtractions.count { it.enabled },
                        "Tests" to reqState.assertions.count { it.enabled }
                    )
                }

                val safeTab = requestTab.coerceIn(0, tabs.size - 1)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { index, (label, badgeCount) ->
                        val isSelected = safeTab == index
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    requestTab = index
                                    performHaptic(HapticFeedbackType.TextHandleMove)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (badgeCount > 0) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = badgeCount.toString(),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(if (isSelected) 24.dp else 0.dp)
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ==========================================
                // 4. TAB CONTENTS
                // ==========================================
                when (safeTab) {
                    0 -> {
                        // QUERY PARAMETERS TAB
                        KeyValueTabContent(
                            title = "Query Parameters",
                            items = reqState.queryParams,
                            isDark = isDark,
                            onItemsChanged = { updated ->
                                val newUrl = ApiTestrRepository.updateUrlWithQueryParams(reqState.url, updated)
                                reqState = reqState.copy(queryParams = updated, url = newUrl)
                            },
                            onCopyJson = { json ->
                                copyToClipboard(appContext, "Query JSON", json)
                                showSavedSnackbar("Copied Query as JSON")
                                performHaptic(HapticFeedbackType.TextHandleMove)
                            },
                            onCopyFlat = { flat ->
                                copyToClipboard(appContext, "Query Params", flat)
                                showSavedSnackbar("Copied Query Params")
                                performHaptic(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                    1 -> {
                        // REQUEST HEADERS TAB
                        KeyValueTabContent(
                            title = "Request Headers",
                            items = reqState.headers,
                            isDark = isDark,
                            onItemsChanged = { updated ->
                                reqState = reqState.copy(headers = updated)
                            },
                            onCopyJson = { json ->
                                copyToClipboard(appContext, "Headers JSON", json)
                                showSavedSnackbar("Copied Headers as JSON")
                                performHaptic(HapticFeedbackType.TextHandleMove)
                            },
                            onCopyFlat = { flat ->
                                copyToClipboard(appContext, "Headers", flat)
                                showSavedSnackbar("Copied Headers")
                                performHaptic(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                    2 -> {
                        if (reqState.method == HttpMethod.WS) {
                            // WS Message Stream Composer
                            WsStreamComposerTab(
                                draftMessage = wsDraftMessage,
                                wsStatus = wsStatus,
                                isDark = isDark,
                                onDraftChanged = { wsDraftMessage = it },
                                onSend = {
                                    if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank()) {
                                        wsSession.send(wsDraftMessage)
                                        performHaptic(HapticFeedbackType.TextHandleMove)
                                        showSavedSnackbar("Sent WS frame")
                                    } else {
                                        showSavedSnackbar("Connect WebSocket first")
                                    }
                                }
                            )
                        } else {
                            // HTTP Request Body
                            HttpRequestBodyTab(
                                bodyType = reqState.bodyType,
                                bodyContent = reqState.bodyContent,
                                isDark = isDark,
                                onBodyTypeChanged = { reqState = reqState.copy(bodyType = it) },
                                onBodyContentChanged = { reqState = reqState.copy(bodyContent = it) },
                                onBeautify = {
                                    reqState = reqState.copy(bodyContent = ToolsRepository.formatJson(reqState.bodyContent))
                                    performHaptic(HapticFeedbackType.TextHandleMove)
                                    showSavedSnackbar("Beautified JSON Body")
                                }
                            )
                        }
                    }
                    3 -> {
                        // AUTH TAB
                        AuthSettingsTab(
                            authType = reqState.authType,
                            bearerToken = reqState.bearerToken,
                            basicUsername = reqState.basicUsername,
                            basicPassword = reqState.basicPassword,
                            apiKeyHeader = reqState.apiKeyHeader,
                            apiKeyValue = reqState.apiKeyValue,
                            onAuthTypeChanged = { reqState = reqState.copy(authType = it) },
                            onBearerChanged = { reqState = reqState.copy(bearerToken = it) },
                            onBasicUsernameChanged = { reqState = reqState.copy(basicUsername = it) },
                            onBasicPasswordChanged = { reqState = reqState.copy(basicPassword = it) },
                            onApiKeyHeaderChanged = { reqState = reqState.copy(apiKeyHeader = it) },
                            onApiKeyValueChanged = { reqState = reqState.copy(apiKeyValue = it) }
                        )
                    }
                    4 -> {
                        // SCRIPTS TAB
                        ScriptsTabContent(
                            preScript = reqState.preScript,
                            onPreScriptChanged = { reqState = reqState.copy(preScript = it) }
                        )
                    }
                    5 -> {
                        // CHAIN EXTRACTIONS TAB
                        ChainExtractionEditor(
                            rules = reqState.chainExtractions,
                            onRulesChanged = { reqState = reqState.copy(chainExtractions = it) }
                        )
                    }
                    6 -> {
                        // TEST ASSERTIONS TAB
                        AssertionEditor(
                            assertions = reqState.assertions,
                            onAssertionsChanged = { reqState = reqState.copy(assertions = it) }
                        )
                    }
                }
            } else {
                // ==========================================
                // 5. RESPONSE VIEW / WS CONSOLE VIEW
                // ==========================================
                if (reqState.method == HttpMethod.WS) {
                    // WebSocket Live Console
                    WsConsoleTabContent(
                        wsStatus = wsStatus,
                        wsMessages = wsMessages,
                        wsError = wsError,
                        wsDraftMessage = wsDraftMessage,
                        onDraftChanged = { wsDraftMessage = it },
                        onSend = {
                            if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank()) {
                                wsSession.send(wsDraftMessage)
                                performHaptic(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onClear = {
                            wsSession.clearMessages()
                            performHaptic(HapticFeedbackType.TextHandleMove)
                        }
                    )
                } else if (responseData == null) {
                    // Empty Response Placeholder
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.RocketLaunch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No response yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap Send below to execute this HTTP request and inspect headers, latency, and status code.",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Full Rich Response Inspector (Reqable-level detailed overview, cookies, hex, syntax highlighting, smart save)
                    val resp = responseData!!
                    RichResponseInspector(
                        response = resp,
                        requestUrl = reqState.url,
                        assertions = reqState.assertions,
                        testResults = testResults,
                        isDark = isDark,
                        onRunTests = {
                            testResults = AssertionRunner.run(reqState.assertions, resp)
                            performHaptic(HapticFeedbackType.TextHandleMove)
                        },
                        onBeautifyBody = {
                            responseData = resp.copy(body = ToolsRepository.formatJson(resp.body))
                            performHaptic(HapticFeedbackType.TextHandleMove)
                            showSavedSnackbar("Beautified Response JSON")
                        },
                        onShowSnackbar = { msg ->
                            showSavedSnackbar(msg)
                        }
                    )
                }
            }

            // Bottom Spacer to prevent floating Send bar and navigation bar from overlapping content
            Spacer(modifier = Modifier.height((userSettings.fabBottomSpacingDp + 64).dp))
        }

        // ==========================================
        // 6. FLOATING BOTTOM SEND BAR
        // ==========================================
        SendFloatingBar(
            isLoading = isLoading,
            isWs = reqState.method == HttpMethod.WS,
            wsStatus = wsStatus,
            modifier = Modifier.align(Alignment.BottomCenter),
            onSend = {
                if (reqState.method == HttpMethod.WS) {
                    if (wsStatus == WsConnectionStatus.CONNECTED || wsStatus == WsConnectionStatus.CONNECTING) {
                        wsSession.close()
                        performHaptic(HapticFeedbackType.LongPress)
                    } else {
                        wsSession.connect()
                        performHaptic(HapticFeedbackType.LongPress)
                    }
                } else {
                    isLoading = true
                    performHaptic(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        val env = com.example.data.env.EnvironmentRepository.active()
                        fun sub(s: String) = com.example.data.env.EnvironmentRepository.substitute(s, env)
                        val substitutedState = reqState.copy(
                            url = sub(reqState.url),
                            headers = reqState.headers.map { it.copy(value = sub(it.value)) },
                            queryParams = reqState.queryParams.map { it.copy(value = sub(it.value)) },
                            bodyContent = sub(reqState.bodyContent),
                            bearerToken = sub(reqState.bearerToken),
                            apiKeyValue = sub(reqState.apiKeyValue),
                        )
                        val resp = ApiTestrRepository.executeRequest(substitutedState, followRedirects)
                        responseData = resp
                        testResults = null
                        isLoading = false
                        selectedViewMode = 1 // auto switch to response view

                        if (resp.isSuccess && reqState.chainExtractions.isNotEmpty()) {
                            val written = ApiCollectionsRepository.runChainExtractions(reqState, resp.body)
                            if (written.isNotEmpty()) {
                                showSavedSnackbar("Extracted " + written.keys.joinToString(", ") { "{{$it}}" })
                            }
                        }

                        if (reqState.assertions.any { it.enabled }) {
                            testResults = AssertionRunner.run(reqState.assertions, resp)
                        }
                    }
                }
            }
        )

        // ==========================================
        // 7. TOP FLOATING SECTION HEADER PILL
        // ==========================================
        SectionHeader(
            title = "API Testr",
            icon = Icons.Default.Code,
            onMiniSettings = onMiniSettings,
            actions = {
                GlassActionPill(
                    icon = if (reqState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (reqState.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        val updated = reqState.copy(isFavorite = !reqState.isFavorite)
                        reqState = updated
                        ApiCollectionsRepository.saveRequest(updated)
                        performHaptic(HapticFeedbackType.LongPress)
                        showSavedSnackbar(if (updated.isFavorite) "Added to favorites" else "Removed from favorites")
                    }
                )
                GlassActionPill(
                    icon = Icons.Default.Save,
                    contentDescription = "Save",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        ApiCollectionsRepository.saveRequest(reqState)
                        performHaptic(HapticFeedbackType.LongPress)
                        showSavedSnackbar("Saved \"${reqState.name}\"")
                    }
                )
                GlassActionPill(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "More",
                    onClick = { showMoreMenu = true }
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )

        // Snackbar Host positioned above the floating action bar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (userSettings.fabBottomSpacingDp + 54).dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }

    // ==========================================
    // MODALS & DROPDOWNS
    // ==========================================

    // Dropdown: More Options Menu
    DropdownMenu(
        expanded = showMoreMenu,
        onDismissRequest = { showMoreMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("</> Code Snippet") },
            onClick = {
                showMoreMenu = false
                showSnippetModal = true
            }
        )
        DropdownMenuItem(
            text = { Text("📥 Import cURL") },
            onClick = {
                showMoreMenu = false
                showImportCurlModal = true
            }
        )
        DropdownMenuItem(
            text = { Text("📋 View Saved APIs") },
            onClick = {
                showMoreMenu = false
                onNavigateToSaved()
            }
        )
        DropdownMenuItem(
            text = { Text("📡 Send to Intercept") },
            onClick = {
                showMoreMenu = false
                onSendToIntercept(reqState)
            }
        )
        DropdownMenuItem(
            text = { Text("🧹 Clear All Fields") },
            onClick = {
                reqState = ApiRequestItem(name = "New Request", url = "https://httpbin.org/get")
                showMoreMenu = false
                showSavedSnackbar("Fields cleared")
            }
        )
        DropdownMenuItem(
            text = { Text("📁 Export cURL as File") },
            onClick = {
                showMoreMenu = false
                val curl = buildString {
                    append("curl -X ").append(reqState.method.name)
                    append(" '").append(reqState.url).append("'")
                    reqState.headers.filter { it.key.isNotBlank() }.forEach { h ->
                        append(" -H '").append(h.key).append(": ").append(h.value).append("'")
                    }
                    if (reqState.bodyContent.isNotBlank() && reqState.bodyType != RequestBodyType.NONE) {
                        val escaped = reqState.bodyContent.replace("'", "'\\''")
                        append(" --data '").append(escaped).append("'")
                    }
                }
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val file = com.example.data.export.FileExporter.writeCurl(appContext, curl)
                        com.example.data.export.FileExporter.shareFile(
                            appContext, file,
                            "application/x-shellscript",
                            "cURL export"
                        )
                    }
                }
            }
        )
    }

    // Modal: Code Snippet
    if (showSnippetModal) {
        val snippetCode = remember(reqState, selectedSnippetLang) {
            ApiTestrRepository.generateCodeSnippet(reqState, selectedSnippetLang)
        }

        ModalBottomSheet(
            onDismissRequest = { showSnippetModal = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Code Snippet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { showSnippetModal = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Python - Requests", "Python - httpx", "JavaScript - fetch", "Kotlin - OkHttp", "cURL", "Go").forEach { lang ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedSnippetLang == lang) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedSnippetLang = lang }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = lang,
                                color = if (selectedSnippetLang == lang) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDark) Color(0xFF070B14) else Color(0xFF1E2430),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(snippetCode, color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        copyToClipboard(appContext, "Code Snippet", snippetCode)
                        showSnippetModal = false
                        showSavedSnackbar("Copied $selectedSnippetLang snippet")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Snippet")
                }
            }
        }
    }

    // Modal: Import cURL
    if (showImportCurlModal) {
        var curlInput by remember { mutableStateOf("curl -X POST https://httpbin.org/post -H \"Content-Type: application/json\" -d '{\"hello\":\"world\"}'") }

        ModalBottomSheet(
            onDismissRequest = { showImportCurlModal = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text("Import cURL Command", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = curlInput,
                    onValueChange = { curlInput = it },
                    placeholder = { Text("Paste cURL command here...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val parsed = ApiTestrRepository.parseCurl(curlInput)
                        reqState = parsed
                        ApiCollectionsRepository.saveRequest(parsed)
                        showImportCurlModal = false
                        performHaptic(HapticFeedbackType.LongPress)
                        showSavedSnackbar("Imported \"${parsed.name}\"")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Import & Load Request", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Modal: Redirect Options Sheet
    if (showRedirectSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRedirectSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Redirect Policy Options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Control whether requests automatically follow HTTP 3xx redirect status codes (e.g. 301, 302, 307, 308).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Follow HTTP Redirects", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (followRedirects) "Enabled (Max 5 hops)" else "Disabled (Stop at 3xx)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = followRedirects,
                        onCheckedChange = { followRedirects = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showRedirectSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: TITLE CARD, URL BAR, AND DROPDOWN
// ====================================================================

@Composable
private fun TitleAndUrlCard(
    reqState: ApiRequestItem,
    followRedirects: Boolean,
    isDark: Boolean,
    onTitleChanged: (String) -> Unit,
    onMethodChanged: (HttpMethod) -> Unit,
    onUrlChanged: (String) -> Unit,
    onToggleRedirects: () -> Unit,
    onLongPressRedirects: () -> Unit
) {
    var isEditingTitle by remember { mutableStateOf(false) }
    var showMethodMenu by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val methodColor = getMethodColor(reqState.method)

    val menuArrowRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showMethodMenu) 180f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        ),
        label = "menuArrowRotation"
    )

    val rocketTilt by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (followRedirects) -18f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        ),
        label = "rocketTilt"
    )

    val rocketScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (followRedirects) 1.15f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "rocketScale"
    )

    val rocketBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (followRedirects) primaryColor.copy(alpha = if (isDark) 0.22f else 0.15f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "rocketBg"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditingTitle) {
                    BasicTextField(
                        value = reqState.name,
                        onValueChange = onTitleChanged,
                        textStyle = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(primaryColor),
                        modifier = Modifier
                            .weight(1f)
                            .drawBehind {
                                val strokeWidth = 2.dp.toPx()
                                val y = size.height - strokeWidth / 2
                                drawLine(
                                    color = primaryColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = strokeWidth,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )
                            }
                            .padding(bottom = 2.dp)
                    )
                } else {
                    Text(
                        text = reqState.name.ifBlank { "New Request" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isEditingTitle = true }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(primaryColor.copy(alpha = if (isEditingTitle) 0.2f else 0.08f))
                        .clickable { isEditingTitle = !isEditingTitle },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEditingTitle) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = "Edit title",
                        tint = primaryColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Draft Note
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Autosaved as draft · unsaved changes",
                style = TextStyle(
                    fontSize = 9.5.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // URL Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Method Dropdown Trigger
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                            .background(methodColor.copy(alpha = if (isDark) 0.22f else 0.14f))
                            .clickable { showMethodMenu = true }
                            .padding(horizontal = 11.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reqState.method.name,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = methodColor
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Select method",
                            tint = methodColor,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = menuArrowRotation }
                        )
                    }

                    // Method Menu Dropdown
                    DropdownMenu(
                        expanded = showMethodMenu,
                        onDismissRequest = { showMethodMenu = false }
                    ) {
                        HttpMethod.values().forEach { m ->
                            val mCol = getMethodColor(m)
                            val isSelected = reqState.method == m
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(mCol.copy(alpha = 0.18f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = m.name,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                color = mCol
                                            )
                                        }
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = primaryColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onMethodChanged(m)
                                    showMethodMenu = false
                                }
                            )
                        }
                    }
                }

                // Editable URL Text Field
                BasicTextField(
                    value = reqState.url,
                    onValueChange = onUrlChanged,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(primaryColor),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (reqState.url.isEmpty()) {
                                Text(
                                    text = "https://api.example.com/v1/resource",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                // Follow Redirects Rocket Toggle with Animated Pill
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                        .background(rocketBg)
                        .clickable { onToggleRedirects() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = "Follow redirects",
                        tint = if (followRedirects) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer {
                                rotationZ = rocketTilt
                                scaleX = rocketScale
                                scaleY = rocketScale
                            }
                    )
                }
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: KEY-VALUE TABLE & LIVE JSON PREVIEW BLOCK
// ====================================================================

@Composable
private fun KeyValueTabContent(
    title: String,
    items: List<KeyValueItem>,
    isDark: Boolean,
    onItemsChanged: (List<KeyValueItem>) -> Unit,
    onCopyJson: (String) -> Unit,
    onCopyFlat: (String) -> Unit
) {
    var showRawEditor by remember { mutableStateOf(false) }

    val formattedJson = remember(items) {
        val activeItems = items.filter { it.isEnabled && it.key.isNotBlank() }
        if (activeItems.isEmpty()) "{\n}"
        else buildString {
            append("{\n")
            activeItems.forEachIndexed { idx, item ->
                append("  \"${item.key}\": \"${item.value}\"")
                if (idx < activeItems.size - 1) append(",")
                append("\n")
            }
            append("}")
        }
    }

    val flatString = remember(items) {
        items.filter { it.isEnabled && it.key.isNotBlank() }.joinToString("&") { "${it.key}=${it.value}" }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Key-Value Table Container (Clean modern surface, dividers removed)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                // Table Header with Right-Aligned Add Row Action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Text(
                            text = "KEY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "VALUE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Right-Aligned "+ Add Row" Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { onItemsChanged(items + KeyValueItem()) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Row",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add row",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Table Rows (Dividers removed for seamless pixel-like design)
                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No parameters. Tap '+ Add row' on the right to insert.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    items.forEach { item ->
                        key(item.id) {
                            KeyValueTableRow(
                                item = item,
                                isDark = isDark,
                                onToggle = { isEnabled ->
                                    onItemsChanged(items.map { if (it.id == item.id) it.copy(isEnabled = isEnabled) else it })
                                },
                                onKeyChange = { newKey ->
                                    onItemsChanged(items.map { if (it.id == item.id) it.copy(key = newKey) else it })
                                },
                                onValueChange = { newVal ->
                                    onItemsChanged(items.map { if (it.id == item.id) it.copy(value = newVal) else it })
                                },
                                onDelete = {
                                    onItemsChanged(items.filter { it.id != item.id })
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live JSON Preview & Raw Editor Block
        LiveJsonAndRawBlock(
            formattedJson = formattedJson,
            rawText = items.joinToString("\n") { if (it.isEnabled) "${it.key}: ${it.value}" else "// ${it.key}: ${it.value}" },
            showRawEditor = showRawEditor,
            isDark = isDark,
            onToggleRawEditor = { showRawEditor = !showRawEditor },
            onBeautify = {
                // Table is already formatted into JSON
            },
            onCopyJson = { onCopyJson(formattedJson) },
            onCopyFlat = { onCopyFlat(flatString) },
            onClear = { onItemsChanged(emptyList()) },
            onRawTextChanged = { newText ->
                val lines = newText.split("\n")
                val updated = lines.mapNotNull { line ->
                    if (line.isBlank()) null
                    else {
                        val disabled = line.startsWith("//")
                        val content = if (disabled) line.removePrefix("//").trim() else line
                        val parts = content.split(":", limit = 2)
                        if (parts.size == 2) KeyValueItem(key = parts[0].trim(), value = parts[1].trim(), isEnabled = !disabled) else null
                    }
                }
                onItemsChanged(updated)
            }
        )
    }
}

@Composable
private fun KeyValueTableRow(
    item: KeyValueItem,
    isDark: Boolean,
    onToggle: (Boolean) -> Unit,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val valueColor = if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)

    // Local mutable state to deliver 100% responsive fast typing with zero dropped characters
    var localKey by remember(item.id) { mutableStateOf(item.key) }
    var localValue by remember(item.id) { mutableStateOf(item.value) }

    LaunchedEffect(item.key) {
        if (localKey != item.key) localKey = item.key
    }
    LaunchedEffect(item.value) {
        if (localValue != item.value) localValue = item.value
    }

    val checkboxScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (item.isEnabled) 1.0f else 0.92f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "checkboxScale"
    )

    val checkboxBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (item.isEnabled) primaryColor else Color.Transparent,
        label = "checkboxBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Custom Animated Checkbox
        Box(
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = checkboxScale
                    scaleY = checkboxScale
                }
                .clip(RoundedCornerShape(6.dp))
                .background(checkboxBg)
                .border(
                    1.5.dp,
                    if (item.isEnabled) primaryColor else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onToggle(!item.isEnabled) },
            contentAlignment = Alignment.Center
        ) {
            if (item.isEnabled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Key Input with zero lag
        BasicTextField(
            value = localKey,
            onValueChange = {
                localKey = it
                onKeyChange(it)
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textDecoration = if (!item.isEnabled) TextDecoration.LineThrough else TextDecoration.None
            ),
            singleLine = true,
            cursorBrush = SolidColor(primaryColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (localKey.isEmpty()) {
                        Text("Key", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Value Input with zero lag
        BasicTextField(
            value = localValue,
            onValueChange = {
                localValue = it
                onValueChange(it)
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = if (item.isEnabled) valueColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textDecoration = if (!item.isEnabled) TextDecoration.LineThrough else TextDecoration.None
            ),
            singleLine = true,
            cursorBrush = SolidColor(primaryColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (localValue.isEmpty()) {
                        Text("Value", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Delete Row Action
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete row",
                tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: LIVE JSON PREVIEW & SYNTAX HIGHLIGHTING BLOCK
// ====================================================================

@Composable
private fun LiveJsonAndRawBlock(
    formattedJson: String,
    rawText: String,
    showRawEditor: Boolean,
    isDark: Boolean,
    onToggleRawEditor: () -> Unit,
    onBeautify: () -> Unit,
    onCopyJson: () -> Unit,
    onCopyFlat: () -> Unit,
    onClear: () -> Unit,
    onRawTextChanged: (String) -> Unit
) {
    val codeBg = if (isDark) Color(0xFF04060C) else Color(0xFF141720)
    val toolbarBg = if (isDark) Color(0xFF0A0E18) else Color(0xFF1B202C)
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = codeBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2838))
    ) {
        Column {
            // JSON Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(toolbarBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JSON",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8B95A6),
                    letterSpacing = 1.sp
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Beautify Button
                    CodeToolbarAction(
                        icon = Icons.Default.AutoAwesome,
                        description = "Beautify JSON",
                        onClick = onBeautify
                    )

                    // Copy as JSON
                    CodeToolbarAction(
                        icon = Icons.Default.Code,
                        description = "Copy as JSON",
                        onClick = onCopyJson
                    )

                    // Copy as Flat key=value
                    CodeToolbarAction(
                        icon = Icons.Default.ContentCopy,
                        description = "Copy as key=value",
                        onClick = onCopyFlat
                    )

                    // Clear All
                    CodeToolbarAction(
                        icon = Icons.Default.Delete,
                        description = "Clear all",
                        onClick = onClear
                    )

                    // Toggle Raw Text Editor
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showRawEditor) primaryColor else Color.Transparent)
                            .clickable { onToggleRawEditor() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Toggle Raw Editor",
                            tint = if (showRawEditor) MaterialTheme.colorScheme.onPrimary else Color(0xFF8B95A6),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Syntax Highlighted JSON Preview
            val lines = formattedJson.split("\n")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                lines.forEachIndexed { index, line ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${index + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF3B4862),
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = highlightJsonLine(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }

            // Expandable Raw Text Editor Drawer
            AnimatedVisibility(
                visible = showRawEditor,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0xFF030408) else Color(0xFF10131B))
                        .drawBehind {
                            drawLine(
                                color = Color(0xFF1E2838),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Editor",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF5B6B85),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "key: value · // to disable a row",
                            fontSize = 9.5.sp,
                            color = Color(0xFF4A556B)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    BasicTextField(
                        value = rawText,
                        onValueChange = onRawTextChanged,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            color = Color(0xFFC9D3E8),
                            lineHeight = 16.sp
                        ),
                        cursorBrush = SolidColor(primaryColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 70.dp, max = 150.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeToolbarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color(0xFF8B95A6),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Builds syntax highlighted AnnotatedString for JSON lines.
 */
private fun highlightJsonLine(line: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var inQuotes = false
        var isKey = false
        var currentToken = StringBuilder()

        val parts = line.split(":", limit = 2)
        if (parts.size == 2 && parts[0].trim().startsWith("\"")) {
            val keyPart = parts[0]
            val valPart = parts[1]

            val colonIdx = line.indexOf(':')
            val beforeColon = line.substring(0, colonIdx)
            val afterColon = line.substring(colonIdx)

            // Key in cyan/blue
            withStyle(SpanStyle(color = Color(0xFF7EC8E3))) {
                append(beforeColon)
            }
            // Colon in punctuation color
            withStyle(SpanStyle(color = Color(0xFF8B95A6))) {
                append(":")
            }
            // Value in green or amber
            withStyle(SpanStyle(color = Color(0xFF8CE3A0))) {
                append(afterColon.removePrefix(":"))
            }
        } else {
            // Generic braces / brackets
            withStyle(SpanStyle(color = Color(0xFFC9D3E8))) {
                append(line)
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: HTTP REQUEST BODY & WEBSOCKET COMPOSER
// ====================================================================

@Composable
private fun HttpRequestBodyTab(
    bodyType: RequestBodyType,
    bodyContent: String,
    isDark: Boolean,
    onBodyTypeChanged: (RequestBodyType) -> Unit,
    onBodyContentChanged: (String) -> Unit,
    onBeautify: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        // Content-Type Selector Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RequestBodyType.values().forEach { bt ->
                val isSel = bodyType == bt
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onBodyTypeChanged(bt) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = bt.name,
                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (bodyType == RequestBodyType.NONE) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(
                    modifier = Modifier.padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "This request does not send a body payload.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Body Editor Surface
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF060911) else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    BasicTextField(
                        value = bodyContent,
                        onValueChange = onBodyContentChanged,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = SolidColor(primaryColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onBeautify,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = "Beautify", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Beautify JSON", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WsStreamComposerTab(
    draftMessage: String,
    wsStatus: WsConnectionStatus,
    isDark: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("WebSocket Frame Payload", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (isDark) Color(0xFF060911) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            BasicTextField(
                value = draftMessage,
                onValueChange = onDraftChanged,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onSend,
                enabled = wsStatus == WsConnectionStatus.CONNECTED && draftMessage.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send Frame", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: AUTH, SCRIPTS, CHAIN EXTRACTIONS, AND ASSERTIONS
// ====================================================================

@Composable
private fun AuthSettingsTab(
    authType: AuthType,
    bearerToken: String,
    basicUsername: String,
    basicPassword: String,
    apiKeyHeader: String,
    apiKeyValue: String,
    onAuthTypeChanged: (AuthType) -> Unit,
    onBearerChanged: (String) -> Unit,
    onBasicUsernameChanged: (String) -> Unit,
    onBasicPasswordChanged: (String) -> Unit,
    onApiKeyHeaderChanged: (String) -> Unit,
    onApiKeyValueChanged: (String) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Authorization Method", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AuthType.values().forEach { at ->
                val isSel = authType == at
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onAuthTypeChanged(at) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = at.name.replace('_', ' '),
                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (authType) {
            AuthType.BEARER -> {
                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = onBearerChanged,
                    label = { Text("Bearer Token") },
                    placeholder = { Text("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
            AuthType.BASIC -> {
                OutlinedTextField(
                    value = basicUsername,
                    onValueChange = onBasicUsernameChanged,
                    label = { Text("Username") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = basicPassword,
                    onValueChange = onBasicPasswordChanged,
                    label = { Text("Password") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
            AuthType.API_KEY -> {
                OutlinedTextField(
                    value = apiKeyHeader,
                    onValueChange = onApiKeyHeaderChanged,
                    label = { Text("Header / Param Key (e.g. X-API-Key)") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKeyValue,
                    onValueChange = onApiKeyValueChanged,
                    label = { Text("API Key Value") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
            else -> {
                Text("No authentication headers will be added to this request.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ScriptsTabContent(
    preScript: String,
    onPreScriptChanged: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Pre-request & Sandbox Scripts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = preScript,
            onValueChange = onPreScriptChanged,
            placeholder = { Text("// JavaScript sandbox (e.g. request.headers['X-Timestamp'] = Date.now())", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}

@Composable
fun ChainExtractionEditor(
    rules: List<ChainExtractionRule>,
    onRulesChanged: (List<ChainExtractionRule>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Response Chaining", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Extract fields into named variables, referenced as {{name}} in other requests.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { onRulesChanged(rules + ChainExtractionRule()) },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Extraction Rule", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (rules.isEmpty()) {
            Text("No extraction rules yet. Add one to extract tokens/IDs from responses.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
        } else {
            rules.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            onRulesChanged(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                        },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )

                    OutlinedTextField(
                        value = rule.jsonPath,
                        onValueChange = { p ->
                            onRulesChanged(rules.map { if (it.id == rule.id) it.copy(jsonPath = p) else it })
                        },
                        placeholder = { Text("Field (e.g. token)", fontSize = 11.sp) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedTextField(
                        value = rule.variableName,
                        onValueChange = { v ->
                            onRulesChanged(rules.map { if (it.id == rule.id) it.copy(variableName = v) else it })
                        },
                        placeholder = { Text("Variable (e.g. authToken)", fontSize = 11.sp) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = { onRulesChanged(rules.filter { it.id != rule.id }) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444).copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AssertionEditor(
    assertions: List<Assertion>,
    onAssertionsChanged: (List<Assertion>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Test Assertions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Evaluate checks against real response status codes, latency, headers, and JSON fields.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { onAssertionsChanged(assertions + Assertion()) },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Assertion", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (assertions.isEmpty()) {
            Text("No assertions yet. Add one to verify status code, response time, or JSON fields.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
        } else {
            assertions.forEach { assertion ->
                fun update(transform: (Assertion) -> Assertion) {
                    onAssertionsChanged(assertions.map { if (it.id == assertion.id) transform(it) else it })
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = assertion.enabled,
                                onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )

                            // Target dropdown
                            var showTargetMenu by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { showTargetMenu = true }
                                        .padding(horizontal = 8.dp, vertical = 7.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(assertion.target.name.replace('_', ' '), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(expanded = showTargetMenu, onDismissRequest = { showTargetMenu = false }) {
                                    AssertionTarget.values().forEach { t ->
                                        DropdownMenuItem(
                                            text = { Text(t.name.replace('_', ' '), fontSize = 12.sp) },
                                            onClick = {
                                                update { it.copy(target = t) }
                                                showTargetMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Operator dropdown
                            var showOperatorMenu by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { showOperatorMenu = true }
                                        .padding(horizontal = 8.dp, vertical = 7.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(assertion.operator.name.replace('_', ' '), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(expanded = showOperatorMenu, onDismissRequest = { showOperatorMenu = false }) {
                                    AssertionOperator.values().forEach { op ->
                                        DropdownMenuItem(
                                            text = { Text(op.name.replace('_', ' '), fontSize = 12.sp) },
                                            onClick = {
                                                update { it.copy(operator = op) }
                                                showOperatorMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { onAssertionsChanged(assertions.filter { it.id != assertion.id }) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444).copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (assertion.target == AssertionTarget.HEADER_VALUE || assertion.target == AssertionTarget.BODY_JSON_FIELD) {
                                OutlinedTextField(
                                    value = assertion.targetKey,
                                    onValueChange = { k -> update { it.copy(targetKey = k) } },
                                    placeholder = {
                                        Text(
                                            if (assertion.target == AssertionTarget.HEADER_VALUE) "Header (content-type)" else "Field (token)",
                                            fontSize = 11.sp
                                        )
                                    },
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            OutlinedTextField(
                                value = assertion.expectedValue,
                                onValueChange = { v -> update { it.copy(expectedValue = v) } },
                                placeholder = { Text("Expected value", fontSize = 11.sp) },
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: WS CONSOLE
// ====================================================================

@Composable
private fun WsConsoleTabContent(
    wsStatus: WsConnectionStatus,
    wsMessages: List<WsMessage>,
    wsError: String?,
    wsDraftMessage: String,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit
) {
    val (dotColor, statusText) = when (wsStatus) {
        WsConnectionStatus.CONNECTED -> Color(0xFF10B981) to "CONNECTED"
        WsConnectionStatus.CONNECTING -> Color(0xFFF59E0B) to "CONNECTING"
        WsConnectionStatus.ERROR -> Color(0xFFEF4444) to "ERROR"
        WsConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "DISCONNECTED"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(statusText, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = dotColor)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${wsMessages.size} frames", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onClear, modifier = Modifier.size(26.dp)) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }
            }
        }

        if (wsError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Error: $wsError",
                color = Color(0xFFEF4444),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                    .padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (wsMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (wsStatus == WsConnectionStatus.CONNECTED) "Connected! Waiting for frames..." else "Connect WebSocket above to stream live frames",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            } else {
                val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US) }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(wsMessages) { msg ->
                        val isOut = !msg.isIncoming
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isOut) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isOut) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color(0xFF10B981).copy(alpha = 0.2f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(if (isOut) "OUT" else "IN", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isOut) Color(0xFF3B82F6) else Color(0xFF10B981))
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(timeFormat.format(java.util.Date(msg.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(msg.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS: FLOATING SEND BUTTON BAR
// ====================================================================

@Composable
private fun SendFloatingBar(
    isLoading: Boolean,
    isWs: Boolean,
    wsStatus: WsConnectionStatus,
    modifier: Modifier = Modifier,
    onSend: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val gradientColor = MaterialTheme.colorScheme.background
    val settings by SettingsRepository.settings.collectAsState()
    val bottomPad = settings.fabBottomSpacingDp.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        gradientColor.copy(alpha = 0f),
                        gradientColor.copy(alpha = 0.85f),
                        gradientColor
                    )
                )
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPad),
        contentAlignment = Alignment.CenterEnd
    ) {
        val containerColor = if (isWs && wsStatus == WsConnectionStatus.CONNECTED) {
            Color(0xFFEF4444)
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onSend,
            enabled = !isLoading,
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            shadowElevation = 8.dp,
            modifier = Modifier.height(46.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isLoading || (isWs && wsStatus == WsConnectionStatus.CONNECTING)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isWs) "Connecting..." else "Sending...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else if (isWs && wsStatus == WsConnectionStatus.CONNECTED) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Disconnect",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Disconnect WS", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color.White)
                } else if (isWs) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Connect",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Connect WS", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

// ====================================================================
// UTILITY FUNCTIONS & HELPERS
// ====================================================================

@Composable
fun AssertionResultRow(result: AssertionResult) {
    val a = result.assertion
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (result.passed) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (result.passed) "Passed" else "Failed",
                tint = if (result.passed) Color(0xFF22C55E) else Color(0xFFEF4444),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val label = when (a.target) {
                    AssertionTarget.STATUS_CODE -> "Status code"
                    AssertionTarget.RESPONSE_TIME_MS -> "Response time"
                    AssertionTarget.HEADER_VALUE -> "Header \"${a.targetKey}\""
                    AssertionTarget.BODY_JSON_FIELD -> "JSON field \"${a.targetKey}\""
                }
                Text(
                    text = "$label ${a.operator.name.lowercase().replace('_', ' ')} \"${a.expectedValue}\"",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (result.passed) "Actual: \"${result.actualValue}\"" else result.message,
                    fontSize = 11.sp,
                    color = if (result.passed) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF4444)
                )
            }
        }
    }
}

@Composable
fun TimelineMetricBar(label: String, time: String, fraction: Float, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(time, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard?.setPrimaryClip(clip)
}
