import Foundation
@testable import MC1Services
import Testing

@Suite("AppBackupEnvelope")
struct AppBackupEnvelopeTests {
  // MARK: - Round-trip encoding

  @Test
  func `Envelope round-trips through JSON encode/decode`() throws {
    let radioID = UUID()
    let envelope = makeTestEnvelope(radioID: radioID)

    let encoder = makeBackupJSONEncoder()
    let json = try encoder.encode(envelope)

    let decoder = makeBackupJSONDecoder()
    let decoded = try decoder.decode(AppBackupEnvelope.self, from: json)

    #expect(decoded.version == envelope.version)
    #expect(decoded.appVersion == envelope.appVersion)
    #expect(decoded.appBuild == envelope.appBuild)
    #expect(decoded.manifest == envelope.manifest)
    #expect(decoded.devices.count == envelope.devices.count)
    #expect(decoded.contacts.count == envelope.contacts.count)
    #expect(decoded.channels.count == envelope.channels.count)
    #expect(decoded.messages.count == envelope.messages.count)
    #expect(decoded.messageRepeats.count == envelope.messageRepeats.count)
    #expect(decoded.reactions.count == envelope.reactions.count)
    #expect(decoded.roomMessages.count == envelope.roomMessages.count)
    #expect(decoded.remoteNodeSessions.count == envelope.remoteNodeSessions.count)
    #expect(decoded.savedTracePaths.count == envelope.savedTracePaths.count)
    #expect(decoded.blockedChannelSenders.count == envelope.blockedChannelSenders.count)
    #expect(decoded.nodeStatusSnapshots.count == envelope.nodeStatusSnapshots.count)
    #expect(decoded.userDefaults == envelope.userDefaults)
  }

  @Test
  func `Backup JSON round-trips sub-second timestamps without truncation`() throws {
    let radioID = UUID()
    let exportDate = Date(timeIntervalSince1970: 1_700_000_500.9876542)
    let messageDate = Date(timeIntervalSince1970: 1_700_000_501.1234567)
    let snapshotTimestamp = Date(timeIntervalSince1970: 1_700_000_502.7654321)

    let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
    let session = RemoteNodeSessionDTO.testSession(
      radioID: radioID,
      lastMessageDate: messageDate
    )
    let snapshot = NodeStatusSnapshotDTO.testSnapshot(
      timestamp: snapshotTimestamp,
      nodePublicKey: Data(repeating: 0xAB, count: 32)
    )
    let envelope = AppBackupEnvelope(
      exportDate: exportDate,
      appVersion: "1.0.0",
      appBuild: "42",
      manifest: BackupManifest(
        deviceCount: 1,
        remoteNodeSessionCount: 1,
        nodeStatusSnapshotCount: 1
      ),
      devices: [device],
      remoteNodeSessions: [session],
      nodeStatusSnapshots: [snapshot]
    )

    let json = try makeBackupJSONEncoder().encode(envelope)
    let decoded = try makeBackupJSONDecoder().decode(AppBackupEnvelope.self, from: json)

    #expect(decoded.exportDate == exportDate)
    #expect(decoded.remoteNodeSessions.first?.lastMessageDate == messageDate)
    #expect(decoded.nodeStatusSnapshots.first?.timestamp == snapshotTimestamp)
  }

  // MARK: - parseBackup (compress -> parse round-trip)

  @Test
  func `parseBackup decompresses and decodes a valid backup`() throws {
    let envelope = makeTestEnvelope(radioID: UUID())

    let json = try makeBackupJSONEncoder().encode(envelope)
    let compressed = try json.zlibCompressed()

    let parsed = try parseBackup(data: compressed)
    #expect(parsed.version == AppBackupEnvelope.currentVersion)
    #expect(parsed.appVersion == "1.0.0")
    #expect(parsed.devices.count == 1)
    #expect(parsed.contacts.count == 1)
  }

