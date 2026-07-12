import Foundation
@testable import MC1
@testable import MC1Services
import Testing

// MARK: - Test Helpers

private func createTestContact(
  radioID: UUID = UUID(),
  name: String = "TestContact",
  type: ContactType = .chat,
  isBlocked: Bool = false
) -> ContactDTO {
  let contact = Contact(
    id: UUID(),
    radioID: radioID,
    publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
    name: name,
    typeRawValue: type.rawValue,
    flags: 0,
    outPathLength: 2,
    outPath: Data([0x01, 0x02]),
    lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
    latitude: 0,
    longitude: 0,
    lastModified: UInt32(Date().timeIntervalSince1970),
    isBlocked: isBlocked
  )
  return ContactDTO(from: contact)
}

private func createTestMessage(
  timestamp: UInt32,
  createdAt: Date? = nil,
  sortDate: Date? = nil,
  text: String = "Test message"
) -> MessageDTO {
  let resolvedCreatedAt = createdAt ?? Date(timeIntervalSince1970: TimeInterval(timestamp))
  let message = Message(
    id: UUID(),
    radioID: UUID(),
    contactID: UUID(),
    text: text,
    timestamp: timestamp,
    createdAt: resolvedCreatedAt,
    sortDate: sortDate,
    directionRawValue: MessageDirection.outgoing.rawValue,
    statusRawValue: MessageStatus.sent.rawValue
  )
  return MessageDTO(from: message)
}

private func createChannelMessage(
  timestamp: UInt32,
  createdAt: Date? = nil,
  senderName: String? = nil,
  isOutgoing: Bool = false,
  text: String = "Test message"
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: UUID(),
    contactID: nil, // nil = channel message
    channelIndex: 0,
    text: text,
    timestamp: timestamp,
    createdAt: createdAt ?? Date(timeIntervalSince1970: TimeInterval(timestamp)),
    direction: isOutgoing ? .outgoing : .incoming,
    status: isOutgoing ? .sent : .delivered,
    textType: .plain,
    ackCode: nil,
    pathLength: 0,
    snr: nil,
    senderKeyPrefix: nil, // Always nil for channel messages per MeshCore protocol
    senderNodeName: senderName,
    isRead: false,
    replyToID: nil,
    roundTripTime: nil,
    heardRepeats: 0,
    retryAttempt: 0,
    maxRetryAttempts: 0
  )
}

/// Builds a calendar date at a specific day and time in the current calendar.
private func makeDate(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
  Calendar.current.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
}

