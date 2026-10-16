package one.meshcore.shared.contact

/**
 * Emoji detection for [ContactDto.avatarInitials], an approximation of Swift's grapheme-level
 * `Character.isEmoji` over the common pictographic ranges. Kept framework-neutral so the avatar
 * initials stay unit-testable on the desktop JVM.
 */

/** The first emoji-like code point in [text] as a string, or `null` when the name is plain text. */
internal fun firstEmojiOrNull(text: String): String? {
    var index = 0
    while (index < text.length) {
        val codePoint = codePointAt(text, index)
        if (isEmojiCodePoint(codePoint)) return codePointToString(codePoint)
        index += if (codePoint > BMP_MAX) 2 else 1
    }
    return null
}

private fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) {
            return SUPPLEMENTARY_OFFSET + ((high.code - HIGH_SURROGATE_BASE) shl SURROGATE_SHIFT) +
                (low.code - LOW_SURROGATE_BASE)
        }
    }
    return high.code
}

private fun codePointToString(codePoint: Int): String {
    if (codePoint <= BMP_MAX) return codePoint.toChar().toString()
    val offset = codePoint - SUPPLEMENTARY_OFFSET
    val high = HIGH_SURROGATE_BASE + (offset shr SURROGATE_SHIFT)
    val low = LOW_SURROGATE_BASE + (offset and SURROGATE_LOW_MASK)
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}

private fun isEmojiCodePoint(codePoint: Int): Boolean = EMOJI_RANGES.any { codePoint in it }

// UTF-16 code-point decoding constants.
private const val BMP_MAX = 0xFFFF
private const val SUPPLEMENTARY_OFFSET = 0x10000
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SURROGATE_SHIFT = 10
private const val SURROGATE_LOW_MASK = 0x3FF

// Common pictographic emoji code-point ranges, an approximation of Swift's `Character.isEmoji`.
private val EMOJI_RANGES = listOf(
    0x1F300..0x1FAFF,
    0x1F000..0x1F0FF,
    0x2600..0x27BF,
    0x2B00..0x2BFF,
    0x1F1E6..0x1F1FF,
    0xFE00..0xFE0F,
)
