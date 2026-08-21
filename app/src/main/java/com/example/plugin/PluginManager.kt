package com.example.plugin

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages plugin lifecycle, registration, and hook dispatch.
 *
 * Plugins are discovered in two ways:
 * 1. Built-in plugins registered via [registerBuiltinPlugins] at startup
 * 2. External plugins loaded from /sdcard/requestr/plugins/
 *
 * Hook dispatch follows a chain-of-responsibility pattern: the first
 * plugin that returns a non-null result wins (short-circuits the chain).
 */
object PluginManager {
    private const val TAG = "PluginManager"

    private val _plugins = MutableStateFlow<Map<String, PluginEntry>>(emptyMap())
    val plugins: StateFlow<Map<String, PluginEntry>> = _plugins.asStateFlow()

    /** Plugins indexed by id for O(1) lookup */
    private val pluginMap: MutableMap<String, Plugin> = LinkedHashMap()

    /**
     * Register built-in plugins at app startup. External plugins
     * can also call this, but it's intended for compile-time plugins.
     */
    fun registerBuiltinPlugins(builtins: List<Plugin>) {
        for (plugin in builtins) {
            registerPlugin(plugin, PluginSource.BUILTIN)
        }
        Log.i(TAG, "Registered ${builtins.size} built-in plugins")
    }

    /** Register a single plugin and call onLoad */
    fun registerPlugin(plugin: Plugin, source: PluginSource = PluginSource.EXTERNAL) {
        if (pluginMap.containsKey(plugin.id)) {
            Log.w(TAG, "Plugin ${plugin.id} already registered — skipping")
            return
        }
        try {
            plugin.onLoad()
            pluginMap[plugin.id] = plugin
            val entry = PluginEntry(
                plugin = plugin,
                source = source,
                enabled = true,
                permissions = plugin.requiredPermissions
            )
            _plugins.value = _plugins.value + (plugin.id to entry)
            Log.i(TAG, "Loaded plugin: ${plugin.id} (${plugin.name}) [${source.name}]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${plugin.id}: ${e.message}", e)
        }
    }

    /** Unregister a plugin and call onUnload */
    fun unregisterPlugin(pluginId: String) {
        val plugin = pluginMap.remove(pluginId) ?: return
        try {
            plugin.onUnload()
            _plugins.value = _plugins.value - pluginId
            Log.i(TAG, "Unloaded plugin: ${pluginId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading plugin ${pluginId}: ${e.message}", e)
        }
    }

    /** Enable/disable a plugin without unloading it */
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val current = _plugins.value[pluginId] ?: return
        _plugins.value = _plugins.value + (pluginId to current.copy(enabled = enabled))
    }

    /** Get all active (loaded + enabled) plugins */
    fun getActivePlugins(): List<Plugin> =
        pluginMap.values.filter { p ->
            _plugins.value[p.id]?.enabled == true
        }

    /** Get a specific plugin by id */
    fun getPlugin(id: String): Plugin? = pluginMap[id]

    // ── Hook dispatch ────────────────────────────────────────────

    /** Dispatch onBeforeRequest to all active plugins. First non-null result wins. */
    fun dispatchOnBeforeRequest(request: PluginHttpRequest): PluginHttpRequest? {
        for (plugin in getActivePlugins()) {
            try {
                val result = plugin.onBeforeRequest(request)
                if (result != null) return result
            } catch (e: Exception) {
                Log.e(TAG, "Plugin ${plugin.id} threw in onBeforeRequest: ${e.message}", e)
            }
        }
        return null
    }

    /** Dispatch onAfterResponse to all active plugins. First non-null result wins. */
    fun dispatchOnAfterResponse(response: PluginHttpResponse): PluginHttpResponse? {
        for (plugin in getActivePlugins()) {
            try {
                val result = plugin.onAfterResponse(response)
                if (result != null) return result
            } catch (e: Exception) {
                Log.e(TAG, "Plugin ${plugin.id} threw in onAfterResponse: ${e.message}", e)
            }
        }
        return null
    }

    /** Dispatch customDecode to all active plugins. First non-null result wins. */
    fun dispatchCustomDecode(contentType: String, rawBytes: ByteArray): String? {
        for (plugin in getActivePlugins()) {
            try {
                val result = plugin.customDecode(contentType, rawBytes)
                if (result != null) return result
            } catch (e: Exception) {
                Log.e(TAG, "Plugin ${plugin.id} threw in customDecode: ${e.message}", e)
            }
        }
        return null
    }

    /** Find and execute a custom tool across all active plugins */
    fun dispatchCustomTool(toolName: String, input: Map<String, String>): String? {
        for (plugin in getActivePlugins()) {
            try {
                val result = plugin.executeCustomTool(toolName, input)
                if (result != null) return result
            } catch (e: Exception) {
                Log.e(TAG, "Plugin ${plugin.id} threw in executeCustomTool: ${e.message}", e)
            }
        }
        return null
    }

    // ── External plugin loading ──────────────────────────────────

    /**
     * Scan /sdcard/requestr/plugins/ for .jar and .dex files and
     * attempt to load Plugin implementations via reflection.
     *
     * Note: On modern Android, loading external DEX/JAR files requires
     * DexClassLoader. This is a best-effort mechanism — plugins MUST
     * implement the [Plugin] interface and declare the implementing
     * class in a META-INF/services/com.example.plugin.Plugin file.
     */
    fun scanExternalPlugins(context: android.content.Context) {
        val pluginDir = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "requestr/plugins"
        )
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
            return
        }

        val dexFiles = pluginDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".dex"))
        } ?: return

        for (dexFile in dexFiles) {
            try {
                val loader = dalvik.system.DexClassLoader(
                    dexFile.absolutePath,
                    context.cacheDir.absolutePath,
                    null,
                    Plugin::class.java.classLoader
                )
                // Try to find a class implementing Plugin via service file
                val serviceFile = loader.getResourceAsStream(
                    "META-INF/services/com.example.plugin.Plugin"
                )
                if (serviceFile != null) {
                    val className = serviceFile.bufferedReader().readLine()?.trim()
                    serviceFile.close()
                    if (className != null && className.isNotEmpty()) {
                        val clazz = loader.loadClass(className)
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is Plugin) {
                            registerPlugin(instance, PluginSource.EXTERNAL)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugin from ${dexFile.name}: ${e.message}", e)
            }
        }
    }
}

data class PluginEntry(
    val plugin: Plugin,
    val source: PluginSource,
    val enabled: Boolean,
    val permissions: List<PluginPermission>
)

enum class PluginSource { BUILTIN, EXTERNAL }
