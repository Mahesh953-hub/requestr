package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.persistence.DbBackup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumentation tests for [RequestrDatabase] with [BundledSQLiteDriver].
 *
 * These tests **must** run on a real device or emulator because `BundledSQLiteDriver`
 * loads a native `libsqliteJni.so`.
 *
 * In Room 2.7.0's pure driver mode (`setDriver` without `openHelperFactory`),
 * `RoomDatabase.getOpenHelper()`, `.query(String,Object[])`, and `.runInTransaction`
 * all throw `IllegalStateException`.  All queries go through **generated DAO suspend
 * functions** (the production path) or the driver's own [SQLiteConnection] for
 * capability probes (FTS5/JSON1/WAL).
 */
@RunWith(AndroidJUnit4::class)
class BundledSQLiteDriverInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "bundled-driver-instrumented-test.db"

    @Before
    fun setUp() {
        // The production-path tests below open the REAL production database name, so start
        // every test from a pristine on-disk state -- state left behind by a previous test
        // (or a previous run on the same device) must never leak into assertions.
        wipeProductionDatabaseState()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        wipeProductionDatabaseState()
    }

    /**
     * Deletes the production database file (plus its `-wal`/`-shm` sidecars via
     * [Context.deleteDatabase]) and every [DbBackup] artifact next to it (pre-migration `.bak`
     * snapshots and the backup metadata JSON), so [RequestrDatabase.buildDatabase] sees a true
     * fresh-install state.
     */
    private fun wipeProductionDatabaseState() {
        context.deleteDatabase(DbBackup.DB_NAME)
        context.getDatabasePath(DbBackup.DB_NAME).parentFile
            ?.listFiles { f -> f.name.startsWith("requestr-pre-v") || f.name.startsWith("requestr-backup") }
            ?.forEach { it.delete() }
    }

    /** Builds a fresh database with BundledSQLiteDriver, all migrations, and WAL. */
    private fun buildBundled(
        dbName: String = testDbName,
        callback: RoomDatabase.Callback? = null,
    ): RequestrDatabase {
        val builder = Room.databaseBuilder(context, RequestrDatabase::class.java, dbName)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
            .fallbackToDestructiveMigrationOnDowngrade(true)
        if (callback != null) builder.addCallback(callback)
        return builder.build()
    }

    // ── FTS5 proven via BundledSQLiteDriver connection ──────────────────────

    @Test
    fun bundledDriver_fts5Works() {
        // Open a direct BundledSQLiteDriver connection to prove the shipped
        // native SQLite binary includes FTS5.  This is the correct API for raw
        // SQL in pure driver mode — Room's generated DAOs use it internally.
        val driver = BundledSQLiteDriver()
        val conn = driver.open(context.getDatabasePath("fts5-probe.db").absolutePath)
        try {
            conn.execSql("CREATE VIRTUAL TABLE fts5_test USING fts5(content, tokenize='unicode61')")
            conn.execSql("INSERT INTO fts5_test(content) VALUES ('hello world')")
            val stmt = conn.prepare("SELECT COUNT(*) FROM fts5_test WHERE fts5_test MATCH 'hello'")
            assertTrue("step must return a row", stmt.step())
            assertEquals("FTS5 MATCH must find the row", 1, stmt.getInt(0))
            stmt.close()
        } finally {
            conn.close()
            context.deleteDatabase("fts5-probe.db")
        }
    }

    // ── JSON1 proven via BundledSQLiteDriver connection ─────────────────────

    @Test
    fun bundledDriver_json1Works() {
        val driver = BundledSQLiteDriver()
        val conn = driver.open(context.getDatabasePath("json1-probe.db").absolutePath)
        try {
            val stmt = conn.prepare("SELECT json_extract('{\"a\": 42}', '$.a')")
            assertTrue(stmt.step())
            assertEquals("json_extract must return 42", 42, stmt.getInt(0))
            stmt.close()
        } finally {
            conn.close()
            context.deleteDatabase("json1-probe.db")
        }
    }

    // ── WAL proven via BundledSQLiteDriver connection after Room opens ───────

    @Test
    fun bundledDriver_walEnabled() {
        // Build with Room (which sets WAL), then probe the file directly.
        val db = buildBundled()
        // Force Room to open by calling a DAO.
        runBlocking { db.capturedTrafficDao().getAll() }

        val driver = BundledSQLiteDriver()
        val conn = driver.open(context.getDatabasePath(testDbName).absolutePath)
        try {
            val stmt = conn.prepare("PRAGMA journal_mode")
            assertTrue(stmt.step())
            assertEquals("wal", stmt.getText(0))
            stmt.close()
        } finally {
            conn.close()
            db.close()
        }
    }

    // ── Room callbacks fire via SQLiteConnection overload ────────────────────

    @Test
    fun bundledDriver_onOpenCallbackFires() {
        var onOpenFired = false

        val db = buildBundled(callback = object : RoomDatabase.Callback() {
            override fun onOpen(connection: SQLiteConnection) {
                onOpenFired = true
            }
        })
        // Force open by using a DAO.
        runBlocking { db.capturedTrafficDao().getAll() }
        assertTrue("onOpen(SQLiteConnection) must fire in bundled mode", onOpenFired)
        db.close()
    }

    @Test
    fun bundledDriver_onDestructiveMigrationCallbackFires() {
        // Stamp a future version so Room triggers destructive downgrade.
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 99
        }

        var destructiveFired = false

        val db = buildBundled(callback = object : RoomDatabase.Callback() {
            override fun onDestructiveMigration(connection: SQLiteConnection) {
                destructiveFired = true
            }
        })
        // Force open.
        runBlocking { db.capturedTrafficDao().getAll() }
        assertTrue(
            "onDestructiveMigration(SQLiteConnection) must fire on downgrade",
            destructiveFired
        )
        db.close()
    }

    // ── Normal DAO access ───────────────────────────────────────────────────

    @Test
    fun bundledDriver_daoQueryWorks() {
        val db = buildBundled()
        val rows = runBlocking { db.capturedTrafficDao().getAll() }
        assertEquals("Fresh DB must be empty", 0, rows.size)
        db.close()
    }

    // ── Schema version via driver connection ────────────────────────────────

    @Test
    fun bundledDriver_freshBuildReachesSchemaVersion() {
        val db = buildBundled()
        // Force Room to open.
        runBlocking { db.capturedTrafficDao().getAll() }

        val driver = BundledSQLiteDriver()
        val conn = driver.open(context.getDatabasePath(testDbName).absolutePath)
        try {
            val stmt = conn.prepare("PRAGMA user_version")
            assertTrue(stmt.step())
            assertEquals(RequestrDatabase.SCHEMA_VERSION, stmt.getInt(0))
            stmt.close()
        } finally {
            conn.close()
            db.close()
        }
    }

    // ── Production buildDatabase with bundled driver ─────────────────────────

    @Test
    fun bundledDriver_productionBuildDatabaseOpensCorrectly() {
        // Fresh install through the real production builder with the bundled driver requested:
        // no on-disk DB exists (see setUp), so `needsMigration=false` and the bundled driver is
        // genuinely selected. Prove it opens, serves DAO queries, and lands on SCHEMA_VERSION.
        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = true)
        val rows = runBlocking { db.capturedTrafficDao().getAll() }
        assertEquals("fresh production DB must start empty", 0, rows.size)
        db.close()

        assertProductionDbVersionViaBundledDriver()
    }

    // ── Genuine v8→v18 migration through the production build path ──────────
    //
    // Room 2.7's pure driver mode (`setDriver` without an openHelperFactory) requires every
    // `Migration` to override `migrate(SQLiteConnection)`; the ten shipped v8→v18 migrations
    // only override `migrate(SupportSQLiteDatabase)`, so driving `BundledSQLiteDriver +
    // ALL_MIGRATIONS` directly throws `NotImplementedError`. Production never does that:
    // `RequestrDatabase.buildDatabase` detects a pending migration and falls back to the
    // support driver for that one open, then uses the bundled driver on every later open.
    // This test exercises exactly that shipped path on a real device.

    @Test
    fun bundledDriver_migratesV8ToV18WithDataPreservation() {
        val dbFile = context.getDatabasePath(DbBackup.DB_NAME)
        dbFile.parentFile?.mkdirs()

        // Create a genuine v8 database from the *exported* v8 schema (app/schemas/.../8.json,
        // shipped as a main asset). Deriving the DDL from the export -- instead of hand-writing
        // it -- guarantees the fixture matches what real v8 installs had on disk (e.g. the
        // nullable `statusCode`), so a post-migration validation failure means a real bug in a
        // shipped migration rather than fixture drift.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 8
            for (sql in exportedSchemaDdl(version = 8)) raw.execSQL(sql)

            raw.execSQL(
                "INSERT INTO captured_traffic (sessionId, method, url, host, path, protocol, " +
                    "requestHeaders, responseHeaders, requestSizeBytes, responseSizeBytes, " +
                    "latencyMs, timestamp, wasDecrypted, isUndecoded) VALUES " +
                    "(1, 'GET', 'https://example.com/migrated', 'example.com', '/migrated', " +
                    "'HTTP/1.1', '{}', '{}', 0, 0, 10, 1700000000000, 0, 0)"
            )
        }

        // Open #1 through the PRODUCTION builder with the bundled driver requested. Because a
        // v8→v18 migration is pending, buildDatabase falls back to the support driver for this
        // open and runs all ten migrations. It must also snapshot the v8 file first (Phase 2).
        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = true)
        try {
            val rows = runBlocking { db.capturedTrafficDao().getAll() }
            assertTrue("Pre-migration row must survive v8→v18", rows.isNotEmpty())
            assertEquals("GET", rows[0].method)
            assertEquals("https://example.com/migrated", rows[0].url)
        } finally {
            db.close()
        }
        assertTrue(
            "buildDatabase must snapshot the v8 file before migrating",
            DbBackup(context).backupFileForVersion(8).exists(),
        )

        // Verify the final version + migration-endpoint tables through a raw BundledSQLiteDriver
        // connection -- proving the migrated file is readable by the bundled native SQLite.
        assertProductionDbVersionViaBundledDriver()
        val driver = BundledSQLiteDriver()
        val conn = driver.open(dbFile.absolutePath)
        try {
            for (table in listOf("notes", "flow_pipelines", "chat_conversations", "recent_activity")) {
                val check = conn.prepare(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'"
                )
                assertTrue("$table must exist after v8→v18", check.step())
                check.close()
            }
        } finally {
            conn.close()
        }

        // Open #2 through the production builder again: the on-disk version now equals
        // SCHEMA_VERSION, so `needsMigration=false` and Room genuinely opens with
        // BundledSQLiteDriver on the migrated data. The row must still be served via DAO.
        val reopened = RequestrDatabase.buildDatabase(context, useBundledDriver = true)
        try {
            val rows = runBlocking { reopened.capturedTrafficDao().getAll() }
            assertEquals("migrated row must be readable via the bundled driver", 1, rows.size)
            assertEquals("https://example.com/migrated", rows[0].url)
        } finally {
            reopened.close()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Asserts the production DB file's `PRAGMA user_version` via a raw bundled connection. */
    private fun assertProductionDbVersionViaBundledDriver() {
        val conn = BundledSQLiteDriver().open(context.getDatabasePath(DbBackup.DB_NAME).absolutePath)
        try {
            val stmt = conn.prepare("PRAGMA user_version")
            assertTrue(stmt.step())
            assertEquals(RequestrDatabase.SCHEMA_VERSION, stmt.getInt(0))
            stmt.close()
        } finally {
            conn.close()
        }
    }

    /**
     * Returns every `CREATE TABLE` / `CREATE INDEX` statement from the exported Room schema
     * `<RequestrDatabase FQCN>/<version>.json` (wired as a main asset in app/build.gradle.kts),
     * with Room's `${TABLE_NAME}` placeholder resolved.
     */
    private fun exportedSchemaDdl(version: Int): List<String> {
        val path = "${RequestrDatabase::class.java.name}/$version.json"
        val json = context.assets.open(path).bufferedReader().use { it.readText() }
        val entities = JSONObject(json).getJSONObject("database").getJSONArray("entities")
        val ddl = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            ddl += entity.getString("createSql").replace("\${TABLE_NAME}", table)
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                ddl += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        return ddl
    }

    /** Executes SQL on a SQLiteConnection (no return value). */
    private fun SQLiteConnection.execSql(sql: String) {
        val stmt = prepare(sql)
        try { stmt.step() } finally { stmt.close() }
    }
}
