package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredTools, key = { it.id }) { tool ->
                RedesignedToolCard(tool = tool, onClick = { onNavigateToTool(tool.id) })
            }

            // "Create Custom Tool" card when Custom category (or "All") is selected.
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Tool")
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
    val categories = listOf(null to "All") + ToolCategory.entries.map { it to it.displayName }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { (cat, label) ->
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

// ─── Tool Card (v6.0 transplant §5.6: 16dp radius, 24dp icon tile, description) ───

@Composable
private fun RedesignedToolCard(
    tool: ToolItem,
    onClick: () -> Unit
) {
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
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon container with warm accent tint
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tool name
            Text(
                text = tool.title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(3.dp))

            // Tool description
            Text(
                text = tool.description,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
