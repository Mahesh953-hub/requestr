package com.example.runtime.http

/**
 * In-memory, per-connection accumulator for the multi-signal stack-fingerprinting evidence
 * that only exists at capture time and cannot be reconstructed from Room afterwards (W4):
 *
 *  - JA3 hash / ALPN offer — require the live TLS ClientHello (`TlsClientHello.ja3Hash()`);
 *    only ever computed once, at the moment [com.example.interception.CaptureEngine] sees the
 *    handshake.
 *  - HTTP/2 SETTINGS `(id, value)` pairs and pseudo-header order — require the live H2 frames
 *    ([com.example.interception.http.Http2Parser.decodeSettings] /
 *    `H2Headers.pseudoHeaders`); nothing about frame order survives past the parse.
 *  - HTTP/1.x request header order + casing — [com.example.interception.http.HttpParser]
 *    lowercases every header name at parse time, and `CapturedTrafficEntity.requestHeaders`
 *    round-trips through a Moshi `Map<String,String>` TypeConverter that does not preserve
 *    insertion order once written to Room. So the *only* place original order/casing exists
 *    is right here, at the moment the request was parsed.
 *
 * Everything else [StackFingerprinter] uses (User-Agent, cache-validator headers) is a real
 * HTTP header value that survives Room verbatim, so those are read straight off the persisted
 * row instead of being duplicated in this store.
 *
 * Scoped to the current process lifetime only — exactly the same honestly-documented
 * limitation as [com.example.runtime.RuntimeInstrumentationEngine]'s `adapterStatuses`/
 * `eventCounts`: signals are only available for connections this process observed live since
 * it last started, never fabricated or backfilled for older sessions.
 */
object StackSignalStore {

    data class Signals(
        var ja3Hash: String? = null,
        var alpn: String? = null,
        var http2Settings: List<Pair<Int, Int>>? = null,
        var pseudoHeaderOrder: List<String>? = null,
        var requestHeaderOrder: List<String>? = null,
    )

    private const val MAX_ENTRIES = 2_000
    private val signals = LinkedHashMap<String, Signals>()

    private fun entryLocked(connectionId: String): Signals {
        signals[connectionId]?.let { return it }
        if (signals.size >= MAX_ENTRIES) {
            val oldest = signals.keys.firstOrNull()
            if (oldest != null) signals.remove(oldest)
        }
        val fresh = Signals()
        signals[connectionId] = fresh
        return fresh
    }

    /** Record a TLS ClientHello's JA3 hash and/or offered ALPN for [connectionId]. */
    @Synchronized
    fun registerTls(connectionId: String, ja3Hash: String?, alpn: String?) {
        val e = entryLocked(connectionId)
        if (ja3Hash != null) e.ja3Hash = ja3Hash
        if (alpn != null) e.alpn = alpn
    }

    /** Record the `(id, value)` pairs of a client-sent HTTP/2 SETTINGS frame for [connectionId]. */
    @Synchronized
    fun registerHttp2Settings(connectionId: String, settingsPairs: List<Pair<Int, Int>>) {
        if (settingsPairs.isEmpty()) return
        entryLocked(connectionId).http2Settings = settingsPairs
    }

    /** Record the pseudo-header emission order (e.g. `[:method, :scheme, :authority, :path]`). */
    @Synchronized
    fun registerPseudoHeaderOrder(connectionId: String, order: List<String>) {
        if (order.isEmpty()) return
        entryLocked(connectionId).pseudoHeaderOrder = order
    }

    /** Record HTTP/1.x request header names in original wire order and casing. */
    @Synchronized
    fun registerRequestHeaderOrder(connectionId: String, order: List<String>) {
        if (order.isEmpty()) return
        entryLocked(connectionId).requestHeaderOrder = order
    }

    /** Snapshot of whatever has been recorded for [connectionId], or null if nothing has. */
    @Synchronized
    fun get(connectionId: String): Signals? = signals[connectionId]?.copy()

    /** Test/diagnostic hook: drop all recorded signals. */
    @Synchronized
    fun clear() = signals.clear()
}
