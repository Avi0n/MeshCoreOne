import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatViewModel failed-send indicators")
@MainActor
struct ChatViewModelFailedSendTests {
  private func makeViewModel() -> ChatViewModel {
    ChatViewModel()
  }

  private func makeContact(
    id: UUID = UUID(),
    radioID: UUID = UUID(),
    lastMessageDate: Date? = Date()
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: radioID,
      publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
      name: "Test",
      typeRawValue: ContactType.chat.rawValue,
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
      isFavorite: false,
      lastMessageDate: lastMessageDate,
      unreadCount: 0
    )
  }

  private func makeChannel(
    id: UUID = UUID(),
    radioID: UUID = UUID(),
    index: UInt8 = 0
  ) -> ChannelDTO {
    ChannelDTO(
      id: id,
      radioID: radioID,
      index: index,
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

  private func makeRoom(
    id: UUID = UUID(),
    radioID: UUID = UUID()
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: id,
      radioID: radioID,
      publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
      name: "Room",
      role: .roomServer,
      isConnected: true,
      isFavorite: false,
      lastMessageDate: Date()
    )
  }

  private func makeMessage(
    radioID: UUID,
    contactID: UUID? = nil,
    channelIndex: UInt8? = nil,
    text: String = "hello",
    status: MessageStatus = .failed
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: channelIndex,
      text: text,
      timestamp: 1_700_000_000,
      createdAt: Date(),
      direction: .outgoing,
      status: status,
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

  @Test
  func `applyFailedSendKeys maps contact id onto the list set`() {
    let viewModel = makeViewModel()
    let contact = makeContact()
    viewModel.conversations = [contact]

    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [contact.id]))

    #expect(viewModel.failedSendConversationIDs == [contact.id])
    #expect(viewModel.conversationHasFailedSend(contact.id))
    #expect(!viewModel.conversationHasFailedSend(UUID()))
  }

  @Test
  func `applyFailedSendKeys unions channel ids`() {
    let viewModel = makeViewModel()
    let channel = makeChannel(index: 4)
    let other = makeChannel(index: 7)

    viewModel.applyFailedSendKeys(FailedSendConversationKeys(channelIDs: [channel.id]))

    #expect(viewModel.failedSendConversationIDs == [channel.id])
    #expect(viewModel.conversationHasFailedSend(channel.id))
    #expect(!viewModel.conversationHasFailedSend(other.id))
  }

  @Test
  func `applyFailedSendKeys maps room session id`() {
    let viewModel = makeViewModel()
    let session = makeRoom()
    viewModel.roomSessions = [session]

    viewModel.applyFailedSendKeys(FailedSendConversationKeys(roomSessionIDs: [session.id]))

    #expect(viewModel.failedSendConversationIDs == [session.id])
    #expect(viewModel.conversationHasFailedSend(session.id))
  }

  @Test
  func `clearConversations empties the failed-send set`() {
    let viewModel = makeViewModel()
    let contact = makeContact()
    viewModel.conversations = [contact]
    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [contact.id]))
    #expect(!viewModel.failedSendConversationIDs.isEmpty)

    viewModel.clearConversations()

    #expect(viewModel.failedSendConversationIDs.isEmpty)
    #expect(!viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `refresh with empty keys clears a previously populated set`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]
    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [contact.id]))
    #expect(viewModel.conversationHasFailedSend(contact.id))

    await viewModel.refreshFailedSendIndicators()

    #expect(viewModel.failedSendConversationIDs.isEmpty)
    #expect(!viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `refresh loads failed DM channel and room keys from the store`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let channel = makeChannel(radioID: radioID, index: 2)
    let session = makeRoom(radioID: radioID)
    try await store.saveChannel(channel)
    try await store.saveRemoteNodeSessionDTO(session)
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )
    try await store.saveMessage(
      makeMessage(radioID: radioID, channelIndex: channel.index, status: .failed)
    )
    try await store.saveRoomMessage(
      RoomMessageDTO(
        sessionID: session.id,
        authorKeyPrefix: Data([0xAB, 0xCD, 0xEF, 0x01]),
        text: "failed room send",
        timestamp: 1_700_000_000,
        isFromSelf: true,
        status: .failed
      )
    )

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]
    viewModel.channels = [channel]
    viewModel.roomSessions = [session]

    await viewModel.refreshFailedSendIndicators()

    #expect(viewModel.conversationHasFailedSend(contact.id))
    #expect(viewModel.conversationHasFailedSend(channel.id))
    #expect(viewModel.conversationHasFailedSend(session.id))
  }

  @Test
  func `conversation reload loads failed-send indicators`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID, lastMessageDate: Date())
    try await store.saveContact(contact)
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )

    await viewModel.requestConversationReload()?.value

    #expect(viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `refresh with no radio id clears the set`() async {
    let viewModel = makeViewModel()
    let contact = makeContact()
    viewModel.conversations = [contact]
    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [contact.id]))

    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { nil },
        currentRadioID: { nil }
      )
    )
    await viewModel.refreshFailedSendIndicators()

    #expect(viewModel.failedSendConversationIDs.isEmpty)
  }

  @Test
  func `messageFailed and peers always refresh`() {
    let viewModel = makeViewModel()
    let messageID = UUID()

    #expect(viewModel.shouldRefreshFailedSendIndicators(for: .messageFailed(messageID: messageID)))
    #expect(viewModel.shouldRefreshFailedSendIndicators(for: .messageResent(messageID: messageID)))
    #expect(viewModel.shouldRefreshFailedSendIndicators(for: .roomMessageFailed(messageID: messageID)))
  }

  @Test
  func `messageStatusResolved refreshes only when a badge is showing`() {
    let viewModel = makeViewModel()
    let event = MessageEvent.messageStatusResolved(messageID: UUID(), status: .delivered)

    #expect(!viewModel.shouldRefreshFailedSendIndicators(for: event))

    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [UUID()]))

    #expect(viewModel.shouldRefreshFailedSendIndicators(for: event))
  }

  @Test
  func `roomMessageStatusUpdated refreshes only when a badge is showing`() {
    let viewModel = makeViewModel()
    let event = MessageEvent.roomMessageStatusUpdated(messageID: UUID())

    #expect(!viewModel.shouldRefreshFailedSendIndicators(for: event))

    viewModel.applyFailedSendKeys(FailedSendConversationKeys(roomSessionIDs: [UUID()]))

    #expect(viewModel.shouldRefreshFailedSendIndicators(for: event))
  }

  @Test
  func `other message events do not refresh`() {
    let viewModel = makeViewModel()
    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [UUID()]))
    let messageID = UUID()
    let contact = makeContact()
    let message = makeMessage(radioID: contact.radioID, contactID: contact.id)

    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .directMessageReceived(message: message, contact: contact)
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .channelMessageReceived(message: message, channelIndex: 0)
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .roomMessageReceived(
        message: RoomMessageDTO(
          sessionID: UUID(),
          authorKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]),
          text: "hi",
          timestamp: 1
        ),
        sessionID: UUID()
      )
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .messageRetrying(messageID: messageID, attempt: 1, maxAttempts: 4)
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .heardRepeatRecorded(messageID: messageID, count: 1)
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .reactionReceived(messageID: messageID, summary: "👍:1")
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .messagesRegionUpdated(messageIDs: [messageID])
    ))
    #expect(!viewModel.shouldRefreshFailedSendIndicators(
      for: .routingChanged(contactID: contact.id, isFlood: true)
    ))
  }

  /// Refresh #1 fetches empty keys and parks; #2 then fetches the saved failed
  /// send and applies it. #1 must drop its empty result instead of wiping #2.
  @Test
  func `stale empty refresh does not overwrite a newer apply`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]

    let arrived = AsyncGate()
    let gate = AsyncGate()
    viewModel.failedSendRefreshInterleaveHook = {
      await arrived.open()
      await gate.wait()
    }

    let first = Task { await viewModel.refreshFailedSendIndicators() }
    await arrived.wait()

    viewModel.failedSendRefreshInterleaveHook = nil
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )
    await viewModel.refreshFailedSendIndicators()
    #expect(viewModel.conversationHasFailedSend(contact.id))

    await gate.open()
    await first.value

    #expect(viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `cancelled in-flight refresh does not apply fetched keys`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )
    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]

    let arrived = AsyncGate()
    let gate = AsyncGate()
    viewModel.failedSendRefreshInterleaveHook = {
      await arrived.open()
      await gate.wait()
    }

    let task = Task { await viewModel.refreshFailedSendIndicators() }
    await arrived.wait()
    task.cancel()
    await gate.open()
    await task.value

    #expect(viewModel.failedSendConversationIDs.isEmpty)
  }

  @Test
  func `store throw leaves last-known badges`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]
    viewModel.applyFailedSendKeys(FailedSendConversationKeys(contactIDs: [contact.id]))
    viewModel.failedSendRefreshFaultInjection = { throw TestStoreError() }

    await viewModel.refreshFailedSendIndicators()

    #expect(viewModel.conversationHasFailedSend(contact.id))
  }

  /// Event-driven refresh is not `reloadTask`; clear must bump generation so a
  /// parked fetch cannot re-dirty `failedSendConversationIDs` after disconnect.
  @Test
  func `clearConversations drops an in-flight refresh apply`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )
    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]

    let arrived = AsyncGate()
    let gate = AsyncGate()
    viewModel.failedSendRefreshInterleaveHook = {
      await arrived.open()
      await gate.wait()
    }

    let task = Task { await viewModel.refreshFailedSendIndicators() }
    await arrived.wait()
    viewModel.clearConversations()
    #expect(viewModel.failedSendConversationIDs.isEmpty)

    await gate.open()
    await task.value

    #expect(viewModel.failedSendConversationIDs.isEmpty)
  }

  @Test
  func `loadMessages marks failed sends seen; prime does not`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    try await store.saveContact(contact)
    try await store.saveMessage(
      makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    )

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    viewModel.conversations = [contact]

    _ = await viewModel.primeInitialMessages(for: contact, populateMode: .replace)
    await viewModel.refreshFailedSendIndicators()
    #expect(viewModel.conversationHasFailedSend(contact.id))

    await viewModel.loadMessages(for: contact, populateMode: .replace)
    await viewModel.refreshFailedSendIndicators()
    #expect(!viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `loadChannelMessages marks failed sends seen`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID, index: 3)
    try await store.saveChannel(channel)
    try await store.saveMessage(
      makeMessage(radioID: radioID, channelIndex: channel.index, status: .failed)
    )

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    viewModel.channels = [channel]

    await viewModel.loadChannelMessages(for: channel, populateMode: .replace)
    await viewModel.refreshFailedSendIndicators()
    #expect(!viewModel.conversationHasFailedSend(channel.id))
  }

  @Test
  func `handle messageFailed for the current contact marks seen`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let message = makeMessage(radioID: radioID, contactID: contact.id, status: .failed)
    try await store.saveContact(contact)
    try await store.saveMessage(message)

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]
    viewModel.currentContact = contact
    await viewModel.refreshFailedSendIndicators()
    #expect(viewModel.conversationHasFailedSend(contact.id))

    await viewModel.handle(.messageFailed(messageID: message.id))
    await viewModel.refreshFailedSendIndicators()
    #expect(!viewModel.conversationHasFailedSend(contact.id))
  }

  @Test
  func `handle messageFailed for another contact does not mark seen`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let openContact = makeContact(radioID: radioID)
    let otherContact = makeContact(radioID: radioID)
    let message = makeMessage(radioID: radioID, contactID: otherContact.id, status: .failed)
    try await store.saveContact(otherContact)
    try await store.saveMessage(message)

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [openContact, otherContact]
    viewModel.currentContact = openContact
    await viewModel.refreshFailedSendIndicators()
    #expect(viewModel.conversationHasFailedSend(otherContact.id))

    await viewModel.handle(.messageFailed(messageID: message.id))
    await viewModel.refreshFailedSendIndicators()
    #expect(viewModel.conversationHasFailedSend(otherContact.id))
  }

  @Test
  func `recordLocalEnqueueFailure marks the current contact seen`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = makeContact(radioID: radioID)
    let message = makeMessage(radioID: radioID, contactID: contact.id, status: .pending)
    try await store.saveContact(contact)
    try await store.saveMessage(message)

    let viewModel = makeViewModel()
    viewModel.configureForTesting(
      dependencies: .testDefaults(
        dataStore: { store },
        currentRadioID: { radioID }
      )
    )
    viewModel.conversations = [contact]
    viewModel.currentContact = contact

    await viewModel.recordLocalEnqueueFailure(
      messageID: message.id,
      error: ChatSendQueueServiceError.notConnected
    )
    await viewModel.refreshFailedSendIndicators()

    #expect(!viewModel.conversationHasFailedSend(contact.id))
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    let stored = try await store.fetchMessage(id: message.id)
    #expect(stored?.status == .failed)
    #expect(stored?.failureSeen == true)
  }
}

private struct TestStoreError: Error {}

/// Parks a failed-send refresh between its fetch and apply.
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
