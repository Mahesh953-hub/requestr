package com.example.browser

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * B6/W9: Browser storage correlation (cookies / localStorage / IndexedDB) via CDP.
 *
 * Three independent, real CDP round-trips, each genuinely parsed into an app-visible model:
 *
 *  1. **Cookies** -- `Network.getCookies` (no params: returns cookies visible to the current
 *     page/target) -> list of `{"name","value","domain","path","expires","httpOnly","secure",
 *     "sameSite"}`.
 *  2. **localStorage** -- `DOMStorage.enable` + `DOMStorage.getDOMStorageItems` with
 *     `{"storageId":{"securityOrigin":<origin>,"isLocalStorage":true}}` -> `[[key,value],...]`
 *     pairs for the initial snapshot, kept live afterwards (W9) by subscribing to
 *     `DOMStorage.domStorageItemAdded`/`domStorageItemRemoved`/`domStorageItemUpdated`/
 *     `domStorageItemsCleared` and applying each delta directly to [localStorage] -- so the
 *     Settings UI reflects real-time page writes, not just a stale poll.
 *  3. **IndexedDB** -- `IndexedDB.enable` + `IndexedDB.requestDatabaseNames` with
 *     `{"securityOrigin":<origin>}` for database names, then (W9) for each database,
 *     `IndexedDB.requestDatabase` to enumerate its object stores, then `IndexedDB.requestData`
 *     per object store to read real records into [indexedDbRecords] -- not just names.
 *
 * Snapshot-plus-events, not pure polling: cookies still require an explicit [refreshStorage]
 * call (Chrome has no cookie-change event over CDP), but localStorage now updates live once
 * `DOMStorage.enable` is on, matching how a real DevTools panel behaves.
 *
 * W8: takes an optional [sessionId] so one observer instance is scoped to exactly one
 * attached CDP target under `Target.setAutoAttach`'s flattened-session model; `null` keeps
 * this usable against a single-target/legacy connection.
 */
