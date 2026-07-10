import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

// MARK: - Test Helpers

private func createTestContact(
  id: UUID = UUID(),
  radioID: UUID,
  name: String = "TestContact"
) -> ContactDTO {
  ContactDTO(
    id: id,
    radioID: radioID,
    publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
    name: name,
    typeRawValue: ContactType.chat.rawValue,
    flags: 0,
    outPathLength: 2,
    outPath: Data([0x01, 0x02]),
    lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
    latitude: 0,
    longitude: 0,
    lastModified: UInt32(Date().timeIntervalSince1970),
    nickname: nil,
    isBlocked: false,
    isMuted: false,
    isFavorite: false,
    lastMessageDate: Date(),
    unreadCount: 0
  )
}

private func createTestChannel(
  id: UUID = UUID(),
  radioID: UUID,
  index: UInt8 = 0,
  name: String = "TestChannel"
) -> ChannelDTO {
  ChannelDTO(
    id: id,
    radioID: radioID,
    index: index,
    name: name,
    secret: Data(),
    isEnabled: true,
    lastMessageDate: Date(),
    unreadCount: 0,
    unreadMentionCount: 0,
    notificationLevel: .all,
    isFavorite: false
  )
}

private func createTestMessage(
  contactID: UUID,
  radioID: UUID,
  timestamp: UInt32,
  createdAt: Date = Date(),
  direction: MessageDirection = .incoming,
  text: String = "Test message"
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: contactID,
    channelIndex: nil,
    text: text,
    timestamp: timestamp,
    createdAt: createdAt,
    direction: direction,
    status: .delivered,
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

private func createChannelMessage(
  radioID: UUID,
  channelIndex: UInt8,
  timestamp: UInt32,
  senderName: String = "Sender",
  text: String = "Test message"
) -> MessageDTO {
  MessageDTO(
    id: UUID(),
    radioID: radioID,
    contactID: nil,
    channelIndex: channelIndex,
    text: text,
    timestamp: timestamp,
    createdAt: Date(),
    direction: .incoming,
    status: .delivered,
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

/// A copy of `EnvInputs.default` with one formatting-relevant field flipped,
/// so `applyEnvInputs` sees a change and invalidates the formatted-text cache.
private func envInputsChangingAppearance() -> EnvInputs {
  let base = EnvInputs.default
  return EnvInputs(
    showInlineImages: base.showInlineImages,
    autoPlayGIFs: base.autoPlayGIFs,
    showIncomingPath: base.showIncomingPath,
    showIncomingHopCount: base.showIncomingHopCount,
    showIncomingRegion: base.showIncomingRegion,
    showIncomingSendTime: base.showIncomingSendTime,
    previewsEnabled: base.previewsEnabled,
    isHighContrast: base.isHighContrast,
    isDark: !base.isDark,
    showMapPreviews: base.showMapPreviews,
    isOffline: base.isOffline,
    currentUserName: base.currentUserName,
    themeID: base.themeID,
    contentSizeCategory: base.contentSizeCategory
  )
}

// MARK: - Pagination Tests

@Suite("ChatViewModel Pagination Tests")
@MainActor
struct ChatViewModelPaginationTests {
  @Test
  func `loadOlderMessages returns early without dataStore`() async {
    let viewModel = ChatViewModel()
    let radioID = UUID()
    let contactID = UUID()
    let contact = createTestContact(id: contactID, radioID: radioID)

    viewModel.currentContact = contact
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // Without configuring dataStore, loadOlderMessages should return early
    await viewModel.loadOlderMessages()

    // No error should be set
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.messages.isEmpty)
  }

  /// Verifies that `loadOlderMessages` clears `isLoadingOlder` before
  /// entering the reaction-indexing loop, so the pagination spinner is not
  /// held open by a slow `ReactionService.indexMessage` actor hop on a
  /// busy channel. The structural ordering is enforced in
  /// `ChatViewModel.loadOlderMessages`: the spinner is cleared immediately
  /// after `buildItems()` and before the channel/DM reaction-indexing
  /// blocks. This test exercises the success path with the reaction-service
  /// provider at its nil default (so the indexing loops are skipped) and asserts the post-return
  /// state matches the documented contract — `isLoadingOlder == false`,
  /// older messages prepended, no error surfaced. The deeper "spinner
  /// already false while indexing still running" property is verified by
  /// source-level review at PR time; `ReactionService` is a concrete
  /// actor without a protocol surface, so an injectable continuation-
  /// blocking stub is not available.
  @Test
  func `loadOlderMessages clears isLoadingOlder on the success path`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contactID = UUID()

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    viewModel.currentContact = createTestContact(id: contactID, radioID: radioID)
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // Seed the database with a page worth of messages so the
    // pagination fetch returns rows and proceeds past the
    // prepend/buildItems block where the early-clear lives.
    for index in 0..<10 {
      let message = createTestMessage(
        contactID: contactID,
        radioID: radioID,
        timestamp: UInt32(1000 + index)
      )
      try await dataStore.saveMessage(message)
    }

    #expect(viewModel.isLoadingOlder == false)

    await viewModel.loadOlderMessages()

    #expect(viewModel.isLoadingOlder == false, "Pagination must release the spinner before returning")
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.errorBannerMessage == nil)
    #expect(viewModel.messages.count == 10, "Pagination should have prepended fetched messages")
  }
}

