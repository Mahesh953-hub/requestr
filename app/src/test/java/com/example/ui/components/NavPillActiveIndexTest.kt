package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Nav pill active-tab resolution. The pill is visible on every root route, including ones that
 * are not tabs (Notes), so a "not a tab" answer has to be representable — the old
 * `indexOfFirst { ... }.coerceAtLeast(0)` silently highlighted Home there.
 */
class NavPillActiveIndexTest {

    private val allRoutes = defaultNavPillRoutes()

    @Test
    fun `default tab order is home intercept apis tools flow music`() {
        assertEquals(listOf("home", "intercept", "apis", "tools", "flow", "music"), allRoutes)
    }

    @Test
    fun `every tab route resolves to its own position`() {
        allRoutes.forEachIndexed { index, route ->
            assertEquals(route, index, resolveActiveNavIndex(allRoutes, route))
        }
    }

    @Test
    fun `non tab root routes resolve to null instead of falling back to home`() {
        listOf(
            "notes",
            "chat",
            "settings",
            "saved_apis",
            "recently_opened",
            "onboarding",
            "tool_workspace/{toolId}",
            "tool_workspace/json_formatter",
            ""
        ).forEach { route ->
            assertNull("route '$route' must not light up a tab", resolveActiveNavIndex(allRoutes, route))
        }
    }

    @Test
    fun `hidden items shift indices and a hidden current route resolves to null`() {
        val visible = visibleNavPillRoutes(setOf("intercept", "flow"))
        assertEquals(listOf("home", "apis", "tools", "music"), visible)
        assertEquals(1, resolveActiveNavIndex(visible, "apis"))
        assertEquals(3, resolveActiveNavIndex(visible, "music"))
        assertNull(resolveActiveNavIndex(visible, "intercept"))
    }

    @Test
    fun `empty tab list never resolves`() {
        assertNull(resolveActiveNavIndex(emptyList(), "home"))
    }
}
