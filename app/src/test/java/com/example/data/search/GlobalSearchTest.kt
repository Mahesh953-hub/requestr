package com.example.data.search

import com.example.data.models.ApiRequestItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.repository.ToolCategory
import com.example.data.repository.ToolItem
import org.junit.Assert.*
import org.junit.Test

class GlobalSearchTest {

    private val apis = listOf(
        ApiRequestItem(id = "a1", name = "Get Users", url = "https://api.example.com/users"),
        ApiRequestItem(id = "a2", name = "Create Order", url = "https://api.example.com/orders"),
    )
    private val notes = listOf(
        NoteItem(id = "n1", title = "Auth notes", content = "Bearer tokens expire in 1h", format = NoteFormatType.MARKDOWN),
        NoteItem(id = "n2", title = "Shopping list", content = "milk, eggs", format = NoteFormatType.PLAIN),
    )
    private val tools = listOf(
        ToolItem(id = "t1", title = "JWT Token Decoder", description = "Decode Header, Payload claims and inspect signatures", category = ToolCategory.DECODERS),
        ToolItem(id = "t2", title = "Base64 Encoder", description = "RFC 4648 Base64 text converter", category = ToolCategory.DECODERS),
    )
    private val flows = listOf(
        FlowNodeItem(id = "f1", title = "Fetch token", type = FlowNodeType.API),
        FlowNodeItem(id = "f2", title = "Log output", type = FlowNodeType.OUTPUT),
    )

    @Test
    fun `matches by api name`() {
        val hits = GlobalSearch.search("Users", apis = apis)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is GlobalSearchHit.Api)
        assertEquals("a1", (hits[0] as GlobalSearchHit.Api).item.id)
    }

    @Test
    fun `matches by api url`() {
        val hits = GlobalSearch.search("orders", apis = apis)
        assertEquals(1, hits.size)
        assertEquals("a2", (hits[0] as GlobalSearchHit.Api).item.id)
    }

    @Test
    fun `matches by note body`() {
        val hits = GlobalSearch.search("Bearer", notes = notes)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is GlobalSearchHit.Note)
        assertEquals("n1", (hits[0] as GlobalSearchHit.Note).item.id)
    }

    @Test
    fun `matches by tool description`() {
        val hits = GlobalSearch.search("signatures", tools = tools)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is GlobalSearchHit.Tool)
        assertEquals("t1", (hits[0] as GlobalSearchHit.Tool).item.id)
    }

    @Test
    fun `matches by flow node title`() {
        val hits = GlobalSearch.search("token", flows = flows)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is GlobalSearchHit.FlowNode)
        assertEquals("f1", (hits[0] as GlobalSearchHit.FlowNode).item.id)
    }

    @Test
    fun `is case insensitive`() {
        val hits = GlobalSearch.search("USERS", apis = apis)
        assertEquals(1, hits.size)
    }

    @Test
    fun `blank query returns empty`() {
        assertTrue(GlobalSearch.search("   ", apis = apis, notes = notes, tools = tools, flows = flows).isEmpty())
        assertTrue(GlobalSearch.search("", apis = apis, notes = notes, tools = tools, flows = flows).isEmpty())
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(GlobalSearch.search("zzz-nonexistent", apis = apis, notes = notes, tools = tools, flows = flows).isEmpty())
    }

    @Test
    fun `multi kind query returns hits from each kind`() {
        val hits = GlobalSearch.search("o", apis = apis, notes = notes, tools = tools, flows = flows)
        val kinds = hits.map { it::class }.toSet()
        assertTrue(kinds.size > 1)
    }
}
