### Summary

Every app-layer affordance that is currently dead, fake, or carries a stale `TODO` marker gets a real implementation or an honest removal. Findings are grounded in files re-read on branch `backup` (HEAD `0db606c`), not carried forward from `AUDIT_REPORT.md` — roughly half of that report's app-layer claims are already fixed, and several real defects it never mentioned are still live. Tasks are batched so each large shared file (`MainActivity.kt`, `FlowScreen.kt`, `AppRepositories.kt`, `SettingsScreen.kt`, `ApiTestrScreen.kt`) is opened once.

All four previously blocking decisions are resolved (2026-08-21): notes/tools/chat move to **five new Room tables** with the version number and `Migration` objects owned by the transport scope; the chat **API key only** goes to Keystore-backed storage while non-secret provider config lives in Room; Cookie Jar / Make Tool / Add to Chat / Pin are **out of scope**, verified absent with evidence rather than silently dropped; and `WorkspaceExporter` is **wired** into Settings with its three honesty bugs fixed. Two ordering constraints follow from that: task 9-10 land **after** the transport scope's VPN_TUN relay/NAT rewrite of the interception files, and task 11 lands **after** its Room migration framework.

Every task must leave `./gradlew :app:assembleDebug --no-configuration-cache` green. Add unit tests under `app/src/test/java/com/example/` for all pure logic; `testOptions.unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true }` is already set in `app/build.gradle.kts`, so `android.util.Log` calls do not need mocking.

### Tasks

1. **[parallel] Real global search on Home** — `app/src/main/java/com/example/ui/screens/HomeScreen.kt`, new `app/src/main/java/com/example/data/search/GlobalSearch.kt`

   Today `searchQuery` is dead: `HomeScreen.kt:98` `var searchQuery by remember { mutableStateOf("") }` and `:184-189` `SearchInputField(query = searchQuery, onQueryChange = { searchQuery = it }, placeholder = "Search APIs, notes, chats, tools...", onClear = { searchQuery = "" })` are the only four references `grep -n "searchQuery" HomeScreen.kt` returns.

   Add `object GlobalSearch` in `data/search/` next to the existing `TrafficSearch.kt`, with a pure `fun search(query: String, apis: List<ApiRequestItem>, notes: List<NoteItem>, tools: List<ToolItem>, flows: List<FlowNodeItem>): List<GlobalSearchHit>` and a `sealed interface GlobalSearchHit` carrying the target (Api / Note / Tool / FlowNode / Section). Match case-insensitively on the fields the user can see: api `name`+`url`, note `title`+`content`, tool `title`+`description`, flow node `title`. Follow the substring style already used at `ToolsScreen.kt:74-85`.

   In `HomeScreen`, when `searchQuery.isNotBlank()`, replace the card grid with a results `LazyColumn` grouped by kind, using the callbacks the composable already receives (`onOpenApiRequest`, `onOpenNote`, `onNavigateToSection`, `onOpenSettings` — signature at `HomeScreen.kt:76-83`). Tools navigate via `onNavigateToSection("tools")` since `HomeScreen` has no tool-id callback; flow nodes via `onNavigateToSection("flow")`. Render a genuine empty state ("No matches for …") rather than an empty list. Drop `chats` from the placeholder text unless task 12 lands, so the placeholder does not promise a source that isn't searched.

   Also fix the dishonest badge at `HomeScreen.kt:468` `badge = "35+ tools"` — `ToolsRepository.getToolsCatalog()` builds 19 built-ins (`ToolsRepository.kt:63-83`). Make it read the real catalogue size.

   *Tests:* new `app/src/test/java/com/example/data/search/GlobalSearchTest.kt` — matches by api name, api url, note body, tool description; case-insensitivity; blank query returns empty; no-match returns empty; multi-kind query returns hits from each kind.

2. **[parallel] Flow import, node editing, and honest run banner** — `app/src/main/java/com/example/ui/screens/FlowScreen.kt`

   *(a) Import is fake.* `FlowScreen.kt:171-187` launches `Intent(ACTION_GET_CONTENT)` from a `Dispatchers.IO` coroutine with no result handling. `FlowSerializer.importFromJson` has zero call sites app-wide (`grep` finds only `HarImporter.kt:30/94/103`, an unrelated same-named method). Replace with `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`, following the existing pattern at `OnboardingScreen.kt:130-140` and `InterceptScreen.kt:226`. On result: read the stream, call `FlowSerializer.importFromJson(json)`, and on non-null push nodes+connections through a new `FlowRepository.replaceGraph(nodes, connections)` (task 4) so Room and the in-memory `StateFlow` agree. Surface success and failure — a null parse must say so, not silently do nothing.

   *(b) Node edit is a dead end.* `nodeForEdit` at `:116` is assigned at `:244` (context-menu Edit) and `:306` (Sequence-List edit button) and never read. Add an `EditFlowNodeBottomSheet` mirroring `AddFlowNodeBottomSheet` (`:717-797`) with title, type, and `configData` fields, wired to a new `FlowRepository.updateNode(node)` (task 4).

   *(c) Run banner lies on failure.* `:215-231` always uses `Color(0xFF10B981)` and `Icons.Default.CheckCircle`. Change `FlowRepository.executeFlow()` to return a result carrying a success boolean (task 4) and colour/icon the banner from it.

   *(d) Fit-to-view is hardcoded.* `:390-417` sets `scale = 0.8f` and `+ 300f` offsets instead of deriving from the measured canvas. Capture the canvas size via `onSizeChanged` and compute `scale` and offsets from the real bounding box so the button actually fits content.

   *(e) Remove the stale label* `// Fix #25 (TODO): Flow export/import via FlowSerializer` at `:127` — export at `:129-152` is real.

   *Tests:* UI-level behaviour here is hard to unit-test; cover the serializer round-trip instead in task 4's test file (`exportToJson` → `importFromJson` preserves node ids, types, positions, `configData`, and connection ports).

