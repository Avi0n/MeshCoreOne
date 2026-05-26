import Testing
import Foundation
@testable import MC1
@testable import MC1Services

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
        contactID: nil,  // nil = channel message
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
        senderKeyPrefix: nil,  // Always nil for channel messages per MeshCore protocol
        senderNodeName: senderName,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )
}

// MARK: - ChatViewModel Tests

@Suite("ChatViewModel Tests")
@MainActor
struct ChatViewModelTests {

    // MARK: - Timestamp Logic Tests

    @Test("First message always shows timestamp")
    func firstMessageAlwaysShowsTimestamp() {
        let messages = [
            createTestMessage(timestamp: 1000)
        ]

        let flags = ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil)
        #expect(flags.showTimestamp == true)
    }

    @Test("Consecutive messages within 5 minutes don't show timestamp")
    func consecutiveMessagesWithin5MinutesDontShowTimestamp() {
        let baseTime: UInt32 = 1000
        let messages = [
            createTestMessage(timestamp: baseTime),
            createTestMessage(timestamp: baseTime + 60),   // 1 minute later
            createTestMessage(timestamp: baseTime + 120),  // 2 minutes later
            createTestMessage(timestamp: baseTime + 180),  // 3 minutes later
            createTestMessage(timestamp: baseTime + 240)   // 4 minutes later
        ]

        // First message always shows timestamp
        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)

        // Messages 1-4 shouldn't show timestamp (within 5 min of previous)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showTimestamp == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[3], previous: messages[2]).showTimestamp == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[4], previous: messages[3]).showTimestamp == false)
    }

    @Test("Message after 5+ minute gap shows timestamp")
    func messageAfter5MinuteGapShowsTimestamp() {
        let baseTime: UInt32 = 1000
        let messages = [
            createTestMessage(timestamp: baseTime),
            createTestMessage(timestamp: baseTime + 301)  // 5 min 1 sec later
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == true)
    }

    @Test("Exactly 5 minute gap does not show timestamp")
    func exactly5MinuteGapDoesNotShowTimestamp() {
        let baseTime: UInt32 = 1000
        let messages = [
            createTestMessage(timestamp: baseTime),
            createTestMessage(timestamp: baseTime + 300)  // Exactly 5 minutes
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false)  // 300 is not > 300
    }

    @Test("Backlog block keys divider grouping on send time, not the shared drain anchor")
    func backlogBlockGroupsBySendTime() {
        // Two backlog rows drained together share the anchor as their sortDate, but were
        // sent ten minutes apart. Grouping must follow send time so the divider still
        // appears inside the block; keying on the shared sortDate would collapse it.
        let anchor = Date(timeIntervalSince1970: 5_000_000)
        let earlier = createTestMessage(timestamp: 1000, sortDate: anchor)
        let later = createTestMessage(timestamp: 1600, sortDate: anchor)  // +10 min send time
        #expect(ChatViewModel.computeDisplayFlags(for: later, previous: earlier).showTimestamp == true)
    }

    @Test("Unread divider lands on the first unread row of the recent block")
    @MainActor
    func dividerLandsOnFirstUnreadBlockRow() {
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

        vm.computeDividerPosition(from: messages, unreadCount: unreadCount)

        #expect(vm.newMessagesDividerMessageID == firstUnread.id)
    }

    @Test("Mixed gaps show correct timestamps")
    func mixedGapsShowCorrectTimestamps() {
        let baseTime: UInt32 = 1000
        let messages = [
            createTestMessage(timestamp: baseTime),           // 0: Always show
            createTestMessage(timestamp: baseTime + 60),      // 1: 1 min - no show
            createTestMessage(timestamp: baseTime + 420),     // 2: 6 min gap from prev - show
            createTestMessage(timestamp: baseTime + 480),     // 3: 1 min - no show
            createTestMessage(timestamp: baseTime + 900),     // 4: 7 min gap - show
            createTestMessage(timestamp: baseTime + 920)      // 5: 20 sec - no show
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showTimestamp == true)   // 360s gap
        #expect(ChatViewModel.computeDisplayFlags(for: messages[3], previous: messages[2]).showTimestamp == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[4], previous: messages[3]).showTimestamp == true)   // 420s gap
        #expect(ChatViewModel.computeDisplayFlags(for: messages[5], previous: messages[4]).showTimestamp == false)
    }

    @Test("buildItems with empty messages produces empty output")
    func buildItemsEmptyMessages() async {
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

    @Test("computeDisplayFlags with same timestamp messages")
    func computeDisplayFlagsSameTimestamp() {
        let baseTime: UInt32 = 1000
        let first = createTestMessage(timestamp: baseTime, text: "Hello")
        let second = createTestMessage(timestamp: baseTime, text: "World")

        let flags = ChatViewModel.computeDisplayFlags(for: second, previous: first)
        #expect(flags.showTimestamp == false)
        #expect(flags.showDirectionGap == false)
    }

    @Test("Single message array shows timestamp")
    func singleMessageArrayShowsTimestamp() {
        let messages = [
            createTestMessage(timestamp: 1000)
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
    }

    @Test("Divider grouping hides the header when send times are close despite a large sortDate gap")
    func groupingHidesHeaderWhenSendTimesCloseDespiteSortDateGap() {
        // Sent one second apart but assigned far-apart sortDates (e.g. drained in separate
        // sessions, so distinct anchors). Grouping follows send time, so no header appears.
        let msg1 = createTestMessage(timestamp: 1000, sortDate: Date(timeIntervalSince1970: 1_000_000))
        let msg2 = createTestMessage(timestamp: 1001, sortDate: Date(timeIntervalSince1970: 1_000_600))
        #expect(ChatViewModel.computeDisplayFlags(for: msg2, previous: msg1).showTimestamp == false)
    }

    @Test("Divider grouping ignores drain time: far createdAt with close send times hides the header")
    func groupingIgnoresCreatedAtWhenSendTimesClose() {
        // Received ten minutes apart (createdAt) but sent one second apart. Grouping must
        // follow send time, not drain time, so the rows stay grouped with no header.
        let msg1 = createTestMessage(timestamp: 1000, createdAt: Date(timeIntervalSince1970: 2_000_000))
        let msg2 = createTestMessage(timestamp: 1001, createdAt: Date(timeIntervalSince1970: 2_000_600))
        #expect(ChatViewModel.computeDisplayFlags(for: msg2, previous: msg1).showTimestamp == false)
    }

    @Test("Large time gaps show timestamp")
    func largeTimeGapsShowTimestamp() {
        let baseTime: UInt32 = 1000
        let messages = [
            createTestMessage(timestamp: baseTime),
            createTestMessage(timestamp: baseTime + 86400)  // 24 hours later
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showTimestamp == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showTimestamp == true)
    }

    // MARK: - Conversation Filtering Tests

    @Test("allConversations excludes repeaters")
    func allConversationsExcludesRepeaters() {
        let viewModel = ChatViewModel()
        let radioID = UUID()

        // Create a mix of contact types
        let chatContact = createTestContact(radioID: radioID, name: "Alice", type: .chat)
        let chatContact2 = createTestContact(radioID: radioID, name: "Bob", type: .chat)
        let repeaterContact = createTestContact(radioID: radioID, name: "Repeater 1", type: .repeater)
        let anotherRepeater = createTestContact(radioID: radioID, name: "Repeater 2", type: .repeater)

        // Set conversations to include repeaters
        viewModel.conversations = [chatContact, chatContact2, repeaterContact, anotherRepeater]

        // Verify allConversations excludes repeaters
        let conversations = viewModel.allConversations
        #expect(conversations.count == 2)

        // Verify only chat contacts are included
        let names = conversations.compactMap { conversation -> String? in
            if case .direct(let contact) = conversation {
                return contact.displayName
            }
            return nil
        }
        #expect(names.contains("Alice"))
        #expect(names.contains("Bob"))
        #expect(!names.contains("Repeater 1"))
        #expect(!names.contains("Repeater 2"))
    }

    @Test("allConversations returns empty when only repeaters exist")
    func allConversationsReturnsEmptyWhenOnlyRepeatersExist() {
        let viewModel = ChatViewModel()
        let radioID = UUID()

        // Only repeaters in conversations
        viewModel.conversations = [
            createTestContact(radioID: radioID, name: "Repeater 1", type: .repeater),
            createTestContact(radioID: radioID, name: "Repeater 2", type: .repeater)
        ]

        let conversations = viewModel.allConversations
        #expect(conversations.isEmpty)
    }

    // MARK: - Loading State Tests

    @Test("hasLoadedOnce starts false")
    func hasLoadedOnceStartsFalse() {
        let viewModel = ChatViewModel()
        #expect(viewModel.hasLoadedOnce == false)
    }

    @Test("isLoading starts false")
    func isLoadingStartsFalse() {
        let viewModel = ChatViewModel()
        #expect(viewModel.isLoading == false)
    }

    @Test("renderState.phase starts .uninitialized when no coordinator is bound")
    func renderStatePhaseUninitializedBeforeBind() {
        let viewModel = ChatViewModel()
        #expect(viewModel.renderState.phase == .uninitialized)
    }

    @Test("renderState.phase is .loaded after replaceAll on bound coordinator")
    func renderStatePhaseLoadedAfterReplaceAll() {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        coordinator.replaceAll([])

        #expect(viewModel.renderState.phase == .loaded)
        #expect(viewModel.messages.isEmpty)
    }

    @Test("loadMessages settles phase to .loaded when dataStore is nil")
    func loadMessagesMarksLoadedWhenDataStoreNil() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        await viewModel.loadMessages(for: createTestContact())

        #expect(viewModel.renderState.phase == .loaded)
    }

    @Test("loadChannelMessages settles phase to .loaded when dataStore is nil")
    func loadChannelMessagesMarksLoadedWhenDataStoreNil() async {
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

    @Test("senderResolutionFor uses message.channelIndex, not currentChannel")
    func senderResolutionDispatchesOnMessageChannelIndex() {
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

    @Test("senderResolutionFor returns wire name for channel msg without senderNodeName via hex fallback")
    func senderResolutionFallsBackToHexForChannelWithoutName() {
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

    @Test("senderResolutionFor returns Unknown sentinel for DM messages")
    func senderResolutionUnknownForDirectMessage() {
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

    @Test("Blocked contacts are excluded from allConversations")
    func blockedContactsExcludedFromConversations() {
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

        let conversations = viewModel.allConversations
        #expect(conversations.count == 1)
        if case .direct(let contact) = conversations.first {
            #expect(contact.name == "Normal")
        } else {
            Issue.record("Expected direct conversation")
        }
    }

    @Test("allConversations returns empty when all contacts are blocked")
    func allConversationsEmptyWhenAllBlocked() {
        let radioID = UUID()
        let viewModel = ChatViewModel()

        viewModel.conversations = [
            createTestContact(radioID: radioID, name: "Blocked1", type: .chat, isBlocked: true),
            createTestContact(radioID: radioID, name: "Blocked2", type: .chat, isBlocked: true)
        ]

        let conversations = viewModel.allConversations
        #expect(conversations.isEmpty)
    }

    @Test("Blocked repeaters are also excluded")
    func blockedRepeatersAlsoExcluded() {
        let radioID = UUID()
        let viewModel = ChatViewModel()

        // Mix of blocked chat, normal chat, and repeater (blocked or not)
        viewModel.conversations = [
            createTestContact(radioID: radioID, name: "Normal", type: .chat, isBlocked: false),
            createTestContact(radioID: radioID, name: "BlockedChat", type: .chat, isBlocked: true),
            createTestContact(radioID: radioID, name: "Repeater", type: .repeater, isBlocked: false),
            createTestContact(radioID: radioID, name: "BlockedRepeater", type: .repeater, isBlocked: true)
        ]

        let conversations = viewModel.allConversations
        #expect(conversations.count == 1)
        if case .direct(let contact) = conversations.first {
            #expect(contact.name == "Normal")
        } else {
            Issue.record("Expected direct conversation with Normal contact")
        }
    }

    @Test("Channel messages from blocked contacts are filtered")
    func channelMessagesFromBlockedContactsFiltered() async {
        let blockedNames: Set<String> = ["BlockedUser", "AnotherBlocked"]

        let messages = [
            MessageDTO(
                id: UUID(),
                radioID: UUID(),
                contactID: nil,
                channelIndex: 0,
                text: "Hello",
                timestamp: 1000,
                createdAt: Date(),
                direction: .incoming,
                status: .delivered,
                textType: .plain,
                ackCode: nil,
                pathLength: 0,
                snr: nil,
                senderKeyPrefix: nil,
                senderNodeName: "NormalUser",
                isRead: false,
                replyToID: nil,
                roundTripTime: nil,
                heardRepeats: 0,
                retryAttempt: 0,
                maxRetryAttempts: 0
            ),
            MessageDTO(
                id: UUID(),
                radioID: UUID(),
                contactID: nil,
                channelIndex: 0,
                text: "Blocked message",
                timestamp: 1001,
                createdAt: Date(),
                direction: .incoming,
                status: .delivered,
                textType: .plain,
                ackCode: nil,
                pathLength: 0,
                snr: nil,
                senderKeyPrefix: nil,
                senderNodeName: "BlockedUser",
                isRead: false,
                replyToID: nil,
                roundTripTime: nil,
                heardRepeats: 0,
                retryAttempt: 0,
                maxRetryAttempts: 0
            ),
            MessageDTO(
                id: UUID(),
                radioID: UUID(),
                contactID: nil,
                channelIndex: 0,
                text: "My message",
                timestamp: 1002,
                createdAt: Date(),
                direction: .outgoing,
                status: .sent,
                textType: .plain,
                ackCode: nil,
                pathLength: 0,
                snr: nil,
                senderKeyPrefix: nil,
                senderNodeName: nil,
                isRead: true,
                replyToID: nil,
                roundTripTime: nil,
                heardRepeats: 0,
                retryAttempt: 0,
                maxRetryAttempts: 0
            )
        ]

        let filtered = messages.filter { message in
            guard let senderName = message.senderNodeName else { return true }
            return !blockedNames.contains(senderName)
        }

        #expect(filtered.count == 2)
        #expect(filtered[0].senderNodeName == "NormalUser")
        #expect(filtered[1].senderNodeName == nil)
    }
}

// MARK: - Display Flags Tests

@Suite("Display Flags")
@MainActor
struct DisplayFlagsTests {

    @Test("First message always shows sender name")
    func firstMessageAlwaysShowsSenderName() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice")
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
    }

    @Test("Consecutive messages from same sender within 5 min hide sender name")
    func consecutiveMessagesFromSameSenderHideName() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: "Alice"),  // 1 min later
            createChannelMessage(timestamp: 1120, senderName: "Alice")   // 2 min later
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == false)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == false)
    }

    @Test("Different sender shows sender name")
    func differentSenderShowsName() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: "Bob")
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
    }

    @Test("Gap over 5 minutes shows sender name")
    func gapOver5MinutesShowsName() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1301, senderName: "Alice")  // 5 min 1 sec later
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
    }

    @Test("Exactly 5 minute gap still groups")
    func exactly5MinuteGapStillGroups() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1300, senderName: "Alice")  // Exactly 5 min
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == false)
    }

    @Test("Outgoing message between incoming breaks group")
    func outgoingMessageBreaksGroup() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: nil, isOutgoing: true),
            createChannelMessage(timestamp: 1120, senderName: "Alice")
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)  // outgoing
        #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == true)  // after outgoing
    }

    @Test("Interleaved senders all show names")
    func interleavedSendersAllShowNames() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: "Bob"),
            createChannelMessage(timestamp: 1120, senderName: "Alice")
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[2], previous: messages[1]).showSenderName == true)
    }

    @Test("Nil sender name shows name to be safe")
    func nilSenderNameShowsName() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: nil)  // malformed message
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
    }

    @Test("Empty string sender name treated as different sender")
    func emptyStringSenderNameTreatedAsDifferent() {
        let messages = [
            createChannelMessage(timestamp: 1000, senderName: "Alice"),
            createChannelMessage(timestamp: 1060, senderName: "")
        ]

        #expect(ChatViewModel.computeDisplayFlags(for: messages[0], previous: nil).showSenderName == true)
        #expect(ChatViewModel.computeDisplayFlags(for: messages[1], previous: messages[0]).showSenderName == true)
    }

    @Test("Direct messages always return true")
    func directMessagesAlwaysReturnTrue() {
        // Direct messages have contactID set
        let message = Message(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),  // non-nil = direct message
            text: "Test",
            timestamp: 1000,
            directionRawValue: MessageDirection.incoming.rawValue,
            statusRawValue: MessageStatus.delivered.rawValue
        )
        let dto = MessageDTO(from: message)

        #expect(ChatViewModel.computeDisplayFlags(for: dto, previous: nil).showSenderName == true)
    }

    @Test("Direction change shows direction gap")
    func directionChangeShowsDirectionGap() {
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
}
