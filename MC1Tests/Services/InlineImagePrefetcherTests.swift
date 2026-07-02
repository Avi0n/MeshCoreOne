import CoreGraphics
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("InlineImagePrefetcher Tests")
@MainActor
struct InlineImagePrefetcherTests {
  // MARK: - URL extraction

  @Test
  func `Text with no URLs returns immediately without probes or previews`() async {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    await prefetcher.prefetch(urlsIn: "hello world, no links here", isChannelMessage: false)

    let probeCalls = await imageCache.probedURLs
    let previewCalls = await linkCache.fetchedURLs
    #expect(probeCalls.isEmpty)
    #expect(previewCalls.isEmpty)
  }

  @Test
  func `Direct image suffix invokes the dimension probe path`() async {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    await prefetcher.prefetch(
      urlsIn: "look at https://example.com/cat.png",
      isChannelMessage: false
    )

    let probeCalls = await imageCache.probedURLs
    let previewCalls = await linkCache.fetchedURLs
    #expect(probeCalls.map(\.absoluteString) == ["https://example.com/cat.png"])
    #expect(previewCalls.isEmpty)
  }

  // MARK: - Parallel fan-out

  @Test
  func `Multiple URLs fan out across both classifier paths`() async {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    await prefetcher.prefetch(
      urlsIn: "image https://example.com/cat.png and article https://example.com/article",
      isChannelMessage: false
    )

    let probeCalls = await imageCache.probedURLs
    let previewCalls = await linkCache.fetchedURLs
    #expect(probeCalls.map(\.absoluteString) == ["https://example.com/cat.png"])
    #expect(previewCalls.map(\.absoluteString) == ["https://example.com/article"])
  }

  // MARK: - Skip-if-cached

  @Test
  func `Direct image probe is skipped when dimensions are already cached`() async throws {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let cachedURL = try #require(URL(string: "https://example.com/cat.png"))
    await store.save(url: cachedURL, size: CGSize(width: 200, height: 100))

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    await prefetcher.prefetch(
      urlsIn: "look at https://example.com/cat.png",
      isChannelMessage: false
    )

    let probeCalls = await imageCache.probedURLs
    #expect(probeCalls.isEmpty)
  }

  // MARK: - Mixed direct image + link preview

  @Test
  func `Mixed direct image and link preview URLs both invoke their paths`() async {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    await prefetcher.prefetch(
      urlsIn: "see https://example.com/cat.jpg then read https://news.example.com/post",
      isChannelMessage: true
    )

    let probeCalls = await imageCache.probedURLs
    let previewCalls = await linkCache.fetchedURLs
    #expect(probeCalls.count == 1)
    #expect(probeCalls.first?.absoluteString == "https://example.com/cat.jpg")
    #expect(previewCalls.count == 1)
    #expect(previewCalls.first?.absoluteString == "https://news.example.com/post")

    let channelFlags = await linkCache.fetchedChannelFlags
    #expect(channelFlags == [true])
  }

  // MARK: - Giphy hosting page routes to probe

  @Test
  func `Giphy hosting URL routes to probe path with resolved direct image URL`() async throws {
    let imageCache = StubImageProber()
    let linkCache = StubLinkPreviewFetcher()
    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    let dataStore = StubDataStore()

    let prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    let hostingURL = try #require(URL(string: "https://giphy.com/gifs/abc123"))
    let expectedProbeURL = ImageURLClassifier.directImageURL(for: hostingURL)

    await prefetcher.prefetch(
      urlsIn: "look at \(hostingURL.absoluteString)",
      isChannelMessage: false
    )

    let probeCalls = await imageCache.probedURLs
    let previewCalls = await linkCache.fetchedURLs
    #expect(probeCalls.map(\.absoluteString) == [expectedProbeURL.absoluteString])
    #expect(previewCalls.isEmpty)
  }

  // MARK: - Helpers

