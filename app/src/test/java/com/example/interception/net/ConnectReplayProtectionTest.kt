package com.example.interception.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.interception.ConnectReplay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectReplayProtectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var registration: SocketProtector.Registration? = null

    @Before
    fun setUp() {
        assertFalse("A previous test leaked SocketProtector", SocketProtector.isInstalled)
        SocketProtector.resetMetrics()
    }

    @After
    fun tearDown() {
        registration?.let(SocketProtector::clear)
        assertFalse("This test leaked SocketProtector", SocketProtector.isInstalled)
    }

    private fun install(result: Boolean, events: MutableList<String>) {
        registration = SocketProtector.install(object : SocketProtector.Backing {
            override fun protectSocket(socket: Socket): Boolean = result.also {
                assertTrue("socket must be wildcard-bound before protection", socket.isBound)
                events += "protect"
            }
            override fun protectDatagramSocket(socket: DatagramSocket) = result
            override fun protectFd(fd: Int) = result
        })
    }

    private class ReplaySocket(response: String) : Socket() {
        private val input = ByteArrayInputStream(response.toByteArray())
        val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = output
    }

    @Test
    fun `production replay protects before connector`() {
        val events = mutableListOf<String>()
        install(result = true, events)
        val socket = ReplaySocket("HTTP/1.1 200 Connection Established\r\n\r\n")

        val result = ConnectReplay.replay(
            context = context,
            targetHost = "upstream.test",
            targetPort = 443,
            socketFactory = { events += "create"; socket },
            socketConnector = { supplied, address, _ ->
                assertEquals(socket, supplied)
                assertEquals(InetSocketAddress("upstream.test", 443), address)
                events += "connect"
            },
        )

        assertEquals("ALLOWED_OPAQUE", result.disposition)
        assertEquals(listOf("create", "protect", "connect"), events)
        assertTrue(socket.output.toString(Charsets.UTF_8.name()).startsWith("CONNECT upstream.test:443"))
    }

    @Test
    fun `production replay returns failed and never connects when protection fails`() {
        val events = mutableListOf<String>()
        install(result = false, events)
        val socket = ReplaySocket("")

        val result = ConnectReplay.replay(
            context = context,
            targetHost = "upstream.test",
            targetPort = 443,
            socketFactory = { events += "create"; socket },
            socketConnector = { _, _, _ -> events += "connect" },
        )

        assertEquals("FAILED", result.disposition)
        assertTrue(result.error.orEmpty().contains("SocketProtector"))
        assertEquals(listOf("create", "protect"), events)
        assertTrue(socket.isClosed)
    }
}
