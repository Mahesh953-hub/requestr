package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── State machine ───────────────────────────────────────────────

enum class NavPillState { Expanded, Collapsing, Collapsed, Expanding }

data class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val NAV_DESTINATIONS = listOf(
    NavDestination("home", "Home", Icons.Default.Home),
    NavDestination("intercept", "Intercept", Icons.Default.Radar),
    NavDestination("apis", "APIs", Icons.Default.Public),
    NavDestination("tools", "Tools", Icons.Default.Tune),
    NavDestination("flow", "Flow", Icons.Default.Code),
    NavDestination("music", "Music", Icons.Default.MusicNote)
)

// ─── Main composable ─────────────────────────────────────────────

/**
 * Floating glassmorphic navigation pill per requestr_nav_pill_spec.md.
 *
 * States: Expanded → (swipe up / scroll down) → Collapsing → Collapsed
 *         Collapsed → (tap / scroll up) → Expanding → Expanded
 *
 * The music mini-player row above is rendered by the caller as an
 * independent element — the pill never controls it.
 */
@Composable
fun CollapsibleNavPill(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    scrollSignalCollapse: Boolean = false,
    scrollSignalExpand: Boolean = false,
    modifier: Modifier = Modifier
) {
    var pillState by remember { mutableStateOf(NavPillState.Expanded) }
    val coroutineScope = rememberCoroutineScope()
    val collapseProgress = remember { Animatable(0f) }

    // Gesture-driven drag offset (for manual swipe)
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // ── React to scroll signals ──────────────────────────────
    // scrollSignalCollapse: content is scrolling down → auto-collapse
    if (scrollSignalCollapse && pillState == NavPillState.Expanded) {
        pillState = NavPillState.Collapsing
        coroutineScope.launch {
            collapseProgress.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            pillState = NavPillState.Collapsed
        }
    }
    // scrollSignalExpand: content stopped / scrolling up → auto-expand
    if (scrollSignalExpand && pillState == NavPillState.Collapsed) {
        pillState = NavPillState.Expanding
        coroutineScope.launch {
            collapseProgress.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            pillState = NavPillState.Expanded
        }
    }

    // ── Animated progress for width + opacity ─────────────────
    val progress by animateFloatAsState(
        targetValue = collapseProgress.value,
        animationSpec = if (pillState == NavPillState.Collapsing || pillState == NavPillState.Expanding) {
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
        } else {
            tween(0)
        },
        label = "collapseAnim"
    )

    val activeIndex = NAV_DESTINATIONS.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // ── Pill width: full (~screen - 32dp) → collapsed (~140dp) ──
    val fullPillWidth = 360 // will use fillMaxWidth; this is conceptual
    val collapsedPillWidth = 140
    val pillWidth = androidx.compose.ui.unit.Dp(
        fullPillWidth - (progress * (fullPillWidth - collapsedPillWidth))
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // ── The pill: glassmorphic surface ────────────────────
        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(52.dp)
                .shadow(
                    elevation = (6f * (1f - progress * 0.5f)).dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
                .clip(RoundedCornerShape(26.dp))
                .background(
                    // Glassmorphic: semi-transparent surface with blur feel
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
                        )
                    )
                )
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(26.dp)
                )
                .pointerInput(pillState) {
                    when (pillState) {
                        NavPillState.Expanded -> {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset < -30) {
                                        pillState = NavPillState.Collapsing
                                        coroutineScope.launch {
                                            collapseProgress.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                                            pillState = NavPillState.Collapsed
                                        }
                                    }
                                    dragOffset = 0f
                                },
                                onDragCancel = { dragOffset = 0f },
                                onVerticalDrag = { _, amt ->
                                    dragOffset += amt
                                    val raw = (collapseProgress.value - amt / 200f).coerceIn(0f, 1f)
                                    coroutineScope.launch { collapseProgress.snapTo(raw) }
                                    if (raw > 0f) pillState = NavPillState.Collapsing
                                }
                            )
                        }
                        NavPillState.Collapsed -> {
                            detectTapGestures {
                                pillState = NavPillState.Expanding
                                coroutineScope.launch {
                                    collapseProgress.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                                    pillState = NavPillState.Expanded
                                }
                            }
                        }
                        else -> {}
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (pillState == NavPillState.Collapsed || pillState == NavPillState.Expanding) {
                // ── Collapsed: only active icon+label ────────
                CollapsedPillContent(NAV_DESTINATIONS[activeIndex])
            } else {
                // ── Expanded / Collapsing: full row ─────────
                ExpandedPillContent(
                    destinations = NAV_DESTINATIONS,
                    activeIndex = activeIndex,
                    progress = progress,
                    onNavigate = onNavigate
                )
            }
        }
    }
}

// ─── Expanded pill content ───────────────────────────────────────

@Composable
private fun ExpandedPillContent(
    destinations: List<NavDestination>,
    activeIndex: Int,
    progress: Float,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        destinations.forEachIndexed { index, dest ->
            val isActive = index == activeIndex
            val itemAlpha = if (isActive) 1f else (1f - progress).coerceIn(0f, 1f)
            val itemScale = if (isActive) 1f else (1f - progress * 0.6f).coerceIn(0.4f, 1f)

            if (itemAlpha > 0.05f) {
                NavPillItem(
                    dest = dest,
                    isActive = isActive,
                    alpha = itemAlpha,
                    scale = itemScale,
                    onClick = { onNavigate(dest.route) }
                )
            }
        }
    }
}

@Composable
private fun NavPillItem(
    dest: NavDestination,
    isActive: Boolean,
    alpha: Float,
    scale: Float,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        else Color.Transparent,
        tween(200), label = "itBg"
    )
    val iconColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(200), label = "itIcon"
    )
    val labelColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0f),
        tween(200), label = "itLabel"
    )

    Box(
        modifier = Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .clickable(enabled = !isActive) { onClick() }
            .padding(
                horizontal = if (isActive) 11.dp else 9.dp,
                vertical = 7.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = iconColor,
                modifier = Modifier.size((17f * scale).dp)
            )
            AnimatedLabel(
                visible = isActive,
                text = dest.label,
                color = labelColor
            )
        }
    }
}

@Composable
private fun AnimatedLabel(visible: Boolean, text: String, color: Color) {
    if (visible) {
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ─── Collapsed pill content ──────────────────────────────────────

@Composable
private fun CollapsedPillContent(dest: NavDestination) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = dest.label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ─── Independent Music Mini-Player ───────────────────────────────

/**
 * Music mini-player floating above the nav pill.
 * Per spec §1 Context B: "Music mini player is independent and always
 * visible if playing." Rendered by the caller — never controlled by
 * the nav pill's state.
 */
@Composable
fun MusicMiniPlayer(
    track: com.example.data.models.MusicTrackItem,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                        )
                    )
                )
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable { onExpand() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(track.albumCoverHex), MaterialTheme.colorScheme.primary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎧", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(track.artist, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isPlaying) {
                        androidx.compose.material3.IconButton(
                            onClick = onNext,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, "Next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    androidx.compose.material3.IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
