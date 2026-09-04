package com.example.interception.http

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * S3 task 57 (gate G27): the HPACK *encoder* the terminating endpoint uses to re-emit header
 * blocks. Every block it produces must decode back byte-exactly with the in-house
 * [Http2Parser.HpackDecoder] (which gate G11 proved against the nghttp2 corpus), including the
 * dynamic-table interplay across a whole connection direction.
 */
class HpackEncoderTest {

    private fun decode(decoder: Http2Parser.HpackDecoder, wire: ByteArray): Http2Parser.H2Headers {
        val frame = Http2Parser.H2Frame(wire.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, wire)
        return Http2Parser.decodeHeaders(frame, wire, decoder)
    }

    private val request = listOf(
        ":method" to "GET",
        ":scheme" to "https",
        ":authority" to "api.example.com",
        ":path" to "/v1/items?page=2",
        "accept" to "application/json",
        "user-agent" to "requestr-test/1.0",
        "x-request-id" to "0123456789abcdef",
    )

    @Test
    fun `round trips a request block and reuses the dynamic table on the second block`() {
        val encoder = HpackEncoder()
        val decoder = Http2Parser.HpackDecoder()

        val first = encoder.encode(request)
        val decodedFirst = decode(decoder, first)
        assertNull(decodedFirst.error)
        assertEquals(request, decodedFirst.entries)

        // The same block again must be all indexed references -- far smaller than the first.
        val second = encoder.encode(request)
        assertTrue("second block ${second.size} B should be much smaller than first ${first.size} B", second.size < first.size / 3)
        val decodedSecond = decode(decoder, second)
        assertNull(decodedSecond.error)
        assertEquals(request, decodedSecond.entries)
        assertEquals(decoder.dynamicCount, encoder.dynamicCount)
        assertEquals(decoder.size, encoder.size)
    }

    @Test
    fun `lowercases field names on the wire and keeps duplicate set-cookie in order`() {
        val encoder = HpackEncoder()
        val decoder = Http2Parser.HpackDecoder()
        val response = listOf(
            ":status" to "200",
            "Content-Type" to "text/html",
            "Set-Cookie" to "a=1; Path=/",
            "Set-Cookie" to "b=2; Path=/; HttpOnly",
        )
        val decoded = decode(decoder, encoder.encode(response))
        assertNull(decoded.error)
        assertEquals(
            listOf(":status" to "200", "content-type" to "text/html", "set-cookie" to "a=1; Path=/", "set-cookie" to "b=2; Path=/; HttpOnly"),
            decoded.entries,
        )
    }

    @Test
    fun `sensitive fields are sent never-indexed and stay out of both dynamic tables`() {
        val encoder = HpackEncoder()
        val decoder = Http2Parser.HpackDecoder()
        val block = listOf(":method" to "GET", ":scheme" to "https", ":path" to "/", "authorization" to "Bearer secret-token", "cookie" to "sid=abc")
        val wire = encoder.encode(block)
        val decoded = decode(decoder, wire)
        assertNull(decoded.error)
        assertEquals(block, decoded.entries)
        // Only :path "/"(static) and the two pseudo statics are referenced; nothing sensitive inserted.
        assertEquals(0, encoder.dynamicCount)
        assertEquals(0, decoder.dynamicCount)
        // Never-indexed literal prefix 0001xxxx must appear for both sensitive fields.
        assertTrue(wire.count { (it.toInt() and 0xF0) == 0x10 } >= 2)
    }

    @Test
    fun `emits a dynamic table size update after the peer shrinks HEADER_TABLE_SIZE`() {
        val encoder = HpackEncoder()
        val decoder = Http2Parser.HpackDecoder()
        assertEquals(request, decode(decoder, encoder.encode(request)).entries)
        assertTrue(encoder.dynamicCount > 0)

        decoder.setMaxHeaderTableSize(0)
        encoder.setMaxHeaderTableSize(0)
        assertEquals(0, encoder.dynamicCount)

        val wire = encoder.encode(request)
        // RFC 7541 §6.3: size update 001xxxxx with value 0 as the very first byte.
        assertEquals(0x20, wire[0].toInt() and 0xFF)
        val decoded = decode(decoder, wire)
        assertNull(decoded.error)
        assertEquals(request, decoded.entries)
        assertEquals(0, decoder.dynamicCount)

        // Growing back: update emitted once, entries indexed again afterwards.
        decoder.setMaxHeaderTableSize(4096)
        encoder.setMaxHeaderTableSize(4096)
        val grown = decode(decoder, encoder.encode(request))
        assertNull(grown.error)
        assertEquals(request, grown.entries)
        assertEquals(decoder.dynamicCount, encoder.dynamicCount)
    }

