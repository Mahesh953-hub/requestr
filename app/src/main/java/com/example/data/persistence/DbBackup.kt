package com.example.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pre-migration database backup with explicit metadata, WAL-safe snapshots, and
 * downgrade-safe restore.
 *
 * ## Lifecycle (called from [com.example.data.local.RequestrDatabase.Companion])
 *
 * 1. Before Room opens, [backupBeforeMigration] checkpoints the WAL, then copies the
 *    main database file (only — no `-shm`) to `databases/requestr-pre-v<N>.bak` and
 *    writes a JSON metadata sidecar.  If the WAL checkpoint fails, the backup is
 *    aborted to avoid a stale/corrupt snapshot.
 *
 * 2. If Room's `fallbackToDestructiveMigrationOnDowngrade` fires (APK rollback),
 *    [recordDestructiveWipe] stamps the metadata so it is never silent.
 *
 * 3. On the next launch at a schema version ≥ the backup version **and** with a
 *    minimum schema version of [MINIMUM_RESTORABLE_VERSION],
 *    [shouldRestoreBeforeOpen] returns `true`; the caller calls [restoreBackup] before
 *    Room opens, so Room sees the restored file and migrates forward normally.
 *
 * 4. After a distinct **second** `buildDatabase` call where no migration or destructive
 *    fallback runs, [markCleanLaunch] deletes the backup and metadata.  This is tracked
 *    via an incrementing [KEY_OPEN_GENERATION] that `buildDatabase` bumps on each
 *    invocation and `markCleanLaunch` compares against its last-seen value, so multiple
 *    `onOpen` callbacks within one Room open sequence cannot double-count.
 *
 * ## Failure semantics
 *
 * Every I/O operation catches and logs rather than throwing, so a corrupt or missing
 * backup never prevents the app from opening.  The Room builder's
 * `fallbackToDestructiveMigrationOnDowngrade` remains the non-bricking last resort
 * when restore is not viable.
 *
 * ## Threading
 *
 * All public methods perform file I/O.  [com.example.data.local.RequestrDatabase.get]
 * enforces an off-main-thread invariant via [requireBackgroundThread]; calling from the
 * main thread throws [IllegalStateException].
 */
class DbBackup(private val context: Context) {

    companion object {
        private const val TAG = "DbBackup"
        const val DB_NAME = "requestr.db"
        private const val METADATA_FILENAME = "requestr-backup-metadata.json"

        /**
         * Versions 1–7 have no exported schema and are destructively recreated by
         * `fallbackToDestructiveMigrationFrom`.  There is no useful data to restore
         * from those versions, so we never attempt backup or restore for them.
         */
        const val MINIMUM_RESTORABLE_VERSION = 8

        // Metadata JSON keys
        private const val KEY_BACKUP_VERSION = "backupVersion"
        private const val KEY_BACKUP_TIMESTAMP = "backupTimestamp"
        private const val KEY_BACKUP_PATH = "backupPath"
        private const val KEY_DESTRUCTIVE_WIPE = "destructiveWipeDetected"
        private const val KEY_WIPE_FROM_VERSION = "wipeFromVersion"
        private const val KEY_WIPE_TO_VERSION = "wipeToVersion"
        private const val KEY_WIPE_TIMESTAMP = "wipeTimestamp"
        private const val KEY_OPEN_GENERATION = "openGeneration"
        private const val KEY_LAST_CLEAN_GENERATION = "lastCleanGeneration"
        private const val KEY_RECOVERY_EVENTS = "recoveryEvents"

        /** Diagnostics rows kept in metadata (task 17); oldest are dropped past this. */
        const val MAX_RECOVERY_EVENTS = 20

        /** Number of distinct `buildDatabase` generations with clean opens required. */
        private const val STABLE_GENERATION_THRESHOLD = 2
    }

    private val databaseDir: File
        get() = context.getDatabasePath(DB_NAME).parentFile
            ?: File(context.filesDir, "databases")

