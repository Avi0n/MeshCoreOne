import Foundation

enum PendingAcknowledgementCommit {
  case committed(MessageStatus)
  case unchanged
  case waitingForOwner
}

extension MessageService {
  func restorePendingAcknowledgements(radioID: UUID) async throws {
    var restored: [UUID: PendingAck] = [:]
    for row in try await dataStore.fetchPendingSends(radioID: radioID) {
      guard let codes = row.ackCodes, let sentAt = row.ackSentAt,
            let deadline = row.ackDeadline, let contactID = row.contactID else { continue }
      guard let message = try await dataStore.fetchMessage(id: row.messageID),
            message.status != .delivered, message.status != .failed else {
        try await dataStore.deletePendingSend(id: row.id)
        continue
      }
      restored[row.messageID] = PendingAck(
        messageID: row.messageID, contactID: contactID, ackCodes: Set(codes),
        sentAt: sentAt, timeout: deadline.timeIntervalSince(sentAt),
        pendingSendID: row.id, deadline: deadline
      )
    }
    let now = Date()
    for (id, tracking) in restored {
      if let deadline = tracking.deadline, deadline > now {
        restored[id]?.sendLeaseID = tracking.pendingSendID
      }
    }
    // Reserve recovered ACK owners before hydration can admit another send.
    await plainTextSendGate?.reserveRestoredAcks(Set(restored.values.compactMap(\.sendLeaseID)))
    pendingAcks.merge(restored) { _, restored in restored }
  }

  func persistAcknowledgement(
    messageID: UUID, radioID: UUID, pendingSendID: UUID?
  ) async throws -> Bool {
    beginAcknowledgementSave(messageID)
    defer { endAcknowledgementSave(messageID) }
    guard let tracking = pendingAcks[messageID], !tracking.isDelivered else { return false }
    #if DEBUG
      if let gate = acknowledgementSaveGateForTesting { await gate() }
    #endif
    let ownerID = pendingSendID ?? UUID()
    let deadline = tracking.sentAt.addingTimeInterval(max(config.ackGiveUpWindow, tracking.timeout))
    let row = PendingSendDTO(
      id: ownerID, envelope: DirectMessageEnvelope(messageID: messageID, contactID: tracking.contactID),
      radioID: radioID, enqueuedAt: tracking.sentAt,
      ackCodes: Array(tracking.ackCodes), ackSentAt: tracking.sentAt, ackDeadline: deadline
    )
    let saved: Bool
    do {
      saved = try await dataStore.savePendingAcknowledgement(row, replacingPendingSendID: pendingSendID)
    } catch {
      throw MessageServiceError.persistenceError(error)
    }
    noteAcknowledgementSaveCommitted(messageID)
    guard saved else {
      if removePendingAckIfCurrent(tracking) {
        await settlePlainTextLease(tracking.sendLeaseID)
      }
      return false
    }
    #if DEBUG
      if let gate = acknowledgementInstallGateForTesting { await gate() }
    #endif
    if let current = pendingAcks[messageID] {
      if current.sentAt == tracking.sentAt, !current.isDelivered {
        pendingAcks[messageID]?.pendingSendID = ownerID
        pendingAcks[messageID]?.deadline = deadline
        if current.matchedAckCode != nil {
          _ = try await commitPendingAcknowledgement(messageID: messageID, sentAt: current.sentAt)
        }
      } else if current.sentAt != tracking.sentAt {
        try await dataStore.deletePendingSend(id: ownerID)
      } else {
        try await resolveFinishedOwner(messageID: messageID, ownerID: ownerID, deadline: deadline)
      }
    } else {
      try await resolveFinishedOwner(messageID: messageID, ownerID: ownerID, deadline: deadline)
    }
    return true
  }

  func waitForAcknowledgementSaves() async {
    guard !acknowledgementSavesInFlight.isEmpty else { return }
    await withCheckedContinuation { continuation in
      acknowledgementSaveWaiters.append(continuation)
    }
  }

