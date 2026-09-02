package com.example.data.models

import org.json.JSONObject

/**
 * Media attachments (Image / File / Screenshot from the attachment sheet -- see
 * `docs/designs/ai-chat/reqy-assistant-light.html` Screen 4) referenced by a chat message.
 *
 * [ChatMessage]/[com.example.data.local.ChatMessageEntity] are frozen this wave (no Room schema
 * bump -- see the Phase A shared context), so there is no dedicated attachment column. Instead
 * an attachment is encoded as a small JSON header on the FIRST line of [ChatMessage.content],
 * prefixed with [ATTACH_MARKER] (a Unicode "symbol for unit separator" that will not appear in
 * normal typed text), followed by the caption/body on the remaining lines. [decodeAttachment]
 * is the exact inverse and is total -- content with no marker round-trips as `null` + itself
 * unchanged, so every existing/plain message keeps working with zero migration.
 *
 * Code-snippet and Request/Response attachments do NOT use this encoding -- they are inserted
 * as plain fenced ```code blocks``` (see [ChatCodeFence]), which is simpler, needs no special
 * decoding, and matches how any other markdown-ish assistant reply already renders.
 */
enum class ChatAttachmentKind { IMAGE, FILE, SCREENSHOT }

data class ChatAttachment(
    val kind: ChatAttachmentKind,
    val name: String,
    val sizeBytes: Long,
    /** `content://` (or file) URI string the bubble can load a thumbnail from. May be blank. */
    val uri: String = "",
)

private const val ATTACH_MARKER = "\u241FREQY_ATTACH\u241F"

/** Maximum size accepted for any single Image/File/Screenshot attachment: 8 MB. */
const val MAX_ATTACHMENT_SIZE_BYTES = 8L * 1024 * 1024

fun encodeAttachment(attachment: ChatAttachment, caption: String): String {
    val header = JSONObject().apply {
        put("kind", attachment.kind.name)
        put("name", attachment.name)
        put("sizeBytes", attachment.sizeBytes)
        put("uri", attachment.uri)
    }
    return ATTACH_MARKER + header.toString() + "\n" + caption
}

/** Returns `(attachment, remainingBody)`; `(null, content)` unchanged when there's no marker. */
fun decodeAttachment(content: String): Pair<ChatAttachment?, String> {
    if (!content.startsWith(ATTACH_MARKER)) return null to content
    val withoutMarker = content.removePrefix(ATTACH_MARKER)
    val newlineIndex = withoutMarker.indexOf('\n')
    val headerJson = if (newlineIndex >= 0) withoutMarker.substring(0, newlineIndex) else withoutMarker
    val body = if (newlineIndex >= 0) withoutMarker.substring(newlineIndex + 1) else ""
    return try {
        val json = JSONObject(headerJson)
        ChatAttachment(
            kind = ChatAttachmentKind.valueOf(json.getString("kind")),
            name = json.optString("name", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            uri = json.optString("uri", ""),
        ) to body
    } catch (e: Exception) {
        null to content
    }
}

/** Pure helpers for the attachment sheet's "Code Snippet" and "Request/Response" fenced blocks. */
object ChatCodeFence {
    /** Wraps [code] in a fenced block the bubble renderer recognizes, tagged with [language]. */
    fun wrap(code: String, language: String = ""): String =
        "```$language\n${code.trimEnd()}\n```"

    /** True when [content] contains at least one complete ``` ... ``` fenced block. */
    fun containsFence(content: String): Boolean =
        Regex("```[a-zA-Z0-9_+-]*\\n[\\s\\S]*?```").containsMatchIn(content)
}
