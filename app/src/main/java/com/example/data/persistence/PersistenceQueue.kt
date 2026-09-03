package com.example.data.persistence

import android.content.Context
import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.example.data.local.AppliedRuleEntity
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.DnsQueryEntity
import com.example.data.local.HeaderKind
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown into a [RowHandle] when the insert that would have produced its row id was dropped under
 * saturation, failed in the DAO, or the queue closed before it ran.
 */
class DroppedWriteException(message: String) : RuntimeException(message)

/**
 * The id of a `captured_traffic` row that may not have been written yet.
 *
 * Two-phase capture (request row on parse, response patch on completion) means the producer needs
 * something to patch *before* the actor has assigned the row id. A handle is that something: the
 * producer keeps it, hands it back in the patch op, and the actor -- which processes ops in
 * submission order on one thread -- resolves it before the patch runs. No relay thread ever waits
 * on it; [await] exists for importers and tests only.
 */
class RowHandle private constructor(val label: String, preset: Long?) {
    internal val deferred = CompletableDeferred<Long>()

    init {
        if (preset != null) deferred.complete(preset)
    }

    /** The row id if the insert already committed, else null (still queued, dropped or failed). */
    val idOrNull: Long?
        get() = if (deferred.isCompleted && !deferred.isCancelled) {
            runCatching { deferred.getCompleted() }.getOrNull()
        } else {
            null
        }

    /** True once the insert is known to have NOT produced a row (dropped/failed/closed). */
    val isDead: Boolean get() = deferred.isCompleted && idOrNull == null

    /** Suspends until the row exists; throws [DroppedWriteException] if it never will. */
    suspend fun await(): Long = deferred.await()

    override fun toString(): String = "RowHandle($label, id=${idOrNull ?: if (isDead) "dead" else "pending"})"

    companion object {
        internal fun pending(label: String) = RowHandle(label, null)

        /** A handle for a row that already exists (legacy callers holding a bare `Long`). */
        fun resolved(id: Long, label: String = "row"): RowHandle = RowHandle(label, id)
    }
}

/**
 * The closed set of writes the single actor knows how to apply. Every op that touches the child
 * tables goes through here so a parent row and its children commit in one Room transaction and
 * so no write ever races another on a second connection.
 */
sealed class PersistenceOp {
    abstract val label: String

    /** Whether dropping this op orphans a handle other ops may still reference. */
    internal open fun onDropped(reason: String) {}

    /**
     * Phase 1: a new `captured_traffic` row plus optional children. Children are written with the
     * freshly assigned id (their `trafficId` is ignored on input). Resolves [handle].
     */
    class InsertTransaction(
        val entity: CapturedTrafficEntity,
        val headers: List<TransactionHeaderEntity> = emptyList(),
        val timings: TransactionTimingEntity? = null,
        val appliedRules: List<AppliedRuleEntity> = emptyList(),
        val handle: RowHandle = RowHandle.pending("tx"),
    ) : PersistenceOp() {
        override val label: String get() = "insert-transaction"
        override fun onDropped(reason: String) {
            handle.deferred.completeExceptionally(DroppedWriteException(reason))
        }
    }

    /**
     * Phase 2: the response (or a later revision of a streamed exchange) for [target]. Applies the
     * same column-level discipline as `CapturedTrafficDao.updateDecodedExchange` (never regress a
     * populated column) and, when present, replaces the row's headers of the given kinds and
     * upserts its timings.
     */
    class PatchDecodedExchange(
        val target: RowHandle,
        val patch: CapturedTrafficEntity,
        val hasRequestHeaders: Boolean = patch.requestHeaders.isNotEmpty(),
        val hasResponseHeaders: Boolean = patch.responseHeaders.isNotEmpty(),
        val headers: List<TransactionHeaderEntity>? = null,
        val timings: TransactionTimingEntity? = null,
    ) : PersistenceOp() {
        override val label: String get() = "patch-exchange"
    }

    class AttachTimings(val target: RowHandle, val timings: TransactionTimingEntity) : PersistenceOp() {
        override val label: String get() = "attach-timings"
    }

    /** Replaces the row's headers of every [HeaderKind] present in [headers]. */
    class AttachHeaders(val target: RowHandle, val headers: List<TransactionHeaderEntity>) : PersistenceOp() {
        override val label: String get() = "attach-headers"
    }

