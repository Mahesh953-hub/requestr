package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.RecentActivityItem
import com.example.data.repository.RecentItemType
import com.example.data.repository.RelativeTimeFormatter
import com.example.ui.components.GlassCard
import kotlinx.coroutines.delay

/**
 * Presentation layer for `RecentActivityRepository`'s real, persisted "recently opened" history.
 *
 * Home's "Recently opened" section (`docs/designs/home/home_rebranding.md` §6) and the full
 * "See all" page ([RecentlyOpenedScreen]) render identical rows, so the row and its
 * type-to-icon/colour mapping live here once rather than being duplicated per screen.
 *
 * Everything user-visible on a row is sourced from the stored snapshot or derived from the real
 * `openedAt` epoch-millis via [RelativeTimeFormatter] — this file deliberately contains no
 * fallback/placeholder timestamp string, because the fabricated literals `"Recent"` and
 * `"Saved"` that Home used to render are exactly what this replaces.
 */

/**
 * A "now" that advances while the surface is composed, so relative timestamps age in place
 * instead of freezing at whatever they read when the screen opened. Recomposes at most twice a
 * minute, which is finer than the smallest bucket [RelativeTimeFormatter] can print.
 *
 * Deliberately not gated on the animations setting: this is data freshness, not motion. A user
 * who has turned animations off still wants a row that says "3m ago" to stop saying that.
 */
@Composable
fun rememberRelativeTimeTicker(intervalMillis: Long = 30_000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Category colour per item type, matching Home's quick-action and Power Module colour coding. */
fun recentTypeColor(type: RecentItemType): Color = when (type) {
    RecentItemType.API -> Color(0xFF10B981)
    RecentItemType.NOTE -> Color(0xFF6366F1)
    RecentItemType.TOOL -> Color(0xFFD97706)
    RecentItemType.CHAT -> Color(0xFF0EA5E9)
    RecentItemType.FLOW -> Color(0xFF4F46E5)
}

/** Leading icon per item type. */
fun recentTypeIcon(type: RecentItemType): ImageVector = when (type) {
    RecentItemType.API -> Icons.Default.Public
    RecentItemType.NOTE -> Icons.AutoMirrored.Filled.Notes
    RecentItemType.TOOL -> Icons.Default.Build
    RecentItemType.CHAT -> Icons.Default.Forum
    RecentItemType.FLOW -> Icons.Default.AccountTree
}

/**
 * The `"{Type} • {SUBTYPE}"` label line. The repository stores the subtype raw (`method.name`,
 * `format.name`, `category.name`, …) so the uppercasing happens here, once. An item whose stored
 * subtype is blank renders just the type rather than a dangling `"Note • "`.
 */
fun recentTypeLabel(item: RecentActivityItem): String =
    if (item.subtype.isBlank()) item.type.label
    else "${item.type.label} • ${item.subtype.uppercase()}"

/**
 * One "recently opened" row: type-coloured icon, `"{Type} • {SUBTYPE}"` label, title, muted
 * preview, real relative timestamp pill and a chevron.
 *
 * [nowMillis] is passed in rather than read here so the whole list formats against a single
 * instant — otherwise rows composed a few milliseconds apart could disagree about "now" — and so
 * the caller can drive re-formatting as time passes.
 */
@Composable
fun RecentActivityRow(
    item: RecentActivityItem,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = recentTypeColor(item.type)

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = recentTypeIcon(item.type),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recentTypeLabel(item),
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.preview.isNotBlank()) {
                    Text(
                        text = item.preview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = RelativeTimeFormatter.format(item.openedAt, nowMillis),
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Routing for a recents row. Kept next to the row so Home and the "See all" page cannot drift
 * into opening the same item two different ways.
 *
 * Every branch goes through the repository function that also *records* the open, so tapping a
 * recents row refreshes that row's own timestamp exactly as opening the item anywhere else does.
 */
class RecentActivityNavigator(
    val openApi: (String) -> Unit,
    val openNote: (String) -> Unit,
    val openTool: (String) -> Unit,
    val openChat: (String) -> Unit,
    val openFlow: (String) -> Unit,
) {
    fun open(item: RecentActivityItem) {
        when (item.type) {
            RecentItemType.API -> openApi(item.itemId)
            RecentItemType.NOTE -> openNote(item.itemId)
            RecentItemType.TOOL -> openTool(item.itemId)
            RecentItemType.CHAT -> openChat(item.itemId)
            RecentItemType.FLOW -> openFlow(item.itemId)
        }
    }
}

/**
 * Type filter for the "See all" page. `null` means "all types".
 *
 * Returned newest-first, preserving the repository's ordering.
 */
fun filterRecents(
    recents: List<RecentActivityItem>,
    type: RecentItemType?,
): List<RecentActivityItem> = if (type == null) recents else recents.filter { it.type == type }

/**
 * Per-type counts for the filter chips, so a chip can show a real number and a type with no
 * history can be hidden instead of offering a filter that yields an empty list.
 */
fun recentTypeCounts(recents: List<RecentActivityItem>): Map<RecentItemType, Int> =
    recents.groupingBy { it.type }.eachCount()