  private static func makeTempDimensionsURL() -> URL {
    let directory = FileManager.default.temporaryDirectory
      .appending(path: "InlineImagePrefetcherTests-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.appending(path: "dimensions.json")
  }
}

// MARK: - Stubs

private actor StubImageProber: InlineImageDimensionProbing {
  private(set) var probedURLs: [URL] = []

  func probeImageDimensions(url: URL) async -> CGSize? {
    probedURLs.append(url)
    return nil
  }
}

private actor StubLinkPreviewFetcher: LinkPreviewCaching {
  private(set) var fetchedURLs: [URL] = []
  private(set) var fetchedChannelFlags: [Bool] = []

  func preview(
    for url: URL,
    using dataStore: any PersistenceStoreProtocol,
    isChannelMessage: Bool
  ) async -> LinkPreviewResult {
    fetchedURLs.append(url)
    fetchedChannelFlags.append(isChannelMessage)
    return .noPreviewAvailable
  }

  func manualFetch(
    for url: URL,
    using dataStore: any PersistenceStoreProtocol
  ) async -> LinkPreviewResult {
    .noPreviewAvailable
  }

  func isFetching(_ url: URL) async -> Bool {
    false
  }

  func cachedPreview(for url: URL) async -> LinkPreviewDataDTO? {
    nil
  }
}

private actor StubDataStore: PersistenceStoreProtocol {
  // MARK: - Link Preview Data

  func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? {
    nil
  }

  func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {}

  // MARK: - Required Protocol Stubs

  func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool {
    false
  }

  func saveMessage(_ dto: MessageDTO) async throws {}
  func fetchMessage(id: UUID) async throws -> MessageDTO? {
    nil
  }

  func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] {
    [:]
  }

