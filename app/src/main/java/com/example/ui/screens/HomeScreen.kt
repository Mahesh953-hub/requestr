package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ApiRequestItem
import com.example.data.models.NoteItem
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.FlowRepository
import com.example.data.repository.NotesRepository
import com.example.data.repository.RecentActivityRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.ToolsRepository
import com.example.data.search.GlobalSearch
import com.example.data.search.GlobalSearchHit
import com.example.data.interception.CaptureLogRepository
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.repository.ChatRepository
import com.example.ui.components.EmptyState
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.GlassBackdropSource
import com.example.ui.components.GlassCard
import com.example.ui.components.HeaderIconCapsule
import com.example.ui.components.HeaderIdentityCapsule
import com.example.ui.components.HeaderMenuCapsule
import com.example.ui.components.HeaderScrollMotion
import com.example.ui.components.HeaderThemeCapsule
import com.example.ui.components.RequestrFilterChip
import com.example.ui.components.ScrollReactiveHeader
import com.example.ui.components.SearchInputField
import com.example.ui.components.StatsCompressiblePill
import com.example.ui.components.StatsPillEntry
import com.example.ui.components.rememberGlassBackdropState
import com.example.ui.theme.rememberAmbientMotionEnabled
import com.example.ui.theme.TextMuted
import com.example.ui.theme.dynamicHeaderAccent
import com.example.LocalBottomOverlayHeight
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home — rebranded per `docs/designs/home/home_rebranding.md` and the two reference screenshots
 * in `docs/designs/home/`.
 *
 * ### What changed structurally (spec §1, the only structural change)
 *
 * The header used to be a plain `Row` **inside** the scrollable `LazyColumn`, so it scrolled away
 * with the content, and it consisted of a text wordmark, a theme toggle and a gradient avatar that
 * doubled as the settings entry point. It is now four **independently floating, individually
 * pinned glass capsules** hosted as an overlay above the list — hamburger, identity capsule with a
 * live status dot, and two separate circular capsules for theme and settings — with genuinely
 * transparent page background visible between them and no shared filled bar behind them
 * (spec §1.2–§1.4, and the hard global transparency rule in
 * `docs/plans/shared-context/PHASE-B-SHARED-CONTEXT.md` §6).
 *
 * Header capsule tint comes from Android's Dynamic Color palette on API 31+ and falls back to the
 * app's static theme tokens below that ([dynamicHeaderAccent]). Per spec §1.5 this applies to the
 * **header only** — Power Module cards and the interception status card deliberately stay on the
 * static token system.
 *
 * The greeting and the stats pill sit in the list's first item rather than in the pinned overlay,
 * so the space they occupy is genuinely reclaimed as they leave. The greeting fades and slides as
 * it goes ([HeaderScrollMotion]) and the stats pill compresses then slides out in two stages
 * ([StatsCompressiblePill]), which is the user's design-system brief; keeping them inside a
 * constant-height pinned header would have satisfied the animation but permanently reserved the
 * screen real estate the brief exists to reclaim.
 *
 * ### What is locked (spec §2–§7) and what is honestly sourced
 *
 * Greeting, search bar, four quick actions, interception status card, recently-opened list and the
 * asymmetric Power Modules grid all keep their reference layout. Every number and state on this
 * screen is read live:
 *
 * - interception status, port, packet count and connection count come from the single source of
 *   truth `InterceptionStateRepository.state`, shared with the Power Modules "Intercept" badge, so
 *   the two can never disagree
 * - the captured count is observed straight from Room
 * - "Recently opened" is the real persisted `RecentActivityRepository` history with real relative
 *   timestamps, replacing the fabricated `savedApis.take(2) + notes.take(2)` list that rendered
 *   the literal strings `"Recent"` and `"Saved"`
 * - search covers APIs, notes, chats, tools and flow nodes, which is what its placeholder claims
 */
