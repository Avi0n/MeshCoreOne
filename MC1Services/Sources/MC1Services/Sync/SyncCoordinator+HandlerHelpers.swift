// SyncCoordinator+HandlerHelpers.swift
import Foundation

// MARK: - Message Handler Helpers

extension SyncCoordinator {
  struct RxLogLookupResult {
    let pathNodes: Data?
    let pathLength: UInt8
    let packetHash: String?
    let routeType: RouteType?
    let regionScope: String?
    let regionScopeMatches: [String]
  }

  /// Looks up path data from an RxLogEntry to correlate with an incoming message.
  /// Channel rows join by DeduplicationKey after decrypt; DMs still use timestamp
  /// and sender-prefix fallback.
  func lookupRxLogEntry(
    dependencies: SyncDependencies,
    radioID: UUID,
    channelIndex: UInt8?,
    senderTimestamp: UInt32,
    senderPublicKeyPrefix: Data?,
    defaultPathLength: UInt8,
    channelDeduplicationKey: String?
  ) async -> RxLogLookupResult {
    if let channelIndex {
      logger.debug("Looking up RxLogEntry for channel \(channelIndex) with senderTimestamp: \(senderTimestamp)")
    }

    do {
      if let channelIndex {
        let rxEntry: RxLogEntryDTO?
        if let channelDeduplicationKey {
          let raw = try await dependencies.dataStore.fetchRxLogEntries(
            radioID: radioID,
            channelIndex: channelIndex,
            senderTimestamp: senderTimestamp
          )
          let decoded = await dependencies.rxLogService.decodedEntries(raw)
          rxEntry = ChannelRXCorrelation.matching(decoded, deduplicationKey: channelDeduplicationKey).first
        } else {
          rxEntry = nil
        }
        if let rxEntry {
          let pathLength = rxEntry.pathLength
          let pathNodes = rxEntry.pathNodes
          logger.info("Correlated channel message to RxLogEntry: pathLength=\(pathLength), pathNodes=\(pathNodes.count) bytes")
          return RxLogLookupResult(
            pathNodes: pathNodes,
            pathLength: pathLength,
            packetHash: rxEntry.packetHash,
            routeType: rxEntry.routeType,
            regionScope: rxEntry.regionScope,
            regionScopeMatches: rxEntry.regionScopeMatches
          )
        }
        logger.warning("No RxLogEntry found for channel \(channelIndex), senderTimestamp: \(senderTimestamp)")
      } else if let rxEntry = try await dependencies.dataStore.findRxLogEntry(
        radioID: radioID,
        channelIndex: nil,
        senderTimestamp: senderTimestamp
      ) {
        let pathLength = rxEntry.pathLength
        let pathNodes = rxEntry.pathNodes
        logger.debug("Correlated incoming direct message to RxLogEntry, pathLength: \(pathLength), pathNodes: \(pathNodes.count) bytes")
        return RxLogLookupResult(
          pathNodes: pathNodes,
          pathLength: pathLength,
          packetHash: rxEntry.packetHash,
          routeType: rxEntry.routeType,
          regionScope: rxEntry.regionScope,
          regionScopeMatches: rxEntry.regionScopeMatches
        )
      } else if let prefixByte = senderPublicKeyPrefix?.first {
        // Fallback for DMs: if timestamp-based lookup failed (e.g., RxLog decryption
        // hadn't extracted the timestamp yet), try matching by sender prefix byte
        // in the raw packet payload within a recent time window.
        let lookbackWindow = Date().addingTimeInterval(-30)
        if let rxEntry = try await dependencies.dataStore.findRxLogEntryBySenderPrefix(
          radioID: radioID,
          senderPrefixByte: prefixByte,
          receivedSince: lookbackWindow
        ) {
          logger.debug("Correlated DM to RxLogEntry via sender prefix fallback, pathLength: \(rxEntry.pathLength)")
          return RxLogLookupResult(
            pathNodes: rxEntry.pathNodes,
            pathLength: rxEntry.pathLength,
            packetHash: rxEntry.packetHash,
            routeType: rxEntry.routeType,
            regionScope: rxEntry.regionScope,
            regionScopeMatches: rxEntry.regionScopeMatches
          )
        }
        logger.debug("No RxLogEntry found for direct message (primary + fallback), senderTimestamp: \(senderTimestamp)")
      } else {
        logger.debug("No RxLogEntry found for direct message, senderTimestamp: \(senderTimestamp)")
      }
    } catch {
      if channelIndex != nil {
        logger.error("Failed to lookup RxLogEntry for channel message: \(error)")
      } else {
        logger.error("Failed to lookup RxLogEntry for direct message: \(error)")
      }
    }

    return RxLogLookupResult(
      pathNodes: nil,
      pathLength: defaultPathLength,
      packetHash: nil,
      routeType: nil,
      regionScope: nil,
      regionScopeMatches: []
    )
  }

