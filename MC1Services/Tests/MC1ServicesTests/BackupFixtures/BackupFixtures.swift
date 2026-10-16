import Foundation
@testable import MC1Services

/// Shared, fully deterministic builders for the cross-platform backup fixtures under
/// `fixtures/backup/`. Every id, date, key, and text is a fixed literal so the encoded
/// JSON is byte-stable: the same build re-encodes to the same bytes on every run, which
/// is what lets `BackupFixtureTests` freeze the on-disk vectors and what the future Kotlin
/// decoder re-implements against. No `UUID()`/`Date()` randomness anywhere.
///
/// The fixtures are the *uncompressed* JSON payload — the exact bytes `makeBackupJSONEncoder`
/// produces before zlib. The zlib wrapper is transport only; the JSON is the wire contract.
enum BackupFixtures {
  // MARK: - Fixed identities

  static let radioID = uuid("A0000000-0000-0000-0000-000000000001")
  static let deviceID = uuid("D0000000-0000-0000-0000-000000000001")
  static let contactID = uuid("C0000000-0000-0000-0000-000000000001")
  static let channelID = uuid("C0000000-0000-0000-0000-0000000000AA")
  static let dmMessageID = uuid("30000000-0000-0000-0000-000000000001")
  static let channelMessageID = uuid("30000000-0000-0000-0000-000000000002")
  static let reactionID = uuid("50000000-0000-0000-0000-000000000001")
  static let repeatID = uuid("60000000-0000-0000-0000-000000000001")
  static let sessionID = uuid("70000000-0000-0000-0000-000000000001")
  static let roomMessageID = uuid("70000000-0000-0000-0000-0000000000AA")
  static let tracePathID = uuid("80000000-0000-0000-0000-000000000001")
  static let tracePathRunID = uuid("80000000-0000-0000-0000-0000000000AA")
  static let blockedSenderID = uuid("90000000-0000-0000-0000-000000000001")
  static let snapshotID = uuid("A1000000-0000-0000-0000-000000000001")
  static let discoveredNodeID = uuid("B0000000-0000-0000-0000-000000000001")

  /// The reconciliation key. Distinct byte pattern from the contact key so a Kotlin
  /// decoder that swaps the two would fail the base64 assertion.
  static let devicePublicKey = Data(repeating: 0x11, count: 32)
  static let contactPublicKey = Data(repeating: 0xAB, count: 32)
  static let channelSecret = Data(repeating: 0x22, count: 16)
  static let discoveredNodePublicKey = Data(repeating: 0xCD, count: 32)

