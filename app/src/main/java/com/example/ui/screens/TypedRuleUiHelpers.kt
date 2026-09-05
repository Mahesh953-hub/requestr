package com.example.ui.screens

import com.example.data.local.InterceptorRuleEntity
import com.example.interception.rules.BodyCryptoConfig
import com.example.interception.rules.BodySource
import com.example.interception.rules.MockConfig
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.Modification
import com.example.interception.rules.RuleConfigJson
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleStage
import com.example.interception.rules.RuleValidation

/**
 * Pure, Compose-free helpers for rendering a typed [InterceptorRuleEntity] row in the rules
 * sheet (S4 task 34 -- the sheet lists `interceptor_rules` now that `intercept_rules` is gone).
 * The fully typed per-stage editors are a later task (rule-engine sub-plan task 16); until then
 * the sheet's quick-add form stays flat and converts through LegacyRuleConverter, and these
 * helpers describe the typed rows it produces.
 */

/** Human label for a rule's stage, as shown in the row's badge. */
fun ruleStageLabel(stage: RuleStage): String = when (stage) {
    RuleStage.GATEWAY -> "Gateway"
    RuleStage.MIRROR -> "Mirror"
    RuleStage.REWRITE -> "Rewrite"
    RuleStage.BREAKPOINT -> "Breakpoint"
    RuleStage.SCRIPT -> "Script"
    RuleStage.MOCK -> "Mock"
    RuleStage.BODY_CRYPTO -> "Body crypto"
    RuleStage.THROTTLE -> "Throttle"
    RuleStage.CHAOS -> "Chaos"
    RuleStage.OBSERVE -> "Observe"
}

/** Badge colour per stage; mutating stages are warm, observe-only is neutral. */
fun ruleStageColorHex(stage: RuleStage): Long = when (stage) {
    RuleStage.GATEWAY -> 0xFFE53935
    RuleStage.MIRROR -> 0xFF8E24AA
    RuleStage.REWRITE -> 0xFFFB8C00
    RuleStage.BREAKPOINT -> 0xFFD81B60
    RuleStage.SCRIPT -> 0xFF3949AB
    RuleStage.MOCK -> 0xFF00897B
    RuleStage.BODY_CRYPTO -> 0xFF6D4C41
    RuleStage.THROTTLE -> 0xFF039BE5
    RuleStage.CHAOS -> 0xFFC62828
    RuleStage.OBSERVE -> 0xFF43A047
}

/**
 * One-line description of what a matcher selects: host globs, then the URL regex, then the
 * remaining constraint kinds by name. An unconstrained matcher reads "any request".
 */
fun ruleMatcherSummary(matcher: RuleMatcherSpec): String {
    if (matcher.isUnconstrained) return "any request"
    val parts = ArrayList<String>()
    if (matcher.hostGlobs.isNotEmpty()) parts += matcher.hostGlobs.joinToString(", ")
    matcher.urlRegex?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (matcher.methods.isNotEmpty()) parts += matcher.methods.joinToString("/")
    matcher.portRange?.let { parts += if (it.first == it.last) "port ${it.first}" else "ports ${it.first}-${it.last}" }
    if (matcher.ipCidrs.isNotEmpty()) parts += matcher.ipCidrs.joinToString(", ")
    if (matcher.headerMatchers.isNotEmpty()) parts += "${matcher.headerMatchers.size} header match(es)"
    if (matcher.appPackages.isNotEmpty()) parts += matcher.appPackages.joinToString(", ")
    return parts.joinToString("  ·  ")
}

/** Matcher summary straight from the persisted row (its `matcherJson`). */
fun ruleMatcherSummary(entity: InterceptorRuleEntity): String =
    ruleMatcherSummary(RuleConfigJson.decodeMatcher(entity.matcherJson))

/**
 * Secondary detail line for a typed row -- the crypto key/scope for BODY_CRYPTO rules, the
 * status code for MOCK rules -- or null when the stage badge says it all.
 */
fun ruleConfigDetail(entity: InterceptorRuleEntity): String? =
    when (val config = RuleConfigJson.decodeConfig(entity.configJson)) {
        is BodyCryptoConfig ->
            "${config.direction.name.lowercase()} · key: ${config.keyAlias.ifBlank { "(none set)" }} · scope: ${config.scope.name}"
        is MockConfig -> "${config.statusCode} ${config.statusMessage}".trim()
        else -> null
    }

/** True when the rule's body (mock or rewrite replacement) is served from a file fixture. */
fun ruleUsesFileFixture(entity: InterceptorRuleEntity): Boolean =
    when (val config = RuleConfigJson.decodeConfig(entity.configJson)) {
        is MockConfig -> config.body is BodySource.File
        is RewriteConfig ->
            (config.requestModifications + config.responseModifications).any { it is Modification.ReplaceBody && it.body is BodySource.File } ||
                config.replaceRequest?.body is BodySource.File || config.replaceResponse?.body is BodySource.File
        else -> false
    }

/**
 * M4 carried over to the typed table: a saved rule that [RuleValidation] refuses today (blank
 * name, empty rewrite, unusable regex, ...) is still listed but flagged with the exact reason it
 * does nothing. Null when the rule is well-formed.
 */
fun savedTypedRuleProblem(entity: InterceptorRuleEntity): String? {
    val problems = try {
        RuleValidation.validate(entity.toDomain(), enabledRules = 0, acknowledged = true)
    } catch (e: Exception) {
        return "Stored rule config could not be decoded (${e.message})."
    }
    if (problems.isEmpty()) return null
    return problems.joinToString(" ") { p -> p.message.trimEnd('.') + "." }
}