  @Test
  func `parseBackup throws invalidFile for garbage data`() {
    let garbage = Data([0x00, 0xFF, 0xAB, 0xCD])
    #expect(throws: AppBackupError.self) {
      try parseBackup(data: garbage)
    }
  }

  @Test
  func `parseBackup throws invalidFile for truncated zlib payloads`() throws {
    let envelope = makeTestEnvelope(radioID: UUID())
    let json = try makeBackupJSONEncoder().encode(envelope)
    let compressed = try json.zlibCompressed()
    // Valid zlib header, missing trailer + tail of deflate stream — matches
    // what a truncated download or interrupted file-copy would produce.
    let truncated = Data(compressed.prefix(max(compressed.count / 2, 4)))

    #expect {
      try parseBackup(data: truncated)
    } throws: { error in
      guard let backupError = error as? AppBackupError,
            case .invalidFile = backupError else {
        return false
      }
      return true
    }
  }

  @Test
  func `parseBackup rejects files larger than the size cap`() {
    let oversized = Data(count: maxBackupCompressedBytes + 1)
    #expect {
      try parseBackup(data: oversized)
    } throws: { error in
      guard let backupError = error as? AppBackupError,
            case .fileTooLarge = backupError else {
        return false
      }
      return true
    }
  }

  @Test
  func `parseBackup rejects payloads whose decompressed size exceeds the cap`() throws {
    // 2 MB of zeros compresses to a few KB, well under the compressed cap.
    // With a 1 MB uncompressed cap, decompression must abort partway through.
    let testUncompressedCap = 1 * 1_048_576
    let bomb = Data(count: 2 * 1_048_576)
    let compressed = try bomb.zlibCompressed()
    #expect(compressed.count < maxBackupCompressedBytes)

    #expect {
      try parseBackup(data: compressed, maxUncompressedBytes: testUncompressedCap)
    } throws: { error in
      guard let backupError = error as? AppBackupError,
            case let .decompressedTooLarge(maxBytes) = backupError else {
        return false
      }
      return maxBytes == testUncompressedCap
    }
  }

  @Test
  func `parseBackup throws unsupportedVersion for future versions`() throws {
    let envelope = makeTestEnvelope(radioID: UUID())
    let encoded = try makeBackupJSONEncoder().encode(envelope)
    guard var json = try JSONSerialization.jsonObject(with: encoded) as? [String: Any] else {
      Issue.record("Failed to deserialize envelope JSON")
      return
    }
    json["version"] = 999
    let modified = try JSONSerialization.data(withJSONObject: json)
    let compressed = try modified.zlibCompressed()

    #expect {
      try parseBackup(data: compressed)
    } throws: { error in
      guard let backupError = error as? AppBackupError,
            case let .unsupportedVersion(found, max) = backupError else {
        return false
      }
      return found == 999 && max == AppBackupEnvelope.currentVersion
    }
  }

  @Test
  func `parseBackup throws corruptedManifest when counts mismatch`() throws {
    let envelope = makeTestEnvelope(radioID: UUID())
    let wrongManifest = BackupManifest(deviceCount: 0)
    let tampered = AppBackupEnvelope(
      appVersion: envelope.appVersion,
      appBuild: envelope.appBuild,
      manifest: wrongManifest,
      devices: envelope.devices,
      contacts: envelope.contacts
    )

    let json = try makeBackupJSONEncoder().encode(tampered)
    let compressed = try json.zlibCompressed()

    #expect(throws: AppBackupError.self) {
      try parseBackup(data: compressed)
    }
  }

  // MARK: - BackupManifest validation

  @Test
  func `Manifest validates correctly when counts match`() {
    let envelope = makeTestEnvelope(radioID: UUID())
    #expect(envelope.manifest.validate(against: envelope))
  }

  @Test
  func `Manifest detects mismatch`() {
    var envelope = makeTestEnvelope(radioID: UUID())
    envelope.devices.append(DeviceDTO.testDevice())
    #expect(!envelope.manifest.validate(against: envelope))
  }

  // MARK: - ImportResult

  @Test
  func `ImportResult computes totals correctly`() {
    var result = ImportResult()
    result.record(.devices, inserted: 2)
    result.record(.contacts, inserted: 5, merged: 1)
    result.record(.messages, skipped: 3)
    result.userDefaultsRestored = true

    #expect(result.totalInserted == 7)
    #expect(result.totalMerged == 1)
    #expect(result.totalSkipped == 3)
    #expect(result.hasRestoredChanges)
  }

  // MARK: - BackupUserDefaults Codable round-trip

  @Test
  func `BackupUserDefaults round-trips through JSON`() throws {
    var prefs = BackupUserDefaults()
    prefs.hasCompletedOnboarding = true
    prefs.mapStyleSelection = "topo"
    prefs.autoDeleteStaleNodesDays = 30
    prefs.frequentEmojis = ["👍", "❤️"]
    prefs.recentEmojis = ["😂", "😮"]
    prefs.notifyContactMessages = false
    prefs.linkPreviewsEnabled = true
    prefs.showIncomingRegion = true
    prefs.showIncomingSendTime = true

    let data = try JSONEncoder().encode(prefs)
    let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: data)

    #expect(decoded == prefs)
    #expect(decoded.showIncomingRegion == true)
    #expect(decoded.showIncomingSendTime == true)
  }

  @Test
  func `Legacy envelope without showIncomingSendTime decodes to nil and restore skips it`() throws {
    // A backup predating the toggle has no key. decodeIfPresent must yield nil,
    // and write-if-missing restore must leave any existing local value untouched.
    let legacyJSON = "{\"hasCompletedOnboarding\":true}"
    let data = Data(legacyJSON.utf8)

    let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: data)
    #expect(decoded.showIncomingSendTime == nil)

    let suiteName = "test.showIncomingSendTime.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    let key = AppStorageKey.showIncomingSendTime.rawValue
    let setKeys = decoded.restore(to: defaults)
    #expect(!setKeys.contains(key))
    #expect(defaults.object(forKey: key) == nil)
  }

  // MARK: - AppBackupError descriptions

  @Test
  func `AppBackupError provides user-facing descriptions`() {
    let errors: [AppBackupError] = [
      .invalidFile,
      .fileTooLarge(actualBytes: 100_000_000, maxBytes: 50_000_000),
      .unsupportedVersion(found: 5, maxSupported: 1),
      .corruptedManifest,
      .exportFailed(underlying: NSError(domain: "test", code: 1)),
      .importFailed(underlying: NSError(domain: "test", code: 2)),
    ]

    for error in errors {
      #expect(error.errorDescription != nil)
    }

    // Verify importFailed does not claim "no data was changed"
    let importError = AppBackupError.importFailed(underlying: NSError(domain: "", code: 0))
    let description = importError.errorDescription ?? ""
    #expect(!description.lowercased().contains("no data was changed"))
  }

  // MARK: - Helpers

  private func makeTestEnvelope(radioID: UUID) -> AppBackupEnvelope {
    let device = DeviceDTO.testDevice()
    let contact = ContactDTO.testContact(radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID)
    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: UUID())
    let messageRepeat = MessageRepeatDTO.testRepeat(messageID: UUID())
    let reaction = ReactionDTO.testReaction(messageID: UUID(), radioID: radioID)
    let roomMessage = RoomMessageDTO.testRoomMessage(sessionID: UUID())
    let session = RemoteNodeSessionDTO.testSession(radioID: radioID)
    let tracePath = SavedTracePathDTO.testPath(radioID: radioID, runs: [TracePathRunDTO.testRun()])
    let blocked = BlockedChannelSenderDTO.testBlockedSender(radioID: radioID)
    let snapshot = NodeStatusSnapshotDTO.testSnapshot(
      nodePublicKey: Data(repeating: 0xAA, count: 32),
      neighborSnapshots: []
    )

    var prefs = BackupUserDefaults()
    prefs.hasCompletedOnboarding = true
    prefs.mapStyleSelection = "standard"

    let manifest = BackupManifest(
      deviceCount: 1,
      contactCount: 1,
      channelCount: 1,
      messageCount: 1,
      messageRepeatCount: 1,
      reactionCount: 1,
      roomMessageCount: 1,
      remoteNodeSessionCount: 1,
      savedTracePathCount: 1,
      blockedChannelSenderCount: 1,
      nodeStatusSnapshotCount: 1
    )

    return AppBackupEnvelope(
      appVersion: "1.0.0",
      appBuild: "42",
      manifest: manifest,
      devices: [device],
      contacts: [contact],
      channels: [channel],
      messages: [message],
      messageRepeats: [messageRepeat],
      reactions: [reaction],
      roomMessages: [roomMessage],
      remoteNodeSessions: [session],
      savedTracePaths: [tracePath],
      blockedChannelSenders: [blocked],
      nodeStatusSnapshots: [snapshot],
      userDefaults: prefs
    )
  }
}