/// Sender-clock timestamp for a day/time. Day-divider detection keys on
/// `MessageDTO.senderDate`, which derives from `timestamp`, so this drives the real path.
private func makeTimestamp(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> UInt32 {
  UInt32(makeDate(year, month, day, hour, minute).timeIntervalSince1970)
}

// MARK: - ChatViewModel Tests

@Suite("ChatViewModel Tests")
@MainActor
struct ChatViewModelTests {
  /// `ChatViewModel.makeBuildInputs` calls `MapSnapshotStore.shared.isResolved`,
  /// which lazily initializes the process-lifetime singleton. Swift Testing
  /// constructs a fresh suite instance per `@Test`, so resetting the singleton
  /// here keeps `resolvedKeys`, `imageEntries`, and `failed` from leaking
  /// between tests in this suite (and from earlier suites that touched it).
  init() {
    MapSnapshotStore.shared.clear()
  }

  // MARK: - Timestamp Logic Tests

  @Test
  func `First message always shows timestamp`() {
    let messages = [
      createTestMessage(timestamp: 1000)
    ]

    let flags = ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil)
    #expect(flags.showTimestamp == true)
  }

  @Test
  func `Consecutive messages within 5 minutes don't show timestamp`() {
    let baseTime: UInt32 = 1000
    let messages = [
      createTestMessage(timestamp: baseTime),
      createTestMessage(timestamp: baseTime + 60), // 1 minute later
      createTestMessage(timestamp: baseTime + 120), // 2 minutes later
      createTestMessage(timestamp: baseTime + 180), // 3 minutes later
      createTestMessage(timestamp: baseTime + 240) // 4 minutes later
    ]

    // First message always shows timestamp
    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)

    // Messages 1-4 shouldn't show timestamp (within 5 min of previous)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showTimestamp == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[3], previous: messages[2]).showTimestamp == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[4], previous: messages[3]).showTimestamp == false)
  }

  @Test
  func `Message after 5+ minute gap shows timestamp`() {
    let baseTime: UInt32 = 1000
    let messages = [
      createTestMessage(timestamp: baseTime),
      createTestMessage(timestamp: baseTime + 301) // 5 min 1 sec later
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == true)
  }

  @Test
  func `Exactly 5 minute gap does not show timestamp`() {
    let baseTime: UInt32 = 1000
    let messages = [
      createTestMessage(timestamp: baseTime),
      createTestMessage(timestamp: baseTime + 300) // Exactly 5 minutes
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false) // 300 is not > 300
  }

  @Test
  func `Backlog block keys divider grouping on send time, not the shared drain anchor`() {
    // Two backlog rows drained together share the anchor as their sortDate, but were
    // sent ten minutes apart. Grouping must follow send time so the divider still
    // appears inside the block; keying on the shared sortDate would collapse it.
    let anchor = Date(timeIntervalSince1970: 5_000_000)
    let earlier = createTestMessage(timestamp: 1000, sortDate: anchor)
    let later = createTestMessage(timestamp: 1600, sortDate: anchor) // +10 min send time
    #expect(ChatViewModel.computeDisplayFlags(for: later, previous: earlier).showTimestamp == true)
  }

  @Test
  @MainActor
  func `Unread divider lands on the first unread row of the recent block`() {
    // Block-at-reconnect layout: older already-read rows, then a recent unread block
    // at the tail. The positional divider must land on the block's first row. This also
    // guards against a regression to a first(where: { !$0.isRead }) scan — every row here
    // has the default isRead == false, so such a scan would wrongly pick index 0.
    let vm = ChatViewModel()
    let readCount = 8
    let unreadCount = 12
    var messages: [MessageDTO] = []
    let readBase = Date(timeIntervalSince1970: 1_000_000)
    for i in 0..<readCount {
      messages.append(createTestMessage(
        timestamp: UInt32(1000 + i),
        sortDate: readBase.addingTimeInterval(TimeInterval(i)),
        text: "read \(i)"
      ))
    }
    let anchor = Date(timeIntervalSince1970: 2_000_000)
    for i in 0..<unreadCount {
      messages.append(createTestMessage(
        timestamp: UInt32(5000 + i),
        sortDate: anchor,
        text: "unread \(i)"
      ))
    }
    let firstUnread = messages[readCount]

    vm.computeDividerPosition(from: messages, unreadCount: unreadCount, isDM: true)

    #expect(vm.newMessagesDividerMessageID == firstUnread.id)
  }

  @Test
  @MainActor
  func `Divider id advances past a filtered-out outgoing reaction at the boundary`() {
    // The positional boundary can land on a sent outgoing reaction, which
    // filterOutgoingReactionMessages drops before buildItems. Keying the divider on that
    // id yields a visible-but-dead button (scrollToItem early-returns on the missing id), so
    // the divider must advance to the next visible row.
    let vm = ChatViewModel()
    let unreadCount = 11
    var messages: [MessageDTO] = []
    for i in 0..<2 {
      messages.append(createTestMessage(timestamp: UInt32(1000 + i), text: "older \(i)"))
    }
    // boundaryIndex = count(13) - unreadCount(11) = 2 → this hidden reaction row.
    let reaction = createTestMessage(timestamp: 1002, text: "👍\nABCDEFGH")
    messages.append(reaction)
    let firstVisibleUnread = createTestMessage(timestamp: 1003, text: "unread visible")
    messages.append(firstVisibleUnread)
    for i in 0..<9 {
      messages.append(createTestMessage(timestamp: UInt32(1004 + i), text: "unread \(i)"))
    }
    // Guard the fixture: the boundary row must actually be a hidden outgoing reaction.
    #expect(vm.isHiddenOutgoingReaction(reaction, isDM: true))

    vm.computeDividerPosition(from: messages, unreadCount: unreadCount, isDM: true)

    #expect(vm.newMessagesDividerMessageID == firstVisibleUnread.id)
  }

  @Test
  @MainActor
  func `Divider clamps to the oldest loaded row when unread exceeds the first page`() {
    // computeDividerPosition sees only the first page while unreadCount is uncapped. When
    // unread >= the loaded count, the positional index clamps to 0, anchoring the divider on
    // the oldest loaded row. Documents that behavior; pagination does not recompute it.
    let vm = ChatViewModel()
    let messages = (0..<30).map { createTestMessage(timestamp: UInt32(1000 + $0), text: "m\($0)") }

    vm.computeDividerPosition(from: messages, unreadCount: 50, isDM: true)

    #expect(vm.newMessagesDividerMessageID == messages[0].id)
  }

  @Test
  func `Mixed gaps show correct timestamps`() {
    let baseTime: UInt32 = 1000
    let messages = [
      createTestMessage(timestamp: baseTime), // 0: Always show
      createTestMessage(timestamp: baseTime + 60), // 1: 1 min - no show
      createTestMessage(timestamp: baseTime + 420), // 2: 6 min gap from prev - show
      createTestMessage(timestamp: baseTime + 480), // 3: 1 min - no show
      createTestMessage(timestamp: baseTime + 900), // 4: 7 min gap - show
      createTestMessage(timestamp: baseTime + 920) // 5: 20 sec - no show
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showTimestamp == true) // 360s gap
    #expect(ChatViewModel.computeDisplayFlags(for: messages[3], previous: messages[2]).showTimestamp == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[4], previous: messages[3]).showTimestamp == true) // 420s gap
    #expect(ChatViewModel.computeDisplayFlags(for: messages[5], previous: messages[4]).showTimestamp == false)
  }

  @Test
  func `buildItems with empty messages produces empty output`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    coordinator.replaceAll([])
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.items.isEmpty)
    #expect(viewModel.messagesByID.isEmpty)
    #expect(viewModel.itemIndexByID.isEmpty)
  }

  @Test
  func `buildItems clears stale mapPreviewRequestIndex so theme-toggle keys do not leak`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // Outgoing message so coordinate-text path runs without sender-name resolution.
    let message = createTestMessage(timestamp: 1000, text: "see 37.7749, -122.4194")
    viewModel.appendMessageIfNew(message)

    let lightOnline = MapSnapshotRequest(latitude: 37.7749, longitude: -122.4194, isDark: false, isOffline: false)
    #expect(viewModel.mapPreviewRequestIndex[lightOnline]?.contains(message.id) == true)

    let darkEnv = EnvInputs(
      autoPlayGIFs: EnvInputs.default.autoPlayGIFs,
      showIncomingPath: EnvInputs.default.showIncomingPath,
      showIncomingHopCount: EnvInputs.default.showIncomingHopCount,
      showIncomingRegion: EnvInputs.default.showIncomingRegion,
      showIncomingSendTime: EnvInputs.default.showIncomingSendTime,
      previewsEnabled: EnvInputs.default.previewsEnabled,
      isHighContrast: EnvInputs.default.isHighContrast,
      isDark: true,
      showMapPreviews: EnvInputs.default.showMapPreviews,
      isOffline: EnvInputs.default.isOffline,
      currentUserName: EnvInputs.default.currentUserName,
      themeID: EnvInputs.default.themeID,
      contentSizeCategory: EnvInputs.default.contentSizeCategory
    )
    viewModel.applyEnvInputs(darkEnv)
    await coordinator.buildItemsTask?.value

    // Stale light-mode key must be gone after the rebuild.
    #expect(viewModel.mapPreviewRequestIndex[lightOnline] == nil)
    let darkOnline = MapSnapshotRequest(latitude: 37.7749, longitude: -122.4194, isDark: true, isOffline: false)
    #expect(viewModel.mapPreviewRequestIndex[darkOnline]?.contains(message.id) == true)
  }

  @Test
  func `a themeID-only EnvInputs change rebuilds items with newly baked theme colors`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // The hashtag run bakes hashtagColor, which differs between default and ember, so a
    // themeID change must produce a different MessageItem. This guards the deliberate baking of
    // bubble colors into MessageItem that defeats the theme-switch-needs-chat-reconfigure
    // landmine, easy to miss because most themes share white outgoing text.
    let message = createTestMessage(timestamp: 1000, text: "ping #news")
    viewModel.appendMessageIfNew(message)
    let before = try #require(viewModel.items.first)

    let emberEnv = EnvInputs(
      autoPlayGIFs: EnvInputs.default.autoPlayGIFs,
      showIncomingPath: EnvInputs.default.showIncomingPath,
      showIncomingHopCount: EnvInputs.default.showIncomingHopCount,
      showIncomingRegion: EnvInputs.default.showIncomingRegion,
      showIncomingSendTime: EnvInputs.default.showIncomingSendTime,
      previewsEnabled: EnvInputs.default.previewsEnabled,
      isHighContrast: EnvInputs.default.isHighContrast,
      isDark: EnvInputs.default.isDark,
      showMapPreviews: EnvInputs.default.showMapPreviews,
      isOffline: EnvInputs.default.isOffline,
      currentUserName: EnvInputs.default.currentUserName,
      themeID: Theme.ember.id,
      contentSizeCategory: EnvInputs.default.contentSizeCategory
    )
    viewModel.applyEnvInputs(emberEnv)
    await coordinator.buildItemsTask?.value

    let after = try #require(viewModel.items.first)
    #expect(after.id == before.id) // same row, re-baked in place
    #expect(after != before) // baked colors changed
  }

  @Test
  func `computeDisplayFlags with same timestamp messages`() {
    let baseTime: UInt32 = 1000
    let first = createTestMessage(timestamp: baseTime, text: "Hello")
    let second = createTestMessage(timestamp: baseTime, text: "World")

    let flags = ChatViewModel.computeDisplayFlags(for: second, previous: first)
    #expect(flags.showTimestamp == false)
    #expect(flags.showDirectionGap == false)
  }

  @Test
  func `Single message array shows timestamp`() {
    let messages = [
      createTestMessage(timestamp: 1000)
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
  }

  @Test
  func `Divider grouping hides the header when send times are close despite a large sortDate gap`() {
    // Sent one second apart but assigned far-apart sortDates (e.g. drained in separate
    // sessions, so distinct anchors). Grouping follows send time, so no header appears.
    let msg1 = createTestMessage(timestamp: 1000, sortDate: Date(timeIntervalSince1970: 1_000_000))
    let msg2 = createTestMessage(timestamp: 1001, sortDate: Date(timeIntervalSince1970: 1_000_600))
    #expect(ChatViewModel.computeDisplayFlags(for: msg2, previous: msg1).showTimestamp == false)
  }

  @Test
  func `Divider grouping ignores drain time: far createdAt with close send times hides the header`() {
    // Received ten minutes apart (createdAt) but sent one second apart. Grouping must
    // follow send time, not drain time, so the rows stay grouped with no header.
    let msg1 = createTestMessage(timestamp: 1000, createdAt: Date(timeIntervalSince1970: 2_000_000))
    let msg2 = createTestMessage(timestamp: 1001, createdAt: Date(timeIntervalSince1970: 2_000_600))
    #expect(ChatViewModel.computeDisplayFlags(for: msg2, previous: msg1).showTimestamp == false)
  }

  @Test
  func `Large time gaps show timestamp`() {
    let baseTime: UInt32 = 1000
    let messages = [
      createTestMessage(timestamp: baseTime),
      createTestMessage(timestamp: baseTime + 86400) // 24 hours later
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == true)
  }

  // MARK: - Conversation Filtering Tests

  @Test
  func `allConversations excludes repeaters`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()

    // Create a mix of contact types
    let chatContact = createTestContact(radioID: radioID, name: "Alice", type: .chat)
    let chatContact2 = createTestContact(radioID: radioID, name: "Bob", type: .chat)
    let repeaterContact = createTestContact(radioID: radioID, name: "Repeater 1", type: .repeater)
    let anotherRepeater = createTestContact(radioID: radioID, name: "Repeater 2", type: .repeater)

    // Set conversations to include repeaters
    viewModel.conversations = [chatContact, chatContact2, repeaterContact, anotherRepeater]
    viewModel.recomputeSnapshot()

    // Verify allConversations excludes repeaters
    let conversations = viewModel.allConversations
    #expect(conversations.count == 2)

    // Verify only chat contacts are included
    let names = conversations.compactMap { conversation -> String? in
      if case let .direct(contact) = conversation {
        return contact.displayName
      }
      return nil
    }
    #expect(names.contains("Alice"))
    #expect(names.contains("Bob"))
    #expect(!names.contains("Repeater 1"))
    #expect(!names.contains("Repeater 2"))
  }

  @Test
  func `allConversations returns empty when only repeaters exist`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()

    // Only repeaters in conversations
    viewModel.conversations = [
      createTestContact(radioID: radioID, name: "Repeater 1", type: .repeater),
      createTestContact(radioID: radioID, name: "Repeater 2", type: .repeater)
    ]
    viewModel.recomputeSnapshot()

    let conversations = viewModel.allConversations
    #expect(conversations.isEmpty)
  }

  // MARK: - Loading State Tests

  @Test
  func `hasLoadedOnce starts false`() {
    let viewModel = ChatViewModel()
    #expect(viewModel.hasLoadedOnce == false)
  }

  @Test
  func `isLoading starts false`() {
    let viewModel = ChatViewModel()
    #expect(viewModel.isLoading == false)
  }

  @Test
  func `renderState.phase starts .uninitialized when no coordinator is bound`() {
    let viewModel = ChatViewModel()
    #expect(viewModel.renderState.phase == .uninitialized)
  }

  @Test
  func `renderState.phase is .loaded after replaceAll on bound coordinator`() {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    coordinator.replaceAll([])

    #expect(viewModel.renderState.phase == .loaded)
    #expect(viewModel.messages.isEmpty)
  }

  @Test
  func `loadMessages settles phase to .loaded when dataStore is nil`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    await viewModel.loadMessages(for: createTestContact())

    #expect(viewModel.renderState.phase == .loaded)
  }

  @Test
  func `loadChannelMessages settles phase to .loaded when dataStore is nil`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    let channel = ChannelDTO(from: Channel(
      radioID: UUID(),
      index: 1,
      name: "Test"
    ))
    await viewModel.loadChannelMessages(for: channel)

    #expect(viewModel.renderState.phase == .loaded)
  }

  // MARK: - Sender Resolution Tests

  @Test
  func `senderResolutionFor uses message.channelIndex, not currentChannel`() {
    let viewModel = ChatViewModel()
    // Resolution must dispatch on intrinsic message data, not on
    // transient view-model state that may not be set during a rebuild.
    #expect(viewModel.currentChannel == nil)

    let channelMessage = createChannelMessage(
      timestamp: 1_700_000_000,
      senderName: "Alice"
    )

    let resolution = viewModel.senderResolutionFor(channelMessage)

    #expect(resolution.displayName == "Alice")
    #expect(resolution.matchKind == .exact)
  }

  @Test
  func `senderResolutionFor returns wire name for channel msg without senderNodeName via hex fallback`() {
    let viewModel = ChatViewModel()
    #expect(viewModel.currentChannel == nil)

    let prefixBytes = Data([0xAB, 0xCD])
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "hi",
      timestamp: 1_700_000_000,
      createdAt: Date(timeIntervalSince1970: 1_700_000_000),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: prefixBytes,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )

    let resolution = viewModel.senderResolutionFor(message)

    #expect(resolution.displayName == "ABCD")
    #expect(resolution.matchKind == .unresolved)
  }

  // MARK: - Inline Image URL Classification

  @Test
  func `makeBuildInputs classifies an image-extension URL as an inline image`() throws {
    let viewModel = ChatViewModel()
    let message = createTestMessage(timestamp: 1000)
    let imageURL = try #require(URL(string: "https://example.com/photo.png"))
    viewModel.cachedURLs[message.id] = imageURL

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    #expect(inputs.isInlineImageURL == true)
  }

  @Test
  func `makeBuildInputs reroutes a URL discovered to serve a page off the inline-image path`() throws {
    // Once the fetch path records an image-extension URL as serving HTML
    // (imageURLsServingPages), the build input must classify it as a link
    // preview, not an inline image, so it neither refetches nor reserves a frame.
    let viewModel = ChatViewModel()
    let message = createTestMessage(timestamp: 1000)
    let pageURL = try #require(URL(string: "https://example.com/photo.png"))
    viewModel.cachedURLs[message.id] = pageURL
    viewModel.imageURLsServingPages.insert(pageURL.absoluteString)

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    #expect(inputs.isInlineImageURL == false)
  }

  @Test
  func `senderResolutionFor returns Unknown sentinel for DM messages`() {
    let viewModel = ChatViewModel()
    let dmMessage = createTestMessage(timestamp: 1_700_000_000)

    let resolution = viewModel.senderResolutionFor(dmMessage)

    #expect(resolution.displayName == L10n.Chats.Chats.Message.Sender.unknown)
    #expect(resolution.matchKind == .unresolved)
  }
}

