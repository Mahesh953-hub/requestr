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
 * Reqy Assistant completeness pass, item 1: proves `MIGRATION_16_17` is a real,
 * non-destructive migration for multi-conversation chat history -- every pre-existing
 * `chat_messages` row MUST survive (never dropped) and gets folded into one new
 * `chat_conversations` row (`'default-migrated'`) with a title derived from the earliest
 * surviving user message, matching [com.example.data.models.deriveChatTitle]'s own
 * truncate-at-40-chars-plus-ellipsis convention. Modelled directly on [Migration15To16Test].
 */
@RunWith(RobolectricTestRunner::class)
class Migration16To17Test {

    private val testDbName = "migration-16-17-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 16 to 17 preserves existing messages and folds them into one derived-title conversation`() {
        var db = helper.createDatabase(testDbName, 16)
        db.execSQL(
            "INSERT INTO chat_messages (id, role, content, timestamp) VALUES " +
                "('m1', 'user', 'This is a really long first user message that goes past forty chars', 1000)"
        )
        db.execSQL(
            "INSERT INTO chat_messages (id, role, content, timestamp) VALUES " +
                "('m2', 'assistant', 'hi there', 2000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 17, false, RequestrDatabase.MIGRATION_16_17)

        // Both pre-existing rows survive, now tagged with the migrated conversation id.
        val messagesCursor = db.query("SELECT id, role, content, timestamp, conversationId FROM chat_messages ORDER BY timestamp ASC")
        assertTrue(messagesCursor.moveToFirst())
        assertEquals("m1", messagesCursor.getString(0))
        assertEquals("user", messagesCursor.getString(1))
        assertEquals("This is a really long first user message that goes past forty chars", messagesCursor.getString(2))
        assertEquals(1000, messagesCursor.getLong(3))
        assertEquals("default-migrated", messagesCursor.getString(4))
        assertTrue(messagesCursor.moveToNext())
        assertEquals("m2", messagesCursor.getString(0))
        assertEquals("default-migrated", messagesCursor.getString(4))
        assertFalse(messagesCursor.moveToNext())
        messagesCursor.close()

        // Exactly one conversation, folding the old history, with a derived title matching
        // deriveChatTitle's own 40-char + ellipsis truncation of the earliest USER message.
        val convCursor = db.query("SELECT id, title, titleIsOverride, createdAt, updatedAt FROM chat_conversations")
        assertTrue(convCursor.moveToFirst())
        assertEquals("default-migrated", convCursor.getString(0))
        assertEquals("This is a really long first user message\u2026", convCursor.getString(1))
        assertEquals(0, convCursor.getInt(2))
        assertEquals(1000, convCursor.getLong(3))
        assertEquals(2000, convCursor.getLong(4))
        assertFalse(convCursor.moveToNext())
        convCursor.close()
        db.close()
    }

    @Test
    fun `migrate 16 to 17 with zero pre-existing messages creates no spurious conversation`() {
        var db = helper.createDatabase(testDbName, 16)
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 17, false, RequestrDatabase.MIGRATION_16_17)

        val messagesCursor = db.query("SELECT COUNT(*) FROM chat_messages")
        assertTrue(messagesCursor.moveToFirst())
        assertEquals(0, messagesCursor.getInt(0))
        messagesCursor.close()

        val convCursor = db.query("SELECT COUNT(*) FROM chat_conversations")
        assertTrue(convCursor.moveToFirst())
        assertEquals(0, convCursor.getInt(0))
        convCursor.close()
        db.close()
    }

    @Test
    fun `migrate 16 to 17 falls back to a placeholder title when no user message survives`() {
        var db = helper.createDatabase(testDbName, 16)
        db.execSQL(
            "INSERT INTO chat_messages (id, role, content, timestamp) VALUES " +
                "('m1', 'assistant', 'a local-mode-only reply', 1000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 17, false, RequestrDatabase.MIGRATION_16_17)

        val convCursor = db.query("SELECT title FROM chat_conversations WHERE id = 'default-migrated'")
        assertTrue(convCursor.moveToFirst())
        assertEquals("Imported chat", convCursor.getString(0))
        convCursor.close()
        db.close()
    }

    @Test
    fun `a freshly inserted v17 message with an explicit conversationId round-trips`() {
        val db = helper.createDatabase(testDbName, 16)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 17, false, RequestrDatabase.MIGRATION_16_17)

        migrated.execSQL(
            "INSERT INTO chat_conversations (id, title, titleIsOverride, createdAt, updatedAt) VALUES " +
                "('conv-2', 'A second chat', 1, 5000, 5000)"
        )
        migrated.execSQL(
            "INSERT INTO chat_messages (id, role, content, timestamp, conversationId) VALUES " +
                "('m3', 'user', 'fresh row', 6000, 'conv-2')"
        )

        val cursor = migrated.query("SELECT conversationId FROM chat_messages WHERE id = 'm3'")
        assertTrue(cursor.moveToFirst())
        assertEquals("conv-2", cursor.getString(0))
        cursor.close()

        val convCursor = migrated.query("SELECT title, titleIsOverride FROM chat_conversations WHERE id = 'conv-2'")
        assertTrue(convCursor.moveToFirst())
        assertEquals("A second chat", convCursor.getString(0))
        assertEquals(1, convCursor.getInt(1))
        convCursor.close()
        migrated.close()
    }
}
