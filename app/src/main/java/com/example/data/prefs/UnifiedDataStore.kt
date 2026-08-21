package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * v3.2 Part C — C1: Single DataStore migration.
 *
 * Before this, the app had THREE separate, fragmented persistence mechanisms:
 *  1. `SettingsRepository` — raw SharedPreferences file "requestr_user_settings"
 *  2. `EnvironmentRepository` — raw SharedPreferences file "requestr_env"
 *  3. `SettingsDataStore` — a real DataStore, but scoped only to interception settings,
 *     file "requestr_interception_settings"
 *
 * This file declares the ONE unified `DataStore<Preferences>` ("requestr_settings") that all
 * three domains now read/write through, plus the one-time migration routine that copies any
 * existing data out of the two legacy SharedPreferences files and the legacy interception
 * DataStore file into this unified store, then deletes the legacy files so they are never
 * read again.
 *
 * There must only ever be ONE DataStore instance per file (Jetpack DataStore's own
 * requirement — two instances on the same file risk corrupted/lost writes), so this is the
 * single top-level delegate; `SettingsRepository`, `EnvironmentRepository`, and
 * `SettingsDataStore` all reference this same property instead of declaring their own.
 */
val Context.unifiedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "requestr_settings",
)

/** All Preferences keys used across the unified store, grouped by the domain that owns them. */
object AppSettingsKeys {
    // -- Migration bookkeeping --
    val MIGRATION_LEGACY_V1_DONE = booleanPreferencesKey("migration_legacy_v1_done")

    // -- SettingsRepository (formerly "requestr_user_settings" SharedPreferences) --
    val USER_NAME = stringPreferencesKey("user_name")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val CORNER_RADIUS = intPreferencesKey("corner_radius")
    // proxy_port is intentionally SHARED with SettingsDataStore below — pre-C1, the Settings
    // screen wrote the same int to two separate stores in lockstep as a workaround for the
    // fragmentation; unifying the storage makes that workaround unnecessary; one key, one value.
    val PROXY_PORT = intPreferencesKey("proxy_port")
    val AUTO_BEAUTIFY = booleanPreferencesKey("auto_beautify")
    val SSL_VERIFY = booleanPreferencesKey("ssl_verify")
    val SNIPPET_LANG = stringPreferencesKey("snippet_lang")
    val ONBOARDING = booleanPreferencesKey("onboarding_completed")
    val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
    val SELECTED_DARK_THEME = stringPreferencesKey("selected_dark_theme")
    val SELECTED_LIGHT_THEME = stringPreferencesKey("selected_light_theme")
    val SWIPE_TO_DELETE = booleanPreferencesKey("swipe_to_delete")
    val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
    val GESTURE_NAVIGATION = booleanPreferencesKey("gesture_navigation")
    val DENSITY_MODE = stringPreferencesKey("density_mode")
    val UPDATE_NOTIFICATION_POLICY = stringPreferencesKey("update_notification_policy")
    val ANIMATION_ENABLED = booleanPreferencesKey("animation_enabled")
    val TRANSITION_BEHAVIOR = stringPreferencesKey("transition_behavior")
    val DISPLAY_SCALE = stringPreferencesKey("display_scale")
    val AUTO_RECORDING = booleanPreferencesKey("auto_recording")
    val TRAFFIC_LOG = booleanPreferencesKey("traffic_log")
    val RECORDING_NOTIFICATIONS = booleanPreferencesKey("recording_notifications")
    val REMOTE_CONTROL = booleanPreferencesKey("remote_control")
    val REMOTE_COMMANDS = booleanPreferencesKey("remote_commands")
    val LAN_DISCOVERY = booleanPreferencesKey("lan_discovery")
    val DEVICE_BROADCASTING = booleanPreferencesKey("device_broadcasting")
    val TRAFFIC_CAPTURE = booleanPreferencesKey("traffic_capture")
    val REQUEST_LOGGING = booleanPreferencesKey("request_logging")
    val RESPONSE_LOGGING = booleanPreferencesKey("response_logging")
    val MUSIC_PERMISSION_STATUS = stringPreferencesKey("music_permission_status")
    val MUSIC_BAR_DISMISSED = booleanPreferencesKey("music_bar_dismissed")
    val MUSIC_LIBRARY_SCANNED = booleanPreferencesKey("music_library_scanned")
    val SELECTED_LOCAL_TRACK_ID = stringPreferencesKey("selected_local_track_id")

