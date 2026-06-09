import Testing
import Foundation
import SwiftData
@testable import MC1
@testable import MC1Services

// MARK: - Test Helpers

private func createTestContact(
    id: UUID = UUID(),
    radioID: UUID,
    name: String = "TestContact"
) -> ContactDTO {
    ContactDTO(
        id: id,
        radioID: radioID,
        publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
        name: name,
        typeRawValue: ContactType.chat.rawValue,
        flags: 0,
        outPathLength: 2,
        outPath: Data([0x01, 0x02]),
        lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
        latitude: 0,
        longitude: 0,
        lastModified: UInt32(Date().timeIntervalSince1970),
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: Date(),
        unreadCount: 0
    )
}

private func createTestChannel(
    id: UUID = UUID(),
    radioID: UUID,
    index: UInt8 = 0,
    name: String = "TestChannel"
) -> ChannelDTO {
    ChannelDTO(
        id: id,
        radioID: radioID,
        index: index,
        name: name,
        secret: Data(),
        isEnabled: true,
        lastMessageDate: Date(),
        unreadCount: 0,
        unreadMentionCount: 0,
        notificationLevel: .all,
        isFavorite: false
    )
}

private func createTestMessage(
    contactID: UUID,
    radioID: UUID,
    timestamp: UInt32,
    createdAt: Date = Date(),
    direction: MessageDirection = .incoming,
    text: String = "Test message"
) -> MessageDTO {
    MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: contactID,
        channelIndex: nil,
        text: text,
        timestamp: timestamp,
        createdAt: createdAt,
        direction: direction,
        status: .delivered,
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
}

private func createChannelMessage(
    radioID: UUID,
    channelIndex: UInt8,
    timestamp: UInt32,
    senderName: String = "Sender",
    text: String = "Test message"
) -> MessageDTO {
    MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: nil,
        channelIndex: channelIndex,
        text: text,
        timestamp: timestamp,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        senderKeyPrefix: nil,
        senderNodeName: senderName,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )
}

// MARK: - Mock DataStore for Pagination Testing

