package com.example.flow

import org.junit.Assert.*
import org.junit.Test

class ConditionEvaluatorTest {

    @Test
    fun `status equals 200 true`() {
        val input = """{"status":200,"body":"ok"}"""
        assertTrue(ConditionEvaluator.evaluate("status == 200", input))
    }

    @Test
    fun `status equals 200 false`() {
        val input = """{"status":404,"body":"not found"}"""
        assertFalse(ConditionEvaluator.evaluate("status == 200", input))
    }

    @Test
    fun `body contains token`() {
        val input = """{"token":"abc123"}"""
        assertTrue(ConditionEvaluator.evaluate("body contains abc123", input))
        assertFalse(ConditionEvaluator.evaluate("body contains zzz", input))
    }

    @Test
    fun `notEmpty on empty object is false`() {
        assertFalse(ConditionEvaluator.evaluate("notEmpty", "{}"))
    }

    @Test
    fun `notEmpty on real content is true`() {
        assertTrue(ConditionEvaluator.evaluate("notEmpty", """{"data":"hello"}"""))
    }

    @Test
    fun `empty on empty object is true`() {
        assertTrue(ConditionEvaluator.evaluate("empty", "{}"))
    }

    @Test
    fun `empty on real content is false`() {
        assertFalse(ConditionEvaluator.evaluate("empty", """{"data":"hello"}"""))
    }

    @Test
    fun `json key left side resolves via JsonFieldExtractor`() {
        val input = """{"userId":"42"}"""
        assertTrue(ConditionEvaluator.evaluate("userId == 42", input))
        assertFalse(ConditionEvaluator.evaluate("userId == 99", input))
    }

    @Test
    fun `matches with valid regex`() {
        val input = """{"email":"a@b.com"}"""
        assertTrue(ConditionEvaluator.evaluate("email matches .+@.+\\..+", input))
    }

    @Test
    fun `matches with invalid regex returns false and reports error`() {
        val input = "anything"
        assertFalse(ConditionEvaluator.evaluate("body matches [", input))
        assertNotNull(ConditionEvaluator.diagnose("body matches [", input))
    }

    @Test
    fun `unparseable config returns false`() {
        assertFalse(ConditionEvaluator.evaluate("this is gibberish with no operator", "anything"))
        assertNotNull(ConditionEvaluator.diagnose("this is gibberish with no operator", "anything"))
    }

    @Test
    fun `blank config returns false with diagnostic`() {
        assertFalse(ConditionEvaluator.evaluate("", "anything"))
        assertNotNull(ConditionEvaluator.diagnose("", "anything"))
    }

    @Test
    fun `notContains operator`() {
        val input = "hello world"
        assertTrue(ConditionEvaluator.evaluate("body notContains goodbye", input))
        assertFalse(ConditionEvaluator.evaluate("body notContains hello", input))
    }

    @Test
    fun `not equals operator`() {
        val input = """{"status":500}"""
        assertTrue(ConditionEvaluator.evaluate("status != 200", input))
        assertFalse(ConditionEvaluator.evaluate("status != 500", input))
    }

    @Test
    fun `valid parse has no diagnostic`() {
        assertNull(ConditionEvaluator.diagnose("status == 200", """{"status":200}"""))
    }
}
