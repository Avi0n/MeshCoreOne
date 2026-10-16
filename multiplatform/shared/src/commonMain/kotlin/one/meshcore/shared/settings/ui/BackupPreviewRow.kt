package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_import_preview_blocked_senders
import one.meshcore.shared.resources.settings_backup_import_preview_channels
import one.meshcore.shared.resources.settings_backup_import_preview_contacts
import one.meshcore.shared.resources.settings_backup_import_preview_devices
import one.meshcore.shared.resources.settings_backup_import_preview_discovered_nodes
import one.meshcore.shared.resources.settings_backup_import_preview_message_repeats
import one.meshcore.shared.resources.settings_backup_import_preview_messages
import one.meshcore.shared.resources.settings_backup_import_preview_node_status_snapshots
import one.meshcore.shared.resources.settings_backup_import_preview_reactions
import one.meshcore.shared.resources.settings_backup_import_preview_remote_node_sessions
import one.meshcore.shared.resources.settings_backup_import_preview_room_messages
import one.meshcore.shared.resources.settings_backup_import_preview_saved_paths
import org.jetbrains.compose.resources.StringResource

/** One manifest line of the import preview: what will be written, and how much of it. */
data class BackupPreviewRow(val label: StringResource, val count: Int)

/**
 * One backup kind, in the order iOS lists `BackupModelKind`. A zero count is omitted, which is the
 * same rule the iOS sheet uses. Kinds the importer never drops stay at zero so they stay off the
 * dropped list.
 */
private class BackupManifestKind(
    val label: StringResource,
    val preview: (BackupImportPreview) -> Int,
    val inserted: (BackupImportResult) -> Int,
    val skipped: (BackupImportResult) -> Int,
    val dropped: (BackupImportResult) -> Int,
)

private val backupManifest = listOf(
    BackupManifestKind(
        Res.string.settings_backup_import_preview_messages,
        preview = { it.messageCount },
        inserted = { it.messagesInserted },
        skipped = { it.messagesSkipped },
        dropped = { it.messagesDropped },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_contacts,
        preview = { it.contactCount },
        inserted = { it.contactsInserted },
        skipped = { it.contactsSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_channels,
        preview = { it.channelCount },
        inserted = { it.channelsInserted },
        skipped = { it.channelsSkipped },
        dropped = { it.channelsDropped },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_devices,
        preview = { it.deviceCount },
        inserted = { it.devicesInserted },
        skipped = { it.devicesSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_room_messages,
        preview = { it.roomMessageCount },
        inserted = { it.roomMessagesInserted },
        skipped = { it.roomMessagesSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_reactions,
        preview = { it.reactionCount },
        inserted = { it.reactionsInserted },
        skipped = { it.reactionsSkipped },
        dropped = { it.reactionsDropped },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_message_repeats,
        preview = { it.messageRepeatCount },
        inserted = { it.messageRepeatsInserted },
        skipped = { it.messageRepeatsSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_saved_paths,
        preview = { it.savedTracePathCount },
        inserted = { it.savedTracePathsInserted },
        skipped = { it.savedTracePathsSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_remote_node_sessions,
        preview = { it.remoteNodeSessionCount },
        inserted = { it.sessionsInserted },
        skipped = { it.sessionsSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_blocked_senders,
        preview = { it.blockedChannelSenderCount },
        inserted = { it.blockedChannelSendersInserted },
        skipped = { it.blockedChannelSendersSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_node_status_snapshots,
        preview = { it.nodeStatusSnapshotCount },
        inserted = { it.nodeStatusSnapshotsInserted },
        skipped = { it.nodeStatusSnapshotsSkipped },
        dropped = { _ -> 0 },
    ),
    BackupManifestKind(
        Res.string.settings_backup_import_preview_discovered_nodes,
        preview = { it.discoveredNodeCount },
        inserted = { it.discoveredNodesInserted },
        skipped = { it.discoveredNodesSkipped },
        dropped = { it.discoveredNodesDropped },
    ),
)

private fun backupManifestRows(count: (BackupManifestKind) -> Int): List<BackupPreviewRow> = backupManifest.map {
    BackupPreviewRow(it.label, count(it))
}.filter { it.count > 0 }

/**
 * The manifest rows an import preview shows. Every kind must appear here. A preview that lists only
 * some of what the file will write understates the import, and a user deciding whether to restore
 * reads it as the whole contents.
 */
fun backupPreviewRows(preview: BackupImportPreview): List<BackupPreviewRow> = backupManifestRows {
    it.preview(preview)
}

/** Inserted rows for the import-success sheet, same kind order as the preview. */
fun backupImportInsertedRows(result: BackupImportResult): List<BackupPreviewRow> = backupManifestRows {
    it.inserted(result)
}

/**
 * Already-here rows for the import-success sheet. iOS `ImportSuccessContent` lists
 * `counts[kind].skipped` only — merges belong in the refreshed footer, not this list.
 */
fun backupImportAlreadyHereRows(result: BackupImportResult): List<BackupPreviewRow> = backupManifestRows {
    it.skipped(result)
}

/**
 * Dropped rows for the import-success sheet. iOS `ImportSuccessContent` iterates
 * `BackupModelKind.allCases` and shows `counts[kind].dropped > 0`.
 */
fun backupImportDroppedRows(result: BackupImportResult): List<BackupPreviewRow> = backupManifestRows {
    it.dropped(result)
}
