package com.example.data.repository

import android.content.Context
import com.example.data.local.RequestrDatabase
import com.example.data.local.toApiRequestItem
import com.example.data.local.toEntity
import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiRequestItem
import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.FlowPipelineItem
import com.example.data.models.flowConnectionId
import com.example.data.models.HttpMethod
import com.example.data.models.MusicSourceType
import com.example.data.models.MusicTrackItem
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.models.RequestBodyType
import com.example.data.models.UserSettingsState
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.prefs.unifiedDataStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ==========================================
// 2. NOTES REPOSITORY
// ==========================================
data class NoteFolder(
    val id: String,
    val name: String,
    val colorHex: Long,
    val iconEmoji: String = "📁"
)

object NotesRepository {

    // Default folder taxonomy — structural categories, not user content, so (unlike the notes
    // below) these are still worth seeding once so a fresh install has somewhere to file a note.
    // Seeded through the same "only when Room is genuinely empty" gate as FlowRepository's
    // defaultNodes/defaultConnections, so a user who deletes every folder does not get them
    // resurrected on the next launch.
    private val defaultFolders = listOf(
        NoteFolder("f-1", "Security Audits", 0xFF3B82F6, "🛡️"),
        NoteFolder("f-2", "API Endpoints", 0xFF06B6D4, "🌐"),
        NoteFolder("f-3", "Frida Snippets", 0xFFF59E0B, "⚡"),
        NoteFolder("f-4", "Reverse Engineering", 0xFF8B5CF6, "🔍")
    )

    private val _folders = MutableStateFlow<List<NoteFolder>>(defaultFolders)
    val folders: StateFlow<List<NoteFolder>> = _folders.asStateFlow()

    // Task 11 (plan-app.md H1): no seed data for notes, by design — the three notes that used
    // to live here (an APEX cert-store writeup, a PKCE checklist, an HTML report template) were
    // fabricated sample content, exactly the "fabricated sample rows" pattern RequestrDatabase's
    // own KDoc calls out and rejects. A fresh install now genuinely has zero notes.
    private val _notes = MutableStateFlow<List<NoteItem>>(emptyList())
    val notes: StateFlow<List<NoteItem>> = _notes.asStateFlow()

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Backs [folders]/[notes] with the real `note_folders`/`notes` Room tables (task 11).
     * Call once from MainActivity.onCreate(), mirroring [FlowRepository.init]/
     * [ApiCollectionsRepository.init]. Folders seed their small default taxonomy on a genuinely
     * empty table; notes never seed anything.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val db = RequestrDatabase.get(ctx)

        repoScope.launch {
            db.noteFolderDao().observeAll().collect { entities ->
                if (entities.isEmpty() && _folders.value == defaultFolders) {
                    defaultFolders.forEach { db.noteFolderDao().upsert(it.toEntity()) }
                } else if (entities.isNotEmpty()) {
                    _folders.value = entities.map { it.toNoteFolder() }
                }
            }
        }
        repoScope.launch {
            db.noteDao().observeAll().collect { entities ->
                _notes.value = entities.map { it.toNoteItem() }
            }
        }
    }

    fun saveNote(note: NoteItem) {
        val updated = note.copy(updatedAt = System.currentTimeMillis())
        val current = _notes.value
        val exists = current.any { it.id == updated.id }
        _notes.value = if (exists) {
            current.map { if (it.id == updated.id) updated else it }
        } else {
            listOf(updated) + current
        }
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).noteDao().upsert(updated.toEntity())
        }
    }

    fun deleteNote(id: String) {
        _notes.value = _notes.value.filter { it.id != id }
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).noteDao().deleteById(id)
        }
    }

    fun togglePin(id: String) {
        val updated = _notes.value.map {
            if (it.id == id) it.copy(isPinned = !it.isPinned) else it
        }
        _notes.value = updated
        val ctx = appContext ?: return
        val toggled = updated.firstOrNull { it.id == id } ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).noteDao().upsert(toggled.toEntity())
        }
    }

    fun addFolder(name: String, colorHex: Long) {
        val folder = NoteFolder(UUID.randomUUID().toString(), name, colorHex)
        _folders.value = _folders.value + folder
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).noteFolderDao().upsert(folder.toEntity())
        }
    }

    // ── Entity mappers ──────────────────────────────────────────────

    private fun NoteFolder.toEntity() = com.example.data.local.NoteFolderEntity(
        id = id, name = name, colorHex = colorHex, emoji = iconEmoji
    )

    private fun com.example.data.local.NoteFolderEntity.toNoteFolder() = NoteFolder(
        id = id, name = name, colorHex = colorHex, iconEmoji = emoji
    )

    private fun NoteItem.toEntity() = com.example.data.local.NoteEntity(
        id = id, title = title, content = content, format = format.name,
        folderId = folderId, folderName = folderName, folderColorHex = folderColorHex,
        isPinned = isPinned, updatedAt = updatedAt
    )

    private fun com.example.data.local.NoteEntity.toNoteItem() = NoteItem(
        id = id, title = title, content = content,
        format = runCatching { NoteFormatType.valueOf(format) }.getOrDefault(NoteFormatType.MARKDOWN),
        folderId = folderId, folderName = folderName, folderColorHex = folderColorHex,
        isPinned = isPinned, updatedAt = updatedAt
    )
}

// ==========================================
// 3. FLOW (AUTOMATION GRAPH) REPOSITORY
// ==========================================
object FlowRepository {

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nodeObserverJob: kotlinx.coroutines.Job? = null
    private var connectionObserverJob: kotlinx.coroutines.Job? = null

    // ── Multi-flow pipeline state (transplant slice 11) ──────────────
    private val _allFlows = MutableStateFlow<List<FlowPipelineItem>>(emptyList())
    val allFlows: StateFlow<List<FlowPipelineItem>> = _allFlows.asStateFlow()

    private val _activeFlowId = MutableStateFlow<String>("default")
    val activeFlowId: StateFlow<String> = _activeFlowId.asStateFlow()

    // Default seed data — used only on first init when DB is empty
    private val defaultNodes = listOf(
        FlowNodeItem(
            id = "node-1", title = "Intercept Trigger",
            type = FlowNodeType.INTERCEPT_TRIGGER, x = 60f, y = 80f,
            inputPorts = emptyList(), outputPorts = listOf("traffic"),
            configData = "Pattern: .*/auth/login.*"
        ),
        FlowNodeItem(
            id = "node-2", title = "Extract JWT Token",
            type = FlowNodeType.TRANSFORM, x = 340f, y = 80f,
            inputPorts = listOf("traffic"), outputPorts = listOf("token"),
            configData = "JSONPath: \$.data.token"
        ),
        FlowNodeItem(
            id = "node-3", title = "Verify with AI",
            type = FlowNodeType.TRANSFORM, x = 620f, y = 80f,
            inputPorts = listOf("token"), outputPorts = listOf("analysis"),
            configData = "Prompt: Audit token entropy & claims"
        ),
        FlowNodeItem(
            id = "node-4", title = "Save Audit Note",
            type = FlowNodeType.OUTPUT, x = 900f, y = 80f,
            inputPorts = listOf("analysis"), outputPorts = emptyList(),
            configData = "Folder: Security Audits"
        )
    )
    private val defaultConnections = listOf(
        FlowConnectionItem(fromNodeId = "node-1", fromPort = "traffic", toNodeId = "node-2", toPort = "traffic"),
        FlowConnectionItem(fromNodeId = "node-2", fromPort = "token", toNodeId = "node-3", toPort = "token"),
        FlowConnectionItem(fromNodeId = "node-3", fromPort = "analysis", toNodeId = "node-4", toPort = "analysis")
    )

