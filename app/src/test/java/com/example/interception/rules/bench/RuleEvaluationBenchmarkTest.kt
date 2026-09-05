package com.example.interception.rules.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate G6: rule evaluation p95 < 1 ms against a 200-rule snapshot, 1,000 warm-up + 5,000 sampled
 * evaluations. The JVM number is the CI signal; `RuleEvaluationDeviceBenchmark` (androidTest)
 * runs the identical harness on a device. The bound here is deliberately the gate's own budget
 * -- on a loaded CI box a 4x margin below it is typical, so a failure means a real regression in
 * `CompiledMatcher` / `RulePipeline`, not noise.
 */
class RuleEvaluationBenchmarkTest {

    @Test
    fun `G6 - 200-rule snapshot evaluates under 1 ms at p95`() {
        val report = RuleSnapshotBenchmark.run()
        println("G6 rule evaluation: $report")
        assertEquals(RuleSnapshotBenchmark.DEFAULT_SAMPLED, report.stats.count)
        assertTrue("some flows must match rules (harness sanity): $report", report.matchedFlows > 1_000)
        assertTrue("p95 over budget: $report", report.withinG6)
    }

    @Test
    fun `the snapshot compiles all 200 rules across the stage mix`() {
        val snapshot = RuleSnapshotBenchmark.snapshot()
        assertEquals(200, snapshot.rules.size)
    }

    @Test
    fun `BenchmarkStats uses nearest-rank percentiles`() {
        val stats = BenchmarkStats(LongArray(100) { (it + 1).toLong() })
        assertEquals(100, stats.count)
        assertEquals(1L, stats.min)
        assertEquals(100L, stats.max)
        assertEquals(50L, stats.p50)
        assertEquals(95L, stats.p95)
        assertEquals(99L, stats.p99)
        assertEquals(100L, stats.percentile(100.0))
        assertEquals(1L, stats.percentile(0.5))
        assertEquals(50.5, stats.mean, 1e-9)
        assertEquals(0L, BenchmarkStats(LongArray(0)).p95)
    }
}
