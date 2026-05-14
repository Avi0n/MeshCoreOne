import Foundation

public extension ChatCoordinator {

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

    /// Fail-safe: drop all state and re-fetch from the data store.
    /// Triggered when an internal invariant trips — currently only from
    /// `applyReloadedIDs` when an expected fetch returns nil for a message
    /// the coordinator still holds.
    ///
    /// Call-site invariant: `hardReset` is intended to be invoked from
    /// `applyReloadedIDs`, which is itself running inside the in-flight
    /// `coalescedReload` Task. The `hardResetInFlight` flag plus the
    /// `coalescedReload` while-loop break ensure that the calling Task
    /// exits without draining `pendingReloadIDs` after the refetch. A
    /// future caller invoking `hardReset` from outside the coalescedReload
    /// loop (a button, a remote-reset event, etc.) must either route
    /// through the same `applyReloadedIDs` chokepoint or await the active
    /// reload Task first — otherwise an in-flight `applyReloadedIDs` can
    /// resume after `replaceAll(fresh)` and stomp the freshly-loaded
    /// state with stale per-ID `update(messageID:)` writes.
    func hardReset(reason: String) {
        logger.warning("ChatCoordinator hardReset: \(reason, privacy: .public)")
        hardResetInFlight = true
        let id = conversationID
        let dataStore = self.dataStore
        hardResetTask = Task { [weak self] in
            defer {
                // Single cleanup site converges success and error paths.
                // Buffered IDs from the hardReset window are guaranteed to
                // drain. The Task body is @MainActor-isolated by
                // ChatCoordinator's @MainActor attribute, so defer fires on
                // the main actor — no nested Task hop needed.
                self?.hardResetInFlight = false
                if let self, !self.pendingReloadIDs.isEmpty {
                    self.scheduleCoalescedReload()
                }
            }
            do {
                let fresh: [MessageDTO]
                switch id.conversation {
                case .dm(let contactID):
                    fresh = try await dataStore.fetchMessages(
                        contactID: contactID,
                        limit: Self.pageSize,
                        offset: 0
                    )
                case .channel(let channelIndex):
                    fresh = try await dataStore.fetchMessages(
                        radioID: id.radioID,
                        channelIndex: channelIndex,
                        limit: Self.pageSize,
                        offset: 0
                    )
                }
                guard let self else { return }
                self.replaceAll(fresh)
                self.renderStateInvalidated?()
            } catch {
                self?.logger.error("hardReset refetch failed: \(String(describing: error))")
            }
        }
    }

    /// Cancel any in-flight maintenance Tasks owned by this coordinator.
    /// Called from `ChatCoordinatorRegistry.tearDown` before the registry
    /// drops its strong references so suspended drain loops do not keep
    /// the coordinator (and its captured `dataStore`) alive past the
    /// container's lifetime.
    func cancelInFlight() {
        buildItemsTask?.cancel()
        coalescedReloadTask?.cancel()
        hardResetTask?.cancel()
    }
}
