package com.example.browser

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * B5: Service Worker observation via CDP.
 *
 * Enables the `ServiceWorker` domain and listens for its three lifecycle events:
 *  - `ServiceWorker.workerRegistrationUpdated` -- registration created/updated
 *    (`{"registrations":[{"registrationId","scopeURL","isDeleted"}]}`)
 *  - `ServiceWorker.workerVersionUpdated` -- a version transitions through
 *    new/installing/installed/activating/activated/redundant
 *    (`{"versions":[{"versionId","registrationId","scriptURL","runningStatus","status"}]}`)
 *  - `ServiceWorker.workerErrorReported` -- a runtime error inside the worker
 *    (`{"errorMessage":{"errorMessage","sourceURL","lineNumber","columnNumber"}}`)
 *
 * Surfaces a live snapshot ([stateFlow]) of every known registration/version keyed by
 * `registrationId`, so the Settings UI can show "N service workers observed" and their
 * current status without the caller having to hand-roll event bookkeeping. This is a genuine
 * CDP event -> app-visible-state pipeline, not a stub that discards what it receives.
 *
 * W8: takes an optional [sessionId] so one observer instance is scoped to exactly one
 * attached CDP target under `Target.setAutoAttach`'s flattened-session model; `null` keeps
 * this usable against a single-target/legacy connection.
 */
class ServiceWorkerObserver(
    private val client: CdpClient,
    private val sessionId: String? = null,
) {
    companion object {
        private const val TAG = "ServiceWorkerObserver"
    }

    data class WorkerRegistration(
        val registrationId: String,
        val scopeUrl: String,
        var scriptUrl: String? = null,
        var runningStatus: String? = null,
        var status: String? = null,
        var lastError: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val _registrations = MutableStateFlow<Map<String, WorkerRegistration>>(emptyMap())
    val stateFlow: StateFlow<Map<String, WorkerRegistration>> = _registrations.asStateFlow()

    fun start() {
        client.send("ServiceWorker.enable", sessionId = sessionId)
        client.on("ServiceWorker.workerRegistrationUpdated", sessionId) { params -> handleRegistrationUpdated(params) }
        client.on("ServiceWorker.workerVersionUpdated", sessionId) { params -> handleVersionUpdated(params) }
        client.on("ServiceWorker.workerErrorReported", sessionId) { params -> handleErrorReported(params) }
    }

    fun stop() {
        client.send("ServiceWorker.disable", sessionId = sessionId)
    }

    private fun handleRegistrationUpdated(params: JSONObject) {
        try {
            val regs = params.optJSONArray("registrations") ?: return
            val current = _registrations.value.toMutableMap()
            for (i in 0 until regs.length()) {
                val r = regs.getJSONObject(i)
                val id = r.optString("registrationId")
                if (id.isEmpty()) continue
                if (r.optBoolean("isDeleted", false)) {
                    current.remove(id)
                } else {
                    val existing = current[id]
                    current[id] = (existing ?: WorkerRegistration(id, r.optString("scopeURL", ""))).copy(
                        scopeUrl = r.optString("scopeURL", existing?.scopeUrl ?: ""),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            _registrations.value = current
        } catch (e: Exception) {
            Log.w(TAG, "workerRegistrationUpdated parse failed: ${e.message}")
        }
    }

    private fun handleVersionUpdated(params: JSONObject) {
        try {
            val versions = params.optJSONArray("versions") ?: return
            val current = _registrations.value.toMutableMap()
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                val regId = v.optString("registrationId")
                if (regId.isEmpty()) continue
                val existing = current[regId] ?: WorkerRegistration(regId, "")
                existing.scriptUrl = v.optString("scriptURL", existing.scriptUrl ?: "").ifEmpty { existing.scriptUrl }
                existing.runningStatus = v.optString("runningStatus", existing.runningStatus ?: "").ifEmpty { existing.runningStatus }
                existing.status = v.optString("status", existing.status ?: "").ifEmpty { existing.status }
                existing.updatedAt = System.currentTimeMillis()
                current[regId] = existing
            }
            _registrations.value = current
        } catch (e: Exception) {
            Log.w(TAG, "workerVersionUpdated parse failed: ${e.message}")
        }
    }

    private fun handleErrorReported(params: JSONObject) {
        try {
            val err = params.optJSONObject("errorMessage") ?: return
            val regId = err.optString("registrationId", "")
            val message = "${err.optString("errorMessage", "unknown error")} @ ${err.optString("sourceURL", "?")}:${err.optInt("lineNumber", -1)}"
            if (regId.isNotEmpty()) {
                val current = _registrations.value.toMutableMap()
                val existing = current[regId] ?: WorkerRegistration(regId, "")
                existing.lastError = message
                existing.updatedAt = System.currentTimeMillis()
                current[regId] = existing
                _registrations.value = current
            }
            Log.w(TAG, "Service worker error: $message")
        } catch (e: Exception) {
            Log.w(TAG, "workerErrorReported parse failed: ${e.message}")
        }
    }
}
