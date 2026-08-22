package com.example.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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

    /**
     * FIX 4: proves a pre-8 install (any version `main` has actually shipped -- 1 through 7,
     * per `git log -p` on this file's `version =` field) opens without throwing, via
     * [RoomDatabase.Builder.fallbackToDestructiveMigrationFrom], instead of crashing on Room's
     * "A migration from N to 9 was required but not found" `IllegalStateException`. There is no
     * exported schema JSON below version 8 (`exportSchema` was `false` before this release, and
     * only 8.json/9.json exist under app/schemas/), so a real non-destructive 5->6->7->8 chain
     * cannot be authored -- destructive recreation from those specific legacy versions is the
     * only option that doesn't crash on every launch for those users. This deliberately does NOT
     * use [MigrationTestHelper] (which needs a real schema JSON per starting version, which does
     * not exist for v5) -- instead it stamps a raw SQLite file's `user_version` header, which is
     * all Room actually reads to decide it's looking at "version 5", then opens it with the exact
     * builder configuration [RequestrDatabase.get] uses.
     */
    @Test
    fun `opening a pre-8 (version 5) database falls back to destructive recreation instead of throwing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "pre8-fallback-test.db"
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        // Stamp a minimal, empty SQLite file at user_version 5 -- Room's onOpen check only
        // reads PRAGMA user_version to detect a version mismatch; it doesn't need real v5
        // tables present for this test, since destructive fallback drops everything anyway.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 5
        }

        val db = Room.databaseBuilder(context, RequestrDatabase::class.java, dbName)
            .addMigrations(RequestrDatabase.MIGRATION_8_9)
            .fallbackToDestructiveMigrationFrom(/* dropAllTables = */ true, 1, 2, 3, 4, 5, 6, 7)
            .build()

        try {
            // Forces Room to actually open/validate the database rather than lazily deferring.
            val count = db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM captured_traffic"
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("destructive recreation must yield an empty (but present) table", 0, count)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
