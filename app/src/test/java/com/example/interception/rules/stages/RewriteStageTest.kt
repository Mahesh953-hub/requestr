package com.example.interception.rules.stages

import android.os.Build
import com.example.interception.MitmProxyEngine
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodySource
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.Matched
import com.example.interception.rules.MessageReplacement
import com.example.interception.rules.Modification
import com.example.interception.rules.RedirectSpec
import com.example.interception.rules.ResolvedRewrite
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S4 task 29: [RewriteStage] in isolation -- the typed successors of the legacy
 * `RuleEnforcer.applyRequestModification` / `applyResponseModification` / REDIRECT branches
 * (byte parity with those is [com.example.interception.rules.LegacyParityTest]; this file pins the
 * *new* surface: ordered modifications, regex capture groups, query params, multi-rule
 * variables, the audit trail, and Content-Length recomputation on the wire).
 *
 * Robolectric only because `MockFixtureLoader` / the engine serializer touch `android.util.Log`
 * and `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RewriteStageTest {
    private val engine = MitmProxyEngine()

    private fun request(
        url: String = "https://api.example/v1/users?page=2&sort=asc",
        body: String = "",
        headers: Map<String, String> = mapOf("host" to "api.example", "accept" to "*/*", "x-trace" to "abc-123"),
        method: HttpMethod = HttpMethod.GET,
    ) = ParsedHttpMessage(
        isRequest = true,
        method = method,
        url = url,
        path = url.substringAfter("api.example"),
        host = "api.example",
        port = 443,
        headers = headers,
        body = body,
        contentType = headers["content-type"] ?: "",
    )

    private fun response(
        body: String = """{"ok":true,"id":42}""",
        headers: Map<String, String> = mapOf("content-type" to "application/json", "content-length" to body.toByteArray().size.toString()),
        status: Int = 200,
    ) = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = status,
        statusMessage = "OK",
        headers = headers,
        body = body,
        contentType = headers["content-type"] ?: "",
    )

    private fun rule(id: Long, name: String, config: RewriteConfig) = Matched(RuleRef(id, name, RuleStage.REWRITE), config)

    private fun resolved(vararg rules: Matched<RewriteConfig>): ResolvedRewrite = ResolvedRewrite(
        redirection = rules.firstOrNull { it.config.redirection != null },
        replaceRequest = rules.firstOrNull { it.config.replaceRequest != null },
        replaceResponse = rules.firstOrNull { it.config.replaceResponse != null },
        rules = rules.toList(),
    )

    // ── empty / no-op ────────────────────────────────────────────────────────────────────

    @Test
    fun `empty resolution is UNCHANGED and touches nothing`() {
        val req = request()
        val before = req.copy()
        assertEquals(RewriteStage.Result.UNCHANGED, RewriteStage.applyRequest(req, ResolvedRewrite(null, null, null, emptyList())))
        assertEquals(before, req)
        assertEquals(RewriteStage.Result.UNCHANGED, RewriteStage.applyResponse(response(), RewriteConfig()))
    }

    @Test
    fun `a rule with only response work leaves the request untouched`() {
        val req = request()
        val cfg = RewriteConfig(responseModifications = listOf(Modification.SetHeader("x-served-by", "requestr")))
        val result = RewriteStage.applyRequest(req, cfg)
        assertFalse(result.changed)
        assertEquals(0, result.stepsApplied)
        assertNull(req.headers["x-served-by"])
    }

    // ── header modifications ────────────────────────────────────────────────────────────

    @Test
    fun `set and remove header run in list order with templating`() {
        val req = request()
        val cfg = RewriteConfig(
            requestModifications = listOf(
                Modification.SetHeader("x-env", "{{env}}-{{request.method}}"),
                Modification.SetHeader("x-copy", "{{request.header.X-Trace}}"),
                Modification.RemoveHeader("accept"),
                Modification.SetHeader("accept", "application/json"),
            ),
            variables = mapOf("env" to "staging"),
        )
        val result = RewriteStage.applyRequest(req, cfg)
        assertTrue(result.changed)
        assertEquals(4, result.stepsApplied)
        assertEquals("staging-GET", req.headers["x-env"])
        assertEquals("abc-123", req.headers["x-copy"])
        assertEquals("application/json", req.headers["accept"])
    }

    @Test
    fun `regex header rewrites in place, numbered and named capture groups are honoured`() {
        val req = request(headers = mapOf("host" to "api.example", "Authorization" to "Bearer live_sk_0099"))
        val cfg = RewriteConfig(
            requestModifications = listOf(
                Modification.RegexHeader("authorization", """live_(?<kind>[a-z]+)_(\d+)""", "test_\${kind}_$2"),
            ),
        )
        RewriteStage.applyRequest(req, cfg)
        // Matched case-insensitively, written back under the original key.
        assertEquals("Bearer test_sk_0099", req.headers["Authorization"])
        assertNull(req.headers["authorization"])
    }

    @Test
    fun `regex header on a missing header is a no-op that still counts as attempted`() {
        val req = request()
        val cfg = RewriteConfig(requestModifications = listOf(Modification.RegexHeader("x-missing", "a", "b")))
        val result = RewriteStage.applyRequest(req, cfg)
        assertEquals(1, result.stepsApplied)
        assertEquals(request().headers, req.headers)
    }

    // ── query params ────────────────────────────────────────────────────────────────────

    @Test
    fun `set query param replaces an existing value, appends a new one, and keeps the fragment`() {
        val req = request(url = "https://api.example/v1/users?page=2&sort=asc#top")
        val cfg = RewriteConfig(
            requestModifications = listOf(
                Modification.SetQueryParam("page", "{{page}}"),
                Modification.SetQueryParam("debug", "1"),
                Modification.RemoveQueryParam("sort"),
            ),
            variables = mapOf("page" to "7"),
        )
        RewriteStage.applyRequest(req, cfg)
        assertEquals("https://api.example/v1/users?page=7&debug=1#top", req.url)
    }

    @Test
    fun `query param modifications never touch a response`() {
        val resp = response()
        resp.url = "https://api.example/v1/users?page=2"
        RewriteStage.applyResponse(resp, RewriteConfig(responseModifications = listOf(Modification.SetQueryParam("page", "9"))))
        assertEquals("https://api.example/v1/users?page=2", resp.url)
    }

    @Test
    fun `set query param url-encodes reserved characters`() {
        assertEquals("https://h/p?q=a%26b%3Dc", RewriteStage.setQueryParam("https://h/p", "q", "a&b=c"))
        assertEquals("https://h/p", RewriteStage.removeQueryParam("https://h/p?only=1", "only"))
        assertEquals("https://h/p?keep=1", RewriteStage.removeQueryParam("https://h/p?keep=1&drop=2&drop=3", "drop"))
    }

    // ── body modifications ──────────────────────────────────────────────────────────────

    @Test
    fun `regex body with capture groups marks the body replaced and drops stale replacement bytes`() {
        val resp = response(body = """{"id":42,"secret":"s3cr3t","name":"Ada"}""")
        resp.replacedBodyBytes = "stale".toByteArray()
        val cfg = RewriteConfig(
            responseModifications = listOf(
                Modification.RegexBody("\"secret\":\"([^\"]+)\"", "\"secret\":\"<redacted:$1>\""),
                Modification.RegexBody("\"name\":\"(?<n>[A-Za-z]+)\"", "\"name\":\"{{prefix}} \${n}\""),
            ),
            variables = mapOf("prefix" to "Dr."),
        )
        val result = RewriteStage.applyResponse(resp, cfg)
        assertTrue(result.changed)
        assertEquals("""{"id":42,"secret":"<redacted:s3cr3t>","name":"Dr. Ada"}""", resp.body)
        assertTrue(resp.bodyReplaced)
        assertNull(resp.replacedBodyBytes)
    }

    @Test
    fun `replace body with text, empty and a file fixture`() {
        val text = request(body = "orig")
        RewriteStage.applyRequest(text, RewriteConfig(requestModifications = listOf(Modification.ReplaceBody(BodySource.Text("{\"user\":\"{{u}}\"}")), ), variables = mapOf("u" to "ada")))
        assertEquals("{\"user\":\"ada\"}", text.body)
        assertTrue(text.bodyReplaced)

        val empty = request(body = "orig")
        RewriteStage.applyRequest(empty, RewriteConfig(requestModifications = listOf(Modification.ReplaceBody(BodySource.Empty))))
        assertEquals("", empty.body)
        assertTrue("explicit empty body is still a replacement", empty.bodyReplaced)

        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte(), 0x80.toByte())
        val file = File.createTempFile("rewrite-fixture", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        val binary = response()
        RewriteStage.applyResponse(binary, RewriteConfig(responseModifications = listOf(Modification.ReplaceBody(BodySource.File(file.absolutePath)))))
        assertTrue(binary.bodyReplaced)
        assertArrayEquals(bytes, binary.replacedBodyBytes)
        assertTrue(binary.isBodyBinary)
    }

    @Test
    fun `a broken file fixture leaves the body untouched but does not abort later modifications`() {
        val resp = response(body = "keep-me")
        val cfg = RewriteConfig(
            responseModifications = listOf(
                Modification.ReplaceBody(BodySource.File("/definitely/not/here.json")),
                Modification.SetHeader("x-after", "ran"),
            ),
        )
        val result = RewriteStage.applyResponse(resp, cfg)
        assertEquals("keep-me", resp.body)
        assertFalse(resp.bodyReplaced)
        assertEquals("ran", resp.headers["x-after"])
        assertEquals(2, result.stepsApplied)
    }

    @Test
    fun `an invalid regex fails that step only`() {
        val resp = response()
        val cfg = RewriteConfig(
            responseModifications = listOf(
                Modification.RegexBody("(unclosed", "x"),
                Modification.SetStatus(418),
            ),
        )
        val result = RewriteStage.applyResponse(resp, cfg)
        assertEquals(1, result.stepsApplied)
        assertEquals(418, resp.statusCode)
        assertEquals("""{"ok":true,"id":42}""", resp.body)
    }

    @Test
    fun `set status only applies to responses`() {
        val req = request()
        RewriteStage.applyRequest(req, RewriteConfig(requestModifications = listOf(Modification.SetStatus(500))))
        assertEquals(0, req.statusCode)
        val resp = response()
        RewriteStage.applyResponse(resp, RewriteConfig(responseModifications = listOf(Modification.SetStatus(503, "Down"))))
        assertEquals(503, resp.statusCode)
    }

    // ── replacement ─────────────────────────────────────────────────────────────────────

    @Test
    fun `replacement sets status, headers and body before modifications run`() {
        val resp = response()
        val cfg = RewriteConfig(
            replaceResponse = MessageReplacement(
                statusCode = 201,
                headers = mapOf("x-replaced" to "{{who}}", "content-type" to "text/plain"),
                body = BodySource.Text("created by {{who}}"),
            ),
            responseModifications = listOf(Modification.RegexBody("created", "CREATED")),
            variables = mapOf("who" to "rewrite"),
        )
        val trail = FlowAuditTrail()
        val result = RewriteStage.applyResponse(resp, resolved(rule(7, "replace-it", cfg)), trail)
        assertTrue(result.changed)
        assertEquals(2, result.stepsApplied)
        assertEquals(201, resp.statusCode)
        assertEquals("rewrite", resp.headers["x-replaced"])
        assertEquals("CREATED by rewrite", resp.body)
        val records = trail.toRecords()
        assertEquals(listOf("replace response", "1 response modification(s)"), records.map { it.detail })
        assertTrue(records.all { it.outcome == FlowAuditTrail.Outcome.APPLIED && it.phase == FlowAuditTrail.Phase.RESPONSE && it.rule.id == 7L })
    }

    @Test
    fun `request replacement ignores statusCode`() {
        val req = request(body = "old")
        RewriteStage.applyRequest(req, RewriteConfig(replaceRequest = MessageReplacement(statusCode = 999, body = BodySource.Text("new"))))
        assertEquals(0, req.statusCode)
        assertEquals("new", req.body)
    }

    // ── redirection ─────────────────────────────────────────────────────────────────────

    @Test
    fun `redirect rewrites url, host and Host header`() {
        val req = request()
        val cfg = RewriteConfig(redirection = RedirectSpec("https://staging.example:8443/v1/users"))
        val trail = FlowAuditTrail()
        RewriteStage.applyRequest(req, resolved(rule(3, "to-staging", cfg)), trail)
        assertEquals("https://staging.example:8443/v1/users", req.url)
        assertEquals("staging.example", req.host)
        assertEquals("staging.example", req.headers["host"])
        assertEquals("redirect -> https://staging.example:8443/v1/users", trail.toRecords().single().detail)
    }

    @Test
    fun `redirect with preserveHost keeps the client Host header`() {
        val req = request()
        RewriteStage.applyRequest(req, RewriteConfig(redirection = RedirectSpec("https://staging.example/x", preserveHost = true)))
        assertEquals("staging.example", req.host)
        assertEquals("api.example", req.headers["host"])
    }

    @Test
    fun `directoryMatch appends the original path and query, templated target`() {
        val req = request(url = "https://api.example/v1/users?page=2")
        val cfg = RewriteConfig(
            redirection = RedirectSpec("https://{{target}}/mirror/", directoryMatch = true),
            variables = mapOf("target" to "cdn.example"),
        )
        RewriteStage.applyRequest(req, cfg)
        assertEquals("https://cdn.example/mirror/v1/users?page=2", req.url)
        assertEquals("cdn.example", req.headers["host"])
    }

    @Test
    fun `a blank redirect target is skipped`() {
        val req = request()
        val result = RewriteStage.applyRequest(req, RewriteConfig(redirection = RedirectSpec("  ")))
        assertFalse(result.changed)
        assertEquals("https://api.example/v1/users?page=2&sort=asc", req.url)
    }

    // ── multi-rule ordering ────────────────────────────────────────────────────────────

    @Test
    fun `several matching rules apply in snapshot order, each with its own variables`() {
        val req = request()
        val first = rule(1, "first", RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-order", "{{v}}")), variables = mapOf("v" to "one")))
        val second = rule(2, "second", RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-order", "{{v}}"), Modification.SetHeader("x-seen-first", "{{request.header.x-order}}")), variables = mapOf("v" to "two")))
        val trail = FlowAuditTrail()
        val result = RewriteStage.applyRequest(req, resolved(first, second), trail)
        assertEquals(3, result.stepsApplied)
        assertEquals("two", req.headers["x-order"])
        // the second rule saw the first rule's write via the request.* built-ins
        assertEquals("one", req.headers["x-seen-first"])
        assertEquals(listOf(1L, 2L), trail.toRecords().map { it.rule.id })
    }

    @Test
    fun `unknown template tokens are left visibly unexpanded`() {
        val req = request()
        RewriteStage.applyRequest(req, RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-t", "{{nope}}/{{request.query.page}}/\\{{lit}}"))))
        assertEquals("{{nope}}/2/{{lit}}", req.headers["x-t"])
    }

    // ── wire-level Content-Length ──────────────────────────────────────────────────────

    @Test
    fun `serializer recomputes Content-Length from the rewritten body and drops a stale encoding`() {
        val resp = response(
            body = "short",
            headers = mapOf("content-type" to "text/plain", "content-length" to "5", "content-encoding" to "gzip", "transfer-encoding" to "chunked"),
        )
        RewriteStage.applyResponse(resp, RewriteConfig(responseModifications = listOf(Modification.ReplaceBody(BodySource.Text("a considerably longer replacement body")))))
        val wire = String(engine.serializeResponse(resp), Charsets.ISO_8859_1)
        val (head, body) = wire.split("\r\n\r\n", limit = 2)
        assertEquals("a considerably longer replacement body", body)
        assertTrue(head.lines().any { it.equals("Content-Length: ${body.length}", ignoreCase = true) })
        assertFalse(head.contains("Content-Encoding", ignoreCase = true))
        assertFalse(head.contains("Transfer-Encoding", ignoreCase = true))
    }

    @Test
    fun `serializer emits Content-Length 0 for an emptied request body`() {
        val req = request(body = "payload", headers = mapOf("host" to "api.example", "content-length" to "7"), method = HttpMethod.POST)
        RewriteStage.applyRequest(req, RewriteConfig(requestModifications = listOf(Modification.ReplaceBody(BodySource.Empty))))
        val wire = String(engine.serializeRequest(req), Charsets.ISO_8859_1)
        assertTrue(wire.startsWith("POST /v1/users?page=2&sort=asc HTTP/1.1\r\n"))
        assertTrue(wire, wire.contains("\r\ncontent-length: 0\r\n", ignoreCase = true))
        assertFalse(wire.contains("content-length: 7", ignoreCase = true))
        assertTrue(wire.endsWith("\r\n\r\n"))
    }
}
