package one.meshcore.shared.backup

/**
 * Summary shown on the import-preview surface before merge. Built from a decoded [AppBackupEnvelope].
 */
data class BackupImportPreview(
    val version: Int,
    val exportDateEpochSeconds: Double,
    val appVersion: String,
    val appBuild: String,
    val deviceCount: Int,
    val contactCount: Int,
    val channelCount: Int,
    val messageCount: Int,
    val messageRepeatCount: Int,
    val reactionCount: Int,
    val roomMessageCount: Int,
    val remoteNodeSessionCount: Int,
    val savedTracePathCount: Int,
    val blockedChannelSenderCount: Int,
    val nodeStatusSnapshotCount: Int,
    val discoveredNodeCount: Int,
    val manifestValid: Boolean,
)

/** Builds a [BackupImportPreview] from a fully decoded envelope. */
fun AppBackupEnvelope.toImportPreview(): BackupImportPreview = BackupImportPreview(
    version = version,
    exportDateEpochSeconds = exportDate,
    appVersion = appVersion,
    appBuild = appBuild,
    deviceCount = devices.size,
    contactCount = contacts.size,
    channelCount = channels.size,
    messageCount = messages.size,
    messageRepeatCount = messageRepeats.size,
    reactionCount = reactions.size,
    roomMessageCount = roomMessages.size,
    remoteNodeSessionCount = remoteNodeSessions.size,
    savedTracePathCount = savedTracePaths.size,
    blockedChannelSenderCount = blockedChannelSenders.size,
    nodeStatusSnapshotCount = nodeStatusSnapshots.size,
    discoveredNodeCount = discoveredNodes.size,
    manifestValid = manifest.validate(this),
)

/**
 * Parses an uncompressed JSON backup payload into a preview. Throws on decode failure so the UI can
 * surface [settings_backup_import_error_title].
 */
fun parseBackupImportPreview(jsonText: String): BackupImportPreview = decodeBackupEnvelope(jsonText).toImportPreview()

/**
 * File-picker product path: decode then validate, matching Swift `parseBackup` before
 * `ImportPreviewSheet`. Does not merge. The paste path keeps [parseBackupImportPreview] so a
 * debug JSON blob can still show counts when the manifest is only invalid.
 */
fun previewPickedBackupJson(jsonText: String): BackupImportPreview {
    val envelope = decodeBackupEnvelope(jsonText)
    envelope.validateForImport()
    return envelope.toImportPreview()
}

/**
 * Whether a previewed backup may be merged now. A foreground auto-reconnect can bring the radio up
 * while the preview is on screen, so the connection is re-read at the commit point rather than trusted
 * from whatever disabled the import entry a moment earlier; merging into a live-syncing store races
 * the sync writes. Mirrors the Swift `AppBackupViewModel.performImport` guard.
 */
fun canCommitBackupImport(manifestValid: Boolean, isRadioConnected: Boolean): Boolean =
    manifestValid && !isRadioConnected
