package com.example.interception

import android.os.Build
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §6 follow-up: [MockFixtureLoader] now serves binary `file://` fixtures byte-exact instead of
 * corrupting them via a UTF-8 round trip. This covers the three real call sites in
 * [RuleEnforcer] that wire [MockFixtureLoader.resolve]/[MockFixtureLoader.applyTo] in --
 * `applyRequestModification`, `applyResponseModification`, and `buildMockResponse`.
 *
 * Robolectric, same reason as [RuleEnforcerBodyReplacedFlagTest] (`org.json.JSONObject`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerBinaryFixtureTest {

    // Every byte 0..255 once -- guaranteed invalid UTF-8, so a UTF-8 round trip would corrupt it.
    private val binaryFixtureBytes = ByteArray(256) { it.toByte() }

    private fun binaryFixtureFile(): File {
        val file = File.createTempFile("fixture", ".bin")
        file.writeBytes(binaryFixtureBytes)
        return file
    }

    private fun request() = ParsedHttpMessage(
        isRequest = true,
        method = com.example.data.models.HttpMethod.POST,
        body = "original",
        rawBodyBytes = "original".toByteArray(Charsets.UTF_8),
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false,
        method = null,
        body = "original",
        rawBodyBytes = "original".toByteArray(Charsets.UTF_8),
    )

    @Test
    fun `applyRequestModification wires a binary fixture into replacedBodyBytes byte-exact`() {
        val file = binaryFixtureFile()
        try {
            val req = request()
            RuleEnforcer.applyRequestModification(req, """{"bodyReplace":"file://${file.absolutePath}"}""")

            assertTrue(req.bodyReplaced)
            assertTrue(req.isBodyBinary)
            assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, req.body)
            assertArrayEquals(binaryFixtureBytes, req.replacedBodyBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `applyRequestModification leaves the request untouched when the fixture file is missing`() {
        val req = request()
        RuleEnforcer.applyRequestModification(req, """{"bodyReplace":"file:///no/such/fixture.bin"}""")

        assertFalse("a broken fixture reference must not flip bodyReplaced", req.bodyReplaced)
        assertEquals("original", req.body)
        assertNull(req.replacedBodyBytes)
    }

    @Test
    fun `applyResponseModification wires a binary fixture into replacedBodyBytes byte-exact`() {
        val file = binaryFixtureFile()
        try {
            val resp = response()
            RuleEnforcer.applyResponseModification(resp, """{"bodyReplace":"file://${file.absolutePath}"}""")

            assertTrue(resp.bodyReplaced)
            assertTrue(resp.isBodyBinary)
            assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, resp.body)
            assertArrayEquals(binaryFixtureBytes, resp.replacedBodyBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `applyResponseModification leaves the response untouched when the fixture file is missing`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(resp, """{"bodyReplace":"file:///no/such/fixture.bin"}""")

        assertFalse(resp.bodyReplaced)
        assertEquals("original", resp.body)
        assertNull(resp.replacedBodyBytes)
    }

    @Test
    fun `buildMockResponse serves a binary fixture byte-exact with a correct Content-Length`() {
        val file = binaryFixtureFile()
        try {
            val config = """{"bodyReplace":"file://${file.absolutePath}"}"""
            val response = RuleEnforcer.buildMockResponse(config)

            assertTrue(response.isBodyBinary)
            assertArrayEquals(binaryFixtureBytes, response.replacedBodyBytes)
            assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, response.body)
            assertEquals(binaryFixtureBytes.size.toString(), response.headers["content-length"])
            assertEquals("application/octet-stream", response.headers["content-type"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `buildMockResponse honors an explicit Content-Type over the binary default`() {
        val file = binaryFixtureFile()
        try {
            val config = """{"bodyReplace":"file://${file.absolutePath}","addHeaders":{"Content-Type":"image/png"}}"""
            val response = RuleEnforcer.buildMockResponse(config)
            assertEquals("image/png", response.headers["content-type"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `buildMockResponse falls back to an empty body when the fixture file is missing`() {
        val config = """{"bodyReplace":"file:///no/such/fixture.bin"}"""
        val response = RuleEnforcer.buildMockResponse(config)

        assertFalse(response.isBodyBinary)
        assertEquals("", response.body)
        assertNull(response.replacedBodyBytes)
        assertEquals("0", response.headers["content-length"])
    }

    @Test
    fun `buildMockResponse still serves a text fixture as plain text, unaffected by binary support`() {
        val file = File.createTempFile("fixture", ".json")
        file.writeText("""{"mocked":true}""")
        try {
            val config = """{"bodyReplace":"file://${file.absolutePath}"}"""
            val response = RuleEnforcer.buildMockResponse(config)

            assertFalse(response.isBodyBinary)
            assertEquals("""{"mocked":true}""", response.body)
            assertNull(response.replacedBodyBytes)
            assertTrue(response.headers["content-type"]?.startsWith("application/json") == true)
        } finally {
            file.delete()
        }
    }
}
