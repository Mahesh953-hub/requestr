package com.example.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S0 task 5: verifies [DbBackup]'s WAL-safe snapshot, checkpoint-failure abort,
 * pre-migration backup, metadata tracking, restore viability logic,
 * generation-based stable-launch guard, v1–7 exclusion, stale WAL deletion on
 * restore, and failure semantics.
 */
@RunWith(RobolectricTestRunner::class)
class DbBackupTest {

    private lateinit var context: Context
    private lateinit var backup: DbBackup
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        backup = DbBackup(context)
        dbFile = context.getDatabasePath(DbBackup.DB_NAME)
        cleanupAll()
    }

    @After
    fun tearDown() {
        cleanupAll()
    }

    // ── readDatabaseVersion (WAL-safe) ──────────────────────────────────────

    @Test
    fun `readDatabaseVersion returns user_version from a real SQLite file`() {
        createSqliteDatabase(version = 18)
        assertEquals(18, backup.readDatabaseVersion(dbFile))
    }

    @Test
    fun `readDatabaseVersion reads through WAL correctly`() {
        createSqliteDatabase(version = 10)
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA user_version = 15")
        }
        assertEquals(15, backup.readDatabaseVersion(dbFile))
    }

    @Test
    fun `readDatabaseVersion returns 0 for non-existent file`() {
        assertEquals(0, backup.readDatabaseVersion(dbFile))
    }

    @Test
    fun `readDatabaseVersion returns 0 for file smaller than header`() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(50))
        assertEquals(0, backup.readDatabaseVersion(dbFile))
    }

    // ── backupBeforeMigration ───────────────────────────────────────────────

    @Test
    fun `backupBeforeMigration copies checkpointed database and writes metadata`() {
        createSqliteDatabase(version = 15)
        assertTrue(backup.backupBeforeMigration(15))

        val backupFile = backup.backupFileForVersion(15)
        assertTrue("Backup file must exist", backupFile.exists())
        assertTrue("Backup file must have content", backupFile.length() > 0)
        assertFalse("No -shm alongside backup", File(backupFile.path + "-shm").exists())

        val metadata = backup.readMetadata()
        assertNotNull(metadata)
        assertEquals(15, metadata!!.getInt("backupVersion"))
        assertFalse(metadata.getBoolean("destructiveWipeDetected"))
    }

    @Test
    fun `backupBeforeMigration refuses v1 through v7`() {
        for (v in 1..7) {
            createSqliteDatabase(version = v)
            assertFalse("Must not backup unsupported v$v", backup.backupBeforeMigration(v))
        }
    }

    @Test
    fun `backupBeforeMigration returns false for non-existent database`() {
        assertFalse(backup.backupBeforeMigration(10))
    }

    @Test
    fun `backup does not include WAL or SHM files`() {
        createSqliteDatabase(version = 16)
        File(dbFile.path + "-wal").writeText("wal-data")
        File(dbFile.path + "-shm").writeText("shm-data")

        assertTrue(backup.backupBeforeMigration(16))

        val bf = backup.backupFileForVersion(16)
        assertFalse("Backup must not include -shm", File(bf.path + "-shm").exists())
    }

    // ── Checkpoint failure aborts backup ─────────────────────────────────────

    @Test
    fun `checkpoint failure causes backup to fail safely`() {
        // Create a non-SQLite file that checkpointWal cannot open.
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("not a sqlite database at all")

        val result = backup.backupBeforeMigration(18)

        assertFalse("Backup must fail when checkpoint fails", result)
        assertFalse(
            "No backup file should exist after failed checkpoint",
            backup.backupFileForVersion(18).exists()
        )
        assertNull("No metadata should exist after failed backup", backup.readMetadata())
    }

    @Test
    fun `checkpointWal throws on corrupt file`() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("corrupt")

        var threw = false
        try {
            backup.checkpointWal(dbFile)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("checkpointWal must throw on corrupt file", threw)
    }

    // ── v1-v7 no-loop ───────────────────────────────────────────────────────

    @Test
    fun `shouldRestoreBeforeOpen rejects backup at v7 (no infinite loop)`() {
        createSqliteDatabase(version = 8)
        backup.backupBeforeMigration(8)
        val metadata = backup.readMetadata()!!
        metadata.put("backupVersion", 7)
        metadata.put("destructiveWipeDetected", true)
        File(dbFile.parentFile, "requestr-backup-metadata.json")
            .writeText(metadata.toString(2))

        assertFalse(
            "Must never restore v1-7 backup",
            backup.shouldRestoreBeforeOpen(appSchemaVersion = 18)
        )
    }

    // ── recordDestructiveWipe ───────────────────────────────────────────────

    @Test
    fun `recordDestructiveWipe stamps metadata with wipe event`() {
        createSqliteDatabase(version = 19)
        backup.backupBeforeMigration(19)
        backup.recordDestructiveWipe(fromVersion = 19, toVersion = 18)

        val metadata = backup.readMetadata()!!
        assertTrue(metadata.getBoolean("destructiveWipeDetected"))
        assertEquals(19, metadata.getInt("wipeFromVersion"))
        assertEquals(18, metadata.getInt("wipeToVersion"))
        assertTrue(metadata.getLong("wipeTimestamp") > 0)
    }

    @Test
    fun `recordDestructiveWipe creates metadata even without prior backup`() {
        backup.recordDestructiveWipe(fromVersion = 20, toVersion = 18)
        assertTrue(backup.readMetadata()!!.getBoolean("destructiveWipeDetected"))
    }

    // ── shouldRestoreBeforeOpen ──────────────────────────────────────────────

    @Test
    fun `shouldRestoreBeforeOpen returns true when backup is viable`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)
        createSqliteDatabase(version = 17) // wiped to lower version

        assertTrue(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `shouldRestoreBeforeOpen returns false when backup version exceeds app version`() {
        createSqliteDatabase(version = 20)
        backup.backupBeforeMigration(20)
        backup.recordDestructiveWipe(fromVersion = 20, toVersion = 18)
        createSqliteDatabase(version = 18)

        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `shouldRestoreBeforeOpen returns false when no wipe was recorded`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `shouldRestoreBeforeOpen returns false when backup file is missing`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)
        backup.backupFileForVersion(18).delete()
        createSqliteDatabase(version = 17)

        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `shouldRestoreBeforeOpen returns false when backup is not newer than current DB`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 18)

        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `shouldRestoreBeforeOpen returns false when no metadata exists`() {
        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    // ── restoreBackup + stale WAL deletion ──────────────────────────────────

    @Test
    fun `restoreBackup copies backup over the live database`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)
        createSqliteDatabase(version = 17)

        assertTrue(backup.restoreBackup())
        assertEquals(18, backup.readDatabaseVersion(dbFile))
        assertFalse(backup.readMetadata()!!.getBoolean("destructiveWipeDetected"))
    }

    @Test
    fun `restoreBackup deletes stale WAL and SHM from wiped DB`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)
        createSqliteDatabase(version = 17)
        // Simulate stale WAL/SHM from the wiped database
        File(dbFile.path + "-wal").writeText("stale-wal")
        File(dbFile.path + "-shm").writeText("stale-shm")

        assertTrue(backup.restoreBackup())
        assertFalse("Stale WAL must be deleted on restore", File(dbFile.path + "-wal").exists())
        assertFalse("Stale SHM must be deleted on restore", File(dbFile.path + "-shm").exists())
    }

    @Test
    fun `restoreBackup returns false when backup file is missing`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)
        backup.backupFileForVersion(18).delete()

        assertFalse(backup.restoreBackup())
    }

    @Test
    fun `restoreBackup returns false when no metadata exists`() {
        assertFalse(backup.restoreBackup())
    }

    // ── markCleanLaunch — generation-based, not callback-count-based ────────

    @Test
    fun `markCleanLaunch deduplicates calls within same generation`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)

        // Generation 1: bump, then call markCleanLaunch multiple times
        backup.bumpOpenGeneration()
        backup.markCleanLaunch()
        backup.markCleanLaunch() // same generation — must not double-count
        backup.markCleanLaunch()

        // Backup must still exist — only 1 distinct generation has cleanly opened
        assertTrue("Backup must survive after one generation",
            backup.backupFileForVersion(18).exists())
    }

    @Test
    fun `markCleanLaunch requires two distinct generations before cleanup`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)

        val backupFile = backup.backupFileForVersion(18)
        assertTrue(backupFile.exists())

        // Generation 1
        backup.bumpOpenGeneration()
        backup.markCleanLaunch()
        assertTrue("Backup must survive first generation", backupFile.exists())

        // Generation 2
        backup.bumpOpenGeneration()
        backup.markCleanLaunch()
        assertFalse("Backup should be deleted after stable generation", backupFile.exists())
        assertNull("Metadata should be deleted", backup.readMetadata())
    }

    @Test
    fun `markCleanLaunch does not delete backup when destructive wipe is pending`() {
        createSqliteDatabase(version = 18)
        backup.backupBeforeMigration(18)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = 17)

        backup.bumpOpenGeneration()
        backup.markCleanLaunch()
        backup.bumpOpenGeneration()
        backup.markCleanLaunch()

        assertTrue("Backup must be preserved", backup.backupFileForVersion(18).exists())
        assertNotNull(backup.readMetadata())
    }

    @Test
    fun `markCleanLaunch is a no-op when no metadata exists`() {
        backup.markCleanLaunch() // must not throw
    }

    // ── Full lifecycle: rollback → wipe → re-upgrade → restore ──────────────

    @Test
    fun `full downgrade-restore lifecycle`() {
        // 1. User has v18 database with real data.
        createSqliteDatabase(version = 18)

        // 2. App upgrades to v19 — backup v18 before migration.
        assertTrue(backup.backupBeforeMigration(18))

        // 3. Database migrates to v19.
        createSqliteDatabase(version = 19)

        // 4. User rolls back to v18 app — detects v19 on disk, backs it up.
        val backup2 = DbBackup(context)
        assertTrue(backup2.backupBeforeMigration(19))

        // 5. Room wipes (v19 → v18 downgrade).
        createSqliteDatabase(version = 18) // empty post-wipe
        backup2.recordDestructiveWipe(fromVersion = 19, toVersion = 18)

        // 6. shouldRestore is false: backup v19 > app v18.
        assertFalse(backup2.shouldRestoreBeforeOpen(appSchemaVersion = 18))

        // 7. User re-upgrades to v19 app.
        assertTrue(backup2.shouldRestoreBeforeOpen(appSchemaVersion = 19))

        // 8. Restore succeeds.
        assertTrue(backup2.restoreBackup())
        assertEquals(19, backup2.readDatabaseVersion(dbFile))
    }

    // ── Corrupt / interrupted backup ────────────────────────────────────────

    @Test
    fun `corrupt metadata is silently ignored`() {
        val metadataFile = File(dbFile.parentFile, "requestr-backup-metadata.json")
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText("not valid json {{{")

        assertNull(backup.readMetadata())
        assertFalse(backup.shouldRestoreBeforeOpen(appSchemaVersion = 18))
    }

    @Test
    fun `interrupted backup leaves no partial backup if main copy fails`() {
        assertFalse(backup.backupBeforeMigration(18))
        assertFalse(backup.backupFileForVersion(18).exists())
        assertNull(backup.readMetadata())
    }

    // ── Feature-flag rollback: default + toggle cycle ───────────────────────

    @Test
    fun `feature flag default is on for S0`() {
        val prefs = context.getSharedPreferences("test_driver_flag", Context.MODE_PRIVATE)
        val flag = DbDriverFlag.forTest(prefs)
        assertTrue("S0 default must be true", flag.isEnabled)
    }

    @Test
    fun `feature flag survives toggle cycle`() {
        val prefs = context.getSharedPreferences("test_driver_flag", Context.MODE_PRIVATE)
        val flag = DbDriverFlag.forTest(prefs)

        flag.setEnabled(false)
        assertFalse(flag.isEnabled)

        flag.setEnabled(true)
        assertTrue(flag.isEnabled)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun createSqliteDatabase(version: Int) {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = version
        }
    }

    private fun cleanupAll() {
        context.deleteDatabase(DbBackup.DB_NAME)
        val parent = dbFile.parentFile ?: return
        parent.listFiles()?.forEach { f ->
            if (f.name.startsWith("requestr-") && (f.name.endsWith(".bak") ||
                    f.name == "requestr-backup-metadata.json")) {
                f.delete()
            }
        }
    }
}
