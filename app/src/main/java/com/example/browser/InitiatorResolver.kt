package com.example.browser

import com.example.correlation.StackFrame
import org.json.JSONObject

/**
 * B4: Initiator correlation.
 *
 * CDP's `Network.requestWillBeSent` event carries an `initiator` object describing what
 * *caused* the request. Its shape depends on `initiator.type`:
 *
 *  - `"parser"`: `{"type":"parser","url":"https://...","lineNumber":N}` — caused by HTML
 *    parsing (e.g. an `<img src>`/`<script src>` tag).
 *  - `"script"`: `{"type":"script","stack":{"callFrames":[{"functionName":..,"url":..,
 *    "lineNumber":..,"columnNumber":..}, ...]}}` — caused by JS (fetch/XHR/etc.), with a
 *    real JS call stack captured by V8 at request time.
 *  - `"preload"`, `"other"`: no extra structured info beyond the type itself.
 *
 * [StackTraceResolver] turns the CDP `stack.callFrames` array into requestr's shared
 * [StackFrame] model (the same model [com.example.runtime.RuntimeInstrumentationEngine] uses
 * for Frida/Xposed stack frames), and [InitiatorResolver] turns the whole `initiator` object
 * into one human-readable line for display next to a captured browser request — e.g.
 * `"script: fetchData (app.js:42:13)"` or `"parser: index.html:10"`.
 */
object InitiatorResolver {

    /**
     * Render a CDP `initiator` JSON object as a single human-readable string.
     * Never throws — falls back to the initiator's raw `type` on anything unexpected.
     */
    fun describe(initiator: JSONObject?): String {
        if (initiator == null) return "unknown"
        val type = initiator.optString("type", "other")
        return when (type) {
            "parser" -> {
                val url = initiator.optString("url", "")
                val line = initiator.optInt("lineNumber", -1)
                if (url.isNotEmpty()) "parser: ${shortUrl(url)}${if (line >= 0) ":${line + 1}" else ""}"
                else "parser"
            }
            "script" -> {
                val frames = StackTraceResolver.resolve(initiator.optJSONObject("stack"))
                val top = frames.firstOrNull()
                if (top != null) {
                    val fn = top.methodName.ifEmpty { "(anonymous)" }
                    val loc = top.fileName?.let { "${shortUrl(it)}:${(top.lineNumber ?: 0)}" } ?: "?"
                    "script: $fn ($loc)"
                } else "script"
            }
            "preload" -> "preload"
            "SignedExchange" -> "signed-exchange"
            else -> type
        }
    }

    /** Full resolved call stack (for detail views), not just the top frame's summary line. */
    fun resolveStack(initiator: JSONObject?): List<StackFrame> {
        if (initiator == null) return emptyList()
        if (initiator.optString("type") != "script") return emptyList()
        return StackTraceResolver.resolve(initiator.optJSONObject("stack"))
    }

    private fun shortUrl(url: String): String = runCatching {
        val u = java.net.URI(url)
        (u.path?.substringAfterLast('/') ?: url).ifEmpty { url }
    }.getOrDefault(url)
}

/**
 * Converts a CDP `Runtime.StackTrace` object (`{"callFrames":[{"functionName","url",
 * "lineNumber","columnNumber","scriptId"}, ...], "parent": {...}}`) into requestr's
 * shared [StackFrame] list. CDP line/column numbers are 0-based; this resolver converts
 * them to the conventional 1-based numbers used everywhere else in the app (matches how
 * [com.example.runtime.RuntimeInstrumentationEngine] frames are already displayed).
 *
 * `parent` (async stack continuation, e.g. across a Promise) is flattened into the same
 * list, appended after the immediate frames, since the UI only needs a linear call chain.
 */
object StackTraceResolver {
    fun resolve(stackTrace: JSONObject?): List<StackFrame> {
        if (stackTrace == null) return emptyList()
        val result = mutableListOf<StackFrame>()
        var current: JSONObject? = stackTrace
        var depth = 0
        while (current != null && depth < 8) { // bound recursion on pathological/cyclic input
            val frames = current.optJSONArray("callFrames")
            if (frames != null) {
                for (i in 0 until frames.length()) {
                    val f = frames.getJSONObject(i)
                    result.add(
                        StackFrame(
                            className = f.optString("scriptId", ""),
                            methodName = f.optString("functionName", "").ifEmpty { "(anonymous)" },
                            fileName = f.optString("url", "").ifEmpty { null },
                            lineNumber = f.optInt("lineNumber", -1).let { if (it >= 0) it + 1 else null },
                        )
                    )
                }
            }
            current = current.optJSONObject("parent")
            depth++
        }
        return result
    }
}
