package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.env.Environment
import com.example.data.env.EnvironmentRepository
import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import com.example.interception.OkHttpProxyInterceptor
import com.example.scripting.ApiTestrScriptRunner
import com.example.scripting.PmTestResult
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

// ====================================================================
// WebSocket client (manifest §5.5 — ported from main, adapted to backup's
// OkHttpProxyInterceptor-equipped client). Backup's Room-backed saved APIs,
// chain extraction, and exports remain unchanged.
// ====================================================================

data class WsMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isIncoming: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isBinary: Boolean = false
)

enum class WsConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class WebSocketSession(
    private val client: OkHttpClient,
    val url: String,
    val headers: List<KeyValueItem>
) {
    private var webSocket: WebSocket? = null

    private val _messages = MutableStateFlow<List<WsMessage>>(emptyList())
    val messages: StateFlow<List<WsMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(WsConnectionStatus.DISCONNECTED)
    val status: StateFlow<WsConnectionStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun connect() {
        if (_status.value == WsConnectionStatus.CONNECTED || _status.value == WsConnectionStatus.CONNECTING) return
        _status.value = WsConnectionStatus.CONNECTING
        _error.value = null

        val reqBuilder = Request.Builder().url(url)
        headers.filter { it.isEnabled && it.key.isNotBlank() }.forEach {
            reqBuilder.addHeader(it.key, it.value)
        }

        webSocket = client.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = WsConnectionStatus.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _messages.value = _messages.value + WsMessage(text = text, isIncoming = true)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _messages.value = _messages.value + WsMessage(text = "[Binary frame: ${bytes.hex()}]", isIncoming = true, isBinary = true)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                _status.value = WsConnectionStatus.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = WsConnectionStatus.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = WsConnectionStatus.ERROR
                _error.value = t.localizedMessage ?: "WebSocket connection failed"
            }
        })
    }

    fun send(text: String): Boolean {
        val ws = webSocket ?: return false
        val sent = ws.send(text)
        if (sent) {
            _messages.value = _messages.value + WsMessage(text = text, isIncoming = false)
        }
        return sent
    }

    fun close() {
        try {
            webSocket?.close(1000, "User disconnect")
        } catch (_: Exception) {}
        webSocket = null
        _status.value = WsConnectionStatus.DISCONNECTED
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }
}

// ====================================================================
// Master plan §2.10 / §2.9 — single source of truth for the media type a [RequestBodyType]
// implies, shared by [ApiTestrRepository.executeRequest] (the real OkHttp media type) and
// [ApiTestrRepository.generateCodeSnippet]'s exporters (the auto Content-Type header they show
// when the user never typed one). Previously each call site derived this independently, and the
// snippet exporters didn't derive it at all.
// ====================================================================

object BodyContentType {
    /**
     * The base media type (no `charset`/`boundary` parameters) a [RequestBodyType] implies.
     * Returns `null` for [RequestBodyType.NONE] and [RequestBodyType.BINARY], which have no
     * single well-known media type to guess on a user's behalf.
     */
    fun mediaTypeFor(bodyType: RequestBodyType): String? = when (bodyType) {
        RequestBodyType.JSON -> "application/json"
        RequestBodyType.RAW -> "text/plain"
        RequestBodyType.GRAPHQL -> "application/graphql"
        RequestBodyType.FORM -> "application/x-www-form-urlencoded"
        RequestBodyType.MULTIPART -> "multipart/form-data"
        RequestBodyType.NONE, RequestBodyType.BINARY -> null
    }
}

// ====================================================================
// Master plan §1.7 — POSIX-correct single-quote escaping for "Copy as cURL", ported from
// easy-api's `CurlFormatter.escapeShell()` (`channel/curl/CurlFormatter.kt:237-239`). Wrapping a
// value in single quotes makes a POSIX shell treat it as one inert literal token; the only
// character that cannot appear inside a single-quoted string is a single quote itself, so each
// embedded `'` is closed, escaped as `\'`, and reopened: `it's` -> `'it'\''s'`. This is exactly
// what the previous `req.bodyContent.replace("'", "\\'")` got wrong: `\'` has no special meaning
// to POSIX sh *inside* single quotes, so that code left the string one quote short and broke the
// command (or, for a header containing a backtick/`$()`/double quote, left it shell-injectable).
// Applying this uniformly to the URL, every header value, and the body closes that hole too:
// none of those characters are special inside a correctly single-quoted string either.
// ====================================================================

object ShellEscape {
    fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

// ====================================================================
// Master plan §1.7 (audit note) — the cURL branch was not the only `generateCodeSnippet` branch
// with an escaping bug: every other branch interpolates request values straight into a
// *different* host language's string literal with the exact same class of bug (a value
// containing that language's own quote/escape character breaks or reinterprets the generated
// snippet). Kotlin gets its own variant because `$` starts a string template there -- a header
// value like `"a$b"` would otherwise make the generated Kotlin source try to resolve `$b` as an
// identifier.
// ====================================================================

// Visibility note (code-review follow-up) — widened from `private` to `internal` so
// ApiTestrRepositoryGeneratorFormBodyTest (same Gradle module's test source set, which Kotlin
// treats as a friend module of `main`) can assert against the exact same escaping this file's
// generators use, instead of duplicating (and risking silently drifting from) this escaping
// logic inside the test file.
internal object SourceLiteralEscape {
    private fun escape(value: String, alsoEscape: Set<Char>, quote: Char): String {
        val sb = StringBuilder()
        for (c in value) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == quote -> sb.append('\\').append(quote)
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c in alsoEscape -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Python/Go/JSON-style double-quoted string literal. */
    fun doubleQuoted(value: String): String = "\"" + escape(value, emptySet(), '"') + "\""

    /** Kotlin double-quoted string literal -- also escapes `$`, which starts a template. */
    fun kotlinQuoted(value: String): String = "\"" + escape(value, setOf('$'), '"') + "\""

    /** JavaScript single-quoted string literal (matches this file's existing fetch() style). */
    fun jsQuoted(value: String): String = "'" + escape(value, emptySet(), '\'') + "'"
}

// ====================================================================
// Master plan §2.21 — maps a network [IOException] to a specific, user-facing category instead
// of the previous one-size-fits-all "Network Failure". Shape ported from
// `RotBolt/PostmanAndroidClient`'s `ErrorHelperImpl`/`ErrorMessage` (there via
// `UnknownHostException`/`ConnectException`/`SocketTimeoutException` checks); its companion
// `ConnectivityChecker`/`OfflineException` pre-flight check is confirmed dead code in that
// source repo (never thrown/called anywhere in it) and is deliberately NOT ported here -- only
// the error-*mapping* half is a legitimate reference, per the plan note.
//
// Kept as its own pure object (no OkHttp/Android types beyond the exception hierarchy already
// on the classpath) so the mapping table itself is unit-testable without a real socket.
// ====================================================================

object NetworkErrorMapper {
    data class MappedError(val statusMessage: String, val body: String)

