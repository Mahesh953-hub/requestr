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
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.ApiTestrRepository
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
    val currentRequest by ApiCollectionsRepository.currentActiveRequest.collectAsState()
    val allRequests by ApiCollectionsRepository.requests.collectAsState()
    val folders by ApiCollectionsRepository.folders.collectAsState()

    var reqState by remember(currentRequest) { mutableStateOf(currentRequest) }
    var responseData by remember { mutableStateOf<ApiResponseData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var selectedViewMode by remember { mutableIntStateOf(0) } // 0: Request, 1: Response
    var followRedirects by remember { mutableStateOf(true) }
    var requestTab by remember { mutableIntStateOf(0) } // 0: Query, 1: Headers, 2: Body, 3: Auth, 4: Scripts
    var responseTab by remember { mutableIntStateOf(0) } // 0: Body, 1: Headers, 2: Metrics

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
                        onClick = {
                            isLoading = true
                            coroutineScope.launch {
                                val env = com.example.data.env.EnvironmentRepository.active()
                                val urlWithEnv = com.example.data.env.EnvironmentRepository.substitute(reqState.url, env)
                                val substitutedState = reqState.copy(url = urlWithEnv)
                                val resp = ApiTestrRepository.executeRequest(substitutedState, followRedirects)
                                responseData = resp
                                isLoading = false
                                selectedViewMode = 1 // auto toggle to response
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
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
            val reqTabs = listOf("Query (${reqState.queryParams.count { it.isEnabled }})", "Headers (${reqState.headers.count { it.isEnabled }})", "Body", "Auth", "Scripts")

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
                        // Query Params Table
                        KeyValueEditorTable(
                            title = "Query Parameters",
                            items = reqState.queryParams,
                            onItemsChanged = { reqState = reqState.copy(queryParams = it) }
                        )
                    }
                    1 -> {
                        // Headers Table
                        KeyValueEditorTable(
                            title = "Request Headers",
                            items = reqState.headers,
                            onItemsChanged = { reqState = reqState.copy(headers = it) }
                        )
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Response Subtabs (Body, Headers, Metrics)
                    TabRow(
                        selectedTabIndex = responseTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        listOf("Body", "Headers (${resp.headers.size})", "Timeline Metrics").forEachIndexed { idx, t ->
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
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = TextMuted)
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
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
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
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextPrimary)
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
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
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
