package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportPreview

/** A valid one-device backup preview, so a settings test can ignore the other counts. */
internal fun samplePreview() = BackupImportPreview(
    version = 1,
    exportDateEpochSeconds = 0.0,
    appVersion = "1.0",
    appBuild = "1",
    deviceCount = 1,
    contactCount = 0,
    channelCount = 0,
    messageCount = 0,
    messageRepeatCount = 0,
    reactionCount = 0,
    roomMessageCount = 0,
    remoteNodeSessionCount = 0,
    savedTracePathCount = 0,
    blockedChannelSenderCount = 0,
    nodeStatusSnapshotCount = 0,
    discoveredNodeCount = 0,
    manifestValid = true,
)
