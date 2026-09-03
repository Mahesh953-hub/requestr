package com.example.interception.net

import java.net.DatagramSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SocketProtectorTest {
    private val registrations = mutableListOf<SocketProtector.Registration>()

    @Before
    fun setUp() {
        assertFalse("A previous test leaked the process-wide protector", SocketProtector.isInstalled)
        SocketProtector.resetMetrics()
    }

    @After
    fun tearDown() {
        registrations.asReversed().forEach(SocketProtector::clear)
        assertFalse("This test leaked the process-wide protector", SocketProtector.isInstalled)
    }

    private fun install(backing: SocketProtector.Backing): SocketProtector.Registration =
        SocketProtector.install(backing).also(registrations::add)

    private class RecordingBacking(
        private val result: Boolean = true,
        private val failure: Throwable? = null,
    ) : SocketProtector.Backing {
        var socketCalls = 0
        var datagramCalls = 0
        var fdCalls = 0

        private fun answer(): Boolean = failure?.let { throw it } ?: result
        override fun protectSocket(socket: Socket): Boolean = answer().also { socketCalls++ }
        override fun protectDatagramSocket(socket: DatagramSocket): Boolean = answer().also { datagramCalls++ }
        override fun protectFd(fd: Int): Boolean = answer().also { fdCalls++ }
    }

    @Test
    fun `all API forms are unavailable without an active VPN`() {
        Socket().use { assertEquals(SocketProtector.ProtectResult.Unavailable, SocketProtector.protectSocket(it)) }
        DatagramSocket().use {
            assertEquals(SocketProtector.ProtectResult.Unavailable, SocketProtector.protectDatagramSocket(it))
        }
        assertEquals(SocketProtector.ProtectResult.Unavailable, SocketProtector.protectFd(42))
        assertEquals(SocketProtector.Metrics(0, 0, 3), SocketProtector.metrics())
    }

    @Test
    fun `socket datagram and fd delegate to installed backing`() {
        val backing = RecordingBacking()
        install(backing)

        Socket().use { assertEquals(SocketProtector.ProtectResult.Protected, SocketProtector.protectSocket(it)) }
        DatagramSocket().use {
            assertEquals(SocketProtector.ProtectResult.Protected, SocketProtector.protectDatagramSocket(it))
        }
        assertEquals(SocketProtector.ProtectResult.Protected, SocketProtector.protectFd(7))

        assertEquals(1, backing.socketCalls)
        assertEquals(1, backing.datagramCalls)
        assertEquals(1, backing.fdCalls)
        assertEquals(SocketProtector.Metrics(3, 0, 0), SocketProtector.metrics())
    }

    @Test
    fun `false and thrown protection both fail closed`() {
        install(RecordingBacking(result = false))
        Socket().use { assertEquals(SocketProtector.ProtectResult.Failed, SocketProtector.protectSocket(it)) }

        install(RecordingBacking(failure = AssertionError("boom")))
        assertEquals(SocketProtector.ProtectResult.Failed, SocketProtector.protectFd(9))
        assertEquals(2, SocketProtector.metrics().failures)
    }

    @Test
    fun `stale registration cannot clear replacement service backing`() {
        val first = install(RecordingBacking())
        val secondBacking = RecordingBacking()
        install(secondBacking)

        SocketProtector.clear(first)
        assertTrue(SocketProtector.isInstalled)
        Socket().use { assertEquals(SocketProtector.ProtectResult.Protected, SocketProtector.protectSocket(it)) }
        assertEquals(1, secondBacking.socketCalls)
    }
}
