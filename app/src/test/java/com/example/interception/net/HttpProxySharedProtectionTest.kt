package com.example.interception.net

import com.example.interception.ConnectControl
import com.example.interception.HttpProxyShared
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

class HttpProxySharedProtectionTest {
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

    private class ScriptedSocket(
        response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK",
    ) : Socket() {
        val written = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(response.toByteArray())
        override fun getInputStream() = input
        override fun getOutputStream() = written
    }

    @Test
    fun `forwardRequest production method protects before connector`() {
        val events = mutableListOf<String>()
        install(result = true, events)
        val socket = ScriptedSocket()

        val result = HttpProxyShared.forwardRequest(
            rawUrl = "http://upstream.test/path",
            method = "GET",
            reqHeaders = mapOf("Host" to "upstream.test"),
            body = ByteArray(0),
            socketFactory = { events += "create"; socket },
            socketConnector = { supplied, address, _ ->
                assertEquals(socket, supplied)
                assertEquals(InetSocketAddress("upstream.test", 80), address)
                events += "connect"
            },
        )

        assertEquals(200, result.first)
        assertEquals("OK", result.third.toString(Charsets.UTF_8))
        assertEquals(listOf("create", "protect", "connect"), events)
    }

    @Test
    fun `forwardRequest protection failure returns 502 without connector`() {
        val events = mutableListOf<String>()
        install(result = false, events)
        val socket = ScriptedSocket()

        val result = HttpProxyShared.forwardRequest(
            rawUrl = "http://upstream.test/path",
            method = "GET",
            reqHeaders = mapOf("Host" to "upstream.test"),
            body = ByteArray(0),
            socketFactory = { events += "create"; socket },
            socketConnector = { _, _, _ -> events += "connect" },
        )

        assertEquals(502, result.first)
        assertEquals(listOf("create", "protect"), events)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `CONNECT relay production method protects before connector and fails closed`() {
        val events = mutableListOf<String>()
        install(result = false, events)
        val upstream = ScriptedSocket()
        val output = ByteArrayOutputStream()

        val failure = runCatching {
            HttpProxyShared.handleConnectAttempt(
                client = ScriptedSocket(),
                target = "upstream.test:443",
                disposition = ConnectControl.Disposition.AllowOpaque,
                output = output,
                socketFactory = { events += "create"; upstream },
                socketConnector = { _, _ -> events += "connect" },
            )
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(listOf("create", "protect"), events)
        assertTrue(upstream.isClosed)
    }
}
