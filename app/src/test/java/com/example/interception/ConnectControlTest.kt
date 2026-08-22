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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [ConnectControl.evaluate]'s mapping from [RuleAction] to [ConnectControl.Disposition]
 * -- the single decision point every CONNECT call site (HttpProxyEngine, MitmProxyEngine,
 * GatewayEngine) now defers to instead of each re-implementing rule lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConnectControlTest {

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
        domain: String,
        action: RuleAction,
        replacementValue: String? = null,
    ) = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(name = "test", domain = domain, action = action, replacementValue = replacementValue)
        )
    }

    @Test
    fun `no matching rule allows opaque tunnel`() {
        val disposition = ConnectControl.evaluate(db, "example.com", 443)
        assertTrue(disposition is ConnectControl.Disposition.AllowOpaque)
    }

    @Test
    fun `BLOCK rule maps to Block with a reason`() {
        insertRule("blocked.example", RuleAction.BLOCK)
        val disposition = ConnectControl.evaluate(db, "blocked.example", 443)
        assertTrue(disposition is ConnectControl.Disposition.Block)
        assertTrue((disposition as ConnectControl.Disposition.Block).reason?.contains("blocked.example") == true)
    }

    @Test
    fun `FORCE_MITM rule maps to ForceMitm`() {
        insertRule("mitm.example", RuleAction.FORCE_MITM)
        val disposition = ConnectControl.evaluate(db, "mitm.example", 443)
        assertTrue(disposition is ConnectControl.Disposition.ForceMitm)
    }

    @Test
    fun `REDIRECT rule with a replacementValue maps to Redirect`() {
        insertRule("old.example", RuleAction.REDIRECT, replacementValue = "new.example:8443")
        val disposition = ConnectControl.evaluate(db, "old.example", 443)
        assertTrue(disposition is ConnectControl.Disposition.Redirect)
        assertEquals("new.example:8443", (disposition as ConnectControl.Disposition.Redirect).target)
    }

    @Test
    fun `REDIRECT rule with a blank replacementValue falls back to AllowOpaque`() {
        insertRule("old.example", RuleAction.REDIRECT, replacementValue = "")
        val disposition = ConnectControl.evaluate(db, "old.example", 443)
        assertTrue(disposition is ConnectControl.Disposition.AllowOpaque)
    }

    @Test
    fun `LOG_ONLY REDIRECT and MODIFY rules with no CONNECT-level meaning fall back to AllowOpaque`() {
        insertRule("a.example", RuleAction.LOG_ONLY)
        insertRule("b.example", RuleAction.MODIFY_REQUEST)
        insertRule("c.example", RuleAction.MODIFY_RESPONSE)
        assertTrue(ConnectControl.evaluate(db, "a.example", 443) is ConnectControl.Disposition.AllowOpaque)
        assertTrue(ConnectControl.evaluate(db, "b.example", 443) is ConnectControl.Disposition.AllowOpaque)
        assertTrue(ConnectControl.evaluate(db, "c.example", 443) is ConnectControl.Disposition.AllowOpaque)
    }
}
