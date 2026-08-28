package com.example.interception

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Master plan §2.18 — rooted target-app SQLite database browser.
 *
 * Reference repos (`alice`, `AndroidSnooper`) let a user browse another app's private SQLite
 * databases on a rooted device for debugging. The safety-critical part is never running
 * arbitrary SQL as root against the *live* database file in place — instead:
 *
 *  1. [listDatabases] enumerates `/data/data/<pkg>/databases/` via a single `su -c ls`.
 *  2. [copyDatabaseLocally] copies the target DB's bytes out with `su -c cat <path>`, piped
 *     directly into this app's own [Context.getCacheDir] — the *root* privilege is used only to
 *     read bytes off disk, once, into a location this app already owns. No su-invoked SQL, no
 *     write-back to the target app's storage, no world-readable copy left on the target's
 *     filesystem (the read stays as an in-process pipe from `su`'s stdout to our own file).
 *  3. All actual SQL runs locally, in-process, via [SQLiteDatabase.openDatabase] with
 *     [SQLiteDatabase.OPEN_READONLY] against that local copy — so even a maliciously crafted
 *     database file can't cause a write anywhere, and a crash/OOM only affects this process.
 *  4. [query] additionally rejects anything that isn't a plain `SELECT`/whitelisted read-only
 *     `PRAGMA` via [isSafeReadOnlyQuery] before ever calling [SQLiteDatabase.rawQuery] — table/
 *     column identifiers can't be bound as query parameters, so any identifier that does need to
 *     be interpolated into SQL text is checked with [isSafeSqlIdentifier] first.
 *
 * All validation helpers below are pure functions with no su/root/Android-framework dependency,
 * so they're unit-testable without an emulator (see `TargetAppDatabaseBrowserTest`).
 *
 * Surfacing note: this object is complete and unit-tested but has no production caller yet — a
 * rooted target-app database browser is a discrete UI feature (database picker, table list, query
 * editor, results grid) that belongs in its own slice, not in an engine-fix PR. See the PR
 * description's out-of-scope notes for the closure rationale.
 */
object TargetAppDatabaseBrowser {
    private const val TAG = "TargetDbBrowser"

    data class QueryResult(val columns: List<String>, val rows: List<List<String?>>)

    sealed interface BrowserError {
        data class SuFailed(val message: String) : BrowserError
        data class InvalidInput(val message: String) : BrowserError
        data class UnsafeQuery(val message: String) : BrowserError
    }

    /**
     * Lists non-sidecar files under the target package's `databases/` directory.
     * `-journal`/`-wal`/`-shm` sidecar files are filtered out since they aren't openable
     * databases on their own.
     */
    fun listDatabases(pkg: String): Result<List<String>> {
        if (!isValidPackageName(pkg)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $pkg"))
        }
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /data/data/$pkg/databases/ 2>/dev/null"))
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            if (exit != 0 && text.isBlank()) {
                throw IllegalStateException("su ls exited $exit for $pkg (no root, or package has no databases dir)")
            }
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && isSideCarDbFile(it).not() }
                .toList()
        }
    }

    /**
     * Copies `/data/data/<pkg>/databases/<dbName>` bytes into this app's cache dir using a
     * single `su -c cat` pipe. Returns the local [File] on success; never leaves a copy under
     * the target app's own storage.
     */
    fun copyDatabaseLocally(context: Context, pkg: String, dbName: String): Result<File> {
        if (!isValidPackageName(pkg)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $pkg"))
        }
        if (!isValidDbFileName(dbName)) {
            return Result.failure(IllegalArgumentException("Invalid database file name: $dbName"))
        }
        return runCatching {
            val remotePath = "/data/data/$pkg/databases/$dbName"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$remotePath'"))
            val outFile = File(context.cacheDir, "dbbrowser_${pkg.hashCode()}_${dbName}_${System.currentTimeMillis()}.db")
            proc.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            val exit = proc.waitFor()
            if (exit != 0 || outFile.length() == 0L) {
                outFile.delete()
                throw IllegalStateException("su cat exited $exit or produced an empty file for $remotePath")
            }
            outFile
        }
    }

    /** Best-effort deletion of a local copy produced by [copyDatabaseLocally]. */
    fun cleanup(localFile: File) {
        runCatching { localFile.delete() }.onFailure { Log.w(TAG, "cleanup failed: ${it.message}") }
    }

    /** Lists user tables (excludes sqlite internal tables) in a locally-opened read-only copy. */
    fun listTables(localFile: File): Result<List<String>> = runCatching {
        SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names.add(cursor.getString(0))
                names
            }
        }
    }

    /**
     * Runs a read-only [sql] statement against the local copy. Rejects anything that doesn't
     * pass [isSafeReadOnlyQuery] before ever touching [SQLiteDatabase.rawQuery].
     */
    fun query(localFile: File, sql: String, args: List<String> = emptyList()): Result<QueryResult> {
        if (!isSafeReadOnlyQuery(sql)) {
            return Result.failure(IllegalArgumentException("Rejected non-read-only statement: $sql"))
        }
        return runCatching {
            SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    val columns = cursor.columnNames.toList()
                    val rows = mutableListOf<List<String?>>()
                    while (cursor.moveToNext()) {
                        rows.add((0 until cursor.columnCount).map { idx -> cursor.getString(idx) })
                    }
                    QueryResult(columns, rows)
                }
            }
        }
    }

    /** Convenience: `SELECT * FROM <table> LIMIT <limit>` with the table name identifier-checked. */
    fun browseTable(localFile: File, table: String, limit: Int = 200): Result<QueryResult> {
        if (!isSafeSqlIdentifier(table)) {
            return Result.failure(IllegalArgumentException("Invalid table identifier: $table"))
        }
        val safeLimit = limit.coerceIn(1, 5000)
        return query(localFile, "SELECT * FROM \"$table\" LIMIT $safeLimit")
    }
}

