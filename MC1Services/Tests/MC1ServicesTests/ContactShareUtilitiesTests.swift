import Foundation
@testable import MC1Services
import Testing

@Suite("ContactShareUtilities Tests")
struct ContactShareUtilitiesTests {
  /// A real 32-byte public key rendered as uppercase hex (64 chars).
  static let validHex = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"

  // MARK: - Round-trip

  @Test(
    arguments: [ContactType.chat, .repeater, .room]
  )
  func `formatShare then parseShare round-trips for every contact type`(type: ContactType) throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let name = "AVN2"

    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: type, name: name)
    let result = try #require(ContactShareUtilities.parseShare(token))

    #expect(result.publicKey == publicKey)
    #expect(result.contactType == type)
    #expect(result.name == name)
  }

  @Test
  func `formatShare emits uppercase hex`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: "Node")
    #expect(token.contains(Self.validHex))
    #expect(token == "<\(Self.validHex):1:Node>")
  }

  // MARK: - Malformed rejection

  @Test
  func `parseShare rejects a 63-char (short) public key`() {
    let shortHex = String(Self.validHex.dropLast())
    #expect(ContactShareUtilities.parseShare("<\(shortHex):1:Node>") == nil)
  }

  @Test
  func `parseShare rejects non-hex characters in the public key`() {
    let nonHex = "G1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
    #expect(ContactShareUtilities.parseShare("<\(nonHex):1:Node>") == nil)
  }

  @Test
  func `parseShare rejects a token missing the name field`() {
    #expect(ContactShareUtilities.parseShare("<\(Self.validHex):1>") == nil)
  }

  @Test
  func `parseShare rejects a token missing type and name`() {
    #expect(ContactShareUtilities.parseShare("<\(Self.validHex)>") == nil)
  }

  @Test
  func `parseShare rejects an empty name`() {
    #expect(ContactShareUtilities.parseShare("<\(Self.validHex):1:>") == nil)
  }

  // MARK: - Adversarial type bounds (regression for the trap)

  @Test(
    arguments: ["256", "300", "99999999999999999999", "0", "4"]
  )
  func `parseShare rejects out-of-range and overflowing type values without trapping`(typeDigits: String) {
    let token = "<\(Self.validHex):\(typeDigits):x>"
    #expect(ContactShareUtilities.parseShare(token) == nil)
  }

  // MARK: - Name escaping

  @Test
  func `parseShare round-trips a name containing '>' to its stripped form`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: "AV>N2")
    #expect(!token.dropFirst().dropLast().contains(">"))

    let result = try #require(ContactShareUtilities.parseShare(token))
    #expect(result.name == "AVN2")
  }

  @Test
  func `parseShare preserves a colon inside the name`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let name = "Base:Camp:1"
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: name)
    let result = try #require(ContactShareUtilities.parseShare(token))
    #expect(result.name == name)
  }

  @Test
  func `parseShare preserves a newline inside the name`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let name = "Line1\nLine2"
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: name)
    let result = try #require(ContactShareUtilities.parseShare(token))
    #expect(result.name == name)
  }

  @Test
  func `parseShare preserves an RTL-override character in the name`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let name = "Node\u{202E}flip"
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: name)
    let result = try #require(ContactShareUtilities.parseShare(token))
    #expect(result.name == name)
  }

  @Test
  func `parseShare round-trips a 1000-character name`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let name = String(repeating: "n", count: 1000)
    let token = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: name)
    let result = try #require(ContactShareUtilities.parseShare(token))
    #expect(result.name == name)
  }

  // MARK: - extractShares

  @Test
  func `extractShares finds multiple tokens amid plain text`() throws {
    let publicKey = try #require(Data(hexString: Self.validHex))
    let first = ContactShareUtilities.formatShare(publicKey: publicKey, type: .chat, name: "Alice")
    let second = ContactShareUtilities.formatShare(publicKey: publicKey, type: .repeater, name: "Bob")
    let text = "Add these: \(first) and also \(second) thanks"

    let results = ContactShareUtilities.extractShares(from: text)
    #expect(results.count == 2)
    #expect(results[0].name == "Alice")
    #expect(results[0].contactType == .chat)
    #expect(results[1].name == "Bob")
    #expect(results[1].contactType == .repeater)
  }

  @Test
  func `extractShares returns empty when there are no tokens`() {
    #expect(ContactShareUtilities.extractShares(from: "Just some plain text, no tokens here.").isEmpty)
  }
}
