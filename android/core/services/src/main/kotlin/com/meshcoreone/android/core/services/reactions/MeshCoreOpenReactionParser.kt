// PortedFrom: MC1Services/Sources/MC1Services/Services/MeshCoreOpenReactionParser.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.services.rendering.SwiftText

/** Parsed meshcore-open v3 reaction data. [dartHash] is 4 lowercase hex characters as received. */
data class ParsedMCOReaction(val emoji: String, val dartHash: String)

/**
 * Parsed meshcore-open v1 reaction data (pre-Jan 2026 clients).
 *
 * [senderNameHash] and [textHash] are full Dart VM `String.hashCode` values (30-bit, decimal-encoded on the
 * wire). For DM reactions, [senderNameHash] is not verified during matching since DMs have implicit sender
 * context.
 */
data class ParsedMCOReactionV1(
    val emoji: String,
    val timestampSeconds: UInt,
    val senderNameHash: UInt,
    val textHash: UInt,
) {
    /** Reconstructs the original v1 messageId, used as the opaque reaction hash for dedup. */
    val messageIdHash: String get() = "${timestampSeconds}_${senderNameHash}_$textHash"
}

/**
 * Parses meshcore-open reaction wire format (receive-only).
 *
 * meshcore-open sends reactions as `r:{4-char-hash}:{2-char-emoji-index}`. The hash is computed using the
 * Dart VM's `String.hashCode` algorithm masked to 16 bits. Positions and lengths are Swift `Character`
 * (grapheme) positions, so a combining mark glued to a delimiter defeats the match exactly as in Swift.
 */
object MeshCoreOpenReactionParser {
    private const val PREFIX_LETTER = "r"
    private const val SEPARATOR = ":"
    private const val V3_LENGTH = 9
    private const val V3_HASH_START = 2
    private const val V3_HASH_END = 6
    private const val V3_INDEX_START = 7
    private const val V1_MESSAGE_ID_START = 2
    private const val V1_PART_SEPARATOR = "_"
    private const val V1_PART_COUNT = 3
    private const val MILLIS_PER_SECOND = 1000u
    private const val HEX_RADIX = 16
    private const val HASH_MASK = 0xFFFFu
    private const val HASH_WIDTH = 4
    private const val THIRTY_BIT_MASK = 0x3FFF_FFFFu

    // MARK: - Parsing

    /** Parses `r:{4-char-hex-hash}:{2-char-hex-emoji-index}`; null if the format doesn't match. */
    fun parse(text: String): ParsedMCOReaction? {
        val characters = SwiftText.graphemes(text)
        if (characters.size != V3_LENGTH || !hasReactionPrefix(characters) || characters[V3_HASH_END] != SEPARATOR) return null
        val hash = characters.subList(V3_HASH_START, V3_HASH_END)
        val index = characters.subList(V3_INDEX_START, characters.size)
        // Validate both are lowercase hex.
        if (!hash.all(::isLowercaseHex) || !index.all(::isLowercaseHex)) return null
        val emojiIndex = SwiftIntegerText.uint8(index.joinToString(""), HEX_RADIX)?.toInt() ?: return null
        if (emojiIndex >= emojiTable.size) return null
        return ParsedMCOReaction(emoji = emojiTable[emojiIndex], dartHash = hash.joinToString(""))
    }

    /**
     * Parses `r:{millis}_{senderNameHash}_{textHash}:{emoji}` (pre-Jan 2026 meshcore-open clients). The hashes
     * are full Dart `String.hashCode` values (30-bit, decimal-encoded).
     */
    fun parseV1(text: String): ParsedMCOReactionV1? {
        val characters = SwiftText.graphemes(text)
        if (!hasReactionPrefix(characters)) return null
        // Split on the last ":" to separate messageId from emoji.
        val lastColon = characters.lastIndexOf(SEPARATOR)
        if (lastColon <= V1_MESSAGE_ID_START) return null
        val messageId = characters.subList(V1_MESSAGE_ID_START, lastColon)
        val emoji = characters.subList(lastColon + 1, characters.size).joinToString("")
        if (emoji.isEmpty()) return null

        // Split messageId on "_" (empty pieces dropped, as Swift `split` does) — expect exactly 3 parts.
        val parts = splitCharacters(messageId, V1_PART_SEPARATOR)
        if (parts.size != V1_PART_COUNT) return null
        val timestampMillis = SwiftIntegerText.uint64(parts[0]) ?: return null
        val senderNameHash = SwiftIntegerText.uint32(parts[1]) ?: return null
        val textHash = SwiftIntegerText.uint32(parts[2]) ?: return null

        val seconds = timestampMillis / MILLIS_PER_SECOND.toULong()
        if (seconds > UInt.MAX_VALUE.toULong()) return null
        return ParsedMCOReactionV1(
            emoji = emoji,
            timestampSeconds = seconds.toUInt(),
            senderNameHash = senderNameHash,
            textHash = textHash,
        )
    }

