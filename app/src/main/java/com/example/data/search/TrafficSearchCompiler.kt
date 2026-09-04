package com.example.data.search

import com.example.data.models.CapturedTrafficItem

/**
 * S3 task 22: compiles the traffic search grammar into one parameterised SQL statement over
 * `captured_traffic` (+ `traffic_fts`, `transaction_headers` and the protocol child tables).
 *
 * ### Grammar
 *
 * ```
 * query   := or
 * or      := and ( OR and )*
 * and     := unary ( [AND] unary )*          -- adjacency is AND
 * unary   := NOT unary | '-' unary | '(' query ')' | term
 * term    := key ':' value | value            -- value may be "quoted with spaces"
 * ```
 *
 * Keys are the existing [TrafficSearch] vocabulary -- `status:` (`500`, `5xx`, `>=400`),
 * `method:`, `host:`, `path:`, `duration:>N` / `duration:<N`, `content-type:`/`ct:`,
 * `protocol:`/`proto:`, `source:`/`src:`, `tag:` -- plus `header:` and `body:` which scope the
 * full-text match to one FTS column. A term without a key, or with an unknown key, is free text.
 *
 * ### SQL
 *
 * Structured keys compile to indexed `WHERE` predicates on `captured_traffic`; free text compiles
 * to `t.id IN (SELECT rowid FROM traffic_fts WHERE traffic_fts MATCH ?)` when the FTS5 index is
 * available ([useFts]) and to `LIKE` predicates over the same columns (plus the WebSocket / SSE /
 * gRPC child payloads) otherwise, so the two paths agree on what a hit is. Every user value is a
 * bound parameter -- nothing the user typed is ever concatenated into SQL, and the `MATCH`
 * argument is rebuilt as a quoted FTS5 string (`"..."*`) so FTS operators typed by the user
 * (`NEAR`, `:`, `*`, `-`) are searched for literally rather than interpreted.
 *
 * The three-condition model of the search UI is [compileConditions]: each condition is a query in
 * this grammar; they are combined with AND (subdivide) or OR (global).
 */
class TrafficSearchCompiler(private val useFts: Boolean) {

    sealed interface Node {
        data class And(val children: List<Node>) : Node
        data class Or(val children: List<Node>) : Node
        data class Not(val child: Node) : Node
        /** [key] is null for free text; otherwise already lower-cased. */
        data class Term(val key: String?, val value: String) : Node
        object All : Node
    }

    /** SQL text plus its positional arguments, ready for `SimpleSQLiteQuery(sql, args)`. */
    data class Compiled(val sql: String, val args: List<Any>)

    // -- parsing ----------------------------------------------------------------------------------

    private sealed interface Tok {
        data class Word(val text: String, val quoted: Boolean) : Tok
        object LParen : Tok
        object RParen : Tok
    }

