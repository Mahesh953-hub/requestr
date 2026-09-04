package com.example.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.models.HttpMethod
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.MitmProxyEngine
import com.example.interception.http.Http2Parser
import com.example.interception.http.Http2Session
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.http.WebSocketParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 21: proves [MIGRATION_20_21] folds the pre-v20 flattened rows into the v20 protocol
 * tables and validates against the exported `21.json`.
 *
 * The fixture is NOT hand-written SQL: the legacy rows are produced by the real legacy writers
 * ([MitmProxyEngine] with `protocolChildren = false`, gRPC driven through a real [Http2Session]),
 * then inserted into a real v20 database through the same [RequestrTypeConverters] the app uses,
 * so the migration is exercised against exactly what a pre-v20 install has on disk.
 */
@RunWith(RobolectricTestRunner::class)
class Migration20To21Test {

    private val testDbName = "migration-20-21-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val converters = RequestrTypeConverters()

    // -- fixture: legacy rows from the real writers ----------------------------------------------

    /** Inserts a v20 `captured_traffic` row from an entity the legacy writer produced. */
    private fun insertV20(db: SupportSQLiteDatabase, e: CapturedTrafficEntity, timestamp: Long): Long =
        db.insert(
            "captured_traffic", SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("sessionId", e.sessionId)
                put("method", e.method)
                put("url", e.url)
                put("host", e.host)
                put("path", e.path)
                if (e.statusCode == null) putNull("statusCode") else put("statusCode", e.statusCode)
                put("protocol", e.protocol)
                put("requestHeaders", converters.stringMapToJson(e.requestHeaders))
                put("responseHeaders", converters.stringMapToJson(e.responseHeaders))
                if (e.requestBody == null) putNull("requestBody") else put("requestBody", e.requestBody)
                if (e.responseBody == null) putNull("responseBody") else put("responseBody", e.responseBody)
                put("requestSizeBytes", e.requestSizeBytes)
                put("responseSizeBytes", e.responseSizeBytes)
                put("latencyMs", e.latencyMs)
                put("timestamp", timestamp)
                if (e.appPackage == null) putNull("appPackage") else put("appPackage", e.appPackage)
                if (e.appUid == null) putNull("appUid") else put("appUid", e.appUid)
                put("wasDecrypted", if (e.wasDecrypted) 1 else 0)
                put("isUndecoded", if (e.isUndecoded) 1 else 0)
                if (e.sourceIp == null) putNull("sourceIp") else put("sourceIp", e.sourceIp)
                if (e.connectionId == null) putNull("connectionId") else put("connectionId", e.connectionId)
                put("isRequestBodyBinary", if (e.isRequestBodyBinary) 1 else 0)
                put("isResponseBodyBinary", if (e.isResponseBodyBinary) 1 else 0)
                put("requestBodyBytesTruncated", if (e.requestBodyBytesTruncated) 1 else 0)
                put("responseBodyBytesTruncated", if (e.responseBodyBytesTruncated) 1 else 0)
                put("captureMethod", e.captureMethod.name)
                put("requestBodyStoredBytes", e.requestBodyStoredBytes)
                put("responseBodyStoredBytes", e.responseBodyStoredBytes)
                put("requestBodyTotalBytes", e.requestBodyTotalBytes)
                put("responseBodyTotalBytes", e.responseBodyTotalBytes)
            },
        )

    private fun request() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://ws.example/chat", path = "/chat", host = "ws.example",
        headers = mapOf("upgrade" to "websocket"), headerLines = listOf("Upgrade" to "websocket"),
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false, method = null, statusCode = 101,
        headers = mapOf("upgrade" to "websocket"), headerLines = listOf("Upgrade" to "websocket"),
    )

    private fun frame(opcode: Int, payload: ByteArray) =
        WebSocketParser.WsFrame(opcode = opcode, isMasked = false, payload = payload, totalLength = payload.size + 2)

    /** The flat `WS→`/`WS←` rows the pre-v20 MITM relay wrote for one chat: text, text, binary, close. */
    private fun legacyWsRows(): List<CapturedTrafficEntity> {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        engine.protocolChildren = false
        val state = MitmProxyEngine.ConnectionProtocolState().apply { wsUrl = "wss://ws.example/chat" }
        // The legacy relay never persisted the 101 exchange as a WS parent; drop it from the fixture
        // to model what a pre-v20 database really holds (only the flat frame rows).
        state.wsParent = engine.emitTransaction(request(), response(), 1L, "ws.example:443", logRequests = true, logResponses = true)
        engine.emitWsFrame(frame(WebSocketParser.OP_TEXT, "hello".toByteArray()), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(frame(WebSocketParser.OP_TEXT, "hi there".toByteArray()), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(frame(WebSocketParser.OP_BINARY, byteArrayOf(0, 1, 2, 0x7f)), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(frame(WebSocketParser.OP_CLOSE, byteArrayOf(0x03, 0xe8.toByte())), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        val flat = sink.rows.map { it.entity }.filter { it.method == "WS→" || it.method == "WS←" }
        assertEquals("fixture sanity: four flat frame rows", 4, flat.size)
        return flat
    }

    private fun literalHeader(name: String, value: String): ByteArray =
        byteArrayOf(0x40) + byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.US_ASCII)

    private fun grpcFrame(payload: ByteArray): ByteArray =
        byteArrayOf(0) + byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
        ) + payload

    private fun headersFrame(streamId: Int, block: ByteArray, flags: Int) = Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, streamId, block)
    private fun dataFrame(streamId: Int, payload: ByteArray, endStream: Boolean) =
        Http2Parser.H2Frame(payload.size, Http2Parser.FRAME_DATA, if (endStream) Http2Parser.FLAG_END_STREAM else 0, streamId, payload)

    private val protoRequest = byteArrayOf(0x08, 0x2a, 0x12, 0x05) + "alice".toByteArray()

    private fun grpcRequestBlock(path: String) =
        literalHeader(":method", "POST") + literalHeader(":scheme", "https") + literalHeader(":path", path) +
            literalHeader(":authority", "grpc.example.com") + literalHeader("content-type", "application/grpc") + literalHeader("te", "trailers")

    /**
     * The flat `x-grpc-*` rows the pre-v20 h2 relay wrote: stream 3 = unary GetUser (1 request, 2
     * responses, trailer status 0), stream 5 = ListUsers with a single request message on a
     * separate call, interleaved.
     */
    private fun legacyGrpcRows(): List<CapturedTrafficEntity> {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        engine.protocolChildren = false
        val table = MitmProxyEngine.GrpcCallTable()
        val session = Http2Session(
            onTransaction = { throw AssertionError("gRPC must never surface as an h2 transaction: $it") },
            onGrpcMessage = { engine.emitGrpcMessage(it, "grpc.example.com", 443, logRequests = true, logResponses = true, table = table) },
            onGrpcCallStarted = { engine.emitGrpcCallStart(it, "grpc.example.com", 443, logRequests = true, table = table) },
            onGrpcCallEnded = { engine.emitGrpcCallEnd(it, "grpc.example.com", 443, logRequests = true, logResponses = true, table = table) },
        )
        val END_HEADERS = Http2Parser.FLAG_END_HEADERS
        val END_STREAM = Http2Parser.FLAG_END_STREAM
        session.onClientFrames(listOf(headersFrame(3, grpcRequestBlock("/my.Service/GetUser"), END_HEADERS)))
        session.onClientFrames(listOf(headersFrame(5, grpcRequestBlock("/my.Service/ListUsers"), END_HEADERS)))
        session.onClientFrames(listOf(dataFrame(3, grpcFrame(protoRequest), endStream = true)))
        session.onClientFrames(listOf(dataFrame(5, grpcFrame(byteArrayOf(0x08, 0x07)), endStream = true)))
        session.onServerFrames(listOf(headersFrame(3, literalHeader(":status", "200") + literalHeader("content-type", "application/grpc"), END_HEADERS)))
        session.onServerFrames(listOf(dataFrame(3, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = false)))
        // Trailers before the final DATA frame: the legacy writer stamps x-grpc-status on messages
        // emitted once the status is known, which is the only way a flat row ever carried it.
        session.onServerFrames(listOf(headersFrame(3, literalHeader("grpc-status", "0"), END_HEADERS)))
        session.onServerFrames(listOf(dataFrame(3, grpcFrame(byteArrayOf(0x08, 0x02)), endStream = true)))
        val flat = sink.rows.map { it.entity }
        assertEquals("fixture sanity: four flat message rows", 4, flat.size)
        assertTrue(flat.all { it.protocol == "gRPC" && (it.requestHeaders + it.responseHeaders).containsKey("x-grpc-stream-id") })
        return flat
    }

    private fun seedPlainRow(db: SupportSQLiteDatabase, id: Long, protocol: String = "HTTP/1.1", headers: String = "{}") {
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, protocol, " +
                "requestHeaders, responseHeaders, requestBody, responseBody, requestSizeBytes, responseSizeBytes, " +
                "latencyMs, timestamp, appPackage, appUid, wasDecrypted, isUndecoded, sourceIp, connectionId, " +
                "isRequestBodyBinary, isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated, captureMethod, " +
                "requestBodyStoredBytes, responseBodyStoredBytes, requestBodyTotalBytes, responseBodyTotalBytes) VALUES (" +
                "$id, 1, 'POST', 'https://kept.example/x', 'kept.example', '/x', 200, '$protocol', '$headers', '{}', NULL, 'ok', " +
                "10, 20, 123, 500, 'com.app', 10077, 1, 0, NULL, 'conn-1', 0, 0, NULL, NULL, 0, 0, 'MITM_PROXY', 0, 0, 0, 0)"
        )
    }

    private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { it.moveToFirst(); it.getInt(0) }
    private fun SupportSQLiteDatabase.rows(sql: String): List<Map<String, Any?>> = query(sql).use { c ->
        val out = ArrayList<Map<String, Any?>>()
        while (c.moveToNext()) {
            out += (0 until c.columnCount).associate { i ->
                c.getColumnName(i) to when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            }
        }
        out
    }

    // -- tests ------------------------------------------------------------------------------------

    @Test
    fun `migrate 20 to 21 folds legacy websocket frames into one synthesized 101 parent per connection`() {
        var db = helper.createDatabase(testDbName, 20)
        seedPlainRow(db, 1)
        val ws = legacyWsRows()
        ws.forEachIndexed { i, e -> insertV20(db, e, timestamp = 1_000L + i * 10) }
        assertEquals(5, db.count("SELECT COUNT(*) FROM captured_traffic"))
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)

        assertEquals("no flat frame rows remain", 0, db.count("SELECT COUNT(*) FROM captured_traffic WHERE method IN ('WS→','WS←')"))
        val kept = db.rows("SELECT * FROM captured_traffic WHERE id = 1").single()
        assertEquals("kept.example", kept["host"])
        assertNull("live rows are never stamped", kept["legacyBackfill"])

        val parent = db.rows("SELECT * FROM captured_traffic WHERE legacyBackfill = 'v21-ws'").single()
        assertEquals("GET", parent["method"])
        assertEquals("wss://ws.example/chat", parent["url"])
        assertEquals("ws.example", parent["host"])
        assertEquals("/chat", parent["path"])
        assertEquals(101L, parent["statusCode"])
        assertEquals("WEBSOCKET", parent["protocol"])
        assertEquals("session of the frames it was folded from", ws.first().sessionId, parent["sessionId"])
        assertEquals("ws.example:443", parent["connectionId"])
        assertEquals("MITM_PROXY", parent["captureMethod"])
        assertEquals(1L, parent["wasDecrypted"])
        assertEquals("first frame's time", 1_000L, parent["timestamp"])
        assertEquals(ws.sumOf { it.requestSizeBytes }, parent["requestSizeBytes"])
        assertEquals(ws.sumOf { it.responseSizeBytes }, parent["responseSizeBytes"])
        assertEquals(mapOf("upgrade" to "websocket", "connection" to "Upgrade"), converters.jsonToStringMap(parent["requestHeaders"] as String))

        val messages = db.rows("SELECT * FROM websocket_messages WHERE trafficId = ${parent["id"]} ORDER BY frameIndex")
        assertEquals(4, messages.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), messages.map { it["frameIndex"] })
        assertEquals(listOf("CLIENT", "SERVER", "CLIENT", "SERVER"), messages.map { it["direction"] })
        assertEquals(listOf(1L, 1L, 2L, 8L), messages.map { it["opcode"] })
        assertEquals(listOf(1L, 1L, 0L, 0L), messages.map { it["isText"] })
        assertEquals("hello", messages[0]["payloadText"])
        assertEquals("hi there", messages[1]["payloadText"])
        assertEquals(listOf(1_000L, 1_010L, 1_020L, 1_030L), messages.map { it["timestampMs"] })
        assertEquals("wire length the flat row recorded", 7L, messages[0]["sizeBytes"])
        assertEquals(10L, messages[1]["sizeBytes"])
        assertTrue(messages.all { it["payloadTruncated"] == 0L && it["connectionId"] == "ws.example:443" })
        db.close()
    }

    @Test
    fun `migrate 20 to 21 folds legacy x-grpc rows into one parent, messages and a call row per stream`() {
        var db = helper.createDatabase(testDbName, 20)
        val grpc = legacyGrpcRows()
        grpc.forEachIndexed { i, e -> insertV20(db, e, timestamp = 2_000L + i * 10) }
        // A live v20 gRPC parent (no synthetic headers) with its call row must be left alone.
        seedPlainRow(db, 99, protocol = "gRPC", headers = "{\"content-type\":\"application/grpc\"}")
        db.execSQL("INSERT INTO grpc_calls (trafficId, service, method, encoding, messageType, streamId, grpcStatus, grpcMessage) VALUES (99, 'live.Svc', 'Do', 'proto', '', 7, 0, NULL)")
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)

        assertEquals("no flat message rows remain", 0, db.count("SELECT COUNT(*) FROM captured_traffic WHERE requestHeaders LIKE '%x-grpc-stream-id%' OR responseHeaders LIKE '%x-grpc-stream-id%'"))
        assertNull(db.rows("SELECT legacyBackfill FROM captured_traffic WHERE id = 99").single()["legacyBackfill"])
        assertEquals(1, db.count("SELECT COUNT(*) FROM grpc_calls WHERE trafficId = 99"))

        val parents = db.rows("SELECT * FROM captured_traffic WHERE legacyBackfill = 'v21-grpc' ORDER BY timestamp")
        assertEquals("one parent per (connection, stream)", 2, parents.size)
        val getUser = parents.single { it["url"] == "https://grpc.example.com/my.Service/GetUser" }
        val listUsers = parents.single { it["url"] == "https://grpc.example.com/my.Service/ListUsers" }
        assertEquals("POST", getUser["method"])
        assertEquals("gRPC", getUser["protocol"])
        assertEquals("/my.Service/GetUser", getUser["path"])
        assertEquals("grpc.example.com", getUser["host"])
        assertNull("flat rows never recorded the HTTP status", getUser["statusCode"])
        assertEquals("grpc.example.com:443", getUser["connectionId"])
        val parentHeaders = converters.jsonToStringMap(getUser["requestHeaders"] as String)
        // The legacy writer stored call.headers = the regular (non-pseudo) request headers only.
        assertEquals("application/grpc", parentHeaders["content-type"])
        assertEquals("trailers", parentHeaders["te"])
        assertTrue("synthetic x-grpc-* keys are stripped", parentHeaders.keys.none { it.startsWith("x-grpc-") || it == "x-protobuf-decoded" })
        assertEquals((protoRequest.size + 5).toLong(), getUser["requestSizeBytes"])
        assertEquals(2L * (2 + 5), getUser["responseSizeBytes"])

        val messages = db.rows("SELECT * FROM grpc_messages WHERE trafficId = ${getUser["id"]} ORDER BY messageIndex")
        assertEquals(3, messages.size)
        assertEquals(listOf(0L, 1L, 2L), messages.map { it["messageIndex"] })
        assertEquals(listOf("CLIENT", "SERVER", "SERVER"), messages.map { it["direction"] })
        assertTrue(messages.all { it["streamId"] == 3L && it["compressed"] == 0L && it["payloadTruncated"] == 0L })
        assertArrayEquals(protoRequest, messages[0]["payload"] as ByteArray)
        assertArrayEquals(byteArrayOf(0x08, 0x01), messages[1]["payload"] as ByteArray)
        assertArrayEquals(byteArrayOf(0x08, 0x02), messages[2]["payload"] as ByteArray)
        assertTrue("decoded protobuf text carried over", (messages[0]["decodedText"] as String).contains("alice"))
        assertEquals((protoRequest.size + 5).toLong(), messages[0]["sizeBytes"])
        assertEquals(listOf(2_000L, 2_020L, 2_030L), messages.map { it["timestampMs"] })

        val call = db.rows("SELECT * FROM grpc_calls WHERE trafficId = ${getUser["id"]}").single()
        assertEquals("my.Service", call["service"])
        assertEquals("GetUser", call["method"])
        assertEquals("proto", call["encoding"])
        assertEquals(3L, call["streamId"])
        assertEquals("last x-grpc-status seen becomes the trailer status", 0L, call["grpcStatus"])

        assertEquals(1, db.count("SELECT COUNT(*) FROM grpc_messages WHERE trafficId = ${listUsers["id"]}"))
        val listCall = db.rows("SELECT * FROM grpc_calls WHERE trafficId = ${listUsers["id"]}").single()
        assertEquals("ListUsers", listCall["method"])
        assertEquals(5L, listCall["streamId"])
        assertNull("no status ever recorded for the open call", listCall["grpcStatus"])
        assertEquals("two backfilled calls plus the live one", 3, db.count("SELECT COUNT(*) FROM grpc_calls"))
        db.close()
    }

    @Test
    fun `backfill is idempotent and a database without legacy rows is untouched`() {
        var db = helper.createDatabase(testDbName, 20)
        seedPlainRow(db, 1)
        legacyWsRows().forEachIndexed { i, e -> insertV20(db, e, 1_000L + i) }
        legacyGrpcRows().forEachIndexed { i, e -> insertV20(db, e, 2_000L + i) }
        db.close()
        db = helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)

        val trafficBefore = db.rows("SELECT id, legacyBackfill FROM captured_traffic ORDER BY id")
        val wsBefore = db.count("SELECT COUNT(*) FROM websocket_messages")
        val grpcBefore = db.count("SELECT COUNT(*) FROM grpc_messages")
        assertEquals(4, trafficBefore.size) // kept + ws parent + 2 grpc parents
        assertEquals(4, wsBefore)
        assertEquals(4, grpcBefore)

        val again = Migration20To21.backfill(db)
        assertEquals(Migration20To21.Result(0, 0, 0, 0), again)
        assertEquals(trafficBefore, db.rows("SELECT id, legacyBackfill FROM captured_traffic ORDER BY id"))
        assertEquals(wsBefore, db.count("SELECT COUNT(*) FROM websocket_messages"))
        assertEquals(grpcBefore, db.count("SELECT COUNT(*) FROM grpc_messages"))
        assertEquals(2, db.count("SELECT COUNT(*) FROM grpc_calls"))
        db.close()
    }

    @Test
    fun `migrate 20 to 21 on an empty database only adds the stamp column`() {
        var db = helper.createDatabase(testDbName, 20)
        seedPlainRow(db, 1)
        db.close()
        db = helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)
        assertEquals(1, db.count("SELECT COUNT(*) FROM captured_traffic"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM captured_traffic WHERE legacyBackfill IS NOT NULL"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM websocket_messages") + db.count("SELECT COUNT(*) FROM grpc_messages") + db.count("SELECT COUNT(*) FROM grpc_calls"))
        db.close()
    }

    @Test
    fun `hex dump and ws url helpers invert the legacy writers`() {
        val (bytes, truncated) = Migration20To21.parseHexDump("08 2a 12 05")
        assertArrayEquals(byteArrayOf(0x08, 0x2a, 0x12, 0x05), bytes)
        assertFalse(truncated)
        val (prefix, cut) = Migration20To21.parseHexDump("08 01 …(+4092B)")
        assertArrayEquals(byteArrayOf(0x08, 0x01), prefix)
        assertTrue(cut)
        assertNull(Migration20To21.parseHexDump("not a dump").first)
        assertNull(Migration20To21.parseHexDump("").first)

        assertEquals("wss://h/p" to 0x1, Migration20To21.splitWsUrl("wss://h/p (TEXT)"))
        assertEquals("wss://h/p" to 0x2, Migration20To21.splitWsUrl("wss://h/p (BINARY)"))
        assertEquals("wss://h/p" to 0x8, Migration20To21.splitWsUrl("wss://h/p (CLOSE)"))
        assertEquals("wss://h/p" to 0xB, Migration20To21.splitWsUrl("wss://h/p (OPCODE_11)"))
        assertEquals("wss://h/p (x)" to 0x1, Migration20To21.splitWsUrl("wss://h/p (x)"))
        assertEquals("wss://h/p" to 0x1, Migration20To21.splitWsUrl("wss://h/p"))
    }
}
