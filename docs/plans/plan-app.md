# plan-app — App-Layer Honesty Pass (requestr, branch `backup` @ `0db606c`)

## Scope and method

This plan covers the **app layer**: Home global search, Flow (canvas/executor/import/export), Notes, Tools, Music, Chats, deep links, keyboard shortcuts, and the app-side reads of capture settings.

**Out of scope** (owned by sibling plans): tun/packet/QUIC transport core and the `VPN_TUN` relay gap; Room schema version number and migrations; CONNECT/decryption and the MV3/SDK surfaces; runtime instrumentation and the browser bridge. Where this plan needs a schema change it is phrased as a **table-and-column request only** (H1); the version number and `Migration` objects belong to the transport scope.

**Every finding below was re-verified by reading the file on `backup` @ `0db606c`.** `AUDIT_REPORT.md` is a stale snapshot of `main` @ v3.4 and says so in its own addendum (lines 378-402); about half its app-layer claims are already fixed, and several real defects it never mentions are still live. Each item states **STILL BROKEN** with a quote, or is listed under "already fixed — excluded" with a quote.

**Verification for every item** (all three, in order):

```
./gradlew :app:compileDebugKotlin --no-configuration-cache
./gradlew :app:testDebugUnitTest  --no-configuration-cache
./gradlew :app:assembleDebug      --no-configuration-cache
```

`:app:assembleDebug` is BUILD SUCCESSFUL on `0db606c` and must stay green. A local `debug.keystore` is needed before the first build (gitignored; see `/memory/testing/requestr/known-issues.md`). New tests go under `app/src/test/java/com/example/`, plain JUnit 4 in the style of `interception/CaptureEngineParserTests.kt`. `app/build.gradle.kts` already sets `testOptions.unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true }`, so `android.util.Log` calls in tested code will not throw.

---

## Batch A — `ui/screens/HomeScreen.kt` (596 lines)

### A1. Make the global search bar real — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/ui/screens/HomeScreen.kt`, new `app/src/main/java/com/example/data/search/GlobalSearch.kt`

**Evidence.** `grep -n "searchQuery" HomeScreen.kt` returns exactly four lines — 98, 185, 186, 188. Line 98:

```kotlin
var searchQuery by remember { mutableStateOf("") }
```

Lines 184-189:

```kotlin
SearchInputField(
    query = searchQuery,
    onQueryChange = { searchQuery = it },
    placeholder = "Search APIs, notes, chats, tools...",
    onClear = { searchQuery = "" }
)
```

There is no filter, no result list, and no navigation. The user types and nothing happens.

**Change.** Add `object GlobalSearch` in `data/search/` alongside the existing `TrafficSearch.kt`, with a pure entry point:

```
fun search(query: String, apis: List<ApiRequestItem>, notes: List<NoteItem>,
           tools: List<ToolItem>, flows: List<FlowNodeItem>): List<GlobalSearchHit>
```

and a `sealed interface GlobalSearchHit` carrying the navigation target. Match case-insensitively on user-visible fields: api `name` + `url`, note `title` + `content`, tool `title` + `description`, flow node `title`. Mirror the substring style already used at `ToolsScreen.kt:74-85`.

In `HomeScreen`, when `searchQuery.isNotBlank()`, replace the card grid with a results list grouped by kind. All sources are already collected in the composable (`HomeScreen.kt:84-96`): `savedApis`, `notes`, `flows`, plus `settings` and `capturedCount`. Dispatch through the callbacks the composable already declares (`HomeScreen.kt:76-83`): `onOpenApiRequest`, `onOpenNote`, `onNavigateToSection`, `onOpenSettings`. Tools and flow nodes route via `onNavigateToSection("tools")` / `("flow")` — `HomeScreen` has no tool-id callback. Render a real empty state ("No matches for …"), not a blank list. Remove `chats` from the placeholder text unless C2 lands, so the placeholder doesn't promise a source that isn't searched.

Also fix the dishonest badge at `HomeScreen.kt:468`:

```kotlin
badge = "35+ tools",
```

`ToolsRepository.getToolsCatalog()` (`ToolsRepository.kt:63-83`) builds **19** built-ins. Make the badge read the real catalogue size.

**Tests.** New `app/src/test/java/com/example/data/search/GlobalSearchTest.kt`: match by api name; match by api url; match inside note body; match by tool description; case-insensitivity; blank query → empty; no-match → empty; a query hitting several kinds returns hits from each.

---

## Batch B — `ui/screens/FlowScreen.kt` (797) + `flow/` + `AppRepositories.kt` (1033)

### B1. Flow import is a fake button — **STILL BROKEN**

**File:** `app/src/main/java/com/example/ui/screens/FlowScreen.kt`

**Evidence.** Lines 171-187:

```kotlin
// Fix #25: Import flow from JSON (share sheet)
IconButton(onClick = {
    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val importIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "application/json"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            flowContext.startActivity(importIntent)
        }
    }
}) { … contentDescription = "Import Flow" … }
```

No result contract, no stream read, no parse. `FlowSerializer.importFromJson` has **zero call sites** app-wide — `grep -rn "importFromJson" app/src/main` matches only `data/import/HarImporter.kt:30/94/103`, an unrelated same-named method on a different object.

**Change.** Replace with `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`, following the existing pattern at `OnboardingScreen.kt:130-140` and `InterceptScreen.kt:226`. On result, read the stream, call `FlowSerializer.importFromJson(json)`, and on non-null push nodes + connections through the new `FlowRepository.replaceGraph(...)` from B5 so Room and the in-memory `StateFlow` agree. Report both success and failure — a null parse must say so.

### B2. Node editing is a dead end — **STILL BROKEN**

**File:** `app/src/main/java/com/example/ui/screens/FlowScreen.kt`

**Evidence.** `grep -n "nodeForEdit" FlowScreen.kt` → three lines: `116` (declaration `var nodeForEdit by remember { mutableStateOf<FlowNodeItem?>(null) }`), `244` (`onNodeEdit = { nodeForEdit = it }`), `306` (`IconButton(onClick = { nodeForEdit = node })`). It is written twice and **never read**. So context-menu Edit (`:508-534`) and the Sequence-List edit button both do nothing visible.

**Change.** Add an `EditFlowNodeBottomSheet` mirroring the existing `AddFlowNodeBottomSheet` (`:717-797`), with title, type, and `configData` fields, wired to the new `FlowRepository.updateNode(node)` from B5.

### B3. Run banner always claims success — **STILL BROKEN**

**File:** `app/src/main/java/com/example/ui/screens/FlowScreen.kt`

**Evidence.** Lines 215-231 render the banner unconditionally in success styling:

```kotlin
backgroundColor = Color(0xFF10B981).copy(alpha = 0.15f),
borderColor = Color(0xFF10B981).copy(alpha = 0.5f)
…
Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = Color(0xFF10B981), …)
```

`executeFlow()` returns a bare `String` (`AppRepositories.kt:274`), so the screen has no way to know the run failed.

**Change.** Have `executeFlow()` return a success flag (B5d) and colour/icon the banner from it.

### B4. Fit-to-view is hardcoded, not fitted — **STILL BROKEN (polish)**

**File:** `app/src/main/java/com/example/ui/screens/FlowScreen.kt`

**Evidence.** Lines 390-417 compute a real bounding box and then discard it:

```kotlin
scale = 0.8f
offsetX = -(minX + contentW / 2) * scale + 300f
offsetY = -(minY + contentH / 2) * scale + 300f
```

`0.8f` and `300f` are viewport-independent constants; content is not actually fitted to the measured canvas.

**Change.** Capture canvas size via `onSizeChanged` and derive `scale` and offsets from the real bounding box and viewport.

Note while in this file: nodes are positioned with a hand-rolled transform (`.offset { IntOffset((node.x.dp.roundToPx() * scale + offsetX).toInt(), …) }`, `(12.sp.value * scale).sp`, `.width((180.dp.value * scale).dp)`) that can drift from the Canvas `graphicsLayer` transform at `:423-428`, and drag math divides by `scale / 2.5f` at `:561-562` — a magic constant. Worth aligning while touching the file, but not a correctness blocker.

### B5. Flow repository correctness — **STILL BROKEN (four distinct defects)**

**File:** `app/src/main/java/com/example/data/repository/AppRepositories.kt`

**(a) Ports lost across restart.** Lines 256-261:

```kotlin
private fun com.example.data.local.FlowNodeEntity.toFlowNodeItem() = FlowNodeItem(
    id = id, title = label,
    type = try { FlowNodeType.valueOf(type) } catch (_: Exception) { FlowNodeType.TEXT },
    x = positionX, y = positionY, configData = configData,
    inputPorts = emptyList(), outputPorts = emptyList() // Ports derived from type at runtime
)
```

