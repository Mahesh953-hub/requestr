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

    /** S1 task 12: [PCAP_FORMAT_PCAPNG] (default) or [PCAP_FORMAT_PCAP]; anything else reads as PCAPng. */
    val pcapFormat: Flow<String> = prefs.map { p ->
        (p[AppSettingsKeys.PCAP_FORMAT] ?: PCAP_FORMAT_PCAPNG).let { if (it == PCAP_FORMAT_PCAP) it else PCAP_FORMAT_PCAPNG }
    }

    /** T2: whether GatewayEngine (LAN proxy for remote devices) is enabled. */
    val gatewayModeEnabled: Flow<Boolean> = prefs.map { it[AppSettingsKeys.GATEWAY_MODE_ENABLED] ?: false }

    /** T2: port GatewayEngine listens on, bound to 0.0.0.0 so LAN clients can reach it. */
    val gatewayPort: Flow<Int> = prefs.map { it[AppSettingsKeys.GATEWAY_PORT] ?: DEFAULT_GATEWAY_PORT }

    /**
     * §1.10: opt-in remote-cert mirroring for `FORCE_MITM` hosts -- see
     * [AppSettingsKeys.REMOTE_CERT_MIRROR_ENABLED]'s KDoc. OFF (`false`) by default.
     */
    val remoteCertMirrorEnabled: Flow<Boolean> =
        prefs.map { it[AppSettingsKeys.REMOTE_CERT_MIRROR_ENABLED] ?: false }

    /** S4 task 33 (D3): the one-time developer-mode acknowledgement gating every mutating stage. OFF by default. */
    val mutatingRulesAcknowledged: Flow<Boolean> = prefs.map { it[AppSettingsKeys.MUTATING_RULES_ACKNOWLEDGED] ?: false }

    /** S4 task 33 (D3): true while at least one mutating rule is enabled (drives the safety notification). */
    val mutatingRulesActive: Flow<Boolean> = prefs.map { it[AppSettingsKeys.MUTATING_RULES_ACTIVE] ?: false }

    /** S1 task 9 (D6): Observe-QUIC override -- in-scope UDP/443 is teed then passed instead of dropped. */
    val quicObserveOverride: Flow<Boolean> = prefs.map { it[AppSettingsKeys.QUIC_OBSERVE_OVERRIDE] ?: false }

    /** S1 task 9 (D6): packages whose UDP always passes untouched even when in the capture selection. */
    val quicBypassPackages: Flow<Set<String>> = prefs.map { it[AppSettingsKeys.QUIC_BYPASS_PACKAGES] ?: emptySet() }

    /**
     * §3 (C2) -- persisted weak-network simulation config, consumed by
     * [com.example.interception.NetworkConditionInjector] via
     * [com.example.interception.HttpProxyShared]. `enabled = false` and all-zero fields by
     * default (see [com.example.interception.NetworkConditionInjector.NetworkCondition.NONE]) --
     * this feature must never affect real traffic unless a user explicitly turns it on.
     */
    val networkCondition: Flow<com.example.interception.NetworkConditionInjector.NetworkCondition> = prefs.map { p ->
        com.example.interception.NetworkConditionInjector.NetworkCondition(
            enabled = p[AppSettingsKeys.NETWORK_CONDITION_ENABLED] ?: false,
            latencyMs = (p[AppSettingsKeys.NETWORK_CONDITION_LATENCY_MS] ?: 0).coerceAtLeast(0),
            jitterMs = (p[AppSettingsKeys.NETWORK_CONDITION_JITTER_MS] ?: 0).coerceAtLeast(0),
            packetLossPercent = (p[AppSettingsKeys.NETWORK_CONDITION_PACKET_LOSS_PCT] ?: 0).coerceIn(0, 100),
        )
    }

    /**
     * SDK & CONNECT scope, Task 6: the bearer token `/api/observe` requires. Null until
     * [LocalApiServer][com.example.api.LocalApiServer] generates one on first start.
     */
    val pairingToken: Flow<String?> = prefs.map { it[AppSettingsKeys.PAIRING_TOKEN] }

    /** Whether `GET /api/pairing-info` is currently allowed to reveal [pairingToken]. */
    val showPairingToken: Flow<Boolean> = prefs.map { it[AppSettingsKeys.SHOW_PAIRING_TOKEN] ?: false }

    /** Epoch millis after which the reveal auto-disables itself (5 minutes from when enabled). */
    val pairingTokenRevealUntil: Flow<Long> = prefs.map { it[AppSettingsKeys.PAIRING_TOKEN_REVEAL_UNTIL] ?: 0L }

    /**
     * App scope, Task 9: capture toggles the VPN service must read directly.
     * Readable from any process/component (e.g. [com.example.interception.RequestrVpnService]
     * restarted by the system without [com.example.MainActivity]), unlike
     * `SettingsRepository.settings.value`, which only reflects reality once
     * `SettingsRepository.init()` has run from the Activity.
     */
    val trafficCapture: Flow<Boolean> = prefs.map { it[AppSettingsKeys.TRAFFIC_CAPTURE] ?: true }
    val requestLogging: Flow<Boolean> = prefs.map { it[AppSettingsKeys.REQUEST_LOGGING] ?: true }
    val responseLogging: Flow<Boolean> = prefs.map { it[AppSettingsKeys.RESPONSE_LOGGING] ?: true }

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
    suspend fun setPcapFormat(value: String) {
        store.edit { it[AppSettingsKeys.PCAP_FORMAT] = if (value == PCAP_FORMAT_PCAP) PCAP_FORMAT_PCAP else PCAP_FORMAT_PCAPNG }
    }

    suspend fun setGatewayModeEnabled(value: Boolean) {
        store.edit { it[AppSettingsKeys.GATEWAY_MODE_ENABLED] = value }
    }

    suspend fun setGatewayPort(port: Int) {
        val safe = port.coerceIn(1024, 65535)
        store.edit { it[AppSettingsKeys.GATEWAY_PORT] = safe }
    }

    suspend fun setRemoteCertMirrorEnabled(value: Boolean) {
        store.edit { it[AppSettingsKeys.REMOTE_CERT_MIRROR_ENABLED] = value }
    }

    suspend fun setMutatingRulesAcknowledged(value: Boolean) {
        store.edit { it[AppSettingsKeys.MUTATING_RULES_ACKNOWLEDGED] = value }
    }

    suspend fun setMutatingRulesActive(value: Boolean) {
        store.edit { it[AppSettingsKeys.MUTATING_RULES_ACTIVE] = value }
    }

    suspend fun setQuicObserveOverride(value: Boolean) {
        store.edit { it[AppSettingsKeys.QUIC_OBSERVE_OVERRIDE] = value }
    }

    suspend fun setQuicBypassPackages(packages: Set<String>) {
        store.edit { it[AppSettingsKeys.QUIC_BYPASS_PACKAGES] = packages }
    }

    /**
     * §3 (C2) -- writes the full weak-network simulation config in one atomic edit (all four
     * fields together, never a partial write that could momentarily have `enabled = true` with
     * stale/default latency values from a prior edit). Values are clamped to
     * [com.example.interception.NetworkConditionInjector.NetworkCondition]'s own valid ranges
     * before writing, so a bad UI input (e.g. a slider briefly at -1 mid-drag) can never persist
     * an invalid config that would throw when [networkCondition] next reconstructs it.
     */
    suspend fun setNetworkCondition(value: com.example.interception.NetworkConditionInjector.NetworkCondition) {
        store.edit {
            it[AppSettingsKeys.NETWORK_CONDITION_ENABLED] = value.enabled
            it[AppSettingsKeys.NETWORK_CONDITION_LATENCY_MS] = value.latencyMs.coerceAtLeast(0)
            it[AppSettingsKeys.NETWORK_CONDITION_JITTER_MS] = value.jitterMs.coerceAtLeast(0)
            it[AppSettingsKeys.NETWORK_CONDITION_PACKET_LOSS_PCT] = value.packetLossPercent.coerceIn(0, 100)
        }
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
        /** S1 task 12: values of [AppSettingsKeys.PCAP_FORMAT]. */
        const val PCAP_FORMAT_PCAP = "pcap"
        const val PCAP_FORMAT_PCAPNG = "pcapng"
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