    private fun tokenize(raw: String): List<Tok> {
        val out = ArrayList<Tok>()
        val sb = StringBuilder()
        var quoted = false
        var inQuote = false
        var keyValue = false // `key:"a b"` -- the quotes protect the value, the key still applies
        fun flush() {
            if (sb.isNotEmpty() || quoted) out += Tok.Word(sb.toString(), quoted)
            sb.clear(); quoted = false; keyValue = false
        }
        for (ch in raw) {
            when {
                ch == '"' -> {
                    if (!inQuote && sb.isNotEmpty() && sb.last() == ':') { inQuote = true; keyValue = true }
                    else { inQuote = !inQuote; if (!keyValue) quoted = true }
                }
                inQuote -> sb.append(ch)
                ch == '(' -> { flush(); out += Tok.LParen }
                ch == ')' -> { flush(); out += Tok.RParen }
                ch.isWhitespace() -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    private class Parser(private val toks: List<Tok>) {
        private var i = 0
        private fun flatten(and: Node.And): Node = Node.And(and.children.flatMap { if (it is Node.And) it.children else listOf(it) })
        private fun peek(): Tok? = toks.getOrNull(i)
        private fun isKeyword(t: Tok?, kw: String) = t is Tok.Word && !t.quoted && t.text.equals(kw, ignoreCase = true)

        fun parseQuery(): Node {
            val node = parseOr()
            while (peek() == Tok.RParen) i++ // stray ')' is ignored, never an error while typing
            if (peek() == null) return node
            val rest = parseQuery()
            return when {
                node == Node.All -> rest
                rest == Node.All -> node
                else -> flatten(Node.And(listOf(node, rest)))
            }
        }

        private fun parseOr(): Node {
            val parts = arrayListOf(parseAnd())
            while (isKeyword(peek(), "OR")) { i++; parts += parseAnd() }
            val real = parts.filter { it != Node.All } // a dangling `OR` while typing is not "everything"
            return when (real.size) { 0 -> Node.All; 1 -> real[0]; else -> Node.Or(real) }
        }

        private fun parseAnd(): Node {
            val parts = ArrayList<Node>()
            while (true) {
                val t = peek() ?: break
                if (t == Tok.RParen || isKeyword(t, "OR")) break
                if (isKeyword(t, "AND")) { i++; continue }
                val u = parseUnary()
                if (u != Node.All) parts += u
            }
            return when (parts.size) { 0 -> Node.All; 1 -> parts[0]; else -> Node.And(parts) }
        }

        private fun parseUnary(): Node {
            val t = peek()
            if (isKeyword(t, "NOT")) { i++; return Node.Not(parseUnary()) }
            if (t is Tok.Word && !t.quoted && t.text.length > 1 && t.text[0] == '-' && t.text[1] != ' ') {
                i++
                return Node.Not(term(t.text.substring(1), quoted = false))
            }
            if (t == Tok.LParen) {
                i++
                val inner = parseOr()
                if (peek() == Tok.RParen) i++
                return inner
            }
            i++
            return when (t) {
                is Tok.Word -> term(t.text, t.quoted)
                else -> Node.All
            }
        }

        private fun term(text: String, quoted: Boolean): Node {
            if (!quoted) {
                val colon = text.indexOf(':')
                if (colon > 0 && colon < text.length - 1) {
                    val key = text.substring(0, colon).lowercase()
                    if (key in KEYS) return Node.Term(key, text.substring(colon + 1).trim())
                }
            }
            return if (text.isBlank()) Node.All else Node.Term(null, text)
        }
    }


    fun parse(raw: String): Node = if (raw.isBlank()) Node.All else Parser(tokenize(raw)).parseQuery()

    // -- compilation ------------------------------------------------------------------------------

    /**
     * `SELECT t.* FROM captured_traffic t WHERE ... ORDER BY t.timestamp DESC LIMIT ?`, optionally
     * scoped to one [sessionId].
     */
    fun compile(raw: String, sessionId: Long? = null, limit: Int = DEFAULT_LIMIT): Compiled =
        compileNode(parse(raw), sessionId, limit)

    /** The UI's three-condition model: every condition is a query; `AND` = subdivide, `OR` = global. */
    fun compileConditions(conditions: List<String>, combineWithOr: Boolean, sessionId: Long? = null, limit: Int = DEFAULT_LIMIT): Compiled {
        val nodes = conditions.filter { it.isNotBlank() }.map(::parse)
        val root: Node = when {
            nodes.isEmpty() -> Node.All
            nodes.size == 1 -> nodes[0]
            combineWithOr -> Node.Or(nodes)
            else -> Node.And(nodes)
        }
        return compileNode(root, sessionId, limit)
    }

    fun compileNode(root: Node, sessionId: Long? = null, limit: Int = DEFAULT_LIMIT): Compiled {
        val args = ArrayList<Any>()
        val where = StringBuilder(predicate(root, args))
        if (sessionId != null) { where.append(" AND t.sessionId = ?"); args += sessionId }
        args += limit
        return Compiled("SELECT t.* FROM captured_traffic t WHERE $where ORDER BY t.timestamp DESC, t.id DESC LIMIT ?", args)
    }

    /** Just the `WHERE` body for [raw] (tests, and callers embedding it in their own statement). */
    fun predicate(raw: String): Compiled {
        val args = ArrayList<Any>()
        return Compiled(predicate(parse(raw), args), args)
    }

    private fun predicate(node: Node, args: MutableList<Any>): String = when (node) {
        Node.All -> "1"
        is Node.And -> node.children.joinToString(" AND ", "(", ")") { predicate(it, args) }
        is Node.Or -> node.children.joinToString(" OR ", "(", ")") { predicate(it, args) }
        is Node.Not -> "NOT (${predicate(node.child, args)})"
        is Node.Term -> term(node, args)
    }

    private fun term(t: Node.Term, args: MutableList<Any>): String {
        val v = t.value
        return when (t.key) {
            null -> freeText(v, column = null, args)
            "header" -> freeText(v, column = "headers_text", args)
            "body" -> freeText(v, column = "body_text", args)
            "status" -> status(v, args)
            "method" -> { args += v.uppercase(); "t.method = ?" }
            "host" -> like("t.host", v, args)
            "path" -> like("t.path", v, args)
            "duration" -> when {
                v.startsWith(">=") -> num(v.drop(2), args) { "t.latencyMs >= ?" }
                v.startsWith("<=") -> num(v.drop(2), args) { "t.latencyMs <= ?" }
                v.startsWith(">") -> num(v.drop(1), args) { "t.latencyMs > ?" }
                v.startsWith("<") -> num(v.drop(1), args) { "t.latencyMs < ?" }
                else -> num(v, args) { "t.latencyMs > ?" }
            }
            "content-type", "ct" -> {
                val a = like("h.value", v, args)
                val b = like("t.responseHeaders", v, args)
                "(EXISTS (SELECT 1 FROM transaction_headers h WHERE h.trafficId = t.id AND h.name = 'content-type' COLLATE NOCASE AND $a) OR $b)"
            }
            "protocol", "proto" -> like("t.protocol", v, args)
            "source", "src" -> source(v, args)
            "tag" -> tag(v, args)
            else -> freeText("${t.key}:$v", column = null, args)
        }
    }

    private fun status(v: String, args: MutableList<Any>): String {
        val cls = Regex("^([1-5])xx$", RegexOption.IGNORE_CASE).find(v)
        if (cls != null) {
            val hundreds = cls.groupValues[1].toInt() * 100
            args += hundreds.toLong(); args += (hundreds + 99).toLong()
            return "COALESCE(t.statusCode, 0) BETWEEN ? AND ?"
        }
        return when {
            v.startsWith(">=") -> num(v.drop(2), args) { "COALESCE(t.statusCode, 0) >= ?" }
            v.startsWith("<=") -> num(v.drop(2), args) { "COALESCE(t.statusCode, 0) <= ?" }
            v.startsWith(">") -> num(v.drop(1), args) { "COALESCE(t.statusCode, 0) > ?" }
            v.startsWith("<") -> num(v.drop(1), args) { "COALESCE(t.statusCode, 0) < ?" }
            else -> num(v, args) { "COALESCE(t.statusCode, 0) = ?" }
        }
    }

    private inline fun num(v: String, args: MutableList<Any>, sql: () -> String): String {
        val n = v.trim().toLongOrNull() ?: return "0" // a non-number can match nothing, honestly
        args += n
        return sql()
    }

    private fun source(v: String, args: MutableList<Any>): String = when (v.lowercase()) {
        "native" -> "(t.sourceIp IS NULL AND t.protocol <> 'sdk')"
        "gateway" -> "t.sourceIp IS NOT NULL"
        "sdk" -> "t.protocol = 'sdk'"
        else -> like("t.captureMethod", v, args) // VPN_TUN, MITM_PROXY, HAR_IMPORT, ...
    }

    /** The only tag the row model derives today is `gateway:<ip>`; anything else matches nothing. */
    private fun tag(v: String, args: MutableList<Any>): String {
        val ip = v.removePrefix("gateway:").takeIf { v.lowercase().startsWith("gateway:") } ?: return "0"
        args += ip
        return "t.sourceIp = ?"
    }

    private fun like(column: String, v: String, args: MutableList<Any>): String {
        args += "%${escapeLike(v)}%"
        return "$column LIKE ? ESCAPE '\\'"
    }

    private fun freeText(v: String, column: String?, args: MutableList<Any>): String {
        if (useFts) {
            args += ftsQuery(v, column)
            return "t.id IN (SELECT rowid FROM ${FtsSchema.TABLE} WHERE ${FtsSchema.TABLE} MATCH ?)"
        }
        val cols = when (column) {
            "headers_text" -> listOf("t.requestHeaders", "t.responseHeaders")
            "body_text" -> listOf("t.requestBody", "t.responseBody", "t.requestBodyPreview", "t.responseBodyPreview")
            else -> listOf("t.url", "t.host", "t.requestHeaders", "t.responseHeaders", "t.requestBody", "t.responseBody", "t.requestBodyPreview", "t.responseBodyPreview")
        }
        val parts = cols.map { like(it, v, args) }.toMutableList()
        if (column != "headers_text") {
            parts += "EXISTS (SELECT 1 FROM websocket_messages w WHERE w.trafficId = t.id AND ${like("w.payloadText", v, args)})"
            parts += "EXISTS (SELECT 1 FROM sse_events s WHERE s.trafficId = t.id AND ${like("s.data", v, args)})"
            parts += "EXISTS (SELECT 1 FROM grpc_messages g WHERE g.trafficId = t.id AND ${like("g.decodedText", v, args)})"
        }
        // Nullable body columns make `x LIKE ?` NULL, and `NOT (NULL OR 0)` is NULL (row dropped);
        // coalesce so negation and OR behave like the in-memory filter.
        return parts.joinToString(" OR ", "COALESCE((", "), 0)")
    }

    // -- in-memory evaluation (flag off / already-loaded lists) -------------------------------------

    /** Evaluates [node] against a loaded item with the same semantics [TrafficSearch.matches] has per term. */
    fun matches(node: Node, item: CapturedTrafficItem): Boolean = when (node) {
        Node.All -> true
        is Node.And -> node.children.all { matches(it, item) }
        is Node.Or -> node.children.any { matches(it, item) }
        is Node.Not -> !matches(node.child, item)
        is Node.Term -> when (node.key) {
            // Keys whose SQL semantics go beyond the legacy in-memory parser (`5xx`, comparisons,
            // column scoping) are evaluated here so both paths agree; the rest delegate.
            "status" -> statusMatches(node.value, item.statusCode)
            "header" -> (item.requestHeaders.entries + item.responseHeaders.entries).any {
                it.key.contains(node.value, ignoreCase = true) || it.value.contains(node.value, ignoreCase = true)
            }
            "body" -> item.requestBody.contains(node.value, ignoreCase = true) || item.responseBody.contains(node.value, ignoreCase = true)
            else -> TrafficSearch.matches(item, TrafficSearch.parse(if (node.key == null) quoteIfNeeded(node.value) else "${node.key}:${quoteIfNeeded(node.value)}"))
        }
    }

    private fun statusMatches(v: String, status: Int): Boolean {
        if (v.length == 3 && v[0].isDigit() && v.endsWith("xx", ignoreCase = true)) {
            val hundreds = (v[0] - '0') * 100
            return status in hundreds..(hundreds + 99)
        }
        fun n(str: String) = str.toLongOrNull()
        return when {
            v.startsWith(">=") -> n(v.drop(2))?.let { status >= it } ?: false
            v.startsWith("<=") -> n(v.drop(2))?.let { status <= it } ?: false
            v.startsWith(">") -> n(v.drop(1))?.let { status > it } ?: false
            v.startsWith("<") -> n(v.drop(1))?.let { status < it } ?: false
            else -> n(v)?.let { status.toLong() == it } ?: false
        }
    }

    private fun quoteIfNeeded(v: String) = if (v.any { it.isWhitespace() }) "\"$v\"" else v

    companion object {
        const val DEFAULT_LIMIT = 500

        val KEYS = setOf(
            "status", "method", "host", "path", "duration", "content-type", "ct", "protocol", "proto",
            "source", "src", "tag", "header", "body",
        )

        /** `%`/`_`/`\` in user text are literals, never wildcards. */
        fun escapeLike(v: String): String = v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * A user term as an FTS5 query: a quoted string (so operators are literal) with a prefix
         * `*`, optionally scoped to one column. Whitespace inside a quoted term is a phrase.
         */
        fun ftsQuery(term: String, column: String?): String {
            val quoted = "\"" + term.replace("\"", "\"\"") + "\"*"
            return if (column == null) quoted else "$column : $quoted"
        }
    }
}
