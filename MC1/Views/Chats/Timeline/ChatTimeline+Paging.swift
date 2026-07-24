import Foundation
import MC1Services

extension ChatTimeline {
  // MARK: - Populate

  /// Populates the coordinator with the first page for `conversation` and
  /// bakes render items, via the shared fetch → divider → filter → write →
  /// bake sequence. Returns `.unavailable` when unbound.
  func open(
    _ conversation: ChatConversationType,
    reactions: ReactionIndexing?
  ) async -> ChatTimelinePopulator.Outcome {
    self.conversation = conversation
    // Every outcome settles: a failed or unavailable open has no divider
    // coming, so the anchor decision must stop waiting for one.
    defer { initialLoadSettled = true }
    guard let writer else { return .unavailable }
    // Interactive opens staged with no unread have already presented at the
    // bottom; pre-latch so a fresher store unread count cannot bake a late
    // divider that grows a presented row. Primes carry no anchor and skip it.
    if role == .interactive, openUnreadCount == 0 {
      bake.dividerComputed = true
    }
    let context = reactions.map { indexing in
      ChatTimelinePopulator.ReactionIndexingContext(
        reactionService: indexing.service,
        scope: indexing.scope,
        rebakeRow: { [weak self] messageID in
          self?.rebakeRow(messageID)
        }
      )
    }
    return await ChatTimelinePopulator.populate(
      conversation,
      writer: writer,
      dataStore: dataStoreProvider(),
      bake: bake,
      envInputs: envInputs,
      senderTables: senderTablesProvider(),
      reactions: context,
      postApply: postApply
    )
  }

  // MARK: - Paging

  /// Loads the next older page for the open conversation, prepends it, and
  /// rebakes. Returns the newly loaded messages (reaction-filtered and
  /// deduplicated) for caller-side bookkeeping such as sender registration
  /// and reaction indexing; empty when skipped (already loading, end of
  /// history, unbound). Throws the fetch error after retiring the spinner.
  @discardableResult
  func loadOlder() async throws -> [MessageDTO] {
    guard !renderState.isLoadingOlder, renderState.hasMoreMessages else { return [] }
    guard let writer, let conversation, let dataStore = dataStoreProvider() else { return [] }

    writer.updateRenderState { $0.with(isLoadingOlder: true) }

    do {
      let currentOffset = renderState.totalFetchedCount
      var olderMessages: [MessageDTO]
      let isDM: Bool

      switch conversation {
      case let .dm(contact):
        isDM = true
        olderMessages = try await dataStore.fetchMessages(
          contactID: contact.id,
          limit: ChatCoordinator.pageSize,
          offset: currentOffset
        )
      case let .channel(channel):
        isDM = false
        olderMessages = try await dataStore.fetchMessages(
          radioID: channel.radioID,
          channelIndex: channel.index,
          limit: ChatCoordinator.pageSize,
          offset: currentOffset
        )
      }

      // Offsets count unfiltered rows, so end-of-history and the next
      // page's offset both derive from the raw fetch count.
      let unfilteredCount = olderMessages.count
      writer.updateRenderState { current in
        current.with(
          hasMoreMessages: unfilteredCount < ChatCoordinator.pageSize ? false : current.hasMoreMessages,
          totalFetchedCount: current.totalFetchedCount + unfilteredCount
        )
      }

      olderMessages = bake.filterOutgoingReactionMessages(olderMessages, isDM: isDM)

      // An in-flight admission can land a message this fetch also carries;
      // drop rows already present so the prepend cannot duplicate them.
      let existingIDs = Set(messages.map(\.id))
      olderMessages = olderMessages.filter { !existingIDs.contains($0.id) }

      // Prepend older messages (they're chronologically earlier), then
      // re-run same-sender reordering across the page boundary to handle
      // clusters that were split between the existing and newly loaded pages.
      writer.prepend(olderMessages)
      let reordered = MessageDTO.reorderSameSenderClusters(messages)
      writer.replaceMessagesPreservingByID(reordered)

      // Clear the spinner before rebaking, not after. `updateRenderState`
      // bumps the coordinator's `renderStateID`; doing it after the rebake
      // invalidates the just-launched off-main build on apply, forcing a full
      // duplicate rebuild of the entire timeline. The prepended messages are
      // already in the canonical array, so the spinner can retire now, and
      // slower caller-side follow-up (reaction indexing) never gates it.
      writer.updateRenderState { $0.with(isLoadingOlder: false) }

      rebakeAll()
      return olderMessages
    } catch {
      writer.updateRenderState { $0.with(isLoadingOlder: false) }
      throw error
    }
  }

  // MARK: - Admission

  /// Admits a message into the open timeline: dedupes against the loaded
  /// window and appends the message and its baked render item in one call
  /// frame, so the row lands already carrying its preview fragment. Returns
  /// false when the message was already present or the timeline is unbound
  /// (a stale writer drops the append at the coordinator).
  @discardableResult
  func admit(_ message: MessageDTO) -> Bool {
    guard coordinator != nil, let writer else { return false }
    let previous = messages.last
    guard writer.append(message) else { return false }
    writer.appendRenderItem(makeItem(for: message, previous: previous))
    return true
  }

  // MARK: - Message mutations

  /// Applies a status transition to a loaded message in place, so the
  /// bubble's status footer crossfades rather than restarting on a fresh
  /// item identity.
  func applyStatusUpdate(
    messageID: UUID,
    status: MessageStatus,
    roundTripTime: UInt32? = nil,
    userInitiated: Bool = false
  ) {
    writer?.applyStatusUpdate(
      messageID: messageID,
      status: status,
      roundTripTime: roundTripTime,
      userInitiated: userInitiated
    )
  }

  /// Queues a message for the coalesced DB-refresh reload cycle.
  func enqueueReload(messageID: UUID) {
    writer?.enqueueReload(messageID: messageID)
  }

  /// Removes a message and its render item together.
  func removeMessage(_ messageID: UUID) {
    writer?.remove(messageID: messageID)
    writer?.removeRenderItem(id: messageID)
  }

  /// Updates a loaded message in place and rebakes its row. No-ops when
  /// the message is not loaded.
  func updateMessage(id: UUID, _ mutation: (inout MessageDTO) -> Void) {
    guard let writer, messagesByID[id] != nil else { return }
    writer.update(messageID: id, mutation)
    rebakeRow(id)
  }
}
