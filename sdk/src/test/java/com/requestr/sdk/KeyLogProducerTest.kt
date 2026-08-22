package com.requestr.sdk

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The plan spec for this test ("write sample NSS keylog lines to a temp file, call
 * `importNssKeyLog`, assert `TlsKeyLogRegistry.entryCount` increases") assumes `TlsKeyLogRegistry`
 * is on this module's compile/test classpath. It deliberately isn't -- see [KeyLogProducer]'s
 * kdoc: `:sdk` is built to be embeddable standalone in third-party apps that have never heard of
 * requestr's `:app` module, so [KeyLogRegistryBridge] resolves that class via `Class.forName` at
 * runtime instead of a compile-time dependency. That means this test module is *exactly* the
 * "host app with no knowledge of requestr's internals" case the bridge is designed to degrade
 * gracefully for -- so instead of asserting a registry entry count that can't exist here, this
 * class tests the two things that actually are this module's responsibility and are testable
 * without that dependency:
 *
 * 1. [KeyLogRegistryBridge] degrades to a harmless no-op (never throws) when
 *    `com.example.interception.tls.TlsKeyLogRegistry` isn't on the classpath -- which is
 *    unconditionally true in this test module, so [KeyLogRegistryBridge.isAvailable] asserting
 *    `false` here is not a placeholder, it's the real, honest behavior this bridge must have for
 *    the overwhelmingly common embedding case.
 * 2. [KeyLogProducer.readNewLines] -- the file-tailing/diffing logic
 *    [KeyLogProducer.startCronetKeyLogMonitor]'s poll loop is built on -- correctly detects
 *    appended lines, ignores unchanged files, and resets on truncation/rotation. This is the part
 *    of task 8a that's actually worth unit-testing; the coroutine polling loop around it is a thin
 *    "call this every 2 seconds" wrapper with nothing else to verify.
 */
@RunWith(RobolectricTestRunner::class)
class KeyLogProducerTest {

    // --- KeyLogRegistryBridge: graceful degradation when TlsKeyLogRegistry is absent ---------

    @Test
    fun `KeyLogRegistryBridge reports unavailable when TlsKeyLogRegistry is not on the classpath`() {
        assertFalse(
            "TlsKeyLogRegistry is intentionally absent from :sdk's test classpath (see class kdoc)" +
                " -- isAvailable() must honestly report that, not silently pretend success",
            KeyLogRegistryBridge.isAvailable(),
        )
    }

    @Test
    fun `KeyLogRegistryBridge record() is a no-op, never throws, when TlsKeyLogRegistry is unavailable`() {
        // Must not throw -- this is called from real request/response handling paths on a real
        // host app's traffic; a third-party app embedding this SDK must never see an exception
        // from a bonus keylog-production feature it never asked for.
        KeyLogRegistryBridge.record("CLIENT_HANDSHAKE_TRAFFIC_SECRET", ByteArray(32), ByteArray(48))
    }

    @Test
    fun `KeyLogRegistryBridge importNssKeyLog() returns 0 and never throws when TlsKeyLogRegistry is unavailable`() {
        val accepted = KeyLogRegistryBridge.importNssKeyLog(sequenceOf("SOME NSS LINE HERE"))
        assertEquals(0, accepted)
    }

    // --- KeyLogProducer.readNewLines: file-tailing/diffing logic -----------------------------

    @Test
    fun `readNewLines returns newly appended lines and the new file size`() {
        val file = File.createTempFile("keylog", ".txt")
        try {
            file.writeText("CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb\n")
            val (firstLines, sizeAfterFirstWrite) = KeyLogProducer.readNewLines(file, 0L)
            assertEquals(listOf("CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb"), firstLines)
            assertEquals(file.length(), sizeAfterFirstWrite)

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write("SERVER_HANDSHAKE_TRAFFIC_SECRET cccc dddd\n".toByteArray())
            }
            val (secondLines, sizeAfterSecondWrite) = KeyLogProducer.readNewLines(file, sizeAfterFirstWrite)
            assertEquals(listOf("SERVER_HANDSHAKE_TRAFFIC_SECRET cccc dddd"), secondLines)
            assertEquals(file.length(), sizeAfterSecondWrite)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readNewLines returns no lines when the file hasn't grown`() {
        val file = File.createTempFile("keylog", ".txt")
        try {
            file.writeText("CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb\n")
            val lastSize = file.length()

            val (lines, newSize) = KeyLogProducer.readNewLines(file, lastSize)

            assertTrue("expected no new lines when nothing was appended", lines.isEmpty())
            assertEquals(lastSize, newSize)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readNewLines filters out blank lines`() {
        val file = File.createTempFile("keylog", ".txt")
        try {
            file.writeText("CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb\n\n\nSERVER_HANDSHAKE_TRAFFIC_SECRET cccc dddd\n")

            val (lines, _) = KeyLogProducer.readNewLines(file, 0L)

            assertEquals(
                listOf(
                    "CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb",
                    "SERVER_HANDSHAKE_TRAFFIC_SECRET cccc dddd",
                ),
                lines,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readNewLines resets to offset 0 when the file was truncated or rotated`() {
        val file = File.createTempFile("keylog", ".txt")
        try {
            file.writeText("CLIENT_HANDSHAKE_TRAFFIC_SECRET aaaa bbbb\nSERVER_HANDSHAKE_TRAFFIC_SECRET cccc dddd\n")
            val lastSize = file.length()

            // Simulate the host app restarting Cronet, which recreates the keylog file from
            // scratch (much smaller than what we last saw).
            file.writeText("CLIENT_HANDSHAKE_TRAFFIC_SECRET newnew\n")
            assertTrue("test setup: new file must be smaller than lastSize for this to be a real truncation", file.length() < lastSize)

            val (linesOnTruncationDetected, resetSize) = KeyLogProducer.readNewLines(file, lastSize)
            assertTrue("expected no lines on the truncation-detection call itself", linesOnTruncationDetected.isEmpty())
            assertEquals(0L, resetSize)

            // The next call (using the reset offset) should now read the new file's contents.
            val (linesAfterReset, _) = KeyLogProducer.readNewLines(file, resetSize)
            assertEquals(listOf("CLIENT_HANDSHAKE_TRAFFIC_SECRET newnew"), linesAfterReset)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readNewLines handles a nonexistent file without throwing`() {
        val file = File.createTempFile("keylog-missing", ".txt")
        file.delete() // exists as a path, but no longer as a file

        val (lines, newSize) = KeyLogProducer.readNewLines(file, 0L)

        assertTrue(lines.isEmpty())
        assertEquals(0L, newSize)
    }
}