    private val _nodes = MutableStateFlow<List<FlowNodeItem>>(defaultNodes)
    val nodes: StateFlow<List<FlowNodeItem>> = _nodes.asStateFlow()

    private val _connections = MutableStateFlow<List<FlowConnectionItem>>(defaultConnections)
    val connections: StateFlow<List<FlowConnectionItem>> = _connections.asStateFlow()

    /**
     * Fix 8 (AUDIT_REPORT) / GAP_ANALYSIS Tier 3 #15: Back FlowRepository from Room.
     * Call from Application/MainActivity.onCreate() once.
     * Loads persisted nodes/connections; seeds defaults if DB is empty (first launch).
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val db = com.example.data.local.RequestrDatabase.get(context.applicationContext)

        // Observe pipelines from Room (transplant slice 11)
        repoScope.launch {
            db.flowPipelineDao().observeAll().collect { pipelines ->
                if (pipelines.isEmpty()) {
                    // First launch or post-migration — seed a default pipeline
                    val defaultPipeline = com.example.data.local.FlowPipelineEntity(
                        id = "default",
                        name = "Auth Login Guard",
                        description = "4 nodes · trigger: Intercept",
                        triggerType = "Intercept",
                        status = "DRAFT",
                        lastRunText = "Never",
                        runsCount = 0,
                    )
                    db.flowPipelineDao().upsert(defaultPipeline)
                    // Seed default nodes/connections if flow_nodes is empty
                    val existingNodes = db.flowNodeDao().getAll("default")
                    if (existingNodes.isEmpty()) {
                        defaultNodes.forEach { node ->
                            db.flowNodeDao().upsert(node.toEntity("default"))
                        }
                        defaultConnections.forEach { conn ->
                            db.flowConnectionDao().upsert(conn.toEntity("default"))
                        }
                    }
                } else {
                    // Update allFlows with live node counts
                    _allFlows.value = pipelines.map { entity ->
                        val count = db.flowPipelineDao().nodeCountForFlow(entity.id)
                        entity.toFlowPipelineItem(count)
                    }
                    // Ensure active flowId is valid
                    val flowIds = pipelines.map { it.id }
                    if (_activeFlowId.value !in flowIds) {
                        _activeFlowId.value = flowIds.first()
                        restartNodeObservers(db)
                    }
                }
            }
        }

        // Start observing nodes/connections for the active flow
        restartNodeObservers(db)
    }

    /**
     * Cancels existing node/connection observers and starts fresh ones for the
     * currently active [activeFlowId]. Called from [init] and [setActiveFlow].
     */
    private fun restartNodeObservers(db: com.example.data.local.RequestrDatabase) {
        nodeObserverJob?.cancel()
        connectionObserverJob?.cancel()
        val flowId = _activeFlowId.value
        nodeObserverJob = repoScope.launch {
            db.flowNodeDao().observeAll(flowId).collect { entities ->
                _nodes.value = entities.map { it.toFlowNodeItem() }
            }
        }
        connectionObserverJob = repoScope.launch {
            db.flowConnectionDao().observeAll(flowId).collect { entities ->
                _connections.value = entities.map { it.toFlowConnectionItem() }
            }
        }
    }

    fun setActiveFlow(flowId: String) {
        val ctx = appContext ?: return
        val db = com.example.data.local.RequestrDatabase.get(ctx)
        _activeFlowId.value = flowId
        restartNodeObservers(db)
    }

    fun setFlowStatus(flowId: String, newStatus: String) {
        val ctx = appContext ?: return
        repoScope.launch {
            com.example.data.local.RequestrDatabase.get(ctx).flowPipelineDao().updateStatus(flowId, newStatus)
        }
    }

