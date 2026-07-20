import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("PersistenceStore Tests")
struct PersistenceStoreTests {
  // MARK: - Test Helpers

  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func createTestDevice(id: UUID = UUID()) -> DeviceDTO {
    DeviceDTO.testDevice(
      id: id,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: false
    ).copy {
      $0.latitude = 37.7749
      $0.longitude = -122.4194
    }
  }

  private func createTestContactFrame(name: String = "TestContact") -> ContactFrame {
    ContactFrame(
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      type: .chat,
      flags: 0,
      outPathLength: 2,
      outPath: Data([0x01, 0x02]),
      name: name,
      lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
      latitude: 37.7749,
      longitude: -122.4194,
      lastModified: UInt32(Date().timeIntervalSince1970)
    )
  }

  // MARK: - Device Tests

  @Test
  func `Save and fetch device`() async throws {
    let store = try await createTestStore()
    let deviceDTO = createTestDevice()

    try await store.saveDevice(deviceDTO)

    let fetched = try await store.fetchDevice(id: deviceDTO.id)
    #expect(fetched != nil)
    #expect(fetched?.nodeName == "TestDevice")
    #expect(fetched?.firmwareVersion == 8)
    #expect(fetched?.frequency == 915_000)
  }

  @Test
  func `Fetch all devices`() async throws {
    let store = try await createTestStore()

    let device1 = createTestDevice()
    let device2 = createTestDevice()

    try await store.saveDevice(device1)
    try await store.saveDevice(device2)

    let devices = try await store.fetchDevices()
    #expect(devices.count == 2)
  }

  @Test
  func `Set active device`() async throws {
    let store = try await createTestStore()

    let device1 = createTestDevice()
    let device2 = createTestDevice()

    try await store.saveDevice(device1)
    try await store.saveDevice(device2)

    try await store.setActiveDevice(id: device1.id)

    let active = try await store.fetchActiveDevice()
    #expect(active?.id == device1.id)
    #expect(active?.isActive == true)

    // Now set device2 as active
    try await store.setActiveDevice(id: device2.id)

    let newActive = try await store.fetchActiveDevice()
    #expect(newActive?.id == device2.id)

    // Verify device1 is no longer active
    let device1Fetched = try await store.fetchDevice(id: device1.id)
    #expect(device1Fetched?.isActive == false)
  }

  // MARK: - Message status terminal-safety

  @Test
  func `updateMessageRetryStatus does not resurrect a .failed row`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = try await store.saveContact(radioID: radioID, from: createTestContactFrame()).id

    let message = MessageDTO(from: Message(
      radioID: radioID,
      contactID: contactID,
      text: "give-up race",
      timestamp: UInt32(Date().timeIntervalSince1970),
      directionRawValue: MessageDirection.outgoing.rawValue
    ))
    try await store.saveMessage(message)

    // The expiry checker fails the row in the loop's await-gap.
    try await store.updateMessageStatus(id: message.id, status: .failed)

    // A stale retry iteration must not flip it back to .retrying.
    try await store.updateMessageRetryStatus(id: message.id, status: .retrying, retryAttempt: 1, maxRetryAttempts: 4)

