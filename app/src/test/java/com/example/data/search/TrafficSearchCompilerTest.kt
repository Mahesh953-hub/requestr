package com.example.data.search

import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import com.example.data.search.TrafficSearchCompiler.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 task 22: parse-to-SQL for every operator and key, precedence, quoting, the FTS `MATCH`
 * argument, the `LIKE` fallback and injection attempts. Pure JVM -- the SQL is executed for real
 * in [TrafficFtsIndexTest].
 */
class TrafficSearchCompilerTest {

    private val fts = TrafficSearchCompiler(useFts = true)
    private val like = TrafficSearchCompiler(useFts = false)

    private fun t(key: String?, v: String) = Node.Term(key, v)

    // -- parsing ----------------------------------------------------------------------------------

    @Test
    fun `adjacent terms are AND, OR binds weaker, NOT and dash negate, parentheses group`() {
        assertEquals(Node.And(listOf(t("status", "500"), t("host", "api"))), fts.parse("status:500 host:api"))
        assertEquals(Node.And(listOf(t("status", "500"), t("host", "api"))), fts.parse("status:500 AND host:api"))
        assertEquals(
            Node.Or(listOf(Node.And(listOf(t("status", "500"), t("host", "api"))), t("method", "POST"))),
            fts.parse("status:500 host:api OR method:POST"),
        )
        assertEquals(
            Node.And(listOf(t("status", "500"), Node.Or(listOf(t("host", "api"), t("method", "POST"))))),
            fts.parse("status:500 (host:api OR method:POST)"),
        )
        assertEquals(Node.And(listOf(t("host", "api"), Node.Not(t("status", "200")))), fts.parse("host:api NOT status:200"))
        assertEquals(Node.And(listOf(t("host", "api"), Node.Not(t(null, "debug")))), fts.parse("host:api -debug"))
        assertEquals(Node.Not(Node.Not(t(null, "x"))), fts.parse("NOT NOT x"))
    }

    @Test
    fun `quoted values keep spaces and are never split or keyed`() {
        assertEquals(t(null, "internal server error"), fts.parse("\"internal server error\""))
        assertEquals(t("body", "rate limit"), fts.parse("body:\"rate limit\""))
        assertEquals(t(null, "status:500"), fts.parse("\"status:500\""), )
        assertEquals(t(null, "or"), fts.parse("\"or\""), )
        assertEquals(Node.And(listOf(t(null, "a"), t(null, "b"))), fts.parse("a \"b"), ) // unterminated quote is tolerated
    }

    @Test
    fun `unknown keys and stray syntax degrade to free text or nothing`() {
        assertEquals(t(null, "foo:bar"), fts.parse("foo:bar"))
        assertEquals(Node.All, fts.parse("   "))
        assertEquals(Node.All, fts.parse("()"))
        assertEquals(t("host", "api"), fts.parse("(host:api"))
        assertEquals(t("host", "api"), fts.parse("host:api)"))
        assertEquals(Node.All, fts.parse("AND OR"))
        assertEquals(t("host", "api"), fts.parse("host:api OR"))
        assertEquals(t("host", "api"), fts.parse("host:api AND ()"))
    }

    // -- SQL --------------------------------------------------------------------------------------

    private fun placeholders(sql: String) = sql.count { it == '?' }

    @Test
    fun `every structured key compiles to a bound predicate`() {
        val cases = mapOf(
            "status:500" to ("COALESCE(t.statusCode, 0) = ?" to listOf<Any>(500L)),
            "status:5xx" to ("COALESCE(t.statusCode, 0) BETWEEN ? AND ?" to listOf<Any>(500L, 599L)),
            "status:>=400" to ("COALESCE(t.statusCode, 0) >= ?" to listOf<Any>(400L)),
            "method:post" to ("t.method = ?" to listOf<Any>("POST")),
            "host:api.example" to ("t.host LIKE ? ESCAPE '\\'" to listOf<Any>("%api.example%")),
            "path:/v1/users" to ("t.path LIKE ? ESCAPE '\\'" to listOf<Any>("%/v1/users%")),
            "duration:>1000" to ("t.latencyMs > ?" to listOf<Any>(1000L)),
            "duration:<50" to ("t.latencyMs < ?" to listOf<Any>(50L)),
            "duration:250" to ("t.latencyMs > ?" to listOf<Any>(250L)),
            "proto:h2" to ("t.protocol LIKE ? ESCAPE '\\'" to listOf<Any>("%h2%")),
            "source:gateway" to ("t.sourceIp IS NOT NULL" to emptyList()),
            "src:sdk" to ("t.protocol = 'sdk'" to emptyList()),
            "source:native" to ("(t.sourceIp IS NULL AND t.protocol <> 'sdk')" to emptyList()),
            "source:vpn_tun" to ("t.captureMethod LIKE ? ESCAPE '\\'" to listOf<Any>("%vpn\\_tun%")),
            "tag:gateway:10.0.0.7" to ("t.sourceIp = ?" to listOf<Any>("10.0.0.7")),
            "tag:nope" to ("0" to emptyList()),
            "status:abc" to ("0" to emptyList()),
        )
        for ((raw, expected) in cases) {
            val c = fts.predicate(raw)
            assertEquals(raw, expected.first, c.sql)
            assertEquals(raw, expected.second, c.args)
            assertEquals(raw, placeholders(c.sql), c.args.size)
        }
    }

