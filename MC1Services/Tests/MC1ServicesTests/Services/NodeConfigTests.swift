import Foundation
@testable import MC1Services
import Testing

@Suite("MeshCoreNodeConfig Decoding")
struct NodeConfigTests {
  // MARK: - Test data

  /// Full config export fixture with synthetic test data.
  private static let fullConfigJSON = Data("""
  {
    "name": "TestNode-2",
    "public_key": "d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9",
    "private_key": "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
    "radio_settings": {
      "frequency": 910525,
      "bandwidth": 62500,
      "spreading_factor": 7,
      "coding_rate": 5,
      "tx_power": 22
    },
    "position_settings": {
      "latitude": "0.0",
      "longitude": "0.0"
    },
    "other_settings": {
      "manual_add_contacts": 0,
      "advert_location_policy": 0
    },
    "channels": [
      { "name": "General", "secret": "aa11bb22cc33dd44ee55ff6600778899" },
      { "name": "Alpha", "secret": "11223344556677889900aabbccddeeff" },
      { "name": "#bravo", "secret": "ffeeddccbbaa99887766554433221100" },
      { "name": "Charlie", "secret": "abcdef0123456789abcdef0123456789" },
      { "name": "#delta", "secret": "0123456789abcdef0123456789abcdef" },
      { "name": "Echo", "secret": "deadbeef01234567deadbeef01234567" }
    ],
    "contacts": [
      {
        "type": 3,
        "name": "Base-W (Room)",
        "custom_name": null,
        "public_key": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
        "flags": 0,
        "latitude": "40.7128",
        "longitude": "-74.006",
        "last_advert": 1767392516,
        "last_modified": 1767392535,
        "out_path": null
      },
      {
        "type": 2,
        "name": "Base-NW (Repeater)",
        "custom_name": null,
        "public_key": "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5",
        "flags": 0,
        "latitude": "34.0522",
        "longitude": "-118.2437",
        "last_advert": 1768515147,
        "last_modified": 1768515165,
        "out_path": null
      },
      {
        "type": 1,
        "name": "TestNode-1",
        "custom_name": null,
        "public_key": "0102030405060708091011121314151617181920212223242526272829303132",
        "flags": 0,
        "latitude": "0.0",
        "longitude": "0.0",
        "last_advert": 1770439152,
        "last_modified": 1770439154,
        "out_path": ""
      }
    ]
  }
  """.utf8)

  /// Channels-only export fixture.
  private static let channelsOnlyJSON = Data("""
  {
    "channels": [
      { "name": "General", "secret": "aa11bb22cc33dd44ee55ff6600778899" },
      { "name": "Alpha", "secret": "11223344556677889900aabbccddeeff" },
      { "name": "#bravo", "secret": "ffeeddccbbaa99887766554433221100" },
      { "name": "Charlie", "secret": "abcdef0123456789abcdef0123456789" },
      { "name": "#delta", "secret": "0123456789abcdef0123456789abcdef" },
      { "name": "Echo", "secret": "deadbeef01234567deadbeef01234567" }
    ]
  }
  """.utf8)

  /// Contacts-only export fixture.
  private static let contactsOnlyJSON = Data("""
  {
    "contacts": [
      {
        "type": 3,
        "name": "Base-W (Room)",
        "custom_name": null,
        "public_key": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
        "flags": 0,
        "latitude": "40.7128",
        "longitude": "-74.006",
        "last_advert": 1767392516,
        "last_modified": 1767392535,
        "out_path": null
      },
      {
        "type": 1,
        "name": "TestNode-1",
        "custom_name": null,
        "public_key": "0102030405060708091011121314151617181920212223242526272829303132",
        "flags": 0,
        "latitude": "0.0",
        "longitude": "0.0",
        "last_advert": 1770439152,
        "last_modified": 1770439154,
        "out_path": ""
      }
    ]
  }
  """.utf8)

  private static let decoder = JSONDecoder()

  // MARK: - Full config