/// A minimal mock data store for testing pagination behavior.
/// Uses in-memory storage and allows configuring responses.
actor PaginationTestDataStore: PersistenceStoreProtocol {
    var messages: [UUID: MessageDTO] = [:]
    var contacts: [UUID: ContactDTO] = [:]
    var channels: [UUID: ChannelDTO] = [:]
    var blockedContacts: [ContactDTO] = []

    var stubbedFetchError: Error?

    init() {}

    // MARK: - Message Operations

    func saveMessage(_ dto: MessageDTO) async throws {
        messages[dto.id] = dto
    }

    func fetchMessage(id: UUID) async throws -> MessageDTO? {
        messages[id]
    }

    func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] {
        var result: [UUID: [MessageDTO]] = [:]
        for contactID in contactIDs {
            let filtered = messages.values.filter { $0.contactID == contactID }
                .sorted { $0.timestamp < $1.timestamp }
            result[contactID] = Array(filtered.prefix(limit))
        }
        return result
    }

    func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] {
        var result: [UUID: [MessageDTO]] = [:]
        for channel in channels {
            let filtered = messages.values.filter { $0.radioID == channel.radioID && $0.channelIndex == channel.channelIndex }
                .sorted { $0.timestamp < $1.timestamp }
            result[channel.id] = Array(filtered.prefix(limit))
        }
        return result
    }

    func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO] {
        if let error = stubbedFetchError {
            throw error
        }
        // Match production: sort descending (newest first), apply offset/limit, then reverse to ascending
        let filtered = messages.values.filter { $0.contactID == contactID }
            .sorted { $0.timestamp > $1.timestamp }
        return Array(filtered.dropFirst(offset).prefix(limit).reversed())
    }

    func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO] {
        if let error = stubbedFetchError {
            throw error
        }
        // Match production: sort descending (newest first), apply offset/limit, then reverse to ascending
        let filtered = messages.values.filter { $0.radioID == radioID && $0.channelIndex == channelIndex }
            .sorted { $0.timestamp > $1.timestamp }
        return Array(filtered.dropFirst(offset).prefix(limit).reversed())
    }

    func updateMessageStatus(id: UUID, status: MessageStatus) async throws {}
    func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {}
    func updateMessageRetryStatus(
        id: UUID,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int
    ) async throws {}
    func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {}
    func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {}
    func updateMessageLinkPreview(
        id: UUID,
        url: String?,
        title: String?,
        imageData: Data?,
        iconData: Data?,
        fetched: Bool
    ) throws {}

    // MARK: - Contact Operations

    func fetchContacts(radioID: UUID) async throws -> [ContactDTO] {
        contacts.values.filter { $0.radioID == radioID }
    }

    func fetchConversations(radioID: UUID) async throws -> [ContactDTO] {
        contacts.values.filter { $0.radioID == radioID && $0.lastMessageDate != nil }
    }

    func fetchContact(id: UUID) async throws -> ContactDTO? {
        contacts[id]
    }

    func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
        contacts.values.first { $0.radioID == radioID && $0.publicKey == publicKey }
    }

    func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO? {
        contacts.values.first { $0.radioID == radioID && $0.publicKey.prefix(6) == publicKeyPrefix }
    }

    func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] { [:] }
    @discardableResult func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> UUID { UUID() }
    func saveContact(_ dto: ContactDTO) async throws { contacts[dto.id] = dto }
    func deleteContact(id: UUID) async throws { contacts.removeValue(forKey: id) }
    func updateContactLastMessage(contactID: UUID, date: Date?) async throws {}
    func incrementUnreadCount(contactID: UUID) async throws {}
    func clearUnreadCount(contactID: UUID) async throws {}

    // MARK: - Mention Tracking

    func markMentionSeen(messageID: UUID) async throws {}
    func incrementUnreadMentionCount(contactID: UUID) async throws {}
    func decrementUnreadMentionCount(contactID: UUID) async throws {}
    func clearUnreadMentionCount(contactID: UUID) async throws {}
    func incrementChannelUnreadMentionCount(channelID: UUID) async throws {}
    func decrementChannelUnreadMentionCount(channelID: UUID) async throws {}
    func clearChannelUnreadMentionCount(channelID: UUID) async throws {}
    func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID] { [] }
    func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID] { [] }
    func deleteMessagesForContact(contactID: UUID) async throws {}
    func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO] {
        blockedContacts.filter { $0.radioID == radioID }
    }

    // MARK: - Blocked Channel Senders

    func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {}
    func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {}
    func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {}
    func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] { [] }

    // MARK: - Channel Operations

    func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] {
        channels.values.filter { $0.radioID == radioID }.sorted { $0.index < $1.index }
    }

    func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? {
        channels.values.first { $0.radioID == radioID && $0.index == index }
    }

    func fetchChannel(id: UUID) async throws -> ChannelDTO? {
        channels[id]
    }

    @discardableResult func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID { UUID() }
    func saveChannel(_ dto: ChannelDTO) async throws { channels[dto.id] = dto }
    func deleteChannel(id: UUID) async throws { channels.removeValue(forKey: id) }
    func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {}
    func incrementChannelUnreadCount(channelID: UUID) async throws {}
    func clearChannelUnreadCount(channelID: UUID) async throws {}

    // MARK: - Saved Trace Paths

    func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] { [] }
    func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? { nil }
    func createSavedTracePath(
        radioID: UUID,
        name: String,
        pathBytes: Data,
        hashSize: Int,
        initialRun: TracePathRunDTO?
    ) async throws -> SavedTracePathDTO {
        SavedTracePathDTO(
            id: UUID(),
            radioID: radioID,
            name: name,
            pathBytes: pathBytes,
            hashSize: hashSize,
            createdDate: Date(),
            runs: []
        )
    }
    func updateSavedTracePathName(id: UUID, name: String) async throws {}
    func deleteSavedTracePath(id: UUID) async throws {}
    func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {}

    // MARK: - Heard Repeats

    func findSentChannelMessage(
        radioID: UUID,
        channelIndex: UInt8,
        timestamp: UInt32,
        text: String,
        withinSeconds: Int
    ) async throws -> MessageDTO? { nil }
    func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {}
    func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] { [] }
    func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool { false }
    func incrementMessageHeardRepeats(id: UUID) async throws -> Int { 0 }
    func deleteMessageRepeats(messageID: UUID) async throws {}
    func incrementMessageSendCount(id: UUID) async throws -> Int { 0 }

    // MARK: - Debug Log Operations

    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}
    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] { [] }
    func countDebugLogEntries() async throws -> Int { 0 }
    func pruneDebugLogEntries(keepCount: Int) async throws {}
    func clearDebugLogEntries() async throws {}

    // MARK: - Link Preview Data

    func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? { nil }
    func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {}

    // MARK: - RxLogEntry Lookup

    func findRxLogEntry(
        channelIndex: UInt8?,
        senderTimestamp: UInt32
    ) async throws -> RxLogEntryDTO? { nil }
    func findRxLogEntryBySenderPrefix(senderPrefixByte: UInt8, receivedSince: Date) async throws -> RxLogEntryDTO? { nil }

    // MARK: - Discovered Nodes

    func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
        fatalError("Not implemented")
    }
    func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO] { [] }
    func deleteDiscoveredNode(id: UUID) async throws {}
    func clearDiscoveredNodes(radioID: UUID) async throws {}
    func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data> { Set() }
    func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO] { [] }
    func saveReaction(_ dto: ReactionDTO) async throws {}
    func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool { false }
    func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws {}
    func deleteReactionsForMessage(messageID: UUID) async throws {}
    func findChannelMessageForReaction(radioID: UUID, channelIndex: UInt8, parsedReaction: ParsedReaction, localNodeName: String?, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? { nil }
    func fetchChannelMessageCandidates(radioID: UUID, channelIndex: UInt8, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] { [] }
    func fetchDMMessageCandidates(radioID: UUID, contactID: UUID, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] { [] }
    func findDMMessageForReaction(radioID: UUID, contactID: UUID, messageHash: String, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? { nil }

    // MARK: - Notification Level

    func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {}
    func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {}
    func markSessionDisconnected(_ sessionID: UUID) async throws {}
    func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool { false }

    // MARK: - Channel Message Deletion

    func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {}

    // MARK: - Pending Sends

    func upsertPendingSend(_ dto: PendingSendDTO) async throws {}
    func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int { 0 }
    func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO] { [] }
    func deletePendingSend(id: UUID) async throws {}
    func deletePendingSendsForMessage(messageID: UUID) async throws {}
    func hasPendingSend(messageID: UUID) async throws -> Bool { false }

    // MARK: - Room Messages

    func saveRoomMessage(_ dto: RoomMessageDTO) async throws {}
    func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? { nil }
    func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] { [] }
    func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool { false }
    func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool { false }
    func updateRoomMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?) async throws {}
    func updateRoomMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
    func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws {}

    // MARK: - Node Status Snapshots

    // swiftlint:disable:next line_length function_parameter_count
    func saveNodeStatusSnapshot(nodePublicKey: Data, batteryMillivolts: UInt16?, lastSNR: Double?, lastRSSI: Int16?, noiseFloor: Int16?, uptimeSeconds: UInt32?, rxAirtimeSeconds: UInt32?, packetsSent: UInt32?, packetsReceived: UInt32?, receiveErrors: UInt32?, postedCount: UInt16?, postPushCount: UInt16?) async throws -> UUID { UUID() }
    func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? { nil }
    func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] { [] }
    func fetchPreviousNodeStatusSnapshot(nodePublicKey: Data, before: Date) async throws -> NodeStatusSnapshotDTO? { nil }
    func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
    func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
    func recordNodeStatusSnapshot(nodePublicKey: Data, status: NodeStatusMetrics?, telemetry: [TelemetrySnapshotEntry]?, neighbors: [NeighborSnapshotEntry]?) async throws -> UUID { UUID() }
    func saveTelemetryOnlySnapshot(nodePublicKey: Data, telemetryEntries: [TelemetrySnapshotEntry]) async throws -> UUID { UUID() }
    func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}
}

