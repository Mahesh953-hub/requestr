package com.example.correlation

/**
 * W5: canonical connection-identity builder.
 *
 * Every capture-path producer built its own ad-hoc connection key string before this existed:
 *  - [com.example.interception.tcp.TcpStream.key] / `.keyFrom(...)`: `"$protocol|$srcIp:$srcPort>$dstIp:$dstPort"`
 *    (`protocol` is the raw IP protocol number, `6` for TCP).
 *  - [com.example.interception.PacketPump]'s QUIC connection id: `"quic|$src:$sPort>$dst:$dPort"`.
 *  - [com.example.interception.MitmProxyEngine]'s `tlsSessionId`: `"$host:$port"` (a *different*
 *    convention -- host:port, not a 5-tuple -- since the MITM path terminates TLS itself and
 *    never sees the real upstream 5-tuple the same way the VPN/tun path does).
 *
 * [ConnectionKey] does not change any of those literal formats (every existing persisted row
 * and every DAO query keyed on them must keep resolving) -- it centralizes them behind one
 * object so new producers stop hand-rolling the separator/ordering, and so
 * [ConnectionAliasRegistry] has one place to register a MITM-side host:port key as an alias of
 * the VPN/tun-side 5-tuple key for the same underlying connection.
 */
object ConnectionKey {
    private const val QUIC_PREFIX = "quic"
    private const val TCP_PROTOCOL_NUMBER = 6

    /** Matches [com.example.interception.tcp.TcpStream.key] / `.keyFrom(...)` exactly. */
    fun forTcp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, protocol: Int = TCP_PROTOCOL_NUMBER): String =
        "$protocol|$srcIp:$srcPort>$dstIp:$dstPort"

    /** Matches [com.example.interception.PacketPump]'s QUIC connection id exactly. */
    fun forQuic(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): String =
        "$QUIC_PREFIX|$srcIp:$srcPort>$dstIp:$dstPort"

    /**
     * Matches [com.example.interception.MitmProxyEngine]'s `tlsSessionId` convention
     * (`"$host:$port"`) for producers that only know a destination host/port, not a real
     * client-side 5-tuple (the MITM proxy terminates TLS itself).
     */
    fun forHostPort(host: String, port: Int): String = "$host:$port"

    /** Structured view of a key produced by [forTcp]/[forQuic], or null if it doesn't parse. */
    data class Parsed(val protocol: String, val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)

    /**
     * Parses a [forTcp]/[forQuic]-shaped key back into its components. Returns null for
     * [forHostPort]-shaped keys (no protocol/src side to recover) or anything malformed --
     * never throws.
     */
    fun parse(key: String): Parsed? {
        val pipeIdx = key.indexOf('|')
        if (pipeIdx < 0) return null
        val protocol = key.substring(0, pipeIdx)
        val rest = key.substring(pipeIdx + 1)
        val arrowIdx = rest.indexOf('>')
        if (arrowIdx < 0) return null
        val srcPart = rest.substring(0, arrowIdx)
        val dstPart = rest.substring(arrowIdx + 1)
        val srcColon = srcPart.lastIndexOf(':')
        val dstColon = dstPart.lastIndexOf(':')
        if (srcColon < 0 || dstColon < 0) return null
        val srcPort = srcPart.substring(srcColon + 1).toIntOrNull() ?: return null
        val dstPort = dstPart.substring(dstColon + 1).toIntOrNull() ?: return null
        return Parsed(
            protocol = protocol,
            srcIp = srcPart.substring(0, srcColon),
            srcPort = srcPort,
            dstIp = dstPart.substring(0, dstColon),
            dstPort = dstPort,
        )
    }
}

/**
 * W5: resolves a MITM-side alias key (e.g. [ConnectionKey.forHostPort]'s `"host:port"`) to the
 * canonical VPN/tun-side 5-tuple key for the same underlying connection, when both are known for
 * the same logical connection. Lineage joins (see
 * [com.example.correlation.graph.ConnectionLineage]) always resolve through this registry first
 * so a MITM row and a VPN-observed row for the same real connection can be matched on one
 * canonical id instead of silently falling back to a time-window heuristic (DEC-4).
 *
 * In-memory only, bounded, process-lifetime -- this is a hint index, not a source of truth: a
 * miss just means lineage can't join across that boundary yet, never a wrong join.
 */
object ConnectionAliasRegistry {
    private const val MAX_ENTRIES = 2_000
    private val aliasToCanonical = LinkedHashMap<String, String>()

    @Synchronized
    fun register(alias: String, canonical: String) {
        if (alias == canonical) return
        aliasToCanonical[alias] = canonical
        while (aliasToCanonical.size > MAX_ENTRIES) {
            val oldest = aliasToCanonical.keys.firstOrNull() ?: break
            aliasToCanonical.remove(oldest)
        }
    }

    /** Returns the canonical key for [alias], or null if no alias was ever registered for it. */
    @Synchronized
    fun resolve(alias: String): String? = aliasToCanonical[alias]

    @Synchronized
    fun clear() = aliasToCanonical.clear()
}
