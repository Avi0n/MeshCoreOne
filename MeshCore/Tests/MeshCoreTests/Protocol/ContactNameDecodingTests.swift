import Foundation
@testable import MeshCore
import Testing

@Suite("Contact name decoding")
struct ContactNameDecodingTests {
  /// Builds a 147-byte contact body in the shape `Parsers.parseContactData` consumes:
  /// the 32-byte name slot holds `nameField` (null-padded to 32 bytes), and `pathLen`
  /// is flood (0xFF) so the parser's path-length guard passes.
  private func contactBody(nameField: Data) -> Data {
    var data = Data()
    data.append(Data(repeating: 0xAA, count: 32)) // 0..<32   publicKey
    data.append(ContactType.chat.rawValue) // 32       type
    data.append(0x00) // 33       flags
    data.append(0xFF) // 34       pathLen (flood)
    data.append(Data(repeating: 0x00, count: 64)) // 35..<99  path
    var name = nameField
    if name.count < 32 { name.append(Data(repeating: 0x00, count: 32 - name.count)) }
    data.append(name.prefix(32)) // 99..<131 name (32 bytes)
    data.appendLittleEndian(UInt32(0)) // lastAdvert
    data.appendLittleEndian(Int32(0)) // lat
    data.appendLittleEndian(Int32(0)) // lon
    data.appendLittleEndian(UInt32(0)) // lastMod
    return data
  }

  @Test
  func `Flag emoji split at the name boundary decodes to a readable prefix`() throws {
    // 28 ASCII bytes + the first three bytes of a flag's lead scalar (F0 9F 87): the
    // byte-wise truncation an 8-byte flag leaves at the buffer boundary (invalid UTF-8).
    var nameField = Data(repeating: 0x78, count: 28)
    nameField.append(contentsOf: [0xF0, 0x9F, 0x87])
    nameField.append(0x00)

    let contact = try #require(Parsers.parseContactData(contactBody(nameField: nameField)))
    #expect(contact.advertisedName == String(repeating: "x", count: 28))
  }

  @Test
  func `Lone regional indicator is kept when only half a flag survives`() throws {
    // 27 ASCII bytes + a flag's complete first regional indicator scalar (F0 9F 87 BA):
    // valid UTF-8 alone, so the prefix keeps it as a dangling indicator (the pinned contract).
    var nameField = Data(repeating: 0x78, count: 27)
    nameField.append(contentsOf: [0xF0, 0x9F, 0x87, 0xBA])
    nameField.append(0x00)

    let contact = try #require(Parsers.parseContactData(contactBody(nameField: nameField)))
    #expect(contact.advertisedName == String(repeating: "x", count: 27) + "\u{1F1FA}")
  }

  @Test
  func `A flag name that fits the buffer decodes unchanged`() throws {
    let nameField = Data("Team \u{1F1FA}\u{1F1F8}".utf8) + Data([0x00]) // "Team 🇺🇸"
    let contact = try #require(Parsers.parseContactData(contactBody(nameField: nameField)))
    #expect(contact.advertisedName == "Team \u{1F1FA}\u{1F1F8}")
  }

  @Test
  func `Plain ASCII name still decodes`() throws {
    let nameField = Data("TestNode".utf8) + Data([0x00])
    let contact = try #require(Parsers.parseContactData(contactBody(nameField: nameField)))
    #expect(contact.advertisedName == "TestNode")
  }
}
