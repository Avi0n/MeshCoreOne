import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  struct Environment {
    let store: PersistenceStore
    let radioID: UUID
    let viewModel: ChatViewModel
    let contact: ContactDTO
    let channel: ChannelDTO
    let room: RemoteNodeSessionDTO
    let sync: SyncCoordinator
    let recorder: Recorder
    let defaults: UserDefaults
    let suite: String

    func cleanup() {
      defaults.removePersistentDomain(forName: suite)
    }

    @MainActor
    func makeViewModel() -> ChatViewModel {
      let model = ChatViewModel(notificationDefaults: defaults, notificationSystemOperations: recorder.operations)
      model.configureForTesting(dependencies: .testDefaults(
        dataStore: { store }, notificationService: { viewModel.notificationService },
        syncCoordinator: { viewModel.syncCoordinator }, currentRadioID: { radioID }
      ))
      return model
    }
  }

  func makeEnvironment(connected: Bool = false) async throws -> Environment {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let contact = ContactDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: 0xAB, count: 32), name: "Ada",
      typeRawValue: ContactType.chat.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
      lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
      nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
      lastMessageDate: Date(), unreadCount: 4, unreadMentionCount: 2
    )
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: 1, name: "General", secret: Data(), isEnabled: true,
      lastMessageDate: Date(), unreadCount: 4, unreadMentionCount: 2
    )
    let room = RemoteNodeSessionDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: 0xCC, count: 32), name: "Room",
      role: .roomServer, isConnected: false, unreadCount: 4, lastMessageDate: Date()
    )
    try await store.saveContact(contact)
    try await store.saveChannel(channel)
    try await store.saveRemoteNodeSessionDTO(room)
    let suite = "ChatReadState.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    for key in [AppStorageKey.notificationBadgeEnabled, .notifyContactMessages, .notifyChannelMessages, .notifyRoomMessages] {
      defaults.set(true, forKey: key.rawValue)
    }
    let recorder = Recorder()
    let sync = SyncCoordinator()
    let service = NotificationService(notificationDefaults: defaults, systemOperations: recorder.operations)
    service.getBadgeCount = {
      await (try? store.getTotalUnreadCounts(radioID: radioID)) ?? (contacts: 0, channels: 0, rooms: 0)
    }
    let viewModel = ChatViewModel(notificationDefaults: defaults, notificationSystemOperations: recorder.operations)
    viewModel.configureForTesting(dependencies: .testDefaults(
      dataStore: { store }, notificationService: { connected ? service : nil },
      syncCoordinator: { connected ? sync : nil }, currentRadioID: { radioID }
    ))
    await viewModel.requestConversationReload()?.value
    return Environment(
      store: store, radioID: radioID, viewModel: viewModel, contact: contact, channel: channel, room: room,
      sync: sync, recorder: recorder, defaults: defaults, suite: suite
    )
  }
}
