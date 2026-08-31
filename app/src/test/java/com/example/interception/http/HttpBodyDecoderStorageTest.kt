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
}
