package com.example.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plugin enable/disable must survive an app restart. The manager is a process singleton, so
 * "restart" is modelled as [PluginManager.resetForTests] followed by the same startup sequence
 * MainActivity runs (register built-ins, then attach the store while scanning) against a
 * [PluginStateStore] whose contents outlive the reset -- exactly what the DataStore-backed
 * store does on disk. `android.util.Log` is a no-op here (unitTests.isReturnDefaultValues).
 */
class PluginManagerPersistenceTest {

    /** In-memory stand-in for [DataStorePluginStateStore]; records every write. */
    private class FakeStore(initial: Set<String> = emptySet()) : PluginStateStore {
        var stored: Set<String> = initial
        var loads = 0
        val writes = mutableListOf<Set<String>>()
        override fun loadDisabledIds(): Set<String> { loads++; return stored }
        override fun saveDisabledIds(ids: Set<String>) { stored = ids; writes += ids }
    }

    private class TestPlugin(
        override val id: String,
        override val name: String = id,
        override val description: String = "test",
        override val version: String = "1",
        override val author: String = "test",
        private val failOnLoad: Boolean = false,
    ) : Plugin {
        var loads = 0
        var unloads = 0
        override fun onLoad() { loads++; if (failOnLoad) error("boom") }
        override fun onUnload() { unloads++ }
    }

    @Before fun clean() = PluginManager.resetForTests()
    @After fun tearDown() = PluginManager.resetForTests()

    @Test
    fun `disable is written to the store as the disabled set`() {
        val store = FakeStore()
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a"), TestPlugin("b")))
        PluginManager.attachStateStore(store)

        PluginManager.setPluginEnabled("a", false)

        assertEquals(setOf("a"), store.stored)
        assertFalse(PluginManager.plugins.value.getValue("a").enabled)
        assertTrue(PluginManager.plugins.value.getValue("b").enabled)
        assertEquals(listOf("b"), PluginManager.getActivePlugins().map { it.id })
    }

    @Test
    fun `re-enabling removes the id and writes the smaller set`() {
        val store = FakeStore()
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a")))
        PluginManager.attachStateStore(store)

        PluginManager.setPluginEnabled("a", false)
        PluginManager.setPluginEnabled("a", true)

        assertEquals(emptySet<String>(), store.stored)
        assertEquals(listOf(setOf("a"), emptySet()), store.writes)
        assertTrue(PluginManager.plugins.value.getValue("a").enabled)
    }

