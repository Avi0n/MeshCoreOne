import Foundation
@testable import MC1
@testable import MC1Services

extension ChatScrollRequestDeliveryTests {
  struct Fixture {
    let timeline: ChatTimeline
    let conversation: ChatConversationType
    let messages: [MessageDTO]
  }

  func makeFixture(
    isChannel: Bool,
    hiddenMiddlePage: Bool = false,
    messageCount: Int = ChatCoordinator.pageSize * 2 + 3
  ) async throws -> Fixture {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contact = ContactDTO(
      id: UUID(), radioID: radioID,
      publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize),
      name: "Alice", typeRawValue: ContactType.chat.rawValue, flags: 0,
      outPathLength: 0, outPath: Data(), lastAdvertTimestamp: 0,
      latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
      nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
      lastMessageDate: nil, unreadCount: 0
    )
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: 1, name: "General",
      secret: Data(repeating: 0, count: 16), isEnabled: true,
      lastMessageDate: nil, unreadCount: 0
    )
    let conversation: ChatConversationType = isChannel ? .channel(channel) : .dm(contact)
    var messages: [MessageDTO] = []
    for offset in 0..<messageCount {
      let timestamp = UInt32(1000 + offset)
      var message = MessageDTO(
        id: UUID(), radioID: radioID,
        contactID: isChannel ? nil : contact.id,
        channelIndex: isChannel ? channel.index : nil,
        text: "Message \(offset)", timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        direction: .incoming, status: .delivered, textType: .plain,
        ackCode: nil, pathLength: 0, snr: nil,
        senderKeyPrefix: nil, senderNodeName: isChannel ? "Alice" : nil,
        isRead: true, replyToID: nil, roundTripTime: nil,
        heardRepeats: 0, retryAttempt: 0, maxRetryAttempts: 0
      )
      if hiddenMiddlePage, (3..<(3 + ChatCoordinator.pageSize)).contains(offset) {
        let reactions = ReactionService()
        message.direction = .outgoing
        message.status = .sent
        message.text = isChannel
          ? reactions.buildReactionText(emoji: "👍", targetSender: "Alice", targetText: "Message 0", targetTimestamp: 1000)
          : reactions.buildDMReactionText(emoji: "👍", targetText: "Message 0", targetTimestamp: 1000)
      }
      messages.append(message)
      try await dataStore.saveMessage(message)
    }
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)
    let timeline = ChatTimeline(role: .interactive)
    timeline.bind(
      registry.coordinator(for: conversation.coordinatorID),
      dataStore: { dataStore }, senderTables: { .empty }, postApply: nil
    )
    let outcome = await timeline.open(conversation, reactions: nil, populateMode: .replace)
    guard case .loaded = outcome else {
      throw FixtureError.failedToPopulate
    }
    await timeline.coordinator?.buildItemsTask?.value
    return Fixture(timeline: timeline, conversation: conversation, messages: messages)
  }

  enum FixtureError: Error {
    case failedToPopulate
  }

  func navigate(
    _ navigation: NavigationCoordinator,
    to conversation: ChatConversationType,
    messageID: UUID
  ) {
    switch conversation {
    case let .dm(contact):
      navigation.navigateToChat(with: contact, scrollToMessageID: messageID)
    case let .channel(channel):
      navigation.navigateToChannel(with: channel, scrollToMessageID: messageID)
    }
  }

  func deliver(_ fixture: Fixture, from navigation: NavigationCoordinator, canHonor: Bool = true) async -> UUID? {
    await ChatScrollRequestDelivery.takeIfHonorable(
      from: navigation,
      kind: fixture.conversation.chatRouteKind,
      conversationID: fixture.conversation.conversationID,
      canHonor: canHonor,
      timeline: fixture.timeline,
      loadOlder: { _ = try? await fixture.timeline.loadOlder() }
    )
  }

  @MainActor
  final class Signal {
    private var continuation: CheckedContinuation<Void, Never>?
    private var opened = false

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }

    func wait() async {
      if opened { return }
      await withCheckedContinuation { continuation = $0 }
    }
  }

  actor Gate {
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
}
