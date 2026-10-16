package one.meshcore.shared.chat.reactions

import one.meshcore.shared.model.toLowercaseHex

/** A parsed meshcore-open v3 reaction: the emoji from the index table and the 4-hex-char Dart hash. */
data class ParsedMcoReaction(val emoji: String, val dartHash: String)

/**
 * A parsed meshcore-open v1 reaction (pre-Jan 2026 clients). The hashes are full 30-bit Dart
 * `String.hashCode` values, decimal-encoded on the wire. For DM reactions [senderNameHash] is not verified
 * during matching since DMs carry implicit sender context.
 */
data class ParsedMcoReactionV1(
    val emoji: String,
    val timestampSeconds: Long,
    val senderNameHash: UInt,
    val textHash: UInt,
) {
    /** Reconstructs the v1 messageId, used as the opaque reaction hash for dedup. */
    val messageIdHash: String get() = "${timestampSeconds}_${senderNameHash}_$textHash"
}

/**
 * Receive-only parser for the two meshcore-open reaction formats. v3 is `r:{4hex}:{2hex}` (hash + emoji
 * index); v1 is `r:{millis}_{senderNameHash}_{textHash}:{emoji}`. Both hash message content with the Dart
 * VM's `String.hashCode`, which runs over UTF-16 code units with 32-bit wrapping arithmetic masked to 30
 * bits. Port of the Swift `MeshCoreOpenReactionParser`; the Dart hash and 184-entry emoji table are the
 * safety-critical, byte-exact contract (a UTF-8 hash would mis-hash non-BMP emoji).
 */
object MeshCoreOpenReactionParser {
    /** Parses v3 `r:{4hex-hash}:{2hex-emoji-index}`; length must be exactly 9, hex lowercase, index < 184. */
    fun parse(text: String): ParsedMcoReaction? {
        if (!isV3Shape(text)) return null
        val hashStr = text.substring(HASH_START, HASH_END)
        val indexStr = text.substring(INDEX_START)
        val emojiIndex = v3EmojiIndex(hashStr, indexStr) ?: return null
        return ParsedMcoReaction(emoji = emojiTable[emojiIndex], dartHash = hashStr)
    }

    /** Parses v1 `r:{millis}_{senderNameHash}_{textHash}:{emoji}`; splits on the last `:`. */
    fun parseV1(text: String): ParsedMcoReactionV1? {
        val parts = v1Body(text) ?: return null
        return v1Reaction(parts)
    }

    private fun isV3Shape(text: String): Boolean =
        text.length == V3_LENGTH && text.startsWith(PREFIX) && text[V3_SECOND_COLON] == ':'

    private fun v3EmojiIndex(hash: String, index: String): Int? {
        if (!isLowercaseHex(hash) || !isLowercaseHex(index)) return null
        val emojiIndex = index.toIntOrNull(radix = HEX_RADIX) ?: return null
        if (emojiIndex >= emojiTable.size) return null
        return emojiIndex
    }

    private fun v1Body(text: String): V1Parts? {
        if (!text.startsWith(PREFIX)) return null
        val lastColon = text.lastIndexOf(':').takeIf { it > PREFIX.length - 1 } ?: return null
        val emoji = text.substring(lastColon + 1).takeIf { it.isNotEmpty() } ?: return null
        val ids = text.substring(PREFIX.length, lastColon).split("_").takeIf { it.size == V1_ID_PARTS } ?: return null
        return V1Parts(emoji = emoji, ids = ids)
    }

    private fun v1Reaction(parts: V1Parts): ParsedMcoReactionV1? {
        val timestampMillis = parts.ids[0].toULongOrNull() ?: return null
        val senderNameHash = parts.ids[1].toUIntOrNull() ?: return null
        val textHash = parts.ids[2].toUIntOrNull() ?: return null
        val seconds = timestampMillis / MILLIS_PER_SECOND
        if (seconds > UInt.MAX_VALUE.toULong()) return null
        return ParsedMcoReactionV1(
            emoji = parts.emoji,
            timestampSeconds = seconds.toLong(),
            senderNameHash = senderNameHash,
            textHash = textHash,
        )
    }

