// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionParser.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.services.rendering.CharacterEmojiDetection
import com.meshcoreone.android.core.services.rendering.SwiftText
import java.security.MessageDigest
import java.time.Instant

/** Parsed DM reaction data (shorter format without sender). [messageHash] is 8 lowercase Crockford Base32 chars. */
data class ParsedDMReaction(val emoji: String, val messageHash: String)

/** One `emoji:count` entry of a reaction summary (Swift `(emoji: String, count: Int)`; Swift `Int` is 64-bit). */
data class ReactionCount(val emoji: String, val count: Long)

/**
 * Parses channel reaction wire text: `@[sender]emoji` or `emoji@[sender]`, then newline and 8-character
 * Crockford hash. Both orders match so mention-first and emoji-first peers interoperate.
 *
 * Swift operates on `Character`s (extended grapheme clusters); every positional test here does the same
 * through [SwiftText.graphemes], so combining marks glued to `@`, `[`, `]`, `,` or `:` never count as
 * those delimiters. Expected values in the tests come from the frozen Swift sources run on Swift 6.3.2.
 */
object ReactionParser {
    private const val CROCKFORD_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
    private const val HASH_LENGTH = 8
    private const val HASH_BYTES = 5
    private const val TIMESTAMP_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_SYMBOL = 5
    private const val SYMBOL_MASK = 0x1FL
    private const val BYTE_MASK = 0xFFL
    private const val ASCII_LIMIT = 128
    private const val MENTION_OPEN = "@"
    private const val MENTION_BRACKET = "["
    private const val MENTION_CLOSE = "]"
    private const val SUMMARY_SEPARATOR = ","
    private const val SUMMARY_PAIR_SEPARATOR = ":"
    private const val ZERO_WIDTH_JOINER = 0x200D

    /** Crockford decode table: ASCII code to 5-bit value, -1 when invalid. Case-insensitive; O→0, I/L→1. */
    private val CROCKFORD_DECODE: IntArray = IntArray(ASCII_LIMIT) { code ->
        when (val character = code.toChar()) {
            'O', 'o' -> 0
            'I', 'i', 'L', 'l' -> 1
            else -> CROCKFORD_ALPHABET.indexOf(character.lowercaseChar())
        }
    }

    /** Returns true if the text matches any known reaction format (PocketMesh or meshcore-open). */
    fun isReactionText(text: String, isDM: Boolean): Boolean {
        if (MeshCoreOpenReactionParser.parse(text) != null) return true
        if (MeshCoreOpenReactionParser.parseV1(text) != null) return true
        return if (isDM) parseDM(text) != null else parse(text) != null
    }

    /** Parses channel reaction text; null if the wire format does not match. */
    fun parse(text: String): ParsedReaction? {
        val (withoutHash, messageHash) = splitHash(text) ?: return null
        val characters = SwiftText.graphemes(withoutHash)
        val atBracket = mentionOpenIndex(characters)
        if (atBracket < 0) return null
        val beforeMention = characters.subList(0, atBracket)
        val afterAtBracket = characters.subList(atBracket + 2, characters.size)

        val emoji: String
        val sender: String
        if (beforeMention.isEmpty()) {
            val closeBracket = afterAtBracket.indexOf(MENTION_CLOSE)
            if (closeBracket < 0) return null
            sender = afterAtBracket.subList(0, closeBracket).joinToString("")
            emoji = afterAtBracket.subList(closeBracket + 1, afterAtBracket.size).joinToString("")
        } else {
            emoji = beforeMention.joinToString("")
            if (afterAtBracket.lastOrNull() != MENTION_CLOSE) return null
            sender = afterAtBracket.subList(0, afterAtBracket.size - 1).joinToString("")
        }

        if (!isReactionEmoji(emoji)) return null
        if (sender.isEmpty()) return null
        return ParsedReaction(emoji = emoji, targetSender = sender, messageHash = messageHash)
    }

    /** Parses DM reaction text: `{emoji}\n{hash}` with no sender field; null if the format doesn't match. */
    fun parseDM(text: String): ParsedDMReaction? {
        // Reject channel format (contains `@[`).
        if (mentionOpenIndex(SwiftText.graphemes(text)) >= 0) return null
        val (emoji, messageHash) = splitHash(text) ?: return null
        if (!isReactionEmoji(emoji)) return null
        return ParsedDMReaction(emoji = emoji, messageHash = messageHash)
    }

    /** Builds DM reaction text in wire format: `{emoji}\n{hash}`. */
    internal fun buildDMReactionText(emoji: String, targetText: String, targetTimestamp: UInt): String =
        "$emoji\n${generateMessageHash(targetText, targetTimestamp)}"