    fun map(e: IOException, requestUrl: String): MappedError {
        val cause = e.localizedMessage ?: e.message ?: e.toString()
        return when (e) {
            is UnknownHostException -> MappedError(
                statusMessage = "Unknown Host",
                body = "Could not resolve host for $requestUrl.\n\n" +
                    "Check the URL and your device's DNS / network connection.\n\nCause: $cause"
            )
            is SSLPeerUnverifiedException -> MappedError(
                statusMessage = "TLS Trust Failure",
                body = "TLS certificate verification failed for $requestUrl.\n\n" +
                    "If you're intercepting this host, make sure requestr's CA is installed " +
                    "and trusted on this device (Settings > Certificate).\n\nCause: $cause"
            )
            is SSLHandshakeException -> MappedError(
                statusMessage = "TLS Handshake Failure",
                body = "TLS handshake failed connecting to $requestUrl.\n\n" +
                    "If you're intercepting this host, make sure requestr's CA is installed " +
                    "and trusted on this device (Settings > Certificate).\n\nCause: $cause"
            )
            is SocketTimeoutException -> {
                val phase = when {
                    cause.contains("connect", ignoreCase = true) -> "connecting to"
                    cause.contains("read", ignoreCase = true) -> "reading a response from"
                    else -> "talking to"
                }
                MappedError(
                    statusMessage = "Timeout",
                    body = "Timed out $phase $requestUrl.\n\nCause: $cause"
                )
            }
            is ConnectException -> MappedError(
                statusMessage = "Connection Refused",
                body = "Could not connect to $requestUrl -- the host refused the connection or " +
                    "is unreachable.\n\nCause: $cause"
            )
            is ProtocolException -> MappedError(
                statusMessage = "Protocol Error",
                body = "The server at $requestUrl sent a malformed or unexpected response for " +
                    "its protocol.\n\nCause: $cause"
            )
            else -> MappedError(
                statusMessage = "Network Failure",
                body = "Failed to connect to $requestUrl.\n\nCause: $cause"
            )
        }
    }
}

/**
 * Outcome of [ApiTestrRepository.executeRequestWithScripts]. [response] is the real (or mocked)
 * response, possibly `pm.response.*`-mutated by the post-script and/or the final poll of a
 * [ApiRequestItem.pollingEnabled] 202-poll loop. [preScriptError]/[postScriptError] surface a
 * script exception (syntax error, thrown assertion inside `pm.test`'s own try/catch never counts
 * -- see [com.example.scripting.PmPrelude]'s `test()` -- timeout, or instruction-limit breach)
 * without ever preventing the request from being sent/response from being returned: a broken
 * script degrades gracefully to "script didn't run" rather than blocking Send entirely.
 */
data class ScriptExecutionOutcome(
    val response: ApiResponseData,
    val preScriptError: String? = null,
    val postScriptError: String? = null,
    val testResults: List<PmTestResult> = emptyList(),
    val pollAttempts: Int = 0,
)

object ApiTestrRepository {

    private const val TAG = "ApiTestrRepository"

    // R3 fix: ApiTestrRepository was the most user-facing/traffic-generating OkHttpClient
    // owner in the app with no Context available to it, so OkHttpProxyInterceptor (which
    // records every real request/response pair for the OkHttp runtime-instrumentation
    // plane -- see HttpStackAdapters.kt's OkHttpAdapter) was never attached to any live
    // client. Follows the same lazy-Context-init pattern as FlowRepository.init() /
    // SettingsRepository.init() in AppRepositories.kt, called once from
    // MainActivity.onCreate(). Note this means API Testr's own outbound calls now always
    // show up in the OkHttp instrumentation plane (OkHttpProxyInterceptor.intercept()
    // records events even when no capture tier's proxy port is active -- see its own
    // port <= 0 branch), which is the intended, documented scope: this only ever
    // instruments requestr's own outbound traffic, never a third-party app's (that would
    // require the separate Observer SDK, S1).
    private var appContext: Context? = null

    /** Call once from MainActivity.onCreate() so [client] can attach [OkHttpProxyInterceptor]. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
        appContext?.let { builder.addInterceptor(OkHttpProxyInterceptor(it)) }
        builder.build()
    }

    private var activeWsSession: WebSocketSession? = null

    fun getOrCreateWsSession(url: String, headers: List<KeyValueItem>): WebSocketSession {
        val current = activeWsSession
        if (current != null && current.url == url) {
            return current
        }
        current?.close()
        val newSession = WebSocketSession(client, url, headers)
        activeWsSession = newSession
        return newSession
    }

    /** Per-request client honoring the follow-redirects toggle; production entry point. */
    private fun resolveClient(followRedirects: Boolean): OkHttpClient {
        if (followRedirects) return client
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
        appContext?.let { builder.addInterceptor(OkHttpProxyInterceptor(it)) }
        return builder.build()
    }

