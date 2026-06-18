// SyncCoordinator+ReactionHandlers.swift
import Foundation

// MARK: - Reaction Handlers

extension SyncCoordinator {

    /// Persists a reaction if it doesn't already exist, notifying the UI on success.
    ///
    /// Deduplicates the check-exists → create → persist → notify pattern used across
    /// DM and channel reaction handlers.
    ///
    /// - Returns: `true` if the reaction was new and saved
    @discardableResult
    func persistReactionIfNew(
        _ reactionDTO: ReactionDTO,
        dependencies: SyncDependencies
    ) async -> Bool {
        let exists = try? await dependencies.dataStore.reactionExists(
            messageID: reactionDTO.messageID,
            senderName: reactionDTO.senderName,
            emoji: reactionDTO.emoji
        )

        guard exists != true else { return false }

        if let result = await dependencies.reactionService.persistReactionAndUpdateSummary(
            reactionDTO,
            using: dependencies.dataStore
        ) {
            dataEventBroadcaster.yield(.reactionReceived(messageID: result.messageID, summary: result.summary))
        }

        return true
    }

    /// Handles an incoming DM reaction by looking up the target message and persisting the reaction.
    ///
    /// - Returns: `true` if the message was consumed as a reaction (caller should return early).
    func handleDMReaction(
        text: String,
        contact: ContactDTO,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        // Try meshcore-open v3 format
        if let mcoReaction = MeshCoreOpenReactionParser.parse(text) {
            return await handleMCODMReaction(
                mcoReaction,
                rawText: text,
                contact: contact,
                radioID: radioID,
                dependencies: dependencies
            )
        }

        // Try meshcore-open v1 format
        if let v1Reaction = MeshCoreOpenReactionParser.parseV1(text) {
            return await handleMCOV1DMReaction(
                v1Reaction,
                rawText: text,
                contact: contact,
                radioID: radioID,
                dependencies: dependencies
            )
        }

        guard let parsed = ReactionParser.parseDM(text) else { return false }

        // Try to find target in cache first
        if let targetMessageID = await dependencies.reactionService.findDMTargetMessage(
            messageHash: parsed.messageHash,
            contactID: contact.id
        ) {
            let reactionDTO = ReactionDTO(
                messageID: targetMessageID,
                emoji: parsed.emoji,
                senderName: contact.displayName,
                messageHash: parsed.messageHash,
                rawText: text,
                contactID: contact.id,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved DM reaction \(parsed.emoji) to message \(targetMessageID)")
            }

            return true
        }

        // Try persistence fallback
        let timestampWindow = reactionTimestampWindow()

        if let targetMessage = try? await dependencies.dataStore.findDMMessageForReaction(
            radioID: radioID,
            contactID: contact.id,
            messageHash: parsed.messageHash,
            timestampWindow: timestampWindow,
            limit: 200
        ) {
            let reactionDTO = ReactionDTO(
                messageID: targetMessage.id,
                emoji: parsed.emoji,
                senderName: contact.displayName,
                messageHash: parsed.messageHash,
                rawText: text,
                contactID: contact.id,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved DM reaction \(parsed.emoji) to message \(targetMessage.id) (from DB)")
            }

            return true
        }

        // Queue as pending if target not found
        await dependencies.reactionService.queuePendingDMReaction(
            parsed: parsed,
            contactID: contact.id,
            senderName: contact.displayName,
            rawText: text,
            radioID: radioID
        )

        logger.debug("Queued pending DM reaction \(parsed.emoji)")
        return true
    }

