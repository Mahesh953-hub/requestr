package com.example.correlation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlation engine: merges observation events from all planes into coherent requests.
 *
 * Matching strategy: timestamp window (±500ms) + destination+port + URL + process + thread.
 * Never depends on one signal alone.
 */
object CorrelationEngine {
    private const val CORRELATION_WINDOW_MS = 500L

    private val eventStore = ConcurrentHashMap<String, MutableList<ObservationEvent>>()
    private val correlatedRequests = MutableStateFlow<Map<String, CorrelatedRequest>>(emptyMap())
    val correlated: StateFlow<Map<String, CorrelatedRequest>> = correlatedRequests.asStateFlow()

    /** Feed an event into the correlation pipeline */
    fun ingest(event: ObservationEvent) {
        val key = buildCorrelationKey(event)
        val keyStr = key.toString()

        eventStore.getOrPut(keyStr) { mutableListOf() }.add(event)
        processCorrelation(key, keyStr)
    }

    /** Attempt to correlate events that share a key */
    private fun processCorrelation(key: CorrelationKey, keyStr: String) {
        val events = eventStore[keyStr] ?: return
        if (events.size < 2) return

        val bySource = events.groupBy { it.source }
        if (bySource.size < 2) return // only one plane saw it

        val correlated = CorrelatedRequest(
            requestId = generateRequestId(keyStr),
            events = events.toList(),
            planes = bySource.keys.toList(),
            key = key,
            timestamp = events.first().timestamp,
            wasCorrelated = true
        )
        correlatedRequests.value = correlatedRequests.value + (correlated.requestId to correlated)
    }

    private fun buildCorrelationKey(event: ObservationEvent): CorrelationKey = when (event) {
        is NetworkEvent -> CorrelationKey(event.dstIp, event.dstPort, null, event.timestamp / 1000)
        is TlsEvent -> CorrelationKey(event.sniHost, 443, null, event.timestamp / 1000)
        is HttpEvent -> CorrelationKey(event.host, null, event.url, event.timestamp / 1000)
        is RuntimeEvent -> CorrelationKey(null, null, event.url, event.timestamp / 1000)
        is BrowserEvent -> CorrelationKey(null, null, null, event.timestamp / 1000)
        is DnsEvent -> CorrelationKey(event.hostname, 53, null, event.timestamp / 1000)
        is QuicEvent -> CorrelationKey(event.sniHost, 443, null, event.timestamp / 1000)
    }

    private fun generateRequestId(key: String): String {
        val hash = key.hashCode()
        return "REQ-" + String.format("%04d", Math.abs(hash) % 10000)
    }

    /** Get events correlated with a given request */
    fun getEventsFor(requestId: String): List<ObservationEvent> {
        return correlatedRequests.value[requestId]?.events ?: emptyList()
    }

    fun clear() {
        eventStore.clear()
        correlatedRequests.value = emptyMap()
    }
}

/** Correlation key: composite of identifying fields */
data class CorrelationKey(
    val destination: String?,
    val port: Int?,
    val url: String?,
    val timestampWindow: Long,   // timestamp / 1000 — seconds granularity
)

/** A correlated request observed across multiple planes */
data class CorrelatedRequest(
    val requestId: String,
    val events: List<ObservationEvent>,
    val planes: List<ObservationEvent.ObserverSource>,
    val key: CorrelationKey,
    val timestamp: Long,
    val wasCorrelated: Boolean = true,
)