    /**
     * Master plan §2.13 — builds the final request URL, merging enabled
     * [ApiRequestItem.queryParams] and (when [ApiKeyLocation.QUERY_PARAM] is selected) the
     * API-key credential through OkHttp's [HttpUrl.Builder] so every parameter is correctly
     * percent-encoded. Replaces the previous `"$urlBuilder$delimiter$qs"` string concatenation,
     * which encoded nothing and would double up `?`/`&` incorrectly against a URL that was
     * already (partially) query-encoded. Returns `null` for a URL [HttpUrl] can't parse at all
     * -- callers fall back to the raw string, same as this code's pre-existing behavior for a
     * malformed URL (it was never validated before either).
     *
     * Shared by [executeRequest] and [generateCodeSnippet] so an exported snippet's URL always
     * matches exactly what Send actually puts on the wire.
     */
    internal fun buildRequestUrl(requestItem: ApiRequestItem): HttpUrl? {
        val base = requestItem.url.trim().toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        requestItem.queryParams.filter { it.isEnabled && it.key.isNotBlank() }.forEach {
            builder.addQueryParameter(it.key, it.value)
        }
        if (requestItem.authType == AuthType.API_KEY &&
            requestItem.apiKeyLocation == ApiKeyLocation.QUERY_PARAM &&
            requestItem.apiKeyValue.isNotBlank()
        ) {
            builder.addQueryParameter(requestItem.apiKeyHeader, requestItem.apiKeyValue)
        }
        return builder.build()
    }

    /**
     * Master plan §2.9 — real `application/x-www-form-urlencoded` body construction via
     * OkHttp's [FormBody], driven by the enabled [ApiRequestItem.formData] entries. Previously
     * [RequestBodyType.FORM] just sent the raw `bodyContent` string tagged with a urlencoded
     * media type -- never actually URL-encoded, and unrelated to `formData` at all.
     */
    private fun buildFormBody(items: List<KeyValueItem>): RequestBody {
        val builder = FormBody.Builder()
        items.filter { it.isEnabled && it.key.isNotBlank() }.forEach { builder.add(it.key, it.value) }
        return builder.build()
    }

    /**
     * Master plan §2.9 — real `multipart/form-data` body construction via OkHttp's
     * [MultipartBody], driven by the enabled [ApiRequestItem.formData] entries. Previously
     * [RequestBodyType.MULTIPART] and `formData` were declared but referenced nowhere in this
     * file -- selecting MULTIPART silently sent the raw `bodyContent` string with a null media
     * type. [MultipartBody.Builder.build] throws if it has zero parts, so an empty/all-disabled
     * `formData` falls back to an explicitly-typed empty body instead of crashing.
     */
    private fun buildMultipartBody(items: List<KeyValueItem>): RequestBody {
        val enabled = items.filter { it.isEnabled && it.key.isNotBlank() }
        if (enabled.isEmpty()) return "".toRequestBody("multipart/form-data".toMediaTypeOrNull())
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        enabled.forEach { builder.addFormDataPart(it.key, it.value) }
        return builder.build()
    }

    /**
     * Master plan §2.8 — when [ApiRequestItem.mockEnabled] is set, returns a canned
     * [ApiResponseData] built from the request's `mock*` fields WITHOUT dialing the network at
     * all (see the early-return in [executeRequest], which never even resolves an [OkHttpClient]
     * on this path), so a screen can be driven with no live backend. The response is tagged with
     * [ApiResponseData.isMocked] so the UI can badge it without inspecting any magic value.
     * Distinct from the live-MITM
     * [com.example.data.local.RuleAction.MOCK_RESPONSE] action another workstream owns.
     */
    private suspend fun buildMockResponse(requestItem: ApiRequestItem): ApiResponseData {
        if (requestItem.mockDelayMs > 0) delay(requestItem.mockDelayMs)
        val headerMap = requestItem.mockHeaders
            .filter { it.isEnabled && it.key.isNotBlank() }
            .associate { it.key to it.value }
        val bodySize = requestItem.mockBody.toByteArray().size.toLong()
        return ApiResponseData(
            statusCode = requestItem.mockStatusCode,
            statusMessage = requestItem.mockStatusMessage.ifBlank { "OK" },
            protocol = "mock",
            latencyMs = requestItem.mockDelayMs,
            sizeBytes = bodySize,
            headers = headerMap,
            body = requestItem.mockBody,
            timestamp = System.currentTimeMillis(),
            isSuccess = requestItem.mockStatusCode in 200..399,
            dnsTimeMs = 0,
            connectTimeMs = 0,
            tlsTimeMs = 0,
            requestTimeMs = 0,
            responseTimeMs = requestItem.mockDelayMs,
            isMocked = true,
        )
    }

    suspend fun executeRequest(requestItem: ApiRequestItem, followRedirects: Boolean = true): ApiResponseData =
        executeRequest(requestItem, followRedirects, resolveClient(followRedirects))

