import Foundation
import Testing
@testable import MC1Services

@Suite("ReactionService Tests")
struct ReactionServiceTests {

    @Test("Builds correct wire format with Crockford Base32 identifier")
    func buildsWireFormat() async {
        let service = ReactionService()
        let timestamp: UInt32 = 1704067200

        let text = service.buildReactionText(
            emoji: "👍",
            targetSender: "AlphaNode",
            targetText: "What's the situation at Main St today?",
            targetTimestamp: timestamp
        )

        // Verify format: {emoji}@[{sender}]\n{hash}
        #expect(text.hasPrefix("👍@[AlphaNode]\n"))

        // Verify 8-char Crockford Base32 identifier is present (lowercase) at end
        let idPattern = #/\n([0-9a-hj-km-np-tv-z]{8})$/#
        #expect(text.firstMatch(of: idPattern) != nil)
    }

    @Test("Builds wire format with short message")
    func buildsWireFormatShortMessage() async {
        let service = ReactionService()
        let timestamp: UInt32 = 1704067200

        let text = service.buildReactionText(
            emoji: "❤️",
            targetSender: "Node",
            targetText: "ok",
            targetTimestamp: timestamp
        )

        #expect(text.hasPrefix("❤️@[Node]\n"))
        #expect(text.hasSuffix(text.suffix(8))) // ends with 8-char hash
    }

    @Test("Generated identifier is consistent")
    func generatedIdentifierIsConsistent() async {
        let service = ReactionService()
        let timestamp: UInt32 = 1704067200
        let targetText = "Hello world"

        let text1 = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: targetText,
            targetTimestamp: timestamp
        )

