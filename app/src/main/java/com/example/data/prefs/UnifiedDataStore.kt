package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    // TRAFFIC_LOG key removed (complaint #4 audit fix): the "Traffic Log" toggle it backed was
    // dead UI with no reader anywhere in the app. See AppModels.kt / SettingsScreen.kt for the
    // full rationale. The legacy migration below intentionally does NOT copy this key forward.
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
    // T2: Gateway mode — an independent, parallel proxy server (GatewayEngine) that lets
    // OTHER LAN devices point their HTTP proxy at this device and get their traffic captured
    // through the same pipeline as on-device captures. Deliberately NOT gated by
    // PreflightController's CaptureMethod/ActiveTier resolution (which only covers traffic
    // originating on THIS device) — it runs independently of whatever local tier is active.
    val GATEWAY_MODE_ENABLED = booleanPreferencesKey("gateway_mode_enabled")
    val GATEWAY_PORT = intPreferencesKey("gateway_port")

    // §1.10: opt-in remote-cert mirroring -- when a FORCE_MITM host's leaf cert is generated,
    // probe the real upstream server first (RemoteCertificateMirror) and mirror its subject/SANs
    // /validity into our self-signed leaf instead of always synthesizing a generic one. OFF by
    // default: mirroring means opening a real upstream connection before any mock-short-circuit
    // decision for that host, which would silently break §1.5's "a MOCK_RESPONSE-only connection
    // never dials the real endpoint" guarantee if it ran unconditionally -- see
    // CertificateAuthority.issueMirroredLeaf's KDoc and MitmProxyEngine's cert-cache call site.
    val REMOTE_CERT_MIRROR_ENABLED = booleanPreferencesKey("remote_cert_mirror_enabled")

    // §3 (C2) — weak-network simulation (added latency/jitter/packet-loss) applied at the proxy
    // relay tier (HttpProxyShared -- the only tier that runs on this emulator; see
    // NetworkConditionInjector's KDoc). OFF by default: simulated network degradation must never
    // silently slow down or fail real traffic unless a user explicitly opts in.
    val NETWORK_CONDITION_ENABLED = booleanPreferencesKey("network_condition_enabled")
    val NETWORK_CONDITION_LATENCY_MS = intPreferencesKey("network_condition_latency_ms")
    val NETWORK_CONDITION_JITTER_MS = intPreferencesKey("network_condition_jitter_ms")
    val NETWORK_CONDITION_PACKET_LOSS_PCT = intPreferencesKey("network_condition_packet_loss_pct")

    // SDK & CONNECT scope, Task 6: pairing token gating `/api/observe` on LocalApiServer.
    // Generated once (SecureRandom, 256-bit) on first LocalApiServer.start() and never rotated
    // automatically -- only the "Regenerate Token" button in Settings changes it.
    val PAIRING_TOKEN = stringPreferencesKey("pairing_token")
    // "Show Pairing Token" toggle: while true, GET /api/pairing-info returns the token; while
    // false it 403s. Auto-disabled by SettingsScreen after 5 minutes (PAIRING_TOKEN_REVEAL_UNTIL).
    val SHOW_PAIRING_TOKEN = booleanPreferencesKey("show_pairing_token")
    val PAIRING_TOKEN_REVEAL_UNTIL = longPreferencesKey("pairing_token_reveal_until")

    // v6.0 transplant (plan task 6, manifest §5.11) — Navigation and Header Modifier keys.
    // Back the glass nav-pill / floating-header visual modifiers semantically recreated
    // from main. Canonical glass-style values are FROSTED_GLASS / SOLID_M3 / OLED_BLACK /
    // TRANSLUCENT (DESIGN-RECORD §11); legacy values are mapped to canonical at read time.
    val NAV_BAR_OPACITY = floatPreferencesKey("nav_bar_opacity")
    val NAV_BAR_BLUR_EFFECT = stringPreferencesKey("nav_bar_blur_effect")
    val NAV_BAR_HIDDEN_ITEMS = androidx.datastore.preferences.core.stringSetPreferencesKey("nav_bar_hidden_items")
    val HEADER_TOP_PADDING = intPreferencesKey("header_top_padding")
    val HEADER_OPACITY = floatPreferencesKey("header_opacity")
    val HEADER_BLUR_STYLE = stringPreferencesKey("header_blur_style")
    val FAB_BOTTOM_SPACING = intPreferencesKey("fab_bottom_spacing")
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

/**
 * v6.0 transplant (DESIGN-RECORD §11) — canonicalizes legacy glass-style key values that
 * main's Settings screen wrote into the single canonical set the glass components read:
 * `FROSTED_GLASS` / `SOLID_M3` / `OLED_BLACK` / `TRANSLUCENT`. Without this, OLED and M3
 * settings silently fell through the component `else → FROSTED_GLASS` branch.
 *
 * Legacy mappings (per §11):
 *  - Header: `FROSTED→FROSTED_GLASS`, `M3_MINIMAL→SOLID_M3`, `CLEAR_GLASS→TRANSLUCENT`, `DEEP_DARK→OLED_BLACK`
 *  - Nav:    `FROSTED→FROSTED_GLASS`, `M3→SOLID_M3`, `OLED→OLED_BLACK`, `TRANSLUCENT→TRANSLUCENT`
 *  - Unknown/blank → `FROSTED_GLASS`
 */
fun canonicalGlassStyle(raw: String?): String = when (raw?.uppercase()?.trim()) {
    "FROSTED_GLASS", "FROSTED" -> "FROSTED_GLASS"
    "SOLID_M3", "M3_MINIMAL", "M3" -> "SOLID_M3"
    "OLED_BLACK", "DEEP_DARK", "OLED" -> "OLED_BLACK"
    "TRANSLUCENT", "CLEAR_GLASS" -> "TRANSLUCENT"
    else -> "FROSTED_GLASS"
}
