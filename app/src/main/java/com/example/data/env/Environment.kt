package com.example.data.env

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

object EnvironmentRepository {
    private val _state = MutableStateFlow(EnvironmentState())
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("requestr_env", Context.MODE_PRIVATE)
        val count = prefs!!.getInt("env_count", 0)
        if (count > 0) {
            val envs = (0 until count).mapNotNull { i ->
                val name = prefs!!.getString("env_${i}_name", null) ?: return@mapNotNull null
                val varCount = prefs!!.getInt("env_${i}_var_count", 0)
                val vars = (0 until varCount).mapNotNull { vi ->
                    val key = prefs!!.getString("env_${i}_var_${vi}_key", null) ?: return@mapNotNull null
                    val value = prefs!!.getString("env_${i}_var_${vi}_value", "") ?: ""
                    key to value
                }.toMap()
                Environment(name, vars)
            }
            val active = prefs!!.getString("env_active", "Local") ?: "Local"
            _state.value = EnvironmentState(envs, active)
        }
    }

    fun active(): Environment {
        val s = _state.value
        return s.environments.find { it.name == s.activeEnvironment } ?: s.environments.first()
    }

    fun setActive(name: String) {
        _state.update { it.copy(activeEnvironment = name) }
        prefs?.edit()?.putString("env_active", name)?.apply()
    }

    fun addEnvironment(env: Environment) {
        _state.update {
            if (it.environments.any { e -> e.name == env.name }) it
            else it.copy(environments = it.environments + env)
        }
        persist()
    }

    fun removeEnvironment(name: String) {
        _state.update { it.copy(environments = it.environments.filter { e -> e.name != name }) }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val envs = _state.value.environments
        p.edit().apply {
            putInt("env_count", envs.size)
            envs.forEachIndexed { i, env ->
                putString("env_${i}_name", env.name)
                putInt("env_${i}_var_count", env.variables.size)
                env.variables.entries.forEachIndexed { vi, (k, v) ->
                    putString("env_${i}_var_${vi}_key", k)
                    putString("env_${i}_var_${vi}_value", v)
                }
            }
        }.apply()
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
