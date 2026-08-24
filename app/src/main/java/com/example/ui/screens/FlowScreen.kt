package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.FlowPipelineItem
import com.example.data.repository.FlowRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    onMiniSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val nodes by FlowRepository.nodes.collectAsState()
    val connections by FlowRepository.connections.collectAsState()
    val allPipelines by FlowRepository.allFlows.collectAsState()
    val activeFlowId by FlowRepository.activeFlowId.collectAsState()

    // B4: real tactile confirmation for node drag/delete, gated once behind
    // settings.hapticFeedbackEnabled via the shared helper.
    val performHaptic = rememberAppHapticFeedback()

    var isRunning by remember { mutableStateOf(false) }
    var executionSuccess by remember { mutableStateOf<Boolean?>(null) }
    var executionMessage by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableIntStateOf(0) } // 0 = Canvas, 1 = Node Sequence List
    var showAddNodeSheet by remember { mutableStateOf(false) }
    var showNewPipelineSheet by remember { mutableStateOf(false) }
    var nodeForEdit by remember { mutableStateOf<FlowNodeItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    fun notify(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    val flowContext = androidx.compose.ui.platform.LocalContext.current

    // Fix #25: real import via a document picker with actual result handling, replacing the
    // previous ACTION_GET_CONTENT fire-and-forget that never read anything back.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val json = runCatching {
                flowContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (json == null) {
                notify("Could not read the selected file.")
                return@launch
            }
            val parsed = com.example.flow.FlowSerializer.importFromJson(json)
            if (parsed == null) {
                notify("Import failed: file is not a valid Flow export.")
            } else {
                val (importedNodes, importedConnections) = parsed
                FlowRepository.replaceGraph(importedNodes, importedConnections)
                notify("Imported ${importedNodes.size} node(s) and ${importedConnections.size} connection(s).")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Flow & Automation (n8n Engine)",
            onMiniSettings = onMiniSettings,
            actions = {
                IconButton(onClick = {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val json = com.example.flow.FlowSerializer.exportToJson(
                                "requestr-flow",
                                nodes,
                                connections
                            )
                            val target = com.example.data.export.FileExporter.writeJson(flowContext, json)
                                ?: error("Failed to write flow export to Downloads/requestr/")
                            com.example.data.export.FileExporter.shareFile(
                                flowContext, target,
                                "application/json",
                                "Flow export"
                            )
                        }.onFailure { notify("Export failed: ${it.message}") }
                    }
                }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Share,
                        contentDescription = "Export Flow",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { viewMode = if (viewMode == 0) 1 else 0 }) {
                    Icon(
                        imageVector = if (viewMode == 0) Icons.Default.LinearScale else Icons.Default.GridView,
                        contentDescription = "Toggle View",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { showAddNodeSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Step",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Import Flow",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        isRunning = true
                        coroutineScope.launch {
                            val res = FlowRepository.executeFlow()
                            executionSuccess = res.success
                            executionMessage = res.message
                            isRunning = false
                        }
                    },
                    enabled = !isRunning && nodes.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Run flow", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Run Flow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )

        // Pipeline Selector Strip (transplant slice 11)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            allPipelines.forEach { pipeline ->
                PipelinePillItem(
                    pipeline = pipeline,
                    isActive = pipeline.id == activeFlowId,
                    onClick = {
                        performHaptic(HapticFeedbackType.TextHandleMove)
                        FlowRepository.setActiveFlow(pipeline.id)
                    }
                )
            }

            // Add Pipeline Pill
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { showNewPipelineSheet = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Pipeline",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "New Pipeline",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Execution progress indicator (v6.0 plan task 13): while a flow is
        // running, show an indeterminate progress banner. The frozen
        // FlowRepository.executeFlow() returns a single FlowRunSummary with no
        // per-node callbacks, so an honest indeterminate indicator is used
        // rather than fabricating deterministic per-node progress.
        if (isRunning) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Running flow…",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    )
                }
            }
        }

        // Execution status banner -- colour and icon now reflect the real outcome (task 2c),
        // instead of always rendering green + CheckCircle regardless of success/failure.
        if (executionMessage != null) {
            val bannerColor = if (executionSuccess == true) Color(0xFF10B981) else Color(0xFFEF4444)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                backgroundColor = bannerColor.copy(alpha = 0.15f),
                borderColor = bannerColor.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (executionSuccess == true) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = if (executionSuccess == true) "Success" else "Failure",
                        tint = bannerColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(executionMessage!!, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (viewMode == 0) {
            // Visual Canvas Mode — with pan/zoom, port-drag, context menu, mini-map
            FlowCanvas(
                nodes = nodes,
                connections = connections,
                performHaptic = performHaptic,
                onNodeMoved = { id, x, y -> FlowRepository.updateNodePosition(id, x, y) },
                onNodeDeleted = { id ->
                    performHaptic(HapticFeedbackType.LongPress)
                    FlowRepository.removeNode(id)
                },
                onNodeEdit = { nodeForEdit = it },
                onConnectionCreated = { fromId, fromPort, toId ->
                    val fromNode = nodes.find { it.id == fromId }
                    val toNode = nodes.find { it.id == toId }
                    if (fromNode != null && toNode != null) {
                        // v6.0 (plan task 13): preserve the dragged source port so CONDITION
                        // nodes record true/false branches the executor follows; non-CONDITION
                        // nodes keep the "out" port. Backup behavior wins — connection id and
                        // toPort stay deterministic via flowConnectionId.
                        val resolvedPort = if (fromNode.type == FlowNodeType.CONDITION &&
                            (fromPort == "true" || fromPort == "false")) fromPort else "out"
                        FlowRepository.addConnection(
                            com.example.data.models.FlowConnectionItem(
                                id = com.example.data.models.flowConnectionId(fromId, resolvedPort, toId, "in"),
                                fromNodeId = fromId,
                                toNodeId = toId,
                                fromPort = resolvedPort,
                                toPort = "in",
                            )
                        )
                        performHaptic(HapticFeedbackType.LongPress)
                    }
                },
                onNodeDuplicated = { node ->
                    FlowRepository.addNode(
                        node.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            x = node.x + 30f,
                            y = node.y + 30f,
                            title = "${node.title} (copy)"
                        )
                    )
                },
                onNodeDisconnected = { nodeId ->
                    connections.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
                        .forEach { FlowRepository.removeConnection(it.id) }
                },
                onAddNode = { showAddNodeSheet = true }
            )
        } else {
            // Sequence List Mode
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Automation Steps (${nodes.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                items(nodes, key = { it.id }) { node ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(node.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("Type: ${node.type.name} · ${node.configData}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Row {
                                IconButton(onClick = { nodeForEdit = node }) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { FlowRepository.removeNode(node.id) }) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // Add Step Bottom Sheet
    if (showAddNodeSheet) {
        AddFlowNodeBottomSheet(
            onDismiss = { showAddNodeSheet = false },
            onAdd = { title, type, config ->
                FlowRepository.addNode(
                    FlowNodeItem(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        type = type,
                        x = (60 + (nodes.size * 50)).toFloat(),
                        y = (80 + (nodes.size * 30)).toFloat(),
                        inputPorts = listOf("in"),
                        outputPorts = listOf("out"),
                        configData = config
                    )
                )
                showAddNodeSheet = false
            }
        )
    }

    // Edit Step Bottom Sheet — closes the previously dead-end nodeForEdit
    // state (context-menu Edit + Sequence-List Edit button both set it, but
    // nothing consumed it before this).
    nodeForEdit?.let { editing ->
        EditFlowNodeBottomSheet(
            node = editing,
            onDismiss = { nodeForEdit = null },
            onSave = { title, type, config ->
                FlowRepository.updateNode(editing.copy(title = title, type = type, configData = config))
                nodeForEdit = null
            }
        )
    }

    // Create New Pipeline Bottom Sheet (transplant slice 11)
    if (showNewPipelineSheet) {
        var pipelineName by remember { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showNewPipelineSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Create New Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = pipelineName,
                    onValueChange = { pipelineName = it },
                    label = { Text("Pipeline Name (e.g. Auth & Token Refresh Flow)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Button(
                    onClick = {
                        FlowRepository.createPipeline(pipelineName)
                        showNewPipelineSheet = false
                    },
                    enabled = pipelineName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Create Pipeline")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )
    }
}

/**
 * Flow canvas with pan/zoom, port-drag connection creation, and long-press
 * context menu. Implements items #23, #24, #32, #33, #34.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FlowCanvas(
    nodes: List<FlowNodeItem>,
    connections: List<FlowConnectionItem>,
    performHaptic: (androidx.compose.ui.hapticfeedback.HapticFeedbackType) -> Unit,
    onNodeMoved: (String, Float, Float) -> Unit,
    onNodeDeleted: (String) -> Unit,
    onNodeEdit: (FlowNodeItem) -> Unit,
    onConnectionCreated: (String, String, String) -> Unit, // fromId, fromPort, toId
    onNodeDuplicated: (FlowNodeItem) -> Unit,
    onNodeDisconnected: (String) -> Unit,
    onAddNode: () -> Unit,
) {
    // Pan/zoom state
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 3f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    // Port-drag connection state
    var dragFromNodeId by remember { mutableStateOf<String?>(null) }
    var dragEndX by remember { mutableStateOf(0f) }
    var dragEndY by remember { mutableStateOf(0f) }
    // v6.0 (plan task 13): which output port a drag started from, so CONDITION nodes can
    // record true/false branches. Defaults to "out" for non-CONDITION nodes.
    var dragFromPort by remember { mutableStateOf("out") }

    // Density captured at composition so the ConditionOutputPort drag lambdas
    // (defined in this @Composable scope, not inside a PointerInputScope) can
    // convert node dp coordinates to pixels. The single output port computes
    // toPx() inside its own pointerInput block (a Density); these extracted
    // lambdas run outside that scope, so they need an explicit Density.
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Long-press context menu state
    var contextMenuNode by remember { mutableStateOf<FlowNodeItem?>(null) }

    // Real measured canvas size, captured via onSizeChanged below. Fit-to-view
    // derives scale/offsets from this instead of a hardcoded 0.8f + 300f guess.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .transformable(state = transformState)
            .onSizeChanged { canvasSize = it }
    ) {
        // Fit-to-view button
        IconButton(
            onClick = {
                // Calculate bounding box of all nodes and center+scale to fit
                // the real measured canvas dimensions, instead of a fixed
                // scale/offset guess that ignored actual screen size.
                if (nodes.isNotEmpty() && canvasSize.width > 0 && canvasSize.height > 0) {
                    val minX = nodes.minOf { it.x }
                    val maxX = nodes.maxOf { it.x } + 180f
                    val minY = nodes.minOf { it.y }
                    val maxY = nodes.maxOf { it.y } + 60f
                    val contentW = maxX - minX
                    val contentH = maxY - minY
                    if (contentW > 0 && contentH > 0) {
                        val fitScale = minOf(
                            canvasSize.width / contentW,
                            canvasSize.height / contentH
                        ).coerceIn(0.3f, 3f)
                        scale = fitScale
                        offsetX = -(minX + contentW / 2) * fitScale + canvasSize.width / 2f
                        offsetY = -(minY + contentH / 2) * fitScale + canvasSize.height / 2f
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Fit to View",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Canvas: grid dots + connection lines + drag preview line
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        ) {
            val step = 28.dp.toPx() / scale
            var x = -offsetX / scale - 500f
            while (x < size.width / scale + 500f) {
                var y = -offsetY / scale - 500f
                while (y < size.height / scale + 500f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.06f),
                        radius = 1.5f / scale,
                        center = Offset(x, y)
                    )
                    y += step
                }
                x += step
            }

            // Draw existing connections
            connections.forEach { conn ->
                val from = nodes.find { it.id == conn.fromNodeId }
                val to = nodes.find { it.id == conn.toNodeId }
                if (from != null && to != null) {
                    val startX = from.x.dp.toPx() + 180.dp.toPx()
                    // v6.0 (plan task 13): CONDITION nodes branch on true/false output ports.
                    // Offset the curve's origin to the matching port so the two branches are
                    // visually distinct (true = upper, false = lower), reflecting the executor's
                    // real CONDITION→true/false branching instead of a single merged origin.
                    val startY = from.y.dp.toPx() + conditionPortYOffset(from, conn.fromPort).dp.toPx()
                    val endX = to.x.dp.toPx()
                    val endY = to.y.dp.toPx() + 35.dp.toPx()

                    val path = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(
                            startX + 60f, startY,
                            endX - 60f, endY,
                            endX, endY
                        )
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFF38BDF8),
                        style = Stroke(width = 3.dp.toPx() / scale)
                    )
                }
            }

            // Draw drag preview line (port-drag connection in progress)
            if (dragFromNodeId != null) {
                val fromNode = nodes.find { it.id == dragFromNodeId }
                if (fromNode != null) {
                    val startX = fromNode.x.dp.toPx() + 180.dp.toPx()
                    val startY = fromNode.y.dp.toPx() +
                        conditionPortYOffset(fromNode, dragFromPort).dp.toPx()
                    val path = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(
                            startX + 60f, startY,
                            dragEndX - 60f, dragEndY,
                            dragEndX, dragEndY
                        )
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                        style = Stroke(width = 2.dp.toPx() / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    )
                }
            }
        }

        // Empty state (flow-empty mockup): centered prompt + Add Node CTA when
        // the graph has no nodes. Rendered above the dotted-grid Canvas so the
        // grid texture stays visible behind it, matching the design spec.
        if (nodes.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = 0.4f }
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "No automation steps yet",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap + to add your first node and start building your pipeline.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = onAddNode,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add first node",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "Add Node",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Node boxes (positioned in transformed space)
        nodes.forEach { node ->
            // v6.0 (plan task 13): node color from the frozen FlowNodeType.colorHex token,
            // matching the design-to-code manifest flow_node_type_colors (single source of
            // truth in AppModels) instead of a divergent hardcoded palette.
            val nodeColor = Color(node.type.colorHex)

            // Long-press context menu for this node
            if (contextMenuNode?.id == node.id) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { contextMenuNode = null },
                    modifier = Modifier
                        .offset { IntOffset(
                            (node.x.dp.roundToPx() * scale + offsetX).toInt(),
                            (node.y.dp.roundToPx() * scale + offsetY).toInt() + 60
                        )}
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { contextMenuNode = null; onNodeEdit(node) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { contextMenuNode = null; onNodeDuplicated(node) }
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect All") },
                        onClick = { contextMenuNode = null; onNodeDisconnected(node.id) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { contextMenuNode = null; onNodeDeleted(node.id) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (node.x.dp.roundToPx() * scale + offsetX).toInt(),
                            (node.y.dp.roundToPx() * scale + offsetY).toInt()
                        )
                    }
                    .width((180.dp.value * scale).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, nodeColor, RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = { onNodeEdit(node) },
                        onLongClick = {
                            performHaptic(HapticFeedbackType.LongPress)
                            contextMenuNode = node
                        }
                    )
                    .pointerInput(node.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onNodeMoved(
                                node.id,
                                node.x + dragAmount.x / scale / 2.5f,
                                node.y + dragAmount.y / scale / 2.5f
                            )
                        }
                    }
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${node.type.iconEmoji} ${node.title}",
                            fontWeight = FontWeight.Bold,
                            fontSize = (12.sp.value * scale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = { onNodeDeleted(node.id) },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = node.configData,
                        fontSize = (9.sp.value * scale).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )

                    // Port handles for drag-to-connect
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Input port (left)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(nodeColor.copy(alpha = 0.4f))
                                .border(1.dp, nodeColor, CircleShape)
                        )
                        // Output ports (right) — draggable to create connection(s).
                        // v6.0 (plan task 13): CONDITION nodes expose two labelled output
                        // ports (true / false) matching the executor's real branching; every
                        // other node type keeps a single "out" port.
                        if (node.type == FlowNodeType.CONDITION) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ConditionOutputPort(
                                    label = "true",
                                    color = nodeColor,
                                    onDragStart = {
                                        dragFromNodeId = node.id
                                        dragFromPort = "true"
                                        with(density) {
                                            dragEndX = node.x.dp.toPx() + 180.dp.toPx()
                                            dragEndY = node.y.dp.toPx() +
                                                conditionPortYOffset(node, "true").dp.toPx()
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragEndX += dragAmount.x / scale
                                        dragEndY += dragAmount.y / scale
                                    },
                                    onDragEnd = {
                                        val dropNode = with(density) {
                                            nodes.find { n ->
                                                n.id != node.id &&
                                                    kotlin.math.abs(dragEndX - n.x.dp.toPx()) < 30f &&
                                                    kotlin.math.abs(dragEndY - (n.y.dp.toPx() + 35.dp.toPx())) < 30f
                                            }
                                        }
                                        if (dropNode != null) {
                                            onConnectionCreated(node.id, "true", dropNode.id)
                                        }
                                        dragFromNodeId = null
                                    },
                                    scale = scale
                                )
                                ConditionOutputPort(
                                    label = "false",
                                    color = nodeColor,
                                    onDragStart = {
                                        dragFromNodeId = node.id
                                        dragFromPort = "false"
                                        with(density) {
                                            dragEndX = node.x.dp.toPx() + 180.dp.toPx()
                                            dragEndY = node.y.dp.toPx() +
                                                conditionPortYOffset(node, "false").dp.toPx()
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragEndX += dragAmount.x / scale
                                        dragEndY += dragAmount.y / scale
                                    },
                                    onDragEnd = {
                                        val dropNode = with(density) {
                                            nodes.find { n ->
                                                n.id != node.id &&
                                                    kotlin.math.abs(dragEndX - n.x.dp.toPx()) < 30f &&
                                                    kotlin.math.abs(dragEndY - (n.y.dp.toPx() + 35.dp.toPx())) < 30f
                                            }
                                        }
                                        if (dropNode != null) {
                                            onConnectionCreated(node.id, "false", dropNode.id)
                                        }
                                        dragFromNodeId = null
                                    },
                                    scale = scale
                                )
                            }
                        } else {
                        // Single output port — draggable to create connection
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(nodeColor)
                                .pointerInput(node.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragFromNodeId = node.id
                                            dragFromPort = "out"
                                            dragEndX = node.x.dp.toPx() + 180.dp.toPx()
                                            dragEndY = node.y.dp.toPx() + 35.dp.toPx()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragEndX += dragAmount.x / scale
                                            dragEndY += dragAmount.y / scale
                                        },
                                        onDragEnd = {
                                            // Check if dropped on another node's input port
                                            val dropNode = nodes.find { n ->
                                                n.id != node.id &&
                                                kotlin.math.abs(dragEndX - n.x.dp.toPx()) < 30f &&
                                                kotlin.math.abs(dragEndY - (n.y.dp.toPx() + 35.dp.toPx())) < 30f
                                            }
                                            if (dropNode != null) {
                                                onConnectionCreated(node.id, "out", dropNode.id)
                                            }
                                            dragFromNodeId = null
                                        }
                                    )
                                }
                        )
                        }
                    }
                }
            }
        }

        // Mini-map (bottom right corner)
        if (nodes.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    val minX = nodes.minOf { it.x }
                    val maxX = nodes.maxOf { it.x } + 180f
                    val minY = nodes.minOf { it.y }
                    val maxY = nodes.maxOf { it.y } + 60f
                    val mapScale = minOf(
                        size.width / (maxX - minX).coerceAtLeast(1f),
                        size.height / (maxY - minY).coerceAtLeast(1f)
                    ) * 0.8f

                    // Draw connections as lines
                    connections.forEach { conn ->
                        val from = nodes.find { it.id == conn.fromNodeId }
                        val to = nodes.find { it.id == conn.toNodeId }
                        if (from != null && to != null) {
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.4f),
                                start = Offset(
                                    (from.x - minX) * mapScale,
                                    (from.y - minY) * mapScale
                                ),
                                end = Offset(
                                    (to.x - minX) * mapScale,
                                    (to.y - minY) * mapScale
                                ),
                                strokeWidth = 1f
                            )
                        }
                    }

                    // Draw nodes as dots
                    nodes.forEach { node ->
                        val color = when (node.type) {
                            FlowNodeType.API -> Color(0xFF38BDF8)
                            FlowNodeType.INTERCEPT_TRIGGER -> Color(0xFFF59E0B)
                            FlowNodeType.TOOL -> Color(0xFFF97316)
                            FlowNodeType.CONDITION -> Color(0xFFA855F7)
                            FlowNodeType.TRANSFORM -> Color(0xFF8B5CF6)
                            FlowNodeType.OUTPUT -> Color(0xFFEC4899)
                            else -> Color(0xFF64748B)
                        }
                        drawCircle(
                            color = color,
                            radius = 3f,
                            center = Offset(
                                (node.x - minX) * mapScale,
                                (node.y - minY) * mapScale
                            )
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFlowNodeBottomSheet(
    onDismiss: () -> Unit,
    onAdd: (String, FlowNodeType, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stepTitle by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(FlowNodeType.API) }
    var configText by remember { mutableStateOf("https://httpbin.org/json") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add Automation Step", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

            OutlinedTextField(
                value = stepTitle,
                onValueChange = { stepTitle = it },
                label = { Text("Step Title (e.g. Extract Token, Trigger Webhook)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Text("Select Trigger / Action Type:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowNodeType.values().forEach { t ->
                    val isSel = selectedType == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedType = t }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${t.iconEmoji} ${t.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            OutlinedTextField(
                value = configText,
                onValueChange = { configText = it },
                label = { Text("Config / Endpoint / Pattern") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Button(
                onClick = {
                    if (stepTitle.isNotBlank()) {
                        onAdd(stepTitle, selectedType, configText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Add Node to Automation Canvas")
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFlowNodeBottomSheet(
    node: FlowNodeItem,
    onDismiss: () -> Unit,
    onSave: (String, FlowNodeType, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stepTitle by remember(node.id) { mutableStateOf(node.title) }
    var selectedType by remember(node.id) { mutableStateOf(node.type) }
    var configText by remember(node.id) { mutableStateOf(node.configData) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Edit Automation Step", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

            OutlinedTextField(
                value = stepTitle,
                onValueChange = { stepTitle = it },
                label = { Text("Step Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Text("Select Trigger / Action Type:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowNodeType.values().forEach { t ->
                    val isSel = selectedType == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedType = t }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${t.iconEmoji} ${t.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            OutlinedTextField(
                value = configText,
                onValueChange = { configText = it },
                label = { Text("Config / Endpoint / Pattern") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Button(
                onClick = {
                    if (stepTitle.isNotBlank()) {
                        onSave(stepTitle, selectedType, configText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Changes")
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ─── PIPELINE PILL ITEM (transplant slice 11) ────────────────────────────────

@Composable
fun PipelinePillItem(
    pipeline: FlowPipelineItem,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val pillBg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "pillBg"
    )
    val pillText by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "pillText"
    )

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = pillBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = pipeline.name,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = pillText
            )
            Text(
                text = "(${pipeline.nodeCount})",
                fontSize = 10.sp,
                color = pillText.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Y offset (in dp) of a node's output port, relative to the node's top-left
 * origin. CONDITION nodes expose two ports — "true" sits higher and "false"
 * lower so the two branches render distinctly — while every other type keeps
 * the single "out" port at the baseline 35dp used by the existing connection
 * geometry. Pure function; presentation-only (does not alter executor logic).
 */
fun conditionPortYOffset(node: FlowNodeItem, port: String): Float =
    if (node.type == FlowNodeType.CONDITION && port == "true") 22f
    else if (node.type == FlowNodeType.CONDITION && port == "false") 48f
    else 35f

/**
 * A labelled, draggable output port for CONDITION nodes (v6.0 plan task 13).
 * Renders the port label ("true"/"false") next to a draggable connector dot,
 * mirroring the single output port's drag-to-connect behaviour so each branch
 * records its own [FlowConnectionItem.fromPort] that the executor follows.
 */
@Composable
fun ConditionOutputPort(
    label: String,
    color: Color,
    onDragStart: (Offset) -> Unit,
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit,
    onDragEnd: () -> Unit,
    scale: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontSize = (8.sp.value * scale).sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
                .pointerInput(label) {
                    detectDragGestures(
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                }
        )
    }
}
