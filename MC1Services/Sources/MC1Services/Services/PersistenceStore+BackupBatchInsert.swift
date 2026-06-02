import Foundation
import SwiftData

/// Slot 0 is reserved for the public channel (`Channel.isPublicChannel` is `index == 0`),
/// so backup-channel relocation never assigns a non-public channel there.
private let firstRelocatableChannelIndex = 1

// MARK: - Batch Insert

extension PersistenceStore {

    /// Inserts DTOs into the model context, skipping any whose `key` is
    /// already in `existingKeys`. Covers the "insert if absent" shape used
    /// by most backup batch-insert paths.
    ///
    /// Dedup is by business `key`; each model's surrogate `id` is a random UUIDv4 minted once
    /// at creation, so a colliding `id` with a divergent business key is statistically
    /// unreachable on any normal path. We deliberately do not re-mint ids here: re-minting a
    /// child's `id` would orphan its inbound foreign keys (`Reaction.messageID`/
    /// `MessageRepeat.messageID` key on `Message.id`; `replyToID` is rewritten only for
    /// duplicate parents). Only Device (recurring CBPeripheral id) and Channel (children key by
    /// slot/`channelIndex`, never `Channel.id`) are safe to re-mint, and both already do
    /// (`batchInsertDevices`, `batchInsertChannels`). If a future change ever makes an `id`
    /// content-derived or reused across rows, revisit this — a colliding insert would upsert the
    /// local row.
    @discardableResult
    private func insertUnique<DTO, M: PersistentModel, Key: Hashable>(
        _ dtos: [DTO],
        existingKeys: Set<Key>,
        key: (DTO) -> Key,
        construct: (DTO) -> M
    ) -> (inserted: Int, skipped: Int) {
        var knownKeys = existingKeys
        var inserted = 0
        var skipped = 0
        for dto in dtos {
            if !knownKeys.insert(key(dto)).inserted {
                skipped += 1
                continue
            }
            modelContext.insert(construct(dto))
            inserted += 1
        }
        return (inserted, skipped)
    }

    /// Inserts DTOs whose parent exists in `existingParentIDs`, skipping
    /// orphans or duplicate keys. Inserted DTOs contribute their parent
    /// ID to `affectedParentIDs` for downstream recompute work.
    @discardableResult
    private func insertUniqueWithParent<DTO, M: PersistentModel, Key: Hashable, ParentKey: Hashable>(
        _ dtos: [DTO],
        existingKeys: Set<Key>,
        existingParentIDs: Set<ParentKey>,
        parentID: (DTO) -> ParentKey,
        key: (DTO) -> Key,
        construct: (DTO) -> M
    ) -> (inserted: Int, skipped: Int, affectedParentIDs: Set<ParentKey>) {
        var knownKeys = existingKeys
        var inserted = 0
        var skipped = 0
        var affectedParentIDs = Set<ParentKey>()
        for dto in dtos {
            let parent = parentID(dto)
            guard existingParentIDs.contains(parent) else {
                skipped += 1
                continue
            }
            if !knownKeys.insert(key(dto)).inserted {
                skipped += 1
                continue
            }
            modelContext.insert(construct(dto))
            affectedParentIDs.insert(parent)
            inserted += 1
        }
        return (inserted, skipped, affectedParentIDs)
    }

    @discardableResult
    public func batchInsertDevices(
        _ dtos: [DeviceDTO],
        existingKeys: Set<Data>
    ) throws -> (inserted: Int, skipped: Int) {
        // Assign a fresh Device.id on insert: the backup UUID was `CBPeripheral.identifier`
        // from the source phone, and `Device.id` is `@Attribute(.unique)`. Reusing the
        // backup UUID here could collide with a live local peripheral identifier and
        // trigger SwiftData's upsert, silently overwriting the current Device row.
        // Child records key by `radioID`, not `Device.id`, so the fresh id breaks no
        // linkage; `ConnectionManager.buildServicesAndSaveDevice` cleans up the stub
        // row when the user reconnects to the real radio.
        insertUnique(
            dtos,
            existingKeys: existingKeys,
            key: \.publicKey,
            construct: { Device(dto: $0.cleanedForImport().copy { $0.id = UUID() }) }
        )
    }

