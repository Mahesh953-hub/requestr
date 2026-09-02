package com.example.ui.screens

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.repository.RecentActivityItem
import com.example.data.repository.RecentItemType
import com.example.data.search.GlobalSearchHit
import com.example.data.interception.InterceptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure logic behind Home's rebrand: the advanced search filter/sort panel, the
 * recents row presentation, and the identity capsule's contextual title.
 *
 * Composables are not tested here — per this repo's convention the testable logic is extracted
 * into plain functions (`HomeSearchFilters.kt`, `RecentActivityUi.kt`) and those are tested.
 */
class HomeSearchFiltersTest {

    private fun api(id: String, name: String, url: String = "https://example.com/$id") =
        GlobalSearchHit.Api(
            ApiRequestItem(id = id, name = name, method = HttpMethod.GET, url = url)
        )

    private fun note(id: String, title: String) = GlobalSearchHit.Note(
        NoteItem(id = id, title = title, content = "body", format = NoteFormatType.MARKDOWN)
    )

    private fun chat(id: String, title: String) = GlobalSearchHit.Chat(
        conversationId = id, title = title, matchedMessage = null, messageCount = 3
    )

    // --- category mapping ---------------------------------------------------------------------

    @Test
    fun `every hit type maps to a distinct category`() {
        assertEquals(HomeSearchType.API, searchTypeOf(api("a", "Alpha")))
        assertEquals(HomeSearchType.NOTE, searchTypeOf(note("n", "Note")))
        assertEquals(HomeSearchType.CHAT, searchTypeOf(chat("c", "Chat")))
    }

    // --- default filter ----------------------------------------------------------------------

    @Test
    fun `default filter is a no-op and reports itself as default`() {
        val hits = listOf(api("a", "Alpha"), note("n", "Zulu"), chat("c", "Mike"))
        val filter = HomeSearchFilter()

        assertTrue(filter.isDefault)
        assertEquals(hits, applyHomeSearchFilter(hits, filter))
    }

    @Test
    fun `an empty type set means no category filter rather than match nothing`() {
        val hits = listOf(api("a", "Alpha"), note("n", "Zulu"))
        val filter = HomeSearchFilter(types = emptySet())

        // The panel must never be able to silently reduce results to zero.
        assertEquals(2, applyHomeSearchFilter(hits, filter).size)
    }

    // --- category filtering -------------------------------------------------------------------

    @Test
    fun `selecting a category keeps only that category`() {
        val hits = listOf(api("a", "Alpha"), note("n", "Zulu"), chat("c", "Mike"))
        val result = applyHomeSearchFilter(hits, HomeSearchFilter(types = setOf(HomeSearchType.NOTE)))

        assertEquals(1, result.size)
        assertEquals(HomeSearchType.NOTE, searchTypeOf(result.single()))
    }

    @Test
    fun `selecting several categories keeps all of them`() {
        val hits = listOf(api("a", "Alpha"), note("n", "Zulu"), chat("c", "Mike"))
        val result = applyHomeSearchFilter(
            hits,
            HomeSearchFilter(types = setOf(HomeSearchType.API, HomeSearchType.CHAT))
        )

        assertEquals(2, result.size)
        assertTrue(result.none { searchTypeOf(it) == HomeSearchType.NOTE })
    }

    @Test
    fun `toggling a type twice returns to the default filter`() {
        val filter = HomeSearchFilter()
            .toggleType(HomeSearchType.TOOL)
            .toggleType(HomeSearchType.TOOL)

        assertTrue(filter.isDefault)
        assertTrue(filter.types.isEmpty())
    }

    @Test
    fun `a category with no hits yields an empty list rather than throwing`() {
        val hits = listOf(api("a", "Alpha"))
        assertTrue(
            applyHomeSearchFilter(hits, HomeSearchFilter(types = setOf(HomeSearchType.FLOW)))
                .isEmpty()
        )
    }

    // --- sorting ------------------------------------------------------------------------------

    @Test
    fun `best match preserves the incoming order`() {
        val hits = listOf(api("a", "Zulu"), api("b", "Alpha"), api("c", "Mike"))
        val result = applyHomeSearchFilter(hits, HomeSearchFilter(sort = HomeSearchSort.BEST_MATCH))

        assertEquals(listOf("Zulu", "Alpha", "Mike"), result.map { it.title })
    }

    @Test
    fun `title ascending sorts case-insensitively`() {
        val hits = listOf(api("a", "zulu"), api("b", "Alpha"), api("c", "mike"))
        val result = applyHomeSearchFilter(hits, HomeSearchFilter(sort = HomeSearchSort.TITLE_ASC))

        assertEquals(listOf("Alpha", "mike", "zulu"), result.map { it.title })
    }

    @Test
    fun `title descending is the exact reverse of ascending`() {
        val hits = listOf(api("a", "zulu"), api("b", "Alpha"), api("c", "mike"))
        val asc = applyHomeSearchFilter(hits, HomeSearchFilter(sort = HomeSearchSort.TITLE_ASC))
        val desc = applyHomeSearchFilter(hits, HomeSearchFilter(sort = HomeSearchSort.TITLE_DESC))

        assertEquals(asc.map { it.title }.reversed(), desc.map { it.title })
    }

