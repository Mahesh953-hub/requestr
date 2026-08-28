package com.example.runtime.frida

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [FridaClient]'s testable, pure pieces: real script-file writing (no placeholder), the
 * `frida` CLI argv builder for each [FridaClient.FridaTarget] (replacing the old hardcoded
 * `com.android.shell` + `--codeshare <raw JS text>` misuse -- `--codeshare` names a hosted
 * Codeshare *project slug*, not inline script text, so that call never injected anything real),
 * and the Frida-message JSON parser. Process spawning ([FridaClient.start]/[FridaClient.detect])
 * needs a real `su`/`frida-server` and is exercised on-device, not here.
 */
@RunWith(RobolectricTestRunner::class)
class FridaClientTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `writeScriptFile writes the real script content to a fresh file`() {
        val script = "console.log('hello from a real script file');"
        val file = FridaClient.writeScriptFile(context, script)

        assertTrue(file.exists())
        assertEquals(script, file.readText())
        assertTrue(file.absolutePath.contains("frida_scripts"))
    }

    @Test
    fun `writeScriptFile writes distinct files across calls, never overwriting with placeholder text`() {
        val fileA = FridaClient.writeScriptFile(context, "// script A")
        val fileB = FridaClient.writeScriptFile(context, "// script B")
        assertEquals("// script A", fileA.readText())
        assertEquals("// script B", fileB.readText())
    }

    @Test
    fun `buildFridaCommand for Spawn uses -f and --no-pause, never --codeshare`() {
        val file = FridaClient.writeScriptFile(context, "// x")
        val command = FridaClient.buildFridaCommand(FridaClient.FridaTarget.Spawn("com.example.target"), file)

        assertTrue(command.contains("-l"))
        assertTrue(command.contains(file.absolutePath))
        assertTrue(command.contains("-f"))
        assertTrue(command.contains("com.example.target"))
        assertTrue(command.contains("--no-pause"))
        assertTrue(!command.contains("--codeshare"))
        // The old bug: always the same hardcoded shell target, regardless of what was asked for.
        assertTrue(!command.contains("com.android.shell"))
    }

    @Test
    fun `buildFridaCommand for Pid uses -p with the given pid`() {
        val file = FridaClient.writeScriptFile(context, "// x")
        val command = FridaClient.buildFridaCommand(FridaClient.FridaTarget.Pid(4242), file)
        assertTrue(command.contains("-p"))
        assertTrue(command.contains("4242"))
        assertTrue(!command.contains("-f"))
    }

    @Test
    fun `buildFridaCommand for Name uses -n with the given process name`() {
        val file = FridaClient.writeScriptFile(context, "// x")
        val command = FridaClient.buildFridaCommand(FridaClient.FridaTarget.Name("com.target:remote"), file)
        assertTrue(command.contains("-n"))
        assertTrue(command.contains("com.target:remote"))
    }

    @Test
    fun `every target loads the script via -l, never via --codeshare`() {
        val file = FridaClient.writeScriptFile(context, "// x")
        val targets = listOf(
            FridaClient.FridaTarget.Spawn("a.b.c"),
            FridaClient.FridaTarget.Pid(1),
            FridaClient.FridaTarget.Name("proc"),
        )
        for (target in targets) {
            val command = FridaClient.buildFridaCommand(target, file)
            assertTrue("$target must load via -l", command.contains("-l"))
            assertTrue("$target must never use --codeshare", !command.contains("--codeshare"))
        }
    }

    // ── parseEvent ─────────────────────────────────────────────────────

    @Test
    fun `parseEvent unwraps a bare send() payload`() {
        val event = FridaClient.parseEvent("""{"type":"response_ready","url":"https://a.example"}""")
        assertNotNull(event)
        assertEquals("response_ready", event!!.type)
        assertTrue(event.data.contains("a.example"))
    }

    @Test
    fun `parseEvent unwraps Frida's own send envelope`() {
        val event = FridaClient.parseEvent("""{"type":"send","payload":{"type":"hook_installed","className":"okhttp3.OkHttpClient${'$'}Builder"}}""")
        assertNotNull(event)
        assertEquals("hook_installed", event!!.type)
        assertTrue(event.data.contains("okhttp3.OkHttpClient"))
    }

    @Test
    fun `parseEvent returns null for non-JSON garbage without throwing`() {
        assertNull(FridaClient.parseEvent("not json at all"))
        assertNull(FridaClient.parseEvent(""))
    }

    @Test
    fun `parseEvent notifies every registered listener`() {
        val received = mutableListOf<FridaEvent>()
        val listener: (FridaEvent) -> Unit = { received.add(it) }
        FridaClient.addListener(listener)
        try {
            FridaClient.parseEvent("""{"type":"exception","errorMessage":"boom"}""")
            assertEquals(1, received.size)
            assertEquals("exception", received.first().type)
        } finally {
            FridaClient.removeListener(listener)
        }
    }

    @Test
    fun `removeListener stops further notifications`() {
        val received = mutableListOf<FridaEvent>()
        val listener: (FridaEvent) -> Unit = { received.add(it) }
        FridaClient.addListener(listener)
        FridaClient.removeListener(listener)
        FridaClient.parseEvent("""{"type":"response_ready"}""")
        assertTrue(received.isEmpty())
    }

    // ── armed-gate (start() must not report success until the script arms) ──

    @Test
    fun `armed event with hooked true satisfies the arm gate`() {
        FridaClient.resetArmGateForTest()
        FridaClient.parseEvent("""{"type":"armed","hooked":true}""")
        assertTrue(FridaClient.lastArmSucceeded())
    }

    @Test
    fun `armed event with hooked false does not satisfy the arm gate`() {
        FridaClient.resetArmGateForTest()
        FridaClient.parseEvent("""{"type":"armed","hooked":false}""")
        assertFalse(FridaClient.lastArmSucceeded())
    }

    @Test
    fun `arm gate stays unsatisfied until an armed event arrives`() {
        FridaClient.resetArmGateForTest()
        assertFalse(FridaClient.lastArmSucceeded())
    }

    @Test
    fun `armed envelope from Frida send wrapper also drives the arm gate`() {
        FridaClient.resetArmGateForTest()
        FridaClient.parseEvent("""{"type":"send","payload":{"type":"armed","hooked":true}}""")
        assertTrue(FridaClient.lastArmSucceeded())
    }
}
