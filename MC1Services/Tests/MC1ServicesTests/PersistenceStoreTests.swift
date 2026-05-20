import Foundation
import SwiftData
import Testing
import MeshCore
@testable import MC1Services

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

    @Test("Save and fetch device")
    func saveAndFetchDevice() async throws {
        let store = try await createTestStore()
        let deviceDTO = createTestDevice()

        try await store.saveDevice(deviceDTO)

        let fetched = try await store.fetchDevice(id: deviceDTO.id)
        #expect(fetched != nil)
        #expect(fetched?.nodeName == "TestDevice")
        #expect(fetched?.firmwareVersion == 8)
        #expect(fetched?.frequency == 915_000)
    }

    @Test("Fetch all devices")
    func fetchAllDevices() async throws {
        let store = try await createTestStore()

        let device1 = createTestDevice()
        let device2 = createTestDevice()

        try await store.saveDevice(device1)
        try await store.saveDevice(device2)

        let devices = try await store.fetchDevices()
        #expect(devices.count == 2)
    }

    @Test("Set active device")
    func setActiveDevice() async throws {
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

    /// Seeds all entity types for a device and returns IDs needed for verification.
    private func seedAllEntityTypes(store: PersistenceStore, radioID: UUID) async throws -> (
        contactID: UUID, messageID: UUID, channelID: UUID, sessionID: UUID
    ) {
        let contactFrame = createTestContactFrame(name: "TestContact")
        let contactID = try await store.saveContact(radioID: radioID, from: contactFrame)

        let message = MessageDTO(from: Message(
            radioID: radioID,
            contactID: contactID,
            text: "Hello!",
            timestamp: UInt32(Date().timeIntervalSince1970)
        ))
        try await store.saveMessage(message)

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
    }

    @Test("deleteDevice removes only device record, preserves all associated data")
    func deleteDevicePreservesData() async throws {
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

    @Test("deleteDeviceData removes all associated data but not device record")
    func deleteDeviceDataPreservesDevice() async throws {
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

    @Test("deleteDeviceAndData removes device and all data atomically")
    func deleteDeviceAndDataRemovesAll() async throws {
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

    @Test("deleteDeviceData for non-existent device does not throw")
    func deleteDeviceDataNonExistent() async throws {
        let store = try await createTestStore()
        try await store.deleteDeviceData(id: UUID())
    }

    @Test("deleteDeviceAndData for non-existent device does not throw")
    func deleteDeviceAndDataNonExistent() async throws {
        let store = try await createTestStore()
        try await store.deleteDeviceAndData(id: UUID())
    }

    @Test("Re-pair after device deletion re-associates orphaned data")
    func rePairReassociatesOrphanedData() async throws {
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

    @Test("Demote device to ghost preserves publicKey and radioID with fresh id")
    func demoteDeviceToGhostPreservesIdentity() async throws {
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

    @Test("Demote device strips all connection methods so it stays hidden")
    func demoteDeviceToGhostStripsAllConnectionMethods() async throws {
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

    @Test("Demote device with unknown id is a no-op")
    func demoteDeviceToGhostUnknownIDNoOp() async throws {
        let store = try await createTestStore()
        try await store.demoteDeviceToGhost(id: UUID())
        let devices = try await store.fetchDevices()
        #expect(devices.isEmpty)
    }

    @Test("Removing a paired device preserves child contacts via radioID")
    func demoteRetainsChildLinkageByRadioID() async throws {
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

    @Test("Save and fetch contact from frame")
    func saveAndFetchContactFromFrame() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame(name: "Alice")
        let contactID = try await store.saveContact(radioID: device.id, from: frame)

        let contact = try await store.fetchContact(id: contactID)
        #expect(contact != nil)
        #expect(contact?.name == "Alice")
        #expect(contact?.type == .chat)
    }

    @Test("Fetch contact by public key")
    func fetchContactByPublicKey() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame(name: "Bob")
        _ = try await store.saveContact(radioID: device.id, from: frame)

        let contact = try await store.fetchContact(radioID: device.id, publicKey: frame.publicKey)
        #expect(contact != nil)
        #expect(contact?.name == "Bob")
    }

    @Test("Update contact last message and unread count")
    func updateContactLastMessageAndUnread() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame()
        let contactID = try await store.saveContact(radioID: device.id, from: frame)

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

    @Test("deleteMessagesForContact removes all messages for a contact")
    func deleteMessagesForContactRemovesAll() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Create first contact
        let frame1 = createTestContactFrame(name: "Contact1")
        let contact1ID = try await store.saveContact(radioID: device.id, from: frame1)

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
        let contact2ID = try await store.saveContact(radioID: device.id, from: frame2)
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

    @Test("deleteMessagesForChannel removes all messages for a channel")
    func deleteMessagesForChannelRemovesAll() async throws {
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
        let contactID = try await store.saveContact(radioID: device.id, from: frame)
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

    @Test("Save and fetch messages for contact")
    func saveAndFetchMessagesForContact() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame()
        let contactID = try await store.saveContact(radioID: device.id, from: frame)

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

    @Test("Find channel message for reaction within timestamp window")
    func findChannelMessageForReactionWithinWindow() async throws {
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

    @Test("Find outgoing channel message for reaction using local node name")
    func findOutgoingChannelMessageForReaction() async throws {
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

    @Test("Update message status")
    func updateMessageStatus() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame()
        let contactID = try await store.saveContact(radioID: device.id, from: frame)

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

    @Test("Save and fetch channels")
    func saveAndFetchChannels() async throws {
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

    @Test("Save and fetch remote node session")
    func saveAndFetchRemoteNodeSession() async throws {
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

    @Test("Update room activity advances sync timestamp and sets lastMessageDate")
    func updateRoomActivity() async throws {
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
        #expect(fetched!.lastMessageDate! >= firstDate!)
    }

    @Test("Update room activity ignores older sync timestamps")
    func updateRoomActivityIgnoresOlderSyncTimestamp() async throws {
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

    @Test("Update room activity without sync timestamp does not change lastSyncTimestamp")
    func updateRoomActivityWithoutSyncTimestamp() async throws {
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

    @Test("Mark room session connected changes isConnected and returns true")
    func markRoomSessionConnected() async throws {
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

    @Test("Mark room session connected returns false when already connected")
    func markRoomSessionConnectedAlreadyConnected() async throws {
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

    @Test("Mark session disconnected preserves permission level")
    func markSessionDisconnectedPreservesPermissionLevel() async throws {
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

    @Test("Mark session disconnected is no-op when already disconnected")
    func markSessionDisconnectedAlreadyDisconnected() async throws {
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

    @Test("Disconnect then recover preserves permission level")
    func disconnectThenRecoverPreservesPermissionLevel() async throws {
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

    @Test("Update remote node session connection can reset permission to guest")
    func updateRemoteNodeSessionConnectionResetsPermissionToGuest() async throws {
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

    @Test("Save and fetch room messages")
    func saveAndFetchRoomMessages() async throws {
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

    @Test("Room message deduplication")
    func roomMessageDeduplication() async throws {
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

    @Test("Cleanup duplicate remote node sessions keeps target and deletes others")
    func cleanupDuplicateRemoteNodeSessions() async throws {
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

    @Test("Get total unread counts")
    func getTotalUnreadCounts() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Create contacts with unread messages
        let frame1 = createTestContactFrame(name: "Contact1")
        let contact1ID = try await store.saveContact(radioID: device.id, from: frame1)
        try await store.incrementUnreadCount(contactID: contact1ID)
        try await store.incrementUnreadCount(contactID: contact1ID)

        let frame2 = createTestContactFrame(name: "Contact2")
        let contact2ID = try await store.saveContact(radioID: device.id, from: frame2)
        try await store.incrementUnreadCount(contactID: contact2ID)

        // Create channel with unread messages
        let channelInfo = ChannelInfo(index: 0, name: "Public", secret: Data(repeating: 0, count: 16))
        let channelID = try await store.saveChannel(radioID: device.id, from: channelInfo)
        try await store.incrementChannelUnreadCount(channelID: channelID)
        try await store.incrementChannelUnreadCount(channelID: channelID)
        try await store.incrementChannelUnreadCount(channelID: channelID)

        let (contacts, channels, rooms) = try await store.getTotalUnreadCounts(radioID: device.id)
        #expect(contacts == 3)  // 2 + 1
        #expect(channels == 3)
        #expect(rooms == 0)
    }

    @Test("Get total unread counts excludes blocked contacts")
    func getTotalUnreadCountsExcludesBlockedContacts() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Create a regular contact with unread messages
        let frame1 = createTestContactFrame(name: "RegularContact")
        let regularContactID = try await store.saveContact(radioID: device.id, from: frame1)
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

    @Test("Get total unread counts excludes repeater contacts")
    func getTotalUnreadCountsExcludesRepeaterContacts() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Regular chat contact: visible in the chats list, contributes to badge
        let chatFrame = createTestContactFrame(name: "ChatContact")
        let chatContactID = try await store.saveContact(radioID: device.id, from: chatFrame)
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
        let repeaterID = try await store.saveContact(radioID: device.id, from: repeaterFrame)
        try await store.incrementUnreadCount(contactID: repeaterID)
        try await store.incrementUnreadCount(contactID: repeaterID)
        try await store.incrementUnreadCount(contactID: repeaterID)

        let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: device.id)
        #expect(contacts == 2, "Repeater-type contacts should not contribute to badge total")
    }

    @Test("Get total unread counts excludes repeater-role sessions")
    func getTotalUnreadCountsExcludesRepeaterSessions() async throws {
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

    @Test("Database warm-up")
    func databaseWarmUp() async throws {
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

    @Test("incrementPendingSendAttemptCount from 0 bumps to 1")
    func testIncrementPendingSendAttemptCount_FromZero_BumpsToOne() async throws {
        let store = try await createTestStore()
        let messageID = UUID()
        let dto = makePendingSendDTO(messageID: messageID, attemptCount: 0)
        try await store.upsertPendingSend(dto)

        let result = try await store.incrementPendingSendAttemptCount(messageID: messageID)
        #expect(result == 1, "first drain attempt should bump 0 → 1")

        let persisted = try await store.fetchPendingSends(radioID: dto.radioID).first
        #expect(persisted?.attemptCount == 1, "persisted attemptCount should match return value")
    }

    @Test("incrementPendingSendAttemptCount returns nil when no row matches")
    func testIncrementPendingSendAttemptCount_NoRow_ReturnsNil() async throws {
        let store = try await createTestStore()
        let messageID = UUID()

        let result = try await store.incrementPendingSendAttemptCount(messageID: messageID)
        #expect(result == nil, "missing-row case is terminal — return nil instead of creating a new row")
    }

    @Test("purgeLegacyAttemptCountRows deletes only legacy nil rows")
    func testPurgeLegacyAttemptCountRows_DeletesLegacyNilRowsOnly() async throws {
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

    @Test("purgeLegacyAttemptCountRows is idempotent")
    func testPurgeLegacyAttemptCountRows_IsIdempotent() async throws {
        let store = try await createTestStore()
        let radioID = UUID()
        let dto = makePendingSendDTO(messageID: UUID(), radioID: radioID, attemptCount: nil)
        try await store.upsertPendingSend(dto)

        let firstDeleted = try await store.purgeLegacyAttemptCountRows()
        let secondDeleted = try await store.purgeLegacyAttemptCountRows()
        #expect(firstDeleted == 1, "first call deletes the legacy nil row")
        #expect(secondDeleted == 0, "second call: predicate matches nothing — idempotent on an empty nil set")
    }

    @Test("warmUp runs both purgeOrphanPendingSends and purgeLegacyAttemptCountRows")
    func testWarmUp_RunsBothPurges() async throws {
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

    @Test("deletePendingSendsForMessage public API saves on return")
    func testDeletePendingSendsForMessage_PublicAPISavesOnReturn() async throws {
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

    @Test("deleteMessage cascades the matching PendingSend in a single transaction")
    func testDeleteMessage_CascadesPendingSends() async throws {
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

    @Test("deleteMessage reaps an orphan PendingSend even when no Message row exists")
    func testDeleteMessage_NoMatchingMessage_StillReapsOrphanPendingSend() async throws {
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

    @Test("deleteDeviceData reaps radio-scoped orphan PendingSends and preserves the Device row")
    func testDeleteDeviceData_ReapsRadioOrphanPendingSends() async throws {
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

    @Test("deleteMessagesForContact cascades PendingSends and spares unrelated contacts")
    func testDeleteMessagesForContact_CascadesPendingSends() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame1 = createTestContactFrame(name: "Contact1")
        let contact1ID = try await store.saveContact(radioID: device.id, from: frame1)
        let frame2 = createTestContactFrame(name: "Contact2")
        let contact2ID = try await store.saveContact(radioID: device.id, from: frame2)

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

    @Test("deleteMessagesForChannel cascades PendingSends and spares other channels")
    func testDeleteMessagesForChannel_CascadesPendingSends() async throws {
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

    @Test("deleteChannelMessages(fromSender:) cascades PendingSends and spares other senders")
    func testDeleteChannelMessagesFromSender_CascadesPendingSends() async throws {
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

    @Test("Save and fetch RxLogEntry preserves senderTimestamp")
    func saveAndFetchRxLogEntryPreservesSenderTimestamp() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let expectedTimestamp: UInt32 = 1703123456
        let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: expectedTimestamp)

        try await store.saveRxLogEntry(dto)

        let entries = try await store.fetchRxLogEntries(radioID: device.id)
        #expect(entries.count == 1)
        #expect(entries.first?.senderTimestamp == expectedTimestamp)
    }

    @Test("Save and fetch RxLogEntry with nil senderTimestamp")
    func saveAndFetchRxLogEntryWithNilTimestamp() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: nil)

        try await store.saveRxLogEntry(dto)

        let entries = try await store.fetchRxLogEntries(radioID: device.id)
        #expect(entries.count == 1)
        #expect(entries.first?.senderTimestamp == nil)
    }

    @Test("RxLogEntryDTO init from model preserves senderTimestamp")
    func rxLogEntryDTOInitFromModelPreservesTimestamp() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Save with timestamp
        let expectedTimestamp: UInt32 = 1703123456
        let dto = createTestRxLogEntryDTO(radioID: device.id, senderTimestamp: expectedTimestamp)
        try await store.saveRxLogEntry(dto)

        // Fetch back (this uses RxLogEntryDTO.init(from: RxLogEntry))
        let entries = try await store.fetchRxLogEntries(radioID: device.id)
        #expect(entries.first?.senderTimestamp == expectedTimestamp)

        // Verify the conversion handles the Int -> UInt32 correctly
        // The model stores Int, DTO uses UInt32
        #expect(entries.first?.senderTimestamp == 1703123456)
    }

    @Test("RX log prune is deferred until threshold is exceeded")
    func rxLogPruneDefersUntilThresholdExceeded() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        for index in 0..<1_100 {
            let dto = createTestRxLogEntryDTO(
                radioID: device.id,
                senderTimestamp: UInt32(index)
            )
            try await store.saveRxLogEntry(dto)
            try await store.pruneRxLogEntries(radioID: device.id)
        }

        let entriesBeforeThreshold = try await store.fetchRxLogEntries(radioID: device.id, limit: 1_200)
        #expect(entriesBeforeThreshold.count == 1_100)

        let thresholdEntry = createTestRxLogEntryDTO(
            radioID: device.id,
            senderTimestamp: UInt32(1_100)
        )
        try await store.saveRxLogEntry(thresholdEntry)
        try await store.pruneRxLogEntries(radioID: device.id)

        let entriesAfterThreshold = try await store.fetchRxLogEntries(radioID: device.id, limit: 1_200)
        #expect(entriesAfterThreshold.count == 1_000)
        #expect(entriesAfterThreshold.first?.senderTimestamp == 1_100)
        #expect(entriesAfterThreshold.last?.senderTimestamp == 101)
    }

    @Test("Clearing RX log resets cached count for future pruning")
    func clearRxLogResetsCachedCount() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        for index in 0..<1_101 {
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

    // MARK: - Region Scope Tests

    @Test("saveRxLogEntry forwards regionScope to the persisted model")
    func saveRxLogEntryForwardsRegionScope() async throws {
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

    @Test("saveRxLogEntry preserves payloadTypeBits including unknown nibbles")
    func saveRxLogEntryPreservesPayloadTypeBits() async throws {
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

    @Test("batchUpdateChannelMessageRegion back-fills normal-case message via timestamp fallback")
    func batchUpdateChannelMessageRegionBackfillsNormalCase() async throws {
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

    @Test("batchUpdateChannelMessageRegion back-fills timestamp-corrected message")
    func batchUpdateChannelMessageRegionBackfillsCorrectedCase() async throws {
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

    @Test("batchUpdateChannelMessageRegion skips outgoing messages")
    func batchUpdateChannelMessageRegionSkipsOutgoing() async throws {
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

    @Test("batchUpdateDMMessageRegion back-fills DM by sender prefix byte")
    func batchUpdateDMMessageRegionBackfillsByPrefix() async throws {
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

    @Test("batchUpdateDMMessageRegion ignores DMs from other senders at same timestamp")
    func batchUpdateDMMessageRegionIgnoresOtherSenders() async throws {
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

    // MARK: - Mute Tests

    @Test("Set contact muted")
    func setContactMuted() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        let frame = createTestContactFrame(name: "Alice")
        let contactID = try await store.saveContact(radioID: device.id, from: frame)

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

    @Test("Muted contacts excluded from badge count")
    func mutedContactsExcludedFromBadgeCount() async throws {
        let store = try await createTestStore()
        let device = createTestDevice()
        try await store.saveDevice(device)

        // Create contact with unreads
        let frame1 = createTestContactFrame(name: "Alice")
        let contact1ID = try await store.saveContact(radioID: device.id, from: frame1)
        try await store.incrementUnreadCount(contactID: contact1ID)
        try await store.incrementUnreadCount(contactID: contact1ID)

        // Create muted contact with unreads
        let frame2 = createTestContactFrame(name: "Bob")
        let contact2ID = try await store.saveContact(radioID: device.id, from: frame2)
        try await store.incrementUnreadCount(contactID: contact2ID)
        try await store.setContactMuted(contact2ID, isMuted: true)

        let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: device.id)

        // Only Alice's 2 unreads should count, Bob is muted
        #expect(contacts == 2)
    }

    @Test("Notification levels affect badge count correctly")
    func notificationLevelsAffectBadgeCount() async throws {
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

    @Test("reconcileGhostIdentity rewrites current device when ghost matches publicKey")
    func reconcileGhostIdentityHappyPath() async throws {
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

    @Test("reconcileGhostIdentity is a no-op when current device already owns the publicKey")
    func reconcileGhostIdentityIdempotent() async throws {
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

    @Test("reconcileGhostIdentity returns nil when no ghost matches")
    func reconcileGhostIdentityNoMatch() async throws {
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

    @Test("reconcileGhostIdentity refuses to delete a saved-but-inactive device with BLE methods")
    func reconcileGhostIdentityRefusesNonGhostInactive() async throws {
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

    @Test("reconcileGhostIdentity finds ghost even when current device's publicKey already matches")
    func reconcileGhostIdentityRetryAfterPublicKeyDrift() async throws {
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

    @Test("reconcileGhostIdentity preserves non-BLE methods from backup ghost")
    func reconcileGhostIdentityPreservesBackupWiFiMethods() async throws {
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
}
