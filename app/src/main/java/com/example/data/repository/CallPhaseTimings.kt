package com.example.data.repository

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * S2 task 18 -- measured phase timings for one API-Testr call, recorded by OkHttp's own
 * [EventListener] hooks instead of the fixed ratios of the total that used to be shown.
 *
 * All values are milliseconds and nullable: null means the phase did not occur on this call
 * (no TLS on plain http, no DNS when the connection was reused) or has not finished yet.
 * Nothing here is derived from the total.
 *
 * Attach one instance per call via `client.newBuilder().eventListener(listener).build()` and
 * read [snapshot] only after the response body has been consumed -- `responseBodyEnd` is what
 * closes the receive phase.
 */
class CallPhaseTimings : EventListener() {
    data class Phases(
        val dnsMs: Long?,
        val connectMs: Long?,
        val tlsMs: Long?,
        /** Request headers start -> request fully written (body end, or headers end without a body). */
        val sendMs: Long?,
        /** Request fully written -> first response header byte (TTFB). */
        val waitMs: Long?,
        /** Response headers start -> response body fully read. */
        val receiveMs: Long?,
        /** callStart -> callEnd/callFailed. */
        val totalMs: Long?,
        /** True when OkHttp reused a pooled connection, so DNS/connect/TLS legitimately did not happen. */
        val connectionReused: Boolean,
    ) {
        /** Every phase that was measured, in order. */
        val measured: List<Long> get() = listOfNotNull(dnsMs, connectMs, tlsMs, sendMs, waitMs, receiveMs)
    }

    @Volatile private var callStartNs: Long? = null
    @Volatile private var callEndNs: Long? = null
    @Volatile private var dnsStartNs: Long? = null
    @Volatile private var dnsEndNs: Long? = null
    @Volatile private var connectStartNs: Long? = null
    @Volatile private var secureStartNs: Long? = null
    @Volatile private var secureEndNs: Long? = null
    @Volatile private var connectEndNs: Long? = null
    @Volatile private var connectionAcquiredNs: Long? = null
    @Volatile private var requestHeadersStartNs: Long? = null
    @Volatile private var requestHeadersEndNs: Long? = null
    @Volatile private var requestBodyEndNs: Long? = null
    @Volatile private var responseHeadersStartNs: Long? = null
    @Volatile private var responseBodyEndNs: Long? = null

    private fun now() = System.nanoTime()

    override fun callStart(call: Call) { callStartNs = now() }
    override fun dnsStart(call: Call, domainName: String) { if (dnsStartNs == null) dnsStartNs = now() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dnsEndNs = now() }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        if (connectStartNs == null) connectStartNs = now()
    }
    override fun secureConnectStart(call: Call) { if (secureStartNs == null) secureStartNs = now() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { secureEndNs = now() }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connectEndNs = now()
    }
    override fun connectFailed(
        call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException,
    ) { connectEndNs = now() }
    override fun connectionAcquired(call: Call, connection: Connection) { connectionAcquiredNs = now() }
    override fun requestHeadersStart(call: Call) { if (requestHeadersStartNs == null) requestHeadersStartNs = now() }
    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { requestHeadersEndNs = now() }
    override fun requestBodyEnd(call: Call, byteCount: Long) { requestBodyEndNs = now() }
    override fun responseHeadersStart(call: Call) { if (responseHeadersStartNs == null) responseHeadersStartNs = now() }
    override fun responseBodyEnd(call: Call, byteCount: Long) { responseBodyEndNs = now() }
    override fun callEnd(call: Call) { callEndNs = now() }
    override fun callFailed(call: Call, ioe: IOException) { callEndNs = now() }

    fun snapshot(): Phases {
        val requestDoneNs = requestBodyEndNs ?: requestHeadersEndNs
        val tcpEndNs = secureStartNs ?: connectEndNs
        return Phases(
            dnsMs = ms(dnsStartNs, dnsEndNs),
            connectMs = ms(connectStartNs, tcpEndNs),
            tlsMs = ms(secureStartNs, secureEndNs),
            sendMs = ms(requestHeadersStartNs, requestDoneNs),
            waitMs = ms(requestDoneNs, responseHeadersStartNs),
            receiveMs = ms(responseHeadersStartNs, responseBodyEndNs),
            totalMs = ms(callStartNs, callEndNs ?: responseBodyEndNs),
            connectionReused = connectionAcquiredNs != null && connectStartNs == null,
        )
    }

    private fun ms(from: Long?, to: Long?): Long? {
        if (from == null || to == null) return null
        val d = (to - from) / 1_000_000
        return if (d < 0) null else d
    }
}
