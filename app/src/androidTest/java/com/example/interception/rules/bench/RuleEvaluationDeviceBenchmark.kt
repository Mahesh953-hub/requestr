package com.example.interception.rules.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate G6 on device: the same [RuleSnapshotBenchmark] harness as the JVM test, run on ART.
 * Prints the percentile summary to logcat (`G6Benchmark`) so a CI device run can archive it.
 */
@RunWith(AndroidJUnit4::class)
class RuleEvaluationDeviceBenchmark {

    @Test
    fun ruleEvaluationP95UnderOneMillisecond() {
        val report = RuleSnapshotBenchmark.run()
        Log.i("G6Benchmark", report.toString())
        assertTrue("G6 p95 over budget on device: $report", report.withinG6)
    }
}