    private val metadataFile: File get() = File(databaseDir, METADATA_FILENAME)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads the SQLite `user_version` via `PRAGMA user_version` using the Android
     * framework `SQLiteDatabase`, which reads through WAL correctly.
     *
     * Returns 0 if the file does not exist or cannot be opened.
     */
    fun readDatabaseVersion(dbFile: File): Int {
        if (!dbFile.exists() || dbFile.length() < 100) return 0
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read database version from ${dbFile.name}", e)
            0
        }
    }

    /**
     * Creates a WAL-checkpointed copy of the live database.
     *
     * If the WAL checkpoint fails (locked DB, corrupt WAL, etc.), the backup is
     * **aborted** rather than copying a potentially stale main file.  This is a
     * deliberate safety choice: a stale backup that silently reverts committed data
     * is worse than no backup at all; the destructive-downgrade fallback is the
     * safety net.
     *
     * @return `true` on success, `false` if checkpoint or copy failed.
     */
    fun backupBeforeMigration(currentVersion: Int): Boolean {
        if (currentVersion < MINIMUM_RESTORABLE_VERSION) return false

        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return false

        val backupFile = backupFileForVersion(currentVersion)
        return try {
            // Checkpoint WAL into the main file so the copy is self-contained.
            // If this throws, the whole backup is aborted.
            checkpointWal(dbFile)

            databaseDir.mkdirs()
            dbFile.copyTo(backupFile, overwrite = true)

            val previousEvents = readMetadata()?.optJSONArray(KEY_RECOVERY_EVENTS)
            writeMetadata(
                JSONObject().apply {
                    if (previousEvents != null) put(KEY_RECOVERY_EVENTS, previousEvents)
                    put(KEY_BACKUP_VERSION, currentVersion)
                    put(KEY_BACKUP_TIMESTAMP, System.currentTimeMillis())
                    put(KEY_BACKUP_PATH, backupFile.absolutePath)
                    put(KEY_DESTRUCTIVE_WIPE, false)
                    put(KEY_OPEN_GENERATION, 0)
                    put(KEY_LAST_CLEAN_GENERATION, -1)
                }
            )
            Log.i(TAG, "Backed up database v$currentVersion → ${backupFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed for v$currentVersion", e)
            backupFile.delete()
            false
        }
    }

    /**
     * Records that Room performed a destructive migration (typically a downgrade wipe).
     * Called from `RoomDatabase.Callback.onDestructiveMigration`.
     */
    fun recordDestructiveWipe(fromVersion: Int, toVersion: Int) {
        try {
            val metadata = readMetadata() ?: JSONObject()
            metadata.put(KEY_DESTRUCTIVE_WIPE, true)
            metadata.put(KEY_WIPE_FROM_VERSION, fromVersion)
            metadata.put(KEY_WIPE_TO_VERSION, toVersion)
            metadata.put(KEY_WIPE_TIMESTAMP, System.currentTimeMillis())
            metadata.put(KEY_LAST_CLEAN_GENERATION, -1)
            writeMetadata(metadata)
            Log.w(
                TAG,
                "Destructive migration recorded: v$fromVersion → v$toVersion (data wiped). " +
                    "Backup preserved for restore on re-upgrade."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record destructive wipe event", e)
        }
    }

    /**
     * Returns `true` when all of the following hold:
     * - A destructive wipe was recorded.
     * - The backup file exists.
     * - The backup version is ≥ [MINIMUM_RESTORABLE_VERSION].
     * - The backup version ≤ [appSchemaVersion] (Room can migrate forward).
     * - The backup version > the current on-disk DB version (the backup is newer
     *   than the wiped/empty database, preventing pointless restores).
     */
    fun shouldRestoreBeforeOpen(appSchemaVersion: Int): Boolean {
        val metadata = readMetadata() ?: return false
        if (!metadata.optBoolean(KEY_DESTRUCTIVE_WIPE, false)) return false

        val backupVersion = metadata.optInt(KEY_BACKUP_VERSION, 0)
        if (backupVersion < MINIMUM_RESTORABLE_VERSION) return false
        if (backupVersion > appSchemaVersion) return false

        val backupPath = metadata.optString(KEY_BACKUP_PATH, "")
        if (backupPath.isEmpty() || !File(backupPath).exists()) return false

        val dbFile = context.getDatabasePath(DB_NAME)
        val currentVersion = if (dbFile.exists()) readDatabaseVersion(dbFile) else 0
        return backupVersion > currentVersion
    }

    /**
     * Restores the backup file over the live database.  The caller MUST ensure Room
     * has **not** opened the database yet (or has closed it) before calling this.
     *
     * Only the main file is restored — any stale `-wal`/`-shm` from the wiped DB
     * are deleted.
     */
    fun restoreBackup(): Boolean {
        val metadata = readMetadata() ?: return false
        val backupPath = metadata.optString(KEY_BACKUP_PATH, "")
        if (backupPath.isEmpty()) return false

        val backupFile = File(backupPath)
        if (!backupFile.exists()) {
            Log.w(TAG, "Backup file not found at $backupPath — cannot restore")
            return false
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        return try {
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            backupFile.copyTo(dbFile, overwrite = true)

            metadata.put(KEY_DESTRUCTIVE_WIPE, false)
            metadata.put(KEY_LAST_CLEAN_GENERATION, -1)
            writeMetadata(metadata)

            val restoredVersion = metadata.optInt(KEY_BACKUP_VERSION, -1)
            Log.i(TAG, "Restored database from backup v$restoredVersion")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Restore failed — app will use the empty (wiped) database", e)
            false
        }
    }

    /**
     * Bumps the open generation in metadata.  Call once per `buildDatabase`
     * invocation, before Room opens.  Returns the new generation number.
     */
    fun bumpOpenGeneration(): Int {
        val metadata = readMetadata() ?: return 0
        val gen = metadata.optInt(KEY_OPEN_GENERATION, 0) + 1
        metadata.put(KEY_OPEN_GENERATION, gen)
        writeMetadata(metadata)
        return gen
    }

    /**
     * Called from `onOpen`.  Compares the current open generation against the
     * last generation that produced a clean open.  If they differ (this is a new
     * `buildDatabase` invocation), records it.  Once [STABLE_GENERATION_THRESHOLD]
     * distinct clean generations have been recorded, deletes the backup.
     *
     * This prevents premature cleanup when Room fires multiple `onOpen` callbacks
     * within a single database open (same process generation).
     */
    fun markCleanLaunch() {
        val metadata = readMetadata() ?: return
        if (metadata.optBoolean(KEY_DESTRUCTIVE_WIPE, false)) return

        val currentGen = metadata.optInt(KEY_OPEN_GENERATION, 0)
        val lastCleanGen = metadata.optInt(KEY_LAST_CLEAN_GENERATION, -1)

        // Same generation as last clean call → no-op (same open sequence)
        if (currentGen == lastCleanGen) return

        metadata.put(KEY_LAST_CLEAN_GENERATION, currentGen)

        // Count how many distinct generations have cleanly opened.
        // Generation counting: if we've bumped at least STABLE_GENERATION_THRESHOLD
        // times without a wipe, we're stable.
        if (currentGen >= STABLE_GENERATION_THRESHOLD) {
            val backupPath = metadata.optString(KEY_BACKUP_PATH, "")
            if (backupPath.isNotEmpty()) File(backupPath).delete()
            // Task 17: the diagnostics rows outlive the backup they describe.
            val events = metadata.optJSONArray(KEY_RECOVERY_EVENTS)
            if (events != null && events.length() > 0) {
                writeMetadata(JSONObject().put(KEY_RECOVERY_EVENTS, events))
            } else {
                metadataFile.delete()
            }
            Log.d(TAG, "Stable (generation $currentGen) — backup removed")
            return
        }

        writeMetadata(metadata)
    }

    /** One persisted recovery/diagnostics row (task 17). */
    data class RecoveryEvent(val kind: String, val detail: String, val at: Long)

    /**
     * Appends a diagnostics row describing a migration/recovery event (backup taken, backup
     * failed, restored, wiped, migration failed). Survives process restarts, unlike
     * [PersistenceHealth], so the Settings diagnostics surface can show what happened on the
     * last few opens. Never throws.
     */
    fun recordRecoveryEvent(kind: String, detail: String, at: Long = System.currentTimeMillis()) {
        try {
            val metadata = readMetadata() ?: JSONObject()
            val events = metadata.optJSONArray(KEY_RECOVERY_EVENTS) ?: JSONArray()
            events.put(JSONObject().put("kind", kind).put("detail", detail).put("at", at))
            while (events.length() > MAX_RECOVERY_EVENTS) events.remove(0)
            metadata.put(KEY_RECOVERY_EVENTS, events)
            writeMetadata(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record recovery event $kind", e)
        }
    }

    /** Persisted recovery rows, oldest first. */
    fun readRecoveryEvents(): List<RecoveryEvent> {
        val events = readMetadata()?.optJSONArray(KEY_RECOVERY_EVENTS) ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            val o = events.optJSONObject(i) ?: return@mapNotNull null
            RecoveryEvent(o.optString("kind"), o.optString("detail"), o.optLong("at"))
        }
    }

    /**
     * Returns the backup metadata as a read-only snapshot, or `null` if none exists.
     * Exposed for diagnostics and tests.
     */
    fun readMetadata(): JSONObject? {
        if (!metadataFile.exists()) return null
        return try {
            JSONObject(metadataFile.readText())
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt backup metadata — ignoring", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read backup metadata", e)
            null
        }
    }

    // ── Internal / test helpers ───────────────────────────────────────────────

    internal fun backupFileForVersion(version: Int): File =
        File(databaseDir, "requestr-pre-v${version}.bak")

    private fun writeMetadata(json: JSONObject) {
        databaseDir.mkdirs()
        metadataFile.writeText(json.toString(2))
    }

    /**
     * Flushes WAL data into the main database file and truncates the WAL to zero
     * bytes.  After this call, the main file is a self-contained snapshot.
     *
     * @throws Exception if the checkpoint fails for any reason (locked, corrupt, etc.).
     *         The caller must abort the backup in that case.
     */
    internal fun checkpointWal(dbFile: File) {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }
}
