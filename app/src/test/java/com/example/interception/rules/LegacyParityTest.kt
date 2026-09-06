package com.example.interception.rules

import com.example.data.local.InterceptorRuleEntity
import com.example.data.models.HttpMethod
import com.example.interception.RuleEnforcer
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.stages.BodyCryptoStage
import com.example.interception.rules.stages.ChaosStage
import com.example.interception.rules.stages.MockStage
import com.example.interception.rules.stages.RewriteStage
import com.example.interception.rules.stages.ScriptStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * G16 (rule parity): for every one of the 11 legacy [LegacyRuleAction]s, the legacy `intercept_rules`
 * row converted through [LegacyRuleConverter] and run through the S4 pipeline + stages must
 * produce a message identical to what [RuleEnforcer] produced from the same row -- every field
 * the serializers read (url, host, status, headers, body, bodyReplaced, isBodyBinary,
 * replacedBodyBytes), hence byte-identical output on the wire. This is the gate that lets v24
 * drop the legacy table (task 34).
 */
@RunWith(RobolectricTestRunner::class)
class LegacyParityTest {

    private fun legacy(action: LegacyRuleAction, replacementValue: String? = null, domain: String = "parity.example", name: String = "legacy") =
        LegacyInterceptRule(id = 7, name = name, domain = domain, action = action, replacementValue = replacementValue)

    private fun decisionFor(rule: LegacyInterceptRule, request: ParsedHttpMessage): RuleDecision {
        val converted = LegacyRuleConverter.convert(rule, sortOrder = 0) as LegacyRuleConverter.ConversionResult.Converted
        val snapshot = RuleSnapshotCompiler.compile(listOf(InterceptorRuleEntity.fromDomain(converted.rule)), emptyList(), 1L)
        return RulePipeline.evaluate(
            snapshot,
            FlowKey(host = request.host, port = 443, url = request.url, method = request.method?.name, headers = request.headers),
        )
    }

    private fun request(body: String = "", headers: Map<String, String> = mapOf("host" to "parity.example", "accept" to "*/*")) =
        ParsedHttpMessage(
            isRequest = true, method = HttpMethod.POST, url = "https://parity.example/v1/items?q=1", path = "/v1/items",
            host = "parity.example", port = 443, headers = headers, body = body,
            rawBodyBytes = body.toByteArray(Charsets.UTF_8),
        )

    private fun response(body: String = """{"ok":true}""", headers: Map<String, String> = mapOf("content-type" to "application/json", "content-length" to "11")) =
        ParsedHttpMessage(
            isRequest = false, method = null, url = "https://parity.example/v1/items?q=1", host = "parity.example",
            statusCode = 200, statusMessage = "OK", headers = headers, body = body,
            rawBodyBytes = body.toByteArray(Charsets.UTF_8),
        )

