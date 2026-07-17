// SyncCoordinator+MessageHandlers.swift
import CryptoKit
import Foundation

// MARK: - Message & Discovery Handler Wiring

extension SyncCoordinator {
  // MARK: - Message Handler Wiring

  func wireMessageHandlers(dependencies: SyncDependencies, radioID: UUID) async {
    logger.info("Wiring message handlers for device \(radioID)")

    // Populate blocked contacts cache
    await refreshBlockedContactsCache(radioID: radioID, dataStore: dependencies.dataStore)

    // Cache device node name for self-mention detection
    let device = try? await dependencies.dataStore.fetchDevice(radioID: radioID)
    let selfNodeName = device?.nodeName ?? ""

    await wireContactMessageHandler(dependencies: dependencies, radioID: radioID, selfNodeName: selfNodeName)
    await wireChannelMessageHandler(dependencies: dependencies, radioID: radioID, selfNodeName: selfNodeName)
    await wireSignedMessageHandler(dependencies: dependencies)
    await wireCLIMessageHandler(dependencies: dependencies)

    logger.info("Message handlers wired successfully")
  }

  // MARK: - Contact Message Handler

  private func wireContactMessageHandler(dependencies: SyncDependencies, radioID: UUID, selfNodeName: String) async {
    await dependencies.messagePollingService.setContactMessageHandler { [weak self] message, contact, context in
      guard let self else { return }
      await handleIncomingMessage(
        kind: .direct(message, contact: contact),
        context: context,
        dependencies: dependencies,
        radioID: radioID,
        selfNodeName: selfNodeName
      )
    }
  }

  // MARK: - Channel Message Handler

  private func wireChannelMessageHandler(dependencies: SyncDependencies, radioID: UUID, selfNodeName: String) async {
    await dependencies.messagePollingService.setChannelMessageHandler { [weak self] message, channel, context in
      guard let self else { return }
      await handleIncomingMessage(
        kind: .channel(message, channel: channel),
        context: context,
        dependencies: dependencies,
        radioID: radioID,
        selfNodeName: selfNodeName
      )
    }
  }

  // MARK: - Incoming Message Pipeline

  /// Discriminates the two text-message ingestion paths, carrying the wire
  /// message and the resolved conversation for each.
  private enum IncomingMessageKind {
    case direct(ContactMessage, contact: ContactDTO?)
    case channel(ChannelMessage, channel: ChannelDTO?)

    /// Log noun distinguishing the two paths in shared diagnostics.
    var logLabel: String {
      switch self {
      case .direct: "direct"
      case .channel: "channel"
      }
    }
  }