    @Test
    fun `content-type checks the header rows and the header map`() {
        val c = fts.predicate("ct:json")
        assertTrue(c.sql.contains("FROM transaction_headers h WHERE h.trafficId = t.id AND h.name = 'content-type' COLLATE NOCASE AND h.value LIKE ? ESCAPE '\\'"))
        assertTrue(c.sql.contains("t.responseHeaders LIKE ? ESCAPE '\\'"))
        assertEquals(listOf<Any>("%json%", "%json%"), c.args)
    }

    @Test
    fun `free text compiles to an FTS MATCH with a quoted prefix query, optionally column scoped`() {
        val c = fts.predicate("timeout")
        assertEquals("t.id IN (SELECT rowid FROM traffic_fts WHERE traffic_fts MATCH ?)", c.sql)
        assertEquals(listOf<Any>("\"timeout\"*"), c.args)
        assertEquals(listOf<Any>("headers_text : \"x-request-id\"*"), fts.predicate("header:x-request-id").args)
        assertEquals(listOf<Any>("body_text : \"rate limit\"*"), fts.predicate("body:\"rate limit\"").args)
    }

    @Test
    fun `FTS operators typed by the user are searched literally`() {
        // A raw `NEAR(a b)` / `a OR b` / `col:x` / `x*` / `-x` would change the MATCH semantics;
        // quoting turns them into plain tokens and doubles embedded quotes.
        assertEquals("\"NEAR(a b)\"*", TrafficSearchCompiler.ftsQuery("NEAR(a b)", null))
        assertEquals("\"url:evil\"*", TrafficSearchCompiler.ftsQuery("url:evil", null))
        assertEquals("\"say \"\"hi\"\"\"*", TrafficSearchCompiler.ftsQuery("say \"hi\"", null))
        assertEquals("\"a\\*\"*", TrafficSearchCompiler.ftsQuery("a\\*", null))
    }

    @Test
    fun `LIKE fallback searches the same columns plus protocol children and escapes wildcards`() {
        val c = like.predicate("100%_done")
        assertFalse(c.sql.contains("traffic_fts"))
        assertTrue("NULL body columns must not poison NOT/OR", c.sql.startsWith("COALESCE((") && c.sql.endsWith("), 0)"))
        for (col in listOf("t.url", "t.host", "t.requestHeaders", "t.responseHeaders", "t.requestBody", "t.responseBody", "t.requestBodyPreview", "t.responseBodyPreview", "w.payloadText", "s.data", "g.decodedText")) {
            assertTrue(col, c.sql.contains("$col LIKE ? ESCAPE '\\'"))
        }
        assertEquals(placeholders(c.sql), c.args.size)
        assertTrue(c.args.all { it == "%100\\%\\_done%" })
        // header: scope skips bodies and children; body: scope skips url/host/headers.
        assertEquals(listOf("t.requestHeaders", "t.responseHeaders"), Regex("(t\\.\\w+) LIKE").findAll(like.predicate("header:x").sql).map { it.groupValues[1] }.toList())
        assertFalse(like.predicate("body:x").sql.contains("t.url"))
        assertTrue(like.predicate("body:x").sql.contains("w.payloadText"))
    }

