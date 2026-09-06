package com.example.interception.rules.stages

import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.NamePolicy
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 task 28: every Host/SNI/CN policy combination resolves to the right three names. */
class MirrorStageTest {

    private val rule = RuleRef(5L, "to-staging", RuleStage.MIRROR)

    private fun plan(
        hostPolicy: NamePolicy = NamePolicy.PRESERVE,
        sniPolicy: NamePolicy = NamePolicy.USE_MIRROR_ADDRESS,
        cnPolicy: NamePolicy = NamePolicy.PRESERVE,
        clientSni: String? = "cdn.app.example",
        targetPort: Int? = 8443,
    ) = MirrorStage.plan(
        connectHost = "app.example", connectPort = 443, clientSni = clientSni,
        config = MirrorConfig("staging.example", targetPort, hostPolicy, sniPolicy, cnPolicy), rule = rule,
    )

    @Test
    fun `dial target is always the mirror, default port is the CONNECT port`() {
        val p = plan()
        assertEquals("staging.example", p.dialHost)
        assertEquals(8443, p.dialPort)
        assertEquals("staging.example:8443", p.target)
        assertEquals(443, plan(targetPort = null).dialPort)
        assertEquals("app.example", p.connectHost)
    }

    @Test
    fun `all 27 policy combinations resolve the three axes independently`() {
        val expectedHost = mapOf(NamePolicy.PRESERVE to null, NamePolicy.USE_CONNECT_ADDRESS to "app.example", NamePolicy.USE_MIRROR_ADDRESS to "staging.example:8443")
        val expectedSni = mapOf(NamePolicy.PRESERVE to "cdn.app.example", NamePolicy.USE_CONNECT_ADDRESS to "app.example", NamePolicy.USE_MIRROR_ADDRESS to "staging.example")
        val expectedCert = mapOf(
            NamePolicy.PRESERVE to Triple("app.example", 443, "cdn.app.example"),
            NamePolicy.USE_CONNECT_ADDRESS to Triple("app.example", 443, null),
            NamePolicy.USE_MIRROR_ADDRESS to Triple("staging.example", 8443, null),
        )
        var combos = 0
        for (h in NamePolicy.entries) for (s in NamePolicy.entries) for (c in NamePolicy.entries) {
            val p = plan(hostPolicy = h, sniPolicy = s, cnPolicy = c)
            val label = "host=$h sni=$s cn=$c"
            assertEquals(label, expectedHost[h], p.hostHeader)
            assertEquals(label, expectedSni[s], p.sniName)
            assertEquals(label, expectedCert[c], Triple(p.certAuthority, p.certPort, p.certSni))
            combos++
        }
        assertEquals(27, combos)
    }

    @Test
    fun `PRESERVE without a client SNI falls back to the CONNECT host`() {
        val p = plan(sniPolicy = NamePolicy.PRESERVE, cnPolicy = NamePolicy.PRESERVE, clientSni = null)
        assertEquals("app.example", p.sniName)
        assertEquals("app.example", p.certAuthority)
        assertNull(p.certSni)
        assertEquals("app.example", plan(sniPolicy = NamePolicy.PRESERVE, clientSni = "  ").sniName)
    }

    @Test
    fun `authority omits the default TLS port only`() {
        assertEquals("h.example", MirrorStage.authority("h.example", 443))
        assertEquals("h.example:8443", MirrorStage.authority("h.example", 8443))
        assertEquals("h.example:80", MirrorStage.authority("h.example", 80))
        assertEquals("app.example", plan(hostPolicy = NamePolicy.USE_CONNECT_ADDRESS).hostHeader)
        assertEquals("staging.example", plan(hostPolicy = NamePolicy.USE_MIRROR_ADDRESS, targetPort = 443).hostHeader)
    }

    private fun request(headers: Map<String, String>) =
        ParsedHttpMessage(isRequest = true, method = HttpMethod.GET, url = "https://app.example/x", host = "app.example", headers = headers)

    @Test
    fun `applyHostHeader rewrites in place preserving the header's case and records the trail`() {
        val req = request(mapOf("Host" to "app.example", "accept" to "*/*"))
        val trail = FlowAuditTrail()
        assertTrue(MirrorStage.applyHostHeader(req, plan(hostPolicy = NamePolicy.USE_MIRROR_ADDRESS), trail))
        assertEquals(mapOf("Host" to "staging.example:8443", "accept" to "*/*"), req.headers)
        assertEquals("app.example", req.host)
        assertEquals("https://app.example/x", req.url)
        val record = trail.toRecords().single()
        assertEquals(FlowAuditTrail.Outcome.APPLIED, record.outcome)
        assertEquals(rule, record.rule)
        assertEquals("host header -> staging.example:8443", record.detail)
    }

    @Test
    fun `applyHostHeader adds a Host header when none was present`() {
        val req = request(emptyMap())
        assertTrue(MirrorStage.applyHostHeader(req, plan(hostPolicy = NamePolicy.USE_CONNECT_ADDRESS)))
        assertEquals("app.example", req.headers["Host"])
    }

    @Test
    fun `applyHostHeader is a no-op for PRESERVE and when the value already matches`() {
        val req = request(mapOf("host" to "app.example"))
        assertFalse(MirrorStage.applyHostHeader(req, plan(hostPolicy = NamePolicy.PRESERVE)))
        assertEquals("app.example", req.headers["host"])
        assertFalse(MirrorStage.applyHostHeader(req, plan(hostPolicy = NamePolicy.USE_CONNECT_ADDRESS)))
    }

    @Test
    fun `attribution header names the rule and the target`() {
        val p = plan()
        assertEquals("rule=\"to-staging\"; target=staging.example:8443", p.attribution)
        val resp = ParsedHttpMessage(isRequest = false, method = null, statusCode = 200, headers = mapOf("X-Requestr-Mirror" to "stale", "content-length" to "0"))
        MirrorStage.stampAttribution(resp, p)
        assertEquals(mapOf("content-length" to "0", RuleMetadataHeaders.MIRROR to p.attribution), resp.headers)
        assertTrue(RuleMetadataHeaders.isMetadata(RuleMetadataHeaders.MIRROR))
    }
}