    /// Handles an incoming channel reaction by looking up the target message and persisting the reaction.
    ///
    /// - Returns: `true` if the message was consumed as a reaction.
    func handleChannelReaction(
        text: String,
        channelIndex: UInt8,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Date,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        // Try meshcore-open v3 format
        if let mcoReaction = MeshCoreOpenReactionParser.parse(text) {
            return await handleMCOChannelReaction(
                mcoReaction,
                rawText: text,
                channelIndex: channelIndex,
                senderNodeName: senderNodeName,
                selfNodeName: selfNodeName,
                receiveTime: receiveTime,
                radioID: radioID,
                dependencies: dependencies
            )
        }

        // Try meshcore-open v1 format
        if let v1Reaction = MeshCoreOpenReactionParser.parseV1(text) {
            return await handleMCOV1ChannelReaction(
                v1Reaction,
                rawText: text,
                channelIndex: channelIndex,
                senderNodeName: senderNodeName,
                selfNodeName: selfNodeName,
                radioID: radioID,
                dependencies: dependencies
            )
        }

        guard let parsed = dependencies.reactionService.tryProcessAsReaction(text) else { return false }

        let senderName = senderNodeName ?? "Unknown"

        if let targetMessageID = await dependencies.reactionService.findTargetMessage(
            parsed: parsed,
            channelIndex: channelIndex
        ) {
            let reactionDTO = ReactionDTO(
                messageID: targetMessageID,
                emoji: parsed.emoji,
                senderName: senderName,
                messageHash: parsed.messageHash,
                rawText: text,
                channelIndex: channelIndex,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved reaction \(parsed.emoji) to message \(targetMessageID)")
            }

            return true
        }

        let timestampWindow = reactionTimestampWindow(at: receiveTime)

        logger.debug("DB lookup: selfNodeName='\(selfNodeName)', targetSender=\(parsed.targetSender), hash=\(parsed.messageHash)")

        if let targetMessage = try? await dependencies.dataStore.findChannelMessageForReaction(
            radioID: radioID,
            channelIndex: channelIndex,
            parsedReaction: parsed,
            localNodeName: selfNodeName.isEmpty ? nil : selfNodeName,
            timestampWindow: timestampWindow,
            limit: 200
        ) {
            let targetMessageID = targetMessage.id
            let reactionDTO = ReactionDTO(
                messageID: targetMessageID,
                emoji: parsed.emoji,
                senderName: senderName,
                messageHash: parsed.messageHash,
                rawText: text,
                channelIndex: channelIndex,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                let targetSenderName: String?
                if targetMessage.direction == .outgoing {
                    targetSenderName = selfNodeName.isEmpty ? nil : selfNodeName
                } else {
                    targetSenderName = targetMessage.senderNodeName
                }

                if let targetSenderName {
                    // Index for future reactions (pending matches not needed here since
                    // message exists in DB, so pending reactions would also match via DB fallback)
                    _ = await dependencies.reactionService.indexMessage(
                        id: targetMessageID,
                        channelIndex: channelIndex,
                        senderName: targetSenderName,
                        text: targetMessage.text,
                        timestamp: targetMessage.reactionTimestamp
                    )
                }

                logger.debug("Saved reaction \(parsed.emoji) to message \(targetMessageID) via DB lookup")
            }

            return true
        }

        // Queue reaction for later matching when target message arrives
        await dependencies.reactionService.queuePendingReaction(
            parsed: parsed,
            channelIndex: channelIndex,
            senderNodeName: senderName,
            rawText: text,
            radioID: radioID
        )
        return true
    }

    /// Computes a symmetric timestamp window around the given time for reaction matching.
    private func reactionTimestampWindow(at time: Date = Date()) -> ClosedRange<UInt32> {
        reactionTimestampWindow(anchor: UInt32(time.timeIntervalSince1970))
    }

    /// Computes a symmetric timestamp window around a specific anchor timestamp.
    private func reactionTimestampWindow(anchor: UInt32) -> ClosedRange<UInt32> {
        let start = anchor > reactionTimestampWindowSeconds ? anchor - reactionTimestampWindowSeconds : 0
        return start...(anchor + reactionTimestampWindowSeconds)
    }

    // MARK: - meshcore-open Reaction Handlers

    /// Handles a meshcore-open DM reaction by computing Dart hashes against DB candidates.
    ///
    /// No LRU cache or pending queue — if no match is found, the reaction is silently dropped.
    private func handleMCODMReaction(
        _ mcoReaction: ParsedMCOReaction,
        rawText: String,
        contact: ContactDTO,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        let timestampWindow = reactionTimestampWindow()

        guard let candidates = try? await dependencies.dataStore.fetchDMMessageCandidates(
            radioID: radioID,
            contactID: contact.id,
            timestampWindow: timestampWindow,
            limit: 200
        ), !candidates.isEmpty else {
            logger.debug("MCO DM reaction \(mcoReaction.emoji): no candidates in window")
            return true
        }

        for candidate in candidates {
            // Skip messages that are themselves reactions
            if ReactionParser.isReactionText(candidate.text, isDM: true) { continue }

            let candidateHash = MeshCoreOpenReactionParser.computeReactionHash(
                timestamp: candidate.reactionTimestamp,
                senderName: nil,
                text: candidate.text
            )

            guard candidateHash == mcoReaction.dartHash else { continue }

            let reactionDTO = ReactionDTO(
                messageID: candidate.id,
                emoji: mcoReaction.emoji,
                senderName: contact.displayName,
                messageHash: mcoReaction.dartHash,
                rawText: rawText,
                contactID: contact.id,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved MCO DM reaction \(mcoReaction.emoji) to message \(candidate.id)")
            }
            return true
        }

        logger.debug("MCO DM reaction \(mcoReaction.emoji): no hash match found")
        return true
    }

