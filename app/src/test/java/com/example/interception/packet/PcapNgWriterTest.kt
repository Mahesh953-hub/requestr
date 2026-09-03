package com.example.interception.packet

import com.example.interception.bridge.BridgeEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * S1 task 12: byte-level contract of [PcapNgWriter] (SHB/IDB/EPB layout, options, padding,
 * direction flags, per-packet comments) and of [PacketTeeRecorder] (tuple recovery from raw
 * IPv4/IPv6 frames, annotation caching, honest handling of unknown flows).
 */
class PcapNgWriterTest {

    // ── minimal PCAPng reader used only by these tests ──────────────────────────────────────

    private data class Block(val type: Int, val body: ByteBuffer)

    private fun blocks(bytes: ByteArray): List<Block> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Block>()
        while (buf.remaining() >= 12) {
            val start = buf.position()
            val type = buf.getInt()
            val total = buf.getInt()
            assertEquals("block length must be a multiple of 4", 0, total % 4)
            val body = ByteArray(total - 12)
            buf.get(body)
            assertEquals("trailing length must equal leading length", total, buf.getInt())
            assertEquals(start + total, buf.position())
            out += Block(type, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN))
        }
        assertEquals("no trailing garbage", 0, buf.remaining())
        return out
    }

    private fun options(body: ByteBuffer): Map<Int, List<ByteArray>> {
        val out = LinkedHashMap<Int, MutableList<ByteArray>>()
        while (body.remaining() >= 4) {
            val code = body.getShort().toInt() and 0xFFFF
            val len = body.getShort().toInt() and 0xFFFF
            if (code == 0 && len == 0) break
            val value = ByteArray(len)
            body.get(value)
            body.position(body.position() + (4 - len % 4) % 4)
            out.getOrPut(code) { ArrayList() } += value
        }
        return out
    }

    private data class Epb(val ifId: Int, val tsMicros: Long, val captured: Int, val original: Int, val data: ByteArray, val options: Map<Int, List<ByteArray>>)

    private fun epb(block: Block): Epb {
        assertEquals(PcapNgWriter.BLOCK_EPB, block.type)
        val b = block.body
        val ifId = b.getInt()
        val hi = b.getInt().toLong() and 0xFFFFFFFFL
        val lo = b.getInt().toLong() and 0xFFFFFFFFL
        val captured = b.getInt()
        val original = b.getInt()
        val data = ByteArray(captured)
        b.get(data)
        b.position(b.position() + (4 - captured % 4) % 4)
        return Epb(ifId, (hi shl 32) or lo, captured, original, data, options(b))
    }

    private fun write(vararg frames: Triple<ByteArray, Boolean?, PcapNgWriter.FlowAnnotation?>, ts: Long = 1_700_000_000_123_456L): List<Block> {
        val out = ByteArrayOutputStream()
        val w = PcapNgWriter(out, snapLen = 64)
        frames.forEachIndexed { i, (frame, outbound, ann) -> w.writePacket(ts + i, frame, outbound = outbound, annotation = ann) }
        w.close()
        assertEquals(frames.size.toLong(), w.packetCount)
        return blocks(out.toByteArray())
    }

    // ── writer ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `header is one SHB then one IDB with LINKTYPE_RAW and microsecond resolution`() {
        val all = write()
        assertEquals(2, all.size)
        val shb = all[0]
        assertEquals(PcapNgWriter.BLOCK_SHB, shb.type)
        assertEquals(PcapNgWriter.BYTE_ORDER_MAGIC, shb.body.getInt())
        assertEquals(1, shb.body.getShort().toInt())
        assertEquals(0, shb.body.getShort().toInt())
        assertEquals(-1L, shb.body.getLong())
        val shbOpts = options(shb.body)
        assertEquals("requestr", String(shbOpts.getValue(PcapNgWriter.OPT_SHB_USERAPPL).single()))

        val idb = all[1]
        assertEquals(PcapNgWriter.BLOCK_IDB, idb.type)
        assertEquals(PcapNgWriter.LINKTYPE_RAW, idb.body.getShort().toInt())
        idb.body.getShort()
        assertEquals(64, idb.body.getInt())
        val idbOpts = options(idb.body)
        assertEquals("requestr-tun", String(idbOpts.getValue(PcapNgWriter.OPT_IF_NAME).single()))
        assertArrayEquals(byteArrayOf(6), idbOpts.getValue(PcapNgWriter.OPT_IF_TSRESOL).single())
    }

    @Test
    fun `EPB carries timestamp, direction flag and the flow comment, padded to 32 bits`() {
        val frame = ByteArray(45) { it.toByte() } // 45 -> 3 bytes of padding
        val ann = PcapNgWriter.FlowAnnotation(
            flowId = 42, uid = 10123, packageName = "com.example.app", attribution = "BRIDGE_INFO",
            source = InetSocketAddress(InetAddress.getByName("10.0.0.2"), 51000),
            destination = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443),
            isTcp = true,
        )
        val all = write(Triple(frame, true, ann), Triple(frame, false, null), Triple(frame, null, PcapNgWriter.FlowAnnotation()))
        assertEquals(5, all.size)

        val first = epb(all[2])
        assertEquals(0, first.ifId)
        assertEquals(1_700_000_000_123_456L, first.tsMicros)
        assertEquals(45, first.captured)
        assertEquals(45, first.original)
        assertArrayEquals(frame, first.data)
        assertEquals(
            "uid=10123 pkg=com.example.app flow=42 src=10.0.0.2:51000 dst=93.184.216.34:443 proto=tcp attribution=BRIDGE_INFO",
            String(first.options.getValue(PcapNgWriter.OPT_COMMENT).single()),
        )
        assertEquals(PcapNgWriter.EPB_FLAG_OUTBOUND, ByteBuffer.wrap(first.options.getValue(PcapNgWriter.OPT_EPB_FLAGS).single()).order(ByteOrder.LITTLE_ENDIAN).getInt())

        val second = epb(all[3])
        assertEquals(1_700_000_000_123_457L, second.tsMicros)
        assertNull("no annotation -> no comment option", second.options[PcapNgWriter.OPT_COMMENT])
        assertEquals(PcapNgWriter.EPB_FLAG_INBOUND, ByteBuffer.wrap(second.options.getValue(PcapNgWriter.OPT_EPB_FLAGS).single()).order(ByteOrder.LITTLE_ENDIAN).getInt())

        val third = epb(all[4])
        assertNull("empty annotation -> no comment option", third.options[PcapNgWriter.OPT_COMMENT])
        assertEquals(0, ByteBuffer.wrap(third.options.getValue(PcapNgWriter.OPT_EPB_FLAGS).single()).order(ByteOrder.LITTLE_ENDIAN).getInt())
    }

    @Test
    fun `frames longer than snaplen are truncated with the original length preserved`() {
        val frame = ByteArray(200) { 0x5A }
        val all = write(Triple(frame, true, null))
        val e = epb(all[2])
        assertEquals(64, e.captured)
        assertEquals(200, e.original)
        assertArrayEquals(frame.copyOf(64), e.data)
    }

    @Test
    fun `writes after close are dropped and IPv6 addresses are bracketed in comments`() {
        val out = ByteArrayOutputStream()
        val w = PcapNgWriter(out)
        w.close()
        w.writePacket(1L, ByteArray(20))
        assertEquals(0L, w.packetCount)
        assertEquals(2, blocks(out.toByteArray()).size)

        val ann = PcapNgWriter.FlowAnnotation(source = InetSocketAddress(InetAddress.getByName("2001:db8::1"), 5), isTcp = false)
        assertEquals("src=[2001:db8:0:0:0:0:0:1]:5 proto=udp", ann.commentText())
        assertTrue(PcapNgWriter.FlowAnnotation().isEmpty)
    }

    // ── tee recorder ────────────────────────────────────────────────────────────────────────

    private fun v4(proto: Int, src: String, sport: Int, dst: String, dport: Int, payload: Int = 8): ByteArray {
        val f = ByteArray(20 + 8 + payload)
        f[0] = 0x45
        f[9] = proto.toByte()
        InetAddress.getByName(src).address.copyInto(f, 12)
        InetAddress.getByName(dst).address.copyInto(f, 16)
        f[20] = (sport shr 8).toByte(); f[21] = sport.toByte()
        f[22] = (dport shr 8).toByte(); f[23] = dport.toByte()
        return f
    }

    private fun v6(proto: Int, src: String, sport: Int, dst: String, dport: Int): ByteArray {
        val f = ByteArray(40 + 8)
        f[0] = 0x60
        f[6] = proto.toByte()
        InetAddress.getByName(src).address.copyInto(f, 8)
        InetAddress.getByName(dst).address.copyInto(f, 24)
        f[40] = (sport shr 8).toByte(); f[41] = sport.toByte()
        f[42] = (dport shr 8).toByte(); f[43] = dport.toByte()
        return f
    }

    private fun sa(host: String, port: Int) = InetSocketAddress(InetAddress.getByName(host), port)

    @Test
    fun `parseTuple recovers TCP and UDP 4-tuples from IPv4 and IPv6 and rejects the rest`() {
        val t4 = PacketTeeRecorder.parseTuple(v4(6, "10.0.0.2", 51000, "93.184.216.34", 443))
        assertEquals(PacketTeeRecorder.FrameTuple(sa("10.0.0.2", 51000), sa("93.184.216.34", 443), isTcp = true), t4)
        val u6 = PacketTeeRecorder.parseTuple(v6(17, "fd00::2", 40000, "2606:4700::1111", 53))
        assertEquals(PacketTeeRecorder.FrameTuple(sa("fd00::2", 40000), sa("2606:4700::1111", 53), isTcp = false), u6)
        assertNull("ICMP has no ports", PacketTeeRecorder.parseTuple(v4(1, "10.0.0.2", 0, "1.1.1.1", 0)))
        assertNull("truncated header", PacketTeeRecorder.parseTuple(byteArrayOf(0x45, 0, 0)))
        assertNull("empty frame", PacketTeeRecorder.parseTuple(ByteArray(0)))
        assertNull("unknown IP version", PacketTeeRecorder.parseTuple(ByteArray(40) { 0x30 }))
    }

    private class Recorded(val ts: Long, val frame: ByteArray, val outbound: Boolean, val annotation: PcapNgWriter.FlowAnnotation?)

    @Test
    fun `tee frames are annotated by their client tuple in both directions and attribution runs once per flow`() {
        val lookups = ArrayList<Pair<InetSocketAddress, Boolean>>()
        val recorded = ArrayList<Recorded>()
        val client = sa("10.0.0.2", 51000)
        val recorder = PacketTeeRecorder(
            annotator = { tuple, isTcp ->
                lookups += tuple to isTcp
                if (tuple == client) PcapNgWriter.FlowAnnotation(flowId = 7, uid = 10001, packageName = "com.app", attribution = "BRIDGE_INFO") else null
            },
            sink = { ts, frame, outbound, ann -> recorded += Recorded(ts, frame, outbound, ann) },
            clock = { 1234L },
        )
        val outbound = v4(6, "10.0.0.2", 51000, "93.184.216.34", 443)
        val inbound = v4(6, "93.184.216.34", 443, "10.0.0.2", 51000)
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, outbound.size, isOutbound = true, rawFrame = outbound))
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, inbound.size, isOutbound = false, rawFrame = inbound))
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, outbound.size, isOutbound = true, rawFrame = outbound))
        // Unrelated events are ignored.
        recorder.onBridgeEvent(BridgeEvent.SessionEnd(7, 1, 1, "eof"))

        assertEquals(3, recorded.size)
        assertEquals(listOf(client to true), lookups)
        assertEquals(3L, recorder.frameCount)
        assertEquals(3L, recorder.annotatedCount)
        assertEquals(0L, recorder.unparsedCount)

        val first = recorded[0]
        assertEquals(1234L, first.ts)
        assertTrue(first.outbound)
        assertNotNull(first.annotation)
        assertEquals(7L, first.annotation!!.flowId)
        assertEquals(10001, first.annotation.uid)
        assertEquals("com.app", first.annotation.packageName)
        assertEquals(sa("10.0.0.2", 51000), first.annotation.source)
        assertEquals(sa("93.184.216.34", 443), first.annotation.destination)
        assertEquals(true, first.annotation.isTcp)

        val second = recorded[1]
        assertEquals(false, second.outbound)
        assertEquals(7L, second.annotation!!.flowId)
        assertEquals("reply frame keeps its own wire tuple", sa("93.184.216.34", 443), second.annotation.source)
        assertEquals(sa("10.0.0.2", 51000), second.annotation.destination)
    }

    @Test
    fun `unknown flows are written without a flow id, retried, and unparseable frames still reach the file`() {
        var known = false
        var lookups = 0
        val recorded = ArrayList<Recorded>()
        val recorder = PacketTeeRecorder(
            annotator = { _, _ -> lookups++; if (known) PcapNgWriter.FlowAnnotation(flowId = 9, uid = 10002) else null },
            sink = { ts, frame, outbound, ann -> recorded += Recorded(ts, frame, outbound, ann) },
        )
        val frame = v4(17, "10.0.0.2", 40000, "8.8.8.8", 53)
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, frame.size, true, frame))
        assertNull(recorded[0].annotation!!.flowId)
        assertNull(recorded[0].annotation!!.uid)
        assertEquals("src=10.0.0.2:40000 dst=8.8.8.8:53 proto=udp", recorded[0].annotation!!.commentText())
        assertEquals(0L, recorder.annotatedCount)

        known = true // the session_start arrived late
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, frame.size, true, frame))
        assertEquals(9L, recorded[1].annotation!!.flowId)
        assertEquals(2, lookups)
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, frame.size, true, frame))
        assertEquals("resolved flows are cached", 2, lookups)
        assertEquals(2L, recorder.annotatedCount)

        val icmp = v4(1, "10.0.0.2", 0, "1.1.1.1", 0)
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, icmp.size, true, icmp))
        assertEquals(4, recorded.size)
        assertNull("no tuple -> no annotation, but the frame is kept", recorded[3].annotation)
        assertEquals(1L, recorder.unparsedCount)

        recorder.forget(sa("10.0.0.2", 40000), isTcp = false)
        recorder.onBridgeEvent(BridgeEvent.PacketTee(0, frame.size, true, frame))
        assertEquals("forget() forces a fresh lookup", 3, lookups)
    }
}