// MARK: - Channel Pagination Tests

@Suite("ChatViewModel Channel Pagination Tests")
@MainActor
struct ChatViewModelChannelPaginationTests {
  @Test
  func `Opening a channel with more unread than one page loads the divider target`() async throws {
    // When unread exceeds pageSize, the first-unread message (where the divider
    // belongs) is older than a standard page. The initial load must be sized to
    // cover all unread so the divider has a materialized message to scroll to,
    // instead of clamping the divider onto the oldest loaded row.
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let total = 80
    let unread = 60
    #expect(unread > ChatCoordinator.pageSize, "Fixture must exceed one page to exercise the fix")

    let channel = ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: channelIndex,
      name: "Mesh HQ",
      secret: Data(),
      isEnabled: true,
      lastMessageDate: Date(),
      unreadCount: unread,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false
    )
    try await dataStore.saveChannel(channel)

    // Ascending timestamps → display order is oldest-first; the first unread row
    // sits at index total - unread.
    var idsOldestFirst: [UUID] = []
    for index in 0..<total {
      let message = createChannelMessage(
        radioID: radioID,
        channelIndex: channelIndex,
        timestamp: UInt32(1000 + index),
        senderName: "User\(index % 3)"
      )
      idsOldestFirst.append(message.id)
      try await dataStore.saveMessage(message)
    }
    let expectedDividerID = idsOldestFirst[total - unread]

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    viewModel.coordinator = ChatCoordinator.makeForTesting()

    await viewModel.loadChannelMessages(for: channel)

    #expect(viewModel.messages.count == ChatCoordinator.initialPageSize(unreadCount: unread),
            "Initial load must fetch all unread plus read context, not just one page")
    #expect(viewModel.newMessagesDividerMessageID == expectedDividerID,
            "Divider must land on the true first-unread message")
    #expect(viewModel.messages.contains { $0.id == expectedDividerID },
            "Divider target must be within the loaded messages")
    #expect(viewModel.messages.contains { $0.id == idsOldestFirst[total - 1] },
            "Newest message must still be loaded")
  }

  @Test
  func `Switching from a channel to a DM clears the channel axis so an incoming channel message is not admitted into the open DM`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channelIndex: UInt8 = 1
    let contactID = UUID()

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    viewModel.coordinator = ChatCoordinator.makeForTesting()

    let channel = createTestChannel(radioID: radioID, index: channelIndex, name: "General")
    let contact = createTestContact(id: contactID, radioID: radioID)

    // Open the channel, then switch to the DM. loadMessages(for:) must clear currentChannel.
    await viewModel.loadChannelMessages(for: channel)
    #expect(viewModel.currentChannel?.index == channelIndex)

    await viewModel.loadMessages(for: contact)
    #expect(viewModel.currentChannel == nil, "Loading a DM must clear the channel axis")
    #expect(viewModel.currentContact?.id == contactID)

    let baselineCount = viewModel.messages.count

    // A channel message for the channel that was just open must not enter the open DM.
    let channelMessage = createChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      timestamp: 2000
    )
    await viewModel.handle(.channelMessageReceived(message: channelMessage, channelIndex: channelIndex))

    #expect(viewModel.messages.count == baselineCount,
            "An incoming channel message must not be admitted into the open DM after the axis switch")
  }
}

// MARK: - Display Items Tests

@Suite("ChatViewModel Display Items Pagination Tests")
@MainActor
struct ChatViewModelDisplayItemsPaginationTests {
  @Test
  func `Display items are rebuilt after loading older messages`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // Start with some messages
    let radioID = UUID()
    let contactID = UUID()

    let messages = (0..<5).map { index in
      createTestMessage(
        contactID: contactID,
        radioID: radioID,
        timestamp: UInt32(1000 + index)
      )
    }