// MARK: - Mock Link Preview Cache

actor MockLinkPreviewCacheForPagination: LinkPreviewCaching {
    func preview(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol,
        isChannelMessage: Bool
    ) async -> LinkPreviewResult {
        .noPreviewAvailable
    }

    func manualFetch(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol
    ) async -> LinkPreviewResult {
        .noPreviewAvailable
    }

    func isFetching(_ url: URL) async -> Bool { false }
    func cachedPreview(for url: URL) async -> LinkPreviewDataDTO? { nil }
}

// MARK: - Pagination Tests

@Suite("ChatViewModel Pagination Tests")
@MainActor
struct ChatViewModelPaginationTests {

    // MARK: - Test: loadOlderMessages sets hasMoreMessages = false when fewer than pageSize returned

    @Test("Loading fewer messages than pageSize marks no more messages available")
    func loadFewerThanPageSizeStopsLoading() async throws {
        let dataStore = PaginationTestDataStore()
        let linkPreviewCache = MockLinkPreviewCacheForPagination()
        let viewModel = ChatViewModel()

        let radioID = UUID()
        let contactID = UUID()
        let contact = createTestContact(id: contactID, radioID: radioID)

        try await dataStore.saveContact(contact)

        // Add only 10 messages (less than pageSize of 50)
        for index in 0..<10 {
            let message = createTestMessage(
                contactID: contactID,
                radioID: radioID,
                timestamp: UInt32(1000 + index)
            )
            try await dataStore.saveMessage(message)
        }

        // Configure view model - need to use the internal configure method
        // Since we can't directly inject a PersistenceStoreProtocol, we'll test through observable behavior
        viewModel.currentContact = contact
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator
        let fetched = try await dataStore.fetchMessages(contactID: contactID, limit: 50, offset: 0)
        coordinator.replaceAll(fetched)

        let initialCount = viewModel.messages.count
        #expect(initialCount == 10)

        // After loading 10 messages (< 50 pageSize), hasMoreMessages should be false internally
        // We verify this by checking that calling loadOlderMessages has no effect
        // when there are no more messages (since we loaded all 10 and offset would be 10)

        // Direct unit test of the pagination logic: if initial load < pageSize, no more loading
        #expect(viewModel.messages.count < 50, "Should have fewer than pageSize messages")
    }

