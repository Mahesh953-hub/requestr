package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

import com.example.data.repository.SettingsRepository
import com.example.ui.theme.animateStateColor
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

/** Routes the pill can show, in tab order, before hidden-items filtering. */
fun defaultNavPillRoutes(): List<String> = DEFAULT_NAV.map { it.route }

/** Applies the user's hidden-items setting to the default tab order. */
fun visibleNavPillRoutes(hiddenItems: Collection<String>): List<String> =
    DEFAULT_NAV.map { it.route }.filter { it !in hiddenItems }

/**
 * Index of the tab matching [currentRoute] in [routes], or null when the current screen is not
 * one of the pill's tabs (Notes, Settings, Saved APIs, tool workspaces, ...).
 *
 * Deliberately no `coerceAtLeast(0)` fallback: the previous behaviour lit up Home whenever the
 * route was not a tab, which told the user they were somewhere they were not.
 */
fun resolveActiveNavIndex(routes: List<String>, currentRoute: String): Int? =
    routes.indexOf(currentRoute).takeIf { it >= 0 }

/**
 * v6.0 transplant — Rebranded Dynamic iOS-style Frosted Glass Navigation Pill.
 *
 * Semantically recreated from main against backup settings (manifest §5.2, DESIGN-RECORD §6).
 * - Floating glass capsule with configurable opacity, glass mode, and hidden items
 * - Spring-driven animated tab transitions with active dot indicator
 * - Haptic tactile response on tab selection
 * - Scroll-driven collapse/expand preserved from backup (backup behavior wins functional conflicts)
 * - Glass look resolved once through [rememberGlassSurface] (DESIGN-SYSTEM §14.7): six modes,
 *   real backdrop blur + refraction on API 31+ when a [backdrop] is supplied
 *
 * @param backdrop the content layer beneath the pill to blur/refract. MainActivity supplies the
 *                 state of the [GlassBackdropSource] wrapping the NavHost; null keeps the
 *                 gradient-only look.
 */
