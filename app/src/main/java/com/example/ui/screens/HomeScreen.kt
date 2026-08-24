package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ApiRequestItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.NoteItem
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.FlowRepository
import com.example.data.repository.NotesRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.ToolsRepository
import com.example.data.search.GlobalSearch
import com.example.data.search.GlobalSearchHit
import com.example.data.interception.CaptureLogRepository
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.SearchInputField
import com.example.ui.components.ThemeToggleButton
import com.example.ui.theme.LocalAnimationsEnabled
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToSection: (String) -> Unit,
    onOpenApiRequest: (ApiRequestItem) -> Unit,
    onOpenTrafficDetail: (CapturedTrafficItem) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenSettings: () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    val savedApis by ApiCollectionsRepository.requests.collectAsState()
    // Real captured-transaction count straight from Room, so the Home badge cannot claim
    // traffic that was never captured (APP_v2.md Bug 1.5).
    val homeContext = LocalContext.current
    val capturedCount by remember(homeContext) {
        CaptureLogRepository.observeCount(homeContext)
    }.collectAsState(initial = 0)
    // Real interception state -- same single source of truth as the Intercept FAB.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    val isInterceptionActive = interceptionState.isActive
    val notes by NotesRepository.notes.collectAsState()
    val flows by FlowRepository.nodes.collectAsState()
    val toolsCatalog = remember { ToolsRepository.getToolsCatalog() }

    var searchQuery by remember { mutableStateOf("") }
    var isRecentsExpanded by remember { mutableStateOf(true) }
    val searchHits = remember(searchQuery, savedApis, notes, toolsCatalog, flows) {
        GlobalSearch.search(searchQuery, apis = savedApis, notes = notes, tools = toolsCatalog, flows = flows)
    }

    // Time-aware greeting
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour in 5..11 -> "Good morning, ${settings.userName} ☀️"
        hour in 12..16 -> "Good afternoon, ${settings.userName} 🌤️"
        hour in 17..21 -> "Good evening, ${settings.userName} 🌙"
        else -> "Good night, ${settings.userName} ⚡"
    }

    val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
    val currentDateStr = dateFormat.format(Date())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = (settings.fabBottomSpacingDp + 16).dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))

            // Top Editorial Brand & Wordmark Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RequestrWordmark(fontSize = 24)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // B13: the theme icon appears ONLY here on Home, immediately to the left of
                    // the avatar. Every other screen's SectionHeader had its own copy removed.
                    ThemeToggleButton()

                    Spacer(modifier = Modifier.width(8.dp))

                    // User Avatar Circle
                    Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = settings.userName.take(2).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Greeting Subtitle
            Column {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = currentDateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Global Search Bar
        item {
            SearchInputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                // "chats" dropped -- Home does not search chat history (that lands with task 12).
                placeholder = "Search APIs, notes, tools, flow nodes...",
                onClear = { searchQuery = "" }
            )
        }

        if (searchQuery.isNotBlank()) {
            if (searchHits.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No matches for \"$searchQuery\"",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                val apiHits = searchHits.filterIsInstance<GlobalSearchHit.Api>()
                val noteHits = searchHits.filterIsInstance<GlobalSearchHit.Note>()
                val toolHits = searchHits.filterIsInstance<GlobalSearchHit.Tool>()
                val flowHits = searchHits.filterIsInstance<GlobalSearchHit.FlowNode>()

                if (apiHits.isNotEmpty()) {
                    item { SearchSectionLabel("APIs") }
                    apiHits.forEach { hit ->
                        item(key = "api-${hit.item.id}") {
                            SearchResultRow(hit) {
                                ApiCollectionsRepository.setActiveRequest(hit.item)
                                onOpenApiRequest(hit.item)
                            }
                        }
                    }
                }
                if (noteHits.isNotEmpty()) {
                    item { SearchSectionLabel("Notes") }
                    noteHits.forEach { hit ->
                        item(key = "note-${hit.item.id}") {
                            SearchResultRow(hit) { onOpenNote(hit.item) }
                        }
                    }
                }
                if (toolHits.isNotEmpty()) {
                    item { SearchSectionLabel("Tools") }
                    toolHits.forEach { hit ->
                        item(key = "tool-${hit.item.id}") {
                            SearchResultRow(hit) { onNavigateToSection("tools") }
                        }
                    }
                }
                if (flowHits.isNotEmpty()) {
                    item { SearchSectionLabel("Flow") }
                    flowHits.forEach { hit ->
                        item(key = "flow-${hit.item.id}") {
                            SearchResultRow(hit) { onNavigateToSection("flow") }
                        }
                    }
                }
            }
        } else {

        // Quick Action Pills Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeQuickActionPill(
                    label = "+ New API",
                    icon = Icons.Default.Public,
                    iconTint = Color(0xFF10B981),
                    onClick = { onNavigateToSection("apis") }
                )
                HomeQuickActionPill(
                    label = "+ Intercept",
                    icon = Icons.Default.Radar,
                    iconTint = Color(0xFFF97316),
                    onClick = { onNavigateToSection("intercept") }
                )
                HomeQuickActionPill(
                    label = "+ New Tool",
                    icon = Icons.Default.Tune,
                    iconTint = Color(0xFFF59E0B),
                    onClick = { onNavigateToSection("tools") }
                )
                HomeQuickActionPill(
                    label = "+ New Note",
                    icon = Icons.Default.Notes,
                    iconTint = Color(0xFF6366F1),
                    onClick = { onNavigateToSection("notes") }
                )
                HomeQuickActionPill(
                    label = "+ New Flow",
                    icon = Icons.Default.Code,
                    iconTint = Color(0xFF4F46E5),
                    onClick = { onNavigateToSection("flow") }
                )
                HomeQuickActionPill(
                    label = "🎵 Music",
                    icon = Icons.Default.MusicNote,
                    iconTint = Color(0xFFDB2777),
                    onClick = { onNavigateToSection("music") }
                )
            }
        }

        // Intercept Live Status Banner
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (isInterceptionActive) {
                    Color(0xFF10B981).copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                borderColor = if (isInterceptionActive) {
                    Color(0xFF10B981).copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                onClick = { onNavigateToSection("intercept") }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Pulsing Radar Indicator
                            PulsingInterceptionIndicator(isActive = isInterceptionActive)

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(
                                text = when (interceptionState.status) {
                                    InterceptionStatus.ACTIVE -> "Interception: ACTIVE"
                                    InterceptionStatus.ARMED -> "Interception: ARMED"
                                    InterceptionStatus.ERROR -> "Interception: ERROR"
                                    InterceptionStatus.CONFIGURED_IDLE -> "Interception is off"
                                    InterceptionStatus.NEVER_CONFIGURED -> "Set up Interception"
                                },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )

                            if (isInterceptionActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Live",
                                        color = Color(0xFF10B981),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                LiveWaveformGraphic()
                            }
                        }

                        // Circular Navigation Arrow
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Open Interceptor",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Detail Line 1: Real method, port & packet count from the live session only.
                    Text(
                        text = when (interceptionState.status) {
                            InterceptionStatus.ACTIVE ->
                                "${interceptionState.methodLabel} · Port ${interceptionState.port} · ${interceptionState.packetCount} packets"
                            InterceptionStatus.ARMED ->
                                "${interceptionState.methodLabel} · Port ${interceptionState.port} · Awaiting confirmation"
                            InterceptionStatus.ERROR ->
                                interceptionState.errorText ?: "Capture service encountered an error"
                            InterceptionStatus.CONFIGURED_IDLE ->
                                "Tap to start live session"
                            InterceptionStatus.NEVER_CONFIGURED ->
                                "Install the CA certificate to begin"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.SansSerif
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Detail Line 2: Real features & connected devices
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "HTTPS Decryption",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text("·", color = MaterialTheme.colorScheme.outline)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Filtering On",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text("·", color = MaterialTheme.colorScheme.outline)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${interceptionState.activeConnections} Connected",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Recents Section (Real Data, Collapsible)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isRecentsExpanded = !isRecentsExpanded }
                ) {
                    Text(
                        text = "Recently opened",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isRecentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isRecentsExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "See all →",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToSection("apis") }
                )
            }

            AnimatedVisibility(
                visible = isRecentsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val recentItems = remember(savedApis, notes) {
                        val list = mutableListOf<RecentRowModel>()
                        savedApis.take(2).forEach { api ->
                            list.add(
                                RecentRowModel(
                                    id = api.id,
                                    type = "API",
                                    subtype = api.method.name,
                                    title = api.name,
                                    preview = api.url.removePrefix("https://").removePrefix("http://"),
                                    timestamp = "Recent",
                                    icon = Icons.Default.Public,
                                    iconColor = Color(0xFF10B981),
                                    onClick = {
                                        ApiCollectionsRepository.setActiveRequest(api)
                                        onOpenApiRequest(api)
                                    }
                                )
                            )
                        }
                        notes.take(2).forEach { note ->
                            list.add(
                                RecentRowModel(
                                    id = note.id,
                                    type = "Note",
                                    subtype = note.format.name,
                                    title = note.title,
                                    preview = note.content.take(40).replace("\n", " "),
                                    timestamp = "Saved",
                                    icon = Icons.Default.Notes,
                                    iconColor = Color(0xFF6366F1),
                                    onClick = { onOpenNote(note) }
                                )
                            )
                        }
                        list.take(4)
                    }

                    if (recentItems.isEmpty()) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No recent items yet. Create an API or Note to get started.",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        recentItems.forEach { item ->
                            RecentItemCard(model = item)
                        }
                    }
                }
            }
        }

        // Power Modules Grid (2-column layout + Flow full width)
        item {
            Text(
                text = "Power Modules",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Row 1: Intercept & APIs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PowerModuleCard(
                        title = "Intercept",
                        subtitle = "MITM VPN, Frida & Rules",
                        badge = if (capturedCount == 0 && !isInterceptionActive) "Not set up" else if (isInterceptionActive) "Live" else "$capturedCount captured",
                        icon = Icons.Default.Radar,
                        accentColor = Color(0xFF6366F1),
                        chips = listOf("Live Traffic", "Rules Engine", "Breakpoints", "SSL Decrypt"),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("intercept") }
                    )
                    PowerModuleCard(
                        title = "APIs",
                        subtitle = "Test & organize endpoints",
                        badge = "${savedApis.size} saved",
                        icon = Icons.Default.Public,
                        accentColor = Color(0xFF0D9488),
                        chips = listOf("Collections", "Environments", "History", "Import"),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("apis") }
                    )
                }

                // Row 2: Tools & Notes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PowerModuleCard(
                        title = "Tools",
                        subtitle = "Decoders, ciphers & hashes",
                        badge = "${toolsCatalog.size}+ tools",
                        icon = Icons.Default.Build,
                        accentColor = Color(0xFFD97706),
                        chips = listOf("Decoders", "Ciphers", "Hashes", "Formatters"),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("tools") }
                    )
                    PowerModuleCard(
                        title = "Notes",
                        subtitle = "Markdown, HTML & checklists",
                        badge = "${notes.size} docs",
                        icon = Icons.Default.Notes,
                        accentColor = Color(0xFFDB2777),
                        chips = listOf("Markdown", "HTML", "Checklists", "Folders"),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("notes") }
                    )
                }

                // Row 3: Flow (Full-Width Card)
                PowerModuleCard(
                    title = "Flow",
                    subtitle = "Node-based automation",
                    badge = if (flows.isNotEmpty()) "Graph active" else "0 nodes",
                    icon = Icons.Default.AccountTree,
                    accentColor = Color(0xFF4F46E5),
                    chips = listOf("Node Graph", "JSON Logic", "Webhooks", "Auto Run"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigateToSection("flow") }
                )
            }
        }

        } // end normal-mode else branch
    }
}

