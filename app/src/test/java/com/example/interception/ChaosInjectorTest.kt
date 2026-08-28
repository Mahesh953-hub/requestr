package com.example.interception

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaosInjectorTest {

    @Test
    fun `zero config always passes through`() {
        repeat(20) {
            assertEquals(ChaosInjector.ChaosOutcome.PassThrough, ChaosInjector.decide(ChaosInjector.ChaosConfig()))
        }
    }

    @Test
    fun `failRate of 1 always fails`() {
        val config = ChaosInjector.ChaosConfig(failRate = 1.0)
        assertEquals(ChaosInjector.ChaosOutcome.Fail, ChaosInjector.decide(config))
    }

    @Test
    fun `corruptBodyRate of 1 always corrupts when failRate is 0`() {
        val config = ChaosInjector.ChaosConfig(corruptBodyRate = 1.0)
        assertEquals(ChaosInjector.ChaosOutcome.CorruptBody, ChaosInjector.decide(config))
    }

    @Test
    fun `fail takes precedence over corrupt`() {
        val config = ChaosInjector.ChaosConfig(failRate = 1.0, corruptBodyRate = 1.0)
        assertEquals(ChaosInjector.ChaosOutcome.Fail, ChaosInjector.decide(config))
    }

    @Test
    fun `fixed delay range returns that exact delay`() {
        val config = ChaosInjector.ChaosConfig(delayMsRange = 500L..500L)
        val outcome = ChaosInjector.decide(config)
        assertEquals(ChaosInjector.ChaosOutcome.Delay(500L), outcome)
    }

    @Test
    fun `delay range returns a value within bounds`() {
        val config = ChaosInjector.ChaosConfig(delayMsRange = 100L..200L)
        val outcome = ChaosInjector.decide(config, random = Random(42))
        assertTrue(outcome is ChaosInjector.ChaosOutcome.Delay)
        val millis = (outcome as ChaosInjector.ChaosOutcome.Delay).millis
        assertTrue(millis in 100L..200L)
    }

    @Test
    fun `invalid rates are rejected`() {
        assertTrue(runCatching { ChaosInjector.ChaosConfig(failRate = 1.5) }.isFailure)
        assertTrue(runCatching { ChaosInjector.ChaosConfig(corruptBodyRate = -0.1) }.isFailure)
        assertTrue(runCatching { ChaosInjector.ChaosConfig(delayMsRange = -5L..10L) }.isFailure)
    }

    @Test
    fun `corrupt mutates non-empty body`() {
        val body = "a".repeat(100)
        val corrupted = ChaosInjector.corrupt(body, random = Random(7))
        assertNotEquals(body, corrupted)
        assertEquals(body.length, corrupted.length)
    }

    @Test
    fun `corrupt leaves empty body untouched`() {
        assertEquals("", ChaosInjector.corrupt("", random = Random(1)))
    }
}
