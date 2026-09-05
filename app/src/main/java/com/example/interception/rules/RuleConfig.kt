package com.example.interception.rules

import com.example.interception.RuleEnforcer
import com.example.scripting.JsScriptEngine
import com.squareup.moshi.JsonClass

/**
 * S4 task 24 -- the typed per-stage configuration of an [InterceptorRule], persisted as
 * `interceptor_rules.configJson`. Exactly one variant per [RuleStage]; [stage] is derived, never
 * stored twice (the JSON envelope carries a `stage` discriminator written by [RuleConfigJson]).
 *
 * These are plain data classes decoded by Moshi codegen. Nothing here is validated beyond what a
 * `data class` `init` can express cheaply; the single validator with typed errors is
 * `RuleValidation` (task 25/33), and the limits it enforces are in `RuleLimits`. Defaults below are
 * the *legacy-parity* defaults -- the value the flat `replacementValue` path used when a key was
 * absent -- so a converted rule with an empty config behaves exactly as it did.
 */
sealed interface RuleConfig {
    val stage: RuleStage
}

// ── GATEWAY ────────────────────────────────────────────────────────────────────────────────────

/**
 * Access-control modes, in **precedence order** (task 27 resolves multiple matching gateway rules
 * by this order regardless of list position): `ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT`.
 *
 *  - [ALLOW_ONLY]: only flows matching some allow-only rule proceed; every other flow is blocked.
 *    The presence of any enabled allow-only rule flips the default from allow to deny.
 *  - [BYPASS]: pass through untouched and un-decrypted; wins over [FORCE_DECRYPT].
 *  - [BLOCK]: the existing `HTTP/1.1 403 Blocked by requestr` / CONNECT refusal.
 *  - [SUSPEND]: hold the connection open with no response until [GatewayConfig.suspendTimeoutMs], then close.
 *  - [FORCE_DECRYPT]: the legacy `FORCE_MITM` -- MITM this host even if it would otherwise be passed through.
 */
enum class GatewayMode { ALLOW_ONLY, BYPASS, BLOCK, SUSPEND, FORCE_DECRYPT }

@JsonClass(generateAdapter = true)
data class GatewayConfig(
    val mode: GatewayMode,
    /** [GatewayMode.SUSPEND] only: how long to hold the socket before closing it. */
    val suspendTimeoutMs: Long = DEFAULT_SUSPEND_TIMEOUT_MS,
    /**
     * [GatewayMode.BLOCK] only: also emit a capture row for the blocked flow. Legacy `BLOCK` never
     * persisted the blocked exchange on either engine, so the parity default is `false`.
     */
    val showBlockedInList: Boolean = false,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.GATEWAY

    companion object {
        const val DEFAULT_SUSPEND_TIMEOUT_MS: Long = 30_000L
    }
}

// ── MIRROR ─────────────────────────────────────────────────────────────────────────────────────

/** Which name a mirrored flow presents on one of the three independent axes (Host header, TLS SNI, leaf CN). */
enum class NamePolicy {
    /** Keep whatever the client sent / expected. */
    PRESERVE,
    /** Use the host the client CONNECTed to (the *original* target). */
    USE_CONNECT_ADDRESS,
    /** Use the mirror target's own name. */
    USE_MIRROR_ADDRESS,
}

@JsonClass(generateAdapter = true)
data class MirrorConfig(
    val targetHost: String,
    /** Null keeps the original port. */
    val targetPort: Int? = null,
    val hostPolicy: NamePolicy = NamePolicy.PRESERVE,
    val sniPolicy: NamePolicy = NamePolicy.USE_MIRROR_ADDRESS,
    val cnPolicy: NamePolicy = NamePolicy.PRESERVE,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.MIRROR
}

// ── REWRITE ────────────────────────────────────────────────────────────────────────────────────

/**
 * Where a replaced body comes from. A `file://` path is resolved through `MockFixtureLoader` at
 * apply time (byte-exact for binary fixtures), exactly as the legacy `bodyReplace` did; a [Text]
 * value and a [File] path are both passed through `RuleTemplateExpander` first so `{{var}}`
 * placeholders keep working in either.
 */
sealed interface BodySource {
    data object Empty : BodySource
    @JsonClass(generateAdapter = true)
    data class Text(val value: String) : BodySource
    @JsonClass(generateAdapter = true)
    data class File(val path: String) : BodySource