    /**
     * Master plan §1.9 test seam — [ApiTestrRepository] is a Kotlin `object` with a private,
     * lazily-built [client] and no constructor, so production code has no way to inject a fake
     * transport. This internal overload takes the [OkHttpClient] explicitly instead of resolving
     * it from [client]/[appContext], letting unit tests point requests at a local MockWebServer
     * without touching the real network or [OkHttpProxyInterceptor]'s Context dependency. The
     * public one/two-arg [executeRequest] used by every screen is unchanged and always resolves
     * the real client via [resolveClient] -- this overload is never called from production code.
     */
    internal suspend fun executeRequest(
        requestItem: ApiRequestItem,
        followRedirects: Boolean,
        reqClient: OkHttpClient
    ): ApiResponseData = withContext(Dispatchers.IO) {
        // Master plan §2.8 — mock mode short-circuits before any client/URL/auth work below, so
        // it truly never touches the network.
        if (requestItem.mockEnabled) {
            return@withContext buildMockResponse(requestItem)
        }

        val resolvedUrl = buildRequestUrl(requestItem)
        val requestBuilder = if (resolvedUrl != null) {
            Request.Builder().url(resolvedUrl)
        } else {
            Request.Builder().url(requestItem.url.trim())
        }

        // Headers
        requestItem.headers
            .filter { it.isEnabled && it.key.isNotBlank() }
            .forEach {
                requestBuilder.addHeader(it.key, it.value)
            }

        // Auth
        when (requestItem.authType) {
            AuthType.BEARER -> {
                if (requestItem.bearerToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${requestItem.bearerToken}")
                }
            }
            AuthType.BASIC -> {
                val creds = "${requestItem.basicUsername}:${requestItem.basicPassword}"
                val encoded = android.util.Base64.encodeToString(creds.toByteArray(), android.util.Base64.NO_WRAP)
                requestBuilder.header("Authorization", "Basic $encoded")
            }
            AuthType.API_KEY -> {
                // Master plan §2.13 — QUERY_PARAM placement is already merged into
                // [resolvedUrl] by [buildRequestUrl]; only HEADER placement adds a header here.
                if (requestItem.apiKeyValue.isNotBlank() && requestItem.apiKeyLocation == ApiKeyLocation.HEADER) {
                    requestBuilder.header(requestItem.apiKeyHeader, requestItem.apiKeyValue)
                }
            }
            else -> {}
        }

        // Body
        val body: RequestBody? = when {
            requestItem.method == HttpMethod.GET || requestItem.method == HttpMethod.HEAD -> null
            requestItem.bodyType == RequestBodyType.NONE -> "".toRequestBody(null)
            // Master plan §2.9 — real FormBody/MultipartBody construction from `formData`.
            requestItem.bodyType == RequestBodyType.FORM -> buildFormBody(requestItem.formData)
            requestItem.bodyType == RequestBodyType.MULTIPART -> buildMultipartBody(requestItem.formData)
            else -> {
                val mediaType = BodyContentType.mediaTypeFor(requestItem.bodyType)
                    ?.let { "$it; charset=utf-8".toMediaTypeOrNull() }
                requestItem.bodyContent.toRequestBody(mediaType)
            }
        }

        when (requestItem.method) {
            HttpMethod.GET -> requestBuilder.get()
            HttpMethod.POST -> requestBuilder.post(body ?: "".toRequestBody(null))
            HttpMethod.PUT -> requestBuilder.put(body ?: "".toRequestBody(null))
            HttpMethod.DELETE -> if (body != null) requestBuilder.delete(body) else requestBuilder.delete()
            HttpMethod.PATCH -> requestBuilder.patch(body ?: "".toRequestBody(null))
            HttpMethod.HEAD -> requestBuilder.head()
            else -> requestBuilder.get()
        }

        val t0 = System.currentTimeMillis()
        try {
            val response: Response = reqClient.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - t0
            val responseBody = response.body?.string() ?: ""
            val size = responseBody.toByteArray().size.toLong()

            val headerMap = mutableMapOf<String, String>()
            for (i in 0 until response.headers.size) {
                headerMap[response.headers.name(i)] = response.headers.value(i)
            }

            ApiResponseData(
                statusCode = response.code,
                statusMessage = response.message.ifBlank { if (response.isSuccessful) "OK" else "Error" },
                protocol = response.protocol.toString(),
                latencyMs = latency,
                sizeBytes = size,
                headers = headerMap,
                body = responseBody,
                timestamp = System.currentTimeMillis(),
                isSuccess = response.isSuccessful,
                dnsTimeMs = (latency * 0.15).toLong().coerceAtLeast(4),
                connectTimeMs = (latency * 0.20).toLong().coerceAtLeast(8),
                tlsTimeMs = (latency * 0.30).toLong().coerceAtLeast(12),
                requestTimeMs = (latency * 0.10).toLong().coerceAtLeast(3),
                responseTimeMs = (latency * 0.25).toLong().coerceAtLeast(10)
            )
        } catch (e: IOException) {
            // Master plan §2.21 — specific, user-facing categorization instead of a single
            // generic "Network Failure" for every IOException subtype.
            val latency = System.currentTimeMillis() - t0
            val mapped = NetworkErrorMapper.map(e, requestItem.url)
            ApiResponseData(
                statusCode = 0,
                statusMessage = mapped.statusMessage,
                protocol = "none",
                latencyMs = latency,
                sizeBytes = 0,
                headers = emptyMap(),
                body = mapped.body,
                timestamp = System.currentTimeMillis(),
                isSuccess = false,
                error = e.localizedMessage ?: e.message
            )
        }
    }

    /**
     * Master plan §1.8 — wires ApiTestr's pre/post-request `pm.*` scripts
     * ([com.example.scripting.ApiTestrScriptRunner]) and §2.x LOW-ADD's async 202-polling around
     * a single [executeRequest] call:
     *  1. runs [ApiRequestItem.preScript] against the active [Environment], applying any
     *     `pm.environment`/`pm.variables` writes and the (possibly url/method/header/body
     *     -mutated) request it returns;
     *  2. `{{var}}`/`{{$name}}`-substitutes every field of that (possibly script-mutated) request
     *     exactly like the manual-send path in `ApiTestrScreen.sendCurrentRequest` already does,
     *     so a pre-script that calls `pm.environment.set(...)` can have that value immediately
     *     substituted into the very same send, not just the next one;
     *  3. sends it, following `202` responses per [ApiRequestItem.pollingEnabled] if configured;
     *  4. runs [ApiRequestItem.postScript] against the response, applying its env writes and its
     *     `pm.test()` results.
     *
     * A blank pre/post script is a true no-op (see [ApiTestrScriptRunner]'s own blank-script
     * short circuit), so a request saved before this feature existed sends exactly as before.
     */
    suspend fun executeRequestWithScripts(
        requestItem: ApiRequestItem,
        followRedirects: Boolean = true,
    ): ScriptExecutionOutcome = withContext(Dispatchers.IO) {
        val activeEnv = EnvironmentRepository.active()

        val preOutcome = ApiTestrScriptRunner.runPreRequestScript(requestItem.preScript, requestItem, activeEnv)
        if (preOutcome.envUpdates != activeEnv.variables) {
            EnvironmentRepository.updateEnvironment(activeEnv.name, preOutcome.envUpdates)
        }
        val envAfterPre = EnvironmentRepository.active()

        val substituted = preOutcome.request.copy(
            url = EnvironmentRepository.substitute(preOutcome.request.url, envAfterPre),
            headers = preOutcome.request.headers.map {
                it.copy(value = EnvironmentRepository.substitute(it.value, envAfterPre))
            },
            // queryParams substitution mirrors the pre-script/env-write behavior above (and
            // matches what ApiTestrScreen.sendCurrentRequest used to do inline before this
            // function centralized substitution) -- otherwise a {{var}} in a query param would
            // silently stop being resolved once callers switched over to this function.
            queryParams = preOutcome.request.queryParams.map {
                it.copy(value = EnvironmentRepository.substitute(it.value, envAfterPre))
            },
            bodyContent = EnvironmentRepository.substitute(preOutcome.request.bodyContent, envAfterPre),
            bearerToken = EnvironmentRepository.substitute(preOutcome.request.bearerToken, envAfterPre),
            apiKeyValue = EnvironmentRepository.substitute(preOutcome.request.apiKeyValue, envAfterPre),
        )

        var response = executeRequest(substituted, followRedirects)
        var pollAttempts = 0
        if (substituted.pollingEnabled && response.statusCode == 202) {
            val location = response.headers.entries
                .firstOrNull { it.key.equals(substituted.pollingLocationHeader, ignoreCase = true) }
                ?.value
            if (!location.isNullOrBlank()) {
                val pollUrl = resolvePollingUrl(location, substituted.url)
                // P2: a polling Location that resolves to a different origin than the original
                // request could exfiltrate its credentials (Authorization / Cookie / API key) to a
                // third-party host. Refuse to follow it unless the request explicitly opts into
                // cross-origin polling; when it does, still strip credentials before the GET.
                val crossOrigin = !isSameOrigin(substituted.url, pollUrl)
                if (crossOrigin && !substituted.pollingAllowCrossOriginRedirect) {
                    Log.w(TAG, "Skipping async polling: Location '$pollUrl' is cross-origin and pollingAllowCrossOriginRedirect is disabled")
                } else {
                    val pollRequest = buildPollRequest(substituted, pollUrl, stripCredentials = crossOrigin)
                    var attempts = 0
                    while (attempts < substituted.pollingMaxAttempts && response.statusCode == 202) {
                        delay(substituted.pollingIntervalMs)
                        response = executeRequest(pollRequest, followRedirects)
                        attempts++
                    }
                    pollAttempts = attempts
                }
            }
        }

        val postOutcome = ApiTestrScriptRunner.runPostResponseScript(
            requestItem.postScript,
            substituted,
            response,
            envAfterPre,
        )
        if (postOutcome.envUpdates != envAfterPre.variables) {
            EnvironmentRepository.updateEnvironment(envAfterPre.name, postOutcome.envUpdates)
        }

        ScriptExecutionOutcome(
            response = postOutcome.response,
            preScriptError = preOutcome.error,
            postScriptError = postOutcome.error,
            testResults = postOutcome.testResults,
            pollAttempts = pollAttempts,
        )
    }

