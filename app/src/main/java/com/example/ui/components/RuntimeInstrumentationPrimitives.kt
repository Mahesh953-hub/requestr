package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v6.1 Phase A / Domain B shared primitives, ported 1:1 from the CSS classes the approved
 * `runtime-instrumentation-*.html` / `runtime-cdp-*.html` mockups define:
 *
 *  - [RuntimeVRow]    → `.vrow`  (rounded surface card with a 3dp status-coloured left rail)
 *  - [RuntimeSubLabel]→ `.sub`   (uppercase, letter-spaced 10sp section label)
 *  - [RuntimeKvRow]   → `.kv`    (fixed-width dim key + monospace value)
 *  - [RuntimeTile]    → `.tile`  (flat bordered metric tile: label / big mono number / sub-line)
 *  - [RuntimeTrack]   → `.track` (thin bordered bar with a coloured fill)
 *  - [RuntimeStatusDot] → `.dot`
 *  - [RuntimeHairline]  → `.hair`
 *  - [RuntimeFlatCard]  → `.card.flat`
 *
 * They live in this file (rather than in `CommonComponents.kt`) deliberately: Domain B owns them,
 * they are `internal` to this module, and keeping them here avoids editing a file three other
 * design domains also touch.
 */

/** Mockup status vocabulary — green healthy/active, amber degraded/partial, red failed, gray idle. */
internal val RuntimeGreen = Color(0xFF10B981)
internal val RuntimeAmber = Color(0xFFF59E0B)
internal val RuntimeRed = Color(0xFFEF4444)
internal val RuntimeGray = Color(0xFF64748B)
internal val RuntimeBlue = Color(0xFF38BDF8)

@Composable
internal fun RuntimeVRow(
    railColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .drawBehind {
                drawRect(
                    color = railColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(width = 3.dp.toPx(), height = size.height),
                )
            }
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .run { if (onClick != null) clickable(onClick = onClick) else this }
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        content = content,
    )
}

@Composable
internal fun RuntimeSubLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun RuntimeKvRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = key,
            modifier = Modifier.width(92.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun RuntimeTile(
    label: String,
    labelColor: Color,
    value: String,
    valueColor: Color,
    subLine: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = labelColor,
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
        Text(
            text = subLine,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RuntimeTrack(
    fraction: Float,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(fillColor),
        )
    }
}

@Composable
internal fun RuntimeStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
internal fun RuntimeHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

@Composable
internal fun RuntimeFlatCard(
    modifier: Modifier = Modifier,
    railColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .run {
                if (railColor == null) {
                    this
                } else {
                    drawBehind {
                        drawRect(
                            color = railColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(width = 3.dp.toPx(), height = size.height),
                        )
                    }
                }
            }
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(start = if (railColor == null) 10.dp else 13.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        content = content,
    )
}

/** `.row` — space-between row used throughout both cards. */
@Composable
internal fun RuntimeSpreadRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Applies a mockup `data-component-id` as a Compose test tag. */
internal fun Modifier.componentId(id: String): Modifier = this.testTag(id)
