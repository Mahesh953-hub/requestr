package com.example.data.search

import com.example.data.models.ApiRequestItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.NoteItem
import com.example.data.repository.ToolItem

/** What a [GlobalSearchHit] points at, so the UI can route the tap correctly. */
sealed interface GlobalSearchHit {
    val title: String
    val subtitle: String

    data class Api(val item: ApiRequestItem) : GlobalSearchHit {
        override val title: String get() = item.name
        override val subtitle: String get() = item.url
    }

    data class Note(val item: NoteItem) : GlobalSearchHit {
        override val title: String get() = item.title
        override val subtitle: String get() = item.content.take(60)
    }

    data class Tool(val item: ToolItem) : GlobalSearchHit {
        override val title: String get() = item.title
        override val subtitle: String get() = item.description
    }

    data class FlowNode(val item: FlowNodeItem) : GlobalSearchHit {
        override val title: String get() = item.title
        override val subtitle: String get() = item.type.label
    }
}

/**
 * Real global search used by Home, replacing the dead `searchQuery` state that
 * previously had no filter, no results, and no navigation behind it.
 *
 * Matches case-insensitively against the fields the user actually sees:
 * api `name`+`url`, note `title`+`content`, tool `title`+`description`,
 * flow node `title`. Follows the substring style already used by
 * `ToolsScreen.kt`'s `filteredTools` filter.
 */
object GlobalSearch {

    fun search(
        query: String,
        apis: List<ApiRequestItem> = emptyList(),
        notes: List<NoteItem> = emptyList(),
        tools: List<ToolItem> = emptyList(),
        flows: List<FlowNodeItem> = emptyList(),
    ): List<GlobalSearchHit> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()

        val apiHits = apis.filter { it.name.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .map { GlobalSearchHit.Api(it) }

        val noteHits = notes.filter { it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
            .map { GlobalSearchHit.Note(it) }

        val toolHits = tools.filter { it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
            .map { GlobalSearchHit.Tool(it) }

        val flowHits = flows.filter { it.title.contains(q, ignoreCase = true) }
            .map { GlobalSearchHit.FlowNode(it) }

        return apiHits + noteHits + toolHits + flowHits
    }
}
