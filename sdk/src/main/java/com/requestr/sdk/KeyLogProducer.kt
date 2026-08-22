package com.requestr.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import javax.net.ssl.SSLSocket

/**
 * Task 8: produces TLS/QUIC key material into `com.example.interception.tls.TlsKeyLogRegistry`
 * so requestr's own decoders (which passively observe the wire, and cannot compute TLS 1.3 /
 * QUIC 1-RTT secrets without endpoint key material) can decrypt traffic this SDK's host app
 * generates.
 *
 * **Cross-module dependency, resolved via reflection, not a compile-time dependency.**
 * `TlsKeyLogRegistry` is owned by requestr's own `:app` module (`com.example.interception.tls`
 * package) -- this `:sdk` module is built to be embeddable standalone in a *third-party* app that
 * has never heard of requestr's `:app` module and never will at compile time. So instead of an
 * `implementation(project(":app"))` dependency (which would be backwards -- the SDK is the thing
 * apps depend on, not the other way around), every call into the registry goes through
 * [KeyLogRegistryBridge], a small reflective shim resolved once via `Class.forName`. If the host
 * app is requestr's own `:app` module (or anything else that happens to have that class on its
 * classpath), the calls work for real; if not (the common case -- a third-party app with no
 * knowledge of requestr's internals), every call becomes a harmless no-op and this whole file
 * degrades gracefully rather than throwing `ClassNotFoundException`. Either way, [OkHttpInterceptor]
 * and friends still capture and forward fully decrypted plaintext bodies via the app-layer
 * interceptor -- keylog production is purely a *bonus* signal for requestr's independent
 * QUIC/TLS-level decoders, never a prerequisite for body capture.
 */
object KeyLogProducer {
    private const val TAG = "RequestrKeyLogProducer"
    private var monitorJob: Job? = null

