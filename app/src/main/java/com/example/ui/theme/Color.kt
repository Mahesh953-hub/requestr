package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. EDITORIAL LIGHT (Universal Light - Ivory/Cream)
// ==========================================
val EditorialLightBackground = Color(0xFFF4F2EC)
val EditorialLightSurface = Color(0xFFFFFFFF)
val EditorialLightSurfaceVariant = Color(0xFFFAF8F3)
val EditorialLightBorder = Color(0xFFE5E2D8)
// Fix 03: The old EditorialLightPrimary (0xFF111111, near-black) was the root
// cause of the "black-on-black" text bug. Composables using `primary` as a
// background and `onSurface` (also 0xFF111111) for text had zero contrast.
// Primary is now the warm orange accent; the old black is moved to secondary.
val EditorialLightPrimary = Color(0xFFF28C28) // Warm Orange
val EditorialLightPrimaryBright = Color(0xFFFB923C) // Brighter orange variant
val EditorialLightAccent = Color(0xFFF28C28) // Warm Orange (same as primary)

// ==========================================
// 2. MONOCHROME OBSIDIAN (Universal Dark)
// ==========================================
val MonochromeDarkBackground = Color(0xFF0A0A0C)
val MonochromeDarkSurface = Color(0xFF141417)
val MonochromeDarkSurfaceVariant = Color(0xFF1C1C21)
val MonochromeDarkBorder = Color(0xFF282830)
val MonochromeDarkPrimary = Color(0xFFFFFFFF)
val MonochromeDarkPrimaryBright = Color(0xFFF0F0F0)
val MonochromeDarkAccent = Color(0xFFF28C28)

// ==========================================
// 3. MIDNIGHT SAPPHIRE
// ==========================================
val MidnightBackground = Color(0xFF070B19)
val MidnightSurface = Color(0xFF0F172A)
val MidnightSurfaceVariant = Color(0xFF1E293B)
val MidnightBorder = Color(0xFF253550)
val MidnightPrimary = Color(0xFF38BDF8)
val MidnightPrimaryBright = Color(0xFF60A5FA)
val MidnightAccentCyan = Color(0xFF6366F1)

// ==========================================
// 4. KYOTO MATCHA (Zen Green)
// ==========================================
val KyotoMatchaBackground = Color(0xFFF3F5F0)
val KyotoMatchaSurface = Color(0xFFFFFFFF)
val KyotoMatchaSurfaceVariant = Color(0xFFE5EBE1)
val KyotoMatchaBorder = Color(0xFFCDD7C7)
val KyotoMatchaPrimary = Color(0xFF2D5A27)
val KyotoMatchaPrimaryBright = Color(0xFF3D7A35)
val KyotoMatchaAccent = Color(0xFF88B04B)

// ==========================================
// 5. CYBER TERMINAL
// ==========================================
val CyberTerminalBackground = Color(0xFF050807)
val CyberTerminalSurface = Color(0xFF0C1410)
val CyberTerminalSurfaceVariant = Color(0xFF132019)
val CyberTerminalBorder = Color(0xFF1E3328)
val CyberTerminalPrimary = Color(0xFF00FF88)
val CyberTerminalPrimaryBright = Color(0xFF55FFAA)
val CyberTerminalAccent = Color(0xFF00D2FF)

// ==========================================
// 6. DUSK TERRACOTTA
// ==========================================
val DuskTerracottaBackground = Color(0xFF140F11)
val DuskTerracottaSurface = Color(0xFF1F161A)
val DuskTerracottaSurfaceVariant = Color(0xFF2D2026)
val DuskTerracottaBorder = Color(0xFF422E37)
val DuskTerracottaPrimary = Color(0xFFE76F51)
val DuskTerracottaPrimaryBright = Color(0xFFF4A261)
val DuskTerracottaAccent = Color(0xFFE29578)

// ==========================================
// 7. SOLAR AMBER
// ==========================================
val SolarAmberBackground = Color(0xFF0D0B08)
val SolarAmberSurface = Color(0xFF19140E)
val SolarAmberSurfaceVariant = Color(0xFF271F15)
val SolarAmberBorder = Color(0xFF3B2F20)
val SolarAmberPrimary = Color(0xFFFFB300)
val SolarAmberPrimaryBright = Color(0xFFFFCA28)
val SolarAmberAccent = Color(0xFFFF5722)

// ==========================================
// COMMON UI & METHOD TOKENS
// ==========================================
val TextPrimary = Color(0xFF111111)
val TextSecondary = Color(0xFF555555)
val TextMuted = Color(0xFF888888)

// Method Badges
val MethodGet = Color(0xFF22C55E)
val MethodPost = Color(0xFF3B82F6)
val MethodPut = Color(0xFFF59E0B)
val MethodDelete = Color(0xFFEF4444)
val MethodPatch = Color(0xFF8B5CF6)
val MethodWs = Color(0xFF06B6D4)
val MethodHead = Color(0xFF64748B)
val MethodOptions = Color(0xFFEC4899)

// Status Badges
val Status2xx = Color(0xFF22C55E)
val Status3xx = Color(0xFF38BDF8)
val Status4xx = Color(0xFFF59E0B)
val Status5xx = Color(0xFFEF4444)

