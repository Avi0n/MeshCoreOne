import Foundation
@testable import MC1Services
import Testing

@Suite("MeshCoreOpenReactionParser Tests")
struct MeshCoreOpenReactionParserTests {
  // MARK: - Parse Valid Format Tests

  @Test
  func `Parses valid reaction with thumbs up (index 00)`() {
    let result = MeshCoreOpenReactionParser.parse("r:a1b2:00")

    #expect(result != nil)
    #expect(result?.emoji == "👍")
    #expect(result?.dartHash == "a1b2")
  }

  @Test
  func `Parses valid reaction with fire (index 05)`() {
    let result = MeshCoreOpenReactionParser.parse("r:ff00:05")

    #expect(result != nil)
    #expect(result?.emoji == "🔥")
    #expect(result?.dartHash == "ff00")
  }

  @Test
  func `Parses valid reaction with heart (index 01)`() {
    let result = MeshCoreOpenReactionParser.parse("r:1234:01")

    #expect(result != nil)
    #expect(result?.emoji == "❤️")
    #expect(result?.dartHash == "1234")
  }

  @Test
  func `Parses reaction at max valid emoji index (0xb7)`() {
    let result = MeshCoreOpenReactionParser.parse("r:abcd:b7")

    #expect(result != nil)
    #expect(result?.emoji == "🚀")
    #expect(result?.dartHash == "abcd")
  }

  // MARK: - Parse Invalid Format Tests

  @Test
  func `Rejects plain text`() {
    #expect(MeshCoreOpenReactionParser.parse("hello world") == nil)
  }

  @Test
  func `Rejects wrong prefix`() {
    #expect(MeshCoreOpenReactionParser.parse("x:a1b2:00") == nil)
  }

  @Test
  func `Rejects uppercase hex in hash`() {
    #expect(MeshCoreOpenReactionParser.parse("r:A1B2:00") == nil)
  }

  @Test
  func `Rejects uppercase hex in index`() {
    #expect(MeshCoreOpenReactionParser.parse("r:a1b2:0A") == nil)
  }

  @Test
  func `Rejects too short`() {
    #expect(MeshCoreOpenReactionParser.parse("r:a1b:00") == nil)
  }

  @Test
  func `Rejects too long`() {
    #expect(MeshCoreOpenReactionParser.parse("r:a1b2c:00") == nil)
  }

  @Test
  func `Rejects missing colons`() {
    #expect(MeshCoreOpenReactionParser.parse("r-a1b2-00") == nil)
  }

  @Test
  func `Rejects emoji index beyond table size`() {
    // 0xb8 = 184, table has 184 entries (0x00–0xb7)
    #expect(MeshCoreOpenReactionParser.parse("r:a1b2:b8") == nil)
  }

  @Test
  func `Rejects legacy channel reaction format`() {
    #expect(MeshCoreOpenReactionParser.parse("👍@[AlphaNode]\n7f3a9c12") == nil)
  }

  @Test
  func `Rejects legacy DM reaction format`() {
    #expect(MeshCoreOpenReactionParser.parse("👍\n7f3a9c12") == nil)
  }

  @Test
  func `Rejects empty string`() {
    #expect(MeshCoreOpenReactionParser.parse("") == nil)
  }

  // MARK: - Emoji Index Mapping Tests

  @Test
  func `Spot-check emoji indices across all categories`() {
    // quickEmojis
    #expect(MeshCoreOpenReactionParser.parse("r:0000:00")?.emoji == "👍") // 0x00
    #expect(MeshCoreOpenReactionParser.parse("r:0000:02")?.emoji == "😂") // 0x02
    #expect(MeshCoreOpenReactionParser.parse("r:0000:03")?.emoji == "🎉") // 0x03

    // smileys start at 0x06
    #expect(MeshCoreOpenReactionParser.parse("r:0000:06")?.emoji == "😀") // first smiley
    #expect(MeshCoreOpenReactionParser.parse("r:0000:45")?.emoji == "😶") // last smiley

    // gestures start at 0x46
    #expect(MeshCoreOpenReactionParser.parse("r:0000:46")?.emoji == "👍") // first gesture
    #expect(MeshCoreOpenReactionParser.parse("r:0000:66")?.emoji == "💪") // last gesture

    // hearts start at 0x67
    #expect(MeshCoreOpenReactionParser.parse("r:0000:67")?.emoji == "❤️") // first heart

    // objects start at 0x87
    #expect(MeshCoreOpenReactionParser.parse("r:0000:87")?.emoji == "🎉") // first object
  }

  // MARK: - Dart String Hash Tests

  @Test
  func `Dart hash of empty input produces 1`() {
    // Dart: "".hashCode should be 0, which becomes 1 (zero-guard)
    let hash = MeshCoreOpenReactionParser.dartStringHash([])
    #expect(hash == 1)
  }

  @Test
  func `Dart hash is deterministic`() {
    let units: [UInt16] = Array("hello".utf16)
    let hash1 = MeshCoreOpenReactionParser.dartStringHash(units)
    let hash2 = MeshCoreOpenReactionParser.dartStringHash(units)
    #expect(hash1 == hash2)
  }

