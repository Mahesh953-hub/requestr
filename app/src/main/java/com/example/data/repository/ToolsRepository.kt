package com.example.data.repository

import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

enum class ToolCategory(val displayName: String, val icon: String) {
    DECODERS("Decoders", "🔓"),
    CIPHERS("Ciphers & Crypt", "🛡️"),
    HASHES("Hashes & Checksums", "🔢"),
    FORMATTERS("Formatters", "✨"),
    NETWORK("Network & IP", "🌐"),
    GENERATORS("Generators", "🎲"),
    CUSTOM("Custom", "🔧")
}

data class ToolItem(
    val id: String,
    val title: String,
    val description: String,
    val category: ToolCategory,
    val icon: ImageVector = Icons.Default.Build
)

object ToolsRepository {

    // Icon shortcuts (Fix 07)
    private val Ic = Icons.Default

    private val builtInTools = listOf(
        ToolItem("jwt-decoder",     "JWT Token Decoder",        "Decode Header, Payload claims and inspect signatures", ToolCategory.DECODERS,   Ic.Key),
        ToolItem("base64",          "Base64 Encoder / Decoder", "RFC 4648 Base64 text & binary converter",              ToolCategory.DECODERS,   Ic.DataObject),
        ToolItem("url-encoder",     "URL / URI Percent Encoder","Encode & decode query strings & path parameters",       ToolCategory.DECODERS,   Ic.Link),
        ToolItem("hex-converter",   "Hex ↔ String Converter",   "Convert ASCII/UTF-8 strings to Hex bytes",              ToolCategory.DECODERS,   Ic.Tag),
        ToolItem("html-entity",     "HTML Entity Escape",       "Escape and unescape HTML special entities",             ToolCategory.DECODERS,   Ic.Code),
        ToolItem("morse-code",      "Morse Code Converter",     "Encode text to Morse code and decode back",             ToolCategory.DECODERS,   Ic.Translate),
        ToolItem("aes-cipher",      "AES Encrypt / Decrypt",    "AES-CBC/GCM with custom key, IV & padding",             ToolCategory.CIPHERS,    Ic.Lock),
        ToolItem("xor-cipher",      "XOR Stream Cipher",        "Fast bitwise XOR encryption with key",                  ToolCategory.CIPHERS,    Ic.SwapHoriz),
        ToolItem("sha256",          "SHA-256 Hash",             "Cryptographic 256-bit secure hash algorithm",           ToolCategory.HASHES,     Ic.Fingerprint),
        ToolItem("sha1",            "SHA-1 Hash",               "160-bit SHA-1 checksum generator",                      ToolCategory.HASHES,     Ic.Fingerprint),
        ToolItem("md5",             "MD5 Hash",                 "Standard 128-bit MD5 digest generator",                 ToolCategory.HASHES,     Ic.Fingerprint),
        ToolItem("hmac-sha256",     "HMAC-SHA256 Signer",       "Keyed-hash message authentication code",                ToolCategory.HASHES,     Ic.Security),
        ToolItem("crc32",           "CRC32 Checksum",           "Cyclic redundancy check for packet verification",       ToolCategory.HASHES,     Ic.Calculate),
        ToolItem("json-formatter",  "JSON Beautifier & Minifier","Prettify, validate syntax and minify JSON structures",  ToolCategory.FORMATTERS, Ic.ContentCopy),
        ToolItem("sql-formatter",   "SQL Query Beautifier",     "Format complex SELECT, INSERT, UPDATE queries",         ToolCategory.FORMATTERS, Ic.TableChart),
        ToolItem("ip-calculator",   "IPv4 Subnet Calculator",   "Calculate CIDR, broadcast, netmask and host range",     ToolCategory.NETWORK,    Ic.Public),
        ToolItem("uuid-gen",        "UUID v4 Generator",        "Generate cryptographically secure RFC 4122 UUIDs",     ToolCategory.GENERATORS, Ic.Token),
        ToolItem("random-string",   "Secure Random String",     "Alphanumeric passwords, tokens, entropy keys",          ToolCategory.GENERATORS, Ic.Password),
        ToolItem("lorem-ipsum",     "Lorem Ipsum Generator",    "Generate mock payload strings and paragraphs",          ToolCategory.GENERATORS, Ic.Notes)
    )

    data class CustomTool(
        val id: String,
        val name: String,
        val operation: String, // "regex-replace" or "template"
        val pattern: String,
        val replacement: String,
    )

    /** User-defined custom tools, stored in-memory. */
    private val customTools = java.util.concurrent.ConcurrentHashMap<String, CustomTool>()

    private fun customToolToItem(ct: CustomTool): ToolItem = ToolItem(
        id = ct.id,
        title = ct.name,
        description = when (ct.operation) {
            "regex-replace" -> "Custom regex: ${ct.pattern} → ${ct.replacement}"
            "template" -> "Template: ${ct.pattern}"
            else -> ct.operation
        },
        category = ToolCategory.CUSTOM,
        icon = Ic.Build
    )

