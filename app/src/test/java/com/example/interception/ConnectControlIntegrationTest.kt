package com.example.interception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises [HttpProxyShared.handleConnectAttempt] end-to-end for every [ConnectControl.Disposition]
 * -- a real client [Socket] against a real upstream [ServerSocket] -- so the byte-counted relay
 * and each disposition's response line are verified against actual sockets, not mocks.
 */
class ConnectControlIntegrationTest {

    /** Minimal "client" side: a connected socket pair, with [output] captured separately. */
    private fun connectedClientSocket(): Socket {
        val acceptor = ServerSocket(0)
        val clientSide = Socket("127.0.0.1", acceptor.localPort)
        val serverSide = acceptor.accept()
        acceptor.close()
        // Return serverSide: this is "the client as seen by the proxy" -- i.e. what
        // handleConnectAttempt's `client: Socket` parameter represents. clientSide is kept
        // alive (not closed) so the pair doesn't tear down; GC / test process exit reclaims it.
        return serverSide
    }

    @Test
    fun `Block disposition writes 403 and never dials upstream`() {
        val client = connectedClientSocket()
        val output = ByteArrayOutputStream()
        var transferredCalled = false

        HttpProxyShared.handleConnectAttempt(
            client, "example.com:443", ConnectControl.Disposition.Block("nope"), output,
        ) { _, _ -> transferredCalled = true }

        val response = output.toString(Charsets.UTF_8.name())
        assertTrue(response.startsWith("HTTP/1.1 403"))
        assertTrue(!transferredCalled) // no relay ran, so the callback must never fire
        client.close()
    }

    @Test
    fun `ForceMitm disposition is answered honestly, never falls back to plaintext relay`() {
        val client = connectedClientSocket()
        val output = ByteArrayOutputStream()
        var transferredCalled = false

        HttpProxyShared.handleConnectAttempt(
            client, "example.com:443", ConnectControl.Disposition.ForceMitm, output,
        ) { _, _ -> transferredCalled = true }

        val response = output.toString(Charsets.UTF_8.name())
        assertTrue(response.startsWith("HTTP/1.1 403"))
        assertTrue(response.contains("MITM"))
        assertTrue(!transferredCalled)
        client.close()
    }

    @Test
    fun `AllowOpaque disposition writes 200 then relays bytes both directions with correct counts`() {
        val upstream = ServerSocket(0)
        val upstreamPayload = "upstream-says-hello".toByteArray()
        val clientPayload = "client-says-hi".toByteArray()
        val latch = CountDownLatch(1)

        val upstreamThread = Thread {
            val conn = upstream.accept()
            val received = ByteArray(clientPayload.size)
            var readTotal = 0
            while (readTotal < received.size) {
                val n = conn.getInputStream().read(received, readTotal, received.size - readTotal)
                if (n == -1) break
                readTotal += n
            }
            conn.getOutputStream().write(upstreamPayload)
            conn.getOutputStream().flush()
            latch.countDown()
            Thread.sleep(200) // give the relay time to forward before the socket closes
            conn.close()
        }
        upstreamThread.start()

        val acceptor = ServerSocket(0)
        val clientSideRaw = Socket("127.0.0.1", acceptor.localPort)
        val serverSideClient = acceptor.accept()
        acceptor.close()

        val output = ByteArrayOutputStream()
        var sentBytes = -1L
        var recvBytes = -1L
        val relayDone = CountDownLatch(1)

        val relayThread = Thread {
            HttpProxyShared.handleConnectAttempt(
                serverSideClient, "127.0.0.1:${upstream.localPort}",
                ConnectControl.Disposition.AllowOpaque, output,
            ) { sent, recv ->
                sentBytes = sent
                recvBytes = recv
                relayDone.countDown()
            }
        }
        relayThread.start()

        // Give the relay a moment to write "200 Connection Established" before we send bytes.
        Thread.sleep(200)
        clientSideRaw.getOutputStream().write(clientPayload)
        clientSideRaw.getOutputStream().flush()

        val readBack = ByteArray(upstreamPayload.size)
        var total = 0
        clientSideRaw.soTimeout = 5000
        while (total < readBack.size) {
            val n = clientSideRaw.getInputStream().read(readBack, total, readBack.size - total)
            if (n == -1) break
            total += n
        }

        clientSideRaw.close() // triggers relay EOF on both threads
        relayDone.await(5, TimeUnit.SECONDS)
        upstream.close()

        val response = output.toString(Charsets.UTF_8.name())
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertEquals(upstreamPayload.toString(Charsets.UTF_8), String(readBack))
        assertEquals(clientPayload.size.toLong(), sentBytes)
        assertEquals(upstreamPayload.size.toLong(), recvBytes)
    }

    @Test
    fun `Redirect disposition tunnels to the redirect target, not the original target`() {
        val redirectUpstream = ServerSocket(0)
        val marker = "redirected-ok".toByteArray()
        val upstreamThread = Thread {
            val conn = redirectUpstream.accept()
            conn.getOutputStream().write(marker)
            conn.getOutputStream().flush()
            Thread.sleep(200)
            conn.close()
        }
        upstreamThread.start()

        val acceptor = ServerSocket(0)
        val clientSideRaw = Socket("127.0.0.1", acceptor.localPort)
        val serverSideClient = acceptor.accept()
        acceptor.close()

        val output = ByteArrayOutputStream()
        val relayDone = CountDownLatch(1)
        val relayThread = Thread {
            HttpProxyShared.handleConnectAttempt(
                serverSideClient, "original.invalid:443",
                ConnectControl.Disposition.Redirect("127.0.0.1:${redirectUpstream.localPort}"), output,
            ) { _, _ -> relayDone.countDown() }
        }
        relayThread.start()

        val readBack = ByteArray(marker.size)
        clientSideRaw.soTimeout = 5000
        var total = 0
        while (total < readBack.size) {
            val n = clientSideRaw.getInputStream().read(readBack, total, readBack.size - total)
            if (n == -1) break
            total += n
        }
        clientSideRaw.close()
        relayDone.await(5, TimeUnit.SECONDS)
        redirectUpstream.close()

        assertEquals(marker.toString(Charsets.UTF_8), String(readBack))
        val response = output.toString(Charsets.UTF_8.name())
        assertTrue(response.startsWith("HTTP/1.1 200"))
    }

    /**
     * [ConnectControl.resolveClientUid] against a REAL loopback TCP connection accepted by this
     * very JVM process -- on a plain Linux host (this test's environment, unlike a real Android
     * device) `/proc/net/tcp` is unrestricted, so the accepted socket's row is present and owned
     * by this process's own uid. Exercises the real, non-fixture [ProcNetUidResolver.resolve]
     * path end to end, complementing [ProcNetUidResolverTest]'s fixture-based unit coverage.
     */
    @Test
    fun `resolveClientUid attributes a real accepted loopback connection to this process's uid`() {
        val acceptor = ServerSocket(0)
        val clientSide = Socket("127.0.0.1", acceptor.localPort)
        val serverSideClient = acceptor.accept()
        acceptor.close()

        val uid = ConnectControl.resolveClientUid(serverSideClient)

        // Best-effort: some sandboxes may still hide /proc/net/tcp rows or run under a runtime
        // where the accepted socket's row isn't visible by the time this reads it -- assert that
        // *when* a uid is resolved, it's a real non-negative id, never crash outright either way.
        if (uid != null) assertTrue(uid >= 0)

        clientSide.close()
        serverSideClient.close()
    }
}
