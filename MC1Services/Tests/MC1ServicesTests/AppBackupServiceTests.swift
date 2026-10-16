import Foundation
@testable import MC1Services
import Testing

@Suite("AppBackupService")
struct AppBackupServiceTests {
  // MARK: - Export: happy path

  @Test
  func `Export produces valid compressed backup`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Seed one contact and two messages (one with link preview blobs)
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0xAA, count: 32))
    try await store.saveContact(contact)

    let plainMsg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    try await store.saveMessage(plainMsg)

    let blobMsgID = UUID()
    let blobMsg = MessageDTO.testDirectMessage(
      id: blobMsgID,
      radioID: radioID,
      contactID: contact.id,
      text: "Check this out: https://example.com"
    )
    try await store.saveMessage(blobMsg)
    try await store.updateMessageLinkPreview(
      id: blobMsgID,
      url: "https://example.com",
      title: "Example",
      imageData: Data(repeating: 0xFF, count: 1024),
      iconData: Data(repeating: 0xFE, count: 32),
      fetched: true
    )

    let service = AppBackupService()

    let result = try await service.export(persistenceStore: store)

    // Must be non-empty compressed data
    #expect(!result.data.isEmpty)

    // Round-trip: parse it back
    let envelope = try parseBackup(data: result.data)

    // Manifest counts
    #expect(envelope.devices.count == 1)
    #expect(envelope.contacts.count == 1)
    #expect(envelope.messages.count == 2)
    #expect(envelope.manifest.validate(against: envelope))
  }

  // MARK: - Link preview blobs are stripped

  @Test
  func `Export strips link preview blobs from messages`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0xBB, count: 32))
    try await store.saveContact(contact)

    let msg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    try await store.saveMessage(msg)

    // Populate link preview fields (set separately after initial save)
    try await store.updateMessageLinkPreview(
      id: msg.id,
      url: "https://example.com",
      title: "Example Domain",
      imageData: Data(repeating: 0x01, count: 500),
      iconData: Data(repeating: 0x02, count: 64),
      fetched: true
    )

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    let envelope = try parseBackup(data: result.data)
    let exported = try #require(envelope.messages.first)

    // URL and title are preserved
    #expect(exported.linkPreviewURL == "https://example.com")
    #expect(exported.linkPreviewTitle == "Example Domain")

    // Blobs and fetched flag are cleared
    #expect(exported.linkPreviewImageData == nil)
    #expect(exported.linkPreviewIconData == nil)
    #expect(exported.linkPreviewFetched == false)
  }

  // MARK: - Empty store

  @Test
  func `Export succeeds with empty store`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    let envelope = try parseBackup(data: result.data)
    #expect(envelope.devices.count == 1)
    #expect(envelope.contacts.count == 0)
    #expect(envelope.messages.count == 0)
    #expect(envelope.manifest.validate(against: envelope))
  }

  // MARK: - Manifest accuracy

  @Test
  func `Manifest counts match exported array sizes`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Add 3 contacts
    for i in 0..<3 {
      let contact = ContactDTO.testContact(
        radioID: radioID,
        publicKey: Data(repeating: UInt8(i + 1), count: 32)
      )
      try await store.saveContact(contact)
    }

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    let envelope = try parseBackup(data: result.data)
    #expect(envelope.manifest.contactCount == 3)
    #expect(envelope.manifest.deviceCount == 1)
    #expect(envelope.manifest.validate(against: envelope))
  }

  // MARK: - Output is compressed

  @Test
  func `Export output is smaller than raw JSON for large payloads`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Add enough messages to make compression meaningful
    for i in 0..<20 {
      let contact = ContactDTO.testContact(
        radioID: radioID,
        publicKey: Data(repeating: UInt8(i % 255 + 1), count: 32) + Data([UInt8(i / 255)])
      )
      try await store.saveContact(contact)
    }

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    // Verify it decompresses successfully (proves it is valid compressed data)
    let decompressed = try (result.data as NSData).decompressed(using: .zlib) as Data
    #expect(decompressed.count > result.data.count)
  }

  // MARK: - Version and source bundle

  @Test
  func `Envelope contains expected version`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    let envelope = try parseBackup(data: result.data)
    #expect(envelope.version == AppBackupEnvelope.currentVersion)
  }

  // MARK: - Import: into empty store

  @Test
  func `Import into empty store restores all records`() async throws {
    let radioID = UUID()
    let device = DeviceDTO.testDevice(id: radioID, radioID: radioID, publicKey: Data(repeating: 0x01, count: 32))
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0xAA, count: 32))
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 0, name: "General")
    let msg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    let session = RemoteNodeSessionDTO.testSession(radioID: radioID)
    let roomMsg = RoomMessageDTO.testRoomMessage(sessionID: session.id)
    let tracePath = SavedTracePathDTO.testPath(radioID: radioID)
    let blocked = BlockedChannelSenderDTO.testBlockedSender(radioID: radioID)
    let snapshot = NodeStatusSnapshotDTO.testSnapshot()
    let reaction = ReactionDTO.testReaction(messageID: msg.id, radioID: radioID)
    let msgRepeat = MessageRepeatDTO.testRepeat(messageID: msg.id)

    let envelope = AppBackupEnvelope.test(
      devices: [device],
      contacts: [contact],
      channels: [channel],
      messages: [msg],
      messageRepeats: [msgRepeat],
      reactions: [reaction],
      roomMessages: [roomMsg],
      remoteNodeSessions: [session],
      savedTracePaths: [tracePath],
      blockedChannelSenders: [blocked],
      nodeStatusSnapshots: [snapshot]
    )

    // Import into a fresh empty store
    let destContainer = try PersistenceStore.createContainer(inMemory: true)
    let destStore = PersistenceStore(modelContainer: destContainer)

    let service = AppBackupService()
    let result = try await service.importBackup(
      envelope: envelope,
      into: destStore
    )

    #expect(result.devicesInserted == 1)
    #expect(result.contactsInserted == 1)
    #expect(result.channelsInserted == 1)
    #expect(result.messagesInserted == 1)
    #expect(result.messageRepeatsInserted == 1)
    #expect(result.reactionsInserted == 1)
    #expect(result.remoteNodeSessionsInserted == 1)
    #expect(result.roomMessagesInserted == 1)
    #expect(result.savedTracePathsInserted == 1)
    #expect(result.blockedChannelSendersInserted == 1)
    #expect(result.nodeStatusSnapshotsInserted == 1)
    #expect(result.totalSkipped == 0)

    // Verify records persisted
    let destDevices = try await destStore.fetchAllDevices()
    #expect(destDevices.count == 1)

    let destContacts = try await destStore.fetchAllContacts(radioID: radioID)
    #expect(destContacts.count == 1)
  }

  // MARK: - Import: radioID remapping

  @Test
  func `Import remaps radioID when local device has same publicKey`() async throws {
    let sharedPublicKey = Data(repeating: 0xDD, count: 32)
    let backupRadioID = UUID()
    let localRadioID = UUID()

    // Build a backup envelope with the backup radioID
    let backupDevice = DeviceDTO.testDevice(
      id: backupRadioID,
      radioID: backupRadioID,
      publicKey: sharedPublicKey
    )
    let backupContact = ContactDTO.testContact(
      radioID: backupRadioID,
      publicKey: Data(repeating: 0xEE, count: 32)
    )
    let backupChannel = ChannelDTO.testChannel(radioID: backupRadioID, index: 1, name: "Remapped")
    let backupMessage = MessageDTO.testDirectMessage(radioID: backupRadioID, contactID: backupContact.id)

    let envelope = AppBackupEnvelope.test(
      devices: [backupDevice],
      contacts: [backupContact],
      channels: [backupChannel],
      messages: [backupMessage]
    )

    // Create local store with a device using the same publicKey but different radioID
    let localStore = try await PersistenceStore.createTestDataStore(radioID: localRadioID)
    // Replace the test device with one that has the shared public key
    let localDevice = DeviceDTO.testDevice(
      id: localRadioID,
      radioID: localRadioID,
      publicKey: sharedPublicKey
    )
    try await localStore.saveDevice(localDevice)

    let service = AppBackupService()
    let result = try await service.importBackup(
      envelope: envelope,
      into: localStore
    )

    // Backup device should be skipped (matched by publicKey)
    #expect(result.devicesInserted == 0)

    // Child records should be inserted with the local radioID
    #expect(result.contactsInserted == 1)
    #expect(result.channelsInserted == 1)
    #expect(result.messagesInserted == 1)

    // Verify the contact was stored with localRadioID, not backupRadioID
    let localContacts = try await localStore.fetchAllContacts(radioID: localRadioID)
    #expect(localContacts.count == 1)

    // The backup radioID should have no contacts
    let backupContacts = try await localStore.fetchAllContacts(radioID: backupRadioID)
    #expect(backupContacts.count == 0)

    // Same for channels
    let localChannels = try await localStore.fetchAllChannels(radioID: localRadioID)
    let remappedChannel = localChannels.first { $0.name == "Remapped" }
    #expect(remappedChannel != nil)
  }

  // MARK: - Export: device redaction

  @Test
  func `Export redacts sensitive device fields (BLE PIN, radio config)`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Override the test device with sensitive values
    let sensitiveDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID).copy {
      $0.blePin = 123_456
      $0.frequency = 869_500
      $0.bandwidth = 125_000
      $0.spreadingFactor = 12
      $0.codingRate = 8
      $0.txPower = 14
      $0.maxTxPower = 22
      $0.latitude = 48.8566
      $0.longitude = 2.3522
      $0.clientRepeat = true
      $0.pathHashMode = 2
      $0.preRepeatFrequency = 915_000
      $0.preRepeatBandwidth = 250_000
      $0.preRepeatSpreadingFactor = 10
      $0.preRepeatCodingRate = 5
      $0.autoAddConfig = 0x0F
      $0.autoAddMaxHops = 3
      $0.telemetryModeBase = 3
      $0.telemetryModeLoc = 2
      $0.telemetryModeEnv = 1
      $0.advertLocationPolicy = 2
      $0.manualAddContacts = true
      $0.multiAcks = 4
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: "BT"),
        .wifi(host: "10.0.0.2", port: 5000, displayName: "WiFi")
      ]
    }
    try await store.saveDevice(sensitiveDevice)

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)
    let envelope = try parseBackup(data: result.data)

    let exported = try #require(envelope.devices.first)

    // Sensitive fields must be reset to Device.init defaults
    #expect(exported.blePin == 0)
    #expect(exported.frequency == 915_000)
    #expect(exported.bandwidth == 250_000)
    #expect(exported.spreadingFactor == 10)
    #expect(exported.codingRate == 5)
    #expect(exported.txPower == 20)
    #expect(exported.maxTxPower == 20)
    #expect(exported.latitude == 0)
    #expect(exported.longitude == 0)
    #expect(exported.clientRepeat == false)
    #expect(exported.pathHashMode == 0)
    #expect(exported.preRepeatFrequency == nil)
    #expect(exported.preRepeatBandwidth == nil)
    #expect(exported.preRepeatSpreadingFactor == nil)
    #expect(exported.preRepeatCodingRate == nil)
    #expect(exported.autoAddConfig == 0)
    #expect(exported.autoAddMaxHops == 0)
    #expect(exported.telemetryModeBase == 2)
    #expect(exported.telemetryModeLoc == 0)
    #expect(exported.telemetryModeEnv == 0)
    #expect(exported.advertLocationPolicy == 0)

    // id is reset so the source phone's CBPeripheral UUID never leaves the device.
    #expect(exported.id != radioID)
    // WiFi is intentionally retained (needed for restore-then-reconnect) and disclosed in
    // the export Security Notice; only Bluetooth is stripped.
    #expect(exported.connectionMethods.contains { $0.isWiFi })
    #expect(!exported.connectionMethods.contains { $0.isBluetooth })
    // Radio-config preferences reset to defaults.
    #expect(exported.manualAddContacts == Device.Defaults.manualAddContacts)
    #expect(exported.multiAcks == Device.Defaults.multiAcks)
  }

  @Test
  func `Export preserves non-sensitive device fields`() async throws {
    let radioID = UUID()
    let customPublicKey = Data(repeating: 0xF7, count: 32)
    let connectedDate = Date(timeIntervalSince1970: 1_700_000_000)
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let device = DeviceDTO.testDevice(
      id: radioID,
      radioID: radioID,
      publicKey: customPublicKey,
      nodeName: "FieldUnit",
      firmwareVersion: 11,
      firmwareVersionString: "v1.15.0",
      lastConnected: connectedDate,
      lastContactSync: 42
    ).copy {
      $0.ocvPreset = "liIon"
      $0.customOCVArrayString = "4200,4100,4000"
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: "BT"),
        .wifi(host: "10.0.0.2", port: 5000, displayName: "WiFi")
      ]
    }
    try await store.saveDevice(device)

    // knownRegions is owned by the targeted add path, not saveDevice's overwrite.
    try await store.addDeviceKnownRegion(radioID: radioID, region: "US")
    try await store.addDeviceKnownRegion(radioID: radioID, region: "EU")

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)
    let envelope = try parseBackup(data: result.data)

    let exported = try #require(envelope.devices.first)

    #expect(exported.publicKey == customPublicKey)
    #expect(exported.nodeName == "FieldUnit")
    #expect(exported.firmwareVersionString == "v1.15.0")
    #expect(exported.lastConnected == connectedDate)
    #expect(exported.lastContactSync == 42)
    #expect(exported.ocvPreset == "liIon")
    #expect(exported.customOCVArrayString == "4200,4100,4000")
    #expect(exported.connectionMethods.count == 1)
    #expect(exported.connectionMethods.first?.isWiFi == true)
    #expect(exported.knownRegions == ["US", "EU"])
  }

  @Test
  func `Export strips Bluetooth connection methods`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let device = DeviceDTO.testDevice(id: radioID, radioID: radioID).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: UUID(), displayName: "BT")]
    }
    try await store.saveDevice(device)

    let result = try await AppBackupService().export(persistenceStore: store)
    let envelope = try parseBackup(data: result.data)

    let exported = try #require(envelope.devices.first)
    #expect(exported.connectionMethods.isEmpty)
  }

  @Test
  func `Import strips Bluetooth connection methods from legacy backups`() async throws {
    let radioID = UUID()
    let localStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Simulate a legacy backup where the export-side filter did not yet
    // strip BLE methods — the importer must still remove them so the
    // restored row isn't keyed to a nonexistent local peripheral.
    let legacyBackupDevice = DeviceDTO.testDevice(
      id: UUID(),
      publicKey: Data(repeating: 0xAB, count: 32)
    ).copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: "BT"),
        .wifi(host: "10.0.0.2", port: 5000, displayName: "WiFi")
      ]
    }
    let envelope = AppBackupEnvelope.test(devices: [legacyBackupDevice])

    _ = try await localStore.importBackupDatabase(envelope)

    let localDevices = try await localStore.fetchAllDevices()
    let restored = try #require(localDevices.first { $0.publicKey == legacyBackupDevice.publicKey })
    #expect(restored.connectionMethods.count == 1)
    #expect(restored.connectionMethods.first?.isWiFi == true)
  }

  @Test
  func `Redacted device round-trips through export and import with safe defaults`() async throws {
    let radioID = UUID()
    let customPublicKey = Data(repeating: 0xF8, count: 32)
    let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let sensitiveDevice = DeviceDTO.testDevice(
      id: radioID,
      radioID: radioID,
      publicKey: customPublicKey
    ).copy {
      $0.blePin = 999_999
      $0.frequency = 433_000
      $0.txPower = 10
    }
    try await sourceStore.saveDevice(sensitiveDevice)

    // Export
    let service = AppBackupService()
    let exportResult = try await service.export(persistenceStore: sourceStore)
    let envelope = try parseBackup(data: exportResult.data)

    // Import into a fresh store (unmatched device — will be inserted)
    let destContainer = try PersistenceStore.createContainer(inMemory: true)
    let destStore = PersistenceStore(modelContainer: destContainer)
    let result = try await service.importBackup(
      envelope: envelope,
      into: destStore
    )

    #expect(result.devicesInserted == 1)

    // Verify the imported device has redacted values, not the originals
    let imported = try #require(await destStore.fetchAllDevices().first)
    #expect(imported.blePin == 0)
    #expect(imported.frequency == 915_000)
    #expect(imported.txPower == 20)
    // Identity preserved
    #expect(imported.publicKey == customPublicKey)
  }

  // MARK: - Import: idempotent

  @Test
  func `Importing same backup twice results in zero inserts on second pass`() async throws {
    let radioID = UUID()
    let device = DeviceDTO.testDevice(id: radioID, radioID: radioID, publicKey: Data(repeating: 0x02, count: 32))
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xCC, count: 32)
    )
    // Messages must have a deduplicationKey to be detected as duplicates
    var msg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    msg.deduplicationKey = "test-dedup-key-1"

    let envelope = AppBackupEnvelope.test(
      devices: [device],
      contacts: [contact],
      messages: [msg]
    )

    let destContainer = try PersistenceStore.createContainer(inMemory: true)
    let destStore = PersistenceStore(modelContainer: destContainer)

    let service = AppBackupService()

    let firstResult = try await service.importBackup(
      envelope: envelope,
      into: destStore
    )
    #expect(firstResult.devicesInserted == 1)
    #expect(firstResult.contactsInserted == 1)
    #expect(firstResult.messagesInserted == 1)

    // Import the same backup again
    let secondResult = try await service.importBackup(
      envelope: envelope,
      into: destStore
    )

    #expect(secondResult.devicesInserted == 0)
    #expect(secondResult.contactsInserted == 0)
    #expect(secondResult.messagesInserted == 0)
    #expect(secondResult.totalInserted == 0)
  }

  // MARK: - Export: ExportResult

  @Test
  func `Export returns ExportResult carrying the envelope manifest`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0xBB, count: 32))
    try await store.saveContact(contact)

    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    try await store.saveMessage(message)

    let service = AppBackupService()
    let result = try await service.export(persistenceStore: store)

    #expect(result.data.isEmpty == false)
    #expect(result.manifest.contactCount == 1)
    #expect(result.manifest.messageCount == 1)
    #expect(result.manifest.count(for: .contacts) == 1)
    #expect(result.manifest.count(for: .messages) == 1)
  }

  // MARK: - Import: Device.id collision does not overwrite local row

  @Test
  func `Import assigns fresh Device.id so an id collision cannot upsert the local row`() async throws {
    // Simulates radio identity rotation: the backup's DeviceDTO carries a UUID
    // that happens to equal the current local peripheral's UUID (iOS reuses the
    // same CBPeripheral.identifier after a firmware pubKey change). Without the
    // fix, SwiftData's upsert on `@Attribute(.unique) Device.id` would silently
    // overwrite the local row's publicKey; with the fix, the backup row is
    // inserted with a fresh id alongside the existing local row.
    let collidingID = UUID()
    let oldPublicKey = Data(repeating: 0xA0, count: 32)
    let newPublicKey = Data(repeating: 0xB0, count: 32)

    let destStore = try await PersistenceStore.createTestDataStore(radioID: collidingID)
    let localDevice = DeviceDTO.testDevice(
      id: collidingID,
      radioID: collidingID,
      publicKey: newPublicKey
    )
    try await destStore.saveDevice(localDevice)

    let backupDevice = DeviceDTO.testDevice(
      id: collidingID,
      radioID: UUID(),
      publicKey: oldPublicKey
    )
    let envelope = AppBackupEnvelope.test(devices: [backupDevice])

    let result = try await AppBackupService().importBackup(
      envelope: envelope,
      into: destStore
    )
    #expect(result.devicesInserted == 1)

    let stored = try await destStore.fetchAllDevices()
    #expect(stored.count == 2)

    // The original local device is still there, with its original pubKey intact.
    let localAfter = try #require(stored.first { $0.publicKey == newPublicKey })
    #expect(localAfter.id == collidingID)

    // The backup landed as a separate row with a fresh id.
    let backupAfter = try #require(stored.first { $0.publicKey == oldPublicKey })
    #expect(backupAfter.id != collidingID)
  }
}
