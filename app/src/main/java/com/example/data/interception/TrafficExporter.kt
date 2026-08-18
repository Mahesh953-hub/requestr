package com.example.data.interception

import com.example.data.models.CapturedTrafficItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export helpers for a captured transaction.
 *
 * These operate strictly on what was actually observed. Absent bodies and headers stay absent —
 * nothing is synthesised to make the output look complete.
 */
object TrafficExporter {

    /** Renders a real, runnable cURL command for the observed request. */
    fun toCurl(item: CapturedTrafficItem): String {
        val sb = StringBuilder("curl -X ${item.method.name}")
        sb.append(" '${item.url}'")
        item.requestHeaders
            .filterKeys { it.isNotBlank() }
            .forEach { (key, value) ->
                sb.append(" \\\n  -H '").append(key).append(": ").append(value.replace("'", "'\\''")).append("'")
            }
        if (item.requestBody.isNotBlank()) {
            sb.append(" \\\n  --data-raw '").append(item.requestBody.replace("'", "'\\''")).append("'")
        }
        return sb.toString()
    }

    /**
     * Renders a single-entry HAR 1.2 document.
     *
     * Sizes and timings come from the recorded values; unknown values are emitted as -1, which is
     * what HAR defines for "not available", rather than a fabricated number.
     */
    fun toHar(item: CapturedTrafficItem): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun headerArray(headers: Map<String, String>): JSONArray {
            val arr = JSONArray()
            headers.forEach { (k, v) ->
                arr.put(JSONObject().put("name", k).put("value", v))
            }
            return arr
        }

        val request = JSONObject()
            .put("method", item.method.name)
            .put("url", item.url)
            .put("httpVersion", item.protocol.ifBlank { "HTTP/1.1" })
            .put("headers", headerArray(item.requestHeaders))
            .put("queryString", JSONArray())
            .put("headersSize", -1)
            .put("bodySize", if (item.requestSize > 0) item.requestSize else -1)
        if (item.requestBody.isNotBlank()) {
            request.put(
                "postData",
                JSONObject()
                    .put("mimeType", item.requestHeaders.entries
                        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                        ?.value ?: "application/octet-stream")
                    .put("text", item.requestBody)
            )
        }

        val content = JSONObject()
            .put("size", if (item.responseSize > 0) item.responseSize else -1)
            .put(
                "mimeType",
                item.responseHeaders.entries
                    .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value ?: ""
            )
        if (item.responseBody.isNotBlank()) content.put("text", item.responseBody)

        val response = JSONObject()
            // A transaction with no decoded status keeps status 0, the HAR convention for
            // "no response was captured", instead of pretending it was 200.
            .put("status", item.statusCode)
            .put("statusText", item.statusMessage)
            .put("httpVersion", item.protocol.ifBlank { "HTTP/1.1" })
            .put("headers", headerArray(item.responseHeaders))
            .put("cookies", JSONArray())
            .put("content", content)
            .put("redirectURL", "")
            .put("headersSize", -1)
            .put("bodySize", if (item.responseSize > 0) item.responseSize else -1)

        val entry = JSONObject()
            .put("startedDateTime", iso.format(Date(item.timestamp)))
            .put("time", item.latencyMs)
            .put("request", request)
            .put("response", response)
            .put("cache", JSONObject())
            .put(
                "timings",
                JSONObject()
                    .put("send", -1)
                    .put("wait", item.latencyMs)
                    .put("receive", -1)
            )

        val log = JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "requestr").put("version", "2.2"))
            .put("entries", JSONArray().put(entry))

        return JSONObject().put("log", log).toString(2)
    }
}
