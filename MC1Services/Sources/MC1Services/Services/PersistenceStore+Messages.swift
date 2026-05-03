import Foundation
import os
import SwiftData

extension PersistenceStore {

    // MARK: - Mention Tracking

    public func markMentionSeen(messageID: UUID) throws {
        let targetID = messageID
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first else { return }
        message.mentionSeen = true
        try modelContext.save()
    }

    // MARK: - Message Operations

    /// Batch fetch last messages for multiple contacts in a single actor-isolated call.
    /// Runs N fetches with zero suspension points between them, avoiding N actor hops.
    public func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] {
        var result: [UUID: [MessageDTO]] = [:]
        result.reserveCapacity(contactIDs.count)
        for contactID in contactIDs {
            result[contactID] = try fetchMessages(contactID: contactID, limit: limit)
        }
        return result
    }

    /// Batch fetch last messages for multiple channels in a single actor-isolated call.
    /// Runs N fetches with zero suspension points between them, avoiding N actor hops.
    public func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] {
        var result: [UUID: [MessageDTO]] = [:]
        result.reserveCapacity(channels.count)
        for channel in channels {
            result[channel.id] = try fetchMessages(radioID: channel.radioID, channelIndex: channel.channelIndex, limit: limit)
        }
        return result
    }

    /// Fetch messages for a contact
    public func fetchMessages(contactID: UUID, limit: Int = 50, offset: Int = 0) throws -> [MessageDTO] {
        let targetContactID: UUID? = contactID
        let predicate = #Predicate<Message> { message in
            message.contactID == targetContactID
        }
        var descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [
                SortDescriptor(\Message.createdAt, order: .reverse),
                SortDescriptor(\Message.timestamp, order: .reverse)
            ]
        )
        descriptor.fetchLimit = limit
        descriptor.fetchOffset = offset

        let messages = try modelContext.fetch(descriptor)
        let dtos = messages.reversed().map { MessageDTO(from: $0) }
        return MessageDTO.reorderSameSenderClusters(dtos)
    }

    /// Fetch messages for a channel
    public func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int = 50, offset: Int = 0) throws -> [MessageDTO] {
        let targetRadioID = radioID
        let targetChannelIndex: UInt8? = channelIndex
        let predicate = #Predicate<Message> { message in
            message.radioID == targetRadioID && message.channelIndex == targetChannelIndex
        }
        var descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [
                SortDescriptor(\Message.createdAt, order: .reverse),
                SortDescriptor(\Message.timestamp, order: .reverse)
            ]
        )
        descriptor.fetchLimit = limit
        descriptor.fetchOffset = offset

        let messages = try modelContext.fetch(descriptor)
        let dtos = messages.reversed().map { MessageDTO(from: $0) }
        return MessageDTO.reorderSameSenderClusters(dtos)
    }

    /// Finds a channel message matching a parsed reaction within a timestamp window.
    public func findChannelMessageForReaction(
        radioID: UUID,
        channelIndex: UInt8,
        parsedReaction: ParsedReaction,
        localNodeName: String?,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) throws -> MessageDTO? {
        let logger = Logger(subsystem: "MC1Services", category: "PersistenceStore")
        // swiftlint:disable:next line_length
        logger.debug("[REACTION-MATCH] Looking for message: targetSender=\(parsedReaction.targetSender), hash=\(parsedReaction.messageHash), localNodeName=\(localNodeName ?? "nil"), window=\(timestampWindow.lowerBound)...\(timestampWindow.upperBound)")

        let candidates = try fetchChannelMessageCandidates(
            radioID: radioID,
            channelIndex: channelIndex,
            timestampWindow: timestampWindow,
            limit: limit
        )
        logger.debug("[REACTION-MATCH] Found \(candidates.count) candidates in window")
        guard !candidates.isEmpty else { return nil }

        for candidate in candidates {
            let direction = candidate.direction == .outgoing ? "outgoing" : "incoming"
            let candidateHash = ReactionParser.generateMessageHash(text: candidate.text, timestamp: candidate.reactionTimestamp)
            logger.debug("[REACTION-MATCH] Candidate: direction=\(direction), senderNodeName=\(candidate.senderNodeName ?? "nil"), hash=\(candidateHash), text=\(candidate.text.prefix(30))")

            if candidate.direction == .outgoing {
                guard let localNodeName, parsedReaction.targetSender == localNodeName else {
                    logger.debug("[REACTION-MATCH] Skip outgoing: localNodeName=\(localNodeName ?? "nil"), targetSender=\(parsedReaction.targetSender)")
                    continue
                }
            } else {
                guard candidate.senderNodeName == parsedReaction.targetSender else {
                    logger.debug("[REACTION-MATCH] Skip incoming: senderNodeName=\(candidate.senderNodeName ?? "nil") != targetSender=\(parsedReaction.targetSender)")
                    continue
                }
            }

            guard candidateHash == parsedReaction.messageHash else {
                logger.debug("[REACTION-MATCH] Hash mismatch: \(candidateHash) != \(parsedReaction.messageHash)")
                continue
            }

            logger.debug("[REACTION-MATCH] Found match!")
            return candidate
        }

        logger.debug("[REACTION-MATCH] No match found")
        return nil
    }

    /// Fetches channel message candidates within a timestamp window for meshcore-open reaction matching.
    ///
    /// Returns raw candidates without hash matching — the caller performs Dart hash comparison.
    public func fetchChannelMessageCandidates(
        radioID: UUID,
        channelIndex: UInt8,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) throws -> [MessageDTO] {
        let targetRadioID = radioID
        let targetChannelIndex: UInt8? = channelIndex
        let start = timestampWindow.lowerBound
        let end = timestampWindow.upperBound

        let predicate = #Predicate<Message> { message in
            message.radioID == targetRadioID &&
            message.channelIndex == targetChannelIndex &&
            message.timestamp >= start &&
            message.timestamp <= end
        }

        var descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [
                SortDescriptor(\Message.createdAt, order: .reverse),
                SortDescriptor(\Message.timestamp, order: .reverse)
            ]
        )
        descriptor.fetchLimit = limit

        return try modelContext.fetch(descriptor).map { MessageDTO(from: $0) }
    }

    /// Fetches DM message candidates within a timestamp window for meshcore-open reaction matching.
    ///
    /// Returns raw candidates without hash matching — the caller performs Dart hash comparison.
    public func fetchDMMessageCandidates(
        radioID: UUID,
        contactID: UUID,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) throws -> [MessageDTO] {
        let targetRadioID = radioID
        let targetContactID: UUID? = contactID
        let start = timestampWindow.lowerBound
        let end = timestampWindow.upperBound

        let predicate = #Predicate<Message> { message in
            message.radioID == targetRadioID &&
            message.contactID == targetContactID &&
            message.timestamp >= start &&
            message.timestamp <= end
        }

        var descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [
                SortDescriptor(\Message.createdAt, order: .reverse),
                SortDescriptor(\Message.timestamp, order: .reverse)
            ]
        )
        descriptor.fetchLimit = limit

        return try modelContext.fetch(descriptor).map { MessageDTO(from: $0) }
    }

    /// Finds a DM message matching a reaction by hash within a timestamp window.
    public func findDMMessageForReaction(
        radioID: UUID,
        contactID: UUID,
        messageHash: String,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) throws -> MessageDTO? {
        let logger = Logger(subsystem: "MC1Services", category: "PersistenceStore")
        logger.debug("[DM-REACTION-MATCH] Looking for DM: hash=\(messageHash), contactID=\(contactID)")

        let candidates = try fetchDMMessageCandidates(
            radioID: radioID,
            contactID: contactID,
            timestampWindow: timestampWindow,
            limit: limit
        )
        logger.debug("[DM-REACTION-MATCH] Found \(candidates.count) candidates")

        for candidate in candidates {
            // Skip messages that are themselves reactions
            if ReactionParser.isReactionText(candidate.text, isDM: true) {
                logger.debug("[DM-REACTION-MATCH] Skipping candidate (is reaction): \(candidate.text.prefix(30))")
                continue
            }

            let direction = candidate.direction == .outgoing ? "outgoing" : "incoming"
            let candidateHash = ReactionParser.generateMessageHash(
                text: candidate.text,
                timestamp: candidate.reactionTimestamp
            )
            logger.debug("[DM-REACTION-MATCH] Candidate: direction=\(direction), timestamp=\(candidate.timestamp), senderTimestamp=\(candidate.senderTimestamp ?? 0), hash=\(candidateHash), text=\(candidate.text.prefix(30))")
            if candidateHash == messageHash {
                logger.debug("[DM-REACTION-MATCH] Found match: \(candidate.id)")
                return candidate
            } else {
                logger.debug("[DM-REACTION-MATCH] Hash mismatch: \(candidateHash) != \(messageHash)")
            }
        }

        return nil
    }

    /// Fetch a message by ID
    public func fetchMessage(id: UUID) throws -> MessageDTO? {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { MessageDTO(from: $0) }
    }

    /// Check if a message with this deduplication key already exists for the given radio.
    public func isDuplicateMessage(deduplicationKey: String, radioID: UUID) throws -> Bool {
        let targetKey = deduplicationKey
        let targetRadioID = radioID
        let predicate = #Predicate<Message> {
            $0.deduplicationKey == targetKey && $0.radioID == targetRadioID
        }
        return try modelContext.fetchCount(FetchDescriptor(predicate: predicate)) > 0
    }

    /// Save a new message
    public func saveMessage(_ dto: MessageDTO) throws {
        let message = Message(
            id: dto.id,
            radioID: dto.radioID,
            contactID: dto.contactID,
            channelIndex: dto.channelIndex,
            text: dto.text,
            timestamp: dto.timestamp,
            createdAt: dto.createdAt,
            directionRawValue: dto.direction.rawValue,
            statusRawValue: dto.status.rawValue,
            textTypeRawValue: dto.textType.rawValue,
            ackCode: dto.ackCode,
            pathLength: dto.pathLength,
            snr: dto.snr,
            pathNodes: dto.pathNodes,
            senderKeyPrefix: dto.senderKeyPrefix,
            senderNodeName: dto.senderNodeName,
            isRead: dto.isRead,
            replyToID: dto.replyToID,
            roundTripTime: dto.roundTripTime,
            heardRepeats: dto.heardRepeats,
            retryAttempt: dto.retryAttempt,
            maxRetryAttempts: dto.maxRetryAttempts,
            deduplicationKey: dto.deduplicationKey,
            containsSelfMention: dto.containsSelfMention,
            mentionSeen: dto.mentionSeen,
            timestampCorrected: dto.timestampCorrected,
            senderTimestamp: dto.senderTimestamp,
            routeTypeRawValue: dto.routeType.map { Int($0.rawValue) } ?? -1,
            regionScope: dto.regionScope
        )
        modelContext.insert(message)
        try modelContext.save()
    }

    /// Update message status
    public func updateMessageStatus(id: UUID, status: MessageStatus) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            message.status = status
            try modelContext.save()
        }
    }

    /// Update message status unless delivery has already won the race.
    ///
    /// - Returns: `true` if the row's status was changed, `false` if no row was
    ///   updated (either the row is already `.delivered`, or no row exists for
    ///   the given `id`). Callers must gate failure side effects (e.g.,
    ///   `messageFailedHandler`, UI toasts) on the return value so they do not
    ///   surface a `.failed` event for a delivered or absent row.
    public func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) throws -> Bool {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first, message.status != .delivered else {
            return false
        }
        message.status = status
        try modelContext.save()
        return true
    }

    /// Update message status with retry attempt information.
    ///
    /// Skips the write when the message is already `.delivered` so a stale
    /// retry iteration (e.g., one racing the persistent ACK listener) cannot
    /// clobber a winning ACK.
    public func updateMessageRetryStatus(
        id: UUID,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int
    ) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first, message.status != .delivered {
            message.status = status
            message.retryAttempt = retryAttempt
            message.maxRetryAttempts = maxRetryAttempts
            try modelContext.save()
        }
    }

    /// Update message timestamp (for resending)
    public func updateMessageTimestamp(id: UUID, timestamp: UInt32) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            message.timestamp = timestamp
            try modelContext.save()
        }
    }

    /// Update message ACK info.
    ///
    /// Never downgrades a `.delivered` message to a lower status: once the
    /// listener (or `finalizeSend`) writes `.delivered` + `roundTripTime`, a
    /// later `.sent` write from the send-return path is skipped so the
    /// authoritative delivery state is preserved.
    public func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32? = nil) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            if message.status == .delivered && status != .delivered { return }
            message.ackCode = ackCode
            message.status = status
            message.roundTripTime = roundTripTime
            try modelContext.save()
        }
    }

    /// Mark a message as read
    public func markMessageAsRead(id: UUID) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            message.isRead = true
            try modelContext.save()
        }
    }

    /// Updates the heard repeats count for a message
    public func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            message.heardRepeats = heardRepeats
            try modelContext.save()
        }
    }

    /// Update link preview data for a message
    public func updateMessageLinkPreview(
        id: UUID,
        url: String?,
        title: String?,
        imageData: Data?,
        iconData: Data?,
        fetched: Bool
    ) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let message = try modelContext.fetch(descriptor).first {
            message.linkPreviewURL = url
            message.linkPreviewTitle = title
            message.linkPreviewImageData = imageData
            message.linkPreviewIconData = iconData
            message.linkPreviewFetched = fetched
            try modelContext.save()
        }
    }

    /// Delete a message and its reactions
    public func deleteMessage(id: UUID) throws {
        let targetID = id
        let predicate = #Predicate<Message> { message in
            message.id == targetID
        }
        if let message = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first {
            try deleteReactionsForMessage(messageID: id)
            modelContext.delete(message)
            try modelContext.save()
        }
    }

    /// Delete all channel messages from a specific sender for a device.
    /// Only deletes messages with a non-nil channelIndex (channel messages), preserving DMs.
    /// Also deletes any reactions associated with the deleted messages.
    public func deleteChannelMessages(fromSender senderName: String, radioID: UUID) throws {
        let targetRadioID = radioID
        let targetSenderName: String? = senderName
        let messagePredicate = #Predicate<Message> { message in
            message.radioID == targetRadioID &&
            message.senderNodeName == targetSenderName &&
            message.channelIndex != nil
        }

        // Fetch message IDs to clean up associated reactions
        let messageIDs = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate)).map(\.id)

        if !messageIDs.isEmpty {
            try modelContext.delete(model: Reaction.self, where: #Predicate {
                messageIDs.contains($0.messageID)
            })
        }

        try modelContext.delete(model: Message.self, where: messagePredicate)
        try modelContext.save()
    }

    /// Count pending messages for a device
    public func countPendingMessages(radioID: UUID) throws -> Int {
        let targetRadioID = radioID
        let pendingStatus = MessageStatus.pending.rawValue
        let sendingStatus = MessageStatus.sending.rawValue
        let predicate = #Predicate<Message> { message in
            message.radioID == targetRadioID &&
            (message.statusRawValue == pendingStatus ||
             message.statusRawValue == sendingStatus)
        }
        return try modelContext.fetchCount(FetchDescriptor(predicate: predicate))
    }

    // MARK: - Heard Repeats

    /// Finds a sent channel message matching the given criteria within a time window.
    /// Used for correlating RX log entries to sent messages.
    ///
    /// - Parameters:
    ///   - radioID: The radio that sent the message
    ///   - channelIndex: Channel the message was sent on
    ///   - timestamp: Sender timestamp from the message
    ///   - text: Message text to match
    ///   - withinSeconds: Time window to search (default 10 seconds)
    /// - Returns: MessageDTO if found, nil otherwise
    public func findSentChannelMessage(
        radioID: UUID,
        channelIndex: UInt8,
        timestamp: UInt32,
        text: String,
        withinSeconds: Int = 10
    ) throws -> MessageDTO? {
        let targetRadioID = radioID
        let targetChannelIndex: UInt8? = channelIndex
        let targetTimestamp = timestamp
        let outgoingDirection = MessageDirection.outgoing.rawValue

        // Calculate time window
        let now = Date()
        let windowStart = now.addingTimeInterval(-TimeInterval(withinSeconds))
        let windowStartTimestamp = UInt32(windowStart.timeIntervalSince1970)

        let predicate = #Predicate<Message> { message in
            message.radioID == targetRadioID &&
            message.channelIndex == targetChannelIndex &&
            message.timestamp == targetTimestamp &&
            message.directionRawValue == outgoingDirection &&
            message.timestamp >= windowStartTimestamp
        }

        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first else {
            return nil
        }

        // Verify text matches (outgoing channel messages store just the text)
        guard message.text == text else {
            return nil
        }

        return MessageDTO(from: message)
    }

    /// Saves a new MessageRepeat entry and links it to the parent message.
    public func saveMessageRepeat(_ dto: MessageRepeatDTO) throws {
        // Fetch the parent message for relationship
        let targetMessageID = dto.messageID
        let messagePredicate = #Predicate<Message> { message in
            message.id == targetMessageID
        }
        var messageDescriptor = FetchDescriptor(predicate: messagePredicate)
        messageDescriptor.fetchLimit = 1

        guard let parentMessage = try modelContext.fetch(messageDescriptor).first else {
            throw PersistenceStoreError.messageNotFound
        }

        let repeat_ = MessageRepeat(
            id: dto.id,
            message: parentMessage,
            messageID: dto.messageID,
            receivedAt: dto.receivedAt,
            pathNodes: dto.pathNodes,
            pathLength: dto.pathLength,
            snr: dto.snr,
            rssi: dto.rssi,
            rxLogEntryID: dto.rxLogEntryID
        )
        modelContext.insert(repeat_)
        try modelContext.save()
    }

    /// Fetches all repeats for a given message, sorted by receivedAt ascending.
    public func fetchMessageRepeats(messageID: UUID) throws -> [MessageRepeatDTO] {
        let targetMessageID = messageID
        let predicate = #Predicate<MessageRepeat> { repeat_ in
            repeat_.messageID == targetMessageID
        }
        let descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [SortDescriptor(\MessageRepeat.receivedAt, order: .forward)]
        )

        let results = try modelContext.fetch(descriptor)
        return results.map { MessageRepeatDTO(from: $0) }
    }

    /// Deletes all repeats for a given message.
    public func deleteMessageRepeats(messageID: UUID) throws {
        let targetMessageID = messageID
        let predicate = #Predicate<MessageRepeat> { repeat_ in
            repeat_.messageID == targetMessageID
        }
        let descriptor = FetchDescriptor(predicate: predicate)

        let results = try modelContext.fetch(descriptor)
        for repeat_ in results {
            modelContext.delete(repeat_)
        }
        try modelContext.save()
    }

    /// Checks if a repeat already exists for the given RX log entry.
    public func messageRepeatExists(rxLogEntryID: UUID) throws -> Bool {
        let targetID: UUID? = rxLogEntryID
        let predicate = #Predicate<MessageRepeat> { repeat_ in
            repeat_.rxLogEntryID == targetID
        }
        return try modelContext.fetchCount(FetchDescriptor(predicate: predicate)) > 0
    }

    /// Increments the heardRepeats count for a message and returns the new count.
    public func incrementMessageHeardRepeats(id: UUID) throws -> Int {
        let targetID = id
        let predicate = #Predicate<Message> { message in message.id == targetID }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first else {
            return 0
        }

        message.heardRepeats += 1
        try modelContext.save()
        return message.heardRepeats
    }

    /// Increments the sendCount for a message and returns the new count.
    public func incrementMessageSendCount(id: UUID) throws -> Int {
        let targetID = id
        let predicate = #Predicate<Message> { message in message.id == targetID }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first else {
            return 0
        }

        message.sendCount += 1
        try modelContext.save()
        return message.sendCount
    }

    // MARK: - Reactions

    /// Saves a new reaction
    public func saveReaction(_ dto: ReactionDTO) throws {
        let reaction = Reaction(
            id: dto.id,
            messageID: dto.messageID,
            emoji: dto.emoji,
            senderName: dto.senderName,
            messageHash: dto.messageHash,
            rawText: dto.rawText,
            receivedAt: dto.receivedAt,
            channelIndex: dto.channelIndex,
            contactID: dto.contactID,
            radioID: dto.radioID
        )
        modelContext.insert(reaction)
        try modelContext.save()
    }

    /// Fetches reactions for a message
    public func fetchReactions(for messageID: UUID, limit: Int = 100) throws -> [ReactionDTO] {
        let targetMessageID = messageID
        var descriptor = FetchDescriptor<Reaction>(
            predicate: #Predicate { $0.messageID == targetMessageID },
            sortBy: [SortDescriptor(\Reaction.receivedAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return try modelContext.fetch(descriptor).map { ReactionDTO(from: $0) }
    }

    /// Checks if a reaction already exists (deduplication)
    public func reactionExists(messageID: UUID, senderName: String, emoji: String) throws -> Bool {
        let targetMessageID = messageID
        let targetSenderName = senderName
        let targetEmoji = emoji
        let predicate = #Predicate<Reaction> {
            $0.messageID == targetMessageID &&
            $0.senderName == targetSenderName &&
            $0.emoji == targetEmoji
        }
        return try modelContext.fetchCount(FetchDescriptor(predicate: predicate)) > 0
    }

    /// Updates a message's reaction summary cache
    public func updateMessageReactionSummary(messageID: UUID, summary: String?) throws {
        let targetMessageID = messageID
        let predicate = #Predicate<Message> { $0.id == targetMessageID }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let message = try modelContext.fetch(descriptor).first else { return }
        message.reactionSummary = summary
        try modelContext.save()
    }

    /// Deletes all reactions for a message
    public func deleteReactionsForMessage(messageID: UUID) throws {
        let targetMessageID = messageID
        try modelContext.delete(model: Reaction.self, where: #Predicate {
            $0.messageID == targetMessageID
        })
        try modelContext.save()
    }
}
