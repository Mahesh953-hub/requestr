package com.example.diagnostics

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TlsSessionEntity
import com.example.interception.tls.TlsHandshakeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 11: [VisibilityAnalyzer.limitingReasons] must report the real FAILED count when the
 * capture actually observed a failed handshake, and must not claim the state is unreachable
 * when it is (both sentences used to be static text before task 11 wired real writers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VisibilityAnalyzerTlsFailedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `limitingReasons omits the unreachable-FAILED sentence when a FAILED row exists`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val sessionId = 42L

        db.tcpConnectionDao().upsert(
            TcpConnectionEntity(
                connectionId = "6|1.1.1.1:1>2.2.2.2:2",
                sessionId = sessionId,
                srcIp = "1.1.1.1",
                destIp = "2.2.2.2",
                srcPort = 1,
                destPort = 2,
                state = "CLOSED",
            )
        )
        db.tlsSessionDao().upsert(
            TlsSessionEntity(
                tlsSessionId = "6|1.1.1.1:1>2.2.2.2:2",
                sessionId = sessionId,
                connectionId = "6|1.1.1.1:1>2.2.2.2:2",
                sniHost = "failed.example",
                version = null,
                cipherSuite = null,
                negotiatedProtocol = null,
                ja3Hash = null,
                handshakeState = TlsHandshakeState.FAILED.name,
                wasDecrypted = false,
                peerCertSubject = null,
                peerCertIssuer = null,
                failureReason = "alert: handshake_failure",
                failedAt = System.currentTimeMillis(),
            )
        )

        val report = VisibilityAnalyzer.analyze(context, sessionId)
        assertTrue("tlsFailedReported must reflect the real FAILED row", report.tlsFailedReported == 1)
        assertFalse(
            "must not claim FAILED is never set once a real FAILED row exists",
            report.limitingReasons.any { it.contains("nothing in the codebase ever sets it") },
        )
        assertTrue(
            "must state the real failed count",
            report.limitingReasons.any { it.contains("1 of") && it.contains("failed") },
        )
    }
}
