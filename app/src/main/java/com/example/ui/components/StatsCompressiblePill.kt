package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.scaledTween
import kotlin.math.roundToInt

/**
 * One number shown in the Home header stats pill. Values must come from real repositories
 * (captured requests, saved APIs, flows…) — the pill renders nothing when [StatsCompressiblePill]
 * receives an empty list rather than inventing counts.
 */
data class StatsPillEntry(
    val label: String,
    val value: Int,
    val icon: ImageVector? = null
)

/**
 * Resolved motion for one scroll position.
 *
 * Stage 1 ([compression] 0→1): the pill shrinks uniformly about its top-centre to
 * [StatsPillMotion.COMPRESSED_SCALE] while the text labels fade out — numbers stay legible.
 * Stage 2 ([slide] 0→1): the compressed pill slides up under the header row and fades out.
 * Layout never changes; everything is a graphics-layer transform, so scrolling costs no relayout.
 */
data class StatsPillFrame(val compression: Float, val slide: Float) {
    val labelAlpha: Float get() = 1f - compression
    val scale: Float get() = 1f - compression * (1f - StatsPillMotion.COMPRESSED_SCALE)
    val alpha: Float get() = 1f - slide
    val isVisible: Boolean get() = alpha > 0.05f
    /** Vertical translation as a fraction of (pill height + gap): 0 at rest, -1 fully hidden. */
    val translationFraction: Float get() = -slide
}

/** Pure scroll → motion maths (unit-tested in StatsCompressiblePillTest). */
object StatsPillMotion {
    /** Delta spec (g): the pill compresses by `scrollOffset / 60f`. */
    const val COMPRESS_END_PX = 60f
    /** Slide-out starts where compression ends and completes 80px later. */
    const val SLIDE_END_PX = 140f
    const val COMPRESSED_SCALE = 0.86f

    fun resolve(
        offsetPx: Float,
        compressEndPx: Float = COMPRESS_END_PX,
        slideEndPx: Float = SLIDE_END_PX
    ): StatsPillFrame {
        val offset = offsetPx.coerceAtLeast(0f)
        val compressEnd = compressEndPx.coerceAtLeast(1f)
        val slideEnd = slideEndPx.coerceAtLeast(compressEnd + 1f)
        val compression = (offset / compressEnd).coerceIn(0f, 1f)
        val slide = ((offset - compressEnd) / (slideEnd - compressEnd)).coerceIn(0f, 1f)
        return StatsPillFrame(compression, slide)
    }
}

/**
 * Two-stage compressible glass stats pill for the Home header (DESIGN-SYSTEM §14.7, delta (g)).
 *
 * @param entries        real counts to show; nothing is rendered when empty.
 * @param scrollOffsetPx snapshot-backed provider of the list's scroll offset in px — read
 *                       inside the graphics layer, so scrolling never recomposes this pill.
 * @param backdrop       backdrop to blur behind the pill (REAL_BLUR tier only).
 * @param accentTint     optional accent (e.g. interceptor status) that recolours the icons.
 * @param slideGap       extra distance the pill travels beyond its own height when sliding out.
 */
@Composable
fun StatsCompressiblePill(
    entries: List<StatsPillEntry>,
    scrollOffsetPx: () -> Float,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    onClick: (() -> Unit)? = null,
    shape: Shape = GlassCapsuleShape,
    slideGap: Dp = 8.dp,
    compressEndPx: Float = StatsPillMotion.COMPRESS_END_PX,
    slideEndPx: Float = StatsPillMotion.SLIDE_END_PX
) {
    if (entries.isEmpty()) return

    val haptics = rememberAppHapticFeedback()
    val surface = rememberGlassSurface(GlassRole.HEADER, backdrop)
    val iconTint = accentTint ?: MaterialTheme.colorScheme.primary
    val visible by remember(scrollOffsetPx, compressEndPx, slideEndPx) {
        derivedStateOf { StatsPillMotion.resolve(scrollOffsetPx(), compressEndPx, slideEndPx).isVisible }
    }
    val valueFade = scaledTween<Float>(durationMillis = 160)

    Box(
        modifier = modifier
            // Stage 2 slide is a *placement* offset, not a graphicsLayer translation: the backdrop
            // blur samples by layout position, so a layer translation would drag a stale slice
            // of the backdrop along (a visible smear in Clear Glass). Placement-only — the slot
            // keeps its size, siblings never relayout, and only this node is re-placed on scroll.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    val frame = StatsPillMotion.resolve(scrollOffsetPx(), compressEndPx, slideEndPx)
                    val travel = placeable.height + slideGap.roundToPx()
                    placeable.placeRelative(0, (frame.translationFraction * travel).roundToInt())
                }
            }
            // Stage 1 scale stays a layer transform. The sampled backdrop shrinks with the pill
            // (≤ 14% of the half-extent, under ≥ 8dp of blur) — reads as the glass pulling its
            // content in, and keeps the slot's layout constant.
            .graphicsLayer {
                val frame = StatsPillMotion.resolve(scrollOffsetPx(), compressEndPx, slideEndPx)
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = frame.scale
                scaleY = frame.scale
                alpha = frame.alpha
            }
            .glassSurface(
                surface = surface,
                shape = shape,
                defaultElevation = 6.dp,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f),
                borderWidth = 0.8.dp
            )
            .then(
                if (onClick != null) Modifier.clickable(enabled = visible) {
                    haptics(HapticFeedbackType.LongPress)
                    onClick()
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.icon != null) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(14.dp)
                        )
                        Box(Modifier.width(4.dp))
                    }
                    AnimatedContent(
                        targetState = entry.value,
                        transitionSpec = { fadeIn(valueFade).togetherWith(fadeOut(valueFade)) },
                        label = "statsPillValue"
                    ) { value ->
                        Text(
                            text = value.toString(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Box(Modifier.width(4.dp))
                    Text(
                        text = entry.label,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            alpha = StatsPillMotion.resolve(scrollOffsetPx(), compressEndPx, slideEndPx).labelAlpha
                        }
                    )
                }
            }
        }
    }
}
