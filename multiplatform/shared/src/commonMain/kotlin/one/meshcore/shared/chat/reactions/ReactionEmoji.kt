package one.meshcore.shared.chat.reactions

/**
 * Whether [text] begins with an emoji code point. The reaction grammars require the payload to start with
 * an emoji (`emoji.first?.isEmoji` in Swift). Kotlin common has no Unicode emoji property, so the check is
 * a code-point set, and it has to cover everything the app's own picker offers: an emoji the picker sends
 * but this check rejects travels as a raw "emoji + hash" text bubble to the sender and to every peer, and
 * 🆘 is in that picker. Skin-tone modifiers, ZWJ families, and regional-indicator flags all lead with an
 * in-range code point; keycaps lead with an ASCII character and are matched by their combining mark.
 */
internal fun startsWithEmoji(text: String): Boolean {
    if (text.isEmpty()) return false
    if (isKeycapSequence(text)) return true
    val codePoint = firstCodePoint(text)
    return EMOJI_RANGES.any { codePoint in it }
}

/**
 * True when [value] is exactly one emoji grapheme. Swift `isReactionEmoji` keeps mention-plus-text
 * (`@[Alice]🎉 on my way`) as chat, not a badge. [extendedGraphemeCount] is the Swift `Character`
 * analog so skin-tone, ZWJ, flags, and keycaps still count as one.
 */
internal fun isReactionEmoji(value: String): Boolean {
    if (value.isEmpty() || !startsWithEmoji(value)) return false
    return extendedGraphemeCount(value) == 1
}

/** Number of extended grapheme clusters in [text] (`String.count` of Swift `Character`s). */
internal expect fun extendedGraphemeCount(text: String): Int

/**
 * A keycap (`1️⃣`, `#️⃣`) leads with a plain ASCII character, so it counts only when the combining keycap
 * mark follows; otherwise any line starting with a digit would read as a reaction.
 */
private fun isKeycapSequence(text: String): Boolean {
    val first = text[0]
    if (first !in '0'..'9' && first != '#' && first != '*') return false
    return text.drop(1).dropWhile { it.code == VARIATION_SELECTOR_16 }.firstOrNull()?.code == COMBINING_KEYCAP
}

private fun firstCodePoint(text: String): Int {
    val high = text[0]
    if (high.isHighSurrogate() && text.length > 1) {
        val low = text[1]
        if (low.isLowSurrogate()) {
            return SUPPLEMENTARY_OFFSET + ((high.code - HIGH_SURROGATE_BASE) shl SURROGATE_SHIFT) +
                (low.code - LOW_SURROGATE_BASE)
        }
    }
    return high.code
}

private const val SUPPLEMENTARY_OFFSET = 0x10000
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SURROGATE_SHIFT = 10
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_KEYCAP = 0x20E3

/**
 * Code points carrying the Unicode Emoji property, as ranges. The supplementary plane is taken wholesale
 * (every pictograph, flag, and enclosed-alphanumeric glyph lives there); the BMP entries stay narrow,
 * because the blocks around them hold ordinary punctuation and symbols that must remain plain text.
 */
private val EMOJI_RANGES = listOf(
    0x1F000..0x1FAFF,
    0x2600..0x27BF,
    0x2B00..0x2BFF,
    0x2194..0x21AA,
    0x231A..0x231B,
    0x2328..0x2328,
    0x23CF..0x23CF,
    0x23E9..0x23FA,
    0x24C2..0x24C2,
    0x25AA..0x25FE,
    0x2934..0x2935,
    0x00A9..0x00AE,
    0x203C..0x203C,
    0x2049..0x2049,
    0x2122..0x2122,
    0x2139..0x2139,
    0x3030..0x3030,
    0x303D..0x303D,
    0x3297..0x3299,
    0xFE00..0xFE0F,
)
