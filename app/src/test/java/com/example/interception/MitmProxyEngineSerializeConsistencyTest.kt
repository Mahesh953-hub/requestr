package com.example.interception

import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.HttpParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * §1.4/MUST-FIX 1 critical correctness constraint: [MitmProxyEngine] relays bytes, and its
 * serializeRequest/serializeResponse re-emit headers + body whenever a rule matches a message
 * (REDIRECT/MODIFY_REQUEST/MODIFY_RESPONSE/MOCK_RESPONSE) -- not only when a rule actually
 * rewrote the body.
 *
 * MUST-FIX 1 (severe, found in review): the previous implementation always wrote
 * `body.toByteArray(UTF_8)` -- [HttpBodyDecoder]'s decoded text *rendering* of the body, which
 * is the empty [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] for any binary, undecodable
 * (br/zstd/compress), or failed-decompression body. That meant every image/protobuf/Brotli
 * request or response on a connection that matched *any* rule -- even one that only added a
 * header -- was forwarded with a zero-byte body while a stale nonzero Content-Length from the
 * original headers survived untouched, hanging the receiving end.
 *
 * These tests assert byte-for-byte body survival end-to-end, not merely that a flag (like
 * `isBodyBinary`) was set correctly -- a flag-only assertion would pass in both the correct
 * and the catastrophic (zero-byte-body) version of this code, which is exactly why the
 * original bug slipped through review.
 *
 * `serializeRequest`/`serializeResponse` are `internal` (see their KDoc in
 * MitmProxyEngine.kt) specifically so this is directly testable on the plain JVM.
 */
class MitmProxyEngineSerializeConsistencyTest {

    private val engine = MitmProxyEngine()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gzip(text: String): ByteArray = gzip(text.toByteArray(Charsets.UTF_8))

    private fun headerBlockAndBody(wire: ByteArray): Pair<String, ByteArray> {
        val text = String(wire, Charsets.ISO_8859_1)
        val sepIndex = text.indexOf("\r\n\r\n")
        val headerBlock = text.substring(0, sepIndex)
        val body = wire.copyOfRange(sepIndex + 4, wire.size)
        return headerBlock to body
    }

    // ── header-only edits must be byte-transparent for the body ─────────

    @Test
    fun `header-only response edit leaves body bytes completely untouched`() {
        // Simulates a MODIFY_RESPONSE rule that only adds a header, never touching bodyReplace.
        val jsonBody = """{"status":"ok","values":[1,2,3,4,5]}"""
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${jsonBody.length}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + jsonBody.toByteArray(Charsets.UTF_8)

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        assertFalse(parsed.bodyReplaced)

        parsed.headers = parsed.headers.toMutableMap().apply { put("x-added", "true") }
        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(jsonBody.toByteArray(Charsets.UTF_8), body)
        assertTrue(headerBlock.contains("x-added: true", ignoreCase = true))
    }

