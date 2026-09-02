package com.example.data.export

import java.util.Base64

/**
 * Parses a pasted `curl ...` command back into a request (the reverse of [CodeExporter]'s
 * `ExportFormat.CURL` export), for Master Plan §3 LOW-ADD "copy/paste cURL for live traffic" (C11).
 *
 * This is the single cURL parser in the app: both Interceptr's `PasteCurlDialog` (see
 * `InterceptScreen.kt`) and API Testr's paste-cURL
 * ([com.example.data.repository.ApiTestrRepository.parseCurl], which delegates here) go through
 * it. It replaced an earlier whitespace-`split("\\s+")` tokenizer that broke on any quoted value
 * containing a space (e.g. `-d '{"a": "b c"}'` or `-H 'User-Agent: My App 1.0'`), had no
 * line-continuation handling, and silently mis-assigned an unrecognized flag's argument as the URL.
 *
 * [parse] never throws: any malformed input -- unbalanced quotes, no `curl` keyword, truncated
 * flags, garbage text -- degrades to the best-effort [ParsedCurlCommand] it can build (an empty
 * URL/method GET/no headers/no body in the worst case) rather than crashing the caller. Input it
 * cannot interpret at all is reported instead of guessed at: see
 * [ParsedCurlCommand.unsupportedFlag].
 */
object CurlParser {

    data class ParsedCurlCommand(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        /**
         * `null` when the command sends no body -- including a `-G`/`--get` command, whose `-d`
         * payload has been folded into [url]'s query string instead.
         */
        val body: String?,
        /**
         * The first short flag whose meaning could not be determined, or `null` when every token
         * was understood.
         *
         * Short flags can be glued to their value (`-XPOST`) and bundled (`-sS`), so an
         * unrecognized letter is genuinely ambiguous: `-Wfoo` could be `-W foo` or the bundle
         * `-W -f -o -o`. Rather than pick one, [parse] records the flag here and leaves the rest
         * of the command parsed on a best-effort basis; callers refuse the import and name the
         * flag -- see [com.example.data.repository.ApiTestrRepository.parseCurl].
         */
        val unsupportedFlag: String? = null,
    )

    /**
     * Every flag known to consume the following token as its value.
     *
     * The `when (flag)` in [parse] maps the subset it understands (`-X`, `-H`, `-d`, ...); this
     * table exists for the rest. Without it, `curl --proxy localhost:8080 https://example.com`
     * parses `localhost:8080` as the request URL, because an unrecognized flag deliberately does
     * not consume its argument and the orphaned value then looks like a bare URL token.
     *
     * [splitFlag] also reads it to tell a glued short-form value (`-XPOST`) from a bundle of
     * value-less short flags (`-sS`), so every single-letter curl flag that takes a value must be
     * listed here even when it is not mapped below.
     *
     * An unlisted *long* value-taking flag can still leak its argument as the URL (long flags are
     * open-ended and cannot be enumerated), which is why callers must reject a result whose URL is
     * not absolute -- see [com.example.data.repository.ApiTestrRepository.parseCurl].
     */
    private val VALUE_FLAGS = setOf(
        // Mapped below.
        "-X", "--request",
        "-H", "--header",
        "-d", "--data", "--data-raw", "--data-binary", "--data-ascii",
        "--data-urlencode",
        "--url",
        "-u", "--user",
        "-A", "--user-agent",
        "-b", "--cookie",
        "-e", "--referer",
        // Not mapped: the value is consumed and discarded so it cannot be mistaken for the URL.
        "-x", "--proxy", "-U", "--proxy-user", "--noproxy", "--preproxy",
        "-o", "--output", "-D", "--dump-header", "--trace", "--trace-ascii",
        "-m", "--max-time", "--connect-timeout", "--expect100-timeout",
        "--retry", "--retry-delay", "--retry-max-time",
        "--cacert", "--capath", "-E", "--cert", "--cert-type", "--key", "--key-type",
        "--pass", "--ciphers", "--tls13-ciphers", "--pinnedpubkey", "--pubkey",
        "-F", "--form", "--form-string", "-T", "--upload-file",
        "--resolve", "--interface", "--dns-servers", "--local-port", "--unix-socket",
        "-w", "--write-out", "--limit-rate", "--max-filesize", "--max-redirs",
        "-r", "--range", "-C", "--continue-at", "-z", "--time-cond",
        "--oauth2-bearer", "--aws-sigv4", "--proto", "--socks4", "--socks5",
        "--socks5-hostname", "--service-name", "--url-query",
        "-c", "--cookie-jar", "--netrc-file", "--libcurl", "--krb", "--engine",
        "-Y", "--speed-limit", "-y", "--speed-time", "--happy-eyeballs-timeout-ms",
        "-K", "--config", "-P", "--ftp-port", "-Q", "--quote", "-t", "--telnet-option",
    )

