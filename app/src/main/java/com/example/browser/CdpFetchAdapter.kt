package com.example.browser

import android.util.Log
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.interception.RuleEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

/**
 * B3: CDP Fetch adapter -- allow/block/modify browser requests in-flight.
 *
 * Enables the `Fetch` domain (`Fetch.enable`) with a stage-request pattern (pauses every
 * request before it is sent) and, on each `Fetch.requestPaused` event, decides whether to:
 *  - `Fetch.failRequest` (BLOCK)
 *  - `Fetch.continueRequest` with a rewritten `url` (REDIRECT)
 *  - `Fetch.continueRequest` with rewritten `headers`/`postData` (MODIFY_REQUEST)
 *  - `Fetch.continueRequest` unmodified (LOG_ONLY / no matching rule)
 *
 * Reuses requestr's existing shared rule engine, [RuleEnforcer], and the same
 * `intercept_rules` Room table the MITM/VPN paths already read from (see
 * [com.example.interception.MitmProxyEngine]). This means a rule the user creates once in
 * Settings/Capture-Setup ("Rules & Modifiers") applies uniformly whether the traffic was
 * captured via VPN/MITM *or* via a live Chrome CDP session -- there is no separate
 * browser-only rule store to keep in sync.
 *
 * Scope caveat: response-stage interception (`Fetch.enable` with
 * `responseStage: HeadersReceived`, `Fetch.fulfillRequest`/`getResponseBody`) is not
 * implemented -- only the request stage is paused/modified. MODIFY_RESPONSE rules are
 * therefore a no-op for CDP-observed traffic today (they still work for MITM/VPN traffic).
 * This mirrors this project's convention of documenting real gaps in code rather than
 * quietly no-op'ing rule types (see the P2/T1/M1 "Remaining gap" notes in TODO_v5.md for the
 * house style this follows).
 */
class CdpFetchAdapter(
    private val client: CdpClient,
    private val context: android.content.Context,
) {
    companion object {
        private const val TAG = "CdpFetchAdapter"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Count of requests allowed/blocked/modified, for the Settings UI (best-effort, in-memory). */
    @Volatile var allowedCount: Int = 0
        private set
    @Volatile var blockedCount: Int = 0
        private set
    @Volatile var modifiedCount: Int = 0
        private set

    fun start() {
        val params = JSONObject().apply {
            put("patterns", org.json.JSONArray().put(JSONObject().apply {
                put("urlPattern", "*")
                put("requestStage", "Request")
            }))
        }
        client.send("Fetch.enable", params)
        client.on("Fetch.requestPaused") { event -> handleRequestPaused(event) }
    }

    fun stop() {
        client.send("Fetch.disable")
    }

    private fun handleRequestPaused(event: JSONObject) {
        val fetchRequestId = event.optString("requestId")
        if (fetchRequestId.isEmpty()) return
        val request = event.optJSONObject("request") ?: return
        val url = request.optString("url", "")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url

        scope.launch {
            try {
                val db = RequestrDatabase.get(context)
                val rule = RuleEnforcer.getMatchingRule(db, url, host)
                when (rule?.action ?: RuleAction.LOG_ONLY) {
                    RuleAction.BLOCK -> {
                        blockedCount++
                        client.send(
                            "Fetch.failRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("errorReason", "BlockedByClient")
                            }
                        )
                        Log.i(TAG, "BLOCK $url (rule=${rule?.name})")
                    }
                    RuleAction.REDIRECT -> {
                        modifiedCount++
                        val target = rule?.replacementValue?.takeIf { it.isNotBlank() } ?: url
                        client.send(
                            "Fetch.continueRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("url", target)
                            }
                        )
                        Log.i(TAG, "REDIRECT $url -> $target")
                    }
                    RuleAction.MODIFY_REQUEST -> {
                        modifiedCount++
                        val continueParams = buildModifiedContinueParams(fetchRequestId, request, rule?.replacementValue)
                        client.send("Fetch.continueRequest", continueParams)
                        Log.i(TAG, "MODIFY_REQUEST $url")
                    }
                    // FORCE_MITM is a CONNECT-level disposition (see ConnectControl) with no
                    // meaning at the Fetch.requestPaused stage -- CDP already observes decrypted
                    // browser traffic, so there's nothing further to MITM here. Treat it the same
                    // as LOG_ONLY: allow the request through unmodified.
                    RuleAction.MODIFY_RESPONSE, RuleAction.LOG_ONLY, RuleAction.FORCE_MITM -> {
                        allowedCount++
                        client.send(
                            "Fetch.continueRequest",
                            JSONObject().apply { put("requestId", fetchRequestId) }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestPaused handling failed: ${e.message}")
                // Fail open: never leave a paused request stuck, always resume it plainly.
                client.send("Fetch.continueRequest", JSONObject().apply { put("requestId", fetchRequestId) })
            }
        }
    }

    /**
     * Builds `Fetch.continueRequest` params applying a MODIFY_REQUEST rule's
     * `{"addHeaders":{...},"removeHeaders":[...],"bodyReplace":"..."}` config -- the same JSON
     * shape [RuleEnforcer.applyRequestModification] uses for MITM traffic, reused here so rule
     * authors don't need to learn a second config format for CDP-observed requests.
     */
    private fun buildModifiedContinueParams(fetchRequestId: String, request: JSONObject, config: String?): JSONObject {
        val headersJson = request.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        headersJson?.keys()?.forEach { key -> headers[key] = headersJson.optString(key, "") }
        var body: String? = request.optString("postData", "").ifEmpty { null }

        if (!config.isNullOrBlank()) {
            runCatching {
                val cfg = JSONObject(config)
                if (cfg.has("addHeaders")) {
                    val add = cfg.getJSONObject("addHeaders")
                    add.keys().forEach { key -> headers[key] = add.optString(key, "") }
                }
                if (cfg.has("removeHeaders")) {
                    val remove = cfg.getJSONArray("removeHeaders")
                    for (i in 0 until remove.length()) headers.remove(remove.getString(i))
                }
                if (cfg.has("bodyReplace")) {
                    body = cfg.getString("bodyReplace")
                }
            }.onFailure { Log.w(TAG, "Failed to apply MODIFY_REQUEST config: ${it.message}") }
        }

        val headerArray = org.json.JSONArray()
        headers.forEach { (k, v) -> headerArray.put(JSONObject().apply { put("name", k); put("value", v) }) }

        return JSONObject().apply {
            put("requestId", fetchRequestId)
            put("headers", headerArray)
            if (body != null) {
                put("postData", Base64.getEncoder().encodeToString(body!!.toByteArray(Charsets.UTF_8)))
            }
        }
    }
}