  /// Shared ingestion pipeline for incoming direct and channel messages:
  /// timestamp correction, RX-log path correlation, dedup, reaction
  /// short-circuit, persistence, unread/notification updates, and UI refresh.
  private func handleIncomingMessage(
    kind: IncomingMessageKind,
    context: DeliveryContext,
    dependencies: SyncDependencies,
    radioID: UUID,
    selfNodeName: String
  ) async {
    // Per-kind wire fields. Channel messages embed the sender as a
    // "NodeName: text" prefix; direct messages carry the sender key prefix.
    let text: String
    let senderNodeName: String?
    let senderTimestampDate: Date
    let textTypeRaw: UInt8
    let snr: Double?
    let reportedPathLength: UInt8
    let contactID: UUID?
    let channelIndex: UInt8?
    let senderKeyPrefix: Data?
    switch kind {
    case let .direct(message, contact):
      // The firmware cannot surface a self-DM here: decrypt runs against a
      // contact's ECDH shared secret and the local self_id is never in
      // contacts[], so an echo of the user's own DM never reaches this arm.
      text = message.text
      senderNodeName = nil
      senderTimestampDate = message.senderTimestamp
      textTypeRaw = message.textType
      snr = message.snr
      reportedPathLength = message.pathLength
      contactID = contact?.id
      channelIndex = nil
      senderKeyPrefix = message.senderPublicKeyPrefix
    case let .channel(message, _):
      // Parse "NodeName: text" format for sender name
      (senderNodeName, text) = Self.parseChannelMessage(message.text)
      senderTimestampDate = message.senderTimestamp
      textTypeRaw = message.textType
      snr = message.snr
      reportedPathLength = message.pathLength
      contactID = nil
      channelIndex = message.channelIndex
      senderKeyPrefix = nil
    }

    let timestamp = UInt32(senderTimestampDate.timeIntervalSince1970)

    // Correct invalid timestamps (sender clock wrong)
    let receiveTime = Date()
    let (finalTimestamp, timestampCorrected) = Self.correctTimestampIfNeeded(timestamp, receiveTime: receiveTime)
    if timestampCorrected {
      logger.debug("Corrected invalid \(kind.logLabel) message timestamp from \(Date(timeIntervalSince1970: TimeInterval(timestamp))) to \(receiveTime)")
    }

    let sortDate = Self.sortDate(for: context, receiveTime: receiveTime)

    // Look up path data from RxLogEntry using the sender timestamp stored
    // during decryption (for direct messages, channelIndex is nil)
    let rxResult = await lookupRxLogEntry(
      dependencies: dependencies,
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: timestamp,
      senderPublicKeyPrefix: senderKeyPrefix,
      defaultPathLength: reportedPathLength
    )

    // Use content-based key for dedup (stable across retry attempts).
    // The RX log packetHash is per-encrypted-packet and differs between
    // retries with different attempt counters, so it must not drive dedup.
    let deduplicationKey = Self.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: channelIndex,
      senderNodeName: senderNodeName, timestamp: timestamp, content: text
    )

    // Check for self-mention before creating DTO
    // For channel messages, filter out messages where the user mentions themselves
    let hasSelfMention: Bool = switch kind {
    case .direct:
      !selfNodeName.isEmpty &&
        MentionUtilities.containsSelfMention(in: text, selfName: selfNodeName)
    case .channel:
      !selfNodeName.isEmpty &&
        senderNodeName != selfNodeName &&
        MentionUtilities.containsSelfMention(in: text, selfName: selfNodeName)
    }

    // Clamp an unknown wire textType to .plain. MessageDTO decodes textType
    // non-optionally, so an out-of-range raw value would throw and fail the
    // whole envelope decode on backup round-trip; guaranteeing only the
    // known cases reach persistence keeps that round-trip safe.
    let resolvedTextType: TextType
    if let parsed = TextType(rawValue: textTypeRaw) {
      resolvedTextType = parsed
    } else {
      logger.warning("Unknown \(kind.logLabel) message textType raw=\(textTypeRaw); clamping to .plain")
      resolvedTextType = .plain
    }

