// SyncCoordinator+HandlerHelpers.swift
import Foundation

// MARK: - Message Handler Helpers

extension SyncCoordinator {

    struct RxLogLookupResult {
        let pathNodes: Data?
        let pathLength: UInt8
        let packetHash: String?
        let routeType: RouteType?
        let regionScope: String?
    }

    /// Looks up path data from an RxLogEntry to correlate with an incoming message.
    func lookupRxLogEntry(
        dependencies: SyncDependencies,
        radioID: UUID,
        channelIndex: UInt8?,
        senderTimestamp: UInt32,
        senderPublicKeyPrefix: Data?,
        defaultPathLength: UInt8
    ) async -> RxLogLookupResult {
        if let channelIndex {
            logger.debug("Looking up RxLogEntry for channel \(channelIndex) with senderTimestamp: \(senderTimestamp)")
        }

        do {
            if let rxEntry = try await dependencies.dataStore.findRxLogEntry(
                radioID: radioID,
                channelIndex: channelIndex,
                senderTimestamp: senderTimestamp
            ) {
                let pathLength = rxEntry.pathLength
                let pathNodes = rxEntry.pathNodes
                if channelIndex != nil {
                    logger.info("Correlated channel message to RxLogEntry: pathLength=\(pathLength), pathNodes=\(pathNodes.count) bytes")
                } else {
                    logger.debug("Correlated incoming direct message to RxLogEntry, pathLength: \(pathLength), pathNodes: \(pathNodes.count) bytes")
                }
                return RxLogLookupResult(pathNodes: pathNodes, pathLength: pathLength, packetHash: rxEntry.packetHash, routeType: rxEntry.routeType, regionScope: rxEntry.regionScope)
            }

            // Fallback for DMs: if timestamp-based lookup failed (e.g., RxLog decryption
            // hadn't extracted the timestamp yet), try matching by sender prefix byte
            // in the raw packet payload within a recent time window.
            if channelIndex == nil,
               let prefixByte = senderPublicKeyPrefix?.first {
                let lookbackWindow = Date().addingTimeInterval(-30)
                if let rxEntry = try await dependencies.dataStore.findRxLogEntryBySenderPrefix(
                    radioID: radioID,
                    senderPrefixByte: prefixByte,
                    receivedSince: lookbackWindow
                ) {
                    logger.debug("Correlated DM to RxLogEntry via sender prefix fallback, pathLength: \(rxEntry.pathLength)")
                    return RxLogLookupResult(pathNodes: rxEntry.pathNodes, pathLength: rxEntry.pathLength, packetHash: rxEntry.packetHash, routeType: rxEntry.routeType, regionScope: rxEntry.regionScope)
                }
                logger.debug("No RxLogEntry found for direct message (primary + fallback), senderTimestamp: \(senderTimestamp)")
            } else if let channelIndex {
                logger.warning("No RxLogEntry found for channel \(channelIndex), senderTimestamp: \(senderTimestamp)")
            } else {
                logger.debug("No RxLogEntry found for direct message, senderTimestamp: \(senderTimestamp)")
            }
        } catch {
            if channelIndex != nil {
                logger.error("Failed to lookup RxLogEntry for channel message: \(error)")
            } else {
                logger.error("Failed to lookup RxLogEntry for direct message: \(error)")
            }
        }

        return RxLogLookupResult(pathNodes: nil, pathLength: defaultPathLength, packetHash: nil, routeType: nil, regionScope: nil)
    }

    /// Increments unread counts and posts a notification for a direct message.
    func updateDMUnreadsAndNotify(
        messageDTO: MessageDTO,
        contactID: UUID,
        contact: ContactDTO?,
        messageText: String,
        hasSelfMention: Bool,
        dependencies: SyncDependencies
    ) async throws {
        // Only increment unread if user is NOT currently viewing this contact's chat
        let isViewingContact = await dependencies.notificationService.activeContactID == contactID
        if !isViewingContact {
            try await dependencies.dataStore.incrementUnreadCount(contactID: contactID)

            // Increment unread mention count if message contains self-mention
            if hasSelfMention {
                try await dependencies.dataStore.incrementUnreadMentionCount(contactID: contactID)
            }
        }

        await dependencies.notificationService.postDirectMessageNotification(
            from: contact?.displayName ?? "Unknown",
            contactID: contactID,
            messageText: messageText,
            messageID: messageDTO.id,
            isMuted: contact?.isMuted ?? false
        )
        await dependencies.notificationService.updateBadgeCount()
    }

    /// Increments unread counts, posts a notification, and notifies real-time listeners for a channel message.
    func updateChannelUnreadsAndNotify(
        messageDTO: MessageDTO,
        channel: ChannelDTO?,
        channelIndex: UInt8,
        senderNodeName: String?,
        messageText: String,
        timestamp: UInt32,
        hasSelfMention: Bool,
        radioID: UUID,
        dependencies: SyncDependencies
    ) async throws {
        if let channelID = channel?.id {
            // Only increment unread if user is NOT currently viewing this channel
            let activeIndex = await dependencies.notificationService.activeChannelIndex
            let activeRadioID = await dependencies.notificationService.activeChannelRadioID
            let isViewingChannel = activeIndex == channel?.index && activeRadioID == channel?.radioID
            if !isViewingChannel {
                try await dependencies.dataStore.incrementChannelUnreadCount(channelID: channelID)

                // Increment unread mention count if message contains self-mention
                if hasSelfMention {
                    try await dependencies.dataStore.incrementChannelUnreadMentionCount(channelID: channelID)
                }
            }
        }
        if Self.shouldPostChannelNotification(forResolvedChannel: channel) {
            await dependencies.notificationService.postChannelMessageNotification(
                channelName: channel?.name ?? "Channel \(channelIndex)",
                channelIndex: channelIndex,
                radioID: radioID,
                senderName: senderNodeName,
                messageText: messageText,
                messageID: messageDTO.id,
                notificationLevel: channel?.notificationLevel ?? .all,
                hasSelfMention: hasSelfMention
            )
        } else {
            recordUnresolvedChannel(
                channelIndex: channelIndex,
                radioID: radioID,
                senderTimestamp: timestamp
            )
        }
        await dependencies.notificationService.updateBadgeCount()

        // Broadcast for real-time chat updates
        dataEventBroadcaster.yield(.channelMessageReceived(message: messageDTO, channelIndex: channelIndex))
    }

    private func recordUnresolvedChannel(
        channelIndex: UInt8,
        radioID: UUID,
        senderTimestamp: UInt32
    ) {
        let isNewIndex = unresolvedChannelIndices.insert(channelIndex).inserted
        logger.warning(
            "Suppressing notification for unresolved channel \(channelIndex) on device \(radioID), senderTimestamp: \(senderTimestamp) — no local channel for this slot"
        )

        let now = Date()
        let shouldEmitSummary: Bool
        if isNewIndex {
            shouldEmitSummary = true
        } else if let lastSummary = lastUnresolvedChannelSummaryAt {
            shouldEmitSummary = now.timeIntervalSince(lastSummary) >= unresolvedChannelSummaryIntervalSeconds
        } else {
            shouldEmitSummary = true
        }

        guard shouldEmitSummary else { return }
        let sortedIndices = unresolvedChannelIndices.sorted()
        logger.warning(
            "Unresolved channel summary: total=\(sortedIndices.count), indices=\(sortedIndices)"
        )
        lastUnresolvedChannelSummaryAt = now
    }
}