  /// Stand-in avatar bytes (a PNG magic-number header) so the contact vector exercises
  /// the base64 `avatarImageData` key on both platforms. Distinct from the key patterns.
  static let contactAvatarData = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])

  // MARK: - Fixed timestamps (integer seconds keep the JSON free of float drift)

  static let exportDate = date(1_700_000_000)
  static let dmDate = date(1_700_000_100)
  static let channelMessageDate = date(1_700_000_200)
  static let lastConnected = date(1_699_000_000)

  // MARK: - Full envelope

  /// Every DTO family populated, including a device carrying both `publicKey` and `radioID`
  /// (the reconciliation and partition keys) plus a WiFi `ConnectionMethod` and `knownRegions`.
  /// Bluetooth methods are intentionally absent: `Device.redactedForBackup()` strips them, so
  /// no real backup ever carries a BLE peripheral UUID.
  static func buildFullEnvelope() -> AppBackupEnvelope {
    let device = DeviceDTO.testDevice(
      id: deviceID,
      radioID: radioID,
      publicKey: devicePublicKey,
      lastConnected: lastConnected
    ).copy {
      $0.connectionMethods = [.wifi(host: "192.168.1.50", port: 5000, displayName: "Base Camp")]
      $0.knownRegions = ["US915", "EU868"]
      $0.latitude = 37.7749
      $0.longitude = -122.4194
      $0.ocvPreset = "custom"
      $0.customOCVArrayString = "4200,4100,4000"
    }

    let contact = ContactDTO.testContact(
      id: contactID,
      radioID: radioID,
      publicKey: contactPublicKey,
      name: "Alice",
      lastAdvertTimestamp: 1_699_500_000,
      latitude: 51.5074,
      longitude: -0.1278,
      lastModified: 1_699_500_050,
      nickname: "Field Ops",
      isFavorite: true,
      isPinned: true,
      lastMessageDate: dmDate,
      unreadCount: 2,
      unreadMentionCount: 1,
      avatarImageData: contactAvatarData
    )

    let channel = ChannelDTO.testChannel(
      id: channelID,
      radioID: radioID,
      index: 1,
      name: "#ops",
      secret: channelSecret,
      lastMessageDate: channelMessageDate,
      unreadCount: 3,
      unreadMentionCount: 1,
      notificationLevel: .mentionsOnly,
      isPinned: true,
      floodScope: .region("US")
    )

    var dm = MessageDTO.testDirectMessage(
      id: dmMessageID,
      radioID: radioID,
      contactID: contactID,
      text: "Hello",
      timestamp: 1_700_000_100,
      createdAt: dmDate,
      direction: .incoming,
      status: .delivered,
      snr: 8.5,
      pathNodes: Data([0x31, 0xA7]),
      heardRepeats: 1
    )
    dm.deduplicationKey = "dm-fixed-1700000100"

    var channelMessage = MessageDTO.testChannelMessage(
      id: channelMessageID,
      radioID: radioID,
      channelIndex: 1,
      text: "Radio check",
      timestamp: 1_700_000_200,
      createdAt: channelMessageDate,
      direction: .incoming,
      status: .delivered,
      senderNodeName: "Jan",
      heardRepeats: 2
    )
    channelMessage.deduplicationKey = "ch-1-1700000200-Jan-DEADBEEF"

    let reaction = ReactionDTO.testReaction(
      id: reactionID,
      messageID: channelMessageID,
      radioID: radioID,
      emoji: "👍",
      senderName: "Jan",
      receivedAt: channelMessageDate,
      channelIndex: 1
    )

    let messageRepeat = MessageRepeatDTO.testRepeat(
      id: repeatID,
      messageID: channelMessageID,
      receivedAt: channelMessageDate,
      pathNodes: Data([0x42]),
      snr: 7.0,
      rssi: -88
    )

    let session = RemoteNodeSessionDTO.testSession(
      id: sessionID,
      radioID: radioID,
      lastConnectedDate: lastConnected,
      lastMessageDate: channelMessageDate
    )

    let roomMessage = RoomMessageDTO.testRoomMessage(
      id: roomMessageID,
      sessionID: sessionID,
      createdAt: channelMessageDate
    )

    let run = TracePathRunDTO.testRun(
      id: tracePathRunID,
      date: channelMessageDate,
      roundTripMs: 180,
      hopsSNR: [8.5, 7.0]
    )
    let tracePath = SavedTracePathDTO.testPath(
      id: tracePathID,
      radioID: radioID,
      createdDate: lastConnected,
      runs: [run]
    )

    let blocked = BlockedChannelSenderDTO.testBlockedSender(
      id: blockedSenderID,
      radioID: radioID,
      dateBlocked: channelMessageDate
    )

    let snapshot = NodeStatusSnapshotDTO.testSnapshot(
      id: snapshotID,
      timestamp: channelMessageDate,
      nodePublicKey: devicePublicKey,
      latitude: 44.5,
      longitude: -110.5,
      altitude: 2400.0
    )

    let discoveredNode = DiscoveredNodeDTO(
      id: discoveredNodeID,
      radioID: radioID,
      publicKey: discoveredNodePublicKey,
      name: "Ridge Repeater",
      typeRawValue: 2,
      lastHeard: channelMessageDate,
      lastAdvertTimestamp: 1_699_600_000,
      latitude: 40.015,
      longitude: -105.2705,
      outPathLength: 1,
      outPath: Data([0x31]),
      inboundHopCount: 2,
      inboundHopAdvertTimestamp: 1_699_600_000
    )

    var prefs = BackupUserDefaults()
    prefs.hasCompletedOnboarding = true
    prefs.mapStyleSelection = "standard"
    prefs.frequentEmojis = ["👍", "❤️"]
    prefs.regionSelection = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .manual
    )

    return envelope(
      devices: [device],
      contacts: [contact],
      channels: [channel],
      messages: [dm, channelMessage],
      messageRepeats: [messageRepeat],
      reactions: [reaction],
      roomMessages: [roomMessage],
      remoteNodeSessions: [session],
      savedTracePaths: [tracePath],
      blockedChannelSenders: [blocked],
      nodeStatusSnapshots: [snapshot],
      discoveredNodes: [discoveredNode],
      userDefaults: prefs
    )
  }

  // MARK: - Unicode / empty-vs-missing envelope

  /// Non-ASCII text (emoji, ZWJ sequences, CJK) plus the empty-string-vs-missing-key
  /// distinction the backup traps doc calls out: `contactID` carries `nickname: ""`
  /// (an emitted empty string) while `secondaryContactID` carries `nickname: nil`
  /// (an omitted key). A decoder that folds "" into nil, or nil into "", diverges here.
  static func buildUnicodeEnvelope() -> AppBackupEnvelope {
    let secondaryContactID = uuid("C0000000-0000-0000-0000-000000000002")

    let device = DeviceDTO.testDevice(
      id: deviceID,
      radioID: radioID,
      publicKey: devicePublicKey,
      lastConnected: lastConnected
    )

    let emptyNicknameContact = ContactDTO.testContact(
      id: contactID,
      radioID: radioID,
      publicKey: contactPublicKey,
      name: "李明",
      nickname: ""
    )
    let missingNicknameContact = ContactDTO.testContact(
      id: secondaryContactID,
      radioID: radioID,
      publicKey: Data(repeating: 0xCD, count: 32),
      name: "Renée",
      nickname: nil
    )

    let channel = ChannelDTO.testChannel(
      id: channelID,
      radioID: radioID,
      index: 2,
      name: "🚨緊急",
      secret: channelSecret
    )

    var emojiMessage = MessageDTO.testChannelMessage(
      id: channelMessageID,
      radioID: radioID,
      channelIndex: 2,
      text: "火事です🔥 evacuate now 👨‍👩‍👧‍👦",
      timestamp: 1_700_000_200,
      createdAt: channelMessageDate,
      direction: .incoming,
      status: .delivered,
      senderNodeName: "山田"
    )
    emojiMessage.deduplicationKey = "ch-2-1700000200-山田-CAFEBABE"

    return envelope(
      devices: [device],
      contacts: [emptyNicknameContact, missingNicknameContact],
      channels: [channel],
      messages: [emojiMessage]
    )
  }

  // MARK: - Legacy source envelope

  /// A modern envelope the generator down-converts into `legacy-minimal.json` by stripping
  /// late-added keys. Kept minimal (one device, one channel, one message) so the resulting
  /// legacy vector isolates the `decodeIfPresent` fallbacks without noise.
  static func buildLegacySourceEnvelope() -> AppBackupEnvelope {
    let device = DeviceDTO.testDevice(
      id: deviceID,
      radioID: radioID,
      publicKey: devicePublicKey,
      lastConnected: lastConnected
    )
    // Non-default values so a passing decode proves the fallback fired rather than
    // echoing an encoded default: muted level, a set mention count, pin on, a
    // named region flood scope (its mode key is the one stripped).
    let channel = ChannelDTO.testChannel(
      id: channelID,
      radioID: radioID,
      index: 1,
      name: "#ops",
      secret: channelSecret,
      unreadCount: 4,
      unreadMentionCount: 5,
      notificationLevel: .muted,
      isPinned: true,
      floodScope: .region("Germany")
    )
    var message = MessageDTO.testDirectMessage(
      id: dmMessageID,
      radioID: radioID,
      contactID: contactID,
      text: "Legacy",
      timestamp: 1_700_000_100,
      createdAt: dmDate,
      direction: .incoming,
      status: .delivered
    )
    message.deduplicationKey = "dm-legacy-1700000100"

    return envelope(
      devices: [device],
      channels: [channel],
      messages: [message]
    )
  }

  /// DTO keys added after the backup format shipped. The generator removes these from the
  /// legacy vector so its decode exercises every `decodeIfPresent` fallback the traps doc pins.
  static let legacyStrippedChannelKeys = [
    "floodScopeModeRawValue", "unreadMentionCount", "notificationLevel", "isPinned", "isFavorite",
  ]
  static let legacyStrippedMessageKeys = ["sortDate", "failureSeen", "regionScopeMatches"]
  static let legacyStrippedEnvelopeKey = "discoveredNodes"
  static let legacyStrippedManifestKey = "discoveredNodeCount"

  // MARK: - Encoder / decoder (production settings)

  /// The exact encoder the production exporter uses: `.secondsSince1970`, `.sortedKeys`,
  /// default base64 for `Data`. Deterministic key order is what makes the fixtures stable.
  static func encoder() -> JSONEncoder { makeBackupJSONEncoder() }
  static func decoder() -> JSONDecoder { makeBackupJSONDecoder() }

  // MARK: - Fixture location

  /// `<repo>/fixtures/backup`, resolved by walking up from this file until that
  /// directory exists. Independent of where MC1Services sits in the tree.
  static var directory: URL {
    var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let fileManager = FileManager.default
    while url.pathComponents.count > 1 {
      let fixtures = url.appendingPathComponent("fixtures/backup", isDirectory: true)
      var isDir: ObjCBool = false
      if fileManager.fileExists(atPath: fixtures.path, isDirectory: &isDir), isDir.boolValue {
        return fixtures
      }
      url = url.deletingLastPathComponent()
    }
    fatalError("Could not locate repo root (no ancestor directory contains fixtures/backup)")
  }

  static func fixtureData(_ name: String) throws -> Data {
    try Data(contentsOf: directory.appendingPathComponent(name))
  }

  // MARK: - Private helpers

  private static func uuid(_ string: String) -> UUID {
    guard let value = UUID(uuidString: string) else {
      fatalError("Invalid fixed UUID literal: \(string)")
    }
    return value
  }

  private static func date(_ secondsSince1970: TimeInterval) -> Date {
    Date(timeIntervalSince1970: secondsSince1970)
  }

  private static func envelope(
    devices: [DeviceDTO] = [],
    contacts: [ContactDTO] = [],
    channels: [ChannelDTO] = [],
    messages: [MessageDTO] = [],
    messageRepeats: [MessageRepeatDTO] = [],
    reactions: [ReactionDTO] = [],
    roomMessages: [RoomMessageDTO] = [],
    remoteNodeSessions: [RemoteNodeSessionDTO] = [],
    savedTracePaths: [SavedTracePathDTO] = [],
    blockedChannelSenders: [BlockedChannelSenderDTO] = [],
    nodeStatusSnapshots: [NodeStatusSnapshotDTO] = [],
    discoveredNodes: [DiscoveredNodeDTO] = [],
    userDefaults: BackupUserDefaults? = nil
  ) -> AppBackupEnvelope {
    var envelope = AppBackupEnvelope(
      exportDate: exportDate,
      appVersion: "1.0.0",
      appBuild: "42",
      devices: devices,
      contacts: contacts,
      channels: channels,
      messages: messages,
      messageRepeats: messageRepeats,
      reactions: reactions,
      roomMessages: roomMessages,
      remoteNodeSessions: remoteNodeSessions,
      savedTracePaths: savedTracePaths,
      blockedChannelSenders: blockedChannelSenders,
      nodeStatusSnapshots: nodeStatusSnapshots,
      discoveredNodes: discoveredNodes,
      userDefaults: userDefaults
    )
    envelope.manifest = BackupManifest(from: envelope)
    return envelope
  }
}