@Composable
fun QuickActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun MainSectionCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = modifier.height(130.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradient.map { it.copy(alpha = 0.4f) }))
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 24.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(badge, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 16.sp
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SearchSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SearchResultRow(hit: com.example.data.search.GlobalSearchHit, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = hit.title,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1
            )
            if (hit.subtitle.isNotBlank()) {
                Text(
                    text = hit.subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private data class RecentRowModel(
    val id: String,
    val type: String,
    val subtype: String,
    val title: String,
    val preview: String,
    val timestamp: String,
    val icon: ImageVector,
    val iconColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun RecentItemCard(model: RecentRowModel) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        onClick = model.onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Category Icon Tile
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(model.iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = model.icon,
                        contentDescription = null,
                        tint = model.iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "${model.type} • ${model.subtype.uppercase()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = model.preview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = model.timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeQuickActionPill(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PulsingInterceptionIndicator(isActive: Boolean) {
    val animationsEnabled = LocalAnimationsEnabled.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )
    val scale = if (animationsEnabled && isActive) animatedScale else 1.0f

    Box(
        modifier = Modifier
            .size(14.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF10B981) else TextMuted)
    )
}

@Composable
private fun LiveWaveformGraphic() {
    val animationsEnabled = LocalAnimationsEnabled.current
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animatedBar1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val animatedBar2 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val animatedBar3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )
    val bar1 = if (animationsEnabled) animatedBar1 else 8f
    val bar2 = if (animationsEnabled) animatedBar2 else 8f
    val bar3 = if (animationsEnabled) animatedBar3 else 8f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar1.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar3.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
    }
}

@Composable
private fun PowerModuleCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    chips: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Row: Icon Tile & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = badge,
                        color = if (badge == "Not set up") Color(0xFFF97316) else MaterialTheme.colorScheme.primary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Title & Subtitle
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Capability Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chips.forEach { chipText ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = chipText,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
