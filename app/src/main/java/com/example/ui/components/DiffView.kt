package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.analysis.TextDiffer

private val DiffGreen = Color(0xFF10B981)
private val DiffRed = Color(0xFFEF4444)
private val DiffAmber = Color(0xFFF59E0B)

/**
 * Line diff of two texts — Phase B redesign (PHASE-B-REBRAND-NO-MOCKUP-COVERAGE §Diff view).
 *
 * Every state is designed explicitly via [buildDiffModel]:
 * - both empty / identical → success-flavoured [EmptyState] (no hunks rendered)
 * - additions only / deletions only / mixed → colour-coded hunk list with [BadgePill] summary
 * - binary → amber warning, no attempt at a line diff
 * - too large → amber warning naming both line counts, LCS never allocated
 *
 * Layout notes for the 720×1600 test device: the hunk list is a [LazyColumn] bounded by
 * [maxHeight] (so it works inside a parent `verticalScroll`), and each row scrolls horizontally
 * through a single shared [androidx.compose.foundation.ScrollState] so long lines never wrap and
 * the gutter stays aligned across rows.
 *
 * @param maxHeight upper bound for the hunk list; the list shrinks to fit shorter diffs.
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
    title: String = "Diff",
    maxHeight: Dp = 320.dp,
) {
    val model = remember(oldText, newText) { buildDiffModel(oldText, newText) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Summary header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (model.kind) {
                    DiffKind.ADDITIONS_ONLY -> BadgePill("+${model.added}", 0xFF10B981)
                    DiffKind.DELETIONS_ONLY -> BadgePill("−${model.removed}", 0xFFEF4444)
                    DiffKind.MIXED -> {
                        BadgePill("+${model.added}", 0xFF10B981)
                        BadgePill("−${model.removed}", 0xFFEF4444)
                    }
                    DiffKind.IDENTICAL -> BadgePill("IDENTICAL", 0xFF10B981)
                    DiffKind.BOTH_EMPTY -> BadgePill("EMPTY", 0xFF64748B)
                    DiffKind.BINARY -> BadgePill("BINARY", 0xFFF59E0B)
                    DiffKind.TOO_LARGE -> BadgePill("TOO LARGE", 0xFFF59E0B)
                }
                if (model.hasRenderableHunks) {
                    Text(
                        "=${model.unchanged}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ) {
            val message = diffStateMessage(model)
            if (message != null) {
                DiffStateNotice(kind = model.kind, message = message)
            } else {
                DiffHunkList(model = model, maxHeight = maxHeight)
            }
        }
    }
}

@Composable
private fun DiffStateNotice(kind: DiffKind, message: String) {
    val (icon, tint) = when (kind) {
        DiffKind.IDENTICAL -> Icons.Default.CheckCircle to DiffGreen
        DiffKind.BOTH_EMPTY -> Icons.Default.Difference to MaterialTheme.colorScheme.onSurfaceVariant
        DiffKind.BINARY -> Icons.Default.DataObject to DiffAmber
        DiffKind.TOO_LARGE -> Icons.Default.WarningAmber to DiffAmber
        // Unreachable for renderable kinds (diffStateMessage returns null), kept exhaustive.
        DiffKind.ADDITIONS_ONLY, DiffKind.DELETIONS_ONLY, DiffKind.MIXED ->
            Icons.Default.Block to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.width(20.dp).height(20.dp)
        )
        Text(
            message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DiffHunkList(model: DiffModel, maxHeight: Dp) {
    // One shared horizontal ScrollState keeps every row (and its gutter) aligned while long
    // lines are panned; rows recycled by the LazyColumn re-attach to the same offset.
    val horizontal = rememberScrollState()
    val gutterWidth = remember(model) { diffGutterWidth(model) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(vertical = 6.dp)
    ) {
        itemsIndexed(model.hunks) { _, hunk ->
            val (bg, fg) = when (hunk.op) {
                TextDiffer.Op.INSERT -> DiffGreen.copy(alpha = 0.10f) to DiffGreen
                TextDiffer.Op.DELETE -> DiffRed.copy(alpha = 0.10f) to DiffRed
                TextDiffer.Op.EQUAL -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val content = if (hunk.op == TextDiffer.Op.DELETE) hunk.oldLine else hunk.newLine
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(horizontal)
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                ) {
                    Text(
                        diffGutterText(hunk, gutterWidth) + " ",
                        color = fg.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        maxLines = 1,
                    )
                    Text(
                        content,
                        color = fg,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