3. **[after 2] Flow branching and real condition evaluation** — `app/src/main/java/com/example/flow/FlowExecutor.kt`, new `app/src/main/java/com/example/flow/ConditionEvaluator.kt`

   *(a) Conditions don't branch.* `FlowExecutor.kt:64-66` enqueues every downstream node — `adjacency[nodeId]?.forEach { nextId -> if (nextId !in visited) queue.addLast(nextId) }` — and `buildAdjacency` (`:200-206`) collapses connections to `Map<fromNodeId, List<toNodeId>>`, discarding `fromPort` entirely. Rebuild adjacency as `Map<String, List<Pair<String /*fromPort*/, String /*toNodeId*/>>>`. After executing a `CONDITION` node, follow only edges whose `fromPort` matches the boolean result (`"true"`/`"false"`), falling back to `"out"` edges when the graph has no labelled ports so existing saved flows keep working.

   *(b) Condition logic is substring guesswork.* `:140-168` lowercases `configData` and pattern-matches: `config.contains("status") && config.contains("200")` then checks `input.contains("200")`; `config.contains("contains")` takes `substringAfter("contains")`; the `not empty` branch at `:155-157` is unreachable because `contains("empty")` at `:152` matches first; and `else -> "true"` defaults every unrecognised config to true.

   Extract a pure `object ConditionEvaluator` with `fun evaluate(config: String, input: String): Boolean` parsing `<left> <op> <right>` where op ∈ `==`, `!=`, `contains`, `notContains`, `matches`, and the unary forms `empty` / `notEmpty`. Resolve `left` as `status` (extract the HTTP status from the input string), `body` (the whole input), or a JSON key resolved through the existing shared `flow/JsonFieldExtractor.extractByKey` (already reused by `executeTransformNode`). An unparseable config must return `false` and be reported as an error, never silently `true`.

   *(c) Hidden defaults.* `executeApiNode` (`:102-123`) silently substitutes `"https://httpbin.org/get"` when the node has no URL and hardcodes `User-Agent: requestr-flow/3.0`. Make a missing URL a real node error instead of quietly hitting a third-party host.

   *Tests:* new `app/src/test/java/com/example/flow/ConditionEvaluatorTest.kt` — `status == 200` true/false; `body contains token`; `notEmpty` on `{}` and on real content; JSON-key left side via `JsonFieldExtractor`; `matches` with a valid and an invalid regex; unparseable config returns false. New `app/src/test/java/com/example/flow/FlowExecutorBranchingTest.kt` — a graph `entry → condition → (true: A, false: B)` executes only A when the condition holds and only B when it doesn't; an unlabelled-port graph still executes downstream nodes.

4. **[parallel] Flow repository correctness in `AppRepositories.kt`** — `app/src/main/java/com/example/data/repository/AppRepositories.kt`

   *(a) Ports are lost across restart.* `FlowNodeEntity.toFlowNodeItem()` at `:256-261` sets `inputPorts = emptyList(), outputPorts = emptyList()` with the comment "Ports derived from type at runtime", but nothing derives them. Derive them from `FlowNodeType` in the mapper: `CONDITION` gets `outputPorts = listOf("true","false")`, everything else `listOf("out")`; inputs `listOf("in")` except entry-style nodes.

   *(b) Connection ids mismatch.* `FlowConnectionEntity.toFlowConnectionItem()` at `:269-272` omits `id`, so the item takes the `UUID.randomUUID()` default from `FlowConnectionItem` (`AppModels.kt:355`), while `toEntity()` at `:263-267` builds `id = "${fromNodeId}_${fromPort}_${toNodeId}_${toPort}"`. `removeConnection(id)` filters `_connections` by `it.id`, so removing a Room-loaded connection can silently fail. Additionally `FlowScreen.kt`'s port-drag builds a third format, `"${fromId}_out_${toId}_in"`. Centralise one id function and use it in all three places; map `id` through in `toFlowConnectionItem()`.

   *(c) New repository API for task 2:* `replaceGraph(nodes, connections)` (clear both Room tables then insert, and update both `StateFlow`s) and `updateNode(node)` (upsert entity + in-memory replace). Follow the existing `addNode`/`persistNode` style at `:195-207`.

   *(d) `executeFlow()` hides failure.* At `:274-310` it returns a human-readable `String`, so `FlowScreen` cannot tell success from failure. Return a small `data class FlowRunSummary(val success: Boolean, val message: String)` (or expose `FlowResult` directly) and update the one call site in `FlowScreen.kt:191-197`.

   *(e) `FlowSerializer.exportToCompactJson` is misnamed* — it delegates straight to `exportToJson`. Either make it genuinely compact (`toString()` instead of `toString(2)`) or delete it; it has no call sites.

   *(f) `ApiCollectionsRepository.saveRequest` claims a rollback it doesn't do.* `:606-609` catches and logs `"Save failed, reverting: ${e.message}"` with no revert. Either re-read from Room to actually revert, or reword the comment and log to say the optimistic value stays until the Room `observeAll()` flow corrects it.

   *(g) Remove the stale label* at `:797` (`// Fix #30 (TODO): removed stale runtime flags …`) and the matching one at `AppModels.kt:392` — both describe completed removals.

   *Tests:* new `app/src/test/java/com/example/flow/FlowSerializerRoundTripTest.kt` — export→import preserves ids, types, positions, `configData`, `fromPort`/`toPort`; malformed JSON returns null. New `app/src/test/java/com/example/data/repository/FlowMapperTest.kt` (pure mapper functions may need to be made internal/visible) — `CONDITION` nodes derive `true`/`false` output ports; connection id survives entity→item→entity.

