package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import com.example.data.models.UserSettingsState
import com.example.data.prefs.canonicalGlassStyle
import com.example.data.repository.SettingsRepository
import com.example.ui.theme.GlassStyles
import kotlin.math.ceil
import kotlin.math.roundToInt

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Tiered glass rendering (DESIGN-SYSTEM §14.7)
//
// Two tiers, chosen once per device from the SDK level:
//
//  • REAL_BLUR (API 31+) — the content underneath a glass shape is captured into a
//    [GraphicsLayer] by a [GlassBackdropSource]; each glass shape ([Modifier.glassBackdrop])
//    re-draws that layer into its own layer with a [BlurEffect] (and, for Liquid Glass, a
//    slight magnification = "refraction"), clipped to the shape, *behind* its own tint brush.
//    RenderNodes are drawn by reference, so a scrolling list underneath is refracted live
//    without re-recording anything per frame.
//
//  • GRADIENT_FALLBACK (API 24–30) — no RenderEffect exists on the platform. Glass keeps the
//    frozen tint-gradient + hairline emulation; blur radius and refraction resolve to 0 and
//    [Modifier.glassBackdrop] is a no-op. Layout, colours and tokens are identical across tiers.
//
// [Modifier.blur] / a per-element RenderEffect is deliberately NOT used: it blurs the element
// (the pill and its icons), not what is beneath it. The source/consumer split below is the only
// way to blur the backdrop with Compose Foundation 1.7.x without an AGSL shader.
// ═════════════════════════════════════════════════════════════════════════════════════════════

enum class GlassRenderTier { REAL_BLUR, GRADIENT_FALLBACK }

/** Minimum SDK on which [android.graphics.RenderEffect] (and therefore backdrop blur) exists. */
const val GLASS_REAL_BLUR_MIN_SDK = Build.VERSION_CODES.S

/** Pure tier selection — [sdkInt] is injectable for unit tests. */
fun glassRenderTier(sdkInt: Int = Build.VERSION.SDK_INT): GlassRenderTier =
    if (sdkInt >= GLASS_REAL_BLUR_MIN_SDK) GlassRenderTier.REAL_BLUR else GlassRenderTier.GRADIENT_FALLBACK

// ─── Backdrop state ──────────────────────────────────────────────────────────────────────────

/**
 * Shared handle between one [GlassBackdropSource] (the content that can be seen *through*
 * glass) and any number of glass consumers ([Modifier.glassBackdrop]) floating above it.
 *
 * Consumers must be drawn AFTER the source and must NOT be descendants of it — a consumer inside
 * its own source would draw the layer it is being recorded into.
 */
@Stable
class GlassBackdropState {
    internal var layer: GraphicsLayer? = null
    internal var sourcePositionInRoot: Offset = Offset.Zero
    internal var sourceSize: IntSize = IntSize.Zero

    private val consumers = ArrayList<GlassBackdropNode>(2)

    /** True once the source has been laid out and recorded at least once. */
    val isReady: Boolean get() = layer != null && sourceSize != IntSize.Zero

    internal fun register(node: GlassBackdropNode) {
        if (!consumers.contains(node)) consumers.add(node)
    }

    internal fun unregister(node: GlassBackdropNode) {
        consumers.remove(node)
    }

    /** Called by the source after each re-record so every consumer re-composites its blur. */
    internal fun notifyDrawn() {
        for (i in consumers.indices) {
            val node = consumers[i]
            if (node.isAttached) node.invalidateDraw()
        }
    }
}

@Composable
fun rememberGlassBackdropState(): GlassBackdropState = remember { GlassBackdropState() }

/**
 * Nearest backdrop for glass consumers that are not handed one explicitly. Defaults to null
 * (no blur, fallback look). [GlassBackdropSource] resets this to null inside its own content so
 * a glass shape *inside* the source never tries to consume it.
 */
val LocalGlassBackdropState = compositionLocalOf<GlassBackdropState?> { null }

// ─── Source ──────────────────────────────────────────────────────────────────────────────────