        let text2 = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: targetText,
            targetTimestamp: timestamp
        )

        #expect(text1 == text2)
    }

    @Test("Different timestamps produce different identifiers")
    func differentTimestampsDifferentIdentifiers() async {
        let service = ReactionService()
        let targetText = "Hello world"

        let text1 = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: targetText,
            targetTimestamp: 1704067200
        )

        let text2 = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: targetText,
            targetTimestamp: 1704067201
        )

        #expect(text1 != text2)
    }

    // MARK: - Disambiguation Tests

    @Test("Finds indexed message by hash and preview")
    func findsIndexedMessage() async {
        let service = ReactionService()
        let messageID = UUID()
        let timestamp: UInt32 = 1704067200

        await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "Node",
            text: "Hello world",
            timestamp: timestamp
        )

        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )

        let parsed = ReactionParser.parse(reactionText)!
        let foundID = await service.findTargetMessage(parsed: parsed, channelIndex: 0)

        #expect(foundID == messageID)
    }

    @Test("Returns nil when no candidates exist")
    func returnsNilWhenNoCandidates() async {
        let service = ReactionService()

        let parsed = ParsedReaction(
            emoji: "👍",
            targetSender: "Node",
            messageHash: "abcd1234"
        )

        let foundID = await service.findTargetMessage(parsed: parsed, channelIndex: 0)

        #expect(foundID == nil)
    }

    @Test("Returns most recently indexed when multiple candidates have same hash")
    func returnsMostRecentWhenMultipleCandidates() async {
        let service = ReactionService()
        let id1 = UUID()
        let id2 = UUID()
        let timestamp: UInt32 = 1704067200

        // Index two messages with same hash (same text and timestamp)
        _ = await service.indexMessage(
            id: id1,
            channelIndex: 0,
            senderName: "Node",
            text: "Same message",
            timestamp: timestamp
        )

        // Small delay to ensure different indexedAt times
        try? await Task.sleep(for: .milliseconds(10))

        _ = await service.indexMessage(
            id: id2,
            channelIndex: 0,
            senderName: "Node",
            text: "Same message",
            timestamp: timestamp
        )

        // Build reaction for the message
        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "Node",
            targetText: "Same message",
            targetTimestamp: timestamp
        )

        let parsed = ReactionParser.parse(reactionText)!
        let foundID = await service.findTargetMessage(parsed: parsed, channelIndex: 0)

        // Should find the most recently indexed (id2)
        #expect(foundID == id2)
    }

    // MARK: - Pending Reactions Queue Tests

    @Test("Queued reaction matches when message indexed")
    func queuedReactionMatchesWhenMessageIndexed() async {
        let service = ReactionService()
        let messageID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Build reaction text for a message that doesn't exist yet
        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "AlphaNode",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )

        let parsed = ReactionParser.parse(reactionText)!

        // Queue the reaction (target message not indexed yet)
        await service.queuePendingReaction(
            parsed: parsed,
            channelIndex: 0,
            senderNodeName: "BetaNode",
            rawText: reactionText,
            radioID: radioID
        )

        // Now index the target message - should return the pending reaction
        let matches = await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "AlphaNode",
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matches.count == 1)
        #expect(matches.first?.parsed.emoji == "👍")
        #expect(matches.first?.senderNodeName == "BetaNode")
    }

    @Test("Multiple reactions for same target all match")
    func multipleReactionsForSameTargetAllMatch() async {
        let service = ReactionService()
        let messageID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue multiple reactions for the same message
        for emoji in ["👍", "❤️", "😂"] {
            let reactionText = service.buildReactionText(
                emoji: emoji,
                targetSender: "AlphaNode",
                targetText: "Hello world",
                targetTimestamp: timestamp
            )
            let parsed = ReactionParser.parse(reactionText)!

            await service.queuePendingReaction(
                parsed: parsed,
                channelIndex: 0,
                senderNodeName: "BetaNode",
                rawText: reactionText,
                radioID: radioID
            )
        }

        // Index the target message - should return all pending reactions
        let matches = await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "AlphaNode",
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matches.count == 3)
        let emojis = Set(matches.map { $0.parsed.emoji })
        #expect(emojis == ["👍", "❤️", "😂"])
    }

    @Test("Hash mismatch prevents false match")
    func hashMismatchPreventsFalseMatch() async {
        let service = ReactionService()
        let messageID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue a reaction for "Hello world"
        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "AlphaNode",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parse(reactionText)!

        await service.queuePendingReaction(
            parsed: parsed,
            channelIndex: 0,
            senderNodeName: "BetaNode",
            rawText: reactionText,
            radioID: radioID
        )

        // Index a different message (different hash)
        let matches = await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "AlphaNode",
            text: "Different text",
            timestamp: timestamp
        )

        // Should NOT match because hash is different
        #expect(matches.isEmpty)
    }

    @Test("Clear removes all pending reactions")
    func clearRemovesAllPending() async {
        let service = ReactionService()
        let messageID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue a reaction
        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "AlphaNode",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parse(reactionText)!

        await service.queuePendingReaction(
            parsed: parsed,
            channelIndex: 0,
            senderNodeName: "BetaNode",
            rawText: reactionText,
            radioID: radioID
        )

        // Clear all pending
        await service.clearPendingReactions()

        // Index the target message - should return nothing
        let matches = await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "AlphaNode",
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matches.isEmpty)
    }

    @Test("Pending reactions are scoped by channel")
    func pendingReactionsScopedByChannel() async {
        let service = ReactionService()
        let messageID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue a reaction for channel 0
        let reactionText = service.buildReactionText(
            emoji: "👍",
            targetSender: "AlphaNode",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parse(reactionText)!

        await service.queuePendingReaction(
            parsed: parsed,
            channelIndex: 0,
            senderNodeName: "BetaNode",
            rawText: reactionText,
            radioID: radioID
        )

        // Index on channel 1 - should NOT match
        let matchesChannel1 = await service.indexMessage(
            id: messageID,
            channelIndex: 1,
            senderName: "AlphaNode",
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matchesChannel1.isEmpty)

        // Index on channel 0 - should match
        let matchesChannel0 = await service.indexMessage(
            id: messageID,
            channelIndex: 0,
            senderName: "AlphaNode",
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matchesChannel0.count == 1)
    }

    // MARK: - DM Reaction Tests

    @Test("Builds DM wire format")
    func buildsDMWireFormat() async {
        let service = ReactionService()
        let text = service.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: 1704067200
        )
        #expect(text.hasPrefix("👍\n"))
        #expect(text.count == 10) // emoji + newline + 8 char hash
        #expect(!text.contains("@["))
    }

    @Test("Indexes DM message and finds by hash")
    func indexesDMMessageAndFinds() async {
        let service = ReactionService()
        let messageID = UUID()
        let contactID = UUID()
        let timestamp: UInt32 = 1704067200

        _ = await service.indexDMMessage(
            id: messageID,
            contactID: contactID,
            text: "Hello world",
            timestamp: timestamp
        )

        let hash = ReactionParser.generateMessageHash(text: "Hello world", timestamp: timestamp)
        let foundID = await service.findDMTargetMessage(
            messageHash: hash,
            contactID: contactID
        )

        #expect(foundID == messageID)
    }

    @Test("DM pending reactions match when message indexed")
    func dmPendingReactionsMatch() async {
        let service = ReactionService()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        let reactionText = service.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )

        let parsed = ReactionParser.parseDM(reactionText)!

        await service.queuePendingDMReaction(
            parsed: parsed,
            contactID: contactID,
            senderName: "Alice",
            rawText: reactionText,
            radioID: radioID
        )

        let matches = await service.indexDMMessage(
            id: messageID,
            contactID: contactID,
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matches.count == 1)
        #expect(matches.first?.parsed.emoji == "👍")
    }

    @Test("DM reactions scoped by contact")
    func dmReactionsScopedByContact() async {
        let service = ReactionService()
        let messageID = UUID()
        let contactID1 = UUID()
        let contactID2 = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        let reactionText = service.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parseDM(reactionText)!

        // Queue for contact1
        await service.queuePendingDMReaction(
            parsed: parsed,
            contactID: contactID1,
            senderName: "Alice",
            rawText: reactionText,
            radioID: radioID
        )

        // Index for contact2 - should NOT match
        let matchesContact2 = await service.indexDMMessage(
            id: messageID,
            contactID: contactID2,
            text: "Hello world",
            timestamp: timestamp
        )
        #expect(matchesContact2.isEmpty)

        // Index for contact1 - should match
        let matchesContact1 = await service.indexDMMessage(
            id: messageID,
            contactID: contactID1,
            text: "Hello world",
            timestamp: timestamp
        )
        #expect(matchesContact1.count == 1)
    }

    @Test("DM returns nil when no candidates in cache")
    func dmReturnsNilWhenNoCandidates() async {
        let service = ReactionService()
        let contactID = UUID()

        let foundID = await service.findDMTargetMessage(
            messageHash: "abcd1234",
            contactID: contactID
        )

        #expect(foundID == nil)
    }

    @Test("DM hash mismatch prevents false match")
    func dmHashMismatchPreventsFalseMatch() async {
        let service = ReactionService()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue a reaction for "Hello world"
        let reactionText = service.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parseDM(reactionText)!

        await service.queuePendingDMReaction(
            parsed: parsed,
            contactID: contactID,
            senderName: "Alice",
            rawText: reactionText,
            radioID: radioID
        )

        // Index a different message (different hash)
        let matches = await service.indexDMMessage(
            id: messageID,
            contactID: contactID,
            text: "Different text",
            timestamp: timestamp
        )

        // Should NOT match because hash is different
        #expect(matches.isEmpty)
    }

    @Test("Clear removes DM pending reactions")
    func clearRemovesDMPending() async {
        let service = ReactionService()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = UUID()
        let timestamp: UInt32 = 1704067200

        // Queue a DM reaction
        let reactionText = service.buildDMReactionText(
            emoji: "👍",
            targetText: "Hello world",
            targetTimestamp: timestamp
        )
        let parsed = ReactionParser.parseDM(reactionText)!

        await service.queuePendingDMReaction(
            parsed: parsed,
            contactID: contactID,
            senderName: "Alice",
            rawText: reactionText,
            radioID: radioID
        )

        // Clear all pending
        await service.clearPendingReactions()

        // Index the target message - should return nothing
        let matches = await service.indexDMMessage(
            id: messageID,
            contactID: contactID,
            text: "Hello world",
            timestamp: timestamp
        )

        #expect(matches.isEmpty)
    }
}
