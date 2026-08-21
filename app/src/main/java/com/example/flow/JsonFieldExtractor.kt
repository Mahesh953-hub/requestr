package com.example.flow

/**
 * Shared JSONPath-like top-level key extractor.
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
 * Deliberately NOT a real JSONPath implementation: it strips a leading `$.`/`.` and matches the
 * remaining string as a single top-level JSON key via regex (string value first, falling back to
 * a bare/non-string value). This mirrors the pre-C2 behavior exactly — nested paths like
 * `$.data.token` do not traverse into `data`, they just look for a literal `"data.token"` key
 * (which will not usually exist). Extending this to real nested traversal is out of scope for
 * C2; flat top-level keys (the common case: `token`, `id`, `session_id`, etc.) work correctly.
 */
object JsonFieldExtractor {

    /** Strips a leading `$.`/`.` and surrounding quotes, mirroring the pre-C2 inline behavior. */
    fun cleanPath(path: String): String =
        path.trim().removePrefix("$.").removePrefix(".").removeSurrounding("\"")

    /**
     * Extracts the value of [path] (after [cleanPath]) from [json]. Returns null if [json] is
     * not parseable-enough for a match or the key is not present.
     */
    fun extract(json: String, path: String): String? {
        val cleanKey = cleanPath(path)
        if (cleanKey.isEmpty()) return null
        return extractByKey(json, cleanKey)
    }

    /** Same regex pair as the original [FlowExecutor] implementation, key already cleaned. */
    fun extractByKey(json: String, cleanKey: String): String? {
        val stringRegex = Regex("\"$cleanKey\"\\s*:\\s*\"([^\"]*)\"")
        return stringRegex.find(json)?.groupValues?.get(1)
            ?: Regex("\"$cleanKey\"\\s*:\\s*(\\S+)").find(json)?.groupValues?.get(1)
    }
}