    @Test
    fun `boolean structure is preserved in SQL with every value bound`() {
        val c = fts.compile("status:5xx (host:api OR method:POST) NOT body:ok", sessionId = 7L, limit = 25)
        assertEquals(
            "SELECT t.* FROM captured_traffic t WHERE (COALESCE(t.statusCode, 0) BETWEEN ? AND ? AND (t.host LIKE ? ESCAPE '\\' OR t.method = ?) AND NOT (t.id IN (SELECT rowid FROM traffic_fts WHERE traffic_fts MATCH ?))) AND t.sessionId = ? ORDER BY t.timestamp DESC, t.id DESC LIMIT ?",
            c.sql,
        )
        assertEquals(listOf<Any>(500L, 599L, "%api%", "POST", "body_text : \"ok\"*", 7L, 25), c.args)
    }

    @Test
    fun `injection attempts stay inside parameters`() {
        val nasty = "'; DROP TABLE captured_traffic; --"
        for (raw in listOf(nasty, "host:$nasty", "\"$nasty\"", "status:$nasty", "method:$nasty", "tag:gateway:$nasty")) {
            val c = fts.compile(raw)
            assertFalse(raw, c.sql.contains("DROP"))
            assertFalse(raw, c.sql.contains("captured_traffic;"))
            assertFalse(raw, c.sql.contains("--"))
            assertEquals(raw, placeholders(c.sql), c.args.size)
        }
        // `status:` with a non-number matches nothing rather than erroring.
        assertEquals("0", fts.predicate("status:'").sql)
    }

    @Test
    fun `three-condition model combines whole conditions with AND or OR`() {
        val and = fts.compileConditions(listOf("status:500", "host:api", ""), combineWithOr = false)
        assertTrue(and.sql.contains("WHERE (COALESCE(t.statusCode, 0) = ? AND t.host LIKE ? ESCAPE '\\')"))
        val or = fts.compileConditions(listOf("status:500 method:GET", "host:api"), combineWithOr = true)
        assertTrue(or.sql.contains("WHERE ((COALESCE(t.statusCode, 0) = ? AND t.method = ?) OR t.host LIKE ? ESCAPE '\\')"))
        assertTrue(fts.compileConditions(emptyList(), combineWithOr = true).sql.contains("WHERE 1 ORDER BY"))
    }

    @Test
    fun `blank query selects everything newest first`() {
        val c = fts.compile("")
        assertEquals("SELECT t.* FROM captured_traffic t WHERE 1 ORDER BY t.timestamp DESC, t.id DESC LIMIT ?", c.sql)
        assertEquals(listOf<Any>(TrafficSearchCompiler.DEFAULT_LIMIT), c.args)
    }
    @Test
    fun `in-memory evaluation of the parsed tree matches the legacy filter and honours OR NOT and parens`() {
        val items = listOf(
            CapturedTrafficItem(id = "1", method = HttpMethod.GET, url = "https://api.example.com/users", host = "api.example.com", path = "/users", statusCode = 200, latencyMs = 40, requestBody = "", responseBody = "{\"users\":[]}", appName = "", appPackage = ""),
            CapturedTrafficItem(id = "2", method = HttpMethod.POST, url = "https://api.example.com/orders", host = "api.example.com", path = "/orders", statusCode = 503, latencyMs = 2_400, requestBody = "", responseBody = "upstream timeout", appName = "", appPackage = ""),
            CapturedTrafficItem(id = "3", method = HttpMethod.GET, url = "https://cdn.example.com/app.js", host = "cdn.example.com", path = "/app.js", statusCode = 304, latencyMs = 5, requestBody = "", responseBody = "", appName = "", appPackage = ""),
        )
        fun ids(q: String) = TrafficSearch.filter(items, q).map { it.id }
        // Legacy AND-only queries behave exactly as before.
        assertEquals(listOf("1", "2"), ids("host:api"))
        assertEquals(listOf("2"), ids("host:api status:5xx"))
        assertEquals(listOf("2"), ids("timeout"))
        assertEquals(listOf("1", "2", "3"), ids(""))
        // New grammar.
        assertEquals(listOf("2", "3"), ids("status:5xx OR host:cdn"))
        assertEquals(listOf("1"), ids("host:api NOT status:5xx"))
        assertEquals(listOf("1"), ids("host:api -timeout"))
        assertEquals(listOf("1", "3"), ids("method:GET (path:users OR path:app)"))
        assertEquals(listOf("2"), ids("duration:>1000"))
        assertEquals(listOf("1", "3"), ids("status:<500"))
        assertEquals(listOf("2"), ids("body:upstream"))
        assertEquals(emptyList<String>(), ids("status:abc"))
        assertEquals(listOf("2"), ids("\"upstream timeout\""))
    }
}
