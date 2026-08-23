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

    // ── ServerHello parse (negotiated cipher suite for QUIC 1-RTT) ───────────────────

    /**
     * Parses a TLS 1.3 ServerHello (handshake type 0x02, RFC 8446 §4.1.3) from [cryptoData]
     * starting at [offset], spanning [length] bytes -- the plaintext recovered from the server's
     * first Handshake CRYPTO frame via
     * [com.example.interception.quic.QuicCrypto.decryptHandshakePacket]. Reads the handshake-type
     * byte (must be 0x02), the 2-byte record version (must be 0x0304 = TLS 1.3), skips the 32-byte
     * random, reads the `legacy_session_id` echo length, and extracts the single 2-byte selected
     * cipher suite ID at the computed offset (version + random + session_id length + session_id).
     * Returns a [TlsServerHello] with the suite name resolved by [mapCipherSuiteId], or null on any
     * structural mismatch (wrong type, truncated buffer, non-TLS-1.3 version) -- mirroring
     * [parseClientHello]'s defensive, never-throw contract.
     */
    fun parseServerHello(cryptoData: ByteArray, offset: Int, length: Int): TlsServerHello? {
        val end = offset + length
        // type(1) + len(3) + version(2) + random(32) + session_id_len(1) + cipher_suite(2)
        if (length < 1 + 3 + 2 + 32 + 1 + 2) return null
        if (end > cryptoData.size) return null

        val handshakeType = cryptoData[offset].toInt() and 0xFF
        if (handshakeType != 0x02) return null // not a ServerHello

        // Skip the 3-byte handshake length (offset+1..3); the caller-supplied [length] bounds the body.
        val versionOffset = offset + 4
        val version = readU16(cryptoData, versionOffset)
        if (version != 0x0304) return null // must be TLS 1.3

        // random(32) at versionOffset+2; legacy_session_id echo length at versionOffset+2+32.
        val sessionIdLenOffset = versionOffset + 2 + 32
        if (sessionIdLenOffset >= end) return null
        val sessionIdLen = cryptoData[sessionIdLenOffset].toInt() and 0xFF
        val cipherSuiteOffset = sessionIdLenOffset + 1 + sessionIdLen
        if (cipherSuiteOffset + 2 > end) return null

        val cipherSuiteId = readU16(cryptoData, cipherSuiteOffset)
        return TlsServerHello(
            version = version,
            cipherSuiteId = cipherSuiteId,
            cipherSuiteName = mapCipherSuiteId(cipherSuiteId),
        )
    }

    /**
     * Maps a TLS 1.3 cipher suite identifier (RFC 8446 Appendix B.4) to its standard name. These are
     * the three AEAD suites QUIC can negotiate (RFC 9001 §5.3); the name selects the AEAD + HKDF
     * hash for [com.example.interception.quic.QuicCrypto.deriveKeysFromTrafficSecret]. Returns null
     * for any other identifier (TLS 1.2 suites, GREASE values) so callers can detect an unsupported
     * negotiation rather than guessing.
     */
    fun mapCipherSuiteId(id: Int): String? = when (id) {
        0x1301 -> "TLS_AES_128_GCM_SHA256"
        0x1302 -> "TLS_AES_256_GCM_SHA384"
        0x1303 -> "TLS_CHACHA20_POLY1305_SHA256"
        else -> null
    }

    // ── alert records (making TlsHandshakeState.FAILED real) ────────────

    /**
     * Parses a TLS alert record at [offset]. Requires content type 21 (alert) and a legal
     * record version (the same `0x0301..0x0304` range [looksLikeTls] already accepts), then
     * reads the 2-byte alert body (level + description, RFC 8446 §6.2).
     *
     * Real constraint, not a gap: TLS 1.3 encrypts alerts once handshake keys are up, so this
     * only ever sees pre-key (plaintext) alerts. Post-key handshake failure is covered by the
     * MITM leg's `SSLHandshakeException` catch and by observed RST/close signals — see
     * `CaptureEngine`/`MitmProxyEngine` task 11 wiring. This function does not claim to see more
     * than it can.
     */
    fun parseAlert(bytes: ByteArray, offset: Int = 0): TlsAlert? {
        if (bytes.size - offset < 7) return null // 5-byte record header + 2-byte alert body
        val contentType = bytes[offset].toInt() and 0xFF
        if (contentType != 21) return null // not an alert record
        val recordVersion = readU16(bytes, offset + 1)
        if (recordVersion !in 0x0301..0x0304) return null
        val recordLen = readU16(bytes, offset + 3)
        if (recordLen < 2) return null

        val level = bytes[offset + 5].toInt() and 0xFF
        val description = bytes[offset + 6].toInt() and 0xFF
        return TlsAlert(
            level = level,
            description = description,
            levelName = when (level) {
                1 -> "warning"
                2 -> "fatal"
                else -> "unknown($level)"
            },
            descriptionName = alertDescriptionName(description),
            isFatal = level == 2,
        )
    }

    /**
     * Walks record boundaries via [readU16] (the same helper [extractSni]/[parseClientHello]
     * use) looking for an alert record anywhere in [bytes] — so an alert following a handshake
     * record in the same buffer (e.g. a server ClientHello response bundled with an immediate
     * alert) is still found, not just one at offset 0.
     */
    fun findAlertInRecords(bytes: ByteArray): TlsAlert? {
        var pos = 0
        while (pos + 5 <= bytes.size) {
            val contentType = bytes[pos].toInt() and 0xFF
            val version = readU16(bytes, pos + 1)
            if (contentType !in 20..23 || version !in 0x0301..0x0304) break
            val recordLen = readU16(bytes, pos + 3)
            if (contentType == 21) {
                parseAlert(bytes, pos)?.let { return it }
            }
            pos += 5 + recordLen
        }
        return null
    }

    /** RFC 8446 §6 alert description codes. Unmapped codes are still reported honestly. */
    private fun alertDescriptionName(code: Int): String = when (code) {
        0 -> "close_notify"
        10 -> "unexpected_message"
        20 -> "bad_record_mac"
        21 -> "decryption_failed"
        22 -> "record_overflow"
        30 -> "decompression_failure"
        40 -> "handshake_failure"
        41 -> "no_certificate"
        42 -> "bad_certificate"
        43 -> "unsupported_certificate"
        44 -> "certificate_revoked"
        45 -> "certificate_expired"
        46 -> "certificate_unknown"
        47 -> "illegal_parameter"
        48 -> "unknown_ca"
        49 -> "access_denied"
        50 -> "decode_error"
        51 -> "decrypt_error"
        60 -> "export_restriction"
        70 -> "protocol_version"
        71 -> "insufficient_security"
        80 -> "internal_error"
        86 -> "inappropriate_fallback"
        90 -> "user_canceled"
        100 -> "no_renegotiation"
        109 -> "missing_extension"
        110 -> "unsupported_extension"
        111 -> "certificate_unobtainable"
        112 -> "unrecognized_name"
        113 -> "bad_certificate_status_response"
        114 -> "bad_certificate_hash_value"
        115 -> "unknown_psk_identity"
        116 -> "certificate_required"
        120 -> "no_application_protocol"
        else -> "unknown($code)"
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

/**
 * A parsed TLS 1.3 ServerHello (RFC 8446 §4.1.3): the negotiated cipher suite that selects QUIC's
 * 1-RTT AEAD/hash ([com.example.interception.quic.QuicCrypto.CipherSuite]) and the record version.
 * Recovered by [TlsDecoder.parseServerHello] from the server's Handshake CRYPTO frame once
 * [com.example.interception.quic.QuicCrypto.decryptHandshakePacket] makes it visible.
 * [cipherSuiteName] is null for a suite [TlsDecoder.mapCipherSuiteId] does not recognize.
 */
data class TlsServerHello(
    val version: Int,
    val cipherSuiteId: Int,
    val cipherSuiteName: String?,
)
