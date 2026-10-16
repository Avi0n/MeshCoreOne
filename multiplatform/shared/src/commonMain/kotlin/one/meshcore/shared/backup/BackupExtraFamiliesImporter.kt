package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.SavedTracePathEntity
import one.meshcore.shared.persistence.TracePathRunEntity
import one.meshcore.shared.persistence.toEntity

/**
 * Imports the model families [RoomBackupImporter] delegates here: message repeats, room messages,
 * saved trace paths, blocked channel senders, and node status snapshots. Split out from
 * [RoomBackupImporter] the same way iOS splits `PersistenceStore+BackupImport.swift` from
 * `PersistenceStore+BackupBatchInsert.swift` — one file per import phase.
 */
object BackupExtraFamiliesImporter {

    /**
     * Insert-only: each repeat represents a distinct hearing of a message, so its own `id` is
     * unique-by-construction. Repeats whose parent message didn't survive import (dropped with its
     * channel, or simply absent) are skipped rather than orphaned. Mirrors iOS
     * `batchInsertMessageRepeats`.
     */
    suspend fun importMessageRepeats(
        database: MeshDatabase,
        repeats: List<MessageRepeatDto>,
        existingMessageIDs: Set<Uuid>,
    ): MessageRepeatImportOutcome {
        if (repeats.isEmpty()) {
            return MessageRepeatImportOutcome(inserted = 0, skipped = 0, affectedMessageIDs = emptySet())
        }
        // Only pre-fetch parents that actually appear as a MessageRepeat.messageID.
        val referencedParentIDs = repeats.map { it.messageID }.distinct().filter { it in existingMessageIDs }
        val existingIDs =
            referencedParentIDs.flatMap { database.messageRepeatDao().listByMessage(it) }
                .mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var skipped = 0
        val affectedMessageIDs = mutableSetOf<Uuid>()
        for (dto in repeats) {
            // dto.messageID absent from existingMessageIDs short-circuits before the add() side effect.
            if (dto.messageID !in existingMessageIDs || !existingIDs.add(dto.id)) {
                skipped++
                continue
            }
            database.messageRepeatDao().upsert(dto.toEntity())
            affectedMessageIDs.add(dto.messageID)
            inserted++
        }
        return MessageRepeatImportOutcome(inserted, skipped, affectedMessageIDs)
    }

    /**
     * Insert-only, deduped by `(sessionID, deduplicationKey)`. Room messages whose parent session
     * didn't survive import are skipped. Mirrors iOS `batchInsertRoomMessages`.
     */
    suspend fun importRoomMessages(
        database: MeshDatabase,
        roomMessages: List<RoomMessageDto>,
        existingSessionIDs: Set<Uuid>,
    ): RoomMessageImportOutcome {
        if (roomMessages.isEmpty()) {
            return RoomMessageImportOutcome(inserted = 0, skipped = 0, affectedSessionIDs = emptySet())
        }
        val referencedSessionIDs = roomMessages.map { it.sessionID }.distinct().filter { it in existingSessionIDs }
        val existingKeys =
            referencedSessionIDs.flatMap { database.roomMessageDao().listBySession(it) }
                .mapTo(mutableSetOf()) { BackupMergeKeys.roomMessageKey(it.sessionID, it.deduplicationKey) }
        var inserted = 0
        var skipped = 0
        val affectedSessionIDs = mutableSetOf<Uuid>()
        for (dto in roomMessages) {
            val key = BackupMergeKeys.roomMessageKey(dto.sessionID, dto.deduplicationKey)
            // dto.sessionID absent from existingSessionIDs short-circuits before the add() side effect.
            if (dto.sessionID !in existingSessionIDs || !existingKeys.add(key)) {
                skipped++
                continue
            }
            database.roomMessageDao().upsert(dto.toEntity())
            affectedSessionIDs.add(dto.sessionID)
            inserted++
        }
        return RoomMessageImportOutcome(inserted, skipped, affectedSessionIDs)
    }

    /**
     * Matches backup trace paths to local ones by `(radioID, pathBytes, hashSize)`: a match appends
     * any runs not already present (globally deduped by run id, since `TracePathRun.id` is unique
     * store-wide); no match inserts the path and its runs. Mirrors iOS `batchInsertSavedTracePaths`.
     */
    suspend fun importSavedTracePaths(
        database: MeshDatabase,
        tracePaths: List<SavedTracePathDto>,
    ): SavedTracePathImportOutcome {
        if (tracePaths.isEmpty()) return SavedTracePathImportOutcome(inserted = 0, skipped = 0, merged = 0)

        var inserted = 0
        var skipped = 0
        var merged = 0
        val radioIDs = tracePaths.map { it.radioID }.distinct()
        val existingPathsByKey =
            radioIDs.flatMap { database.savedTracePathDao().listByRadio(it) }
                .associateBy { BackupMergeKeys.savedTracePathKey(it.radioID, it.pathBytes, it.hashSize) }
                .toMutableMap()
        val seenRunIDs = database.tracePathRunDao().listAllIds().toMutableSet()

        for (dto in tracePaths) {
            val key = BackupMergeKeys.savedTracePathKey(dto.radioID, dto.pathBytes, dto.hashSize)
            val existingPath = existingPathsByKey[key]
            if (existingPath != null) {
                skipped++
                if (appendNewRuns(database, dto.runs, existingPath.id, seenRunIDs)) merged++
                continue
            }
            existingPathsByKey[key] = insertTracePath(database, dto, seenRunIDs)
            inserted++
        }
        return SavedTracePathImportOutcome(inserted, skipped, merged)
    }