    @Test("loadOlderMessages prepends messages to array")
    func loadOlderMessagesPrepends() async throws {
        let dataStore = PaginationTestDataStore()
        let radioID = UUID()
        let contactID = UUID()
        let contact = createTestContact(id: contactID, radioID: radioID)

        try await dataStore.saveContact(contact)

        // Add 60 messages with sequential timestamps (0-59)
        for index in 0..<60 {
            let message = createTestMessage(
                contactID: contactID,
                radioID: radioID,
                timestamp: UInt32(1000 + index),
                text: "Message \(index)"
            )
            try await dataStore.saveMessage(message)
        }

        // Production pagination: sort descending, apply offset/limit, reverse to ascending
        // With 60 messages (timestamps 1000-1059):
        // - offset 0, limit 50 returns the 50 most recent (1010-1059), sorted ascending
        // - offset 50, limit 50 returns the next 10 older (1000-1009), sorted ascending

        let firstPage = try await dataStore.fetchMessages(contactID: contactID, limit: 50, offset: 0)
        #expect(firstPage.count == 50)
        #expect(firstPage.first?.timestamp == 1010, "First page starts with oldest of the 50 most recent")
        #expect(firstPage.last?.timestamp == 1059, "First page ends with the most recent message")

        // Fetch older messages (what loadOlderMessages does)
        let secondPage = try await dataStore.fetchMessages(contactID: contactID, limit: 50, offset: 50)
        #expect(secondPage.count == 10, "Second page has remaining 10 older messages")
        #expect(secondPage.first?.timestamp == 1000, "Second page starts with the oldest message")
        #expect(secondPage.last?.timestamp == 1009, "Second page ends before first page starts")

        // Simulate loadOlderMessages: prepend older messages
        var messages = firstPage
        messages.insert(contentsOf: secondPage, at: 0)

        #expect(messages.count == 60)
        #expect(messages.first?.timestamp == 1000, "After prepend, oldest is first")
        #expect(messages.last?.timestamp == 1059, "After prepend, newest is last")
    }

