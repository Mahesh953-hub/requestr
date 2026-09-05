package com.example.interception.rules.stages

import com.example.interception.NetworkConditionInjector
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ThrottleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs

/**
 * S4 task 30 / gate G7: "throttle within ±20 % of the configured rate on a 10 MB download, per
 * preset". The bucket's clock and sleep are injectable, so the per-preset check runs on a virtual
 * timeline (a real GPRS download of 10 MB would take 27 minutes); one real-clock case proves the
 * timeline maps onto wall time, and [ThrottleRelayTest] covers the socket relay end to end.
 */
class ThrottleStageTest {
    private val rule = RuleRef(9L, "slow-lane", RuleStage.THROTTLE)

    /** A deterministic clock whose `sleep` simply advances it. */
    private class VirtualClock {
        var nowNanos = 1_000_000_000L
        val clock: () -> Long = { nowNanos }
        var sleeps = 0
        val sleep: (Long) -> Unit = { nanos -> nowNanos += nanos; sleeps++ }
    }

    private fun pump(out: OutputStream, totalBytes: Long, chunk: Int = 16 * 1024) {
        val buf = ByteArray(chunk)
        var remaining = totalBytes
        while (remaining > 0) {
            val n = minOf(remaining, chunk.toLong()).toInt()
            out.write(buf, 0, n)
            remaining -= n
        }
        out.flush()
    }

    private class CountingSink : OutputStream() {
        var bytes = 0L
        var flushes = 0
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
        override fun flush() { flushes++ }
    }

    // ── presets / resolve ───────────────────────────────────────────────────────────────

    @Test
    fun `presets are ordered from slowest to fastest and round-trip through a config`() {
        val keys = NetworkConditionPresets.ALL.map { it.key }
        assertEquals(listOf("gprs", "2g", "3g", "4g", "5g"), keys)
        assertTrue(NetworkConditionPresets.ALL.zipWithNext().all { (a, b) -> a.downlinkBps < b.downlinkBps && a.latencyMs >= b.latencyMs })
        val cfg = NetworkConditionPresets.UMTS_3G.toConfig()
        assertEquals("3g", cfg.presetKey)
        assertEquals(750_000L, cfg.downlinkBps)
        assertSame(NetworkConditionPresets.UMTS_3G, NetworkConditionPresets.byKey("3G"))
        assertNull(NetworkConditionPresets.byKey("dialup"))
        assertNull(NetworkConditionPresets.byKey(null))
    }

    @Test
    fun `resolve prefers the rule numbers, falls back to the preset only when they are all zero`() {
        val tweaked = ThrottleStage.resolve(ThrottleConfig(presetKey = "4g", downlinkBps = 1_000_000, latencyMs = 40))
        assertEquals(1_000_000L, tweaked.downlinkBps)
        assertEquals(0L, tweaked.uplinkBps)
        assertEquals(40, tweaked.latencyMs)
        assertEquals("4G (LTE)", tweaked.label)

        val onlyKey = ThrottleStage.resolve(ThrottleConfig(presetKey = "gprs"))
        assertEquals(50_000L, onlyKey.downlinkBps)
        assertEquals(500, onlyKey.latencyMs)
        assertEquals(1, onlyKey.lossPercent)

        val custom = ThrottleStage.resolve(ThrottleConfig(downlinkBps = 8_000, lossPercent = 150, latencyMs = -5))
        assertEquals("custom", custom.label)
        assertEquals(100, custom.lossPercent)
        assertEquals(0, custom.latencyMs)

        assertTrue(ThrottleStage.resolve(ThrottleConfig()).isNoop)
        assertTrue(ThrottleStage.resolve(ThrottleConfig(presetKey = "nope")).isNoop)
    }

    @Test
    fun `a no-op config yields no shaper and a skipped audit line`() {
        val trail = FlowAuditTrail()
        assertNull(ThrottleStage.shaperFor(rule, ThrottleConfig(), trail))
        val rec = trail.toRecords().single()
        assertEquals(FlowAuditTrail.Outcome.SKIPPED_PRECEDENCE, rec.outcome)
        assertEquals(FlowAuditTrail.Phase.CONNECT, rec.phase)
    }

