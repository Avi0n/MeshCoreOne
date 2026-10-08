import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
struct ChatViewModelConversationTests {
  // MARK: - Test Helpers

  private func makeContact(
    id: UUID = UUID(),
    name: String = "Test",
    typeRawValue: UInt8 = ContactType.chat.rawValue,
    isFavorite: Bool = false,
    isPinned: Bool = false,
    lastMessageDate: Date? = nil
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0xAB, count: 32),
      name: name,
      typeRawValue: typeRawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: isFavorite,
      isPinned: isPinned,
      lastMessageDate: lastMessageDate,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private func makeChannel(
    id: UUID = UUID(),
    radioID: UUID = UUID(),
    name: String = "General",
    isPinned: Bool = false
  ) -> ChannelDTO {
    ChannelDTO(
      id: id,
      radioID: radioID,
      index: 1,
      name: name,
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      isPinned: isPinned
    )
  }

  private func makeDependencies(dataStore: DataStore?) -> ChatViewModel.Dependencies {
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

  private func configuredViewModel(dataStore: DataStore?) -> ChatViewModel {
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: makeDependencies(dataStore: dataStore),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: nil,
      conversation: nil
    )
    return viewModel
  }

  private func makeStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  // MARK: - pinnedConversations Tests

  @Test
  func `pinnedConversations returns only pins`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = [
      makeContact(name: "Alice", isPinned: true),
      makeContact(name: "Bob", isPinned: false),
      makeContact(name: "Charlie", isPinned: true)
    ]
    viewModel.recomputeSnapshot()

    let pinned = viewModel.pinnedConversations
    // Evaluated outside #expect: the key-path form does not compile inside the
    // macro expansion, and swiftformat's preferKeyPath rejects the closure form.
    let allArePinned = pinned.allSatisfy(\.isPinned)

