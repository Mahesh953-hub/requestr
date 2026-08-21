package com.example.interception.tls

import android.util.Log

/**
 * Detects TLS records in a byte-stream and extracts metadata.
 *
 * TLS records have a 5-byte header:
 *   byte 0:    content-type (0x14=ChangeCipherSpec, 0x15=Alert, 0x16=Handshake, 0x17=AppData)
 *   bytes 1-2: version (0x0301=TLS1.0, 0x0302=TLS1.1, 0x0303=TLS1.2, 0x0304=TLS1.3)
 *   bytes 3-4: length (u16 big-endian)
 *
 * ClientHello (handshake type 0x01) contains extensions from which we extract
 * the server_name (SNI, type 0x0000).
 *
 * What this does NOT do: decrypt application data. That requires a full MITM
 * proxy with knowledge of the session keys, which is built separately.
 */
object TlsDecoder {

    // ── detection ───────────────────────────────────────────────────────

    /**
     * Returns true if [bytes] starts with a TLS record header and the
     * content-type is a recognised value.
     */
    fun looksLikeTls(bytes: ByteArray, offset: Int = 0): Boolean {
        if (bytes.size - offset < 5) return false
        val contentType = bytes[offset].toInt() and 0xFF
        if (contentType !in 20..23) return false
        val version = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
        return version in 0x0301..0x0304
    }

    // ── ClientHello SNI extraction ──────────────────────────────────────

    /**
     * Try to extract the SNI hostname from a TLS ClientHello in [bytes].
     * Returns null if no ClientHello is detected or SNI extension is absent.
     *
     * ClientHello structure (after record header):
     *   byte 0:     handshake-type (0x01=ClientHello)
     *   bytes 1-3:  length (24-bit big-endian)
     *   bytes 4-5:  client version
     *   bytes 6-37: random (32 bytes)
     *   byte 38:    session-id length
     *   ...session-id
     *   ...cipher-suites (2-byte length + list)
     *   ...compression (1-byte length + list)
     *   ...extensions (2-byte length + list)
     *
     * Each extension: 2-byte type + 2-byte length + data
     *   SNI = type 0x0000
     *   SNI data: 2-byte list length, then each entry: 1-byte name-type (0=hostname)
     *             + 2-byte length + value
     */
    fun extractSni(bytes: ByteArray, offset: Int = 0): String? {
        val start = offset
        if (bytes.size - start < 5) return null

        // Skip TLS record header
        val contentType = bytes[start].toInt() and 0xFF
        if (contentType != 22) return null // not handshake
        val recordVersion = readU16(bytes, start + 1)
        if (recordVersion !in 0x0301..0x0304) return null
        val recordLen = readU16(bytes, start + 3)
        val handshakeStart = start + 5
        if (bytes.size < handshakeStart + 4) return null

        val handshakeType = bytes[handshakeStart].toInt() and 0xFF
        if (handshakeType != 1) return null // not ClientHello

        val handshakeLen = readU24(bytes, handshakeStart + 1)
        val clientHelloEnd = handshakeStart + 4 + handshakeLen
        if (bytes.size < clientHelloEnd) return null

        var pos = handshakeStart + 4
        pos += 2 // client version
        pos += 32 // random
        if (pos >= bytes.size) return null
        val sessionIdLen = bytes[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > bytes.size) return null
        val cipherSuitesLen = readU16(bytes, pos)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > bytes.size) return null
        val compressionLen = bytes[pos].toInt() and 0xFF
        pos += 1 + compressionLen

        // Extensions
        if (pos + 2 > bytes.size) return null
        val extensionsLen = readU16(bytes, pos)
        pos += 2
        val extensionsEnd = pos + extensionsLen

        while (pos + 4 <= extensionsEnd && pos + 4 <= bytes.size) {
            val extType = readU16(bytes, pos)
            val extLen = readU16(bytes, pos + 2)
            pos += 4

            if (extType == 0x0000 && pos + extLen <= bytes.size && extLen >= 5) {
                // SNI extension
                val sniListLen = readU16(bytes, pos)
                pos += 2
                val sniEnd = pos + sniListLen

                while (pos + 3 <= sniEnd && pos + 3 <= bytes.size) {
                    val nameType = bytes[pos].toInt() and 0xFF
                    val nameLen = readU16(bytes, pos + 1)
                    pos += 3
                    if (nameType == 0 && nameLen > 0 && pos + nameLen <= bytes.size) {
                        val hostname = String(bytes, pos, nameLen, Charsets.UTF_8)
                        return hostname.lowercase().take(256)
                    }
                    pos += nameLen
                }
                break
            }
            pos += extLen
        }

