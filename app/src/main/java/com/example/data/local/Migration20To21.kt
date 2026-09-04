package com.example.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * S3 task 21 -- v20 -> v21, the one-time backfill of the pre-v20 flattened protocol rows
 * (plan §4, v21 row).
 *
 * Schema: one nullable column, `captured_traffic.legacyBackfill`, stamped on every parent row this
 * migration synthesizes (`"v21-ws"` / `"v21-grpc"`). Nothing else changes shape.
 *
 * Data, in the same transaction Room wraps the migration in:
 *
 *  1. **WebSocket.** Every `method IN ('WS→', 'WS←')` row (one per frame, `url = "<wsUrl> (<OPCODE>)"`,
 *     payload text in `requestBody`/`responseBody`) is grouped by `(sessionId, connectionId, wsUrl)`
 *     into ONE synthesized parent -- the 101 upgrade exchange the engine never persisted as such
 *     (`method = GET`, `statusCode = 101`, `protocol = WEBSOCKET`, timestamp = first frame) -- and
 *     each frame becomes a `websocket_messages` child (`frameIndex` in timestamp/id order, opcode
 *     from the URL suffix, `sizeBytes` = the frame's wire length the flat row recorded).
 *  2. **gRPC.** Every `protocol = 'gRPC'` row carrying the synthetic `x-grpc-stream-id` header (one
 *     per message, hex-dumped payload in the body column) is grouped by
 *     `(sessionId, connectionId, url, x-grpc-stream-id)` into ONE synthesized parent per call
 *     (`statusCode` stays NULL -- the flat rows never recorded the HTTP status), each message
 *     becomes a `grpc_messages` child (payload re-parsed from the hex dump when it was complete,
 *     `decodedText` from `x-protobuf-decoded`) and the call gets its `grpc_calls` row with the
 *     last `x-grpc-status` seen as the trailer status.
 *  3. The converted flat rows are deleted (their children cascade). Nothing is invented: unknown
 *     fields stay NULL, sizes are the sums the flat rows carried, `captureMethod`/`appPackage`/
 *     `appUid`/`sourceIp`/`wasDecrypted` are copied from the first frame of the group.
 *
 * Idempotent: the selection only matches flat rows, which no longer exist after step 3, and a
 * live-captured v20 parent never carries `x-grpc-stream-id` nor a `WS→` method, so re-running
 * [backfill] on an already-converted database is a no-op. [backfill] is `internal` so the
 * migration test can prove exactly that.
 *
 * Group identity caveat, documented rather than papered over: MITM-relay flat rows recorded
 * `connectionId = "host:port"` (not a per-socket id), so several legacy WS connections to the same
 * endpoint in one session fold into one synthesized parent. Tun-path rows carry the real 5-tuple
 * key and stay separate. A spilled (file-backed) legacy body has only its stored preview in the row;
 * such a message is imported with `payloadTruncated = 1`.
 */
val MIGRATION_20_21: Migration = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN legacyBackfill TEXT")
        Migration20To21.backfill(db)
    }
}

internal object Migration20To21 {
    const val STAMP_WS = "v21-ws"
    const val STAMP_GRPC = "v21-grpc"

    /** What one run converted; returned for logging/tests. */
    data class Result(val wsParents: Int, val wsMessages: Int, val grpcParents: Int, val grpcMessages: Int)

    private val converters = RequestrTypeConverters()

    fun backfill(db: SupportSQLiteDatabase): Result {
        val ws = backfillWebSocket(db)
        val grpc = backfillGrpc(db)
        return Result(ws.first, ws.second, grpc.first, grpc.second)
    }

    // -- shared -----------------------------------------------------------------------------------

