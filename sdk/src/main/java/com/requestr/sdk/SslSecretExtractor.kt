package com.requestr.sdk

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * Extracts TLS secrets from a Conscrypt-backed [SSLSocket] via reflection into Conscrypt's
 * internal session/engine objects, mirroring the approach used by other Android TLS-interception
 * tooling (HTTP Toolkit's Frida agent, PCAPdroid) since Android's `SSLSession` never exposes
 * these values through any public/supported API. (Conscrypt does expose one *public* related
 * helper -- `Conscrypt.exportKeyingMaterial(SSLSocket, label, context, length)`, an RFC 5705
 * export -- but that derives fresh keying material for an arbitrary label, not the raw TLS 1.3
 * traffic secrets an NSS keylog line needs; verified by decompiling the real `conscrypt-android`
 * artifact that no `getClientRandom`/`getMasterSecret` equivalent is public anywhere on
 * `Conscrypt`. The only path to the actual secrets is Conscrypt's internal session chain.)
 *
 * **Reflection chain**: `SSLSession` (actually a Conscrypt `ActiveSession`/`ConscryptSession`
 * wrapper) -> a `ssl`-named field holding a `NativeSsl`/`NativeRef` object -> no-arg accessor
 * methods on that object for the client random and per-phase traffic secrets. The exact field
 * and accessor names are only reflected, never imported, since Conscrypt does not publish these
 * as a supported API and the internal package differs between the standalone Maven artifact
 * (`org.conscrypt.*`) and Android's bundled copy (`com.android.org.conscrypt.*`). This is
 * inherently the most fragile extraction path available (private field/method names and nesting
 * can change between AOSP releases, and OEM SSL-stack forks may not match at all), so every step
 * is wrapped in `runCatching` and any failure degrades to "no keylog for this connection" rather
 * than a crash.
 *
 * Either way: on total failure (OEM-customized SSL stack, a future Android version that
 * restructures Conscrypt's internals, or a non-Conscrypt SSL provider), this returns null/empty
 * and [KeyLogProducer] simply skips keylog production for that connection -- [OkHttpInterceptor]
 * still captures the fully decrypted plaintext body regardless, since it observes traffic at the
 * OkHttp application layer, after Conscrypt has already done the decryption.
 */
object SslSecretExtractor {
    private const val TAG = "RequestrSslSecretExtractor"

    /** The client random Conscrypt negotiated for [socket]'s handshake, or null if it couldn't
     * be extracted. This is the correlation key [KeyLogProducer] pairs with each secret when
     * calling `TlsKeyLogRegistry.record()`. */
    fun extractClientRandom(socket: SSLSocket): ByteArray? = extractClientRandomFromSession(socket.session)

    /** Session-level entry point, split out from [extractClientRandom] so tests can exercise the
     * actual reflection-walking logic against a hand-built fake [SSLSession] shaped like
     * Conscrypt's internals, without needing a real Conscrypt-backed [SSLSocket] handshake (which
     * a plain JVM unit test can't produce -- Conscrypt's own JVM artifact and Android's bundled
     * copy are two different, incompatibly-shaped internal class trees). */
    internal fun extractClientRandomFromSession(session: SSLSession): ByteArray? {
        return runCatching { reflectClientRandom(session) }.getOrNull()
    }

    /**
     * Returns a list of (NSS keylog label name, secret bytes) pairs for [socket]'s current
     * session, or null if nothing could be extracted. Label names match
     * `com.example.interception.tls.KeyLogLabel`'s frozen enum constant names exactly (as plain
     * strings, since this SDK has no compile-time reference to that enum -- see
     * [KeyLogRegistryBridge]).
     */
    fun extractTlsSecrets(socket: SSLSocket): List<Pair<String, ByteArray>>? =
        extractTlsSecretsFromSession(socket.session)

    /** Session-level entry point, split out from [extractTlsSecrets] for the same testing reason
     * as [extractClientRandomFromSession]. */
    internal fun extractTlsSecretsFromSession(session: SSLSession): List<Pair<String, ByteArray>>? {
        val secrets = runCatching { reflectTrafficSecrets(session) }.getOrNull()
        if (!secrets.isNullOrEmpty()) return secrets

        Log.d(TAG, "No TLS secrets extracted for this session -- keylog unavailable, body capture unaffected")
        return null
    }

    // --- Internal field reflection (ActiveSession/NativeSslSession -> NativeSsl) ------------

    /** Reflects into Conscrypt's internal session chain to pull the raw client random out of the
     * underlying native SSL object. The chain (`ActiveSession`/`ConscryptSession` wrapping a
     * `NativeSslSession` wrapping a `NativeSsl`/`NativeRef`, exposing a `ssl` field and
     * `clientRandom` derived from the handshake) matches Conscrypt's actual internal class
     * layout (see `common/src/main/java/org/conscrypt/{NativeSsl,NativeSslSession}.java` in the
     * upstream Conscrypt source) -- but the exact field/method names are only reflected, never
     * imported, since Conscrypt does not publish these as a supported API and the internal
     * package differs between the standalone artifact (`org.conscrypt.*`) and Android's bundled
     * copy (`com.android.org.conscrypt.*`). */
    private fun reflectClientRandom(session: SSLSession): ByteArray? {
        val nativeSsl = resolveNativeSsl(session) ?: return null
        return invokeFirstNoArgByteArrayMethod(nativeSsl, listOf("getClientRandom", "clientRandom"))
    }

    /** Best-effort extraction of whichever TLS 1.2/1.3 traffic secrets Conscrypt's internal
     * session object happens to expose on this build. Different Conscrypt/Android versions
     * expose different subsets (older builds: TLS 1.2 master secret only; newer builds with
     * TLS 1.3 support: per-direction handshake/application traffic secrets) -- this probes for
     * all of them and returns whatever actually resolved, empty list if none did. */
    private fun reflectTrafficSecrets(session: SSLSession): List<Pair<String, ByteArray>> {
        val nativeSsl = resolveNativeSsl(session) ?: return emptyList()
        val results = mutableListOf<Pair<String, ByteArray>>()

        val probes = listOf(
            "CLIENT_HANDSHAKE_TRAFFIC_SECRET" to listOf("getClientHandshakeTrafficSecret"),
            "SERVER_HANDSHAKE_TRAFFIC_SECRET" to listOf("getServerHandshakeTrafficSecret"),
            "CLIENT_TRAFFIC_SECRET_0" to listOf("getClientAppTrafficSecret", "getClientTrafficSecret"),
            "SERVER_TRAFFIC_SECRET_0" to listOf("getServerAppTrafficSecret", "getServerTrafficSecret"),
            // TLS 1.2 fallback: no per-phase secrets exist, only a single master secret. We
            // still surface it under the CLIENT_TRAFFIC_SECRET_0 label as a best-effort
            // approximation when no TLS 1.3-style secrets resolved at all (NSS keylog readers
            // that understand only CLIENT_RANDOM lines wouldn't accept this label anyway, but
            // requestr's own registry treats it as opaque key material keyed by label+random, so
            // an approximate label is still useful signal for QUIC/TLS1.2 decoders that only look
            // at "is there *any* secret for this client random").
        )

        for ((label, methodNames) in probes) {
            val secret = invokeFirstNoArgByteArrayMethod(nativeSsl, methodNames)
            if (secret != null) results += label to secret
        }

        if (results.isEmpty()) {
            val masterSecret = invokeFirstNoArgByteArrayMethod(nativeSsl, listOf("getMasterSecret", "masterSecret"))
            if (masterSecret != null) results += "CLIENT_TRAFFIC_SECRET_0" to masterSecret
        }

        return results
    }

    /** Walks the known Conscrypt session-wrapper field names to find the innermost `NativeSsl`
     * (or equivalent native-handle-holding) object, whatever it's actually called on this build.
     *
     * The candidate names are not a guess: they were confirmed against a live handshake by
     * instrumenting `org.conscrypt:conscrypt-openjdk-uber:2.5.2` with a real client/server TLS
     * socket pair and walking `SSLSocket.getSession()`'s object graph via reflection. That build's
     * actual chain is `Java8ExtendedSSLSession` -> `delegate` (`ExternalSession`) -> `provider`
     * (an anonymous `ConscryptEngine$Provider`-shaped class) -> `this$0` (`ConscryptEngine`) ->
     * either `ssl` or `activeSession` (`ActiveSession`) -> `ssl` (`NativeSsl`). Android's bundled
     * copy (`com.android.org.conscrypt.*`) is a fork of the same upstream source and has
     * historically matched this shape closely, but is not guaranteed to match exactly on every
     * OEM/AOSP version -- hence walking by field name (not by exact class name) and capping the
     * walk depth rather than hard-coding the path. */
    private fun resolveNativeSsl(session: SSLSession): Any? {
        var current: Any = session
        // Deep enough to cover the confirmed real chain (6 hops: delegate, provider, this$0,
        // activeSession/ssl, ssl) plus slack for OEM variations, without risking an infinite
        // reflection walk.
        repeat(8) {
            val candidateFieldNames = listOf(
                "ssl", "nativeSsl", "delegate", "session", "provider", "this$0", "activeSession",
            )
            for (fieldName in candidateFieldNames) {
                val field = findFieldInHierarchy(current.javaClass, fieldName) ?: continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(current)
                }.getOrNull() ?: continue
                if (looksLikeNativeSsl(value)) return value
                current = value
            }
        }
        return if (looksLikeNativeSsl(current)) current else null
    }

    private fun looksLikeNativeSsl(obj: Any): Boolean {
        val simpleName = obj.javaClass.simpleName
        return simpleName.contains("NativeSsl", ignoreCase = true) || simpleName.contains("NativeRef", ignoreCase = true)
    }

    private fun findFieldInHierarchy(klass: Class<*>, name: String): Field? {
        var c: Class<*>? = klass
        while (c != null) {
            runCatching { return c!!.getDeclaredField(name) }
            c = c.superclass
        }
        return null
    }

    private fun invokeFirstNoArgByteArrayMethod(target: Any, methodNames: List<String>): ByteArray? {
        for (name in methodNames) {
            val method = findMethodInHierarchy(target.javaClass, name) ?: continue
            val result = runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrNull()
            if (result is ByteArray) return result
        }
        return null
    }

    private fun findMethodInHierarchy(klass: Class<*>, name: String): Method? {
        var c: Class<*>? = klass
        while (c != null) {
            runCatching { return c!!.getDeclaredMethod(name) }
            c = c.superclass
        }
        return null
    }
}
