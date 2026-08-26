package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.CdpCardState
import com.example.data.models.CdpConnectionPhase
import com.example.data.models.CdpReconnectState
import com.example.data.models.CdpServiceWorkerRow
import com.example.data.models.CdpStorageState
import com.example.data.models.CdpTargetRow
import com.example.data.models.LocalStorageChange
import com.example.data.models.LocalStorageChangeKind

/**
 * v6.1 Phase A / Domain B, surface 2: the Settings "BROWSER BRIDGE (CDP)" card, ported from
 * `runtime-cdp-connected-*.html` (multi-target attach, rule-enforcement tiles, live storage streams,
 * service workers), `runtime-cdp-disconnected-*.html` (editable host:port + empty state) and
 * `runtime-cdp-reconnecting-*.html` (backoff strip + stale last-known targets).
 *
 * Pure renderer over [CdpCardState] from
 * [com.example.runtime.RuntimeInstrumentationStateProvider]. Lifecycle stays with
 * [com.example.browser.BrowserBridgeManager]: this card only raises [onToggleConnection] and
 * [onRefreshStorage].
 *
 * Honesty rules the card enforces:
 *  - RECONNECTING is only shown when the client's retry loop is genuinely running, and the backoff
 *    strip renders the shipped `CdpClient` schedule (1s→16s, bounded attempts), not the mockup's
 *    illustrative "1s→30s cap" ladder the engine does not implement;
 *  - last-known targets during a drop are explicitly labelled stale;
 *  - a target's expanded view shows the real per-target facts requestr retains (sessionId, targetId,
 *    type, URL, correlated request count) and says plainly that per-request activity is not
 *    replayable, instead of showing the mockup's illustrative request list as if it were live;
 *  - IndexedDB record counts are labelled as partial when any object store reported `hasMore`.
 */
