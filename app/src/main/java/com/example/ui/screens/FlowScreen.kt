package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.data.repository.FlowRepository
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrFilterChip
import com.example.ui.components.SectionHeader
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    onMiniSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val nodes by FlowRepository.nodes.collectAsState()
    val connections by FlowRepository.connections.collectAsState()
    val userSettings by com.example.data.repository.SettingsRepository.settings.collectAsState()

    // B4: real tactile confirmation for node drag/delete, gated once behind
    // settings.hapticFeedbackEnabled via the shared helper.
    val performHaptic = rememberAppHapticFeedback()

    var isRunning by remember { mutableStateOf(false) }
    var executionResult by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableIntStateOf(0) } // 0 = Canvas, 1 = Node Sequence List
    var showAddNodeSheet by remember { mutableStateOf(false) }
    var nodeForEdit by remember { mutableStateOf<FlowNodeItem?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 58.dp)
        ) {
            // Execution status banner
            if (executionResult != null) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    backgroundColor = Color(0xFF10B981).copy(alpha = 0.15f),
                    borderColor = Color(0xFF10B981).copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(executionResult!!, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                onConnectionCreated = { fromId, toId ->
                    val fromNode = nodes.find { it.id == fromId }
                    val toNode = nodes.find { it.id == toId }
                    if (fromNode != null && toNode != null) {
                        FlowRepository.addConnection(
                            com.example.data.models.FlowConnectionItem(
                                id = "${fromId}_out_${toId}_in",
                                fromNodeId = fromId,
                                toNodeId = toId,
                                fromPort = "out",
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
                }
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

                item { Spacer(modifier = Modifier.height((userSettings.fabBottomSpacingDp + 48).dp)) }
            }
        }
    }

    SectionHeader(
            title = "Flow & Automation",
            icon = Icons.Default.LinearScale,
            onMiniSettings = onMiniSettings,
            actions = {
                GlassActionPill(
                    icon = Icons.Default.Add,
                    contentDescription = "Add Step",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { showAddNodeSheet = true }
                )
                GlassActionPill(
                    icon = if (viewMode == 0) Icons.Default.LinearScale else Icons.Default.GridView,
                    contentDescription = "Toggle View",
                    onClick = { viewMode = if (viewMode == 0) 1 else 0 }
                )
                GlassActionPill(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Run Flow",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (!isRunning) {
                            isRunning = true
                            coroutineScope.launch {
                                val res = FlowRepository.executeFlow()
                                executionResult = res
                                isRunning = false
                            }
                        }
                    }
                )
                val flowContext = androidx.compose.ui.platform.LocalContext.current
                GlassActionPill(
                    icon = androidx.compose.material.icons.Icons.Default.Share,
                    contentDescription = "Export Flow",
                    onClick = {
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val json = com.example.flow.FlowSerializer.exportToJson(
                                    "requestr-flow",
                                    nodes,
                                    connections
                                )
                                val file = com.example.data.export.FileExporter.writeJson(flowContext, json)
                                com.example.data.export.FileExporter.shareFile(
                                    flowContext, file,
                                    "application/json",
                                    "Flow export"
                                )
                            }
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )
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
}

/**
 * Flow canvas with pan/zoom, port-drag connection creation, and long-press
 * context menu. Implements TODO items #23, #24, #32, #33, #34.
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
    onConnectionCreated: (String, String) -> Unit,
    onNodeDuplicated: (FlowNodeItem) -> Unit,
    onNodeDisconnected: (String) -> Unit,
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

    // Long-press context menu state
    var contextMenuNode by remember { mutableStateOf<FlowNodeItem?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .transformable(state = transformState)
    ) {
        // Fit-to-view button
        IconButton(
            onClick = {
                // Calculate bounding box of all nodes and center+scale to fit
                if (nodes.isNotEmpty()) {
                    val minX = nodes.minOf { it.x }
                    val maxX = nodes.maxOf { it.x } + 180f
                    val minY = nodes.minOf { it.y }
                    val maxY = nodes.maxOf { it.y } + 60f
                    val contentW = maxX - minX
                    val contentH = maxY - minY
                    if (contentW > 0 && contentH > 0) {
                        scale = 0.8f
                        offsetX = -(minX + contentW / 2) * scale + 300f
                        offsetY = -(minY + contentH / 2) * scale + 300f
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
                    val startY = from.y.dp.toPx() + 35.dp.toPx()
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
                    val startY = fromNode.y.dp.toPx() + 35.dp.toPx()
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

        // Node boxes (positioned in transformed space)
        nodes.forEach { node ->
            val nodeColor = when (node.type) {
                FlowNodeType.INTERCEPT_TRIGGER -> Color(0xFFF59E0B)
                FlowNodeType.API -> Color(0xFF38BDF8)
                FlowNodeType.TRANSFORM -> Color(0xFF8B5CF6)
                FlowNodeType.OUTPUT -> Color(0xFFEC4899)
                FlowNodeType.TOOL -> Color(0xFFF97316)
                FlowNodeType.CONDITION -> Color(0xFFA855F7)
                FlowNodeType.TEXT -> Color(0xFF64748B)
                FlowNodeType.DELAY -> Color(0xFF8B5CF6)
            }

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
                        // Output port (right) — draggable to create connection
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(nodeColor)
                                .pointerInput(node.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragFromNodeId = node.id
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
                                                onConnectionCreated(node.id, dropNode.id)
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
                    RequestrFilterChip(
                        selected = selectedType == t,
                        onClick = { selectedType = t },
                        label = "${t.iconEmoji} ${t.label}"
                    )
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