    fun createNewFlow(name: String, triggerType: String): FlowPipelineItem {
        val flowId = "flow-${UUID.randomUUID().toString().take(6)}"
        val newPipeline = com.example.data.local.FlowPipelineEntity(
            id = flowId,
            name = name.ifBlank { "Untitled Flow" },
            description = "1 node · trigger: $triggerType",
            triggerType = triggerType,
            status = "DRAFT",
            lastRunText = "Never",
            runsCount = 0,
        )
        val item = newPipeline.toFlowPipelineItem(0)
        // Add to in-memory list immediately for responsiveness
        _allFlows.value = _allFlows.value + item
        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                val db = com.example.data.local.RequestrDatabase.get(ctx)
                db.flowPipelineDao().upsert(newPipeline)
                // Seed a trigger node for the new flow
                val triggerNode = FlowNodeItem(
                    id = "node-${UUID.randomUUID().toString().take(4)}",
                    title = "$triggerType Trigger",
                    type = FlowNodeType.INTERCEPT_TRIGGER,
                    x = 80f, y = 80f,
                    configData = "Pattern: .*"
                )
                db.flowNodeDao().upsert(triggerNode.toEntity(flowId))
            }
        }
        setActiveFlow(flowId)
        return item
    }

    fun createPipeline(name: String): FlowPipelineItem {
        return createNewFlow(name, "Manual")
    }

    fun deleteFlow(flowId: String) {
        if (_allFlows.value.size <= 1) return
        _allFlows.value = _allFlows.value.filter { it.id != flowId }
        val ctx = appContext ?: return
        repoScope.launch {
            val db = com.example.data.local.RequestrDatabase.get(ctx)
            db.flowNodeDao().clearFlow(flowId)
            db.flowConnectionDao().clearFlow(flowId)
            db.flowPipelineDao().deleteById(flowId)
        }
        if (_activeFlowId.value == flowId) {
            val next = _allFlows.value.firstOrNull() ?: return
            setActiveFlow(next.id)
        }
    }

    fun addNode(node: FlowNodeItem) {
        _nodes.value = _nodes.value + node
        persistNode(node)
    }

    /** Upserts an edited node (title/type/configData) both in-memory and in Room. */
    fun updateNode(node: FlowNodeItem) {
        _nodes.value = _nodes.value.map { if (it.id == node.id) node else it }
        persistNode(node)
    }

    fun updateNodePosition(id: String, x: Float, y: Float) {
        _nodes.value = _nodes.value.map {
            if (it.id == id) it.copy(x = x, y = y) else it
        }
        val ctx = appContext ?: return
        repoScope.launch {
            com.example.data.local.RequestrDatabase.get(ctx).flowNodeDao().updatePosition(id, x, y)
        }
    }

    fun removeNode(id: String) {
        _nodes.value = _nodes.value.filter { it.id != id }
        _connections.value = _connections.value.filter { it.fromNodeId != id && it.toNodeId != id }
        val ctx = appContext ?: return
        repoScope.launch {
            val db = com.example.data.local.RequestrDatabase.get(ctx)
            db.flowNodeDao().deleteById(id)
            db.flowConnectionDao().deleteByNodeId(id)
        }
    }

    fun addConnection(conn: FlowConnectionItem) {
        _connections.value = _connections.value + conn
        persistConnection(conn)
    }

    /** Removes a connection by ID and deletes it from Room. */
    fun removeConnection(id: String) {
        _connections.value = _connections.value.filter { it.id != id }
        val ctx = appContext ?: return
        repoScope.launch {
            com.example.data.local.RequestrDatabase.get(ctx).flowConnectionDao().deleteById(id)
        }
    }

    /**
     * Replaces the entire graph (used by JSON import): clears both Room tables, inserts the
     * new nodes/connections, and updates both StateFlows so the canvas reflects the import
     * immediately rather than waiting on the next Room emission.
     */
    fun replaceGraph(nodes: List<FlowNodeItem>, connections: List<FlowConnectionItem>) {
        _nodes.value = nodes
        _connections.value = connections
        val ctx = appContext ?: return
        repoScope.launch {
            val db = com.example.data.local.RequestrDatabase.get(ctx)
            val flowId = _activeFlowId.value
            db.flowNodeDao().clearFlow(flowId)
            db.flowConnectionDao().clearFlow(flowId)
            nodes.forEach { db.flowNodeDao().upsert(it.toEntity(flowId)) }
            connections.forEach { db.flowConnectionDao().upsert(it.toEntity(flowId)) }
        }
    }

    private fun persistNode(node: FlowNodeItem) {
        val ctx = appContext ?: return
        repoScope.launch {
            com.example.data.local.RequestrDatabase.get(ctx).flowNodeDao().upsert(node.toEntity())
        }
    }

    private fun persistConnection(conn: FlowConnectionItem) {
        val ctx = appContext ?: return
        repoScope.launch {
            com.example.data.local.RequestrDatabase.get(ctx).flowConnectionDao().upsert(conn.toEntity())
        }
    }

    // ── Entity mappers ──────────────────────────────────────────────

    internal fun FlowNodeItem.toEntity(flowId: String = _activeFlowId.value) = com.example.data.local.FlowNodeEntity(
        id = id, flowId = flowId, type = type.name, label = title,
        positionX = x, positionY = y, configData = configData
    )

    /**
     * Ports are derived from [FlowNodeType] here, fixing the data loss where a saved node's
     * ports came back empty after restart (they were dropped on write and never derived on
     * read). CONDITION nodes get labelled `true`/`false` output ports so FlowExecutor's
     * port-based branching (task 3) has something real to match against; every other type
     * keeps the single `out` port. INTERCEPT_TRIGGER has no upstream, so it gets no input port.
     */
    internal fun com.example.data.local.FlowNodeEntity.toFlowNodeItem(): FlowNodeItem {
        val nodeType = try { FlowNodeType.valueOf(type) } catch (_: Exception) { FlowNodeType.TEXT }
        val outputPorts = if (nodeType == FlowNodeType.CONDITION) listOf("true", "false") else listOf("out")
        val inputPorts = if (nodeType == FlowNodeType.INTERCEPT_TRIGGER) emptyList() else listOf("in")
        return FlowNodeItem(
            id = id, title = label,
            type = nodeType,
            x = positionX, y = positionY, configData = configData,
            inputPorts = inputPorts, outputPorts = outputPorts
        )
    }

    internal fun FlowConnectionItem.toEntity(flowId: String = _activeFlowId.value) = com.example.data.local.FlowConnectionEntity(
        id = flowConnectionId(fromNodeId, fromPort, toNodeId, toPort),
        flowId = flowId, fromNodeId = fromNodeId, fromPort = fromPort,
        toNodeId = toNodeId, toPort = toPort
    )

    /** `id` is now mapped through -- previously omitted here, so a Room-loaded connection took
     * the random-UUID default from [FlowConnectionItem] instead of the id [toEntity] computed,
     * meaning `removeConnection(id)` could silently miss it after a restart. */
    internal fun com.example.data.local.FlowConnectionEntity.toFlowConnectionItem() = FlowConnectionItem(
        id = id,
        fromNodeId = fromNodeId, fromPort = fromPort,
        toNodeId = toNodeId, toPort = toPort
    )

    internal fun com.example.data.local.FlowPipelineEntity.toFlowPipelineItem(nodeCount: Int) = FlowPipelineItem(
        id = id, name = name, description = description,
        triggerType = triggerType, status = status,
        lastRunText = lastRunText, runsCount = runsCount,
        nodeCount = nodeCount
    )

    /** Real success/failure signal so [FlowScreen] can colour its run banner honestly. */
    suspend fun executeFlow(): FlowRunSummary {
        val nodes = _nodes.value.toList()
        val connections = _connections.value.toList()

        if (nodes.isEmpty()) {
            return FlowRunSummary(success = false, message = "No nodes to execute. Add a trigger node to begin.")
        }

        val executor = com.example.flow.FlowExecutor { url, method, headers, body ->
            val request = com.example.data.models.ApiRequestItem(
                url = url,
                method = try { com.example.data.models.HttpMethod.valueOf(method) }
                    catch (_: Exception) { com.example.data.models.HttpMethod.GET },
                headers = headers.map { com.example.data.models.KeyValueItem(it.key, it.value) },
                bodyContent = body,
                bodyType = com.example.data.models.RequestBodyType.JSON,
            )
            val response = com.example.data.repository.ApiTestrRepository.executeRequest(request, true)
            response.body.ifBlank { "{\"status\":${response.statusCode}}" }
        }

        val result = executor.execute(nodes, connections)

        val message = buildString {
            appendLine("Flow execution ${if (result.success) "completed" else "finished with errors"}.")
            appendLine("${result.nodesExecuted} node(s) executed in ${result.totalDurationMs}ms.")
            if (result.errors.isNotEmpty()) {
                appendLine("Errors:")
                result.errors.forEach { appendLine("  - $it") }
            }
            if (result.outputs.isNotEmpty()) {
                appendLine("Outputs:")
                result.outputs.entries.take(5).forEach { (id, out) ->
                    val label = nodes.find { it.id == id }?.title ?: id
                    appendLine("  $label: ${out.take(80)}")
                }
                if (result.outputs.size > 5) appendLine("  ... and ${result.outputs.size - 5} more")
            }
        }.trimEnd()

        return FlowRunSummary(success = result.success, message = message)
    }
}

/** Carries success/failure out of [FlowRepository.executeFlow] so the caller can render an
 * honest result banner instead of always assuming success. */
data class FlowRunSummary(val success: Boolean, val message: String)

// ==========================================
// 4. MUSIC REPOSITORY
// ==========================================
object MusicRepository {
    private val _tracks = MutableStateFlow<List<MusicTrackItem>>(emptyList())
    val tracks: StateFlow<List<MusicTrackItem>> = _tracks.asStateFlow()

