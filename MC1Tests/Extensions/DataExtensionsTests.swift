import Foundation
@testable import MC1Services
import Testing

@Suite("Data Extensions Tests")
struct DataExtensionsTests {
  // MARK: - uppercaseHexString() Tests

  @Test
  func `Empty data returns empty string`() {
    let data = Data()
    #expect(data.uppercaseHexString() == "")
  }

  @Test
  func `Hex string with no separator`() {
    let data = Data([0xAA, 0xBB, 0xCC, 0xDD])
    #expect(data.uppercaseHexString() == "AABBCCDD")
  }

  @Test
  func `Hex string with space separator`() {
    let data = Data([0xAA, 0xBB, 0xCC, 0xDD])
    #expect(data.uppercaseHexString(separator: " ") == "AA BB CC DD")
  }

  @Test
  func `Hex string with custom separator`() {
    let data = Data([0xAA, 0xBB, 0xCC])
    #expect(data.uppercaseHexString(separator: ":") == "AA:BB:CC")
  }

  @Test
  func `Hex string for single byte`() {
    let data = Data([0x0F])
    #expect(data.uppercaseHexString() == "0F")
  }

  @Test
  func `Hex string preserves leading zeros`() {
    let data = Data([0x00, 0x01, 0x02])
    #expect(data.uppercaseHexString() == "000102")
  }

  // MARK: - init?(hexString:) Tests

  @Test
  func `Init from valid hex string`() {
    let data = Data(hexString: "AABBCCDD")
    #expect(data == Data([0xAA, 0xBB, 0xCC, 0xDD]))
  }

  @Test
  func `Init from hex string with spaces`() {
    let data = Data(hexString: "AA BB CC DD")
    #expect(data == Data([0xAA, 0xBB, 0xCC, 0xDD]))
  }

  @Test
  func `Init from lowercase hex string`() {
    let data = Data(hexString: "aabbccdd")
    #expect(data == Data([0xAA, 0xBB, 0xCC, 0xDD]))
  }

  @Test
  func `Init from mixed case hex string`() {
    let data = Data(hexString: "AaBbCcDd")
    #expect(data == Data([0xAA, 0xBB, 0xCC, 0xDD]))
  }

  @Test
  func `Init from empty hex string`() {
    let data = Data(hexString: "")
    #expect(data == Data())
  }

  @Test
  func `Init from odd-length hex string returns nil`() {
    let data = Data(hexString: "ABC")
    #expect(data == nil)
  }

  @Test
  func `Init filters out non-hex characters`() {
    let data = Data(hexString: "AA-BB-CC")
    #expect(data == Data([0xAA, 0xBB, 0xCC]))
  }

  // MARK: - Round-trip Tests

  @Test
  func `Round-trip preserves data`() {
    let original = Data([0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
    let hexString = original.uppercaseHexString()
    let restored = Data(hexString: hexString)
    #expect(restored == original)
  }

  @Test
  func `Round-trip with spaces preserves data`() {
    let original = Data([0xDE, 0xAD, 0xBE, 0xEF])
    let hexString = original.uppercaseHexString(separator: " ")
    let restored = Data(hexString: hexString)
    #expect(restored == original)
  }
}
