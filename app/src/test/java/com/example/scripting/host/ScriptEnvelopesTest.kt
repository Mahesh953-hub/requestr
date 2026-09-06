package com.example.scripting.host

import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.scripting.GlobalVariableStore
import com.example.scripting.PmPrelude
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 32: the JSON contract that crosses the `:scripthost` process boundary -- round trips,
 * base64 binary bodies, the 2 MiB truncation flag, and the pure [ScriptContract.execute].
 * Robolectric only for `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScriptEnvelopesTest {

    @Before
    fun clearGlobals() = GlobalVariableStore.clear()

    private fun request(body: String = "{\"a\":1}") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.POST, url = "https://api.example/v1/users", host = "api.example",
        headers = mapOf("content-type" to "application/json", "x-a" to "1"), body = body,
    )

    private fun envelope(source: String, message: ParsedHttpMessage = request(), phase: String = PmPrelude.PHASE_REQUEST, shared: Map<String, String> = emptyMap()) =
        ScriptContract.ScriptRequestEnvelope(
            phase = phase, flowId = "flow-1", scriptId = "rule-a", source = source, timeoutMs = 2_000L, maxInstructions = 1_000_000L,
            variables = mapOf("token" to "abc"), shared = shared, message = ScriptContract.MessageDto.fromMessage(message),
        )

    @Test
    fun `request and response envelopes round-trip through JSON`() {
        val env = envelope("pm.request.headers.set('x-b', '2');", shared = mapOf("k" to "v"))
        val decoded = ScriptContract.decodeRequest(ScriptContract.encodeRequest(env))
        assertEquals(env, decoded)

        val response = ScriptContract.ScriptResponseEnvelope(
            ok = true, aborted = false, message = env.message.copy(bodyReplaced = true, body = "x"),
            shared = mapOf("k" to "v2"), globals = mapOf("g" to "1"), console = listOf("hello"),
            tests = listOf(ScriptContract.TestDto("t", true, "ok")), error = null, pid = 42,
        )
        assertEquals(response, ScriptContract.decodeResponse(ScriptContract.encodeResponse(response)))
    }

    @Test
    fun `binary bodies cross the boundary base64 and come back byte-exact`() {
        val bytes = ByteArray(1024) { (it * 31).toByte() }
        val message = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
            headers = mapOf("content-type" to "application/octet-stream"), body = "", isBodyBinary = true, rawBodyBytes = bytes,
        )
        val dto = ScriptContract.MessageDto.fromMessage(message)
        assertNull(dto.body)
        assertNotNull(dto.bodyBase64)
        assertFalse(dto.bodyTruncated)
        val working = ScriptContract.decodeRequest(ScriptContract.encodeRequest(envelope("1;", message))).message.toWorkingMessage()
        assertTrue(working.isBodyBinary)
        assertArrayEquals(bytes, working.rawBodyBytes)
    }

    @Test
    fun `bodies over the limit are truncated and flagged`() {
        val big = "x".repeat(ScriptContract.MAX_SCRIPT_BODY_BYTES + 10)
        val dto = ScriptContract.MessageDto.fromMessage(request(big))
        assertTrue(dto.bodyTruncated)
        assertEquals(ScriptContract.MAX_SCRIPT_BODY_BYTES, dto.body!!.length)

        val small = ScriptContract.MessageDto.fromMessage(request("small"))
        assertFalse(small.bodyTruncated)
        assertEquals("small", small.body)
    }

    @Test
    fun `execute applies pm mutations and returns them in the reply`() {
        val reply = ScriptContract.execute(envelope("pm.request.headers.set('x-b', '2'); pm.request.body = 'edited'; console.log('hi ' + pm.environment.get('token'));"))
        assertTrue(reply.error, reply.ok)
        assertFalse(reply.aborted)
        val out = reply.message!!
        assertEquals("2", out.headers["x-b"])
        assertEquals("edited", out.body)
        assertTrue(out.bodyReplaced)
        assertEquals(listOf("hi abc"), reply.console)

        val target = request()
        val original = ScriptContract.MessageDto.fromMessage(target)
        assertNull(out.applyTo(target, original))
        assertEquals("edited", target.body)
        assertTrue(target.bodyReplaced)
        assertEquals("2", target.headers["x-b"])
    }

    @Test
    fun `execute leaves the body untouched when the script does not assign it`() {
        val reply = ScriptContract.execute(envelope("pm.request.headers.set('x-b', '2');"))
        assertTrue(reply.ok)
        assertFalse(reply.message!!.bodyReplaced)
        val target = request()
        reply.message!!.applyTo(target, ScriptContract.MessageDto.fromMessage(target))
        assertFalse(target.bodyReplaced)
        assertEquals("{\"a\":1}", target.body)
    }

    @Test
    fun `a body mutation against a truncated body is refused but headers still apply`() {
        val big = request("y".repeat(ScriptContract.MAX_SCRIPT_BODY_BYTES + 1))
        val original = ScriptContract.MessageDto.fromMessage(big)
        assertTrue(original.bodyTruncated)
        val reply = ScriptContract.execute(envelope("pm.request.body = 'tiny'; pm.request.headers.set('x-b', '2');", big))
        assertTrue(reply.ok)
        val refused = reply.message!!.applyTo(big, original)
        assertNotNull(refused)
        assertTrue(refused!!.contains("refused"))
        assertFalse(big.bodyReplaced)
        assertEquals(ScriptContract.MAX_SCRIPT_BODY_BYTES + 1, big.body.length)
        assertEquals("2", big.headers["x-b"])
    }

    @Test
    fun `lifecycle hooks see context shared and a null return aborts`() {
        val reply = ScriptContract.execute(
            envelope(
                "function onRequest(context, request) { context.shared.seen = request.url; request.headers.set('x-hook', 'yes'); }",
                shared = mapOf("prior" to "1"),
            ),
        )
        assertTrue(reply.error, reply.ok)
        assertFalse(reply.aborted)
        assertEquals("https://api.example/v1/users", reply.shared["seen"])
        assertEquals("1", reply.shared["prior"])
        assertEquals("yes", reply.message!!.headers["x-hook"])

        val abort = ScriptContract.execute(envelope("function onRequest(context, request) { return null; }"))
        assertTrue(abort.ok)
        assertTrue(abort.aborted)

        val responsePhase = ScriptContract.execute(
            envelope(
                "var calls = []; function onRequest(c, r) { calls.push('req'); } function onResponse(c, r) { calls.push('resp:' + r.code); c.shared.n = String(calls.length); }",
                message = ParsedHttpMessage(isRequest = false, method = null, statusCode = 204, statusMessage = "No Content", headers = emptyMap(), body = ""),
                phase = PmPrelude.PHASE_RESPONSE,
            ),
        )
        assertTrue(responsePhase.error, responsePhase.ok)
        assertEquals("1", responsePhase.shared["n"])
    }

    @Test
    fun `globals are seeded from and returned through the envelope`() {
        val env = envelope("pm.globals.set('count', String(Number(pm.globals.get('count')) + 1));").copy(globals = mapOf("count" to "41"))
        val reply = ScriptContract.execute(env)
        assertTrue(reply.error, reply.ok)
        assertEquals("42", reply.globals["count"])
    }

    @Test
    fun `script errors are reported with ok false and the flow is not aborted`() {
        val reply = ScriptContract.execute(envelope("throw new Error('kaboom');"))
        assertFalse(reply.ok)
        assertFalse(reply.aborted)
        assertTrue(reply.error!!.contains("kaboom"))
        assertNull(reply.message)
    }

    @Test
    fun `a runaway script is cut off by the engine deadline inside execute`() {
        val started = System.currentTimeMillis()
        val reply = ScriptContract.execute(envelope("while (true) { }").copy(timeoutMs = 300L, maxInstructions = Long.MAX_VALUE))
        val elapsed = System.currentTimeMillis() - started
        assertFalse(reply.ok)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
    }
}
