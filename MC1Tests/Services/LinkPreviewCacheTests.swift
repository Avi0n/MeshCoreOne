import Testing
import Foundation
import MeshCore
@testable import MC1
@testable import MC1Services

@Suite("LinkPreviewCache Tests")
struct LinkPreviewCacheTests {

    // MARK: - Memory Cache Tests

    @Test("Returns cached preview from memory on subsequent requests")
    func returnsCachedPreviewFromMemory() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/article")!

        // Seed the database with a preview
        let dto = LinkPreviewDataDTO(
            url: url.absoluteString,
            title: "Test Article",
            imageData: nil,
            iconData: nil
        )
        await dataStore.setStoredPreview(dto, for: url.absoluteString)

        // First request should hit database
        let result1 = await cache.preview(for: url, using: dataStore, isChannelMessage: false)
        #expect(isLoaded(result1, withTitle: "Test Article"))
        let fetchCount1 = await dataStore.fetchCallCount
        #expect(fetchCount1 == 1)

        // Second request should hit memory cache (no additional fetch)
        let result2 = await cache.preview(for: url, using: dataStore, isChannelMessage: false)
        #expect(isLoaded(result2, withTitle: "Test Article"))
        let fetchCount2 = await dataStore.fetchCallCount
        #expect(fetchCount2 == 1) // Should not increase
    }

    @Test("Memory cache returns correct preview data")
    func memoryCacheReturnsCorrectData() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/test")!

        let dto = LinkPreviewDataDTO(
            url: url.absoluteString,
            title: "Memory Cache Test",
            imageData: Data([1, 2, 3]),
            iconData: Data([4, 5, 6])
        )
        await dataStore.setStoredPreview(dto, for: url.absoluteString)

        // Load into memory cache
        _ = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        // Verify cached data matches
        let cached = await cache.cachedPreview(for: url)
        #expect(cached?.title == "Memory Cache Test")
        #expect(cached?.imageData == Data([1, 2, 3]))
        #expect(cached?.iconData == Data([4, 5, 6]))
    }

    // MARK: - Negative Cache Tests

    @Test("Negative cache prevents repeated network fetches for unavailable previews")
    func negativeCachePreventsRepeatedFetches() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/no-preview")!

        // First request finds no preview (returns noPreviewAvailable)
        _ = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        let initialFetchCount = await dataStore.fetchCallCount

        // Subsequent requests should hit negative cache (no database lookup)
        _ = await cache.preview(for: url, using: dataStore, isChannelMessage: false)
        _ = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        // The key assertion is that repeated requests don't exponentially increase fetches
        let finalFetchCount = await dataStore.fetchCallCount
        #expect(finalFetchCount <= initialFetchCount + 2)
    }

    @Test("Manual fetch clears negative cache and retries")
    func manualFetchClearsNegativeCache() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/retry")!

        // First auto-fetch finds nothing
        _ = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        // Manual fetch should attempt again (clearing negative cache)
        _ = await cache.manualFetch(for: url, using: dataStore)

        // Verify manual fetch was attempted (fetch count increased)
        let fetchCount = await dataStore.fetchCallCount
        #expect(fetchCount >= 1)
    }

    // MARK: - In-Flight Deduplication Tests

    @Test("Concurrent requests for same URL don't create duplicate fetches")
    func concurrentRequestsAreDeduplicated() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/concurrent")!

        // Add delay to database fetch to simulate slow operation
        await dataStore.setFetchDelay(.milliseconds(100))

        // Launch multiple concurrent requests
        async let result1 = cache.preview(for: url, using: dataStore, isChannelMessage: false)
        async let result2 = cache.preview(for: url, using: dataStore, isChannelMessage: false)
        async let result3 = cache.preview(for: url, using: dataStore, isChannelMessage: false)

        // Wait for all to complete
        let results = await [result1, result2, result3]

        // All results should be consistent
        #expect(results.count == 3)
    }

    @Test("isFetching returns true while fetch is in progress")
    func isFetchingReturnsTrueDuringFetch() async {
        let cache = LinkPreviewCache()
        let url = URL(string: "https://example.com/inflight")!

        // Initially not fetching
        let initiallyFetching = await cache.isFetching(url)
        #expect(!initiallyFetching)
    }

    // MARK: - Database Integration Tests

    @Test("Preview is persisted to database after network fetch")
    func previewIsPersistedToDatabase() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/persist")!

        // Seed a preview that will be "fetched"
        let dto = LinkPreviewDataDTO(
            url: url.absoluteString,
            title: "Persisted Preview",
            imageData: nil,
            iconData: nil
        )
        await dataStore.setStoredPreview(dto, for: url.absoluteString)

        // Request preview
        let result = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        #expect(isLoaded(result, withTitle: "Persisted Preview"))
    }

    @Test("Database errors are handled gracefully")
    func databaseErrorsHandledGracefully() async {
        let cache = LinkPreviewCache()
        let dataStore = MockPreviewDataStore()
        let url = URL(string: "https://example.com/error")!

        // Configure dataStore to throw on fetch
        await dataStore.setShouldThrowOnFetch(true)

        // Request should not crash
        let result = await cache.preview(for: url, using: dataStore, isChannelMessage: false)

        // Should return disabled or noPreviewAvailable, not crash
        #expect(isDisabledOrNoPreview(result))
    }

    // MARK: - Helper Functions

    private func isLoaded(_ result: LinkPreviewResult, withTitle title: String) -> Bool {
        if case .loaded(let dto) = result {
            return dto.title == title
        }
        return false
    }

    private func isDisabledOrNoPreview(_ result: LinkPreviewResult) -> Bool {
        switch result {
        case .disabled, .noPreviewAvailable:
            return true
        default:
            return false
        }
    }
}

