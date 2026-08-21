package com.example.interception.packet

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * PCAP-format packet capture for Wireshark/pcap analysis.
 * Writes raw IP packets to standard .pcap format.
 *
 * Format: PCAP global header (24B) + per-packet headers (16B) + payload
 * Link type: 228 (LINKTYPE_IPV4)
 */
object PacketCaptureEngine {
    private const val TAG = "PacketCapture"
    private const val PCAP_MAGIC = 0xA1B2C3D4L
    private const val LINKTYPE_IPV4 = 228

    private var output: OutputStream? = null
    private var packetCount = 0L
    private var active = false

    fun start(context: Context, file: File? = null): Boolean {
        if (active) return true
        val captureFile = file ?: File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "requestr/capture_${System.currentTimeMillis()}.pcap"
        ).also { it.parentFile?.mkdirs() }

        return try {
            output = FileOutputStream(captureFile).apply {
                writeGlobalHeader()
            }
            packetCount = 0
            active = true
            Log.i(TAG, "PCAP started → ${captureFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PCAP start failed: ${e.message}")
            false
        }
    }

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
