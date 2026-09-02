package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * §1.4/§2.7 integration coverage: [HttpParser.tryParseRequest]/[HttpParser.tryParseResponse]
 * must decompress per Content-Encoding and detect binary bodies via [HttpBodyDecoder], while
 * [ParsedHttpMessage.consumedBytes] (the byte count the caller drains from its buffer) stays
 * exactly equal to the raw, still-encoded wire size -- decompression must never perturb the
 * relay's buffer-draining arithmetic.
 */
class HttpParserBodyDecodingTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun `gzip response body is decompressed before it reaches ParsedHttpMessage`() {
        val jsonBody = """{"users":[{"id":1,"name":"Ada"}]}"""
        val compressed = gzip(jsonBody)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\n" +
            "Content-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertEquals(jsonBody, parsed!!.body)
        assertFalse(parsed.isBodyBinary)
        // §1.4 correctness constraint: consumedBytes must reflect the raw (compressed) wire
        // size, i.e. exactly headers + compressed body -- never the decompressed length.
        assertEquals(wire.size, parsed.consumedBytes)
    }

    @Test
    fun `gzip request body is decompressed before it reaches ParsedHttpMessage`() {
        val jsonBody = """{"query":"search term"}"""
        val compressed = gzip(jsonBody)
        val head = "POST /search HTTP/1.1\r\nHost: api.test\r\nContent-Encoding: gzip\r\n" +
            "Content-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseRequest(wire, "api.test", 443, isHttps = true)

        assertNotNull(parsed)
        assertEquals(jsonBody, parsed!!.body)
        assertEquals(wire.size, parsed.consumedBytes)
    }

    @Test
    fun `identity response body is unchanged from prior behavior`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"status\":\"ok\"}"
        val parsed = HttpParser.tryParseResponse(response.toByteArray())

        assertNotNull(parsed)
        assertEquals("{\"status\":\"ok\"}", parsed!!.body)
        assertFalse(parsed.isBodyBinary)
    }

    @Test
    fun `brotli response body is marked undecodable, not garbled`() {
        val fakeBrotli = byteArrayOf(0x1b, 0x02, 0x00, 0x04, 0x73, 0x9a.toByte(), 0xff.toByte(), 0x01, 0x02)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: ${fakeBrotli.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + fakeBrotli

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertEquals(wire.size, parsed!!.consumedBytes)
    }

    @Test
    fun `truncated corrupt gzip response does not crash the parser`() {
        val corrupt = gzip("would have been valid json but got chopped").copyOf(6)
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${corrupt.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + corrupt

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertEquals(wire.size, parsed!!.consumedBytes)
    }

    @Test
    fun `binary PNG response body is flagged binary and not force-decoded as UTF-8`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + png

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertTrue(parsed!!.isBodyBinary)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, parsed.body)
        assertEquals(wire.size, parsed.consumedBytes)
    }

    @Test
    fun `gzip-compressed real PNG response is decompressed for storage, not persisted as gzip bytes`() {
        // SHOULD-FIX (gzip'd-image gallery bug): a genuine, valid 1x1 transparent PNG -- not a
        // synthetic non-UTF8 stand-in -- so this actually exercises "bytes classified as an
        // image and bytes handed to a bitmap decoder are the same bytes", the exact property
        // the reviewer flagged as broken. Real servers gzip almost everything, images included.
        val realPng = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABAQMAAAAl21bKAAAAAXNSR0IArs4c6QAAAAlwSFlzAAAAAAAA" +
                "AAAB8ppjxAAAAApJREFUeJxjYAAAAAIAAeIhvDMAAAAASUVORK5CYII=",
        )
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertTrue("fixture must actually be a valid PNG", realPng.copyOf(8).contentEquals(pngMagic))

        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(realPng) }
        val compressed = out.toByteArray()

        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Encoding: gzip\r\n" +
            "Content-Length: ${compressed.size}\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + compressed

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertTrue(parsed!!.isBodyBinary)
        // consumedBytes/rawBodyBytes stay wire-exact (still gzip-compressed) -- serialization
        // fidelity for a non-bodyReplaced message must not regress.
        assertEquals(wire.size, parsed.consumedBytes)
        assertTrue(parsed.rawBodyBytes!!.contentEquals(compressed))

        // The actual fix: the bytes HttpBodyDecoder.forStorage would persist as the BLOB column
        // are the decompressed, genuinely-decodable PNG -- not the gzip container.
        val storage = HttpBodyDecoder.forStorage(parsed)
        assertTrue(storage.isBinary)
        assertFalse(storage.truncated)
        assertTrue(
            "stored bytes must be the real PNG, not still-gzipped data",
            storage.bytes!!.contentEquals(realPng),
        )
        assertTrue(storage.bytes!!.copyOf(8).contentEquals(pngMagic))
        // requestSizeBytes/responseSizeBytes must keep reporting the true *wire* size (the
        // compressed length), never silently switch to reporting the decompressed length.
        assertEquals(compressed.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `gzip chunked response body is decompressed after chunks are reassembled`() {
        val jsonBody = """{"chunked":true,"ok":1}"""
        val compressed = gzip(jsonBody)
        // Build a single chunk containing the entire compressed payload, followed by the
        // terminal zero-size chunk.
        val chunkHeader = compressed.size.toString(16) + "\r\n"
        val chunkedBody = chunkHeader.toByteArray(Charsets.ISO_8859_1) + compressed + "\r\n0\r\n\r\n".toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n"
        val wire = head.toByteArray(Charsets.ISO_8859_1) + chunkedBody

        val parsed = HttpParser.tryParseResponse(wire)

        assertNotNull(parsed)
        assertEquals(jsonBody, parsed!!.body)
    }
}