    /// Handles a meshcore-open channel reaction by computing Dart hashes against DB candidates.
    ///
    /// No LRU cache or pending queue — if no match is found, the reaction is silently dropped.
    private func handleMCOChannelReaction(
        _ mcoReaction: ParsedMCOReaction,
        rawText: String,
        channelIndex: UInt8,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Date,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        let senderName = senderNodeName ?? "Unknown"
        let timestampWindow = reactionTimestampWindow(at: receiveTime)

        guard let candidates = try? await dependencies.dataStore.fetchChannelMessageCandidates(
            radioID: radioID,
            channelIndex: channelIndex,
            timestampWindow: timestampWindow,
            limit: 200
        ), !candidates.isEmpty else {
            logger.debug("MCO channel reaction \(mcoReaction.emoji): no candidates in window")
            return true
        }

        for candidate in candidates {
            // Skip messages that are themselves reactions
            if ReactionParser.isReactionText(candidate.text, isDM: false) { continue }

            // For channel messages, the Dart hash includes the sender name
            let candidateSenderName: String?
            if candidate.direction == .outgoing {
                candidateSenderName = selfNodeName.isEmpty ? nil : selfNodeName
            } else {
                candidateSenderName = candidate.senderNodeName
            }

            let candidateHash = MeshCoreOpenReactionParser.computeReactionHash(
                timestamp: candidate.reactionTimestamp,
                senderName: candidateSenderName,
                text: candidate.text
            )

            guard candidateHash == mcoReaction.dartHash else { continue }

            let reactionDTO = ReactionDTO(
                messageID: candidate.id,
                emoji: mcoReaction.emoji,
                senderName: senderName,
                messageHash: mcoReaction.dartHash,
                rawText: rawText,
                channelIndex: channelIndex,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved MCO channel reaction \(mcoReaction.emoji) to message \(candidate.id)")
            }
            return true
        }

        logger.debug("MCO channel reaction \(mcoReaction.emoji): no hash match found")
        return true
    }

    // MARK: - meshcore-open V1 Reaction Handlers

    /// Handles a meshcore-open v1 DM reaction by matching timestamp + Dart text hash.
    private func handleMCOV1DMReaction(
        _ v1Reaction: ParsedMCOReactionV1,
        rawText: String,
        contact: ContactDTO,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        let timestampWindow = reactionTimestampWindow(
            anchor: v1Reaction.timestampSeconds
        )

        guard let candidates = try? await dependencies.dataStore.fetchDMMessageCandidates(
            radioID: radioID,
            contactID: contact.id,
            timestampWindow: timestampWindow,
            limit: 200
        ), !candidates.isEmpty else {
            logger.debug("MCO v1 DM reaction \(v1Reaction.emoji): no candidates in window")
            return true
        }

        for candidate in candidates {
            if ReactionParser.isReactionText(candidate.text, isDM: true) { continue }

            let textHash = MeshCoreOpenReactionParser.dartStringHash(candidate.text)
            guard textHash == v1Reaction.textHash else { continue }

            let reactionDTO = ReactionDTO(
                messageID: candidate.id,
                emoji: v1Reaction.emoji,
                senderName: contact.displayName,
                messageHash: v1Reaction.messageIdHash,
                rawText: rawText,
                contactID: contact.id,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved MCO v1 DM reaction \(v1Reaction.emoji) to message \(candidate.id)")
            }
            return true
        }

        logger.debug("MCO v1 DM reaction \(v1Reaction.emoji): no hash match found")
        return true
    }

    /// Handles a meshcore-open v1 channel reaction by matching timestamp + Dart sender/text hashes.
    private func handleMCOV1ChannelReaction(
        _ v1Reaction: ParsedMCOReactionV1,
        rawText: String,
        channelIndex: UInt8,
        senderNodeName: String?,
        selfNodeName: String,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async -> Bool {
        let senderName = senderNodeName ?? "Unknown"
        let timestampWindow = reactionTimestampWindow(
            anchor: v1Reaction.timestampSeconds
        )

        guard let candidates = try? await dependencies.dataStore.fetchChannelMessageCandidates(
            radioID: radioID,
            channelIndex: channelIndex,
            timestampWindow: timestampWindow,
            limit: 200
        ), !candidates.isEmpty else {
            logger.debug("MCO v1 channel reaction \(v1Reaction.emoji): no candidates in window")
            return true
        }

        for candidate in candidates {
            if ReactionParser.isReactionText(candidate.text, isDM: false) { continue }

            // Verify sender name hash
            let candidateSenderName: String?
            if candidate.direction == .outgoing {
                candidateSenderName = selfNodeName.isEmpty ? nil : selfNodeName
            } else {
                candidateSenderName = candidate.senderNodeName
            }

            if let name = candidateSenderName {
                let nameHash = MeshCoreOpenReactionParser.dartStringHash(name)
                guard nameHash == v1Reaction.senderNameHash else { continue }
            }

            // Verify text hash
            let textHash = MeshCoreOpenReactionParser.dartStringHash(candidate.text)
            guard textHash == v1Reaction.textHash else { continue }

            let reactionDTO = ReactionDTO(
                messageID: candidate.id,
                emoji: v1Reaction.emoji,
                senderName: senderName,
                messageHash: v1Reaction.messageIdHash,
                rawText: rawText,
                channelIndex: channelIndex,
                radioID: radioID
            )
            if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
                logger.debug("Saved MCO v1 channel reaction \(v1Reaction.emoji) to message \(candidate.id)")
            }
            return true
        }

        logger.debug("MCO v1 channel reaction \(v1Reaction.emoji): no hash match found")
        return true
    }
}
