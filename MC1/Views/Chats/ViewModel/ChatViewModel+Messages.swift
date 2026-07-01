import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Notification Level

  /// Sets notification level for a conversation with optimistic UI update
  func setNotificationLevel(_ conversation: Conversation, level: NotificationLevel) async {
    guard connectionStateProvider() == .ready else { return }
    let originalLevel = conversation.notificationLevel

    // Capture once so the write and the badge update target the same container.
    let dataStore = dataStore
    let notificationService = notificationService

    // Optimistic UI update
    updateConversationNotificationLevel(conversation, level: level)

    do {
      switch conversation {
      case let .direct(contact):
        // Contacts still use boolean muted
        try await dataStore?.setContactMuted(contact.id, isMuted: level == .muted)
      case let .channel(channel):
        try await dataStore?.setChannelNotificationLevel(channel.id, level: level)
      case let .room(session):
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
    switch conversation {
    case let .direct(contact):
      if let index = conversations.firstIndex(where: { $0.id == contact.id }) {
        conversations[index] = conversations[index].with(isMuted: level == .muted)
      }
    case let .channel(channel):
      if let index = channels.firstIndex(where: { $0.id == channel.id }) {
        channels[index] = channels[index].with(notificationLevel: level)
      }
    case let .room(session):
      if let index = roomSessions.firstIndex(where: { $0.id == session.id }) {
        roomSessions[index] = roomSessions[index].with(notificationLevel: level)
      }
    }
    recomputeSnapshot()
  }

  // MARK: - Favorite

  /// Sets favorite state for a conversation with optimistic UI update
  func setFavorite(_ conversation: Conversation, isFavorite: Bool) async {
    guard connectionStateProvider() == .ready else { return }
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
    guard connectionStateProvider() == .ready else { return }
    let originalState = conversation.isFavorite
    let newState = !originalState

    switch conversation {
    case let .direct(contact):
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

    case let .channel(channel):
      // Channels are app-only - optimistic update
      applyFavoriteUpdate(conversation, isFavorite: newState, disableAnimation: disableAnimation)

      do {
        try await dataStore?.setChannelFavorite(channel.id, isFavorite: newState)
      } catch {
        // Rollback on failure
        applyFavoriteUpdate(conversation, isFavorite: originalState, disableAnimation: disableAnimation)
        logger.error("Failed to toggle channel favorite: \(error)")
      }

    case let .room(session):
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

  /// Updates the favorite state in the local buffers. `recomputeSnapshot()` runs synchronously
  /// after the mutation so it stays inside any `disablesAnimations` transaction the caller opens.
  private func updateConversationFavoriteState(_ conversation: Conversation, isFavorite: Bool) {
    switch conversation {
    case let .direct(contact):
      if let index = conversations.firstIndex(where: { $0.id == contact.id }) {
        conversations[index] = conversations[index].with(isFavorite: isFavorite)
      }
    case let .channel(channel):
      if let index = channels.firstIndex(where: { $0.id == channel.id }) {
        channels[index] = channels[index].with(isFavorite: isFavorite)
      }
    case let .room(session):
      if let index = roomSessions.firstIndex(where: { $0.id == session.id }) {
        roomSessions[index] = roomSessions[index].with(isFavorite: isFavorite)
      }
    }
    recomputeSnapshot()
  }

  // MARK: - Conversation List

  /// Clears all conversation data from the view model.
  /// Called when the device is forgotten or removed so the list doesn't show stale entries.
  func clearConversations() {
    conversations = []
    channels = []
    roomSessions = []
    pendingRemovalIDs = []
    deletingIDs = []
    allContacts = []
    channelSenders = []
    channelSenderNames = []
    channelSenderOrder = [:]
    contactNameSet = []
    lastMessageCache = [:]
    recomputeSnapshot()
  }

  /// True while an optimistic hide or a confirmation-gated radio delete is in flight for
  /// `id`. Gates the delete action so a rapid re-tap can't double-fire the same removal.
  func isDeletePending(_ id: UUID) -> Bool {
    pendingRemovalIDs.contains(id) || deletingIDs.contains(id)
  }

  /// Hides a conversation, recording the id in `pendingRemovalIDs` so a racing reload can't
  /// resurrect it; `reconcilePendingRemovals()` drops the id once the fetch confirms it's gone.
  func removeConversation(_ conversation: Conversation) {
    pendingRemovalIDs.insert(conversation.id)
    withAnimation(.snappy) {
      switch conversation {
      case let .direct(contact):
        conversations = conversations.filter { $0.id != contact.id }
      case let .channel(channel):
        channels = channels.filter { $0.id != channel.id }
      case let .room(session):
        roomSessions = roomSessions.filter { $0.id != session.id }
      }
      recomputeSnapshot()
    }
  }

  /// Re-admits a row after its delete failed: drops the mask and re-inserts the caller-held DTO.
  /// Reusing the held DTO rather than re-fetching keeps the rollback independent of reload timing.
  func restoreConversation(_ conversation: Conversation) {
    pendingRemovalIDs.remove(conversation.id)
    withAnimation(.snappy) {
      switch conversation {
      case let .direct(contact):
        if !conversations.contains(where: { $0.id == contact.id }) {
          conversations.append(contact)
        }
      case let .channel(channel):
        if !channels.contains(where: { $0.id == channel.id }) {
          channels.append(channel)
        }
      case let .room(session):
        if !roomSessions.contains(where: { $0.id == session.id }) {
          roomSessions.append(session)
        }
      }
      recomputeSnapshot()
    }
  }

  /// Confirms a direct conversation's local clear: drops the mask and purges the fetch buffer
  /// together. Purging matters because a stale reload could have re-added the contact while it
  /// was masked, and a later recompute would then republish it; a re-created row still returns
  /// via the next reload's fetch.
  func confirmDirectRemoval(_ contact: ContactDTO) {
    pendingRemovalIDs.remove(contact.id)
    conversations.removeAll { $0.id == contact.id }
    recomputeSnapshot()
  }

  /// Load conversations for a device
  func loadConversations(radioID: UUID) async {
    guard let dataStore else { return }

    isLoading = true
    errorBannerMessage = nil

    do {
      conversations = try await dataStore.fetchConversations(radioID: radioID)
      recomputeSnapshot()
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
      recomputeSnapshot()
    } catch {
      // Silently handle - channels are optional
    }
  }

  /// Single entry point for every list reload. Cancel-and-replaces any in-flight reload so the
  /// latest request wins and a superseded one returns at an `isCancelled` gate before committing.
  /// Returns the new task so a caller that needs ordering (initial load) can await `.value`.
  @discardableResult
  func requestConversationReload() -> Task<Void, Never>? {
    reloadTask?.cancel()
    guard let radioID = currentRadioIDProvider() else {
      reloadTask = nil
      clearConversations()
      return nil
    }
    let task = Task { @MainActor [weak self] in
      guard let self else { return }
      await performConversationReload(radioID: radioID)
    }
    reloadTask = task
    return task
  }

  /// Fetches contacts, channels, and rooms into locals, then commits one consistent snapshot.
  /// No `await` may sit between the last `isCancelled` check and the assignment, so no other
  /// reload can interleave a mismatched commit on the main actor.
  private func performConversationReload(radioID: UUID) async {
    guard let dataStore else { return }
    isLoading = true
    defer { isLoading = false }

    // Only fetchConversations sets the error banner; channel/room failures stay silent.
    var banner: String?
    let fetchedConversations: [ContactDTO]?
    do {
      fetchedConversations = try await dataStore.fetchConversations(radioID: radioID)
    } catch {
      fetchedConversations = nil
      banner = L10n.Chats.Chats.Error.loadConversationsFailed
      logger.error("performConversationReload fetchConversations failed: \(error.localizedDescription)")
    }
    if Task.isCancelled { return }
    #if DEBUG
      await reloadInterleaveHook?()
      if Task.isCancelled { return }
    #endif

    let fetchedChannels = try? await dataStore.fetchChannels(radioID: radioID)
    if Task.isCancelled { return }
    let fetchedRooms = await (try? dataStore.fetchRemoteNodeSessions(radioID: radioID))?
      .filter(\.isRoom)
    if Task.isCancelled { return }

    if let fetchedConversations { conversations = fetchedConversations }
    if let fetchedChannels { channels = fetchedChannels }
    if let fetchedRooms { roomSessions = fetchedRooms }
    errorBannerMessage = banner
    reconcilePendingRemovals()
    recomputeSnapshot()
    hasLoadedOnce = true

    // Skip the trailing preview load if this reload was superseded.
    if Task.isCancelled { return }
    await loadLastMessagePreviews()
  }

  // MARK: - Messages

  /// Load messages for a contact
  func loadMessages(for contact: ContactDTO) async {
    // Close the per-conversation empty-state gate while the fetch is
    // in flight. No-op when the coordinator is already past
    // `.uninitialized` (warm rebind, refresh).
    coordinator?.beginLoading()

    guard let dataStore else {
      coordinator?.markLoaded()
      return
    }

    // Clear preview state only when switching to a different conversation
    if currentContact?.id != contact.id {
      clearPreviewState()
      newMessagesDividerMessageID = nil
      dividerComputed = false
    }

    currentContact = contact
    currentChannel = nil

    // Track active conversation for notification suppression
    notificationService?.setActiveConversation(contactID: contact.id)

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
      computeDividerPosition(from: fetchedMessages, unreadCount: contact.unreadCount, isDM: true)

      // Hide sent reaction messages (unless failed)
      fetchedMessages = filterOutgoingReactionMessages(fetchedMessages, isDM: true)

      // Use unfiltered count to determine if more messages exist
      coordinator?.updateRenderState { $0.with(hasMoreMessages: unfilteredCount == ChatCoordinator.pageSize) }
      coordinator?.replaceAll(fetchedMessages)

      buildItems()

      // Index loaded messages for reaction matching and process any pending reactions
      if let reactionService = reactionServiceProvider() {
        await indexMessagesForReactions(
          fetchedMessages,
          scope: .direct(contact),
          reactionService: reactionService,
          dataStore: dataStore
        )
      }

      // Clear unread count and mention badge, then notify UI to refresh chat list.
      // The messages already rendered, so a bookkeeping failure here is logged
      // rather than surfaced as a load error.
      do {
        try await dataStore.clearUnreadCount(contactID: contact.id)
        try await dataStore.clearUnreadMentionCount(contactID: contact.id)
      } catch {
        logger.warning("loadMessages: failed to clear unread counts - \(error.localizedDescription)")
      }
      syncCoordinator?.notifyConversationsChanged()

      // Update app badge
      await notificationService?.updateBadgeCount()
    } catch is CancellationError {
      // Benign cancellation; the superseding load will refetch.
    } catch {
      errorMessage = error.userFacingMessage
    }

    // Ensures the empty-state gate opens even when the fetch threw —
    // `replaceAll` is the success path; this catches the failure path.
    coordinator?.markLoaded()
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

  /// Restores the composer for `id` on conversation entry, applying restore sources in a
  /// fixed priority order so the precedence can't be reversed by reordering at the call site:
  /// a pending notification quick-reply draft (assigned unconditionally) wins over an older
  /// persisted disk draft (applied only when the field is still empty).
  func restoreComposerDraft(from store: DraftStore, id: ChatConversationID) {
    loadDraftIfExists()
    loadDraft(from: store, id: id)
  }

  /// Restores the persisted composer draft for `id`, but only when the field is empty — so a
  /// reconnect-driven reload can't clobber in-progress text and a quick-reply draft applied
  /// first keeps precedence. The store is passed in from the view's environment, never a
  /// stored dependency, so a stale configure can't silently skip the restore.
  func loadDraft(from store: DraftStore, id: ChatConversationID) {
    if let restored = store.draftToApply(over: composingText, for: id) {
      composingText = restored
    }
  }

  /// Persists the current composer text as the draft for `id`, or removes it when empty.
  func saveDraft(to store: DraftStore, id: ChatConversationID) {
    store.setDraft(composingText, for: id)
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
      schedulePrefetchForOutgoingMessage(message, isChannelMessage: false)
      syncCoordinator?.notifyConversationsChanged()
    } catch {
      errorMessage = error.userFacingMessage
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
          // Find the last non-reaction message (skip outgoing reactions unless failed)
          let lastMessage = contactMessages[contact.id]?.last { message in
            guard message.direction == .outgoing,
                  ReactionParser.parseDM(message.text) != nil else {
              return true
            }
            return message.status == .failed
          }

          // Evict the cached preview only when no messages remain, so a cleared DM
          // (still listed via lastMessageDate) shows "No messages"; a contact whose
          // recent messages are all filtered-out reactions keeps its prior preview.
          if let lastMessage {
            lastMessageCache[contact.id] = lastMessage
          } else if contactMessages[contact.id]?.isEmpty ?? true {
            lastMessageCache.removeValue(forKey: contact.id)
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
