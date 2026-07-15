import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Contacts

  /// Load all contacts for mention autocomplete
  func loadAllContacts(radioID: UUID) async {
    guard let dataStore else { return }

    do {
      allContacts = try await dataStore.fetchContacts(radioID: radioID)
      contactNameSet = Set(allContacts.map(\.name))
      nicknamesByLoweredName = MessageBubbleConfiguration.buildNicknameLookup(from: allContacts)
    } catch {
      logger.warning("Failed to load contacts for mentions: \(error.localizedDescription)")
    }
  }

  // MARK: - Messages

  /// Load messages for a contact: marks the conversation active, populates the
  /// coordinator, then clears unread state. Delegates the coordinator population
  /// to `primeInitialMessages(for:)`; the unread/badge/notify side effects here
  /// run only when that load succeeded.
  func loadMessages(for contact: ContactDTO) async {
    // Track active conversation for notification suppression
    notificationService?.setActiveConversation(contactID: contact.id)

    guard await primeInitialMessages(for: contact) else { return }

    // Clear unread count and mention badge, then notify UI to refresh chat list.
    // The messages already rendered, so a bookkeeping failure here is logged
    // rather than surfaced as a load error.
    do {
      try await dataStore?.clearUnreadCount(contactID: contact.id)
      try await dataStore?.clearUnreadMentionCount(contactID: contact.id)
    } catch {
      logger.warning("loadMessages: failed to clear unread counts - \(error.localizedDescription)")
    }
    syncCoordinator?.notifyConversationsChanged()

    // Update app badge
    await notificationService?.updateBadgeCount()
  }

  /// Populates the bound coordinator with the first page for `contact` and builds
  /// its render items — with no notification, unread-clearing, or badge side
  /// effects. Safe to run before navigation to warm the coordinator so the
  /// conversation renders populated on the first frame instead of popping in a
  /// frame after the push transition. `loadMessages` layers the open-time side
  /// effects on top. Returns true when the fetch succeeded.
  @discardableResult
  func primeInitialMessages(for contact: ContactDTO) async -> Bool {
    // Close the per-conversation empty-state gate while the fetch is
    // in flight. No-op when the coordinator is already past
    // `.uninitialized` (warm rebind, refresh).
    timelineWriter?.beginLoading()

    guard let dataStore else {
      timelineWriter?.markLoaded()
      return false
    }

    // Clear preview state only when switching away from a previously loaded
    // conversation. A fresh view model has nothing to clear, and its cells
    // may already be fetching previews for this same conversation (warm
    // coordinators render before the load task runs), so clearing here would
    // cancel those fetches mid-flight and strand their rows at `.loading`.
    let isConversationSwitch = currentChannel != nil
      || (currentContact != nil && currentContact?.id != contact.id)
    if isConversationSwitch {
      clearPreviewState()
      newMessagesDividerMessageID = nil
      dividerComputed = false
    }

    currentContact = contact
    currentChannel = nil

    isLoading = true
    // Dual-reset: this function is shared between passive load and user-initiated
    // retry paths, so both surfaces must clear at entry to avoid stale state.
    errorMessage = nil
    errorBannerMessage = nil

    // Reset pagination state for new conversation
    timelineWriter?.updateRenderState { $0.with(hasMoreMessages: true, isLoadingOlder: false, totalFetchedCount: 0) }

    var loaded = false
    do {
      // Size the first page to include every unread message so the divider target is loaded.
      let initialLimit = ChatCoordinator.initialPageSize(unreadCount: contact.unreadCount)
      var fetchedMessages = try await dataStore.fetchMessages(contactID: contact.id, limit: initialLimit, offset: 0)
      let unfilteredCount = fetchedMessages.count
      timelineWriter?.updateRenderState { $0.with(totalFetchedCount: unfilteredCount) }

      // Compute divider position before filtering, using unfiltered array
      computeDividerPosition(from: fetchedMessages, unreadCount: contact.unreadCount, isDM: true)

      // Hide sent reaction messages (unless failed)
      fetchedMessages = filterOutgoingReactionMessages(fetchedMessages, isDM: true)

      // A full page means more history may exist above (compare to what we requested).
      timelineWriter?.updateRenderState { $0.with(hasMoreMessages: unfilteredCount == initialLimit) }
      timelineWriter?.replaceAll(fetchedMessages)

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
      loaded = true
    } catch is CancellationError {
      // Benign cancellation; the superseding load will refetch.
    } catch {
      errorMessage = error.userFacingMessage
    }

    // Ensures the empty-state gate opens even when the fetch threw —
    // `replaceAll` is the success path; this catches the failure path.
    timelineWriter?.markLoaded()
    isLoading = false
    return loaded
  }

  // MARK: - Drafts

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

  // MARK: - Sending

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
      timelineWriter?.applyStatusUpdate(messageID: message.id, status: .failed)
      sendErrorMessage = Self.copyForEnqueueFailure(error)
    }
  }
}
