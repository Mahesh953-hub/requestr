package com.example.data.persistence

import android.content.Context
import android.content.SharedPreferences

/**
 * Rollout flag for the native tun2proxy bridge's packet-tee surface (defect G, S0 gaps audit).
 *
 * [com.example.interception.tun.Tun2ProxyConfig.forMitmProxyEngine] must set `teeEnabled=true`
 * for production VPN-tier capture -- otherwise the tee surface (S1's PCAPng export / passive
 * QUIC decode consumer) never receives frames even though the native bridge is fully capable of
 * producing them. Modeled directly on [DbDriverFlag]'s `SharedPreferences`-backed singleton
 * pattern for the same reason: the flag must be readable synchronously from
 * [com.example.interception.RequestrVpnService]'s `applicationContext` without any coroutine
 * scope or DataStore round-trip.
 *
 * ## Rollout / rollback
 *
 * - **Default: on** (`true`) -- the tee path only decodes/copies bytes that already cross the
 *   native bridge; leaving it off by default would silently starve every S1+ consumer of tee
 *   frames with no observable signal.
 * - **Rollback**: call [setEnabled]`(false)` if the tee path is ever implicated in a production
 *   incident (e.g. a native decode bug). Takes effect on the next [Tun2ProxyBridge.start] call
 *   (typically the next capture-session start), since [Tun2ProxyConfig] is immutable per
 *   session.
 */
class TeeRolloutFlag private constructor(private val prefs: SharedPreferences) {

    /**
     * Returns `true` if the native tun2proxy bridge should enable its packet-tee surface.
     * Safe to call from any thread.
     */
    val isEnabled: Boolean get() = prefs.getBoolean(KEY_TEE_ENABLED, true)

    /** Enables or disables the tee surface. Takes effect on the next bridge start. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TEE_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "requestr_tee_rollout"
        private const val KEY_TEE_ENABLED = "tee_enabled"

        @Volatile
        private var INSTANCE: TeeRolloutFlag? = null

        /** Returns (and lazily creates) the singleton flag instance. */
        fun get(context: Context): TeeRolloutFlag =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TeeRolloutFlag(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { INSTANCE = it }
            }

        /**
         * Creates an instance from an arbitrary [SharedPreferences] — for tests only.
         * Production code MUST use [get] to ensure the singleton is used.
         */
        internal fun forTest(prefs: SharedPreferences): TeeRolloutFlag = TeeRolloutFlag(prefs)
    }
}