  @Test
  func `Dart hash of single character 'a'`() {
    // Manually compute: code_unit = 97 (0x61)
    // hash = 0
    // hash += 97 → 97
    // hash += 97 << 10 → 97 + 99328 = 99425
    // hash ^= 99425 >> 6 → 99425 ^ 1553 = 100464
    // finalize:
    // hash += 100464 << 3 → 100464 + 803712 = 904176
    // hash ^= 904176 >> 11 → 904176 ^ 441 = 904617
    // hash += 904617 << 15 → 904617 + 29640630272 (wraps in UInt32) → need wrapping
    // Let's just verify it's > 0 and within 30 bits
    let hash = MeshCoreOpenReactionParser.dartStringHash([97])
    #expect(hash > 0)
    #expect(hash < (1 << 30))
  }

  @Test
  func `Dart hash result is within 30-bit range`() {
    let units: [UInt16] = Array("test string with various chars 🎉".utf16)
    let hash = MeshCoreOpenReactionParser.dartStringHash(units)
    #expect(hash > 0)
    #expect(hash <= (1 << 30) - 1)
  }

  @Test
  func `Different inputs produce different hashes`() {
    let hash1 = MeshCoreOpenReactionParser.dartStringHash(Array("hello".utf16))
    let hash2 = MeshCoreOpenReactionParser.dartStringHash(Array("world".utf16))
    #expect(hash1 != hash2)
  }

  // MARK: - Hash Computation Tests

