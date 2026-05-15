import SwiftUI
import MC1Services

extension ChatViewModel {

    // MARK: - Notification Level

    /// Sets notification level for a conversation with optimistic UI update
    func setNotificationLevel(_ conversation: Conversation, level: NotificationLevel) async {
        guard appState?.connectionState == .ready else { return }
        let originalLevel = conversation.notificationLevel

        // Optimistic UI update
        updateConversationNotificationLevel(conversation, level: level)

        do {
            switch conversation {
            case .direct(let contact):
                // Contacts still use boolean muted
                try await dataStore?.setContactMuted(contact.id, isMuted: level == .muted)
            case .channel(let channel):
                try await dataStore?.setChannelNotificationLevel(channel.id, level: level)
            case .room(let session):
                try await dataStore?.setSessionNotificationLevel(session.id, level: level)
            }
            await notificationService?.updateBadgeCount()
        } catch {
            // Rollback on failure
            updateConversationNotificationLevel(conversation, level: originalLevel)
            logger.error("Failed to set notification level: \(error)")
        }
    }

    /// Toggles between muted and all (for swipe action)
    func toggleMute(_ conversation: Conversation) async {
        let newLevel: NotificationLevel = conversation.isMuted ? .all : .muted
        await setNotificationLevel(conversation, level: newLevel)
    }

    /// Updates the notification level in the local conversations array
    private func updateConversationNotificationLevel(_ conversation: Conversation, level: NotificationLevel) {
        invalidateConversationCache()
        switch conversation {
        case .direct(let contact):
            if let index = conversations.firstIndex(where: { $0.id == contact.id }) {
                conversations[index] = conversations[index].with(isMuted: level == .muted)
            }
        case .channel(let channel):
            if let index = channels.firstIndex(where: { $0.id == channel.id }) {
                channels[index] = channels[index].with(notificationLevel: level)
            }
        case .room(let session):
            if let index = roomSessions.firstIndex(where: { $0.id == session.id }) {
                roomSessions[index] = roomSessions[index].with(notificationLevel: level)
            }
        }
    }

    // MARK: - Favorite

    /// Sets favorite state for a conversation with optimistic UI update
    func setFavorite(_ conversation: Conversation, isFavorite: Bool) async {
        guard appState?.connectionState == .ready else { return }
        guard conversation.isFavorite != isFavorite else { return }

        // Reuse existing toggle logic
        await toggleFavorite(conversation)
    }

    /// Toggles favorite state for a conversation.
    ///
    /// For direct messages (contacts), this pushes the change to the device and waits
    /// for confirmation before updating the UI. For channels and rooms (app-only),
    /// this uses optimistic updates.
    ///
    /// - Parameters:
    ///   - conversation: The conversation to toggle
    ///   - disableAnimation: When true, disables SwiftUI List animations to prevent
    ///     conflicts with swipe action dismissal animations
    func toggleFavorite(_ conversation: Conversation, disableAnimation: Bool = false) async {
        guard appState?.connectionState == .ready else { return }
        let originalState = conversation.isFavorite
        let newState = !originalState

        switch conversation {
        case .direct(let contact):
            // Contacts sync with device - wait for confirmation
            togglingFavoriteID = contact.id
            defer { togglingFavoriteID = nil }

            do {
                try await contactService?.setContactFavorite(contact.id, isFavorite: newState)
                // Device confirmed - update local UI
                applyFavoriteUpdate(conversation, isFavorite: newState, disableAnimation: disableAnimation)
            } catch {
                logger.error("Failed to toggle contact favorite: \(error)")
            }

        case .channel(let channel):
            // Channels are app-only - optimistic update
            applyFavoriteUpdate(conversation, isFavorite: newState, disableAnimation: disableAnimation)

            do {
                try await dataStore?.setChannelFavorite(channel.id, isFavorite: newState)
            } catch {
                // Rollback on failure
                applyFavoriteUpdate(conversation, isFavorite: originalState, disableAnimation: disableAnimation)
                logger.error("Failed to toggle channel favorite: \(error)")
            }

        case .room(let session):
            // Rooms are app-only - optimistic update
            applyFavoriteUpdate(conversation, isFavorite: newState, disableAnimation: disableAnimation)

            do {
                try await dataStore?.setSessionFavorite(session.id, isFavorite: newState)
            } catch {
                // Rollback on failure
                applyFavoriteUpdate(conversation, isFavorite: originalState, disableAnimation: disableAnimation)
                logger.error("Failed to toggle room favorite: \(error)")
            }
        }
    }

