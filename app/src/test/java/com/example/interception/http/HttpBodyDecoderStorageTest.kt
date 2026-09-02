package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the binary-body persistence additions: [HttpBodyDecoder.clampForStorage] (the named
 * storage cap) and [HttpBodyDecoder.forStorage] (the [com.example.data.local.CapturedTrafficEntity]
 * three-state field computation shared by every persistence path). Plain JVM, no Robolectric
 * needed -- same as [HttpBodyDecoderTest].
 */
class HttpBodyDecoderStorageTest {

    // ── clampForStorage ──────────────────────────────────────────────

    @Test
    fun `bytes at or under the cap are stored verbatim, not truncated`() {
        val atCap = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES) { it.toByte() }
        val result = HttpBodyDecoder.clampForStorage(atCap)
        assertFalse(result.truncated)
        assertTrue(result.bytes!!.contentEquals(atCap))
    }

    @Test
    fun `bytes one over the cap are not stored at all, and flagged truncated`() {
        val overCap = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1)
        val result = HttpBodyDecoder.clampForStorage(overCap)
        assertTrue(result.truncated)
        assertNull(result.bytes)
    }

    @Test
    fun `small bytes are stored verbatim`() {
        val small = "hello".toByteArray(Charsets.UTF_8)
        val result = HttpBodyDecoder.clampForStorage(small)
        assertFalse(result.truncated)
        assertTrue(result.bytes!!.contentEquals(small))
    }

    // ── forStorage: text bodies (state unaffected by this change) ───

    @Test
    fun `text body reports isBinary false, no bytes column, true size from body text`() {
        val message = ParsedHttpMessage(
            isRequest = true,
            method = null,
            body = "hello world",
            isBodyBinary = false,
            rawBodyBytes = "hello world".toByteArray(Charsets.UTF_8),
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertFalse(storage.truncated)
        assertEquals(11L, storage.trueSizeBytes)
    }

    @Test
    fun `no body at all reports isBinary false, zero true size, no bytes column`() {
        val message = ParsedHttpMessage(
            isRequest = false,
            method = null,
            body = "",
            isBodyBinary = false,
            rawBodyBytes = ByteArray(0),
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertFalse(storage.truncated)
        assertEquals(0L, storage.trueSizeBytes)
    }

    // ── forStorage: binary bodies (the actual fix) ───────────────────

    @Test
    fun `binary body within the cap is stored with real bytes and correct true size`() {
        val jpegLike = ByteArray(4096) { (it % 251).toByte() }
        val message = ParsedHttpMessage(
            isRequest = false,
            method = null,
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = jpegLike,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertFalse(storage.truncated)
        assertTrue(storage.bytes!!.contentEquals(jpegLike))
        assertEquals(4096L, storage.trueSizeBytes)
    }

    @Test
    fun `binary body exceeding the cap is not stored, but true size still reflects the real length`() {
        val huge = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 5000)
        val message = ParsedHttpMessage(
            isRequest = false,
            method = null,
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = huge,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.truncated)
        assertNull(storage.bytes)
        assertEquals(huge.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `binary body stores decodedBodyBytes, not the still-encoded rawBodyBytes, when they differ`() {
        // SHOULD-FIX (gzip'd-image gallery bug): simulates what HttpParser now produces for a
        // gzip-compressed body -- rawBodyBytes stays wire-exact (still "compressed"),
        // decodedBodyBytes carries what isBodyBinary was actually classified from and what a
        // renderer needs. forStorage must persist the latter.
        val stillCompressed = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x01, 0x02, 0x03)
        val realImageBytes = ByteArray(2048) { (it % 200).toByte() }
        val message = ParsedHttpMessage(
            isRequest = false,
            method = null,
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = stillCompressed,
            decodedBodyBytes = realImageBytes,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertFalse(storage.truncated)
        assertTrue(storage.bytes!!.contentEquals(realImageBytes))
        assertFalse(storage.bytes!!.contentEquals(stillCompressed))
        // trueSizeBytes must keep reflecting the true *wire* size (the still-compressed
        // length), never the decompressed length -- requestSizeBytes/responseSizeBytes must
        // not silently change meaning.
        assertEquals(stillCompressed.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `binary body with no decodedBodyBytes set falls back to rawBodyBytes (pre-fix behavior preserved)`() {
        val jpegLike = ByteArray(4096) { (it % 251).toByte() }
        val message = ParsedHttpMessage(
            isRequest = false,
            method = null,
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = jpegLike,
            decodedBodyBytes = null,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.bytes!!.contentEquals(jpegLike))
        assertEquals(4096L, storage.trueSizeBytes)
    }

    @Test
    fun `binary flagged with null rawBodyBytes degrades to zero-length rather than throwing`() {
        val message = ParsedHttpMessage(
            isRequest = true,
            method = null,
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = null,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertFalse(storage.truncated)
        assertEquals(0, storage.bytes!!.size)
        assertEquals(0L, storage.trueSizeBytes)
    }

    // ── forStorage: bodyReplaced -- the row must hold what was SENT, not what arrived ───

    private fun binaryOriginal(n: Int = 512): ByteArray = ByteArray(n) { i -> (0xFF - (i % 256)).toByte() }

    @Test
    fun `text body reports text column equal to body`() {
        val message = ParsedHttpMessage(
            isRequest = true, method = null, body = "{\"a\":1}", isBodyBinary = false,
            rawBodyBytes = "{\"a\":1}".toByteArray(Charsets.UTF_8),
        )
        assertEquals("{\"a\":1}", HttpBodyDecoder.forStorage(message).text)
    }

    @Test
    fun `non-replaced binary body reports the empty placeholder text column`() {
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = binaryOriginal(),
        )
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, HttpBodyDecoder.forStorage(message).text)
    }

    @Test
    fun `replaced binary body persists replacedBodyBytes, not the original decoded bytes`() {
        val original = binaryOriginal()
        val corrupted = original.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte(); it[100] = 0x00 }
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = original, decodedBodyBytes = original,
        )
        message.replacedBodyBytes = corrupted
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.bytes!!.contentEquals(corrupted))
        assertFalse(storage.bytes!!.contentEquals(original))
        assertEquals(corrupted.size.toLong(), storage.trueSizeBytes)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, storage.text)
    }

    @Test
    fun `gzipped image regression guard -- non-replaced binary still persists decoded bytes`() {
        val gzipWire = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00) + ByteArray(64) { 0x55 }
        val decodedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(300) { 0x80.toByte() }
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = gzipWire, decodedBodyBytes = decodedJpeg,
        )
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.bytes!!.contentEquals(decodedJpeg))
        assertEquals(gzipWire.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `text replacement on a binary original is stored as text, isBinary false, original bytes dropped`() {
        // MODIFY_RESPONSE bodyReplace / pm.response.body on a JPEG: body is the new JSON text,
        // replacedBodyBytes is null (text path), but the parse-time isBodyBinary=true is stale.
        val original = binaryOriginal()
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = original, decodedBodyBytes = original,
        )
        message.body = "{\"replaced\":true}"
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertFalse(storage.truncated)
        assertEquals("{\"replaced\":true}", storage.text)
        assertEquals("{\"replaced\":true}".toByteArray(Charsets.UTF_8).size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `text replacement containing control chars (chaos corrupt on text) is still a text row`() {
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = "{\"ok\":true}", isBodyBinary = false,
            rawBodyBytes = "{\"ok\":true}".toByteArray(Charsets.UTF_8),
        )
        message.body = "\u0004]ok\":true}"
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertEquals("\u0004]ok\":true}", storage.text)
    }

    @Test
    fun `replaced bytes that are valid UTF-8 are stored in the text column (AES decrypt of JSON)`() {
        val ciphertext = binaryOriginal(80)
        val plaintext = "{\"secret\":\"decrypted\"}".toByteArray(Charsets.UTF_8)
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = false, rawBodyBytes = ciphertext, decodedBodyBytes = ciphertext,
        )
        message.replacedBodyBytes = plaintext
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertEquals("{\"secret\":\"decrypted\"}", storage.text)
        assertEquals(plaintext.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `replaced non-UTF8 bytes on a text original become a binary row (AES encrypt of JSON)`() {
        val ciphertext = binaryOriginal(80)
        val message = ParsedHttpMessage(
            isRequest = true, method = null, body = "{\"secret\":\"plaintext\"}",
            isBodyBinary = false, rawBodyBytes = "{\"secret\":\"plaintext\"}".toByteArray(Charsets.UTF_8),
        )
        message.replacedBodyBytes = ciphertext
        message.bodyReplaced = true
        // Note: body/isBodyBinary deliberately left stale here -- forStorage must not trust them.
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.bytes!!.contentEquals(ciphertext))
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, storage.text)
        assertEquals(80L, storage.trueSizeBytes)
    }

    @Test
    fun `explicit isBodyBinary with replacedBodyBytes is honoured even when the bytes sniff as UTF-8`() {
        // A byte-exact mock fixture that happens to start with ASCII must stay a byte-exact BLOB.
        val fixture = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(40) { 0x41 }
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = null,
        )
        message.replacedBodyBytes = fixture
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.bytes!!.contentEquals(fixture))
    }

    @Test
    fun `replaced binary body over the cap is flagged truncated with true size preserved`() {
        val big = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1) { 0xFE.toByte() }
        val message = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = ByteArray(10),
        )
        message.replacedBodyBytes = big
        message.bodyReplaced = true
        val storage = HttpBodyDecoder.forStorage(message)
        assertTrue(storage.isBinary)
        assertTrue(storage.truncated)
        assertNull(storage.bytes)
        assertEquals(big.size.toLong(), storage.trueSizeBytes)
    }
}