Nothing derives them. Derive in the mapper from `FlowNodeType` (`AppModels.kt:331-340`): `CONDITION` → `outputPorts = listOf("true","false")`, everything else `listOf("out")`; inputs `listOf("in")`.

**(b) Connection ids mismatch across three formats.** `toEntity()` at `:263-267` builds `id = "${fromNodeId}_${fromPort}_${toNodeId}_${toPort}"`; `toFlowConnectionItem()` at `:269-272` omits `id` entirely, so the item takes the `UUID.randomUUID()` default from `FlowConnectionItem` (`AppModels.kt:355`); and `FlowScreen.kt:245-260` builds a third format, `"${fromId}_out_${toId}_in"`. `removeConnection(id)` filters `_connections` by `it.id`, so removing a Room-loaded connection can silently fail. Centralise one id function, use it in all three places, and map `id` through in `toFlowConnectionItem()`.

**(c) Missing repository API for B1/B2.** Add `replaceGraph(nodes, connections)` (clear both Room tables, insert, update both `StateFlow`s) and `updateNode(node)` (upsert + in-memory replace), following the existing `addNode`/`persistNode` style at `:195-207`.

**(d) `executeFlow()` hides failure.** At `:274-310` it returns a human-readable multi-line `String`. Return `data class FlowRunSummary(val success: Boolean, val message: String)` (or expose `FlowResult` directly) and update the single call site at `FlowScreen.kt:191-197`.

**(e) `FlowSerializer.exportToCompactJson` is misnamed** — it delegates straight to `exportToJson` and has no call sites. Make it genuinely compact (`toString()` not `toString(2)`) or delete it.

**(f) `ApiCollectionsRepository.saveRequest` claims a rollback it never performs.** Lines 606-609:

```kotlin
} catch (e: Exception) {
    // DB write failed — revert optimistic update by re-reading from Room
    android.util.Log.w("ApiCollections", "Save failed, reverting: ${e.message}")
}
```

The comment says "revert"; nothing reverts. Either re-read from Room, or reword to state that the optimistic value stands until the `observeAll()` collector corrects it.

**(g) Remove stale labels** at `AppRepositories.kt:797` (`// Fix #30 (TODO): removed stale runtime flags …`) and `AppModels.kt:392` (`// Fix #30 (TODO): Removed isVpnActive, …`) — both describe completed removals.

### B6. Flow conditions cannot branch and don't really evaluate — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/flow/FlowExecutor.kt`, new `app/src/main/java/com/example/flow/ConditionEvaluator.kt`

**(a) No branch selection.** `FlowExecutor.kt:64-66`:

```kotlin
// Enqueue downstream nodes
adjacency[nodeId]?.forEach { nextId ->
    if (nextId !in visited) queue.addLast(nextId)
}
```

and `buildAdjacency` at `:200-206`:

```kotlin
private fun buildAdjacency(connections: List<FlowConnectionItem>): Map<String, List<String>> {
    val adj = mutableMapOf<String, MutableList<String>>()
    connections.forEach { conn -> adj.getOrPut(conn.fromNodeId) { mutableListOf() }.add(conn.toNodeId) }
    return adj
}
```

`fromPort` is discarded, so true/false routing is structurally impossible — a condition node's result is computed and then ignored. Rebuild adjacency as `Map<String, List<Pair<fromPort, toNodeId>>>`; after a `CONDITION` node, follow only edges whose `fromPort` matches the boolean result, falling back to `"out"` edges when a graph has no labelled ports so existing saved flows keep working.

**(b) Condition logic is substring guesswork.** `:140-168` lowercases `configData` and pattern-matches. Two concrete bugs: the `not empty` branch at `:155-157` is **unreachable** because `config.contains("empty")` at `:152` matches first, and the fallthrough is:

```kotlin
else -> "true"
```

so any unrecognised config silently passes. Extract a pure `object ConditionEvaluator` with `fun evaluate(config: String, input: String): Boolean` parsing `<left> <op> <right>`, ops `==`, `!=`, `contains`, `notContains`, `matches`, plus unary `empty` / `notEmpty`. Resolve `left` as `status` (extract the HTTP status from the input), `body` (whole input), or a JSON key via the already-shared `flow/JsonFieldExtractor.extractByKey` (reused today by `executeTransformNode` and by `ApiCollectionsRepository.runChainExtractions`). An unparseable config returns `false` **and** is reported as an error — never silently true.

**(c) Hidden third-party default.** `executeApiNode` at `:102-123` substitutes `"https://httpbin.org/get"` when the node has no URL and hardcodes `User-Agent: requestr-flow/3.0`. A missing URL must be a real node error rather than a quiet request to someone else's host.

**Excluded — already fixed:** `executeNode` (`:85-99`) dispatches all eight `FlowNodeType` values (API, INTERCEPT_TRIGGER, TRANSFORM, CONDITION, TEXT, TOOL, OUTPUT, DELAY), so the audit's "only API node type is handled" is stale.

### B7. Remove the stale export label

`FlowScreen.kt:127` reads `// Fix #25 (TODO): Flow export/import via FlowSerializer`, but export at `:129-152` is fully real (`FlowSerializer.exportToJson` → `FileExporter.writeJson` → `FileExporter.shareFile`). Delete the marker once B1 lands.

**Tests for Batch B.**
- New `app/src/test/java/com/example/flow/ConditionEvaluatorTest.kt` — `status == 200` true and false; `body contains token`; `notEmpty` against `{}` and against real content; JSON-key left side; `matches` with valid and invalid regex; unparseable config → false.
- New `app/src/test/java/com/example/flow/FlowExecutorBranchingTest.kt` — graph `entry → condition → (true: A, false: B)` runs only A when true and only B when false; an unlabelled-port graph still reaches downstream nodes.
- New `app/src/test/java/com/example/flow/FlowSerializerRoundTripTest.kt` — export→import preserves node ids, types, positions, `configData`, and connection `fromPort`/`toPort`; malformed JSON returns null.
- New `app/src/test/java/com/example/data/repository/FlowMapperTest.kt` — `CONDITION` derives `true`/`false` output ports; connection id survives entity→item→entity unchanged.

---

## Batch C — `MainActivity.kt` (901) + `SettingsScreen.kt` (1858) + `ApiTestrScreen.kt` (1561)

### C1. Deep links: live delivery and the four advertised actions — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/MainActivity.kt`, `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`, new `app/src/main/java/com/example/DeeplinkRouter.kt`

**(a) The drain runs exactly once.** `onNewIntent` at `:120-127` enqueues into `companion object { val pendingDeeplinks = ConcurrentLinkedQueue<Uri>() }`, and the consumer at `:345-358` is:

```kotlin
LaunchedEffect(Unit) {
    val initialUri = activity?.intent?.data
    if (initialUri?.scheme == "requestr") handleRequestrDeeplink(navController, initialUri)
    while (true) {
        val uri = MainActivity.pendingDeeplinks.poll() ?: break
        handleRequestrDeeplink(navController, uri)
    }
}
```

The effect body runs once and `break`s on the first empty poll. Any link arriving **while the app is open** — precisely what the `Fix #22` comment at `:122` promises — is enqueued and never handled. Replace the queue with a `MutableSharedFlow<Uri>` (`tryEmit` from `onNewIntent`) collected in a `LaunchedEffect(navController)`.

**(b) The advertised actions don't exist.** `SettingsScreen.kt:646-651`:

```kotlin
val deepLinks = listOf(
    "requestr://intercept/toggle" to "Toggle capture state",
    "requestr://api/run?id=req-1" to "Trigger API payload",
    "requestr://flow/execute?id=flow-1" to "Run n8n Automation Chain",
    "requestr://note/create?title=Log" to "New quick note"
)
```

`handleRequestrDeeplink` (`:219-260`) only maps hosts to routes — none of toggle / run / execute / create is implemented. And `"flow/execute" -> Screen.Flow.route` at `:234` is unreachable dead code: a URI host can never contain `/`. The `uri.path` fallback maps `"flow"` to the Flow route only.

Implement the four actions from `uri.host` + `uri.pathSegments` + query params, navigating **and** acting: `intercept/toggle` → route to Intercept and drive start/stop through `CaptureController` (`ui/screens/CaptureController.kt:107` `requestStart()` / `:161` `requestStop()`) so the same pre-flight gate applies as the FAB; `api/run?id=` → `ApiCollectionsRepository.setActiveRequest` then `ApiTestrRepository.executeRequest`; `flow/execute?id=` → `FlowRepository.executeFlow()`; `note/create?title=` → `NotesRepository.saveNote` then open Notes. Delete the unreachable `"flow/execute"` host branch. Replace the silent swallow at `:256-259` (`catch (_: Exception) { /* Navigation not ready yet — ignore, deep link is best-effort */ }`) with a logged failure.

