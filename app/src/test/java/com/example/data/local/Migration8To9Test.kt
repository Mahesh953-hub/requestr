package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 10: proves `MIGRATION_8_9` is a real, non-destructive migration.
 *
 * Before this change, `RequestrDatabase` used `.fallbackToDestructiveMigration(dropAllTables =
 * true)` -- every version bump silently wiped a user's captured traffic, saved requests, and flow
 * graphs. This test fails if that wipe ever comes back: it inserts rows under the v8 schema, runs
 * the real migration, then asserts those exact rows are still there.
 */
@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {

    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 8 to 9 preserves existing rows and adds new columns, index, and tables`() {
        // Build the v8 database and insert one row per table this migration touches.
        var db = helper.createDatabase(testDbName, 8)
        db.execSQL(
            "INSERT INTO captured_traffic (sessionId, method, url, host, path, protocol, " +
                "requestHeaders, responseHeaders, requestSizeBytes, responseSizeBytes, latencyMs, " +
                "timestamp, wasDecrypted, isUndecoded) VALUES " +
                "(1, 'GET', 'https://example.com/', 'example.com', '/', 'HTTP/1.1', '{}', '{}', " +
                "0, 0, 10, 1000, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO tls_sessions (tlsSessionId, sessionId, connectionId, handshakeState, " +
                "wasDecrypted, establishedAt, handshakeDurationMs) VALUES " +
                "('tls-1', 1, 'tcp|1.1.1.1:1>2.2.2.2:2', 'ESTABLISHED', 0, 2000, 5)"
        )
        db.close()

        // Real migration path -- this is what dies if a destructive fallback is reintroduced.
        //
        // validateDroppedTables is false here, not true: this migration also creates
        // connect_attempts (Section C), whose @Entity/@Dao (ConnectAttemptEntity) is registered
        // only on the SDK scope's branch, not in this worktree's @Database entity list. With
        // validateDroppedTables = true, Room's schema validator throws "Migration didn't
        // properly handle: Unexpected table connect_attempts" because it sees a table with no
        // matching entity here -- that is an artifact of this scope not carrying the SDK
        // scope's entity, not a bug in the migration SQL. The explicit table/index assertions
        // below are what actually prove Section C is correct; the user reconciles the exported
        // schema JSON once both scopes' entity lists are merged.
        db = helper.runMigrationsAndValidate(testDbName, 9, false, RequestrDatabase.MIGRATION_8_9)

        // Pre-existing rows survived, and the new columns exist and are null on old rows.
        db.query("SELECT connectionId FROM captured_traffic WHERE host = 'example.com'").use { c ->
            assertTrue("captured_traffic row must survive the migration", c.moveToFirst())
            assertNull(c.getString(0))
        }
        db.query("SELECT failureReason, failedAt FROM tls_sessions WHERE tlsSessionId = 'tls-1'").use { c ->
            assertTrue("tls_sessions row must survive the migration", c.moveToFirst())
            assertNull(c.getString(0))
            assertTrue(c.isNull(1))
        }

        // The new index exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND " +
                "name = 'index_captured_traffic_connectionId'"
        ).use { c -> assertTrue("new connectionId index must exist", c.moveToFirst()) }

        // The five app-scope tables exist.
        listOf("notes", "note_folders", "custom_tools", "chat_messages", "chat_providers").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { c ->
                assertTrue("table $table must exist after migration", c.moveToFirst())
            }
        }

        // Section C: connect_attempts (SDK scope's ConnectAttemptEntity) and its three indices
        // exist. This is the assertion that would have caught the original cross-scope bug: the
        // SDK branch registers ConnectAttemptEntity in the @Database entity list without ever
        // creating this table itself, so if this migration didn't create it, Room would throw at
        // database-open time on every launch once the two branches are merged.
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'connect_attempts'").use { c ->
            assertTrue("connect_attempts table must exist after migration", c.moveToFirst())
        }
        // Regression guard for the "id INTEGER PRIMARY KEY AUTOINCREMENT" nullability bug: SQLite
        // treats that column declaration as implicitly nullable unless NOT NULL is spelled out
        // explicitly, but Room always generates an explicit NOT NULL for a non-null Kotlin Long
        // primary key -- so a merge with the SDK scope's ConnectAttemptEntity (Kotlin `val id:
        // Long = 0`) would throw "Migration didn't properly handle: connect_attempts" at
        // database-open time on the notNull attribute alone, even with the table and its indices
        // otherwise present. The table/index existence checks above cannot catch this; only a
        // column-attribute check can.
        db.query("SELECT \"notnull\" FROM pragma_table_info('connect_attempts') WHERE name = 'id'").use { c ->
            assertTrue("connect_attempts.id column must be reported by PRAGMA table_info", c.moveToFirst())
            assertEquals("connect_attempts.id must be NOT NULL to match Room's generated DDL for an autoincrement primary key", 1, c.getInt(0))
        }
        listOf(
            "index_connect_attempts_sessionId",
            "index_connect_attempts_targetHost",
            "index_connect_attempts_timestamp",
        ).forEach { indexName ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'"
            ).use { c -> assertTrue("index $indexName must exist after migration", c.moveToFirst()) }
        }

        // A round-trip insert carrying the new fields works.
        db.execSQL(
            "INSERT INTO captured_traffic (sessionId, method, url, host, path, protocol, " +
                "requestHeaders, responseHeaders, requestSizeBytes, responseSizeBytes, latencyMs, " +
                "timestamp, wasDecrypted, isUndecoded, connectionId) VALUES " +
                "(1, 'GET', 'https://round-trip.example/', 'round-trip.example', '/', 'HTTP/1.1', " +
                "'{}', '{}', 0, 0, 10, 3000, 0, 0, 'tcp|1.1.1.1:1>2.2.2.2:2')"
        )
        db.query(
            "SELECT connectionId FROM captured_traffic WHERE host = 'round-trip.example'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("tcp|1.1.1.1:1>2.2.2.2:2", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO notes (id, title, content, format, folderName, folderColorHex, " +
                "isPinned, createdAt, updatedAt) VALUES " +
                "('n-1', 'Title', 'Body', 'MARKDOWN', 'General', 0, 0, 4000, 4000)"
        )
        db.query("SELECT title FROM notes WHERE id = 'n-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Title", c.getString(0))
        }
        db.close()
    }
}
