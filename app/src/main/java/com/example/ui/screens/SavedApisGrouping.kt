package com.example.ui.screens

import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiRequestItem

/**
 * Phase A (dossier Q3) — pure grouping/counting logic backing `SavedApisScreen`'s
 * collections-grouped card list (the `saved_apis` mockup HTML files: "GitHub REST API v3 — 14
 * endpoints · 2 environments"). Kept out of the composable so grouping correctness, the
 * ungrouped bucket, empty-collection handling, ordering stability, and the endpoint count can
 * all be unit tested without Compose.
 *
 * Deliberately does NOT surface an "N environments" count: [ApiFolderItem] (the only
 * collection/grouping concept `ApiCollectionsRepository` actually has) has no environment
 * association, and [com.example.data.env.EnvironmentRepository]'s environments are a single
 * global list, not scoped to a folder/collection. Inventing a per-collection environment count
 * here would be exactly the fabricated-data gap this pass forbids — see [CollectionSummary]'s
 * doc for what is shown instead.
 */

/** Sentinel id for the synthetic "Ungrouped" bucket returned by [groupRequestsIntoCollections]. */
const val UNGROUPED_COLLECTION_ID = "__ungrouped__"

/**
 * One collection card's worth of data. [folder] is null only for the synthetic "Ungrouped"
 * bucket holding every request whose `folderId` doesn't resolve to a real [ApiFolderItem] (no
 * folder at all, or a dangling id). [endpointCount] is the one count this pass can source
 * truthfully from real data ([requests].size); there is deliberately no `environmentCount` --
 * see this file's header doc.
 */
data class CollectionSummary(
    val folder: ApiFolderItem?,
    val requests: List<ApiRequestItem>,
) {
    val id: String get() = folder?.id ?: UNGROUPED_COLLECTION_ID
    val name: String get() = folder?.name ?: "Ungrouped"
    val endpointCount: Int get() = requests.size
    val isUngrouped: Boolean get() = folder == null
}

/**
 * Groups [requests] by [folders], preserving [folders]' own order (stable, deterministic --
 * never re-sorts collections by name/size/recency) with the "Ungrouped" bucket always last, and
 * only when it is non-empty (an install with zero unfiled requests never shows a pointless empty
 * "Ungrouped" card).
 *
 * Every folder in [folders] gets a [CollectionSummary] even when it has zero matching requests,
 * so a collection created with no requests yet still renders its own card (with an empty-state
 * placeholder in the UI) instead of silently disappearing from the list.
 *
 * Requests within each collection are sorted by [ApiRequestItem.updatedAt] descending (most
 * recently touched first), matching the flat list's pre-existing sort order so a request's
 * position within its card doesn't jump around for reasons unrelated to the grouping itself.
 */
fun groupRequestsIntoCollections(
    requests: List<ApiRequestItem>,
    folders: List<ApiFolderItem>,
): List<CollectionSummary> {
    val byFolderId = requests.groupBy { it.folderId }
    val knownFolderIds = folders.mapTo(mutableSetOf()) { it.id }

    val grouped = folders.map { folder ->
        CollectionSummary(
            folder = folder,
            requests = byFolderId[folder.id].orEmpty().sortedByDescending { it.updatedAt },
        )
    }

    val ungrouped = requests
        .filter { it.folderId == null || it.folderId !in knownFolderIds }
        .sortedByDescending { it.updatedAt }

    return if (ungrouped.isEmpty()) grouped else grouped + CollectionSummary(folder = null, requests = ungrouped)
}
