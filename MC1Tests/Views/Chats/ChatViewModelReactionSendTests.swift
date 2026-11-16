import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ChatViewModel reaction sends")
@MainActor
struct ChatViewModelReactionSendTests {
  @Test
  func `reaction stays queued after an earlier request times out`() async throws {
    let radio = ConnectionTestRadioTransport()
    let clock = TestClock()
    let session = MeshCoreSession(transport: radio, clock: clock)
    try await session.start()
    let environment = try await makeEnvironment(isChannel: true)
    let channel = try #require(environment.viewModel.currentChannel)
    let device = DeviceDTO(from: Device(
      radioID: channel.radioID, publicKey: radio.publicKey, nodeName: "Test Radio",
      firmwareVersion: radio.firmwareVersion
    ))
    try await environment.store.saveDevice(device)
    await radio.setChannel(index: channel.index, name: channel.name, secret: channel.secret)
    let reactions = ReactionService()
    let messageService = MessageService(session: session, dataStore: environment.store, contactService: nil)
    let channelService = ChannelService(session: session, dataStore: environment.store, rxLogService: nil)
    let queue = ChatSendQueueService(
      radioID: channel.radioID, dataStore: environment.store, messageService: messageService,
      channelService: channelService, reactionService: reactions
    )
    let viewModel = environment.viewModel
    viewModel.configureForTesting(dependencies: .testDefaults(
      dataStore: { environment.store }, messageService: { messageService }, channelService: { channelService },
      connectionState: { .ready }, connectedDevice: { device }, currentRadioID: { channel.radioID },
      reactionService: { reactions }, chatSendQueueService: { queue }
    ))

    do {
      await radio.holdReplies(for: .getBattery)
      let battery = Task { try await session.getBattery() }
      try await waitUntil("battery query should be awaiting its reply") {
        await radio.heldReplyCount(for: .getBattery) == 1 && clock.sleeperCount == 1
      }
      clock.advance(by: .seconds(5))
      await #expect { try await battery.value } throws: { error in
        if case MeshCoreError.timeout = error { return true }
        return false
      }

      await viewModel.sendReaction(emoji: "👀", to: environment.target)
      try await waitUntil("reaction should park for connection recovery") {
        await queue.parkedDrainCountForTesting > 0
      }
      await viewModel.sendReaction(emoji: "👀", to: environment.target)
      let pending = try await environment.store.fetchPendingSends(radioID: channel.radioID)
      let queued = try #require(pending.first)
      #expect(pending.count == 1)
      #expect(try await environment.store.fetchMessage(id: queued.messageID)?.status == .pending)
      #expect(try await environment.store.fetchReactions(for: environment.target.id).isEmpty)
      #expect(await radio.sentData.contains { $0.first == CommandCode.sendChannelMessage.rawValue } == false)
      #expect(viewModel.errorMessage == nil)
      #expect(viewModel.sendErrorMessage == nil)

      viewModel.releaseTimelineWriter()
      await queue.shutdown()
      await queue.awaitDrainCompletion()
      #expect(try await environment.store.hasPendingSend(messageID: queued.messageID))
      await session.stop()
      await radio.releaseAllGates()
    } catch {
      await queue.shutdown()
      await session.stop()
      await radio.releaseAllGates()
      throw error
    }
  }

  @Test(arguments: [false, true])
  func `missing queue surfaces a send error that a reload preserves`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel)
    let viewModel = environment.viewModel

    await viewModel.sendReaction(emoji: "👀", to: environment.target)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    await reload(viewModel)

    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(try await environment.store.fetchPendingSends(radioID: environment.target.radioID).isEmpty)
  }

  @Test(arguments: [false, true])
  func `failed reaction appears after a reload hid its pending row`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel)
    let reaction = makeReaction(to: environment.target)
    try await environment.store.saveMessage(reaction)
    let viewModel = environment.viewModel
    await reload(viewModel)
    #expect(viewModel.messagesByID[reaction.id] == nil)

    try await environment.store.updateMessageStatus(id: reaction.id, status: .failed)
    await viewModel.handle(.messageFailed(messageID: reaction.id))

    #expect(viewModel.messagesByID[reaction.id]?.status == .failed)
    #expect(viewModel.items.contains { $0.id == reaction.id })
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(try await environment.store.fetchMessage(id: reaction.id)?.failureSeen == true)
    await reload(viewModel)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
  }

  @Test(arguments: [false, true])
  func `DM reaction retry hides its failed row and retains single attempt policy`(isResend: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: false)
    var reaction = makeReaction(to: environment.target)
    reaction.status = .failed
    try await environment.store.saveMessage(reaction)
    let viewModel = environment.viewModel
    await viewModel.handle(.messageFailed(messageID: reaction.id))
    let fetchedCount = viewModel.totalFetchedCount
    #expect(viewModel.items.contains { $0.id == reaction.id })

    if isResend {
      await viewModel.sendAgain(reaction)
    } else {
      await viewModel.retryMessage(reaction)
    }

    let pending = try #require(try await environment.store.fetchPendingSends(radioID: reaction.radioID).first)
    #expect(pending.messageID == reaction.id)
    #expect(pending.isSingleAttempt)
    #expect(viewModel.messagesByID[reaction.id] == nil)
    #expect(viewModel.items.contains { $0.id == reaction.id } == false)
    #expect(viewModel.totalFetchedCount == fetchedCount)
  }

  @Test
  func `channel reaction retry rejection restores its failed row`() async throws {
    let environment = try await makeEnvironment(isChannel: true)
    let service = MessageService(
      session: MeshCoreSession(transport: MockTransport()), dataStore: environment.store, contactService: nil
    )
    let viewModel = environment.viewModel
    viewModel.configureForTesting(dependencies: .testDefaults(
      dataStore: { environment.store }, messageService: { service }, currentRadioID: { environment.target.radioID }
    ))
    var reaction = makeReaction(to: environment.target)
    reaction.status = .failed
    try await environment.store.saveMessage(reaction)
    await viewModel.handle(.messageFailed(messageID: reaction.id))

    await viewModel.retryChannelMessage(reaction)

    #expect(viewModel.messagesByID[reaction.id]?.status == .failed)
    #expect(viewModel.items.contains { $0.id == reaction.id })
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(try await environment.store.fetchMessage(id: reaction.id)?.failureSeen == true)
    #expect(try await environment.store.hasPendingSend(messageID: reaction.id) == false)
  }

  @Test(arguments: [false, true], [false, true])
  func `accepted reaction hides its failed row and refreshes the target badge`(isChannel: Bool, isResend: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel)
    var reaction = makeReaction(to: environment.target)
    reaction.status = .failed
    try await environment.store.saveMessage(reaction)
    let viewModel = environment.viewModel
    await viewModel.handle(.messageFailed(messageID: reaction.id))
    let fetchedCount = viewModel.totalFetchedCount
    try await environment.store.updateMessageStatus(id: reaction.id, status: .sent)

    if isResend {
      await viewModel.handle(.messageResent(messageID: reaction.id))
    } else {
      await viewModel.handle(.messageStatusResolved(messageID: reaction.id, status: .sent))
    }
    await viewModel.handle(.reactionReceived(messageID: environment.target.id, summary: "👀:1"))
    await viewModel.coordinator?.coalescedReloadTask?.value
    await viewModel.coordinator?.buildItemsTask?.value

    #expect(viewModel.messagesByID[reaction.id] == nil)
    #expect(viewModel.items.contains { $0.id == reaction.id } == false)
    #expect(viewModel.messagesByID[environment.target.id]?.reactionSummary == "👀:1")
    #expect(viewModel.totalFetchedCount == fetchedCount)
  }

  @Test(arguments: [false, true], [false, true])
  func `another conversation's reaction failure does not enter the open conversation`(isChannel: Bool, isOtherRadio: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel)
    var reaction = makeReaction(to: environment.target)
    if isOtherRadio {
      reaction.radioID = UUID()
    } else if isChannel {
      reaction.channelIndex = 1
    } else {
      reaction.contactID = UUID()
    }
    try await environment.store.saveMessage(reaction)
    try await environment.store.updateMessageStatus(id: reaction.id, status: .failed)

    await environment.viewModel.handle(.messageFailed(messageID: reaction.id))

    #expect(environment.viewModel.messagesByID[reaction.id] == nil)
    #expect(environment.viewModel.sendErrorMessage == nil)
    #expect(try await environment.store.fetchMessage(id: reaction.id)?.failureSeen == false)
  }

  @Test(arguments: [false, true])
  func `a dismissed conversation leaves a reaction failure unseen`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(isChannel: isChannel)
    let reaction = makeReaction(to: environment.target)
    try await environment.store.saveMessage(reaction)
    try await environment.store.updateMessageStatus(id: reaction.id, status: .failed)
    environment.viewModel.releaseTimelineWriter()

    await environment.viewModel.handle(.messageFailed(messageID: reaction.id))

    #expect(environment.viewModel.messagesByID[reaction.id] == nil)
    #expect(environment.viewModel.sendErrorMessage == nil)
    #expect(try await environment.store.fetchMessage(id: reaction.id)?.failureSeen == false)
  }

  private func makeEnvironment(isChannel: Bool) async throws -> (viewModel: ChatViewModel, store: PersistenceStore, target: MessageDTO) {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { store }, currentRadioID: { radioID }))
    let target: MessageDTO
    let conversationID: ChatConversationID
    if isChannel {
      let channel = ChannelDTO(
        id: UUID(), radioID: radioID, index: 0, name: "test",
        secret: Data(repeating: 1, count: 16), isEnabled: true,
        lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveChannel(channel)
      viewModel.currentChannel = channel
      conversationID = .channel(radioID: radioID, channelIndex: channel.index)
      target = MessageDTO(from: Message(
        radioID: radioID, channelIndex: channel.index, text: "1 hop to STP",
        timestamp: 1_700_000_000, directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue, senderNodeName: "Melody"
      ))
    } else {
      let contact = ContactDTO(
        id: UUID(), radioID: radioID, publicKey: Data(repeating: 2, count: 32),
        name: "Melody", typeRawValue: ContactType.chat.rawValue, flags: 0,
        outPathLength: 0, outPath: Data(), lastAdvertTimestamp: 0,
        latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
        nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
        lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveContact(contact)
      viewModel.currentContact = contact
      conversationID = .dm(radioID: radioID, contactID: contact.id)
      target = MessageDTO(from: Message(
        radioID: radioID, contactID: contact.id, text: "1 hop to STP",
        timestamp: 1_700_000_000, directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue
      ))
    }
    try await store.saveMessage(target)
    viewModel.bindCoordinatorForTesting(ChatCoordinator(conversationID: conversationID, dataStore: store))
    await reload(viewModel)
    return (viewModel, store, target)
  }

  private func reload(_ viewModel: ChatViewModel) async {
    if let channel = viewModel.currentChannel {
      await viewModel.loadChannelMessages(for: channel, populateMode: .refreshWindow)
    } else if let contact = viewModel.currentContact {
      await viewModel.loadMessages(for: contact, populateMode: .refreshWindow)
    }
  }

  private func makeReaction(to target: MessageDTO) -> MessageDTO {
    let service = ReactionService()
    let text = if target.contactID != nil {
      service.buildDMReactionText(emoji: "👀", targetText: target.text, targetTimestamp: target.reactionTimestamp)
    } else {
      service.buildReactionText(
        emoji: "👀", targetSender: "Melody", targetText: target.text, targetTimestamp: target.reactionTimestamp
      )
    }
    return MessageDTO(from: Message(
      radioID: target.radioID, contactID: target.contactID, channelIndex: target.channelIndex,
      text: text, timestamp: target.timestamp + 1,
      directionRawValue: MessageDirection.outgoing.rawValue, statusRawValue: MessageStatus.pending.rawValue,
      reactionTargetMessageID: target.id, reactionSenderName: "Test Radio"
    ))
  }
}
