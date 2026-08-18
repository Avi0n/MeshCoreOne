import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Reconnect must refresh the open timeline in place instead of replacing it.
@Suite("Chat reconnect populate", .serialized)
@MainActor
struct ChatReconnectPopulateTests {
  @Test
  func `refreshWindow keeps the paged-in window`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: ChatCoordinator.pageSize + 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount
    let hasMoreBefore = env.viewModel.hasMoreMessages
    #expect(countBefore > ChatCoordinator.pageSize)
    #expect(hasMoreBefore)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )

    #expect(loaded)
    await env.viewModel.coordinator?.buildItemsTask?.value
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.map(\.id) == env.viewModel.renderState.items.map(\.id))
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
    #expect(env.viewModel.hasMoreMessages == hasMoreBefore)
  }

  @Test
  func `refreshWindow load still clears unread and returns success`() async throws {
    let env = try await makeLoadedEnvironment(unreadCount: 4)
    #expect(env.viewModel.messages.isEmpty == false)

    await env.viewModel.loadMessages(for: env.contact, populateMode: .refreshWindow)
    #expect(env.viewModel.errorMessage == nil)

    let updated = try await env.dataStore.fetchContact(id: env.contact.id)
    #expect(updated?.unreadCount == 0)
  }

  @Test
  func `replace mode replaces the paged-in window`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    #expect(env.viewModel.messages.count > ChatCoordinator.pageSize)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .replace
    )

    #expect(loaded)
    #expect(env.viewModel.messages.count == ChatCoordinator.pageSize)
    #expect(!env.viewModel.messages.contains { $0.id == oldestID })
  }

  @Test
  func `refreshWindow clears a stuck isLoadingOlder`() async throws {
    let env = try await makeLoadedEnvironment()
    env.viewModel.timeline.writer?.updateRenderState { $0.with(isLoadingOlder: true) }
    #expect(env.viewModel.isLoadingOlder)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )

    #expect(loaded)
    #expect(env.viewModel.isLoadingOlder == false)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize)
  }

  @Test
  func `first open of a primed coordinator runs populate and bakes the divider`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let unread = 3
    let contact = makeContact(radioID: radioID, unreadCount: unread)
    try await dataStore.saveContact(contact)
    for index in 0..<(ChatCoordinator.pageSize + unread) {
      try await dataStore.saveMessage(
        makeMessage(
          radioID: radioID,
          contactID: contact.id,
          timestamp: UInt32(1000 + index)
        )
      )
    }

    let coordinator = ChatCoordinator(
      conversationID: .dm(radioID: radioID, contactID: contact.id),
      dataStore: dataStore
    )

    // Warm the coordinator the way the navigation prefetch does: another
    // writer populates it before the view's own open has run.
    let primer = ChatViewModel()
    primer.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    primer.bindCoordinatorForTesting(coordinator)
    #expect(await primer.primeInitialMessages(for: contact, populateMode: .replace))

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    viewModel.bindCoordinatorForTesting(coordinator)
    viewModel.timeline.stageOpen(.dm(contact))
    #expect(viewModel.timeline.initialLoadSettled == false)

    let loaded = await viewModel.primeInitialMessages(for: contact, populateMode: .refreshWindow)

    #expect(loaded)
    #expect(viewModel.timeline.initialLoadSettled,
            "First open must run populate, not skip the warm window")
    let dividerID = try #require(viewModel.bake.newMessagesDividerMessageID,
                                 "The interactive open must bake the New Messages divider")
    await coordinator.buildItemsTask?.value
    #expect(viewModel.timeline.firstSnapshot == .present(target: dividerID),
            "A staged open with unread must present at the divider, not withhold")
  }

  @Test
  func `never-paired conversation open is unavailable`() async {
    let appState = AppState()
    defer { appState.shutdown() }
    appState.connectionManager.testForceNeverPaired = true

    #expect(appState.connectionManager.lastConnectedDeviceID == nil)
    #expect(appState.offlineDataStore == nil)
    #expect(appState.ensureChatCoordinatorRegistry() == nil)
    #expect(appState.chatCoordinatorRegistry == nil)

    let contact = makeContact(radioID: UUID(), unreadCount: 0)
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: appState.ensureChatCoordinatorRegistry(),
      conversation: .dm(contact)
    )
    viewModel.applyEnvInputs(.default)

    let loaded = await viewModel.primeInitialMessages(
      for: contact,
      populateMode: .refreshWindow
    )
    #expect(loaded == false)
    #expect(appState.chatCoordinatorRegistry == nil)
    #expect(viewModel.renderState.phase == .uninitialized)
  }

  @Test
  func `catch-up delivery admits once after refreshWindow`() async throws {
    let env = try await makeLoadedEnvironment()
    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)

    let incoming = makeMessage(
      radioID: env.contact.radioID,
      contactID: env.contact.id,
      timestamp: 9000
    )
    try await env.dataStore.saveMessage(incoming)

    await env.viewModel.handle(.directMessageReceived(message: incoming, contact: env.contact))
    #expect(env.viewModel.messages.contains { $0.id == incoming.id })
    let countAfterAdmit = env.viewModel.messages.count

    await env.viewModel.handle(.directMessageReceived(message: incoming, contact: env.contact))
    #expect(env.viewModel.messages.count == countAfterAdmit)
  }

  @Test
  func `refreshWindow keeps the paged-in channel window and sender tables`() async throws {
    let env = try await makeLoadedChannelEnvironment(extraOlderCount: ChatCoordinator.pageSize + 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount
    let hasMoreBefore = env.viewModel.hasMoreMessages
    let senderNamesBefore = env.viewModel.channelSenderNames
    let senderOrderBefore = env.viewModel.channelSenderOrder
    let oldestLoadedSender = try #require(env.viewModel.messages.first?.senderNodeName)
    #expect(countBefore > ChatCoordinator.pageSize)
    #expect(hasMoreBefore)
    #expect(senderNamesBefore.contains(oldestLoadedSender))

    let loaded = await env.viewModel.primeInitialChannelMessages(
      for: env.channel,
      populateMode: .refreshWindow
    )

    #expect(loaded)
    await env.viewModel.coordinator?.buildItemsTask?.value
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.map(\.id) == env.viewModel.renderState.items.map(\.id))
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
    #expect(env.viewModel.hasMoreMessages == hasMoreBefore)
    #expect(env.viewModel.channelSenderNames == senderNamesBefore)
    #expect(env.viewModel.channelSenderOrder == senderOrderBefore)
  }

  @Test
  func `replace mode replaces the paged-in channel window`() async throws {
    let env = try await makeLoadedChannelEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    #expect(env.viewModel.messages.count > ChatCoordinator.pageSize)
    #expect(env.viewModel.channelSenderNames.contains(Self.oldestChannelSenderName))

    let loaded = await env.viewModel.primeInitialChannelMessages(
      for: env.channel,
      populateMode: .replace
    )

    #expect(loaded)
    #expect(env.viewModel.messages.count == ChatCoordinator.pageSize)
    #expect(!env.viewModel.messages.contains { $0.id == oldestID })
    #expect(!env.viewModel.channelSenderNames.contains(Self.oldestChannelSenderName))
  }

  @Test
  func `refreshWindow reconciles a silent store status rewrite`() async throws {
    let env = try await makeLoadedEnvironment()
    let target = try #require(env.viewModel.messages.last)
    #expect(target.status == .delivered)

    try await env.dataStore.updateMessageStatus(id: target.id, status: .failed)
    #expect(env.viewModel.messagesByID[target.id]?.status == .delivered)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )

    #expect(loaded)
    #expect(env.viewModel.messagesByID[target.id]?.status == .failed)
  }

  @Test
  func `refreshWindow reindexes reactions on a fresh ReactionService`() async throws {
    let env = try await makeLoadedEnvironment()
    let target = try #require(env.viewModel.messages.last)
    let freshReactions = ReactionService()
    env.viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { env.dataStore },
        reactionService: { freshReactions }
      )
    )
    try env.viewModel.bindCoordinatorForTesting(#require(env.viewModel.coordinator))

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)

    let hash = ReactionParser.generateMessageHash(
      text: target.text,
      timestamp: target.reactionTimestamp
    )
    let found = await freshReactions.findDMTargetMessage(
      messageHash: hash,
      contactID: env.contact.id
    )
    #expect(found == target.id)
  }

  @Test
  func `failed populate then refreshWindow retries with the intact window`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount
    #expect(countBefore > ChatCoordinator.pageSize)

    env.viewModel.timeline.testPopulateError = PopulateTestError.fetchFailed
    defer { env.viewModel.timeline.testPopulateError = nil }

    let failed = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(failed == false)
    #expect(env.viewModel.errorMessage != nil)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)

    env.viewModel.timeline.testPopulateError = nil

    let retried = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(retried)
    #expect(env.viewModel.errorMessage == nil)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
  }

  @Test
  func `populate catch keeps counters so refreshWindow retries the full window`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount
    #expect(countBefore > ChatCoordinator.pageSize)

    env.viewModel.timeline.testPopulateFetchError = PopulateTestError.fetchFailed
    defer { env.viewModel.timeline.testPopulateFetchError = nil }

    let failed = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(failed == false)
    #expect(env.viewModel.errorMessage != nil)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)

    env.viewModel.timeline.testPopulateFetchError = nil

    let retried = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(retried)
    #expect(env.viewModel.errorMessage == nil)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
  }

  @Test
  func `cancelled populate keeps counters and does not set errorMessage`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount

    env.viewModel.timeline.testPopulateFetchError = CancellationError()
    defer { env.viewModel.timeline.testPopulateFetchError = nil }

    let cancelled = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(cancelled == false)
    #expect(env.viewModel.errorMessage == nil)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
  }

  @Test
  func `refreshWindow after newer store rows reports hasMoreMessages`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: ChatCoordinator.pageSize + 12)
    #expect(env.viewModel.hasMoreMessages == true)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize * 2)

    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    for index in 0..<ChatCoordinator.pageSize {
      try await env.dataStore.saveMessage(
        makeMessage(
          radioID: env.contact.radioID,
          contactID: env.contact.id,
          timestamp: newestLoaded + 1 + UInt32(index)
        )
      )
    }

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)
    #expect(env.viewModel.hasMoreMessages == true)
  }

  @Test
  func `loadOlder then refreshWindow serializes and keeps both pages`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: ChatCoordinator.pageSize * 2 + 12)
    let coordinator = try #require(env.viewModel.coordinator)
    let countBefore = env.viewModel.messages.count
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    #expect(env.viewModel.hasMoreMessages == true)

    let arrived = AsyncGate()
    let gate = AsyncGate()
    env.viewModel.timeline.loadOlderInterleaveHook = {
      await arrived.open()
      await gate.wait()
    }
    defer { env.viewModel.timeline.loadOlderInterleaveHook = nil }

    let olderTask = Task {
      await env.viewModel.loadOlderMessages()
    }
    await arrived.wait()

    let newer = makeMessage(
      radioID: env.contact.radioID,
      contactID: env.contact.id,
      timestamp: newestLoaded + 1
    )
    try await env.dataStore.saveMessage(newer)

    let populateTask = Task {
      await env.viewModel.primeInitialMessages(
        for: env.contact,
        populateMode: .refreshWindow
      )
    }

    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })

    await gate.open()
    await olderTask.value
    let loaded = await populateTask.value
    await coordinator.windowOperationTask?.value

    #expect(loaded)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.contains { $0.id == newer.id })
    #expect(env.viewModel.messages.count == countBefore + ChatCoordinator.pageSize + 1)
    #expect(env.viewModel.errorBannerMessage == nil)
    #expect(env.viewModel.isLoadingOlder == false)
  }

  @Test
  func `hardReset preserves the paged-in window so the next loadOlder abuts it`() async throws {
    let extraOlderCount = ChatCoordinator.pageSize + 12
    let env = try await makeLoadedEnvironment(extraOlderCount: extraOlderCount)
    let coordinator = try #require(env.viewModel.coordinator)
    let countBefore = env.viewModel.messages.count
    let fetchedBefore = env.viewModel.totalFetchedCount
    let oldestID = try #require(env.viewModel.messages.first?.id)
    #expect(fetchedBefore > ChatCoordinator.pageSize)

    coordinator.hardReset(reason: "test")
    await coordinator.hardResetTask?.value

    #expect(env.viewModel.totalFetchedCount == fetchedBefore)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.hasMoreMessages == true)

    await env.viewModel.loadOlderMessages()

    let expectedCount = ChatCoordinator.pageSize + extraOlderCount
    #expect(env.viewModel.messages.count == expectedCount)
    #expect(env.viewModel.totalFetchedCount == expectedCount)
    let timestamps = env.viewModel.messages.map(\.timestamp)
    #expect(timestamps == timestamps.sorted())
    #expect(env.viewModel.messages.first?.timestamp == 1000)
  }

  @Test
  func `refreshWindow after hidden outgoing reactions retains the oldest row`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let visibleCountBefore = env.viewModel.messages.count
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)

    let hiddenReactionCount = 2
    for index in 0..<hiddenReactionCount {
      try await env.dataStore.saveMessage(
        makeOutgoingReactionMessage(
          radioID: env.contact.radioID,
          contactID: env.contact.id,
          timestamp: newestLoaded + 1 + UInt32(index)
        )
      )
    }
    #expect(env.viewModel.messages.count == visibleCountBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)
    #expect(env.viewModel.messages.count == visibleCountBefore)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.allSatisfy { message in
      !env.viewModel.bake.isHiddenOutgoingReaction(message, isDM: true)
    })
  }

  @Test
  func `fully paged conversation keeps hasMoreMessages false across refreshWindow`() async throws {
    let extraOlderCount = 12
    let env = try await makeLoadedEnvironment(extraOlderCount: extraOlderCount)
    #expect(env.viewModel.hasMoreMessages == false)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize + extraOlderCount)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)
    #expect(env.viewModel.hasMoreMessages == false)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize + extraOlderCount)
  }

  @Test
  func `first open of exactly one page reports no further history`() async throws {
    let env = try await makeLoadedEnvironment()
    #expect(env.viewModel.messages.count == ChatCoordinator.pageSize)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize)
    #expect(env.viewModel.hasMoreMessages == false)
  }

  @Test
  func `refreshWindow catch-up of newer store rows keeps the oldest loaded row`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    let newerCount = 3
    var newerIDs: [UUID] = []
    for index in 0..<newerCount {
      let message = makeMessage(
        radioID: env.contact.radioID,
        contactID: env.contact.id,
        timestamp: newestLoaded + 1 + UInt32(index)
      )
      newerIDs.append(message.id)
      try await env.dataStore.saveMessage(message)
    }

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    for id in newerIDs {
      #expect(env.viewModel.messages.contains { $0.id == id })
    }
  }

  @Test
  func `exactly floorLimit plus one drops the probe and reports hasMore`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID, unreadCount: 0)
    try await dataStore.saveContact(contact)
    let total = ChatCoordinator.pageSize + 1
    for index in 0..<total {
      try await dataStore.saveMessage(
        makeMessage(
          radioID: radioID,
          contactID: contact.id,
          timestamp: UInt32(1000 + index)
        )
      )
    }

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    let coordinator = ChatCoordinator(
      conversationID: .dm(radioID: radioID, contactID: contact.id),
      dataStore: dataStore
    )
    viewModel.bindCoordinatorForTesting(coordinator)

    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .replace))
    #expect(viewModel.messages.count == ChatCoordinator.pageSize)
    #expect(viewModel.totalFetchedCount == ChatCoordinator.pageSize)
    #expect(viewModel.hasMoreMessages)
    #expect(viewModel.messages.first?.timestamp == 1001)

    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .refreshWindow))
    #expect(viewModel.messages.count == ChatCoordinator.pageSize)
    #expect(viewModel.totalFetchedCount == ChatCoordinator.pageSize)
    #expect(viewModel.hasMoreMessages)
    #expect(viewModel.messages.first?.timestamp == 1001)
  }

  @Test
  func `fully paged floorLimit plus one keeps every row and reports no more`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 1)
    #expect(env.viewModel.messages.count == ChatCoordinator.pageSize + 1)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize + 1)
    #expect(env.viewModel.hasMoreMessages == false)

    let loaded = await env.viewModel.primeInitialMessages(
      for: env.contact,
      populateMode: .refreshWindow
    )
    #expect(loaded)
    #expect(env.viewModel.messages.count == ChatCoordinator.pageSize + 1)
    #expect(env.viewModel.totalFetchedCount == ChatCoordinator.pageSize + 1)
    #expect(env.viewModel.hasMoreMessages == false)
  }

  @Test
  func `cancelled populate after a parked loadOlder commits nothing`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: ChatCoordinator.pageSize * 2 + 12)
    let coordinator = try #require(env.viewModel.coordinator)
    let countBefore = env.viewModel.messages.count
    let oldestID = try #require(env.viewModel.messages.first?.id)

    let arrived = AsyncGate()
    let gate = AsyncGate()
    env.viewModel.timeline.loadOlderInterleaveHook = {
      await arrived.open()
      await gate.wait()
    }
    defer { env.viewModel.timeline.loadOlderInterleaveHook = nil }

    let olderTask = Task {
      await env.viewModel.loadOlderMessages()
    }
    await arrived.wait()

    let populateTask = Task {
      await env.viewModel.timeline.open(
        .dm(env.contact),
        reactions: nil,
        populateMode: .refreshWindow
      )
    }
    #expect(env.viewModel.messages.count == countBefore)

    populateTask.cancel()
    await gate.open()
    await olderTask.value
    let outcome = await populateTask.value
    await coordinator.windowOperationTask?.value

    guard case .cancelled = outcome else {
      Issue.record("expected .cancelled, got \(outcome)")
      return
    }
    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.count == countBefore + ChatCoordinator.pageSize)
  }

  @Test
  func `cancelled populate after the window fetch commits nothing`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: 12)
    let coordinator = try #require(env.viewModel.coordinator)
    let countBefore = env.viewModel.messages.count
    let idsBefore = Set(env.viewModel.messages.map(\.id))
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    let newer = makeMessage(
      radioID: env.contact.radioID,
      contactID: env.contact.id,
      timestamp: newestLoaded + 1
    )
    try await env.dataStore.saveMessage(newer)

    let arrived = AsyncGate()
    let gate = AsyncGate()
    coordinator.testPopulateAfterFetchHook = {
      await arrived.open()
      await gate.wait()
    }
    defer { coordinator.testPopulateAfterFetchHook = nil }

    let populateTask = Task {
      await env.viewModel.timeline.open(
        .dm(env.contact),
        reactions: nil,
        populateMode: .refreshWindow
      )
    }
    await arrived.wait()
    #expect(env.viewModel.messages.count == countBefore)

    populateTask.cancel()
    await gate.open()
    let outcome = await populateTask.value
    await coordinator.windowOperationTask?.value

    guard case .cancelled = outcome else {
      Issue.record("expected .cancelled, got \(outcome)")
      return
    }
    #expect(!env.viewModel.messages.contains { $0.id == newer.id })
    #expect(Set(env.viewModel.messages.map(\.id)) == idsBefore)
    #expect(env.viewModel.messages.count == countBefore)
  }

  @Test
  func `cancelled hardReset does not replaceAll or reschedule coalesced reload`() async throws {
    let env = try await makeLoadedEnvironment(extraOlderCount: ChatCoordinator.pageSize + 12)
    let coordinator = try #require(env.viewModel.coordinator)
    let countBefore = env.viewModel.messages.count
    let idsBefore = Set(env.viewModel.messages.map(\.id))
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    let newer = makeMessage(
      radioID: env.contact.radioID,
      contactID: env.contact.id,
      timestamp: newestLoaded + 1
    )
    try await env.dataStore.saveMessage(newer)

    let arrived = AsyncGate()
    let gate = AsyncGate()
    coordinator.hardResetAfterFetchHook = {
      await arrived.open()
      await gate.wait()
    }
    defer { coordinator.hardResetAfterFetchHook = nil }

    let pendingID = UUID()
    coordinator.pendingReloadIDs.insert(pendingID)
    coordinator.hardReset(reason: "test cancel")
    await arrived.wait()
    coordinator.cancelInFlight()

    await gate.open()
    await coordinator.hardResetTask?.value

    #expect(!env.viewModel.messages.contains { $0.id == newer.id })
    #expect(Set(env.viewModel.messages.map(\.id)) == idsBefore)
    #expect(env.viewModel.messages.count == countBefore)
    #expect(coordinator.pendingReloadIDs.contains(pendingID))
    #expect(!coordinator.reloadInFlight)
    #expect(!coordinator.hardResetInFlight)
  }

  @Test
  func `hardReset hides outgoing reaction rows and keeps the unfiltered fetch count`() async throws {
    let env = try await makeLoadedEnvironment()
    let coordinator = try #require(env.viewModel.coordinator)
    let oldestID = try #require(env.viewModel.messages.first?.id)
    let visibleCountBefore = env.viewModel.messages.count
    let newestLoaded = try #require(env.viewModel.messages.last?.timestamp)
    try await env.dataStore.saveMessage(
      makeOutgoingReactionMessage(
        radioID: env.contact.radioID,
        contactID: env.contact.id,
        timestamp: newestLoaded + 1
      )
    )

    coordinator.hardReset(reason: "test")
    await coordinator.hardResetTask?.value

    #expect(env.viewModel.messages.contains { $0.id == oldestID })
    #expect(env.viewModel.messages.count == visibleCountBefore)
    #expect(env.viewModel.totalFetchedCount == visibleCountBefore + 1)
    #expect(env.viewModel.messages.allSatisfy { message in
      !env.viewModel.bake.isHiddenOutgoingReaction(message, isDM: true)
    })
  }

  // MARK: - Fixtures

  private static let oldestChannelSenderName = "OldestSender"

  private enum PopulateTestError: Error {
    case fetchFailed
  }

  private struct LoadedEnvironment {
    let dataStore: PersistenceStore
    let viewModel: ChatViewModel
    let contact: ContactDTO
  }

  private func makeLoadedEnvironment(
    extraOlderCount: Int = 0,
    unreadCount: Int = 0
  ) async throws -> LoadedEnvironment {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID, unreadCount: unreadCount)
    try await dataStore.saveContact(contact)

    let total = ChatCoordinator.pageSize + extraOlderCount
    for index in 0..<total {
      try await dataStore.saveMessage(
        makeMessage(
          radioID: radioID,
          contactID: contact.id,
          timestamp: UInt32(1000 + index)
        )
      )
    }

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    let coordinator = ChatCoordinator(
      conversationID: .dm(radioID: radioID, contactID: contact.id),
      dataStore: dataStore
    )
    viewModel.bindCoordinatorForTesting(coordinator)

    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .replace))
    if extraOlderCount > 0 {
      await viewModel.loadOlderMessages()
    }
    return LoadedEnvironment(dataStore: dataStore, viewModel: viewModel, contact: contact)
  }

  private struct LoadedChannelEnvironment {
    let viewModel: ChatViewModel
    let channel: ChannelDTO
  }

  private func makeLoadedChannelEnvironment(
    extraOlderCount: Int = 0
  ) async throws -> LoadedChannelEnvironment {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID)
    try await dataStore.saveChannel(channel)

    let total = ChatCoordinator.pageSize + extraOlderCount
    for index in 0..<total {
      let senderName = index == 0 ? Self.oldestChannelSenderName : "Sender\(index % 3)"
      try await dataStore.saveMessage(
        makeChannelMessage(
          radioID: radioID,
          channelIndex: channel.index,
          timestamp: UInt32(1000 + index),
          senderName: senderName
        )
      )
    }

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    let coordinator = ChatCoordinator(
      conversationID: .channel(radioID: radioID, channelIndex: channel.index),
      dataStore: dataStore
    )
    viewModel.bindCoordinatorForTesting(coordinator)

    #expect(await viewModel.primeInitialChannelMessages(for: channel, populateMode: .replace))
    if extraOlderCount > 0 {
      await viewModel.loadOlderMessages()
    }
    return LoadedChannelEnvironment(viewModel: viewModel, channel: channel)
  }
}

