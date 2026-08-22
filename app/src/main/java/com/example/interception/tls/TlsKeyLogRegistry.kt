package com.example.interception.tls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canonical in-memory store of TLS/QUIC key material, keyed by (label, clientRandom).
 *
 * requestr cannot passively compute QUIC 1-RTT / TLS 1.3 traffic secrets from the wire — that
 * needs "the private key material of one endpoint" ([QuicCrypto]'s own doc). This registry is
 * how the planes that DO legitimately hold that material make it available to the decoders:
 *
 *  - the MITM leg ([MitmProxyEngine]), which terminates TLS itself, registers secrets for the
 *    connections it decrypts;
 *  - the SDK plane (owned by the SDK scope) exports key material from inside the app's own
 *    process, via this same [record] entry point;
 *  - a user-supplied `SSLKEYLOGFILE` (NSS keylog format) can be imported via [importNssKeyLog].
 *
 * **FROZEN CONTRACT.** The public surface below (the [KeyLogLabel] enum, [record], [lookup],
 * [importNssKeyLog], [clear], [entryCount]) is frozen so the SDK scope can bind to it verbatim.
 * Do not change a signature here without notifying that scope.
 *
 * Contract semantics:
 *  - [record] is idempotent for an identical (label, clientRandom) pair, and overwrites on a
 *    differing secret (last writer wins). Safe to call from any thread.
 *  - [clientRandom] is the raw 32 bytes from the ClientHello, not hex; [secret] is raw bytes.
 *    Callers pass copies -- this registry never retains a caller-mutable reference.
 *  - [record] silently ignores a [clientRandom] of the wrong length rather than throwing, so a
 *    producer bug can never crash a capture.
 *  - [lookup] returns null on a miss. A miss is a normal, expected runtime state (surfaced
 *    honestly in the UI as "no key material"), never an error.
 *  - [importNssKeyLog] accepts the standard `LABEL <64-hex client_random> <hex secret>` line
 *    format, returns the count of lines accepted, and ignores malformed lines without throwing.
 *
 * Secrets never touch disk -- in-memory only, bounded, TTL-pruned, and cleared on capture stop
 * (wired into [com.example.interception.RequestrVpnService.cleanup]).
 */
enum class KeyLogLabel {
    CLIENT_HANDSHAKE_TRAFFIC_SECRET,
    SERVER_HANDSHAKE_TRAFFIC_SECRET,
    CLIENT_TRAFFIC_SECRET_0,
    SERVER_TRAFFIC_SECRET_0,
    CLIENT_EARLY_TRAFFIC_SECRET,
}

object TlsKeyLogRegistry {

    private const val CLIENT_RANDOM_LEN = 32
    private const val MAX_ENTRIES = 512
    private const val TTL_MS = 30 * 60 * 1000L // 30 minutes -- generous vs. one capture session

    private data class Key(val label: KeyLogLabel, val clientRandomHex: String)
    private data class Entry(val secret: ByteArray, val recordedAt: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    fun record(label: KeyLogLabel, clientRandom: ByteArray, secret: ByteArray) {
        if (clientRandom.size != CLIENT_RANDOM_LEN) return
        val key = Key(label, clientRandom.toHexLower())
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneLocked(now)
            entries[key] = Entry(secret.copyOf(), now)
            if (entries.size > MAX_ENTRIES) {
                val oldestKey = entries.entries.minByOrNull { it.value.recordedAt }?.key
                oldestKey?.let { entries.remove(it) }
            }
            _entryCount.value = entries.size
        }
    }

    fun lookup(label: KeyLogLabel, clientRandom: ByteArray): ByteArray? {
        if (clientRandom.size != CLIENT_RANDOM_LEN) return null
        val key = Key(label, clientRandom.toHexLower())
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneLocked(now)
            val entry = entries[key] ?: return null
            return entry.secret.copyOf()
        }
    }

    /**
     * Imports NSS keylog-format lines: `LABEL <64-hex client_random> <hex secret>`.
     * Malformed or unrecognised lines are skipped, never thrown. Hex is case-insensitive.
     * Returns the number of lines accepted.
     */
    fun importNssKeyLog(lines: Sequence<String>): Int {
        var accepted = 0
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 3) continue
            val label = nssLabelToEnum(parts[0]) ?: continue
            val clientRandomHex = parts[1]
            val secretHex = parts[2]
            if (clientRandomHex.length != CLIENT_RANDOM_LEN * 2) continue
            val clientRandom = clientRandomHex.hexToBytesOrNull() ?: continue
            val secret = secretHex.hexToBytesOrNull() ?: continue
            record(label, clientRandom, secret)
            accepted++
        }
        return accepted
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _entryCount.value = 0
        }
    }

    private fun pruneLocked(now: Long) {
        val cutoff = now - TTL_MS
        entries.entries.removeAll { it.value.recordedAt < cutoff }
        _entryCount.value = entries.size
    }

    private fun nssLabelToEnum(nssLabel: String): KeyLogLabel? = when (nssLabel.uppercase()) {
        "CLIENT_HANDSHAKE_TRAFFIC_SECRET" -> KeyLogLabel.CLIENT_HANDSHAKE_TRAFFIC_SECRET
        "SERVER_HANDSHAKE_TRAFFIC_SECRET" -> KeyLogLabel.SERVER_HANDSHAKE_TRAFFIC_SECRET
        "CLIENT_TRAFFIC_SECRET_0" -> KeyLogLabel.CLIENT_TRAFFIC_SECRET_0
        "SERVER_TRAFFIC_SECRET_0" -> KeyLogLabel.SERVER_TRAFFIC_SECRET_0
        "CLIENT_EARLY_TRAFFIC_SECRET" -> KeyLogLabel.CLIENT_EARLY_TRAFFIC_SECRET
        else -> null
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i ->
                val hi = Character.digit(this[i * 2], 16)
                val lo = Character.digit(this[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                ((hi shl 4) or lo).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
