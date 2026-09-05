package com.example.interception.rules.stages

import android.os.Build
import com.example.interception.ChaosInjector
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.ChaosConfig
import com.example.interception.rules.RuleMetadataHeaders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * S4 task 30: [ChaosStage] -- the typed CHAOS slot. Request leg rolls only the fail die, response
 * leg rolls corrupt/delay, bounds are clamped, attribution lands on `x-requestr-chaos`, and a
 * seeded [Random] makes every outcome reproducible. (Byte parity with the legacy enforcer is in
 * `LegacyParityTest`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ChaosStageTest {

    private fun response(body: String = """{"ok":true}""", binary: ByteArray? = null) = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = 200,
        statusMessage = "OK",
        headers = mapOf("content-type" to if (binary != null) "application/octet-stream" else "application/json"),
        body = if (binary != null) HttpBodyDecoder.BINARY_BODY_PLACEHOLDER else body,
        isBodyBinary = binary != null,
        rawBodyBytes = binary,
    )

    /** A Random whose nextDouble draws are scripted; everything else is deterministic (seeded). */
    private class Scripted(vararg doubles: Double, seed: Int = 1) : Random() {
        private val queue = ArrayDeque(doubles.toList())
        private val fallback = Random(seed)
        override fun nextBits(bitCount: Int): Int = fallback.nextBits(bitCount)
        override fun nextDouble(): Double = queue.removeFirstOrNull() ?: fallback.nextDouble()
    }

    // ── config -> injector ──────────────────────────────────────────────────────────────

    @Test
    fun `injector config clamps rates to unit interval and delays to the ceiling`() {
        val cfg = ChaosStage.toInjectorConfig(ChaosConfig(failRate = 1.7, corruptBodyRate = -0.3, delayMinMs = -50, delayMaxMs = ChaosConfig.MAX_DELAY_MS + 99_999))
        assertEquals(1.0, cfg.failRate, 0.0)
        assertEquals(0.0, cfg.corruptBodyRate, 0.0)
        assertEquals(0L..ChaosConfig.MAX_DELAY_MS, cfg.delayMsRange)
        // an inverted range collapses to [min, min]
        assertEquals(400L..400L, ChaosStage.toInjectorConfig(ChaosConfig(delayMinMs = 400, delayMaxMs = 100)).delayMsRange)
    }

    // ── request leg ─────────────────────────────────────────────────────────────────────

    @Test
    fun `request leg rolls only the fail die`() {
        val cfg = ChaosConfig(failRate = 0.5, corruptBodyRate = 1.0, delayMinMs = 1_000, delayMaxMs = 1_000)
        // 0.2 < 0.5 -> fail
        val failed = ChaosStage.applyToRequest(cfg, "flaky", Scripted(0.2))
        assertTrue(failed is ChaosStage.Result.Fail)
        // 0.9 >= 0.5 -> pass through; corrupt/delay must NOT fire on the request leg even at 100 %
        assertEquals(ChaosStage.Result.PassThrough, ChaosStage.applyToRequest(cfg, "flaky", Scripted(0.9)))
    }

    @Test
    fun `request leg with zero fail rate never consults the fail die`() {
        val cfg = ChaosConfig(corruptBodyRate = 1.0, delayMinMs = 10, delayMaxMs = 10)
        assertEquals(ChaosStage.Result.PassThrough, ChaosStage.applyToRequest(cfg, "r", Scripted(0.0, 0.0)))
    }

    @Test
    fun `failure response carries the configured status, body and attribution`() {
        val cfg = ChaosConfig(failRate = 1.0, failStatusCode = 429, failStatusMessage = "Too Many", failBody = """{"err":"slow down"}""")
        val result = ChaosStage.applyToRequest(cfg, "rate-limit", Random(1)) as ChaosStage.Result.Fail
        val resp = result.failureResponse
        assertEquals(429, resp.statusCode)
        assertEquals("Too Many", resp.statusMessage)
        assertEquals("""{"err":"slow down"}""", resp.body)
        assertEquals("19", resp.headers["content-length"])
        assertEquals("application/json; charset=utf-8", resp.headers["content-type"])
        assertEquals("fail; rule=\"rate-limit\"", resp.headers[RuleMetadataHeaders.CHAOS])
        assertTrue(resp.bodyReplaced)
        assertFalse(resp.isRequest)

        val defaults = ChaosStage.failureResponse(ChaosConfig(), "d")
        assertEquals(503, defaults.statusCode)
        assertEquals("Service Unavailable", defaults.statusMessage)
        assertEquals(ChaosConfig.DEFAULT_FAIL_BODY, defaults.body)
    }

    // ── response leg ────────────────────────────────────────────────────────────────────

    @Test
    fun `response leg never fails even at fail rate 1`() {
        val cfg = ChaosConfig(failRate = 1.0)
        val resp = response()
        assertEquals(ChaosStage.Result.PassThrough, ChaosStage.applyToResponse(resp, cfg, "r", Random(3)))
        assertNull(resp.headers[RuleMetadataHeaders.CHAOS])
        assertFalse(resp.bodyReplaced)
    }

    @Test
    fun `response leg delay is sampled inside the range and stamped`() {
        val cfg = ChaosConfig(delayMinMs = 200, delayMaxMs = 800)
        val resp = response()
        val result = ChaosStage.applyToResponse(resp, cfg, "laggy", Random(7))
        assertTrue(result is ChaosStage.Result.Delay)
        val millis = (result as ChaosStage.Result.Delay).millis
        assertTrue(millis in 200L..800L)
        assertEquals("delay=${millis}ms; rule=\"laggy\"", resp.headers[RuleMetadataHeaders.CHAOS])
        assertFalse("a delay does not touch the body", resp.bodyReplaced)
        assertEquals("""{"ok":true}""", resp.body)
    }

    @Test
    fun `fixed delay range yields exactly that delay without consulting the RNG`() {
        val cfg = ChaosConfig(delayMinMs = 350, delayMaxMs = 350)
        val a = ChaosStage.applyToResponse(response(), cfg, "r", Random(1)) as ChaosStage.Result.Delay
        val b = ChaosStage.applyToResponse(response(), cfg, "r", Random(99)) as ChaosStage.Result.Delay
        assertEquals(350L, a.millis)
        assertEquals(350L, b.millis)
    }

    @Test
    fun `corrupt body wins over delay and flips text characters`() {
        val cfg = ChaosConfig(corruptBodyRate = 1.0, delayMinMs = 500, delayMaxMs = 500)
        val original = """{"ok":true,"payload":"abcdefghijklmnopqrstuvwxyz"}"""
        val resp = response(body = original)
        val random = Random(11)
        assertEquals(ChaosStage.Result.Applied, ChaosStage.applyToResponse(resp, cfg, "mangle", random))
        assertNotEquals(original, resp.body)
        assertEquals(original.length, resp.body.length)
        assertTrue(resp.bodyReplaced)
        assertNull(resp.replacedBodyBytes)
        assertEquals("corrupt-body; rule=\"mangle\"", resp.headers[RuleMetadataHeaders.CHAOS])
        // Same seed, same corruption -- reproducible.
        val again = response(body = original)
        ChaosStage.applyToResponse(again, cfg, "mangle", Random(11))
        assertEquals(resp.body, again.body)
        assertEquals(ChaosInjector.corrupt(original, Random(11).also { it.nextDouble() }), resp.body)
    }

    @Test
    fun `corrupt body on a binary message flips bytes byte-exact`() {
        val bytes = ByteArray(512) { (it * 7).toByte() }
        val resp = response(binary = bytes)
        assertEquals(ChaosStage.Result.Applied, ChaosStage.applyToResponse(resp, ChaosConfig(corruptBodyRate = 1.0), "bin", Random(5)))
        val corrupted = resp.replacedBodyBytes ?: throw AssertionError("binary corruption must set replacedBodyBytes")
        assertEquals(bytes.size, corrupted.size)
        assertFalse(corrupted.contentEquals(bytes))
        assertArrayEquals("original bytes are untouched", ByteArray(512) { (it * 7).toByte() }, bytes)
        assertTrue(resp.bodyReplaced)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, resp.body)
    }

    @Test
    fun `corrupting an empty body is recorded honestly`() {
        val resp = response(body = "")
        assertEquals(ChaosStage.Result.Applied, ChaosStage.applyToResponse(resp, ChaosConfig(corruptBodyRate = 1.0), "e", Random(2)))
        assertEquals("", resp.body)
        assertTrue(resp.bodyReplaced)
        assertEquals("corrupt-body; rule=\"e\"; body-empty=true", resp.headers[RuleMetadataHeaders.CHAOS])
    }

    @Test
    fun `an all-zero config passes everything through on both legs`() {
        val cfg = ChaosConfig()
        assertEquals(ChaosStage.Result.PassThrough, ChaosStage.applyToRequest(cfg, "z", Random(1)))
        val resp = response()
        assertEquals(ChaosStage.Result.PassThrough, ChaosStage.applyToResponse(resp, cfg, "z", Random(1)))
        assertNull(resp.headers[RuleMetadataHeaders.CHAOS])
    }

    @Test
    fun `joint probabilities -- fail first, then corrupt, then delay`() {
        // Scripted draws: P(fail)=0.3, P(corrupt)=0.5 given no fail.
        val cfg = ChaosConfig(failRate = 0.3, corruptBodyRate = 0.5, delayMinMs = 100, delayMaxMs = 100)
        // request: 0.29 -> fail
        assertTrue(ChaosStage.applyToRequest(cfg, "j", Scripted(0.29)) is ChaosStage.Result.Fail)
        // response: fail die is disabled, first draw 0.49 -> corrupt
        assertEquals(ChaosStage.Result.Applied, ChaosStage.applyToResponse(response(), cfg, "j", Scripted(0.49)))
        // response: 0.51 -> no corrupt -> fixed delay
        assertEquals(ChaosStage.Result.Delay(100), ChaosStage.applyToResponse(response(), cfg, "j", Scripted(0.51)))
    }
}
