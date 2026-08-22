package com.example.interception.tls

import com.example.interception.tcp.TcpHandshakePhase
import org.junit.Assert.*
import org.junit.Test

/**
 * Task 11: exercises [classifyTlsOutcome] — the (handshake phase, alert, existing-row) → FAILED
 * mapping — without Room or sockets. See [com.example.interception.CaptureEngine.persistTlsFailureIfNeeded]
 * for the production call site and [com.example.interception.MitmProxyEngine.persistFailedMitmTlsSession]
 * for the MITM-leg SSLHandshakeException path this classifier does not cover (that path is a
 * direct catch, not a phase/alert classification).
 */
class TlsFailureStateTest {

    private fun fatalAlert(descriptionName: String, description: Int) =
        TlsAlert(level = 2, description = description, levelName = "fatal", descriptionName = descriptionName, isFatal = true)

    private fun warningAlert(descriptionName: String, description: Int) =
        TlsAlert(level = 1, description = description, levelName = "warning", descriptionName = descriptionName, isFatal = false)

    @Test
    fun `fatal alert produces FAILED with alert reason`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.SYN_ACK_SEEN,
            alert = fatalAlert("handshake_failure", 40),
            hasEstablishedRow = false,
        )
        assertNotNull(outcome)
        assertEquals("alert: handshake_failure", outcome!!.reason)
    }

    @Test
    fun `warning close_notify after establish produces no FAILED`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.CLOSING,
            alert = warningAlert("close_notify", 0),
            hasEstablishedRow = true,
        )
        assertNull(outcome)
    }

    @Test
    fun `RST during handshake produces FAILED with reset reason`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.RESET,
            alert = null,
            hasEstablishedRow = false,
        )
        assertNotNull(outcome)
        assertEquals("connection reset during handshake", outcome!!.reason)
    }

    @Test
    fun `RST after establish produces no FAILED`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.RESET,
            alert = null,
            hasEstablishedRow = true,
        )
        assertNull(outcome)
    }

    @Test
    fun `closed before handshake completed produces FAILED with closed reason`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.CLOSED,
            alert = null,
            hasEstablishedRow = false,
        )
        assertNotNull(outcome)
        assertEquals("closed before handshake completed", outcome!!.reason)
    }

    @Test
    fun `no ClientHello ever seen produces null regardless of phase`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.RESET,
            alert = null,
            hasEstablishedRow = false,
            hasClientHello = false,
        )
        assertNull(outcome)
    }

    @Test
    fun `in-progress handshake with no signal yet produces null`() {
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.SYN_ACK_SEEN,
            alert = null,
            hasEstablishedRow = false,
        )
        assertNull(outcome)
    }

    @Test
    fun `fatal alert wins even if a row was already established`() {
        // A fatal alert after ESTABLISHED is unusual but should still be reported honestly --
        // it is the most specific signal available and always wins per the classifier's doc.
        val outcome = classifyTlsOutcome(
            phase = TcpHandshakePhase.ESTABLISHED,
            alert = fatalAlert("unknown_ca", 48),
            hasEstablishedRow = true,
        )
        assertNotNull(outcome)
        assertEquals("alert: unknown_ca", outcome!!.reason)
    }
}
