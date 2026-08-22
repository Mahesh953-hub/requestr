package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.example.data.repository.ToolsRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusCodeBadge
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
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
    var wsConnected by remember { mutableStateOf(false) }

    // v3.4 Part C — C3 (Test assertions / test runner): results of the last "Run Tests" pass
    // against `responseData`. Cleared whenever a fresh Send/Run happens so stale pass/fail
    // results are never shown next to a response they didn't evaluate.
    var testResults by remember { mutableStateOf<List<AssertionResult>?>(null) }

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

    // Task 5(c): send/new-request keyboard shortcuts. Previously MainActivity's key listener
    // called `sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))` / `.../NEW_REQUEST`
    // with no registered receiver anywhere, so Ctrl+Enter/Ctrl+Shift+N were silent no-ops.
    // com.example.AppShortcuts is an in-process SharedFlow this screen collects directly.
    fun sendCurrentRequest() {
        isLoading = true
        coroutineScope.launch {
            // v3.2 Part C — C2 (Request chaining): substitute {{variableName}}
            // in URL, headers, and body — not just the URL as before — so a
            // value extracted from an earlier chained request's response can
            // be referenced anywhere in this request, per the plan's spec.
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
            // v3.4 Part C — C3: clear any previous run's pass/fail results —
            // they belong to a different response, not this one.
            testResults = null
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

        // Request URL & Method Bar
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            backgroundColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Request Name inline editor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = reqState.name,
                        onValueChange = { reqState = reqState.copy(name = it) },
                        textStyle = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = { sendCurrentRequest() },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else if (reqState.method == HttpMethod.WS && wsConnected) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Disconnect", fontWeight = FontWeight.Bold)
                        } else if (reqState.method == HttpMethod.WS) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Connect", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Send", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Method + URL Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Method Dropdown Pill
                    Box {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showMethodDropdown = true }
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            MethodBadge(method = reqState.method, fontSize = 12)
                        }

                        // WS connection status indicator
                        if (reqState.method == HttpMethod.WS) {
                            val (wsLabel, wsColor) = when {
                                wsConnected -> "Connected" to Color(0xFF10B981)
                                isLoading -> "Connecting..." to Color(0xFFF59E0B)
                                else -> "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(wsLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = wsColor,
                                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp))
                                    .background(wsColor.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 1.dp))
                        }

                        DropdownMenu(
                            expanded = showMethodDropdown,
                            onDismissRequest = { showMethodDropdown = false }
                        ) {
                            HttpMethod.values().forEach { m ->
                                DropdownMenuItem(
                                    text = { MethodBadge(method = m) },
                                    onClick = {
                                        reqState = reqState.copy(method = m)
                                        showMethodDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // URL Input
                    OutlinedTextField(
                        value = reqState.url,
                        onValueChange = { reqState = reqState.copy(url = it) },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextPrimary
                        ),
                        placeholder = { Text("https://api.example.com/v1/endpoint", color = TextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Follow Redirects toggle — Fix 09
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Follow redirects", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(6.dp))
            androidx.compose.material3.Switch(
                checked = followRedirects,
                onCheckedChange = { followRedirects = it },
                modifier = Modifier.height(24.dp)
            )
        }

        // View Mode Segmented Bar — Fix 09: clean labels, no counts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { selectedViewMode = 0 },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedViewMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
            ) {
                Text("Request", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selectedViewMode == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = { selectedViewMode = 1 },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedViewMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
            ) {
                Text("Response", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selectedViewMode == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Main View Content
        if (selectedViewMode == 0) {
            // ==========================================
            // REQUEST EDITOR VIEW
            // ==========================================
            val reqTabs = listOf(
                "Query (${reqState.queryParams.count { it.isEnabled }})",
                "Headers (${reqState.headers.count { it.isEnabled }})",
                "Body",
                "Auth",
                "Scripts",
                "Chain (${reqState.chainExtractions.count { it.enabled }})",
                "Tests (${reqState.assertions.count { it.enabled }})",
            )

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
                        // Request Body
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RequestBodyType.values().forEach { bt ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (reqState.bodyType == bt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { reqState = reqState.copy(bodyType = bt) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(bt.name, color = if (reqState.bodyType == bt) Color.White else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (reqState.bodyType == RequestBodyType.NONE) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("This request does not have a body", color = TextMuted)
                                }
                            } else {
                                OutlinedTextField(
                                    value = reqState.bodyContent,
                                    onValueChange = { reqState = reqState.copy(bodyContent = it) },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            reqState = reqState.copy(bodyContent = ToolsRepository.formatJson(reqState.bodyContent))
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Text("✨ Beautify JSON", color = TextPrimary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // Auth Tab
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text("Authorization Type:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AuthType.values().forEach { at ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (reqState.authType == at) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { reqState = reqState.copy(authType = at) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(at.name, color = if (reqState.authType == at) Color.White else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            when (reqState.authType) {
                                AuthType.BEARER -> {
                                    OutlinedTextField(
                                        value = reqState.bearerToken,
                                        onValueChange = { reqState = reqState.copy(bearerToken = it) },
                                        label = { Text("Bearer Token") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                AuthType.BASIC -> {
                                    OutlinedTextField(
                                        value = reqState.basicUsername,
                                        onValueChange = { reqState = reqState.copy(basicUsername = it) },
                                        label = { Text("Username") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = reqState.basicPassword,
                                        onValueChange = { reqState = reqState.copy(basicPassword = it) },
                                        label = { Text("Password") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                AuthType.API_KEY -> {
                                    OutlinedTextField(
                                        value = reqState.apiKeyHeader,
                                        onValueChange = { reqState = reqState.copy(apiKeyHeader = it) },
                                        label = { Text("Header Key (e.g. X-API-Key)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = reqState.apiKeyValue,
                                        onValueChange = { reqState = reqState.copy(apiKeyValue = it) },
                                        label = { Text("API Key Value") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                else -> {
                                    Text("No authentication configured for this request.", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    4 -> {
                        // Scripts
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text("Pre-request & Post-response JS Sandbox:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = reqState.preScript,
                                onValueChange = { reqState = reqState.copy(preScript = it) },
                                label = { Text("Pre-request Script") },
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
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
                }
            }
        } else {
            // ==========================================
            // RESPONSE VIEW
            // ==========================================
            if (responseData == null) {
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Status Badge Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusCodeBadge(statusCode = resp.statusCode, statusMessage = resp.statusMessage)
                            Spacer(modifier = Modifier.width(8.dp))
                            ProtocolBadge(protocol = resp.protocol)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${resp.latencyMs} ms", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${resp.sizeBytes} B", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
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
                    val responseTabTitles = if (reqState.assertions.any { it.enabled }) {
                        listOf("Body", "Headers (${resp.headers.size})", "Timeline Metrics", "Tests${testResults?.let { " (${it.count { r -> r.passed }}/${it.size})" } ?: ""}")
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
                                val results = testResults
                                if (results == null) {
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
                                }
                            }
                        }
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

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    ) { data ->
        Snackbar(snackbarData = data)
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
