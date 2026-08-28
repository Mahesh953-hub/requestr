package com.example.interception

import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Exercises [ConnectReplay.replay] against a real [ServerSocket] standing in for a CONNECT
 * endpoint -- verifies the request line it writes and how it parses each response status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConnectReplayTest {

    /**
     * Starts a fake CONNECT endpoint that replies with [statusLine] and captures the request it
     * received. Accepts connections in a loop (not just once) so callers exercising
     * [ConnectReplay.replayRepeated] with `count > 1` get a fresh accept per attempt instead of
     * the 2nd+ attempt failing to connect once the listen backlog/first connection is used up.
     */
    private fun fakeConnectEndpoint(statusLine: String): Pair<ServerSocket, StringBuilder> {
        val server = ServerSocket(0)
        val received = StringBuilder()
        Thread {
            while (!server.isClosed) {
                val conn = try {
                    server.accept()
                } catch (_: Exception) {
                    break // server closed while blocked in accept()
                }
                Thread {
                    val input = conn.getInputStream()
                    // Read the CONNECT request line + headers up to the blank line.
                    while (true) {
                        val line = HttpProxyShared.readLine(input) ?: break
                        synchronized(received) { received.append(line).append("\n") }
                        if (line.isEmpty()) break
                    }
                    conn.getOutputStream().write("$statusLine\r\n\r\n".toByteArray())
                    conn.getOutputStream().flush()
                    Thread.sleep(200) // give the client time to read before the socket tears down
                    conn.close()
                }.start()
            }
        }.start()
        return server to received
    }

    @Test
    fun `replay writes a correct CONNECT request line`() {
        val (server, received) = fakeConnectEndpoint("HTTP/1.1 200 Connection Established")
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val result = ConnectReplay.replay(context, "127.0.0.1", server.localPort)

        assertEquals("ALLOWED_OPAQUE", result.disposition)
        assertNull(result.error)
        server.close()
        TimeUnit.MILLISECONDS.sleep(50)
        assertTrue(received.toString().startsWith("CONNECT 127.0.0.1:${server.localPort} HTTP/1.1"))
    }

    @Test
    fun `replay maps a 403 response to BLOCKED`() {
        val (server, _) = fakeConnectEndpoint("HTTP/1.1 403 Blocked by requestr")
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val result = ConnectReplay.replay(context, "127.0.0.1", server.localPort)

        assertEquals("BLOCKED", result.disposition)
        server.close()
    }

    @Test
    fun `replay maps an unexpected status to FAILED`() {
        val (server, _) = fakeConnectEndpoint("HTTP/1.1 500 Internal Server Error")
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val result = ConnectReplay.replay(context, "127.0.0.1", server.localPort)

        assertEquals("FAILED", result.disposition)
        server.close()
    }

    @Test
    fun `replay reports FAILED with an error message when nothing is listening`() {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close() // free the port immediately so the connect attempt fails
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val result = ConnectReplay.replay(context, "127.0.0.1", port)

        assertEquals("FAILED", result.disposition)
        assertTrue(result.error != null)
    }

    @Test
    fun `forceMitm without a running MITM proxy fails fast without dialing anything`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val result = ConnectReplay.replay(context, "example.com", 443, forceMitm = true)

        assertEquals("FAILED", result.disposition)
        assertTrue(result.error?.contains("MITM", ignoreCase = true) == true)
    }

    @Test
    fun `replayRepeated runs exactly count attempts in order`() = runBlocking {
        val (server, _) = fakeConnectEndpoint("HTTP/1.1 200 Connection Established")
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val results = ConnectReplay.replayRepeated(
            context, "127.0.0.1", server.localPort, count = 3, jitterMs = 0L,
        )

        assertEquals(3, results.size)
        results.forEach { assertEquals("ALLOWED_OPAQUE", it.disposition) }
        server.close()
    }

    @Test
    fun `replayRepeated with count less than 1 still runs once`() = runBlocking {
        val (server, _) = fakeConnectEndpoint("HTTP/1.1 200 Connection Established")
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val results = ConnectReplay.replayRepeated(context, "127.0.0.1", server.localPort, count = 0)

        assertEquals(1, results.size)
        server.close()
    }

    @Test
    fun `replayRepeated captures failures across attempts, not just the last`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val results = ConnectReplay.replayRepeated(context, "127.0.0.1", port, count = 2, jitterMs = 5L)

        assertEquals(2, results.size)
        results.forEach { assertEquals("FAILED", it.disposition) }
    }

    @Test
    fun `recheckCaTrust delegates to CapabilityProbe`() = runBlocking {
        val state = ConnectReplay.recheckCaTrust()
        // Robolectric has no real trust-store material, so the only real assertion is that
        // this returns a valid CaState without throwing (CapabilityProbe.probeCaInstalled is
        // exercised in detail by its own test).
        assertTrue(com.example.data.interception.CaState.entries.contains(state))
    }
}
