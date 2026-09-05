package com.example.interception.rules

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * S4 task 24 -- the one Moshi instance for the typed rule model, and the JSON envelopes for the
 * three sealed hierarchies ([RuleConfig] keyed on `stage`, [Modification] keyed on `type`,
 * [BodySource] keyed on `kind`). Every leaf data class uses Moshi codegen
 * (`@JsonClass(generateAdapter = true)`, already wired via `moshi-kotlin-codegen` in
 * `app/build.gradle.kts`); only the discriminators are hand-written here, so no
 * `moshi-adapters` dependency is needed.
 *
 * Decoding contract (mirrors "unknown `RuleAction` decodes to `LOG_ONLY`" in `RequestrTypeConverters`):
 *  - a `configJson` whose `stage` is missing or unknown decodes to [ObserveConfig];
 *  - a `configJson` whose `stage` is known but whose body is malformed throws [JsonDataException]
 *    -- the caller (snapshot compiler / converter) decides whether to fail open. Silently turning a
 *    half-readable REWRITE into an observe rule would hide a real config error, so that is not done here.
 *  - `matcherJson` is decoded by the codegen adapter directly; a malformed matcher throws.
 *
 * Encoding is stable and compact (no indentation, sorted-by-constructor field order from codegen),
 * so a rule re-saved without edits produces byte-identical JSON -- the DAO's `updatedAt` bump is
 * the only thing that changes.
 */
object RuleConfigJson {

    private const val STAGE_KEY = "stage"
    private const val TYPE_KEY = "type"
    private const val KIND_KEY = "kind"

    val moshi: Moshi = Moshi.Builder()
        .add(BodySourceAdapterFactory)
        .add(ModificationAdapterFactory)
        .add(RuleConfigAdapterFactory)
        .build()