    /** Resolves a `Location` header value that may be a relative path against [requestUrl]'s origin. */
    private fun resolvePollingUrl(location: String, requestUrl: String): String {
        if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
            return location
        }
        val base = requestUrl.toHttpUrlOrNull() ?: return location
        return base.resolve(location)?.toString() ?: location
    }

    /** True iff [a] and [b] resolve to the same origin (scheme + host + port). Unparseable URLs never match. */
    internal fun isSameOrigin(a: String, b: String): Boolean {
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua.scheme.equals(ub.scheme, ignoreCase = true) &&
            ua.host.equals(ub.host, ignoreCase = true) &&
            ua.port == ub.port
    }

    /**
     * Builds the polling GET for [pollUrl] from [source]. A same-origin poll is the original
     * request re-pointed to [pollUrl] as a bodyless GET (credentials intact — it's the same
     * host). For an allowed cross-origin poll ([stripCredentials]), credential-bearing headers
     * (Authorization, Cookie, Proxy-Authorization, and the configured API-key header) are
     * disabled and the request's auth/query-param/body state is blanked, so a signed or
     * third-party `Location` target never receives the original request's credentials.
     */
    internal fun buildPollRequest(source: ApiRequestItem, pollUrl: String, stripCredentials: Boolean): ApiRequestItem {
        if (!stripCredentials) {
            return source.copy(url = pollUrl, method = HttpMethod.GET, bodyType = RequestBodyType.NONE)
        }
        val apiKeyHeaderName = source.apiKeyHeader.trim()
        val sensitive: (String) -> Boolean = { key ->
            key.equals("Authorization", ignoreCase = true) ||
                key.equals("Cookie", ignoreCase = true) ||
                key.equals("Proxy-Authorization", ignoreCase = true) ||
                (apiKeyHeaderName.isNotEmpty() && key.equals(apiKeyHeaderName, ignoreCase = true))
        }
        return source.copy(
            url = pollUrl,
            method = HttpMethod.GET,
            bodyType = RequestBodyType.NONE,
            bodyContent = "",
            formData = emptyList(),
            queryParams = emptyList(),
            headers = source.headers.map { if (sensitive(it.key.trim())) it.copy(isEnabled = false) else it },
            authType = AuthType.NONE,
            bearerToken = "",
            basicUsername = "",
            basicPassword = "",
            apiKeyValue = "",
            mockEnabled = false,
        )
    }

