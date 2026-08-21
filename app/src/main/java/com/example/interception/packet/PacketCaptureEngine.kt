package com.example.interception.packet

import android.content.Context
import android.util.Log
import com.example.data.export.MediaStoreExporter
import com.example.data.export.MediaStoreExporter.ExportTarget
import java.io.OutputStream

/**
 * PCAP-format packet capture for Wireshark/pcap analysis.
 * Writes raw IP packets to standard .pcap format.
 *
 * Format: PCAP global header (24B) + per-packet headers (16B) + payload
 * Link type: 228 (LINKTYPE_IPV4)
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

    private var output: OutputStream? = null
    private var packetCount = 0L
    private var active = false
    // T1: remembered so the Settings/Capture Setup UI can offer a "share last capture"
    // action without needing to re-derive the timestamped filename itself. Survives
    // stop() (cleared only when a new session actually starts writing a new file).
    private var lastTarget: ExportTarget? = null

    fun start(context: Context, displayName: String? = null): Boolean {
        if (active) return true
        val name = displayName ?: "capture_${System.currentTimeMillis()}.pcap"

        return try {
            val (target, stream) = MediaStoreExporter.createAndOpen(context, name, "application/vnd.tcpdump.pcap")
                ?: run {
                    Log.e(TAG, "PCAP start failed: could not create $name in Downloads/requestr/")
                    return false
                }
            stream.writeGlobalHeader()
            output = stream
            packetCount = 0
            active = true
            lastTarget = target
            Log.i(TAG, "PCAP started → Downloads/requestr/$name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PCAP start failed: ${e.message}")
            false
        }
    }

    /** T1: the most recently written .pcap export (from this session or a prior one), if any. */
    fun getLastExport(): ExportTarget? = lastTarget

    fun stop() {
        active = false
        try { output?.close() } catch (_: Exception) {}
        output = null
        Log.i(TAG, "PCAP stopped — $packetCount packets")
    }

    fun writePacket(timestampSec: Long, timestampUsec: Long, data: ByteArray, offset: Int = 0, len: Int = data.size - offset) {
        if (!active || output == null) return
        try {
            val o = output ?: return
            o.write(intToLE(timestampSec.toInt()))
            o.write(intToLE(timestampUsec.toInt()))
            o.write(intToLE(len))
            o.write(intToLE(len))
            o.write(data, offset, len)
            o.flush()
            packetCount++
        } catch (_: Exception) {}
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
