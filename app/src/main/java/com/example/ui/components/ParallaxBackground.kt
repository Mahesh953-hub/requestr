package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.example.ui.theme.rememberEffectiveDurationScale
import com.example.ui.theme.scaledTween
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Design-system parallax base layer (DESIGN brief: "background parallax effect moving subtly
 * independent of foreground content as a base layer for glassmorphism refraction").
 *
 * Hosted exactly once, behind the `Scaffold`/`NavHost` in `MainActivity.RequestrApp`, with the
 * Scaffold's `containerColor` set to `Color.Transparent` so this layer *is* the page background.
 *
 * What it draws — taken from both navigation mockups, whose page background is
 * `radial-gradient(circle at 50% 0%, <accent-tinted lift> 0%, <base> 60–65%)`:
 *  1. a solid `colorScheme.background` fill that never moves (no edge can ever be exposed);
 *  2. a soft radial accent glow anchored at top-centre, fading out by ~65% of the
 *     farthest-corner radius — the mockup gradient, verbatim;
 *  3. a dimmer secondary blob low-left, so the bottom nav pill has something non-uniform under
 *     it to refract (the mockup glow has faded to flat base by the time it reaches the pill).
 *
 * How it moves — only layers 2 and 3, via a single `graphicsLayer` whose translation is read
 * from [ParallaxState] inside the layer block. That read is a snapshot read in the layer phase:
 * the list scrolls → the RenderNode's translation updates. No recomposition, no redraw of the
 * gradients, no allocation per frame. The gradient brushes are built in `drawWithCache` and
 * rebuilt only when the size or the theme colours change.
 *
 * The moving layer is measured oversize by the maximum shift in each axis so a full-travel
 * translation never reveals its edge over the base fill.
 *
 * Accessibility: the hand-over settle and the lateral drift both go through the app's
 * animation settings (`scaledTween` / `rememberEffectiveDurationScale`). With animations off
 * they snap to the end state — the layer never jumps mid-frame or flickers.
 */
@Composable
fun ParallaxBackground(
    modifier: Modifier = Modifier,
    state: ParallaxState = LocalParallaxState.current,
) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.background
    val isDark = base.luminance() < 0.5f
    // Alphas tuned against the mockup deltas (#131E32 over #090D16 dark; #FFF7ED over #F8FAFC
    // light): a lift you feel rather than see. If it draws attention to itself it is wrong.
    val topGlow = scheme.primary.copy(alpha = if (isDark) 0.14f else 0.09f)
    val lowBlob = scheme.secondary.copy(alpha = if (isDark) 0.07f else 0.05f)

    val density = LocalDensity.current
    val maxVerticalPx = with(density) { ParallaxDefaults.MaxVerticalShift.toPx() }
    val maxLateralPx = with(density) { ParallaxDefaults.MaxLateralShift.toPx() }
    SideEffect { state.configure(maxVerticalShiftPx = maxVerticalPx, maxLateralShiftPx = maxLateralPx) }

    // Hand-over continuity: bleed the correction recorded by ParallaxState.attach() down to zero.
    // Duration scale 0 (animations off / NONE) resolves to tween(0) → snaps to the end state.
    val settleMs = (ParallaxDefaults.SettleDurationMs * rememberEffectiveDurationScale()).roundToInt()
    LaunchedEffect(state, state.attachGeneration) {
        state.settleCorrection(tween(durationMillis = settleMs))
    }

    // Lateral drift follows the active tab; animated so it travels *with* the tab slide but
    // over a shorter distance — that difference in travel is the parallax cue.
    val lateralFraction by animateFloatAsState(
        targetValue = state.lateralFraction,
        animationSpec = scaledTween(ParallaxDefaults.LateralDriftDurationMs),
        label = "parallaxLateral",
    )

    Box(modifier = modifier.fillMaxSize().background(base)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .bleed(vertical = maxVerticalPx.roundToInt(), horizontal = maxLateralPx.roundToInt())
                .graphicsLayer {
                    translationY = state.verticalShiftPx()
                    translationX = lateralShiftPx(lateralFraction, maxLateralPx)
                }
                .drawWithCache {
                    // Coordinates are in the oversize child; the visible viewport starts at
                    // (maxLateralPx, maxVerticalPx).
                    val viewportW = size.width - 2f * maxLateralPx
                    val viewportH = size.height - 2f * maxVerticalPx
                    val topCentre = Offset(maxLateralPx + viewportW / 2f, maxVerticalPx)
                    // CSS `circle at 50% 0%` default size is farthest-corner.
                    val farthestCorner = hypot(viewportW / 2f, viewportH)
                    val glow = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to topGlow,
                            0.65f to Color.Transparent,
                        ),
                        center = topCentre,
                        radius = farthestCorner.coerceAtLeast(1f),
                    )
                    val blobCentre = Offset(maxLateralPx + viewportW * 0.18f, maxVerticalPx + viewportH * 0.88f)
                    val blob = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to lowBlob,
                            1f to Color.Transparent,
                        ),
                        center = blobCentre,
                        radius = (viewportW * 0.55f).coerceAtLeast(1f),
                    )
                    onDrawBehind {
                        drawRect(glow)
                        drawRect(blob)
                    }
                }
        )
    }
}

/**
 * Measure the child larger than the incoming bounds by [vertical] px above and below and
 * [horizontal] px left and right, centred, so a translation within those limits never exposes
 * the child's edge. Falls back to the incoming size when constraints are unbounded.
 */
private fun Modifier.bleed(vertical: Int, horizontal: Int): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    val placeable = measurable.measure(Constraints.fixed(width + 2 * horizontal, height + 2 * vertical))
    layout(width, height) {
        placeable.place(-horizontal, -vertical)
    }
}
