package com.example.interception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Master plan §3 (C10) — pure unit tests for [RuleTemplateExpander], no Robolectric/Android
 * dependency needed since [RuleTemplateExpander.expand] is a pure function.
 */
class RuleTemplateExpanderTest {

    @Test
    fun `a template with no double braces is returned completely unchanged (fast path)`() {
        val template = "just plain text, no placeholders here"
        assertEquals(template, RuleTemplateExpander.expand(template))
    }

    @Test
    fun `an unrecognized placeholder is left completely intact rather than blanked`() {
        val result = RuleTemplateExpander.expand("Bearer {{nope}}")
        assertEquals("Bearer {{nope}}", result)
    }

    @Test
    fun `a user-defined variable from the rule config is substituted`() {
        val result = RuleTemplateExpander.expand("token={{apiKey}}", variables = mapOf("apiKey" to "abc123"))
        assertEquals("token=abc123", result)
    }

    @Test
    fun `multiple placeholders in one template all expand`() {
        val result = RuleTemplateExpander.expand(
            "{{greeting}}, {{name}}!",
            variables = mapOf("greeting" to "Hello", "name" to "World"),
        )
        assertEquals("Hello, World!", result)
    }

    @Test
    fun `whitespace inside the braces is trimmed before lookup`() {
        val result = RuleTemplateExpander.expand("{{ apiKey }}", variables = mapOf("apiKey" to "abc123"))
        assertEquals("abc123", result)
    }

    @Test
    fun `an escaped literal double-brace is never expanded and the backslash is dropped`() {
        val result = RuleTemplateExpander.expand("\\{{apiKey}} is literal", variables = mapOf("apiKey" to "abc123"))
        assertEquals("{{apiKey}} is literal", result)
    }

    @Test
    fun `escaped and real placeholders coexist correctly in the same template`() {
        val result = RuleTemplateExpander.expand(
            "\\{{literal}} but {{real}} expands",
            variables = mapOf("real" to "REAL", "literal" to "SHOULD_NOT_APPEAR"),
        )
        assertEquals("{{literal}} but REAL expands", result)
    }

    // ---- request.* built-ins ------------------------------------------------------------

    @Test
    fun `request context built-ins expand from a context built off a URL`() {
        val context = RuleTemplateExpander.Context.fromRequestUrl(
            "https://api.example.com/v1/users?id=42",
            method = "GET",
        )
        val result = RuleTemplateExpander.expand(
            "{{request.method}} {{request.host}}{{request.path}} q={{request.query.id}}",
            context = context,
        )
        assertEquals("GET api.example.com/v1/users q=42", result)
    }

    @Test
    fun `request context built-ins expand from a live ParsedHttpMessage`() {
        val request = com.example.interception.http.ParsedHttpMessage(
            isRequest = true,
            method = com.example.data.models.HttpMethod.POST,
            url = "https://api.example.com/v1/orders?ref=xyz",
            path = "/v1/orders",
            host = "api.example.com",
            headers = mapOf("x-trace-id" to "trace-999"),
        )
        val context = RuleTemplateExpander.Context.fromMessage(request)
        val result = RuleTemplateExpander.expand(
            "{{request.method}} {{request.host}}{{request.path}}?{{request.query.ref}} trace={{request.header.X-Trace-Id}}",
            context = context,
        )
        assertEquals("POST api.example.com/v1/orders?xyz trace=trace-999", result)
    }

    @Test
    fun `an unavailable request built-in is left untouched rather than rendered blank`() {
        // fromRequestUrl with no method supplied -- {{request.method}} has nothing to resolve to.
        val context = RuleTemplateExpander.Context.fromRequestUrl("https://api.example.com/x")
        val result = RuleTemplateExpander.expand("method={{request.method}}", context = context)
        assertEquals("method={{request.method}}", result)
    }

    @Test
    fun `a missing query parameter is left untouched`() {
        val context = RuleTemplateExpander.Context.fromRequestUrl("https://api.example.com/x?a=1")
        val result = RuleTemplateExpander.expand("b={{request.query.b}}", context = context)
        assertEquals("b={{request.query.b}}", result)
    }

    @Test
    fun `header lookup is case-insensitive`() {
        val context = RuleTemplateExpander.Context(headers = mapOf("Authorization" to "secret-token"))
        val result = RuleTemplateExpander.expand("auth={{request.header.authorization}}", context = context)
        assertEquals("auth=secret-token", result)
    }

    // ---- legacy TIMESTAMP/RANDOM/UUID built-ins, deterministic via injected clock/RNG ----

    @Test
    fun `TIMESTAMP expands to the injected clock's epoch-millis value, not the real wall clock`() {
        val result = RuleTemplateExpander.expand("ts={{TIMESTAMP}}", clock = { 1234567890L })
        assertEquals("ts=1234567890", result)
    }

    @Test
    fun `RANDOM expands to a value deterministically derived from the injected Random`() {
        val result = RuleTemplateExpander.expand("n={{RANDOM}}", random = Random(42))
        val expected = Random(42).nextInt(1000, 100_000).toString()
        assertEquals("n=$expected", result)
    }

    @Test
    fun `RANDOM always produces a 4-to-5-digit numeral`() {
        val result = RuleTemplateExpander.expand("{{RANDOM}}")
        assertTrue(result.matches(Regex("\\d{4,5}")))
    }

    @Test
    fun `UUID expands to a value deterministically derived from the injected Random and parses as a real UUID`() {
        val result = RuleTemplateExpander.expand("id={{UUID}}", random = Random(7))
        val seeded = Random(7)
        val expected = UUID(seeded.nextLong(), seeded.nextLong()).toString()
        assertEquals("id=$expected", result)
        // Re-parseable as a real UUID (validates the string shape independent of the exact value).
        UUID.fromString(result.removePrefix("id="))
    }

    // ---- {{$name}} dynamic generators (delegated to DynamicVariables) --------------------

    @Test
    fun `dollar-prefixed dynamic generator tokens expand via DynamicVariables`() {
        val result = RuleTemplateExpander.expand("id={{\$guid}}")
        assertTrue(result.startsWith("id="))
        val value = result.removePrefix("id=")
        UUID.fromString(value) // throws if not a valid UUID shape
    }

    @Test
    fun `an unrecognized dollar-prefixed token is left untouched, same as any other unknown token`() {
        val result = RuleTemplateExpander.expand("{{\$notARealGenerator}}")
        assertEquals("{{\$notARealGenerator}}", result)
    }

    // ---- precedence ------------------------------------------------------------------------

    @Test
    fun `a request built-in wins over a same-named user variable since it is a reserved namespace`() {
        // "request.host" is never a plausible user variable key anyway, but this pins the
        // documented precedence: built-ins are resolved before the user `variables` map.
        val context = RuleTemplateExpander.Context.fromRequestUrl("https://real-host.example.com/")
        val result = RuleTemplateExpander.expand(
            "{{request.host}}",
            variables = mapOf("request.host" to "should-not-win"),
            context = context,
        )
        assertEquals("real-host.example.com", result)
    }
}
