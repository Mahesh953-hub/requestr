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
 * Floating glassmorphic nav pill.
 *
 * - No parent block — pill is the only rendered element
 * - Glassy surface: gradient with alpha, hairline border, soft shadow
 * - Swipe up on pill → collapse to icon+label (width = chip)
 * - Tap collapsed pill → expand and STAY OPEN
 * - Scroll-driven collapse via external signal
 * - No ripple indication — only color feedback
 * - Smooth spring animations throughout
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

    var collapsed by remember { mutableStateOf(false) }
    val progressAnim = remember { Animatable(0f) }
    val progress by animateFloatAsState(progressAnim.value, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "p")

    // Scroll-driven: collapse on scroll-down, expand on scroll-up
    if (scrollCollapseSignal && !collapsed) {
        scope.launch { progressAnim.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)); collapsed = true }
    }
    if (scrollExpandSignal && collapsed) {
        scope.launch { progressAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)); collapsed = false }
    }

    val f = progress.coerceIn(0f, 1f)

    // When fully collapsed, wrap content; otherwise fill
    val widthMod = if (f > 0.95f) Modifier.wrapContentWidth() else Modifier.fillMaxWidth()

    Box(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .then(widthMod),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .then(widthMod)
                .height(48.dp)
                .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.04f), spotColor = Color.Black.copy(alpha = 0.06f))
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
                    ))
                )
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f), RoundedCornerShape(24.dp))
                // Collapsed-state tap: re-expand on tap anywhere inside the pill
                .pointerInput(collapsed) {
                    if (collapsed) {
                        detectTapGestures {
                            scope.launch { progressAnim.animateTo(0f, spring()); collapsed = false }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (f > 0.90f) {
                CollapsedChip(NAV[activeIdx])
            } else {
                ExpandedRow(NAV, activeIdx, f) { route -> onNavigate(route) }
            }
        }
    }
}

@Composable
private fun ExpandedRow(dests: List<NavDestination>, activeIdx: Int, progress: Float, onNav: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).height(IntrinsicSize.Min),
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
    val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(180))
    val fg by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, tween(180))
    Box(
        Modifier.alpha(alpha).clip(RoundedCornerShape(20.dp)).background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = dest.icon, contentDescription = dest.label, tint = fg, modifier = Modifier.size(17.dp))
            if (active) { Spacer(Modifier.width(5.dp)); Text(dest.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CollapsedChip(dest: NavDestination) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { /* handled by parent */ }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = dest.icon, contentDescription = dest.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(dest.label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── Music Mini-Player (independent) ─────────────────────────────

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
        Modifier.padding(horizontal = 8.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
            )))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(
                Brush.linearGradient(listOf(Color(track.albumCoverHex), MaterialTheme.colorScheme.primary))
            ), contentAlignment = Alignment.Center) { Text("🎧", fontSize = 16.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(track.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            if (isPlaying) { IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) { Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) } }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) { Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }
    }
}
