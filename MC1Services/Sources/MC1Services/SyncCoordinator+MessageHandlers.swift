// SyncCoordinator+MessageHandlers.swift
import CryptoKit
import Foundation

// MARK: - Message & Discovery Handler Wiring

extension SyncCoordinator {

    // MARK: - Message Handler Wiring

    /// Persists a reaction if it doesn't already exist, notifying the UI on success.
    ///
    /// Deduplicates the check-exists → create → persist → notify pattern used across
    /// DM and channel reaction handlers.
    ///
    /// - Returns: `true` if the reaction was new and saved
    @discardableResult
    private func persistReactionIfNew(
        _ reactionDTO: ReactionDTO,
        services: ServiceContainer
    ) async -> Bool {
        let exists = try? await services.dataStore.reactionExists(
            messageID: reactionDTO.messageID,
            senderName: reactionDTO.senderName,
            emoji: reactionDTO.emoji
        )

        guard exists != true else { return false }

        if let result = await services.reactionService.persistReactionAndUpdateSummary(
            reactionDTO,
            using: services.dataStore
        ) {
            await onReactionReceived?(result.messageID, result.summary)
        }

        return true
    }

    func wireMessageHandlers(services: ServiceContainer, radioID: UUID) async {
        logger.info("Wiring message handlers for device \(radioID)")

        // Populate blocked contacts cache
        await refreshBlockedContactsCache(radioID: radioID, dataStore: services.dataStore)

        // Cache device node name for self-mention detection
        let device = try? await services.dataStore.fetchDevice(radioID: radioID)
        let selfNodeName = device?.nodeName ?? ""

        await wireContactMessageHandler(services: services, radioID: radioID, selfNodeName: selfNodeName)
        await wireChannelMessageHandler(services: services, radioID: radioID, selfNodeName: selfNodeName)
        await wireSignedMessageHandler(services: services)
        await wireCLIMessageHandler(services: services)

        logger.info("Message handlers wired successfully")
    }

    // MARK: - Contact Message Handler

