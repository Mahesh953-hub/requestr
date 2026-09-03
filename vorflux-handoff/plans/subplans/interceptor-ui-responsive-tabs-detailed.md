### Summary

Build one scrollable, intrinsic-width tab primitive in the Lane A component layer, migrate every tab and pill site in the app onto it (or onto the correct sibling component), then extract the 5095-line `InterceptScreen.kt` monolith and build out the 15 interceptor surfaces from `/code/.plans/designs/design-plan.json` as complete Compose screens. Tab labels never wrap, never compress, and never get equal-width treatment; overflow scrolls horizontally with the selected tab scrolled into view. All colour, shape, blur, density, motion and haptic values come from the existing frozen design system.

Branch: `vorflux/fix-audit-gaps` at `b71d61f`. Repo root: `/code/Mahesh953-hub/requestr`. Paths below are relative to `app/src/main/java/com/example/` unless stated otherwise.

Lane ownership from `docs/DESIGN-SYSTEM.md` is binding: Lane A owns `ui/theme/**`, `ui/components/**`, `MainActivity.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt`; Lane B owns `ApiTestrScreen.kt`, `ToolsScreen.kt`, `ToolWorkspaceScreen.kt`, `ResponseInspectorHelpers.kt`; Lane C owns `FlowScreen.kt`; Lane D owns `InterceptScreen.kt` and extracted interceptor UI. `data/repository/AppRepositories.kt`, `data/models/AppModels.kt` and `data/prefs/UnifiedDataStore.kt` are frozen — no task below edits them.

---

### Tasks

#### 1. **[parallel] Scroll primitive, responsive tab row, chip strip, and tier documentation** (Lane A)

New files:
- `ui/components/RequestrScrollableStrip.kt` — internal `ScrollableStrip` composable: `BoxWithConstraints` shell, `Row(Modifier.horizontalScroll(scrollState), horizontalArrangement = Arrangement.spacedBy(6.dp))`, trailing/leading fade drawn with `Modifier.drawWithContent` + `Brush.horizontalGradient(transparent → containerColor)` over a 34.dp band, plus an optional chevron affordance. Fades are visible only when `scrollState.canScrollForward` / `canScrollBackward`. `overscrollEffect` left at Compose default so momentum and the platform glow are unchanged.
- `ui/components/RequestrResponsiveTabRow.kt` — public tab component and its `@Immutable data class RequestrTabItem(id: String, label: String, icon: ImageVector? = null, badgeCount: Int? = null, contentDescription: String? = null, enabled: Boolean = true)`. Signature:
  `RequestrResponsiveTabRow(items: List<RequestrTabItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, indicator: TabIndicatorStyle = TabIndicatorStyle.Pill, compact: Boolean = false, maxTabWidth: Dp? = null, glassRole: GlassRole? = null, scrollState: ScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })`.