    private class FlatRow(c: Cursor) {
        val id = c.getLong(c.getColumnIndexOrThrow("id"))
        val sessionId = c.getLong(c.getColumnIndexOrThrow("sessionId"))
        val method = c.getString(c.getColumnIndexOrThrow("method"))
        val url = c.getString(c.getColumnIndexOrThrow("url"))
        val host = c.getString(c.getColumnIndexOrThrow("host"))
        val path = c.getString(c.getColumnIndexOrThrow("path"))
        val requestHeaders = c.getString(c.getColumnIndexOrThrow("requestHeaders"))
        val responseHeaders = c.getString(c.getColumnIndexOrThrow("responseHeaders"))
        val requestBody = c.optString("requestBody")
        val responseBody = c.optString("responseBody")
        val requestBodyPreview = c.optString("requestBodyPreview")
        val responseBodyPreview = c.optString("responseBodyPreview")
        val requestBodyFilePath = c.optString("requestBodyFilePath")
        val responseBodyFilePath = c.optString("responseBodyFilePath")
        val requestSizeBytes = c.getLong(c.getColumnIndexOrThrow("requestSizeBytes"))
        val responseSizeBytes = c.getLong(c.getColumnIndexOrThrow("responseSizeBytes"))
        val timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
        val appPackage = c.optString("appPackage")
        val appUid = c.getColumnIndexOrThrow("appUid").let { if (c.isNull(it)) null else c.getInt(it) }
        val wasDecrypted = c.getInt(c.getColumnIndexOrThrow("wasDecrypted"))
        val sourceIp = c.optString("sourceIp")
        val connectionId = c.optString("connectionId")
        val captureMethod = c.getString(c.getColumnIndexOrThrow("captureMethod"))

        private fun Cursor.optString(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    }

    private const val FLAT_COLUMNS = "id, sessionId, method, url, host, path, requestHeaders, responseHeaders, requestBody, " +
        "responseBody, requestBodyPreview, responseBodyPreview, requestBodyFilePath, responseBodyFilePath, " +
        "requestSizeBytes, responseSizeBytes, timestamp, appPackage, appUid, wasDecrypted, sourceIp, connectionId, captureMethod"

    private fun readFlat(db: SupportSQLiteDatabase, where: String): List<FlatRow> {
        val rows = ArrayList<FlatRow>()
        db.query("SELECT $FLAT_COLUMNS FROM captured_traffic WHERE $where ORDER BY timestamp, id").use { c ->
            while (c.moveToNext()) rows += FlatRow(c)
        }
        return rows
    }

    private fun parentValues(first: FlatRow, stamp: String, method: String, url: String, path: String, statusCode: Int?, protocol: String,
                             requestHeaders: String, responseHeaders: String, requestBytes: Long, responseBytes: Long, timestamp: Long) =
        ContentValues().apply {
            put("sessionId", first.sessionId)
            put("method", method)
            put("url", url)
            put("host", first.host)
            put("path", path)
            if (statusCode == null) putNull("statusCode") else put("statusCode", statusCode)
            put("protocol", protocol)
            put("requestHeaders", requestHeaders)
            put("responseHeaders", responseHeaders)
            putNull("requestBody")
            putNull("responseBody")
            put("requestSizeBytes", requestBytes)
            put("responseSizeBytes", responseBytes)
            put("latencyMs", 0L)
            put("timestamp", timestamp)
            if (first.appPackage == null) putNull("appPackage") else put("appPackage", first.appPackage)
            if (first.appUid == null) putNull("appUid") else put("appUid", first.appUid)
            put("wasDecrypted", first.wasDecrypted)
            put("isUndecoded", 0)
            if (first.sourceIp == null) putNull("sourceIp") else put("sourceIp", first.sourceIp)
            if (first.connectionId == null) putNull("connectionId") else put("connectionId", first.connectionId)
            put("isRequestBodyBinary", 0)
            put("isResponseBodyBinary", 0)
            put("requestBodyBytesTruncated", 0)
            put("responseBodyBytesTruncated", 0)
            put("captureMethod", first.captureMethod)
            put("requestBodyStoredBytes", 0L)
            put("responseBodyStoredBytes", 0L)
            put("requestBodyTotalBytes", requestBytes)
            put("responseBodyTotalBytes", responseBytes)
            put("legacyBackfill", stamp)
        }

    private fun deleteFlat(db: SupportSQLiteDatabase, ids: List<Long>) {
        ids.chunked(500).forEach { chunk ->
            db.execSQL("DELETE FROM captured_traffic WHERE id IN (${chunk.joinToString(",")})")
        }
    }

    // -- WebSocket --------------------------------------------------------------------------------