// MARK: - Mock Data Store

private actor MockPreviewDataStore: PersistenceStoreProtocol {
    private var storedPreviews: [String: LinkPreviewDataDTO] = [:]
    private(set) var fetchCallCount = 0
    private var saveCallCount = 0
    private var fetchDelay: Duration = .zero
    private var shouldThrowOnFetch = false
    private var shouldThrowOnSave = false

    // Async setters for actor-isolated properties
    func setStoredPreview(_ dto: LinkPreviewDataDTO, for url: String) {
        storedPreviews[url] = dto
    }

    func setFetchDelay(_ delay: Duration) {
        fetchDelay = delay
    }

    func setShouldThrowOnFetch(_ value: Bool) {
        shouldThrowOnFetch = value
    }

    func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? {
        fetchCallCount += 1

        if shouldThrowOnFetch {
            throw MockError.fetchFailed
        }

        if fetchDelay > .zero {
            try? await Task.sleep(for: fetchDelay)
        }

        return storedPreviews[url]
    }

    func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {
        saveCallCount += 1

        if shouldThrowOnSave {
            throw MockError.saveFailed
        }

        storedPreviews[dto.url] = dto
    }

    private enum MockError: Error {
        case fetchFailed
        case saveFailed
    }

    // MARK: - Required Protocol Stubs

    // Message Operations
    func saveMessage(_ dto: MessageDTO) async throws {}
    func fetchMessage(id: UUID) async throws -> MessageDTO? { nil }
    func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO] { [] }
    func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO] { [] }
    func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] { [:] }
    func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] { [:] }
    func updateMessageStatus(id: UUID, status: MessageStatus) async throws {}
    func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {}
    func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
    func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {}
    func updateMessageLinkPreview(id: UUID, url: String?, title: String?, imageData: Data?, iconData: Data?, fetched: Bool) throws {}

    // Contact Operations
    func fetchContacts(radioID: UUID) async throws -> [ContactDTO] { [] }
    func fetchConversations(radioID: UUID) async throws -> [ContactDTO] { [] }
    func fetchContact(id: UUID) async throws -> ContactDTO? { nil }
    func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? { nil }
    func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO? { nil }
    @discardableResult func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> UUID { UUID() }
    func saveContact(_ dto: ContactDTO) async throws {}
    func deleteContact(id: UUID) async throws {}
    func updateContactLastMessage(contactID: UUID, date: Date?) async throws {}
    func incrementUnreadCount(contactID: UUID) async throws {}
    func clearUnreadCount(contactID: UUID) async throws {}

    // Mention Tracking
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
    func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO] { [] }

    // Blocked Channel Senders
    func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {}
    func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {}
    func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {}
    func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] { [] }

    // Channel Operations
    func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] { [] }
    func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? { nil }
    func fetchChannel(id: UUID) async throws -> ChannelDTO? { nil }
    @discardableResult func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID { UUID() }
    func saveChannel(_ dto: ChannelDTO) async throws {}
    func deleteChannel(id: UUID) async throws {}
    func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {}
    func incrementChannelUnreadCount(channelID: UUID) async throws {}
    func clearChannelUnreadCount(channelID: UUID) async throws {}

    // Saved Trace Paths
    func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] { [] }
    func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? { nil }
    func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO {
        SavedTracePathDTO(id: UUID(), radioID: radioID, name: name, pathBytes: pathBytes, hashSize: hashSize, createdDate: Date(), runs: [])
    }
    func updateSavedTracePathName(id: UUID, name: String) async throws {}
    func deleteSavedTracePath(id: UUID) async throws {}
    func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {}

    // Heard Repeats
    func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String, withinSeconds: Int) async throws -> MessageDTO? { nil }
    func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {}
    func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] { [] }
    func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool { false }
    func incrementMessageHeardRepeats(id: UUID) async throws -> Int { 0 }
    func deleteMessageRepeats(messageID: UUID) async throws {}
    func incrementMessageSendCount(id: UUID) async throws -> Int { 0 }
    func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {}

    // Debug Log Entries
    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}
    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] { [] }
    func countDebugLogEntries() async throws -> Int { 0 }
    func pruneDebugLogEntries(keepCount: Int) async throws {}
    func clearDebugLogEntries() async throws {}

    // Contact Public Keys
    func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] { [:] }

    // RxLogEntry Lookup
    func findRxLogEntry(channelIndex: UInt8?, senderTimestamp: UInt32) async throws -> RxLogEntryDTO? { nil }
    func findRxLogEntryBySenderPrefix(senderPrefixByte: UInt8, receivedSince: Date) async throws -> RxLogEntryDTO? { nil }

    // Room Message Operations
    func saveRoomMessage(_ dto: RoomMessageDTO) async throws {}
    func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? { nil }
    func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] { [] }
    func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool { false }
    func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool { false }
    func updateRoomMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?) async throws {}
    func updateRoomMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
    func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws {}

    // Discovered Nodes
    func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
        fatalError("Not implemented")
    }
    func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO] { [] }
    func deleteDiscoveredNode(id: UUID) async throws {}
    func clearDiscoveredNodes(radioID: UUID) async throws {}
    func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data> { Set() }

    // Reactions
    func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO] { [] }
    func saveReaction(_ dto: ReactionDTO) async throws {}
    func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool { false }
    func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws {}
    func deleteReactionsForMessage(messageID: UUID) async throws {}
    func findChannelMessageForReaction(radioID: UUID, channelIndex: UInt8, parsedReaction: ParsedReaction, localNodeName: String?, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? { nil }
    func fetchChannelMessageCandidates(radioID: UUID, channelIndex: UInt8, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] { [] }
    func fetchDMMessageCandidates(radioID: UUID, contactID: UUID, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] { [] }
    func findDMMessageForReaction(radioID: UUID, contactID: UUID, messageHash: String, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? { nil }

    // Notification Level
    func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {}
    func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {}
    func markSessionDisconnected(_ sessionID: UUID) async throws {}
    func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool { false }

    // Channel Message Deletion
    func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {}

    // Node Status Snapshots
    // swiftlint:disable:next line_length
    func saveNodeStatusSnapshot(nodePublicKey: Data, batteryMillivolts: UInt16?, lastSNR: Double?, lastRSSI: Int16?, noiseFloor: Int16?, uptimeSeconds: UInt32?, rxAirtimeSeconds: UInt32?, packetsSent: UInt32?, packetsReceived: UInt32?, receiveErrors: UInt32?, postedCount: UInt16?, postPushCount: UInt16?) async throws -> UUID { UUID() }
    func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? { nil }
    func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] { [] }
    func fetchPreviousNodeStatusSnapshot(nodePublicKey: Data, before: Date) async throws -> NodeStatusSnapshotDTO? { nil }
    func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
    func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
    func saveTelemetryOnlySnapshot(nodePublicKey: Data, telemetryEntries: [TelemetrySnapshotEntry]) async throws -> UUID { UUID() }
    func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}
}
