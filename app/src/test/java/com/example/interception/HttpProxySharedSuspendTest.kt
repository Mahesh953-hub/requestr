package com.example.interception

import android.os.Build
import com.example.interception.relay.RelayMetrics
import com.example.interception.relay.RelayPool
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 27 (G21 flavour for the gateway): a `SUSPEND` gateway rule parks exactly one flow. The
 * client socket is held open silently for the rule's timeout and then closed; nothing is written
 * back, no upstream socket is dialled, and no shared relay permit is consumed -- so a parked flow
 * can never starve the other relays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class HttpProxySharedSuspendTest {

    private class Loopback : AutoCloseable {
        val server = ServerSocket(0)
        val client: Socket = Socket("127.0.0.1", server.localPort)
        val accepted: Socket = server.accept()
        override fun close() { runCatching { client.close() }; runCatching { accepted.close() }; runCatching { server.close() } }
    }

    @Test
    fun `suspendFlow sleeps for the rule timeout and then closes the socket without writing`() {
        Loopback().use { lb ->
            val slept = AtomicLong(-1)
            lb.accepted.soTimeout = 2_000
            HttpProxyShared.suspendFlow(lb.client, timeoutMs = 30_000, sleep = { slept.set(it) })

            assertEquals(30_000L, slept.get())
            assertTrue("the parked client's socket must be closed after the timeout", lb.client.isClosed)
            // The peer observes a clean EOF and nothing else: no status line, no reply bytes.
            assertEquals(-1, lb.accepted.getInputStream().read())
        }
    }

    @Test
    fun `a zero timeout closes immediately without sleeping`() {
        Loopback().use { lb ->
            var sleeps = 0
            HttpProxyShared.suspendFlow(lb.client, timeoutMs = 0, sleep = { sleeps++ })
            assertEquals(0, sleeps)
            assertTrue(lb.client.isClosed)
        }
    }

    @Test
    fun `the socket is closed even if the sleep is interrupted`() {
        Loopback().use { lb ->
            runCatching {
                HttpProxyShared.suspendFlow(lb.client, timeoutMs = 10) { throw InterruptedException("cancelled") }
            }
            assertTrue(lb.client.isClosed)
        }
    }

    @Test
    fun `a Suspend disposition through handleConnectAttempt takes no relay permit and dials nothing`() = runBlocking {
        Loopback().use { lb ->
            val metrics = RelayMetrics()
            val pool = RelayPool(parallelism = 1, maxQueued = 1, metrics = metrics)
            val output = ByteArrayOutputStream()
            var dialled = false
            var slept = 0L

            HttpProxyShared.handleConnectAttempt(
                client = lb.client,
                target = "held.example:443",
                disposition = ConnectControl.Disposition.Suspend(timeoutMs = 5_000, ruleName = "hold"),
                output = output,
                sleep = { slept = it },
                socketFactory = { dialled = true; Socket() },
                socketConnector = { _, _ -> dialled = true },
                relayPool = pool,
            )

            assertEquals(5_000L, slept)
            assertEquals("no bytes back to the client (CONNECT never completes)", 0, output.size())
            assertFalse("a parked flow must never dial upstream", dialled)
            assertEquals("no relay permit consumed", 1, pool.availablePermits)
            assertEquals(0L, metrics.snapshot().admitted)
            assertEquals(0, metrics.snapshot().active)
            assertTrue(lb.client.isClosed)
        }
    }

    @Test
    fun `parking one flow leaves the pool free for the next flow`() = runBlocking {
        val pool = RelayPool(parallelism = 1, maxQueued = 0, metrics = RelayMetrics())
        Loopback().use { lb ->
            HttpProxyShared.handleConnectAttempt(
                lb.client, "held.example:443",
                ConnectControl.Disposition.Suspend(timeoutMs = 1_000, ruleName = "hold"),
                ByteArrayOutputStream(), sleep = { }, relayPool = pool,
            )
        }
        val admission = pool.admit()
        assertTrue("the permit must still be available after a suspended flow", admission is RelayPool.Admission.Admitted)
        (admission as RelayPool.Admission.Admitted).release()
    }
}