    @discardableResult
    public func batchInsertContacts(
        _ dtos: [ContactDTO],
        radioIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, merged: Int, contactIDsByKey: [String: UUID]) {
        var existingContactsByKey = try fetchExistingContactsByKey(radioIDs: radioIDs)
        var contactIDsByKey: [String: UUID] = [:]
        contactIDsByKey.reserveCapacity(dtos.count)
        var inserted = 0
        var skipped = 0
        var merged = 0
        for dto in dtos {
            let key = contactKey(radioID: dto.radioID, publicKey: dto.publicKey)
            if let existing = existingContactsByKey[key] {
                if mergeBackupMetadata(into: existing, from: dto) {
                    merged += 1
                }
                skipped += 1
                contactIDsByKey[key] = existing.id
                continue
            }
            let contact = Contact(dto: dto)
            modelContext.insert(contact)
            existingContactsByKey[key] = contact
            contactIDsByKey[key] = contact.id
            inserted += 1
        }

        return (inserted, skipped, merged, contactIDsByKey)
    }

    /// Outcome of reconciling backup channels against local channels.
    ///
    /// `channelIndexRemap` maps `radioID -> [backupIndex: localIndex]` for every channel
    /// whose placement differs from its backup slot, and `droppedChannelIndices` lists the
    /// `(radioID, backupIndex)` slots that had no free local placement. The caller applies
    /// both to channel messages before insert.
    public struct ChannelBatchInsertResult: Sendable {
        public let inserted: Int
        public let skipped: Int
        public let merged: Int
        public let dropped: Int
        public let channelIndexRemap: [UUID: [UInt8: UInt8]]
        public let droppedChannelIndices: [UUID: Set<UInt8>]
    }

    /// Reconciles backup channels against local channels by stable cryptographic identity
    /// `(radioID, secret)`, treating the slot `index` as mere placement. A backup channel
    /// whose secret matches a local channel merges into it (and records a message-index
    /// remap if the slot differs); a backup channel with no local secret match is placed at
    /// its own slot when free, otherwise relocated to the lowest free slot within the
    /// radio's channel capacity. Channels with an empty (all-zero) secret carry no stable
    /// identity — the public channel and unconfigured slots — so they fall back to
    /// index-based placement, matching legacy behavior and keeping distinct empty-secret
    /// slots from collapsing.
    ///
    /// A backup channel with no free slot is dropped and reported via `skipped`; its
    /// messages are dropped by the caller because no placement exists.
    @discardableResult
    public func batchInsertChannels(
        _ dtos: [ChannelDTO],
        radioIDs: Set<UUID>,
        maxChannelsByRadioID: [UUID: UInt8] = [:]
    ) throws -> ChannelBatchInsertResult {
        let existingChannels = try fetchExistingChannels(radioIDs: radioIDs)

        var localChannelsBySecret: [UUID: [Data: Channel]] = [:]
        var occupiedIndicesByRadioID: [UUID: Set<UInt8>] = [:]
        var localChannelByIndex: [UUID: [UInt8: Channel]] = [:]
        for channel in existingChannels {
            occupiedIndicesByRadioID[channel.radioID, default: []].insert(channel.index)
            localChannelByIndex[channel.radioID, default: [:]][channel.index] = channel
            if channelHasStableSecret(channel.secret) {
                localChannelsBySecret[channel.radioID, default: [:]][channel.secret] = channel
            }
        }

        var inserted = 0
        var skipped = 0
        var merged = 0
        var dropped = 0
        var channelIndexRemap: [UUID: [UInt8: UInt8]] = [:]
        var droppedChannelIndices: [UUID: Set<UInt8>] = [:]

        // Sort by (radioID, index) so free-slot assignment is deterministic regardless of
        // the envelope's array order.
        let orderedDTOs = dtos.sorted {
            $0.radioID == $1.radioID ? $0.index < $1.index : $0.radioID.uuidString < $1.radioID.uuidString
        }

        for dto in orderedDTOs {
            let radioID = dto.radioID

            if channelHasStableSecret(dto.secret),
               let existing = localChannelsBySecret[radioID]?[dto.secret] {
                if mergeBackupMetadata(into: existing, from: dto) {
                    merged += 1
                }
                skipped += 1
                if existing.index != dto.index {
                    channelIndexRemap[radioID, default: [:]][dto.index] = existing.index
                }
                continue
            }

            // Empty-secret channels reconcile by slot (the public channel's slot is its
            // identity); a local channel already in that slot is the merge target.
            if !channelHasStableSecret(dto.secret),
               let existing = localChannelByIndex[radioID]?[dto.index] {
                if mergeBackupMetadata(into: existing, from: dto) {
                    merged += 1
                }
                skipped += 1
                continue
            }

            guard let placementIndex = resolveChannelPlacementIndex(
                backupIndex: dto.index,
                occupiedIndices: occupiedIndicesByRadioID[radioID] ?? [],
                maxChannels: maxChannelsByRadioID[radioID]
            ) else {
                backupLogger.warning(
                    "Backup channel at slot \(dto.index) for radio \(radioID.uuidString) has no free local slot; dropping it and its messages."
                )
                dropped += 1
                droppedChannelIndices[radioID, default: []].insert(dto.index)
                continue
            }

            // Re-mint the surrogate id on insert so a backup channel can never upsert a live
            // local channel that shares its `@Attribute(.unique)` id (e.g. re-importing a stale
            // backup after the same slot was reconfigured and its secret rotated in place).
            // Channel has no inbound foreign key, so the fresh id breaks no message/reaction
            // linkage (those key by channelIndex). Mirrors `batchInsertDevices`.
            let relocatedDTO = placementIndex == dto.index ? dto : dto.with(index: placementIndex)
            let placedDTO = relocatedDTO.with(id: UUID())
            let channel = Channel(dto: placedDTO)
            modelContext.insert(channel)
            occupiedIndicesByRadioID[radioID, default: []].insert(placementIndex)
            localChannelByIndex[radioID, default: [:]][placementIndex] = channel
            if channelHasStableSecret(channel.secret) {
                localChannelsBySecret[radioID, default: [:]][channel.secret] = channel
            }
            if placementIndex != dto.index {
                channelIndexRemap[radioID, default: [:]][dto.index] = placementIndex
            }
            inserted += 1
        }
        return ChannelBatchInsertResult(
            inserted: inserted,
            skipped: skipped,
            merged: merged,
            dropped: dropped,
            channelIndexRemap: channelIndexRemap,
            droppedChannelIndices: droppedChannelIndices
        )
    }

