package com.example.data.prefs

import android.content.Context

/**
 * Small, isolated preference store for Reqy Assistant chat settings that don't belong in Room
 * and deliberately do NOT go through the app-wide `unifiedDataStore`/`AppSettingsKeys` registry
 * either: several other agents are touching that shared file and `SettingsScreen.kt` in the
 * same wave, so this keeps Reqy's own small, non-secret settings (system instructions, Local
 * Mode, notifications, advanced network tuning, message density) in their own tiny
 * `SharedPreferences` file with zero risk of merge conflicts or accidentally colliding key names.
 *
 * Nothing here is a secret -- the API key never touches this file, only [SecureKeyStore]
 * (Keystore-backed) does.
 */
object ChatSettingsStore {

    private const val PREFS_NAME = "reqy_assistant_settings"

    private const val KEY_INSTRUCTIONS = "instructions"
    private const val KEY_LOCAL_MODE = "local_mode_enabled"
    private const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val KEY_COMPACT_DENSITY = "compact_density"
    private const val KEY_TIMEOUT_SECONDS = "advanced_timeout_seconds"
    private const val KEY_TEMPERATURE = "advanced_temperature"
    private const val KEY_RAW_LOGGING = "advanced_raw_logging"

    const val DEFAULT_INSTRUCTIONS =
        "You are Reqy, requestr's built-in assistant. Be concise. Prefer pointing the user to " +
            "the exact in-app screen over long explanations when a screen already does the job."
    const val DEFAULT_TIMEOUT_SECONDS = 30
    const val DEFAULT_TEMPERATURE = 0.7

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInstructions(context: Context): String =
        prefs(context).getString(KEY_INSTRUCTIONS, null) ?: DEFAULT_INSTRUCTIONS

    fun setInstructions(context: Context, text: String) {
        prefs(context).edit().putString(KEY_INSTRUCTIONS, text).apply()
    }

    fun isLocalModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCAL_MODE, false)

    fun setLocalModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_MODE, enabled).apply()
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
    }

    fun isCompactDensity(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPACT_DENSITY, false)

    fun setCompactDensity(context: Context, compact: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPACT_DENSITY, compact).apply()
    }

    // Note: this file used to also hold a single global chat-title override (KEY_TITLE_OVERRIDE /
    // getTitleOverride / setTitleOverride) set via the drawer's Rename action. It was removed
    // during the Reqy Assistant multi-conversation pass -- title overrides now live
    // per-conversation on ChatConversationEntity.title/titleIsOverride in Room, since a single
    // global override no longer makes sense once multiple conversations exist.

    fun getTimeoutSeconds(context: Context): Int =
        prefs(context).getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_TIMEOUT_SECONDS, seconds).apply()
    }

    fun getTemperature(context: Context): Double =
        prefs(context).getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE.toFloat()).toDouble()

    fun setTemperature(context: Context, temperature: Double) {
        prefs(context).edit().putFloat(KEY_TEMPERATURE, temperature.toFloat()).apply()
    }

    fun isRawLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RAW_LOGGING, false)

    fun setRawLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAW_LOGGING, enabled).apply()
    }
}
