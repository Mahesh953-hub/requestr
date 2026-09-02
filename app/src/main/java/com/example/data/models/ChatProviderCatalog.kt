package com.example.data.models

/**
 * Reqy Assistant provider catalog — matches the provider-chip row in
 * `docs/designs/ai-chat/reqy-assistant-light.html` Screen 2 (`settings-provider-chips`):
 * OpenAI, Anthropic, Gemini, OpenRouter, Groq (Free), HuggingFace (Free), Ollama (Local),
 * + Custom.
 *
 * This file is deliberately pure/no-I/O so it can be unit-tested directly: it only describes
 * *shape* (which fields a chip needs, its default endpoint/model, whether it needs a key) plus
 * a couple of pure validation helpers. The actual HTTP call per kind lives in
 * [com.example.data.repository.ChatRepository]; [ChatMessage]/[ChatProviderConfig] themselves
 * are NOT touched by this file (both stay exactly as declared elsewhere in this package) — a
 * provider's *kind* is derived from [ChatProviderConfig.id] via [ChatProviderKind.fromId].
 */
enum class ChatProviderKind(
    val id: String,
    val displayName: String,
    /** ARGB-ish brand color (matches the chip's colored dot in the mockup). */
    val brandColor: Long,
    /** True when [ChatRepository] refuses to call this provider without a non-blank key. */
    val requiresApiKey: Boolean,
    /** True when the chip shows an editable base-URL/host field (Ollama, Custom). */
    val editableBaseUrl: Boolean,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /**
     * Conservative allow-list of providers known to accept multimodal (image) input in their
     * chat-completion shape. Used only to decide whether the composer shows a clear "this
     * provider will only see the image's name/size, not the picture" warning before Send --
     * the image is never silently dropped either way, this only controls the honesty of the
     * copy. Defaults to `false` for anything not explicitly verified (Groq/HuggingFace/Ollama's
     * free-tier text models, and any unknown Custom endpoint).
     */
    val supportsVision: Boolean = false,
) {
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        brandColor = 0xFF10A37F,
        requiresApiKey = true,
        editableBaseUrl = false,
        defaultBaseUrl = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
        supportsVision = true,
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        brandColor = 0xFFD97757,
        requiresApiKey = true,
        editableBaseUrl = false,
        defaultBaseUrl = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-3-5-sonnet-20241022",
        supportsVision = true,
    ),
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
        brandColor = 0xFF4285F4,
        requiresApiKey = true,
        editableBaseUrl = false,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        defaultModel = "gemini-1.5-flash",
        supportsVision = true,
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        brandColor = 0xFF8B5CF6,
        requiresApiKey = true,
        editableBaseUrl = false,
        defaultBaseUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "openai/gpt-4o-mini",
        supportsVision = true,
    ),
    GROQ(
        id = "groq",
        displayName = "Groq (Free)",
        brandColor = 0xFFF97316,
        requiresApiKey = true,
        editableBaseUrl = false,
        defaultBaseUrl = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.1-8b-instant",
    ),
    HUGGINGFACE(
        id = "huggingface",
        displayName = "HuggingFace (Free)",
        brandColor = 0xFFFFD21E,
        requiresApiKey = false,
        editableBaseUrl = false,
        defaultBaseUrl = "https://api-inference.huggingface.co/models",
        defaultModel = "meta-llama/Llama-3.2-3B-Instruct",
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama (Local)",
        brandColor = 0xFF000000,
        requiresApiKey = false,
        editableBaseUrl = true,
        // 10.0.2.2 is the Android emulator's alias for the host machine's loopback -- one of
        // the few hosts network_security_config.xml exempts from the cleartext ban (see
        // isBlockedCleartextHost below), so a fresh Ollama chip works out of the box on-device.
        defaultBaseUrl = "http://10.0.2.2:11434",
        defaultModel = "llama3.2",
    ),
    CUSTOM(
        id = "custom",
        displayName = "+ Custom",
        brandColor = 0xFF64748B,
        requiresApiKey = false,
        editableBaseUrl = true,
        defaultBaseUrl = "",
        defaultModel = "",
    );

    /** True for the OpenAI-compatible-shaped providers that share one request/response codec. */
    val isOpenAiCompatible: Boolean
        get() = this == OPENAI || this == OPENROUTER || this == GROQ || this == CUSTOM

    companion object {
        /** Declared order == the mockup's chip order (`settings-provider-chips`). */
        val chipOrder: List<ChatProviderKind> = entries.toList()

        fun fromId(id: String): ChatProviderKind = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

/**
 * Hosts `res/xml/network_security_config.xml` exempts from the cleartext-HTTP ban (base-config
 * there is `cleartextTrafficPermitted="false"`). Kept in sync with that file by hand -- there are
 * only 5 entries and they almost never change.
 */
private val CLEARTEXT_ALLOWED_HOSTS = setOf(
    "localhost", "127.0.0.1", "10.0.2.2", "10.0.3.2", "0.0.0.0",
)

/** Extracts the bare host (no port, no userinfo, no path) from a `scheme://host[:port]/...` URL. */
internal fun extractHost(rawUrl: String): String {
    val withoutScheme = rawUrl.trim().substringAfter("://", missingDelimiterValue = rawUrl.trim())
    val withoutPath = withoutScheme.substringBefore('/')
    val withoutUserInfo = withoutPath.substringAfterLast('@')
    val withoutPort = if (withoutUserInfo.startsWith('[')) {
        // IPv6 literal, e.g. [::1]:8080 -- keep the bracketed form as the "host".
        withoutUserInfo.substringBefore("]/") + if (withoutUserInfo.contains(']')) "]" else ""
    } else {
        withoutUserInfo.substringBefore(':')
    }
    return withoutPort
}

/**
 * True when [rawUrl] is plain HTTP to a host the platform will actually refuse at the socket
 * layer (see `network_security_config.xml`'s `cleartextTrafficPermitted="false"` base-config).
 * Callers should check this BEFORE making the network call so the user gets a precise reason
 * instead of a generic "Connection failed" / `CLEARTEXT communication ... not permitted`.
 */
fun isBlockedCleartextHost(rawUrl: String): Boolean {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)) return false
    val host = extractHost(trimmed)
    return host.isNotBlank() && host.lowercase() !in CLEARTEXT_ALLOWED_HOSTS
}

/**
 * Field-level validation for a provider chip's config, run before Send is enabled in the UI
 * (and defensively again in [com.example.data.repository.ChatRepository.callProvider]). Returns
 * a human-readable reason, or `null` when [baseUrl]/[apiKey] are ready to call.
 */
fun validateProviderFields(kind: ChatProviderKind, baseUrl: String, apiKey: String): String? {
    val effectiveBaseUrl = baseUrl.ifBlank { kind.defaultBaseUrl }
    if (kind.editableBaseUrl && effectiveBaseUrl.isBlank()) {
        return "${kind.displayName} needs a base URL (e.g. an Ollama host, or your custom endpoint)."
    }
    if (kind.requiresApiKey && apiKey.isBlank()) {
        return "${kind.displayName} needs an API key."
    }
    if (isBlockedCleartextHost(effectiveBaseUrl)) {
        val host = extractHost(effectiveBaseUrl)
        return "Cleartext HTTP to '$host' is blocked by requestr's network security policy. " +
            "Only localhost/127.0.0.1/10.0.2.2/10.0.3.2/0.0.0.0 allow plain HTTP -- use HTTPS, " +
            "or point this at the emulator's host alias (10.0.2.2) instead."
    }
    return null
}
