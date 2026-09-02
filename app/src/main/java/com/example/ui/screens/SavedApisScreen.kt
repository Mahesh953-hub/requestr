package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.export.PostmanCollectionCodec
import com.example.data.models.ApiRequestItem
import com.example.data.models.CollectionTestReport
import com.example.data.models.HttpMethod
import com.example.data.models.RequestTestResult
import com.example.data.repository.RelativeTimeFormatter
import com.example.data.repository.ApiCollectionsRepository
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.MethodBadge
import com.example.ui.theme.animateStateColor
import com.example.ui.components.FloatingCapsuleHeader
import com.example.ui.components.GlassActionCluster
import kotlinx.coroutines.launch

/**
 * Saved & Liked APIs browser (Fix 09). Two tabs at top:
 * "Saved" — all saved requests; "Liked" — favorites only.
 * Tapping a card opens the request in API Testr via callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedApisScreen(
    onBack: () -> Unit,
    onOpenRequest: (ApiRequestItem) -> Unit
) {
    val allRequests by ApiCollectionsRepository.requests.collectAsState()
    val allFolders by ApiCollectionsRepository.folders.collectAsState()
    var showSaved by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // v3.4 Part C — C3 (Test assertions / test runner): "test runner mode" — run every saved
    // request's assertions in sequence (folderId = null covers the whole collection; there is
    // no folder-picker UI yet, so "run all" is the scope this screen exposes) and show a summary
    // report. null = not running / no report yet.
    var isRunningTests by remember { mutableStateOf(false) }
    var testReport by remember { mutableStateOf<CollectionTestReport?>(null) }
    val testableCount = remember(allRequests) { allRequests.count { r -> r.assertions.any { it.enabled } } }
    // Master plan §2.12 — opt-in halt-on-failure for the collection runner; defaults to off so
    // "Run All Tests" behaves exactly as before unless the user turns this on.
    var haltOnFailure by remember { mutableStateOf(false) }

    // Master plan §1.6 — Postman Collection v2.1 export/import, following the same
    // MediaStoreExporter/FileExporter share pattern SettingsScreen's workspace export uses.
    val postmanImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (json == null) {
                android.widget.Toast.makeText(context, "Could not read the selected file.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = PostmanCollectionCodec.import(json)
            // Master plan §1.6 -- imported requests already carry a folderId pointing at one of
            // result.folders; that folder must exist in the repository BEFORE the requests are
            // saved, or every one of those folderId values is a dangling reference to a folder
            // that was only ever mentioned in this toast, never actually created.
            ApiCollectionsRepository.addFolders(result.folders)
            result.requests.forEach { ApiCollectionsRepository.saveRequest(it) }
            val message = "Imported ${result.requests.size} request(s)" +
                (if (result.folders.isNotEmpty()) " in ${result.folders.size} folder(s)" else "") +
                (if (result.errors.isNotEmpty()) " (${result.errors.size} items skipped)" else "")
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Order is not set here: groupRequestsIntoCollections sorts each collection's requests by
    // updatedAt descending as part of its contract, and nothing else reads this list's order.
    // (Sorting outside the remember also produced a fresh list on every recomposition, which
    // invalidated the grouping's remember key below every time.)
    val filtered = remember(allRequests, showSaved) {
        if (showSaved) allRequests
        else allRequests.filter { it.isFavorite }
    }

    // Dossier Q3 — collections-grouped card list (docs/designs/saved_apis/*.html) instead of the
    // old flat list. Pure grouping/counting/sorting lives in SavedApisGrouping.kt so it can be
    // unit tested without Compose; this screen only owns per-card expand/collapse UI state.
    val collectionGroups = remember(filtered, allFolders) {
        groupRequestsIntoCollections(filtered, allFolders)
    }
    // Collapsed-state set rather than expanded-state: a freshly created folder (not yet in this
    // set either way) defaults to expanded, matching the pre-restructure flat list where every
    // request was always visible.
    var collapsedCollectionIds by remember { mutableStateOf(setOf<String>()) }

    // The collection list is the screen's scroll container: bind it to the parallax layer.
    val listState = rememberLazyListState()
    DriveParallaxFrom(listState)

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Column(modifier = Modifier.fillMaxSize()) {
        // Header — floating glass capsules (DESIGN-SYSTEM §9.3 / §14.7, shared-context §6).
        // docs/designs/saved_apis/ draws one `.header-capsule` bar around the controls; the
        // global rule that header shapes are independent floating capsules over a transparent
        // page wins, so the back capsule, identity capsule and the export/import/run cluster
        // float separately here. Title/actions are the screen's real ones (no "New Folder"
        // action exists in this screen, so the mockup's accent button is not fabricated).
        FloatingCapsuleHeader(
            title = "Saved APIs",
            icon = Icons.Default.Bookmark,
            onBack = onBack,
            trailingContent = {
                GlassActionCluster {
                    // Master plan §1.6 — Postman Collection v2.1 export/import. Export always available
                    // (an empty collection is still a valid, if pointless, export); import is always
                    // available too since it starts from zero saved requests.
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                runCatching {
                                    val json = PostmanCollectionCodec.export(
                                        collectionName = "requestr export",
                                        requests = allRequests,
                                        folders = allFolders,
                                    )
                                    val target = com.example.data.export.MediaStoreExporter.writeText(
                                        context,
                                        "requestr-collection-${System.currentTimeMillis()}.postman_collection.json",
                                        "application/json",
                                        json,
                                    ) ?: error("Failed to write Postman collection JSON")
                                    com.example.data.export.FileExporter.shareFile(
                                        context, target, "application/json", "requestr Postman Collection"
                                    )
                                }.onFailure { e ->
                                    android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Upload, "Export as Postman Collection", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { postmanImportLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Default.Download, "Import Postman Collection", tint = MaterialTheme.colorScheme.onSurface)
                    }

                    // v3.4 Part C — C3: only shown when at least one saved request actually has an
                    // enabled assertion — otherwise there is nothing to run and the button would just
                    // be a dead end.
                    if (testableCount > 0) {
                        IconButton(
                            onClick = {
                                isRunningTests = true
                                coroutineScope.launch {
                                    testReport = ApiCollectionsRepository.runCollectionTests(
                                        folderId = null,
                                        haltOnFailure = haltOnFailure,
                                    )
                                    isRunningTests = false
                                }
                            },
                            enabled = !isRunningTests
                        ) {
                            if (isRunningTests) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow, "Run All Tests ($testableCount)",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        )

        // Master plan §2.12 — halt-on-failure toggle for the test runner. Kept outside the
        // header row (which is already tight) but still above the request list so it is always
        // visible while deciding whether to run tests.
        if (testableCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Halt on first failure",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = haltOnFailure,
                    onCheckedChange = { haltOnFailure = it },
                    colors = SwitchDefaults.colors(),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Tab row: Saved / Liked
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TabChip("Saved", allRequests.size, showSaved) {
                showSaved = true
            }
            TabChip("Liked", allRequests.count { it.isFavorite }, !showSaved) {
                showSaved = false
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        // List — dossier Q3: collections-grouped cards (was a flat LazyColumn of requests).
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.List, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (showSaved) "No saved requests yet"
                        else "No liked requests yet",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Save a request in API Testr to see it here",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(com.example.ui.theme.LocalAppSpacing.current.listItemSpacing)
            ) {
                items(collectionGroups, key = { it.id }) { group ->
                    val expanded = group.id !in collapsedCollectionIds
                    CollectionGroupCard(
                        group = group,
                        expanded = expanded,
                        onToggleExpanded = {
                            collapsedCollectionIds = if (expanded) {
                                collapsedCollectionIds + group.id
                            } else {
                                collapsedCollectionIds - group.id
                            }
                        }
                    ) {
                        if (group.requests.isEmpty()) {
                            EmptyCollectionPlaceholder()
                        } else {
                            group.requests.forEach { request ->
                                key(request.id) {
                                    val swipeState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = { value ->
                                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                                ApiCollectionsRepository.deleteRequest(request.id)
                                                true
                                            } else false
                                        }
                                    )
                                    SwipeToDismissBox(
                                        state = swipeState,
                                        backgroundContent = {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.error)
                                                    .padding(horizontal = 20.dp),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                Icon(Icons.Default.Delete, null, tint = Color.White)
                                            }
                                        },
                                        enableDismissFromStartToEnd = false,
                                        enableDismissFromEndToStart = true
                                    ) {
                                        SavedApiCard(
                                            request = request,
                                            allRequests = allRequests,
                                            onClick = { onOpenRequest(request) },
                                            onToggleFavorite = {
                                                ApiCollectionsRepository.toggleFavorite(request.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }


    // v3.4 Part C — C3: summary report modal for "Run All Tests" — X passed / Y failed overall,
    // with a per-request breakdown so a failure can be traced back to which saved request and
    // which specific assertion caused it.
    val report = testReport
    if (report != null) {
        ModalBottomSheet(
            onDismissRequest = { testReport = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            TestReportContent(report)
        }
    }
}

/**
 * v3.4 Part C — C3: contents of the "Run All Tests" summary modal — overall X/Y passed count,
 * then one row per tested saved request showing its own pass/fail count (or "request failed"
 * when the request itself could not execute, e.g. network error) and, per assertion, whether it
 * passed with the actual value or failed with the expected-vs-actual message.
 */
