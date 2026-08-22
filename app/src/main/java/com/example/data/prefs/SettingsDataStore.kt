package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** How the capture tier is chosen when interception starts. */
enum class CaptureTierPreference {
    /** Probe and pick the best available: Root > Shizuku > VPN. */
    AUTO,

    /** Force the rootless VpnService path, useful for testing the baseline. */
    FORCE_VPN_ONLY,
}

/** Whether interception applies to every app or an explicit allow-list. */
enum class AppFilterMode { ALL_APPS, SELECTED_APPS }

/**
 * Persisted interception configuration.
 *
 * v3.2 Part C — C1: this class now reads/writes through the single unified DataStore
 * ([unifiedDataStore]) instead of its own separate "requestr_interception_settings" file.
 * The public API (proxyPort, captureTierPreference, appFilterMode, selectedPackages,
 * rememberLastAppFilter, caInstalledHint Flows + setters) is unchanged so every existing
 * consumer (InterceptScreen, CaptureController, SettingsScreen, RequestrVpnService,
 * PreflightController) keeps working without modification.
 */
class SettingsDataStore(private val context: Context) {

    private val store: DataStore<Preferences> get() = context.unifiedDataStore

    private val prefs: Flow<Preferences> = store.data
        // A corrupt prefs file must not crash the app; fall back to defaults.
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }

    val proxyPort: Flow<Int> = prefs.map { it[AppSettingsKeys.PROXY_PORT] ?: DEFAULT_PROXY_PORT }

    val captureTierPreference: Flow<CaptureTierPreference> = prefs.map { p ->
        runCatching { CaptureTierPreference.valueOf(p[AppSettingsKeys.CAPTURE_TIER_PREFERENCE] ?: "") }
            .getOrDefault(CaptureTierPreference.AUTO)
    }

    val appFilterMode: Flow<AppFilterMode> = prefs.map { p ->
        runCatching { AppFilterMode.valueOf(p[AppSettingsKeys.APP_FILTER_MODE] ?: "") }
            .getOrDefault(AppFilterMode.ALL_APPS)
    }

    /** Packages the user explicitly selected. Empty by default — nothing is pre-selected. */
    val selectedPackages: Flow<Set<String>> = prefs.map { it[AppSettingsKeys.SELECTED_PACKAGES] ?: emptySet() }

    val rememberLastAppFilter: Flow<Boolean> = prefs.map { it[AppSettingsKeys.REMEMBER_LAST_APP_FILTER] ?: true }

    /**
     * Cached hint only. The CA chip and pre-flight gate MUST re-probe the real trust store —
     * this value is never trusted as proof of installation (APP_v2.md Bug 3.4).
     */
    val caInstalledHint: Flow<Boolean> = prefs.map { it[AppSettingsKeys.CA_INSTALLED_HINT] ?: false }

    /** "VPN" or "PROXY" — which capture mode to use */
    val captureRecordMode: Flow<String> = prefs.map { p ->
        (p[AppSettingsKeys.CAPTURE_RECORD_MODE] ?: "VPN").let { if (it in listOf("VPN", "PROXY")) it else "VPN" }
    }

    val enhancedMode: Flow<Boolean> = prefs.map { it[AppSettingsKeys.ENHANCED_MODE] ?: false }
    val loggingEnabled: Flow<Boolean> = prefs.map { it[AppSettingsKeys.LOGGING_ENABLED] ?: false }
    val printUndecoded: Flow<Boolean> = prefs.map { it[AppSettingsKeys.PRINT_UNDECODED] ?: true }
    val dumpToFile: Flow<Boolean> = prefs.map { it[AppSettingsKeys.DUMP_TO_FILE] ?: false }
    val pcapEnabled: Flow<Boolean> = prefs.map { it[AppSettingsKeys.PCAP_ENABLED] ?: false }

    /** T2: whether GatewayEngine (LAN proxy for remote devices) is enabled. */
    val gatewayModeEnabled: Flow<Boolean> = prefs.map { it[AppSettingsKeys.GATEWAY_MODE_ENABLED] ?: false }

    /** T2: port GatewayEngine listens on, bound to 0.0.0.0 so LAN clients can reach it. */
    val gatewayPort: Flow<Int> = prefs.map { it[AppSettingsKeys.GATEWAY_PORT] ?: DEFAULT_GATEWAY_PORT }

    /**
     * SDK & CONNECT scope, Task 6: the bearer token `/api/observe` requires. Null until
     * [LocalApiServer][com.example.api.LocalApiServer] generates one on first start.
     */
    val pairingToken: Flow<String?> = prefs.map { it[AppSettingsKeys.PAIRING_TOKEN] }

    /** Whether `GET /api/pairing-info` is currently allowed to reveal [pairingToken]. */
    val showPairingToken: Flow<Boolean> = prefs.map { it[AppSettingsKeys.SHOW_PAIRING_TOKEN] ?: false }

    /** Epoch millis after which the reveal auto-disables itself (5 minutes from when enabled). */
    val pairingTokenRevealUntil: Flow<Long> = prefs.map { it[AppSettingsKeys.PAIRING_TOKEN_REVEAL_UNTIL] ?: 0L }

    suspend fun setProxyPort(port: Int) {
        // Keep within the unprivileged, valid TCP range.
        val safe = port.coerceIn(1024, 65535)
        store.edit { it[AppSettingsKeys.PROXY_PORT] = safe }
    }

    suspend fun setCaptureTierPreference(value: CaptureTierPreference) {
        store.edit { it[AppSettingsKeys.CAPTURE_TIER_PREFERENCE] = value.name }
    }

    suspend fun setAppFilterMode(value: AppFilterMode) {
        store.edit { it[AppSettingsKeys.APP_FILTER_MODE] = value.name }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        store.edit { it[AppSettingsKeys.SELECTED_PACKAGES] = packages }
    }

    suspend fun setRememberLastAppFilter(value: Boolean) {
        store.edit { it[AppSettingsKeys.REMEMBER_LAST_APP_FILTER] = value }
    }

    suspend fun setCaInstalledHint(value: Boolean) {
        store.edit { it[AppSettingsKeys.CA_INSTALLED_HINT] = value }
    }

    suspend fun setCaptureRecordMode(value: String) {
        store.edit { it[AppSettingsKeys.CAPTURE_RECORD_MODE] = value }
    }

    suspend fun setEnhancedMode(value: Boolean) {
        store.edit { it[AppSettingsKeys.ENHANCED_MODE] = value }
    }
    suspend fun setLoggingEnabled(value: Boolean) {
        store.edit { it[AppSettingsKeys.LOGGING_ENABLED] = value }
    }
    suspend fun setPrintUndecoded(value: Boolean) {
        store.edit { it[AppSettingsKeys.PRINT_UNDECODED] = value }
    }
    suspend fun setDumpToFile(value: Boolean) {
        store.edit { it[AppSettingsKeys.DUMP_TO_FILE] = value }
    }
    suspend fun setPcapEnabled(value: Boolean) {
        store.edit { it[AppSettingsKeys.PCAP_ENABLED] = value }
    }

    suspend fun setGatewayModeEnabled(value: Boolean) {
        store.edit { it[AppSettingsKeys.GATEWAY_MODE_ENABLED] = value }
    }

    suspend fun setGatewayPort(port: Int) {
        val safe = port.coerceIn(1024, 65535)
        store.edit { it[AppSettingsKeys.GATEWAY_PORT] = safe }
    }

    suspend fun setPairingToken(value: String) {
        store.edit { it[AppSettingsKeys.PAIRING_TOKEN] = value }
    }

    /** Enables the reveal window (used by [showPairingToken]/[pairingTokenRevealUntil]). */
    suspend fun setShowPairingToken(value: Boolean, revealDurationMs: Long = PAIRING_REVEAL_DURATION_MS) {
        store.edit {
            it[AppSettingsKeys.SHOW_PAIRING_TOKEN] = value
            it[AppSettingsKeys.PAIRING_TOKEN_REVEAL_UNTIL] =
                if (value) System.currentTimeMillis() + revealDurationMs else 0L
        }
    }

    companion object {
        const val DEFAULT_PROXY_PORT = 9000
        const val DEFAULT_GATEWAY_PORT = 8888
        const val PAIRING_REVEAL_DURATION_MS = 5 * 60 * 1000L

        @Volatile
        private var INSTANCE: SettingsDataStore? = null

        fun get(context: Context): SettingsDataStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
