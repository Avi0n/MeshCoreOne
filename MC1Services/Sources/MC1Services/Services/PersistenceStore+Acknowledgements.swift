import Foundation
import SwiftData

public extension PersistenceStore {
  func savePendingAcknowledgement(
    _ dto: PendingSendDTO, replacingPendingSendID: UUID?, markSent: Bool = true
  ) async throws -> Bool {
    try commitPendingRxLogEntries()
    let messageID = dto.messageID
    let messagePredicate = #Predicate<Message> { $0.id == messageID }
    guard let message = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate)).first,
          message.radioID == dto.radioID, message.channelIndex == nil,
          message.status != .delivered,
          // A failed row is eligible when this save converts its queued send.
          message.status != .failed || replacingPendingSendID != nil else { return false }

    let row: PendingSend
    if let replacingPendingSendID {
      let targetID = replacingPendingSendID
      let predicate = #Predicate<PendingSend> { $0.id == targetID }
      guard let existing = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first,
            existing.messageID == messageID, existing.radioID == dto.radioID,
            existing.ackDeadline == nil else { return false }
      row = existing
    } else {
      let pendingPredicate = #Predicate<PendingSend> { $0.messageID == messageID }
      guard try modelContext.fetch(FetchDescriptor(predicate: pendingPredicate)).isEmpty else { return false }
      let scopedRadioID = dto.radioID
      let radioPredicate = #Predicate<PendingSend> { $0.radioID == scopedRadioID }
      var sequenceDescriptor = FetchDescriptor<PendingSend>(
        predicate: radioPredicate, sortBy: [SortDescriptor(\.sequence, order: .reverse)]
      )
      sequenceDescriptor.fetchLimit = 1
      let latest = try modelContext.fetch(sequenceDescriptor).first
      row = PendingSend(dto: dto)
      row.sequence = (latest?.sequence ?? 0) + 1
      modelContext.insert(row)
    }
    row.ackCodes = dto.ackCodes
    row.ackSentAt = dto.ackSentAt
    row.ackDeadline = dto.ackDeadline
    if markSent {
      message.status = .sent
    } else if message.status == .failed {
      // Give-up and a late acknowledgement skip a row that is already failed.
      message.status = .pending
    }
    do {
      _ = try stageOutgoingReaction(message)
      #if DEBUG
        try savePendingAcknowledgementFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    return true
  }

  func resolvePendingAcknowledgement(
    id: UUID, expectedDeadline: Date?, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?
  ) async throws -> Bool {
    try commitPendingRxLogEntries()
    let targetID = id
    let predicate = #Predicate<PendingSend> { $0.id == targetID }
    guard let row = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first,
          row.ackDeadline == expectedDeadline else { return false }
    let messageID = row.messageID
    let messagePredicate = #Predicate<Message> { $0.id == messageID }
    let message = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate)).first
    let shouldUpdate = message != nil && message?.status != .delivered && message?.status != .failed
    if shouldUpdate, let message {
      message.status = status
      if status == .failed { message.failureSeen = false }
      if let ackCode { message.ackCode = ackCode }
      if let roundTripTime { message.roundTripTime = roundTripTime }
    }
    do {
      if let message { _ = try stageOutgoingReaction(message) }
      if row.ackDeadline != nil { modelContext.delete(row) }
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    return shouldUpdate
  }

  #if DEBUG
    func setSavePendingAcknowledgementFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
      savePendingAcknowledgementFaultInjection = hook
    }
  #endif
}
