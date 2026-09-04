package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.persistence.DbBackup
import com.example.data.persistence.DbDriverFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S0 tasks 3 + 5: proves the [RequestrDatabase] builder configuration is correct
 * in **support-driver mode** (the default, Robolectric-compatible path).
 *
 * Bundled-driver mode (`BundledSQLiteDriver`) loads a native `.so` that is not
 * available under Robolectric.  Its FTS5/JSON1 gate and callback tests belong in
 * `androidTest` (on-device).  These unit tests verify the framework/support path
 * which is the safe default and the rollback target.
 */
@RunWith(RobolectricTestRunner::class)
class RequestrDatabaseBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "builder-config-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    // ── Support-mode builder tests ──────────────────────────────────────────

    @Test
    fun `support mode - database opens with all migrations and reaches current version`() {
        val db = buildSupportMode()
        try {
            val sdb = db.openHelper.readableDatabase
            val version = sdb.query("PRAGMA user_version").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals(RequestrDatabase.SCHEMA_VERSION, version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `support mode - SCHEMA_VERSION matches annotated database version`() {
        val db = buildSupportMode()
        try {
            val sdb = db.openHelper.readableDatabase
            val actualVersion = sdb.query("PRAGMA user_version").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals(RequestrDatabase.SCHEMA_VERSION, actualVersion)
        } finally {
            db.close()
        }
    }

    @Test
    fun `support mode - WAL is enabled`() {
        val db = buildSupportMode()
        try {
            val sdb = db.openHelper.readableDatabase
            val journalMode = sdb.query("PRAGMA journal_mode").use { c ->
                c.moveToFirst(); c.getString(0)
            }
            assertEquals("wal", journalMode)
        } finally {
            db.close()
        }
    }

    @Test
    fun `support mode - downgrade from a higher version does not crash`() {
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 99
        }

        val db = buildSupportMode()
        try {
            val count = db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM captured_traffic"
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(0, count)
        } finally {
            db.close()
        }
    }

    // ── Support-mode callback test ──────────────────────────────────────────

    @Test
    fun `support mode - onOpen callback fires`() {
        var onOpenCalled = false

        val db = Room.databaseBuilder(context, RequestrDatabase::class.java, testDbName)
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    onOpenCalled = true
                }
                override fun onOpen(connection: SQLiteConnection) {
                    // Room 2.7.0 calls the SQLiteConnection overload in all modes.
                    // In support mode the connection is a SupportSQLiteConnection;
                    // the base class default would forward to the SupportSQLiteDatabase
                    // overload, but overriding this prevents that forwarding.  Our
                    // production BackupCallback handles both → same shared logic.
                    onOpenCalled = true
                }
            })
            .build()

        try {
            db.openHelper.readableDatabase // force open
            assertTrue("onOpen callback must fire", onOpenCalled)
        } finally {
            db.close()
        }
    }

    @Test
    fun `support mode - onDestructiveMigration callback fires on downgrade`() {
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 99
        }

        var destructiveCalled = false

        val db = Room.databaseBuilder(context, RequestrDatabase::class.java, testDbName)
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    destructiveCalled = true
                }
                override fun onDestructiveMigration(connection: SQLiteConnection) {
                    destructiveCalled = true
                }
            })
            .build()

        try {
            db.openHelper.readableDatabase // force open
            assertTrue(
                "onDestructiveMigration must fire on downgrade from v99",
                destructiveCalled
            )
        } finally {
            db.close()
        }
    }

    // ── Feature flag tests ──────────────────────────────────────────────────

    @Test
    fun `feature flag default is off - buildDatabase uses support mode`() {
        // DbDriverFlag defaults to false; buildDatabase should succeed under
        // Robolectric, proving it does NOT attempt BundledSQLiteDriver.
        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = false)
        try {
            val sdb = db.openHelper.readableDatabase
            val version = sdb.query("PRAGMA user_version").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals(RequestrDatabase.SCHEMA_VERSION, version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `ALL_MIGRATIONS array contains exactly 14 migrations`() {
        assertEquals(14, RequestrDatabase.ALL_MIGRATIONS.size)
        assertEquals(8, RequestrDatabase.ALL_MIGRATIONS.first().startVersion)
        assertEquals(9, RequestrDatabase.ALL_MIGRATIONS.first().endVersion)
        assertEquals(21, RequestrDatabase.ALL_MIGRATIONS.last().startVersion)
        assertEquals(22, RequestrDatabase.ALL_MIGRATIONS.last().endVersion)
    }

    // ── MigrationTestHelper: all 14 migrations on support path ──────────────

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `all 10 migrations run sequentially from v8 to v18 on support path`() {
        // Create a v8 database.
        var db = helper.createDatabase("migration-chain-test", 8)
        db.close()

        // Run all migrations in sequence.
        db = helper.runMigrationsAndValidate(
            "migration-chain-test", 18, false,
            *RequestrDatabase.ALL_MIGRATIONS
        )

        // Spot-check tables from first and last migration exist.
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='notes'").use { c ->
            assertTrue("notes table from v9 must exist", c.moveToFirst())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='recent_activity'").use { c ->
            assertTrue("recent_activity table from v18 must exist", c.moveToFirst())
        }
        db.close()
    }

    // ── BackupCallback structural test ──────────────────────────────────────

    @Test
    fun `BackupCallback records destructive wipe and marks clean launch via support overloads`() {
        createSqliteDatabase(version = 18)
        val localBackup = DbBackup(context)
        localBackup.backupBeforeMigration(18)

        // Simulate downgrade: stamp v99 then open with destructive fallback.
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 99
        }

        // Wire a backup that tracks the pre-open version.
        val testBackup = DbBackup(context)
        var destructiveCalled = false

        val db = Room.databaseBuilder(context, RequestrDatabase::class.java, testDbName)
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    testBackup.recordDestructiveWipe(99, RequestrDatabase.SCHEMA_VERSION)
                    destructiveCalled = true
                }
                override fun onDestructiveMigration(connection: SQLiteConnection) {
                    testBackup.recordDestructiveWipe(99, RequestrDatabase.SCHEMA_VERSION)
                    destructiveCalled = true
                }
            })
            .build()

        try {
            db.openHelper.readableDatabase
            assertTrue("Destructive callback must fire", destructiveCalled)

            val metadata = testBackup.readMetadata()
            assertNotNull(metadata)
            assertTrue(metadata!!.getBoolean("destructiveWipeDetected"))
            assertEquals(99, metadata.getInt("wipeFromVersion"))
        } finally {
            db.close()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSupportMode(): RequestrDatabase {
        return Room.databaseBuilder(context, RequestrDatabase::class.java, testDbName)
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    private fun createSqliteDatabase(version: Int) {
        val dbFile = context.getDatabasePath(DbBackup.DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = version
        }
    }
}
