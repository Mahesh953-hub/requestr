package com.example.data.persistence

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.example.data.local.RequestrDatabase
import com.example.data.local.SpilledBodyRef

/**
 * S2 task 19: session pruning and quotas.
 *
 * Runs on the persistence actor after every committed batch (registered through
 * [PersistenceQueue.addBatchListener]) and enforces four caps, in this order:
 *
 * 1. **Per-session transaction cap** ([Config.maxTransactionsPerSession], default 5,000): each
 *    session the batch touched is trimmed oldest-first (capture `timestamp`, then id) so a
 *    long-running capture keeps a bounded, most-recent window.
 * 2. **Session age cap** ([Config.maxSessionAgeMs]): ended sessions whose `endedAt` is older than
 *    the cap are deleted whole.
 * 3. **Spill-directory size cap** ([Config.maxSpillBytes]): while the [BodyStore] is over the cap,
 *    the oldest *ended* session is deleted whole.
 * 4. **Global database ceiling** ([Config.maxDbBytes], measured as used pages, so it falls as rows
 *    are deleted without a VACUUM): same eviction as (3).
 *
 * A deletion is one IMMEDIATE transaction per session on the writer connection: the
 * `captured_traffic` rows go first and `ON DELETE CASCADE` takes `transaction_headers`,
 * `transaction_timings` and `applied_rules` (provenance) with them; the protocol children
 * (`tcp_connections`, `udp_flows`, `quic_connections`, `tls_sessions`, `dns_queries`,
 * `connect_attempts`) are cleared by `sessionId`; the `capture_sessions` row goes last. FTS rows
 * arrive in S3 and hang off `captured_traffic` too. Spilled body files are deleted *after* the
 * transaction commits -- a file whose row is gone is exactly what the startup orphan sweep
 * ([BodyStore.sweepOrphans]) already handles, whereas a row whose file is gone would render as
 * "not stored", so the order is chosen so a crash between the two steps is self-healing.
 *
 * **Quota exhaustion.** When (3) or (4) is still over the cap with no ended session left to evict
 * -- the live session alone exceeds the ceiling -- the pruner raises
 * [PersistenceHealth.pauseCapture] with [PersistenceHealth.PauseReason.QUOTA_SPILL_BYTES] /
 * [PersistenceHealth.PauseReason.QUOTA_DB_BYTES]. [PersistenceQueue.insertTransaction] then rejects
 * new transactions (counted in [PersistenceHealth.Snapshot.quotaRejectedWrites], visible through
 * the health banner and the VPN status line's "capture paused: quota") until a later evaluation
 * finds the store back under its caps, which clears the pause. Pausing rather than silently
 * dropping is the §4 failure-table contract; pruning the live session harder would instead make
 * the capture the user is looking at disappear underneath them.
 */
