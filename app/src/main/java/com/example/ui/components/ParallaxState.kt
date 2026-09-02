package com.example.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Design-system parallax base layer — shared state.
 *
 * The app draws one [ParallaxBackground] behind the whole `NavHost` (hosted in
 * `MainActivity.RequestrApp`). It moves subtly and independently of the foreground so the
 * glass surfaces floating above it (nav pill, header capsules, stats pill) have something
 * non-uniform to refract. This class is the single shared signal that drives it:
 *
 *  - The **active screen** binds its scroll container with one call —
 *    `DriveParallaxFrom(listState)` — and the layer follows that container's scroll at
 *    [ParallaxState.verticalFactor] speed, softly saturating at the configured maximum shift.
 *  - **MainActivity** reports which bottom tab is active via [setLateralTabPosition]; the layer
 *    drifts a few dp sideways in the opposite direction of the tab slide so the background
 *    visibly moves less than the foreground during a lateral tab transition.
 *  - **Glass consumers** read [verticalShiftPx] / [lateralShiftPx] to know exactly where the
 *    layer currently is (both are snapshot-backed, so they can be read inside `graphicsLayer`
 *    or `drawBehind` lambdas with no recomposition and no per-frame allocation).
 *
 * Zero per-frame allocation by design: nothing here is collected through a `Flow`; the bound
 * scroll container is read lazily in the draw/layer phase, so Compose re-runs only the layer
 * block (a RenderNode property update) when the list scrolls.
 *
 * Continuity: when the active screen changes, the newly bound container almost certainly has a
 * different scroll offset (Home scrolled 600px vs a fresh Tools tab at 0). Rather than letting the
 * layer jump, [attach] records a *continuity correction* that keeps the visible shift identical at
 * the hand-over instant; [ParallaxBackground] then settles that correction to zero with a scaled
 * tween, or snaps it when animations are disabled (settles at end state — never jumps or flickers).
 */
@Stable
class ParallaxState(
    /** Fraction of the content scroll the layer moves at, before soft saturation. */
    val verticalFactor: Float = ParallaxDefaults.VerticalFactor,
) {
    private var source by mutableStateOf<ParallaxScrollSource?>(null)

    /** Scroll offset frozen at the moment the last source detached, so the layer holds still. */
    private var frozenOffsetPx by mutableFloatStateOf(0f)

    /** Maximum vertical shift in px, configured by [ParallaxBackground] from its Dp token. */
    var maxVerticalShiftPx: Float by mutableFloatStateOf(0f)
        private set

    /** Maximum lateral shift in px, configured by [ParallaxBackground] from its Dp token. */
    var maxLateralShiftPx: Float by mutableFloatStateOf(0f)
        private set

    /** Vertical px added to the computed shift so a source hand-over is visually continuous. */
    var continuityCorrectionPx: Float by mutableFloatStateOf(0f)
        private set

    /** Bumped on every [attach]; [ParallaxBackground] keys its settle animation on it. */
    var attachGeneration: Int by mutableIntStateOf(0)
        private set

    /** 0f = left-most tab, 1f = right-most tab, 0.5f = centre / unknown. */
    var lateralFraction: Float by mutableFloatStateOf(0.5f)
        private set

    /** Whether any scroll container is currently bound. */
    val isDriven: Boolean get() = source != null

    /** Raw content scroll offset (px, >= 0 at rest) of the bound source, or the frozen value. */
    val scrollOffsetPx: Float
        get() = source?.currentScrollOffsetPx() ?: frozenOffsetPx

    /**
     * The layer's current vertical translation in px — negative while scrolled down, because the
     * background travels in the same direction as the content but slower. Includes the
     * continuity correction, so this is exactly what is on screen.
     */
    fun verticalShiftPx(): Float =
        rawVerticalShiftPx(scrollOffsetPx) + continuityCorrectionPx

    /**
     * The layer's current horizontal translation target in px. Moving right along the tab bar
     * drifts the background left (negative), mirroring the foreground slide at a fraction of it.
     */
    fun lateralShiftPx(): Float = lateralShiftPx(lateralFraction, maxLateralShiftPx)

    private fun rawVerticalShiftPx(offsetPx: Float): Float =
        -parallaxShiftPx(offsetPx, verticalFactor, maxVerticalShiftPx)

    /** Called once by [ParallaxBackground] with its density-resolved tokens. Idempotent. */
    fun configure(maxVerticalShiftPx: Float, maxLateralShiftPx: Float) {
        if (this.maxVerticalShiftPx != maxVerticalShiftPx) this.maxVerticalShiftPx = maxVerticalShiftPx
        if (this.maxLateralShiftPx != maxLateralShiftPx) this.maxLateralShiftPx = maxLateralShiftPx
    }

    /**
     * Bind the active screen's scroll container. Continuous at the hand-over instant: the
     * visible shift before and after this call is identical; the difference is parked in
     * [continuityCorrectionPx] for [settleCorrection] to bleed off.
     */
    fun attach(newSource: ParallaxScrollSource) {
        if (source === newSource) return
        val visibleBefore = verticalShiftPx()
        source = newSource
        val rawAfter = rawVerticalShiftPx(newSource.currentScrollOffsetPx())
        continuityCorrectionPx = visibleBefore - rawAfter
        attachGeneration++
    }

    /**
     * Unbind [oldSource] if it is still the active one. The layer keeps its current position
     * (offset frozen) so a screen leaving during a transition does not move the background.
     */
    fun detach(oldSource: ParallaxScrollSource) {
        if (source !== oldSource) return
        frozenOffsetPx = oldSource.currentScrollOffsetPx()
        source = null
    }

    /**
     * Report the active bottom tab. [index] is the position among the [count] visible tabs.
     * Non-tab routes (Settings, detail screens) should not call this so the drift stays where
     * the underlying tab left it.
     */
    fun setLateralTabPosition(index: Int, count: Int) {
        val fraction = lateralFractionForTab(index, count)
        if (lateralFraction != fraction) lateralFraction = fraction
    }

    /** Animate [continuityCorrectionPx] to zero. A zero-duration spec snaps (animations off). */
    suspend fun settleCorrection(spec: AnimationSpec<Float>) {
        val start = continuityCorrectionPx
        if (start == 0f) return
        animate(initialValue = start, targetValue = 0f, animationSpec = spec) { value, _ ->
            continuityCorrectionPx = value
        }
    }
}