    private class V1Parts(val emoji: String, val ids: List<String>)

    /** Convenience overload hashing a String's UTF-16 code units directly. */
    fun dartStringHash(string: String): UInt = dartStringHash(string.toUtf16CodeUnits())

    /**
     * The Dart VM's `String.hashCode`: fold each UTF-16 code unit with wrapping 32-bit arithmetic, finalize,
     * mask to 30 bits, and map a zero result to 1. `UInt` `+`/`shl` wrap by default; `shr` is the logical
     * shift Dart's `>>>` uses.
     */
    fun dartStringHash(codeUnits: List<Int>): UInt {
        var hash = 0u
        for (unit in codeUnits) {
            hash += unit.toUInt()
            hash += (hash shl LEFT_SHIFT_10)
            hash = hash xor (hash shr RIGHT_SHIFT_6)
        }
        hash += (hash shl LEFT_SHIFT_3)
        hash = hash xor (hash shr RIGHT_SHIFT_11)
        hash += (hash shl LEFT_SHIFT_15)
        hash = hash and HASH_BIT_MASK
        return if (hash == 0u) 1u else hash
    }

    /**
     * The v3 reaction hash: Dart-hash `"{timestamp}" + senderName + first-5-UTF16-code-units(text)`, mask to
     * 16 bits, format as 4 lowercase hex chars. [senderName] is null for DM reactions (omitted from the
     * input). Truncation is over UTF-16 code units, so a non-BMP leading emoji counts as two.
     */
    fun computeReactionHash(timestamp: Long, senderName: String?, text: String): String {
        val codeUnits = ArrayList<Int>()
        codeUnits += timestamp.toString().toUtf16CodeUnits()
        if (senderName != null) codeUnits += senderName.toUtf16CodeUnits()
        codeUnits += text.toUtf16CodeUnits().take(TEXT_CODE_UNIT_PREFIX)

        val bits = (dartStringHash(codeUnits) and MASK_16_BIT).toInt()
        return byteArrayOf((bits ushr REACTION_HASH_HIGH_BYTE_SHIFT).toByte(), bits.toByte()).toLowercaseHex()
    }

    private fun isLowercaseHex(string: String): Boolean =
        string.isNotEmpty() && string.all { it in '0'..'9' || it in 'a'..'f' }

    private const val PREFIX = "r:"
    private const val V3_LENGTH = 9
    private const val V3_SECOND_COLON = 6
    private const val HASH_START = 2
    private const val HASH_END = 6
    private const val INDEX_START = 7
    private const val HEX_RADIX = 16
    private const val REACTION_HASH_HIGH_BYTE_SHIFT = 8
    private const val V1_ID_PARTS = 3
    private const val MILLIS_PER_SECOND = 1000uL
    private const val TEXT_CODE_UNIT_PREFIX = 5
    private const val MASK_16_BIT = 0xFFFFu
    private val HASH_BIT_MASK: UInt = (1u shl 30) - 1u
    private const val LEFT_SHIFT_10 = 10
    private const val LEFT_SHIFT_3 = 3
    private const val LEFT_SHIFT_15 = 15
    private const val RIGHT_SHIFT_6 = 6
    private const val RIGHT_SHIFT_11 = 11

    /**
     * The 184-emoji lookup table from meshcore-open's `emoji_picker.dart` (indices 0x00–0xB7), copied
     * verbatim from the Swift `MeshCoreOpenReactionParser.emojiTable`: quickEmojis + smileys + gestures +
     * hearts + objects.
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
}

/** The UTF-16 code units of a Kotlin String (each `Char` is already a UTF-16 unit). */
private fun String.toUtf16CodeUnits(): List<Int> = map { it.code }
