package com.example.interception.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Content-Encoding-aware body decoding + binary detection.
 *
 * Extracted out of [HttpParser] into its own Android-framework-free object so both halves
 * are directly unit-testable on the plain JVM (no Robolectric needed).
 *
 * §1.4 of docs/plans/MASTER-FEATURE-ADDITION-PLAN.md ("the single highest-value,
 * lowest-effort fix in this entire plan"): [HttpParser] used to force-decode every
 * request/response body as UTF-8 with zero Content-Encoding inspection, so gzip'd JSON
 * (the majority of real API traffic) was captured and displayed as garbled bytes. [decode]
 * reads the declared Content-Encoding and inflates gzip/deflate *before* any charset
 * decoding happens. `br` and `zstd` have no JDK/Android-stdlib decoder, so those are
 * detected and reported as [Encoding.UNDECODABLE] instead of being force-decoded into
 * garbage.
 *
 * §2.7: even after Content-Encoding is handled, some bodies are simply not text (images,
 * protobufs, etc). Force-UTF8-decoding those produces corrupt, storage-bloating text (every
 * invalid byte becomes a multi-byte U+FFFD replacement character). [isProbablyUtf8] ports
 * the heuristic used by ihsanbal/LoggingInterceptor (itself the OkHttp logging-interceptor
 * heuristic): decode a bounded prefix of the bytes as strict UTF-8 and reject it as text if
 * decoding fails or if it contains non-whitespace ISO control characters.
 */
object HttpBodyDecoder {

    /**
     * Hard cap on decompressed output size. Guards against decompression-bomb bodies
     * (a small compressed payload that expands to gigabytes) turning a single captured
     * response into an OOM. Chosen generously above any real API payload we expect to see.
     */
    private const val MAX_DECOMPRESSED_BYTES = 10 * 1024 * 1024 // 10 MB

    /**
     * Only this many leading bytes are sniffed by [isProbablyUtf8]. Bodies captured by the
     * proxy can be large; the binary/text determination only needs a prefix, so the cost is
     * bounded regardless of body size.
     */
    private const val UTF8_SNIFF_PREFIX_BYTES = 8 * 1024

    /**
     * Body text stored/displayed in place of a payload [isProbablyUtf8] flagged as binary.
     * Currently, literally, the empty string -- the original bytes are dropped from [decode]'s
     * `text` output entirely, not marked with some sentinel that still carries them. Real
     * byte-for-byte fidelity for a binary/undecodable body comes from
     * [ParsedHttpMessage.rawBodyBytes] instead (populated by [HttpParser], which calls
     * [decode] on the raw wire bytes before this placeholder ever gets substituted in).
     * Deliberately not the raw bytes here: force-decoding or byte-preserving-encoding binary
     * data into a Kotlin/Java (UTF-16) String is exactly the "corrupt, storage-bloating text"
     * behavior §2.7 exists to avoid.
     *
     * WARNING: do not change this to some non-empty human-readable placeholder without first
     * checking every place [text]/[ParsedHttpMessage.body] gets written back onto the wire
     * (MitmProxyEngine's serializeRequest/serializeResponse). Those key off
     * [ParsedHttpMessage.bodyReplaced] (not [ParsedHttpMessage.isBodyBinary]) specifically so a
     * non-replaced body is always re-serialized from `rawBodyBytes` verbatim, never from this
     * value -- but if some future caller ever *did* write this placeholder onto the wire, a
     * non-empty string here would start appearing as literal body content in place of every
     * image/protobuf/undecodable response instead of an empty one.
     */
    const val BINARY_BODY_PLACEHOLDER = ""

    /** Content-Encoding values this JDK/Android has no decoder for. */
    private val UNDECODABLE_ENCODINGS = setOf("br", "zstd", "compress")

    /** The Content-Encoding a body declared, independent of whether we could act on it. */
    enum class Encoding {
        /** No Content-Encoding header, or explicitly "identity". */
        IDENTITY,
        GZIP,
        DEFLATE,
        /** Recognized but no JDK/Android-stdlib decoder exists (br, zstd, compress). */
        UNDECODABLE,
        /** Some other/unrecognized token. Treated like [UNDECODABLE]: left alone. */
        UNKNOWN,
    }

    /**
     * Result of running [decode] over a raw body.
     *
     * @property text the body text to store/display: decoded charset text for textual
     *   bodies, or [BINARY_BODY_PLACEHOLDER] when [isBinary] is true.
     * @property isBinary true when the final bytes (after any decompression attempt) failed
     *   [isProbablyUtf8] -- callers should treat this payload as opaque binary, not text.
     * @property encoding the Content-Encoding the body declared (IDENTITY when absent).
     * @property encodingConsumed true iff decompression was actually applied, meaning [text]
     *   no longer corresponds to the declared Content-Encoding: any re-serialization of this
     *   message onto the wire must drop/adjust that header (see MitmProxyEngine's
     *   serializeRequest/serializeResponse). False for IDENTITY, UNDECODABLE/UNKNOWN (nothing
     *   was done to the bytes), and for a decompression attempt that failed (falls back to
     *   treating the still-encoded raw bytes as the body, matching pre-fix behavior).
     */
    data class DecodedBody(
        val text: String,
        val isBinary: Boolean,
        val encoding: Encoding,
        val encodingConsumed: Boolean,
    )

    /**
     * Decodes [rawBody] according to the declared `Content-Encoding` header value
     * ([contentEncodingHeader], may be null/blank for identity), then runs the [isProbablyUtf8]
     * binary heuristic over the resulting bytes.
     *
     * Never throws: a malformed/truncated compressed body must not kill the parser (and, by
     * extension, the MITM relay) -- any decompression failure silently falls back to treating
     * [rawBody] as-is, exactly as the parser behaved before this fix existed.
     */
    fun decode(rawBody: ByteArray, contentEncodingHeader: String?): DecodedBody {
        val encoding = classify(contentEncodingHeader)

        val decompressed: ByteArray? = when (encoding) {
            Encoding.GZIP -> runCatching { gunzip(rawBody) }.getOrNull()
            Encoding.DEFLATE -> runCatching { inflate(rawBody) }.getOrNull()
            Encoding.IDENTITY, Encoding.UNDECODABLE, Encoding.UNKNOWN -> null
        }
        val finalBytes = decompressed ?: rawBody
        val encodingConsumed = decompressed != null

        val binary = !isProbablyUtf8(finalBytes)
        val text = if (binary) {
            BINARY_BODY_PLACEHOLDER
        } else {
            runCatching { String(finalBytes, Charsets.UTF_8) }.getOrDefault(BINARY_BODY_PLACEHOLDER)
        }

        return DecodedBody(text = text, isBinary = binary, encoding = encoding, encodingConsumed = encodingConsumed)
    }

    private fun classify(contentEncodingHeader: String?): Encoding {
        val enc = contentEncodingHeader?.trim()?.lowercase().orEmpty()
        return when {
            enc.isEmpty() || enc == "identity" -> Encoding.IDENTITY
            enc == "gzip" || enc == "x-gzip" -> Encoding.GZIP
            enc == "deflate" -> Encoding.DEFLATE
            enc in UNDECODABLE_ENCODINGS -> Encoding.UNDECODABLE
            else -> Encoding.UNKNOWN
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it) }
    }

    /**
     * RFC 7230 says "deflate" means zlib-wrapped DEFLATE (RFC 1950), but a large fraction of
     * real servers send raw DEFLATE (RFC 1951, no zlib header/checksum) under that same
     * Content-Encoding token. Try zlib-wrapped first, then fall back to raw/nowrap.
     */
    private fun inflate(bytes: ByteArray): ByteArray {
        return runCatching { inflateWith(bytes, nowrap = false) }
            .getOrElse { inflateWith(bytes, nowrap = true) }
    }

    private fun inflateWith(bytes: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        return try {
            InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { readBounded(it) }
        } finally {
            inflater.end()
        }
    }

    /** Reads [stream] to completion, throwing if the output would exceed [MAX_DECOMPRESSED_BYTES]. */
    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_DECOMPRESSED_BYTES) {
                throw IOException("Decompressed body exceeds cap of $MAX_DECOMPRESSED_BYTES bytes")
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Heuristic port of the isProbablyUtf8() shape used by ihsanbal/LoggingInterceptor (based
     * on OkHttp's own logging-interceptor heuristic): strictly decode a bounded prefix of
     * [bytes] as UTF-8 and reject it as text if decoding fails (malformed/truncated
     * multi-byte sequence -- the classic signature of raw binary data) or if it contains a
     * non-whitespace ISO control character (NUL, etc -- something real JSON/text/HTML never
     * legitimately contains, but random binary bytes decode into "successfully" more than one
     * might expect).
     *
     * SHOULD-FIX 1: the sniff window is a raw byte-count cutoff ([UTF8_SNIFF_PREFIX_BYTES]),
     * which can land in the middle of a legitimate multi-byte UTF-8 sequence when [bytes] is
     * longer than the window. Decoding that truncated tail strictly would throw
     * [CharacterCodingException] and misclassify perfectly good text as binary purely because
     * of where the cut happened to fall. [truncateToLastCompleteUtf8Boundary] walks the cut
     * point back to the start of whatever multi-byte sequence straddles it, if any, before the
     * strict decode ever runs.
     *
     * An empty body is considered text (nothing to prove otherwise).
     */
    fun isProbablyUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val rawPrefixLen = bytes.size.coerceAtMost(UTF8_SNIFF_PREFIX_BYTES)
        val prefixLen = truncateToLastCompleteUtf8Boundary(bytes, rawPrefixLen)

        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, prefixLen))
        } catch (_: CharacterCodingException) {
            return false
        }

        val decodedText = decoded.toString()
        var i = 0
        while (i < decodedText.length) {
            val codePoint = decodedText.codePointAt(i)
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) return false
            i += Character.charCount(codePoint)
        }
        return true
    }

    /**
     * If [cutAt] (< [bytes].size, i.e. the sniff window actually truncated something) falls in
     * the middle of a multi-byte UTF-8 sequence, moves the cut back to that sequence's first
     * (lead) byte so the truncated tail is excluded from strict decoding entirely, rather than
     * feeding the decoder a guaranteed-malformed partial sequence. Only ever shrinks [cutAt];
     * never grows it (never reads past the original window).
     */
    private fun truncateToLastCompleteUtf8Boundary(bytes: ByteArray, cutAt: Int): Int {
        if (cutAt >= bytes.size) return cutAt // nothing was actually truncated

        // Walk back over UTF-8 continuation bytes (10xxxxxx) to find the lead byte of the
        // sequence straddling the cut, if any. A well-formed UTF-8 sequence is at most 4 bytes.
        var leadIndex = cutAt - 1
        var stepsBack = 0
        while (leadIndex >= 0 && stepsBack < 3 && (bytes[leadIndex].toInt() and 0xC0) == 0x80) {
            leadIndex--
            stepsBack++
        }
        if (leadIndex < 0) return cutAt // no lead byte within the lookback window; leave as-is

        val leadByte = bytes[leadIndex].toInt() and 0xFF
        val sequenceLength = when {
            leadByte and 0x80 == 0x00 -> 1 // ASCII
            leadByte and 0xE0 == 0xC0 -> 2
            leadByte and 0xF0 == 0xE0 -> 3
            leadByte and 0xF8 == 0xF0 -> 4
            else -> 1 // stray continuation byte with no valid lead -- nothing coherent to trim
        }
        val sequenceEnd = leadIndex + sequenceLength
        // Only trim back if that sequence would have run past the cut (a genuine straddle);
        // otherwise the cut already lands on a clean boundary and must be left untouched.
        return if (sequenceEnd > cutAt) leadIndex else cutAt
    }
}
