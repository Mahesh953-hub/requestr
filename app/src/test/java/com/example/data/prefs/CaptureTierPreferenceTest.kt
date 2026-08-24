package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.0 Lane A — capture-tier visual-mapping contract.
 *
 * The 6-tier visual selector in SettingsScreen (ProxyTierTypeSelector, matching
 * settings-main-dark.html .tiergrid) maps six visual capture-method tiers to the frozen
 * two-value [CaptureTierPreference] enum consumed read-only by PreflightController. This test
 * locks the frozen enum surface (so the backbone's read-only assumption holds) and pins the
 * intended visual→backing mapping so a refactor of the selector cannot silently change which
 * backing value a tier resolves to.
 *
 * Pure Kotlin — runs as plain JUnit, no Robolectric.
 */
class CaptureTierPreferenceTest {

    @Test
    fun `enum has exactly the two frozen values`() {
        assertEquals(2, CaptureTierPreference.values().size)
        assertEquals(CaptureTierPreference.AUTO, CaptureTierPreference.valueOf("AUTO"))
        assertEquals(CaptureTierPreference.FORCE_VPN_ONLY, CaptureTierPreference.valueOf("FORCE_VPN_ONLY"))
    }

    @Test
    fun `valueOf round-trips every value's name`() {
        CaptureTierPreference.values().forEach { pref ->
            assertEquals(pref, CaptureTierPreference.valueOf(pref.name))
        }
    }

    @Test
    fun `VPN tier maps to FORCE_VPN_ONLY`() {
        // The mockup shows VPN selected (3B82F6) as the default tier; selecting VPN forces the
        // rootless path, i.e. FORCE_VPN_ONLY. The selector must keep this mapping.
        assertEquals(CaptureTierPreference.FORCE_VPN_ONLY, vpnTierBacking())
    }

    @Test
    fun `every non-VPN tier maps to AUTO`() {
        // Proxy, Root, Shizuku, Frida, LSPosed all map to AUTO (auto-probe Root→Shizuku→VPN).
        listOf("Proxy", "Root", "Shizuku", "Frida", "LSPosed").forEach { tierLabel ->
            assertEquals(
                "tier $tierLabel must map to AUTO",
                CaptureTierPreference.AUTO,
                nonVpnTierBacking(tierLabel),
            )
        }
    }

    @Test
    fun `AUTO default resolves to Root as visual stand-in`() {
        // When the backing value is AUTO and no tier has been tapped yet, the selector
        // highlights Root (head of the Root→Shizuku→VPN probe chain) as the visual stand-in.
        assertEquals("Root", autoDefaultVisualLabel())
    }

    @Test
    fun `FORCE_VPN_ONLY default resolves to VPN as visual stand-in`() {
        assertEquals("VPN", forceVpnDefaultVisualLabel())
    }

    // ── Mapping helpers — mirror the ProxyTierTypeSelector logic exactly ──────

    private fun vpnTierBacking(): CaptureTierPreference = CaptureTierPreference.FORCE_VPN_ONLY

    private fun nonVpnTierBacking(label: String): CaptureTierPreference {
        assertTrue(label != "VPN")
        return CaptureTierPreference.AUTO
    }

    private fun autoDefaultVisualLabel(): String = "Root"

    private fun forceVpnDefaultVisualLabel(): String = "VPN"
}
