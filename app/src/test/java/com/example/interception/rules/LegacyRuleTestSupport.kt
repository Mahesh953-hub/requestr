package com.example.interception.rules

import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * S4 task 26 test support: the engines no longer read `intercept_rules` (or any DAO) on the hot
 * path -- every decision comes from the immutable [RuleSnapshot] in [RuleSnapshotStore]. Engine
 * tests written against the legacy table keep seeding [LegacyInterceptRule] values exactly as
 * before through [legacyRuleDao] (S4 task 34: `intercept_rules` is gone, so the seed is converted
 * through the SAME [LegacyRuleConverter] the v23 migration uses and written to `interceptor_rules`)
 * and call [syncSnapshotFromLegacy] afterwards, which compiles the enabled typed rows into the
 * current snapshot. That keeps those tests honest about the v1 → v2 mapping too: a legacy rule
 * the converter cannot express would visibly fail to insert here.
 */
object LegacyRuleTestSupport {
    /** Converts the enabled legacy rules in [db] into a compiled snapshot and installs it as current. */
    fun syncSnapshotFromLegacy(db: RequestrDatabase): RuleSnapshot {
        val entities = runBlocking { db.interceptorRuleDao().getEnabledOrdered() }
        return install(entities, revision = 1L)
    }

    /** Compiles [legacy] rules (no database) into a snapshot and installs it as current. */
    fun installLegacy(legacy: List<LegacyInterceptRule>, revision: Long = 1L): RuleSnapshot {
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

/**
 * S4 task 34: a stand-in for the retired `InterceptRuleDao` so engine tests can keep seeding
 * rules in the flat v1 vocabulary. Every insert converts through [LegacyRuleConverter] and lands
 * in `interceptor_rules`; reads come back as typed [InterceptorRuleEntity] rows.
 */
class LegacyRuleTestDao(private val db: RequestrDatabase) {
    private val dao get() = db.interceptorRuleDao()

    /** Converts and inserts [legacy] after the current tail; throws if the converter cannot express it. */
    suspend fun insert(legacy: LegacyInterceptRule): Long {
        val rule = when (val result = LegacyRuleConverter.convert(legacy, sortOrder = dao.maxSortOrder() + 1)) {
            is LegacyRuleConverter.ConversionResult.Converted -> result.rule
            is LegacyRuleConverter.ConversionResult.Unconvertible -> throw IllegalArgumentException("legacy rule not convertible: ${result.reason}")
        }
        return dao.insert(InterceptorRuleEntity.fromDomain(rule.copy(legacyRuleId = if (legacy.id != 0L) legacy.id else null)))
    }

    fun observeAll(): Flow<List<InterceptorRuleEntity>> = dao.observeAllOrdered()
    suspend fun getAll(): List<InterceptorRuleEntity> = dao.getAllOrdered()
    suspend fun getEnabledRules(): List<InterceptorRuleEntity> = dao.getEnabledOrdered()
    suspend fun getById(id: Long): InterceptorRuleEntity? = dao.getById(id)
    suspend fun update(entity: InterceptorRuleEntity) = dao.update(entity)
    suspend fun delete(entity: InterceptorRuleEntity) = dao.delete(entity)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}

/** Test-only accessor mirroring the retired `db.interceptRuleDao()` call sites. */
fun RequestrDatabase.legacyRuleDao(): LegacyRuleTestDao = LegacyRuleTestDao(this)
