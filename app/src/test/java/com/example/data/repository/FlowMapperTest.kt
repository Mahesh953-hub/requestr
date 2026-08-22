package com.example.data.repository

import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.flowConnectionId
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FlowRepository's Room entity<->item mappers (task 4). The mapper functions are
 * member extension functions on [FlowRepository] and were made `internal` (were `private`)
 * specifically so this test can call them directly, per the plan's own note that "pure mapper
 * functions may need to be made internal/visible for testing".
 */
class FlowMapperTest {

    @Test
    fun `condition node derives true and false output ports on read`() = with(FlowRepository) {
        val entity = FlowNodeItem(
            id = "n1", title = "Check", type = FlowNodeType.CONDITION, configData = "status == 200"
        ).toEntity()

        val item = entity.toFlowNodeItem()

        assertEquals(listOf("true", "false"), item.outputPorts)
        assertEquals(listOf("in"), item.inputPorts)
    }

    @Test
    fun `non-condition node gets single out port`() = with(FlowRepository) {
        val entity = FlowNodeItem(id = "n2", title = "Get", type = FlowNodeType.API).toEntity()
        val item = entity.toFlowNodeItem()

        assertEquals(listOf("out"), item.outputPorts)
        assertEquals(listOf("in"), item.inputPorts)
    }

    @Test
    fun `intercept trigger node has no input port`() = with(FlowRepository) {
        val entity = FlowNodeItem(id = "n3", title = "Trigger", type = FlowNodeType.INTERCEPT_TRIGGER).toEntity()
        val item = entity.toFlowNodeItem()

        assertTrue(item.inputPorts.isEmpty())
    }

    @Test
    fun `connection id survives entity round trip unchanged`() = with(FlowRepository) {
        val original = FlowConnectionItem(
            fromNodeId = "a", fromPort = "true", toNodeId = "b", toPort = "in"
        )
        val expectedId = flowConnectionId("a", "true", "b", "in")

        val entity = original.toEntity()
        assertEquals(expectedId, entity.id)

        val roundTripped = entity.toFlowConnectionItem()
        assertEquals(expectedId, roundTripped.id)
        assertEquals("a", roundTripped.fromNodeId)
        assertEquals("true", roundTripped.fromPort)
        assertEquals("b", roundTripped.toNodeId)
        assertEquals("in", roundTripped.toPort)
    }
}
