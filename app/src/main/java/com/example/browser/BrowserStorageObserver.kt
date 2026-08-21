package com.example.browser

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * B6: Browser storage correlation (cookies / localStorage / IndexedDB) via CDP.
 *
 * Three independent, real CDP round-trips, each genuinely parsed into an app-visible model:
 *
 *  1. **Cookies** -- `Network.getCookies` (no params: returns cookies visible to the current
 *     page/target) -> list of `{"name","value","domain","path","expires","httpOnly","secure",
 *     "sameSite"}`.
 *  2. **localStorage** -- `DOMStorage.enable` + `DOMStorage.getDOMStorageItems` with
 *     `{"storageId":{"securityOrigin":<origin>,"isLocalStorage":true}}` -> `[[key,value],...]`
 *     pairs. Requires knowing the page's origin, which this observer reads off the CDP
 *     target info already captured by [CdpClient] when it attached (falls back to asking the
 *     caller to supply it explicitly via [refreshStorage] if unknown).
 *  3. **IndexedDB** -- `IndexedDB.enable` + `IndexedDB.requestDatabaseNames` with
 *     `{"securityOrigin":<origin>}` -> database names only (`IndexedDB.requestData` for full
 *     record dumps is NOT implemented -- see scope caveat below).
 *
 * Snapshot-based, not event-streamed: Chrome does not push storage-change events for
 * cookies/localStorage the way it does for `Network.*`, so this observer polls on demand via
 * [refreshStorage] (call it from the UI, e.g. a manual "Refresh" action next to the browser
 * bridge card) rather than pretending to have a live event stream. The result lands in
 * [stateFlow] either way, so it's genuinely visible state, not console-log-only debug output.
 *
 * Scope caveat: IndexedDB record contents (`IndexedDB.requestData`) and localStorage
 * per-key change notifications (`DOMStorage.domStorageItemAdded/Removed/Updated`) are not
 * implemented -- only database *names* and current localStorage *snapshots* are captured.
 * Documented here rather than silently omitted, matching this project's convention for
 * partially-scoped features (compare the P2/T1 "Remaining gap" notes in TODO_v5.md).
 */
class BrowserStorageObserver(
    private val client: CdpClient,
    private val originProvider: () -> String?,
) {
    companion object {
        private const val TAG = "BrowserStorageObserver"
    }

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
        val lastRefreshed: Long = 0L,
        val lastError: String? = null,
    )

    private val _snapshot = MutableStateFlow(StorageSnapshot())
    val stateFlow: StateFlow<StorageSnapshot> = _snapshot.asStateFlow()

    fun start() {
        client.send("DOMStorage.enable")
        client.send("IndexedDB.enable")
    }

    fun stop() {
        client.send("DOMStorage.disable")
        client.send("IndexedDB.disable")
    }

    /** Pull a fresh snapshot of cookies + localStorage + IndexedDB database names. */
    fun refreshStorage() {
        fetchCookies()
        val origin = originProvider()
        if (origin != null) {
            fetchLocalStorage(origin)
            fetchIndexedDbNames(origin)
        } else {
            Log.i(TAG, "No known page origin -- skipping localStorage/IndexedDB fetch (cookies still fetched)")
        }
    }

    private fun fetchCookies() {
        client.send("Network.getCookies") { result, error ->
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
        client.send("DOMStorage.getDOMStorageItems", JSONObject().apply { put("storageId", storageId) }) { result, error ->
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

    private fun fetchIndexedDbNames(origin: String) {
        client.send("IndexedDB.requestDatabaseNames", JSONObject().apply { put("securityOrigin", origin) }) { result, error ->
            if (error != null || result == null) {
                Log.w(TAG, "requestDatabaseNames failed: $error")
                return@send
            }
            try {
                val names = result.optJSONArray("databaseNames") ?: org.json.JSONArray()
                val list = (0 until names.length()).map { names.optString(it, "") }
                _snapshot.value = _snapshot.value.copy(indexedDbDatabases = list, lastRefreshed = System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "IndexedDB parse failed: ${e.message}")
            }
        }
    }
}
