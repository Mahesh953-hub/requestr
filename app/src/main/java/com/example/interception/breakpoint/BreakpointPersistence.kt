package com.example.interception.breakpoint

import android.util.Log
import com.example.data.local.PausedFlowDao
import com.example.data.local.PausedFlowEntity
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S4 task 31: mirrors the in-memory [BreakpointRegistry] into `breakpoint_paused_flows` so the
 * UI / LAN dashboard / local API can list what is paused without reaching into the engine.
 * Every write is fire-and-forget off the relay thread; a lost row only affects the list, never
 * the flow itself (the registry is authoritative and always auto-resumes).
 */
class BreakpointPersistence(
    private val dao: PausedFlowDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : BreakpointRegistry.Listener {

    /** Rows left by a previous process are stale by definition -- sweep them at attach time. */
    fun attach(registry: BreakpointRegistry) {
        scope.launch { runCatching { dao.deleteAll() }.onFailure { Log.w(TAG, "startup sweep failed: ${it.message}") } }
        registry.addListener(this)
    }

    fun detach(registry: BreakpointRegistry) = registry.removeListener(this)

    override fun onPaused(flow: BreakpointRegistry.PausedFlow) {
        val entity = toEntity(flow)
        scope.launch { runCatching { dao.upsert(entity) }.onFailure { Log.w(TAG, "upsert failed: ${it.message}") } }
    }

    override fun onReleased(flow: BreakpointRegistry.PausedFlow, action: BreakpointAction) {
        scope.launch { runCatching { dao.deleteByFlowId(flow.flowId) }.onFailure { Log.w(TAG, "delete failed: ${it.message}") } }
    }

    companion object {
        private const val TAG = "BreakpointPersistence"
        /** Bounded preview: the full body stays with the relay thread. */
        const val MAX_SNAPSHOT_BODY_CHARS = 8 * 1024

        fun toEntity(flow: BreakpointRegistry.PausedFlow): PausedFlowEntity {
            val snapshot = snapshotOf(flow.message)
            return PausedFlowEntity(
                flowId = flow.flowId,
                ruleId = flow.rule.id,
                ruleName = flow.rule.name,
                phase = flow.phase.name,
                host = flow.host,
                url = flow.url,
                method = flow.method,
                requestSnapshot = if (flow.phase == FlowAuditTrail.Phase.REQUEST) snapshot else null,
                responseSnapshot = if (flow.phase == FlowAuditTrail.Phase.RESPONSE) snapshot else null,
                pausedAt = flow.pausedAt,
                timeoutAt = flow.timeoutAt,
                state = "PAUSED",
            )
        }

        /** Human-readable head + headers + bounded body preview, never the raw binary bytes. */
        fun snapshotOf(m: ParsedHttpMessage): String {
            val sb = StringBuilder()
            if (m.isRequest) sb.append(m.method?.name ?: "GET").append(' ').append(m.url).append(' ').append(m.version)
            else sb.append(m.version).append(' ').append(m.statusCode).append(' ').append(m.statusMessage)
            sb.append('\n')
            for ((k, v) in m.headers) sb.append(k).append(": ").append(v).append('\n')
            sb.append('\n')
            if (m.isBodyBinary) {
                val n = m.replacedBodyBytes?.size ?: m.rawBodyBytes?.size ?: 0
                sb.append("<binary body, ").append(n).append(" bytes>")
            } else {
                sb.append(m.body.take(MAX_SNAPSHOT_BODY_CHARS))
                if (m.body.length > MAX_SNAPSHOT_BODY_CHARS) sb.append("\n<truncated ").append(m.body.length - MAX_SNAPSHOT_BODY_CHARS).append(" chars>")
            }
            return sb.toString()
        }
    }
}
