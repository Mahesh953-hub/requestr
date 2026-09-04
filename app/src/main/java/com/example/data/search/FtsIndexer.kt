package com.example.data.search

import android.util.Log
import androidx.room.useWriterConnection
import com.example.data.local.GrpcMessageEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.SseEventEntity
import com.example.data.local.WebSocketMessageEntity

/**
 * S3 task 22: folds protocol child payloads into the parent's `traffic_fts.body_text` so a
 * WebSocket connection, an event stream or a gRPC call is findable by the content of one of its
 * messages. The parent row itself is indexed by the [FtsSchema] triggers; this only *appends*.
 *
 * Called by `PersistenceQueue` right after a child batch commits. Best-effort by contract: an index
 * failure is logged and skipped, never surfaced to the writer -- a search-index problem must never
 * lose captured traffic (persistence subplan, "failure paths"). No-op while [FtsSchema.available]
 * is false (framework SQLite without FTS5), where free text is searched with `LIKE` instead.
 */
object FtsIndexer {
    private const val TAG = "FtsIndexer"

    /** Per-append cap so a chatty socket cannot grow one FTS document without bound. */
    const val MAX_APPEND_CHARS = 16_384

    /** Search text of one WebSocket message: its text payload (binary frames contribute nothing). */
    fun childText(message: WebSocketMessageEntity): String? = message.payloadText?.takeIf { it.isNotBlank() }

    /** Search text of SSE events: event names and data. */
    fun childText(events: List<SseEventEntity>): String? =
        events.asSequence()
            .flatMap { sequenceOf(it.eventName, it.data) }
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    /** Search text of one gRPC message: the decoded protobuf rendering (raw bytes are not indexed). */
    fun childText(message: GrpcMessageEntity): String? = message.decodedText?.takeIf { it.isNotBlank() }

    /**
     * Appends [text] to the parent's `body_text`. Runs outside the child's transaction on purpose: if
     * FTS5 is missing or the statement fails, the children are already committed.
     */
    suspend fun appendChildText(db: RequestrDatabase, trafficId: Long, text: String) {
        if (!FtsSchema.available.get()) return
        val clipped = if (text.length > MAX_APPEND_CHARS) text.substring(0, MAX_APPEND_CHARS) else text
        try {
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    "UPDATE ${FtsSchema.TABLE} SET body_text = body_text || ' ' || ? WHERE rowid = ?",
                ) { stmt ->
                    stmt.bindText(1, clipped)
                    stmt.bindLong(2, trafficId)
                    stmt.step()
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "FTS child append for #$trafficId failed: ${t.message}")
        }
    }

    /**
     * Population-time counterpart of [appendChildText]: folds every existing child's text into its
     * parent's document in three set-based statements. Called once by [FtsSchema.ensure] right after
     * the parents are indexed.
     */
    internal fun populateChildren(sql: FtsSchema.Sql) {
        sql.exec(
            "UPDATE ${FtsSchema.TABLE} SET body_text = body_text || ' ' || " +
                "(SELECT group_concat(w.payloadText, ' ') FROM websocket_messages w WHERE w.trafficId = ${FtsSchema.TABLE}.traffic_id AND w.payloadText IS NOT NULL) " +
                "WHERE traffic_id IN (SELECT DISTINCT trafficId FROM websocket_messages WHERE payloadText IS NOT NULL)",
        )
        sql.exec(
            "UPDATE ${FtsSchema.TABLE} SET body_text = body_text || ' ' || " +
                "(SELECT group_concat(COALESCE(s.eventName, '') || ' ' || s.data, ' ') FROM sse_events s WHERE s.trafficId = ${FtsSchema.TABLE}.traffic_id) " +
                "WHERE traffic_id IN (SELECT DISTINCT trafficId FROM sse_events)",
        )
        sql.exec(
            "UPDATE ${FtsSchema.TABLE} SET body_text = body_text || ' ' || " +
                "(SELECT group_concat(g.decodedText, ' ') FROM grpc_messages g WHERE g.trafficId = ${FtsSchema.TABLE}.traffic_id AND g.decodedText IS NOT NULL) " +
                "WHERE traffic_id IN (SELECT DISTINCT trafficId FROM grpc_messages WHERE decodedText IS NOT NULL)",
        )
    }
}
