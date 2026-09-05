package com.example.interception

import android.os.Build
import com.example.interception.relay.RelayMetrics
import com.example.interception.relay.RelayPool
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ThrottleConfig
import com.example.interception.rules.stages.ThrottleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * S4 task 30 / G7 end to end on real sockets: a THROTTLE rule on an opaque CONNECT tunnel paces
 * the upstream->client copy loop of [HttpProxyShared.relay] so a 512 KiB download at 4 Mbps takes
 * ~1 s (±20 %), while the client->upstream direction with no uplink cap is not slowed at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ThrottleRelayTest {

    private class Loopback : AutoCloseable {
        val server = ServerSocket(0)
        val client: Socket = Socket("127.0.0.1", server.localPort)
        val accepted: Socket = server.accept()
        override fun close() { runCatching { client.close() }; runCatching { accepted.close() }; runCatching { server.close() } }
    }

    private val rule = RuleRef(4L, "lte", RuleStage.THROTTLE)

    @Test
    fun `download through a throttled opaque tunnel is paced to the downlink rate`() = runBlocking {
        val payload = 512L * 1024
        val bitsPerSecond = 4_000_000L
        val upstreamServer = ServerSocket(0)
        val served = CountDownLatch(1)
        val serverThread = thread(name = "throttle-upstream") {
            runCatching {
                upstreamServer.accept().use { s ->
                    val out = s.getOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var left = payload
                    while (left > 0) {
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        out.write(chunk, 0, n)
                        left -= n
                    }
                    out.flush()
                    s.shutdownOutput()
                    // wait for the client to hang up so the relay's other leg ends too
                    runCatching { while (s.getInputStream().read() != -1) { } }
                }
            }
            served.countDown()
        }

        Loopback().use { lb ->
            val pool = RelayPool(parallelism = 2, maxQueued = 2, metrics = RelayMetrics())
            val trail = FlowAuditTrail()
            val shaper = ThrottleStage.shaperFor(rule, ThrottleConfig(presetKey = "custom", downlinkBps = bitsPerSecond), trail)
            val received = AtomicLong()
            val start = System.nanoTime()

            val reader = thread(name = "throttle-client") {
                val input = lb.accepted.getInputStream()
                val buf = ByteArray(64 * 1024)
                // skip the 200 reply line
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    received.addAndGet(n.toLong())
                    if (received.get() >= payload) break
                }
                runCatching { lb.accepted.close() }
            }

            HttpProxyShared.handleConnectAttempt(
                client = lb.client,
                target = "127.0.0.1:${upstreamServer.localPort}",
                disposition = ConnectControl.Disposition.AllowOpaque,
                output = lb.client.getOutputStream(),
                relayPool = pool,
                throttle = shaper,
            ) { _, toClient -> received.set(maxOf(received.get(), toClient)) }

            reader.join(10_000)
            val elapsed = (System.nanoTime() - start) / 1e9
            val expected = ThrottleStage.expectedSeconds(payload, bitsPerSecond)
            assertTrue("client must have received the whole download (${received.get()})", received.get() >= payload)
            // burst allowance (~100 ms of rate) shortens the wall time a little; ±20 % of expected still brackets it
            assertTrue("took ${"%.2f".format(elapsed)}s, expected ${"%.2f".format(expected)}s", elapsed >= expected * 0.8 && elapsed <= expected * 1.2 + 0.25)
            assertEquals("throttle custom down=4Mbps", trail.toRecords().single().detail)
            assertEquals(FlowAuditTrail.Outcome.APPLIED, trail.toRecords().single().outcome)
        }
        assertTrue(served.await(5, TimeUnit.SECONDS))
        serverThread.join(2_000)
        upstreamServer.close()
    }

    @Test
    fun `an uncapped direction is not slowed by a shaper on the other one`() = runBlocking {
        val payload = 2L * 1024 * 1024
        val upstreamServer = ServerSocket(0)
        val sunk = AtomicLong()
        val done = CountDownLatch(1)
        thread(name = "throttle-upstream-sink") {
            runCatching {
                upstreamServer.accept().use { s ->
                    val buf = ByteArray(64 * 1024)
                    // close once the whole upload arrived so the relay's idle download leg gets EOF
                    while (sunk.get() < payload) {
                        val n = s.getInputStream().read(buf)
                        if (n == -1) break
                        sunk.addAndGet(n.toLong())
                    }
                }
            }
            done.countDown()
        }
        Loopback().use { lb ->
            val pool = RelayPool(parallelism = 2, maxQueued = 2, metrics = RelayMetrics())
            // 8 kbps downlink -- would take 35 minutes for the payload if it applied to uploads.
            val shaper = ThrottleStage.shaperFor(rule, ThrottleConfig(downlinkBps = 8_000))
            val writer = thread(name = "throttle-uploader") {
                val input = lb.accepted.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val out = lb.accepted.getOutputStream()
                val chunk = ByteArray(64 * 1024)
                var left = payload
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, n); left -= n
                }
                out.flush()
                lb.accepted.shutdownOutput()
            }
            val start = System.nanoTime()
            HttpProxyShared.handleConnectAttempt(
                client = lb.client,
                target = "127.0.0.1:${upstreamServer.localPort}",
                disposition = ConnectControl.Disposition.AllowOpaque,
                output = lb.client.getOutputStream(),
                relayPool = pool,
                throttle = shaper,
            ) { toUpstream, _ -> assertEquals(payload, toUpstream) }
            writer.join(5_000)
            val elapsed = (System.nanoTime() - start) / 1e9
            assertTrue("upload of 2 MiB must not be throttled (took ${"%.2f".format(elapsed)}s)", elapsed < 5.0)
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(payload, sunk.get())
        upstreamServer.close()
    }
}
