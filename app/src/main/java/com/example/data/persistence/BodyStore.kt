package com.example.data.persistence

import android.content.Context
import android.util.Log
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * S2 task 16 -- the body spill store (interceptor plan §4 "body-reference columns", §7 task 16).
 *
 * Bodies at or below [INLINE_LIMIT_BYTES] stay in the existing `requestBody`/`responseBody`
 * text and `requestBodyBytes`/`responseBodyBytes` BLOB columns exactly as before. Larger bodies
 * are written to `filesDir/captures/<sessionId>/<trafficId>.<req|res>.bin` -- `filesDir`, not
 * `cacheDir`, because a body referenced by a saved HAR export must not vanish under cache
 * pressure -- and the row records the relative path, the SHA-256 of the full body, the real
 * total size, how many bytes the file holds, and a bounded UTF-8 preview
 * ([PREVIEW_BYTES]) so the list/detail UI has something to show without opening the file.
 *
 * **Never materialise, never partial:** [spill] streams through a [BUFFER_BYTES] buffer while
 * hashing incrementally, writes to a temp file in the same directory and `rename`s it into
 * place, so a crash mid-write leaves an orphan temp file (removed by [sweepOrphans]) and never
 * a row pointing at a half body. The [ByteArray] overload exists for today's engines, which
 * already hold the decoded body in memory; S3's streaming parsers use the [InputStream] one.
 *
 * **Failure semantics (plan §4 failure table):** a write failure returns [Result.Failed]; the
 * caller (the persistence actor) keeps the row, sets the existing `*BodyBytesTruncated` flag so
 * the UI renders the honest "not stored" state it already knows, records the real total size,
 * and bumps [PersistenceHealth.Snapshot.spillFailures]. The parent transaction never fails
 * because of a body.
 *
 * **Lifecycle:** [deleteSession] removes a session's whole directory (session delete / prune);
 * [deleteBody] removes one file (row delete); [sweepOrphans] at startup deletes files no row
 * references and clears references whose file is gone (rendered as "body evicted").
 *
 * Rollout flag: `InterceptorFlags.bodySpill`. Off means [PersistenceQueue] has no store and
 * `HttpBodyDecoder.clampForStorage` falls back to the pre-S2 2 MiB drop.
 */
