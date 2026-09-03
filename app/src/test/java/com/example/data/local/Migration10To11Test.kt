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
 * v10 → v11 (transplant slice 11): proves `MIGRATION_10_11` creates the
 * `flow_pipelines` table and does not touch any existing table or row.
 */
@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {

    private val testDbName = "migration-10-11-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 10 to 11 creates flow_pipelines and preserves existing data`() {
        var db = helper.createDatabase(testDbName, 10)
        // Insert a row in an unrelated table to prove additive-only.
        db.execSQL(
            "INSERT INTO notes (id, title, content, format, folderId, folderName, " +
                "folderColorHex, isPinned, createdAt, updatedAt) " +
                "VALUES ('n1', 'Kept note', 'body', 'MARKDOWN', NULL, 'General', 0, 0, 1000, 2000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDbName, 11, false, RequestrDatabase.MIGRATION_10_11
        )

        // flow_pipelines table must exist
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='flow_pipelines'"
        ).use { c -> assertTrue("flow_pipelines must exist", c.moveToFirst()) }

        // flow_pipelines starts empty
        db.query("SELECT COUNT(*) FROM flow_pipelines").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // Pre-existing row in notes is untouched
        db.query("SELECT title FROM notes WHERE id = 'n1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Kept note", c.getString(0))
        }
        db.close()
    }

    @Test
    fun `a freshly inserted v11 flow_pipelines row round-trips`() {
        val db = helper.createDatabase(testDbName, 10)
        db.close()
        val migrated = helper.runMigrationsAndValidate(
            testDbName, 11, false, RequestrDatabase.MIGRATION_10_11
        )

        migrated.execSQL(
            "INSERT INTO flow_pipelines (id, name, description, triggerType, status, " +
                "lastRunText, runsCount, createdAt) " +
                "VALUES ('p1', 'Pipeline One', 'desc', 'MANUAL', 'IDLE', '', 0, 1700000000000)"
        )

        val cursor = migrated.query(
            "SELECT id, name, triggerType, status, runsCount FROM flow_pipelines WHERE id = 'p1'"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("p1", cursor.getString(0))
        assertEquals("Pipeline One", cursor.getString(1))
        assertEquals("MANUAL", cursor.getString(2))
        assertEquals("IDLE", cursor.getString(3))
        assertEquals(0, cursor.getInt(4))
        assertFalse(cursor.moveToNext())
        cursor.close()
        migrated.close()
    }
}
