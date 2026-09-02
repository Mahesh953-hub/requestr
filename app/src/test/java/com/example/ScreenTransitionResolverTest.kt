package com.example

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Design system — screen_transitions. Locks the direction logic behind the NavHost
 * enter/exit/popEnter/popExit lambdas in `MainActivity.RequestrApp`:
 *
 *  - tab → tab is lateral, and the direction follows the actual movement along the tab bar;
 *  - anything involving a non-tab route is a depth push/pop;
 *  - Onboarding never has a spatial relationship with anything.
 *
 * Pure Kotlin over route strings; no Compose runtime needed.
 */
class ScreenTransitionResolverTest {

    private val tabs = BOTTOM_TAB_ORDER

    @Test
    fun `tab order matches the nav pill left to right`() {
        assertEquals(listOf("home", "intercept", "apis", "tools", "flow", "music"), tabs)
    }

    @Test
    fun `moving right along the tab bar is lateral forward`() {
        assertEquals(ScreenTransition.LATERAL_FORWARD, resolveScreenTransition("home", "intercept", isPop = false))
        assertEquals(ScreenTransition.LATERAL_FORWARD, resolveScreenTransition("home", "music", isPop = false))
        assertEquals(ScreenTransition.LATERAL_FORWARD, resolveScreenTransition("apis", "flow", isPop = false))
    }

    @Test
    fun `moving left along the tab bar is lateral backward`() {
        assertEquals(ScreenTransition.LATERAL_BACKWARD, resolveScreenTransition("intercept", "home", isPop = false))
        assertEquals(ScreenTransition.LATERAL_BACKWARD, resolveScreenTransition("music", "home", isPop = false))
        assertEquals(ScreenTransition.LATERAL_BACKWARD, resolveScreenTransition("flow", "apis", isPop = false))
    }

    @Test
    fun `direction is decided by tab position not by push or pop`() {
        // Back from Tools lands on Home (popUpTo(home) saveState): Home is to the LEFT of Tools,
        // so even though this is a pop the content must slide the "leftwards" way.
        assertEquals(ScreenTransition.LATERAL_BACKWARD, resolveScreenTransition("tools", "home", isPop = true))
        assertEquals(ScreenTransition.LATERAL_FORWARD, resolveScreenTransition("home", "tools", isPop = true))
    }

    @Test
    fun `every ordered tab pair resolves to the direction of travel`() {
        for (from in tabs.indices) {
            for (to in tabs.indices) {
                val expected = when {
                    to > from -> ScreenTransition.LATERAL_FORWARD
                    to < from -> ScreenTransition.LATERAL_BACKWARD
                    else -> ScreenTransition.FADE
                }
                assertEquals(
                    "${tabs[from]} -> ${tabs[to]}",
                    expected,
                    resolveScreenTransition(tabs[from], tabs[to], isPop = false),
                )
            }
        }
    }

    @Test
    fun `hidden tabs do not change relative direction`() {
        // The user hid "apis" and "tools"; Intercept -> Flow is still a move to the right.
        val visible = listOf("home", "intercept", "flow", "music")
        assertEquals(ScreenTransition.LATERAL_FORWARD, resolveScreenTransition("intercept", "flow", isPop = false, tabOrder = visible))
        assertEquals(ScreenTransition.LATERAL_BACKWARD, resolveScreenTransition("music", "intercept", isPop = false, tabOrder = visible))
    }

    @Test
    fun `tab to detail is a depth push`() {
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("home", "settings", isPop = false))
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("apis", "saved_apis", isPop = false))
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("tools", "tool_workspace/{toolId}", isPop = false))
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("home", "notes", isPop = false))
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("home", "chat", isPop = false))
    }

    @Test
    fun `detail back to a tab is a depth pop whether popped or navigated`() {
        assertEquals(ScreenTransition.DEPTH_BACKWARD, resolveScreenTransition("settings", "home", isPop = true))
        assertEquals(ScreenTransition.DEPTH_BACKWARD, resolveScreenTransition("tool_workspace/{toolId}", "tools", isPop = true))
        // SavedApis -> Apis uses popUpTo(saved_apis){inclusive} + navigate, i.e. not a pop.
        assertEquals(ScreenTransition.DEPTH_BACKWARD, resolveScreenTransition("saved_apis", "apis", isPop = false))
        // Chat's own "Home" drawer item navigates to Home.
        assertEquals(ScreenTransition.DEPTH_BACKWARD, resolveScreenTransition("chat", "home", isPop = false))
    }

    @Test
    fun `detail to detail pushes forward and pops backward`() {
        assertEquals(ScreenTransition.DEPTH_FORWARD, resolveScreenTransition("chat", "saved_apis", isPop = false))
        assertEquals(ScreenTransition.DEPTH_BACKWARD, resolveScreenTransition("saved_apis", "chat", isPop = true))
    }

    @Test
    fun `onboarding fades in both directions`() {
        assertEquals(ScreenTransition.FADE, resolveScreenTransition("onboarding", "home", isPop = false))
        assertEquals(ScreenTransition.FADE, resolveScreenTransition("settings", "onboarding", isPop = false))
    }

    @Test
    fun `missing routes fade`() {
        assertEquals(ScreenTransition.FADE, resolveScreenTransition(null, "home", isPop = false))
        assertEquals(ScreenTransition.FADE, resolveScreenTransition("home", null, isPop = false))
    }

    @Test
    fun `re-selecting the same tab fades`() {
        assertEquals(ScreenTransition.FADE, resolveScreenTransition("home", "home", isPop = false))
    }

    // ---- bottom overlay inset (nav pill hosting) ----------------------------------------

    @Test
    fun `routes that reserve the overlay themselves scroll under the pill and get no inset`() {
        for (route in listOf("home", "tools", "music", "notes")) {
            assertEquals(route, 0.dp, bottomOverlayInsetFor(route, 96.dp))
        }
    }

    @Test
    fun `every other route is inset by the overlay height`() {
        for (route in listOf("intercept", "apis", "flow", "settings", "chat", "saved_apis", "recently_opened", "tool_workspace/{toolId}", "onboarding")) {
            assertEquals(route, 96.dp, bottomOverlayInsetFor(route, 96.dp))
        }
    }

    @Test
    fun `under-pill set is exactly the routes whose every mode pads for the overlay`() {
        assertEquals(setOf("home", "tools", "music", "notes"), ROUTES_SCROLLING_UNDER_PILL)
    }

    @Test
    fun `hidden pill means no inset anywhere`() {
        assertEquals(0.dp, bottomOverlayInsetFor("settings", 0.dp))
    }
}
