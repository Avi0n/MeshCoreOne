import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Channel Messages

  /// Load messages for a channel: marks it active, syncs the device flood scope,
  /// populates the coordinator, then clears unread state. Delegates coordinator
  /// population to `primeInitialChannelMessages(for:)`; the unread/badge/notify
  /// side effects here run only when that load succeeded.
  func loadChannelMessages(for channel: ChannelDTO) async {
    // Track active channel for notification suppression
    notificationService?.setActiveConversation(
      channelIndex: channel.index,
      channelRadioID: channel.radioID
    )

    let loaded = await primeInitialChannelMessages(for: channel)

    // Push the device flood scope after populating the timeline. A device
    // command, so it runs only on real channel open — never during prefetch.
    await syncFloodScope(for: channel)

    guard loaded else { return }

    // Clear unread count and mention badge, then notify UI to refresh chat list.
    // The messages already rendered, so a bookkeeping failure here is logged
    // rather than surfaced as a load error.
    do {
      try await dataStore?.clearChannelUnreadCount(channelID: channel.id)
      try await dataStore?.clearChannelUnreadMentionCount(channelID: channel.id)
    } catch {
      logger.warning("loadChannelMessages: failed to clear unread counts - \(error.localizedDescription)")
    }
    syncCoordinator?.notifyConversationsChanged()

    // Update app badge
    await notificationService?.updateBadgeCount()
  }

  /// Populates the bound coordinator with the first page for `channel` and builds
  /// its render items and mention senders — with no notification, flood-scope,
  /// unread-clearing, or badge side effects. Safe to run before navigation to
  /// warm the coordinator so the channel renders populated on the first frame
  /// instead of popping in after the push transition. `loadChannelMessages`
  /// layers the open-time side effects on top. Returns true when the fetch
  /// succeeded.
  @discardableResult
  func primeInitialChannelMessages(for channel: ChannelDTO) async -> Bool {
    // Close the per-conversation empty-state gate while the fetch is
    // in flight. No-op when the coordinator is already past
    // `.uninitialized` (warm rebind, refresh).
    coordinator?.beginLoading()

    guard let dataStore else {
      coordinator?.markLoaded()
      return false
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

    isLoading = true
    // Dual-reset: this function is shared between passive load and user-initiated
    // retry paths, so both surfaces must clear at entry to avoid stale state.
    errorMessage = nil
    errorBannerMessage = nil

    // Reset pagination state for new conversation
    coordinator?.updateRenderState { $0.with(hasMoreMessages: true, isLoadingOlder: false, totalFetchedCount: 0) }

    var loaded = false
    do {
      // Size the first page to include every unread message so the divider target is loaded.
      let initialLimit = ChatCoordinator.initialPageSize(unreadCount: channel.unreadCount)
      var fetchedMessages = try await dataStore.fetchMessages(radioID: channel.radioID, channelIndex: channel.index, limit: initialLimit, offset: 0)
      let unfilteredCount = fetchedMessages.count
      coordinator?.updateRenderState { $0.with(totalFetchedCount: unfilteredCount) }

      // Compute divider position before filtering, using unfiltered array
      computeDividerPosition(from: fetchedMessages, unreadCount: channel.unreadCount, isDM: false)

      // Hide sent reaction messages (unless failed)
      fetchedMessages = filterOutgoingReactionMessages(fetchedMessages, isDM: false)

      // A full page means more history may exist above (compare to what we requested).
      coordinator?.updateRenderState { $0.with(hasMoreMessages: unfilteredCount == initialLimit) }
      coordinator?.replaceAll(fetchedMessages)

      buildChannelSenders(radioID: channel.radioID)
      buildItems()

      // Index loaded messages for reaction matching and process any pending reactions
      if let reactionService = reactionServiceProvider() {
        await indexMessagesForReactions(
          fetchedMessages,
          scope: .channel(channel, localNodeName: connectedDeviceProvider()?.nodeName),
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
    coordinator?.markLoaded()
    isLoading = false
    return loaded
  }

  /// Pushes the device's session-scoped flood key to match the effective scope
  /// for `channel`. The effective scope combines the per-channel preference with
  /// the device-level default — `.inherit` means "fall through to the default".
  /// A no-op when the desired scope already matches the last one pushed.
  private func syncFloodScope(for channel: ChannelDTO) async {
    let deviceDefault = connectedDeviceProvider()?.defaultFloodScopeName
    let desiredState: ChatViewModel.RegionScopeState = .pushed(
      channel.floodScope,
      deviceDefault: deviceDefault
    )
    guard lastSetRegionScope != desiredState, let session = sessionProvider() else { return }

    let resolved = ChannelFloodScopeResolver.resolve(
      channelFloodScope: channel.floodScope,
      deviceDefaultFloodScopeName: deviceDefault,
      supportsUnscopedFloodSend: connectedDeviceProvider()?.supportsUnscopedFloodSend ?? false
    )
    do {
      switch resolved {
      case .unscoped:
        try await session.setFloodScopeUnscoped()
      case let .scope(scope):
        try await session.setFloodScope(scope)
      }
      lastSetRegionScope = desiredState
    } catch is CancellationError {
      // Benign: a superseding load (reconnect / conversation switch) cancelled this one.
    } catch {
      logger.error("Failed to set flood scope: \(error.localizedDescription)")
    }
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

    let message: MessageDTO
    do {
      message = try await messageService.createPendingChannelMessage(
        text: text,
        channelIndex: channel.index,
        radioID: channel.radioID
      )
      appendMessageIfNew(message)
      schedulePrefetchForOutgoingMessage(message, isChannelMessage: true)
    } catch {
      errorMessage = error.userFacingMessage
      return
    }

    let envelope = ChannelMessageEnvelope(
      messageID: message.id,
      channelIndex: channel.index,
      isResend: false,
      messageText: message.text,
      messageTimestamp: message.timestamp,
      localNodeName: connectedDeviceProvider()?.nodeName
    )
    do {
      try await enqueueChannel(envelope)
    } catch {
      logger.error("enqueueChannel failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
      _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: message.id, status: .failed)
      coordinator?.applyStatusUpdate(messageID: message.id, status: .failed)
      sendErrorMessage = Self.copyForEnqueueFailure(error)
    }
  }

  /// Retry sending a failed channel message in place. The drain stamps a
  /// fresh timestamp via `resendChannelMessage` so the retry packet hashes
  /// differently from the original — the mesh dedup table is a 128-slot
  /// cyclic ring with no time-based eviction, so reusing the original
  /// timestamp would be silently dropped at every neighbour until 127
  /// unrelated packets evict the slot. The `retryInFlight` guard prevents
  /// reentrant double-tap during the synchronous status-update + reload +
  /// enqueue window. Once status flips to `.pending`, the bubble's retry
  /// button hides (UI gate), so a fresh tap cannot enqueue again until the
  /// channel send later fails and the row returns to `.failed`.
  func retryChannelMessage(_ message: MessageDTO) async {
    guard messageService != nil,
          currentChannel != nil,
          let channelIndex = message.channelIndex,
          !retryInFlight else { return }

    retryInFlight = true
    defer { retryInFlight = false }

    // Stand in for the surfaces loadChannelMessages would have reset.
    errorMessage = nil
    errorBannerMessage = nil

    // Capture once so the three status-update writes below all target the
    // same container even if a reconnect fires between the awaits.
    let dataStore = dataStore

    // Release any prior queue ownership before enqueuing a fresh
    // envelope. Channel sends never reach `.delivered` (no end-to-end
    // ACK), so the `.delivered` clobber risk that motivates the DM
    // guard doesn't apply here — but the queue's `hasPendingSend` gate
    // and the symmetric call shape with `retryMessage` are worth the
    // extra delete. Best-effort; the gate self-corrects.
    try? await dataStore?.deletePendingSendsForMessage(messageID: message.id)

    // Flip the row in place for instant "Sending" feedback rather than a
    // full loadChannelMessages refetch, which would also reset paging
    // state. The coordinator's applyStatusUpdate guards against
    // downgrading a row that resolved concurrently. Swap to the
    // unless-delivered variant for shape symmetry with `retryMessage`
    // and so a stray ACK landing doesn't get clobbered if a future
    // refactor introduces channel-side delivery.
    _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: message.id, status: .pending)
    coordinator?.applyStatusUpdate(messageID: message.id, status: .pending, userInitiated: true)

    let envelope = ChannelMessageEnvelope(
      messageID: message.id,
      channelIndex: channelIndex,
      isResend: true,
      messageText: message.text,
      messageTimestamp: message.timestamp,
      localNodeName: connectedDeviceProvider()?.nodeName
    )
    do {
      try await enqueueChannel(envelope)
    } catch {
      logger.error("enqueueChannel retry failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
      _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: message.id, status: .failed)
      coordinator?.applyStatusUpdate(messageID: message.id, status: .failed)
      sendErrorMessage = Self.copyForEnqueueFailure(error)
    }
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

  /// Register a channel sender for the mention picker. Always max-merges the
  /// timestamp into `channelSenderOrder` so older messages contribute to
  /// recency ranking; inserts a synthetic contact only when the sender is
  /// neither a known contact nor already tracked.
  func addChannelSenderIfNew(_ name: String, radioID: UUID, timestamp: UInt32) {
    let trimmed = name.trimmingCharacters(in: .whitespaces)
    guard !trimmed.isEmpty, trimmed.count <= 128 else { return }

    channelSenderOrder[trimmed] = max(timestamp, channelSenderOrder[trimmed] ?? 0)

    guard !contactNameSet.contains(trimmed),
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
      outPathLength: PacketBuilder.floodPathSentinel,
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
