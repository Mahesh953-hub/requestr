package com.example.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * FIX D: a single, correct "lazily create-once and reuse a per-tier session row" helper.
 *
 * Before this fix there were three near-duplicate copies of this logic with three different
 * correctness levels:
 *  - [com.example.api.LocalApiServer.sdkSessionId] cached nothing at all and re-queried
 *    [CaptureSessionDao.observeAll] on every single `/api/observe` batch.
 *  - [com.example.interception.gateway.GatewayEngine.gatewaySessionId] cached the id in a plain
 *    `private var` with no `@Volatile` and no lock -- racy, since `handleClient` runs one
 *    coroutine per accepted connection, so two connections opening at once could both observe
 *    the uncached `0L` and insert two GATEWAY session rows.
 *  - [com.example.runtime.RuntimeTrafficSink.runtimeSessionId] already got this right:
 *    `@Volatile` fast path plus [Mutex]-guarded double-checked locking. This object generalizes
 *    that exact pattern, keyed per [CaptureTier] so one instance safely serves every caller.
 */
object CaptureSessionRegistry {

    private val mutex = Mutex()

    @Volatile
    private var cache: Map<CaptureTier, Long> = emptyMap()

    /**
     * Returns the id of the single always-open (`endedAt == null`) session row for [tier],
     * creating one if none exists yet. [port] is stamped onto a newly-created row only (SDK and
     * GATEWAY tiers use it; other tiers pass 0 and it is ignored on the cached/existing path).
     */
    suspend fun openSessionIdFor(db: RequestrDatabase, tier: CaptureTier, port: Int = 0): Long {
        cache[tier]?.let { return it }
        return mutex.withLock {
            cache[tier]?.let { return it }
            val sessionDao = db.captureSessionDao()
            val existing = sessionDao.observeAll().first().firstOrNull { it.tier == tier && it.endedAt == null }
            val id = existing?.id ?: sessionDao.insert(CaptureSessionEntity(tier = tier, port = port))
            cache = cache + (tier to id)
            id
        }
    }

    /** Test-only: forces the next [openSessionIdFor] call for every tier to re-query the DB. */
    internal fun resetForTest() {
        cache = emptyMap()
    }
}