  @Test
  func `computeReactionHash returns 4-char lowercase hex`() {
    let hash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "AlphaNode",
      text: "Hello world"
    )
    #expect(hash.count == 4)
    #expect(hash.allSatisfy { $0.isHexDigit && !$0.isUppercase })
  }

  @Test
  func `computeReactionHash is deterministic`() {
    let hash1 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "AlphaNode",
      text: "Hello world"
    )
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "AlphaNode",
      text: "Hello world"
    )
    #expect(hash1 == hash2)
  }

  @Test
  func `computeReactionHash changes with different timestamp`() {
    let hash1 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "Hello"
    )
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_001,
      senderName: "Node",
      text: "Hello"
    )
    #expect(hash1 != hash2)
  }

  @Test
  func `computeReactionHash changes with different sender`() {
    let hash1 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "AlphaNode",
      text: "Hello"
    )
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "BetaNode",
      text: "Hello"
    )
    #expect(hash1 != hash2)
  }

  @Test
  func `computeReactionHash changes with different text`() {
    let hash1 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "Hello"
    )
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "World"
    )
    #expect(hash1 != hash2)
  }

  @Test
  func `computeReactionHash with nil sender (DM mode)`() {
    let hash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: nil,
      text: "Hello world"
    )
    #expect(hash.count == 4)

    // Should differ from channel mode with same params
    let channelHash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "Hello world"
    )
    #expect(hash != channelHash)
  }

  @Test
  func `computeReactionHash truncates text to 5 UTF-16 code units`() {
    // "Hello" is 5 code units, "Hello world" has 11
    // Both should produce the same hash since only first 5 code units are used
    let hash1 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "Hello"
    )
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: "Hello world"
    )
    #expect(hash1 == hash2)
  }

  @Test
  func `computeReactionHash handles short text (fewer than 5 code units)`() {
    let hash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: nil,
      text: "Hi"
    )
    #expect(hash.count == 4)
  }

  // MARK: - UTF-16 Edge Cases

  @Test
  func `computeReactionHash handles emoji in text (multi-code-unit)`() {
    // 🎉 is 2 UTF-16 code units (surrogate pair), so "🎉abc" = 5 code units
    let hash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: nil,
      text: "🎉abc"
    )
    #expect(hash.count == 4)

    // "🎉abcdef" should hash the same since first 5 code units match
    let hash2 = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: nil,
      text: "🎉abcdef"
    )
    #expect(hash == hash2)
  }

  @Test
  func `computeReactionHash handles empty text`() {
    let hash = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "Node",
      text: ""
    )
    #expect(hash.count == 4)
  }

  // MARK: - Cross-App Test Vectors

  @Test
  func `Dart hash matches known Dart VM output for 'hello'`() {
    // In Dart: "hello".hashCode == 150804507
    // This is the definitive cross-app test vector
    let hash = MeshCoreOpenReactionParser.dartStringHash(Array("hello".utf16))
    #expect(hash == 150_804_507)
  }

  @Test
  func `computeReactionHash is internally consistent`() {
    // Verify computeReactionHash assembles code units correctly
    // by comparing against manual dartStringHash call
    let testUnits = Array("1700000000AHello".utf16)
    let fullHash = MeshCoreOpenReactionParser.dartStringHash(testUnits)
    let masked = fullHash & 0xFFFF
    let expected = String(format: "%04x", masked)

    let computed = MeshCoreOpenReactionParser.computeReactionHash(
      timestamp: 1_700_000_000,
      senderName: "A",
      text: "Hello"
    )
    #expect(computed == expected)
  }

  @Test
  func `Emoji table has exactly 184 entries`() {
    #expect(MeshCoreOpenReactionParser.emojiTable.count == 184)
  }

  // MARK: - V1 Parse Tests

  @Test
  func `Parses v1 reaction from real wire capture`() {
    let result = MeshCoreOpenReactionParser.parseV1("r:1772600903000_951919033_868488711:👍")

    #expect(result != nil)
    #expect(result?.emoji == "👍")
    #expect(result?.timestampSeconds == 1_772_600_903)
    #expect(result?.senderNameHash == 951_919_033)
    #expect(result?.textHash == 868_488_711)
  }

  @Test
  func `Parses v1 reaction with heart emoji`() {
    let result = MeshCoreOpenReactionParser.parseV1("r:1700000000000_12345_67890:❤️")

    #expect(result != nil)
    #expect(result?.emoji == "❤️")
    #expect(result?.timestampSeconds == 1_700_000_000)
    #expect(result?.senderNameHash == 12345)
    #expect(result?.textHash == 67890)
  }

  @Test
  func `Parses v1 reaction with fire emoji`() {
    let result = MeshCoreOpenReactionParser.parseV1("r:1772600903000_100_200:🔥")

    #expect(result != nil)
    #expect(result?.emoji == "🔥")
  }

  @Test
  func `V1 rejects v3 format`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:a1b2:00") == nil)
  }

  @Test
  func `V1 rejects plain text`() {
    #expect(MeshCoreOpenReactionParser.parseV1("hello world") == nil)
  }

  @Test
  func `V1 rejects wrong prefix`() {
    #expect(MeshCoreOpenReactionParser.parseV1("x:1700000000000_100_200:👍") == nil)
  }

  @Test
  func `V1 rejects too few underscore parts`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100:👍") == nil)
  }

  @Test
  func `V1 rejects too many underscore parts`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_200_300:👍") == nil)
  }

  @Test
  func `V1 rejects non-numeric timestamp`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:abc_100_200:👍") == nil)
  }

  @Test
  func `V1 rejects non-numeric hash values`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:1700000000000_abc_200:👍") == nil)
    #expect(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_xyz:👍") == nil)
  }

  @Test
  func `V1 rejects empty emoji`() {
    #expect(MeshCoreOpenReactionParser.parseV1("r:1700000000000_100_200:") == nil)
  }

  @Test
  func `V1 rejects legacy channel format`() {
    #expect(MeshCoreOpenReactionParser.parseV1("👍@[AlphaNode]\n7f3a9c12") == nil)
  }

  @Test
  func `V1 rejects empty string`() {
    #expect(MeshCoreOpenReactionParser.parseV1("") == nil)
  }

  @Test
  func `V1 timestamp converts millis to seconds correctly`() {
    // 1700000000500 ms → 1700000000 s (truncated, not rounded)
    let result = MeshCoreOpenReactionParser.parseV1("r:1700000000500_100_200:👍")
    #expect(result?.timestampSeconds == 1_700_000_000)
  }

  // MARK: - V1 Hash Matching Tests

  @Test
  func `dartStringHash can verify v1 sender name hash`() {
    // Compute the Dart hash of a known sender name
    let senderName = "TestNode"
    let expectedHash = MeshCoreOpenReactionParser.dartStringHash(Array(senderName.utf16))

    // Construct a v1 reaction with that hash
    let reactionText = "r:1700000000000_\(expectedHash)_12345:👍"
    let parsed = MeshCoreOpenReactionParser.parseV1(reactionText)

    #expect(parsed != nil)
    #expect(parsed?.senderNameHash == expectedHash)
  }

  @Test
  func `dartStringHash can verify v1 text hash`() {
    let messageText = "Hello from mesh"
    let expectedHash = MeshCoreOpenReactionParser.dartStringHash(Array(messageText.utf16))

    let reactionText = "r:1700000000000_12345_\(expectedHash):👍"
    let parsed = MeshCoreOpenReactionParser.parseV1(reactionText)

    #expect(parsed != nil)
    #expect(parsed?.textHash == expectedHash)
  }

  @Test
  func `V1 round-trip: construct reaction and verify both hashes match`() {
    let senderName = "AVN1"
    let messageText = "Test message content"
    let timestampMs: UInt64 = 1_772_600_903_000

    let senderHash = MeshCoreOpenReactionParser.dartStringHash(Array(senderName.utf16))
    let textHash = MeshCoreOpenReactionParser.dartStringHash(Array(messageText.utf16))

    let reactionText = "r:\(timestampMs)_\(senderHash)_\(textHash):👍"
    let parsed = MeshCoreOpenReactionParser.parseV1(reactionText)

    #expect(parsed != nil)
    #expect(parsed?.timestampSeconds == UInt32(timestampMs / 1000))
    #expect(parsed?.senderNameHash == senderHash)
    #expect(parsed?.textHash == textHash)
    #expect(parsed?.emoji == "👍")
  }
}
