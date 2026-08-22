package com.requestr.sdk

import android.content.Context
import android.util.Log
import com.google.android.gms.net.CronetProviderInstaller
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UrlRequest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Enables TLS keylogging + request/response observation for apps using Google's Cronet HTTP
 * stack (`play-services-cronet`), typically via `com.google.android.gms.net.CronetProviderInstaller`
 * or the bundled Cronet impl.
 *
 * `compileOnly("com.google.android.gms:play-services-cronet:...")` in `sdk/build.gradle.kts`
 * means this whole file only actually loads its classes if the host app itself depends on
 * Cronet -- an app that never touches Cronet never pays for (or crashes on) a missing class.
 */
fun ExperimentalCronetEngine.Builder.addRequestrInterceptor(context: Context, observer: RequestrObserver): ExperimentalCronetEngine.Builder {
    // `ssl_key_log_file` is a documented experimental Cronet option (confirmed via Chromium
    // source: components/cronet/android/api/src/org/chromium/net/CronetEngine.java). Every TLS
    // session Cronet negotiates (including QUIC/HTTP3, which never touches OkHttp/Conscrypt at
    // all) appends NSS-format keylog lines to this file as it goes.
    val keylogFile = File(context.filesDir, CronetInterceptor.KEYLOG_FILE_NAME)
    val opts = JSONObject().put("ssl_key_log_file", keylogFile.absolutePath)
    setExperimentalOptions(opts.toString())
    return this
}

/** Wraps a Cronet [UrlRequest.Callback] to capture response body chunks, status, and timing --
 * the request-body side must come from the caller (Cronet's request bodies are streamed via a
 * separate `UploadDataProvider`, not visible from the callback alone). */
class RequestrCronetCallback(
    private val delegate: UrlRequest.Callback,
    private val observer: RequestrObserver,
    private val url: String,
    private val method: String,
    private val requestHeaders: Map<String, String>,
) : UrlRequest.Callback() {
    private val startedAt = System.currentTimeMillis()
    private val responseCapture = ByteArrayOutputStream()
    private var statusCode: Int? = null

    override fun onRedirectReceived(request: UrlRequest, info: org.chromium.net.UrlResponseInfo, newLocationUrl: String) {
        delegate.onRedirectReceived(request, info, newLocationUrl)
    }

    override fun onResponseStarted(request: UrlRequest, info: org.chromium.net.UrlResponseInfo) {
        statusCode = info.httpStatusCode
        delegate.onResponseStarted(request, info)
    }

    override fun onReadCompleted(request: UrlRequest, info: org.chromium.net.UrlResponseInfo, byteBuffer: ByteBuffer) {
        runCatching {
            val duplicate = byteBuffer.duplicate()
            duplicate.flip()
            if (responseCapture.size() < MAX_CAPTURE_BYTES) {
                val remaining = minOf(duplicate.remaining(), MAX_CAPTURE_BYTES - responseCapture.size())
                val bytes = ByteArray(remaining)
                duplicate.get(bytes)
                responseCapture.write(bytes)
            }
        }
        delegate.onReadCompleted(request, info, byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: org.chromium.net.UrlResponseInfo) {
        report(info)
        delegate.onSucceeded(request, info)
    }

    override fun onFailed(request: UrlRequest, info: org.chromium.net.UrlResponseInfo?, error: org.chromium.net.CronetException) {
        report(info)
        delegate.onFailed(request, info, error)
    }

    override fun onCanceled(request: UrlRequest, info: org.chromium.net.UrlResponseInfo?) {
        report(info)
        delegate.onCanceled(request, info)
    }

    private fun report(info: org.chromium.net.UrlResponseInfo?) {
        observer.observeDetailed(
            url = url,
            method = method,
            headers = requestHeaders,
            statusCode = statusCode ?: info?.httpStatusCode,
            requestBody = null, // request bodies stream via a separate UploadDataProvider on Cronet
            responseBody = responseCapture.toByteArray().takeIf { it.isNotEmpty() },
            durationMs = System.currentTimeMillis() - startedAt,
            httpStack = "Cronet",
            isDecrypted = true,
        )
    }

    companion object {
        private const val MAX_CAPTURE_BYTES = 1 * 1024 * 1024
    }
}

object CronetInterceptor {
    const val KEYLOG_FILE_NAME = "requestr_cronet_keylog.txt"
    private const val TAG = "RequestrCronet"

    /** Verifies a Cronet provider is actually available in this process before the host app
     * tries to build a [CronetEngine] against it -- surfaces a clear log line instead of a
     * confusing crash deep inside Play Services if Cronet isn't installed on this device. */
    fun isCronetAvailable(context: Context): Boolean {
        return try {
            CronetProviderInstaller.installProvider(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cronet provider unavailable: ${e.message}")
            false
        }
    }
}
