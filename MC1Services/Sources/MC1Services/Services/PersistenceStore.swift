import Foundation
import os
import SwiftData

// MARK: - PersistenceStore Errors

public enum PersistenceStoreError: Error, Sendable {
    case deviceNotFound
    case contactNotFound
    case messageNotFound
    case channelNotFound
    case remoteNodeSessionNotFound
    case saveFailed(String)
    case fetchFailed(String)
    case invalidData
}

extension PersistenceStoreError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .deviceNotFound: "Device not found."
        case .contactNotFound: "Contact not found."
        case .messageNotFound: "Message not found."
        case .channelNotFound: "Channel not found."
        case .remoteNodeSessionNotFound: "Remote node session not found."
        case .saveFailed(let msg): "Failed to save: \(msg)"
        case .fetchFailed(let msg): "Failed to fetch: \(msg)"
        case .invalidData: "Invalid data."
        }
    }
}

// MARK: - PersistenceStore Actor

/// ModelActor for background SwiftData operations.
/// Provides per-device data isolation and thread-safe access.
@ModelActor
public actor PersistenceStore: PersistenceStoreProtocol {
    var rxLogEntryCountsByDevice: [UUID: Int] = [:]

    #if DEBUG
    /// Test-only fault-injection hook fired immediately before `modelContext.save()`
    /// in `importBackupDatabase`. Debug-only so the production API stays clean.
    /// SwiftData upserts on unique-constraint conflicts, so there is no reliable
    /// in-band way to provoke a save failure from a well-formed envelope.
    var backupImportFaultInjection: (@Sendable () throws -> Void)?

    /// Test-only hook fired after `modelContext.save()` succeeds and the
    /// transaction is committed. Tests use it to cancel the outer task so
    /// they can assert post-commit behaviour of `importBackup`.
    var backupImportPostCommitHook: (@Sendable () -> Void)?

    /// Test-only fault injection fired at the top of
    /// `incrementPendingSendAttemptCount`. Tests use it to verify the
    /// bump-failure park-and-requeue path in `ChatSendQueueService`'s drain
    /// closures — a real SwiftData save failure here is otherwise hard to
    /// provoke from a well-formed row.
    var incrementPendingSendAttemptCountFaultInjection: (@Sendable () throws -> Void)?
    #endif

    /// Shared schema for MeshCore One models
    public static let schema = Schema([
        Device.self,
        Contact.self,
        Message.self,
        MessageRepeat.self,
        Reaction.self,
        Channel.self,
        RemoteNodeSession.self,
        RoomMessage.self,
        SavedTracePath.self,
        TracePathRun.self,
        RxLogEntry.self,
        DebugLogEntry.self,
        LinkPreviewData.self,
        DiscoveredNode.self,
        NodeStatusSnapshot.self,
        BlockedChannelSender.self,
        PendingSend.self
    ])

    /// Creates a ModelContainer for the app.
    ///
    /// Schema evolution (no VersionedSchema — handled via lightweight migration):
    /// - v1→v2: Contact.outPathLength, DiscoveredNode.outPathLength changed Int8→UInt8
    ///          (SQLite INTEGER is identical for both; bit pattern -1 == 0xFF).
    ///          Added MessageRepeat.pathLength (UInt8, default 0).
    ///          Added SavedTracePath.hashSize (Int, default 1).
    /// - v2→v3: Added PendingSend (new table; no migration impact on existing rows).
    /// - v3→v4: Added PendingSend.attemptCount (Int?, default nil). Existing rows
    ///          lightweight-migrate to NULL; PersistenceStore.warmUp() runs
    ///          purgeLegacyAttemptCountRows() on connect to delete any nil row.
    /// - v4→v5: Added TracePathRun.id uniqueness (run ids are minted once and
    ///          immutable, and backup import dedupes them store-wide, so existing
    ///          stores hold no duplicates) and RxLogEntry [radioID, receivedAt]
    ///          index.
    public static func createContainer(inMemory: Bool = false) throws -> ModelContainer {
        if !inMemory {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
        }

        let configuration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: inMemory,
            allowsSave: true
        )
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    // MARK: - Database Warm-up

    /// Forces SwiftData to initialize the database.
    /// Call this early in app lifecycle to avoid lazy initialization during user operations.
    public func warmUp() throws {
        // Perform a simple fetch to trigger modelContext initialization
        _ = try modelContext.fetchCount(FetchDescriptor<Device>())

        // Both inner operations are idempotent under their own predicates:
        // purgeOrphanPendingSends filters by absence of a matching
        // Device.radioID and returns an empty row set once Device rows catch
        // up; purgeLegacyAttemptCountRows filters by `attemptCount == nil`
        // and returns empty after the first purge across the lifetime of
        // the storage. Running both on every connect costs an empty fetch and
        // self-heals the "erase device then re-pair" path — a process-level
        // latch would suppress that recovery.
        let logger = Logger(subsystem: "com.mc1", category: "PersistenceStore.warmUp")
        do {
            try purgeOrphanPendingSends()
        } catch {
            logger.warning("purgeOrphanPendingSends failed: \(error.localizedDescription, privacy: .public)")
        }
        do {
            try purgeLegacyAttemptCountRows()
        } catch {
            logger.warning("purgeLegacyAttemptCountRows failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: - Discovered Nodes

    private let maxDiscoveredNodes = 1000

    /// Inbound advert hop counts heard via the RX log before the matching node row exists.
    /// The firmware emits the 0x88 RX packet before the 0x80 advert push that creates the row,
    /// so a keyed write would otherwise miss on first contact. Buffered here and drained by the
    /// upsert when the row lands. Bounded; the partner advert normally drains an entry at once.
    private var pendingInboundHops: [PendingHopKey: PendingHop] = [:]
    private let maxPendingInboundHops = 256

    private struct PendingHopKey: Hashable {
        let radioID: UUID
        let publicKey: Data
    }

    private struct PendingHop {
        let hopCount: Int
        let advertTimestamp: UInt32?
    }

    public func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
        let targetRadioID = radioID
        let publicKey = frame.publicKey
        let predicate = #Predicate<DiscoveredNode> { node in
            node.radioID == targetRadioID && node.publicKey == publicKey
        }
        var descriptor = FetchDescriptor<DiscoveredNode>(predicate: predicate)
        descriptor.fetchLimit = 1
        let existing = try modelContext.fetch(descriptor)

        let node: DiscoveredNode
        let isNew: Bool
        if let existingNode = existing.first {
            existingNode.name = frame.name
            existingNode.typeRawValue = frame.type.rawValue
            existingNode.lastHeard = Date()
            existingNode.lastAdvertTimestamp = frame.lastAdvertTimestamp
            existingNode.latitude = frame.latitude
            existingNode.longitude = frame.longitude
            existingNode.outPathLength = frame.outPathLength
            existingNode.outPath = frame.outPath
            node = existingNode
            isNew = false
        } else {
            node = DiscoveredNode(
                radioID: radioID,
                publicKey: frame.publicKey,
                name: frame.name,
                typeRawValue: frame.type.rawValue,
                lastAdvertTimestamp: frame.lastAdvertTimestamp,
                latitude: frame.latitude,
                longitude: frame.longitude,
                outPathLength: frame.outPathLength,
                outPath: frame.outPath
            )
            modelContext.insert(node)
            isNew = true

            try enforceDiscoveredNodeCap(radioID: radioID)
        }

        applyPendingInboundHop(to: node)

        try modelContext.save()

        // Temporary Discover trace: confirm the row committed and report the
        // post-save row count for this radio (catches silent cap eviction).
        // Filter by category "discover-trace"; remove with the matching probes.
        let radioRows = (try? modelContext.fetchCount(
            FetchDescriptor<DiscoveredNode>(predicate: #Predicate { $0.radioID == targetRadioID })
        )) ?? -1
        PersistentLogger(subsystem: "com.mc1", category: "discover-trace")
            .info("B3 persisted DiscoveredNode isNew=\(isNew) radioRows=\(radioRows)/\(maxDiscoveredNodes)")

        return (node: DiscoveredNodeDTO(from: node), isNew: isNew)
    }

    public func setInboundHopCount(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) throws {
        let targetRadioID = radioID
        let targetKey = publicKey
        let predicate = #Predicate<DiscoveredNode> { node in
            node.radioID == targetRadioID && node.publicKey == targetKey
        }
        var descriptor = FetchDescriptor<DiscoveredNode>(predicate: predicate)
        descriptor.fetchLimit = 1
        // No row yet: buffer the pair so the advert upsert drains it on first contact.
        guard let node = try modelContext.fetch(descriptor).first else {
            bufferPendingInboundHop(radioID: targetRadioID, publicKey: targetKey, hopCount: hopCount, advertTimestamp: advertTimestamp)
            return
        }
        guard let adopted = adoptInboundHop(
            storedHops: node.inboundHopCount,
            storedTimestamp: node.inboundHopAdvertTimestamp,
            incomingHops: hopCount,
            incomingTimestamp: advertTimestamp
        ) else {
            return
        }
        node.inboundHopCount = adopted.hopCount
        node.inboundHopAdvertTimestamp = adopted.advertTimestamp
        try modelContext.save()
    }

    /// Stash an inbound hop count heard before its node row exists. Applies the same adopt rule
    /// as the live-row path. Evicts an arbitrary entry past the cap so a flood of never-resolved
    /// adverts can't grow this without bound.
    private func bufferPendingInboundHop(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) {
        let key = PendingHopKey(radioID: radioID, publicKey: publicKey)
        let existing = pendingInboundHops[key]
        guard let adopted = adoptInboundHop(
            storedHops: existing?.hopCount,
            storedTimestamp: existing?.advertTimestamp,
            incomingHops: hopCount,
            incomingTimestamp: advertTimestamp
        ) else { return }
        if existing == nil,
           pendingInboundHops.count >= maxPendingInboundHops,
           let evicted = pendingInboundHops.keys.first {
            pendingInboundHops.removeValue(forKey: evicted)
        }
        pendingInboundHops[key] = PendingHop(hopCount: adopted.hopCount, advertTimestamp: adopted.advertTimestamp)
    }

    /// Apply and clear any inbound hop count buffered before this row existed.
    private func applyPendingInboundHop(to node: DiscoveredNode) {
        let key = PendingHopKey(radioID: node.radioID, publicKey: node.publicKey)
        guard let buffered = pendingInboundHops.removeValue(forKey: key) else { return }
        if let adopted = adoptInboundHop(
            storedHops: node.inboundHopCount,
            storedTimestamp: node.inboundHopAdvertTimestamp,
            incomingHops: buffered.hopCount,
            incomingTimestamp: buffered.advertTimestamp
        ) {
            node.inboundHopCount = adopted.hopCount
            node.inboundHopAdvertTimestamp = adopted.advertTimestamp
        }
    }

    private func enforceDiscoveredNodeCap(radioID: UUID) throws {
        let targetRadioID = radioID
        let countPredicate = #Predicate<DiscoveredNode> { $0.radioID == targetRadioID }
        let countDescriptor = FetchDescriptor<DiscoveredNode>(predicate: countPredicate)
        let count = try modelContext.fetchCount(countDescriptor)

        if count > maxDiscoveredNodes {
            var oldestDescriptor = FetchDescriptor<DiscoveredNode>(
                predicate: countPredicate,
                sortBy: [SortDescriptor(\.lastHeard, order: .forward)]
            )
            oldestDescriptor.fetchLimit = count - maxDiscoveredNodes
            let toDelete = try modelContext.fetch(oldestDescriptor)
            for node in toDelete {
                modelContext.delete(node)
            }
            let logger = Logger(subsystem: "com.mc1", category: "PersistenceStore")
            logger.warning("DiscoveredNode cap exceeded, evicted \(toDelete.count) oldest nodes")
        }
    }

    public func fetchDiscoveredNodes(radioID: UUID) throws -> [DiscoveredNodeDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<DiscoveredNode> { $0.radioID == targetRadioID }
        let descriptor = FetchDescriptor<DiscoveredNode>(predicate: predicate)
        let nodes = try modelContext.fetch(descriptor)
        return nodes.map { DiscoveredNodeDTO(from: $0) }
    }

    public func deleteDiscoveredNode(id: UUID) throws {
        let targetID = id
        let predicate = #Predicate<DiscoveredNode> { $0.id == targetID }
        var descriptor = FetchDescriptor<DiscoveredNode>(predicate: predicate)
        descriptor.fetchLimit = 1
        if let node = try modelContext.fetch(descriptor).first {
            modelContext.delete(node)
            try modelContext.save()
        }
    }

    public func clearDiscoveredNodes(radioID: UUID) throws {
        let targetRadioID = radioID
        let predicate = #Predicate<DiscoveredNode> { $0.radioID == targetRadioID }
        let descriptor = FetchDescriptor<DiscoveredNode>(predicate: predicate)
        let nodes = try modelContext.fetch(descriptor)
        for node in nodes {
            modelContext.delete(node)
        }
        try modelContext.save()
    }
}
