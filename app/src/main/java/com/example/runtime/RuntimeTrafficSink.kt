package com.example.runtime

import android.content.Context
import android.util.Log
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.CaptureSessionRegistry
import com.example.data.local.CaptureTier
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * W1: a [RuntimeCaptureSink] that maps every [InstrumentationEvent] carrying a non-null [url]
 * into a real [CapturedTrafficEntity] row and inserts it into Room, so runtime-plane captures
 * (Cronet, WebView, and any future in-process adapter) show up in the same traffic list as
 * VPN/root/MITM-captured traffic instead of only participating in [com.example.correlation.CorrelationEngine].
 *
 * Mirrors the field mapping [com.example.api.LocalApiServer.receiveObserverEvents] already uses
 * for SDK-sourced rows (host/path derived via [java.net.URI], `wasDecrypted = true` -- runtime
 * adapters observe the plaintext request/response inside this process, so there is nothing to
 * decrypt) and gates request/response bodies on [SettingsDataStore]'s logging toggles exactly
 * as [com.example.interception.MitmProxyEngine.emitTransaction] does.
 *
 * Session id: a single long-lived [CaptureTier.RUNTIME] session row, reused across the process
 * lifetime via [CaptureSessionRegistry] (FIX D: this used to be its own hand-rolled
 * `@Volatile` + [kotlinx.coroutines.sync.Mutex] cache, the only one of three near-duplicate
 * copies of this pattern that was actually correct -- consolidated so the other two,
 * [com.example.api.LocalApiServer.sdkSessionId] and
 * [com.example.interception.gateway.GatewayEngine.gatewaySessionId], get the same correctness
 * instead of each maintaining (or failing to maintain) their own cache).
 */
class RuntimeTrafficSink(private val context: Context) : RuntimeCaptureSink {

    private val tag = "RuntimeTrafficSink"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEvents(events: List<InstrumentationEvent>) {
        val withUrl = events.filter { !it.url.isNullOrBlank() }
        if (withUrl.isEmpty()) return
        scope.launch {
            runCatching { persist(withUrl) }
                .onFailure { Log.w(tag, "Failed to persist runtime traffic: ${it.message}") }
        }
    }

    private suspend fun persist(events: List<InstrumentationEvent>) {
        val db = RequestrDatabase.get(context)
        val dao = db.capturedTrafficDao()
        val sessionId = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.RUNTIME)
        // Fix C: SettingsDataStore, not SettingsRepository.settings.value -- this sink can run
        // before anything in-process has called SettingsRepository.init() (same failure mode
        // documented at RequestrVpnService's prefs.requestLogging.first() read), in which case
        // SettingsRepository silently returns hardcoded `true` defaults regardless of what the
        // user actually configured.
        val prefs = SettingsDataStore.get(context)
        val logRequests = prefs.requestLogging.first()
        val logResponses = prefs.responseLogging.first()
        for (e in events) {
            val url = e.url ?: continue
            val host = e.host ?: runCatching { java.net.URI(url).host }.getOrNull() ?: url
            val path = runCatching { java.net.URI(url).path }.getOrNull() ?: ""
            val entity = CapturedTrafficEntity(
                sessionId = sessionId,
                method = e.method ?: "GET",
                url = url,
                host = host,
                path = path.orEmpty(),
                statusCode = e.statusCode,
                protocol = e.protocol.orEmpty(),
                requestHeaders = if (logRequests) e.headers.orEmpty() else emptyMap(),
                responseHeaders = emptyMap(),
                requestBody = if (logRequests) e.requestBody else null,
                responseBody = if (logResponses) e.responseBody else null,
                requestSizeBytes = e.requestSizeBytes,
                responseSizeBytes = e.responseSizeBytes,
                latencyMs = e.latencyMs,
                timestamp = e.timestamp,
                wasDecrypted = true,
                connectionId = e.connectionId,
            )
            dao.insert(entity)
        }
    }
}
