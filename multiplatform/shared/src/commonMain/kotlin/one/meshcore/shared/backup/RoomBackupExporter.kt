package one.meshcore.shared.backup

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toDto

/**
 * Collects backup DTO families from [MeshDatabase] and encodes an envelope. Covers devices,
 * contacts, channels, messages, message repeats, reactions, room messages, remote sessions,
 * saved trace paths (with runs), blocked senders, and node status snapshots.
 */
object RoomBackupExporter {
    suspend fun exportJson(
        database: MeshDatabase,
        appVersion: String,
        appBuild: String,
        exportDate: Double,
        userDefaults: BackupUserDefaults? = null,
    ): String {
        val devices = database.deviceDao().listAll().map { it.toDto() }
        val contacts = database.contactDao().listAll().map { it.toDto() }
        val channels = database.channelDao().listAll().map { it.toDto() }
        val sessions = database.remoteNodeSessionDao().getAll().map { it.toDto() }
        val discoveredNodes = database.discoveredNodeDao().listAll().map { it.toDto() }

        // Every family reads store-wide, never per radio partition: a partition outlives its Device row
        // (deleted, never re-paired) and can hold nothing but messages, reactions, trace paths or
        // blocked senders. iOS `fetchAll*` exports every row regardless, so any radio-derived scope
        // silently drops history.
        val messages = database.messageDao().listAll().map { it.toDto().withBackupDeduplicationKey() }
        val messageIds = messages.map { it.id }.toSet()
        val messageRepeats =
            database.messageRepeatDao().listAll()
                .filter { it.messageID in messageIds }
                .map { it.toDto() }
        val reactions = database.reactionDao().listAll().map { it.toDto() }
        // Room messages and message repeats stay scoped to their surviving parent so an orphan row
        // never reaches the envelope, mirroring iOS `fetchAllRoomMessages(sessionIDs:)`.
        val sessionIds = sessions.map { it.id }.toSet()
        val roomMessages =
            database.roomMessageDao().listAll().filter { it.sessionID in sessionIds }.map { it.toDto() }
        val savedTracePaths =
            database.savedTracePathDao().listAll().map { path ->
                val runs = database.tracePathRunDao().listByPath(path.id).map { it.toDto() }
                path.toDto(runs)
            }
        val blocked = database.blockedChannelSenderDao().listAll().map { it.toDto() }
        val nodeStatusSnapshots = database.nodeStatusSnapshotDao().listAll().map { it.toDto() }

        return BackupEnvelopeBuilder.encode(
            BackupExportInput(
                appVersion = appVersion,
                appBuild = appBuild,
                exportDate = exportDate,
                devices = devices,
                contacts = contacts,
                channels = channels,
                messages = messages,
                messageRepeats = messageRepeats,
                reactions = reactions,
                roomMessages = roomMessages,
                remoteNodeSessions = sessions,
                savedTracePaths = savedTracePaths,
                blockedChannelSenders = blocked,
                nodeStatusSnapshots = nodeStatusSnapshots,
                discoveredNodes = discoveredNodes,
                userDefaults = userDefaults,
            ),
        )
    }

    /**
     * Backfills the content dedup key on an incoming message that predates the field, so an import can
     * match it against the same packet already heard live. Outgoing messages are deliberately left
     * without one — restore keys them on their own id. Mirrors iOS `fetchAllMessagesForBackup`.
     */
    private fun MessageDto.withBackupDeduplicationKey(): MessageDto =
        if (direction == MessageDirection.OUTGOING || deduplicationKey != null) {
            this
        } else {
            copy(deduplicationKey = BackupMerge.contentDeduplicationKey(this))
        }
}
