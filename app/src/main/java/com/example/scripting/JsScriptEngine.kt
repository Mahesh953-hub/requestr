package com.example.scripting

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Real, embeddable ECMAScript execution for requestr, backing both ApiTestr's pre/post-request
 * scripts ([com.example.scripting.ApiTestrScriptRunner]) and [com.example.interception.RuleEnforcer]'s
 * live `SCRIPT_REWRITE` rule action. Wraps Mozilla Rhino (`org.mozilla:rhino`, see the `rhino`
 * version-catalog entry) -- a pure-Java engine with no JNI/Truffle/GraalVM dependency, actively
 * maintained at github.com/mozilla/rhino.
 *
 * This is a real interpreter, not a keyword-matching stand-in: [eval] compiles and executes
 * genuine JavaScript source (the `pm.*` shim in [PmPrelude] plus whatever the user wrote) via
 * Rhino's actual parser/interpreter loop.
 *
 * ## Why interpreted mode is mandatory on Android
 * Rhino's default "compiled" mode (`optimizationLevel >= 0`) generates real JVM `.class`
 * bytecode at runtime via an internal codegen pass and loads it with a custom `ClassLoader`.
 * ART cannot load that bytecode format (it expects DEX), so this throws
 * `ClassFormatError`/`VerifyError`-shaped failures on-device despite working fine on a JVM unit
 * test host. [eval] unconditionally forces `Context.optimizationLevel = -1` (pure tree-walking
 * interpreter) for exactly this reason -- this is *not* an optional tuning knob here, it is the
 * only mode Rhino supports on ART.
 *
 * ## Deterministic limits
 * A script must not be able to hang a request pipeline (ApiTestr's Send, or the live MITM
 * decrypt path once `SCRIPT_REWRITE` is wired in). Rhino's [Context.setInstructionObserverThreshold]
 * calls back into [LimitedContext.observeInstructionCount] every N bytecode-interpreter
 * instructions; that callback enforces two independent, deterministic budgets:
 *  - a wall-clock deadline (a real timeout, not merely an instruction cap, so `while(true){}`
 *    with no allocations still gets killed)
 *  - a total instruction count ceiling (so a script that sleeps/`Date.now()`-polls to dodge the
 *    clock, or a host running unusually slowly, still terminates deterministically)
 * Both throw a plain [RuntimeException] subtype from inside the interpreter loop, which Rhino
 * propagates out of [Context.evaluateString] like any other script exception -- [eval] catches
 * it and reports it the same way as a syntax/runtime error.
 */
object JsScriptEngine {

    /** Default wall-clock budget for one pre-script, post-script, or rule-rewrite script run. */
    const val DEFAULT_TIMEOUT_MS: Long = 3_000L

    /** Default total Rhino bytecode-interpreter instruction ceiling for one script run. */
    const val DEFAULT_MAX_INSTRUCTIONS: Long = 5_000_000L

    /** How many interpreter instructions elapse between [LimitedContext.observeInstructionCount] callbacks. */
    private const val INSTRUCTION_OBSERVER_THRESHOLD = 2_000

    /** Thrown from inside the interpreter loop when a script exceeds its wall-clock budget. */
    class ScriptTimeoutException(message: String) : RuntimeException(message)

    /** Thrown from inside the interpreter loop when a script exceeds its instruction budget. */
    class ScriptInstructionLimitException(message: String) : RuntimeException(message)

    /** Outcome of one [eval] call. [error] is a human-readable message; null means success. */
    data class EvalResult(val ok: Boolean, val error: String?)

    private class LimitedContext(factory: ContextFactory) : Context(factory) {
        var deadlineMs: Long = Long.MAX_VALUE
        var maxInstructions: Long = Long.MAX_VALUE
        var instructionsSeen: Long = 0

        override fun observeInstructionCount(instructionCount: Int) {
            instructionsSeen += instructionCount
            if (System.currentTimeMillis() > deadlineMs) {
                throw ScriptTimeoutException("Script exceeded its execution time budget")
            }
            if (instructionsSeen > maxInstructions) {
                throw ScriptInstructionLimitException("Script exceeded its instruction budget")
            }
        }
    }

    private class LimitedContextFactory : ContextFactory() {
        override fun makeContext(): Context = LimitedContext(this)
    }

    /**
     * Evaluates [prelude] (the `pm.*` shim, see [PmPrelude]) followed by [script] in one fresh
     * Rhino scope, with [bridge] exposed to that scope as the global `__bridge` object that the
     * prelude's JS calls into for every real side effect (variable read/write, request/response
     * field access, test recording, console capture).
     *
     * [bridge] is any plain Kotlin/Java object -- Rhino's LiveConnect layer (`Context.javaToJS`)
     * wraps it automatically and exposes its public methods to JS by reflection, so no
     * `Scriptable`/`ScriptableObject` boilerplate is needed on the Kotlin side.
     */
    fun eval(
        script: String,
        prelude: String,
        bridge: Any,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxInstructions: Long = DEFAULT_MAX_INSTRUCTIONS,
    ): EvalResult {
        val factory = LimitedContextFactory()
        val cx = factory.enterContext() as LimitedContext
        return try {
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
            cx.deadlineMs = System.currentTimeMillis() + timeoutMs
            cx.maxInstructions = maxInstructions
            // Rhino gotcha: WrapFactory defaults javaPrimitiveWrap=true, which wraps every
            // String/Number/Boolean a __bridge method returns (e.g. pm.request.url,
            // pm.environment.get(...), pm.response.body) into a NativeJavaObject instead of a
            // native JS primitive -- so `typeof` reports "object" and `pm.expect(x).to.equal(...)`
            // (which uses `===`) always fails even when the values are textually identical.
            // Disabling it makes every __bridge return value a real JS string/number/boolean, as
            // PmPrelude's assertion chain and every `{{var}}`-templated setter assume.
            cx.wrapFactory.isJavaPrimitiveWrap = false

            val scope: Scriptable = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "__bridge", Context.javaToJS(bridge, scope))
            cx.evaluateString(scope, prelude, "pm-prelude.js", 1, null)
            cx.evaluateString(scope, script, "user-script.js", 1, null)
            EvalResult(ok = true, error = null)
        } catch (e: ScriptTimeoutException) {
            EvalResult(ok = false, error = e.message)
        } catch (e: ScriptInstructionLimitException) {
            EvalResult(ok = false, error = e.message)
        } catch (e: RhinoException) {
            EvalResult(ok = false, error = e.details() ?: e.message ?: "Script error")
        } catch (e: StackOverflowError) {
            EvalResult(ok = false, error = "Script recursed too deeply")
        } catch (e: OutOfMemoryError) {
            // Rhino's tree-walking interpreter recurses in Java for nested JS function calls,
            // allocating a heap-based CallFrame per call rather than only consuming JVM stack
            // frames. Unbounded recursion can therefore exhaust the heap before it ever trips a
            // StackOverflowError, so a runaway script must not be allowed to bring down the host
            // process via an uncaught OutOfMemoryError. The frames involved are short-lived
            // (per-call locals), so it's safe to catch and let them be reclaimed here.
            EvalResult(ok = false, error = "Script exceeded its memory budget")
        } catch (e: Exception) {
            EvalResult(ok = false, error = e.message ?: e.toString())
        } finally {
            Context.exit()
        }
    }
}