5. **[parallel] Deep links: real actions and a live drain** — `app/src/main/java/com/example/MainActivity.kt`

   *(a) The drain runs once.* `:345-358` does `LaunchedEffect(Unit) { … while (true) { val uri = MainActivity.pendingDeeplinks.poll() ?: break; … } }`. Because the effect body runs once and `break`s on the first empty poll, links delivered by `onNewIntent` (`:120-127`) while the app is open — precisely what the Fix #22 comment promises — are enqueued and never handled. Replace the `ConcurrentLinkedQueue` with a `MutableSharedFlow<Uri>` (extraBufferCapacity, `tryEmit` from `onNewIntent`) collected in a `LaunchedEffect(navController)`.

   *(b) Advertised actions don't exist.* `SettingsScreen.kt:646-651` lists `requestr://intercept/toggle`, `requestr://api/run?id=req-1`, `requestr://flow/execute?id=flow-1`, `requestr://note/create?title=Log`. `handleRequestrDeeplink` (`:219-260`) only maps hosts to routes. Also `"flow/execute" -> Screen.Flow.route` at `:234` is unreachable dead code — a URI host can never contain `/`.

   Implement the four advertised actions by parsing `uri.host` + `uri.pathSegments` + query params, then navigating **and** performing the action: `intercept/toggle` → route to Intercept and drive start/stop through `CaptureController` (`ui/screens/CaptureController.kt:107/161`) so the same pre-flight gate applies as the FAB; `api/run?id=` → `ApiCollectionsRepository.setActiveRequest` then execute via `ApiTestrRepository.executeRequest`; `flow/execute?id=` → `FlowRepository.executeFlow()`; `note/create?title=` → `NotesRepository.saveNote` with the given title and open Notes. Delete the unreachable `"flow/execute"` host branch. Replace the silent `catch (_: Exception) { }` with a logged failure so a broken link is diagnosable.

   If any action cannot be implemented cleanly (e.g. `intercept/toggle` needs an Activity-scoped consent launcher that a top-level function can't reach), remove that row from the `SettingsScreen.kt:646-651` list rather than leaving it advertised.

   *(c) Keyboard shortcuts are no-ops.* `:131-155` calls `sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))` and `…NEW_REQUEST`; `grep -rn "registerReceiver\|BroadcastReceiver" app/src/main` returns nothing, so only `KEYCODE_ESCAPE` works. Replace the broadcasts with an in-process `object AppShortcuts { val events: SharedFlow<ShortcutEvent> }` that `ApiTestrScreen` collects (it already has a `SnackbarHostState` and `coroutineScope` at `ApiTestrScreen.kt:139-142`) to trigger send and new-request. Remove the stale `(TODO)` labels at `:122`, `:214`, `:345`.

   *Tests:* new `app/src/test/java/com/example/DeeplinkRouterTest.kt` — extract the URI→intent mapping into a pure `object DeeplinkRouter` returning a `sealed interface DeeplinkAction` so it is testable without Compose. Cover: each host maps to the right route; `api/run?id=x` yields a RunApi action carrying `x`; `flow/execute` yields ExecuteFlow; unknown host returns null; path-form fallback (`requestr:///notes`) still resolves.

6. **[parallel] Markdown and checklist rendering in Notes** — `app/src/main/java/com/example/ui/screens/NotesScreen.kt`, new `app/src/main/java/com/example/ui/text/MarkdownRenderer.kt`

   `NotesScreen.kt:243-256` admits in-code that "Markdown shows as-is (monospace) since no MD render lib exists", and the HTML path does `AnnotatedString(spanned.toString())` — calling `.toString()` on the `Spanned` discards every span, so "HTML rendering" is really tag-stripping. There is no markdown dependency in `app/build.gradle.kts`, so hand-roll a small renderer: `object MarkdownRenderer { fun render(source: String): AnnotatedString }` supporting ATX headings, `**bold**`, `*italic*`, `` `code` ``, fenced code blocks, unordered lists, and links-as-styled-text. Convert the HTML path to walk the `Spanned`'s spans into `AnnotatedString` styles instead of flattening to a string. Render `CHECKLIST` notes as real checkbox rows parsed from `- [ ]` / `- [x]`, writing changes back through `NotesRepository.saveNote`.

   Also: the export block at `:122-161` is real, so remove the stale `// Fix #18 (TODO): Export note as MD or HTML file` at `:121`; replace the silent `?: return@launch` with user-visible failure; and drop the pointless `when (editFormat)` whose every branch returns `editContent`.

   *Tests:* new `app/src/test/java/com/example/ui/text/MarkdownRendererTest.kt` — headings produce larger/bold spans; `**bold**` and `*italic*` produce the right `SpanStyle` ranges and strip the markers; inline and fenced code use monospace; list items are prefixed; unmatched markers are left literal. New `MarkdownChecklistTest.kt` — `- [x]`/`- [ ]` parse to checked/unchecked items and toggling round-trips back into the source text.

7. **[parallel] Custom tools: real workspace, live catalogue, no placeholder** — `app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt`, `app/src/main/java/com/example/ui/screens/ToolsScreen.kt`, `app/src/main/java/com/example/data/repository/ToolsRepository.kt`

   *(a) Every custom tool hits "Coming Soon".* `ToolWorkspaceScreen.kt:124-156` dispatches all 19 built-in ids and `:157` falls through to `PlaceholderWorkspace(tool.title)`, which renders `"🚧"` + `"$title — Coming Soon"` (`:709-717`). Custom tools carry ids like `custom-strip-html`, so they all land there — even though `ToolsRepository.executeToolFunction` (`:350`) already routes `customTools[toolId]` to `executeCustomTool`. Add a `CustomToolWorkspace` (input field → `executeToolFunction(tool.id, input)` → `outputBlock`, reusing the existing shared helpers `modeRow`, `outputBlock`, `copyToClipboard`) and delete `PlaceholderWorkspace` so no id can silently dead-end again.

   *(b) A new custom tool doesn't appear.* `ToolsScreen.kt:74` `val allTools = ToolsRepository.getToolsCatalog()` is a plain call and `filteredTools = remember(searchQuery, selectedCategory) { … }` does not key on the catalogue. Expose `ToolsRepository.catalog: StateFlow<List<ToolItem>>` backed by the in-memory custom-tool map, collect it in `ToolsScreen`, and key the `remember` on it.

   *(c) Fragile description round-trip.* `addCustomTool` (`:393-403`) recovers `operation`/`pattern`/`replacement` by re-parsing the display `description` (`startsWith("Regex:")`, `substringAfter(": ")`, `substringBefore(" → ")`, `substringAfter("→ ")`). Change the signature to accept the structured `CustomTool` fields directly and have `getToolsCatalog()` build the description for display only. Update the create sheet at `ToolsScreen.kt:164-256`.

   *(d) Remove the stale label* `// Fix #37 (TODO): "Create Custom Tool" card …` at `ToolsScreen.kt:124` — the card and sheet are real.

   *Tests:* new `app/src/test/java/com/example/data/repository/ToolsRepositoryCustomToolTest.kt` — a structured `addCustomTool` then `executeToolFunction` applies a regex replace and a template substitution; an invalid regex returns the error JSON rather than throwing; the tool appears in `catalog`; the display description is derived, not parsed.

8. **[parallel] Music: wire the buttons, delete the fictional sources** — `app/src/main/java/com/example/ui/screens/MusicScreen.kt`, `app/src/main/java/com/example/data/models/AppModels.kt`, `app/src/main/java/com/example/data/repository/AppRepositories.kt`

   *(a) Two no-op buttons.* `MusicScreen.kt:115` `IconButton(onClick = { /* Refresh local tracks */ })` labelled "Scan Audio Files" and `:276` `Button(onClick = { /* Scan Audio */ })` labelled "Import MP3". `MusicRepository.loadLocalLibrary(context)` (`AppRepositories.kt:338-341`) exists and is only called from `LaunchedEffect(Unit)` at `MusicScreen.kt:82-86`. Wire the header icon to a real re-scan (with a progress indication and a result message including the track count). For "Import MP3", either open a document picker that copies/adds the chosen audio file, or — if that lands outside this scope — remove the button; a labelled button that does nothing is exactly what the stub-or-remove standard forbids.

   *(b) Confirmation promised but absent.* `:289` says `// Fix #35 (TODO): swipe-to-remove with confirmation` and `:290-316` really removes the track via `rememberSwipeToDismissBoxState(confirmValueChange = …)` with no dialog. Add a confirmation dialog (reuse `ConfirmActionDialog`, used at `InterceptScreen.kt:629`) that returns `false` from `confirmValueChange` until confirmed, then remove the stale label.

   *(c) Fictional sources.* `MusicSourceType` (`AppModels.kt:362-366`) declares `SYNTH_LOFI("requestr Synth Beats")`, `AMBIENT_CYBER("Cyber Cyberpunk Ambience")`, `CHILL_CODING("Chill Coding Terminal")`, `LOCAL("Local Storage")`. `grep -rn "YouTube\|JioSaavn" app/src` returns zero hits; `LocalMusicScanner` only ever produces `LOCAL`. Delete the three unreachable values and update `MusicScreen.kt:173` (`currentTrack.source.label`) and the `MusicRepository` default track (`AppRepositories.kt:321-323`) accordingly.

   *Tests:* no pure logic is introduced; verification is `:app:compileDebugKotlin` plus a grep confirming zero remaining references to the deleted enum values.

9. **[after the transport plan's VPN_TUN relay/NAT rewrite] Capture settings must be readable without MainActivity** — `app/src/main/java/com/example/data/prefs/SettingsDataStore.kt`, `app/src/main/java/com/example/interception/CaptureEngine.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/RequestrVpnService.kt`

   `CaptureEngine` reads `SettingsRepository.settings.value.requestLoggingEnabled` / `.responseLoggingEnabled` at `:499`, `:502`, `:503` (gRPC persist path) and again at `:602-605` (plaintext HTTP path). `SettingsRepository` *is* DataStore-backed (`AppRepositories.kt:741-785`), but `SettingsRepository.init()` is called only from `MainActivity.onCreate()`. `CaptureEngine` runs inside `RequestrVpnService`, which the system can start or restart without the Activity; `initialized`/`appContext` are per-process, so in that case `settings.value` returns the hardcoded `true` defaults from `UserSettingsState` (`AppModels.kt:427-429`) and silently ignores the user's persisted choice.

   `SettingsDataStore` has no flows for these three keys today (its list at `:57-77` covers proxyPort … gatewayPort), although the keys exist in `UnifiedDataStore.kt:74-76` (`TRAFFIC_CAPTURE`, `REQUEST_LOGGING`, `RESPONSE_LOGGING`). Add `requestLogging`, `responseLogging`, and `trafficCapture` as `Flow<Boolean>` in `SettingsDataStore`, read them in `RequestRVpnService.startCapture()` alongside the existing `printUndecoded` (`:139`), `dumpToFile` (`:140`), `enhancedMode` (`:145`), and thread them into `PacketPump` → `CaptureEngine` as constructor vars, exactly like `captureEnabled`/`enhancedMode` are threaded today (`PacketPump.kt:47-51,73`). Remove the direct `SettingsRepository` reads from `CaptureEngine`.

   **Hard ordering constraint.** These three interception files are owned by the transport-core plan and are being heavily rewritten there for the VPN_TUN relay/NAT gap. This task must land **after** that rewrite. Keep the change minimal and surgical — as a diff against current code it is: three new `Flow<Boolean>` accessors in `SettingsDataStore` (purely additive; the keys already exist at `UnifiedDataStore.kt:74-76`), parameter threading through `RequestrVpnService.startCapture()` → `PacketPump` → `CaptureEngine` with no control-flow change, and five replaced read sites in `CaptureEngine` (`:499`, `:502`, `:503`, `:602-605`). Roughly a dozen changed lines, so reapplying after a conflict is cheap.

   *Tests:* no new pure logic; verify by asserting `CaptureEngine.kt` no longer references `SettingsRepository` (grep) and that `:app:assembleDebug` stays green.

10. **[after 9] Remove stale TODO labels on already-real code** — `app/src/main/java/com/example/interception/CaptureEngine.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/RequestrVpnService.kt`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`, `app/src/main/java/com/example/data/export/WorkspaceExporter.kt`

    The user requires zero `TODO` comments left behind. These five are labels on working implementations, verified:

    - `CaptureEngine.kt:263` `// Fix #14 (TODO): Detect WebSocket upgrade handshake` — real: `WebSocketParser.looksLikeUpgrade` at `:265`, frame parsing at `:301-321`, `persistWsFrame` at `:662-688`.
    - `CaptureEngine.kt:277` `// Fix #15 (TODO): If the response is SSE …` — `SseParser.looksLikeSse`/`parseEvents` are called at `:279-280`. Caveat: the parsed events are only `Log.i`'d, never persisted, despite the comment saying "persist the event stream as a tagged transaction". Either persist them the way `persistWsFrame` does, or reword the comment to state that SSE is detected and logged only.
    - `CaptureEngine.kt:535` `// Fix #7 (TODO): Implement all rule actions, not just BLOCK.` — all of BLOCK/REDIRECT/MODIFY_REQUEST/MODIFY_RESPONSE/LOG_ONLY are handled at `:537-580`.
    - `PacketPump.kt:168` `// Fix #16 (TODO): intercept DNS queries on UDP port 53.` — real: `DnsParser.parse` + `dnsCallback` at `:169-176`.
    - `RequestrVpnService.kt:180` `// Fix #2 (TODO): Hot-reload app filter …` — real observer at `:178-198` (`combine(selectedPackages, appFilterMode).drop(1).collect { … stopCapture(); delay(500); startCapture() }`). Note while here that `startCapture()` re-registers the observer on every restart and `.drop(1)` is per-collector, so rapid filter changes can stack restarts — worth a guard.
    - `SettingsScreen.kt:1246` `// Fix #21 (TODO): auto-promote to system store when root/Shizuku` — real implementation immediately below at `:1249-1271`. Add an `onFailure` branch to the `runCatching`, which currently produces no user feedback when promotion fails.
    - `WorkspaceExporter.kt:17` `TODO #50 (Workspace system).` — see task 13.

    *Tests:* `grep -rn "TODO" app/src/main/java/com/example --include=*.kt` must return only intentional non-marker prose, and `:app:assembleDebug` stays green.

11. **[after the transport plan's Room migration framework] Persist Notes, custom tools, and chat history to five new Room tables** — `app/src/main/java/com/example/data/local/Entities.kt`, `Daos.kt`, `app/src/main/java/com/example/data/repository/AppRepositories.kt`, `ToolsRepository.kt`, `ChatRepository.kt`, `MainActivity.kt`

    `NotesRepository` (`AppRepositories.kt:41-101`) holds 3 hardcoded seed notes (`n-1` MARKDOWN, `n-2` CHECKLIST, `n-3` HTML) and 4 seed folders in `MutableStateFlow`s; `saveNote`/`deleteNote`/`togglePin` only mutate memory. `ToolsRepository.customTools` is a `ConcurrentHashMap` (`:382`, commented "stored in-memory"). `ChatRepository._messages` is a `MutableStateFlow` cleared by `clearHistory()`. All three vanish on process death.

    **Decision Q1(a): five new tables.** This task requests tables and columns only. **The schema version number and the `Migration` objects belong to the transport scope**, which is also replacing `.fallbackToDestructiveMigration(dropAllTables = true)` (`RequestrDatabase.kt:69`) with real migrations. These tables must therefore arrive inside a proper `Migration` with `CREATE TABLE` statements — **never a destructive wipe**, which would destroy the captured traffic, saved requests, and flow graphs that already persist today.

    | New table | Columns | Backs |
    |---|---|---|
    | `notes` | `id` TEXT PK, `title` TEXT, `content` TEXT, `format` TEXT (`NoteFormatType` name), `folderId` TEXT nullable, `folderName` TEXT, `folderColorHex` INTEGER, `isPinned` INTEGER, `createdAt` INTEGER, `updatedAt` INTEGER; index on `isPinned`, `updatedAt` | `NotesRepository._notes` (`AppRepositories.kt:52-90`) |
    | `note_folders` | `id` TEXT PK, `name` TEXT, `colorHex` INTEGER, `emoji` TEXT | `NotesRepository._folders` (`AppRepositories.kt:42-50`) |
    | `custom_tools` | `id` TEXT PK, `name` TEXT, `operation` TEXT (`regex-replace`\|`template`), `pattern` TEXT, `replacement` TEXT, `createdAt` INTEGER | `ToolsRepository.customTools` (`ToolsRepository.kt:382`) |
    | `chat_messages` | `id` TEXT PK, `role` TEXT (`user`\|`assistant`), `content` TEXT, `timestamp` INTEGER; index on `timestamp` | `ChatRepository._messages` (`ChatRepository.kt:25-26`) |
    | `chat_providers` | `id` TEXT PK, `name` TEXT, `baseUrl` TEXT, `model` TEXT, `isActive` INTEGER, `createdAt` INTEGER — **no API-key column** | task 12's provider list |

    Column shapes mirror the existing models exactly: `NoteItem`/`NoteFolder` (`AppModels.kt:316-330`), `ToolsRepository.CustomTool` (`:384-390`), `ChatMessage` (`AppModels.kt:438-443`). Follow the entity conventions already in `data/local/Entities.kt` (`@Entity(tableName = …, indices = [Index("…")])`, `@PrimaryKey`) and add five DAOs to `data/local/Daos.kt` shaped like `SavedApiRequestDao`: `observeAll(): Flow<List<E>>`, `getAll()`, `upsert(e)`, `deleteById(id)`.

    Wire each repository following the `ApiCollectionsRepository` pattern (`AppRepositories.kt:524-640`): `init(context)` collecting `dao().observeAll()` into the `StateFlow`, writes going through the DAO. Register each new `init(context)` in `MainActivity.onCreate()` beside the existing sequence (`SettingsRepository.init`, `ApiCollectionsRepository.init`, `FlowRepository.init`, `ApiTestrRepository.init`, …). There is no `Application` subclass, so `onCreate` is the single init site.

    **Seed data.** Once Room-backed, seed only when the table is genuinely empty — mirror `FlowRepository`'s `defaultNodes`/`defaultConnections` approach (`AppRepositories.kt:154-272`) so restarts do not duplicate rows and a user who deletes every note does not get them resurrected. `RequestrDatabase`'s own KDoc sets the precedent: "Ships with NO seed data by design … so the UI renders a genuine empty state instead of the fabricated sample rows that shipped in v2.1." Prefer no seed data at all for `notes`; if seeds are kept, gate them behind a one-time "seeded" DataStore flag.

    *Tests:* new tests using `Room.inMemoryDatabaseBuilder` (Robolectric is already a `testImplementation` dependency) — a saved note survives a simulated restart; deleting all notes leaves the table empty and does **not** re-seed; a custom tool survives restart and is still executable through `ToolsRepository`; chat history round-trips with role and ordering preserved.

12. **[after 11] Chat provider configuration with the API key in Keystore-backed storage** — `app/src/main/java/com/example/data/repository/ChatRepository.kt`, `app/src/main/java/com/example/ui/screens/ChatScreen.kt`, `SettingsScreen.kt`, new `app/src/main/java/com/example/data/prefs/SecureKeyStore.kt`

    `ChatRepository.kt:27-29` declares `var providerUrl`, `var providerKey`, `var providerModel` as plain mutable fields with no persistence and **no writer anywhere** — `grep "providerUrl|providerKey"` outside `ChatRepository.kt` matches only `ChatScreen.kt:95`, which *reads* `providerUrl.isBlank()`. So `callProvider` (`:73-119`, a real OpenAI-compatible POST with a last-10-message window) is permanently unreachable and the app is always in local-mock mode via `generateLocalResponse` (`:125-147`, hardcoded keyword matching). The error text at `:117` even says "Check provider URL and API key in Settings" — a Settings screen that does not exist. `ChatScreen.kt:95` also reads a plain `var` inside composition, so the banner would never recompose even once a writer existed.

    Add a real provider-configuration UI — a new `SettingsSectionHeader("AI PROVIDER")` card in `SettingsScreen.kt` following the existing card style — with URL, model, and key fields. Replace the three plain `var`s with a `StateFlow<ChatProviderConfig>` on `ChatRepository` so the banner recomposes. Keep `generateLocalResponse` as a fallback but label it honestly ("Local mock — no provider configured"). Remove the stale `TODO #44` labels at `ChatRepository.kt:17` and `ChatScreen.kt:55`.

    **Decision Q2(b): the API key goes to Keystore-backed storage; nothing else does.** `name`, `baseUrl`, `model`, `isActive`, `createdAt` live in the `chat_providers` Room table from task 11. Only `apiKey` is encrypted. The Room row stores the key's **alias**, never the key: lookup is `provider.id` → alias → decrypt.

    **Storage mechanism — decided: raw `AndroidKeyStore`, zero new dependencies.** The decision originally named `EncryptedSharedPreferences`. `androidx.security:security-crypto` and `EncryptedSharedPreferences` are **deprecated upstream**; the `androidx.security.crypto` reference now points callers at plain `android.content.SharedPreferences` and there is no 1:1 official replacement. **Confirmed 2026-08-21: use option (a) below. Do not add `androidx.security:security-crypto` or `tink-android`** — the table is provenance, not a menu. Resolution checked against the configured repositories (`settings.gradle.kts`: `google()`, `mavenCentral()`, `jitpack.io`, `api.xposed.info`, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`):

    | Option | Artifact | Repo | Latest | Verdict |
    |---|---|---|---|---|
    | **(a) CHOSEN** | *none* — `java.security.KeyStore` + `javax.crypto` | platform | — | Zero new dependencies; `minSdk = 24` (`app/build.gradle.kts:18`) is above the API 23 floor for `KeyGenParameterSpec` + `AndroidKeyStore` AES/GCM |
    | (b) rejected | `com.google.crypto.tink:tink-android` | mavenCentral | **1.18.0** | Resolves and is maintained, but a new dependency and larger APK for no benefit at this scale |
    | (c) rejected | `androidx.security:security-crypto` | google | **1.1.0** (metadata lastUpdated 2025-07-30) | Resolves, but deprecated upstream |

    `gradle/libs.versions.toml` has no `security`/`crypto`/`tink` entry today (`grep` → exit 1), so any of these is brand new.

    **Implementation (option a).** New `data/prefs/SecureKeyStore.kt`: generate/fetch an AES-256 key in `AndroidKeyStore` via `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)` with `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE`; `Cipher.getInstance("AES/GCM/NoPadding")` to encrypt; store `iv + ciphertext` Base64-encoded in the existing unified DataStore under a new key. API: `suspend fun putSecret(alias, value)`, `getSecret(alias): String?`, `removeSecret(alias)`. The app already calls `KeyStore.getInstance` in 7 places (`MitmProxyEngine.kt:638`, `CaWhitelistService.kt:71`, `CertificateAuthority.kt:64/129/175`, `CapabilityProbe.kt:162`, `RootModuleGenerator.kt:216`), though none use `AndroidKeyStore` for app-generated keys yet.

    **Guardrails — this is hand-rolled crypto, not a library.** Three non-negotiables: a fresh random IV per encryption, never reused across writes; a GCM auth-tag failure on decrypt (`AEADBadTagException`) surfaced to the user as "key unreadable, re-enter it" rather than a silent empty string that would make the app look configured when it is not; and the plaintext key never passed to `Log` at any level.

    **Cross-scope handoff, no action here.** `CertificateAuthority.kt:42` is `private val keystorePassword = "requestr-ca-local".toCharArray()` — a hardcoded PKCS#12 password protecting the CA private key, persisted by `persist()` (`:127-136`) to `File(context.filesDir, KEYSTORE_FILE)`. Same class of problem. **Routed to the SDK/decryption scope (confirmed 2026-08-21); this plan takes no action.** Recorded so the finding survives between scopes.

    *Tests:* new `app/src/test/java/com/example/data/repository/ChatProviderConfigTest.kt` — a provider row round-trips through in-memory Room with no key column present; `isConfigured` is false for blank url or blank key; the request-body builder produces the expected OpenAI-compatible JSON with the last-10-message window (`ChatRepository.kt:73-119`). New `app/src/test/java/com/example/data/prefs/SecureKeyStoreTest.kt` (Robolectric ships an `AndroidKeyStore` shim) — `putSecret` then `getSecret` returns the original; `removeSecret` makes it null; the persisted DataStore value is **not equal** to the plaintext key, proving it is actually encrypted.

13. **[parallel] Wire Workspace export/import into Settings and fix its three honesty bugs** — `app/src/main/java/com/example/data/export/WorkspaceExporter.kt`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`

    `WorkspaceExporter.exportWorkspace` (`:25-89`) and `importWorkspace` (`:96-166`) are complete and real — they read and write intercept rules, saved requests, and flow nodes/connections — but `grep -rn "exportWorkspace\|importWorkspace" app/ sdk/ cli/` finds only their own declarations. **Zero call sites.** Decision Q4: wire it. Orphaned working code is the "looks done, isn't reachable" pattern this pass exists to end; there is no delete branch.

    Add an Export/Import row to the existing `SettingsSectionHeader("BACKUP & PRIVACY")` section (`SettingsScreen.kt:1393`):

    - **Export** — `WorkspaceExporter.exportWorkspace(context)`, then `FileExporter.writeJson(context, json)` with the `?: error("Failed to write … to Downloads/requestr/")` guard, then `FileExporter.shareFile(context, target, "application/json", "requestr Workspace")`. This is the exact pattern already used for the traffic-log export at `SettingsScreen.kt:1434-1440`. Never a raw `File` — scoped storage silently fails on API 29+.
    - **Import** — an `ActivityResultContracts.OpenDocument()` launcher filtered to `application/json`, read the stream, call `importWorkspace`, and surface `ImportResult.summary` (`WorkspaceExporter.kt:176-178`). Show `errors` when non-empty rather than swallowing them.

    Three honesty bugs to fix in the same pass:

    | Bug | Evidence | Fix |
    |---|---|---|
    | KDoc overclaims contents | `:11-16` says a workspace includes rules, saved requests, flows, **environments, notes, and settings** — the export writes only rules, `savedRequests`, and flow nodes/connections | Correct the KDoc to list what it actually writes. Notes become exportable after task 11, so extending the export is a clean follow-up |
    | Hardcoded app version | `:30` `root.put("appVersion", "3.8")` while `versionName = "5.8-backup"` (`app/build.gradle.kts:21`) | `BuildConfig.VERSION_NAME`. `buildConfig = true` is already set (`app/build.gradle.kts:56`) and `BuildConfig.VERSION_NAME` is already used at `SettingsScreen.kt:1486` and `AppNavigationDrawer.kt:338` |
    | `requestsImported` always 0 | Export writes `savedRequests` (`:48-60`), but `importWorkspace` never reads it — the counter is declared at `:100`, never incremented, and reported at `:166` | Add a `savedRequests` read loop mirroring the rules loop, upserting through `savedApiRequestDao()`. Round-trip fidelity is the point of a backup file |

    Also remove the `TODO #50 (Workspace system).` marker at `:17`.

    *Tests:* new `app/src/test/java/com/example/data/export/WorkspaceExporterTest.kt` — export JSON contains the documented top-level keys and `appVersion` matches `BuildConfig.VERSION_NAME`; `importWorkspace` on that JSON reports matching counts **including a non-zero `requestsImported`**; malformed JSON produces errors rather than throwing.

14. **[after 1-13] Exhaustive marker sweep across this scope's files**

    The requirement is that **every** stub marker in this scope's files is gone by the end — implemented or the feature removed — not just the five labels called out in task 10. Re-grepped across `ui/screens/{HomeScreen,FlowScreen,NotesScreen,ToolsScreen,ToolWorkspaceScreen,MusicScreen,ChatScreen}.kt`, `MainActivity.kt`, `flow/{FlowExecutor,FlowSerializer}.kt`, `data/repository/{ChatRepository,ToolsRepository,AppRepositories}.kt`, `data/models/AppModels.kt`, `data/export/WorkspaceExporter.kt`, `data/search/TrafficSearch.kt`. **13 real marker sites**, each already owned by a task above:

    | Marker | Owned by |
    |---|---|
    | `FlowScreen.kt:127` `// Fix #25 (TODO): Flow export/import via FlowSerializer` | task 2 |
    | `FlowScreen.kt:347` KDoc `Implements TODO items #23, #24, #32, #33, #34.` — a reference to *completed* items, but it still trips the final grep; reword to "Implements items #23…" | task 2 |
    | `NotesScreen.kt:121` `// Fix #18 (TODO): Export note as MD or HTML file` | task 6 |
    | `ToolsScreen.kt:124` `// Fix #37 (TODO): "Create Custom Tool" card…` | task 7 |
    | `ToolWorkspaceScreen.kt:158` `else -> PlaceholderWorkspace(tool.title)` | task 7 |
    | `ToolWorkspaceScreen.kt:705` `// Placeholder`, `:709` `private fun PlaceholderWorkspace(…)`, `:714` `Text("$title — Coming Soon", …)` | task 7 (delete the function) |
    | `MusicScreen.kt:289` `// Fix #35 (TODO): swipe-to-remove with confirmation` | task 8 |
    | `MainActivity.kt:122, 214, 345` `Fix #22`/`Fix #43 (TODO)` labels; `:131` the dead `sendBroadcast` block itself | task 5 |
    | `AppRepositories.kt:797`, `AppModels.kt:392` `Fix #30 (TODO): removed stale runtime flags` — labels describing completed removals | tasks 4 / 8 |
    | `ChatRepository.kt:17`, `ChatScreen.kt:55` `* TODO #44 (Chats section).` | task 12 |
    | `WorkspaceExporter.kt:17` `* TODO #50 (Workspace system).` | task 13 |

    **Provisional-language sweep** (`grep -rn "For now\|for now"` across all of `app/src/main/java/com/example/`) — exactly two hits:

    - `interception/CaptureEngine.kt:544` `// For now, log + persist with a tag so user knows it was a redirect candidate.` inside the `RuleAction.REDIRECT` branch (`:542-552`), which does real work (it rewrites `request.url` and `request.host` from `matchedRule.replacementValue`). Provisional wording on working code — **reword during task 10**, same file, same surgical pass.
    - `interception/http/HttpParser.kt:196` `"CONNECT" -> HttpMethod.WS // CONNECT is tunnelling, map to WS for now` — belongs to the sibling CONNECT/decryption scope. **Not touched here**, recorded so it is not assumed missed.

    **Benign matches that must NOT be edited** — Compose `placeholder =` text-field parameters, not stub markers: `HomeScreen.kt:187`; `NotesScreen.kt:218, 266, 301`; `ToolsScreen.kt:98, 187, 212, 220, 228, 229`; `ToolWorkspaceScreen.kt:216, 357, 399, 456, 588, 606, 648`; `ChatScreen.kt:177`. Also benign descriptive prose: `AppRepositories.kt:549` ("empty placeholder request so the API Testr screen opens safely") and `AppRepositories.kt:852` ("this can never silently no-op").

    **Files already clean, zero markers:** `flow/FlowExecutor.kt`, `flow/FlowSerializer.kt`, `data/repository/ToolsRepository.kt`, `data/search/TrafficSearch.kt`.

    *Tests:* `grep -rnE "TODO|FIXME|XXX|HACK|Coming Soon|not implemented" app/src/main/java/com/example --include=*.kt` returns zero hits in this scope's files; `grep -rn "PlaceholderWorkspace" …` returns nothing; all three Gradle commands stay green.

### Testing

**Per task:** each task above names its own test file and cases. All new tests go under `app/src/test/java/com/example/`, matching the existing style in `interception/CaptureEngineParserTests.kt` (plain JUnit 4, `org.junit.Assert.*`, backtick-quoted test names, synthetic inputs and asserted outputs). `isReturnDefaultValues = true` is already configured, so `android.util.Log` calls inside tested code will not throw.

**Per-task verification (run all three, in order):**

```
./gradlew :app:compileDebugKotlin --no-configuration-cache
./gradlew :app:testDebugUnitTest  --no-configuration-cache
./gradlew :app:assembleDebug      --no-configuration-cache
```

`:app:assembleDebug` is green on `0db606c` and must stay green after every task. A local `debug.keystore` is required before the first build (`keytool -genkey -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`) — it is gitignored, per `/memory/testing/requestr/known-issues.md`.

**Integration verification after all tasks:**

1. **Exhaustive marker sweep (task 14).** `grep -rnE "TODO|FIXME|XXX|HACK|Coming Soon|not implemented" app/src/main/java/com/example --include=*.kt` returns zero hits in this scope's files. All 13 catalogued sites gone; the two `For now` sites resolved (`CaptureEngine.kt:544` reworded, `HttpParser.kt:196` left to the sibling scope). The 24 Compose `placeholder =` matches listed in task 14 are the only permitted survivors of a `placeholder`-only grep.
2. `grep -rn "onClick = { /\*\|onClick = {}" app/src/main/java/com/example --include=*.kt` returns nothing, and `grep -rn "PlaceholderWorkspace" …` returns nothing.
3. Manual on-device pass on a Redroid container (`/memory/knowledge/setup-learnings/android.md`; VPN tiers need `adb root && adb shell "ln -s /dev/net/tun /dev/tun"`, re-applied per container): type in Home search and confirm real hits that navigate; export a flow, kill the app, import it back and confirm nodes, ports, and connections survive; run a flow with a condition node and confirm only the matching branch executes and the banner colour matches the outcome; open a Markdown note preview and confirm formatting renders; create a custom tool and confirm it appears in the catalogue immediately and opens a working workspace, not "Coming Soon"; fire each advertised `requestr://` link with `adb shell am start -a android.intent.action.VIEW -d "requestr://…"` both cold and while the app is open, and confirm the action fires, not just navigation.
4. Confirm the interception baseline is unregressed: start a capture, toggle Request/Response logging in Settings, and confirm the persisted rows honour the toggles (task 9).
5. Persistence pass: write a note, force-stop the app, reopen and confirm it survived; delete every note, restart, and confirm they are **not** resurrected (task 11). Save a chat API key, force-stop, reopen and confirm the provider still works while the on-disk DataStore value is not the plaintext key (task 12). Export a workspace, wipe rules, re-import and confirm the reported counts include a non-zero `requestsImported` (task 13).
