package com.example.scripting

/**
 * Process-wide, in-memory backing store for `pm.globals` (master plan §1.8/§1.11).
 *
 * Postman-style globals are meant to outlive a single request/script run (unlike
 * `pm.environment`, which is scoped to the active [com.example.data.env.Environment] and
 * persisted through [com.example.data.env.EnvironmentRepository]). A real implementation would
 * persist this to DataStore/Room so it also survives a process death, but that is intentionally
 * out of scope here to keep this change time-boxed -- documented limitation, not an oversight:
 * `pm.globals.set(...)` values are visible to every later script run in the same app process
 * (ApiTestr sends and live RuleEnforcer SCRIPT_REWRITE rules share this one store) but reset on
 * app restart.
 */
object GlobalVariableStore {
    private val values = LinkedHashMap<String, String>()

    @Synchronized
    fun get(key: String): String? = values[key]

    @Synchronized
    fun set(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    fun unset(key: String) {
        values.remove(key)
    }

    @Synchronized
    fun has(key: String): Boolean = values.containsKey(key)

    @Synchronized
    fun keys(): Set<String> = values.keys.toSet()

    @Synchronized
    fun snapshot(): Map<String, String> = values.toMap()

    /** Test-only reset so unit tests don't leak globals between cases via this process-wide store. */
    @Synchronized
    fun clear() {
        values.clear()
    }
}