    /**
     * Generates the message identifier for the reaction wire format: SHA-256 over the UTF-8 text followed
     * by the little-endian 32-bit timestamp, first 5 bytes as 8 lowercase Crockford Base32 characters.
     */
    fun generateMessageHash(text: String, timestamp: UInt): String {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val stamp = timestamp.toLong()
        val input = ByteArray(textBytes.size + TIMESTAMP_BYTES) { index ->
            if (index < textBytes.size) textBytes[index]
            else (stamp ushr ((index - textBytes.size) * BITS_PER_BYTE)).toByte()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val bits = (0 until HASH_BYTES).fold(0L) { acc, index -> (acc shl BITS_PER_BYTE) or (digest[index].toLong() and BYTE_MASK) }
        return buildString(HASH_LENGTH) {
            for (shift in (HASH_BYTES * BITS_PER_BYTE - BITS_PER_SYMBOL) downTo 0 step BITS_PER_SYMBOL) {
                append(CROCKFORD_ALPHABET[((bits ushr shift) and SYMBOL_MASK).toInt()])
            }
        }
    }

    /** Builds the summary string from emoji counts, sorted by count descending (stable for ties). */
    internal fun buildSummary(reactions: List<ReactionCount>): String =
        reactions.sortedByDescending { it.count }.joinToString(SUMMARY_SEPARATOR) { "${it.emoji}:${it.count}" }

    /**
     * Builds the summary string from reaction DTOs: count descending, then earliest `receivedAt` ascending.
     * Like Swift's `Dictionary(grouping:by: \.emoji)`, canonically equivalent emoji share one group that is
     * spelled as its first member. Groups tied on count and earliest time keep first-appearance order (Swift
     * leaves that order to its per-process dictionary hashing).
     */
    internal fun buildSummary(reactions: Collection<ReactionDTO>): String {
        val groups = reactions.groupBy { SwiftText.canonical(it.emoji) }.values
        return groups
            .map { items -> SummaryGroup(items.first().emoji, items.size, items.minOf { it.receivedAt }) }
            .sortedWith(compareByDescending<SummaryGroup> { it.count }.thenBy { it.earliest })
            .joinToString(SUMMARY_SEPARATOR) { "${it.emoji}:${it.count}" }
    }

    /** Parses a summary string into emoji/count pairs; malformed entries are skipped. */
    fun parseSummary(summary: String?): List<ReactionCount> {
        if (summary.isNullOrEmpty()) return emptyList()
        return splitCharacters(SwiftText.graphemes(summary), SUMMARY_SEPARATOR).mapNotNull { part ->
            val components = splitCharacters(part, SUMMARY_PAIR_SEPARATOR)
            if (components.size != 2) return@mapNotNull null
            val count = SwiftIntegerText.int64(components[1].joinToString("")) ?: return@mapNotNull null
            ReactionCount(components[0].joinToString(""), count)
        }
    }

    private data class SummaryGroup(val emoji: String, val count: Int, val earliest: Instant)

    /** True when [value] is exactly one emoji `Character`. */
    private fun isReactionEmoji(value: String): Boolean {
        val characters = SwiftText.graphemes(value)
        return characters.size == 1 && CharacterEmojiDetection.isEmoji(characters[0]) &&
            preservesEmojiJoinerBoundaries(value)
    }

    /**
     * GB11 joins only Extended_Pictographic Extend* ZWJ + Extended_Pictographic.
     * JDK 21's \X retains the initial pictographic context across non-Extend
     * scalars, including another ZWJ. Do not mistake the resulting run for one
     * Swift Character. Trailing ZWJs still attach under GB9 and remain valid.
     */
    private fun preservesEmojiJoinerBoundaries(value: String): Boolean {
        var pictographicPrefix = false
        var joinerHasPictographicPrefix = false
        var previousWasJoiner = false
        var offset = 0
        while (offset < value.length) {
            val scalar = value.codePointAt(offset)
            when {
                ReactionGraphemeProperties.isExtendedPictographic(scalar) -> {
                    if (previousWasJoiner && !joinerHasPictographicPrefix) return false
                    pictographicPrefix = true
                }
                ReactionGraphemeProperties.isExtend(scalar) -> Unit
                else -> {
                    joinerHasPictographicPrefix = scalar == ZERO_WIDTH_JOINER && pictographicPrefix
                    pictographicPrefix = false
                }
            }
            previousWasJoiner = scalar == ZERO_WIDTH_JOINER
            offset += Character.charCount(scalar)
        }
        return true
    }

    /**
     * Splits at the last newline `Character` into (text before it, normalized hash). An LF that is the second
     * half of a CRLF grapheme is not a newline `Character` in Swift, and any earlier LF would leave that CRLF
     * inside the hash where it can never validate, so both cases reject. The 8 hash `Character`s must each be
     * one ASCII Crockford symbol, which makes the grapheme count equal the UTF-16 length.
     */
    private fun splitHash(text: String): Pair<String, String>? {
        val newline = text.lastIndexOf('\n')
        if (newline < 0 || (newline > 0 && text[newline - 1] == '\r')) return null
        val rawHash = text.substring(newline + 1)
        if (rawHash.length != HASH_LENGTH || !rawHash.all(::isCrockfordSymbol)) return null
        val normalized = rawHash.map { CROCKFORD_ALPHABET[CROCKFORD_DECODE[it.code]] }.joinToString("")
        return text.substring(0, newline) to normalized
    }

    private fun isCrockfordSymbol(character: Char): Boolean = character.code < ASCII_LIMIT && CROCKFORD_DECODE[character.code] >= 0

    /** Grapheme-aware `range(of: "@[")`: an `@` Character immediately followed by a `[` Character. */
    private fun mentionOpenIndex(characters: List<String>): Int =
        (0 until characters.size - 1).firstOrNull { characters[it] == MENTION_OPEN && characters[it + 1] == MENTION_BRACKET } ?: -1

    /** Swift `split(separator:)` over Characters: drops empty pieces. */
    private fun splitCharacters(characters: List<String>, separator: String): List<List<String>> {
        val pieces = ArrayList<List<String>>()
        var start = 0
        for (index in 0..characters.size) {
            if (index == characters.size || characters[index] == separator) {
                if (index > start) pieces.add(characters.subList(start, index))
                start = index + 1
            }
        }
        return pieces
    }
}