If an action can't be implemented cleanly (e.g. `intercept/toggle` needs an Activity-scoped consent launcher a top-level function can't reach), **remove that row from the Settings list** rather than leave it advertised.

Extract the URI→action mapping into a pure `object DeeplinkRouter` returning a `sealed interface DeeplinkAction`, so it is unit-testable without Compose.

**(c) Keyboard shortcuts are no-ops.** `:131-155`:

```kotlin
ctrl && keyCode == KeyEvent.KEYCODE_ENTER -> {
    // Broadcast send-request intent — ApiTestrScreen picks it up
    sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))
    true
}
ctrl && shift && keyCode == KeyEvent.KEYCODE_N -> {
    sendBroadcast(Intent("com.example.requestr.NEW_REQUEST"))
    true
}
```

`grep -rn "registerReceiver\|BroadcastReceiver" app/src/main` returns **nothing**, and the only hits for `SEND_REQUEST`/`NEW_REQUEST` are these two `sendBroadcast` calls. Nothing picks them up; only `KEYCODE_ESCAPE` works. Replace with an in-process `object AppShortcuts { val events: SharedFlow<ShortcutEvent> }` collected by `ApiTestrScreen`, which already has a `coroutineScope` and `SnackbarHostState` (`ApiTestrScreen.kt:139-142`) to trigger send and new-request and confirm to the user.

**(d) Remove stale labels** at `MainActivity.kt:122`, `:214`, `:345`.

**Tests.** New `app/src/test/java/com/example/DeeplinkRouterTest.kt` — each host maps to the right route; `api/run?id=x` yields a RunApi action carrying `x`; `flow/execute` yields ExecuteFlow; `note/create?title=Log` yields CreateNote with the title; unknown host returns null; path-form fallback (`requestr:///notes`) still resolves.

### C2. CA auto-promote label + missing failure feedback — **already implemented, comment stale**

**File:** `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`

`:1246` reads `// Fix #21 (TODO): auto-promote to system store when root/Shizuku`, but `:1249-1271` is a working implementation: `autoPromoteTriggered` guard, `LaunchedEffect(capabilities.root, capabilities.shizuku, capabilities.ca)`, `runCatching { CaWhitelistService.promoteToSystem(settingsContext) }.onSuccess { … Toast "CA auto-promoted to system trust store" }`, then `CapabilityProbe.probeAll`. Plus a manual "B11" fallback button and a "Regenerate CA" button. **Delete the `(TODO)` label** and add an `onFailure` branch — today a failed promotion produces no user feedback.

### C3. `ApiTestrScreen` right menu — **audit claims are already fixed; verified contents**

`ApiTestrScreen.kt:1012-1080` contains exactly six items, all real: `</> Code Snippet`, `📥 Import cURL` (modal at `:964-1010` parses via `ApiTestrRepository.parseCurl`), `📋 View Saved`, `📡 Send to Intercept`, `🧹 Clear Fields`, `📁 Export cURL as File` (`:1052-1080`, real `FileExporter.writeCurl` + `shareFile`). So audit **#13** (stub items) and **#14** (clipboard-only export) are both **already fixed** — `grep "Cookie|cookieJar|Pin\b"` in this file returns nothing.

Two small residual honesty fixes while in the file: the cURL export launches on a bare `CoroutineScope(Dispatchers.IO)` rather than a lifecycle scope, and failure only does `Log.w("ApiTestr", "Export cURL failed: …")` with no snackbar — the screen already has one.

Whether to **add** Cookie Jar / Make Tool / Add to Chat / Pin is net-new scope — see Blocking question 3.

---

## Batch D — `ui/screens/NotesScreen.kt` (410)

### D1. Markdown and checklist rendering — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/ui/screens/NotesScreen.kt`, new `app/src/main/java/com/example/ui/text/MarkdownRenderer.kt`

**Evidence.** `:243-256`, with the admission in-code:

```kotlin
// Fix 16 (AUDIT_REPORT §11.2): HTML preview now renders via
// HtmlCompat instead of showing raw tags as monospace text.
// Markdown shows as-is (monospace) since no MD render lib exists.
if (editFormat == NoteFormatType.HTML) {
    val annotated = remember(editContent) {
        val spanned = HtmlCompat.fromHtml(editContent, HtmlCompat.FROM_HTML_MODE_COMPACT)
        AnnotatedString(spanned.toString())
    }
    Text(annotated, …)
} else {
    Text(editContent, …, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}
```

Two problems: Markdown is never rendered, and the HTML path calls `.toString()` on the `Spanned`, discarding every span — so "HTML rendering" is really tag-stripping. `CHECKLIST` (a declared `NoteFormatType`, `AppModels.kt:309-314`) has no rendering at all. No markdown dependency exists in `app/build.gradle.kts`.

**Change.** Hand-roll `object MarkdownRenderer { fun render(source: String): AnnotatedString }` supporting ATX headings, `**bold**`, `*italic*`, `` `code` ``, fenced code blocks, unordered lists, and links as styled text. Convert the HTML path to walk the `Spanned`'s spans into `AnnotatedString` styles instead of flattening. Render `CHECKLIST` notes as real checkbox rows parsed from `- [ ]` / `- [x]`, writing toggles back through `NotesRepository.saveNote`.

### D2. Export label is stale; failure is silent

`:121` reads `// Fix #18 (TODO): Export note as MD or HTML file`, but `:122-161` is a real implementation: format-specific ext/mime (HTML→`.html`/`text/html`, MARKDOWN→`.md`, else `.txt`), filename sanitised via `Regex("[^a-zA-Z0-9._-]")`, then `MediaStoreExporter.writeText(...)` + `FileExporter.shareFile(...)` with the documented scoped-storage rationale. **Delete the label.** Two real defects remain: `?: return@launch` swallows write failure with no user feedback, and the `when (editFormat)` computing `text` returns `editContent` in every branch — dead code.

**Tests.** New `app/src/test/java/com/example/ui/text/MarkdownRendererTest.kt` — headings get larger/bold spans; `**bold**` / `*italic*` produce the right `SpanStyle` ranges with markers stripped; inline and fenced code use monospace; list items are prefixed; unmatched markers stay literal. New `MarkdownChecklistTest.kt` — `- [x]` / `- [ ]` parse to checked/unchecked and toggling round-trips back into the source text.

---

## Batch E — `ui/screens/ToolsScreen.kt` (365) + `ToolWorkspaceScreen.kt` (779) + `ToolsRepository.kt` (416)

### E1. Every custom tool dead-ends in "Coming Soon" — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/ui/screens/ToolWorkspaceScreen.kt`, `app/src/main/java/com/example/data/repository/ToolsRepository.kt`

**Evidence.** `ToolWorkspaceScreen.kt:124-156` dispatches 19 built-in ids by `when (tool.id)`, then `:157`:

```kotlin
else -> PlaceholderWorkspace(tool.title)
```

and `:709-717`:

```kotlin
Text("🚧", fontSize = 40.sp)
Text("$title — Coming Soon", …)
```

`addCustomTool` builds ids as `"custom-${name.lowercase().replace(Regex("[^a-z0-9]"), "-")}"` (`ToolsScreen.kt`, create sheet), so **every** user-created custom tool lands in the placeholder — even though `ToolsRepository.executeToolFunction(toolId, input)` at `:350` already routes `customTools[toolId]` to `executeCustomTool` (`:405-415`, real regex-replace and template substitution).

**Change.** Add a `CustomToolWorkspace` (input field → `executeToolFunction(tool.id, input)` → `outputBlock`), reusing the existing shared helpers `modeRow`, `outputBlock`, `copyToClipboard`. **Delete `PlaceholderWorkspace`** so no id can silently dead-end again.

**Excluded — already fixed:** the audit's "all 18 tools lack a dedicated workspace" is stale. The catalogue has 19 built-ins and `:124-156` covers every one of them.

### E2. A newly created custom tool doesn't appear — **STILL BROKEN**

**File:** `app/src/main/java/com/example/ui/screens/ToolsScreen.kt`

`:74` is a plain call, `val allTools = ToolsRepository.getToolsCatalog()`, and `filteredTools = remember(searchQuery, selectedCategory) { allTools.filter { … } }` does not key on the catalogue — so after creating a tool the list is unchanged until some unrelated recomposition. Expose `ToolsRepository.catalog: StateFlow<List<ToolItem>>` backed by the custom-tool map, collect it in `ToolsScreen`, and key the `remember` on it.

### E3. Custom tools round-trip through display text — **STILL BROKEN**

**File:** `app/src/main/java/com/example/data/repository/ToolsRepository.kt`

`:393-403`:

