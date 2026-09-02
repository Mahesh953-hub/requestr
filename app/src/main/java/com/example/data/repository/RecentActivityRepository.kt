package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.RecentActivityDao
import com.example.data.local.RecentActivityEntity
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The kinds of item Home's "Recently opened" section can show a row for. [label] is the
 * human-readable type for the `"{Type} • {SUBTYPE}"` line (home_rebranding.md §6), e.g.
 * `"Note • MARKDOWN"`, `"API • GET"`, `"Tool • DECODERS"`, `"Chat • CONVERSATION"`.
 */
enum class RecentItemType(val label: String) {
    API("API"),
    NOTE("Note"),
    TOOL("Tool"),
    CHAT("Chat"),
    FLOW("Flow"),
}

/**
 * One "recently opened" row as Home consumes it. A self-contained snapshot -- see
 * [com.example.data.local.RecentActivityEntity] -- so the UI never has to re-query the source
 * table to render it. [openedAt] is the real epoch-millis of the most recent open; format it with
 * [RelativeTimeFormatter.format].
 */
data class RecentActivityItem(
    val type: RecentItemType,
    val itemId: String,
    val subtype: String,
    val title: String,
    val preview: String,
    val openedAt: Long,
)

/**
 * Real, persisted, deduplicated, bounded "recently opened" history, backing Home's
 * "Recently opened" section (home_rebranding.md §6) and replacing the list Home used to fabricate
 * from `savedApis.take(2) + notes.take(2)` with literal `"Recent"`/`"Saved"` timestamps.
 *
 * **Who writes to it.** Not the UI. Every item type's open event is recorded by the repository
 * function that already mediates opening that item, so opening a note from the Notes list, from
 * search, or from Home's own recents row all record identically:
 *
 * - saved API request: [ApiCollectionsRepository.setActiveRequest]
 * - note:              [NotesRepository.openNote]
 * - tool:              [ToolsRepository.openTool] (built-in and custom tools alike)
 * - chat conversation: [ChatRepository.switchToConversation] / [ChatRepository.startNewChat]
 * - flow pipeline:     [FlowRepository.setActiveFlow]
 *
 * Those same repositories call [updateSnapshot] when the item is re-saved/renamed (so a stale
 * title is never shown) and [forget] when it is deleted (so a row never navigates into nothing).
 *
 * **Semantics.** Re-opening an item updates its single row's timestamp (composite primary key,
 * REPLACE) -- this is "recently opened", not "every open ever". The table is pruned to
 * [MAX_ENTRIES] rows after every write. There is NO seed data: an empty table means nothing has
 * been opened yet, matching [NotesRepository]/[ChatRepository]'s own no-seed-data decisions.
 *
 * **Cost.** [recordOpened] captures the timestamp synchronously and enqueues the write on a
 * strictly FIFO chain of IO coroutines; it never blocks the caller and a failed write is logged,
 * never thrown -- a broken history write must not break opening the item. Each queued write runs
 * to completion before the next starts (a job chain, not just a single-threaded dispatcher --
 * Room's suspend calls hop to its own executor, so a dispatcher alone would let a second write
 * start while the first was mid-query), so an upsert and the prune that follows it can never
 * interleave with another open's pair and the table is never left one row over the cap.
 *
 * Call [init] once from MainActivity.onCreate(), mirroring [NotesRepository.init]. The
 * repositories listed above also call [init] from their own `init` -- it is idempotent -- so the
 * recorder is attached whenever any producer of open events is.
 */
object RecentActivityRepository {

    /**
     * Retained-history cap. Home shows 4 rows and the spec's "See all" page shows the rest; 50
     * comfortably covers several sessions of real work across five item types while keeping the
     * table trivially small (every row is a few hundred bytes of text) and the prune query
     * effectively free. Anything older than the 50th most-recent open is, by definition, no
     * longer "recent".
     */
    const val MAX_ENTRIES = 50

    /** Longest stored preview. Rows render one muted line; anything past this is never shown. */
    private const val PREVIEW_MAX_CHARS = 120

    private const val TAG = "RecentActivity"

    private val _recents = MutableStateFlow<List<RecentActivityItem>>(emptyList())

    /** Most recently opened first, at most [MAX_ENTRIES]. Home `collectAsState`s this. */
    val recents: StateFlow<List<RecentActivityItem>> = _recents.asStateFlow()

    @Volatile
    private var dao: RecentActivityDao? = null

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    // FIFO write chain (see the class doc): every write joins the previous one before running, so
    // writes complete strictly in submission order regardless of which thread Room runs them on.
    private val queueLock = Any()
    private var writeTail: Job = Job().also { it.complete() }

