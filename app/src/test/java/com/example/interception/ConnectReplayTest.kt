package com.example.interception

import android.os.Build
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

    /** Starts a fake CONNECT endpoint that replies with [statusLine] and captures the request it received. */
    private fun fakeConnectEndpoint(statusLine: String): Pair<ServerSocket, StringBuilder> {
        val server = ServerSocket(0)
        val received = StringBuilder()
        Thread {
            val conn = server.accept()
            val input = conn.getInputStream()
            // Read the CONNECT request line + headers up to the blank line.
            while (true) {
                val line = HttpProxyShared.readLine(input) ?: break
                received.append(line).append("\n")
                if (line.isEmpty()) break
            }
            conn.getOutputStream().write("$statusLine\r\n\r\n".toByteArray())
            conn.getOutputStream().flush()
            Thread.sleep(200) // give the client time to read before the socket tears down
            conn.close()
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
}
