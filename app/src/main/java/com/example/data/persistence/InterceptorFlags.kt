package com.example.data.persistence

import android.content.Context
import android.content.SharedPreferences

/**
 * S1 rollout flags from master plan §6: `relayPoolBounded`, `quicTeePolicy`, `pcapng`.
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

    fun setRelayPoolBounded(enabled: Boolean) = prefs.edit().putBoolean(KEY_RELAY_POOL_BOUNDED, enabled).apply()
    fun setQuicTeePolicy(enabled: Boolean) = prefs.edit().putBoolean(KEY_QUIC_TEE_POLICY, enabled).apply()
    fun setPcapng(enabled: Boolean) = prefs.edit().putBoolean(KEY_PCAPNG, enabled).apply()

    companion object {
        private const val PREFS_NAME = "requestr_interceptor_flags"
        internal const val KEY_RELAY_POOL_BOUNDED = "relay_pool_bounded"
        internal const val KEY_QUIC_TEE_POLICY = "quic_tee_policy"
        internal const val KEY_PCAPNG = "pcapng"

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
