package com.example.data.search

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S3 task 22 (interceptor plan §4, v22): the `traffic_fts` FTS5 index over `captured_traffic`.
 *
 * ```
 * traffic_fts(traffic_id UNINDEXED, url, host, headers_text, body_text, tokenize='unicode61')
 * ```
 *
 * The FTS row's `rowid` is the `captured_traffic.id` (set explicitly by every insert), so per-row
 * delete/append is an O(1) rowid lookup; `traffic_id` carries the same value for joins.
 *
 * The index is kept in sync by three SQL triggers on `captured_traffic` (`AFTER INSERT`,
 * `AFTER UPDATE OF` the searchable columns, `AFTER DELETE`), so every writer -- the persistence
 * queue, the decoded-exchange patch, HAR import, session pruning -- keeps it correct without
 * knowing it exists. `headers_text` is the request + response header JSON (names and values are
 * tokenized out of the JSON by unicode61); `body_text` is the inline text bodies or, for a spilled
 * body, its stored preview. Binary bodies contribute nothing (their `*Body` text column is the
 * empty placeholder), so a binary payload is findable by URL/host/headers, never by content.
 * [FtsIndexer] appends WebSocket / SSE / gRPC child payload text to `body_text` so a connection is
 * findable by the content of one of its messages.
 *
 * ### Why this is not a Room `@Fts4` entity and not created by the migration alone
 *
 * FTS5 needs the bundled SQLite driver (S0 task 3). `RequestrDatabase.buildDatabase` opens through
 * the *framework* driver whenever a migration has to run (Room only runs `Migration.migrate` on the
 * support path) and through the bundled driver otherwise, and Android's framework SQLite is not
 * guaranteed to ship FTS5 (`no such module: fts5`). So:
 *
 *  - `MIGRATION_21_22` calls [ensure] best-effort: it succeeds where FTS5 exists and is a logged
 *    no-op where it does not. Nothing about the migration depends on it.
 *  - [roomCallback] calls [ensure] on every open (`CREATE ... IF NOT EXISTS`, cheap when present),
 *    so a fresh install (which never runs migrations) and the first bundled-driver open after a
 *    framework-driver migration both get the index, and it is populated eagerly from the existing
 *    rows the first time it is created.
 *  - [available] records the outcome for this process; [TrafficSearchCompiler] compiles free text
 *    to `MATCH` when true and to `LIKE` predicates otherwise, so search works either way.
 */
object FtsSchema {
    private const val TAG = "FtsSchema"
    const val TABLE = "traffic_fts"

    /** Whether `traffic_fts` exists on the currently open database (per process; see class doc). */
    val available = AtomicBoolean(false)

    /** Text the triggers derive from a `captured_traffic` row, as SQL expressions over alias [r]. */
    private fun urlExpr(r: String) = "$r.url"
    private fun hostExpr(r: String) = "$r.host"
    private fun headersExpr(r: String) = "$r.requestHeaders || ' ' || $r.responseHeaders"
    private fun bodyExpr(r: String) =
        "COALESCE($r.requestBody, $r.requestBodyPreview, '') || ' ' || COALESCE($r.responseBody, $r.responseBodyPreview, '')"

    private val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(" +
            "traffic_id UNINDEXED, url, host, headers_text, body_text, tokenize='unicode61')"

    private val TRIGGERS = listOf(
        "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ai AFTER INSERT ON captured_traffic BEGIN " +
            "INSERT INTO $TABLE(rowid, traffic_id, url, host, headers_text, body_text) " +
            "VALUES (new.id, new.id, ${urlExpr("new")}, ${hostExpr("new")}, ${headersExpr("new")}, ${bodyExpr("new")}); END",
        "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ad AFTER DELETE ON captured_traffic BEGIN " +
            "DELETE FROM $TABLE WHERE rowid = old.id; END",
        "CREATE TRIGGER IF NOT EXISTS ${TABLE}_au AFTER UPDATE OF url, host, requestHeaders, responseHeaders, " +
            "requestBody, responseBody, requestBodyPreview, responseBodyPreview ON captured_traffic BEGIN " +
            "DELETE FROM $TABLE WHERE rowid = old.id; " +
            "INSERT INTO $TABLE(rowid, traffic_id, url, host, headers_text, body_text) " +
            "VALUES (new.id, new.id, ${urlExpr("new")}, ${hostExpr("new")}, ${headersExpr("new")}, ${bodyExpr("new")}); END",
    )

    /** Eager population over existing rows; children text is folded in by [FtsIndexer.populateChildren]. */
    private val POPULATE =
        "INSERT INTO $TABLE(rowid, traffic_id, url, host, headers_text, body_text) " +
            "SELECT t.id, t.id, ${urlExpr("t")}, ${hostExpr("t")}, ${headersExpr("t")}, ${bodyExpr("t")} FROM captured_traffic t"

    private const val EXISTS = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$TABLE'"

    /** Minimal SQL surface shared by the support-database and driver-connection entry points. */
    internal interface Sql {
        fun exec(sql: String)
        fun scalar(sql: String): Long
    }

    internal fun sql(db: SupportSQLiteDatabase): Sql = object : Sql {
        override fun exec(sql: String) = db.execSQL(sql)
        override fun scalar(sql: String): Long = db.query(sql).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    internal fun sql(connection: SQLiteConnection): Sql = object : Sql {
        override fun exec(sql: String) = connection.execSQL(sql)
        override fun scalar(sql: String): Long {
            val s = connection.prepare(sql)
            try { return if (s.step()) s.getLong(0) else 0L } finally { s.close() }
        }
    }

    /**
     * Creates the index and its triggers if missing and populates it from the existing rows when it
     * was just created. Returns true when `traffic_fts` exists afterwards. Never throws: a runtime
     * without FTS5 leaves the database exactly as it was and search falls back to `LIKE`.
     */
    fun ensure(db: SupportSQLiteDatabase): Boolean = ensure(sql(db))
    fun ensure(connection: SQLiteConnection): Boolean = ensure(sql(connection))

    internal fun ensure(sql: Sql): Boolean {
        val existed = runCatching { sql.scalar(EXISTS) > 0 }.getOrDefault(false)
        val ok = runCatching {
            sql.exec(CREATE_TABLE)
            TRIGGERS.forEach(sql::exec)
            if (!existed) {
                sql.exec("DELETE FROM $TABLE")
                sql.exec(POPULATE)
                FtsIndexer.populateChildren(sql)
            }
            true
        }.getOrElse { t ->
            Log.w(TAG, "FTS5 index unavailable on this SQLite (${t.message}); search falls back to LIKE")
            false
        }
        available.set(ok)
        return ok
    }

    /** Drops the index and triggers (tests, and the documented rollback for the `ftsSearch` flag). */
    internal fun drop(sql: Sql) {
        listOf("${TABLE}_ai", "${TABLE}_ad", "${TABLE}_au").forEach { runCatching { sql.exec("DROP TRIGGER IF EXISTS $it") } }
        runCatching { sql.exec("DROP TABLE IF EXISTS $TABLE") }
        available.set(false)
    }

    /**
     * Room callback that runs [ensure] on open in both driver modes (Room calls the
     * `SupportSQLiteDatabase` overloads on the framework path and the `SQLiteConnection` overloads
     * on the bundled-driver path). Added by `RequestrDatabase.buildDatabase`; tests add it explicitly.
     */
    val roomCallback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) { ensure(db) }
        override fun onOpen(connection: SQLiteConnection) { ensure(connection) }
    }
}