    companion object {
        const val FILE_SCHEME = "file://"

        /** The legacy `bodyReplace` contract: a `file://` value is a fixture reference, anything else literal text. */
        fun fromLegacyValue(value: String): BodySource = when {
            value.isEmpty() -> Empty
            value.startsWith(FILE_SCHEME) -> File(value)
            else -> Text(value)
        }
    }
}

/**
 * One rewrite step. Applied in list order by `RewriteStage` (task 29); a step never sees the
 * message as it was before an earlier step ran.
 */
sealed interface Modification {
    /** Set (or add) a header. Case-insensitive name match on an existing header keeps that header's casing. */
    @JsonClass(generateAdapter = true)
    data class SetHeader(val name: String, val value: String) : Modification

    /** Remove every header with this name (case-insensitive). */
    @JsonClass(generateAdapter = true)
    data class RemoveHeader(val name: String) : Modification

    /** Regex-replace the value of [name]; `$1` / `${name}` group references are honoured. */
    @JsonClass(generateAdapter = true)
    data class RegexHeader(val name: String, val pattern: String, val replacement: String) : Modification

    @JsonClass(generateAdapter = true)
    data class SetQueryParam(val name: String, val value: String) : Modification

    @JsonClass(generateAdapter = true)
    data class RemoveQueryParam(val name: String) : Modification

    /** Regex-replace over a text body. Refused (recorded `FAILED`) on a binary body rather than corrupting bytes. */
    @JsonClass(generateAdapter = true)
    data class RegexBody(val pattern: String, val replacement: String) : Modification

    /** Whole-body replacement -- the legacy `bodyReplace`. */
    @JsonClass(generateAdapter = true)
    data class ReplaceBody(val body: BodySource) : Modification

    /** Response only: the legacy `statusCode` key. [message] null keeps the existing reason phrase. */
    @JsonClass(generateAdapter = true)
    data class SetStatus(val code: Int, val message: String? = null) : Modification
}

/**
 * A redirection. Legacy `REDIRECT` stored the bare target URL as `replacementValue`, overwrote
 * `request.url`, re-derived `request.host` from it and rewrote the `Host` header -- that is
 * `preserveHost = false, directoryMatch = false`.
 *
 * @property preserveHost keep the original `Host` header instead of the target's host.
 * @property directoryMatch treat the matcher's URL match as a path prefix and map it onto the
 *   target's path prefix, keeping the remainder; false replaces the whole URL.
 */
@JsonClass(generateAdapter = true)
data class RedirectSpec(
    val targetUrl: String,
    val preserveHost: Boolean = false,
    val directoryMatch: Boolean = false,
)

/** Build a whole request or response from status / headers / body. Absent fields keep the original's value. */
@JsonClass(generateAdapter = true)
data class MessageReplacement(
    val statusCode: Int? = null,
    val statusMessage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: BodySource = BodySource.Empty,
)

@JsonClass(generateAdapter = true)
data class RewriteConfig(
    val redirection: RedirectSpec? = null,
    val replaceRequest: MessageReplacement? = null,
    val replaceResponse: MessageReplacement? = null,
    val requestModifications: List<Modification> = emptyList(),
    val responseModifications: List<Modification> = emptyList(),
    /** The legacy `variables` map seeding `RuleTemplateExpander` for `{{var}}` placeholders. */
    val variables: Map<String, String> = emptyMap(),
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.REWRITE

    val isNoOp: Boolean
        get() = redirection == null && replaceRequest == null && replaceResponse == null &&
            requestModifications.isEmpty() && responseModifications.isEmpty()
}

// ── BREAKPOINT ─────────────────────────────────────────────────────────────────────────────────

enum class BreakpointPhase { REQUEST, RESPONSE, BOTH }

@JsonClass(generateAdapter = true)
data class BreakpointConfig(
    val phase: BreakpointPhase = BreakpointPhase.BOTH,
    /** Mandatory auto-resume; the hard ceiling is enforced by `RuleLimits` (task 33). */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.BREAKPOINT

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

// ── SCRIPT ─────────────────────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class ScriptConfig(
    /** JavaScript source. Blank is a no-op (legacy `SCRIPT_REWRITE` with a blank `script` passed the message through). */
    val source: String,
    /** Per-call wall-clock deadline; ceiling `RuleLimits.MAX_SCRIPT_TIMEOUT_MS` (10 s). */
    val timeoutMs: Long = JsScriptEngine.DEFAULT_TIMEOUT_MS,
    val maxInstructions: Long = JsScriptEngine.DEFAULT_MAX_INSTRUCTIONS,
    /** Seeds `pm.environment` / `pm.variables` -- the rule's only variable source besides process-wide globals. */
    val variables: Map<String, String> = emptyMap(),
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.SCRIPT
}

