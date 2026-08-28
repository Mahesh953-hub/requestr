package com.example.ui.screens

import android.os.Build
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed (not plain JUnit) despite these otherwise being pure functions: without a
 * real `org.json` implementation, `prettyPrintJsonOrFallback`'s `JSONObject`/`JSONArray` calls hit
 * Android's stub jar and silently return defaults under Gradle's `isReturnDefaultValues` unit-test
 * option, throwing `NullPointerException` instead of parsing -- same reasoning as
 * [com.example.api.LocalApiServerToolsEncodingTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CapturedTrafficHelpersTest {

    private fun item(
        method: HttpMethod = HttpMethod.GET,
        status: Int = 200,
        latencyMs: Long = 100,
        host: String = "api.example.com",
        timestamp: Long = 1_000L,
        responseHeaders: Map<String, String> = emptyMap(),
        requestSize: Long = 0,
        responseSize: Long = 0,
    ) = CapturedTrafficItem(
        method = method,
        statusCode = status,
        latencyMs = latencyMs,
        host = host,
        timestamp = timestamp,
        responseHeaders = responseHeaders,
        requestSize = requestSize,
        responseSize = responseSize,
    )

    // ── content-type / render kind ──────────────────────────────────────

    @Test
    fun `contentTypeOf strips charset and lowercases`() {
        assertEquals(
            "application/json",
            contentTypeOf(mapOf("Content-Type" to "Application/JSON; charset=utf-8")),
        )
    }

    @Test
    fun `contentTypeOf returns null when absent`() {
        assertEquals(null, contentTypeOf(mapOf("server" to "nginx")))
    }

    @Test
    fun `detectBodyRenderKind classifies known content types`() {
        assertEquals(BodyRenderKind.JSON, detectBodyRenderKind("application/json", "{}"))
        assertEquals(BodyRenderKind.XML_OR_HTML, detectBodyRenderKind("text/html", "<html></html>"))
        assertEquals(BodyRenderKind.XML_OR_HTML, detectBodyRenderKind("application/xml", "<a/>"))
        assertEquals(BodyRenderKind.IMAGE, detectBodyRenderKind("image/png", ""))
        assertEquals(BodyRenderKind.PLAIN_TEXT, detectBodyRenderKind("text/plain", "hi"))
    }

    @Test
    fun `detectBodyRenderKind sniffs json-looking body when content type is missing`() {
        assertEquals(BodyRenderKind.JSON, detectBodyRenderKind(null, "{\"a\":1}"))
        assertEquals(BodyRenderKind.XML_OR_HTML, detectBodyRenderKind(null, "<a></a>"))
        assertEquals(BodyRenderKind.PLAIN_TEXT, detectBodyRenderKind(null, "just text"))
    }

    @Test
    fun `detectBodyRenderKind treats octet-stream with json body as JSON via sniff`() {
        assertEquals(BodyRenderKind.JSON, detectBodyRenderKind("application/octet-stream", "{\"a\":1}"))
    }

    @Test
    fun `detectBodyRenderKind treats octet-stream with non-textual body as unrenderable binary`() {
        assertEquals(BodyRenderKind.UNRENDERABLE_BINARY, detectBodyRenderKind("application/octet-stream", "\u0000\u0001\u0002binary"))
    }

    // ── JSON pretty print ───────────────────────────────────────────────

    @Test
    fun `prettyPrintJsonOrFallback pretty prints valid json object`() {
        val result = prettyPrintJsonOrFallback("""{"a":1,"b":2}""")
        assertTrue(result.contains("\n"))
        assertTrue(result.contains("\"a\": 1"))
    }

    @Test
    fun `prettyPrintJsonOrFallback pretty prints valid json array`() {
        val result = prettyPrintJsonOrFallback("[1,2,3]")
        assertTrue(result.contains("\n"))
    }

    @Test
    fun `prettyPrintJsonOrFallback falls back on invalid json`() {
        assertEquals("not json at all", prettyPrintJsonOrFallback("not json at all"))
    }

    @Test
    fun `prettyPrintJsonOrFallback returns empty body unchanged`() {
        assertEquals("", prettyPrintJsonOrFallback(""))
    }

    // ── size gate ────────────────────────────────────────────────────────

    @Test
    fun `shouldGateBodyRendering gates only bodies over the threshold`() {
        assertFalse(shouldGateBodyRendering(MAX_RENDERABLE_BODY_BYTES))
        assertTrue(shouldGateBodyRendering(MAX_RENDERABLE_BODY_BYTES + 1))
    }

    // ── multi-key sort ───────────────────────────────────────────────────

    @Test
    fun `sortCaptures with empty specs returns original order`() {
        val items = listOf(item(timestamp = 3), item(timestamp = 1), item(timestamp = 2))
        assertEquals(items, sortCaptures(items, emptyList()))
    }

    @Test
    fun `sortCaptures sorts by a single descending key`() {
        val items = listOf(item(latencyMs = 10), item(latencyMs = 30), item(latencyMs = 20))
        val sorted = sortCaptures(items, listOf(CaptureSortSpec(CaptureSortKey.LATENCY, descending = true)))
        assertEquals(listOf(30L, 20L, 10L), sorted.map { it.latencyMs })
    }

    @Test
    fun `sortCaptures sorts by a single ascending key`() {
        val items = listOf(item(latencyMs = 10), item(latencyMs = 30), item(latencyMs = 20))
        val sorted = sortCaptures(items, listOf(CaptureSortSpec(CaptureSortKey.LATENCY, descending = false)))
        assertEquals(listOf(10L, 20L, 30L), sorted.map { it.latencyMs })
    }

    @Test
    fun `sortCaptures applies multiple keys with tie-breaking`() {
        val items = listOf(
            item(status = 200, latencyMs = 50),
            item(status = 500, latencyMs = 10),
            item(status = 500, latencyMs = 90),
        )
        val sorted = sortCaptures(
            items,
            listOf(
                CaptureSortSpec(CaptureSortKey.STATUS, descending = true),
                CaptureSortSpec(CaptureSortKey.LATENCY, descending = true),
            ),
        )
        assertEquals(listOf(500 to 90L, 500 to 10L, 200 to 50L), sorted.map { it.statusCode to it.latencyMs })
    }

    // ── aggregate stats ──────────────────────────────────────────────────

    @Test
    fun `computeCaptureStats on empty list returns zeros`() {
        val stats = computeCaptureStats(emptyList())
        assertEquals(0, stats.total)
        assertEquals(0, stats.errorCount)
        assertEquals(0.0, stats.averageLatencyMs, 0.0)
    }

    @Test
    fun `computeCaptureStats aggregates errors, methods, latency and bytes`() {
        val items = listOf(
            item(method = HttpMethod.GET, status = 200, latencyMs = 100, requestSize = 10, responseSize = 20),
            item(method = HttpMethod.GET, status = 404, latencyMs = 200, requestSize = 5, responseSize = 5),
            item(method = HttpMethod.POST, status = 500, latencyMs = 300, requestSize = 1, responseSize = 1),
        )
        val stats = computeCaptureStats(items)
        assertEquals(3, stats.total)
        assertEquals(2, stats.errorCount)
        assertEquals(mapOf("GET" to 2, "POST" to 1), stats.byMethod)
        assertEquals(200.0, stats.averageLatencyMs, 0.0)
        assertEquals(42L, stats.totalBytes)
    }

    // ── image gallery ────────────────────────────────────────────────────

    @Test
    fun `isImageResponse detects image content types`() {
        assertTrue(isImageResponse("image/png"))
        assertTrue(isImageResponse("IMAGE/JPEG"))
        assertFalse(isImageResponse("application/json"))
        assertFalse(isImageResponse(null))
    }

    @Test
    fun `extractImageItems filters to image responses preserving order`() {
        val a = item(host = "a", responseHeaders = mapOf("content-type" to "image/png"))
        val b = item(host = "b", responseHeaders = mapOf("content-type" to "application/json"))
        val c = item(host = "c", responseHeaders = mapOf("content-type" to "image/jpeg"))
        val result = extractImageItems(listOf(a, b, c))
        assertEquals(listOf("a", "c"), result.map { it.host })
    }
}