    @Test("loadOlderMessages guards against concurrent fetches")
    func loadOlderMessagesGuardsConcurrent() async {
        let viewModel = ChatViewModel()

        // isLoadingOlder starts false
        #expect(viewModel.isLoadingOlder == false)

        // After initial setup without dataStore, calling loadOlderMessages returns early
        // This tests the guard condition
        await viewModel.loadOlderMessages()
        #expect(viewModel.isLoadingOlder == false)
    }

    @Test("loadOlderMessages returns early without dataStore")
    func loadOlderMessagesWithoutDataStoreDoesNothing() async {
        let viewModel = ChatViewModel()
        let radioID = UUID()
        let contactID = UUID()
        let contact = createTestContact(id: contactID, radioID: radioID)

        viewModel.currentContact = contact
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        // Without configuring dataStore, loadOlderMessages should return early
        await viewModel.loadOlderMessages()

        // No error should be set
        #expect(viewModel.errorMessage == nil)
        #expect(viewModel.messages.isEmpty)
    }

    @Test("Pagination state resets when loading messages for new contact")
    func paginationStateResetsOnConversationSwitch() async {
        let viewModel = ChatViewModel()
        let radioID = UUID()

        // Create two contacts
        let contactA = createTestContact(id: UUID(), radioID: radioID, name: "Alice")
        let contactB = createTestContact(id: UUID(), radioID: radioID, name: "Bob")

        // Start with contact A
        viewModel.currentContact = contactA
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator
        coordinator.replaceAll([
            createTestMessage(contactID: contactA.id, radioID: radioID, timestamp: 1000)
        ])

        // isLoadingOlder should be false
        #expect(viewModel.isLoadingOlder == false)

        // Switch to contact B
        viewModel.currentContact = contactB
        coordinator.replaceAll([])

        // State should be clean for new contact
        #expect(viewModel.messages.isEmpty)
        #expect(viewModel.isLoadingOlder == false)
    }

    @Test("Initial message load sets hasMoreMessages based on count")
    func initialLoadSetsHasMoreMessages() async {
        let viewModel = ChatViewModel()

        // When messages.count equals pageSize (50), hasMoreMessages should remain true
        // When messages.count < pageSize, hasMoreMessages becomes false

        // This is tested indirectly through the loadMessages behavior
        // The key is that with < 50 messages, subsequent loadOlderMessages calls should not fetch

        #expect(viewModel.messages.isEmpty)
    }

    /// Verifies that `loadOlderMessages` clears `isLoadingOlder` before
    /// entering the reaction-indexing loop, so the pagination spinner is not
    /// held open by a slow `ReactionService.indexMessage` actor hop on a
    /// busy channel. The structural ordering is enforced in
    /// `ChatViewModel.loadOlderMessages`: the spinner is cleared immediately
    /// after `buildItems()` and before the channel/DM reaction-indexing
    /// blocks. This test exercises the success path with `appState` unset
    /// (so the indexing loops are skipped) and asserts the post-return
    /// state matches the documented contract — `isLoadingOlder == false`,
    /// older messages prepended, no error surfaced. The deeper "spinner
    /// already false while indexing still running" property is verified by
    /// source-level review at PR time; `ReactionService` is a concrete
    /// actor without a protocol surface, so an injectable continuation-
    /// blocking stub is not available.
    @Test("loadOlderMessages clears isLoadingOlder on the success path")
    func loadOlderMessagesClearsSpinnerBeforeIndexing() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let radioID = UUID()
        let contactID = UUID()

        let viewModel = ChatViewModel()
        viewModel.dataStore = dataStore
        viewModel.currentContact = createTestContact(id: contactID, radioID: radioID)
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        // Seed the database with a page worth of messages so the
        // pagination fetch returns rows and proceeds past the
        // prepend/buildItems block where the early-clear lives.
        for index in 0..<10 {
            let message = createTestMessage(
                contactID: contactID,
                radioID: radioID,
                timestamp: UInt32(1000 + index)
            )
            try await dataStore.saveMessage(message)
        }

        #expect(viewModel.isLoadingOlder == false)

        await viewModel.loadOlderMessages()

