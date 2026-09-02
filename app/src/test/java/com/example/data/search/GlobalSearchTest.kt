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
    private val chats = listOf(
        ChatSearchEntry(
            conversationId = "c1",
            title = "Debugging the OAuth flow",
            messages = listOf("Why does my refresh token expire?", "Refresh tokens rotate on every use.\nCheck the  rotation  policy."),
        ),
        ChatSearchEntry(conversationId = "c2", title = "New chat", messages = emptyList()),
        ChatSearchEntry(conversationId = "c3", title = "Regex help", messages = listOf("How do I match an IPv4 address?")),
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

    // ── Chat search (Home placeholder: "Search APIs, notes, chats, tools...") ──

    @Test
    fun `matches a chat by conversation title and reports its real message count`() {
        val hits = GlobalSearch.search("oauth", chats = chats)
        assertEquals(1, hits.size)
        val hit = hits[0] as GlobalSearchHit.Chat
        assertEquals("c1", hit.conversationId)
        assertEquals("Debugging the OAuth flow", hit.title)
        assertEquals(2, hit.messageCount)
    }

    @Test
    fun `matches a chat by message content and surfaces the matching message as the subtitle`() {
        val hits = GlobalSearch.search("ipv4", chats = chats)
        assertEquals(1, hits.size)
        val hit = hits[0] as GlobalSearchHit.Chat
        assertEquals("c3", hit.conversationId)
        assertEquals("How do I match an IPv4 address?", hit.matchedMessage)
        assertEquals("How do I match an IPv4 address?", hit.subtitle)
    }

    @Test
    fun `message match collapses whitespace and picks the first matching message`() {
        val hit = GlobalSearch.search("rotation", chats = chats).single() as GlobalSearchHit.Chat
        assertEquals("Refresh tokens rotate on every use. Check the rotation policy.", hit.matchedMessage)
    }

    @Test
    fun `a conversation matched on both title and content is reported once`() {
        // "refresh" appears in two messages of c1 but not its title; "flow" appears only in the
        // title. Either way c1 must be a single hit.
        assertEquals(1, GlobalSearch.search("refresh", chats = chats).size)
        assertEquals(1, GlobalSearch.search("flow", chats = chats).size)
    }

    @Test
    fun `title-only chat match uses the message count as subtitle, singular and plural`() {
        val hit = GlobalSearch.search("Debugging", chats = chats).single() as GlobalSearchHit.Chat
        assertEquals(null, hit.matchedMessage)
        assertEquals("2 messages", hit.subtitle)

        val single = GlobalSearch.search("Regex", chats = chats).single() as GlobalSearchHit.Chat
        assertEquals("1 message", single.subtitle)

        val empty = GlobalSearch.search("New chat", chats = chats).single() as GlobalSearchHit.Chat
        assertEquals("0 messages", empty.subtitle)
    }

    @Test
    fun `chat search is case insensitive`() {
        assertEquals(1, GlobalSearch.search("REFRESH TOKEN", chats = chats).size)
        assertEquals(1, GlobalSearch.search("debugging the oauth", chats = chats).size)
    }

    @Test
    fun `chat search with no match returns empty and blank query returns empty`() {
        assertTrue(GlobalSearch.search("zzz-nonexistent", chats = chats).isEmpty())
        assertTrue(GlobalSearch.search("   ", chats = chats).isEmpty())
    }

    @Test
    fun `chat hits come after the other kinds and the existing call convention still works`() {
        // "token" matches api-less, note "Bearer tokens", flow "Fetch token" and chat c1's message.
        val hits = GlobalSearch.search("token", apis = apis, notes = notes, tools = tools, flows = flows, chats = chats)
        assertTrue(hits.first() is GlobalSearchHit.Note)
        assertTrue(hits.last() is GlobalSearchHit.Chat)
        assertEquals(1, hits.count { it is GlobalSearchHit.Chat })
        // Omitting `chats` (the pre-existing call shape from HomeScreen) yields no chat hits.
        assertTrue(GlobalSearch.search("token", apis = apis, notes = notes, tools = tools, flows = flows).none { it is GlobalSearchHit.Chat })
    }
}
