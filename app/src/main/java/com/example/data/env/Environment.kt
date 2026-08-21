package com.example.data.env

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.example.data.prefs.AppSettingsKeys
import com.example.data.prefs.unifiedDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Environment(
    val name: String,
    val variables: Map<String, String> = emptyMap(),
)

data class EnvironmentState(
    val environments: List<Environment> = listOf(
        Environment("Local", mapOf("BASE_URL" to "http://localhost:3000")),
        Environment("Development", mapOf("BASE_URL" to "https://dev-api.example.com")),
        Environment("Staging", mapOf("BASE_URL" to "https://staging-api.example.com")),
        Environment("Production", mapOf("BASE_URL" to "https://api.example.com")),
    ),
    val activeEnvironment: String = "Local",
)

/** Plain JSON codec for a `List<Environment>`, shared by [EnvironmentRepository] and the C1 migration. */
object EnvironmentJsonCodec {
    fun toJson(envs: List<Environment>): String {
        val arr = JSONArray()
        envs.forEach { env ->
            val obj = JSONObject()
            obj.put("name", env.name)
            val vars = JSONObject()
            env.variables.forEach { (k, v) -> vars.put(k, v) }
            obj.put("variables", vars)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Environment> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val varsObj = obj.optJSONObject("variables") ?: JSONObject()
                val vars = varsObj.keys().asSequence().associateWith { key -> varsObj.getString(key) }
                Environment(name, vars)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * v3.2 Part C — C1: now backed by the single unified DataStore ([unifiedDataStore]) instead of
 * its own raw "requestr_env" SharedPreferences file. The environments list is stored as one JSON
 * blob under [AppSettingsKeys.ENVIRONMENTS_JSON] rather than the old manually-indexed
 * `env_{i}_var_{vi}_key` scheme — same data, one write path, no partial-write races between
 * sibling keys.
 */
object EnvironmentRepository {
    private val _state = MutableStateFlow(EnvironmentState())
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false

    /**
     * Loads persisted environments from the unified DataStore. Safe to call multiple times;
     * only the first call does the load. Callers that need the migrated data guaranteed to be
     * visible must ensure [com.example.data.prefs.LegacyDataMigration.migrateIfNeeded] has already
     * run against the same context (MainActivity.onCreate ensures the ordering).
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        repoScope.launch {
            val prefs = context.applicationContext.unifiedDataStore.data.first()
            val json = prefs[AppSettingsKeys.ENVIRONMENTS_JSON]
            val envs = json?.let { EnvironmentJsonCodec.fromJson(it) }
            val active = prefs[AppSettingsKeys.ACTIVE_ENVIRONMENT]
            if (!envs.isNullOrEmpty()) {
                _state.value = EnvironmentState(envs, active ?: envs.first().name)
            } else if (active != null) {
                _state.value = _state.value.copy(activeEnvironment = active)
            }
        }
    }

    fun active(): Environment {
        val s = _state.value
        return s.environments.find { it.name == s.activeEnvironment } ?: s.environments.first()
    }

    fun setActive(name: String) {
        _state.update { it.copy(activeEnvironment = name) }
        persistActive(name)
    }

    fun addEnvironment(env: Environment) {
        _state.update {
            if (it.environments.any { e -> e.name == env.name }) it
            else it.copy(environments = it.environments + env)
        }
        persistEnvironments()
    }

    fun removeEnvironment(name: String) {
        _state.update { it.copy(environments = it.environments.filter { e -> e.name != name }) }
        persistEnvironments()
    }

    fun updateEnvironment(name: String, variables: Map<String, String>) {
        _state.update {
            it.copy(environments = it.environments.map { e -> if (e.name == name) e.copy(variables = variables) else e })
        }
        persistEnvironments()
    }

    private fun persistEnvironments() {
        val ctx = appContext ?: return
        val envs = _state.value.environments
        repoScope.launch {
            ctx.unifiedDataStore.edit { it[AppSettingsKeys.ENVIRONMENTS_JSON] = EnvironmentJsonCodec.toJson(envs) }
        }
    }

    private fun persistActive(name: String) {
        val ctx = appContext ?: return
        repoScope.launch {
            ctx.unifiedDataStore.edit { it[AppSettingsKeys.ACTIVE_ENVIRONMENT] = name }
        }
    }

    // ── Variable substitution ──────────────────────────────────────────

    fun substitute(template: String, env: Environment = active()): String {
        var result = template
        // Built-in variables
        result = result.replace("{{TIMESTAMP}}", System.currentTimeMillis().toString())
        result = result.replace("{{RANDOM}}", (1000..99999).random().toString())
        result = result.replace("{{UUID}}", java.util.UUID.randomUUID().toString())
        // Environment variables
        env.variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}

private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
