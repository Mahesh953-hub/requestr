package com.example.interception.rules

import com.example.data.local.InterceptRuleEntity
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.runBlocking

/**
 * S4 task 26 test support: the engines no longer read `intercept_rules` (or any DAO) on the hot
 * path -- every decision comes from the immutable [RuleSnapshot] in [RuleSnapshotStore]. Engine
 * tests written against the legacy table keep seeding [InterceptRuleEntity] rows exactly as
 * before and call [syncSnapshotFromLegacy] afterwards, which converts the enabled legacy rows
 * through the SAME [LegacyRuleConverter] the v24 migration uses and installs the compiled
 * snapshot. That keeps those tests honest about the v1 → v2 mapping too: a legacy rule the
 * converter cannot express would visibly stop matching here.
 */
object LegacyRuleTestSupport {
    /** Converts the enabled legacy rules in [db] into a compiled snapshot and installs it as current. */
    fun syncSnapshotFromLegacy(db: RequestrDatabase): RuleSnapshot {
        val legacy = runBlocking { db.interceptRuleDao().getEnabledRules() }
        return installLegacy(legacy)
    }

    /** Compiles [legacy] rules (no database) into a snapshot and installs it as current. */
    fun installLegacy(legacy: List<InterceptRuleEntity>, revision: Long = 1L): RuleSnapshot {
        val entities = legacy.mapIndexedNotNull { index, rule ->
            when (val result = LegacyRuleConverter.convert(rule, sortOrder = index)) {
                is LegacyRuleConverter.ConversionResult.Converted -> InterceptorRuleEntity.fromDomain(result.rule)
                is LegacyRuleConverter.ConversionResult.Unconvertible -> null
            }
        }
        return install(entities, revision)
    }

    /** Compiles typed [InterceptorRule]s into a snapshot and installs it as current. */
    fun installRules(rules: List<InterceptorRule>, revision: Long = 1L): RuleSnapshot =
        install(rules.map { InterceptorRuleEntity.fromDomain(it) }, revision)

    private fun install(entities: List<InterceptorRuleEntity>, revision: Long): RuleSnapshot {
        RuleSnapshotStore.resetForTest()
        val snapshot = RuleSnapshotCompiler.compile(entities, emptyList(), revision)
        RuleSnapshotStore.replaceForTest(snapshot)
        return snapshot
    }

    /** Back to the empty snapshot; call from `@After` so rules never leak between tests. */
    fun clear() {
        RuleSnapshotStore.resetForTest()
    }
}
