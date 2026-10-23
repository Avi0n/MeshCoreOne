import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
struct ChatTimelineOpeningCancellationTests {
  private enum Metrics {
    static let unreadCount = 1
    static let readCount = 1
    static let timestamp: UInt32 = 1_700_000_000
    static let channelIndex: UInt8 = 1
  }

  enum Cancellation: Sendable {
    case fetchError
    case task
  }

  enum Retry: Sendable {
    case loaded
    case failed
    case noUnread
  }

  private enum FetchError: Error {
    case failed
  }

  @Test(arguments: [
    (Cancellation.fetchError, Retry.loaded),
    (.task, .loaded),
    (.fetchError, .failed),
    (.fetchError, .noUnread),
  ])
  func `cancelled first unread open withholds cached items until a terminal retry`(
    cancellation: Cancellation, retry: Retry
  ) async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    try await store.saveChannel(channel)
    for index in 0..<(Metrics.readCount + Metrics.unreadCount) {
      let timestamp = Metrics.timestamp + UInt32(index)
      try await store.saveMessage(MessageDTO(from: Message(
        radioID: radioID, channelIndex: channel.index, text: "Message \(index)", timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        isRead: index < Metrics.readCount
      )))
    }
    let coordinator = ChatCoordinator(
      conversationID: .channel(radioID: radioID, channelIndex: channel.index), dataStore: store
    )
    let prime = ChatTimeline(role: .prime)
    prime.bind(coordinator, dataStore: { store }, senderTables: { .empty }, postApply: nil)
    let primeOutcome = await prime.open(.channel(channel), reactions: nil, populateMode: .replace)
    guard case .loaded = primeOutcome else {
      Issue.record("The closed conversation must be primed before testing its first open")
      return
    }
    await coordinator.buildItemsTask?.value
    prime.releaseWriter()

    let timeline = ChatTimeline(role: .interactive)
    timeline.stageOpen(.channel(channel))
    timeline.bind(coordinator, dataStore: { store }, senderTables: { .empty }, postApply: nil)
    try #require(timeline.items.isEmpty == false)
    try #require(timeline.firstSnapshot == .withhold)

    switch cancellation {
    case .fetchError:
      timeline.testPopulateFetchError = CancellationError()
    case .task:
      coordinator.testPopulateAfterFetchHook = {
        withUnsafeCurrentTask { $0?.cancel() }
      }
    }
    let cancelled = await Task {
      let outcome = await timeline.open(.channel(channel), reactions: nil, populateMode: .refreshWindow)
      if case .cancelled = outcome { return true }
      return false
    }.value

    try #require(cancelled)
    #expect(timeline.firstSnapshot == .withhold,
            "A cancelled first open must not present cached items at the bottom before its retry")

    timeline.testPopulateFetchError = nil
    coordinator.testPopulateAfterFetchHook = nil
    switch retry {
    case .loaded:
      break
    case .failed:
      timeline.testPopulateFetchError = FetchError.failed
    case .noUnread:
      try await store.clearChannelUnreadCount(channelID: channel.id)
    }
    let retryOutcome = await timeline.open(.channel(channel), reactions: nil, populateMode: .refreshWindow)
    switch (retry, retryOutcome) {
    case (.loaded, .loaded), (.noUnread, .loaded), (.failed, .failed):
      break
    default:
      Issue.record("The retry must reach its expected terminal outcome")
      return
    }
    await coordinator.buildItemsTask?.value
    switch retry {
    case .loaded:
      let dividerID = try #require(timeline.bake.newMessagesDividerMessageID)
      #expect(timeline.firstSnapshot == .present(target: dividerID))
      timeline.consumeAnchor()

      let settledSnapshot = timeline.firstSnapshot
      let refreshCancelled = await Task {
        withUnsafeCurrentTask { $0?.cancel() }
        let outcome = await timeline.open(.channel(channel), reactions: nil, populateMode: .refreshWindow)
        if case .cancelled = outcome { return true }
        return false
      }.value
      #expect(refreshCancelled)
      #expect(timeline.initialLoadSettled)
      #expect(timeline.firstSnapshot == settledSnapshot)
    case .failed, .noUnread:
      #expect(timeline.firstSnapshot == .present(target: nil))
    }
  }

  @Test(arguments: [false, true])
  func `unbound entry settles only when its task has not been cancelled`(cancelled: Bool) async {
    let channel = makeChannel(radioID: UUID())
    let timeline = ChatTimeline(role: .interactive)
    timeline.stageOpen(.channel(channel))
    await Task {
      if cancelled {
        withUnsafeCurrentTask { $0?.cancel() }
      }
      let outcome = await timeline.open(.channel(channel), reactions: nil, populateMode: .refreshWindow)
      switch (cancelled, outcome) {
      case (true, .cancelled), (false, .unavailable):
        break
      default:
        Issue.record("Cancelled entry must be distinguished from an unavailable writer")
      }
    }.value

    #expect(timeline.firstSnapshot == (cancelled ? .withhold : .present(target: nil)))
  }

  private func makeChannel(radioID: UUID) -> ChannelDTO {
    ChannelDTO(
      id: UUID(), radioID: radioID, index: Metrics.channelIndex, name: "Fixture", secret: Data(),
      isEnabled: true, lastMessageDate: nil, unreadCount: Metrics.unreadCount
    )
  }
}
