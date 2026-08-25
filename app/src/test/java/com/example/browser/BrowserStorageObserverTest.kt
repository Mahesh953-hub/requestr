package com.example.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [BrowserStorageObserver]'s W9 real-data behavior, matching the plan's test spec
 * for work item 9: `DOMStorage.*` live deltas applied to [BrowserStorageObserver.StorageSnapshot],
 * and `IndexedDB.requestData` response JSON parsed into the expected record list.
 *
 * Uses [CdpClient.dispatchForTest] to route real CDP event frames through the same [CdpClient.on]
 * listeners [BrowserStorageObserver.start] registers, and [CdpClient.sendHookForTest] to supply
 * canned command results -- no live socket, mirroring [CdpClientRoutingTest]/[CdpFetchAdapterTest].
 */
@RunWith(RobolectricTestRunner::class)
class BrowserStorageObserverTest {

    private fun storageId(origin: String = "https://example.com") = JSONObject().apply {
        put("securityOrigin", origin)
        put("isLocalStorage", true)
    }

    private fun itemEvent(key: String, newValue: String? = null, oldValue: String? = null): JSONObject =
        JSONObject().apply {
            put("storageId", storageId())
            put("key", key)
            if (newValue != null) put("newValue", newValue)
            if (oldValue != null) put("oldValue", oldValue)
        }

    private fun buildObserver(client: CdpClient, origin: String? = "https://example.com"): BrowserStorageObserver {
        val observer = BrowserStorageObserver(client, sessionId = null) { origin }
        observer.start()
        return observer
    }

    // ---- DOMStorage live-event deltas (added -> updated -> removed) ----

