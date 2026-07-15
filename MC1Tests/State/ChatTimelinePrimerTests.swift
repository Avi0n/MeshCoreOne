import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

/// Primer owns the sole `.prime` writer role and composes with the
/// `ChatCoordinator.bindWriter` seam: stale post-resume writes drop, binds deny
/// under an open interactive owner, and channel primes resolve senders through
/// contacts.
@Suite("ChatTimelinePrimer", .serialized)
@MainActor
struct ChatTimelinePrimerTests {
  // MARK: - Fixtures

  private func makeStore() throws -> PersistenceStore {
    // Unique in-memory containers: in-memory stores intern by URL.
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makePrimerDependencies(
    registry: ChatCoordinatorRegistry,
    dataStore: PersistenceStore
  ) -> ChatTimelinePrimer.Dependencies {
    ChatTimelinePrimer.Dependencies(
      registry: { registry },
      dataStore: { dataStore },
      reactionService: { nil },
      connectedDeviceNodeName: { nil },
      inlineImageDimensionsStore: { nil },
      prefetchDataStore: { nil }
    )
  }

  private func makeContact(
    radioID: UUID,
    id: UUID = UUID(),
    name: String = "TestContact",
    nickname: String? = nil,
    unreadCount: Int = 0
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nickname,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: unreadCount
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

  private func makeDirectMessage(
    radioID: UUID,
    contactID: UUID,
    timestamp: UInt32,
    text: String
  ) -> MessageDTO {
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

  private func makeChannelMessage(
    radioID: UUID,
    channelIndex: UInt8 = 0,
    timestamp: UInt32,
    text: String,
    senderNodeName: String
  ) -> MessageDTO {
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
      senderNodeName: senderNodeName,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  /// Binds `viewModel` to `coordinator` the way `bindCoordinator` does:
  /// writer and rebuild hooks installed as one act.
  private func bind(
    _ viewModel: ChatViewModel,
    to coordinator: ChatCoordinator,
    role: ChatWriterRole = .interactive
  ) {
    viewModel.attachCoordinator(coordinator)
    viewModel.timelineWriter = coordinator.bindWriter(
      owner: viewModel,
      role: role,
      renderItemRebuilder: { [weak viewModel] messageID in
        viewModel?.rebuildDisplayItem(for: messageID)
      },
      renderStateInvalidated: { [weak viewModel] in
        viewModel?.buildItems()
      }
    )
  }

  // MARK: - Tests

  @Test
  func `a primer resuming from a DB await after the live open has all its writes dropped`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let seed = makeDirectMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 1000,
      text: "seed"
    )
    try await dataStore.saveMessage(seed)

    let conversation = ChatConversationType.dm(contact)
    let coordinator = registry.coordinator(for: conversation.coordinatorID)

    let primer = ChatTimelinePrimer(
      dependencies: makePrimerDependencies(registry: registry, dataStore: dataStore),
      linkPreviewCache: nil
    )

    // Start the prime; yield so it binds `.prime` and suspends on the store hop.
    let primeTask = Task {
      await primer.prime(conversation, envInputs: .default)
    }
    await Task.yield()

    // Live open supersedes the prime writer.
    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)
    let liveWriter = try #require(liveVM.timelineWriter)
    #expect(liveWriter.isCurrent)

    let liveMessage = makeDirectMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 2000,
      text: "live"
    )
    liveWriter.replaceAll([liveMessage])
    liveVM.buildItems()
    await coordinator.buildItemsTask?.value

    let settledMessages = coordinator.messages
    let settledRenderState = coordinator.renderState
    let settledRenderStateID = coordinator.renderStateID

    await primeTask.value

    #expect(coordinator.messages == settledMessages)
    #expect(coordinator.renderState == settledRenderState)
    #expect(coordinator.renderStateID == settledRenderStateID)
  }

  @Test
  func `a channel prime resolves sender names through the contact table`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    let wireName = "AlphaNode"
    let nickname = "Alpha"
    let contact = makeContact(radioID: radioID, name: wireName, nickname: nickname)
    try await dataStore.saveChannel(channel)
    try await dataStore.saveContact(contact)

    let message = makeChannelMessage(
      radioID: radioID,
      channelIndex: channel.index,
      timestamp: 1000,
      text: "hello channel",
      senderNodeName: wireName
    )
    try await dataStore.saveMessage(message)

    let primer = ChatTimelinePrimer(
      dependencies: makePrimerDependencies(registry: registry, dataStore: dataStore),
      linkPreviewCache: nil
    )
    await primer.prime(.channel(channel), envInputs: .default)

    let coordinator = try #require(
      registry.existingCoordinator(for: .channel(radioID: radioID, channelIndex: channel.index))
    )
    await coordinator.buildItemsTask?.value

    let item = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(item.envelope.senderResolution.matchKind != .unresolved)
    #expect(item.envelope.senderResolution.displayName == wireName)
    #expect(item.envelope.senderResolution.unverifiedNickname == nickname)
  }

  @Test
  func `a reload draining while the primer holds the writer rebuilds the row`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    var message = makeDirectMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 1000,
      text: "seed"
    )
    message.status = .sending
    try await dataStore.saveMessage(message)

    let conversation = ChatConversationType.dm(contact)
    let primer = ChatTimelinePrimer(
      dependencies: makePrimerDependencies(registry: registry, dataStore: dataStore),
      linkPreviewCache: nil
    )
    await primer.prime(conversation, envInputs: .default)

    let coordinator = registry.coordinator(for: conversation.coordinatorID)
    await coordinator.buildItemsTask?.value
    let primed = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(primed.footer.status == .sending)

    // The status resolves in the store and a reload drains while the primer
    // still owns the writer: refreshing the DTO must also rebake its row.
    try await dataStore.updateMessageStatus(id: message.id, status: .delivered)
    coordinator.enqueueReload(messageID: message.id)
    await coordinator.coalescedReloadTask?.value

    #expect(coordinator.messagesByID[message.id]?.status == .delivered)
    let reloaded = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(
      reloaded.footer.status == .delivered,
      "a refreshed DTO must not leave the primed item stale"
    )
  }

  @Test
  func `a prime is denied while the conversation is open`() async throws {
    let dataStore = try makeStore()
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let message = makeDirectMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 1000,
      text: "already open"
    )
    try await dataStore.saveMessage(message)

    let conversation = ChatConversationType.dm(contact)
    let coordinator = registry.coordinator(for: conversation.coordinatorID)

    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)
    let liveWriter = try #require(liveVM.timelineWriter)
    liveWriter.replaceAll([message])
    liveVM.buildItems()
    await coordinator.buildItemsTask?.value

    let messagesBefore = coordinator.messages
    let renderStateIDBefore = coordinator.renderStateID

    let primer = ChatTimelinePrimer(
      dependencies: makePrimerDependencies(registry: registry, dataStore: dataStore),
      linkPreviewCache: nil
    )
    await primer.prime(conversation, envInputs: .default)

    #expect(coordinator.messages == messagesBefore)
    #expect(coordinator.renderStateID == renderStateIDBefore)
  }
}
