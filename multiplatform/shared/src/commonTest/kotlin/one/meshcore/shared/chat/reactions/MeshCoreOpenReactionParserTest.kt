package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Swift `MeshCoreOpenReactionParserTests`: v3/v1 grammar, the emoji table, and the Dart
 * `String.hashCode` incl. the definitive cross-app vector `"hello" -> 150804507` and UTF-16 truncation.
 */
class MeshCoreOpenReactionParserTest {
    // MARK: - v3 parse valid

    @Test
    fun parsesThumbsUpIndex00() {
        val result = MeshCoreOpenReactionParser.parse("r:a1b2:00")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals("a1b2", result.dartHash)
    }

    @Test
    fun parsesFireIndex05() {
        assertEquals("🔥", MeshCoreOpenReactionParser.parse("r:ff00:05")?.emoji)
    }

    @Test
    fun parsesHeartIndex01() {
        assertEquals("❤️", MeshCoreOpenReactionParser.parse("r:1234:01")?.emoji)
    }

    @Test
    fun parsesMaxValidIndex0xb7() {
        assertEquals("🚀", MeshCoreOpenReactionParser.parse("r:abcd:b7")?.emoji)
    }

    // MARK: - v3 parse invalid

    @Test fun rejectsPlainText() = assertNull(MeshCoreOpenReactionParser.parse("hello world"))

    @Test fun rejectsWrongPrefix() = assertNull(MeshCoreOpenReactionParser.parse("x:a1b2:00"))

    @Test fun rejectsUppercaseHexInHash() = assertNull(MeshCoreOpenReactionParser.parse("r:A1B2:00"))

    @Test fun rejectsUppercaseHexInIndex() = assertNull(MeshCoreOpenReactionParser.parse("r:a1b2:0A"))

    @Test fun rejectsTooShort() = assertNull(MeshCoreOpenReactionParser.parse("r:a1b:00"))

    @Test fun rejectsTooLong() = assertNull(MeshCoreOpenReactionParser.parse("r:a1b2c:00"))

    @Test fun rejectsMissingColons() = assertNull(MeshCoreOpenReactionParser.parse("r-a1b2-00"))

    @Test fun rejectsIndexBeyondTable() = assertNull(MeshCoreOpenReactionParser.parse("r:a1b2:b8"))

    @Test fun rejectsLegacyChannelFormat() = assertNull(MeshCoreOpenReactionParser.parse("👍@[AlphaNode]\n7f3a9c12"))

    @Test fun rejectsLegacyDmFormat() = assertNull(MeshCoreOpenReactionParser.parse("👍\n7f3a9c12"))

    @Test fun rejectsEmptyString() = assertNull(MeshCoreOpenReactionParser.parse(""))

    // MARK: - Emoji index mapping

    @Test
    fun spotChecksEmojiIndicesAcrossCategories() {
        assertEquals("👍", MeshCoreOpenReactionParser.parse("r:0000:00")?.emoji)
        assertEquals("😂", MeshCoreOpenReactionParser.parse("r:0000:02")?.emoji)
        assertEquals("🎉", MeshCoreOpenReactionParser.parse("r:0000:03")?.emoji)
        assertEquals("😀", MeshCoreOpenReactionParser.parse("r:0000:06")?.emoji)
        assertEquals("😶", MeshCoreOpenReactionParser.parse("r:0000:45")?.emoji)
        assertEquals("👍", MeshCoreOpenReactionParser.parse("r:0000:46")?.emoji)
        assertEquals("💪", MeshCoreOpenReactionParser.parse("r:0000:66")?.emoji)
        assertEquals("❤️", MeshCoreOpenReactionParser.parse("r:0000:67")?.emoji)
        assertEquals("🎉", MeshCoreOpenReactionParser.parse("r:0000:87")?.emoji)
    }

    @Test
    fun emojiTableHasExactly184Entries() {
        assertEquals(184, MeshCoreOpenReactionParser.emojiTable.size)
    }

    // MARK: - Dart hash

    @Test
    fun dartHashOfEmptyInputIsOne() {
        assertEquals(1u, MeshCoreOpenReactionParser.dartStringHash(emptyList()))
    }

    @Test
    fun dartHashIsDeterministic() {
        assertEquals(
            MeshCoreOpenReactionParser.dartStringHash("hello"),
            MeshCoreOpenReactionParser.dartStringHash("hello"),
        )
    }

    @Test
    fun dartHashSingleCharacterAIsPositiveWithin30Bits() {
        val hash = MeshCoreOpenReactionParser.dartStringHash(listOf('a'.code))
        assertTrue(hash > 0u)
        assertTrue(hash < (1u shl 30))
    }

    @Test
    fun dartHashResultWithin30BitRange() {
        val hash = MeshCoreOpenReactionParser.dartStringHash("test string with various chars 🎉")
        assertTrue(hash > 0u)
        assertTrue(hash <= (1u shl 30) - 1u)
    }

    @Test
    fun differentInputsProduceDifferentHashes() {
        assertTrue(
            MeshCoreOpenReactionParser.dartStringHash("hello") != MeshCoreOpenReactionParser.dartStringHash("world"),
        )
    }

    @Test
    fun dartHashMatchesKnownDartVmOutputForHello() {
        // In Dart: "hello".hashCode == 150804507. The definitive cross-app vector.
        assertEquals(150_804_507u, MeshCoreOpenReactionParser.dartStringHash("hello"))
    }

    // MARK: - computeReactionHash