    @Test
    fun `binary PNG response body survives header-only reserialization byte-for-byte`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte(), 0x10, 0x20,
        )
        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + png

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        assertTrue("sanity: parser must have flagged this as binary", parsed.isBodyBinary)
        assertFalse(parsed.bodyReplaced)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals("the original PNG bytes must survive, not the empty placeholder", png, body)
        assertTrue(headerBlock.contains("Content-Length: ${png.size}", ignoreCase = true))
    }

    @Test
    fun `undecodable br response body survives reserialization byte-for-byte and keeps its header`() {
        val fakeBrotli = byteArrayOf(0x1b, 0x02, 0x00, 0x04, 0x73, 0x9a.toByte(), 0xff.toByte(), 0x01, 0x02)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: ${fakeBrotli.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + fakeBrotli

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        assertFalse(parsed.bodyReplaced)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(fakeBrotli, body)
        assertTrue(headerBlock.contains("Content-Encoding: br", ignoreCase = true))
        assertTrue(headerBlock.contains("Content-Length: ${fakeBrotli.size}", ignoreCase = true))
    }

    @Test
    fun `zstd response body survives reserialization byte-for-byte`() {
        val fakeZstd = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0x01, 0x02, 0x03)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: zstd\r\nContent-Length: ${fakeZstd.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + fakeZstd

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(fakeZstd, body)
        assertTrue(headerBlock.contains("Content-Encoding: zstd", ignoreCase = true))
    }

    @Test
    fun `corrupt gzip response body survives reserialization byte-for-byte`() {
        val corrupt = gzip("this would have been valid json but got chopped").copyOf(6)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${corrupt.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + corrupt

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(corrupt, body)
        assertTrue(headerBlock.contains("Content-Encoding: gzip", ignoreCase = true))
    }

    @Test
    fun `body that hit the decompression cap survives reserialization byte-for-byte`() {
        // Highly-compressible >10 MB payload -- decompresses past HttpBodyDecoder's cap, so
        // decode() falls back to the still-compressed raw bytes.
        val huge = ByteArray(11 * 1024 * 1024) { 'a'.code.toByte() }
        val compressed = gzip(huge)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        // Sanity precondition (not the assertion under test): decompression must have
        // genuinely failed here (hit HttpBodyDecoder's cap), or this test would not exercise
        // the "falls back to the still-compressed raw bytes" path it's named for. Re-derived
        // directly from HttpBodyDecoder.decode rather than off ParsedHttpMessage, since the
        // parser no longer carries this per-message (see MASTER-FEATURE-ADDITION-PLAN §-tracked
        // removal of the write-only bodyContentEncodingConsumed field).
        val decoded = HttpBodyDecoder.decode(compressed, "gzip")
        assertFalse("sanity: decompression must have failed (hit the cap)", decoded.encodingConsumed)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(compressed, body)
        assertTrue(headerBlock.contains("Content-Encoding: gzip", ignoreCase = true))
    }

    @Test
    fun `gzip response carrying binary content survives reserialization byte-for-byte still gzip-compressed`() {
        val binaryPayload = byteArrayOf(0x00, 0x01, 0x02, 0xFE.toByte(), 0xFF.toByte(), 0x00, 0x10, 0x20)
        val compressed = gzip(binaryPayload)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        // Decompression succeeded, but the decompressed content is itself binary -- body
        // becomes the empty placeholder, yet the wire bytes forwarded must still be the
        // original gzip-compressed bytes, and the header must still say so.
        assertTrue(parsed.isBodyBinary)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(compressed, body)
        assertTrue(headerBlock.contains("Content-Encoding: gzip", ignoreCase = true))
    }

    @Test
    fun `identity body reserialization is unaffected`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello"
        val parsed = HttpParser.tryParseResponse(response.toByteArray())
        checkNotNull(parsed)

        val reserialized = engine.serializeResponse(parsed)
        val text = String(reserialized, Charsets.ISO_8859_1)
        assertTrue(text.endsWith("hello"))
        assertFalse(text.substringBefore("\r\n\r\n").contains("Content-Encoding", ignoreCase = true))
    }

    @Test
    fun `decompressed-then-reserialized response with no rule body edit still forwards original compressed bytes`() {
        // A rule that only inspects/matches (or edits headers) but never calls bodyReplace
        // must be byte-transparent for the body -- even though HttpParser successfully
        // decompressed it into readable text, that text is not what must go out on the wire.
        val jsonBody = """{"status":"ok","values":[1,2,3,4,5]}"""
        val compressed = gzip(jsonBody)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        assertEquals(jsonBody, parsed.body) // sanity: the parser decompressed it for display
        assertFalse(parsed.bodyReplaced)

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertArrayEquals(compressed, body)
        assertTrue(headerBlock.contains("Content-Encoding: gzip", ignoreCase = true))
        assertTrue(headerBlock.contains("Content-Length: ${compressed.size}", ignoreCase = true))
    }

    // ── bodyReplaced: true (a rule's bodyReplace actually fired) ─────────

    @Test
    fun `bodyReplace on a response drops Content-Encoding and writes the replacement plaintext`() {
        val fakeBrotli = byteArrayOf(0x1b, 0x02, 0x00, 0x04, 0x73, 0x9a.toByte(), 0xff.toByte())
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: ${fakeBrotli.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + fakeBrotli

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        // Exactly what RuleEnforcer.applyResponseModification does for a "bodyReplace" key --
        // set directly here (rather than going through RuleEnforcer/org.json.JSONObject,
        // which needs Robolectric) to keep this test package's plain-JVM-only property.
        parsed.body = """{"mocked":true}"""
        parsed.bodyReplaced = true

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertEquals("""{"mocked":true}""", String(body, Charsets.UTF_8))
        assertFalse(
            "replacement is plaintext -- inherited Content-Encoding would be a lie",
            headerBlock.contains("Content-Encoding", ignoreCase = true),
        )
        assertTrue(headerBlock.contains("Content-Length: 15", ignoreCase = true))
    }

    @Test
    fun `bodyReplace on a request drops Content-Encoding and writes the replacement plaintext`() {
        val jsonBody = """{"query":"abc"}"""
        val compressed = gzip(jsonBody)
        val head = "POST /search HTTP/1.1\r\nHost: api.test\r\nContent-Encoding: gzip\r\n" +
            "Content-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseRequest(wire, "api.test", 443, isHttps = true)
        checkNotNull(parsed)
        parsed.body = "replaced"
        parsed.bodyReplaced = true

        val reserialized = engine.serializeRequest(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertEquals("replaced", String(body, Charsets.UTF_8))
        assertFalse(headerBlock.contains("Content-Encoding", ignoreCase = true))
        assertTrue(headerBlock.contains("Content-Length: 8", ignoreCase = true))
    }

    @Test
    fun `empty-string bodyReplace still zeroes Content-Length correctly`() {
        val jsonBody = """{"a":1}"""
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${jsonBody.length}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + jsonBody.toByteArray(Charsets.UTF_8)

        val parsed = HttpParser.tryParseResponse(wire)
        checkNotNull(parsed)
        parsed.body = ""
        parsed.bodyReplaced = true

        val reserialized = engine.serializeResponse(parsed)
        val (headerBlock, body) = headerBlockAndBody(reserialized)

        assertEquals(0, body.size)
        assertTrue(headerBlock.contains("Content-Length: 0", ignoreCase = true))
    }
}
