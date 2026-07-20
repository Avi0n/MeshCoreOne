import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

/// Freshness guarantees for the shared populate path and the arrival-time
/// prewarm chain.
///
/// The populate tests pin that `ChatTimelinePopulator` bakes the divider from
/// the store's current unread count, not the navigation-time DTO's, which can
/// be stale by the time populate runs.
///
/// The hook-chain tests exercise the production
/// `AppState.ensureChatPrewarmRefresher()` wiring end to end; the unit tests
/// in ChatPrewarmRefresherTests inject test hooks and cannot catch a
/// production-wiring failure.
@Suite("ChatTimelineFreshness", .serialized)
@MainActor
struct ChatTimelineFreshnessTests {
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

  private func makeChannel(radioID: UUID, index: UInt8 = 0) -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: index,
      name: "TestChannel",
      secret: Data(),
      isEnabled: true,
      lastMessageDate: Date(),
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false
    )
  }

  private func makeChannelMessage(radioID: UUID, channelIndex: UInt8 = 0, timestamp: UInt32, text: String) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: nil,
      channelIndex: channelIndex,
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
      senderNodeName: "Sender",
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private final class WriterOwner {}

  // MARK: - Populate unread-count freshness

  @Test
  func `cold-open populate bakes the divider from the store's fresh unread count, not the pushed DTO`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let contactID = UUID()

    // One read message, one that arrived during the push transition.
    try await dataStore.saveMessage(makeDirectMessage(radioID: radioID, contactID: contactID, timestamp: 1000, text: "old"))
    let newMessage = makeDirectMessage(radioID: radioID, contactID: contactID, timestamp: 2000, text: "new")
    try await dataStore.saveMessage(newMessage)
    // The store's current truth: one unread.
    try await dataStore.saveContact(makeContact(radioID: radioID, id: contactID, unreadCount: 1))

    // The DTO the row was tapped with, captured before the arrival: zero unread.
    let staleContact = makeContact(radioID: radioID, id: contactID, unreadCount: 0)

    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let owner = WriterOwner()
    let writer = try #require(coordinator.bindWriter(owner: owner, role: .prime))
    let bake = ChatMessageBakeState()

    let outcome = await ChatTimelinePopulator.populate(
      .dm(staleContact),
      writer: writer,
      dataStore: dataStore,
      bake: bake,
      envInputs: .default,
      senderTables: .empty,
      reactions: nil,
      postApply: nil
    )
    guard case .loaded = outcome else {
      Issue.record("populate outcome was \(outcome), expected .loaded")
      return
    }

    await coordinator.buildItemsTask?.value
    let dividerItemID = coordinator.renderState.items.first { $0.grouping.showNewMessagesDivider }?.id
    #expect(dividerItemID == newMessage.id)
  }

  // MARK: - Production hook chain, arrival-time refresh

  @Test
  func `production AppState hook chain refreshes a warm closed channel on arrival`() async throws {
    let appState = AppState()
    appState.connectionManager.testLastConnectedDeviceID = UUID()
    let store = try #require(appState.offlineDataStore)

    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    try await store.saveChannel(channel)
    try await store.saveMessage(makeChannelMessage(radioID: radioID, timestamp: 1000, text: "old"))

    // The chats list rendered at least once: env snapshot captured.
    _ = appState.chatEnvInputs(
      for: .channel(channel),
      themeID: "default",
      isDark: false,
      isHighContrast: false,
      contentSizeCategory: "L"
    )

    // Warm the coordinator the way the navigation-time prefetch does.
    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let primer = ChatTimelinePrimer(
      dependencies: appState.makeChatTimelinePrimerDependencies(),
      linkPreviewCache: nil
    )
    await primer.prime(.channel(channel), envInputs: .default)

    let id = ChatConversationID.channel(radioID: radioID, channelIndex: channel.index)
    let coordinator = try #require(registry.existingCoordinator(for: id))
    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.messages.count == 1)

    // Message arrives while the chat is closed.
    let newMessage = makeChannelMessage(radioID: radioID, timestamp: 2000, text: "new")
    try await store.saveMessage(newMessage)

    let refresher = appState.ensureChatPrewarmRefresher()
    refresher.noteChannelMessage(radioID: radioID, channelIndex: channel.index)

    // The schedule gates must have passed for an entry to exist at all.
    let task = try #require(refresher.inFlight[id])
    await task.value

    #expect(coordinator.messages.count == 2)
    #expect(coordinator.messages.last?.id == newMessage.id)
  }

  @Test
  func `production AppState hook chain refreshes a warm closed DM on arrival`() async throws {
    let appState = AppState()
    appState.connectionManager.testLastConnectedDeviceID = UUID()
    let store = try #require(appState.offlineDataStore)

    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await store.saveContact(contact)
    try await store.saveMessage(makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 1000, text: "old"))

    _ = appState.chatEnvInputs(
      for: .dm(contact),
      themeID: "default",
      isDark: false,
      isHighContrast: false,
      contentSizeCategory: "L"
    )

    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let primer = ChatTimelinePrimer(
      dependencies: appState.makeChatTimelinePrimerDependencies(),
      linkPreviewCache: nil
    )
    await primer.prime(.dm(contact), envInputs: .default)

    let id = ChatConversationID.dm(radioID: radioID, contactID: contact.id)
    let coordinator = try #require(registry.existingCoordinator(for: id))
    #expect(coordinator.renderState.phase == .loaded)

    let newMessage = makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 2000, text: "new")
    try await store.saveMessage(newMessage)

    let refresher = appState.ensureChatPrewarmRefresher()
    refresher.noteDirectMessage(contact: contact)

    let task = try #require(refresher.inFlight[id])
    await task.value

    #expect(coordinator.messages.count == 2)
    #expect(coordinator.messages.last?.id == newMessage.id)
  }

  // MARK: - Writer release on close

  @Test
  func `a still-alive interactive owner cannot starve the arrival refresh after close`() async throws {
    let appState = AppState()
    appState.connectionManager.testLastConnectedDeviceID = UUID()
    let store = try #require(appState.offlineDataStore)

    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await store.saveContact(contact)
    try await store.saveMessage(makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 1000, text: "old"))

    _ = appState.chatEnvInputs(
      for: .dm(contact),
      themeID: "default",
      isDark: false,
      isHighContrast: false,
      contentSizeCategory: "L"
    )

    let registry = try #require(appState.ensureChatCoordinatorRegistry())

    // A live open: the interactive view model binds the writer and populates.
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: .dm(contact)
    )
    viewModel.applyEnvInputs(.default)
    await viewModel.primeInitialMessages(for: contact)

    let id = ChatConversationID.dm(radioID: radioID, contactID: contact.id)
    let coordinator = try #require(registry.existingCoordinator(for: id))
    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.messages.count == 1)

    // The chat closes while the view model stays strongly referenced (SwiftUI
    // can keep a popped destination's state alive), so only the explicit
    // release can vacate the writer slot.
    viewModel.releaseTimelineWriter()

    let newMessage = makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 2000, text: "new")
    try await store.saveMessage(newMessage)

    let refresher = appState.ensureChatPrewarmRefresher()
    refresher.noteDirectMessage(contact: contact)

    let task = try #require(refresher.inFlight[id])
    await task.value

    #expect(coordinator.messages.count == 2)
    #expect(coordinator.messages.last?.id == newMessage.id)
    withExtendedLifetime(viewModel) {}
  }
}
