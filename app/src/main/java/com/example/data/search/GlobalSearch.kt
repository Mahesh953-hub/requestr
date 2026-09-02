package com.example.data.search

import com.example.data.models.ApiRequestItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.NoteItem
import com.example.data.repository.ToolItem

/**
 * A chat conversation as [GlobalSearch] sees it: the title the drawer shows plus every message
 * body exactly as the bubble renders it (attachment markers already stripped by
 * [com.example.data.repository.ChatRepository], which publishes these as
 * `ChatRepository.searchableConversations`). Deliberately a plain value type with no Room or
 * `org.json` dependency so search stays a pure function over in-memory lists.
 */
data class ChatSearchEntry(
    val conversationId: String,
    val title: String,
    val messages: List<String>,
)

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

    /**
     * A conversation whose title or message content matched. [conversationId] is what the UI
     * hands to `ChatRepository.switchToConversation` before navigating to the `"chat"` route.
     * [matchedMessage] is the first message body that contained the query (null when only the
     * title matched); [messageCount] is the conversation's real message count.
     */
    data class Chat(
        val conversationId: String,
        override val title: String,
        val matchedMessage: String?,
        val messageCount: Int,
    ) : GlobalSearchHit {
        override val subtitle: String
            get() = matchedMessage?.take(60)
                ?: if (messageCount == 1) "1 message" else "$messageCount messages"
    }
}

/**
 * Real global search used by Home, replacing the dead `searchQuery` state that
 * previously had no filter, no results, and no navigation behind it.
 *
 * Matches case-insensitively against the fields the user actually sees:
 * api `name`+`url`, note `title`+`content`, tool `title`+`description`,
 * flow node `title`, chat conversation `title`+every message body. Follows the
 * substring style already used by `ToolsScreen.kt`'s `filteredTools` filter.
 *
 * A pure function over in-memory lists: callers pass already-collected `StateFlow` values
 * (chat content arrives via `ChatRepository.searchableConversations`), so search never does
 * blocking I/O inside composition.
 */
object GlobalSearch {

    fun search(
        query: String,
        apis: List<ApiRequestItem> = emptyList(),
        notes: List<NoteItem> = emptyList(),
        tools: List<ToolItem> = emptyList(),
        flows: List<FlowNodeItem> = emptyList(),
        chats: List<ChatSearchEntry> = emptyList(),
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

        val chatHits = chats.mapNotNull { chat ->
            val matchedMessage = chat.messages.firstOrNull { it.contains(q, ignoreCase = true) }
            val titleMatches = chat.title.contains(q, ignoreCase = true)
            if (!titleMatches && matchedMessage == null) return@mapNotNull null
            GlobalSearchHit.Chat(
                conversationId = chat.conversationId,
                title = chat.title,
                matchedMessage = matchedMessage?.replace(Regex("\\s+"), " ")?.trim(),
                messageCount = chat.messages.size,
            )
        }

        return apiHits + noteHits + toolHits + flowHits + chatHits
    }
}
