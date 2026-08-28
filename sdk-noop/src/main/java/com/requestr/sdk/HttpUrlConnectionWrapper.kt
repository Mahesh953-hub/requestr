package com.requestr.sdk

/**
 * No-op counterpart to the real `:sdk` module's `HttpUrlConnectionWrapper` -- see there for the
 * full contract this mirrors. Never installs a [java.net.URLStreamHandlerFactory]: every
 * `HttpURLConnection` in the process stays completely un-instrumented, and [install] always
 * reports that it did not win any race (there was never a race to win).
 */
object HttpUrlConnectionWrapper {
    /** Always `false` -- see class kdoc. */
    fun install(observer: RequestrObserver): Boolean = false
}
