package com.example.flow

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Shared JSONPath-like nested-path extractor.
 *
 * v3.2 Part C — C2 (Request chaining): this is the exact extraction logic that used to live
 * privately inside [FlowExecutor.extractJsonField]/`executeTransformNode`'s path cleanup. C2's
 * plan explicitly says to "reuse that extraction logic rather than building a second, different
 * one" for pulling variables out of an API Testr response body, so it has been pulled out into
 * this standalone object. [FlowExecutor] now delegates to [extract]/[cleanPath] instead of
 * keeping its own private copy, and the request-chaining feature
 * ([com.example.data.repository.ApiCollectionsRepository] extraction rules, run from
 * `ApiTestrScreen`'s Send handler) calls the same functions — one extractor, two call sites.
 *
 * Master plan §2.11: until this change, [extract] was deliberately NOT a real JSONPath
 * implementation — it stripped a leading `$.`/`.` and matched the remaining string as a single
 * TOP-LEVEL JSON key via regex, so `$.data.token` looked for a literal `"data.token"` key and
 * never traversed into `data`. [extract] now parses the body as real JSON and walks dotted /
 * array-indexed paths (`$.data.items[0].id`, and the bare equivalent `data.items.0.id`) via
 * [extractNested] first. Two things are preserved on purpose, because both are load-bearing for
 * existing call sites and tests:
 *  - a single flat top-level key (`token`, `id`, ...) is never routed through JSON parsing at
 *    all — it goes straight to the legacy [extractByKey] regex, so the pre-§2.11 behavior for
 *    the common case (including on a body that is not even valid JSON, which the regex still
 *    "works" against) is byte-for-byte unchanged.
 *  - [extractNested] never throws: a body that fails to parse, or a path segment that does not
 *    resolve (wrong type, missing key, out-of-range index), simply falls back to [extractByKey]
 *    exactly like the old single-key behavior, rather than surfacing a parse error.
 */
object JsonFieldExtractor {

    /** Strips a leading `$.`/`.` and surrounding quotes, mirroring the pre-C2 inline behavior. */
    fun cleanPath(path: String): String =
        path.trim().removePrefix("$.").removePrefix(".").removeSurrounding("\"")

    /**
     * Extracts the value at [path] (after [cleanPath]) from [json].
     *
     * §2.11: for a multi-segment path (contains `.` or `[`) this first tries real JSON
     * traversal via [extractNested]; if that fails for any reason (not valid JSON, path does
     * not resolve) it falls back to the legacy single-key [extractByKey] regex, matching the
     * pre-§2.11 behavior for that path shape. A single flat segment always goes straight to
     * [extractByKey], unchanged from before this change.
     */
    fun extract(json: String, path: String): String? {
        val cleanKey = cleanPath(path)
        if (cleanKey.isEmpty()) return null
        if (isNestedPath(cleanKey)) {
            extractNested(json, cleanKey)?.let { return it }
        }
        return extractByKey(json, cleanKey)
    }

    /** Same regex pair as the original [FlowExecutor] implementation, key already cleaned. */
    fun extractByKey(json: String, cleanKey: String): String? {
        val stringRegex = Regex("\"$cleanKey\"\\s*:\\s*\"([^\"]*)\"")
        return stringRegex.find(json)?.groupValues?.get(1)
            ?: Regex("\"$cleanKey\"\\s*:\\s*(\\S+)").find(json)?.groupValues?.get(1)
    }

    /** A path needs real traversal once it names more than one segment (dotted or `[n]`). */
    private fun isNestedPath(cleanKey: String): Boolean =
        cleanKey.contains('.') || cleanKey.contains('[')

    /**
     * Real nested-path traversal (§2.11). Returns null — never throws — when [json] does not
     * parse or any segment of [path] fails to resolve, so callers can fall back to the legacy
     * flat-key regex without special-casing exceptions.
     */
    private fun extractNested(json: String, path: String): String? {
        val root = runCatching { JSONTokener(json).nextValue() }.getOrNull() ?: return null
        var current: Any? = root
        for (segment in splitPath(path)) {
            if (segment.isEmpty()) continue
            current = when (val node = current) {
                is JSONObject -> if (node.has(segment)) node.opt(segment) else return null
                is JSONArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    if (index in 0 until node.length()) node.opt(index) else return null
                }
                else -> return null
            }
        }
        return stringify(current)
    }

    /** Normalizes `foo[0].bar` and the bare `foo.0.bar` form into the same segment list. */
    private fun splitPath(path: String): List<String> =
        path.replace(Regex("\\[(\\d+)]"), ".$1")
            .split('.')
            .filter { it.isNotEmpty() }

    /** Non-string leaves (numbers/booleans/null/objects/arrays) stringify sensibly for env vars. */
    private fun stringify(value: Any?): String? = when {
        value == null || value == JSONObject.NULL -> null
        value is String -> value
        else -> value.toString()
    }
}
