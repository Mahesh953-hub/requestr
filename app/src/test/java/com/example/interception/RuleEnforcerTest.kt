package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [RuleEnforcer.checkRules] / [RuleEnforcer.getMatchingRule] for every [RuleAction],
 * with particular attention to [RuleAction.FORCE_MITM] -- new for the CONNECT-control
 * subsystem -- to make sure it round-trips through the real `intercept_rules` table exactly
 * like the pre-existing actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerTest {

    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertRule(
        domain: String = "",
        pattern: String = "",
        action: RuleAction,
        enabled: Boolean = true,
        replacementValue: String? = null,
    ) = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "test",
                domain = domain,
                pattern = pattern,
                action = action,
                enabled = enabled,
                replacementValue = replacementValue,
            )
        )
    }

    @Test
    fun `no matching rule returns LOG_ONLY`() {
        assertEquals(RuleAction.LOG_ONLY, RuleEnforcer.checkRules(db, "https://example.com/", "example.com"))
    }

    @Test
    fun `domain match returns BLOCK`() {
        insertRule(domain = "blocked.example", action = RuleAction.BLOCK)
        assertEquals(
            RuleAction.BLOCK,
            RuleEnforcer.checkRules(db, "https://blocked.example/path", "blocked.example"),
        )
    }

    @Test
    fun `domain match returns FORCE_MITM`() {
        insertRule(domain = "mitm.example", action = RuleAction.FORCE_MITM)
        assertEquals(
            RuleAction.FORCE_MITM,
            RuleEnforcer.checkRules(db, "https://mitm.example:443/", "mitm.example"),
        )
    }

    @Test
    fun `disabled FORCE_MITM rule does not match`() {
        insertRule(domain = "mitm.example", action = RuleAction.FORCE_MITM, enabled = false)
        assertEquals(
            RuleAction.LOG_ONLY,
            RuleEnforcer.checkRules(db, "https://mitm.example/", "mitm.example"),
        )
    }

    @Test
    fun `getMatchingRule exposes replacementValue for REDIRECT`() {
        insertRule(domain = "old.example", action = RuleAction.REDIRECT, replacementValue = "new.example:443")
        val rule = RuleEnforcer.getMatchingRule(db, "https://old.example/", "old.example")
        assertEquals("new.example:443", rule?.replacementValue)
        assertEquals(RuleAction.REDIRECT, rule?.action)
    }

    @Test
    fun `first matching enabled rule wins over a later one`() {
        insertRule(domain = "a.example", action = RuleAction.LOG_ONLY)
        insertRule(pattern = "secret", action = RuleAction.BLOCK)
        // The first rule (domain match) wins even though the second rule (pattern match) would
        // also match -- RuleEnforcer explicitly documents first-match-wins semantics.
        assertEquals(
            RuleAction.LOG_ONLY,
            RuleEnforcer.checkRules(db, "https://a.example/secret/path", "a.example"),
        )
    }

    @Test
    fun `pattern match alone (no domain rule ahead of it) is honored`() {
        insertRule(pattern = "secret", action = RuleAction.BLOCK)
        assertEquals(
            RuleAction.BLOCK,
            RuleEnforcer.checkRules(db, "https://a.example/secret/path", "a.example"),
        )
    }

    @Test
    fun `getMatchingRule returns null when nothing matches`() {
        insertRule(domain = "other.example", action = RuleAction.BLOCK)
        assertNull(RuleEnforcer.getMatchingRule(db, "https://example.com/", "example.com"))
    }

    // ── §1.5 MOCK_RESPONSE — RuleEnforcer.buildMockResponse ─────────────

    @Test
    fun `domain match returns MOCK_RESPONSE`() {
        insertRule(domain = "mock.example", action = RuleAction.MOCK_RESPONSE, replacementValue = "{}")
        assertEquals(
            RuleAction.MOCK_RESPONSE,
            RuleEnforcer.checkRules(db, "https://mock.example/", "mock.example"),
        )
    }

    @Test
    fun `buildMockResponse applies statusCode, addHeaders and bodyReplace from config`() {
        val config = """{"statusCode":201,"addHeaders":{"X-Mock":"true"},"bodyReplace":"hello"}"""
        val response = RuleEnforcer.buildMockResponse(config, "https://mock.example/")

        assertEquals(201, response.statusCode)
        assertEquals("true", response.headers["x-mock"])
        assertEquals("hello", response.body)
        assertEquals("https://mock.example/", response.url)
    }

    @Test
    fun `buildMockResponse computes Content-Length from the actual body bytes`() {
        val config = """{"bodyReplace":"0123456789"}"""
        val response = RuleEnforcer.buildMockResponse(config)
        assertEquals("10", response.headers["content-length"])
    }

    @Test
    fun `buildMockResponse Content-Length reflects UTF-8 byte length, not char length`() {
        // "é" is 1 char but 2 UTF-8 bytes -- a length computed from body.length would desync.
        val config = """{"bodyReplace":"café"}"""
        val response = RuleEnforcer.buildMockResponse(config)
        assertEquals("café".toByteArray(Charsets.UTF_8).size.toString(), response.headers["content-length"])
    }

    @Test
    fun `buildMockResponse defaults to status 200 and empty body when config is null`() {
        val response = RuleEnforcer.buildMockResponse(null)
        assertEquals(200, response.statusCode)
        assertEquals("", response.body)
        assertEquals("0", response.headers["content-length"])
    }

    @Test
    fun `buildMockResponse defaults to status 200 when config is blank`() {
        val response = RuleEnforcer.buildMockResponse("   ")
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `buildMockResponse defaults Content-Type to application-json when the body is JSON`() {
        val response = RuleEnforcer.buildMockResponse("""{"bodyReplace":"{\"ok\":true}"}""")
        assertTrue(response.headers["content-type"]?.startsWith("application/json") == true)
    }

    @Test
    fun `buildMockResponse defaults Content-Type to text-plain when the body is not JSON`() {
        val response = RuleEnforcer.buildMockResponse("""{"bodyReplace":"plain text"}""")
        assertTrue(response.headers["content-type"]?.startsWith("text/plain") == true)
    }

    @Test
    fun `buildMockResponse honors an explicit Content-Type over the JSON-sniffed default`() {
        val config = """{"bodyReplace":"{}","addHeaders":{"Content-Type":"application/xml"}}"""
        val response = RuleEnforcer.buildMockResponse(config)
        assertEquals("application/xml", response.headers["content-type"])
    }

    @Test
    fun `buildMockResponse falls back to safe defaults when config JSON is malformed`() {
        val response = RuleEnforcer.buildMockResponse("{not valid json")
        assertEquals(200, response.statusCode)
        assertEquals("", response.body)
        assertEquals("0", response.headers["content-length"])
    }
}
