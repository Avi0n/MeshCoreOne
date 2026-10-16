package one.meshcore.shared.chat.reactions

import one.meshcore.shared.crypto.sha256

/** A parsed PocketMesh channel reaction: the emoji, the target message's sender, and its 8-char hash. */
data class ParsedReaction(val emoji: String, val targetSender: String, val messageHash: String)

/** A parsed PocketMesh DM reaction: the emoji and the target message's 8-char hash (no sender field). */
data class ParsedDmReaction(val emoji: String, val messageHash: String)

/**
 * PocketMesh reaction wire format. A reaction is an ordinary text message: channel bodies are
 * `@[sender]{emoji}\n{hash}` (mention-first, what this app sends) or `{emoji}@[sender]\n{hash}`
 * (emoji-first, still parsed so older peers interoperate); DMs are `{emoji}\n{hash}`.
 * `{emoji}` is exactly one grapheme so mention-plus-text stays chat. Port of Swift
 * `ReactionParser`, byte-for-byte on SHA-256 + little-endian-uint32 + Crockford Base32.
 */
object ReactionParser {
    /** Whether [text] matches any known reaction format (PocketMesh or meshcore-open v3/v1). */
    fun isReactionText(text: String, isDM: Boolean): Boolean {
        if (MeshCoreOpenReactionParser.parse(text) != null) return true
        if (MeshCoreOpenReactionParser.parseV1(text) != null) return true
        return if (isDM) parseDM(text) != null else parse(text) != null
    }

    /**
     * Whether [text] is a reaction in the PocketMesh grammar for its conversation kind — the only form
     * this app sends. The timeline filter and the conversation-list preview both hide an already-sent
     * outgoing reaction with this test, so a reader never sees raw wire text where a message belongs.
     */
    fun isSentReactionText(text: String, isDM: Boolean): Boolean =
        if (isDM) parseDM(text) != null else parse(text) != null

    /** Parses a channel reaction; null if the format does not match. Accepts mention-first and emoji-first. */
    fun parse(text: String): ParsedReaction? {
        val split = ChannelText.splitHash(text) ?: return null
        return ChannelText.reactionFromMention(split)
    }

    /** Parses a DM reaction (`{emoji}\n{hash}`); rejects the channel format and returns null on mismatch. */
    fun parseDM(text: String): ParsedDmReaction? = DmText.split(text)

    /** Builds channel reaction wire text: `@[{sender}]{emoji}\n{hash}`. */
    fun buildReactionText(emoji: String, targetSender: String, targetText: String, targetTimestamp: Long): String {
        val hash = generateMessageHash(targetText, targetTimestamp)
        return "@[$targetSender]$emoji\n$hash"
    }

    /** Builds DM reaction wire text: `{emoji}\n{hash}`. */
    fun buildDmReactionText(emoji: String, targetText: String, targetTimestamp: Long): String {
        val hash = generateMessageHash(targetText, targetTimestamp)
        return "$emoji\n$hash"
    }

    /**
     * The 8-char Crockford Base32 message hash: `SHA256(utf8(text) + littleEndianUInt32(timestamp))`, first
     * 5 bytes packed MSB-first. Sender and receiver must agree on [timestamp] (the wire send time), so
     * callers pass `MessageDto.reactionTimestamp`. [timestamp] is masked to 32 bits before the LE append.
     */
    fun generateMessageHash(text: String, timestamp: Long): String {
        val textBytes = text.encodeToByteArray()
        val ts = timestamp and UINT32_MASK
        val data = ByteArray(textBytes.size + UINT32_BYTES)
        textBytes.copyInto(data)
        var offset = textBytes.size
        for (shift in 0 until UINT32_BYTES) {
            data[offset++] = ((ts shr (shift * BITS_PER_BYTE)) and BYTE_MASK).toByte()
        }
        val digest = sha256(data)
        return CrockfordBase32.encode(digest, HASH_BYTE_COUNT)
    }

    /** Builds the `"👍:3,❤️:2"` summary from pre-counted pairs, sorted by count descending. */
    fun buildSummary(counts: List<Pair<String, Int>>): String =
        counts.sortedByDescending { it.second }.joinToString(",") { "${it.first}:${it.second}" }