    private val configAdapter: JsonAdapter<RuleConfig> = moshi.adapter(RuleConfig::class.java)
    private val matcherAdapter: JsonAdapter<RuleMatcherSpec> = moshi.adapter(RuleMatcherSpec::class.java)
    private val stringMapAdapter: JsonAdapter<Map<String, String>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))

    fun encodeConfig(config: RuleConfig): String = configAdapter.toJson(config)

    /** See the class KDoc for the unknown-stage / malformed-body contract. */
    fun decodeConfig(json: String): RuleConfig =
        configAdapter.fromJson(json) ?: throw JsonDataException("configJson decoded to null")

    fun encodeMatcher(spec: RuleMatcherSpec): String = matcherAdapter.toJson(spec)

    fun decodeMatcher(json: String): RuleMatcherSpec =
        matcherAdapter.fromJson(json) ?: throw JsonDataException("matcherJson decoded to null")

    fun encodeStringMap(map: Map<String, String>): String = stringMapAdapter.toJson(map)

    fun decodeStringMap(json: String): Map<String, String> = stringMapAdapter.fromJson(json) ?: emptyMap()

    // ── RuleConfig envelope: {"stage": "<RuleStage>", ...variant fields} ──────────────────────

    private object RuleConfigAdapterFactory : JsonAdapter.Factory {
        override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
            if (annotations.isNotEmpty() || Types.getRawType(type) != RuleConfig::class.java) return null
            return RuleConfigAdapter(moshi).nullSafe()
        }
    }

    private class RuleConfigAdapter(moshi: Moshi) : JsonAdapter<RuleConfig>() {
        private val gateway = moshi.adapter(GatewayConfig::class.java)
        private val mirror = moshi.adapter(MirrorConfig::class.java)
        private val rewrite = moshi.adapter(RewriteConfig::class.java)
        private val breakpoint = moshi.adapter(BreakpointConfig::class.java)
        private val script = moshi.adapter(ScriptConfig::class.java)
        private val mock = moshi.adapter(MockConfig::class.java)
        private val bodyCrypto = moshi.adapter(BodyCryptoConfig::class.java)
        private val throttle = moshi.adapter(ThrottleConfig::class.java)
        private val chaos = moshi.adapter(ChaosConfig::class.java)

        override fun fromJson(reader: JsonReader): RuleConfig {
            val value = reader.readJsonValue()
            val map = value as? Map<*, *> ?: throw JsonDataException("expected a JSON object for RuleConfig at ${reader.path}")
            val stage = RuleStage.fromNameOrObserve(map[STAGE_KEY] as? String)
            val body = LinkedHashMap<String, Any?>()
            for ((k, v) in map) if (k is String && k != STAGE_KEY) body[k] = v
            return when (stage) {
                RuleStage.GATEWAY -> gateway.fromJsonValue(body)
                RuleStage.MIRROR -> mirror.fromJsonValue(body)
                RuleStage.REWRITE -> rewrite.fromJsonValue(body)
                RuleStage.BREAKPOINT -> breakpoint.fromJsonValue(body)
                RuleStage.SCRIPT -> script.fromJsonValue(body)
                RuleStage.MOCK -> mock.fromJsonValue(body)
                RuleStage.BODY_CRYPTO -> bodyCrypto.fromJsonValue(body)
                RuleStage.THROTTLE -> throttle.fromJsonValue(body)
                RuleStage.CHAOS -> chaos.fromJsonValue(body)
                RuleStage.OBSERVE -> ObserveConfig
            } ?: throw JsonDataException("RuleConfig for stage $stage decoded to null")
        }

        override fun toJson(writer: JsonWriter, value: RuleConfig?) {
            requireNotNull(value)
            val fields: Map<*, *> = when (value) {
                is GatewayConfig -> gateway.toJsonValue(value)
                is MirrorConfig -> mirror.toJsonValue(value)
                is RewriteConfig -> rewrite.toJsonValue(value)
                is BreakpointConfig -> breakpoint.toJsonValue(value)
                is ScriptConfig -> script.toJsonValue(value)
                is MockConfig -> mock.toJsonValue(value)
                is BodyCryptoConfig -> bodyCrypto.toJsonValue(value)
                is ThrottleConfig -> throttle.toJsonValue(value)
                is ChaosConfig -> chaos.toJsonValue(value)
                is ObserveConfig -> emptyMap<String, Any?>()
            } as Map<*, *>
            writer.beginObject()
            writer.name(STAGE_KEY).value(value.stage.name)
            for ((k, v) in fields) {
                writer.name(k as String)
                writeAny(writer, v)
            }
            writer.endObject()
        }
    }

    // ── Modification envelope: {"type": "SetHeader", ...} ────────────────────────────────────

    private object ModificationAdapterFactory : JsonAdapter.Factory {
        override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
            if (annotations.isNotEmpty() || Types.getRawType(type) != Modification::class.java) return null
            return ModificationAdapter(moshi).nullSafe()
        }
    }

    private class ModificationAdapter(moshi: Moshi) : JsonAdapter<Modification>() {
        private val adapters: Map<String, JsonAdapter<out Modification>> = linkedMapOf(
            "SetHeader" to moshi.adapter(Modification.SetHeader::class.java),
            "RemoveHeader" to moshi.adapter(Modification.RemoveHeader::class.java),
            "RegexHeader" to moshi.adapter(Modification.RegexHeader::class.java),
            "SetQueryParam" to moshi.adapter(Modification.SetQueryParam::class.java),
            "RemoveQueryParam" to moshi.adapter(Modification.RemoveQueryParam::class.java),
            "RegexBody" to moshi.adapter(Modification.RegexBody::class.java),
            "ReplaceBody" to moshi.adapter(Modification.ReplaceBody::class.java),
            "SetStatus" to moshi.adapter(Modification.SetStatus::class.java),
        )

        private fun typeName(m: Modification): String = when (m) {
            is Modification.SetHeader -> "SetHeader"
            is Modification.RemoveHeader -> "RemoveHeader"
            is Modification.RegexHeader -> "RegexHeader"
            is Modification.SetQueryParam -> "SetQueryParam"
            is Modification.RemoveQueryParam -> "RemoveQueryParam"
            is Modification.RegexBody -> "RegexBody"
            is Modification.ReplaceBody -> "ReplaceBody"
            is Modification.SetStatus -> "SetStatus"
        }

        override fun fromJson(reader: JsonReader): Modification {
            val map = reader.readJsonValue() as? Map<*, *>
                ?: throw JsonDataException("expected a JSON object for Modification at ${reader.path}")
            val type = map[TYPE_KEY] as? String ?: throw JsonDataException("Modification is missing '$TYPE_KEY'")
            val adapter = adapters[type] ?: throw JsonDataException("unknown Modification type '$type'")
            val body = LinkedHashMap<String, Any?>()
            for ((k, v) in map) if (k is String && k != TYPE_KEY) body[k] = v
            return adapter.fromJsonValue(body) ?: throw JsonDataException("Modification '$type' decoded to null")
        }

        override fun toJson(writer: JsonWriter, value: Modification?) {
            requireNotNull(value)
            val name = typeName(value)
            @Suppress("UNCHECKED_CAST")
            val adapter = adapters.getValue(name) as JsonAdapter<Modification>
            val fields = adapter.toJsonValue(value) as Map<*, *>
            writer.beginObject()
            writer.name(TYPE_KEY).value(name)
            for ((k, v) in fields) {
                writer.name(k as String)
                writeAny(writer, v)
            }
            writer.endObject()
        }
    }

    // ── BodySource envelope: {"kind":"EMPTY"} | {"kind":"TEXT","value":..} | {"kind":"FILE","path":..}

    private object BodySourceAdapterFactory : JsonAdapter.Factory {
        override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
            if (annotations.isNotEmpty() || Types.getRawType(type) != BodySource::class.java) return null
            return BodySourceAdapter.nullSafe()
        }
    }

    private object BodySourceAdapter : JsonAdapter<BodySource>() {
        override fun fromJson(reader: JsonReader): BodySource {
            val map = reader.readJsonValue() as? Map<*, *>
                ?: throw JsonDataException("expected a JSON object for BodySource at ${reader.path}")
            return when (val kind = map[KIND_KEY] as? String) {
                "EMPTY", null -> BodySource.Empty
                "TEXT" -> BodySource.Text(map["value"] as? String ?: "")
                "FILE" -> BodySource.File(map["path"] as? String ?: throw JsonDataException("BodySource FILE is missing 'path'"))
                else -> throw JsonDataException("unknown BodySource kind '$kind'")
            }
        }

        override fun toJson(writer: JsonWriter, value: BodySource?) {
            requireNotNull(value)
            writer.beginObject()
            when (value) {
                BodySource.Empty -> writer.name(KIND_KEY).value("EMPTY")
                is BodySource.Text -> { writer.name(KIND_KEY).value("TEXT"); writer.name("value").value(value.value) }
                is BodySource.File -> { writer.name(KIND_KEY).value("FILE"); writer.name("path").value(value.path) }
            }
            writer.endObject()
        }
    }

    /** Writes a `readJsonValue()`-shaped tree (maps, lists, strings, numbers, booleans, null). */
    private fun writeAny(writer: JsonWriter, v: Any?) {
        when (v) {
            null -> writer.nullValue()
            is String -> writer.value(v)
            is Boolean -> writer.value(v)
            is Number -> {
                // Codegen emits Long/Int/Double boxes; readJsonValue emits Double. Keep integers integral.
                val d = v.toDouble()
                if (v is Double && d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) writer.value(d.toLong()) else writer.value(v)
            }
            is Map<*, *> -> {
                writer.beginObject()
                for ((k, value) in v) { writer.name(k as String); writeAny(writer, value) }
                writer.endObject()
            }
            is List<*> -> {
                writer.beginArray()
                for (item in v) writeAny(writer, item)
                writer.endArray()
            }
            else -> writer.value(v.toString())
        }
    }
}