    private val _currentTrack = MutableStateFlow(
        MusicTrackItem("", "No Track", "No Artist", 0, MusicSourceType.LOCAL, 0xFF1E3A8A)
    )
    val currentTrack: StateFlow<MusicTrackItem> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec: StateFlow<Int> = _currentPositionSec.asStateFlow()

    // ── Real MediaPlayer-backed playback ───────────────────────────
    @Volatile private var mediaPlayer: android.media.MediaPlayer? = null
    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    private var currentPlayTrackId: String? = null

    /** Scan the device's local music library and populate the track list. */
    suspend fun loadLocalLibrary(context: android.content.Context) {
        val local = com.example.data.music.LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = local
    }

    /** Scan and pick a random track. If library is empty, current track unchanged. */
    suspend fun pickRandomTrack(context: android.content.Context): MusicTrackItem {
        val local = com.example.data.music.LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = local
        val random = local.randomOrNull() ?: _currentTrack.value
        _currentTrack.value = random
        return random
    }

    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp == null) {
            // No player yet – start fresh for the current track
            playCurrentTrack()
            return
        }
        try {
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying.value = false
                cancelPositionUpdates()
            } else {
                mp.start()
                _isPlaying.value = true
                startPositionUpdates()
            }
        } catch (_: Exception) {
            // Player in error state – recreate
            releasePlayer()
            playCurrentTrack()
        }
    }

    fun nextTrack() {
        val current = _tracks.value
        if (current.isEmpty()) return
        val curId = currentPlayTrackId ?: _currentTrack.value.id
        val index = current.indexOfFirst { it.id == curId }
        val nextIndex = (index + 1) % current.size
        val track = current[nextIndex]
        _currentTrack.value = track
        _currentPositionSec.value = 0
        releasePlayer()
        playCurrentTrack()
    }

    fun prevTrack() {
        val current = _tracks.value
        if (current.isEmpty()) return
        val curId = currentPlayTrackId ?: _currentTrack.value.id
        val index = current.indexOfFirst { it.id == curId }
        val prevIndex = if (index - 1 < 0) current.size - 1 else index - 1
        val track = current[prevIndex]
        _currentTrack.value = track
        _currentPositionSec.value = 0
        releasePlayer()
        playCurrentTrack()
    }

    fun seekTo(sec: Int) {
        val mp = mediaPlayer
        if (mp != null) {
            try {
                mp.seekTo(sec * 1000)
                _currentPositionSec.value = sec
            } catch (_: Exception) { }
        }
    }

    fun selectTrack(id: String) {
        val found = _tracks.value.find { it.id == id }
        if (found != null) {
            _currentTrack.value = found
            _currentPositionSec.value = 0
            releasePlayer()
            playCurrentTrack()
        }
    }

    fun stopMusic() {
        releasePlayer()
        _isPlaying.value = false
        _currentPositionSec.value = 0
        currentPlayTrackId = null
    }

    /** Removes a track from the playlist (Fix #35). If it's the current track, stops playback. */
    fun removeTrack(id: String) {
        val wasCurrent = currentPlayTrackId == id || _currentTrack.value.id == id
        _tracks.value = _tracks.value.filter { it.id != id }
        if (wasCurrent) {
            stopMusic()
        }
    }

    // ── Internal media player lifecycle ────────────────────────────

    private val playbackScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    private fun playCurrentTrack() {
        val track = _currentTrack.value
        if (track.filePath.isNullOrBlank()) return // no local file to play

        playbackScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                releasePlayerSync()
                val mp = android.media.MediaPlayer().apply {
                    setDataSource(track.filePath)
                    setOnPreparedListener {
                        it.start()
                        _isPlaying.value = true
                        currentPlayTrackId = track.id
                        startPositionUpdates()
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        cancelPositionUpdates()
                        // Auto-advance to next track on main thread
                        playbackScope.launch { nextTrack() }
                    }
                    setOnErrorListener { _, what, extra ->
                        _isPlaying.value = false
                        cancelPositionUpdates()
                        android.util.Log.e("MusicRepo", "MediaPlayer error what=$what extra=$extra")
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = mp
            } catch (e: Exception) {
                _isPlaying.value = false
                android.util.Log.e("MusicRepo", "Failed to play: ${e.message}")
            }
        }
    }

    @Volatile private var trackingPosition = false

    private fun startPositionUpdates() {
        cancelPositionUpdates()
        trackingPosition = true
        positionUpdateJob = playbackScope.launch {
            while (trackingPosition) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    try {
                        _currentPositionSec.value = mp.currentPosition / 1000
                    } catch (_: Exception) { }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun cancelPositionUpdates() {
        trackingPosition = false
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun releasePlayer() {
        cancelPositionUpdates()
        releasePlayerSync()
    }

    private fun releasePlayerSync() {
        val mp = mediaPlayer
        mediaPlayer = null
        currentPlayTrackId = null
        try {
            mp?.stop()
            mp?.release()
        } catch (_: Exception) { }
    }
}

// ==========================================
// 5. APIS / COLLECTIONS REPOSITORY
// ==========================================
object ApiCollectionsRepository {

    private val _folders = MutableStateFlow<List<ApiFolderItem>>(
        listOf(
            ApiFolderItem("f-1", "Public APIs", 0xFF3B82F6),
            ApiFolderItem("f-2", "Auth & Identity", 0xFF06B6D4),
            ApiFolderItem("f-3", "Internal Microservices", 0xFFF59E0B),
            ApiFolderItem("f-4", "WebSockets & Stream", 0xFF8B5CF6)
        )
    )
    val folders: StateFlow<List<ApiFolderItem>> = _folders.asStateFlow()

    // No seed data by design.
    //
    // v2.1 shipped sample requests (HttpBin JSON Echo, GitHub User Profile, Post JSON Payload)
    // which surfaced on Home's "Recently opened" even on a fresh install (APP_v2.md Bug 1.6).
    // A new install now genuinely has no saved requests.
    private val _requests = MutableStateFlow<List<ApiRequestItem>>(emptyList())
    val requests: StateFlow<List<ApiRequestItem>> = _requests.asStateFlow()

    /**
     * The request currently open in the editor.
     *
     * v2.1 (line ~461) called `_requests.value.first()` which crashed on a fresh install
     * with NoSuchElementException because the seed data was removed. Now defaults to an
     * empty placeholder request so the API Testr screen opens safely.
     */
    private val _currentActiveRequest = MutableStateFlow(
        ApiRequestItem(
            id = "new-request",
            name = "New Request",
            method = com.example.data.models.HttpMethod.GET,
            url = "https://httpbin.org/get",
        )
    )
    val currentActiveRequest: StateFlow<ApiRequestItem> = _currentActiveRequest.asStateFlow()

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v3.1 fix (A4): saved requests previously lived only in [_requests], a plain in-memory
     * `MutableStateFlow` — Save/Star looked like it worked but every request was lost on process
     * death or app restart. Call once from Application/MainActivity.onCreate() (mirrors
     * [SettingsRepository.init]) to back [_requests] with the real `saved_api_requests` Room
     * table; every subsequent read reflects the DB, and writes below persist to it.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        repoScope.launch {
            RequestrDatabase.get(ctx).savedApiRequestDao().observeAll().collect { rows ->
                _requests.value = rows.map { it.toApiRequestItem() }
            }
        }
    }

    fun setActiveRequest(req: ApiRequestItem) {
        _currentActiveRequest.value = req
    }

    fun saveRequest(req: ApiRequestItem) {
        val updated = req.copy(updatedAt = System.currentTimeMillis())
        _currentActiveRequest.value = updated

        // Fix 8 (AUDIT_REPORT §4.1): Previously used fire-and-forget DB write — process death
        // before commit lost the save. Now we still update UI optimistically for responsiveness,
        // but the Room Flow (init() collector) will overwrite _requests with the DB truth shortly.
        // The optimistic update ensures instant UI feedback; the Room collector ensures consistency.
        val current = _requests.value
        val exists = current.any { it.id == updated.id }
        _requests.value = if (exists) {
            current.map { if (it.id == updated.id) updated else it }
        } else {
            listOf(updated) + current
        }

        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                try {
                    RequestrDatabase.get(ctx).savedApiRequestDao().upsert(updated.toEntity())
                } catch (e: Exception) {
                    // DB write failed. This does NOT revert the optimistic in-memory value --
                    // there is no re-read here. The optimistic value simply stays until the
                    // Room observeAll() flow (init()'s collector) next emits and corrects it,
                    // which will not happen if the write never lands. Logged so a persistent
                    // save failure is at least diagnosable.
                    android.util.Log.w("ApiCollections", "Save failed, optimistic value kept until Room reconciles: ${e.message}")
                }
            }
        }
    }

    fun deleteRequest(id: String) {
        _requests.value = _requests.value.filter { it.id != id }
        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                RequestrDatabase.get(ctx).savedApiRequestDao().deleteById(id)
            }
        }
    }

    fun toggleFavorite(id: String) {
        var newFavorite = false
        _requests.value = _requests.value.map {
            if (it.id == id) {
                newFavorite = !it.isFavorite
                it.copy(isFavorite = newFavorite)
            } else it
        }
        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                RequestrDatabase.get(ctx).savedApiRequestDao().setFavorite(id, newFavorite)
            }
        }
    }

    /**
     * v3.2 Part C — C2 (Request chaining): runs [request]'s enabled
     * [com.example.data.models.ChainExtractionRule]s against a just-received [responseBody],
     * using the same top-level-key extractor the Flow Transform node uses
     * ([com.example.flow.JsonFieldExtractor] — reused, not re-implemented per the plan), and
     * writes every successful extraction into the active Environment via
     * [com.example.data.env.EnvironmentRepository.updateEnvironment] so a later request in the
     * chain can reference it as `{{variableName}}` (substituted by
     * [com.example.data.env.EnvironmentRepository.substitute]).
     *
     * Returns the map of variableName -> extractedValue that was actually written, so the
     * calling screen can show the user what happened (a rule whose path is not found in the
     * response is silently skipped, not an error — the request itself already succeeded).
     */
    fun runChainExtractions(request: ApiRequestItem, responseBody: String): Map<String, String> {
        val rules = request.chainExtractions.filter { it.enabled && it.variableName.isNotBlank() }
        if (rules.isEmpty()) return emptyMap()

        val extracted = linkedMapOf<String, String>()
        rules.forEach { rule ->
            com.example.flow.JsonFieldExtractor.extract(responseBody, rule.jsonPath)?.let { value ->
                extracted[rule.variableName.trim()] = value
            }
        }
        if (extracted.isEmpty()) return emptyMap()

        val env = com.example.data.env.EnvironmentRepository.active()
        com.example.data.env.EnvironmentRepository.updateEnvironment(env.name, env.variables + extracted)
        return extracted
    }

    /**
     * v3.3 Part C — C3 (Test assertions / test runner): "Test runner mode" — runs every saved
     * request in [folderId] (or every saved request, when null, for an ad-hoc "run all" outside
     * a folder) that has at least one assertion, in sequence, substituting `{{var}}` templates
     * against the active Environment exactly like a normal Send (so C2 chaining still applies —
     * a request earlier in the list can feed variables the next one consumes), running its
     * chain-extraction rules on success, then evaluating its assertions via [AssertionRunner].
     * Returns a [com.example.data.models.CollectionTestReport] summarizing pass/fail counts.
     */
    suspend fun runCollectionTests(folderId: String?): com.example.data.models.CollectionTestReport {
        val candidates = _requests.value
            .filter { folderId == null || it.folderId == folderId }
            .filter { it.assertions.any { a -> a.enabled } }
            .sortedBy { it.updatedAt }

        val results = candidates.map { request ->
            val env = com.example.data.env.EnvironmentRepository.active()
            fun sub(s: String) = com.example.data.env.EnvironmentRepository.substitute(s, env)
            val substituted = request.copy(
                url = sub(request.url),
                headers = request.headers.map { it.copy(value = sub(it.value)) },
                queryParams = request.queryParams.map { it.copy(value = sub(it.value)) },
                bodyContent = sub(request.bodyContent),
                bearerToken = sub(request.bearerToken),
                apiKeyValue = sub(request.apiKeyValue),
            )
            val resp = runCatching { ApiTestrRepository.executeRequest(substituted) }.getOrNull()
            if (resp == null) {
                com.example.data.models.RequestTestResult(
                    request = request,
                    response = null,
                    results = emptyList(),
                    requestFailed = true,
                )
            } else {
                if (resp.isSuccess && request.chainExtractions.isNotEmpty()) {
                    runChainExtractions(request, resp.body)
                }
                com.example.data.models.RequestTestResult(
                    request = request,
                    response = resp,
                    results = AssertionRunner.run(request.assertions, resp),
                    requestFailed = false,
                )
            }
        }
        return com.example.data.models.CollectionTestReport(folderId = folderId, requestResults = results)
    }
}