    @Test
    fun `toggling to the state it already has does not rewrite the store`() {
        val store = FakeStore()
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a")))
        PluginManager.attachStateStore(store)

        PluginManager.setPluginEnabled("a", true)

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `round trip - a plugin disabled before restart is disabled after restart`() {
        val store = FakeStore()
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a"), TestPlugin("b")))
        PluginManager.attachStateStore(store)
        PluginManager.setPluginEnabled("b", false)

        // "Restart": process state is gone, the store is not.
        PluginManager.resetForTests()
        assertTrue(PluginManager.plugins.value.isEmpty())

        // MainActivity order: built-ins register before any Context exists, then the store attaches.
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a"), TestPlugin("b")))
        assertTrue("before the store attaches every plugin is enabled", PluginManager.plugins.value.getValue("b").enabled)
        PluginManager.attachStateStore(store)

        assertTrue(PluginManager.plugins.value.getValue("a").enabled)
        assertFalse(PluginManager.plugins.value.getValue("b").enabled)
        assertEquals(listOf("a"), PluginManager.getActivePlugins().map { it.id })
        assertEquals("one load per process start", 2, store.loads)
    }

    @Test
    fun `a plugin registered after the store attached honours the persisted disable`() {
        val store = FakeStore(initial = setOf("ext"))
        PluginManager.attachStateStore(store)

        val result = PluginManager.registerPlugin(TestPlugin("ext"), PluginSource.EXTERNAL)

        assertEquals(PluginRegistration.LOADED, result)
        assertFalse(PluginManager.plugins.value.getValue("ext").enabled)
        assertTrue(PluginManager.getActivePlugins().isEmpty())
    }

    @Test
    fun `unload then re-register keeps the user's disable`() {
        val store = FakeStore()
        PluginManager.attachStateStore(store)
        val ext = TestPlugin("ext")
        PluginManager.registerPlugin(ext)
        PluginManager.setPluginEnabled("ext", false)

        PluginManager.unregisterPlugin("ext")
        assertEquals(1, ext.unloads)
        assertNull(PluginManager.getPlugin("ext"))

        PluginManager.registerPlugin(TestPlugin("ext"))
        assertFalse(PluginManager.plugins.value.getValue("ext").enabled)
        assertEquals(setOf("ext"), store.stored)
    }

    @Test
    fun `attaching a second store is a no-op so a Settings rescan cannot reload stale state`() {
        val first = FakeStore(initial = setOf("a"))
        val second = FakeStore(initial = emptySet())
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a")))
        PluginManager.attachStateStore(first)
        PluginManager.attachStateStore(second)

        assertFalse(PluginManager.plugins.value.getValue("a").enabled)
        assertEquals(0, second.loads)
        PluginManager.setPluginEnabled("a", true)
        assertEquals(emptySet<String>(), first.stored)
        assertTrue(second.writes.isEmpty())
    }

    @Test
    fun `a store that throws on load leaves every plugin enabled and still records later toggles`() {
        val broken = object : PluginStateStore {
            var stored: Set<String>? = null
            override fun loadDisabledIds(): Set<String> = throw IllegalStateException("disk")
            override fun saveDisabledIds(ids: Set<String>) { stored = ids }
        }
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a")))
        PluginManager.attachStateStore(broken)

        assertTrue(PluginManager.plugins.value.getValue("a").enabled)
        PluginManager.setPluginEnabled("a", false)
        assertEquals(setOf("a"), broken.stored)
    }

    @Test
    fun `without any store the toggle still holds for the rest of the process`() {
        PluginManager.registerBuiltinPlugins(listOf(TestPlugin("a")))
        PluginManager.setPluginEnabled("a", false)

        assertFalse(PluginManager.plugins.value.getValue("a").enabled)
        assertEquals(setOf("a"), PluginManager.disabledPluginIds())
    }

    @Test
    fun `registerPlugin reports LOADED, ALREADY_REGISTERED and FAILED distinctly`() {
        assertEquals(PluginRegistration.LOADED, PluginManager.registerPlugin(TestPlugin("a")))
        assertEquals(PluginRegistration.ALREADY_REGISTERED, PluginManager.registerPlugin(TestPlugin("a")))
        assertEquals(PluginRegistration.FAILED, PluginManager.registerPlugin(TestPlugin("bad", failOnLoad = true)))
        assertNull(PluginManager.getPlugin("bad"))
        assertEquals(setOf("a"), PluginManager.plugins.value.keys)
    }

    @Test
    fun `scan result summary reflects the scanner's own counts`() {
        assertEquals(
            "Folder did not exist — created it. Drop a .jar or .dex there and rescan.",
            PluginScanResult(directory = "/d", filesFound = 0, directoryCreated = true).summary(),
        )
        assertEquals(
            "Folder could not be read — check storage permission for /d.",
            PluginScanResult(directory = "/d", filesFound = 0, unreadable = true).summary(),
        )
        assertEquals(
            "Folder is empty — no .jar or .dex files found.",
            PluginScanResult(directory = "/d", filesFound = 0).summary(),
        )
        assertEquals(
            "3 file(s) found: 1 new plugin(s) loaded, 1 already loaded, 1 failed to load (x.jar; see logcat tag PluginManager).",
            PluginScanResult(directory = "/d", filesFound = 3, loaded = listOf("p"), alreadyLoaded = 1, failed = listOf("x.jar")).summary(),
        )
        assertEquals(
            "1 file(s) found: 1 without a Plugin service file (lib.jar).",
            PluginScanResult(directory = "/d", filesFound = 1, notAPlugin = listOf("lib.jar")).summary(),
        )
    }
}
