package com.example.interception.packet

import android.content.Context
import android.util.Log
import com.example.data.export.MediaStoreExporter
import com.example.data.export.MediaStoreExporter.ExportTarget
import java.io.OutputStream

/**
 * Packet capture export for Wireshark/pcap analysis, in one of two selectable [Format]s:
 *
 * - [Format.PCAP]: classic libpcap -- global header (24B) + per-packet headers (16B) + payload,
 *   link type 228 (`LINKTYPE_IPV4`). Byte-identical to the pre-S1 output.
 * - [Format.PCAPNG] (S1 task 12): PCAPng via [PcapNgWriter] -- `LINKTYPE_RAW` (IPv4 and IPv6),
 *   direction flags and a per-packet comment carrying UID, package name and bridge flow id.
 *
 * Both formats are fed from the same [writePacket] entry point by both packet sources: the
 * legacy tun pump (`PacketPump.writeToPcap`) and, in native-bridge mode, the tee consumer
 * [PacketTeeRecorder] -- before S1 the native bridge produced no packets at all.
 *
 * Complaint #5 audit fix: this used to build a path via
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` and open a raw
 * `FileOutputStream` on it. On API 29+ (this app's targetSdk is 36) scoped storage silently
 * blocks that -- `start()` reported `true` and `packetCount` kept incrementing, but the
 * .pcap file was never actually created, so the export button had nothing to share. Now
 * routed through [MediaStoreExporter], which uses `MediaStore.Downloads` on API 29+
 * (falling back to the old direct-File write pre-29, where it still works).
 */
object PacketCaptureEngine {
    private const val TAG = "PacketCapture"
    private const val PCAP_MAGIC = 0xA1B2C3D4L
    private const val LINKTYPE_IPV4 = 228

    /** Output container. Selected per session by [start]; see `InterceptorFlags.pcapng`. */
    enum class Format(val extension: String, val mimeType: String) {
        PCAP("pcap", "application/vnd.tcpdump.pcap"),
        PCAPNG("pcapng", "application/x-pcapng"),
    }

    private val lock = Any()
    private var output: OutputStream? = null
    private var pcapng: PcapNgWriter? = null
    private var format: Format = Format.PCAP
    private var packetCount = 0L
    @Volatile private var active = false
    // T1: remembered so the Settings/Capture Setup UI can offer a "share last capture"
    // action without needing to re-derive the timestamped filename itself. Survives
    // stop() (cleared only when a new session actually starts writing a new file).
    private var lastTarget: ExportTarget? = null

    fun start(context: Context, displayName: String? = null, format: Format = Format.PCAP): Boolean {
        synchronized(lock) {
            if (active) return true
            val name = displayName ?: "capture_${System.currentTimeMillis()}.${format.extension}"

            return try {
                val (target, stream) = MediaStoreExporter.createAndOpen(context, name, format.mimeType)
                    ?: run {
                        Log.e(TAG, "${format.name} start failed: could not create $name in Downloads/requestr/")
                        return false
                    }
                when (format) {
                    Format.PCAP -> {
                        stream.writeGlobalHeader()
                        pcapng = null
                    }
                    Format.PCAPNG -> pcapng = PcapNgWriter(stream)
                }
                output = stream
                this.format = format
                packetCount = 0
                active = true
                lastTarget = target
                Log.i(TAG, "${format.name} started → Downloads/requestr/$name")
                true
            } catch (e: Exception) {
                Log.e(TAG, "${format.name} start failed: ${e.message}")
                false
            }
        }
    }

    /** Format of the running (or most recent) session. */
    fun currentFormat(): Format = format

    /** T1: the most recently written .pcap export (from this session or a prior one), if any. */
    fun getLastExport(): ExportTarget? = lastTarget

    fun stop() {
        synchronized(lock) {
            active = false
            pcapng?.let { runCatching { it.close() } }
            pcapng = null
            try { output?.close() } catch (_: Exception) {}
            output = null
            Log.i(TAG, "${format.name} stopped — $packetCount packets")
        }
    }

    /**
     * Writes one raw IP frame. [outbound] and [annotation] (S1 task 12) are recorded only by
     * [Format.PCAPNG]; classic PCAP has nowhere to put them and ignores both.
     */
    fun writePacket(
        timestampSec: Long,
        timestampUsec: Long,
        data: ByteArray,
        offset: Int = 0,
        len: Int = data.size - offset,
        outbound: Boolean? = null,
        annotation: PcapNgWriter.FlowAnnotation? = null,
    ) {
        if (!active) return
        synchronized(lock) {
            if (!active) return
            try {
                val ng = pcapng
                if (ng != null) {
                    ng.writePacket(
                        timestampMicros = timestampSec * 1_000_000 + timestampUsec,
                        frame = data, offset = offset, length = len,
                        outbound = outbound, annotation = annotation,
                    )
                } else {
                    val o = output ?: return
                    o.write(intToLE(timestampSec.toInt()))
                    o.write(intToLE(timestampUsec.toInt()))
                    o.write(intToLE(len))
                    o.write(intToLE(len))
                    o.write(data, offset, len)
                    o.flush()
                }
                packetCount++
            } catch (_: Exception) {}
        }
    }

    private fun OutputStream.writeGlobalHeader() {
        write(intToLE(PCAP_MAGIC.toInt()))
        write(shortToLE(2))
        write(shortToLE(4))
        write(intToLE(0))
        write(intToLE(0))
        write(intToLE(65535))
        write(intToLE(LINKTYPE_IPV4))
    }

    private fun intToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(),
        (v shr 24 and 0xFF).toByte()
    )

    private fun shortToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte()
    )

    fun isActive() = active
    fun getPacketCount() = packetCount
}