```kotlin
fun addCustomTool(item: ToolItem) {
    val customTool = CustomTool(
        id = item.id,
        name = item.title,
        operation = if (item.description.startsWith("Regex:")) "regex-replace" else "template",
        pattern = item.description.substringAfter(": ").substringBefore(" → "),
        replacement = if (item.description.contains("→")) item.description.substringAfter("→ ") else "",
    )
    customTools[customTool.id] = customTool
}
```

It reverse-parses the human-readable description that `getToolsCatalog()` (`:85-97`) generated. Any wording change silently corrupts the tool. Change the signature to take the structured fields directly and build the description for display only. Update the create sheet at `ToolsScreen.kt:164-256`.

### E4. Remove the stale label

`ToolsScreen.kt:124` reads `// Fix #37 (TODO): "Create Custom Tool" card when Custom category is selected`, but the card (`:125-160`) and the `ModalBottomSheet` (`:164-256`, name field, `regex-replace`/`template` chips, pattern + replacement, Create) are real. Delete the label.

**Tests.** New `app/src/test/java/com/example/data/repository/ToolsRepositoryCustomToolTest.kt` — structured `addCustomTool` then `executeToolFunction` applies a regex replace and a template substitution; an invalid regex returns the error JSON instead of throwing; the tool appears in `catalog`; the display description is derived, not parsed back.

---

## Batch F — `ui/screens/MusicScreen.kt` (352) + `AppModels.kt` + `AppRepositories.kt`

### F1. Two labelled no-op buttons — **STILL BROKEN**

**File:** `app/src/main/java/com/example/ui/screens/MusicScreen.kt`

`:115`:

```kotlin
IconButton(onClick = { /* Refresh local tracks */ }) {
    Icon(imageVector = Icons.Default.MusicNote, contentDescription = "Scan Audio Files", …)
}
```

`:276`:

```kotlin
Button(onClick = { /* Scan Audio */ }, …) { … Text("Import MP3", …) }
```

Both are empty lambdas — these are the only two matches for empty `onClick` lambdas in the whole app (`grep -rn "onClick = { /\*"`). `MusicRepository.loadLocalLibrary(context)` exists (`AppRepositories.kt:338-341`) and is called only from `LaunchedEffect(Unit)` at `MusicScreen.kt:82-86`.

**Change.** Wire the header icon to a real re-scan with progress and a result message including the track count. For "Import MP3", either open a document picker that adds the chosen audio file, or **remove the button** — a labelled button that does nothing is exactly what the project's stub-or-remove standard forbids (see the documented precedents at `SettingsScreen.kt:155-161`, `:245-251`, `:285-292`).

### F2. Swipe-to-remove promises a confirmation it never shows — **STILL BROKEN (partial)**

`:289` reads `// Fix #35 (TODO): swipe-to-remove with confirmation`; `:290-316` really removes:

```kotlin
rememberSwipeToDismissBoxState(confirmValueChange = { value ->
    if (value == SwipeToDismissBoxValue.EndToStart) { MusicRepository.removeTrack(track.id); true } else false
})
```

There is no confirmation dialog, so a stray swipe destroys a row silently. Add one (reuse `ConfirmActionDialog`, used at `InterceptScreen.kt:629`), returning `false` from `confirmValueChange` until confirmed, then delete the stale label. Drag-reorder does not exist and `MusicRepository` has no reorder function (verified fun list: `loadLocalLibrary`, `pickRandomTrack`, `togglePlayPause`, `nextTrack`, `prevTrack`, `seekTo`, `selectTrack`, `stopMusic`, `removeTrack`, `playCurrentTrack`, `releasePlayerSync`) — out of scope unless requested.

### F3. Three fictional music sources — **STILL BROKEN (fabricated content)**

**Files:** `app/src/main/java/com/example/data/models/AppModels.kt`, `app/src/main/java/com/example/data/repository/AppRepositories.kt`, `app/src/main/java/com/example/ui/screens/MusicScreen.kt`

`AppModels.kt:362-366`:

```kotlin
enum class MusicSourceType(val label: String) {
    SYNTH_LOFI("requestr Synth Beats"),
    AMBIENT_CYBER("Cyber Cyberpunk Ambience"),
    CHILL_CODING("Chill Coding Terminal"),
    LOCAL("Local Storage")
}
```

`grep -rn "YouTube\|JioSaavn" app/src` → **zero hits**, so the audit's "Sources selector (Local/YouTube/JioSaavn)" is stale as written. The real defect is that only `LOCAL` is ever produced (`data/music/LocalMusicScanner.kt`), while three fabricated source labels exist. Delete the three unreachable values and update `MusicScreen.kt:173` (`currentTrack.source.label`) and the `MusicRepository` default track at `AppRepositories.kt:321-323`.

**Tests.** No new pure logic; verify with `:app:compileDebugKotlin` plus a grep confirming zero remaining references to the deleted enum values.

---

## Batch G — capture-settings plumbing (shared with the transport plan)

### G1. Capture settings unreadable in a service-only process — **STILL BROKEN**

**Files:** `app/src/main/java/com/example/data/prefs/SettingsDataStore.kt`, `app/src/main/java/com/example/interception/CaptureEngine.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/RequestrVpnService.kt`

**Evidence.** `CaptureEngine.kt:499`:

```kotlin
requestHeaders = if (isClient) { if (SettingsRepository.settings.value.requestLoggingEnabled) call.headers + metaHeaders else metaHeaders } else metaHeaders,
```

plus `:502`, `:503` (gRPC path) and `:602-605` (plaintext-HTTP path).

Important nuance: `SettingsRepository` **is** DataStore-backed (`AppRepositories.kt:741-785` collects `ctx.unifiedDataStore.data` into `_settings`), so this is not staleness within a process. The real risk is that `CaptureEngine` runs inside `RequestrVpnService`, and `SettingsRepository.init()` is called **only** from `MainActivity.onCreate()`. `initialized`/`appContext` are per-process, and the system can start or restart the service without the Activity — in which case `settings.value` returns the hardcoded `true` defaults from `UserSettingsState` (`AppModels.kt:427-429`) and silently ignores the user's persisted choice. `RequestrVpnService.kt:141` has the same shape: `val captureEnabled = SettingsRepository.settings.value.trafficCaptureEnabled`.

`SettingsDataStore` has no flows for these keys — its list at `:57-77` covers proxyPort … gatewayPort — although the keys exist in `UnifiedDataStore.kt:74-76` (`TRAFFIC_CAPTURE`, `REQUEST_LOGGING`, `RESPONSE_LOGGING`).

**Change.** Add `requestLogging`, `responseLogging`, `trafficCapture` as `Flow<Boolean>` in `SettingsDataStore`; read them in `RequestrVpnService.startCapture()` alongside the existing `printUndecoded` (`:139`), `dumpToFile` (`:140`), `enhancedMode` (`:145`); thread them into `PacketPump` → `CaptureEngine` as constructor vars exactly as `captureEnabled`/`enhancedMode` are today (`PacketPump.kt:47-51` and `:73`). Remove the direct `SettingsRepository` reads from `CaptureEngine`.

Deliberately additive. Coordinate ordering with the transport-core plan, which also owns these files.

**Tests.** No new pure logic; verify `CaptureEngine.kt` no longer references `SettingsRepository` (grep) and `:app:assembleDebug` stays green.

### G2. Stale TODO labels on already-real interception code

The user requires zero `TODO` comments left behind. All five verified as labels on working code:

- `CaptureEngine.kt:263` `// Fix #14 (TODO): Detect WebSocket upgrade handshake` — real: `WebSocketParser.looksLikeUpgrade` at `:265`, frame parsing at `:301-321`, `persistWsFrame` at `:662-688`.
- `CaptureEngine.kt:277` `// Fix #15 (TODO): If the response is SSE (text/event-stream), parse events` — `SseParser.looksLikeSse`/`parseEvents` are called at `:279-280`, **but the events are only `Log.i`'d and never persisted**, contradicting the comment's "persist the event stream as a tagged transaction". Either persist them the way `persistWsFrame` does, or reword to say SSE is detected and logged only.
- `CaptureEngine.kt:535` `// Fix #7 (TODO): Implement all rule actions, not just BLOCK.` — BLOCK / REDIRECT / MODIFY_REQUEST / MODIFY_RESPONSE / LOG_ONLY are all handled at `:537-580` via `RuleEnforcer`. Delete.
- `PacketPump.kt:168` `// Fix #16 (TODO): intercept DNS queries on UDP port 53.` — real: `DnsParser.parse` + `dnsCallback` at `:169-176`. Delete.
- `RequestrVpnService.kt:180` `// Fix #2 (TODO): Hot-reload app filter.` — real observer at `:178-198` (`combine(selectedPackages, appFilterMode).drop(1).collect { stopCapture(); delay(500); startCapture() }`). Delete the `(TODO)`; note that `startCapture()` re-registers the observer on every restart and `.drop(1)` is per-collector, so rapid filter changes can stack restarts — worth a guard.

