import Testing
import Foundation
import CoreGraphics
import MeshCore
@testable import MC1
@testable import MC1Services

/// Verifies the receive-time withhold-and-release flow: messages with URLs
/// race a `InlineImagePrefetcher` against a 3s timeout before admission,
/// while messages without URLs admit immediately. Outgoing messages bypass
/// withholding and morph in via `rebuildDisplayItem` after their own prefetch
/// resolves. The dimension resolution stream re-emits rebuilds for any
/// matching message after the bubble has already landed.
@Suite("ChatViewModel admission flow")
@MainActor
struct ChatViewModelAdmissionTests {

    // MARK: - Plain text path

    @Test("Plain-text message admits immediately without invoking prefetcher")
    func plainTextSkipsPrefetcher() async {
        let viewModel = makeBoundViewModel()
        let imageCache = SlowImageProber(delay: .milliseconds(50))
        let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(50))
        let store = makeStore()
        bind(store, to: viewModel)
        viewModel.prefetcher = InlineImagePrefetcher(
            imageCache: imageCache,
            linkPreviewCache: linkCache,
            dimensionsStore: store,
            dataStore: AdmissionStubDataStore()
        )

        let message = makeMessage(text: "hello world, nothing to fetch")
        await viewModel.admitIncomingMessage(message, isChannelMessage: false)

