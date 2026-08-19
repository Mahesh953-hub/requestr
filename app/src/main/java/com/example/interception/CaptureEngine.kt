package com.example.interception

import android.util.Log
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.repository.SettingsRepository
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.tcp.TcpReassembler
import com.example.interception.tcp.TcpStream
import com.example.interception.tls.TlsDecoder

/**
 * Orchestrates the pipeline: raw tun packets → TCP reassembly → HTTP/TLS
 * detection → Room persistence.
 *
 * This class is driven by [PacketPump] each time a packet is read from the
 * tun fd. It handles the "what do we do with this packet now? " logic that
 * turns raw IP into captured transactions.
 *
 * **Current capabilities:**
 * - Plaintext HTTP/1.x request-response pairing → persisted to `captured_traffic`
 * - TLS ClientHello SNI extraction → logs the hostname
 *
 * **Not yet built (next increment):**
 * - TLS MITM (decrypting application data requires a proxy that presents our
 *   CA to the client and connects to the real server)
 * - HTTP/2 frame parsing
 * - WebSocket message capture
 */
class CaptureEngine(
    private val db: RequestrDatabase,
    private val sessionId: Long,
) {
    private val reassembler = TcpReassembler()
    private val pendingRequests = LinkedHashMap<String, ParsedHttpMessage>() // keyed by stream key

    /** Transactions persisted this session. */
    var transactionCount = 0
        private set

    // ── entry point ─────────────────────────────────────────────────────

    /**
     * Feed one raw IP packet into the engine. Call from PacketPump's read loop.
     *
     * @param packet  complete IP datagram
     * @param length  bytes read
     * @param version 4 or 6
     */
    fun ingest(packet: ByteArray, length: Int, version: Int) {
        val stream = reassembler.feed(packet, length, version) ?: return

        // Only inspect streams that have some data buffered
        if (stream.clientToServer.isEmpty() && stream.serverToClient.isEmpty()) return

        // TLS detection: check once per stream
        if (!stream.isTls && stream.clientToServer.size() >= 5) {
            val clientBytes = stream.clientToServer.bytes()
            if (TlsDecoder.looksLikeTls(clientBytes)) {
                stream.isTls = true
                stream.sniHost = TlsDecoder.extractSni(clientBytes)
                if (stream.sniHost != null) {
                    Log.i(TAG, "TLS: ${stream.sniHost} (${stream.key})")
                    // We can't decrypt TLS without MITM. Record the SNI observation
                    // but do NOT fabricate a transaction — the log stays empty for TLS
                    // until the MITM layer is built.
                }
                // Once identified as TLS, clear the buffers — we can't parse HTTP from it
                stream.clientToServer.reset()
                stream.serverToClient.reset()
                return
            }
        }

        if (stream.isTls) return // not inspecting TLS streams further (no MITM yet)

        // ── HTTP detection ───────────────────────────────────────────────
        val csBytes = stream.clientToServer.bytes()
        val request = HttpParser.tryParseRequest(
            bytes = csBytes,
            host = "${stream.dstIp}:${stream.dstPort}",
            port = stream.dstPort,
            isHttps = false, // TLS streams are caught above; this path is plaintext
        )

        if (request != null) {
            stream.clientToServer.drain(request.consumedBytes)
            // Store the pending request; wait for the response
            pendingRequests[stream.key] = request
        }

        // Check for a response if we have a pending request
        val pendingReq = pendingRequests[stream.key] ?: return
        val scBytes = stream.serverToClient.bytes()
        val response = HttpParser.tryParseResponse(scBytes, sentRequestUrl = pendingReq.url)

        if (response != null) {
            stream.serverToClient.drain(response.consumedBytes)
            pendingRequests.remove(stream.key)

            // Persist the pair
            persistTransaction(pendingReq, response, stream)
        }
    }

    // ── persistence ─────────────────────────────────────────────────────

    private fun persistTransaction(
        request: ParsedHttpMessage,
        response: ParsedHttpMessage,
        stream: TcpStream,
    ) {
        // B9(b): the three "Traffic & Network" Settings switches now gate what this,
        // the one real persistence path for captured HTTP transactions, actually writes —
        // previously these switches had zero consumers anywhere in the codebase.
        val settings = SettingsRepository.settings.value
        if (!settings.trafficCaptureEnabled) {
            // Traffic capture disabled entirely: record nothing, matching the toggle's
            // stated effect ("Capture network traffic for inspection").
            return
        }

        val entity = CapturedTrafficEntity(
            sessionId = sessionId,
            method = (request.method ?: com.example.data.models.HttpMethod.GET).name,
            url = request.url,
            host = request.host,
            path = request.path,
            statusCode = if (response.statusCode == 0) null else response.statusCode,
            protocol = response.version.ifEmpty { request.version },
            requestHeaders = if (settings.requestLoggingEnabled) request.headers else emptyMap(),
            responseHeaders = if (settings.responseLoggingEnabled) response.headers else emptyMap(),
            requestBody = if (settings.requestLoggingEnabled) request.body.ifEmpty { null } else null,
            responseBody = if (settings.responseLoggingEnabled) response.body.ifEmpty { null } else null,
            requestSizeBytes = request.body.toByteArray(Charsets.UTF_8).size.toLong(),
            responseSizeBytes = response.body.toByteArray(Charsets.UTF_8).size.toLong(),
            latencyMs = 0L, // without proxy we don't have timing between request/response
            timestamp = System.currentTimeMillis(),
            wasDecrypted = false,
        )

        runCatching {
            kotlinx.coroutines.runBlocking {
                db.capturedTrafficDao().insert(entity)
            }
            transactionCount++
            Log.d(TAG, "Captured: ${response.statusCode} ${request.method?.name} ${request.url.take(120)}")
        }.onFailure { e ->
            Log.w(TAG, "Failed to persist transaction: ${e.message}")
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    /** Prune idle streams and stale pending requests. Call periodically. */
    fun prune() {
        reassembler.prune()
        // Remove pending requests for streams that no longer exist
        val activeKeys = reassembler.streams.keys
        pendingRequests.keys.removeAll { it !in activeKeys }
    }

    fun streamCount(): Int = reassembler.streamCount()

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
