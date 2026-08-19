package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    onMiniSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val nodes by FlowRepository.nodes.collectAsState()
    val connections by FlowRepository.connections.collectAsState()

    // B4: real tactile confirmation for node drag/delete, gated once behind
    // settings.hapticFeedbackEnabled via the shared helper.
    val performHaptic = rememberAppHapticFeedback()

    var isRunning by remember { mutableStateOf(false) }
    var executionResult by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableIntStateOf(0) } // 0 = Canvas, 1 = Node Sequence List
    var showAddNodeSheet by remember { mutableStateOf(false) }
    var nodeForEdit by remember { mutableStateOf<FlowNodeItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Flow & Automation (n8n Engine)",
            onMiniSettings = onMiniSettings,
            actions = {
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

                Button(
                    onClick = {
                        isRunning = true
                        coroutineScope.launch {
                            val res = FlowRepository.executeFlow()
                            executionResult = res
                            isRunning = false
                        }
                    },
                    enabled = !isRunning,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Run Flow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )

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
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(executionResult!!, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (viewMode == 0) {
            // Visual Canvas Mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                // Draw grid dots and connection bezier paths
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = 28.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        var y = 0f
                        while (y < size.height) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.06f),
                                radius = 1.5f,
                                center = Offset(x, y)
                            )
                            y += step
                        }
                        x += step
                    }

                    // Connection Lines
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
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }

                // Interactive draggable nodes
                nodes.forEach { node ->
                    DraggableNodeBox(
                        node = node,
                        onDrag = { dx, dy ->
                            FlowRepository.updateNodePosition(node.id, node.x + dx / 2.5f, node.y + dy / 2.5f)
                        },
                        onDelete = {
                            performHaptic(HapticFeedbackType.LongPress)
                            FlowRepository.removeNode(node.id)
                        },
                        onEdit = { nodeForEdit = node }
                    )
                }
            }
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
}

@Composable
fun DraggableNodeBox(
    node: FlowNodeItem,
    onDrag: (Float, Float) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val nodeColor = when (node.type) {
        FlowNodeType.INTERCEPT_TRIGGER -> Color(0xFFF59E0B)
        FlowNodeType.API -> Color(0xFF38BDF8)
        FlowNodeType.TRANSFORM -> Color(0xFF8B5CF6)

        FlowNodeType.OUTPUT -> Color(0xFFEC4899)
        FlowNodeType.TOOL -> Color(0xFFF97316)
        FlowNodeType.CONDITION -> Color(0xFFA855F7)
        FlowNodeType.TEXT -> Color(0xFF64748B)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(node.x.dp.roundToPx(), node.y.dp.roundToPx()) }
            .width(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, nodeColor, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
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
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = node.configData,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
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