    let fetched = try await store.fetchMessage(id: message.id)
    #expect(fetched?.status == .failed)
  }

  @Test
  func `updateMessageRetryStatus still advances a non-terminal row`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = try await store.saveContact(radioID: radioID, from: createTestContactFrame()).id

    let message = MessageDTO(from: Message(
      radioID: radioID,
      contactID: contactID,
      text: "in flight",
      timestamp: UInt32(Date().timeIntervalSince1970),
      directionRawValue: MessageDirection.outgoing.rawValue
    ))
    try await store.saveMessage(message)

    try await store.updateMessageRetryStatus(id: message.id, status: .retrying, retryAttempt: 0, maxRetryAttempts: 4)

    let fetched = try await store.fetchMessage(id: message.id)
    #expect(fetched?.status == .retrying)
  }

  /// Seeds all entity types for a device and returns IDs needed for verification.
  private func seedAllEntityTypes(store: PersistenceStore, radioID: UUID) async throws -> (
    contactID: UUID, messageID: UUID, channelID: UUID, sessionID: UUID
  ) {
    let contactFrame = createTestContactFrame(name: "TestContact")
    let contactID = try await store.saveContact(radioID: radioID, from: contactFrame).id

    let message = MessageDTO(from: Message(
      radioID: radioID,
      contactID: contactID,
      text: "Hello!",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(message)
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))

    let channelInfo = ChannelInfo(index: 1, name: "Private", secret: Data(repeating: 0x42, count: 16))
    let channelID = try await store.saveChannel(radioID: radioID, from: channelInfo)

    let reaction = ReactionDTO(
      messageID: message.id,
      emoji: "👍",
      senderName: "Reactor",
      messageHash: "AABBCCDD",
      rawText: "👍",
      radioID: radioID
    )
    try await store.saveReaction(reaction)

    let session = createTestRoomSession(radioID: radioID)
    try await store.saveRemoteNodeSessionDTO(session)

    let roomMessage = RoomMessageDTO(
      sessionID: session.id,
      authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
      authorName: "Author",
      text: "Room message",
      timestamp: UInt32(Date().timeIntervalSince1970)
    )
    try await store.saveRoomMessage(roomMessage)

    let blocked = BlockedChannelSenderDTO(name: "Spammer", radioID: radioID)
    try await store.saveBlockedChannelSender(blocked)

    let rxLog = createTestRxLogEntryDTO(radioID: radioID, senderTimestamp: 12345)
    try await store.saveRxLogEntry(rxLog)

    let discoveredFrame = createTestContactFrame(name: "Discovered")
    _ = try await store.upsertDiscoveredNode(radioID: radioID, from: discoveredFrame)

    return (contactID, message.id, channelID, session.id)
  }

  /// Asserts all entity types for a device are present.
  private func assertAllDataExists(
    store: PersistenceStore, radioID: UUID, sessionID: UUID, messageID: UUID
  ) async throws {
    let contacts = try await store.fetchContacts(radioID: radioID)
    #expect(contacts.count == 1, "Expected 1 contact")
    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.count == 1, "Expected 1 channel")
    let reactions = try await store.fetchReactions(for: messageID)
    #expect(reactions.count == 1, "Expected 1 reaction")
    let sessions = try await store.fetchRemoteNodeSessions(radioID: radioID)
    #expect(sessions.count == 1, "Expected 1 session")
    let roomMessages = try await store.fetchRoomMessages(sessionID: sessionID)
    #expect(roomMessages.count == 1, "Expected 1 room message")
    let blockedSenders = try await store.fetchBlockedChannelSenders(radioID: radioID)
    #expect(blockedSenders.count == 1, "Expected 1 blocked sender")
    let rxEntries = try await store.fetchRxLogEntries(radioID: radioID)
    #expect(rxEntries.count == 1, "Expected 1 RX log entry")
    let discoveredNodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(discoveredNodes.count == 1, "Expected 1 discovered node")
  }

  /// Asserts all entity types for a device have been deleted.
  private func assertAllDataDeleted(
    store: PersistenceStore, radioID: UUID, sessionID: UUID, messageID: UUID
  ) async throws {
    let contacts = try await store.fetchContacts(radioID: radioID)
    #expect(contacts.isEmpty, "Expected no contacts")
    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.isEmpty, "Expected no channels")
    let reactions = try await store.fetchReactions(for: messageID)
    #expect(reactions.isEmpty, "Expected no reactions")
    let sessions = try await store.fetchRemoteNodeSessions(radioID: radioID)
    #expect(sessions.isEmpty, "Expected no sessions")
    let roomMessages = try await store.fetchRoomMessages(sessionID: sessionID)
    #expect(roomMessages.isEmpty, "Expected no room messages")
    let blockedSenders = try await store.fetchBlockedChannelSenders(radioID: radioID)
    #expect(blockedSenders.isEmpty, "Expected no blocked senders")
    let rxEntries = try await store.fetchRxLogEntries(radioID: radioID)
    #expect(rxEntries.isEmpty, "Expected no RX log entries")
    let discoveredNodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(discoveredNodes.isEmpty, "Expected no discovered nodes")
    let repeats = try await store.fetchMessageRepeats(messageID: messageID)
    #expect(repeats.isEmpty, "Expected no message repeats")
  }

  @Test
  func `deleteDevice removes only device record, preserves all associated data`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let ids = try await seedAllEntityTypes(store: store, radioID: device.id)

    try await store.deleteDevice(id: device.id)

    let fetchedDevice = try await store.fetchDevice(id: device.id)
    #expect(fetchedDevice == nil, "Device record should be deleted")

    try await assertAllDataExists(
      store: store, radioID: device.id,
      sessionID: ids.sessionID, messageID: ids.messageID
    )
  }

  @Test
  func `deleteDeviceData removes all associated data but not device record`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let ids = try await seedAllEntityTypes(store: store, radioID: device.id)

    try await store.deleteDeviceData(id: device.id)

    let fetchedDevice = try await store.fetchDevice(id: device.id)
    #expect(fetchedDevice != nil, "Device record should be preserved")

    try await assertAllDataDeleted(
      store: store, radioID: device.id,
      sessionID: ids.sessionID, messageID: ids.messageID
    )
  }

  @Test
  func `deleteDeviceAndData removes device and all data atomically`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let ids = try await seedAllEntityTypes(store: store, radioID: device.id)

    try await store.deleteDeviceAndData(id: device.id)

    let fetchedDevice = try await store.fetchDevice(id: device.id)
    #expect(fetchedDevice == nil, "Device record should be deleted")

    try await assertAllDataDeleted(
      store: store, radioID: device.id,
      sessionID: ids.sessionID, messageID: ids.messageID
    )
  }

  @Test
  func `deleteDeviceAndData does not trap when a message has heard repeats (cascade-vs-batch)`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let contactID = try await store.saveContact(radioID: device.id, from: createTestContactFrame()).id
    let message = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "broadcast",
      timestamp: UInt32(Date().timeIntervalSince1970),
      directionRawValue: MessageDirection.outgoing.rawValue
    ))
    try await store.saveMessage(message)
    // Two heard repeats wire up message.repeats so the delete exercises cascade
    // propagation over a relationship whose child rows were batch-deleted first.
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))

    try await store.deleteDeviceAndData(id: device.id)

    #expect(try await store.fetchDevice(id: device.id) == nil)
    #expect(try await store.fetchMessage(id: message.id) == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  @Test
  func `deleteDeviceData reaps a message and its heard repeats while preserving the device`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let contactID = try await store.saveContact(radioID: device.id, from: createTestContactFrame()).id
    let message = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "broadcast",
      timestamp: UInt32(Date().timeIntervalSince1970),
      directionRawValue: MessageDirection.outgoing.rawValue
    ))
    try await store.saveMessage(message)
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))

    try await store.deleteDeviceData(id: device.id)

    #expect(try await store.fetchDevice(id: device.id) != nil)
    #expect(try await store.fetchMessage(id: message.id) == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  @Test
  func `deleteDeviceData for non-existent device does not throw`() async throws {
    let store = try await createTestStore()
    try await store.deleteDeviceData(id: UUID())
  }

  @Test
  func `deleteDeviceAndData for non-existent device does not throw`() async throws {
    let store = try await createTestStore()
    try await store.deleteDeviceAndData(id: UUID())
  }

  @Test
  func `Re-pair after device deletion re-associates orphaned data`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let contactFrame = createTestContactFrame(name: "Survivor")
    _ = try await store.saveContact(radioID: device.id, from: contactFrame)

    let channelInfo = ChannelInfo(index: 0, name: "General", secret: Data(repeating: 0, count: 16))
    _ = try await store.saveChannel(radioID: device.id, from: channelInfo)

    // Simulate ASK removal: delete device record only
    try await store.deleteDevice(id: device.id)

    // Simulate re-pair: saveDevice upserts with same ID
    try await store.saveDevice(device)

    let contacts = try await store.fetchContacts(radioID: device.id)
    #expect(contacts.count == 1)
    #expect(contacts.first?.name == "Survivor")

    let channels = try await store.fetchChannels(radioID: device.id)
    #expect(channels.count == 1)
    #expect(channels.first?.name == "General")
  }

  @Test
  func `Demote device to ghost preserves publicKey and radioID with fresh id`() async throws {
    let store = try await createTestStore()
    let original = createTestDevice().copy {
      $0.isActive = true
    }
    try await store.saveDevice(original)

    try await store.demoteDeviceToGhost(id: original.id)

    let originalLookup = try await store.fetchDevice(id: original.id)
    #expect(originalLookup == nil, "Original BLE id should no longer resolve")

    let ghost = try await store.fetchDevice(publicKey: original.publicKey)
    #expect(ghost != nil)
    #expect(ghost?.id != original.id, "Ghost must have a fresh id")
    #expect(ghost?.publicKey == original.publicKey)
    #expect(ghost?.radioID == original.radioID)
    #expect(ghost?.isActive == false)
  }

  @Test
  func `Demote device strips all connection methods so it stays hidden`() async throws {
    let store = try await createTestStore()
    let wifi = ConnectionMethod.wifi(host: "10.0.0.5", port: 5000, displayName: nil)
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: UUID(), displayName: nil)
    let original = createTestDevice().copy {
      $0.connectionMethods = [wifi, bluetooth]
    }
    try await store.saveDevice(original)

    try await store.demoteDeviceToGhost(id: original.id)

    let ghost = try await store.fetchDevice(publicKey: original.publicKey)
    #expect(ghost?.connectionMethods.isEmpty == true,
            "Demoted ghost must have no connection methods so DeviceSelectionFilter hides it")
  }

  @Test
  func `Demote device with unknown id is a no-op`() async throws {
    let store = try await createTestStore()
    try await store.demoteDeviceToGhost(id: UUID())
    let devices = try await store.fetchDevices()
    #expect(devices.isEmpty)
  }

  @Test
  func `Removing a paired device preserves child contacts via radioID`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let contactFrame = createTestContactFrame(name: "Alice")
    _ = try await store.saveContact(radioID: device.radioID, from: contactFrame)

    try await store.demoteDeviceToGhost(id: device.id)

    let ghost = try await store.fetchDevice(publicKey: device.publicKey)
    #expect(ghost?.radioID == device.radioID)
    let contacts = try await store.fetchContacts(radioID: device.radioID)
    #expect(contacts.count == 1)
    #expect(contacts.first?.name == "Alice")
  }

  // MARK: - Contact Tests

  @Test
  func `Save and fetch contact from frame`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame(name: "Alice")
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    let contact = try await store.fetchContact(id: contactID)
    #expect(contact != nil)
    #expect(contact?.name == "Alice")
    #expect(contact?.type == .chat)
  }

  @Test
  func `Fetch contact by public key`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame(name: "Bob")
    _ = try await store.saveContact(radioID: device.id, from: frame)

    let contact = try await store.fetchContact(radioID: device.id, publicKey: frame.publicKey)
    #expect(contact != nil)
    #expect(contact?.name == "Bob")
  }

  @Test
  func `Update contact last message and unread count`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    let now = Date()
    try await store.updateContactLastMessage(contactID: contactID, date: now)
    try await store.incrementUnreadCount(contactID: contactID)
    try await store.incrementUnreadCount(contactID: contactID)

    var contact = try await store.fetchContact(id: contactID)
    #expect(contact?.unreadCount == 2)
    #expect(contact?.lastMessageDate != nil)

    try await store.clearUnreadCount(contactID: contactID)

    contact = try await store.fetchContact(id: contactID)
    #expect(contact?.unreadCount == 0)
  }

  @Test
  func `deleteMessagesForContact removes all messages for a contact`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create first contact
    let frame1 = createTestContactFrame(name: "Contact1")
    let contact1ID = try await store.saveContact(radioID: device.id, from: frame1).id

    // Create multiple messages for this contact
    for i in 0..<5 {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        contactID: contact1ID,
        text: "Message \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      ))
      try await store.saveMessage(message)
    }

    // Create a second contact with a message (should not be deleted)
    let frame2 = createTestContactFrame(name: "Contact2")
    let contact2ID = try await store.saveContact(radioID: device.id, from: frame2).id
    let otherMessage = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contact2ID,
      text: "Other message",
      timestamp: UInt32(Date().timeIntervalSince1970) + 100
    ))
    try await store.saveMessage(otherMessage)

    // Verify messages exist before deletion
    var contact1Messages = try await store.fetchMessages(contactID: contact1ID)
    #expect(contact1Messages.count == 5)

    var contact2Messages = try await store.fetchMessages(contactID: contact2ID)
    #expect(contact2Messages.count == 1)

    // Delete messages for the first contact
    try await store.deleteMessagesForContact(contactID: contact1ID)

    // Verify messages for deleted contact are gone
    contact1Messages = try await store.fetchMessages(contactID: contact1ID)
    #expect(contact1Messages.isEmpty)

    // Verify messages for other contact still exist
    contact2Messages = try await store.fetchMessages(contactID: contact2ID)
    #expect(contact2Messages.count == 1)
  }

  @Test
  func `recomputeContactLastMessageDate keeps a conversation visible while older messages remain and clears it only when empty`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame(name: "Conversation")
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    let base = Date(timeIntervalSince1970: 1_700_000_000)
    let messages = (0..<3).map { i in
      MessageDTO.testDirectMessage(
        radioID: device.id,
        contactID: contactID,
        text: "Message \(i)",
        timestamp: UInt32(base.timeIntervalSince1970) + UInt32(i),
        createdAt: base.addingTimeInterval(Double(i))
      )
    }
    for message in messages {
      try await store.saveMessage(message)
    }

    // Newest message sets the date; the conversation is visible.
    var newDate = try await store.recomputeContactLastMessageDate(contactID: contactID)
    #expect(newDate == messages[2].date)
    var conversations = try await store.fetchConversations(radioID: device.id)
    #expect(conversations.contains { $0.id == contactID })

    // Deleting the newest message falls back to the next remaining message,
    // not nil: the conversation must stay visible while messages remain.
    try await store.deleteMessage(id: messages[2].id)
    newDate = try await store.recomputeContactLastMessageDate(contactID: contactID)
    #expect(newDate == messages[1].date)
    conversations = try await store.fetchConversations(radioID: device.id)
    #expect(conversations.contains { $0.id == contactID })

    // Deleting the last remaining messages clears the date and removes the conversation.
    try await store.deleteMessage(id: messages[1].id)
    try await store.deleteMessage(id: messages[0].id)
    newDate = try await store.recomputeContactLastMessageDate(contactID: contactID)
    #expect(newDate == nil)
    conversations = try await store.fetchConversations(radioID: device.id)
    #expect(!conversations.contains { $0.id == contactID })
  }

  @Test
  func `deleteMessagesForChannel removes all messages for a channel`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let channelIndex0: UInt8 = 0
    let channelIndex1: UInt8 = 1

    // Create messages for channel 0
    for i in 0..<5 {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        channelIndex: channelIndex0,
        text: "Channel 0 Message \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      ))
      try await store.saveMessage(message)
    }

    // Create messages for channel 1 (should not be deleted)
    for i in 0..<3 {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        channelIndex: channelIndex1,
        text: "Channel 1 Message \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i + 100)
      ))
      try await store.saveMessage(message)
    }

    // Create a contact message (should not be deleted)
    let frame = createTestContactFrame(name: "Contact1")
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id
    let contactMessage = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "Contact message",
      timestamp: UInt32(Date().timeIntervalSince1970) + 200
    ))
    try await store.saveMessage(contactMessage)

    // Verify messages exist before deletion
    var channel0Messages = try await store.fetchMessages(radioID: device.id, channelIndex: channelIndex0)
    #expect(channel0Messages.count == 5)

    var channel1Messages = try await store.fetchMessages(radioID: device.id, channelIndex: channelIndex1)
    #expect(channel1Messages.count == 3)

    var contactMessages = try await store.fetchMessages(contactID: contactID)
    #expect(contactMessages.count == 1)

    // Delete messages for channel 0
    try await store.deleteMessagesForChannel(radioID: device.id, channelIndex: channelIndex0)

    // Verify channel 0 messages are gone
    channel0Messages = try await store.fetchMessages(radioID: device.id, channelIndex: channelIndex0)
    #expect(channel0Messages.isEmpty)

    // Verify channel 1 messages still exist
    channel1Messages = try await store.fetchMessages(radioID: device.id, channelIndex: channelIndex1)
    #expect(channel1Messages.count == 3)

    // Verify contact messages still exist
    contactMessages = try await store.fetchMessages(contactID: contactID)
    #expect(contactMessages.count == 1)
  }

  // MARK: - Message Tests

  @Test
  func `Save and fetch messages for contact`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    // Save multiple messages
    for i in 0..<5 {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        contactID: contactID,
        text: "Message \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      ))
      try await store.saveMessage(message)
    }

    let messages = try await store.fetchMessages(contactID: contactID)
    #expect(messages.count == 5)
    // Messages should be in chronological order (oldest first)
    #expect(messages.first?.text == "Message 0")
    #expect(messages.last?.text == "Message 4")
  }

  @Test
  func `Interleaved backlog from multiple senders reassembles in sortDate (send) order`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    // Backlog drains in a scrambled receive order: rows arrive (createdAt)
    // in the order 2, 0, 3, 1 but their send times (sortDate) are 0..<4.
    // Display must reassemble by send order regardless of receive order.
    let drainBase = Date(timeIntervalSince1970: 2_000_000)
    let sendBase = Date(timeIntervalSince1970: 1_000_000)
    let scrambledSendOrder = [2, 0, 3, 1]
    for (receiveOffset, sendIndex) in scrambledSendOrder.enumerated() {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        contactID: contactID,
        text: "Send \(sendIndex)",
        timestamp: UInt32(sendBase.timeIntervalSince1970) + UInt32(sendIndex),
        createdAt: drainBase.addingTimeInterval(TimeInterval(receiveOffset)),
        sortDate: sendBase.addingTimeInterval(TimeInterval(sendIndex))
      ))
      try await store.saveMessage(message)
    }

    let messages = try await store.fetchMessages(contactID: contactID)
    #expect(messages.map(\.text) == ["Send 0", "Send 1", "Send 2", "Send 3"])
  }

  @Test
  func `Live message stays last even when an earlier-sortDate backlog row is inserted after it`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    // A live row: sortDate == createdAt == now.
    let now = Date(timeIntervalSince1970: 3_000_000)
    let liveMessage = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "Live",
      timestamp: UInt32(now.timeIntervalSince1970),
      createdAt: now,
      sortDate: now
    ))
    try await store.saveMessage(liveMessage)

    // A backlog row inserted afterwards (later createdAt) but with an
    // earlier send time. Its skewed sender clock must not let it sort
    // above the live row.
    let laterCreatedAt = now.addingTimeInterval(60)
    let earlierSendTime = now.addingTimeInterval(-3600)
    let backlogMessage = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "Backlog",
      timestamp: UInt32(earlierSendTime.timeIntervalSince1970),
      createdAt: laterCreatedAt,
      sortDate: earlierSendTime
    ))
    try await store.saveMessage(backlogMessage)

    let messages = try await store.fetchMessages(contactID: contactID)
    #expect(messages.map(\.text) == ["Backlog", "Live"])
    #expect(messages.last?.text == "Live")
  }

  @Test
  func `Equal sortDate and timestamp fall back to createdAt order (tertiary key)`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    // All three rows share an identical sortDate (primary key, e.g. un-backfilled
    // rows that all defaulted to the same value) and an identical timestamp (secondary
    // key), so only createdAt — the tertiary sort key — can break the tie. Varying
    // timestamp too would let the secondary key drive the order and mask whether
    // createdAt is honored.
    let sharedSortDate = Date(timeIntervalSince1970: 4_000_000)
    let sharedTimestamp = UInt32(sharedSortDate.timeIntervalSince1970)
    let createdBase = Date(timeIntervalSince1970: 5_000_000)
    for i in 0..<3 {
      let message = MessageDTO(from: Message(
        radioID: device.id,
        contactID: contactID,
        text: "Tie \(i)",
        timestamp: sharedTimestamp,
        createdAt: createdBase.addingTimeInterval(TimeInterval(i)),
        sortDate: sharedSortDate
      ))
      try await store.saveMessage(message)
    }

    let messages = try await store.fetchMessages(contactID: contactID)
    #expect(messages.map(\.text) == ["Tie 0", "Tie 1", "Tie 2"])
  }

  @Test
  func `Backlog block orders by send time within a shared drain anchor`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let channelIndex: UInt8 = 0
    // One drain anchor shared by the whole block (block-at-reconnect).
    let anchor = Date(timeIntervalSince1970: 1_700_000_000)

    /// Three different senders so reorderSameSenderClusters leaves them alone,
    /// isolating the fetch's sort keys. createdAt (drain order) runs opposite to
    /// send time, proving the secondary sort key is timestamp, not createdAt.
    func backlogMessage(sender: String, sendTime: UInt32, drainOffset: TimeInterval) -> MessageDTO {
      MessageDTO(from: Message(
        radioID: device.id,
        channelIndex: channelIndex,
        text: sender,
        timestamp: sendTime,
        createdAt: anchor.addingTimeInterval(drainOffset),
        sortDate: anchor,
        directionRawValue: MessageDirection.incoming.rawValue,
        senderNodeName: sender
      ))
    }
    // Drained Carol, Bob, Alice (createdAt 0,1,2) but sent Alice < Bob < Carol.
    try await store.saveMessage(backlogMessage(sender: "Carol", sendTime: 300, drainOffset: 0))
    try await store.saveMessage(backlogMessage(sender: "Bob", sendTime: 200, drainOffset: 1))
    try await store.saveMessage(backlogMessage(sender: "Alice", sendTime: 100, drainOffset: 2))

    let messages = try await store.fetchMessages(radioID: device.id, channelIndex: channelIndex)

    #expect(messages.map(\.text) == ["Alice", "Bob", "Carol"])
  }

  @Test
  func `Find channel message for reaction within timestamp window`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let channelIndex: UInt8 = 1
    let baseTimestamp: UInt32 = 1_700_000_000
    var targetMessage: MessageDTO?

    for i in 0..<120 {
      let timestamp = baseTimestamp + UInt32(i)
      let message = MessageDTO(
        id: UUID(),
        radioID: device.id,
        contactID: nil,
        channelIndex: channelIndex,
        text: "Message \(i)",
        timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        senderKeyPrefix: nil,
        senderNodeName: "RemoteNode",
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
      )
      try await store.saveMessage(message)
      if i == 80 {
        targetMessage = message
      }
    }

    let message = try #require(targetMessage)
    let reactionService = ReactionService()
    let reactionText = reactionService.buildReactionText(
      emoji: "👍",
      targetSender: "RemoteNode",
      targetText: message.text,
      targetTimestamp: message.timestamp
    )
    let parsed = try #require(ReactionParser.parse(reactionText))

    let now = message.timestamp
    let windowStart = now > 300 ? now - 300 : 0
    let windowEnd = now + 300

    let found = try await store.findChannelMessageForReaction(
      radioID: device.id,
      channelIndex: channelIndex,
      parsedReaction: parsed,
      localNodeName: "LocalNode",
      timestampWindow: windowStart...windowEnd,
      limit: 200
    )

    #expect(found?.id == message.id)
  }

  @Test
  func `Find outgoing channel message for reaction using local node name`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let channelIndex: UInt8 = 2
    let timestamp: UInt32 = 1_700_000_200

    let outgoingMessage = MessageDTO(
      id: UUID(),
      radioID: device.id,
      contactID: nil,
      channelIndex: channelIndex,
      text: "Local message",
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
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
      maxRetryAttempts: 0
    )
    try await store.saveMessage(outgoingMessage)

    let reactionService = ReactionService()
    let reactionText = reactionService.buildReactionText(
      emoji: "🔥",
      targetSender: "LocalNode",
      targetText: outgoingMessage.text,
      targetTimestamp: outgoingMessage.timestamp
    )
    let parsed = try #require(ReactionParser.parse(reactionText))

    let now = outgoingMessage.timestamp
    let windowStart = now > 300 ? now - 300 : 0
    let windowEnd = now + 300

    let found = try await store.findChannelMessageForReaction(
      radioID: device.id,
      channelIndex: channelIndex,
      parsedReaction: parsed,
      localNodeName: "LocalNode",
      timestampWindow: windowStart...windowEnd,
      limit: 200
    )

    #expect(found?.id == outgoingMessage.id)
  }

  @Test
  func `Update message status`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame()
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    let message = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "Test",
      statusRawValue: MessageStatus.pending.rawValue
    ))
    try await store.saveMessage(message)

    // Update status to sending
    try await store.updateMessageStatus(id: message.id, status: .sending)
    var fetched = try await store.fetchMessage(id: message.id)
    #expect(fetched?.status == .sending)

    // Update status to sent
    try await store.updateMessageStatus(id: message.id, status: .sent)
    fetched = try await store.fetchMessage(id: message.id)
    #expect(fetched?.status == .sent)
  }

  // MARK: - Channel Tests

  @Test
  func `Save and fetch channels`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Add public channel
    let publicChannel = ChannelInfo(index: 0, name: "Public", secret: Data(repeating: 0, count: 16))
    _ = try await store.saveChannel(radioID: device.id, from: publicChannel)

    // Add private channel
    let privateChannel = ChannelInfo(index: 1, name: "Private", secret: Data(repeating: 0x42, count: 16))
    _ = try await store.saveChannel(radioID: device.id, from: privateChannel)

    let channels = try await store.fetchChannels(radioID: device.id)
    #expect(channels.count == 2)
    #expect(channels[0].index == 0)
    #expect(channels[0].name == "Public")
    #expect(channels[1].index == 1)
    #expect(channels[1].name == "Private")
  }

  // MARK: - RemoteNodeSession Tests

  private func createTestRoomSession(radioID: UUID) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "TestRoom",
      role: .roomServer,
      latitude: 37.7749,
      longitude: -122.4194,
      isConnected: false,
      permissionLevel: .guest,
      lastSyncTimestamp: 0
    )
  }

  @Test
  func `Save and fetch remote node session`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched != nil)
    #expect(fetched?.name == "TestRoom")
    #expect(fetched?.role == .roomServer)
  }

  @Test
  func `Update room activity advances sync timestamp and sets lastMessageDate`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Update with sync timestamp
    try await store.updateRoomActivity(session.id, syncTimestamp: 1000)

    var fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.lastSyncTimestamp == 1000)
    #expect(fetched?.lastMessageDate != nil)

    let firstDate = fetched?.lastMessageDate

    // Update to higher sync timestamp
    try await store.updateRoomActivity(session.id, syncTimestamp: 2000)

    fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.lastSyncTimestamp == 2000)
    #expect(fetched?.lastMessageDate != nil)
    #expect(try #require(fetched?.lastMessageDate) >= firstDate!)
  }

  @Test
  func `Update room activity ignores older sync timestamps`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Set initial timestamp
    try await store.updateRoomActivity(session.id, syncTimestamp: 5000)

    var fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.lastSyncTimestamp == 5000)

    // Try to update with older timestamp - sync timestamp should be ignored
    try await store.updateRoomActivity(session.id, syncTimestamp: 3000)

    fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.lastSyncTimestamp == 5000)
    // But lastMessageDate should still be updated
    #expect(fetched?.lastMessageDate != nil)
  }

  @Test
  func `Update room activity without sync timestamp does not change lastSyncTimestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Set initial sync timestamp
    try await store.updateRoomActivity(session.id, syncTimestamp: 5000)

    // Call without sync timestamp (send path)
    try await store.updateRoomActivity(session.id)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.lastSyncTimestamp == 5000)
    #expect(fetched?.lastMessageDate != nil)
  }

  @Test
  func `Mark room session connected changes isConnected and returns true`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create a disconnected session with admin permission
    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: false,
      permissionLevel: .admin,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    let result = try await store.markRoomSessionConnected(session.id)
    #expect(result == true)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.isConnected == true)
    // Permission level must not be changed
    #expect(fetched?.permissionLevel == .admin)
  }

  @Test
  func `Mark room session connected returns false when already connected`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: true,
      permissionLevel: .guest,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    let result = try await store.markRoomSessionConnected(session.id)
    #expect(result == false)
  }

  @Test
  func `Mark session disconnected preserves permission level`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: true,
      permissionLevel: .admin,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    try await store.markSessionDisconnected(session.id)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.isConnected == false)
    #expect(fetched?.permissionLevel == .admin)
  }

  @Test
  func `Mark session disconnected is no-op when already disconnected`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: false,
      permissionLevel: .admin,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    try await store.markSessionDisconnected(session.id)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.isConnected == false)
    #expect(fetched?.permissionLevel == .admin)
  }

  @Test
  func `Disconnect then recover preserves permission level`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: true,
      permissionLevel: .admin,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    try await store.markSessionDisconnected(session.id)
    _ = try await store.markRoomSessionConnected(session.id)

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.isConnected == true)
    #expect(fetched?.permissionLevel == .admin)
  }

  @Test
  func `Update remote node session connection can reset permission to guest`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    var session = createTestRoomSession(radioID: device.id)
    session = RemoteNodeSessionDTO(
      id: session.id,
      radioID: session.radioID,
      publicKey: session.publicKey,
      name: session.name,
      role: session.role,
      isConnected: true,
      permissionLevel: .admin,
      lastSyncTimestamp: session.lastSyncTimestamp
    )
    try await store.saveRemoteNodeSessionDTO(session)

    try await store.updateRemoteNodeSessionConnection(
      id: session.id,
      isConnected: false,
      permissionLevel: .guest
    )

    let fetched = try await store.fetchRemoteNodeSession(id: session.id)
    #expect(fetched?.isConnected == false)
    #expect(fetched?.permissionLevel == .guest)
  }

  // MARK: - RoomMessage Tests

  @Test
  func `Save and fetch room messages`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Save room messages
    for i in 0..<3 {
      let message = RoomMessageDTO(
        sessionID: session.id,
        authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
        authorName: "Author\(i)",
        text: "Room message \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      )
      try await store.saveRoomMessage(message)
    }

    let messages = try await store.fetchRoomMessages(sessionID: session.id)
    #expect(messages.count == 3)
  }

  @Test
  func `Room messages tied on timestamp order deterministically by createdAt`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Same wire timestamp (1-second resolution) but distinct arrival times. Inserted
    // out of arrival order so the fetch must impose the createdAt tie-break itself.
    let sharedTimestamp = UInt32(Date().timeIntervalSince1970)
    let base = Date(timeIntervalSince1970: 1_700_000_000)
    let arrivals: [(text: String, createdAt: Date)] = [
      ("second", base.addingTimeInterval(1)),
      ("third", base.addingTimeInterval(2)),
      ("first", base)
    ]
    for arrival in arrivals {
      let message = RoomMessageDTO(
        sessionID: session.id,
        authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
        text: arrival.text,
        timestamp: sharedTimestamp,
        createdAt: arrival.createdAt
      )
      try await store.saveRoomMessage(message)
    }

    let messages = try await store.fetchRoomMessages(sessionID: session.id)
    #expect(messages.map(\.text) == ["first", "second", "third"])
  }

  @Test
  func `Room messages order primarily by wire timestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    // Distinct timestamps inserted out of order; arrival order is the inverse of
    // send order to prove the timestamp key wins over createdAt.
    let baseTimestamp = UInt32(Date().timeIntervalSince1970)
    let arrival = Date(timeIntervalSince1970: 1_700_000_000)
    let entries: [(text: String, offset: UInt32, arrivalOffset: TimeInterval)] = [
      ("newest", 2, 0),
      ("oldest", 0, 2),
      ("middle", 1, 1)
    ]
    for entry in entries {
      let message = RoomMessageDTO(
        sessionID: session.id,
        authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
        text: entry.text,
        timestamp: baseTimestamp + entry.offset,
        createdAt: arrival.addingTimeInterval(entry.arrivalOffset)
      )
      try await store.saveRoomMessage(message)
    }

    let messages = try await store.fetchRoomMessages(sessionID: session.id)
    #expect(messages.map(\.text) == ["oldest", "middle", "newest"])
  }

  @Test
  func `Room message deduplication`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let session = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(session)

    let timestamp = UInt32(Date().timeIntervalSince1970)
    let authorKeyPrefix = Data([0x01, 0x02, 0x03, 0x04])
    let text = "Duplicate message"

    // Save message
    let message1 = RoomMessageDTO(
      sessionID: session.id,
      authorKeyPrefix: authorKeyPrefix,
      text: text,
      timestamp: timestamp
    )
    try await store.saveRoomMessage(message1)

    // Try to save duplicate (same timestamp, author, and content hash)
    let message2 = RoomMessageDTO(
      sessionID: session.id,
      authorKeyPrefix: authorKeyPrefix,
      text: text,
      timestamp: timestamp
    )
    try await store.saveRoomMessage(message2)

    // Should only have one message
    let messages = try await store.fetchRoomMessages(sessionID: session.id)
    #expect(messages.count == 1)
  }

  // MARK: - Duplicate Session Cleanup Tests

  @Test
  func `Cleanup duplicate remote node sessions keeps target and deletes others`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let sharedKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    // Create two sessions with the same publicKey
    let keepSession = RemoteNodeSessionDTO(
      id: UUID(),
      radioID: device.id,
      publicKey: sharedKey,
      name: "KeepRoom",
      role: .roomServer,
      latitude: 0, longitude: 0,
      isConnected: false,
      permissionLevel: .guest,
      lastSyncTimestamp: 0
    )
    let duplicateSession = RemoteNodeSessionDTO(
      id: UUID(),
      radioID: device.id,
      publicKey: sharedKey,
      name: "DuplicateRoom",
      role: .roomServer,
      latitude: 0, longitude: 0,
      isConnected: false,
      permissionLevel: .guest,
      lastSyncTimestamp: 0
    )

    try await store.saveRemoteNodeSessionDTO(keepSession)
    try await store.saveRemoteNodeSessionDTO(duplicateSession)

    // Add a room message to the duplicate session
    let message = RoomMessageDTO(
      sessionID: duplicateSession.id,
      authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
      authorName: "Author",
      text: "Message on duplicate",
      timestamp: UInt32(Date().timeIntervalSince1970)
    )
    try await store.saveRoomMessage(message)

    // Cleanup: keep one, delete the other
    try await store.cleanupDuplicateRemoteNodeSessions(publicKey: sharedKey, keepID: keepSession.id)

    // Kept session should still exist
    let kept = try await store.fetchRemoteNodeSession(id: keepSession.id)
    #expect(kept != nil)
    #expect(kept?.name == "KeepRoom")

    // Duplicate session should be gone
    let deleted = try await store.fetchRemoteNodeSession(id: duplicateSession.id)
    #expect(deleted == nil)

    // Room messages of the duplicate should be gone
    let messages = try await store.fetchRoomMessages(sessionID: duplicateSession.id)
    #expect(messages.isEmpty)
  }

  // MARK: - Badge Count Tests

  @Test
  func `Get total unread counts`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create contacts with unread messages
    let frame1 = createTestContactFrame(name: "Contact1")
    let contact1ID = try await store.saveContact(radioID: device.id, from: frame1).id
    try await store.incrementUnreadCount(contactID: contact1ID)
    try await store.incrementUnreadCount(contactID: contact1ID)

    let frame2 = createTestContactFrame(name: "Contact2")
    let contact2ID = try await store.saveContact(radioID: device.id, from: frame2).id
    try await store.incrementUnreadCount(contactID: contact2ID)

    // Create channel with unread messages
    let channelInfo = ChannelInfo(index: 0, name: "Public", secret: Data(repeating: 0, count: 16))
    let channelID = try await store.saveChannel(radioID: device.id, from: channelInfo)
    try await store.incrementChannelUnreadCount(channelID: channelID)
    try await store.incrementChannelUnreadCount(channelID: channelID)
    try await store.incrementChannelUnreadCount(channelID: channelID)

    let (contacts, channels, rooms) = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(contacts == 3) // 2 + 1
    #expect(channels == 3)
    #expect(rooms == 0)
  }

  @Test
  func `Get total unread counts excludes blocked contacts`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create a regular contact with unread messages
    let frame1 = createTestContactFrame(name: "RegularContact")
    let regularContactID = try await store.saveContact(radioID: device.id, from: frame1).id
    try await store.incrementUnreadCount(contactID: regularContactID)
    try await store.incrementUnreadCount(contactID: regularContactID)

    // Create a blocked contact with unread messages
    let blockedContact = ContactDTO(
      id: UUID(),
      radioID: device.id,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "BlockedContact",
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: true,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 5
    )
    try await store.saveContact(blockedContact)

    // Get total unread counts - should exclude blocked contact
    let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: device.id)

    // Should only include the 2 unread from the regular contact, not the 5 from blocked
    #expect(contacts == 2, "Blocked contacts should not contribute to unread count total")
  }

  @Test
  func `Get total unread counts excludes repeater contacts`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Regular chat contact: visible in the chats list, contributes to badge
    let chatFrame = createTestContactFrame(name: "ChatContact")
    let chatContactID = try await store.saveContact(radioID: device.id, from: chatFrame).id
    try await store.incrementUnreadCount(contactID: chatContactID)
    try await store.incrementUnreadCount(contactID: chatContactID)

    // Repeater contact: filtered out of chats list, must not contribute to badge
    let repeaterFrame = ContactFrame(
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      type: .repeater,
      flags: 0,
      outPathLength: 2,
      outPath: Data([0x01, 0x02]),
      name: "RepeaterContact",
      lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
      latitude: 0,
      longitude: 0,
      lastModified: UInt32(Date().timeIntervalSince1970)
    )
    let repeaterID = try await store.saveContact(radioID: device.id, from: repeaterFrame).id
    try await store.incrementUnreadCount(contactID: repeaterID)
    try await store.incrementUnreadCount(contactID: repeaterID)
    try await store.incrementUnreadCount(contactID: repeaterID)

    let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(contacts == 2, "Repeater-type contacts should not contribute to badge total")
  }

  @Test
  func `Get total unread counts excludes repeater-role sessions`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Room session: visible in the chats list, contributes to badge
    let roomSession = createTestRoomSession(radioID: device.id)
    try await store.saveRemoteNodeSessionDTO(roomSession)
    try await store.incrementRoomUnreadCount(roomSession.id)
    try await store.incrementRoomUnreadCount(roomSession.id)

    // Repeater-role admin session: filtered out of chats list, must not contribute
    let repeaterSession = RemoteNodeSessionDTO(
      id: UUID(),
      radioID: device.id,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "RepeaterAdmin",
      role: .repeater,
      latitude: 0,
      longitude: 0,
      isConnected: false,
      permissionLevel: .guest,
      lastSyncTimestamp: 0
    )
    try await store.saveRemoteNodeSessionDTO(repeaterSession)
    try await store.incrementRoomUnreadCount(repeaterSession.id)
    try await store.incrementRoomUnreadCount(repeaterSession.id)
    try await store.incrementRoomUnreadCount(repeaterSession.id)

    let (_, _, rooms) = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(rooms == 2, "Repeater-role sessions should not contribute to badge total")
  }

  // MARK: - Warm-up Test

  @Test
  func `Database warm-up`() async throws {
    let store = try await createTestStore()

    // Should not throw
    try await store.warmUp()
  }

  // MARK: - PendingSend attemptCount Tests

  private func makePendingSendDTO(
    messageID: UUID = UUID(),
    radioID: UUID = UUID(),
    attemptCount: Int? = 0,
    sequence: Int = 1
  ) -> PendingSendDTO {
    PendingSendDTO(
      id: UUID(),
      radioID: radioID,
      messageID: messageID,
      kind: .dm,
      contactID: UUID(),
      channelIndex: nil,
      isResend: false,
      messageText: "",
      messageTimestamp: 0,
      localNodeName: nil,
      sequence: sequence,
      enqueuedAt: Date(),
      attemptCount: attemptCount
    )
  }

  @Test
  func `incrementPendingSendAttemptCount from 0 bumps to 1`() async throws {
    let store = try await createTestStore()
    let messageID = UUID()
    let dto = makePendingSendDTO(messageID: messageID, attemptCount: 0)
    try await store.upsertPendingSend(dto)

    let result = try await store.incrementPendingSendAttemptCount(messageID: messageID)
    #expect(result == 1, "first drain attempt should bump 0 → 1")

    let persisted = try await store.fetchPendingSends(radioID: dto.radioID).first
    #expect(persisted?.attemptCount == 1, "persisted attemptCount should match return value")
  }

  @Test
  func `incrementPendingSendAttemptCount returns nil when no row matches`() async throws {
    let store = try await createTestStore()
    let messageID = UUID()

    let result = try await store.incrementPendingSendAttemptCount(messageID: messageID)
    #expect(result == nil, "missing-row case is terminal — return nil instead of creating a new row")
  }

  @Test
  func `purgeLegacyAttemptCountRows deletes only legacy nil rows`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let legacyDTO = makePendingSendDTO(messageID: UUID(), radioID: radioID, attemptCount: nil, sequence: 1)
    let raceDTO = makePendingSendDTO(messageID: UUID(), radioID: radioID, attemptCount: 0, sequence: 2)
    let drainedDTO = makePendingSendDTO(messageID: UUID(), radioID: radioID, attemptCount: 3, sequence: 3)
    try await store.upsertPendingSend(legacyDTO)
    try await store.upsertPendingSend(raceDTO)
    try await store.upsertPendingSend(drainedDTO)

    let deleted = try await store.purgeLegacyAttemptCountRows()
    #expect(deleted == 1, "only the single nil-valued row should be deleted")

    let rows = try await store.fetchPendingSends(radioID: radioID)
    let messageIDs = Set(rows.map(\.messageID))
    #expect(!messageIDs.contains(legacyDTO.messageID), "legacy nil row must be deleted")
    let byMessageID = Dictionary(uniqueKeysWithValues: rows.map { ($0.messageID, $0.attemptCount) })
    #expect(byMessageID[raceDTO.messageID] == 0, "race-window 0 row stays at 0")
    #expect(byMessageID[drainedDTO.messageID] == 3, "already-drained row stays untouched")
  }

  @Test
  func `purgeLegacyAttemptCountRows is idempotent`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let dto = makePendingSendDTO(messageID: UUID(), radioID: radioID, attemptCount: nil)
    try await store.upsertPendingSend(dto)

    let firstDeleted = try await store.purgeLegacyAttemptCountRows()
    let secondDeleted = try await store.purgeLegacyAttemptCountRows()
    #expect(firstDeleted == 1, "first call deletes the legacy nil row")
    #expect(secondDeleted == 0, "second call: predicate matches nothing — idempotent on an empty nil set")
  }

  @Test
  func `warmUp runs both purgeOrphanPendingSends and purgeLegacyAttemptCountRows`() async throws {
    let store = try await createTestStore()
    let radioWithDevice = UUID()
    let radioWithoutDevice = UUID()

    // Device for one of the two radios — the other's PendingSends are orphans.
    let scopedDevice = DeviceDTO.testDevice(id: radioWithDevice, radioID: radioWithDevice)
    try await store.saveDevice(scopedDevice)

    let nilCountOnDeviceRadio = makePendingSendDTO(
      messageID: UUID(), radioID: radioWithDevice, attemptCount: nil, sequence: 1
    )
    let orphanOnUnknownRadio = makePendingSendDTO(
      messageID: UUID(), radioID: radioWithoutDevice, attemptCount: nil, sequence: 1
    )
    try await store.upsertPendingSend(nilCountOnDeviceRadio)
    try await store.upsertPendingSend(orphanOnUnknownRadio)

    try await store.warmUp()

    let survivingForDevice = try await store.fetchPendingSends(radioID: radioWithDevice)
    let survivingForUnknown = try await store.fetchPendingSends(radioID: radioWithoutDevice)
    #expect(survivingForDevice.isEmpty,
            "warmUp must run both purges: nil-attemptCount rows deleted even when radio has a paired device")
    #expect(survivingForUnknown.isEmpty,
            "row attached to no-device radio must be purged by purgeOrphanPendingSends")
  }

  @Test
  func `deletePendingSendsForMessage public API saves on return`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let messageID = UUID()
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: messageID, radioID: radioID, attemptCount: 1, sequence: 1
    ))

    try await store.deletePendingSendsForMessage(messageID: messageID)

    // No explicit save from the test — visibility of the deletion to a
    // subsequent fetch confirms the public method saved on return,
    // matching the contract expected by ChatSendQueueService callers.
    let hasPending = try await store.hasPendingSend(messageID: messageID)
    #expect(hasPending == false,
            "public deletePendingSendsForMessage must save before returning")
  }

  // MARK: - PendingSend Cascade Tests

  @Test
  func `deleteMessage cascades the matching PendingSend in a single transaction`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let messageID = UUID()
    let message = MessageDTO(from: Message(
      id: messageID,
      radioID: device.id,
      contactID: nil,
      channelIndex: 0,
      text: "hello",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(message)

    let pending = makePendingSendDTO(messageID: messageID, radioID: device.id, attemptCount: 0)
    try await store.upsertPendingSend(pending)

    try await store.deleteMessage(id: messageID)

    let remainingPending = try await store.fetchPendingSends(radioID: device.id)
    #expect(remainingPending.isEmpty,
            "deleteMessage must cascade the PendingSend row keyed by the deleted messageID")
    let remainingMessages = try await store.fetchAllMessages(radioID: device.id)
    #expect(remainingMessages.isEmpty,
            "deleteMessage must still remove the Message row")
  }

  @Test
  func `deleteMessage reaps an orphan PendingSend even when no Message row exists`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // An orphan PendingSend with no corresponding Message — can arise from
    // a same-millisecond race between deleteMessage and upsertPendingSend.
    let orphanMessageID = UUID()
    let pending = makePendingSendDTO(
      messageID: orphanMessageID, radioID: device.id, attemptCount: 0
    )
    try await store.upsertPendingSend(pending)

    try await store.deleteMessage(id: orphanMessageID)

    let remainingPending = try await store.fetchPendingSends(radioID: device.id)
    #expect(remainingPending.isEmpty,
            "deleteMessage must reap orphan PendingSends even without a matching Message row")
  }

  @Test
  func `deleteDeviceData reaps radio-scoped orphan PendingSends and preserves the Device row`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Message + matching PendingSend (messageID cascade reaches this row).
    let matchedMessageID = UUID()
    let matchedMessage = MessageDTO(from: Message(
      id: matchedMessageID,
      radioID: device.id,
      contactID: nil,
      channelIndex: 0,
      text: "matched",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(matchedMessage)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: matchedMessageID, radioID: device.id, attemptCount: 0, sequence: 1
    ))

    // PendingSend whose messageID does not correspond to any saved Message.
    // The messageIDs-keyed cascade cannot see it; the radioID-keyed defensive
    // delete must reap it.
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: UUID(), radioID: device.id, attemptCount: 0, sequence: 2
    ))

    try await store.deleteDeviceData(id: device.id)

    let surviving = try await store.fetchPendingSends(radioID: device.id)
    #expect(surviving.isEmpty,
            "deleteDeviceData must reap both Message-matched and orphan PendingSends for the radio")

    let fetchedDevice = try await store.fetchDevice(id: device.id)
    #expect(fetchedDevice != nil,
            "deleteDeviceData must preserve the Device row")
  }

  @Test
  func `deleteMessagesForContact cascades PendingSends and spares unrelated contacts`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame1 = createTestContactFrame(name: "Contact1")
    let contact1ID = try await store.saveContact(radioID: device.id, from: frame1).id
    let frame2 = createTestContactFrame(name: "Contact2")
    let contact2ID = try await store.saveContact(radioID: device.id, from: frame2).id

    var contact1MessageIDs: [UUID] = []
    for i in 0..<3 {
      let messageID = UUID()
      contact1MessageIDs.append(messageID)
      let message = MessageDTO(from: Message(
        id: messageID,
        radioID: device.id,
        contactID: contact1ID,
        text: "C1 \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      ))
      try await store.saveMessage(message)
      try await store.upsertPendingSend(makePendingSendDTO(
        messageID: messageID, radioID: device.id, attemptCount: 0, sequence: i + 1
      ))
    }

    let contact2MessageID = UUID()
    let contact2Message = MessageDTO(from: Message(
      id: contact2MessageID,
      radioID: device.id,
      contactID: contact2ID,
      text: "C2 keep",
      timestamp: UInt32(Date().timeIntervalSince1970) + 100
    ))
    try await store.saveMessage(contact2Message)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: contact2MessageID, radioID: device.id, attemptCount: 0, sequence: 99
    ))

    try await store.deleteMessagesForContact(contactID: contact1ID)

    let remaining = try await store.fetchPendingSends(radioID: device.id)
    #expect(remaining.count == 1,
            "only the unrelated contact's PendingSend should survive")
    #expect(remaining.first?.messageID == contact2MessageID,
            "surviving PendingSend must belong to the untouched contact")

    let contact2Messages = try await store.fetchMessages(contactID: contact2ID)
    #expect(contact2Messages.count == 1,
            "unrelated contact's Message row must be preserved")
  }

  @Test
  func `deleteContact cascades messages, reactions, repeats, and pending sends`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let contactID = try await store.saveContact(radioID: device.id, from: createTestContactFrame(name: "Doomed")).id
    let survivorID = try await store.saveContact(radioID: device.id, from: createTestContactFrame(name: "Survivor")).id

    let message = MessageDTO(from: Message(
      radioID: device.id,
      contactID: contactID,
      text: "cascade me",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(message)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: message.id, radioID: device.id, attemptCount: 0, sequence: 1
    ))
    try await store.saveReaction(ReactionDTO(
      messageID: message.id,
      emoji: "👍",
      senderName: "Doomed",
      messageHash: "hash",
      rawText: "👍",
      contactID: contactID,
      radioID: device.id
    ))
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id))

    let survivorMessage = MessageDTO(from: Message(
      radioID: device.id,
      contactID: survivorID,
      text: "keep me",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(survivorMessage)

    try await store.deleteContact(id: contactID)

    #expect(try await store.fetchContact(id: contactID) == nil,
            "Contact row must be deleted")
    #expect(try await store.fetchMessages(contactID: contactID).isEmpty,
            "messages must die with the contact")
    #expect(try await store.fetchReactions(for: message.id).isEmpty,
            "reactions must die with the contact")
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty,
            "message repeats must die with the contact")
    #expect(try await store.fetchPendingSends(radioID: device.id).isEmpty,
            "pending sends must die with the contact")
    #expect(try await store.fetchContact(id: survivorID) != nil,
            "unrelated contact must be preserved")
    #expect(try await store.fetchMessages(contactID: survivorID).count == 1,
            "unrelated contact's messages must be preserved")
  }

  @Test
  func `deleteContact removes local data when the contact row is already gone`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // No Contact row exists for this ID — the removeLocalContact scenario,
    // where the radio no longer knows the contact but local data remains.
    let ghostContactID = UUID()
    let message = MessageDTO(from: Message(
      radioID: device.id,
      contactID: ghostContactID,
      text: "orphaned",
      timestamp: UInt32(Date().timeIntervalSince1970)
    ))
    try await store.saveMessage(message)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: message.id, radioID: device.id, attemptCount: 0, sequence: 1
    ))

    try await store.deleteContact(id: ghostContactID)

    #expect(try await store.fetchMessages(contactID: ghostContactID).isEmpty,
            "local messages must be removed even without a Contact row")
    #expect(try await store.fetchPendingSends(radioID: device.id).isEmpty,
            "pending sends must be removed even without a Contact row")
  }

  @Test
  func `deleteMessagesForChannel cascades PendingSends and spares other channels`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let targetChannel: UInt8 = 0
    let untouchedChannel: UInt8 = 1

    for i in 0..<3 {
      let messageID = UUID()
      let message = MessageDTO(from: Message(
        id: messageID,
        radioID: device.id,
        contactID: nil,
        channelIndex: targetChannel,
        text: "Ch0 \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i)
      ))
      try await store.saveMessage(message)
      try await store.upsertPendingSend(makePendingSendDTO(
        messageID: messageID, radioID: device.id, attemptCount: 0, sequence: i + 1
      ))
    }

    let untouchedMessageID = UUID()
    let untouchedMessage = MessageDTO(from: Message(
      id: untouchedMessageID,
      radioID: device.id,
      contactID: nil,
      channelIndex: untouchedChannel,
      text: "Ch1 keep",
      timestamp: UInt32(Date().timeIntervalSince1970) + 100
    ))
    try await store.saveMessage(untouchedMessage)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: untouchedMessageID, radioID: device.id, attemptCount: 0, sequence: 99
    ))

    try await store.deleteMessagesForChannel(radioID: device.id, channelIndex: targetChannel)

    let remaining = try await store.fetchPendingSends(radioID: device.id)
    #expect(remaining.count == 1,
            "only the untouched channel's PendingSend should survive")
    #expect(remaining.first?.messageID == untouchedMessageID,
            "surviving PendingSend must belong to the untouched channel")

    let untouchedChannelMessages = try await store.fetchMessages(
      radioID: device.id, channelIndex: untouchedChannel
    )
    #expect(untouchedChannelMessages.count == 1,
            "unrelated channel's Message row must be preserved")
  }

  @Test
  func `deleteChannelMessages(fromSender:) cascades PendingSends and spares other senders`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let channelIndex: UInt8 = 0
    let targetSender = "Spammer"
    let untouchedSender = "Friend"

    for i in 0..<3 {
      let messageID = UUID()
      let message = MessageDTO(from: Message(
        id: messageID,
        radioID: device.id,
        contactID: nil,
        channelIndex: channelIndex,
        text: "spam \(i)",
        timestamp: UInt32(Date().timeIntervalSince1970) + UInt32(i),
        senderNodeName: targetSender
      ))
      try await store.saveMessage(message)
      try await store.upsertPendingSend(makePendingSendDTO(
        messageID: messageID, radioID: device.id, attemptCount: 0, sequence: i + 1
      ))
    }

    let untouchedMessageID = UUID()
    let untouchedMessage = MessageDTO(from: Message(
      id: untouchedMessageID,
      radioID: device.id,
      contactID: nil,
      channelIndex: channelIndex,
      text: "friend",
      timestamp: UInt32(Date().timeIntervalSince1970) + 100,
      senderNodeName: untouchedSender
    ))
    try await store.saveMessage(untouchedMessage)
    try await store.upsertPendingSend(makePendingSendDTO(
      messageID: untouchedMessageID, radioID: device.id, attemptCount: 0, sequence: 99
    ))

    try await store.deleteChannelMessages(fromSender: targetSender, radioID: device.id)

    let remaining = try await store.fetchPendingSends(radioID: device.id)
    #expect(remaining.count == 1,
            "only the other sender's PendingSend should survive")
    #expect(remaining.first?.messageID == untouchedMessageID,
            "surviving PendingSend must belong to the untouched sender")
  }

  // MARK: - RxLogEntry Tests

  private func createTestRxLogEntryDTO(
    radioID: UUID,
    senderTimestamp: UInt32? = nil,
    regionScope: String? = nil,
    payloadTypeBits: UInt8 = 5,
    transportCode: Data? = nil,
    channelIndex: UInt8? = 1,
    packetPayload: Data = Data([0xAB, 0xCD, 0xEF])
  ) -> RxLogEntryDTO {
    // Create minimal ParsedRxLogData for the DTO
    let parsed = ParsedRxLogData(
      snr: 10.5,
      rssi: -65,
      rawPayload: Data([0x15, 0x01, 0x02, 0x03]),
      routeType: .flood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: payloadTypeBits,
      transportCode: transportCode,
      pathLength: 1,
      pathNodes: [0x42],
      packetPayload: packetPayload
    )

    return RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      channelIndex: channelIndex,
      channelName: "TestChannel",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      regionScope: regionScope,
      decodedText: "Hello mesh!"
    )
  }

  @Test
  func `RxLogEntryDTO(from:) falls back on out-of-range stored values instead of trapping`() {
    let model = RxLogEntry(
      radioID: UUID(),
      routeType: 999,
      payloadType: -1,
      payloadVersion: 5000,
      pathLength: 400,
      pathNodes: Data(),
      packetPayload: Data(),
      rawPayload: Data(),
      packetHash: "deadbeef",
      channelIndex: 9999,
      senderTimestamp: -10
    )

    let dto = RxLogEntryDTO(from: model)

    #expect(dto.routeType == .flood)
    #expect(dto.payloadType == .unknown)
    #expect(dto.payloadVersion == 0)
    #expect(dto.pathLength == 0)
    #expect(dto.channelIndex == nil)
    #expect(dto.senderTimestamp == nil)
  }

  @Test
  func `Save and fetch RxLogEntry preserves senderTimestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let expectedTimestamp: UInt32 = 1_703_123_456
    let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: expectedTimestamp)

    try await store.saveRxLogEntry(dto)

    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.count == 1)
    #expect(entries.first?.senderTimestamp == expectedTimestamp)
  }

  @Test
  func `Save and fetch RxLogEntry with nil senderTimestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: nil)

    try await store.saveRxLogEntry(dto)

    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.count == 1)
    #expect(entries.first?.senderTimestamp == nil)
  }

  @Test
  func `RxLogEntryDTO init from model preserves senderTimestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Save with timestamp
    let expectedTimestamp: UInt32 = 1_703_123_456
    let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: expectedTimestamp)
    try await store.saveRxLogEntry(dto)

    // Fetch back (this uses RxLogEntryDTO.init(from: RxLogEntry))
    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.first?.senderTimestamp == expectedTimestamp)

    // Verify the conversion handles the Int -> UInt32 correctly
    // The model stores Int, DTO uses UInt32
    #expect(entries.first?.senderTimestamp == 1_703_123_456)
  }

  @Test
  func `RX log prune is deferred until threshold is exceeded`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    for index in 0..<1100 {
      let dto = createTestRxLogEntryDTO(
        radioID: device.id,
        senderTimestamp: UInt32(index)
      )
      try await store.saveRxLogEntry(dto)
      try await store.pruneRxLogEntries(radioID: device.id)
    }

    let entriesBeforeThreshold = try await store.fetchRxLogEntries(radioID: device.id, limit: 1200)
    #expect(entriesBeforeThreshold.count == 1100)

    let thresholdEntry = createTestRxLogEntryDTO(
      radioID: device.id,
      senderTimestamp: UInt32(1100)
    )
    try await store.saveRxLogEntry(thresholdEntry)
    try await store.pruneRxLogEntries(radioID: device.id)

    let entriesAfterThreshold = try await store.fetchRxLogEntries(radioID: device.id, limit: 1200)
    #expect(entriesAfterThreshold.count == 1000)
    #expect(entriesAfterThreshold.first?.senderTimestamp == 1100)
    #expect(entriesAfterThreshold.last?.senderTimestamp == 101)
  }

  @Test
  func `Clearing RX log resets cached count for future pruning`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    for index in 0..<1101 {
      let dto = createTestRxLogEntryDTO(
        radioID: device.id,
        senderTimestamp: UInt32(index)
      )
      try await store.saveRxLogEntry(dto)
    }
    try await store.pruneRxLogEntries(radioID: device.id)
    try await store.clearRxLogEntries(radioID: device.id)

    let replacement = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: 42)
    try await store.saveRxLogEntry(replacement)
    try await store.pruneRxLogEntries(radioID: device.id)

    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.count == 1)
    #expect(entries.first?.senderTimestamp == 42)
  }

  @Test
  func `A store rebuilt over a populated container seeds its prune cache from disk`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let radioID = UUID()

    let storeA = PersistenceStore(modelContainer: container)
    try await storeA.saveDevice(createTestDevice().copy { $0.id = radioID; $0.radioID = radioID })

    // Fill to the retention cap (keepCount + pruneThreshold) without exceeding it.
    for index in 0..<1100 {
      try await storeA.saveRxLogEntry(
        createTestRxLogEntryDTO(radioID: radioID, senderTimestamp: UInt32(index))
      )
      try await storeA.pruneRxLogEntries(radioID: radioID)
    }
    let beforeReconnect = try await storeA.fetchRxLogEntries(radioID: radioID, limit: 1200)
    #expect(beforeReconnect.count == 1100)

    // Reconnect: a new store over the same container starts with a cold cache.
    // Writing one full prune cycle past the cap drives the count back to keepCount
    // only if storeB seeded from disk; a cold-from-zero cache never trips the gate.
    let storeB = PersistenceStore(modelContainer: container)
    for index in 1100..<1202 {
      try await storeB.saveRxLogEntry(
        createTestRxLogEntryDTO(radioID: radioID, senderTimestamp: UInt32(index))
      )
      try await storeB.pruneRxLogEntries(radioID: radioID)
    }

    // Pruning only fires if storeB seeded its count from disk rather than from zero.
    let afterReconnect = try await storeB.fetchRxLogEntries(radioID: radioID, limit: 1300)
    #expect(afterReconnect.count == 1000)
  }

  // MARK: - Region Scope Tests

  @Test
  func `saveRxLogEntry forwards regionScope to the persisted model`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let dto = createTestRxLogEntryDTO(
      radioID: device.id,
      senderTimestamp: 1_703_000_000,
      regionScope: "Germany"
    )
    try await store.saveRxLogEntry(dto)

    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.first?.regionScope == "Germany")
  }

  @Test
  func `saveRxLogEntry preserves payloadTypeBits including unknown nibbles`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let dto = createTestRxLogEntryDTO(
      radioID: device.id,
      senderTimestamp: 1_703_000_001,
      payloadTypeBits: 0x0C
    )
    try await store.saveRxLogEntry(dto)

    let entries = try await store.fetchRxLogEntries(radioID: device.id)
    #expect(entries.first?.payloadTypeBits == 0x0C)
  }

  @Test
  func `batchUpdateChannelMessageRegion back-fills normal-case message via timestamp fallback`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let wireTimestamp: UInt32 = 1_703_111_111
    // Normal case: senderTimestamp stays nil, wire timestamp lives on `timestamp`.
    let dto = MessageDTO.testChannelMessage(
      radioID: device.id,
      channelIndex: 0,
      timestamp: wireTimestamp,
      direction: .incoming,
      status: .delivered
    )
    try await store.saveMessage(dto)

    try await store.batchUpdateChannelMessageRegion(
      radioID: device.id,
      updates: [(channelIndex: 0, senderTimestamp: wireTimestamp, regionScope: "Germany")]
    )

    let saved = try await store.fetchMessages(radioID: device.id, channelIndex: 0)
    #expect(saved.first?.regionScope == "Germany")
  }

  @Test
  func `batchUpdateChannelMessageRegion back-fills timestamp-corrected message`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let originalWire: UInt32 = 1_703_222_222
    var dto = MessageDTO.testChannelMessage(
      radioID: device.id,
      channelIndex: 1,
      timestamp: UInt32(Date().timeIntervalSince1970),
      direction: .incoming,
      status: .delivered
    )
    dto.senderTimestamp = originalWire
    try await store.saveMessage(dto)

    try await store.batchUpdateChannelMessageRegion(
      radioID: device.id,
      updates: [(channelIndex: 1, senderTimestamp: originalWire, regionScope: "USA")]
    )

    let saved = try await store.fetchMessages(radioID: device.id, channelIndex: 1)
    #expect(saved.first?.regionScope == "USA")
  }

  @Test
  func `batchUpdateChannelMessageRegion skips outgoing messages`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let wireTimestamp: UInt32 = 1_703_333_333
    let dto = MessageDTO.testChannelMessage(
      radioID: device.id,
      channelIndex: 2,
      timestamp: wireTimestamp,
      direction: .outgoing,
      status: .sent
    )
    try await store.saveMessage(dto)

    try await store.batchUpdateChannelMessageRegion(
      radioID: device.id,
      updates: [(channelIndex: 2, senderTimestamp: wireTimestamp, regionScope: "France")]
    )

    let saved = try await store.fetchMessages(radioID: device.id, channelIndex: 2)
    #expect(saved.first?.regionScope == nil)
  }

  @Test
  func `batchUpdateDMMessageRegion back-fills DM by sender prefix byte`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let wireTimestamp: UInt32 = 1_703_444_444
    let senderKey = Data([0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x03])
    let contactID = UUID()
    let dto = MessageDTO.testDirectMessage(
      radioID: device.id,
      contactID: contactID,
      timestamp: wireTimestamp,
      direction: .incoming,
      status: .delivered,
      senderKeyPrefix: senderKey
    )
    try await store.saveMessage(dto)

    try await store.batchUpdateDMMessageRegion(
      radioID: device.id,
      updates: [(senderPrefixByte: 0xAB, senderTimestamp: wireTimestamp, regionScope: "Germany")]
    )

    let saved = try await store.fetchMessages(contactID: contactID)
    #expect(saved.first?.regionScope == "Germany")
  }

  @Test
  func `batchUpdateDMMessageRegion ignores DMs from other senders at same timestamp`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let wireTimestamp: UInt32 = 1_703_555_555
    let aliceKey = Data([0xAA, 0x11, 0x22, 0x33, 0x44, 0x55])
    let bobKey = Data([0xBB, 0x66, 0x77, 0x88, 0x99, 0x00])
    let aliceContact = UUID()
    let bobContact = UUID()

    let alice = MessageDTO.testDirectMessage(
      radioID: device.id,
      contactID: aliceContact,
      text: "From Alice",
      timestamp: wireTimestamp,
      direction: .incoming,
      status: .delivered,
      senderKeyPrefix: aliceKey
    )
    let bob = MessageDTO.testDirectMessage(
      radioID: device.id,
      contactID: bobContact,
      text: "From Bob",
      timestamp: wireTimestamp,
      direction: .incoming,
      status: .delivered,
      senderKeyPrefix: bobKey
    )
    try await store.saveMessage(alice)
    try await store.saveMessage(bob)

    try await store.batchUpdateDMMessageRegion(
      radioID: device.id,
      updates: [(senderPrefixByte: 0xAA, senderTimestamp: wireTimestamp, regionScope: "Germany")]
    )

    let aliceSaved = try await store.fetchMessages(contactID: aliceContact)
    let bobSaved = try await store.fetchMessages(contactID: bobContact)
    #expect(aliceSaved.first?.regionScope == "Germany")
    #expect(bobSaved.first?.regionScope == nil)
  }

  // MARK: - saveContact isNew / deleteContactIfUnreferenced

  @Test
  func `saveContact from frame returns isNew true then false with stable id`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame(name: "Stable")
    let first = try await store.saveContact(radioID: device.id, from: frame)
    #expect(first.isNew == true)

    let updated = ContactFrame(
      publicKey: frame.publicKey,
      type: frame.type,
      flags: frame.flags,
      outPathLength: frame.outPathLength,
      outPath: frame.outPath,
      name: "Renamed",
      lastAdvertTimestamp: frame.lastAdvertTimestamp &+ 1,
      latitude: frame.latitude,
      longitude: frame.longitude,
      lastModified: frame.lastModified &+ 1
    )
    let second = try await store.saveContact(radioID: device.id, from: updated)
    #expect(second.isNew == false)
    #expect(second.id == first.id)

    let fetched = try await store.fetchContact(id: first.id)
    #expect(fetched?.name == "Renamed")
  }

  @Test
  func `deleteContactIfUnreferenced skips when messages exist and deletes when none`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let withMessagesID = try await store.saveContact(
      radioID: device.id, from: createTestContactFrame(name: "WithMsgs")
    ).id
    try await store.saveMessage(
      MessageDTO.testDirectMessage(radioID: device.id, contactID: withMessagesID, text: "keep")
    )
    try await store.deleteContactIfUnreferenced(id: withMessagesID)
    #expect(try await store.fetchContact(id: withMessagesID) != nil)
    #expect(try await store.fetchMessages(contactID: withMessagesID, limit: 10, offset: 0).count == 1)

    let bareID = try await store.saveContact(
      radioID: device.id, from: createTestContactFrame(name: "Bare")
    ).id
    try await store.deleteContactIfUnreferenced(id: bareID)
    #expect(try await store.fetchContact(id: bareID) == nil)
  }

  // MARK: - Mute Tests

  @Test
  func `Set contact muted`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    let frame = createTestContactFrame(name: "Alice")
    let contactID = try await store.saveContact(radioID: device.id, from: frame).id

    // Initially not muted
    var contact = try await store.fetchContact(id: contactID)
    #expect(contact?.isMuted == false)

    // Mute
    try await store.setContactMuted(contactID, isMuted: true)
    contact = try await store.fetchContact(id: contactID)
    #expect(contact?.isMuted == true)

    // Unmute
    try await store.setContactMuted(contactID, isMuted: false)
    contact = try await store.fetchContact(id: contactID)
    #expect(contact?.isMuted == false)
  }

  @Test
  func `Muted contacts excluded from badge count`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create contact with unreads
    let frame1 = createTestContactFrame(name: "Alice")
    let contact1ID = try await store.saveContact(radioID: device.id, from: frame1).id
    try await store.incrementUnreadCount(contactID: contact1ID)
    try await store.incrementUnreadCount(contactID: contact1ID)

    // Create muted contact with unreads
    let frame2 = createTestContactFrame(name: "Bob")
    let contact2ID = try await store.saveContact(radioID: device.id, from: frame2).id
    try await store.incrementUnreadCount(contactID: contact2ID)
    try await store.setContactMuted(contact2ID, isMuted: true)

    let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: device.id)

    // Only Alice's 2 unreads should count, Bob is muted
    #expect(contacts == 2)
  }

  @Test
  func `Notification levels affect badge count correctly`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)

    // Create channel with unreads
    let channelInfo = ChannelInfo(index: 1, name: "Test", secret: Data(repeating: 0x42, count: 16))
    let channelID = try await store.saveChannel(radioID: device.id, from: channelInfo)
    try await store.incrementChannelUnreadCount(channelID: channelID)
    try await store.incrementChannelUnreadCount(channelID: channelID)

    // Default (all) - should count all unreads
    var counts = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(counts.channels == 2)

    // Muted - should exclude from badge
    try await store.setChannelNotificationLevel(channelID, level: .muted)
    counts = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(counts.channels == 0)

    // Mentions only with no mentions - should show 0
    try await store.setChannelNotificationLevel(channelID, level: .mentionsOnly)
    counts = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(counts.channels == 0)

    // Mentions only with mentions - should show mention count
    try await store.incrementChannelUnreadMentionCount(channelID: channelID)
    counts = try await store.getTotalUnreadCounts(radioID: device.id)
    #expect(counts.channels == 1)
  }

  // MARK: - Ghost Identity Reconciliation Tests

  @Test
  func `reconcileGhostIdentity rewrites current device when ghost matches publicKey`() async throws {
    let store = try await createTestStore()

    let oldPublicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
    let newPublicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    let ghostRadioID = UUID()
    let originalDeviceID = UUID()
    let ghost = createTestDevice(id: originalDeviceID).copy {
      $0.publicKey = oldPublicKey
      $0.radioID = ghostRadioID
      $0.isActive = false
      $0.connectionMethods = []
    }
    try await store.saveDevice(ghost)

    let currentRadioID = UUID()
    let currentDeviceID = UUID()
    let current = createTestDevice(id: currentDeviceID).copy {
      $0.publicKey = newPublicKey
      $0.radioID = currentRadioID
      $0.isActive = true
    }
    try await store.saveDevice(current)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: currentDeviceID,
      newPublicKey: oldPublicKey
    )

    #expect(result == ghostRadioID, "Expected the ghost's radioID to be returned")

    let updated = try await store.fetchDevice(id: currentDeviceID)
    #expect(updated?.radioID == ghostRadioID)
    #expect(updated?.publicKey == oldPublicKey)

    let ghostLookup = try await store.fetchDevice(id: originalDeviceID)
    #expect(ghostLookup == nil, "Ghost row should be deleted after reconciliation")
  }

  @Test
  func `reconcileGhostIdentity is a no-op when current device already owns the publicKey`() async throws {
    let store = try await createTestStore()
    let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
    let device = createTestDevice().copy { $0.publicKey = publicKey }
    try await store.saveDevice(device)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: device.id,
      newPublicKey: publicKey
    )

    #expect(result == nil)
    let unchanged = try await store.fetchDevice(id: device.id)
    #expect(unchanged?.radioID == device.radioID)
  }

  @Test
  func `reconcileGhostIdentity returns nil when no ghost matches`() async throws {
    let store = try await createTestStore()
    let device = createTestDevice()
    try await store.saveDevice(device)
    let unrelatedKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: device.id,
      newPublicKey: unrelatedKey
    )
    #expect(result == nil)

    let unchanged = try await store.fetchDevice(id: device.id)
    #expect(unchanged?.publicKey == device.publicKey)
    #expect(unchanged?.radioID == device.radioID)
  }

  @Test
  func `reconcileGhostIdentity refuses to delete a saved-but-inactive device with BLE methods`() async throws {
    let store = try await createTestStore()

    let sharedPublicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    let inactiveBLE = createTestDevice().copy {
      $0.publicKey = sharedPublicKey
      $0.isActive = false
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: nil)
      ]
    }
    try await store.saveDevice(inactiveBLE)

    let current = createTestDevice().copy {
      $0.publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
      $0.isActive = true
    }
    try await store.saveDevice(current)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: current.id,
      newPublicKey: sharedPublicKey
    )
    #expect(result == nil, "Must not match a non-ghost inactive device")

    let stillThere = try await store.fetchDevice(id: inactiveBLE.id)
    #expect(stillThere != nil, "Saved-but-inactive device must not be deleted")
    #expect(stillThere?.connectionMethods.contains(where: \.isBluetooth) == true)
  }

  @Test
  func `reconcileGhostIdentity finds ghost even when current device's publicKey already matches`() async throws {
    let store = try await createTestStore()

    let restoredPublicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    let ghostRadioID = UUID()
    let ghost = createTestDevice().copy {
      $0.publicKey = restoredPublicKey
      $0.radioID = ghostRadioID
      $0.isActive = false
      $0.connectionMethods = []
    }
    try await store.saveDevice(ghost)

    let staleRadioID = UUID()
    let current = createTestDevice().copy {
      $0.publicKey = restoredPublicKey
      $0.radioID = staleRadioID
      $0.isActive = true
    }
    try await store.saveDevice(current)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: current.id,
      newPublicKey: restoredPublicKey
    )
    #expect(result == ghostRadioID, "Reconciliation must find the ghost on retry")

    let updated = try await store.fetchDevice(id: current.id)
    #expect(updated?.radioID == ghostRadioID)

    let ghostLookup = try await store.fetchDevice(id: ghost.id)
    #expect(ghostLookup == nil)
  }

  @Test
  func `reconcileGhostIdentity preserves non-BLE methods from backup ghost`() async throws {
    let store = try await createTestStore()

    let restoredPublicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
    let backupWiFi = ConnectionMethod.wifi(host: "10.0.0.7", port: 5000, displayName: "Backup WiFi")

    let ghostRadioID = UUID()
    let ghost = createTestDevice().copy {
      $0.publicKey = restoredPublicKey
      $0.radioID = ghostRadioID
      $0.isActive = false
      $0.connectionMethods = [backupWiFi]
    }
    try await store.saveDevice(ghost)

    let currentBLE = ConnectionMethod.bluetooth(peripheralUUID: UUID(), displayName: "Current BLE")
    let current = createTestDevice().copy {
      $0.publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
      $0.radioID = UUID()
      $0.isActive = true
      $0.connectionMethods = [currentBLE]
    }
    try await store.saveDevice(current)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: current.id,
      newPublicKey: restoredPublicKey
    )

    #expect(result == ghostRadioID)

    let updated = try #require(await store.fetchDevice(id: current.id))
    #expect(updated.connectionMethods.contains(currentBLE))
    #expect(updated.connectionMethods.contains(backupWiFi))
    #expect(updated.connectionMethods.filter(\.isBluetooth).count == 1)

    let ghostLookup = try await store.fetchDevice(id: ghost.id)
    #expect(ghostLookup == nil)
  }

  // MARK: - Terminal-State Guards (DM ACK machine)

  @Test
  func `updateMessageAck refuses to write .delivered onto a .failed row`() async throws {
    let store = try await createTestStore()
    let messageID = UUID()
    try await store.saveMessage(
      MessageDTO.testDirectMessage(id: messageID, status: .failed)
    )

    try await store.updateMessageAck(id: messageID, ackCode: 0xDEAD_BEEF, status: .delivered)

    let stored = try await store.fetchMessage(id: messageID)
    #expect(stored?.status == .failed,
            ".failed is terminal for the ACK-delivery write; a late ACK must not flip it to .delivered")
  }

  @Test
  func `clearRetryingToSent no-ops on .failed but promotes .retrying and .pending to .sent`() async throws {
    let store = try await createTestStore()

    let failedID = UUID()
    try await store.saveMessage(MessageDTO.testDirectMessage(id: failedID, status: .failed))
    let failedMoved = try await store.clearRetryingToSent(id: failedID)
    #expect(failedMoved == false, "must not resurrect a checker-failed row")
    #expect(try await store.fetchMessage(id: failedID)?.status == .failed)

    let retryingID = UUID()
    try await store.saveMessage(MessageDTO.testDirectMessage(id: retryingID, status: .retrying))
    let retryingMoved = try await store.clearRetryingToSent(id: retryingID)
    #expect(retryingMoved == true)
    #expect(try await store.fetchMessage(id: retryingID)?.status == .sent)

    let pendingID = UUID()
    try await store.saveMessage(MessageDTO.testDirectMessage(id: pendingID, status: .pending))
    let pendingMoved = try await store.clearRetryingToSent(id: pendingID)
    #expect(pendingMoved == true,
            "a single-attempt row still .pending at give-up must also promote to .sent")
    #expect(try await store.fetchMessage(id: pendingID)?.status == .sent)
  }

  @Test
  func `clearRetryingToSent no-ops on a .delivered row`() async throws {
    let store = try await createTestStore()
    let messageID = UUID()
    try await store.saveMessage(MessageDTO.testDirectMessage(id: messageID, status: .delivered))

    let moved = try await store.clearRetryingToSent(id: messageID)
    #expect(moved == false)
    #expect(try await store.fetchMessage(id: messageID)?.status == .delivered)
  }

  @Test
  func `shared updateMessageStatusUnlessDelivered still remaps .failed to .pending for the offline queue`() async throws {
    let store = try await createTestStore()
    let messageID = UUID()
    try await store.saveMessage(MessageDTO.testDirectMessage(id: messageID, status: .failed))

    // The offline send queue's transient-recovery remap must keep working;
    // .failed -> .pending is not a constraint violation because the row can
    // only ever reach .delivered by going forward through .sent again.
    let remapped = try await store.updateMessageStatusUnlessDelivered(id: messageID, status: .pending)
    #expect(remapped == true)
    #expect(try await store.fetchMessage(id: messageID)?.status == .pending)

    // A subsequent successful redelivery still reaches .delivered.
    try await store.updateMessageAck(id: messageID, ackCode: 0x1234_5678, status: .delivered)
    #expect(try await store.fetchMessage(id: messageID)?.status == .delivered)
  }

  @Test
  func `hasOutgoingSentDM flags a stuck .sent DM by ackCode and ignores other rows`() async throws {
    let store = try await createTestStore()
    let ackCode: UInt32 = 0xCAFE_F00D

    let sentID = UUID()
    try await store.saveMessage(
      MessageDTO.testDirectMessage(id: sentID, status: .sent, ackCode: ackCode)
    )
    #expect(try await store.hasOutgoingSentDM(ackCode: ackCode) == true)

    // A different ackCode must not match.
    #expect(try await store.hasOutgoingSentDM(ackCode: 0x0000_0001) == false)

    // A delivered row with the same ackCode is not an orphan.
    let deliveredID = UUID()
    try await store.saveMessage(
      MessageDTO.testDirectMessage(id: deliveredID, status: .delivered, ackCode: 0xBADC_0DE5)
    )
    #expect(try await store.hasOutgoingSentDM(ackCode: 0xBADC_0DE5) == false)
  }

  // MARK: - Inbound Advert Hop Count

  @Test
  func `setInboundHopCount round-trips onto an existing discovered node`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)

    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 4, advertTimestamp: 100)

    let fetched = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(fetched.count == 1)
    #expect(fetched.first?.inboundHopCount == 4)
    #expect(fetched.first?.inboundHopAdvertTimestamp == 100)
  }

  @Test
  func `setInboundHopCount is a no-op when no matching row exists`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let unknownKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    try await store.setInboundHopCount(radioID: radioID, publicKey: unknownKey, hopCount: 2, advertTimestamp: nil)

    let fetched = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(fetched.isEmpty, "No row should be created for an unknown pubkey")
  }

  @Test
  func `setInboundHopCount keeps the closest copy within the same broadcast`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    let ts: UInt32 = 200

    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 3, advertTimestamp: ts)
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 0, advertTimestamp: ts)
    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).first?.inboundHopCount == 0)

    // A farther copy of the same broadcast must not raise the stored count.
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 5, advertTimestamp: ts)
    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).first?.inboundHopCount == 0)
  }

  @Test
  func `a newer advert timestamp raises the inbound hop count`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)

    // First advert: heard directly (0 hops).
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 0, advertTimestamp: 100)
    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).first?.inboundHopCount == 0)

    // Second advert with a newer timestamp: the node is now farther away. Must update.
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 3, advertTimestamp: 200)
    let fetched = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(fetched.first?.inboundHopCount == 3)
    #expect(fetched.first?.inboundHopAdvertTimestamp == 200)
  }

  @Test
  func `an older advert timestamp is a no-op even when it has a closer hop count`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)

    // Current state: 3 hops, timestamp 200.
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 3, advertTimestamp: 200)

    // Stale copy with an older timestamp but closer hops must not replace the current state.
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 1, advertTimestamp: 100)
    let fetched = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(fetched.first?.inboundHopCount == 3)
    #expect(fetched.first?.inboundHopAdvertTimestamp == 200)
  }

  @Test
  func `upsertDiscoveredNode does not reset a stored inbound hop count`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 2, advertTimestamp: 50)

    // Re-upsert the same node (a fresh out-path advert) keyed by the same pubkey.
    let updatedFrame = ContactFrame(
      publicKey: node.publicKey,
      type: .chat,
      flags: 0,
      outPathLength: PacketBuilder.floodPathSentinel,
      outPath: Data(),
      name: "Advertiser",
      lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
      latitude: 0,
      longitude: 0,
      lastModified: UInt32(Date().timeIntervalSince1970)
    )
    _ = try await store.upsertDiscoveredNode(radioID: radioID, from: updatedFrame)

    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).first?.inboundHopCount == 2)
  }

  @Test
  func `an inbound hop heard before the node row exists is applied when the advert upsert lands`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")

    // Firmware emits the RX-log packet (hop count) before the advert push that creates the
    // row, so this write lands first while no row exists.
    try await store.setInboundHopCount(radioID: radioID, publicKey: frame.publicKey, hopCount: 3, advertTimestamp: 100)
    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).isEmpty)

    // The advert upsert then creates the row and must adopt the buffered hop count on first
    // contact, not wait for a second advert.
    let (node, isNew) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    #expect(isNew)
    #expect(node.inboundHopCount == 3)
    #expect(try await store.fetchDiscoveredNodes(radioID: radioID).first?.inboundHopCount == 3)
  }

  @Test
  func `a buffered inbound hop keeps the closest copy of the same broadcast before the row lands`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let ts: UInt32 = 100

    try await store.setInboundHopCount(radioID: radioID, publicKey: frame.publicKey, hopCount: 4, advertTimestamp: ts)
    try await store.setInboundHopCount(radioID: radioID, publicKey: frame.publicKey, hopCount: 1, advertTimestamp: ts)
    // A farther copy of the same buffered broadcast must not raise the count.
    try await store.setInboundHopCount(radioID: radioID, publicKey: frame.publicKey, hopCount: 6, advertTimestamp: ts)

    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    #expect(node.inboundHopCount == 1)
  }

  // MARK: - DiscoveredNodeDTO

  @Test
  func `DiscoveredNodeDTO carries inboundHopCount and equality discriminates on it`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let frame = createTestContactFrame(name: "Advertiser")
    let (node, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    try await store.setInboundHopCount(radioID: radioID, publicKey: node.publicKey, hopCount: 1, advertTimestamp: nil)

    let withHop = try await store.fetchDiscoveredNodes(radioID: radioID).first
    #expect(withHop?.inboundHopCount == 1)

    // Two DTOs differing only in inboundHopCount must not be equal.
    guard let base = withHop else {
      Issue.record("Expected a fetched discovered node")
      return
    }
    let mutated = DiscoveredNodeDTO(
      id: base.id,
      radioID: base.radioID,
      publicKey: base.publicKey,
      name: base.name,
      typeRawValue: base.typeRawValue,
      lastHeard: base.lastHeard,
      lastAdvertTimestamp: base.lastAdvertTimestamp,
      latitude: base.latitude,
      longitude: base.longitude,
      outPathLength: base.outPathLength,
      outPath: base.outPath,
      inboundHopCount: 99,
      inboundHopAdvertTimestamp: nil
    )
    #expect(base != mutated)
  }
}