    private func wireContactMessageHandler(services: ServiceContainer, radioID: UUID, selfNodeName: String) async {
        await services.messagePollingService.setContactMessageHandler { [weak self] message, contact, context in
            guard let self else { return }

            let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

            // Correct invalid timestamps (sender clock wrong)
            let receiveTime = Date()
            let (finalTimestamp, timestampCorrected) = Self.correctTimestampIfNeeded(timestamp, receiveTime: receiveTime)
            if timestampCorrected {
                self.logger.debug("Corrected invalid direct message timestamp from \(Date(timeIntervalSince1970: TimeInterval(timestamp))) to \(receiveTime)")
            }

            let sortDate = Self.sortDate(for: context, receiveTime: receiveTime)

            // Look up path data from RxLogEntry (for direct messages, channelIndex is nil)
            let rxResult = await self.lookupRxLogEntry(
                services: services,
                channelIndex: nil,
                senderTimestamp: timestamp,
                senderPublicKeyPrefix: message.senderPublicKeyPrefix,
                defaultPathLength: message.pathLength
            )

            // Use content-based key for dedup (stable across retry attempts).
            // The RX log packetHash is per-encrypted-packet and differs between
            // retries with different attempt counters, so it must not drive dedup.
            let deduplicationKey = Self.fallbackDeduplicationKey(
                contactID: contact?.id, channelIndex: nil,
                senderNodeName: nil, timestamp: timestamp, content: message.text
            )

            // Check for self-mention before creating DTO
            let hasSelfMention = !selfNodeName.isEmpty &&
                MentionUtilities.containsSelfMention(in: message.text, selfName: selfNodeName)

            // regionScope is incoming-only by data-pipeline design — outgoing
            // messages do not flow through 0x88 / RxLogEntry correlation.
            let messageDTO = MessageDTO(
                id: UUID(),
                radioID: radioID,
                contactID: contact?.id,
                channelIndex: nil,
                text: message.text,
                timestamp: finalTimestamp,
                createdAt: receiveTime,
                sortDate: sortDate,
                direction: .incoming,
                status: .delivered,
                textType: TextType(rawValue: message.textType) ?? .plain,
                ackCode: nil,
                pathLength: rxResult.pathLength,
                snr: message.snr,
                pathNodes: rxResult.pathNodes,
                senderKeyPrefix: message.senderPublicKeyPrefix,
                senderNodeName: nil,
                isRead: false,
                replyToID: nil,
                roundTripTime: nil,
                heardRepeats: 0,
                retryAttempt: 0,
                maxRetryAttempts: 0,
                deduplicationKey: deduplicationKey,
                containsSelfMention: hasSelfMention,
                mentionSeen: false,
                timestampCorrected: timestampCorrected,
                senderTimestamp: timestampCorrected ? timestamp : nil,
                routeType: rxResult.routeType,
                regionScope: rxResult.regionScope
            )

            // Check for duplicate before saving
            do {
                if try await services.dataStore.isDuplicateMessage(deduplicationKey: deduplicationKey, radioID: radioID) {
                    self.logger.info("Skipping duplicate direct message")
                    return
                }
            } catch {
                self.logger.warning("Dedup check failed, proceeding with save: \(error)")
            }

            // Check if this is a DM reaction
            if let contact,
               await self.handleDMReaction(
                   text: message.text,
                   contact: contact,
                   radioID: radioID,
                   services: services
               ) {
                return
            }

            do {
                try await services.dataStore.saveMessage(messageDTO)

                // Index DM message for reaction targeting
                if let contact {
                    let pendingMatches = await services.reactionService.indexDMMessage(
                        id: messageDTO.id,
                        contactID: contact.id,
                        text: message.text,
                        timestamp: timestamp
                    )

                    // Process pending reactions that now have their target
                    for pending in pendingMatches {
                        let reactionDTO = ReactionDTO(
                            messageID: messageDTO.id,
                            emoji: pending.parsed.emoji,
                            senderName: pending.senderName,
                            messageHash: pending.parsed.messageHash,
                            rawText: pending.rawText,
                            contactID: contact.id,
                            radioID: radioID
                        )
                        if await self.persistReactionIfNew(reactionDTO, services: services) {
                            self.logger.debug("Processed pending DM reaction \(pending.parsed.emoji)")
                        }
                    }
                }

                // Update contact's last message date
                if let contactID = contact?.id {
                    try await services.dataStore.updateContactLastMessage(contactID: contactID, date: Date())
                }

                // Only increment unread count, post notification, and update badge for non-blocked contacts
                if let contactID = contact?.id, contact?.isBlocked != true {
                    try await self.updateDMUnreadsAndNotify(
                        messageDTO: messageDTO,
                        contactID: contactID,
                        contact: contact,
                        messageText: message.text,
                        hasSelfMention: hasSelfMention,
                        services: services
                    )
                }

                // Notify UI via SyncCoordinator
                await self.notifyConversationsChanged()

                // Forward to MessageEventStream consumers for real-time chat updates
                if let contact {
                    await self.onDirectMessageReceived?(messageDTO, contact)
                }
            } catch {
                self.logger.error("Failed to save contact message: \(error)")
            }
        }
    }

    // MARK: - Channel Message Handler

