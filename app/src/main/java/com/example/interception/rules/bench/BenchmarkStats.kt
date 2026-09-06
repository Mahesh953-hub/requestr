package com.example.interception.rules.bench

/**
 * S4 task 32 / gate G6: the percentile helper the plan calls for (no `p95` harness existed in
 * `app/src/test`). Nanosecond samples in, nearest-rank percentiles out. Shared by the JVM
 * benchmark test and the on-device `RuleEvaluationDeviceBenchmark`.
 */
class BenchmarkStats(samples: LongArray) {
    private val sorted: LongArray = samples.copyOf().also { it.sort() }

    val count: Int get() = sorted.size
    val min: Long get() = if (sorted.isEmpty()) 0L else sorted.first()
    val max: Long get() = if (sorted.isEmpty()) 0L else sorted.last()
    val mean: Double get() = if (sorted.isEmpty()) 0.0 else sorted.sum().toDouble() / sorted.size

    /** Nearest-rank percentile, `p` in (0, 100]. */
    fun percentile(p: Double): Long {
        require(p > 0.0 && p <= 100.0) { "percentile out of range: $p" }
        if (sorted.isEmpty()) return 0L
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    val p50: Long get() = percentile(50.0)
    val p95: Long get() = percentile(95.0)
    val p99: Long get() = percentile(99.0)

    fun summary(unit: String = "ns"): String =
        "n=$count min=$min$unit p50=$p50$unit p95=$p95$unit p99=$p99$unit max=$max$unit mean=${"%.1f".format(mean)}$unit"

    companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        fun of(samples: Collection<Long>): BenchmarkStats = BenchmarkStats(samples.toLongArray())
    }
}
