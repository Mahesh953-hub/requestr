package com.example.scripting.host

import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.ScriptConfig
import com.example.interception.rules.stages.ScriptStage
import com.example.scripting.PmPrelude
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 32 / gate G20: a misbehaving script host can never hang a relay. The Messenger
 * plumbing is replaced by fakes of [ScriptHostClient.Transport]; the deadline, kill, circuit
 * breaker and kill switch logic under test is the real thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScriptHostTimeoutTest {

    private fun request() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://api.example/v1", host = "api.example",
        headers = mapOf("x-a" to "1"), body = "",
    )

    private fun envelope(source: String = "1;", timeoutMs: Long = 300L) = ScriptContract.ScriptRequestEnvelope(
        phase = PmPrelude.PHASE_REQUEST, flowId = "f", scriptId = "s", source = source, timeoutMs = timeoutMs,
        maxInstructions = 1_000_000L, message = ScriptContract.MessageDto.fromMessage(request()),
    )

    /** Hangs until the deadline (like a wedged remote process) -- `call` honours the deadline, returns null. */
    private class HangingTransport : ScriptHostClient.Transport {
        val kills = AtomicInteger(0)
        val killReasons = ArrayList<String>()
        val released = CountDownLatch(1)
        override fun call(requestJson: String, deadlineMs: Long): String? {
            released.await(deadlineMs, TimeUnit.MILLISECONDS)
            return null
        }
        override fun kill(reason: String) { kills.incrementAndGet(); killReasons += reason }
    }

    /** Runs the envelope in-process (what the real service does) -- a healthy host. */
    private class InProcessTransport : ScriptHostClient.Transport {
        val kills = AtomicInteger(0)
        override fun call(requestJson: String, deadlineMs: Long): String =
            ScriptContract.encodeResponse(ScriptContract.execute(ScriptContract.decodeRequest(requestJson), pid = 4242))
        override fun kill(reason: String) { kills.incrementAndGet() }
    }

    @Test
    fun `a hanging host returns within the deadline and the host is killed`() {
        val transport = HangingTransport()
        val client = ScriptHostClient(transport)
        val started = System.currentTimeMillis()
        val outcome = client.run(envelope(timeoutMs = 300L))
        val elapsed = System.currentTimeMillis() - started
        assertTrue(outcome is ScriptHostClient.Outcome.HostFailure)
        assertTrue("elapsed ${elapsed}ms", elapsed in 250..3_000)
        assertEquals(1, transport.kills.get())
        assertTrue(transport.killReasons.single().contains("deadline"))
        assertFalse(client.isOpen)
    }

    @Test
    fun `the deadline is capped at ten seconds regardless of the rule's timeout`() {
        val transport = object : ScriptHostClient.Transport {
            var seenDeadline = -1L
            override fun call(requestJson: String, deadlineMs: Long): String? { seenDeadline = deadlineMs; return null }
            override fun kill(reason: String) {}
        }
        ScriptHostClient(transport).run(envelope(timeoutMs = 60_000L))
        assertEquals(ScriptHostClient.MAX_TIMEOUT_MS + ScriptHostClient.DEADLINE_GRACE_MS, transport.seenDeadline)
    }

    @Test
    fun `three consecutive host failures open the circuit and later calls fail fast`() {
        val transport = HangingTransport()
        val client = ScriptHostClient(transport)
        repeat(3) { client.run(envelope(timeoutMs = 50L)) }
        assertTrue(client.isOpen)
        assertEquals(3, transport.kills.get())
        val started = System.currentTimeMillis()
        val outcome = client.run(envelope(timeoutMs = 5_000L))
        assertTrue(System.currentTimeMillis() - started < 1_000)
        assertTrue((outcome as ScriptHostClient.Outcome.HostFailure).reason.contains("circuit open"))
        assertEquals(3, transport.kills.get())

        client.reset()
        assertFalse(client.isOpen)
        assertNull(client.lastFailure.value)
    }

    @Test
    fun `a healthy call resets the failure count so intermittent failures never trip the breaker`() {
        var hang = true
        val hanging = HangingTransport()
        val healthy = InProcessTransport()
        val transport = object : ScriptHostClient.Transport {
            override fun call(requestJson: String, deadlineMs: Long): String? = if (hang) hanging.call(requestJson, deadlineMs) else healthy.call(requestJson, deadlineMs)
            override fun kill(reason: String) { hanging.kill(reason) }
        }
        val client = ScriptHostClient(transport)
        repeat(2) { client.run(envelope(timeoutMs = 50L)) }
        hang = false
        assertTrue(client.run(envelope()) is ScriptHostClient.Outcome.Ok)
        hang = true
        repeat(2) { client.run(envelope(timeoutMs = 50L)) }
        assertFalse(client.isOpen)
    }

    @Test
    fun `the kill switch kills the host and opens the circuit`() {
        val transport = InProcessTransport()
        val client = ScriptHostClient(transport)
        assertTrue(client.run(envelope()) is ScriptHostClient.Outcome.Ok)
        client.killSwitch("user pressed stop")
        assertEquals(1, transport.kills.get())
        assertTrue(client.isOpen)
        assertEquals("user pressed stop", client.lastFailure.value)
        assertTrue(client.run(envelope()) is ScriptHostClient.Outcome.HostFailure)
    }

    @Test
    fun `a dead binder is a host failure that kills and counts toward the breaker`() {
        val transport = object : ScriptHostClient.Transport {
            var kills = 0
            override fun call(requestJson: String, deadlineMs: Long): String? = throw IllegalStateException("script host disconnected")
            override fun kill(reason: String) { kills++ }
        }
        val client = ScriptHostClient(transport)
        val outcome = client.run(envelope())
        assertTrue((outcome as ScriptHostClient.Outcome.HostFailure).reason.contains("disconnected"))
        assertEquals(1, transport.kills)
    }

    @Test
    fun `a real infinite loop is ended by the engine deadline inside the host and reported as a script error`() {
        val client = ScriptHostClient(InProcessTransport())
        val started = System.currentTimeMillis()
        val outcome = client.run(envelope("while (true) {}", timeoutMs = 300L).copy(maxInstructions = Long.MAX_VALUE))
        assertTrue(System.currentTimeMillis() - started < 5_000)
        val ok = outcome as ScriptHostClient.Outcome.Ok
        assertFalse(ok.response.ok)
        assertNotNull(ok.response.error)
        assertFalse(client.isOpen)
    }

    @Test
    fun `HostRunner - a host failure leaves the flow unmodified and reports the error`() {
        val transport = HangingTransport()
        val runner = ScriptStage.HostRunner(ScriptHostClient(transport))
        val message = request()
        val shared = LinkedHashMap<String, String>()
        val outcome = runner.run(ScriptStage.ScriptCall(message, ScriptConfig("pm.request.headers.set('x-b','2');", timeoutMs = 100L), "r", 0L, FlowAuditTrail.Phase.REQUEST, "flow", shared))
        assertNotNull(outcome.error)
        assertFalse(outcome.aborted)
        assertNull(message.headers["x-b"])
        assertEquals(1, transport.kills.get())
    }

    @Test
    fun `HostRunner - a healthy host mutates the message and threads shared state`() {
        val runner = ScriptStage.HostRunner(ScriptHostClient(InProcessTransport()))
        val message = request()
        val shared = LinkedHashMap<String, String>()
        val outcome = runner.run(
            ScriptStage.ScriptCall(
                message, ScriptConfig("function onRequest(context, request) { request.headers.set('x-b', '2'); context.shared.mark = 'm'; }"),
                "r", 0L, FlowAuditTrail.Phase.REQUEST, "flow", shared,
            ),
        )
        assertNull(outcome.error)
        assertEquals("2", message.headers["x-b"])
        assertEquals("m", shared["mark"])
    }
}
