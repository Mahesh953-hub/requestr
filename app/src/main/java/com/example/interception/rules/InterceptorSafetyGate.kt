package com.example.interception.rules

import android.content.Context
import com.example.data.local.InterceptorRuleDao
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.SettingsDataStore
import com.example.interception.breakpoint.BreakpointRegistry
import com.example.scripting.host.ScriptHostClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * S4 task 33 (D3): the abuse/safety controls in front of every mutating stage.
 *
 *  1. **Acknowledgement.** Until the one-time developer-mode acknowledgement is recorded
 *     (`SettingsDataStore.mutatingRulesAcknowledged`), no mutating rule can be enabled
 *     ([RuleValidation]) and, as a backstop, none acts on a live flow ([screen]).
 *  2. **Selected targets only.** When capture is scoped to `selectedPackages`, a flow attributed to
 *     a package outside that selection is observed but never mutated; the refusal is recorded in
 *     the audit trail as [FlowAuditTrail.Outcome.REFUSED_SAFETY] so the row explains itself.
 *  3. **Kill switch.** [disableAllMutatingRules] disables every rule in [RuleStage.MUTATING] in one
 *     statement, resumes every paused flow, and kills the script host. Observe / gateway /
 *     throttle / mirror rules are left alone.
 *
 * Fail-open vs fail-closed per stage (the declaration D3 asks for):
 *  - GATEWAY block/allow-only: fail-closed (a matching flow is refused; an unmatched one passes).
 *  - MIRROR, REWRITE, SCRIPT, THROTTLE, CHAOS, BREAKPOINT: fail-open -- an error, deadline or
 *    missing slot forwards the flow unmodified with a FAILED/SKIPPED record.
 *  - MOCK: fail-open to the origin only when the fixture cannot be built; otherwise it never dials.
 *  - BODY_CRYPTO: fail-closed -- an untransformable body is never forwarded (502 instead).
 *  - Script body mutation against a truncated body: fail-closed on the body, fail-open on the flow.
 *
 * [Policy] is a plain snapshot the service keeps current from settings flows; [screen] reads it
 * on the relay thread without touching DataStore.
 */