---

## Batch H — unblocked by user decisions (2026-08-21)

All four questions are answered. Decisions recorded at the end of this document under "Resolved decisions".

### H1. Persist Notes, custom tools, and chat history to Room — **decided: Q1(a), new tables**

`NotesRepository` (`AppRepositories.kt:41-101`) holds 3 hardcoded seed notes (`n-1` MARKDOWN, `n-2` CHECKLIST, `n-3` HTML) and 4 seed folders in `MutableStateFlow`s; `saveNote`/`deleteNote`/`togglePin` only mutate memory. `ToolsRepository.customTools` is a `ConcurrentHashMap` (`:382`, commented "stored in-memory"). `ChatRepository._messages` is a `MutableStateFlow`. All three vanish on process death.

Decision Q1(a) applies: **new Room tables.** The schema request is below.

#### Schema request — 5 new tables (this plan does NOT pick a version number)

**The version number and the `Migration` objects are owned by the transport scope.** This plan requests tables and columns only. The transport plan is also replacing `.fallbackToDestructiveMigration(dropAllTables = true)` (`RequestrDatabase.kt:69`) with real migrations, so **these tables must arrive as a proper `Migration` with `CREATE TABLE` statements, never as a destructive wipe** — a wipe would destroy the user's existing captured traffic, saved requests, and flow graphs, all of which already persist today.

| New table | Columns | Backs |
|---|---|---|
| `notes` | `id` TEXT PK, `title` TEXT, `content` TEXT, `format` TEXT (`NoteFormatType` name), `folderId` TEXT nullable, `folderName` TEXT, `folderColorHex` INTEGER, `isPinned` INTEGER, `createdAt` INTEGER, `updatedAt` INTEGER; index on `isPinned` and `updatedAt` | `NotesRepository._notes` (`AppRepositories.kt:52-90`) |
| `note_folders` | `id` TEXT PK, `name` TEXT, `colorHex` INTEGER, `emoji` TEXT | `NotesRepository._folders` (`AppRepositories.kt:42-50`) |
| `custom_tools` | `id` TEXT PK, `name` TEXT, `operation` TEXT (`regex-replace`\|`template`), `pattern` TEXT, `replacement` TEXT, `createdAt` INTEGER | `ToolsRepository.customTools` (`ToolsRepository.kt:382`) |
| `chat_messages` | `id` TEXT PK, `role` TEXT (`user`\|`assistant`), `content` TEXT, `timestamp` INTEGER; index on `timestamp` | `ChatRepository._messages` (`ChatRepository.kt:25-26`) |
| `chat_providers` | `id` TEXT PK, `name` TEXT, `baseUrl` TEXT, `model` TEXT, `isActive` INTEGER, `createdAt` INTEGER — **no key column; see H2** | H2's provider list |

Column shapes mirror the existing models exactly: `NoteItem`/`NoteFolder` (`AppModels.kt:316-330`), `ToolsRepository.CustomTool` (`:384-390`), `ChatMessage` (`AppModels.kt:438-443`). Follow the entity conventions in `data/local/Entities.kt` (`@Entity(tableName = …, indices = [Index("…")])`, `@PrimaryKey`) and add five DAOs to `data/local/Daos.kt` in the shape of `SavedApiRequestDao` — `observeAll(): Flow<List<E>>`, `getAll()`, `upsert(e)`, `deleteById(id)`.

Note `chat_providers` deliberately has **no API-key column**. The key never touches Room or DataStore — see H2.

Wire each repository following the `ApiCollectionsRepository` pattern (`AppRepositories.kt:524-640`): `init(context)` collecting `dao().observeAll()` into the `StateFlow`, writes through the DAO. Register each new `init(context)` in `MainActivity.onCreate()` beside the existing ones (see the verified init order in the risk section).

**Seed-data behaviour.** `NotesRepository` currently ships 3 hardcoded seed notes and 4 seed folders as `MutableStateFlow` initial values. Once Room-backed, seed only when the table is genuinely empty — mirror `FlowRepository`'s `defaultNodes`/`defaultConnections` approach (`AppRepositories.kt:154-272`) so restarts don't duplicate rows and a user who deletes every note doesn't get them resurrected. `RequestrDatabase`'s own KDoc sets the precedent: "Ships with NO seed data by design … so the UI renders a genuine empty state instead of the fabricated sample rows that shipped in v2.1." Prefer no seed data at all for `notes`; if seeds are kept, gate them behind a one-time "seeded" DataStore flag.

**Ordering.** H1 lands **after** the transport plan's migration framework exists (it owns the version number and replaces the destructive-migration fallback). H1's repository wiring is otherwise independent of the rest of this plan.

**Tests.** Per repository: save/reload round-trip through an in-memory Room instance (`Room.inMemoryDatabaseBuilder`, Robolectric is already a `testImplementation` dependency); notes survive a simulated restart; deleting all notes leaves the table empty and does not re-seed; a custom tool survives restart and is still executable; chat history round-trips with role and ordering preserved.

### H2. Chat provider config and history — **decided: Q2(b), key in Keystore-backed storage only**

`ChatRepository.kt:27-29`:

```kotlin
var providerUrl: String = ""
var providerKey: String = ""
var providerModel: String = ""
```

Plain mutable fields — no persistence, no secure storage, and **no writer anywhere**: `grep "providerUrl|providerKey"` outside `ChatRepository.kt` matches only `ChatScreen.kt:95`, which *reads* `ChatRepository.providerUrl.isBlank()`. So `callProvider` (`:73-119`, a real OpenAI-compatible POST with a last-10-message window) is permanently unreachable and the app is always in local-mock mode via `generateLocalResponse` (`:125-147`, hardcoded keyword matching). The error text at `:117` even says "Check provider URL and API key in Settings" — and no such Settings UI exists. Worse, `ChatScreen.kt:95` reads a plain `var` inside composition, so the banner would never recompose even once a writer existed.

**Decided work.** Add a real provider-configuration UI — a new `SettingsSectionHeader("AI PROVIDER")` card in `SettingsScreen.kt` following the existing card style — with URL, model, and key fields. Replace the three plain `var`s with a `StateFlow<ChatProviderConfig>` on `ChatRepository` so `ChatScreen.kt:95`'s banner actually recomposes. Keep `generateLocalResponse` as a fallback but label it honestly ("Local mock — no provider configured"). Remove the stale `TODO #44` labels at `ChatRepository.kt:17` and `ChatScreen.kt:55`.

**Split of storage (per decision Q2b).**

| Field | Where it lives | Why |
|---|---|---|
| `name`, `baseUrl`, `model`, `isActive`, `createdAt` | `chat_providers` Room table (H1) | Non-secret config; queryable; consistent with every other persisted list in the app |
| `apiKey` | Keystore-protected encrypted blob only | Real third-party credential; must not sit in plaintext |

The `chat_providers` row holds the key's **alias**, never the key. Lookup is `provider.id` → Keystore alias → decrypt.

#### Storage mechanism — decided: raw `AndroidKeyStore`, zero new dependencies

The decision originally named `EncryptedSharedPreferences`. Verified against upstream: `androidx.security:security-crypto` and `EncryptedSharedPreferences` are **deprecated**; the Android API reference for `androidx.security.crypto` now points callers at plain `android.content.SharedPreferences` and there is no 1:1 official drop-in replacement. Google's guidance is DataStore for persistence + Android Keystore for key protection + Tink (or equivalent) for the encryption layer.

**Resolved 2026-08-21: use raw `AndroidKeyStore` + `Cipher("AES/GCM/NoPadding")`, zero new dependencies.** Shipping a deprecated artifact would be the same class of defect this pass exists to eliminate. **Do not add `androidx.security:security-crypto` or `tink-android`** — the table below is provenance for why, not a menu of live options.