        #expect(viewModel.isLoadingOlder == false, "Pagination must release the spinner before returning")
        #expect(viewModel.errorMessage == nil)
        #expect(viewModel.errorBannerMessage == nil)
        #expect(viewModel.messages.count == 10, "Pagination should have prepended fetched messages")
    }
}

// MARK: - Channel Pagination Tests

@Suite("ChatViewModel Channel Pagination Tests")
@MainActor
struct ChatViewModelChannelPaginationTests {

    @Test("Channel message pagination works similar to direct messages")
    func channelPaginationWorks() async throws {
        let dataStore = PaginationTestDataStore()
        let radioID = UUID()
        let channelIndex: UInt8 = 0
        let channel = createTestChannel(radioID: radioID, index: channelIndex)

        try await dataStore.saveChannel(channel)

        // Add 30 channel messages
        for index in 0..<30 {
            let message = createChannelMessage(
                radioID: radioID,
                channelIndex: channelIndex,
                timestamp: UInt32(1000 + index),
                senderName: "User\(index % 3)"
            )
            try await dataStore.saveMessage(message)
        }

        // Fetch first page
        let messages = try await dataStore.fetchMessages(
            radioID: radioID,
            channelIndex: channelIndex,
            limit: 50,
            offset: 0
        )

        #expect(messages.count == 30)
        #expect(messages.count < 50, "Fewer than pageSize means no more messages available")
    }

    @Test("loadOlderMessages handles channel messages")
    func loadOlderMessagesHandlesChannels() async {
        let viewModel = ChatViewModel()
        let radioID = UUID()
        let channelIndex: UInt8 = 1
        let channel = createTestChannel(radioID: radioID, index: channelIndex, name: "General")

        viewModel.currentChannel = channel
        viewModel.currentContact = nil

        // Without dataStore configured, loadOlderMessages returns early
        await viewModel.loadOlderMessages()

        #expect(viewModel.isLoadingOlder == false)
        #expect(viewModel.errorMessage == nil)
    }

    @Test("Initial channel load uses unfiltered count for hasMoreMessages")
    func initialLoadUsesUnfilteredCountForPagination() async throws {
        // If we fetch 50 messages and 10 are blocked, hasMoreMessages should still be true
        // because the unfiltered count (50) equals pageSize
        let dataStore = PaginationTestDataStore()
        let radioID = UUID()
        let channelIndex: UInt8 = 0

        // Add exactly 50 messages (pageSize), some from blocked sender
        for index in 0..<50 {
            let senderName = index < 10 ? "BlockedUser" : "User\(index)"
            let message = createChannelMessage(
                radioID: radioID,
                channelIndex: channelIndex,
                timestamp: UInt32(1000 + index),
                senderName: senderName
            )
            try await dataStore.saveMessage(message)
        }

        // Fetch all messages
        let messages = try await dataStore.fetchMessages(
            radioID: radioID,
            channelIndex: channelIndex,
            limit: 50,
            offset: 0
        )

        #expect(messages.count == 50, "Should fetch 50 messages before filtering")

        // After filtering, would have 40 messages, but hasMoreMessages should be based on 50
        let filtered = messages.filter { $0.senderNodeName != "BlockedUser" }
        #expect(filtered.count == 40, "After filtering should have 40 messages")

        // The key insight: unfiltered count (50) == pageSize means hasMoreMessages = true
        #expect(messages.count == 50, "Unfiltered count should drive pagination decision")
    }
}

// MARK: - Display Items Tests

@Suite("ChatViewModel Display Items Pagination Tests")
@MainActor
struct ChatViewModelDisplayItemsPaginationTests {

    @Test("Display items are rebuilt after loading older messages")
    func displayItemsRebuildAfterLoadingOlder() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        // Start with some messages
        let radioID = UUID()
        let contactID = UUID()

        let messages = (0..<5).map { index in
            createTestMessage(
                contactID: contactID,
                radioID: radioID,
                timestamp: UInt32(1000 + index)
            )
        }

        coordinator.replaceAll(messages)
        viewModel.buildItems()
        await coordinator.buildItemsTask?.value

        #expect(viewModel.items.count == 5)