  func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] {
    [:]
  }

  func updateMessageStatus(id: UUID, status: MessageStatus) async throws {}
  func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {}
  func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
  func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {}
  func updateMessageLinkPreview(id: UUID, url: String?, title: String?, imageData: Data?, iconData: Data?, fetched: Bool) throws {}

  func fetchContacts(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func fetchConversations(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func fetchContact(id: UUID) async throws -> ContactDTO? {
    nil
  }

  func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
    nil
  }

  func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO? {
    nil
  }

  @discardableResult func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> UUID {
    UUID()
  }

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
  func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID] {
    []
  }

  func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID] {
    []
  }

  func deleteMessagesForContact(contactID: UUID) async throws {}
  func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {}
  func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {}
  func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {}
  func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] {
    []
  }

  func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] {
    []
  }

  func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? {
    nil
  }

  func fetchChannel(id: UUID) async throws -> ChannelDTO? {
    nil
  }

  @discardableResult func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID {
    UUID()
  }

  func saveChannel(_ dto: ChannelDTO) async throws {}
  func deleteChannel(id: UUID) async throws {}
  func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {}
  func incrementChannelUnreadCount(channelID: UUID) async throws {}
  func clearChannelUnreadCount(channelID: UUID) async throws {}
  func clearChannelUnreadCount(radioID: UUID, index: UInt8) async throws {}

  func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] {
    []
  }

  func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? {
    nil
  }

  func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO {
    SavedTracePathDTO(id: UUID(), radioID: radioID, name: name, pathBytes: pathBytes, hashSize: hashSize, createdDate: Date(), runs: [])
  }

  func updateSavedTracePathName(id: UUID, name: String) async throws {}
  func deleteSavedTracePath(id: UUID) async throws {}
  func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {}

  func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String) async throws -> MessageDTO? {
    nil
  }

  func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {}
  func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] {
    []
  }

  func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool {
    false
  }

  func incrementMessageHeardRepeats(id: UUID) async throws -> Int {
    0
  }

  func deleteMessageRepeats(messageID: UUID) async throws {}
  func incrementMessageSendCount(id: UUID) async throws -> Int {
    0
  }

  func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {}

  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}
  func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
    []
  }

  func countDebugLogEntries() async throws -> Int {
    0
  }

  func pruneDebugLogEntries(keepCount: Int) async throws {}
  func clearDebugLogEntries() async throws {}

  func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] {
    [:]
  }

  func findRxLogEntry(radioID: UUID, channelIndex: UInt8?, senderTimestamp: UInt32) async throws -> RxLogEntryDTO? {
    nil
  }

  func findRxLogEntryBySenderPrefix(radioID: UUID, senderPrefixByte: UInt8, receivedSince: Date) async throws -> RxLogEntryDTO? {
    nil
  }

  func saveRoomMessage(_ dto: RoomMessageDTO) async throws {}
  func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? {
    nil
  }

  func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] {
    []
  }

  func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool {
    false
  }

  func updateRoomMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?) async throws {}
  func updateRoomMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
  func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws {}

  func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
    fatalError("Not implemented")
  }

  func setInboundHopCount(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) async throws {}
  func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO] {
    []
  }

  func deleteDiscoveredNode(id: UUID) async throws {}
  func clearDiscoveredNodes(radioID: UUID) async throws {}
  func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data> {
    Set()
  }

  func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO] {
    []
  }

  func saveReaction(_ dto: ReactionDTO) async throws {}
  func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool {
    false
  }

  func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws {}
  func deleteReactionsForMessage(messageID: UUID) async throws {}
  func findChannelMessageForReaction(radioID: UUID, channelIndex: UInt8, parsedReaction: ParsedReaction, localNodeName: String?, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? {
    nil
  }

  func fetchChannelMessageCandidates(radioID: UUID, channelIndex: UInt8, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchDMMessageCandidates(radioID: UUID, contactID: UUID, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] {
    []
  }

  func findDMMessageForReaction(radioID: UUID, contactID: UUID, messageHash: String, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? {
    nil
  }

  func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {}
  func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {}
  func fetchDevice(id: UUID) async throws -> DeviceDTO? {
    nil
  }

  func fetchDevice(radioID: UUID) async throws -> DeviceDTO? {
    nil
  }

  func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws {}
  func fetchRemoteNodeSession(id: UUID) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func fetchRemoteNodeSession(publicKey: Data) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func markSessionDisconnected(_ sessionID: UUID) async throws {}
  func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool {
    false
  }

  func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) async throws -> Bool {
    false
  }

  func clearRetryingToSent(id: UUID) async throws -> Bool {
    false
  }

  func hasOutgoingSentDM(ackCode: UInt32) async throws -> Bool {
    false
  }

  func markMessageAsRead(id: UUID) async throws {}
  func incrementPendingSendAttemptCount(messageID: UUID) async throws -> Int? {
    nil
  }

  func saveDevice(_ dto: DeviceDTO) async throws {}
  func fetchRemoteNodeSessionByPrefix(_ prefix: Data) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func fetchRemoteNodeSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
    []
  }

  func fetchConnectedRemoteNodeSessions() async throws -> [RemoteNodeSessionDTO] {
    []
  }

  func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) async throws {}
  func updateRemoteNodeSessionConnection(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel) async throws {}
  func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) async throws {}
  func deleteRemoteNodeSession(id: UUID) async throws {}
  func incrementRoomUnreadCount(_ sessionID: UUID) async throws {}
  func resetRoomUnreadCount(_ sessionID: UUID) async throws {}
  func findContactByPublicKey(_ publicKey: Data) async throws -> ContactDTO? {
    nil
  }

  func findContactNameByKeyPrefix(_ prefix: Data) async throws -> String? {
    nil
  }

  func saveRxLogEntry(_ dto: RxLogEntryDTO) async throws {}
  func fetchRxLogEntries(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO] {
    []
  }

  func clearRxLogEntries(radioID: UUID) async throws {}
  func pruneRxLogEntries(radioID: UUID, keepCount: Int, pruneThreshold: Int) async throws {}
  func fetchEntriesWithMissingRegion(radioID: UUID) async throws -> [RxLogEntryDTO] {
    []
  }

  func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) async throws -> [RxLogEntryDTO] {
    []
  }

  func batchUpdateRxLogRegion(updates: [(id: UUID, regionScope: String?)]) async throws {}
  func batchUpdateRxLogDecryption(_ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]) async throws {}
  func batchUpdateChannelMessageRegion(radioID: UUID, updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)]) async throws {}
  func batchUpdateDMMessageRegion(radioID: UUID, updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)]) async throws {}

  func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {}

  // swiftlint:disable:next function_parameter_count
  func saveNodeStatusSnapshot(
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16?,
    postPushCount: UInt16?
  ) async throws -> UUID {
    UUID()
  }

  func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? {
    nil
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] {
    []
  }

  func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
  func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
  func recordNodeStatusSnapshot(nodePublicKey: Data, status: NodeStatusMetrics?, telemetry: [TelemetrySnapshotEntry]?, neighbors: [NeighborSnapshotEntry]?) async throws -> UUID {
    UUID()
  }

  func saveTelemetryOnlySnapshot(nodePublicKey: Data, telemetryEntries: [TelemetrySnapshotEntry]) async throws -> UUID {
    UUID()
  }

  func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}

  func upsertPendingSend(_ dto: PendingSendDTO) async throws {}
  func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int {
    0
  }

  func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO] {
    []
  }

  func deletePendingSend(id: UUID) async throws {}
  func deletePendingSendsForMessage(messageID: UUID) async throws {}
  func hasPendingSend(messageID: UUID) async throws -> Bool {
    false
  }
}
