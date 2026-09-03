package com.example.interception.net

import com.example.interception.RemoteCertificateMirror
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteCertificateMirrorProtectionTest {
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
    fun `production fetch protects before connector without network access`() {
        val events = mutableListOf<String>()
        install(result = true, events)
        val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket

        val result = RemoteCertificateMirror.fetchRemoteLeaf(
            host = "upstream.test",
            socketFactory = { _: SSLContext -> events += "create"; socket },
            socketConnector = { supplied: SSLSocket, _: SocketAddress, _: Int ->
                assertEquals(socket, supplied)
                events += "connect"
                throw StopAfterConnect
            },
        )

        assertNull(result)
        assertEquals(listOf("create", "protect", "connect"), events)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `protection failure returns null and never invokes connector`() {
        val events = mutableListOf<String>()
        install(result = false, events)
        val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket

        val result = RemoteCertificateMirror.fetchRemoteLeaf(
            host = "upstream.test",
            socketFactory = { events += "create"; socket },
            socketConnector = { _, _, _ -> events += "connect" },
        )

        assertNull(result)
        assertEquals(listOf("create", "protect"), events)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `unavailable outside VPN is allowed to reach connector`() {
        val events = mutableListOf<String>()
        val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket

        RemoteCertificateMirror.fetchRemoteLeaf(
            host = "upstream.test",
            socketFactory = { events += "create"; socket },
            socketConnector = { _, _, _ -> events += "connect"; throw StopAfterConnect },
        )

        assertEquals(listOf("create", "connect"), events)
        assertEquals(1, SocketProtector.metrics().unavailable)
    }

    private object StopAfterConnect : RuntimeException()
}
