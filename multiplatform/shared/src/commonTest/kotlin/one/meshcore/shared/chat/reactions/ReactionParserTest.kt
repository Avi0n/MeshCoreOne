package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of the Swift `ReactionParserTests`: PocketMesh channel/DM grammar, Crockford, and the summary. */
class ReactionParserTest {
    // MARK: - Valid channel format

    @Test
    fun parsesSimpleReactionWithThumbsUp() {
        val result = ReactionParser.parse("👍@[AlphaNode]\n7f3a9c12")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals("AlphaNode", result.targetSender)
        assertEquals("7f3a9c12", result.messageHash)
    }

    @Test
    fun parsesReactionWithHeartEmoji() {
        val result = ReactionParser.parse("❤️@[BetaNode]\ne4d8b1a0")
        assertNotNull(result)
        assertEquals("❤️", result.emoji)
        assertEquals("BetaNode", result.targetSender)
        assertEquals("e4d8b1a0", result.messageHash)
    }

    @Test
    fun normalizesUppercaseIdentifierToLowercase() {
        assertEquals("abcdef12", ReactionParser.parse("👍@[Node]\nABCDEF12")?.messageHash)
    }

    @Test
    fun normalizesMixedCaseIdentifier() {
        assertEquals("abcdef12", ReactionParser.parse("👍@[Node]\nAbCdEf12")?.messageHash)
    }

    // MARK: - Crockford identifier

    @Test
    fun generatesEightCharCrockfordIdentifier() {
        val hash = ReactionParser.generateMessageHash("Hello", 1_704_067_200)
        assertEquals(8, hash.length)
        val valid = "0123456789abcdefghjkmnpqrstvwxyz".toSet()
        assertTrue(hash.all { it in valid })
    }

    @Test
    fun sameInputProducesSameIdentifier() {
        assertEquals(
            ReactionParser.generateMessageHash("Hello", 1_704_067_200),
            ReactionParser.generateMessageHash("Hello", 1_704_067_200),
        )
    }

    @Test
    fun differentTextProducesDifferentIdentifier() {
        assertTrue(
            ReactionParser.generateMessageHash("Hello", 1_704_067_200) !=
                ReactionParser.generateMessageHash("World", 1_704_067_200),
        )
    }

    @Test
    fun differentTimestampProducesDifferentIdentifier() {
        assertTrue(
            ReactionParser.generateMessageHash("Hello", 1_704_067_200) !=
                ReactionParser.generateMessageHash("Hello", 1_704_067_201),
        )
    }

    @Test
    fun crockfordVectorsMatchIndependentCrossCheck() {
        // Values independently computed in Python (SHA256 + LE-uint32 + Crockford, first 5 bytes).
        assertEquals("b45pc4ek", ReactionParser.generateMessageHash("Hello", 1_704_067_200))
        assertEquals("vp1vxnf4", ReactionParser.generateMessageHash("World", 1_704_067_200))
        assertEquals("7a6r3xy1", ReactionParser.generateMessageHash("Hello world", 1_704_067_200))
    }

    @Test
    fun crockfordOIsDecodedAsZero() {
        assertEquals("00000000", ReactionParser.parse("👍@[Node]\nOOOOOOOO")?.messageHash)
    }

    @Test
    fun crockfordIandLDecodedAsOne() {
        assertEquals("11111111", ReactionParser.parse("👍@[Node]\niiiiiiii")?.messageHash)
        assertEquals("11111111", ReactionParser.parse("👍@[Node]\nLLLLLLLL")?.messageHash)
    }

    // MARK: - Edge cases

    @Test
    fun parsesSenderNameContainingColon() {
        assertEquals("Node:Alpha", ReactionParser.parse("👍@[Node:Alpha]\na1b2c3d4")?.targetSender)
    }

    // MARK: - Invalid channel format

    @Test
    fun returnsNullForPlainText() = assertNull(ReactionParser.parse("Just a normal message"))

    @Test
    fun returnsNullForMissingIdentifier() = assertNull(ReactionParser.parse("👍@[Node]"))

    @Test
    fun returnsNullForMissingAtSymbol() = assertNull(ReactionParser.parse("👍 [Node]\na1b2c3d4"))

    @Test
    fun returnsNullForMissingBrackets() = assertNull(ReactionParser.parse("👍@Node\na1b2c3d4"))

    @Test
    fun returnsNullForInvalidIdentifierLength() = assertNull(ReactionParser.parse("👍@[Node]\nabc"))

    @Test
    fun returnsNullForInvalidCrockfordU() = assertNull(ReactionParser.parse("👍@[Node]\nuuuuuuuu"))

    @Test
    fun returnsNullForEmptySender() = assertNull(ReactionParser.parse("👍@[]\na1b2c3d4"))

    @Test
    fun returnsNullForNonEmojiStart() = assertNull(ReactionParser.parse("A@[Node]\na1b2c3d4"))

    @Test
    fun returnsNullForMentionFirstWithoutEmoji() = assertNull(ReactionParser.parse("@[Node]\na1b2c3d4"))

    @Test
    fun returnsNullForMentionFirstRemainderThatMerelyStartsWithEmoji() =
        assertNull(ReactionParser.parse("@[Alice]🎉 on my way\npassword"))

