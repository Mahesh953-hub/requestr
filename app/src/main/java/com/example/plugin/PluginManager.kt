package com.example.plugin

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.runBlocking

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
     * Ids the user switched off. Survives restarts through [stateStore]; consulted by
     * [registerPlugin] so a plugin that is registered after the user disabled it (built-ins at
     * the next launch, an external .jar on rescan) comes back disabled rather than silently
     * re-enabled.
     */
    @Volatile private var disabledIds: Set<String> = emptySet()
    @Volatile private var stateStore: PluginStateStore? = null

    /**
     * Attach the store that remembers [disabledIds] across restarts. Loads the persisted set,
     * applies it to every plugin already registered (built-ins are registered before any
     * Context is available -- see MainActivity.onCreate's order) and records it for every later
     * registration. Idempotent: a second call with a store already attached is a no-op.
     */
    fun attachStateStore(store: PluginStateStore) {
        if (stateStore != null) return
        val persisted = try {
            store.loadDisabledIds()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read persisted plugin state; treating every plugin as enabled: ${e.message}", e)
            emptySet()
        }
        disabledIds = persisted
        stateStore = store
        if (persisted.isNotEmpty()) {
            _plugins.value = _plugins.value.mapValues { (id, entry) ->
                if (id in persisted && entry.enabled) entry.copy(enabled = false) else entry
            }
            Log.i(TAG, "Restored ${persisted.size} disabled plugin id(s)")
        }
    }

    /** [attachStateStore] with the production store backed by the unified DataStore. */
    fun attachPersistence(context: Context) {
        if (stateStore != null) return
        attachStateStore(DataStorePluginStateStore(context.applicationContext))
    }

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

    /**
     * Register a single plugin and call onLoad. Returns the outcome so callers (the external
     * scanner, the Settings rescan) can report exactly what happened instead of inferring it.
     */
    fun registerPlugin(plugin: Plugin, source: PluginSource = PluginSource.EXTERNAL): PluginRegistration {
        if (pluginMap.containsKey(plugin.id)) {
            Log.w(TAG, "Plugin ${plugin.id} already registered — skipping")
            return PluginRegistration.ALREADY_REGISTERED
        }
        return try {
            plugin.onLoad()
            pluginMap[plugin.id] = plugin
            val entry = PluginEntry(
                plugin = plugin,
                source = source,
                // Honour a disable the user saved before this registration (previous launch or
                // an earlier session of this launch).
                enabled = plugin.id !in disabledIds,
                permissions = plugin.requiredPermissions
            )
            _plugins.value = _plugins.value + (plugin.id to entry)
            Log.i(TAG, "Loaded plugin: ${plugin.id} (${plugin.name}) [${source.name}] enabled=${entry.enabled}")
            PluginRegistration.LOADED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${plugin.id}: ${e.message}", e)
            PluginRegistration.FAILED
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

    /**
     * Enable/disable a plugin without unloading it. The choice is written to [stateStore]
     * (when attached) so it holds across app restarts; without a store it is still honoured for
     * the rest of this process.
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val current = _plugins.value[pluginId] ?: return
        _plugins.value = _plugins.value + (pluginId to current.copy(enabled = enabled))
        val next = if (enabled) disabledIds - pluginId else disabledIds + pluginId
        if (next == disabledIds) return
        disabledIds = next
        stateStore?.saveDisabledIds(next)
    }

    /** Ids currently remembered as disabled (persisted set plus this session's toggles). */
    fun disabledPluginIds(): Set<String> = disabledIds

    /**
     * Drop every plugin, the disabled set and the attached store. Unit tests only -- the
     * manager is a process-wide singleton, so each test starts from a clean slate.
     */
    fun resetForTests() {
        pluginMap.clear()
        _plugins.value = emptyMap()
        disabledIds = emptySet()
        stateStore = null
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

    /** Where external plugins are picked up from. */
    fun externalPluginDir(): java.io.File =
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "requestr/plugins")

    /**
     * Scan /sdcard/requestr/plugins/ for .jar and .dex files and
     * attempt to load Plugin implementations via reflection.
     *
     * Note: On modern Android, loading external DEX/JAR files requires
     * DexClassLoader. This is a best-effort mechanism — plugins MUST
     * implement the [Plugin] interface and declare the implementing
     * class in a META-INF/services/com.example.plugin.Plugin file.
     *
     * This is the first startup call that has a [Context] (MainActivity registers built-ins
     * first, then calls this), so it also attaches persistence, restoring the user's disabled
     * set before any external plugin registers. Returns exactly what the scan did.
     */
    fun scanExternalPlugins(context: Context): PluginScanResult {
        attachPersistence(context)
        val pluginDir = externalPluginDir()
        if (!pluginDir.exists()) {
            val created = pluginDir.mkdirs()
            return PluginScanResult(directory = pluginDir.path, directoryCreated = created, filesFound = 0)
        }

        val dexFiles = pluginDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".dex"))
        } ?: return PluginScanResult(directory = pluginDir.path, filesFound = 0, unreadable = true)

        val loaded = mutableListOf<String>()
        var alreadyLoaded = 0
        val failed = mutableListOf<String>()
        val notAPlugin = mutableListOf<String>()
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
                if (serviceFile == null) {
                    notAPlugin += dexFile.name
                    continue
                }
                val className = serviceFile.bufferedReader().readLine()?.trim()
                serviceFile.close()
                if (className.isNullOrEmpty()) {
                    notAPlugin += dexFile.name
                    continue
                }
                val clazz = loader.loadClass(className)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance !is Plugin) {
                    notAPlugin += dexFile.name
                    continue
                }
                when (registerPlugin(instance, PluginSource.EXTERNAL)) {
                    PluginRegistration.LOADED -> loaded += instance.id
                    PluginRegistration.ALREADY_REGISTERED -> alreadyLoaded++
                    PluginRegistration.FAILED -> failed += dexFile.name
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugin from ${dexFile.name}: ${e.message}", e)
                failed += dexFile.name
            }
        }
        return PluginScanResult(
            directory = pluginDir.path,
            filesFound = dexFiles.size,
            loaded = loaded,
            alreadyLoaded = alreadyLoaded,
            failed = failed,
            notAPlugin = notAPlugin,
        )
    }
}