    /// Lowest free slot for a relocating channel: prefer the backup's own slot when free,
    /// otherwise the lowest unoccupied index within `[firstRelocatableChannelIndex, maxChannels)`
    /// so slot 0 stays reserved for the public channel. Returns nil when no slot is free so the
    /// caller can drop the channel rather than mis-associate it. When `maxChannels` is unavailable
    /// (no matching device DTO), the search is bounded by the highest occupied slot plus one so it
    /// still terminates without inventing a capacity.
    private func resolveChannelPlacementIndex(
        backupIndex: UInt8,
        occupiedIndices: Set<UInt8>,
        maxChannels: UInt8?
    ) -> UInt8? {
        if !occupiedIndices.contains(backupIndex) {
            return backupIndex
        }
        let upperBound: Int
        if let maxChannels {
            upperBound = Int(maxChannels)
        } else {
            upperBound = Int(occupiedIndices.max() ?? 0) + 1
        }
        guard upperBound > firstRelocatableChannelIndex else { return nil }
        for candidate in firstRelocatableChannelIndex..<upperBound where !occupiedIndices.contains(UInt8(candidate)) {
            return UInt8(candidate)
        }
        return nil
    }

    /// A channel secret carries stable cryptographic identity only when it is non-empty.
    /// An all-zero secret marks the public channel or an unconfigured slot, which is keyed
    /// by slot index instead.
    private func channelHasStableSecret(_ secret: Data) -> Bool {
        !secret.isEmpty && !secret.allSatisfy { $0 == 0 }
    }

