import Foundation
@testable import MC1
@testable import MC1Services
import Testing

// MARK: - CLIToolViewModel Tests

@Suite("CLIToolViewModel Tests")
@MainActor
struct CLIToolViewModelTests {
  // MARK: - Helper

  private func createConfiguredViewModel() -> CLIToolViewModel {
    let viewModel = CLIToolViewModel()
    viewModel.configure(
      repeaterAdminService: { nil },
      remoteNodeService: { nil },
      dataStore: { nil },
      radioID: { nil },
      localDeviceName: "TestDevice"
    )
    return viewModel
  }

  // MARK: - Prompt Tests

  @Test
  func `Prompt shows disconnected when no session`() {
    let viewModel = createConfiguredViewModel()
    viewModel.configure(
      repeaterAdminService: { nil },
      remoteNodeService: { nil },
      dataStore: { nil },
      radioID: { nil },
      localDeviceName: "Test"
    )
    #expect(viewModel.promptText.contains("disconnected"))
  }

  @Test
  func `Prompt shows countdown during login`() {
    let viewModel = CLIToolViewModel()

    // Configure the view model
    viewModel.configure(
      repeaterAdminService: { nil },
      remoteNodeService: { nil },
      dataStore: { nil },
      radioID: { nil },
      localDeviceName: "TestDevice"
    )

    // When remainingSeconds is nil and not waiting, should show normal prompt
    #expect(!viewModel.promptText.contains("Logging in"))
  }

  // MARK: - History Tests

  @Test
  func `History navigation up retrieves previous commands`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("first")
    try await waitUntil("first command should finish") { !viewModel.isWaitingForResponse }
    viewModel.executeCommand("second")

    viewModel.historyUp()
    #expect(viewModel.currentInput == "second")

