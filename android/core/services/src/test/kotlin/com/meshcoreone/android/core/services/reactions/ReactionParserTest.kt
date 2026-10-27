// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ReactionParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.services.rendering.CharacterEmojiDetection
import com.meshcoreone.android.core.services.rendering.SwiftText
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ReactionParserTest {
    @TestFactory
    fun validFormatTests(): List<DynamicTest> = listOf(
        case("Parses simple reaction with thumbs up") {
            val result = assertNotNull(ReactionParser.parse("@[AlphaNode]👍\n7f3a9c12"))
            assertEquals("👍", result.emoji)
            assertEquals("AlphaNode", result.targetSender)
            assertEquals("7f3a9c12", result.messageHash)
        },
        case("Parses reaction with heart emoji") {
            val result = assertNotNull(ReactionParser.parse("@[BetaNode]❤\uFE0F\ne4d8b1a0"))
            assertEquals("❤\uFE0F", result.emoji)
            assertEquals("BetaNode", result.targetSender)
            assertEquals("e4d8b1a0", result.messageHash)
        },
        case("Parses reaction with uppercase identifier and normalizes to lowercase") {
            assertEquals("abcdef12", assertNotNull(ReactionParser.parse("@[Node]👍\nABCDEF12")).messageHash)
        },
        case("Parses reaction with mixed case identifier") {
            assertEquals("abcdef12", assertNotNull(ReactionParser.parse("@[Node]👍\nAbCdEf12")).messageHash)
        },
        case("Parses emoji-first reaction with thumbs up") {
            val result = assertNotNull(ReactionParser.parse("👍@[AlphaNode]\n7f3a9c12"))
            assertEquals("👍", result.emoji)
            assertEquals("AlphaNode", result.targetSender)
            assertEquals("7f3a9c12", result.messageHash)
        },
    )

    @TestFactory
    fun crockfordIdentifierTests(): List<DynamicTest> = listOf(
        case("Generates 8-character Crockford Base32 identifier") {
            val hash = ReactionParser.generateMessageHash("Hello", 1_704_067_200u)
            assertEquals(8, hash.length)
            assertTrue(hash.all { it in "0123456789abcdefghjkmnpqrstvwxyz" }, hash)
            // Swift-runtime value (docs/Reactions.md example).
            assertEquals("b45pc4ek", hash)
        },
        case("Same input produces same identifier") {
            assertEquals(
                ReactionParser.generateMessageHash("Hello", 1_704_067_200u),
                ReactionParser.generateMessageHash("Hello", 1_704_067_200u),
            )
        },
        case("Different text produces different identifier") {
            assertNotEquals(
                ReactionParser.generateMessageHash("Hello", 1_704_067_200u),
                ReactionParser.generateMessageHash("World", 1_704_067_200u),
            )
        },
        case("Different timestamp produces different identifier") {
            assertNotEquals(
                ReactionParser.generateMessageHash("Hello", 1_704_067_200u),
                ReactionParser.generateMessageHash("Hello", 1_704_067_201u),
            )
        },
        case("Crockford O is decoded as 0") {
            assertEquals("00000000", assertNotNull(ReactionParser.parse("@[Node]👍\nOOOOOOOO")).messageHash)
        },
        case("Crockford I/L are decoded as 1") {
            assertEquals("11111111", ReactionParser.parse("@[Node]👍\niiiiiiii")?.messageHash)
            assertEquals("11111111", ReactionParser.parse("@[Node]👍\nLLLLLLLL")?.messageHash)
        },
    )

    @TestFactory
    fun edgeAndInvalidFormatTests(): List<DynamicTest> = listOf(
        case("Parses sender name containing colon") {
            assertEquals("Node:Alpha", assertNotNull(ReactionParser.parse("@[Node:Alpha]👍\na1b2c3d4")).targetSender)
        },
        case("Returns nil for plain text message") { assertNull(ReactionParser.parse("Just a normal message")) },
        case("Returns nil for missing identifier") { assertNull(ReactionParser.parse("@[Node]👍")) },
        case("Returns nil for missing @ symbol") { assertNull(ReactionParser.parse("👍 [Node]\na1b2c3d4")) },
        case("Returns nil for missing brackets around sender") { assertNull(ReactionParser.parse("👍@Node\na1b2c3d4")) },
        case("Returns nil for invalid identifier length") { assertNull(ReactionParser.parse("@[Node]👍\nabc")) },
        case("Returns nil for invalid Crockford characters (U)") { assertNull(ReactionParser.parse("@[Node]👍\nuuuuuuuu")) },
        case("Returns nil for empty sender") { assertNull(ReactionParser.parse("@[]👍\na1b2c3d4")) },
        case("Returns nil for mention-first without emoji") { assertNull(ReactionParser.parse("@[Node]\na1b2c3d4")) },
        case("Returns nil for mention-first remainder that merely starts with emoji") {
            assertNull(ReactionParser.parse("@[Alice]🎉 on my way\npassword"))
        },
        case("Returns nil for emoji-first remainder that merely starts with emoji") {
            assertNull(ReactionParser.parse("🎉 on my way@[Alice]\npassword"))
        },
        case("Returns nil for DM remainder that merely starts with emoji") {
            assertNull(ReactionParser.parseDM("🎉 on my way\npassword"))
        },
        case("Parses mention-plus-single-emoji with Crockford last line as a reaction") {
            // Crockford maps i to 1, so the last line is a valid hash and this body matches the reaction format.
            val result = ReactionParser.parse("@[Alice]👍\nreceived")
            assertEquals("👍", result?.emoji)
            assertEquals("Alice", result?.targetSender)
            assertEquals("rece1ved", result?.messageHash)
        },
        case("Returns nil for emoji-first body not starting with emoji") { assertNull(ReactionParser.parse("A@[Node]\na1b2c3d4")) },
    )

    @TestFactory
    fun zwjEmojiTests(): List<DynamicTest> = listOf(
        case("Parses reaction with skin tone modifier") {
            assertEquals("👍🏽", assertNotNull(ReactionParser.parse("@[Node]👍🏽\na1b2c3d4")).emoji)
        },
        case("Parses reaction with family ZWJ emoji") {
            assertEquals("👨\u200D👩\u200D👧", assertNotNull(ReactionParser.parse("@[Node]👨\u200D👩\u200D👧\na1b2c3d4")).emoji)
        },
        case("Parses reaction with flag emoji") {
            assertEquals("🇺🇸", assertNotNull(ReactionParser.parse("@[Node]🇺🇸\na1b2c3d4")).emoji)
        },
    )

    @TestFactory
    fun summaryCacheTests(): List<DynamicTest> = listOf(
        case("Builds summary from reactions") {
            val summary = ReactionParser.buildSummary(listOf(ReactionCount("👍", 3), ReactionCount("❤\uFE0F", 2), ReactionCount("😂", 1)))
            assertEquals("👍:3,❤\uFE0F:2,😂:1", summary)
        },
        case("Parses summary string") {
            val parsed = ReactionParser.parseSummary("👍:3,❤\uFE0F:2,😂:1")
            assertEquals(3, parsed.size)
            assertEquals(ReactionCount("👍", 3), parsed[0])
            assertEquals(ReactionCount("❤\uFE0F", 2), parsed[1])
            assertEquals(ReactionCount("😂", 1), parsed[2])
        },
        case("Parses empty summary") { assertTrue(ReactionParser.parseSummary(null).isEmpty()) },
        case("Sorts summary by count descending") {
            val summary = ReactionParser.buildSummary(listOf(ReactionCount("😂", 1), ReactionCount("👍", 5), ReactionCount("❤\uFE0F", 3)))
            assertEquals("👍:5,❤\uFE0F:3,😂:1", summary)
        },
    )

    @TestFactory
    fun reactionDTOTests(): List<DynamicTest> = listOf(
        case("ReactionDTO can be created with contactID for DMs") {
            val contactID = UUID.randomUUID()
            val dto = ReactionDTO(
                messageID = UUID.randomUUID(),
                emoji = "👍",
                senderName = "TestNode",
                messageHash = "a1b2c3d4",
                rawText = "@[TestNode]👍\na1b2c3d4",
                contactID = contactID,
                radioId = RadioId(UUID.randomUUID()),
            )
            assertEquals(contactID, dto.contactID)
            assertNull(dto.channelIndex)
        },
        case("ReactionDTO can be created with channelIndex for channels") {
            val dto = ReactionDTO(
                messageID = UUID.randomUUID(),
                emoji = "👍",
                senderName = "TestNode",
                messageHash = "a1b2c3d4",
                rawText = "@[TestNode]👍\na1b2c3d4",
                channelIndex = 5u,
                radioId = RadioId(UUID.randomUUID()),
            )
            assertEquals(5u.toUByte(), dto.channelIndex)
            assertNull(dto.contactID)
        },
    )

    @TestFactory
    fun dmReactionFormatTests(): List<DynamicTest> = listOf(
        case("Parses DM reaction format without sender") {
            assertEquals(ParsedDMReaction("👍", "7f3a9c12"), ReactionParser.parseDM("👍\n7f3a9c12"))
        },
        case("Parses DM reaction with heart emoji") {
            assertEquals("❤\uFE0F", assertNotNull(ReactionParser.parseDM("❤\uFE0F\ne4d8b1a0")).emoji)
        },
        case("Returns nil for DM format missing hash") { assertNull(ReactionParser.parseDM("👍")) },
        case("DM parser rejects channel format") { assertNull(ReactionParser.parseDM("@[Node]👍\nabcd1234")) },
        case("Builds DM reaction text correctly") {
            val text = ReactionParser.buildDMReactionText("👍", "Hello world", 1_704_067_200u)
            assertTrue(text.startsWith("👍\n"))
            assertEquals(10, SwiftText.graphemeCount(text)) // emoji (grapheme cluster) + newline + 8 char hash
            assertFalse(text.contains("@["))
        },
        case("Parses DM reaction with uppercase hash and normalizes to lowercase") {
            assertEquals("abcdef12", assertNotNull(ReactionParser.parseDM("👍\nABCDEF12")).messageHash)
        },
        case("DM parser rejects invalid Crockford characters") { assertNull(ReactionParser.parseDM("👍\nuuuuuuuu")) },
        case("DM parser rejects non-emoji start") { assertNull(ReactionParser.parseDM("A\na1b2c3d4")) },
        case("DM parser handles skin tone modifier emoji") {
            assertEquals("👍🏽", assertNotNull(ReactionParser.parseDM("👍🏽\na1b2c3d4")).emoji)
        },
        case("DM round-trip: build then parse produces same emoji and hash") {
            val text = ReactionParser.buildDMReactionText("👍", "Hello world", 1_704_067_200u)
            val parsed = assertNotNull(ReactionParser.parseDM(text))
            assertEquals("👍", parsed.emoji)
            assertEquals(ReactionParser.generateMessageHash("Hello world", 1_704_067_200u), parsed.messageHash)
        },
    )

    @TestFactory
    fun channelRoundTrip(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-216::channel round-trip: build then parse yields the target sender, emoji and hash") {
            val text = ReactionService().buildReactionText("🎉", "Node With Spaces", "Hello", 1_704_067_200u)
            assertEquals(ParsedReaction("🎉", "Node With Spaces", ReactionParser.generateMessageHash("Hello", 1_704_067_200u)), ReactionParser.parse(text))
        },
    )

    @TestFactory
    fun graphemeClassification(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-216::doubled ZWJ starts another Character rather than a channel or DM reaction") {
            // GB9 attaches both ZWJs; GB11 cannot cross the second one because
            // ZWJ is not Extend. The pinned parser requires exactly one Character.
            assertReactionClassification("\uD83D\uDC4D\u200D\u200D\uD83D\uDC4D", accepted = false)
        },
        DynamicTest.dynamicTest("WP-216::GB11 context resets across arbitrary extra joiners and non-Extend scalars") {
            listOf(
                "\uD83D\uDC4D\u200D\u200D\u200D\uD83D\uDC4D",
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\u200D\uD83D\uDC67",
                "\uD83D\uDC4D\u200D\u0308\u200D\uD83D\uDC4D",
                "\uD83D\uDC4D\u0903\u200D\uD83D\uDC4D",
            ).forEach { assertReactionClassification(it, accepted = false) }
        },
        DynamicTest.dynamicTest("WP-216::GB11 keeps complete pictographic Extend star ZWJ chains") {
            listOf(
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
                "\uD83D\uDC4D\uD83C\uDFFD\u200D\uD83D\uDC4D",
                "\u2764\uFE0F\u200D\uD83D\uDD25",
                "\uD83D\uDC4D\u0308\uFE0F\u200D\uD83D\uDC4D",
            ).forEach { assertReactionClassification(it, accepted = true) }
        },
        DynamicTest.dynamicTest("WP-216::GB9 preserves trailing joiners without requiring a following pictograph") {
            listOf(
                "\uD83D\uDE00\u200D",
                "\uD83D\uDE00\u200D\u200D",
                "\uD83D\uDE00\u200D\u0308\u200D",
            ).forEach { assertReactionClassification(it, accepted = true) }
        },
        DynamicTest.dynamicTest("WP-216::flags skin tones and emoji in target names retain exact wire fields") {
            listOf("\uD83C\uDDFA\uD83C\uDDF8", "\uD83D\uDC4D\uD83C\uDFFD")
                .forEach { assertReactionClassification(it, accepted = true) }
            val modifier = "\uD83C\uDFFD"
            val mentionFirst = "@[Node]$modifier\nabcdefgh"
            val emojiFirst = "$modifier@[Node]\nabcdefgh"
            val direct = "$modifier\nabcdefgh"
            // GB9 joins the modifier to ']', so the pinned Character lookup cannot find that delimiter.
            assertNull(ReactionParser.parse(mentionFirst))
            assertFalse(ReactionParser.isReactionText(mentionFirst, isDM = false))
            assertEquals(ParsedReaction(modifier, "Node", "abcdefgh"), ReactionParser.parse(emojiFirst))
            assertTrue(ReactionParser.isReactionText(emojiFirst, isDM = false))
            assertEquals(ParsedDMReaction(modifier, "abcdefgh"), ReactionParser.parseDM(direct))
            assertTrue(ReactionParser.isReactionText(direct, isDM = true))
            assertEquals(
                ParsedReaction("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "Node\uD83D\uDD25", "abcdefgh"),
                ReactionParser.parse("@[Node\uD83D\uDD25]\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\nABCDEFGH"),
            )
        },
        DynamicTest.dynamicTest("WP-216::GB11 uses Extended Pictographic and Extend rather than the Emoji property") {
            assertTrue(CharacterEmojiDetection.hasEmojiProperty(0x1F1FA))
            assertFalse(ReactionGraphemeProperties.isExtendedPictographic(0x1F1FA))
            assertTrue(ReactionGraphemeProperties.isExtend(0x1F3FD))
            assertFalse(ReactionGraphemeProperties.isExtendedPictographic(0x1F3FD))
            assertTrue(ReactionGraphemeProperties.isExtend(0xFE0F))
            assertFalse(ReactionGraphemeProperties.isExtend(0x200D))
            assertFalse(ReactionGraphemeProperties.isExtend(0x0903))
            assertTrue(ReactionGraphemeProperties.isExtendedPictographic(0x1F44D))
        },
        DynamicTest.dynamicTest("WP-216::generated Extended Pictographic membership matches the independent JDK 21 Unicode baseline") {
            var members = 0
            for (scalar in 0..Character.MAX_CODE_POINT) {
                val expected = Character.isExtendedPictographic(scalar)
                assertEquals(expected, ReactionGraphemeProperties.isExtendedPictographic(scalar), "U+${scalar.toString(16)}")
                if (expected) members++
            }
            assertEquals(3537, members)
        },
        DynamicTest.dynamicTest("WP-216::generated Extend membership matches the independent official UCD 15 digest") {
            // SHA-256 of ascending Extend scalars, each encoded as four LE bytes,
            // calculated directly from the checksum-bound official UCD input.
            val digest = MessageDigest.getInstance("SHA-256")
            var members = 0
            for (scalar in 0..Character.MAX_CODE_POINT) {
                if (!ReactionGraphemeProperties.isExtend(scalar)) continue
                members++
                for (offset in 0..3) digest.update((scalar ushr (offset * 8)).toByte())
            }
            assertEquals(2130, members)
            assertEquals(
                "3a8199c1373e1bcebe32e029de62eacc351ecef2a8dc5faff767d4d5619b2e38",
                digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
            )
        },
    )

    private fun assertReactionClassification(emoji: String, accepted: Boolean) {
        val hash = "abcdefgh"
        val direct = "$emoji\n$hash"
        val channels = listOf("@[Node]$emoji\n$hash", "$emoji@[Node]\n$hash")
        if (accepted) {
            assertEquals(ParsedDMReaction(emoji, hash), ReactionParser.parseDM(direct))
            channels.forEach { assertEquals(ParsedReaction(emoji, "Node", hash), ReactionParser.parse(it)) }
        } else {
            assertNull(ReactionParser.parseDM(direct))
            channels.forEach { assertNull(ReactionParser.parse(it)) }
        }
        assertEquals(accepted, ReactionParser.isReactionText(direct, isDM = true))
        channels.forEach { assertEquals(accepted, ReactionParser.isReactionText(it, isDM = false)) }
    }

    private fun case(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("ReactionParserTests::$name()", body)
}
