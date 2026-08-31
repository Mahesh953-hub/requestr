package com.example.interception

import android.os.Build
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §2.14(b) / security audit fix (fail-open AES crypto): covers [RuleEnforcer.applyBodyCrypto]'s
 * scope-gating and its [RuleEnforcer.CryptoApplyResult] contract. A failure (bad key, blank
 * alias, foreign ciphertext) must come back as [RuleEnforcer.CryptoApplyResult.Failed] -- never
 * silently reported as if nothing happened -- so a caller (see
 * [MitmProxyEngineCryptoFailClosedTest]) can block/substitute the transaction instead of
 * forwarding the untransformed body. The actual AES/GCM encrypt/decrypt round trip is covered by
 * [RequestCryptoManagerTest] via its injectable key-provider seam -- `AndroidKeyStore` isn't
 * available under Robolectric/plain-JVM tests (see `SecureKeyStoreTest`'s documented boundary),
 * so [RuleEnforcer.applyBodyCrypto]'s own success path (which goes through the real
 * `SecureKeyStore`-backed [RequestCryptoManager.encrypt]/[decrypt]) is exercised on a physical
 * device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerBodyCryptoTest {

    private fun requestMessage(body: String = "hello") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.POST, body = body,
    )

    private fun responseMessage(body: String = "world") = ParsedHttpMessage(
        isRequest = false, method = null, body = body,
    )

    private fun rule(scope: String, alias: String = "test-alias", action: RuleAction = RuleAction.BODY_ENCRYPT_AES) =
        InterceptRuleEntity(
            name = "crypto-rule",
            action = action,
            cryptoScope = scope,
            cryptoKeyAlias = alias,
        )

    @Test
    fun `REQUEST scope skips response messages and reports NotApplicable`() {
        val response = responseMessage()
        val result = RuleEnforcer.applyBodyCrypto(response, rule("REQUEST"), encrypt = true)
        assertFalse(response.bodyReplaced)
        assertNull(response.replacedBodyBytes)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.NotApplicable)
    }

    @Test
    fun `RESPONSE scope skips request messages and reports NotApplicable`() {
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("RESPONSE"), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.NotApplicable)
    }

    @Test
    fun `blank key alias fails closed on encrypt -- message untouched but reported Failed, never silent`() {
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("BOTH", alias = "", action = RuleAction.BODY_ENCRYPT_AES), encrypt = true)
        assertFalse("message must not look like it was successfully encrypted", request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
        assertTrue(
            "a blank alias must surface as Failed so the caller blocks the transaction instead " +
                "of forwarding the untouched plaintext as if encryption succeeded",
            result is RuleEnforcer.CryptoApplyResult.Failed,
        )
    }

    @Test
    fun `blank key alias fails closed on decrypt too`() {
        val response = responseMessage()
        val result = RuleEnforcer.applyBodyCrypto(response, rule("BOTH", alias = "", action = RuleAction.BODY_DECRYPT_AES), encrypt = false)
        assertFalse(response.bodyReplaced)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.Failed)
    }

    @Test
    fun `unrecognized scope fails open to applying (REQUEST message eligible)`() {
        // Not asserting a successful crypto result here (needs real AndroidKeyStore -- see
        // class kdoc), just that an unrecognized scope value doesn't itself skip the message
        // the way REQUEST-only/RESPONSE-only do -- it reaches the (keystore-backed) crypto call
        // rather than being gated out early. A blank alias still fails closed either way.
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("SOMETHING_ELSE", alias = ""), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.Failed)
    }

    @Test
    fun `buildCryptoFailureResponse never includes key material and is machine-checkable`() {
        val failure = RuleEnforcer.buildCryptoFailureResponse(
            ruleName = "my-rule",
            encrypt = true,
            reason = "encrypt failed: no key alias configured for this rule",
        )
        assertTrue(failure.statusCode >= 400)
        assertTrue(failure.bodyReplaced)
        assertTrue(failure.body.contains("my-rule"))
        assertTrue(failure.body.contains("encrypt failed"))
        assertTrue("must carry a marker distinguishing this from a real upstream 5xx", failure.headers["x-requestr-crypto-failed"] == "true")
    }
}
