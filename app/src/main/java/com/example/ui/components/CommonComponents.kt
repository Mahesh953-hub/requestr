package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.models.HttpMethod
import com.example.data.models.MusicTrackItem
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TrafficInterceptorRepository
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.MethodDelete
import com.example.ui.theme.MethodGet
import com.example.ui.theme.MethodHead
import com.example.ui.theme.MethodOptions
import com.example.ui.theme.MethodPatch
import com.example.ui.theme.MethodPost
import com.example.ui.theme.MethodPut
import com.example.ui.theme.MethodWs
import com.example.ui.theme.Status2xx
import com.example.ui.theme.Status3xx
import com.example.ui.theme.Status4xx
import com.example.ui.theme.Status5xx

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    val appliedRadius = cornerRadius ?: settings.cornerRadiusDp.dp
    val shape = RoundedCornerShape(appliedRadius)

    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
    } else {
        modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
    }

    Box(modifier = cardModifier) {
        content()
    }
}

/**
 * Editorial Luxury Wordmark for "requestr."
 * captures Lumora's elegant editorial personality:
 * high-contrast serif, slightly italic, narrow proportions, generous rhythm,
 * soft curves, sharp hairlines, and an alive signature dot tied to interceptor state.
 */
@Composable
fun RequestrWordmark(
    modifier: Modifier = Modifier,
    fontSize: Int = 22,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    onClick: (() -> Unit)? = null
) {
    val isCapturing by TrafficInterceptorRepository.isCapturing.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    val dotColor = if (isCapturing) InterceptorActiveGreen else InterceptorInactiveRed

    Row(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "requestr",
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
            fontSize = fontSize.sp,
            letterSpacing = 0.8.sp,
            color = textColor
        )

        Spacer(modifier = Modifier.width(2.dp))

        // Signature Alive Dot
        Box(
            modifier = Modifier
                .padding(top = (fontSize * 0.25).dp)
                .size((fontSize * 0.24).coerceAtLeast(5.0).dp)
                .scale(if (isCapturing) dotPulse else 1.0f)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
fun ThemeToggleButton(
    modifier: Modifier = Modifier
) {
    val settings by SettingsRepository.settings.collectAsState()
    val isLight = settings.themeMode.equals("EDITORIAL_LIGHT", ignoreCase = true) || settings.themeMode.equals("LIGHT", ignoreCase = true)

    IconButton(
        onClick = {
            if (isLight) {
                SettingsRepository.updateTheme("MONOCHROME_DARK")
            } else {
                SettingsRepository.updateTheme("EDITORIAL_LIGHT")
            }
        },
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = if (isLight) Icons.Default.DarkMode else Icons.Default.LightMode,
            contentDescription = if (isLight) "Switch to Dark Theme" else "Switch to Light Theme",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TopEditorialBar(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Navigation Drawer",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            RequestrWordmark(fontSize = 22)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemeToggleButton()
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MethodBadge(
    method: HttpMethod,
    modifier: Modifier = Modifier,
    fontSize: Int = 11
) {
    val color = when (method) {
        HttpMethod.GET -> MethodGet
        HttpMethod.POST -> MethodPost
        HttpMethod.PUT -> MethodPut
        HttpMethod.DELETE -> MethodDelete
        HttpMethod.PATCH -> MethodPatch
        HttpMethod.WS -> MethodWs
        HttpMethod.HEAD -> MethodHead
        HttpMethod.OPTIONS -> MethodOptions
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = method.name,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun StatusCodeBadge(
    statusCode: Int,
    statusMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val color = when {
        statusCode in 200..299 -> Status2xx
        statusCode in 300..399 -> Status3xx
        statusCode in 400..499 -> Status4xx
        statusCode >= 500 -> Status5xx
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val text = if (statusMessage.isNullOrBlank()) "$statusCode" else "$statusCode $statusMessage"

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ProtocolBadge(protocol: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = protocol.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onMiniSettings: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (actions != null) {
                actions()
            }
            ThemeToggleButton()
            if (onMiniSettings != null) {
                IconButton(onClick = onMiniSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Quick Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty() && onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Mini Player Persistent Bar with Close Action
 * Dismisses and halts playback on tapping close.
 */
@Composable
fun MiniPlayerPersistentBar(
    track: MusicTrackItem,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        cornerRadius = 14.dp,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        borderColor = MaterialTheme.colorScheme.outline,
        onClick = onExpand
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(track.albumCoverHex),
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎧", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = track.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = track.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (isPlaying) {
                    IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Player",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Hovered Floating Pill Navigation Bar
 * Replaces standard bottom bar with floating frosted pill dock.
 * All modules (Home -> Dashboard, Proxy, APIs, Tools, AI, Notes, Flow, Music)
 * have instant tap navigation and lively active pill indicators.
 */
@Composable
fun HoveredPillNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navItems = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("intercept", "Proxy", Icons.Default.Radar),
        Triple("apis", "APIs", Icons.Default.Public),
        Triple("tools", "Tools", Icons.Default.Tune),
        Triple("chats", "AI", Icons.Default.Chat),
        Triple("notes", "Notes", Icons.Default.Notes),
        Triple("flow", "Flow", Icons.Default.Code),
        Triple("music", "Music", Icons.Default.MusicNote)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(32.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { (route, label, icon) ->
                val isSelected = currentRoute == route

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            else Color.Transparent
                        )
                        .clickable {
                            if (currentRoute != route) {
                                onNavigate(route)
                            }
                        }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom-Anchored Expandable Pill Menu (Improvement #1)
 * Replaces old navigation bars with an animated expandable pill stack
 */
@Composable
fun ExpandablePillMenu(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 135f else 0f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f),
        label = "pillRotation"
    )

    val menuItems = listOf(
        Triple("home", "All", "✦"),
        Triple("intercept", "Interceptor", "📡"),
        Triple("apis", "APIs", "⚡"),
        Triple("tools", "Tools", "🛠️"),
        Triple("chats", "AI Chats", "💬"),
        Triple("notes", "Notes", "📝"),
        Triple("flow", "Flow", "🔀"),
        Triple("music", "Music", "🎵")
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Expanded vertical stack with animated staggered visibility
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(150)) + slideInVertically(spring(dampingRatio = 0.8f)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(spring(dampingRatio = 0.8f)) { it / 2 }
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                menuItems.reversed().forEach { (route, label, icon) ->
                    val isActive = currentRoute == route
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable {
                                isExpanded = false
                                onNavigate(route)
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(icon, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Center Floating Trigger Pill [ ✦ ]
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable { isExpanded = !isExpanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Expand Menu",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotationAngle)
            )
        }
    }
}

/**
 * Long-press Contextual Action Menu (Improvement #4)
 */
@Composable
fun ContextualActionMenuDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    actions: List<ContextActionItem>
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            cornerRadius = 20.dp,
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outline
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions.forEach { action ->
                        val isEnabled = action.enabled
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .clickable(enabled = isEnabled) {
                                    onDismiss()
                                    action.onClick()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = if (isEnabled) (action.iconTint ?: MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = action.label,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ContextActionItem(
    val label: String,
    val icon: ImageVector,
    val iconTint: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InPageMiniSettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    onNavigateFullSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$title Configuration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            content()

            Spacer(modifier = Modifier.height(16.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                onClick = onNavigateFullSettings
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Full App Settings →",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