    private fun enqueueWrite(label: String, block: suspend () -> Unit) {
        synchronized(queueLock) {
            val previous = writeTail
            writeTail = writeScope.launch {
                previous.join()
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "$label failed: ${e.message}")
                }
            }
        }
    }

    /** Wall clock for `openedAt`; swapped by tests so ordering assertions are deterministic. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Attaches to the real `recent_activity` table. Idempotent. */
    fun init(context: Context) {
        if (dao != null) return
        attach(RequestrDatabase.get(context.applicationContext).recentActivityDao())
    }

    /**
     * Records that the user just opened [itemId] of [type]. Upserts the (type, id) row with a
     * fresh `openedAt`, then prunes the table to [MAX_ENTRIES]. Fire-and-forget: returns
     * immediately, never throws. A blank [itemId] is ignored -- there is nothing to reopen.
     */
    fun recordOpened(type: RecentItemType, itemId: String, subtype: String, title: String, preview: String) {
        val d = dao ?: return
        if (itemId.isBlank()) return
        val entity = RecentActivityEntity(
            itemType = type.name,
            itemId = itemId,
            subtype = subtype.trim(),
            title = title.trim(),
            preview = normalisePreview(preview),
            openedAt = clock(),
        )
        enqueueWrite("recordOpened(${type.name}, $itemId); item still opened") {
            d.upsert(entity)
            d.pruneOlderThanNewest(MAX_ENTRIES)
        }
    }

    /**
     * Refreshes the stored title/subtype/preview of an item that is already in the history
     * without changing its `openedAt` -- editing or renaming something is not opening it. No-op
     * when the item was never opened. Fire-and-forget, never throws.
     */
    fun updateSnapshot(type: RecentItemType, itemId: String, subtype: String, title: String, preview: String) {
        val d = dao ?: return
        if (itemId.isBlank()) return
        enqueueWrite("updateSnapshot(${type.name}, $itemId)") {
            d.updateSnapshot(type.name, itemId, subtype.trim(), title.trim(), normalisePreview(preview))
        }
    }

    /** Removes the row for a deleted item so Home never shows a row that opens nothing. */
    fun forget(type: RecentItemType, itemId: String) {
        val d = dao ?: return
        enqueueWrite("forget(${type.name}, $itemId)") { d.delete(type.name, itemId) }
    }

    /** Removes every row of one type -- e.g. after "Clear ALL conversations". */
    fun forgetAll(type: RecentItemType) {
        val d = dao ?: return
        enqueueWrite("forgetAll(${type.name})") { d.deleteAllOfType(type.name) }
    }

    /** Wipes the whole history (a "Clear recently opened" action). */
    fun clearAll() {
        val d = dao ?: return
        enqueueWrite("clearAll") { d.clear() }
    }

    /**
     * Test-only: binds this singleton to an in-memory [RequestrDatabase] instead of the shared
     * file-backed one, replacing any previous binding (the `object` outlives a test class in the
     * same JVM fork -- same reasoning as [ChatRepository.resetForTest]).
     */
    internal fun attachForTest(db: RequestrDatabase) {
        detach()
        attach(db.recentActivityDao())
    }

    /** Test-only: undoes [init]/[attachForTest] and restores the real clock. */
    internal fun resetForTest() {
        detach()
        clock = System::currentTimeMillis
    }

    /**
     * Test-only: suspends until every write queued before this call has completed. Sound because
     * the write chain is strictly FIFO -- the current tail completes only after everything queued
     * before it has finished.
     */
    internal suspend fun awaitPendingWrites() {
        synchronized(queueLock) { writeTail }.join()
    }

    private fun attach(d: RecentActivityDao) {
        dao = d
        observeJob = observeScope.launch {
            d.observeRecent(MAX_ENTRIES).collect { rows ->
                _recents.value = rows.mapNotNull { it.toItem() }
            }
        }
    }

    private fun detach() {
        observeJob?.cancel()
        observeJob = null
        dao = null
        _recents.value = emptyList()
    }

    /** One line, whitespace-collapsed, capped -- the row renders a single muted preview line. */
    private fun normalisePreview(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(PREVIEW_MAX_CHARS)

    private fun RecentActivityEntity.toItem(): RecentActivityItem? {
        // A row whose type this build no longer knows (only possible if a type is ever removed
        // from the enum) is skipped rather than crashing Home.
        val type = RecentItemType.entries.firstOrNull { it.name == itemType } ?: return null
        return RecentActivityItem(
            type = type,
            itemId = itemId,
            subtype = subtype,
            title = title,
            preview = preview,
            openedAt = openedAt,
        )
    }
}
