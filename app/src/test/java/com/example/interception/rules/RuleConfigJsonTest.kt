package com.example.interception.rules

import com.squareup.moshi.JsonDataException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S4 task 24 -- the `configJson` / `matcherJson` wire format. Every [RuleConfig] variant must
 * survive a round trip byte-for-byte (the snapshot compiler (task 25) and the import/export path
 * both depend on it), and the three polymorphic envelopes (`stage`, `type`, `kind`) must degrade
 * the way the class KDoc promises.
 */
@RunWith(RobolectricTestRunner::class)
class RuleConfigJsonTest {

    private fun <T : RuleConfig> roundTrip(config: T): RuleConfig =
        RuleConfigJson.decodeConfig(RuleConfigJson.encodeConfig(config))

    private fun stageOf(json: String): String = JSONObject(json).getString("stage")

    @Test
    fun `gateway config round-trips and carries its stage discriminator`() {
        val cfg = GatewayConfig(mode = GatewayMode.SUSPEND, suspendTimeoutMs = 12_345, showBlockedInList = true)
        val json = RuleConfigJson.encodeConfig(cfg)
        assertEquals("GATEWAY", stageOf(json))
        assertEquals(cfg, roundTrip(cfg))
        for (mode in GatewayMode.entries) assertEquals(GatewayConfig(mode), roundTrip(GatewayConfig(mode)))
    }

    @Test
    fun `mirror config round-trips with every name policy`() {
        val cfg = MirrorConfig(
            targetHost = "staging.example.com",
            targetPort = 8443,
            hostPolicy = NamePolicy.USE_CONNECT_ADDRESS,
            sniPolicy = NamePolicy.USE_MIRROR_ADDRESS,
            cnPolicy = NamePolicy.PRESERVE,
        )
        assertEquals(cfg, roundTrip(cfg))
        assertEquals(MirrorConfig("x"), roundTrip(MirrorConfig("x")))
    }

    @Test
    fun `rewrite config round-trips every modification type and body source`() {
        val cfg = RewriteConfig(
            redirection = RedirectSpec("https://mirror.example/api", preserveHost = true, directoryMatch = true),
            replaceRequest = MessageReplacement(headers = mapOf("x-a" to "1"), body = BodySource.Text("req")),
            replaceResponse = MessageReplacement(statusCode = 418, statusMessage = "Teapot", body = BodySource.File("/sdcard/fixture.json")),
            requestModifications = listOf(
                Modification.SetHeader("x-debug", "1"),
                Modification.RemoveHeader("cookie"),
                Modification.RegexHeader("user-agent", "Chrome/\\d+", "Chrome/0"),
                Modification.SetQueryParam("debug", "true"),
                Modification.RemoveQueryParam("utm_source"),
                Modification.RegexBody("\"secret\":\"[^\"]*\"", "\"secret\":\"***\""),
                Modification.ReplaceBody(BodySource.Empty),
            ),
            responseModifications = listOf(
                Modification.SetStatus(503),
                Modification.SetStatus(200, "Fine"),
                Modification.ReplaceBody(BodySource.Text("{}")),
                Modification.ReplaceBody(BodySource.File("file:///x.json")),
            ),
            variables = mapOf("env" to "staging"),
        )
        val json = RuleConfigJson.encodeConfig(cfg)
        assertEquals("REWRITE", stageOf(json))
        val decoded = roundTrip(cfg)
        assertEquals(cfg, decoded)
        // Modification envelope uses simple class names; body source uses `kind`.
        assertTrue(json.contains("\"type\":\"RegexHeader\""))
        assertTrue(json.contains("\"kind\":\"FILE\""))
        assertTrue(json.contains("\"kind\":\"EMPTY\""))
        assertTrue(json.contains("\"kind\":\"TEXT\""))
    }

    @Test
    fun `empty rewrite config is a no-op and round-trips`() {
        val cfg = RewriteConfig()
        assertTrue(cfg.isNoOp)
        assertEquals(cfg, roundTrip(cfg))
        assertFalse(RewriteConfig(redirection = RedirectSpec("https://a/")).isNoOp)
    }

    @Test
    fun `breakpoint, script, mock, crypto, throttle, chaos and observe round-trip`() {
        val configs: List<RuleConfig> = listOf(
            BreakpointConfig(phase = BreakpointPhase.RESPONSE, timeoutMs = 99),
            BreakpointConfig(),
            ScriptConfig(source = "function onRequest(r){ r.headers['x'] = '1'; }", timeoutMs = 1_500, maxInstructions = 10, variables = mapOf("k" to "v")),
            MockConfig(statusCode = 201, statusMessage = "Created", headers = mapOf("content-type" to "application/json"), body = BodySource.Text("{\"ok\":true}"), variables = mapOf("a" to "b")),
            MockConfig(),
            BodyCryptoConfig(direction = CryptoDirection.DECRYPT, scope = CryptoScope.REQUEST, keyAlias = "alias-1", algorithm = "AES/GCM/NoPadding"),
            ThrottleConfig(presetKey = "3g", downlinkBps = 750_000, uplinkBps = 250_000, latencyMs = 100, jitterMs = 20, lossPercent = 1),
            ChaosConfig(failRate = 0.25, corruptBodyRate = 0.1, delayMinMs = 100, delayMaxMs = 900, failStatusCode = 500, failStatusMessage = "Boom", failBody = "{}"),
            ObserveConfig,
        )
        for (cfg in configs) {
            val json = RuleConfigJson.encodeConfig(cfg)
            assertEquals(cfg.stage.name, stageOf(json))
            assertEquals("round trip of $cfg", cfg, RuleConfigJson.decodeConfig(json))
        }
    }