    /**
     * **8a. Cronet keylog path.** [CronetInterceptor.addRequestrInterceptor] already configures
     * Cronet's native engine to append NSS-format keylog lines (`ssl_key_log_file` experimental
     * option) to [CronetInterceptor.KEYLOG_FILE_NAME] as every TLS/QUIC session it negotiates
     * completes its handshake. This starts a lightweight poll loop that tails that file and
     * imports newly-appended lines into the registry.
     *
     * Polling (not `FileObserver`) is deliberate: `FileObserver`'s `MODIFY` event doesn't fire
     * reliably for append-only writes on every OEM's filesystem/kernel combination, whereas a
     * simple "did the file grow" check via [File.length] is universally correct, if slightly
     * less immediate (2s worst-case latency, acceptable for a debugging/observability feature).
     */
    fun startCronetKeyLogMonitor(context: Context, scope: CoroutineScope) {
        val keylogFile = File(context.filesDir, CronetInterceptor.KEYLOG_FILE_NAME)
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            var lastSize = keylogFile.length()
            while (isActive) {
                kotlinx.coroutines.delay(2_000)
                val (newLines, newSize) = readNewLines(keylogFile, lastSize)
                lastSize = newSize
                if (newLines.isNotEmpty()) {
                    runCatching {
                        val accepted = KeyLogRegistryBridge.importNssKeyLog(newLines.asSequence())
                        Log.d(TAG, "Cronet keylog monitor imported $accepted new line(s)")
                    }.onFailure { e -> Log.w(TAG, "Cronet keylog monitor read failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Reads whatever new, complete lines have been appended to [file] since [lastSize] bytes
     * were already consumed, returning them plus the file's new total size to remember as the
     * next call's [lastSize]. Split out from [startCronetKeyLogMonitor]'s poll loop so the
     * tailing/diffing logic itself -- which is the part actually worth unit-testing -- can be
     * exercised directly against a real temp file without needing a live coroutine scope or the
     * (in a JVM unit test, always-unavailable) [KeyLogRegistryBridge].
     *
     * Handles truncation/rotation (e.g. the host app restarted Cronet and it recreated the
     * keylog file from scratch) by restarting from offset 0 rather than seeking past EOF forever.
     */
    internal fun readNewLines(file: File, lastSize: Long): Pair<List<String>, Long> {
        val currentSize = runCatching { file.length() }.getOrDefault(lastSize)
        if (currentSize < lastSize) {
            // Truncated/rotated -- restart from the beginning on the next read.
            return emptyList<String>() to 0L
        }
        if (currentSize == lastSize) return emptyList<String>() to lastSize

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(lastSize)
                val newBytes = ByteArray((currentSize - lastSize).toInt())
                raf.readFully(newBytes)
                val newLines = String(newBytes).lines().filter { it.isNotBlank() }
                newLines to currentSize
            }
        }.getOrElse { e ->
            Log.w(TAG, "Cronet keylog monitor read failed: ${e.message}")
            emptyList<String>() to lastSize
        }
    }

    fun stopCronetKeyLogMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * **8b. OkHttp/Conscrypt reflection path.** OkHttp on Android negotiates TLS through
     * Conscrypt, which does not expose the raw master secret / traffic secrets through the
     * public [javax.net.ssl.SSLSession] API. [SslSecretExtractor] cargo-cults the reflection
     * chain used by other Android TLS-interception tooling (HTTP Toolkit's agent, PCAPdroid,
     * etc.) to pull the client random + derived secrets back out of Conscrypt's internal
     * objects, then records them here in NSS-line form.
     *
     * Called from [RequestrEventListener.secureConnectEnd] once the handshake completes on
     * an [SSLSocket]-based connection (OkHttp's Conscrypt path always is one).
     */
    fun recordFromSslSocket(socket: SSLSocket) {
        val secrets = SslSecretExtractor.extractTlsSecrets(socket)
        if (secrets.isNullOrEmpty()) return
        val clientRandom = SslSecretExtractor.extractClientRandom(socket) ?: return
        for ((label, secret) in secrets) {
            KeyLogRegistryBridge.record(label, clientRandom, secret)
        }
    }
}

/**
 * Reflective bridge into `com.example.interception.tls.TlsKeyLogRegistry` -- see [KeyLogProducer]
 * kdoc for why this is reflection-based rather than a normal Kotlin import. Resolution happens
 * once, lazily, and every subsequent call is a cheap reflective method invocation (or a no-op if
 * resolution failed, e.g. because the host app is a real third-party app that doesn't have
 * requestr's `:app` module on its classpath at all -- the overwhelmingly common case for this
 * SDK).
 */
internal object KeyLogRegistryBridge {
    private const val TAG = "RequestrKeyLogBridge"
    private const val REGISTRY_CLASS = "com.example.interception.tls.TlsKeyLogRegistry"
    private const val LABEL_CLASS = "com.example.interception.tls.KeyLogLabel"

    private val resolved: Resolved? by lazy { resolve() }

    private class Resolved(
        val registryInstance: Any,
        val recordMethod: java.lang.reflect.Method,
        val importMethod: java.lang.reflect.Method,
        val labelEnumConstants: Map<String, Any>,
    )

    private fun resolve(): Resolved? {
        return try {
            val registryClass = Class.forName(REGISTRY_CLASS)
            val labelClass = Class.forName(LABEL_CLASS)
            // TlsKeyLogRegistry is a Kotlin `object` -- its singleton instance lives on the
            // synthesized static `INSTANCE` field.
            val instanceField = registryClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val instance = requireNotNull(instanceField.get(null)) { "TlsKeyLogRegistry.INSTANCE was null" }

            val recordMethod = registryClass.getDeclaredMethod(
                "record",
                labelClass,
                ByteArray::class.java,
                ByteArray::class.java,
            )
            recordMethod.isAccessible = true

            val importMethod = registryClass.getDeclaredMethod("importNssKeyLog", kotlin.sequences.Sequence::class.java)
            importMethod.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val enumConstants = (labelClass.enumConstants as Array<Any>).associateBy { (it as Enum<*>).name }

            Resolved(instance, recordMethod, importMethod, enumConstants)
        } catch (e: Throwable) {
            // ClassNotFoundException is the expected/common case (a third-party host app with
            // no knowledge of requestr's :app module). NoSuchMethodException/NoSuchFieldException
            // would mean the frozen contract changed underneath us -- log louder for that, but
            // either way, degrade to a no-op rather than crash the host app.
            Log.d(TAG, "TlsKeyLogRegistry not available on this classpath -- keylog production disabled: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** [labelName] must match one of `KeyLogLabel`'s frozen enum constant names exactly
     * (e.g. `"CLIENT_HANDSHAKE_TRAFFIC_SECRET"`). Unrecognised names are silently ignored --
     * mirrors the registry's own "never throw on bad input" contract. */
    fun record(labelName: String, clientRandom: ByteArray, secret: ByteArray) {
        val r = resolved ?: return
        val label = r.labelEnumConstants[labelName] ?: return
        runCatching { r.recordMethod.invoke(r.registryInstance, label, clientRandom, secret) }
            .onFailure { e -> Log.w(TAG, "record() reflective call failed: ${e.message}") }
    }

    fun importNssKeyLog(lines: Sequence<String>): Int {
        val r = resolved ?: return 0
        return runCatching { r.importMethod.invoke(r.registryInstance, lines) as? Int ?: 0 }
            .getOrElse { e ->
                Log.w(TAG, "importNssKeyLog() reflective call failed: ${e.message}")
                0
            }
    }

    /** True once real resolution against `TlsKeyLogRegistry` has succeeded -- lets tests and
     * callers distinguish "keylog genuinely produced" from "silently degraded, no such class". */
    fun isAvailable(): Boolean = resolved != null
}
