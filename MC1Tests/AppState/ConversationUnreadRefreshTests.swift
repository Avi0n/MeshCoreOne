import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Conversation unread refresh")
@MainActor
struct ConversationUnreadRefreshTests {
  @Test
  func `live activity counts all conversation kinds on only its radio`() async throws {
    let suite = "ConversationUnreadRefresh.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    var publications: [Int] = []
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true,
      defaults: defaults, conversationUnreadOperations: .init(
        read: AppState.ConversationUnreadOperations.live.read,
        publish: { _, count in publications.append(count) }
      )
    )
    defer { appState.shutdown() }
    let store = appState.connectionManager.persistenceStore
    let radioID = UUID()
    try await store.saveContact(makeContact(radioID: radioID, unreadCount: 2))
    try await store.saveContact(makeContact(radioID: UUID(), unreadCount: 7))
    try await store.saveChannel(ChannelDTO(
      id: UUID(), radioID: radioID, index: 1, name: "Channel", secret: Data(), isEnabled: true,
      lastMessageDate: Date(), unreadCount: 3, notificationLevel: .all
    ))
    try await store.saveRemoteNodeSessionDTO(RemoteNodeSessionDTO(
      radioID: radioID, publicKey: Data(repeating: 0xCD, count: 32), name: "Room",
      role: .roomServer, unreadCount: 5, notificationLevel: .all
    ))
    let services = installServices(appState, radioID: radioID)
    defer { Task { await services.tearDown() } }

    await appState.refreshConversations().value

    #expect(publications == [10])
  }

  @Test(arguments: [0, 1, 2])
  func `old count cannot publish after a newer refresh or owner replacement`(replacement: Int) async throws {
    let suite = "ConversationUnreadRefresh.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    let arrived = Gate()
    let resume = Gate()
    var reads = 0
    var publications: [Int] = []
    let operations = AppState.ConversationUnreadOperations(
      read: { store, radioID in
        let count = await AppState.ConversationUnreadOperations.live.read(store, radioID)
        reads += 1
        if reads == 1 {
          await arrived.open()
          await resume.wait()
        }
        return count
      },
      publish: { _, count in publications.append(count) }
    )
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true,
      defaults: defaults, conversationUnreadOperations: operations
    )
    defer { appState.shutdown() }
    let store = appState.connectionManager.persistenceStore
    let radioID = UUID()
    let contact = makeContact(radioID: radioID, unreadCount: 7)
    try await store.saveContact(contact)
    let oldServices = installServices(appState, radioID: radioID)
    defer { Task { await oldServices.tearDown() } }
    let old = appState.refreshConversations()
    await arrived.wait()

    if replacement == 0 {
      try await store.setContactReadState(contact.id, isRead: true)
      await appState.refreshConversations().value
      await resume.open()
      await old.value
      #expect(publications == [0])
    } else {
      let nextRadioID = replacement == 1 ? UUID() : radioID
      try await store.setContactReadState(contact.id, isRead: true)
      let nextContact = makeContact(radioID: nextRadioID, unreadCount: 2)
      try await store.saveContact(nextContact)
      let nextServices = installServices(appState, radioID: nextRadioID)
      defer { Task { await nextServices.tearDown() } }
      await resume.open()
      await old.value
      #expect(publications.isEmpty)
      await appState.refreshConversations().value
      #expect(publications == [2])
    }
    #expect(appState.conversationsVersion == 2)
  }

  private func installServices(_ appState: AppState, radioID: UUID) -> ServiceContainer {
    let services = ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: appState.connectionManager.persistenceStore, radioID: radioID
    )
    appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Test")
    appState.connectionManager.setTestState(services: services)
    return services
  }

  private func makeContact(radioID: UUID, unreadCount: Int) -> ContactDTO {
    ContactDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: UInt8.random(in: 0...255), count: 32), name: "Test",
      typeRawValue: ContactType.chat.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
      lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
      nickname: nil, isBlocked: false, isMuted: false, isFavorite: false, lastMessageDate: Date(), unreadCount: unreadCount
    )
  }

  private actor Gate {
    private var waiter: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
      if isOpen { return }
      await withCheckedContinuation { waiter = $0 }
    }

    func open() {
      isOpen = true
      waiter?.resume()
      waiter = nil
    }
  }
}
