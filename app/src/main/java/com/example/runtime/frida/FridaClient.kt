package com.example.runtime.frida

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Frida client: manages frida-server process lifecycle and script injection.
 *
 * Honest state machine: Unavailable -> Detected -> Connected -> Attached -> Streaming -> Failed
 * Never says "Available" when nothing is actually connected.
 *
 * The previous version of this file always spawned the fixed target `com.android.shell` and
 * passed a raw JS string to Frida's `--codeshare` flag -- `--codeshare` names a *hosted
 * Codeshare project slug*, not inline script text, so that call silently injected nothing real
 * (frida would either fail to resolve the "slug" or ignore it) while [FridaState] still reported
 * `STREAMING`. [start] now takes an explicit [FridaTarget] (spawn a real package, or attach by
 * pid/process name) and writes the caller's [script] to a real file under [Context.filesDir],
 * loaded via Frida's own `-l <file>` flag -- the actual injection mechanism.
 */
object FridaClient {
    private const val TAG = "FridaClient"

    /** How long [start] waits (after the frida CLI process itself is up) for the script's `armed` event. */
    private const val ARM_TIMEOUT_MS: Long = 10_000L

    private var fridaProcess: Process? = null
    private var scriptProcess: Process? = null
    private var outputJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Armed-gate state. [start] must not report success until the injected script itself emits
    // `armed` with `hooked: true` (the script always sends it as the last thing armAll() does).
    // resetArmGate() is called at the top of each start, parseEvent() drives the latch when it
    // sees an `armed` event, and awaitArmed() blocks (bounded) on it. This turns "frida CLI
    // launched" into a real signal that the hook actually installed instead of a false "active".
    @Volatile private var armedSucceeded = false
    @Volatile private var armedLatch = java.util.concurrent.CountDownLatch(1)

    enum class FridaState { UNAVAILABLE, DETECTED, CONNECTED, ATTACHED, STREAMING, FAILED, STOPPED }

    @Volatile var state: FridaState = FridaState.UNAVAILABLE
        private set

    /** What to inject [start]'s script into. */
    sealed class FridaTarget {
        /** Spawn a fresh process for [packageName] (Frida `-f`), held until the script attaches. */
        data class Spawn(val packageName: String) : FridaTarget()

        /** Attach to an already-running process by pid (Frida `-p`). */
        data class Pid(val pid: Int) : FridaTarget()

        /** Attach to an already-running process by its Frida-visible process name (Frida `-n`). */
        data class Name(val processName: String) : FridaTarget()
    }

    private val listeners = CopyOnWriteArrayList<(FridaEvent) -> Unit>()