    class AppendAppliedRules(val target: RowHandle, val rules: List<AppliedRuleEntity>) : PersistenceOp() {
        override val label: String get() = "append-applied-rules"
    }

    class InsertDns(val entity: DnsQueryEntity) : PersistenceOp() {
        override val label: String get() = "insert-dns"
    }

    /**
     * Escape hatch for writes that have no dedicated op yet (session bookkeeping, protocol
     * children). Runs inside the actor, so it still serialises against every other write.
     */
    class Custom(override val label: String, val block: suspend (RequestrDatabase) -> Unit) : PersistenceOp()

    /** Internal fence for [PersistenceQueue.awaitIdle]. */
    internal class Barrier(val done: CompletableDeferred<Unit>) : PersistenceOp() {
        override val label: String get() = "barrier"
        override fun onDropped(reason: String) {
            done.complete(Unit)
        }
    }
}

/**
 * S2 task 15 -- the single-writer persistence queue (interceptor plan §7 task 15, §4 failure
 * table).
 *
 * One bounded [Channel] (capacity [DEFAULT_CAPACITY]), one actor coroutine on a dedicated
 * single-thread dispatcher, every `captured_traffic` / child-table / DNS write in the process
 * flows through it. Producers call the non-suspending [submit]/[insertTransaction]/... from relay
 * threads, tun-read loops, NanoHTTPD handlers and coroutines alike; none of them ever touches
 * Room directly and none can block on disk IO -- the `runBlocking { dao.insert() }` blocks at
 * `CaptureEngine.kt:630/:863` and the VPN service's private mini-queue are gone.
 *
 * **Saturation:** when the channel is full the *oldest* queued op is evicted (drop-oldest), counted
 * in [PersistenceHealth.Snapshot.droppedWrites], and the new op is admitted. Dropping an insert
 * kills its [RowHandle] so later patches for it are counted as orphaned instead of failing in the
 * DAO. Drop-oldest rather than block-the-producer because the producer *is* the relay: a stalled
 * relay is a stalled app under test, which is worse than a hole in the log that the UI reports.
 *
 * **Failure:** a DAO exception discards that one op (its own transaction rolled back), increments
 * [PersistenceHealth.Snapshot.failedWrites] with the op label + message, and the actor continues.
 * The actor itself never dies on a write error.
 *
 * **Batches:** the actor drains up to [MAX_BATCH] ops per wake-up, then calls every
 * [addBatchListener] with a summary -- the hook `SessionPruner` (task 19) uses to enforce quotas
 * after commit rather than on every row.
 */
