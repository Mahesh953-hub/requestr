package com.example.ui.screens

import android.os.Build
import com.example.data.local.RuleAction
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4 (review carry-forward): "Add-Rule form still creates enabled silent no-op rules." These are
 * the red/green tests for the finding itself -- written first against the pre-fix
 * [replacementValueError], where five of the six failed (only the MOCK_RESPONSE case passed,
 * because a blank mock is the documented empty `200 OK` and had to stay valid). Every rule type
 * the [RuleAction] enum defines is covered here for its blank case; the shape/boundary/type-switch
 * cases live in [ReplacementValueUiHelpersTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleConfigValidityTest {

    @Test
    fun `blank MODIFY_REQUEST config is rejected`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, "")
        assertNotNull(error)
        assertTrue(error!!, error.contains("Modify Request"))
    }

    @Test
    fun `blank MODIFY_RESPONSE config is rejected`() {
        val error = replacementValueError(RuleAction.MODIFY_RESPONSE, "  ")
        assertNotNull(error)
        assertTrue(error!!, error.contains("Modify Response"))
    }

    @Test
    fun `blank CHAOS config is rejected`() {
        val error = replacementValueError(RuleAction.CHAOS, "")
        assertNotNull(error)
        assertTrue(error!!, error.contains("Chaos"))
    }

    @Test
    fun `blank SCRIPT_REWRITE config is rejected`() {
        val error = replacementValueError(RuleAction.SCRIPT_REWRITE, "")
        assertNotNull(error)
        assertTrue(error!!, error.contains("Script (JS)"))
    }

    @Test
    fun `blank REDIRECT config is rejected`() {
        assertNotNull(replacementValueError(RuleAction.REDIRECT, ""))
    }

    @Test
    fun `blank MOCK_RESPONSE config stays valid`() {
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, ""))
    }

    @Test
    fun `blank config is valid for the actions that never read one`() {
        assertNull(replacementValueError(RuleAction.BLOCK, ""))
        assertNull(replacementValueError(RuleAction.LOG_ONLY, ""))
        assertNull(replacementValueError(RuleAction.FORCE_MITM, ""))
    }

    @Test
    fun `AES crypto actions are gated on the key alias not on replacementValue`() {
        assertNull(replacementValueError(RuleAction.BODY_ENCRYPT_AES, ""))
        assertNull(replacementValueError(RuleAction.BODY_DECRYPT_AES, ""))
        assertNotNull(ruleConfigError(RuleConfigDraft(RuleAction.BODY_ENCRYPT_AES, cryptoKeyAlias = "")))
        assertNotNull(ruleConfigError(RuleConfigDraft(RuleAction.BODY_DECRYPT_AES, cryptoKeyAlias = " ")))
    }

    @Test
    fun `CHAOS config with every rate at zero is rejected`() {
        assertNotNull(replacementValueError(RuleAction.CHAOS, """{"failRate":0,"delayMs":[0,0],"corruptBodyRate":0}"""))
    }

    @Test
    fun `every RuleAction has a decided blank-case verdict`() {
        // Guard against a future enum member silently falling into "no spec -> always valid".
        val alwaysValidBlank = setOf(
            RuleAction.BLOCK, RuleAction.LOG_ONLY, RuleAction.FORCE_MITM, RuleAction.MOCK_RESPONSE,
            RuleAction.BODY_ENCRYPT_AES, RuleAction.BODY_DECRYPT_AES,
        )
        RuleAction.values().forEach { action ->
            val verdict = replacementValueError(action, null)
            if (action in alwaysValidBlank) assertNull(action.name, verdict) else assertNotNull(action.name, verdict)
        }
    }
}
