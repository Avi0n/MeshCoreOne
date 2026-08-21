import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

@Suite("Incoming avatar cluster", .isolatedIncomingAvatarJPEGStore)
@MainActor
struct IncomingAvatarClusterTests {
  init() {
    MapSnapshotStore.shared.clear()
  }

  @Test
  func `two incoming channel same sender 60s — first hides avatar, second shows`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == false
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `sender change — both incoming channel rows show avatar`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Bob")

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == true
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `incoming then outgoing channel — incoming shows avatar`() {
    let incoming = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let outgoing = createChannelMessage(timestamp: 1060, senderName: nil, isOutgoing: true)

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: incoming, previous: nil, next: outgoing)
        .isClusterEnd == true
    )
  }

  @Test
  func `300s gap — first incoming channel hides avatar`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1300, senderName: "Alice")
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == false
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `two nil-name incoming channel 60s — both show avatar`() {
    let first = createChannelMessage(timestamp: 1000, senderName: nil)
    let second = createChannelMessage(timestamp: 1060, senderName: nil)
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == true
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `301s gap — first incoming channel shows avatar`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1301, senderName: "Alice")

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == true
    )
  }

  @Test
  func `day change within 300s same sender — cluster continues`() {
    let first = createChannelMessage(
      timestamp: makeTimestamp(2024, 5, 1, 23, 58),
      senderName: "Alice"
    )
    let second = createChannelMessage(
      timestamp: makeTimestamp(2024, 5, 2, 0, 1),
      senderName: "Alice"
    )

    let firstFlags = ChatMessageBakeState.computeDisplayFlags(
      for: first, previous: nil, next: second
    )
    let secondFlags = ChatMessageBakeState.computeDisplayFlags(
      for: second, previous: first, next: nil
    )
    #expect(firstFlags.isClusterEnd == false)
    #expect(secondFlags.isClusterEnd == true)
    #expect(secondFlags.showDayDivider == true)
  }

  @Test
  func `two incoming DMs 60s apart both hide avatar`() {
    let first = createIncomingDM(timestamp: 1000)
    let second = createIncomingDM(timestamp: 1060)

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == false
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == false
    )
  }

  @Test
  func `outgoing channel hides avatar`() {
    let message = createChannelMessage(timestamp: 1000, senderName: nil, isOutgoing: true)
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: message, previous: nil, next: nil)
        .isClusterEnd == false
    )
  }

  @Test
  func `two empty-name incoming channel 60s — first hides avatar, second shows`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "")
    let second = createChannelMessage(timestamp: 1060, senderName: "")

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == false
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `Alice then empty name — both incoming channel rows show avatar`() {
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "")

    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: first, previous: nil, next: second)
        .isClusterEnd == true
    )
    #expect(
      ChatMessageBakeState.computeDisplayFlags(for: second, previous: first, next: nil)
        .isClusterEnd == true
    )
  }

  @Test
  func `channel follow-up admit hands avatar to the new cluster-end`() {
    let viewModel = boundViewModel()
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")

    viewModel.appendMessageIfNew(first)
    #expect(viewModel.items[0].envelope.incomingAvatar != nil)

    viewModel.appendMessageIfNew(second)
    #expect(viewModel.items.count == 2)
    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)
  }

  @Test
  func `incoming then outgoing channel admit keeps incoming avatar`() {
    let viewModel = boundViewModel()
    let incoming = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let outgoing = createChannelMessage(timestamp: 1060, senderName: nil, isOutgoing: true)

    viewModel.appendMessageIfNew(incoming)
    viewModel.appendMessageIfNew(outgoing)

    #expect(viewModel.items[0].envelope.incomingAvatar != nil)
    #expect(viewModel.items[1].envelope.incomingAvatar == nil)
  }

  @Test
  func `DM admits do not rewrite the previous item`() throws {
    let viewModel = boundViewModel()
    let first = createIncomingDM(timestamp: 1000)
    let second = createIncomingDM(timestamp: 1060)

    viewModel.appendMessageIfNew(first)
    let afterFirst = try #require(viewModel.items.first)

    viewModel.appendMessageIfNew(second)
    #expect(viewModel.items.count == 2)
    #expect(viewModel.items[0] == afterFirst)
    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar == nil)
  }

  @Test
  func `delete last incoming channel promotes the previous avatar`() {
    let viewModel = boundViewModel()
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)

    viewModel.timeline.removeMessage(second.id)

    #expect(viewModel.items.count == 1)
    #expect(viewModel.items[0].id == first.id)
    #expect(viewModel.items[0].envelope.incomingAvatar != nil)
  }

  @Test
  func `delete middle of 250s Alice cluster splits into two cluster-ends`() {
    let viewModel = boundViewModel()
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let middle = createChannelMessage(timestamp: 1250, senderName: "Alice")
    let last = createChannelMessage(timestamp: 1500, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(middle)
    viewModel.appendMessageIfNew(last)

    viewModel.timeline.removeMessage(middle.id)

    #expect(viewModel.items.count == 2)
    #expect(viewModel.items[0].envelope.incomingAvatar != nil)
    #expect(viewModel.items[1].grouping.showSenderName == true)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)
  }

  @Test
  func `delete middle of a tight Alice burst keeps one cluster-end`() {
    let viewModel = boundViewModel()
    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let middle = createChannelMessage(timestamp: 1060, senderName: "Alice")
    let last = createChannelMessage(timestamp: 1120, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(middle)
    viewModel.appendMessageIfNew(last)

    viewModel.timeline.removeMessage(middle.id)

    #expect(viewModel.items.count == 2)
    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)
  }

  @Test
  func `delete incoming DM leaves remaining item bit-identical`() {
    let viewModel = boundViewModel()
    let first = createIncomingDM(timestamp: 1000)
    let second = createIncomingDM(timestamp: 1060)
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)
    let remaining = viewModel.items[0]

    viewModel.timeline.removeMessage(second.id)

    #expect(viewModel.items.count == 1)
    #expect(viewModel.items[0] == remaining)
    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
  }

  @Test
  func `contact-table patch rewrites only rows that already have an identity`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channel = ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: 0,
      name: "Ops",
      secret: Data(),
      isEnabled: true,
      lastMessageDate: Date(),
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false
    )
    let alice = ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "Alice",
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
      lastMessageDate: nil,
      unreadCount: 0,
      avatarImageData: Data("jpeg-alice".utf8)
    )
    try await dataStore.saveContact(alice)

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    viewModel.timeline.stageOpen(.channel(channel))

    let first = createChannelMessage(timestamp: 1000, senderName: "Alice")
    let second = createChannelMessage(timestamp: 1060, senderName: "Alice")
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)

    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar != nil)

    await viewModel.loadAllContacts(radioID: radioID)

    #expect(viewModel.items[0].envelope.incomingAvatar == nil)
    #expect(viewModel.items[1].envelope.incomingAvatar?.matchedContactID == alice.id)
  }
}

