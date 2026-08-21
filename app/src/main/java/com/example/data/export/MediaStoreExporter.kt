package com.example.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Shared "write a file the user can actually find in Downloads/requestr/" helper.
 *
 * Root cause of complaint #5 (PCAP/dump/hex export produce 0 files): every writer in this app
 * ([com.example.interception.packet.PacketCaptureEngine], [com.example.interception.CaptureEngine]'s
 * dump-to-file, and [FileExporter]'s CA/HAR/JSON/cURL writers) built a path via
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` and wrote to it with a
 * raw `File`/`FileOutputStream`. That works on API < 29, but from Android 10 (API 29) onward,
 * scoped storage means an app cannot create files in the public Downloads directory via the
 * plain `File` API at all -- `mkdirs()`/`FileOutputStream` either silently no-ops or throws
 * `EACCES`/`FileNotFoundException`, which every call site here swallowed with a bare
 * `catch (Exception)`, `runCatching { }`, or simply never checked -- so capture "succeeded"
 * (packetCount/transactionCount kept incrementing) while zero bytes ever reached disk. This
 * app's targetSdk is 36, so scoped storage is mandatory, not opt-out.
 *
 * The fix: on API 29+, create the file via `MediaStore.Downloads` with
 * `RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/requestr"` and write through the
 * returned `content://` `Uri`. This requires **no runtime permission** -- an app can always
 * insert/write its own MediaStore entries. On API < 29 (pre-scoped-storage), fall back to the
 * original direct `File` write, which still works there.
 */
object MediaStoreExporter {

    private const val SUBDIR = "requestr"

    /** Where a created export actually landed -- callers need this to write bytes and to share it later. */
    sealed class ExportTarget {
        /** API 29+: a MediaStore content Uri. Already shareable as-is (no FileProvider needed). */
        data class MediaStoreTarget(val uri: Uri, val displayName: String) : ExportTarget()

        /** API < 29 fallback: a plain File in the public Downloads dir, shared via FileProvider. */
        data class FileTarget(val file: File) : ExportTarget()
    }

    /**
     * Creates (or reuses, for callers that generate their own unique names) an entry named
     * [displayName] in Downloads/requestr/ and returns a handle to write to it.
     * Returns null if the entry could not be created at all (e.g. MediaStore insert failed).
     */
    fun create(context: Context, displayName: String, mimeType: String): ExportTarget? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            uri?.let { ExportTarget.MediaStoreTarget(it, displayName) }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
            dir.mkdirs()
            ExportTarget.FileTarget(File(dir, displayName))
        }
    }

    /** Opens a writable stream for a target created by [create]. Caller is responsible for closing it. */
    fun openOutputStream(context: Context, target: ExportTarget): OutputStream? = when (target) {
        is ExportTarget.MediaStoreTarget -> context.contentResolver.openOutputStream(target.uri)
        is ExportTarget.FileTarget -> FileOutputStream(target.file)
    }

    /** Convenience: create + open in one call. Returns null if either step fails. */
    fun createAndOpen(context: Context, displayName: String, mimeType: String): Pair<ExportTarget, OutputStream>? {
        val target = create(context, displayName, mimeType) ?: return null
        val stream = runCatching { openOutputStream(context, target) }.getOrNull() ?: return null
        return target to stream
    }

    /** Writes [bytes] in one shot to a new Downloads/requestr/[displayName] entry. */
    fun writeBytes(context: Context, displayName: String, mimeType: String, bytes: ByteArray): ExportTarget? {
        val (target, stream) = createAndOpen(context, displayName, mimeType) ?: return null
        return try {
            stream.use { it.write(bytes) }
            target
        } catch (_: Exception) {
            null
        }
    }

    /** Writes [text] in one shot to a new Downloads/requestr/[displayName] entry. */
    fun writeText(context: Context, displayName: String, mimeType: String, text: String): ExportTarget? =
        writeBytes(context, displayName, mimeType, text.toByteArray())
}
