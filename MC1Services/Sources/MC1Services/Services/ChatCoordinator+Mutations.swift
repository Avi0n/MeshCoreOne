import Foundation

public extension ChatCoordinator {
  /// Replace the entire canonical messages list. Rebuilds the lookup
  /// dictionary and bumps `renderStateID` so any in-flight off-main
  /// build discards on apply. Settles `renderState.phase` to `.loaded`,
  /// which is the load-completion seam for both initial loads and
  /// `hardReset` refetches.
  func replaceAll(_ newMessages: [MessageDTO]) {
    messages = newMessages
    messagesByID = Dictionary(newMessages.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
    if renderState.phase != .loaded {
      renderState = renderState.with(phase: .loaded)
    }
    renderStateID &+= 1
  }

  /// Transition `renderState.phase` to `.loading` only when still
  /// `.uninitialized`. Called at the entry of `loadMessages` /
  /// `loadChannelMessages` so the per-conversation view's empty-state
  /// gate stays closed while the awaited fetch is in flight. A no-op
  /// once the coordinator has reached `.loading` or `.loaded` — a
  /// subsequent refresh of an already-populated timeline must not
  /// regress to a placeholder.
  func beginLoading() {
    guard renderState.phase == .uninitialized else { return }
    renderState = renderState.with(phase: .loading)
    renderStateID &+= 1
  }

  /// Force the phase to `.loaded`. Used by the load paths to dismiss the
  /// placeholder on error and on the nil-`dataStore` early-return, where
  /// `replaceAll` is never reached. Idempotent when already `.loaded`.
  func markLoaded() {
    guard renderState.phase != .loaded else { return }
    renderState = renderState.with(phase: .loaded)
    renderStateID &+= 1
  }

  /// Insert `older` at the head of the canonical messages list. Used by
  /// pagination to prepend a fresh page above the current timeline.
  /// Skips IDs already present so concurrent appends do not produce
  /// duplicate-key crashes during reordering.
  func prepend(_ older: [MessageDTO]) {
    guard !older.isEmpty else { return }
    let filtered = older.filter { messagesByID[$0.id] == nil }
    guard !filtered.isEmpty else { return }
    messages.insert(contentsOf: filtered, at: 0)
    for dto in filtered {
      messagesByID[dto.id] = dto
    }
    renderStateID &+= 1
  }

  /// Append a single message if its ID is not already known. The guard
  /// reads `messagesByID` (O(1) lookup) so events landing during an
  /// off-main build are still deduplicated correctly.
  @discardableResult
  func append(_ message: MessageDTO) -> Bool {
    guard messagesByID[message.id] == nil else { return false }
    messages.append(message)
    messagesByID[message.id] = message
    renderStateID &+= 1
    return true
  }

  /// Apply an in-place mutation to a message identified by ID. No-op on
  /// missing ID. Bumps `renderStateID` so stale off-main builds discard.
  func update(messageID: UUID, _ transform: (inout MessageDTO) -> Void) {
    guard let index = messages.firstIndex(where: { $0.id == messageID }) else { return }
    transform(&messages[index])
    messagesByID[messageID] = messages[index]
    renderStateID &+= 1
  }

  /// Remove a message by ID. No-op if absent.
  func remove(messageID: UUID) {
    guard messagesByID[messageID] != nil else { return }
    messages.removeAll { $0.id == messageID }
    messagesByID[messageID] = nil
    renderStateID &+= 1
  }

  /// Reassign the entire `messages` array in place after a same-sender
  /// reordering pass. Rebuilds `messagesByID` to match. Bumps
  /// `renderStateID`.
  func replaceMessagesPreservingByID(_ reordered: [MessageDTO]) {
    messages = reordered
    messagesByID = Dictionary(reordered.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
    renderStateID &+= 1
  }

  /// Single seam through which off-main builds apply their rendered
  /// timeline. Returns `false` if the captured `capturedID` does not
  /// match the current `renderStateID` — i.e., the build is stale and
  /// the caller should re-run.
  @discardableResult
  func setRenderState(_ new: ChatRenderState, capturedID: UInt64) -> Bool {
    guard capturedID == renderStateID else { return false }
    renderState = new
    return true
  }

  /// Direct render-state assignment for single-row updates that already
  /// hold the canonical messages array consistent. Used by load paths
  /// that reset pagination fields. Bumps `renderStateID` because the
  /// new render state may displace an in-flight off-main build.
  func updateRenderState(_ transform: (ChatRenderState) -> ChatRenderState) {
    renderState = transform(renderState)
    renderStateID &+= 1
  }

  /// Append a fully built `MessageItem` to the render state. Used by the
  /// single-row append path so the new bubble is visible immediately
  /// without waiting for the next off-main build.
  func appendRenderItem(_ item: MessageItem) {
    renderState = renderState.appendingItem(item)
    renderStateID &+= 1
  }

  /// Replace a single render-state item by ID via `transform`. No-op on
  /// missing ID. Bumps `renderStateID`.
  func updateRenderItem(id: UUID, _ transform: (MessageItem) -> MessageItem) {
    let newState = renderState.updatingItem(id: id, transform)
    guard newState != renderState else { return }
    renderState = newState
    renderStateID &+= 1
  }

  /// Remove a single render-state item by ID. Bumps `renderStateID`.
  func removeRenderItem(id: UUID) {
    let newState = renderState.removingItem(id: id)
    guard newState != renderState else { return }
    renderState = newState
    renderStateID &+= 1
  }

  /// Apply a status-only update to a message in place. Mutates the canonical
  /// `MessageDTO` (so `BubbleStatusRow` re-reads the new label) and the
  /// rendered `MessageItem`'s envelope and footer (so `bubbleColor` and
  /// `accessibilityMessageLabel` reflect the new status). No DB read.
  ///
  /// `roundTripTime` is preserved when nil so .sent transitions don't clobber
  /// an existing RTT recorded by a prior .delivered event.
  ///
  /// Mirrors the monotonic guard in `PersistenceStore.updateMessageAck`: once
  /// the DTO has reached `.delivered`, a later `.sent` write from the
  /// send-return path is skipped so the authoritative delivery state is
  /// preserved. Without this guard, the DM-send-return then ACK-listener race
  /// produces a visible `.delivered` then `.sent` downgrade in the UI when
  /// the listener wins.
  ///
  /// A second guard blocks `.failed` from being downgraded to `.pending` by
  /// a non-user-initiated path. Legitimate user-initiated retry sites set
  /// `userInitiated: true` so the flip lands; event-stream `applyStatusUpdate`
  /// callers (ack / send confirmation) never carry `.pending`, so the
  /// default avoids the visible `.failed` then `.pending` flicker when a
  /// stale event lands after the queue marked the row terminal.
  ///
  /// The `.delivered` downgrade guard is also relaxed for `userInitiated`
  /// transitions so a user-initiated resend can visibly flip a delivered
  /// row back to `.pending` while the queue retransmits; event-stream
  /// callers still cannot downgrade a delivered row.
  func applyStatusUpdate(
    messageID: UUID,
    status: MessageStatus,
    roundTripTime: UInt32? = nil,
    userInitiated: Bool = false
  ) {
    if let current = messagesByID[messageID] {
      if current.status == .delivered, status != .delivered, !userInitiated { return }
      if current.status == .failed, status == .pending, !userInitiated { return }
    }
    update(messageID: messageID) { dto in
      dto.status = status
      if let roundTripTime {
        dto.roundTripTime = roundTripTime
      }
    }
    updateRenderItem(id: messageID) { item in
      item.with(
        envelope: item.envelope.with(status: status),
        footer: item.footer.with(status: status)
      )
    }
  }
}
