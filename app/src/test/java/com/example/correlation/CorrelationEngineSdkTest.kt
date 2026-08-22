package com.example.correlation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 10: SDK-sourced events (`ObservationEvent.ObserverSource.SDK`, emitted by
 * `LocalApiServer.receiveObserverEvents` as [RuntimeEvent]s) must flow through
 * [CorrelationEngine] exactly like every other observation plane -- no special-casing
 * needed since [CorrelationEngine.buildCorrelationKey] already keys [RuntimeEvent]s by URL
 * regardless of `source`.
 */
class CorrelationEngineSdkTest {

    @After
    fun tearDown() {
        CorrelationEngine.clear()
    }

    @Test
    fun `ingesting a single SDK RuntimeEvent does not throw and is retrievable once correlated`() {
        val sharedUrl = "https://api.example.com/v1/widgets"
        val timestamp = 1_700_000_000_000L

        val sdkEvent = RuntimeEvent(
            eventId = "sdk-1",
            timestamp = timestamp,
            source = ObservationEvent.ObserverSource.SDK,
            connectionId = null,
            processName = "com.thirdparty.app",
            pid = 1234,
            uid = 10_099,
            eventType = "request_started",
            method = "GET",
            url = sharedUrl,
            stackFrames = emptyList(),
            adapterType = "okhttp",
        )

        // A single event on its own plane never correlates (processCorrelation requires >=2
        // planes) -- ingest() itself must not throw regardless.
        CorrelationEngine.ingest(sdkEvent)

        // A second RuntimeEvent from a different observation plane, sharing the same
        // correlation key (RuntimeEvent's key is URL + 1-second timestamp window, independent
        // of `source` -- see CorrelationEngine.buildCorrelationKey), triggers correlation and
        // lets us assert the SDK event was actually stored and surfaced.
        val runtimeEvent = RuntimeEvent(
            eventId = "runtime-1",
            timestamp = timestamp + 10,
            source = ObservationEvent.ObserverSource.RUNTIME,
            connectionId = null,
            processName = "com.thirdparty.app",
            pid = 1234,
            uid = 10_099,
            eventType = "response_ready",
            method = "GET",
            url = sharedUrl,
            stackFrames = emptyList(),
            adapterType = "frida",
        )
        CorrelationEngine.ingest(runtimeEvent)

        val correlated = CorrelationEngine.correlated.value.values
            .firstOrNull { it.planes.contains(ObservationEvent.ObserverSource.SDK) }
        assertTrue("expected a correlated request that includes the SDK plane", correlated != null)
        assertTrue(
            "expected planes to include both SDK and RUNTIME",
            correlated!!.planes.containsAll(
                listOf(ObservationEvent.ObserverSource.SDK, ObservationEvent.ObserverSource.RUNTIME)
            )
        )
        val eventsForRequest = CorrelationEngine.getEventsFor(correlated.requestId)
        assertEquals(2, eventsForRequest.size)
        assertTrue(eventsForRequest.any { it.source == ObservationEvent.ObserverSource.SDK })
    }
}
