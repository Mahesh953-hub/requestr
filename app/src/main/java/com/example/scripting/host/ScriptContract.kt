package com.example.scripting.host

import android.util.Base64
import com.example.data.models.HttpMethod
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.stages.ScriptStage
import com.example.scripting.GlobalVariableStore
import com.example.scripting.JsScriptEngine
import com.example.scripting.PmPrelude
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * S4 task 32: the JSON contract between the app process and the isolated `:scripthost` process,
 * plus the one pure function both sides share -- [execute], which runs an envelope through
 * [JsScriptEngine] with the two non-negotiable Rhino settings ([JsScriptEngine.eval] forces
 * `optimizationLevel = -1` and `isJavaPrimitiveWrap = false`).
 *
 * Bodies over [MAX_SCRIPT_BODY_BYTES] cross the boundary truncated with `bodyTruncated = true`;
 * a body mutation returned against a truncated body is refused by the caller (fail-closed on the
 * body, fail-open on the flow). Binary bodies travel base64 so the host can hand them back
 * byte-exact when the script does not touch them.
 */
object ScriptContract {
    /** Aligned with `HttpBodyDecoder.MAX_STORED_BODY_BYTES`: 2 MiB. */
    const val MAX_SCRIPT_BODY_BYTES: Int = 2 * 1024 * 1024

    /** Messenger protocol. */
    const val MSG_HELLO = 1
    const val MSG_RUN = 2
    const val MSG_RESULT = 3
    const val KEY_JSON = "json"
    const val KEY_CALL_ID = "callId"
    const val KEY_PID = "pid"

    val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(ScriptRequestEnvelope::class.java)
    private val responseAdapter = moshi.adapter(ScriptResponseEnvelope::class.java)

    fun encodeRequest(e: ScriptRequestEnvelope): String = requestAdapter.toJson(e)
    fun decodeRequest(json: String): ScriptRequestEnvelope = requestAdapter.fromJson(json) ?: error("empty request envelope")
    fun encodeResponse(e: ScriptResponseEnvelope): String = responseAdapter.toJson(e)
    fun decodeResponse(json: String): ScriptResponseEnvelope = responseAdapter.fromJson(json) ?: error("empty response envelope")

    @JsonClass(generateAdapter = false)
    data class MessageDto(
        val isRequest: Boolean,
        val url: String = "",
        val method: String? = null,
        val statusCode: Int = 0,
        val statusMessage: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** UTF-8 text body (possibly truncated). Null for a binary body. */
        val body: String? = null,
        /** Base64 of the binary body bytes (possibly truncated). Null for a text body. */
        val bodyBase64: String? = null,
        val bodyTruncated: Boolean = false,
        /** Set by the host when the script assigned `pm.request.body` / `pm.response.body`. */
        val bodyReplaced: Boolean = false,
    ) {
        companion object {
            fun fromMessage(m: ParsedHttpMessage, maxBodyBytes: Int = MAX_SCRIPT_BODY_BYTES): MessageDto {
                val binaryBytes = if (m.isBodyBinary) (m.replacedBodyBytes ?: m.rawBodyBytes ?: ByteArray(0)) else null
                return if (binaryBytes != null) {
                    val truncated = binaryBytes.size > maxBodyBytes
                    MessageDto(
                        isRequest = m.isRequest, url = m.url, method = m.method?.name, statusCode = m.statusCode,
                        statusMessage = m.statusMessage, headers = m.headers,
                        bodyBase64 = Base64.encodeToString(if (truncated) binaryBytes.copyOf(maxBodyBytes) else binaryBytes, Base64.NO_WRAP),
                        bodyTruncated = truncated,
                    )
                } else {
                    val text = m.body
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val truncated = bytes.size > maxBodyBytes
                    MessageDto(
                        isRequest = m.isRequest, url = m.url, method = m.method?.name, statusCode = m.statusCode,
                        statusMessage = m.statusMessage, headers = m.headers,
                        body = if (truncated) String(bytes, 0, maxBodyBytes, Charsets.UTF_8) else text,
                        bodyTruncated = truncated,
                    )
                }
            }
        }

        /** Rebuilds a working [ParsedHttpMessage] the bridge mutates in place (remote side). */
        fun toWorkingMessage(): ParsedHttpMessage {
            val bytes = bodyBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
            return ParsedHttpMessage(
                isRequest = isRequest,
                method = method?.let { runCatching { HttpMethod.valueOf(it) }.getOrNull() },
                url = url, statusCode = statusCode, statusMessage = statusMessage, headers = headers,
                body = if (bytes != null) HttpBodyDecoder.BINARY_BODY_PLACEHOLDER else (body ?: ""),
                isBodyBinary = bytes != null, rawBodyBytes = bytes,
            )
        }

        /**
         * Applies the host's result onto the live message. Headers/url/status always apply; a body
         * mutation is applied only when the body crossed the boundary whole -- writing a truncated
         * body to the wire is never acceptable. Returns the refusal reason, or null.
         */
        fun applyTo(target: ParsedHttpMessage, original: MessageDto): String? {
            if (target.isRequest) {
                if (url.isNotEmpty()) target.url = url
            } else if (statusCode > 0) {
                target.statusCode = statusCode
            }
            target.headers = headers
            if (!bodyReplaced) return null
            if (original.bodyTruncated) return "script changed a body larger than ${MAX_SCRIPT_BODY_BYTES} bytes; body mutation refused"
            target.body = body ?: ""
            target.replacedBodyBytes = null
            target.isBodyBinary = false
            target.bodyReplaced = true
            return null
        }
    }

