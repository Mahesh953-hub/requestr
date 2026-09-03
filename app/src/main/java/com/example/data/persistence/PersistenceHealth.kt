package com.example.data.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * S2 tasks 15/17 -- the persistence failure table from the interceptor plan §4, as observable
 * state instead of scattered `Log.w` lines.
 *
 * Every row in the table below has a counter or a state here, is set by exactly one component,
 * and is read by the Intercept screen / notification so data loss is *surfaced*, never hidden:
 *
 * | Failure                       | Recorded by                    | Field                                   |
 * |-------------------------------|--------------------------------|-----------------------------------------|
 * | queue saturation (drop-oldest)| [PersistenceQueue]             | [Snapshot.droppedWrites], [Snapshot.saturated] |
 * | DAO exception                 | [PersistenceQueue]             | [Snapshot.failedWrites], [Snapshot.lastFailure] |
 * | patch for a row that was never written | [PersistenceQueue]    | [Snapshot.orphanedPatches]              |
 * | body spill failure            | `BodyStore` (task 16)          | [Snapshot.spillFailures]                |
 * | spill orphan sweep            | `BodyStore` (task 16)          | [Snapshot.orphanedSpillFilesRemoved]    |
 * | migration backup / restore    | `DbBackup` / builder (task 17) | [Snapshot.migration]                    |
 * | FTS index failure             | S3 (reserved)                  | [Snapshot.ftsFailures]                  |
 * | timing underflow (negative phase) | task 18                    | [Snapshot.timingUnderflows]             |
 * | quota reached                 | `SessionPruner` (task 19)      | [Snapshot.capturePaused], [Snapshot.prunedTransactions] |
 *
 * This is a process-wide `object` for the same reason
 * [com.example.data.interception.InterceptionStateRepository] is: the writers live in the VPN
 * service, the gateway, the local API server and importers, none of which share a DI graph, and
 * the reader is Compose. [InterceptionStateRepository.persistence] re-exports [state] so UI code
 * keeps a single entry point.
 */
object PersistenceHealth {

    /**
     * Task 17: what happened to the database at this process's open, most severe first when
     * several apply. [BACKED_UP] is informational; [RESTORED_FROM_BACKUP] means a downgrade wipe
     * was undone; [BACKUP_FAILED] means the migration ran without a safety copy;
     * [DESTRUCTIVE_WIPE] means rows were lost; [MIGRATION_FAILED] means the schema upgrade threw
     * and the file is still on the previous version.
     */
    enum class MigrationEvent { NONE, BACKED_UP, RESTORED_FROM_BACKUP, BACKUP_FAILED, DESTRUCTIVE_WIPE, MIGRATION_FAILED }

    enum class PauseReason { QUOTA_TRANSACTIONS, QUOTA_DB_BYTES, QUOTA_SPILL_BYTES, STORAGE_FULL }

