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
import com.example.data.models.HttpMethod
import com.example.data.models.MusicSourceType
import com.example.data.models.MusicTrackItem
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.models.RequestBodyType
import com.example.data.models.UserSettingsState
import androidx.datastore.preferences.core.edit
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
    private val _folders = MutableStateFlow<List<NoteFolder>>(
        listOf(
            NoteFolder("f-1", "Security Audits", 0xFF3B82F6, "🛡️"),
            NoteFolder("f-2", "API Endpoints", 0xFF06B6D4, "🌐"),
            NoteFolder("f-3", "Frida Snippets", 0xFFF59E0B, "⚡"),
            NoteFolder("f-4", "Reverse Engineering", 0xFF8B5CF6, "🔍")
        )
    )
    val folders: StateFlow<List<NoteFolder>> = _folders.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteItem>>(
        listOf(
            NoteItem(
                id = "n-1",
                title = "Android 14+ APEX Certificate Store",
                content = "# Android 14+ APEX CA Certificates\n\nStarting with Android 14, system CA certificates moved from `/system/etc/security/cacerts/` to the Conscrypt APEX module at `/apex/com.android.conscrypt/cacerts/`.\n\n### Mounting steps with Magisk:\n```bash\n# Bind mount writable tmpfs over APEX directory\nmount -t tmpfs tmpfs /apex/com.android.conscrypt/cacerts\ncp /system/etc/security/cacerts/* /apex/com.android.conscrypt/cacerts/\ncp /data/local/tmp/requestr_ca.0 /apex/com.android.conscrypt/cacerts/\n```\n- Fully trusted by all system & user apps\n- Works seamlessly with requestr root installer",
                format = NoteFormatType.MARKDOWN,
                folderId = "f-1",
                folderName = "Security Audits",
                folderColorHex = 0xFF3B82F6,
                isPinned = true
            ),
            NoteItem(
                id = "n-2",
                title = "OAuth 2.0 PKCE Checklist",
                content = "- [x] Code Verifier (43-128 chars random Base64URL)\n- [x] Code Challenge = BASE64URL-ENCODE(SHA256(code_verifier))\n- [ ] Authorization request with `code_challenge_method=S256`\n- [ ] Token exchange with raw `code_verifier`\n- [ ] Validate state token prevents CSRF",
                format = NoteFormatType.CHECKLIST,
                folderId = "f-2",
                folderName = "API Endpoints",
                folderColorHex = 0xFF06B6D4,
                isPinned = true
            ),
            NoteItem(
                id = "n-3",
                title = "Custom HTML Report Template",
                content = "<div style=\"font-family: monospace; background: #0A0E1A; color: #60A5FA; padding: 16px; border-radius: 8px;\">\n  <h2>requestr API Audit Report</h2>\n  <p>Endpoint: <code>https://api.internal/v2/user</code></p>\n  <p>Status: <span style=\"color: #22C55E;\">PASSED</span></p>\n</div>",
                format = NoteFormatType.HTML,
                folderId = "f-4",
                folderName = "Reverse Engineering",
                folderColorHex = 0xFF8B5CF6,
                isPinned = false
            )
        )
    )
    val notes: StateFlow<List<NoteItem>> = _notes.asStateFlow()

    fun saveNote(note: NoteItem) {
        val current = _notes.value
        val exists = current.any { it.id == note.id }
        if (exists) {
            _notes.value = current.map { if (it.id == note.id) note.copy(updatedAt = System.currentTimeMillis()) else it }
        } else {
            _notes.value = listOf(note.copy(updatedAt = System.currentTimeMillis())) + current
        }
    }

    fun deleteNote(id: String) {
        _notes.value = _notes.value.filter { it.id != id }
    }

    fun togglePin(id: String) {
        _notes.value = _notes.value.map {
            if (it.id == id) it.copy(isPinned = !it.isPinned) else it
        }
    }

    fun addFolder(name: String, colorHex: Long) {
        _folders.value = _folders.value + NoteFolder(UUID.randomUUID().toString(), name, colorHex)
    }
}

