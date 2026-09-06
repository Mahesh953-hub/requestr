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

    // ── S4 task 32: epilogue + lifecycle entry points ─────────────────────────────────────────

    @Test
    fun `eval runs the epilogue after the script in the same scope and returns its value`() {
        val result = JsScriptEngine.eval(
            script = "var marker = 'from-script';",
            prelude = "",
            bridge = Any(),
            epilogue = "marker + '!'",
        )
        assertTrue(result.ok)
        assertEquals("from-script!", result.value)
    }

    @Test
    fun `an epilogue that yields undefined leaves value null`() {
        val result = JsScriptEngine.eval(script = "1;", prelude = "", bridge = Any(), epilogue = "var y = 2;")
        assertTrue(result.ok)
        assertNull(result.value)
    }

    @Test
    fun `an epilogue is not run when the script itself failed`() {
        val result = JsScriptEngine.eval(script = "throw new Error('first');", prelude = "", bridge = Any(), epilogue = "'reached'")
        assertFalse(result.ok)
        assertNull(result.value)
        assertTrue(result.error!!.contains("first"))
    }

    @Test
    fun `PmPrelude EPILOGUE dispatches onRequest or onResponse by phase and reports ABORT for a null return`() {
        class Bridge {
            var phase = "onRequest"
            var stored: String? = null
            fun phaseName() = phase
            fun sharedJson() = "{\"seed\":\"1\"}"
            fun sharedPut(json: String) { stored = json }
        }
        val prelude = "var context = { shared: JSON.parse(__bridge.sharedJson()) }; var __phase = __bridge.phaseName(); var pm = { request: {u: 'req'}, response: {u: 'resp'} };"
        val bridge = Bridge()
        val ok = JsScriptEngine.eval(
            script = "var seen = []; function onRequest(c, r) { seen.push(r.u); c.shared.x = c.shared.seed + '2'; } function onResponse(c, r) { seen.push('WRONG'); }",
            prelude = prelude, bridge = bridge, epilogue = PmPrelude.EPILOGUE,
        )
        assertTrue(ok.error, ok.ok)
        assertEquals("OK", ok.value)
        assertTrue(bridge.stored!!.contains("\"x\":\"12\""))

        bridge.phase = "onResponse"
        val abort = JsScriptEngine.eval(
            script = "function onResponse(c, r) { if (r.u !== 'resp') throw new Error('wrong object'); return null; }",
            prelude = prelude, bridge = bridge, epilogue = PmPrelude.EPILOGUE,
        )
        assertTrue(abort.error, abort.ok)
        assertEquals("ABORT", abort.value)

        val noHook = JsScriptEngine.eval(script = "var z = 1;", prelude = prelude, bridge = bridge, epilogue = PmPrelude.EPILOGUE)
        assertTrue(noHook.ok)
        assertEquals("OK", noHook.value)
    }
}
