package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.persistence.DbBackup
import com.example.data.persistence.PersistenceHealth
import com.example.data.persistence.PersistenceHealth.MigrationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S2 task 17 / gate G15 "downgrade restores from backup": the real [RequestrDatabase.buildDatabase]
 * path against the real `requestr.db` file -- a downgrade wipe is reported as
 * [MigrationEvent.DESTRUCTIVE_WIPE] with a persisted diagnostics row, a viable backup is
 * restored on the next open and reported as [MigrationEvent.RESTORED_FROM_BACKUP] with the rows
 * back, a normal upgrade reports [MigrationEvent.BACKED_UP], and a failing migration surfaces as
 * [MigrationEvent.MIGRATION_FAILED] with the file still on its previous version.
 */
@RunWith(RobolectricTestRunner::class)
class RecoverySemanticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile: File get() = context.getDatabasePath(DbBackup.DB_NAME)

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        // Robolectric runs the real RequestrApplication, whose onCreate launches
        // RequestrDatabase.initAsync on IO -- i.e. a v19 `requestr.db` is being created in the
        // background while this test starts. Join that init (initAsync is synchronized, so this
        // returns once the in-flight build is done) before closing it and wiping the files;
        // otherwise the wipe races the open and the v18 seed lands under a live v19 WAL.
        runBlocking { withContext(Dispatchers.IO) { RequestrDatabase.initAsync(context) } }
        RequestrDatabase.resetForTest()
        PersistenceHealth.reset()
        wipeFiles()
    }

    @After
    fun tearDown() {
        RequestrDatabase.resetForTest()
        wipeFiles()
        PersistenceHealth.reset()
    }

    private fun wipeFiles() {
        context.deleteDatabase(DbBackup.DB_NAME)
        dbFile.parentFile?.listFiles()?.forEach { if (it.name.startsWith("requestr-")) it.delete() }
        // Robolectric quirk (see RequestrDatabaseBuilderTest): the databases dir must exist before
        // a framework open helper is pointed at a file in it.
        dbFile.parentFile?.mkdirs()
    }

    /**
     * A real v18 `requestr.db` with one captured row, created by Room's own v18 schema under a
     * scratch name and copied into place (checkpointed to a single file first).
     */
    private fun createV18WithOneRow() {
        val seedName = "recovery-seed-v18-${System.nanoTime()}"
        val db = helper.createDatabase(seedName, 18)
        db.execSQL(
            "INSERT INTO captured_traffic (id, sessionId, method, url, host, path, statusCode, protocol, " +
                "requestHeaders, responseHeaders, requestBody, responseBody, requestSizeBytes, responseSizeBytes, " +
                "latencyMs, timestamp, appPackage, appUid, wasDecrypted, isUndecoded, sourceIp, connectionId, " +
                "isRequestBodyBinary, isResponseBodyBinary, requestBodyBytes, responseBodyBytes, " +
                "requestBodyBytesTruncated, responseBodyBytesTruncated, captureMethod) VALUES (" +
                "1, 7, 'GET', 'https://kept.example/x', 'kept.example', '/x', 200, 'HTTP/1.1', '{}', '{}', NULL, 'ok', " +
                "10, 20, 123, 1000, 'com.app', 10077, 1, 0, NULL, 'conn-1', 0, 0, NULL, NULL, 0, 0, 'MITM_PROXY')",
        )
        db.close()
        val seed = context.getDatabasePath(seedName)
        SQLiteDatabase.openDatabase(seed.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        }
        dbFile.parentFile?.mkdirs()
        seed.copyTo(dbFile, overwrite = true)
        context.deleteDatabase(seedName)
        assertEquals(18, userVersion())
    }

    private fun rowCount(db: RequestrDatabase): Int = runBlocking { db.capturedTrafficDao().getAll().size }

    private fun userVersion(): Int =
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    @Test
    fun `upgrade from v18 backs up first, migrates, keeps rows and reports BACKED_UP`() {
        createV18WithOneRow()

        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = false)
        try {
            assertEquals(1, rowCount(db))
        } finally {
            db.close()
        }
        assertEquals(RequestrDatabase.SCHEMA_VERSION, userVersion())

        val snap = PersistenceHealth.snapshot()
        assertEquals(MigrationEvent.BACKED_UP, snap.migration)
        assertEquals("v18 → v20", snap.migrationDetail)
        assertNull("BACKED_UP is informational, not a headline", snap.headline)
        assertFalse(snap.hasDataLoss)

        val backup = DbBackup(context)
        assertTrue(backup.backupFileForVersion(18).exists())
        val events = backup.readRecoveryEvents()
        assertEquals(listOf("backed_up"), events.map { it.kind })
    }

    @Test
    fun `downgrade wipe is reported as DESTRUCTIVE_WIPE with a diagnostics row and no rows`() {
        createV18WithOneRow()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }

        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = false)
        try {
            assertEquals(0, rowCount(db)) // wiped: the tables were dropped and recreated
        } finally {
            db.close()
        }

        val snap = PersistenceHealth.snapshot()
        assertEquals(MigrationEvent.DESTRUCTIVE_WIPE, snap.migration)
        assertTrue(snap.hasDataLoss)
        assertTrue(snap.headline!!.startsWith("Database was reset"))
        assertTrue(snap.headline!!.contains("v99 → v20"))

        val backup = DbBackup(context)
        assertEquals(listOf("backed_up", "destructive_wipe"), backup.readRecoveryEvents().map { it.kind })
        // The v99 copy is kept for a future re-upgrade, but is not restorable by this app version.
        assertTrue(backup.backupFileForVersion(99).exists())
        assertFalse(backup.shouldRestoreBeforeOpen(RequestrDatabase.SCHEMA_VERSION))
    }

    @Test
    fun `a viable backup after a downgrade wipe is restored on the next open and rows come back`() {
        // A v18 file with data was backed up before an upgrade, then a wipe left nothing behind.
        createV18WithOneRow()
        val backup = DbBackup(context)
        assertTrue(backup.backupBeforeMigration(18))
        context.deleteDatabase(DbBackup.DB_NAME)
        backup.recordDestructiveWipe(fromVersion = 18, toVersion = RequestrDatabase.SCHEMA_VERSION)
        assertTrue(backup.shouldRestoreBeforeOpen(RequestrDatabase.SCHEMA_VERSION))

        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = false)
        try {
            assertEquals("restored row must survive the v18→v19 migration", 1, rowCount(db))
        } finally {
            db.close()
        }
        assertEquals(RequestrDatabase.SCHEMA_VERSION, userVersion())

        val snap = PersistenceHealth.snapshot()
        assertEquals(MigrationEvent.RESTORED_FROM_BACKUP, snap.migration)
        assertFalse(snap.hasDataLoss)
        assertTrue(snap.headline!!.startsWith("Database restored from backup"))
        assertTrue(snap.headline!!.contains("v18"))

        val kinds = DbBackup(context).readRecoveryEvents().map { it.kind }
        assertEquals(listOf("restored", "backed_up"), kinds)
    }

    @Test
    fun `a failing migration is reported as MIGRATION_FAILED and the file stays on the previous version`() {
        createV18WithOneRow()
        // Sabotage: a pre-existing table with the v19 child's name makes MIGRATION_18_19's
        // CREATE TABLE fail; Room rolls the migration back inside its transaction.
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TABLE transaction_headers (bogus INTEGER)")
        }

        // initAsync runs the warm-up only off the main thread, as in production.
        runBlocking { withContext(Dispatchers.IO) { RequestrDatabase.initAsync(context) } }

        val snap = PersistenceHealth.snapshot()
        assertEquals(MigrationEvent.MIGRATION_FAILED, snap.migration)
        assertNotNull(snap.migrationDetail)
        assertTrue(snap.hasDataLoss)
        assertTrue(snap.headline!!.startsWith("Database upgrade failed"))
        assertTrue(snap.headline!!.endsWith("still on the previous version"))

        RequestrDatabase.resetForTest()
        assertEquals("rolled back: still v18", 18, userVersion())
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM captured_traffic", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        val kinds = DbBackup(context).readRecoveryEvents().map { it.kind }
        assertEquals(listOf("backed_up", "migration_failed"), kinds)
    }

    @Test
    fun `recordMigration never lets a less severe later event hide a more severe one`() {
        PersistenceHealth.recordMigration(MigrationEvent.RESTORED_FROM_BACKUP, "r")
        PersistenceHealth.recordMigration(MigrationEvent.BACKED_UP, "b")
        assertEquals(MigrationEvent.RESTORED_FROM_BACKUP, PersistenceHealth.snapshot().migration)
        PersistenceHealth.recordMigration(MigrationEvent.DESTRUCTIVE_WIPE, "w")
        assertEquals(MigrationEvent.DESTRUCTIVE_WIPE, PersistenceHealth.snapshot().migration)
        PersistenceHealth.recordMigration(MigrationEvent.NONE)
        assertEquals(MigrationEvent.NONE, PersistenceHealth.snapshot().migration)
        PersistenceHealth.recordMigration(MigrationEvent.BACKUP_FAILED, "v18 → v20")
        assertTrue(PersistenceHealth.snapshot().headline!!.startsWith("Pre-upgrade backup failed"))
        assertFalse(PersistenceHealth.snapshot().hasDataLoss)
    }

    @Test
    fun `recovery events are bounded and survive the stable-launch cleanup`() {
        val backup = DbBackup(context)
        repeat(DbBackup.MAX_RECOVERY_EVENTS + 5) { backup.recordRecoveryEvent("k$it", "d", at = it.toLong()) }
        val events = backup.readRecoveryEvents()
        assertEquals(DbBackup.MAX_RECOVERY_EVENTS, events.size)
        assertEquals("k5", events.first().kind)
        assertEquals("k${DbBackup.MAX_RECOVERY_EVENTS + 4}", events.last().kind)

        // A real backup rewrites metadata: the rows must be carried over ...
        createV18WithOneRow()
        assertTrue(backup.backupBeforeMigration(18))
        assertEquals(DbBackup.MAX_RECOVERY_EVENTS, backup.readRecoveryEvents().size)
        // ... and the stable-launch cleanup removes the backup file but keeps the rows.
        backup.bumpOpenGeneration(); backup.markCleanLaunch()
        backup.bumpOpenGeneration(); backup.markCleanLaunch()
        assertFalse(backup.backupFileForVersion(18).exists())
        assertEquals(DbBackup.MAX_RECOVERY_EVENTS, backup.readRecoveryEvents().size)
    }
}
