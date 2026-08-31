package com.example.interception.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
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

    /**
     * Hard, named cap on how many raw bytes of a single binary body get persisted verbatim
     * (BLOB column, see [com.example.data.local.CapturedTrafficEntity.requestBodyBytes] /
     * `.responseBodyBytes`) per direction of a single transaction.
     *
     * This is deliberately "store nothing" beyond the cap, not "store a truncated prefix":
     * a partial JPEG/PNG/protobuf is not renderable/decodable anyway, so a clipped blob would
     * just be dead weight. [clampForStorage] returns `bytes = null` and `truncated = true` in
     * that case; the true original length is still recoverable from the caller's already-known
     * pre-clamp byte count (persisted separately as `requestSizeBytes`/`responseSizeBytes`) --
     * see [clampForStorage]'s doc for the exact three-state contract this produces.
     *
     * 2 MiB comfortably covers real-world API response images (thumbnails, icons, optimized
     * photos) without letting a single row's BLOB balloon the `captured_traffic` table --
     * bodies genuinely bigger than this are exactly the case the UI's large-body gate
     * ([com.example.ui.screens.CapturedTrafficHelpers.MAX_RENDERABLE_BODY_BYTES], a much
     * smaller 100 KiB threshold for *rendering* already-stored text) also has to worry about.
     */
    const val MAX_STORED_BODY_BYTES: Int = 2 * 1024 * 1024 // 2 MiB

    /**
     * Result of [clampForStorage]: what to actually persist as a binary-body BLOB column, and
     * whether the cap was hit.
     *
     * @property bytes the exact bytes to store, or null when [truncated] is true (the input
     *   exceeded [MAX_STORED_BODY_BYTES], so nothing usable was kept -- see [MAX_STORED_BODY_BYTES]).
     * @property truncated true iff the input exceeded [MAX_STORED_BODY_BYTES] and [bytes] is
     *   therefore null. Callers must persist this as its own row flag (never silently drop the
     *   distinction) so the UI can render "binary body, not stored -- exceeded N KB" distinctly
     *   from "no body at all" and from "binary body, stored".
     */
    data class ClampedBody(val bytes: ByteArray?, val truncated: Boolean)

    /**
     * Caps [bytes] at [MAX_STORED_BODY_BYTES] for BLOB storage. Callers still separately record
     * `bytes.size` (the true, un-clamped length) as the row's `requestSizeBytes`/
     * `responseSizeBytes` *before* calling this -- that field must reflect reality regardless of
     * whether the bytes themselves made the storage cut, which is exactly what lets the UI show
     * a correct "N KB, truncated" message even though [ClampedBody.bytes] is null.
     */
    fun clampForStorage(bytes: ByteArray): ClampedBody =
        if (bytes.size <= MAX_STORED_BODY_BYTES) {
            ClampedBody(bytes = bytes, truncated = false)
        } else {
            ClampedBody(bytes = null, truncated = true)
        }

    /**
     * Everything a [com.example.data.local.CapturedTrafficEntity] persist call needs for one
     * direction (request or response) of a transaction, computed straight from
     * [ParsedHttpMessage.isBodyBinary]/[ParsedHttpMessage.rawBodyBytes]/[ParsedHttpMessage.body]
     * so every persistence path (MitmProxyEngine.emitTransaction, CaptureEngine.persistTransaction,
     * ...) applies the exact same rule and can't drift from each other.
     *
     * @property isBinary mirrors [ParsedHttpMessage.isBodyBinary] -- write straight to
     *   `isRequestBodyBinary`/`isResponseBodyBinary`.
     * @property bytes the BLOB to persist as `requestBodyBytes`/`responseBodyBytes` -- always
     *   null when [isBinary] is false (the text column is authoritative then); may also be null
     *   when [isBinary] is true and [truncated] is true (exceeded [MAX_STORED_BODY_BYTES]).
     *   SHOULD-FIX (gzip'd-image gallery bug): for a binary body this is
     *   [ParsedHttpMessage.decodedBodyBytes] (the same, already-decompressed-if-possible bytes
     *   [isBinary] was actually classified from), never the still-wire-encoded
     *   [ParsedHttpMessage.rawBodyBytes] -- storing the latter meant a gzip-compressed JPEG was
     *   classified as binary (correctly) but persisted as gzip bytes, so `BitmapFactory` in
     *   [com.example.ui.screens.ImageGalleryScreen] tried to decode gzip data as an image and
     *   always failed. [rawBodyBytes] itself is untouched by this -- MitmProxyEngine's
     *   serializeRequest/serializeResponse still re-emit it verbatim for wire fidelity.
     * @property truncated write straight to `requestBodyBytesTruncated`/`responseBodyBytesTruncated`.
     * @property trueSizeBytes the real, un-clamped *wire* byte length -- deliberately
     *   [ParsedHttpMessage.rawBodyBytes]'s (still-encoded) length, not the decompressed
     *   [bytes]'s length, so `requestSizeBytes`/`responseSizeBytes` keep reporting what actually
     *   crossed the wire even though [bytes] itself is now decompressed content; for a text body
     *   this matches the pre-existing `body.toByteArray(UTF_8).size` computation exactly, so
     *   switching a caller over to this for text bodies is a no-op.
     */
    data class BinaryBodyStorage(
        val isBinary: Boolean,
        val bytes: ByteArray?,
        val truncated: Boolean,
        val trueSizeBytes: Long,
    )

    fun forStorage(message: ParsedHttpMessage): BinaryBodyStorage {
        if (!message.isBodyBinary) {
            return BinaryBodyStorage(
                isBinary = false,
                bytes = null,
                truncated = false,
                trueSizeBytes = message.body.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        }
        val raw = message.rawBodyBytes ?: ByteArray(0)
        // SHOULD-FIX (gzip'd-image gallery bug): store the decompressed bytes [isBinary] was
        // classified from (falling back to the wire-exact raw bytes only when decompression was
        // never attempted, e.g. a hand-built fixture with no rawBodyBytes at all) -- never the
        // still-encoded raw bytes. Size accounting (trueSizeBytes below) intentionally still
        // reads off `raw`, so it keeps reporting true wire size regardless of this.
        val decoded = message.decodedBodyBytes ?: raw
        val clamped = clampForStorage(decoded)
        return BinaryBodyStorage(
            isBinary = true,
            bytes = clamped.bytes,
            truncated = clamped.truncated,
            trueSizeBytes = raw.size.toLong(),
        )
    }

    /**
     * [forStorage] sibling for capture paths that never build a [ParsedHttpMessage] at all --
     * [com.example.interception.gateway.GatewayEngine]'s T2 LAN-proxy relay and
     * [com.example.interception.PacketPump]'s HTTP/3 (QUIC) exchange path both operate on raw
     * post-decompression [ByteArray]s directly, with no parsed-message object to hang
     * [ParsedHttpMessage.isBodyBinary]/[ParsedHttpMessage.rawBodyBytes] off of.
     *
     * Classifies [bytes] with the exact same [isProbablyUtf8] heuristic and applies the exact
     * same [MAX_STORED_BODY_BYTES] cap via [clampForStorage] as the [ParsedHttpMessage] overload
     * above, so every capture path -- MitmProxyEngine, CaptureEngine, GatewayEngine, PacketPump
     * -- produces the identical three-state contract (text/no-body, binary stored, binary
     * exceeded cap) with no per-transport special casing in the UI.
     */
    fun forStorage(bytes: ByteArray): BinaryBodyStorage {
        if (isProbablyUtf8(bytes)) {
            return BinaryBodyStorage(
                isBinary = false,
                bytes = null,
                truncated = false,
                trueSizeBytes = bytes.size.toLong(),
            )
        }
        val clamped = clampForStorage(bytes)
        return BinaryBodyStorage(
            isBinary = true,
            bytes = clamped.bytes,
            truncated = clamped.truncated,
            trueSizeBytes = bytes.size.toLong(),
        )
    }

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
        /**
         * SHOULD-FIX (gzip'd-image gallery bug): the exact bytes [isBinary]/[text] were computed
         * from -- i.e. [rawBody] after a successful decompression attempt, or [rawBody] itself
         * unchanged when [encodingConsumed] is false. This is deliberately *not* the same array
         * [ParsedHttpMessage.rawBodyBytes] carries: that field must stay wire-exact (still
         * gzip/deflate-encoded) forever, because MitmProxyEngine's serializeRequest/
         * serializeResponse re-emit it verbatim onto the wire alongside the original, untouched
         * Content-Encoding header for any non-`bodyReplaced` message -- feeding decompressed
         * bytes back out under an unchanged "Content-Encoding: gzip" header would corrupt every
         * relayed message with a compressed body. [decodedBytes] exists purely so storage/display
         * code (see [HttpBodyDecoder.forStorage]) can persist bytes that actually match what
         * [isBinary] classified and what a renderer (e.g. the image gallery's `BitmapFactory`)
         * needs, without touching the wire-serialization contract at all.
         */
        val decodedBytes: ByteArray,
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

        return DecodedBody(
            text = text,
            isBinary = binary,
            encoding = encoding,
            encodingConsumed = encodingConsumed,
            decodedBytes = finalBytes,
        )
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
     * longer than the window. Decoding via [java.nio.charset.CharsetDecoder.decode] with
     * `endOfInput = false` handles that for free: an incomplete trailing sequence caused purely
     * by running out of input is reported as `UNDERFLOW`, not a decoding error, so a truncated
     * multi-byte character at the sniff boundary is never misclassified as binary.
     *
     * An empty body is considered text (nothing to prove otherwise).
     */
    fun isProbablyUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val prefixLen = bytes.size.coerceAtMost(UTF8_SNIFF_PREFIX_BYTES)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(prefixLen)
        // endOfInput = false is load-bearing: it tells the decoder the sniffed prefix may be
        // truncated mid-sequence, so a dangling lead byte at the very end yields UNDERFLOW
        // (treated as text below) instead of a malformed-input error.
        val result = decoder.decode(ByteBuffer.wrap(bytes, 0, prefixLen), out, false)
        if (result.isError) return false

        out.flip()
        val decodedText = out.toString()
        var i = 0
        while (i < decodedText.length) {
            val codePoint = decodedText.codePointAt(i)
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) return false
            i += Character.charCount(codePoint)
        }
        return true
    }
}
