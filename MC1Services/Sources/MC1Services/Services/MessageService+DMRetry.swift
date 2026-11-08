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
    waitForAcknowledgement: Bool
  ) async throws -> Data? {
    let maxRetryAttempts = budget.totalAttempts - 1
    let sendDate = Date(timeIntervalSince1970: TimeInterval(timestamp))
    var lastExpectedAck: Data?

    for attemptIndex in 0..<budget.totalAttempts {
      guard !Task.isCancelled else {
        throw CancellationError()
      }

      if lastExpectedAck != nil, pendingAcks[messageID]?.isAwaitingAck != true { return lastExpectedAck }

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

      if attemptIndex > 0 {
        logger.info("Retry sending message: attempt \(attemptIndex + 1)/\(budget.totalAttempts)")
      }

      // Precompute the expected ACK CRC before the send so the persistent
      // ACK listener cannot race ahead of trackPendingAck on short direct links.
      guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
        throw MessageServiceError.notConnected
      }

      if lastExpectedAck != nil, pendingAcks[messageID]?.isAwaitingAck != true { return lastExpectedAck }
      let previousAck = pendingAcks[messageID]

      let predictedAck = AckCodeBuilder.expectedAck(
        timestamp: timestamp,
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
        contactID: contact.id,
        ackCode: predictedAck,
        timeout: preSendTimeout,
        sendLeaseID: sendLease?.id
      )
      await notePlainTextLease(sendLease)

      let sentInfo: MessageSentInfo?
      do {
        sentInfo = try await withPoolBackoff(
          transientCode: FirmwareDeviceErrorCode.directMessageTableFull,
          config: config.poolBackoff,
          logger: logger
        ) { () async throws -> MessageSentInfo? in
          try await self.submitDMCommand(
            messageID: messageID, publicKey: contact.publicKey, text: text,
            timestamp: sendDate, attempt: UInt8(attemptIndex)
          )
        }
      } catch {
        if lastExpectedAck != nil, !Task.isCancelled,
           retainAcceptedDMAfterCommandFailure(
             error, messageID: messageID, previousAck: previousAck, sendLeaseID: sendLease?.id
           ) {
          logger.warning("DM command issuance ended messageID=\(messageID) attempt=\(attemptIndex): \(String(describing: error))")
          return pendingAcks[messageID]?.isAwaitingAck == true ? nil : lastExpectedAck
        }
        await discardTrackedAck(messageID: messageID)
        throw error
      }
      guard let sentInfo else { return lastExpectedAck }
      lastExpectedAck = sentInfo.expectedAck
      guard pendingAcks[messageID]?.isAwaitingAck == true else { return lastExpectedAck }

      let ackTimeout = timeout ?? max(config.minTimeout, dmAckTimeout(for: sentInfo))

      recordDMCommandAcceptance(
        messageID: messageID, sentInfo: sentInfo, predictedAck: predictedAck, timeout: ackTimeout
      )

      if !waitForAcknowledgement { return nil }

      let ackEvent = await session.waitForEvent(
        filter: .acknowledgement(code: sentInfo.expectedAck),
        timeout: ackTimeout
      )

      if ackEvent != nil || pendingAcks[messageID]?.isAwaitingAck != true {
        return lastExpectedAck
      }
    }

    logger.info("DM retry budget spent messageID=\(messageID) attempts=\(budget.totalAttempts)")
    return nil
  }

  private func submitDMCommand(
    messageID: UUID, publicKey: Data, text: String, timestamp: Date, attempt: UInt8
  ) async throws -> MessageSentInfo? {
    guard pendingAcks[messageID]?.isAwaitingAck == true else { return nil }
    // The last submission may have reached the radio even when its response times out.
    pendingAcks[messageID]?.sentAt = Date()
    return try await session.sendMessage(to: publicKey.prefix(6), text: text, timestamp: timestamp, attempt: attempt)
  }

  func recordDMCommandAcceptance(messageID: UUID, sentInfo: MessageSentInfo, predictedAck: Data, timeout: TimeInterval) {
    guard let tracking = pendingAcks[messageID], tracking.isAwaitingAck else { return }
    if sentInfo.expectedAck != predictedAck {
      logger.warning("DM expected ACK differs from prediction messageID=\(messageID)")
      trackPendingAck(
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
}