@Composable
private fun TestReportContent(report: CollectionTestReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text("Test Run Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "${report.passedAssertions} / ${report.totalAssertions} assertions passed across ${report.requestResults.size} request(s)",
            color = if (report.failedAssertions == 0 && report.totalAssertions > 0) Color(0xFF22C55E) else Color(0xFFEF4444),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.height(420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(report.requestResults) { result -> RequestTestResultRow(result) }
        }
    }
}

@Composable
private fun RequestTestResultRow(result: RequestTestResult) {
    // Master plan §2.12 — a row halted-out of the run renders distinctly from both a pass and
    // a genuine failure, so the user can tell "never ran because an earlier step failed" apart
    // from "ran and failed".
    val skipped = ApiCollectionsRepository.isSkippedResult(result)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (skipped) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(method = result.request.method, fontSize = 10)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                result.request.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                skipped -> Icon(
                    Icons.Default.SkipNext, "Skipped (halted after an earlier failure)",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
                )
                result.requestFailed -> Icon(Icons.Default.Close, "Request failed", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                else -> {
                    val passed = result.results.count { it.passed }
                    Text(
                        "$passed/${result.results.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (passed == result.results.size) Color(0xFF22C55E) else Color(0xFFEF4444)
                    )
                }
            }
        }

        if (skipped) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Skipped — run halted after an earlier request failed.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (result.requestFailed) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Request could not be executed (network/DNS error).",
                fontSize = 11.sp,
                color = Color(0xFFEF4444)
            )
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            result.results.forEach { assertionResult ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                    Icon(
                        imageVector = if (assertionResult.passed) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (assertionResult.passed) Color(0xFF22C55E) else Color(0xFFEF4444),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (assertionResult.passed) "Passed (actual: \"${assertionResult.actualValue}\")" else assertionResult.message,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (assertionResult.passed) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    // Selection animates on a gated tween; theme colours resolve instantly.
    val bgColor by animateStateColor(
        active = selected,
        activeColor = MaterialTheme.colorScheme.primary,
        inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = com.example.ui.theme.scaledTween(250),
        label = "tabBg"
    )
    val textColor by animateStateColor(
        active = selected,
        activeColor = MaterialTheme.colorScheme.onPrimary,
        inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = com.example.ui.theme.scaledTween(250),
        label = "tabText"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = textColor
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                "$count",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun CollectionGroupCard(
    group: CollectionSummary,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = com.example.ui.theme.scaledTween(200),
        label = "collectionChevron"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Dossier Q3: only the endpoint count is truthfully sourceable here.
                // ApiFolderItem has no environment field and EnvironmentRepository models
                // environments as a single global list, not one scoped per collection — so
                // unlike the mockup's "2 environments" line, we deliberately omit any
                // environment count rather than fabricate one.
                Text(
                    if (group.endpointCount == 1) "1 endpoint" else "${group.endpointCount} endpoints",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(com.example.ui.theme.LocalAppSpacing.current.listItemSpacing)
            ) {
                content()
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun EmptyCollectionPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "No requests yet",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun SavedApiCard(
    request: ApiRequestItem,
    allRequests: List<ApiRequestItem>,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val chainTargets = remember(request, allRequests) { chainTargets(request, allRequests) }
    val chainSources = remember(request, allRequests) { chainSources(request, allRequests) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(com.example.ui.theme.LocalAppSpacing.current.cardPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Method badge
            MethodBadge(method = request.method, fontSize = 10)

            Spacer(modifier = Modifier.width(10.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    request.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    request.url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    RelativeTimeFormatter.format(request.updatedAt),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Favorite toggle
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (request.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    "Favorite",
                    tint = if (request.isFavorite) Color(0xFFF59E0B)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // v3.2 Part C — C2: explicit "Chain" indicator showing the extraction -> injection
        // relationship between this saved request and other saved requests, instead of
        // leaving {{variableName}} matching purely implicit with no visual trace.
        if (chainTargets.isNotEmpty() || chainSources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column {
                if (chainSources.isNotEmpty()) {
                    ChainLinkRow(
                        icon = "⬇",
                        text = "Uses variables from: ${chainSources.joinToString(", ")}"
                    )
                }
                if (chainTargets.isNotEmpty()) {
                    ChainLinkRow(
                        icon = "⬆",
                        text = "Feeds variables to: ${chainTargets.joinToString(", ")}"
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainLinkRow(icon: String, text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * v3.2 Part C — C2 (Request chaining): variable names this request *reads* via `{{name}}`
 * tokens across its URL, headers, query params, body, and bearer/API-key auth fields. Used to
 * cross-reference against other saved requests' [chainExtractionVariableNames] so the "Chain"
 * indicator can show an explicit link instead of relying on the user to remember variable names.
 */
private val CHAIN_VAR_REGEX = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

private fun referencedChainVariables(request: ApiRequestItem): Set<String> {
    val haystacks = buildList {
        add(request.url)
        add(request.bodyContent)
        add(request.bearerToken)
        add(request.apiKeyValue)
        request.headers.forEach { add(it.value) }
        request.queryParams.forEach { add(it.value) }
    }
    return haystacks
        .flatMap { CHAIN_VAR_REGEX.findAll(it).map { m -> m.groupValues[1] } }
        .toSet()
}

/** Variable names this request *writes* into the active Environment via its chain extraction rules. */
private fun chainExtractionVariableNames(request: ApiRequestItem): Set<String> =
    request.chainExtractions
        .filter { it.enabled && it.variableName.isNotBlank() }
        .map { it.variableName.trim() }
        .toSet()

/** Names of other saved requests that consume a variable this [request] extracts. */
private fun chainTargets(request: ApiRequestItem, all: List<ApiRequestItem>): List<String> {
    val produced = chainExtractionVariableNames(request)
    if (produced.isEmpty()) return emptyList()
    return all
        .filter { it.id != request.id }
        .filter { referencedChainVariables(it).intersect(produced).isNotEmpty() }
        .map { it.name }
}

/** Names of other saved requests that extract a variable this [request] consumes. */
private fun chainSources(request: ApiRequestItem, all: List<ApiRequestItem>): List<String> {
    val consumed = referencedChainVariables(request)
    if (consumed.isEmpty()) return emptyList()
    return all
        .filter { it.id != request.id }
        .filter { chainExtractionVariableNames(it).intersect(consumed).isNotEmpty() }
        .map { it.name }
}

