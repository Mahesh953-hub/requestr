package com.example.scripting

/**
 * Shared plumbing behind the Kotlin `__bridge` object [PmPrelude]'s JS calls into (see
 * [JsScriptEngine.eval]). Holds everything that behaves identically no matter which concrete
 * request/response representation is being scripted -- `pm.environment`/`pm.globals` storage,
 * `pm.test()` recording, `console.*` capture, and the `{{var}}`/`{{$name}}` templating shared by
 * every setter in [PmPrelude] (`pm.request.url = ...`, `pm.request.headers.set(...)`, etc.).
 *
 * The two real call sites plug in wildly different request/response shapes -- ApiTestr's
 * pre/post scripts mutate a not-yet-sent [com.example.data.models.ApiRequestItem] and read a
 * just-received [com.example.data.models.ApiResponseData] (see `ApiTestrScriptRunner`), while a
 * live `SCRIPT_REWRITE` rule ([com.example.interception.RuleEnforcer.applyScriptRewrite]) mutates
 * a single in-flight [com.example.interception.http.ParsedHttpMessage] in place, dispatching on
 * whether it is a request or a response -- so those accessors are `abstract` here rather than
 * forcing one concrete representation on both.
 */
abstract class PmBridgeBase(initialEnvVars: Map<String, String>) {

    private val envVars = LinkedHashMap(initialEnvVars)
    /**
     * S4 task 32: `context.shared` -- the per-flow scratch object that survives from a script's
     * `onRequest` into the same flow's `onResponse`. Seeded by the caller (from the request-leg
     * result, or the envelope in the remote host) and read back after the run via [sharedContext].
     * Values are strings on the Kotlin side; JS sees a plain object (non-strings are stringified).
     */
    private val shared = LinkedHashMap<String, String>()

    /** S4 task 32: `"onRequest"` / `"onResponse"` (or blank for ApiTestr scripts) -- read by the prelude as `__phase`. */
    var phase: String = ""
    fun phaseName(): String = phase

    /** The shared context as it stood at the end of the run. */
    val sharedContext: Map<String, String> get() = shared.toMap()

    fun seedShared(values: Map<String, String>) { shared.clear(); shared.putAll(values) }

    /** JSON object the prelude parses into `context.shared`. */
    fun sharedJson(): String = com.squareup.moshi.Moshi.Builder().build()
        .adapter<Map<String, String>>(com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))
        .toJson(shared)

    /** Written back by the prelude epilogue: `JSON.stringify(context.shared)`. */
    fun sharedPut(json: String) {
        val parsed = runCatching {
            com.squareup.moshi.Moshi.Builder().build()
                .adapter<Map<String, Any?>>(com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
                .fromJson(json)
        }.getOrNull() ?: return
        shared.clear()
        for ((k, v) in parsed) {
            if (v == null) continue
            shared[k] = when (v) {
                is String -> v
                is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
                else -> v.toString()
            }
        }
    }
    private val testResults = mutableListOf<PmTestResult>()
    private val consoleLines = mutableListOf<String>()

    /** Every `pm.test()` result recorded during the run, in call order. */
    val recordedTests: List<PmTestResult> get() = testResults.toList()

    /** Every `console.log`/`warn`/`error` line captured during the run, in call order. */
    val consoleOutput: List<String> get() = consoleLines.toList()

    /**
     * The env-variable map as it stood at the end of the run (after every `pm.environment.set`/
     * `unset`/`pm.variables.set` call). Callers write this back onto the real
     * [com.example.data.env.Environment] (ApiTestr) or just discard it (a `SCRIPT_REWRITE` rule
     * has no environment to write back to -- its `variables` map is config, not env storage).
     */
    val finalEnvVars: Map<String, String> get() = envVars.toMap()

    // ---- pm.environment ----------------------------------------------------------------
    fun envGet(key: String): String? = envVars[key]
    fun envSet(key: String, value: String) { envVars[key] = value }
    fun envUnset(key: String) { envVars.remove(key) }
    fun envHas(key: String): Boolean = envVars.containsKey(key)
    fun envKeys(): Array<String> = envVars.keys.toTypedArray()

    // ---- pm.globals (process-wide, see GlobalVariableStore) ----------------------------
    fun globalGet(key: String): String? = GlobalVariableStore.get(key)
    fun globalSet(key: String, value: String) { GlobalVariableStore.set(key, value) }
    fun globalUnset(key: String) { GlobalVariableStore.unset(key) }
    fun globalHas(key: String): Boolean = GlobalVariableStore.has(key)
    fun globalKeys(): Array<String> = GlobalVariableStore.keys().toTypedArray()

    // ---- pm.test / console --------------------------------------------------------------
    fun recordTest(name: String, passed: Boolean, message: String) {
        testResults.add(PmTestResult(name, passed, message))
    }

    fun consoleLog(message: String) {
        consoleLines.add(message)
    }

    /**
     * Shared `{{var}}`/`{{$name}}` templating applied by every mutating setter in [PmPrelude]
     * (`pm.request.url = ...`, `pm.request.headers.set(name, value)`, `pm.response.body = ...`,
     * ...). Order: `{{$name}}` dynamic generators first (via [DynamicVariables.substitute]),
     * then the current env vars, then any global that isn't shadowed by an env var of the same
     * name -- matching [com.example.data.env.EnvironmentRepository.substitute]'s precedence.
     */
    fun templateString(value: String): String {
        var result = DynamicVariables.substitute(value)
        envVars.forEach { (key, v) -> result = result.replace("{{$key}}", v) }
        GlobalVariableStore.snapshot().forEach { (key, v) ->
            if (!envVars.containsKey(key)) result = result.replace("{{$key}}", v)
        }
        return result
    }

    // ---- pm.request -----------------------------------------------------------------------
    abstract fun requestGetUrl(): String
    abstract fun requestSetUrl(value: String)
    abstract fun requestGetMethod(): String
    abstract fun requestSetMethod(value: String)
    abstract fun requestGetHeader(name: String): String?
    abstract fun requestSetHeader(name: String, value: String)
    abstract fun requestRemoveHeader(name: String)
    abstract fun requestHeaderNames(): Array<String>
    abstract fun requestGetBody(): String
    abstract fun requestSetBody(value: String)

    // ---- pm.response ------------------------------------------------------------------------
    abstract fun responseGetStatusCode(): Int
    abstract fun responseSetStatusCode(value: Int)
    abstract fun responseGetStatusMessage(): String
    abstract fun responseGetHeader(name: String): String?
    abstract fun responseSetHeader(name: String, value: String)
    abstract fun responseRemoveHeader(name: String)
    abstract fun responseHeaderNames(): Array<String>
    abstract fun responseGetBody(): String
    abstract fun responseSetBody(value: String)
    abstract fun responseGetResponseTimeMs(): Long
}
