package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 1 (dossier Q2 / audit §7.5): proves `MIGRATION_15_16` is a real, non-destructive
 * migration for `captured_traffic.captureMethod` -- every pre-existing row must default to
 * `'UNKNOWN'` (matching [CapturedTrafficEntity.captureMethod]'s Kotlin default exactly, same
 * discipline as every migration before it) and every other pre-existing column must survive the
 * `ALTER TABLE` untouched. Modelled directly on [Migration14To15Test].
 */
@RunWith(RobolectricTestRunner::class)
class Migration15To16Test {

    private val testDbName = "migration-15-16-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 15 to 16 preserves existing rows and defaults captureMethod to UNKNOWN`() {
        var db = helper.createDatabase(testDbName, 15)
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, " +
                "protocol, requestHeaders, responseHeaders, requestBody, responseBody, " +
                "requestSizeBytes, responseSizeBytes, latencyMs, timestamp, appPackage, appUid, " +
                "wasDecrypted, isUndecoded, sourceIp, connectionId, isRequestBodyBinary, " +
                "isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated) VALUES " +
                "(1, 1, 'GET', 'https://example.com/x', 'example.com', '/x', 200, 'HTTP/1.1', " +
                "'{}', '{}', NULL, 'hello', 0, 5, 12, 1700000000000, NULL, NULL, 1, 0, NULL, " +
                "NULL, 0, 0, NULL, NULL, 0, 0)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 16, false, RequestrDatabase.MIGRATION_15_16)

        val cursor = db.query(
            "SELECT method, url, responseBody, responseSizeBytes, captureMethod " +
                "FROM captured_traffic WHERE id = 1"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("GET", cursor.getString(0))
        assertEquals("https://example.com/x", cursor.getString(1))
        assertEquals("hello", cursor.getString(2))
        assertEquals(5, cursor.getInt(3))
        assertEquals("UNKNOWN", cursor.getString(4))
        cursor.close()
        db.close()
    }

    @Test
    fun `a freshly inserted v16 row can record a real, non-default captureMethod`() {
        val db = helper.createDatabase(testDbName, 15)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 16, false, RequestrDatabase.MIGRATION_15_16)

        migrated.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, " +
                "protocol, requestHeaders, responseHeaders, requestBody, responseBody, " +
                "requestSizeBytes, responseSizeBytes, latencyMs, timestamp, appPackage, appUid, " +
                "wasDecrypted, isUndecoded, sourceIp, connectionId, isRequestBodyBinary, " +
                "isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated, captureMethod) VALUES " +
                "(2, 1, 'GET', 'https://example.com/y', 'example.com', '/y', 200, 'HTTP/1.1', " +
                "'{}', '{}', NULL, NULL, 0, 0, 5, 1700000000000, NULL, NULL, 1, 0, NULL, NULL, " +
                "0, 0, NULL, NULL, 0, 0, 'VPN_TUN')"
        )

        val cursor = migrated.query("SELECT captureMethod FROM captured_traffic WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("VPN_TUN", cursor.getString(0))
        cursor.close()
        migrated.close()
    }
}
