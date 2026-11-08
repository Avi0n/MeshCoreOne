import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatViewModel failed reaction ordering")
@MainActor
struct ChatViewModelReactionFailureOrderTests {
  private static let baseTimestamp: UInt32 = 1_700_000_000
  private static let olderRowRemainder = 3
  private static let unreadCount = 1
  private static let emoji = "👀"
  private static let senderName = "Melody"

  @Test(arguments: [false, true], LoadedWindow.allCases)
  private func `failed hidden reaction appears before newer messages without recounting its row`(
    isChannel: Bool, window: LoadedWindow
  ) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel, window: window)
    let viewModel = environment.viewModel
    if window != .firstPage {
      await viewModel.loadOlderMessages()
    }
    await viewModel.coordinator?.buildItemsTask?.value
    let rawCount = viewModel.totalFetchedCount
    let expected = Array(environment.history.suffix(rawCount))
    let expectedVisible = expected.filter { $0.id != environment.reaction.id }
    let oldestVisibleID = try #require(viewModel.messages.first?.id)
    #expect(rawCount == (window == .firstPage ? environment.history.count : ChatCoordinator.pageSize * 2))
    #expect(viewModel.messages.map(\.id) == expectedVisible.map(\.id))
    #expect(viewModel.items.map(\.id) == expectedVisible.map(\.id))
    #expect(viewModel.messagesByID[environment.reaction.id] == nil)
    #expect(environment.target.sortDate < environment.reaction.sortDate)
    #expect(environment.reaction.sortDate < environment.newer.sortDate)
    if window == .boundary {
      #expect(expected.first?.id == environment.reaction.id)
      #expect(viewModel.messagesByID[environment.target.id] == nil)
    }

    try await environment.store.updateMessageStatus(id: environment.reaction.id, status: .failed)
    await viewModel.handle(.messageFailed(messageID: environment.reaction.id))

    expectChronologicalMessages(viewModel, expected: expected, rawCount: rawCount)
    #expect(viewModel.messagesByID[oldestVisibleID] != nil)
    #expect(viewModel.messagesByID[environment.reaction.id]?.status == .failed)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(try await environment.store.fetchMessage(id: environment.reaction.id)?.failureSeen == true)

    await viewModel.coordinator?.coalescedReloadTask?.value
    await viewModel.coordinator?.buildItemsTask?.value

    expectChronologicalMessages(viewModel, expected: expected, rawCount: rawCount)
    #expect(viewModel.messagesByID[oldestVisibleID] != nil)

    if window != .firstPage {
      #expect(viewModel.hasMoreMessages)
      await viewModel.loadOlderMessages()
      await viewModel.coordinator?.buildItemsTask?.value
      expectChronologicalMessages(viewModel, expected: environment.history, rawCount: environment.history.count)
      #expect(viewModel.hasMoreMessages == false)
    }
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `dismissal during a failed reaction refresh leaves the timeline unchanged`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel, window: .firstPage)
    let viewModel = environment.viewModel
    let coordinator = try #require(viewModel.coordinator)
    await coordinator.buildItemsTask?.value
    if let channel = viewModel.currentChannel {
      let unreadChannel = Channel(dto: channel)
      unreadChannel.unreadCount = Self.unreadCount
      viewModel.timeline.stageOpen(.channel(ChannelDTO(from: unreadChannel)))
    } else if let contact = viewModel.currentContact {
      let unreadContact = Contact(dto: contact)
      unreadContact.unreadCount = Self.unreadCount
      viewModel.timeline.stageOpen(.dm(ContactDTO(from: unreadContact)))
    }
    try await environment.store.updateMessageStatus(id: environment.reaction.id, status: .failed)
    let fetchFinished = AsyncStream<Void>.makeStream()
    let resumeFetch = AsyncStream<Void>.makeStream()
    defer {
      coordinator.testPopulateAfterFetchHook = nil
      fetchFinished.continuation.finish()
      resumeFetch.continuation.finish()
    }
    coordinator.testPopulateAfterFetchHook = {
      fetchFinished.continuation.yield(())
      for await _ in resumeFetch.stream {}
    }
    let failureTask = Task {
      defer { fetchFinished.continuation.finish() }
      await viewModel.handle(.messageFailed(messageID: environment.reaction.id))
    }
    defer { failureTask.cancel() }
    var fetchEvents = fetchFinished.stream.makeAsyncIterator()
    try #require(await fetchEvents.next() != nil)
    let messageIDs = viewModel.messages.map(\.id)
    let renderState = viewModel.renderState
    let anchorState = viewModel.timeline.openAnchorState
    let snapshot = viewModel.timeline.firstSnapshot
    let dividerID = viewModel.bake.newMessagesDividerMessageID
    let dividerComputed = viewModel.bake.dividerComputed
    let cachedMessageIDs = Set(viewModel.bake.formattedTextCache.keys)
    #expect(snapshot == .withhold)
    #expect(dividerComputed == false)

    viewModel.releaseTimelineWriter()
    resumeFetch.continuation.finish()
    await failureTask.value
    await coordinator.coalescedReloadTask?.value
    await coordinator.buildItemsTask?.value

    #expect(viewModel.messages.map(\.id) == messageIDs)
    #expect(viewModel.messagesByID[environment.reaction.id] == nil)
    #expect(viewModel.renderState == renderState)
    #expect(viewModel.timeline.openAnchorState == anchorState)
    #expect(viewModel.timeline.firstSnapshot == snapshot)
    #expect(viewModel.bake.newMessagesDividerMessageID == dividerID)
    #expect(viewModel.bake.dividerComputed == dividerComputed)
    #expect(Set(viewModel.bake.formattedTextCache.keys) == cachedMessageIDs)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.sendErrorMessage == nil)
    #expect(try await environment.store.fetchMessage(id: environment.reaction.id)?.failureSeen == false)
  }

  private func expectChronologicalMessages(_ viewModel: ChatViewModel, expected: [MessageDTO], rawCount: Int) {
    #expect(viewModel.messages.map(\.id) == expected.map(\.id))
    #expect(viewModel.items.map(\.id) == expected.map(\.id))
    #expect(viewModel.messages.map(\.sortDate) == viewModel.messages.map(\.sortDate).sorted())
    #expect(viewModel.totalFetchedCount == rawCount)
  }

  private func makeEnvironment(isChannel: Bool, window: LoadedWindow) async throws -> Environment {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(
      dataStore: { store }, connectionState: { .ready }, currentRadioID: { radioID }
    ))
    let contactID: UUID?
    let channelIndex: UInt8?
    let conversationID: ChatConversationID
    if isChannel {
      let channel = ChannelDTO(
        id: UUID(), radioID: radioID, index: 0, name: "test",
        secret: Data(repeating: 1, count: 16), isEnabled: true,
        lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveChannel(channel)
      viewModel.currentChannel = channel
      contactID = nil
      channelIndex = channel.index
      conversationID = .channel(radioID: radioID, channelIndex: channel.index)
    } else {
      let contact = ContactDTO(
        id: UUID(), radioID: radioID, publicKey: Data(repeating: 2, count: 32),
        name: Self.senderName, typeRawValue: ContactType.chat.rawValue, flags: 0,
        outPathLength: 0, outPath: Data(), lastAdvertTimestamp: 0,
        latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
        nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
        lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveContact(contact)
      viewModel.currentContact = contact
      contactID = contact.id
      channelIndex = nil
      conversationID = .dm(radioID: radioID, contactID: contact.id)
    }

    let olderCount = switch window {
    case .firstPage: 0
    case .paged: ChatCoordinator.pageSize * 2 + Self.olderRowRemainder
    case .boundary: Self.olderRowRemainder
    }
    var history: [MessageDTO] = []
    for index in 0..<olderCount {
      history.append(makeIncoming(
        radioID: radioID, contactID: contactID, channelIndex: channelIndex,
        text: "Message \(index)", timestamp: Self.baseTimestamp + UInt32(index)
      ))
    }
    let target = makeIncoming(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: "Reaction target", timestamp: Self.baseTimestamp + UInt32(olderCount)
    )
    let service = ReactionService()
    let text = isChannel
      ? service.buildReactionText(
        emoji: Self.emoji, targetSender: Self.senderName, targetText: target.text, targetTimestamp: target.reactionTimestamp
      )
      : service.buildDMReactionText(emoji: Self.emoji, targetText: target.text, targetTimestamp: target.reactionTimestamp)
    let reactionTimestamp = target.timestamp + 1
    let reaction = MessageDTO(from: Message(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: text, timestamp: reactionTimestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(reactionTimestamp)),
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: MessageStatus.sent.rawValue,
      reactionTargetMessageID: target.id, reactionSenderName: "Test Radio"
    ))
    let newer = makeIncoming(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: "Newer message", timestamp: reactionTimestamp + 1
    )
    history.append(contentsOf: [target, reaction, newer])
    let newerCount = window == .boundary ? ChatCoordinator.pageSize * 2 - 1 : 1
    for index in 1..<newerCount {
      history.append(makeIncoming(
        radioID: radioID, contactID: contactID, channelIndex: channelIndex,
        text: "Newer message \(index)", timestamp: newer.timestamp + UInt32(index)
      ))
    }
    for message in history {
      try await store.saveMessage(message)
    }
    viewModel.bindCoordinatorForTesting(ChatCoordinator(conversationID: conversationID, dataStore: store))
    if let channel = viewModel.currentChannel {
      await viewModel.loadChannelMessages(for: channel, populateMode: .replace)
    } else if let contact = viewModel.currentContact {
      await viewModel.loadMessages(for: contact, populateMode: .replace)
    }
    return Environment(
      viewModel: viewModel, store: store, target: target, reaction: reaction, newer: newer, history: history
    )
  }

  private func makeIncoming(
    radioID: UUID, contactID: UUID?, channelIndex: UInt8?, text: String, timestamp: UInt32
  ) -> MessageDTO {
    MessageDTO(from: Message(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: text, timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      directionRawValue: MessageDirection.incoming.rawValue,
      statusRawValue: MessageStatus.delivered.rawValue,
      senderNodeName: channelIndex != nil ? Self.senderName : nil
    ))
  }

  private struct Environment {
    let viewModel: ChatViewModel
    let store: PersistenceStore
    let target: MessageDTO
    let reaction: MessageDTO
    let newer: MessageDTO
    let history: [MessageDTO]
  }

  private enum LoadedWindow: CaseIterable, Sendable {
    case firstPage
    case paged
    case boundary
  }
}
