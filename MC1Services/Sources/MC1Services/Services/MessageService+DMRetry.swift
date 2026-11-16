import Foundation
import MeshCore

private let dmAckTimeoutHeadroom: Double = 1.2
private let millisecondsPerSecond: Double = 1000

extension MessageService {
  // MARK: - Direct Message Retry Loop

  func sendDirectMessageWithRetryLoop(
    messageID: UUID,
    to contact: ContactDTO,
    text: String,
    timestamp: UInt32,
    timeout: TimeInterval?,
    budget: DMSendBudget,
    sendLease: PlainTextSendGate.Lease?,
    waitForAcknowledgement: Bool,
    pendingSendID: UUID? = nil,
    preserveTimestamp: Bool = true
  ) async throws -> Data? {
    let maxRetryAttempts = budget.totalAttempts - 1
    let sendDate = Date(timeIntervalSince1970: TimeInterval(timestamp))
    var lastExpectedAck: Data?
    var wireAttempt = 0
    var submissions = 0
    var didResetPath = false
    var commitWireTimestamp = !preserveTimestamp
    var replacedDeliveryTimestamp: UInt32?
    if preserveTimestamp, let pendingSendID {
      guard let stored = try await dataStore.pendingSendWireAttempt(id: pendingSendID) else { return nil }
      if stored >= AckCodeBuilder.attemptCount { return nil }
      wireAttempt = Int(stored)
    }

    if wireAttempt > 0 {
      await trackConsumedDirectMessageAttempts(
        messageID: messageID,
        contactID: contact.id,
        text: text,
        timestamp: timestamp,
        startingAttempt: UInt8(wireAttempt),
        timeout: timeout,
        sendLeaseID: sendLease?.id,
        replacingDeliveredTimestamp: commitWireTimestamp
      )
    }

    while submissions < budget.totalAttempts, wireAttempt < Int(AckCodeBuilder.attemptCount) {
      if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: commitWireTimestamp) {
        return lastExpectedAck
      }
      guard !Task.isCancelled else {
        throw CancellationError()
      }

      if lastExpectedAck != nil, pendingAcks[messageID]?.isAwaitingAck != true { return lastExpectedAck }

      if submissions > 0 {
        try await dataStore.updateMessageRetryStatus(
          id: messageID,
          status: .retrying,
          retryAttempt: submissions - 1,
          maxRetryAttempts: maxRetryAttempts
        )
        statusEventBroadcaster.yield(.retrying(
          messageID: messageID,
          attempt: submissions - 1,
          maxAttempts: maxRetryAttempts
        ))
      }

      if budget.floodAttempts > 0, wireAttempt >= budget.directAttempts, !didResetPath {
        logger.info("Resetting path to flood before attempt \(wireAttempt)")
        didResetPath = true
        do {
          if let contactService {
            try await contactService.resetPath(radioID: contact.radioID, publicKey: contact.publicKey)
          } else {
            try await session.resetPath(publicKey: contact.publicKey)
            _ = try await dataStore.updateContactRoute(
              radioID: contact.radioID,
              publicKey: contact.publicKey,
              outPathLength: PacketBuilder.floodPathSentinel,
              outPath: Data()
            )
          }
          if try await dataStore.fetchContact(radioID: contact.radioID, publicKey: contact.publicKey) != nil {
            statusEventBroadcaster.yield(.routingChanged(contactID: contact.id, isFlood: true))
          }
        } catch {
          logger.warning("Failed to reset path: \(error.localizedDescription), continuing...")
        }
      }

      if submissions > 0 {
        logger.info("Retry sending message: attempt \(wireAttempt + 1)/\(budget.totalAttempts)")
      }

      // Precompute the expected ACK CRC before the send so the persistent
      // ACK listener cannot race ahead of trackPendingAck on short direct links.
      guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
        throw MessageServiceError.notConnected
      }
      if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: commitWireTimestamp) {
        return lastExpectedAck
      }

      if lastExpectedAck != nil, pendingAcks[messageID]?.isAwaitingAck != true { return lastExpectedAck }
      let previousAck = pendingAcks[messageID]
      let attemptByte = UInt8(wireAttempt)

      let predictedAck = AckCodeBuilder.expectedAck(
        timestamp: timestamp,
        attempt: attemptByte,
        text: text,
        senderPublicKey: senderPublicKey
      )

      // Pre-send floor must outlive one checkExpiredAcks tick; otherwise a
      // BLE round-trip > 1s could let the checker expire the speculative
      // entry before sendMessage returns and we overwrite the timeout with
      // the authoritative sentInfo-derived value.
      let preSendTimeout = max(timeout ?? config.minTimeout, checkInterval)
      await trackPendingAck(
        messageID: messageID,
        contactID: contact.id,
        ackCode: predictedAck,
        timeout: preSendTimeout,
        sendLeaseID: sendLease?.id,
        replacingDeliveredTimestamp: commitWireTimestamp
      )
      await notePlainTextLease(sendLease)

      #if DEBUG
        if let gate = directMessageSubmitGateForTesting {
          await gate(attemptByte)
        }
      #endif
      if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: commitWireTimestamp) {
        return lastExpectedAck
      }
      guard pendingAcks[messageID]?.isAwaitingAck == true else { return lastExpectedAck }
      if commitWireTimestamp {
        replacedDeliveryTimestamp = try await dataStore.commitOutgoingDirectMessageTimestamp(
          id: messageID, timestamp: timestamp
        )
        commitWireTimestamp = false
      }

      let sentInfo: MessageSentInfo?
      let rollbackTimestamp = replacedDeliveryTimestamp
      do {
        sentInfo = try await withPoolBackoff(
          transientCode: FirmwareDeviceErrorCode.directMessageTableFull,
          config: config.poolBackoff,
          logger: logger
        ) { () async throws -> MessageSentInfo? in
          try await self.submitDMCommand(
            messageID: messageID, publicKey: contact.publicKey, text: text,
            timestamp: sendDate, attempt: attemptByte,
            replacedDeliveryTimestamp: rollbackTimestamp, pendingSendID: pendingSendID
          )
        }
        replacedDeliveryTimestamp = nil
      } catch {
        return try await recoverFailedDirectMessageAttempt(
          error,
          messageID: messageID,
          attempt: attemptByte,
          pendingSendID: pendingSendID,
          previousTimestamp: replacedDeliveryTimestamp,
          lastExpectedAck: lastExpectedAck,
          previousAck: previousAck,
          sendLeaseID: sendLease?.id,
          wireAttempt: wireAttempt
        )
      }
      guard let sentInfo else { return lastExpectedAck }
      lastExpectedAck = sentInfo.expectedAck
      guard pendingAcks[messageID]?.isAwaitingAck == true else { return lastExpectedAck }

      let ackTimeout = timeout ?? max(config.minTimeout, dmAckTimeout(for: sentInfo))
      // Subscribe before the attempt byte is saved. An acknowledgement that
      // arrives during that save stays buffered for the wait below.
      let (subscriptionID, ackStream) = await session.eventsTracked(
        filter: .acknowledgement(code: sentInfo.expectedAck)
      )
      var consumed = true
      let stillWaiting = await noteAcceptedDirectMessageAttempt(
        messageID: messageID,
        sentInfo: sentInfo,
        predictedAck: predictedAck,
        timeout: ackTimeout,
        attempt: attemptByte,
        pendingSendID: pendingSendID,
        consumed: &consumed
      )
      if !stillWaiting {
        await session.finishEvents(id: subscriptionID)
        return lastExpectedAck
      }
      if !waitForAcknowledgement {
        await session.finishEvents(id: subscriptionID)
        return nil
      }
      let ackEvent = await nextDirectMessageAcknowledgement(ackStream, timeout: ackTimeout)
      await session.finishEvents(id: subscriptionID)

      if ackEvent != nil || pendingAcks[messageID]?.isAwaitingAck != true {
        return lastExpectedAck
      }
      // A missed attempt-byte save is not an end-to-end ACK. The tracker still holds the predicted code.
      guard consumed else { return nil }
      wireAttempt += 1
      submissions += 1
    }

    logger.info("DM retry budget spent messageID=\(messageID) attempts=\(budget.totalAttempts)")
    return nil
  }

  private func recoverFailedDirectMessageAttempt(
    _ error: Error,
    messageID: UUID,
    attempt: UInt8,
    pendingSendID: UUID?,
    previousTimestamp: UInt32?,
    lastExpectedAck: Data?,
    previousAck: PendingAck?,
    sendLeaseID: UUID?,
    wireAttempt: Int
  ) async throws -> Data? {
    if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: false) {
      return lastExpectedAck
    }
    if isDirectMessageCommandTimeout(error) {
      _ = await consumeDirectMessageWireAttempt(attempt, pendingSendID: pendingSendID)
      if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: false) {
        if let pendingSendID,
           await (try? dataStore.fetchMessage(id: messageID))?.status == .delivered {
          try? await dataStore.deletePendingSend(id: pendingSendID)
        }
        return lastExpectedAck
      }
    } else if isUnsubmittedDirectMessage(error) {
      if let previousTimestamp {
        _ = try await restoreDeliveredDirectMessage(messageID: messageID, previousTimestamp: previousTimestamp)
      }
      await discardTrackedAck(messageID: messageID)
      throw error
    } else if let previousTimestamp,
              try await restoreRejectedDelivery(
                error, messageID: messageID, pendingSendID: pendingSendID,
                previousTimestamp: previousTimestamp
              ) {
      return lastExpectedAck
    }
    if lastExpectedAck != nil, !Task.isCancelled,
       retainAcceptedDMAfterCommandFailure(
         error, messageID: messageID, previousAck: previousAck, sendLeaseID: sendLeaseID
       ) {
      logger.warning("DM command issuance ended messageID=\(messageID) attempt=\(wireAttempt): \(String(describing: error))")
      return pendingAcks[messageID]?.isAwaitingAck == true ? nil : lastExpectedAck
    }
    await discardTrackedAck(messageID: messageID)
    throw error
  }

  /// Returns false when this attempt is already resolved and the loop should stop.
  private func noteAcceptedDirectMessageAttempt(
    messageID: UUID,
    sentInfo: MessageSentInfo,
    predictedAck: Data,
    timeout: TimeInterval,
    attempt: UInt8,
    pendingSendID: UUID?,
    consumed: inout Bool
  ) async -> Bool {
    if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: false) {
      return false
    }
    consumed = await consumeDirectMessageWireAttempt(attempt, pendingSendID: pendingSendID)
    guard pendingAcks[messageID]?.isAwaitingAck == true else { return false }
    await recordDMCommandAcceptance(
      messageID: messageID, sentInfo: sentInfo, predictedAck: predictedAck, timeout: timeout
    )
    return true
  }

  /// Reads one acknowledgement from the subscription opened before attempt bookkeeping.
  private func nextDirectMessageAcknowledgement(
    _ stream: AsyncStream<MeshEvent>,
    timeout: TimeInterval
  ) async -> MeshEvent? {
    let sessionClock = (session as? MeshCoreSession)?.clock
    return await withTaskGroup(of: MeshEvent?.self) { group in
      group.addTask {
        for await event in stream {
          return event
        }
        return nil
      }
      group.addTask {
        if let sessionClock {
          try? await sessionClock.sleep(for: .seconds(timeout))
        } else {
          try? await Task.sleep(for: .seconds(timeout))
        }
        return nil
      }
      let result = await group.next() ?? nil
      group.cancelAll()
      return result
    }
  }

  private func submitDMCommand(
    messageID: UUID,
    publicKey: Data,
    text: String,
    timestamp: Date,
    attempt: UInt8,
    replacedDeliveryTimestamp: UInt32?,
    pendingSendID: UUID?
  ) async throws -> MessageSentInfo? {
    if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: false) { return nil }
    guard pendingAcks[messageID]?.isAwaitingAck == true else { return nil }
    // The last submission may have reached the radio even when its response times out.
    pendingAcks[messageID]?.sentAt = Date()
    do {
      return try await session.sendMessage(to: publicKey.prefix(6), text: text, timestamp: timestamp, attempt: attempt)
    } catch {
      if isDirectMessageTableFull(error) { throw error }
      if let replacedDeliveryTimestamp,
         try await restoreRejectedDelivery(
           error, messageID: messageID, pendingSendID: pendingSendID,
           previousTimestamp: replacedDeliveryTimestamp
         ) {
        return nil
      }
      throw error
    }
  }

  func recordDMCommandAcceptance(
    messageID: UUID, sentInfo: MessageSentInfo, predictedAck: Data, timeout: TimeInterval
  ) async {
    pendingAcks[messageID]?.commandAccepted = true
    guard let tracking = pendingAcks[messageID], tracking.isAwaitingAck else { return }
    if sentInfo.expectedAck != predictedAck {
      logger.warning("DM expected ACK differs from prediction messageID=\(messageID)")
      await trackPendingAck(
        messageID: messageID, contactID: tracking.contactID, ackCode: sentInfo.expectedAck, timeout: timeout
      )
    } else {
      pendingAcks[messageID]?.sentAt = Date()
      pendingAcks[messageID]?.timeout = timeout
    }
  }

  func dmAckTimeout(for sentInfo: MessageSentInfo) -> TimeInterval {
    Double(sentInfo.suggestedTimeoutMs) / millisecondsPerSecond * dmAckTimeoutHeadroom
  }

  private func retainAcceptedDMAfterCommandFailure(
    _ error: Error, messageID: UUID, previousAck: PendingAck?, sendLeaseID: UUID?
  ) -> Bool {
    guard let error = error as? MeshCoreError else { return false }
    let rejected: Bool
    switch error {
    case .deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull):
      rejected = true
    case .timeout:
      rejected = false
    default:
      return false
    }
    guard let previousAck, let current = pendingAcks[messageID],
          current.sendLeaseID == sendLeaseID else { return true }
    guard current.isAwaitingAck else { return true }
    if rejected {
      // A rejected command keeps the last accepted attempt's codes and deadline.
      pendingAcks[messageID]?.ackCodes = previousAck.ackCodes
      pendingAcks[messageID]?.sentAt = previousAck.sentAt
      pendingAcks[messageID]?.timeout = previousAck.timeout
    } else {
      // A missing host reply leaves this attempt's predicted ACK eligible from its submission time.
      let earlierDeadline = previousAck.expiresAt(giveUpWindow: config.ackGiveUpWindow)
      pendingAcks[messageID]?.timeout = max(previousAck.timeout, earlierDeadline.timeIntervalSince(current.sentAt))
    }
    return true
  }

  /// A definite rejection did not queue the packet, so restore the delivered row and stop.
  private func restoreRejectedDelivery(
    _ error: Error,
    messageID: UUID,
    pendingSendID: UUID?,
    previousTimestamp: UInt32
  ) async throws -> Bool {
    guard isRejectedDirectMessageCommand(error) else { return false }
    let restored = try await restoreDeliveredDirectMessage(
      messageID: messageID, previousTimestamp: previousTimestamp
    )
    guard restored else { return false }
    if let pendingSendID {
      try? await dataStore.deletePendingSend(id: pendingSendID)
    }
    await discardTrackedAck(messageID: messageID)
    return true
  }

  /// Puts the committed timestamp back and tells an open chat the row is delivered again.
  private func restoreDeliveredDirectMessage(messageID: UUID, previousTimestamp: UInt32) async throws -> Bool {
    let restored = try await dataStore.restoreUnacceptedDeliveredDirectMessage(
      id: messageID, timestamp: previousTimestamp
    )
    guard restored else { return false }
    statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .delivered, roundTripTime: nil))
    return true
  }

  /// Not submitted and table full stay queued so the drain can retry them.
  func parksQueuedDirectMessage(_ error: Error) -> Bool {
    isUnsubmittedDirectMessage(error) || isDirectMessageTableFull(error)
  }

  private func isDirectMessageTableFull(_ error: Error) -> Bool {
    switch error {
    case MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull):
      true
    case let MessageServiceError.sessionError(underlying):
      isDirectMessageTableFull(underlying)
    default:
      false
    }
  }

  /// A host timeout means the command may already be queued. Table full is parked, not restored.
  /// Any other device error is a definite rejection.
  private func isRejectedDirectMessageCommand(_ error: Error) -> Bool {
    switch error {
    case MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull):
      false
    case MeshCoreError.deviceError:
      true
    case let MessageServiceError.sessionError(underlying):
      isRejectedDirectMessageCommand(underlying)
    default:
      false
    }
  }

  private func isUnsubmittedDirectMessage(_ error: Error) -> Bool {
    switch error {
    case MeshCoreError.notConnected:
      true
    case let MessageServiceError.sessionError(underlying):
      isUnsubmittedDirectMessage(underlying)
    default:
      false
    }
  }

  private func isDirectMessageCommandTimeout(_ error: Error) -> Bool {
    switch error {
    case MeshCoreError.timeout:
      true
    case let MessageServiceError.sessionError(underlying):
      isDirectMessageCommandTimeout(underlying)
    default:
      false
    }
  }

  /// Attempt bytes already handed to the radio for this timestamp stay matchable before the next command.
  private func trackConsumedDirectMessageAttempts(
    messageID: UUID,
    contactID: UUID,
    text: String,
    timestamp: UInt32,
    startingAttempt: UInt8,
    timeout: TimeInterval?,
    sendLeaseID: UUID?,
    replacingDeliveredTimestamp: Bool
  ) async {
    guard startingAttempt > 0, let senderPublicKey = await session.currentSelfInfo?.publicKey else { return }
    if await directMessageSendShouldStop(messageID, replacingDeliveredTimestamp: replacingDeliveredTimestamp) {
      return
    }
    let preSendTimeout = max(timeout ?? config.minTimeout, checkInterval)
    for prior in 0..<startingAttempt {
      await trackPendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCode: AckCodeBuilder.expectedAck(
          timestamp: timestamp,
          attempt: prior,
          text: text,
          senderPublicKey: senderPublicKey
        ),
        timeout: preSendTimeout,
        sendLeaseID: sendLeaseID,
        replacingDeliveredTimestamp: replacingDeliveredTimestamp
      )
    }
  }

  /// Stops another command when this attempt is already resolved or the row is delivered.
  /// A resend that still has to commit its new timestamp is not stopped by the old delivery.
  private func directMessageSendShouldStop(_ messageID: UUID, replacingDeliveredTimestamp: Bool) async -> Bool {
    if pendingAcks[messageID]?.isAwaitingAck == false { return true }
    if replacingDeliveredTimestamp { return false }
    guard await (try? dataStore.fetchMessage(id: messageID))?.status == .delivered else { return false }
    await discardTrackedAck(messageID: messageID)
    return true
  }

  /// Records the next byte before the ACK wait. A missing row stops further submissions of this timestamp.
  func consumeDirectMessageWireAttempt(_ attempt: UInt8, pendingSendID: UUID?) async -> Bool {
    guard let pendingSendID else { return true }
    let next = UInt8(min(Int(attempt) + 1, Int(AckCodeBuilder.attemptCount)))
    do {
      return try await dataStore.setPendingSendWireAttempt(id: pendingSendID, to: next)
    } catch {
      logger.warning("DM wire attempt record failed messageID pendingSend=\(pendingSendID): \(error.localizedDescription)")
      return false
    }
  }
}