    private func applyFavoriteUpdate(_ conversation: Conversation, isFavorite: Bool, disableAnimation: Bool) {
        if disableAnimation {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                updateConversationFavoriteState(conversation, isFavorite: isFavorite)
            }
        } else {
            updateConversationFavoriteState(conversation, isFavorite: isFavorite)
        }
    }

    /// Updates the favorite state in the local conversations array
    private func updateConversationFavoriteState(_ conversation: Conversation, isFavorite: Bool) {
        invalidateConversationCache()
        switch conversation {
        case .direct(let contact):
            if let index = conversations.firstIndex(where: { $0.id == contact.id }) {
                conversations[index] = conversations[index].with(isFavorite: isFavorite)
            }
        case .channel(let channel):
            if let index = channels.firstIndex(where: { $0.id == channel.id }) {
                channels[index] = channels[index].with(isFavorite: isFavorite)
            }
        case .room(let session):
            if let index = roomSessions.firstIndex(where: { $0.id == session.id }) {
                roomSessions[index] = roomSessions[index].with(isFavorite: isFavorite)
            }
        }
    }

    // MARK: - Conversation List

    /// Clears all conversation data from the view model.
    /// Called when the device is forgotten or removed so the list doesn't show stale entries.
    func clearConversations() {
        conversations = []
        channels = []
        roomSessions = []
        allContacts = []
        channelSenders = []
        channelSenderNames = []
        channelSenderOrder = [:]
        contactNameSet = []
        lastMessageCache = [:]
        invalidateConversationCache()
    }

    /// Removes a conversation from local arrays for optimistic UI update.
    func removeConversation(_ conversation: Conversation) {
        invalidateConversationCache()
        switch conversation {
        case .direct(let contact):
            conversations = conversations.filter { $0.id != contact.id }
        case .channel(let channel):
            channels = channels.filter { $0.id != channel.id }
        case .room(let session):
            roomSessions = roomSessions.filter { $0.id != session.id }
        }
    }

    /// Load conversations for a device
    func loadConversations(radioID: UUID) async {
        guard let dataStore else { return }

        isLoading = true
        errorBannerMessage = nil

        do {
            conversations = try await dataStore.fetchConversations(radioID: radioID)
            invalidateConversationCache()
        } catch {
            errorBannerMessage = L10n.Chats.Chats.Error.loadConversationsFailed
            logger.error("loadConversations failed: \(error.localizedDescription)")
        }

        hasLoadedOnce = true
        isLoading = false
    }

    /// Load all contacts for mention autocomplete
    func loadAllContacts(radioID: UUID) async {
        guard let dataStore else { return }

        do {
            allContacts = try await dataStore.fetchContacts(radioID: radioID)
            contactNameSet = Set(allContacts.map(\.name))
        } catch {
            logger.warning("Failed to load contacts for mentions: \(error.localizedDescription)")
        }
    }

    /// Load channels for a device
    func loadChannels(radioID: UUID) async {
        guard let dataStore else { return }

        do {
            channels = try await dataStore.fetchChannels(radioID: radioID)
            invalidateConversationCache()
        } catch {
            // Silently handle - channels are optional
        }
    }

    /// Load all conversations (contacts + channels + rooms) for unified display.
    /// Fetches into local variables first, then applies all mutations in a single
    /// synchronous block so SwiftUI sees one consistent state update.
    func loadAllConversations(radioID: UUID) async {
        guard let dataStore else { return }

        isLoading = true
        errorBannerMessage = nil

        // Fetch into locals — no @Observable mutations between awaits.
        var fetchedConversations: [ContactDTO]?
        var fetchedChannels: [ChannelDTO]?
        var fetchedRoomSessions: [RemoteNodeSessionDTO]?

        do {
            fetchedConversations = try await dataStore.fetchConversations(radioID: radioID)
        } catch {
            errorBannerMessage = L10n.Chats.Chats.Error.loadConversationsFailed
            logger.error("loadAllConversations failed: \(error.localizedDescription)")
        }

        fetchedChannels = try? await dataStore.fetchChannels(radioID: radioID)
        fetchedRoomSessions = (try? await dataStore.fetchRemoteNodeSessions(radioID: radioID))?
            .filter { $0.isRoom }

        // Apply all changes in a single synchronous block so SwiftUI sees one
        // consistent state instead of three intermediate partial states.
        if let fetchedConversations { conversations = fetchedConversations }
        if let fetchedChannels { channels = fetchedChannels }
        if let fetchedRoomSessions { roomSessions = fetchedRoomSessions }
        invalidateConversationCache()

        hasLoadedOnce = true
        isLoading = false

        await loadLastMessagePreviews()
    }

    // MARK: - Messages

    /// Load messages for a contact
    func loadMessages(for contact: ContactDTO) async {
        guard let dataStore else { return }

        // Clear preview state only when switching to a different conversation
        if currentContact?.id != contact.id {
            clearPreviewState()
            newMessagesDividerMessageID = nil
            dividerComputed = false
        }

        currentContact = contact

        // Track active conversation for notification suppression
        notificationService?.activeContactID = contact.id

        isLoading = true
        // Dual-reset: this function is shared between passive load and user-initiated
        // retry paths, so both surfaces must clear at entry to avoid stale state.
        errorMessage = nil
        errorBannerMessage = nil

        // Reset pagination state for new conversation
        coordinator?.updateRenderState { $0.with(hasMoreMessages: true, isLoadingOlder: false, totalFetchedCount: 0) }

        do {
            var fetchedMessages = try await dataStore.fetchMessages(contactID: contact.id, limit: ChatCoordinator.pageSize, offset: 0)
            let unfilteredCount = fetchedMessages.count
            coordinator?.updateRenderState { $0.with(totalFetchedCount: unfilteredCount) }

            // Compute divider position before filtering, using unfiltered array
            computeDividerPosition(from: fetchedMessages, unreadCount: contact.unreadCount)

            // Hide sent reaction messages (unless failed)
            fetchedMessages = filterOutgoingReactionMessages(fetchedMessages, isDM: true)

            coordinator?.replaceAll(fetchedMessages)
            coordinator?.updateRenderState { $0.with(hasMoreMessages: unfilteredCount == ChatCoordinator.pageSize) }

            buildItems()

            // Index loaded messages for reaction matching and process any pending reactions
            if let reactionService = appState?.services?.reactionService {
                for message in fetchedMessages {
                    let pendingMatches = await reactionService.indexDMMessage(
                        id: message.id,
                        contactID: contact.id,
                        text: message.text,
                        timestamp: message.reactionTimestamp
                    )

                    // Process any pending reactions that now have their target
                    for pending in pendingMatches {
                        let exists = try? await dataStore.reactionExists(
                            messageID: message.id,
                            senderName: pending.senderName,
                            emoji: pending.parsed.emoji
                        )

                        if exists != true {
                            let reactionDTO = ReactionDTO(
                                messageID: message.id,
                                emoji: pending.parsed.emoji,
                                senderName: pending.senderName,
                                messageHash: pending.parsed.messageHash,
                                rawText: pending.rawText,
                                contactID: contact.id,
                                radioID: contact.radioID
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

            // Clear unread count and mention badge, then notify UI to refresh chat list
            try await dataStore.clearUnreadCount(contactID: contact.id)
            try await dataStore.clearUnreadMentionCount(contactID: contact.id)
            syncCoordinator?.notifyConversationsChanged()

            // Update app badge
            await notificationService?.updateBadgeCount()
        } catch {
            errorMessage = error.localizedDescription
        }

        hasLoadedOnce = true
        isLoading = false
    }

    /// Load any saved draft for the current contact
    /// Drafts are consumed (removed) after loading to prevent re-display
    /// If no draft exists, this method does nothing
    func loadDraftIfExists() {
        guard let contact = currentContact,
              let notificationService,
              let draft = notificationService.consumeDraft(for: contact.id) else {
            return
        }
        composingText = draft
    }

    /// Send a message to the current contact
    /// This is non-blocking - message is created and shown immediately, sent in background
    func sendMessage(text: String) async {
        guard let contact = currentContact,
              let messageService,
              !text.isEmpty else {
            return
        }

        errorMessage = nil

        let message: MessageDTO
        do {
            message = try await messageService.createPendingMessage(text: text, to: contact)
            appendMessageIfNew(message)
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contact.id)
        do {
            try await enqueueDM(envelope)
        } catch {
            logger.error("enqueueDM failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
            _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: message.id, status: .failed)
            coordinator?.applyStatusUpdate(messageID: message.id, status: .failed)
            sendErrorMessage = Self.copyForEnqueueFailure(error)
        }
    }

    /// Refresh messages for current contact
    func refreshMessages() async {
        guard let contact = currentContact else { return }
        await loadMessages(for: contact)
    }

    // MARK: - Message Previews

    /// Get the cached last-message preview text for a conversation, keyed by its id.
    func lastMessagePreview(id: UUID) -> String? {
        lastMessageCache[id]?.text
    }

    /// Load last message previews for all conversations.
    /// Uses batch fetch methods to minimize actor hops (2 hops instead of N).
    func loadLastMessagePreviews() async {
        guard let dataStore else { return }

        // Batch fetch contact message previews (single actor hop)
        if !conversations.isEmpty {
            do {
                let contactMessages = try await dataStore.fetchLastMessages(contactIDs: conversations.map(\.id), limit: 10)
                for contact in conversations {
                    guard let messages = contactMessages[contact.id] else { continue }

                    // Find the last non-reaction message (skip outgoing reactions unless failed)
                    let lastMessage = messages.last { message in
                        guard message.direction == .outgoing,
                              ReactionParser.parseDM(message.text) != nil else {
                            return true
                        }
                        return message.status == .failed
                    }

                    if let lastMessage {
                        lastMessageCache[contact.id] = lastMessage
                    }
                }
            } catch {
                logger.warning("Failed to load contact message previews: \(error)")
            }
        }

        // Batch fetch channel message previews (single actor hop)
        if !channels.isEmpty {
            do {
                let channelParams = channels.map { (radioID: $0.radioID, channelIndex: $0.index, id: $0.id) }
                let channelMessages = try await dataStore.fetchLastChannelMessages(channels: channelParams, limit: 20)
                for channel in channels {
                    guard let messages = channelMessages[channel.id] else { continue }

                    // Filter out outgoing reactions (keep failed ones visible)
                    let lastMessage = messages.last { message in
                        if message.direction == .outgoing,
                           ReactionParser.parse(message.text) != nil,
                           message.status != .failed {
                            return false
                        }
                        return true
                    }

                    if let lastMessage {
                        lastMessageCache[channel.id] = lastMessage
                    } else {
                        lastMessageCache.removeValue(forKey: channel.id)
                    }
                }
            } catch {
                logger.warning("Failed to load channel message previews: \(error)")
            }
        }
    }

}
