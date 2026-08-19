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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ApiRequestItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import com.example.data.models.NoteItem
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.FlowRepository
import com.example.data.repository.NotesRepository
import com.example.data.repository.SettingsRepository
import com.example.data.interception.CaptureLogRepository
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.repository.TrafficInterceptorRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.MethodBadge
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.SearchInputField
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
    val assistants by ChatRepository.assistants.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

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
                placeholder = "Search APIs, notes, chats, tools...",
                onClear = { searchQuery = "" }
            )
        }

        // Quick Action Chips Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(
                    label = "+ New API",
                    icon = Icons.Default.Public,
                    onClick = { onNavigateToSection("apis") }
                )
                QuickActionChip(
                    label = "+ Intercept",
                    icon = Icons.Default.Radar,
                    onClick = { onNavigateToSection("intercept") }
                )
                QuickActionChip(
                    label = "+ New Tool",
                    icon = Icons.Default.Tune,
                    onClick = { onNavigateToSection("tools") }
                )
                QuickActionChip(
                    label = "+ New Chat",
                    icon = Icons.Default.Chat,
                    onClick = { onNavigateToSection("chats") }
                )
                QuickActionChip(
                    label = "+ New Note",
                    icon = Icons.Default.Notes,
                    onClick = { onNavigateToSection("notes") }
                )
                QuickActionChip(
                    label = "+ New Flow",
                    icon = Icons.Default.Code,
                    onClick = { onNavigateToSection("flow") }
                )
                QuickActionChip(
                    label = "🎵 Music",
                    icon = Icons.Default.MusicNote,
                    onClick = { onNavigateToSection("music") }
                )
            }
        }

        // Intercept Live Status Banner
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (isInterceptionActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                borderColor = if (isInterceptionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                onClick = { onNavigateToSection("intercept") }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Green only when genuinely capturing; muted when never configured.
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when (interceptionState.status) {
                                        InterceptionStatus.ACTIVE -> Color(0xFF22C55E)
                                        InterceptionStatus.ARMED -> Color(0xFFEAB308)
                                        InterceptionStatus.ERROR -> Color(0xFFEF4444)
                                        InterceptionStatus.CONFIGURED_IDLE -> TextMuted
                                        InterceptionStatus.NEVER_CONFIGURED -> TextMuted
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = when (interceptionState.status) {
                                    InterceptionStatus.ACTIVE -> "Interception: ACTIVE"
                                    InterceptionStatus.ARMED -> "Interception: ARMED"
                                    InterceptionStatus.ERROR -> "Interception: ERROR"
                                    InterceptionStatus.CONFIGURED_IDLE -> "Interception is off"
                                    InterceptionStatus.NEVER_CONFIGURED -> "Set up Interception"
                                },
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                            // Real port, method, and packet count from the live session only.
                            Text(
                                text = when (interceptionState.status) {
                                    InterceptionStatus.ACTIVE ->
                                        "${interceptionState.methodLabel} · " +
                                            "${interceptionState.packetCount} packets"
                                    InterceptionStatus.ARMED -> "Pre-flight passed · tap to confirm"
                                    InterceptionStatus.ERROR ->
                                        interceptionState.errorText ?: "Capture failed"
                                    InterceptionStatus.CONFIGURED_IDLE -> "Tap to start capturing"
                                    InterceptionStatus.NEVER_CONFIGURED ->
                                        "Install the CA certificate to begin"
                                },
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Text(
                        text = when (interceptionState.status) {
                            InterceptionStatus.ACTIVE -> "Live →"
                            InterceptionStatus.NEVER_CONFIGURED -> "Set up →"
                            else -> "Start →"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Recents Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recently opened",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "See all →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToSection("apis") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // API Recents
                savedApis.take(3).forEach { api ->
                    GlassCard(
                        modifier = Modifier
                            .width(170.dp)
                            .height(115.dp),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            ApiCollectionsRepository.setActiveRequest(api)
                            onOpenApiRequest(api)
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MethodBadge(method = api.method)
                                Text("API", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = api.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                            Text(
                                text = api.url.removePrefix("https://").removePrefix("http://"),
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Note Recents
                notes.take(2).forEach { note ->
                    GlassCard(
                        modifier = Modifier
                            .width(170.dp)
                            .height(115.dp),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { onOpenNote(note) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📝 Note · ${note.format.name}",
                                color = Color(note.folderColorHex),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = note.title,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                            Text(
                                text = note.content.take(40).replace("\n", " "),
                                color = TextMuted,
                                fontSize = 10.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        // Main Navigation Cards Grid (2-column layout)
        item {
            Text(
                text = "Power Modules",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Row 1: Intercept & APIs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MainSectionCard(
                        title = "Intercept",
                        subtitle = "MITM VPN, Frida & Rules",
                        badge = if (capturedCount == 0) "Not set up" else "$capturedCount captured",
                        icon = "📡",
                        gradient = listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("intercept") }
                    )
                    MainSectionCard(
                        title = "APIs",
                        subtitle = "Test & organize endpoints",
                        badge = "${savedApis.size} saved",
                        icon = "🌐",
                        gradient = listOf(Color(0xFF065F46), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("apis") }
                    )
                }

                // Row 2: Tools & Chats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MainSectionCard(
                        title = "Tools",
                        subtitle = "Decoders, ciphers & hashes",
                        badge = "35+ tools",
                        icon = "🔧",
                        gradient = listOf(Color(0xFF78350F), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("tools") }
                    )
                    MainSectionCard(
                        title = "Chats",
                        subtitle = "AI reverse engineer assistant",
                        badge = "${assistants.size} active",
                        icon = "💬",
                        gradient = listOf(Color(0xFF581C87), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("chats") }
                    )
                }

                // Row 3: Notes & Flow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MainSectionCard(
                        title = "Notes",
                        subtitle = "Markdown, HTML & checklists",
                        badge = "${notes.size} docs",
                        icon = "📝",
                        gradient = listOf(Color(0xFF312E81), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("notes") }
                    )
                    MainSectionCard(
                        title = "Flow",
                        subtitle = "Node-based automation",
                        badge = "Graph active",
                        icon = "🗺️",
                        gradient = listOf(Color(0xFF831843), Color(0xFF0F172A)),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToSection("flow") }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
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