  func commitPendingAcknowledgement(messageID: UUID, sentAt: Date) async throws -> PendingAcknowledgementCommit {
    guard let current = pendingAcks[messageID], current.sentAt == sentAt else { return .unchanged }
    if uncommittedAcknowledgementSaves.contains(messageID), current.pendingSendID == nil {
      return .waitingForOwner
    }
    let status: MessageStatus = current.matchedAckCode == nil ? .failed : .delivered
    let leaseID = current.sendLeaseID
    let updated = try await writeTerminalAcknowledgement(current, status: status)
    guard pendingAcks[messageID]?.sentAt == sentAt else {
      return updated ? .committed(status) : .unchanged
    }
    guard updated else {
      pendingAcks.removeValue(forKey: messageID)
      await settlePlainTextLease(leaseID)
      return .unchanged
    }
    pendingAcks[messageID]?.isDelivered = true
    if status == .delivered {
      do {
        try await dataStore.updateContactLastMessage(contactID: current.contactID, date: Date())
      } catch {
        logger.error("Failed to update contact lastMessageDate: \(error.localizedDescription)")
      }
    }
    let tripTime = pendingAcks[messageID]?.matchedTripTime
    pendingAcks.removeValue(forKey: messageID)
    await settlePlainTextLease(leaseID)
    switch status {
    case .delivered:
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .delivered, roundTripTime: tripTime))
    case .failed:
      statusEventBroadcaster.yield(.failed(messageID: messageID))
    default:
      break
    }
    return .committed(status)
  }

  private func beginAcknowledgementSave(_ messageID: UUID) {
    acknowledgementSavesInFlight[messageID, default: 0] += 1
    uncommittedAcknowledgementSaves.insert(messageID)
  }

  private func noteAcknowledgementSaveCommitted(_ messageID: UUID) {
    uncommittedAcknowledgementSaves.remove(messageID)
  }

  private func endAcknowledgementSave(_ messageID: UUID) {
    uncommittedAcknowledgementSaves.remove(messageID)
    let remaining = (acknowledgementSavesInFlight[messageID] ?? 1) - 1
    if remaining > 0 {
      acknowledgementSavesInFlight[messageID] = remaining
    } else {
      acknowledgementSavesInFlight.removeValue(forKey: messageID)
    }
    guard acknowledgementSavesInFlight.isEmpty else { return }
    let waiters = acknowledgementSaveWaiters
    acknowledgementSaveWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  private func writeTerminalAcknowledgement(_ current: PendingAck, status: MessageStatus) async throws -> Bool {
    if let ownerID = current.pendingSendID {
      return try await dataStore.resolvePendingAcknowledgement(
        id: ownerID, expectedDeadline: current.deadline, status: status,
        ackCode: current.matchedAckCode, roundTripTime: current.matchedTripTime
      )
    }
    let existing = try await dataStore.fetchMessage(id: current.messageID)
    guard let existing, existing.status != .delivered, existing.status != .failed else { return false }
    if status == .delivered, let code = current.matchedAckCode {
      try await dataStore.updateMessageAck(
        id: current.messageID, ackCode: code, status: .delivered, roundTripTime: current.matchedTripTime
      )
      return try await dataStore.fetchMessage(id: current.messageID)?.status == .delivered
    }
    return try await dataStore.updateMessageStatusUnlessDelivered(id: current.messageID, status: .failed)
  }

  private func resolveFinishedOwner(messageID: UUID, ownerID: UUID, deadline: Date) async throws {
    let message = try await dataStore.fetchMessage(id: messageID)
    guard message == nil || message?.status == .delivered || message?.status == .failed else { return }
    _ = try await dataStore.resolvePendingAcknowledgement(
      id: ownerID, expectedDeadline: deadline, status: message?.status ?? .failed,
      ackCode: message?.ackCode, roundTripTime: message?.roundTripTime
    )
  }

  @discardableResult
  func removePendingAckIfCurrent(_ tracking: PendingAck) -> Bool {
    guard let current = pendingAcks[tracking.messageID], current.sentAt == tracking.sentAt,
          current.pendingSendID == tracking.pendingSendID else { return false }
    pendingAcks.removeValue(forKey: tracking.messageID)
    return true
  }
}
