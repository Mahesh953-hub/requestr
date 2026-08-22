package com.example.flow

import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses Robolectric because [FlowSerializer] round-trips through `org.json.JSONObject`, and the
 * plain android.jar stub on the unit-test classpath returns null from all `org.json` methods
 * (only `isReturnDefaultValues` applies to Android framework classes referenced directly, not
 * to making org.json actually parse). Robolectric provides a real implementation.
 */
@RunWith(RobolectricTestRunner::class)
class FlowSerializerRoundTripTest {

    @Test
    fun `export then import preserves node ids types positions and configData`() {
        val nodes = listOf(
            FlowNodeItem(id = "n1", title = "Get Token", type = FlowNodeType.API, x = 120f, y = 80f, configData = """{"url":"https://example.com"}"""),
            FlowNodeItem(id = "n2", title = "Check Status", type = FlowNodeType.CONDITION, x = 300f, y = 80f, configData = """{"expr":"status == 200"}"""),
        )
        val connections = listOf(
            FlowConnectionItem(id = "c1", fromNodeId = "n1", fromPort = "out", toNodeId = "n2", toPort = "in"),
        )

        val json = FlowSerializer.exportToJson("test-flow", nodes, connections)
        val imported = FlowSerializer.importFromJson(json)

        assertNotNull(imported)
        val (importedNodes, importedConnections) = imported!!

        assertEquals(2, importedNodes.size)
        val n1 = importedNodes.find { it.id == "n1" }
        assertNotNull(n1)
        assertEquals(FlowNodeType.API, n1!!.type)
        assertEquals(120f, n1.x)
        assertEquals(80f, n1.y)
        assertTrue(n1.configData.contains("example.com"))

        val n2 = importedNodes.find { it.id == "n2" }
        assertNotNull(n2)
        assertEquals(FlowNodeType.CONDITION, n2!!.type)

        assertEquals(1, importedConnections.size)
        val conn = importedConnections.first()
        assertEquals("n1", conn.fromNodeId)
        assertEquals("n2", conn.toNodeId)
        assertEquals("out", conn.fromPort)
        assertEquals("in", conn.toPort)
    }

    @Test
    fun `malformed json returns null`() {
        val result = FlowSerializer.importFromJson("{ this is not valid json ")
        assertNull(result)
    }

    @Test
    fun `json missing nodes array returns null`() {
        val result = FlowSerializer.importFromJson("""{"name":"empty flow"}""")
        assertNull(result)
    }

    @Test
    fun `export produces valid json importable back`() {
        // configData must itself be valid JSON — exportToJson wraps it in a JSONObject, so a
        // plain string like "hello" would throw. Real node configData is always JSON (see
        // FlowExecutor's "url"/"method"/"body" fields), so this matches real usage.
        val nodes = listOf(FlowNodeItem(id = "solo", title = "Solo", type = FlowNodeType.TEXT, configData = """{"text":"hello"}"""))
        val json = FlowSerializer.exportToJson("solo-flow", nodes, emptyList())
        val imported = FlowSerializer.importFromJson(json)
        assertNotNull(imported)
        assertEquals(1, imported!!.first.size)
        assertEquals(0, imported.second.size)
    }
}
