import Foundation
import Testing
@testable import MC1Services

struct MCOTextCodecTests {
  @Test func frozenGenerationZeroReferenceVectors() throws {
    let url = try #require(Bundle.module.url(forResource: "MCOTextFixtures", withExtension: "json", subdirectory: "Fixtures"))
    let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    let fixtures = try #require(root["fixtures"] as? [[String: Any]])
    #expect(fixtures.count == 17)
    for fixture in fixtures {
      let encoded = try #require(fixture["dataBase64"] as? String)
      let data = try #require(Data(base64Encoded: encoded))
      let bitLength = try #require(fixture["bitLength"] as? Int)
      let expected = try #require(fixture["text"] as? String)
      #expect(try MCOTextCodec.decode(data, bitLength: bitLength) == expected)
    }
  }

  @Test func rawUTF8DoesNotRequireKnownModelGeneration() throws {
    let bits = [0, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0]
    let header = bytes(bits)
    #expect(try MCOTextCodec.decode(header + Data("Help".utf8), bitLength: 48) == "Help")
  }

  @Test func rejectsPendingShiftAndInvalidUTF8() {
    // Version 1, generation 0, English/no partner, then an unfinished SHIFT.
    let shift = [0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0]
    #expect(throws: MCOImageCodecError.damaged) {
      try MCOTextCodec.decode(bytes(shift), bitLength: shift.count)
    }
    #expect(throws: MCOImageCodecError.damaged) {
      try MCOTextCodec.decode(Data([0x23, 0x90, 0xff]), bitLength: 24)
    }
  }

  private func bytes(_ bits: [Int]) -> Data {
    var bytes = [UInt8](repeating: 0, count: (bits.count + 7) / 8)
    for (index, bit) in bits.enumerated() where bit != 0 { bytes[index / 8] |= 1 << (7 - index % 8) }
    return Data(bytes)
  }
}
