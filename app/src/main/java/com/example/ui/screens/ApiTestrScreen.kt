package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ApiKeyLocation
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
import com.example.data.repository.WebSocketSession
import com.example.data.repository.WsConnectionStatus
import com.example.data.repository.WsMessage
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.RequestrSegmentedTabs
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiTestrScreen(
    onMiniSettings: () -> Unit,
    onNavigateToSaved: () -> Unit = {},
    onSendToIntercept: (ApiRequestItem) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val currentRequest by ApiCollectionsRepository.currentActiveRequest.collectAsState()
    val allRequests by ApiCollectionsRepository.requests.collectAsState()
    val folders by ApiCollectionsRepository.folders.collectAsState()

    var reqState by remember(currentRequest) { mutableStateOf(currentRequest) }
    var responseData by remember { mutableStateOf<ApiResponseData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // v6.0 transplant (manifest §5.5): WebSocket session state — backup had a bare
    // `wsConnected` boolean that was never set to true; the WS method showed "Connect"
    // but never connected. Now backed by the ported WebSocketSession/WsConnectionStatus.
    var wsSession by remember { mutableStateOf<WebSocketSession?>(null) }
    val disconnectedFlow = remember { MutableStateFlow(WsConnectionStatus.DISCONNECTED) }
    val wsStatus by (wsSession?.status ?: disconnectedFlow).collectAsState()
    // v6.0 transplant (manifest §5.5): WS message stream + draft for WsConsoleTabContent
    val emptyMsgFlow = remember { MutableStateFlow<List<WsMessage>>(emptyList()) }
    val wsMessages by (wsSession?.messages ?: emptyMsgFlow).collectAsState()
    val wsError by (wsSession?.error ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    var wsDraftMessage by remember { mutableStateOf("{\"type\":\"ping\"}") }

    // v6.0 lane B (manifest §api-testr-ws): Disconnect WS confirmation dialog — the
    // SendFloatingBar turns red and reads "Disconnect WS" when connected; tapping it
    // opens an M3 confirmation dialog (Cancel + Disconnect) instead of closing the
    // socket immediately, so an accidental tap does not drop a live session.
    var showDisconnectWsDialog by remember { mutableStateOf(false) }

    // v3.4 Part C — C3 (Test assertions / test runner): results of the last "Run Tests" pass
    // against `responseData`. Cleared whenever a fresh Send/Run happens so stale pass/fail
    // results are never shown next to a response they didn't evaluate.
    var testResults by remember { mutableStateOf<List<AssertionResult>?>(null) }

    // Script-engine wiring (master plan §1.10/§1.11): pm.test() results recorded by the
    // pre-/post-request Rhino scripts, plus any script/polling error surfaced from
    // ApiTestrRepository.executeRequestWithScripts. Kept separate from `testResults`
    // (the declarative AssertionResult list above) since they come from a different
    // engine and are rendered in their own rows (see PmTestResultRow).
    var scriptTestResults by remember { mutableStateOf<List<com.example.scripting.PmTestResult>?>(null) }
    var scriptError by remember { mutableStateOf<String?>(null) }

    var selectedViewMode by remember { mutableIntStateOf(0) } // 0: Request, 1: Response
    var followRedirects by remember { mutableStateOf(true) }
    var requestTab by remember { mutableIntStateOf(0) } // 0: Query, 1: Headers, 2: Body, 3: Auth, 4: Scripts, 5: Chain, 6: Tests
    var responseTab by remember { mutableIntStateOf(0) } // 0: Body, 1: Headers, 2: Metrics, 3: Test Results

    var showMethodDropdown by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSnippetModal by remember { mutableStateOf(false) }
    var showImportCurlModal by remember { mutableStateOf(false) }
    var selectedSnippetLang by remember { mutableStateOf("Python - Requests") }

    // v3.1 fix (A4): Save previously gave zero feedback — the request was written to
    // ApiCollectionsRepository silently, with no confirmation the tap did anything. This is the
    // app's first SnackbarHost; other screens still have none.
    val snackbarHostState = remember { SnackbarHostState() }
    fun showSavedSnackbar(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    // B4: real tactile confirmation for save/favorite/import, gated once behind
    // settings.hapticFeedbackEnabled via the shared helper.
    val performHaptic = rememberAppHapticFeedback()

    // v6.0 transplant (manifest §5.5): settings for SendFloatingBar spacing + snackbar offset
    val settings by SettingsRepository.settings.collectAsState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Task 5(c): send/new-request keyboard shortcuts. Previously MainActivity's key listener
    // called `sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))` / `.../NEW_REQUEST`
    // with no registered receiver anywhere, so Ctrl+Enter/Ctrl+Shift+N were silent no-ops.
    // com.example.AppShortcuts is an in-process SharedFlow this screen collects directly.
    fun sendCurrentRequest() {
        // v6.0 transplant (manifest §5.5): WebSocket connect/disconnect — backup had
        // a bare wsConnected boolean that was never set, so WS method was a dead button.
        if (reqState.method == HttpMethod.WS) {
            val session = wsSession
            if (session != null && session.status.value == WsConnectionStatus.CONNECTED) {
                // v6.0 lane B (manifest §api-testr-ws): confirm before closing a live
                // WebSocket session — the SendFloatingBar already reads "Disconnect WS",
                // and the dialog gives the user a chance to cancel.
                showDisconnectWsDialog = true
            } else if (session == null ||
                session.status.value == WsConnectionStatus.DISCONNECTED ||
                session.status.value == WsConnectionStatus.ERROR
            ) {
                session?.close()
                val newSession = ApiTestrRepository.getOrCreateWsSession(reqState.url, reqState.headers)
                newSession.connect()
                wsSession = newSession
            }
            return
        }
        isLoading = true
        coroutineScope.launch {
            // Master plan §1.10/§1.11: {{var}} substitution, pre-/post-request pm.* scripts,
            // and 202-polling are now all sequenced centrally in
            // ApiTestrRepository.executeRequestWithScripts (pre-script runs BEFORE
            // substitution, so a pre-script's pm.environment.set(...) is visible in the SAME
            // send) — pass the original, un-substituted reqState here rather than
            // pre-substituting it ourselves, to avoid double-applying substitution.
            val outcome = ApiTestrRepository.executeRequestWithScripts(reqState, followRedirects)
            val resp = outcome.response
            responseData = resp
            // v3.4 Part C — C3: clear any previous run's pass/fail results —
            // they belong to a different response, not this one.
            testResults = null
            scriptTestResults = outcome.testResults.ifEmpty { null }
            scriptError = outcome.preScriptError ?: outcome.postScriptError
            isLoading = false
            selectedViewMode = 1 // auto toggle to response

            // Run this request's chain-extraction rules (if any) against the
            // response body, writing extracted values into the active
            // Environment so a subsequent chained request can pick them up.
            if (resp.isSuccess && reqState.chainExtractions.isNotEmpty()) {
                val written = ApiCollectionsRepository.runChainExtractions(reqState, resp.body)
                if (written.isNotEmpty()) {
                    showSavedSnackbar(
                        "Extracted " + written.keys.joinToString(", ") { "{{$it}}" }
                    )
                }
            }

            // v3.4 Part C — C3: if this request carries assertions, evaluate
            // them immediately against the just-received response so a plain
            // Send already shows pass/fail without a second tap.
            if (reqState.assertions.any { it.enabled }) {
                testResults = AssertionRunner.run(reqState.assertions, resp)
            }
        }
    }

    LaunchedEffect(Unit) {
        com.example.AppShortcuts.events.collect { event ->
            when (event) {
                is com.example.ShortcutEvent.SendRequest -> {
                    if (!isLoading) sendCurrentRequest()
                }
                is com.example.ShortcutEvent.NewRequest -> {
                    reqState = ApiRequestItem(name = "New Request", url = "https://httpbin.org/get")
                    showSavedSnackbar("Started a new request")
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Section Header
        SectionHeader(
            title = "API Testr",
            onMiniSettings = onMiniSettings,
            actions = {
                IconButton(onClick = {
                    val updated = reqState.copy(isFavorite = !reqState.isFavorite)
                    reqState = updated
                    ApiCollectionsRepository.saveRequest(updated)
                    performHaptic(HapticFeedbackType.LongPress)
                    showSavedSnackbar(if (updated.isFavorite) "Added to favorites" else "Removed from favorites")
                }) {
                    Icon(
                        imageVector = if (reqState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (reqState.isFavorite) Color(0xFFF59E0B) else TextMuted
                    )
                }
                IconButton(onClick = {
                    ApiCollectionsRepository.saveRequest(reqState)
                    performHaptic(HapticFeedbackType.LongPress)
                    showSavedSnackbar("Saved \"${reqState.name}\"")
                }) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                }
            }
        )

        // v6.0 transplant (manifest §5.5): TitleAndUrlCard replaces the inline GlassCard
        // request bar + separate follow-redirects toggle with a single polished card
        // featuring inline title edit, method menu, URL input, and rocket follow-redirect
        // animation. The Send button moves to SendFloatingBar at the bottom.
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TitleAndUrlCard(
                reqState = reqState,
                followRedirects = followRedirects,
                wsStatus = if (reqState.method == HttpMethod.WS) wsStatus else null,
                onTitleChanged = { reqState = reqState.copy(name = it) },
                onMethodChanged = { reqState = reqState.copy(method = it) },
                onUrlChanged = { reqState = reqState.copy(url = it) },
                onToggleRedirects = { followRedirects = !followRedirects }
            )
        }

        // View Mode Segmented Bar — v6.0 lane B (manifest §api-testr-request/response):
        // frozen RequestrSegmentedTabs (radius 26dp container, selected radius 24dp
        // primary) replaces the old plain Button toggle for visual parity with the
        // approved mockups. Labels stay clean with no counts.
        RequestrSegmentedTabs(
            items = listOf(0, 1),
            selectedItem = selectedViewMode,
            onItemSelected = { selectedViewMode = it },
            label = { if (it == 0) "Request" else "Response" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            equalWeight = true
        )

        // Main View Content
        if (selectedViewMode == 0) {
            // ==========================================
            // REQUEST EDITOR VIEW
            // ==========================================
            // v6.0 transplant (manifest §5.5): WS-specific tab set when method is WS
            val reqTabs = if (reqState.method == HttpMethod.WS) {
                listOf(
                    "Query (${reqState.queryParams.count { it.isEnabled }})",
                    "Headers (${reqState.headers.count { it.isEnabled }})",
                    "WS Payload",
                    "Auth",
                    "Scripts",
                )
            } else {
                listOf(
                    "Query (${reqState.queryParams.count { it.isEnabled }})",
                    "Headers (${reqState.headers.count { it.isEnabled }})",
                    "Body",
                    "Auth",
                    "Scripts",
                    "Chain (${reqState.chainExtractions.count { it.enabled }})",
                    "Tests (${reqState.assertions.count { it.enabled }})",
                    // Master plan §2.8 — per-saved-request mock mode editor tab.
                    "Mock" + if (reqState.mockEnabled) " (On)" else "",
                    // Master plan §1.10 — async polling for 202/in-progress operations.
                    "Polling" + if (reqState.pollingEnabled) " (On)" else "",
                )
            }
            // Clamp tab index when switching between WS and HTTP tab sets
            if (requestTab >= reqTabs.size) requestTab = 0

            TabRow(
                selectedTabIndex = requestTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[requestTab]),
                        color = MaterialTheme.colorScheme.primary,
                        height = 2.dp
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                reqTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = requestTab == index,
                        onClick = { requestTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = if (requestTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (requestTab == index) MaterialTheme.colorScheme.primary else TextSecondary
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (requestTab) {
                    0 -> {
                        // Query Params: Table + JSON + optional raw text editor
                        KeyValueEditorTable(
                            title = "Query Parameters",
                            items = reqState.queryParams,
                            onItemsChanged = { reqState = reqState.copy(queryParams = it) }
                        )
                        var showRawEditor by remember { mutableStateOf(false) }
                        RawEditorToggleRow(showRawEditor) { showRawEditor = !showRawEditor }
                        if (showRawEditor) {
                            RawTextEditor(
                                items = reqState.queryParams,
                                onItemsChanged = { reqState = reqState.copy(queryParams = it) }
                            )
                        }
                    }
                    1 -> {
                        // Headers: Table + JSON + optional raw text editor
                        KeyValueEditorTable(
                            title = "Request Headers",
                            items = reqState.headers,
                            onItemsChanged = { reqState = reqState.copy(headers = it) }
                        )
                        var showRawEditor by remember { mutableStateOf(false) }
                        RawEditorToggleRow(showRawEditor) { showRawEditor = !showRawEditor }
                        if (showRawEditor) {
                            RawTextEditor(
                                items = reqState.headers,
                                onItemsChanged = { reqState = reqState.copy(headers = it) }
                            )
                        }
                    }
                    2 -> {
                        // v6.0 transplant (manifest §5.5): WS Payload tab or HTTP Body tab
                        if (reqState.method == HttpMethod.WS) {
                            WsStreamComposerTab(
                                draftMessage = wsDraftMessage,
                                wsStatus = wsStatus,
                                isDark = isDark,
                                onDraftChanged = { wsDraftMessage = it },
                                onSend = {
                                    if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank()) {
                                        wsSession?.send(wsDraftMessage)
                                        performHaptic(HapticFeedbackType.TextHandleMove)
                                        showSavedSnackbar("Sent WS frame")
                                    } else {
                                        showSavedSnackbar("Connect WebSocket first")
                                    }
                                }
                            )
                        } else {
                            HttpRequestBodyTab(
                                bodyType = reqState.bodyType,
                                bodyContent = reqState.bodyContent,
                                formData = reqState.formData,
                                isDark = isDark,
                                onBodyTypeChanged = { reqState = reqState.copy(bodyType = it) },
                                onBodyContentChanged = { reqState = reqState.copy(bodyContent = it) },
                                onFormDataChanged = { reqState = reqState.copy(formData = it) },
                                onBeautify = {
                                    reqState = reqState.copy(bodyContent = ToolsRepository.formatJson(reqState.bodyContent))
                                    performHaptic(HapticFeedbackType.TextHandleMove)
                                    showSavedSnackbar("Beautified JSON Body")
                                }
                            )
                        }
                    }
                    3 -> {
                        // v6.0 transplant (manifest §5.5): Auth tab extracted into AuthSettingsTab
                        AuthSettingsTab(
                            authType = reqState.authType,
                            bearerToken = reqState.bearerToken,
                            basicUsername = reqState.basicUsername,
                            basicPassword = reqState.basicPassword,
                            apiKeyHeader = reqState.apiKeyHeader,
                            apiKeyValue = reqState.apiKeyValue,
                            apiKeyLocation = reqState.apiKeyLocation,
                            onAuthTypeChanged = { reqState = reqState.copy(authType = it) },
                            onBearerChanged = { reqState = reqState.copy(bearerToken = it) },
                            onBasicUsernameChanged = { reqState = reqState.copy(basicUsername = it) },
                            onBasicPasswordChanged = { reqState = reqState.copy(basicPassword = it) },
                            onApiKeyHeaderChanged = { reqState = reqState.copy(apiKeyHeader = it) },
                            onApiKeyValueChanged = { reqState = reqState.copy(apiKeyValue = it) },
                            onApiKeyLocationChanged = { reqState = reqState.copy(apiKeyLocation = it) }
                        )
                    }
                    4 -> {
                        // v6.0 transplant (manifest §5.5): Scripts tab extracted into ScriptsTabContent
                        ScriptsTabContent(
                            preScript = reqState.preScript,
                            onPreScriptChanged = { reqState = reqState.copy(preScript = it) },
                            postScript = reqState.postScript,
                            onPostScriptChanged = { reqState = reqState.copy(postScript = it) }
                        )
                    }
                    5 -> {
                        // v3.2 Part C — C2: Request chaining — extraction rules run against this
                        // request's response, writing named variables into the active
                        // Environment for a later chained request to reference via {{name}}.
                        ChainExtractionEditor(
                            rules = reqState.chainExtractions,
                            onRulesChanged = { reqState = reqState.copy(chainExtractions = it) }
                        )
                    }
                    6 -> {
                        // v3.4 Part C — C3: assertions attached to this saved request, evaluated
                        // against a real response by AssertionRunner (see the Send handler above
                        // and the standalone "Run Tests" button in the Response view).
                        AssertionEditor(
                            assertions = reqState.assertions,
                            onAssertionsChanged = { reqState = reqState.copy(assertions = it) }
                        )
                    }
                    7 -> {
                        // Master plan §2.8 — per-saved-request mock mode: when enabled, Send
                        // returns these canned fields from ApiTestrRepository.executeRequest
                        // instead of dialing the network at all.
                        MockModeEditor(
                            mockEnabled = reqState.mockEnabled,
                            mockStatusCode = reqState.mockStatusCode,
                            mockStatusMessage = reqState.mockStatusMessage,
                            mockHeaders = reqState.mockHeaders,
                            mockBody = reqState.mockBody,
                            mockDelayMs = reqState.mockDelayMs,
                            isDark = isDark,
                            onMockEnabledChanged = { reqState = reqState.copy(mockEnabled = it) },
                            onMockStatusCodeChanged = { reqState = reqState.copy(mockStatusCode = it) },
                            onMockStatusMessageChanged = { reqState = reqState.copy(mockStatusMessage = it) },
                            onMockHeadersChanged = { reqState = reqState.copy(mockHeaders = it) },
                            onMockBodyChanged = { reqState = reqState.copy(mockBody = it) },
                            onMockDelayMsChanged = { reqState = reqState.copy(mockDelayMs = it) }
                        )
                    }
                    8 -> {
                        // Master plan §1.10 — per-saved-request async polling for 202/
                        // in-progress operations, consumed by
                        // ApiTestrRepository.executeRequestWithScripts.
                        PollingEditor(
                            pollingEnabled = reqState.pollingEnabled,
                            pollingIntervalMs = reqState.pollingIntervalMs,
                            pollingMaxAttempts = reqState.pollingMaxAttempts,
                            pollingLocationHeader = reqState.pollingLocationHeader,
                            onPollingEnabledChanged = { reqState = reqState.copy(pollingEnabled = it) },
                            onPollingIntervalMsChanged = { reqState = reqState.copy(pollingIntervalMs = it) },
                            onPollingMaxAttemptsChanged = { reqState = reqState.copy(pollingMaxAttempts = it) },
                            onPollingLocationHeaderChanged = { reqState = reqState.copy(pollingLocationHeader = it) }
                        )
                    }
                }
            }
        } else {
            // ==========================================
            // RESPONSE VIEW
            // ==========================================
            // v6.0 transplant (manifest §5.5): WS console view when method is WS
            if (reqState.method == HttpMethod.WS) {
                WsConsoleTabContent(
                    wsStatus = wsStatus,
                    wsMessages = wsMessages,
                    wsError = wsError,
                    wsDraftMessage = wsDraftMessage,
                    onDraftChanged = { wsDraftMessage = it },
                    onSend = {
                        if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank()) {
                            wsSession?.send(wsDraftMessage)
                            performHaptic(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onClear = {
                        wsSession?.clearMessages()
                        performHaptic(HapticFeedbackType.TextHandleMove)
                    }
                )
            } else if (responseData == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📡", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No response yet", color = TextSecondary, fontWeight = FontWeight.Bold)
                        Text("Tap Send above to execute this HTTP request", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                val resp = responseData!!

                // v6.0 lane B (manifest §api-testr-response/error/timeout): classify the
                // response for the summary badge + dedicated error/timeout card. A
                // SocketTimeoutException surfaces as statusCode 0 with "timeout" in the
                // error message; a 5xx is a server error; anything else with statusCode 0
                // is a generic network failure. Honest response data is always shown —
                // these cards supplement, never replace, the raw body/headers/timeline.
                val isTimeout = resp.statusCode == 0 &&
                    (resp.error?.contains("timeout", ignoreCase = true) == true ||
                        resp.statusMessage.contains("timeout", ignoreCase = true))
                val isServerError = resp.statusCode >= 500
                val isNetworkError = resp.statusCode == 0 && !isTimeout
                val errorColor = when {
                    isTimeout -> Color(0xFFF59E0B)
                    isServerError -> Color(0xFFEF4444)
                    isNetworkError -> Color(0xFFEF4444)
                    else -> Color(0xFF10B981)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // v6.0 lane B (manifest §api-testr-response): ApiResponseSummary —
                    // bordered card (radius 14dp, surface bg) holding the status badge,
                    // protocol, latency, and size in one row, matching the mockup layout.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isTimeout) {
                                // Amber "Connection Timeout" badge (manifest §api-testr-timeout)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Connection Timeout",
                                        color = Color(0xFFF59E0B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else if (isNetworkError) {
                                StatusCodeBadge(statusCode = resp.statusCode, statusMessage = "Network Failure")
                            } else {
                                StatusCodeBadge(statusCode = resp.statusCode, statusMessage = resp.statusMessage)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            if (!isTimeout && !isNetworkError) {
                                ProtocolBadge(protocol = resp.protocol)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Text("—", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("${resp.latencyMs} ms", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(formatBytes(resp.sizeBytes), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // v6.0 lane B (manifest §api-testr-response/timeout): 4 compact timing
                    // tiles (DNS/TCP/TLS/Total). On timeout they read "—" at half opacity.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TimingTile("DNS", if (isTimeout || isNetworkError) "—" else "${resp.dnsTimeMs}", isTimeout || isNetworkError, Modifier.weight(1f))
                        TimingTile("TCP", if (isTimeout || isNetworkError) "—" else "${resp.connectTimeMs}", isTimeout || isNetworkError, Modifier.weight(1f))
                        TimingTile("TLS", if (isTimeout || isNetworkError) "—" else "${resp.tlsTimeMs}", isTimeout || isNetworkError, Modifier.weight(1f))
                        TimingTile("Total", if (isTimeout || isNetworkError) "—" else "${resp.latencyMs}", isTimeout || isNetworkError, Modifier.weight(1f))
                    }

                    // v3.4 Part C — C3: "Run Tests" — re-evaluate this request's assertions
                    // against the response already sitting in `resp`, without re-sending the
                    // request. Only shown when the request actually carries at least one
                    // enabled assertion, so requests with no tests don't grow an empty button.
                    if (reqState.assertions.any { it.enabled }) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    testResults = AssertionRunner.run(reqState.assertions, resp)
                                    responseTab = 3
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Confirm", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run Tests", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Response Subtabs (Body, Headers, Metrics, Tests)
                    // Master plan §1.10/§1.11: the Tests tab now also covers pm.test() results
                    // recorded by the pre-/post-request scripts (scriptTestResults), not just
                    // the declarative AssertionResult list -- either one alone is enough to show
                    // the tab, and both render inside its content below.
                    val hasScriptTests = !scriptTestResults.isNullOrEmpty()
                    val responseTabTitles = if (reqState.assertions.any { it.enabled } || hasScriptTests) {
                        val passed = (testResults?.count { it.passed } ?: 0) + (scriptTestResults?.count { it.passed } ?: 0)
                        val total = (testResults?.size ?: 0) + (scriptTestResults?.size ?: 0)
                        val suffix = if (testResults != null || scriptTestResults != null) " ($passed/$total)" else ""
                        listOf("Body", "Headers (${resp.headers.size})", "Timeline Metrics", "Tests$suffix")
                    } else {
                        listOf("Body", "Headers (${resp.headers.size})", "Timeline Metrics")
                    }
                    // Defensive clamp: if the request's assertions changed since responseTab was
                    // last set (e.g. navigated to a different saved request), avoid indexing past
                    // the now-shorter tab list.
                    if (responseTab >= responseTabTitles.size) responseTab = 0
                    TabRow(
                        selectedTabIndex = responseTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        responseTabTitles.forEachIndexed { idx, t ->
                            Tab(
                                selected = responseTab == idx,
                                onClick = { responseTab = idx },
                                text = { Text(t, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        when (responseTab) {
                            0 -> {
                                // Response Body
                                Column(modifier = Modifier.fillMaxSize()) {
                                    GlassCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        backgroundColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        LazyColumn(modifier = Modifier.padding(12.dp)) {
                                            item {
                                                Text(
                                                    text = resp.body,
                                                    color = if (resp.isSuccess) MaterialTheme.colorScheme.secondary else Color(0xFFEF4444),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Button(
                                            onClick = {
                                                responseData = resp.copy(body = ToolsRepository.formatJson(resp.body))
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Text("✨ Beautify", color = TextPrimary, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            1 -> {
                                // Response Headers
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(resp.headers.entries.toList()) { (k, v) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("$k: ", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                            Text(v, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                            2 -> {
                                // Timeline Metrics
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TimelineMetricBar("DNS Resolution", "${resp.dnsTimeMs} ms", 0.15f, Color(0xFF3B82F6))
                                    TimelineMetricBar("TCP Connect", "${resp.connectTimeMs} ms", 0.25f, Color(0xFF06B6D4))
                                    TimelineMetricBar("TLS Handshake", "${resp.tlsTimeMs} ms", 0.40f, Color(0xFF8B5CF6))
                                    TimelineMetricBar("Request Sent", "${resp.requestTimeMs} ms", 0.10f, Color(0xFFF59E0B))
                                    TimelineMetricBar("Server Response", "${resp.responseTimeMs} ms", 0.60f, Color(0xFF22C55E))
                                }
                            }
                            3 -> {
                                // v3.4 Part C — C3: pass/fail per assertion, actual vs expected.
                                // Master plan §1.10/§1.11: also renders pm.test() results from the
                                // pre-/post-request scripts (scriptTestResults), plus any script
                                // execution error (scriptError), interleaved after the assertions.
                                val results = testResults
                                val scriptResults = scriptTestResults
                                if (results == null && scriptResults == null) {
                                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Tap \"Run Tests\" above to evaluate this request's assertions against this response.",
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (results != null) {
                                            item {
                                                val passedCount = results.count { it.passed }
                                                Text(
                                                    "$passedCount / ${results.size} assertions passed",
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (passedCount == results.size) Color(0xFF22C55E) else Color(0xFFEF4444),
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                            items(results) { result -> AssertionResultRow(result) }
                                        }
                                        if (scriptResults != null) {
                                            item {
                                                if (results != null) Spacer(modifier = Modifier.height(10.dp))
                                                val passedCount = scriptResults.count { it.passed }
                                                Text(
                                                    "$passedCount / ${scriptResults.size} pm.test() checks passed",
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (passedCount == scriptResults.size) Color(0xFF22C55E) else Color(0xFFEF4444),
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                            items(scriptResults) { result -> PmTestResultRow(result) }
                                        }
                                        if (scriptError != null) {
                                            item {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Text(
                                                    "Script error: $scriptError",
                                                    color = Color(0xFFEF4444),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // v6.0 lane B (manifest §api-testr-error/timeout): dedicated error or
                    // timeout card with a RetryActionPill, shown after the honest body/
                    // headers/timeline content so the raw response is never hidden.
                    if (isServerError || isTimeout || isNetworkError) {
                        val cardTitle: String
                        val cardMessage: String
                        val cardIcon: androidx.compose.ui.graphics.vector.ImageVector
                        when {
                            isTimeout -> {
                                cardTitle = "Connection timed out"
                                cardMessage = "The request exceeded the timeout. Check your network connection and server availability, then retry."
                                cardIcon = Icons.Default.Schedule
                            }
                            isServerError -> {
                                cardTitle = "Server returned ${resp.statusCode}"
                                cardMessage = "The server encountered an internal error. Check the endpoint or try again later."
                                cardIcon = Icons.Default.Warning
                            }
                            else -> {
                                cardTitle = "Network failure"
                                cardMessage = resp.error ?: "Failed to reach the server. Check your network connection and retry."
                                cardIcon = Icons.Default.Warning
                            }
                        }
                        ErrorStateCard(
                            title = cardTitle,
                            message = cardMessage,
                            icon = cardIcon,
                            color = errorColor
                        )
                        RetryActionPill(onRetry = { sendCurrentRequest() })
                    }
                }
            }
        }
    }

    // Modal: Code Snippets (Python, JS, Kotlin, Swift, cURL, Go)
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
                    Text("Code Snippet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    IconButton(onClick = { showSnippetModal = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
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
                            Text(lang, color = if (selectedSnippetLang == lang) Color.White else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    backgroundColor = Color(0xFF070B14)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(snippetCode, color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { showSnippetModal = false },
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
                Text("Import cURL Command", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = curlInput,
                    onValueChange = { curlInput = it },
                    placeholder = { Text("Paste cURL here...", color = TextMuted) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp)
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
                    Text("Import & Load", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Dropdown: Right Menu
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
            text = { Text("📋 View Saved") },
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
            text = { Text("🧹 Clear Fields") },
            onClick = {
                reqState = ApiRequestItem(name = "New Request", url = "https://httpbin.org/get")
                showMoreMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("📁 Export cURL as File") },
            onClick = {
                showMoreMenu = false
                // Build curl string from the current request state (Fix #14 audit)
                val curl = buildString {
                    append("curl -X ").append(reqState.method.name)
                    append(" '").append(reqState.url).append("'")
                    reqState.headers.filter { it.key.isNotBlank() }.forEach { h ->
                        append(" -H '").append(h.key).append(": ").append(h.value).append("'")
                    }
                    if (reqState.bodyContent.isNotBlank() && reqState.bodyType != com.example.data.models.RequestBodyType.NONE) {
                        val escaped = reqState.bodyContent.replace("'", "'\\''")
                        append(" --data '").append(escaped).append("'")
                    }
                }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        val target = com.example.data.export.FileExporter.writeCurl(appContext, curl)
                            ?: error("Failed to write cURL export to Downloads/requestr/")
                        com.example.data.export.FileExporter.shareFile(
                            appContext, target,
                            "application/x-shellscript",
                            "cURL export"
                        )
                    }.onFailure { e ->
                        android.util.Log.w("ApiTestr", "Export cURL failed: ${e.message}")
                    }
                }
            }
        )
    }

    // v6.0 transplant (manifest §5.5): snackbar offset above SendFloatingBar
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = (settings.fabBottomSpacingDp + 54).dp)
    ) { data ->
        Snackbar(snackbarData = data)
    }

    // v6.0 transplant (manifest §5.5): SendFloatingBar replaces inline send button
    SendFloatingBar(
        isLoading = isLoading,
        isWs = reqState.method == HttpMethod.WS,
        wsStatus = wsStatus,
        modifier = Modifier.align(Alignment.BottomCenter),
        onSend = { sendCurrentRequest() }
    )

    // v6.0 lane B (manifest §api-testr-ws): Disconnect WebSocket confirmation dialog.
    // The SendFloatingBar reads "Disconnect WS" (red) when a session is live; tapping
    // it sets showDisconnectWsDialog so the user can cancel before the socket closes.
    if (showDisconnectWsDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDisconnectWsDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Disconnect WebSocket?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "The active WebSocket session to ${reqState.url.take(40)}${if (reqState.url.length > 40) "…" else ""} will be closed. Pending messages will be lost.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wsSession?.close()
                        wsSession = null
                        showDisconnectWsDialog = false
                    }
                ) {
                    Text("Disconnect", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectWsDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
    }
}

@Composable
fun KeyValueEditorTable(
    title: String,
    items: List<KeyValueItem>,
    onItemsChanged: (List<KeyValueItem>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Button(
                onClick = {
                    onItemsChanged(items + KeyValueItem())
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = TextPrimary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Row", fontSize = 11.sp, color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.isEnabled,
                        onCheckedChange = { enabled ->
                            onItemsChanged(items.map { if (it.id == item.id) it.copy(isEnabled = enabled) else it })
                        },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )

                    OutlinedTextField(
                        value = item.key,
                        onValueChange = { k ->
                            onItemsChanged(items.map { if (it.id == item.id) it.copy(key = k) else it })
                        },
                        placeholder = { Text("Key", fontSize = 11.sp, color = TextMuted) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedTextField(
                        value = item.value,
                        onValueChange = { v ->
                            onItemsChanged(items.map { if (it.id == item.id) it.copy(value = v) else it })
                        },
                        placeholder = { Text("Value", fontSize = 11.sp, color = TextMuted) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = { onItemsChanged(items.filter { it.id != item.id }) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ChainExtractionEditor(
    rules: List<ChainExtractionRule>,
    onRulesChanged: (List<ChainExtractionRule>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Response Chaining", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "Extract a field from this request's response into a named variable, then reference it as {{name}} in another request's URL, headers, or body.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                onRulesChanged(rules + ChainExtractionRule())
            },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = TextPrimary)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Extraction Rule", fontSize = 11.sp, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (rules.isEmpty()) {
            Text(
                "No extraction rules yet. Add one to pull a value out of this request's JSON response body (e.g. path \"token\") and store it as a variable (e.g. \"authToken\") on the active Environment.",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rules) { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        placeholder = { Text("JSON field (e.g. token)", fontSize = 11.sp, color = TextMuted) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
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
                        placeholder = { Text("Variable name (e.g. authToken)", fontSize = 11.sp, color = TextMuted) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = { onRulesChanged(rules.filter { it.id != rule.id }) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * v3.4 Part C — C3 (Test assertions / test runner): editor for the [Assertion]s attached to a
 * saved request. Mirrors [ChainExtractionEditor]'s structure (header, "+ Add" button, empty
 * state, LazyColumn of rows with a checkbox + delete button) but each row needs more fields
 * than a chain-extraction rule: a target dropdown, a conditionally-shown target-key field
 * (header name / JSON field — hidden for STATUS_CODE and RESPONSE_TIME_MS, which have nothing
 * to name), an operator dropdown, and the expected-value field.
 */
@Composable
fun AssertionEditor(
    assertions: List<Assertion>,
    onAssertionsChanged: (List<Assertion>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column {
            Text("Test Assertions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "Attach checks to this request. After Send (or via \"Run Tests\" in the Response view), each assertion is evaluated against the real response and shown as pass/fail with actual vs. expected values.",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { onAssertionsChanged(assertions + Assertion()) },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = TextPrimary)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Assertion", fontSize = 11.sp, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (assertions.isEmpty()) {
            Text(
                "No assertions yet. Add one to check, for example, that the status code equals 200, a header contains a value, a JSON field equals an expected value, or the response time is less than a threshold.",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(assertions) { assertion ->
                fun update(transform: (Assertion) -> Assertion) {
                    onAssertionsChanged(assertions.map { if (it.id == assertion.id) transform(it) else it })
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(8.dp)
                ) {
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
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(assertion.target.name.replace('_', ' '), fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
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
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(assertion.operator.name.replace('_', ' '), fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
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
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // targetKey only makes sense for HEADER_VALUE / BODY_JSON_FIELD — status
                        // code and response time are single scalar values with nothing to name.
                        if (assertion.target == AssertionTarget.HEADER_VALUE || assertion.target == AssertionTarget.BODY_JSON_FIELD) {
                            OutlinedTextField(
                                value = assertion.targetKey,
                                onValueChange = { k -> update { it.copy(targetKey = k) } },
                                placeholder = {
                                    Text(
                                        if (assertion.target == AssertionTarget.HEADER_VALUE) "Header name (e.g. content-type)" else "JSON field (e.g. token)",
                                        fontSize = 11.sp, color = TextMuted
                                    )
                                },
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        OutlinedTextField(
                            value = assertion.expectedValue,
                            onValueChange = { v -> update { it.copy(expectedValue = v) } },
                            placeholder = { Text("Expected value", fontSize = 11.sp, color = TextMuted) },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
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

/**
 * v3.4 Part C — C3: one row of the "Run Tests" result list — the assertion's description,
 * a pass/fail indicator, and (when failed) the human-readable actual-vs-expected message
 * from [AssertionResult.message].
 */
@Composable
fun AssertionResultRow(result: AssertionResult) {
    val a = result.assertion
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
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
                "$label ${a.operator.name.lowercase().replace('_', ' ')} \"${a.expectedValue}\"",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                if (result.passed) "Actual: \"${result.actualValue}\"" else result.message,
                fontSize = 11.sp,
                color = if (result.passed) TextSecondary else Color(0xFFEF4444)
            )
        }
    }
}

/**
 * Master plan §1.10/§1.11: one row of a `pm.test(name, fn)` result recorded by a pre-/
 * post-request script, analogous to [AssertionResultRow] but for [com.example.scripting.PmTestResult]
 * (the script engine's own record type, distinct from the declarative [AssertionResult]).
 */
@Composable
fun PmTestResultRow(result: com.example.scripting.PmTestResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
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
            Text(
                result.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (result.message.isNotBlank()) {
                Text(
                    result.message,
                    fontSize = 11.sp,
                    color = if (result.passed) TextSecondary else Color(0xFFEF4444)
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
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Text(time, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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

// ─── Raw Text Editor for Query/Headers ───────────────────────────────

@Composable
private fun RawEditorToggleRow(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (expanded) "Raw Editor — visible below" else "Raw Editor", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (expanded) "Hide" else "Show", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onToggle() })
    }
}

@Composable
private fun RawTextEditor(
    items: List<KeyValueItem>,
    onItemsChanged: (List<KeyValueItem>) -> Unit
) {
    var rawText by remember(items) {
        mutableStateOf(items.joinToString("\n") { if (it.isEnabled) "${it.key}: ${it.value}" else "// ${it.key}: ${it.value}" })
    }

    Text(
        "One per line. Prefix // to disable. Two-way sync with table above.",
        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(
        value = rawText,
        onValueChange = { newText ->
            rawText = newText
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
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().height(120.dp)
    )
}

// ====================================================================
// v6.0 transplant (manifest §5.5, Lane B) — ported from main's ApiTestrScreen
// ====================================================================

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

@Composable
private fun TitleAndUrlCard(
    reqState: ApiRequestItem,
    followRedirects: Boolean,
    wsStatus: WsConnectionStatus? = null,
    onTitleChanged: (String) -> Unit,
    onMethodChanged: (HttpMethod) -> Unit,
    onUrlChanged: (String) -> Unit,
    onToggleRedirects: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var isEditingTitle by remember { mutableStateOf(false) }
    var showMethodMenu by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val methodColor = getMethodColor(reqState.method)

    val menuArrowRotation by animateFloatAsState(
        targetValue = if (showMethodMenu) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "menuArrowRotation"
    )

    val rocketTilt by animateFloatAsState(
        targetValue = if (followRedirects) -18f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "rocketTilt"
    )

    val rocketScale by animateFloatAsState(
        targetValue = if (followRedirects) 1.15f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rocketScale"
    )

    val rocketBg by animateColorAsState(
        targetValue = if (followRedirects) primaryColor.copy(alpha = if (isDark) 0.22f else 0.15f) else Color.Transparent,
        animationSpec = tween(200),
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

                // v6.0 lane B (manifest §api-testr-ws): CONNECTED badge with green dot
                // shown inside the URL card when a WebSocket session is live.
                if (wsStatus == WsConnectionStatus.CONNECTED) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CONNECTED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
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

@Composable
private fun SendFloatingBar(
    isLoading: Boolean,
    isWs: Boolean,
    wsStatus: WsConnectionStatus,
    modifier: Modifier = Modifier,
    onSend: () -> Unit
) {
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
// v6.0 transplant (manifest §5.5): Enhanced key-value row with animated
// checkbox, colored monospace fields, zero-lag local state, delete button.
// ====================================================================

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

    var localKey by remember(item.id) { mutableStateOf(item.key) }
    var localValue by remember(item.id) { mutableStateOf(item.value) }

    LaunchedEffect(item.key) {
        if (localKey != item.key) localKey = item.key
    }
    LaunchedEffect(item.value) {
        if (localValue != item.value) localValue = item.value
    }

    val checkboxScale by animateFloatAsState(
        targetValue = if (item.isEnabled) 1.0f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "checkboxScale"
    )

    val checkboxBg by animateColorAsState(
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
// v6.0 transplant (manifest §5.5): HTTP Request Body tab with Surface
// styling, content-type selector pills, beautify button.
// ====================================================================

@Composable
private fun HttpRequestBodyTab(
    bodyType: RequestBodyType,
    bodyContent: String,
    // Master plan §2.9 — key/value pairs sent as real FormBody/MultipartBody parts for
    // RequestBodyType.FORM / RequestBodyType.MULTIPART, instead of the free-text bodyContent
    // editor used by every other body type.
    formData: List<KeyValueItem>,
    isDark: Boolean,
    onBodyTypeChanged: (RequestBodyType) -> Unit,
    onBodyContentChanged: (String) -> Unit,
    onFormDataChanged: (List<KeyValueItem>) -> Unit,
    onBeautify: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxSize()) {
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
        } else if (bodyType == RequestBodyType.FORM || bodyType == RequestBodyType.MULTIPART) {
            // Master plan §2.9 — reuse the same KeyValueEditorTable pattern already used for
            // query params / headers, rather than inventing a new UI language for form fields.
            KeyValueEditorTable(
                title = if (bodyType == RequestBodyType.MULTIPART) "Form Data (multipart/form-data)" else "Form Data (x-www-form-urlencoded)",
                items = formData,
                onItemsChanged = onFormDataChanged
            )
        } else {
            // Body Editor Surface
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF060911) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

// ====================================================================
// v6.0 transplant (manifest §5.5): Auth settings tab with styled fields.
// ====================================================================

@Composable
private fun AuthSettingsTab(
    authType: AuthType,
    bearerToken: String,
    basicUsername: String,
    basicPassword: String,
    apiKeyHeader: String,
    apiKeyValue: String,
    // Master plan §2.13 — where the API-key credential is placed on the wire.
    apiKeyLocation: ApiKeyLocation,
    onAuthTypeChanged: (AuthType) -> Unit,
    onBearerChanged: (String) -> Unit,
    onBasicUsernameChanged: (String) -> Unit,
    onBasicPasswordChanged: (String) -> Unit,
    onApiKeyHeaderChanged: (String) -> Unit,
    onApiKeyValueChanged: (String) -> Unit,
    onApiKeyLocationChanged: (ApiKeyLocation) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxSize()) {
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
                // Basic Auth polish: password now defaults to masked, with a show/hide toggle --
                // previously this field had no visualTransformation at all, so the password was
                // always rendered in plain text.
                var showBasicPassword by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = basicPassword,
                    onValueChange = onBasicPasswordChanged,
                    label = { Text("Password") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    visualTransformation = if (showBasicPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showBasicPassword = !showBasicPassword },
                            modifier = Modifier.testTag("toggleBasicPasswordVisibility")
                        ) {
                            Icon(
                                imageVector = if (showBasicPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showBasicPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("basicPasswordField"),
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
                Spacer(modifier = Modifier.height(10.dp))
                // Master plan §2.13 — Header vs Query Param placement toggle.
                Text("Send as", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiKeyLocation.values().forEach { loc ->
                        val isSel = apiKeyLocation == loc
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onApiKeyLocationChanged(loc) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (loc == ApiKeyLocation.HEADER) "Header" else "Query Param",
                                color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            else -> {
                Text("No authentication headers will be added to this request.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

// ====================================================================
// Master plan §2.8 — per-saved-request mock mode editor. When mockEnabled is
// on, ApiTestrRepository.executeRequest() short-circuits before dialing the
// network and returns these canned fields instead (see buildMockResponse()).
// ====================================================================

// Visibility note: was `private fun`. Widened to `internal` (not `public`) so the Robolectric
// Compose UI test added for the height-starvation fix below (MockModeEditorUiTest, in the
// app/src/test source set of this same Gradle module) can call this composable directly instead
// of re-implementing/duplicating its body in test code. `internal` keeps it out of any public
// API surface while still being visible across files within this module and its test source
// set (test compilation is a friend of the main compilation), matching the same rationale
// already used for `SourceLiteralEscape` in ApiTestrRepository.kt.
@Composable
internal fun MockModeEditor(
    mockEnabled: Boolean,
    mockStatusCode: Int,
    mockStatusMessage: String,
    mockHeaders: List<KeyValueItem>,
    mockBody: String,
    mockDelayMs: Long,
    isDark: Boolean,
    onMockEnabledChanged: (Boolean) -> Unit,
    onMockStatusCodeChanged: (Int) -> Unit,
    onMockStatusMessageChanged: (String) -> Unit,
    onMockHeadersChanged: (List<KeyValueItem>) -> Unit,
    onMockBodyChanged: (String) -> Unit,
    onMockDelayMsChanged: (Long) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    // Bug fix (device smoke test, Android 15) -- this was Modifier.fillMaxSize() with no scroll.
    // KeyValueEditorTable's internal LazyColumn has no height bound of its own, so inside a
    // Column(Modifier.fillMaxSize()) it always claims 100% of whatever height is left when it's
    // measured -- regardless of how few "Mock Headers" rows it actually holds. Every other
    // KeyValueEditorTable call site (query params, headers, form data) happens to place the
    // table last in its own Column (or as an unrelated Box sibling for query params/headers),
    // so nothing after it ever gets starved -- this is the one call site with more content
    // below the table ("Mock Body"), so it's the one place this latent behavior turned into a
    // real bug: "Mock Body" was silently given zero height and never rendered.
    // Fixed locally here (not inside KeyValueEditorTable) by (a) capping the "Mock Headers"
    // table's own height below so the LazyColumn has room to be small instead of huge, and (b)
    // making the whole editor scroll so "Mock Body" is reachable regardless of viewport size --
    // reproduced identically at 720x1600 and a resized 720x2600 in the report, so this isn't a
    // small-screen-only fix, it's this genuine unbounded-height claim.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mock Response", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "When enabled, Send returns these canned fields instead of dialing the network.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = mockEnabled,
                onCheckedChange = onMockEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = primaryColor
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = mockStatusCode.toString(),
                onValueChange = { onMockStatusCodeChanged(it.toIntOrNull() ?: 0) },
                label = { Text("Status Code") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                // testTag added for MockModeEditorUiTest (Robolectric Compose test) so the field
                // can be located deterministically instead of relying on fuzzy text matching.
                modifier = Modifier.weight(1f).testTag("mockStatusCodeField"),
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = mockStatusMessage,
                onValueChange = onMockStatusMessageChanged,
                label = { Text("Status Message") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).testTag("mockStatusMessageField"),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = mockDelayMs.toString(),
            onValueChange = { onMockDelayMsChanged(it.toLongOrNull() ?: 0L) },
            label = { Text("Simulated Delay (ms)") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().testTag("mockDelayMsField"),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // heightIn(max) here (not inside the shared KeyValueEditorTable) is what actually fixes
        // this call site: it gives KeyValueEditorTable's own Modifier.fillMaxSize() root a
        // bounded height to fill instead of the ambient-unbounded one this verticalScroll
        // Column's content-measurement pass would otherwise hand it (which a LazyColumn cannot
        // lay out against at all -- Compose requires a bounded max-height constraint for a
        // lazy list). A handful of mock header rows render at their natural height well under
        // this cap; a long list still scrolls, just within this bounded region instead of
        // devouring the rest of the editor the way it did before this fix.
        Box(modifier = Modifier.heightIn(max = 280.dp)) {
            KeyValueEditorTable(
                title = "Mock Headers",
                items = mockHeaders,
                onItemsChanged = onMockHeadersChanged
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text("Mock Body", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (isDark) Color(0xFF060911) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                BasicTextField(
                    value = mockBody,
                    onValueChange = onMockBodyChanged,
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
                        .testTag("mockBodyField")
                )
            }
        }
    }
}

// ====================================================================
// Master plan §1.10 — per-saved-request async polling editor. When
// pollingEnabled is on and a Send comes back 202, ApiTestrRepository
// .executeRequestWithScripts polls [pollingLocationHeader]'s URL every
// [pollingIntervalMs] up to [pollingMaxAttempts] times before returning.
// ====================================================================

@Composable
internal fun PollingEditor(
    pollingEnabled: Boolean,
    pollingIntervalMs: Long,
    pollingMaxAttempts: Int,
    pollingLocationHeader: String,
    onPollingEnabledChanged: (Boolean) -> Unit,
    onPollingIntervalMsChanged: (Long) -> Unit,
    onPollingMaxAttemptsChanged: (Int) -> Unit,
    onPollingLocationHeaderChanged: (String) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Async Polling", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "When Send returns 202, follow the location header until it stops returning 202.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = pollingEnabled,
                onCheckedChange = onPollingEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = primaryColor
                ),
                modifier = Modifier.testTag("pollingEnabledSwitch")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = pollingLocationHeader,
            onValueChange = onPollingLocationHeaderChanged,
            label = { Text("Location Header") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().testTag("pollingLocationHeaderField"),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = pollingIntervalMs.toString(),
                onValueChange = { onPollingIntervalMsChanged(it.toLongOrNull() ?: 0L) },
                label = { Text("Interval (ms)") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).testTag("pollingIntervalMsField"),
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = pollingMaxAttempts.toString(),
                onValueChange = { onPollingMaxAttemptsChanged(it.toIntOrNull() ?: 0) },
                label = { Text("Max Attempts") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).testTag("pollingMaxAttemptsField"),
                shape = RoundedCornerShape(10.dp)
            )
        }
    }
}

// ====================================================================
// v6.0 transplant (manifest §5.5): Scripts tab with styled editor.
// ====================================================================

@Composable
private fun ScriptsTabContent(
    preScript: String,
    onPreScriptChanged: (String) -> Unit,
    postScript: String,
    onPostScriptChanged: (String) -> Unit
) {
    // Master plan §1.10: both fields now run against a real Rhino `pm.*` engine
    // (see ApiTestrScriptRunner / JsScriptEngine / PmPrelude) -- pre-request runs before the
    // request is sent (and before {{var}} substitution), post-response runs after the response
    // (and any 202-polling) completes, with `pm.test(name, fn)` results shown in the Tests tab.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Pre-request Script", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = preScript,
            onValueChange = onPreScriptChanged,
            placeholder = { Text("// pm.environment.set('token', pm.request.headers.get('X-Api-Key'))", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .testTag("preScriptField"),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Post-response Script", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = postScript,
            onValueChange = onPostScriptChanged,
            placeholder = { Text("// pm.test('status is 200', function () { pm.expect(pm.response.statusCode).to.equal(200) })", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .testTag("postScriptField"),
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

// ====================================================================
// v6.0 transplant (manifest §5.5): WebSocket message composer tab.
// ====================================================================

@Composable
private fun WsStreamComposerTab(
    draftMessage: String,
    wsStatus: WsConnectionStatus,
    isDark: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("WebSocket Frame Payload", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (isDark) Color(0xFF060911) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
// v6.0 transplant (manifest §5.5): WebSocket live console with message
// stream, status indicator, error display, draft input, and clear.
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
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
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    // v6.0 lane B (manifest §api-testr-ws): synthetic SYSTEM frame at the
                    // top of the console when connected — the session model has no system
                    // message type, so this is a UI-only banner matching the mockup.
                    if (wsStatus == WsConnectionStatus.CONNECTED) {
                        item(key = "sys-connection") {
                            WsMessageRow(
                                tag = "SYSTEM",
                                tagColor = Color(0xFF10B981),
                                bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                bodyItalic = true,
                                timestamp = null,
                                body = "WebSocket connection established · handshake complete"
                            )
                        }
                    }
                    items(wsMessages, key = { it.id }) { msg ->
                        val isOut = !msg.isIncoming
                        if (isOut) {
                            WsMessageRow(
                                tag = "SEND",
                                tagColor = Color(0xFF818CF8),
                                bodyColor = Color(0xFFC7D2FE),
                                bodyItalic = false,
                                timestamp = timeFormat.format(java.util.Date(msg.timestamp)),
                                body = msg.text
                            )
                        } else {
                            WsMessageRow(
                                tag = "RECV",
                                tagColor = Color(0xFF06B6D4),
                                bodyColor = Color(0xFF67E8F9),
                                bodyItalic = false,
                                timestamp = timeFormat.format(java.util.Date(msg.timestamp)),
                                body = msg.text
                            )
                        }
                    }
                }
            }
        }

        // v6.0 lane B (manifest §api-testr-ws): Composer bar — rounded surface input
        // with mail icon and inline send, matching the mockup's WsComposerBar.
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            BasicTextField(
                value = wsDraftMessage,
                onValueChange = onDraftChanged,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (wsDraftMessage.isEmpty()) {
                            Text(
                                "Type a message to send...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank()) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (wsStatus == WsConnectionStatus.CONNECTED && wsDraftMessage.isNotBlank())
                        MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

// ====================================================================
// v6.0 transplant (manifest §5.5): Live JSON preview with syntax
// highlighting, toolbar actions, and expandable raw text editor.
// ====================================================================

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
private fun highlightJsonLine(line: String): AnnotatedString {
    return buildAnnotatedString {
        val parts = line.split(":", limit = 2)
        if (parts.size == 2 && parts[0].trim().startsWith("\"")) {
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
            // Value in green
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
        border = BorderStroke(1.dp, Color(0xFF1E2838))
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
                    CodeToolbarAction(
                        icon = Icons.Default.AutoAwesome,
                        description = "Beautify JSON",
                        onClick = onBeautify
                    )
                    CodeToolbarAction(
                        icon = Icons.Default.ContentCopy,
                        description = "Copy as JSON",
                        onClick = onCopyJson
                    )
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

// ====================================================================
// v6.0 lane B (manifest §api-testr-response/error/timeout/ws): lane-local
// helper composables for the redesigned response viewer and WS console.
// ====================================================================

/** Formats a byte count as a human-readable string (e.g. "1.0 KB", "842 B"). */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    return if (kb < 1024) String.format("%.1f KB", kb) else String.format("%.1f MB", kb / 1024.0)
}

/**
 * Compact timing tile (manifest §api-testr-response): radius 12dp, flat bg, border,
 * label 9sp uppercase + value 16sp bold monospace. When [muted] the tile dims to
 * half opacity with muted text — used for the timeout state where no timing exists.
 */
@Composable
private fun TimingTile(label: String, value: String, muted: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 9.dp, vertical = 8.dp)
                .graphicsLayer { alpha = if (muted) 0.5f else 1f },
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (muted) TextMuted else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (muted) TextMuted else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Error/timeout state card (manifest §api-testr-error/timeout): tinted bg, border,
 * warning/clock icon + title + message. Supplemental to the honest raw body display.
 */
@Composable
private fun ErrorStateCard(
    title: String,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Retry action pill (manifest §api-testr-error/timeout): radius 20dp, surface bg,
 * primary-bright border/text, refresh icon, shadow. Re-sends the current request.
 */
@Composable
private fun RetryActionPill(onRetry: () -> Unit) {
    val primaryBright = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onRetry,
        modifier = Modifier
            .padding(top = 14.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, primaryBright.copy(alpha = 0.5f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry",
                tint = primaryBright,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = "Retry Request",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = primaryBright
            )
        }
    }
}

/**
 * Single WebSocket message row (manifest §api-testr-ws): tagged frame with RECV/SEND/
 * SYSTEM label, optional timestamp, and monospace body in the tag's accent color.
 */
@Composable
private fun WsMessageRow(
    tag: String,
    tagColor: Color,
    bodyColor: Color,
    bodyItalic: Boolean,
    timestamp: String?,
    body: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tagColor.copy(alpha = 0.16f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = tag,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = tagColor
                )
            }
            if (timestamp != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timestamp,
                    fontSize = 9.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = body,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = bodyColor,
            fontStyle = if (bodyItalic) FontStyle.Italic else FontStyle.Normal
        )
    }
}
