package com.example.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §3 LOW-ADD: ~20 dynamic generators (Postman-style `{{$name}}` tokens). Covers
 * generator coverage/count, [DynamicVariables.substitute]'s recognized/unrecognized-token
 * behavior, and the "never memoized" contract two references to the same token rely on.
 */
class DynamicVariablesTest {

    @Test
    fun `has at least twenty generators`() {
        assertTrue(
            "expected at least 20 dynamic generators, found ${DynamicVariables.supportedNames.size}",
            DynamicVariables.supportedNames.size >= 20
        )
    }

    @Test
    fun `generate returns a non-null value for every supported name`() {
        DynamicVariables.supportedNames.forEach { name ->
            val value = DynamicVariables.generate(name)
            assertNotNull("generator for \$$name returned null", value)
            assertTrue("generator for \$$name returned a blank value", value!!.isNotBlank())
        }
    }

    @Test
    fun `generate returns null for an unrecognized name`() {
        assertNull(DynamicVariables.generate("notARealGenerator"))
    }

    @Test
    fun `substitute leaves plain text untouched`() {
        assertEquals("hello world", DynamicVariables.substitute("hello world"))
    }

    @Test
    fun `substitute leaves an unrecognized token untouched rather than blanking it`() {
        val template = "id={{\$notARealGenerator}}"
        assertEquals(template, DynamicVariables.substitute(template))
    }

    @Test
    fun `substitute replaces a recognized token with a non-empty value`() {
        val result = DynamicVariables.substitute("token={{\$guid}}")
        assertFalse(result.contains("{{\$guid}}"))
        assertTrue(result.startsWith("token="))
        assertTrue(result.removePrefix("token=").isNotBlank())
    }

    @Test
    fun `substitute replaces multiple distinct tokens independently`() {
        val result = DynamicVariables.substitute("{{\$randomInt}}-{{\$randomWord}}")
        assertFalse(result.contains("{{\$"))
        assertTrue(result.contains("-"))
    }

    @Test
    fun `substitute never memoizes -- two references to the same token can differ`() {
        // Not every generator is guaranteed to differ on every call (e.g. randomBoolean is a
        // coin flip), so use a generator with a huge value space to make a collision the
        // exception, not the point being tested.
        var sawDifferentValues = false
        repeat(20) {
            val result = DynamicVariables.substitute("{{\$guid}}|{{\$guid}}")
            val (first, second) = result.split("|")
            if (first != second) sawDifferentValues = true
        }
        assertTrue("expected two {{\$guid}} references to ever produce different values", sawDifferentValues)
        // Every individual reference must still have actually been substituted, though.
        val result = DynamicVariables.substitute("{{\$guid}}|{{\$guid}}")
        val (first, second) = result.split("|")
        assertNotEquals("", first)
        assertNotEquals("", second)
    }

    @Test
    fun `substitute short-circuits templates with no dollar-token marker`() {
        // {{var}} (env-style, no leading $) is not this class's concern -- it must be left
        // completely alone so EnvironmentRepository/PmBridgeBase can apply their own pass.
        assertEquals("{{var}}", DynamicVariables.substitute("{{var}}"))
    }
}
