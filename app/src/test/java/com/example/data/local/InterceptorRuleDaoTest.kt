package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.interception.rules.ChaosConfig
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MockConfig
import com.example.interception.rules.ObserveConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleFolder
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S4 task 24 -- the v23 DAOs against an in-memory Room build of the real schema: ordering is
 * explicit (`sortOrder ASC, id ASC`), folder queries, reorder/enable helpers, the unknown-stage
 * converter contract, and the replay FK cascade.
 */
@RunWith(RobolectricTestRunner::class)
class InterceptorRuleDaoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var dao: InterceptorRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        dao = db.interceptorRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rule(
        name: String,
        config: RuleConfig,
        sortOrder: Int,
        enabled: Boolean = true,
        folderId: Long? = null,
        matcher: RuleMatcherSpec = RuleMatcherSpec(hostGlobs = listOf("*$name*")),
    ) = InterceptorRuleEntity.fromDomain(
        InterceptorRule(name = name, stage = config.stage, enabled = enabled, sortOrder = sortOrder, folderId = folderId, matcher = matcher, config = config, createdAt = 1, updatedAt = 1),
    )

    @Test
    fun `ordering is by sortOrder then id regardless of insertion order`() = runBlocking {
        val c = dao.insert(rule("c", ObserveConfig, sortOrder = 5))
        val a = dao.insert(rule("a", ObserveConfig, sortOrder = 0))
        val b1 = dao.insert(rule("b1", ObserveConfig, sortOrder = 2))
        val b2 = dao.insert(rule("b2", ObserveConfig, sortOrder = 2))
        assertEquals(listOf(a, b1, b2, c), dao.getAllOrdered().map { it.id })
        assertEquals(listOf(a, b1, b2, c), dao.observeAllOrdered().first().map { it.id })
        assertEquals(5, dao.maxSortOrder())
    }

    @Test
    fun `enabled queries filter and stay ordered`() = runBlocking {
        val on1 = dao.insert(rule("on1", GatewayConfig(GatewayMode.BLOCK), sortOrder = 3))
        dao.insert(rule("off", MockConfig(), sortOrder = 1, enabled = false))
        val on2 = dao.insert(rule("on2", ChaosConfig(failRate = 0.1), sortOrder = 2))
        assertEquals(listOf(on2, on1), dao.getEnabledOrdered().map { it.id })
        assertEquals(listOf(on2, on1), dao.observeEnabledOrdered().first().map { it.id })
        assertEquals(2, dao.countEnabled())
        assertEquals(3, dao.count())

        dao.setEnabled(on1, false, updatedAt = 99)
        assertEquals(listOf(on2), dao.getEnabledOrdered().map { it.id })
        assertEquals(99L, dao.getById(on1)!!.updatedAt)
        assertFalse(dao.getById(on1)!!.enabled)
    }

    @Test
    fun `reorderAll rewrites sortOrder to list position`() = runBlocking {
        val a = dao.insert(rule("a", ObserveConfig, sortOrder = 0))
        val b = dao.insert(rule("b", ObserveConfig, sortOrder = 1))
        val c = dao.insert(rule("c", ObserveConfig, sortOrder = 2))
        dao.reorderAll(listOf(c, a, b), updatedAt = 5)
        assertEquals(listOf(c, a, b), dao.getAllOrdered().map { it.id })
        assertEquals(listOf(0, 1, 2), dao.getAllOrdered().map { it.sortOrder })
        assertTrue(dao.getAllOrdered().all { it.updatedAt == 5L })
    }

    @Test
    fun `folder queries and unfiling on folder delete`() = runBlocking {
        val folders = db.ruleFolderDao()
        val f = folders.insert(RuleFolderEntity.fromDomain(RuleFolder(name = "staging", sortOrder = 0, createdAt = 1)))
        val inF = dao.insert(rule("in", ObserveConfig, sortOrder = 0, folderId = f))
        val loose = dao.insert(rule("loose", ObserveConfig, sortOrder = 1))
        assertEquals(listOf(inF), dao.observeByFolder(f).first().map { it.id })
        assertEquals(listOf(loose), dao.observeUnfiled().first().map { it.id })

        dao.moveToFolder(loose, f, updatedAt = 2)
        assertEquals(listOf(inF, loose), dao.observeByFolder(f).first().map { it.id })

        folders.setEnabled(f, false)
        folders.setCollapsed(f, true)
        folders.getById(f)!!.let { assertFalse(it.enabled); assertTrue(it.collapsed) }

        folders.deleteAndUnfile(f)
        assertNull(folders.getById(f))
        assertEquals(2, dao.count())
        assertTrue(dao.getAllOrdered().all { it.folderId == null })
    }

    @Test
    fun `disableStages is the kill switch for mutating stages only`() = runBlocking {
        dao.insert(rule("gw", GatewayConfig(GatewayMode.BLOCK), sortOrder = 0))
        dao.insert(rule("mock", MockConfig(), sortOrder = 1))
        dao.insert(rule("chaos", ChaosConfig(), sortOrder = 2))
        val n = dao.disableStages(RuleStage.MUTATING.map { it.name }, updatedAt = 7)
        assertEquals(2, n)
        assertEquals(listOf("gw"), dao.getEnabledOrdered().map { it.name })
    }

    @Test
    fun `entity round-trips through the domain model and stage column follows config`() = runBlocking {
        val id = dao.insert(rule("x", ChaosConfig(failRate = 0.3, delayMinMs = 1, delayMaxMs = 2), sortOrder = 0))
        val e = dao.getById(id)!!
        assertEquals(RuleStage.CHAOS, e.stage)
        val d = e.toDomain()
        assertEquals(ChaosConfig(failRate = 0.3, delayMinMs = 1, delayMaxMs = 2), d.config)
        assertEquals(listOf("*x*"), d.matcher.hostGlobs)
        assertEquals(id, d.id)
        assertEquals(e, InterceptorRuleEntity.fromDomain(d))
    }

    @Test
    fun `unknown stage name written by a newer build degrades to OBSERVE`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO interceptor_rules (name, stage, enabled, sortOrder, folderId, matcherJson, configJson, createdAt, updatedAt, legacyRuleId) " +
                "VALUES ('future', 'HOLOGRAM', 1, 0, NULL, '{}', '{\"stage\":\"HOLOGRAM\",\"x\":1}', 1, 1, NULL)",
        )
        val e = dao.getAllOrdered().single()
        assertEquals(RuleStage.OBSERVE, e.stage)
        val d = e.toDomain()
        assertEquals(ObserveConfig, d.config)
        assertEquals(RuleStage.OBSERVE, d.stage)
    }

    @Test
    fun `hasBlockRuleForDomain matches the converted glob shape only for enabled gateway block rules`() = runBlocking {
        val id = dao.insert(rule("blk", GatewayConfig(GatewayMode.BLOCK), sortOrder = 0, matcher = RuleMatcherSpec(hostGlobs = listOf("*ads.example*"))))
        dao.insert(rule("bypass", GatewayConfig(GatewayMode.BYPASS), sortOrder = 1, matcher = RuleMatcherSpec(hostGlobs = listOf("*cdn.example*"))))
        assertTrue(dao.hasBlockRuleForDomain("ads.example"))
        assertTrue(dao.observeHasBlockRuleForDomain("ads.example").first())
        assertFalse(dao.hasBlockRuleForDomain("cdn.example"))
        assertFalse(dao.hasBlockRuleForDomain("example"))
        dao.setEnabled(id, false, updatedAt = 1)
        assertFalse(dao.hasBlockRuleForDomain("ads.example"))
    }

    @Test
    fun `paused flows upsert by flowId and sweep`() = runBlocking {
        val paused = db.pausedFlowDao()
        val row = PausedFlowEntity(flowId = "f1", ruleId = 1, ruleName = "bp", phase = "REQUEST", host = "h", url = "https://h/", method = "GET", requestSnapshot = "{}", responseSnapshot = null, pausedAt = 10, timeoutAt = 20)
        paused.upsert(row)
        paused.upsert(row.copy(state = "EDITING"))
        assertEquals("EDITING", paused.getByFlowId("f1")!!.state)
        assertEquals(1, paused.observeCount().first())
        paused.setState("f1", "RESUMED")
        assertEquals("RESUMED", paused.getByFlowId("f1")!!.state)
        assertEquals(1, paused.deleteAll())
        assertTrue(paused.getAll().isEmpty())
    }

    @Test
    fun `replay results cascade with their job`() = runBlocking {
        val jobs = db.replayJobDao()
        val results = db.replayResultDao()
        val job = jobs.insert(ReplayJobEntity(sourceFlowIds = "[1,2]", iterations = 2, intervalMs = 0, jitterMs = 0, concurrency = 1, createdAt = 1))
        results.insert(ReplayResultEntity(jobId = job, sourceFlowId = 1, attemptNumber = 1, startedAt = 1))
        results.insert(ReplayResultEntity(jobId = job, sourceFlowId = 2, attemptNumber = 1, startedAt = 2, statusCode = 200, latencyMs = 5, completedAt = 7))
        assertEquals(2, results.countByJob(job))
        assertEquals(listOf(1L, 2L), results.observeByJob(job).first().map { it.sourceFlowId })
        jobs.setCounters(job, queued = 0, completed = 1, failed = 1)
        jobs.setStatus(job, "COMPLETED", completedAt = 9, error = null)
        jobs.getById(job)!!.let { assertEquals("COMPLETED", it.status); assertEquals(1, it.completedCount); assertEquals(9L, it.completedAt) }
        assertTrue(jobs.getActive().isEmpty())
        jobs.deleteById(job)
        assertEquals(0, results.countByJob(job))
    }
}
