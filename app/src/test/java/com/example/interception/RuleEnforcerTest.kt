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
}
