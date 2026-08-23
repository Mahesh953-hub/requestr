package com.requestr.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import javax.net.ssl.SSLSocket

/**
 * Task 8 / Task 13 (CAT-2d): produces TLS/QUIC key material into
 * `com.example.interception.tls.TlsKeyLogRegistry` so requestr's own decoders (which passively
 * observe the wire, and cannot compute TLS 1.3 / QUIC 1-RTT secrets without endpoint key
 * material) can decrypt traffic this SDK's host app generates.
 *
 * **PRIMARY path: authenticated cross-process HTTP.** [postLines] POSTs NSS-format keylog lines
 * to requestr's own `POST /api/keylog` endpoint (`com.example.api.LocalApiServer`, in the `:app`
 * module -- not visible from here) with the pairing `Authorization: Bearer <token>` header
 * configured via [init], exactly like [RequestrObserver] already POSTs observed traffic to
 * `/api/observe`. This is the mechanism that actually works when this SDK is embedded in a real
 * third-party host app, which never shares a process -- let alone a classpath -- with requestr's
 * own `:app` module. Both producers below (Cronet keylog tailing and [SslSecretExtractor]
 * reflection) flow through it.
 *
 * **OPTIONAL fast path: in-process reflection.** [KeyLogRegistryBridge] resolves
 * `TlsKeyLogRegistry` via `Class.forName` and, when it happens to be on the classpath (i.e. this
 * SDK is embedded inside requestr's own `:app` module itself, or a test harness that put it
 * there), records the same material directly in-process with no HTTP round trip. This used to be
 * the *sole* mechanism -- which made it a permanent no-op for every real third-party host app,
 * since such an app never has requestr's `:app` classes on its classpath at all. It is kept only
 * as a bonus, same-process fast path now: harmless when unavailable (the common case), and
 * redundant-but-idempotent with the HTTP path when it does resolve.
 */
object KeyLogProducer {
    private const val TAG = "RequestrKeyLogProducer"
    private var monitorJob: Job? = null

    /** HTTP posting target + credential for the PRIMARY path, configured once via [init] (mirrors
     *  [RequestrObserver.init]'s own `apiBase`/`pairingToken` fields). Until [init] is called with
     *  a non-null token, [postLines] logs and drops -- the same "no-op until configured" contract
     *  [RequestrObserver.observe] already has, so a host app that never opts into keylog
     *  production never sees a network call attempted at all. */
    @Volatile private var apiBase: String = "http://127.0.0.1:9876"
    @Volatile private var pairingToken: String? = null
    private val httpClient = OkHttpClient()

    // Own SupervisorJob-scoped CoroutineScope for posting, independent of whatever scope the
    // Cronet monitor runs under -- same "not GlobalScope, never block the caller" rationale as
    // RequestrObserver's own `scope`. The callers of postLines (an OkHttp EventListener callback
    // thread for recordFromSslSocket, or this object's own poll loop) must never block on
    // network I/O themselves.
    private val postScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Configures the PRIMARY HTTP posting target. Called once by [RequestrSdk.init] when
     * `Options.enableKeyLog` is true, passing through the same `apiBaseUrl`/`pairingToken` that
     * [RequestrObserver.init] receives. Safe to call again (e.g. from a test) -- last call wins,
     * same as [RequestrObserver.init].
     */
    fun init(apiBaseUrl: String, pairingToken: String?) {
        apiBase = apiBaseUrl
        this.pairingToken = pairingToken
    }

    /**
     * **8a. Cronet keylog path.** [CronetInterceptor.addRequestrInterceptor] already configures
     * Cronet's native engine to append NSS-format keylog lines (`ssl_key_log_file` experimental
     * option) to [CronetInterceptor.KEYLOG_FILE_NAME] as every TLS/QUIC session it negotiates
     * completes its handshake. This starts a lightweight poll loop that tails that file and
     * forwards newly-appended lines to both producer paths via [postLines] (primary) and the
     * reflective bridge (optional fast path).
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
                    // PRIMARY: cross-process HTTP path -- see class kdoc.
                    postLines(newLines)
                    // OPTIONAL: same-process fast path, harmless no-op when unavailable.
                    runCatching {
                        val accepted = KeyLogRegistryBridge.importNssKeyLog(newLines.asSequence())
                        Log.d(TAG, "Cronet keylog monitor imported $accepted new line(s) via same-process fast path")
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
     * objects, then hands them to [recordSecrets].
     *
     * Called from [RequestrEventListener.secureConnectEnd] once the handshake completes on
     * an [SSLSocket]-based connection (OkHttp's Conscrypt path always is one).
     */
    fun recordFromSslSocket(socket: SSLSocket) {
        val secrets = SslSecretExtractor.extractTlsSecrets(socket)
        if (secrets.isNullOrEmpty()) return
        val clientRandom = SslSecretExtractor.extractClientRandom(socket) ?: return
        recordSecrets(clientRandom, secrets)
    }

