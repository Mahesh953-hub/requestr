package com.example.flow

/**
 * Task 3 — real condition evaluation for CONDITION flow nodes.
 *
 * Replaces the previous `executeConditionNode` substring-guesswork (which defaulted an
 * unrecognized config to `"true"`, silently sending every unparseable condition down the
 * "true" branch) with a small parser over `<left> <op> <right>` expressions plus two unary
 * forms (`empty` / `notEmpty`).
 *
 * Left-hand side resolves to one of:
 *  - `status`   — the numeric HTTP status extracted from [input] (via the `status` JSON key,
 *                 same extraction [JsonFieldExtractor] already uses elsewhere).
 *  - `body`     — the whole [input] string, unmodified.
 *  - any other token — treated as a JSON key, resolved via [JsonFieldExtractor.extractByKey]
 *                 against [input]. If the key can't be found, the left side resolves to "".
 *
 * Supported operators: `==`, `!=`, `contains`, `notContains`, `matches` (regex against the
 * right-hand side). Unary: `empty` / `notEmpty` (single token, no right-hand side, checks the
 * left operand — or `body` if no left operand is given).
 *
 * An unparseable config returns `false` from [evaluate] — never silently `true` — and
 * [diagnose] reports *why*, so callers (see [FlowExecutor]) can surface a real error instead of
 * a condition that quietly always takes the same branch.
 */
object ConditionEvaluator {

    private val BINARY_OPS = listOf("==", "!=", "notContains", "contains", "matches")

    /** Evaluates [config] against [input]. Unparseable config resolves to `false`. */
    fun evaluate(config: String, input: String): Boolean = parse(config, input).first

    /**
     * Returns a human-readable reason [config] could not be parsed, or `null` if it parsed
     * successfully (including regex-invalid `matches` — that's reported too).
     */
    fun diagnose(config: String, input: String): String? = parse(config, input).second

    /** Returns (result, errorOrNull). */
    private fun parse(config: String, input: String): Pair<Boolean, String?> {
        val trimmed = config.trim()
        if (trimmed.isEmpty()) {
            return false to "Condition config is empty"
        }

        val lower = trimmed.lowercase()

        // Unary forms: "empty" / "notEmpty", optionally prefixed with a left operand
        // (e.g. "body empty", "token notEmpty"). Bare "empty"/"notEmpty" checks the whole input.
        if (lower == "notempty" || (lower.endsWith(" notempty"))) {
            val left = trimmed.substringBeforeLast(" ", missingDelimiterValue = "").trim()
            val value = resolveLeft(left.ifEmpty { "body" }, input)
            return (value.isNotBlank() && value != "{}") to null
        }
        if (lower == "empty" || lower.endsWith(" empty")) {
            val left = trimmed.substringBeforeLast(" ", missingDelimiterValue = "").trim()
            val value = resolveLeft(left.ifEmpty { "body" }, input)
            return (value.isBlank() || value == "{}") to null
        }

        // Binary forms: "<left> <op> <right>"
        for (op in BINARY_OPS) {
            val idx = findOperatorIndex(trimmed, op)
            if (idx >= 0) {
                val leftToken = trimmed.substring(0, idx).trim()
                val rightToken = trimmed.substring(idx + op.length).trim()
                if (leftToken.isEmpty() || rightToken.isEmpty()) continue
                val leftValue = resolveLeft(leftToken, input)
                val rightValue = stripQuotes(rightToken)
                return evalOp(op, leftValue, rightValue)
            }
        }

        return false to "Could not parse condition: \"$config\""
    }

    private fun evalOp(op: String, left: String, right: String): Pair<Boolean, String?> = when (op) {
        "==" -> (left.trim().equals(right, ignoreCase = true)) to null
        "!=" -> (!left.trim().equals(right, ignoreCase = true)) to null
        "contains" -> (left.contains(right, ignoreCase = true)) to null
        "notContains" -> (!left.contains(right, ignoreCase = true)) to null
        "matches" -> {
            val regex = runCatching { Regex(right) }.getOrNull()
            if (regex == null) {
                false to "Invalid regex in condition: \"$right\""
            } else {
                regex.containsMatchIn(left) to null
            }
        }
        else -> false to "Unknown operator: $op"
    }

    /** Finds the index of [op] as a standalone token (surrounded by whitespace on both sides). */
    private fun findOperatorIndex(expr: String, op: String): Int {
        val regex = Regex("(?<=\\s)${Regex.escape(op)}(?=\\s)")
        val match = regex.find(expr) ?: return -1
        return match.range.first
    }

    private fun stripQuotes(token: String): String =
        token.trim().removeSurrounding("\"").removeSurrounding("'")

    /** Resolves a left-hand operand token against [input]. */
    private fun resolveLeft(token: String, input: String): String {
        return when (token.lowercase()) {
            "body" -> input
            // Dedicated pattern instead of JsonFieldExtractor.extractByKey: that shared
            // extractor's fallback (non-quoted-value) regex is `\S+`, which is greedy enough to
            // swallow trailing JSON punctuation for an unquoted numeric field (e.g. it returns
            // `200,"body":"ok"}` from `{"status":200,"body":"ok"}` instead of `200`), so it must
            // not be used here for a numeric status code.
            "status" -> Regex("\"status\"\\s*:\\s*\"?(\\d{3})\"?").find(input)?.groupValues?.get(1)
                ?: Regex("\\bstatus\\D{0,3}(\\d{3})\\b").find(input)?.groupValues?.get(1)
                ?: ""
            else -> JsonFieldExtractor.extractByKey(input, stripQuotes(token)) ?: ""
        }
    }
}
