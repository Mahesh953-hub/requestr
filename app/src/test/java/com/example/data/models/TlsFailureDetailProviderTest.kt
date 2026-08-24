package com.example.data.models

import com.example.data.local.TlsSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14 / Lane D: unit tests for [toTlsFailureDetail] — the [TlsSessionEntity] →
 * [TlsFailureDetail] mapping that drives [com.example.ui.components.TlsFailureDetailSheet].
 * Covers the two approved mockup variants (unknown_ca, handshake_failure) plus the RST/close
 * paths and the null return for non-FAILED sessions.
 */
class TlsFailureDetailProviderTest {

    private fun failedEntity(reason: String, sni: String? = "host.example", version: String? = "TLSv1.3") =
        TlsSessionEntity(
            tlsSessionId = "tls-test",
            sessionId = 1,
            connectionId = "tls-test",
            sniHost = sni,
            version = version,
            cipherSuite = null,
            negotiatedProtocol = null,
            ja3Hash = "abc123",
            handshakeState = "FAILED",
            wasDecrypted = false,
            peerCertSubject = null,
            peerCertIssuer = null,
            failureReason = reason,
        )

    @Test
    fun `non-FAILED entity maps to null`() {
        val established = TlsSessionEntity(
            tlsSessionId = "tls-est", sessionId = 1, connectionId = "c", sniHost = "h", version = "TLSv1.3",
            cipherSuite = "TLS_AES_128_GCM_SHA256", negotiatedProtocol = "h2",
            ja3Hash = "x", handshakeState = "ESTABLISHED", wasDecrypted = true,
            peerCertSubject = "CN=h", peerCertIssuer = "CN=ca",
        )
        assertNull(toTlsFailureDetail(established))
    }

    @Test
    fun `unknown_ca is a client trust rejection with Install CA remedy`() {
        val detail = toTlsFailureDetail(failedEntity("alert: unknown_ca", sni = "pay.stripe.com", version = "TLSv1.2"))!!
        assertEquals("Trust rejection", detail.category)
        assertEquals("Client (the app)", detail.alertSentBy)
        assertEquals(TlsRemedyType.INSTALL_CA, detail.remedy)
        assertEquals("Install requestr CA", detail.remedy.primaryLabel)
        assertEquals("Bypass this host", detail.remedy.secondaryLabel)
        // 3-step rail, last stage FAILED.
        assertEquals(3, detail.stages.size)
        assertEquals(TlsStageState.FAILED, detail.stages.last().state)
        assertEquals(TlsStageState.DONE, detail.stages[1].state)
    }

    @Test
    fun `handshake_failure is a server negotiation failure with Extend cipher remedy`() {
        val detail = toTlsFailureDetail(failedEntity("alert: handshake_failure", sni = "clients4.google.com"))!!
        assertEquals("Negotiation failure", detail.category)
        assertEquals("Server (upstream)", detail.alertSentBy)
        assertEquals(TlsRemedyType.EXTEND_CIPHERS, detail.remedy)
        // No ServerHello reached for a server-side handshake_failure.
        assertEquals(TlsStageState.NOT_REACHED, detail.stages[1].state)
        assertEquals(TlsStageState.FAILED, detail.stages.last().state)
    }

    @Test
    fun `reset is a peer connection reset with bypass remedy`() {
        val detail = toTlsFailureDetail(failedEntity("connection reset during handshake"))!!
        assertEquals("Connection reset", detail.category)
        assertEquals(TlsRemedyType.BYPASS_HOST, detail.remedy)
        assertEquals(TlsStageState.NOT_REACHED, detail.stages[1].state)
    }

    @Test
    fun `closed is a premature close with bypass remedy`() {
        val detail = toTlsFailureDetail(failedEntity("closed before handshake completed"))!!
        assertEquals("Premature close", detail.category)
        assertEquals(TlsRemedyType.BYPASS_HOST, detail.remedy)
    }

    @Test
    fun `session facts are read off the entity`() {
        val detail = toTlsFailureDetail(failedEntity("alert: unknown_ca", sni = "pay.stripe.com", version = "TLSv1.2"))!!
        assertEquals("pay.stripe.com", detail.sniHost)
        assertEquals("TLSv1.2", detail.offeredVersion)
        assertEquals("abc123", detail.ja3Hash)
        assertTrue(detail.failureReason.contains("unknown_ca"))
    }
}
