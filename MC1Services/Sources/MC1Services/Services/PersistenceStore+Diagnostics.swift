import Foundation
import MeshCore
import SwiftData

extension PersistenceStore {

    // MARK: - Saved Trace Path Operations

    public func fetchSavedTracePaths(deviceID: UUID) throws -> [SavedTracePathDTO] {
        let targetDeviceID = deviceID
        let descriptor = FetchDescriptor<SavedTracePath>(
            predicate: #Predicate { $0.deviceID == targetDeviceID },
            sortBy: [SortDescriptor(\.createdDate, order: .reverse)]
        )
        let paths = try modelContext.fetch(descriptor)
        return paths.map { SavedTracePathDTO(from: $0) }
    }

    public func fetchSavedTracePath(id: UUID) throws -> SavedTracePathDTO? {
        let targetID = id
        let descriptor = FetchDescriptor<SavedTracePath>(
            predicate: #Predicate { $0.id == targetID }
        )
        guard let path = try modelContext.fetch(descriptor).first else { return nil }
        return SavedTracePathDTO(from: path)
    }

    public func createSavedTracePath(
        deviceID: UUID,
        name: String,
        pathBytes: Data,
        hashSize: Int = 1,
        initialRun: TracePathRunDTO?
    ) throws -> SavedTracePathDTO {
        let path = SavedTracePath(
            deviceID: deviceID,
            name: name,
            pathBytes: pathBytes,
            hashSize: hashSize
        )

        if let runDTO = initialRun {
            let run = TracePathRun(
                id: runDTO.id,
                date: runDTO.date,
                success: runDTO.success,
                roundTripMs: runDTO.roundTripMs,
                hopsData: (try? JSONEncoder().encode(runDTO.hopsSNR)) ?? Data()
            )
            run.savedPath = path
            path.runs.append(run)
            modelContext.insert(run)
        }

        modelContext.insert(path)
        try modelContext.save()
        return SavedTracePathDTO(from: path)
    }