        #expect(viewModel.messages.count == 1)
        let probed = await imageCache.probedURLs
        let previewed = await linkCache.fetchedURLs
        #expect(probed.isEmpty)
        #expect(previewed.isEmpty)
    }

    // MARK: - Fast prefetch path

    @Test("URL-bearing message waits for prefetch then admits")
    func fastPrefetchAdmitsAfterCompletion() async {
        let viewModel = makeBoundViewModel()
        let imageCache = SlowImageProber(delay: .milliseconds(50))
        let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(50))
        let store = makeStore()
        bind(store, to: viewModel)
        viewModel.prefetcher = InlineImagePrefetcher(
            imageCache: imageCache,
            linkPreviewCache: linkCache,
            dimensionsStore: store,
            dataStore: AdmissionStubDataStore()
        )

        let message = makeMessage(text: "see https://example.com/cat.png")
        await viewModel.admitIncomingMessage(message, isChannelMessage: false)

        #expect(viewModel.messages.count == 1)
        let probed = await imageCache.probedURLs
        #expect(probed.map(\.absoluteString) == ["https://example.com/cat.png"])
    }

    // MARK: - Nil prefetcher path

    @Test("Missing prefetcher falls back to direct append")
    func nilPrefetcherAdmitsDirectly() async {
        let viewModel = makeBoundViewModel()
        viewModel.prefetcher = nil

        let message = makeMessage(text: "https://example.com/cat.png")
        await viewModel.admitIncomingMessage(message, isChannelMessage: false)

        #expect(viewModel.messages.count == 1)
    }

    // MARK: - Dimension resolution rebuilds

    @Test("Dimension resolution rebuilds matching message")
    func dimensionResolutionRebuildsMatchingMessage() async {
        let viewModel = makeBoundViewModel()
        viewModel.applyEnvInputs(.default)

        let url = URL(string: "https://example.com/cat.png")!
        let message = makeMessage(text: "look \(url.absoluteString)")
        viewModel.appendMessageIfNew(message)
        viewModel.cachedURLs[message.id] = url

        let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
        bind(store, to: viewModel)
        await store.save(url: url, size: CGSize(width: 200, height: 100))

        await viewModel.handleDimensionResolution(url)

        let item = viewModel.items.first { $0.id == message.id }
        #expect(item != nil)
        guard let item else { return }
        let inlineFragment = item.content.compactMap { fragment -> InlineImage? in
            if case .inlineImage(let image) = fragment { return image }
            return nil
        }.first
        #expect(inlineFragment?.cachedAspect == 2.0)
    }

    // MARK: - Timeout-wins race

    @Test(
        "Slow prefetch loses to timeout; message still admits",
        .timeLimit(.minutes(1))
    )
    func slowPrefetchAdmitsAtTimeout() async {
        let viewModel = makeBoundViewModel()
        // Inject a short timeout so the test runs fast; production stays 3s.
        let testTimeout: Duration = .milliseconds(200)
        viewModel.prefetchTimeout = testTimeout

        // Stub probe takes much longer than the timeout.
        let imageCache = SlowImageProber(delay: .seconds(5))
        let linkCache = SlowLinkPreviewFetcher(delay: .seconds(5))
        let store = makeStore()
        bind(store, to: viewModel)
        viewModel.prefetcher = InlineImagePrefetcher(
            imageCache: imageCache,
            linkPreviewCache: linkCache,
            dimensionsStore: store,
            dataStore: AdmissionStubDataStore()
        )

        let message = makeMessage(text: "see https://example.com/cat.png")
        let start = ContinuousClock.now
        await viewModel.admitIncomingMessage(message, isChannelMessage: false)
        let elapsed = ContinuousClock.now - start

        #expect(viewModel.messages.count == 1)
        // Admission should land at ~testTimeout, well below the probe delay.
        // Allow a generous upper bound for scheduler jitter.
        #expect(elapsed < .seconds(1),
                "Admission must not block on the slow probe (elapsed=\(elapsed))")
    }

    // MARK: - Giphy short-code coverage

    @Test("Giphy g:abc short-code triggers prefetch race")
    func giphyShortCodeTriggersRace() async {
        let viewModel = makeBoundViewModel()
        viewModel.prefetchTimeout = .milliseconds(50)

        let imageCache = SlowImageProber(delay: .milliseconds(5))
        let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(5))
        let store = makeStore()
        bind(store, to: viewModel)
        viewModel.prefetcher = InlineImagePrefetcher(
            imageCache: imageCache,
            linkPreviewCache: linkCache,
            dimensionsStore: store,
            dataStore: AdmissionStubDataStore()
        )

        let message = makeMessage(text: "g:abc123")
        await viewModel.admitIncomingMessage(message, isChannelMessage: false)

        #expect(viewModel.messages.count == 1)
        let probed = await imageCache.probedURLs
        // Giphy short-codes expand to direct .gif URLs and hit the probe path.
        #expect(probed.map(\.absoluteString) == ["https://media.giphy.com/media/abc123/giphy.gif"])
    }

    // MARK: - Outgoing prefetch dispatch

    @Test("Outgoing prefetch dispatches when URL present")
    func outgoingPrefetchInvokesProbe() async {
        let viewModel = makeBoundViewModel()
        let imageCache = SlowImageProber(delay: .milliseconds(10))
        let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(10))
        let store = makeStore()
        bind(store, to: viewModel)
        viewModel.prefetcher = InlineImagePrefetcher(
            imageCache: imageCache,
            linkPreviewCache: linkCache,
            dimensionsStore: store,
            dataStore: AdmissionStubDataStore()
        )

        let message = makeMessage(
            text: "outgoing https://example.com/dog.png",
            direction: .outgoing
        )
        viewModel.appendMessageIfNew(message)
        viewModel.schedulePrefetchForOutgoingMessage(message, isChannelMessage: false)
        #expect(viewModel.messages.count == 1)

        let deadline = Date().addingTimeInterval(2)
        var probed: [URL] = []
        while Date() < deadline {
            probed = await imageCache.probedURLs
            if !probed.isEmpty { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
        #expect(probed.map(\.absoluteString) == ["https://example.com/dog.png"])
    }

    // MARK: - Helpers

    private func makeBoundViewModel() -> ChatViewModel {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator
        return viewModel
    }

    private func makeStore() -> InlineImageDimensionsStore {
        InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    }

    /// Installs the store through `configure` so the provider-backed
    /// `inlineImageDimensionsStore` property serves it, matching production wiring.
    private func bind(_ store: InlineImageDimensionsStore, to viewModel: ChatViewModel) {
        viewModel.configureForTesting(
            dependencies: .testDefaults(inlineImageDimensionsStore: { store })
        )
    }

    private func makeMessage(
        text: String,
        direction: MessageDirection = .incoming
    ) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            text: text,
            timestamp: 1_000,
            createdAt: Date(timeIntervalSince1970: 1_000),
            direction: direction,
            status: direction == .outgoing ? .pending : .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: direction == .incoming ? "Sender" : nil,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    private static func makeTempDimensionsURL() -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "ChatViewModelAdmissionTests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appending(path: "dimensions.json")
    }
}

// MARK: - Stubs

private actor SlowImageProber: InlineImageDimensionProbing {
    private let delay: Duration
    private(set) var probedURLs: [URL] = []

    init(delay: Duration) {
        self.delay = delay
    }

    func probeImageDimensions(url: URL) async -> CGSize? {
        probedURLs.append(url)
        try? await Task.sleep(for: delay)
        return nil
    }
}

