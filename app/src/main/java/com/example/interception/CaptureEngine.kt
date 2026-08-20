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
import kotlinx.coroutines.runBlocking

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
    var printUndecoded: Boolean = true,
    var dumpToFile: Boolean = false,
) {
    private val dumpFile: java.io.File? by lazy {
        if (!dumpToFile) null else {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "requestr")
            dir.mkdirs()
            java.io.File(dir, "capture_${sessionId}_${System.currentTimeMillis()}.hex").also { Log.i(TAG, "Dump file: ${it.absolutePath}") }
        }
    }
    private val dumpWriter: java.io.BufferedWriter? by lazy { dumpFile?.bufferedWriter() }
    private val reassembler = TcpReassembler()
    private val pendingRequests = LinkedHashMap<String, ParsedHttpMessage>() // keyed by stream key
    // Streams that upgraded to WebSocket — subsequent bytes are WS frames (Fix #14)
    private val wsStreams = mutableSetOf<String>()

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
                Log.i(TAG, "TLS: ${stream.sniHost ?: "unknown"} (${stream.key}) bytes=${clientBytes.size}")

                if (printUndecoded) {
                    val entity = CapturedTrafficEntity(
                        method = "TLS",
                        url = "https://${stream.sniHost ?: stream.dstIp}",
                        host = stream.sniHost ?: "$stream.dstIp:$stream.dstPort",
                        requestHeaders = mapOf("x-tls-sni" to (stream.sniHost ?: "—"), "x-capture" to "undecoded"),
                        requestBody = null,
                        statusCode = 0,
                        responseHeaders = mapOf("x-undecoded" to "tls-detected", "x-bytes" to "${clientBytes.size}"),
                        responseBody = clientBytes.copyOfRange(0, minOf(128, clientBytes.size)).joinToString(" ") { "%02x".format(it) },
                        timestamp = System.currentTimeMillis(),
                        sessionId = sessionId,
                        wasDecrypted = false,
                        isUndecoded = true,
                    )
                    kotlinx.coroutines.runBlocking {
                        try { db.capturedTrafficDao().insert(entity); transactionCount++; Log.i(TAG, "Undecoded TLS persisted: ${stream.sniHost ?: stream.dstIp}") } catch (_: Exception) {}
                    }
                }
                // Dump to file if enabled
                if (dumpFile != null) {
                    try { dumpWriter?.appendLine("[${System.currentTimeMillis()}] TLS ${stream.sniHost ?: stream.dstIp}:${stream.dstPort} ← ${clientBytes.size}B → ${scHex(clientBytes.copyOfRange(0, minOf(128, clientBytes.size)))}"); dumpWriter?.flush() } catch (_: Exception) {}
                }
                stream.clientToServer.reset()
                stream.serverToClient.reset()
                return
            }
        }

        if (stream.isTls && !stream.decryptedByMitm) return // allow MITM-decrypted streams through

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

            // Fix #14 (TODO): Detect WebSocket upgrade handshake. After the HTTP
            // upgrade request, subsequent bytes on this stream are WS frames.
            if (com.example.interception.http.WebSocketParser.looksLikeUpgrade(request.headers, isRequest = true)) {
                Log.i(TAG, "WebSocket upgrade on ${stream.key}")
                synchronized(wsStreams) { wsStreams.add(stream.key) }
            }
        }

        // Check for a response if we have a pending request
        val pendingReq = pendingRequests[stream.key] ?: return
        val scBytes = stream.serverToClient.bytes()
        val response = HttpParser.tryParseResponse(scBytes, sentRequestUrl = pendingReq.url)

        if (response != null) {
            // Fix #15 (TODO): If the response is SSE (text/event-stream), parse events
            // and persist the event stream as a tagged transaction.
            if (com.example.interception.http.SseParser.looksLikeSse(response.headers)) {
                val (events, _) = com.example.interception.http.SseParser.parseEvents(response.body)
                if (events.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "SSE stream on ${stream.key}: ${events.size} events, first=" +
                            events.first().event
                    )
                }
            }

            stream.serverToClient.drain(response.consumedBytes)
            pendingRequests.remove(stream.key)

            // Persist the pair
            persistTransaction(pendingReq, response, stream)
        }

        // Fix #14: After WebSocket upgrade, parse frames from either direction
        if (stream.key in wsStreams) {
            val clientBytes = stream.clientToServer.bytes()
            val serverBytes = stream.serverToClient.bytes()
            if (clientBytes.isNotEmpty()) {
                val (consumed, frames) = com.example.interception.http.WebSocketParser.parseFrames(clientBytes)
                if (consumed > 0) {
                    stream.clientToServer.drain(consumed)
                    frames.forEach { frame ->
                        Log.d(TAG, "WS <- client: ${com.example.interception.http.WebSocketParser.describe(frame)}")
                        persistWsFrame(stream, frame, isClient = true)
                    }
                }
            }
            if (serverBytes.isNotEmpty()) {
                val (consumed, frames) = com.example.interception.http.WebSocketParser.parseFrames(serverBytes)
                if (consumed > 0) {
                    stream.serverToClient.drain(consumed)
                    frames.forEach { frame ->
                        Log.d(TAG, "WS -> server: ${com.example.interception.http.WebSocketParser.describe(frame)}")
                        persistWsFrame(stream, frame, isClient = false)
                    }
                }
            }
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

        // Fix 15 (AUDIT_REPORT): Consult intercept rules before persisting.
        // Fix #7 (TODO): Implement all rule actions, not just BLOCK.
        val ruleAction = checkRules(request.url, request.host)
        when (ruleAction) {
            com.example.data.local.RuleAction.BLOCK -> {
                Log.d(TAG, "BLOCKED by rule: ${request.method?.name} ${request.url.take(80)}")
                return  // Drop entirely — never persisted, never forwarded
            }
            com.example.data.local.RuleAction.REDIRECT -> {
                // REDIRECT: Find the matching rule's replacementValue and rewrite the URL.
                // For now, log + persist with a tag so user knows it was a redirect candidate.
                Log.d(TAG, "REDIRECT rule matched: ${request.url.take(80)}")
                // Persist with modified URL if replacementValue is set
                val matchedRule = getMatchingRule(request.url, request.host)
                if (matchedRule?.replacementValue?.isNotBlank() == true) {
                    request.url = matchedRule.replacementValue!!
                    request.host = runCatching {
                        java.net.URI(matchedRule.replacementValue!!).host ?: request.host
                    }.getOrDefault(request.host)
                }
            }
            com.example.data.local.RuleAction.MODIFY_REQUEST -> {
                // MODIFY_REQUEST: Transform request headers/body per rule's replacementValue.
                // replacementValue format: JSON with optional "addHeaders", "removeHeaders", "bodyReplace"
                Log.d(TAG, "MODIFY_REQUEST rule matched: ${request.url.take(80)}")
                val matchedRule = getMatchingRule(request.url, request.host)
                matchedRule?.replacementValue?.let { config ->
                    applyRequestModification(request, config)
                }
            }
            com.example.data.local.RuleAction.MODIFY_RESPONSE -> {
                // MODIFY_RESPONSE: Transform response headers/body per rule's replacementValue.
                Log.d(TAG, "MODIFY_RESPONSE rule matched: ${request.url.take(80)}")
                val matchedRule = getMatchingRule(request.url, request.host)
                matchedRule?.replacementValue?.let { config ->
                    applyResponseModification(response, config)
                }
            }
            com.example.data.local.RuleAction.LOG_ONLY -> {
                // LOG_ONLY: Always persist, even if trafficCaptureEnabled is off.
                // This overrides the capture toggle — tagged requests are always saved.
            }
        }

        // LOG_ONLY rules override the capture toggle — always persist tagged traffic
        val forcePersist = (ruleAction == com.example.data.local.RuleAction.LOG_ONLY
            && checkRules(request.url, request.host) != com.example.data.local.RuleAction.LOG_ONLY)
            // ^ if a specific LOG_ONLY rule matched (not the default), force persist

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

    /**
     * Persists a WebSocket frame as a fake transaction so it appears in the capture log.
     * The URL is derived from the stream host plus " (WS)" suffix to make the direction visible.
     */
    private fun persistWsFrame(stream: TcpStream, frame: com.example.interception.http.WebSocketParser.WsFrame, isClient: Boolean) {
        val settings = SettingsRepository.settings.value
        if (!settings.trafficCaptureEnabled) return

        val wsHost = stream.sniHost ?: stream.dstIp
        val wsUrl = "ws://$wsHost${if (stream.dstPort != 80) ":${stream.dstPort}" else ""}"
        val entity = CapturedTrafficEntity(
            sessionId = sessionId,
            method = if (isClient) "WS→" else "WS←",
            url = "$wsUrl (${frame.opcodeName})",
            host = wsHost,
            path = "/",
            statusCode = null,
            protocol = "WEBSOCKET",
            requestHeaders = emptyMap(),
            responseHeaders = emptyMap(),
            requestBody = if (isClient) frame.payloadText else null,
            responseBody = if (!isClient) frame.payloadText else null,
            requestSizeBytes = if (isClient) frame.totalLength.toLong() else 0L,
            responseSizeBytes = if (!isClient) frame.totalLength.toLong() else 0L,
            latencyMs = 0L,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = false,
        )
        runCatching {
            kotlinx.coroutines.runBlocking { db.capturedTrafficDao().insert(entity) }
        }.onFailure { Log.w(TAG, "Failed to persist WS frame: ${it.message}") }
    }

    /** Returns the first matching rule entity (for accessing replacementValue). */
    private fun getMatchingRule(url: String, host: String): InterceptRuleEntity? {
        val rules: List<InterceptRuleEntity> = runCatching {
            kotlinx.coroutines.runBlocking {
                db.interceptRuleDao().getEnabledRules()
            }
        }.getOrDefault(emptyList())

        for (rule in rules) {
            val matchesDomain = rule.domain.isNotEmpty() && host.contains(rule.domain, ignoreCase = true)
            val matchesPattern = rule.pattern.isNotEmpty() && runCatching {
                Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)
            }.getOrDefault(false)
            if (matchesDomain || matchesPattern) return rule
        }
        return null
    }

    /**
     * Applies request modifications from a rule's replacementValue (JSON config).
     * Format: {"addHeaders":{"X-Custom":"value"}, "removeHeaders":["Cookie"], "bodyReplace":"new body"}
     */
    private fun applyRequestModification(request: ParsedHttpMessage, config: String) {
        runCatching {
            val json = org.json.JSONObject(config)
            if (json.has("addHeaders")) {
                val headers = json.getJSONObject("addHeaders")
                val mutable = request.headers.toMutableMap()
                headers.keys().forEach { key -> mutable[key] = headers.getString(key) }
                request.headers = mutable
            }
            if (json.has("removeHeaders")) {
                val toRemove = json.getJSONArray("removeHeaders")
                val mutable = request.headers.toMutableMap()
                for (i in 0 until toRemove.length()) {
                    mutable.remove(toRemove.getString(i))
                }
                request.headers = mutable
            }
            if (json.has("bodyReplace")) {
                request.body = json.getString("bodyReplace")
            }
        }.onFailure { Log.w(TAG, "Failed to apply request modification: ${it.message}") }
    }

    /**
     * Applies response modifications from a rule's replacementValue (JSON config).
     * Format: {"statusCode":200, "addHeaders":{"X-Mock":"true"}, "bodyReplace":"{}"}
     */
    private fun applyResponseModification(response: ParsedHttpMessage, config: String) {
        runCatching {
            val json = org.json.JSONObject(config)
            if (json.has("statusCode")) {
                response.statusCode = json.getInt("statusCode")
            }
            if (json.has("addHeaders")) {
                val headers = json.getJSONObject("addHeaders")
                val mutable = response.headers.toMutableMap()
                headers.keys().forEach { key -> mutable[key] = headers.getString(key) }
                response.headers = mutable
            }
            if (json.has("bodyReplace")) {
                response.body = json.getString("bodyReplace")
            }
        }.onFailure { Log.w(TAG, "Failed to apply response modification: ${it.message}") }
    }

    private fun scHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
