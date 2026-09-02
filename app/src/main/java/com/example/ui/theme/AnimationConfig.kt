package com.example.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * v3.1 B8 — real, app-wide animation gating.
 *
 * Settings > UI Preferences exposes "Animation" (on/off) and "Transition Behavior"
 * (STANDARD / REDUCED / NONE). Previously these were stored and never read anywhere,
 * so every Compose animation in the app (pill-nav rotation spring, FAB colour
 * cross-fade/pulse, drawer/tab colour fades, wordmark dot pulse, node-drag feedback,
 * theme-toggle colour fades) ran unconditionally regardless of what the user picked.
 *
 * [LocalAnimationsEnabled] and [LocalAnimationDurationScale] are read by the app's
 * animation call sites (via [scaledTween] / [scaledSpring] below) instead of each
 * screen re-deriving its own on/off logic. [RequestrApp] (MainActivity.kt) provides
 * both from live [com.example.data.repository.SettingsRepository] state, keyed off
 * `animationEnabled` and `transitionBehavior`:
 *   - animationEnabled=false OR transitionBehavior=NONE -> animations disabled entirely
 *     (duration scale forced to 0, so tween/spring specs resolve instantly)
 *   - transitionBehavior=REDUCED -> animations still run but at a fraction of their
 *     authored duration (shorter, snappier, accessibility-friendly)
 *   - transitionBehavior=STANDARD -> full authored duration, unmodified
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** 1f = full authored duration, 0.4f = reduced, 0f = instant (effectively disabled). */
val LocalAnimationDurationScale = compositionLocalOf { 1f }

/**
 * Resolves the effective duration scale for the current composition, collapsing
 * [LocalAnimationsEnabled]=false to 0f regardless of [LocalAnimationDurationScale].
 */
@Composable
fun rememberEffectiveDurationScale(): Float {
    val enabled = LocalAnimationsEnabled.current
    val scale = LocalAnimationDurationScale.current
    return if (!enabled) 0f else scale
}

/**
 * Duration-scaled drop-in replacement for [tween]. When the resolved scale is 0
 * (animations off, or Transition Behavior = NONE), the duration collapses to 0ms so
 * the target value is applied effectively instantly instead of animating.
 */
@Composable
fun <T> scaledTween(durationMillis: Int, delayMillis: Int = 0): TweenSpec<T> {
    val scale = rememberEffectiveDurationScale()
    val scaledDuration = (durationMillis * scale).toInt().coerceAtLeast(0)
    return tween(durationMillis = scaledDuration, delayMillis = (delayMillis * scale).toInt().coerceAtLeast(0))
}

/**
 * Duration-scaled drop-in replacement for [spring]. Spring specs aren't duration-based,
 * so when animations are disabled we substitute a 0ms [tween] (instant) instead; when
 * reduced, we raise stiffness so the spring settles faster while keeping spring feel.
 */
@Composable
fun <T> scaledSpring(dampingRatio: Float = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness: Float = androidx.compose.animation.core.Spring.StiffnessMedium): FiniteAnimationSpec<T> {
    val enabled = LocalAnimationsEnabled.current
    val scale = LocalAnimationDurationScale.current
    if (!enabled || scale <= 0f) {
        return tween(durationMillis = 0)
    }
    // scale < 1 (REDUCED) -> higher stiffness -> settles faster.
    val effectiveStiffness = if (scale < 1f) stiffness / scale.coerceAtLeast(0.15f) else stiffness
    return spring(dampingRatio = dampingRatio, stiffness = effectiveStiffness)
}

@Composable
fun ProvideAnimationConfig(
    animationsEnabled: Boolean,
    transitionBehavior: String,
    content: @Composable () -> Unit
) {
    val scale = when (transitionBehavior.uppercase()) {
        "NONE" -> 0f
        "REDUCED" -> 0.4f
        else -> 1f
    }
    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalAnimationDurationScale provides scale
    ) {
        content()
    }
}

/**
 * Whether purely **ambient** motion — decorative infinite loops such as a pulsing status dot, a
 * rotating artwork disc or an equaliser bar — should run at all.
 *
 * These are the animations a reduced-motion user most wants gone, and they are also the ones
 * duration scaling handles worst: shortening the period of an infinite loop makes it *faster* and
 * therefore more distracting, which is the opposite of what "reduced" asks for. So ambient loops
 * are simply off unless the user is at full motion, and the caller falls back to its resting
 * value — which every ambient site in this codebase already defines.
 *
 * Finite, meaningful transitions keep using [scaledTween] / [scaledSpring] and are merely
 * shortened when reduced.
 */
@Composable
fun rememberAmbientMotionEnabled(): Boolean = rememberEffectiveDurationScale() >= 1f
