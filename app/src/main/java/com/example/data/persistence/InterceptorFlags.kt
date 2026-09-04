package com.example.data.persistence

import android.content.Context
import android.content.SharedPreferences

/**
 * S1/S2 rollout flags from master plan §6: `relayPoolBounded`, `quicTeePolicy`, `pcapng`,
 * `bodySpill`.
 *
 * One `SharedPreferences` file, one boolean per flag, modeled on [TeeRolloutFlag]/[DbDriverFlag]
 * so every flag is readable synchronously from [com.example.interception.RequestrVpnService]
 * and the session handlers without a coroutine scope or DataStore round-trip. Flags are read
 * once per capture session (or per connection where documented) -- flipping one takes effect on
 * the next session start.
 *
 * Defaults are **on**: each flag guards code that replaces a known-defective path (raw relay
 * threads with `join(60_000)`, no UDP scope policy, no packets in native-bridge mode). Turning a
 * flag off restores the pre-S1 behaviour for that surface only and is the documented rollback.
 */
class InterceptorFlags private constructor(private val prefs: SharedPreferences) {

    /**
     * Task 7: when `true` the relay pool enforces its permit bound and wait queue; when `false`
     * every connection is admitted immediately (raw threads are gone either way).
     */
    val relayPoolBounded: Boolean get() = prefs.getBoolean(KEY_RELAY_POOL_BOUNDED, true)

    /**
     * Task 9: when `true` the D6 UDP/QUIC scope policy is handed to the native bridge and the
     * legacy pump; when `false` UDP is passed untouched everywhere (`PASS_ALL`).
     */
    val quicTeePolicy: Boolean get() = prefs.getBoolean(KEY_QUIC_TEE_POLICY, true)

    /** Task 12: when `true` the tee feeds the PCAPng writer (and classic PCAP) in native-bridge mode. */
    val pcapng: Boolean get() = prefs.getBoolean(KEY_PCAPNG, true)

    /**
     * S2 task 16: when `true` bodies above [BodyStore.INLINE_LIMIT_BYTES] are spilled to
     * `filesDir/captures/` by the persistence actor and engines hand up to
     * [BodyStore.MAX_SPILLABLE_BYTES] to it; when `false` the pre-S2 behaviour applies (bodies
     * above `HttpBodyDecoder.MAX_STORED_BODY_BYTES` are dropped, only their size is kept).
     */
    val bodySpill: Boolean get() = prefs.getBoolean(KEY_BODY_SPILL, true)

    /**
     * S3 task 20: when `true` WebSocket messages, SSE events and gRPC messages are written as
     * v20 child rows under one parent transaction (the 101 upgrade, the event-stream response,
     * the gRPC call); when `false` the pre-v20 flattened rows (`WS→`/`WS←`, `x-grpc-*`) are
     * produced instead, exactly as before, so the traffic list keeps its per-message rows until
     * the protocol viewers land.
     */
    val protocolChildren: Boolean get() = prefs.getBoolean(KEY_PROTOCOL_CHILDREN, true)

    /**
     * S3 task 22: when `true` the traffic list and Home search run the compiled
     * [com.example.data.search.TrafficSearchCompiler] query against Room (FTS5 `MATCH` where the
     * index exists, `LIKE` otherwise); when `false` search is the pre-v22 in-memory filter over the
     * loaded list. The index is maintained either way once it exists.
     */
    val ftsSearch: Boolean get() = prefs.getBoolean(KEY_FTS_SEARCH, true)

    fun setRelayPoolBounded(enabled: Boolean) = prefs.edit().putBoolean(KEY_RELAY_POOL_BOUNDED, enabled).apply()
    fun setQuicTeePolicy(enabled: Boolean) = prefs.edit().putBoolean(KEY_QUIC_TEE_POLICY, enabled).apply()
    fun setPcapng(enabled: Boolean) = prefs.edit().putBoolean(KEY_PCAPNG, enabled).apply()
    fun setBodySpill(enabled: Boolean) = prefs.edit().putBoolean(KEY_BODY_SPILL, enabled).apply()
    fun setProtocolChildren(enabled: Boolean) = prefs.edit().putBoolean(KEY_PROTOCOL_CHILDREN, enabled).apply()
    fun setFtsSearch(enabled: Boolean) = prefs.edit().putBoolean(KEY_FTS_SEARCH, enabled).apply()

    companion object {
        private const val PREFS_NAME = "requestr_interceptor_flags"
        internal const val KEY_RELAY_POOL_BOUNDED = "relay_pool_bounded"
        internal const val KEY_QUIC_TEE_POLICY = "quic_tee_policy"
        internal const val KEY_PCAPNG = "pcapng"
        internal const val KEY_BODY_SPILL = "body_spill"
        internal const val KEY_PROTOCOL_CHILDREN = "protocol_children"
        internal const val KEY_FTS_SEARCH = "fts_search"

        @Volatile
        private var INSTANCE: InterceptorFlags? = null

        fun get(context: Context): InterceptorFlags =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: InterceptorFlags(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { INSTANCE = it }
            }

        /** Test-only constructor over arbitrary prefs; production must use [get]. */
        internal fun forTest(prefs: SharedPreferences): InterceptorFlags = InterceptorFlags(prefs)
    }
}
