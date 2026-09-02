package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security audit fix (MUST-FIX 2, fail-open AES crypto): [RuleEnforcer.applyBodyCrypto] itself
 * always returned safely (see [RequestCryptoManagerTest]), but the *callers* in
 * [MitmProxyEngine.handleParsedRequest]/[MitmProxyEngine.handleParsedResponse] used to ignore a
 * [RuleEnforcer.CryptoApplyResult.Failed] outcome entirely and forward the untransformed message
 * anyway -- for BODY_ENCRYPT_AES that meant plaintext went out on the wire looking exactly like a
 * successful encrypt; for BODY_DECRYPT_AES the client received raw ciphertext looking like a
 * normal response. These tests drive both fail-closed short-circuits with a blank
 * `cryptoKeyAlias` (the simplest way to force [RequestCryptoManager] to fail deterministically
 * without a real AndroidKeyStore -- see [RequestCryptoManagerTest]'s `keyProvider` seam for the
 * lower-level coverage of *why* it fails).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineCryptoFailClosedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertCryptoRule(action: RuleAction, domain: String = "crypto.example") = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "broken-crypto-rule",
                domain = domain,
                action = action,
                cryptoKeyAlias = "", // blank alias -> RequestCryptoManager always fails closed
            )
        )
    }

    private fun request(url: String = "https://crypto.example/api", host: String = "crypto.example", body: String = "plaintext-body") =
        ParsedHttpMessage(isRequest = true, method = HttpMethod.POST, url = url, host = host, body = body)

    private fun neverConnectUpstream(host: String = "crypto.example", port: Int = 443) =
        LazyUpstreamConnection(
            host, port,
            connectionFactory = { _, _ -> throw AssertionError("a failed crypto rule must never dial upstream") },
        )

    // ── Request-side: BODY_ENCRYPT_AES failure must never forward the plaintext upstream ──

    @Test
    fun `BODY_ENCRYPT_AES failure never dials upstream and never enqueues into pending`() {
        insertCryptoRule(RuleAction.BODY_ENCRYPT_AES)

        val engine = MitmProxyEngine()
        val upstream = neverConnectUpstream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()

        engine.handleParsedRequest(
            db, request(), ByteArray(0), upstream, ByteArrayOutputStream(), Any(), pending,
            "crypto.example", 443, true, true,
        )

        assertFalse("upstream must never be dialed on a crypto failure", upstream.isConnected)
        assertTrue("a request that never went upstream has no real response to pair with", pending.isEmpty())
    }

    @Test
    fun `BODY_ENCRYPT_AES failure writes a visible failure response to the client, not the plaintext`() {
        insertCryptoRule(RuleAction.BODY_ENCRYPT_AES)

        val engine = MitmProxyEngine()
        val clientOut = ByteArrayOutputStream()

        engine.handleParsedRequest(
            db, request(body = "super-secret-plaintext"), ByteArray(0), neverConnectUpstream(), clientOut, Any(),
            ConcurrentLinkedQueue(), "crypto.example", 443, true, true,
        )

        val responseText = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("client must see a failure status, not a normal 2xx", responseText.startsWith("HTTP/1.1 502"))
        assertTrue("failure response must carry the machine-checkable marker header", responseText.contains("x-requestr-crypto-failed: true"))
        assertFalse("the plaintext must never reach the wire on an encrypt failure", responseText.contains("super-secret-plaintext"))
    }

    @Test
    fun `BODY_ENCRYPT_AES failure still emits a captured transaction so it is visible in the traffic list`() {
        insertCryptoRule(RuleAction.BODY_ENCRYPT_AES)

        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.handleParsedRequest(
            db, request(), ByteArray(0), neverConnectUpstream(), ByteArrayOutputStream(), Any(),
            ConcurrentLinkedQueue(), "crypto.example", 443, true, true,
        )

        val entity = captured ?: throw AssertionError("a failed crypto rule must still emit a captured transaction")
        assertEquals(502, entity.statusCode)
    }

    // ── Response-side: BODY_DECRYPT_AES failure must never forward the raw ciphertext to the client ──

    private fun wsState() = MitmProxyEngine.ConnectionProtocolState()

    @Test
    fun `BODY_DECRYPT_AES failure on the response path never forwards the untransformed body to the client`() {
        insertCryptoRule(RuleAction.BODY_DECRYPT_AES)

        val engine = MitmProxyEngine()
        val clientOut = ByteArrayOutputStream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
        pending.add(request() to System.currentTimeMillis())

        val response = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
            body = "not-actually-decryptable-ciphertext",
        )

        engine.handleParsedResponse(
            db, response, ByteArray(0), clientOut, Any(), pending,
            "crypto.example", 443, true, true, wsState(),
        )

        val responseText = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("client must see the synthesized failure response, not the real 200", responseText.startsWith("HTTP/1.1 502"))
        assertTrue(responseText.contains("x-requestr-crypto-failed: true"))
        assertFalse("raw ciphertext must never reach the client on a decrypt failure", responseText.contains("not-actually-decryptable-ciphertext"))
    }

    @Test
    fun `BODY_DECRYPT_AES failure on the response path emits the failure response, not the real one`() {
        insertCryptoRule(RuleAction.BODY_DECRYPT_AES)

        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
        pending.add(request() to System.currentTimeMillis())

        val response = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
            body = "ciphertext-that-will-not-decrypt",
        )

        engine.handleParsedResponse(
            db, response, ByteArray(0), ByteArrayOutputStream(), Any(), pending,
            "crypto.example", 443, true, true, wsState(),
        )

        val entity = captured ?: throw AssertionError("a failed crypto rule must still emit a captured transaction")
        assertEquals("the persisted transaction must reflect the failure, not the real 200", 502, entity.statusCode)
        assertFalse(entity.responseBody?.contains("ciphertext-that-will-not-decrypt") == true)
    }
}
