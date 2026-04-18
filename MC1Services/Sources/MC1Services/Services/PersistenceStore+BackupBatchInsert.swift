import Foundation
import SwiftData

// MARK: - Batch Insert

extension PersistenceStore {

    /// Inserts DTOs into the model context, skipping any whose `key` is
    /// already in `existingKeys`. Covers the "insert if absent" shape used
    /// by most backup batch-insert paths.
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

    @discardableResult
    public func batchInsertChannels(
        _ dtos: [ChannelDTO],
        radioIDs: Set<UUID>
    ) throws -> (inserted: Int, skipped: Int, merged: Int) {
        var existingChannelsByKey = try fetchExistingChannelsByKey(radioIDs: radioIDs)
        var inserted = 0
        var skipped = 0
        var merged = 0
        for dto in dtos {
            let key = channelKey(radioID: dto.radioID, index: dto.index)
            if let existing = existingChannelsByKey[key] {
                if mergeBackupMetadata(into: existing, from: dto) {
                    merged += 1
                }
                skipped += 1
                continue
            }
            let channel = Channel(dto: dto)
            modelContext.insert(channel)
            existingChannelsByKey[key] = channel
            inserted += 1
        }
        return (inserted, skipped, merged)
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
