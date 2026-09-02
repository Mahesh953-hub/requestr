package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.RecentActivityRepository
import com.example.data.repository.RecentItemType
import com.example.data.repository.SettingsRepository
import com.example.ui.components.EmptyState
import com.example.ui.components.FloatingCapsuleHeader
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrFilterChip

/**
 * The full "Recently opened" page behind Home's "See all" link
 * (`docs/designs/home/home_rebranding.md` §6 / the reference screenshot's annotation 12: "Open
 * the full Recently opened page").
 *
 * Home shows the 4 most recent rows; this page shows the entire retained history
 * ([RecentActivityRepository.MAX_ENTRIES]) with a per-type filter. Rows, colours, labels and
 * timestamps are the shared ones from `RecentActivityUi.kt`, so the two surfaces cannot diverge.
 *
 * There is no seed data anywhere behind this: an empty page genuinely means nothing has been
 * opened yet.
 */
@Composable
fun RecentlyOpenedScreen(
    onBack: () -> Unit,
    navigator: RecentActivityNavigator,
) {
    val settings by SettingsRepository.settings.collectAsState()
    val recents by RecentActivityRepository.recents.collectAsState()
    var typeFilter by remember { mutableStateOf<RecentItemType?>(null) }

    val counts = remember(recents) { recentTypeCounts(recents) }
    // A type whose history has since been pruned or deleted must not stay selected, or the page
    // shows an empty list with no obvious way back to "All".
    val effectiveFilter = typeFilter?.takeIf { counts.containsKey(it) }
    val visible = remember(recents, effectiveFilter) { filterRecents(recents, effectiveFilter) }
    val nowMillis = rememberRelativeTimeTicker()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        FloatingCapsuleHeader(
            title = "Recently opened",
            icon = Icons.Default.History,
            onBack = onBack,
        )

        if (recents.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                EmptyState(
                    icon = Icons.Default.History,
                    message = "Nothing opened yet. APIs, notes, tools, chats and flows you open " +
                        "will appear here, newest first.",
                )
            }
            return@Column
        }

        // Filter chips only offer types that actually have history, each with its real count.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RequestrFilterChip(
                selected = effectiveFilter == null,
                onClick = { typeFilter = null },
                label = "All",
                count = recents.size,
            )
            RecentItemType.entries.forEach { type ->
                val count = counts[type] ?: return@forEach
                RequestrFilterChip(
                    selected = effectiveFilter == type,
                    onClick = { typeFilter = if (effectiveFilter == type) null else type },
                    label = type.label,
                    count = count,
                    icon = recentTypeIcon(type),
                )
            }
        }

        Text(
            text = "Newest first · keeps the last ${RecentActivityRepository.MAX_ENTRIES} items you opened",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = (settings.fabBottomSpacingDp + 16).dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { "${it.type.name}-${it.itemId}" }) { item ->
                    RecentActivityRow(
                        item = item,
                        nowMillis = nowMillis,
                        onClick = { navigator.open(item) },
                    )
                }
            }
        }
    }
}
