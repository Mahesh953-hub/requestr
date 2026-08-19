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
 * Dedicated file exporter for requestr. All exports write actual files to
 * a dedicated app folder rather than sharing raw text via ACTION_SEND.
 *
 * Export folder: {externalFilesDir}/Documents/requestr/exports/
 * Survives normal app updates; removed when app is uninstalled.
 */
object FileExporter {

    private const val EXPORT_SUBDIR = "requestr/exports"

    /** Resolve (and lazily create) the export folder. */
    fun getExportFolder(context: Context): File {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir // fallback to internal storage
        return File(docsDir, EXPORT_SUBDIR).apply { mkdirs() }
    }

    // ─── File writers ────────────────────────────────────────────────

    // ─── Blocking variants (for use outside coroutines) ─────────────

    fun writePemBlocking(context: Context, pem: String): File {
        val file = File(getExportFolder(context), "requestr-ca-${timestamp()}.pem")
        file.writeText(pem)
        return file
    }

    fun writeHarBlocking(context: Context, har: String, host: String): File {
        val safe = host.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(32)
        val file = File(getExportFolder(context), "har-${safe}-${timestamp()}.har")
        file.writeText(har)
        return file
    }

    // ─── Suspend variants (coroutine-friendly) ──────────────────────

    suspend fun writePem(context: Context, pem: String): File = withContext(Dispatchers.IO) {
        writePemBlocking(context, pem)
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

    // ─── Share helper ────────────────────────────────────────────────

    /**
     * Share a file via the system share sheet using FileProvider.
     * Requires a FileProvider declaration in AndroidManifest.xml.
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
