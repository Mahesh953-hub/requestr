package com.example.interception

import java.io.File

/**
 * Master plan §3 LOW-ADD — file-backed mock fixture support.
 *
 * [RuleEnforcer]'s `bodyReplace` value (used by `MODIFY_REQUEST`/`MODIFY_RESPONSE`/
 * `MOCK_RESPONSE` rules) is currently always a literal string baked directly into the rule's
 * `replacementValue` JSON. That's awkward for large/binary/frequently-edited fixtures (a big
 * JSON payload, an image, a golden HAR-derived body) since editing it means re-typing the whole
 * rule. [resolveBodyReplace] adds an *opt-in* indirection: a `bodyReplace` value of the form
 * `file://<absolute-path>` is resolved to that file's contents (UTF-8 text) instead of being used
 * literally; anything else (including a value that merely looks like a URL but isn't the
 * `file://` scheme) passes through completely unchanged, so every existing rule with a literal
 * `bodyReplace` keeps behaving exactly as before.
 *
 * **Not wired into [RuleEnforcer] itself** ([RuleEnforcer.kt] is out of scope to edit for this
 * change). The exact three call sites a future edit to `RuleEnforcer.kt` would wrap with this
 * resolver:
 *
 *  - `RuleEnforcer.applyRequestModification`, `if (json.has("bodyReplace")) request.body =
 *    json.getString("bodyReplace")` -- wrap the right-hand side in
 *    `MockFixtureLoader.resolveBodyReplace(json.getString("bodyReplace"))`.
 *  - `RuleEnforcer.applyResponseModification`, same shape for `response.body`.
 *  - `RuleEnforcer.buildMockResponse`, `if (json.has("bodyReplace")) body =
 *    json.getString("bodyReplace")` -- same wrap for the mock body.
 *
 * All three are one-line changes once `RuleEnforcer.kt` is back in scope; nothing else about the
 * rule schema or engines needs to change.
 */
object MockFixtureLoader {
    private const val FILE_SCHEME_PREFIX = "file://"

    /**
     * Resolves a `bodyReplace` value: if it starts with `file://`, reads and returns that file's
     * contents as UTF-8 text (or [fallbackOnError] if the file is missing/unreadable/too large);
     * otherwise returns [raw] unchanged. Never throws.
     */
    fun resolveBodyReplace(raw: String?, maxBytes: Long = DEFAULT_MAX_FIXTURE_BYTES, fallbackOnError: String? = raw): String? {
        if (raw == null || !raw.startsWith(FILE_SCHEME_PREFIX)) return raw
        val path = raw.removePrefix(FILE_SCHEME_PREFIX)
        return runCatching {
            val file = File(path)
            require(file.isFile) { "not a regular file: $path" }
            require(file.length() <= maxBytes) { "fixture file too large (${file.length()} > $maxBytes bytes): $path" }
            file.readText(Charsets.UTF_8)
        }.getOrElse { fallbackOnError }
    }

    /** Whether [raw] is a `file://` fixture reference (vs. a literal body). Pure, for UI/tests. */
    fun isFileFixtureReference(raw: String?): Boolean = raw?.startsWith(FILE_SCHEME_PREFIX) == true

    const val DEFAULT_MAX_FIXTURE_BYTES: Long = 5L * 1024 * 1024
}
