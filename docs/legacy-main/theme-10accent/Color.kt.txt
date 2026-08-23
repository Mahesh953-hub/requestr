package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. UNIVERSAL LIGHT BASE PALETTE
// ==========================================
val UniversalLightBackground = Color(0xFFF8FAFC)
val UniversalLightSurface = Color(0xFFFFFFFF)
val UniversalLightSurfaceVariant = Color(0xFFF1F5F9)
val UniversalLightBorder = Color(0xFFE2E8F0)
val UniversalLightBorderVariant = Color(0xFFCBD5E1)
val UniversalLightTextPrimary = Color(0xFF0F172A)
val UniversalLightTextSecondary = Color(0xFF475569)
val UniversalLightTextMuted = Color(0xFF94A3B8)

// ==========================================
// 2. UNIVERSAL DARK BASE PALETTE
// ==========================================
val UniversalDarkBackground = Color(0xFF090D16)
val UniversalDarkSurface = Color(0xFF101726)
val UniversalDarkSurfaceVariant = Color(0xFF182238)
val UniversalDarkBorder = Color(0xFF263550)
val UniversalDarkBorderVariant = Color(0xFF334155)
val UniversalDarkTextPrimary = Color(0xFFF8FAFC)
val UniversalDarkTextSecondary = Color(0xFF94A3B8)
val UniversalDarkTextMuted = Color(0xFF64748B)

// ==========================================
// 3. DYNAMIC ACCENT PALETTES (Android 17 / Pixel Style)
// ==========================================
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

// ==========================================
// LEGACY THEME COLOR ALIASES (Compatibility)
// ==========================================
val EditorialLightBackground = UniversalLightBackground
val EditorialLightSurface = UniversalLightSurface
val EditorialLightSurfaceVariant = UniversalLightSurfaceVariant
val EditorialLightBorder = UniversalLightBorder
val EditorialLightPrimary = AccentOrange
val EditorialLightPrimaryBright = AccentOrangeBright
val EditorialLightAccent = AccentOrange

val MonochromeDarkBackground = UniversalDarkBackground
val MonochromeDarkSurface = UniversalDarkSurface
val MonochromeDarkSurfaceVariant = UniversalDarkSurfaceVariant
val MonochromeDarkBorder = UniversalDarkBorder
val MonochromeDarkPrimary = Color(0xFFFFFFFF)
val MonochromeDarkPrimaryBright = Color(0xFFF0F0F0)
val MonochromeDarkAccent = AccentOrange

val MidnightBackground = UniversalDarkBackground
val MidnightSurface = UniversalDarkSurface
val MidnightSurfaceVariant = UniversalDarkSurfaceVariant
val MidnightBorder = UniversalDarkBorder
val MidnightPrimary = AccentSapphireBright
val MidnightPrimaryBright = Color(0xFF93C5FD)
val MidnightAccentCyan = AccentIndigo

val KyotoMatchaBackground = UniversalLightBackground
val KyotoMatchaSurface = UniversalLightSurface
val KyotoMatchaSurfaceVariant = UniversalLightSurfaceVariant
val KyotoMatchaBorder = UniversalLightBorder
val KyotoMatchaPrimary = AccentEmerald
val KyotoMatchaPrimaryBright = AccentEmeraldBright
val KyotoMatchaAccent = AccentLime

val CyberTerminalBackground = UniversalDarkBackground
val CyberTerminalSurface = UniversalDarkSurface
val CyberTerminalSurfaceVariant = UniversalDarkSurfaceVariant
val CyberTerminalBorder = UniversalDarkBorder
val CyberTerminalPrimary = Color(0xFF00FF88)
val CyberTerminalPrimaryBright = Color(0xFF55FFAA)
val CyberTerminalAccent = AccentCyan

val DuskTerracottaBackground = UniversalDarkBackground
val DuskTerracottaSurface = UniversalDarkSurface
val DuskTerracottaSurfaceVariant = UniversalDarkSurfaceVariant
val DuskTerracottaBorder = UniversalDarkBorder
val DuskTerracottaPrimary = AccentOrange
val DuskTerracottaPrimaryBright = AccentOrangeBright
val DuskTerracottaAccent = AccentRose

val SolarAmberBackground = UniversalDarkBackground
val SolarAmberSurface = UniversalDarkSurface
val SolarAmberSurfaceVariant = UniversalDarkSurfaceVariant
val SolarAmberBorder = UniversalDarkBorder
val SolarAmberPrimary = AccentAmber
val SolarAmberPrimaryBright = AccentAmberBright
val SolarAmberAccent = AccentOrange

// ==========================================
// COMMON UI & METHOD TOKENS
// ==========================================
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF475569)
val TextMuted = Color(0xFF94A3B8)

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