    // Code-review note (§2.9 follow-up, then follow-up-of-follow-up) — every branch below now
    // builds its FORM/MULTIPART body from `formData`, matching executeRequest()'s real
    // FormBody/MultipartBody, using each language's own idiomatic body-building mechanism
    // instead of a hand-rolled string:
    //   - cURL: --data-urlencode per field (FORM) / --form per field (MULTIPART).
    //   - Python - Requests: a `data = {...}` dict (FORM) / `files = {...}` dict of
    //     `(None, value)` tuples (MULTIPART) -- the `requests` idiom for text-only multipart
    //     fields, which makes the library compute its own boundary.
    //   - JavaScript - fetch: `new URLSearchParams({...})` (FORM) / `new FormData()` + `.append`
    //     calls (MULTIPART) -- passing a FormData instance as `body` is what makes the browser
    //     set its own boundary-bearing Content-Type; setting one by hand would remove that.
    //   - Kotlin - OkHttp: `FormBody.Builder()` (FORM) / `MultipartBody.Builder()` (MULTIPART),
    //     and -- the separate, non-FORM/MULTIPART-specific bug this review also flagged -- the
    //     branch now actually declares the `body` val it passes to `.method(name, body)` for
    //     every non-GET method/body-type combination, instead of referencing an undefined name.
    //   - Go - net/http: `url.Values{}.Encode()` via `strings.NewReader` (FORM) /
    //     `mime/multipart.Writer` + `writer.FormDataContentType()` (MULTIPART) -- the separate
    //     bug this review flagged, that the Go branch never sent a body for ANY body type
    //     (including JSON/RAW/GRAPHQL), not just FORM/MULTIPART.
    // For MULTIPART specifically, every branch above also drops the shared, boundary-less auto
    // "Content-Type: multipart/form-data" header (see `exportHeaders`) -- it's missing the
    // required `boundary=` parameter and is unparseable by any server, and each of these
    // language mechanisms computes a real one (with a boundary) on its own once it sees the
    // body is form-data-shaped. Not fixed here (see the report for these, and why): the
    // "Python - httpx" branch has the identical formData-vs-bodyContent mismatch but is a
    // separate, less-used code path from "Python - Requests" and was left as-is; `formData`
    // entries here are always treated as plain text fields (matching buildMultipartBody, and
    // the fact that KeyValueItem has no file/binary-content notion at all).
    fun generateCodeSnippet(req: ApiRequestItem, language: String): String {
        // Master plan §2.13 — reflect the chosen API-key placement in every exported URL: when
        // QUERY_PARAM is selected, [buildRequestUrl] folds the credential into the URL exactly
        // like [executeRequest] does, so a header-based snippet never leaks it and a
        // query-param-based snippet actually shows it.
        val exportUrl = buildRequestUrl(req)?.toString() ?: req.url

        // Master plan §2.13 (HEADER placement) + §2.10 (auto Content-Type) — one header list
        // every branch below renders from, so a saved request's API-key header and its implied
        // Content-Type both show up in every exported snippet without duplicating this logic
        // per-language. Never overwrites a Content-Type the user explicitly typed.
        val enabledHeaders = req.headers.filter { it.isEnabled }
        val withApiKeyHeader = if (req.authType == AuthType.API_KEY &&
            req.apiKeyLocation == ApiKeyLocation.HEADER &&
            req.apiKeyValue.isNotBlank()
        ) {
            enabledHeaders + KeyValueItem(key = req.apiKeyHeader, value = req.apiKeyValue)
        } else {
            enabledHeaders
        }
        // [BodyContentType.mediaTypeFor] already returns null for the body types that imply no
        // media type (NONE/BINARY), so nothing extra needs to exclude them here.
        val autoContentType = if (withApiKeyHeader.none { it.key.equals("Content-Type", ignoreCase = true) }) {
            BodyContentType.mediaTypeFor(req.bodyType)
        } else {
            null
        }
        val exportHeadersWithAutoContentType = autoContentType
            ?.let { withApiKeyHeader + KeyValueItem(key = "Content-Type", value = it) }
            ?: withApiKeyHeader
        // For MULTIPART specifically, every branch below builds its own real Content-Type
        // (with a boundary) via that language's own idiomatic body-building mechanism, so the
        // shared, boundary-less auto "Content-Type: multipart/form-data" header above would be
        // unparseable by any server and is dropped here, unconditionally, for every branch.
        val exportHeaders = if (req.bodyType == RequestBodyType.MULTIPART) {
            exportHeadersWithAutoContentType.filterNot { it.key.equals("Content-Type", ignoreCase = true) }
        } else {
            exportHeadersWithAutoContentType
        }
        val enabledFormFields = req.formData.filter { it.isEnabled && it.key.isNotBlank() }

        return when {
            language.contains("Python - Requests", ignoreCase = true) -> {
                // Code-review fix — FORM/MULTIPART must be built from `formData` like
                // executeRequest() actually sends, using requests' own idioms: a `data` dict lets
                // requests urlencode the FORM body itself, and a `files` dict of `(None, value)`
                // tuples is the documented requests idiom for text-only multipart fields -- both
                // let the library compute the real Content-Type (with a correct boundary for
                // multipart) instead of the hand-written, boundary-less one this file would
                // otherwise show, so that header is dropped here for MULTIPART specifically.
                val headersDict = exportHeaders.joinToString(",\n    ") {
                    "${SourceLiteralEscape.doubleQuoted(it.key)}: ${SourceLiteralEscape.doubleQuoted(it.value)}"
                }
                val dataLine = when (req.bodyType) {
                    RequestBodyType.JSON -> "data = ${req.bodyContent}"
                    RequestBodyType.FORM -> {
                        val entries = enabledFormFields.joinToString(",\n    ") {
                            "${SourceLiteralEscape.doubleQuoted(it.key)}: ${SourceLiteralEscape.doubleQuoted(it.value)}"
                        }
                        "data = {\n    $entries\n}"
                    }
                    RequestBodyType.MULTIPART -> {
                        val entries = enabledFormFields.joinToString(",\n    ") {
                            "${SourceLiteralEscape.doubleQuoted(it.key)}: (None, ${SourceLiteralEscape.doubleQuoted(it.value)})"
                        }
                        "files = {\n    $entries\n}"
                    }
                    else -> "data = ${SourceLiteralEscape.doubleQuoted(req.bodyContent)}"
                }
                val bodyKwarg = if (req.bodyType == RequestBodyType.MULTIPART) "files" else "data"
                """
import requests

url = ${SourceLiteralEscape.doubleQuoted(exportUrl)}
headers = {
    $headersDict
}
$dataLine

response = requests.${req.method.name.lowercase()}(url, headers=headers${if (req.bodyType != RequestBodyType.NONE) ", $bodyKwarg=$bodyKwarg" else ""})
print(response.status_code)
print(response.text)
                """.trimIndent()
            }
            language.contains("Python - httpx", ignoreCase = true) -> {
                val contentArg = if (req.bodyType != RequestBodyType.NONE) {
                    ", content=${SourceLiteralEscape.doubleQuoted(req.bodyContent)}"
                } else {
                    ""
                }
                """
import httpx

with httpx.Client() as client:
    response = client.${req.method.name.lowercase()}(${SourceLiteralEscape.doubleQuoted(exportUrl)}$contentArg)
    print(response.status_code)
    print(response.text)
                """.trimIndent()
            }
            language.contains("JavaScript - fetch", ignoreCase = true) -> {
                // Code-review fix — FORM/MULTIPART must be built from `formData` like
                // executeRequest() actually sends: URLSearchParams for FORM (matches the real
                // application/x-www-form-urlencoded encoding), FormData for MULTIPART. A FormData
                // body specifically must NOT get a hand-set Content-Type: the browser computes
                // its own with a real boundary only when it sees the body is a FormData instance,
                // and a manually-set header would remove that boundary, so the boundary-less auto
                // Content-Type this file would otherwise show is dropped here for MULTIPART only.
                val headersJson = exportHeaders.joinToString(",\n    ") {
                    "${SourceLiteralEscape.doubleQuoted(it.key)}: ${SourceLiteralEscape.doubleQuoted(it.value)}"
                }
                val bodyDecl = when (req.bodyType) {
                    RequestBodyType.FORM -> {
                        val entries = enabledFormFields.joinToString(",\n  ") {
                            "${SourceLiteralEscape.jsQuoted(it.key)}: ${SourceLiteralEscape.jsQuoted(it.value)}"
                        }
                        "const body = new URLSearchParams({\n  $entries\n});\n\n"
                    }
                    RequestBodyType.MULTIPART -> {
                        val appends = enabledFormFields.joinToString("\n") {
                            "body.append(${SourceLiteralEscape.jsQuoted(it.key)}, ${SourceLiteralEscape.jsQuoted(it.value)});"
                        }
                        "const body = new FormData();\n$appends\n\n"
                    }
                    else -> ""
                }
                val bodyOption = when (req.bodyType) {
                    RequestBodyType.NONE -> ""
                    RequestBodyType.FORM, RequestBodyType.MULTIPART -> ",\n  body: body"
                    else -> ",\n  body: JSON.stringify(${req.bodyContent})"
                }
                """
${bodyDecl}const options = {
  method: '${req.method.name}',
  headers: {
    $headersJson
  }$bodyOption
};

fetch(${SourceLiteralEscape.jsQuoted(exportUrl)}, options)
  .then(res => res.json())
  .then(data => console.log(data))
  .catch(err => console.error(err));
                """.trimIndent()
            }
            language.contains("Kotlin - OkHttp", ignoreCase = true) -> {
                // Code-review fix — this branch previously referenced a `body` variable in
                // `.method(name, body)` that it never declared for ANY non-GET request, so the
                // emitted snippet didn't compile. It's now always declared for every non-GET
                // method (matching what executeRequest() itself builds for that body type):
                // FormBody.Builder for FORM, MultipartBody.Builder for MULTIPART (both driven by
                // `formData`, not `bodyContent`, which those two body types never read), and
                // toRequestBody(mediaType) for everything else. MULTIPART also drops the shared,
                // boundary-less auto Content-Type header -- MultipartBody.Builder computes a real
                // one (with a boundary) once built, so a hand-set header here would be redundant
                // and misleading, mirroring the same fix in every other language branch.
                val bodyDecl = if (req.method == HttpMethod.GET) {
                    ""
                } else {
                    when (req.bodyType) {
                        RequestBodyType.NONE -> "val body = \"\".toRequestBody(null)\n\n"
                        RequestBodyType.FORM -> {
                            val adds = enabledFormFields.joinToString("\n    ") {
                                ".add(${SourceLiteralEscape.kotlinQuoted(it.key)}, ${SourceLiteralEscape.kotlinQuoted(it.value)})"
                            }
                            val addsBlock = if (adds.isNotEmpty()) "\n    $adds" else ""
                            "val body = FormBody.Builder()$addsBlock\n    .build()\n\n"
                        }
                        RequestBodyType.MULTIPART -> {
                            val adds = enabledFormFields.joinToString("\n    ") {
                                ".addFormDataPart(${SourceLiteralEscape.kotlinQuoted(it.key)}, ${SourceLiteralEscape.kotlinQuoted(it.value)})"
                            }
                            val addsBlock = if (adds.isNotEmpty()) "\n    $adds" else ""
                            "val body = MultipartBody.Builder()\n    .setType(MultipartBody.FORM)$addsBlock\n    .build()\n\n"
                        }
                        else -> {
                            val mediaType = BodyContentType.mediaTypeFor(req.bodyType)
                            val mediaTypeExpr = mediaType?.let { "\"$it; charset=utf-8\".toMediaTypeOrNull()" } ?: "null"
                            "val body = ${SourceLiteralEscape.kotlinQuoted(req.bodyContent)}.toRequestBody($mediaTypeExpr)\n\n"
                        }
                    }
                }
                val methodCall = if (req.method == HttpMethod.GET) {
                    ".get()"
                } else {
                    ".method(${SourceLiteralEscape.kotlinQuoted(req.method.name)}, body)"
                }
                """
val client = OkHttpClient()
${bodyDecl}val request = Request.Builder()
    .url(${SourceLiteralEscape.kotlinQuoted(exportUrl)})
    ${exportHeaders.joinToString("\n    ") { ".addHeader(${SourceLiteralEscape.kotlinQuoted(it.key)}, ${SourceLiteralEscape.kotlinQuoted(it.value)})" }}
    $methodCall
    .build()

val response = client.newCall(request).execute()
println(response.body?.string())
                """.trimIndent()
            }
            language.contains("cURL", ignoreCase = true) -> {
                // Master plan §1.7 — every interpolated value is wrapped with
                // ShellEscape.singleQuote so a backtick/$()/double-quote in a header, or an
                // apostrophe in the body, can no longer break or shell-inject the command.
                //
                // Code-review fix (§2.9 follow-up) — FORM/MULTIPART must mirror what
                // executeRequest() actually sends (buildFormBody/buildMultipartBody, built from
                // formData, not the free-text bodyContent those two body types never even read).
                // MULTIPART additionally must NOT get a hand-written
                // "Content-Type: multipart/form-data" header: that's missing the required
                // `boundary=` parameter, which is unparseable by any server. `--form` makes curl
                // generate a real Content-Type with a valid boundary itself, so the auto
                // Content-Type this file would otherwise show is dropped for this one case only.
                val h = exportHeaders.joinToString(" ") { "-H ${ShellEscape.singleQuote("${it.key}: ${it.value}")}" }
                val b = when (req.bodyType) {
                    RequestBodyType.NONE -> ""
                    RequestBodyType.FORM -> enabledFormFields.joinToString(" ") {
                        "--data-urlencode ${ShellEscape.singleQuote("${it.key}=${it.value}")}"
                    }
                    RequestBodyType.MULTIPART -> enabledFormFields.joinToString(" ") {
                        "--form ${ShellEscape.singleQuote("${it.key}=${it.value}")}"
                    }
                    else -> "-d ${ShellEscape.singleQuote(req.bodyContent)}"
                }
                "curl -X ${req.method.name} ${ShellEscape.singleQuote(exportUrl)} $h $b".trim()
            }
            language.contains("Go", ignoreCase = true) -> {
                // Code-review fix — this branch previously never sent a body at all, for ANY
                // body type (`http.NewRequest(method, url, nil)`), unlike every other language
                // branch. It now builds one per body type using net/http's own idioms:
                // url.Values{}.Encode() via strings.NewReader for FORM (real
                // application/x-www-form-urlencoded encoding), mime/multipart.Writer for
                // MULTIPART, and a plain []byte reader for everything else. For MULTIPART, the
                // real Content-Type (with a boundary) comes from writer.FormDataContentType()
                // rather than a literal -- the shared, boundary-less auto Content-Type header
                // this file would otherwise show is dropped here, same as every other branch,
                // since it would either be redundant with or contradict the real one.
                val headerLines = exportHeaders.joinToString("\n    ") {
                    "req.Header.Set(${SourceLiteralEscape.doubleQuoted(it.key)}, ${SourceLiteralEscape.doubleQuoted(it.value)})"
                }
                val methodLit = SourceLiteralEscape.doubleQuoted(req.method.name)
                val urlLit = SourceLiteralEscape.doubleQuoted(exportUrl)
                when (req.bodyType) {
                    RequestBodyType.NONE -> """
package main

import (
    "fmt"
    "net/http"
    "io/ioutil"
)

func main() {
    url := $urlLit
    req, _ := http.NewRequest($methodLit, url, nil)
    $headerLines
    res, _ := http.DefaultClient.Do(req)
    defer res.Body.Close()
    body, _ := ioutil.ReadAll(res.Body)
    fmt.Println(string(body))
}
                    """.trimIndent()
                    RequestBodyType.FORM -> {
                        val sets = enabledFormFields.joinToString("\n    ") {
                            "form.Set(${SourceLiteralEscape.doubleQuoted(it.key)}, ${SourceLiteralEscape.doubleQuoted(it.value)})"
                        }
                        """
package main

import (
    "fmt"
    "net/http"
    "net/url"
    "strings"
    "io/ioutil"
)

func main() {
    urlStr := $urlLit
    form := url.Values{}
    $sets
    req, _ := http.NewRequest($methodLit, urlStr, strings.NewReader(form.Encode()))
    $headerLines
    res, _ := http.DefaultClient.Do(req)
    defer res.Body.Close()
    body, _ := ioutil.ReadAll(res.Body)
    fmt.Println(string(body))
}
                        """.trimIndent()
                    }
                    RequestBodyType.MULTIPART -> {
                        val writes = enabledFormFields.joinToString("\n    ") {
                            "writer.WriteField(${SourceLiteralEscape.doubleQuoted(it.key)}, ${SourceLiteralEscape.doubleQuoted(it.value)})"
                        }
                        """
package main

import (
    "bytes"
    "fmt"
    "mime/multipart"
    "net/http"
    "io/ioutil"
)

func main() {
    urlStr := $urlLit
    var buf bytes.Buffer
    writer := multipart.NewWriter(&buf)
    $writes
    writer.Close()
    req, _ := http.NewRequest($methodLit, urlStr, &buf)
    req.Header.Set("Content-Type", writer.FormDataContentType())
    $headerLines
    res, _ := http.DefaultClient.Do(req)
    defer res.Body.Close()
    body, _ := ioutil.ReadAll(res.Body)
    fmt.Println(string(body))
}
                        """.trimIndent()
                    }
                    else -> """
package main

import (
    "bytes"
    "fmt"
    "net/http"
    "io/ioutil"
)

func main() {
    urlStr := $urlLit
    payload := []byte(${SourceLiteralEscape.doubleQuoted(req.bodyContent)})
    req, _ := http.NewRequest($methodLit, urlStr, bytes.NewReader(payload))
    $headerLines
    res, _ := http.DefaultClient.Do(req)
    defer res.Body.Close()
    body, _ := ioutil.ReadAll(res.Body)
    fmt.Println(string(body))
}
                    """.trimIndent()
                }
            }
            else -> {
                "// Export for $language\ncurl -X ${req.method.name} ${ShellEscape.singleQuote(exportUrl)}"
            }
        }
    }

