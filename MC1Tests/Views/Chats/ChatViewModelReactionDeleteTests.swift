import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatViewModel reaction deletion")
@MainActor
struct ChatViewModelReactionDeleteTests {
  private static let baseTimestamp: UInt32 = 1_700_000_000
  private static let olderRowRemainder = 3
  private static let emoji = "👀"
  private static let senderName = "Melody"

  @Test(arguments: [false, true], [false, true])
  func `deleting a target removes its reaction and preserves older paging`(isChannel: Bool, isFailed: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel, isFailed: isFailed)
    let viewModel = environment.viewModel
    await viewModel.loadOlderMessages()
    let oldestLoadedID = try #require(viewModel.messages.first?.id)
    let rawCount = viewModel.totalFetchedCount
    #expect(rawCount == ChatCoordinator.pageSize * 2)
    #expect((viewModel.messagesByID[environment.child.id] != nil) == isFailed)

    await viewModel.deleteMessage(environment.target)
    await viewModel.coordinator?.buildItemsTask?.value

    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.messagesByID[environment.target.id] == nil)
    #expect(viewModel.messagesByID[environment.child.id] == nil)
    #expect(viewModel.items.contains { $0.id == environment.child.id } == false)
    #expect(viewModel.messagesByID[oldestLoadedID] != nil)
    #expect(viewModel.totalFetchedCount == rawCount - 2)
    #expect(try await environment.store.fetchMessage(id: environment.child.id) == nil)

    await viewModel.loadOlderMessages()
    await viewModel.coordinator?.buildItemsTask?.value

    #expect(Set(viewModel.messages.map(\.id)) == Set(environment.history.map(\.id)))
    #expect(viewModel.totalFetchedCount == environment.history.count)
    #expect(viewModel.hasMoreMessages == false)
  }

  private func makeEnvironment(isChannel: Bool, isFailed: Bool) async throws -> Environment {
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
    let olderCount = ChatCoordinator.pageSize * 2 + Self.olderRowRemainder
    var history: [MessageDTO] = []
    for index in 0..<olderCount {
      let timestamp = Self.baseTimestamp + UInt32(index)
      let message = MessageDTO(from: Message(
        radioID: radioID, contactID: contactID, channelIndex: channelIndex,
        text: "Message \(index)", timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        senderNodeName: isChannel ? Self.senderName : nil
      ))
      try await store.saveMessage(message)
      history.append(message)
    }
    let timestamp = Self.baseTimestamp + UInt32(olderCount)
    let target = MessageDTO(from: Message(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: "Reaction target", timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      directionRawValue: MessageDirection.incoming.rawValue,
      statusRawValue: MessageStatus.delivered.rawValue,
      senderNodeName: isChannel ? Self.senderName : nil
    ))
    try await store.saveMessage(target)
    let service = ReactionService()
    let text = isChannel
      ? service.buildReactionText(
        emoji: Self.emoji, targetSender: Self.senderName, targetText: target.text, targetTimestamp: target.reactionTimestamp
      )
      : service.buildDMReactionText(emoji: Self.emoji, targetText: target.text, targetTimestamp: target.reactionTimestamp)
    let child = MessageDTO(from: Message(
      radioID: radioID, contactID: contactID, channelIndex: channelIndex,
      text: text, timestamp: timestamp + 1,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp + 1)),
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: (isFailed ? MessageStatus.failed : .sent).rawValue,
      reactionTargetMessageID: target.id, reactionSenderName: "Test Radio"
    ))
    try await store.saveMessage(child)
    viewModel.bindCoordinatorForTesting(ChatCoordinator(conversationID: conversationID, dataStore: store))
    if let channel = viewModel.currentChannel {
      await viewModel.loadChannelMessages(for: channel, populateMode: .replace)
    } else if let contact = viewModel.currentContact {
      await viewModel.loadMessages(for: contact, populateMode: .replace)
    }
    return Environment(viewModel: viewModel, store: store, target: target, child: child, history: history)
  }

  private struct Environment {
    let viewModel: ChatViewModel
    let store: PersistenceStore
    let target: MessageDTO
    let child: MessageDTO
    let history: [MessageDTO]
  }
}
