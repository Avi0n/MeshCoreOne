import Foundation
@testable import MC1Services
import Testing

@Suite("ReactionParser Tests")
struct ReactionParserTests {
  // MARK: - Valid Format Tests

  @Test
  func `Parses simple reaction with thumbs up`() {
    let text = "👍@[AlphaNode]\n7f3a9c12"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.emoji == "👍")
    #expect(result?.targetSender == "AlphaNode")
    #expect(result?.messageHash == "7f3a9c12")
  }

  @Test
  func `Parses reaction with heart emoji`() {
    let text = "❤️@[BetaNode]\ne4d8b1a0"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.emoji == "❤️")
    #expect(result?.targetSender == "BetaNode")
    #expect(result?.messageHash == "e4d8b1a0")
  }

  @Test
  func `Parses reaction with uppercase identifier and normalizes to lowercase`() {
    let text = "👍@[Node]\nABCDEF12"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.messageHash == "abcdef12")
  }

  @Test
  func `Parses reaction with mixed case identifier`() {
    let text = "👍@[Node]\nAbCdEf12"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.messageHash == "abcdef12")
  }

  // MARK: - Crockford Base32 Identifier Tests

  @Test
  func `Generates 8-character Crockford Base32 identifier`() {
    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    #expect(hash.count == 8)
    // Verify all characters are valid Crockford Base32 (lowercase)
    let validChars = CharacterSet(charactersIn: "0123456789abcdefghjkmnpqrstvwxyz")
    #expect(hash.unicodeScalars.allSatisfy { validChars.contains($0) })
  }

  @Test
  func `Same input produces same identifier`() {
    let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let hash2 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    #expect(hash1 == hash2)
  }

  @Test
  func `Different text produces different identifier`() {
    let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let hash2 = ReactionParser.generateMessageHash(text: "World", timestamp: 1_704_067_200)
    #expect(hash1 != hash2)
  }

  @Test
  func `Different timestamp produces different identifier`() {
    let hash1 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let hash2 = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_201)
    #expect(hash1 != hash2)
  }

  @Test
  func `Crockford O is decoded as 0`() {
    let text = "👍@[Node]\nOOOOOOOO"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.messageHash == "00000000")
  }

  @Test
  func `Crockford I/L are decoded as 1`() {
    let textI = "👍@[Node]\niiiiiiii"
    let resultI = ReactionParser.parse(textI)
    #expect(resultI?.messageHash == "11111111")

    let textL = "👍@[Node]\nLLLLLLLL"
    let resultL = ReactionParser.parse(textL)
    #expect(resultL?.messageHash == "11111111")
  }

  // MARK: - Edge Cases

  @Test
  func `Parses sender name containing colon`() {
    let text = "👍@[Node:Alpha]\na1b2c3d4"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.targetSender == "Node:Alpha")
  }

  // MARK: - Invalid Format Tests

  @Test
  func `Returns nil for plain text message`() {
    let text = "Just a normal message"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for missing identifier`() {
    let text = "👍@[Node]"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for missing @ symbol`() {
    let text = "👍 [Node]\na1b2c3d4"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for missing brackets around sender`() {
    let text = "👍@Node\na1b2c3d4"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for invalid identifier length`() {
    let text = "👍@[Node]\nabc"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for invalid Crockford characters (U)`() {
    let text = "👍@[Node]\nuuuuuuuu"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for empty sender`() {
    let text = "👍@[]\na1b2c3d4"
    #expect(ReactionParser.parse(text) == nil)
  }

  @Test
  func `Returns nil for text not starting with emoji`() {
    let text = "A@[Node]\na1b2c3d4"
    #expect(ReactionParser.parse(text) == nil)
  }

  // MARK: - ZWJ Emoji Tests

  @Test
  func `Parses reaction with skin tone modifier`() {
    let text = "👍🏽@[Node]\na1b2c3d4"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.emoji == "👍🏽")
  }

  @Test
  func `Parses reaction with family ZWJ emoji`() {
    let text = "👨‍👩‍👧@[Node]\na1b2c3d4"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.emoji == "👨‍👩‍👧")
  }

  @Test
  func `Parses reaction with flag emoji`() {
    let text = "🇺🇸@[Node]\na1b2c3d4"
    let result = ReactionParser.parse(text)

    #expect(result != nil)
    #expect(result?.emoji == "🇺🇸")
  }

  // MARK: - Summary Cache Tests

  @Test
  func `Builds summary from reactions`() {
    let reactions = [
      ("👍", 3),
      ("❤️", 2),
      ("😂", 1)
    ]
    let summary = ReactionParser.buildSummary(from: reactions)
    #expect(summary == "👍:3,❤️:2,😂:1")
  }

  @Test
  func `Parses summary string`() {
    let summary = "👍:3,❤️:2,😂:1"
    let parsed = ReactionParser.parseSummary(summary)

    #expect(parsed.count == 3)
    #expect(parsed[0] == ("👍", 3))
    #expect(parsed[1] == ("❤️", 2))
    #expect(parsed[2] == ("😂", 1))
  }

  @Test
  func `Parses empty summary`() {
    let parsed = ReactionParser.parseSummary(nil)
    #expect(parsed.isEmpty)
  }

  @Test
  func `Sorts summary by count descending`() {
    let reactions = [
      ("😂", 1),
      ("👍", 5),
      ("❤️", 3)
    ]
    let summary = ReactionParser.buildSummary(from: reactions)
    #expect(summary == "👍:5,❤️:3,😂:1")
  }

  // MARK: - ReactionDTO DM Support Tests

  @Test
  func `ReactionDTO can be created with contactID for DMs`() {
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

  @Test
  func `ReactionDTO can be created with channelIndex for channels`() {
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

  @Test
  func `Parses DM reaction format without sender`() {
    let text = "👍\n7f3a9c12"
    let result = ReactionParser.parseDM(text)

    #expect(result != nil)
    #expect(result?.emoji == "👍")
    #expect(result?.messageHash == "7f3a9c12")
  }

  @Test
  func `Parses DM reaction with heart emoji`() {
    let text = "❤️\ne4d8b1a0"
    let result = ReactionParser.parseDM(text)

    #expect(result != nil)
    #expect(result?.emoji == "❤️")
  }

  @Test
  func `Returns nil for DM format missing hash`() {
    let text = "👍"
    #expect(ReactionParser.parseDM(text) == nil)
  }

  @Test
  func `DM parser rejects channel format`() {
    let text = "👍@[Node]\nabcd1234"
    #expect(ReactionParser.parseDM(text) == nil)
  }

  @Test
  func `Builds DM reaction text correctly`() {
    let text = ReactionParser.buildDMReactionText(
      emoji: "👍",
      targetText: "Hello world",
      targetTimestamp: 1_704_067_200
    )
    #expect(text.hasPrefix("👍\n"))
    #expect(text.count == 10) // emoji (grapheme cluster) + newline + 8 char hash
    #expect(!text.contains("@["))
  }

  @Test
  func `Parses DM reaction with uppercase hash and normalizes to lowercase`() {
    let text = "👍\nABCDEF12"
    let result = ReactionParser.parseDM(text)

    #expect(result != nil)
    #expect(result?.messageHash == "abcdef12")
  }

  @Test
  func `DM parser rejects invalid Crockford characters`() {
    let text = "👍\nuuuuuuuu"
    #expect(ReactionParser.parseDM(text) == nil)
  }

  @Test
  func `DM parser rejects non-emoji start`() {
    let text = "A\na1b2c3d4"
    #expect(ReactionParser.parseDM(text) == nil)
  }

  @Test
  func `DM parser handles skin tone modifier emoji`() {
    let text = "👍🏽\na1b2c3d4"
    let result = ReactionParser.parseDM(text)

    #expect(result != nil)
    #expect(result?.emoji == "👍🏽")
  }

  @Test
  func `DM round-trip: build then parse produces same emoji and hash`() {
    let originalEmoji = "👍"
    let targetText = "Hello world"
    let timestamp: UInt32 = 1_704_067_200

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
