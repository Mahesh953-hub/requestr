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
 * Master plan §2.13 + §2.8: proves `MIGRATION_11_12` is a real, non-destructive migration.
 *
 * v12 adds seven columns to `saved_api_requests` — `apiKeyLocation` (§2.13, API-key placement)
 * and the six `mock*` columns (§2.8, per-request mock mode). Every one of them is `NOT NULL`,
 * which is the risk this test exists to cover: `ALTER TABLE ... ADD COLUMN ... NOT NULL` without
 * a `DEFAULT` is rejected outright by SQLite on a table that already has rows, and a `DEFAULT`
 * that disagrees with the Kotlin field default would silently give upgraded installs different
 * behaviour from fresh ones. So this asserts both that a pre-existing v11 row survives, and that
 * it reads back with exactly the values a freshly-constructed
 * [com.example.data.models.ApiRequestItem] would have.
 *
 * Follows the same shape as [Migration8To9Test], including `validateDroppedTables = false` for
 * the same cross-scope reason documented there (the `connect_attempts` table created by
 * `MIGRATION_8_9` has no matching `@Entity` registered in this worktree's `@Database` list).
 */
@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {

    private val testDbName = "migration-11-12-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 11 to 12 preserves existing rows and defaults the new columns to the Kotlin defaults`() {
        // A v11 row that uses every column the old schema had, so we can prove none of the
        // pre-existing user data is disturbed by the ALTER TABLE statements.
        var db = helper.createDatabase(testDbName, 11)
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, bodyType, bodyContent, formData, preScript, postScript, " +
                "isFavorite, updatedAt, chainExtractions, assertions) VALUES " +
                "('req-1', 'Login', 'folder-1', 'POST', 'https://api.example.com/login', '[]', " +
                "'[]', 'API_KEY', '', '', '', 'X-Api-Key', 'secret-value', 'JSON', " +
                "'{\"user\":\"ada\"}', '[]', '', '', 1, 1700000000000, '[]', '[]')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 12, false, RequestrDatabase.MIGRATION_11_12)

        // The pre-existing row survived with its original values intact.
        db.query(
            "SELECT name, url, apiKeyHeader, apiKeyValue, bodyContent, isFavorite, updatedAt " +
                "FROM saved_api_requests WHERE id = 'req-1'"
        ).use { c ->
            assertTrue("the v11 saved request must survive the migration", c.moveToFirst())
            assertEquals("Login", c.getString(0))
            assertEquals("https://api.example.com/login", c.getString(1))
            assertEquals("X-Api-Key", c.getString(2))
            assertEquals("secret-value", c.getString(3))
            assertEquals("{\"user\":\"ada\"}", c.getString(4))
            assertEquals(1, c.getInt(5))
            assertEquals(1700000000000L, c.getLong(6))
        }

        // The seven new columns backfilled to the same values a fresh row would get, so an
        // upgraded install behaves identically to a clean install: API keys keep going in the
        // header, and mock mode starts off.
        db.query(
            "SELECT apiKeyLocation, mockEnabled, mockStatusCode, mockStatusMessage, " +
                "mockHeaders, mockBody, mockDelayMs FROM saved_api_requests WHERE id = 'req-1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("HEADER", c.getString(0))
            assertEquals("mock mode must default to off for existing requests", 0, c.getInt(1))
            assertEquals(200, c.getInt(2))
            assertEquals("OK", c.getString(3))
            // Stored via RequestrTypeConverters as a JSON array, so the empty-list default must
            // be '[]' and not '' -- an empty string would throw when the converter parses it.
            assertEquals("[]", c.getString(4))
            // Must equal ApiRequestItem.mockBody's default verbatim: a migrated row and a
            // freshly-created row have to behave identically once mock mode is switched on.
            assertEquals("{\"mocked\": true}", c.getString(5))
            assertEquals(0L, c.getLong(6))
        }

        // A round-trip insert carrying the new columns works, proving they are writable and not
        // merely present.
        db.execSQL(
            "INSERT INTO saved_api_requests (id, name, folderId, method, url, queryParams, " +
                "headers, authType, bearerToken, basicUsername, basicPassword, apiKeyHeader, " +
                "apiKeyValue, bodyType, bodyContent, formData, preScript, postScript, " +
                "isFavorite, updatedAt, chainExtractions, assertions, apiKeyLocation, " +
                "mockEnabled, mockStatusCode, mockStatusMessage, mockHeaders, mockBody, " +
                "mockDelayMs) VALUES " +
                "('req-2', 'Mocked', NULL, 'GET', 'https://api.example.com/me', '[]', '[]', " +
                "'API_KEY', '', '', '', 'access_token', 'tok', 'NONE', '', '[]', '', '', 0, " +
                "1700000001000, '[]', '[]', 'QUERY_PARAM', 1, 503, 'Service Unavailable', " +
                "'[]', '{\"mocked\":true}', 250)"
        )
        db.query(
            "SELECT apiKeyLocation, mockEnabled, mockStatusCode, mockStatusMessage, mockBody, " +
                "mockDelayMs FROM saved_api_requests WHERE id = 'req-2'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("QUERY_PARAM", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(503, c.getInt(2))
            assertEquals("Service Unavailable", c.getString(3))
            assertEquals("{\"mocked\":true}", c.getString(4))
            assertEquals(250L, c.getLong(5))
        }
        db.close()
    }
}
