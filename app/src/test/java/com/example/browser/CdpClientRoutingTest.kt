package com.example.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CdpClient]'s W8 message-construction and dispatch routing logic, exercised
 * as pure functions over JSON strings -- no real socket, matching the plan's test spec for
 * work item 8 (multi-target CDP client with flattened session routing and reconnect-with-backoff).
 *
 * Robolectric is required (not a plain JVM test) because [CdpClient]'s dispatch/attach paths
 * call `android.util.Log`, matching the existing convention in [CdpNetworkAdapterTest].
 */
@RunWith(RobolectricTestRunner::class)
class CdpClientRoutingTest {

    @Test
    fun `buildMessage includes sessionId when non-null`() {
        val msg = CdpClient.buildMessage(1, "Network.enable", JSONObject(), sessionId = "S1")
        assertTrue(msg.has("sessionId"))
        assertEquals("S1", msg.getString("sessionId"))
    }

    @Test
    fun `buildMessage omits sessionId when null`() {
        val msg = CdpClient.buildMessage(1, "Network.enable", JSONObject(), sessionId = null)
        assertFalse(msg.has("sessionId"))
    }

    @Test
    fun `dispatch of a session-scoped event invokes only that session's listener`() {
        val client = CdpClient("localhost", 9222)
        var s1Invocations = 0
        var s2Invocations = 0
        client.on("Network.requestWillBeSent", sessionId = "S1") { s1Invocations++ }
        client.on("Network.requestWillBeSent", sessionId = "S2") { s2Invocations++ }

        client.dispatchForTest(
            """{"method":"Network.requestWillBeSent","params":{},"sessionId":"S1"}""",
        )

        assertEquals(1, s1Invocations)
        assertEquals(0, s2Invocations)
    }

    @Test
    fun `dispatch of a browser-level event with no sessionId invokes only the browser-level listener`() {
        val client = CdpClient("localhost", 9222)
        var browserInvocations = 0
        var sessionInvocations = 0
        client.on("Target.attachedToTarget", sessionId = null) { browserInvocations++ }
        client.on("Target.attachedToTarget", sessionId = "S1") { sessionInvocations++ }

        client.dispatchForTest(
            """{"method":"Target.attachedToTarget","params":{}}""",
        )

        assertEquals(1, browserInvocations)
        assertEquals(0, sessionInvocations)
    }

    @Test
    fun `attachedToTarget then detachedFromTarget adds and removes the target from the state map`() {
        val client = CdpClient("localhost", 9222)

        client.dispatchForTest(
            """
            {
              "method": "Target.attachedToTarget",
              "params": {
                "sessionId": "S1",
                "targetInfo": {"targetId": "T1", "type": "page", "title": "Example", "url": "https://example.com/"}
              }
            }
            """.trimIndent(),
        )
        assertEquals(1, client.targets.value.size)
        assertTrue(client.targets.value.containsKey("S1"))
        assertEquals("T1", client.targets.value["S1"]?.targetId)

        client.dispatchForTest(
            """{"method":"Target.detachedFromTarget","params":{"sessionId":"S1"}}""",
        )
        assertTrue(client.targets.value.isEmpty())
    }

    @Test
    fun `targetDestroyed removes the target matched by targetId`() {
        val client = CdpClient("localhost", 9222)

        client.dispatchForTest(
            """
            {
              "method": "Target.attachedToTarget",
              "params": {
                "sessionId": "S1",
                "targetInfo": {"targetId": "T1", "type": "page", "title": "Example", "url": "https://example.com/"}
              }
            }
            """.trimIndent(),
        )
        assertEquals(1, client.targets.value.size)

        client.dispatchForTest(
            """{"method":"Target.targetDestroyed","params":{"targetId":"T1"}}""",
        )
        assertTrue(client.targets.value.isEmpty())
    }

    @Test
    fun `onTargetAttached and onTargetDetached callbacks fire with the right info`() {
        val client = CdpClient("localhost", 9222)
        var attached: CdpClient.TargetInfo? = null
        var detachedSessionId: String? = null
        client.onTargetAttached = { attached = it }
        client.onTargetDetached = { detachedSessionId = it }

        client.dispatchForTest(
            """
            {
              "method": "Target.attachedToTarget",
              "params": {
                "sessionId": "S1",
                "targetInfo": {"targetId": "T1", "type": "page", "title": "Example", "url": "https://example.com/"}
              }
            }
            """.trimIndent(),
        )
        assertEquals("S1", attached?.sessionId)
        assertEquals("https://example.com/", attached?.url)

        client.dispatchForTest(
            """{"method":"Target.detachedFromTarget","params":{"sessionId":"S1"}}""",
        )
        assertEquals("S1", detachedSessionId)
    }

    @Test
    fun `backoff schedule is capped, doubling, and never negative`() {
        assertEquals(0L, CdpClient.computeBackoffDelayMs(0))
        assertEquals(0L, CdpClient.computeBackoffDelayMs(-1))
        assertEquals(1_000L, CdpClient.computeBackoffDelayMs(1))
        assertEquals(2_000L, CdpClient.computeBackoffDelayMs(2))
        assertEquals(4_000L, CdpClient.computeBackoffDelayMs(3))
        assertEquals(8_000L, CdpClient.computeBackoffDelayMs(4))
        assertEquals(16_000L, CdpClient.computeBackoffDelayMs(5))
        assertEquals(16_000L, CdpClient.computeBackoffDelayMs(6))

        for (attempt in 0..10) {
            assertTrue(CdpClient.computeBackoffDelayMs(attempt) >= 0L)
            assertTrue(CdpClient.computeBackoffDelayMs(attempt) <= 16_000L)
        }
    }

    @Test
    fun `listenerKey keeps browser-level and session listeners distinct`() {
        val browserKey = CdpClient.listenerKey(null, "Network.enable")
        val sessionKey = CdpClient.listenerKey("S1", "Network.enable")
        assertFalse(browserKey == sessionKey)
    }
}
