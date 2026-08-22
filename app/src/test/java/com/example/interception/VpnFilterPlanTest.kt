package com.example.interception

import com.example.data.prefs.AppFilterMode
import org.junit.Assert.*
import org.junit.Test

class VpnFilterPlanTest {

    private val self = "com.kailashhh.requestr"

    @Test
    fun `all-apps mode is a blocklist containing self`() {
        val plan = resolveFilterPlan(AppFilterMode.ALL_APPS, emptySet(), self)
        assertEquals(FilterPlan.Blocklist, plan)
    }

    @Test
    fun `selected-with-others is an allowlist excluding self`() {
        val selected = setOf("com.other.app1", "com.other.app2", self)
        val plan = resolveFilterPlan(AppFilterMode.SELECTED_APPS, selected, self)
        assertTrue(plan is FilterPlan.Allowlist)
        val allowlist = plan as FilterPlan.Allowlist
        assertEquals(setOf("com.other.app1", "com.other.app2"), allowlist.packages)
        assertFalse(allowlist.packages.contains(self))
    }

    @Test
    fun `selected-containing-only-self falls back to blocklist`() {
        val plan = resolveFilterPlan(AppFilterMode.SELECTED_APPS, setOf(self), self)
        assertEquals(FilterPlan.Blocklist, plan)
    }

    @Test
    fun `selected-empty falls back to blocklist`() {
        val plan = resolveFilterPlan(AppFilterMode.SELECTED_APPS, emptySet(), self)
        assertEquals(FilterPlan.Blocklist, plan)
    }
}
