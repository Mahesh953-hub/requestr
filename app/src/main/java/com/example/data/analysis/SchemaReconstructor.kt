package com.example.data.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema Reconstruction — infers JSON Schema from response body samples.
 *
 * Feeds one or more JSON response bodies, and it produces:
 * 1. JSON Schema (draft-07 subset)
 * 2. TypeScript interface
 * 3. Kotlin data class
 * 4. Python dataclass
 *
 * This is the backend for #46 (Schema Reconstruction).
 */
object SchemaReconstructor {

    // ── JSON Schema ─────────────────────────────────────────────────

    /** Infers a JSON Schema from a JSON string. */
    fun inferJsonSchema(json: String): String {
        val parsed = parseAny(json) ?: return "{}"
        val schema = inferSchema(parsed)
        return JSONObject(schema.toString()).toString(2)
    }

    private fun inferSchema(value: Any?): JSONObject {
        val schema = JSONObject()
        when (value) {
            is JSONObject -> {
                schema.put("type", "object")
                val props = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    props.put(key, inferSchema(value.get(key)))
                }
                schema.put("properties", props)
                if (value.length() > 0) {
                    schema.put("required", JSONArray(value.keys().asSequence().toList()))
                }
            }
            is JSONArray -> {
                schema.put("type", "array")
                if (value.length() > 0) {
                    schema.put("items", inferSchema(value.get(0)))
                }
            }
            is String -> schema.put("type", "string")
            is Int, is Long -> schema.put("type", "integer")
            is Double, is Float -> schema.put("type", "number")
            is Boolean -> schema.put("type", "boolean")
            null -> schema.put("type", "null")
            else -> schema.put("type", "string")
        }
        return schema
    }

    // ── TypeScript ──────────────────────────────────────────────────

    /** Generates a TypeScript interface from a JSON response body. */
    fun inferTypeScript(json: String, interfaceName: String = "ApiResponse"): String {
        val parsed = parseAny(json) ?: return "interface $interfaceName {}"
        val sb = StringBuilder()
        sb.appendLine("interface $interfaceName {")
        if (parsed is JSONObject) {
            generateTypeScriptFields(parsed, sb, 1)
        }
        sb.appendLine("}")
        return sb.toString()
    }

    private fun generateTypeScriptFields(obj: JSONObject, sb: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key)
            val tsType = when (value) {
                is JSONObject -> "object" // Could recurse with a named interface
                is JSONArray -> {
                    if (value.length() > 0) {
                        val first = value.get(0)
                        when (first) {
                            is String -> "string[]"
                            is Int, is Long -> "number[]"
                            is Double, is Float -> "number[]"
                            is Boolean -> "boolean[]"
                            is JSONObject -> "object[]"
                            else -> "any[]"
                        }
                    } else "any[]"
                }
                is String -> "string"
                is Int, is Long -> "number"
                is Double, is Float -> "number"
                is Boolean -> "boolean"
                else -> "any"
            }
            sb.appendLine("$pad$key: $tsType;")
        }
    }

    // ── Kotlin data class ──────────────────────────────────────────

    /** Generates a Kotlin data class from a JSON response body. */
    fun inferKotlin(json: String, className: String = "ApiResponse"): String {
        val parsed = parseAny(json) ?: return "data class $className()"
        val sb = StringBuilder()
        sb.appendLine("data class $className(")
        if (parsed is JSONObject) {
            val keys = objKeys(parsed)
            keys.forEachIndexed { idx, key ->
                val value = parsed.get(key)
                val ktType = when (value) {
                    is JSONObject -> "Map<String, Any>"
                    is JSONArray -> {
                        if (value.length() > 0) {
                            when (value.get(0)) {
                                is String -> "List<String>"
                                is Int, is Long -> "List<Long>"
                                is Double, is Float -> "List<Double>"
                                is Boolean -> "List<Boolean>"
                                else -> "List<Any>"
                            }
                        } else "List<Any>"
                    }
                    is String -> "String"
                    is Int -> "Int"
                    is Long -> "Long"
                    is Double, is Float -> "Double"
                    is Boolean -> "Boolean"
                    else -> "Any"
                }
                val comma = if (idx < keys.size - 1) "," else ""
                sb.appendLine("    val ${key.toCamelCase()}: $ktType,$comma")
            }
        }
        sb.appendLine(")")
        return sb.toString()
    }

    // ── Python dataclass ───────────────────────────────────────────

    /** Generates a Python dataclass from a JSON response body. */
    fun inferPython(json: String, className: String = "ApiResponse"): String {
        val parsed = parseAny(json) ?: return "@dataclass\nclass $className:\n    pass"
        val sb = StringBuilder()
        sb.appendLine("from dataclasses import dataclass")
        sb.appendLine("from typing import Optional, List, Dict, Any")
        sb.appendLine()
        sb.appendLine("@dataclass")
        sb.appendLine("class $className:")
        if (parsed is JSONObject) {
            val keys = objKeys(parsed)
            if (keys.isEmpty()) {
                sb.appendLine("    pass")
            } else {
                keys.forEach { key ->
                    val value = parsed.get(key)
                    val pyType = when (value) {
                        is JSONObject -> "Dict[str, Any]"
                        is JSONArray -> "List[Any]"
                        is String -> "str"
                        is Int -> "int"
                        is Long -> "int"
                        is Double, is Float -> "float"
                        is Boolean -> "bool"
                        else -> "Any"
                    }
                    sb.appendLine("    ${key.toSnakeCase()}: $pyType")
                }
            }
        }
        return sb.toString()
    }

    // ── Internal helpers ───────────────────────────────────────────

    private fun parseAny(json: String): Any? {
        return try {
            when {
                json.trimStart().startsWith("{") -> JSONObject(json)
                json.trimStart().startsWith("[") -> JSONArray(json)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun objKeys(obj: JSONObject): List<String> {
        val keys = mutableListOf<String>()
        val iter = obj.keys()
        while (iter.hasNext()) keys.add(iter.next())
        return keys
    }

    private fun String.toCamelCase(): String {
        return split("_", "-", " ").mapIndexed { i, part ->
            if (i == 0) part.lowercase()
            else part.lowercase().replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    private fun String.toSnakeCase(): String {
        return replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            .replace("-", "_")
    }
}
