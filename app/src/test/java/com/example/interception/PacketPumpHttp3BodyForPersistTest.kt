package com.example.interception

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import com.example.interception.http.HttpBodyDecoder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Item 4 / gap B: [PacketPump]'s HTTP/3 (QUIC) body-persistence path used to have its own,
 * independent `decodeBody` that force-decoded every body as UTF-8 and, on a failed round trip,
 * recorded a `"<binary N bytes>"` text marker instead of storing real bytes -- unlike every other
 * capture path (MitmProxyEngine, CaptureEngine, GatewayEngine), which all share
 * [HttpBodyDecoder.forStorage]'s three-state contract (text/no-body, binary stored, binary
 * exceeded cap). [PacketPump.bodyForPersist] closes that gap; this unit-tests it directly rather
 * than only end-to-end (see [com.example.interception.quic.Http3EndToEndFixtureTest] for the real
 * QUIC-driven coverage), since the real [com.example.interception.quic.QuicConnectionDecoder.Http3Exchange]
 * only ever buffers up to 64 KiB per direction -- well under [HttpBodyDecoder.MAX_STORED_BODY_BYTES]
 * (2 MiB) -- so the "binary body genuinely exceeds the storage cap" state can never actually be
 * reached by driving real QUIC packets; it is only reachable by calling this pure function
 * directly with a fabricated oversized [ByteArray], exactly as this test does.
 *
 * Robolectric only because constructing a [PacketPump] instance requires a [RequestrDatabase] and
 * an Android [android.content.Context] -- [bodyForPersist] itself touches neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class PacketPumpHttp3BodyForPersistTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var inputFile: File? = null
    private var outputFile: File? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null

    private fun newPump(): PacketPump {
        val inFile = File.createTempFile("h3-body-in", ".bin")
        val outFile = File.createTempFile("h3-body-out", ".bin")
        inputFile = inFile
        outputFile = outFile
        val ins = FileInputStream(inFile)
        val outs = FileOutputStream(outFile)
        input = ins
        output = outs
        return PacketPump(context = context, input = ins, output = outs, sessionId = 1L, db = RequestrDatabase.get(context))
    }

    @After
    fun tearDown() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { inputFile?.delete() }
        runCatching { outputFile?.delete() }
    }

    @Test
    fun `a UTF-8 text body is stored as real text with no binary flags`() {
        val pump = newPump()
        val result = pump.bodyForPersist("hello world".toByteArray(Charsets.UTF_8), decoderTruncated = false)

        assertEquals("hello world", result.text)
        assertFalse(result.isBinary)
        assertNull(result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun `a binary body under the decoder and storage caps is stored byte-exact`() {
        val pump = newPump()
        // Every byte 0..255 once -- guaranteed invalid UTF-8.
        val binary = ByteArray(256) { it.toByte() }
        val result = pump.bodyForPersist(binary, decoderTruncated = false)

        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, result.text)
        assertTrue(result.isBinary)
        assertArrayEquals(binary, result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun `a binary body the decoder already truncated is never stored as a partial blob`() {
        val pump = newPump()
        // Well under HttpBodyDecoder.MAX_STORED_BODY_BYTES on its own -- decoderTruncated alone
        // must still force "not stored", because these bytes are only a prefix of the real body.
        val binaryPrefix = ByteArray(128) { it.toByte() }
        val result = pump.bodyForPersist(binaryPrefix, decoderTruncated = true)

        assertTrue(result.isBinary)
        assertNull("a decoder-truncated prefix must never be persisted as if it were the whole body", result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun `a binary body exceeding the 2 MiB storage cap is dropped but flagged truncated`() {
        val pump = newPump()
        val oversized = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1) { 0xFF.toByte() }
        val result = pump.bodyForPersist(oversized, decoderTruncated = false)

        assertTrue(result.isBinary)
        assertNull(result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun `an empty body is treated as text, never binary`() {
        val pump = newPump()
        val result = pump.bodyForPersist(ByteArray(0), decoderTruncated = false)

        assertFalse(result.isBinary)
        assertEquals("", result.text)
        assertNull(result.bytes)
        assertFalse(result.truncated)
    }
}