    data class Snapshot(
        /** Ops currently buffered in the queue (best-effort, sampled after each batch). */
        val queueDepth: Int = 0,
        /** Ops the actor has applied successfully since process start. */
        val committedWrites: Long = 0,
        /** Ops evicted by drop-oldest because the channel was full. */
        val droppedWrites: Long = 0,
        /** Ops whose DAO call threw; the op was discarded, the row it targeted is untouched. */
        val failedWrites: Long = 0,
        /** Response/timing/header patches whose parent insert was dropped or failed. */
        val orphanedPatches: Long = 0,
        /** True while a drop happened within the last [PersistenceQueue.SATURATION_WINDOW_MS]. */
        val saturated: Boolean = false,
        val lastDropAt: Long? = null,
        /** `"<op label>: <exception message>"` of the most recent DAO failure. */
        val lastFailure: String? = null,
        val lastFailureAt: Long? = null,
        val spillFailures: Long = 0,
        val orphanedSpillFilesRemoved: Long = 0,
        val migration: MigrationEvent = MigrationEvent.NONE,
        val migrationDetail: String? = null,
        val ftsFailures: Long = 0,
        val timingUnderflows: Long = 0,
        val prunedTransactions: Long = 0,
        val capturePaused: PauseReason? = null,
        val capturePausedDetail: String? = null,
        /** Transactions refused at the producer because capture was paused on a quota. */
        val quotaRejectedWrites: Long = 0,
    ) {
        /** Anything a user should be told about, in one line; null when everything is healthy. */
        val headline: String?
            get() = when {
                capturePaused != null -> "Capture paused: ${capturePausedDetail ?: capturePaused.name.lowercase()}"
                saturated -> "Persistence saturated: $droppedWrites write${if (droppedWrites == 1L) "" else "s"} dropped"
                failedWrites > 0 -> "$failedWrites write${if (failedWrites == 1L) "" else "s"} failed: ${lastFailure ?: "unknown"}"
                spillFailures > 0 -> "$spillFailures large bod${if (spillFailures == 1L) "y" else "ies"} could not be saved to disk"
                migration == MigrationEvent.MIGRATION_FAILED -> "Database upgrade failed${migrationDetail?.let { ": $it" } ?: ""} -- still on the previous version"
                migration == MigrationEvent.DESTRUCTIVE_WIPE -> "Database was reset${migrationDetail?.let { " ($it)" } ?: ""}"
                migration == MigrationEvent.RESTORED_FROM_BACKUP -> "Database restored from backup${migrationDetail?.let { " ($it)" } ?: ""}"
                migration == MigrationEvent.BACKUP_FAILED -> "Pre-upgrade backup failed${migrationDetail?.let { " ($it)" } ?: ""} -- upgrade ran without a safety copy"
                else -> null
            }

        val hasDataLoss: Boolean
            get() = droppedWrites > 0 || failedWrites > 0 || spillFailures > 0 ||
                migration == MigrationEvent.DESTRUCTIVE_WIPE || migration == MigrationEvent.MIGRATION_FAILED
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun snapshot(): Snapshot = _state.value

    fun update(transform: (Snapshot) -> Snapshot) = _state.update(transform)

    // -- queue ---------------------------------------------------------------------------------

    fun recordCommitted(count: Int, depth: Int) = update {
        it.copy(committedWrites = it.committedWrites + count, queueDepth = depth)
    }

    fun recordDropped(count: Int, now: Long) = update {
        it.copy(droppedWrites = it.droppedWrites + count, saturated = true, lastDropAt = now)
    }

    fun recordFailed(label: String, message: String?, now: Long) = update {
        it.copy(
            failedWrites = it.failedWrites + 1,
            lastFailure = "$label: ${message ?: "unknown error"}".take(200),
            lastFailureAt = now,
        )
    }

    fun recordOrphanedPatch() = update { it.copy(orphanedPatches = it.orphanedPatches + 1) }

    fun clearSaturation() = update { if (it.saturated) it.copy(saturated = false) else it }

    // -- spill (task 16) -----------------------------------------------------------------------

    fun recordSpillFailure() = update { it.copy(spillFailures = it.spillFailures + 1) }

    fun recordOrphanSweep(removed: Int) = update {
        it.copy(orphanedSpillFilesRemoved = it.orphanedSpillFilesRemoved + removed)
    }

    // -- migration (task 17) -------------------------------------------------------------------

    /**
     * Records a migration/recovery event. Severity is monotonic within a process: a later,
     * less severe event (e.g. BACKED_UP after RESTORED_FROM_BACKUP in the same open) never hides
     * a more severe one; [MigrationEvent.NONE] always resets.
     */
    fun recordMigration(event: MigrationEvent, detail: String? = null) = update {
        if (event != MigrationEvent.NONE && event.ordinal < it.migration.ordinal) it
        else it.copy(migration = event, migrationDetail = detail)
    }

    // -- S3 / task 18 --------------------------------------------------------------------------

    fun recordFtsFailure() = update { it.copy(ftsFailures = it.ftsFailures + 1) }

    fun recordTimingUnderflow() = update { it.copy(timingUnderflows = it.timingUnderflows + 1) }

    // -- quota (task 19) -----------------------------------------------------------------------

    fun recordPruned(count: Int) = update { it.copy(prunedTransactions = it.prunedTransactions + count) }

    fun recordQuotaRejected() = update { it.copy(quotaRejectedWrites = it.quotaRejectedWrites + 1) }

    fun pauseCapture(reason: PauseReason, detail: String?) = update {
        it.copy(capturePaused = reason, capturePausedDetail = detail)
    }

    fun resumeCapture() = update {
        if (it.capturePaused == null) it else it.copy(capturePaused = null, capturePausedDetail = null)
    }

    /** Test/reset hook: back to a pristine snapshot. */
    fun reset() {
        _state.value = Snapshot()
    }
}
