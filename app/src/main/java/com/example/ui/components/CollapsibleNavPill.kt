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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ─── Data ────────────────────────────────────────────────────────

data class NavDestination(val route: String, val label: String, val icon: ImageVector)

private val NAV = listOf(
    NavDestination("home", "Home", Icons.Default.Home),
    NavDestination("intercept", "Intercept", Icons.Default.Radar),
    NavDestination("apis", "APIs", Icons.Default.Public),
    NavDestination("tools", "Tools", Icons.Default.Tune),
    NavDestination("flow", "Flow", Icons.Default.Code),
    NavDestination("music", "Music", Icons.Default.MusicNote)
)

// ─── Main composable ─────────────────────────────────────────────

/**
 * Floating glassmorphic navigation pill.
 *
 * - NO parent container — the pill is the only rendered element.
 * - TAP the collapsed pill → EXPAND only (never toggle-close).
 * - SWIPE up on expanded pill → collapse to icon+label (width hugs the chip).
 * - Collapsed width exactly matches the active chip; no extra dead space.
 * - No gray ripple — only colour feedback on active/inactive states.
 * - Glassy transparent background, hairline border, soft shadow.
 */
@Composable
fun CollapsibleNavPill(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeIdx = NAV.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()

    // ── State: Expanded (full pill) | Collapsed (icon+label chip) ──
    var isCollapsed by remember { mutableStateOf(false) }

    // ── Animated progress: 0 = expanded, 1 = collapsed ─────────────
    val animateProgress = remember { Animatable(0f) }
    val progress by animateFloatAsState(
        targetValue = animateProgress.value,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "pillProgress"
    )

    // Drag offset for gesture tracking
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // ── Width calculation: when collapsed, width hugs the chip ──
    // We can't animate between "wrap" and "full" directly with Dp values,
    // so we use alpha + width constraints.
    // Expanded: fillMaxWidth with side margins
    // Collapsed: wrap the single chip tightly
    val collapsedFraction = progress.coerceIn(0f, 1f)

    // ── Glass pill surface ────────────────────────────────────────
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .then(
                if (collapsedFraction < 0.95f) {
                    // Expanded / transitioning: takes full width with side margins
                    Modifier.fillMaxWidth()
                } else {
                    // Fully collapsed: hug content
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── The pill itself ─────────────────────────────────────
        Box(
            modifier = Modifier
                .then(
                    if (collapsedFraction < 0.95f) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier // wraps to content
                    }
                )
                .height(48.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.08f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(
                    // Glassy translucent — no solid parent block
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        )
                    )
                )
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(24.dp)
                )
                .pointerInput(isCollapsed) {
                    if (!isCollapsed) {
                        // Expanded: swipe up to collapse
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffset < -25f) {
                                    coroutineScope.launch {
                                        animateProgress.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                                        isCollapsed = true
                                    }
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                            onVerticalDrag = { _, amt ->
                                dragOffset += amt
                                coroutineScope.launch {
                                    animateProgress.snapTo((animateProgress.value - amt / 180f).coerceIn(0f, 1f))
                                }
                            }
                        )
                    } else {
                        // Collapsed: TAP to expand (never closes on tap)
                        detectTapGestures {
                            coroutineScope.launch {
                                animateProgress.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                                isCollapsed = false
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isCollapsed && collapsedFraction > 0.9f) {
                // ── Collapsed: single active icon+label chip, width hugs ──
                CollapsedChip(NAV[activeIdx])
            } else {
                // ── Expanded: full row of all items ────────────────
                ExpandedRow(
                    destinations = NAV,
                    activeIdx = activeIdx,
                    progress = collapsedFraction,
                    onNavigate = { route ->
                        onNavigate(route)
                    }
                )
            }
        }
    }
}

// ─── Expanded row ────────────────────────────────────────────────

@Composable
private fun ExpandedRow(
    destinations: List<NavDestination>,
    activeIdx: Int,
    progress: Float,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        destinations.forEachIndexed { idx, dest ->
            val isActive = idx == activeIdx
            // Non-active items fade as progress → 1
            val itemAlpha = if (isActive) 1f else (1f - progress * 1.2f).coerceIn(0f, 1f)

            if (itemAlpha > 0.03f) {
                PillItem(
                    dest = dest,
                    isActive = isActive,
                    alpha = itemAlpha,
                    onClick = { onNavigate(dest.route) }
                )
            }
        }
    }
}

@Composable
private fun PillItem(
    dest: NavDestination,
    isActive: Boolean,
    alpha: Float,
    onClick: () -> Unit
) {
    val activeBg = MaterialTheme.colorScheme.primaryContainer
    val activeFg = MaterialTheme.colorScheme.onPrimaryContainer
    val inactiveFg = MaterialTheme.colorScheme.onSurfaceVariant

    val bg by animateColorAsState(
        if (isActive) activeBg else Color.Transparent,
        tween(200), label = "iBg"
    )
    val fg by animateColorAsState(
        if (isActive) activeFg else inactiveFg,
        tween(200), label = "iFg"
    )

    // NO ripple — just color feedback
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,              // ← NO gray ripple overlay
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = fg,
                modifier = Modifier.size(17.dp)
            )
            if (isActive) {
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = dest.label,
                    color = activeFg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

// ─── Collapsed chip ──────────────────────────────────────────────

@Composable
private fun CollapsedChip(dest: NavDestination) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(
                interactionSource = interactionSource,
                indication = null,              // ← NO gray ripple
                onClick = { /* handled by parent pointerInput */ }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = dest.icon,
            contentDescription = dest.label,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = dest.label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ─── Music Mini-Player (independent, per spec §1-B) ──────────────

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
            .padding(horizontal = 8.dp)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    )
                )
            )
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            ) { Text("🎧", fontSize = 16.sp) }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(track.artist, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }

            IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
            }
            if (isPlaying) {
                IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.SkipNext, "Next",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}
