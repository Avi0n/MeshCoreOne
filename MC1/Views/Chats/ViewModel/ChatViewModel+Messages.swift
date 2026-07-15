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
    // Clear preview state only when switching away from a previously loaded
    // conversation. A fresh view model has nothing to clear, and its cells
    // may already be fetching previews for this same conversation (warm
    // coordinators render before the load task runs), so clearing here would
    // cancel those fetches mid-flight and strand their rows at `.loading`.
    let isConversationSwitch = currentChannel != nil
      || (currentContact != nil && currentContact?.id != contact.id)
    if isConversationSwitch {
      clearPreviewState()
      bake.newMessagesDividerMessageID = nil
      bake.dividerComputed = false
    }

    currentContact = contact
    currentChannel = nil

    isLoading = true
    // Dual-reset: this function is shared between passive load and user-initiated
    // retry paths, so both surfaces must clear at entry to avoid stale state.
    errorMessage = nil
    errorBannerMessage = nil

    guard let timelineWriter else {
      isLoading = false
      return false
    }

    let reactions: ChatTimelinePopulator.ReactionIndexingContext? = {
      guard let reactionService = reactionServiceProvider() else { return nil }
      return ChatTimelinePopulator.ReactionIndexingContext(
        reactionService: reactionService,
        scope: .direct(contact),
        rebakeRow: { [weak self] messageID in
          self?.rebuildDisplayItem(for: messageID)
        }
      )
    }()

    let outcome = await ChatTimelinePopulator.populate(
      .dm(contact),
      writer: timelineWriter,
      dataStore: dataStore,
      bake: bake,
      envInputs: envInputs,
      senderTables: currentSenderTables(),
      reactions: reactions,
      postApply: { [weak self] in self?.decodeLegacyPreviewImages() }
    )

    let didLoad: Bool
    switch outcome {
    case .loaded:
      didLoad = true
    case .cancelled, .unavailable:
      didLoad = false
    case let .failed(error):
      errorMessage = error.userFacingMessage
      didLoad = false
    }

    isLoading = false
    return didLoad
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
