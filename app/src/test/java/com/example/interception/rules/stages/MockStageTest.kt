package com.example.interception.rules.stages

import android.os.Build
import com.example.interception.MitmProxyEngine
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.HttpParser
import com.example.interception.rules.BodySource
import com.example.interception.rules.MockConfig
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
 * S4 task 29: [MockStage] -- the typed successor of `RuleEnforcer.buildMockResponse`. Header
 * lower-casing, content-type inference, `{{...}}` templating with request built-ins, `file://`
 * fixtures (text and binary), broken fixture fallback, and the always-recomputed Content-Length.
 * Robolectric for `org.json` (content-type sniffing) and `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MockStageTest {
    private val engine = MitmProxyEngine()

    @Test
    fun `defaults produce an empty 200 with text content type and zero length`() {
        val resp = MockStage.build(MockConfig())
        assertFalse(resp.isRequest)
        assertNull(resp.method)
        assertEquals(200, resp.statusCode)
        assertEquals("OK", resp.statusMessage)
        assertEquals("", resp.body)
        assertEquals("text/plain; charset=utf-8", resp.headers["content-type"])
        assertEquals("0", resp.headers["content-length"])
        assertTrue("a mock body is by definition a replacement", resp.bodyReplaced)
        assertFalse(resp.isBodyBinary)
    }

    @Test
    fun `status, headers and body are taken from the config with header names lower-cased`() {
        val resp = MockStage.build(
            MockConfig(
                statusCode = 404,
                statusMessage = "Not Found",
                headers = mapOf("X-Mock" to "yes", "Content-Type" to "application/problem+json"),
                body = BodySource.Text("""{"title":"missing"}"""),
            ),
        )
        assertEquals(404, resp.statusCode)
        assertEquals("Not Found", resp.statusMessage)
        assertEquals("yes", resp.headers["x-mock"])
        assertNull(resp.headers["X-Mock"])
        assertEquals("explicit content-type wins over inference", "application/problem+json", resp.headers["content-type"])
        assertEquals("""{"title":"missing"}""", resp.body)
        assertEquals("19", resp.headers["content-length"])
    }

    @Test
    fun `content type is inferred as JSON for objects and arrays, text otherwise`() {
        assertEquals("application/json; charset=utf-8", MockStage.build(MockConfig(body = BodySource.Text(""" {"a":1} """))).headers["content-type"])
        assertEquals("application/json; charset=utf-8", MockStage.build(MockConfig(body = BodySource.Text("[1,2]"))).headers["content-type"])
        assertEquals("text/plain; charset=utf-8", MockStage.build(MockConfig(body = BodySource.Text("{not json"))).headers["content-type"])
        assertEquals("text/plain; charset=utf-8", MockStage.build(MockConfig(body = BodySource.Text("hello"))).headers["content-type"])
    }

    @Test
    fun `content length counts UTF-8 bytes not characters`() {
        val body = "héllo wörld — ✓"
        val resp = MockStage.build(MockConfig(body = BodySource.Text(body)))
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toString(), resp.headers["content-length"])
        assertTrue(body.toByteArray(Charsets.UTF_8).size > body.length)
    }

    @Test
    fun `templates expand rule variables and request built-ins in headers and body`() {
        val resp = MockStage.build(
            MockConfig(
                headers = mapOf("x-echo-path" to "{{request.path}}", "x-env" to "{{env}}"),
                body = BodySource.Text("""{"method":"{{request.method}}","id":"{{request.query.id}}","auth":"{{request.header.Authorization}}","env":"{{env}}","raw":"\{{keep}}"}"""),
                variables = mapOf("env" to "qa"),
            ),
            requestUrl = "https://api.example/v1/items?id=77",
            method = "POST",
            requestHeaders = mapOf("authorization" to "Bearer t0k"),
        )
        assertEquals("/v1/items", resp.headers["x-echo-path"])
        assertEquals("qa", resp.headers["x-env"])
        assertEquals("""{"method":"POST","id":"77","auth":"Bearer t0k","env":"qa","raw":"{{keep}}"}""", resp.body)
        assertEquals("application/json; charset=utf-8", resp.headers["content-type"])
        assertEquals("https://api.example/v1/items?id=77", resp.url)
    }

    @Test
    fun `text file fixture is loaded, templated path included`() {
        val file = File.createTempFile("mock-fixture", ".json").apply { writeText("""{"from":"file"}"""); deleteOnExit() }
        val resp = MockStage.build(
            MockConfig(body = BodySource.File("{{dir}}/${file.name}"), variables = mapOf("dir" to file.parent!!)),
        )
        assertEquals("""{"from":"file"}""", resp.body)
        assertEquals("application/json; charset=utf-8", resp.headers["content-type"])
        assertEquals("15", resp.headers["content-length"])
        assertFalse(resp.isBodyBinary)
    }

    @Test
    fun `binary file fixture is served byte-exact as octet-stream`() {
        val bytes = ByteArray(256) { it.toByte() }
        val file = File.createTempFile("mock-fixture", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        val resp = MockStage.build(MockConfig(body = BodySource.File("file://${file.absolutePath}")))
        assertTrue(resp.isBodyBinary)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, resp.body)
        assertArrayEquals(bytes, resp.replacedBodyBytes)
        assertEquals("application/octet-stream", resp.headers["content-type"])
        assertEquals("256", resp.headers["content-length"])

        val wire = engine.serializeResponse(resp)
        val headerEnd = wire.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertArrayEquals(bytes, wire.copyOfRange(headerEnd, wire.size))
    }

    @Test
    fun `a broken file fixture falls back to an empty body and never leaks the reference`() {
        val resp = MockStage.build(MockConfig(body = BodySource.File("/nope/missing.json")))
        assertEquals("", resp.body)
        assertEquals("0", resp.headers["content-length"])
        assertFalse(resp.body.contains("missing.json"))
    }

    @Test
    fun `mock serializes to a parseable response with matching Content-Length`() {
        val resp = MockStage.build(
            MockConfig(statusCode = 201, statusMessage = "Created", headers = mapOf("Location" to "/v1/items/9"), body = BodySource.Text("""{"id":9}""")),
        )
        val wire = engine.serializeResponse(resp)
        val text = wire.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 201 Created\r\n"))
        assertTrue(text, text.contains("\r\ncontent-length: 8\r\n", ignoreCase = true))
        assertTrue(text.contains("\r\nlocation: /v1/items/9\r\n"))
        val parsed = HttpParser.tryParseResponse(wire) ?: throw AssertionError("client must parse the mock")
        assertEquals(201, parsed.statusCode)
        assertEquals("""{"id":9}""", parsed.body)
    }

    @Test
    fun `looksLikeJson is strict`() {
        assertTrue(MockStage.looksLikeJson("""{"a":[1,{"b":null}]}"""))
        assertTrue(MockStage.looksLikeJson("[]"))
        assertFalse(MockStage.looksLikeJson(""))
        assertFalse(MockStage.looksLikeJson("   "))
        assertFalse(MockStage.looksLikeJson("{\"a\":}"))
        assertFalse(MockStage.looksLikeJson("42"))
    }
}
