package com.example.interception.uid

import com.example.interception.bridge.BridgeEvent
import com.example.interception.gateway.local.ClientOrigin
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 task 10: the attribution ladder is deterministic and every rung is observable. Pure JVM:
 * every platform lookup is injected; `ownerLookupSupported` is forced so the API-29 gate does
 * not depend on the host's `Build.VERSION.SDK_INT` (which is 0 under plain unit tests).
 */
class UidAttributorTest {

    private val origin = ClientOrigin(ip = "10.0.0.7", port = 51000, transport = "tcp")
    private val src = InetSocketAddress("10.0.0.7", 51000)
    private val dst = InetSocketAddress("198.18.0.5", 443)

    private fun sessionStart(id: Long = 1L, s: String = "10.0.0.7:51000", d: String = "198.18.0.5:443") =
        BridgeEvent.SessionStart(sessionId = id, src = s, dst = d, hostname = "example.com", isTcp = true)

    @Test
    fun `bridge origin plus tracked destination resolves through the owner lookup with the real tuple`() {
        val seen = ArrayList<Triple<Int, InetSocketAddress, InetSocketAddress>>()
        val attributor = UidAttributor(
            ownerUidLookup = { p, l, r -> seen += Triple(p, l, r); if (l == src && r == dst) 10123 else null },
            procNetLookup = { _, _ -> error("must not fall through") },
            ownerLookupSupported = true,
        )
        attributor.onBridgeEvent(sessionStart())
        assertEquals(1, attributor.trackedSessions)

        val result = attributor.attribute(UidAttributor.Flow(origin = origin))

        assertEquals(UidAttributor.Attribution.Resolved(10123, UidAttributor.Source.BRIDGE_INFO), result)
        assertEquals(1, seen.size)
        assertEquals(android.system.OsConstants.IPPROTO_TCP, seen[0].first)
        assertEquals(src, seen[0].second)
        assertEquals(dst, seen[0].third)
    }

    @Test
    fun `bridge tuple falls back to proc net before giving up on the origin rung`() {
        val attributor = UidAttributor(
            ownerUidLookup = { _, _, _ -> null },
            procNetLookup = { l, r -> if (l == src && r == dst) 10555 else null },
            ownerLookupSupported = true,
        )
        attributor.onBridgeEvent(sessionStart())
        val result = attributor.attribute(UidAttributor.Flow(origin = origin))
        assertEquals(UidAttributor.Attribution.Resolved(10555, UidAttributor.Source.BRIDGE_INFO), result)
    }

    @Test
    fun `session end forgets the tuple and a later flow reports why the origin could not be used`() {
        val attributor = UidAttributor(
            ownerUidLookup = { _, _, _ -> 10123 },
            procNetLookup = { _, _ -> null },
            ownerLookupSupported = true,
        )
        attributor.onBridgeEvent(sessionStart(id = 7))
        attributor.onBridgeEvent(BridgeEvent.SessionEnd(sessionId = 7, txBytes = 1, rxBytes = 1, reason = "closed"))
        assertEquals(0, attributor.trackedSessions)

        val result = attributor.attribute(UidAttributor.Flow(origin = origin))

        assertTrue(result is UidAttributor.Attribution.Unattributed)
        val reasons = (result as UidAttributor.Attribution.Unattributed).reasons
        assertTrue(reasons.toString(), reasons.any { it.contains("destination untracked") })
        assertTrue(reasons.toString(), reasons.any { it.contains("in-process bridge") })
        assertTrue(reasons.toString(), reasons.any { it.contains("capture selection empty") })
        assertNull(result.uidOrNull)
    }

    @Test
    fun `an explicit destination substitutes for an untracked bridge session`() {
        val attributor = UidAttributor(
            ownerUidLookup = { _, l, r -> if (l == src && r == dst) 42 else null },
            procNetLookup = { _, _ -> null },
            ownerLookupSupported = true,
        )
        val result = attributor.attribute(UidAttributor.Flow(origin = origin, destination = dst))
        assertEquals(UidAttributor.Attribution.Resolved(42, UidAttributor.Source.BRIDGE_INFO), result)
    }