    @Test
    fun returnsNullForEmojiFirstRemainderThatMerelyStartsWithEmoji() =
        assertNull(ReactionParser.parse("🎉 on my way@[Alice]\npassword"))

    @Test
    fun returnsNullForDmRemainderThatMerelyStartsWithEmoji() =
        assertNull(ReactionParser.parseDM("🎉 on my way\npassword"))

    @Test
    fun parsesMentionPlusSingleEmojiWithCrockfordLastLine() {
        val result = ReactionParser.parse("@[Alice]👍\nreceived")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals("Alice", result.targetSender)
        assertEquals("rece1ved", result.messageHash)
    }

    @Test
    fun parsesMentionFirstThumbsUp() {
        val result = ReactionParser.parse("@[AlphaNode]👍\n7f3a9c12")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals("AlphaNode", result.targetSender)
        assertEquals("7f3a9c12", result.messageHash)
    }

    @Test
    fun buildsMentionFirstChannelWireText() {
        val text = ReactionParser.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
        assertTrue(text.startsWith("@[AlphaNode]👍\n"))
        val parsed = ReactionParser.parse(text)
        assertNotNull(parsed)
        assertEquals("👍", parsed.emoji)
        assertEquals("AlphaNode", parsed.targetSender)
    }

    // MARK: - ZWJ / skin-tone / flag

    @Test
    fun parsesReactionWithSkinToneModifier() {
        assertEquals("👍🏽", ReactionParser.parse("👍🏽@[Node]\na1b2c3d4")?.emoji)
    }

    @Test
    fun parsesReactionWithFamilyZwjEmoji() {
        assertEquals("👨‍👩‍👧", ReactionParser.parse("👨‍👩‍👧@[Node]\na1b2c3d4")?.emoji)
    }

    @Test
    fun parsesReactionWithFlagEmoji() {
        assertEquals("🇺🇸", ReactionParser.parse("🇺🇸@[Node]\na1b2c3d4")?.emoji)
    }

    // MARK: - Summary

    @Test
    fun buildsSummaryFromReactions() {
        val summary = ReactionParser.buildSummary(listOf("👍" to 3, "❤️" to 2, "😂" to 1))
        assertEquals("👍:3,❤️:2,😂:1", summary)
    }

    @Test
    fun parsesSummaryString() {
        val parsed = ReactionParser.parseSummary("👍:3,❤️:2,😂:1")
        assertEquals(listOf("👍" to 3, "❤️" to 2, "😂" to 1), parsed)
    }

    @Test
    fun parsesEmptySummary() = assertTrue(ReactionParser.parseSummary(null).isEmpty())

    @Test
    fun sortsSummaryByCountDescending() {
        val summary = ReactionParser.buildSummary(listOf("😂" to 1, "👍" to 5, "❤️" to 3))
        assertEquals("👍:5,❤️:3,😂:1", summary)
    }

    @Test
    fun buildsSummaryFromRowsWithEarliestReceivedAtTieBreak() {
        // Two emojis tie at count 2; the one whose earliest row is older sorts first.
        val rows = listOf(
            ReactionRow("❤️", receivedAt = 30.0),
            ReactionRow("❤️", receivedAt = 40.0),
            ReactionRow("👍", receivedAt = 10.0),
            ReactionRow("👍", receivedAt = 50.0),
        )
        assertEquals("👍:2,❤️:2", ReactionParser.buildSummaryFromReactions(rows))
    }

    // MARK: - DM format

    @Test
    fun parsesDmReactionWithoutSender() {
        val result = ReactionParser.parseDM("👍\n7f3a9c12")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals("7f3a9c12", result.messageHash)
    }

    @Test
    fun returnsNullForDmMissingHash() = assertNull(ReactionParser.parseDM("👍"))

    @Test
    fun dmParserRejectsChannelFormat() = assertNull(ReactionParser.parseDM("👍@[Node]\nabcd1234"))

    @Test
    fun buildsDmReactionText() {
        val text = ReactionParser.buildDmReactionText("👍", "Hello world", 1_704_067_200)
        assertTrue(text.startsWith("👍\n"))
        assertTrue(!text.contains("@["))
    }

    @Test
    fun normalizesDmUppercaseHash() {
        assertEquals("abcdef12", ReactionParser.parseDM("👍\nABCDEF12")?.messageHash)
    }

    @Test
    fun dmParserRejectsInvalidCrockford() = assertNull(ReactionParser.parseDM("👍\nuuuuuuuu"))

    @Test
    fun dmParserRejectsNonEmojiStart() = assertNull(ReactionParser.parseDM("A\na1b2c3d4"))

    @Test
    fun dmParserHandlesSkinToneModifier() {
        assertEquals("👍🏽", ReactionParser.parseDM("👍🏽\na1b2c3d4")?.emoji)
    }

    @Test
    fun dmRoundTripBuildThenParse() {
        val text = ReactionParser.buildDmReactionText("👍", "Hello world", 1_704_067_200)
        val parsed = ReactionParser.parseDM(text)
        assertNotNull(parsed)
        assertEquals("👍", parsed.emoji)
        assertEquals(ReactionParser.generateMessageHash("Hello world", 1_704_067_200), parsed.messageHash)
    }
}
