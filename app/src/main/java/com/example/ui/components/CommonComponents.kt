package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.models.HttpMethod
import com.example.data.models.MusicTrackItem
import com.example.data.repository.SettingsRepository
// TrafficInterceptorRepository import removed — wordmark dot reads InterceptionStateRepository
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.animateStateColor
import com.example.ui.theme.scaledSpring
import com.example.ui.theme.scaledTween
import com.example.ui.theme.MethodDelete
import com.example.ui.theme.MethodGet
import com.example.ui.theme.MethodHead
import com.example.ui.theme.MethodOptions
import com.example.ui.theme.MethodPatch
import com.example.ui.theme.MethodPost
import com.example.ui.theme.MethodPut
import com.example.ui.theme.MethodWs
import com.example.ui.theme.Status2xx
import com.example.ui.theme.Status3xx
import com.example.ui.theme.Status4xx
import com.example.ui.theme.Status5xx
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * B4: single shared entry point for real haptic feedback, gated once behind
 * [com.example.data.models.UserSettingsState.hapticFeedbackEnabled] instead of being re-checked
 * ad hoc at every call site. Call this from any screen that wants a tactile response for a
 * button press, toggle, or successful action; it silently no-ops when the user has disabled
 * haptics in Settings.
 */
@Composable
fun rememberAppHapticFeedback(): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    val enabled = SettingsRepository.settings.collectAsState().value.hapticFeedbackEnabled
    return { type -> if (enabled) haptics.performHapticFeedback(type) }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    val appliedRadius = cornerRadius ?: settings.cornerRadiusDp.dp
    val shape = RoundedCornerShape(appliedRadius)

    val cardModifier = modifier
        .background(backgroundColor, shape)
        .border(1.dp, borderColor, shape)
        .clip(shape)
        .run { if (onClick != null) this.clickable(onClick = onClick) else this }

    Box(modifier = cardModifier) {
        content()
    }
}

/**
 * requestr wordmark with live interception-status indicator.
 *
 * Renders the brand name in a geometric mono/sans-serif style with the
 * "r" repeated as a subtle visual motif. The trailing dot is alive —
 * it pulses green when capturing, glows amber when armed, and sits
 * muted when idle.  The dot reads [InterceptionStateRepository] so it
 * can never disagree with the Intercept screen's FAB.
 */
