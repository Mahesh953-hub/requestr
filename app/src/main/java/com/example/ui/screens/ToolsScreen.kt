package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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

    val allTools = remember { ToolsRepository.getToolsCatalog() }

    val filteredTools = remember(searchQuery, selectedCategory) {
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

        // Category selector — Fix 07 redesign: text-only, accent underline
        CategorySelector(
            selected = selectedCategory,
            onSelect = { selectedCategory = it },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Tool grid — 2 columns, icon + name
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredTools, key = { it.id }) { tool ->
                ToolCard(tool = tool, onClick = { onNavigateToTool(tool.id) })
            }

            // Fix #37 (TODO): "Create Custom Tool" card when Custom category is selected
            if (selectedCategory == null || selectedCategory == ToolCategory.CUSTOM) {
                item(key = "create-custom-tool") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        onClick = { showCustomToolSheet = true }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create custom tool",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Create Custom Tool",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Chain existing tools or define a regex replace",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Custom tool creation bottom sheet
    if (showCustomToolSheet) {
        var toolName by remember { mutableStateOf("") }
        var toolOperation by remember { mutableStateOf("regex-replace") }
        var toolPattern by remember { mutableStateOf("") }
        var toolReplacement by remember { mutableStateOf("") }

        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showCustomToolSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Create Custom Tool", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                OutlinedTextField(
                    value = toolName,
                    onValueChange = { toolName = it },
                    label = { Text("Tool Name") },
                    placeholder = { Text("e.g., Strip HTML Tags") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Operation selector
                Text("Operation", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "regex-replace" to "Regex Replace",
                        "template" to "Template",
                    ).forEach { (op, label) ->
                        FilterChip(
                            selected = toolOperation == op,
                            onClick = { toolOperation = op },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                if (toolOperation == "regex-replace") {
                    OutlinedTextField(
                        value = toolPattern,
                        onValueChange = { toolPattern = it },
                        label = { Text("Regex Pattern") },
                        placeholder = { Text("e.g., <[^>]*>") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = toolReplacement,
                        onValueChange = { toolReplacement = it },
                        label = { Text("Replacement") },
                        placeholder = { Text("e.g., (empty to remove)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = toolPattern,
                        onValueChange = { toolPattern = it },
                        label = { Text("Template (use {input} as placeholder)") },
                        placeholder = { Text("e.g., Result: {input}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Button(
                    onClick = {
                        if (toolName.isNotBlank()) {
                            val toolId = "custom-${toolName.lowercase().replace(Regex("[^a-z0-9]"), "-")}"
                            ToolsRepository.addCustomTool(
                                com.example.data.repository.ToolItem(
                                    id = toolId,
                                    title = toolName,
                                    description = if (toolOperation == "regex-replace") "Regex: $toolPattern → $toolReplacement" else "Template: $toolPattern",
                                    category = ToolCategory.CUSTOM,
                                    icon = Icons.Default.Add,
                                )
                            )
                            showCustomToolSheet = false
                        }
                    },
                    enabled = toolName.isNotBlank() && toolPattern.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Tool")
                }
            }
        }
    }
}

// ─── Category Selector ──────────────────────────────────────────────

@Composable
private fun CategorySelector(
    selected: ToolCategory?,
    onSelect: (ToolCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = listOf(null to "All") + ToolCategory.entries.map { it to it.displayName }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        categories.forEach { (cat, label) ->
            val isActive = cat == selected
            val textColor by animateColorAsState(
                if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = com.example.ui.theme.scaledTween(250),
                label = "catTextColor"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable { onSelect(cat) }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                )
                if (isActive) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

// ─── Tool Card ──────────────────────────────────────────────────────

@Composable
private fun ToolCard(
    tool: ToolItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tool name
            Text(
                text = tool.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
    }
}
