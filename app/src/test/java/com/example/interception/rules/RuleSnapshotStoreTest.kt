package com.example.interception.rules

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleDao
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleFolderDao
import com.example.data.local.RuleFolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * S4 task 25 -- [RuleSnapshotCompiler] and [RuleSnapshotStore] against a real in-memory Room build.
 * The store must (a) publish a new revision on every DAO emission, (b) never hand a reader a
 * half-built snapshot, (c) honour folder enablement, and (d) keep matching allocation-free of the
 * database: once compiled, `current` answers with the database closed.
 */
@RunWith(RobolectricTestRunner::class)
class RuleSnapshotStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        RuleSnapshotStore.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        RuleSnapshotStore.resetForTest()
        scope.cancel()
        db.close()
    }

    private fun entity(
        id: Long = 0,
        name: String,
        config: RuleConfig,
        sortOrder: Int,
        enabled: Boolean = true,
        folderId: Long? = null,
        matcher: RuleMatcherSpec = RuleMatcherSpec(hostGlobs = listOf("*$name*")),
    ) = InterceptorRuleEntity.fromDomain(
        InterceptorRule(id = id, name = name, stage = config.stage, enabled = enabled, sortOrder = sortOrder, folderId = folderId, matcher = matcher, config = config, createdAt = 1, updatedAt = 1),
    )

    private suspend fun awaitRevisionAtLeast(rev: Long): RuleSnapshot = withTimeout(10_000) {
        while (RuleSnapshotStore.current.revision < rev) delay(10)
        RuleSnapshotStore.current
    }

    private suspend fun awaitSize(size: Int): RuleSnapshot = withTimeout(10_000) {
        while (RuleSnapshotStore.current.size != size) delay(10)
        RuleSnapshotStore.current
    }

    // ── compiler ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `compiler orders by sortOrder then id, buckets by stage, and skips disabled rules`() {
        val rules = listOf(
            entity(id = 5, name = "e", config = ChaosConfig(), sortOrder = 2),
            entity(id = 1, name = "a", config = GatewayConfig(GatewayMode.BLOCK), sortOrder = 1),
            entity(id = 2, name = "b", config = GatewayConfig(GatewayMode.BYPASS), sortOrder = 1),
            entity(id = 3, name = "c", config = MockConfig(), sortOrder = 0, enabled = false),
            entity(id = 4, name = "d", config = ObserveConfig, sortOrder = 0),
        )
        val snap = RuleSnapshotCompiler.compile(rules, emptyList(), revision = 7)
        assertEquals(7L, snap.revision)
        assertEquals(listOf(4L, 1L, 2L, 5L), snap.rules.map { it.id })
        assertEquals(listOf(1L, 2L), snap.forStage(RuleStage.GATEWAY).map { it.id })
        assertEquals(listOf(5L), snap.forStage(RuleStage.CHAOS).map { it.id })
        assertTrue(snap.forStage(RuleStage.MOCK).isEmpty())
        assertFalse(snap.hasStage(RuleStage.MOCK))
        assertTrue(snap.issues.isEmpty())
        assertEquals("a", snap.findById(1)!!.name)
        assertEquals(RuleRef(1, "a", RuleStage.GATEWAY), snap.findById(1)!!.ref)
        assertTrue(snap.compileDurationNanos >= 0)
    }

    @Test
    fun `compiler applies folder enablement and reports dangling folders`() {
        val folders = listOf(
            RuleFolderEntity(id = 10, name = "on", sortOrder = 0, enabled = true, collapsed = false, createdAt = 1),
            RuleFolderEntity(id = 11, name = "off", sortOrder = 1, enabled = false, collapsed = false, createdAt = 1),
        )
        val rules = listOf(
            entity(id = 1, name = "in-on", config = ObserveConfig, sortOrder = 0, folderId = 10),
            entity(id = 2, name = "in-off", config = ObserveConfig, sortOrder = 1, folderId = 11),
            entity(id = 3, name = "dangling", config = ObserveConfig, sortOrder = 2, folderId = 99),
            entity(id = 4, name = "loose", config = ObserveConfig, sortOrder = 3),
        )
        val snap = RuleSnapshotCompiler.compile(rules, folders, revision = 1)
        assertEquals(listOf(1L, 4L), snap.rules.map { it.id })
        assertEquals(1, snap.issues.size)
        assertEquals(3L, snap.issues[0].ruleId)
        assertTrue(snap.issues[0].message.contains("folder #99"))
    }

    @Test
    fun `compiler keeps a rule with a broken regex (always-false predicate) and drops undecodable rows with an issue`() {
        val broken = entity(id = 1, name = "bad-regex", config = ObserveConfig, sortOrder = 0, matcher = RuleMatcherSpec(urlRegex = "(("))
        val undecodable = entity(id = 2, name = "bad-config", config = ObserveConfig, sortOrder = 1).copy(configJson = """{"stage":"MIRROR"}""")
        val badMatcher = entity(id = 3, name = "bad-matcher", config = ObserveConfig, sortOrder = 2).copy(matcherJson = "[1")
        val fine = entity(id = 4, name = "fine", config = ObserveConfig, sortOrder = 3)
        val snap = RuleSnapshotCompiler.compile(listOf(broken, undecodable, badMatcher, fine), emptyList(), revision = 1)
        assertEquals(listOf(1L, 4L), snap.rules.map { it.id })
        assertEquals(3, snap.issues.size)
        assertFalse(snap.findById(1)!!.matches(FlowKey("h", 443, "https://h/((")))
        assertTrue(snap.issues.any { it.ruleId == 2L && it.message.contains("configJson") })
        assertTrue(snap.issues.any { it.ruleId == 3L && it.message.contains("matcherJson") })
    }

    @Test
    fun `compiler takes the stage from the config, not the column`() {
        val e = entity(id = 1, name = "x", config = MockConfig(), sortOrder = 0).copy(stage = RuleStage.OBSERVE)
        val snap = RuleSnapshotCompiler.compile(listOf(e), emptyList(), revision = 1)
        assertEquals(RuleStage.MOCK, snap.rules.single().stage)
        assertEquals(listOf(1L), snap.forStage(RuleStage.MOCK).map { it.id })
    }

    // ── store ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `store starts empty at revision 0 and publishes a new revision on every DAO emission`() = runBlocking {
        assertSame(RuleSnapshot.EMPTY, RuleSnapshotStore.current)
        assertEquals(0L, RuleSnapshotStore.current.revision)

        val rules = FakeRuleDao()
        val folders = FakeFolderDao()
        RuleSnapshotStore.start(scope, rules, folders)
        assertTrue(RuleSnapshotStore.isRunning)
        val first = awaitRevisionAtLeast(1)
        assertTrue(first.isEmpty)

        rules.emit(listOf(entity(id = 1, name = "one", config = GatewayConfig(GatewayMode.BLOCK), sortOrder = 0)))
        val second = awaitSize(1)
        assertTrue(second.revision > first.revision)
        assertEquals(listOf(1L), second.rules.map { it.id })
        assertNotSame(first, second)
        assertEquals(second, RuleSnapshotStore.snapshots.value)

        rules.emit(emptyList())
        val third = awaitSize(0)
        assertTrue(third.revision > second.revision)

        // An emission whose content did not change is coalesced (distinctUntilChanged): no new revision.
        rules.emit(emptyList())
        delay(200)
        assertEquals(third.revision, RuleSnapshotStore.current.revision)

        // Idempotent start: same collector, no second revision stream.
        val before = RuleSnapshotStore.current.revision
        RuleSnapshotStore.start(scope, rules, folders)
        delay(200)
        assertEquals(before, RuleSnapshotStore.current.revision)

        RuleSnapshotStore.stop()
        assertFalse(RuleSnapshotStore.isRunning)
        assertEquals(third, RuleSnapshotStore.current)
        // A stopped collector never publishes again, even if its flow still emits.
        rules.emit(listOf(entity(id = 2, name = "late", config = ObserveConfig, sortOrder = 0)))
        delay(200)
        assertEquals(third, RuleSnapshotStore.current)
    }

    @Test
    fun `folder toggles republish without a rules-table change`() = runBlocking {
        val rules = FakeRuleDao(listOf(entity(id = 1, name = "in", config = ObserveConfig, sortOrder = 0, folderId = 10)))
        val folders = FakeFolderDao(listOf(RuleFolderEntity(id = 10, name = "f", sortOrder = 0, enabled = true, collapsed = false, createdAt = 1)))
        RuleSnapshotStore.start(scope, rules, folders)
        val withFolderOn = awaitSize(1)

        folders.emit(listOf(RuleFolderEntity(id = 10, name = "f", sortOrder = 0, enabled = false, collapsed = false, createdAt = 1)))
        val off = awaitSize(0)
        assertTrue(off.revision > withFolderOn.revision)

        folders.emit(listOf(RuleFolderEntity(id = 10, name = "f", sortOrder = 0, enabled = true, collapsed = false, createdAt = 1)))
        val on = awaitSize(1)
        assertTrue(on.revision > off.revision)
    }

    @Test
    fun `store compiles the live Room table on start`() = runBlocking {
        val folderId = db.ruleFolderDao().insert(RuleFolderEntity(name = "off", sortOrder = 0, enabled = false, collapsed = false, createdAt = 1))
        db.interceptorRuleDao().insert(entity(name = "hidden", config = ObserveConfig, sortOrder = 0, folderId = folderId))
        val visible = db.interceptorRuleDao().insert(entity(name = "visible", config = MockConfig(), sortOrder = 1))
        db.interceptorRuleDao().insert(entity(name = "disabled", config = ChaosConfig(), sortOrder = 2, enabled = false))
        RuleSnapshotStore.start(scope, db)
        val snap = awaitSize(1)
        assertEquals(listOf(visible), snap.rules.map { it.id })
        assertEquals(RuleStage.MOCK, snap.rules.single().stage)
    }

    @Test
    fun `evaluation against current needs no database`() = runBlocking {
        db.interceptorRuleDao().insert(entity(name = "blocked", config = GatewayConfig(GatewayMode.BLOCK), sortOrder = 0, matcher = RuleMatcherSpec(hostGlobs = listOf("*.ads.example"))))
        RuleSnapshotStore.start(scope, db)
        val snap = awaitSize(1)
        RuleSnapshotStore.stop()
        db.close()

        val rule = snap.forStage(RuleStage.GATEWAY).single()
        assertTrue(rule.matches(FlowKey.forConnect("x.ads.example", 443)))
        assertFalse(rule.matches(FlowKey.forConnect("cdn.example", 443)))
        assertEquals(GatewayConfig(GatewayMode.BLOCK), rule.config)
    }

    @Test
    fun `replaceForTest publishes verbatim and later live revisions move past it`() = runBlocking {
        val handBuilt = RuleSnapshotCompiler.compile(listOf(entity(id = 77, name = "hand", config = ObserveConfig, sortOrder = 0)), emptyList(), revision = 40)
        RuleSnapshotStore.replaceForTest(handBuilt)
        assertSame(handBuilt, RuleSnapshotStore.current)
        assertEquals(40L, RuleSnapshotStore.snapshots.value.revision)

        RuleSnapshotStore.start(scope, db)
        val live = awaitRevisionAtLeast(41)
        assertTrue(live.revision > 40)
        assertTrue(live.isEmpty)
    }

    @Test
    fun `startFromApplication is disarmed once a test owns the store`() {
        // setUp called resetForTest(); the Application's collector (bound to the real on-disk DB) must not
        // race the test's collector for the process-wide singleton.
        assertEquals(null, RuleSnapshotStore.startFromApplication(scope, db))
        assertFalse(RuleSnapshotStore.isRunning)
    }

    @Test
    fun `publish is usable directly and counts compiles`() {
        val n = RuleSnapshotStore.compileCount
        val snap = RuleSnapshotStore.publish(listOf(entity(id = 1, name = "a", config = ThrottleConfig(latencyMs = 5), sortOrder = 0)), emptyList())
        assertEquals(1, snap.size)
        assertSame(snap, RuleSnapshotStore.current)
        assertEquals(n + 1, RuleSnapshotStore.compileCount)
    }

    // ── fakes: the two DAO methods the store consumes, backed by StateFlows ──────────────────

    private class FakeRuleDao(initial: List<InterceptorRuleEntity> = emptyList()) : InterceptorRuleDao by unsupported() {
        private val flow = MutableStateFlow(initial)
        fun emit(rules: List<InterceptorRuleEntity>) { flow.value = rules }
        override fun observeEnabledOrdered(): Flow<List<InterceptorRuleEntity>> = flow.map { list -> list.filter { it.enabled } }
    }

    private class FakeFolderDao(initial: List<RuleFolderEntity> = emptyList()) : RuleFolderDao by unsupported() {
        private val flow = MutableStateFlow(initial)
        fun emit(folders: List<RuleFolderEntity>) { flow.value = folders }
        override fun observeAll(): Flow<List<RuleFolderEntity>> = flow
    }

    private companion object {
        /** Every other DAO method throws: the store must only ever call the two observe methods. */
        inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException("RuleSnapshotStore must not call ${method.name}") } as T
    }
}
