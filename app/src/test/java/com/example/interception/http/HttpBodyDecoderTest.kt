package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [HttpBodyDecoder]: §1.4 (Content-Encoding-aware decompression) and §2.7
 * (binary-body detection) of docs/plans/MASTER-FEATURE-ADDITION-PLAN.md.
 */
class HttpBodyDecoderTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** zlib-wrapped DEFLATE (RFC 1950) -- what "Content-Encoding: deflate" is supposed to mean. */
    private fun zlibDeflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Raw DEFLATE (RFC 1951, no zlib header) -- what many real servers actually send. */
    private fun rawDeflate(text: String): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    // ── decode(): Content-Encoding handling ──────────────────────────────

    @Test
    fun `gzip body round-trips to original text`() {
        val original = """{"id":1,"name":"hello world","items":[1,2,3]}"""
        val result = HttpBodyDecoder.decode(gzip(original), "gzip")

        assertEquals(original, result.text)
        assertFalse(result.isBinary)
        assertEquals(HttpBodyDecoder.Encoding.GZIP, result.encoding)
        assertTrue(result.encodingConsumed)
    }

    @Test
    fun `x-gzip is treated the same as gzip`() {
        val original = "plain text body"
        val result = HttpBodyDecoder.decode(gzip(original), "x-gzip")
        assertEquals(original, result.text)
        assertTrue(result.encodingConsumed)
    }

    @Test
    fun `zlib-wrapped deflate body round-trips to original text`() {
        val original = """{"status":"ok"}"""
        val result = HttpBodyDecoder.decode(zlibDeflate(original), "deflate")

        assertEquals(original, result.text)
        assertFalse(result.isBinary)
        assertEquals(HttpBodyDecoder.Encoding.DEFLATE, result.encoding)
        assertTrue(result.encodingConsumed)
    }

    @Test
    fun `raw deflate without zlib header falls back correctly`() {
        val original = "a large number of real servers send raw deflate, not zlib-wrapped"
        val result = HttpBodyDecoder.decode(rawDeflate(original), "deflate")

        assertEquals(original, result.text)
        assertFalse(result.isBinary)
        assertTrue(result.encodingConsumed)
    }

    @Test
    fun `brotli is marked undecodable rather than force-decoded into garbage`() {
        // Real brotli bytes, but the JDK/Android stdlib has no decoder -- verify we do not
        // even attempt a decode that would emit garbage.
        val fakeBrotliBytes = byteArrayOf(0x1b, 0x02, 0x00, 0x04, 0x73, 0x9a.toByte(), 0xff.toByte())
        val result = HttpBodyDecoder.decode(fakeBrotliBytes, "br")

        assertEquals(HttpBodyDecoder.Encoding.UNDECODABLE, result.encoding)
        assertFalse("declared-but-undecodable encodings must not be reported as consumed", result.encodingConsumed)
    }

    @Test
    fun `zstd is marked undecodable`() {
        val result = HttpBodyDecoder.decode(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()), "zstd")
        assertEquals(HttpBodyDecoder.Encoding.UNDECODABLE, result.encoding)
        assertFalse(result.encodingConsumed)
    }

    @Test
    fun `identity (no Content-Encoding) is left as plain UTF-8 text`() {
        val original = "hello, no compression here"
        val result = HttpBodyDecoder.decode(original.toByteArray(Charsets.UTF_8), null)

        assertEquals(original, result.text)
        assertEquals(HttpBodyDecoder.Encoding.IDENTITY, result.encoding)
        assertFalse(result.encodingConsumed)
        assertFalse(result.isBinary)
    }

    @Test
    fun `blank Content-Encoding header behaves like identity`() {
        val result = HttpBodyDecoder.decode("text".toByteArray(), "")
        assertEquals(HttpBodyDecoder.Encoding.IDENTITY, result.encoding)
    }

    @Test
    fun `truncated corrupt gzip body falls back safely without throwing`() {
        val corrupt = gzip("this will be truncated").copyOf(5) // chop off almost everything
        val result = HttpBodyDecoder.decode(corrupt, "gzip")

        // Must not throw. Decompression failed, so the header is still accurate -- must not
        // be reported as consumed, or a caller would strip Content-Encoding over bytes that
        // are still actually gzip-compressed.
        assertFalse(result.encodingConsumed)
        assertEquals(HttpBodyDecoder.Encoding.GZIP, result.encoding)
    }

    @Test
    fun `empty gzip body does not throw`() {
        val result = HttpBodyDecoder.decode(ByteArray(0), "gzip")
        assertFalse(result.encodingConsumed)
    }

    // ── isProbablyUtf8() / binary detection ───────────────────────────────

    @Test
    fun `PNG magic bytes are detected as binary`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(HttpBodyDecoder.isProbablyUtf8(png))
    }

    @Test
    fun `random binary bytes are detected as binary`() {
        val random = byteArrayOf(0x00, 0xFF.toByte(), 0x13, 0x37, 0x92.toByte(), 0x01, 0xDE.toByte(), 0xAD.toByte())
        assertFalse(HttpBodyDecoder.isProbablyUtf8(random))
    }

    @Test
    fun `valid multi-byte UTF-8 text is not flagged binary`() {
        val text = "héllo wörld — emoji: \uD83D\uDE00 done" // includes an accented char, an
        // em dash, and a 4-byte UTF-8 surrogate-pair emoji.
        assertTrue(HttpBodyDecoder.isProbablyUtf8(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `plain ASCII JSON is not flagged binary`() {
        assertTrue(HttpBodyDecoder.isProbablyUtf8("""{"key":"value"}""".toByteArray()))
    }

    @Test
    fun `empty body is considered text`() {
        assertTrue(HttpBodyDecoder.isProbablyUtf8(ByteArray(0)))
    }

    @Test
    fun `multi-byte UTF-8 sequence straddling the sniff window boundary is not flagged binary`() {
        // SHOULD-FIX 1: HttpBodyDecoder only strictly-decodes a bounded prefix
        // (UTF8_SNIFF_PREFIX_BYTES == 8192) of the body for the binary heuristic. If that cut
        // point lands in the middle of a legitimate multi-byte UTF-8 sequence, the old code
        // would throw CharacterCodingException on the truncated tail and misclassify perfectly
        // good text as binary. Build a body where a 4-byte emoji sequence straddles byte
        // offset 8192 exactly: 8190 bytes of ASCII (offsets 0..8189), then the emoji's 4 bytes
        // (offsets 8190..8193) -- two of which (8190, 8191) fall inside the 8192-byte window
        // and two of which (8192, 8193) fall just outside it.
        val asciiPrefix = "a".repeat(8190)
        val emoji = "\uD83D\uDE00" // U+1F600 GRINNING FACE -- 4 UTF-8 bytes: F0 9F 98 80
        val suffix = " and then some more plain text after the emoji"
        val text = asciiPrefix + emoji + suffix
        val bytes = text.toByteArray(Charsets.UTF_8)

        // Sanity: the body must actually be longer than the sniff window and the emoji bytes
        // must genuinely straddle the 8192 cut, or this test would not exercise the bug.
        assertTrue(bytes.size > 8192)
        assertEquals(0xF0, bytes[8190].toInt() and 0xFF) // emoji lead byte at offset 8190

        assertTrue(
            "a multi-byte char straddling the sniff boundary must not cause a false binary flag",
            HttpBodyDecoder.isProbablyUtf8(bytes),
        )
    }

    @Test
    fun `a binary gzip payload decompresses to a binary body flagged accordingly`() {
        val binaryPayload = byteArrayOf(0x00, 0x01, 0x02, 0xFE.toByte(), 0xFF.toByte(), 0x00, 0x10, 0x20)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(binaryPayload) }

        val result = HttpBodyDecoder.decode(out.toByteArray(), "gzip")

        assertTrue(result.encodingConsumed)
        assertTrue(result.isBinary)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, result.text)
    }
}