    // regionScope is incoming-only by data-pipeline design — outgoing
    // messages do not flow through 0x88 / RxLogEntry correlation.
    let messageDTO = MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: channelIndex,
      text: text,
      timestamp: finalTimestamp,
      createdAt: receiveTime,
      sortDate: sortDate,
      direction: .incoming,
      status: .delivered,
      textType: resolvedTextType,
      ackCode: nil,
      pathLength: rxResult.pathLength,
      snr: snr,
      pathNodes: rxResult.pathNodes,
      senderKeyPrefix: senderKeyPrefix,
      senderNodeName: senderNodeName,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      deduplicationKey: deduplicationKey,
      containsSelfMention: hasSelfMention,
      mentionSeen: false,
      timestampCorrected: timestampCorrected,
      senderTimestamp: timestampCorrected ? timestamp : nil,
      routeType: rxResult.routeType,
      regionScope: rxResult.regionScope
    )

    // Check for duplicate before saving
    do {
      if try await dependencies.dataStore.isDuplicateMessage(deduplicationKey: deduplicationKey, radioID: radioID) {
        logger.info("Skipping duplicate \(kind.logLabel) message")
        return
      }
    } catch {
      logger.warning("Dedup check failed, proceeding with save: \(error)")
    }

    switch kind {
    case let .direct(_, contact):
      // Check if this is a DM reaction
      if let contact,
         await handleDMReaction(
           text: text,
           contact: contact,
           radioID: radioID,
           dependencies: dependencies
         ) {
        return
      }
    case let .channel(message, _):
      // Discard messages from blocked senders
      if isBlockedSender(senderNodeName) {
        return
      }

      // Check if this is a reaction
      if await handleChannelReaction(
        text: text,
        channelIndex: message.channelIndex,
        senderNodeName: senderNodeName,
        selfNodeName: selfNodeName,
        receiveTime: receiveTime,
        radioID: radioID,
        dependencies: dependencies
      ) {
        return
      }
    }

    do {
      try await dependencies.dataStore.saveMessage(messageDTO)

      switch kind {
      case let .direct(_, contact):
        try await indexAndNotifyDirectMessage(
          messageDTO: messageDTO,
          contact: contact,
          messageText: text,
          timestamp: timestamp,
          hasSelfMention: hasSelfMention,
          dependencies: dependencies,
          radioID: radioID
        )
      case let .channel(message, channel):
        try await indexAndNotifyChannelMessage(
          messageDTO: messageDTO,
          channel: channel,
          channelIndex: message.channelIndex,
          senderNodeName: senderNodeName,
          messageText: text,
          timestamp: timestamp,
          hasSelfMention: hasSelfMention,
          dependencies: dependencies,
          radioID: radioID
        )
      }

      // Notify conversation list of changes
      await notifyConversationsChanged()

      // Broadcast for real-time chat updates
      if case let .direct(_, contact) = kind, let contact {
        dataEventBroadcaster.yield(.directMessageReceived(message: messageDTO, contact: contact))
      }
    } catch {
      switch kind {
      case .direct:
        logger.error("Failed to save contact message: \(error)")
      case .channel:
        logger.error("Failed to save channel message: \(error)")
      }
    }
  }

  /// Post-save side effects for a direct message: reaction indexing, contact
  /// bookkeeping, and unread/notification updates.
  private func indexAndNotifyDirectMessage(
    messageDTO: MessageDTO,
    contact: ContactDTO?,
    messageText: String,
    timestamp: UInt32,
    hasSelfMention: Bool,
    dependencies: SyncDependencies,
    radioID: UUID
  ) async throws {
    // Index DM message for reaction targeting
    if let contact {
      let pendingMatches = await dependencies.reactionService.indexDMMessage(
        id: messageDTO.id,
        contactID: contact.id,
        text: messageText,
        timestamp: timestamp
      )

      // Process pending reactions that now have their target
      for pending in pendingMatches {
        let reactionDTO = ReactionDTO(
          messageID: messageDTO.id,
          emoji: pending.parsed.emoji,
          senderName: pending.senderName,
          messageHash: pending.parsed.messageHash,
          rawText: pending.rawText,
          contactID: contact.id,
          radioID: radioID
        )
        if await persistReactionIfNew(reactionDTO, dependencies: dependencies) {
          logger.debug("Processed pending DM reaction \(pending.parsed.emoji)")
        }
      }
    }

    // Update contact's last message date
    if let contactID = contact?.id {
      try await dependencies.dataStore.updateContactLastMessage(contactID: contactID, date: Date())
    }

    // Only increment unread count, post notification, and update badge for non-blocked contacts
    if let contactID = contact?.id, contact?.isBlocked != true {
      try await updateDMUnreadsAndNotify(
        messageDTO: messageDTO,
        contactID: contactID,
        contact: contact,
        messageText: messageText,
        hasSelfMention: hasSelfMention,
        dependencies: dependencies
      )
    }
  }

  /// Post-save side effects for a channel message: reaction indexing, channel
  /// bookkeeping, and unread/notification updates.
  private func indexAndNotifyChannelMessage(
    messageDTO: MessageDTO,
    channel: ChannelDTO?,
    channelIndex: UInt8,
    senderNodeName: String?,
    messageText: String,
    timestamp: UInt32,
    hasSelfMention: Bool,
    dependencies: SyncDependencies,
    radioID: UUID
  ) async throws {
    // Index message for reaction matching and process any pending reactions
    // Use original timestamp for indexing so pending reactions can match
    if let senderName = senderNodeName {
      let pendingMatches = await dependencies.reactionService.indexMessage(
        id: messageDTO.id,
        channelIndex: channelIndex,
        senderName: senderName,
        text: messageText,
        timestamp: timestamp
      )

      // Process any pending reactions that now have their target
      for pending in pendingMatches {
        let reactionDTO = ReactionDTO(
          messageID: messageDTO.id,
          emoji: pending.parsed.emoji,
          senderName: pending.senderNodeName,
          messageHash: pending.parsed.messageHash,
          rawText: pending.rawText,
          channelIndex: pending.channelIndex,
          radioID: pending.radioID
        )
        await persistReactionIfNew(reactionDTO, dependencies: dependencies)
      }
    }

    // Update channel's last message date
    if let channelID = channel?.id {
      try await dependencies.dataStore.updateChannelLastMessage(channelID: channelID, date: Date())
    }

    // Only update unread count, badges, and notify UI for non-blocked senders
    if !isBlockedSender(senderNodeName) {
      try await updateChannelUnreadsAndNotify(
        messageDTO: messageDTO,
        channel: channel,
        channelIndex: channelIndex,
        senderNodeName: senderNodeName,
        messageText: messageText,
        timestamp: timestamp,
        hasSelfMention: hasSelfMention,
        radioID: radioID,
        dependencies: dependencies
      )
    }
  }

  // MARK: - Signed Message Handler

  private func wireSignedMessageHandler(dependencies: SyncDependencies) async {
    await dependencies.messagePollingService.setSignedMessageHandler { [weak self] message, _ in
      guard let self else { return }
      await handleIncomingSignedMessage(message, dependencies: dependencies)
    }
  }

  /// Persists a signed room message via `RoomServerService`, then posts the
  /// notification and refreshes the UI when the message was new.
  private func handleIncomingSignedMessage(_ message: ContactMessage, dependencies: SyncDependencies) async {
    // For signed room messages, the signature contains the 4-byte author key prefix
    guard let authorPrefix = message.signature?.prefix(4), authorPrefix.count == 4 else {
      logger.warning("Dropping signed message: missing or invalid author prefix")
      return
    }

    let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

    do {
      let savedMessage = try await dependencies.roomServerService.handleIncomingMessage(
        senderPublicKeyPrefix: message.senderPublicKeyPrefix,
        timestamp: timestamp,
        authorPrefix: Data(authorPrefix),
        text: message.text
      )

      // If message was saved (not a duplicate), notify UI and post notification
      if let savedMessage {
        // Fetch session for room name and mute status
        let session = try? await dependencies.dataStore.fetchRemoteNodeSession(id: savedMessage.sessionID)

        // Post notification for room message
        await dependencies.notificationService.postRoomMessageNotification(
          roomName: session?.name ?? "Room",
          sessionID: savedMessage.sessionID,
          senderName: savedMessage.authorName,
          messageText: savedMessage.text,
          messageID: savedMessage.id,
          notificationLevel: session?.notificationLevel ?? .all
        )
        await dependencies.notificationService.updateBadgeCount()

        await notifyConversationsChanged()
        dataEventBroadcaster.yield(.roomMessageReceived(savedMessage))
      }
    } catch {
      logger.error("Failed to handle room message: \(error)")
    }
  }

  // MARK: - CLI Message Handler

  private func wireCLIMessageHandler(dependencies: SyncDependencies) async {
    await dependencies.messagePollingService.setCLIMessageHandler { [weak self] message, contact in
      guard let self else { return }
      await handleIncomingCLIMessage(message, contact: contact, dependencies: dependencies)
    }
  }

  /// Routes a CLI response to the room or repeater admin service for the sending contact.
  /// A wire prefix echoed by the firmware is stripped first so downstream
  /// parsers see the same reply text regardless of firmware echo support.
  private func handleIncomingCLIMessage(
    _ message: ContactMessage,
    contact: ContactDTO?,
    dependencies: SyncDependencies
  ) async {
    if let contact {
      let routed: ContactMessage = if let echoed = CLIResponse.splitEchoedPrefix(message.text) {
        ContactMessage(
          senderPublicKeyPrefix: message.senderPublicKeyPrefix,
          pathLength: message.pathLength,
          textType: message.textType,
          senderTimestamp: message.senderTimestamp,
          signature: message.signature,
          text: echoed.body,
          snr: message.snr
        )
      } else {
        message
      }

      if contact.type == .room {
        await dependencies.roomAdminService.invokeCLIHandler(routed, fromContact: contact)
      } else {
        await dependencies.repeaterAdminService.invokeCLIHandler(routed, fromContact: contact)
      }
    } else {
      logger.warning("Dropping CLI response: no contact found for sender")
    }
  }

  // MARK: - Discovery Event Monitoring

  /// Consumes the advertisement event stream to post new-contact
  /// notifications and refresh contact lists. The subscription is
  /// registered synchronously before this method returns; events yielded
  /// earlier (during the initial sync) are deliberately not seen.
  func startDiscoveryEventMonitoring(dependencies: SyncDependencies, radioID: UUID) {
    logger.info("Starting discovery event monitoring for device \(radioID)")
    discoveryEventsTask?.cancel()
    let events = dependencies.advertisementService.events()
    discoveryEventsTask = Task { [weak self] in
      for await event in events {
        guard let self else { return }
        switch event {
        case let .newContactDiscovered(name, contactID, contactType):
          // Manual-add mode: a new contact was discovered via advertisement
          PersistentLogger(subsystem: "com.mc1", category: "discover-trace")
            .info("B4 relay newContactDiscovered \(contactID) -> notifyContactsChanged")
          await dependencies.notificationService.postNewContactNotification(
            contactName: name,
            contactID: contactID,
            contactType: contactType
          )
          await notifyContactsChanged()
        case .contactUpdated, .nodeStorageFullChanged, .contactDeletedCleanup,
             .pathDiscoveryResponse, .traceResponse, .traceSnrObserved:
          break
        }
      }
    }
  }

  /// Cancels the discovery event task so it releases the service references
  /// it captures. Called by `ServiceContainer.tearDown()`.
  func cancelDiscoveryEventMonitoring() {
    discoveryEventsTask?.cancel()
    discoveryEventsTask = nil
  }

  // MARK: - Static Helpers

  nonisolated static func fallbackDeduplicationKey(
    contactID: UUID?,
    channelIndex: UInt8?,
    senderNodeName: String?,
    timestamp: UInt32,
    content: String
  ) -> String {
    DeduplicationKey.contentBased(
      contactID: contactID,
      channelIndex: channelIndex,
      senderNodeName: senderNodeName,
      timestamp: timestamp,
      content: content
    )
  }

  nonisolated static func parseChannelMessage(_ text: String) -> (senderNodeName: String?, messageText: String) {
    let parts = text.split(separator: ":", maxSplits: 1)
    if parts.count > 1 {
      let senderName = String(parts[0]).trimmingCharacters(in: .whitespaces)
      let messageText = String(parts[1]).trimmingCharacters(in: .whitespaces)
      return (senderName, messageText)
    }
    return (nil, text)
  }

  /// A received channel message only warrants a user notification when it resolves to a known
  /// local channel. The radio can report a message on a slot the app has no `Channel` for: the
  /// firmware decrypts zero-key group traffic on an unconfigured slot and attributes it to the
  /// first empty slot. Such messages have no openable chat, so they are logged as unresolved
  /// but never surfaced as a notification.
  nonisolated static func shouldPostChannelNotification(forResolvedChannel channel: ChannelDTO?) -> Bool {
    channel != nil
  }
}
