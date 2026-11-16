import Foundation
import MeshCore
import os
import SwiftData

private let rxLogFlushLogger = Logger(subsystem: "com.mc1", category: "PersistenceStore")

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
      regionScopeMatches: dto.regionScopeMatches,
      payloadTypeBits: Int(dto.payloadTypeBits)
    )
    modelContext.insert(entry)
    rxLogEntryCountsByDevice[dto.radioID] = countBeforeInsert + 1
    unsavedRxLogInsertCount += 1

    if unsavedRxLogInsertCount >= RxLogRetention.batchSize {
      try commitPendingRxLogEntries()
    } else {
      scheduleRxLogFlush()
    }
  }

  /// Exact `async throws` match for `RxLogPersisting` so this implementation is
  /// the protocol witness; a `throws`-only method loses to the default no-op.
  func flushPendingRxLogEntries() async throws {
    try commitPendingRxLogEntries()
  }

  private func scheduleRxLogFlush() {
    guard rxLogFlushTask == nil else { return }
    rxLogFlushTask = Task {
      try? await Task.sleep(for: RxLogRetention.flushInterval)
      guard !Task.isCancelled else { return }
      do {
        try await self.flushPendingRxLogEntries()
      } catch {
        rxLogFlushLogger.error("Failed to flush pending RX log entries: \(error.localizedDescription)")
      }
    }
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
  /// retention bounded to `keepCount + pruneThreshold + batchSize - 1` entries between
  /// flush-time prune passes.
  func pruneRxLogEntries(
    radioID: UUID,
    keepCount: Int = RxLogRetention.keepCount,
    pruneThreshold: Int = RxLogRetention.pruneThreshold
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

  func fetchRxLogEntries(
    radioID: UUID,
    channelIndex: UInt8,
    senderTimestamp: UInt32
  ) throws -> [RxLogEntryDTO] {
    let targetRadioID = radioID
    let targetTimestamp = Int(senderTimestamp)
    let channelIndexInt = Int(channelIndex)

    let predicate = #Predicate<RxLogEntry> { entry in
      entry.radioID == targetRadioID &&
        entry.channelIndex == channelIndexInt &&
        entry.senderTimestamp == targetTimestamp
    }

    let descriptor = FetchDescriptor<RxLogEntry>(
      predicate: predicate,
      sortBy: [SortDescriptor(\.receivedAt, order: .forward)]
    )

    return try modelContext.fetch(descriptor).map { RxLogEntryDTO(from: $0) }
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

  /// Fetch transport-coded RX log entries for region reprocess.
  /// Bound by prune retention; includes rows that already have a label so
  /// unique, multi-match, and clear rewrites all work.
  func fetchEntriesWithTransportCode(radioID: UUID, limit: Int) throws -> [RxLogEntryDTO] {
    let targetRadioID = radioID
    var descriptor = FetchDescriptor<RxLogEntry>(
      predicate: #Predicate {
        $0.radioID == targetRadioID &&
          $0.transportCode != nil
      },
      sortBy: [SortDescriptor(\.receivedAt, order: .reverse)]
    )
    descriptor.fetchLimit = limit
    let entries = try modelContext.fetch(descriptor)
    return entries.map { RxLogEntryDTO(from: $0) }
  }

  /// Batch update dual region fields on RX log entries by id.
  func batchUpdateRxLogRegion(
    updates: [(id: UUID, regionScope: String?, regionScopeMatches: [String])]
  ) throws {
    guard !updates.isEmpty else { return }
    let byID = Dictionary(uniqueKeysWithValues: updates.map { ($0.id, $0) })
    let targetIDs = Array(byID.keys)
    // Chunk for SQLite variable limits on large reprocess writes.
    let chunkSize = 200
    for start in stride(from: 0, to: targetIDs.count, by: chunkSize) {
      let chunk = Array(targetIDs[start..<min(start + chunkSize, targetIDs.count)])
      let descriptor = FetchDescriptor<RxLogEntry>(
        predicate: #Predicate { chunk.contains($0.id) }
      )
      for entry in try modelContext.fetch(descriptor) {
        guard let update = byID[entry.id] else { continue }
        entry.regionScope = update.regionScope
        entry.regionScopeMatches = update.regionScopeMatches
      }
    }
    try modelContext.save()
  }

  /// Batch update dual region fields on incoming channel `Message` rows
  /// correlated by `(channelIndex, senderTimestamp)`. The wire timestamp
  /// fallback is required because `Message.senderTimestamp` is only
  /// populated for the rare timestamp-corrected case; the normal case puts
  /// the wire timestamp on `Message.timestamp`.
  @discardableResult
  func batchUpdateChannelMessageRegion(
    radioID: UUID,
    updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])]
  ) throws -> [UUID] {
    let targetRadioID = radioID
    let incoming = MessageDirection.incoming.rawValue
    var touchedIDs: [UUID] = []
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
        message.regionScopeMatches = update.regionScopeMatches
        touchedIDs.append(message.id)
      }
    }
    try modelContext.save()
    return touchedIDs
  }

  /// Batch update dual region fields on incoming DM `Message` rows. DMs
  /// carry the sender prefix byte at `RxLogEntry.packetPayload[1]` but
  /// the Message side stores the full multi-byte `senderKeyPrefix`, so
  /// the predicate fetches by timestamp + DM channel and an in-memory
  /// pass disambiguates by first-byte equality. Mirrors the correlation
  /// key used by `findRxLogEntryBySenderPrefix`.
  @discardableResult
  func batchUpdateDMMessageRegion(
    radioID: UUID,
    updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])]
  ) throws -> [UUID] {
    let targetRadioID = radioID
    let nilChannel: UInt8? = nil
    let incoming = MessageDirection.incoming.rawValue
    var touchedIDs: [UUID] = []
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
        message.regionScopeMatches = update.regionScopeMatches
        touchedIDs.append(message.id)
      }
    }
    try modelContext.save()
    return touchedIDs
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

  /// Prunes debug log entries older than the cutoff, then enforces a hard
  /// row ceiling by deleting the oldest remaining entries.
  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) throws {
    let cutoffDate = cutoff
    try modelContext.delete(
      model: DebugLogEntry.self,
      where: #Predicate { $0.timestamp < cutoffDate }
    )

    let count = try countDebugLogEntries()
    if count > keepCount {
      var descriptor = FetchDescriptor<DebugLogEntry>(
        sortBy: [SortDescriptor(\.timestamp, order: .forward)]
      )
      descriptor.fetchLimit = count - keepCount
      let toDelete = try modelContext.fetch(descriptor)
      for entry in toDelete {
        modelContext.delete(entry)
      }
    }
    try modelContext.save()
  }

  /// Clears all debug log entries.
  func clearDebugLogEntries() throws {
    try modelContext.delete(model: DebugLogEntry.self)
    try modelContext.save()
  }

  // MARK: - Node Status Snapshots

  private static let snapshotFetchBatchSize = 32

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
    try latestSnapshot(nodePublicKey: nodePublicKey, selfRadioID: nil).map(NodeStatusSnapshotDTO.init)
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) throws -> [NodeStatusSnapshotDTO] {
    try snapshots(nodePublicKey: nodePublicKey, selfRadioID: nil, since: since).map(NodeStatusSnapshotDTO.init)
  }

  func fetchSelfTelemetrySnapshots(
    radioID: UUID,
    nodePublicKey: Data
  ) throws -> [NodeStatusSnapshotDTO] {
    try snapshots(nodePublicKey: nodePublicKey, selfRadioID: radioID, since: nil).map(NodeStatusSnapshotDTO.init)
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

  /// Coalesces remote captures in one store turn so concurrent reads share the same window.
  func recordNodeStatusSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?,
    location: NodeLocationFix?
  ) throws -> UUID {
    let capturedAt = Date.now
    let latest = try latestSnapshot(
      nodePublicKey: nodePublicKey,
      selfRadioID: nil,
      since: capturedAt.addingTimeInterval(-NodeSnapshotPolicy.minimumInterval)
    )
    return try insertOrEnrichSnapshot(
      latest: latest,
      capturedAt: capturedAt,
      nodePublicKey: nodePublicKey,
      selfRadioID: nil,
      status: status,
      telemetry: telemetry,
      neighbors: neighbors,
      location: location
    )
  }

  func recordSelfTelemetrySnapshot(
    deviceID: UUID,
    radioID: UUID,
    nodePublicKey: Data,
    telemetry: [TelemetrySnapshotEntry]?,
    location: NodeLocationFix?
  ) throws -> UUID? {
    let targetID = deviceID
    var descriptor = FetchDescriptor<Device>(predicate: #Predicate { $0.id == targetID })
    descriptor.fetchLimit = 1
    // Keep the identity check and write in one turn so deletion cannot interleave.
    guard let device = try modelContext.fetch(descriptor).first,
          device.radioID == radioID, device.publicKey == nodePublicKey else { return nil }
    let capturedAt = Date.now
    let latest = try latestSnapshot(
      nodePublicKey: nodePublicKey,
      selfRadioID: radioID,
      since: capturedAt.addingTimeInterval(-NodeSnapshotPolicy.minimumInterval)
    )
    // Give the first fix its own row so History uses the time it was captured.
    let addsFirstFix = location != nil && latest?.latitude == nil
    return try insertOrEnrichSnapshot(
      latest: addsFirstFix ? nil : latest,
      capturedAt: capturedAt,
      nodePublicKey: nodePublicKey,
      selfRadioID: radioID,
      status: nil,
      telemetry: telemetry,
      neighbors: nil,
      location: location
    )
  }

  private func latestSnapshot(
    nodePublicKey: Data,
    selfRadioID: UUID?,
    since: Date? = nil
  ) throws -> NodeStatusSnapshot? {
    var descriptor = snapshotDescriptor(nodePublicKey: nodePublicKey, since: since, order: .reverse)
    if since != nil || modelContext.hasChanges {
      return try modelContext.fetch(descriptor).first { $0.selfRadioID == selfRadioID }
    }
    descriptor.includePendingChanges = false
    let rows = try modelContext.fetch(descriptor, batchSize: Self.snapshotFetchBatchSize)
    return rows.first { $0.selfRadioID == selfRadioID }
  }

  /// Match the optional owner in Swift to include legacy nil-owner rows.
  private func snapshots(nodePublicKey: Data, selfRadioID: UUID?, since: Date?) throws -> [NodeStatusSnapshot] {
    let descriptor = snapshotDescriptor(nodePublicKey: nodePublicKey, since: since, order: .forward)
    return try modelContext.fetch(descriptor).filter { $0.selfRadioID == selfRadioID }
  }

  private func snapshotDescriptor(
    nodePublicKey: Data,
    since: Date?,
    order: SortOrder
  ) -> FetchDescriptor<NodeStatusSnapshot> {
    let key = nodePublicKey
    let sortBy = [SortDescriptor(\NodeStatusSnapshot.timestamp, order: order)]
    if let since {
      return FetchDescriptor(
        predicate: #Predicate { $0.nodePublicKey == key && $0.timestamp >= since },
        sortBy: sortBy
      )
    }
    return FetchDescriptor(predicate: #Predicate { $0.nodePublicKey == key }, sortBy: sortBy)
  }

  private func insertOrEnrichSnapshot(
    latest: NodeStatusSnapshot?,
    capturedAt: Date,
    nodePublicKey: Data,
    selfRadioID: UUID?,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?,
    location: NodeLocationFix?
  ) throws -> UUID {
    if let latest, latest.timestamp.distance(to: capturedAt) < NodeSnapshotPolicy.minimumInterval {
      if let status, latest.uptimeSeconds == nil {
        latest.apply(status)
      }
      if let telemetry {
        latest.telemetryEntries = telemetry
      }
      if let neighbors {
        latest.neighborSnapshots = neighbors
      }
      if let location, latest.latitude == nil {
        latest.latitude = location.latitude
        latest.longitude = location.longitude
        latest.altitude = location.altitude
      }
      try modelContext.save()
      return latest.id
    }

    let snapshot = NodeStatusSnapshot(
      timestamp: capturedAt,
      nodePublicKey: nodePublicKey,
      selfRadioID: selfRadioID,
      telemetryEntries: telemetry
    )
    if let status {
      snapshot.apply(status)
    }
    if let neighbors {
      snapshot.neighborSnapshots = neighbors
    }
    if let location {
      snapshot.latitude = location.latitude
      snapshot.longitude = location.longitude
      snapshot.altitude = location.altitude
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
