import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

/// Exercises the arrival-time refresh of warm chat coordinators. A coordinator
/// prewarmed while its chat is closed must not keep its prime-time tail:
/// reopening would render the stale list on the first frame and the fresh
/// fetch would land as an offset-preserving tail append, leaving the view
/// scrolled above the new messages instead of at the bottom.
@Suite("ChatPrewarmRefresher", .serialized)
@MainActor
struct ChatPrewarmRefresherTests {
  // MARK: - Fixtures

  private func makeStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeDependencies(dataStore: PersistenceStore) -> ChatViewModel.Dependencies {
    ChatViewModel.Dependencies(
      dataStore: { dataStore },
      messageService: { nil },
      notificationService: { nil },
      channelService: { nil },
      roomServerService: { nil },
      contactService: { nil },
      syncCoordinator: { nil },
      connectionState: { .disconnected },
      connectedDevice: { nil },
      currentRadioID: { nil },
      session: { nil },
      reactionService: { nil },
      chatSendQueueService: { nil },
      inlineImageDimensionsStore: { nil },
      prefetchDataStore: { nil }
    )
  }

  private func makeHooks(
    registry: ChatCoordinatorRegistry,
    dataStore: PersistenceStore,
    isActive: @escaping @MainActor (ChatPrewarmRefresher.ConversationKind) -> Bool = { _ in false }
  ) -> ChatPrewarmRefresher.Hooks {
    ChatPrewarmRefresher.Hooks(
      registry: { registry },
      dependencies: { [self] in makeDependencies(dataStore: dataStore) },
      envInputs: { _ in .default },
      isConversationActive: isActive,
      channel: { radioID, index in
        await (try? dataStore.fetchChannel(radioID: radioID, index: index)) ?? nil
      },
      linkPreviewCache: { nil }
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

  private func makeContact(radioID: UUID) -> ContactDTO {
    ContactDTO(
      id: UUID(),
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
      unreadCount: 0
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

  /// Warms the shared coordinator the way `AppState.prefetchConversation` does:
  /// a throwaway view model primes it, then is discarded.
  private func warmCoordinator(
    registry: ChatCoordinatorRegistry,
    dataStore: PersistenceStore,
    conversation: ChatConversationType
  ) async {
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: makeDependencies(dataStore: dataStore),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: conversation
    )
    viewModel.applyEnvInputs(.default)
    switch conversation {
    case let .dm(contact):
      await viewModel.primeInitialMessages(for: contact)
    case let .channel(channel):
      await viewModel.primeInitialChannelMessages(for: channel)
    }
  }

  // MARK: - Tests

  @Test
  func `channel arrival re-primes a warm coordinator with the new tail`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    try await dataStore.saveChannel(channel)
    try await dataStore.saveMessage(makeChannelMessage(radioID: radioID, timestamp: 1000, text: "old"))

    await warmCoordinator(registry: registry, dataStore: dataStore, conversation: .channel(channel))
    let id = ChatConversationID.channel(radioID: radioID, channelIndex: channel.index)
    let coordinator = try #require(registry.existingCoordinator(for: id))
    #expect(coordinator.messages.count == 1)

    // Message arrives while the chat is closed.
    let newMessage = makeChannelMessage(radioID: radioID, timestamp: 2000, text: "new")
    try await dataStore.saveMessage(newMessage)

    let refresher = ChatPrewarmRefresher(
      hooks: makeHooks(registry: registry, dataStore: dataStore),
      debounce: .zero
    )
    refresher.noteChannelMessage(radioID: radioID, channelIndex: channel.index)
    // A second arrival in the same window coalesces onto the scheduled refresh.
    refresher.noteChannelMessage(radioID: radioID, channelIndex: channel.index)
    #expect(refresher.inFlight.count == 1)

    let task = try #require(refresher.inFlight[id])
    await task.value

    #expect(coordinator.messages.count == 2)
    #expect(coordinator.messages.last?.id == newMessage.id)
    await coordinator.buildItemsTask?.value
    #expect(coordinator.renderState.items.contains { $0.id == newMessage.id })
    #expect(refresher.inFlight.isEmpty)
  }

  @Test
  func `direct-message arrival re-primes a warm coordinator with the new tail`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await dataStore.saveMessage(makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 1000, text: "old"))

    await warmCoordinator(registry: registry, dataStore: dataStore, conversation: .dm(contact))
    let id = ChatConversationID.dm(radioID: radioID, contactID: contact.id)
    let coordinator = try #require(registry.existingCoordinator(for: id))
    #expect(coordinator.messages.count == 1)

    let newMessage = makeDirectMessage(radioID: radioID, contactID: contact.id, timestamp: 2000, text: "new")
    try await dataStore.saveMessage(newMessage)

    let refresher = ChatPrewarmRefresher(
      hooks: makeHooks(registry: registry, dataStore: dataStore),
      debounce: .zero
    )
    refresher.noteDirectMessage(contact: contact)

    let task = try #require(refresher.inFlight[id])
    await task.value

    #expect(coordinator.messages.count == 2)
    #expect(coordinator.messages.last?.id == newMessage.id)
  }

  @Test
  func `an open conversation is not refreshed`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    try await dataStore.saveChannel(channel)
    try await dataStore.saveMessage(makeChannelMessage(radioID: radioID, timestamp: 1000, text: "old"))

    await warmCoordinator(registry: registry, dataStore: dataStore, conversation: .channel(channel))
    let id = ChatConversationID.channel(radioID: radioID, channelIndex: channel.index)
    let coordinator = try #require(registry.existingCoordinator(for: id))

    try await dataStore.saveMessage(makeChannelMessage(radioID: radioID, timestamp: 2000, text: "new"))

    let refresher = ChatPrewarmRefresher(
      hooks: makeHooks(registry: registry, dataStore: dataStore, isActive: { _ in true }),
      debounce: .zero
    )
    refresher.noteChannelMessage(radioID: radioID, channelIndex: channel.index)

    #expect(refresher.inFlight.isEmpty)
    #expect(coordinator.messages.count == 1)
  }

  @Test
  func `a cold conversation is ignored and no coordinator is created`() throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()

    let refresher = ChatPrewarmRefresher(
      hooks: makeHooks(registry: registry, dataStore: dataStore),
      debounce: .zero
    )
    refresher.noteChannelMessage(radioID: radioID, channelIndex: 0)

    #expect(refresher.inFlight.isEmpty)
    #expect(registry.existingCoordinator(for: .channel(radioID: radioID, channelIndex: 0)) == nil)
  }
}
