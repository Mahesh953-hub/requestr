package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ToolCategory
import com.example.data.repository.ToolItem
import com.example.data.repository.ToolsRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.SearchInputField
import com.example.ui.components.SectionHeader

/**
 * Redesigned Tools Library (Fix 07). Category selector with accent underline,
 * 2-column icon+name tool grid, and proper navigation to [ToolWorkspaceScreen].
 * Grid scroll position and selected category survive navigation via back-stack
 * preservation.
 */
@Composable
fun ToolsScreen(
    onMiniSettings: () -> Unit,
    onNavigateToTool: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var showCustomToolSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val allTools by ToolsRepository.catalog.collectAsState()

    val filteredTools = remember(allTools, searchQuery, selectedCategory) {
        allTools.filter { tool ->
            (selectedCategory == null || tool.category == selectedCategory) &&
                    (searchQuery.isBlank() || tool.title.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Tool Library",
            onMiniSettings = onMiniSettings,
            onBack = null
        )

        // Search
        SearchInputField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search tools…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // v6.0 transplant (manifest §5.6): Category selector — 20dp capsule chips
        ToolCategorySelector(
            selected = selectedCategory,
            onSelect = { selectedCategory = it },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Tool grid — 2 columns, icon + name + description
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // v6.0 lane B (manifest §tools-catalogue): "Create Custom Tool" is a
            // full-width card with a dashed border + primaryContainer tint, shown
            // when the Custom category (or All) is active — sits above custom tools.
            if (selectedCategory == null || selectedCategory == ToolCategory.CUSTOM) {
                item(key = "create-custom-tool", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    CreateCustomToolCard(onClick = { showCustomToolSheet = true })
                }
            }

            items(filteredTools, key = { it.id }) { tool ->
                RedesignedToolCard(tool = tool, onClick = { onNavigateToTool(tool.id) })
            }
        }
    }

    // v6.0 lane B (manifest §tools-custom-sheet): Custom tool creation bottom sheet
    // with drag handle, operation FilterChips, and live preview showing input → output.
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    if (showCustomToolSheet) {
        var toolName by remember { mutableStateOf("") }
        var toolOperation by remember { mutableStateOf("regex-replace") }
        var toolPattern by remember { mutableStateOf("") }
        var toolReplacement by remember { mutableStateOf("") }
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // v6.0 lane B (manifest §tools-custom-sheet): live preview — a fixed sample
        // input is run through the regex-replace or template operation so the user
        // sees the transform result before saving, matching the mockup's LivePreviewBox.
        val sampleInput = "Authorization: Bearer eyJhbGciOiJIUzI1"
        val previewOutput = remember(toolOperation, toolPattern, toolReplacement) {
            runCatching {
                if (toolOperation == "regex-replace" && toolPattern.isNotBlank()) {
                    Regex(toolPattern).replace(sampleInput, toolReplacement)
                } else if (toolOperation == "template" && toolPattern.isNotBlank()) {
                    toolPattern.replace("{input}", sampleInput)
                } else {
                    sampleInput
                }
            }.getOrDefault("Invalid regex pattern")
        }

        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showCustomToolSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = sheetState,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Sheet header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Create Custom Tool", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable { showCustomToolSheet = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Tool Name
                FieldLabel("Tool Name")
                OutlinedTextField(
                    value = toolName,
                    onValueChange = { toolName = it },
                    placeholder = { Text("e.g., Log Sanitizer") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Operation FilterChips
                FieldLabel("Operation")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "regex-replace" to "Regex Replace",
                        "template" to "Template",
                    ).forEach { (op, label) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (toolOperation == op) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    if (toolOperation == op) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { toolOperation = op }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (toolOperation == op) FontWeight.Bold else FontWeight.Medium,
                                color = if (toolOperation == op) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (toolOperation == "regex-replace") {
                    FieldLabel("Regex Pattern")
                    OutlinedTextField(
                        value = toolPattern,
                        onValueChange = { toolPattern = it },
                        placeholder = { Text("(Bearer|token|api[_-]?key)\\s*[:=]\\s*\\S+") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )
                    FieldLabel("Replacement")
                    OutlinedTextField(
                        value = toolReplacement,
                        onValueChange = { toolReplacement = it },
                        placeholder = { Text("[REDACTED]") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    FieldLabel("Template (use {input} as placeholder)")
                    OutlinedTextField(
                        value = toolPattern,
                        onValueChange = { toolPattern = it },
                        placeholder = { Text("Result: {input}") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Live Preview
                FieldLabel("Live Preview")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "INPUT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = sampleInput,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF67E8F9)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "OUTPUT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = previewOutput,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF34D399)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save button (manifest §tools-custom-sheet: radius 16dp, primary bg)
                Button(
                    onClick = {
                        if (toolName.isNotBlank()) {
                            ToolsRepository.addCustomTool(
                                name = toolName,
                                operation = toolOperation,
                                pattern = toolPattern,
                                replacement = toolReplacement,
                            )
                            showCustomToolSheet = false
                        }
                    },
                    enabled = toolName.isNotBlank() && toolPattern.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Custom Tool", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

// ─── Category Selector (v6.0 transplant §5.6: 20dp capsule chips) ───

@Composable
private fun ToolCategorySelector(
    selected: ToolCategory?,
    onSelect: (ToolCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    // v6.0 lane B (manifest §tools-catalogue): capsule chips with emoji icons from
    // the ToolCategory.icon field, matching the mockup's All/🔓Decoders/🛡️Ciphers…
    val categories = listOf(null to "All" to "") + ToolCategory.entries.map { it to it.displayName to it.icon }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { (pair, emoji) ->
            val (cat, label) = pair
            val isActive = cat == selected
            val activeBg by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(200),
                label = "catBg"
            )
            val activeText by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(200),
                label = "catText"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(activeBg)
                    .clickable { onSelect(cat) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (emoji.isNotEmpty()) {
                        Text(
                            text = emoji,
                            fontSize = 13.sp,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.size(5.dp))
                    }
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = activeText
                    )
                }
            }
        }
    }
}

// ─── Tool Card (v6.0 lane B manifest §tools-catalogue: 16dp radius, 36dp icon tile
//     with category-specific accent color, title 13.5sp, subtitle 10.5sp) ───

/**
 * Per-tool accent color matching the approved mockup's icon-tile tints.
 * Falls back to primary for unknown/custom tools.
 */
private fun toolAccentColor(toolId: String): Color = when (toolId) {
    "jwt-decoder"     -> Color(0xFF06B6D4) // cyan
    "base64"          -> Color(0xFF818CF8) // indigo-bright
    "url-encoder"     -> Color(0xFF22C55E) // green
    "hex-converter"   -> Color(0xFFF59E0B) // amber
    "html-entity"     -> Color(0xFF8B5CF6) // violet
    "morse-code"      -> Color(0xFF64748B) // slate
    "aes-cipher"      -> Color(0xFF3B82F6) // blue
    "xor-cipher"      -> Color(0xFFEC4899) // pink
    "sha256", "sha1"  -> Color(0xFF10B981) // emerald
    "md5"             -> Color(0xFFF97316) // orange
    "hmac-sha256"     -> Color(0xFF8B5CF6) // violet
    "crc32"           -> Color(0xFF84CC16) // lime
    "json-formatter"  -> Color(0xFF818CF8) // indigo-bright
    "sql-formatter"   -> Color(0xFF06B6D4) // cyan
    "ip-calculator"   -> Color(0xFF3B82F6) // blue
    "uuid-gen"        -> Color(0xFF10B981) // emerald
    "random-string"   -> Color(0xFF84CC16) // lime
    "lorem-ipsum"     -> Color(0xFF64748B) // slate
    else              -> Color(0xFFF59E0B) // amber (custom tools)
}

@Composable
private fun RedesignedToolCard(
    tool: ToolItem,
    onClick: () -> Unit
) {
    val accent = toolAccentColor(tool.id)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon tile with category-specific accent tint (manifest §tools-catalogue)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.title,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                // Tool name (13.5sp bold)
                Text(
                    text = tool.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = (-0.2).sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Tool description (10.5sp)
                Text(
                    text = tool.description,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

// ─── Create Custom Tool card (manifest §tools-catalogue: full-width, dashed
//     border, primaryContainer tint, icon tile + title + subtitle) ───

@Composable
private fun CreateCustomToolCard(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val dashColor = primary.copy(alpha = 0.4f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .drawBehind {
                // v6.0 lane B (manifest §tools-catalogue): dashed border — Compose has
                // no built-in dashed BorderStroke, so draw it with a dashPathEffect.
                val r = 16.dp.toPx()
                val w = 1.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = primary.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon tile
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.2f))
                    .border(1.dp, primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create custom tool",
                    tint = primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Create Custom Tool",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "Regex replace or template transform",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Field label (manifest §tools-custom-sheet: 11sp bold uppercase, dim color) ───

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 7.dp)
    )
}