    // MARK: - Hash Computation

    /** Dart VM `String.hashCode` over the string's UTF-16 code units. */
    fun dartStringHash(string: String): UInt = dartStringHash(string.map { it.code.toUShort() })

    /**
     * Reimplements the Dart VM's `String.hashCode` algorithm on UTF-16 code units with a 30-bit result:
     * per unit `hash += unit; hash += hash << 10; hash ^= hash >> 6`, then `hash += hash << 3;
     * hash ^= hash >> 11; hash += hash << 15`, mask to 30 bits, and 0 becomes 1. All arithmetic wraps at 32 bits.
     */
    fun dartStringHash(codeUnits: List<UShort>): UInt {
        var hash = 0u
        for (unit in codeUnits) {
            hash += unit.toUInt()
            hash += hash shl 10
            hash = hash xor (hash shr 6)
        }
        hash += hash shl 3
        hash = hash xor (hash shr 11)
        hash += hash shl 15
        hash = hash and THIRTY_BIT_MASK
        return if (hash == 0u) 1u else hash
    }

    /**
     * Computes the reaction hash used by meshcore-open: Dart hash of `"$timestamp" + senderName +
     * first 5 UTF-16 code units of text`, masked to 16 bits, as 4 lowercase hex chars. [senderName] is null
     * for DM reactions.
     */
    fun computeReactionHash(timestamp: UInt, senderName: String?, text: String): String {
        val units = timestamp.toString() + (senderName ?: "") + text.take(5)
        val hash = dartStringHash(units) and HASH_MASK
        return hash.toString(HEX_RADIX).padStart(HASH_WIDTH, '0')
    }

    // MARK: - Emoji Table

    /**
     * The 184-emoji lookup table from meshcore-open's emoji_picker.dart.
     * Concatenated in order: quickEmojis + smileys + gestures + hearts + objects.
     */
    val emojiTable: List<String> = listOf(
        // quickEmojis (0x00–0x05)
        "👍", "❤️", "😂", "🎉", "👏", "🔥",
        // smileys (0x06–0x45)
        "😀", "😃", "😄", "😁", "😅", "😂", "🤣", "😊",
        "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘",
        "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪",
        "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏",
        "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
        "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡",
        "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰",
        "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶",
        // gestures (0x46–0x66)
        "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️",
        "🤟", "🤘", "👌", "🤌", "🤏", "👈", "👉", "👆",
        "👇", "☝️", "👋", "🤚", "🖐️", "✋", "🖖", "👏",
        "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳",
        "💪",
        // hearts (0x67–0x86)
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "💌", "💢", "💥", "💫",
        "💦", "💨", "🕳️", "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭",
        // objects (0x87–0xB7)
        "🎉", "🎊", "🎈", "🎁", "🎀", "🪅", "🪆", "🏆",
        "🥇", "🥈", "🥉", "⚽", "⚾", "🥎", "🏀", "🏐",
        "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑", "🏒",
        "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "🔥",
        "⭐", "🌟", "✨", "⚡", "💡", "🔦", "🏮", "🪔",
        "📱", "💻", "⌚", "📷", "📺", "📻", "🎵", "🎶",
        "🚀",
    )

    // MARK: - Private Helpers

    private fun hasReactionPrefix(characters: List<String>): Boolean =
        characters.size >= 2 && characters[0] == PREFIX_LETTER && characters[1] == SEPARATOR

    /**
     * Swift `isHexDigit && !isUppercase` for one Character: a single scalar among ASCII `0-9 a-f` or their
     * fullwidth forms U+FF10–U+FF19 and U+FF41–U+FF46 (set enumerated from the Swift 6.3.2 runtime).
     */
    private fun isLowercaseHex(character: String): Boolean {
        if (character.length != 1) return false
        return when (character[0]) {
            in '0'..'9', in 'a'..'f', in '\uFF10'..'\uFF19', in '\uFF41'..'\uFF46' -> true
            else -> false
        }
    }

    /** Swift `split(separator:)` over Characters (empty pieces dropped), joined back to strings. */
    private fun splitCharacters(characters: List<String>, separator: String): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        for (index in 0..characters.size) {
            if (index == characters.size || characters[index] == separator) {
                if (index > start) pieces.add(characters.subList(start, index).joinToString(""))
                start = index + 1
            }
        }
        return pieces
    }
}
