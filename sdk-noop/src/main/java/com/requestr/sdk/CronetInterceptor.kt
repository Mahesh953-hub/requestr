package com.requestr.sdk

import android.content.Context
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer

/**
 * No-op counterpart to the real `:sdk` module's `CronetInterceptor.kt` -- see there for the full
 * contract this mirrors. Sets no experimental options on [ExperimentalCronetEngine.Builder]: no
 * keylog file, no capture of any kind.
 */
fun ExperimentalCronetEngine.Builder.addRequestrInterceptor(context: Context, observer: RequestrObserver): ExperimentalCronetEngine.Builder = this

/** No-op counterpart to the real `:sdk` module's `RequestrCronetCallback` -- every callback is
 * forwarded straight to [delegate], unobserved. */
class RequestrCronetCallback(
    private val delegate: UrlRequest.Callback,
    private val observer: RequestrObserver,
    private val url: String,
    private val method: String,
    private val requestHeaders: Map<String, String>,
) : UrlRequest.Callback() {
    override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        delegate.onRedirectReceived(request, info, newLocationUrl)
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        delegate.onResponseStarted(request, info)
    }

    override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        delegate.onReadCompleted(request, info, byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        delegate.onSucceeded(request, info)
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
        delegate.onFailed(request, info, error)
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        delegate.onCanceled(request, info)
    }
}

/** No-op counterpart to the real `:sdk` module's `CronetInterceptor` object. */
object CronetInterceptor {
    const val KEYLOG_FILE_NAME = "requestr_cronet_keylog.txt"

    /** Always `false` -- this module never actually checks (or cares) whether a Cronet
     * provider is installed, since it never uses one either way. */
    fun isCronetAvailable(context: Context): Boolean = false
}
