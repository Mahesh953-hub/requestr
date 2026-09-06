package com.example.interception.rules.stages

import android.os.Build
import com.example.interception.MitmProxyEngine
import com.example.interception.RequestCryptoManager
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodyCryptoConfig
import com.example.interception.rules.CryptoDirection
import com.example.interception.rules.CryptoScope
import com.example.interception.rules.RuleMetadataHeaders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * S4 task 29: [BodyCryptoStage] -- the typed successor of `RuleEnforcer.applyBodyCrypto`.
 * Scope gating, fail-closed behaviour (blank alias, wrong key, undecodable encoding), the
 * decoded-body contract (cipher runs over the *inflated* bytes, output goes identity-encoded)
 * and the wire-level Content-Length recompute through the real parser + serializer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class BodyCryptoStageTest {
    private val engine = MitmProxyEngine()

    private fun keyFor(alias: String): SecretKey {
        val random = java.util.Random(alias.hashCode().toLong())
        return SecretKeySpec(ByteArray(32).also { random.nextBytes(it) }, "AES")
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun parseRequest(wireBody: ByteArray, contentEncoding: String? = null): ParsedHttpMessage {
        val head = buildString {
            append("POST /upload HTTP/1.1\r\nHost: api.test\r\nContent-Type: application/json\r\n")
            if (contentEncoding != null) append("Content-Encoding: $contentEncoding\r\n")
            append("Content-Length: ${wireBody.size}\r\n\r\n")
        }
        return HttpParser.tryParseRequest(head.toByteArray(Charsets.ISO_8859_1) + wireBody, "api.test", 443, isHttps = true)
            ?: throw AssertionError("fixture must parse")
    }

    private fun parseResponse(wireBody: ByteArray, contentEncoding: String? = null): ParsedHttpMessage {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n")
            if (contentEncoding != null) append("Content-Encoding: $contentEncoding\r\n")
            append("Content-Length: ${wireBody.size}\r\n\r\n")
        }
        return HttpParser.tryParseResponse(head.toByteArray(Charsets.ISO_8859_1) + wireBody)
            ?: throw AssertionError("fixture must parse")
    }

    private fun wireHeaders(wire: ByteArray): Map<String, String> {
        val head = wire.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
        return head.lines().drop(1).associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
    }

    private fun wireBody(wire: ByteArray): ByteArray {
        val headerEnd = wire.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        return wire.copyOfRange(headerEnd, wire.size)
    }

    private fun config(direction: CryptoDirection, scope: CryptoScope = CryptoScope.BOTH, alias: String = "test-alias") =
        BodyCryptoConfig(direction = direction, scope = scope, keyAlias = alias)

    private val plaintext = """{"users":[{"id":1,"name":"Ada"}],"page":1}""".toByteArray(Charsets.UTF_8)

    // ── scope ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `scope gates which direction the rule touches`() {
        val req = parseRequest(plaintext)
        val resp = parseResponse(plaintext)
        assertEquals(BodyCryptoStage.Result.NotApplicable, BodyCryptoStage.applyWith(req, config(CryptoDirection.ENCRYPT, CryptoScope.RESPONSE), "r", ::keyFor))
        assertFalse(req.bodyReplaced)
        assertEquals(BodyCryptoStage.Result.NotApplicable, BodyCryptoStage.applyWith(resp, config(CryptoDirection.ENCRYPT, CryptoScope.REQUEST), "r", ::keyFor))
        assertFalse(resp.bodyReplaced)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(req, config(CryptoDirection.ENCRYPT, CryptoScope.REQUEST), "r", ::keyFor))
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(resp, config(CryptoDirection.ENCRYPT, CryptoScope.RESPONSE), "r", ::keyFor))
    }

    // ── encrypt ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `encrypt replaces the body with IV-prefixed ciphertext flagged binary`() {
        val req = parseRequest(plaintext)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(req, config(CryptoDirection.ENCRYPT), "enc", ::keyFor))
        assertTrue(req.bodyReplaced)
        val cipher = req.replacedBodyBytes ?: throw AssertionError("ciphertext bytes must be set")
        assertEquals("12-byte IV + ciphertext + 16-byte tag", 12 + plaintext.size + 16, cipher.size)
        assertFalse(cipher.contentEquals(plaintext))
        assertTrue(req.isBodyBinary)
        val back = RequestCryptoManager.decryptWith("test-alias", cipher, "AES/GCM/NoPadding", ::keyFor)
        assertArrayEquals(plaintext, (back as RequestCryptoManager.CryptoResult.Success).bytes)
    }

    @Test
    fun `encrypt runs over the inflated body and the wire carries identity-encoded ciphertext with a fresh Content-Length`() {
        val outbound = parseRequest(gzip(plaintext), "gzip")
        assertArrayEquals(plaintext, outbound.decodedBodyBytes)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(outbound, config(CryptoDirection.ENCRYPT), "enc", ::keyFor))
        val wire = engine.serializeRequest(outbound)
        val headers = wireHeaders(wire)
        assertNull(headers["content-encoding"])
        val body = wireBody(wire)
        assertEquals(body.size.toString(), headers["content-length"])
        assertFalse(body.contentEquals(gzip(plaintext)))
        val direct = RequestCryptoManager.decryptWith("test-alias", body, "AES/GCM/NoPadding", ::keyFor)
        assertArrayEquals("cipher ran over the JSON, not the gzip stream", plaintext, (direct as RequestCryptoManager.CryptoResult.Success).bytes)
    }

    // ── decrypt ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `decrypt restores the plaintext as a text body and the serializer recomputes the length`() {
        val cipher = (RequestCryptoManager.encryptWith("test-alias", plaintext, "AES/GCM/NoPadding", ::keyFor) as RequestCryptoManager.CryptoResult.Success).bytes
        val inbound = parseResponse(cipher)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(inbound, config(CryptoDirection.DECRYPT), "dec", ::keyFor))
        assertFalse(inbound.isBodyBinary)
        assertEquals(String(plaintext, Charsets.UTF_8), inbound.body)
        assertArrayEquals(plaintext, inbound.replacedBodyBytes)
        val wire = engine.serializeResponse(inbound)
        assertEquals(plaintext.size.toString(), wireHeaders(wire)["content-length"])
        assertArrayEquals(plaintext, wireBody(wire))
        val clientView = HttpParser.tryParseResponse(wire) ?: throw AssertionError("client must parse")
        assertEquals(String(plaintext, Charsets.UTF_8), clientView.body)
    }

    @Test
    fun `decrypt inflates a gzip compressed ciphertext first`() {
        val cipher = (RequestCryptoManager.encryptWith("test-alias", plaintext, "AES/GCM/NoPadding", ::keyFor) as RequestCryptoManager.CryptoResult.Success).bytes
        val inbound = parseResponse(gzip(cipher), "gzip")
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(inbound, config(CryptoDirection.DECRYPT), "dec", ::keyFor))
        assertEquals(String(plaintext, Charsets.UTF_8), inbound.body)
        assertNull(wireHeaders(engine.serializeResponse(inbound))["content-encoding"])
    }

    @Test
    fun `decrypt with the wrong key fails closed and the message is left untouched`() {
        val cipher = (RequestCryptoManager.encryptWith("alias-a", plaintext, "AES/GCM/NoPadding", ::keyFor) as RequestCryptoManager.CryptoResult.Success).bytes
        val inbound = parseResponse(cipher)
        val result = BodyCryptoStage.applyWith(inbound, config(CryptoDirection.DECRYPT, alias = "alias-b"), "dec", ::keyFor)
        assertTrue(result.toString(), result is BodyCryptoStage.Result.Failed)
        assertFalse(inbound.bodyReplaced)
        assertNull(inbound.replacedBodyBytes)
        assertArrayEquals(cipher, inbound.rawBodyBytes)
    }

    @Test
    fun `garbage ciphertext is a failure, not an exception`() {
        val inbound = parseResponse("definitely not ciphertext".toByteArray())
        assertTrue(BodyCryptoStage.applyWith(inbound, config(CryptoDirection.DECRYPT), "dec", ::keyFor) is BodyCryptoStage.Result.Failed)
    }

    // ── fail-closed configuration ───────────────────────────────────────────────────────

    @Test
    fun `blank key alias is refused rather than skipped`() {
        val req = parseRequest(plaintext)
        val result = BodyCryptoStage.applyWith(req, config(CryptoDirection.ENCRYPT, alias = "  "), "enc", ::keyFor)
        assertTrue(result is BodyCryptoStage.Result.Failed)
        assertTrue((result as BodyCryptoStage.Result.Failed).reason.contains("no key alias"))
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `an undecodable Content-Encoding is refused for both directions`() {
        for (direction in CryptoDirection.values()) {
            val msg = parseRequest("br-compressed-bytes".toByteArray(), "br")
            val result = BodyCryptoStage.applyWith(msg, config(direction), "rule", ::keyFor)
            assertTrue("$direction: $result", result is BodyCryptoStage.Result.Failed)
            assertTrue((result as BodyCryptoStage.Result.Failed).reason.contains("Content-Encoding: br"))
            assertFalse(msg.bodyReplaced)
        }
    }

    @Test
    fun `a key provider that throws surfaces as a failure`() {
        val req = parseRequest(plaintext)
        val result = BodyCryptoStage.applyWith(req, config(CryptoDirection.ENCRYPT), "enc") { throw IllegalStateException("keystore locked") }
        assertTrue(result is BodyCryptoStage.Result.Failed)
        assertFalse(req.bodyReplaced)
    }

    // ── failure response ────────────────────────────────────────────────────────────────

    @Test
    fun `failure response is a 502 stamped with the crypto-failed metadata header`() {
        val resp = BodyCryptoStage.failureResponse("payments", CryptoDirection.DECRYPT, "tag mismatch")
        assertFalse(resp.isRequest)
        assertEquals(502, resp.statusCode)
        assertEquals("Crypto rule failed (requestr)", resp.statusMessage)
        assertEquals("true", resp.headers[RuleMetadataHeaders.CRYPTO_FAILED])
        assertEquals("requestr crypto rule 'payments' failed to decrypt this body and was blocked: tag mismatch", resp.body)
        assertEquals(resp.body.toByteArray().size.toString(), resp.headers["content-length"])
        assertTrue(resp.bodyReplaced)
        val wire = engine.serializeResponse(resp)
        assertEquals(resp.body.toByteArray().size.toString(), wireHeaders(wire)["content-length"])
    }

    @Test
    fun `encrypt then decrypt across two rules round-trips through parser and serializer`() {
        val outbound = parseRequest(plaintext)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(outbound, config(CryptoDirection.ENCRYPT, CryptoScope.REQUEST), "enc", ::keyFor))
        val onWire = wireBody(engine.serializeRequest(outbound))
        val inbound = parseResponse(onWire)
        assertEquals(BodyCryptoStage.Result.Applied, BodyCryptoStage.applyWith(inbound, config(CryptoDirection.DECRYPT, CryptoScope.RESPONSE), "dec", ::keyFor))
        assertArrayEquals(plaintext, wireBody(engine.serializeResponse(inbound)))
    }
}
