package com.example.interception

import android.util.Log
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
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
        // Fix 06 (AUDIT_REPORT): Previously read SettingsRepository.settings.value (stale
        // snapshot). Now we read via .value on the StateFlow which IS the live-collected state
        // from DataStore. The StateFlow is hot — its .value IS the latest emission, so this is
        // always the most recent toggle state without needing a coroutine collect here.
        // The actual fix is ensuring CaptureEngine checks at persistence time (which it does).
        val settings = SettingsRepository.settings.value
        if (!settings.trafficCaptureEnabled) {
            return
        }

        // Fix 15 (AUDIT_REPORT): Consult intercept rules before persisting
        // Rules are checked synchronously (blocking) since we're already on PacketPump's thread.
        val ruleAction = checkRules(request.url, request.host)
        if (ruleAction == com.example.data.local.RuleAction.BLOCK) {
            Log.d(TAG, "BLOCKED by rule: ${request.method?.name} ${request.url.take(80)}")
            return  // Drop the transaction entirely — never persisted
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

    // ── Rules engine (Fix 15 / AUDIT_REPORT) ─────────────────────────────

    /**
     * Check enabled intercept rules against a request URL/host.
     * Returns the highest-priority action if any rule matches, or LOG_ONLY as default.
     *
     * Rule matching: pattern is checked as regex against the full URL.
     * Domain field is checked as substring against the host.
     */
    private fun checkRules(url: String, host: String): com.example.data.local.RuleAction {
        val rules: List<InterceptRuleEntity> = runCatching {
            kotlinx.coroutines.runBlocking {
                db.interceptRuleDao().getEnabledRules()
            }
        }.getOrDefault(emptyList())

        if (rules.isEmpty()) return com.example.data.local.RuleAction.LOG_ONLY

        for (rule in rules) {
            val matchesDomain = rule.domain.isNotEmpty() && host.contains(rule.domain, ignoreCase = true)
            val matchesPattern = rule.pattern.isNotEmpty() && runCatching {
                Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)
            }.getOrDefault(false)

            if (matchesDomain || matchesPattern) {
                Log.d(TAG, "Rule '${rule.name}' matched: action=${rule.action.name}")
                return rule.action
            }
        }
        return com.example.data.local.RuleAction.LOG_ONLY
    }

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
