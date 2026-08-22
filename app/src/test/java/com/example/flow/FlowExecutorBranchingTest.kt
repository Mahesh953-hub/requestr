package com.example.flow

import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FlowExecutorBranchingTest {

    private val noopHttp: suspend (String, String, Map<String, String>, String) -> String =
        { _, _, _, _ -> "{}" }

    @Test
    fun `condition true branch only executes matching node`() = runBlocking {
        val entry = FlowNodeItem(id = "entry", type = FlowNodeType.TEXT, configData = "hello", outputPorts = listOf("out"))
        val condition = FlowNodeItem(id = "cond", type = FlowNodeType.CONDITION, configData = "body contains hello", outputPorts = listOf("true", "false"))
        val onTrue = FlowNodeItem(id = "a", type = FlowNodeType.TEXT, configData = "A")
        val onFalse = FlowNodeItem(id = "b", type = FlowNodeType.TEXT, configData = "B")

        val nodes = listOf(entry, condition, onTrue, onFalse)
        val connections = listOf(
            FlowConnectionItem(fromNodeId = "entry", fromPort = "out", toNodeId = "cond", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "true", toNodeId = "a", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "false", toNodeId = "b", toPort = "in"),
        )

        val executor = FlowExecutor(noopHttp)
        val result = executor.execute(nodes, connections)

        assertTrue(result.outputs.containsKey("a"))
        assertFalse(result.outputs.containsKey("b"))
    }

    @Test
    fun `condition false branch only executes matching node`() = runBlocking {
        val entry = FlowNodeItem(id = "entry", type = FlowNodeType.TEXT, configData = "hello", outputPorts = listOf("out"))
        val condition = FlowNodeItem(id = "cond", type = FlowNodeType.CONDITION, configData = "body contains nomatch", outputPorts = listOf("true", "false"))
        val onTrue = FlowNodeItem(id = "a", type = FlowNodeType.TEXT, configData = "A")
        val onFalse = FlowNodeItem(id = "b", type = FlowNodeType.TEXT, configData = "B")

        val nodes = listOf(entry, condition, onTrue, onFalse)
        val connections = listOf(
            FlowConnectionItem(fromNodeId = "entry", fromPort = "out", toNodeId = "cond", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "true", toNodeId = "a", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "false", toNodeId = "b", toPort = "in"),
        )

        val executor = FlowExecutor(noopHttp)
        val result = executor.execute(nodes, connections)

        assertFalse(result.outputs.containsKey("a"))
        assertTrue(result.outputs.containsKey("b"))
    }

    @Test
    fun `unlabelled port graph still reaches downstream nodes`() = runBlocking {
        // Legacy/older saved flow: all connections use the generic "out" port, none labelled
        // "true"/"false". Branching logic must fall back to following every edge so these
        // flows don't silently stop executing downstream nodes.
        val entry = FlowNodeItem(id = "entry", type = FlowNodeType.TEXT, configData = "hello", outputPorts = listOf("out"))
        val condition = FlowNodeItem(id = "cond", type = FlowNodeType.CONDITION, configData = "body contains hello", outputPorts = listOf("out"))
        val downstream = FlowNodeItem(id = "next", type = FlowNodeType.TEXT, configData = "next-step")

        val nodes = listOf(entry, condition, downstream)
        val connections = listOf(
            FlowConnectionItem(fromNodeId = "entry", fromPort = "out", toNodeId = "cond", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "out", toNodeId = "next", toPort = "in"),
        )

        val executor = FlowExecutor(noopHttp)
        val result = executor.execute(nodes, connections)

        assertTrue(result.outputs.containsKey("next"))
    }

    @Test
    fun `missing url on API node becomes a real error not a hidden default`() = runBlocking {
        val entry = FlowNodeItem(id = "entry", type = FlowNodeType.API, configData = "{}")
        val nodes = listOf(entry)

        val executor = FlowExecutor(noopHttp)
        val result = executor.execute(nodes, emptyList())

        val output = result.outputs["entry"]
        assertNotNull(output)
        assertTrue(output!!.contains("error"))
        assertFalse(output.contains("httpbin"))
    }

    @Test
    fun `unparseable condition config is reported as an error not a silent true`() = runBlocking {
        val entry = FlowNodeItem(id = "entry", type = FlowNodeType.TEXT, configData = "hello")
        val condition = FlowNodeItem(id = "cond", type = FlowNodeType.CONDITION, configData = "totally not parseable", outputPorts = listOf("true", "false"))
        val onTrue = FlowNodeItem(id = "a", type = FlowNodeType.TEXT, configData = "A")

        val nodes = listOf(entry, condition, onTrue)
        val connections = listOf(
            FlowConnectionItem(fromNodeId = "entry", fromPort = "out", toNodeId = "cond", toPort = "in"),
            FlowConnectionItem(fromNodeId = "cond", fromPort = "true", toNodeId = "a", toPort = "in"),
        )

        val executor = FlowExecutor(noopHttp)
        val result = executor.execute(nodes, connections)

        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("cond") || it.contains("parse") })
    }
}
