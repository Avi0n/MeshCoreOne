package one.meshcore.shared.backup

import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.model.Uuid

/** Counts from [RoomBackupImporter.importEnvelope]. */
data class BackupImportResult(
    val devicesInserted: Int = 0,
    val devicesMerged: Int = 0,
    val contactsInserted: Int = 0,
    val contactsMerged: Int = 0,
    /** Existing contacts the backup matched, including those whose metadata merged. iOS `counts[.contacts].skipped`. */
    val contactsSkipped: Int = 0,
    val channelsInserted: Int = 0,
    val channelsMerged: Int = 0,
    /** Local channels the backup matched by secret or slot. iOS `counts[.channels].skipped`. */
    val channelsSkipped: Int = 0,
    val channelsDropped: Int = 0,
    val sessionsInserted: Int = 0,
    val sessionsMerged: Int = 0,
    /** Existing sessions the backup matched, including those whose metadata merged. */
    val sessionsSkipped: Int = 0,
    val messagesInserted: Int = 0,
    /** Backup messages a local row already covers (insert-only dedupe). iOS `counts[.messages].skipped`. */
    val messagesSkipped: Int = 0,
    val messagesDropped: Int = 0,
    val reactionsInserted: Int = 0,
    /** Duplicate or orphan reactions. iOS `counts[.reactions].skipped`. */
    val reactionsSkipped: Int = 0,
    val reactionsDropped: Int = 0,
    val messageRepeatsInserted: Int = 0,
    val messageRepeatsSkipped: Int = 0,
    val roomMessagesInserted: Int = 0,
    val roomMessagesSkipped: Int = 0,
    val savedTracePathsInserted: Int = 0,
    val savedTracePathsSkipped: Int = 0,
    val savedTracePathsMerged: Int = 0,
    val blockedChannelSendersInserted: Int = 0,
    val blockedChannelSendersSkipped: Int = 0,
    val nodeStatusSnapshotsInserted: Int = 0,
    val nodeStatusSnapshotsSkipped: Int = 0,
    val discoveredNodesInserted: Int = 0,
    /** Discover rows already present locally, or rejected as invalid. */
    val discoveredNodesSkipped: Int = 0,
    /** Envelope-duplicate device public keys; first occurrence wins. iOS `counts[.devices].skipped`. */
    val devicesSkipped: Int = 0,
    /** Discover rows discarded because the per-radio cap was already reached. */
    val discoveredNodesDropped: Int = 0,
    /** Local slots whose channel identity changed (inserts + drops); drafts for these should clear. */
    val channelSlotsAffected: Map<Uuid, Set<Int>> = emptyMap(),
    /** True when the envelope wrote at least one missing preference key. iOS `userDefaultsRestored`. */
    val userDefaultsRestored: Boolean = false,
) {
    val totalInserted: Int
        get() = devicesInserted + contactsInserted + channelsInserted + sessionsInserted +
            messagesInserted + reactionsInserted + messageRepeatsInserted + roomMessagesInserted +
            savedTracePathsInserted + blockedChannelSendersInserted + nodeStatusSnapshotsInserted +
            discoveredNodesInserted

    val totalMerged: Int
        get() = devicesMerged + contactsMerged + channelsMerged + sessionsMerged + savedTracePathsMerged

    val totalSkipped: Int
        get() = messagesSkipped + contactsSkipped + channelsSkipped + devicesSkipped +
            roomMessagesSkipped + reactionsSkipped + messageRepeatsSkipped +
            savedTracePathsSkipped + sessionsSkipped + blockedChannelSendersSkipped +
            nodeStatusSnapshotsSkipped + discoveredNodesSkipped

    val totalDropped: Int
        get() = channelsDropped + messagesDropped + reactionsDropped + discoveredNodesDropped

    val hasRestoredChanges: Boolean
        get() = totalInserted > 0 || totalMerged > 0 || userDefaultsRestored
}

/**
 * Clears the composer drafts for slots whose channel identity changed during the import. A draft is
 * typed against the channel that occupied a slot, so a relocated or dropped channel would hand it to
 * whichever channel takes the slot next. Mirrors the iOS `onChannelDraftSlotsAffected` hook.
 */
suspend fun BackupImportResult.clearAffectedChannelDrafts(draftEditors: ChatDraftEditors) {
    if (channelSlotsAffected.isEmpty()) return
    draftEditors.resetChannelDrafts(channelSlotsAffected)
}
