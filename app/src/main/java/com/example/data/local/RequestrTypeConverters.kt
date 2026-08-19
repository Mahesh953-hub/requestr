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

    // -- Saved API Testr requests (A4) --------------------------------------------------------

    @TypeConverter
    fun savedKeyValueListToJson(value: List<SavedKeyValue>?): String =
        if (value.isNullOrEmpty()) "[]" else savedKeyValueListAdapter.toJson(value)

    @TypeConverter
    fun jsonToSavedKeyValueList(value: String?): List<SavedKeyValue> =
        if (value.isNullOrBlank() || value == "[]") emptyList()
        else runCatching { savedKeyValueListAdapter.fromJson(value) }.getOrNull() ?: emptyList()

    // -- Request chaining extraction rules (C2) -----------------------------------------------

    @TypeConverter
    fun chainExtractionListToJson(value: List<SavedChainExtractionRule>?): String =
        if (value.isNullOrEmpty()) "[]" else chainExtractionListAdapter.toJson(value)

    @TypeConverter
    fun jsonToChainExtractionList(value: String?): List<SavedChainExtractionRule> =
        if (value.isNullOrBlank() || value == "[]") emptyList()
        else runCatching { chainExtractionListAdapter.fromJson(value) }.getOrNull() ?: emptyList()

    // -- Test assertions (C3) ------------------------------------------------------------------

    @TypeConverter
    fun assertionListToJson(value: List<SavedAssertion>?): String =
        if (value.isNullOrEmpty()) "[]" else assertionListAdapter.toJson(value)

    @TypeConverter
    fun jsonToAssertionList(value: String?): List<SavedAssertion> =
        if (value.isNullOrBlank() || value == "[]") emptyList()
        else runCatching { assertionListAdapter.fromJson(value) }.getOrNull() ?: emptyList()

    private companion object {
        val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

        val mapAdapter = moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )

        val listAdapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )

        val savedKeyValueListAdapter = moshi.adapter<List<SavedKeyValue>>(
            Types.newParameterizedType(List::class.java, SavedKeyValue::class.java)
        )

        val chainExtractionListAdapter = moshi.adapter<List<SavedChainExtractionRule>>(
            Types.newParameterizedType(List::class.java, SavedChainExtractionRule::class.java)
        )

        val assertionListAdapter = moshi.adapter<List<SavedAssertion>>(
            Types.newParameterizedType(List::class.java, SavedAssertion::class.java)
        )
    }
}
