package com.example.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Master plan §3 LOW-ADD (C9) — each rung of [resolveAppDisplayLabel]'s fallback chain
 * (launcher-activity label -> application label -> package name), exercised in isolation.
 */
class AppModelsLabelFallbackTest {

    @Test
    fun `launcher label wins when present and usable`() {
        assertEquals(
            "Chrome",
            resolveAppDisplayLabel(
                launcherLabel = "Chrome",
                applicationLabel = "com.android.chrome",
                packageName = "com.android.chrome"
            )
        )
    }

    @Test
    fun `falls back to application label when launcher label is blank`() {
        assertEquals(
            "My App",
            resolveAppDisplayLabel(
                launcherLabel = "",
                applicationLabel = "My App",
                packageName = "com.example.myapp"
            )
        )
    }

    @Test
    fun `falls back to application label when launcher label is null`() {
        assertEquals(
            "My App",
            resolveAppDisplayLabel(
                launcherLabel = null,
                applicationLabel = "My App",
                packageName = "com.example.myapp"
            )
        )
    }

    @Test
    fun `falls back to application label when launcher label equals the package name`() {
        assertEquals(
            "My App",
            resolveAppDisplayLabel(
                launcherLabel = "com.example.myapp",
                applicationLabel = "My App",
                packageName = "com.example.myapp"
            )
        )
    }

    @Test
    fun `falls back to package name when both labels are unusable`() {
        assertEquals(
            "com.example.myapp",
            resolveAppDisplayLabel(
                launcherLabel = "com.example.myapp",
                applicationLabel = "",
                packageName = "com.example.myapp"
            )
        )
    }

    @Test
    fun `falls back to package name when both labels are null`() {
        assertEquals(
            "com.example.myapp",
            resolveAppDisplayLabel(
                launcherLabel = null,
                applicationLabel = null,
                packageName = "com.example.myapp"
            )
        )
    }

    @Test
    fun `never returns a blank string even with whitespace-only labels`() {
        val result = resolveAppDisplayLabel(
            launcherLabel = "   ",
            applicationLabel = "   ",
            packageName = "com.example.myapp"
        )
        assertEquals("com.example.myapp", result)
        assert(result.isNotBlank())
    }

    @Test
    fun `trims surrounding whitespace from a usable label`() {
        assertEquals(
            "Chrome",
            resolveAppDisplayLabel(
                launcherLabel = "  Chrome  ",
                applicationLabel = null,
                packageName = "com.android.chrome"
            )
        )
    }
}