        return null
    }

    // ── ClientHello full parse (M2) ──────────────────────────────────────

    /**
     * Human-readable TLS record version, e.g. "TLS 1.2". Used by the "undecoded" capture
     * path (no MITM) where the record header is the only version signal available — the
     * real negotiated version requires seeing ServerHello, which we never decrypt here.
     */
    fun versionName(bytes: ByteArray, offset: Int = 0): String? {
        if (bytes.size - offset < 3) return null
        return when (readU16(bytes, offset + 1)) {
            0x0301 -> "TLS 1.0"; 0x0302 -> "TLS 1.1"; 0x0303 -> "TLS 1.2"; 0x0304 -> "TLS 1.3"
            else -> null
        }
    }

    /**
     * Parse the full ClientHello (version, offered cipher suites, extensions, supported
     * groups, signature algorithms, ALPN, SNI) so callers can compute a JA3 fingerprint via
     * [TlsClientHello.ja3Hash] and persist a richer TLS session row (M2) than SNI alone.
     *
     * Returns null on any structural mismatch (not a ClientHello, truncated buffer, etc.) —
     * mirrors [extractSni]'s defensive parsing rather than throwing.
     */
    fun parseClientHello(bytes: ByteArray, offset: Int = 0): TlsClientHello? {
        val start = offset
        if (bytes.size - start < 5) return null
        if ((bytes[start].toInt() and 0xFF) != 22) return null // not handshake
        val recordVersion = readU16(bytes, start + 1)
        if (recordVersion !in 0x0301..0x0304) return null

        val handshakeStart = start + 5
        if (bytes.size < handshakeStart + 4) return null
        if ((bytes[handshakeStart].toInt() and 0xFF) != 1) return null // not ClientHello

        val handshakeLen = readU24(bytes, handshakeStart + 1)
        val clientHelloEnd = handshakeStart + 4 + handshakeLen
        if (bytes.size < clientHelloEnd) return null

        var pos = handshakeStart + 4
        val clientVersion = readU16(bytes, pos)
        pos += 2 + 32 // client version + random
        if (pos >= bytes.size) return null
        val sessionIdLen = bytes[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > bytes.size) return null

        val cipherSuitesLen = readU16(bytes, pos)
        pos += 2
        val cipherSuites = mutableListOf<Int>()
        var cpos = pos
        while (cpos + 2 <= pos + cipherSuitesLen && cpos + 2 <= bytes.size) {
            cipherSuites.add(readU16(bytes, cpos))
            cpos += 2
        }
        pos += cipherSuitesLen
        if (pos + 1 > bytes.size) return null

        val compressionLen = bytes[pos].toInt() and 0xFF
        val compressionMethods = mutableListOf<Int>()
        for (i in 0 until compressionLen) {
            if (pos + 1 + i < bytes.size) compressionMethods.add(bytes[pos + 1 + i].toInt() and 0xFF)
        }
        pos += 1 + compressionLen

        val extensions = mutableListOf<Int>()
        val supportedGroups = mutableListOf<Int>()
        val signatureAlgorithms = mutableListOf<Int>()
        var alpn: String? = null
        var sni: String? = null

        if (pos + 2 <= bytes.size) {
            val extensionsLen = readU16(bytes, pos)
            pos += 2
            val extensionsEnd = pos + extensionsLen

            while (pos + 4 <= extensionsEnd && pos + 4 <= bytes.size) {
                val extType = readU16(bytes, pos)
                val extLen = readU16(bytes, pos + 2)
                val extDataStart = pos + 4
                extensions.add(extType)

                if (extDataStart + extLen <= bytes.size) {
                    when (extType) {
                        0x0000 -> { // server_name (SNI)
                            var p = extDataStart
                            if (p + 2 <= bytes.size) {
                                val listEnd = p + 2 + readU16(bytes, p)
                                p += 2
                                while (p + 3 <= listEnd && p + 3 <= bytes.size) {
                                    val nameType = bytes[p].toInt() and 0xFF
                                    val nameLen = readU16(bytes, p + 1)
                                    p += 3
                                    if (nameType == 0 && nameLen > 0 && p + nameLen <= bytes.size) {
                                        sni = String(bytes, p, nameLen, Charsets.UTF_8).lowercase().take(256)
                                    }
                                    p += nameLen
                                }
                            }
                        }
                        0x000a -> { // supported_groups
                            var p = extDataStart
                            if (p + 2 <= bytes.size) {
                                val listLen = readU16(bytes, p)
                                p += 2
                                var g = p
                                while (g + 2 <= p + listLen && g + 2 <= bytes.size) {
                                    supportedGroups.add(readU16(bytes, g))
                                    g += 2
                                }
                            }
                        }
                        0x000d -> { // signature_algorithms
                            var p = extDataStart
                            if (p + 2 <= bytes.size) {
                                val listLen = readU16(bytes, p)
                                p += 2
                                var s = p
                                while (s + 2 <= p + listLen && s + 2 <= bytes.size) {
                                    signatureAlgorithms.add(readU16(bytes, s))
                                    s += 2
                                }
                            }
                        }
                        0x0010 -> { // application_layer_protocol_negotiation (ALPN)
                            var p = extDataStart
                            if (p + 2 <= bytes.size) {
                                p += 2 // protocol name list length
                                if (p + 1 <= bytes.size) {
                                    val nameLen = bytes[p].toInt() and 0xFF
                                    p += 1
                                    if (nameLen > 0 && p + nameLen <= bytes.size) {
                                        alpn = String(bytes, p, nameLen, Charsets.UTF_8)
                                    }
                                }
                            }
                        }
                    }
                }
                pos = extDataStart + extLen
            }
        }

        return TlsClientHello(
            version = when (clientVersion) {
                0x0301 -> "TLS 1.0"; 0x0302 -> "TLS 1.1"; 0x0303 -> "TLS 1.2"; 0x0304 -> "TLS 1.3"
                else -> "0x%04x".format(clientVersion)
            },
            cipherSuites = cipherSuites,
            compressionMethods = compressionMethods,
            extensions = extensions,
            supportedGroups = supportedGroups,
            signatureAlgorithms = signatureAlgorithms,
            alpn = alpn,
            sni = sni,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readU16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun readU24(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 16) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        (buf[off + 2].toInt() and 0xFF)

    private const val TAG = "TlsDecoder"
}