    coordinator.replaceAll(messages)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.items.count == 5)

    // Add more messages (simulating loadOlderMessages prepend)
    let olderMessages = (0..<3).map { index in
      createTestMessage(
        contactID: contactID,
        radioID: radioID,
        timestamp: UInt32(900 + index)
      )
    }

    coordinator.prepend(olderMessages)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.items.count == 8)
  }

  @Test
  func `Formatted-text cache covers the timeline and persists across pagination`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    let radioID = UUID()
    let contactID = UUID()

    let messages = (0..<5).map { index in
      createTestMessage(contactID: contactID, radioID: radioID, timestamp: UInt32(1000 + index), text: "Message \(index)")
    }
    coordinator.replaceAll(messages)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.formattedTextCache.count == 5, "Every built row should be memoized")
    let cachedText = viewModel.formattedTextCache[messages[0].id]?.text

    let older = (0..<3).map { index in
      createTestMessage(contactID: contactID, radioID: radioID, timestamp: UInt32(900 + index), text: "Older \(index)")
    }
    coordinator.prepend(older)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.formattedTextCache.count == 8, "Cache grows to cover the prepended page")
    #expect(viewModel.formattedTextCache[messages[0].id]?.text == cachedText,
            "An already-formatted row keeps its cached value across the pagination rebuild")
  }

  @Test
  func `Changing env inputs clears the formatted-text cache`() {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    // Empty timeline: applyEnvInputs clears the cache and returns before any
    // rebuild, so the clear is observable in isolation.
    viewModel.formattedTextCache[UUID()] = (text: AttributedString("stale"), mapCoordinate: nil)
    #expect(viewModel.formattedTextCache.isEmpty == false)

    viewModel.applyEnvInputs(envInputsChangingAppearance())

    #expect(viewModel.formattedTextCache.isEmpty, "An appearance change must invalidate every cached row")
  }

  @Test
  func `buildItems prunes cache entries for messages no longer in the timeline`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    let radioID = UUID()
    let contactID = UUID()

    let messages = (0..<5).map { index in
      createTestMessage(contactID: contactID, radioID: radioID, timestamp: UInt32(1000 + index))
    }
    coordinator.replaceAll(messages)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value
    #expect(viewModel.formattedTextCache.count == 5)

    // Switch conversations: fewer messages than the cache holds triggers the prune.
    coordinator.replaceAll([messages[0], messages[1]])
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.formattedTextCache.count == 2, "Stale entries are pruned to the current timeline")
    #expect(viewModel.formattedTextCache[messages[4].id] == nil)
  }

  @Test
  func `Message lookup by ID works after pagination`() async {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    let radioID = UUID()
    let contactID = UUID()

    let message1 = createTestMessage(contactID: contactID, radioID: radioID, timestamp: 1000)
    let message2 = createTestMessage(contactID: contactID, radioID: radioID, timestamp: 1001)

    coordinator.replaceAll([message1, message2])
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    // Lookup should work
    #expect(viewModel.items.count == 2)
    let foundMessage = viewModel.message(for: viewModel.items[0])
    #expect(foundMessage?.id == message1.id)
  }
}

// MARK: - Cross-Boundary Reordering Tests

@Suite("Same-Sender Cluster Reordering Across Page Boundaries")
@MainActor
struct CrossBoundaryReorderingTests {
  @Test
  func `Reordering fixes same-sender cluster split across pagination boundary`() {
    // Scenario: Sender sends msg1 (t=100), msg2 (t=101), msg3 (t=102) rapidly.
    // Mesh delivers them out of order: msg3, msg1, msg2.
    // msg3 ends up on page 2 (older), msg1 and msg2 on page 1 (newer).
    //
    // Each page is reordered independently, but the cross-boundary cluster
    // (msg3 on page 2, msg1+msg2 on page 1) is NOT reordered until merge.

    let radioID = UUID()
    let contactID = UUID()
    let base = Date(timeIntervalSince1970: 1_000_000)

    // Page 2 (older, loaded second via loadOlderMessages): msg3 arrived first
    let msg3 = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 102,
      createdAt: base.addingTimeInterval(0), // received first
      text: "msg3"
    )