    public func updateSavedTracePathName(id: UUID, name: String) throws {
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

    public func deleteSavedTracePath(id: UUID) throws {
        let targetID = id
        let descriptor = FetchDescriptor<SavedTracePath>(
            predicate: #Predicate { $0.id == targetID }
        )
        guard let path = try modelContext.fetch(descriptor).first else { return }
        modelContext.delete(path)
        try modelContext.save()
    }

    public func appendTracePathRun(pathID: UUID, run runDTO: TracePathRunDTO) throws {
        let targetID = pathID
        let descriptor = FetchDescriptor<SavedTracePath>(
            predicate: #Predicate { $0.id == targetID }
        )
        guard let path = try modelContext.fetch(descriptor).first else {
            throw PersistenceStoreError.fetchFailed("SavedTracePath not found")
        }

        let run = TracePathRun(
            id: runDTO.id,
            date: runDTO.date,
            success: runDTO.success,
            roundTripMs: runDTO.roundTripMs,
            hopsData: (try? JSONEncoder().encode(runDTO.hopsSNR)) ?? Data()
        )
        run.savedPath = path
        path.runs.append(run)
        modelContext.insert(run)
        try modelContext.save()
    }

    // MARK: - RxLogEntry

    /// Save a new RX log entry.
    public func saveRxLogEntry(_ dto: RxLogEntryDTO) throws {
        let entry = RxLogEntry(
            id: dto.id,
            deviceID: dto.deviceID,
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
            senderTimestamp: dto.senderTimestamp.map { Int($0) }
        )
        modelContext.insert(entry)
        try modelContext.save()
        rxLogEntryCountsByDevice[dto.deviceID, default: 0] += 1
    }

    /// Fetch RX log entries for a device, most recent first.
    public func fetchRxLogEntries(deviceID: UUID, limit: Int = 500) throws -> [RxLogEntryDTO] {
        let targetDeviceID = deviceID
        var descriptor = FetchDescriptor<RxLogEntry>(
            predicate: #Predicate { $0.deviceID == targetDeviceID },
            sortBy: [SortDescriptor(\.receivedAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        let entries = try modelContext.fetch(descriptor)
        return entries.map { RxLogEntryDTO(from: $0) }
    }

    /// Count RX log entries for a device.
    public func countRxLogEntries(deviceID: UUID) throws -> Int {
        let targetDeviceID = deviceID
        let descriptor = FetchDescriptor<RxLogEntry>(
            predicate: #Predicate { $0.deviceID == targetDeviceID }
        )
        return try modelContext.fetchCount(descriptor)
    }

    /// Delete oldest entries once the log materially exceeds the retention cap.
    ///
    /// This avoids repeated count/fetch/delete maintenance on every RX packet while keeping
    /// retention bounded to `keepCount + pruneThreshold` entries between prune passes.
    public func pruneRxLogEntries(
        deviceID: UUID,
        keepCount: Int = 1000,
        pruneThreshold: Int = 100
    ) throws {
        let count = try cachedRxLogEntryCount(deviceID: deviceID)
        guard count > keepCount + pruneThreshold else { return }

        let deleteCount = count - keepCount
        let targetDeviceID = deviceID

        var descriptor = FetchDescriptor<RxLogEntry>(
            predicate: #Predicate { $0.deviceID == targetDeviceID },
            sortBy: [SortDescriptor(\.receivedAt, order: .forward)]  // Oldest first
        )
        descriptor.fetchLimit = deleteCount

        let toDelete = try modelContext.fetch(descriptor)
        for entry in toDelete {
            modelContext.delete(entry)
        }
        try modelContext.save()
        rxLogEntryCountsByDevice[deviceID] = keepCount
    }

    /// Clear all RX log entries for a device.
    public func clearRxLogEntries(deviceID: UUID) throws {
        let targetDeviceID = deviceID
        let descriptor = FetchDescriptor<RxLogEntry>(
            predicate: #Predicate { $0.deviceID == targetDeviceID }
        )
        let entries = try modelContext.fetch(descriptor)
        for entry in entries {
            modelContext.delete(entry)
        }
        try modelContext.save()
        rxLogEntryCountsByDevice[deviceID] = 0
    }

    private func cachedRxLogEntryCount(deviceID: UUID) throws -> Int {
        if let cached = rxLogEntryCountsByDevice[deviceID] {
            return cached
        }

        let count = try countRxLogEntries(deviceID: deviceID)
        rxLogEntryCountsByDevice[deviceID] = count
        return count
    }

    /// Find RxLogEntry matching an incoming message for path correlation.
    ///
    /// For channel messages: Correlates by channel index and sender timestamp.
    /// For direct messages: Correlates by sender timestamp and payload type.
    public func findRxLogEntry(
        channelIndex: UInt8?,
        senderTimestamp: UInt32
    ) throws -> RxLogEntryDTO? {
        let targetTimestamp = Int(senderTimestamp)

        if let channelIndex {
            // Channel message: match on channelIndex and senderTimestamp
            let channelIndexInt = Int(channelIndex)

            let predicate = #Predicate<RxLogEntry> { entry in
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
    public func findRxLogEntryBySenderPrefix(
        senderPrefixByte: UInt8,
        receivedSince: Date
    ) throws -> RxLogEntryDTO? {
        let textMessageType = Int(PayloadType.textMessage.rawValue)
        let cutoff = receivedSince

        let predicate = #Predicate<RxLogEntry> { entry in
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
    public func fetchRecentEntriesByDecryptStatus(deviceID: UUID, status: DecryptStatus, since: Date) throws -> [RxLogEntryDTO] {
        let targetDeviceID = deviceID
        let targetStatus = status.rawValue
        let cutoff = since
        let descriptor = FetchDescriptor<RxLogEntry>(
            predicate: #Predicate {
                $0.deviceID == targetDeviceID &&
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
    public func batchUpdateRxLogDecryption(
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

    // MARK: - Debug Log Entries

    /// Saves a batch of debug log entries.
    public func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) throws {
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
    public func fetchDebugLogEntries(since date: Date, limit: Int = 1000) throws -> [DebugLogEntryDTO] {
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
    public func countDebugLogEntries() throws -> Int {
        let descriptor = FetchDescriptor<DebugLogEntry>()
        return try modelContext.fetchCount(descriptor)
    }

    /// Prunes debug log entries, keeping only the most recent entries.
    public func pruneDebugLogEntries(keepCount: Int = 1000) throws {
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
    public func clearDebugLogEntries() throws {
        try modelContext.delete(model: DebugLogEntry.self)
        try modelContext.save()
    }

    // MARK: - Node Status Snapshots

    public func saveNodeStatusSnapshot(
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
    public func saveNodeStatusSnapshot(
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

    public func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) throws -> NodeStatusSnapshotDTO? {
        var descriptor = FetchDescriptor<NodeStatusSnapshot>(
            predicate: #Predicate { $0.nodePublicKey == nodePublicKey },
            sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map(NodeStatusSnapshotDTO.init)
    }

    public func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) throws -> [NodeStatusSnapshotDTO] {
        let descriptor: FetchDescriptor<NodeStatusSnapshot>
        if let since {
            descriptor = FetchDescriptor<NodeStatusSnapshot>(
                predicate: #Predicate { $0.nodePublicKey == nodePublicKey && $0.timestamp >= since },
                sortBy: [SortDescriptor(\.timestamp)]
            )
        } else {
            descriptor = FetchDescriptor<NodeStatusSnapshot>(
                predicate: #Predicate { $0.nodePublicKey == nodePublicKey },
                sortBy: [SortDescriptor(\.timestamp)]
            )
        }
        return try modelContext.fetch(descriptor).map(NodeStatusSnapshotDTO.init)
    }

    public func fetchPreviousNodeStatusSnapshot(nodePublicKey: Data, before: Date) throws -> NodeStatusSnapshotDTO? {
        var descriptor = FetchDescriptor<NodeStatusSnapshot>(
            predicate: #Predicate { $0.nodePublicKey == nodePublicKey && $0.timestamp < before },
            sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map(NodeStatusSnapshotDTO.init)
    }

    public func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) throws {
        var descriptor = FetchDescriptor<NodeStatusSnapshot>(
            predicate: #Predicate { $0.id == id }
        )
        descriptor.fetchLimit = 1
        guard let snapshot = try modelContext.fetch(descriptor).first else { return }
        snapshot.neighborSnapshots = neighbors
        try modelContext.save()
    }

    public func saveTelemetryOnlySnapshot(
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

    public func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) throws {
        var descriptor = FetchDescriptor<NodeStatusSnapshot>(
            predicate: #Predicate { $0.id == id }
        )
        descriptor.fetchLimit = 1
        guard let snapshot = try modelContext.fetch(descriptor).first else { return }
        snapshot.telemetryEntries = telemetry
        try modelContext.save()
    }

    public func deleteOldNodeStatusSnapshots(olderThan date: Date) throws {
        try modelContext.delete(
            model: NodeStatusSnapshot.self,
            where: #Predicate { $0.timestamp < date }
        )
        try modelContext.save()
    }
}
