package com.example.data.local

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Converters for the non-primitive columns used by the interception schema.
 * Header maps and package lists are stored as JSON.
 */
class RequestrTypeConverters {

    @TypeConverter
    fun stringMapToJson(value: Map<String, String>?): String =
        if (value.isNullOrEmpty()) "{}" else mapAdapter.toJson(value)

    @TypeConverter
    fun jsonToStringMap(value: String?): Map<String, String> =
        if (value.isNullOrBlank() || value == "{}") emptyMap()
        else runCatching { mapAdapter.fromJson(value) }.getOrNull() ?: emptyMap()

    @TypeConverter
    fun stringListToJson(value: List<String>?): String =
        if (value.isNullOrEmpty()) "[]" else listAdapter.toJson(value)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        if (value.isNullOrBlank() || value == "[]") emptyList()
        else runCatching { listAdapter.fromJson(value) }.getOrNull() ?: emptyList()

    @TypeConverter
    fun captureTierToName(value: CaptureTier?): String = (value ?: CaptureTier.NONE).name

    @TypeConverter
    fun nameToCaptureTier(value: String?): CaptureTier =
        runCatching { CaptureTier.valueOf(value ?: "") }.getOrNull() ?: CaptureTier.NONE

    @TypeConverter
    fun ruleActionToName(value: RuleAction?): String = (value ?: RuleAction.LOG_ONLY).name

    @TypeConverter
    fun nameToRuleAction(value: String?): RuleAction =
        runCatching { RuleAction.valueOf(value ?: "") }.getOrNull() ?: RuleAction.LOG_ONLY

    private companion object {
        val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

        val mapAdapter = moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )

        val listAdapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )
    }
}
