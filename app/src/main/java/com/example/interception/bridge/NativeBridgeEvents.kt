package com.example.interception.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin mirror of the typed event protocol defined in `native/tun2proxy-bridge/src/events.rs`.
 *
 * Each [BridgeEvent] variant corresponds to a tagged JSON object emitted by the native bridge.
 * The [parse] function dispatches on the `type` field and returns the appropriate sealed subclass.
 *
 * Uses `org.json.JSONObject` for robust parsing. Under Robolectric this is the real Android
 * implementation; for pure JVM tests, org.json is on the test classpath.
 */
sealed class BridgeEvent {

    /** UDP policy verdict (mirrors `events::UdpVerdict` in Rust). */
    enum class UdpVerdict(val wireName: String) {
        ALLOW("allow"),
        TEE_AND_DROP("tee_and_drop"),
        TEE_AND_PASS("tee_and_pass");

        companion object {
            /** Returns null for unrecognized wire names — callers must not default to ALLOW. */
            fun fromWire(name: String): UdpVerdict? = entries.find { it.wireName == name }
        }
    }

    data class SessionStart(
        val sessionId: Long,
        val src: String,
        val dst: String,
        val hostname: String?,
        val isTcp: Boolean,
    ) : BridgeEvent()

    data class SessionEnd(
        val sessionId: Long,
        val txBytes: Long,
        val rxBytes: Long,
        val reason: String,
    ) : BridgeEvent()

    data class Dns(
        val name: String,
        val address: String,
        val isVirtual: Boolean,
    ) : BridgeEvent()

    data class UdpPolicy(
        val sessionId: Long,
        val src: String,
        val dst: String,
        val verdict: UdpVerdict,
    ) : BridgeEvent()