@Composable
fun RequestrWordmark(
    modifier: Modifier = Modifier,
    fontSize: Int = 22,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    onClick: (() -> Unit)? = null,
) {
    val interceptionState by InterceptionStateRepository.state.collectAsState()

    // v3.1 B8: an infinite repeatable animation can't be duration-scaled to 0 without
    // flickering forever between its two endpoints, so when animations are disabled we
    // skip the infinite transition altogether and hold a static value instead.
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = com.example.ui.theme.rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedDotPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )
    val dotPulse = if (animationsEnabled) animatedDotPulse else 1f

    val dotColor = when (interceptionState.status) {
        InterceptionStatus.ACTIVE -> InterceptorActiveGreen
        InterceptionStatus.ARMED -> InterceptorIdleAmber
        InterceptionStatus.ERROR -> InterceptorInactiveRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val isCapturing = interceptionState.isActive

    Row(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name in a clean geometric style: "requestr" with the final "r"
        // sharing the weight of the dot so the brand feels balanced.
        Text(
            text = "requestr",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (fontSize * 1.08f).sp,
            letterSpacing = (-0.5).sp,
            color = textColor,
        )

        Spacer(modifier = Modifier.width((fontSize * 0.18f).dp))

        // Alive dot — colour + pulse driven by real interception state.
        Box(
            modifier = Modifier
                .padding(top = (fontSize * 0.22f).dp)
                .size((fontSize * 0.28f).coerceAtLeast(6f).dp)
                .scale(if (isCapturing) dotPulse else 1.0f)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

/**
 * Pure theme-toggle rules, derived from [AppThemeMode] so new families are reachable the moment
 * they are added to the enum (the previous hard-coded 7-entry ring silently hid Pastel Light and
 * Deep Olive Dark). Unit-tested in ThemeToggleTest.
 */
object ThemeToggle {
    private fun resolve(currentKey: String?): AppThemeMode =
        AppThemeMode.fromKey(currentKey) ?: AppThemeMode.MONOCHROME_DARK

    /**
     * Tap: flip light ↔ dark, landing on the family the user last chose for the other side
     * (`selectedLightTheme` / `selectedDarkTheme`). A remembered key that is missing, unknown or
     * on the wrong side falls back to the first family of that side.
     */
    fun flip(currentKey: String?, selectedLightTheme: String?, selectedDarkTheme: String?): AppThemeMode {
        val current = resolve(currentKey)
        return if (current.isLight) {
            AppThemeMode.fromKey(selectedDarkTheme)?.takeIf { !it.isLight } ?: AppThemeMode.darkThemes().first()
        } else {
            AppThemeMode.fromKey(selectedLightTheme)?.takeIf { it.isLight } ?: AppThemeMode.lightThemes().first()
        }
    }

    /** Long-press: advance to the next family on the same side (light → next light, dark → next dark). */
    fun nextInFamily(currentKey: String?): AppThemeMode {
        val current = resolve(currentKey)
        val ring = if (current.isLight) AppThemeMode.lightThemes() else AppThemeMode.darkThemes()
        return ring[(ring.indexOf(current) + 1) % ring.size]
    }

    fun isLight(currentKey: String?): Boolean = resolve(currentKey).isLight
}

/**
 * Everything a theme toggle needs to render and act, resolved once from live settings, so
 * [HeaderThemeCapsule] and any future placement share exactly one implementation of the cycle
 * and the icon selection rather than restating a list that can drift.
 */
@Immutable
data class ThemeToggleUiState(
    val isCurrentLight: Boolean,
    val flipTarget: AppThemeMode,
    val nextTarget: AppThemeMode
) {
    /** Sun when the app is dark (tap → light), moon when light (tap → dark). */
    val icon: ImageVector get() = if (isCurrentLight) Icons.Default.DarkMode else Icons.Default.LightMode
    val flipLabel: String get() = "Switch to ${flipTarget.displayName}"
    val nextLabel: String get() = "Next ${if (isCurrentLight) "light" else "dark"} theme: ${nextTarget.displayName}"

    /** Tap: flip light ↔ dark onto the remembered family (writes through the unified path). */
    fun flip() = SettingsRepository.updateThemeSelection(flipTarget)

    /** Long-press: advance within the current side. */
    fun next() = SettingsRepository.updateThemeSelection(nextTarget)
}

@Composable
fun rememberThemeToggleUiState(): ThemeToggleUiState {
    val settings by SettingsRepository.settings.collectAsState()
    return remember(settings.themeMode, settings.selectedLightTheme, settings.selectedDarkTheme) {
        ThemeToggleUiState(
            isCurrentLight = ThemeToggle.isLight(settings.themeMode),
            flipTarget = ThemeToggle.flip(settings.themeMode, settings.selectedLightTheme, settings.selectedDarkTheme),
            nextTarget = ThemeToggle.nextInFamily(settings.themeMode)
        )
    }
}

@Composable
fun MethodBadge(
    method: HttpMethod,
    modifier: Modifier = Modifier,
    fontSize: Int = 11
) {
    val color = when (method) {
        HttpMethod.GET -> MethodGet
        HttpMethod.POST -> MethodPost
        HttpMethod.PUT -> MethodPut
        HttpMethod.DELETE -> MethodDelete
        HttpMethod.PATCH -> MethodPatch
        HttpMethod.WS -> MethodWs
        HttpMethod.HEAD -> MethodHead
        HttpMethod.OPTIONS -> MethodOptions
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = method.name,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Pure formatting decision behind [StatusCodeBadge] — extracted so the "what text does the
 * badge show" call can be unit-tested without a Compose UI test harness.
 *
 * Audit fix: rows that never decoded a real HTTP/QUIC status (an HTTP/3 OBSERVED/DETECTED row,
 * or a fully opaque undecoded-TLS row) used to be handed [statusCode] `== 0` as a numeric
 * placeholder by upstream mapping code, and this function would happily print the literal `"0"`
 * — a reader could reasonably mistake that for a real (if unusual) status code. [hasKnownStatus]
 * makes that distinction explicit at the call site instead of re-deriving it here from the
 * magic-number `statusCode == 0`, so callers that only ever produce real statuses (every
 * existing caller of [StatusCodeBadge] except the Intercept traffic list) are unaffected by
 * defaulting it to `true`.
 */
fun statusCodeBadgeText(
    statusCode: Int,
    statusMessage: String?,
    hasKnownStatus: Boolean,
    unknownStatusLabel: String,
): String = when {
    !hasKnownStatus -> unknownStatusLabel
    statusMessage.isNullOrBlank() -> "$statusCode"
    else -> "$statusCode $statusMessage"
}

@Composable
fun StatusCodeBadge(
    statusCode: Int,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
    /**
     * False means [statusCode] carries no real, decoded HTTP/QUIC status — see
     * [statusCodeBadgeText]. Defaults to `true` so every pre-existing caller (which always has
     * a real status, even for a failed request) keeps its exact current rendering unchanged.
     * Only a caller that can genuinely produce a statusless row (currently just the Intercept
     * traffic list, for HTTP/3 OBSERVED/DETECTED and opaque undecoded-TLS rows) should ever
     * pass `false` here.
     */
    hasKnownStatus: Boolean = true,
    /**
     * Text shown instead of a fabricated numeric status when [hasKnownStatus] is `false`.
     * Defaults to an em dash; HTTP/3 callers pass the row's own "OBSERVED"/"DETECTED" label
     * (already computed for [Http3RowIndicator]) so the badge agrees with the rest of the row
     * instead of inventing new wording.
     */
    unknownStatusLabel: String = "—",
) {
    val color = when {
        !hasKnownStatus -> MaterialTheme.colorScheme.onSurfaceVariant
        statusCode in 200..299 -> Status2xx
        statusCode in 300..399 -> Status3xx
        statusCode in 400..499 -> Status4xx
        statusCode >= 500 -> Status5xx
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val text = statusCodeBadgeText(statusCode, statusMessage, hasKnownStatus, unknownStatusLabel)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ProtocolBadge(protocol: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = protocol.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Generic colored status pill — a lightweight label with a translucent tinted
 * background and bold colored text. The canonical small status badge for all
 * lanes (connection states, protocols, capability markers, traffic sources).
 *
 * Ownership (plan task 7): after this PR merges, [BadgePill] belongs exclusively
 * to the shared-shell lane (Lane A). All other lanes consume it without editing
 * it. Lane-specific badge variants ([MethodBadge], [StatusCodeBadge],
 * [ProtocolBadge]) remain in this file alongside it.
 *
 * Accessibility: text is rendered with [FontWeight.Bold] for legibility; pass a
 * [modifier] with `Modifier.semantics { }` / `clearAndSetSemantics` when a
 * content description is needed for icon-adjacent pills. Keep font-scale-safe by
 * not wrapping this in a fixed-height container.
 *
 * @param text label displayed inside the pill.
 * @param color ARGB color (e.g. `0xFF10B981`) used for both the 15% translucent
 *   background and the bold foreground text.
 * @param modifier optional [Modifier] for layout/positioning.
 */
@Composable
fun BadgePill(
    text: String,
    color: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(color).copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(color),
        )
    }
}

/**
 * Standard screen header. Renders the floating glass capsule header (DESIGN-SYSTEM §9.3 / §14.7)
 * so every screen that calls it is header-compliant by construction: back capsule, identity
 * capsule with the lowercase title, and the screen's [actions] grouped in ONE trailing glass
 * cluster capsule (plain `IconButton`s inside it render flat on the shared glass). Page
 * background shows through between the shapes — there is no filled toolbar.
 *
 * `onMiniSettings` gets its own trailing gear capsule. The theme toggle lives ONLY on the Home
 * header and in the drawer (B13) and is intentionally absent here.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onMiniSettings: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null
) {
    FloatingCapsuleHeader(
        title = title,
        modifier = modifier,
        onBack = onBack,
        trailingContent = {
            if (actions != null) {
                GlassActionCluster { actions() }
            }
            if (onMiniSettings != null) {
                GlassActionPill(
                    icon = Icons.Default.Settings,
                    contentDescription = "Quick Settings",
                    onClick = onMiniSettings,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * One glass capsule that hosts several actions side by side (a screen's `IconButton`s or
 * [GlassActionPill]s). Children read [LocalInsideGlassCluster] and drop their own capsule so
 * the cluster is the only glass shape; the M3 48dp touch-target enforcement is relaxed to the
 * capsule height so three actions still fit next to a title on a 360dp-wide screen.
 */
@Composable
fun GlassActionCluster(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable RowScope.() -> Unit
) {
    val surface = rememberGlassSurface(GlassRole.HEADER, backdrop)
    Row(
        modifier = modifier
            .height(38.dp)
            .glassSurface(
                surface = surface,
                shape = shape,
                defaultElevation = 6.dp,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.15f),
                borderWidth = 0.8.dp
            )
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(
            LocalInsideGlassCluster provides true,
            LocalMinimumInteractiveComponentSize provides 38.dp
        ) {
            content()
        }
    }
}

/** True while composing inside a [GlassActionCluster]; glass pills render flat there. */
val LocalInsideGlassCluster = compositionLocalOf { false }

@Composable
fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    /**
     * Optional second trailing affordance (Home: sliders icon opening the advanced-filter panel,
     * home-section_prototype_detailed.png annotation 8). Rendered *alongside* the clear button —
     * never instead of it — inside the field's own trailing slot so the tap target stays within
     * the capsule. Null keeps the field byte-for-byte as before for the other screens.
     */
    trailingAction: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            val showClear = query.isNotEmpty() && onClear != null
            if (trailingAction == null) {
                if (showClear) SearchClearButton(onClear!!)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showClear) SearchClearButton(onClear!!)
                    trailingAction()
                }
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SearchClearButton(onClear: () -> Unit) {
    IconButton(onClick = onClear) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Clear",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Mini Player Persistent Bar with Close Action
 * Dismisses and halts playback on tapping close.
 */
@Composable
fun MiniPlayerPersistentBar(
    track: MusicTrackItem,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        borderColor = MaterialTheme.colorScheme.outline,
        onClick = onExpand
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(track.albumCoverHex),
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎧", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = track.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = track.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (isPlaying) {
                    IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Player",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Hovered Floating Pill Navigation Bar
 * Replaces standard bottom bar with floating frosted pill dock.
 * All modules (Home -> Dashboard, Proxy, APIs, Tools, AI, Notes, Flow, Music)
 * have instant tap navigation and lively active pill indicators.
 */
@Composable
fun HoveredPillNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navItems = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("intercept", "Record", Icons.Default.Radar),
        Triple("apis", "APIs", Icons.Default.Public),
        Triple("tools", "Tools", Icons.Default.Tune),
        Triple("notes", "Notes", Icons.AutoMirrored.Filled.Notes),
        Triple("flow", "Flow", Icons.Default.Code),
        Triple("music", "Music", Icons.Default.MusicNote)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(32.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { (route, label, icon) ->
                val isSelected = currentRoute == route

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            else Color.Transparent
                        )
                        .clickable {
                            if (currentRoute != route) {
                                onNavigate(route)
                            }
                        }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom-Anchored Expandable Pill Menu (Improvement #1)
 * Replaces old navigation bars with an animated expandable pill stack
 */
@Composable
fun ExpandablePillMenu(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 135f else 0f,
        animationSpec = scaledSpring(stiffness = 400f, dampingRatio = 0.7f),
        label = "pillRotation"
    )

    val menuItems = listOf(
        Triple("home", "All", "✦"),
        Triple("intercept", "Interceptor", "📡"),
        Triple("apis", "APIs", "⚡"),
        Triple("tools", "Tools", "🛠️"),
        Triple("notes", "Notes", "📝"),
        Triple("flow", "Flow", "🔀"),
        Triple("music", "Music", "🎵")
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Expanded vertical stack with animated staggered visibility
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(scaledTween(150)) + slideInVertically(scaledSpring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f)) { it / 2 },
            exit = fadeOut(scaledTween(150)) + slideOutVertically(scaledSpring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f)) { it / 2 }
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                menuItems.reversed().forEach { (route, label, icon) ->
                    val isActive = currentRoute == route
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable {
                                isExpanded = false
                                onNavigate(route)
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(icon, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Center Floating Trigger Pill [ ✦ ]
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable { isExpanded = !isExpanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Expand Menu",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotationAngle)
            )
        }
    }
}

/**
 * Long-press Contextual Action Menu (Improvement #4)
 */
@Composable
fun ContextualActionMenuDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    actions: List<ContextActionItem>
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outline
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions.forEach { action ->
                        val isEnabled = action.enabled
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .clickable(enabled = isEnabled) {
                                    onDismiss()
                                    action.onClick()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = if (isEnabled) (action.iconTint ?: MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = action.label,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ContextActionItem(
    val label: String,
    val icon: ImageVector,
    val iconTint: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * A plain confirm/dismiss dialog used to surface real blocking conditions (missing CA, no app
 * selection, a genuine failure reason) instead of failing silently.
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = "Cancel",
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outline
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (dismissLabel != null) {
                        Text(
                            text = dismissLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onDismiss() }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = confirmLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onConfirm() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** Formats a real byte counter for compact status readouts. */
fun formatByteCount(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InPageMiniSettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    onNavigateFullSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$title Configuration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            content()

            Spacer(modifier = Modifier.height(16.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                onClick = onNavigateFullSettings
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Full App Settings →",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================================================================
// v6.0 transplant (plan task 6, manifest §5.2/§5.3, DESIGN-RECORD §6/§7/§8) —
// Accepted shared glass components semantically recreated from main against backup
// settings/models. Animation-gated per DESIGN-RECORD §3; glass-style keys read the
// canonical set (FROSTED_GLASS/SOLID_M3/OLED_BLACK/TRANSLUCENT) per §11.
// ============================================================================

/**
 * Composition local providing a drawer-open callback to descendants. Provided by
 * MainActivity's app shell so headers/nav-pills can open the drawer without explicit wiring.
 */
val LocalAppDrawerController = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * Canvas-drawn 3-line "Lucide"-style menu icon (Icons-free). Used as the glass drawer button.
 * DESIGN-RECORD §7.5.
 */
@Composable
fun LucideMenuIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    strokeWidthDp: Dp = 2.dp
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(20.dp)) {
        val stroke = strokeWidthDp.toPx()
        val w = size.width
        val h = size.height
        val startX = 2.dp.toPx()
        val endX = w - 2.dp.toPx()

        drawLine(color = tint, start = Offset(startX, h * 0.25f), end = Offset(endX, h * 0.25f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(startX, h * 0.50f), end = Offset(endX - 3.dp.toPx(), h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(startX, h * 0.75f), end = Offset(endX, h * 0.75f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/**
 * Glass action pill — 38dp glass icon button with spring press-scale, badge dot and haptic.
 * DESIGN-RECORD §7.2. Glass look comes from [rememberGlassSurface] (single source of truth for
 * the six glass modes, real backdrop blur on API 31+ when [backdrop] is present).
 *
 * @param accentTint optional accent that tints the surface (accent @ 0.14), border (accent @ 0.40)
 *                   and icon — e.g. interceptor status colour on the Home header.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassActionPill(
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    badgeCount: Int? = null,
    size: Dp = 38.dp,
    iconSize: Dp = 18.dp,
    customIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null
) {
    val haptics = rememberAppHapticFeedback()
    val insideCluster = LocalInsideGlassCluster.current
    val surface = rememberGlassSurface(GlassRole.HEADER, backdrop)

    // Press state comes from the same interaction source that drives the ripple, so the
    // spring press-scale and the Material indication stay in lock-step.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = scaledSpring(dampingRatio = 0.6f, stiffness = 600f),
        label = "glassPillScale"
    )

    val surfaceModifier = if (insideCluster) {
        Modifier.clip(shape)
    } else {
        Modifier.glassSurface(
            surface = surface,
            shape = shape,
            defaultElevation = 6.dp,
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            spotColor = Color.Black.copy(alpha = 0.15f),
            borderWidth = 0.8.dp,
            tintOverlay = accentTint?.copy(alpha = 0.14f),
            borderColorOverride = accentTint?.copy(alpha = 0.40f)
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .then(surfaceModifier)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = contentDescription,
                onLongClickLabel = longClickLabel,
                onLongClick = if (onLongClick != null) {
                    {
                        haptics(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                } else null,
                onClick = {
                    haptics(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .semantics(mergeDescendants = true) {
                // Icon() already contributes the description for the vector path (merged here);
                // customIcon (canvas-drawn) needs it set explicitly.
                if (contentDescription != null && customIcon != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        if (customIcon != null) {
            customIcon()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = accentTint ?: tint,
                modifier = Modifier.size(iconSize)
            )
        }

        if (badgeCount != null && badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentTint ?: MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// ─── Header capsule primitives (DESIGN-SYSTEM §14.7, mockup `.circle-btn` 42×42 r14) ────────

/**
 * Header icon capsule — the 42dp variant of [GlassActionPill] used by the Home header's
 * "locked" icons (theme, avatar, notifications). Icons never move or scale with scroll.
 */
@Composable
fun HeaderIconCapsule(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    badgeCount: Int? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    size: Dp = 42.dp
) {
    GlassActionPill(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
        badgeCount = badgeCount,
        size = size,
        iconSize = 20.dp,
        shape = shape,
        backdrop = backdrop,
        accentTint = accentTint
    )
}

/** Hamburger capsule that opens the navigation drawer (net-new; drawer was swipe-only). */
@Composable
fun HeaderMenuCapsule(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    size: Dp = 42.dp
) {
    GlassActionPill(
        contentDescription = "Open Navigation Menu",
        onClick = onClick,
        modifier = modifier,
        size = size,
        shape = shape,
        backdrop = backdrop,
        accentTint = accentTint,
        customIcon = { LucideMenuIcon(tint = accentTint ?: tint) }
    )
}

/**
 * Theme toggle as its own floating glass capsule (home_rebranding.md §1.2: a distinct capsule
 * next to the settings gear, not one capsule holding two icons). Same tokens and [accentTint]
 * behaviour as [HeaderIconCapsule]; the cycle and icon come from [rememberThemeToggleUiState],
 * built on [rememberThemeToggleUiState]. Tap flips light ↔ dark, long-press walks the current
 * side, so every [AppThemeMode] stays reachable from the header.
 */
@Composable
fun HeaderThemeCapsule(
    modifier: Modifier = Modifier,
    accentTint: Color? = null,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    shape: Shape = RoundedCornerShape(14.dp),
    size: Dp = 42.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val state = rememberThemeToggleUiState()
    GlassActionPill(
        icon = state.icon,
        contentDescription = state.flipLabel,
        onClick = { state.flip() },
        modifier = modifier,
        tint = tint,
        size = size,
        iconSize = 20.dp,
        shape = shape,
        backdrop = backdrop,
        accentTint = accentTint,
        onLongClick = { state.next() },
        longClickLabel = state.nextLabel
    )
}

/**
 * Identity capsule: `( icon  title • )`. Title changes follow the phase-b §1.5 five-step swap —
 * the capsule keeps its width, the icon stays, the old text fades out, the new text fades in,
 * and only then does the capsule spring to the new width. Every step is gated by the animation
 * settings ([scaledTween] / [scaledSpring]); with animations off the swap is instant.
 *
 * @param accentTint mockup identity pill: bg primary @ 0.12 (light) / 0.20 (dark), border
 *                   primary @ 0.35 / 0.40, text primary. Null keeps the neutral glass look.
 */
@Composable
fun HeaderIdentityCapsule(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    showStatusDot: Boolean = true,
    statusDotColor: Color? = null,
    accentTint: Color? = null,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    shape: Shape = GlassCapsuleShape,
    onClick: (() -> Unit)? = null
) {
    val surface = rememberGlassSurface(GlassRole.HEADER, backdrop)
    val haptics = rememberAppHapticFeedback()
    val density = LocalDensity.current
    val isDark = rememberGlassPalette().isDark

    var displayedTitle by remember { mutableStateOf(title) }
    val textAlpha = remember { Animatable(1f) }
    var measuredTextWidthPx by remember { mutableIntStateOf(0) }
    var lockedTextWidthPx by remember { mutableStateOf<Int?>(null) }
    val fadeOutSpec = scaledTween<Float>(durationMillis = 100)
    val fadeInSpec = scaledTween<Float>(durationMillis = 180)

    LaunchedEffect(title) {
        if (title == displayedTitle) return@LaunchedEffect
        // 2. capsule holds its size, icon remains
        lockedTextWidthPx = measuredTextWidthPx
        // 3. old text fades out
        textAlpha.animateTo(0f, fadeOutSpec)
        // 4. new text appears (in the locked slot)
        displayedTitle = title
        textAlpha.animateTo(1f, fadeInSpec)
        // 5. release → capsule resizes (animateContentSize spring)
        lockedTextWidthPx = null
    }

    val isWordmark = displayedTitle.equals("requestr", ignoreCase = true)
    val textColor = accentTint ?: MaterialTheme.colorScheme.onSurface
    val tintOverlay = accentTint?.copy(alpha = if (isDark) 0.20f else 0.12f)
    val borderOverride = accentTint?.copy(alpha = if (isDark) 0.40f else 0.35f)

    Box(
        modifier = modifier
            .glassSurface(
                surface = surface,
                shape = shape,
                defaultElevation = 6.dp,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f),
                borderWidth = 0.8.dp,
                tintOverlay = tintOverlay,
                borderColorOverride = borderOverride
            )
            .then(
                if (onClick != null) Modifier.clickable {
                    haptics(HapticFeedbackType.LongPress)
                    onClick()
                } else Modifier
            )
            .animateContentSize(
                animationSpec = scaledSpring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentTint ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            val locked = lockedTextWidthPx
            Box(
                modifier = Modifier
                    .then(if (locked != null) Modifier.width(with(density) { locked.toDp() }) else Modifier)
                    .onSizeChanged { if (lockedTextWidthPx == null) measuredTextWidthPx = it.width }
                    .graphicsLayer { alpha = textAlpha.value },
                contentAlignment = Alignment.CenterStart
            ) {
                if (isWordmark) {
                    RequestrWordmark(fontSize = 17)
                } else {
                    Text(
                        text = displayedTitle.lowercase(),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (locked != null) TextOverflow.Clip else TextOverflow.Ellipsis,
                        color = textColor
                    )
                }
            }

            if (showStatusDot && !isWordmark) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusDotColor ?: accentTint ?: MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/**
 * Floating Capsule Header — detached overlay of independent floating rounded controls:
 * leading nav capsule + left-aligned identity capsule + trailing action capsules, with the page
 * background visible between them. Configurable top offset, opacity and glass mode
 * (DESIGN-RECORD §7.3, DESIGN-SYSTEM §9.3 / §14.7).
 *
 * @param backdrop   layer beneath the header to blur (Home hosts the header as an overlay above
 *                   a [GlassBackdropSource]-wrapped list and passes that state).
 * @param accentTint accent for the identity capsule (see [HeaderIdentityCapsule]).
 */
@Composable
fun FloatingCapsuleHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    showWordmarkDot: Boolean = true,
    statusDotColor: Color? = null,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    val settings by SettingsRepository.settings.collectAsState()
    val topPadding = (6 + settings.headerTopPaddingDp).dp

    CompositionLocalProvider(LocalGlassBackdropState provides backdrop) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left-Aligned: Leading Navigation Capsule + Left-Aligned Identity Capsule
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                if (leadingContent != null) {
                    leadingContent()
                } else if (onBack != null) {
                    GlassActionPill(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                } else if (onOpenDrawer != null) {
                    GlassActionPill(
                        contentDescription = "Open Navigation Menu",
                        onClick = onOpenDrawer,
                        customIcon = {
                            LucideMenuIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )
                }

                HeaderIdentityCapsule(
                    title = title,
                    icon = icon,
                    showStatusDot = showWordmarkDot,
                    statusDotColor = statusDotColor,
                    accentTint = accentTint,
                    backdrop = backdrop
                )
            }

            // Trailing Independent Action Capsules
            if (trailingContent != null) {
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailingContent
                )
            }
        }
    }
}

/**
 * Dynamic Frosted Glass Header — alias for [FloatingCapsuleHeader]. DESIGN-RECORD §7.3.
 */
@Composable
fun DynamicGlassHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    showWordmarkDot: Boolean = true,
    statusDotColor: Color? = null,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    accentTint: Color? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    FloatingCapsuleHeader(
        title = title,
        icon = icon,
        modifier = modifier,
        onOpenDrawer = onOpenDrawer,
        onBack = onBack,
        showWordmarkDot = showWordmarkDot,
        statusDotColor = statusDotColor,
        backdrop = backdrop,
        accentTint = accentTint,
        trailingContent = trailingContent
    )
}

// ─── Scroll-reactive header (Home) ───────────────────────────────────────────────────────────

/** Pure scroll → header motion maths (unit-tested in HeaderScrollMotionTest). */
object HeaderScrollMotion {
    /** Scroll distance over which the greeting fades out completely. */
    const val GREETING_COLLAPSE_PX = 120f
    /** How far the greeting slides up while fading (px). */
    const val GREETING_MAX_TRANSLATION_PX = 24f

    fun greetingAlpha(offsetPx: Float, collapsePx: Float = GREETING_COLLAPSE_PX): Float =
        (1f - offsetPx.coerceAtLeast(0f) / collapsePx.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun greetingTranslation(
        offsetPx: Float,
        collapsePx: Float = GREETING_COLLAPSE_PX,
        maxTranslationPx: Float = GREETING_MAX_TRANSLATION_PX
    ): Float = -(1f - greetingAlpha(offsetPx, collapsePx)) * maxTranslationPx

    /**
     * Header-relevant scroll offset for a lazy list: exact while the first item is visible, and
     * pinned at [collapseDistancePx] (fully collapsed) once it has scrolled off — the header only
     * needs the first ~140px, so it never has to estimate item sizes.
     */
    fun offsetFromLazyList(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        collapseDistancePx: Float
    ): Float = if (firstVisibleItemIndex > 0) collapseDistancePx
    else firstVisibleItemScrollOffset.toFloat().coerceIn(0f, collapseDistancePx)
}

/**
 * Header stack that reacts to the page scroll without ever relayouting:
 *  1. [capsuleRow]  — locked; icons and identity capsule never move or scale
 *  2. [greeting]    — fades and slides up over [greetingCollapsePx]
 *  3. [statsPill]   — a [StatsCompressiblePill] (or anything) that compresses, then slides under
 *                    the capsule row
 *
 * Layout height is constant (transforms only), so the host can measure it once through
 * [onHeightChanged] and use it as the list's top content padding. Host it as an overlay above a
 * [GlassBackdropSource]-wrapped list and pass the same [backdrop] so the capsules blur the list.
 */
@Composable
fun ScrollReactiveHeader(
    scrollOffsetPx: () -> Float,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = LocalGlassBackdropState.current,
    greetingCollapsePx: Float = HeaderScrollMotion.GREETING_COLLAPSE_PX,
    onHeightChanged: ((Int) -> Unit)? = null,
    capsuleRow: @Composable RowScope.() -> Unit,
    greeting: (@Composable () -> Unit)? = null,
    statsPill: (@Composable () -> Unit)? = null
) {
    val settings by SettingsRepository.settings.collectAsState()
    val topPadding = (6 + settings.headerTopPaddingDp).dp

    CompositionLocalProvider(LocalGlassBackdropState provides backdrop) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (onHeightChanged != null) Modifier.onSizeChanged { onHeightChanged(it.height) } else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = capsuleRow
            )
            if (greeting != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .graphicsLayer {
                            val offset = scrollOffsetPx()
                            alpha = HeaderScrollMotion.greetingAlpha(offset, greetingCollapsePx)
                            translationY = HeaderScrollMotion.greetingTranslation(offset, greetingCollapsePx)
                        }
                ) {
                    greeting()
                }
            }
            if (statsPill != null) {
                Box(modifier = Modifier.padding(top = 10.dp)) {
                    statsPill()
                }
            }
        }
    }
}

/**
 * Dynamic glass switch — 46×26dp track, spring-animated thumb, haptic. DESIGN-RECORD §7.4.
 */
@Composable
fun DynamicGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberAppHapticFeedback()
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = scaledSpring(dampingRatio = 0.7f, stiffness = 500f),
        label = "switchThumbOffset"
    )

    val trackColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val borderColor = if (checked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor.copy(alpha = if (checked) 0.95f else 0.65f))
            .border(0.6.dp, borderColor, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled) {
                haptics(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, Color.Black.copy(alpha = 0.12f), CircleShape)
        )
    }
}

/**
 * Segmented tabs — 26dp container, animated pill shape/color/border. DESIGN-RECORD §8.1.
 */
@Composable
fun <T> RequestrSegmentedTabs(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: @Composable ((T) -> Unit)? = null,
    icon: ((T) -> ImageVector?)? = null,
    equalWeight: Boolean = false,
    horizontalPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp
) {
    val haptics = rememberAppHapticFeedback()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(26.dp))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val targetBorder = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent

            // Progress-based so a theme switch repaints instantly; only the selection animates.
            val bgAnim by animateStateColor(
                active = isSelected,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = Color.Transparent,
                animationSpec = scaledTween(200),
                label = "tabBg"
            )
            val fgAnim by animateStateColor(
                active = isSelected,
                activeColor = MaterialTheme.colorScheme.onPrimary,
                inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = scaledTween(200),
                label = "tabFg"
            )
            val shapeAnim by animateDpAsState(targetValue = if (isSelected) 24.dp else 10.dp, animationSpec = scaledTween(200), label = "tabShape")

            val itemModifier = if (equalWeight) Modifier.weight(1f) else Modifier
            Box(
                modifier = itemModifier
                    .clip(RoundedCornerShape(shapeAnim))
                    .background(bgAnim)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = targetBorder,
                        shape = RoundedCornerShape(shapeAnim)
                    )
                    .clickable {
                        if (!isSelected) {
                            haptics(HapticFeedbackType.TextHandleMove)
                            onItemSelected(item)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val ic = icon?.invoke(item)
                    if (ic != null) {
                        Icon(
                            imageVector = ic,
                            contentDescription = null,
                            tint = fgAnim,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = label(item),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = fgAnim,
                        maxLines = 1
                    )
                    if (badge != null) {
                        badge(item)
                    }
                }
            }
        }
    }
}

/**
 * Standardized filter chip — selected: full pill 24dp, unselected: 10dp, with count badge.
 * DESIGN-RECORD §8.2.
 */
@Composable
fun RequestrFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null
) {
    val haptics = rememberAppHapticFeedback()
    val shape = if (selected) RoundedCornerShape(24.dp) else RoundedCornerShape(10.dp)
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.8.dp, border, shape)
            .clickable {
                haptics(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg
            )
            if (count != null && count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "$count",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Shared empty-state surface (DESIGN-SYSTEM §15 state vocabulary).
 *
 * Centered icon (48.dp at onSurfaceVariant@0.4) + message (14.sp onSurfaceVariant) + an
 * optional call-to-action. This is the single canonical empty-state composable all lanes
 * consume so empty surfaces stay visually consistent across Home/Music/Reqy/Flow. Lane-owned
 * content (the icon, copy, and CTA) is passed in; the tokens are fixed here.
 *
 * Accessibility: the icon carries a [contentDescription] only when it conveys state not already
 * announced by the message — pass `null` (default) when the icon is purely decorative so
 * TalkBack does not redundantly announce it. The message text wraps and centers, so the
 * surface is font-scale-safe (no fixed-height container). The optional [cta] is announced as
 * whatever control the lane supplies (typically a labeled pill/button).
 *
 * @param icon leading image vector rendered at 48.dp at 40% onSurfaceVariant.
 * @param message explanatory copy, 14.sp centered, wrapping allowed.
 * @param modifier layout modifier (e.g. `Modifier.fillMaxWidth()`).
 * @param contentDescription TalkBack label for the icon, or null when decorative.
 * @param cta optional call-to-action composable (a pill or button) rendered below the message.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    cta: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (cta != null) {
            cta()
        }
    }
}
