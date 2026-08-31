package com.example.interception

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockFixtureLoaderTest {

    @Test
    fun `literal body passes through unchanged`() {
        assertEquals("hello world", MockFixtureLoader.resolveBodyReplace("hello world"))
    }

    @Test
    fun `null passes through unchanged`() {
        assertNull(MockFixtureLoader.resolveBodyReplace(null))
    }

    @Test
    fun `file reference is resolved to file contents`() {
        val file = File.createTempFile("fixture", ".json")
        file.writeText("""{"mocked":true}""")
        try {
            val result = MockFixtureLoader.resolveBodyReplace("file://${file.absolutePath}")
            assertEquals("""{"mocked":true}""", result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing file falls back to raw value by default`() {
        val result = MockFixtureLoader.resolveBodyReplace("file:///no/such/file/here.json")
        assertEquals("file:///no/such/file/here.json", result)
    }

    @Test
    fun `missing file falls back to custom fallback when provided`() {
        val result = MockFixtureLoader.resolveBodyReplace(
            "file:///no/such/file/here.json",
            fallbackOnError = "{}",
        )
        assertEquals("{}", result)
    }

    @Test
    fun `oversized file falls back rather than reading it fully`() {
        val file = File.createTempFile("fixture", ".bin")
        file.writeText("x".repeat(1024))
        try {
            val result = MockFixtureLoader.resolveBodyReplace(
                "file://${file.absolutePath}",
                maxBytes = 10,
                fallbackOnError = "too-big",
            )
            assertEquals("too-big", result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `isFileFixtureReference detects the scheme`() {
        assertTrue(MockFixtureLoader.isFileFixtureReference("file:///tmp/x.json"))
        assertFalse(MockFixtureLoader.isFileFixtureReference("just a literal body"))
        assertFalse(MockFixtureLoader.isFileFixtureReference(null))
    }

    // ── resolve(): byte-exact binary fixtures (§6 follow-up) ─────────

    @Test
    fun `resolve returns Text for a literal non-file value`() {
        val resolution = MockFixtureLoader.resolve("hello world")
        assertTrue(resolution is MockFixtureLoader.Resolution.Text)
        assertEquals("hello world", (resolution as MockFixtureLoader.Resolution.Text).text)
    }

    @Test
    fun `resolve returns Text for a UTF-8 text fixture file, identical to resolveBodyReplace`() {
        val file = File.createTempFile("fixture", ".json")
        file.writeText("""{"mocked":true}""")
        try {
            val resolution = MockFixtureLoader.resolve("file://${file.absolutePath}")
            assertTrue(resolution is MockFixtureLoader.Resolution.Text)
            assertEquals("""{"mocked":true}""", (resolution as MockFixtureLoader.Resolution.Text).text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `resolve returns Binary with byte-exact contents for a non-UTF8 fixture file`() {
        // A minimal PNG-ish byte sequence: magic bytes + a stray invalid UTF-8 continuation byte,
        // guaranteed to fail HttpBodyDecoder.isProbablyUtf8's strict decode.
        val pngLike = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte(), 0xD8.toByte())
        val file = File.createTempFile("fixture", ".png")
        file.writeBytes(pngLike)
        try {
            val resolution = MockFixtureLoader.resolve("file://${file.absolutePath}")
            assertTrue(resolution is MockFixtureLoader.Resolution.Binary)
            assertTrue(pngLike.contentEquals((resolution as MockFixtureLoader.Resolution.Binary).bytes))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `resolve does not corrupt a binary fixture via UTF-8 round trip`() {
        // Every byte 0..255 once -- guaranteed to contain invalid UTF-8 sequences, and if it
        // were ever decoded/re-encoded as UTF-8 text, the byte count and/or values would change.
        val allBytes = ByteArray(256) { it.toByte() }
        val file = File.createTempFile("fixture", ".bin")
        file.writeBytes(allBytes)
        try {
            val resolution = MockFixtureLoader.resolve("file://${file.absolutePath}")
            assertTrue(resolution is MockFixtureLoader.Resolution.Binary)
            assertEquals(256, (resolution as MockFixtureLoader.Resolution.Binary).bytes.size)
            assertTrue(allBytes.contentEquals(resolution.bytes))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `resolve returns a clear Error for a missing file, not an empty or misleading value`() {
        val resolution = MockFixtureLoader.resolve("file:///no/such/file/here.json")
        assertTrue(resolution is MockFixtureLoader.Resolution.Error)
        val message = (resolution as MockFixtureLoader.Resolution.Error).message
        assertTrue("error message should mention the path", message.contains("/no/such/file/here.json"))
        assertTrue("error message should say what went wrong", message.contains("not found"))
    }

    @Test
    fun `resolve returns a clear Error for an oversized file rather than reading it fully`() {
        val file = File.createTempFile("fixture", ".bin")
        file.writeText("x".repeat(1024))
        try {
            val resolution = MockFixtureLoader.resolve("file://${file.absolutePath}", maxBytes = 10)
            assertTrue(resolution is MockFixtureLoader.Resolution.Error)
            assertTrue((resolution as MockFixtureLoader.Resolution.Error).message.contains("too large"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `resolveBodyReplace degrades a binary fixture to the binary placeholder rather than corrupting it`() {
        val allBytes = ByteArray(256) { it.toByte() }
        val file = File.createTempFile("fixture", ".bin")
        file.writeBytes(allBytes)
        try {
            val result = MockFixtureLoader.resolveBodyReplace("file://${file.absolutePath}")
            assertEquals(com.example.interception.http.HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, result)
        } finally {
            file.delete()
        }
    }
}
