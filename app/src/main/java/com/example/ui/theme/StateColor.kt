package com.example.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Design-system rule: theme switches are instantaneous, state changes animate.
 *
 * `animateColorAsState(if (selected) primary else surface)` violates that rule — the
 * endpoints are theme colours, so when the user swaps theme family / accent every
 * selected chip, tab and pill visibly cross-fades from the old palette to the new
 * one. The fix is to animate a *progress* scalar (0f = inactive, 1f = active) and
 * lerp between whatever the theme colours are *right now*. A theme swap then
 * re-resolves on the very next frame; a selection change still eases over the
 * caller's [AnimationSpec].
 *
 * [resolveStateColor] is the pure core so it can be unit-tested without Compose.
 */
fun resolveStateColor(inactive: Color, active: Color, progress: Float): Color {
    val t = progress.coerceIn(0f, 1f)
    return when (t) {
        0f -> inactive
        1f -> active
        else -> lerp(inactive, active, t)
    }
}

/**
 * Animates the active/inactive *state* of a colour while keeping the colour itself bound
 * to the current theme.
 *
 * @param active whether the element is currently in its active/selected state
 * @param activeColor theme colour when active — read fresh every composition
 * @param inactiveColor theme colour when inactive — read fresh every composition
 * @param animationSpec spec for the 0f..1f progress; callers should pass
 *   [scaledTween] / [scaledSpring] so Settings > Animation gating applies
 */
@Composable
fun animateStateColor(
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    animationSpec: AnimationSpec<Float>,
    label: String = "stateColor"
): State<Color> {
    val progress = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = animationSpec,
        label = label
    )
    return remember(activeColor, inactiveColor) {
        derivedStateOf { resolveStateColor(inactiveColor, activeColor, progress.value) }
    }
}

/**
 * Pure core of [animateIndexedStateColor]: resolves a fractional position along [colors].
 *
 * An integral [progress] returns that entry exactly; a fractional one lerps between its
 * neighbours. Out-of-range values clamp to the ends, and an empty list is a programming error
 * the caller must not reach — it throws rather than silently rendering transparent.
 */
fun resolveIndexedStateColor(colors: List<Color>, progress: Float): Color {
    require(colors.isNotEmpty()) { "resolveIndexedStateColor needs at least one colour" }
    val t = progress.coerceIn(0f, (colors.size - 1).toFloat())
    val lower = t.toInt()
    val upper = (lower + 1).coerceAtMost(colors.size - 1)
    val fraction = t - lower
    return if (lower == upper || fraction == 0f) colors[lower] else lerp(colors[lower], colors[upper], fraction)
}

/**
 * The multi-state sibling of [animateStateColor], for an element with more than two colour
 * states (for example a capture FAB that is idle / armed / capturing).
 *
 * Same contract and same reason: the animated quantity is the *position* between states, and
 * [colors] is re-read from the theme every composition, so a theme swap repaints on the next
 * frame instead of cross-fading from the previous palette.
 */
@Composable
fun animateIndexedStateColor(
    index: Int,
    colors: List<Color>,
    animationSpec: AnimationSpec<Float>,
    label: String = "indexedStateColor"
): State<Color> {
    val progress = animateFloatAsState(
        targetValue = index.coerceIn(0, colors.size - 1).toFloat(),
        animationSpec = animationSpec,
        label = label
    )
    return remember(colors) {
        derivedStateOf { resolveIndexedStateColor(colors, progress.value) }
    }
}
