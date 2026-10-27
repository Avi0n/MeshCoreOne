// AndroidOnly: WP-216 pins WP-213's local ReactionWireFormat stand-in to the canonical ReactionParser port so it can later delegate.
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.services.rendering.MessageLRUCache
import com.meshcoreone.android.core.services.rendering.ReactionWireFormat
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * WP-213 shipped `rendering.ReactionWireFormat` (hash, channel and DM parsers) before this canonical port
 * existed. These checks feed both the same inputs — every oracle vector plus a seeded corpus built from the
 * delimiter, newline, combining-mark, emoji and Crockford pieces the parsers branch on — and require identical
 * results, so `ReactionWireFormat` can become a thin delegate without behavior change.
 */
class ReactionWireFormatAgreementTest {
    private val pieces = listOf(
        "@", "[", "]", "@[", "\n", "\r", "\r\n", "\u0301", "\u0341", "\uFE0F", "\u200D", "\u20E3", " ", ":", ",",
        "A", "Alice", "e\u0301", "É", "#", "1", "©", "⌚", "⏩", "❤", "👍", "🏽", "🇺", "🇸", "👨\u200D👩\u200D👧", "🎉 on my way",
        "abcdefgh", "ABCDEFGH", "OoIiLl01", "uuuuuuuu", "abcdefg", "abcdefghi", "\uFF21", "\uFEFF", "क\u094Dष",
    )

    private fun corpus(): List<String> {
        val random = Random(216)
        val generated = List(GENERATED_INPUTS) {
            val length = random.nextInt(1, MAX_PIECES + 1)
            (0 until length).joinToString("") { pieces[random.nextInt(pieces.size)] }
        }
        // Every combination of the real wire shapes, so plenty of inputs parse successfully.
        val emojis = listOf("👍", "❤\uFE0F", "👍🏽", "🇺🇸", "#\u0301", "1\uFE0F\u20E3", "x", "👍👍", "©", "⏩")
        val senders = listOf("Alice", "", "A]B", "@[B", "e\u0301", "Node:1", "\u0301A")
        val hashes = listOf("abcdefgh", "OOIILL00", "ABCDEFGH", "abcdefgu", "abcdefg", "abcdef\u01F5")
        val shaped = emojis.flatMap { emoji ->
            senders.flatMap { sender ->
                hashes.flatMap { hash ->
                    listOf("@[$sender]$emoji\n$hash", "$emoji@[$sender]\n$hash", "$emoji\n$hash", "$emoji\r\n$hash", "$sender\n$emoji\n$hash")
                }
            }
        }
        val oracle = ReactionsSwiftOracleVectors.channelParse.map { it.first } + ReactionsSwiftOracleVectors.directParse.map { it.first } +
            ReactionsSwiftOracleVectors.isReactionText.map { it.first }
        val boundaryEmojis = listOf(
            "\uD83D\uDC4D\u200D\u200D\uD83D\uDC4D",
            "\uD83D\uDC4D\u200D\u200D\u200D\uD83D\uDC4D",
            "\uD83D\uDC4D\u200D\u0308\u200D\uD83D\uDC4D",
            "\uD83D\uDC4D\u0903\u200D\uD83D\uDC4D",
            "\uD83D\uDC4D\uD83C\uDFFD\u200D\uD83D\uDC4D",
            "\uD83D\uDE00\u200D", "\uD83D\uDE00\u200D\u200D",
        )
        val boundaries = boundaryEmojis.flatMap { emoji ->
            listOf("@[Node]$emoji\nabcdefgh", "$emoji@[Node]\nabcdefgh", "$emoji\nabcdefgh")
        } + "@[Node\uD83D\uDD25]\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\nABCDEFGH"
        return (oracle + generated + shaped + boundaries).distinct()
    }

    @TestFactory
    fun agreement(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-216::ReactionWireFormat.parse agrees with ReactionParser.parse on the oracle and seeded corpus") {
            val inputs = corpus()
            val mismatches = inputs.filter { ReactionWireFormat.parse(it) != ReactionParser.parse(it) }
            if (mismatches.isNotEmpty()) fail("parse disagrees for ${mismatches.size} inputs: ${mismatches.take(10).map(::escape)}")
            assertTrue(inputs.count { ReactionParser.parse(it) != null } >= MIN_CHANNEL_PARSED, "corpus must exercise successful parses")
        },
        DynamicTest.dynamicTest("WP-216::ReactionWireFormat.parseDM agrees with ReactionParser.parseDM on the oracle and seeded corpus") {
            val inputs = corpus()
            val mismatches = inputs.filter { input ->
                val canonical = ReactionParser.parseDM(input)?.let { it.emoji to it.messageHash }
                ReactionWireFormat.parseDM(input) != canonical
            }
            if (mismatches.isNotEmpty()) fail("parseDM disagrees for ${mismatches.size} inputs: ${mismatches.take(10).map(::escape)}")
            assertTrue(inputs.count { ReactionParser.parseDM(it) != null } >= MIN_DM_PARSED, "corpus must exercise successful DM parses")
        },
        DynamicTest.dynamicTest("WP-216::ReactionWireFormat.generateMessageHash agrees with ReactionParser.generateMessageHash") {
            val random = Random(2_160)
            val stamps = listOf(0u, 1u, 255u, 256u, 65_535u, 1_704_067_200u, Int.MAX_VALUE.toUInt(), 2_147_483_648u, UInt.MAX_VALUE) +
                List(STAMP_SAMPLES) { random.nextInt().toUInt() }
            val texts = corpus() + listOf("", "Hello", "café", "cafe\u0301", "𝄞", "\u0000", "x".repeat(LONG_TEXT))
            val mismatches = texts.flatMap { text -> stamps.map { text to it } }
                .filter { (text, stamp) -> ReactionWireFormat.generateMessageHash(text, stamp) != ReactionParser.generateMessageHash(text, stamp) }
            if (mismatches.isNotEmpty()) fail("hash disagrees for ${mismatches.size} inputs: ${mismatches.take(10)}")
        },
        DynamicTest.dynamicTest("WP-216::MessageLRUCache keys built by WP-213 are found with ReactionParser hashes") {
            val cache = MessageLRUCache()
            val messageID = UUID.randomUUID()
            cache.index(messageID, 2u, "e\u0301", "Hello world", 1_704_067_200u)
            val hash = ReactionParser.generateMessageHash("Hello world", 1_704_067_200u)
            assertEquals(listOf(messageID), cache.lookup(2u, "é", hash).map { it.messageID })
        },
    )

    private fun escape(value: String): String = value.map { c -> if (c.code in 0x20..0x7E) c.toString() else "\\u%04X".format(c.code) }.joinToString("")

    private companion object {
        const val GENERATED_INPUTS = 4_000
        const val MAX_PIECES = 8
        const val MIN_CHANNEL_PARSED = 100
        const val MIN_DM_PARSED = 20
        const val STAMP_SAMPLES = 6
        const val LONG_TEXT = 4_096
    }
}