// ── MOCK ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Legacy-parity defaults: `200 OK`, empty body, no headers. Content-Type auto-detection (JSON /
 * octet-stream / text) and the always-recomputed Content-Length are behaviour of `MockStage`, not
 * data here. [headers] keys are stored lowercased (the legacy path lowercased them on parse).
 */
@JsonClass(generateAdapter = true)
data class MockConfig(
    val statusCode: Int = 200,
    val statusMessage: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val body: BodySource = BodySource.Empty,
    val variables: Map<String, String> = emptyMap(),
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.MOCK
}

// ── BODY_CRYPTO ────────────────────────────────────────────────────────────────────────────────

enum class CryptoDirection { ENCRYPT, DECRYPT }

/** Which side(s) of the exchange a body-crypto rule transforms. Legacy: anything unrecognised meant BOTH. */
enum class CryptoScope { REQUEST, RESPONSE, BOTH }

@JsonClass(generateAdapter = true)
data class BodyCryptoConfig(
    val direction: CryptoDirection,
    val scope: CryptoScope = CryptoScope.BOTH,
    /** AndroidKeyStore alias. Blank is a configuration error surfaced fail-closed (never silently skipped). */
    val keyAlias: String,
    val algorithm: String = DEFAULT_ALGORITHM,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.BODY_CRYPTO

    companion object {
        const val DEFAULT_ALGORITHM = "AES/GCM/NoPadding"
    }
}

// ── THROTTLE ───────────────────────────────────────────────────────────────────────────────────

/**
 * Bandwidth shaping plus the existing latency / jitter / loss model. `0` bps means unlimited on
 * that direction. [presetKey] names a `NetworkConditionPresets` entry the values were copied from
 * (informational -- the numbers below are authoritative).
 */
@JsonClass(generateAdapter = true)
data class ThrottleConfig(
    val presetKey: String? = null,
    val downlinkBps: Long = 0L,
    val uplinkBps: Long = 0L,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val lossPercent: Int = 0,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.THROTTLE
}

// ── CHAOS ──────────────────────────────────────────────────────────────────────────────────────

/**
 * Typed form of the legacy CHAOS `replacementValue`. The joint-probability semantics
 * (P(fail)=f, P(corrupt)=(1-f)*c, P(delay)=(1-f)*(1-c)) live in `ChaosInjector` and are unchanged.
 * Delay bounds are clamped to `[0, RuleEnforcer.MAX_CHAOS_DELAY_MS]` by the converter and validator.
 */
@JsonClass(generateAdapter = true)
data class ChaosConfig(
    val failRate: Double = 0.0,
    val corruptBodyRate: Double = 0.0,
    val delayMinMs: Long = 0L,
    val delayMaxMs: Long = 0L,
    val failStatusCode: Int = DEFAULT_FAIL_STATUS_CODE,
    val failStatusMessage: String = DEFAULT_FAIL_STATUS_MESSAGE,
    val failBody: String = DEFAULT_FAIL_BODY,
) : RuleConfig {
    override val stage: RuleStage get() = RuleStage.CHAOS

    val delayRange: LongRange get() = delayMinMs..delayMaxMs

    companion object {
        const val DEFAULT_FAIL_STATUS_CODE = 503
        const val DEFAULT_FAIL_STATUS_MESSAGE = "Service Unavailable"
        const val DEFAULT_FAIL_BODY = "{\"error\":\"chaos-injected failure\"}"
        const val MAX_DELAY_MS: Long = RuleLimits.MAX_CHAOS_DELAY_MS
    }
}

// ── OBSERVE ────────────────────────────────────────────────────────────────────────────────────

/** Capture-only. Has no fields; serialised as `{"stage":"OBSERVE"}`. */
data object ObserveConfig : RuleConfig {
    override val stage: RuleStage get() = RuleStage.OBSERVE
}