@Composable
fun CollapsibleNavPill(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    scrollCollapseSignal: Boolean = false,
    scrollExpandSignal: Boolean = false,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current
) {
    val haptics = rememberAppHapticFeedback()
    val settings by SettingsRepository.settings.collectAsState()
    val hiddenItems = settings.navBarHiddenItems

    val activeNavList = remember(hiddenItems) {
        DEFAULT_NAV.filter { it.route !in hiddenItems }
    }
    val activeIdx = resolveActiveNavIndex(activeNavList.map { it.route }, currentRoute)

    // Backup scroll-collapse behavior preserved. One Animatable drives everything; the specs are
    // resolved in composition so the accessibility gating (animations off / reduced) applies.
    val scope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf(false) }
    val progressAnim = remember { Animatable(0f) }
    val collapseSpec = scaledSpring<Float>(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)
    val expandSpec = scaledSpring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
    val tapExpandSpec = scaledSpring<Float>()

    LaunchedEffect(scrollCollapseSignal, scrollExpandSignal) {
        if (scrollCollapseSignal && !collapsed) {
            collapsed = true
            progressAnim.animateTo(1f, collapseSpec)
        } else if (scrollExpandSignal && collapsed) {
            collapsed = false
            progressAnim.animateTo(0f, expandSpec)
        }
    }

    val f = progressAnim.value.coerceIn(0f, 1f)
    val widthMod = if (f > 0.95f) Modifier.wrapContentWidth() else Modifier.fillMaxWidth()

    val glass = rememberGlassSurface(GlassRole.NAV, backdrop)
    val pillShape = RoundedCornerShape(28.dp)
    val expandInteraction = remember { MutableInteractionSource() }

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
                .glassSurface(
                    surface = glass,
                    shape = pillShape,
                    defaultElevation = 12.dp,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.25f),
                    borderWidth = 0.9.dp
                )
                .clickable(
                    enabled = collapsed,
                    interactionSource = expandInteraction,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = "Expand navigation"
                ) {
                    collapsed = false
                    scope.launch { progressAnim.animateTo(0f, tapExpandSpec) }
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (f > 0.90f && activeNavList.isNotEmpty()) {
                CollapsedChip(activeIdx?.let { activeNavList[it] })
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

/** Active-tab tint: mockup `.nav-pill .tab.active` = primary @ 0.12–0.20; delta spec (f) fixes 0.20. */
private const val NAV_TAB_ACTIVE_TINT_ALPHA = 0.20f
private const val NAV_TAB_ACTIVE_BORDER_ALPHA = 0.40f

/**
 * One tab of the nav pill. The active tab is itself a small tinted glass shape that expands to
 * show its label: tint, border, corner radius, elevation and width all animate on the same
 * gated spring so a tab change reads as one motion.
 *
 * Deviation from delta spec (f) `if (isActive) 8.dp else 4.dp`: an inactive tab has a fully
 * transparent background, and a 4dp shadow under nothing renders as a floating grey smudge.
 * Inactive elevation is therefore 0 and the active tab lifts to 6dp.
 */
@Composable
private fun NavPillTab(
    dest: NavDestination,
    isActive: Boolean,
    onClick: () -> Unit,
    contentAlpha: Float = 1f
) {
    // Theme colours are resolved at read time (animateStateColor), so a theme switch repaints the
    // tabs on the next frame while an active-tab change still eases on the gated spring.
    val colorSpec = scaledSpring<Float>(dampingRatio = 0.75f, stiffness = 350f)
    val bgAnim by animateStateColor(
        active = isActive,
        activeColor = MaterialTheme.colorScheme.primary.copy(alpha = NAV_TAB_ACTIVE_TINT_ALPHA),
        inactiveColor = Color.Transparent,
        animationSpec = colorSpec,
        label = "tabBg"
    )
    val fgAnim by animateStateColor(
        active = isActive,
        activeColor = MaterialTheme.colorScheme.primary,
        inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        animationSpec = colorSpec,
        label = "tabFg"
    )
    val borderAnim by animateStateColor(
        active = isActive,
        activeColor = MaterialTheme.colorScheme.primary.copy(alpha = NAV_TAB_ACTIVE_BORDER_ALPHA),
        inactiveColor = Color.Transparent,
        animationSpec = colorSpec,
        label = "tabBorder"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 1.0f,
        animationSpec = scaledSpring(dampingRatio = 0.65f, stiffness = 400f),
        label = "tabScale"
    )
    val dpSpec = scaledSpring<Dp>(dampingRatio = 0.75f, stiffness = 350f)
    val cornerAnim by animateDpAsState(
        targetValue = if (isActive) 24.dp else 10.dp,
        animationSpec = dpSpec,
        label = "tabCorner"
    )
    val elevationAnim by animateDpAsState(
        targetValue = if (isActive) 6.dp else 0.dp,
        animationSpec = dpSpec,
        label = "tabElevation"
    )
    val labelSpec = scaledSpring<Float>(dampingRatio = 0.75f, stiffness = 400f)
    val labelSizeSpec = scaledSpring<IntSize>(dampingRatio = 0.75f, stiffness = 400f)
    val contentSizeSpec = scaledSpring<IntSize>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val tabShape = RoundedCornerShape(cornerAnim)

    Box(
        modifier = Modifier
            .alpha(contentAlpha)
            .scale(scaleAnim)
            .animateContentSize(animationSpec = contentSizeSpec)
            .shadow(
                elevation = elevationAnim,
                shape = tabShape,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clip(tabShape)
            .background(bgAnim)
            .border(width = 1.dp, color = borderAnim, shape = tabShape)
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
                enter = fadeIn(animationSpec = labelSpec) + expandHorizontally(animationSpec = labelSizeSpec),
                exit = fadeOut(animationSpec = labelSpec) + shrinkHorizontally(animationSpec = labelSizeSpec)
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

/**
 * Collapsed-state chip. With an active tab it names that tab; on a non-tab route (Notes, ...)
 * it degrades to the mockup's `nav-pill-collapsed` chevron-up affordance so the pill never
 * claims a destination the user is not on.
 */
@Composable
private fun CollapsedChip(dest: NavDestination?) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dest != null) {
            Icon(imageVector = dest.icon, contentDescription = dest.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text(dest.label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
        } else {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Expand navigation",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp)
            )
        }
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
