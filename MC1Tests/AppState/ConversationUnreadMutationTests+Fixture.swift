import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension ConversationUnreadMutationTests {
  @MainActor
  final class Fixture {
    let appState: AppState
    let store: PersistenceStore
    let radioID: UUID
    let contact: ContactDTO
    let conversation: Conversation
    let viewModel: ChatViewModel
    let recorder: Recorder
    let defaults: UserDefaults
    let suite: String
    var services: ServiceContainer
    var currentRadioID: UUID?
    var currentStore: PersistenceStore?
    private var containers: [ServiceContainer]

    init(kind: Kind, level: NotificationLevel = .all) async throws {
      let suite = "ConversationUnreadMutation.\(UUID())"
      let defaults = try #require(UserDefaults(suiteName: suite))
      for key in [AppStorageKey.notificationBadgeEnabled, .notifyContactMessages, .notifyChannelMessages, .notifyRoomMessages] {
        defaults.set(true, forKey: key.rawValue)
      }
      let recorder = Recorder()
      let appState = try AppState(
        modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true,
        defaults: defaults, conversationUnreadOperations: .init(
          read: AppState.ConversationUnreadOperations.live.read,
          publish: { _, count in recorder.publish(count) }
        )
      )
      let store = appState.connectionManager.persistenceStore
      let radioID = UUID()
      let contact = ContactDTO(
        id: UUID(), radioID: radioID, publicKey: Data(repeating: 0xAB, count: 32), name: "Ada",
        typeRawValue: ContactType.chat.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
        lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
        nickname: nil, isBlocked: false, isMuted: level == .muted, isFavorite: false,
        lastMessageDate: Date(timeIntervalSince1970: 1000), unreadCount: 3
      )
      let conversation: Conversation
      switch kind {
      case .direct:
        try await store.saveContact(contact)
        for timestamp in UInt32(1000)...UInt32(1002) {
          try await store.saveMessage(MessageDTO(
            id: UUID(), radioID: radioID, contactID: contact.id, channelIndex: nil,
            text: "Message \(timestamp)", timestamp: timestamp,
            createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)), direction: .incoming,
            status: .delivered, textType: .plain, ackCode: nil, pathLength: 0, snr: nil,
            senderKeyPrefix: nil, senderNodeName: nil, isRead: false, replyToID: nil,
            roundTripTime: nil, heardRepeats: 0, retryAttempt: 0, maxRetryAttempts: 0
          ))
        }
        conversation = .direct(contact)
      case .channel:
        let channel = ChannelDTO(
          id: UUID(), radioID: radioID, index: 1, name: "General", secret: Data(), isEnabled: true,
          lastMessageDate: Date(), unreadCount: 3, unreadMentionCount: 1, notificationLevel: level
        )
        try await store.saveChannel(channel)
        conversation = .channel(channel)
      case .room:
        let room = RemoteNodeSessionDTO(
          radioID: radioID, publicKey: Data(repeating: 0xCD, count: 32), name: "Room",
          role: .roomServer, unreadCount: 3, notificationLevel: level, lastMessageDate: Date()
        )
        try await store.saveRemoteNodeSessionDTO(room)
        conversation = .room(room)
      }
      let services = ServiceContainer(
        session: MeshCoreSession(transport: MockTransport()), dataStore: store, radioID: radioID
      )
      let notificationService = NotificationService(
        notificationDefaults: defaults, systemOperations: .init(
          setBadgeCount: { recorder.badges.append($0) },
          deliveredRequests: { [] }, removeDeliveredNotifications: { _ in }
        )
      )
      notificationService.getBadgeCount = {
        await (try? store.getTotalUnreadCounts(radioID: radioID)) ?? (contacts: 0, channels: 0, rooms: 0)
      }
      self.suite = suite
      self.defaults = defaults
      self.recorder = recorder
      self.appState = appState
      self.store = store
      self.radioID = radioID
      self.contact = contact
      self.conversation = conversation
      self.services = services
      self.containers = [services]
      self.currentRadioID = radioID
      self.currentStore = store
      self.viewModel = ChatViewModel()
      viewModel.configureForTesting(dependencies: .testDefaults(
        dataStore: { self.currentStore }, notificationService: { notificationService },
        syncCoordinator: { self.services.syncCoordinator }, connectionState: { .ready },
        currentRadioID: { self.currentRadioID }
      ))
      appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Radio")
      appState.connectionManager.setTestState(services: services)
      appState.wireSyncDataEvents(services: services)
      await appState.refreshConversations().value
      await viewModel.requestConversationReload()?.value
      try #require(await recorder.publications == [total()])
    }

    func total() async -> Int {
      await AppState.ConversationUnreadOperations.live.read(store, radioID)
    }

    func replaceServices() {
      services = ServiceContainer(
        session: MeshCoreSession(transport: MockTransport()), dataStore: store, radioID: radioID
      )
      containers.append(services)
      appState.connectionManager.setTestState(services: services)
      appState.wireSyncDataEvents(services: services)
    }

    func finishEvents() async {
      services.syncCoordinator.finishDataEvents()
      await appState.syncDataEventsTask?.value
      if appState.conversationsVersion > 1 { await recorder.waitForChange() }
    }

    func shutdown() {
      viewModel.conversationMutationPostSaveHook = nil
      viewModel.configureForTesting(dependencies: .testDefaults())
      appState.shutdown()
      defaults.removePersistentDomain(forName: suite)
      let containers = containers
      Task { for container in containers {
        await container.tearDown()
      } }
    }
  }

  @MainActor
  final class Recorder {
    var publications: [Int] = []
    var badges: [Int] = []
    private var continuation: CheckedContinuation<Void, Never>?

    func publish(_ count: Int) {
      publications.append(count)
      if publications.count > 1 {
        continuation?.resume()
        continuation = nil
      }
    }

    func waitForChange() async {
      if publications.count > 1 { return }
      await withCheckedContinuation { continuation = $0 }
    }
  }
}
