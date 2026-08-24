package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

import com.example.data.repository.SettingsRepository
import com.example.ui.theme.scaledSpring

data class NavDestination(val route: String, val label: String, val icon: ImageVector)

// v6.0 transplant (manifest §5.2, DESIGN-RECORD §6.5) — default visible destinations.
// Hidden-items filtering reads settings.navBarHiddenItems at runtime.
private val DEFAULT_NAV = listOf(
    NavDestination("home", "Home", Icons.Default.Home),
    NavDestination("intercept", "Intercept", Icons.Default.Radar),
    NavDestination("apis", "APIs", Icons.Default.Public),
    NavDestination("tools", "Tools", Icons.Default.Tune),
    NavDestination("flow", "Flow", Icons.Default.Code),
    NavDestination("music", "Music", Icons.Default.MusicNote)
)

/**
 * v6.0 transplant — Rebranded Dynamic iOS-style Frosted Glass Navigation Pill.
 *
 * Semantically recreated from main against backup settings (manifest §5.2, DESIGN-RECORD §6).
 * - Frosted-glass capsule with configurable opacity, blur style, and hidden items
 * - Spring-driven animated tab transitions with active dot indicator
 * - Haptic tactile response on tab selection
 * - Scroll-driven collapse/expand preserved from backup (backup behavior wins functional conflicts)
 * - Canonical glass-style keys: FROSTED_GLASS / SOLID_M3 / OLED_BLACK / TRANSLUCENT (§11)
 */
@Composable
fun CollapsibleNavPill(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    scrollCollapseSignal: Boolean = false,
    scrollExpandSignal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHapticFeedback()
    val settings by SettingsRepository.settings.collectAsState()
    val opacity = settings.navBarBackgroundOpacity.coerceIn(0.2f, 1f)
    val blurEffect = settings.navBarBlurEffect
    val hiddenItems = settings.navBarHiddenItems

    val activeNavList = remember(hiddenItems) {
        DEFAULT_NAV.filter { it.route !in hiddenItems }
    }
    val activeIdx = activeNavList.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // Backup scroll-collapse behavior preserved.
    val scope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf(false) }
    val progressAnim = remember { Animatable(0f) }
    val progress by animateFloatAsState(progressAnim.value, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "p")

    if (scrollCollapseSignal && !collapsed) {
        scope.launch { progressAnim.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)); collapsed = true }
    }
    if (scrollExpandSignal && collapsed) {
        scope.launch { progressAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)); collapsed = false }
    }

    val f = progress.coerceIn(0f, 1f)
    val widthMod = if (f > 0.95f) Modifier.wrapContentWidth() else Modifier.fillMaxWidth()

    val bgBrush = when (blurEffect) {
        "SOLID_M3" -> Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
        )
        "OLED_BLACK" -> Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = opacity), Color(0xFF0F172A).copy(alpha = opacity))
        )
        "TRANSLUCENT" -> Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surface.copy(alpha = (opacity * 0.7f).coerceIn(0.2f, 1f)),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.5f).coerceIn(0.2f, 1f))
            )
        )
        else -> Brush.verticalGradient( // FROSTED_GLASS
            listOf(
                MaterialTheme.colorScheme.surface.copy(alpha = opacity),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (opacity * 0.95f).coerceIn(0.2f, 1f))
            )
        )
    }

    val borderColor = when (blurEffect) {
        "SOLID_M3" -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        "OLED_BLACK" -> Color.White.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(widthMod),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .then(widthMod)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.25f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(bgBrush)
                .border(0.9.dp, borderColor, RoundedCornerShape(28.dp))
                .pointerInput(collapsed) {
                    if (collapsed) {
                        detectTapGestures {
                            scope.launch { progressAnim.animateTo(0f, spring()); collapsed = false }
                        }
                    }
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (f > 0.90f && activeNavList.isNotEmpty()) {
                CollapsedChip(activeNavList[activeIdx])
            } else {
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    activeNavList.forEach { dest ->
                        val isActive = dest.route == currentRoute
                        val itemAlpha = if (isActive) 1f else (1f - f * 1.3f).coerceIn(0f, 1f)
                        if (itemAlpha > 0.03f) {
                            NavPillTab(
                                dest = dest,
                                isActive = isActive,
                                onClick = {
                                    if (!isActive) {
                                        haptics(HapticFeedbackType.LongPress)
                                        onNavigate(dest.route)
                                    }
                                },
                                contentAlpha = itemAlpha
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPillTab(
    dest: NavDestination,
    isActive: Boolean,
    onClick: () -> Unit,
    contentAlpha: Float = 1f
) {
    val bgAnim by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
        label = "tabBg"
    )
    val fgAnim by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
        label = "tabFg"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 1.0f,
        animationSpec = scaledSpring(dampingRatio = 0.65f, stiffness = 400f),
        label = "tabScale"
    )

    val tabShape = if (isActive) RoundedCornerShape(24.dp) else RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .alpha(contentAlpha)
            .scale(scaleAnim)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .clip(tabShape)
            .background(bgAnim)
            .border(
                width = if (isActive) 0.8.dp else 0.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent,
                shape = tabShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (isActive) 13.dp else 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = fgAnim,
                modifier = Modifier.size(19.dp)
            )
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) +
                        expandHorizontally(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)),
                exit = fadeOut(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) +
                        shrinkHorizontally(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = dest.label,
                        color = fgAnim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
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
}

@Composable
private fun CollapsedChip(dest: NavDestination) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = dest.icon, contentDescription = dest.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(dest.label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
    }
}

// ─── Music Mini-Player (Detached Actionable Thumbnail + Details Bar) ──────────
// v6.0 transplant (manifest §5.8) — uses TrackArtworkView for the thumbnail.
// Backup behavior preserved: toggle/next/close/expand, honest LOCAL-only track data.

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 1. DETACHED ACTIONABLE LEFT ICON / THUMBNAIL ──
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.16f)
                )
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    RoundedCornerShape(16.dp)
                )
                .clickable(onClick = {
                    haptics(HapticFeedbackType.LongPress)
                    onExpand()
                }),
            contentAlignment = Alignment.Center
        ) {
            TrackArtworkView(
                track = track,
                size = 46.dp,
                shape = RoundedCornerShape(16.dp),
                iconSize = 22.dp,
                showBorder = false
            )
        }

        // ── 2. SPACE (DETACHED SEPARATION) ──
        Spacer(modifier = Modifier.width(10.dp))

        // ── 3. MUSIC DETAILS BAR ──
        Box(
            modifier = Modifier
                .weight(1f)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(23.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.14f)
                )
                .clip(RoundedCornerShape(23.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
                        )
                    )
                )
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                    RoundedCornerShape(23.dp)
                )
                .padding(start = 14.dp, end = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title & Artist
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = {
                            haptics(HapticFeedbackType.LongPress)
                            onExpand()
                        })
                ) {
                    Text(
                        text = track.title.ifBlank { "No Track" },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist.ifBlank { "Unknown Artist" },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play / Pause Button
                IconButton(
                    onClick = {
                        haptics(HapticFeedbackType.LongPress)
                        onTogglePlay()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Next Track Button
                IconButton(
                    onClick = {
                        haptics(HapticFeedbackType.LongPress)
                        onNext()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Close / Dismiss Button
                IconButton(
                    onClick = {
                        haptics(HapticFeedbackType.LongPress)
                        onClose()
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