    // Page 1 (newer, loaded first): msg1 and msg2 arrived later
    let msg1 = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 100,
      createdAt: base.addingTimeInterval(2), // received second
      text: "msg1"
    )
    let msg2 = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 101,
      createdAt: base.addingTimeInterval(3), // received third
      text: "msg2"
    )

    // Simulate independent per-page reordering (as production does)
    let page2Reordered = MessageDTO.reorderSameSenderClusters([msg3]) // single msg, no-op
    let page1Reordered = MessageDTO.reorderSameSenderClusters([msg1, msg2]) // already ordered

    // Merge: prepend older page
    var merged = page2Reordered
    merged.append(contentsOf: page1Reordered)

    // Without cross-boundary reordering: msg3, msg1, msg2 (receive order at boundary)
    #expect(merged.map(\.text) == ["msg3", "msg1", "msg2"])

    // After re-running reorderSameSenderClusters on the full merged array
    let fixed = MessageDTO.reorderSameSenderClusters(merged)

    // All three are from the same sender (DM, same direction), within 5s window,
    // so they're reordered by sender timestamp: msg1, msg2, msg3
    #expect(fixed.map(\.text) == ["msg1", "msg2", "msg3"])
  }

  @Test
  func `Reordering does not merge clusters beyond the 5-second window`() {
    let radioID = UUID()
    let contactID = UUID()
    let base = Date(timeIntervalSince1970: 1_000_000)

    // Page 2 message: received well before the page 1 messages (>5s gap)
    let oldMsg = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 100,
      createdAt: base.addingTimeInterval(0),
      text: "old"
    )

    // Page 1 messages: received 10 seconds later
    let newMsg1 = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 99, // earlier sender timestamp but later receive
      createdAt: base.addingTimeInterval(10),
      text: "new1"
    )
    let newMsg2 = createTestMessage(
      contactID: contactID,
      radioID: radioID,
      timestamp: 102,
      createdAt: base.addingTimeInterval(11),
      text: "new2"
    )

    // Merge: prepend older page
    var merged = [oldMsg]
    merged.append(contentsOf: [newMsg1, newMsg2])

    let result = MessageDTO.reorderSameSenderClusters(merged)

    // The 10-second gap between oldMsg and newMsg1 exceeds the 5s window,
    // so they should NOT be clustered — order stays as-is
    #expect(result.map(\.text) == ["old", "new1", "new2"])
  }
}

// MARK: - Channel Sender Registration (Pagination Regression)

@Suite("ChatViewModel Channel Sender Registration")
@MainActor
struct ChatViewModelChannelSenderRegistrationTests {
  @Test
  func `addChannelSenderIfNew inserts synthetic sender and records timestamp`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()

    viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 1000)

    #expect(viewModel.channelSenderNames.contains("Alice"))
    #expect(viewModel.channelSenders.count == 1)
    #expect(viewModel.channelSenderOrder["Alice"] == 1000)
  }

  @Test
  func `addChannelSenderIfNew max-merges timestamps for the same sender`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()

    viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 1000)
    viewModel.addChannelSenderIfNew("Alice", radioID: radioID, timestamp: 500)

    // Older message arriving after a newer one must not lower the recency stamp.
    #expect(viewModel.channelSenderOrder["Alice"] == 1000)
    #expect(viewModel.channelSenders.count == 1)
  }

  @Test
  func `addChannelSenderIfNew skips synthetic for known contacts but still records order`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()
    viewModel.contactNameSet = ["Bob"]

    viewModel.addChannelSenderIfNew("Bob", radioID: radioID, timestamp: 1500)

    #expect(viewModel.channelSenders.isEmpty, "No synthetic for a known contact")
    #expect(viewModel.channelSenderNames.contains("Bob") == false)
    #expect(viewModel.channelSenderOrder["Bob"] == 1500, "Order tracks all senders for mention recency")
  }

  @Test
  func `addChannelSenderIfNew rejects empty and oversized names`() {
    let viewModel = ChatViewModel()
    let radioID = UUID()
    let tooLong = String(repeating: "x", count: 129)

    viewModel.addChannelSenderIfNew("   ", radioID: radioID, timestamp: 1)
    viewModel.addChannelSenderIfNew("", radioID: radioID, timestamp: 1)
    viewModel.addChannelSenderIfNew(tooLong, radioID: radioID, timestamp: 1)

    #expect(viewModel.channelSenders.isEmpty)
    #expect(viewModel.channelSenderOrder.isEmpty)
  }

  @Test
  func `Pagination registration: older-page senders join channelSenderNames and channelSenderOrder`() {
    // Simulates the loadOlderMessages call site: live receive registers
    // recent senders first, then a paginated older page registers earlier
    // senders. After both steps, the mention picker must see all senders
    // and the order map must reflect each sender's own timestamp.
    let viewModel = ChatViewModel()
    let radioID = UUID()

    // Live-receive: senders A and B on the current page
    viewModel.addChannelSenderIfNew("A", radioID: radioID, timestamp: 2000)
    viewModel.addChannelSenderIfNew("B", radioID: radioID, timestamp: 2100)

    // Pagination: older page reveals senders C and D, plus another
    // A message from earlier
    viewModel.addChannelSenderIfNew("C", radioID: radioID, timestamp: 500)
    viewModel.addChannelSenderIfNew("D", radioID: radioID, timestamp: 600)
    viewModel.addChannelSenderIfNew("A", radioID: radioID, timestamp: 100)

    #expect(viewModel.channelSenderNames == ["A", "B", "C", "D"])
    #expect(viewModel.channelSenders.count == 4)
    #expect(viewModel.channelSenderOrder["A"] == 2000, "A keeps its live recency, not the older one")
    #expect(viewModel.channelSenderOrder["B"] == 2100)
    #expect(viewModel.channelSenderOrder["C"] == 500)
    #expect(viewModel.channelSenderOrder["D"] == 600)
  }
}