// MARK: - Blocked Contact Filtering Tests

@Suite("Blocked Contact Filtering")
@MainActor
struct BlockedContactFilteringTests {
  @Test
  func `Blocked contacts are excluded from allConversations`() {
    let radioID = UUID()
    let viewModel = ChatViewModel()

    // Create contacts - one blocked, one not
    let normalContact = createTestContact(
      radioID: radioID,
      name: "Normal",
      type: .chat,
      isBlocked: false
    )
    let blockedContact = createTestContact(
      radioID: radioID,
      name: "Blocked",
      type: .chat,
      isBlocked: true
    )

    viewModel.conversations = [normalContact, blockedContact]
    viewModel.recomputeSnapshot()

    let conversations = viewModel.allConversations
    #expect(conversations.count == 1)
    if case let .direct(contact) = conversations.first {
      #expect(contact.name == "Normal")
    } else {
      Issue.record("Expected direct conversation")
    }
  }

  @Test
  func `allConversations returns empty when all contacts are blocked`() {
    let radioID = UUID()
    let viewModel = ChatViewModel()

    viewModel.conversations = [
      createTestContact(radioID: radioID, name: "Blocked1", type: .chat, isBlocked: true),
      createTestContact(radioID: radioID, name: "Blocked2", type: .chat, isBlocked: true)
    ]
    viewModel.recomputeSnapshot()

    let conversations = viewModel.allConversations
    #expect(conversations.isEmpty)
  }