    /**
     * Every curl short flag that takes no value, and so may appear anywhere in a bundle.
     *
     * Together with the single-letter entries in [VALUE_FLAGS] this covers curl's complete
     * (and effectively frozen) short-option set, which is what lets [splitFlag] treat a letter in
     * neither table as an error worth reporting instead of guessing at it.
     */
    private val BOOLEAN_SHORT_FLAGS = setOf(
        "-a", "-B", "-f", "-g", "-G", "-h", "-i", "-I", "-j", "-J", "-k", "-l", "-L",
        "-M", "-n", "-N", "-O", "-p", "-q", "-R", "-s", "-S", "-v", "-V", "-Z", "-#",
        "-0", "-1", "-2", "-3", "-4", "-6", "-:",
    )

    fun parse(input: String): ParsedCurlCommand {
        val tokens = tokenize(input)
        var method: String? = null
        var url: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val dataParts = mutableListOf<String>()
        var unsupportedFlag: String? = null
        var dataAsQuery = false

        var i = 0
        // Skip a leading "curl" keyword (and any leading shell variable assignments like
        // "FOO=bar curl ...", which are indistinguishable from an unknown flag/URL here and are
        // simply ignored rather than mis-parsed as one).
        while (i < tokens.size && tokens[i].equals("curl", ignoreCase = true)) i++

        while (i < tokens.size) {
            val raw = tokens[i]
            val (flag, inlineValue, badFlag, bundledFlags) = splitFlag(raw)
            unsupportedFlag = unsupportedFlag ?: badFlag
            // -G/--get modifies how the -d payloads are used rather than contributing anything
            // itself, and it may appear anywhere -- including bundled (-sG), which is why the
            // short form is matched against the bundle rather than the effective flag.
            if (flag == "--get" || "-G" in bundledFlags) dataAsQuery = true

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
                    // Real curl treats `--data @file.json` as "read this file"; there is no
                    // filesystem to read here, so the argument is kept verbatim (including any
                    // leading `@`) rather than dropped, leaving non-`@` data round-trips exact.
                    nextValue()?.let { dataParts.add(it) }
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
                    if (flag in VALUE_FLAGS) {
                        // A value-taking flag this parser does not map (--proxy, -o, --max-time,
                        // ...). Consume and discard its argument: leaving it to fall through is
                        // what turned `--proxy localhost:8080 https://example.com` into a request
                        // aimed at "localhost:8080".
                        nextValue()
                    } else if (flag.startsWith("-")) {
                        // Value-less flag: a long boolean (--compressed, --insecure, an unknown
                        // future flag) or a bundle of short booleans (-sSLk). Nothing to consume.
                        // An unlisted long value-taking flag lands here and can still leak its
                        // argument as the URL, which the caller's absolute-URL check catches.
                    } else if (url == null) {
                        url = raw
                    }
                }
            }
            i++
        }

        // `-G` turns the accumulated payload into query parameters on a bodiless request. The
        // parts are already in the form curl would send them: `-d` verbatim, `--data-urlencode`
        // percent-encoded, joined with `&`.
        val data = if (dataParts.isNotEmpty()) dataParts.joinToString("&") else null
        val query = if (dataAsQuery) data else null
        val body = if (dataAsQuery) null else data
        return ParsedCurlCommand(
            method = method ?: if (body != null) "POST" else "GET",
            url = (url ?: "").let { if (query == null) it else appendQuery(it, query) },
            headers = headers,
            body = body,
            unsupportedFlag = unsupportedFlag,
        )
    }

    /**
     * Adds `-G`'s payload to [url]'s query string.
     *
     * curl merges with any query the URL already carries rather than replacing it, so the
     * separator depends on what is already there: `?` for a URL with no query, `&` for one that
     * has, and nothing at all when the URL already ends in a separator (`.../x?` or `.../x?a=1&`),
     * which would otherwise produce an empty parameter.
     */
    private fun appendQuery(url: String, query: String): String {
        val separator = when {
            url.endsWith("?") || url.endsWith("&") -> ""
            url.contains("?") -> "&"
            else -> "?"
        }
        return "$url$separator$query"
    }

    /** [splitFlag]'s decomposition of one token: see [ParsedCurlCommand.unsupportedFlag]. */
    private data class FlagToken(
        val flag: String,
        val inlineValue: String?,
        val unsupportedFlag: String?,
        /**
         * The value-less short flags in the token, whether or not it is a bundle (`-sSG` gives
         * `-s`, `-S`, `-G`; a lone `-G` gives `-G`). Only `-G` is acted on; the rest are no-ops
         * here but are cheaper to carry than to distinguish.
         */
        val bundledFlags: List<String>,
    )

    /**
     * Splits one token into the flag [parse] should act on and any value glued to it, so that the
     * spellings curl accepts interchangeably parse identically:
     *
     *  - `--header=value` / `--header value` (long form, value after `=` or in the next token);
     *  - `-XPOST` / `-X POST` (short form, value glued or in the next token) -- likewise
     *    `-H'A: b'` and `-d'{"a": 1}'`, whose quotes the tokenizer has already removed;
     *  - `-sS` (a bundle of value-less short flags), optionally ending in a value-taking flag:
     *    `-sSXPOST` and `-sSX POST` both mean `-s -S -X POST`.
     *
     * A bundle's value-less flags are returned in [FlagToken.bundledFlags] rather than dropped,
     * because one of them changes the meaning of the command: `-G` moves the `-d` payload into the
     * query string, and `curl -sG -d a=b url` must not import as a POST. A letter in neither
     * [VALUE_FLAGS] nor [BOOLEAN_SHORT_FLAGS] cannot be classified as either, so it is returned in
     * [FlagToken.unsupportedFlag] for the caller to refuse.
     */
    private fun splitFlag(token: String): FlagToken {
        if (!token.startsWith("-") || token.length < 2) {
            return FlagToken(token, null, null, emptyList())
        }
        if (token.startsWith("--")) {
            val eq = token.indexOf('=')
            return if (eq > 0) {
                FlagToken(token.substring(0, eq), token.substring(eq + 1), null, emptyList())
            } else {
                FlagToken(token, null, null, emptyList())
            }
        }
        var unsupported: String? = null
        val bundled = mutableListOf<String>()
        for (offset in 1 until token.length) {
            val short = "-${token[offset]}"
            when {
                // A value-taking flag ends the bundle: everything after it is its value.
                short in VALUE_FLAGS -> return FlagToken(
                    flag = short,
                    inlineValue = token.substring(offset + 1).ifEmpty { null },
                    unsupportedFlag = unsupported,
                    bundledFlags = bundled,
                )
                short in BOOLEAN_SHORT_FLAGS -> bundled.add(short)
                else -> unsupported = unsupported ?: short
            }
        }
        // An all-boolean bundle. Handing the whole token back keeps it in parse's value-less
        // branch, which discards it.
        return FlagToken(token, null, unsupported, bundled)
    }

    private fun splitHeader(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null
        return raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
    }

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
