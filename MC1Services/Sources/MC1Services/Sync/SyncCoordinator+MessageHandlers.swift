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
    await wireChannelDatagramHandler(dependencies: dependencies, radioID: radioID, selfNodeName: selfNodeName)
    await wireSignedMessageHandler(dependencies: dependencies)
    await wireCLIMessageHandler(dependencies: dependencies)

    logger.info("Message handlers wired successfully")
  }

  // MARK: - Contact Message Handler

  private func wireContactMessageHandler(dependencies: SyncDependencies, radioID: UUID, selfNodeName: String) async {
    await dependencies.messagePollingService.setContactMessageHandler { [weak self] message, contact, context, scope, preference in
      guard let self, scope.radioID == radioID, scope.isCurrent else { return }
      await handleIncomingMessage(
        kind: .direct(message, contact: contact),
        context: context,
        dependencies: dependencies,
        selfNodeName: selfNodeName,
        scope: scope,
        preference: preference
      )
    }
  }

  // MARK: - Channel Message Handler

  private func wireChannelMessageHandler(dependencies: SyncDependencies, radioID: UUID, selfNodeName: String) async {
    await dependencies.messagePollingService.setChannelMessageHandler { [weak self] message, channel, context, scope, preference, arrivedAt in
      guard let self, scope.radioID == radioID, scope.isCurrent else { return }
      await handleIncomingMessage(
        kind: .channel(message, channel: channel),
        receivedAt: arrivedAt,
        context: context,
        dependencies: dependencies,
        selfNodeName: selfNodeName,
        scope: scope,
        preference: preference
      )
    }
  }

  private func wireChannelDatagramHandler(dependencies: SyncDependencies, radioID: UUID, selfNodeName: String) async {
    await dependencies.messagePollingService.setChannelDatagramHandler { [weak self] datagram, channel, context, scope, preference, arrivedAt in
      guard let self, scope.radioID == radioID, scope.isCurrent, let image = MCOImageTransport.decode(datagram) else { return }
      let message = ChannelMessage(
        channelIndex: datagram.channelIndex,
        pathLength: datagram.pathLength,
        textType: MeshTextType.plain.rawValue,
        senderTimestamp: Date(timeIntervalSince1970: 0),
        text: image.text,
        snr: datagram.snr
      )
      await handleIncomingMessage(
        kind: .channel(message, channel: channel),
        receivedAt: arrivedAt,
        context: context,
        dependencies: dependencies,
        selfNodeName: selfNodeName,
        scope: scope,
        preference: preference,
        datagramSenderName: image.senderName
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

  /// When a DM arrives with no local contact, try to create the row from a
  /// pending 0x80 key before save/notify. Adoption still links pure orphans
  /// without posting a second alert.
  private func resolveDirectContactIfNeeded(
    _ kind: IncomingMessageKind,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async -> IncomingMessageKind {
    guard case let .direct(message, contact) = kind, contact == nil else {
      return kind
    }
    let prefix = message.senderPublicKeyPrefix
    guard !prefix.isEmpty,
          let materialized = await dependencies.advertisementService
            .materializeContactForPendingAdvert(matchingPrefix: prefix, scope: scope)
    else {
      return kind
    }
    return .direct(message, contact: materialized)
  }

  /// Re-decrypts stamp-matched 0x88 rows and harvests extras, then refetches
  /// so the channel broadcast carries the extra count.
  private func harvestAndRefreshChannelMessage(
    messageDTO: MessageDTO,
    radioID: UUID,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async throws -> MessageDTO {
    guard let channelIndex = messageDTO.channelIndex else { return messageDTO }
    let stamp = messageDTO.senderTimestamp ?? messageDTO.timestamp
    do {
      let raw = try await dependencies.dataStore.fetchRxLogEntries(
        radioID: radioID,
        channelIndex: channelIndex,
        senderTimestamp: stamp
      )
      let decoded = await dependencies.rxLogService.decodedEntries(raw)
      guard scope.isCurrent else { return messageDTO }
      await dependencies.heardRepeatsService.harvestIncomingPaths(
        for: messageDTO,
        decodedCandidates: decoded
      )
    } catch {
      logger.error("Failed to harvest incoming channel paths: \(error)")
    }
    if let refreshed = try await dependencies.dataStore.fetchMessage(id: messageDTO.id) {
      return refreshed
    }
    return messageDTO
  }

  /// True when a row already owns this content key. A known distinct channel path
  /// is recorded as an extra; a nil path is unknown and is not written as one.
  private func recordChannelArrivalAndSkipDuplicate(
    deduplicationKey: String,
    radioID: UUID,
    rxResult: RxLogLookupResult,
    snr: Double?,
    receiveTime: Date,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async -> Bool {
    do {
      guard let existing = try await dependencies.dataStore.fetchMessage(
        deduplicationKey: deduplicationKey,
        radioID: radioID
      ) else {
        return false
      }
      guard scope.isCurrent else { return true }
      if let path = rxResult.pathNodes {
        await dependencies.heardRepeatsService.recordDistinctPathIfNeeded(
          message: existing,
          pathNodes: path,
          pathLength: rxResult.pathLength,
          snr: snr,
          rssi: nil,
          receivedAt: receiveTime,
          rxLogEntryID: nil
        )
      }
      logger.info("Skipping duplicate channel message")
      return true
    } catch {
      logger.warning("Dedup check failed, proceeding with save: \(error)")
      return false
    }
  }

  /// Shared ingestion pipeline for incoming direct and channel messages:
  /// timestamp correction, RX-log path correlation, dedup, reaction
  /// short-circuit, persistence, unread/notification updates, and UI refresh.
  private func handleIncomingMessage(
    kind: IncomingMessageKind,
    receivedAt: Date = .now,
    context: DeliveryContext,
    dependencies: SyncDependencies,
    selfNodeName: String,
    scope: ReceiveScope,
    preference: TextRulesPreference?,
    datagramSenderName: String? = nil
  ) async {
    guard scope.isCurrent else { return }
    let radioID = scope.radioID
    // A DM can land in the advert delta-sync debounce window before the local
    // Contact row exists. Materialize from a pending 0x80 key so unread,
    // notification, and Chats-list updates use the live path exactly once.
    let resolvedKind = await resolveDirectContactIfNeeded(
      kind, dependencies: dependencies, scope: scope
    )

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
    switch resolvedKind {
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
      if let datagramSenderName {
        senderNodeName = datagramSenderName
        text = message.text
      } else {
        (senderNodeName, text) = Self.parseChannelMessage(message.text)
      }
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
    let receiveTime = receivedAt
    let (finalTimestamp, timestampCorrected) = Self.correctTimestampIfNeeded(timestamp, receiveTime: receiveTime)
    if timestampCorrected {
      logger.debug("Corrected invalid \(resolvedKind.logLabel) message timestamp from \(Date(timeIntervalSince1970: TimeInterval(timestamp))) to \(receiveTime)")
    }

    let sortDate = Self.sortDate(for: context, receiveTime: receiveTime)

    // Use content-based key for dedup (stable across retry attempts).
    // The RX log packetHash is per-encrypted-packet and differs between
    // retries with different attempt counters, so it must not drive dedup.
    let deduplicationKey = Self.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: channelIndex,
      senderNodeName: senderNodeName,
      timestamp: datagramSenderName == nil ? timestamp : finalTimestamp,
      content: text
    )

    // Channel lookup joins decrypted 0x88 rows by DeduplicationKey; DMs still
    // match timestamp then sender-prefix.
    let rxResult = await lookupRxLogEntry(
      dependencies: dependencies,
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: datagramSenderName == nil ? timestamp : nil,
      senderPublicKeyPrefix: senderKeyPrefix,
      defaultPathLength: reportedPathLength,
      channelDeduplicationKey: channelIndex != nil ? deduplicationKey : nil
    )

    // Check for self-mention before creating DTO
    // For channel messages, filter out messages where the user mentions themselves
    let hasSelfMention: Bool = switch resolvedKind {
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
      logger.warning("Unknown \(resolvedKind.logLabel) message textType raw=\(textTypeRaw); clamping to .plain")
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
      regionScope: rxResult.regionScope,
      regionScopeMatches: rxResult.regionScopeMatches
    )

    switch resolvedKind {
    case let .direct(_, contact):
      await receiveDirectMessage(
        messageDTO, wireTextType: textTypeRaw, contact: contact,
        scope: scope, preference: preference, dependencies: dependencies
      )

    case let .channel(message, channel):
      if await recordChannelArrivalAndSkipDuplicate(
        deduplicationKey: deduplicationKey,
        radioID: radioID,
        rxResult: rxResult,
        snr: snr,
        receiveTime: receiveTime,
        dependencies: dependencies,
        scope: scope
      ) {
        return
      }
      guard scope.isCurrent else { return }
      if isBlockedSender(senderNodeName) {
        return
      }
      do {
        if let channel, try await dependencies.dataStore.shouldSuppressChannelMessage(
          messageDTO, frame: message, channel: channel, scope: scope, preference: preference
        ) { return }
      } catch {
        logger.error("Failed to evaluate channel Text Rules: \(error)")
        return
      }
      guard scope.isCurrent else { return }
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
      do {
        await runTestBeforeMessageSave(messageDTO)
        guard try await dependencies.dataStore.saveReceivedChannelMessage(
                messageDTO, scope: scope, conversationID: channel?.textRulesID
              ),
              scope.isCurrent else { return }
        let savedDTO: MessageDTO
        if datagramSenderName == nil {
          savedDTO = try await harvestAndRefreshChannelMessage(
            messageDTO: messageDTO,
            radioID: radioID,
            dependencies: dependencies,
            scope: scope
          )
        } else {
          savedDTO = messageDTO
        }
        try await indexAndNotifyChannelMessage(
          messageDTO: savedDTO,
          channel: channel,
          indexesReactions: datagramSenderName == nil,
          dependencies: dependencies,
          scope: scope
        )
        await notifyConversationsChanged(scope: scope)
      } catch {
        logger.error("Failed to save channel message: \(error)")
      }
    }
  }

  private func receiveDirectMessage(
    _ message: MessageDTO,
    wireTextType: UInt8,
    contact: ContactDTO?,
    scope: ReceiveScope,
    preference: TextRulesPreference?,
    dependencies: SyncDependencies
  ) async {
    do {
      await runTestBeforeMessageSave(message)
      let admission = try await dependencies.dataStore.admitDirectMessage(
        message, wireTextType: wireTextType, contact: contact, scope: scope, preference: preference
      )
      guard scope.isCurrent else { return }
      switch admission {
      case .discarded:
        return
      case let .replay(saved):
        logger.debug("Skipping replay of direct message \(saved.id)")
      case let .reaction(contact):
        await stampDirectMessageContact(contact, message: message, scope: scope, dependencies: dependencies)
        _ = await handleDMReaction(
          text: message.text,
          contact: contact,
          radioID: message.radioID,
          dependencies: dependencies,
          scope: scope
        )
      case let .saved(contact):
        if let contact {
          await stampDirectMessageContact(contact, message: message, scope: scope, dependencies: dependencies)
          try await indexAndNotifyDirectMessage(
            messageDTO: message,
            contact: contact,
            dependencies: dependencies,
            scope: scope
          )
          guard await (try? dependencies.dataStore.currentDirectMessageContact(contact, scope: scope)) != nil,
                scope.isCurrent else { return }
          dataEventBroadcaster.yield(.directMessageReceived(message: message, contact: contact, scope: scope))
        }
        await notifyConversationsChanged(scope: scope)
      }
    } catch {
      logger.error("Failed to process incoming direct message: \(error)")
    }
  }

  private func stampDirectMessageContact(
    _ contact: ContactDTO,
    message: MessageDTO,
    scope: ReceiveScope,
    dependencies: SyncDependencies
  ) async {
    do {
      try await dependencies.dataStore.touchDirectMessageContact(contact, scope: scope, at: message.createdAt)
    } catch {
      logger.error("lastHeard stamp failed for inbound DM: \(error.localizedDescription)")
    }
  }

  /// Post-save side effects for a direct message: reaction indexing, contact
  /// bookkeeping, and unread/notification updates.
  private func indexAndNotifyDirectMessage(
    messageDTO: MessageDTO,
    contact: ContactDTO,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async throws {
    let pendingMatches = await dependencies.reactionService.indexDMMessage(
      id: messageDTO.id,
      contactID: contact.id,
      text: messageDTO.text,
      timestamp: messageDTO.reactionTimestamp
    )
    for pending in pendingMatches {
      let reactionDTO = ReactionDTO(
        messageID: messageDTO.id,
        emoji: pending.parsed.emoji,
        senderName: pending.senderName,
        messageHash: pending.parsed.messageHash,
        rawText: pending.rawText,
        contactID: contact.id,
        radioID: scope.radioID
      )
      await persistReactionIfNew(
        reactionDTO,
        dependencies: dependencies,
        directMessage: pending.directMessage ?? (scope, contact)
      )
    }

    try await updateDMUnreadsAndNotify(
      messageDTO: messageDTO,
      contact: contact,
      dependencies: dependencies,
      scope: scope
    )
  }

  /// Post-save side effects for a channel message: reaction indexing, channel
  /// bookkeeping, and unread/notification updates.
  private func indexAndNotifyChannelMessage(
    messageDTO: MessageDTO,
    channel: ChannelDTO?,
    indexesReactions: Bool = true,
    dependencies: SyncDependencies,
    scope: ReceiveScope
  ) async throws {
    guard scope.isCurrent, let channelIndex = messageDTO.channelIndex else { return }
    // A channel image is not a reaction target. Indexing it still runs so a
    // reaction queued before the image arrived drops that pending key.
    let isChannelImage = MCOImageCodec.isChannelImage(channelIndex: channelIndex, text: messageDTO.text)
    let senderName = messageDTO.senderNodeName ?? (isChannelImage ? "Unknown" : nil)
    if indexesReactions || isChannelImage, let senderName {
      let pendingMatches = await dependencies.reactionService.indexMessage(
        id: messageDTO.id,
        channelIndex: channelIndex,
        senderName: senderName,
        text: messageDTO.text,
        timestamp: messageDTO.reactionTimestamp
      )

      for pending in pendingMatches {
        guard scope.isCurrent else { return }
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

    guard scope.isCurrent else { return }
    if let channelID = channel?.id {
      try await dependencies.dataStore.updateChannelLastMessage(channelID: channelID, date: Date())
    }

    if !isBlockedSender(messageDTO.senderNodeName) {
      try await updateChannelUnreadsAndNotify(
        messageDTO: messageDTO,
        channel: channel,
        dependencies: dependencies,
        scope: scope
      )
    }
  }

  // MARK: - Signed Message Handler

  private func wireSignedMessageHandler(dependencies: SyncDependencies) async {
    await dependencies.messagePollingService.setSignedMessageHandler { [weak self] message, scope in
      guard let self else { return }
      await handleIncomingSignedMessage(message, scope: scope, dependencies: dependencies)
    }
  }

  private func handleIncomingSignedMessage(
    _ message: ContactMessage,
    scope: ReceiveScope,
    dependencies: SyncDependencies
  ) async {
    // For signed room messages, the signature contains the 4-byte author key prefix.
    guard let authorPrefix = message.signature?.prefix(4), authorPrefix.count == 4 else {
      logger.warning("Dropping signed message: missing or invalid author prefix")
      return
    }

    let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)
    do {
      guard let saved = try await dependencies.roomServerService.handleIncomingMessage(
              senderPublicKeyPrefix: message.senderPublicKeyPrefix,
              timestamp: timestamp,
              authorPrefix: Data(authorPrefix),
              text: message.text,
              scope: scope
            ), let room = try await dependencies.dataStore.fetchRemoteNodeSession(id: saved.room.id),
            room.radioID == saved.room.radioID,
            room.textRulesID == saved.room.textRulesID,
            scope.isCurrent else { return }

      await dependencies.notificationService.postRoomMessageNotification(
        roomName: room.name,
        sessionID: saved.message.sessionID,
        senderName: saved.message.authorName,
        messageText: saved.message.text,
        messageID: saved.message.id,
        notificationLevel: room.notificationLevel,
        scope: scope
      )
      guard scope.isCurrent else { return }
      await dependencies.notificationService.updateBadgeCount()
      await notifyConversationsChanged(scope: scope)
      guard scope.isCurrent else { return }
      dataEventBroadcaster.yield(.roomMessageReceived(saved.message, roomID: room.textRulesID, scope: scope))
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
      } else if contact.type == .repeater {
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
            .debug("B4 relay newContactDiscovered \(contactID) -> notifyContactsChanged")
          await dependencies.notificationService.postNewContactNotification(
            contactName: name,
            contactID: contactID,
            contactType: contactType
          )
          await notifyContactsChanged()
        case let .orphanDirectMessagesAdopted(contactIDs):
          // These DMs arrived before their sender's contact existed, so no
          // banner or badge fired at receipt. Adoption bumped the unread counts;
          // post the banner and refresh the badge now, once per contact. The
          // final badge refresh also covers a muted contact, whose unread rose
          // but whose suppressed banner never updates the badge itself.
          for contactID in contactIDs {
            do {
              guard let contact = try await dependencies.dataStore.fetchContact(id: contactID),
                    !contact.isBlocked,
                    let message = try await dependencies.dataStore.newestUnreadIncomingMessage(
                      contactID: contactID
                    )
              else { continue }
              await dependencies.notificationService.postDirectMessageNotification(
                from: contact.displayName,
                contactID: contactID,
                messageText: message.text,
                messageID: message.id,
                isMuted: contact.isMuted
              )
            } catch {
              logger.error(
                "Adopted DM notification failed for \(contactID): \(error.localizedDescription)"
              )
            }
          }
          await dependencies.notificationService.updateBadgeCount()
        case .contactUpdated, .conversationsChanged, .nodeStorageFullChanged,
             .contactDeletedCleanup, .pathDiscoveryResponse, .pathDiscoveryWriteFailed,
             .traceResponse, .traceSnrObserved:
          break
        }
      }
    }
  }

  /// Cancels the discovery event task so it releases the service references
  /// it captures. Called by `ServiceContainer.tearDown()`.
  func cancelDiscoveryEventMonitoring() async {
    let worker = discoveryEventsTask
    discoveryEventsTask = nil
    worker?.cancel()
    await worker?.value
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