private actor SlowLinkPreviewFetcher: LinkPreviewCaching {
    private let delay: Duration
    private(set) var fetchedURLs: [URL] = []

    init(delay: Duration) {
        self.delay = delay
    }

    func preview(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol,
        isChannelMessage: Bool
    ) async -> LinkPreviewResult {
        fetchedURLs.append(url)
        try? await Task.sleep(for: delay)
        return .noPreviewAvailable
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

private actor AdmissionStubDataStore: PersistenceStoreProtocol {

    // MARK: - Link Preview Data

    func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? { nil }
    func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {}

    // MARK: - Required Protocol Stubs

    func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool { false }
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

    func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {}
    func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {}
    func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {}
    func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] { [] }

    func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] { [] }
    func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? { nil }
    func fetchChannel(id: UUID) async throws -> ChannelDTO? { nil }
    @discardableResult func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID { UUID() }
    func saveChannel(_ dto: ChannelDTO) async throws {}
    func deleteChannel(id: UUID) async throws {}
    func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {}
    func incrementChannelUnreadCount(channelID: UUID) async throws {}
    func clearChannelUnreadCount(channelID: UUID) async throws {}
    func clearChannelUnreadCount(radioID: UUID, index: UInt8) async throws {}

    func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] { [] }
    func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? { nil }
    func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO {
        SavedTracePathDTO(id: UUID(), radioID: radioID, name: name, pathBytes: pathBytes, hashSize: hashSize, createdDate: Date(), runs: [])
    }
    func updateSavedTracePathName(id: UUID, name: String) async throws {}
    func deleteSavedTracePath(id: UUID) async throws {}
    func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {}

    func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String, withinSeconds: Int) async throws -> MessageDTO? { nil }
    func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {}
    func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] { [] }
    func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool { false }
    func incrementMessageHeardRepeats(id: UUID) async throws -> Int { 0 }
    func deleteMessageRepeats(messageID: UUID) async throws {}
    func incrementMessageSendCount(id: UUID) async throws -> Int { 0 }
    func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {}

    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}
    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] { [] }
    func countDebugLogEntries() async throws -> Int { 0 }
    func pruneDebugLogEntries(keepCount: Int) async throws {}
    func clearDebugLogEntries() async throws {}

    func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] { [:] }

    func findRxLogEntry(radioID: UUID, channelIndex: UInt8?, senderTimestamp: UInt32) async throws -> RxLogEntryDTO? { nil }
    func findRxLogEntryBySenderPrefix(radioID: UUID, senderPrefixByte: UInt8, receivedSince: Date) async throws -> RxLogEntryDTO? { nil }

    func saveRoomMessage(_ dto: RoomMessageDTO) async throws {}
    func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? { nil }
    func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] { [] }
    func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool { false }
    func updateRoomMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?) async throws {}
    func updateRoomMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
    func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws {}

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

    func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {}
    func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {}
    func fetchDevice(id: UUID) async throws -> DeviceDTO? { nil }
    func fetchDevice(radioID: UUID) async throws -> DeviceDTO? { nil }
    func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws {}
    func fetchRemoteNodeSession(id: UUID) async throws -> RemoteNodeSessionDTO? { nil }
    func fetchRemoteNodeSession(publicKey: Data) async throws -> RemoteNodeSessionDTO? { nil }
    func markSessionDisconnected(_ sessionID: UUID) async throws {}
    func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool { false }
    func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) async throws -> Bool { false }
    func markMessageAsRead(id: UUID) async throws {}
    func incrementPendingSendAttemptCount(messageID: UUID) async throws -> Int? { nil }
    func saveDevice(_ dto: DeviceDTO) async throws {}
    func fetchRemoteNodeSessionByPrefix(_ prefix: Data) async throws -> RemoteNodeSessionDTO? { nil }
    func fetchRemoteNodeSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] { [] }
    func fetchConnectedRemoteNodeSessions() async throws -> [RemoteNodeSessionDTO] { [] }
    func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) async throws {}
    func updateRemoteNodeSessionConnection(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel) async throws {}
    func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) async throws {}
    func deleteRemoteNodeSession(id: UUID) async throws {}
    func incrementRoomUnreadCount(_ sessionID: UUID) async throws {}
    func resetRoomUnreadCount(_ sessionID: UUID) async throws {}
    func findContactByPublicKey(_ publicKey: Data) async throws -> ContactDTO? { nil }
    func findContactNameByKeyPrefix(_ prefix: Data) async throws -> String? { nil }
    func saveRxLogEntry(_ dto: RxLogEntryDTO) async throws {}
    func fetchRxLogEntries(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO] { [] }
    func clearRxLogEntries(radioID: UUID) async throws {}
    func pruneRxLogEntries(radioID: UUID, keepCount: Int, pruneThreshold: Int) async throws {}
    func fetchEntriesWithMissingRegion(radioID: UUID) async throws -> [RxLogEntryDTO] { [] }
    func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) async throws -> [RxLogEntryDTO] { [] }
    func batchUpdateRxLogRegion(updates: [(id: UUID, regionScope: String?)]) async throws {}
    func batchUpdateRxLogDecryption(_ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]) async throws {}
    func batchUpdateChannelMessageRegion(radioID: UUID, updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)]) async throws {}
    func batchUpdateDMMessageRegion(radioID: UUID, updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)]) async throws {}

    func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {}

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

    func upsertPendingSend(_ dto: PendingSendDTO) async throws {}
    func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int { 0 }
    func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO] { [] }
    func deletePendingSend(id: UUID) async throws {}
    func deletePendingSendsForMessage(messageID: UUID) async throws {}
    func hasPendingSend(messageID: UUID) async throws -> Bool { false }
}
