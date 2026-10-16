package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase

/**
 * Every family's `radioID` rewritten through [BackupRadioIdentity]'s mapping, computed once so
 * [RoomBackupImporter.importEnvelope] stays a flat sequence of per-family import calls.
 */
internal data class RemappedFamilies(
    val contacts: List<ContactDto>,
    val channels: List<ChannelDto>,
    val messages: List<MessageDto>,
    val reactions: List<ReactionDto>,
    val sessions: List<RemoteNodeSessionDto>,
    val tracePaths: List<SavedTracePathDto>,
    val blockedSenders: List<BlockedChannelSenderDto>,
    val discoveredNodes: List<DiscoveredNodeDto>,
)

internal fun remapFamilies(envelope: AppBackupEnvelope, mapping: Map<Uuid, Uuid>) = RemappedFamilies(
    contacts = envelope.contacts.map { it.remapRadioID(mapping) },
    channels = envelope.channels.map { it.remapRadioID(mapping) },
    messages = envelope.messages.map { it.remapRadioID(mapping) },
    reactions = envelope.reactions.map { it.remapRadioID(mapping) },
    sessions = envelope.remoteNodeSessions.map { it.remapRadioID(mapping) },
    tracePaths = envelope.savedTracePaths.map { it.remapRadioID(mapping) },
    blockedSenders = envelope.blockedChannelSenders.map { it.remapRadioID(mapping) },
    discoveredNodes = envelope.discoveredNodes.map { it.remapRadioID(mapping) },
)

/**
 * The backup-id → local-id maps the dependent families follow: [sessionIDsByKey] resolves a room
 * message onto its merged session, [messageIDByBackupID] a repeat onto the message that won its key.
 */
internal data class BackupIdentityMaps(
    val sessionIDsByKey: Map<String, Uuid>,
    val messageIDByBackupID: Map<Uuid, Uuid>,
    val skippedIncomingParents: List<BackupMessageIdentity.SkippedIncomingParent>,
)

/**
 * Local ids of every envelope message that landed: one that deduplicated against a local row is
 * present under the id that won its key, the rest under their own. Bounds the parent-existence guard
 * for reactions and message repeats. Mirrors iOS `allMessageIDs`.
 */
internal fun resolvedMessageIDs(messages: List<MessageDto>, messageIDByBackupID: Map<Uuid, Uuid>): Set<Uuid> =
    messages.mapTo(mutableSetOf()) { messageIDByBackupID[it.id] ?: it.id }

/** Insert counts for the families [BackupExtraFamiliesImporter] owns. */
internal data class ExtraFamiliesOutcome(
    val messageRepeatsInserted: Int,
    val messageRepeatsSkipped: Int,
    /** Messages that gained a repeat, for the post-import `heardRepeats` recompute. */
    val messageRepeatAffectedMessageIDs: Set<Uuid>,
    val roomMessagesInserted: Int,
    val roomMessagesSkipped: Int,
    /** Room messages with `sessionID` already remapped onto the local rows, for the date reconcile. */
    val roomMessages: List<RoomMessageDto>,
    /** Sessions that gained a room message, bounding the same reconcile. */
    val roomMessageAffectedSessionIDs: Set<Uuid>,
    val tracePathsInserted: Int,
    val tracePathsSkipped: Int,
    val tracePathsMerged: Int,
    val blockedSendersInserted: Int,
    val blockedSendersSkipped: Int,
    val snapshotsInserted: Int,
    val snapshotsSkipped: Int,
    val discoveredNodesInserted: Int,
    /** Discover rows already present locally or rejected as invalid. */
    val discoveredNodesSkipped: Int,
    /** Discover rows discarded because the per-radio cap was already reached. */
    val discoveredNodesDropped: Int,
)

/**
 * Drives every [BackupExtraFamiliesImporter] family for one envelope import, keeping
 * [RoomBackupImporter.importEnvelope] focused on the families it owns directly.
 */
internal suspend fun importExtraFamilies(
    database: MeshDatabase,
    envelope: AppBackupEnvelope,
    remapped: RemappedFamilies,
    mapped: BackupChannelIndexMapping.Result,
    identity: BackupIdentityMaps,
): ExtraFamiliesOutcome {
    val messageIDMapping = identity.messageIDByBackupID
    val allMessageIDs = resolvedMessageIDs(mapped.messages, messageIDMapping)
    val repeats = BackupMessageIdentity.applyToRepeats(messageIDMapping, envelope.messageRepeats)
    val incomingPaths = BackupIncomingPathImporter.mergeIncomingPathArrivals(
        database,
        identity.skippedIncomingParents,
        repeats,
        allMessageIDs,
    )
    val repeatOutcome = BackupExtraFamiliesImporter.importMessageRepeats(
        database,
        incomingPaths.repeatsToImport,
        allMessageIDs,
    )

    val sessionIDsByKey = identity.sessionIDsByKey
    val sessionIDMapping = BackupExtraFamiliesImporter.buildSessionIDMapping(remapped.sessions, sessionIDsByKey)
    val remappedRoomMessages =
        BackupExtraFamiliesImporter.applySessionIDMapping(sessionIDMapping, envelope.roomMessages)
    val allSessionIDs = sessionIDsByKey.values.toSet()
    val roomMessageOutcome =
        BackupExtraFamiliesImporter.importRoomMessages(database, remappedRoomMessages, allSessionIDs)

    val tracePathOutcome = BackupExtraFamiliesImporter.importSavedTracePaths(database, remapped.tracePaths)
    val blockedOutcome = BackupExtraFamiliesImporter.importBlockedChannelSenders(database, remapped.blockedSenders)
    val snapshotOutcome =
        BackupExtraFamiliesImporter.importNodeStatusSnapshots(database, envelope.nodeStatusSnapshots)
    val discoveredOutcome = DiscoveredNodeBackupImporter.import(database, remapped.discoveredNodes)

    return ExtraFamiliesOutcome(
        messageRepeatsInserted = repeatOutcome.inserted + incomingPaths.promoted,
        messageRepeatsSkipped = repeatOutcome.skipped + incomingPaths.skippedRepeats,
        messageRepeatAffectedMessageIDs = repeatOutcome.affectedMessageIDs + incomingPaths.affectedMessageIDs,
        roomMessagesInserted = roomMessageOutcome.inserted,
        roomMessagesSkipped = roomMessageOutcome.skipped,
        roomMessages = remappedRoomMessages,
        roomMessageAffectedSessionIDs = roomMessageOutcome.affectedSessionIDs,
        tracePathsInserted = tracePathOutcome.inserted,
        tracePathsSkipped = tracePathOutcome.skipped,
        tracePathsMerged = tracePathOutcome.merged,
        blockedSendersInserted = blockedOutcome.inserted,
        blockedSendersSkipped = blockedOutcome.skipped,
        snapshotsInserted = snapshotOutcome.inserted,
        snapshotsSkipped = snapshotOutcome.skipped,
        discoveredNodesInserted = discoveredOutcome.inserted,
        discoveredNodesSkipped = discoveredOutcome.skipped,
        discoveredNodesDropped = discoveredOutcome.dropped,
    )
}