  @Test
  func `Blocked repeaters are also excluded`() {
    let radioID = UUID()
    let viewModel = ChatViewModel()

    // Mix of blocked chat, normal chat, and repeater (blocked or not)
    viewModel.conversations = [
      createTestContact(radioID: radioID, name: "Normal", type: .chat, isBlocked: false),
      createTestContact(radioID: radioID, name: "BlockedChat", type: .chat, isBlocked: true),
      createTestContact(radioID: radioID, name: "Repeater", type: .repeater, isBlocked: false),
      createTestContact(radioID: radioID, name: "BlockedRepeater", type: .repeater, isBlocked: true)
    ]
    viewModel.recomputeSnapshot()

    let conversations = viewModel.allConversations
    #expect(conversations.count == 1)
    if case let .direct(contact) = conversations.first {
      #expect(contact.name == "Normal")
    } else {
      Issue.record("Expected direct conversation with Normal contact")
    }
  }
}

// MARK: - Display Flags Tests

@Suite("Display Flags")
@MainActor
struct DisplayFlagsTests {
  @Test
  func `First message always shows sender name`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice")
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
  }

  @Test
  func `Consecutive messages from same sender within 5 min hide sender name`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: "Alice"), // 1 min later
      createChannelMessage(timestamp: 1120, senderName: "Alice") // 2 min later
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == false)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == false)
  }

  @Test
  func `Different sender shows sender name`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: "Bob")
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
  }

  @Test
  func `Gap over 5 minutes shows sender name`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1301, senderName: "Alice") // 5 min 1 sec later
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
  }

  @Test
  func `Exactly 5 minute gap still groups`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1300, senderName: "Alice") // Exactly 5 min
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == false)
  }

  @Test
  func `Outgoing message between incoming breaks group`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: nil, isOutgoing: true),
      createChannelMessage(timestamp: 1120, senderName: "Alice")
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true) // outgoing
    #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == true) // after outgoing
  }

  @Test
  func `Interleaved senders all show names`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: "Bob"),
      createChannelMessage(timestamp: 1120, senderName: "Alice")
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == true)
  }

  @Test
  func `Nil sender name shows name to be safe`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: nil) // malformed message
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
  }

  @Test
  func `Empty string sender name treated as different sender`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: "")
    ]

    #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
  }

  @Test
  func `Direct messages always return true`() {
    // Direct messages have contactID set
    let message = Message(
      id: UUID(),
      radioID: UUID(),
      contactID: UUID(), // non-nil = direct message
      text: "Test",
      timestamp: 1000,
      directionRawValue: MessageDirection.incoming.rawValue,
      statusRawValue: MessageStatus.delivered.rawValue
    )
    let dto = MessageDTO(from: message)

    #expect(ChatViewModel.computeDisplayFlags(for: dto, previous: nil).showSenderName == true)
  }

  @Test
  func `Direction change shows direction gap`() {
    let messages = [
      createChannelMessage(timestamp: 1000, senderName: "Alice"),
      createChannelMessage(timestamp: 1060, senderName: "Alice", isOutgoing: true),
      createChannelMessage(timestamp: 1120, senderName: "Alice")
    ]
    let flags0 = ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil)
    let flags1 = ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0])
    let flags2 = ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1])
    #expect(flags0.showDirectionGap == false)
    #expect(flags1.showDirectionGap == true)
    #expect(flags2.showDirectionGap == true)
  }

  // MARK: - Day Divider

  @Test
  func `First message always shows day divider`() {
    let message = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 10))
    #expect(ChatViewModel.computeDisplayFlags(for: message, previous: nil).showDayDivider == true)
  }

  @Test
  func `Same calendar day hides day divider`() {
    let m0 = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 10, 0))
    let m1 = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 10, 1))
    #expect(ChatViewModel.computeDisplayFlags(for: m1, previous: m0).showDayDivider == false)
  }

  @Test
  func `Calendar day change shows day divider`() {
    let m0 = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 10))
    let m1 = createTestMessage(timestamp: makeTimestamp(2024, 5, 2, 10))
    #expect(ChatViewModel.computeDisplayFlags(for: m1, previous: m0).showDayDivider == true)
  }

  @Test
  func `Day change detection ignores a shared local receive day`() {
    // Both rows were stored locally on the same day (a one-session backlog sync),
    // but were sent on different days; the divider must key on the send day.
    let receiveDay = makeDate(2024, 6, 2, 14)
    let m0 = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 10), createdAt: receiveDay)
    let m1 = createTestMessage(timestamp: makeTimestamp(2024, 5, 2, 10), createdAt: receiveDay)
    #expect(ChatViewModel.computeDisplayFlags(for: m1, previous: m0).showDayDivider == true)
  }

  @Test
  func `Day change divides even under the grouping gap`() {
    // 180s send-time gap is under the 300s grouping threshold, but the two
    // messages straddle midnight, so the day divider must still show.
    let m0 = createTestMessage(timestamp: makeTimestamp(2024, 5, 1, 23, 58))
    let m1 = createTestMessage(timestamp: makeTimestamp(2024, 5, 2, 0, 1))
    #expect(ChatViewModel.computeDisplayFlags(for: m1, previous: m0).showDayDivider == true)
  }
}

