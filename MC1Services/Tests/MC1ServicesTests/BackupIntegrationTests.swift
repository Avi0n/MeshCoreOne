import Foundation
import SwiftData
import Testing
@testable import MC1Services

/// Integration tests covering the full export → parse → import pipeline.
/// These tests use real in-memory PersistenceStores and the public AppBackupService API,
/// exercising the complete data flow rather than manually constructed envelopes.
@Suite("BackupIntegration")
struct BackupIntegrationTests {

    // MARK: - Test 1: Full round-trip

    /// Exports from a populated store and imports into a fresh store, verifying all data survives.
    @Test("Full round-trip: export then import into fresh store restores all data")
    func fullRoundTrip() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        // Seed data
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xAB, count: 32),
            name: "Alice"
        )
        try await sourceStore.saveContact(contact)

        let channel = ChannelDTO.testChannel(radioID: radioID, index: 0, name: "General")
        try await sourceStore.saveChannel(channel)

        var msg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, text: "Hello")
        msg.deduplicationKey = "integration-dedup-\(UUID())"
        try await sourceStore.saveMessage(msg)

        let reaction = ReactionDTO.testReaction(messageID: msg.id, radioID: radioID)
        try await sourceStore.saveReaction(reaction)

        // Export
        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)

        // Parse
        let envelope = try parseBackup(data: exportResult.data)
        #expect(envelope.manifest.validate(against: envelope))

        // Import into a fresh store
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        // Verify insert counts
        #expect(result.devicesInserted == 1)
        #expect(result.contactsInserted == 1)
        #expect(result.channelsInserted == 1)
        #expect(result.messagesInserted == 1)
        #expect(result.reactionsInserted == 1)
        #expect(result.totalSkipped == 0)

        // Verify data actually persisted in the destination store
        let destContacts = try await destStore.fetchAllContacts(radioID: radioID)
        #expect(destContacts.count == 1)
        #expect(destContacts.first?.name == "Alice")

        let destChannels = try await destStore.fetchAllChannels(radioID: radioID)
        #expect(destChannels.count == 1)
        #expect(destChannels.first?.name == "General")

        let destMessages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(destMessages.count == 1)
        #expect(destMessages.first?.text == "Hello")

        let messageIDs = Set(destMessages.map(\.id))
        let destReactions = try await destStore.fetchAllReactions(radioID: radioID)
        #expect(destReactions.count == 1)
        // Reaction messageID must match a message in the destination store
        #expect(messageIDs.contains(destReactions.first!.messageID))
    }

    // MARK: - Test 1b: Unmodeled contact type byte survives backup

    /// `ContactDTO.typeRawValue` is a non-optional field that has existed since the backup
    /// feature shipped, so the contract is a plain encode → decode round-trip (no
    /// `decodeIfPresent` legacy path). An unmodeled type byte must survive verbatim so a
    /// future refactor can't silently re-collapse it onto a modeled `ContactType`.
    @Test("Unmodeled contact type byte (0x04) survives DTO encode → decode round-trip")
    func unmodeledContactTypeSurvivesCodableRoundTrip() throws {
        let dto = ContactDTO.testContact(typeRawValue: 0x04)
        let encoded = try JSONEncoder().encode(dto)
        let decoded = try JSONDecoder().decode(ContactDTO.self, from: encoded)
        #expect(decoded.typeRawValue == 0x04)
        #expect(decoded.type == .chat)
    }

    /// Full envelope path: an unmodeled type byte must survive export → import into a fresh store,
    /// not just an isolated DTO round-trip.
    @Test("Unmodeled contact type byte (0x04) survives full backup export → import")
    func unmodeledContactTypeSurvivesBackupPipeline() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xAB, count: 32),
            name: "FutureType",
            typeRawValue: 0x04
        )
        try await sourceStore.saveContact(contact)

        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        _ = try await service.importBackup(envelope: envelope, into: destStore)

        let destContacts = try await destStore.fetchAllContacts(radioID: radioID)
        #expect(destContacts.first?.typeRawValue == 0x04)
        #expect(destContacts.first?.type == .chat)
    }

    // MARK: - Test 2: Cross-bundle radioID remapping

    /// When the target store contains a device with the same publicKey as the backup but a
    /// different radioID, all child records must be remapped to the local radioID.
    @Test("Cross-bundle: child records are remapped to local radioID on publicKey match")
    func crossBundleRadioIDRemapping() async throws {
        let sharedPublicKey = Data(repeating: 0xCC, count: 32)
        let sourceRadioID = UUID()
        let targetRadioID = UUID()

        // Source store: device with sharedPublicKey, radioID = sourceRadioID
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: sourceRadioID)
        let sourceDevice = DeviceDTO.testDevice(
            id: sourceRadioID,
            radioID: sourceRadioID,
            publicKey: sharedPublicKey
        )
        try await sourceStore.saveDevice(sourceDevice)

        let contact = ContactDTO.testContact(
            radioID: sourceRadioID,
            publicKey: Data(repeating: 0xDD, count: 32),
            name: "Bob"
        )
        try await sourceStore.saveContact(contact)

        // Export from source
        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        // Target store: device with same publicKey but different radioID
        let targetStore = try await PersistenceStore.createTestDataStore(radioID: targetRadioID)
        let targetDevice = DeviceDTO.testDevice(
            id: targetRadioID,
            radioID: targetRadioID,
            publicKey: sharedPublicKey
        )
        try await targetStore.saveDevice(targetDevice)

        // Import
        let result = try await service.importBackup(
            envelope: envelope,
            into: targetStore
        )

        // Backup device matched by publicKey → not inserted
        #expect(result.devicesInserted == 0)
        #expect(result.contactsInserted == 1)

        // Contact must live under targetRadioID, not sourceRadioID
        let contactsUnderTarget = try await targetStore.fetchAllContacts(radioID: targetRadioID)
        #expect(contactsUnderTarget.count == 1)
        #expect(contactsUnderTarget.first?.name == "Bob")

        let contactsUnderSource = try await targetStore.fetchAllContacts(radioID: sourceRadioID)
        #expect(contactsUnderSource.count == 0)
    }

    // MARK: - Test 4: Merge import — DM thread visible for existing contact

    @Test("Import onto existing contact with nil lastMessageDate makes DM thread visible")
    func importOntoExistingContact_DmThreadVisible() async throws {
        let radioID = UUID()
        let sharedPublicKey = Data(repeating: 0xAB, count: 32)

        // Destination store has a contact with no message history
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: sharedPublicKey,
            name: "Alice",
            lastMessageDate: nil
        )
        try await destStore.saveContact(contact)

        // Verify contact is NOT in conversations list (lastMessageDate is nil)
        let beforeConversations = try await destStore.fetchConversations(radioID: radioID)
        #expect(beforeConversations.isEmpty)

        // Build backup with DMs for the same contact
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupContact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: sharedPublicKey,
            name: "Alice",
            lastMessageDate: Date()
        )
        var msg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: backupContact.id,
            text: "Restored DM"
        )
        msg.deduplicationKey = "merge-dm-\(UUID())"

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [backupContact],
            messages: [msg]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        // Contact was skipped (already exists), message was inserted
        #expect(result.contactsSkipped == 1)
        #expect(result.messagesInserted == 1)

        // Contact must now appear in conversations
        let afterConversations = try await destStore.fetchConversations(radioID: radioID)
        #expect(afterConversations.count == 1)
        #expect(afterConversations.first?.name == "Alice")
        #expect(afterConversations.first?.lastMessageDate != nil)
    }

    // MARK: - Test 5: Merge import — MessageRepeat relationship set (pre-existing parent)

    @Test("Import repeats onto existing message sets relationship and enables cascade delete")
    func importOntoExistingMessage_RepeatRelationshipSet() async throws {
        let radioID = UUID()

        // Destination store has an existing message
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)
        try await destStore.saveContact(contact)

        var existingMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Existing",
            direction: .incoming
        )
        existingMsg.deduplicationKey = "existing-msg-key"
        try await destStore.saveMessage(existingMsg)

        // Build backup that contributes a repeat for the same message
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        var backupMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Existing",
            direction: .incoming
        )
        backupMsg.deduplicationKey = "existing-msg-key"

        let repeat1 = MessageRepeatDTO.testRepeat(
            messageID: backupMsg.id,
            pathNodes: Data([0x31])
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [contact],
            messages: [backupMsg],
            messageRepeats: [repeat1]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.messagesSkipped == 1)
        #expect(result.messageRepeatsInserted == 1)

        // Verify the repeat was linked to the existing message
        let repeats = try await destStore.fetchMessageRepeats(messageID: existingMsg.id)
        #expect(repeats.count == 1)

        // Delete the message — repeat must cascade-delete
        try await destStore.deleteMessage(id: existingMsg.id)
        let repeatsAfterDelete = try await destStore.fetchMessageRepeats(messageID: existingMsg.id)
        #expect(repeatsAfterDelete.isEmpty)
    }

    // MARK: - Test 6: Fresh-store import — MessageRepeat cascade delete

    @Test("Fresh-store import sets MessageRepeat relationship and cascade deletes work")
    func freshStoreImport_RepeatCascadeDelete() async throws {
        let radioID = UUID()

        // Source store with a message and repeats
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xBB, count: 32),
            name: "Bob"
        )
        try await sourceStore.saveContact(contact)

        var msg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: 0,
            text: "Hello mesh"
        )
        msg.deduplicationKey = "fresh-cascade-\(UUID())"
        try await sourceStore.saveMessage(msg)

        // Save repeat using the normal (relationship-setting) path
        let repeatDTO = MessageRepeatDTO.testRepeat(messageID: msg.id, pathNodes: Data([0x42]))
        try await sourceStore.saveMessageRepeat(repeatDTO)

        // Export
        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        // Import into fresh store
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )
        #expect(result.messagesInserted == 1)
        #expect(result.messageRepeatsInserted == 1)

        // Verify repeats exist
        let destMessages = try await destStore.fetchAllMessages(radioID: radioID)
        let destMsgID = try #require(destMessages.first?.id)
        let repeats = try await destStore.fetchMessageRepeats(messageID: destMsgID)
        #expect(repeats.count == 1)

        // Delete the message — repeat must cascade-delete
        try await destStore.deleteMessage(id: destMsgID)
        let repeatsAfterDelete = try await destStore.fetchMessageRepeats(messageID: destMsgID)
        #expect(repeatsAfterDelete.isEmpty)
    }

    // MARK: - Test 7: Merge import — caches recomputed

    @Test("Import repeats/reactions onto existing message recomputes heardRepeats and reactionSummary")
    func importOntoExistingMessage_CachesRecomputed() async throws {
        let radioID = UUID()

        // Destination store with a message (heardRepeats=0, no reactions)
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)
        try await destStore.saveContact(contact)

        var existingMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Cache test",
            direction: .incoming,
            heardRepeats: 0
        )
        existingMsg.deduplicationKey = "cache-test-key"
        try await destStore.saveMessage(existingMsg)

        // Build backup that adds 2 repeats and 1 reaction to the same message
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        var backupMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Cache test",
            direction: .incoming
        )
        backupMsg.deduplicationKey = "cache-test-key"

        let repeat1 = MessageRepeatDTO.testRepeat(
            messageID: backupMsg.id,
            pathNodes: Data([0x31])
        )
        let repeat2 = MessageRepeatDTO.testRepeat(
            messageID: backupMsg.id,
            pathNodes: Data([0x42])
        )
        let reaction = ReactionDTO.testReaction(
            messageID: backupMsg.id,
            radioID: radioID,
            emoji: "👍",
            senderName: "Eve"
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [contact],
            messages: [backupMsg],
            messageRepeats: [repeat1, repeat2],
            reactions: [reaction]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.messagesSkipped == 1)
        #expect(result.messageRepeatsInserted == 2)
        #expect(result.reactionsInserted == 1)

        // Verify heardRepeats was recomputed
        let updatedMsg = try await destStore.fetchMessage(id: existingMsg.id)
        #expect(updatedMsg?.heardRepeats == 2)

        // Verify reactionSummary was recomputed
        #expect(updatedMsg?.reactionSummary == "👍:1")
    }

    // MARK: - Test 8: Merge import — channel metadata refreshed

    @Test("Import messages onto existing channel with nil lastMessageDate refreshes metadata")
    func importOntoExistingChannel_ChannelMetadataRefreshed() async throws {
        let radioID = UUID()

        // Destination store has a channel with no messages
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let channel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 0,
            name: "General",
            lastMessageDate: nil
        )
        try await destStore.saveChannel(channel)

        // Verify channel has nil lastMessageDate
        let beforeChannel = try await destStore.fetchChannel(radioID: radioID, index: 0)
        #expect(beforeChannel?.lastMessageDate == nil)

        // Build backup with messages for the same channel
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(radioID: radioID, index: 0, name: "General")
        var msg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: 0,
            text: "Restored channel msg"
        )
        msg.deduplicationKey = "channel-meta-\(UUID())"

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            channels: [backupChannel],
            messages: [msg]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.channelsSkipped == 1)
        #expect(result.messagesInserted == 1)

        // Channel must now have lastMessageDate set
        let afterChannel = try await destStore.fetchChannel(radioID: radioID, index: 0)
        #expect(afterChannel?.lastMessageDate != nil)
    }

    // MARK: - Test 9: Merge import — saved trace path runs preserved

    @Test("Import onto existing saved trace path merges runs without duplicating them")
    func importOntoExistingSavedTracePath_MergesRuns() async throws {
        let radioID = UUID()
        let pathBytes = Data([0x12, 0x34, 0x56, 0x78])
        let existingRun = TracePathRunDTO.testRun(
            date: Date().addingTimeInterval(-120),
            roundTripMs: 180
        )
        let importedRun = TracePathRunDTO.testRun(
            date: Date(),
            roundTripMs: 95
        )

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingPath = try await destStore.createSavedTracePath(
            radioID: radioID,
            name: "Shared Route",
            pathBytes: pathBytes,
            hashSize: 2,
            initialRun: existingRun
        )

        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupPath = SavedTracePathDTO.testPath(
            radioID: radioID,
            name: "Shared Route",
            pathBytes: pathBytes,
            hashSize: 2,
            runs: [importedRun]
        )
        let envelope = AppBackupEnvelope.test(
            devices: [device],
            savedTracePaths: [backupPath]
        )

        let service = AppBackupService()

        let firstResult = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(firstResult.savedTracePathsInserted == 0)
        #expect(firstResult.savedTracePathsSkipped == 1)
        #expect(firstResult.savedTracePathsMerged == 1)
        #expect(firstResult.hasRestoredChanges)

        let mergedPath = try #require(await destStore.fetchSavedTracePath(id: existingPath.id))
        #expect(mergedPath.runs.count == 2)
        #expect(Set(mergedPath.runs.map(\.id)) == Set([existingRun.id, importedRun.id]))

        let secondResult = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(secondResult.savedTracePathsInserted == 0)
        #expect(secondResult.savedTracePathsSkipped == 1)
        #expect(secondResult.savedTracePathsMerged == 0)
        #expect(!secondResult.hasRestoredChanges)

        let deduplicatedPath = try #require(await destStore.fetchSavedTracePath(id: existingPath.id))
        #expect(deduplicatedPath.runs.count == 2)
        #expect(Set(deduplicatedPath.runs.map(\.id)) == Set([existingRun.id, importedRun.id]))
    }

    // MARK: - Test 10: Orphaned radio-scoped data survives export/import

    @Test("Export preserves orphaned radio-scoped data after device-only delete")
    func exportPreservesOrphanedRadioScopedData() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xD1, count: 32),
            name: "Orphaned Contact"
        )
        try await sourceStore.saveContact(contact)

        let channel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 1,
            name: "Orphaned Channel",
            unreadCount: 3,
            notificationLevel: .mentionsOnly,
            isFavorite: true
        )
        try await sourceStore.saveChannel(channel)

        var message = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Preserve me"
        )
        message.deduplicationKey = "orphaned-message-\(UUID())"
        try await sourceStore.saveMessage(message)

        let session = RemoteNodeSessionDTO.testSession(radioID: radioID)
        try await sourceStore.saveRemoteNodeSessionDTO(session)

        let roomMessage = RoomMessageDTO.testRoomMessage(sessionID: session.id)
        try await sourceStore.saveRoomMessage(roomMessage)

        let blockedSender = BlockedChannelSenderDTO.testBlockedSender(radioID: radioID)
        try await sourceStore.saveBlockedChannelSender(blockedSender)

        let tracePath = SavedTracePathDTO.testPath(radioID: radioID)
        let initialRun = tracePath.runs.first
        _ = try await sourceStore.createSavedTracePath(
            radioID: tracePath.radioID,
            name: tracePath.name,
            pathBytes: tracePath.pathBytes,
            hashSize: tracePath.hashSize,
            initialRun: initialRun
        )

        try await sourceStore.deleteDevice(id: radioID)
        #expect(try await sourceStore.fetchDevice(id: radioID) == nil)

        let service = AppBackupService()
        let result = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: result.data)

        #expect(envelope.devices.isEmpty)
        #expect(envelope.contacts.count == 1)
        #expect(envelope.channels.count == 1)
        #expect(envelope.messages.count == 1)
        #expect(envelope.remoteNodeSessions.count == 1)
        #expect(envelope.roomMessages.count == 1)
        #expect(envelope.savedTracePaths.count == 1)
        #expect(envelope.blockedChannelSenders.count == 1)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let firstResult = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(firstResult.devicesInserted == 0)
        #expect(firstResult.contactsInserted == 1)
        #expect(firstResult.channelsInserted == 1)
        #expect(firstResult.messagesInserted == 1)
        #expect(firstResult.remoteNodeSessionsInserted == 1)
        #expect(firstResult.roomMessagesInserted == 1)
        #expect(firstResult.savedTracePathsInserted == 1)
        #expect(firstResult.blockedChannelSendersInserted == 1)

        #expect(try await destStore.fetchAllContacts(radioID: radioID).count == 1)
        #expect(try await destStore.fetchAllChannels(radioID: radioID).count == 1)
        #expect(try await destStore.fetchAllMessages(radioID: radioID).count == 1)
        #expect(try await destStore.fetchRemoteNodeSessions(radioID: radioID).count == 1)
        #expect(try await destStore.fetchRoomMessages(sessionID: session.id).count == 1)
        #expect(try await destStore.fetchSavedTracePaths(radioID: radioID).count == 1)
        #expect(try await destStore.fetchBlockedChannelSenders(radioID: radioID).count == 1)

        let secondResult = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(secondResult.totalInserted == 0)
        #expect(secondResult.contactsSkipped == 1)
        #expect(secondResult.channelsSkipped == 1)
        #expect(secondResult.messagesSkipped == 1)
        #expect(secondResult.remoteNodeSessionsSkipped == 1)
        #expect(secondResult.roomMessagesSkipped == 1)
        #expect(secondResult.savedTracePathsSkipped == 1)
        #expect(secondResult.blockedChannelSendersSkipped == 1)
    }

    // MARK: - Test 11: Merge import — contact metadata restored

    @Test("Import onto existing contact restores backup-owned contact metadata")
    func importOntoExistingContact_RestoresBackupMetadata() async throws {
        let radioID = UUID()
        let sharedPublicKey = Data(repeating: 0xE2, count: 32)
        let importedDate = Date(timeIntervalSince1970: 1_700_000_000)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingContact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: sharedPublicKey,
            name: "Alice",
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0
        )
        try await destStore.saveContact(existingContact)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupContact = ContactDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: sharedPublicKey,
            name: "Alice",
            typeRawValue: existingContact.typeRawValue,
            flags: existingContact.flags,
            outPathLength: existingContact.outPathLength,
            outPath: existingContact.outPath,
            lastAdvertTimestamp: existingContact.lastAdvertTimestamp,
            latitude: existingContact.latitude,
            longitude: existingContact.longitude,
            lastModified: existingContact.lastModified,
            nickname: "Field Ops",
            isBlocked: true,
            isMuted: true,
            isFavorite: true,
            lastMessageDate: importedDate,
            unreadCount: 7,
            unreadMentionCount: 2,
            ocvPreset: OCVPreset.custom.rawValue,
            customOCVArrayString: "4200,4100,4000"
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            contacts: [backupContact]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.contactsInserted == 0)
        #expect(result.contactsSkipped == 1)

        let mergedContact = try #require(
            await destStore.fetchContact(radioID: radioID, publicKey: sharedPublicKey)
        )
        #expect(mergedContact.nickname == "Field Ops")
        #expect(mergedContact.isBlocked == true)
        #expect(mergedContact.isMuted == true)
        #expect(mergedContact.isFavorite == true)
        #expect(mergedContact.lastMessageDate == importedDate)
        #expect(mergedContact.unreadCount == 7)
        #expect(mergedContact.unreadMentionCount == 2)
        #expect(mergedContact.ocvPreset == OCVPreset.custom.rawValue)
        #expect(mergedContact.customOCVArrayString == "4200,4100,4000")
    }

    // MARK: - Test 12: Merge import — channel metadata restored

    @Test("Import onto existing channel restores backup-owned channel metadata")
    func importOntoExistingChannel_RestoresBackupMetadata() async throws {
        let radioID = UUID()
        let importedDate = Date(timeIntervalSince1970: 1_700_000_100)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 2,
            name: "Ops",
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            notificationLevel: .all,
            isFavorite: false,
            floodScope: .inherit
        )
        try await destStore.saveChannel(existingChannel)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: existingChannel.index,
            name: existingChannel.name,
            lastMessageDate: importedDate,
            unreadCount: 11,
            unreadMentionCount: 4,
            notificationLevel: .mentionsOnly,
            isFavorite: true,
            floodScope: .region("US")
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupChannel]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.channelsInserted == 0)
        #expect(result.channelsSkipped == 1)

        let mergedChannel = try #require(await destStore.fetchChannel(radioID: radioID, index: 2))
        #expect(mergedChannel.lastMessageDate == importedDate)
        #expect(mergedChannel.unreadCount == 11)
        #expect(mergedChannel.unreadMentionCount == 4)
        #expect(mergedChannel.notificationLevel == .mentionsOnly)
        #expect(mergedChannel.isFavorite == true)
        #expect(mergedChannel.regionScope == "US")
    }

    // MARK: - Test 12b: Different-secret slot collision relocates instead of merging

    /// Local `#kosice` (secret 0x01) occupies slot 2; the backup carries a distinct channel
    /// `#praha` (secret 0x02) that also claims slot 2. Because the two secrets differ, this
    /// is "different channel, relocate", never a metadata merge: `#kosice` keeps its slot,
    /// name, and secret untouched, and `#praha` is inserted as a separate channel on a free
    /// slot. Contrast with same-secret merges, where backup metadata is adopted in place.
    @Test("Import onto a different-secret slot keeps the local channel and inserts the backup channel separately")
    func importOntoExistingChannel_DifferentSecretRelocatesInsteadOfMerging() async throws {
        let radioID = UUID()
        let localSecret = Data(repeating: 0x01, count: 16)
        let backupSecret = Data(repeating: 0x02, count: 16)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 2,
            name: "#kosice",
            secret: localSecret
        )
        try await destStore.saveChannel(existingChannel)

        // Same (radioID, index) slot, but a different name and secret in the backup.
        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: 2,
            name: "#praha",
            secret: backupSecret
        )

        let envelope = AppBackupEnvelope.test(devices: [backupDevice], channels: [backupChannel])
        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        // The backup channel is a distinct identity, so it is inserted (relocated), not
        // merged into #kosice's slot.
        #expect(result.channelsInserted == 1)
        #expect(result.channelsSkipped == 0)

        // #kosice keeps its slot, name, and secret.
        let kosice = try #require(await destStore.fetchChannel(radioID: radioID, index: 2))
        #expect(kosice.name == "#kosice")
        #expect(kosice.secret == localSecret)

        // #praha exists as a separate channel on a different slot, with its own name/secret.
        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        let praha = try #require(channels.first { $0.secret == backupSecret })
        #expect(praha.name == "#praha")
        #expect(praha.index != 2)
    }

    // MARK: - Channel reconciliation by secret (slot relocation) — REPRO

    /// Reproduction for silent wrong-context delivery. Backup `#praha` (secretA) lives
    /// at slot 3 with a channel message; locally slot 3 is `#brno` (secretB). With slot-only
    /// reconciliation the imported message attaches to `#brno` and `#praha` is dropped. The
    /// fix reconciles by `(radioID, secret)`: `#praha` relocates to a free slot and its
    /// message follows; `#brno` is untouched.
    @Test("Channel reconcile: backup channel collides with a different-secret local slot, relocates and carries its message")
    func channelSecretReconcile_RelocatesOnDifferentSecretSlotCollision() async throws {
        let radioID = UUID()
        let secretBrno = Data(repeating: 0xB0, count: 16)
        let secretPraha = Data(repeating: 0xA0, count: 16)
        let collisionIndex: UInt8 = 3

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let localBrno = ChannelDTO.testChannel(
            radioID: radioID,
            index: collisionIndex,
            name: "#brno",
            secret: secretBrno
        )
        try await destStore.saveChannel(localBrno)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupPraha = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: collisionIndex,
            name: "#praha",
            secret: secretPraha
        )
        var prahaMessage = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: collisionIndex,
            text: "Praha checkpoint",
            timestamp: 1_700_000_900,
            direction: .incoming,
            senderNodeName: "Jan"
        )
        prahaMessage.deduplicationKey = "ch-\(collisionIndex)-1700000900-Jan-DEADBEEF"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupPraha],
            messages: [prahaMessage]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        // #praha is a distinct channel: inserted (relocated), not merged into #brno's slot.
        #expect(result.channelsInserted == 1)
        #expect(result.channelsSkipped == 0)
        #expect(result.messagesInserted == 1)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        let brno = try #require(channels.first { $0.secret == secretBrno })
        let praha = try #require(channels.first { $0.secret == secretPraha })

        // #brno keeps its slot and name; #praha lands on a different (free) slot.
        #expect(brno.index == collisionIndex)
        #expect(brno.name == "#brno")
        #expect(praha.index != collisionIndex)
        #expect(praha.name == "#praha")

        // The message followed #praha's relocated slot, not #brno's.
        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.count == 1)
        let restored = try #require(messages.first)
        #expect(restored.channelIndex == praha.index)
        #expect(restored.text == "Praha checkpoint")

        // #brno received no foreign message.
        let brnoMessages = messages.filter { $0.channelIndex == collisionIndex }
        #expect(brnoMessages.isEmpty)
    }

    @Test("Re-importing a stale backup never upserts a reconfigured live channel")
    func staleBackupDoesNotOverwriteReconfiguredChannel() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let radioID = UUID()
        let sharedID = UUID()

        // Live channel: same surrogate id as the backup, but secret rotated in place (S2),
        // occupying the backup's slot.
        let liveSecret = Data(repeating: 0xB2, count: 32)
        let live = ChannelDTO(
            id: sharedID, radioID: radioID, index: 3, name: "Live Net",
            secret: liveSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        try await store.batchInsertChannels([live], radioIDs: [radioID], maxChannelsByRadioID: [radioID: 8])

        // Backup channel: same id, slot 3, but the original secret S1 (no local match).
        let backupSecret = Data(repeating: 0xA1, count: 32)
        let backup = ChannelDTO(
            id: sharedID, radioID: radioID, index: 3, name: "Stale Net",
            secret: backupSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        let result = try await store.batchInsertChannels([backup], radioIDs: [radioID], maxChannelsByRadioID: [radioID: 8])

        let channels = try await store.fetchChannels(radioID: radioID)
        // Live channel survives untouched at slot 3 with its rotated secret.
        let liveRow = try #require(channels.first { $0.secret == liveSecret })
        #expect(liveRow.index == 3)
        #expect(liveRow.name == "Live Net")
        // Backup channel coexists, relocated to a free slot with a fresh id.
        let backupRow = try #require(channels.first { $0.secret == backupSecret })
        #expect(backupRow.id != sharedID)
        #expect(backupRow.index != 3)
        #expect(result.inserted == 1)
    }

    @Test("batchInsertChannels reports newly-occupied local slots for draft clearing, excluding merges")
    func batchInsertReportsInsertedLocalIndicesForDraftClearing() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let radioID = UUID()

        let mergeSecret = Data(repeating: 0xC1, count: 32)
        let occupiedSecret = Data(repeating: 0xC4, count: 32)
        let localAtSlot2 = ChannelDTO(
            id: UUID(), radioID: radioID, index: 2, name: "Keep",
            secret: mergeSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        let localAtSlot4 = ChannelDTO(
            id: UUID(), radioID: radioID, index: 4, name: "Occupied",
            secret: occupiedSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        try await store.batchInsertChannels(
            [localAtSlot2, localAtSlot4], radioIDs: [radioID], maxChannelsByRadioID: [radioID: 8]
        )

        // (a) merges by secret into local slot 2 though recorded at slot 6 — occupant unchanged.
        let mergeBackup = ChannelDTO(
            id: UUID(), radioID: radioID, index: 6, name: "Keep",
            secret: mergeSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        // (b) foreign channel placed at its own free slot 5.
        let collideSecret = Data(repeating: 0xD4, count: 32)
        let freshAtFreeSlot = ChannelDTO(
            id: UUID(), radioID: radioID, index: 5, name: "Fresh",
            secret: Data(repeating: 0xD5, count: 32), isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        // (c) foreign channel colliding with occupied slot 4, relocated to a free slot.
        let collideAtSlot4 = ChannelDTO(
            id: UUID(), radioID: radioID, index: 4, name: "Collide",
            secret: collideSecret, isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0,
            notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )

        let result = try await store.batchInsertChannels(
            [mergeBackup, freshAtFreeSlot, collideAtSlot4],
            radioIDs: [radioID], maxChannelsByRadioID: [radioID: 8]
        )

        let inserted = result.insertedLocalIndices[radioID] ?? []
        // Fresh insert recorded at its own free slot.
        #expect(inserted.contains(5))
        // Merge-by-secret destination (2) and backup-file source (6) are not local inserts.
        #expect(!inserted.contains(2))
        #expect(!inserted.contains(6))
        // The colliding channel's untouched source slot (4) is not recorded; its relocation
        // destination is.
        #expect(!inserted.contains(4))
        let relocatedRow = try #require(
            (try await store.fetchChannels(radioID: radioID)).first { $0.secret == collideSecret }
        )
        #expect(relocatedRow.index != 4)
        #expect(inserted.contains(relocatedRow.index))
        #expect(result.inserted == 2)
    }

    @Test("Exporting a channel with an unmigrated notification level does not migrate the live row")
    func exportReadDoesNotMigrateNotificationLevel() throws {
        let channel = Channel(
            radioID: UUID(), index: 1, name: "Ops",
            secret: Data(repeating: 0x33, count: 32)
        )
        // Simulate a pre-migration row: -1 sentinel with the legacy muted flag set.
        channel.notificationLevelRawValue = -1
        channel.legacyIsMuted = true

        let dto = ChannelDTO(from: channel)

        // The read did not trigger the migrating getter's in-memory write-back.
        #expect(channel.notificationLevelRawValue == -1)
        // The DTO still carries the correctly decoded level (muted, from legacy isMuted).
        #expect(dto.notificationLevel == .muted)
    }

    @Test("Duplicate device public keys in one envelope skip the loser and remap its children")
    func duplicatePublicKeyRemapsChildrenToSinglePartition() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let key = Data(repeating: 0x42, count: 32)
        let radioA = UUID(), radioB = UUID()
        let devA = DeviceDTO.testDevice().copy { $0.publicKey = key; $0.radioID = radioA }
        let devB = DeviceDTO.testDevice().copy { $0.publicKey = key; $0.radioID = radioB }
        // Both devices share the duplicate `key` (the scenario under test), but the contacts
        // must carry distinct public keys: after both contacts' radioID is remapped to the
        // single surviving local radioID they would otherwise share an identical `contactKey`
        // (radioID + publicKey) and the second would be deduped, collapsing to one row.
        let contactA = ContactDTO.testContact(radioID: radioA, publicKey: Data(repeating: 0x01, count: 32))
        let contactB = ContactDTO.testContact(radioID: radioB, publicKey: Data(repeating: 0x02, count: 32))
        let envelope = AppBackupEnvelope.test(
            devices: [devA, devB], contacts: [contactA, contactB]
        )
        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: store)
        #expect(result.counts[.devices]?.skipped == 1)   // loser duplicate
        #expect(result.counts[.devices]?.inserted == 1)
        // Both contacts land under the single surviving local radioID.
        let devices = try await store.fetchAllDevices()
        let localRadioID = try #require(devices.first?.radioID)
        let contacts = try await store.fetchContacts(radioID: localRadioID)
        #expect(contacts.count == 2)
    }

    @Test("Channel floodScope survives a fresh-insert export/import round-trip")
    func channelFloodScopeRoundTripsOnFreshInsert() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        try await sourceStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 0, name: "General", floodScope: .inherit)
        )
        try await sourceStore.saveChannel(
            ChannelDTO.testChannel(
                radioID: radioID, index: 1, name: "Regional",
                secret: Data(repeating: 0x55, count: 32), floodScope: .region("US")
            )
        )

        let service = AppBackupService()
        let envelope = try parseBackup(data: try await service.export(persistenceStore: sourceStore).data)

        // Fresh dest store: both channels take the fresh-insert path (Channel(dto:)), not merge.
        let destStore = PersistenceStore(modelContainer: try PersistenceStore.createContainer(inMemory: true))
        let result = try await service.importBackup(envelope: envelope, into: destStore)
        #expect(result.channelsInserted == 2)

        let restoredRegional = try #require(try await destStore.fetchChannel(radioID: radioID, index: 1))
        #expect(restoredRegional.floodScope == .region("US"))
        let restoredInherit = try #require(try await destStore.fetchChannel(radioID: radioID, index: 0))
        #expect(restoredInherit.floodScope == .inherit)
    }

    @Test("Re-importing the same backup is idempotent for unread counts and last-message dates")
    func secondImportIsIdempotentForMergeFields() async throws {
        let radioID = UUID()
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        // Local channel the backup will merge into (matched by stable secret).
        let secret = Data(repeating: 0x77, count: 32)
        let localDate = Date(timeIntervalSince1970: 1_700_000_000)
        try await destStore.saveChannel(
            ChannelDTO.testChannel(
                radioID: radioID, index: 1, name: "Ops", secret: secret,
                lastMessageDate: localDate, unreadCount: 5
            )
        )

        // Backup carries the same channel with a higher unread count and a later date.
        let backupDate = Date(timeIntervalSince1970: 1_700_009_000)
        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            radioID: radioID, index: 1, name: "Ops", secret: secret,
            lastMessageDate: backupDate, unreadCount: 9
        )
        let envelope = AppBackupEnvelope.test(devices: [backupDevice], channels: [backupChannel])

        let service = AppBackupService()
        _ = try await service.importBackup(envelope: envelope, into: destStore)
        let afterFirst = try #require(try await destStore.fetchChannel(radioID: radioID, index: 1))

        _ = try await service.importBackup(envelope: envelope, into: destStore)
        let afterSecond = try #require(try await destStore.fetchChannel(radioID: radioID, index: 1))

        // The merge (max of local/backup) and date reconciliation are idempotent: a repeat
        // import neither double-counts unread nor oscillates the last-message date.
        #expect(afterSecond.unreadCount == afterFirst.unreadCount)
        #expect(afterSecond.unreadMentionCount == afterFirst.unreadMentionCount)
        #expect(afterSecond.lastMessageDate == afterFirst.lastMessageDate)
    }

    /// Case A: same secret at the same slot is a pure metadata merge with no relocation.
    @Test("Channel reconcile: same secret at same slot merges metadata, index unchanged")
    func channelSecretReconcile_SameSecretSameSlotIsNoOp() async throws {
        let radioID = UUID()
        let secret = Data(repeating: 0x44, count: 16)
        let slot: UInt8 = 2
        let importedDate = Date(timeIntervalSince1970: 1_700_000_950)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let localChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: slot,
            name: "#ops",
            secret: secret,
            lastMessageDate: nil
        )
        try await destStore.saveChannel(localChannel)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: slot,
            name: "#ops",
            secret: secret,
            lastMessageDate: importedDate
        )
        var msg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: slot,
            text: "Same slot",
            timestamp: 1_700_000_950,
            // Older than importedDate so the merged channel date is the max and survives
            // the lastMessageDate reconcile pass (which keys on createdAt).
            createdAt: Date(timeIntervalSince1970: 1_600_000_000),
            direction: .incoming,
            senderNodeName: "Eva"
        )
        msg.deduplicationKey = "ch-\(slot)-1700000950-Eva-CAFE0001"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupChannel],
            messages: [msg]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 0)
        #expect(result.channelsSkipped == 1)
        #expect(result.messagesInserted == 1)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        #expect(channels.count == 1)
        #expect(channels.first?.index == slot)
        #expect(channels.first?.secret == secret)
        #expect(channels.first?.lastMessageDate == importedDate)

        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.count == 1)
        #expect(messages.first?.channelIndex == slot)
    }

    /// Case B: the same secret moved slots between backup and restore. The message
    /// remaps to the local slot; no duplicate channel is inserted.
    @Test("Channel reconcile: same secret on a different slot remaps the message, no duplicate channel")
    func channelSecretReconcile_SameSecretDifferentSlotRemapsMessage() async throws {
        let radioID = UUID()
        let secret = Data(repeating: 0x55, count: 16)
        let backupSlot: UInt8 = 3
        let localSlot: UInt8 = 5

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let localChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: localSlot,
            name: "#praha",
            secret: secret
        )
        try await destStore.saveChannel(localChannel)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: backupSlot,
            name: "#praha",
            secret: secret
        )
        var msg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: backupSlot,
            text: "Moved slots",
            timestamp: 1_700_001_000,
            direction: .incoming,
            senderNodeName: "Lena"
        )
        msg.deduplicationKey = "ch-\(backupSlot)-1700001000-Lena-0BADF00D"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupChannel],
            messages: [msg]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 0)
        #expect(result.channelsSkipped == 1)
        #expect(result.messagesInserted == 1)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        #expect(channels.count == 1)
        #expect(channels.first?.index == localSlot)

        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.count == 1)
        #expect(messages.first?.channelIndex == localSlot)
    }

    /// The public channel (index 0, all-zero secret) merges into the local public channel
    /// without relocating, because empty-secret channels match by index.
    @Test("Channel reconcile: public channel merges into local public channel without relocation")
    func channelSecretReconcile_PublicChannelMergesIntoLocalPublic() async throws {
        let radioID = UUID()

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let localPublic = ChannelDTO.testChannel(
            radioID: radioID,
            index: 0,
            name: "Public",
            lastMessageDate: nil
        )
        try await destStore.saveChannel(localPublic)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupPublic = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: 0,
            name: "Public",
            lastMessageDate: Date(timeIntervalSince1970: 1_700_001_100)
        )
        var msg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: 0,
            text: "Public broadcast",
            timestamp: 1_700_001_100,
            direction: .incoming,
            senderNodeName: "Mara"
        )
        msg.deduplicationKey = "ch-0-1700001100-Mara-FEEDFACE"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupPublic],
            messages: [msg]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 0)
        #expect(result.channelsSkipped == 1)
        #expect(result.messagesInserted == 1)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        #expect(channels.count == 1)
        #expect(channels.first?.index == 0)

        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.count == 1)
        #expect(messages.first?.channelIndex == 0)
    }

    /// Dedup-key preservation: re-importing the same relocated backup must skip the
    /// channel message on the second pass, proving the dedup key was rewritten to the
    /// relocated index consistently across both runs.
    @Test("Channel reconcile: re-importing a relocated channel skips the message the second time")
    func channelSecretReconcile_RemapPreservesDedupKeyAcrossReimport() async throws {
        let radioID = UUID()
        let secretBrno = Data(repeating: 0xB2, count: 16)
        let secretPraha = Data(repeating: 0xA2, count: 16)
        let collisionIndex: UInt8 = 3

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: collisionIndex, name: "#brno", secret: secretBrno)
        )

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupPraha = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: collisionIndex,
            name: "#praha",
            secret: secretPraha
        )
        var prahaMessage = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: collisionIndex,
            text: "Praha checkpoint",
            timestamp: 1_700_001_200,
            direction: .incoming,
            senderNodeName: "Jan"
        )
        prahaMessage.deduplicationKey = "ch-\(collisionIndex)-1700001200-Jan-12345678"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupPraha],
            messages: [prahaMessage]
        )

        let service = AppBackupService()
        let firstResult = try await service.importBackup(envelope: envelope, into: destStore)
        #expect(firstResult.messagesInserted == 1)

        let secondResult = try await service.importBackup(envelope: envelope, into: destStore)
        #expect(secondResult.messagesInserted == 0)
        #expect(secondResult.messagesSkipped == 1)

        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.count == 1)
    }

    /// Safety case: every local slot within the radio's channel capacity is occupied by a
    /// different secret, so the backup channel has nowhere to land. It must be dropped (not
    /// mis-associated), and its messages must be dropped with it rather than attaching to a
    /// foreign channel.
    @Test("Channel reconcile: backup channel with no free slot is dropped along with its messages")
    func channelSecretReconcile_NoFreeSlotDropsChannelAndMessages() async throws {
        let radioID = UUID()
        let maxChannels: UInt8 = 2
        let secretPraha = Data(repeating: 0xA4, count: 16)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        // Fill every slot in [0, maxChannels) with distinct-secret local channels.
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 0, name: "#a", secret: Data(repeating: 0x10, count: 16))
        )
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 1, name: "#b", secret: Data(repeating: 0x11, count: 16))
        )

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID).copy { $0.maxChannels = maxChannels }
        let backupPraha = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: 1,
            name: "#praha",
            secret: secretPraha
        )
        var prahaMessage = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: 1,
            text: "Should not survive",
            timestamp: 1_700_001_300,
            direction: .incoming,
            senderNodeName: "Jan"
        )
        prahaMessage.deduplicationKey = "ch-1-1700001300-Jan-AABBCCDD"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupPraha],
            messages: [prahaMessage]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        // #praha is dropped: not inserted, counted as dropped; its message is not inserted.
        #expect(result.channelsInserted == 0)
        #expect(result.channelsDropped == 1)
        #expect(result.messagesInserted == 0)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        #expect(channels.count == 2)
        #expect(channels.contains { $0.secret == secretPraha } == false)

        let messages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(messages.isEmpty)
    }

    @Test("Dropped channels and their messages are accounted as dropped, not already-here")
    func droppedChannelHistoryIsAccountedDistinctly() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let radioID = UUID()

        // Local radio is full to capacity (maxChannels = 2: slot 0 public + slot 1 used).
        let existing = ChannelDTO(
            id: UUID(), radioID: radioID, index: 1, name: "Local",
            secret: Data(repeating: 0x11, count: 32), isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0, notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        try await store.batchInsertChannels([existing], radioIDs: [radioID], maxChannelsByRadioID: [radioID: 2])

        // Backup channel at slot 1 with a different secret has no free slot -> dropped.
        let backupChannel = ChannelDTO(
            id: UUID(), radioID: radioID, index: 1, name: "Backup",
            secret: Data(repeating: 0x22, count: 32), isEnabled: true, lastMessageDate: nil,
            unreadCount: 0, unreadMentionCount: 0, notificationLevel: .all, isFavorite: false, floodScope: .inherit
        )
        let channelResult = try await store.batchInsertChannels(
            [backupChannel], radioIDs: [radioID], maxChannelsByRadioID: [radioID: 2]
        )
        #expect(channelResult.dropped == 1)
        #expect(channelResult.skipped == 0)
        #expect(channelResult.droppedChannelIndices[radioID]?.contains(1) == true)
    }

    @Test("Full import accounts a no-slot channel's messages as dropped and the manifest balances")
    func droppedChannelFullImportBalancesManifest() async throws {
        let radioID = UUID()
        let maxChannels: UInt8 = 2
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        // Fill every slot in [0, maxChannels) with distinct-secret local channels.
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 0, name: "#a", secret: Data(repeating: 0x10, count: 16))
        )
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 1, name: "#b", secret: Data(repeating: 0x11, count: 16))
        )

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID).copy { $0.maxChannels = maxChannels }
        // One backup channel that merges into an existing slot (slot 0, same empty-ish handling
        // avoided by using a distinct secret at slot 0) and one that has no free slot -> dropped.
        let droppedChannel = ChannelDTO.testChannel(
            id: UUID(), radioID: radioID, index: 1, name: "#dropped", secret: Data(repeating: 0xA4, count: 16)
        )
        var droppedMessage1 = MessageDTO.testChannelMessage(
            radioID: radioID, channelIndex: 1, text: "lost one",
            timestamp: 1_700_002_000, direction: .incoming, senderNodeName: "Jan"
        )
        droppedMessage1.deduplicationKey = "ch-1-1700002000-Jan-AABBCCDD"
        var droppedMessage2 = MessageDTO.testChannelMessage(
            radioID: radioID, channelIndex: 1, text: "lost two",
            timestamp: 1_700_002_100, direction: .incoming, senderNodeName: "Eva"
        )
        droppedMessage2.deduplicationKey = "ch-1-1700002100-Eva-AABBCCDE"

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [droppedChannel],
            messages: [droppedMessage1, droppedMessage2]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.counts[.channels]?.dropped == 1)
        #expect(result.counts[.messages]?.dropped == 2)

        // Message balance invariant: every declared message is inserted, merged, skipped, or dropped.
        let m = try #require(result.counts[.messages])
        #expect(envelope.manifest.messageCount == m.inserted + m.merged + m.skipped + m.dropped)
    }

    /// Slot 0 is reserved for the public channel, so a relocating non-public channel skips it
    /// even when free. Local `#brno` occupies slot 1; slots 0 and 2 are free. The colliding
    /// backup `#praha` must relocate to slot 2, never slot 0.
    @Test("Channel reconcile: relocation reserves slot 0 for the public channel")
    func channelSecretReconcile_RelocationReservesPublicSlotZero() async throws {
        let radioID = UUID()
        let secretBrno = Data(repeating: 0xB6, count: 16)
        let secretPraha = Data(repeating: 0xA6, count: 16)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        // Local channel occupies slot 1; slot 0 is intentionally left free (no public channel).
        try await destStore.saveChannel(
            ChannelDTO.testChannel(radioID: radioID, index: 1, name: "#brno", secret: secretBrno)
        )

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID).copy { $0.maxChannels = 3 }
        let backupPraha = ChannelDTO.testChannel(
            id: UUID(),
            radioID: radioID,
            index: 1,
            name: "#praha",
            secret: secretPraha
        )

        let envelope = AppBackupEnvelope.test(devices: [backupDevice], channels: [backupPraha])
        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 1)

        let channels = try await destStore.fetchAllChannels(radioID: radioID)
        let praha = try #require(channels.first { $0.secret == secretPraha })
        // Lowest free slot at or above 1 — slot 0 is skipped even though it is free.
        #expect(praha.index == 2)
    }

    // MARK: - Test 13: Fresh-store import — remote sessions start disconnected

    @Test("Import inserts remote sessions as disconnected while preserving backup metadata")
    func importRemoteSession_ResetsTransientConnectionState() async throws {
        let radioID = UUID()
        let publicKey = Data(repeating: 0xF3, count: 32)
        let importedDate = Date(timeIntervalSince1970: 1_700_000_200)

        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupSession = RemoteNodeSessionDTO.testSession(
            radioID: radioID,
            publicKey: publicKey,
            name: "Ops Room",
            role: .roomServer,
            isConnected: true,
            permissionLevel: .admin,
            lastConnectedDate: importedDate,
            unreadCount: 5,
            notificationLevel: .mentionsOnly,
            isFavorite: true,
            neighborCount: 4,
            lastSyncTimestamp: 88,
            lastMessageDate: importedDate
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            remoteNodeSessions: [backupSession]
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let service = AppBackupService()

        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.remoteNodeSessionsInserted == 1)
        #expect(result.remoteNodeSessionsSkipped == 0)

        let importedSession = try #require(await destStore.fetchRemoteNodeSession(id: backupSession.id))
        #expect(importedSession.isConnected == false)
        #expect(importedSession.permissionLevel == .admin)
        #expect(importedSession.lastConnectedDate == importedDate)
        #expect(importedSession.unreadCount == 5)
        #expect(importedSession.notificationLevel == .mentionsOnly)
        #expect(importedSession.isFavorite == true)
        #expect(importedSession.lastSyncTimestamp == 88)
        #expect(importedSession.lastMessageDate == importedDate)
    }

    // MARK: - Test 14: Room-message delivery metadata survives import

    @Test("Import preserves room-message delivery metadata")
    func importRoomMessage_PreservesDeliveryMetadata() async throws {
        let radioID = UUID()
        let createdAt = Date(timeIntervalSince1970: 1_700_000_275.25)
        let session = RemoteNodeSessionDTO.testSession(radioID: radioID)
        let roomMessage = RoomMessageDTO(
            id: UUID(),
            sessionID: session.id,
            authorKeyPrefix: Data([0xCA, 0xFE, 0xBA, 0xBE]),
            authorName: "Ops",
            text: "Retry me",
            timestamp: 1_700_000_275,
            createdAt: createdAt,
            isFromSelf: true,
            status: .failed,
            ackCode: 0xDEADBEEF,
            roundTripTime: 1_450,
            retryAttempt: 3,
            maxRetryAttempts: 7
        )

        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice(id: radioID, radioID: radioID)],
            roomMessages: [roomMessage],
            remoteNodeSessions: [session]
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let service = AppBackupService()

        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.roomMessagesInserted == 1)

        let importedMessage = try #require(await destStore.fetchRoomMessage(id: roomMessage.id))
        #expect(importedMessage.createdAt == createdAt)
        #expect(importedMessage.status == .failed)
        #expect(importedMessage.ackCode == 0xDEADBEEF)
        #expect(importedMessage.roundTripTime == 1_450)
        #expect(importedMessage.retryAttempt == 3)
        #expect(importedMessage.maxRetryAttempts == 7)
    }

    // MARK: - Test 15: Merge import — remote session metadata restored

    @Test("Import onto existing remote session restores backup metadata without clobbering live state")
    func importOntoExistingRemoteSession_RestoresBackupMetadata() async throws {
        let radioID = UUID()
        let publicKey = Data(repeating: 0xF4, count: 32)
        let localConnectedDate = Date(timeIntervalSince1970: 1_700_000_250)
        let importedDate = Date(timeIntervalSince1970: 1_700_000_300)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingSession = RemoteNodeSessionDTO.testSession(
            radioID: radioID,
            publicKey: publicKey,
            name: "Ops Room",
            role: .roomServer,
            isConnected: true,
            permissionLevel: .admin,
            lastConnectedDate: localConnectedDate,
            unreadCount: 0,
            notificationLevel: .all,
            isFavorite: false,
            neighborCount: 2,
            lastSyncTimestamp: 123,
            lastMessageDate: nil
        )
        try await destStore.saveRemoteNodeSessionDTO(existingSession)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupSession = RemoteNodeSessionDTO.testSession(
            id: UUID(),
            radioID: radioID,
            publicKey: publicKey,
            name: "Ops Room",
            role: .roomServer,
            isConnected: false,
            permissionLevel: .guest,
            lastConnectedDate: Date(timeIntervalSince1970: 1_700_000_225),
            unreadCount: 9,
            notificationLevel: .mentionsOnly,
            isFavorite: true,
            neighborCount: 7,
            lastSyncTimestamp: 8,
            lastMessageDate: importedDate
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            remoteNodeSessions: [backupSession]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.remoteNodeSessionsInserted == 0)
        #expect(result.remoteNodeSessionsSkipped == 1)
        #expect(result.remoteNodeSessionsMerged == 1)
        #expect(result.hasRestoredChanges)

        let mergedSession = try #require(await destStore.fetchRemoteNodeSession(id: existingSession.id))
        #expect(mergedSession.isConnected == true)
        #expect(mergedSession.permissionLevel == .admin)
        #expect(mergedSession.lastConnectedDate == localConnectedDate)
        #expect(mergedSession.unreadCount == 9)
        #expect(mergedSession.notificationLevel == .mentionsOnly)
        #expect(mergedSession.isFavorite == true)
        #expect(mergedSession.lastSyncTimestamp == 123)
        #expect(mergedSession.lastMessageDate == importedDate)
    }

    // MARK: - Test 16: Merge import — same-second node snapshots preserved

    @Test("Import preserves distinct node snapshots recorded within the same second")
    func importNodeSnapshots_PreservesSubsecondHistory() async throws {
        let radioID = UUID()
        let nodePublicKey = Data(repeating: 0xF5, count: 32)
        let baseTimestamp = Date(timeIntervalSince1970: 1_700_000_400)
        let existingTimestamp = baseTimestamp.addingTimeInterval(0.100)
        let importedTimestamp = baseTimestamp.addingTimeInterval(0.900)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        _ = try await destStore.saveNodeStatusSnapshot(
            timestamp: existingTimestamp,
            nodePublicKey: nodePublicKey,
            batteryMillivolts: 3800,
            lastSNR: 8.5,
            lastRSSI: -90,
            noiseFloor: -112,
            uptimeSeconds: 60,
            rxAirtimeSeconds: nil,
            packetsSent: nil,
            packetsReceived: nil,
            receiveErrors: nil
        )

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let existingSnapshot = NodeStatusSnapshotDTO.testSnapshot(
            timestamp: existingTimestamp,
            nodePublicKey: nodePublicKey,
            batteryMillivolts: 3800,
            lastSNR: 8.5,
            lastRSSI: -90,
            noiseFloor: -112,
            uptimeSeconds: 60
        )
        let importedSnapshot = NodeStatusSnapshotDTO.testSnapshot(
            timestamp: importedTimestamp,
            nodePublicKey: nodePublicKey,
            batteryMillivolts: nil,
            lastSNR: nil,
            lastRSSI: nil,
            noiseFloor: nil,
            uptimeSeconds: nil,
            telemetryEntries: [TelemetrySnapshotEntry(channel: 1, type: "temperature", value: 21.5)]
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            nodeStatusSnapshots: [existingSnapshot, importedSnapshot]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.nodeStatusSnapshotsInserted == 1)
        #expect(result.nodeStatusSnapshotsSkipped == 1)

        let snapshots = try await destStore.fetchNodeStatusSnapshots(nodePublicKey: nodePublicKey, since: nil)
        #expect(snapshots.count == 2)
        #expect(snapshots.map(\.timestamp) == [existingTimestamp, importedTimestamp])
        #expect(snapshots.last?.telemetryEntries?.count == 1)
    }

    // MARK: - Test 17: Failed import cleanup

    @Test("Successful import restores autosave on the destination store")
    func successfulImport_RestoresAutosave() async throws {
        let radioID = UUID()
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        await destStore.setAutosaveEnabledForTesting(true)

        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice(id: radioID, radioID: radioID)]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(
            envelope: envelope,
            into: destStore
        )

        #expect(result.devicesInserted == 1)
        #expect(await destStore.autosaveEnabledForTesting())
        #expect(!(await destStore.hasPendingChangesForTesting()))
        #expect(try await destStore.fetchAllDevices().count == 1)
    }

    @Test("Failed import rolls back reconcile-phase mutations to pre-existing rows")
    func failedImport_RollsBackReconcileMutationsOnExistingRows() async throws {
        let radioID = UUID()
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        await destStore.setAutosaveEnabledForTesting(true)

        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xC2, count: 32),
            name: "Pre-existing"
        )
        try await destStore.saveContact(contact)
        // Pre-existing contacts always have lastMessageDate = nil out of the gate.
        let baselineContacts = try await destStore.fetchAllContacts(radioID: radioID)
        let preImportLastMessageDate = baselineContacts.first?.lastMessageDate

        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupMessage = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Reconcile target",
            timestamp: 99999
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [contact],
            messages: [backupMessage]
        )

        await destStore.setBackupImportFaultInjection { throw InjectedImportFailure.simulated }

        await #expect(throws: InjectedImportFailure.simulated) {
            try await destStore.importBackupDatabase(envelope)
        }

        let postImportContacts = try await destStore.fetchAllContacts(radioID: radioID)
        #expect(postImportContacts.count == 1)
        #expect(postImportContacts.first?.lastMessageDate == preImportLastMessageDate)
        #expect(try await destStore.fetchAllMessages().isEmpty)
        #expect(!(await destStore.hasPendingChangesForTesting()))
    }

    @Test("Failed import clears pending data and restores autosave")
    func failedImport_RollsBackPendingChangesAndRestoresAutosave() async throws {
        let radioID = UUID()
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        await destStore.setAutosaveEnabledForTesting(true)

        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice(id: radioID, radioID: radioID)]
        )

        await destStore.setBackupImportFaultInjection { throw InjectedImportFailure.simulated }

        await #expect(throws: InjectedImportFailure.simulated) {
            try await destStore.importBackupDatabase(envelope)
        }

        #expect(await destStore.autosaveEnabledForTesting())
        #expect(!(await destStore.hasPendingChangesForTesting()))
        #expect(try await destStore.fetchAllDevices().isEmpty)

        try await destStore.saveContact(
            ContactDTO.testContact(
                radioID: radioID,
                publicKey: Data(repeating: 0xC1, count: 32),
                name: "Recovered Contact"
            )
        )

        let contacts = try await destStore.fetchAllContacts(radioID: radioID)
        #expect(contacts.count == 1)
        #expect(contacts.first?.name == "Recovered Contact")
    }

    @Test("Disk-backed container has zero partial state after faulted import is abandoned")
    func faultedImport_LeavesNoPartialStateOnDiskAfterReopen() async throws {
        let storeURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("backup-crash-\(UUID().uuidString).store")
        defer {
            let fm = FileManager.default
            try? fm.removeItem(at: storeURL)
            try? fm.removeItem(at: storeURL.appendingPathExtension("shm"))
            try? fm.removeItem(at: storeURL.appendingPathExtension("wal"))
        }

        let radioID = UUID()
        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice(id: radioID, radioID: radioID)],
            contacts: [
                ContactDTO.testContact(
                    radioID: radioID,
                    publicKey: Data(repeating: 0x42, count: 32),
                    name: "Should not survive"
                )
            ]
        )

        // Throw before save() to exercise the error path. With autosaveEnabled = false
        // and save() never reached, nothing was flushed to SQLite — the defer's
        // rollback() just clears the in-memory context. This is the error path, not a
        // crash path: Swift's defer runs on throw, so a true bypassed-defer scenario
        // would require a child process that exits mid-import.
        do {
            let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
            let container = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
            let store = PersistenceStore(modelContainer: container)
            await store.setBackupImportFaultInjection { throw InjectedImportFailure.simulated }
            await #expect(throws: InjectedImportFailure.simulated) {
                try await store.importBackupDatabase(envelope)
            }
        }

        let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
        let reopened = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
        let freshStore = PersistenceStore(modelContainer: reopened)

        #expect(try await freshStore.fetchAllDevices().isEmpty)
        #expect(try await freshStore.fetchAllContacts(radioID: radioID).isEmpty)
    }

    @Test("Concurrent live-store writer during import preserves both datasets")
    func concurrentLiveWrite_DuringImport_PreservesBoth() async throws {
        // Two @ModelActor instances on the same ModelContainer simulate a radio
        // connecting mid-import: the backup flow resolved a standalone
        // PersistenceStore at T=0, then ConnectionManager stood up a second
        // PersistenceStore on the same container to service the live link.
        let sharedContainer = try PersistenceStore.createContainer(inMemory: true)
        let backupStore = PersistenceStore(modelContainer: sharedContainer)
        let liveStore = PersistenceStore(modelContainer: sharedContainer)

        let backupRadioID = UUID()
        let liveRadioID = UUID()
        let backupDevicePublicKey = Data(repeating: 0xB0, count: 32)
        let liveDevicePublicKey = Data(repeating: 0xC0, count: 32)

        let backupContact = ContactDTO.testContact(
            radioID: backupRadioID,
            publicKey: Data(repeating: 0xB1, count: 32),
            name: "From backup"
        )
        let envelope = AppBackupEnvelope.test(
            devices: [
                DeviceDTO.testDevice(
                    id: backupRadioID,
                    radioID: backupRadioID,
                    publicKey: backupDevicePublicKey
                )
            ],
            contacts: [backupContact]
        )

        try await liveStore.saveDevice(
            DeviceDTO.testDevice(
                id: liveRadioID,
                radioID: liveRadioID,
                publicKey: liveDevicePublicKey
            )
        )
        let liveContact = ContactDTO.testContact(
            radioID: liveRadioID,
            publicKey: Data(repeating: 0xC1, count: 32),
            name: "From connect"
        )

        async let importResult: ImportResult = backupStore.importBackupDatabase(envelope)
        async let liveWrite: Void = liveStore.saveContact(liveContact)

        _ = try await importResult
        try await liveWrite

        // A third actor guarantees we read through the persistent store rather than
        // either writer's context cache — `fetchAllContacts` on the writers can miss
        // the other actor's commits until the cache invalidates.
        let verifier = PersistenceStore(modelContainer: sharedContainer)
        let liveContacts = try await verifier.fetchAllContacts(radioID: liveRadioID)
        #expect(liveContacts.contains { $0.publicKey == liveContact.publicKey })
        let backupContacts = try await verifier.fetchAllContacts(radioID: backupRadioID)
        #expect(backupContacts.contains { $0.publicKey == backupContact.publicKey })

        let allDevices = try await verifier.fetchAllDevices()
        #expect(allDevices.count == 2)
    }

    // MARK: - Test 18: Export assigns content-based keys to nil-keyed messages

    @Test("Export assigns content-based dedup keys to incoming messages with nil deduplicationKey")
    func exportAssignsContentBasedKeysToNilKeyedMessages() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xF6, count: 32),
            name: "Dan"
        )
        try await sourceStore.saveContact(contact)

        // Save an incoming DM with nil deduplicationKey (simulates pre-migration message)
        let dm = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Pre-migration DM",
            timestamp: 12345,
            direction: .incoming
        )
        try await sourceStore.saveMessage(dm)

        // Save an incoming channel message with nil deduplicationKey
        let chMsg = MessageDTO.testChannelMessage(
            radioID: radioID,
            channelIndex: 2,
            text: "Pre-migration channel msg",
            timestamp: 67890,
            direction: .incoming,
            senderNodeName: "Node1"
        )
        try await sourceStore.saveMessage(chMsg)

        // Export
        let service = AppBackupService()
        let result = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: result.data)

        // Verify exported messages have content-based keys, not backup-<UUID>
        let exportedDM = try #require(envelope.messages.first { $0.contactID == contact.id })
        #expect(exportedDM.deduplicationKey?.hasPrefix("dm-") == true)
        #expect(exportedDM.deduplicationKey?.hasPrefix("backup-") != true)

        let exportedCh = try #require(envelope.messages.first { $0.channelIndex == 2 })
        #expect(exportedCh.deduplicationKey?.hasPrefix("ch-") == true)
        #expect(exportedCh.deduplicationKey?.hasPrefix("backup-") != true)
    }

    // MARK: - Test 19: Export preserves existing content-based keys

    @Test("Export preserves existing content-based dedup keys unchanged")
    func exportPreservesExistingContentBasedKeys() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let contact = ContactDTO.testContact(radioID: radioID)
        try await sourceStore.saveContact(contact)

        let existingKey = "dm-\(contact.id.uuidString)-99999-AABBCCDD"
        var msg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Already has key"
        )
        msg.deduplicationKey = existingKey
        try await sourceStore.saveMessage(msg)

        let service = AppBackupService()
        let result = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: result.data)

        let exported = try #require(envelope.messages.first)
        #expect(exported.deduplicationKey == existingKey)
    }

    // MARK: - Test 23: Duplicates within a single backup don't orphan their children

    /// Two incoming messages in the same envelope that share a content key — the
    /// second is skipped (same wire packet seen twice), and its repeats/reactions
    /// must be remapped to the first (winning) UUID.
    @Test("Duplicate messages within one envelope: children of the skipped duplicate link to the inserted message")
    func importDuplicateMessagesInEnvelope_ChildrenLinkToInsertedDuplicate() async throws {
        let radioID = UUID()
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)

        var firstMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Duplicate in envelope",
            timestamp: 1_700_000_500,
            direction: .incoming
        )
        firstMsg.deduplicationKey = nil

        var secondMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Duplicate in envelope",
            timestamp: 1_700_000_500,
            direction: .incoming
        )
        secondMsg.deduplicationKey = nil
        #expect(firstMsg.id != secondMsg.id)

        // Children reference the second (to-be-skipped) message's UUID.
        let repeatForSecond = MessageRepeatDTO.testRepeat(
            messageID: secondMsg.id,
            pathNodes: Data([0x11])
        )
        let reactionForSecond = ReactionDTO.testReaction(
            messageID: secondMsg.id,
            radioID: radioID,
            emoji: "🌶️",
            senderName: "Dup"
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [contact],
            messages: [firstMsg, secondMsg],
            messageRepeats: [repeatForSecond],
            reactions: [reactionForSecond]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.messagesInserted == 1)
        #expect(result.messagesSkipped == 1)
        #expect(result.messageRepeatsInserted == 1)
        #expect(result.reactionsInserted == 1)

        // Children must attach to the first (winning) UUID; the second UUID must have no rows.
        let repeatsUnderFirst = try await destStore.fetchMessageRepeats(messageID: firstMsg.id)
        #expect(repeatsUnderFirst.count == 1)
        let orphanedRepeats = try await destStore.fetchMessageRepeats(messageID: secondMsg.id)
        #expect(orphanedRepeats.isEmpty)

        let winner = try #require(await destStore.fetchMessage(id: firstMsg.id))
        #expect(winner.heardRepeats == 1)
        #expect(winner.reactionSummary == "🌶️:1")
    }

    // MARK: - Test 24: Cancellation after DB commit reports success, not cancelled

    /// A task cancelled between the DB commit and the rest of `importBackup`
    /// must not throw CancellationError. The DB write has already landed, so
    /// a throw here would report cancellation while the database actually
    /// persisted.
    @Test("Task cancellation after DB commit does not throw and returns a successful result")
    func cancellationAfterCommit_ImportReturnsSuccess() async throws {
        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice()]
        )

        // Post-commit hook cancels the task that's running the import (the
        // child Task below), mirroring the race where the user taps Cancel
        // mid-save. Running the import in a child Task keeps the outer test's
        // cancellation state clean so post-import fetches can run.
        await destStore.setBackupImportPostCommitHook {
            withUnsafeCurrentTask { $0?.cancel() }
        }

        let service = AppBackupService()
        let importTask = Task<ImportResult, Error> {
            try await service.importBackup(envelope: envelope, into: destStore)
        }

        let result: ImportResult
        do {
            result = try await importTask.value
        } catch {
            Issue.record("Import after post-commit cancellation should succeed, got: \(error)")
            return
        }

        #expect(result.devicesInserted == 1)
        #expect(try await destStore.fetchAllDevices().count == 1)
    }

    // MARK: - Test 25: userDefaultsRestored reflects actual writes

    /// When every UserDefaults key carried in the backup is already set
    /// locally, `restore(to:)` writes nothing. The import result must then
    /// report `userDefaultsRestored == false`, otherwise a second no-op
    /// import would claim `hasRestoredChanges` with nothing actually changed.
    @Test("Import reports userDefaultsRestored=false when no new keys were written")
    func importWithAllDefaultsAlreadySet_ReportsRestoredFalse() async throws {
        let key = "hasCompletedOnboarding"
        let defaults = UserDefaults.standard
        let originalValue = defaults.object(forKey: key)
        defaults.set(true, forKey: key)
        defer {
            if let value = originalValue {
                defaults.set(value, forKey: key)
            } else {
                defaults.removeObject(forKey: key)
            }
        }

        var backupDefaults = BackupUserDefaults()
        backupDefaults.hasCompletedOnboarding = true

        let envelope = AppBackupEnvelope.test(
            devices: [DeviceDTO.testDevice()],
            userDefaults: backupDefaults
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(!result.userDefaultsRestored)
    }

    // MARK: - Test 26: Fresh-store import — contact unread counts preserved

    @Test("Fresh-insert import preserves contact unread counts from backup")
    func importContact_FreshInsertPreservesUnreadCount() async throws {
        let radioID = UUID()
        let publicKey = Data(repeating: 0xE5, count: 32)
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupContact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: publicKey,
            unreadCount: 7,
            unreadMentionCount: 2
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [backupContact]
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let service = AppBackupService()

        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.contactsInserted == 1)
        #expect(result.contactsSkipped == 0)

        let importedContact = try #require(
            await destStore.fetchContact(radioID: radioID, publicKey: publicKey)
        )
        #expect(importedContact.unreadCount == 7)
        #expect(importedContact.unreadMentionCount == 2)
    }

    // MARK: - Test 27: Fresh-store import — channel unread counts preserved

    @Test("Fresh-insert import preserves channel unread counts from backup")
    func importChannel_FreshInsertPreservesUnreadCount() async throws {
        let radioID = UUID()
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 4,
            unreadCount: 3,
            unreadMentionCount: 1
        )

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            channels: [backupChannel]
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let service = AppBackupService()

        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 1)
        #expect(result.channelsSkipped == 0)

        let importedChannel = try #require(await destStore.fetchChannel(radioID: radioID, index: 4))
        #expect(importedChannel.unreadCount == 3)
        #expect(importedChannel.unreadMentionCount == 1)
    }

    // MARK: - Test 28: Merge import — contact unread counts max with local

    @Test("Merge import keeps local contact unread counts when they exceed backup values")
    func importOntoExistingContact_UnreadMergesUsingMax() async throws {
        let radioID = UUID()
        let publicKey = Data(repeating: 0xE6, count: 32)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingContact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: publicKey,
            unreadCount: 9,
            unreadMentionCount: 4
        )
        try await destStore.saveContact(existingContact)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupContact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: publicKey,
            unreadCount: 2,
            unreadMentionCount: 1
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            contacts: [backupContact]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.contactsInserted == 0)
        #expect(result.contactsSkipped == 1)

        let mergedContact = try #require(
            await destStore.fetchContact(radioID: radioID, publicKey: publicKey)
        )
        #expect(mergedContact.unreadCount == 9)
        #expect(mergedContact.unreadMentionCount == 4)
    }

    // MARK: - Test 29: Merge import — channel unread counts max with local

    @Test("Merge import keeps local channel unread counts when they exceed backup values")
    func importOntoExistingChannel_UnreadMergesUsingMax() async throws {
        let radioID = UUID()

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 5,
            unreadCount: 6,
            unreadMentionCount: 3
        )
        try await destStore.saveChannel(existingChannel)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupChannel = ChannelDTO.testChannel(
            radioID: radioID,
            index: 5,
            unreadCount: 1,
            unreadMentionCount: 0
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            channels: [backupChannel]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.channelsInserted == 0)
        #expect(result.channelsSkipped == 1)

        let mergedChannel = try #require(await destStore.fetchChannel(radioID: radioID, index: 5))
        #expect(mergedChannel.unreadCount == 6)
        #expect(mergedChannel.unreadMentionCount == 3)
    }

    // MARK: - Test 30: Merge import — remote session unread count max with local

    @Test("Merge import keeps local remote-session unread count when it exceeds backup value")
    func importOntoExistingRemoteSession_UnreadMergesUsingMax() async throws {
        let radioID = UUID()
        let publicKey = Data(repeating: 0xE7, count: 32)

        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let existingSession = RemoteNodeSessionDTO.testSession(
            radioID: radioID,
            publicKey: publicKey,
            unreadCount: 8
        )
        try await destStore.saveRemoteNodeSessionDTO(existingSession)

        let backupDevice = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        let backupSession = RemoteNodeSessionDTO.testSession(
            id: UUID(),
            radioID: radioID,
            publicKey: publicKey,
            unreadCount: 2
        )

        let envelope = AppBackupEnvelope.test(
            devices: [backupDevice],
            remoteNodeSessions: [backupSession]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.remoteNodeSessionsInserted == 0)
        #expect(result.remoteNodeSessionsSkipped == 1)

        let mergedSession = try #require(await destStore.fetchRemoteNodeSession(id: existingSession.id))
        #expect(mergedSession.unreadCount == 8)
    }

    // MARK: - Outgoing duplicates must not collapse

    /// A user who double-taps send (or retries within the same UInt32 second) ends up
    /// with two outgoing rows that share recipient + text + timestamp. They are two
    /// distinct intentional actions, and backup/restore must keep both.
    @Test("Outgoing messages with identical recipient/text/timestamp survive round-trip without deduplication")
    func outgoingDuplicates_PreservedAcrossRoundTrip() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)
        try await sourceStore.saveContact(contact)

        let sharedTimestamp: UInt32 = 1_700_001_234

        let firstMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "ok",
            timestamp: sharedTimestamp,
            direction: .outgoing
        )
        let secondMsg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "ok",
            timestamp: sharedTimestamp,
            direction: .outgoing
        )
        #expect(firstMsg.id != secondMsg.id)
        try await sourceStore.saveMessage(firstMsg)
        try await sourceStore.saveMessage(secondMsg)

        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.messagesInserted == 2)
        #expect(result.messagesSkipped == 0)

        let destMessages = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(destMessages.count == 2)
        #expect(Set(destMessages.map(\.id)) == Set([firstMsg.id, secondMsg.id]))
    }

    // MARK: - Repeat rows through the same route must not collapse

    /// `MessageRepeat` distinguishes hearings by `id`/`rxLogEntryID`. Two observations
    /// of the same message through the same path (e.g. a flood echo) are genuinely
    /// distinct and must both survive round-trip so `heardRepeats` stays accurate.
    @Test("Repeats with identical path but distinct ids survive round-trip and heardRepeats is recomputed")
    func repeatsOnSamePath_PreservedAcrossRoundTrip() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)
        try await sourceStore.saveContact(contact)

        var msg = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "echoed",
            direction: .outgoing
        )
        msg.deduplicationKey = nil
        try await sourceStore.saveMessage(msg)

        let sharedPath = Data([0x42])
        let firstRepeat = MessageRepeatDTO.testRepeat(
            messageID: msg.id,
            pathNodes: sharedPath,
            rxLogEntryID: UUID()
        )
        let secondRepeat = MessageRepeatDTO.testRepeat(
            messageID: msg.id,
            pathNodes: sharedPath,
            rxLogEntryID: UUID()
        )
        #expect(firstRepeat.id != secondRepeat.id)
        try await sourceStore.saveMessageRepeat(firstRepeat)
        try await sourceStore.saveMessageRepeat(secondRepeat)

        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.messageRepeatsInserted == 2)
        #expect(result.messageRepeatsSkipped == 0)

        let restoredRepeats = try await destStore.fetchMessageRepeats(messageID: msg.id)
        #expect(restoredRepeats.count == 2)
        #expect(Set(restoredRepeats.map(\.id)) == Set([firstRepeat.id, secondRepeat.id]))

        let restoredMsg = try #require(await destStore.fetchMessage(id: msg.id))
        #expect(restoredMsg.heardRepeats == 2)
    }

    // MARK: - Reply chain remap

    /// When a backup's replied-to parent already exists locally (content-keyed merge),
    /// the reply's replyToID must be rewritten onto the local parent UUID — otherwise
    /// reply navigation dangles on the pre-merge backup UUID.
    @Test("Reply remaps onto local parent when parent is merged during import")
    func replyRemapOntoMergedParent() async throws {
        let radioID = UUID()
        let destStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(radioID: radioID)
        try await destStore.saveContact(contact)

        var existingParent = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Parent",
            direction: .incoming
        )
        existingParent.deduplicationKey = "reply-remap-parent"
        try await destStore.saveMessage(existingParent)

        // Backup contains the same parent (will be skipped/merged) and a reply
        // whose replyToID points at the backup-side parent UUID.
        let device = DeviceDTO.testDevice(id: radioID, radioID: radioID)
        var backupParent = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Parent",
            direction: .incoming
        )
        backupParent.deduplicationKey = "reply-remap-parent"
        #expect(backupParent.id != existingParent.id)

        var reply = MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contact.id,
            text: "Reply",
            direction: .incoming,
            replyToID: backupParent.id
        )
        reply.deduplicationKey = "reply-remap-reply"

        let envelope = AppBackupEnvelope.test(
            devices: [device],
            contacts: [contact],
            messages: [backupParent, reply]
        )

        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)
        #expect(result.messagesSkipped == 1)
        #expect(result.messagesInserted == 1)

        let destMessages = try await destStore.fetchAllMessages(radioID: radioID)
        let restoredReply = try #require(destMessages.first { $0.id == reply.id })
        #expect(restoredReply.replyToID == existingParent.id)
    }

    // MARK: - Cross-radio channel message preservation

    /// Two companion radios often receive the same over-the-air channel packet. The stored
    /// live-sync deduplicationKey is radio-agnostic by construction, so the backup-reconciliation
    /// key must be scoped by radioID. Otherwise the import path collapses both rows into one
    /// and the second companion's channel view renders blank after restore — the same failure
    /// mode fixed in live sync (issue #288) leaking back through backup/restore.
    @Test("Backup restores the same channel packet under each companion radio that received it")
    func crossRadioChannelMessagesBothSurviveImport() async throws {
        let radioA = UUID()
        let radioB = UUID()
        let deviceA = DeviceDTO.testDevice(
            id: radioA,
            radioID: radioA,
            publicKey: Data(repeating: 0xAA, count: 32),
            nodeName: "CompanionA"
        )
        let deviceB = DeviceDTO.testDevice(
            id: radioB,
            radioID: radioB,
            publicKey: Data(repeating: 0xBB, count: 32),
            nodeName: "CompanionB"
        )
        let channelA = ChannelDTO.testChannel(radioID: radioA, index: 0, name: "General")
        let channelB = ChannelDTO.testChannel(radioID: radioB, index: 0, name: "General")

        // Same wire packet — identical content-based dedup key — seen by both companions.
        let sharedDedupKey = "ch-0-1700000000-Alice-A1B2C3D4"
        var msgFromA = MessageDTO.testChannelMessage(
            radioID: radioA,
            channelIndex: 0,
            text: "aaaaa",
            timestamp: 1_700_000_000,
            direction: .incoming,
            status: .delivered,
            senderNodeName: "Alice"
        )
        msgFromA.deduplicationKey = sharedDedupKey

        var msgFromB = MessageDTO.testChannelMessage(
            radioID: radioB,
            channelIndex: 0,
            text: "aaaaa",
            timestamp: 1_700_000_000,
            direction: .incoming,
            status: .delivered,
            senderNodeName: "Alice"
        )
        msgFromB.deduplicationKey = sharedDedupKey

        let envelope = AppBackupEnvelope.test(
            devices: [deviceA, deviceB],
            channels: [channelA, channelB],
            messages: [msgFromA, msgFromB]
        )

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        let service = AppBackupService()
        let result = try await service.importBackup(envelope: envelope, into: destStore)

        #expect(result.messagesInserted == 2,
                "Both companions' copies of the same channel packet must be restored — otherwise the second companion's channel view goes blank post-restore")
        #expect(result.messagesSkipped == 0)

        let messagesForA = try await destStore.fetchAllMessages(radioID: radioA)
        let messagesForB = try await destStore.fetchAllMessages(radioID: radioB)
        #expect(messagesForA.count == 1)
        #expect(messagesForB.count == 1)
        #expect(messagesForA.first?.text == "aaaaa")
        #expect(messagesForB.first?.text == "aaaaa")
    }

    // MARK: - regionSelection backup contract

    @Test("regionSelection round-trips through encode/decode")
    func regionSelectionRoundTrips() throws {
        var prefs = BackupUserDefaults()
        prefs.regionSelection = RegionSelection(
            countryCode: "US",
            administrativeAreaCode: "US-CA",
            countyKey: "los angeles",
            source: .location
        )
        let data = try JSONEncoder().encode(prefs)
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: data)
        #expect(decoded.regionSelection == prefs.regionSelection)
    }

    @Test("Legacy envelope without regionSelection decodes as nil")
    func legacyEnvelopeDecodesRegionAsNil() throws {
        let legacyJSON = """
        {
            "hasCompletedOnboarding": true,
            "mapStyleSelection": "topo"
        }
        """.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: legacyJSON)
        #expect(decoded.regionSelection == nil)
        #expect(decoded.hasCompletedOnboarding == true)
    }

    @Test("restore writes regionSelection only when local key is missing")
    func restoreRespectsExistingValue() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        let local = RegionSelection(countryCode: "DE", source: .manual)
        defaults.set(try JSONEncoder().encode(local), forKey: BackupUserDefaults.regionSelectionKey)

        var prefs = BackupUserDefaults()
        prefs.regionSelection = RegionSelection(countryCode: "US", source: .location)
        let setKeys = prefs.restore(to: defaults)
        #expect(!setKeys.contains(BackupUserDefaults.regionSelectionKey))

        let stillThere = try JSONDecoder().decode(
            RegionSelection.self,
            from: defaults.data(forKey: BackupUserDefaults.regionSelectionKey)!
        )
        #expect(stillThere == local)
    }

    @Test("restore writes regionSelection when local is missing (fresh install)")
    func restoreWritesWhenMissing() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        var prefs = BackupUserDefaults()
        prefs.regionSelection = RegionSelection(countryCode: "US", source: .location)
        let setKeys = prefs.restore(to: defaults)

        #expect(setKeys.contains(BackupUserDefaults.regionSelectionKey))
        let restored = try JSONDecoder().decode(
            RegionSelection.self,
            from: defaults.data(forKey: BackupUserDefaults.regionSelectionKey)!
        )
        #expect(restored == prefs.regionSelection)
    }

    // MARK: - Message.regionScope round-trip

    @Test("Message.regionScope round-trips through full export/import")
    func messageRegionScopeRoundTrip() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Data(repeating: 0xAB, count: 32),
            name: "Alice"
        )
        try await sourceStore.saveContact(contact)

        var msg = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, text: "Greetings")
        msg.regionScope = "Germany"
        msg.deduplicationKey = "region-scope-roundtrip-\(UUID())"
        try await sourceStore.saveMessage(msg)

        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        _ = try await service.importBackup(envelope: envelope, into: destStore)

        let restored = try await destStore.fetchAllMessages(radioID: radioID)
        #expect(restored.count == 1)
        #expect(restored.first?.regionScope == "Germany")
    }

    @Test("MessageDTO Codable: regionScope set decodes round-trip")
    func messageDTORegionScopeCodableRoundTrip() throws {
        var dto = MessageDTO.testDirectMessage(radioID: UUID(), contactID: UUID(), text: "Test")
        dto.regionScope = "Bavaria"

        let encoded = try JSONEncoder().encode(dto)
        let decoded = try JSONDecoder().decode(MessageDTO.self, from: encoded)
        #expect(decoded.regionScope == "Bavaria")
    }

    @Test("Legacy MessageDTO envelope without regionScope decodes as nil")
    func messageDTOLegacyEnvelopeMissingRegionScope() throws {
        let baseDTO = MessageDTO.testDirectMessage(radioID: UUID(), contactID: UUID(), text: "Legacy")
        let encoded = try JSONEncoder().encode(baseDTO)
        var json = try #require(try JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        json.removeValue(forKey: "regionScope")

        let stripped = try JSONSerialization.data(withJSONObject: json)
        let decoded = try JSONDecoder().decode(MessageDTO.self, from: stripped)
        #expect(decoded.regionScope == nil)
    }

    @Test("Message(dto:) forwards regionScope verbatim through DTO to model")
    func messageInitFromDTOForwardsRegionScope() {
        var dto = MessageDTO.testDirectMessage(radioID: UUID(), contactID: UUID(), text: "Forward")
        dto.regionScope = "USA"
        let model = Message(dto: dto)
        #expect(model.regionScope == "USA")

        var nilDTO = MessageDTO.testDirectMessage(radioID: UUID(), contactID: UUID(), text: "NilCase")
        nilDTO.regionScope = nil
        let nilModel = Message(dto: nilDTO)
        #expect(nilModel.regionScope == nil)
    }

    // MARK: - MessageDTO.sortDate round-trip

    @Test("MessageDTO Codable: sortDate distinct from createdAt round-trips")
    func messageDTOSortDateCodableRoundTrip() throws {
        let createdAt = Date(timeIntervalSince1970: 1_700_000_000)
        let sortDate = Date(timeIntervalSince1970: 1_600_000_000)
        var dto = MessageDTO.testDirectMessage(
            radioID: UUID(),
            contactID: UUID(),
            text: "Test",
            createdAt: createdAt
        )
        dto.sortDate = sortDate

        let encoded = try JSONEncoder().encode(dto)
        let decoded = try JSONDecoder().decode(MessageDTO.self, from: encoded)
        #expect(decoded.sortDate == sortDate)
        #expect(decoded.sortDate != decoded.createdAt)
    }

    @Test("Legacy MessageDTO envelope without sortDate falls back to createdAt")
    func messageDTOLegacyEnvelopeMissingSortDate() throws {
        let baseDTO = MessageDTO.testDirectMessage(
            radioID: UUID(),
            contactID: UUID(),
            text: "Legacy",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let encoded = try JSONEncoder().encode(baseDTO)
        var json = try #require(try JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        json.removeValue(forKey: "sortDate")

        let stripped = try JSONSerialization.data(withJSONObject: json)
        let decoded = try JSONDecoder().decode(MessageDTO.self, from: stripped)
        #expect(decoded.sortDate == decoded.createdAt)
    }

    @Test("Message(dto:) forwards sortDate verbatim through DTO to model")
    func messageInitFromDTOForwardsSortDate() {
        let createdAt = Date(timeIntervalSince1970: 1_700_000_000)
        let sortDate = Date(timeIntervalSince1970: 1_600_000_000)
        var dto = MessageDTO.testDirectMessage(
            radioID: UUID(),
            contactID: UUID(),
            text: "Forward",
            createdAt: createdAt
        )
        dto.sortDate = sortDate
        let model = Message(dto: dto)
        #expect(model.sortDate == sortDate)
        #expect(model.sortDate != model.createdAt)
    }

    @Test("Fully-populated MessageDTO survives encode/decode with every field distinct")
    func messageDTOFullyPopulatedCodableRoundTrip() throws {
        let dto = MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: 7,
            text: "Every field set",
            timestamp: 1_700_000_001,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            direction: .incoming,
            status: .delivered,
            textType: .signedPlain,
            ackCode: 0xDEAD_BEEF,
            pathLength: 5,
            snr: 12.5,
            pathNodes: Data([0x01, 0x02, 0x03]),
            senderKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
            senderNodeName: "Bob",
            isRead: true,
            replyToID: UUID(),
            roundTripTime: 432,
            heardRepeats: 3,
            sendCount: 2,
            retryAttempt: 1,
            maxRetryAttempts: 4,
            deduplicationKey: "dedup-key",
            linkPreviewURL: "https://example.com",
            linkPreviewTitle: "Example",
            linkPreviewImageData: Data([0x10, 0x20]),
            linkPreviewIconData: Data([0x30, 0x40]),
            linkPreviewFetched: true,
            containsSelfMention: true,
            mentionSeen: true,
            timestampCorrected: true,
            senderTimestamp: 1_699_999_999,
            reactionSummary: "👍:3,❤️:2",
            routeType: .tcDirect,
            regionScope: "Germany"
        )
        var populated = dto
        populated.sortDate = Date(timeIntervalSince1970: 1_600_000_000)

        let encoded = try JSONEncoder().encode(populated)
        let decoded = try JSONDecoder().decode(MessageDTO.self, from: encoded)
        #expect(decoded == populated)
    }

    @Test("saveMessage persists send metadata and preview fields through export and import")
    func saveMessagePreservesMetadataThroughBackupRoundTrip() async throws {
        let radioID = UUID()
        let sourceStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        var msg = MessageDTO.testDirectMessage(radioID: radioID, sendCount: 3)
        msg.deduplicationKey = "save-message-fields-\(UUID())"
        msg.linkPreviewURL = "https://example.com"
        msg.linkPreviewTitle = "Example"
        msg.reactionSummary = "👍:2"
        try await sourceStore.saveMessage(msg)

        // saveMessage routes through Message(dto:), so the live row keeps every DTO field.
        let savedRow = try #require(await sourceStore.fetchMessage(id: msg.id))
        #expect(savedRow.sendCount == 3)
        #expect(savedRow.linkPreviewURL == "https://example.com")
        #expect(savedRow.linkPreviewTitle == "Example")
        #expect(savedRow.reactionSummary == "👍:2")

        let service = AppBackupService()
        let exportResult = try await service.export(persistenceStore: sourceStore)
        let envelope = try parseBackup(data: exportResult.data)

        let destContainer = try PersistenceStore.createContainer(inMemory: true)
        let destStore = PersistenceStore(modelContainer: destContainer)
        _ = try await service.importBackup(envelope: envelope, into: destStore)

        let imported = try #require(await destStore.fetchMessage(id: msg.id))
        #expect(imported.sendCount == 3)
        #expect(imported.linkPreviewURL == "https://example.com")
        #expect(imported.linkPreviewTitle == "Example")
        #expect(imported.reactionSummary == "👍:2")
    }

    // MARK: - selectedThemeID backup contract

    @Test("selectedThemeID round-trips through encode/decode")
    func selectedThemeIDRoundTrips() throws {
        var prefs = BackupUserDefaults()
        prefs.selectedThemeID = "ember"
        let data = try JSONEncoder().encode(prefs)
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: data)
        #expect(decoded.selectedThemeID == "ember")
    }

    @Test("Legacy envelope without selectedThemeID decodes as nil")
    func legacyEnvelopeDecodesSelectedThemeIDAsNil() throws {
        let legacyJSON = """
        { "hasCompletedOnboarding": true, "mapStyleSelection": "topo" }
        """.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: legacyJSON)
        #expect(decoded.selectedThemeID == nil)
        #expect(decoded.hasCompletedOnboarding == true)
    }

    @Test("restore writes selectedThemeID only when local key is missing")
    func restoreRespectsExistingSelectedThemeID() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        defaults.set("marine", forKey: PersistenceKeys.selectedThemeID)
        var prefs = BackupUserDefaults()
        prefs.selectedThemeID = "ember"
        let setKeys = prefs.restore(to: defaults)
        #expect(!setKeys.contains(PersistenceKeys.selectedThemeID))
        #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == "marine")
    }

    @Test("restore writes selectedThemeID when local is missing (fresh install)")
    func restoreWritesSelectedThemeIDWhenMissing() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        var prefs = BackupUserDefaults()
        prefs.selectedThemeID = "ember"
        let setKeys = prefs.restore(to: defaults)
        #expect(setKeys.contains(PersistenceKeys.selectedThemeID))
        #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == "ember")
    }

    // MARK: - appColorSchemePreference backup contract

    @Test("appColorSchemePreference round-trips through encode/decode")
    func appColorSchemePreferenceRoundTrips() throws {
        var prefs = BackupUserDefaults()
        prefs.appColorSchemePreference = "dark"
        let data = try JSONEncoder().encode(prefs)
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: data)
        #expect(decoded.appColorSchemePreference == "dark")
    }

    @Test("Legacy envelope without appColorSchemePreference decodes as nil")
    func legacyEnvelopeDecodesAppColorSchemePreferenceAsNil() throws {
        let legacyJSON = """
        { "hasCompletedOnboarding": true, "mapStyleSelection": "topo" }
        """.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(BackupUserDefaults.self, from: legacyJSON)
        #expect(decoded.appColorSchemePreference == nil)
    }

    @Test("restore writes appColorSchemePreference only when local key is missing")
    func restoreRespectsExistingAppColorSchemePreference() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        defaults.set("light", forKey: PersistenceKeys.appColorSchemePreference)
        var prefs = BackupUserDefaults()
        prefs.appColorSchemePreference = "dark"
        let setKeys = prefs.restore(to: defaults)
        #expect(!setKeys.contains(PersistenceKeys.appColorSchemePreference))
        #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference) == "light")
    }

    @Test("restore writes appColorSchemePreference when local is missing (fresh install)")
    func restoreWritesAppColorSchemePreferenceWhenMissing() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        var prefs = BackupUserDefaults()
        prefs.appColorSchemePreference = "dark"
        let setKeys = prefs.restore(to: defaults)
        #expect(setKeys.contains(PersistenceKeys.appColorSchemePreference))
        #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference) == "dark")
    }

    // MARK: - Export read path (snapshot) for the new appearance keys

    @Test("snapshot reads selectedThemeID and appColorSchemePreference from UserDefaults")
    func snapshotReadsAppearanceKeys() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        defaults.set("marine", forKey: PersistenceKeys.selectedThemeID)
        defaults.set("dark", forKey: PersistenceKeys.appColorSchemePreference)
        let snapshot = BackupUserDefaults.snapshot(from: defaults)
        #expect(snapshot.selectedThemeID == "marine")
        #expect(snapshot.appColorSchemePreference == "dark")
    }

    @Test("snapshot leaves appearance keys nil when unset")
    func snapshotLeavesAppearanceKeysNilWhenUnset() throws {
        let defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
        let snapshot = BackupUserDefaults.snapshot(from: defaults)
        #expect(snapshot.selectedThemeID == nil)
        #expect(snapshot.appColorSchemePreference == nil)
    }
}

private enum InjectedImportFailure: Error {
    case simulated
}

private extension PersistenceStore {
    func setAutosaveEnabledForTesting(_ isEnabled: Bool) {
        modelContext.autosaveEnabled = isEnabled
    }

    func autosaveEnabledForTesting() -> Bool {
        modelContext.autosaveEnabled
    }

    func hasPendingChangesForTesting() -> Bool {
        modelContext.hasChanges
    }
}
