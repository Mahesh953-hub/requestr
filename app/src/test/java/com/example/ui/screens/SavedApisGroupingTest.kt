package com.example.ui.screens

import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiRequestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function tests for [groupRequestsIntoCollections] (dossier Q3 restructure). */
class SavedApisGroupingTest {

    private fun folder(id: String, name: String) = ApiFolderItem(id = id, name = name)
    private fun request(id: String, folderId: String?, updatedAt: Long) =
        ApiRequestItem(id = id, name = "req-$id", folderId = folderId, updatedAt = updatedAt)

    @Test
    fun `groups requests under their matching folder, preserving folder order`() {
        val folders = listOf(folder("f-1", "GitHub REST API v3"), folder("f-2", "Stripe Payment Intents"))
        val requests = listOf(
            request("r-1", "f-2", 100),
            request("r-2", "f-1", 200),
            request("r-3", "f-1", 300),
        )

        val groups = groupRequestsIntoCollections(requests, folders)

        assertEquals(2, groups.size)
        assertEquals("GitHub REST API v3", groups[0].name)
        assertEquals(listOf("r-3", "r-2"), groups[0].requests.map { it.id })
        assertEquals("Stripe Payment Intents", groups[1].name)
        assertEquals(listOf("r-1"), groups[1].requests.map { it.id })
    }

    @Test
    fun `endpoint count is the real number of requests in that collection`() {
        val folders = listOf(folder("f-1", "GitHub REST API v3"))
        val requests = (1..14).map { request("r-$it", "f-1", it.toLong()) }

        val groups = groupRequestsIntoCollections(requests, folders)

        assertEquals(14, groups.single().endpointCount)
    }

    @Test
    fun `requests with no folderId land in a trailing Ungrouped bucket`() {
        val folders = listOf(folder("f-1", "GitHub REST API v3"))
        val requests = listOf(
            request("r-1", "f-1", 100),
            request("r-2", null, 200),
        )

        val groups = groupRequestsIntoCollections(requests, folders)

        assertEquals(2, groups.size)
        assertEquals("GitHub REST API v3", groups[0].name)
        assertTrue(groups[1].isUngrouped)
        assertEquals(UNGROUPED_COLLECTION_ID, groups[1].id)
        assertEquals(listOf("r-2"), groups[1].requests.map { it.id })
    }

    @Test
    fun `a request whose folderId points at no real folder is treated as ungrouped, not dropped`() {
        val folders = listOf(folder("f-1", "GitHub REST API v3"))
        val requests = listOf(request("r-1", "dangling-folder-id", 100))

        val groups = groupRequestsIntoCollections(requests, folders)

        // The real folder still gets a card (even though it has zero requests)...
        assertEquals(2, groups.size)
        assertEquals("GitHub REST API v3", groups[0].name)
        assertEquals(0, groups[0].endpointCount)
        // ...and the orphaned request is reachable in Ungrouped, never silently lost.
        assertTrue(groups[1].isUngrouped)
        assertEquals(listOf("r-1"), groups[1].requests.map { it.id })
    }

    @Test
    fun `an empty collection still gets a card instead of disappearing`() {
        val folders = listOf(folder("f-1", "Empty Collection"))

        val groups = groupRequestsIntoCollections(emptyList(), folders)

        assertEquals(1, groups.size)
        assertEquals("Empty Collection", groups[0].name)
        assertEquals(0, groups[0].endpointCount)
        assertTrue(groups[0].requests.isEmpty())
    }

    @Test
    fun `no Ungrouped card is rendered when every request has a real folder`() {
        val folders = listOf(folder("f-1", "GitHub REST API v3"))
        val requests = listOf(request("r-1", "f-1", 100))

        val groups = groupRequestsIntoCollections(requests, folders)

        assertFalse(groups.any { it.isUngrouped })
    }

    @Test
    fun `zero folders and zero requests produces an empty list, not a crash`() {
        assertTrue(groupRequestsIntoCollections(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `zero folders with only unfiled requests produces a single Ungrouped bucket`() {
        val requests = listOf(request("r-1", null, 100), request("r-2", null, 200))

        val groups = groupRequestsIntoCollections(requests, emptyList())

        assertEquals(1, groups.size)
        assertTrue(groups[0].isUngrouped)
        assertEquals(listOf("r-2", "r-1"), groups[0].requests.map { it.id })
    }

    @Test
    fun `grouping is stable across repeated calls with the same input`() {
        val folders = listOf(folder("f-2", "B"), folder("f-1", "A"))
        val requests = listOf(request("r-1", "f-1", 100), request("r-2", "f-2", 200))

        val first = groupRequestsIntoCollections(requests, folders).map { it.id }
        val second = groupRequestsIntoCollections(requests, folders).map { it.id }

        assertEquals(first, second)
        // Order follows the folders list as given (repository order), not alphabetical/size sort.
        assertEquals(listOf("f-2", "f-1"), first)
    }
}