    @discardableResult
    public func batchInsertMessages(
        _ dtos: [MessageDTO],
        existingKeys: Set<String>,
        existingIDsByKey: [String: [UUID]]
    ) throws -> (inserted: Int, skipped: Int, messageIDByBackupID: [UUID: UUID]) {
        var knownKeys = existingKeys
        var idsByKey = existingIDsByKey
        var messageIDByBackupID: [UUID: UUID] = [:]
        var toInsert: [MessageDTO] = []
        toInsert.reserveCapacity(dtos.count)
        var skipped = 0

        // Pass 1: resolve duplicates and build the backup→local ID remap. Doing this
        // before any insert lets pass 2 rewrite replyToID onto winning local UUIDs,
        // so reply chains survive imports where the parent already exists locally.
        for dto in dtos {
            let key = messageBackupKey(for: dto)
            if knownKeys.contains(key) {
                // Deterministic tiebreak when multiple locals share a key.
                let winning = idsByKey[key]?.min(by: { $0.uuidString < $1.uuidString })
                if let winning, winning != dto.id {
                    messageIDByBackupID[dto.id] = winning
                }
                skipped += 1
                continue
            }
            knownKeys.insert(key)
            idsByKey[key, default: []].append(dto.id)
            toInsert.append(dto)
        }

        // Pass 2: insert, rewriting replyToID through the remap so retained messages
        // point at the winning local parent rather than a skipped backup UUID.
        for var dto in toInsert {
            if let replyTo = dto.replyToID, let winning = messageIDByBackupID[replyTo] {
                dto.replyToID = winning
            }
            modelContext.insert(Message(dto: dto))
        }

        return (inserted: toInsert.count, skipped: skipped, messageIDByBackupID: messageIDByBackupID)
    }