    /** Appends runs from `runs` not already in `seenRunIDs`; returns whether any run was appended. */
    private suspend fun appendNewRuns(
        database: MeshDatabase,
        runs: List<TracePathRunDto>,
        savedPathID: Uuid,
        seenRunIDs: MutableSet<Uuid>,
    ): Boolean {
        var appended = false
        for (run in runs) {
            if (!seenRunIDs.add(run.id)) continue
            database.tracePathRunDao().upsert(run.toEntity(savedPathID))
            appended = true
        }
        return appended
    }

    private suspend fun insertTracePath(
        database: MeshDatabase,
        dto: SavedTracePathDto,
        seenRunIDs: MutableSet<Uuid>,
    ): SavedTracePathEntity {
        val entity =
            SavedTracePathEntity(
                id = dto.id,
                radioID = dto.radioID,
                name = dto.name,
                pathBytes = dto.pathBytes,
                hashSize = dto.hashSize,
                createdDate = dto.createdDate,
            )
        database.savedTracePathDao().upsert(entity)
        for (run in dto.runs) {
            if (!seenRunIDs.add(run.id)) continue
            database.tracePathRunDao().upsert(run.toEntity(dto.id))
        }
        return entity
    }

    /** Insert-only, deduped by `(radioID, name)`. Blocking is binary, so there's no metadata to merge. */
    suspend fun importBlockedChannelSenders(
        database: MeshDatabase,
        blockedSenders: List<BlockedChannelSenderDto>,
    ): InsertSkipOutcome {
        if (blockedSenders.isEmpty()) return InsertSkipOutcome(inserted = 0, skipped = 0)
        val radioIDs = blockedSenders.map { it.radioID }.distinct()
        val existingKeys =
            radioIDs.flatMap { database.blockedChannelSenderDao().listByRadio(it) }
                .mapTo(mutableSetOf()) { BackupMergeKeys.blockedChannelSenderKey(it.radioID, it.name) }
        var inserted = 0
        var skipped = 0
        for (dto in blockedSenders) {
            val key = BackupMergeKeys.blockedChannelSenderKey(dto.radioID, dto.name)
            if (!existingKeys.add(key)) {
                skipped++
                continue
            }
            database.blockedChannelSenderDao().upsert(dto.toEntity())
            inserted++
        }
        return InsertSkipOutcome(inserted, skipped)
    }

    /**
     * Insert-only, deduped by `(nodePublicKey, timestamp)`. Snapshots aren't `radioID`-scoped (a
     * repeater/room can be seen from any radio), so the existing-key scan is global, mirroring iOS.
     */
    suspend fun importNodeStatusSnapshots(
        database: MeshDatabase,
        snapshots: List<NodeStatusSnapshotDto>,
    ): InsertSkipOutcome {
        if (snapshots.isEmpty()) return InsertSkipOutcome(inserted = 0, skipped = 0)
        val existingKeys =
            database.nodeStatusSnapshotDao().listAll()
                .mapTo(mutableSetOf()) { BackupMerge.nodeStatusSnapshotKey(it.nodePublicKey, it.timestamp) }
        var inserted = 0
        var skipped = 0
        for (dto in snapshots) {
            val key = BackupMerge.nodeStatusSnapshotKey(dto.nodePublicKey, dto.timestamp)
            if (!existingKeys.add(key)) {
                skipped++
                continue
            }
            database.nodeStatusSnapshotDao().upsert(dto.toEntity())
            inserted++
        }
        return InsertSkipOutcome(inserted, skipped)
    }

    /**
     * Backup-session-id → local-session-id entries for sessions that merged into an existing local
     * row (identity mappings omitted). Mirrors iOS `buildSessionIDMapping`.
     */
    fun buildSessionIDMapping(
        sessions: List<RemoteNodeSessionDto>,
        sessionIDsByKey: Map<String, Uuid>,
    ): Map<Uuid, Uuid> = mergedLocalIdMapping(
        rows = sessions,
        key = { BackupMergeKeys.remoteNodeSessionKey(it.radioID, it.publicKey) },
        foreignId = { it.id },
        localIdsByKey = sessionIDsByKey,
    )

    /** Rewrites `sessionID` on room messages so they attach to the local (post-merge) session row. */
    fun applySessionIDMapping(mapping: Map<Uuid, Uuid>, roomMessages: List<RoomMessageDto>): List<RoomMessageDto> {
        if (mapping.isEmpty()) return roomMessages
        return roomMessages.map { dto -> mapping[dto.sessionID]?.let { dto.copy(sessionID = it) } ?: dto }
    }

    /** Insert count plus the parent messages that gained a repeat, which need their caches recomputed. */
    data class MessageRepeatImportOutcome(val inserted: Int, val skipped: Int, val affectedMessageIDs: Set<Uuid>)

    /** Insert count plus the sessions that gained a room message, whose `lastMessageDate` is now stale. */
    data class RoomMessageImportOutcome(val inserted: Int, val skipped: Int, val affectedSessionIDs: Set<Uuid>)

    data class SavedTracePathImportOutcome(val inserted: Int, val skipped: Int, val merged: Int)

    data class InsertSkipOutcome(val inserted: Int, val skipped: Int)

    private fun TracePathRunDto.toEntity(savedPathID: Uuid): TracePathRunEntity = TracePathRunEntity(
        id = id,
        savedPathID = savedPathID,
        date = date,
        success = success,
        roundTripMs = roundTripMs,
        hopsSNR = hopsSNR,
    )
}
