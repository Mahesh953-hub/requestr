package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Real Frida capture engine.
 *
 * Manages Frida script injection into target processes via the frida-server
 * daemon running on the device. Scripts are written to app-private storage,
 * injected via `frida -l script.js <target>`, and output is streamed back.
 *
 * Requires: frida-server binary accessible via PATH on device (from Magisk
 * module or manual push to /data/local/tmp).
 */
class FridaCaptureEngine {
    private var activeProcess: Process? = null
    private var active = false
    private var sessionId = 0L
    private val scriptDir by lazy { File("/data/data/com.kailashhh.requestr/files/frida_scripts") }

    companion object {
        private const val TAG = "FridaEngine"
    }

    /** Check if frida-server is reachable. */
    fun isAvailable(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "which frida-server"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(3, TimeUnit.SECONDS)
            out.contains("frida-server")
        } catch (_: Exception) { false }
    }

    /** Write a Frida script and inject into target process. */
    fun start(script: String, targetPid: Int): Boolean {
        if (active) return false
        sessionId = System.currentTimeMillis()

        return runCatching {
            scriptDir.mkdirs()
            val scriptFile = File(scriptDir, "hook_${sessionId}.js")
            scriptFile.writeText(script)

            val cmd = "frida -p $targetPid -l ${scriptFile.absolutePath} --no-pause"
            activeProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))

            active = true
            InterceptionStateRepository.markActive(
                sessionId = sessionId, tier = ActiveTier.ROOT,
                port = 0, method = CaptureMethod.ROOT_FRIDA,
            )

            // Background reader for Frida output
            Thread { readFridaOutput() }.start()

            Log.i(TAG, "Frida injected into PID $targetPid")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Frida start failed: ${e.message}")
            InterceptionStateRepository.markError("Frida: ${e.message}")
            false
        }
    }

    fun stop() {
        activeProcess?.destroy()
        activeProcess = null
        active = false
        Log.i(TAG, "Frida engine stopped")
    }

    fun isActive(): Boolean = active

    /** Verify frida-server is alive. */
    fun verify(): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof frida-server"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(3, TimeUnit.SECONDS)
            out.trim().isNotBlank()
        }.getOrDefault(false)
    }

    /** Built-in script templates for common hook patterns. */
    fun builtinScript(name: String): String = when (name.lowercase()) {
        "okhttp_pin" -> okHttpPinBypass
        "trustmanager" -> trustManagerBypass
        "cert_pinner" -> certPinnerBypass
        "http_log" -> httpLogHook
        "jni_trace" -> jniTrace
        // Real capture, not a bypass: the reflection-only OkHttp Builder.build hook
        // (com.example.runtime.frida.OkHttpBuilderHookScript) that installs a capturing
        // application interceptor and send()s post-reassembly request/response bodies --
        // see com.example.runtime.frida.FridaOkHttpAdapter for the Kotlin-side consumer that
        // feeds these events into RuntimeInstrumentationEngine.
        "okhttp_capture" -> com.example.runtime.frida.OkHttpBuilderHookScript.SOURCE
        else -> "// Unknown builtin: $name"
    }

    private fun readFridaOutput() {
        try {
            val reader = BufferedReader(InputStreamReader(activeProcess?.inputStream ?: return))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "Frida: $line")
                InterceptionStateRepository.updateCounters()
            }
        } catch (_: Exception) { /* process died */ }
    }

    // ── Built-in scripts ────────────────────────────────────────────────

    private val okHttpPinBypass = """
        Java.perform(function() {
            var CPBuilder = Java.use("okhttp3.CertificatePinner" + "$" + "Builder");
            CPBuilder.add.overload("java.lang.String", "[Ljava.lang.String;")
                .implementation = function() { return this; };
            console.log("[requestr] OkHttp CertificatePinner bypassed");
        });
    """.trimIndent()

    private val trustManagerBypass = """
        Java.perform(function() {
            var TrustManager = Java.use("javax.net.ssl.X509TrustManager");
            var SSLContext = Java.use("javax.net.ssl.SSLContext");
            var TrustAll = Java.registerClass({
                name: "com.requestr.TrustAllCerts",
                implements: [TrustManager],
                methods: {
                    checkClientTrusted: function() {},
                    checkServerTrusted: function() {},
                    getAcceptedIssuers: function() { return []; }
                }
            });
            console.log("[requestr] TrustManager bypass active");
        });
    """.trimIndent()

    private val certPinnerBypass = """
        setTimeout(function() {
            Java.perform(function() {
                var Pinner = Java.use("okhttp3.CertificatePinner");
                Pinner.check.overload("java.lang.String", "java.util.List")
                    .implementation = function(host, certs) {
                        console.log("[requestr] Pinner bypass: " + host);
                        return;
                    };
            });
        }, 100);
    """.trimIndent()

    private val httpLogHook = """
        Java.perform(function() {
            var Request = Java.use("okhttp3.Request");
            var Response = Java.use("okhttp3.Response");
            Request.toString.implementation = function() {
                var s = this.toString.call(this);
                send({type: "request", url: this.url().toString(),
                     method: this.method(), headers: this.headers().toString()});
                return s;
            };
            console.log("[requestr] HTTP log hook active");
        });
    """.trimIndent()

    private val jniTrace = """
        const moduleName = "libnative-lib.so";
        const exports = Module.enumerateExports(moduleName);
        exports.forEach(function(exp) {
            if (exp.type === "function") {
                Interceptor.attach(exp.address, {
                    onEnter: function(args) {
                        console.log("[JNI] " + exp.name + "()");
                    }
                });
            }
        });
        console.log("[requestr] JNI trace active on " + moduleName);
    """.trimIndent()
}