class InterceptorSafetyGate(
    initial: Policy = Policy.PERMISSIVE,
    /** `uid -> package names`; empty when unknown. [bind] swaps in `PackageManager.getPackagesForUid`. */
    @Volatile var packagesForUid: (Int) -> List<String> = { emptyList() },
    /** False makes [bind] a no-op so a test can pin a policy on an engine it starts. */
    private val followSettings: Boolean = true,
) {
    data class Policy(
        val acknowledged: Boolean,
        /** True when `appFilterMode == SELECTED_APPS`; the selection then bounds mutation. */
        val restrictToSelection: Boolean,
        val selectedPackages: Set<String>,
    ) {
        companion object {
            /** No acknowledgement recorded, no selection: mutation refused everywhere until the user acknowledges. */
            val DEFAULT = Policy(acknowledged = false, restrictToSelection = false, selectedPackages = emptySet())
            /** Tests and tools that do not exercise the gate. */
            val PERMISSIVE = Policy(acknowledged = true, restrictToSelection = false, selectedPackages = emptySet())
        }
    }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Refused(val reason: String) : Verdict
    }

    private val _policy = MutableStateFlow(initial)
    val policy: StateFlow<Policy> get() = _policy

    fun update(policy: Policy) { _policy.value = policy }

    /**
     * Keeps [policy] current from settings for as long as [scope] lives. `MitmProxyEngine.start`
     * calls this, so every production engine is bound by construction; the first emission
     * replaces whatever the gate held before.
     */
    fun bind(context: Context, scope: CoroutineScope): Job? {
        if (!followSettings) return null
        val app = context.applicationContext
        val pm = app.packageManager
        packagesForUid = { uid -> runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull() ?: emptyList() }
        val prefs = SettingsDataStore.get(app)
        return scope.launch {
            combine(prefs.mutatingRulesAcknowledged, prefs.appFilterMode, prefs.selectedPackages) { ack, mode, selected ->
                Policy(acknowledged = ack, restrictToSelection = mode == AppFilterMode.SELECTED_APPS, selectedPackages = selected)
            }.collect { update(it) }
        }
    }

    /**
     * Undoes [bind]: cancels the collector and returns to [Policy.PERMISSIVE]. Only an engine that
     * has just stopped calls this -- nothing relays while stopped, and the next [bind] replaces the
     * policy before the gateway accepts a flow. It keeps one test's settings from leaking into the
     * next through the process-wide [shared] gate.
     */
    fun unbind(binding: Job?) {
        binding?.cancel()
        if (followSettings) update(Policy.PERMISSIVE)
    }

    /** True when at least one enabled rule in [snapshot] belongs to a mutating stage. */
    fun hasMutatingRules(snapshot: RuleSnapshot): Boolean = snapshot.rules.any { it.stage in RuleStage.MUTATING }

    /** Would a mutating rule be allowed to act on this flow? Pure; safe on the relay thread. */
    fun allowMutation(appPackage: String? = null, clientUid: Int? = null): Verdict {
        val p = _policy.value
        if (!p.acknowledged) return Verdict.Refused("mutating rules not acknowledged (developer mode)")
        if (!p.restrictToSelection) return Verdict.Allowed
        val packages: List<String> = when {
            appPackage != null -> listOf(appPackage)
            clientUid != null -> packagesForUid(clientUid)
            else -> emptyList()
        }
        // Unattributed flows are allowed: on the VPN tier the selection is already enforced by
        // the tunnel itself, and refusing here would silently break every rule on API < 29.
        if (packages.isEmpty()) return Verdict.Allowed
        return if (packages.any { it in p.selectedPackages }) Verdict.Allowed
        else Verdict.Refused("package ${packages.first()} is outside the capture selection")
    }

    /**
     * Applies the gate to a decision: when mutation is refused, every mutating rule in [decision]
     * gets a [FlowAuditTrail.Outcome.REFUSED_SAFETY] record and an observe-only decision (gateway,
     * throttle and observe intact) is returned. Otherwise [decision] is returned unchanged.
     */
    fun screen(decision: RuleDecision, phase: FlowAuditTrail.Phase, trail: FlowAuditTrail?, appPackage: String? = null, clientUid: Int? = null): RuleDecision {
        val mutating = decision.mutatingRules
        if (mutating.isEmpty()) return decision
        val verdict = allowMutation(appPackage, clientUid)
        if (verdict is Verdict.Allowed) return decision
        val reason = (verdict as Verdict.Refused).reason
        if (trail != null) for (rule in mutating) trail.record(rule, phase, FlowAuditTrail.Outcome.REFUSED_SAFETY, reason)
        return decision.observeOnly()
    }

    /**
     * The kill switch: disables every mutating rule, resumes every paused flow, kills the script
     * host. Returns the number of rules disabled. Gateway / observe / throttle / mirror stay.
     */
    suspend fun disableAllMutatingRules(
        dao: InterceptorRuleDao,
        breakpoints: BreakpointRegistry = BreakpointRegistry.shared,
        scriptHost: ScriptHostClient? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val disabled = dao.disableStages(RuleStage.MUTATING.map { it.name }, nowMs)
        breakpoints.resumeAll()
        scriptHost?.killSwitch("all mutating rules disabled")
        return disabled
    }

    companion object {
        /**
         * Process-wide gate. Starts [Policy.PERMISSIVE] only because no traffic exists before an
         * engine starts, and `MitmProxyEngine.start` binds it to settings before accepting flows.
         */
        val shared: InterceptorSafetyGate by lazy { InterceptorSafetyGate(Policy.PERMISSIVE) }
    }
}