    @Test
    fun `tracked table is bounded and evicts the least recently used source`() {
        val attributor = UidAttributor(maxTrackedSessions = 3, ownerLookupSupported = true)
        for (i in 1..5) attributor.onBridgeEvent(sessionStart(id = i.toLong(), s = "10.0.0.$i:5000", d = "198.18.0.1:443"))
        assertEquals(3, attributor.trackedSessions)
        assertNull(attributor.trackedDestination(ClientOrigin("10.0.0.1", 5000, "tcp")))
        assertNotNull(attributor.trackedDestination(ClientOrigin("10.0.0.5", 5000, "tcp")))
    }

    @Test
    fun `stale session end for a re-used source does not drop the newer session`() {
        val attributor = UidAttributor(ownerLookupSupported = true)
        attributor.onBridgeEvent(sessionStart(id = 1))
        attributor.onBridgeEvent(sessionStart(id = 2, d = "198.18.0.9:443"))
        attributor.onBridgeEvent(BridgeEvent.SessionEnd(sessionId = 1, txBytes = 0, rxBytes = 0, reason = "late"))
        assertEquals(InetSocketAddress("198.18.0.9", 443), attributor.trackedDestination(origin))
    }

    @Test
    fun `bridge flows never fall through to the accepted socket which belongs to this process`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { _ ->
                server.accept().use { accepted ->
                    val attributor = UidAttributor(
                        // Neither lookup knows the bridge tuple; both would "resolve" the
                        // accepted socket's peer -- which is this process -- to our own uid.
                        ownerUidLookup = { _, l, _ -> if (l == src) null else 10001 /* our own uid */ },
                        procNetLookup = { l, _ -> if (l == src) null else 10001 },
                        ownerLookupSupported = true,
                    )
                    val result = attributor.attribute(UidAttributor.Flow(origin = origin, clientSocket = accepted, destination = dst))
                    assertTrue(result.toString(), result is UidAttributor.Attribution.Unattributed)
                }
            }
        }
    }

    @Test
    fun `accepted socket is attributed via the connection owner on API 29 plus`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { accepted ->
                    val attributor = UidAttributor(
                        ownerUidLookup = { _, l, r ->
                            val local = accepted.localSocketAddress as InetSocketAddress
                            val remote = accepted.remoteSocketAddress as InetSocketAddress
                            if (l == remote && r == local) 10777 else null
                        },
                        procNetLookup = { _, _ -> error("owner lookup must win") },
                        ownerLookupSupported = true,
                    )
                    val result = attributor.attribute(UidAttributor.Flow(clientSocket = accepted))
                    assertEquals(UidAttributor.Attribution.Resolved(10777, UidAttributor.Source.CONNECTION_OWNER), result)
                    assertFalse((result as UidAttributor.Attribution.Resolved).isInferred)
                }
            }
        }
    }

    @Test
    fun `pre 29 skips the owner lookup and uses proc net for the accepted socket`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { _ ->
                server.accept().use { accepted ->
                    var ownerCalls = 0
                    val attributor = UidAttributor(
                        ownerUidLookup = { _, _, _ -> ownerCalls++; 1 },
                        procNetLookup = { _, _ -> 10888 },
                        ownerLookupSupported = false,
                    )
                    val result = attributor.attribute(UidAttributor.Flow(clientSocket = accepted))
                    assertEquals(UidAttributor.Attribution.Resolved(10888, UidAttributor.Source.PROC_NET), result)
                    assertEquals(0, ownerCalls)
                }
            }
        }
    }

    @Test
    fun `single app capture selection is an inference and says so`() {
        val attributor = UidAttributor(
            captureSelection = { setOf("com.example.app") },
            packageUidLookup = { pkg -> if (pkg == "com.example.app") 10999 else null },
            ownerLookupSupported = true,
        )
        val result = attributor.attribute(UidAttributor.Flow())
        assertEquals(UidAttributor.Attribution.Resolved(10999, UidAttributor.Source.SELECTION_INFERENCE), result)
        assertTrue((result as UidAttributor.Attribution.Resolved).isInferred)
    }

    @Test
    fun `multiple selected apps never guess`() {
        val attributor = UidAttributor(
            captureSelection = { setOf("a", "b") },
            packageUidLookup = { 1 },
            ownerLookupSupported = true,
        )
        val result = attributor.attribute(UidAttributor.Flow())
        assertTrue(result is UidAttributor.Attribution.Unattributed)
        assertTrue(result.toString(), (result as UidAttributor.Attribution.Unattributed).reason.contains("2 apps in capture selection"))
    }

    @Test
    fun `lookups that throw are treated as no answer not as a crash`() {
        val attributor = UidAttributor(
            ownerUidLookup = { _, _, _ -> throw SecurityException("no permission") },
            procNetLookup = { _, _ -> throw java.io.IOException("unreadable") },
            captureSelection = { throw IllegalStateException("datastore down") },
            ownerLookupSupported = true,
        )
        attributor.onBridgeEvent(sessionStart())
        val result = attributor.attribute(UidAttributor.Flow(origin = origin))
        assertTrue(result is UidAttributor.Attribution.Unattributed)
    }

    @Test
    fun `socket address parsing accepts v4 and bracketed v6 and rejects names`() {
        assertEquals(InetSocketAddress("10.0.0.7", 51000), UidAttributor.parseSocketAddress("10.0.0.7:51000"))
        val v6 = UidAttributor.parseSocketAddress("[fd00::1]:8443")
        assertNotNull(v6)
        assertEquals(8443, v6!!.port)
        assertEquals("fd00:0:0:0:0:0:0:1", v6.address.hostAddress)
        assertNull(UidAttributor.parseSocketAddress("example.com:443"))
        assertNull(UidAttributor.parseSocketAddress("fd00::1:443"))
        assertNull(UidAttributor.parseSocketAddress("10.0.0.7:0"))
        assertNull(UidAttributor.parseSocketAddress("10.0.0.7"))
        assertNull(UidAttributor.parseSocketAddress(""))
    }

    @Test
    fun `malformed bridge events are ignored rather than tracked`() {
        val attributor = UidAttributor(ownerLookupSupported = true)
        attributor.onBridgeEvent(sessionStart(s = "garbage", d = "198.18.0.5:443"))
        attributor.onBridgeEvent(sessionStart(s = "10.0.0.7:51000", d = "nowhere"))
        assertEquals(0, attributor.trackedSessions)
    }

    @Test
    fun `tracked sessions are attributable by client tuple for the PCAPng tee and vanish on session_end`() {
        val attributor = UidAttributor(
            ownerUidLookup = { proto, l, r ->
                if (proto == android.system.OsConstants.IPPROTO_UDP && l.port == 40000 && r.port == 53) 10077 else null
            },
            procNetLookup = { _, _ -> null },
            ownerLookupSupported = true,
        )
        val src = InetSocketAddress("10.0.0.7", 40000)
        assertNull("untracked tuple must not be guessed", attributor.attributeTracked(src))
        attributor.onBridgeEvent(BridgeEvent.SessionStart(sessionId = 5L, src = "10.0.0.7:40000", dst = "8.8.8.8:53", hostname = null, isTcp = false))

        val tracked = attributor.trackedSession(src)
        assertEquals(UidAttributor.TrackedSession(5L, InetSocketAddress("8.8.8.8", 53), isTcp = false), tracked)
        val (session, attribution) = attributor.attributeTracked(src)!!
        assertEquals(5L, session.sessionId)
        // Resolved on the bridge rung: the owner lookup ran with the bridge's real 4-tuple.
        assertEquals(UidAttributor.Attribution.Resolved(10077, UidAttributor.Source.BRIDGE_INFO), attribution)

        attributor.onBridgeEvent(BridgeEvent.SessionEnd(sessionId = 5L, txBytes = 1, rxBytes = 1, reason = "eof"))
        assertNull(attributor.trackedSession(src))
        assertNull(attributor.attributeTracked(src))
    }
}
