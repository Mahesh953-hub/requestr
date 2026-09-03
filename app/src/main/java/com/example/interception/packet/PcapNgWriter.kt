package com.example.interception.packet

import com.example.interception.bridge.BridgeEvent
import com.example.interception.bridge.BridgeEventListener
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * S1 task 12: PCAPng writer for the native bridge's packet tee.
 *
 * Emits exactly the three block types Wireshark needs -- one Section Header Block (SHB), one
 * Interface Description Block (IDB, `LINKTYPE_RAW` so IPv4 and IPv6 frames share the interface)
 * and one Enhanced Packet Block (EPB) per frame. Every EPB carries `epb_flags` with the
 * direction bit and, when the flow is known, an `opt_comment` of the form
 * `uid=10123 pkg=com.example.app flow=42 src=10.0.0.2:51000 dst=93.184.216.34:443 proto=tcp
 * attribution=BRIDGE_INFO`, which Wireshark shows in the packet comment column and which the
 * `frame.comment` display filter can search. All integers are little-endian (byte-order magic
 * `0x1A2B3C4D`), timestamps are microseconds since the epoch (`if_tsresol = 6`).
 *
 * Pure JVM: no Android types, so the exact bytes are asserted in `PcapNgWriterTest`. Writes are
 * serialised on the writer -- the tee delivers frames on the bridge listener thread while the
 * legacy tun pump writes from its own reader.
 */
class PcapNgWriter(
    private val out: OutputStream,
    private val snapLen: Int = DEFAULT_SNAPLEN,
    interfaceName: String = "requestr-tun",
    userApplication: String = "requestr",
) {
    /** What is known about the flow a frame belongs to; every field is optional. */
    data class FlowAnnotation(
        /** Native bridge session id (`flow=` in the comment); null when the frame matched no session. */
        val flowId: Long? = null,
        val uid: Int? = null,
        val packageName: String? = null,
        /** [com.example.interception.uid.UidAttributor.Source] name, or the unattributed reason. */
        val attribution: String? = null,
        val source: InetSocketAddress? = null,
        val destination: InetSocketAddress? = null,
        val isTcp: Boolean? = null,
    ) {
        /** Human-readable `key=value` list; empty when nothing is known. */
        fun commentText(): String = buildList {
            uid?.let { add("uid=$it") }
            packageName?.let { add("pkg=$it") }
            flowId?.let { add("flow=$it") }
            source?.let { add("src=${format(it)}") }
            destination?.let { add("dst=${format(it)}") }
            isTcp?.let { add("proto=${if (it) "tcp" else "udp"}") }
            attribution?.let { add("attribution=$it") }
        }.joinToString(" ")

        val isEmpty: Boolean get() = commentText().isEmpty()

        private fun format(address: InetSocketAddress): String {
            val host = address.address?.hostAddress ?: address.hostString
            return if (host.contains(':')) "[$host]:${address.port}" else "$host:${address.port}"
        }
    }

    private val lock = Any()
    private var closed = false
    private val written = AtomicLong()
    /** One scratch buffer, reused under [lock]: `[type][total][body...][total]`. Declared before `init`. */
    private val scratch: ByteBuffer = ByteBuffer.allocate(snapLen + BLOCK_OVERHEAD).order(ByteOrder.LITTLE_ENDIAN)

    /** Frames written so far (diagnostics / UI). */
    val packetCount: Long get() = written.get()

    init {
        synchronized(lock) {
            writeBlock(BLOCK_SHB) { body ->
                body.putInt(BYTE_ORDER_MAGIC)
                body.putShort(1).putShort(0) // major.minor
                body.putLong(-1L) // section length unknown
                putOption(body, OPT_SHB_USERAPPL, userApplication.toByteArray(Charsets.UTF_8))
                putEndOfOptions(body)
            }
            writeBlock(BLOCK_IDB) { body ->
                body.putShort(LINKTYPE_RAW.toShort()).putShort(0)
                body.putInt(snapLen)
                putOption(body, OPT_IF_NAME, interfaceName.toByteArray(Charsets.UTF_8))
                putOption(body, OPT_IF_TSRESOL, byteArrayOf(6))
                putEndOfOptions(body)
            }
            out.flush()
        }
    }

    /**
     * Writes one Enhanced Packet Block. [timestampMicros] is microseconds since the epoch;
     * [outbound] (client app -> network) is recorded in `epb_flags`; frames longer than
     * [snapLen] are truncated with the original length preserved.
     */
    fun writePacket(
        timestampMicros: Long,
        frame: ByteArray,
        offset: Int = 0,
        length: Int = frame.size - offset,
        outbound: Boolean? = null,
        annotation: FlowAnnotation? = null,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= frame.size) { "frame slice out of range" }
        val captured = minOf(length, snapLen)
        val comment = annotation?.commentText()?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            if (closed) return
            writeBlock(BLOCK_EPB) { body ->
                body.putInt(0) // interface id
                body.putInt((timestampMicros ushr 32).toInt())
                body.putInt(timestampMicros.toInt())
                body.putInt(captured)
                body.putInt(length)
                body.put(frame, offset, captured)
                pad(body, captured)
                if (comment != null) putOption(body, OPT_COMMENT, comment)
                val flags = when (outbound) {
                    true -> EPB_FLAG_OUTBOUND
                    false -> EPB_FLAG_INBOUND
                    null -> 0
                }
                putOption(body, OPT_EPB_FLAGS, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags).array())
                putEndOfOptions(body)
            }
            out.flush()
            written.incrementAndGet()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    // ── block encoding ───────────────────────────────────────────────────────────────────────

    private fun writeBlock(type: Int, fill: (ByteBuffer) -> Unit) {
        val block = scratch
        block.clear()
        block.putInt(type)
        block.putInt(0) // total length, patched below
        fill(block)
        val total = block.position() + 4
        block.putInt(total)
        block.putInt(4, total)
        out.write(block.array(), 0, total)
    }

    private fun putOption(body: ByteBuffer, code: Int, value: ByteArray) {
        val v = if (value.size > MAX_OPTION_BYTES) value.copyOf(MAX_OPTION_BYTES) else value
        body.putShort(code.toShort())
        body.putShort(v.size.toShort())
        body.put(v)
        pad(body, v.size)
    }

    private fun putEndOfOptions(body: ByteBuffer) {
        body.putShort(0).putShort(0)
    }

    private fun pad(body: ByteBuffer, length: Int) {
        var padding = (4 - (length % 4)) % 4
        while (padding-- > 0) body.put(0)
    }

    companion object {
        const val BLOCK_SHB = 0x0A0D0D0A
        const val BLOCK_IDB = 0x00000001
        const val BLOCK_EPB = 0x00000006
        const val BYTE_ORDER_MAGIC = 0x1A2B3C4D
        /** Raw IP; the version nibble of each frame selects IPv4 or IPv6. */
        const val LINKTYPE_RAW = 101
        const val OPT_COMMENT = 1
        const val OPT_SHB_USERAPPL = 4
        const val OPT_IF_NAME = 2
        const val OPT_IF_TSRESOL = 9
        const val OPT_EPB_FLAGS = 2
        /** `epb_flags` direction bits: 01 = inbound, 10 = outbound. */
        const val EPB_FLAG_INBOUND = 0x1
        const val EPB_FLAG_OUTBOUND = 0x2
        /** Matches the bridge's `MAX_TEE_FRAME_BYTES` (largest frame the tee ever delivers). */
        const val DEFAULT_SNAPLEN = 9000
        private const val MAX_OPTION_BYTES = 0xFFFF - 3
        private const val BLOCK_OVERHEAD = 1024

        /** Convenience for callers that track wall-clock milliseconds. */
        fun microsOf(epochMillis: Long): Long = epochMillis * 1000
    }
}

