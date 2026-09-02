package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * Material You (Dynamic Color) tint for the **floating header capsules only**.
 *
 * `docs/designs/home/home_rebranding.md` §1.5 is explicit about both halves of this:
 *
 * - the header's capsule tint must derive from Android's wallpaper-extracted palette where the
 *   device supports it, rather than being hardcoded to the colours visible in the reference
 *   screenshots (which are illustrative of the prototype's fixed theme only);
 * - and this applies to the header **only**. Power Module card colours, the interception status
 *   card and every other surface deliberately stay on the app's static 9-theme token system.
 *   Extending dynamic colour app-wide is called out in the spec as a separate, explicit decision.
 *
 * Returns `null` below API 31, where `dynamicLightColorScheme`/`dynamicDarkColorScheme` do not
 * exist. `null` means "no override", and every capsule primitive
 * (`HeaderIconCapsule`, `HeaderIdentityCapsule`, `HeaderMenuCapsule`, `StatsCompressiblePill`, …)
 * treats a null `accentTint` as "use the current theme's own tokens" — so the fallback is the
 * app's existing static theme system, exactly as the spec requires, with no second code path.
 *
 * Light/dark is decided from the **app's** resolved colour scheme rather than the system setting,
 * because this app's theme families are user-chosen and independent of the system dark-mode flag:
 * a user running the Obsidian family under a light system theme must get the dark dynamic palette,
 * or the capsule tint fights the surface it sits on.
 */
@Composable
fun dynamicHeaderAccent(): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val isDark = surface.luminance() < 0.5f
    return remember(context, isDark) {
        val scheme =
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        scheme.primary
    }
}
