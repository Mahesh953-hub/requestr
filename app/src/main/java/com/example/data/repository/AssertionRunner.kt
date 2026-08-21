package com.example.data.repository

import com.example.data.models.ApiResponseData
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionResult
import com.example.data.models.AssertionTarget
import com.example.flow.JsonFieldExtractor

/**
 * v3.3 Part C — C3 (Test assertions / test runner): evaluates a saved request's [Assertion]s
 * against a real [ApiResponseData] returned by [ApiTestrRepository.executeRequest]. No network
 * or DB access here — pure evaluation so it is trivially reusable from both a single-request
 * "Run Tests" tap (`ApiTestrScreen`) and the folder-level test runner
 * (`SavedApisScreen`/`ApiCollectionsRepository.runCollectionTests`).
 *
 * [AssertionTarget.BODY_JSON_FIELD] reuses [JsonFieldExtractor] — the same top-level-key
 * extractor the Flow Transform node and C2's request-chaining extraction already share — rather
 * than adding a third, slightly-different JSON-field lookup.
 */
object AssertionRunner {

    fun run(assertions: List<Assertion>, response: ApiResponseData): List<AssertionResult> =
        assertions.filter { it.enabled }.map { evaluate(it, response) }

    private fun evaluate(assertion: Assertion, response: ApiResponseData): AssertionResult {
        val actual: String? = when (assertion.target) {
            AssertionTarget.STATUS_CODE -> response.statusCode.toString()
            AssertionTarget.RESPONSE_TIME_MS -> response.latencyMs.toString()
            AssertionTarget.HEADER_VALUE -> {
                val key = assertion.targetKey.trim()
                response.headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            }
            AssertionTarget.BODY_JSON_FIELD -> JsonFieldExtractor.extract(response.body, assertion.targetKey)
        }

        if (actual == null) {
            val what = when (assertion.target) {
                AssertionTarget.HEADER_VALUE -> "header \"${assertion.targetKey}\""
                AssertionTarget.BODY_JSON_FIELD -> "JSON field \"${assertion.targetKey}\""
                else -> "value"
            }
            return AssertionResult(
                assertion = assertion,
                passed = false,
                actualValue = "(not present)",
                message = "$what was not found in the response",
            )
        }

        val passed = compare(actual, assertion.operator, assertion.expectedValue)
        val message = if (passed) {
            "Passed"
        } else {
            "Expected ${assertion.operator.name.lowercase().replace('_', ' ')} \"${assertion.expectedValue}\", got \"$actual\""
        }
        return AssertionResult(assertion = assertion, passed = passed, actualValue = actual, message = message)
    }

    private fun compare(actual: String, operator: AssertionOperator, expected: String): Boolean {
        return when (operator) {
            AssertionOperator.EQUALS -> actual == expected
            AssertionOperator.CONTAINS -> actual.contains(expected)
            AssertionOperator.GREATER_THAN ->
                actual.toDoubleOrNull()?.let { a -> expected.toDoubleOrNull()?.let { e -> a > e } } ?: false
            AssertionOperator.LESS_THAN ->
                actual.toDoubleOrNull()?.let { a -> expected.toDoubleOrNull()?.let { e -> a < e } } ?: false
            AssertionOperator.MATCHES_REGEX ->
                runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
        }
    }
}