    @discardableResult
    public func batchInsertMessageRepeats(
        _ dtos: [MessageRepeatDTO],
        existingIDs: Set<UUID>,
        existingMessageIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, affectedMessageIDs: Set<UUID>) {
        // Only pre-fetch parents that actually appear as a MessageRepeat.messageID;
        // on a large import the repeat set is orders of magnitude smaller than
        // the full envelope message set, so loading every message into the
        // context just to key a relationship map is wasted work.
        let referencedParentIDs = Set(dtos.map(\.messageID)).intersection(existingMessageIDs)
        let parentMessages = try fetchInChunks(keys: Array(referencedParentIDs)) { chunk in
            let predicate = #Predicate<Message> { chunk.contains($0.id) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        let messagesByID = Dictionary(uniqueKeysWithValues: parentMessages.map { ($0.id, $0) })

        let result = insertUniqueWithParent(
            dtos,
            existingKeys: existingIDs,
            existingParentIDs: existingMessageIDs,
            parentID: \.messageID,
            key: \.id,
            construct: { MessageRepeat(dto: $0, message: messagesByID[$0.messageID]) }
        )
        return (result.inserted, result.skipped, result.affectedParentIDs)
    }

    @discardableResult
    public func batchInsertReactions(
        _ dtos: [ReactionDTO],
        existingKeys: Set<String>,
        existingMessageIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, affectedMessageIDs: Set<UUID>) {
        let result = insertUniqueWithParent(
            dtos,
            existingKeys: existingKeys,
            existingParentIDs: existingMessageIDs,
            parentID: \.messageID,
            key: { reactionKey(messageID: $0.messageID, senderName: $0.senderName, emoji: $0.emoji) },
            construct: Reaction.init(dto:)
        )
        return (result.inserted, result.skipped, result.affectedParentIDs)
    }

    @discardableResult
    public func batchInsertRoomMessages(
        _ dtos: [RoomMessageDTO],
        existingKeys: Set<String>,
        existingSessionIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, affectedSessionIDs: Set<UUID>) {
        let result = insertUniqueWithParent(
            dtos,
            existingKeys: existingKeys,
            existingParentIDs: existingSessionIDs,
            parentID: \.sessionID,
            key: { roomMessageKey(sessionID: $0.sessionID, deduplicationKey: $0.deduplicationKey) },
            construct: RoomMessage.init(dto:)
        )
        return (result.inserted, result.skipped, result.affectedParentIDs)
    }

    @discardableResult
    public func batchInsertRemoteNodeSessions(
        _ dtos: [RemoteNodeSessionDTO],
        radioIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, merged: Int, sessionIDsByKey: [String: UUID]) {
        var existingSessionsByKey = try fetchExistingRemoteNodeSessionsByKey(radioIDs: radioIDs)
        var sessionIDsByKey: [String: UUID] = [:]
        sessionIDsByKey.reserveCapacity(dtos.count)
        var inserted = 0
        var skipped = 0
        var merged = 0
        for dto in dtos {
            let key = remoteNodeSessionKey(radioID: dto.radioID, publicKey: dto.publicKey)
            if let existing = existingSessionsByKey[key] {
                if mergeBackupMetadata(into: existing, from: dto) {
                    merged += 1
                }
                skipped += 1
                sessionIDsByKey[key] = existing.id
                continue
            }
            let session = RemoteNodeSession(dto: dto)
            // Restored sessions are never live BLE connections.
            session.isConnected = false
            modelContext.insert(session)
            existingSessionsByKey[key] = session
            sessionIDsByKey[key] = session.id
            inserted += 1
        }

        return (inserted, skipped, merged, sessionIDsByKey)
    }

    @discardableResult
    public func batchInsertSavedTracePaths(
        _ dtos: [SavedTracePathDTO]
    ) throws -> (inserted: Int, skipped: Int, merged: Int) {
        guard !dtos.isEmpty else { return (0, 0, 0) }

        var inserted = 0
        var skipped = 0
        var merged = 0
        let radioIDArray = Array(Set(dtos.map(\.radioID)))
        let existingPaths = try fetchInChunks(keys: radioIDArray) { chunk in
            let predicate = #Predicate<SavedTracePath> { chunk.contains($0.radioID) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        var existingPathsByKey: [String: SavedTracePath] = [:]
        var existingRunIDsByPathID: [UUID: Set<UUID>] = [:]

        for path in existingPaths {
            let pathKey = savedTracePathKey(radioID: path.radioID, pathBytes: path.pathBytes, hashSize: path.hashSize)
            existingPathsByKey[pathKey] = path
            existingRunIDsByPathID[path.id] = Set(path.runs.map(\.id))
        }

        for dto in dtos {
            let key = savedTracePathKey(radioID: dto.radioID, pathBytes: dto.pathBytes, hashSize: dto.hashSize)
            if let existingPath = existingPathsByKey[key] {
                skipped += 1
                var existingRunIDs = existingRunIDsByPathID[existingPath.id] ?? []
                var appendedRun = false

                for runDTO in dto.runs where !existingRunIDs.contains(runDTO.id) {
                    let run = try TracePathRun(dto: runDTO)
                    run.savedPath = existingPath
                    modelContext.insert(run)
                    existingRunIDs.insert(runDTO.id)
                    appendedRun = true
                }

                existingRunIDsByPathID[existingPath.id] = existingRunIDs
                if appendedRun {
                    merged += 1
                }
                continue
            }

            let path = SavedTracePath(
                id: dto.id,
                radioID: dto.radioID,
                name: dto.name,
                pathBytes: dto.pathBytes,
                hashSize: dto.hashSize,
                createdDate: dto.createdDate
            )
            modelContext.insert(path)

            var insertedRunIDs: Set<UUID> = []
            for runDTO in dto.runs {
                guard !insertedRunIDs.contains(runDTO.id) else { continue }
                let run = try TracePathRun(dto: runDTO)
                run.savedPath = path
                modelContext.insert(run)
                insertedRunIDs.insert(runDTO.id)
            }

            existingPathsByKey[key] = path
            existingRunIDsByPathID[path.id] = insertedRunIDs
            inserted += 1
        }
        return (inserted, skipped, merged)
    }

    @discardableResult
    public func batchInsertBlockedChannelSenders(
        _ dtos: [BlockedChannelSenderDTO],
        existingKeys: Set<String>
    ) throws -> (inserted: Int, skipped: Int) {
        insertUnique(
            dtos,
            existingKeys: existingKeys,
            key: { blockedChannelSenderKey(radioID: $0.radioID, name: $0.name) },
            construct: BlockedChannelSender.init(dto:)
        )
    }

    @discardableResult
    public func batchInsertNodeStatusSnapshots(
        _ dtos: [NodeStatusSnapshotDTO],
        existingKeys: Set<String>
    ) throws -> (inserted: Int, skipped: Int) {
        insertUnique(
            dtos,
            existingKeys: existingKeys,
            key: { nodeStatusSnapshotKey(nodePublicKey: $0.nodePublicKey, timestamp: $0.timestamp) },
            construct: NodeStatusSnapshot.init(dto:)
        )
    }
}
