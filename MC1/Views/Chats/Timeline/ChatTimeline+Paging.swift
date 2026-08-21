import Foundation
import MC1Services

extension ChatTimeline {
  // MARK: - Populate

  /// Populates the coordinator for `conversation` and bakes render items.
  /// Returns `.unavailable` when unbound.
  /// `populateMode` selects first-page replacement or an in-place window refresh; see `ChatPopulateMode`.
  func open(
    _ conversation: ChatConversationType,
    reactions: ReactionIndexing?,
    populateMode: ChatPopulateMode
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
    #if DEBUG
      if let error = testPopulateError {
        writer.beginLoading()
        writer.markLoaded()
        return .failed(error)
      }
      coordinator?.testPopulateFetchError = testPopulateFetchError
    #endif
    let context = reactions.map { indexing in
      ChatTimelinePopulator.ReactionIndexingContext(
        reactionService: indexing.service,
        scope: indexing.scope,
        rebakeRow: { [weak self] messageID in
          self?.rebakeRow(messageID)
        }
      )
    }
    guard let coordinator else { return .unavailable }
    let outcome: ChatTimelinePopulator.Outcome
    do {
      outcome = try await writer.performWindowOperation {
        try Task.checkCancellation()
        // Compute the anchor inside the lane. An earlier loadOlder would
        // extend the window; an enqueue-time anchor would truncate it.
        let refreshWindow = populateMode == .refreshWindow
          && coordinator.conversationID == conversation.coordinatorID
          && coordinator.renderState.phase == .loaded
          && !coordinator.messages.isEmpty
        let anchorSortDate: Date? = refreshWindow
          ? coordinator.messages.map(\.sortDate).min()
          : nil
        return await ChatTimelinePopulator.populate(
          conversation,
          writer: writer,
          dataStore: dataStoreProvider(),
          bake: bake,
          envInputs: envInputs,
          senderTables: senderTablesProvider,
          postApply: postApply,
          anchorSortDate: anchorSortDate
        )
      }
    } catch is CancellationError {
      return .cancelled
    } catch {
      return .failed(error)
    }
    if case .loaded = outcome,
       let context,
       let dataStore = dataStoreProvider() {
      await ChatTimelinePopulator.indexMessagesForReactions(
        coordinator.messages,
        scope: context.scope,
        reactionService: context.reactionService,
        dataStore: dataStore,
        writer: writer,
        rebakeRow: context.rebakeRow
      )
    }
    return outcome
  }

  // MARK: - Paging

  /// Loads the next older page for the open conversation, prepends it, and
  /// rebakes. Returns the newly loaded messages (reaction-filtered and
  /// deduplicated) for caller-side bookkeeping such as sender registration
  /// and reaction indexing; empty when skipped (already loading, end of
  /// history, or unbound). Fetch errors throw after the spinner retires.
  @discardableResult
  func loadOlder() async throws -> [MessageDTO] {
    guard let coordinator, let writer, let conversation else { return [] }
    guard let dataStore = dataStoreProvider() else { return [] }
    do {
      return try await writer.performWindowOperation {
        try Task.checkCancellation()
        guard !coordinator.renderState.isLoadingOlder,
              coordinator.renderState.hasMoreMessages else { return [] }

        writer.updateRenderState { $0.with(isLoadingOlder: true) }

        do {
          let currentOffset = coordinator.renderState.totalFetchedCount
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

          #if DEBUG
            await loadOlderInterleaveHook?()
            if let error = loadOlderTestError {
              throw error
            }
          #endif

          try Task.checkCancellation()

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
          let existingIDs = Set(coordinator.messages.map(\.id))
          olderMessages = olderMessages.filter { !existingIDs.contains($0.id) }

          // Re-run same-sender reordering so clusters split across the
          // page boundary stay grouped.
          writer.prepend(olderMessages)
          let reordered = MessageDTO.reorderSameSenderClusters(coordinator.messages)
          writer.replaceMessagesPreservingByID(reordered)

          // Retire the spinner before rebake. `updateRenderState` bumps
          // `renderStateID`; doing it after would invalidate the just-launched build.
          writer.updateRenderState { $0.with(isLoadingOlder: false) }

          bake.bakeAll(
            messages: coordinator.messages,
            writer: writer,
            envInputs: envInputs,
            senderTables: senderTablesProvider(),
            postApply: postApply
          )
          return olderMessages
        } catch is CancellationError {
          writer.updateRenderState { $0.with(isLoadingOlder: false) }
          return []
        } catch {
          writer.updateRenderState { $0.with(isLoadingOlder: false) }
          throw error
        }
      }
    } catch is CancellationError {
      return []
    } catch {
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
    let newItem = makeItem(for: message, previous: previous, next: nil)
    let shouldHandoff = previous.map {
      ChatMessageBakeState.incomingClusterContinues(from: $0, to: message)
    } ?? false
    writer.updateRenderState { state in
      var next = state
      if shouldHandoff, let previous {
        next = next.updatingItem(id: previous.id) { item in
          item.with(envelope: item.envelope.with(incomingAvatar: nil))
        }
      }
      return next.appendingItem(newItem)
    }
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

  func enqueueReload(updatedMessageIDs: Set<UUID>) {
    writer?.enqueueReload(updatedMessageIDs: updatedMessageIDs)
  }

  /// Removes a message and rebakes remaining channel neighbors in one items update.
  func removeMessage(_ messageID: UUID) {
    guard let writer, let coordinator else { return }
    let messages = coordinator.messages
    guard let index = messages.firstIndex(where: { $0.id == messageID }) else { return }
    let previous = index > 0 ? messages[index - 1] : nil
    let nextDTO = index + 1 < messages.count ? messages[index + 1] : nil
    let prevPrev = index > 1 ? messages[index - 2] : nil
    let nextNext = index + 2 < messages.count ? messages[index + 2] : nil
    writer.remove(messageID: messageID)
    writer.updateRenderState { state in
      var next = state.removingItem(id: messageID)
      if let previous, previous.isChannelMessage {
        next = next.updatingItem(id: previous.id) { _ in
          makeItem(for: previous, previous: prevPrev, next: nextDTO)
        }
      }
      if let nextDTO, nextDTO.isChannelMessage {
        next = next.updatingItem(id: nextDTO.id) { _ in
          makeItem(for: nextDTO, previous: previous, next: nextNext)
        }
      }
      return next
    }
  }

  /// Updates a loaded message in place and rebakes its row. No-ops when
  /// the message is not loaded.
  func updateMessage(id: UUID, _ mutation: (inout MessageDTO) -> Void) {
    guard let writer, messagesByID[id] != nil else { return }
    writer.update(messageID: id, mutation)
    rebakeRow(id)
  }
}
