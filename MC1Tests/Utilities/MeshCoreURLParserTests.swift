import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MeshCoreURLParser Tests")
struct MeshCoreURLParserTests {
  /// A real 32-byte public key rendered as uppercase hex (64 chars).
  static let validHex = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"

  /// A second, distinct 32-byte key used to prove an injected key never wins.
  static let otherHex = String(repeating: "BC", count: 32)

  /// Exports a contact then parses it back, exercising the full emit -> parse round-trip.
  private static func roundTrip(name: String, type: ContactType = .chat) throws -> MeshCoreURLParser.ContactResult {
    let key = try #require(Data(hexString: validHex))
    let uri = ContactService.exportContactURI(name: name, publicKey: key, type: type)
    return try #require(MeshCoreURLParser.parseContactURL(uri), "round-trip should parse for name: \(name)")
  }

  @Test
  func `parseContactURL falls back to .chat for an out-of-range type without trapping`() throws {
    let url = "meshcore://contact/add?name=Node&public_key=\(Self.validHex)&type=300"
    let result = try #require(MeshCoreURLParser.parseContactURL(url))
    #expect(result.name == "Node")
    #expect(result.contactType == .chat)
  }

  @Test
  func `parseContactURL maps type=2 to .repeater`() throws {
    let url = "meshcore://contact/add?name=Node&public_key=\(Self.validHex)&type=2"
    let result = try #require(MeshCoreURLParser.parseContactURL(url))
    #expect(result.contactType == .repeater)
  }

  // MARK: - Export/parse round-trip and query injection

  @Test
  func `A name carrying an injected public_key/type query does not override the declared key or type`() throws {
    let spoofName = "Alice&public_key=\(Self.otherHex)&type=3"
    let declaredKey = try #require(Data(hexString: Self.validHex))

    let uri = ContactService.exportContactURI(name: spoofName, publicKey: declaredKey, type: .chat)
    let result = try #require(MeshCoreURLParser.parseContactURL(uri))

    #expect(result.publicKey == declaredKey, "Declared key must win over the injected public_key")
    #expect(result.contactType == .chat, "Declared type must win over the injected type")
    #expect(result.name == spoofName, "The whole injected string is the name, preserved verbatim")
  }

  @Test
  func `Names containing query-significant characters round-trip intact`() throws {
    for name in ["a & b", "a=b", "key & value = pair", "100% sure?", "a#b"] {
      let result = try Self.roundTrip(name: name)
      #expect(result.name == name, "name should survive round-trip: expected \(name), got \(result.name)")
    }
  }

  @Test
  func `A literal plus in a name is preserved, not turned into a space`() throws {
    let result = try Self.roundTrip(name: "C++ dev")
    #expect(result.name == "C++ dev")
  }

  @Test
  func `Spaces, colons, and unicode round-trip intact`() throws {
    for name in ["Field Base", "12:30 rally point", "Café au lait", "北京"] {
      let result = try Self.roundTrip(name: name)
      #expect(result.name == name, "name should survive round-trip: expected \(name), got \(result.name)")
    }
  }

  @Test
  func `Every contact type round-trips through export and parse`() throws {
    for type in [ContactType.chat, .repeater, .room] {
      let result = try Self.roundTrip(name: "Node", type: type)
      #expect(result.contactType == type)
    }
  }

  // MARK: - parseMapURL

  @Test
  func `parseMapURL parses a valid map link into the correct coordinate`() throws {
    let url = "meshcore://map?lat=37.334900&lon=-122.009020"
    let coordinate = try #require(MeshCoreURLParser.parseMapURL(url))
    #expect(abs(coordinate.latitude - 37.3349) < 0.000001)
    #expect(abs(coordinate.longitude - -122.00902) < 0.000001)
  }

  @Test
  func `parseMapURL returns nil when lat or lon is missing`() {
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=37.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lon=-122.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map") == nil)
  }

  @Test
  func `parseMapURL rejects out-of-range coordinates`() {
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=91.0&lon=0.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=0.0&lon=181.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=-90.001&lon=0.0") == nil)
  }

  @Test
  func `parseMapURL rejects a non-map host`() {
    #expect(MeshCoreURLParser.parseMapURL("meshcore://contact?lat=10.0&lon=10.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://channel/add?lat=10.0&lon=10.0") == nil)
  }

  @Test
  func `parseMapURL rejects non-finite and hex-float values that Double would otherwise accept`() {
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=nan&lon=0.0") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=0.0&lon=inf") == nil)
    #expect(MeshCoreURLParser.parseMapURL("meshcore://map?lat=0x1p4&lon=0.0") == nil)
  }
}