/** Outcome of one [PluginManager.registerPlugin] call. */
enum class PluginRegistration { LOADED, ALREADY_REGISTERED, FAILED }

/**
 * What one [PluginManager.scanExternalPlugins] pass found and did. Every number is the
 * scanner's own count -- nothing is inferred by a caller from before/after sizes.
 */
data class PluginScanResult(
    val directory: String,
    val filesFound: Int,
    val loaded: List<String> = emptyList(),
    val alreadyLoaded: Int = 0,
    val failed: List<String> = emptyList(),
    val notAPlugin: List<String> = emptyList(),
    val directoryCreated: Boolean = false,
    val unreadable: Boolean = false,
) {
    /** One-line, user-facing summary for the Settings rescan row. */
    fun summary(): String = when {
        directoryCreated -> "Folder did not exist — created it. Drop a .jar or .dex there and rescan."
        unreadable -> "Folder could not be read — check storage permission for $directory."
        filesFound == 0 -> "Folder is empty — no .jar or .dex files found."
        else -> buildString {
            append("$filesFound file(s) found: ")
            val parts = mutableListOf<String>()
            if (loaded.isNotEmpty()) parts += "${loaded.size} new plugin(s) loaded"
            if (alreadyLoaded > 0) parts += "$alreadyLoaded already loaded"
            if (notAPlugin.isNotEmpty()) parts += "${notAPlugin.size} without a Plugin service file (${notAPlugin.joinToString()})"
            if (failed.isNotEmpty()) parts += "${failed.size} failed to load (${failed.joinToString()}; see logcat tag PluginManager)"
            append(parts.joinToString(", "))
            append(".")
        }
    }
}

/** Where the set of user-disabled plugin ids lives across restarts. */
interface PluginStateStore {
    /** Called once when attached; may block briefly (startup, before any hook dispatch). */
    fun loadDisabledIds(): Set<String>

    /** Called on every toggle with the complete new set. */
    fun saveDisabledIds(ids: Set<String>)
}

/**
 * Production [PluginStateStore] on [AppSettingsKeys.DISABLED_PLUGIN_IDS] in the unified
 * DataStore. The load is a single blocking read of the preferences file, made once during
 * MainActivity.onCreate -- the same moment the scanner already does synchronous file IO -- so
 * the disabled set is in place before the first hook can dispatch. Saves follow
 * SettingsRepository's fire-and-forget pattern.
 */
class DataStorePluginStateStore(private val appContext: Context) : PluginStateStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun loadDisabledIds(): Set<String> = runBlocking {
        appContext.unifiedDataStore.data.first()[AppSettingsKeys.DISABLED_PLUGIN_IDS] ?: emptySet()
    }

    override fun saveDisabledIds(ids: Set<String>) {
        scope.launch {
            appContext.unifiedDataStore.edit { it[AppSettingsKeys.DISABLED_PLUGIN_IDS] = ids }
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