    @Test
    fun `observe config serialises as just the stage`() {
        assertEquals("""{"stage":"OBSERVE"}""", RuleConfigJson.encodeConfig(ObserveConfig))
    }

    @Test
    fun `unknown or missing stage decodes to observe rather than failing`() {
        assertEquals(ObserveConfig, RuleConfigJson.decodeConfig("""{"stage":"HOLOGRAM","anything":1}"""))
        assertEquals(ObserveConfig, RuleConfigJson.decodeConfig("""{"foo":"bar"}"""))
        assertEquals(ObserveConfig, RuleConfigJson.decodeConfig("""{}"""))
    }

    @Test
    fun `known stage with malformed body is a hard error`() {
        // MirrorConfig.targetHost is non-null with no default -- a REWRITE-shaped body under MIRROR must not silently decode.
        val err = runCatching { RuleConfigJson.decodeConfig("""{"stage":"MIRROR","redirection":{"targetUrl":"x"}}""") }.exceptionOrNull()
        assertTrue("expected JsonDataException, got $err", err is JsonDataException)
        val notObject = runCatching { RuleConfigJson.decodeConfig("[1,2,3]") }.exceptionOrNull()
        assertTrue(notObject is JsonDataException)
    }

    @Test
    fun `defaults are filled in for omitted optional fields`() {
        val cfg = RuleConfigJson.decodeConfig("""{"stage":"CHAOS","failRate":0.5}""") as ChaosConfig
        assertEquals(0.5, cfg.failRate, 0.0)
        assertEquals(0.0, cfg.corruptBodyRate, 0.0)
        assertEquals(ChaosConfig.DEFAULT_FAIL_STATUS_CODE, cfg.failStatusCode)
        assertEquals(ChaosConfig.DEFAULT_FAIL_BODY, cfg.failBody)
        val mock = RuleConfigJson.decodeConfig("""{"stage":"MOCK"}""") as MockConfig
        assertEquals(200, mock.statusCode)
        assertEquals(BodySource.Empty, mock.body)
    }

    @Test
    fun `unknown modification type or body kind is rejected`() {
        val badType = runCatching {
            RuleConfigJson.decodeConfig("""{"stage":"REWRITE","requestModifications":[{"type":"Teleport","name":"x"}]}""")
        }.exceptionOrNull()
        assertTrue("$badType", badType is JsonDataException)
        val badKind = runCatching {
            RuleConfigJson.decodeConfig("""{"stage":"MOCK","body":{"kind":"BLOB"}}""")
        }.exceptionOrNull()
        assertTrue("$badKind", badKind is JsonDataException)
    }

    @Test
    fun `matcher spec round-trips every constraint`() {
        val spec = RuleMatcherSpec(
            hostGlobs = listOf("*.example.com", "api.example.org"),
            urlRegex = "^https://.*/v[12]/",
            methods = listOf("GET", "POST"),
            portRange = PortRange(8000, 8999),
            ipCidrs = listOf("10.0.0.0/8", "2001:db8::/32"),
            headerMatchers = listOf(HeaderMatcher("x-env", "^stag"), HeaderMatcher("authorization", null)),
            appPackages = listOf("com.example.app"),
            combine = MatchCombine.ANY,
        )
        assertEquals(spec, RuleConfigJson.decodeMatcher(RuleConfigJson.encodeMatcher(spec)))
        assertFalse(spec.isUnconstrained)
        assertTrue(RuleMatcherSpec.MATCH_ALL.isUnconstrained)
        assertEquals(RuleMatcherSpec.MATCH_ALL, RuleConfigJson.decodeMatcher("{}"))
        assertEquals(MatchCombine.ALL, RuleConfigJson.decodeMatcher("{}").combine)
    }

    @Test
    fun `port range helpers`() {
        assertEquals(PortRange(443, 443), PortRange.single(443))
        assertTrue(PortRange(8000, 8999).contains(8443))
        assertFalse(PortRange(8000, 8999).contains(443))
    }

    @Test
    fun `string maps round-trip and empty decodes to empty`() {
        val m = mapOf("a" to "1", "b" to "x\"y")
        assertEquals(m, RuleConfigJson.decodeStringMap(RuleConfigJson.encodeStringMap(m)))
        assertEquals(emptyMap<String, String>(), RuleConfigJson.decodeStringMap("{}"))
        assertEquals(emptyMap<String, String>(), RuleConfigJson.decodeStringMap("null"))
    }

    @Test
    fun `body source legacy value parsing`() {
        assertEquals(BodySource.Empty, BodySource.fromLegacyValue(""))
        assertEquals(BodySource.Text("hello"), BodySource.fromLegacyValue("hello"))
        assertEquals(BodySource.File("file:///sdcard/x.json"), BodySource.fromLegacyValue("file:///sdcard/x.json"))
        assertNull((BodySource.fromLegacyValue("x") as? BodySource.File))
    }
}
