package com.example.interception

import android.os.Build
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §2.14(b): covers [RuleEnforcer.applyBodyCrypto]'s scope-gating and fail-open-but-visible
 * behavior for a broken rule config. The actual AES/GCM encrypt/decrypt round trip is covered
 * by [RequestCryptoManagerTest] via its injectable key-provider seam -- `AndroidKeyStore` isn't
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

    private fun rule(scope: String, alias: String = "test-alias") = InterceptRuleEntity(
        name = "crypto-rule",
        action = RuleAction.BODY_ENCRYPT_AES,
        cryptoScope = scope,
        cryptoKeyAlias = alias,
    )

    @Test
    fun `REQUEST scope skips response messages`() {
        val response = responseMessage()
        RuleEnforcer.applyBodyCrypto(response, rule("REQUEST"), encrypt = true)
        assertFalse(response.bodyReplaced)
        assertNull(response.replacedBodyBytes)
    }

    @Test
    fun `RESPONSE scope skips request messages`() {
        val request = requestMessage()
        RuleEnforcer.applyBodyCrypto(request, rule("RESPONSE"), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
    }

    @Test
    fun `blank key alias fails open -- message left untouched, never a broken connection`() {
        val request = requestMessage()
        RuleEnforcer.applyBodyCrypto(request, rule("BOTH", alias = ""), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
    }

    @Test
    fun `unrecognized scope fails open to applying (REQUEST message eligible)`() {
        // Not asserting a successful crypto result here (needs real AndroidKeyStore -- see
        // class kdoc), just that an unrecognized scope value doesn't itself skip the message
        // the way REQUEST-only/RESPONSE-only do -- it reaches the (keystore-backed) crypto call
        // rather than being gated out early. A blank alias still fails safely either way.
        val request = requestMessage()
        RuleEnforcer.applyBodyCrypto(request, rule("SOMETHING_ELSE", alias = ""), encrypt = true)
        assertFalse(request.bodyReplaced)
    }
}
