package com.example.runtime

import android.content.Context
import android.util.Log
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CaptureTier
import com.example.data.local.RequestrDatabase
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * W1: a [RuntimeCaptureSink] that maps every [InstrumentationEvent] carrying a non-null [url]
 * into a real [CapturedTrafficEntity] row and inserts it into Room, so runtime-plane captures
 * (Cronet, WebView, and any future in-process adapter) show up in the same traffic list as
 * VPN/root/MITM-captured traffic instead of only participating in [com.example.correlation.CorrelationEngine].
 *
 * Mirrors the field mapping [com.example.api.LocalApiServer.receiveObserverEvents] already uses
 * for SDK-sourced rows (host/path derived via [java.net.URI], `wasDecrypted = true` -- runtime
 * adapters observe the plaintext request/response inside this process, so there is nothing to
 * decrypt) and gates request/response bodies on [SettingsRepository]'s logging toggles exactly
 * as [com.example.interception.MitmProxyEngine.emitTransaction] does.
 *
 * Session id: a single long-lived [CaptureTier.RUNTIME] session row, reused across the process
 * lifetime -- same lazy-create-once pattern as [com.example.api.LocalApiServer.sdkSessionId].
 */
class RuntimeTrafficSink(private val context: Context) : RuntimeCaptureSink {

    private val tag = "RuntimeTrafficSink"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionMutex = Mutex()
    @Volatile private var cachedSessionId: Long? = null

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
        val sessionId = runtimeSessionId(db)
        val logRequests = SettingsRepository.settings.value.requestLoggingEnabled
        val logResponses = SettingsRepository.settings.value.responseLoggingEnabled
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
                // NOTE (merge reconciliation): CapturedTrafficEntity.connectionId is owned by the
                // transport scope (Room schema v9). Once that column lands, add
                // `connectionId = e.connectionId` here so runtime-captured rows join lineage the
                // same way every other producer does.
            )
            dao.insert(entity)
        }
    }

    private suspend fun runtimeSessionId(db: RequestrDatabase): Long {
        cachedSessionId?.let { return it }
        return sessionMutex.withLock {
            cachedSessionId?.let { return it }
            val sessionDao = db.captureSessionDao()
            val existing = sessionDao.observeAll().first()
                .firstOrNull { it.tier == CaptureTier.RUNTIME && it.endedAt == null }
            val id = existing?.id ?: sessionDao.insert(CaptureSessionEntity(tier = CaptureTier.RUNTIME))
            cachedSessionId = id
            id
        }
    }
}