/**
 * Anything that can report a content scroll offset in px. Implementations must read
 * snapshot state (e.g. `LazyListState`, `ScrollState`) so the parallax layer invalidates on
 * scroll without any explicit observation.
 */
fun interface ParallaxScrollSource {
    fun currentScrollOffsetPx(): Float
}

/**
 * The app-wide parallax state. Provided once in `MainActivity.RequestrApp`; the default is a
 * detached instance so previews and isolated tests compose without a provider.
 */
val LocalParallaxState = staticCompositionLocalOf { ParallaxState() }

object ParallaxDefaults {
    /** Layer speed relative to content scroll before saturation. Subtle by design. */
    const val VerticalFactor = 0.30f

    /** Soft ceiling on vertical travel. Reached asymptotically, never with a hard stop. */
    val MaxVerticalShift: Dp = 40.dp

    /** Total lateral travel between the left-most and right-most tab. */
    val MaxLateralShift: Dp = 12.dp

    /** Base duration for settling a hand-over correction; scaled by the animation settings. */
    const val SettleDurationMs = 420

    /** Base duration for the lateral drift; matches the tab transition so they move together. */
    const val LateralDriftDurationMs = 320
}

// ---------------------------------------------------------------------------------------------
// Screen-side binders. One line in the screen; nothing else to wire.
// ---------------------------------------------------------------------------------------------

/**
 * Drive the parallax layer from a `LazyColumn` / `LazyRow` state. Call once in the screen that
 * owns the list, next to `rememberLazyListState()`:
 *
 * ```
 * val listState = rememberLazyListState()
 * DriveParallaxFrom(listState)
 * LazyColumn(state = listState, ...)
 * ```
 */
@Composable
fun DriveParallaxFrom(listState: LazyListState, state: ParallaxState = LocalParallaxState.current) {
    DisposableEffect(state, listState) {
        val source = ParallaxScrollSource { listState.estimatedScrollOffsetPx() }
        state.attach(source)
        onDispose { state.detach(source) }
    }
}

/** Drive the parallax layer from a `LazyVerticalGrid` / `LazyHorizontalGrid` state. */
@Composable
fun DriveParallaxFrom(gridState: LazyGridState, state: ParallaxState = LocalParallaxState.current) {
    DisposableEffect(state, gridState) {
        val source = ParallaxScrollSource { gridState.estimatedScrollOffsetPx() }
        state.attach(source)
        onDispose { state.detach(source) }
    }
}