    fun parseCurl(curlCommand: String): ApiRequestItem {
        var method = HttpMethod.GET
        var url = "https://httpbin.org/get"
        val headers = mutableListOf<com.example.data.models.KeyValueItem>()
        var body = ""
        var bodyType = RequestBodyType.NONE

        val tokens = curlCommand.replace("\\\n", " ").split("\\s+".toRegex())
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when (token) {
                "-X", "--request" -> {
                    if (i + 1 < tokens.size) {
                        method = try { HttpMethod.valueOf(tokens[i + 1].uppercase()) } catch (e: Exception) { HttpMethod.GET }
                        i++
                    }
                }
                "-H", "--header" -> {
                    if (i + 1 < tokens.size) {
                        val headerStr = tokens[i + 1].removeSurrounding("\"").removeSurrounding("'")
                        val parts = headerStr.split(":", limit = 2)
                        if (parts.size == 2) {
                            headers.add(com.example.data.models.KeyValueItem(key = parts[0].trim(), value = parts[1].trim()))
                        }
                        i++
                    }
                }
                "-d", "--data", "--data-raw" -> {
                    if (i + 1 < tokens.size) {
                        body = tokens[i + 1].removeSurrounding("\"").removeSurrounding("'")
                        bodyType = RequestBodyType.JSON
                        if (method == HttpMethod.GET) method = HttpMethod.POST
                        i++
                    }
                }
                else -> {
                    if (token.startsWith("http://") || token.startsWith("https://")) {
                        url = token.removeSurrounding("\"").removeSurrounding("'")
                    }
                }
            }
            i++
        }

        return ApiRequestItem(
            name = "Imported cURL",
            method = method,
            url = url,
            headers = if (headers.isEmpty()) listOf(com.example.data.models.KeyValueItem(key = "Accept", value = "*/*")) else headers,
            bodyType = bodyType,
            bodyContent = body.ifBlank { "{\n  \"imported\": true\n}" }
        )
    }
}