class BodyStore internal constructor(
    /** `filesDir/captures`. */
    val root: File,
    private val health: PersistenceHealth = PersistenceHealth,
    private val inlineLimit: Int = INLINE_LIMIT_BYTES,
) {

    enum class Direction(val suffix: String) { REQUEST("req"), RESPONSE("res") }

    /** What the row records for a spilled body. [relativePath] is relative to [root]. */
    data class Spilled(
        val relativePath: String,
        val sha256: String,
        val storedBytes: Long,
        val totalBytes: Long,
        val preview: String?,
    )

    sealed class Result {
        data class Stored(val spilled: Spilled) : Result()
        data class Failed(val totalBytes: Long, val reason: String) : Result()
    }

    data class SweepResult(val filesRemoved: Int, val referencesCleared: Int, val bytesFreed: Long)

    /** True when a body of [sizeBytes] must leave the inline columns. */
    fun shouldSpill(sizeBytes: Long): Boolean = sizeBytes > inlineLimit

    // -- writing -------------------------------------------------------------------------------

    /**
     * Streams [input] to the spill file for ([sessionId], [trafficId], [direction]), hashing as it
     * goes. [isText] controls whether a UTF-8 preview is produced. Never throws.
     */
    fun spill(sessionId: Long, trafficId: Long, direction: Direction, input: InputStream, isText: Boolean): Result {
        val dir = sessionDir(sessionId)
        val target = File(dir, fileName(trafficId, direction))
        var temp: File? = null
        var total = 0L
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.path}")
            temp = File(dir, ".tmp-${UUID.randomUUID()}")
            val digest = MessageDigest.getInstance("SHA-256")
            val preview = if (isText) java.io.ByteArrayOutputStream(minOf(PREVIEW_BYTES, 8 * 1024)) else null
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    total += n
                    if (preview != null && preview.size() < PREVIEW_BYTES) {
                        preview.write(buf, 0, minOf(n, PREVIEW_BYTES - preview.size()))
                    }
                }
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
            if (!temp.renameTo(target)) throw IOException("rename failed for ${target.name}")
            temp = null
            Result.Stored(
                Spilled(
                    relativePath = "$sessionId/${target.name}",
                    sha256 = digest.digest().toHex(),
                    storedBytes = total,
                    totalBytes = total,
                    preview = preview?.let { previewText(it.toByteArray()) },
                ),
            )
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            temp?.delete()
            health.recordSpillFailure()
            Log.e(TAG, "spill failed for $sessionId/${target.name}: ${t.message}")
            Result.Failed(totalBytes = total, reason = t.message ?: t.javaClass.simpleName)
        }
    }

    /** [ByteArray] convenience for engines that already hold the decoded body. */
    fun spill(sessionId: Long, trafficId: Long, direction: Direction, bytes: ByteArray, isText: Boolean): Result =
        when (val r = spill(sessionId, trafficId, direction, bytes.inputStream(), isText)) {
            is Result.Stored -> r
            is Result.Failed -> r.copy(totalBytes = bytes.size.toLong())
        }

    // -- reading -------------------------------------------------------------------------------

    /** The file for a stored [relativePath], or null when it no longer exists ("body evicted"). */
    fun file(relativePath: String): File? {
        val f = File(root, relativePath)
        // Guard against a corrupted path escaping the store.
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return null
        return f.takeIf { it.isFile }
    }

    /** Whole body, or null when evicted. Only for bodies the caller knows are small enough to hold. */
    fun read(relativePath: String): ByteArray? = file(relativePath)?.let { f -> runCatching { f.readBytes() }.getOrNull() }

    /** True when the file behind [relativePath] still hashes to [expectedSha256]. */
    fun verify(relativePath: String, expectedSha256: String): Boolean {
        val f = file(relativePath) ?: return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().toHex().equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    // -- lifecycle -----------------------------------------------------------------------------

    fun deleteBody(relativePath: String?): Boolean {
        if (relativePath == null) return false
        return file(relativePath)?.delete() == true
    }

    /** Removes every spilled body of [sessionId]. Returns bytes freed. */
    fun deleteSession(sessionId: Long): Long {
        val dir = sessionDir(sessionId)
        if (!dir.isDirectory) return 0L
        val bytes = dirBytes(dir)
        dir.deleteRecursively()
        return bytes
    }

    /** Removes the whole store (traffic "clear all"). Returns bytes freed. */
    fun deleteAll(): Long {
        if (!root.isDirectory) return 0L
        val bytes = totalBytes()
        root.listFiles()?.forEach { it.deleteRecursively() }
        return bytes
    }

    fun sessionBytes(sessionId: Long): Long = dirBytes(sessionDir(sessionId))

    fun totalBytes(): Long = dirBytes(root)

    /** Session ids that currently have a spill directory. */
    fun sessionsOnDisk(): List<Long> =
        root.listFiles()?.mapNotNull { it.takeIf(File::isDirectory)?.name?.toLongOrNull() } ?: emptyList()

    /**
     * Startup reconciliation (plan task 16 "orphan sweep"): deletes temp files and files no
     * `captured_traffic` row references, and clears `*BodyFilePath` on rows whose file is gone,
     * setting the truncated flag so the UI shows "not stored" rather than an empty body. Runs on
     * the persistence actor so it never races a live insert.
     */
    suspend fun sweepOrphans(db: RequestrDatabase): SweepResult {
        val dao = db.capturedTrafficDao()
        var filesRemoved = 0
        var bytesFreed = 0L
        var referencesCleared = 0

        val referenced = HashSet<String>()
        val rows = dao.getSpilledBodyRefs()
        for (row in rows) {
            var reqPath = row.requestBodyFilePath
            var resPath = row.responseBodyFilePath
            var changed = false
            if (reqPath != null) {
                if (file(reqPath) == null) { reqPath = null; changed = true } else referenced += reqPath
            }
            if (resPath != null) {
                if (file(resPath) == null) { resPath = null; changed = true } else referenced += resPath
            }
            if (changed) {
                dao.clearEvictedBodyRefs(
                    id = row.id,
                    requestBodyFilePath = reqPath,
                    responseBodyFilePath = resPath,
                    requestEvicted = row.requestBodyFilePath != null && reqPath == null,
                    responseEvicted = row.responseBodyFilePath != null && resPath == null,
                )
                referencesCleared++
            }
        }

        root.listFiles()?.forEach { sessionDir ->
            if (!sessionDir.isDirectory) {
                bytesFreed += sessionDir.length(); if (sessionDir.delete()) filesRemoved++
                return@forEach
            }
            sessionDir.listFiles()?.forEach { f ->
                val rel = "${sessionDir.name}/${f.name}"
                if (f.name.startsWith(".tmp-") || rel !in referenced) {
                    bytesFreed += f.length()
                    if (f.delete()) filesRemoved++
                }
            }
            if (sessionDir.listFiles()?.isEmpty() == true) sessionDir.delete()
        }
        if (filesRemoved > 0) health.recordOrphanSweep(filesRemoved)
        if (filesRemoved > 0 || referencesCleared > 0) {
            Log.i(TAG, "orphan sweep: removed $filesRemoved file(s), freed $bytesFreed B, cleared $referencesCleared reference(s)")
        }
        return SweepResult(filesRemoved, referencesCleared, bytesFreed)
    }

    // -- entity helpers used by the persistence actor ------------------------------------------

    /**
     * Applies the inline-or-spill decision to both directions of [entity] for the row [trafficId]
     * and returns the row to persist. Inline columns are nulled for a spilled body; a failed
     * spill leaves the inline columns null too (the bytes exceeded the inline limit, so they were
     * never going to be stored) and raises the truncated flag.
     */
    fun place(entity: CapturedTrafficEntity, trafficId: Long): CapturedTrafficEntity {
        var e = entity
        // -- request --
        val reqBytes = e.requestBodyBytes
        val reqText = e.requestBody
        if (reqBytes != null && shouldSpill(reqBytes.size.toLong())) {
            e = when (val r = spill(e.sessionId, trafficId, Direction.REQUEST, reqBytes, isText = false)) {
                is Result.Stored -> e.copy(
                    requestBodyBytes = null,
                    requestBodyFilePath = r.spilled.relativePath,
                    requestBodySha256 = r.spilled.sha256,
                    requestBodyStoredBytes = r.spilled.storedBytes,
                    requestBodyTotalBytes = r.spilled.totalBytes,
                    requestBodyPreview = null,
                )
                is Result.Failed -> e.copy(requestBodyBytes = null, requestBodyBytesTruncated = true, requestBodyTotalBytes = r.totalBytes)
            }
        } else if (reqText != null && reqText.length > inlineLimit / 4 && shouldSpill(utf8Length(reqText))) {
            val bytes = reqText.toByteArray(Charsets.UTF_8)
            e = when (val r = spill(e.sessionId, trafficId, Direction.REQUEST, bytes, isText = true)) {
                is Result.Stored -> e.copy(
                    requestBody = null,
                    requestBodyFilePath = r.spilled.relativePath,
                    requestBodySha256 = r.spilled.sha256,
                    requestBodyStoredBytes = r.spilled.storedBytes,
                    requestBodyTotalBytes = r.spilled.totalBytes,
                    requestBodyPreview = r.spilled.preview,
                )
                is Result.Failed -> e.copy(
                    requestBody = null,
                    requestBodyPreview = previewText(bytes),
                    requestBodyBytesTruncated = true,
                    requestBodyTotalBytes = r.totalBytes,
                )
            }
        }
        // -- response --
        val resBytes = e.responseBodyBytes
        val resText = e.responseBody
        if (resBytes != null && shouldSpill(resBytes.size.toLong())) {
            e = when (val r = spill(e.sessionId, trafficId, Direction.RESPONSE, resBytes, isText = false)) {
                is Result.Stored -> e.copy(
                    responseBodyBytes = null,
                    responseBodyFilePath = r.spilled.relativePath,
                    responseBodySha256 = r.spilled.sha256,
                    responseBodyStoredBytes = r.spilled.storedBytes,
                    responseBodyTotalBytes = r.spilled.totalBytes,
                    responseBodyPreview = null,
                )
                is Result.Failed -> e.copy(responseBodyBytes = null, responseBodyBytesTruncated = true, responseBodyTotalBytes = r.totalBytes)
            }
        } else if (resText != null && resText.length > inlineLimit / 4 && shouldSpill(utf8Length(resText))) {
            val bytes = resText.toByteArray(Charsets.UTF_8)
            e = when (val r = spill(e.sessionId, trafficId, Direction.RESPONSE, bytes, isText = true)) {
                is Result.Stored -> e.copy(
                    responseBody = null,
                    responseBodyFilePath = r.spilled.relativePath,
                    responseBodySha256 = r.spilled.sha256,
                    responseBodyStoredBytes = r.spilled.storedBytes,
                    responseBodyTotalBytes = r.spilled.totalBytes,
                    responseBodyPreview = r.spilled.preview,
                )
                is Result.Failed -> e.copy(
                    responseBody = null,
                    responseBodyPreview = previewText(bytes),
                    responseBodyBytesTruncated = true,
                    responseBodyTotalBytes = r.totalBytes,
                )
            }
        }
        return e
    }

    /** Whether [entity] carries at least one body that [place] would move out of the row. */
    fun needsSpill(entity: CapturedTrafficEntity): Boolean {
        entity.requestBodyBytes?.let { if (shouldSpill(it.size.toLong())) return true }
        entity.responseBodyBytes?.let { if (shouldSpill(it.size.toLong())) return true }
        entity.requestBody?.let { if (it.length > inlineLimit / 4 && shouldSpill(utf8Length(it))) return true }
        entity.responseBody?.let { if (it.length > inlineLimit / 4 && shouldSpill(utf8Length(it))) return true }
        return false
    }

    // -- internals -----------------------------------------------------------------------------

    private fun sessionDir(sessionId: Long) = File(root, sessionId.toString())

    private fun fileName(trafficId: Long, direction: Direction) = "$trafficId.${direction.suffix}.bin"

    private fun dirBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    companion object {
        private const val TAG = "BodyStore"

        /** Bodies above this leave the row (plan §4: "inline limit 1 MB"). */
        const val INLINE_LIMIT_BYTES: Int = 1024 * 1024

        /** UTF-8 preview kept on the row for a spilled text body. */
        const val PREVIEW_BYTES: Int = 64 * 1024

        /** Streaming buffer; the store itself never holds more than this per body in flight. */
        const val BUFFER_BYTES: Int = 64 * 1024

        /**
         * Hard per-direction ceiling for bodies engines may hand to the store as a single
         * [ByteArray] before spilling (plan task 16 "hard 2 MB per-direction memory ceiling" is
         * for the streaming parsers' buffers; this is the cap for the pre-S3 in-memory path).
         */
        const val MAX_SPILLABLE_BYTES: Int = 32 * 1024 * 1024

        const val DIR_NAME = "captures"

        @Volatile
        private var INSTANCE: BodyStore? = null

        fun get(context: Context): BodyStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BodyStore(File(context.applicationContext.filesDir, DIR_NAME)).also { INSTANCE = it }
            }

        internal fun forTest(root: File, inlineLimit: Int = INLINE_LIMIT_BYTES): BodyStore =
            BodyStore(root, inlineLimit = inlineLimit)

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** UTF-8 byte length without allocating the encoded array. */
        internal fun utf8Length(s: String): Long {
            var n = 0L
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        /** First [PREVIEW_BYTES] of [bytes] as text, cut at a char boundary so it never ends mid-sequence. */
        internal fun previewText(bytes: ByteArray): String {
            var end = minOf(bytes.size, PREVIEW_BYTES)
            var k = end - 1
            var cont = 0
            while (k >= 0 && cont < 3 && (bytes[k].toInt() and 0xC0) == 0x80) { k--; cont++ }
            if (k >= 0) {
                val lead = bytes[k].toInt() and 0xFF
                val need = when {
                    lead >= 0xF0 -> 4
                    lead >= 0xE0 -> 3
                    lead >= 0xC0 -> 2
                    else -> 1
                }
                if (need > 1 && cont + 1 < need) end = k
            }
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }
}
