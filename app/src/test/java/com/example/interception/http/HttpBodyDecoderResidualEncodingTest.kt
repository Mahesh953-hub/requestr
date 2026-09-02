package com.example.interception.http

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [HttpBodyDecoder.residualContentEncoding]: the signal BODY_ENCRYPT_AES/BODY_DECRYPT_AES
 * use to decide whether [ParsedHttpMessage.decodedBodyBytes] is identity content (safe to
 * transform and re-emit without a Content-Encoding header) or still carries an encoding this
 * device could not undo (must refuse). Plain JVM, no Robolectric.
 */
class HttpBodyDecoderResidualEncodingTest {

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun response(wireBody: ByteArray, contentEncoding: String?): ParsedHttpMessage {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n")
            if (contentEncoding != null) append("Content-Encoding: $contentEncoding\r\n")
            append("Content-Length: ${wireBody.size}\r\n\r\n")
        }
        return HttpParser.tryParseResponse(head.toByteArray(Charsets.ISO_8859_1) + wireBody)
            ?: throw AssertionError("fixture must parse")
    }

    @Test
    fun `no Content-Encoding header is identity`() {
        assertNull(HttpBodyDecoder.residualContentEncoding(response("{}".toByteArray(), null)))
    }

    @Test
    fun `explicit identity token is identity`() {
        assertNull(HttpBodyDecoder.residualContentEncoding(response("{}".toByteArray(), "identity")))
    }

    @Test
    fun `gzip that the parser inflated leaves no residual encoding`() {
        val message = response(gzip("""{"a":1}""".toByteArray()), "gzip")
        assertEquals("""{"a":1}""", message.body)
        assertNull(HttpBodyDecoder.residualContentEncoding(message))
    }

    @Test
    fun `brotli cannot be decoded here so the declared token is residual`() {
        val message = response(byteArrayOf(0x1b, 0x03, 0x00, 0x11, 0x22), "br")
        assertEquals("br", HttpBodyDecoder.residualContentEncoding(message))
    }

    @Test
    fun `declared gzip that fails to inflate is still residual gzip`() {
        val message = response("definitely not gzip".toByteArray(), "gzip")
        assertEquals("gzip", HttpBodyDecoder.residualContentEncoding(message))
    }

    @Test
    fun `unknown token is residual`() {
        assertEquals("sdch", HttpBodyDecoder.residualContentEncoding(response("x".toByteArray(), "sdch")))
    }

    @Test
    fun `header lookup is case-insensitive and normalises the token`() {
        val message = ParsedHttpMessage(
            isRequest = false, method = null, headers = mapOf("Content-Encoding" to " BR "),
            body = "", isBodyBinary = true, rawBodyBytes = byteArrayOf(1, 2, 3), decodedBodyBytes = byteArrayOf(1, 2, 3),
        )
        assertEquals("br", HttpBodyDecoder.residualContentEncoding(message))
    }

    @Test
    fun `synthesized message with no wire bytes but a declared encoding is residual`() {
        val message = ParsedHttpMessage(
            isRequest = true, method = null, headers = mapOf("content-encoding" to "gzip"), body = "{}",
        )
        assertEquals("gzip", HttpBodyDecoder.residualContentEncoding(message))
    }
}
