package com.example.ui.screens

import com.example.data.search.GlobalSearchHit

/**
 * Pure filter/sort logic behind Home's search bar and its advanced filter panel.
 *
 * The reference screenshot (`docs/designs/home/home-section_prototype_detailed.png`, annotation 8)
 * specifies a trailing sliders icon on the search bar that opens an "Advanced Filters" panel to
 * refine search results. Before this it was an icon with nothing behind it.
 *
 * Extracted from the composable so it is unit-testable on the JVM, following the established
 * pattern in this codebase (`SavedApisGrouping.kt`, `CapturedTrafficHelpers.kt`, …).
 */

/** The result categories the panel can filter by — one per [GlobalSearchHit] subtype. */
enum class HomeSearchType(val label: String) {
    API("APIs"),
    NOTE("Notes"),
    TOOL("Tools"),
    FLOW("Flow"),
    CHAT("Chats"),
}

/** Which category a hit belongs to. Exhaustive over [GlobalSearchHit]. */
fun searchTypeOf(hit: GlobalSearchHit): HomeSearchType = when (hit) {
    is GlobalSearchHit.Api -> HomeSearchType.API
    is GlobalSearchHit.Note -> HomeSearchType.NOTE
    is GlobalSearchHit.Tool -> HomeSearchType.TOOL
    is GlobalSearchHit.FlowNode -> HomeSearchType.FLOW
    is GlobalSearchHit.Chat -> HomeSearchType.CHAT
}

/**
 * Sort order for results.
 *
 * [BEST_MATCH] preserves `GlobalSearch`'s own ordering (grouped by category), which is the
 * closest thing to relevance the substring matcher actually provides — it is deliberately not
 * called "Relevance", because that would imply a scoring model that does not exist.
 */
enum class HomeSearchSort(val label: String) {
    BEST_MATCH("Best match"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
}

/**
 * The panel's state. An empty [types] set means "no category filter" rather than "match nothing",
 * so the panel can never put search into a state where it silently returns nothing while the user
 * is still typing.
 */
data class HomeSearchFilter(
    val types: Set<HomeSearchType> = emptySet(),
    val sort: HomeSearchSort = HomeSearchSort.BEST_MATCH,
) {
    /** True when the filter changes nothing — drives the "active" dot on the sliders icon. */
    val isDefault: Boolean get() = types.isEmpty() && sort == HomeSearchSort.BEST_MATCH

    fun toggleType(type: HomeSearchType): HomeSearchFilter =
        copy(types = if (type in types) types - type else types + type)
}

/** Applies [filter] to [hits]. Stable: equal titles keep their incoming relative order. */
fun applyHomeSearchFilter(
    hits: List<GlobalSearchHit>,
    filter: HomeSearchFilter,
): List<GlobalSearchHit> {
    val byType =
        if (filter.types.isEmpty()) hits
        else hits.filter { searchTypeOf(it) in filter.types }

    return when (filter.sort) {
        HomeSearchSort.BEST_MATCH -> byType
        HomeSearchSort.TITLE_ASC -> byType.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        HomeSearchSort.TITLE_DESC -> byType.sortedWith(
            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
    }
}

/**
 * Per-category counts over the *unfiltered* hits, so a category chip shows how many results it
 * would reveal rather than a count that collapses to zero as soon as it is deselected.
 */
fun homeSearchTypeCounts(hits: List<GlobalSearchHit>): Map<HomeSearchType, Int> =
    hits.groupingBy { searchTypeOf(it) }.eachCount()
