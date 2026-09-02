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
 * Home rebrand §6: proves `MIGRATION_17_18` creates the `recent_activity` table exactly as
 * `RecentActivityEntity` declares it (Room's `runMigrationsAndValidate` diffs the migrated
 * schema against the exported 18.json), that it is additive -- pre-existing rows in other tables
 * survive untouched -- and that it starts genuinely empty with no backfilled "recents".
 * Modelled directly on [Migration16To17Test].
 */
@RunWith(RobolectricTestRunner::class)
class Migration17To18Test {

    private val testDbName = "migration-17-18-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 17 to 18 creates an empty recent_activity table and preserves existing data`() {
        var db = helper.createDatabase(testDbName, 17)
        db.execSQL(
            "INSERT INTO notes (id, title, content, format, folderId, folderName, folderColorHex, isPinned, createdAt, updatedAt) " +
                "VALUES ('n1', 'Kept note', 'body', 'MARKDOWN', NULL, 'General', 0, 0, 1000, 2000)"
        )
        db.execSQL(
            "INSERT INTO chat_conversations (id, title, titleIsOverride, createdAt, updatedAt) " +
                "VALUES ('c1', 'Kept chat', 0, 1000, 2000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 18, false, RequestrDatabase.MIGRATION_17_18)

        // Additive: nothing that existed at v17 was touched.
        val noteCursor = db.query("SELECT title FROM notes WHERE id = 'n1'")
        assertTrue(noteCursor.moveToFirst())
        assertEquals("Kept note", noteCursor.getString(0))
        noteCursor.close()
        val convCursor = db.query("SELECT title FROM chat_conversations WHERE id = 'c1'")
        assertTrue(convCursor.moveToFirst())
        assertEquals("Kept chat", convCursor.getString(0))
        convCursor.close()

        // No backfill: the old "recents" were fabricated from list order, so there is no real
        // history to carry over. An upgraded install starts with an honest empty section.
        val countCursor = db.query("SELECT COUNT(*) FROM recent_activity")
        assertTrue(countCursor.moveToFirst())
        assertEquals(0, countCursor.getInt(0))
        countCursor.close()

        // The openedAt index exists under the name Room expects.
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'recent_activity' AND name = 'index_recent_activity_openedAt'"
        )
        assertTrue(indexCursor.moveToFirst())
        indexCursor.close()
        db.close()
    }

    @Test
    fun `migrated table enforces the composite key -- re-inserting the same item replaces it`() {
        val db = helper.createDatabase(testDbName, 17)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 18, false, RequestrDatabase.MIGRATION_17_18)

        migrated.execSQL(
            "INSERT INTO recent_activity (itemType, itemId, subtype, title, preview, openedAt) " +
                "VALUES ('NOTE', 'n1', 'MARKDOWN', 'First open', 'p', 1000)"
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO recent_activity (itemType, itemId, subtype, title, preview, openedAt) " +
                "VALUES ('NOTE', 'n1', 'MARKDOWN', 'Second open', 'p', 2000)"
        )
        // Same id, different type is a distinct item.
        migrated.execSQL(
            "INSERT INTO recent_activity (itemType, itemId, subtype, title, preview, openedAt) " +
                "VALUES ('API', 'n1', 'GET', 'An API', 'p', 3000)"
        )

        val cursor = migrated.query("SELECT itemType, title, openedAt FROM recent_activity ORDER BY openedAt DESC")
        assertTrue(cursor.moveToFirst())
        assertEquals("API", cursor.getString(0))
        assertTrue(cursor.moveToNext())
        assertEquals("NOTE", cursor.getString(0))
        assertEquals("Second open", cursor.getString(1))
        assertEquals(2000, cursor.getLong(2))
        assertFalse(cursor.moveToNext())
        cursor.close()
        migrated.close()
    }
}
