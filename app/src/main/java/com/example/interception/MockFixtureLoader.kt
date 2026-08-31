package com.example.interception

import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import java.io.File

/**
 * Master plan §3 LOW-ADD — file-backed mock fixture support, now byte-exact (§2.14(b)/§6
 * follow-up).
 *
 * [RuleEnforcer]'s `bodyReplace` value (used by `MODIFY_REQUEST`/`MODIFY_RESPONSE`/
 * `MOCK_RESPONSE` rules) is either a literal string baked directly into the rule's
 * `replacementValue` JSON, or an *opt-in* indirection: a `bodyReplace` value of the form
 * `file://<absolute-path>` is resolved to that file's real contents instead of being used
 * literally. Anything else (including a value that merely looks like a URL but isn't the
 * `file://` scheme) passes through completely unchanged, so every existing rule with a literal
 * `bodyReplace` keeps behaving exactly as before.
 *
 * [resolve] is the byte-exact API ([RuleEnforcer]'s three call sites use it via [applyTo]/
 * [asMockBody]): it reads the *raw bytes* of a `file://` fixture and classifies them with the
 * exact same [HttpBodyDecoder.isProbablyUtf8] heuristic the capture path uses, so a text fixture
 * (JSON/HTML/plain text) still comes back as a UTF-8 [Resolution.Text] (unchanged behavior from
 * before this fixed the binary case), while a genuinely binary fixture (PNG, protobuf, a raw
 * gzip blob, ...) comes back as [Resolution.Binary] with the real bytes intact -- no UTF-8
 * round trip corrupts it.
 *
 * [resolveBodyReplace] is kept as the original text-only API, unchanged, for any caller that
 * only ever wanted a `String` (and for the pre-existing test suite that pins its exact
 * behavior, including its "fall back to the raw `file://...` value on error" default).
 */
object MockFixtureLoader {
    private const val FILE_SCHEME_PREFIX = "file://"

    const val DEFAULT_MAX_FIXTURE_BYTES: Long = 5L * 1024 * 1024

    /**
     * Result of [resolve]. Exactly one of these three, never a silent empty-string stand-in for
     * "something went wrong" -- that was the § the previous file-only implementation got wrong.
     */
    sealed interface Resolution {
        /** A literal (non-`file://`) [bodyReplace] value, or a `file://` fixture whose bytes
         *  are valid UTF-8 text ([HttpBodyDecoder.isProbablyUtf8]). Behaves exactly like the
         *  pre-binary-support implementation for this case. */
        data class Text(val text: String) : Resolution

        /** A `file://` fixture whose bytes are NOT valid UTF-8 text -- the real, byte-exact
         *  fixture contents (image, protobuf, gzip, ...). */
        data class Binary(val bytes: ByteArray) : Resolution

        /** The `file://` reference could not be honored: missing file, unreadable file, or a
         *  file bigger than the configured cap. [message] is a specific, human-readable reason
         *  -- callers must surface this (e.g. log it) rather than silently falling through to an
         *  empty body, which would misrepresent a broken mock config as "mock returns nothing"
         *  instead of "this fixture reference is broken". */
        data class Error(val message: String) : Resolution
    }

    /**
     * Resolves a `bodyReplace` value into a [Resolution]. Never throws.
     *
     * A `file://` reference reads the file's raw bytes (bounded by [maxBytes], the same
     * decompression-bomb-style guard [resolveBodyReplace] already had) and classifies them:
     * UTF-8 text stays a [Resolution.Text] (byte-for-byte identical to what [resolveBodyReplace]
     * would have returned for that same file); anything else becomes [Resolution.Binary] with
     * the exact bytes, never a lossy/placeholder string.
     */
    fun resolve(raw: String?, maxBytes: Long = DEFAULT_MAX_FIXTURE_BYTES): Resolution {
        if (raw == null) return Resolution.Text("")
        if (!raw.startsWith(FILE_SCHEME_PREFIX)) return Resolution.Text(raw)

        val path = raw.removePrefix(FILE_SCHEME_PREFIX)
        val file = File(path)
        if (!file.isFile) return Resolution.Error("fixture file not found: $path")
        if (file.length() > maxBytes) {
            return Resolution.Error("fixture file too large (${file.length()} > $maxBytes bytes): $path")
        }

        val bytes = runCatching { file.readBytes() }.getOrElse {
            return Resolution.Error("fixture file unreadable: $path (${it.message})")
        }
        return if (HttpBodyDecoder.isProbablyUtf8(bytes)) {
            Resolution.Text(String(bytes, Charsets.UTF_8))
        } else {
            Resolution.Binary(bytes)
        }
    }

    /**
     * Applies [resolution] to an existing [message] (the `applyRequestModification`/
     * `applyResponseModification` shape): [Resolution.Text] sets [ParsedHttpMessage.body] as
     * before; [Resolution.Binary] sets [ParsedHttpMessage.replacedBodyBytes] to the byte-exact
     * fixture bytes and leaves [ParsedHttpMessage.body] at
     * [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] (mirrors how a genuinely binary captured body is
     * represented -- see that constant's doc); [Resolution.Error] logs [onError] and leaves
     * [message] completely untouched (no `bodyReplaced` flip, no empty-body regression) --
     * matches [RuleEnforcer.applyBodyCrypto]'s fail-open discipline on failure.
     */
    fun applyTo(message: ParsedHttpMessage, resolution: Resolution, onError: (String) -> Unit) {
        when (resolution) {
            is Resolution.Text -> {
                message.body = resolution.text
                message.replacedBodyBytes = null
                message.isBodyBinary = false
                message.bodyReplaced = true
            }
            is Resolution.Binary -> {
                message.body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER
                message.replacedBodyBytes = resolution.bytes
                message.isBodyBinary = true
                message.bodyReplaced = true
            }
            is Resolution.Error -> onError(resolution.message)
        }
    }

    /**
     * The original text-only API: resolves a `bodyReplace` value, returning a plain [String].
     * Kept byte-for-byte behaviorally identical to the pre-binary-support implementation
     * (including its default "fall back to the raw [raw] value on error" behavior) for any
     * caller/test that only ever wanted a `String` and doesn't need [resolve]'s error/binary
     * distinction.
     *
     * A [Resolution.Binary] fixture degrades to [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] here
     * (the same lossy-but-honest placeholder a genuinely binary captured body gets) -- callers
     * that need the real bytes for a binary fixture must use [resolve]/[applyTo] instead.
     */
    fun resolveBodyReplace(raw: String?, maxBytes: Long = DEFAULT_MAX_FIXTURE_BYTES, fallbackOnError: String? = raw): String? {
        if (raw == null) return null
        return when (val resolution = resolve(raw, maxBytes)) {
            is Resolution.Text -> resolution.text
            is Resolution.Binary -> HttpBodyDecoder.BINARY_BODY_PLACEHOLDER
            is Resolution.Error -> fallbackOnError
        }
    }

    /** Whether [raw] is a `file://` fixture reference (vs. a literal body). Pure, for UI/tests. */
    fun isFileFixtureReference(raw: String?): Boolean = raw?.startsWith(FILE_SCHEME_PREFIX) == true
}