Resolution facts checked directly against the configured repositories (`settings.gradle.kts` `dependencyResolutionManagement` = `google()`, `mavenCentral()`, `jitpack.io`, `api.xposed.info`, with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`):

| Artifact | Repo | Latest available | Status |
|---|---|---|---|
| *(none — Keystore-direct)* | platform | — | **CHOSEN.** `java.security.KeyStore` + `javax.crypto`, already available |
| `com.google.crypto.tink:tink-android` | `mavenCentral()` | **1.18.0** | Rejected — resolves and is maintained, but a new dependency for no benefit here |
| `androidx.security:security-crypto` | `google()` | **1.1.0** (Google Maven metadata, lastUpdated 2025-07-30) | Rejected — resolves, but deprecated upstream |

`gradle/libs.versions.toml` has **no** `security` or `crypto` entry today (`grep -n "security\|crypto\|tink"` → exit 1), so any of these is a brand-new dependency. `minSdk = 24` (`app/build.gradle.kts:18`), which is above the API 23 floor for `KeyGenParameterSpec` + `AndroidKeyStore` AES/GCM, so the zero-dependency path is available.

**Implementation — new `data/prefs/SecureKeyStore.kt`:**

- Generate/fetch an AES-256 key in `AndroidKeyStore` via `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)` with `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE`.
- `Cipher.getInstance("AES/GCM/NoPadding")` to encrypt the key string; store `iv + ciphertext` Base64-encoded in the existing unified DataStore under a new key.
- API surface: `suspend fun putSecret(alias: String, value: String)`, `suspend fun getSecret(alias: String): String?`, `suspend fun removeSecret(alias: String)`.
- No new Gradle dependency, and the app already calls `KeyStore.getInstance` in 7 places (`MitmProxyEngine.kt:638`, `CaWhitelistService.kt:71`, `CertificateAuthority.kt:64/129/175`, `CapabilityProbe.kt:162`, `RootModuleGenerator.kt:216`), so `java.security` is familiar ground in this codebase — though none of those use `AndroidKeyStore` for app-generated keys yet, so this is the first such use.

**Rejected alternatives, recorded so they are not revisited:** (b) `com.google.crypto.tink:tink-android:1.18.0` + an `AndroidKeystore`-wrapped keyset — one new dependency and a larger APK for no benefit at this scale; (c) `androidx.security:security-crypto:1.1.0` as originally named — deprecated upstream. Option (a) satisfies the requirement ("the key never sits in plaintext") with the smallest footprint and no new supply-chain surface.

**Guardrail for implementation.** Because this is hand-rolled crypto rather than a library, three things are non-negotiable: a fresh random IV per encryption (never reused across writes), the GCM auth tag verified on decrypt (a `AEADBadTagException` must surface as "key unreadable, re-enter it", not a silent empty string), and the plaintext key never written to `Log` at any level. The `SecureKeyStoreTest` case asserting the on-disk value differs from the plaintext is what proves the encryption is actually happening.

**Cross-scope handoff — no action here.** `CertificateAuthority.kt:42` is `private val keystorePassword = "requestr-ca-local".toCharArray()` — a hardcoded PKCS#12 password protecting the CA private key, persisted by `persist()` (`:127-136`) to `File(context.filesDir, KEYSTORE_FILE)`. Same class of problem as a plaintext API key. **Routed to the SDK/decryption scope (confirmed 2026-08-21); this plan takes no action on it.** Recorded so the finding survives between scopes — `SecureKeyStore` is the natural home for it once that scope picks it up.

Note: **`AUDIT_REPORT.md`'s entire §Chats section is stale** — `ChatScreen.kt` (235 lines) and `ChatRepository.kt` (148 lines) both exist and the chat route (`"chat"`) is registered in `rootRoutes` and the nav graph (`MainActivity.kt:618`). What is missing is provider management, config persistence, chat history persistence, and streaming.

**Tests.** New `app/src/test/java/com/example/data/repository/ChatProviderConfigTest.kt` — provider row round-trips through in-memory Room with no key column present; `isConfigured` is false for blank url or blank key; the request-body builder produces the expected OpenAI-compatible JSON with the last-10-message window. New `app/src/test/java/com/example/data/prefs/SecureKeyStoreTest.kt` (Robolectric, which ships an `AndroidKeyStore` shim) — `putSecret` then `getSecret` returns the original string; `removeSecret` makes it null; the persisted DataStore value is not equal to the plaintext key (proves it is actually encrypted).

**Ordering.** H2 lands after H1 (needs the `chat_providers` table). `SecureKeyStore.kt` itself is standalone and can be written in parallel.

### H3. Surface Workspace export/import — **decided: Q4, wire it**

`data/export/WorkspaceExporter.kt` (180 lines) is complete and real: `exportWorkspace` (`:25-89`) writes intercept rules, saved requests, and flow nodes/connections as JSON; `importWorkspace` (`:96-166`) reads rules, flow nodes, and flow connections back into Room. But `grep -rn "exportWorkspace\|importWorkspace" app/ sdk/ cli/` finds only their own declarations — **zero call sites**. It carries `TODO #50 (Workspace system).` at `:17`.

**Decided work — wire it.** Orphaned working code is the "looks done, isn't reachable" pattern this pass exists to end. Add an Export/Import row to the existing `SettingsSectionHeader("BACKUP & PRIVACY")` section (`SettingsScreen.kt:1393`):

- **Export** — call `WorkspaceExporter.exportWorkspace(context)`, write via `FileExporter.writeJson(context, json)` and hand off with `FileExporter.shareFile(context, target, "application/json", "requestr Workspace")`. This is the exact pattern already used for the traffic-log export at `SettingsScreen.kt:1434-1440`, including its `?: error("Failed to write … to Downloads/requestr/")` null guard. Never a raw `File` — scoped storage silently fails on API 29+.
- **Import** — an `ActivityResultContracts.OpenDocument()` launcher (filter `application/json`), read the stream, call `importWorkspace`, and surface `ImportResult.summary` (`WorkspaceExporter.kt:176-178`) in a snackbar or dialog. Show `errors` when non-empty rather than swallowing them.

**Three honesty bugs to fix in the same pass:**

| Bug | Location | Fix |
|---|---|---|
| KDoc overclaims contents | `WorkspaceExporter.kt:11-16` says a workspace includes "intercept rules, saved API requests, flow graphs, **environments, notes, and settings**" — export only writes rules, savedRequests, and flow nodes/connections | Either extend the export to genuinely cover environments/notes/settings, or correct the KDoc to list only what it writes. Prefer correcting the KDoc now; notes become exportable once H1 lands, so a follow-up can extend it |
| Hardcoded app version | `:30` `root.put("appVersion", "3.8")` while `versionName = "5.8-backup"` (`app/build.gradle.kts:21`) | `root.put("appVersion", BuildConfig.VERSION_NAME)`. `buildConfig = true` is already set (`app/build.gradle.kts:56`) and `BuildConfig.VERSION_NAME` is already used at `SettingsScreen.kt:1486` and `AppNavigationDrawer.kt:338` |
| `requestsImported` always 0 | Export writes `savedRequests` (`:48-60`) but `importWorkspace` never reads it — `requestsImported` is declared at `:100`, never incremented, and reported at `:166` | Add a `savedRequests` read loop mirroring the rules loop, upserting through `savedApiRequestDao()`. Round-trip fidelity is the whole point of a backup file |

Also remove the `TODO #50 (Workspace system).` marker at `:17`.

**No delete alternative** — the engine is real and reachable-by-design once wired; the stub-or-remove standard is satisfied by wiring, not by deletion.

**Tests.** New `app/src/test/java/com/example/data/export/WorkspaceExporterTest.kt` — export JSON contains the documented top-level keys; `importWorkspace` on that JSON reports matching counts; malformed JSON yields errors rather than throwing.

---

## Excluded — audit findings verified as already fixed

| Audit item | Proof it is fixed |
|---|---|
| Flow pan/zoom is a stub | `FlowScreen.kt:363-370` `rememberTransformableState { … scale = (scale*zoomChange).coerceIn(0.3f,3f) … }`, applied at `:387` and `:423-428` |
| Flow mini-map is a stub | `FlowScreen.kt:650-713` — real 80.dp Canvas drawing connections and node dots |
| Port-drag connection creation is a stub | `FlowScreen.kt:612-644` `detectDragGestures` + drop hit-test → `onConnectionCreated` |
| Long-press context menu is a stub | `FlowScreen.kt:508-534` `DropdownMenu` (Edit / Duplicate / Disconnect All / Delete) via `.combinedClickable` at `:549-555` |
| Flow export is a stub | `FlowScreen.kt:129-152` real `FlowSerializer.exportToJson` → `FileExporter` |
| #8 Flow nodes not persisted | `FlowRepository` is Room-backed, `AppRepositories.kt:154-272` |
| `FlowExecutor` handles only API nodes | `executeNode` covers all 8 types, `FlowExecutor.kt:85-99` |
| #9/#12 Saved-APIs favourites race | `ApiCollectionsRepository` collects `savedApiRequestDao().observeAll()`, `AppRepositories.kt:524-640`; `SavedApisScreen.kt:93-95` reads that flow |
| #13 API Testr menu stubs | Cookie Jar / Make Tool / Add to Chat / Pin absent; six real items at `ApiTestrScreen.kt:1012-1080` |
| #14 cURL export is clipboard-only | Real file export at `ApiTestrScreen.kt:1052-1080` |
| REG-05 onboarding theme collision | `OnboardingScreen.kt:208-232` writes appearance mode + both accents; `MainActivity.kt:283-299` reads them consistently |
| #11 duplicate Music/Storage permission card | `OnboardingScreen.kt:452-463` — `effectiveMusic` derived from `storageState`, disclosed as "Covered by Storage Access" |
| #21 CA auto-promote missing | `SettingsScreen.kt:1249-1271` (label removal only — C2) |
| #2 VPN app-filter hot reload missing | `RequestrVpnService.kt:178-198` (label removal only — G2) |
| §Tools "all 18 tools lack a workspace" | 19 built-ins, all dispatched at `ToolWorkspaceScreen.kt:124-156` |
| §Chats "no chat screen exists" | `ChatScreen.kt` (235) + `ChatRepository.kt` (148) exist and are routed |
| #18 Notes export missing | `NotesScreen.kt:122-161` real `MediaStoreExporter.writeText` + `shareFile` |
| #14/#15/#16 WS / SSE / DNS parsing | Real call sites at `CaptureEngine.kt:265,279-280` and `PacketPump.kt:169-176` |
| #7 only BLOCK rule action | All five actions handled, `CaptureEngine.kt:537-580` |
| #30 stale runtime flags | Already removed; only the comments remain (`AppModels.kt:392`, `AppRepositories.kt:797`) |