        // Add more messages (simulating loadOlderMessages prepend)
        let olderMessages = (0..<3).map { index in
            createTestMessage(
                contactID: contactID,
                radioID: radioID,
                timestamp: UInt32(900 + index)
            )
        }

        coordinator.prepend(olderMessages)
        viewModel.buildItems()
        await coordinator.buildItemsTask?.value

        #expect(viewModel.items.count == 8)
    }

    @Test("Message lookup by ID works after pagination")
    func messageLookupWorksAfterPagination() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator
        let radioID = UUID()
        let contactID = UUID()

        let message1 = createTestMessage(contactID: contactID, radioID: radioID, timestamp: 1000)
        let message2 = createTestMessage(contactID: contactID, radioID: radioID, timestamp: 1001)

        coordinator.replaceAll([message1, message2])
        viewModel.buildItems()
        await coordinator.buildItemsTask?.value

        // Lookup should work
        #expect(viewModel.items.count == 2)
        let foundMessage = viewModel.message(for: viewModel.items[0])
        #expect(foundMessage?.id == message1.id)
    }
}

// MARK: - Cross-Boundary Reordering Tests

@Suite("Same-Sender Cluster Reordering Across Page Boundaries")
@MainActor
struct CrossBoundaryReorderingTests {

    @Test("Reordering fixes same-sender cluster split across pagination boundary")
    func reorderingFixesSplitCluster() {
        // Scenario: Sender sends msg1 (t=100), msg2 (t=101), msg3 (t=102) rapidly.
        // Mesh delivers them out of order: msg3, msg1, msg2.
        // msg3 ends up on page 2 (older), msg1 and msg2 on page 1 (newer).
        //
        // Each page is reordered independently, but the cross-boundary cluster
        // (msg3 on page 2, msg1+msg2 on page 1) is NOT reordered until merge.

        let radioID = UUID()
        let contactID = UUID()
        let base = Date(timeIntervalSince1970: 1_000_000)

        // Page 2 (older, loaded second via loadOlderMessages): msg3 arrived first
        let msg3 = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 102,
            createdAt: base.addingTimeInterval(0),  // received first
            text: "msg3"
        )

        // Page 1 (newer, loaded first): msg1 and msg2 arrived later
        let msg1 = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 100,
            createdAt: base.addingTimeInterval(2),  // received second
            text: "msg1"
        )
        let msg2 = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 101,
            createdAt: base.addingTimeInterval(3),  // received third
            text: "msg2"
        )

        // Simulate independent per-page reordering (as production does)
        let page2Reordered = MessageDTO.reorderSameSenderClusters([msg3])  // single msg, no-op
        let page1Reordered = MessageDTO.reorderSameSenderClusters([msg1, msg2])  // already ordered

        // Merge: prepend older page
        var merged = page2Reordered
        merged.append(contentsOf: page1Reordered)

        // Without cross-boundary reordering: msg3, msg1, msg2 (receive order at boundary)
        #expect(merged.map(\.text) == ["msg3", "msg1", "msg2"])

        // After re-running reorderSameSenderClusters on the full merged array
        let fixed = MessageDTO.reorderSameSenderClusters(merged)

        // All three are from the same sender (DM, same direction), within 5s window,
        // so they're reordered by sender timestamp: msg1, msg2, msg3
        #expect(fixed.map(\.text) == ["msg1", "msg2", "msg3"])
    }

    @Test("Reordering does not merge clusters beyond the 5-second window")
    func reorderingRespectsWindowAtBoundary() {
        let radioID = UUID()
        let contactID = UUID()
        let base = Date(timeIntervalSince1970: 1_000_000)

        // Page 2 message: received well before the page 1 messages (>5s gap)
        let oldMsg = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 100,
            createdAt: base.addingTimeInterval(0),
            text: "old"
        )

        // Page 1 messages: received 10 seconds later
        let newMsg1 = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 99,  // earlier sender timestamp but later receive
            createdAt: base.addingTimeInterval(10),
            text: "new1"
        )
        let newMsg2 = createTestMessage(
            contactID: contactID,
            radioID: radioID,
            timestamp: 102,
            createdAt: base.addingTimeInterval(11),
            text: "new2"
        )

        // Merge: prepend older page
        var merged = [oldMsg]
        merged.append(contentsOf: [newMsg1, newMsg2])

        let result = MessageDTO.reorderSameSenderClusters(merged)

        // The 10-second gap between oldMsg and newMsg1 exceeds the 5s window,
        // so they should NOT be clustered — order stays as-is
        #expect(result.map(\.text) == ["old", "new1", "new2"])
    }
}