    data class PacketTee(
        val sessionId: Long,
        val packetLen: Int,
        val isOutbound: Boolean,
        /**
         * Decoded raw IP frame bytes. Defect B (S0 gaps audit): guaranteed non-null and exactly
         * [packetLen] bytes long -- any malformed/oversized/missing tee payload is rejected at
         * the whole-event level as [Unknown] instead of surfacing a half-valid [PacketTee] with
         * a null frame.
         */
        val rawFrame: ByteArray,
    ) : BridgeEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PacketTee) return false
            return sessionId == other.sessionId && packetLen == other.packetLen
                    && isOutbound == other.isOutbound && rawFrame.contentEquals(other.rawFrame)
        }
        override fun hashCode(): Int = sessionId.hashCode()
    }

    data class Error(
        val sessionId: Long?,
        val code: String,
        val message: String,
    ) : BridgeEvent()

    /** Parse failure or unrecognized event type. */
    data class Unknown(val rawJson: String, val reason: String) : BridgeEvent()

    companion object {
        /**
         * Mirrors `native/tun2proxy-bridge/src/events.rs`'s `MAX_TEE_FRAME_BYTES` exactly. A
         * `packet_tee` event whose declared `packet_len` falls outside `(0, MAX_TEE_FRAME_BYTES]`
         * is rejected as [Unknown] -- it can never be a genuine frame the native side would emit.
         */
        private const val MAX_TEE_FRAME_BYTES = 9000

        /**
         * The largest base64-encoded length any genuine `packet_tee` payload can have:
         * `ceil(MAX_TEE_FRAME_BYTES / 3) * 4` = 12000 chars (standard base64, no line wrapping,
         * matching the Rust `base64` crate's `STANDARD` engine). Checked BEFORE attempting to
         * decode `data_base64`, so an attacker-controlled oversized string can never reach the
         * decoder even if it claims a small `packet_len`.
         */
        private const val MAX_TEE_BASE64_CHARS = 12000

        /**
         * Parse a JSON event string emitted by the native bridge into a typed [BridgeEvent].
         * Returns [Unknown] for malformed JSON or unrecognized types — never throws.
         */
        fun parse(json: String): BridgeEvent {
            val obj: JSONObject
            try {
                obj = JSONObject(json)
            } catch (e: Exception) {
                return Unknown(json, "invalid JSON: ${e.message}")
            }

            return try {
                parseObject(json, obj)
            } catch (e: Exception) {
                Unknown(json, "parse error: ${e::class.simpleName}: ${e.message}")
            }
        }

        private fun parseObject(json: String, obj: JSONObject): BridgeEvent {
            val type: String = obj.safeString("type")
            if (type.isEmpty()) return Unknown(json, "missing type field")

            return when (type) {
                "session_start" -> {
                    val src = obj.safeString("src")
                    val dst = obj.safeString("dst")
                    if (src.isEmpty() || dst.isEmpty()) {
                        return Unknown(json, "session_start missing required src/dst")
                    }
                    SessionStart(
                        sessionId = obj.safeLong("session_id"),
                        src = src,
                        dst = dst,
                        hostname = if (obj.has("hostname") && !obj.isNull("hostname")) obj.safeString("hostname").ifEmpty { null } else null,
                        isTcp = obj.safeBool("is_tcp", true),
                    )
                }
                "session_end" -> SessionEnd(
                    sessionId = obj.safeLong("session_id"),
                    txBytes = obj.safeLong("tx_bytes"),
                    rxBytes = obj.safeLong("rx_bytes"),
                    reason = obj.safeString("reason"),
                )
                "dns" -> Dns(
                    name = obj.safeString("name"),
                    address = obj.safeString("address"),
                    isVirtual = obj.safeBool("is_virtual", false),
                )
                "udp_policy" -> {
                    val verdictStr = obj.safeString("verdict")
                    val verdict = UdpVerdict.fromWire(verdictStr)
                        ?: return Unknown(json, "udp_policy: unrecognized verdict '$verdictStr'")
                    UdpPolicy(
                        sessionId = obj.safeLong("session_id"),
                        src = obj.safeString("src"),
                        dst = obj.safeString("dst"),
                        verdict = verdict,
                    )
                }
                "packet_tee" -> {
                    val packetLen = obj.safeInt("packet_len")
                    if (packetLen <= 0 || packetLen > MAX_TEE_FRAME_BYTES) {
                        return Unknown(
                            json,
                            "packet_tee: packet_len $packetLen out of bounds (0, $MAX_TEE_FRAME_BYTES]"
                        )
                    }
                    if (!obj.has("data_base64") || obj.isNull("data_base64")) {
                        return Unknown(json, "packet_tee: missing data_base64")
                    }
                    val dataB64 = obj.safeString("data_base64")
                    if (dataB64.isEmpty()) {
                        return Unknown(json, "packet_tee: empty data_base64")
                    }
                    // Validate the declared length BEFORE attempting to decode -- an oversized
                    // base64 string must never reach the decoder, regardless of what packet_len
                    // claims.
                    if (dataB64.length > MAX_TEE_BASE64_CHARS) {
                        return Unknown(
                            json,
                            "packet_tee: data_base64 length ${dataB64.length} exceeds cap $MAX_TEE_BASE64_CHARS"
                        )
                    }
                    val decoded = try {
                        android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        return Unknown(json, "packet_tee: base64 decode failed: ${e.message}")
                    }
                    if (decoded.size != packetLen) {
                        return Unknown(
                            json,
                            "packet_tee: decoded length ${decoded.size} != declared packet_len $packetLen"
                        )
                    }
                    PacketTee(
                        sessionId = obj.safeLong("session_id"),
                        packetLen = packetLen,
                        isOutbound = obj.safeBool("is_outbound", true),
                        rawFrame = decoded,
                    )
                }
                "error" -> Error(
                    sessionId = if (obj.has("session_id")) obj.safeLong("session_id") else null,
                    code = obj.safeString("code"),
                    message = obj.safeString("message"),
                )
                else -> Unknown(json, "unrecognized type '$type'")
            }
        }

        /** Safe string extraction — uses get() to avoid Robolectric org.json shadow quirks. */
        private fun JSONObject.safeString(key: String): String {
            if (!has(key) || isNull(key)) return ""
            return try { get(key).toString() } catch (_: Exception) { "" }
        }

        /** Safe boolean extraction. */
        private fun JSONObject.safeBool(key: String, default: Boolean): Boolean {
            if (!has(key) || isNull(key)) return default
            return try {
                val v = get(key)
                when (v) {
                    is Boolean -> v
                    is String -> v.equals("true", ignoreCase = true)
                    else -> default
                }
            } catch (_: Exception) { default }
        }

        /** Safe long extraction — handles both Integer and Long JSON number storage. */
        private fun JSONObject.safeLong(key: String): Long {
            val v = opt(key) ?: return 0L
            return when (v) {
                is Long -> v
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }

        /** Safe int extraction — handles both Integer and Long JSON number storage. */
        private fun JSONObject.safeInt(key: String): Int {
            val v = opt(key) ?: return 0
            return when (v) {
                is Int -> v
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: 0
                else -> 0
            }
        }
    }
}

/**
 * Listener interface for bridge events. S1's `BridgeSupervisor` implements this to route
 * events to the capture engine, packet writer, and diagnostics.
 */
interface BridgeEventListener {
    fun onBridgeEvent(event: BridgeEvent)
}
