import Foundation

enum PendingAcknowledgementCommit {
  case committed(MessageStatus)
  case unchanged
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
        owner: .init(id: row.id, radioID: radioID, deadline: deadline, phase: .saved)
      )
    }
    let now = Date()
    for (id, tracking) in restored {
      if let owner = tracking.owner, owner.deadline > now {
        restored[id]?.sendLeaseID = owner.id
      }
    }
    // Reserve recovered ACK owners before hydration can admit another send.
    await plainTextSendGate?.reserveRestoredAcks(Set(restored.values.compactMap(\.sendLeaseID)))
    pendingAcks.merge(restored) { _, restored in restored }
  }

  @discardableResult
  func persistAcknowledgement(messageID: UUID, radioID: UUID, pendingSendID: UUID?) async -> Bool {
    pendingAcks[messageID]?.prepareOwner(
      radioID: radioID, pendingSendID: pendingSendID, giveUpWindow: config.ackGiveUpWindow
    )
    if let tracking = pendingAcks[messageID], tracking.owner?.phase == .saved,
       tracking.matchedAckCode != nil {
      do {
        _ = try await commitPendingAcknowledgement(messageID: messageID, sentAt: tracking.sentAt)
      } catch {
        logger.warning("ACK resolution deferred messageID=\(messageID): \(String(describing: error))")
      }
      return false
    }
    return await saveAcknowledgementOwner(messageID: messageID)
  }

  private func saveAcknowledgementOwner(messageID: UUID) async -> Bool {
    guard !acknowledgementSavesInFlight.contains(messageID),
          let owner = pendingAcks[messageID]?.beginOwnerSave(),
          let tracking = pendingAcks[messageID] else { return false }
    acknowledgementSavesInFlight.insert(messageID)
    defer { endAcknowledgementSave(messageID) }

    let row = PendingSendDTO(
      id: owner.id, envelope: DirectMessageEnvelope(messageID: messageID, contactID: tracking.contactID),
      radioID: owner.radioID, enqueuedAt: tracking.sentAt,
      ackCodes: Array(tracking.ackCodes), ackSentAt: tracking.sentAt, ackDeadline: owner.deadline
    )
    #if DEBUG
      if let gate = acknowledgementSaveGateForTesting { await gate() }
    #endif
    let saved: Bool
    do {
      saved = try await dataStore.savePendingAcknowledgement(
        row, replacingPendingSendID: owner.phase == .saving(.convert) ? owner.id : nil
      )
    } catch {
      if pendingAcks[messageID]?.owner == owner, pendingAcks[messageID]?.sentAt == tracking.sentAt {
        pendingAcks[messageID]?.finishOwnerSave(succeeded: false)
      }
      logger.warning("ACK owner save deferred messageID=\(messageID): \(String(describing: error))")
      return false
    }

    guard let current = pendingAcks[messageID], current.owner == owner,
          current.sentAt == tracking.sentAt, !current.isResolved else {
      if saved {
        await cleanUpStaleAcknowledgementOwner(tracking, owner: owner)
      }
      return false
    }
    guard saved else {
      await discardTrackedAck(messageID: messageID)
      return false
    }

    pendingAcks[messageID]?.finishOwnerSave(succeeded: true)
    if current.matchedAckCode != nil {
      do {
        _ = try await commitPendingAcknowledgement(messageID: messageID, sentAt: current.sentAt)
        return false
      } catch {
        logger.warning("ACK resolution deferred messageID=\(messageID): \(String(describing: error))")
        return pendingAcks[messageID]?.owner?.id == owner.id
      }
    }
    return true
  }

  func retryPendingAcknowledgementSaves() async {
    for messageID in pendingAcks.keys {
      if await saveAcknowledgementOwner(messageID: messageID) {
        await notifyOutgoingReactionAccepted(messageID: messageID)
        statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
      }
    }
  }

  func waitForAcknowledgementSaves() async {
    guard !acknowledgementSavesInFlight.isEmpty else { return }
    await withCheckedContinuation { continuation in
      acknowledgementSaveWaiters.append(continuation)
    }
  }

  func commitPendingAcknowledgement(messageID: UUID, sentAt: Date) async throws -> PendingAcknowledgementCommit {
    guard pendingAcks[messageID]?.sentAt == sentAt,
          let status = pendingAcks[messageID]?.beginResolution(),
          let current = pendingAcks[messageID] else { return .unchanged }
    let leaseID = current.sendLeaseID
    let updated: Bool
    do {
      updated = try await writeTerminalAcknowledgement(current, status: status)
    } catch {
      if pendingAcks[messageID]?.sentAt == sentAt {
        pendingAcks[messageID]?.resolutionSaveFailed()
      }
      throw error
    }
    guard pendingAcks[messageID]?.sentAt == sentAt else {
      return updated ? .committed(status) : .unchanged
    }
    pendingAcks[messageID]?.resolutionSaveSucceeded()
    if current.owner == nil, let installedOwner = pendingAcks[messageID]?.owner {
      await cleanUpStaleAcknowledgementOwner(current, owner: installedOwner)
    }
    if updated, status == .delivered {
      do {
        try await dataStore.updateContactLastMessage(contactID: current.contactID, date: Date())
      } catch {
        logger.error("Failed to update contact lastMessageDate: \(error.localizedDescription)")
      }
    }
    let tripTime = current.matchedTripTime
    if pendingAcks[messageID]?.sentAt == sentAt {
      pendingAcks.removeValue(forKey: messageID)
    }
    await settlePlainTextLease(leaseID)
    guard updated else { return .unchanged }
    switch status {
    case .delivered:
      await notifyOutgoingReactionAccepted(messageID: messageID)
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .delivered, roundTripTime: tripTime))
    case .failed:
      statusEventBroadcaster.yield(.failed(messageID: messageID))
    default:
      break
    }
    return .committed(status)
  }

  private func endAcknowledgementSave(_ messageID: UUID) {
    acknowledgementSavesInFlight.remove(messageID)
    guard acknowledgementSavesInFlight.isEmpty else { return }
    let waiters = acknowledgementSaveWaiters
    acknowledgementSaveWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  private func writeTerminalAcknowledgement(_ current: PendingAck, status: MessageStatus) async throws -> Bool {
    if let owner = current.owner {
      return try await dataStore.resolvePendingAcknowledgement(
        id: owner.id, expectedDeadline: owner.deadline, status: status,
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

  private func cleanUpStaleAcknowledgementOwner(_ tracking: PendingAck, owner: PendingAck.Owner) async {
    do {
      if let current = pendingAcks[tracking.messageID], current.sentAt != tracking.sentAt {
        try await dataStore.deletePendingSend(id: owner.id)
        return
      }
      let message = try await dataStore.fetchMessage(id: tracking.messageID)
      guard message == nil || message?.status == .delivered || message?.status == .failed else { return }
      _ = try await dataStore.resolvePendingAcknowledgement(
        id: owner.id, expectedDeadline: owner.deadline, status: message?.status ?? .failed,
        ackCode: message?.ackCode, roundTripTime: message?.roundTripTime
      )
    } catch {
      logger.warning("Stale ACK owner cleanup failed messageID=\(tracking.messageID): \(String(describing: error))")
    }
  }
}
