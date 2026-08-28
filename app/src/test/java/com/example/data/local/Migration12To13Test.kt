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
 * Master plan §1.10: proves `MIGRATION_12_13` is a real, non-destructive migration.
 *
 * v13 adds four `polling*` columns to `saved_api_requests` for the ApiTestr async-polling
 * feature (202/in-progress operations, followed via `ApiTestrRepository.executeRequestWithScripts`).
 * Same risk `Migration11To12Test` exists to cover: every new column is `NOT NULL`, so a bad or
 * missing `DEFAULT` would either fail the `ALTER TABLE` outright on a table with existing rows,
 * or silently diverge an upgraded install's behaviour from a fresh one. This asserts both that a
 * pre-existing v12 row survives untouched, and that the new columns backfill to exactly the
 * values a freshly-constructed [com.example.data.models.ApiRequestItem] would have.
 *
 * Follows the same shape as [Migration11To12Test], including `validateDroppedTables = false` for
 * the same cross-scope reason documented there.
 */
@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {

    private val testDbName = "migration-12-13-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 12 to 13 preserves existing rows and defaults the new polling columns to the Kotlin defaults`() {
        // A v12 row that uses every column the old schema had, so we can prove none of the
        // pre-existing user data is disturbed by the ALTER TABLE statements.
        var db = helper.createDatabase(testDbName, 12)
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, bodyType, bodyContent, formData, preScript, postScript, " +
                "isFavorite, updatedAt, chainExtractions, assertions, apiKeyLocation, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, " +
                "mockDelayMs) VALUES " +
                "('req-1', 'Start Job', 'folder-1', 'POST', 'https://api.example.com/jobs', '[]', " +
                "'[]', 'API_KEY', '', '', '', 'X-Api-Key', 'secret-value', 'JSON', " +
                "'{\"job\":\"export\"}', '[]', '', '', 1, 1700000000000, '[]', '[]', " +
                "'HEADER', 0, 200, 'OK', '[]', '{\"mocked\": true}', 0)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 13, false, RequestrDatabase.MIGRATION_12_13)

        // The pre-existing row survived with its original values intact.
        db.query(
            "SELECT name, url, apiKeyHeader, apiKeyValue, bodyContent, isFavorite, updatedAt " +
                "FROM saved_api_requests WHERE id = 'req-1'"
        ).use { c ->
            assertTrue("the v12 saved request must survive the migration", c.moveToFirst())
            assertEquals("Start Job", c.getString(0))
            assertEquals("https://api.example.com/jobs", c.getString(1))
            assertEquals("X-Api-Key", c.getString(2))
            assertEquals("secret-value", c.getString(3))
            assertEquals("{\"job\":\"export\"}", c.getString(4))
            assertEquals(1, c.getInt(5))
            assertEquals(1700000000000L, c.getLong(6))
        }

        // The four new columns backfilled to exactly what a fresh ApiRequestItem would have:
        // polling starts off, at a 2s interval, up to 10 attempts, watching "Location".
        db.query(
            "SELECT pollingEnabled, pollingIntervalMs, pollingMaxAttempts, pollingLocationHeader " +
                "FROM saved_api_requests WHERE id = 'req-1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("polling must default to off for existing requests", 0, c.getInt(0))
            assertEquals(2000L, c.getLong(1))
            assertEquals(10, c.getInt(2))
            assertEquals("Location", c.getString(3))
        }

        // A round-trip insert carrying the new columns works, proving they are writable and not
        // merely present.
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, bodyType, bodyContent, formData, preScript, postScript, " +
                "isFavorite, updatedAt, chainExtractions, assertions, apiKeyLocation, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, " +
                "mockDelayMs, pollingEnabled, pollingIntervalMs, pollingMaxAttempts, " +
                "pollingLocationHeader) VALUES " +
                "('req-2', 'Polled Job', NULL, 'POST', 'https://api.example.com/jobs', '[]', " +
                "'[]', 'NONE', '', '', '', '', '', 'NONE', '', '[]', '', '', 0, " +
                "1700000001000, '[]', '[]', 'HEADER', 0, 200, 'OK', '[]', " +
                "'{\"mocked\": true}', 0, 1, 500, 20, 'X-Poll-Location')"
        )
        db.query(
            "SELECT pollingEnabled, pollingIntervalMs, pollingMaxAttempts, pollingLocationHeader " +
                "FROM saved_api_requests WHERE id = 'req-2'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(500L, c.getLong(1))
            assertEquals(20, c.getInt(2))
            assertEquals("X-Poll-Location", c.getString(3))
        }
        db.close()
    }
}
