package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class NavDestination(val route: String, val label: String, val icon: ImageVector)

private val NAV = listOf(
    NavDestination("home", "Home", Icons.Default.Home),
    NavDestination("intercept", "Intercept", Icons.Default.Radar),
    NavDestination("apis", "APIs", Icons.Default.Public),
    NavDestination("tools", "Tools", Icons.Default.Tune),
    NavDestination("flow", "Flow", Icons.Default.Code),
    NavDestination("music", "Music", Icons.Default.MusicNote)
)

/**
 * Rebranded Dynamic iOS-style Frosted Glass Collapsible Navigation Pill.
 *
 * - Smooth spring-driven collapse/expand
 * - Android 17+ Pixel dynamic colors & active pill halo
 * - Haptic feedback on tap
 * - Active glowing dot indicator
 */
@Composable
fun CollapsibleNavPill(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    scrollCollapseSignal: Boolean = false,
    scrollExpandSignal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeIdx = NAV.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHapticFeedback()

    var collapsed by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val progressAnim = remember { Animatable(0f) }
    val progress by animateFloatAsState(
        progressAnim.value,
        spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "pillProgress"
    )

    // Scroll-driven: collapse on scroll-down, expand on scroll-up
    if (scrollCollapseSignal && !collapsed) {
        scope.launch {
            progressAnim.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 400f))
            collapsed = true
        }
    }
    if (scrollExpandSignal && collapsed) {
        scope.launch {
            progressAnim.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
            collapsed = false
        }
    }

    val f = progress.coerceIn(0f, 1f)
    val widthMod = if (f > 0.92f) Modifier.wrapContentWidth() else Modifier.fillMaxWidth()

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(widthMod),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .then(widthMod)
                .height(52.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.15f)
                )
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
                        )
                    )
                )
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    RoundedCornerShape(26.dp)
                )
                .pointerInput(collapsed) {
                    if (!collapsed) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffset < -20f) {
                                    scope.launch { progressAnim.animateTo(1f, spring()); collapsed = true }
                                } else {
                                    scope.launch { progressAnim.animateTo(0f, spring()) }
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragOffset = 0f
                                scope.launch { progressAnim.animateTo(0f, spring()) }
                            },
                            onVerticalDrag = { _, amt ->
                                dragOffset += amt
                                scope.launch {
                                    progressAnim.snapTo((progressAnim.value - amt / 160f).coerceIn(0f, 1f))
                                }
                            }
                        )
                    } else {
                        detectTapGestures {
                            haptics(HapticFeedbackType.LongPress)
                            scope.launch {
                                progressAnim.animateTo(0f, spring())
                                collapsed = false
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (f > 0.90f) {
                CollapsedChip(NAV[activeIdx])
            } else {
                ExpandedRow(NAV, activeIdx, f) { route ->
                    haptics(HapticFeedbackType.LongPress)
                    onNavigate(route)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRow(
    dests: List<NavDestination>,
    activeIdx: Int,
    progress: Float,
    onNav: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        dests.forEachIndexed { i, d ->
            val a = if (i == activeIdx) 1f else (1f - progress * 1.3f).coerceIn(0f, 1f)
            if (a > 0.03f) PillItem(d, i == activeIdx, a) { onNav(d.route) }
        }
    }
}

@Composable
private fun PillItem(dest: NavDestination, active: Boolean, alpha: Float, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        else Color.Transparent,
        tween(220),
        label = "pillItemBg"
    )
    val fg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220),
        label = "pillItemFg"
    )

    Box(
        Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            if (active) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = dest.label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun CollapsedChip(dest: NavDestination) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = dest.icon,
            contentDescription = dest.label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = dest.label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─── Music Mini-Player (Dynamic iOS Glass Capsule) ─────────────────────────────

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
    val haptics = rememberAppHapticFeedback()

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)
                    )
                )
            )
            .border(
                0.6.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = {
                haptics(HapticFeedbackType.LongPress)
                onExpand()
            })
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(track.albumCoverHex), MaterialTheme.colorScheme.primary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("🎧", fontSize = 16.sp)
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = track.artist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = {
                    haptics(HapticFeedbackType.LongPress)
                    onTogglePlay()
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (isPlaying) {
                IconButton(
                    onClick = {
                        haptics(HapticFeedbackType.LongPress)
                        onNext()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = {
                    haptics(HapticFeedbackType.LongPress)
                    onClose()
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