@Composable
fun CdpCard(
    state: CdpCardState,
    hostPortInput: String,
    onHostPortInputChange: (String) -> Unit,
    onToggleConnection: (Boolean) -> Unit,
    onRefreshStorage: () -> Unit,
    expandedTargetSessionId: String?,
    onToggleTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().componentId("cdp-card"),
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            RuntimeSpreadRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Browser Bridge",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Chrome DevTools Protocol",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                BadgePill(text = state.phase.label, color = state.phase.color)
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeStatusDot(color = Color(state.phase.color))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    state.statusMessage,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                summaryLine(state),
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (state.toggleOn) state.endpoint ?: hostPortInput else hostPortInput,
                    onValueChange = onHostPortInputChange,
                    enabled = !state.toggleOn,
                    label = { Text("CDP host:port", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f).componentId("cdp-hostport"),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = state.toggleOn,
                    onCheckedChange = onToggleConnection,
                    modifier = Modifier.componentId("cdp-connect-switch"),
                )
            }
            Text(
                when (state.phase) {
                    CdpConnectionPhase.RECONNECTING ->
                        "Toggling off cancels the retry loop and detaches all targets."
                    CdpConnectionPhase.DISCONNECTED ->
                        "Requires Chrome/Chromium running with --remote-debugging-port. Toggle on to attach."
                    CdpConnectionPhase.FAILED ->
                        "No retry is pending. Toggle off, then on, to try again."
                    else ->
                        "Requires Chrome/Chromium running with --remote-debugging-port"
                },
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.reconnect?.let { ReconnectBlock(it) }

            if (state.targets.isEmpty()) {
                DisconnectedEmptyState()
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                RuntimeSpreadRow(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        if (state.targetsAreStale) "LAST KNOWN TARGETS" else "ATTACHED TARGETS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (state.targetsAreStale) {
                            "stale · refreshes on reconnect"
                        } else {
                            "${state.targets.size} · sessionId-routed"
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (state.targetsAreStale) RuntimeAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                state.targets.forEach { target ->
                    TargetRow(
                        target = target,
                        expanded = expandedTargetSessionId == target.sessionId,
                        onClick = { onToggleTarget(target.sessionId) },
                    )
                }
            }

            // Every observation block is gated on a live connection, matching the reconnecting mockup,
            // which shows only the backoff panel and the stale target list. Fetch interception is not
            // running while the socket is down (the per-target adapters were torn down with their
            // sessions), so leaving the rule tiles up would present dead totals as live enforcement.
            if (state.isConnected) {
                RuleEnforcementBlock(state)
                StorageBlock(state.storage, onRefreshStorage)
                ServiceWorkerBlock(state.serviceWorkers)
            }

            if (state.phase == CdpConnectionPhase.RECONNECTING) {
                Text(
                    "Targets, storage, and service workers reload automatically once the connection is " +
                        "restored — last-known values are shown above until then.",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "Extension bridge: load browser-extension/ (unpacked) in chrome://extensions to relay " +
                    "page-context fetch/XHR data CDP can't see to http://localhost:9876/api/observe.",
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReconnectBlock(reconnect: CdpReconnectState) {
    RuntimeFlatCard(modifier = Modifier.padding(top = 10.dp)) {
        RuntimeSpreadRow {
            Text(
                "Reconnect with backoff",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BadgePill(
                text = "attempt ${reconnect.attempt}/${reconnect.maxAttempts}",
                color = 0xFFF59E0B,
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeStatusDot(color = RuntimeAmber)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (reconnect.nextRetryDelayMs > 0) {
                    "Next retry in ${reconnect.nextRetryDelayMs / 1000}s"
                } else {
                    "Retrying now"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RuntimeAmber,
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            reconnect.backoffScheduleMs.forEachIndexed { index, delayMs ->
                val attempt = index + 1
                val done = attempt < reconnect.attempt
                val current = attempt == reconnect.attempt
                Text(
                    "${delayMs / 1000}s",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        current -> RuntimeAmber
                        done -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            }
        }
        reconnect.connectionLostAtMs?.let { lostAt ->
            Text(
                "elapsed since drop: ${formatElapsed(System.currentTimeMillis() - lostAt)}",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
        RuntimeKvRow(key = "last error", value = reconnect.lastError ?: "none reported")
    }
}

@Composable
private fun TargetRow(target: CdpTargetRow, expanded: Boolean, onClick: () -> Unit) {
    RuntimeVRow(
        railColor = if (target.stale) RuntimeGray else if (expanded) RuntimeGreen else RuntimeGray,
        onClick = onClick,
        modifier = Modifier.componentId("cdp-target-${target.sessionId}"),
    ) {
        RuntimeSpreadRow {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Tab,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        target.title.ifBlank { "(untitled ${target.type.ifBlank { "target" }})" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        target.url.ifBlank { "(no url reported)" },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BadgePill(text = "${target.requestCount} req", color = 0xFF64748B)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse target" else "Expand target",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            RuntimeKvRow(key = "sessionId", value = target.sessionId)
            RuntimeKvRow(key = "targetId", value = target.targetId.ifBlank { "(not reported)" })
            RuntimeKvRow(key = "type", value = target.type.ifBlank { "(not reported)" })
            RuntimeKvRow(key = "correlated", value = "${target.requestCount} network events")
            Text(
                if (target.stale) {
                    "Streams for this target are paused — it will re-attach with a fresh sessionId once " +
                        "the connection is restored."
                } else {
                    "Per-request activity is not retained for replay: CDP events are correlated and " +
                        "persisted as they arrive, so this row reports real totals rather than a " +
                        "reconstructed request list."
                },
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisconnectedEmptyState() {
    RuntimeFlatCard(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            "No targets attached",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Connect to discover browser tabs, service workers, and storage. Each attached tab gets its " +
                "own sessionId; network, Fetch, and storage streams route per-target.",
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimeSubLabel("WHEN CONNECTED")
        listOf(
            "Per-tab lifecycle & request counts (multi-target attach)",
            "Reconnect with exponential backoff (1s→16s, ${com.example.browser.CdpClient.MAX_RECONNECT_ATTEMPTS} attempts)",
            "Fetch interception — request-stage & response-stage body modification",
            "Storage streams: cookies, localStorage change events, IndexedDB record counts",
            "Service-worker registrations & statuses",
        ).forEach {
            Text(
                "· $it",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleEnforcementBlock(state: CdpCardState) {
    RuntimeSubLabel("RULE ENFORCEMENT")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RuntimeTile(
            label = "Blocked",
            labelColor = RuntimeRed,
            value = state.rules.blockedRequestStage.toString(),
            valueColor = RuntimeRed,
            subLine = "request-stage",
            modifier = Modifier.weight(1f),
        )
        RuntimeTile(
            label = "Modified",
            labelColor = RuntimeAmber,
            value = state.rules.modifiedTotal.toString(),
            valueColor = RuntimeAmber,
            subLine = "${state.rules.modifiedResponseStage} response-stage",
            modifier = Modifier.weight(1f),
        )
        RuntimeTile(
            label = "Redirected",
            labelColor = RuntimeBlue,
            value = state.rules.redirectedRequestStage.toString(),
            valueColor = RuntimeBlue,
            subLine = "request-stage",
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        "Fetch interception active · ${state.rules.allowed} allowed · " +
            "${state.rules.bodiesCaptured} response bodies captured",
        modifier = Modifier.padding(top = 7.dp),
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StorageBlock(storage: CdpStorageState?, onRefreshStorage: () -> Unit) {
    RuntimeSubLabel("STORAGE")
    if (storage == null) {
        Text(
            "No storage observer is attached yet — cookies, localStorage, and IndexedDB appear once a " +
                "target attaches.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    RuntimeFlatCard {
        RuntimeSpreadRow {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cookie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Cookies",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${storage.cookieCount} · ${storage.cookieDomainCount} domains",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onRefreshStorage, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh storage",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            "Chrome has no cookie-change event over CDP — this is a snapshot, refreshed on demand.",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    RuntimeFlatCard(modifier = Modifier.padding(top = 6.dp)) {
        RuntimeSpreadRow {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "localStorage",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(6.dp))
                BadgePill(text = "LIVE", color = 0xFF10B981)
            }
            Text(
                "${storage.localStorageKeyCount} keys",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (storage.localStorageChanges.isEmpty()) {
            Text(
                "No writes observed since attaching — DOMStorage events are live, so an empty list means " +
                    "the page has not changed localStorage yet.",
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            storage.localStorageChanges.forEach { LocalStorageChangeRow(it) }
        }
    }

    RuntimeFlatCard(modifier = Modifier.padding(top = 6.dp)) {
        RuntimeSpreadRow {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "IndexedDB",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${storage.indexedDbDatabases.size} DBs · ${storage.indexedDbRecordCount} records" +
                    if (storage.indexedDbTruncated) " (partial)" else "",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        storage.indexedDbDatabases.forEach { db ->
            Text(
                "${db.databaseName} · ${db.objectStoreCount} stores · ${db.recordCount} records" +
                    if (db.truncated) " (truncated at page size)" else "",
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        storage.lastError?.let {
            Text(
                "Storage fetch error: $it",
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LocalStorageChangeRow(change: LocalStorageChange) {
    val (label, color) = when (change.kind) {
        LocalStorageChangeKind.SET -> "set" to RuntimeGreen
        LocalStorageChangeKind.UPDATED -> changeArrow(change) to RuntimeAmber
        LocalStorageChangeKind.REMOVED -> "removed" to RuntimeRed
        LocalStorageChangeKind.CLEARED -> "cleared" to RuntimeRed
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            change.key ?: "(whole store)",
            modifier = Modifier.weight(1f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        Text(
            "· ${relativeTime(change.atMs)}",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceWorkerBlock(workers: List<CdpServiceWorkerRow>) {
    RuntimeSubLabel("SERVICE WORKERS")
    if (workers.isEmpty()) {
        Text(
            "None observed on the attached targets.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    workers.forEach { worker ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RuntimeStatusDot(
                color = when (worker.status?.lowercase()) {
                    "activated", "active" -> RuntimeGreen
                    null -> RuntimeGray
                    else -> RuntimeAmber
                },
            )
            Text(
                worker.scriptUrl ?: worker.scopeUrl.ifBlank { "(no script url reported)" },
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${worker.status ?: "status unknown"} · scope ${worker.scopeUrl.ifBlank { "?" }}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        worker.lastError?.let {
            Text(
                it,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "3→4" for a real old→new pair; falls back to "updated" when the old value was not reported. */
internal fun changeArrow(change: LocalStorageChange): String {
    val old = change.oldValue?.takeIf { it.isNotEmpty() }
    val new = change.newValue?.takeIf { it.isNotEmpty() }
    return when {
        old != null && new != null -> "${old.take(12)}→${new.take(12)}"
        else -> "updated"
    }
}

/** Coarse "Ns/Nm/Nh ago" for a real event timestamp. */
internal fun relativeTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val seconds = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

/**
 * The card's one-line summary under the status row. Counts are real; the connected variant reports
 * the live uptime only when a genuine CONNECTED timestamp exists.
 */
internal fun summaryLine(state: CdpCardState): String = when (state.phase) {
    CdpConnectionPhase.CONNECTED -> {
        val uptime = state.connectedSinceMs?.let { " · up ${formatElapsed(System.currentTimeMillis() - it)}" } ?: ""
        "${state.targets.size} targets attached · ${state.eventsCorrelated} network events correlated$uptime"
    }
    CdpConnectionPhase.RECONNECTING ->
        "${state.targets.size} targets were attached · streams paused until the connection is restored"
    CdpConnectionPhase.CONNECTING -> "Connecting to ${state.endpoint ?: "the configured endpoint"}…"
    CdpConnectionPhase.FAILED ->
        if (state.reconnectAttemptsMade > 0) {
            "Not connected · gave up after ${state.reconnectAttemptsMade} reconnect " +
                "attempt${if (state.reconnectAttemptsMade == 1) "" else "s"} · 0 targets attached"
        } else {
            // No retry ran, so claiming an exhausted retry loop would be a lie.
            "Not connected · connection attempt failed · 0 targets attached"
        }
    CdpConnectionPhase.DISCONNECTED -> "Not connected to a CDP endpoint · 0 targets attached"
}