    private val OPCODES = mapOf(
        "CONTINUATION" to 0x0, "TEXT" to 0x1, "BINARY" to 0x2, "CLOSE" to 0x8, "PING" to 0x9, "PONG" to 0xA,
    )

    /** `"wss://h/p (TEXT)"` -> `("wss://h/p", 0x1)`; an unrecognised/absent suffix maps to TEXT. */
    internal fun splitWsUrl(url: String): Pair<String, Int> {
        val open = url.lastIndexOf(" (")
        if (open < 0 || !url.endsWith(")")) return url to 0x1
        val name = url.substring(open + 2, url.length - 1)
        val opcode = OPCODES[name] ?: name.removePrefix("OPCODE_").toIntOrNull() ?: return url to 0x1
        return url.substring(0, open) to opcode
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash)
    }

    private fun backfillWebSocket(db: SupportSQLiteDatabase): Pair<Int, Int> {
        val flat = readFlat(db, "method IN ('WS→', 'WS←')")
        if (flat.isEmpty()) return 0 to 0
        val groups = LinkedHashMap<Triple<Long, String?, String>, MutableList<FlatRow>>()
        for (row in flat) {
            val (base, _) = splitWsUrl(row.url)
            groups.getOrPut(Triple(row.sessionId, row.connectionId, base)) { ArrayList() } += row
        }
        var messages = 0
        val upgradeHeaders = converters.stringMapToJson(mapOf("upgrade" to "websocket", "connection" to "Upgrade"))
        for ((key, rows) in groups) {
            val first = rows.first()
            val parentId = db.insert(
                "captured_traffic", SQLiteDatabase.CONFLICT_ABORT,
                parentValues(
                    first, STAMP_WS, method = "GET", url = key.third, path = pathOf(key.third), statusCode = 101,
                    protocol = "WEBSOCKET", requestHeaders = upgradeHeaders, responseHeaders = upgradeHeaders,
                    requestBytes = rows.sumOf { it.requestSizeBytes }, responseBytes = rows.sumOf { it.responseSizeBytes },
                    timestamp = first.timestamp,
                ),
            )
            rows.forEachIndexed { index, row ->
                val isClient = row.method == "WS→"
                val (_, opcode) = splitWsUrl(row.url)
                val text = if (isClient) row.requestBody ?: row.requestBodyPreview else row.responseBody ?: row.responseBodyPreview
                val spilled = if (isClient) row.requestBodyFilePath != null else row.responseBodyFilePath != null
                db.insert(
                    "websocket_messages", SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("trafficId", parentId)
                        put("connectionId", row.connectionId ?: key.third)
                        put("direction", if (isClient) MessageDirection.CLIENT.name else MessageDirection.SERVER.name)
                        put("opcode", opcode)
                        put("isText", if (opcode == 0x1) 1 else 0)
                        if (text == null) putNull("payloadText") else put("payloadText", text)
                        putNull("payloadBytes")
                        put("payloadTruncated", if (spilled) 1 else 0)
                        put("sizeBytes", if (isClient) row.requestSizeBytes else row.responseSizeBytes)
                        put("timestampMs", row.timestamp)
                        put("frameIndex", index)
                    },
                )
                messages++
            }
        }
        deleteFlat(db, flat.map { it.id })
        return groups.size to messages
    }

    // -- gRPC -------------------------------------------------------------------------------------

    private val GRPC_META_KEYS = setOf(
        "x-grpc-encoding", "x-grpc-stream-id", "x-grpc-message-type", "x-grpc-compressed", "x-grpc-status", "x-protobuf-decoded",
    )

    /** Inverse of `ProtobufDecoder.hexDump`: `"08 2a 12"` -> bytes; a `" …(+NB)"` tail marks a truncated dump. */
    internal fun parseHexDump(dump: String): Pair<ByteArray?, Boolean> {
        if (dump.isBlank()) return null to false
        val truncated = dump.contains(" …(+")
        val body = if (truncated) dump.substringBefore(" …(+") else dump
        val out = java.io.ByteArrayOutputStream()
        for (tok in body.trim().split(' ')) {
            if (tok.length != 2) return null to truncated
            val v = tok.toIntOrNull(16) ?: return null to truncated
            out.write(v)
        }
        return out.toByteArray() to truncated
    }

    private fun backfillGrpc(db: SupportSQLiteDatabase): Pair<Int, Int> {
        val flat = readFlat(
            db,
            "protocol = 'gRPC' AND (requestHeaders LIKE '%x-grpc-stream-id%' OR responseHeaders LIKE '%x-grpc-stream-id%')",
        )
        if (flat.isEmpty()) return 0 to 0

        class Msg(val row: FlatRow, val reqHeaders: Map<String, String>, val meta: Map<String, String>) {
            val isClient = row.requestSizeBytes > 0 || (row.responseSizeBytes == 0L && row.requestBody != null)
        }
        val groups = LinkedHashMap<List<Any?>, MutableList<Msg>>()
        val converted = ArrayList<Long>()
        for (row in flat) {
            val req = converters.jsonToStringMap(row.requestHeaders)
            val resp = converters.jsonToStringMap(row.responseHeaders)
            val meta = (req.filterKeys { it in GRPC_META_KEYS } + resp.filterKeys { it in GRPC_META_KEYS })
            val streamId = meta["x-grpc-stream-id"]?.toIntOrNull() ?: continue // not a legacy message row after all
            converted += row.id
            groups.getOrPut(listOf(row.sessionId, row.connectionId, row.url, streamId)) { ArrayList() } += Msg(row, req, meta)
        }
        var messages = 0
        for ((key, msgs) in groups) {
            val first = msgs.first().row
            val streamId = key[3] as Int
            val callHeaders = msgs.firstOrNull { it.isClient }?.reqHeaders?.filterKeys { it !in GRPC_META_KEYS } ?: emptyMap()
            val parentId = db.insert(
                "captured_traffic", SQLiteDatabase.CONFLICT_ABORT,
                parentValues(
                    first, STAMP_GRPC, method = first.method, url = first.url, path = first.path, statusCode = null,
                    protocol = "gRPC", requestHeaders = converters.stringMapToJson(callHeaders), responseHeaders = "{}",
                    requestBytes = msgs.sumOf { it.row.requestSizeBytes }, responseBytes = msgs.sumOf { it.row.responseSizeBytes },
                    timestamp = first.timestamp,
                ),
            )
            msgs.forEachIndexed { index, m ->
                val dump = if (m.isClient) m.row.requestBody else m.row.responseBody
                val (payload, truncated) = dump?.let { parseHexDump(it) } ?: (null to false)
                db.insert(
                    "grpc_messages", SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("trafficId", parentId)
                        put("streamId", streamId)
                        put("direction", if (m.isClient) MessageDirection.CLIENT.name else MessageDirection.SERVER.name)
                        put("compressed", if (m.meta["x-grpc-compressed"] == "true") 1 else 0)
                        put("messageIndex", index)
                        if (payload == null) putNull("payload") else put("payload", payload)
                        put("payloadTruncated", if (truncated) 1 else 0)
                        val decoded = m.meta["x-protobuf-decoded"]
                        if (decoded == null) putNull("decodedText") else put("decodedText", decoded)
                        put("sizeBytes", if (m.isClient) m.row.requestSizeBytes else m.row.responseSizeBytes)
                        put("timestampMs", m.row.timestamp)
                    },
                )
                messages++
            }
            val rpc = first.path.trimStart('/')
            val service = rpc.substringBeforeLast('/', "")
            val method = rpc.substringAfterLast('/')
            val status = msgs.mapNotNull { it.meta["x-grpc-status"]?.toIntOrNull() }.lastOrNull()
            db.insert(
                "grpc_calls", SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("trafficId", parentId)
                    put("service", service)
                    put("method", method)
                    put("encoding", msgs.firstNotNullOfOrNull { it.meta["x-grpc-encoding"] } ?: "proto")
                    put("messageType", msgs.firstNotNullOfOrNull { it.meta["x-grpc-message-type"] } ?: "")
                    put("streamId", streamId)
                    if (status == null) putNull("grpcStatus") else put("grpcStatus", status)
                    putNull("grpcMessage")
                },
            )
        }
        deleteFlat(db, converted)
        return groups.size to messages
    }
}