    @Test
    fun `an entry larger than the table empties it instead of being inserted`() {
        val encoder = HpackEncoder(initialMaxTableSize = 128)
        val decoder = Http2Parser.HpackDecoder().also { it.setMaxHeaderTableSize(128) }
        // First block is written before any SETTINGS exchange, so the encoder announces 128.
        val small = listOf(":status" to "200", "x-a" to "1")
        assertEquals(small, decode(decoder, encoder.encode(small)).entries)
        val huge = listOf(":status" to "200", "x-huge" to "v".repeat(500))
        val decoded = decode(decoder, encoder.encode(huge))
        assertNull(decoded.error)
        assertEquals(huge, decoded.entries)
        assertEquals(decoder.dynamicCount, encoder.dynamicCount)
        assertEquals(decoder.size, encoder.size)
    }

    @Test
    fun `huffman encoder round trips every byte value and prefers the shorter form`() {
        // All 256 octets are not valid UTF-8, so decode falls back to ISO-8859-1 (byte-preserving).
        val all = ByteArray(256) { it.toByte() }
        val encoded = HpackHuffman.encode(all)
        assertEquals(encoded.size, HpackHuffman.encodedLength(all))
        assertEquals(all.toList(), HpackHuffman.decode(encoded, 0, encoded.size).toByteArray(Charsets.ISO_8859_1).toList())
        val ascii = "www.example.com".toByteArray()
        val huff = HpackHuffman.encode(ascii)
        assertEquals(12, huff.size) // RFC 7541 C.4.1
        assertEquals(huff.size, HpackHuffman.encodedLength(ascii))
        assertEquals("www.example.com", HpackHuffman.decode(huff, 0, huff.size))
    }

    // ── nghttp2 corpus re-encoded ───────────────────────────────────────────────────────

    private val moshi = Moshi.Builder().build()
    private val storyAdapter = moshi.adapter<Map<String, Any>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    private fun loadStoryHeaders(profile: String, index: Int): List<Pair<Int?, List<Pair<String, String>>>> {
        val name = "hpack-test-case/$profile/story_${index.toString().padStart(2, '0')}.json.gz"
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing corpus file $name" }
        val json = GZIPInputStream(stream).use { InputStreamReader(it, Charsets.UTF_8).readText() }
        val story = storyAdapter.fromJson(json)!!
        @Suppress("UNCHECKED_CAST")
        val cases = story["cases"] as List<Map<String, Any>>
        return cases.map { c ->
            @Suppress("UNCHECKED_CAST")
            val headers = (c["headers"] as List<Map<String, String>>).map { h -> h.entries.single().let { it.key to it.value } }
            (c["header_table_size"] as Double?)?.toInt() to headers
        }
    }

    @Test
    fun `re-encodes every nghttp2 corpus story and decodes back byte-exactly`() {
        var blocks = 0
        for ((profile, count) in listOf("nghttp2" to 32, "nghttp2-change-table-size" to 31)) {
            for (story in 0 until count) {
                val encoder = HpackEncoder()
                val decoder = Http2Parser.HpackDecoder()
                for ((tableSize, headers) in loadStoryHeaders(profile, story)) {
                    tableSize?.let { decoder.setMaxHeaderTableSize(it); encoder.setMaxHeaderTableSize(it) }
                    val decoded = decode(decoder, encoder.encode(headers))
                    val where = "$profile/story_$story block $blocks"
                    assertNull(where, decoded.error)
                    // The corpus keeps original casing only in lowercase already; compare lowercased names.
                    assertEquals(where, headers.map { it.first.lowercase() to it.second }, decoded.entries)
                    assertEquals(where, decoder.size, encoder.size)
                    blocks++
                }
            }
        }
        assertEquals(3_384 + 3_267, blocks)
    }
}