/**
 * Wraps the content that glass shapes float over. On the REAL_BLUR tier its draw is recorded
 * into [state]'s layer (and drawn normally from that layer); on the fallback tier it is a plain
 * pass-through with zero overhead.
 */
@Composable
fun GlassBackdropSource(
    state: GlassBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.glassBackdropSource(state)) {
        CompositionLocalProvider(LocalGlassBackdropState provides null) {
            content()
        }
    }
}

fun Modifier.glassBackdropSource(
    state: GlassBackdropState,
    tier: GlassRenderTier = glassRenderTier()
): Modifier = if (tier != GlassRenderTier.REAL_BLUR) this else this.then(GlassBackdropSourceElement(state))

private data class GlassBackdropSourceElement(
    val state: GlassBackdropState
) : ModifierNodeElement<GlassBackdropSourceNode>() {
    override fun create() = GlassBackdropSourceNode(state)
    override fun update(node: GlassBackdropSourceNode) { node.updateState(state) }
    override fun InspectorInfo.inspectableProperties() {
        name = "glassBackdropSource"
    }
}

private class GlassBackdropSourceNode(
    private var state: GlassBackdropState
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var layer: GraphicsLayer? = null

    fun updateState(newState: GlassBackdropState) {
        if (newState === state) return
        state.layer = null
        state.sourceSize = IntSize.Zero
        state = newState
        state.layer = layer
        invalidateDraw()
    }

    override fun onAttach() {
        val created = requireGraphicsContext().createGraphicsLayer()
        layer = created
        state.layer = created
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        if (state.layer != null) {
            state.layer = null
            state.sourceSize = IntSize.Zero
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val pos = coordinates.positionInRoot()
        if (pos != state.sourcePositionInRoot) {
            state.sourcePositionInRoot = pos
            state.notifyDrawn()
        }
    }

    override fun ContentDrawScope.draw() {
        val target = layer
        if (target == null) {
            drawContent()
            return
        }
        val intSize = size.toIntSize()
        state.sourceSize = intSize
        target.record(intSize) { this@draw.drawContent() }
        drawLayer(target)
        state.notifyDrawn()
    }
}

// ─── Consumer ────────────────────────────────────────────────────────────────────────────────

/**
 * Draws a blurred (and optionally refracted) copy of [state]'s backdrop behind this element,
 * clipped to [shape]. No-op when [state] is null, on the fallback tier, or when neither blur
 * nor refraction is requested. Place it after `clip(shape)` and before `background(...)`.
 *
 * @param refraction Extra magnification of the backdrop about the shape's centre
 *                   (0.06 = 6% larger) — the Liquid Glass lens. 0 disables.
 */
fun Modifier.glassBackdrop(
    state: GlassBackdropState?,
    shape: Shape,
    blurRadius: Dp,
    refraction: Float = 0f,
    tier: GlassRenderTier = glassRenderTier()
): Modifier {
    if (state == null || tier != GlassRenderTier.REAL_BLUR) return this
    if (blurRadius <= 0.dp && refraction <= 0f) return this
    return this.then(GlassBackdropElement(state, shape, blurRadius, refraction.coerceIn(0f, 0.5f)))
}

private data class GlassBackdropElement(
    val state: GlassBackdropState,
    val shape: Shape,
    val blurRadius: Dp,
    val refraction: Float
) : ModifierNodeElement<GlassBackdropNode>() {
    override fun create() = GlassBackdropNode(state, shape, blurRadius, refraction)
    override fun update(node: GlassBackdropNode) = node.update(state, shape, blurRadius, refraction)
    override fun InspectorInfo.inspectableProperties() {
        name = "glassBackdrop"
        properties["blurRadius"] = blurRadius
        properties["refraction"] = refraction
    }
}

internal class GlassBackdropNode(
    private var state: GlassBackdropState,
    private var shape: Shape,
    private var blurRadius: Dp,
    private var refraction: Float
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var effectLayer: GraphicsLayer? = null
    private var positionInRoot: Offset = Offset.Zero

    // Caches — rebuilt only when the inputs they depend on change.
    private var cachedSize: Size = Size.Unspecified
    private var cachedOutlinePath: Path? = null
    private var cachedBlurPx: Float = -1f
    private var cachedEffect: RenderEffect? = null

    // Recording lambda kept in a field so drawing allocates nothing per frame.
    private var recordSourceLayer: GraphicsLayer? = null
    private var recordMarginPx: Float = 0f
    private var recordScale: Float = 1f
    private var recordPivotX: Float = 0f
    private var recordPivotY: Float = 0f
    private var recordDx: Float = 0f
    private var recordDy: Float = 0f
    private val recordBlock: DrawScope.() -> Unit = {
        val src = recordSourceLayer
        if (src != null) {
            translate(recordMarginPx, recordMarginPx) {
                scale(recordScale, recordScale, Offset(recordPivotX, recordPivotY)) {
                    translate(recordDx, recordDy) {
                        drawLayer(src)
                    }
                }
            }
        }
    }

    fun update(newState: GlassBackdropState, newShape: Shape, newBlur: Dp, newRefraction: Float) {
        if (newState !== state) {
            state.unregister(this)
            state = newState
            if (isAttached) state.register(this)
        }
        if (newShape != shape) {
            shape = newShape
            cachedOutlinePath = null
            cachedSize = Size.Unspecified
        }
        blurRadius = newBlur
        refraction = newRefraction
        invalidateDraw()
    }

    override fun onAttach() {
        effectLayer = requireGraphicsContext().createGraphicsLayer()
        state.register(this)
    }

    override fun onDetach() {
        state.unregister(this)
        effectLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        effectLayer = null
        cachedOutlinePath = null
        cachedEffect = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val pos = coordinates.positionInRoot()
        if (pos != positionInRoot) {
            positionInRoot = pos
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val source = state.layer
        val effect = effectLayer
        if (source == null || effect == null || !state.isReady || size.isEmpty()) {
            drawContent()
            return
        }

        val blurPx = blurRadius.toPx()
        val marginPx = ceil(blurPx * 2f)
        val w = size.width
        val h = size.height
        val effectSize = IntSize(
            (w + marginPx * 2f).roundToInt().coerceAtLeast(1),
            (h + marginPx * 2f).roundToInt().coerceAtLeast(1)
        )

        if (blurPx != cachedBlurPx) {
            cachedBlurPx = blurPx
            cachedEffect = if (blurPx > 0f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
        }
        effect.renderEffect = cachedEffect

        recordSourceLayer = source
        recordMarginPx = marginPx
        recordScale = 1f + refraction
        recordPivotX = marginPx + w / 2f
        recordPivotY = marginPx + h / 2f
        recordDx = state.sourcePositionInRoot.x - positionInRoot.x
        recordDy = state.sourcePositionInRoot.y - positionInRoot.y
        effect.record(effectSize, recordBlock)

        val path = outlinePath(size)
        clipPath(path) {
            translate(-marginPx, -marginPx) {
                drawLayer(effect)
            }
        }
        drawContent()
    }

    private fun DrawScope.outlinePath(currentSize: Size): Path {
        val existing = cachedOutlinePath
        if (existing != null && currentSize == cachedSize) return existing
        val outline: Outline = shape.createOutline(currentSize, layoutDirection, this)
        val path = existing?.also { it.reset() } ?: Path()
        path.addOutline(outline)
        cachedOutlinePath = path
        cachedSize = currentSize
        return path
    }
}

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Glass modes — the single source of truth for tint / border / blur per mode and role.
// Replaces the three duplicated `when (blurStyle)` blocks that used to live in GlassActionPill,
// FloatingCapsuleHeader and CollapsibleNavPill.
// ═════════════════════════════════════════════════════════════════════════════════════════════

enum class GlassMode(val key: String) {
    FROSTED_GLASS("FROSTED_GLASS"),
    SOLID_M3("SOLID_M3"),
    OLED_BLACK("OLED_BLACK"),
    TRANSLUCENT("TRANSLUCENT"),
    LIQUID_GLASS("LIQUID_GLASS"),
    TUNE_GLASS("TUNE_GLASS");

    companion object {
        /** Accepts any raw stored value; aliases resolve via [canonicalGlassStyle]. */
        fun fromKey(raw: String?): GlassMode {
            val key = canonicalGlassStyle(raw)
            return entries.firstOrNull { it.key == key } ?: FROSTED_GLASS
        }
    }
}

/**
 * User-facing label per mode. Delegates to the theme catalogue ([GlassStyles.label]) — the single
 * source of truth Settings renders from — so the renderer and the pickers can never disagree
 * (TRANSLUCENT is "Clear Glass"; the legacy `CLEAR_GLASS` key stays an alias of it).
 */
fun glassModeDisplayName(raw: String?): String = GlassStyles.label(GlassMode.fromKey(raw).key)

/** Which floating shape is being rendered — the two roles keep slightly different frozen tokens. */
enum class GlassRole { NAV, HEADER }

/** Theme colours the resolver needs; kept as a plain data class so it is unit-testable. */
data class GlassPalette(
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color
) {
    val isDark: Boolean get() = surface.luminance() < 0.5f
}

/** Tune Glass sliders. Only the TUNE_GLASS branch of [resolveGlassSurface] may read these. */
data class GlassTuneValues(
    val blurDp: Int,
    val tintOpacity: Float,
    val borderOpacity: Float,
    val shadowDp: Int
) {
    companion object {
        fun from(settings: UserSettingsState) = GlassTuneValues(
            blurDp = settings.glassTuneBlurDp.coerceIn(0, 48),
            tintOpacity = settings.glassTuneTintOpacity.coerceIn(0.15f, 1f),
            borderOpacity = settings.glassTuneBorderOpacity.coerceIn(0f, 1f),
            shadowDp = settings.glassTuneShadowDp.coerceIn(0, 24)
        )
    }
}

/**
 * Fully resolved look of one glass surface.
 *
 * @param shadowElevationDp null = keep the component's frozen default elevation.
 * @param highlightAlpha    alpha of the specular top-edge highlight (0 = none).
 */
data class GlassSurfaceSpec(
    val mode: GlassMode,
    val tintTop: Color,
    val tintBottom: Color,
    val borderColor: Color,
    val blurRadiusDp: Float,
    val refraction: Float,
    val highlightAlpha: Float,
    val shadowElevationDp: Float?
) {
    val hasBlur: Boolean get() = blurRadiusDp > 0f
}

/** Blur radius the fixed glass modes use when real blur is available (mockup: blur(24px)). */
const val GLASS_FROSTED_BLUR_DP = 24f
/** Clear Glass keeps most of the backdrop legible — a light blur only. */
const val GLASS_CLEAR_BLUR_DP = 8f
/** Liquid Glass lens magnification. */
const val GLASS_LIQUID_REFRACTION = 0.06f
/** Liquid Glass specular top-edge highlight alpha. */
const val GLASS_LIQUID_HIGHLIGHT_ALPHA = 0.22f

private val OledBottom = Color(0xFF0F172A)

/**
 * Pure resolver. [opacity] is the role's user opacity (nav or header slider); [realBlur] is
 * whether the device is on [GlassRenderTier.REAL_BLUR]. Legacy modes reproduce the frozen
 * gradient tokens exactly; blur is layered underneath them only when available.
 */
fun resolveGlassSurface(
    modeKey: String?,
    opacity: Float,
    role: GlassRole,
    palette: GlassPalette,
    tune: GlassTuneValues,
    realBlur: Boolean
): GlassSurfaceSpec {
    val mode = GlassMode.fromKey(modeKey)
    val op = opacity.coerceIn(0.2f, 1f)
    val legacyBorder = palette.outline.copy(alpha = if (role == GlassRole.NAV) 0.35f else 0.30f)

    return when (mode) {
        GlassMode.SOLID_M3 -> GlassSurfaceSpec(
            mode = mode,
            tintTop = palette.surface,
            tintBottom = palette.surfaceVariant,
            borderColor = palette.outlineVariant.copy(alpha = 0.6f),
            blurRadiusDp = 0f,
            refraction = 0f,
            highlightAlpha = 0f,
            shadowElevationDp = null
        )
        GlassMode.OLED_BLACK -> GlassSurfaceSpec(
            mode = mode,
            tintTop = Color.Black.copy(alpha = op),
            tintBottom = OledBottom.copy(alpha = op),
            borderColor = Color.White.copy(alpha = 0.15f),
            blurRadiusDp = 0f,
            refraction = 0f,
            highlightAlpha = 0f,
            shadowElevationDp = null
        )
        GlassMode.TRANSLUCENT -> {
            val (topMul, bottomMul) = if (role == GlassRole.NAV) 0.7f to 0.5f else 0.65f to 0.45f
            GlassSurfaceSpec(
                mode = mode,
                tintTop = palette.surface.copy(alpha = (op * topMul).coerceIn(0.2f, 1f)),
                tintBottom = palette.surfaceVariant.copy(alpha = (op * bottomMul).coerceIn(0.2f, 1f)),
                borderColor = legacyBorder,
                blurRadiusDp = if (realBlur) GLASS_CLEAR_BLUR_DP else 0f,
                refraction = 0f,
                highlightAlpha = 0f,
                shadowElevationDp = null
            )
        }
        GlassMode.FROSTED_GLASS -> GlassSurfaceSpec(
            mode = mode,
            tintTop = palette.surface.copy(alpha = op),
            tintBottom = palette.surfaceVariant.copy(alpha = (op * 0.95f).coerceIn(0.2f, 1f)),
            borderColor = legacyBorder,
            blurRadiusDp = if (realBlur) GLASS_FROSTED_BLUR_DP else 0f,
            refraction = 0f,
            highlightAlpha = 0f,
            shadowElevationDp = null
        )
        GlassMode.LIQUID_GLASS -> {
            val border = if (palette.isDark) Color.White.copy(alpha = 0.28f) else palette.outline.copy(alpha = 0.40f)
            if (realBlur) {
                GlassSurfaceSpec(
                    mode = mode,
                    tintTop = palette.surface.copy(alpha = (op * 0.45f).coerceIn(0.1f, 1f)),
                    tintBottom = palette.surfaceVariant.copy(alpha = (op * 0.30f).coerceIn(0.1f, 1f)),
                    borderColor = border,
                    blurRadiusDp = GLASS_FROSTED_BLUR_DP,
                    refraction = GLASS_LIQUID_REFRACTION,
                    highlightAlpha = GLASS_LIQUID_HIGHLIGHT_ALPHA,
                    shadowElevationDp = null
                )
            } else {
                // No blur to hide the content → denser tint so text stays legible.
                GlassSurfaceSpec(
                    mode = mode,
                    tintTop = palette.surface.copy(alpha = (op * 0.80f).coerceIn(0.2f, 1f)),
                    tintBottom = palette.surfaceVariant.copy(alpha = (op * 0.70f).coerceIn(0.2f, 1f)),
                    borderColor = border,
                    blurRadiusDp = 0f,
                    refraction = 0f,
                    highlightAlpha = GLASS_LIQUID_HIGHLIGHT_ALPHA,
                    shadowElevationDp = null
                )
            }
        }
        GlassMode.TUNE_GLASS -> {
            val blur = if (realBlur) tune.blurDp.toFloat() else 0f
            GlassSurfaceSpec(
                mode = mode,
                tintTop = palette.surface.copy(alpha = tune.tintOpacity),
                tintBottom = palette.surfaceVariant.copy(alpha = (tune.tintOpacity * 0.95f).coerceIn(0.15f, 1f)),
                borderColor = palette.outline.copy(alpha = tune.borderOpacity),
                blurRadiusDp = blur,
                refraction = if (blur > 0f) GLASS_LIQUID_REFRACTION else 0f,
                highlightAlpha = if (blur > 0f) GLASS_LIQUID_HIGHLIGHT_ALPHA else 0f,
                shadowElevationDp = tune.shadowDp.toFloat()
            )
        }
    }
}

// ─── Composable glue ─────────────────────────────────────────────────────────────────────────

/** Resolved spec plus its remembered brushes and the backdrop it should blur. */
@Stable
class GlassSurface internal constructor(
    val spec: GlassSurfaceSpec,
    val brush: Brush,
    val backdrop: GlassBackdropState?
)

@Composable
fun rememberGlassPalette(): GlassPalette {
    val cs = MaterialTheme.colorScheme
    return remember(cs.surface, cs.surfaceVariant, cs.outline, cs.outlineVariant, cs.primary) {
        GlassPalette(cs.surface, cs.surfaceVariant, cs.outline, cs.outlineVariant, cs.primary)
    }
}

/**
 * Reads the live settings for [role] (nav or header opacity + mode, plus Tune Glass values when
 * that mode is active) and resolves them against the current theme. [backdrop] defaults to the
 * nearest [LocalGlassBackdropState]; pass one explicitly for overlays hosted outside the source.
 */
@Composable
fun rememberGlassSurface(
    role: GlassRole,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current
): GlassSurface {
    val settings by SettingsRepository.settings.collectAsState()
    val palette = rememberGlassPalette()
    val modeKey = if (role == GlassRole.NAV) settings.navBarBlurEffect else settings.headerBlurStyle
    val opacity = if (role == GlassRole.NAV) settings.navBarBackgroundOpacity else settings.headerBackgroundOpacity
    val tune = GlassTuneValues.from(settings)
    val realBlur = glassRenderTier() == GlassRenderTier.REAL_BLUR && backdrop != null
    return remember(modeKey, opacity, role, palette, tune, realBlur, backdrop) {
        val spec = resolveGlassSurface(modeKey, opacity, role, palette, tune, realBlur)
        GlassSurface(
            spec = spec,
            brush = Brush.verticalGradient(listOf(spec.tintTop, spec.tintBottom)),
            backdrop = backdrop
        )
    }
}

/**
 * The complete glass stack for a floating shape: shadow → clip → backdrop blur → tint →
 * optional accent overlay → specular highlight → hairline border. Components keep their own
 * frozen shadow/border tokens and pass them in; Tune Glass overrides the elevation only.
 *
 * @param tintOverlay         accent wash drawn over the mode tint (e.g. primary @ 0.14).
 * @param borderColorOverride replaces the mode's border colour (e.g. primary @ 0.40).
 */
fun Modifier.glassSurface(
    surface: GlassSurface,
    shape: Shape,
    defaultElevation: Dp,
    ambientColor: Color,
    spotColor: Color,
    borderWidth: Dp,
    tintOverlay: Color? = null,
    borderColorOverride: Color? = null
): Modifier {
    val spec = surface.spec
    val elevation = spec.shadowElevationDp?.dp ?: defaultElevation
    return this
        .shadow(elevation = elevation, shape = shape, ambientColor = ambientColor, spotColor = spotColor)
        .clip(shape)
        .glassBackdrop(surface.backdrop, shape, spec.blurRadiusDp.dp, spec.refraction)
        .background(surface.brush)
        .then(if (tintOverlay != null && tintOverlay.alpha > 0f) Modifier.background(tintOverlay) else Modifier)
        .then(if (spec.highlightAlpha > 0f) Modifier.glassHighlight(spec.highlightAlpha) else Modifier)
        .border(borderWidth, borderColorOverride ?: spec.borderColor, shape)
}

/** Specular top-edge highlight: white fading out over the top 55% of the shape. */
private fun Modifier.glassHighlight(alpha: Float): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = alpha * 0.25f), Color.Transparent),
        startY = 0f,
        endY = size.height * 0.55f
    )
    onDrawBehind { drawRect(brush) }
}

/** Default header/pill capsule shape shared by the floating header primitives. */
val GlassCapsuleShape = RoundedCornerShape(18.dp)