    @Test
    fun `a real config yields a shaper and an APPLIED audit line describing it`() {
        val trail = FlowAuditTrail()
        val shaper = ThrottleStage.shaperFor(rule, NetworkConditionPresets.UMTS_3G.toConfig(), trail)
        assertNotNull(shaper)
        assertEquals("throttle 3G down=750kbps up=250kbps latency=100ms±20", trail.toRecords().single().detail)
        assertEquals(FlowAuditTrail.Outcome.APPLIED, trail.toRecords().single().outcome)
        assertEquals(750_000L / 8, shaper!!.downlink!!.rateBytesPerSecond)
        assertEquals(250_000L / 8, shaper.uplink!!.rateBytesPerSecond)
    }

    @Test
    fun `latency-only shape wraps nothing but overrides the dial condition`() {
        val shaper = ThrottleStage.shaperFor(rule, ThrottleConfig(latencyMs = 250, jitterMs = 50, lossPercent = 3))!!
        assertFalse(shaper.shape.shapesBandwidth)
        val sink = ByteArrayOutputStream()
        assertSame(sink, shaper.toClient(sink))
        assertSame(sink, shaper.toUpstream(sink))
        val global = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 10)
        val effective = shaper.networkCondition(global)
        assertEquals(NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 250, jitterMs = 50, packetLossPercent = 3), effective)
    }

    @Test
    fun `bandwidth-only shape keeps the global dial condition`() {
        val shaper = ThrottleStage.shaperFor(rule, ThrottleConfig(downlinkBps = 1_000_000))!!
        val global = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 10)
        assertSame(global, shaper.networkCondition(global))
        assertNull(shaper.uplink)
        assertNotNull(shaper.downlink)
    }

    @Test
    fun `global setting with bandwidth builds a settings shaper, without it none`() {
        assertNull(ThrottleStage.shaperFor(NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 50)))
        assertNull(ThrottleStage.shaperFor(NetworkConditionInjector.NetworkCondition(enabled = false, downlinkBps = 1_000)))
        val s = ThrottleStage.shaperFor(NetworkConditionInjector.NetworkCondition(enabled = true, downlinkBps = 800_000))!!
        assertEquals("settings", s.shape.label)
        assertNull(s.rule)
        assertEquals(100_000L, s.downlink!!.rateBytesPerSecond)
    }

    // ── token bucket ────────────────────────────────────────────────────────────────────

    @Test
    fun `bucket admits a burst up to capacity without sleeping, then paces at the rate`() {
        val vc = VirtualClock()
        val bucket = TokenBucket(rateBytesPerSecond = 10_000, capacityBytes = 1_000, clock = vc.clock, sleep = vc.sleep)
        assertEquals(1_000L, bucket.availableBytes)
        bucket.acquire(1_000)
        assertEquals(0, vc.sleeps)
        assertEquals(0L, bucket.availableBytes)
        // 500 more bytes at 10 kB/s = 50 ms of debt.
        bucket.acquire(500)
        assertEquals(1, vc.sleeps)
        assertEquals(1_000_000_000L + 50_000_000L, vc.nowNanos)
        assertEquals(0L, bucket.availableBytes)
        // Idle for 2 s: refills to capacity, never above.
        vc.nowNanos += 2_000_000_000L
        assertEquals(1_000L, bucket.availableBytes)
    }

    @Test
    fun `bucket slices a write larger than its capacity`() {
        val vc = VirtualClock()
        val bucket = TokenBucket(rateBytesPerSecond = 1_000, capacityBytes = 100, clock = vc.clock, sleep = vc.sleep)
        bucket.acquire(1_100) // first 100 free, then 1000 bytes of debt = 1 s
        assertEquals(1_000_000_000L, vc.nowNanos - 1_000_000_000L)
        assertTrue(vc.sleeps >= 10)
    }

    @Test
    fun `default capacity is about 100 ms of the rate, clamped`() {
        assertEquals(1024L, TokenBucket.defaultCapacity(6_250)) // GPRS: 625 B -> floor
        assertEquals(50_000L, TokenBucket.defaultCapacity(500_000)) // 4G
        assertEquals(256L * 1024, TokenBucket.defaultCapacity(12_500_000)) // 5G -> cap
    }

    @Test
    fun `throttled stream forwards every byte and flushes between slices`() {
        val vc = VirtualClock()
        val bucket = TokenBucket(rateBytesPerSecond = 1_000_000, capacityBytes = 4_096, clock = vc.clock, sleep = vc.sleep)
        val sink = CountingSink()
        val out = ThrottledOutputStream(sink, bucket)
        out.write(ByteArray(10_000))
        out.write(7)
        out.flush()
        assertEquals(10_001L, sink.bytes)
        assertTrue("intermediate flushes so the receiver sees a trickle", sink.flushes >= 3)
    }

    // ── G7: ±20 % per preset on a virtual clock ─────────────────────────────────────────

    @Test
    fun `G7 -- every preset moves a 10 MB download within 20 percent of its downlink rate`() {
        val tenMb = 10L * 1024 * 1024
        for (preset in NetworkConditionPresets.ALL) {
            val vc = VirtualClock()
            val shaper = ThrottleStage.shaperFor(rule, preset.toConfig(), clock = vc.clock, sleep = vc.sleep)!!
            val sink = CountingSink()
            val start = vc.nowNanos
            pump(shaper.toClient(sink), tenMb)
            val elapsedSeconds = (vc.nowNanos - start) / 1e9
            val expected = ThrottleStage.expectedSeconds(tenMb, preset.downlinkBps)
            val deviation = abs(elapsedSeconds - expected) / expected
            assertEquals(tenMb, sink.bytes)
            assertTrue(
                "${preset.label}: expected ${"%.1f".format(expected)}s, took ${"%.1f".format(elapsedSeconds)}s (${"%.1f".format(deviation * 100)}%)",
                deviation <= 0.20,
            )
        }
    }

    @Test
    fun `G7 -- uplink is paced independently of downlink`() {
        val vc = VirtualClock()
        val shaper = ThrottleStage.shaperFor(rule, NetworkConditionPresets.LTE_4G.toConfig(), clock = vc.clock, sleep = vc.sleep)!!
        val oneMb = 1L * 1024 * 1024
        val start = vc.nowNanos
        pump(shaper.toUpstream(CountingSink()), oneMb)
        val upSeconds = (vc.nowNanos - start) / 1e9
        val expectedUp = ThrottleStage.expectedSeconds(oneMb, NetworkConditionPresets.LTE_4G.uplinkBps)
        assertTrue("up: $upSeconds vs $expectedUp", abs(upSeconds - expectedUp) / expectedUp <= 0.20)
        // the downlink bucket is still full -- nothing shared between directions
        assertEquals(shaper.downlink!!.capacityBytes, shaper.downlink!!.availableBytes)
    }

    @Test
    fun `G7 -- real clock, 1 MB at 8 Mbps lands within 20 percent of one second`() {
        val oneMb = 1L * 1024 * 1024
        val shaper = ThrottleStage.shaperFor(rule, ThrottleConfig(downlinkBps = 8_388_608))!! // 1 MiB/s
        val sink = CountingSink()
        val start = System.nanoTime()
        pump(shaper.toClient(sink), oneMb, chunk = 32 * 1024)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(oneMb, sink.bytes)
        // First 100 ms worth is admitted as burst, so expected ≈ 0.9 s; the ±20 % band around 1 s covers it.
        assertTrue("took ${"%.3f".format(elapsed)}s", elapsed in 0.8..1.2)
    }

    @Test
    fun `describe and helpers`() {
        assertEquals(1L, ThrottleStage.bytesPerSecond(3))
        assertEquals(125_000L, ThrottleStage.bytesPerSecond(1_000_000))
        assertEquals("custom down=8kbps loss=5%", ThrottleStage.resolve(ThrottleConfig(downlinkBps = 8_000, lossPercent = 5)).describe())
        assertEquals(80.0, ThrottleStage.expectedSeconds(10_000, 1_000), 1e-9)
    }
}
