import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ContactShareContent")
struct ContactShareContentTests {
  private static let keyHex = String(repeating: "AB", count: 32)

  @Test
  func `compact public key hex has no whitespace`() {
    let data = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let hex = ContactShareContent.compactPublicKeyHex(data)
    #expect(hex == "AABBCCDD")
    #expect(hex.contains { $0.isWhitespace } == false)
  }

  @Test
  func `uri is meshcore contact/add and round-trips`() throws {
    let key = try #require(Data(hexString: Self.keyHex))
    let uri = ContactShareContent.uri(name: "Alice", publicKey: key, type: .chat)
    #expect(uri.hasPrefix("meshcore://contact/add?"))
    #expect(!uri.contains(" "))
    let parsed = try #require(MeshCoreURLParser.parseContactURL(uri))
    #expect(parsed.name == "Alice")
    #expect(parsed.publicKey == key)
    #expect(parsed.contactType == .chat)
  }

  @Test
  func `uri preserves contact type`() throws {
    let key = try #require(Data(hexString: Self.keyHex))
    let uri = ContactShareContent.uri(name: "Relay", publicKey: key, type: .repeater)
    let parsed = try #require(MeshCoreURLParser.parseContactURL(uri))
    #expect(parsed.contactType == .repeater)
  }
}
