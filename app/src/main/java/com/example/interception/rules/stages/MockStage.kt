package com.example.interception.rules.stages

import android.util.Log
import com.example.interception.MockFixtureLoader
import com.example.interception.RuleTemplateExpander
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodySource
import com.example.interception.rules.MockConfig

/**
 * S4 task 26/29: the MOCK slot -- synthesizes the response for a matched [MockConfig] without
 * dialing upstream. Byte-level parity with the legacy `RuleEnforcer.buildMockResponse` (G16):
 *  - header names lower-cased, values templated from the bare request URL;
 *  - `content-type` auto-filled (`application/octet-stream` for a binary fixture,
 *    `application/json; charset=utf-8` when the body parses as JSON, else `text/plain`);
 *  - `content-length` always recomputed from the real body bytes;
 *  - a broken `file://` fixture logs and falls back to the empty body, never leaks the reference.
 */
object MockStage {
    private const val TAG = "MockStage"

    fun build(config: MockConfig, requestUrl: String = "", method: String? = null, requestHeaders: Map<String, String> = emptyMap()): ParsedHttpMessage {
        val context = RuleTemplateExpander.Context.fromRequestUrl(requestUrl, method, requestHeaders)
        fun tmpl(value: String) = RuleTemplateExpander.expand(value, config.variables, context)

        var body = ""
        var bodyBytes: ByteArray? = null
        val raw: String? = when (val source = config.body) {
            BodySource.Empty -> null
            is BodySource.Text -> source.value
            is BodySource.File -> if (source.path.startsWith(BodySource.FILE_SCHEME)) source.path else BodySource.FILE_SCHEME + source.path
        }
        if (raw != null) {
            when (val resolution = MockFixtureLoader.resolve(tmpl(raw))) {
                is MockFixtureLoader.Resolution.Text -> body = resolution.text
                is MockFixtureLoader.Resolution.Binary -> bodyBytes = resolution.bytes
                is MockFixtureLoader.Resolution.Error ->
                    Log.w(TAG, "mock body fixture failed, using empty body: ${resolution.message}")
            }
        }
        val headers = linkedMapOf<String, String>()
        for ((k, v) in config.headers) headers[k.lowercase()] = tmpl(v)

        val isBinary = bodyBytes != null
        val realBodyLength = bodyBytes?.size ?: body.toByteArray(Charsets.UTF_8).size
        if (!headers.containsKey("content-type")) {
            headers["content-type"] = when {
                isBinary -> "application/octet-stream"
                looksLikeJson(body) -> "application/json; charset=utf-8"
                else -> "text/plain; charset=utf-8"
            }
        }
        headers["content-length"] = realBodyLength.toString()

        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            url = requestUrl,
            statusCode = config.statusCode,
            statusMessage = config.statusMessage,
            headers = headers,
            body = if (isBinary) HttpBodyDecoder.BINARY_BODY_PLACEHOLDER else body,
            isBodyBinary = isBinary,
            replacedBodyBytes = bodyBytes,
            bodyReplaced = true,
        )
    }

    internal fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            when (trimmed.first()) {
                '{' -> { org.json.JSONObject(trimmed); true }
                '[' -> { org.json.JSONArray(trimmed); true }
                else -> false
            }
        }.getOrDefault(false)
    }
}