    @Test
    fun computeReactionHashReturns4CharLowercaseHex() {
        val hash = MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "AlphaNode", "Hello world")
        assertEquals(4, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun computeReactionHashIsDeterministic() {
        assertEquals(
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "AlphaNode", "Hello world"),
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "AlphaNode", "Hello world"),
        )
    }

    @Test
    fun computeReactionHashChangesWithTimestamp() {
        assertTrue(
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "Hello") !=
                MeshCoreOpenReactionParser.computeReactionHash(1_700_000_001, "Node", "Hello"),
        )
    }

    @Test
    fun computeReactionHashChangesWithSender() {
        assertTrue(
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "AlphaNode", "Hello") !=
                MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "BetaNode", "Hello"),
        )
    }

    @Test
    fun computeReactionHashChangesWithText() {
        assertTrue(
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "Hello") !=
                MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "World"),
        )
    }

    @Test
    fun computeReactionHashNilSenderDmMode() {
        val dm = MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, null, "Hello world")
        assertEquals(4, dm.length)
        assertTrue(dm != MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "Hello world"))
        // Independent Python cross-check of the DM vector.
        assertEquals("b95a", dm)
    }

    @Test
    fun computeReactionHashTruncatesTextTo5Utf16CodeUnits() {
        assertEquals(
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "Hello"),
            MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "Node", "Hello world"),
        )
    }

    @Test
    fun computeReactionHashHandlesNonBmpEmojiOverUtf16() {
        // 🎉 is two UTF-16 code units, so "🎉abc" and "🎉abcdef" share the first 5 code units and hash equal.
        val a = MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, null, "🎉abc")
        val b = MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, null, "🎉abcdef")
        assertEquals(a, b)
        assertEquals("486c", a) // Independent Python cross-check over UTF-16 code units.
    }

    @Test
    fun computeReactionHashInternallyConsistent() {
        val fullHash = MeshCoreOpenReactionParser.dartStringHash("1700000000AHello")
        val expected = (fullHash and 0xFFFFu).toString(16).padStart(4, '0')
        assertEquals(expected, MeshCoreOpenReactionParser.computeReactionHash(1_700_000_000, "A", "Hello"))
        assertEquals("036f", expected) // Independent Python cross-check.
    }

    // MARK: - v1 parse

    @Test
    fun parsesV1FromRealWireCapture() {
        val result = MeshCoreOpenReactionParser.parseV1("r:1772600903000_951919033_868488711:👍")
        assertNotNull(result)
        assertEquals("👍", result.emoji)
        assertEquals(1_772_600_903L, result.timestampSeconds)
        assertEquals(951_919_033u, result.senderNameHash)
        assertEquals(868_488_711u, result.textHash)
    }

    @Test
    fun parsesV1WithHeart() {
        val result = MeshCoreOpenReactionParser.parseV1("r:1700000000000_12345_67890:❤️")
        assertNotNull(result)
        assertEquals("❤️", result.emoji)
        assertEquals(1_700_000_000L, result.timestampSeconds)
        assertEquals(12_345u, result.senderNameHash)
        assertEquals(67_890u, result.textHash)
    }

    @Test fun v1RejectsV3Format() = assertNull(MeshCoreOpenReactionParser.parseV1("r:a1b2:00"))

    @Test fun v1RejectsPlainText() = assertNull(MeshCoreOpenReactionParser.parseV1("hello world"))

    @Test fun v1RejectsWrongPrefix() = assertNull(MeshCoreOpenReactionParser.parseV1("x:1700000000000_100_200:👍"))

    @Test fun v1RejectsTooFewParts() = assertNull(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100:👍"))

    @Test fun v1RejectsTooManyParts() = assertNull(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_200_300:👍"))

    @Test fun v1RejectsNonNumericTimestamp() = assertNull(MeshCoreOpenReactionParser.parseV1("r:abc_100_200:👍"))

    @Test
    fun v1RejectsNonNumericHashValues() {
        assertNull(MeshCoreOpenReactionParser.parseV1("r:1700000000000_abc_200:👍"))
        assertNull(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_xyz:👍"))
    }

    @Test fun v1RejectsEmptyEmoji() = assertNull(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_200:"))

    @Test
    fun v1RejectsLegacyChannelFormat() {
        assertNull(MeshCoreOpenReactionParser.parseV1("👍@[AlphaNode]\n7f3a9c12"))
    }

    @Test fun v1RejectsEmptyString() = assertNull(MeshCoreOpenReactionParser.parseV1(""))

    @Test
    fun v1TimestampTruncatesMillisToSeconds() {
        assertEquals(1_700_000_000L, MeshCoreOpenReactionParser.parseV1("r:1700000000500_100_200:👍")?.timestampSeconds)
    }

    // MARK: - v1 hash matching

    @Test
    fun dartHashVerifiesV1SenderNameHash() {
        val expected = MeshCoreOpenReactionParser.dartStringHash("TestNode")
        val parsed = MeshCoreOpenReactionParser.parseV1("r:1700000000000_${expected}_12345:👍")
        assertNotNull(parsed)
        assertEquals(expected, parsed.senderNameHash)
    }

    @Test
    fun v1RoundTripBothHashesMatch() {
        val senderHash = MeshCoreOpenReactionParser.dartStringHash("AVN1")
        val textHash = MeshCoreOpenReactionParser.dartStringHash("Test message content")
        val parsed = MeshCoreOpenReactionParser.parseV1("r:1772600903000_${senderHash}_$textHash:👍")
        assertNotNull(parsed)
        assertEquals(1_772_600_903L, parsed.timestampSeconds)
        assertEquals(senderHash, parsed.senderNameHash)
        assertEquals(textHash, parsed.textHash)
        assertEquals("👍", parsed.emoji)
    }
}