  /// Updates the current Contact before publishing ordinary DM effects.
  func updateDMUnreadsAndNotify(
    messageDTO: MessageDTO,
    contact: ContactDTO,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async throws {
    // A mounted conversation does not mean its messages are visible in the background.
    let isInForeground = await dependencies.appStateProvider?.isInForeground ?? true
    let activeContactID = await dependencies.notificationService.activeContactID
    let isViewingContact = isInForeground && activeContactID == contact.id
    guard let current = try await dependencies.dataStore.updateContactAfterDirectMessage(
      messageDTO, contact: contact, scope: scope, isViewingContact: isViewingContact
    ), !current.isBlocked, scope.isCurrent else { return }

    await dependencies.notificationService.postDirectMessageNotification(
      from: current.displayName,
      contactID: current.id,
      messageText: messageDTO.text,
      messageID: messageDTO.id,
      isMuted: current.isMuted,
      scope: scope
    )
    await dependencies.notificationService.updateBadgeCount()
  }

  /// Increments unread counts, posts a notification, and notifies real-time listeners for a channel message.
  func updateChannelUnreadsAndNotify(
    messageDTO: MessageDTO,
    channel: ChannelDTO?,
    channelIndex: UInt8,
    senderNodeName: String?,
    messageText: String,
    timestamp: UInt32,
    hasSelfMention: Bool,
    radioID: UUID,
    dependencies: SyncDependencies,
    legacyScope: ReceiveScope? = nil
  ) async throws {
    guard legacyScope?.isCurrent != false else { return }
    if let channelID = channel?.id {
      // A mounted conversation does not mean its messages are visible in the background.
      let isInForeground = await dependencies.appStateProvider?.isInForeground ?? true
      let activeIndex = await dependencies.notificationService.activeChannelIndex
      let activeRadioID = await dependencies.notificationService.activeChannelRadioID
      let isViewingChannel = isInForeground && activeIndex == channel?.index && activeRadioID == channel?.radioID
      if !isViewingChannel {
        guard legacyScope?.isCurrent != false else { return }
        try await dependencies.dataStore.incrementChannelUnreadCount(channelID: channelID)

        // Increment unread mention count if message contains self-mention
        if hasSelfMention, legacyScope?.isCurrent != false {
          try await dependencies.dataStore.incrementChannelUnreadMentionCount(channelID: channelID)
        }
      }
    }
    guard legacyScope?.isCurrent != false else { return }
    if Self.shouldPostChannelNotification(forResolvedChannel: channel) {
      await dependencies.notificationService.postChannelMessageNotification(
        channelName: channel?.name ?? "Channel \(channelIndex)",
        channelIndex: channelIndex,
        radioID: radioID,
        senderName: senderNodeName,
        messageText: messageText,
        messageID: messageDTO.id,
        notificationLevel: channel?.notificationLevel ?? .all,
        hasSelfMention: hasSelfMention,
        legacyScope: legacyScope
      )
    } else if channel == nil {
      recordUnresolvedChannel(
        channelIndex: channelIndex,
        radioID: radioID,
        senderTimestamp: timestamp
      )
    }
    guard legacyScope?.isCurrent != false else { return }
    await dependencies.notificationService.updateBadgeCount()

    // Broadcast for real-time chat updates
    guard legacyScope?.isCurrent != false else { return }
    dataEventBroadcaster.yield(.channelMessageReceived(
      message: messageDTO, channelIndex: channelIndex, legacyScope: legacyScope
    ))
  }

  private func recordUnresolvedChannel(
    channelIndex: UInt8,
    radioID: UUID,
    senderTimestamp: UInt32
  ) {
    let isNewIndex = unresolvedChannelIndices.insert(channelIndex).inserted
    logger.warning(
      "Suppressing notification for unresolved channel \(channelIndex) on device \(radioID), senderTimestamp: \(senderTimestamp) — no local channel for this slot"
    )

    let now = Date()
    let shouldEmitSummary: Bool = if isNewIndex {
      true
    } else if let lastSummary = lastUnresolvedChannelSummaryAt {
      now.timeIntervalSince(lastSummary) >= unresolvedChannelSummaryIntervalSeconds
    } else {
      true
    }

    guard shouldEmitSummary else { return }
    let sortedIndices = unresolvedChannelIndices.sorted()
    logger.warning(
      "Unresolved channel summary: total=\(sortedIndices.count), indices=\(sortedIndices)"
    )
    lastUnresolvedChannelSummaryAt = now
  }
}