    /**
     * Builds the summary from reaction rows: group by emoji, sort by count descending then by earliest
     * `receivedAt` ascending as the tie-break, join `"{emoji}:{count}"` with commas.
     */
    fun buildSummaryFromReactions(reactions: List<ReactionRow>): String {
        val grouped = reactions.groupBy { it.emoji }
        return grouped
            .map { (emoji, items) ->
                Triple(emoji, items.size, items.minOf { it.receivedAt })
            }
            .sortedWith(compareByDescending<Triple<String, Int, Double>> { it.second }.thenBy { it.third })
            .joinToString(",") { "${it.first}:${it.second}" }
    }

    /**
     * Parses a summary string into emoji/count pairs. Null or empty yields an empty list; a split with
     * other than exactly two `:`-parts or a non-integer count is dropped. Emoji containing `:` is not
     * defended and duplicates are not merged, matching the Swift `parseSummary`.
     */
    fun parseSummary(summary: String?): List<Pair<String, Int>> {
        if (summary.isNullOrEmpty()) return emptyList()
        return summary.split(",").mapNotNull { part ->
            val components = part.split(":")
            if (components.size != SUMMARY_PAIR_PARTS) return@mapNotNull null
            val count = components[1].toIntOrNull() ?: return@mapNotNull null
            components[0] to count
        }
    }

    private object ChannelText {
        fun splitHash(text: String): HashSplit? {
            val newlineIndex = text.lastIndexOf('\n').takeIf { it >= 0 } ?: return null
            val rawHash = text.substring(newlineIndex + 1)
                .takeIf { it.length == HASH_LENGTH && CrockfordBase32.isValid(it) }
                ?: return null
            return HashSplit(
                messageHash = CrockfordBase32.normalize(rawHash),
                withoutHash = text.substring(0, newlineIndex),
            )
        }

        fun reactionFromMention(split: HashSplit): ParsedReaction? {
            val at = split.withoutHash.indexOf(AT_BRACKET).takeIf { it >= 0 } ?: return null
            val fields = mentionFields(
                split.withoutHash.substring(0, at),
                split.withoutHash.substring(at + AT_BRACKET.length),
            ) ?: return null
            if (!isReactionEmoji(fields.emoji) || fields.sender.isEmpty()) return null
            return ParsedReaction(
                emoji = fields.emoji,
                targetSender = fields.sender,
                messageHash = split.messageHash,
            )
        }

        private fun mentionFields(before: String, after: String): MentionFields? {
            if (before.isEmpty()) return mentionFirst(after)
            return emojiFirst(before, after)
        }

        private fun mentionFirst(after: String): MentionFields? {
            val close = after.indexOf(']').takeIf { it >= 0 } ?: return null
            return MentionFields(sender = after.substring(0, close), emoji = after.substring(close + 1))
        }

        private fun emojiFirst(before: String, after: String): MentionFields? {
            if (!after.endsWith("]")) return null
            return MentionFields(sender = after.dropLast(1), emoji = before)
        }

        class HashSplit(val messageHash: String, val withoutHash: String)

        class MentionFields(val sender: String, val emoji: String)
    }

    private object DmText {
        fun split(text: String): ParsedDmReaction? {
            if (text.contains(AT_BRACKET)) return null
            val newlineIndex = text.lastIndexOf('\n').takeIf { it >= 0 } ?: return null
            val rawHash = text.substring(newlineIndex + 1)
                .takeIf { it.length == HASH_LENGTH && CrockfordBase32.isValid(it) }
                ?: return null
            val emoji = text.substring(0, newlineIndex).takeIf { isReactionEmoji(it) } ?: return null
            return ParsedDmReaction(emoji = emoji, messageHash = CrockfordBase32.normalize(rawHash))
        }
    }

    private const val AT_BRACKET = "@["
    private const val HASH_LENGTH = 8
    private const val HASH_BYTE_COUNT = 5
    private const val UINT32_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val BYTE_MASK = 0xFFL
    private const val SUMMARY_PAIR_PARTS = 2
}

/** The subset of a persisted reaction the summary builder reads (`receivedAt` is epoch seconds). */
data class ReactionRow(val emoji: String, val receivedAt: Double)
