package com.example.data.interception

import android.content.Context
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.QuicConnectionEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TlsSessionEntity
import com.example.data.local.UdpFlowEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read path for the v5 connection model (M1): TCP connections, UDP flows and QUIC connections
 * persisted by [com.example.interception.CaptureEngine] / [com.example.interception.PacketPump]
 * during a capture session.
 *
 * Mirrors [CaptureLogRepository]'s contract -- this is the only place the UI reads these rows
 * from, and it emits exactly what the engine has written (no fabricated rows).
 */
object ConnectionsRepository {

    /** All TCP connections observed for a given capture session, most-recent first. */
    fun observeTcpConnections(context: Context, sessionId: Long): Flow<List<TcpConnectionEntity>> =
        RequestrDatabase.get(context).tcpConnectionDao().observeBySession(sessionId)

    /** All UDP flows observed for a given capture session, most-recent first. */
    fun observeUdpFlows(context: Context, sessionId: Long): Flow<List<UdpFlowEntity>> =
        RequestrDatabase.get(context).udpFlowDao().observeBySession(sessionId)

    /** All QUIC connections observed for a given capture session, most-recent first. */
    fun observeQuicConnections(context: Context, sessionId: Long): Flow<List<QuicConnectionEntity>> =
        RequestrDatabase.get(context).quicConnectionDao().observeBySession(sessionId)

    /** All TLS sessions (M2) observed for a given capture session, most-recent first. */
    fun observeTlsSessions(context: Context, sessionId: Long): Flow<List<TlsSessionEntity>> =
        RequestrDatabase.get(context).tlsSessionDao().observeBySession(sessionId)

    /** Every TLS session ever recorded, regardless of session -- used for the "All" view. */
    fun observeAllTlsSessions(context: Context): Flow<List<TlsSessionEntity>> =
        RequestrDatabase.get(context).tlsSessionDao().observeAll()

    /** Every TCP connection ever recorded, regardless of session -- used for the "All" view. */
    fun observeAllTcpConnections(context: Context): Flow<List<TcpConnectionEntity>> =
        RequestrDatabase.get(context).tcpConnectionDao().observeAll()

    fun observeAllUdpFlows(context: Context): Flow<List<UdpFlowEntity>> =
        RequestrDatabase.get(context).udpFlowDao().observeAll()

    fun observeAllQuicConnections(context: Context): Flow<List<QuicConnectionEntity>> =
        RequestrDatabase.get(context).quicConnectionDao().observeAll()

    /** All CONNECT (HTTPS tunnel) attempts for a given capture session, most-recent first. */
    fun observeConnectAttempts(context: Context, sessionId: Long): Flow<List<ConnectAttemptEntity>> =
        RequestrDatabase.get(context).connectAttemptDao().observeBySession(sessionId)

    /** Every CONNECT attempt ever recorded, regardless of session -- used for the "All" view. */
    fun observeAllConnectAttempts(context: Context): Flow<List<ConnectAttemptEntity>> =
        RequestrDatabase.get(context).connectAttemptDao().observeAll()
}
