import Foundation
import Testing
@testable import MC1Services

/// The Swift side of the cross-platform backup freeze. These decode the checked-in vectors
/// under `fixtures/backup/` and assert the invariants the Kotlin decoder must reproduce. The
/// byte-identity checks fail the moment a DTO's `Codable` output drifts from the frozen file,
/// forcing a deliberate regeneration (see `BackupFixtureGenerator`) plus a matching Kotlin change.
@Suite("BackupFixtures")
struct BackupFixtureTests {
  // MARK: - full-envelope.json

  @Test
  func `full-envelope re-encodes byte-identically to the checked-in fixture`() throws {
    let onDisk = try BackupFixtures.fixtureData("full-envelope.json")
    let reEncoded = try BackupFixtures.encoder().encode(BackupFixtures.buildFullEnvelope())
    #expect(
      reEncoded == onDisk,
      "full-envelope.json is stale. Regenerate with REGENERATE_BACKUP_FIXTURES=1 and update the Kotlin decoder."
    )
  }

  @Test
  func `full-envelope decodes with every DTO family and reconciliation keys intact`() throws {
    let envelope = try decodeFixture("full-envelope.json")

    #expect(envelope.version == AppBackupEnvelope.currentVersion)
    #expect(envelope.manifest.validate(against: envelope))

    // Every family the envelope carries is present.
    #expect(envelope.devices.count == 1)
    #expect(envelope.contacts.count == 1)
    #expect(envelope.channels.count == 1)
    #expect(envelope.messages.count == 2)
    #expect(envelope.messageRepeats.count == 1)
    #expect(envelope.reactions.count == 1)
    #expect(envelope.roomMessages.count == 1)
    #expect(envelope.remoteNodeSessions.count == 1)
    #expect(envelope.savedTracePaths.count == 1)
    #expect(envelope.blockedChannelSenders.count == 1)
    #expect(envelope.nodeStatusSnapshots.count == 1)
    #expect(envelope.discoveredNodes.count == 1)

    // Device reconciliation/partition keys survive verbatim.
    let device = try #require(envelope.devices.first)
    #expect(device.radioID == BackupFixtures.radioID)
    #expect(device.publicKey == BackupFixtures.devicePublicKey)
    // Associated-value ConnectionMethod decodes back to its WiFi case; no BLE method present.
    #expect(device.connectionMethods.count == 1)
    #expect(device.connectionMethods.first?.isWiFi == true)
    #expect(device.connectionMethods.allSatisfy { !$0.isBluetooth })
    #expect(device.knownRegions == ["US915", "EU868"])

    // Channel flood scope reconstitutes from its raw storage fields.
    let channel = try #require(envelope.channels.first)
    #expect(channel.radioID == BackupFixtures.radioID)
    #expect(channel.floodScope == .region("US"))
    #expect(channel.notificationLevel == .mentionsOnly)

    let contact = try #require(envelope.contacts.first)
    #expect(contact.publicKey == BackupFixtures.contactPublicKey)
    #expect(contact.nickname == "Field Ops")
    // Avatar bytes survive the base64 round-trip.
    #expect(contact.avatarImageData == BackupFixtures.contactAvatarData)

    // Discovered node keeps its radioID scope and public key, plus the passive advert-hop fields.
    let discovered = try #require(envelope.discoveredNodes.first)
    #expect(discovered.radioID == BackupFixtures.radioID)
    #expect(discovered.publicKey == BackupFixtures.discoveredNodePublicKey)
    #expect(discovered.inboundHopCount == 2)

    // Snapshot GPS fix round-trips all three components.
    let snapshot = try #require(envelope.nodeStatusSnapshots.first)
    #expect(snapshot.latitude == 44.5)
    #expect(snapshot.longitude == -110.5)
    #expect(snapshot.altitude == 2400.0)

    // Emoji reaction text survives.
    #expect(envelope.reactions.first?.emoji == "👍")

    // UserDefaults region selection round-trips.
    #expect(envelope.userDefaults?.regionSelection?.countryCode == "US")
    #expect(envelope.userDefaults?.frequentEmojis == ["👍", "❤️"])
  }

  // MARK: - kotlin-reexport-full.json (reverse golden)

  /// Kotlin's encode path re-exports the full envelope; the Swift importer must accept those
  /// bytes (currently byte-identical to full-envelope.json when the freezes match).
  @Test
  func `kotlin-reexport-full decodes through the real Swift importer`() throws {
    let envelope = try decodeFixture("kotlin-reexport-full.json")
    #expect(envelope.version == AppBackupEnvelope.currentVersion)
    #expect(envelope.manifest.validate(against: envelope))
    #expect(envelope.devices.count == 1)
    #expect(envelope.contacts.count == 1)
    #expect(envelope.channels.count == 1)
    #expect(envelope.messages.count == 2)
    let device = try #require(envelope.devices.first)
    #expect(device.radioID == BackupFixtures.radioID)
    #expect(device.publicKey == BackupFixtures.devicePublicKey)
    #expect(envelope.reactions.first?.emoji == "👍")
  }

  // MARK: - unicode-edge.json

  @Test
  func `unicode-edge re-encodes byte-identically to the checked-in fixture`() throws {
    let onDisk = try BackupFixtures.fixtureData("unicode-edge.json")
    let reEncoded = try BackupFixtures.encoder().encode(BackupFixtures.buildUnicodeEnvelope())
    #expect(
      reEncoded == onDisk,
      "unicode-edge.json is stale. Regenerate with REGENERATE_BACKUP_FIXTURES=1 and update the Kotlin decoder."
    )
  }

  @Test
  func `unicode-edge preserves non-ASCII text and the empty-vs-missing nickname distinction`() throws {
    let envelope = try decodeFixture("unicode-edge.json")

    let names = Set(envelope.contacts.map(\.name))
    #expect(names == ["李明", "Renée"])

    // Empty string was emitted (present) for one contact; the key was omitted (nil) for the other.
    let emptyNickname = try #require(envelope.contacts.first { $0.name == "李明" })
    #expect(emptyNickname.nickname == "")
    let missingNickname = try #require(envelope.contacts.first { $0.name == "Renée" })
    #expect(missingNickname.nickname == nil)

    #expect(envelope.channels.first?.name == "🚨緊急")
    let message = try #require(envelope.messages.first)
    #expect(message.text == "火事です🔥 evacuate now 👨‍👩‍👧‍👦")
    #expect(message.senderNodeName == "山田")
  }

  /// The raw bytes must carry an emitted `""` nickname and no key for the missing one, so a
  /// Kotlin decoder that can't tell "" from absent is caught at the byte level, not just after decode.
  @Test
  func `unicode-edge raw JSON emits an empty-string nickname and omits the missing one`() throws {
    let root = try rawObject("unicode-edge.json")
    let contacts = try #require(root["contacts"] as? [[String: Any]])

    let liMing = try #require(contacts.first { $0["name"] as? String == "李明" })
    #expect(liMing["nickname"] as? String == "")

    let renee = try #require(contacts.first { $0["name"] as? String == "Renée" })
    #expect(renee["nickname"] == nil)
    #expect(renee.keys.contains("nickname") == false)
  }

  // MARK: - legacy-minimal.json

  @Test
  func `legacy-minimal decodes through every decodeIfPresent fallback`() throws {
    let envelope = try decodeFixture("legacy-minimal.json")
    #expect(envelope.manifest.validate(against: envelope))

    // Message: missing sortDate falls back to createdAt.
    let message = try #require(envelope.messages.first)
    #expect(message.sortDate == message.createdAt)
    #expect(message.createdAt == BackupFixtures.dmDate)

    // Channel: the mode key is stripped but a named regionScope remains, so the decoder's
    // missing-key fallback reconstructs .region(name) (the corrective post-migration
    // semantics). The other stripped keys fall back to their documented defaults.
    let channel = try #require(envelope.channels.first)
    #expect(channel.floodScope == .region("Germany"))
    #expect(channel.unreadMentionCount == 0)
    #expect(channel.notificationLevel == .all)
    #expect(channel.isPinned == false)
    // Non-stripped values still decode from the wire.
    #expect(channel.unreadCount == 4)
    #expect(channel.secret == BackupFixtures.channelSecret)

    // Envelope: missing discoveredNodes key falls back to an empty list, and the
    // manifest's missing count defaults to 0 so validation still matches.
    #expect(envelope.discoveredNodes.isEmpty)
    #expect(envelope.manifest.discoveredNodeCount == 0)
  }

  /// The legacy vector must physically lack the late-added keys, otherwise it wouldn't
  /// exercise the fallbacks it exists to freeze.
  @Test
  func `legacy-minimal raw JSON omits the late-added keys`() throws {
    let root = try rawObject("legacy-minimal.json")

    let channel = try #require((root["channels"] as? [[String: Any]])?.first)
    for key in BackupFixtures.legacyStrippedChannelKeys {
      #expect(channel.keys.contains(key) == false, "legacy channel unexpectedly carries \(key)")
    }

    let message = try #require((root["messages"] as? [[String: Any]])?.first)
    for key in BackupFixtures.legacyStrippedMessageKeys {
      #expect(message.keys.contains(key) == false, "legacy message unexpectedly carries \(key)")
    }

    #expect(root.keys.contains(BackupFixtures.legacyStrippedEnvelopeKey) == false)
    let manifest = try #require(root["manifest"] as? [String: Any])
    #expect(manifest.keys.contains(BackupFixtures.legacyStrippedManifestKey) == false)
  }

  // MARK: - Helpers

  private func decodeFixture(_ name: String) throws -> AppBackupEnvelope {
    try BackupFixtures.decoder().decode(AppBackupEnvelope.self, from: BackupFixtures.fixtureData(name))
  }

  private func rawObject(_ name: String) throws -> [String: Any] {
    let object = try JSONSerialization.jsonObject(with: BackupFixtures.fixtureData(name))
    return try #require(object as? [String: Any])
  }
}