    /**
     * Legacy single-callback seam. Assigning replaces nothing -- it just adds another listener,
     * same as [addListener] -- kept only so any existing `FridaClient.onEvent = { ... }` call
     * site keeps compiling; new code should call [addListener]/[removeListener] directly so
     * multiple consumers (e.g. more than one [InstrumentationAdapter]-style listener) can coexist
     * without clobbering each other, which a single nullable `var` could not support.
     */
    var onEvent: ((FridaEvent) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) listeners.add(value)
        }

    fun addListener(listener: (FridaEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (FridaEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Detect frida-server on the device.
     * Checks /data/local/tmp/frida-server and port 27042.
     */
    fun detect(context: Context): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "sh", "-c",
                    "test -f /data/local/tmp/frida-server && echo ok || test -c /dev/frida && echo ok || echo no",
                ),
            )
            val result = p.waitFor() == 0 && p.inputStream.bufferedReader().readLine() == "ok"
            if (result) state = FridaState.DETECTED
            result
        } catch (_: Exception) { false }
    }

    /**
     * Start frida-server and inject [script] into [target].
     *
     * [script] is written to a real file under [context]'s private storage (see [writeScriptFile])
     * and loaded via Frida's `-l` flag (see [buildFridaCommand]) -- never via `--codeshare`.
     */
    fun start(context: Context, target: FridaTarget, script: String): Boolean {
        if (!detect(context)) { state = FridaState.UNAVAILABLE; return false }

        state = FridaState.CONNECTED
        return try {
            // Start frida-server in background
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "/data/local/tmp/frida-server -D &"))
            fridaProcess = proc
            proc.waitFor()
            Thread.sleep(500)

            // Test connectivity
            val test = Runtime.getRuntime().exec(arrayOf("su", "-c", "frida-ps -U | head -1"))
            val ok = test.inputStream.bufferedReader().readText().contains("PID")
            if (!ok) { state = FridaState.FAILED; return false }
            state = FridaState.ATTACHED

            val scriptFile = writeScriptFile(context, script)
            resetArmGate()
            startScriptStream(target, scriptFile)
            // Do not report success merely because the frida CLI process launched. Wait (bounded)
            // for the script's own `armed` event confirming the hook actually installed somewhere;
            // a timeout, or `armed` with `hooked:false`, means no capture is happening and must be
            // reported as failure rather than a false "active"/STREAMING.
            if (awaitArmed(ARM_TIMEOUT_MS)) {
                true
            } else {
                Log.e(TAG, "Frida script never armed within ${ARM_TIMEOUT_MS}ms")
                state = FridaState.FAILED
                false
            }
        } catch (e: Exception) {
            state = FridaState.FAILED
            Log.e(TAG, "Frida start failed: ${e.message}")
            false
        }
    }

    /** Convenience: injects the built-in reflection-only OkHttp `Builder.build` hook (see [OkHttpBuilderHookScript]). */
    fun startOkHttpHook(context: Context, target: FridaTarget): Boolean =
        start(context, target, OkHttpBuilderHookScript.SOURCE)

    /** Stop everything */
    fun stop() {
        state = FridaState.STOPPED
        outputJob?.cancel()
        scriptProcess?.destroy()
        scriptProcess = null
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f frida-server")) } catch (_: Exception) {}
        fridaProcess = null
    }

    /** Writes [script] to a fresh file under `<filesDir>/frida_scripts/`. Real file, real content, no placeholder. */
    internal fun writeScriptFile(context: Context, script: String): File {
        val dir = File(context.filesDir, "frida_scripts")
        dir.mkdirs()
        // A monotonic counter alongside the timestamp guarantees distinct filenames even across
        // calls landing in the same millisecond (System.currentTimeMillis() alone is not fine
        // grained enough) -- two back-to-back writes must never silently collide/overwrite.
        val file = File(dir, "hook_${System.currentTimeMillis()}_${scriptFileCounter.incrementAndGet()}.js")
        file.writeText(script)
        return file
    }

    private val scriptFileCounter = AtomicLong(0)

    /**
     * Builds the real `frida` CLI argv for [target] loading [scriptFile] via `-l` -- pure and
     * unit-testable without spawning any process, unlike the rest of [start].
     */
    internal fun buildFridaCommand(target: FridaTarget, scriptFile: File): List<String> {
        val base = listOf("frida", "-U", "-l", scriptFile.absolutePath)
        return when (target) {
            is FridaTarget.Spawn -> base + listOf("-f", target.packageName, "--no-pause")
            is FridaTarget.Pid -> base + listOf("-p", target.pid.toString())
            is FridaTarget.Name -> base + listOf("-n", target.processName)
        }
    }

    private fun startScriptStream(target: FridaTarget, scriptFile: File) {
        outputJob = scope.launch {
            try {
                val command = buildFridaCommand(target, scriptFile)
                val shellCommand = command.joinToString(" ") { shellQuote(it) }
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "$shellCommand 2>&1"))
                scriptProcess = proc
                state = FridaState.STREAMING

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    line?.let { if (it.trimStart().startsWith("{")) parseEvent(it) }
                }
            } catch (e: Exception) {
                if (state == FridaState.STREAMING) state = FridaState.FAILED
            }
        }
    }

    private fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Parses one line of Frida CLI output that looked like a JSON `send()` message. Accepts both
     * a bare payload (`{"type":"...",...}`) and Frida's own message envelope
     * (`{"type":"send","payload":{...}}`), unwrapping the latter -- never throws, silently drops
     * anything that isn't real JSON.
     */
    internal fun parseEvent(jsonLine: String): FridaEvent? {
        val event = try {
            val json = org.json.JSONObject(jsonLine)
            val payload = json.optJSONObject("payload") ?: json
            FridaEvent(type = payload.optString("type", "unknown"), data = payload.toString())
        } catch (_: Exception) {
            null
        }
        event?.let { e ->
            // Drive the start() armed-gate: the script always sends a final `armed` event with a
            // `hooked` boolean (true only when at least one Builder hook actually installed). Only
            // `hooked:true` satisfies the gate; `armed` is also always sent (with hooked:false) on
            // total failure, which is what lets start() distinguish "no hook found" from "never
            // finished arming at all" (pure timeout).
            if (e.type == "armed") {
                val hooked = runCatching {
                    org.json.JSONObject(e.data).optBoolean("hooked", false)
                }.getOrDefault(false)
                if (hooked) armedSucceeded = true
                armedLatch.countDown()
            }
            listeners.forEach { it(e) }
        }
        return event
    }

    private fun resetArmGate() {
        armedSucceeded = false
        armedLatch = java.util.concurrent.CountDownLatch(1)
    }

    private fun awaitArmed(timeoutMs: Long): Boolean {
        return try {
            armedLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            armedSucceeded
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            armedSucceeded
        }
    }

    // Test seams only. [start]'s process spawning cannot run in a JVM unit test, but the armed
    // gate itself (parseEvent driving armedSucceeded + a latch) can be exercised via parseEvent.
    internal fun resetArmGateForTest() = resetArmGate()

    internal fun lastArmSucceeded() = armedSucceeded

    fun isRunning() = state == FridaState.STREAMING
}

data class FridaEvent(val type: String, val data: String)
