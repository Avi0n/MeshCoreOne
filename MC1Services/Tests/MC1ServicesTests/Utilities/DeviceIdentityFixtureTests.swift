import Foundation
@testable import MC1Services
import Testing

@Suite("DeviceIdentity fixtures")
struct DeviceIdentityFixtureTests {
  @Test
  func `Matches frozen SHA-256 UUID vectors`() throws {
    for vector in try IdentityFixtureFile.vectors() {
      let uuid = DeviceIdentity.deriveUUID(from: vector.publicKey)
      #expect(uuid.uuidString.uppercased() == vector.uuid)
    }
  }
}

private enum IdentityFixtureFile {
  struct Vector {
    var publicKey: Data
    var uuid: String
  }

  static func vectors() throws -> [Vector] {
    let data = try RepoFixtureData.load("identity/derive-uuid.json")
    let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
    let rows = json?["vectors"] as? [[String: Any]] ?? []
    return try rows.map { row in
      guard let hex = row["publicKeyHex"] as? String, let uuid = row["uuid"] as? String else {
        throw FixtureError.malformed
      }
      return Vector(publicKey: try hexData(hex), uuid: uuid)
    }
  }

  private enum FixtureError: Error { case malformed }
}

private enum RepoFixtureData {
  static func load(_ relative: String) throws -> Data {
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