/** `com.example.app`, `com.example.app123` — standard Android package-name shape. */
fun isValidPackageName(pkg: String): Boolean =
    pkg.isNotBlank() && Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$").matches(pkg)

/** No path separators/traversal — a single flat file name, e.g. `app.db`, `cache.db-shm` excluded elsewhere. */
fun isValidDbFileName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && name != "." && name != ".." && Regex("^[\\w.\\-]+$").matches(name)

fun isSideCarDbFile(name: String): Boolean =
    name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")

/** Plain SQL identifier — no quoting/escaping needed, safe to interpolate directly. */
fun isSafeSqlIdentifier(identifier: String): Boolean =
    identifier.isNotBlank() && Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(identifier)

/**
 * Whitelist of statement shapes [TargetAppDatabaseBrowser.query] will execute: a single
 * `SELECT`, or a handful of introspection `PRAGMA`s that are read-only in SQLite (they report
 * schema info; they do not mutate the database). Anything else — `PRAGMA journal_mode=...`,
 * `ATTACH`, `INSERT`/`UPDATE`/`DELETE`/`DROP`, multiple statements chained with `;` — is
 * rejected.
 */
fun isSafeReadOnlyQuery(sql: String): Boolean {
    val trimmed = sql.trim()
    if (trimmed.isEmpty()) return false
    // Reject statement chaining: allow at most one trailing semicolon.
    val withoutTrailingSemicolon = trimmed.removeSuffix(";")
    if (withoutTrailingSemicolon.contains(';')) return false

    val upper = withoutTrailingSemicolon.uppercase()
    if (upper.startsWith("SELECT ") || upper == "SELECT") return true

    val readOnlyPragmas = listOf(
        "PRAGMA TABLE_INFO", "PRAGMA DATABASE_LIST", "PRAGMA INDEX_LIST",
        "PRAGMA INDEX_INFO", "PRAGMA FOREIGN_KEY_LIST", "PRAGMA TABLE_LIST",
        "PRAGMA SCHEMA_VERSION", "PRAGMA USER_VERSION", "PRAGMA INTEGRITY_CHECK",
    )
    return readOnlyPragmas.any { upper.startsWith(it) }
}