// ==========================================
// 3. FLOW (AUTOMATION GRAPH) REPOSITORY
// ==========================================
object FlowRepository {

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        // Observe nodes from Room
        repoScope.launch {
            db.flowNodeDao().observeAll().collect { entities ->
                if (entities.isEmpty() && _nodes.value == defaultNodes) {
                    // First launch — seed defaults into Room
                    defaultNodes.forEach { node ->
                        db.flowNodeDao().upsert(node.toEntity())
                    }
                    defaultConnections.forEach { conn ->
                        db.flowConnectionDao().upsert(conn.toEntity())
                    }
                } else if (entities.isNotEmpty()) {
                    _nodes.value = entities.map { it.toFlowNodeItem() }
                }
            }
        }
        repoScope.launch {
            db.flowConnectionDao().observeAll().collect { entities ->
                if (entities.isNotEmpty()) {
                    _connections.value = entities.map { it.toFlowConnectionItem() }
                }
            }
        }
    }

    fun addNode(node: FlowNodeItem) {
        _nodes.value = _nodes.value + node
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

    private fun FlowNodeItem.toEntity() = com.example.data.local.FlowNodeEntity(
        id = id, type = type.name, label = title,
        positionX = x, positionY = y, configData = configData
    )

    private fun com.example.data.local.FlowNodeEntity.toFlowNodeItem() = FlowNodeItem(
        id = id, title = label,
        type = try { FlowNodeType.valueOf(type) } catch (_: Exception) { FlowNodeType.TEXT },
        x = positionX, y = positionY, configData = configData,
        inputPorts = emptyList(), outputPorts = emptyList() // Ports derived from type at runtime
    )

    private fun FlowConnectionItem.toEntity() = com.example.data.local.FlowConnectionEntity(
        id = "${fromNodeId}_${fromPort}_${toNodeId}_${toPort}",
        fromNodeId = fromNodeId, fromPort = fromPort,
        toNodeId = toNodeId, toPort = toPort
    )

    private fun com.example.data.local.FlowConnectionEntity.toFlowConnectionItem() = FlowConnectionItem(
        fromNodeId = fromNodeId, fromPort = fromPort,
        toNodeId = toNodeId, toPort = toPort
    )

    suspend fun executeFlow(): String {
        val nodes = _nodes.value.toList()
        val connections = _connections.value.toList()

        if (nodes.isEmpty()) return "No nodes to execute. Add a trigger node to begin."

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

        return buildString {
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
    }
}

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
                    // DB write failed — revert optimistic update by re-reading from Room
                    android.util.Log.w("ApiCollections", "Save failed, reverting: ${e.message}")
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
                    isDarkTheme = p[K.IS_DARK_THEME] ?: _settings.value.isDarkTheme,
                    selectedAccent = p[K.SELECTED_ACCENT] ?: _settings.value.selectedAccent,
                    cornerRadiusDp = p[K.CORNER_RADIUS] ?: _settings.value.cornerRadiusDp,
                    proxyPort = p[K.PROXY_PORT] ?: _settings.value.proxyPort,
                    autoBeautify = p[K.AUTO_BEAUTIFY] ?: _settings.value.autoBeautify,
                    sslVerification = p[K.SSL_VERIFY] ?: _settings.value.sslVerification,
                    defaultSnippetLang = p[K.SNIPPET_LANG] ?: _settings.value.defaultSnippetLang,
                    onboardingCompleted = p[K.ONBOARDING] ?: _settings.value.onboardingCompleted,
                    appearanceMode = p[K.APPEARANCE_MODE] ?: _settings.value.appearanceMode,
                    selectedDarkTheme = p[K.SELECTED_DARK_THEME] ?: _settings.value.selectedDarkTheme,
                    selectedLightTheme = p[K.SELECTED_LIGHT_THEME] ?: _settings.value.selectedLightTheme,
                    swipeToDeleteEnabled = p[K.SWIPE_TO_DELETE] ?: _settings.value.swipeToDeleteEnabled,
                    hapticFeedbackEnabled = p[K.HAPTIC_FEEDBACK] ?: _settings.value.hapticFeedbackEnabled,
                    gestureNavigationEnabled = p[K.GESTURE_NAVIGATION] ?: _settings.value.gestureNavigationEnabled,
                    densityMode = p[K.DENSITY_MODE] ?: _settings.value.densityMode,
                    updateNotificationPolicy = p[K.UPDATE_NOTIFICATION_POLICY] ?: _settings.value.updateNotificationPolicy,
                    animationEnabled = p[K.ANIMATION_ENABLED] ?: _settings.value.animationEnabled,
                    transitionBehavior = p[K.TRANSITION_BEHAVIOR] ?: _settings.value.transitionBehavior,
                    displayScale = p[K.DISPLAY_SCALE] ?: _settings.value.displayScale,
                    autoRecordingEnabled = p[K.AUTO_RECORDING] ?: _settings.value.autoRecordingEnabled,
                    trafficLogEnabled = p[K.TRAFFIC_LOG] ?: _settings.value.trafficLogEnabled,
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
                )
            }
        }
    }

    private val _settings = MutableStateFlow(
        UserSettingsState(
            userName = "",
            themeMode = "INDIGO",
            isDarkTheme = true,
            selectedAccent = "INDIGO",
            cornerRadiusDp = 16,
            proxyPort = 8080,
            autoBeautify = true,
            sslVerification = false,
            defaultSnippetLang = "Python - Requests",
            onboardingCompleted = false,
        )
    )
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    fun updateIsDarkTheme(isDark: Boolean) {
        _settings.value = _settings.value.copy(isDarkTheme = isDark)
        persist(K.IS_DARK_THEME, isDark)
    }

    fun updateSelectedAccent(accent: String) {
        _settings.value = _settings.value.copy(selectedAccent = accent)
        persist(K.SELECTED_ACCENT, accent)
    }

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
    fun updateTrafficLog(enabled: Boolean) {
        _settings.value = _settings.value.copy(trafficLogEnabled = enabled)
        persist(K.TRAFFIC_LOG, enabled)
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
                prefs[K.SWIPE_TO_DELETE] = newSettings.swipeToDeleteEnabled
                prefs[K.HAPTIC_FEEDBACK] = newSettings.hapticFeedbackEnabled
                prefs[K.GESTURE_NAVIGATION] = newSettings.gestureNavigationEnabled
                prefs[K.DENSITY_MODE] = newSettings.densityMode
                prefs[K.UPDATE_NOTIFICATION_POLICY] = newSettings.updateNotificationPolicy
                prefs[K.ANIMATION_ENABLED] = newSettings.animationEnabled
                prefs[K.TRANSITION_BEHAVIOR] = newSettings.transitionBehavior
                prefs[K.DISPLAY_SCALE] = newSettings.displayScale
                prefs[K.AUTO_RECORDING] = newSettings.autoRecordingEnabled
                prefs[K.TRAFFIC_LOG] = newSettings.trafficLogEnabled
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

    /** Short alias so the update*() functions above stay readable. */
    private val K get() = com.example.data.prefs.AppSettingsKeys
}
