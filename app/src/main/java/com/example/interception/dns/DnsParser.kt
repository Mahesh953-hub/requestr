package com.example.interception.dns

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Basic DNS packet parser for capturing DNS queries from tun.
 *
 * Wire format:
 *   Header (12 bytes): ID, Flags, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT
 *   Question: QNAME (label sequence), QTYPE, QCLASS
 *   Answer/Authority/Additional: RR records
 */
object DnsParser {

    data class DnsQuery(
        val transactionId: Int,
        val hostname: String,
        val queryType: String,
        val queryClass: String,
        val answers: List<String>,
        val isResponse: Boolean,
    ) {
        val qtypeName: String
            get() = when (queryType) {
                "1" -> "A"
                "2" -> "NS"
                "5" -> "CNAME"
                "6" -> "SOA"
                "15" -> "MX"
                "16" -> "TXT"
                "28" -> "AAAA"
                "33" -> "SRV"
                "255" -> "ANY"
                "65" -> "HTTPS"
                "99" -> "SPF"
                else -> "TYPE$queryType"
            }
    }

    /**
     * Parses a DNS packet (UDP payload, port 53) and returns the query details.
     * Returns null if the packet is not a valid DNS message.
     */
    fun parse(packet: ByteArray, srcPort: Int, dstPort: Int): DnsQuery? {
        if (srcPort != 53 && dstPort != 53) return null
        if (packet.size < 12) return null

        return try {
            val stream = DataInputStream(ByteArrayInputStream(packet))
            val transactionId = stream.readShort().toInt() and 0xFFFF
            val flags = stream.readShort().toInt() and 0xFFFF
            val qdCount = stream.readShort().toInt() and 0xFFFF
            val anCount = stream.readShort().toInt() and 0xFFFF
            stream.skipBytes(4) // NSCOUNT + ARCOUNT

            val isResponse = (flags and 0x8000) != 0

            if (qdCount == 0) {
                return DnsQuery(transactionId, "", "0", "0", emptyList(), isResponse)
            }

            // Parse question section
            val hostname = parseQname(stream)
            val qtype = stream.readShort().toInt() and 0xFFFF
            val qclass = stream.readShort().toInt() and 0xFFFF

            // Skip answers for query-side logging (response has answers)
            val answers = mutableListOf<String>()
            if (isResponse && anCount > 0) {
                // Skip to answer section — we don't fully parse RRs for simplicity
                // but log the answer count
                answers.add("$anCount answer(s)")
            }

            DnsQuery(
                transactionId = transactionId,
                hostname = hostname,
                queryType = qtype.toString(),
                queryClass = qclass.toString(),
                answers = answers,
                isResponse = isResponse,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses a QNAME (RFC 1035 §4.1.4) — sequence of length-prefixed labels
     * terminated by a null byte or pointer.
     */
    private fun parseQname(stream: DataInputStream): String {
        val sb = StringBuilder()
        var jumped = false
        var jumps = 0
        var current = stream.readUnsignedByte()

        while (current != 0 && jumps < 128) {
            if ((current and 0xC0) == 0xC0) {
                // Pointer to another location
                val ptr = (current and 0x3F shl 8) or stream.readUnsignedByte()
                if (!jumped) {
                    stream.mark(1)
                }
                jumped = true
                jumps++
                // Skip to the pointer target — for simplicity, we can't rewind
                // from a stream, so we just mark and continue reading.
                // A full implementation would need random access.
                current = stream.readUnsignedByte()
                continue
            }
            if (sb.isNotEmpty()) sb.append(".")
            val labelBytes = ByteArray(current)
            stream.readFully(labelBytes)
            sb.append(String(labelBytes, Charsets.ISO_8859_1))
            current = stream.readUnsignedByte()
        }
        return sb.toString()
    }
}