// Interceptor Alive / Inactive Indicator Dot
val InterceptorActiveGreen = Color(0xFF22C55E)
val InterceptorInactiveRed = Color(0xFFEF4444)
val InterceptorIdleAmber = Color(0xFFF59E0B)

// Tier status colors
val TierVpnActive = Color(0xFF3B82F6)
val TierRootActive = Color(0xFF10B981)
val TierShizukuActive = Color(0xFF8B5CF6)
val TierFridaActive = Color(0xFFF43F5E)
val TierLsposedActive = Color(0xFFF59E0B)

// ==========================================
// v6.0 — UNIVERSAL BASE PALETTE (10-accent theme engine port)
// ==========================================
// Ported verbatim from docs/legacy-main/theme-10accent/Color.kt.txt (main's orthogonal
// accent-axis theme model — an AppAccent axis independent of light/dark family). Namespaced
// as Universal*/Accent* so these additions never clash with the EditorialLight*/
// MonochromeDark*/Midnight*/KyotoMatcha*/CyberTerminal*/DuskTerracotta*/SolarAmber* tokens
// above, which remain untouched and stay the source of truth for the existing 7-theme
// AppThemeMode rendering path (see Theme.kt). The AppAccent enum and
// buildLight/DarkColorScheme(accent) built from these tokens live in Theme.kt; wiring them
// into MainActivity/SettingsScreen/OnboardingScreen/AppNavigationDrawer is a separate change.
val UniversalLightBackground = Color(0xFFF8FAFC)
val UniversalLightSurface = Color(0xFFFFFFFF)
val UniversalLightSurfaceVariant = Color(0xFFF1F5F9)
val UniversalLightBorder = Color(0xFFE2E8F0)
val UniversalLightBorderVariant = Color(0xFFCBD5E1)
val UniversalLightTextPrimary = Color(0xFF0F172A)
val UniversalLightTextSecondary = Color(0xFF475569)
val UniversalLightTextMuted = Color(0xFF94A3B8)

val UniversalDarkBackground = Color(0xFF090D16)
val UniversalDarkSurface = Color(0xFF101726)
val UniversalDarkSurfaceVariant = Color(0xFF182238)
val UniversalDarkBorder = Color(0xFF263550)
val UniversalDarkBorderVariant = Color(0xFF334155)
val UniversalDarkTextPrimary = Color(0xFFF8FAFC)
val UniversalDarkTextSecondary = Color(0xFF94A3B8)
val UniversalDarkTextMuted = Color(0xFF64748B)

// 10 dynamic accent palettes (Android 17 / Pixel-style dynamic coloring) — the source of
// truth for the AppAccent enum in Theme.kt.
val AccentIndigo = Color(0xFF6366F1)
val AccentIndigoBright = Color(0xFF818CF8)
val AccentIndigoContainerLight = Color(0xFFEEF2FF)
val AccentIndigoContainerDark = Color(0xFF1E1B4B)

val AccentCyan = Color(0xFF06B6D4)
val AccentCyanBright = Color(0xFF22D3EE)
val AccentCyanContainerLight = Color(0xFFECFEFF)
val AccentCyanContainerDark = Color(0xFF083344)

val AccentEmerald = Color(0xFF10B981)
val AccentEmeraldBright = Color(0xFF34D399)
val AccentEmeraldContainerLight = Color(0xFFECFDF5)
val AccentEmeraldContainerDark = Color(0xFF064E3B)

val AccentOrange = Color(0xFFF97316)
val AccentOrangeBright = Color(0xFFFB923C)
val AccentOrangeContainerLight = Color(0xFFFFF7ED)
val AccentOrangeContainerDark = Color(0xFF431407)

val AccentLime = Color(0xFF84CC16)
val AccentLimeBright = Color(0xFFA3E635)
val AccentLimeContainerLight = Color(0xFFF7FEE7)
val AccentLimeContainerDark = Color(0xFF1A2E05)

val AccentViolet = Color(0xFF8B5CF6)
val AccentVioletBright = Color(0xFFA78BFA)
val AccentVioletContainerLight = Color(0xFFF5F3FF)
val AccentVioletContainerDark = Color(0xFF2E1065)

val AccentRose = Color(0xFFF43F5E)
val AccentRoseBright = Color(0xFFFB7185)
val AccentRoseContainerLight = Color(0xFFFFF1F2)
val AccentRoseContainerDark = Color(0xFF4C0519)

val AccentAmber = Color(0xFFF59E0B)
val AccentAmberBright = Color(0xFFFBBF24)
val AccentAmberContainerLight = Color(0xFFFFFBEB)
val AccentAmberContainerDark = Color(0xFF451A03)

val AccentSapphire = Color(0xFF3B82F6)
val AccentSapphireBright = Color(0xFF60A5FA)
val AccentSapphireContainerLight = Color(0xFFEFF6FF)
val AccentSapphireContainerDark = Color(0xFF172554)

val AccentObsidian = Color(0xFF64748B)
val AccentObsidianBright = Color(0xFF94A3B8)
val AccentObsidianContainerLight = Color(0xFFF8FAFC)
val AccentObsidianContainerDark = Color(0xFF0F172A)