// MARK: - Inline Image Master + Scope Gating

@Suite("ChatViewModel inline image gating")
@MainActor
struct ChatViewModelImageGatingTests {
  /// Master toggle applied to `EnvInputs`. Only `previewsEnabled` matters here;
  /// the rest mirror `EnvInputs.default`.
  private func makeEnv(previewsEnabled: Bool) -> EnvInputs {
    EnvInputs(
      autoPlayGIFs: true,
      showIncomingPath: false,
      showIncomingHopCount: false,
      showIncomingRegion: false,
      showIncomingSendTime: false,
      previewsEnabled: previewsEnabled,
      isHighContrast: false,
      isDark: false,
      showMapPreviews: false,
      isOffline: false,
      currentUserName: "Me",
      themeID: EnvInputs.defaultThemeID,
      contentSizeCategory: EnvInputs.defaultContentSizeCategory
    )
  }

  /// Scratch `UserDefaults` suite so scope state never leaks into `.standard`.
  private func scratchPreferences(enabled: Bool, autoResolveDM: Bool) -> LinkPreviewPreferences {
    let suite = UserDefaults(suiteName: "ChatVMImageGating-\(UUID().uuidString)")!
    suite.set(enabled, forKey: AppStorageKey.linkPreviewsEnabled.rawValue)
    suite.set(autoResolveDM, forKey: AppStorageKey.linkPreviewsAutoResolveDM.rawValue)
    return LinkPreviewPreferences(defaults: suite)
  }

