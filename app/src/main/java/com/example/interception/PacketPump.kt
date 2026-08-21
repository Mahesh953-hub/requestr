package com.example.interception

import android.util.Log
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.QuicConnectionEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.UdpFlowEntity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Reads real IP packets off the tun file descriptor, feeds them through the
 * [CaptureEngine] pipeline, and reports genuine counters.
 *
 * **Pipeline (completed):**
 * 1. Parses actual IPv4/IPv6 headers
 * 2. Tracks distinct 5-tuple flows as "active connections"
 * 3. [CaptureEngine] handles TCP stream reassembly + HTTP/1.x parsing → Room
 * 4. TLS ClientHello SNI extraction (flag-only; no decryption without MITM)
 * 5. Periodic flush of real counters into [InterceptionStateRepository] + session row
 *
 * **Not yet built (next increment):**
 * - TLS MITM proxy that presents our CA to the client, connects to the real
 *   server, and decrypts application data on both sides
 *
 * Deliberate design rule: this class never invents a transaction. If the pump cannot decode a real
 * HTTP exchange, it records nothing in the traffic table rather than writing a plausible-looking
 * row. Counters reflect only bytes actually observed.
 */
class PacketPump(
    private val input: FileInputStream,
    private val output: FileOutputStream,
    private val sessionId: Long,
    private val db: RequestrDatabase,
    /** Called for each parsed DNS query (Fix #16). */
    var dnsCallback: ((com.example.interception.dns.DnsParser.DnsQuery, String, Int, String, Int) -> Unit)? = null,
    printUndecoded: Boolean = true,
    dumpToFile: Boolean = false,
    captureEnabled: Boolean = true,
) {

    private var packetCount = 0
    private var byteCount = 0L

    /** Distinct flows seen recently, keyed by 5-tuple, with last-seen timestamps for expiry. */
    private val activeFlows = LinkedHashMap<String, Long>()

    /** M1: last time (ms) each UDP flow / QUIC connection row was upserted, to throttle writes. */
    private val lastUdpPersist = mutableMapOf<String, Long>()
    private val quicConnections = mutableSetOf<String>()

    /** TCP reassembly + HTTP/TLS pipeline. Feeds decoded transactions into Room. */
    private val captureEngine = CaptureEngine(db, sessionId, printUndecoded, dumpToFile, captureEnabled)

    suspend fun run(scope: CoroutineScope) {
        val buffer = ByteBuffer.allocate(MAX_PACKET)

        // Periodic flush so the UI shows live progress without thrashing the DB per packet.
        val flusher = scope.launch { flushLoop() }

        try {
            withContext(Dispatchers.IO) {
                while (scope.isActive) {
                    buffer.clear()
                    val read = try {
                        input.read(buffer.array())
                    } catch (e: Exception) {
                        if (!scope.isActive) break
                        Log.w(TAG, "tun read error: ${e.message}")
                        break
                    }

                    if (read <= 0) {
                        // No data available; yield briefly rather than spinning hot.
                        delay(IDLE_BACKOFF_MS)
                        continue
                    }

                    packetCount++
                    byteCount += read

                    parseAndTrackFlow(buffer.array(), read)
                }
            }
        } finally {
            flusher.cancel()
            flushNow()
        }
    }

    /**
     * Minimal real IP header parse. We extract version, protocol and addresses to identify the
     * flow. This is genuine packet inspection, not a placeholder.
     */
    private fun parseAndTrackFlow(packet: ByteArray, length: Int) {
        if (length < 20) return

        val version = (packet[0].toInt() shr 4) and 0xF
        when (version) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> return
        }

        // Route to the capture engine: TCP reassembly → HTTP → Room.
        captureEngine.ingest(packet, length, version)
    }

    private fun parseIpv4(packet: ByteArray, length: Int) {
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (length < ihl + 4) return

        val protocol = packet[9].toInt() and 0xFF
        val src = "${packet[12].u()}.${packet[13].u()}.${packet[14].u()}.${packet[15].u()}"
        val dst = "${packet[16].u()}.${packet[17].u()}.${packet[18].u()}.${packet[19].u()}"

        // Ports only exist for TCP(6)/UDP(17).
        val (sPort, dPort) = if (protocol == 6 || protocol == 17) {
            val s = ((packet[ihl].u() shl 8) or packet[ihl + 1].u())
            val d = ((packet[ihl + 2].u() shl 8) or packet[ihl + 3].u())
            s to d
        } else 0 to 0

        // Fix #16 (TODO): intercept DNS queries on UDP port 53.
        if (protocol == 17 && (sPort == 53 || dPort == 53)) {
            val udpPayload = packet.copyOfRange(ihl + 8, length)
            val query = com.example.interception.dns.DnsParser.parse(udpPayload, sPort, dPort)
            query?.let { dns ->
                Log.d(TAG, "DNS ${dns.qtypeName}: ${dns.hostname} (${if (dns.isResponse) "response" else "query"})")
                dnsCallback?.invoke(dns, src, sPort, dst, dPort)
            }
        }

        // Fix #26 (TODO): extract SNI from QUIC Initial packets on UDP port 443.
        // M1/P2: persist a QuicConnectionEntity whenever we successfully decode a QUIC Initial's
        // SNI — this is real session identity (5-tuple + SNI + version), not a placeholder.
        if (protocol == 17 && (sPort == 443 || dPort == 443)) {
            val quicPayload = packet.copyOfRange(ihl + 8, length)
            val sni = com.example.interception.quic.QuicParser.extractSni(quicPayload)
            if (sni != null) {
                Log.i(TAG, "QUIC SNI: $sni (${if (dPort == 443) "outbound" else "inbound"})")
                persistQuicConnection(src, sPort, dst, dPort, sni, quicPayload)
            }
        }

        // M1: general UDP flow tracking (byte counters), covering all UDP traffic including
        // DNS/QUIC above -- those get the specialized entities too, but every UDP 5-tuple gets
        // a udp_flows row so "Connections" surfaces the full picture.
        if (protocol == 17) {
            persistUdpFlow(src, sPort, dst, dPort, length - ihl - 8)
        }

        trackFlow("$protocol|$src:$sPort>$dst:$dPort")
    }

    /** M1: upsert a udp_flows row, throttled per 5-tuple so we don't hit Room every packet. */
    private fun persistUdpFlow(src: String, sPort: Int, dst: String, dPort: Int, payloadLen: Int) {
        val flowId = "17|$src:$sPort>$dst:$dPort"
        val now = System.currentTimeMillis()
        val last = lastUdpPersist[flowId]
        if (last != null && now - last < UDP_PERSIST_INTERVAL_MS) return
        lastUdpPersist[flowId] = now
        val entity = UdpFlowEntity(
            flowId = flowId,
            sessionId = sessionId,
            srcIp = src, destIp = dst,
            srcPort = sPort, destPort = dPort,
            transportProtocol = if (sPort == 443 || dPort == 443) "quic" else if (sPort == 53 || dPort == 53) "dns" else "udp",
            startedAt = now,
            byteCount = payloadLen.toLong().coerceAtLeast(0),
        )
        runCatching {
            runBlocking { db.udpFlowDao().upsert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist UDP flow: ${e.message}") }
    }

    /** M1/P2: upsert a quic_connections row once per connection (identified by 5-tuple). */
    private fun persistQuicConnection(src: String, sPort: Int, dst: String, dPort: Int, sni: String, quicPayload: ByteArray) {
        val connectionId = "quic|$src:$sPort>$dst:$dPort"
        if (!quicConnections.add(connectionId)) return // already persisted for this 5-tuple
        val version = if (quicPayload.size >= 5) {
            ((quicPayload[1].toInt() and 0xFF) shl 24) or
                ((quicPayload[2].toInt() and 0xFF) shl 16) or
                ((quicPayload[3].toInt() and 0xFF) shl 8) or
                (quicPayload[4].toInt() and 0xFF)
        } else null
        val entity = QuicConnectionEntity(
            connectionId = connectionId,
            sessionId = sessionId,
            srcIp = src, destIp = dst,
            srcPort = sPort, destPort = dPort,
            sniHost = sni,
            alpn = null, // ALPN extension parsing not yet extracted from the ClientHello (P2 follow-up)
            quicVersion = version,
            dstConnectionId = connectionId,
            establishedAt = System.currentTimeMillis(),
        )
        runCatching {
            runBlocking { db.quicConnectionDao().upsert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist QUIC connection: ${e.message}") }
    }

    private fun parseIpv6(packet: ByteArray, length: Int) {
        if (length < 40) return
        val nextHeader = packet[6].toInt() and 0xFF
        val src = packet.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }
        val dst = packet.copyOfRange(24, 40).joinToString("") { "%02x".format(it) }

        val (sPort, dPort) = if ((nextHeader == 6 || nextHeader == 17) && length >= 44) {
            val s = ((packet[40].u() shl 8) or packet[41].u())
            val d = ((packet[42].u() shl 8) or packet[43].u())
            s to d
        } else 0 to 0

        trackFlow("$nextHeader|$src:$sPort>$dst:$dPort")
    }

    private fun trackFlow(key: String) {
        val now = System.currentTimeMillis()
        activeFlows[key] = now
        if (activeFlows.size > MAX_TRACKED_FLOWS) {
            val cutoff = now - FLOW_TTL_MS
            activeFlows.entries.removeAll { it.value < cutoff }
        }
    }

    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            flushNow()
        }
    }

    private suspend fun flushNow() {
        val now = System.currentTimeMillis()
        val cutoff = now - FLOW_TTL_MS
        activeFlows.entries.removeAll { it.value < cutoff }
        val connections = activeFlows.size

        captureEngine.prune()

        // M1: bound the UDP/QUIC bookkeeping maps -- drop entries for flows no longer active so
        // long-running sessions don't leak memory keyed by every 5-tuple ever seen.
        lastUdpPersist.entries.removeAll { it.value < cutoff }
        if (quicConnections.size > MAX_TRACKED_FLOWS) quicConnections.clear()

        InterceptionStateRepository.updateCounters(
            packetCount = packetCount,
            bytesTransferred = byteCount,
            activeConnections = connections,
        )

        val transactions = captureEngine.transactionCount

        runCatching {
            db.captureSessionDao().updateCounters(
                id = sessionId,
                packets = packetCount,
                bytes = byteCount,
                transactions = transactions,
            )
        }
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF

    private companion object {
        const val TAG = "PacketPump"
        const val MAX_PACKET = 32767
        const val IDLE_BACKOFF_MS = 10L
        const val FLUSH_INTERVAL_MS = 1000L
        const val FLOW_TTL_MS = 30_000L
        const val MAX_TRACKED_FLOWS = 512
        const val UDP_PERSIST_INTERVAL_MS = 2_000L
    }
}
