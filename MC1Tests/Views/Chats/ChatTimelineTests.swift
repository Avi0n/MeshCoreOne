import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Interface tests for `ChatTimeline`: the open/paging sequence exercised
/// through the module's own surface, with a real in-memory store: no view
/// model, no provider bundle.
@Suite("ChatTimeline interface")
@MainActor
struct ChatTimelineTests {
  // MARK: - Fixtures

  private func makeStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeContact(radioID: UUID, id: UUID = UUID(), unreadCount: Int = 0) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: unreadCount
    )
  }

  private func makeDirectMessage(radioID: UUID, contactID: UUID, timestamp: UInt32, text: String) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: text,
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      direction: .incoming,
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

  /// A bound interactive timeline over a fresh registry coordinator.
  private func makeBoundTimeline(
    dataStore: PersistenceStore,
    conversationID: ChatConversationID
  ) -> ChatTimeline {
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let timeline = ChatTimeline(role: .interactive)
    timeline.bind(
      registry.coordinator(for: conversationID),
      dataStore: { dataStore },
      senderTables: { .empty },
      postApply: nil
    )
    return timeline
  }

  // MARK: - Open

  @Test
  func `open on an unbound timeline reports unavailable`() async {
    let timeline = ChatTimeline(role: .interactive)
    let contact = makeContact(radioID: UUID())
    let outcome = await timeline.open(.dm(contact), reactions: nil)
    guard case .unavailable = outcome else {
      Issue.record("expected .unavailable, got \(outcome)")
      return
    }
  }

  @Test
  func `open loads the newest page and marks further history available`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let total = ChatCoordinator.pageSize + 10
    for offset in 0..<total {
      try await dataStore.saveMessage(makeDirectMessage(
        radioID: radioID, contactID: contact.id,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      ))
    }

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    let outcome = await timeline.open(.dm(contact), reactions: nil)

    guard case .loaded = outcome else {
      Issue.record("expected .loaded, got \(outcome)")
      return
    }
    #expect(timeline.messages.count == ChatCoordinator.pageSize)
    #expect(timeline.messages.first?.text == "m10")
    #expect(timeline.renderState.hasMoreMessages)
  }

  // MARK: - Paging

  @Test
  func `loadOlder prepends the older page in order and ends history on a short page`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let total = ChatCoordinator.pageSize + 10
    for offset in 0..<total {
      try await dataStore.saveMessage(makeDirectMessage(
        radioID: radioID, contactID: contact.id,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      ))
    }

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)

    let older = try await timeline.loadOlder()

    #expect(older.count == 10)
    #expect(timeline.messages.count == total)
    #expect(timeline.messages.first?.text == "m0")
    let timestamps = timeline.messages.map(\.timestamp)
    #expect(timestamps == timestamps.sorted())
    #expect(!timeline.renderState.hasMoreMessages)
    #expect(!timeline.renderState.isLoadingOlder)
  }

  @Test
  func `loadOlder retires the spinner and bakes the prepended rows`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let total = ChatCoordinator.pageSize + 3
    for offset in 0..<total {
      try await dataStore.saveMessage(makeDirectMessage(
        radioID: radioID, contactID: contact.id,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      ))
    }

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)
    _ = try await timeline.loadOlder()

    #expect(!timeline.renderState.isLoadingOlder)
    await timeline.coordinator?.buildItemsTask?.value
    #expect(timeline.items.count == total)
    #expect(timeline.items.first?.id == timeline.messages.first?.id)
  }

  @Test
  func `loadOlder drops rows an in-flight admission already landed`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let total = ChatCoordinator.pageSize + 5
    var seeded: [MessageDTO] = []
    for offset in 0..<total {
      let message = makeDirectMessage(
        radioID: radioID, contactID: contact.id,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      )
      seeded.append(message)
      try await dataStore.saveMessage(message)
    }

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)

    // A row from the older page lands via the live event path first.
    let raced = seeded[2]
    timeline.writer?.append(raced)

    let older = try await timeline.loadOlder()

    #expect(!older.contains { $0.id == raced.id })
    #expect(timeline.messages.count(where: { $0.id == raced.id }) == 1)
  }

  @Test
  func `loadOlder is a no-op at end of history`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    for offset in 0..<3 {
      try await dataStore.saveMessage(makeDirectMessage(
        radioID: radioID, contactID: contact.id,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      ))
    }

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)
    #expect(!timeline.renderState.hasMoreMessages)

    let older = try await timeline.loadOlder()
    #expect(older.isEmpty)
    #expect(timeline.messages.count == 3)
  }

  // MARK: - Admission

  @Test
  func `admit appends the message and its baked row in one call frame`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)

    let message = makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 2000, text: "hello")
    #expect(timeline.admit(message))

    #expect(timeline.messages.last?.id == message.id)
    #expect(timeline.items.last?.id == message.id)
  }

  @Test
  func `admit dedupes a message already in the loaded window`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let seeded = makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 1000, text: "seed")
    try await dataStore.saveMessage(seeded)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)
    #expect(timeline.messages.count == 1)

    #expect(!timeline.admit(seeded))
    #expect(timeline.messages.count == 1)
  }

  @Test
  func `admit no-ops on an unbound timeline`() {
    let timeline = ChatTimeline(role: .interactive)
    let message = makeDirectMessage(radioID: UUID(), contactID: UUID(), timestamp: 1000, text: "x")
    #expect(!timeline.admit(message))
    #expect(timeline.messages.isEmpty)
  }

  // MARK: - Open anchor

  @Test
  func `a staged open with no unread presents at the bottom immediately`() {
    let timeline = ChatTimeline(role: .interactive)
    let contact = makeContact(radioID: UUID(), unreadCount: 0)
    timeline.stageOpen(.dm(contact))
    #expect(timeline.firstSnapshot == .present(target: nil))
  }

  @Test
  func `a reopened warm timeline withholds until this session's divider resolves, then anchors on it`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contactID = UUID()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))

    var unread: MessageDTO?
    for offset in 0..<5 {
      let message = makeDirectMessage(
        radioID: radioID, contactID: contactID,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      )
      if offset == 4 { unread = message }
      try await dataStore.saveMessage(message)
    }

    // First session: everything read; open, position, close.
    try await dataStore.saveContact(makeContact(radioID: radioID, id: contactID, unreadCount: 0))
    let firstSession = ChatTimeline(role: .interactive)
    let readContact = makeContact(radioID: radioID, id: contactID, unreadCount: 0)
    firstSession.stageOpen(.dm(readContact))
    firstSession.bind(coordinator, dataStore: { dataStore }, senderTables: { .empty }, postApply: nil)
    _ = await firstSession.open(.dm(readContact), reactions: nil)
    await coordinator.buildItemsTask?.value
    firstSession.releaseWriter()
    #expect(!coordinator.renderState.items.isEmpty)

    // One message becomes unread while the chat is closed.
    try await dataStore.saveContact(makeContact(radioID: radioID, id: contactID, unreadCount: 1))

    // Second session over the same warm coordinator: items are already on
    // screen, but the anchor must wait for this session's divider.
    let secondSession = ChatTimeline(role: .interactive)
    let unreadContact = makeContact(radioID: radioID, id: contactID, unreadCount: 1)
    secondSession.stageOpen(.dm(unreadContact))
    #expect(secondSession.firstSnapshot == .withhold)

    secondSession.bind(coordinator, dataStore: { dataStore }, senderTables: { .empty }, postApply: nil)
    #expect(secondSession.firstSnapshot == .withhold)

    _ = await secondSession.open(.dm(unreadContact), reactions: nil)
    await coordinator.buildItemsTask?.value
    let unreadID = try #require(unread?.id)
    #expect(secondSession.firstSnapshot == .present(target: unreadID))

    secondSession.consumeAnchor()
    #expect(secondSession.firstSnapshot == .present(target: nil))
  }

  @Test
  func `an open that bakes no divider settles instead of withholding forever`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contactID = UUID()
    try await dataStore.saveMessage(makeDirectMessage(
      radioID: radioID, contactID: contactID, timestamp: 1000, text: "m0"
    ))
    // The store's truth: nothing unread; the pushed DTO is stale.
    try await dataStore.saveContact(makeContact(radioID: radioID, id: contactID, unreadCount: 0))
    let staleContact = makeContact(radioID: radioID, id: contactID, unreadCount: 2)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contactID)
    )
    timeline.stageOpen(.dm(staleContact))
    #expect(timeline.firstSnapshot == .withhold)

    _ = await timeline.open(.dm(staleContact), reactions: nil)
    #expect(timeline.firstSnapshot == .present(target: nil))
  }

  @Test
  func `an open staged with no unread backlog never bakes a late divider`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contactID = UUID()
    for offset in 0..<5 {
      try await dataStore.saveMessage(makeDirectMessage(
        radioID: radioID, contactID: contactID,
        timestamp: UInt32(1000 + offset), text: "m\(offset)"
      ))
    }
    // Store reports three unread; pushed DTO reads zero, so the gate presents
    // at the bottom before populate runs.
    try await dataStore.saveContact(makeContact(radioID: radioID, id: contactID, unreadCount: 3))
    let staleContact = makeContact(radioID: radioID, id: contactID, unreadCount: 0)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contactID)
    )
    timeline.stageOpen(.dm(staleContact))
    #expect(timeline.firstSnapshot == .present(target: nil))

    _ = await timeline.open(.dm(staleContact), reactions: nil)
    await timeline.coordinator?.buildItemsTask?.value

    // A late divider from the store's fresher count would only grow a row
    // already presented, so this open stays divider-free.
    #expect(timeline.bake.newMessagesDividerMessageID == nil)
    #expect(!timeline.items.contains { $0.grouping.showNewMessagesDivider })
    #expect(timeline.firstSnapshot == .present(target: nil))
  }

  // MARK: - Bake updates

  @Test
  func `resetOrphanedLoading returns only a loading row to idle`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let message = makeDirectMessage(
      radioID: radioID, contactID: contact.id,
      timestamp: 1000, text: "see https://example.com/page"
    )
    try await dataStore.saveMessage(message)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)

    timeline.apply(.previewState(messageID: message.id, state: .loading))
    timeline.apply(.resetOrphanedLoading(messageID: message.id))
    #expect(timeline.bake.previewStates[message.id] == .idle)

    timeline.apply(.previewState(messageID: message.id, state: .noPreview))
    timeline.apply(.resetOrphanedLoading(messageID: message.id))
    #expect(timeline.bake.previewStates[message.id] == .noPreview)
  }

  @Test
  func `urlServesPage reroutes loading twins sharing the URL`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let url = try #require(URL(string: "https://example.com/shot.png"))
    let first = makeDirectMessage(
      radioID: radioID, contactID: contact.id, timestamp: 1000, text: url.absoluteString
    )
    let twin = makeDirectMessage(
      radioID: radioID, contactID: contact.id, timestamp: 1001, text: url.absoluteString
    )
    try await dataStore.saveMessage(first)
    try await dataStore.saveMessage(twin)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)

    timeline.apply(.previewState(messageID: first.id, state: .loading))
    timeline.apply(.previewState(messageID: twin.id, state: .loading))
    timeline.apply(.urlServesPage(messageID: first.id, url: url, reroute: .idle))

    #expect(timeline.bake.previewStates[first.id] == .idle)
    #expect(timeline.bake.previewStates[twin.id] == .idle)
    #expect(timeline.bake.imageURLsServingPages.contains(url.absoluteString))
  }

  @Test
  func `clearBakeState drops per-message bake state`() async throws {
    let dataStore = try makeStore()
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let message = makeDirectMessage(
      radioID: radioID, contactID: contact.id,
      timestamp: 1000, text: "https://example.com/article"
    )
    try await dataStore.saveMessage(message)

    let timeline = makeBoundTimeline(
      dataStore: dataStore,
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    _ = await timeline.open(.dm(contact), reactions: nil)
    timeline.apply(.previewState(messageID: message.id, state: .loading))
    #expect(!timeline.bake.cachedURLs.isEmpty)

    timeline.clearBakeState()

    #expect(timeline.bake.previewStates.isEmpty)
    #expect(timeline.bake.cachedURLs.isEmpty)
    #expect(timeline.bake.loadedPreviews.isEmpty)
  }
}
