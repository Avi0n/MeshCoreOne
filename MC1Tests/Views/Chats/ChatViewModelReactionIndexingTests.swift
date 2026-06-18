import Testing
import Foundation
@testable import MC1
@testable import MC1Services

// MARK: - Fixtures

private func makeContact(radioID: UUID, name: String = "Alice") -> ContactDTO {
    ContactDTO(
        id: UUID(),
        radioID: radioID,
        publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
        name: name,
        typeRawValue: ContactType.chat.rawValue,
        flags: 0,
        outPathLength: 2,
        outPath: Data([0x01, 0x02]),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0,
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: Date(),
        unreadCount: 0
    )
}

private func makeChannel(radioID: UUID, index: UInt8 = 3, name: String = "General") -> ChannelDTO {
    ChannelDTO(
        id: UUID(),
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

private func makeIncomingMessage(
    radioID: UUID,
    contactID: UUID? = nil,
    channelIndex: UInt8? = nil,
    senderNodeName: String? = nil,
    text: String = "Hello world",
    timestamp: UInt32 = 1_704_067_200
) -> MessageDTO {
    MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: contactID,
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
        senderNodeName: senderNodeName,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )
}

// MARK: - Tests

@Suite("ChatViewModel Reaction Indexing")
@MainActor
struct ChatViewModelReactionIndexingTests {

    private func makeViewModel(with message: MessageDTO) -> ChatViewModel {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator
        coordinator.replaceAll([message])
        return viewModel
    }

    @Test("DM scope matches a queued pending reaction, persists it scoped to the contact, and updates the summary")
    func dmScopePersistsPendingReaction() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let reactionService = ReactionService()

        let radioID = UUID()
        let contact = makeContact(radioID: radioID)
        let message = makeIncomingMessage(radioID: radioID, contactID: contact.id)
        try await dataStore.saveMessage(message)

        let viewModel = makeViewModel(with: message)

        // Queue a reaction whose target hasn't been indexed yet, as the live
        // receive path does when the referenced message isn't in the cache.
        let rawText = ReactionParser.buildDMReactionText(
            emoji: "👍",
            targetText: message.text,
            targetTimestamp: message.reactionTimestamp
        )
        let parsed = try #require(ReactionParser.parseDM(rawText))
        await reactionService.queuePendingDMReaction(
            parsed: parsed,
            contactID: contact.id,
            senderName: "Bob",
            rawText: rawText,
            radioID: contact.radioID
        )

        await viewModel.indexMessagesForReactions(
            [message],
            scope: .direct(contact),
            reactionService: reactionService,
            dataStore: dataStore
        )

        let persisted = try await dataStore.fetchReactions(for: message.id)
        #expect(persisted.count == 1)
        let reaction = try #require(persisted.first)
        #expect(reaction.emoji == "👍")
        #expect(reaction.senderName == "Bob")
        #expect(reaction.contactID == contact.id)
        #expect(reaction.channelIndex == nil)
        #expect(reaction.radioID == contact.radioID, "Persisted radioID must come from the contact, never a freshly minted UUID")
        #expect(viewModel.messages.first?.reactionSummary?.contains("👍") == true)
    }

    @Test("Channel scope matches a queued pending reaction, persists it scoped to the channel, and updates the summary")
    func channelScopePersistsPendingReaction() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let reactionService = ReactionService()

        let radioID = UUID()
        let channel = makeChannel(radioID: radioID)
        let message = makeIncomingMessage(
            radioID: radioID,
            channelIndex: channel.index,
            senderNodeName: "Alice"
        )
        try await dataStore.saveMessage(message)

        let viewModel = makeViewModel(with: message)

        let rawText = reactionService.buildReactionText(
            emoji: "🔥",
            targetSender: "Alice",
            targetText: message.text,
            targetTimestamp: message.timestamp
        )
        let parsed = try #require(ReactionParser.parse(rawText))
        await reactionService.queuePendingReaction(
            parsed: parsed,
            channelIndex: channel.index,
            senderNodeName: "Bob",
            rawText: rawText,
            radioID: channel.radioID
        )

        await viewModel.indexMessagesForReactions(
            [message],
            scope: .channel(channel, localNodeName: nil),
            reactionService: reactionService,
            dataStore: dataStore
        )

        let persisted = try await dataStore.fetchReactions(for: message.id)
        #expect(persisted.count == 1)
        let reaction = try #require(persisted.first)
        #expect(reaction.emoji == "🔥")
        #expect(reaction.senderName == "Bob")
        #expect(reaction.channelIndex == channel.index)
        #expect(reaction.contactID == nil)
        #expect(reaction.radioID == channel.radioID, "Persisted radioID must come from the channel, never a freshly minted UUID")
        #expect(viewModel.messages.first?.reactionSummary?.contains("🔥") == true)
    }

    @Test("Re-queued duplicate reaction is dropped by the reactionExists guard")
    func duplicatePendingReactionIsNotPersistedTwice() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let reactionService = ReactionService()

        let radioID = UUID()
        let contact = makeContact(radioID: radioID)
        let message = makeIncomingMessage(radioID: radioID, contactID: contact.id)
        try await dataStore.saveMessage(message)

        let viewModel = makeViewModel(with: message)

        let rawText = ReactionParser.buildDMReactionText(
            emoji: "👍",
            targetText: message.text,
            targetTimestamp: message.reactionTimestamp
        )
        let parsed = try #require(ReactionParser.parseDM(rawText))

        // Queue, index, then queue the identical reaction again and re-index;
        // the second pass must hit the reactionExists guard and not duplicate.
        for _ in 0..<2 {
            await reactionService.queuePendingDMReaction(
                parsed: parsed,
                contactID: contact.id,
                senderName: "Bob",
                rawText: rawText,
                radioID: contact.radioID
            )
            await viewModel.indexMessagesForReactions(
                [message],
                scope: .direct(contact),
                reactionService: reactionService,
                dataStore: dataStore
            )
        }

        let persisted = try await dataStore.fetchReactions(for: message.id)
        #expect(persisted.count == 1)
    }

    @Test("Channel scope skips outgoing messages when the local node name is unknown")
    func channelScopeSkipsOutgoingWithoutLocalNodeName() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let reactionService = ReactionService()

        let radioID = UUID()
        let channel = makeChannel(radioID: radioID)
        var message = makeIncomingMessage(radioID: radioID, channelIndex: channel.index, senderNodeName: nil)
        message.direction = .outgoing
        try await dataStore.saveMessage(message)

        let viewModel = makeViewModel(with: message)

        let rawText = reactionService.buildReactionText(
            emoji: "🔥",
            targetSender: "Me",
            targetText: message.text,
            targetTimestamp: message.timestamp
        )
        let parsed = try #require(ReactionParser.parse(rawText))
        await reactionService.queuePendingReaction(
            parsed: parsed,
            channelIndex: channel.index,
            senderNodeName: "Bob",
            rawText: rawText,
            radioID: channel.radioID
        )

        await viewModel.indexMessagesForReactions(
            [message],
            scope: .channel(channel, localNodeName: nil),
            reactionService: reactionService,
            dataStore: dataStore
        )

        // Without a resolvable sender name the message is never indexed, so
        // the queued reaction stays pending and nothing is persisted.
        let persisted = try await dataStore.fetchReactions(for: message.id)
        #expect(persisted.isEmpty)
    }
}
