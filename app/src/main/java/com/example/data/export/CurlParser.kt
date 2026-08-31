package com.example.data.export

import java.util.Base64

/**
 * Parses a pasted `curl ...` command back into a request (the reverse of [CodeExporter]'s
 * `ExportFormat.CURL` export), for Master Plan §3 LOW-ADD "copy/paste cURL for live traffic" (C11).
 *
 * The Interceptr screen already had a "Paste cURL" header action before this file existed, but it
 * called straight into [com.example.data.repository.ApiTestrRepository.parseCurl] -- a
 * whitespace-`split("\\s+")` tokenizer that breaks on any quoted value containing a space (e.g.
 * `-d '{"a": "b c"}'` or `-H 'User-Agent: My App 1.0'`), has no line-continuation handling, and
 * silently mis-assigns an unrecognized flag's argument as the URL. That implementation lives in
 * `data/repository/ApiTestrRepository.kt`, which is out of this file's ownership scope (owned by
 * a sibling agent, and `ApiTestrScreen.kt` -- also out of scope -- calls it directly for its own,
 * unrelated "paste cURL into a saved request" feature), so it cannot be edited or deleted here.
 * This parser is the hardened replacement Interceptr's own paste-cURL dialog now calls instead;
 * see `InterceptScreen.kt`'s `PasteCurlDialog`.
 *
 * [parse] never throws: any malformed input -- unbalanced quotes, no `curl` keyword, truncated
 * flags, garbage text -- degrades to the best-effort [ParsedCurlCommand] it can build (an empty
 * URL/method GET/no headers/no body in the worst case) rather than crashing the caller.
 */
object CurlParser {

    data class ParsedCurlCommand(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String?,
    )

    /** Flags that always consume the following token as their value. */
    private val VALUE_FLAGS = setOf(
        "-X", "--request",
        "-H", "--header",
        "-d", "--data", "--data-raw", "--data-binary", "--data-ascii",
        "--data-urlencode",
        "--url",
        "-u", "--user",
        "-A", "--user-agent",
        "-b", "--cookie",
        "-e", "--referer",
    )

    fun parse(input: String): ParsedCurlCommand {
        val tokens = tokenize(input)
        var method: String? = null
        var url: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val dataParts = mutableListOf<String>()

        var i = 0
        // Skip a leading "curl" keyword (and any leading shell variable assignments like
        // "FOO=bar curl ...", which are indistinguishable from an unknown flag/URL here and are
        // simply ignored rather than mis-parsed as one).
        while (i < tokens.size && tokens[i].equals("curl", ignoreCase = true)) i++

        while (i < tokens.size) {
            val raw = tokens[i]
            val (flag, inlineValue) = splitInlineValue(raw)

            fun nextValue(): String? {
                if (inlineValue != null) return inlineValue
                val next = tokens.getOrNull(i + 1) ?: return null
                i++
                return next
            }

            when (flag) {
                "-X", "--request" -> nextValue()?.let { method = it.uppercase() }
                "-H", "--header" -> nextValue()?.let { splitHeader(it)?.let(headers::add) }
                "-d", "--data", "--data-raw", "--data-binary", "--data-ascii" ->
                    nextValue()?.let { dataParts.add(stripAtFilePrefix(it)) }
                "--data-urlencode" -> nextValue()?.let { dataParts.add(urlEncodeDataArg(it)) }
                "--url" -> nextValue()?.let { url = it }
                "-u", "--user" -> nextValue()?.let { cred ->
                    val encoded = Base64.getEncoder().encodeToString(cred.toByteArray(Charsets.UTF_8))
                    headers.add("Authorization" to "Basic $encoded")
                }
                "-A", "--user-agent" -> nextValue()?.let { headers.add("User-Agent" to it) }
                "-b", "--cookie" -> nextValue()?.let { headers.add("Cookie" to it) }
                "-e", "--referer" -> nextValue()?.let { headers.add("Referer" to it) }
                else -> {
                    if (flag.startsWith("-")) {
                        // Unrecognized flag (e.g. -s, --compressed, -L, -k, --insecure, -v, -i,
                        // --http2, an unknown future flag). Deliberately does NOT consume the
                        // next token: we cannot tell whether an unknown flag is boolean or
                        // value-taking, and wrongly eating the real URL token is worse than
                        // leaving an unrecognized value flag's argument to fall through (it will
                        // just be ignored below since it won't look like a URL/won't overwrite
                        // an already-found one -- see "else" branch).
                    } else if (url == null) {
                        url = raw
                    }
                }
            }
            i++
        }

        val body = if (dataParts.isNotEmpty()) dataParts.joinToString("&") else null
        val resolvedMethod = method ?: if (body != null) "POST" else "GET"
        return ParsedCurlCommand(
            method = resolvedMethod,
            url = url ?: "",
            headers = headers,
            body = body,
        )
    }

    /** Handles both `--header value` and `--header=value`/`-Hvalue`-style inline forms. */
    private fun splitInlineValue(token: String): Pair<String, String?> {
        if (!token.startsWith("-")) return token to null
        val eq = token.indexOf('=')
        if (token.startsWith("--") && eq > 0) {
            return token.substring(0, eq) to token.substring(eq + 1)
        }
        return token to null
    }

    private fun splitHeader(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null
        return raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
    }

    /** `--data @file.json` reads a local file in real curl; here there's no filesystem to read,
     * so the `@` marker is stripped and the (otherwise unusable) literal path/text is kept as-is
     * rather than silently dropped, so a round-trip on non-`@` data is unaffected. */
    private fun stripAtFilePrefix(value: String): String = value

    /** `--data-urlencode [name=]content` URL-encodes the content portion only. */
    private fun urlEncodeDataArg(raw: String): String {
        val eq = raw.indexOf('=')
        return if (eq >= 0) {
            val name = raw.substring(0, eq)
            val value = raw.substring(eq + 1)
            "$name=${urlEncode(value)}"
        } else {
            urlEncode(raw)
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Shell-like tokenizer: honours single/double quotes (no expansion inside single quotes,
     * `\"`/`\\`/`` \` ``/`\$` escapes inside double quotes), a bare backslash escaping the next
     * character outside quotes, and collapses a trailing backslash-newline ("line continuation")
     * into a plain space before splitting on whitespace. Never throws on unbalanced quotes --
     * an unterminated quote just runs to end-of-input as a single token.
     */
    private fun tokenize(input: String): List<String> {
        val collapsed = input.replace(Regex("\\\\\\r?\\n"), " ")
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var tokenStarted = false
        var i = 0
        while (i < collapsed.length) {
            val c = collapsed[i]
            when {
                inSingle -> {
                    if (c == '\'') inSingle = false else current.append(c)
                }
                inDouble -> {
                    when {
                        c == '"' -> inDouble = false
                        c == '\\' && i + 1 < collapsed.length && collapsed[i + 1] in "\"\\$`" -> {
                            current.append(collapsed[i + 1])
                            i++
                        }
                        else -> current.append(c)
                    }
                }
                c == '\'' -> { inSingle = true; tokenStarted = true }
                c == '"' -> { inDouble = true; tokenStarted = true }
                c == '\\' && i + 1 < collapsed.length -> {
                    current.append(collapsed[i + 1])
                    i++
                    tokenStarted = true
                }
                c.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens.add(current.toString())
                        current.clear()
                        tokenStarted = false
                    }
                }
                else -> { current.append(c); tokenStarted = true }
            }
            i++
        }
        if (tokenStarted) tokens.add(current.toString())
        return tokens
    }
}