    private fun computeCatalog(): List<ToolItem> = builtInTools + customTools.values.map(::customToolToItem)

    // E2: the tool list used to be `ToolsRepository.getToolsCatalog()` called once and never
    // re-keyed, so a newly created custom tool never appeared until an unrelated recomposition.
    // `catalog` is the live source of truth; `getToolsCatalog()` below just reads its value for
    // call sites (Flow TOOL nodes, LocalApiServer) that only need a one-shot snapshot.
    private val _catalog = kotlinx.coroutines.flow.MutableStateFlow(computeCatalog())
    val catalog: kotlinx.coroutines.flow.StateFlow<List<ToolItem>> = _catalog

    fun getToolsCatalog(): List<ToolItem> = _catalog.value


    // ==========================================
    // 1. DECODERS
    // ==========================================

    fun urlEncode(input: String): String = try {
        URLEncoder.encode(input, StandardCharsets.UTF_8.name())
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun urlDecode(input: String): String = try {
        URLDecoder.decode(input, StandardCharsets.UTF_8.name())
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun encodeBase64(input: String): String = try {
        Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun decodeBase64(input: String): String = try {
        val bytes = Base64.decode(input, Base64.DEFAULT)
        String(bytes, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        "Error: Invalid Base64 string (${e.message})"
    }

    fun textToHex(input: String): String = try {
        input.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun hexToText(input: String): String = try {
        val clean = input.replace(" ", "").replace("0x", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            val j = clean.substring(index, index + 2).toInt(16)
            result[i] = j.toByte()
        }
        String(result, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        "Error: Invalid Hex string (${e.message})"
    }

    fun decodeJwt(token: String): String {
        return try {
            val parts = token.trim().split(".")
            if (parts.size < 2) {
                return "Invalid JWT: Must contain header.payload segments separated by '.'"
            }
            val headerJson = formatJson(String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            val payloadJson = formatJson(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            val sig = if (parts.size >= 3) parts[2] else "[No signature]"

            """
// === JWT HEADER ===
$headerJson

// === JWT PAYLOAD (CLAIMS) ===
$payloadJson

// === SIGNATURE ===
$sig
            """.trimIndent()
        } catch (e: Exception) {
            "Error decoding JWT: ${e.localizedMessage}"
        }
    }

    fun htmlEntityEncode(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    fun htmlEntityDecode(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    // ==========================================
    // 2. ENCRYPTION / DECRYPTION
    // ==========================================

    fun aesEncrypt(
        plainText: String,
        keyStr: String,
        ivStr: String = "1234567890123456",
        mode: String = "CBC",
        padding: String = "PKCS5Padding"
    ): String {
        return try {
            val keyBytes = keyStr.padEnd(16, '0').take(16).toByteArray(StandardCharsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/$mode/$padding")
            if (mode == "ECB") {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            } else if (mode == "GCM") {
                val ivBytes = ivStr.padEnd(12, '0').take(12).toByteArray(StandardCharsets.UTF_8)
                val gcmSpec = GCMParameterSpec(128, ivBytes)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            } else {
                val ivBytes = ivStr.padEnd(16, '0').take(16).toByteArray(StandardCharsets.UTF_8)
                val ivSpec = IvParameterSpec(ivBytes)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            }
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            "AES Encryption Error: ${e.message}"
        }
    }

    fun aesDecrypt(
        cipherTextBase64: String,
        keyStr: String,
        ivStr: String = "1234567890123456",
        mode: String = "CBC",
        padding: String = "PKCS5Padding"
    ): String {
        return try {
            val keyBytes = keyStr.padEnd(16, '0').take(16).toByteArray(StandardCharsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/$mode/$padding")
            if (mode == "ECB") {
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
            } else if (mode == "GCM") {
                val ivBytes = ivStr.padEnd(12, '0').take(12).toByteArray(StandardCharsets.UTF_8)
                val gcmSpec = GCMParameterSpec(128, ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            } else {
                val ivBytes = ivStr.padEnd(16, '0').take(16).toByteArray(StandardCharsets.UTF_8)
                val ivSpec = IvParameterSpec(ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            }
            val cipherBytes = Base64.decode(cipherTextBase64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            "AES Decryption Error: ${e.message}"
        }
    }

    // ==========================================
    // 3. HASHING & SIGNATURES
    // ==========================================

    fun hashSha256(input: String): String = hashAlgorithm("SHA-256", input)
    fun hashSha1(input: String): String = hashAlgorithm("SHA-1", input)
    fun hashMd5(input: String): String = hashAlgorithm("MD5", input)

    private fun hashAlgorithm(algorithm: String, input: String): String {
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Hash Error ($algorithm): ${e.message}"
        }
    }

    // ==========================================
    // 4. NETWORK / IP
    // ==========================================

    fun calculateSubnet(cidrStr: String): String {
        return try {
            val parts = cidrStr.trim().split("/")
            val ip = parts[0]
            val maskBits = if (parts.size > 1) parts[1].toInt() else 24
            val totalHosts = (1L shl (32 - maskBits))
            val usableHosts = if (totalHosts > 2) totalHosts - 2 else totalHosts

            """
IP Address: $ip
CIDR Prefix: /$maskBits
Total Addresses: $totalHosts
Usable Hosts: $usableHosts
Subnet Mask: ${cidrToNetmask(maskBits)}
Broadcast: $ip (Derived)
            """.trimIndent()
        } catch (e: Exception) {
            "Subnet calculation error: ${e.localizedMessage}"
        }
    }

    private fun cidrToNetmask(cidr: Int): String {
        val mask = (-1L shl (32 - cidr)).toInt()
        return "${(mask ushr 24) and 0xff}.${(mask ushr 16) and 0xff}.${(mask ushr 8) and 0xff}.${mask and 0xff}"
    }

    // ==========================================
    // 5. GENERATORS
    // ==========================================

    fun generateUuid(): String = UUID.randomUUID().toString()

    fun generateRandomString(length: Int = 32): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+"
        return (1..length).map { chars.random() }.joinToString("")
    }

    fun generateLoremIpsum(paragraphs: Int = 2): String {
        val p = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
        return (1..paragraphs).joinToString("\n\n") { p }
    }

    // ==========================================
    // 6. FORMATTERS
    // ==========================================

    fun formatJson(json: String, indent: Int = 2): String {
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).toString(indent)
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed).toString(indent)
            } else {
                json
            }
        } catch (e: Exception) {
            json
        }
    }

    fun formatSql(sql: String): String {
        val keywords = listOf("SELECT", "FROM", "WHERE", "AND", "OR", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM")
        var formatted = sql.trim()
        keywords.forEach { kw ->
            val regex = "(?i)\\b$kw\\b".toRegex()
            formatted = regex.replace(formatted, "\n$kw")
        }
        return formatted.trim()
    }

    /**
     * Dispatches a tool execution by ID for use in Flow TOOL nodes.
     * Maps toolId to the appropriate function in this repository.
     */
    fun executeToolFunction(toolId: String, input: String): String {
        // Custom tools first — check if it's a custom regex/template tool
        customTools[toolId]?.let { customTool ->
            return executeCustomTool(customTool, input)
        }

        return when (toolId.lowercase().replace(" ", "-").replace("_", "-")) {
            "base64-encode", "base64" -> encodeBase64(input)
            "base64-decode" -> decodeBase64(input)
            "hex-encode", "text-to-hex" -> textToHex(input)
            "hex-decode", "hex-to-text" -> hexToText(input)
            "jwt-decode", "jwt-decoder" -> decodeJwt(input)
            "html-encode", "html-entity-encode" -> htmlEntityEncode(input)
            "html-decode", "html-entity-decode" -> htmlEntityDecode(input)
            "sha256", "hash-sha256" -> hashSha256(input)
            "sha1", "hash-sha1" -> hashSha1(input)
            "md5", "hash-md5" -> hashMd5(input)
            "uuid", "generate-uuid" -> generateUuid()
            "random-string", "secure-random-string" -> generateRandomString(input.toIntOrNull() ?: 32)
            "lorem", "lorem-ipsum" -> generateLoremIpsum(input.toIntOrNull() ?: 2)
            "json-format", "json-formatter" -> formatJson(input)
            "sql-format", "sql-formatter" -> formatSql(input)
            "ip-calc", "ip-calculator", "subnet" -> calculateSubnet(input)
            "url-encode" -> java.net.URLEncoder.encode(input, "UTF-8")
            "url-decode" -> java.net.URLDecoder.decode(input, "UTF-8")
            else -> "{\"error\": \"Unknown tool: $toolId\", \"input\": \"${input.take(100)}\"}"
        }
    }

    // ── Custom Tools ─────────────────────────────────────────────────

    /**
     * Adds a custom tool from structured fields, and republishes [catalog] so the Tools list
     * reflects it immediately.
     *
     * E3: previously took a display-formatted [ToolItem] and reverse-parsed its description
     * (e.g. `"Regex: $pattern → $replacement"`) to recover `pattern`/`replacement` — any wording
     * change in that description format would silently corrupt the tool. Structured fields
     * remove that round-trip entirely; the description is now derived for display only, in
     * [customToolToItem].
     */
    fun addCustomTool(name: String, operation: String, pattern: String, replacement: String) {
        val id = "custom-${name.lowercase().replace(Regex("[^a-z0-9]"), "-")}"
        customTools[id] = CustomTool(id = id, name = name, operation = operation, pattern = pattern, replacement = replacement)
        _catalog.value = computeCatalog()
    }

    /** Executes a custom tool on the input. */
    private fun executeCustomTool(tool: CustomTool, input: String): String {
        return when (tool.operation) {
            "regex-replace" -> try {
                input.replace(Regex(tool.pattern), tool.replacement)
            } catch (e: Exception) {
                "{\"error\": \"Invalid regex: ${e.message}\"}"
            }
            "template" -> tool.pattern.replace("{input}", input)
            else -> input
        }
    }
}