    #expect(pinned.count == 2)
    #expect(allArePinned)
  }

  @Test
  func `pinnedConversations sorts by lastMessageDate descending`() {
    let viewModel = ChatViewModel()
    let older = Date(timeIntervalSince1970: 1000)
    let newer = Date(timeIntervalSince1970: 2000)

    viewModel.conversations = [
      makeContact(name: "Older", isPinned: true, lastMessageDate: older),
      makeContact(name: "Newer", isPinned: true, lastMessageDate: newer)
    ]
    viewModel.recomputeSnapshot()

    let pinned = viewModel.pinnedConversations

    #expect(pinned.count == 2)
    #expect(pinned[0].displayName == "Newer")
    #expect(pinned[1].displayName == "Older")
  }

  @Test
  func `pinnedConversations returns empty when no chats are pinned`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = [
      makeContact(name: "Alice", isPinned: false),
      makeContact(name: "Bob", isPinned: false)
    ]
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.isEmpty)
  }

  @Test
  func `a favorite contact that is not pinned sorts with the unpinned chats`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = [
      makeContact(name: "Ada", isFavorite: true, isPinned: false, lastMessageDate: Date())
    ]
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.isEmpty)
    #expect(viewModel.unpinnedConversations.map(\.displayName) == ["Ada"])
  }

  // MARK: - unpinnedConversations Tests

  @Test
  func `unpinnedConversations returns only unpinned chats`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = [
      makeContact(name: "Alice", isPinned: true),
      makeContact(name: "Bob", isPinned: false),
      makeContact(name: "Charlie", isPinned: false)
    ]
    viewModel.recomputeSnapshot()

    let unpinned = viewModel.unpinnedConversations

    #expect(unpinned.count == 2)
    #expect(unpinned.allSatisfy { !$0.isPinned })
  }

  @Test
  func `unpinnedConversations sorts by lastMessageDate descending`() {
    let viewModel = ChatViewModel()
    let older = Date(timeIntervalSince1970: 1000)
    let newer = Date(timeIntervalSince1970: 2000)

    viewModel.conversations = [
      makeContact(name: "Older", isPinned: false, lastMessageDate: older),
      makeContact(name: "Newer", isPinned: false, lastMessageDate: newer)
    ]
    viewModel.recomputeSnapshot()

    let unpinned = viewModel.unpinnedConversations

    #expect(unpinned.count == 2)
    #expect(unpinned[0].displayName == "Newer")
    #expect(unpinned[1].displayName == "Older")
  }

  // MARK: - allConversations Tests

  @Test
  func `allConversations returns pins first then the other chats`() {
    let viewModel = ChatViewModel()
    let now = Date()

    viewModel.conversations = [
      makeContact(name: "Other", isPinned: false, lastMessageDate: now),
      makeContact(name: "Pinned", isPinned: true, lastMessageDate: now.addingTimeInterval(-1000))
    ]
    viewModel.recomputeSnapshot()

    let all = viewModel.allConversations

    #expect(all.count == 2)
    #expect(all[0].displayName == "Pinned")
    #expect(all[1].displayName == "Other")
  }

  // MARK: - Snapshot Recompute Tests

  @Test
  func `snapshot reflects pin state changes after recompute`() {
    let viewModel = ChatViewModel()
    let contact = makeContact(name: "Test", isPinned: false)
    viewModel.conversations = [contact]
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.isEmpty)
    #expect(viewModel.unpinnedConversations.count == 1)

    viewModel.conversations = [
      makeContact(id: contact.id, name: "Test", isPinned: true)
    ]
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.count == 1)
    #expect(viewModel.unpinnedConversations.isEmpty)
  }

  // MARK: - Edge Cases

  @Test
  func `handles empty conversations array`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = []
    viewModel.channels = []
    viewModel.roomSessions = []
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.isEmpty)
    #expect(viewModel.unpinnedConversations.isEmpty)
    #expect(viewModel.allConversations.isEmpty)
  }

  @Test
  func `handles nil lastMessageDate by sorting to end`() {
    let viewModel = ChatViewModel()
    let withDate = Date()

    viewModel.conversations = [
      makeContact(name: "NoDate", isPinned: true, lastMessageDate: nil),
      makeContact(name: "HasDate", isPinned: true, lastMessageDate: withDate)
    ]
    viewModel.recomputeSnapshot()

    let pinned = viewModel.pinnedConversations

    #expect(pinned[0].displayName == "HasDate")
    #expect(pinned[1].displayName == "NoDate")
  }

  @Test
  func `a repeater with both pin and favorite stays out of the list`() {
    let viewModel = ChatViewModel()
    viewModel.conversations = [
      makeContact(
        name: "Tower",
        typeRawValue: ContactType.repeater.rawValue,
        isFavorite: true,
        isPinned: true,
        lastMessageDate: Date()
      )
    ]
    viewModel.recomputeSnapshot()

    #expect(viewModel.pinnedConversations.isEmpty)
    #expect(viewModel.unpinnedConversations.isEmpty)
  }

  // MARK: - Stale swipe snapshots

  /// Swipe chrome keeps the row from first reveal; a second tap must invert live mute state.
  @Test
  func `toggleMute with stale unmuted snapshot unmutes live row`() async {
    let viewModel = ChatViewModel()
    viewModel.connectionStateProvider = { .ready }
    let contact = makeContact(name: "Alice")
    viewModel.conversations = [contact]
    viewModel.recomputeSnapshot()
    let stale = Conversation.direct(contact)

    await viewModel.toggleMute(stale)
    #expect(viewModel.conversations[0].isMuted)

    await viewModel.toggleMute(stale)
    #expect(!viewModel.conversations[0].isMuted)
  }

  /// A second tap keeps the row captured at first reveal, and still flips the live pin offline.
  @Test
  func `togglePinned with stale unpinned snapshot unpins the live channel while disconnected`() async throws {
    let store = try makeStore()
    let channel = makeChannel(isPinned: false)
    try await store.saveChannel(channel)

    let viewModel = configuredViewModel(dataStore: store)
    viewModel.channels = [channel]
    viewModel.recomputeSnapshot()
    let stale = Conversation.channel(channel)

    await viewModel.togglePinned(stale)
    #expect(viewModel.channels[0].isPinned)

    await viewModel.togglePinned(stale)
    #expect(!viewModel.channels[0].isPinned)

    let stored = try await store.fetchChannel(id: channel.id)
    #expect(stored?.isPinned == false)
  }

  @Test
  func `togglePinned on a direct chat saves when contactService is nil`() async throws {
    let store = try makeStore()
    let contact = makeContact(name: "Ada", isFavorite: true, isPinned: false)
    try await store.saveContact(contact)

    let viewModel = configuredViewModel(dataStore: store)
    viewModel.conversations = [contact]
    viewModel.recomputeSnapshot()

    await viewModel.togglePinned(.direct(contact))

    #expect(viewModel.conversations[0].isPinned)
    #expect(viewModel.conversations[0].isFavorite)
    let stored = try await store.fetchContact(id: contact.id)
    #expect(stored?.isPinned == true)
    #expect(stored?.isFavorite == true)
  }

  @Test
  func `togglePinned restores the pin when the channel is missing`() async throws {
    let store = try makeStore()
    let channel = makeChannel(isPinned: true)
    let viewModel = configuredViewModel(dataStore: store)
    viewModel.channels = [channel]
    viewModel.recomputeSnapshot()

    await viewModel.togglePinned(.channel(channel))

    #expect(viewModel.channels[0].isPinned)
    #expect(viewModel.errorMessage == PersistenceStoreError.channelNotFound.userFacingMessage)
  }

  @Test
  func `togglePinned with a nil store leaves the pin and sets pinSaveFailed`() async {
    let viewModel = configuredViewModel(dataStore: nil)
    let channel = makeChannel(isPinned: false)
    viewModel.channels = [channel]
    viewModel.recomputeSnapshot()

    await viewModel.togglePinned(.channel(channel))

    #expect(!viewModel.channels[0].isPinned)
    #expect(viewModel.errorMessage == L10n.Chats.Chats.Error.pinSaveFailed)
  }

  // MARK: - errorBannerMessage Tests

  @Test
  func `errorBannerMessage round-trips through setting and clearing`() {
    let viewModel = ChatViewModel()
    #expect(viewModel.errorBannerMessage == nil)
    viewModel.errorBannerMessage = "Test banner"
    #expect(viewModel.errorBannerMessage == "Test banner")
    viewModel.errorBannerMessage = nil
    #expect(viewModel.errorBannerMessage == nil)
  }
}
