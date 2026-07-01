import Foundation
import MeshCore
import SwiftData

public extension PersistenceStore {
  // MARK: - Saved Trace Path Operations

  func fetchSavedTracePaths(radioID: UUID) throws -> [SavedTracePathDTO] {
    let targetRadioID = radioID
    let descriptor = FetchDescriptor<SavedTracePath>(
      predicate: #Predicate { $0.radioID == targetRadioID },
      sortBy: [SortDescriptor(\.createdDate, order: .reverse)]
    )
    let paths = try modelContext.fetch(descriptor)
    return paths.map { SavedTracePathDTO(from: $0) }
  }

  func fetchSavedTracePath(id: UUID) throws -> SavedTracePathDTO? {
    let targetID = id
    let descriptor = FetchDescriptor<SavedTracePath>(
      predicate: #Predicate { $0.id == targetID }
    )
    guard let path = try modelContext.fetch(descriptor).first else { return nil }
    return SavedTracePathDTO(from: path)
  }

  func createSavedTracePath(
    radioID: UUID,
    name: String,
    pathBytes: Data,
    hashSize: Int = 1,
    initialRun: TracePathRunDTO?
  ) throws -> SavedTracePathDTO {
    let path = SavedTracePath(
      radioID: radioID,
      name: name,
      pathBytes: pathBytes,
      hashSize: hashSize
    )

    if let runDTO = initialRun {
      let run = try TracePathRun(dto: runDTO)
      run.savedPath = path
      path.runs.append(run)
      modelContext.insert(run)
    }

    modelContext.insert(path)
    try modelContext.save()
    return SavedTracePathDTO(from: path)
  }

