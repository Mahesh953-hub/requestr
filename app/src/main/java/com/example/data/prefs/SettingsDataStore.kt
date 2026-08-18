package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.interceptionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "requestr_interception_settings",
)

/**
 * Persisted interception configuration.
 *
 * Scoped to interception only for this pass — theme/onboarding/appearance keys are
 * deliberately absent because that work is deferred.
 */
class SettingsDataStore(private val context: Context) {

    private val store get() = context.interceptionDataStore

    private val prefs: Flow<Preferences> = store.data
        // A corrupt prefs file must not crash the app; fall back to defaults.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val proxyPort: Flow<Int> = prefs.map { it[KEY_PROXY_PORT] ?: DEFAULT_PROXY_PORT }

    val captureTierPreference: Flow<CaptureTierPreference> = prefs.map { p ->
        runCatching { CaptureTierPreference.valueOf(p[KEY_TIER_PREF] ?: "") }
            .getOrDefault(CaptureTierPreference.AUTO)
    }

    val appFilterMode: Flow<AppFilterMode> = prefs.map { p ->
        runCatching { AppFilterMode.valueOf(p[KEY_APP_FILTER_MODE] ?: "") }
            .getOrDefault(AppFilterMode.ALL_APPS)
    }

    /** Packages the user explicitly selected. Empty by default — nothing is pre-selected. */
    val selectedPackages: Flow<Set<String>> = prefs.map { it[KEY_SELECTED_PACKAGES] ?: emptySet() }

    val rememberLastAppFilter: Flow<Boolean> = prefs.map { it[KEY_REMEMBER_FILTER] ?: true }

    /**
     * Cached hint only. The CA chip and pre-flight gate MUST re-probe the real trust store —
     * this value is never trusted as proof of installation (APP_v2.md Bug 3.4).
     */
    val caInstalledHint: Flow<Boolean> = prefs.map { it[KEY_CA_INSTALLED_HINT] ?: false }

    suspend fun setProxyPort(port: Int) {
        // Keep within the unprivileged, valid TCP range.
        val safe = port.coerceIn(1024, 65535)
        store.edit { it[KEY_PROXY_PORT] = safe }
    }

    suspend fun setCaptureTierPreference(value: CaptureTierPreference) {
        store.edit { it[KEY_TIER_PREF] = value.name }
    }

    suspend fun setAppFilterMode(value: AppFilterMode) {
        store.edit { it[KEY_APP_FILTER_MODE] = value.name }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        store.edit { it[KEY_SELECTED_PACKAGES] = packages }
    }

    suspend fun setRememberLastAppFilter(value: Boolean) {
        store.edit { it[KEY_REMEMBER_FILTER] = value }
    }

    suspend fun setCaInstalledHint(value: Boolean) {
        store.edit { it[KEY_CA_INSTALLED_HINT] = value }
    }

    companion object {
        const val DEFAULT_PROXY_PORT = 8080

        private val KEY_PROXY_PORT = intPreferencesKey("proxy_port")
        private val KEY_TIER_PREF = stringPreferencesKey("capture_tier_preference")
        private val KEY_APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        private val KEY_SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        private val KEY_REMEMBER_FILTER = booleanPreferencesKey("remember_last_app_filter")
        private val KEY_CA_INSTALLED_HINT = booleanPreferencesKey("ca_installed_hint")

        @Volatile
        private var INSTANCE: SettingsDataStore? = null

        fun get(context: Context): SettingsDataStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