// ==========================================
// 6. USER SETTINGS REPOSITORY
// ==========================================
// v3.2 Part C — C1: now backed by the single unified DataStore
// (com.example.data.prefs.unifiedDataStore) instead of its own raw "requestr_user_settings"
// SharedPreferences file. init() triggers the one-time legacy migration (idempotent — see
// LegacyDataMigration) and then collects the unified store into this StateFlow so every
// existing call site (settings.value, settings StateFlow, update*() functions) keeps working
// unchanged.
object SettingsRepository {
    private var appContext: Context? = null
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false

    // v6.0 — accent axis persistence key. Defined here (not in AppSettingsKeys) because this
    // persistence-only change is scoped to AppModels.kt + AppRepositories.kt only; orthogonal
    // to appearanceMode/selectedDarkTheme/selectedLightTheme (see UserSettingsState.selectedAccent).
    private val SELECTED_ACCENT = stringPreferencesKey("selected_accent")

    /**
     * Call once from Application.onCreate() or early in MainActivity before any
     * read of [settings]. Loads persisted values; runtime-only flags (root,
     * Shizuku, Frida, VPN) stay false until [CapabilityProbe] runs.
     */
    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true
        val ctx = context.applicationContext
        appContext = ctx
        repoScope.launch {
            com.example.data.prefs.LegacyDataMigration.migrateIfNeeded(ctx)
            ctx.unifiedDataStore.data.collect { p ->
                _settings.value = _settings.value.copy(
                    userName = p[K.USER_NAME] ?: _settings.value.userName,
                    themeMode = p[K.THEME_MODE] ?: _settings.value.themeMode,
                    cornerRadiusDp = p[K.CORNER_RADIUS] ?: _settings.value.cornerRadiusDp,
                    proxyPort = p[K.PROXY_PORT] ?: _settings.value.proxyPort,
                    autoBeautify = p[K.AUTO_BEAUTIFY] ?: _settings.value.autoBeautify,
                    sslVerification = p[K.SSL_VERIFY] ?: _settings.value.sslVerification,
                    defaultSnippetLang = p[K.SNIPPET_LANG] ?: _settings.value.defaultSnippetLang,
                    onboardingCompleted = p[K.ONBOARDING] ?: _settings.value.onboardingCompleted,
                    appearanceMode = p[K.APPEARANCE_MODE] ?: _settings.value.appearanceMode,
                    selectedDarkTheme = p[K.SELECTED_DARK_THEME] ?: _settings.value.selectedDarkTheme,
                    selectedLightTheme = p[K.SELECTED_LIGHT_THEME] ?: _settings.value.selectedLightTheme,
                    selectedAccent = p[SELECTED_ACCENT] ?: _settings.value.selectedAccent,
                    swipeToDeleteEnabled = p[K.SWIPE_TO_DELETE] ?: _settings.value.swipeToDeleteEnabled,
                    hapticFeedbackEnabled = p[K.HAPTIC_FEEDBACK] ?: _settings.value.hapticFeedbackEnabled,
                    gestureNavigationEnabled = p[K.GESTURE_NAVIGATION] ?: _settings.value.gestureNavigationEnabled,
                    densityMode = p[K.DENSITY_MODE] ?: _settings.value.densityMode,
                    updateNotificationPolicy = p[K.UPDATE_NOTIFICATION_POLICY] ?: _settings.value.updateNotificationPolicy,
                    animationEnabled = p[K.ANIMATION_ENABLED] ?: _settings.value.animationEnabled,
                    transitionBehavior = p[K.TRANSITION_BEHAVIOR] ?: _settings.value.transitionBehavior,
                    displayScale = p[K.DISPLAY_SCALE] ?: _settings.value.displayScale,
                    autoRecordingEnabled = p[K.AUTO_RECORDING] ?: _settings.value.autoRecordingEnabled,
                    recordingNotificationsEnabled = p[K.RECORDING_NOTIFICATIONS] ?: _settings.value.recordingNotificationsEnabled,
                    remoteControlEnabled = p[K.REMOTE_CONTROL] ?: _settings.value.remoteControlEnabled,
                    remoteCommandsEnabled = p[K.REMOTE_COMMANDS] ?: _settings.value.remoteCommandsEnabled,
                    lanDeviceDiscoveryEnabled = p[K.LAN_DISCOVERY] ?: _settings.value.lanDeviceDiscoveryEnabled,
                    deviceBroadcastingEnabled = p[K.DEVICE_BROADCASTING] ?: _settings.value.deviceBroadcastingEnabled,
                    trafficCaptureEnabled = p[K.TRAFFIC_CAPTURE] ?: _settings.value.trafficCaptureEnabled,
                    requestLoggingEnabled = p[K.REQUEST_LOGGING] ?: _settings.value.requestLoggingEnabled,
                    responseLoggingEnabled = p[K.RESPONSE_LOGGING] ?: _settings.value.responseLoggingEnabled,
                    musicPermissionStatus = p[K.MUSIC_PERMISSION_STATUS] ?: _settings.value.musicPermissionStatus,
                    musicBarDismissed = p[K.MUSIC_BAR_DISMISSED] ?: _settings.value.musicBarDismissed,
                    musicLibraryScanned = p[K.MUSIC_LIBRARY_SCANNED] ?: _settings.value.musicLibraryScanned,
                    selectedLocalTrackId = p[K.SELECTED_LOCAL_TRACK_ID] ?: _settings.value.selectedLocalTrackId,
                    navBarBackgroundOpacity = p[K.NAV_BAR_OPACITY] ?: _settings.value.navBarBackgroundOpacity,
                    navBarBlurEffect = com.example.data.prefs.canonicalGlassStyle(p[K.NAV_BAR_BLUR_EFFECT] ?: _settings.value.navBarBlurEffect),
                    navBarHiddenItems = p[K.NAV_BAR_HIDDEN_ITEMS] ?: _settings.value.navBarHiddenItems,
                    headerTopPaddingDp = p[K.HEADER_TOP_PADDING] ?: _settings.value.headerTopPaddingDp,
                    headerBackgroundOpacity = p[K.HEADER_OPACITY] ?: _settings.value.headerBackgroundOpacity,
                    headerBlurStyle = com.example.data.prefs.canonicalGlassStyle(p[K.HEADER_BLUR_STYLE] ?: _settings.value.headerBlurStyle),
                    fabBottomSpacingDp = p[K.FAB_BOTTOM_SPACING] ?: _settings.value.fabBottomSpacingDp,
                )
            }
        }
    }

    private val _settings = MutableStateFlow(
        UserSettingsState(
            userName = "",
            // Default to MonochromeDark. v2.1 defaulted to "MIDNIGHT" which maps to
            // "Midnight Sapphire" — a fine theme, but the welcome screen uses it
            // before the user picks, which confuses the first-run experience.
            // MonochromeDark is the universal dark starting point.
            themeMode = "MONOCHROME_DARK",
            cornerRadiusDp = 16,
            proxyPort = 8080,
            // Fix #30: stale runtime flags (isVpnActive, isRootDetected, isShizukuAvailable,
            // isFridaReady, isLsposedInstalled) were removed here. They were never updated
            // by CapabilityProbe and never read by the UI. Diagnostics reads
            // InterceptionStateRepository.capabilities directly.
            autoBeautify = true,
            sslVerification = false,
            defaultSnippetLang = "Python - Requests",
            // Onboarding not yet completed until the user finishes the wizard.
            // v2.1 set this to true, skipping onboarding entirely.
            onboardingCompleted = false,
        )
    )
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    fun updateUserName(name: String) {
        _settings.value = _settings.value.copy(userName = name)
        persist(K.USER_NAME, name)
    }

    fun updateTheme(themeMode: String) {
        _settings.value = _settings.value.copy(themeMode = themeMode)
        persist(K.THEME_MODE, themeMode)
    }

    // v3.1 B1 — the single, unified theme-selection write path. Every UI entry point
    // that lets the user pick a root theme (Setup Wizard's AppearanceStep, Settings'
    // AppearanceSelector + AccentColorGrid, CommonComponents' ThemeToggleButton, and
    // AppNavigationDrawer's quick-switcher) must route through THIS function instead
    // of calling updateTheme()/updateSelectedDarkTheme() directly and separately —
    // that dual-write divergence (mutating themeMode identity without regard to which
    // appearanceMode family the pick belongs to) was exactly the "theme selection
    // collision" bug. This function:
    //  1. Writes themeMode (the value MyApplicationTheme actually renders) once.
    //  2. Remembers the pick under the correct per-family slot (selectedLightTheme or
    //     selectedDarkTheme) keyed off the theme's own isLight flag — NOT off whatever
    //     appearanceMode happened to be active — so light/dark choices never bleed into
    //     each other regardless of which mode the picker was shown under.
    //  3. Never touches appearanceMode — picking a root theme must never silently flip
    //     SYSTEM/LIGHT/DARK; that stays a fully independent, orthogonal setting.
    fun updateThemeSelection(theme: com.example.ui.theme.AppThemeMode) {
        val current = _settings.value
        val updated = if (theme.isLight) {
            current.copy(themeMode = theme.name, selectedLightTheme = theme.name)
        } else {
            current.copy(themeMode = theme.name, selectedDarkTheme = theme.name)
        }
        _settings.value = updated
        persist(K.THEME_MODE, updated.themeMode)
        if (theme.isLight) persist(K.SELECTED_LIGHT_THEME, updated.selectedLightTheme)
        else persist(K.SELECTED_DARK_THEME, updated.selectedDarkTheme)
    }

    // Convenience overload for call sites that only have the string key (e.g. from a
    // DropdownMenu/grid tap using AppThemeMode.name as the value). Falls back to the
    // legacy single-field updateTheme() if the string doesn't map to a known theme, so
    // this can never silently no-op.
    fun updateThemeSelection(themeName: String) {
        val theme = com.example.ui.theme.AppThemeMode.values()
            .find { it.name.equals(themeName, ignoreCase = true) }
        if (theme != null) updateThemeSelection(theme) else updateTheme(themeName)
    }

    fun updateCornerRadius(radius: Int) {
        _settings.value = _settings.value.copy(cornerRadiusDp = radius)
        persist(K.CORNER_RADIUS, radius)
    }

    fun updateProxyPort(port: Int) {
        _settings.value = _settings.value.copy(proxyPort = port)
        persist(K.PROXY_PORT, port)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        _settings.value = _settings.value.copy(onboardingCompleted = completed)
        persist(K.ONBOARDING, completed)
    }

    // Fix 01 — Appearance
    fun updateAppearanceMode(mode: String) {
        _settings.value = _settings.value.copy(appearanceMode = mode)
        persist(K.APPEARANCE_MODE, mode)
    }
    fun updateSelectedDarkTheme(theme: String) {
        _settings.value = _settings.value.copy(selectedDarkTheme = theme)
        persist(K.SELECTED_DARK_THEME, theme)
    }
    fun updateSelectedLightTheme(theme: String) {
        _settings.value = _settings.value.copy(selectedLightTheme = theme)
        persist(K.SELECTED_LIGHT_THEME, theme)
    }
    // v6.0 — orthogonal accent axis. Mirrors updateAppearanceMode: writes only the accent
    // field, never touches appearanceMode/selectedDarkTheme/selectedLightTheme.
    fun updateAccent(accent: String) {
        _settings.value = _settings.value.copy(selectedAccent = accent)
        persist(SELECTED_ACCENT, accent)
    }
    // Fix 02 — UI & UX
    fun updateSwipeToDelete(enabled: Boolean) {
        _settings.value = _settings.value.copy(swipeToDeleteEnabled = enabled)
        persist(K.SWIPE_TO_DELETE, enabled)
    }
    fun updateHapticFeedback(enabled: Boolean) {
        _settings.value = _settings.value.copy(hapticFeedbackEnabled = enabled)
        persist(K.HAPTIC_FEEDBACK, enabled)
    }
    fun updateGestureNavigation(enabled: Boolean) {
        _settings.value = _settings.value.copy(gestureNavigationEnabled = enabled)
        persist(K.GESTURE_NAVIGATION, enabled)
    }
    fun updateDensityMode(mode: String) {
        _settings.value = _settings.value.copy(densityMode = mode)
        persist(K.DENSITY_MODE, mode)
    }
    fun updateNotificationPolicy(policy: String) {
        _settings.value = _settings.value.copy(updateNotificationPolicy = policy)
        persist(K.UPDATE_NOTIFICATION_POLICY, policy)
    }
    fun updateAnimationEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(animationEnabled = enabled)
        persist(K.ANIMATION_ENABLED, enabled)
    }
    fun updateTransitionBehavior(behavior: String) {
        _settings.value = _settings.value.copy(transitionBehavior = behavior)
        persist(K.TRANSITION_BEHAVIOR, behavior)
    }
    fun updateDisplayScale(scale: String) {
        _settings.value = _settings.value.copy(displayScale = scale)
        persist(K.DISPLAY_SCALE, scale)
    }
    // Fix 02 — Features
    fun updateAutoRecording(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoRecordingEnabled = enabled)
        persist(K.AUTO_RECORDING, enabled)
    }
    fun updateRecordingNotifications(enabled: Boolean) {
        _settings.value = _settings.value.copy(recordingNotificationsEnabled = enabled)
        persist(K.RECORDING_NOTIFICATIONS, enabled)
    }
    fun updateRemoteControl(enabled: Boolean) {
        _settings.value = _settings.value.copy(remoteControlEnabled = enabled)
        persist(K.REMOTE_CONTROL, enabled)
    }
    fun updateRemoteCommands(enabled: Boolean) {
        _settings.value = _settings.value.copy(remoteCommandsEnabled = enabled)
        persist(K.REMOTE_COMMANDS, enabled)
    }
    fun updateLanDiscovery(enabled: Boolean) {
        _settings.value = _settings.value.copy(lanDeviceDiscoveryEnabled = enabled)
        persist(K.LAN_DISCOVERY, enabled)
    }
    fun updateDeviceBroadcasting(enabled: Boolean) {
        _settings.value = _settings.value.copy(deviceBroadcastingEnabled = enabled)
        persist(K.DEVICE_BROADCASTING, enabled)
    }
    fun updateTrafficCapture(enabled: Boolean) {
        _settings.value = _settings.value.copy(trafficCaptureEnabled = enabled)
        persist(K.TRAFFIC_CAPTURE, enabled)
    }
    fun updateRequestLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(requestLoggingEnabled = enabled)
        persist(K.REQUEST_LOGGING, enabled)
    }
    fun updateResponseLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(responseLoggingEnabled = enabled)
        persist(K.RESPONSE_LOGGING, enabled)
    }
    // Fix 05 — Music
    fun updateMusicPermissionStatus(status: String) {
        _settings.value = _settings.value.copy(musicPermissionStatus = status)
        persist(K.MUSIC_PERMISSION_STATUS, status)
    }
    fun updateMusicBarDismissed(dismissed: Boolean) {
        _settings.value = _settings.value.copy(musicBarDismissed = dismissed)
        persist(K.MUSIC_BAR_DISMISSED, dismissed)
    }
    fun updateMusicLibraryScanned(scanned: Boolean) {
        _settings.value = _settings.value.copy(musicLibraryScanned = scanned)
        persist(K.MUSIC_LIBRARY_SCANNED, scanned)
    }
    fun updateSelectedLocalTrackId(id: String?) {
        _settings.value = _settings.value.copy(selectedLocalTrackId = id)
        if (id != null) persist(K.SELECTED_LOCAL_TRACK_ID, id)
    }

    // v6.0 transplant (manifest §5.11) — Navigation and Header Modifier update functions.
    // Backup behavior wins: these are purely additive visual preferences; they never touch
    // appearanceMode/selectedAccent/selectedDarkTheme/selectedLightTheme.
    fun updateNavBarOpacity(opacity: Float) {
        _settings.value = _settings.value.copy(navBarBackgroundOpacity = opacity)
        persist(K.NAV_BAR_OPACITY, opacity)
    }
    fun updateNavBarBlurEffect(effect: String) {
        val canonical = com.example.data.prefs.canonicalGlassStyle(effect)
        _settings.value = _settings.value.copy(navBarBlurEffect = canonical)
        persist(K.NAV_BAR_BLUR_EFFECT, canonical)
    }
    fun setNavBarHiddenItems(items: Set<String>) {
        _settings.value = _settings.value.copy(navBarHiddenItems = items)
        persist(K.NAV_BAR_HIDDEN_ITEMS, items)
    }
    fun toggleNavHiddenItem(item: String) {
        val current = _settings.value.navBarHiddenItems
        val updated = if (current.contains(item)) current - item else current + item
        setNavBarHiddenItems(updated)
    }
    fun updateHeaderTopPadding(paddingDp: Int) {
        _settings.value = _settings.value.copy(headerTopPaddingDp = paddingDp)
        persist(K.HEADER_TOP_PADDING, paddingDp)
    }
    fun updateHeaderOpacity(opacity: Float) {
        _settings.value = _settings.value.copy(headerBackgroundOpacity = opacity)
        persist(K.HEADER_OPACITY, opacity)
    }
    fun updateHeaderBlurStyle(style: String) {
        val canonical = com.example.data.prefs.canonicalGlassStyle(style)
        _settings.value = _settings.value.copy(headerBlurStyle = canonical)
        persist(K.HEADER_BLUR_STYLE, canonical)
    }
    fun updateFabBottomSpacing(spacingDp: Int) {
        _settings.value = _settings.value.copy(fabBottomSpacingDp = spacingDp)
        persist(K.FAB_BOTTOM_SPACING, spacingDp)
    }

    fun updateSettings(newSettings: UserSettingsState) {
        _settings.value = newSettings
        val ctx = appContext ?: return
        repoScope.launch {
            ctx.unifiedDataStore.edit { prefs ->
                prefs[K.USER_NAME] = newSettings.userName
                prefs[K.THEME_MODE] = newSettings.themeMode
                prefs[K.CORNER_RADIUS] = newSettings.cornerRadiusDp
                prefs[K.PROXY_PORT] = newSettings.proxyPort
                prefs[K.AUTO_BEAUTIFY] = newSettings.autoBeautify
                prefs[K.SSL_VERIFY] = newSettings.sslVerification
                prefs[K.SNIPPET_LANG] = newSettings.defaultSnippetLang
                prefs[K.ONBOARDING] = newSettings.onboardingCompleted
                prefs[K.APPEARANCE_MODE] = newSettings.appearanceMode
                prefs[K.SELECTED_DARK_THEME] = newSettings.selectedDarkTheme
                prefs[K.SELECTED_LIGHT_THEME] = newSettings.selectedLightTheme
                prefs[SELECTED_ACCENT] = newSettings.selectedAccent
                prefs[K.SWIPE_TO_DELETE] = newSettings.swipeToDeleteEnabled
                prefs[K.HAPTIC_FEEDBACK] = newSettings.hapticFeedbackEnabled
                prefs[K.GESTURE_NAVIGATION] = newSettings.gestureNavigationEnabled
                prefs[K.DENSITY_MODE] = newSettings.densityMode
                prefs[K.UPDATE_NOTIFICATION_POLICY] = newSettings.updateNotificationPolicy
                prefs[K.ANIMATION_ENABLED] = newSettings.animationEnabled
                prefs[K.TRANSITION_BEHAVIOR] = newSettings.transitionBehavior
                prefs[K.DISPLAY_SCALE] = newSettings.displayScale
                prefs[K.AUTO_RECORDING] = newSettings.autoRecordingEnabled
                prefs[K.RECORDING_NOTIFICATIONS] = newSettings.recordingNotificationsEnabled
                prefs[K.REMOTE_CONTROL] = newSettings.remoteControlEnabled
                prefs[K.REMOTE_COMMANDS] = newSettings.remoteCommandsEnabled
                prefs[K.LAN_DISCOVERY] = newSettings.lanDeviceDiscoveryEnabled
                prefs[K.DEVICE_BROADCASTING] = newSettings.deviceBroadcastingEnabled
                prefs[K.TRAFFIC_CAPTURE] = newSettings.trafficCaptureEnabled
                prefs[K.REQUEST_LOGGING] = newSettings.requestLoggingEnabled
                prefs[K.RESPONSE_LOGGING] = newSettings.responseLoggingEnabled
                prefs[K.MUSIC_PERMISSION_STATUS] = newSettings.musicPermissionStatus
                prefs[K.MUSIC_BAR_DISMISSED] = newSettings.musicBarDismissed
                prefs[K.MUSIC_LIBRARY_SCANNED] = newSettings.musicLibraryScanned
                if (newSettings.selectedLocalTrackId != null) prefs[K.SELECTED_LOCAL_TRACK_ID] = newSettings.selectedLocalTrackId
                prefs[K.NAV_BAR_OPACITY] = newSettings.navBarBackgroundOpacity
                prefs[K.NAV_BAR_BLUR_EFFECT] = newSettings.navBarBlurEffect
                prefs[K.NAV_BAR_HIDDEN_ITEMS] = newSettings.navBarHiddenItems
                prefs[K.HEADER_TOP_PADDING] = newSettings.headerTopPaddingDp
                prefs[K.HEADER_OPACITY] = newSettings.headerBackgroundOpacity
                prefs[K.HEADER_BLUR_STYLE] = newSettings.headerBlurStyle
                prefs[K.FAB_BOTTOM_SPACING] = newSettings.fabBottomSpacingDp
            }
        }
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        val ctx = appContext ?: return
        repoScope.launch { ctx.unifiedDataStore.edit { it[key] = value } }
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        val ctx = appContext ?: return
        repoScope.launch { ctx.unifiedDataStore.edit { it[key] = value } }
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        val ctx = appContext ?: return
        repoScope.launch { ctx.unifiedDataStore.edit { it[key] = value } }
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<Float>, value: Float) {
        val ctx = appContext ?: return
        repoScope.launch { ctx.unifiedDataStore.edit { it[key] = value } }
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>, value: Set<String>) {
        val ctx = appContext ?: return
        repoScope.launch { ctx.unifiedDataStore.edit { it[key] = value } }
    }

    /** Short alias so the update*() functions above stay readable. */
    private val K get() = com.example.data.prefs.AppSettingsKeys
}