---

## Suggested execution order

1. **Independent, parallel-safe:** A1, B1-B7, C1, C2, C3, D1-D2, E1-E4, F1-F3.
2. **After the transport plan's relay/NAT work lands:** G1, then G2 — see the risk note below. Both touch files the transport scope is actively rewriting.
3. **After the transport plan's migration framework lands:** H1 (new tables), then H2 (needs `chat_providers`). `SecureKeyStore.kt` can be written in parallel with H1.
4. **Independent:** H3 — `WorkspaceExporter` needs no schema change.

Within Batch B, do B5 before B1/B2/B3 — they call the new repository API. Do B6 after B5(a) so port derivation exists before branch routing is wired.

The final marker sweep runs last, after every batch, since markers are removed across A through H.

---

## Risk / integration notes

### Batch G must land AFTER the transport relay/NAT work — highest-risk sequencing in this plan

Batch G touches `interception/CaptureEngine.kt` (743 lines), `interception/PacketPump.kt`, and `interception/RequestrVpnService.kt` (401 lines). **The transport scope owns all three** and is heavily rewriting them for the VPN_TUN relay/NAT gap (the tun `output: FileOutputStream` is never written today — disclosed in the repo's own v5.8-backup audit addendum). If G lands first, the transport rewrite either clobbers it or has to reapply it by hand inside a much larger refactor.

Mitigation, and how G is scoped:

- **G lands last, after the transport relay work is merged.** This is a hard ordering constraint, not a preference.
- **G is deliberately minimal and surgical.** It is described as a diff against current code, not a redesign:
  - `SettingsDataStore.kt` — add three `Flow<Boolean>` accessors. The DataStore keys already exist (`UnifiedDataStore.kt:74-76`); this is additive only, no existing line changes.
  - `RequestrVpnService.startCapture()` — read the three flows and pass them into `PacketPump`; pass them through to `CaptureEngine`. Parameter threading, no control-flow change.
  - `CaptureEngine.kt` — replace the `SettingsRepository.settings.value` reads at `:499`, `:502`, `:503`, and `:602-605` with the passed-in values. Five read sites, nothing else.
  - `G2` — delete or reword six comment lines (`CaptureEngine.kt:263, 277, 535, 544`, `PacketPump.kt:168`, `RequestrVpnService.kt:180`). Comments only, except `:277` where the SSE path needs a real decision (persist the events like `persistWsFrame` does, or say honestly that SSE is detected and logged only).
- Total G footprint: roughly a dozen changed lines plus three new accessors. If a conflict still occurs it is trivially reapplicable.

### Shared large files

Each is opened once per batch to reduce collision surface with sibling plans:

| File | Lines | Touched by | Other scopes also touching it |
|---|---|---|---|
| `ui/screens/InterceptScreen.kt` | 2968 | *not touched here* | transport-core, decryption |
| `ui/screens/SettingsScreen.kt` | 1858 | C1(b), C2, H2, H3 | transport-core (capture/proxy sections), instrumentation, browser bridge |
| `ui/screens/ApiTestrScreen.kt` | 1561 | C1(c), C3 | possibly decryption/SDK scope |
| `data/repository/AppRepositories.kt` | 1033 | B5, F3, H1 | transport-core (`SettingsRepository`), instrumentation |
| `MainActivity.kt` | 901 | C1 | every scope that adds a repository `init(context)` or a nav route |
| `interception/CaptureEngine.kt` | 743 | G1, G2 | **transport-core owns this file** |
| `interception/RequestrVpnService.kt` | 401 | G1, G2 | **transport-core owns this file** |
| `interception/PacketPump.kt` | — | G1, G2 | **transport-core owns this file** — it also owns the CRITICAL VPN_TUN relay gap (the tun `output: FileOutputStream` is never written), which is explicitly not in this plan |
| `data/prefs/SettingsDataStore.kt` | — | G1 | transport-core |
| `data/local/Entities.kt`, `Daos.kt`, `RequestrDatabase.kt` | 324 / 357 / 76 | H1 only, as five table+column requests | **schema plan owns version number and migrations** |

**Room.** This plan requests **five new tables** and no other schema change: `notes`, `note_folders`, `custom_tools`, `chat_messages`, `chat_providers` (see H1 for columns). Current state is `version = 8` with `fallbackToDestructiveMigration(dropAllTables = true)` (`RequestrDatabase.kt:39,69`) and 11 registered entities. **The transport scope owns the final version number and the `Migration` objects**, and is replacing the destructive fallback — so these five tables must arrive as `CREATE TABLE` statements inside a real migration. A wipe would destroy the captured traffic, saved requests, and flow graphs that already persist today.

**MainActivity init order.** Any new repository `init(context)` must join the existing sequence in `onCreate` (verified order): `SettingsRepository.init`, `ApiCollectionsRepository.init`, `FlowRepository.init`, `ApiTestrRepository.init`, `CaptureLogRepository.seedDefaultRules`, `PluginManager.registerBuiltinPlugins` + `scanExternalPlugins`, `LocalApiServer.init`, `GatewayEngine.init`, `RuntimeInstrumentationEngine.registerAdapter`/`start`, `BrowserBridgeManager.init`, `EnvironmentRepository.init`, then `enableEdgeToEdge()` + `setContent { RequestrApp() }`. There is **no `Application` subclass** — `app/src/main/java/com/example/` contains only `MainActivity.kt` at the top level — so `MainActivity.onCreate` is the single init site, which is exactly why G1 matters for the service process.

**Conventions to follow.** Singleton `object` repositories exposing `StateFlow` with a lazy `init(context)` guarded by `if (initialized) return` / `if (appContext != null) return` and a `private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Compose: `GlassCard`, `SectionHeader`, `SettingsSectionHeader`, `SubsectionTitle`, `SelectPreference`, `SearchInputField` (`CommonComponents.kt:413-456`), `rememberAppHapticFeedback()`, `LocalAppSpacing.current`, `scaledTween`, theme colors `TextPrimary`/`TextSecondary`/`TextMuted`. Exports must route through `MediaStoreExporter.writeText` or `FileExporter.write*` + `shareFile` — never a raw `File`, which silently fails under scoped storage on API 29+ (documented at `FileExporter.kt:23` and `MediaStoreExporter.kt:22`).

**Stub-or-remove precedent.** Documented in `SettingsScreen.kt` at `:155-161` (B9c removed Auto-start interceptor / Strict TLS / Follow Redirects — "No genuinely real target existed for any of the three, so per the plan's stub-or-remove standard they are removed rather than left as switches that flip with zero effect"), `:245-251` (B3 `TokenPlaceholders`), `:285-292` (B2 PiP, B6 Table Mode). Apply the same standard to F1's "Import MP3" and to E1's `PlaceholderWorkspace`. H2 and H3 are both being *built* rather than removed, per the resolved decisions.

**Known non-blocking environment issue.** `/memory/testing/requestr/known-issues.md` reports the Embedded HTTP API Server toggle not updating its label. That was fixed since — `LocalApiServer` now exposes `isRunningFlow` (`api/LocalApiServer.kt:39-43`) and `SettingsScreen.kt:681` collects it. Recording here so the stale memory note isn't acted on.

**Device testing.** VPN-tier features need `adb root && adb shell "ln -s /dev/net/tun /dev/tun"` re-applied per Redroid container (`/memory/knowledge/setup-learnings/android.md`). Root/Shizuku/VPN interception cannot be fully exercised on stock Redroid — accepted environment limitation, not an app defect.

---

## Final verification (after every batch)

1. **Exhaustive marker sweep.** `grep -rnE "TODO|FIXME|XXX|HACK|Coming Soon|not implemented" app/src/main/java/com/example --include=*.kt` → zero hits in this scope's files. Every one of the 13 sites catalogued in the marker-sweep section must be gone, and the two `For now` sites reviewed (`CaptureEngine.kt:544` reworded; `HttpParser.kt:196` left to the sibling scope). The Compose `placeholder =` matches listed as benign are the only permitted survivors of a `placeholder`-only grep.
2. `grep -rn "onClick = { /\*\|onClick = {}" app/src/main/java/com/example --include=*.kt` → nothing.
3. `grep -rn "PlaceholderWorkspace" app/src/main/java/com/example --include=*.kt` → nothing (E1 deletes it).
4. All three Gradle commands green: `./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug`, each with `--no-configuration-cache`.
5. Manual device pass: Home search returns real hits that navigate; export a flow, kill the app, re-import and confirm nodes/ports/connections survive; run a flow with a condition node and confirm only the matching branch executes and the banner colour matches the outcome; open a Markdown note preview and confirm formatting; create a custom tool and confirm it appears immediately and opens a real workspace; fire each advertised `requestr://` link with `adb shell am start -a android.intent.action.VIEW -d "requestr://…"` both cold and while the app is open, confirming the action fires rather than bare navigation; start a capture, toggle Request/Response logging, and confirm the persisted rows honour the toggles (G1); write a note, force-stop the app, reopen and confirm it survived (H1); save an API key, force-stop, reopen and confirm the provider still works while the DataStore value on disk is not the plaintext key (H2); export a workspace, wipe rules, re-import and confirm the reported counts include a non-zero `requestsImported` (H3).

---

## Resolved decisions (2026-08-21)

All four blocking questions are answered. Nothing in this plan is blocked.

| # | Question | Decision | Where it lands |
|---|---|---|---|
| Q1 | Persist notes / custom tools / chat to Room? | **(a) New tables.** Version number and `Migration` objects are owned by the transport scope; this plan requests tables and columns only, and they must arrive as a proper `Migration`, never a destructive wipe | H1 |
| Q2 | Where do chat provider credentials live? | **(b) Keystore-backed storage, for the key only.** Provider URL, model, and other non-secret config go to Room/DataStore as normal. A plaintext API key is not acceptable in a security tool | H2 |
| Q3 | Add Cookie Jar / Make Tool / Add to Chat / Pin to the API Testr menu? | **Skip.** Verified absent — recorded below with evidence rather than silently dropped | out of scope, recorded |
| Q4 | Wire or delete `WorkspaceExporter`? | **Wire it** into BACKUP & PRIVACY and fix its three honesty bugs. Orphaned working code is the "looks done, isn't reachable" pattern being eliminated | H3 |

### Q3 — verified out of scope, with evidence

**Cookie Jar, Make Tool, Add to Chat, and Pin are not in this plan because they are already absent from the UI, with nothing stub-shaped left behind.** Evidence: the API Testr overflow menu at `ApiTestrScreen.kt:1012-1080` contains exactly six items, all backed by real code; `grep -rn "Cookie\|cookieJar\|Add to Chat\|Make Tool" app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` returns nothing. No dead menu entry, no disabled button, no "Coming Soon" label — so the stub-or-remove standard is already satisfied and adding them would be net-new feature scope, not honesty repair. Revisit Cookie Jar as its own feature request if wanted.

### Q2 — storage mechanism confirmed

The decision originally named `EncryptedSharedPreferences`. That API (`androidx.security:security-crypto`) is **deprecated upstream** with no 1:1 official replacement, so recommending it would have shipped exactly the kind of defect this pass eliminates. **Confirmed 2026-08-21: raw `AndroidKeyStore` + `Cipher("AES/GCM/NoPadding")`, zero new dependencies.** `tink-android:1.18.0` and `security-crypto:1.1.0` are recorded in H2 as rejected, not as open options. Nothing about Q2 is outstanding.

### Cross-scope handoff — CA keystore password

`CertificateAuthority.kt:42` hardcodes `private val keystorePassword = "requestr-ca-local".toCharArray()`, protecting the CA private key persisted by `persist()` (`:127-136`) to `File(context.filesDir, KEYSTORE_FILE)`. Same class of problem as a plaintext API key. **Routed to the SDK/decryption scope — no action in this plan.** Recorded here only so the finding is not lost between scopes; `SecureKeyStore` (H2) is the natural home for it once that scope picks it up.

---

## TODO / FIXME marker sweep — exhaustive across this scope's files

The requirement is that **every** stub marker in this scope's files is gone by the end — implemented or the feature removed. This sweep is **exhaustive across the scope's files, not just the five labels called out in G2.** Command run: `grep -nE "TODO|FIXME|XXX|HACK|Coming Soon|not implemented|no-op|noop|placeholder|Placeholder|stub|Stub|for now|For now"` over `ui/screens/{HomeScreen,FlowScreen,NotesScreen,ToolsScreen,ToolWorkspaceScreen,MusicScreen,ChatScreen}.kt`, `MainActivity.kt`, `flow/{FlowExecutor,FlowSerializer}.kt`, `data/repository/{ChatRepository,ToolsRepository,AppRepositories}.kt`, `data/models/AppModels.kt`, `data/export/WorkspaceExporter.kt`, `data/search/TrafficSearch.kt`.

**Real markers requiring action — 13 sites, every one assigned to a batch:**

| Marker | Batch |
|---|---|
| `FlowScreen.kt:127` `// Fix #25 (TODO): Flow export/import via FlowSerializer` | B7 |
| `FlowScreen.kt:347` KDoc `Implements TODO items #23, #24, #32, #33, #34.` — a *reference* to completed items, but it still trips the final `grep`; reword to "Implements items #23…" | B7 |
| `NotesScreen.kt:121` `// Fix #18 (TODO): Export note as MD or HTML file` | D2 |
| `ToolsScreen.kt:124` `// Fix #37 (TODO): "Create Custom Tool" card…` | E4 |
| `ToolWorkspaceScreen.kt:158` `else -> PlaceholderWorkspace(tool.title)` | E1 |
| `ToolWorkspaceScreen.kt:705` `// Placeholder` | E1 |
| `ToolWorkspaceScreen.kt:709` `private fun PlaceholderWorkspace(title: String)` | E1 |
| `ToolWorkspaceScreen.kt:714` `Text("$title — Coming Soon", …)` | E1 |
| `MusicScreen.kt:289` `// Fix #35 (TODO): swipe-to-remove with confirmation` | F2 |
| `ChatScreen.kt:55` `* TODO #44 (Chats section).` | H2 |
| `ChatRepository.kt:17` `* TODO #44 (Chats section).` | H2 |
| `MainActivity.kt:122, 214, 345` — `Fix #22` / `Fix #43 (TODO)` labels; `:131` is the dead `sendBroadcast` block itself | C1(c), C1(d) |
| `AppRepositories.kt:797` and `AppModels.kt:392` — `Fix #30 (TODO): removed stale runtime flags` (labels describing completed removals) | delete with F3 / B5(g) |
| `WorkspaceExporter.kt:17` `* TODO #50 (Workspace system).` | H3 |

**Provisional-language sweep** (`grep -rn "For now\|for now"` across all of `app/src/main/java/com/example/`) — only two hits, both outside this scope's core files:

- `interception/CaptureEngine.kt:544` `// For now, log + persist with a tag so user knows it was a redirect candidate.` inside the `RuleAction.REDIRECT` branch (`:542-552`). The branch does real work — it rewrites `request.url` and `request.host` from `matchedRule.replacementValue` — so this is provisional wording sitting on working code. **Reword as part of G2** (same file, same surgical pass).
- `interception/http/HttpParser.kt:196` `"CONNECT" -> HttpMethod.WS // CONNECT is tunnelling, map to WS for now` — belongs to the sibling CONNECT/decryption scope. **Not touched here**, recorded so it is not assumed missed.

**Benign matches that must NOT be edited** — these are Compose `placeholder =` parameters on text fields, not stub markers: `HomeScreen.kt:187`; `NotesScreen.kt:218, 266, 301`; `ToolsScreen.kt:98, 187, 212, 220, 228, 229`; `ToolWorkspaceScreen.kt:216, 357, 399, 456, 588, 606, 648`; `ChatScreen.kt:177`. Also benign descriptive prose: `AppRepositories.kt:549` ("empty placeholder request so the API Testr screen opens safely") and `AppRepositories.kt:852` ("this can never silently no-op").

**Files already clean, zero markers:** `flow/FlowExecutor.kt`, `flow/FlowSerializer.kt`, `data/repository/ToolsRepository.kt`, `data/search/TrafficSearch.kt`.
