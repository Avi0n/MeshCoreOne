import Foundation
@testable import MeshCore
import Testing

@Suite("DataExtensions")
struct DataExtensionsTests {
  @Test
  func `paddedOrTruncated pads short data`() {
    let data = Data([0x01, 0x02, 0x03])
    let result = data.paddedOrTruncated(to: 6)
    #expect(result == Data([0x01, 0x02, 0x03, 0x00, 0x00, 0x00]))
  }

  @Test
  func `paddedOrTruncated truncates long data`() {
    let data = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06])
    let result = data.paddedOrTruncated(to: 3)
    #expect(result == Data([0x01, 0x02, 0x03]))
  }

  @Test
  func `paddedOrTruncated returns exact size unchanged`() {
    let data = Data([0x01, 0x02, 0x03])
    let result = data.paddedOrTruncated(to: 3)
    #expect(result == data)
  }

  @Test
  func `paddedOrTruncated returns empty for negative length`() {
    let data = Data([0x01, 0x02, 0x03])
    let result = data.paddedOrTruncated(to: -1)
    #expect(result == Data())
  }

  @Test
  func `utf8PaddedOrTruncated pads short string`() {
    let result = "Hi".utf8PaddedOrTruncated(to: 6)
    #expect(result == Data([0x48, 0x69, 0x00, 0x00, 0x00, 0x00]))
  }

  @Test
  func `utf8PaddedOrTruncated truncates long string`() {
    let result = "Hello World".utf8PaddedOrTruncated(to: 5)
    #expect(result == Data([0x48, 0x65, 0x6C, 0x6C, 0x6F])) // "Hello"
  }

  @Test
  func `appendLittleEndian UInt32`() {
    var data = Data()
    data.appendLittleEndian(UInt32(0x1234_5678))
    #expect(data == Data([0x78, 0x56, 0x34, 0x12]))
  }

  @Test
  func `appendLittleEndian Int32`() {
    var data = Data()
    data.appendLittleEndian(Int32(-1))
    #expect(data == Data([0xFF, 0xFF, 0xFF, 0xFF]))
  }

  // MARK: - utf8Prefix(maxBytes:)

  @Test
  func `utf8Prefix ASCII unchanged when under limit`() {
    let result = "Hello".utf8Prefix(maxBytes: 10)
    #expect(result == "Hello")
  }

  @Test
  func `utf8Prefix ASCII truncated at exact limit`() {
    let result = "Hello".utf8Prefix(maxBytes: 3)
    #expect(result == "Hel")
  }

  @Test
  func `utf8Prefix CJK never splits three-byte characters`() {
    // Each CJK character is 3 UTF-8 bytes
    let cjk = "你好世界" // 12 bytes total
    let result = cjk.utf8Prefix(maxBytes: 7) // room for 2 chars (6 bytes), not 3 (9 bytes)
    #expect(result == "你好")
    #expect(result.utf8.count == 6)
  }

  @Test
  func `utf8Prefix emoji never splits four-byte characters`() {
    // Each emoji is 4 UTF-8 bytes
    let emoji = "😀🎉🔥"
    let result = emoji.utf8Prefix(maxBytes: 5) // room for 1 emoji (4 bytes), not 2 (8 bytes)
    #expect(result == "😀")
    #expect(result.utf8.count == 4)
  }

  @Test
  func `utf8Prefix exact boundary includes character`() {
    let cjk = "你好" // 6 bytes total
    let result = cjk.utf8Prefix(maxBytes: 6)
    #expect(result == "你好")
  }

  @Test
  func `utf8Prefix empty string returns empty`() {
    let result = "".utf8Prefix(maxBytes: 10)
    #expect(result == "")
  }

  @Test
  func `utf8Prefix zero bytes returns empty`() {
    let result = "Hello".utf8Prefix(maxBytes: 0)
    #expect(result == "")
  }

  @Test
  func `utf8Prefix negative bytes returns empty`() {
    let result = "Hello".utf8Prefix(maxBytes: -1)
    #expect(result == "")
  }

  @Test
  func `utf8Prefix mixed ASCII and multibyte`() {
    let mixed = "Hi你" // 2 + 3 = 5 bytes
    let result = mixed.utf8Prefix(maxBytes: 4) // room for "Hi" (2) but not "Hi你" (5)
    #expect(result == "Hi")
  }

  // MARK: - utf8PaddedOrTruncated with multi-byte characters

  @Test
  func `utf8PaddedOrTruncated does not split CJK characters`() {
    let cjk = "你好世界" // 12 bytes
    let result = cjk.utf8PaddedOrTruncated(to: 8)
    // Should include "你好" (6 bytes) + 2 zero-padding bytes
    #expect(result.count == 8)
    #expect(result[6] == 0x00)
    #expect(result[7] == 0x00)
    // Verify the text portion decodes correctly
    let textPortion = String(decoding: result.prefix(6), as: UTF8.self)
    #expect(textPortion == "你好")
  }

  @Test
  func `utf8PaddedOrTruncated does not split emoji`() {
    let emoji = "😀🎉" // 8 bytes
    let result = emoji.utf8PaddedOrTruncated(to: 6)
    // Should include "😀" (4 bytes) + 2 zero-padding bytes
    #expect(result.count == 6)
    let textPortion = String(decoding: result.prefix(4), as: UTF8.self)
    #expect(textPortion == "😀")
  }
}