    @JsonClass(generateAdapter = false)
    data class ScriptRequestEnvelope(
        /** [PmPrelude.PHASE_REQUEST] or [PmPrelude.PHASE_RESPONSE]. */
        val phase: String,
        val flowId: String,
        val scriptId: String,
        val source: String,
        val timeoutMs: Long,
        val maxInstructions: Long,
        val variables: Map<String, String> = emptyMap(),
        /** `context.shared` carried from this flow's `onRequest` into its `onResponse`. */
        val shared: Map<String, String> = emptyMap(),
        /** `pm.globals` snapshot: the remote process has no view of the app's store. */
        val globals: Map<String, String> = emptyMap(),
        val message: MessageDto,
        val responseTimeMs: Long = 0L,
    )

    @JsonClass(generateAdapter = false)
    data class TestDto(val name: String, val passed: Boolean, val message: String)

    @JsonClass(generateAdapter = false)
    data class ScriptResponseEnvelope(
        val ok: Boolean,
        val aborted: Boolean = false,
        val message: MessageDto? = null,
        val shared: Map<String, String> = emptyMap(),
        val globals: Map<String, String> = emptyMap(),
        val console: List<String> = emptyList(),
        val tests: List<TestDto> = emptyList(),
        val error: String? = null,
        /** The executing process; the client kills it on a missed deadline. */
        val pid: Int = 0,
    )

    /**
     * Runs one envelope to completion. Pure with respect to the caller's process: globals are
     * seeded from and returned through the envelope, the message is a working copy.
     */
    fun execute(request: ScriptRequestEnvelope, pid: Int = 0): ScriptResponseEnvelope {
        val working = request.message.toWorkingMessage()
        val bridge = ScriptStage.bridgeFor(working, request.variables, request.responseTimeMs)
        bridge.phase = request.phase
        bridge.seedShared(request.shared)
        GlobalVariableStore.clear()
        request.globals.forEach { (k, v) -> GlobalVariableStore.set(k, v) }
        val result = runCatching {
            JsScriptEngine.eval(request.source, PmPrelude.SOURCE, bridge, request.timeoutMs, request.maxInstructions, epilogue = PmPrelude.EPILOGUE)
        }.getOrElse { JsScriptEngine.EvalResult(ok = false, error = "invalid script config: ${it.message}") }
        val tests = bridge.recordedTests.map { TestDto(it.name, it.passed, it.message) }
        if (!result.ok) {
            return ScriptResponseEnvelope(
                ok = false, shared = bridge.sharedContext, globals = GlobalVariableStore.snapshot(),
                console = bridge.consoleOutput, tests = tests, error = result.error ?: "script failed without an error message", pid = pid,
            )
        }
        val out = MessageDto.fromMessage(working, maxBodyBytes = Int.MAX_VALUE).copy(bodyReplaced = working.bodyReplaced)
        return ScriptResponseEnvelope(
            ok = true, aborted = result.value == "ABORT", message = out, shared = bridge.sharedContext,
            globals = GlobalVariableStore.snapshot(), console = bridge.consoleOutput, tests = tests, pid = pid,
        )
    }
}
