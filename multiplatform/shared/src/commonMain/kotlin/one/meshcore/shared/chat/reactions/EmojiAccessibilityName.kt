package one.meshcore.shared.chat.reactions

/**
 * Spoken accessibility name for an emoji sequence, derived from Unicode character names
 * (e.g. "👍" → "thumbs up sign"). Mirrors iOS `String.emojiAccessibilityName` so TalkBack /
 * VoiceOver announce a stable app-controlled label rather than OS-default emoji speech.
 */
fun emojiAccessibilityName(emoji: String): String {
    if (emoji.isEmpty()) return emoji
    val parts = mutableListOf<String>()
    var index = 0
    while (index < emoji.length) {
        val codePoint = codePointAt(emoji, index)
        val name = unicodeCharacterName(codePoint)
        if (name != null) {
            parts.add(name.lowercase())
        }
        index += codePointCharCount(codePoint)
    }
    return if (parts.isEmpty()) emoji else parts.joinToString(" ")
}

/** Unicode code point at [index] (handles surrogate pairs). */
internal expect fun codePointAt(text: String, index: Int): Int

/** Number of UTF-16 chars that encode [codePoint]. */
internal expect fun codePointCharCount(codePoint: Int): Int

/** Official Unicode name for [codePoint], or null when undefined. */
internal expect fun unicodeCharacterName(codePoint: Int): String?