  @Test
  func `Full config decodes all top-level fields`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)

    #expect(config.name == "TestNode-2")
    #expect(config.publicKey == "d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9")
    #expect(config.privateKey == "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
    #expect(config.radioSettings != nil)
    #expect(config.positionSettings != nil)
    #expect(config.otherSettings != nil)
    #expect(config.channels?.count == 6)
    #expect(config.contacts?.count == 3)
  }

  @Test
  func `Full config decodes radio settings`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let radio = try #require(config.radioSettings)

    #expect(radio.frequency == 910_525)
    #expect(radio.bandwidth == 62500)
    #expect(radio.spreadingFactor == 7)
    #expect(radio.codingRate == 5)
    #expect(radio.txPower == 22)
  }

  @Test
  func `Full config decodes position settings with isZero`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let position = try #require(config.positionSettings)

    #expect(position.latitude == "0.0")
    #expect(position.longitude == "0.0")
    #expect(position.isZero)
  }

  @Test
  func `Full config decodes companion-app other settings (2 of 7 fields)`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let other = try #require(config.otherSettings)

    #expect(other.manualAddContacts == 0)
    #expect(other.advertLocationPolicy == 0)
    // Companion app omits these fields
    #expect(other.telemetryModeBase == nil)
    #expect(other.telemetryModeLocation == nil)
    #expect(other.telemetryModeEnvironment == nil)
    #expect(other.multiAcks == nil)
    #expect(other.advertisementType == nil)
  }

  @Test
  func `Full config decodes channel names and secrets`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let channels = try #require(config.channels)

    #expect(channels[0].name == "General")
    #expect(channels[0].secret == "aa11bb22cc33dd44ee55ff6600778899")
    #expect(channels[2].name == "#bravo")
    #expect(channels[5].name == "Echo")
  }

  @Test
  func `Full config decodes contacts with varying types and positions`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let contacts = try #require(config.contacts)

    // Room (type 3) with position
    let room = contacts[0]
    #expect(room.type == 3)
    #expect(room.name == "Base-W (Room)")
    #expect(room.customName == nil)
    #expect(room.publicKey == "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6")
    #expect(room.flags == 0)
    #expect(room.latitude == "40.7128")
    #expect(room.longitude == "-74.006")
    #expect(room.lastAdvert == 1_767_392_516)
    #expect(room.lastModified == 1_767_392_535)
    #expect(room.outPath == nil)

    // Repeater (type 2) with position
    let repeater = contacts[1]
    #expect(repeater.type == 2)
    #expect(repeater.name == "Base-NW (Repeater)")

    // Client (type 1) with empty out_path
    let client = contacts[2]
    #expect(client.type == 1)
    #expect(client.name == "TestNode-1")
    #expect(client.outPath == "")
  }

  // MARK: - Channels-only config

  @Test
  func `Channels-only config has nil for absent sections`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.channelsOnlyJSON)

    #expect(config.name == nil)
    #expect(config.publicKey == nil)
    #expect(config.privateKey == nil)
    #expect(config.radioSettings == nil)
    #expect(config.positionSettings == nil)
    #expect(config.otherSettings == nil)
    #expect(config.contacts == nil)

    let channels = try #require(config.channels)
    #expect(channels.count == 6)
    #expect(channels[0].name == "General")
    #expect(channels[4].name == "#delta")
  }

  // MARK: - Contacts-only config

  @Test
  func `Contacts-only config has nil for absent sections`() throws {
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.contactsOnlyJSON)

    #expect(config.name == nil)
    #expect(config.publicKey == nil)
    #expect(config.privateKey == nil)
    #expect(config.radioSettings == nil)
    #expect(config.positionSettings == nil)
    #expect(config.otherSettings == nil)
    #expect(config.channels == nil)

    let contacts = try #require(config.contacts)
    #expect(contacts.count == 2)
    #expect(contacts[0].name == "Base-W (Room)")
    #expect(contacts[0].outPath == nil)
    #expect(contacts[1].name == "TestNode-1")
    #expect(contacts[1].outPath == "")
  }

  // MARK: - Round-trip

  @Test
  func `Round-trip encode/decode preserves all fields`() throws {
    let original = try Self.decoder.decode(MeshCoreNodeConfig.self, from: Self.fullConfigJSON)
    let encoded = try JSONEncoder().encode(original)
    let decoded = try Self.decoder.decode(MeshCoreNodeConfig.self, from: encoded)

    #expect(decoded.name == original.name)
    #expect(decoded.publicKey == original.publicKey)
    #expect(decoded.privateKey == original.privateKey)
    #expect(decoded.radioSettings == original.radioSettings)
    #expect(decoded.positionSettings == original.positionSettings)
    #expect(decoded.otherSettings == original.otherSettings)
    #expect(decoded.channels == original.channels)
    #expect(decoded.contacts == original.contacts)
  }

  // MARK: - Edge cases

  @Test
  func `Contact with null out_path decodes as nil`() throws {
    let json = Data("""
    {
      "contacts": [{
        "type": 1, "name": "Test", "custom_name": null,
        "public_key": "aabb", "flags": 0,
        "latitude": "0.0", "longitude": "0.0",
        "last_advert": 0, "last_modified": 0,
        "out_path": null
      }]
    }
    """.utf8)

    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)
    let contact = try #require(config.contacts?.first)
    #expect(contact.outPath == nil)
  }

  @Test
  func `Contact with empty string out_path decodes as empty string`() throws {
    let json = Data("""
    {
      "contacts": [{
        "type": 1, "name": "Test", "custom_name": null,
        "public_key": "aabb", "flags": 0,
        "latitude": "0.0", "longitude": "0.0",
        "last_advert": 0, "last_modified": 0,
        "out_path": ""
      }]
    }
    """.utf8)

    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)
    let contact = try #require(config.contacts?.first)
    #expect(contact.outPath == "")
  }

  @Test
  func `Contact with null custom_name decodes as nil`() throws {
    let json = Data("""
    {
      "contacts": [{
        "type": 1, "name": "Test", "custom_name": null,
        "public_key": "aabb", "flags": 0,
        "latitude": "0.0", "longitude": "0.0",
        "last_advert": 0, "last_modified": 0,
        "out_path": null
      }]
    }
    """.utf8)

    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)
    let contact = try #require(config.contacts?.first)
    #expect(contact.customName == nil)
  }

  @Test
  func `Contact with non-null custom_name decodes correctly`() throws {
    let json = Data("""
    {
      "contacts": [{
        "type": 1, "name": "Test", "custom_name": "My Custom Name",
        "public_key": "aabb", "flags": 0,
        "latitude": "0.0", "longitude": "0.0",
        "last_advert": 0, "last_modified": 0,
        "out_path": null
      }]
    }
    """.utf8)

    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)
    let contact = try #require(config.contacts?.first)
    #expect(contact.customName == "My Custom Name")
  }

  @Test
  func `Empty JSON object decodes with all nil fields`() throws {
    let json = Data("{}".utf8)
    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)

    #expect(config.name == nil)
    #expect(config.publicKey == nil)
    #expect(config.privateKey == nil)
    #expect(config.radioSettings == nil)
    #expect(config.positionSettings == nil)
    #expect(config.otherSettings == nil)
    #expect(config.channels == nil)
    #expect(config.contacts == nil)
  }

  @Test
  func `ContactConfig encodes nil customName and outPath as explicit null`() throws {
    let contact = MeshCoreNodeConfig.ContactConfig(
      type: 1, name: "Test",
      publicKey: "aabb", flags: 0,
      latitude: "0.0", longitude: "0.0",
      lastAdvert: 0, lastModified: 0
    )

    let data = try JSONEncoder().encode(contact)
    let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])

    // Both keys must be present with NSNull (JSON null)
    #expect(json["custom_name"] is NSNull)
    #expect(json["out_path"] is NSNull)
  }

  @Test
  func `ContactConfig encodes non-nil customName and outPath as values`() throws {
    let contact = MeshCoreNodeConfig.ContactConfig(
      type: 1, name: "Test", customName: "Nick",
      publicKey: "aabb", flags: 0,
      latitude: "0.0", longitude: "0.0",
      lastAdvert: 0, lastModified: 0,
      outPath: "aabbcc"
    )

    let data = try JSONEncoder().encode(contact)
    let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])

    #expect(json["custom_name"] as? String == "Nick")
    #expect(json["out_path"] as? String == "aabbcc")
  }

  @Test
  func `Position isZero returns false for non-zero coordinates`() {
    let position = MeshCoreNodeConfig.PositionSettings(
      latitude: "40.7128",
      longitude: "-74.006"
    )
    #expect(!position.isZero)
  }

  @Test
  func `OtherSettings with all 7 fields round-trips correctly`() throws {
    let json = Data("""
    {
      "other_settings": {
        "manual_add_contacts": 1,
        "advert_location_policy": 2,
        "telemetry_mode_base": 3,
        "telemetry_mode_location": 4,
        "telemetry_mode_environment": 5,
        "multi_acks": 6,
        "advertisement_type": 7
      }
    }
    """.utf8)

    let config = try Self.decoder.decode(MeshCoreNodeConfig.self, from: json)
    let other = try #require(config.otherSettings)

    #expect(other.manualAddContacts == 1)
    #expect(other.advertLocationPolicy == 2)
    #expect(other.telemetryModeBase == 3)
    #expect(other.telemetryModeLocation == 4)
    #expect(other.telemetryModeEnvironment == 5)
    #expect(other.multiAcks == 6)
    #expect(other.advertisementType == 7)

    // Round-trip
    let encoded = try JSONEncoder().encode(config)
    let decoded = try Self.decoder.decode(MeshCoreNodeConfig.self, from: encoded)
    #expect(decoded.otherSettings == other)
  }

  // MARK: - ConfigSections

  @Test
  func `ConfigSections defaults to all false`() {
    let sections = ConfigSections()

    #expect(!sections.nodeIdentity)
    #expect(!sections.radioSettings)
    #expect(!sections.positionSettings)
    #expect(!sections.otherSettings)
    #expect(!sections.channels)
    #expect(!sections.contacts)
    #expect(!sections.allSelected)
    #expect(!sections.anySectionSelected)
  }

  @Test
  func `ConfigSections allSelected is false when any section is false`() {
    var sections = ConfigSections()
    sections.selectAll()
    sections.channels = false

    #expect(!sections.allSelected)
  }

  @Test
  func `ConfigSections allSelected is false when only one section is true`() {
    let sections = ConfigSections(
      nodeIdentity: false,
      radioSettings: false,
      positionSettings: false,
      otherSettings: false,
      channels: true,
      contacts: false
    )

    #expect(!sections.allSelected)
  }

  @Test
  func `ConfigSections selectAll sets all to true`() {
    var sections = ConfigSections()
    sections.selectAll()

    #expect(sections.allSelected)
    #expect(sections.anySectionSelected)
  }

  @Test
  func `ConfigSections deselectAll sets all to false`() {
    var sections = ConfigSections()
    sections.selectAll()
    sections.deselectAll()

    #expect(!sections.allSelected)
    #expect(!sections.anySectionSelected)
  }
}
