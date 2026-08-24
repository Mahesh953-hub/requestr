package com.example.ui.screens

import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v6.0 Lane C (plan task 13) — tests for the pure presentation helper [conditionPortYOffset],
 * which returns the dp Y-offset of a flow node's output port so the canvas can draw CONDITION
 * branches (true/false) at distinct origins and keep every other node's single "out" port at
 * the baseline 35dp used by the connection geometry.
 *
 * Pure Kotlin (no Android framework / Compose), runs as plain JUnit. Pins the frozen
 * [FlowNodeType.colorHex]-driven port layout so a future visual tweak cannot desync the
 * connection-curve origin from the rendered port handle.
 */
class FlowCanvasHelpersTest {

    @Test
    fun `condition true port returns upper offset`() {
        val node = FlowNodeItem(type = FlowNodeType.CONDITION)
        assertEquals(22f, conditionPortYOffset(node, "true"))
    }

    @Test
    fun `condition false port returns lower offset`() {
        val node = FlowNodeItem(type = FlowNodeType.CONDITION)
        assertEquals(48f, conditionPortYOffset(node, "false"))
    }

    @Test
    fun `condition true and false ports are distinct`() {
        val node = FlowNodeItem(type = FlowNodeType.CONDITION)
        val trueY = conditionPortYOffset(node, "true")
        val falseY = conditionPortYOffset(node, "false")
        assert(trueY != falseY) { "true/false ports must render at distinct Y offsets" }
    }

    @Test
    fun `condition out port falls back to baseline`() {
        val node = FlowNodeItem(type = FlowNodeType.CONDITION)
        assertEquals(35f, conditionPortYOffset(node, "out"))
    }

    @Test
    fun `api node out port returns baseline`() {
        val node = FlowNodeItem(type = FlowNodeType.API)
        assertEquals(35f, conditionPortYOffset(node, "out"))
    }

    @Test
    fun `api node true port returns baseline because not condition`() {
        // Non-CONDITION nodes only ever expose "out"; any other port label must fall back
        // to the baseline so a stray label never collapses to 0 or a port-specific offset.
        val node = FlowNodeItem(type = FlowNodeType.API)
        assertEquals(35f, conditionPortYOffset(node, "true"))
        assertEquals(35f, conditionPortYOffset(node, "false"))
    }

    @Test
    fun `transform node out port returns baseline`() {
        val node = FlowNodeItem(type = FlowNodeType.TRANSFORM)
        assertEquals(35f, conditionPortYOffset(node, "out"))
    }

    @Test
    fun `unknown port label on non-condition node returns baseline`() {
        val node = FlowNodeItem(type = FlowNodeType.OUTPUT)
        assertEquals(35f, conditionPortYOffset(node, "unknown"))
    }

    @Test
    fun `intercept trigger out port returns baseline`() {
        val node = FlowNodeItem(type = FlowNodeType.INTERCEPT_TRIGGER)
        assertEquals(35f, conditionPortYOffset(node, "out"))
    }
}