class PersistenceQueue internal constructor(
    private val db: RequestrDatabase,
    dispatcher: CoroutineDispatcher,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val health: PersistenceHealth = PersistenceHealth,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What one drained batch committed; consumed by pruning/quota logic. */
    data class Batch(
        val committed: Int,
        val failed: Int,
        val insertedTransactions: Int,
        val touchedSessionIds: Set<Long>,
    )

    private val channel = Channel<PersistenceOp>(capacity)
    private val depth = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("requestr-persist"))
    private val batchListeners = CopyOnWriteArrayList<suspend (Batch) -> Unit>()
    private val lastDropLogAt = AtomicLong(0)
    private val lastFailLogAt = AtomicLong(0)

    @Volatile
    private var closed = false

    private val actor: Job = scope.launch { drainLoop() }

    // -- producer API (never suspends, never touches Room) ----------------------------------------

    /**
     * Enqueues [op]. Returns false only if the queue is closed; saturation is handled internally by
     * evicting the oldest op (see class doc) so a live queue always accepts.
     */
    fun submit(op: PersistenceOp): Boolean {
        if (closed) {
            op.onDropped("persistence queue closed")
            return false
        }
        var attempts = 0
        while (true) {
            val result = channel.trySend(op)
            if (result.isSuccess) {
                depth.incrementAndGet()
                return true
            }
            if (result.isClosed) {
                op.onDropped("persistence queue closed")
                return false
            }
            // Full: evict the oldest queued op and retry. Bounded so a pathological race with the
            // consumer can't spin forever.
            val evicted = channel.tryReceive().getOrNull()
            if (evicted != null) {
                depth.decrementAndGet()
                evicted.onDropped("evicted under saturation (capacity $capacity)")
                health.recordDropped(1, clock())
                warnRateLimited(lastDropLogAt, "queue full (capacity $capacity); dropped oldest '${evicted.label}'")
            }
            if (++attempts > 3) {
                // Give up on THIS op instead of spinning; still a counted drop.
                op.onDropped("could not enqueue under saturation")
                health.recordDropped(1, clock())
                return true
            }
        }
    }

    fun insertTransaction(
        entity: CapturedTrafficEntity,
        headers: List<TransactionHeaderEntity> = emptyList(),
        timings: TransactionTimingEntity? = null,
        appliedRules: List<AppliedRuleEntity> = emptyList(),
    ): RowHandle {
        val op = PersistenceOp.InsertTransaction(entity, headers, timings, appliedRules)
        submit(op)
        return op.handle
    }

    fun patchDecodedExchange(
        target: RowHandle,
        patch: CapturedTrafficEntity,
        hasRequestHeaders: Boolean = patch.requestHeaders.isNotEmpty(),
        hasResponseHeaders: Boolean = patch.responseHeaders.isNotEmpty(),
        headers: List<TransactionHeaderEntity>? = null,
        timings: TransactionTimingEntity? = null,
    ) = submit(PersistenceOp.PatchDecodedExchange(target, patch, hasRequestHeaders, hasResponseHeaders, headers, timings))

    fun attachTimings(target: RowHandle, timings: TransactionTimingEntity) =
        submit(PersistenceOp.AttachTimings(target, timings))

    fun attachHeaders(target: RowHandle, headers: List<TransactionHeaderEntity>) =
        submit(PersistenceOp.AttachHeaders(target, headers))

    fun appendAppliedRules(target: RowHandle, rules: List<AppliedRuleEntity>) =
        submit(PersistenceOp.AppendAppliedRules(target, rules))

    fun insertDns(entity: DnsQueryEntity) = submit(PersistenceOp.InsertDns(entity))

    fun execute(label: String, block: suspend (RequestrDatabase) -> Unit) = submit(PersistenceOp.Custom(label, block))

    /** Current buffered op count (exact, producer-side). */
    val pending: Int get() = depth.get()

    /** Registers a post-batch hook (task 19 pruning). Runs on the actor thread after each drain. */
    fun addBatchListener(listener: suspend (Batch) -> Unit) {
        batchListeners += listener
    }

    fun removeBatchListener(listener: suspend (Batch) -> Unit) {
        batchListeners -= listener
    }

    // -- suspending helpers for importers and tests ----------------------------------------------

    /**
     * Suspends until every op submitted before this call has been applied (or dropped). Uses a
     * barrier op, so it is ordered like any other write and never busy-waits.
     */
    suspend fun awaitIdle() {
        if (closed) return
        val fence = CompletableDeferred<Unit>()
        val op = PersistenceOp.Barrier(fence)
        // Barriers use the suspending send: an importer/test can wait, and a barrier must not
        // evict real data to make room for itself.
        try {
            channel.send(op)
            depth.incrementAndGet()
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            return
        }
        fence.await()
    }

    /**
     * Back-pressured insert for bulk producers that are NOT relays (HAR import, SDK ingestion):
     * suspends while the queue is full instead of evicting live capture data, then waits for the
     * row id. Throws [DroppedWriteException] if the row was evicted anyway.
     */
    suspend fun insertTransactionAndAwait(entity: CapturedTrafficEntity): Long {
        val op = PersistenceOp.InsertTransaction(entity)
        if (closed) throw DroppedWriteException("persistence queue closed")
        channel.send(op)
        depth.incrementAndGet()
        return op.handle.await()
    }

    /** Stops accepting ops, drains what is queued, then cancels the actor. */
    suspend fun close() {
        closed = true
        channel.close()
        actor.join()
    }

    // -- actor -----------------------------------------------------------------------------------

    private suspend fun drainLoop() {
        val batch = ArrayList<PersistenceOp>(MAX_BATCH)
        while (true) {
            val first = try {
                channel.receive()
            } catch (_: ClosedReceiveChannelException) {
                return
            }
            batch.clear()
            batch += first
            while (batch.size < MAX_BATCH) {
                batch += channel.tryReceive().getOrNull() ?: break
            }
            depth.addAndGet(-batch.size)

            var committed = 0
            var failed = 0
            var inserted = 0
            val sessions = LinkedHashSet<Long>()
            for (op in batch) {
                try {
                    if (apply(op, sessions)) {
                        committed++
                        if (op is PersistenceOp.InsertTransaction) inserted++
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failed++
                    health.recordFailed(op.label, t.message, clock())
                    warnRateLimited(lastFailLogAt, "write '${op.label}' failed: ${t.message}", error = true)
                    if (op is PersistenceOp.InsertTransaction) {
                        op.handle.deferred.completeExceptionally(DroppedWriteException("insert failed: ${t.message}"))
                    }
                }
            }
            health.recordCommitted(committed, depth.get())
            val lastDrop = health.snapshot().lastDropAt
            if (lastDrop != null && clock() - lastDrop > SATURATION_WINDOW_MS) health.clearSaturation()

            if (batchListeners.isNotEmpty()) {
                val summary = Batch(committed, failed, inserted, sessions)
                for (listener in batchListeners) {
                    try {
                        listener(summary)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "batch listener failed: ${t.message}")
                    }
                }
            }
            // Barriers resolve last so awaitIdle() callers observe the listeners' effects too.
            for (op in batch) if (op is PersistenceOp.Barrier) op.done.complete(Unit)
        }
    }

    /** Applies one op in its own transaction. Returns false when skipped (orphaned/barrier). */
    private suspend fun apply(op: PersistenceOp, sessions: MutableSet<Long>): Boolean {
        when (op) {
            is PersistenceOp.InsertTransaction -> {
                val id = db.write {
                    val id = db.capturedTrafficDao().insert(op.entity)
                    if (op.headers.isNotEmpty()) {
                        db.transactionHeaderDao().insertAll(op.headers.map { it.copy(trafficId = id) })
                    }
                    op.timings?.let { db.transactionTimingDao().upsert(it.copy(trafficId = id)) }
                    if (op.appliedRules.isNotEmpty()) {
                        db.appliedRuleDao().insertAll(op.appliedRules.map { it.copy(trafficId = id) })
                    }
                    id
                }
                sessions += op.entity.sessionId
                op.handle.deferred.complete(id)
                return true
            }
            is PersistenceOp.PatchDecodedExchange -> {
                val id = resolve(op.target) ?: return false
                val p = op.patch
                db.write {
                    db.capturedTrafficDao().updateDecodedExchange(
                        id = id,
                        method = p.method,
                        url = p.url,
                        host = p.host,
                        path = p.path,
                        hasRequestHeaders = op.hasRequestHeaders,
                        requestHeaders = p.requestHeaders,
                        statusCode = p.statusCode,
                        hasResponseHeaders = op.hasResponseHeaders,
                        responseHeaders = p.responseHeaders,
                        requestBody = p.requestBody,
                        responseBody = p.responseBody,
                        requestSizeBytes = p.requestSizeBytes,
                        responseSizeBytes = p.responseSizeBytes,
                        latencyMs = p.latencyMs,
                        isRequestBodyBinary = p.isRequestBodyBinary,
                        isResponseBodyBinary = p.isResponseBodyBinary,
                        requestBodyBytes = p.requestBodyBytes,
                        responseBodyBytes = p.responseBodyBytes,
                        requestBodyBytesTruncated = p.requestBodyBytesTruncated,
                        responseBodyBytesTruncated = p.responseBodyBytesTruncated,
                    )
                    op.headers?.let { replaceHeaders(id, it) }
                    op.timings?.let { db.transactionTimingDao().upsert(it.copy(trafficId = id)) }
                }
                sessions += p.sessionId
                return true
            }
            is PersistenceOp.AttachTimings -> {
                val id = resolve(op.target) ?: return false
                db.transactionTimingDao().upsert(op.timings.copy(trafficId = id))
                return true
            }
            is PersistenceOp.AttachHeaders -> {
                val id = resolve(op.target) ?: return false
                db.write { replaceHeaders(id, op.headers) }
                return true
            }
            is PersistenceOp.AppendAppliedRules -> {
                val id = resolve(op.target) ?: return false
                if (op.rules.isNotEmpty()) db.appliedRuleDao().insertAll(op.rules.map { it.copy(trafficId = id) })
                return true
            }
            is PersistenceOp.InsertDns -> {
                db.dnsQueryDao().insert(op.entity)
                sessions += op.entity.sessionId
                return true
            }
            is PersistenceOp.Custom -> {
                op.block(db)
                return true
            }
            is PersistenceOp.Barrier -> return false // released after the batch listeners ran
        }
    }

    /**
     * One IMMEDIATE transaction on the writer connection. `useWriterConnection` (not the legacy
     * `withTransaction`) because the production database opens through the bundled SQLite driver
     * on fresh installs and through the support helper after a migration -- see
     * `RequestrDatabase.buildDatabase` -- and only the connection-based API works in both modes.
     * DAO suspend calls inside the block reuse the scoped connection.
     */
    private suspend fun <R> RequestrDatabase.write(block: suspend () -> R): R =
        useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

    private suspend fun replaceHeaders(id: Long, headers: List<TransactionHeaderEntity>) {
        val dao = db.transactionHeaderDao()
        for (kind in headers.map { it.kind }.distinct()) dao.deleteKind(id, kind)
        if (headers.isNotEmpty()) dao.insertAll(headers.map { it.copy(trafficId = id) })
    }

    /**
     * The row id for a handle, or null (counted as an orphaned patch) when the insert was dropped,
     * failed, or -- impossible in order-preserving processing, but guarded -- has not run yet.
     */
    private fun resolve(handle: RowHandle): Long? {
        val id = handle.idOrNull
        if (id == null) {
            health.recordOrphanedPatch()
            warnRateLimited(lastFailLogAt, "patch for ${handle} skipped: parent row never written")
        }
        return id
    }

    private fun warnRateLimited(last: AtomicLong, message: String, error: Boolean = false) {
        val now = clock()
        val prev = last.get()
        if (now - prev < LOG_INTERVAL_MS) return
        if (!last.compareAndSet(prev, now)) return
        if (error) Log.e(TAG, message) else Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "PersistenceQueue"
        const val DEFAULT_CAPACITY = 500
        const val MAX_BATCH = 64
        /** A drop keeps [PersistenceHealth.Snapshot.saturated] raised this long. */
        const val SATURATION_WINDOW_MS = 10_000L
        private const val LOG_INTERVAL_MS = 5_000L

        /**
         * One queue per database instance. In production that is exactly one (the
         * [RequestrDatabase.get] singleton); tests that build in-memory databases each get their
         * own, garbage-collected with the database. Weak keys so a closed test database does not
         * pin its queue.
         */
        private val registry = java.util.WeakHashMap<RequestrDatabase, PersistenceQueue>()

        /** The process-wide queue over [RequestrDatabase.get]. */
        fun get(context: Context): PersistenceQueue = forDatabase(RequestrDatabase.get(context.applicationContext))

        /**
         * The queue owning writes to [db]. Engines that are handed a database instance
         * (`PacketPump`, `CaptureEngine`) resolve their queue through this so an in-memory test
         * database is written by a queue over that same database.
         */
        fun forDatabase(db: RequestrDatabase): PersistenceQueue = synchronized(registry) {
            registry.getOrPut(db) { PersistenceQueue(db, serialDispatcher()) }
        }

        /**
         * A serial (parallelism 1) view of [kotlinx.coroutines.Dispatchers.IO]: the actor is one
         * coroutine, so what it needs is "never on main, never two at once", not a private OS
         * thread that would leak per in-memory test database.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun serialDispatcher(): CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

        /** Test-only: a queue over an in-memory database and a controllable dispatcher. */
        internal fun forTest(
            db: RequestrDatabase,
            dispatcher: CoroutineDispatcher,
            capacity: Int = DEFAULT_CAPACITY,
            health: PersistenceHealth = PersistenceHealth,
            clock: () -> Long = System::currentTimeMillis,
        ): PersistenceQueue = PersistenceQueue(db, dispatcher, capacity, health, clock)

        /** Test-only: forget every registered queue (e.g. after swapping the database). */
        internal fun resetForTest() {
            synchronized(registry) { registry.clear() }
        }
    }
}
