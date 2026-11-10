import Foundation
import os
import SwiftData

public extension PersistenceStore {
  static let initialAttemptCount = 0
  private static let pendingSendLogger = Logger(
    subsystem: "com.mc1",
    category: "PersistenceStore.PendingSends"
  )

  // MARK: - PendingSend CRUD

  /// Insert (or update if `dto.id` already exists) a pending send row.
  /// The `dto.sequence` value is used as-is. Used by tests that need to
  /// pin sequence values; the production enqueue path uses
  /// `insertPendingSendAssigningSequence(_:)` instead so the sequence
  /// is assigned atomically.
  func upsertPendingSend(_ dto: PendingSendDTO) throws {
    let id = dto.id
    let predicate = #Predicate<PendingSend> { row in
      row.id == id
    }
    var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
    descriptor.fetchLimit = 1

    if let existing = try modelContext.fetch(descriptor).first {
      existing.radioID = dto.radioID
      existing.messageID = dto.messageID
      existing.kindRawValue = dto.kind.rawValue
      existing.contactID = dto.contactID
      existing.channelIndex = dto.channelIndex
      existing.isResend = dto.isResend
      existing.isSingleAttempt = dto.isSingleAttempt
      existing.messageText = dto.messageText
      existing.messageTimestamp = dto.messageTimestamp
      existing.localNodeName = dto.localNodeName
      existing.sequence = dto.sequence
      existing.enqueuedAt = dto.enqueuedAt
      existing.attemptCount = dto.attemptCount
      existing.nextWireAttempt = dto.nextWireAttempt
      existing.ackCodes = dto.ackCodes
      existing.ackSentAt = dto.ackSentAt
      existing.ackDeadline = dto.ackDeadline
    } else {
      modelContext.insert(PendingSend(dto: dto))
    }
    try modelContext.save()
  }

  /// Insert a new pending send row, atomically computing and assigning
  /// the next sequence number for the row's radio. The `dto.sequence`
  /// field is ignored — the assigned value is the one that lands on
  /// disk, and it is also the return value.
  ///
  /// Atomicity: the read of `max(sequence)` and the insert both run on
  /// `PersistenceStore`'s serial `@ModelActor` isolation, so two
  /// concurrent enqueues from main never see the same `max+1`. This is
  /// the production enqueue path; `upsertPendingSend(_:)` above is
  /// reserved for tests and explicit-sequence scenarios.
  func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int {
    #if DEBUG
      try await pendingSendInsertHandler?()
    #endif
    let assignedSequence = try insertPendingSendAssigningSequenceWithoutSaving(dto)
    try modelContext.save()
    return assignedSequence
  }

  internal func insertPendingSendAssigningSequenceWithoutSaving(_ dto: PendingSendDTO) throws -> Int {
    let scopedRadioID = dto.radioID
    let radioPredicate = #Predicate<PendingSend> { row in
      row.radioID == scopedRadioID
    }
    var seqDescriptor = FetchDescriptor<PendingSend>(
      predicate: radioPredicate,
      sortBy: [SortDescriptor(\.sequence, order: .reverse)]
    )
    seqDescriptor.fetchLimit = 1
    let latest = try modelContext.fetch(seqDescriptor).first
    let assignedSequence = (latest?.sequence ?? 0) + 1

    let inserted = PendingSend(dto: dto)
    inserted.sequence = assignedSequence
    modelContext.insert(inserted)
    return assignedSequence
  }

  /// Replaces this message's pending rows in one save, so a crash cannot leave `.pending` with no row.
  /// `.delivered` stays `.delivered` until the new packet commits its timestamp immediately before the command.
  func replacePendingSendForRetry(
    messageID: UUID,
    dto: PendingSendDTO
  ) async throws -> Int {
    let scopedMessageID = messageID
    let pendingPredicate = #Predicate<PendingSend> { row in
      row.messageID == scopedMessageID
    }
    let existing = try modelContext.fetch(FetchDescriptor<PendingSend>(predicate: pendingPredicate))
    for row in existing {
      modelContext.delete(row)
    }

    let messagePredicate = #Predicate<Message> { message in
      message.id == scopedMessageID
    }
    var messageDescriptor = FetchDescriptor<Message>(predicate: messagePredicate)
    messageDescriptor.fetchLimit = 1
    if let message = try modelContext.fetch(messageDescriptor).first,
       message.status != .delivered {
      message.status = .pending
    }

    let scopedRadioID = dto.radioID
    let radioPredicate = #Predicate<PendingSend> { row in
      row.radioID == scopedRadioID
    }
    var seqDescriptor = FetchDescriptor<PendingSend>(
      predicate: radioPredicate,
      sortBy: [SortDescriptor(\.sequence, order: .reverse)]
    )
    seqDescriptor.fetchLimit = 1
    let latest = try modelContext.fetch(seqDescriptor).first
    let assignedSequence = (latest?.sequence ?? 0) + 1

    let inserted = PendingSend(dto: dto)
    inserted.sequence = assignedSequence
    modelContext.insert(inserted)

    try modelContext.save()
    return assignedSequence
  }

  /// Fetch all pending sends for a given radio, ordered by sequence
  /// ascending. Rows whose `kindRawValue` does not resolve to a known
  /// `PendingSendKind` case are skipped and logged at warning level —
  /// such a row could only exist after a downgrade from a future build
  /// that introduced a new case, and silently routing it to `.dm` would
  /// strand the row on disk via the envelope-materialization nil guard.
  func fetchPendingSends(radioID: UUID) throws -> [PendingSendDTO] {
    let scopedRadioID = radioID
    let predicate = #Predicate<PendingSend> { row in
      row.radioID == scopedRadioID
    }
    let descriptor = FetchDescriptor<PendingSend>(
      predicate: predicate,
      sortBy: [SortDescriptor(\.sequence, order: .forward)]
    )
    return try modelContext.fetch(descriptor).compactMap { row in
      guard PendingSendKind(rawValue: row.kindRawValue) != nil else {
        Self.pendingSendLogger.warning(
          "Skipping PendingSend \(row.id, privacy: .public) with unknown kindRawValue=\(row.kindRawValue, privacy: .public)"
        )
        return nil
      }
      return row.toDTO()
    }
  }

  /// Delete a pending send by row id. No-op if the id is not present.
  func deletePendingSend(id: UUID) throws {
    let target = id
    let predicate = #Predicate<PendingSend> { row in
      row.id == target
    }
    var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
    descriptor.fetchLimit = 1
    if let row = try modelContext.fetch(descriptor).first {
      modelContext.delete(row)
      try modelContext.save()
    }
  }

  /// Deletes pending sends only when both the Device and matching Message are absent.
  /// Persist newly paired Device records first; identity reassociation may leave valid
  /// unsent history under the previous Radio ID.
  @discardableResult
  func purgeOrphanPendingSends() throws -> Int {
    let knownRadioIDs = try Set(modelContext.fetch(FetchDescriptor<Device>()).map(\.radioID))
    let allRows = try modelContext.fetch(FetchDescriptor<PendingSend>())
    var orphans: [PendingSend] = []
    for row in allRows where !knownRadioIDs.contains(row.radioID) {
      let messageID = row.messageID
      let radioID = row.radioID
      let predicate = #Predicate<Message> { $0.id == messageID && $0.radioID == radioID }
      if try modelContext.fetchCount(FetchDescriptor(predicate: predicate)) == 0 { orphans.append(row) }
    }
    guard !orphans.isEmpty else { return 0 }
    for row in orphans {
      modelContext.delete(row)
    }
    try modelContext.save()
    Self.pendingSendLogger.info("Purged \(orphans.count, privacy: .public) orphan PendingSend rows")
    return orphans.count
  }

  /// Delete every pending send row whose `messageID` matches. No-op if no
  /// rows match. Used at drain time when only the envelope's `messageID`
  /// is available; the radio is intentionally not part of the predicate
  /// because the user can switch conversations (and therefore the radio
  /// in scope) between enqueue and drain, and the same `messageID` may
  /// be enqueued more than once on the resend path. `messageID` is a
  /// UUID so cross-radio collision is effectively zero.
  func deletePendingSendsForMessage(messageID: UUID) throws {
    let scopedMessageID = messageID
    let predicate = #Predicate<PendingSend> { row in
      row.messageID == scopedMessageID
    }
    let descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
    let rows = try modelContext.fetch(descriptor)
    guard !rows.isEmpty else { return }
    for row in rows {
      modelContext.delete(row)
    }
    try modelContext.save()
  }

  /// Bulk non-saving cascade helper. Caller owns the single transactional
  /// save. Issues one `delete(model:where:)` per chunk so wiping thousands
  /// of messages costs O(chunks) bulk ops rather than O(n) fetches.
  ///
  /// **Cascade atomicity:** the cascade is serial within `@ModelActor`
  /// (`PersistenceStore`'s actor isolation serialises awaits), not
  /// transactional in the SQL sense. A crash mid-cascade leaves a
  /// partially-deleted state on disk; the next `purgeOrphanPendingSends`
  /// + hydrate cycle reconciles. Callers requiring strict atomicity stage
  /// every delete in this function and call `save()` exactly once at the
  /// end (the convention every cascade site in this codebase follows).
  ///
  /// **Chunking:** SwiftData translates `contains` predicates to SQL
  /// `IN (?, ?, …)`. SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` on
  /// iOS 18+ is 32766, so wiping a radio with >32k messages would hit
  /// the ceiling unchunked. 500 keeps headroom and keeps the predicate
  /// compact for SwiftData's compile-time validation.
  ///
  /// **`@Relationship` cascade bypass:** bulk `delete(model:where:)`
  /// skips the SwiftData change-tracking pipeline, so
  /// `@Relationship(deleteRule: .cascade)` declarations do not fire on
  /// these paths. Any future cascade-by-relationship added to `Message`
  /// must be mirrored explicitly in every cascade site that bulk-deletes
  /// messages.
  internal func _deletePendingSendsForMessageIDsWithoutSaving(messageIDs: [UUID]) throws {
    guard !messageIDs.isEmpty else { return }
    let chunkSize = 500
    for start in stride(from: 0, to: messageIDs.count, by: chunkSize) {
      let chunk = Array(messageIDs[start..<min(start + chunkSize, messageIDs.count)])
      let scopedIDs = chunk
      try modelContext.delete(model: PendingSend.self, where: #Predicate { row in
        scopedIDs.contains(row.messageID)
      })
    }
  }

  func hasPendingSend(messageID: UUID) throws -> Bool {
    let scopedMessageID = messageID
    let predicate = #Predicate<PendingSend> { row in
      row.messageID == scopedMessageID
    }
    var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
    descriptor.fetchLimit = 1
    return try !modelContext.fetch(descriptor).isEmpty
  }

  /// Fetch every `PendingSend` row that matches a given message ID, ordered
  /// by sequence ascending. Returns DTOs for cross-actor safety. Rows whose
  /// `kindRawValue` does not resolve to a known `PendingSendKind` case are
  /// skipped, matching the contract of `fetchPendingSends(radioID:)`.
  ///
  /// Symmetric with `deletePendingSendsForMessage(messageID:)` — both ignore
  /// the radio because the user can switch radios between enqueue and
  /// observation, and a UUID `messageID` makes cross-radio collision
  /// effectively zero.
  func fetchPendingSendsForMessage(messageID: UUID) throws -> [PendingSendDTO] {
    let scopedMessageID = messageID
    let predicate = #Predicate<PendingSend> { row in
      row.messageID == scopedMessageID
    }
    let descriptor = FetchDescriptor<PendingSend>(
      predicate: predicate,
      sortBy: [SortDescriptor(\.sequence, order: .forward)]
    )
    return try modelContext.fetch(descriptor).compactMap { row in
      guard PendingSendKind(rawValue: row.kindRawValue) != nil else { return nil }
      return row.toDTO()
    }
  }

  /// Deletes any `PendingSend` row where `attemptCount` is `nil`. Such rows
  /// can only exist via lightweight migration from a schema version that
  /// predates `attemptCount`; their drain history is ambiguous, so the user
  /// re-sends if they care.
  ///
  /// Idempotent: an empty fetch after the first purge across the lifetime of
  /// the storage.
  ///
  /// Called from `PersistenceStore.warmUp()` on every connect.
  @discardableResult
  func purgeLegacyAttemptCountRows() throws -> Int {
    let nilPredicate = #Predicate<PendingSend> { row in
      row.attemptCount == nil
    }
    let rows = try modelContext.fetch(FetchDescriptor<PendingSend>(predicate: nilPredicate))
    guard !rows.isEmpty else { return 0 }
    for row in rows {
      modelContext.delete(row)
    }
    try modelContext.save()
    Self.pendingSendLogger.notice(
      "purgeLegacyAttemptCountRows deleted \(rows.count, privacy: .public) row(s) with attemptCount=nil"
    )
    return rows.count
  }

  func pendingSendWireAttempt(id: UUID) async throws -> UInt8? {
    guard let row = try pendingSendRow(id: id), row.ackDeadline == nil else { return nil }
    return row.nextWireAttempt
  }

  /// A missing id or an ACK owner writes nothing and does not create a row.
  func setPendingSendWireAttempt(id: UUID, to next: UInt8) async throws -> Bool {
    guard let row = try pendingSendRow(id: id), row.ackDeadline == nil else { return false }
    row.nextWireAttempt = next
    do {
      #if DEBUG
        try await pendingSendWireAttemptSaveFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    return true
  }

  /// Queued direct messages that already handed attempt bytes to the radio, in sequence order.
  /// Uses the live message timestamp and text. Skips channel messages and rows already delivered.
  func queuedDirectMessageWireAttempts() async throws -> [QueuedDirectMessageWireAttempt] {
    let dmKind = PendingSendKind.dm.rawValue
    let predicate = #Predicate<PendingSend> { row in
      row.ackDeadline == nil && row.kindRawValue == dmKind
    }
    let rows = try modelContext.fetch(FetchDescriptor<PendingSend>(
      predicate: predicate,
      sortBy: [SortDescriptor(\.sequence, order: .forward)]
    ))
    var attempts: [QueuedDirectMessageWireAttempt] = []
    for row in rows where row.nextWireAttempt > 0 {
      let messageID = row.messageID
      let messagePredicate = #Predicate<Message> { message in
        message.id == messageID
      }
      var descriptor = FetchDescriptor<Message>(predicate: messagePredicate)
      descriptor.fetchLimit = 1
      guard let message = try modelContext.fetch(descriptor).first else { continue }
      guard message.channelIndex == nil, message.status != .delivered else { continue }
      attempts.append(QueuedDirectMessageWireAttempt(
        pendingSendID: row.id,
        messageID: messageID,
        timestamp: message.timestamp,
        text: message.text,
        consumedAttempts: row.nextWireAttempt
      ))
    }
    return attempts
  }

  /// Marks this queued direct message delivered and deletes its row in one save.
  /// A host timeout may have written `.failed` while the row still holds the offered byte.
  func deliverQueuedDirectMessage(
    pendingSendID: UUID,
    ackCode: UInt32,
    roundTripTime: UInt32?
  ) async throws -> MessageDTO? {
    guard let row = try pendingSendRow(id: pendingSendID),
          row.ackDeadline == nil, row.kind == .dm else { return nil }
    let messageID = row.messageID
    let messagePredicate = #Predicate<Message> { message in
      message.id == messageID
    }
    var descriptor = FetchDescriptor<Message>(predicate: messagePredicate)
    descriptor.fetchLimit = 1
    guard let message = try modelContext.fetch(descriptor).first,
          message.channelIndex == nil, message.status != .delivered else { return nil }
    message.status = .delivered
    message.ackCode = ackCode
    if let roundTripTime {
      message.roundTripTime = roundTripTime
    }
    modelContext.delete(row)
    do {
      _ = try stageOutgoingReaction(message)
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    return MessageDTO(from: message)
  }

  private func pendingSendRow(id: UUID) throws -> PendingSend? {
    let target = id
    let predicate = #Predicate<PendingSend> { row in
      row.id == target
    }
    var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first
  }

  /// Restores eligible unsent DMs and increments the queued row's attempt count in one save.
  /// Returns nil if no queued row remains. A persistence error leaves preparation retryable.
  @discardableResult
  func preparePendingSendAttempt(messageID: UUID) async throws -> (pendingSendID: UUID, attemptCount: Int)? {
    #if DEBUG
      await pendingSendAttemptHandler?()
      try preparePendingSendAttemptFaultInjection?()
    #endif
    try commitPendingRxLogEntries()
    let scopedMessageID = messageID
    let predicate = #Predicate<PendingSend> { row in
      row.messageID == scopedMessageID && row.ackDeadline == nil
    }
    var descriptor = FetchDescriptor<PendingSend>(predicate: predicate, sortBy: [SortDescriptor(\.sequence)])
    descriptor.fetchLimit = 1
    guard let row = try modelContext.fetch(descriptor).first else { return nil }
    do {
      if row.kind == .dm, row.attemptCount == Self.initialAttemptCount, !row.isResend, !row.isSingleAttempt {
        let radioID = row.radioID
        let messagePredicate = #Predicate<Message> { $0.id == scopedMessageID && $0.radioID == radioID }
        if let message = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate)).first,
           message.reactionTargetMessageID == nil, message.status == .failed {
          // Admission can fail after the durable write. Restore unsent DMs so ACK
          // persistence can record acceptance and delivery on their first attempt.
          message.status = .pending
        }
      }
      let nextCount = (row.attemptCount ?? Self.initialAttemptCount) + 1
      row.attemptCount = nextCount
      #if DEBUG
        try pendingSendAttemptSaveFaultInjection?(messageID)
      #endif
      try modelContext.save()
      return (row.id, nextCount)
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  #if DEBUG
    func setPendingSendAttemptHandler(_ handler: (@Sendable () async -> Void)?) {
      pendingSendAttemptHandler = handler
    }

    func setPendingSendInsertHandler(_ handler: (@Sendable () async throws -> Void)?) {
      pendingSendInsertHandler = handler
    }

    func setPreparePendingSendAttemptFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
      preparePendingSendAttemptFaultInjection = hook
    }

    func setPendingSendAttemptSaveFaultInjection(_ hook: (@Sendable (UUID) throws -> Void)?) {
      pendingSendAttemptSaveFaultInjection = hook
    }

    func setPendingSendWireAttemptSaveFaultInjection(_ hook: (@Sendable () async throws -> Void)?) {
      pendingSendWireAttemptSaveFaultInjection = hook
    }
  #endif
}
