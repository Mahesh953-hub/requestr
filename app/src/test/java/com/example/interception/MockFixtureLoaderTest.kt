package com.example.interception

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockFixtureLoaderTest {

    @Test
    fun `literal body passes through unchanged`() {
        assertEquals("hello world", MockFixtureLoader.resolveBodyReplace("hello world"))
    }

    @Test
    fun `null passes through unchanged`() {
        assertNull(MockFixtureLoader.resolveBodyReplace(null))
    }

    @Test
    fun `file reference is resolved to file contents`() {
        val file = File.createTempFile("fixture", ".json")
        file.writeText("""{"mocked":true}""")
        try {
            val result = MockFixtureLoader.resolveBodyReplace("file://${file.absolutePath}")
            assertEquals("""{"mocked":true}""", result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing file falls back to raw value by default`() {
        val result = MockFixtureLoader.resolveBodyReplace("file:///no/such/file/here.json")
        assertEquals("file:///no/such/file/here.json", result)
    }

    @Test
    fun `missing file falls back to custom fallback when provided`() {
        val result = MockFixtureLoader.resolveBodyReplace(
            "file:///no/such/file/here.json",
            fallbackOnError = "{}",
        )
        assertEquals("{}", result)
    }

    @Test
    fun `oversized file falls back rather than reading it fully`() {
        val file = File.createTempFile("fixture", ".bin")
        file.writeText("x".repeat(1024))
        try {
            val result = MockFixtureLoader.resolveBodyReplace(
                "file://${file.absolutePath}",
                maxBytes = 10,
                fallbackOnError = "too-big",
            )
            assertEquals("too-big", result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `isFileFixtureReference detects the scheme`() {
        assertTrue(MockFixtureLoader.isFileFixtureReference("file:///tmp/x.json"))
        assertFalse(MockFixtureLoader.isFileFixtureReference("just a literal body"))
        assertFalse(MockFixtureLoader.isFileFixtureReference(null))
    }
}
