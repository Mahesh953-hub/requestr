package com.example.data.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.data.export.MediaStoreExporter.ExportTarget
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
 * Complaint #5 audit fix: every writer here used to build a path with
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` and write via a raw
 * `File`/`FileOutputStream`. On API 29+ (this app's targetSdk is 36, so always) scoped storage
 * makes that a silent no-op or `EACCES` -- every call site here swallowed the failure with
 * `runCatching { }`, so exports appeared to succeed while producing zero bytes on disk. All
 * writers now go through [MediaStoreExporter], which uses `MediaStore.Downloads` on API 29+
 * (falling back to the old direct-File approach pre-29, where it still works). See
 * [MediaStoreExporter] for the full explanation.
 */
object FileExporter {

    /**
     * Pre-scoped-storage (API < 29) fallback folder, still used internally by
     * [MediaStoreExporter] and by [com.example.interception.RootModuleGenerator]'s zip export.
     * Prefer [MediaStoreExporter] for anything new -- this is kept only for callers that need
     * the plain-File fallback path directly.
     */
    fun getExportFolder(context: Context): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val primary = File(downloads, "requestr")
        if (primary.mkdirs() || primary.isDirectory) return primary

        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(docsDir, "requestr/exports").apply { mkdirs() }
    }

    // ─── CA Certificate Export (multiple formats) ───────────────────────

    /**
     * Export CA as PEM (.pem) — standard base64 encoded X.509.
     * Filename: requestr-ca.pem
     * Path: Downloads/requestr/requestr-ca.pem
     */
    suspend fun writeCaPem(context: Context, pem: String): ExportTarget? = withContext(Dispatchers.IO) {
        MediaStoreExporter.writeText(context, "requestr-ca.pem", "application/x-pem-file", pem)
    }

    /**
     * Export CA as system hash format (.0) — for /system/etc/security/cacerts/.
     * The filename is the subject hash used by Android's system trust store.
     * Filename: <hash>.0  (e.g., a1b2c3d4.0)
     * Path: Downloads/requestr/<hash>.0
     */
    suspend fun writeCaSystemHash(context: Context, pem: String, subjectHash: String): ExportTarget? =
        withContext(Dispatchers.IO) {
            MediaStoreExporter.writeText(context, "$subjectHash.0", "application/x-x509-ca-cert", pem)
        }

    /**
     * Export CA as DER (.cert / .cer) — binary DER encoding.
     * Filename: requestr-ca.cert
     * Path: Downloads/requestr/requestr-ca.cert
     */
    suspend fun writeCaDer(context: Context, derBytes: ByteArray): ExportTarget? = withContext(Dispatchers.IO) {
        MediaStoreExporter.writeBytes(context, "requestr-ca.cert", "application/x-x509-ca-cert", derBytes)
    }

    /**
     * Export CA as PKCS#12 (.p12) — full keypair bundle with password protection.
     * Filename: requestr-ca.p12
     * Path: Downloads/requestr/requestr-ca.p12
     */
    suspend fun writeCaP12(context: Context, p12Bytes: ByteArray): ExportTarget? = withContext(Dispatchers.IO) {
        MediaStoreExporter.writeBytes(context, "requestr-ca.p12", "application/x-pkcs12", p12Bytes)
    }

    // ─── Legacy PEM writer (backward compat) ────────────────────────────

    fun writePemBlocking(context: Context, pem: String): ExportTarget? =
        MediaStoreExporter.writeText(context, "requestr-ca.pem", "application/x-pem-file", pem)

    suspend fun writePem(context: Context, pem: String): ExportTarget? = withContext(Dispatchers.IO) {
        writePemBlocking(context, pem)
    }

    // ─── Traffic / HAR / JSON / cURL writers ────────────────────────────

    /**
     * Blocking on purpose — some call sites invoke this directly from a Compose `onClick`
     * without a coroutine scope (pre-existing behaviour kept as-is; the MediaStore insert +
     * write is fast enough in practice, matching the original raw-File write it replaces).
     */
    fun writeHarBlocking(context: Context, har: String, host: String): ExportTarget? {
        val safe = host.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(32)
        return MediaStoreExporter.writeText(context, "har-$safe-${timestamp()}.har", "application/json", har)
    }

    suspend fun writeHar(context: Context, har: String, host: String): ExportTarget? = withContext(Dispatchers.IO) {
        writeHarBlocking(context, har, host)
    }

    suspend fun writeJson(context: Context, json: String): ExportTarget? = withContext(Dispatchers.IO) {
        MediaStoreExporter.writeText(context, "traffic-log-${timestamp()}.json", "application/json", json)
    }

    suspend fun writeCurl(context: Context, curl: String): ExportTarget? = withContext(Dispatchers.IO) {
        MediaStoreExporter.writeText(context, "curl-${timestamp()}.sh", "text/x-shellscript", "#!/bin/bash\n$curl\n")
    }

    // ─── Share helper ────────────────────────────────────────────────────

    /**
     * Share an export via the system share sheet.
     *
     * [MediaStoreExporter.ExportTarget.MediaStoreTarget] already carries a `content://` Uri the
     * app owns (from its own `MediaStore.Downloads` insert), so it's shared directly --
     * `FLAG_GRANT_READ_URI_PERMISSION` on the intent grants the receiving app temporary read
     * access. [MediaStoreExporter.ExportTarget.FileTarget] (pre-API-29 fallback) still needs
     * [FileProvider], exactly as before.
     */
    fun shareFile(context: Context, target: ExportTarget, mimeType: String, subject: String) {
        val (uri, label) = when (target) {
            is ExportTarget.MediaStoreTarget -> target.uri to target.displayName
            is ExportTarget.FileTarget -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                target.file
            ) to target.file.name
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Export as ${label.substringAfterLast('.', label).uppercase()}")
        )
    }

    /** Overload for callers that already have a plain [File] (pre-API-29 direct writes). */
    fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
        shareFile(context, ExportTarget.FileTarget(file), mimeType, subject)
    }

    // ─── Internal ────────────────────────────────────────────────────

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