    // -- EnvironmentRepository (formerly "requestr_env" SharedPreferences) --
    // The whole environments list is stored as one JSON blob (see Environment.kt) rather than
    // re-implementing the old manually-indexed env_{i}_var_{vi}_key scheme in Preferences keys.
    val ENVIRONMENTS_JSON = stringPreferencesKey("environments_json")
    val ACTIVE_ENVIRONMENT = stringPreferencesKey("active_environment")

    // -- SettingsDataStore (formerly its own "requestr_interception_settings" DataStore) --
    val CAPTURE_TIER_PREFERENCE = stringPreferencesKey("capture_tier_preference")
    val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
    val SELECTED_PACKAGES = androidx.datastore.preferences.core.stringSetPreferencesKey("selected_packages")
    val REMEMBER_LAST_APP_FILTER = booleanPreferencesKey("remember_last_app_filter")
    val CA_INSTALLED_HINT = booleanPreferencesKey("ca_installed_hint")
    // v4.2: capture mode selection
    val CAPTURE_RECORD_MODE = stringPreferencesKey("capture_record_mode")  // "VPN" or "PROXY"
    val ENHANCED_MODE = booleanPreferencesKey("enhanced_mode")             // richer logging + analysis
    val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")         // show debug overlay
    val PRINT_UNDECODED = booleanPreferencesKey("print_undecoded")         // show raw TLS/TCP in list
    val DUMP_TO_FILE = booleanPreferencesKey("dump_to_file")               // dump raw packets to disk
    // v5.6 (T1): export every raw IP packet seen on the VPN tun interface to a standard
    // .pcap file (via PacketCaptureEngine) for external analysis in Wireshark etc. Only
    // takes effect for the VPN_TUN / ROOT_FRIDA-fallback capture methods, which are the only
    // ones that see raw IP packets through PacketPump -- see PreflightController.
    val PCAP_ENABLED = booleanPreferencesKey("pcap_enabled")
}

/**
 * One-time migration from the three legacy stores into [Context.unifiedDataStore]. Safe to call
 * multiple times (and from multiple init paths) — guarded by [AppSettingsKeys.MIGRATION_LEGACY_V1_DONE]
 * so the actual copy only ever runs once per install.
 */
object LegacyDataMigration {

    private const val LEGACY_USER_SETTINGS_PREFS = "requestr_user_settings"
    private const val LEGACY_ENV_PREFS = "requestr_env"
    private const val LEGACY_INTERCEPTION_DATASTORE_FILE = "requestr_interception_settings"

    suspend fun migrateIfNeeded(context: Context) {
        val already = context.unifiedDataStore.data.first()[AppSettingsKeys.MIGRATION_LEGACY_V1_DONE] ?: false
        if (already) return

        context.unifiedDataStore.edit { prefs ->
            migrateUserSettings(context, prefs)
            migrateEnvironments(context, prefs)
            migrateInterceptionDataStore(context, prefs)
            prefs[AppSettingsKeys.MIGRATION_LEGACY_V1_DONE] = true
        }

        // Never read the old locations again — remove them so a future accidental read (or a
        // stray reference during refactor) can't silently resurrect stale data.
        context.deleteSharedPreferences(LEGACY_USER_SETTINGS_PREFS)
        context.deleteSharedPreferences(LEGACY_ENV_PREFS)
        runCatching {
            java.io.File(context.filesDir, "datastore/$LEGACY_INTERCEPTION_DATASTORE_FILE.preferences_pb").delete()
        }
    }