private func makeContact(radioID: UUID, unreadCount: Int) -> ContactDTO {
  ContactDTO(
    id: UUID(),
    radioID: radioID,
    publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
    name: "TestContact",
    typeRawValue: ContactType.chat.rawValue,
    flags: 0,
    outPathLength: 2,
    outPath: Data([0x01, 0x02]),
    lastAdvertTimestamp: 0,
    latitude: 0,
    longitude: 0,
    lastModified: 0,
    lastHeardTimestamp: nil,
    nickname: nil,
    isBlocked: false,
    isMuted: false,
    isFavorite: false,
    lastMessageDate: Date(),
    unreadCount: unreadCount
  )
}

private func makeChannel(radioID: UUID) -> ChannelDTO {
  ChannelDTO(
    id: UUID(),
    radioID: radioID,
    index: 3,
    name: "General",
    secret: Data(),
    isEnabled: true,
    lastMessageDate: Date(),
    unreadCount: 0,
    unreadMentionCount: 0,
    notificationLevel: .all,
    isFavorite: false
  )
}

private func makeChannelMessage(
  radioID: UUID,
  channelIndex: UInt8,
  timestamp: UInt32,
  senderName: String
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: nil,
    channelIndex: channelIndex,
    text: "channel \(timestamp)",
    timestamp: timestamp,
    createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
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

private func makeOutgoingReactionMessage(
  radioID: UUID,
  contactID: UUID,
  timestamp: UInt32
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: contactID,
    channelIndex: nil,
    text: "👍\nABCDEFGH",
    timestamp: timestamp,
    createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
    direction: .outgoing,
    status: .sent,
    textType: .plain,
    ackCode: nil,
    pathLength: 0,
    snr: nil,
    senderKeyPrefix: nil,
    senderNodeName: nil,
    isRead: true,
    replyToID: nil,
    roundTripTime: nil,
    heardRepeats: 0,
    retryAttempt: 0,
    maxRetryAttempts: 0
  )
}

private func makeMessage(radioID: UUID, contactID: UUID, timestamp: UInt32) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: contactID,
    channelIndex: nil,
    text: "message \(timestamp)",
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

/// Parks `loadOlder` between its fetch and first write.
private actor AsyncGate {
  private var waiter: CheckedContinuation<Void, Never>?
  private var opened = false

  func wait() async {
    if opened { return }
    await withCheckedContinuation { waiter = $0 }
  }

  func open() {
    opened = true
    waiter?.resume()
    waiter = nil
  }
}
