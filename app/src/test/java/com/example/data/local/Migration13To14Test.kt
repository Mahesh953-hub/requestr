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
 * v13 → v14 (P2 review fix): proves `MIGRATION_13_14` adds the
 * `pollingAllowCrossOriginRedirect` column to `saved_api_requests` with a default
 * of `0` (false), matching the Kotlin field.  Pre-existing rows must survive.
 */
@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {

    private val testDbName = "migration-13-14-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 13 to 14 preserves existing rows and defaults pollingAllowCrossOriginRedirect to 0`() {
        var db = helper.createDatabase(testDbName, 13)
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, apiKeyLocation, bodyType, bodyContent, formData, preScript, " +
                "postScript, isFavorite, updatedAt, chainExtractions, assertions, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, " +
                "mockDelayMs, pollingEnabled, pollingIntervalMs, pollingMaxAttempts, " +
                "pollingLocationHeader) VALUES " +
                "('r1', 'My Request', '', 'GET', 'https://example.com', '[]', '[]', 'NONE', " +
                "'', '', '', '', '', 'HEADER', 'NONE', '', '[]', '', '', 0, 1700000000000, " +
                "'[]', '[]', 0, 200, 'OK', '[]', '{\"mocked\": true}', 0, 0, 2000, 10, " +
                "'Location')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDbName, 14, false, RequestrDatabase.MIGRATION_13_14
        )

        val cursor = db.query(
            "SELECT name, pollingAllowCrossOriginRedirect FROM saved_api_requests WHERE id = 'r1'"
        )
        assertTrue("Pre-existing row must survive", cursor.moveToFirst())
        assertEquals("My Request", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        cursor.close()
        db.close()
    }

    @Test
    fun `a freshly inserted v14 row can opt into cross-origin redirect`() {
        val db = helper.createDatabase(testDbName, 13)
        db.close()
        val migrated = helper.runMigrationsAndValidate(
            testDbName, 14, false, RequestrDatabase.MIGRATION_13_14
        )

        migrated.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, apiKeyLocation, bodyType, bodyContent, formData, preScript, " +
                "postScript, isFavorite, updatedAt, chainExtractions, assertions, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, " +
                "mockDelayMs, pollingEnabled, pollingIntervalMs, pollingMaxAttempts, " +
                "pollingLocationHeader, pollingAllowCrossOriginRedirect) VALUES " +
                "('r2', 'Cross-Origin', '', 'POST', 'https://api.example.com', '[]', '[]', " +
                "'NONE', '', '', '', '', '', 'HEADER', 'JSON', '{}', '[]', '', '', 0, " +
                "1700000000000, '[]', '[]', 0, 200, 'OK', '[]', '{\"mocked\": true}', 0, " +
                "1, 2000, 10, 'Location', 1)"
        )

        val cursor = migrated.query(
            "SELECT pollingAllowCrossOriginRedirect FROM saved_api_requests WHERE id = 'r2'"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        cursor.close()
        migrated.close()
    }
}