class SessionPruner internal constructor(
    private val db: RequestrDatabase,
    private val bodyStore: () -> BodyStore?,
    val config: Config = Config(),
    private val health: PersistenceHealth = PersistenceHealth,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dbBytes: (suspend () -> Long)? = null,
) {

    data class Config(
        val maxTransactionsPerSession: Int = DEFAULT_MAX_TRANSACTIONS_PER_SESSION,
        val maxSessionAgeMs: Long = DEFAULT_MAX_SESSION_AGE_MS,
        val maxSpillBytes: Long = DEFAULT_MAX_SPILL_BYTES,
        val maxDbBytes: Long = DEFAULT_MAX_DB_BYTES,
    ) {
        init {
            require(maxTransactionsPerSession > 0) { "maxTransactionsPerSession must be positive" }
            require(maxSessionAgeMs > 0) { "maxSessionAgeMs must be positive" }
            require(maxSpillBytes > 0) { "maxSpillBytes must be positive" }
            require(maxDbBytes > 0) { "maxDbBytes must be positive" }
        }
    }

    /** What one evaluation did. */
    data class Result(
        val prunedTransactions: Int = 0,
        val prunedSessions: Int = 0,
        val freedSpillBytes: Long = 0,
        /** Non-null when capture is (still) paused after this evaluation. */
        val paused: PersistenceHealth.PauseReason? = null,
    ) {
        val isEmpty: Boolean get() = prunedTransactions == 0 && prunedSessions == 0 && paused == null
    }

    /** The hook handed to [PersistenceQueue.addBatchListener]. */
    val batchListener: suspend (PersistenceQueue.Batch) -> Unit = { batch ->
        if (batch.committed > 0) enforce(batch.touchedSessionIds)
    }

    /**
     * Evaluates every cap. [touchedSessions] are checked against the per-session cap; the
     * store-wide caps are always checked. Safe to call from any coroutine, but production calls
     * come from the persistence actor so pruning never races a live insert.
     */
    suspend fun enforce(touchedSessions: Set<Long> = emptySet()): Result {
        var pruned = 0
        var sessions = 0
        var freed = 0L

        // 1. per-session transaction cap
        for (sessionId in touchedSessions) {
            val r = capSession(sessionId)
            pruned += r.prunedTransactions
            freed += r.freedSpillBytes
        }

        // 2. session age cap
        val cutoff = clock() - config.maxSessionAgeMs
        for (session in db.captureSessionDao().getEndedOldestFirst()) {
            val endedAt = session.endedAt ?: continue
            if (endedAt > cutoff) continue
            val r = deleteSession(session.id)
            pruned += r.prunedTransactions; sessions += r.prunedSessions; freed += r.freedSpillBytes
        }

        // 3 + 4. store-wide ceilings, evicting whole ended sessions oldest-first
        var paused: PersistenceHealth.PauseReason? = null
        var spillDetail: String? = null
        var dbDetail: String? = null
        while (true) {
            val spill = bodyStore()?.totalBytes() ?: 0L
            val used = usedDbBytes()
            val overSpill = spill > config.maxSpillBytes
            val overDb = used > config.maxDbBytes
            if (!overSpill && !overDb) break
            val victim = db.captureSessionDao().getEndedOldestFirst().firstOrNull()
            if (victim == null) {
                if (overDb) {
                    paused = PersistenceHealth.PauseReason.QUOTA_DB_BYTES
                    dbDetail = "database ${mib(used)} MiB over the ${mib(config.maxDbBytes)} MiB limit"
                }
                if (overSpill && paused == null) {
                    paused = PersistenceHealth.PauseReason.QUOTA_SPILL_BYTES
                    spillDetail = "saved bodies ${mib(spill)} MiB over the ${mib(config.maxSpillBytes)} MiB limit"
                }
                break
            }
            val r = deleteSession(victim.id)
            pruned += r.prunedTransactions; sessions += r.prunedSessions; freed += r.freedSpillBytes
        }

        if (pruned > 0) health.recordPruned(pruned)
        if (pruned > 0 || sessions > 0) {
            Log.i(TAG, "pruned $pruned transaction(s), $sessions whole session(s), freed $freed B of spilled bodies")
        }

        val before = health.snapshot().capturePaused
        if (paused != null) {
            val detail = dbDetail ?: spillDetail
            health.pauseCapture(paused, detail)
            if (before != paused) Log.w(TAG, "capture paused: quota -- $detail")
        } else if (before == PersistenceHealth.PauseReason.QUOTA_DB_BYTES ||
            before == PersistenceHealth.PauseReason.QUOTA_SPILL_BYTES
        ) {
            health.resumeCapture()
            Log.i(TAG, "capture resumed: store back under its quotas")
        }
        return Result(pruned, sessions, freed, paused)
    }

    /** Trims [sessionId] to [Config.maxTransactionsPerSession] rows, oldest first. */
    internal suspend fun capSession(sessionId: Long): Result {
        val dao = db.capturedTrafficDao()
        val excess = dao.countForSession(sessionId) - config.maxTransactionsPerSession
        if (excess <= 0) return Result()
        val victims: List<SpilledBodyRef> = dao.getOldestRefsForSession(sessionId, excess)
        if (victims.isEmpty()) return Result()
        var deleted = 0
        db.write {
            for (chunk in victims.chunked(SQL_IN_CHUNK)) {
                deleted += dao.deleteByIds(chunk.map { it.id })
            }
        }
        val freed = deleteSpilledFiles(victims)
        return Result(prunedTransactions = deleted, freedSpillBytes = freed)
    }

    /**
     * Deletes one whole session -- transactions (cascading to headers, timings, applied rules),
     * protocol children, connect attempts and the session row -- in one transaction, then its
     * spill directory.
     */
    internal suspend fun deleteSession(sessionId: Long): Result {
        var deleted = 0
        db.write {
            deleted = db.capturedTrafficDao().clearSession(sessionId)
            db.tcpConnectionDao().clearSession(sessionId)
            db.udpFlowDao().clearSession(sessionId)
            db.quicConnectionDao().clearSession(sessionId)
            db.tlsSessionDao().clearSession(sessionId)
            db.dnsQueryDao().clearSession(sessionId)
            db.connectAttemptDao().clearSession(sessionId)
            db.captureSessionDao().deleteById(sessionId)
        }
        val freed = bodyStore()?.deleteSession(sessionId) ?: 0L
        return Result(prunedTransactions = deleted, prunedSessions = 1, freedSpillBytes = freed)
    }

    private fun deleteSpilledFiles(refs: List<SpilledBodyRef>): Long {
        val store = bodyStore() ?: return 0L
        var freed = 0L
        for (ref in refs) {
            for (path in listOfNotNull(ref.requestBodyFilePath, ref.responseBodyFilePath)) {
                val size = store.file(path)?.length() ?: 0L
                if (store.deleteBody(path)) freed += size
            }
        }
        return freed
    }

    /**
     * Bytes the database actually uses: `(page_count - freelist_count) * page_size`. Unlike the
     * file length this drops as rows are deleted, so a prune can bring the store back under the
     * ceiling without a VACUUM.
     */
    internal suspend fun usedDbBytes(): Long {
        dbBytes?.let { return it() }
        return db.useReaderConnection { connection ->
            val pageSize = connection.usePrepared("PRAGMA page_size") { it.step(); it.getLong(0) }
            val pages = connection.usePrepared("PRAGMA page_count") { it.step(); it.getLong(0) }
            val free = connection.usePrepared("PRAGMA freelist_count") { it.step(); it.getLong(0) }
            (pages - free).coerceAtLeast(0L) * pageSize
        }
    }

    private suspend fun <R> RequestrDatabase.write(block: suspend () -> R): R =
        useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

    private fun mib(bytes: Long): Long = bytes / (1024L * 1024L)

    companion object {
        private const val TAG = "SessionPruner"

        const val DEFAULT_MAX_TRANSACTIONS_PER_SESSION: Int = 5_000
        const val DEFAULT_MAX_SESSION_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_SPILL_BYTES: Long = 512L * 1024 * 1024
        const val DEFAULT_MAX_DB_BYTES: Long = 1024L * 1024 * 1024

        /** Well under SQLite's default 999 bound-parameter limit. */
        private const val SQL_IN_CHUNK = 500
    }
}