    @Test
    fun `added then updated then removed deltas yield the expected final map`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("k1", newValue = "v1"))
            }.toString(),
        )
        assertEquals(mapOf("k1" to "v1"), observer.stateFlow.value.localStorage)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("k2", newValue = "v2"))
            }.toString(),
        )
        assertEquals(mapOf("k1" to "v1", "k2" to "v2"), observer.stateFlow.value.localStorage)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemUpdated")
                put("params", itemEvent("k1", newValue = "v1-updated", oldValue = "v1"))
            }.toString(),
        )
        assertEquals(mapOf("k1" to "v1-updated", "k2" to "v2"), observer.stateFlow.value.localStorage)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemRemoved")
                put("params", itemEvent("k1"))
            }.toString(),
        )
        assertEquals(mapOf("k2" to "v2"), observer.stateFlow.value.localStorage)
    }

    @Test
    fun `domStorageItemsCleared empties the localStorage map`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("k1", newValue = "v1"))
            }.toString(),
        )
        assertEquals(mapOf("k1" to "v1"), observer.stateFlow.value.localStorage)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemsCleared")
                put("params", JSONObject().apply { put("storageId", storageId()) })
            }.toString(),
        )
        assertTrue(observer.stateFlow.value.localStorage.isEmpty())
    }

    @Test
    fun `an event with an empty key is ignored`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("", newValue = "v1"))
            }.toString(),
        )
        assertTrue(observer.stateFlow.value.localStorage.isEmpty())
    }

    // ---- v6.1 Domain B: bounded live localStorage change events ----

    @Test
    fun `live deltas are recorded as bounded, newest-first change events`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("auth.token", newValue = "abc"))
            }.toString(),
        )
        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemUpdated")
                put("params", itemEvent("cart.count", newValue = "4", oldValue = "3"))
            }.toString(),
        )

        val events = observer.stateFlow.value.recentEvents
        assertEquals(2, events.size)
        // Newest first, so the card's stream reads like the mockup's event list.
        assertEquals("cart.count", events[0].key)
        assertEquals(BrowserStorageObserver.DomStorageEventKind.UPDATED, events[0].kind)
        assertEquals("3", events[0].oldValue)
        assertEquals("4", events[0].newValue)
        assertEquals("auth.token", events[1].key)
        assertEquals(BrowserStorageObserver.DomStorageEventKind.ADDED, events[1].kind)
    }

    @Test
    fun `retained change events are capped at MAX_RETAINED_EVENTS`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        repeat(BrowserStorageObserver.MAX_RETAINED_EVENTS + 7) { i ->
            client.dispatchForTest(
                JSONObject().apply {
                    put("method", "DOMStorage.domStorageItemAdded")
                    put("params", itemEvent("k$i", newValue = "v$i"))
                }.toString(),
            )
        }

        val events = observer.stateFlow.value.recentEvents
        assertEquals(BrowserStorageObserver.MAX_RETAINED_EVENTS, events.size)
        assertEquals("k${BrowserStorageObserver.MAX_RETAINED_EVENTS + 6}", events.first().key)
    }

    @Test
    fun `a removal event records the previous value it actually observed`() {
        val client = CdpClient("localhost", 9222)
        val observer = buildObserver(client)

        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemAdded")
                put("params", itemEvent("k1", newValue = "v1"))
            }.toString(),
        )
        client.dispatchForTest(
            JSONObject().apply {
                put("method", "DOMStorage.domStorageItemRemoved")
                put("params", itemEvent("k1"))
            }.toString(),
        )

        val removal = observer.stateFlow.value.recentEvents.first()
        assertEquals(BrowserStorageObserver.DomStorageEventKind.REMOVED, removal.kind)
        assertEquals("v1", removal.oldValue)
        assertEquals(null, removal.newValue)
    }

    // ---- IndexedDB.requestData parsing into records ----

    @Test
    fun `requestData response parses into the expected record list`() {
        val client = CdpClient("localhost", 9222)
        var refreshLatchCommands = mutableListOf<Pair<String, JSONObject>>()
        client.sendHookForTest = { method, params, _, callback ->
            refreshLatchCommands.add(method to params)
            val canned: JSONObject? = when (method) {
                "Network.getCookies" -> JSONObject().apply { put("cookies", JSONArray()) }
                "DOMStorage.getDOMStorageItems" -> JSONObject().apply { put("entries", JSONArray()) }
                "IndexedDB.requestDatabaseNames" -> JSONObject().apply {
                    put("databaseNames", JSONArray().put("mydb"))
                }
                "IndexedDB.requestDatabase" -> JSONObject().apply {
                    put(
                        "databaseWithObjectStores",
                        JSONObject().apply {
                            put(
                                "objectStores",
                                JSONArray().put(JSONObject().apply { put("name", "store1") }),
                            )
                        },
                    )
                }
                "IndexedDB.requestData" -> JSONObject().apply {
                    put(
                        "objectStoreDataEntries",
                        JSONArray().put(
                            JSONObject().apply {
                                put("key", JSONObject().apply { put("description", "key-1") })
                                put("value", JSONObject().apply { put("description", "value-1") })
                            },
                        ),
                    )
                    put("hasMore", true)
                }
                else -> null
            }
            callback(canned, null)
        }
        val observer = buildObserver(client)

        observer.refreshStorage()

        val snapshot = observer.stateFlow.value
        assertEquals(listOf("mydb"), snapshot.indexedDbDatabases)
        assertEquals(listOf("key-1: value-1"), snapshot.indexedDbRecords["mydb/store1"])
        assertEquals(true, snapshot.indexedDbHasMore["mydb/store1"])
    }

    @Test
    fun `requestData with no matching entries yields an empty record list and hasMore false`() {
        val client = CdpClient("localhost", 9222)
        client.sendHookForTest = { method, _, _, callback ->
            val canned: JSONObject? = when (method) {
                "Network.getCookies" -> JSONObject().apply { put("cookies", JSONArray()) }
                "DOMStorage.getDOMStorageItems" -> JSONObject().apply { put("entries", JSONArray()) }
                "IndexedDB.requestDatabaseNames" -> JSONObject().apply {
                    put("databaseNames", JSONArray().put("emptydb"))
                }
                "IndexedDB.requestDatabase" -> JSONObject().apply {
                    put(
                        "databaseWithObjectStores",
                        JSONObject().apply {
                            put(
                                "objectStores",
                                JSONArray().put(JSONObject().apply { put("name", "storeA") }),
                            )
                        },
                    )
                }
                "IndexedDB.requestData" -> JSONObject().apply {
                    put("objectStoreDataEntries", JSONArray())
                    put("hasMore", false)
                }
                else -> null
            }
            callback(canned, null)
        }
        val observer = buildObserver(client)

        observer.refreshStorage()

        val snapshot = observer.stateFlow.value
        assertEquals(emptyList<String>(), snapshot.indexedDbRecords["emptydb/storeA"])
        assertFalse(snapshot.indexedDbHasMore["emptydb/storeA"] ?: true)
    }
}