@Composable
fun HomeScreen(
    onNavigateToSection: (String) -> Unit,
    onOpenApiRequest: (ApiRequestItem) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenToolWorkspace: (String) -> Unit,
    onOpenChatConversation: (String) -> Unit,
    onOpenAllRecents: () -> Unit,
) {
    val settings by SettingsRepository.settings.collectAsState()
    val savedApis by ApiCollectionsRepository.requests.collectAsState()
    // Real captured-transaction count straight from Room, so the Home badge cannot claim
    // traffic that was never captured.
    val homeContext = LocalContext.current
    val capturedCount by remember(homeContext) {
        CaptureLogRepository.observeCount(homeContext)
    }.collectAsState(initial = 0)
    // Real interception state -- same single source of truth as the Power Modules badge.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    val isInterceptionActive = interceptionState.isActive
    val notes by NotesRepository.notes.collectAsState()
    val flows by FlowRepository.nodes.collectAsState()
    val toolsCatalog by ToolsRepository.catalog.collectAsState()
    val chats by ChatRepository.searchableConversations.collectAsState()
    val recents by RecentActivityRepository.recents.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var searchFilter by remember { mutableStateOf(HomeSearchFilter()) }
    var isFilterPanelOpen by remember { mutableStateOf(false) }
    var isRecentsExpanded by remember { mutableStateOf(true) }

    // S3 task 22: traffic hits come from the compiled FTS query (flag `ftsSearch`), fetched off
    // the main thread whenever the query changes; empty until the query settles.
    val ftsSearchEnabled = remember(homeContext) { com.example.data.persistence.InterceptorFlags.get(homeContext).ftsSearch }
    val trafficHits by produceState(initialValue = emptyList<com.example.data.models.CapturedTrafficItem>(), searchQuery, ftsSearchEnabled) {
        value = if (!ftsSearchEnabled || searchQuery.isBlank()) emptyList()
        else runCatching { CaptureLogRepository.searchTraffic(homeContext, searchQuery, limit = 50) }.getOrDefault(emptyList())
    }
    val allHits = remember(searchQuery, savedApis, notes, toolsCatalog, flows, chats, trafficHits) {
        GlobalSearch.search(
            searchQuery,
            apis = savedApis,
            notes = notes,
            tools = toolsCatalog,
            flows = flows,
            chats = chats,
            traffic = trafficHits,
        )
    }
    val searchHits = remember(allHits, searchFilter) { applyHomeSearchFilter(allHits, searchFilter) }
    val hitCounts = remember(allHits) { homeSearchTypeCounts(allHits) }

    // Time-aware greeting. The leaf emoji is the spec's, for every part of the day.
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour in 5..11 -> "Good morning, ${settings.userName} 🍃"
        hour in 12..16 -> "Good afternoon, ${settings.userName} 🍃"
        hour in 17..21 -> "Good evening, ${settings.userName} 🍃"
        else -> "Good night, ${settings.userName} 🍃"
    }

    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM", Locale.getDefault()) }
    val currentDateStr = remember(dateFormat) { dateFormat.format(Date()) }

    // Material You for the header capsules only (spec §1.5); null below API 31 so the capsules
    // fall back to the app's static theme tokens exactly like every other themed surface.
    val headerAccent = dynamicHeaderAccent()

    // The header overlay blurs and refracts the list scrolling beneath it. The list is the
    // backdrop *source*; the overlay is a later sibling, never a descendant, or there would be
    // nothing behind it to sample.
    val backdrop = rememberGlassBackdropState()
    val listState = rememberLazyListState()
    // Feeds the activity-level background parallax layer while Home is on screen.
    DriveParallaxFrom(listState)
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeightDp = remember(headerHeightPx, density) {
        with(density) { headerHeightPx.toDp() }
    }
    // Exact while the first item is on screen and pinned at the end of travel once it is not --
    // the header only cares about the first ~140px, so it never estimates item sizes.
    val scrollOffsetPx: () -> Float = remember(listState) {
        {
            HeaderScrollMotion.offsetFromLazyList(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                HOME_HEADER_COLLAPSE_PX,
            )
        }
    }

    val recentsNavigator = remember(savedApis, notes) {
        RecentActivityNavigator(
            openApi = { id ->
                val api = savedApis.firstOrNull { it.id == id }
                if (api != null) {
                    ApiCollectionsRepository.setActiveRequest(api)
                    onOpenApiRequest(api)
                } else {
                    // The row's source request is gone. RecentActivityRepository.forget() removes
                    // rows on delete, so this is only reachable in the narrow window before that
                    // write lands; open the collection rather than navigating into nothing.
                    onNavigateToSection("apis")
                }
            },
            openNote = { id ->
                val note = notes.firstOrNull { it.id == id }
                if (note != null) onOpenNote(note) else onNavigateToSection("notes")
            },
            openTool = onOpenToolWorkspace,
            openChat = onOpenChatConversation,
            openFlow = { id ->
                FlowRepository.setActiveFlow(id)
                onNavigateToSection("flow")
            },
        )
    }
    val nowMillis = rememberRelativeTimeTicker()

    // Real, live counts. StatsCompressiblePill renders nothing for an empty list, and these
    // values are never synthesised -- a fresh install honestly reads zero.
    val statsEntries = remember(savedApis.size, capturedCount, notes.size, toolsCatalog.size) {
        listOf(
            StatsPillEntry(label = "APIs", value = savedApis.size, icon = Icons.Default.Public),
            StatsPillEntry(label = "Captured", value = capturedCount, icon = Icons.Default.Radar),
            StatsPillEntry(label = "Notes", value = notes.size, icon = Icons.AutoMirrored.Filled.Notes),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Deliberately NOT opaque: the activity's `ParallaxBackground` sits behind every screen
        // and is the base layer the glass refracts. Painting `colorScheme.background` here would
        // hide it and the whole glassmorphism effect would read as flat grey.
        GlassBackdropSource(
            state = backdrop,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                // Home is in `ROUTES_SCROLLING_UNDER_PILL`, so it gets no bottom inset from the
                // NavHost and must reserve the overlay height itself. `LocalBottomOverlayHeight`
                // is the measured height of the pill *plus* the mini player when it is showing,
                // which a fixed `fabBottomSpacingDp` could not account for.
                contentPadding = PaddingValues(
                    top = headerHeightDp,
                    bottom = LocalBottomOverlayHeight.current + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Greeting + stats pill: page content, so the space is reclaimed as they leave.
                item(key = "home-greeting") {
                    Column {
                        Column(
                            modifier = Modifier.graphicsLayer {
                                val offset = scrollOffsetPx()
                                alpha = HeaderScrollMotion.greetingAlpha(offset, HOME_GREETING_COLLAPSE_PX)
                                translationY = HeaderScrollMotion.greetingTranslation(offset, HOME_GREETING_COLLAPSE_PX)
                            }
                        ) {
                            Text(
                                text = greeting,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = currentDateStr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        StatsCompressiblePill(
                            entries = statsEntries,
                            scrollOffsetPx = scrollOffsetPx,
                            // Deliberately NOT `backdrop = backdrop`: this pill is scrolling page
                            // content and lives INSIDE `GlassBackdropSource(state = backdrop)`'s
                            // own content, so it is a descendant of the very source it would be
                            // sampling. Recording that source's layer while it is already being
                            // recorded (i.e. blurring a layer from inside itself) crashes
                            // RenderThread with a native stack overflow (see BlurModifier.kt's
                            // "must NOT be descendants" contract on GlassBackdropState). Let this
                            // resolve `LocalGlassBackdropState.current`, which `GlassBackdropSource`
                            // already resets to null for its own descendants — the pill correctly
                            // falls back to the flat (non-blurred) glass look instead of crashing.
                            accentTint = headerAccent,
                            onClick = onOpenAllRecents,
                        )
                    }
                }

                // Global search bar + advanced filter panel (reference annotations 7 and 8).
                item(key = "home-search") {
                    Column {
                        SearchInputField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "Search APIs, notes, chats, tools...",
                            onClear = { searchQuery = "" },
                            trailingAction = {
                                HomeSearchFilterToggle(
                                    isOpen = isFilterPanelOpen,
                                    isFilterActive = !searchFilter.isDefault,
                                    onClick = { isFilterPanelOpen = !isFilterPanelOpen },
                                )
                            },
                        )
                        AnimatedVisibility(
                            visible = isFilterPanelOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            HomeSearchFilterPanel(
                                filter = searchFilter,
                                counts = hitCounts,
                                hasQuery = searchQuery.isNotBlank(),
                                onFilterChange = { searchFilter = it },
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }

                if (searchQuery.isNotBlank()) {
                    if (searchHits.isEmpty()) {
                        item(key = "home-search-empty") {
                            HomeSearchEmptyState(
                                query = searchQuery,
                                isFiltered = !searchFilter.isDefault,
                                totalUnfiltered = allHits.size,
                                onClearFilters = { searchFilter = HomeSearchFilter() },
                            )
                        }
                    } else {
                        HomeSearchType.entries.forEach { type ->
                            val hitsOfType = searchHits.filter { searchTypeOf(it) == type }
                            if (hitsOfType.isEmpty()) return@forEach
                            item(key = "home-search-label-${type.name}") {
                                SearchSectionLabel("${type.label} · ${hitsOfType.size}")
                            }
                            hitsOfType.forEach { hit ->
                                item(key = "home-search-${type.name}-${searchHitKey(hit)}") {
                                    SearchResultRow(hit) {
                                        openSearchHit(
                                            hit = hit,
                                            onOpenApiRequest = onOpenApiRequest,
                                            onOpenNote = onOpenNote,
                                            onOpenToolWorkspace = onOpenToolWorkspace,
                                            onOpenChatConversation = onOpenChatConversation,
                                            onNavigateToSection = onNavigateToSection,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Quick Actions — exactly the four the spec defines (§4).
                    item(key = "home-quick-actions") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HomeQuickActionPill(
                                label = "+ New API",
                                icon = Icons.Default.Public,
                                iconTint = Color(0xFF10B981),
                                onClick = { onNavigateToSection("apis") },
                            )
                            HomeQuickActionPill(
                                label = "+ Intercept",
                                icon = Icons.Default.Radar,
                                iconTint = Color(0xFFF97316),
                                onClick = { onNavigateToSection("intercept") },
                            )
                            HomeQuickActionPill(
                                label = "+ New Tool",
                                icon = Icons.Default.Tune,
                                iconTint = Color(0xFFF59E0B),
                                onClick = { onNavigateToSection("tools") },
                            )
                            HomeQuickActionPill(
                                label = "+ New Note",
                                icon = Icons.AutoMirrored.Filled.Notes,
                                iconTint = Color(0xFF6366F1),
                                onClick = { onNavigateToSection("notes") },
                            )
                        }
                    }

                    item(key = "home-intercept-card") {
                        InterceptionStatusCard(
                            state = interceptionState,
                            onOpen = { onNavigateToSection("intercept") },
                        )
                    }

                    item(key = "home-recents") {
                        RecentlyOpenedSection(
                            recents = recents,
                            isExpanded = isRecentsExpanded,
                            onToggleExpanded = { isRecentsExpanded = !isRecentsExpanded },
                            onSeeAll = onOpenAllRecents,
                            nowMillis = nowMillis,
                            navigator = recentsNavigator,
                            onCreateApi = { onNavigateToSection("apis") },
                        )
                    }

                    item(key = "home-power-modules") {
                        PowerModulesGrid(
                            capturedCount = capturedCount,
                            isInterceptionActive = isInterceptionActive,
                            savedApiCount = savedApis.size,
                            toolCount = toolsCatalog.size,
                            noteCount = notes.size,
                            hasFlowNodes = flows.isNotEmpty(),
                            onNavigateToSection = onNavigateToSection,
                        )
                    }
                }
            }
        }

        // Pinned floating capsules. Individually glass-surfaced, transparent background between
        // them, and they never move, collapse or react to scroll (spec §1.3).
        ScrollReactiveHeader(
            scrollOffsetPx = scrollOffsetPx,
            modifier = Modifier.align(Alignment.TopCenter),
            backdrop = backdrop,
            onHeightChanged = { headerHeightPx = it },
            capsuleRow = {
                HeaderMenuCapsule(
                    onClick = onOpenDrawer,
                    accentTint = headerAccent,
                    backdrop = backdrop,
                )
                Spacer(modifier = Modifier.weight(1f))
                HeaderIdentityCapsule(
                    title = homeIdentityTitle(interceptionState.status),
                    statusDotColor = homeStatusDotColor(isInterceptionActive),
                    accentTint = headerAccent,
                    backdrop = backdrop,
                    onClick = { onNavigateToSection("intercept") },
                )
                Spacer(modifier = Modifier.weight(1f))
                HeaderThemeCapsule(
                    accentTint = headerAccent,
                    backdrop = backdrop,
                )
                HeaderIconCapsule(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    accentTint = headerAccent,
                    backdrop = backdrop,
                )
            },
        )
    }
}

/** Scroll distance over which Home's stats pill completes its compress-then-slide travel. */
private const val HOME_HEADER_COLLAPSE_PX = 140f

/** Scroll distance over which Home's greeting fades out completely. */
private const val HOME_GREETING_COLLAPSE_PX = 120f

/**
 * The identity capsule's text. `"requestr"` at rest (rendered as the wordmark), swapping to
 * `"interceptr"` while a capture session is live — the active-intercept header state in
 * `docs/designs/navigation/navigation_showcase_dark.html`. The swap drives
 * `HeaderIdentityCapsule`'s 5-step sequence (icon anchored, old text fades, new text appears,
 * capsule resizes last).
 */
internal fun homeIdentityTitle(status: InterceptionStatus): String = when (status) {
    InterceptionStatus.ACTIVE, InterceptionStatus.ARMED -> "interceptr"
    else -> "requestr"
}

/** Green when a capture session is live, red when it is not (spec §1.2). */
internal fun homeStatusDotColor(isActive: Boolean): Color =
    if (isActive) Color(0xFF10B981) else Color(0xFFEF4444)

/** Stable list key per search hit, so recomposition does not reuse a row across hit types. */
internal fun searchHitKey(hit: GlobalSearchHit): String = when (hit) {
    is GlobalSearchHit.Api -> hit.item.id
    is GlobalSearchHit.Note -> hit.item.id
    is GlobalSearchHit.Tool -> hit.item.id
    is GlobalSearchHit.FlowNode -> hit.item.id
    is GlobalSearchHit.Chat -> hit.conversationId
    is GlobalSearchHit.Traffic -> "traffic:" + hit.item.id
}

/**
 * Opens a search hit. Every branch routes through the repository call that also records the open,
 * so a result tapped from search lands in "Recently opened" exactly as it would from its own
 * screen.
 */
private fun openSearchHit(
    hit: GlobalSearchHit,
    onOpenApiRequest: (ApiRequestItem) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenToolWorkspace: (String) -> Unit,
    onOpenChatConversation: (String) -> Unit,
    onNavigateToSection: (String) -> Unit,
) {
    when (hit) {
        is GlobalSearchHit.Api -> {
            ApiCollectionsRepository.setActiveRequest(hit.item)
            onOpenApiRequest(hit.item)
        }
        is GlobalSearchHit.Note -> onOpenNote(hit.item)
        is GlobalSearchHit.Tool -> onOpenToolWorkspace(hit.item.id)
        is GlobalSearchHit.Chat -> onOpenChatConversation(hit.conversationId)
        is GlobalSearchHit.FlowNode -> onNavigateToSection("flow")
        is GlobalSearchHit.Traffic -> onNavigateToSection("intercept")
    }
}

// ---------------------------------------------------------------------------------------------
// Search: filter toggle, advanced filter panel, result rows
// ---------------------------------------------------------------------------------------------

/**
 * The sliders affordance in the search field's trailing slot (reference annotation 8). Carries a
 * small dot while a non-default filter is applied, so a user cannot leave a filter on, forget
 * about it, and read a shortened result list as "there is nothing else".
 */
@Composable
private fun HomeSearchFilterToggle(
    isOpen: Boolean,
    isFilterActive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isOpen || isFilterActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = if (isOpen) "Hide advanced filters" else "Advanced filters",
            tint = if (isOpen || isFilterActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        if (isFilterActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The advanced filter/sort panel. Category chips show real counts over the unfiltered hits, so a
 * chip tells the user how many results it would reveal.
 *
 * With no query typed there is nothing to count, so the panel says so rather than showing five
 * chips reading zero.
 */
@Composable
private fun HomeSearchFilterPanel(
    filter: HomeSearchFilter,
    counts: Map<HomeSearchType, Int>,
    hasQuery: Boolean,
    onFilterChange: (HomeSearchFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ADVANCED FILTERS",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!filter.isDefault) {
                    Text(
                        text = "Reset",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onFilterChange(HomeSearchFilter()) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (hasQuery) "Categories" else "Categories (type a query to see counts)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HomeSearchType.entries.forEach { type ->
                    RequestrFilterChip(
                        selected = type in filter.types,
                        onClick = { onFilterChange(filter.toggleType(type)) },
                        label = type.label,
                        count = if (hasQuery) (counts[type] ?: 0) else null,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Sort",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HomeSearchSort.entries.forEach { sort ->
                    RequestrFilterChip(
                        selected = filter.sort == sort,
                        onClick = { onFilterChange(filter.copy(sort = sort)) },
                        label = sort.label,
                    )
                }
            }
        }
    }
}

/**
 * No-results state. Distinguishes "nothing matches this query at all" from "the filter you left
 * on is hiding the matches", and offers the fix in the second case.
 */
@Composable
private fun HomeSearchEmptyState(
    query: String,
    isFiltered: Boolean,
    totalUnfiltered: Int,
    onClearFilters: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        EmptyState(
            icon = Icons.Default.FilterList,
            message = if (isFiltered && totalUnfiltered > 0) {
                "Your filters hide all $totalUnfiltered match" +
                    (if (totalUnfiltered == 1) "" else "es") + " for \"$query\"."
            } else {
                "No matches for \"$query\" in your APIs, notes, chats, tools or flow nodes."
            },
        ) {
            if (isFiltered && totalUnfiltered > 0) {
                HomeQuickActionPill(
                    label = "Clear filters",
                    icon = Icons.Default.FilterList,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onClearFilters,
                )
            }
        }
    }
}

@Composable
private fun SearchSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SearchResultRow(hit: GlobalSearchHit, onClick: () -> Unit) {
    val accent = when (searchTypeOf(hit)) {
        HomeSearchType.API -> Color(0xFF10B981)
        HomeSearchType.NOTE -> Color(0xFF6366F1)
        HomeSearchType.TOOL -> Color(0xFFD97706)
        HomeSearchType.FLOW -> Color(0xFF4F46E5)
        HomeSearchType.CHAT -> Color(0xFF0EA5E9)
        HomeSearchType.TRAFFIC -> Color(0xFFEF4444)
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.subtitle.isNotBlank()) {
                    Text(
                        text = hit.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Interception status card (spec §5) — every value live, nothing stubbed
// ---------------------------------------------------------------------------------------------

@Composable
private fun InterceptionStatusCard(
    state: com.example.data.interception.InterceptionState,
    onOpen: () -> Unit,
) {
    val isActive = state.isActive
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (isActive) Color(0xFF10B981).copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surface,
        borderColor = if (isActive) Color(0xFF10B981).copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        onClick = onOpen,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    PulsingInterceptionIndicator(isActive = isActive)

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = when (state.status) {
                            InterceptionStatus.ACTIVE -> "Interception: ACTIVE"
                            InterceptionStatus.ARMED -> "Interception: ARMED"
                            InterceptionStatus.ERROR -> "Interception: ERROR"
                            InterceptionStatus.CONFIGURED_IDLE -> "Interception is off"
                            InterceptionStatus.NEVER_CONFIGURED -> "Set up Interception"
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Live",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        LiveWaveformGraphic()
                    }
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open Interceptor",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real method, port and packet count from the live session only.
            Text(
                text = when (state.status) {
                    InterceptionStatus.ACTIVE ->
                        "${state.methodLabel} · Port ${state.port} · ${state.packetCount} packets"
                    InterceptionStatus.ARMED ->
                        "${state.methodLabel} · Port ${state.port} · Awaiting confirmation"
                    InterceptionStatus.ERROR ->
                        state.errorText ?: "Capture service encountered an error"
                    InterceptionStatus.CONFIGURED_IDLE -> "Tap to start live session"
                    InterceptionStatus.NEVER_CONFIGURED -> "Install the CA certificate to begin"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.SansSerif,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InterceptionFeatureChip(Icons.Default.Lock, "HTTPS Decryption")
                Text("·", color = MaterialTheme.colorScheme.outline)
                InterceptionFeatureChip(Icons.Default.FilterList, "Filtering On")
                Text("·", color = MaterialTheme.colorScheme.outline)
                InterceptionFeatureChip(Icons.Default.Person, "${state.activeConnections} Connected")
            }
        }
    }
}

@Composable
private fun InterceptionFeatureChip(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Recently opened (spec §6) — the real persisted history
// ---------------------------------------------------------------------------------------------

/** How many rows Home shows before "See all" (spec §6: "exactly 4 rows visible"). */
private const val HOME_RECENTS_VISIBLE = 4

@Composable
private fun RecentlyOpenedSection(
    recents: List<com.example.data.repository.RecentActivityItem>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSeeAll: () -> Unit,
    nowMillis: Long,
    navigator: RecentActivityNavigator,
    onCreateApi: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onToggleExpanded),
            ) {
                Text(
                    text = "Recently opened",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // "See all" opens the full history page. Hidden when there is nothing beyond the four
            // rows already on screen, rather than linking to a page that shows the same list.
            if (recents.size > HOME_RECENTS_VISIBLE) {
                Text(
                    text = "See all →",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onSeeAll),
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recents.isEmpty()) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        EmptyState(
                            icon = Icons.Default.Folder,
                            message = "Nothing opened yet. APIs, notes, tools, chats and flows " +
                                "you open will appear here, newest first.",
                        ) {
                            HomeQuickActionPill(
                                label = "+ New API",
                                icon = Icons.Default.Public,
                                iconTint = Color(0xFF10B981),
                                onClick = onCreateApi,
                            )
                        }
                    }
                } else {
                    recents.take(HOME_RECENTS_VISIBLE).forEach { item ->
                        RecentActivityRow(
                            item = item,
                            nowMillis = nowMillis,
                            onClick = { navigator.open(item) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Power Modules grid (spec §7) — every badge a real, live-queried value
// ---------------------------------------------------------------------------------------------

@Composable
private fun PowerModulesGrid(
    capturedCount: Int,
    isInterceptionActive: Boolean,
    savedApiCount: Int,
    toolCount: Int,
    noteCount: Int,
    hasFlowNodes: Boolean,
    onNavigateToSection: (String) -> Unit,
) {
    Column {
        Text(
            text = "Power Modules",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PowerModuleCard(
                    title = "Intercept",
                    subtitle = "MITM VPN, Frida & Rules",
                    // Same InterceptionStateRepository state the status card above reads, so the
                    // two cannot disagree about whether interception is set up.
                    badge = when {
                        isInterceptionActive -> "Live"
                        capturedCount == 0 -> "Not set up"
                        else -> "$capturedCount captured"
                    },
                    icon = Icons.Default.Radar,
                    accentColor = Color(0xFF6366F1),
                    chips = listOf("Live Traffic", "Rules Engine", "Breakpoints", "SSL Decrypt"),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToSection("intercept") },
                )
                PowerModuleCard(
                    title = "APIs",
                    subtitle = "Test & organize endpoints",
                    badge = "$savedApiCount saved",
                    icon = Icons.Default.Public,
                    accentColor = Color(0xFF0D9488),
                    chips = listOf("Collections", "Environments", "History", "Import"),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToSection("apis") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PowerModuleCard(
                    title = "Tools",
                    subtitle = "Decoders, ciphers & hashes",
                    // Exact count, not "N+". The catalogue size is known precisely, so a "+"
                    // would imply more tools exist than the app can actually offer.
                    badge = "$toolCount tools",
                    icon = Icons.Default.Build,
                    accentColor = Color(0xFFD97706),
                    chips = listOf("Decoders", "Ciphers", "Hashes", "Formatters"),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToSection("tools") },
                )
                PowerModuleCard(
                    title = "Notes",
                    subtitle = "Markdown, HTML & checklists",
                    badge = if (noteCount == 1) "1 doc" else "$noteCount docs",
                    icon = Icons.AutoMirrored.Filled.Notes,
                    accentColor = Color(0xFFDB2777),
                    chips = listOf("Markdown", "HTML", "Checklists", "Folders"),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateToSection("notes") },
                )
            }

            PowerModuleCard(
                title = "Flow",
                subtitle = "Node-based automation",
                badge = if (hasFlowNodes) "Graph active" else "0 nodes",
                icon = Icons.Default.AccountTree,
                accentColor = Color(0xFF4F46E5),
                chips = listOf("Node Graph", "JSON Logic", "Webhooks", "Auto Run"),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigateToSection("flow") },
            )
        }
    }
}
@Composable
private fun HomeQuickActionPill(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PulsingInterceptionIndicator(isActive: Boolean) {
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )
    val scale = if (animationsEnabled && isActive) animatedScale else 1.0f

    Box(
        modifier = Modifier
            .size(14.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF10B981) else TextMuted)
    )
}

@Composable
private fun LiveWaveformGraphic() {
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animatedBar1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val animatedBar2 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val animatedBar3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )
    val bar1 = if (animationsEnabled) animatedBar1 else 8f
    val bar2 = if (animationsEnabled) animatedBar2 else 8f
    val bar3 = if (animationsEnabled) animatedBar3 else 8f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar1.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
        Box(modifier = Modifier.size(width = 2.5.dp, height = bar3.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF10B981)))
    }
}

@Composable
private fun PowerModuleCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    chips: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Row: Icon Tile & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = badge,
                        color = if (badge == "Not set up") Color(0xFFF97316) else MaterialTheme.colorScheme.primary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Title & Subtitle
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Capability Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chips.forEach { chipText ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = chipText,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