    private func wireChannelMessageHandler(services: ServiceContainer, radioID: UUID, selfNodeName: String) async {
        await services.messagePollingService.setChannelMessageHandler { [weak self] message, channel, context in
            guard let self else { return }

            // Parse "NodeName: text" format for sender name
            let (senderNodeName, messageText) = Self.parseChannelMessage(message.text)

            let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

            // Correct invalid timestamps (sender clock wrong)
            let receiveTime = Date()
            let (finalTimestamp, timestampCorrected) = Self.correctTimestampIfNeeded(timestamp, receiveTime: receiveTime)
            if timestampCorrected {
                self.logger.debug("Corrected invalid channel message timestamp from \(Date(timeIntervalSince1970: TimeInterval(timestamp))) to \(receiveTime)")
            }

            let sortDate = Self.sortDate(for: context, receiveTime: receiveTime)

            // Look up path data from RxLogEntry using sender timestamp (stored during decryption)
            let rxResult = await self.lookupRxLogEntry(
                services: services,
                channelIndex: message.channelIndex,
                senderTimestamp: timestamp,
                senderPublicKeyPrefix: nil,
                defaultPathLength: message.pathLength
            )

            // Use content-based key for dedup (stable across retry attempts).
            let deduplicationKey = Self.fallbackDeduplicationKey(
                contactID: nil, channelIndex: message.channelIndex,
                senderNodeName: senderNodeName, timestamp: timestamp, content: messageText
            )

            // Check for self-mention before creating DTO
            // Filter out messages where user mentions themselves
            let hasSelfMention = !selfNodeName.isEmpty &&
                senderNodeName != selfNodeName &&
                MentionUtilities.containsSelfMention(in: messageText, selfName: selfNodeName)

            let messageDTO = MessageDTO(
                id: UUID(),
                radioID: radioID,
                contactID: nil,
                channelIndex: message.channelIndex,
                text: messageText,
                timestamp: finalTimestamp,
                createdAt: receiveTime,
                sortDate: sortDate,
                direction: .incoming,
                status: .delivered,
                textType: TextType(rawValue: message.textType) ?? .plain,
                ackCode: nil,
                pathLength: rxResult.pathLength,
                snr: message.snr,
                pathNodes: rxResult.pathNodes,
                senderKeyPrefix: nil,
                senderNodeName: senderNodeName,
                isRead: false,
                replyToID: nil,
                roundTripTime: nil,
                heardRepeats: 0,
                retryAttempt: 0,
                maxRetryAttempts: 0,
                deduplicationKey: deduplicationKey,
                containsSelfMention: hasSelfMention,
                mentionSeen: false,
                timestampCorrected: timestampCorrected,
                senderTimestamp: timestampCorrected ? timestamp : nil,
                routeType: rxResult.routeType,
                regionScope: rxResult.regionScope
            )

            // Check for duplicate before saving
            do {
                if try await services.dataStore.isDuplicateMessage(deduplicationKey: deduplicationKey, radioID: radioID) {
                    self.logger.info("Skipping duplicate channel message")
                    return
                }
            } catch {
                self.logger.warning("Dedup check failed, proceeding with save: \(error)")
            }

            // Discard messages from blocked senders
            if await self.isBlockedSender(senderNodeName) {
                return
            }

            // Check if this is a reaction
            if await self.handleChannelReaction(
                text: messageText,
                channelIndex: message.channelIndex,
                senderNodeName: senderNodeName,
                selfNodeName: selfNodeName,
                receiveTime: receiveTime,
                radioID: radioID,
                services: services
            ) {
                return
            }

            do {
                try await services.dataStore.saveMessage(messageDTO)

                // Index message for reaction matching and process any pending reactions
                // Use original timestamp for indexing so pending reactions can match
                if let senderName = senderNodeName {
                    let pendingMatches = await services.reactionService.indexMessage(
                        id: messageDTO.id,
                        channelIndex: message.channelIndex,
                        senderName: senderName,
                        text: messageText,
                        timestamp: timestamp
                    )

                    // Process any pending reactions that now have their target
                    for pending in pendingMatches {
                        let reactionDTO = ReactionDTO(
                            messageID: messageDTO.id,
                            emoji: pending.parsed.emoji,
                            senderName: pending.senderNodeName,
                            messageHash: pending.parsed.messageHash,
                            rawText: pending.rawText,
                            channelIndex: pending.channelIndex,
                            radioID: pending.radioID
                        )
                        await self.persistReactionIfNew(reactionDTO, services: services)
                    }
                }

                // Update channel's last message date
                if let channelID = channel?.id {
                    try await services.dataStore.updateChannelLastMessage(channelID: channelID, date: Date())
                }

                // Only update unread count, badges, and notify UI for non-blocked senders
                if await !self.isBlockedSender(senderNodeName) {
                    try await self.updateChannelUnreadsAndNotify(
                        messageDTO: messageDTO,
                        channel: channel,
                        channelIndex: message.channelIndex,
                        senderNodeName: senderNodeName,
                        messageText: messageText,
                        timestamp: timestamp,
                        hasSelfMention: hasSelfMention,
                        radioID: radioID,
                        services: services
                    )
                }

                // Notify conversation list of changes
                await self.notifyConversationsChanged()
            } catch {
                self.logger.error("Failed to save channel message: \(error)")
            }
        }
    }

