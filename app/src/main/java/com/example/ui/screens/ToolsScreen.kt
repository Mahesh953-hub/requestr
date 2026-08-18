package com.example.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.data.repository.ToolCategory
import com.example.data.repository.ToolItem
import com.example.data.repository.ToolsRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.SearchInputField
import com.example.ui.components.SectionHeader
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ToolsScreen(
    onMiniSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var activeTool by remember { mutableStateOf<ToolItem?>(null) }

    val allTools = remember { ToolsRepository.getToolsCatalog() }

    val filteredTools = remember(searchQuery, selectedCategory) {
        allTools.filter { tool ->
            (selectedCategory == null || tool.category == selectedCategory) &&
                    (searchQuery.isBlank() || tool.title.contains(searchQuery, ignoreCase = true) || tool.description.contains(searchQuery, ignoreCase = true))
        }
    }

    val currentTool = activeTool

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (currentTool == null) {
            // Main Catalog View
            SectionHeader(
                title = "Security & Dev Tools",
                onMiniSettings = onMiniSettings
            )

            // Search Bar
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                SearchInputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search 35+ decoders, hashes, ciphers...",
                    onClear = { searchQuery = "" }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedCategory == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { selectedCategory = null }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text("All Tools", color = if (selectedCategory == null) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                ToolCategory.values().forEach { cat ->
                    val isCatSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCatSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text("${cat.icon} ${cat.displayName}", color = if (isCatSelected) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tools Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTools, key = { it.id }) { tool ->
                    GlassCard(
                        modifier = Modifier.height(120.dp),
                        cornerRadius = 14.dp,
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        onClick = { activeTool = tool }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tool.category.icon, fontSize = 20.sp)
                                Text(
                                    tool.category.displayName,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Column {
                                Text(
                                    text = tool.title,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = tool.description,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Interactive Tool Execution View
            val tool = currentTool
            var inputText by remember(tool.id) {
                mutableStateOf(
                    when (tool.id) {
                        "jwt-decoder" -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMzM3IiwibmFtZSI6IlJlcXVlc3RyIFVzZXIiLCJyb2xlIjoiYWRtaW4iLCJpYXQiOjE1MTYyMzkwMjJ9.4zC"
                        "json-formatter" -> "{\"name\":\"requestr\",\"active\":true,\"features\":[\"mitm\",\"frida\",\"rest\"]}"
                        "url-encoder" -> "https://api.example.com/search?query=hello world & security=100%"
                        "base64" -> "requestr Cyber Tool"
                        "md5" -> "admin123"
                        "sha256" -> "cyber_secret_payload"
                        "hex-converter" -> "Hello Android"
                        "ip-calculator" -> "192.168.1.100/24"
                        else -> "Sample input text..."
                    }
                )
            }
            var outputText by remember { mutableStateOf("") }
            var isEncodeMode by remember { mutableStateOf(true) }

            val calculatedOutput = remember(inputText, isEncodeMode, tool.id) {
                executeToolLogic(tool.id, inputText, isEncodeMode)
            }
            outputText = calculatedOutput

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activeTool = null }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "${tool.category.icon} ${tool.title}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(tool.description, color = TextSecondary, fontSize = 11.sp)
                    }
                }

                if (tool.id in listOf("base64", "url-encoder", "html-entity", "hex-converter")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { isEncodeMode = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isEncodeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text("Encode / Convert", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { isEncodeMode = false },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (!isEncodeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text("Decode / Reverse", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text("Input Data:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Result / Output:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { /* copy */ },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Output", color = TextPrimary, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    backgroundColor = Color(0xFF070B14)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(
                                outputText,
                                color = MaterialTheme.colorScheme.secondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

fun executeToolLogic(toolId: String, input: String, isEncode: Boolean): String {
    return try {
        when (toolId) {
            "jwt-decoder" -> ToolsRepository.decodeJwt(input)
            "json-formatter" -> ToolsRepository.formatJson(input)
            "base64" -> if (isEncode) ToolsRepository.encodeBase64(input) else ToolsRepository.decodeBase64(input)
            "url-encoder" -> if (isEncode) ToolsRepository.urlEncode(input) else ToolsRepository.urlDecode(input)
            "md5" -> ToolsRepository.hashMd5(input)
            "sha256" -> ToolsRepository.hashSha256(input)
            "sha1" -> ToolsRepository.hashSha1(input)
            "hex-converter" -> if (isEncode) ToolsRepository.textToHex(input) else ToolsRepository.hexToText(input)
            "html-entity" -> if (isEncode) ToolsRepository.htmlEntityEncode(input) else ToolsRepository.htmlEntityDecode(input)
            "ip-calculator" -> ToolsRepository.calculateSubnet(input)
            "uuid-gen" -> "Generated UUIDv4:\n${ToolsRepository.generateUuid()}\n${ToolsRepository.generateUuid()}\n${ToolsRepository.generateUuid()}"
            "random-string" -> "Random Secure Token (32 chars):\n${ToolsRepository.generateRandomString(32)}"
            "lorem-ipsum" -> ToolsRepository.generateLoremIpsum(3)
            "sql-formatter" -> ToolsRepository.formatSql(input)
            else -> "Output generated for $toolId:\n${input.reversed()}"
        }
    } catch (e: Exception) {
        "Error evaluating tool: ${e.localizedMessage}"
    }
}
