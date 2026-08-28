package com.example.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §1.10/§1.11: proves the raw Rhino wrapper itself -- interpreted-mode eval,
 * deterministic timeout/instruction-limit enforcement, and basic success/failure reporting --
 * independent of the `pm.*` shim ([PmPrelude]) or either concrete bridge
 * ([ApiTestrScriptRunner], [com.example.interception.RuleEnforcer.applyScriptRewrite]).
 *
 * Uses an empty prelude (`""`) and a plain [Any] bridge throughout so a failure here can never
 * be blamed on the prelude/bridge layers -- those get their own dedicated tests.
 */
class JsScriptEngineTest {

    @Test
    fun `eval succeeds for trivial script`() {
        val result = JsScriptEngine.eval(script = "var x = 1 + 1;", prelude = "", bridge = Any())
        assertTrue(result.ok)
        assertNull(result.error)
    }

    @Test
    fun `eval reports a syntax error as a failure, not a crash`() {
        val result = JsScriptEngine.eval(script = "this is not valid js (((", prelude = "", bridge = Any())
        assertFalse(result.ok)
        assertTrue(result.error != null && result.error!!.isNotBlank())
    }

    @Test
    fun `eval reports a thrown runtime error as a failure`() {
        val result = JsScriptEngine.eval(script = "throw new Error('boom');", prelude = "", bridge = Any())
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("boom"))
    }

    @Test
    fun `eval can call a public method on the bridge via __bridge`() {
        class Bridge { var seen: String? = null; fun record(v: String) { seen = v } }
        val bridge = Bridge()
        val result = JsScriptEngine.eval(script = "__bridge.record('hi');", prelude = "", bridge = bridge)
        assertTrue(result.ok)
        assertEquals("hi", bridge.seen)
    }

    @Test
    fun `eval enforces the instruction budget deterministically`() {
        // A tight, allocation-free busy loop with no wall-clock-friendly yielding: this must be
        // caught by the instruction ceiling (not the timeout) well before DEFAULT_TIMEOUT_MS.
        val result = JsScriptEngine.eval(
            script = "var i = 0; while (true) { i = i + 1; }",
            prelude = "",
            bridge = Any(),
            timeoutMs = 60_000L,
            maxInstructions = 10_000L,
        )
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("instruction"))
    }

    @Test
    fun `eval enforces the wall-clock timeout deterministically`() {
        val result = JsScriptEngine.eval(
            script = "var i = 0; while (true) { i = i + 1; }",
            prelude = "",
            bridge = Any(),
            timeoutMs = 50L,
            maxInstructions = Long.MAX_VALUE,
        )
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("time"))
    }

    @Test
    fun `eval runs the prelude before the script, sharing one scope`() {
        val result = JsScriptEngine.eval(
            script = "if (typeof helper !== 'function') throw new Error('prelude not visible');",
            prelude = "function helper() { return 42; }",
            bridge = Any(),
        )
        assertTrue(result.ok)
    }

    @Test
    fun `eval survives runaway recursion without crashing the process`() {
        val result = JsScriptEngine.eval(
            script = "function boom() { return boom(); } boom();",
            prelude = "",
            bridge = Any(),
        )
        assertFalse(result.ok)
    }
}