class BrowserStorageObserver(
    private val client: CdpClient,
    private val sessionId: String? = null,
    private val originProvider: () -> String?,
) {
    companion object {
        private const val TAG = "BrowserStorageObserver"

        /** Max IndexedDB records fetched per object store per refresh -- avoids unbounded pulls. */
        private const val INDEXEDDB_PAGE_SIZE = 100

        /**
         * v6.1 Domain B: how many of the most recent `DOMStorage` change events are retained for
         * the Settings card's live localStorage stream. Bounded on purpose -- a busy page can write
         * thousands of keys, and this list exists to show *recent* activity, not to be a log.
         */
        internal const val MAX_RETAINED_EVENTS = 20
    }

    /** Which `DOMStorage.domStorageItem*` event produced a [DomStorageEvent]. */
    enum class DomStorageEventKind { ADDED, UPDATED, REMOVED, CLEARED }

    /**
     * v6.1 Domain B: one real, observed localStorage change. Recorded only from a genuine CDP
     * `DOMStorage` event -- never synthesised from a snapshot diff, so an empty list honestly means
     * "the page has not written to localStorage since we attached".
     */
    data class DomStorageEvent(
        val key: String?,
        val kind: DomStorageEventKind,
        val oldValue: String?,
        val newValue: String?,
        val atMs: Long = System.currentTimeMillis(),
    )

    data class CookieInfo(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val httpOnly: Boolean,
        val secure: Boolean,
        val sameSite: String?,
    )

    data class StorageSnapshot(
        val cookies: List<CookieInfo> = emptyList(),
        val localStorage: Map<String, String> = emptyMap(),
        val indexedDbDatabases: List<String> = emptyList(),
        /** database name -> rendered "key: value"-shaped records (W9). */
        val indexedDbRecords: Map<String, List<String>> = emptyMap(),
        /** database name -> true when more records exist past [INDEXEDDB_PAGE_SIZE] (W9). */
        val indexedDbHasMore: Map<String, Boolean> = emptyMap(),
        /**
         * v6.1 Domain B: most-recent-first, bounded to [MAX_RETAINED_EVENTS], live `DOMStorage`
         * change events. Distinct from [localStorage], which is the current key/value state.
         */
        val recentEvents: List<DomStorageEvent> = emptyList(),
        val lastRefreshed: Long = 0L,
        val lastError: String? = null,
    )

    private val _snapshot = MutableStateFlow(StorageSnapshot())
    val stateFlow: StateFlow<StorageSnapshot> = _snapshot.asStateFlow()

    fun start() {
        client.send("DOMStorage.enable", sessionId = sessionId)
        client.send("IndexedDB.enable", sessionId = sessionId)
        client.on("DOMStorage.domStorageItemAdded", sessionId) { params -> applyItemAdded(params) }
        client.on("DOMStorage.domStorageItemRemoved", sessionId) { params -> applyItemRemoved(params) }
        client.on("DOMStorage.domStorageItemUpdated", sessionId) { params -> applyItemUpdated(params) }
        client.on("DOMStorage.domStorageItemsCleared", sessionId) { params -> applyItemsCleared(params) }
    }

    fun stop() {
        client.send("DOMStorage.disable", sessionId = sessionId)
        client.send("IndexedDB.disable", sessionId = sessionId)
    }

    /** Pull a fresh snapshot of cookies + localStorage + IndexedDB database names and records. */
    fun refreshStorage() {
        fetchCookies()
        val origin = originProvider()
        if (origin != null) {
            fetchLocalStorage(origin)
            fetchIndexedDbNamesAndRecords(origin)
        } else {
            Log.i(TAG, "No known page origin -- skipping localStorage/IndexedDB fetch (cookies still fetched)")
        }
    }

    // ── DOMStorage live-event deltas (W9) ──────────────────────────────

    /** `DOMStorage.domStorageItemAdded`: `{"storageId":{...},"key":"...","newValue":"..."}`. */
    private fun applyItemAdded(params: JSONObject) {
        val key = params.optString("key", "")
        if (key.isEmpty()) return
        val value = params.optString("newValue", "")
        _snapshot.value = _snapshot.value
            .copy(localStorage = _snapshot.value.localStorage + (key to value))
            .withEvent(DomStorageEvent(key, DomStorageEventKind.ADDED, null, value))
    }

    /** `DOMStorage.domStorageItemRemoved`: `{"storageId":{...},"key":"..."}`. */
    private fun applyItemRemoved(params: JSONObject) {
        val key = params.optString("key", "")
        if (key.isEmpty()) return
        val previous = _snapshot.value.localStorage[key]
        _snapshot.value = _snapshot.value
            .copy(localStorage = _snapshot.value.localStorage - key)
            .withEvent(DomStorageEvent(key, DomStorageEventKind.REMOVED, previous, null))
    }

    /** `DOMStorage.domStorageItemUpdated`: `{"storageId":{...},"key":"...","oldValue":"...","newValue":"..."}`. */
    private fun applyItemUpdated(params: JSONObject) {
        val key = params.optString("key", "")
        if (key.isEmpty()) return
        val value = params.optString("newValue", "")
        val old = params.optString("oldValue", "").ifEmpty { _snapshot.value.localStorage[key] }
        _snapshot.value = _snapshot.value
            .copy(localStorage = _snapshot.value.localStorage + (key to value))
            .withEvent(DomStorageEvent(key, DomStorageEventKind.UPDATED, old, value))
    }

    /** `DOMStorage.domStorageItemsCleared`: `{"storageId":{...}}` -- the whole store was cleared. */
    private fun applyItemsCleared(params: JSONObject) {
        _snapshot.value = _snapshot.value
            .copy(localStorage = emptyMap())
            .withEvent(DomStorageEvent(null, DomStorageEventKind.CLEARED, null, null))
    }

    /** Prepends [event] to [StorageSnapshot.recentEvents], bounded to [MAX_RETAINED_EVENTS]. */
    private fun StorageSnapshot.withEvent(event: DomStorageEvent): StorageSnapshot =
        copy(recentEvents = (listOf(event) + recentEvents).take(MAX_RETAINED_EVENTS))

    // ── Snapshot fetches ────────────────────────────────────────────────

    private fun fetchCookies() {
        client.send("Network.getCookies", sessionId = sessionId) { result, error ->
            if (error != null || result == null) {
                _snapshot.value = _snapshot.value.copy(lastError = error ?: "getCookies failed")
                return@send
            }
            try {
                val arr = result.optJSONArray("cookies") ?: org.json.JSONArray()
                val cookies = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    CookieInfo(
                        name = c.optString("name", ""),
                        value = c.optString("value", ""),
                        domain = c.optString("domain", ""),
                        path = c.optString("path", "/"),
                        httpOnly = c.optBoolean("httpOnly", false),
                        secure = c.optBoolean("secure", false),
                        sameSite = c.optString("sameSite", "").ifEmpty { null },
                    )
                }
                _snapshot.value = _snapshot.value.copy(
                    cookies = cookies,
                    lastRefreshed = System.currentTimeMillis(),
                    lastError = null,
                )
            } catch (e: Exception) {
                _snapshot.value = _snapshot.value.copy(lastError = "cookie parse failed: ${e.message}")
            }
        }
    }

    private fun fetchLocalStorage(origin: String) {
        val storageId = JSONObject().apply {
            put("securityOrigin", origin)
            put("isLocalStorage", true)
        }
        client.send("DOMStorage.getDOMStorageItems", JSONObject().apply { put("storageId", storageId) }, sessionId = sessionId) { result, error ->
            if (error != null || result == null) {
                Log.w(TAG, "getDOMStorageItems failed: $error")
                return@send
            }
            try {
                val entries = result.optJSONArray("entries") ?: org.json.JSONArray()
                val map = mutableMapOf<String, String>()
                for (i in 0 until entries.length()) {
                    val pair = entries.optJSONArray(i) ?: continue
                    if (pair.length() >= 2) map[pair.optString(0, "")] = pair.optString(1, "")
                }
                _snapshot.value = _snapshot.value.copy(localStorage = map, lastRefreshed = System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "DOMStorage parse failed: ${e.message}")
            }
        }
    }

    private fun fetchIndexedDbNamesAndRecords(origin: String) {
        client.send("IndexedDB.requestDatabaseNames", JSONObject().apply { put("securityOrigin", origin) }, sessionId = sessionId) { result, error ->
            if (error != null || result == null) {
                Log.w(TAG, "requestDatabaseNames failed: $error")
                return@send
            }
            try {
                val names = result.optJSONArray("databaseNames") ?: org.json.JSONArray()
                val list = (0 until names.length()).map { names.optString(it, "") }
                _snapshot.value = _snapshot.value.copy(indexedDbDatabases = list, lastRefreshed = System.currentTimeMillis())
                list.forEach { dbName -> fetchIndexedDbRecords(origin, dbName) }
            } catch (e: Exception) {
                Log.w(TAG, "IndexedDB parse failed: ${e.message}")
            }
        }
    }

    /** `IndexedDB.requestDatabase` -> enumerate object stores, then `IndexedDB.requestData` each. */
    private fun fetchIndexedDbRecords(origin: String, dbName: String) {
        val params = JSONObject().apply {
            put("securityOrigin", origin)
            put("databaseName", dbName)
        }
        client.send("IndexedDB.requestDatabase", params, sessionId = sessionId) { result, error ->
            if (error != null || result == null) {
                Log.w(TAG, "requestDatabase($dbName) failed: $error")
                return@send
            }
            try {
                val dbJson = result.optJSONObject("databaseWithObjectStores") ?: return@send
                val stores = dbJson.optJSONArray("objectStores") ?: org.json.JSONArray()
                for (i in 0 until stores.length()) {
                    val storeName = stores.optJSONObject(i)?.optString("name", "") ?: continue
                    if (storeName.isNotEmpty()) fetchIndexedDbObjectStoreData(origin, dbName, storeName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestDatabase($dbName) parse failed: ${e.message}")
            }
        }
    }

    private fun fetchIndexedDbObjectStoreData(origin: String, dbName: String, storeName: String) {
        val params = JSONObject().apply {
            put("securityOrigin", origin)
            put("databaseName", dbName)
            put("objectStoreName", storeName)
            put("indexName", "")
            put("skipCount", 0)
            put("pageSize", INDEXEDDB_PAGE_SIZE)
        }
        client.send("IndexedDB.requestData", params, sessionId = sessionId) { result, error ->
            if (error != null || result == null) {
                Log.w(TAG, "requestData($dbName/$storeName) failed: $error")
                return@send
            }
            try {
                val entries = result.optJSONArray("objectStoreDataEntries") ?: org.json.JSONArray()
                val hasMore = result.optBoolean("hasMore", false)
                val records = (0 until entries.length()).map { i ->
                    val e = entries.optJSONObject(i)
                    val key = e?.optJSONObject("key")?.optString("description", "?") ?: "?"
                    val value = e?.optJSONObject("value")?.optString("description", "?") ?: "?"
                    "$key: $value"
                }
                val key = "$dbName/$storeName"
                _snapshot.value = _snapshot.value.copy(
                    indexedDbRecords = _snapshot.value.indexedDbRecords + (key to records),
                    indexedDbHasMore = _snapshot.value.indexedDbHasMore + (key to hasMore),
                    lastRefreshed = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "requestData($dbName/$storeName) parse failed: ${e.message}")
            }
        }
    }
}