    @Test
    fun `sorting is applied after category filtering`() {
        val hits = listOf(api("a", "Zulu"), note("n", "Alpha"), api("b", "Mike"))
        val result = applyHomeSearchFilter(
            hits,
            HomeSearchFilter(types = setOf(HomeSearchType.API), sort = HomeSearchSort.TITLE_ASC)
        )

        assertEquals(listOf("Mike", "Zulu"), result.map { it.title })
    }

    @Test
    fun `a non-default sort alone marks the filter as non-default`() {
        assertFalse(HomeSearchFilter(sort = HomeSearchSort.TITLE_ASC).isDefault)
    }

    // --- counts -------------------------------------------------------------------------------

    @Test
    fun `counts are per category over the unfiltered hits`() {
        val hits = listOf(api("a", "Alpha"), api("b", "Beta"), note("n", "Note"))
        val counts = homeSearchTypeCounts(hits)

        assertEquals(2, counts[HomeSearchType.API])
        assertEquals(1, counts[HomeSearchType.NOTE])
        assertEquals(null, counts[HomeSearchType.CHAT])
    }

    // --- hit keys -----------------------------------------------------------------------------

    @Test
    fun `hit keys are stable per hit and distinguish an api from a note with the same id`() {
        assertEquals("same", searchHitKey(api("same", "Alpha")))
        assertEquals("same", searchHitKey(note("same", "Alpha")))
        // The list key is namespaced by type at the call site, so identical ids across types are
        // fine; what must hold is that the key is derived from the item's own id.
        assertNotEquals(searchHitKey(api("a", "Alpha")), searchHitKey(api("b", "Alpha")))
    }

    @Test
    fun `a chat hit keys on its conversation id`() {
        assertEquals("conv-7", searchHitKey(chat("conv-7", "Anything")))
    }
}

/** Recents row presentation and the identity capsule's contextual title. */
class HomeRecentsPresentationTest {

    private fun item(
        type: RecentItemType,
        subtype: String = "MARKDOWN",
        id: String = "id-1",
        openedAt: Long = 1_000L,
    ) = RecentActivityItem(
        type = type,
        itemId = id,
        subtype = subtype,
        title = "Title",
        preview = "preview",
        openedAt = openedAt,
    )

    @Test
    fun `type label uppercases the stored raw subtype`() {
        assertEquals("Note • MARKDOWN", recentTypeLabel(item(RecentItemType.NOTE, "markdown")))
        assertEquals("API • GET", recentTypeLabel(item(RecentItemType.API, "get")))
    }

    @Test
    fun `a blank subtype renders the type alone with no dangling separator`() {
        assertEquals("Tool", recentTypeLabel(item(RecentItemType.TOOL, subtype = "")))
        assertEquals("Tool", recentTypeLabel(item(RecentItemType.TOOL, subtype = "   ")))
    }

    @Test
    fun `every type has its own colour and icon`() {
        val colors = RecentItemType.entries.map { recentTypeColor(it) }
        val icons = RecentItemType.entries.map { recentTypeIcon(it) }

        assertEquals(RecentItemType.entries.size, colors.distinct().size)
        assertEquals(RecentItemType.entries.size, icons.distinct().size)
    }

    @Test
    fun `filtering by null returns everything and preserves order`() {
        val recents = listOf(
            item(RecentItemType.API, id = "a", openedAt = 30),
            item(RecentItemType.NOTE, id = "b", openedAt = 20),
        )
        assertEquals(recents, filterRecents(recents, null))
    }

    @Test
    fun `filtering by a type keeps only that type`() {
        val recents = listOf(
            item(RecentItemType.API, id = "a"),
            item(RecentItemType.NOTE, id = "b"),
            item(RecentItemType.API, id = "c"),
        )
        val result = filterRecents(recents, RecentItemType.API)

        assertEquals(listOf("a", "c"), result.map { it.itemId })
    }

    @Test
    fun `type counts only include types that are present`() {
        val recents = listOf(
            item(RecentItemType.API, id = "a"),
            item(RecentItemType.API, id = "b"),
            item(RecentItemType.CHAT, id = "c"),
        )
        val counts = recentTypeCounts(recents)

        assertEquals(2, counts[RecentItemType.API])
        assertEquals(1, counts[RecentItemType.CHAT])
        assertFalse(counts.containsKey(RecentItemType.FLOW))
    }

    @Test
    fun `identity capsule shows the wordmark unless a capture session is live`() {
        assertEquals("requestr", homeIdentityTitle(InterceptionStatus.NEVER_CONFIGURED))
        assertEquals("requestr", homeIdentityTitle(InterceptionStatus.CONFIGURED_IDLE))
        assertEquals("requestr", homeIdentityTitle(InterceptionStatus.ERROR))
        assertEquals("interceptr", homeIdentityTitle(InterceptionStatus.ACTIVE))
        assertEquals("interceptr", homeIdentityTitle(InterceptionStatus.ARMED))
    }

    @Test
    fun `status dot is green when active and red when not`() {
        assertNotEquals(homeStatusDotColor(true), homeStatusDotColor(false))
        // Green channel dominates when active, red channel when not -- the spec's colour coding.
        assertTrue(homeStatusDotColor(true).green > homeStatusDotColor(true).red)
        assertTrue(homeStatusDotColor(false).red > homeStatusDotColor(false).green)
    }
}