    // MARK: - Signed Message Handler

    private func wireSignedMessageHandler(services: ServiceContainer) async {
        await services.messagePollingService.setSignedMessageHandler { [weak self] message, _ in
            guard let self else { return }

            // For signed room messages, the signature contains the 4-byte author key prefix
            guard let authorPrefix = message.signature?.prefix(4), authorPrefix.count == 4 else {
                self.logger.warning("Dropping signed message: missing or invalid author prefix")
                return
            }

            let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

            do {
                let savedMessage = try await services.roomServerService.handleIncomingMessage(
                    senderPublicKeyPrefix: message.senderPublicKeyPrefix,
                    timestamp: timestamp,
                    authorPrefix: Data(authorPrefix),
                    text: message.text
                )

                // If message was saved (not a duplicate), notify UI and post notification
                if let savedMessage {
                    // Fetch session for room name and mute status
                    let session = try? await services.dataStore.fetchRemoteNodeSession(id: savedMessage.sessionID)

                    // Post notification for room message
                    await services.notificationService.postRoomMessageNotification(
                        roomName: session?.name ?? "Room",
                        senderName: savedMessage.authorName,
                        messageText: savedMessage.text,
                        messageID: savedMessage.id,
                        notificationLevel: session?.notificationLevel ?? .all
                    )
                    await services.notificationService.updateBadgeCount()

                    await self.notifyConversationsChanged()
                    await self.onRoomMessageReceived?(savedMessage)
                }
            } catch {
                self.logger.error("Failed to handle room message: \(error)")
            }
        }
    }

    // MARK: - CLI Message Handler

    private func wireCLIMessageHandler(services: ServiceContainer) async {
        await services.messagePollingService.setCLIMessageHandler { [weak self] message, contact in
            guard let self else { return }

            if let contact {
                if contact.type == .room {
                    await services.roomAdminService.invokeCLIHandler(message, fromContact: contact)
                } else {
                    await services.repeaterAdminService.invokeCLIHandler(message, fromContact: contact)
                }
            } else {
                self.logger.warning("Dropping CLI response: no contact found for sender")
            }
        }
    }

    // MARK: - Discovery Handler Wiring

    func wireDiscoveryHandlers(services: ServiceContainer, radioID: UUID) async {
        logger.info("Wiring discovery handlers for device \(radioID)")

        // New contact discovered handler (manual-add mode)
        // Posts notification when a new contact is discovered via advertisement
        await services.advertisementService.setNewContactDiscoveredHandler { [weak self] contactName, contactID, contactType in
            guard let self else { return }

            await services.notificationService.postNewContactNotification(
                contactName: contactName,
                contactID: contactID,
                contactType: contactType
            )

            await self.notifyContactsChanged()
        }

        // Contact sync request handler (auto-add mode)
        // AdvertisementService fetches and saves the new contact directly,
        // this handler just triggers UI refresh
        await services.advertisementService.setContactSyncRequestHandler { [weak self] _ in
            guard let self else { return }
            await self.notifyContactsChanged()
        }

        logger.info("Discovery handlers wired successfully")
    }

    // MARK: - Message Handler Helpers

    private struct RxLogLookupResult {
        let pathNodes: Data?
        let pathLength: UInt8
        let packetHash: String?
        let routeType: RouteType?
        let regionScope: String?
    }

