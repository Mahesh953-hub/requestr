package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Master plan §2.14(b) (per-URL AES body crypto) and the binary-body-persistence follow-up:
 * proves `MIGRATION_14_15` is a real, non-destructive migration for BOTH of the unrelated
 * feature's columns it carries -- they landed in the same release, so they share one version
 * bump rather than each getting their own (see [RequestrDatabase.MIGRATION_14_15]'s doc).
 *
 * Same shape/risk as [Migration12To13Test]: every new `NOT NULL` column needs a `DEFAULT` that
 * matches the corresponding Kotlin data class default exactly, and a pre-existing v14 row must
 * survive the `ALTER TABLE`s untouched. Additionally proves the new `captured_traffic` BLOB
 * columns (`requestBodyBytes`/`responseBodyBytes`) round-trip real bytes for a freshly inserted
 * v15 row, since [Migration12To13Test]'s pattern only covers text/integer columns.
 */
@RunWith(RobolectricTestRunner::class)
class Migration14To15Test {

    private val testDbName = "migration-14-15-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 14 to 15 preserves existing rows and defaults every new column to the Kotlin defaults`() {
        var db = helper.createDatabase(testDbName, 14)
        db.execSQL(
            "INSERT INTO intercept_rules (id, name, pattern, domain, action, enabled, " +
                "replacementValue, createdAt) VALUES " +
                "(1, 'Block ads', '.*ads.*', 'ads.example.com', 'BLOCK', 1, NULL, 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, " +
                "protocol, requestHeaders, responseHeaders, requestBody, responseBody, " +
                "requestSizeBytes, responseSizeBytes, latencyMs, timestamp, appPackage, appUid, " +
                "wasDecrypted, isUndecoded, sourceIp, connectionId) VALUES " +
                "(1, 1, 'GET', 'https://example.com/x', 'example.com', '/x', 200, 'HTTP/1.1', " +
                "'{}', '{}', NULL, 'hello', 0, 5, 12, 1700000000000, NULL, NULL, 1, 0, NULL, NULL)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 15, false, RequestrDatabase.MIGRATION_14_15)

        // Pre-existing intercept_rules row: crypto columns default to LegacyInterceptRule's Kotlin
        // defaults; the pre-existing columns are untouched.
        var cursor = db.query("SELECT name, action, cryptoKeyAlias, cryptoAlgorithm, cryptoDirection, cryptoScope FROM intercept_rules WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Block ads", cursor.getString(0))
        assertEquals("BLOCK", cursor.getString(1))
        assertEquals("", cursor.getString(2))
        assertEquals("AES/GCM/NoPadding", cursor.getString(3))
        assertEquals("ENCRYPT", cursor.getString(4))
        assertEquals("BOTH", cursor.getString(5))
        cursor.close()

        // Pre-existing captured_traffic row: new binary-body columns default to
        // CapturedTrafficEntity's Kotlin defaults (false/null), and the pre-existing text body
        // and size columns are untouched.
        cursor = db.query(
            "SELECT responseBody, responseSizeBytes, isRequestBodyBinary, isResponseBodyBinary, " +
                "requestBodyBytes, responseBodyBytes, requestBodyBytesTruncated, responseBodyBytesTruncated " +
                "FROM captured_traffic WHERE id = 1"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("hello", cursor.getString(0))
        assertEquals(5, cursor.getInt(1))
        assertEquals(0, cursor.getInt(2)) // isRequestBodyBinary defaults false
        assertEquals(0, cursor.getInt(3)) // isResponseBodyBinary defaults false
        assertNull(cursor.getBlob(4)) // requestBodyBytes defaults null
        assertNull(cursor.getBlob(5)) // responseBodyBytes defaults null
        assertEquals(0, cursor.getInt(6)) // requestBodyBytesTruncated defaults false
        assertEquals(0, cursor.getInt(7)) // responseBodyBytesTruncated defaults false
        cursor.close()
        db.close()
    }

    @Test
    fun `a freshly inserted v15 row round-trips real binary body bytes through the BLOB columns`() {
        val db = helper.createDatabase(testDbName, 14)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 15, false, RequestrDatabase.MIGRATION_14_15)

        val jpegLike = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)
        migrated.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, " +
                "protocol, requestHeaders, responseHeaders, requestBody, responseBody, " +
                "requestSizeBytes, responseSizeBytes, latencyMs, timestamp, appPackage, appUid, " +
                "wasDecrypted, isUndecoded, sourceIp, connectionId, isRequestBodyBinary, " +
                "isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated) VALUES " +
                "(2, 1, 'GET', 'https://example.com/img.jpg', 'example.com', '/img.jpg', 200, " +
                "'HTTP/1.1', '{}', '{}', NULL, NULL, 0, ${jpegLike.size}, 5, 1700000000000, NULL, " +
                "NULL, 1, 0, NULL, NULL, 0, 1, NULL, ?, 0, 0)",
            arrayOf(jpegLike),
        )

        val cursor = migrated.query("SELECT responseBodyBytes, isResponseBodyBinary FROM captured_traffic WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertArrayEquals(jpegLike, cursor.getBlob(0))
        assertEquals(1, cursor.getInt(1))
        cursor.close()
        migrated.close()
    }
}
