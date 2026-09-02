package com.example.ui.screens

import android.os.Build
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.Http3RowState
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

    // ── aggregate stats: status class / top hosts / spread ────────────────

    @Test
    fun `computeCaptureStats buckets status codes into classes`() {
        val items = listOf(
            item(status = 200), item(status = 201), item(status = 301),
            item(status = 404), item(status = 500), item(status = 503),
        )
        val stats = computeCaptureStats(items)
        assertEquals(mapOf("2xx" to 2, "3xx" to 1, "4xx" to 1, "5xx" to 2), stats.byStatusClass)
    }

    @Test
    fun `computeCaptureStats ranks top hosts by request count descending and caps the list`() {
        val items = (1..3).map { item(host = "busy.example.com") } +
            (1..2).map { item(host = "medium.example.com") } +
            listOf(item(host = "rare-a.example.com")) +
            listOf(item(host = "rare-b.example.com")) +
            listOf(item(host = "rare-c.example.com")) +
            listOf(item(host = "rare-d.example.com"))
        val stats = computeCaptureStats(items)
        assertEquals(STATS_TOP_HOSTS_LIMIT, stats.topHosts.size)
        assertEquals("busy.example.com" to 3, stats.topHosts[0])
        assertEquals("medium.example.com" to 2, stats.topHosts[1])
    }

    @Test
    fun `computeCaptureStats reports min max and average bytes per request`() {
        val items = listOf(
            item(latencyMs = 10, requestSize = 10, responseSize = 10),
            item(latencyMs = 50, requestSize = 20, responseSize = 20),
            item(latencyMs = 30, requestSize = 30, responseSize = 30),
        )
        val stats = computeCaptureStats(items)
        assertEquals(10L, stats.minLatencyMs)
        assertEquals(50L, stats.maxLatencyMs)
        assertEquals(40.0, stats.averageBytesPerRequest, 0.0001) // (20+40+60)/3
    }

    // ── replay schedule (C7) ────────────────────────────────────────────

    @Test
    fun `computeReplaySchedule returns empty list for zero count`() {
        assertEquals(emptyList<Long>(), computeReplaySchedule(0, 1000, 200))
    }

    @Test
    fun `computeReplaySchedule first send is always immediate`() {
        val schedule = computeReplaySchedule(5, 1000, 500, random = kotlin.random.Random(42))
        assertEquals(0L, schedule.first())
        assertEquals(5, schedule.size)
    }

    @Test
    fun `computeReplaySchedule with zero jitter is exactly the interval after the first send`() {
        val schedule = computeReplaySchedule(4, 750, 0, random = kotlin.random.Random(7))
        assertEquals(listOf(0L, 750L, 750L, 750L), schedule)
    }

    @Test
    fun `computeReplaySchedule jittered delays stay within bounds and never go negative`() {
        val interval = 200L
        val jitter = 150L
        val schedule = computeReplaySchedule(50, interval, jitter, random = kotlin.random.Random(1234))
        schedule.drop(1).forEach { delay ->
            assertTrue(delay >= 0L)
            assertTrue(delay >= interval - jitter)
            assertTrue(delay <= interval + jitter)
        }
    }

    @Test
    fun `computeReplaySchedule is deterministic for a given seed`() {
        val a = computeReplaySchedule(10, 300, 100, random = kotlin.random.Random(99))
        val b = computeReplaySchedule(10, 300, 100, random = kotlin.random.Random(99))
        assertEquals(a, b)
    }

    @Test
    fun `computeReplaySchedule rejects negative arguments`() {
        var threw = false
        try {
            computeReplaySchedule(-1, 100, 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `describeSortSpecs returns Newest for an empty spec list`() {
        assertEquals("Newest", describeSortSpecs(emptyList()))
    }

    @Test
    fun `describeSortSpecs formats a single descending key`() {
        assertEquals("Time↓", describeSortSpecs(listOf(CaptureSortSpec(CaptureSortKey.TIMESTAMP, descending = true))))
    }

    @Test
    fun `describeSortSpecs formats multiple keys in order with direction arrows`() {
        val specs = listOf(
            CaptureSortSpec(CaptureSortKey.STATUS, descending = true),
            CaptureSortSpec(CaptureSortKey.LATENCY, descending = false)
        )
        assertEquals("Status↓, Latency↑", describeSortSpecs(specs))
    }

    @Test
    fun `stripDataUriPrefix strips a data URI prefix leaving the base64 payload`() {
        assertEquals("QUJD", stripDataUriPrefix("data:image/png;base64,QUJD"))
    }

    @Test
    fun `stripDataUriPrefix leaves a bare base64 body unchanged`() {
        assertEquals("QUJD", stripDataUriPrefix("QUJD"))
    }

    @Test
    fun `stripDataUriPrefix leaves non-data-URI text unchanged even if it starts with data`() {
        assertEquals("database record", stripDataUriPrefix("database record"))
    }

    // ── ruleActionLabel / ruleActionColorHex (exhaustive over every RuleAction) ──────────

    @Test
    fun `ruleActionLabel and ruleActionColorHex are defined for every RuleAction value`() {
        com.example.data.local.RuleAction.values().forEach { action ->
            assertTrue(ruleActionLabel(action).isNotBlank())
            // 0xFF... literals decode as negative Long via the sign bit -- just assert it's a
            // real, distinct-looking ARGB literal rather than the accidental default 0.
            assertTrue(ruleActionColorHex(action) != 0L)
        }
    }

    @Test
    fun `ruleActionLabel gives the two new crypto actions distinct human-readable labels`() {
        assertEquals("Body Encrypt (AES)", ruleActionLabel(com.example.data.local.RuleAction.BODY_ENCRYPT_AES))
        assertEquals("Body Decrypt (AES)", ruleActionLabel(com.example.data.local.RuleAction.BODY_DECRYPT_AES))
    }

    @Test
    fun `ruleActionColorHex gives the two new crypto actions distinct colors from each other`() {
        assertTrue(
            ruleActionColorHex(com.example.data.local.RuleAction.BODY_ENCRYPT_AES) !=
                ruleActionColorHex(com.example.data.local.RuleAction.BODY_DECRYPT_AES)
        )
    }

    // ── isBodyCryptoAction / impliedCryptoDirection / supportsFileFixtureBody ──────────

    @Test
    fun `isBodyCryptoAction is true only for the two AES actions`() {
        assertTrue(isBodyCryptoAction(com.example.data.local.RuleAction.BODY_ENCRYPT_AES))
        assertTrue(isBodyCryptoAction(com.example.data.local.RuleAction.BODY_DECRYPT_AES))
        assertFalse(isBodyCryptoAction(com.example.data.local.RuleAction.BLOCK))
        assertFalse(isBodyCryptoAction(com.example.data.local.RuleAction.MOCK_RESPONSE))
    }

    @Test
    fun `impliedCryptoDirection matches ENCRYPT DECRYPT actions and defaults ENCRYPT otherwise`() {
        assertEquals("ENCRYPT", impliedCryptoDirection(com.example.data.local.RuleAction.BODY_ENCRYPT_AES))
        assertEquals("DECRYPT", impliedCryptoDirection(com.example.data.local.RuleAction.BODY_DECRYPT_AES))
        assertEquals("ENCRYPT", impliedCryptoDirection(com.example.data.local.RuleAction.BLOCK))
    }

    @Test
    fun `supportsFileFixtureBody is true only for the three body-replacement actions`() {
        assertTrue(supportsFileFixtureBody(com.example.data.local.RuleAction.MOCK_RESPONSE))
        assertTrue(supportsFileFixtureBody(com.example.data.local.RuleAction.MODIFY_REQUEST))
        assertTrue(supportsFileFixtureBody(com.example.data.local.RuleAction.MODIFY_RESPONSE))
        assertFalse(supportsFileFixtureBody(com.example.data.local.RuleAction.BLOCK))
        assertFalse(supportsFileFixtureBody(com.example.data.local.RuleAction.BODY_ENCRYPT_AES))
    }

    // ── cryptoKeyAliasError ──────────────────────────────────────────────────────────────

    @Test
    fun `cryptoKeyAliasError flags a blank alias on a crypto action`() {
        val error = cryptoKeyAliasError(com.example.data.local.RuleAction.BODY_ENCRYPT_AES, "")
        assertTrue(error != null && error.contains("Key alias"))
    }

    @Test
    fun `cryptoKeyAliasError is null for a non-blank alias on a crypto action`() {
        assertEquals(null, cryptoKeyAliasError(com.example.data.local.RuleAction.BODY_DECRYPT_AES, "my-alias"))
    }

    @Test
    fun `cryptoKeyAliasError is null for a blank alias on a non-crypto action`() {
        assertEquals(null, cryptoKeyAliasError(com.example.data.local.RuleAction.BLOCK, ""))
    }

    // ── classifyBodyStorageState / exceededCapMessage ───────────────────────────────────

    @Test
    fun `classifyBodyStorageState is TEXT when not flagged binary`() {
        assertEquals(BodyStorageState.TEXT, classifyBodyStorageState(isBinary = false, truncated = false))
        assertEquals(BodyStorageState.TEXT, classifyBodyStorageState(isBinary = false, truncated = true))
    }

    @Test
    fun `classifyBodyStorageState is BINARY_STORED when binary and within the cap`() {
        assertEquals(BodyStorageState.BINARY_STORED, classifyBodyStorageState(isBinary = true, truncated = false))
    }

    @Test
    fun `classifyBodyStorageState is BINARY_EXCEEDED_CAP when binary and truncated`() {
        assertEquals(BodyStorageState.BINARY_EXCEEDED_CAP, classifyBodyStorageState(isBinary = true, truncated = true))
    }

    @Test
    fun `exceededCapMessage names the true size and the cap`() {
        val message = exceededCapMessage(trueSizeBytes = 5 * 1024 * 1024L, capBytes = 2 * 1024 * 1024L)
        assertTrue(message.contains("5.0 MB"))
        assertTrue(message.contains("2.0 MB"))
    }

    // ── isCaUntrustedDegrade ─────────────────────────────────────────────────────────────

    @Test
    fun `isCaUntrustedDegrade is true for ALLOWED_OPAQUE with a failure reason`() {
        assertTrue(isCaUntrustedDegrade("ALLOWED_OPAQUE", "requestr's CA is not trusted on this device"))
    }

    @Test
    fun `isCaUntrustedDegrade is false for a plain ALLOWED_OPAQUE with no failure reason`() {
        assertFalse(isCaUntrustedDegrade("ALLOWED_OPAQUE", null))
    }

    @Test
    fun `isCaUntrustedDegrade is false for other dispositions even with a failure reason`() {
        assertFalse(isCaUntrustedDegrade("BLOCKED", "blocked by rule"))
        assertFalse(isCaUntrustedDegrade("MITM_DECRYPTED", null))
    }

    // ── hexDump ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `hexDump renders an empty byte array as an empty string`() {
        assertEquals("", hexDump(ByteArray(0)))
    }

    @Test
    fun `hexDump renders bytes as hex plus a printable-ASCII column`() {
        val dump = hexDump("AB".toByteArray(Charsets.US_ASCII))
        assertTrue(dump.contains("41 42"))
        assertTrue(dump.contains("AB"))
    }

    @Test
    fun `hexDump truncates past maxBytes and notes how many more bytes remain`() {
        val bytes = ByteArray(20) { 0x41 }
        val dump = hexDump(bytes, maxBytes = 16)
        assertTrue(dump.contains("more byte(s)"))
    }

    // ── isSensitiveHeaderName / maskHeaderValue (Item 2, audit §7.2) ───────────────────────

    @Test
    fun `isSensitiveHeaderName matches the fixed set case-insensitively`() {
        assertTrue(isSensitiveHeaderName("Set-Cookie"))
        assertTrue(isSensitiveHeaderName("set-cookie"))
        assertTrue(isSensitiveHeaderName("SET-COOKIE"))
        assertTrue(isSensitiveHeaderName("Cookie"))
        assertTrue(isSensitiveHeaderName("cookie"))
        assertTrue(isSensitiveHeaderName("Authorization"))
        assertTrue(isSensitiveHeaderName("authorization"))
        assertTrue(isSensitiveHeaderName("Proxy-Authorization"))
        assertTrue(isSensitiveHeaderName("proxy-authorization"))
    }

    @Test
    fun `isSensitiveHeaderName tolerates incidental whitespace around the name`() {
        assertTrue(isSensitiveHeaderName("  Set-Cookie  "))
    }

    @Test
    fun `isSensitiveHeaderName is false for unrelated header names`() {
        assertFalse(isSensitiveHeaderName("Content-Type"))
        assertFalse(isSensitiveHeaderName("X-Cookie-Consent"))
        assertFalse(isSensitiveHeaderName("Authorization-Hint"))
        assertFalse(isSensitiveHeaderName(""))
    }

    @Test
    fun `maskHeaderValue never renders one asterisk per character`() {
        val longToken = "a".repeat(500)
        val masked = maskHeaderValue(longToken)
        assertFalse(masked.contains(longToken))
        assertTrue(masked.length < 50)
    }

    @Test
    fun `maskHeaderValue reports the real byte length as separate text`() {
        val masked = maskHeaderValue("s3cr3t-token-value")
        assertTrue(masked.contains("18 bytes"))
    }

    @Test
    fun `maskHeaderValue byte length reflects UTF-8 encoding not char count`() {
        // "é" is 1 char but 2 UTF-8 bytes -- the reported length must be the real byte length.
        val masked = maskHeaderValue("é")
        assertTrue(masked.contains("2 bytes"))
    }

    @Test
    fun `maskHeaderValue on an empty value has no byte-count suffix`() {
        val masked = maskHeaderValue("")
        assertFalse(masked.contains("bytes"))
        assertTrue(masked.isNotEmpty())
    }

    @Test
    fun `maskHeaderValue on a very long value stays a short fixed placeholder`() {
        val huge = "x".repeat(10_000)
        val masked = maskHeaderValue(huge)
        assertTrue(masked.length < 50)
        assertTrue(masked.contains("10000 bytes"))
    }

    @Test
    fun `unknownStatusLabelFor renders OBSERVED for an HTTP3 row with no decoded status`() {
        assertEquals("OBSERVED", unknownStatusLabelFor(Http3RowState.OBSERVED))
    }

    @Test
    fun `unknownStatusLabelFor renders DETECTED for a QUIC-only row`() {
        assertEquals("DETECTED", unknownStatusLabelFor(Http3RowState.DETECTED))
    }

    @Test
    fun `unknownStatusLabelFor falls back to an em dash for a statusless non-HTTP3 row`() {
        // e.g. a fully opaque undecoded-TLS row, which has no Http3RowState at all.
        assertEquals("—", unknownStatusLabelFor(null))
    }

    @Test
    fun `unknownStatusLabelFor never returns DECODED even if misused on a decoded row`() {
        // DECODED implies a real status was found, so hasKnownStatus should already be true and
        // this function should never actually be called for that state -- but if it ever is
        // (defensive), it must not fabricate a "DECODED" placeholder that looks like a status.
        assertEquals("—", unknownStatusLabelFor(Http3RowState.DECODED))
    }
}
