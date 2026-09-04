package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 20: proves [MIGRATION_19_20] produces exactly the schema Room exports for v20
 * (`runMigrationsAndValidate` diffs the migrated database against `20.json`), that it is
 * additive -- seeded v19 rows, including the legacy flattened `WS→` and `x-grpc-*` rows that v21
 * will fold, survive untouched -- that the five protocol tables start empty (v20 backfills
 * nothing; that is v21's job), and that `ON DELETE CASCADE` actually cascades for each of them.
 * Modelled on [Migration18To19Test].
 */
@RunWith(RobolectricTestRunner::class)
class Migration19To20Test {

    private val testDbName = "migration-19-20-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedV19Traffic(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        host: String,
        method: String = "GET",
        protocol: String = "HTTP/1.1",
        requestHeaders: String = "{}",
    ) {
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, protocol, " +
                "requestHeaders, responseHeaders, requestBody, responseBody, requestSizeBytes, responseSizeBytes, " +
                "latencyMs, timestamp, appPackage, appUid, wasDecrypted, isUndecoded, sourceIp, connectionId, " +
                "isRequestBodyBinary, isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated, captureMethod, " +
                "requestBodyStoredBytes, responseBodyStoredBytes, requestBodyTotalBytes, responseBodyTotalBytes) VALUES (" +
                "$id, 7, '$method', 'https://$host/x', '$host', '/x', 200, '$protocol', '$requestHeaders', '{}', NULL, 'ok', " +
                "10, 20, 123, 1000, 'com.app', 10077, 1, 0, NULL, 'conn-1', 0, 0, NULL, NULL, 0, 0, 'MITM_PROXY', " +
                "0, 0, 0, 0)"
        )
    }

    @Test
    fun `migrate 19 to 20 is additive, validates against the exported schema and backfills nothing`() {
        var db = helper.createDatabase(testDbName, 19)
        seedV19Traffic(db, 1, "kept.example")
        // Legacy flattened rows that v21 (not v20) is responsible for folding.
        seedV19Traffic(db, 2, "ws.example", method = "WS→", protocol = "WEBSOCKET")
        seedV19Traffic(
            db, 3, "grpc.example", method = "POST", protocol = "gRPC",
            requestHeaders = "{\"x-grpc-stream-id\":\"3\",\"x-grpc-encoding\":\"proto\"}",
        )
        db.execSQL(
            "INSERT INTO transaction_headers (trafficId, kind, ordinal, name, nameLower, value) VALUES " +
                "(1, 'RESPONSE', 0, 'Set-Cookie', 'set-cookie', 'a=1')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 20, false, MIGRATION_19_20)

        // Every seeded row is intact -- including the legacy rows, which v20 must not touch.
        db.query("SELECT id, method, protocol, requestHeaders FROM captured_traffic ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0)); assertEquals("GET", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("WS→", c.getString(1)); assertEquals("WEBSOCKET", c.getString(2))
            assertTrue(c.moveToNext())
            assertEquals("gRPC", c.getString(2))
            assertTrue(c.getString(3).contains("x-grpc-stream-id"))
            assertFalse(c.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM transaction_headers").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }

        // New tables exist and are empty: no protocol event was invented for pre-v20 rows.
        for (table in listOf("websocket_messages", "sse_events", "grpc_calls", "grpc_messages", "http2_stream_events")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table must start empty", 0, c.getInt(0))
            }
        }

        // Indices carry the names Room expects.
        for (idx in listOf(
            "index_websocket_messages_trafficId_frameIndex",
            "index_websocket_messages_timestampMs",
            "index_sse_events_trafficId_eventIndex",
            "index_sse_events_timestampMs",
            "index_grpc_messages_trafficId_messageIndex",
            "index_grpc_messages_streamId",
            "index_grpc_messages_timestampMs",
            "index_http2_stream_events_trafficId_id",
            "index_http2_stream_events_streamId",
            "index_http2_stream_events_timestampMs",
        )) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$idx'").use { c ->
                assertTrue("missing index $idx", c.moveToFirst())
            }
        }
        db.close()
    }

    @Test
    fun `protocol child rows cascade when the parent transaction is deleted`() {
        var db = helper.createDatabase(testDbName, 19)
        seedV19Traffic(db, 1, "a.example")
        seedV19Traffic(db, 2, "b.example")
        db.close()
        db = helper.runMigrationsAndValidate(testDbName, 20, false, MIGRATION_19_20)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL(
            "INSERT INTO websocket_messages (trafficId, connectionId, direction, opcode, isText, payloadText, " +
                "payloadBytes, payloadTruncated, sizeBytes, timestampMs, frameIndex) VALUES " +
                "(1, 'a.example:443', 'CLIENT', 1, 1, 'hello', NULL, 0, 5, 1000, 0), " +
                "(1, 'a.example:443', 'SERVER', 2, 0, NULL, X'0102', 0, 2, 1001, 1), " +
                "(2, 'b.example:443', 'CLIENT', 1, 1, 'other', NULL, 0, 5, 1002, 0)"
        )
        db.execSQL(
            "INSERT INTO sse_events (trafficId, eventName, data, eventId, retryMs, timestampMs, eventIndex) VALUES " +
                "(1, NULL, 'tick', '7', NULL, 1000, 0), (2, 'update', '{}', NULL, 3000, 1001, 0)"
        )
        db.execSQL(
            "INSERT INTO grpc_calls (trafficId, service, method, encoding, messageType, streamId, grpcStatus, grpcMessage) VALUES " +
                "(1, 'my.Svc', 'Get', 'proto', '', 3, NULL, NULL), (2, 'my.Svc', 'Put', 'proto', '', 5, 0, NULL)"
        )
        db.execSQL(
            "INSERT INTO grpc_messages (trafficId, streamId, direction, compressed, messageIndex, payload, " +
                "payloadTruncated, decodedText, sizeBytes, timestampMs) VALUES " +
                "(1, 3, 'CLIENT', 0, 0, X'0801', 0, '1: 1', 2, 1000), (2, 5, 'SERVER', 0, 0, NULL, 0, NULL, 0, 1001)"
        )
        db.execSQL(
            "INSERT INTO http2_stream_events (trafficId, connectionId, streamId, direction, frameType, flags, " +
                "payloadLength, timestampMs, note) VALUES " +
                "(1, 'a.example:443', 3, 'CLIENT', 1, 4, 40, 1000, NULL), (2, 'b.example:443', 5, 'SERVER', 0, 1, 2, 1001, 'END_STREAM')"
        )

        // Both directions readable in frame order; binary payload byte-exact.
        db.query("SELECT direction, opcode, payloadText, payloadBytes FROM websocket_messages WHERE trafficId = 1 ORDER BY frameIndex").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("CLIENT", c.getString(0)); assertEquals("hello", c.getString(2))
            assertTrue(c.moveToNext()); assertEquals("SERVER", c.getString(0)); assertEquals(2, c.getInt(1))
            assertTrue(byteArrayOf(1, 2).contentEquals(c.getBlob(3)))
            assertFalse(c.moveToNext())
        }
        // NULL grpc-status stays NULL: "trailers not seen yet" is distinguishable from OK (0).
        db.query("SELECT grpcStatus FROM grpc_calls WHERE trafficId = 1").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0))
        }

        db.execSQL("DELETE FROM captured_traffic WHERE id = 1")

        for (table in listOf("websocket_messages", "sse_events", "grpc_calls", "grpc_messages", "http2_stream_events")) {
            db.query("SELECT COUNT(*) FROM $table WHERE trafficId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table rows of the deleted parent must cascade", 0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM $table WHERE trafficId = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table rows of the surviving parent must stay", 1, c.getInt(0))
            }
        }
        db.close()
    }
}