/**
 * S1 task 12: consumes [BridgeEvent.PacketTee] frames and hands them to [PacketCaptureEngine]
 * with a per-flow [PcapNgWriter.FlowAnnotation].
 *
 * The tee sits at the raw tun device inside the vendored tun2proxy (`tee_device.rs`), *below*
 * its session table, so every teed frame arrives with `sessionId == 0`. The flow is therefore
 * recovered here from the IP/transport header: the client-side tuple (the frame's source for
 * outbound frames, its destination for inbound ones) is the same `src` the bridge reports in
 * `session_start`, which [annotator] resolves to the session id, UID and package. Annotations
 * are cached per client tuple ([maxCachedFlows] entries, LRU) so attribution runs once per flow
 * rather than once per packet.
 *
 * Nothing here blocks the bridge listener thread on Room or the network: the only work per
 * frame is header parsing plus a synchronous file write, both bounded.
 */
class PacketTeeRecorder(
    /** Resolves the client-side tuple of a frame to its annotation (`null` = nothing known). */
    private val annotator: (clientTuple: InetSocketAddress, isTcp: Boolean) -> PcapNgWriter.FlowAnnotation?,
    /** Where frames go; defaults to the process-wide [PacketCaptureEngine]. */
    private val sink: (timestampMillis: Long, frame: ByteArray, outbound: Boolean, annotation: PcapNgWriter.FlowAnnotation?) -> Unit =
        { ts, frame, outbound, annotation ->
            PacketCaptureEngine.writePacket(
                timestampSec = ts / 1000,
                timestampUsec = (ts % 1000) * 1000,
                data = frame,
                outbound = outbound,
                annotation = annotation,
            )
        },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCachedFlows: Int = 2048,
) : BridgeEventListener {

    /** Parsed transport tuple of a teed frame. */
    data class FrameTuple(
        val source: InetSocketAddress,
        val destination: InetSocketAddress,
        val isTcp: Boolean,
    )

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Pair<InetSocketAddress, Boolean>, PcapNgWriter.FlowAnnotation?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<InetSocketAddress, Boolean>, PcapNgWriter.FlowAnnotation?>): Boolean =
            size > maxCachedFlows
    }

    private val framesSeen = AtomicLong()
    private val framesAnnotated = AtomicLong()
    private val framesUnparsed = AtomicLong()

    /** Tee frames received (whether or not the writer was active). */
    val frameCount: Long get() = framesSeen.get()
    /** Frames that resolved to a known bridge session. */
    val annotatedCount: Long get() = framesAnnotated.get()
    /** Frames whose IP/transport header could not be parsed (written without a flow comment). */
    val unparsedCount: Long get() = framesUnparsed.get()

    override fun onBridgeEvent(event: BridgeEvent) {
        if (event !is BridgeEvent.PacketTee) return
        framesSeen.incrementAndGet()
        val tuple = parseTuple(event.rawFrame)
        val annotation = if (tuple == null) {
            framesUnparsed.incrementAndGet()
            null
        } else {
            val client = if (event.isOutbound) tuple.source else tuple.destination
            val resolved = annotationFor(client, tuple.isTcp)
            if (resolved?.flowId != null) framesAnnotated.incrementAndGet()
            // Always record the tuple we parsed, even when the bridge knows nothing about it.
            (resolved ?: PcapNgWriter.FlowAnnotation()).copy(
                source = tuple.source,
                destination = tuple.destination,
                isTcp = tuple.isTcp,
            )
        }
        runCatching { sink(clock(), event.rawFrame, event.isOutbound, annotation) }
    }

    /** Drops a flow's cached annotation (call on `session_end`, or when attribution may have improved). */
    fun forget(clientTuple: InetSocketAddress, isTcp: Boolean) {
        synchronized(cacheLock) { cache.remove(clientTuple to isTcp) }
    }

    private fun annotationFor(client: InetSocketAddress, isTcp: Boolean): PcapNgWriter.FlowAnnotation? {
        val key = client to isTcp
        synchronized(cacheLock) {
            if (cache.containsKey(key)) {
                val hit = cache[key]
                // Unknown flows are retried: the session_start may simply not have arrived yet.
                if (hit?.flowId != null) return hit
            }
        }
        val fresh = runCatching { annotator(client, isTcp) }.getOrNull()
        synchronized(cacheLock) { cache[key] = fresh }
        return fresh
    }

    companion object {
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17

        /**
         * Extracts the transport 4-tuple from a raw IPv4/IPv6 frame; null for anything that is
         * not TCP/UDP or is too short. IPv6 extension headers are not walked -- a frame whose
         * next header is not TCP/UDP is treated as unparsed, never mis-attributed.
         */
        fun parseTuple(frame: ByteArray): FrameTuple? {
            if (frame.isEmpty()) return null
            return when ((frame[0].toInt() ushr 4) and 0xF) {
                4 -> parseV4(frame)
                6 -> parseV6(frame)
                else -> null
            }
        }

        private fun parseV4(f: ByteArray): FrameTuple? {
            if (f.size < 20) return null
            val ihl = (f[0].toInt() and 0xF) * 4
            if (ihl < 20 || f.size < ihl + 4) return null
            val proto = f[9].toInt() and 0xFF
            val isTcp = when (proto) {
                PROTO_TCP -> true
                PROTO_UDP -> false
                else -> return null
            }
            val src = InetAddress.getByAddress(f.copyOfRange(12, 16))
            val dst = InetAddress.getByAddress(f.copyOfRange(16, 20))
            return FrameTuple(
                InetSocketAddress(src, port(f, ihl)),
                InetSocketAddress(dst, port(f, ihl + 2)),
                isTcp,
            )
        }

        private fun parseV6(f: ByteArray): FrameTuple? {
            if (f.size < 44) return null
            val proto = f[6].toInt() and 0xFF
            val isTcp = when (proto) {
                PROTO_TCP -> true
                PROTO_UDP -> false
                else -> return null
            }
            val src = InetAddress.getByAddress(f.copyOfRange(8, 24))
            val dst = InetAddress.getByAddress(f.copyOfRange(24, 40))
            return FrameTuple(
                InetSocketAddress(src, port(f, 40)),
                InetSocketAddress(dst, port(f, 42)),
                isTcp,
            )
        }

        private fun port(f: ByteArray, at: Int): Int = ((f[at].toInt() and 0xFF) shl 8) or (f[at + 1].toInt() and 0xFF)
    }
}