    /**
     * Records [secrets] (NSS label name to raw secret bytes) observed for [clientRandom] through
     * both producer paths: [postLines] (PRIMARY, authenticated HTTP) and [KeyLogRegistryBridge]
     * (OPTIONAL, same-process reflection). Split out from [recordFromSslSocket] so the part with
     * actual logic worth testing -- building the exact NSS line format and fanning out to both
     * paths -- is exercisable with plain byte arrays, without needing a real or hand-built fake
     * [SSLSocket]/[javax.net.ssl.SSLSession] object graph (see [SslSecretExtractor]'s own tests
     * for why building one of those is nontrivial).
     */
    internal fun recordSecrets(clientRandom: ByteArray, secrets: List<Pair<String, ByteArray>>) {
        if (secrets.isEmpty()) return
        val clientRandomHex = clientRandom.toNssHex()
        postLines(secrets.map { (label, secret) -> "$label $clientRandomHex ${secret.toNssHex()}" })
        for ((label, secret) in secrets) {
            KeyLogRegistryBridge.record(label, clientRandom, secret)
        }
    }

    /**
     * **PRIMARY producer path (Task 13 / CAT-2d).** POSTs [lines] (each already in NSS keylog
     * line format: `LABEL <64-hex client_random> <hex secret>`) to requestr's authenticated
     * `POST /api/keylog` endpoint as `{"lines": [...]}`, with `Authorization: Bearer
     * <pairingToken>` -- the exact shape and header `com.example.api.LocalApiServer`'s
     * `receiveKeylogLines` (not visible from this module) expects, mirroring the same
     * pairing-Bearer contract `/api/observe` already uses via [RequestrObserver.flush].
     *
     * Fire-and-forget on [postScope] -- never blocks the caller (an OkHttp `EventListener`
     * callback thread for [recordSecrets], or this object's own poll loop for the Cronet
     * monitor). A missing [pairingToken] ([init] never called with one), a non-2xx response, or
     * any I/O failure is logged and dropped, never thrown -- keylog production is a bonus signal
     * for requestr's independent wire-level decoders, never allowed to crash or block the host
     * app's real traffic. `internal` (not `private`) so [KeyLogProducerHttpTest] can drive it
     * directly with plain line strings, the same rationale [readNewLines] is `internal` for.
     */
    internal fun postLines(lines: List<String>) {
        if (lines.isEmpty()) return
        val token = pairingToken
        if (token == null) {
            Log.d(TAG, "postLines() skipped -- KeyLogProducer.init() was never called with a pairing token")
            return
        }
        postScope.launch {
            runCatching {
                val payload = JSONObject().put("lines", JSONArray(lines)).toString()
                val request = Request.Builder()
                    .url("$apiBase/api/keylog")
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "keylog POST rejected (HTTP ${response.code})")
                    }
                }
            }.onFailure { e -> Log.w(TAG, "keylog POST failed: ${e.message}") }
        }
    }

    private fun ByteArray.toNssHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Test-only: resets HTTP-posting configuration ([apiBase]/[pairingToken]) back to their
     *  just-constructed defaults, so each test in [KeyLogProducerHttpTest] runs independent of
     *  whichever other test happened to run first in the same JVM -- this object is a
     *  process-wide singleton, same rationale as [RequestrSdk.resetForTests]. Never call this
     *  from production code. */
    internal fun resetForTests() {
        apiBase = "http://127.0.0.1:9876"
        pairingToken = null
    }
}

/**
 * Reflective bridge into `com.example.interception.tls.TlsKeyLogRegistry` -- see [KeyLogProducer]
 * kdoc for why this is reflection-based rather than a normal Kotlin import, and why it is now an
 * OPTIONAL same-process fast path rather than the sole producer mechanism. Resolution happens
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
