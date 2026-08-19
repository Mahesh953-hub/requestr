package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v3.1 B7 — real, app-wide density gating.
 *
 * Settings > Display Behavior exposes "Compact / Comfortable Density". Previously this
 * was stored and never read anywhere, so switching it produced zero visible change.
 * [AppSpacing] carries the actual dp tokens (list item vertical spacing, card internal
 * padding, section gaps) that shared list/card composables read via [LocalAppSpacing]
 * instead of hardcoding their own dp literals. [RequestrApp] (MainActivity.kt) provides
 * the resolved [AppSpacing] from live [com.example.data.repository.SettingsRepository]
 * state, keyed off `densityMode` ("COMPACT" vs "COMFORTABLE").
 *
 * Verified consumers (per the plan's explicit visual-verification requirement): the
 * Intercept traffic log's [com.example.ui.screens.TrafficItemCard]/list spacing and the
 * Saved APIs collection list's [com.example.ui.screens.SavedApiCard]/list spacing both
 * read these tokens, so toggling Compact vs Comfortable visibly tightens/loosens row
 * spacing and card padding on both screens.
 */
data class AppSpacing(
    val listItemSpacing: Dp,
    val cardPadding: Dp,
    val sectionGap: Dp,
)

private val CompactSpacing = AppSpacing(
    listItemSpacing = 4.dp,
    cardPadding = 8.dp,
    sectionGap = 4.dp,
)

private val ComfortableSpacing = AppSpacing(
    listItemSpacing = 8.dp,
    cardPadding = 14.dp,
    sectionGap = 8.dp,
)

val LocalAppSpacing = staticCompositionLocalOf { ComfortableSpacing }

@Composable
fun ProvideDensityConfig(densityMode: String, content: @Composable () -> Unit) {
    val spacing = if (densityMode.equals("COMPACT", ignoreCase = true)) CompactSpacing else ComfortableSpacing
    CompositionLocalProvider(LocalAppSpacing provides spacing) {
        content()
    }
}
