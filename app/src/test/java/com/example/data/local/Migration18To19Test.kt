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
 * S2 task 14 (G15): proves [MIGRATION_18_19] produces exactly the schema Room exports for v19
 * (`runMigrationsAndValidate` diffs the migrated database against `19.json`), that it is
 * additive -- seeded v18 rows survive byte-for-byte with the new columns at their defaults --
 * that the three child tables start empty with NO backfilled timings, and that the
 * `ON DELETE CASCADE` foreign keys actually cascade. Modelled on [Migration17To18Test].
 */
@RunWith(RobolectricTestRunner::class)
class Migration18To19Test {

    private val testDbName = "migration-18-19-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedV18Traffic(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long, host: String) {
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, protocol, " +
                "requestHeaders, responseHeaders, requestBody, responseBody, requestSizeBytes, responseSizeBytes, " +
                "latencyMs, timestamp, appPackage, appUid, wasDecrypted, isUndecoded, sourceIp, connectionId, " +
                "isRequestBodyBinary, isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated, captureMethod) VALUES (" +
                "$id, 7, 'GET', 'https://$host/x', '$host', '/x', 200, 'HTTP/1.1', '{}', '{}', NULL, 'ok', " +
                "10, 20, 123, 1000, 'com.app', 10077, 1, 0, NULL, 'conn-1', 0, 0, NULL, NULL, 0, 0, 'MITM_PROXY')"
        )
    }

    @Test
    fun `migrate 18 to 19 is additive, validates against the exported schema and backfills nothing`() {
        var db = helper.createDatabase(testDbName, 18)
        seedV18Traffic(db, 1, "kept.example")
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, headers, authType, " +
                "bearerToken, basicUsername, basicPassword, apiKeyHeader, apiKeyValue, apiKeyLocation, bodyType, " +
                "bodyContent, formData, preScript, postScript, isFavorite, updatedAt, chainExtractions, assertions, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, mockDelayMs, pollingEnabled, " +
                "pollingIntervalMs, pollingMaxAttempts, pollingLocationHeader, pollingAllowCrossOriginRedirect) VALUES (" +
                "'r1', 'Saved', NULL, 'POST', 'https://api.example/v1', '[]', '[]', 'NONE', '', '', '', 'X-API-Key', '', " +
                "'HEADER', 'NONE', '', '[]', '', '', 0, 2000, '[]', '[]', 0, 200, 'OK', '[]', '{\"mocked\": true}', 0, 0, " +
                "2000, 10, 'Location', 0)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 19, false, MIGRATION_18_19)

        // Seeded traffic row intact, new columns at their honest defaults.
        db.query(
            "SELECT host, statusCode, latencyMs, requestBodyFilePath, responseBodyFilePath, requestBodySha256, " +
                "responseBodyPreview, requestBodyStoredBytes, responseBodyStoredBytes, requestBodyTotalBytes, " +
                "responseBodyTotalBytes, negotiatedProtocol, protocolDowngradeReason FROM captured_traffic WHERE id = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("kept.example", c.getString(0))
            assertEquals(200, c.getInt(1))
            assertEquals(123L, c.getLong(2))
            for (i in 3..6) assertTrue("column $i must be NULL", c.isNull(i))
            for (i in 7..10) assertEquals("column $i must default to 0", 0L, c.getLong(i))
            assertTrue(c.isNull(11))
            assertTrue(c.isNull(12))
        }

        // Saved request intact, protocolPreference defaults to AUTO (task 58 contract).
        db.query("SELECT name, protocolPreference FROM saved_api_requests WHERE id = 'r1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Saved", c.getString(0))
            assertEquals("AUTO", c.getString(1))
        }

        // New tables exist and are empty: no timing was invented for the pre-v19 row.
        for (table in listOf("transaction_headers", "transaction_timings", "applied_rules")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table must start empty", 0, c.getInt(0))
            }
        }
        db.query("SELECT COUNT(*) FROM transaction_timings WHERE trafficId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no backfilled timings", 0, c.getInt(0))
        }

        // Indices carry the names Room expects.
        for (idx in listOf(
            "index_transaction_headers_trafficId_kind_ordinal",
            "index_transaction_headers_nameLower",
            "index_applied_rules_trafficId",
        )) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$idx'").use { c ->
                assertTrue("missing index $idx", c.moveToFirst())
            }
        }
        db.close()
    }

    @Test
    fun `child rows cascade when the parent transaction is deleted`() {
        var db = helper.createDatabase(testDbName, 18)
        seedV18Traffic(db, 1, "a.example")
        seedV18Traffic(db, 2, "b.example")
        db.close()
        db = helper.runMigrationsAndValidate(testDbName, 19, false, MIGRATION_18_19)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL(
            "INSERT INTO transaction_headers (trafficId, kind, ordinal, name, nameLower, value) VALUES " +
                "(1, 'RESPONSE', 0, 'Set-Cookie', 'set-cookie', 'a=1'), " +
                "(1, 'RESPONSE', 1, 'Set-Cookie', 'set-cookie', 'b=2'), " +
                "(2, 'REQUEST', 0, 'Host', 'host', 'b.example')"
        )
        db.execSQL("INSERT INTO transaction_timings (trafficId, connectMs, tlsMs) VALUES (1, 12, 34), (2, NULL, NULL)")
        db.execSQL(
            "INSERT INTO applied_rules (trafficId, ruleId, ruleName, stage, phase, outcome, detail, appliedAt) VALUES " +
                "(1, 'r-1', 'Block ads', 'GATEWAY', 'REQUEST', 'APPLIED', NULL, 5000)"
        )

        // Duplicate Set-Cookie survives in wire order -- the whole point of the table.
        db.query("SELECT value FROM transaction_headers WHERE trafficId = 1 AND nameLower = 'set-cookie' ORDER BY ordinal").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("a=1", c.getString(0))
            assertTrue(c.moveToNext()); assertEquals("b=2", c.getString(0))
            assertFalse(c.moveToNext())
        }
        // NULL stays NULL: absence of a measurement is distinguishable from zero.
        db.query("SELECT connectMs FROM transaction_timings WHERE trafficId = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }

        db.execSQL("DELETE FROM captured_traffic WHERE id = 1")

        db.query("SELECT COUNT(*) FROM transaction_headers WHERE trafficId = 1").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM transaction_timings WHERE trafficId = 1").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM applied_rules WHERE trafficId = 1").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        // The sibling's children are untouched.
        db.query("SELECT COUNT(*) FROM transaction_headers WHERE trafficId = 2").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM transaction_timings WHERE trafficId = 2").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    @Test
    fun `full chain 8 to 19 through ALL_MIGRATIONS still validates`() {
        val db = helper.createDatabase(testDbName, 8)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 19, true, *RequestrDatabase.ALL_MIGRATIONS)
        migrated.query("PRAGMA user_version").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(19, c.getInt(0))
        }
        migrated.close()
    }
}
