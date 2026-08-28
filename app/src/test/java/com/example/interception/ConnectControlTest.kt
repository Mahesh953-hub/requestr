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

    // ── PinningFailureRegistry auto-bypass ────────────────────────────────────────────────

    @org.junit.Before
    fun resetPinningRegistry() {
        ConnectControl.PinningFailureRegistry.clearAll()
    }

    @Test
    fun `FORCE_MITM rule stays ForceMitm below the pinning-failure threshold`() {
        insertRule("pinned.example", RuleAction.FORCE_MITM)
        ConnectControl.PinningFailureRegistry.recordFailure("pinned.example", threshold = 3)
        ConnectControl.PinningFailureRegistry.recordFailure("pinned.example", threshold = 3)
        assertTrue(ConnectControl.evaluate(db, "pinned.example", 443) is ConnectControl.Disposition.ForceMitm)
    }

    @Test
    fun `FORCE_MITM rule downgrades to AllowOpaque once the pinning-failure threshold trips`() {
        insertRule("pinned.example", RuleAction.FORCE_MITM)
        repeat(3) { ConnectControl.PinningFailureRegistry.recordFailure("pinned.example", threshold = 3) }
        assertTrue(ConnectControl.evaluate(db, "pinned.example", 443) is ConnectControl.Disposition.AllowOpaque)
    }

    @Test
    fun `pinning bypass is host-scoped and case-insensitive, does not leak to other hosts`() {
        insertRule("pinned.example", RuleAction.FORCE_MITM)
        insertRule("other.example", RuleAction.FORCE_MITM)
        repeat(3) { ConnectControl.PinningFailureRegistry.recordFailure("Pinned.Example", threshold = 3) }
        assertTrue(ConnectControl.evaluate(db, "pinned.example", 443) is ConnectControl.Disposition.AllowOpaque)
        assertTrue(ConnectControl.evaluate(db, "other.example", 443) is ConnectControl.Disposition.ForceMitm)
    }

    @Test
    fun `pinning bypass does not affect BLOCK or REDIRECT dispositions`() {
        insertRule("blocked.example", RuleAction.BLOCK)
        insertRule("old.example", RuleAction.REDIRECT, replacementValue = "new.example:8443")
        repeat(3) {
            ConnectControl.PinningFailureRegistry.recordFailure("blocked.example", threshold = 3)
            ConnectControl.PinningFailureRegistry.recordFailure("old.example", threshold = 3)
        }
        assertTrue(ConnectControl.evaluate(db, "blocked.example", 443) is ConnectControl.Disposition.Block)
        assertTrue(ConnectControl.evaluate(db, "old.example", 443) is ConnectControl.Disposition.Redirect)
    }

    @Test
    fun `recordFailure returns true only on the call that trips the bypass`() {
        val tripped = (1..4).map {
            ConnectControl.PinningFailureRegistry.recordFailure("x.example", threshold = 3)
        }
        assertEquals(listOf(false, false, true, false), tripped)
    }

    @Test
    fun `failures outside the window do not accumulate towards the threshold`() {
        val base = 1_000_000L
        ConnectControl.PinningFailureRegistry.recordFailure("stale.example", at = base, threshold = 3, windowMs = 1000)
        ConnectControl.PinningFailureRegistry.recordFailure("stale.example", at = base + 500, threshold = 3, windowMs = 1000)
        // Third failure lands well outside the 1000ms window relative to the first two --
        // they should have aged out, so this alone must not trip a threshold of 3.
        val tripped = ConnectControl.PinningFailureRegistry.recordFailure(
            "stale.example", at = base + 5000, threshold = 3, windowMs = 1000,
        )
        assertTrue(!tripped)
        assertTrue(!ConnectControl.PinningFailureRegistry.shouldBypass("stale.example", now = base + 5000))
    }

    @Test
    fun `bypass expires after its duration`() {
        val base = 1_000_000L
        repeat(3) { ConnectControl.PinningFailureRegistry.recordFailure("expiring.example", at = base, threshold = 3, bypassDurationMs = 500) }
        assertTrue(ConnectControl.PinningFailureRegistry.shouldBypass("expiring.example", now = base + 100))
        assertTrue(!ConnectControl.PinningFailureRegistry.shouldBypass("expiring.example", now = base + 1000))
    }

    @Test
    fun `clear removes tracked state for a host`() {
        repeat(3) { ConnectControl.PinningFailureRegistry.recordFailure("clearme.example", threshold = 3) }
        assertTrue(ConnectControl.PinningFailureRegistry.shouldBypass("clearme.example"))
        ConnectControl.PinningFailureRegistry.clear("clearme.example")
        assertTrue(!ConnectControl.PinningFailureRegistry.shouldBypass("clearme.example"))
    }
}
