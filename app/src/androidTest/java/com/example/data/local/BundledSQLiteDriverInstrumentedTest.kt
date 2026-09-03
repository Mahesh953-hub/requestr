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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val fixtureDbName = "bundled-migration-fixture.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        context.deleteDatabase(fixtureDbName)
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
        val db = RequestrDatabase.buildDatabase(context, useBundledDriver = true)
        val rows = runBlocking { db.capturedTrafficDao().getAll() }
        assertEquals(0, rows.size)
        db.close()
    }

    // ── Genuine v8→v18 migration on bundled driver ──────────────────────────

    @Test
    fun bundledDriver_migratesV8ToV18WithDataPreservation() {
        val dbFile = context.getDatabasePath(fixtureDbName)
        dbFile.parentFile?.mkdirs()

        // Create a genuine v8 database with the v8 schema and one row.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 8
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS captured_traffic (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "sessionId INTEGER NOT NULL, method TEXT NOT NULL, url TEXT NOT NULL, " +
                    "host TEXT NOT NULL, path TEXT NOT NULL, statusCode INTEGER NOT NULL DEFAULT 0, " +
                    "protocol TEXT NOT NULL, requestHeaders TEXT NOT NULL, " +
                    "responseHeaders TEXT NOT NULL, requestBody TEXT, responseBody TEXT, " +
                    "requestSizeBytes INTEGER NOT NULL, responseSizeBytes INTEGER NOT NULL, " +
                    "latencyMs INTEGER NOT NULL, timestamp INTEGER NOT NULL, " +
                    "appPackage TEXT, appUid INTEGER, wasDecrypted INTEGER NOT NULL, " +
                    "isUndecoded INTEGER NOT NULL, sourceIp TEXT)"
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS capture_sessions (" +
                    "sessionId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "startedAt INTEGER NOT NULL, endedAt INTEGER, " +
                    "captureTier TEXT NOT NULL, isActive INTEGER NOT NULL)"
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS intercept_rules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, pattern TEXT NOT NULL, domain TEXT NOT NULL, " +
                    "action TEXT NOT NULL, replacementValue TEXT NOT NULL, " +
                    "isEnabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS saved_api_requests (" +
                    "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, folderId TEXT NOT NULL, " +
                    "method TEXT NOT NULL, url TEXT NOT NULL, queryParams TEXT NOT NULL, " +
                    "headers TEXT NOT NULL, authType TEXT NOT NULL, bearerToken TEXT NOT NULL, " +
                    "basicUsername TEXT NOT NULL, basicPassword TEXT NOT NULL, " +
                    "apiKeyHeader TEXT NOT NULL, apiKeyValue TEXT NOT NULL, " +
                    "bodyType TEXT NOT NULL, bodyContent TEXT NOT NULL, formData TEXT NOT NULL, " +
                    "preScript TEXT NOT NULL, postScript TEXT NOT NULL, " +
                    "isFavorite INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "chainExtractions TEXT NOT NULL, assertions TEXT NOT NULL)"
            )
            raw.execSQL("CREATE TABLE IF NOT EXISTS flow_nodes (id TEXT NOT NULL PRIMARY KEY, flowId TEXT NOT NULL, type TEXT NOT NULL, label TEXT NOT NULL, config TEXT NOT NULL, positionX REAL NOT NULL, positionY REAL NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS flow_connections (id TEXT NOT NULL PRIMARY KEY, flowId TEXT NOT NULL, sourceNodeId TEXT NOT NULL, targetNodeId TEXT NOT NULL, label TEXT NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS tcp_connections (connectionId TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, srcIp TEXT NOT NULL, destIp TEXT NOT NULL, srcPort INTEGER NOT NULL, destPort INTEGER NOT NULL, state TEXT NOT NULL, bytesIn INTEGER NOT NULL, bytesOut INTEGER NOT NULL, establishedAt INTEGER NOT NULL, closedAt INTEGER, closeReason TEXT)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS udp_flows (flowId TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, srcIp TEXT NOT NULL, destIp TEXT NOT NULL, srcPort INTEGER NOT NULL, destPort INTEGER NOT NULL, bytesIn INTEGER NOT NULL, bytesOut INTEGER NOT NULL, firstSeenAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL, packetCount INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS quic_connections (connectionId TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, srcIp TEXT NOT NULL, destIp TEXT NOT NULL, srcPort INTEGER NOT NULL, destPort INTEGER NOT NULL, sniHost TEXT, alpn TEXT, quicVersion TEXT, dstConnectionId TEXT, establishedAt INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS tls_sessions (tlsSessionId TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, connectionId TEXT NOT NULL, handshakeState TEXT NOT NULL, wasDecrypted INTEGER NOT NULL, establishedAt INTEGER NOT NULL, handshakeDurationMs INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS dns_queries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, queryName TEXT NOT NULL, queryType TEXT NOT NULL, responseIps TEXT NOT NULL, responseTtl INTEGER NOT NULL, latencyMs INTEGER NOT NULL, timestamp INTEGER NOT NULL, wasBlocked INTEGER NOT NULL, resolver TEXT NOT NULL)")

            raw.execSQL(
                "INSERT INTO captured_traffic (sessionId, method, url, host, path, protocol, " +
                    "requestHeaders, responseHeaders, requestSizeBytes, responseSizeBytes, " +
                    "latencyMs, timestamp, wasDecrypted, isUndecoded) VALUES " +
                    "(1, 'GET', 'https://example.com/migrated', 'example.com', '/migrated', " +
                    "'HTTP/1.1', '{}', '{}', 0, 0, 10, 1700000000000, 0, 0)"
            )
        }

        // Open with BundledSQLiteDriver — Room must run all 10 migrations (8→18).
        val db = buildBundled(dbName = fixtureDbName)

        // Verify the v8 row survived via DAO (the correct driver-mode API).
        val rows = runBlocking { db.capturedTrafficDao().getAll() }
        assertTrue("Pre-migration row must survive v8→v18", rows.isNotEmpty())
        assertEquals("GET", rows[0].method)
        assertEquals("https://example.com/migrated", rows[0].url)

        // Verify final version via driver connection.
        val driver = BundledSQLiteDriver()
        val conn = driver.open(dbFile.absolutePath)
        try {
            val stmt = conn.prepare("PRAGMA user_version")
            assertTrue(stmt.step())
            assertEquals(RequestrDatabase.SCHEMA_VERSION, stmt.getInt(0))
            stmt.close()

            // Spot-check tables from migration endpoints.
            for (table in listOf("notes", "flow_pipelines", "chat_conversations", "recent_activity")) {
                val check = conn.prepare(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'"
                )
                assertTrue("$table must exist after v8→v18", check.step())
                check.close()
            }
        } finally {
            conn.close()
            db.close()
        }
    }

    // ── Helper to execute SQL on a SQLiteConnection (no return value) ────────

    private fun SQLiteConnection.execSql(sql: String) {
        val stmt = prepare(sql)
        try { stmt.step() } finally { stmt.close() }
    }
}
