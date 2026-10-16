import Foundation
@testable import MC1
@testable import MC1Services

extension ChatViewModel.Dependencies {
  /// All-nil baseline for tests; override only the providers a test exercises.
  /// Production `configure` call sites state every dependency explicitly; the
  /// defaults here exist so each test reads as just its overrides.
  static func testDefaults(
    dataStore: @escaping @MainActor () -> DataStore? = { nil },
    messageService: @escaping @MainActor () -> MessageService? = { nil },
    notificationService: @escaping @MainActor () -> NotificationService? = { nil },
    channelService: @escaping @MainActor () -> ChannelService? = { nil },
    roomServerService: @escaping @MainActor () -> RoomServerService? = { nil },
    contactService: @escaping @MainActor () -> ContactService? = { nil },
    syncCoordinator: @escaping @MainActor () -> SyncCoordinator? = { nil },
    connectionState: @escaping @MainActor () -> DeviceConnectionState = { .disconnected },
    connectedDevice: @escaping @MainActor () -> DeviceDTO? = { nil },
    currentRadioID: @escaping @MainActor () -> UUID? = { nil },
    session: @escaping @MainActor () -> MeshCoreSession? = { nil },
    reactionService: @escaping @MainActor () -> ReactionService? = { nil },
    chatSendQueueService: @escaping @MainActor () -> ChatSendQueueService? = { nil },
    inlineImageDimensionsStore: @escaping @MainActor () -> InlineImageDimensionsStore? = { nil },
    prefetchDataStore: @escaping @MainActor () -> (any PersistenceStoreProtocol)? = { nil }
  ) -> Self {
    ChatViewModel.Dependencies(
      dataStore: dataStore,
      messageService: messageService,
      notificationService: notificationService,
      channelService: channelService,
      roomServerService: roomServerService,
      contactService: contactService,
      syncCoordinator: syncCoordinator,
      connectionState: connectionState,
      connectedDevice: connectedDevice,
      currentRadioID: currentRadioID,
      session: session,
      reactionService: reactionService,
      chatSendQueueService: chatSendQueueService,
      inlineImageDimensionsStore: inlineImageDimensionsStore,
      prefetchDataStore: prefetchDataStore
    )
  }
}

extension ChatViewModel {
  /// Configures with `dependencies` and no screen-scoped extras.
  func configureForTesting(dependencies: ChatViewModel.Dependencies) {
    configure(
      dependencies: dependencies,
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: nil,
      conversation: nil
    )
  }
}
