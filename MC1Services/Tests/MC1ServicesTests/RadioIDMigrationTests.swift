import CryptoKit
import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("RadioID Migration Tests", .serialized)
struct RadioIDMigrationTests {
  // MARK: - Test Helpers

  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  // MARK: - radioID Propagation

  @Test
  func `Migration propagates new radioID from Device to all children`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()

    // Create device with id = bleUUID; radioID will get UUID() default but we
    // simulate the post-rename state where children have radioID == bleUUID.
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    let contact = ContactDTO.testContact(id: UUID(), radioID: bleUUID, name: "Alice")
    try await store.saveContact(contact)

    let channel = ChannelDTO(
      id: UUID(),
      radioID: bleUUID,
      index: 0,
      name: "General",
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      notificationLevel: .all
    )
    try await store.saveChannel(channel)

    let messageID = UUID()
    let message = MessageDTO.testDirectMessage(
      id: messageID,
      radioID: bleUUID,
      contactID: contact.id,
      text: "Hello",
      direction: .outgoing,
      status: .sent
    )
    try await store.saveMessage(message)

    try await store.performRadioIDMigration()

    // Device's radioID should be different from bleUUID
    let fetchedDevice = try await store.fetchDevice(id: bleUUID)
    #expect(fetchedDevice != nil)
    let newRadioID = try #require(fetchedDevice?.radioID)
    #expect(newRadioID != bleUUID, "Device should have a new radioID, not the old BLE UUID")

    // All children should share the new radioID
    let contacts = try await store.fetchContacts(radioID: newRadioID)
    #expect(contacts.count == 1)
    #expect(contacts.first?.radioID == newRadioID)

    let channels = try await store.fetchChannels(radioID: newRadioID)
    #expect(channels.count == 1)
    #expect(channels.first?.radioID == newRadioID)

    let fetchedMessage = try await store.fetchMessage(id: messageID)
    #expect(fetchedMessage != nil)
    #expect(fetchedMessage?.radioID == newRadioID)
  }

  // MARK: - Dedup Key Backfill

  @Test
  func `Outgoing DM with nil dedup key gets backfilled`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    let contactID = UUID()
    let timestamp: UInt32 = 1_704_067_200
    let text = "Hello mesh"
    let messageID = UUID()

    let message = MessageDTO(
      id: messageID,
      radioID: bleUUID,
      contactID: contactID,
      channelIndex: nil,
      text: text,
      timestamp: timestamp,
      createdAt: Date(),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      deduplicationKey: nil
    )
    try await store.saveMessage(message)

    try await store.performRadioIDMigration()

    let fetchedMsg = try await store.fetchMessage(id: messageID)
    #expect(fetchedMsg != nil)

    let key = fetchedMsg?.deduplicationKey
    #expect(key != nil, "Outgoing message should have a dedup key after migration")

    // Verify format matches fallbackDeduplicationKey exactly
    let expectedKey = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID,
      channelIndex: nil,
      senderNodeName: nil,
      timestamp: timestamp,
      content: text
    )
    #expect(key == expectedKey, "Dedup key format must match fallbackDeduplicationKey: got \(key ?? "nil"), expected \(expectedKey)")
  }

  @Test
  func `Incoming message with nil dedup key stays nil`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    let messageID = UUID()
    let message = MessageDTO(
      id: messageID,
      radioID: bleUUID,
      contactID: UUID(),
      channelIndex: nil,
      text: "Incoming hello",
      timestamp: 1_704_067_200,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 1,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      deduplicationKey: nil
    )
    try await store.saveMessage(message)

    try await store.performRadioIDMigration()

    let fetchedMsg = try await store.fetchMessage(id: messageID)
    #expect(fetchedMsg != nil)
    #expect(fetchedMsg?.deduplicationKey == nil, "Incoming message dedup key should stay nil")
  }

  @Test
  func `Existing dedup key is not overwritten`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    let existingKey = "dm-existing-key-12345678"
    let messageID = UUID()
    let message = MessageDTO(
      id: messageID,
      radioID: bleUUID,
      contactID: UUID(),
      channelIndex: nil,
      text: "Already keyed",
      timestamp: 1_704_067_200,
      createdAt: Date(),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      deduplicationKey: existingKey
    )
    try await store.saveMessage(message)

    try await store.performRadioIDMigration()

    let fetchedMsg = try await store.fetchMessage(id: messageID)
    #expect(fetchedMsg != nil)
    #expect(fetchedMsg?.deduplicationKey == existingKey, "Pre-existing dedup key must not be modified")
  }

  @Test
  func `UserDefaults guard prevents re-run`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    let contact = ContactDTO.testContact(id: UUID(), radioID: bleUUID, name: "Bob")
    try await store.saveContact(contact)

    // First run
    try await store.performRadioIDMigration()

    let fetchedDevice = try await store.fetchDevice(id: bleUUID)
    let firstRadioID = try #require(fetchedDevice?.radioID)
    #expect(firstRadioID != bleUUID)

    // Second run should be a no-op (guarded by UserDefaults)
    try await store.performRadioIDMigration()

    let fetchedDeviceAgain = try await store.fetchDevice(id: bleUUID)
    #expect(fetchedDeviceAgain?.radioID == firstRadioID, "Second migration run should not change radioID")

    let contacts = try await store.fetchContacts(radioID: firstRadioID)
    #expect(contacts.count == 1, "Contact should still have the radioID from the first run")
  }

  // MARK: - lastConnectedRadioID Backfill

  @Test
  func `Migration backfills lastConnectedRadioID from lastConnectedDeviceID`() async throws {
    let store = try await createTestStore()
    await store.resetRadioIDMigrationFlag()

    let bleUUID = UUID()
    let device = DeviceDTO.testDevice(id: bleUUID, radioID: bleUUID)
    try await store.saveDevice(device)

    // Simulate pre-upgrade state: lastConnectedDeviceID exists, lastConnectedRadioID does not
    UserDefaults.standard.set(bleUUID.uuidString, forKey: "com.pocketmesh.lastConnectedDeviceID")
    UserDefaults.standard.removeObject(forKey: "com.pocketmesh.lastConnectedRadioID")

    try await store.performRadioIDMigration()

    // lastConnectedRadioID should now be populated
    let radioIDString = UserDefaults.standard.string(forKey: "com.pocketmesh.lastConnectedRadioID")
    #expect(radioIDString != nil)

    // It should match the device's new radioID
    let fetchedDevice = try await store.fetchDevice(id: bleUUID)
    #expect(radioIDString == fetchedDevice?.radioID.uuidString)

    // Clean up
    UserDefaults.standard.removeObject(forKey: "com.pocketmesh.lastConnectedDeviceID")
    UserDefaults.standard.removeObject(forKey: "com.pocketmesh.lastConnectedRadioID")
  }
}
