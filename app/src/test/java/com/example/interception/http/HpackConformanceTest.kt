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
 * S3 task 23 / gate G11 (decision D7): the nghttp2 HPACK vector corpus from
 * `http2jp/hpack-test-case` (MIT, see `src/test/resources/hpack-test-case/LICENSE`) decoded by
 * the in-house [Http2Parser.HpackDecoder].
 *
 * Three encoder profiles (32 + 31 + 31 stories), 9,918 header blocks in total:
 *  - `nghttp2`                    default 4,096-byte table, Huffman when shorter, mixed indexing
 *  - `nghttp2-16384-4096`         `SETTINGS_HEADER_TABLE_SIZE` 16,384 negotiated down to 4,096
 *  - `nghttp2-change-table-size`  the table size changes between blocks (dynamic table size
 *                                 update instructions on the wire, evictions on shrink)
 *
 * Each story is decoded with ONE decoder instance in sequence (dynamic table state carries over),
 * exactly as a connection direction would. `header_table_size` is applied as the acknowledged
 * SETTINGS value before the block that carries it.
 */
class HpackConformanceTest {

    private val moshi = Moshi.Builder().build()
    private val storyAdapter = moshi.adapter<Map<String, Any>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    private data class Case(val seqno: Int, val tableSize: Int?, val wire: ByteArray, val headers: List<Pair<String, String>>)

    private fun loadStory(profile: String, index: Int): List<Case> {
        val name = "hpack-test-case/$profile/story_${index.toString().padStart(2, '0')}.json.gz"
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing corpus file $name" }
        val json = GZIPInputStream(stream).use { InputStreamReader(it, Charsets.UTF_8).readText() }
        val story = storyAdapter.fromJson(json)!!
        @Suppress("UNCHECKED_CAST")
        val cases = story["cases"] as List<Map<String, Any>>
        return cases.map { c ->
            @Suppress("UNCHECKED_CAST")
            val headers = (c["headers"] as List<Map<String, String>>).map { h -> h.entries.single().let { it.key to it.value } }
            Case(
                seqno = (c["seqno"] as Double).toInt(),
                tableSize = (c["header_table_size"] as Double?)?.toInt(),
                wire = hex(c["wire"] as String),
                headers = headers,
            )
        }
    }

    private fun storyCount(profile: String) = if (profile == "nghttp2") 32 else 31

    private fun runProfile(profile: String): Int {
        var blocks = 0
        for (story in 0 until storyCount(profile)) {
            val decoder = Http2Parser.HpackDecoder()
            for (case in loadStory(profile, story)) {
                case.tableSize?.let { decoder.setMaxHeaderTableSize(it) }
                val frame = Http2Parser.H2Frame(case.wire.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, case.wire)
                val decoded = Http2Parser.decodeHeaders(frame, case.wire, decoder)
                val where = "$profile/story_$story seqno=${case.seqno}"
                assertNull(where, decoded.error)
                assertEquals(where, case.headers, decoded.entries)
                blocks++
            }
        }
        return blocks
    }

    @Test
    fun `nghttp2 default profile decodes every header block byte-exactly`() {
        assertEquals(3_384, runProfile("nghttp2"))
    }

    @Test
    fun `nghttp2 16384 to 4096 table size profile decodes every header block`() {
        assertEquals(3_267, runProfile("nghttp2-16384-4096"))
    }

    @Test
    fun `nghttp2 changing table size profile decodes every header block with evictions`() {
        assertEquals(3_267, runProfile("nghttp2-change-table-size"))
    }

    @Test
    fun `corpus stories are complete`() {
        // Guards against a partially checked-in corpus silently shrinking the gate.
        for (profile in listOf("nghttp2", "nghttp2-16384-4096", "nghttp2-change-table-size")) {
            for (story in 0 until storyCount(profile)) assertTrue("$profile/$story", loadStory(profile, story).isNotEmpty())
        }
    }

    companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }
    }
}
