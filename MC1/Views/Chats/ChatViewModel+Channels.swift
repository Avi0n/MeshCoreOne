import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Channel Messages

    /// Load messages for a channel
    func loadChannelMessages(for channel: ChannelDTO) async {
        logger.info("loadChannelMessages: start channel=\(channel.index) radioID=\(channel.radioID)")

        guard let dataStore else {
            logger.info("loadChannelMessages: dataStore is nil, returning early")
            return
        }

        // Clear preview state only when switching to a different conversation
        if currentChannel?.id != channel.id {
            clearPreviewState()
            newMessagesDividerMessageID = nil
            dividerComputed = false
            lastSetRegionScope = .unknown
        }

        currentChannel = channel
        currentContact = nil

        // Track active channel for notification suppression
        notificationService?.activeContactID = nil
        notificationService?.activeChannelIndex = channel.index
        notificationService?.activeChannelRadioID = channel.radioID

        // Set flood scope on device when channel or region changes
        if lastSetRegionScope == .unknown || lastSetRegionScope != .set(channel.regionScope) {
            if let session = appState?.services?.session {
                let scope: FloodScope = channel.regionScope.map { .region($0) } ?? .disabled
                do {
                    try await session.setFloodScope(scope)
                    lastSetRegionScope = .set(channel.regionScope)
                } catch {
                    logger.error("Failed to set flood scope: \(error.localizedDescription)")
                }
            }
        }

        logger.info("loadChannelMessages: setting isLoading=true, current messages.count=\(self.messages.count)")
        isLoading = true
        errorMessage = nil

        // Reset pagination state for new conversation
        hasMoreMessages = true
        isLoadingOlder = false
        totalFetchedCount = 0

        do {
            var fetchedMessages = try await dataStore.fetchMessages(radioID: channel.radioID, channelIndex: channel.index, limit: pageSize, offset: 0)
            let unfilteredCount = fetchedMessages.count
            totalFetchedCount = unfilteredCount
            logger.info("loadChannelMessages: fetched \(unfilteredCount) messages")

            // Compute divider position before filtering, using unfiltered array
            computeDividerPosition(from: fetchedMessages, unreadCount: channel.unreadCount)

            // Hide sent reaction messages (unless failed)
            fetchedMessages = filterOutgoingReactionMessages(fetchedMessages, isDM: false)

            // Use unfiltered count to determine if more messages exist
            hasMoreMessages = unfilteredCount == pageSize
            messages = fetchedMessages

            buildChannelSenders(radioID: channel.radioID)
            buildDisplayItems()

            // Index loaded messages for reaction matching and process any pending reactions
            if let reactionService = appState?.services?.reactionService {
                let localNodeName = appState?.connectedDevice?.nodeName
                let radioID = appState?.connectedDevice?.radioID ?? UUID()
                for message in fetchedMessages {
                    let senderName: String?
                    if message.isOutgoing {
                        senderName = localNodeName
                    } else {
                        senderName = message.senderNodeName
                    }
                    if let senderName {
                        let pendingMatches = await reactionService.indexMessage(
                            id: message.id,
                            channelIndex: channel.index,
                            senderName: senderName,
                            text: message.text,
                            timestamp: message.timestamp
                        )

                        // Process any pending reactions that now have their target
                        for pending in pendingMatches {
                            let exists = try? await dataStore.reactionExists(
                                messageID: message.id,
                                senderName: pending.senderNodeName,
                                emoji: pending.parsed.emoji
                            )

                            if exists != true {
                                let reactionDTO = ReactionDTO(
                                    messageID: message.id,
                                    emoji: pending.parsed.emoji,
                                    senderName: pending.senderNodeName,
                                    messageHash: pending.parsed.messageHash,
                                    rawText: pending.rawText,
                                    channelIndex: pending.channelIndex,
                                    radioID: radioID
                                )
                                if let result = await reactionService.persistReactionAndUpdateSummary(
                                    reactionDTO,
                                    using: dataStore
                                ) {
                                    updateReactionSummary(for: result.messageID, summary: result.summary)
                                }
                            }
                        }
                    }
                }
            }

            // Clear unread count and mention badge, then notify UI to refresh chat list
            try await dataStore.clearChannelUnreadCount(channelID: channel.id)
            try await dataStore.clearChannelUnreadMentionCount(channelID: channel.id)
            syncCoordinator?.notifyConversationsChanged()

            // Update app badge
            await notificationService?.updateBadgeCount()
        } catch {
            logger.info("loadChannelMessages: error - \(error.localizedDescription)")
            errorMessage = error.localizedDescription
        }

        logger.info("loadChannelMessages: done, isLoading=false, messages.count=\(self.messages.count)")
        hasLoadedOnce = true
        isLoading = false
    }

    // MARK: - Channel Actions

    /// Send a channel message optimistically — shows immediately, sends in background.
    func sendChannelMessage(text: String) async {
        guard let channel = currentChannel,
              let messageService,
              !text.isEmpty else {
            return
        }

        errorMessage = nil

        do {
            let message = try await messageService.createPendingChannelMessage(
                text: text,
                channelIndex: channel.index,
                radioID: channel.radioID
            )
            appendMessageIfNew(message)

            channelSendQueue.append(QueuedChannelMessage(messageID: message.id))

            if !isProcessingChannelQueue {
                channelQueueTask?.cancel()
                channelQueueTask = Task { await processChannelQueue() }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Process queued channel messages serially
    private func processChannelQueue() async {
        guard let messageService else { return }

        isProcessingChannelQueue = true
        defer { isProcessingChannelQueue = false }

        let channel = currentChannel

        repeat {
            while !channelSendQueue.isEmpty {
                let queued = channelSendQueue.removeFirst()

                do {
                    try await messageService.sendPendingChannelMessage(messageID: queued.messageID)

                    // Index for reaction matching after successful send
                    if let message = messagesByID[queued.messageID],
                       let channelIndex = message.channelIndex,
                       let reactionService = appState?.services?.reactionService,
                       let localNodeName = appState?.connectedDevice?.nodeName {
                        _ = await reactionService.indexMessage(
                            id: queued.messageID,
                            channelIndex: channelIndex,
                            senderName: localNodeName,
                            text: message.text,
                            timestamp: message.timestamp
                        )
                    }
                } catch is CancellationError {
                    channelSendQueue.insert(queued, at: 0)
                    return
                } catch {
                    errorMessage = error.localizedDescription
                }
            }

            // Reload after queue drains — syncs statuses and conversation list
            if let channel {
                await loadChannelMessages(for: channel)
                await loadChannels(radioID: channel.radioID)
            }
        } while !channelSendQueue.isEmpty
    }

    /// Retry sending a failed channel message in place.
    func retryChannelMessage(_ message: MessageDTO) async {
        guard let messageService,
              let channel = currentChannel,
              message.channelIndex != nil,
              !isRetryingChannelMessage else { return }

        isRetryingChannelMessage = true
        defer { isRetryingChannelMessage = false }

        try? await dataStore?.updateMessageStatus(id: message.id, status: .pending)
        await loadChannelMessages(for: channel)

        do {
            try await messageService.sendPendingChannelMessage(messageID: message.id)
        } catch is CancellationError {
            // Fall through to reload so UI reflects current state
        } catch {
            errorMessage = error.localizedDescription
            showRetryError = true
        }

        await loadChannelMessages(for: channel)
        await loadChannels(radioID: channel.radioID)
    }

    // MARK: - In-Place Updates

    /// Update heard repeat count for a message in place without a full reload.
    func updateHeardRepeats(for messageID: UUID, count: Int) {
        updateMessage(id: messageID) { $0.heardRepeats = count }
    }

    // MARK: - Channel Sender Tracking

    /// Build synthetic contacts from channel message senders not in contacts.
    /// Called after loading channel messages to populate mention picker.
    /// Builds into local collections first to avoid multiple @Observable updates.
    private func buildChannelSenders(radioID: UUID) {
        var localNames: Set<String> = []
        var localSenders: [ContactDTO] = []
        var localOrder: [String: UInt32] = [:]

        for message in messages {
            if let name = message.senderNodeName {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                guard !trimmed.isEmpty, trimmed.count <= 128 else { continue }

                // Track latest timestamp for all senders (contacts and non-contacts)
                localOrder[trimmed] = max(message.timestamp, localOrder[trimmed] ?? 0)

                // Build synthetic contacts only for non-contact senders
                guard !contactNameSet.contains(trimmed),
                      !localNames.contains(trimmed) else { continue }

                localNames.insert(trimmed)
                localSenders.append(makeSyntheticContact(name: trimmed, radioID: radioID))
            }
        }

        // Assign once to minimize observation updates
        channelSenderNames = localNames
        channelSenders = localSenders
        channelSenderOrder = localOrder

        logger.info("Built \(self.channelSenders.count) synthetic contacts from channel senders")
    }

    /// Add a channel sender as a synthetic contact if not already tracked.
    /// Used for incremental additions when new messages arrive.
    func addChannelSenderIfNew(_ name: String, radioID: UUID) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              trimmed.count <= 128,
              !contactNameSet.contains(trimmed),
              !channelSenderNames.contains(trimmed) else { return }

        channelSenderNames.insert(trimmed)
        channelSenders.append(makeSyntheticContact(name: trimmed, radioID: radioID))
    }

    /// Create a synthetic ContactDTO for a channel sender not in contacts.
    private func makeSyntheticContact(name: String, radioID: UUID) -> ContactDTO {
        ContactDTO(
            id: name.stableUUID,
            radioID: radioID,
            publicKey: Data(),
            name: name,
            typeRawValue: ContactType.chat.rawValue,
            flags: 0,
            outPathLength: 0xFF,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0.0,
            longitude: 0.0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }
}
