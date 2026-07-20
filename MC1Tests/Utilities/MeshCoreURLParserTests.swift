import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import OSLog
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

  // MARK: - parseChannelURL

  /// Golden hashtag PSK vectors (SHA256 of the full hashtag name, first 16 bytes).
  private static let goldenHashtagSecrets: [(name: String, hex: String)] = [
    ("#test", "9cd8fcf22a47333b591d96a2b848b73f"),
    ("#avion-testing2", "3976fbac9120f147576900ac90d41dd2")
  ]

  private static let publicChannelSecretHex = "8b3387e9c5cdea6ac9e5edbaa115cd72"
  private static let privateSecretHex = "00112233445566778899AABBCCDDEEFF"

  @Test
  func `parseChannelURL accepts a private channel with explicit secret`() throws {
    let url = "meshcore://channel/add?name=Ops&secret=\(Self.privateSecretHex)"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.name == "Ops")
    #expect(result.secret == Data(hexString: Self.privateSecretHex))
    #expect(result.regionScope == nil)
  }

  @Test
  func `parseChannelURL derives secret for secretless hashtag names`() throws {
    for entry in Self.goldenHashtagSecrets {
      let encoded = entry.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? entry.name
      let url = "meshcore://channel/add?name=\(encoded)"
      let result = try #require(MeshCoreURLParser.parseChannelURL(url), "should parse \(entry.name)")
      #expect(result.name == entry.name)
      #expect(result.secret == Data(hexString: entry.hex))
      #expect(result.secret == ChannelService.hashSecret(entry.name))
    }
  }

  @Test
  func `parseChannelURL normalizes hashtag case when deriving secret`() throws {
    let url = "meshcore://channel/add?name=%23Test"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.name == "#test")
    #expect(result.secret == ChannelService.hashSecret("#test"))
    #expect(result.secret == Data(hexString: "9cd8fcf22a47333b591d96a2b848b73f"))
  }

  @Test
  func `parseChannelURL rejects secretless bare names`() {
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=Ops") == nil)
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=test") == nil)
  }

  @Test
  func `parseChannelURL rejects empty secret with bare non-hashtag name`() {
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=Ops&secret=") == nil)
  }

  @Test
  func `parseChannelURL rejects secretless invalid hashtag bodies`() {
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=%23") == nil)
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=%23-bad") == nil)
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=%23has_underscore") == nil)
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=%23has%20space") == nil)
  }

  @Test
  func `parseChannelURL rejects present but invalid secrets without falling back to name-hash`() {
    let hashtag = "%23test"
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=\(hashtag)&secret=ZZ") == nil)
    #expect(MeshCoreURLParser.parseChannelURL("meshcore://channel/add?name=\(hashtag)&secret=AABB") == nil)
    #expect(
      MeshCoreURLParser.parseChannelURL(
        "meshcore://channel/add?name=\(hashtag)&secret=00112233445566778899AABBCCDDEE"
      ) == nil
    )
  }

  @Test
  func `parseChannelURL keeps explicit secret when name is hashtag-shaped`() throws {
    let url = "meshcore://channel/add?name=%23test&secret=\(Self.privateSecretHex)"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.name == "#test")
    #expect(result.secret == Data(hexString: Self.privateSecretHex))
    #expect(result.secret != ChannelService.hashSecret("#test"))
  }

  @Test
  func `parseChannelURL treats empty secret as missing for hashtag derivation`() throws {
    let url = "meshcore://channel/add?name=%23test&secret="
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.name == "#test")
    #expect(result.secret == ChannelService.hashSecret("#test"))
  }

  @Test
  func `parseChannelURL reads optional region_scope`() throws {
    let url = "meshcore://channel/add?name=%23avion-testing2&region_scope=testregion"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.regionScope == "testregion")
    #expect(result.secret == Data(hexString: "3976fbac9120f147576900ac90d41dd2"))
  }

  @Test
  func `parseChannelURL ignores empty region_scope`() throws {
    let url = "meshcore://channel/add?name=Ops&secret=\(Self.privateSecretHex)&region_scope="
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.regionScope == nil)
  }

  @Test
  func `parseChannelURL trims whitespace-only region_scope to nil`() throws {
    let url = "meshcore://channel/add?name=Ops&secret=\(Self.privateSecretHex)&region_scope=%20%20"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.regionScope == nil)
  }

  @Test
  func `parseChannelURL trims and caps region_scope length`() throws {
    let longName = String(repeating: "a", count: ProtocolLimits.maxDefaultFloodScopeNameBytes + 10)
    let url = "meshcore://channel/add?name=Ops&secret=\(Self.privateSecretHex)&region_scope=%20\(longName)%20"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    let expected = String(repeating: "a", count: ProtocolLimits.maxDefaultFloodScopeNameBytes)
    #expect(result.regionScope == expected)
  }

  @Test
  func `hasHashtagSecretMismatch is true only when secret is not the public hashtag hash`() throws {
    let privateSecret = try #require(Data(hexString: Self.privateSecretHex))
    let mismatch = MeshCoreURLParser.ChannelResult(name: "#test", secret: privateSecret)
    #expect(mismatch.hasHashtagSecretMismatch)

    let publicHash = ChannelService.hashSecret("#test")
    let match = MeshCoreURLParser.ChannelResult(name: "#test", secret: publicHash)
    #expect(!match.hasHashtagSecretMismatch)

    let privateName = MeshCoreURLParser.ChannelResult(name: "Ops", secret: privateSecret)
    #expect(!privateName.hasHashtagSecretMismatch)
  }

  @Test
  func `preferredFloodScope maps region names and ignores empty`() {
    #expect(ChannelJoinFloodScopeApplier.preferredFloodScope(from: nil) == nil)
    #expect(ChannelJoinFloodScopeApplier.preferredFloodScope(from: "") == nil)
    #expect(ChannelJoinFloodScopeApplier.preferredFloodScope(from: "  ") == nil)
    #expect(ChannelJoinFloodScopeApplier.preferredFloodScope(from: "testregion") == .region("testregion"))
    #expect(ChannelJoinFloodScopeApplier.preferredFloodScope(from: "  testregion  ") == .region("testregion"))
  }

  @Test
  func `applyIfNeeded persists region scope and uses with floodScope on success`() async {
    let channel = ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "#test",
      secret: ChannelService.hashSecret("#test"),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let recorder = FloodScopeWriteRecorder()
    let logger = Logger(subsystem: "com.mc1.tests", category: "ChannelJoinFloodScopeApplier")

    let updated = await ChannelJoinFloodScopeApplier.applyIfNeeded(
      channel: channel,
      regionScope: "testregion",
      setFloodScope: { id, scope in await recorder.record(id: id, scope: scope) },
      logger: logger
    )

    let recorded = await recorder.last
    #expect(recorded?.id == channel.id)
    #expect(recorded?.scope == .region("testregion"))
    #expect(updated.floodScope == .region("testregion"))
  }

  @Test
  func `applyIfNeeded keeps original channel when flood write fails`() async {
    let channel = ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "Ops",
      secret: Data(repeating: 0xAB, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let logger = Logger(subsystem: "com.mc1.tests", category: "ChannelJoinFloodScopeApplier")

    let updated = await ChannelJoinFloodScopeApplier.applyIfNeeded(
      channel: channel,
      regionScope: "testregion",
      setFloodScope: { _, _ in throw PersistenceStoreError.channelNotFound },
      logger: logger
    )

    #expect(updated.floodScope == .inherit)
    #expect(updated.id == channel.id)
  }

  @Test
  func `applyIfNeeded does not write when regionScope is nil`() async {
    let channel = ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "Ops",
      secret: Data(repeating: 0xAB, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let recorder = FloodScopeWriteRecorder()
    let logger = Logger(subsystem: "com.mc1.tests", category: "ChannelJoinFloodScopeApplier")

    let updated = await ChannelJoinFloodScopeApplier.applyIfNeeded(
      channel: channel,
      regionScope: nil,
      setFloodScope: { id, scope in await recorder.record(id: id, scope: scope) },
      logger: logger
    )

    #expect(await recorder.writeCount == 0)
    #expect(updated.floodScope == .inherit)
  }

  @Test
  func `export and parse round-trip preserves name secret and region`() throws {
    let secret = try #require(Data(hexString: Self.privateSecretHex))
    let uri = ChannelService.exportChannelURI(
      name: "Ops & #1",
      secret: secret,
      floodScope: .region("testregion")
    )
    let result = try #require(MeshCoreURLParser.parseChannelURL(uri))
    #expect(result.name == "Ops & #1")
    #expect(result.secret == secret)
    #expect(result.regionScope == "testregion")
  }

  @Test
  func `export omits region_scope for inherit and allRegions`() throws {
    let secret = try #require(Data(hexString: Self.privateSecretHex))
    for scope in [ChannelFloodScope.inherit, .allRegions] {
      let uri = ChannelService.exportChannelURI(name: "Ops", secret: secret, floodScope: scope)
      #expect(!uri.contains("region_scope"))
      let result = try #require(MeshCoreURLParser.parseChannelURL(uri))
      #expect(result.regionScope == nil)
    }
  }

  @Test
  func `export includes secret for hashtag channels so older clients still work`() throws {
    let name = "#avion-testing2"
    let secret = ChannelService.hashSecret(name)
    let uri = ChannelService.exportChannelURI(name: name, secret: secret, floodScope: .region("testregion"))
    #expect(uri.contains("secret="))
    let result = try #require(MeshCoreURLParser.parseChannelURL(uri))
    #expect(result.name == name)
    #expect(result.secret == secret)
    #expect(result.regionScope == "testregion")
  }

  @Test
  func `Public channel golden secret parses as explicit fixed key not name-hash`() throws {
    // Public uses a well-known fixed key, not hashSecret("Public").
    let url = "meshcore://channel/add?name=Public&secret=\(Self.publicChannelSecretHex)"
    let result = try #require(MeshCoreURLParser.parseChannelURL(url))
    #expect(result.name == "Public")
    #expect(result.secret == Data(hexString: Self.publicChannelSecretHex))
    #expect(result.secret != ChannelService.hashSecret("Public"))
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

/// Sendable capture box for flood-scope write assertions.
private actor FloodScopeWriteRecorder {
  private(set) var last: (id: UUID, scope: ChannelFloodScope)?
  private(set) var writeCount = 0

  func record(id: UUID, scope: ChannelFloodScope) {
    last = (id, scope)
    writeCount += 1
  }
}
