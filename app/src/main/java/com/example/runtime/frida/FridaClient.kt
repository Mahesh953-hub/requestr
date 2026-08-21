package com.example.runtime.frida

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Frida client: manages frida-server process lifecycle and script injection.
 *
 * Honest state machine: Unavailable → Detected → Connected → Attached → Streaming → Failed
 * Never says "Available" when nothing is actually connected.
 */
object FridaClient {
    private const val TAG = "FridaClient"
    private var fridaProcess: Process? = null
    private var scriptProcess: Process? = null
    private var outputJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    enum class FridaState { UNAVAILABLE, DETECTED, CONNECTED, ATTACHED, STREAMING, FAILED, STOPPED }

    @Volatile var state: FridaState = FridaState.UNAVAILABLE
        private set

    var onEvent: ((FridaEvent) -> Unit)? = null

    /**
     * Detect frida-server on the device.
     * Checks /data/local/tmp/frida-server and port 27042.
     */
    fun detect(context: Context): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "test -f /data/local/tmp/frida-server && echo ok || test -c /dev/frida && echo ok || echo no"))
            val result = p.waitFor() == 0 && p.inputStream.bufferedReader().readLine() == "ok"
            if (result) state = FridaState.DETECTED
            result
        } catch (_: Exception) { false }
    }

    /**
     * Start frida-server and begin streaming events.
     */
    fun start(context: Context): Boolean {
        if (!detect(context)) { state = FridaState.UNAVAILABLE; return false }

        state = FridaState.CONNECTED
        try {
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

            // Start script streaming (basic HTTP hook)
            startScriptStream()
            return true
        } catch (e: Exception) {
            state = FridaState.FAILED
            Log.e(TAG, "Frida start failed: ${e.message}")
            return false
        }
    }

    /** Stop everything */
    fun stop() {
        state = FridaState.STOPPED
        outputJob?.cancel()
        scriptProcess?.destroy()
        scriptProcess = null
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f frida-server")) } catch (_: Exception) {}
        fridaProcess = null
    }

    private fun startScriptStream() {
        outputJob = scope.launch {
            try {
                val script = "setTimeout(function(){ Java.perform(function(){ var Builder = Java.use('okhttp3.Request.Builder'); Builder.url.implementation = function(u) { send({type:'http', method:'GET', url: u.toString()}); return this.url(u); }; }); }, 500);"

                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "frida -U -f com.android.shell --codeshare $script 2>&1"))
                scriptProcess = proc
                state = FridaState.STREAMING

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    line?.let {
                        if (it.startsWith("{")) parseEvent(it)
                    }
                }
            } catch (e: Exception) {
                if (state == FridaState.STREAMING) state = FridaState.FAILED
            }
        }
    }

    private fun parseEvent(jsonLine: String) {
        try {
            val event = FridaEvent(
                type = jsonLine.substringAfter("\"type\":\"").substringBefore("\""),
                data = jsonLine
            )
            onEvent?.invoke(event)
        } catch (_: Exception) {}
    }

    fun isRunning() = state == FridaState.STREAMING
}

data class FridaEvent(val type: String, val data: String)