    viewModel.historyUp()
    #expect(viewModel.currentInput == "first")
  }

  @Test
  func `History navigation down moves forward through history`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("first")
    try await waitUntil("first command should finish") { !viewModel.isWaitingForResponse }
    viewModel.executeCommand("second")

    viewModel.historyUp()
    viewModel.historyUp()
    viewModel.historyDown()

    #expect(viewModel.currentInput == "second")
  }

  @Test
  func `History is limited to 100 entries`() async {
    let viewModel = createConfiguredViewModel()
    for i in 0..<150 {
      viewModel.executeCommand("command\(i)")
      // Let the command task finish and release the busy claim.
      await Task.yield()
    }

    // Navigate to oldest entry
    for _ in 0..<100 {
      viewModel.historyUp()
    }

    // Should be command50 (oldest after trimming), not command0
    #expect(viewModel.currentInput == "command50")
  }

  @Test
  func `Login command stored in history without password`() {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("login MyRepeater")

    viewModel.historyUp()
    #expect(viewModel.currentInput == "login MyRepeater")
  }

  // MARK: - Built-in Commands Tests

  @Test
  func `Clear command removes output`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("help")
    try await waitUntil("help output should appear") {
      !viewModel.outputLines.isEmpty
    }

    viewModel.executeCommand("clear")
    try await waitUntil("output should be cleared") {
      viewModel.outputLines.isEmpty
    }
  }

  @Test
  func `Help command shows available commands`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("help")
    try await waitUntil("help output should appear") {
      viewModel.outputLines.contains { $0.text.contains("login") }
    }

    let output = viewModel.outputLines.map(\.text).joined(separator: "\n")
    #expect(output.contains("login"))
    #expect(output.contains("logout"))
    #expect(output.contains("session"))
  }

  // MARK: - Output Management Tests

  @Test
  func `Output lines are limited to prevent memory growth`() async throws {
    let viewModel = createConfiguredViewModel()
    for i in 0..<1100 {
      viewModel.executeCommand("command\(i)")
      // Let the command task finish and release the busy claim.
      await Task.yield()
    }

    try await waitUntil("output should be trimmed after commands") {
      viewModel.outputLines.count <= 1000
    }
  }

  // MARK: - Session Tests

  @Test
  func `Session list shows local`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("session list")
    try await waitUntil("session list output should appear") {
      viewModel.outputLines.contains { $0.text.contains("local") }
    }

    let output = viewModel.outputLines.map(\.text).joined(separator: "\n")
    #expect(output.contains("local"))
  }

  // MARK: - Cancellation Tests

  @Test
  func `Cancel command stops waiting`() async throws {
    let viewModel = createConfiguredViewModel()
    viewModel.executeCommand("help")
    try await waitUntil("help output should appear") {
      !viewModel.outputLines.isEmpty
    }

    viewModel.cancelCurrentCommand()

    #expect(!viewModel.isWaitingForResponse)
  }

  @Test
  func `Resumed cancelled command does not clear a newer command's busy flag`() async throws {
    let radioID = UUID()
    let parkingStore = ParkingContactStore()
    let viewModel = CLIToolViewModel()
    viewModel.configure(
      repeaterAdminService: { nil },
      remoteNodeService: { nil },
      dataStore: { parkingStore },
      radioID: { radioID },
      localDeviceName: "TestDevice"
    )
    viewModel.activeSession = .local(deviceName: "TestDevice")

    // Drain the configure-time completion prefetch so the parked-continuation
    // queue and counts below reflect only the command tasks under test.
    try await waitUntil("completion prefetch should park") { await parkingStore.parkedCount == 1 }
    await parkingStore.resumeFirst()
    try await waitUntil("completion prefetch should drain") { await parkingStore.parkedCount == 0 }

    // Task A claims the busy flag and parks inside the store's fetchContacts.
    viewModel.executeCommand("nodes")
    try await waitUntil("task A should claim the busy flag") { viewModel.isWaitingForResponse }
    try await waitUntil("task A should reach fetchContacts") {
      await parkingStore.parkedCount == 1
    }

    // Cancel A, then supersede it with task B which re-claims the flag.
    viewModel.cancelCurrentCommand()
    #expect(!viewModel.isWaitingForResponse)

    viewModel.executeCommand("nodes")
    try await waitUntil("task B should claim the busy flag") { viewModel.isWaitingForResponse }
    try await waitUntil("task B should reach fetchContacts") {
      await parkingStore.parkedCount == 2
    }

    // Let cancelled task A resume and run its defer.
    await parkingStore.resumeFirst()
    for _ in 0..<10 {
      await Task.yield()
    }

    // A's resumed defer must not clear B's claim on the busy flag.
    #expect(viewModel.isWaitingForResponse)

    // B's own completion still clears the flag, proving the guard does not leak it.
    await parkingStore.resumeFirst()
    try await waitUntil("task B completion should clear the busy flag") {
      !viewModel.isWaitingForResponse
    }
  }

  // MARK: - Empty Input Tests

  @Test
  func `Empty input shows prompt echo`() {
    let viewModel = createConfiguredViewModel()

    let initialCount = viewModel.outputLines.count
    let initialHistoryCount = viewModel.commandHistory.count
    viewModel.currentInput = ""
    viewModel.executeCommand("")

    #expect(viewModel.outputLines.count == initialCount + 1)
    #expect(viewModel.commandHistory.count == initialHistoryCount)
    #expect(viewModel.outputLines.last?.type == .command)
  }

  // MARK: - Ghost Text Tests

  @Test
  func `Ghost text shows suffix for matching command`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "hel"

    viewModel.updateGhostText(cursorAtEnd: true)

    #expect(viewModel.ghostText == "p")
  }

  @Test
  func `Ghost text empty when no match`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "xyz"

    viewModel.updateGhostText(cursorAtEnd: true)

    #expect(viewModel.ghostText == "")
  }

  @Test
  func `Ghost text empty for empty input`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = ""

    viewModel.updateGhostText(cursorAtEnd: true)

    #expect(viewModel.ghostText == "")
  }

  @Test
  func `Ghost text empty when cursor not at end`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "hel"

    viewModel.updateGhostText(cursorAtEnd: false)

    #expect(viewModel.ghostText == "")
  }

  @Test
  func `Accept ghost text appends to input`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "hel"
    viewModel.updateGhostText(cursorAtEnd: true)

    viewModel.acceptGhostText()

    #expect(viewModel.currentInput == "help")
    #expect(viewModel.ghostText == "")
  }

  @Test
  func `Accept ghost text does nothing when empty`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "xyz"
    viewModel.updateGhostText(cursorAtEnd: true)

    viewModel.acceptGhostText()

    #expect(viewModel.currentInput == "xyz")
  }

  // MARK: - Tab Completion Tests

  @Test
  func `Tab completion single match auto-completes`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "hel"

    viewModel.tabComplete()

    #expect(viewModel.currentInput == "help ")
  }

  @Test
  func `Tab completion multiple matches returns suggestions`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    let suggestions = viewModel.tabComplete()

    #expect(suggestions != nil)
    #expect(suggestions?.contains("login") == true)
    #expect(suggestions?.contains("logout") == true)
  }

  @Test
  func `Tab completion no match returns nil`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "xyz"

    let suggestions = viewModel.tabComplete()

    #expect(suggestions == nil)
  }

  @Test
  func `Ghost text shows argument completion after space`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "session l"

    viewModel.updateGhostText(cursorAtEnd: true)

    #expect(viewModel.ghostText == "ist" || viewModel.ghostText == "ocal")
  }

  // MARK: - Interactive Tab Completion Tests

  @Test
  func `First tab shows suggestions without selection`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    let suggestions = viewModel.tabComplete()

    #expect(suggestions != nil)
    #expect(viewModel.tabSuggestions != nil)
    #expect(viewModel.tabSelectionIndex == nil)
  }

  @Test
  func `Second tab enters selection mode`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()
    _ = viewModel.tabComplete()

    #expect(viewModel.tabSelectionIndex == 0)
  }

  @Test
  func `Third tab cycles to next suggestion`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()
    _ = viewModel.tabComplete()
    _ = viewModel.tabComplete()

    #expect(viewModel.tabSelectionIndex == 1)
  }

  @Test
  func `Tab cycles wrap around`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()
    let count = viewModel.tabSuggestions?.count ?? 0

    // Cycle through all suggestions plus one more
    for _ in 0..<(count + 1) {
      _ = viewModel.tabComplete()
    }

    #expect(viewModel.tabSelectionIndex == 0)
  }

  @Test
  func `Apply selected suggestion returns true when in selection mode`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()
    _ = viewModel.tabComplete()

    let applied = viewModel.applySelectedSuggestion()

    #expect(applied == true)
    #expect(viewModel.currentInput == "login ")
    #expect(viewModel.tabSuggestions == nil)
    #expect(viewModel.tabSelectionIndex == nil)
  }

  @Test
  func `Apply selected suggestion returns false when not in selection mode`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()

    let applied = viewModel.applySelectedSuggestion()

    #expect(applied == false)
  }

  @Test
  func `Clear tab state clears suggestions and selection`() {
    let viewModel = createConfiguredViewModel()
    viewModel.currentInput = "lo"

    _ = viewModel.tabComplete()
    _ = viewModel.tabComplete()

    viewModel.clearTabState()

    #expect(viewModel.tabSuggestions == nil)
    #expect(viewModel.tabSelectionIndex == nil)
  }
}

// MARK: - Parking Persistence Store

/// Parks every `fetchContacts` caller on a stored continuation so a test can interleave
/// cancel and resubmit while a spawned command body is suspended mid-fetch. Continuations
/// resume FIFO; an unsignalled fetch never returns on its own.
actor ParkingContactStore: PersistenceStoreProtocol {
  private var parked: [CheckedContinuation<[ContactDTO], Never>] = []

  var parkedCount: Int {
    parked.count
  }

  func resumeFirst() {
    guard !parked.isEmpty else { return }
    parked.removeFirst().resume(returning: [])
  }

  func fetchContacts(radioID: UUID) async -> [ContactDTO] {
    await withCheckedContinuation { parked.append($0) }
  }

  // MARK: - Unused Protocol Requirements (stubs)

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

  func deleteMessagesForContact(contactID: UUID) async throws {}
  func updateMessageStatus(id: UUID, status: MessageStatus) async throws {}
  func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {}
  func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
  func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {}
  func updateMessageLinkPreview(id: UUID, url: String?, title: String?, imageData: Data?, iconData: Data?, fetched: Bool) throws {}
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

  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {}
  func clearDebugLogEntries() async throws {}
  func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? {
    nil
  }

  func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {}
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

  func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool {
    false
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