    /** Everything the serializers read; two messages with equal fingerprints serialize identically. */
    private fun fingerprint(m: ParsedHttpMessage): String = buildString {
        append(m.isRequest).append('|').append(m.method).append('|').append(m.url).append('|').append(m.host).append('|')
        append(m.statusCode).append('|').append(m.statusMessage).append('|')
        append(m.headers.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }).append('|')
        append(m.body).append('|').append(m.bodyReplaced).append('|').append(m.isBodyBinary).append('|')
        append(m.replacedBodyBytes?.joinToString("") { "%02x".format(it) })
    }

    private fun assertParity(legacyMessage: ParsedHttpMessage, newMessage: ParsedHttpMessage) =
        assertEquals(fingerprint(legacyMessage), fingerprint(newMessage))

    // ── 1. BLOCK ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `BLOCK -- legacy first-match BLOCK equals a blocking gateway decision`() {
        val rule = legacy(LegacyRuleAction.BLOCK)
        val decision = decisionFor(rule, request())
        assertTrue(decision.gateway.blocks)
        assertEquals(GatewayMode.BLOCK, decision.gateway.effectiveMode)
        // And the negative: a non-matching host is neither blocked legacy-side nor pipeline-side.
        val other = request().also { it.host = "other.example"; it.url = "https://other.example/" }
        assertFalse(decisionFor(rule, other).gateway.blocks)
    }

    // ── 2. FORCE_MITM ────────────────────────────────────────────────────────────────────────

    @Test
    fun `FORCE_MITM -- maps to FORCE_DECRYPT gateway`() {
        val decision = decisionFor(legacy(LegacyRuleAction.FORCE_MITM), request())
        assertEquals(GatewayMode.FORCE_DECRYPT, decision.gateway.effectiveMode)
        assertFalse(decision.gateway.blocks)
    }

    // ── 3. LOG_ONLY ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `LOG_ONLY -- becomes an observe match that changes nothing`() {
        val decision = decisionFor(legacy(LegacyRuleAction.LOG_ONLY), request())
        assertEquals(1, decision.observe.size)
        assertFalse(decision.mutates)
        val legacyMsg = request("body"); val newMsg = request("body")
        RewriteStage.applyRequest(newMsg, decision.rewrite)
        assertParity(legacyMsg, newMsg)
    }

    // ── 4. REDIRECT ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `REDIRECT -- url host and host header rewritten identically`() {
        val target = "https://staging.example:8443/v2/items?x=y"
        val rule = legacy(LegacyRuleAction.REDIRECT, target)
        // Legacy MitmProxyEngine.handleParsedRequest REDIRECT branch, verbatim.
        val legacyMsg = request("b")
        legacyMsg.url = rule.replacementValue!!
        runCatching { java.net.URI(legacyMsg.url).host?.let { legacyMsg.host = it } }
        legacyMsg.headers = legacyMsg.headers.toMutableMap().also { it["host"] = legacyMsg.host }

        val newMsg = request("b")
        val decision = decisionFor(rule, newMsg)
        assertTrue(RewriteStage.applyRequest(newMsg, decision.rewrite).changed)
        assertParity(legacyMsg, newMsg)
        assertEquals("staging.example", newMsg.host)
    }

    @Test
    fun `REDIRECT -- blank target is a no-op both sides`() {
        val newMsg = request("b")
        val decision = decisionFor(legacy(LegacyRuleAction.REDIRECT, "   "), newMsg)
        assertFalse(decision.rewrite.hasRequestWork)
        assertParity(request("b"), newMsg)
    }

    // ── 5. MODIFY_REQUEST ────────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_REQUEST -- addHeaders removeHeaders bodyReplace identical`() {
        val config = """{"addHeaders":{"X-Trace":"abc","authorization":"Bearer t"},"removeHeaders":["accept"],"bodyReplace":"{\"replaced\":true}"}"""
        val rule = legacy(LegacyRuleAction.MODIFY_REQUEST, config)
        val legacyMsg = request("orig")
        RuleEnforcer.applyRequestModification(legacyMsg, config)
        val newMsg = request("orig")
        RewriteStage.applyRequest(newMsg, decisionFor(rule, newMsg).rewrite)
        assertParity(legacyMsg, newMsg)
        assertTrue(newMsg.bodyReplaced)
        assertEquals("abc", newMsg.headers["X-Trace"])
        assertNull(newMsg.headers["accept"])
    }

    @Test
    fun `MODIFY_REQUEST -- header-only edit leaves the body and bodyReplaced alone`() {
        val config = """{"addHeaders":{"x-a":"1"}}"""
        val legacyMsg = request("keep")
        RuleEnforcer.applyRequestModification(legacyMsg, config)
        val newMsg = request("keep")
        RewriteStage.applyRequest(newMsg, decisionFor(legacy(LegacyRuleAction.MODIFY_REQUEST, config), newMsg).rewrite)
        assertParity(legacyMsg, newMsg)
        assertFalse(newMsg.bodyReplaced)
    }

    // ── 6. MODIFY_RESPONSE ───────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_RESPONSE -- statusCode addHeaders bodyReplace identical`() {
        val config = """{"statusCode":418,"addHeaders":{"X-Mock":"true"},"bodyReplace":"teapot"}"""
        val rule = legacy(LegacyRuleAction.MODIFY_RESPONSE, config)
        val legacyMsg = response()
        RuleEnforcer.applyResponseModification(legacyMsg, config)
        val newMsg = response()
        val decision = decisionFor(rule, request())
        assertTrue(decision.rewrite.hasResponseWork)
        RewriteStage.applyResponse(newMsg, decision.rewrite)
        assertParity(legacyMsg, newMsg)
        assertEquals(418, newMsg.statusCode)
        assertEquals("teapot", newMsg.body)
    }

    // ── 7. MOCK_RESPONSE ─────────────────────────────────────────────────────────────────────

    @Test
    fun `MOCK_RESPONSE -- synthesized response identical for JSON and text bodies`() {
        for (config in listOf(
            """{"statusCode":201,"statusMessage":"Created","headers":{"X-Served-By":"mock"},"body":"{\"id\":42}"}""",
            """{"body":"plain text"}""",
            """{"statusCode":204}""",
            """{"headers":{"Content-Type":"application/xml"},"body":"<a/>"}""",
        )) {
            val req = request()
            val legacyMsg = RuleEnforcer.buildMockResponse(config, req.url)
            val decision = decisionFor(legacy(LegacyRuleAction.MOCK_RESPONSE, config), req)
            val newMsg = MockStage.build(decision.mock!!.config, req.url, req.method?.name, req.headers)
            assertEquals(config, fingerprint(legacyMsg), fingerprint(newMsg))
        }
    }

    // ── 8. SCRIPT_REWRITE ────────────────────────────────────────────────────────────────────

    @Test
    fun `SCRIPT_REWRITE -- request and response mutations identical`() {
        val requestScript = """pm.request.headers.set('X-Injected', 'yes'); pm.request.url = 'https://parity.example/rewritten'; pm.request.body = 'from-script';"""
        val requestConfig = org.json.JSONObject().put("script", requestScript).toString()
        val legacyReq = request("orig")
        assertNull(RuleEnforcer.applyScriptRewrite(legacyReq, requestConfig))
        val newReq = request("orig")
        val decision = decisionFor(legacy(LegacyRuleAction.SCRIPT_REWRITE, requestConfig), newReq)
        val result = ScriptStage.apply(newReq, decision.scripts, FlowAuditTrail.Phase.REQUEST)
        assertTrue(result.changed); assertTrue(result.errors.isEmpty())
        assertParity(legacyReq, newReq)
        assertEquals("from-script", newReq.body)

        val responseScript = """pm.response.code = 202; pm.response.headers.set('X-From', 'script'); pm.response.body = '{"rewritten":true}';"""
        val responseConfig = org.json.JSONObject().put("script", responseScript).toString()
        val legacyResp = response()
        assertNull(RuleEnforcer.applyScriptRewrite(legacyResp, responseConfig))
        val newResp = response()
        val decision2 = decisionFor(legacy(LegacyRuleAction.SCRIPT_REWRITE, responseConfig), request())
        ScriptStage.apply(newResp, decision2.scripts, FlowAuditTrail.Phase.RESPONSE)
        assertParity(legacyResp, newResp)
        assertEquals(202, newResp.statusCode)
    }

    @Test
    fun `SCRIPT_REWRITE -- a throwing script stamps the identical error header`() {
        val config = org.json.JSONObject().put("script", "throw new Error('boom');").toString()
        val rule = legacy(LegacyRuleAction.SCRIPT_REWRITE, config, name = "js-rule")
        val legacyResp = response()
        val error = RuleEnforcer.applyScriptRewrite(legacyResp, config)
        assertNotNull(error)
        legacyResp.headers = legacyResp.headers + (RuleEnforcer.SCRIPT_ERROR_HEADER to RuleEnforcer.scriptErrorHeaderValue(error!!, rule.name))
        val newResp = response()
        ScriptStage.apply(newResp, decisionFor(rule, request()).scripts, FlowAuditTrail.Phase.RESPONSE)
        assertParity(legacyResp, newResp)
        assertTrue(newResp.headers[RuleMetadataHeaders.SCRIPT_ERROR]!!.endsWith("; rule=\"js-rule\""))
    }

    // ── 9. CHAOS ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `CHAOS -- fail delay and corrupt-body outcomes identical under the same seed`() {
        val configs = listOf(
            """{"failRate":1.0,"failStatusCode":429,"failStatusMessage":"Slow Down","failBody":"rate limited"}""",
            """{"failRate":1.0}""",
            """{"delayMs":[250,250]}""",
            """{"corruptBodyRate":1.0}""",
            """{"failRate":0.5,"corruptBodyRate":0.5,"delayMs":[10,500]}""",
        )
        for (config in configs) for (seed in 1..5) {
            val rule = legacy(LegacyRuleAction.CHAOS, config, name = "flaky-api")
            val decision = decisionFor(rule, request())
            val chaos = decision.chaos!!
            // request leg
            val legacyReq = RuleEnforcer.applyChaosToRequest(rule, Random(seed))
            val newReq = ChaosStage.applyToRequest(chaos.config, chaos.rule.name, Random(seed))
            when (legacyReq) {
                is RuleEnforcer.ChaosApplyResult.Fail -> {
                    val n = newReq as ChaosStage.Result.Fail
                    assertEquals("$config seed=$seed", fingerprint(legacyReq.failureResponse), fingerprint(n.failureResponse))
                }
                else -> assertTrue("$config seed=$seed", newReq is ChaosStage.Result.PassThrough)
            }
            // response leg
            val legacyResp = response()
            val newResp = response()
            val l = RuleEnforcer.applyChaosToResponse(legacyResp, rule, Random(seed))
            val n = ChaosStage.applyToResponse(newResp, chaos.config, chaos.rule.name, Random(seed))
            when (l) {
                is RuleEnforcer.ChaosApplyResult.Applied -> assertTrue("$config seed=$seed", n is ChaosStage.Result.Applied)
                is RuleEnforcer.ChaosApplyResult.Delay -> assertEquals("$config seed=$seed", l.millis, (n as ChaosStage.Result.Delay).millis)
                RuleEnforcer.ChaosApplyResult.PassThrough -> assertTrue("$config seed=$seed", n is ChaosStage.Result.PassThrough)
                is RuleEnforcer.ChaosApplyResult.Fail -> throw AssertionError("legacy never fails on the response leg")
            }
            assertEquals("$config seed=$seed", fingerprint(legacyResp), fingerprint(newResp))
        }
    }

    // ── 10 + 11. BODY_ENCRYPT_AES / BODY_DECRYPT_AES ─────────────────────────────────────────

    private fun keyFor(alias: String): SecretKey {
        val random = java.util.Random(alias.hashCode().toLong())
        return SecretKeySpec(ByteArray(32).also { random.nextBytes(it) }, "AES")
    }

    private fun cryptoLegacy(action: LegacyRuleAction, scope: String = "BOTH", alias: String = "k1") = LegacyInterceptRule(
        id = 9, name = "crypto-rule", domain = "parity.example", action = action,
        cryptoKeyAlias = alias, cryptoScope = scope, cryptoDirection = if (action == LegacyRuleAction.BODY_ENCRYPT_AES) "ENCRYPT" else "DECRYPT",
    )

    @Test
    fun `BODY_ENCRYPT_AES then BODY_DECRYPT_AES -- ciphertext from either engine decrypts identically in the other`() {
        val plaintext = """{"secret":"plaintext-value"}"""
        val encRule = cryptoLegacy(LegacyRuleAction.BODY_ENCRYPT_AES)
        val encDecision = decisionFor(encRule, request())
        val encConfig = encDecision.bodyCrypto!!.config
        assertEquals(CryptoDirection.ENCRYPT, encConfig.direction)
        assertEquals(CryptoScope.BOTH, encConfig.scope)
        assertEquals("k1", encConfig.keyAlias)

        // Legacy encrypts -> new decrypts (and vice versa). GCM IVs are random, so ciphertexts
        // differ per run; what must be identical is the structural result and the round trip.
        val legacyEnc = request(plaintext)
        assertTrue(RuleEnforcer.applyBodyCryptoWith(legacyEnc, encRule, encrypt = true, ::keyFor) is RuleEnforcer.CryptoApplyResult.Applied)
        val newEnc = request(plaintext)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(newEnc, encConfig, "crypto-rule", ::keyFor))
        assertEquals(legacyEnc.bodyReplaced, newEnc.bodyReplaced)
        assertEquals(legacyEnc.isBodyBinary, newEnc.isBodyBinary)
        assertEquals(legacyEnc.body, newEnc.body)
        assertEquals(legacyEnc.replacedBodyBytes!!.size, newEnc.replacedBodyBytes!!.size)

        val decRule = cryptoLegacy(LegacyRuleAction.BODY_DECRYPT_AES)
        val decConfig = decisionFor(decRule, request()).bodyCrypto!!.config
        fun inbound(cipher: ByteArray) = ParsedHttpMessage(
            isRequest = false, method = null, url = "https://parity.example/v1/items", host = "parity.example", statusCode = 200,
            headers = mapOf("content-type" to "application/octet-stream"), body = "", isBodyBinary = true, rawBodyBytes = cipher,
        )
        val legacyDec = inbound(newEnc.replacedBodyBytes!!)
        val newDec = inbound(legacyEnc.replacedBodyBytes!!)
        assertTrue(RuleEnforcer.applyBodyCryptoWith(legacyDec, decRule, encrypt = false, ::keyFor) is RuleEnforcer.CryptoApplyResult.Applied)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(newDec, decConfig, "crypto-rule", ::keyFor))
        assertEquals(plaintext, String(newDec.replacedBodyBytes!!, Charsets.UTF_8))
        assertEquals(String(legacyDec.replacedBodyBytes!!, Charsets.UTF_8), String(newDec.replacedBodyBytes!!, Charsets.UTF_8))
        assertEquals(legacyDec.body, newDec.body)
        assertEquals(legacyDec.isBodyBinary, newDec.isBodyBinary)
        assertEquals(legacyDec.bodyReplaced, newDec.bodyReplaced)
    }

    @Test
    fun `BODY crypto -- blank alias fails closed with an identical synthetic 502`() {
        for (action in listOf(LegacyRuleAction.BODY_ENCRYPT_AES, LegacyRuleAction.BODY_DECRYPT_AES)) {
            val rule = cryptoLegacy(action, alias = "")
            val encrypt = action == LegacyRuleAction.BODY_ENCRYPT_AES
            val legacyMsg = request("plain")
            val legacyResult = RuleEnforcer.applyBodyCryptoWith(legacyMsg, rule, encrypt, ::keyFor) as RuleEnforcer.CryptoApplyResult.Failed
            val newMsg = request("plain")
            val config = decisionFor(rule, newMsg).bodyCrypto!!.config
            val newResult = BodyCryptoStage.applyWith(newMsg, config, rule.name, ::keyFor) as BodyCryptoStage.Result.Failed
            assertEquals(legacyResult.reason, newResult.reason)
            assertParity(legacyMsg, newMsg) // untouched either side
            assertParity(
                RuleEnforcer.buildCryptoFailureResponse(rule.name, encrypt, legacyResult.reason),
                BodyCryptoStage.failureResponse(rule.name, config.direction, newResult.reason),
            )
        }
    }

    @Test
    fun `BODY crypto -- scope REQUEST or RESPONSE skips the other message type identically`() {
        val rule = cryptoLegacy(LegacyRuleAction.BODY_ENCRYPT_AES, scope = "REQUEST")
        val config = decisionFor(rule, request()).bodyCrypto!!.config
        val resp = response()
        assertTrue(RuleEnforcer.applyBodyCryptoWith(resp, rule, encrypt = true, ::keyFor) is RuleEnforcer.CryptoApplyResult.NotApplicable)
        assertEquals(BodyCryptoStage.Result.NotApplicable, BodyCryptoStage.applyWith(response(), config, rule.name, ::keyFor))
    }

    // ── conversion coverage ──────────────────────────────────────────────────────────────────

    @Test
    fun `every legacy action converts to exactly one typed stage`() {
        val expected = mapOf(
            LegacyRuleAction.BLOCK to RuleStage.GATEWAY, LegacyRuleAction.FORCE_MITM to RuleStage.GATEWAY, LegacyRuleAction.LOG_ONLY to RuleStage.OBSERVE,
            LegacyRuleAction.REDIRECT to RuleStage.REWRITE, LegacyRuleAction.MODIFY_REQUEST to RuleStage.REWRITE, LegacyRuleAction.MODIFY_RESPONSE to RuleStage.REWRITE,
            LegacyRuleAction.MOCK_RESPONSE to RuleStage.MOCK, LegacyRuleAction.SCRIPT_REWRITE to RuleStage.SCRIPT, LegacyRuleAction.CHAOS to RuleStage.CHAOS,
            LegacyRuleAction.BODY_ENCRYPT_AES to RuleStage.BODY_CRYPTO, LegacyRuleAction.BODY_DECRYPT_AES to RuleStage.BODY_CRYPTO,
        )
        assertEquals("all 11 legacy actions covered", LegacyRuleAction.entries.toSet(), expected.keys)
        for ((action, stage) in expected) {
            val row = if (stage == RuleStage.BODY_CRYPTO) cryptoLegacy(action) else legacy(action, replacementValue = if (action == LegacyRuleAction.SCRIPT_REWRITE) """{"script":"1"}""" else "https://x.example/")
            val converted = LegacyRuleConverter.convert(row, 0) as LegacyRuleConverter.ConversionResult.Converted
            assertEquals(action.name, stage, converted.rule.stage)
        }
    }
}
