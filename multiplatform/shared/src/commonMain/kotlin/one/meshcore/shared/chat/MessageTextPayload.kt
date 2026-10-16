package one.meshcore.shared.chat

import one.meshcore.shared.chat.text.FormattedText

/**
 * Payload for the `.text` fragment. Value-typed and fully structural, so two payloads are equal iff every
 * field matches — equality drives list diffing. Mirrors the Swift `MessageTextPayload`.
 *
 * [formattedText] carries the linkified [FormattedText] (display string plus spans). When it is null the
 * renderer falls back to [raw]. The framework-free span model keeps the shared payload independent of
 * Compose's `AnnotatedString`.
 */
data class MessageTextPayload(
    val raw: String,
    val formattedText: FormattedText?,
    val baseColor: BaseColorSlot,
    val isOutgoing: Boolean,
    val currentUserName: String,
)
