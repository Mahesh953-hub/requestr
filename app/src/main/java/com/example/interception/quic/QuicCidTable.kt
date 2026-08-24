package com.example.interception.quic

import java.util.concurrent.ConcurrentHashMap

/**
 * QUIC connection-ID routing table (RFC 9000 §5.8 / §17.2).
 *
 * A QUIC connection is identified not by a single 5-tuple but by a *set* of active connection IDs
 * (CIDs): the client's chosen DCID, the DCID the server echoes back as its SCID, additional CIDs
 * issued via NEW_CONNECTION_ID (0x18), and — for zero-length-DCID connections — the 5-tuple itself
 * as a fallback. This table maps any active CID (and, for zero-length DCIDs, the 5-tuple) to a
 * single opaque routing key that names the owning [QuicConnection], so the datagram router can
 * dispatch a packet by its DCID no matter which of the connection's several CIDs it carries — and
 * tolerate the Initial→Handshake DCID change and the Retry re-key.
 *
 * The active-CID set per connection is bounded by [maxCidsPerConnection] (RFC 9000
 * `active_connection_id_limit`, default 8): an add beyond the bound is rejected so a flood of
 * spoofed NEW_CONNECTION_ID frames can never grow the table unboundedly.
 *
 * Thread-safe: all three backing maps are [ConcurrentHashMap]s and each connection's CID set is
 * guarded for the bound check.
 *
 * @param maxCidsPerConnection maximum active CIDs retained per connection; extras are rejected.
 */
class QuicCidTable(
    private val maxCidsPerConnection: Int = DEFAULT_MAX_CIDS_PER_CONNECTION,
) {

    /** Maps an active CID (a DCID or server-issued CID) to its connection's routing key. */
    private val cidToConnection: MutableMap<CidKey, String> = ConcurrentHashMap()

    /** Maps a routing key to the bounded set of active CIDs advertising that connection. */
    private val connectionToCids: MutableMap<String, MutableSet<CidKey>> = ConcurrentHashMap()

    /** 5-tuple fallback for zero-length-DCID connections (empty DCID → route by tuple). */
    private val tupleToConnection: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * Registers [cid] as an active connection ID routing to [routingKey], updating both the
     * forward ([cidToConnection]) and reverse ([connectionToCids]) indexes.
     *
     * Returns `true` if the CID was accepted. Returns `false` if [cid] is empty (zero-length CIDs
     * are routed by 5-tuple via [registerZeroLengthConnection]/[lookupByTuple], never by CID) or
     * if [routingKey] already holds [maxCidsPerConnection] active CIDs and the add was rejected to
     * keep the set bounded. Re-adding an already-known CID for the same routing key is idempotent
     * and returns `true`.
     */
    fun addCid(routingKey: String, cid: ByteArray): Boolean {
        if (cid.isEmpty()) return false
        val key = CidKey(cid)
        val cids = connectionToCids.computeIfAbsent(routingKey) {
            ConcurrentHashMap.newKeySet<CidKey>()
        }
        synchronized(cids) {
            if (cids.size >= maxCidsPerConnection && key !in cids) return false
            cids.add(key)
        }
        cidToConnection[key] = routingKey
        return true
    }

    /**
     * Retires [cid] from the active set: removes it from [cidToConnection] and from the reverse
     * index of its connection. Returns the routing key the CID was mapped to (so the caller can
     * tell whether the connection just lost its last live CID), or `null` if [cid] was not
     * registered.
     */
    fun retireCid(cid: ByteArray): String? {
        val key = CidKey(cid)
        val routingKey = cidToConnection.remove(key) ?: return null
        connectionToCids[routingKey]?.remove(key)
        return routingKey
    }

    /**
     * Re-keys a connection after a Retry (RFC 9000 §17.2.8). On a Retry the client switches its
     * DCID to the Retry packet's SCID ([retryScid]); the connection previously routed by
     * [originalDcid] must now be routable by [retryScid], and the caller must re-derive the
     * Initial keys from [retryScid].
     *
     * This registers [retryScid] for the same routing key, retires [originalDcid] (the client no
     * longer sends it as a DCID), and returns the routing key so the caller can re-derive Initial
     * keys from [retryScid]. Returns `null` if [originalDcid] was not a known CID (no connection
     * to re-key — e.g. a Retry for a connection we never observed, or a spoofed Retry).
     */
    fun rekeyAfterRetry(originalDcid: ByteArray, retryScid: ByteArray): String? {
        val routingKey = cidToConnection[CidKey(originalDcid)] ?: return null
        addCid(routingKey, retryScid)
        retireCid(originalDcid)
        return routingKey
    }

    /**
     * Returns the routing key for the connection carrying [cid] as an active DCID, or `null` if
     * [cid] is unknown. Zero-length DCIDs (empty [cid]) are never registered here — a connection
     * whose DCID is empty is routed by 5-tuple via [lookupByTuple] instead.
     */
    fun lookup(cid: ByteArray): String? = cidToConnection[CidKey(cid)]

    /**
     * Registers a zero-length-DCID connection: when a connection's DCID is empty (RFC 9000 §17.2),
     * its packets are routed by the 5-tuple ([tupleKey]) instead of by CID.
     */
    fun registerZeroLengthConnection(tupleKey: String, routingKey: String) {
        tupleToConnection[tupleKey] = routingKey
    }

    /**
     * Returns the routing key for a zero-length-DCID connection keyed by its 5-tuple ([tupleKey]),
     * or `null` if no such connection is registered. The fallback path used when a packet's DCID
     * is empty.
     */
    fun lookupByTuple(tupleKey: String): String? = tupleToConnection[tupleKey]

    /**
     * Releases every routing alias owned by [routingKey]. Called when the owning connection decoder
     * is evicted, so retired connections cannot retain CID or zero-length-DCID tuple entries forever
     * or route a later, unrelated connection into stale state.
     */
    fun removeConnection(routingKey: String) {
        val cids = connectionToCids.remove(routingKey).orEmpty()
        for (cid in cids) cidToConnection.remove(cid, routingKey)
        tupleToConnection.entries.removeIf { it.value == routingKey }
    }

    /**
     * Content-equal wrapper for [ByteArray] CIDs, so the [ConcurrentHashMap] keys compare by CID
     * bytes rather than by array identity (a raw [ByteArray] uses reference equality, which would
     * make a freshly-extracted DCID from a second packet miss an entry added from the first).
     */
    private class CidKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is CidKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    companion object {
        /** RFC 9000 default `active_connection_id_limit` upper bound (§18.2). */
        const val DEFAULT_MAX_CIDS_PER_CONNECTION = 8
    }
}
