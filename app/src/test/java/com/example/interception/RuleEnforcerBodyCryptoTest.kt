package com.example.interception

import android.os.Build
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
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
 * §2.14(b) / security audit fix (fail-open AES crypto): covers [RuleEnforcer.applyBodyCrypto]'s
 * scope-gating and its [RuleEnforcer.CryptoApplyResult] contract. A failure (bad key, blank
 * alias, foreign ciphertext) must come back as [RuleEnforcer.CryptoApplyResult.Failed] -- never
 * silently reported as if nothing happened -- so a caller (see
 * [MitmProxyEngineCryptoFailClosedTest]) can block/substitute the transaction instead of
 * forwarding the untransformed body. The actual AES/GCM encrypt/decrypt round trip is covered by
 * [RequestCryptoManagerTest] via its injectable key-provider seam -- `AndroidKeyStore` isn't
 * available under Robolectric/plain-JVM tests (see `SecureKeyStoreTest`'s documented boundary),
 * so [RuleEnforcer.applyBodyCrypto]'s success path is driven here through
 * [RuleEnforcer.applyBodyCryptoWith] (same logic, injectable key provider) -- in particular the
 * persisted-row contract: after a successful transform the message's `body`/`isBodyBinary` and
 * therefore [HttpBodyDecoder.forStorage] must describe the bytes that were SENT (ciphertext for
 * encrypt, recovered plaintext for decrypt), never the pre-transformation body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerBodyCryptoTest {

    private fun requestMessage(body: String = "hello") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.POST, body = body,
    )

    private fun responseMessage(body: String = "world") = ParsedHttpMessage(
        isRequest = false, method = null, body = body,
    )

    private fun rule(scope: String, alias: String = "test-alias", action: RuleAction = RuleAction.BODY_ENCRYPT_AES) =
        InterceptRuleEntity(
            name = "crypto-rule",
            action = action,
            cryptoScope = scope,
            cryptoKeyAlias = alias,
        )

    @Test
    fun `REQUEST scope skips response messages and reports NotApplicable`() {
        val response = responseMessage()
        val result = RuleEnforcer.applyBodyCrypto(response, rule("REQUEST"), encrypt = true)
        assertFalse(response.bodyReplaced)
        assertNull(response.replacedBodyBytes)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.NotApplicable)
    }

    @Test
    fun `RESPONSE scope skips request messages and reports NotApplicable`() {
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("RESPONSE"), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.NotApplicable)
    }

    @Test
    fun `blank key alias fails closed on encrypt -- message untouched but reported Failed, never silent`() {
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("BOTH", alias = "", action = RuleAction.BODY_ENCRYPT_AES), encrypt = true)
        assertFalse("message must not look like it was successfully encrypted", request.bodyReplaced)
        assertNull(request.replacedBodyBytes)
        assertTrue(
            "a blank alias must surface as Failed so the caller blocks the transaction instead " +
                "of forwarding the untouched plaintext as if encryption succeeded",
            result is RuleEnforcer.CryptoApplyResult.Failed,
        )
    }

    @Test
    fun `blank key alias fails closed on decrypt too`() {
        val response = responseMessage()
        val result = RuleEnforcer.applyBodyCrypto(response, rule("BOTH", alias = "", action = RuleAction.BODY_DECRYPT_AES), encrypt = false)
        assertFalse(response.bodyReplaced)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.Failed)
    }

    @Test
    fun `unrecognized scope fails open to applying (REQUEST message eligible)`() {
        // Not asserting a successful crypto result here (needs real AndroidKeyStore -- see
        // class kdoc), just that an unrecognized scope value doesn't itself skip the message
        // the way REQUEST-only/RESPONSE-only do -- it reaches the (keystore-backed) crypto call
        // rather than being gated out early. A blank alias still fails closed either way.
        val request = requestMessage()
        val result = RuleEnforcer.applyBodyCrypto(request, rule("SOMETHING_ELSE", alias = ""), encrypt = true)
        assertFalse(request.bodyReplaced)
        assertTrue(result is RuleEnforcer.CryptoApplyResult.Failed)
    }

    @Test
    fun `buildCryptoFailureResponse never includes key material and is machine-checkable`() {
        val failure = RuleEnforcer.buildCryptoFailureResponse(
            ruleName = "my-rule",
            encrypt = true,
            reason = "encrypt failed: no key alias configured for this rule",
        )
        assertTrue(failure.statusCode >= 400)
        assertTrue(failure.bodyReplaced)
        assertTrue(failure.body.contains("my-rule"))
        assertTrue(failure.body.contains("encrypt failed"))
        assertTrue("must carry a marker distinguishing this from a real upstream 5xx", failure.headers["x-requestr-crypto-failed"] == "true")
    }

    // ── success path via the key-provider seam: the row must describe what was sent ─────────

    private fun keyFor(alias: String): SecretKey {
        val random = java.util.Random(alias.hashCode().toLong())
        return SecretKeySpec(ByteArray(32).also { random.nextBytes(it) }, "AES")
    }

    @Test
    fun `successful encrypt of a JSON body leaves body and isBodyBinary describing the ciphertext, and the row persists ciphertext`() {
        val plaintext = """{"secret":"plaintext-value"}"""
        val request = ParsedHttpMessage(
            isRequest = true, method = HttpMethod.POST, body = plaintext,
            rawBodyBytes = plaintext.toByteArray(Charsets.UTF_8),
        )

        val result = RuleEnforcer.applyBodyCryptoWith(request, rule("REQUEST"), encrypt = true, ::keyFor)

        assertTrue(result is RuleEnforcer.CryptoApplyResult.Applied)
        assertTrue(request.bodyReplaced)
        val ciphertext = request.replacedBodyBytes ?: throw AssertionError("ciphertext must be set")
        assertTrue("ciphertext must be flagged binary", request.isBodyBinary)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, request.body)
        val storage = HttpBodyDecoder.forStorage(request)
        assertTrue(storage.isBinary)
        assertArrayEquals(ciphertext, storage.bytes)
        assertEquals(HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, storage.text)
        assertFalse("plaintext must not leak into the persisted row", storage.text.contains("plaintext-value"))
        assertEquals(ciphertext.size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `successful decrypt of ciphertext back to JSON persists the plaintext as a text row`() {
        val plaintext = """{"secret":"recovered"}"""
        val ciphertext = (RequestCryptoManager.encryptWith("test-alias", plaintext.toByteArray(Charsets.UTF_8), "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes
        val response = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = ciphertext, decodedBodyBytes = ciphertext,
        )

        val result = RuleEnforcer.applyBodyCryptoWith(response, rule("RESPONSE", action = RuleAction.BODY_DECRYPT_AES), encrypt = false, ::keyFor)

        assertTrue(result is RuleEnforcer.CryptoApplyResult.Applied)
        assertFalse("decrypted JSON must no longer be flagged binary", response.isBodyBinary)
        assertEquals(plaintext, response.body)
        val storage = HttpBodyDecoder.forStorage(response)
        assertFalse(storage.isBinary)
        assertNull(storage.bytes)
        assertEquals(plaintext, storage.text)
        assertEquals(plaintext.toByteArray(Charsets.UTF_8).size.toLong(), storage.trueSizeBytes)
    }

    @Test
    fun `successful decrypt of a binary payload keeps it a binary row holding the recovered bytes, not the ciphertext`() {
        val payload = ByteArray(64) { i -> (0xF0 + (i % 16)).toByte() }
        val ciphertext = (RequestCryptoManager.encryptWith("test-alias", payload, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes
        val response = ParsedHttpMessage(
            isRequest = false, method = null, body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true, rawBodyBytes = ciphertext, decodedBodyBytes = ciphertext,
        )

        RuleEnforcer.applyBodyCryptoWith(response, rule("RESPONSE", action = RuleAction.BODY_DECRYPT_AES), encrypt = false, ::keyFor)

        assertTrue(response.isBodyBinary)
        val storage = HttpBodyDecoder.forStorage(response)
        assertTrue(storage.isBinary)
        assertArrayEquals(payload, storage.bytes)
        assertFalse("row must not hold the ciphertext", storage.bytes!!.contentEquals(ciphertext))
    }

    // ── Content-Encoding ordering: decode -> transform -> send identity ─────────────────────

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun parseResponse(wireBody: ByteArray, contentEncoding: String?): ParsedHttpMessage {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n")
            if (contentEncoding != null) append("Content-Encoding: $contentEncoding\r\n")
            append("Content-Length: ${wireBody.size}\r\n\r\n")
        }
        return HttpParser.tryParseResponse(head.toByteArray(Charsets.ISO_8859_1) + wireBody)
            ?: throw AssertionError("fixture must parse")
    }

    private fun parseRequest(wireBody: ByteArray, contentEncoding: String?): ParsedHttpMessage {
        val head = buildString {
            append("POST /upload HTTP/1.1\r\nHost: api.test\r\nContent-Type: application/json\r\n")
            if (contentEncoding != null) append("Content-Encoding: $contentEncoding\r\n")
            append("Content-Length: ${wireBody.size}\r\n\r\n")
        }
        return HttpParser.tryParseRequest(head.toByteArray(Charsets.ISO_8859_1) + wireBody, "api.test", 443, isHttps = true)
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

    @Test
    fun `ENCRYPT then DECRYPT round-trips a gzip compressed body back to its content bytes through the real parser and serializer`() {
        val plaintext = """{"users":[{"id":1,"name":"Ada"},{"id":2,"name":"Grace"}],"page":1}""".toByteArray(Charsets.UTF_8)
        val engine = MitmProxyEngine()

        // Leg 1: gzip'd request body arrives, BODY_ENCRYPT_AES runs, message is serialized.
        val outbound = parseRequest(gzip(plaintext), "gzip")
        assertArrayEquals("parser fixture must have inflated the body", plaintext, outbound.decodedBodyBytes)
        val enc = RuleEnforcer.applyBodyCryptoWith(outbound, rule("REQUEST"), encrypt = true, ::keyFor)
        assertTrue(enc.toString(), enc is RuleEnforcer.CryptoApplyResult.Applied)
        val encryptedWire = engine.serializeRequest(outbound)
        val encryptedHeaders = wireHeaders(encryptedWire)
        assertNull("ciphertext is not gzip -- Content-Encoding must be gone", encryptedHeaders["content-encoding"])
        val ciphertextOnWire = wireBody(encryptedWire)
        assertEquals(ciphertextOnWire.size.toString(), encryptedHeaders["content-length"])
        assertFalse("wire must not carry the plaintext", ciphertextOnWire.contentEquals(plaintext))
        assertFalse("wire must not carry the gzip bytes either", ciphertextOnWire.contentEquals(gzip(plaintext)))

        // The cipher must have run over the INFLATED content, not the gzip bytes: decrypting
        // the wire ciphertext directly yields the JSON, not a gzip stream.
        val direct = RequestCryptoManager.decryptWith("test-alias", ciphertextOnWire, "AES/GCM/NoPadding", ::keyFor)
        assertArrayEquals(plaintext, (direct as RequestCryptoManager.CryptoResult.Success).bytes)

        // Leg 2: the ciphertext comes back (identity-encoded, as it was sent), BODY_DECRYPT_AES runs.
        val inbound = parseResponse(ciphertextOnWire, null)
        val dec = RuleEnforcer.applyBodyCryptoWith(inbound, rule("RESPONSE", action = RuleAction.BODY_DECRYPT_AES), encrypt = false, ::keyFor)
        assertTrue(dec.toString(), dec is RuleEnforcer.CryptoApplyResult.Applied)
        val decryptedWire = engine.serializeResponse(inbound)
        val decryptedHeaders = wireHeaders(decryptedWire)
        assertNull("identity content must not claim an encoding", decryptedHeaders["content-encoding"])
        assertArrayEquals("round trip must recover the original content bytes", plaintext, wireBody(decryptedWire))
        assertEquals(plaintext.size.toString(), decryptedHeaders["content-length"])
        // And what the client can actually parse off that wire is the original JSON.
        val clientView = HttpParser.tryParseResponse(decryptedWire) ?: throw AssertionError("client must be able to parse")
        assertEquals(String(plaintext, Charsets.UTF_8), clientView.body)
        assertFalse(clientView.isBodyBinary)
    }

    @Test
    fun `DECRYPT inflates a gzip compressed ciphertext before decrypting`() {
        val plaintext = """{"ok":true}""".toByteArray(Charsets.UTF_8)
        val ciphertext = (RequestCryptoManager.encryptWith("test-alias", plaintext, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes
        // A server that gzips the opaque blob on the way back: still round-trips.
        val inbound = parseResponse(gzip(ciphertext), "gzip")

        val dec = RuleEnforcer.applyBodyCryptoWith(inbound, rule("RESPONSE", action = RuleAction.BODY_DECRYPT_AES), encrypt = false, ::keyFor)

        assertTrue(dec.toString(), dec is RuleEnforcer.CryptoApplyResult.Applied)
        assertArrayEquals(plaintext, wireBody(MitmProxyEngine().serializeResponse(inbound)))
    }

    @Test
    fun `ENCRYPT refuses a brotli body it cannot decode, fails closed with the encoding named, and leaves the message untouched`() {
        val outbound = parseRequest(byteArrayOf(0x1b, 0x03, 0x00, 0x11, 0x22, 0x33), "br")

        val result = RuleEnforcer.applyBodyCryptoWith(outbound, rule("REQUEST"), encrypt = true, ::keyFor)

        assertTrue(result.toString(), result is RuleEnforcer.CryptoApplyResult.Failed)
        val reason = (result as RuleEnforcer.CryptoApplyResult.Failed).reason
        assertTrue(reason, reason.contains("Content-Encoding: br"))
        assertTrue(reason, reason.contains("BODY_ENCRYPT_AES refused"))
        assertFalse(outbound.bodyReplaced)
        assertNull(outbound.replacedBodyBytes)
        // The existing fail-closed response carries that reason to the client, same as a key failure.
        val failure = RuleEnforcer.buildCryptoFailureResponse("crypto-rule", encrypt = true, reason = reason)
        assertEquals(502, failure.statusCode)
        assertTrue(failure.body.contains("Content-Encoding: br"))
    }

    @Test
    fun `DECRYPT refuses a declared-gzip body that failed to inflate rather than decrypting compressed garbage`() {
        val inbound = parseResponse("this is not a gzip stream".toByteArray(), "gzip")

        val result = RuleEnforcer.applyBodyCryptoWith(inbound, rule("RESPONSE", action = RuleAction.BODY_DECRYPT_AES), encrypt = false, ::keyFor)

        assertTrue(result.toString(), result is RuleEnforcer.CryptoApplyResult.Failed)
        val reason = (result as RuleEnforcer.CryptoApplyResult.Failed).reason
        assertTrue(reason, reason.contains("BODY_DECRYPT_AES refused"))
        assertTrue(reason, reason.contains("Content-Encoding: gzip"))
        assertFalse(inbound.bodyReplaced)
    }

    @Test
    fun `identity body with no Content-Encoding header is transformed exactly as before`() {
        val plaintext = """{"plain":true}""".toByteArray(Charsets.UTF_8)
        val outbound = parseRequest(plaintext, null)
        val enc = RuleEnforcer.applyBodyCryptoWith(outbound, rule("REQUEST"), encrypt = true, ::keyFor)
        assertTrue(enc is RuleEnforcer.CryptoApplyResult.Applied)
        val direct = RequestCryptoManager.decryptWith("test-alias", outbound.replacedBodyBytes!!, "AES/GCM/NoPadding", ::keyFor)
        assertArrayEquals(plaintext, (direct as RequestCryptoManager.CryptoResult.Success).bytes)
    }
}