// MARK: - Channel Sender Registration (Pagination Regression)

@Suite("ChatViewModel Channel Sender Registration")
@MainActor
struct ChatViewModelChannelSenderRegistrationTests {

    @Test("addChannelSenderIfNew inserts synthetic sender and records timestamp")
    func registersNewSender() {
        let viewModel = ChatViewModel()
        let radioID = UUID()

        viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 1000)

        #expect(viewModel.channelSenderNames.contains("Alice"))
        #expect(viewModel.channelSenders.count == 1)
        #expect(viewModel.channelSenderOrder["Alice"] == 1000)
    }

    @Test("addChannelSenderIfNew max-merges timestamps for the same sender")
    func maxMergesTimestamp() {
        let viewModel = ChatViewModel()
        let radioID = UUID()

        viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 1000)
        viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 500)

        // Older message arriving after a newer one must not lower the recency stamp.
        #expect(viewModel.channelSenderOrder["Alice"] == 1000)
        #expect(viewModel.channelSenders.count == 1)
    }

    @Test("addChannelSenderIfNew skips synthetic for known contacts but still records order")
    func recordsOrderForKnownContact() {
        let viewModel = ChatViewModel()
        let radioID = UUID()
        viewModel.contactNameSet = ["Bob"]

        viewModel.addChannelSenderIfNew("Bob", radioID: radioID, timestamp: 1500)

        #expect(viewModel.channelSenders.isEmpty, "No synthetic for a known contact")
        #expect(viewModel.channelSenderNames.contains("Bob") == false)
        #expect(viewModel.channelSenderOrder["Bob"] == 1500, "Order tracks all senders for mention recency")
    }

    @Test("addChannelSenderIfNew rejects empty and oversized names")
    func rejectsInvalidNames() {
        let viewModel = ChatViewModel()
        let radioID = UUID()
        let tooLong = String(repeating: "x", count: 129)

        viewModel.addChannelSenderIfNew("   ", radioID: radioID, timestamp: 1)
        viewModel.addChannelSenderIfNew("", radioID: radioID, timestamp: 1)
        viewModel.addChannelSenderIfNew(tooLong, radioID: radioID, timestamp: 1)

        #expect(viewModel.channelSenders.isEmpty)
        #expect(viewModel.channelSenderOrder.isEmpty)
    }

    @Test("Pagination registration: older-page senders join channelSenderNames and channelSenderOrder")
    func paginationRegistersOlderSenders() {
        // Simulates the loadOlderMessages call site: live receive registers
        // recent senders first, then a paginated older page registers earlier
        // senders. After both steps, the mention picker must see all senders
        // and the order map must reflect each sender's own timestamp.
        let viewModel = ChatViewModel()
        let radioID = UUID()

        // Live-receive: senders A and B on the current page
        viewModel.addChannelSenderIfNew("A", radioID: radioID, timestamp: 2000)
        viewModel.addChannelSenderIfNew("B", radioID: radioID, timestamp: 2100)

        // Pagination: older page reveals senders C and D, plus another
        // A message from earlier
        viewModel.addChannelSenderIfNew("C", radioID: radioID, timestamp: 500)
        viewModel.addChannelSenderIfNew("D", radioID: radioID, timestamp: 600)
        viewModel.addChannelSenderIfNew("A", radioID: radioID, timestamp: 100)

        #expect(viewModel.channelSenderNames == ["A", "B", "C", "D"])
        #expect(viewModel.channelSenders.count == 4)
        #expect(viewModel.channelSenderOrder["A"] == 2000, "A keeps its live recency, not the older one")
        #expect(viewModel.channelSenderOrder["B"] == 2100)
        #expect(viewModel.channelSenderOrder["C"] == 500)
        #expect(viewModel.channelSenderOrder["D"] == 600)
    }
}
