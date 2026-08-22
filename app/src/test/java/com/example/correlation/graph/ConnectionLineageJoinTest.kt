package com.example.correlation.graph

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TlsSessionEntity
import com.example.interception.tls.TlsHandshakeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 12: [ConnectionLineage.httpSteps] must join on the exact `connectionId` column only.
 *
 * This is the regression test that stops a `getByHostInWindow` fallback from being
 * reintroduced -- see the "Lineage contract" section of docs/plans/plan-transport-detailed.md
 * task 12: a same-host-in-window match can attach a plausible-but-wrong HTTP row to a
 * connection, fabricating a causal chain the data does not support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectionLineageJoinTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // RequestrDatabase.get() memoizes a process-wide singleton (by design, for production use),
    // so rows written by one @Test can leak into another @Test running in the same JVM. Give
    // every test its own sessionId/connectionId namespace rather than relying on test isolation
    // the singleton doesn't provide.
    private val unique = java.util.UUID.randomUUID().toString().take(8)
    private val sessionId = System.nanoTime()
    private val connectionId = "6|10.0.0.1:1111>93.184.216.34:443#$unique"
    private val otherConnectionId = "6|10.0.0.2:2222>93.184.216.34:443#$unique"
    private val host = "example-$unique.test"

    private fun seedTcpAndTls(now: Long) = runBlocking {
        val db = RequestrDatabase.get(context)
        db.tcpConnectionDao().upsert(
            TcpConnectionEntity(
                connectionId = connectionId,
                sessionId = sessionId,
                srcIp = "10.0.0.1",
                destIp = "93.184.216.34",
                srcPort = 1111,
                destPort = 443,
                state = "ESTABLISHED",
                establishedAt = now,
            )
        )
        db.tlsSessionDao().upsert(
            TlsSessionEntity(
                tlsSessionId = connectionId,
                sessionId = sessionId,
                connectionId = connectionId,
                sniHost = host,
                version = "TLSv1.3",
                cipherSuite = null,
                negotiatedProtocol = null,
                ja3Hash = null,
                handshakeState = TlsHandshakeState.ESTABLISHED.name,
                wasDecrypted = false,
                peerCertSubject = null,
                peerCertIssuer = null,
                establishedAt = now,
            )
        )
    }

    @Test
    fun `exact connectionId join attaches the matching row and ignores a same-host different-connection row`() = runBlocking {
        val now = System.currentTimeMillis()
        seedTcpAndTls(now)

        val db = RequestrDatabase.get(context)
        // Matching row: same connectionId as the TLS/TCP rows above.
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "GET",
                url = "https://$host/ok",
                host = host,
                path = "/ok",
                statusCode = 200,
                timestamp = now + 10,
                connectionId = connectionId,
            )
        )
        // Decoy row: same host, well inside a 30s window of the handshake, but a genuinely
        // DIFFERENT connectionId -- must NOT be attached by an exact-key join.
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "GET",
                url = "https://$host/decoy",
                host = host,
                path = "/decoy",
                statusCode = 200,
                timestamp = now + 15_000,
                connectionId = otherConnectionId,
            )
        )

        val chain = ConnectionLineage.build(context, sessionId, connectionId)
        val httpLabels = chain.steps.filter { it.label.startsWith("GET") }.map { it.label }

        assertEquals("exactly one HTTP row should attach", 1, httpLabels.size)
        assertTrue("the attached row must be the exact-connectionId match, not the decoy", httpLabels.single().contains("/ok"))
        assertTrue("the decoy row must never be attached", httpLabels.none { it.contains("/decoy") })
    }

    @Test
    fun `a connectionId-less row produces no lineage step, not a window-matched one`() = runBlocking {
        val now = System.currentTimeMillis()
        seedTcpAndTls(now)

        val db = RequestrDatabase.get(context)
        // Same host, inside the old 30s window, but connectionId = null (e.g. SDK-sourced row
        // via LocalApiServer.receiveObserverEvents) -- must never be fallback-attached.
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "GET",
                url = "https://$host/sdk",
                host = host,
                path = "/sdk",
                statusCode = 200,
                timestamp = now + 5_000,
                connectionId = null,
            )
        )

        val chain = ConnectionLineage.build(context, sessionId, connectionId)
        val httpLabels = chain.steps.filter { it.label.startsWith("GET") }
        assertTrue("a connectionId-less row must never be attached via a window match", httpLabels.isEmpty())
        assertTrue(
            "must instead surface the honest 'no HTTP transaction' step",
            chain.steps.any { it.label == "No HTTP transaction observed" },
        )
    }

    @Test
    fun `every attached HTTP step comes from a row that actually carries the queried connectionId`() = runBlocking {
        val now = System.currentTimeMillis()
        seedTcpAndTls(now)

        val db = RequestrDatabase.get(context)
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "GET",
                url = "https://$host/a",
                host = host,
                path = "/a",
                statusCode = 200,
                timestamp = now + 10,
                connectionId = connectionId,
            )
        )
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "POST",
                url = "https://$host/b",
                host = host,
                path = "/b",
                statusCode = 201,
                timestamp = now + 20,
                connectionId = connectionId,
            )
        )

        val rows = db.capturedTrafficDao().getByConnectionId(connectionId)
        assertEquals(2, rows.size)
        assertTrue("every row returned by the exact join must carry the queried connectionId", rows.all { it.connectionId == connectionId })
    }
}
