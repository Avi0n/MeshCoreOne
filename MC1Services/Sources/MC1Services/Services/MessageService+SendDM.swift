import Foundation
import MeshCore

// MARK: - Send Direct Message

public extension MessageService {
  /// Sends once; end-to-end ACK tracking continues after the method returns.
  func sendDirectMessage(
    text: String,
    to contact: ContactDTO,
    textType: TextType = .plain,
    replyToID: UUID? = nil
  ) async throws -> MessageDTO {
    // Counted before `session.sendMessage`. `RequestContext` releases its slot
    // before resuming this caller, and `retireInlineSends` waits without cancelling it.
    try beginInlineSend()
    defer { endInlineSend() }
    try validateDirectMessage(text: text, to: contact)

    let messageID = UUID()
    let timestamp = UInt32(Date().timeIntervalSince1970)

    let messageDTO = createOutgoingMessage(
      id: messageID,
      radioID: contact.radioID,
      contactID: contact.id,
      text: text,
      timestamp: timestamp,
      textType: textType,
      replyToID: replyToID
    )
    try await dataStore.saveMessage(messageDTO)

    // Persist before admission so a reply waiting on another send survives process death.
    let pendingSendID = UUID()
    do {
      _ = try await dataStore.insertPendingSendAssigningSequence(
        PendingSendDTO(
          id: pendingSendID,
          envelope: DirectMessageEnvelope(messageID: messageID, contactID: contact.id, isSingleAttempt: true),
          radioID: contact.radioID
        )
      )
    } catch {
      statusEventBroadcaster.yield(.failed(messageID: messageID))
      try await failMessageAndRethrow(error, messageID: messageID)
    }

    var sendLease: PlainTextSendGate.Lease?
    defer { await stopPlainTextLease(sendLease) }
    var ackOwnerCommitted = false
    defer {
      if !ackOwnerCommitted {
        // A row that already records an ACK deadline belongs to delivery tracking.
        // Deleting it would drop a send the radio has accepted.
        let stillUnsent: Bool = if let rows = try? await dataStore.fetchPendingSends(radioID: contact.radioID) {
          rows.first { $0.id == pendingSendID }?.ackDeadline == nil
        } else {
          true
        }
        if stillUnsent {
          try? await dataStore.deletePendingSend(id: pendingSendID)
        }
      }
    }
    let predictedAck: Data
    let sentInfo: MessageSentInfo
    do {
      sendLease = try await adoptPlainTextLease(nil)
      if sendLease != nil, try await dataStore.fetchMessage(id: messageID) == nil {
        throw CancellationError()
      }
      // A restart must preserve this timestamp once the command may have reached the radio.
      guard let attempted = try await dataStore.incrementPendingSendAttemptCount(messageID: messageID),
            attempted.pendingSendID == pendingSendID else {
        throw CancellationError()
      }
      // Track the predicted ACK before sending so a fast reply cannot outrun registration.
      guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
        throw MessageServiceError.notConnected
      }
      predictedAck = AckCodeBuilder.expectedAck(
        timestamp: timestamp,
        attempt: 0,
        text: text,
        senderPublicKey: senderPublicKey
      )
      // Keep speculative tracking alive until the command returns its ACK timeout.
      trackPendingAck(
        messageID: messageID,
        contactID: contact.id,
        ackCode: predictedAck,
        timeout: checkInterval,
        sendLeaseID: sendLease?.id
      )
      await notePlainTextLease(sendLease)

      do {
        sentInfo = try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.directMessageTableFull, config: config.poolBackoff, logger: logger) {
          try await session.sendMessage(
            to: contact.publicKey,
            text: text,
            timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
          )
        }
      } catch {
        await discardTrackedAck(messageID: messageID)
        throw error
      }
    } catch {
      statusEventBroadcaster.yield(.failed(messageID: messageID))
      try await failMessageAndRethrow(error, messageID: messageID)
    }

    #if DEBUG
      if let gate = inlineSendAcceptedGateForTesting { await gate() }
    #endif

    let ackTimeout = TimeInterval(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2

    if sentInfo.expectedAck != predictedAck {
      logger.warning(
        "expectedAck mismatch for \(messageID) attempt 0: predicted \(predictedAck.uppercaseHexString()) vs firmware \(sentInfo.expectedAck.uppercaseHexString()); merging firmware code"
      )
      trackPendingAck(
        messageID: messageID,
        contactID: contact.id,
        ackCode: sentInfo.expectedAck,
        timeout: ackTimeout,
        sendLeaseID: sendLease?.id
      )
    } else {
      pendingAcks[messageID]?.timeout = ackTimeout
      pendingAcks[messageID]?.sentAt = Date()
    }

    // The radio accepted the send; bookkeeping errors must leave terminal status
    // to the end-to-end ACK or expiry checker.
    do {
      if try await persistAcknowledgement(
        messageID: messageID, radioID: contact.radioID, pendingSendID: pendingSendID
      ) {
        ackOwnerCommitted = true
      }
      // updateMessageAck preserves `.delivered`, so writing `.sent` here
      // is a no-op when the listener already won the race.
      try await dataStore.updateMessageAck(
        id: messageID,
        ackCode: sentInfo.expectedAck.ackCodeUInt32,
        status: .sent
      )
      try await dataStore.updateContactLastMessage(contactID: contact.id, date: Date())
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
    } catch {
      logger.warning("DM post-send bookkeeping failed messageID=\(messageID) send already accepted: \(String(describing: error))")
    }

    guard let message = try await dataStore.fetchMessage(id: messageID) else {
      throw MessageServiceError.sendFailed("Failed to fetch saved message")
    }
    return message
  }

  // MARK: - Send with Automatic Retry

  /// Uses one `DMSendBudget` snapshot, resetting the out path once before fallback attempts.
  /// The radio selects the out path for each attempt, including after the reset.
  func sendMessageWithRetry(
    text: String,
    to contact: ContactDTO,
    textType: TextType = .plain,
    replyToID: UUID? = nil,
    timeout: TimeInterval = 0,
    onMessageCreated: (@Sendable (MessageDTO) async -> Void)? = nil
  ) async throws -> MessageDTO {
    try validateDirectMessage(text: text, to: contact)
    let sendLease = try await adoptPlainTextLease(nil)
    defer { await stopPlainTextLease(sendLease) }

    let messageID = UUID()
    let timestamp = UInt32(Date().timeIntervalSince1970)

    // Save message to store as pending first
    let messageDTO = createOutgoingMessage(
      id: messageID,
      radioID: contact.radioID,
      contactID: contact.id,
      text: text,
      timestamp: timestamp,
      textType: textType,
      replyToID: replyToID
    )
    try await dataStore.saveMessage(messageDTO)

    // Notify caller that message is saved
    await onMessageCreated?(messageDTO)

    // Capture initial routing state to detect changes
    let initialPathLength = contact.outPathLength

    // Run app-layer retry loop with UI notifications
    do {
      let sentInfo = try await sendDirectMessageWithRetryLoop(
        messageID: messageID,
        contactID: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        text: text,
        timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        timestampRaw: timestamp,
        timeout: timeout > 0 ? timeout : nil,
        budget: dmSendBudgetProvider(),
        sendLease: sendLease
      )

      return try await finalizeSend(
        messageID: messageID,
        contactID: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        sentInfo: sentInfo,
        initialPathLength: initialPathLength
      )
    } catch {
      statusEventBroadcaster.yield(.failed(messageID: messageID))
      try await failMessageAndRethrow(error, messageID: messageID)
    }
  }

  /// Creates a pending message without sending it.
  ///
  /// Use this when you want to show the message in the UI immediately
  /// and drain it later via ``sendPendingDirectMessage(messageID:to:preserveTimestamp:)``.
  ///
  /// - Parameters:
  ///   - text: The message text
  ///   - contact: The recipient contact
  ///   - textType: The type of text content (default: .plain)
  ///   - replyToID: Optional ID of message being replied to
  ///
  /// - Returns: The created message DTO with pending status
  func createPendingMessage(
    text: String,
    to contact: ContactDTO,
    textType: TextType = .plain,
    replyToID: UUID? = nil
  ) async throws -> MessageDTO {
    try validateDirectMessage(text: text, to: contact)

    let messageID = UUID()
    let timestamp = UInt32(Date().timeIntervalSince1970)

    let messageDTO = createOutgoingMessage(
      id: messageID,
      radioID: contact.radioID,
      contactID: contact.id,
      text: text,
      timestamp: timestamp,
      textType: textType,
      replyToID: replyToID
    )
    try await dataStore.saveMessage(messageDTO)
    try await dataStore.updateContactLastMessage(contactID: contact.id, date: messageDTO.date)

    return messageDTO
  }

  /// Drains a `.pending` DM through the app-layer retry loop. Does not
  /// bump `sendCount` or broadcast `.resent`; both bubble-affecting side
  /// effects belong to
  /// ``resendDirectMessage(messageID:to:preserveTimestamp:)``.
  ///
  /// - Parameter preserveTimestamp: True when a prior drain attempt may
  ///   already have placed the packet on the wire (queue auto-park-and-
  ///   retry), so the recipient's dedup ring catches the duplicate
  ///   rather than rendering it twice. False on the first drain, where
  ///   a fresh timestamp keeps mesh repeaters from filtering the packet.
  func sendPendingDirectMessage(
    messageID: UUID,
    to contact: ContactDTO,
    preserveTimestamp: Bool = false,
    pendingSendID: UUID? = nil
  ) async throws -> MessageDTO {
    try await performQueuedDirectMessageSend(
      messageID: messageID,
      to: contact,
      preserveTimestamp: preserveTimestamp,
      isResend: false,
      isSingleAttempt: false,
      pendingSendID: pendingSendID,
      sendLease: nil
    )
  }

  /// Resends an already-sent DM. Re-runs the app-layer retry loop,
  /// increments `sendCount`, and broadcasts `.resent` so the bubble
  /// surfaces "Sent N times".
  ///
  /// - Parameter preserveTimestamp: True on queue auto-park-and-retry
  ///   of the resend; false on the first drain attempt so the wire
  ///   packet hashes distinctly from the original and clears repeater
  ///   dedup rings.
  func resendDirectMessage(
    messageID: UUID,
    to contact: ContactDTO,
    preserveTimestamp: Bool = false,
    pendingSendID: UUID? = nil
  ) async throws -> MessageDTO {
    try await performQueuedDirectMessageSend(
      messageID: messageID,
      to: contact,
      preserveTimestamp: preserveTimestamp,
      isResend: true,
      isSingleAttempt: false,
      pendingSendID: pendingSendID,
      sendLease: nil
    )
  }

  internal func performQueuedDirectMessageSend(
    messageID: UUID,
    to contact: ContactDTO,
    preserveTimestamp: Bool,
    isResend: Bool,
    isSingleAttempt: Bool,
    pendingSendID: UUID?,
    sendLease providedLease: PlainTextSendGate.Lease?
  ) async throws -> MessageDTO {
    let sendLease = try await adoptPlainTextLease(providedLease)
    defer { await stopPlainTextLease(sendLease) }

    guard !inFlightRetries.contains(messageID) else {
      logger.warning("Send already in progress for message: \(messageID)")
      throw MessageServiceError.sendFailed("Retry already in progress")
    }

    inFlightRetries.insert(messageID)
    defer { inFlightRetries.remove(messageID) }

    let initialPathLength = contact.outPathLength

    guard let existingMessage = try await dataStore.fetchMessage(id: messageID) else {
      throw MessageServiceError.sendFailed("Message not found")
    }
    if isSingleAttempt, !isResend, existingMessage.status == .delivered || existingMessage.status == .failed {
      return existingMessage
    }

    // Fresh timestamp on first drain — mesh repeaters deduplicate by
    // packet content, so reusing the original would be dropped. Queue
    // auto-retry passes preserveTimestamp: true so the duplicate landing
    // on a recipient that already saw the first send gets caught by
    // their dedup ring rather than rendered as a second copy.
    let wireTimestamp: Date
    let wireTimestampRaw: UInt32
    if preserveTimestamp {
      wireTimestampRaw = existingMessage.timestamp
      wireTimestamp = Date(timeIntervalSince1970: TimeInterval(wireTimestampRaw))
    } else {
      wireTimestamp = Date()
      wireTimestampRaw = UInt32(wireTimestamp.timeIntervalSince1970)
      try await dataStore.updateMessageTimestamp(id: messageID, timestamp: wireTimestampRaw)
    }

    do {
      let sentInfo = try await sendDirectMessageWithRetryLoop(
        messageID: messageID,
        contactID: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        text: existingMessage.text,
        timestamp: wireTimestamp,
        timestampRaw: wireTimestampRaw,
        timeout: nil,
        budget: isSingleAttempt ? .singleAttempt : dmSendBudgetProvider(),
        sendLease: sendLease
      )

      // Bump only on a confirmed successful resend so sendCount counts
      // landed user-initiated sends, not queue-driven attempts;
      // bookkeeping failures log because the on-air retransmission
      // already happened.
      if isResend, sentInfo != nil {
        do {
          _ = try await dataStore.incrementMessageSendCount(id: messageID)
        } catch {
          logger.warning("DM resend sendCount bookkeeping failed messageID=\(messageID): \(String(describing: error))")
        }
      }

      let message = try await finalizeSend(
        messageID: messageID,
        contactID: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        sentInfo: sentInfo,
        initialPathLength: initialPathLength,
        pendingSendID: pendingSendID
      )

      // Broadcast after the DB write so the downstream refetch sees both
      // the bumped sendCount and the committed terminal status.
      if isResend, sentInfo != nil {
        statusEventBroadcaster.yield(.resent(messageID: messageID))
      }

      return message
    } catch {
      if error is CancellationError || Task.isCancelled {
        // ChatSendQueueService replays a cancelled send, so this must not mark the message failed.
        // Drop the tracked ACK so checkExpiredAcks cannot fail the row after the wait ends.
        await discardTrackedAck(messageID: messageID)
        throw CancellationError()
      }
      if case MessageServiceError.persistenceError = error { throw error }
      try await failMessageAndRethrow(error, messageID: messageID, pendingSendID: pendingSendID)
    }
  }

  // MARK: - Direct Message Retry Loop

  private func sendDirectMessageWithRetryLoop(
    messageID: UUID,
    contactID: UUID,
    radioID: UUID,
    publicKey: Data,
    text: String,
    timestamp: Date,
    timestampRaw: UInt32,
    timeout: TimeInterval?,
    budget: DMSendBudget,
    sendLease: PlainTextSendGate.Lease?
  ) async throws -> MessageSentInfo? {
    let maxRetryAttempts = budget.totalAttempts - 1

    for attemptIndex in 0..<budget.totalAttempts {
      guard !Task.isCancelled else {
        throw CancellationError()
      }

      if attemptIndex > 0 {
        try await dataStore.updateMessageRetryStatus(
          id: messageID,
          status: .retrying,
          retryAttempt: attemptIndex - 1,
          maxRetryAttempts: maxRetryAttempts
        )
        statusEventBroadcaster.yield(.retrying(
          messageID: messageID,
          attempt: attemptIndex - 1,
          maxAttempts: maxRetryAttempts
        ))
      }

      if attemptIndex == budget.directAttempts {
        logger.info("Resetting path to flood after \(attemptIndex) failed attempts")
        do {
          if let contactService {
            try await contactService.resetPath(radioID: radioID, publicKey: publicKey)
          } else {
            try await session.resetPath(publicKey: publicKey)
            _ = try await dataStore.updateContactRoute(
              radioID: radioID,
              publicKey: publicKey,
              outPathLength: PacketBuilder.floodPathSentinel,
              outPath: Data()
            )
          }
          if try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) != nil {
            statusEventBroadcaster.yield(.routingChanged(contactID: contactID, isFlood: true))
          }
        } catch {
          logger.warning("Failed to reset path: \(error.localizedDescription), continuing...")
        }
      }

      if attemptIndex > 0 {
        logger.info("Retry sending message: attempt \(attemptIndex + 1)/\(budget.totalAttempts)")
      }

      // Precompute the expected ACK CRC before the send so the persistent
      // ACK listener cannot race ahead of trackPendingAck on short direct links.
      guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
        throw MessageServiceError.notConnected
      }

      let predictedAck = AckCodeBuilder.expectedAck(
        timestamp: timestampRaw,
        attempt: UInt8(attemptIndex),
        text: text,
        senderPublicKey: senderPublicKey
      )

      // Pre-send floor must outlive one checkExpiredAcks tick; otherwise a
      // BLE round-trip > 1s could let the checker expire the speculative
      // entry before sendMessage returns and we overwrite the timeout with
      // the authoritative sentInfo-derived value.
      let preSendTimeout = max(timeout ?? config.minTimeout, checkInterval)
      trackPendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCode: predictedAck,
        timeout: preSendTimeout,
        sendLeaseID: sendLease?.id
      )
      await notePlainTextLease(sendLease)

      let sentInfo: MessageSentInfo
      do {
        sentInfo = try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.directMessageTableFull, config: config.poolBackoff, logger: logger) {
          try await session.sendMessage(
            to: publicKey.prefix(6),
            text: text,
            timestamp: timestamp,
            attempt: UInt8(attemptIndex)
          )
        }
      } catch {
        // Drop the whole speculative entry on send failure. A partial
        // remove would leave an empty-codes PendingAck visible to
        // checkExpiredAcks if failMessageAndRethrow is ever bypassed.
        await discardTrackedAck(messageID: messageID)
        throw error
      }

      // If the persistent ACK listener consumed our predicted ACK during
      // sendMessage's cross-actor suspension, the entry is already
      // removed or marked delivered. Short-circuit before waitForEvent
      // so the retry loop doesn't clobber .delivered via
      // updateMessageRetryStatus and broadcast a duplicate DM.
      guard let tracked = pendingAcks[messageID], !tracked.isDelivered else {
        return sentInfo
      }

      let ackTimeout = timeout ?? max(
        config.minTimeout,
        Double(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2
      )

      if sentInfo.expectedAck != predictedAck {
        logger.warning(
          "expectedAck mismatch for \(messageID) attempt \(attemptIndex): predicted \(predictedAck.uppercaseHexString()) vs firmware \(sentInfo.expectedAck.uppercaseHexString()); merging firmware code"
        )
        trackPendingAck(
          messageID: messageID,
          contactID: contactID,
          ackCode: sentInfo.expectedAck,
          timeout: ackTimeout,
          sendLeaseID: sendLease?.id
        )
      } else {
        // Re-stamp sentAt so checkExpiredAcks measures timeout from
        // send-return, not from the speculative insert.
        pendingAcks[messageID]?.timeout = ackTimeout
        pendingAcks[messageID]?.sentAt = Date()
      }

      let ackEvent = await session.waitForEvent(
        filter: .acknowledgement(code: sentInfo.expectedAck),
        timeout: ackTimeout
      )

      if ackEvent != nil {
        logger.info("Message acknowledged on attempt \(attemptIndex + 1)")
        return sentInfo
      }

      // Listener may have consumed the ACK between the pre-send guard
      // and waitForEvent's subscription becoming active; re-check before
      // retrying so we don't resend a DM the firmware already delivered.
      if pendingAcks[messageID]?.isDelivered != false {
        return sentInfo
      }
    }

    logger.warning("Message delivery failed after \(budget.totalAttempts) attempts")
    return nil
  }

  // MARK: - Routing Change Detection

  /// Checks if contact routing changed and broadcasts `.routingChanged` if so.
  ///
  /// Called after sendMessageWithRetry to detect if routing switched
  /// between direct and flood modes during the retry process.
  private func checkAndNotifyRoutingChange(
    publicKey: Data,
    contactID: UUID,
    radioID: UUID,
    initialPathLength: UInt8
  ) async {
    do {
      let newPathLength: UInt8?
      if let contactService {
        newPathLength = try await contactService.mirrorObservedRoute(radioID: radioID, publicKey: publicKey)
      } else if let updatedContact = try await session.getContact(publicKey: publicKey) {
        _ = try await dataStore.updateContactRoute(
          radioID: radioID,
          publicKey: publicKey,
          outPathLength: updatedContact.outPathLength,
          outPath: updatedContact.outPath
        )
        newPathLength = updatedContact.outPathLength
      } else {
        newPathLength = nil
      }
      guard let newPathLength else {
        logger.info("Contact not found in device contacts after retry")
        return
      }

      if newPathLength != initialPathLength {
        logger.info("Routing changed for contact \(contactID): \(initialPathLength) -> \(newPathLength)")
        let isNowFlood = newPathLength == PacketBuilder.floodPathSentinel
        statusEventBroadcaster.yield(.routingChanged(contactID: contactID, isFlood: isNowFlood))
      }
    } catch {
      logger.warning("Failed to check routing change: \(error.localizedDescription)")
    }
  }

  // MARK: - Private Helpers

  /// Records a new expected-ACK code for a message awaiting delivery.
  ///
  /// On the first call for a given `messageID` this creates the entry; on
  /// subsequent retry attempts it adds the new `ackCode` to the same entry's
  /// `ackCodes` set and resets both `sentAt` and the timeout window to the
  /// latest attempt so `checkExpiredAcks` does not fail the in-flight retry.
  internal func trackPendingAck(
    messageID: UUID,
    contactID: UUID,
    ackCode: Data,
    timeout: TimeInterval,
    sendLeaseID: UUID? = nil
  ) {
    // Diagnostic: the firmware ACK CRC is derived from (timestamp, attempt,
    // text, sender key) with no recipient, so identical text sent in the
    // same second to different contacts produces the same code. Counting
    // collisions across distinct in-flight messages measures how often a
    // delivery confirmation could be attributed to the wrong message.
    if let colliding = pendingAcks.first(where: { $0.key != messageID && $0.value.ackCodes.contains(ackCode) }) {
      logger.warning("[ack-diag] ackCode collision: code=\(ackCode.uppercaseHexString()) shared by messages \(colliding.key) and \(messageID)")
    }
    if var existing = pendingAcks[messageID] {
      existing.ackCodes.insert(ackCode)
      existing.sentAt = Date()
      existing.timeout = timeout
      existing.pendingSendID = nil
      existing.deadline = nil
      if let sendLeaseID {
        existing.sendLeaseID = sendLeaseID
      }
      pendingAcks[messageID] = existing
    } else {
      pendingAcks[messageID] = PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: timeout,
        sendLeaseID: sendLeaseID
      )
    }
  }

  private func validateDirectMessage(text: String, to contact: ContactDTO) throws {
    guard contact.type != .repeater, contact.type != .sensor else { throw MessageServiceError.invalidRecipient }
    guard text.utf8.count <= ProtocolLimits.maxDirectMessageLength else { throw MessageServiceError.messageTooLong }
  }

  internal func finalizeSend(
    messageID: UUID,
    contactID: UUID,
    radioID: UUID,
    publicKey: Data,
    sentInfo: MessageSentInfo?,
    initialPathLength: UInt8,
    pendingSendID: UUID? = nil
  ) async throws -> MessageDTO {
    // Peek the pendingAcks entry without taking ownership. A missing entry
    // means `handleAcknowledgement` already processed the ACK (it removes
    // the entry on delivery) or `checkExpiredAcks` already failed and
    // removed it; an `isDelivered == true` entry means the listener marked
    // it mid-flight. In those cases the owner holds the terminal write
    // (including `roundTripTime`) and we drop any residual entry and skip
    // the DB update here.
    let tracking = pendingAcks[messageID]

    if tracking == nil || tracking?.isDelivered == true {
      let leaseID = tracking?.sendLeaseID
      pendingAcks.removeValue(forKey: messageID)
      await settlePlainTextLease(leaseID)
    } else if let sentInfo {
      // The retry loop's waitForEvent matched the ACK. Take ownership and
      // record the legitimate `.sent` -> `.delivered` upgrade.
      let leaseID = tracking?.sendLeaseID
      pendingAcks.removeValue(forKey: messageID)
      await settlePlainTextLease(leaseID)
      try await dataStore.updateMessageAck(
        id: messageID,
        ackCode: sentInfo.expectedAck.ackCodeUInt32,
        status: .delivered
      )
      try await dataStore.updateContactLastMessage(contactID: contactID, date: Date())
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .delivered, roundTripTime: nil))
    } else {
      // Retry budget spent without an ACK. Neither fail the row nor remove
      // the entry: `checkExpiredAcks` owns the give-up at
      // max(ackGiveUpWindow, last attempt's ACK timeout), so a
      // late-but-legitimate ACK can still upgrade the row.
      // The original PendingSend becomes a durable ACK owner in the same
      // transaction that clears `.retrying`; a replacement or terminal row
      // cannot be adopted by this send.
      let movedToSent = try await persistAcknowledgement(
        messageID: messageID, radioID: radioID, pendingSendID: pendingSendID
      )
      if movedToSent {
        statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
      }
    }
    await checkAndNotifyRoutingChange(
      publicKey: publicKey,
      contactID: contactID,
      radioID: radioID,
      initialPathLength: initialPathLength
    )
    guard let message = try await dataStore.fetchMessage(id: messageID) else {
      throw MessageServiceError.sendFailed("Failed to fetch message")
    }
    return message
  }

  private func createOutgoingMessage(
    id: UUID,
    radioID: UUID,
    contactID: UUID,
    text: String,
    timestamp: UInt32,
    textType: TextType,
    replyToID: UUID?
  ) -> MessageDTO {
    let message = Message(
      id: id,
      radioID: radioID,
      contactID: contactID,
      text: text,
      timestamp: timestamp,
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: MessageStatus.pending.rawValue,
      textTypeRawValue: textType.rawValue,
      replyToID: replyToID
    )
    return MessageDTO(from: message)
  }
}
