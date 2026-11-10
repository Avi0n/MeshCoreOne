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
    defer {
      if pendingAcks[messageID]?.owner == nil {
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
      guard let attempted = try await dataStore.preparePendingSendAttempt(messageID: messageID),
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
      await trackPendingAck(
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

    let ackTimeout = dmAckTimeout(for: sentInfo)

    await recordDMCommandAcceptance(
      messageID: messageID, sentInfo: sentInfo, predictedAck: predictedAck, timeout: ackTimeout
    )
    _ = await consumeDirectMessageWireAttempt(0, pendingSendID: pendingSendID)

    // The radio accepted the send; bookkeeping errors must leave terminal status
    // to the end-to-end ACK or expiry checker.
    do {
      await persistAcknowledgement(
        messageID: messageID, radioID: contact.radioID, pendingSendID: pendingSendID
      )
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
      let acknowledgedCode = try await sendDirectMessageWithRetryLoop(
        messageID: messageID,
        to: contact,
        text: text,
        timestamp: timestamp,
        timeout: timeout > 0 ? timeout : nil,
        budget: dmSendBudgetProvider(),
        sendLease: sendLease,
        waitForAcknowledgement: true
      )

      return try await finalizeSend(
        messageID: messageID,
        contactID: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        acknowledgedCode: acknowledgedCode,
        initialPathLength: initialPathLength
      )
    } catch {
      statusEventBroadcaster.yield(.failed(messageID: messageID))
      try await failMessageAndRethrow(error, messageID: messageID)
    }
  }

  /// Saves the pending Message and updates contact recency before the caller publishes it.
  internal func createPendingMessage(
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

  /// Drains a pending direct message without bumping sendCount or broadcasting `.resent`.
  /// - Parameter preserveTimestamp: True continues at the stored attempt byte. False starts a new timestamp at attempt 0.
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

  /// Resends a direct message, increments sendCount, and broadcasts `.resent`.
  /// - Parameter preserveTimestamp: True continues at the stored attempt byte. False starts a new timestamp at attempt 0.
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

    // The mesh seen table keys on ciphertext hash. A preserved timestamp continues
    // at the next unused attempt byte. A new timestamp is a new packet on this message.
    let wireTimestamp: UInt32
    if preserveTimestamp {
      if existingMessage.status == .delivered {
        return existingMessage
      }
      if let pendingSendID {
        guard let stored = try await dataStore.pendingSendWireAttempt(id: pendingSendID) else {
          guard let current = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Message not found")
          }
          return current
        }
        if stored >= AckCodeBuilder.attemptCount {
          return try await handOffExhaustedDirectMessage(
            messageID: messageID,
            contact: contact,
            message: existingMessage,
            pendingSendID: pendingSendID
          )
        }
      }
      wireTimestamp = existingMessage.timestamp
    } else {
      wireTimestamp = UInt32(Date().timeIntervalSince1970)
    }

    do {
      let acknowledgedCode = try await sendDirectMessageWithRetryLoop(
        messageID: messageID,
        to: contact,
        text: existingMessage.text,
        timestamp: wireTimestamp,
        timeout: nil,
        budget: isSingleAttempt || existingMessage.reactionTargetMessageID != nil ? .singleAttempt : dmSendBudgetProvider(),
        sendLease: sendLease,
        waitForAcknowledgement: existingMessage.reactionTargetMessageID == nil,
        pendingSendID: pendingSendID,
        preserveTimestamp: preserveTimestamp
      )

      // Bump only on a confirmed successful resend so sendCount counts
      // landed user-initiated sends, not queue-driven attempts;
      // bookkeeping failures log because the on-air retransmission
      // already happened.
      if isResend, acknowledgedCode != nil {
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
        acknowledgedCode: acknowledgedCode,
        initialPathLength: initialPathLength,
        pendingSendID: pendingSendID
      )

      // Broadcast after the DB write so the downstream refetch sees both
      // the bumped sendCount and the committed terminal status.
      if isResend, acknowledgedCode != nil {
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
      // Not submitted and table full stay queued. Failing a restored delivery throws
      // CancellationError, and the drain would retry that send immediately.
      if parksQueuedDirectMessage(error) {
        await discardTrackedAck(messageID: messageID)
        throw error
      }
      try await failMessageAndRethrow(error, messageID: messageID, pendingSendID: pendingSendID)
    }
  }

  /// Installs an acknowledgement owner for attempts already handed to the radio.
  /// A missing sender key throws before any write so the queue can retry the handoff.
  private func handOffExhaustedDirectMessage(
    messageID: UUID,
    contact: ContactDTO,
    message: MessageDTO,
    pendingSendID: UUID
  ) async throws -> MessageDTO {
    guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
      throw MessageServiceError.notConnected
    }
    var ackCodes = Set<Data>()
    for attempt in 0..<AckCodeBuilder.attemptCount {
      ackCodes.insert(AckCodeBuilder.expectedAck(
        timestamp: message.timestamp,
        attempt: attempt,
        text: message.text,
        senderPublicKey: senderPublicKey
      ))
    }
    let ackTimeout = max(config.minTimeout, config.ackGiveUpWindow)
    let sentAt = Date()
    pendingAcks[messageID] = PendingAck(
      messageID: messageID,
      contactID: contact.id,
      ackCodes: ackCodes,
      sentAt: sentAt,
      timeout: ackTimeout
    )
    let saved = await persistAcknowledgement(
      messageID: messageID, radioID: contact.radioID, pendingSendID: pendingSendID
    )
    guard let current = try await dataStore.fetchMessage(id: messageID) else {
      throw MessageServiceError.sendFailed("Message not found")
    }
    if saved, current.status == .sent {
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
    }
    return current
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

  /// Accumulates expected ACK codes while awaiting delivery and renews the attempt's timing.
  /// `checkExpiredAcks` then preserves the in-flight retry until its deadline.
  internal func trackPendingAck(
    messageID: UUID,
    contactID: UUID,
    ackCode: Data,
    timeout: TimeInterval,
    sendLeaseID: UUID? = nil,
    replacingDeliveredTimestamp: Bool = false
  ) async {
    if !replacingDeliveredTimestamp,
       await (try? dataStore.fetchMessage(id: messageID))?.status == .delivered {
      return
    }
    // ACK CRCs omit the recipient, so identical text sent in one second can collide across Contacts.
    // Log collisions to diagnose acknowledgements attributed to the wrong DM.
    if let colliding = pendingAcks.first(where: { $0.key != messageID && $0.value.ackCodes.contains(ackCode) }) {
      logger.warning("[ack-diag] ackCode collision: code=\(ackCode.uppercaseHexString()) shared by messages \(colliding.key) and \(messageID)")
    }
    if var existing = pendingAcks[messageID] {
      guard existing.isAwaitingAck else { return }
      existing.ackCodes.insert(ackCode)
      existing.sentAt = Date()
      existing.timeout = timeout
      existing.resetOwner()
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

  func validateDirectMessage(text: String, to contact: ContactDTO) throws {
    guard contact.type != .repeater, contact.type != .sensor else { throw MessageServiceError.invalidRecipient }
    guard text.utf8.count <= ProtocolLimits.maxDirectMessageLength else { throw MessageServiceError.messageTooLong }
  }

  internal func finalizeSend(
    messageID: UUID,
    contactID: UUID,
    radioID: UUID,
    publicKey: Data,
    acknowledgedCode: Data?,
    initialPathLength: UInt8,
    pendingSendID: UUID? = nil
  ) async throws -> MessageDTO {
    // A missing or resolved entry leaves terminal bookkeeping with the ACK listener
    // or expiry checker, including the committed round-trip time.
    let tracking = pendingAcks[messageID]

    if tracking == nil || tracking?.isResolved == true {
      let leaseID = tracking?.sendLeaseID
      pendingAcks.removeValue(forKey: messageID)
      await settlePlainTextLease(leaseID)
    } else {
      if let acknowledgedCode, tracking?.isAwaitingAck == true {
        pendingAcks[messageID]?.recordAcknowledgement(code: acknowledgedCode.ackCodeUInt32, tripTime: nil)
      }
      // Command issuance ended; durable ACK ownership preserves late matches
      // and retries a failed owner save without submitting another command.
      let saved = await persistAcknowledgement(
        messageID: messageID, radioID: radioID, pendingSendID: pendingSendID
      )
      if saved, await (try? dataStore.fetchMessage(id: messageID))?.status == .sent {
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
    if message.reactionTargetMessageID != nil {
      await notifyOutgoingReactionAccepted(messageID: messageID)
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