    private fun migrateUserSettings(context: Context, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val p = context.getSharedPreferences(LEGACY_USER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (p.all.isEmpty()) return
        fun str(key: String, target: Preferences.Key<String>) {
            p.getString(key, null)?.let { prefs[target] = it }
        }
        fun bool(key: String, target: Preferences.Key<Boolean>) {
            if (p.contains(key)) prefs[target] = p.getBoolean(key, false)
        }
        fun int(key: String, target: Preferences.Key<Int>) {
            if (p.contains(key)) prefs[target] = p.getInt(key, 0)
        }
        str("user_name", AppSettingsKeys.USER_NAME)
        str("theme_mode", AppSettingsKeys.THEME_MODE)
        int("corner_radius", AppSettingsKeys.CORNER_RADIUS)
        int("proxy_port", AppSettingsKeys.PROXY_PORT)
        bool("auto_beautify", AppSettingsKeys.AUTO_BEAUTIFY)
        bool("ssl_verify", AppSettingsKeys.SSL_VERIFY)
        str("snippet_lang", AppSettingsKeys.SNIPPET_LANG)
        bool("onboarding_completed", AppSettingsKeys.ONBOARDING)
        str("appearance_mode", AppSettingsKeys.APPEARANCE_MODE)
        str("selected_dark_theme", AppSettingsKeys.SELECTED_DARK_THEME)
        str("selected_light_theme", AppSettingsKeys.SELECTED_LIGHT_THEME)
        bool("swipe_to_delete", AppSettingsKeys.SWIPE_TO_DELETE)
        bool("haptic_feedback", AppSettingsKeys.HAPTIC_FEEDBACK)
        bool("gesture_navigation", AppSettingsKeys.GESTURE_NAVIGATION)
        str("density_mode", AppSettingsKeys.DENSITY_MODE)
        str("update_notification_policy", AppSettingsKeys.UPDATE_NOTIFICATION_POLICY)
        bool("animation_enabled", AppSettingsKeys.ANIMATION_ENABLED)
        str("transition_behavior", AppSettingsKeys.TRANSITION_BEHAVIOR)
        str("display_scale", AppSettingsKeys.DISPLAY_SCALE)
        bool("auto_recording", AppSettingsKeys.AUTO_RECORDING)
        bool("traffic_log", AppSettingsKeys.TRAFFIC_LOG)
        bool("recording_notifications", AppSettingsKeys.RECORDING_NOTIFICATIONS)
        bool("remote_control", AppSettingsKeys.REMOTE_CONTROL)
        bool("remote_commands", AppSettingsKeys.REMOTE_COMMANDS)
        bool("lan_discovery", AppSettingsKeys.LAN_DISCOVERY)
        bool("device_broadcasting", AppSettingsKeys.DEVICE_BROADCASTING)
        bool("traffic_capture", AppSettingsKeys.TRAFFIC_CAPTURE)
        bool("request_logging", AppSettingsKeys.REQUEST_LOGGING)
        bool("response_logging", AppSettingsKeys.RESPONSE_LOGGING)
        str("music_permission_status", AppSettingsKeys.MUSIC_PERMISSION_STATUS)
        bool("music_bar_dismissed", AppSettingsKeys.MUSIC_BAR_DISMISSED)
        bool("music_library_scanned", AppSettingsKeys.MUSIC_LIBRARY_SCANNED)
        str("selected_local_track_id", AppSettingsKeys.SELECTED_LOCAL_TRACK_ID)
    }

    private fun migrateEnvironments(context: Context, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val p = context.getSharedPreferences(LEGACY_ENV_PREFS, Context.MODE_PRIVATE)
        val count = p.getInt("env_count", 0)
        if (count <= 0) return
        val envs = (0 until count).mapNotNull { i ->
            val name = p.getString("env_${i}_name", null) ?: return@mapNotNull null
            val varCount = p.getInt("env_${i}_var_count", 0)
            val vars = (0 until varCount).mapNotNull { vi ->
                val key = p.getString("env_${i}_var_${vi}_key", null) ?: return@mapNotNull null
                val value = p.getString("env_${i}_var_${vi}_value", "") ?: ""
                key to value
            }.toMap()
            com.example.data.env.Environment(name, vars)
        }
        if (envs.isEmpty()) return
        prefs[AppSettingsKeys.ENVIRONMENTS_JSON] = com.example.data.env.EnvironmentJsonCodec.toJson(envs)
        p.getString("env_active", null)?.let { prefs[AppSettingsKeys.ACTIVE_ENVIRONMENT] = it }
    }

    private suspend fun migrateInterceptionDataStore(
        context: Context,
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        val legacyFile = java.io.File(context.filesDir, "datastore/$LEGACY_INTERCEPTION_DATASTORE_FILE.preferences_pb")
        if (!legacyFile.exists()) return
        val legacyStore: DataStore<Preferences> = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { legacyFile },
        )
        val legacy = runCatching { legacyStore.data.first() }.getOrNull() ?: return

        legacy[intPreferencesKey("proxy_port")]?.let { prefs[AppSettingsKeys.PROXY_PORT] = it }
        legacy[stringPreferencesKey("capture_tier_preference")]?.let { prefs[AppSettingsKeys.CAPTURE_TIER_PREFERENCE] = it }
        legacy[stringPreferencesKey("app_filter_mode")]?.let { prefs[AppSettingsKeys.APP_FILTER_MODE] = it }
        legacy[androidx.datastore.preferences.core.stringSetPreferencesKey("selected_packages")]?.let {
            prefs[AppSettingsKeys.SELECTED_PACKAGES] = it
        }
        legacy[booleanPreferencesKey("remember_last_app_filter")]?.let { prefs[AppSettingsKeys.REMEMBER_LAST_APP_FILTER] = it }
        legacy[booleanPreferencesKey("ca_installed_hint")]?.let { prefs[AppSettingsKeys.CA_INSTALLED_HINT] = it }
    }
}
