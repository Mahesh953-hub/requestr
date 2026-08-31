package com.example.interception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Master plan §3 (C2) — pure unit tests for [NetworkConditionInjector], no
 * Robolectric/Android dependency needed since every function here is pure and takes an
 * injectable [Random] plus (for [NetworkConditionInjector.apply]) an injectable `sleep`
 * lambda -- no test in this file ever performs a real [Thread.sleep].
 */
class NetworkConditionInjectorTest {

    // ---- NetworkCondition validation ----------------------------------------------------

    @Test
    fun `NONE is the fully-disabled all-zero default`() {
        val none = NetworkConditionInjector.NetworkCondition.NONE
        assertFalse(none.enabled)
        assertEquals(0, none.latencyMs)
        assertEquals(0, none.jitterMs)
        assertEquals(0, none.packetLossPercent)
    }

    @Test
    fun `negative latencyMs is rejected`() {
        try {
            NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `negative jitterMs is rejected`() {
        try {
            NetworkConditionInjector.NetworkCondition(enabled = true, jitterMs = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `packetLossPercent out of 0-100 range is rejected`() {
        try {
            NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 101)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- delayMillisFor -------------------------------------------------------------------

    @Test
    fun `delayMillisFor is zero when disabled, even with nonzero latency configured`() {
        val condition = NetworkConditionInjector.NetworkCondition(
            enabled = false, latencyMs = 500, jitterMs = 100,
        )
        assertEquals(0L, NetworkConditionInjector.delayMillisFor(condition, Random(1)))
    }

    @Test
    fun `delayMillisFor is zero when enabled but latency and jitter are both zero`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true)
        assertEquals(0L, NetworkConditionInjector.delayMillisFor(condition, Random(1)))
    }

    @Test
    fun `delayMillisFor returns flat latency when jitter is zero`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 250, jitterMs = 0)
        assertEquals(250L, NetworkConditionInjector.delayMillisFor(condition, Random(1)))
        assertEquals(250L, NetworkConditionInjector.delayMillisFor(condition, Random(999)))
    }

    @Test
    fun `delayMillisFor with jitter stays within latency plus-or-minus jitter and never negative`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 100, jitterMs = 30)
        repeat(200) { seed ->
            val delay = NetworkConditionInjector.delayMillisFor(condition, Random(seed))
            assertTrue("delay=$delay should be >= 0", delay >= 0L)
            assertTrue("delay=$delay should be <= 130", delay <= 130L)
        }
    }

    @Test
    fun `delayMillisFor is deterministic for a fixed seed`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 100, jitterMs = 30)
        val first = NetworkConditionInjector.delayMillisFor(condition, Random(42))
        val second = NetworkConditionInjector.delayMillisFor(condition, Random(42))
        assertEquals(first, second)
    }

    // ---- shouldDrop -------------------------------------------------------------------------

    @Test
    fun `shouldDrop is always false when disabled, and never consults random`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = false, packetLossPercent = 100)
        val random = NeverCallRandom()
        assertFalse(NetworkConditionInjector.shouldDrop(condition, random))
    }

    @Test
    fun `shouldDrop is always false at zero percent, and never consults random`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 0)
        val random = NeverCallRandom()
        assertFalse(NetworkConditionInjector.shouldDrop(condition, random))
    }

    @Test
    fun `shouldDrop is always true at 100 percent, and never consults random`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 100)
        val random = NeverCallRandom()
        assertTrue(NetworkConditionInjector.shouldDrop(condition, random))
    }

    @Test
    fun `shouldDrop respects a mid-range percentage across many seeds statistically`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 50)
        var drops = 0
        val trials = 2000
        repeat(trials) { seed ->
            if (NetworkConditionInjector.shouldDrop(condition, Random(seed))) drops++
        }
        val ratio = drops.toDouble() / trials
        assertTrue("expected roughly 50% drop ratio, got $ratio", ratio in 0.3..0.7)
    }

    // ---- apply --------------------------------------------------------------------------

    @Test
    fun `apply does nothing when disabled -- no sleep, no exception`() {
        val condition = NetworkConditionInjector.NetworkCondition(
            enabled = false, latencyMs = 500, packetLossPercent = 100,
        )
        var sleptFor: Long? = null
        NetworkConditionInjector.apply(condition, Random(1), sleep = { sleptFor = it })
        assertEquals(null, sleptFor)
    }

    @Test
    fun `apply sleeps for the computed delay before evaluating drop`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, latencyMs = 40, jitterMs = 0)
        var sleptFor: Long? = null
        NetworkConditionInjector.apply(condition, Random(1), sleep = { sleptFor = it })
        assertEquals(40L, sleptFor)
    }

    @Test
    fun `apply throws SimulatedPacketLossException when packet loss always fires`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 100)
        var sleepCalls = 0
        try {
            NetworkConditionInjector.apply(condition, Random(1), sleep = { sleepCalls++ })
            fail("expected SimulatedPacketLossException")
        } catch (e: NetworkConditionInjector.SimulatedPacketLossException) {
            // expected -- and it must be an IOException so existing catch-alls (runCatching in
            // HttpProxyShared.forwardRequest, dial-time exception propagation in
            // LazyUpstreamConnection.connect()) already handle it with zero new code.
            assertTrue(e is java.io.IOException)
        }
        // packetLossPercent=100 with no latency configured means the computed delay is zero, so
        // apply() never invokes sleep at all (it only sleeps when delay > 0).
        assertEquals(0, sleepCalls)
    }

    @Test
    fun `apply sleeps even for a doomed connection when latency is also configured`() {
        val condition = NetworkConditionInjector.NetworkCondition(
            enabled = true, latencyMs = 25, packetLossPercent = 100,
        )
        var sleptFor: Long? = null
        try {
            NetworkConditionInjector.apply(condition, Random(1), sleep = { sleptFor = it })
            fail("expected SimulatedPacketLossException")
        } catch (e: NetworkConditionInjector.SimulatedPacketLossException) {
            // expected
        }
        assertEquals(25L, sleptFor)
    }

    @Test
    fun `apply never throws when packet loss never fires`() {
        val condition = NetworkConditionInjector.NetworkCondition(enabled = true, packetLossPercent = 0)
        NetworkConditionInjector.apply(condition, Random(1), sleep = {})
        // no exception == pass
    }

    /** A [Random] whose every method fails the test if ever invoked. */
    private class NeverCallRandom : Random() {
        override fun nextBits(bitCount: Int): Int =
            throw AssertionError("random should not have been consulted")
    }
}