    /// Looks up path data from an RxLogEntry to correlate with an incoming message.
    private func lookupRxLogEntry(
        services: ServiceContainer,
        channelIndex: UInt8?,
        senderTimestamp: UInt32,
        senderPublicKeyPrefix: Data?,
        defaultPathLength: UInt8
    ) async -> RxLogLookupResult {
        if let channelIndex {
            logger.debug("Looking up RxLogEntry for channel \(channelIndex) with senderTimestamp: \(senderTimestamp)")
        }

        do {
            if let rxEntry = try await services.dataStore.findRxLogEntry(
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
                if let rxEntry = try await services.dataStore.findRxLogEntryBySenderPrefix(
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

    /// Handles an incoming DM reaction by looking up the target message and persisting the reaction.
    ///
    /// - Returns: `true` if the message was consumed as a reaction (caller should return early).
    private func handleDMReaction(
        text: String,
        contact: ContactDTO,
        radioID: UUID,
        services: ServiceContainer
    ) async -> Bool {
        // Try meshcore-open v3 format
        if let mcoReaction = MeshCoreOpenReactionParser.parse(text) {
            return await handleMCODMReaction(
                mcoReaction,
                rawText: text,
                contact: contact,
                radioID: radioID,
                services: services
            )
        }

        // Try meshcore-open v1 format
        if let v1Reaction = MeshCoreOpenReactionParser.parseV1(text) {
            return await handleMCOV1DMReaction(
                v1Reaction,
                rawText: text,
                contact: contact,
                radioID: radioID,
                services: services
            )
        }

        guard let parsed = ReactionParser.parseDM(text) else { return false }

        // Try to find target in cache first
        if let targetMessageID = await services.reactionService.findDMTargetMessage(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
                logger.debug("Saved DM reaction \(parsed.emoji) to message \(targetMessageID)")
            }

            return true
        }

        // Try persistence fallback
        let timestampWindow = reactionTimestampWindow()

        if let targetMessage = try? await services.dataStore.findDMMessageForReaction(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
                logger.debug("Saved DM reaction \(parsed.emoji) to message \(targetMessage.id) (from DB)")
            }

            return true
        }

        // Queue as pending if target not found
        await services.reactionService.queuePendingDMReaction(
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
    private func handleChannelReaction(
        text: String,
        channelIndex: UInt8,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Date,
        radioID: UUID,
        services: ServiceContainer
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
                services: services
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
                services: services
            )
        }

        guard let parsed = services.reactionService.tryProcessAsReaction(text) else { return false }

        let senderName = senderNodeName ?? "Unknown"

        if let targetMessageID = await services.reactionService.findTargetMessage(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
                logger.debug("Saved reaction \(parsed.emoji) to message \(targetMessageID)")
            }

            return true
        }

        let timestampWindow = reactionTimestampWindow(at: receiveTime)

        logger.debug("DB lookup: selfNodeName='\(selfNodeName)', targetSender=\(parsed.targetSender), hash=\(parsed.messageHash)")

        if let targetMessage = try? await services.dataStore.findChannelMessageForReaction(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
                let targetSenderName: String?
                if targetMessage.direction == .outgoing {
                    targetSenderName = selfNodeName.isEmpty ? nil : selfNodeName
                } else {
                    targetSenderName = targetMessage.senderNodeName
                }

                if let targetSenderName {
                    // Index for future reactions (pending matches not needed here since
                    // message exists in DB, so pending reactions would also match via DB fallback)
                    _ = await services.reactionService.indexMessage(
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
        await services.reactionService.queuePendingReaction(
            parsed: parsed,
            channelIndex: channelIndex,
            senderNodeName: senderName,
            rawText: text,
            radioID: radioID
        )
        return true
    }

    /// Increments unread counts and posts a notification for a direct message.
    private func updateDMUnreadsAndNotify(
        messageDTO: MessageDTO,
        contactID: UUID,
        contact: ContactDTO?,
        messageText: String,
        hasSelfMention: Bool,
        services: ServiceContainer
    ) async throws {
        // Only increment unread if user is NOT currently viewing this contact's chat
        let isViewingContact = await services.notificationService.activeContactID == contactID
        if !isViewingContact {
            try await services.dataStore.incrementUnreadCount(contactID: contactID)

            // Increment unread mention count if message contains self-mention
            if hasSelfMention {
                try await services.dataStore.incrementUnreadMentionCount(contactID: contactID)
            }
        }

        await services.notificationService.postDirectMessageNotification(
            from: contact?.displayName ?? "Unknown",
            contactID: contactID,
            messageText: messageText,
            messageID: messageDTO.id,
            isMuted: contact?.isMuted ?? false
        )
        await services.notificationService.updateBadgeCount()
    }

    /// Increments unread counts, posts a notification, and notifies real-time listeners for a channel message.
    private func updateChannelUnreadsAndNotify(
        messageDTO: MessageDTO,
        channel: ChannelDTO?,
        channelIndex: UInt8,
        senderNodeName: String?,
        messageText: String,
        timestamp: UInt32,
        hasSelfMention: Bool,
        radioID: UUID,
        services: ServiceContainer
    ) async throws {
        if let channelID = channel?.id {
            // Only increment unread if user is NOT currently viewing this channel
            let activeIndex = await services.notificationService.activeChannelIndex
            let activeRadioID = await services.notificationService.activeChannelRadioID
            let isViewingChannel = activeIndex == channel?.index && activeRadioID == channel?.radioID
            if !isViewingChannel {
                try await services.dataStore.incrementChannelUnreadCount(channelID: channelID)

                // Increment unread mention count if message contains self-mention
                if hasSelfMention {
                    try await services.dataStore.incrementChannelUnreadMentionCount(channelID: channelID)
                }
            }
        }
        if Self.shouldPostChannelNotification(forResolvedChannel: channel) {
            await services.notificationService.postChannelMessageNotification(
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
        await services.notificationService.updateBadgeCount()

        // Forward to MessageEventStream consumers for real-time chat updates
        await onChannelMessageReceived?(messageDTO, channelIndex)
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
        services: ServiceContainer
    ) async -> Bool {
        let timestampWindow = reactionTimestampWindow()

        guard let candidates = try? await services.dataStore.fetchDMMessageCandidates(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
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
        services: ServiceContainer
    ) async -> Bool {
        let senderName = senderNodeName ?? "Unknown"
        let timestampWindow = reactionTimestampWindow(at: receiveTime)

        guard let candidates = try? await services.dataStore.fetchChannelMessageCandidates(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
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
        services: ServiceContainer
    ) async -> Bool {
        let timestampWindow = reactionTimestampWindow(
            anchor: v1Reaction.timestampSeconds
        )

        guard let candidates = try? await services.dataStore.fetchDMMessageCandidates(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
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
        services: ServiceContainer
    ) async -> Bool {
        let senderName = senderNodeName ?? "Unknown"
        let timestampWindow = reactionTimestampWindow(
            anchor: v1Reaction.timestampSeconds
        )

        guard let candidates = try? await services.dataStore.fetchChannelMessageCandidates(
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
            if await persistReactionIfNew(reactionDTO, services: services) {
                logger.debug("Saved MCO v1 channel reaction \(v1Reaction.emoji) to message \(candidate.id)")
            }
            return true
        }

        logger.debug("MCO v1 channel reaction \(v1Reaction.emoji): no hash match found")
        return true
    }

    nonisolated static func fallbackDeduplicationKey(
        contactID: UUID?,
        channelIndex: UInt8?,
        senderNodeName: String?,
        timestamp: UInt32,
        content: String
    ) -> String {
        DeduplicationKey.contentBased(
            contactID: contactID,
            channelIndex: channelIndex,
            senderNodeName: senderNodeName,
            timestamp: timestamp,
            content: content
        )
    }

    nonisolated static func parseChannelMessage(_ text: String) -> (senderNodeName: String?, messageText: String) {
        let parts = text.split(separator: ":", maxSplits: 1)
        if parts.count > 1 {
            let senderName = String(parts[0]).trimmingCharacters(in: .whitespaces)
            let messageText = String(parts[1]).trimmingCharacters(in: .whitespaces)
            return (senderName, messageText)
        }
        return (nil, text)
    }

    /// A received channel message only warrants a user notification when it resolves to a known
    /// local channel. The radio can report a message on a slot the app has no `Channel` for: the
    /// firmware decrypts zero-key group traffic on an unconfigured slot and attributes it to the
    /// first empty slot. Such messages have no openable chat, so they are logged as unresolved
    /// but never surfaced as a notification.
    nonisolated static func shouldPostChannelNotification(forResolvedChannel channel: ChannelDTO?) -> Bool {
        channel != nil
    }
}
