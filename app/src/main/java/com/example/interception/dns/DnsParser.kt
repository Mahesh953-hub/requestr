package com.example.interception.dns

/**
 * Basic DNS packet parser for capturing DNS queries/responses from tun.
 *
 * Wire format:
 *   Header (12 bytes): ID, Flags, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT
 *   Question: QNAME (label sequence), QTYPE, QCLASS
 *   Answer/Authority/Additional: RR records
 *
 * D2 note: [resolvedIps] on a response is what lets [com.example.correlation.graph.ConnectionLineage]
 * link a DNS answer to the TCP connection(s) that actually dialed one of those IPs -- so this
 * parser has to do real random-access RR parsing (not just count answers) to make that link
 * genuine rather than guessed.
 */
object DnsParser {

    data class DnsQuery(
        val transactionId: Int,
        val hostname: String,
        val queryType: String,
        val queryClass: String,
        val answers: List<String>,
        val isResponse: Boolean,
        /** IPv4/IPv6 addresses extracted from A/AAAA answer records (empty for queries). */
        val resolvedIps: List<String> = emptyList(),
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
     * Parses a DNS packet (UDP payload, port 53) and returns the query/response details.
     * Returns null if the packet is not a valid DNS message.
     *
     * Uses random access into [packet] (rather than a forward-only stream) so RFC 1035 §4.1.4
     * label-compression pointers can actually be followed -- both in the question and, for
     * responses, in every answer RR's NAME field.
     */
    fun parse(packet: ByteArray, srcPort: Int, dstPort: Int): DnsQuery? {
        if (srcPort != 53 && dstPort != 53) return null
        if (packet.size < 12) return null

        return try {
            val transactionId = u16(packet, 0)
            val flags = u16(packet, 2)
            val qdCount = u16(packet, 4)
            val anCount = u16(packet, 6)
            // NSCOUNT (8) + ARCOUNT (10) not consumed by name.

            val isResponse = (flags and 0x8000) != 0

            var offset = 12
            if (qdCount == 0) {
                return DnsQuery(transactionId, "", "0", "0", emptyList(), isResponse)
            }

            // Parse question section (first question only -- practically always exactly one).
            val (hostname, afterName) = readName(packet, offset)
            offset = afterName
            if (offset + 4 > packet.size) {
                return DnsQuery(transactionId, hostname, "0", "0", emptyList(), isResponse)
            }
            val qtype = u16(packet, offset)
            val qclass = u16(packet, offset + 2)
            offset += 4

            // Skip any additional questions beyond the first (rare, but keeps offset correct).
            for (i in 1 until qdCount) {
                if (offset >= packet.size) break
                val (_, next) = readName(packet, offset)
                offset = next + 4
            }

            val answerSummaries = mutableListOf<String>()
            val resolvedIps = mutableListOf<String>()

            if (isResponse && anCount > 0) {
                for (i in 0 until anCount) {
                    if (offset >= packet.size) break
                    val (rrName, afterRrName) = readName(packet, offset)
                    offset = afterRrName
                    if (offset + 10 > packet.size) break
                    val rrType = u16(packet, offset)
                    // rrClass at offset+2, TTL at offset+4 (4 bytes) -- not needed here.
                    val rdLength = u16(packet, offset + 8)
                    val rdataStart = offset + 10
                    if (rdataStart + rdLength > packet.size) break

                    when (rrType) {
                        1 -> if (rdLength == 4) { // A record
                            val ip = "${u8(packet, rdataStart)}.${u8(packet, rdataStart + 1)}." +
                                "${u8(packet, rdataStart + 2)}.${u8(packet, rdataStart + 3)}"
                            resolvedIps.add(ip)
                            answerSummaries.add("A $rrName -> $ip")
                        }
                        28 -> if (rdLength == 16) { // AAAA record
                            val ip = (0 until 16 step 2).joinToString(":") { j ->
                                "%02x%02x".format(u8(packet, rdataStart + j), u8(packet, rdataStart + j + 1))
                            }
                            resolvedIps.add(ip)
                            answerSummaries.add("AAAA $rrName -> $ip")
                        }
                        5 -> { // CNAME -- follow the chain but don't add to resolvedIps directly
                            val (cname, _) = readName(packet, rdataStart)
                            answerSummaries.add("CNAME $rrName -> $cname")
                        }
                        else -> answerSummaries.add("TYPE$rrType $rrName")
                    }

                    offset = rdataStart + rdLength
                }
            }

            DnsQuery(
                transactionId = transactionId,
                hostname = hostname,
                queryType = qtype.toString(),
                queryClass = qclass.toString(),
                answers = answerSummaries,
                isResponse = isResponse,
                resolvedIps = resolvedIps,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun u8(packet: ByteArray, at: Int): Int = packet[at].toInt() and 0xFF

    private fun u16(packet: ByteArray, at: Int): Int =
        ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

    /**
     * Reads a QNAME (RFC 1035 §4.1.4) starting at [start], returning the decoded dotted name
     * plus the offset immediately after the (possibly-compressed) name in the *original*
     * message -- i.e. right after the terminating 0x00 byte or, if the name ended in a pointer,
     * right after the 2-byte pointer itself. That distinction is what makes pointer-following
     * correct: the caller's cursor must advance past the pointer bytes, not past wherever the
     * pointer target happened to end.
     */
    private fun readName(packet: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var pos = start
        var jumps = 0
        var end = -1 // offset right after the name in the original stream (set on first pointer or terminator)

        while (pos < packet.size && jumps < 128) {
            val len = u8(packet, pos)
            if (len == 0) {
                if (end < 0) end = pos + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= packet.size) break
                val pointer = ((len and 0x3F) shl 8) or u8(packet, pos + 1)
                if (end < 0) end = pos + 2
                jumps++
                pos = pointer
                continue
            }
            pos++
            if (pos + len > packet.size) break
            labels.add(String(packet, pos, len, Charsets.ISO_8859_1))
            pos += len
        }
        if (end < 0) end = pos
        return labels.joinToString(".") to end
    }
}
