import Foundation
@testable import MeshCore
import Testing

@Suite("Ed25519ToX25519 fixtures")
struct Ed25519ToX25519FixtureTests {
  @Test
  func `Matches frozen birational-map vectors`() throws {
    for vector in try Ed25519FixtureFile.vectors() {
      let converted = try #require(Ed25519ToX25519.convertPublicKey(vector.ed25519))
      #expect(converted == vector.x25519)
    }
  }

  @Test
  func `Wrong length is rejected`() {
    #expect(Ed25519ToX25519.convertPublicKey(Data()) == nil)
    #expect(Ed25519ToX25519.convertPublicKey(Data(repeating: 0, count: 16)) == nil)
  }
}

private enum Ed25519FixtureFile {
  struct Vector {
    var ed25519: Data
    var x25519: Data
  }

  static func vectors() throws -> [Vector] {
    let data = try loadRepoFixture("crypto/ed25519-x25519.json")
    let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
    let rows = json?["vectors"] as? [[String: Any]] ?? []
    return try rows.map { row in
      guard let ed = row["ed25519PublicHex"] as? String, let x = row["x25519PublicHex"] as? String else {
        throw CocoaError(.fileReadCorruptFile)
      }
      return Vector(ed25519: try hexData(ed), x25519: try hexData(x))
    }
  }
}

private func loadRepoFixture(_ relative: String) throws -> Data {
  var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
  let fileManager = FileManager.default
  while url.pathComponents.count > 1 {
    let candidate = url.appendingPathComponent("fixtures/\(relative)")
    if fileManager.fileExists(atPath: candidate.path) {
      return try Data(contentsOf: candidate)
    }
    url.deleteLastPathComponent()
  }
  throw CocoaError(.fileNoSuchFile)
}

private func hexData(_ hex: String) throws -> Data {
  var bytes = [UInt8]()
  var index = hex.startIndex
  while index < hex.endIndex {
    let next = hex.index(index, offsetBy: 2)
    guard let byte = UInt8(hex[index..<next], radix: 16) else {
      throw CocoaError(.fileReadCorruptFile)
    }
    bytes.append(byte)
    index = next
  }
  return Data(bytes)
}
