package com.example.interception

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * S3 task 57 (D7 cutover, gate G27): the observe-only HTTP/2 byte-forwarding relay is deleted
 * from [MitmProxyEngine], not kept as a fallback -- an h2 client connection is terminated by
 * [com.example.interception.http.Http2ProxyConnection] or, with `InterceptorFlags.h2Terminate`
 * off, never negotiated in the first place (ALPN offers `http/1.1` only).
 */
class MitmProxyEngineH2TerminationCutoverTest {

    private fun mainSource(relative: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: fail("could not locate app/src/main/java from ${File(".").absolutePath}") as Nothing
        return File(root, relative).readText()
    }

    @Test
    fun `observe-only h2 relay functions are gone from MitmProxyEngine`() {
        val engine = mainSource("com/example/interception/MitmProxyEngine.kt")
        assertFalse(engine.contains("inspectH2ClientToServer"))
        assertFalse(engine.contains("inspectH2ServerToClient"))
        assertTrue(engine.contains("Http2ProxyConnection("))
        assertTrue(engine.contains("h2.runClientLeg()"))
        assertTrue(engine.contains("h2.runUpstreamLeg()"))
    }

    @Test
    fun `client-facing ALPN offer is driven by the h2Terminate flag`() {
        val engine = mainSource("com/example/interception/MitmProxyEngine.kt")
        assertTrue(engine.contains("""if (h2Terminate) arrayOf("h2", "http/1.1") else arrayOf("http/1.1")"""))
        assertTrue(engine.contains("h2Terminate = flags.h2Terminate"))
        val flags = mainSource("com/example/data/persistence/InterceptorFlags.kt")
        assertTrue(flags.contains("val h2Terminate: Boolean get() = prefs.getBoolean(KEY_H2_TERMINATE, true)"))
    }

    @Test
    fun `an h2 client leg offers h2 then http 1_1 upstream so the translator can pick either`() {
        val engine = mainSource("com/example/interception/MitmProxyEngine.kt")
        assertTrue(engine.contains(""""h2" -> listOf("h2", "http/1.1")"""))
        val upstream = mainSource("com/example/interception/LazyUpstreamConnection.kt")
        assertTrue(upstream.contains("val alpn: String? = null"))
        assertTrue(upstream.contains("handshakeApplicationProtocol"))
    }
}
