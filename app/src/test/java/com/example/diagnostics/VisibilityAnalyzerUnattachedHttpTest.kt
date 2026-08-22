package com.example.diagnostics

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6/DEC-4: [ConnectionLineage] joins HTTP rows on an exact `connectionId`, so a row persisted
 * without one belongs to no lineage. Those rows must be counted and surfaced, not silently
 * dropped -- otherwise they deflate the per-connection coverage fractions with no explanation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VisibilityAnalyzerUnattachedHttpTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun httpRow(sessionId: Long, host: String, connectionId: String?) =
        CapturedTrafficEntity(
            sessionId = sessionId,
            method = "GET",
            url = "https://$host/x",
            host = host,
            path = "/x",
            statusCode = 200,
            protocol = "HTTP/1.1",
            connectionId = connectionId,
        )

    @Test
    fun `rows with no connectionId are counted as unattached and excluded from coverage`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val sessionId = 8801L
        val connectionId = "6|10.0.0.1:5555>93.184.216.34:443"

        db.tcpConnectionDao().upsert(
            TcpConnectionEntity(
                connectionId = connectionId,
                sessionId = sessionId,
                srcIp = "10.0.0.1",
                destIp = "93.184.216.34",
                srcPort = 5555,
                destPort = 443,
                state = "ESTABLISHED",
            )
        )

        // One row that genuinely belongs to the connection above...
        db.capturedTrafficDao().insert(httpRow(sessionId, "attached.example", connectionId))
        // ...and two that no lineage can claim, which a host+window heuristic would have
        // mis-attached to the connection above.
        db.capturedTrafficDao().insert(httpRow(sessionId, "orphan-one.example", null))
        db.capturedTrafficDao().insert(httpRow(sessionId, "orphan-two.example", null))

        val report = VisibilityAnalyzer.analyze(context, sessionId)

        assertEquals("both connectionId-less rows must be counted", 2, report.unattachedHttpRows)
        assertEquals("all three rows are still counted in httpTotal", 3, report.httpTotal)
        assertEquals(
            "only the exact-key-joined row may count toward per-connection coverage",
            1,
            report.connectionsWithHttpDecoded,
        )
        assertTrue(
            "the unattached count must be explained to the user, not left unexplained",
            report.limitingReasons.any { it.contains("no connectionId") },
        )
    }

    @Test
    fun `a session whose rows all carry a connectionId reports zero unattached`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val sessionId = 8802L
        val connectionId = "6|10.0.0.2:5556>93.184.216.35:443"

        db.tcpConnectionDao().upsert(
            TcpConnectionEntity(
                connectionId = connectionId,
                sessionId = sessionId,
                srcIp = "10.0.0.2",
                destIp = "93.184.216.35",
                srcPort = 5556,
                destPort = 443,
                state = "ESTABLISHED",
            )
        )
        db.capturedTrafficDao().insert(httpRow(sessionId, "clean.example", connectionId))

        val report = VisibilityAnalyzer.analyze(context, sessionId)

        assertEquals(0, report.unattachedHttpRows)
        assertTrue(
            "no unattached-rows explanation should appear when there are none",
            report.limitingReasons.none { it.contains("no connectionId") },
        )
    }
}