@MainActor
private func boundViewModel() -> ChatViewModel {
  let viewModel = ChatViewModel()
  let coordinator = ChatCoordinator.makeForTesting()
  viewModel.bindCoordinatorForTesting(coordinator)
  return viewModel
}

private func createChannelMessage(
  timestamp: UInt32,
  senderName: String? = nil,
  isOutgoing: Bool = false
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: UUID(),
    contactID: nil,
    channelIndex: 0,
    text: "Test message",
    timestamp: timestamp,
    createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
    direction: isOutgoing ? .outgoing : .incoming,
    status: isOutgoing ? .sent : .delivered,
    textType: .plain,
    ackCode: nil,
    pathLength: 0,
    snr: nil,
    senderKeyPrefix: nil,
    senderNodeName: senderName,
    isRead: false,
    replyToID: nil,
    roundTripTime: nil,
    heardRepeats: 0,
    retryAttempt: 0,
    maxRetryAttempts: 0
  )
}

private func createIncomingDM(timestamp: UInt32) -> MessageDTO {
  let message = Message(
    id: UUID(),
    radioID: UUID(),
    contactID: UUID(),
    text: "Test",
    timestamp: timestamp,
    directionRawValue: MessageDirection.incoming.rawValue,
    statusRawValue: MessageStatus.delivered.rawValue
  )
  return MessageDTO(from: message)
}

private func makeDate(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
  Calendar.current.date(
    from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)
  )!
}

private func makeTimestamp(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> UInt32 {
  UInt32(makeDate(year, month, day, hour, minute).timeIntervalSince1970)
}
