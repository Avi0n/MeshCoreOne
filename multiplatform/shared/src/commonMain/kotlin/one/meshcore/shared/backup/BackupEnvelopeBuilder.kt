package one.meshcore.shared.backup

/**
 * Assembles an [AppBackupEnvelope] with a manifest that matches array sizes. Pure helper used by
 * export (Room collectors + share) and tests. Mirrors Swift `AppBackupService` envelope construction
 * before `makeBackupJSONEncoder()`.
 */
object BackupEnvelopeBuilder {
    fun build(input: BackupExportInput): AppBackupEnvelope {
        val redactedDevices = input.devices.map { it.redactedForBackup() }
        // Redaction happens here rather than in the Room collector so no export path can bypass it.
        val redactedMessages = input.messages.map { it.redactedForBackup() }
        return AppBackupEnvelope(
            version = input.version,
            exportDate = input.exportDate,
            appVersion = input.appVersion,
            appBuild = input.appBuild,
            manifest =
            BackupManifest(
                deviceCount = redactedDevices.size,
                contactCount = input.contacts.size,
                channelCount = input.channels.size,
                messageCount = redactedMessages.size,
                messageRepeatCount = input.messageRepeats.size,
                reactionCount = input.reactions.size,
                roomMessageCount = input.roomMessages.size,
                remoteNodeSessionCount = input.remoteNodeSessions.size,
                savedTracePathCount = input.savedTracePaths.size,
                blockedChannelSenderCount = input.blockedChannelSenders.size,
                nodeStatusSnapshotCount = input.nodeStatusSnapshots.size,
                discoveredNodeCount = input.discoveredNodes.size,
            ),
            devices = redactedDevices,
            contacts = input.contacts,
            channels = input.channels,
            messages = redactedMessages,
            messageRepeats = input.messageRepeats,
            reactions = input.reactions,
            roomMessages = input.roomMessages,
            remoteNodeSessions = input.remoteNodeSessions,
            savedTracePaths = input.savedTracePaths,
            blockedChannelSenders = input.blockedChannelSenders,
            nodeStatusSnapshots = input.nodeStatusSnapshots,
            discoveredNodes = input.discoveredNodes,
            userDefaults = input.userDefaults,
        )
    }

    fun encode(input: BackupExportInput): String = encodeBackupEnvelope(build(input))
}

/** Input snapshot for [BackupEnvelopeBuilder] — one field per envelope family. */
data class BackupExportInput(
    val appVersion: String,
    val appBuild: String,
    val exportDate: Double,
    val devices: List<DeviceDto> = emptyList(),
    val contacts: List<ContactDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val messageRepeats: List<MessageRepeatDto> = emptyList(),
    val reactions: List<ReactionDto> = emptyList(),
    val roomMessages: List<RoomMessageDto> = emptyList(),
    val remoteNodeSessions: List<RemoteNodeSessionDto> = emptyList(),
    val savedTracePaths: List<SavedTracePathDto> = emptyList(),
    val blockedChannelSenders: List<BlockedChannelSenderDto> = emptyList(),
    val nodeStatusSnapshots: List<NodeStatusSnapshotDto> = emptyList(),
    val discoveredNodes: List<DiscoveredNodeDto> = emptyList(),
    val userDefaults: BackupUserDefaults? = null,
    val version: Int = AppBackupEnvelope.CURRENT_VERSION,
)