- `ui/components/TabStripMath.kt` — pure, Compose-free decision functions (this repo's tested pattern, cf. `bottomOverlayInsetFor` in `MainActivity.kt:539` and `statusCodeBadgeText` in `CommonComponents.kt`):
  - `tabScrollTarget(selectedStartPx: Int, selectedWidthPx: Int, viewportPx: Int, currentOffsetPx: Int, maxOffsetPx: Int, edgePeekPx: Int, isRtl: Boolean): Int`
  - `clampSelectedTabIndex(requestedIndex: Int, previousSelectedId: String?, ids: List<String>): Int` — prefers a stable-id match, otherwise clamps into `0..lastIndex`, otherwise `0` for an empty list
  - `tabAccessibilityLabel(label: String, badgeCount: Int?, position: Int, count: Int): String`
  - `stripOverflows(contentWidthPx: Int, viewportWidthPx: Int): Boolean`
- `ui/components/RequestrChipStrip.kt` — `RequestrChipStrip(modifier, contentPadding, content: @Composable RowScope.() -> Unit)` wrapping `ScrollableStrip` for filter-chip rows, plus a `selectedIndex`-aware overload that scrolls the active chip into view.

Behaviour requirements (each is an acceptance criterion, not a suggestion):
- Tab measure path is `Modifier.wrapContentWidth()` inside `horizontalScroll`, so children receive `Constraints(maxWidth = Infinity)` and can never be compressed. `Modifier.weight` must not appear in this file.
- Label is `Text(maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)`. Ellipsis is applied only when the caller passes a non-null `maxTabWidth`; default is `null` (never truncate).
- Whether the strip scrolls is read from `scrollState.maxValue > 0`, never from `LocalConfiguration.screenWidthDp`. The audit's `tabRowWidth > screenWidth * 0.9f` estimate is explicitly not implemented.
- Each tab records its `x` and `width` via `Modifier.onGloballyPositioned` into a `remember { mutableStateMapOf<Int, IntRange>() }`; a `LaunchedEffect(selectedIndex, positions[selectedIndex], scrollState.maxValue)` calls `tabScrollTarget` then `scrollState.animateScrollTo(target, scaledTween(250))`, or `scrollTo(target)` when `!LocalAnimationsEnabled.current || LocalAnimationDurationScale.current == 0f`.
- `selectedIndex` is passed through `clampSelectedTabIndex` before use, so an item count change (ApiTestr response tabs 5↔6) can never index out of bounds or silently jump.
- Semantics: container `Modifier.selectableGroup()`; each tab `Modifier.semantics { role = Role.Tab; selected = index == selectedIndex; contentDescription = item.contentDescription ?: tabAccessibilityLabel(...) }`.
- Keyboard: `Modifier.onPreviewKeyEvent` on the container handles `DirectionLeft`, `DirectionRight`, `MoveHome`, `MoveEnd` **only** while a descendant holds focus (tracked with `onFocusChanged`), returning `false` otherwise so no global shortcut is shadowed.
- Tokens: pill shape `animateDpAsState(if (selected) 24.dp else 10.dp, scaledTween(200))`, selected background `MaterialTheme.colorScheme.primary` and content `onPrimary`, unselected `surfaceVariant.copy(alpha = 0.45f)` / `onSurfaceVariant`, both via `animateStateColor(..., scaledTween(200))` exactly as `RequestrSegmentedTabs` does. Padding `PaddingValues(horizontal = 13.dp, vertical = 9.dp)` at standard density, `10.dp/6.dp` when `compact = true`, further adjusted by `LocalDensityConfig`. Label `12.sp` Bold when selected / Medium otherwise. Icon `15.dp` with a `5.dp` spacer. Badge uses the existing `BadgePill`.
- Minimum tab height `44.dp` via `defaultMinSize`, with no fixed-height container anywhere in the label chain (design-system §14.4).
- Haptic on select: `rememberAppHapticFeedback()` with `HapticFeedbackType.TextHandleMove`, matching `RequestrSegmentedTabs`. Never the only feedback — colour and pill shape also change.
- Glass: when `glassRole != null`, the container background uses `Modifier.glassSurface(rememberGlassSurface(role, backdrop))`, must be a sibling drawn after its `GlassBackdropSource` (never a descendant), and must resolve through `canonicalGlassStyle` so all six modes (`FROSTED_GLASS`, `SOLID_M3`, `OLED_BLACK`, `TRANSLUCENT`, `LIQUID_GLASS`, `TUNE_GLASS`) work. When `glassRole == null` the container is transparent.

Documentation, same task:
- Add a `§10.4 RequestrResponsiveTabRow / RequestrChipStrip` token table to `docs/DESIGN-SYSTEM.md` and a "which component do I use" rule to §10.1.
- New `docs/design/responsive-tabs-implementation.md`.
- New `9. RESPONSIVE TABS` section in `docs/plans/dossiers/design-system-current-state.md`.
- Add a KDoc block on `RequestrSegmentedTabs` in `ui/components/CommonComponents.kt:1474` stating the ≤4-item ceiling and pointing at the new component. No signature change to `RequestrSegmentedTabs`.

Tests: `app/src/test/java/com/example/ui/components/TabStripMathTest.kt` (pure) and `RequestrResponsiveTabRowTest.kt` (Robolectric + `createComposeRule`, following `app/src/test/java/com/example/ui/screens/MockModeEditorUiTest.kt`). Detail in the Testing section.

#### 2. **[after 1] Migrate ApiTestr tab and pill sites** (Lane B, `ui/screens/ApiTestrScreen.kt`)

- Request tabs (`TabRow` at line 463, `Tab` at 479, labels built at 437–461) → `RequestrResponsiveTabRow`. Seven HTTP labels (`Query (n)`, `Headers (n)`, `Body (n chars)`, `Auth`, `Scripts`, `Chain`, `Tests`) and three WebSocket labels. Counts move from label text into `badgeCount` where the count is a plain integer; `Body (n chars)` keeps its inline text because it is not a plain count.
- Response tabs (`TabRow` at 834, `Tab` at 840) → `RequestrResponsiveTabRow` with stable ids, so appearing/disappearing `Test Results` / `Script Results` tabs go through `clampSelectedTabIndex` instead of shifting the selection. Extract the tab-set builder into a pure `apiTestrResponseTabs(hasAssertions: Boolean, hasScriptResults: Boolean): List<RequestrTabItem>` in `ui/screens/ResponseInspectorHelpers.kt` and unit test it.
- Content-type selector pills at 2654 (`HttpRequestBodyTab`, iterates `RequestBodyType.values()`) → `RequestrResponsiveTabRow(compact = true)`.
- Authorization method selector at 2840 (`AuthSettingsTab`, iterates `AuthType.values()`) → `RequestrResponsiveTabRow(compact = true)`; keep the existing `at.name.replace('_', ' ')` label transform.
- The 2-item Request/Response view toggle at 420 keeps `RequestrSegmentedTabs` with `equalWeight = true`. Add a comment recording why it is exempt.
- Audit the remaining `horizontalScroll` rows at 890, 1029, 1414 and the `WsStreamComposerTab` region around 3277: convert any that are selector rows to `RequestrChipStrip`, leave genuinely non-selector rows alone, and note the verdict per site in a comment.
- Remove the now-unused `TabRow` / `TabRowDefaults` imports from this file.

#### 3. **[after 1] Normalise remaining chip and selector rows; remove dead imports** (Lane A + Lane B + Lane C)

- `ui/screens/ToolsScreen.kt:390` `ToolCategorySelector` → `RequestrChipStrip` + `RequestrFilterChip`, preserving the emoji icons, the leading `All` entry and `ToolCategory.entries` order (Lane B).
- `ui/screens/FlowScreen.kt:57–59`: delete the three unused `TabRow` / `SecondaryIndicator` / `tabIndicatorOffset` imports (Lane C).
- Adopt `RequestrChipStrip` for the existing `RequestrFilterChip` rows so the fade/scroll affordance is not reimplemented per screen: `ui/screens/RecentlyOpenedScreen.kt:100,108`, `ui/screens/NotesScreen.kt:206,213`, `ui/screens/HomeScreen.kt:680,705` (Lane A for Home, Lane A for Notes/RecentlyOpened per the components-and-shell ownership; confirm before editing).
- No change to `NotesScreen.kt:489` format selector or `SettingsScreen.kt:2962` density selector — both are ≤4 short fixed labels and stay on `RequestrSegmentedTabs`. Record this as an explicit "reviewed, no change" line in the migration doc so it is not re-audited later.

#### 4. **[after 1] Navigation and route scaffolding** (Lane A, `MainActivity.kt` + `DeeplinkRouter.kt`)

- Extend `sealed class Screen` (line 331) with the new depth destinations, using the `ToolWorkspace.createRoute` data-class pattern for argument routes:
  `CaptureSessions("capture/sessions")`, `CaptureFilters("capture/filters")`, `CaptureDiagnostics("capture/diagnostics")`, `Inspector("capture/inspector/{txId}")`, `WebSocketViewer("capture/ws/{txId}")`, `SseViewer("capture/sse/{txId}")`, `GrpcViewer("capture/grpc/{txId}")`, `Http3Viewer("capture/http3/{connId}")`, `Rules("rules")`, `RuleEditor("rules/edit/{ruleId}?type={type}")`, `Breakpoints("breakpoints")`, `Replay("capture/replay/{txId}")`, `Certificates("certificates")`, `Throttle("throttle")`, `Scripts("scripts")`.
- Register each with the existing private `NavGraphBuilder.screen(route, arguments)` helper (line 546) inside the `NavHost` at line 829, next to the existing `intercept` registration at ~936.
- Nav pill and `CollapsibleNavPill.DEFAULT_NAV` are unchanged — `intercept` remains the only interceptor entry in the pill, so `settings.navBarHiddenItems` semantics and `NAV_ORDER` are untouched and `ScreenTransitionResolverTest` keeps passing. New routes are not in `NAV_ORDER`, so `resolveScreenTransition` (line 396) gives them `DEPTH_FORWARD` / `DEPTH_BACKWARD` automatically with no code change.
- Decide `ROUTES_SCROLLING_UNDER_PILL` membership (line 532) per new route: list-backed screens whose root is a `LazyColumn` that pads `LocalBottomOverlayHeight` join the set; screens with a bottom-anchored action bar do not. Record the choice in the KDoc block above that set, which already documents this reasoning per route.
- Add matching `DeeplinkRouter.Routes` constants and `parse` cases; extend `app/src/test/java/com/example/DeeplinkRouterTest.kt`. Keep `intercept` navigation-only, per the existing comment explaining why `intercept/toggle` is deliberately absent.
- Add drawer entries in `ui/components/AppNavigationDrawer.kt` for Rules, Breakpoints, Certificates and Diagnostics.

#### 5. **[after 1] Extract `InterceptScreen.kt` into `ui/screens/capture/`, no behaviour change** (Lane D)

Pure mechanical split of the 5095-line file so the later screen tasks can run in parallel and each diff stays reviewable. New package `ui/screens/capture/`:

| New file | Moved from `InterceptScreen.kt` |
|---|---|
| `CaptureWorkspaceScreen.kt` | `InterceptScreen` (204–1134) |
| `CaptureSetupSheet.kt` | `CaptureSetupBottomSheet` (2765), `SetupRow` (3084), `CapabilityStatusCard` (1684), `EngineChip` (1192) |
| `ConnectionsSurface.kt` | `ConnectionsBottomSheet` (1891), `EmptyConnectionsState` (2471), `ConnectAttemptDetailSheet` (2508), `connectDispositionColor`, `clientIdentityFor` |
| `TransactionRow.kt` | `TrafficItemCard` (1740) |
| `TransactionDetailSurface.kt` | `CapturedDetailBottomSheet` (4637), `CaptureBodySection` (4233), `CaptureBinaryBodySection` (4281), `SensitiveAwareHeaderRow` (4982), `DetailRow`, `TimingBar`, `formatBytes` |
| `RulesConfigSurface.kt` | `RulesConfigBottomSheet` (3596) |
| `CertificateSurface.kt` | `MitmCertificateBottomSheet` (1415) |
| `SystemIntegrationSurface.kt` | `SystemIntegrationBottomSheet` (1578), `FridaScriptsBottomSheet` (1234) |
| `CaptureFilterSurface.kt` | `AppFilterBottomSheet` (3288), `SortOptionsBottomSheet` (3135) |
| `ReplaySurface.kt` | `RepeatReplayDialog` (4395), `PasteCurlDialog` (1136) |

In the same pass, migrate this file's two `TabRow` sites and its ad-hoc selectors:
- Protocol tabs (`TabRow` at 2016, `Tab` at 2034; `TCP (n)` … `Provenance (n)`) → `RequestrResponsiveTabRow` with counts as `badgeCount`.
- Flow-inspector tabs (`TabRow` at 4738, `Tab` at 4749; Overview/Headers/Body/Timing/TLS/Raw) → `RequestrResponsiveTabRow`.
- Method filter row at 513 (`ALL (n)` + per-method) → `RequestrChipStrip` + `RequestrFilterChip`.
- `EngineChip` capability row at 2939 → `RequestrChipStrip`, chips stay non-selectable status chips.
- `RuleAction` selector at 3724–3729 (raw M3 `FilterChip`) → `RequestrChipStrip` + `RequestrFilterChip`.
- Crypto scope selector at 4016–4020 (`REQUEST` / `RESPONSE` / `BOTH`, raw `FilterChip`, fixed `Row`) → `RequestrSegmentedTabs` (3 short fixed labels).
- Delete the file's `TabRow` / `TabRowDefaults` imports.

Existing helpers stay where they are and are reused, not duplicated: `ui/screens/CaptureController.kt`, `CapturedTrafficHelpers.kt`, `ResponseInspectorHelpers.kt`, `NetworkConditionUiHelpers.kt`, `RuleTemplateUiHelperskt`, `ui/components/RelayHealthCard.kt`, `RelayHealthDetailSheet.kt`, `Http3DetailSheet.kt`, `Http3RowIndicator.kt`, `TlsFailureDetailSheet.kt`, `StackFingerprintPanel.kt`, `RuntimeInstrumentationCard.kt`, `DiffView.kt`.

Acceptance: `InterceptScreen.kt` is reduced to a thin `@Composable fun InterceptScreen(...)` that delegates to `CaptureWorkspaceScreen`, the existing route registration and `onSendToTestr` / `onMiniSettings` / `onNavigateFullSettings` contract are unchanged, and the full unit test suite still passes with no test edits other than tab-related ones.

#### 6. **[after 5] Capture workspace screen** (Lane D, `ui/screens/capture/CaptureWorkspaceScreen.kt`)

From the `capture-workspace` mockup. Six-tab `RequestrResponsiveTabRow`: Traffic · Connections · TLS sessions · DNS queries · QUIC / HTTP3 · Diagnostics. Each tab renders real data from an existing source — no tab may be a placeholder:

| Tab | Data source |
|---|---|
| Traffic | `CaptureLogRepository.observeTraffic(context)` → `CapturedTrafficEntity` |
| Connections | `ConnectionsRepository` → `TcpConnectionEntity`, `UdpFlowEntity` |
| TLS sessions | `TlsSessionDao` (`Daos.kt:635`) |
| DNS queries | `DnsQueryDao` (`Daos.kt:671`) |
| QUIC / HTTP3 | `QuicConnectionDao` (`Daos.kt:497`) + `QuicDecodeStatus` |
| Diagnostics | `CapabilityProbe` + `InterceptionStateRepository.capabilities` (embeds the summary from task 12) |

Other elements: status pill driven by `InterceptionStateRepository.state` (`CAPTURE READY · VPN_TUN` style, reusing `CaptureStateBanner`); search field with the existing `TrafficSearch.filter` plus the mockup's `status:500 method:POST host:…` token syntax; quick-filter chip row via `RequestrChipStrip`; transaction rows using the extracted `TransactionRow.kt` with method badge, host, path, protocol badge (`h2` / `h3` / `SSE`) and `StatusCodeBadge`; a detail pane with an "Open inspector" action that navigates to `capture/inspector/{txId}`.

Responsive layout: single column with a bottom detail sheet below `sw600dp`; the mockup's `minmax(0,1.25fr) minmax(280px,.75fr)` two-pane split at `sw600dp` and above, implemented with `BoxWithConstraints`. Empty, loading, error and permission states use the §15 state vocabulary.

Pure logic extracted to `ui/screens/capture/CaptureWorkspaceHelpers.kt` and unit tested: search-token parsing, quick-filter predicate composition, row protocol/status derivation, tab badge counts, two-pane breakpoint decision.

#### 7. **[after 5] Session workspace, filters, and query builder** (Lane D, `ui/screens/capture/SessionWorkspaceScreen.kt` + `CaptureFilterPanel.kt`)

From the `session-workspace` mockup. Session tabs (`Current capture`, imported HAR sessions, pinned reviews, plus a dashed `＋ New session` affordance) via `RequestrResponsiveTabRow`, backed by `CaptureSessionEntity` / `CaptureSessionDao` and `CaptureSessionRegistry.kt`. Dynamic 1→10+ sessions must not shift the selection — that is `clampSelectedTabIndex` with the session id as the stable key.

Filter explorer sidebar: Applications (from `TrafficInterceptorRepository.installedApps`), Domains (derived from captured hosts), Visibility with "Hide bypassed traffic". Query builder with add/remove condition rows (field / operator / value), Apply, and quick-filter chips. Below `sw600dp` the sidebar collapses behind a filter button, matching the mockup's `@media(max-width:760px)` behaviour.

Pure logic in `CaptureFilterHelpers.kt`: condition model, validation, predicate compilation, round-trip to and from the search-token string used in task 6. Unit tested including the mockup's empty line "No transactions match all active conditions."

#### 8. **[after 5] Protocol-adaptive transaction inspector** (Lane D, `ui/screens/capture/InspectorScreen.kt`)

From the `inspector` mockup. Up to eight tabs: Overview · Headers · Body · Cookies · Timing · TLS · HTTP/2 frames · Raw / Hex. The set is protocol-adaptive, computed by a pure `inspectorTabsFor(entity: CapturedTrafficEntity, tls: TlsSessionEntity?, quic: QuicConnectionEntity?): List<RequestrTabItem>` in `InspectorHelpers.kt` — HTTP/2 frames only for `h2`, TLS only when a session exists, Cookies only when `Set-Cookie` / `Cookie` is present. Unit test every combination.

Request and Response fact panels; body viewer with pretty/raw/hex modes using the existing `HttpBodyDecoder` and honouring `isRequestBodyBinary` / `isResponseBodyBinary` / `*BodyBytesTruncated` three-state contract from `Entities.kt` (a truncated binary body must read "N KB, not stored", never blank). Actions: Repeat (routes to `ReplaySurface`), Send to API Testr (existing `onSendToTestr`), Export, Copy cURL.

Nested-scroll rule: the body viewer's vertical scroll takes precedence for vertical drags; the tab strip's horizontal scroll takes precedence for horizontal drags. Verified by test.

#### 9. **[after 8] WebSocket, SSE, gRPC and HTTP/3 protocol viewers** (Lane D, `ui/screens/capture/protocol/`)

Four screens, each with a main `RequestrResponsiveTabRow` plus a nested `compact = true` row for payload encoding — proving the component nests correctly:
- `WebSocketViewerScreen.kt` — Messages · Handshake · Frames · Timing · TLS; direction chips All/Sent/Received; frame detail facts (Direction, Opcode, FIN, Compression) + compact Text · Hex. Reads `websocket_messages`.
- `SseViewerScreen.kt` — Events · Request · Response headers · Timing · Raw stream; Follow chip; event facts (Event, ID, Retry) + compact Text · JSON · Hex. Reads `sse_events`.
- `GrpcViewerScreen.kt` — Messages · Metadata · Trailers · HTTP/2 frames · Timing · Raw / Hex; `grpc-status` badge; framing facts (Direction, Compressed flag, Encoding, Descriptor) + compact Hex · Raw. Reads `grpc_messages` and `http2_stream_events`. Renders the mockup's honest line "Protobuf descriptor unavailable / Raw payload preserved" when `ProtobufDecoder` has no descriptor.
- `Http3ViewerScreen.kt` — Connection · Packets · Streams · QPACK · TLS keys · Raw / Hex. Reads `QuicConnectionEntity` + `QuicDecodeStatus` and the existing `Http3DetailState` (Decoded / QuicOnly / Undecrypted) and `TlsKeyLogRegistry`. Renders the honest states verbatim from the mockup: `OBSERVED · NO KEYS` badge, "Decryption unavailable without matching session keys", the "No HTTP body is shown" callout, a numbered "Make this decodable" remedy list, and the two actions "Open TLS key diagnostics" and "Block QUIC and retry over HTTP/2". Reuses `ui/components/Http3DetailSheet.kt` content rather than duplicating it.

**Cross-plan dependency:** the four dedicated tables (`websocket_messages`, `sse_events`, `grpc_messages`, `http2_stream_events`) are delivered by the protocol/persistence sibling plan at schema v19→v20. Today the same events exist only flattened into `captured_traffic` — WebSocket as `method = "WS→"` / `"WS←"` rows (`CaptureEngine.kt:843`) and gRPC as `x-grpc-*` pseudo-headers (`:595–599`) — so the data does exist but in a shape these viewers cannot render correctly. Start this task after that migration lands. Only the HTTP/3 viewer is independent of it and can ship earlier.

The inspector's Headers and Timing tabs (task 8) carry the same dependency: Headers needs `transaction_headers` to preserve duplicate and ordered `Set-Cookie` values, and Timing needs `transaction_timings` for measured dns/connect/tls/send/wait/receive phases rather than the single `latencyMs` column. Build those two tabs against the normalized tables; the other six inspector tabs have no dependency.

#### 10. **[after 5] Rule library and the five rule editors** (Lane D, `ui/screens/capture/rules/`)

`RuleLibraryScreen.kt` from the `rule-library` mockup: folder sidebar (All rules / named folders / Unfiled), ordered rule list with drag-to-reorder handle, rule-type badges (Gateway red, Mirror blue, Rewrite violet, Mock green, Throttle amber), per-rule enable checkbox, Import / Export / ＋ New rule, Disable all, and the evaluation-order note "Drag to change evaluation order. First matching terminal action wins." Backed by `InterceptRuleEntity` / `InterceptRuleDao` and `com.example.data.local.RuleAction`.

`RuleEditorScaffold.kt` — shared 4-step shell (sidebar steps at `sw600dp`+, a `RequestrResponsiveTabRow` step strip below that), plus Cancel / test-action / Save. Five bodies, one file each, matching the mockups exactly:
- `GatewayRuleEditor.kt` — Action (Block / Allow only / Bypass / Suspend), Layer, Match target (Domain wildcard / IP / CIDR / URL pattern), Domain pattern, warn callout "Block closes only matching flows."
- `MirrorRuleEditor.kt` — source/destination host+port, three-way identity policy radio, callout "Existing keep-alive connections are not remapped."
- `RewriteRuleEditor.kt` — Phase select, URL pattern, Literal vs Regular expression, header-change rows, body textarea, callout "Content-Length is recalculated automatically." Reuses `RuleTemplateUiHelpers.kt` and `ReplacementValueUiHelpers.kt`.
- `MockRuleEditor.kt` — Method, URL pattern, Status code, and a nested 3-tab `Body · Headers · Delay` strip. Reuses the existing `MockModeEditor` behaviour covered by `MockModeEditorUiTest.kt`.
- `ThrottleRuleEditor.kt` — Preset, Direction, Latency, Jitter, Bandwidth, Packet loss, all defaulting to empty with placeholder "Required" and the warn callout "Enter measured test values before saving. The editor does not assume a network profile." Reuses `NetworkConditionUiHelpers.kt` and `NetworkConditionInjector`.

Validation for all five lives in `RuleEditorHelpers.kt` as pure functions and is unit tested, extending the existing `RuleConfigValidityTest.kt`.

#### 11. **[after 10] Breakpoint workspace** (Lane D, `ui/screens/capture/BreakpointWorkspaceScreen.kt`)

From the `breakpoint-workspace` mockup. Paused-flow queue sidebar with PAUSED / WAITING amber badges and a Resume all action; editor pane with a 4-tab `RequestrResponsiveTabRow` (Headers · Body · Cookies · Raw / Hex); header rows with masked `Authorization` plus a Reveal toggle reusing the existing sensitive-header treatment from `SensitiveAwareHeaderRow`; ＋ Add header; body editor; Abort (danger) / Save draft / Resume with changes. Wires to `RuleEnforcer` breakpoint state. Pure queue-ordering and edit-diff logic in `BreakpointHelpers.kt`, unit tested.

#### 12. **[after 5] Capture diagnostics, trust, and remediation** (Lane D, `ui/screens/capture/DiagnosticsScreen.kt`)

From the `diagnostics` mockup. Summary header with a three-state severity dot; capture-path check list (VPN permission, Relay listener, Root CA trust, QUIC policy) each with pass / warn / info state read from `CapabilityProbe`, `CaTrustGate`, `CaptureMethodWatcher` and `VisibilityAnalyzer`; a "Re-run checks" action; per-check remediation detail with real actions (Install Root CA, Export PEM, Bypass this host); browser-specific checks including the Chrome/Firefox trust differences and "Chromium does not trust user-added CAs for QUIC; use HTTP/2 fallback".

Version-accurate copy, derived by a pure function from `Build.VERSION.SDK_INT` and unit tested at API 24, 28, 29, 31, 33, 34: user CA install is opt-in from API 24, UID attribution needs API 29, real blur needs API 31, `POST_NOTIFICATIONS` at 33, foreground-service type plus the 6h/day cap and the conscrypt APEX CA store move at 34.

Pure derivation in `DiagnosticsHelpers.kt`: `captureChecks(...)` → ordered list of `DiagnosticCheck(id, label, severity, remediation)`, plus `overallSeverity(checks)`. Unit tested for every severity combination.

#### 13. **[after 5] Export, replay, certificates, throttle presets, scripts, and the Settings capture expansion** (Lane D for the surfaces, Lane A for Settings)

- `ui/screens/capture/ExportPanel.kt` — HAR 1.2, cURL, and language snippets, reusing `data/interception/TrafficExporter.kt` and `data/export/`.
- `ui/screens/capture/ReplayControlScreen.kt` — Settings · Progress · Results tabs over `ConnectReplay` / `ConnectControl`, with the 255-concurrent-connection ceiling surfaced as a real limit, not a silent clamp.
- `ui/screens/capture/CertificateManagementScreen.kt` — Install · Verify · Pinning tabs over `CertificateAuthority`, `CaTrustGate`, `CaP12Exchange`, `CaWhitelistService`.
- `ui/screens/capture/NetworkThrottleScreen.kt` — Presets · Custom tabs over `NetworkConditionInjector`; no preset is pre-selected and no values are assumed.
- `ui/screens/capture/ScriptEditorScreen.kt` — Pre-Request · Post-Response · Shared · Console (4 items; uses `RequestrResponsiveTabRow` rather than the segmented control because the labels are long, per the tier rule). Backed by `scripting/`.
- `ui/screens/SettingsScreen.kt` — add the capture-tier grid (VPN / Root / Shizuku / Frida / SDK / None mapped to `CaptureTier`), CA management entry, throttle preset defaults and script limits. Uses existing `SettingsDataStore` keys where they exist; any genuinely new user-facing preference is called out for the data owner rather than added to the frozen `UnifiedDataStore.kt`.

Scope of this task depends on the on-device policy blocking question in the summary. If the developer-mode option is chosen, the traffic-modifying surfaces (rewrite, mock, breakpoint, throttle, scripts) sit behind a single gate in Settings, and that gate is implemented here.

#### 14. **[after 2,3,5–13] Cross-cutting verification and stub audit**

- Roborazzi baselines recorded for the tab component and every new screen across the configuration matrix in the Testing section (`./gradlew recordRoborazziDebug`, then `verifyRoborazziDebug` in the normal test run).
- Grep gate: no `TODO`, `FIXME`, `Coming soon`, `Not implemented`, `placeholder` or empty composable body in `ui/screens/capture/**` or the new component files. Add this as a test that scans the source tree, so it cannot regress.
- Grep gate: zero `androidx.compose.material3.TabRow` references remain in `app/src/main`.
- Grep gate: no `Modifier.weight` inside `RequestrResponsiveTabRow.kt`.
- Confirm each of the 15 design-plan sections maps to a shipped file, and record the mapping in `docs/plans/dossiers/mockup-to-screen-inventory.md` with `file:line` evidence in the existing APPLIED / PARTIAL format.

---

### Testing

**Pure JVM unit tests** (no Robolectric, matching `CommonComponentsTest.kt`):
- `TabStripMathTest.kt` — `tabScrollTarget` leaves an already-visible tab alone; scrolls a right-edge tab to `end - viewport + edgePeek`; scrolls a left-edge tab to `start - edgePeek`; clamps to `0..maxOffsetPx`; mirrors correctly for `isRtl = true`. `clampSelectedTabIndex` keeps selection by id when an item is inserted before it, keeps it by id when an item after it is removed, clamps when the selected item itself is removed, and returns 0 for an empty list. `tabAccessibilityLabel` includes badge count and position. `stripOverflows` is exact at the boundary.
- Per-screen helper tests: `apiTestrResponseTabs`, `inspectorTabsFor` (every protocol combination), `captureChecks` / `overallSeverity`, search-token parsing, filter-predicate compilation, rule-editor validation for all five types, breakpoint queue ordering, API-level diagnostics copy at 24/28/29/31/33/34.

**Robolectric + Compose component tests** (`createComposeRule`, pattern from `MockModeEditorUiTest.kt`). These are the direct proof of the user's non-negotiables:
- Two tabs with different-length labels produce different measured widths — `onNodeWithText(...).fetchSemanticsNode().size.width` differs, and each equals its own text's intrinsic width plus padding within 1dp. This is the regression guard against equal-width squeezing.
- Row height is identical for a 3-character label and a 30-character label, proving nothing wraps.
- With 12 long tabs at 360dp, `scrollState.maxValue > 0`; selecting the last tab leaves it inside the viewport bounds.
- With 4 short tabs at 412dp, `scrollState.maxValue == 0` and no fade is drawn.
- Removing a tab before the selected one keeps the same logical tab selected.
- `onSelect` fires with the correct index for each tab tapped.
- Semantics: every tab exposes `Role.Tab`, exactly one is `selected`, and the container is a `selectableGroup`.
- Keyboard left/right/Home/End move the selection only while the strip has focus; an unfocused strip returns the key event unhandled.
- Reduced motion: with `LocalAnimationsEnabled = false` selection and scroll are instant; `LocalAnimationDurationScale = 0.4f` still completes.
- Nested scroll: a vertical drag inside the inspector body viewer scrolls the body and not the tab strip; a horizontal drag scrolls the strip and not the body.
- Font scale 2.0 via `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f))`: no label is clipped and no tab height is fixed.
- RTL via `@Config(qualifiers = "w360dp-h640dp-ldrtl")`: first tab sits at the right edge and scroll direction is mirrored.

**Roborazzi screenshot baselines** (first use of the already-configured plugin, so the plan includes creating the baseline directory and wiring `verifyRoborazziDebug` into the test task):
- Tab component: pill indicator at index 0 / middle / last; overflowing and non-overflowing; `compact = true`; with icons and badges.
- Configuration matrix per new screen: 360dp portrait, 412dp portrait, 600dp landscape (two-pane), light and dark, font scale 1.0 and 2.0, LTR and RTL.
- Theme matrix: all six glass modes on the tab container, and light/dark across the 10 `AppAccent` values for the tab component only (not every screen).

**Integration verification:**
- Full `./gradlew testDebugUnitTest` passes with the existing 205 test files unchanged except the tab-related edits called out per task.
- Existing `ScreenTransitionResolverTest`, `DeeplinkRouterTest`, `NavPillActiveIndexTest`, `GlassStyleKeysTest`, `GlassSurfaceResolverTest`, `AccentThemeTest`, `RuleConfigValidityTest`, `MockModeEditorUiTest` all still pass.
- After task 5, a diff review confirms the extraction changed no behaviour: same composable names, same parameters, same call order.
- Manual device pass on a 360dp phone and a tablet: every migrated tab row shows full labels, scrolls when it overflows, and keeps the selected tab visible; capture start/stop, CA install, HAR import/export and send-to-API-Testr still work end to end.
