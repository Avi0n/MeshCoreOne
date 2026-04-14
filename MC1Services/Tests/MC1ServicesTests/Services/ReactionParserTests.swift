import Foundation
import Testing
@testable import MC1Services

@Suite("ReactionParser Tests")
struct ReactionParserTests {

    // MARK: - Valid Format Tests

    @Test("Parses simple reaction with thumbs up")
    func parsesSimpleReaction() {
        let text = "👍@[AlphaNode]\n7f3a9c12"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.emoji == "👍")
        #expect(result?.targetSender == "AlphaNode")
        #expect(result?.messageHash == "7f3a9c12")
    }

    @Test("Parses reaction with heart emoji")
    func parsesHeartReaction() {
        let text = "❤️@[BetaNode]\ne4d8b1a0"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.emoji == "❤️")
        #expect(result?.targetSender == "BetaNode")
        #expect(result?.messageHash == "e4d8b1a0")
    }

    @Test("Parses reaction with uppercase identifier and normalizes to lowercase")
    func parsesUppercaseIdentifier() {
        let text = "👍@[Node]\nABCDEF12"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.messageHash == "abcdef12")
    }

    @Test("Parses reaction with mixed case identifier")
    func parsesMixedCaseIdentifier() {
        let text = "👍@[Node]\nAbCdEf12"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.messageHash == "abcdef12")
    }

    // MARK: - Crockford Base32 Identifier Tests

    @Test("Generates 8-character Crockford Base32 identifier")
    func generatesEightCharBase32() {
        let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067200)
        #expect(hash.count == 8)
        // Verify all characters are valid Crockford Base32 (lowercase)
        let validChars = CharacterSet(charactersIn: "0123456789abcdefghjkmnpqrstvwxyz")
        #expect(hash.unicodeScalars.allSatisfy { validChars.contains($0) })
    }

    @Test("Same input produces same identifier")
    func sameInputSameHash() {
        let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067200)
        let hash2 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067200)
        #expect(hash1 == hash2)
    }

    @Test("Different text produces different identifier")
    func differentTextDifferentHash() {
        let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067200)
        let hash2 = ReactionParser.generateMessageHash(text: "World", timestamp: 1704067200)
        #expect(hash1 != hash2)
    }

    @Test("Different timestamp produces different identifier")
    func differentTimestampDifferentHash() {
        let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067200)
        let hash2 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1704067201)
        #expect(hash1 != hash2)
    }

    @Test("Crockford O is decoded as 0")
    func crockfordODecodesAsZero() {
        let text = "👍@[Node]\nOOOOOOOO"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.messageHash == "00000000")
    }

    @Test("Crockford I/L are decoded as 1")
    func crockfordILDecodeAsOne() {
        let textI = "👍@[Node]\niiiiiiii"
        let resultI = ReactionParser.parse(textI)
        #expect(resultI?.messageHash == "11111111")

        let textL = "👍@[Node]\nLLLLLLLL"
        let resultL = ReactionParser.parse(textL)
        #expect(resultL?.messageHash == "11111111")
    }

    // MARK: - Edge Cases

    @Test("Parses sender name containing colon")
    func parsesSenderWithColon() {
        let text = "👍@[Node:Alpha]\na1b2c3d4"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.targetSender == "Node:Alpha")
    }

    // MARK: - Invalid Format Tests

    @Test("Returns nil for plain text message")
    func returnsNilForPlainText() {
        let text = "Just a normal message"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for missing identifier")
    func returnsNilForMissingHash() {
        let text = "👍@[Node]"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for missing @ symbol")
    func returnsNilForMissingAt() {
        let text = "👍 [Node]\na1b2c3d4"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for missing brackets around sender")
    func returnsNilForMissingBrackets() {
        let text = "👍@Node\na1b2c3d4"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for invalid identifier length")
    func returnsNilForInvalidHashLength() {
        let text = "👍@[Node]\nabc"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for invalid Crockford characters (U)")
    func returnsNilForInvalidCrockfordU() {
        let text = "👍@[Node]\nuuuuuuuu"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for empty sender")
    func returnsNilForEmptySender() {
        let text = "👍@[]\na1b2c3d4"
        #expect(ReactionParser.parse(text) == nil)
    }

    @Test("Returns nil for text not starting with emoji")
    func returnsNilForNonEmojiStart() {
        let text = "A@[Node]\na1b2c3d4"
        #expect(ReactionParser.parse(text) == nil)
    }

    // MARK: - ZWJ Emoji Tests

    @Test("Parses reaction with skin tone modifier")
    func parsesEmojiWithSkinTone() {
        let text = "👍🏽@[Node]\na1b2c3d4"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.emoji == "👍🏽")
    }

    @Test("Parses reaction with family ZWJ emoji")
    func parsesFamilyEmoji() {
        let text = "👨‍👩‍👧@[Node]\na1b2c3d4"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.emoji == "👨‍👩‍👧")
    }

    @Test("Parses reaction with flag emoji")
    func parsesFlagEmoji() {
        let text = "🇺🇸@[Node]\na1b2c3d4"
        let result = ReactionParser.parse(text)

        #expect(result != nil)
        #expect(result?.emoji == "🇺🇸")
    }

    // MARK: - Summary Cache Tests

    @Test("Builds summary from reactions")
    func buildsSummary() {
        let reactions = [
            ("👍", 3),
            ("❤️", 2),
            ("😂", 1)
        ]
        let summary = ReactionParser.buildSummary(from: reactions)
        #expect(summary == "👍:3,❤️:2,😂:1")
    }

    @Test("Parses summary string")
    func parsesSummary() {
        let summary = "👍:3,❤️:2,😂:1"
        let parsed = ReactionParser.parseSummary(summary)

        #expect(parsed.count == 3)
        #expect(parsed[0] == ("👍", 3))
        #expect(parsed[1] == ("❤️", 2))
        #expect(parsed[2] == ("😂", 1))
    }

    @Test("Parses empty summary")
    func parsesEmptySummary() {
        let parsed = ReactionParser.parseSummary(nil)
        #expect(parsed.isEmpty)
    }

    @Test("Sorts summary by count descending")
    func sortsSummaryByCount() {
        let reactions = [
            ("😂", 1),
            ("👍", 5),
            ("❤️", 3)
        ]
        let summary = ReactionParser.buildSummary(from: reactions)
        #expect(summary == "👍:5,❤️:3,😂:1")
    }

    // MARK: - ReactionDTO DM Support Tests

    @Test("ReactionDTO can be created with contactID for DMs")
    func reactionDTOWithContactID() {
        let contactID = UUID()
        let radioID = UUID()
        let messageID = UUID()

        let dto = ReactionDTO(
            messageID: messageID,
            emoji: "👍",
            senderName: "TestNode",
            messageHash: "a1b2c3d4",
            rawText: "👍@[TestNode]\na1b2c3d4",
            contactID: contactID,
            radioID: radioID
        )

        #expect(dto.contactID == contactID)
        #expect(dto.channelIndex == nil)
    }

    @Test("ReactionDTO can be created with channelIndex for channels")
    func reactionDTOWithChannelIndex() {
        let radioID = UUID()
        let messageID = UUID()

        let dto = ReactionDTO(
            messageID: messageID,
            emoji: "👍",
            senderName: "TestNode",
            messageHash: "a1b2c3d4",
            rawText: "👍@[TestNode]\na1b2c3d4",
            channelIndex: 5,
            radioID: radioID
        )

        #expect(dto.channelIndex == 5)
        #expect(dto.contactID == nil)
    }

    // MARK: - DM Reaction Format Tests

    @Test("Parses DM reaction format without sender")
    func parsesDMReaction() {
        let text = "👍\n7f3a9c12"
        let result = ReactionParser.parseDM(text)

        #expect(result != nil)
        #expect(result?.emoji == "👍")
        #expect(result?.messageHash == "7f3a9c12")
    }

    @Test("Parses DM reaction with heart emoji")
    func parsesDMHeartReaction() {
        let text = "❤️\ne4d8b1a0"
        let result = ReactionParser.parseDM(text)

        #expect(result != nil)
        #expect(result?.emoji == "❤️")
    }

    @Test("Returns nil for DM format missing hash")
    func returnsNilForDMMissingHash() {
        let text = "👍"
        #expect(ReactionParser.parseDM(text) == nil)
    }

    @Test("DM parser rejects channel format")
    func dmParserRejectsChannelFormat() {
        let text = "👍@[Node]\nabcd1234"
        #expect(ReactionParser.parseDM(text) == nil)
    }

    @Test("Builds DM reaction text correctly")
    func buildsDMReactionText() {
        let text = ReactionParser.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: 1704067200
        )
        #expect(text.hasPrefix("👍\n"))
        #expect(text.count == 10) // emoji (grapheme cluster) + newline + 8 char hash
        #expect(!text.contains("@["))
    }

    @Test("Parses DM reaction with uppercase hash and normalizes to lowercase")
    func parsesDMUppercaseHash() {
        let text = "👍\nABCDEF12"
        let result = ReactionParser.parseDM(text)

        #expect(result != nil)
        #expect(result?.messageHash == "abcdef12")
    }

    @Test("DM parser rejects invalid Crockford characters")
    func dmParserRejectsInvalidCrockford() {
        let text = "👍\nuuuuuuuu"
        #expect(ReactionParser.parseDM(text) == nil)
    }

    @Test("DM parser rejects non-emoji start")
    func dmParserRejectsNonEmojiStart() {
        let text = "A\na1b2c3d4"
        #expect(ReactionParser.parseDM(text) == nil)
    }

    @Test("DM parser handles skin tone modifier emoji")
    func dmParserHandlesSkinToneEmoji() {
        let text = "👍🏽\na1b2c3d4"
        let result = ReactionParser.parseDM(text)

        #expect(result != nil)
        #expect(result?.emoji == "👍🏽")
    }

    @Test("DM round-trip: build then parse produces same emoji and hash")
    func dmRoundTrip() {
        let originalEmoji = "👍"
        let targetText = "Hello world"
        let timestamp: UInt32 = 1704067200

        let text = ReactionParser.buildDMReactionText(
            emoji: originalEmoji,
            targetText: targetText,
            targetTimestamp: timestamp
        )

        let parsed = ReactionParser.parseDM(text)
        #expect(parsed != nil)
        #expect(parsed?.emoji == originalEmoji)

        let expectedHash = ReactionParser.generateMessageHash(text: targetText, timestamp: timestamp)
        #expect(parsed?.messageHash == expectedHash)
    }
}
