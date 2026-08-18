import Foundation

/// Internal: reload scheduling is reached from app code only via
/// `ChatTimelineWriter.enqueueReload`; `hardReset`/`cancelInFlight` are
/// coordinator/registry internals.
extension ChatCoordinator {
  /// Single chokepoint for ack / retry / fail / heard-repeat / reaction
  /// events. Unions IDs into `pendingReloadIDs` and schedules a coalesced
  /// load if one is not already in flight. The load takes an atomic
  /// snapshot of the buffer, clears it, fetches fresh DTOs from the
  /// store, and applies. No event is ever dropped because no event asks
  /// "is this in render state?".
  func enqueueReload(updatedMessageIDs: Set<UUID>) {
    pendingReloadIDs.formUnion(updatedMessageIDs)
    scheduleCoalescedReload()
  }

  /// Convenience for the single-ID hot path.
  func enqueueReload(messageID: UUID) {
    enqueueReload(updatedMessageIDs: [messageID])
  }

  private func scheduleCoalescedReload() {
    guard !reloadInFlight, !hardResetInFlight else { return }
    reloadInFlight = true
    coalescedReloadTask = Task { [weak self] in
      await self?.coalescedReload()
    }
  }

  /// Drain `pendingReloadIDs` until empty: snapshot, clear, fetch, apply.
  /// Re-checks at the top of the loop so events landing during a fetch
  /// are reconciled in the next iteration. The `hardResetInFlight` break
  /// stops a mid-flight drain from stomping post-hardReset state with
  /// stale per-ID updates; the scheduler guard above prevents new
  /// Tasks, the break stops the running one.
  private func coalescedReload() async {
    while !pendingReloadIDs.isEmpty {
      if hardResetInFlight { break }
      let snapshot = pendingReloadIDs
      pendingReloadIDs.removeAll(keepingCapacity: true)
      await applyReloadedIDs(snapshot)
    }
    reloadInFlight = false
  }

  /// Per-ID fetch + in-place update. Routes through `dataStore` bound at
  /// construction. A fetch returning nil for an ID that the coordinator
  /// still holds is treated as inconsistency and triggers `hardReset`.
  /// After each successful refresh, `renderItemRebuilder` rebuilds the
  /// corresponding `MessageItem` so ack / retry / fail / heard-repeat /
  /// reaction events refresh the rendered bubble without a full
  /// `buildItems()` cycle.
  private func applyReloadedIDs(_ ids: Set<UUID>) async {
    var inconsistencyDetected = false
    var refreshedIDs: [UUID] = []
    for id in ids {
      do {
        if let fetched = try await dataStore.fetchMessage(id: id) {
          guard messagesByID[id] != nil else { continue }
          update(messageID: id) { dto in
            dto = fetched
          }
          refreshedIDs.append(id)
        } else if messagesByID[id] != nil {
          inconsistencyDetected = true
          logger.warning("applyReloadedIDs: fetch returned nil for known id \(id, privacy: .public)")
        }
      } catch {
        logger.warning("applyReloadedIDs fetch failed for \(id, privacy: .public): \(String(describing: error))")
      }
    }
    if let rebuilder = renderItemRebuilder {
      for id in refreshedIDs {
        rebuilder(id)
      }
    }
    if inconsistencyDetected {
      hardReset(reason: "fetch returned nil for in-memory message")
    }
  }

  /// Drop all state and re-fetch the loaded window. Runs on the window
  /// lane so it cannot interleave with populate or loadOlder.
  func hardReset(reason: String) {
    logger.warning("ChatCoordinator hardReset: \(reason, privacy: .public)")
    hardResetInFlight = true
    let id = conversationID
    let dataStore = dataStore
    hardResetTask = Task { [weak self] in
      defer {
        self?.hardResetInFlight = false
        if let self, !Task.isCancelled, !self.pendingReloadIDs.isEmpty {
          self.scheduleCoalescedReload()
        }
      }
      await self?.performWindowOperation {
        do {
          try Task.checkCancellation()
          guard let self else { return }
          let anchorSortDate = self.messages.map(\.sortDate).min()
          let window: (messages: [MessageDTO], hasMore: Bool) = switch id.conversation {
          case let .dm(contactID):
            try await dataStore.fetchMessageWindow(
              contactID: contactID,
              anchorSortDate: anchorSortDate,
              floorLimit: Self.pageSize
            )
          case let .channel(channelIndex):
            try await dataStore.fetchMessageWindow(
              radioID: id.radioID,
              channelIndex: channelIndex,
              anchorSortDate: anchorSortDate,
              floorLimit: Self.pageSize
            )
          }
          #if DEBUG
            await self.hardResetAfterFetchHook?()
          #endif
          try Task.checkCancellation()
          let unfilteredCount = window.messages.count
          self.replaceAll(self.hidingOutgoingReactions(window.messages))
          self.updateRenderState {
            $0.with(
              hasMoreMessages: window.hasMore,
              totalFetchedCount: unfilteredCount
            )
          }
          self.renderStateInvalidated?()
        } catch is CancellationError {
        } catch {
          self?.logger.error("hardReset refetch failed: \(String(describing: error))")
        }
      }
    }
  }

  /// Cancel in-flight maintenance Tasks. Called from
  /// `ChatCoordinatorRegistry` before it drops this coordinator.
  func cancelInFlight() {
    buildItemsTask?.cancel()
    coalescedReloadTask?.cancel()
    hardResetTask?.cancel()
  }

  private func hidingOutgoingReactions(_ messages: [MessageDTO]) -> [MessageDTO] {
    let isDM = switch conversationID.conversation {
    case .dm: true
    case .channel: false
    }
    return messages.filter { !$0.isHiddenOutgoingReaction(isDM: isDM) }
  }
}
