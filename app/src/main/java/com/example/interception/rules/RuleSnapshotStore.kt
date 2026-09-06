package com.example.interception.rules

import android.util.Log
import com.example.data.local.InterceptorRuleDao
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleFolderDao
import com.example.data.local.RuleFolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * S4 task 25 -- the process-wide publisher of the compiled rule library.
 *
 * Relay threads read [current] (a `@Volatile` reference: one load, no lock, no allocation) and
 * evaluate against it; they never see a half-built snapshot and never touch Room. The collector
 * started by [start] combines `InterceptorRuleDao.observeEnabledOrdered()` with
 * `RuleFolderDao.observeAll()`, recompiles on every emission on [Dispatchers.Default], and
 * publishes the new snapshot with the next [RuleSnapshot.revision]. Room's invalidation tracker
 * coalesces bursts of writes, and [distinctUntilChanged] drops emissions whose inputs did not
 * actually change, so an import of 200 rules costs one or two compiles, not 200.
 *
 * This replaces the legacy `runBlocking { db.interceptRuleDao().getEnabledRules() }` executed once
 * (twice, in `MitmProxyEngine`) per flow on the relay thread. The cut-over of the engines is task
 * 26; until then the store runs alongside the legacy path so the snapshot is warm when the
 * pipeline arrives.
 *
 * Lifecycle: [start] is idempotent per process (a second call while running is a no-op and returns
 * the same job); [stop] cancels the collector and leaves [current] at its last value. Tests use
 * [replaceForTest] to publish a hand-built snapshot without a database.
 */
object RuleSnapshotStore {

    private const val TAG = "RuleSnapshotStore"

    @Volatile
    var current: RuleSnapshot = RuleSnapshot.EMPTY
        private set

    private val _snapshots = MutableStateFlow(RuleSnapshot.EMPTY)

    /** For UI and diagnostics. Engines must use [current] on hot paths, not this flow. */
    val snapshots: StateFlow<RuleSnapshot> = _snapshots.asStateFlow()

    private val revision = AtomicLong(0L)

    @Volatile
    private var collector: Job? = null

    /** Bumped on every start/stop so a collector that is still winding down after [stop] cannot publish a stale revision. */
    @Volatile
    private var generation: Long = 0L

    /** Number of successful compiles since process start (diagnostics / tests). */
    @Volatile
    var compileCount: Long = 0L
        private set

    /**
     * Set by [resetForTest]: once a test has taken control of the store, the process-wide
     * [startFromApplication] call becomes a no-op. Robolectric instantiates `RequestrApplication`
     * for every test, and its collector (bound to the real on-disk database) would otherwise race
     * the test's own [start] for this singleton.
     */
    @Volatile
    private var testControlled: Boolean = false

    /** Convenience over [start]. */
    fun start(scope: CoroutineScope, db: RequestrDatabase): Job =
        start(scope, db.interceptorRuleDao(), db.ruleFolderDao())

    /** Application entry point: identical to [start] unless a test owns the store (see [testControlled]). */
    fun startFromApplication(scope: CoroutineScope, db: RequestrDatabase): Job? {
        if (testControlled) return null
        return start(scope, db)
    }

    @Synchronized
    fun start(scope: CoroutineScope, ruleDao: InterceptorRuleDao, folderDao: RuleFolderDao): Job {
        collector?.takeIf { it.isActive }?.let { return it }
        val gen = ++generation
        val job = scope.launch(Dispatchers.Default) {
            combine(ruleDao.observeEnabledOrdered(), folderDao.observeAll()) { rules, folders -> rules to folders }
                .distinctUntilChanged()
                .catch { t -> Log.e(TAG, "rule snapshot collector failed; keeping revision ${current.revision}", t) }
                .collect { (rules, folders) -> if (gen == generation) publish(rules, folders) }
        }
        collector = job
        return job
    }

    @Synchronized
    fun stop() {
        generation++
        collector?.cancel()
        collector = null
    }

    val isRunning: Boolean get() = collector?.isActive == true

    /** Compiles and publishes; also usable directly (imports that want the snapshot before Room's invalidation fires). */
    fun publish(rules: List<InterceptorRuleEntity>, folders: List<RuleFolderEntity>): RuleSnapshot {
        val snapshot = try {
            RuleSnapshotCompiler.compile(rules, folders, revision.incrementAndGet())
        } catch (t: Throwable) {
            // Compile is defensive per rule, so this is a bug, not bad data. Keep serving the previous revision.
            Log.e(TAG, "rule snapshot compile failed; keeping revision ${current.revision}", t)
            return current
        }
        install(snapshot)
        if (snapshot.issues.isNotEmpty()) {
            for (issue in snapshot.issues) Log.w(TAG, "rev ${snapshot.revision}: rule #${issue.ruleId} '${issue.ruleName}': ${issue.message}")
        }
        Log.i(TAG, "published $snapshot in ${snapshot.compileDurationNanos / 1_000} us")
        return snapshot
    }

    /** Test seam: publishes [snapshot] as-is (its revision is used verbatim) without a database. */
    fun replaceForTest(snapshot: RuleSnapshot) {
        install(snapshot)
        revision.updateAndGet { r -> maxOf(r, snapshot.revision) }
    }

    /** Test seam: back to [RuleSnapshot.EMPTY] at revision 0 with the collector stopped; disarms [startFromApplication]. */
    fun resetForTest() {
        testControlled = true
        stop()
        current = RuleSnapshot.EMPTY
        _snapshots.value = RuleSnapshot.EMPTY
        revision.set(0L)
        compileCount = 0L
    }

    private fun install(snapshot: RuleSnapshot) {
        current = snapshot
        _snapshots.value = snapshot
        compileCount++
    }
}
