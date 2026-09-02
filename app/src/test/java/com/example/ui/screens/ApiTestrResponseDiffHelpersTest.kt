package com.example.ui.screens

import com.example.data.models.ApiResponseData
import com.example.ui.components.DiffKind
import com.example.ui.components.buildDiffModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the "Compare runs" state behind API Testr's DiffView call site. No Android
 * dependency. The last two tests go end-to-end through `buildDiffModel` with bodies shaped like
 * real API Testr responses, so the sheet's reachable diff states are pinned here, not assumed.
 */
class ApiTestrResponseDiffHelpersTest {

    private fun run(
        requestId: String = "req-1",
        body: String = "{}",
        method: String = "GET",
        url: String = "https://httpbin.org/get",
        statusCode: Int = 200,
        statusMessage: String = "OK",
        error: String? = null,
        latencyMs: Long = 142,
        sizeBytes: Long = 1024,
        timestamp: Long = 0L,
    ) = ApiRunSnapshot(
        requestId = requestId, method = method, url = url,
        response = ApiResponseData(
            statusCode = statusCode, statusMessage = statusMessage, body = body, error = error,
            latencyMs = latencyMs, sizeBytes = sizeBytes, timestamp = timestamp,
            isSuccess = statusCode in 200..299,
        ),
    )

    @Test
    fun `first send of a request has no baseline and yields the only-one-run state`() {
        val (baseline, runs) = recordRun(emptyMap(), run())
        assertNull(baseline)
        assertEquals(1, runs.size)
        val availability = responseCompareAvailability(baseline)
        assertEquals(ResponseCompareAvailability.NO_PREVIOUS_RUN, availability)
        assertTrue(responseCompareMessage(availability)!!.contains("Only one response so far"))
    }

    @Test
    fun `second send of the same request returns the first as baseline and replaces it`() {
        val first = run(body = "a")
        val second = run(body = "b")
        val (_, afterFirst) = recordRun(emptyMap(), first)
        val (baseline, afterSecond) = recordRun(afterFirst, second)
        assertEquals(first, baseline)
        assertEquals(second, afterSecond["req-1"])
        assertEquals(ResponseCompareAvailability.READY, responseCompareAvailability(baseline))
        assertNull(responseCompareMessage(ResponseCompareAvailability.READY))
    }

    @Test
    fun `interleaving another request does not steal the baseline`() {
        val a1 = run(requestId = "A", body = "a1")
        val b1 = run(requestId = "B", body = "b1")
        val a2 = run(requestId = "A", body = "a2")
        var runs: Map<String, ApiRunSnapshot> = emptyMap()
        runs = recordRun(runs, a1).second
        val (bBaseline, afterB) = recordRun(runs, b1)
        assertNull(bBaseline)
        val (aBaseline, afterA2) = recordRun(afterB, a2)
        assertEquals(a1, aBaseline)
        assertEquals(b1, afterA2["B"])
        assertEquals(a2, afterA2["A"])
    }

    @Test
    fun `runSideLabel reports status latency size and time, and names a transport failure`() {
        // Fixed epoch so the HH:mm:ss suffix is deterministic regardless of the JVM timezone:
        // we assert on the leading fields and that a time-of-day segment is present.
        val ok = runSideLabel(run(latencyMs = 87, sizeBytes = 2048, timestamp = 0L))
        assertTrue(ok, ok.startsWith("200 OK · 87 ms · 2.0 KB · "))
        assertTrue(ok, Regex("\\d{2}:\\d{2}:\\d{2}$").containsMatchIn(ok))

        val failed = runSideLabel(run(statusCode = 0, statusMessage = "", error = "timeout after 30s", sizeBytes = 0))
        assertTrue(failed, failed.startsWith("Failed: timeout after 30s · "))
        assertTrue(failed, failed.contains("0 B"))
    }

    @Test
    fun `runTargetsDiffer flags a changed URL or method between the two runs`() {
        val base = run()
        assertFalse(runTargetsDiffer(base, run()))
        assertTrue(runTargetsDiffer(base, run(url = "https://httpbin.org/anything")))
        assertTrue(runTargetsDiffer(base, run(method = "POST")))
    }

    @Test
    fun `runFormatBytes matches the screen's B KB MB scale`() {
        assertEquals("512 B", runFormatBytes(512))
        assertEquals("1.5 KB", runFormatBytes(1536))
        assertEquals("2.0 MB", runFormatBytes(2L * 1024 * 1024))
    }

    @Test
    fun `two runs of the same request reach the identical, mixed and both-empty diff states`() {
        val identical = buildDiffModel("{\"ok\":true}", "{\"ok\":true}")
        assertEquals(DiffKind.IDENTICAL, identical.kind)

        val mixed = buildDiffModel(
            "{\n  \"count\": 1,\n  \"items\": [\"a\"]\n}",
            "{\n  \"count\": 2,\n  \"items\": [\"a\", \"b\"]\n}",
        )
        assertEquals(DiffKind.MIXED, mixed.kind)
        assertTrue(mixed.hasRenderableHunks)

        // e.g. two 204 No Content responses
        assertEquals(DiffKind.BOTH_EMPTY, buildDiffModel("", "").kind)
    }

    @Test
    fun `a binary body or two very long bodies fall into the guarded diff states`() {
        // A PNG fetched through API Testr lands in ApiResponseData.body as a String full of
        // control bytes / replacement chars -- the sheet must not try to line-diff it.
        val pngLike = "\u0089PNG\r\n\u001a\n\u0000\u0000\u0000\rIHDR\u0000\u0000\u0001\u0000"
        assertEquals(DiffKind.BINARY, buildDiffModel(pngLike, pngLike + "\u0000\u0001").kind)

        val big = (1..2100).joinToString("\n") { "\"row$it\": $it," }
        val bigger = (1..2100).joinToString("\n") { "\"row$it\": ${it + 1}," }
        assertEquals(DiffKind.TOO_LARGE, buildDiffModel(big, bigger).kind)
    }
}
