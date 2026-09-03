package com.example.interception.net

import com.example.interception.LazyUpstreamConnection
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LazyUpstreamProtectionTest {
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

    @Test
    fun `production connect protects before injected connector`() {
        val events = mutableListOf<String>()
        install(result = true, events)
        val socket = Socket()
        val connection = LazyUpstreamConnection(
            host = "upstream.test",
            port = 443,
            socketFactory = { events += "create"; socket },
            socketConnector = { supplied: Socket, _: SocketAddress ->
                assertEquals(socket, supplied)
                events += "connect"
                throw StopAfterConnect
            },
        )

        runCatching { connection.connect() }
        assertEquals(listOf("create", "protect", "connect"), events)
    }

    @Test
    fun `protection failure closes socket and never invokes connector`() {
        val events = mutableListOf<String>()
        install(result = false, events)
        val socket = Socket()
        val connection = LazyUpstreamConnection(
            host = "upstream.test",
            port = 443,
            socketFactory = { events += "create"; socket },
            socketConnector = { _, _ -> events += "connect" },
        )

        val failure = runCatching { connection.connect() }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(listOf("create", "protect"), events)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `unavailable outside VPN still reaches connector`() {
        val events = mutableListOf<String>()
        val connection = LazyUpstreamConnection(
            host = "upstream.test",
            port = 443,
            socketFactory = { events += "create"; Socket() },
            socketConnector = { _, _ -> events += "connect"; throw StopAfterConnect },
        )

        runCatching { connection.connect() }
        assertEquals(listOf("create", "connect"), events)
        assertEquals(1, SocketProtector.metrics().unavailable)
    }

    private object StopAfterConnect : RuntimeException()
}