/** Drive the parallax layer from a `Modifier.verticalScroll(scrollState)` container. */
@Composable
fun DriveParallaxFrom(scrollState: ScrollState, state: ParallaxState = LocalParallaxState.current) {
    DisposableEffect(state, scrollState) {
        val source = ParallaxScrollSource { scrollState.value.toFloat() }
        state.attach(source)
        onDispose { state.detach(source) }
    }
}

/** Drive the parallax layer from any custom snapshot-backed source. */
@Composable
fun DriveParallaxFrom(source: ParallaxScrollSource, state: ParallaxState = LocalParallaxState.current) {
    DisposableEffect(state, source) {
        state.attach(source)
        onDispose { state.detach(source) }
    }
}

// ---------------------------------------------------------------------------------------------
// Pure maths — unit-tested in ParallaxStateTest.
// ---------------------------------------------------------------------------------------------

/**
 * Soft-saturating parallax shift: `≈ factor * offset` near rest, asymptotically approaching
 * [maxShiftPx] instead of hitting a hard clamp (a hard stop reads as the background "catching").
 * Sign follows the offset. Returns 0 when [maxShiftPx] <= 0 (layer not configured yet).
 */
fun parallaxShiftPx(offsetPx: Float, factor: Float, maxShiftPx: Float): Float {
    if (maxShiftPx <= 0f || factor == 0f) return 0f
    val linear = abs(offsetPx) * factor
    return sign(offsetPx) * maxShiftPx * tanh(linear / maxShiftPx)
}

/**
 * 0f for the first tab, 1f for the last, evenly spaced between. A single tab or an
 * out-of-range index resolves to the centre so the layer does not lurch.
 */
fun lateralFractionForTab(index: Int, count: Int): Float {
    if (count <= 1 || index < 0 || index >= count) return 0.5f
    return index.toFloat() / (count - 1).toFloat()
}

/** Horizontal translation for a lateral fraction: right-most tab pushes the layer left. */
fun lateralShiftPx(lateralFraction: Float, maxLateralShiftPx: Float): Float =
    -(lateralFraction.coerceIn(0f, 1f) - 0.5f) * maxLateralShiftPx

/**
 * Absolute scroll estimate for a lazy list: items before the first visible one are assumed to
 * be the average size of the currently visible items. Exact while the first item is visible
 * (where the parallax is still far from saturation and precision matters most).
 */
fun estimateLazyScrollOffsetPx(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    averageVisibleItemSizePx: Float,
    mainAxisItemSpacingPx: Int,
): Float {
    if (firstVisibleItemIndex <= 0) return firstVisibleItemScrollOffset.toFloat().coerceAtLeast(0f)
    return firstVisibleItemIndex * (averageVisibleItemSizePx + mainAxisItemSpacingPx) +
        firstVisibleItemScrollOffset
}

/** Snapshot-backed, allocation-free scroll estimate for a [LazyListState]. */
fun LazyListState.estimatedScrollOffsetPx(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    var total = 0
    for (i in visible.indices) total += visible[i].size
    return estimateLazyScrollOffsetPx(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        averageVisibleItemSizePx = total.toFloat() / visible.size,
        mainAxisItemSpacingPx = info.mainAxisItemSpacing,
    )
}

/**
 * Snapshot-backed, allocation-free scroll estimate for a [LazyGridState]. A grid scrolls by
 * *lines* (rows for a vertical grid), so the line index of the first visible item stands in for
 * the list's item index and every visible item's main-axis size is averaged as the line size —
 * items on one line share a height, so the average is exact for uniform lines.
 */
fun LazyGridState.estimatedScrollOffsetPx(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    val vertical = info.orientation == Orientation.Vertical
    val first = visible[0]
    val firstLine = (if (vertical) first.row else first.column).coerceAtLeast(0)
    var total = 0
    for (i in visible.indices) {
        total += if (vertical) visible[i].size.height else visible[i].size.width
    }
    return estimateLazyScrollOffsetPx(
        firstVisibleItemIndex = firstLine,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        averageVisibleItemSizePx = total.toFloat() / visible.size,
        mainAxisItemSpacingPx = info.mainAxisItemSpacing,
    )
}