  private func makeViewModel(
    message: MessageDTO,
    imageURL: URL,
    previewsEnabled: Bool,
    scopeOn: Bool
  ) -> ChatViewModel {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    viewModel.appendMessageIfNew(message)
    viewModel.cachedURLs[message.id] = imageURL
    viewModel.envInputs = makeEnv(previewsEnabled: previewsEnabled)
    viewModel.linkPreviewPreferences = scratchPreferences(enabled: previewsEnabled, autoResolveDM: scopeOn)
    return viewModel
  }

  @Test
  func `requestImageFetch parks at disabled when scope is off`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: false)

    viewModel.requestImageFetch(for: message.id)

    #expect(viewModel.previewStates[message.id] == .disabled)
    #expect(viewModel.imageFetchTasks[message.id] == nil)
  }

  @Test
  func `requestImageFetch starts a fetch when scope is on`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: true)

    viewModel.requestImageFetch(for: message.id)

    #expect(viewModel.imageFetchTasks[message.id] != nil)
    #expect(viewModel.previewStates[message.id] != .disabled)
    viewModel.cancelImageFetch(for: message.id)
  }

  @Test
  func `requestImageFetch is a no-op when link content is off`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: false, scopeOn: true)

    viewModel.requestImageFetch(for: message.id)

    #expect(viewModel.previewStates[message.id] == nil)
    #expect(viewModel.imageFetchTasks[message.id] == nil)
  }

  @Test
  func `shouldRequestImageFetch is false when link content is off`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: false, scopeOn: true)

    #expect(viewModel.shouldRequestImageFetch(for: message.id) == false)
  }

  @Test
  func `shouldRequestImageFetch is true for an image url when link content is on regardless of scope`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: false)

    #expect(viewModel.shouldRequestImageFetch(for: message.id) == true)
  }

  @Test
  func `manualFetchImage bypasses the scope gate from a disabled state`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: false)

    // Scope-off parks the state at the tap-to-load placeholder.
    viewModel.requestImageFetch(for: message.id)
    #expect(viewModel.previewStates[message.id] == .disabled)

    // The tap fires the fetch despite scope being off.
    viewModel.manualFetchImage(for: message.id)
    #expect(viewModel.imageFetchTasks[message.id] != nil)
    viewModel.cancelImageFetch(for: message.id)
  }

  @Test
  func `manualFetchImage is a no-op when the state is not disabled`() throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: false)

    // Fresh state (nil), not `.disabled`: manualFetchImage must not fire.
    viewModel.manualFetchImage(for: message.id)
    #expect(viewModel.imageFetchTasks[message.id] == nil)
  }

  @Test
  func `retryImageFetch is a no-op when link content is off`() async throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: false, scopeOn: true)

    await viewModel.retryImageFetch(for: message.id)

    #expect(viewModel.imageFetchTasks[message.id] == nil)
  }

  @Test
  func `retryImageFetch bypasses the scope gate and fetches directly`() async throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: false)

    // A visible retry is an explicit user action: scope off must not bounce it
    // to the tap-to-load placeholder, it must start a fetch directly.
    await viewModel.retryImageFetch(for: message.id)

    #expect(viewModel.imageFetchTasks[message.id] != nil)
    #expect(viewModel.previewStates[message.id] != .disabled)
    viewModel.cancelImageFetch(for: message.id)
  }

  @Test
  func `retryImageFetch is a no-op for a malware-flagged message`() async throws {
    let message = createTestMessage(timestamp: 1000, text: "see https://example.com/cat.png")
    let url = try #require(URL(string: "https://example.com/cat.png"))
    let viewModel = makeViewModel(message: message, imageURL: url, previewsEnabled: true, scopeOn: true)
    viewModel.previewStates[message.id] = .malwareWarning

    await viewModel.retryImageFetch(for: message.id)

    #expect(viewModel.imageFetchTasks[message.id] == nil)
    #expect(viewModel.previewStates[message.id] == .malwareWarning)
  }
}
