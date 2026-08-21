package com.example.data.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated file exporter for requestr. Exports write to shared storage
 * at Downloads/requestr/ so the user can easily access them with a file
 * manager or adb pull.
 *
 * Fallback: {externalFilesDir}/Documents/requestr/exports/ if shared storage
 * is unavailable.
 */
object FileExporter {

    private const val EXPORT_SUBDIR = "requestr"

    /** Resolve (and lazily create) the primary export folder in shared Downloads. */
    fun getExportFolder(context: Context): File {
        // Primary: /storage/emulated/0/Download/requestr/
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val primary = File(downloads, EXPORT_SUBDIR)
        if (primary.mkdirs() || primary.isDirectory) return primary

        // Fallback: app-private Documents
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(docsDir, "$EXPORT_SUBDIR/exports").apply { mkdirs() }
    }

    // ─── CA Certificate Export (multiple formats) ───────────────────────

    /**
     * Export CA as PEM (.pem) — standard base64 encoded X.509.
     * Filename: requestr-ca.pem
     * Path: Downloads/requestr/requestr-ca.pem
     */
    suspend fun writeCaPem(context: Context, pem: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "requestr-ca.pem")
        file.writeText(pem)
        file
    }

    /**
     * Export CA as system hash format (.0) — for /system/etc/security/cacerts/.
     * The filename is the subject hash used by Android's system trust store.
     * Filename: <hash>.0  (e.g., a1b2c3d4.0)
     * Path: Downloads/requestr/<hash>.0
     */
    suspend fun writeCaSystemHash(context: Context, pem: String, subjectHash: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "${subjectHash}.0")
        file.writeText(pem)
        file
    }

    /**
     * Export CA as DER (.cert / .cer) — binary DER encoding.
     * Filename: requestr-ca.cert
     * Path: Downloads/requestr/requestr-ca.cert
     */
    suspend fun writeCaDer(context: Context, derBytes: ByteArray): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "requestr-ca.cert")
        file.writeBytes(derBytes)
        file
    }

    /**
     * Export CA as PKCS#12 (.p12) — full keypair bundle with password protection.
     * Filename: requestr-ca.p12
     * Path: Downloads/requestr/requestr-ca.p12
     */
    suspend fun writeCaP12(context: Context, p12Bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "requestr-ca.p12")
        file.writeBytes(p12Bytes)
        file
    }

    // ─── Legacy PEM writer (backward compat) ────────────────────────────

    fun writePemBlocking(context: Context, pem: String): File {
        val file = File(getExportFolder(context), "requestr-ca.pem")
        file.writeText(pem)
        return file
    }

    suspend fun writePem(context: Context, pem: String): File = withContext(Dispatchers.IO) {
        writePemBlocking(context, pem)
    }

    // ─── Traffic / HAR / JSON / cURL writers ────────────────────────────

    fun writeHarBlocking(context: Context, har: String, host: String): File {
        val safe = host.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(32)
        val file = File(getExportFolder(context), "har-${safe}-${timestamp()}.har")
        file.writeText(har)
        return file
    }

    suspend fun writeHar(context: Context, har: String, host: String): File = withContext(Dispatchers.IO) {
        writeHarBlocking(context, har, host)
    }

    suspend fun writeJson(context: Context, json: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "traffic-log-${timestamp()}.json")
        file.writeText(json)
        file
    }

    suspend fun writeCurl(context: Context, curl: String): File = withContext(Dispatchers.IO) {
        val file = File(getExportFolder(context), "curl-${timestamp()}.sh")
        file.writeText("#!/bin/bash\n$curl\n")
        file
    }

    // ─── Share helper ────────────────────────────────────────────────────

    /**
     * Share a file via the system share sheet using FileProvider.
     */
    fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Export as ${file.extension.uppercase()}")
        )
    }

    // ─── Internal ────────────────────────────────────────────────────

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
