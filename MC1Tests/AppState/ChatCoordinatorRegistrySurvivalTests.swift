import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// The registry on `AppState` keeps the same coordinator across disconnect
/// and reconnect. `clear()` empties entries on restore and forget.
@MainActor
@Suite("Chat coordinator registry survival", .serialized)
struct ChatCoordinatorRegistrySurvivalTests {
  @Test
  func `disconnect then reconnect wiring keeps the same coordinator instance`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    appState.connectionManager.testLastConnectedDeviceID = UUID()

    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let conversationID = ChatConversationID.dm(radioID: UUID(), contactID: UUID())
    let original = registry.coordinator(for: conversationID)

    await appState.disconnect()

    #expect(appState.chatCoordinatorRegistry === registry)
    #expect(appState.chatCoordinatorRegistry?.existingCoordinator(for: conversationID) === original)

    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport())
    )
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      session: services.session,
      connectedDevice: DeviceDTO(from: Device(
        radioID: UUID(), publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: "Fixture"
      ))
    )
    await appState.wireServicesIfConnected()

    #expect(appState.chatCoordinatorRegistry === registry)
    #expect(appState.chatCoordinatorRegistry?.existingCoordinator(for: conversationID) === original)
  }

  @Test
  func `connection loss refreshes an open channel timeline to the committed send`() async throws {
    let suiteName = "test.loss-timeline.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    let radioID = UUID()
    appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Fixture")
    let store = try #require(appState.offlineDataStore)
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: 2, name: "Ops",
      secret: Data(repeating: 0x5A, count: 16), isEnabled: true,
      lastMessageDate: nil, unreadCount: 0
    )
    let message = MessageDTO(
      id: UUID(), radioID: radioID, contactID: nil, channelIndex: channel.index,
      text: "kept", timestamp: 1_700_000_000,
      createdAt: Date(timeIntervalSince1970: 1_700_000_000),
      direction: .outgoing, status: .pending, textType: .plain, ackCode: nil,
      pathLength: 0, snr: nil, senderKeyPrefix: nil, senderNodeName: nil,
      isRead: true, replyToID: nil, roundTripTime: nil, heardRepeats: 0,
      retryAttempt: 0, maxRetryAttempts: 0
    )
    try await store.saveChannel(channel)
    try await store.saveMessage(message)

    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: radioID),
      onNavigateToMap: nil, linkPreviewCache: nil,
      chatCoordinatorRegistry: registry, conversation: .channel(channel)
    )
    viewModel.applyEnvInputs(.default)
    #expect(await viewModel.primeInitialChannelMessages(for: channel, populateMode: .replace))
    #expect(viewModel.messagesByID[message.id]?.status == .pending)
    try await store.updateMessageStatus(id: message.id, status: .sent)
    #expect(viewModel.messagesByID[message.id]?.status == .pending)

    let versionBeforeLoss = appState.servicesVersion
    await appState.disconnect()
    #expect(appState.servicesVersion == versionBeforeLoss + 1)
    #expect(appState.services == nil)

    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: radioID),
      onNavigateToMap: nil, linkPreviewCache: nil,
      chatCoordinatorRegistry: registry, conversation: .channel(channel)
    )
    await viewModel.loadChannelMessages(for: channel, populateMode: .refreshWindow)
    await viewModel.coordinator?.buildItemsTask?.value
    #expect(viewModel.messagesByID[message.id]?.status == .sent)

    let versionAfterRefresh = appState.servicesVersion
    await appState.handleConnectionLoss(ConnectionLossReceipt(requestID: UUID(), retiredGeneration: UUID()))
    #expect(appState.servicesVersion == versionAfterRefresh)
    #expect(viewModel.messagesByID[message.id]?.status == .sent)
  }

  @Test
  func `notifyDataRestored bumps servicesVersion and the next load binds a fresh coordinator`() async throws {
    let suiteName = "test.registry-source.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    let radioID = UUID()
    appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Fixture")
    let store = try #require(appState.offlineDataStore)

    let contact = makeForgetTestContact(radioID: radioID)
    try await store.saveContact(contact)
    try await store.saveMessage(makeForgetTestMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 1000
    ))

    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: contact.radioID),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: .dm(contact)
    )
    viewModel.applyEnvInputs(.default)
    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .replace))
    let original = try #require(viewModel.coordinator)
    let versionBefore = appState.servicesVersion

    appState.notifyDataRestored()

    #expect(appState.chatCoordinatorRegistry === registry)
    #expect(appState.servicesVersion == versionBefore + 1)
    #expect(registry.existingCoordinator(for: original.conversationID) == nil)

    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: contact.radioID),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: .dm(contact)
    )
    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .refreshWindow))
    let rebound = try #require(viewModel.coordinator)
    #expect(rebound !== original)
  }

  @Test
  func `forgetting the last-connected device empties the registry and reloads unavailable`() async throws {
    let suiteName = "test.forget-registry.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let container = try PersistenceStore.createContainer(inMemory: true)
    let appState = AppState(modelContainer: container, defaults: defaults)
    defer { appState.shutdown() }
    let deviceID = UUID()
    let radioID = UUID()
    appState.connectionManager.persistConnection(
      deviceID: deviceID,
      radioID: radioID,
      deviceName: "ForgetTest"
    )

    let store = try #require(appState.offlineDataStore)
    let contact = makeForgetTestContact(radioID: radioID)
    try await store.saveContact(contact)
    try await store.saveMessage(makeForgetTestMessage(
      radioID: radioID,
      contactID: contact.id,
      timestamp: 1000
    ))

    let registry = try #require(appState.ensureChatCoordinatorRegistry())
    let conversationID = ChatConversationID.dm(radioID: radioID, contactID: contact.id)
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: contact.radioID),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: .dm(contact)
    )
    viewModel.applyEnvInputs(.default)
    #expect(await viewModel.primeInitialMessages(for: contact, populateMode: .replace))
    #expect(viewModel.messages.isEmpty == false)

    let versionBefore = appState.servicesVersion
    await appState.connectionManager.clearPersistedConnection(for: deviceID)

    #expect(appState.chatCoordinatorRegistry === registry)
    #expect(registry.existingCoordinator(for: conversationID) == nil)
    #expect(appState.servicesVersion == versionBefore + 1)
    #expect(appState.offlineDataStore == nil)

    viewModel.configure(
      dependencies: appState.makeChatViewModelDependencies(radioID: contact.radioID),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: registry,
      conversation: .dm(contact)
    )
    let reloaded = await viewModel.primeInitialMessages(
      for: contact,
      populateMode: .refreshWindow
    )
    #expect(reloaded == false)
    #expect(viewModel.messages.isEmpty)
    #expect(viewModel.coordinator != nil)
    #expect(viewModel.coordinator?.messages.isEmpty == true)
  }

  @Test
  func `forgetting a non-last-connected device does not fire onLastConnectedDeviceCleared`() async throws {
    let suiteName = "test.last-connected-clear.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(modelContainer: container, defaults: defaults)

    var fired = false
    manager.onLastConnectedDeviceCleared = { fired = true }

    let lastID = UUID()
    manager.persistConnection(deviceID: lastID, radioID: UUID(), deviceName: "Holder")
    await manager.clearPersistedConnection(for: UUID())
    #expect(fired == false)

    await manager.clearPersistedConnection(for: lastID)
    #expect(fired)
  }
}

private func makeForgetTestContact(radioID: UUID) -> ContactDTO {
  ContactDTO(
    id: UUID(),
    radioID: radioID,
    publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
    name: "ForgetContact",
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
    unreadCount: 0
  )
}

private func makeForgetTestMessage(radioID: UUID, contactID: UUID, timestamp: UInt32) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: contactID,
    channelIndex: nil,
    text: "forget \(timestamp)",
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