  func updateSavedTracePathName(id: UUID, name: String) throws {
    let targetID = id
    let descriptor = FetchDescriptor<SavedTracePath>(
      predicate: #Predicate { $0.id == targetID }
    )
    guard let path = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.fetchFailed("SavedTracePath not found")
    }
    path.name = name
    try modelContext.save()
  }

  func deleteSavedTracePath(id: UUID) throws {
    let targetID = id
    let descriptor = FetchDescriptor<SavedTracePath>(
      predicate: #Predicate { $0.id == targetID }
    )
    guard let path = try modelContext.fetch(descriptor).first else { return }
    modelContext.delete(path)
    try modelContext.save()
  }

  func appendTracePathRun(pathID: UUID, run runDTO: TracePathRunDTO) throws {
    let targetID = pathID
    let descriptor = FetchDescriptor<SavedTracePath>(
      predicate: #Predicate { $0.id == targetID }
    )
    guard let path = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.fetchFailed("SavedTracePath not found")
    }

    let run = try TracePathRun(dto: runDTO)
    run.savedPath = path
    path.runs.append(run)
    modelContext.insert(run)
    try modelContext.save()
  }

  // MARK: - RxLogEntry

  /// Save a new RX log entry.
  func saveRxLogEntry(_ dto: RxLogEntryDTO) throws {
    // Seed the count cache from disk before the insert; incrementing a
    // missing entry from zero would undercount rows persisted by earlier
    // sessions and suppress pruning indefinitely.
    let countBeforeInsert = try cachedRxLogEntryCount(radioID: dto.radioID)
    let entry = RxLogEntry(
      id: dto.id,
      radioID: dto.radioID,
      receivedAt: dto.receivedAt,
      snr: dto.snr,
      rssi: dto.rssi,
      routeType: Int(dto.routeType.rawValue),
      payloadType: Int(dto.payloadType.rawValue),
      payloadVersion: Int(dto.payloadVersion),
      transportCode: dto.transportCode,
      pathLength: Int(dto.pathLength),
      pathNodes: dto.pathNodes,
      packetPayload: dto.packetPayload,
      rawPayload: dto.rawPayload,
      packetHash: dto.packetHash,
      channelIndex: dto.channelIndex.map { Int($0) },
      channelName: dto.channelName,
      decryptStatus: dto.decryptStatus.rawValue,
      fromContactName: dto.fromContactName,
      toContactName: dto.toContactName,
      senderTimestamp: dto.senderTimestamp.map { Int($0) },
      regionScope: dto.regionScope,
      payloadTypeBits: Int(dto.payloadTypeBits)
    )
    modelContext.insert(entry)
    try modelContext.save()
    rxLogEntryCountsByDevice[dto.radioID] = countBeforeInsert + 1
  }

  /// Fetch RX log entries for a device, most recent first.
  func fetchRxLogEntries(radioID: UUID, limit: Int = 500) throws -> [RxLogEntryDTO] {
    let targetRadioID = radioID
    var descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate { $0.radioID == targetRadioID },
      sortBy: [SortDescriptor(\.receivedAt, order: .reverse)]
    )
    descriptor.fetchLimit = limit
    let entries = try modelContext.fetch(descriptor)
    return entries.map { RxLogEntryDTO(from: $0) }
  }

  /// Count RX log entries for a device.
  func countRxLogEntries(radioID: UUID) throws -> Int {
    let targetRadioID = radioID
    let descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate { $0.radioID == targetRadioID }
    )
    return try modelContext.fetchCount(descriptor)
  }

  /// Delete oldest entries once the log materially exceeds the retention cap.
  ///
  /// This avoids repeated count/fetch/delete maintenance on every RX packet while keeping
  /// retention bounded to `keepCount + pruneThreshold` entries between prune passes.
  func pruneRxLogEntries(
    radioID: UUID,
    keepCount: Int = 1000,
    pruneThreshold: Int = 100
  ) throws {
    let count = try cachedRxLogEntryCount(radioID: radioID)
    guard count > keepCount + pruneThreshold else { return }

    let deleteCount = count - keepCount
    let targetRadioID = radioID

    var descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate { $0.radioID == targetRadioID },
      sortBy: [SortDescriptor(\.receivedAt, order: .forward)] // Oldest first
    )
    descriptor.fetchLimit = deleteCount

    let toDelete = try modelContext.fetch(descriptor)
    for entry in toDelete {
      modelContext.delete(entry)
    }
    try modelContext.save()
    rxLogEntryCountsByDevice[radioID] = keepCount
  }

  /// Clear all RX log entries for a device.
  func clearRxLogEntries(radioID: UUID) throws {
    let targetRadioID = radioID
    let descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate { $0.radioID == targetRadioID }
    )
    let entries = try modelContext.fetch(descriptor)
    for entry in entries {
      modelContext.delete(entry)
    }
    try modelContext.save()
    rxLogEntryCountsByDevice[radioID] = 0
  }

  private func cachedRxLogEntryCount(radioID: UUID) throws -> Int {
    if let cached = rxLogEntryCountsByDevice[radioID] {
      return cached
    }

    let count = try countRxLogEntries(radioID: radioID)
    rxLogEntryCountsByDevice[radioID] = count
    return count
  }

  /// Find RxLogEntry matching an incoming message for path correlation.
  ///
  /// For channel messages: Correlates by channel index and sender timestamp.
  /// For direct messages: Correlates by sender timestamp and payload type.
  func findRxLogEntry(
    radioID: UUID,
    channelIndex: UInt8?,
    senderTimestamp: UInt32
  ) throws -> RxLogEntryDTO? {
    let targetRadioID = radioID
    let targetTimestamp = Int(senderTimestamp)

    if let channelIndex {
      // Channel message: match on channelIndex and senderTimestamp
      let channelIndexInt = Int(channelIndex)

      let predicate = #Predicate<RxLogEntry> { entry in
        entry.radioID == targetRadioID &&
          entry.channelIndex == channelIndexInt &&
          entry.senderTimestamp == targetTimestamp
      }

      var descriptor = FetchDescriptor<RxLogEntry>(predicate: predicate)
      descriptor.fetchLimit = 1
      descriptor.sortBy = [SortDescriptor(\.receivedAt, order: .reverse)]

      let results = try modelContext.fetch(descriptor)
      return results.first.map { RxLogEntryDTO(from: $0) }
    } else {
      // Direct message: match on senderTimestamp
      let textMessageType = Int(PayloadType.textMessage.rawValue)

      let predicate = #Predicate<RxLogEntry> { entry in
        entry.radioID == targetRadioID &&
          entry.senderTimestamp == targetTimestamp &&
          entry.channelIndex == nil &&
          entry.payloadType == textMessageType
      }

      var descriptor = FetchDescriptor<RxLogEntry>(predicate: predicate)
      descriptor.fetchLimit = 1
      descriptor.sortBy = [SortDescriptor(\.receivedAt, order: .reverse)]

      let results = try modelContext.fetch(descriptor)
      return results.first.map { RxLogEntryDTO(from: $0) }
    }
  }

  /// Find a DM RxLogEntry by matching the sender prefix byte in the packet payload.
  ///
  /// Fallback for when the primary `findRxLogEntry(senderTimestamp:)` fails because
  /// DM decryption hadn't succeeded yet (senderTimestamp was nil). Matches on the
  /// unencrypted srcHash byte at `packetPayload[1]` and a receive-time window.
  func findRxLogEntryBySenderPrefix(
    radioID: UUID,
    senderPrefixByte: UInt8,
    receivedSince: Date
  ) throws -> RxLogEntryDTO? {
    let targetRadioID = radioID
    let textMessageType = Int(PayloadType.textMessage.rawValue)
    let cutoff = receivedSince

    let predicate = #Predicate<RxLogEntry> { entry in
      entry.radioID == targetRadioID &&
        entry.channelIndex == nil &&
        entry.payloadType == textMessageType &&
        entry.receivedAt >= cutoff
    }

    var descriptor = FetchDescriptor<RxLogEntry>(predicate: predicate)
    descriptor.sortBy = [SortDescriptor(\.receivedAt, order: .reverse)]
    descriptor.fetchLimit = 20

    let candidates = try modelContext.fetch(descriptor)

    // Filter in-memory: match sender prefix byte at packetPayload[1]
    let match = candidates.first { entry in
      entry.packetPayload.count >= 2 && entry.packetPayload[1] == senderPrefixByte
    }

    return match.map { RxLogEntryDTO(from: $0) }
  }

  /// Fetch recent RX log entries with a given decrypt status.
  func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) throws -> [RxLogEntryDTO] {
    let targetRadioID = radioID
    let targetStatus = status.rawValue
    let cutoff = since
    let descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate {
        $0.radioID == targetRadioID &&
          $0.decryptStatus == targetStatus &&
          $0.receivedAt >= cutoff
      },
      sortBy: [SortDescriptor(\.receivedAt, order: .forward)]
    )
    let entries = try modelContext.fetch(descriptor)
    return entries.map { RxLogEntryDTO(from: $0) }
  }

  /// Batch update RX log entries after successful decryption.
  /// Note: decodedText is @Transient and not persisted.
  func batchUpdateRxLogDecryption(
    _ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]
  ) throws {
    for update in updates {
      let targetID = update.id
      let descriptor = FetchDescriptor<RxLogEntry>(
        predicate: #Predicate { $0.id == targetID }
      )
      guard let entry = try modelContext.fetch(descriptor).first else { continue }

      entry.channelIndex = update.channelIndex.map { Int($0) }
      entry.channelName = update.channelName
      entry.decryptStatus = DecryptStatus.success.rawValue
      entry.senderTimestamp = update.senderTimestamp.map { Int($0) }
    }
    try modelContext.save()
  }

  /// Fetch RX log entries that have a transport code but no resolved
  /// region yet — the back-fill candidate set.
  func fetchEntriesWithMissingRegion(radioID: UUID) throws -> [RxLogEntryDTO] {
    let targetRadioID = radioID
    let descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate {
        $0.radioID == targetRadioID &&
          $0.transportCode != nil &&
          $0.regionScope == nil
      },
      sortBy: [SortDescriptor(\.receivedAt, order: .forward)]
    )
    let entries = try modelContext.fetch(descriptor)
    return entries.map { RxLogEntryDTO(from: $0) }
  }

  /// Batch update `regionScope` on RX log entries by id.
  func batchUpdateRxLogRegion(
    updates: [(id: UUID, regionScope: String?)]
  ) throws {
    for update in updates {
      let targetID = update.id
      let descriptor = FetchDescriptor<RxLogEntry>(
        predicate: #Predicate { $0.id == targetID }
      )
      guard let entry = try modelContext.fetch(descriptor).first else { continue }
      entry.regionScope = update.regionScope
    }
    try modelContext.save()
  }

  /// Batch update `regionScope` on incoming **channel** `Message` rows
  /// correlated by `(channelIndex, senderTimestamp)`. The wire timestamp
  /// fallback is required because `Message.senderTimestamp` is only
  /// populated for the rare timestamp-corrected case; the normal case puts
  /// the wire timestamp on `Message.timestamp`.
  func batchUpdateChannelMessageRegion(
    radioID: UUID,
    updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) throws {
    let targetRadioID = radioID
    let incoming = MessageDirection.incoming.rawValue
    for update in updates {
      let targetIndex: UInt8? = update.channelIndex
      let targetSenderTimestamp: UInt32? = update.senderTimestamp
      let targetWireTimestamp: UInt32 = update.senderTimestamp
      let descriptor = FetchDescriptor<Message>(
        predicate: #Predicate {
          $0.radioID == targetRadioID &&
            $0.channelIndex == targetIndex &&
            $0.directionRawValue == incoming &&
            ($0.senderTimestamp == targetSenderTimestamp ||
              ($0.senderTimestamp == nil && $0.timestamp == targetWireTimestamp))
        }
      )
      for message in try modelContext.fetch(descriptor) {
        message.regionScope = update.regionScope
      }
    }
    try modelContext.save()
  }

  /// Batch update `regionScope` on incoming **DM** `Message` rows. DMs
  /// carry the sender prefix byte at `RxLogEntry.packetPayload[1]` but
  /// the Message side stores the full multi-byte `senderKeyPrefix`, so
  /// the predicate fetches by timestamp + DM channel and an in-memory
  /// pass disambiguates by first-byte equality. Mirrors the correlation
  /// key used by `findRxLogEntryBySenderPrefix`.
  func batchUpdateDMMessageRegion(
    radioID: UUID,
    updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) throws {
    let targetRadioID = radioID
    let nilChannel: UInt8? = nil
    let incoming = MessageDirection.incoming.rawValue
    for update in updates {
      let targetSenderTimestamp: UInt32? = update.senderTimestamp
      let targetWireTimestamp: UInt32 = update.senderTimestamp
      let descriptor = FetchDescriptor<Message>(
        predicate: #Predicate {
          $0.radioID == targetRadioID &&
            $0.channelIndex == nilChannel &&
            $0.directionRawValue == incoming &&
            ($0.senderTimestamp == targetSenderTimestamp ||
              ($0.senderTimestamp == nil && $0.timestamp == targetWireTimestamp))
        }
      )
      let prefixByte = update.senderPrefixByte
      let candidates = try modelContext.fetch(descriptor)
      for message in candidates where message.senderKeyPrefix?.first == prefixByte {
        message.regionScope = update.regionScope
      }
    }
    try modelContext.save()
  }

  // MARK: - Debug Log Entries

  /// Saves a batch of debug log entries.
  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) throws {
    for dto in dtos {
      let entry = DebugLogEntry(
        id: dto.id,
        timestamp: dto.timestamp,
        level: dto.level.rawValue,
        subsystem: dto.subsystem,
        category: dto.category,
        message: dto.message
      )
      modelContext.insert(entry)
    }
    try modelContext.save()
  }

  /// Fetches debug log entries since a given date.
  func fetchDebugLogEntries(since date: Date, limit: Int = 1000) throws -> [DebugLogEntryDTO] {
    let startDate = date
    var descriptor = FetchDescriptor<DebugLogEntry>(
      predicate: #Predicate { $0.timestamp >= startDate },
      sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
    )
    descriptor.fetchLimit = limit
    let entries = try modelContext.fetch(descriptor)
    return entries.map { DebugLogEntryDTO(from: $0) }
  }

  /// Counts all debug log entries.
  func countDebugLogEntries() throws -> Int {
    let descriptor = FetchDescriptor<DebugLogEntry>()
    return try modelContext.fetchCount(descriptor)
  }

  /// Prunes debug log entries, keeping only the most recent entries.
  func pruneDebugLogEntries(keepCount: Int = 1000) throws {
    let count = try countDebugLogEntries()
    guard count > keepCount else { return }

    let deleteCount = count - keepCount
    var descriptor = FetchDescriptor<DebugLogEntry>(
      sortBy: [SortDescriptor(\.timestamp, order: .forward)]
    )
    descriptor.fetchLimit = deleteCount

    let toDelete = try modelContext.fetch(descriptor)
    for entry in toDelete {
      modelContext.delete(entry)
    }
    try modelContext.save()
  }

  /// Clears all debug log entries.
  func clearDebugLogEntries() throws {
    try modelContext.delete(model: DebugLogEntry.self)
    try modelContext.save()
  }

  // MARK: - Node Status Snapshots

  func saveNodeStatusSnapshot(
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil
  ) throws -> UUID {
    try saveNodeStatusSnapshot(
      timestamp: .now,
      nodePublicKey: nodePublicKey,
      batteryMillivolts: batteryMillivolts,
      lastSNR: lastSNR,
      lastRSSI: lastRSSI,
      noiseFloor: noiseFloor,
      uptimeSeconds: uptimeSeconds,
      rxAirtimeSeconds: rxAirtimeSeconds,
      packetsSent: packetsSent,
      packetsReceived: packetsReceived,
      receiveErrors: receiveErrors,
      postedCount: postedCount,
      postPushCount: postPushCount
    )
  }

  // Overload that accepts an explicit timestamp, used by tests to avoid timing-dependent sleeps.
  // swiftlint:disable:next function_parameter_count
  func saveNodeStatusSnapshot(
    timestamp: Date,
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil
  ) throws -> UUID {
    let snapshot = NodeStatusSnapshot(
      timestamp: timestamp,
      nodePublicKey: nodePublicKey,
      batteryMillivolts: batteryMillivolts,
      lastSNR: lastSNR,
      lastRSSI: lastRSSI,
      noiseFloor: noiseFloor,
      uptimeSeconds: uptimeSeconds,
      rxAirtimeSeconds: rxAirtimeSeconds,
      packetsSent: packetsSent,
      packetsReceived: packetsReceived,
      receiveErrors: receiveErrors,
      postedCount: postedCount,
      postPushCount: postPushCount
    )
    modelContext.insert(snapshot)
    try modelContext.save()
    return snapshot.id
  }

  func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) throws -> NodeStatusSnapshotDTO? {
    var descriptor = FetchDescriptor<NodeStatusSnapshot>(
      predicate: #Predicate { $0.nodePublicKey == nodePublicKey },
      sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
    )
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first.map(NodeStatusSnapshotDTO.init)
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) throws -> [NodeStatusSnapshotDTO] {
    let descriptor = if let since {
      FetchDescriptor<NodeStatusSnapshot>(
        predicate: #Predicate { $0.nodePublicKey == nodePublicKey && $0.timestamp >= since },
        sortBy: [SortDescriptor(\.timestamp)]
      )
    } else {
      FetchDescriptor<NodeStatusSnapshot>(
        predicate: #Predicate { $0.nodePublicKey == nodePublicKey },
        sortBy: [SortDescriptor(\.timestamp)]
      )
    }
    return try modelContext.fetch(descriptor).map(NodeStatusSnapshotDTO.init)
  }

  func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) throws {
    var descriptor = FetchDescriptor<NodeStatusSnapshot>(
      predicate: #Predicate { $0.id == id }
    )
    descriptor.fetchLimit = 1
    guard let snapshot = try modelContext.fetch(descriptor).first else { return }
    snapshot.neighborSnapshots = neighbors
    try modelContext.save()
  }

  func saveTelemetryOnlySnapshot(
    nodePublicKey: Data,
    telemetryEntries: [TelemetrySnapshotEntry]
  ) throws -> UUID {
    let snapshot = NodeStatusSnapshot(
      nodePublicKey: nodePublicKey,
      telemetryEntries: telemetryEntries
    )
    modelContext.insert(snapshot)
    try modelContext.save()
    return snapshot.id
  }

  func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) throws {
    var descriptor = FetchDescriptor<NodeStatusSnapshot>(
      predicate: #Predicate { $0.id == id }
    )
    descriptor.fetchLimit = 1
    guard let snapshot = try modelContext.fetch(descriptor).first else { return }
    snapshot.telemetryEntries = telemetry
    try modelContext.save()
  }

  /// Atomically capture a status, telemetry, and/or neighbor snapshot for a node.
  ///
  /// The fetch-latest decision and the insert-or-enrich both run in this single
  /// `@ModelActor` method body with no `await`, so two concurrent captures
  /// serialize and the second observes the first's row — there is no window for a
  /// duplicate in-window insert. Keeping the body suspension-free is the whole
  /// point; an `await` here would reopen that race. Mirrors the same-isolation
  /// guarantee `insertPendingSendAssigningSequence` relies on.
  ///
  /// Within `NodeSnapshotPolicy.minimumInterval` of the latest snapshot the
  /// capture enriches that row: status fields are applied only when the row is
  /// still telemetry-only (`uptimeSeconds == nil`), preserving the
  /// one-status-point-per-window throttle; telemetry and neighbor arrays are
  /// applied whenever supplied. Outside the window a new snapshot is inserted.
  func recordNodeStatusSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?
  ) throws -> UUID {
    var latestDescriptor = FetchDescriptor<NodeStatusSnapshot>(
      predicate: #Predicate { $0.nodePublicKey == nodePublicKey },
      sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
    )
    latestDescriptor.fetchLimit = 1

    if let latest = try modelContext.fetch(latestDescriptor).first,
       latest.timestamp.distance(to: .now) < NodeSnapshotPolicy.minimumInterval {
      if let status, latest.uptimeSeconds == nil {
        latest.apply(status)
      }
      if let telemetry {
        latest.telemetryEntries = telemetry
      }
      if let neighbors {
        latest.neighborSnapshots = neighbors
      }
      try modelContext.save()
      return latest.id
    }

    let snapshot = NodeStatusSnapshot(
      nodePublicKey: nodePublicKey,
      telemetryEntries: telemetry
    )
    if let status {
      snapshot.apply(status)
    }
    if let neighbors {
      snapshot.neighborSnapshots = neighbors
    }
    modelContext.insert(snapshot)
    try modelContext.save()
    return snapshot.id
  }

  func deleteOldNodeStatusSnapshots(olderThan date: Date) throws {
    try modelContext.delete(
      model: NodeStatusSnapshot.self,
      where: #Predicate { $0.timestamp < date }
    )
    try modelContext.save()
  }
}
