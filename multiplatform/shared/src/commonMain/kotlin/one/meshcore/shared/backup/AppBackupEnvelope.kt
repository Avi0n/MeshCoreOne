package one.meshcore.shared.backup

import kotlinx.serialization.Serializable

/**
 * Top-level backup container. Mirrors Swift `AppBackupEnvelope`: every SwiftData family is a DTO
 * array, non-SwiftData UserDefaults live in [userDefaults], and [manifest] carries declared counts
 * validated after decode.
 *
 * The eleven original families carry no default, so a file missing one fails to decode exactly as
 * Swift's hard `decode` does. Defaulting them would let a truncated file's absent array agree with
 * the manifest's absent count and import as a silent zero-row restore. `discoveredNodes` is the one
 * family added after the format shipped, so its key is legitimately absent from older envelopes and
 * it alone defaults to empty.
 */
@Serializable
data class AppBackupEnvelope(
    val version: Int,
    val exportDate: Double,
    val appVersion: String,
    val appBuild: String,
    val manifest: BackupManifest,
    val devices: List<DeviceDto>,
    val contacts: List<ContactDto>,
    val channels: List<ChannelDto>,
    val messages: List<MessageDto>,
    val messageRepeats: List<MessageRepeatDto>,
    val reactions: List<ReactionDto>,
    val roomMessages: List<RoomMessageDto>,
    val remoteNodeSessions: List<RemoteNodeSessionDto>,
    val savedTracePaths: List<SavedTracePathDto>,
    val blockedChannelSenders: List<BlockedChannelSenderDto>,
    val nodeStatusSnapshots: List<NodeStatusSnapshotDto>,
    val discoveredNodes: List<DiscoveredNodeDto> = emptyList(),
    val userDefaults: BackupUserDefaults? = null,
) {
    /**
     * The integrity gate every import must clear, mirroring the two `guard`s in Swift `parseBackup`: a
     * newer format is refused outright, and a manifest that disagrees with the arrays it describes means
     * the file was truncated or edited, so no part of it is trustworthy enough to merge.
     */
    fun validateForImport() {
        if (version > CURRENT_VERSION) {
            throw AppBackupError.UnsupportedVersion(found = version, maxSupported = CURRENT_VERSION)
        }
        if (!manifest.validate(this)) throw AppBackupError.CorruptedManifest()
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Declared per-family counts. Mirrors Swift `BackupManifest`. [validate] confirms the declared counts
 * match the actual array sizes, the same integrity gate Swift's `parseBackup` applies.
 *
 * The eleven original counts carry no default for the same reason their arrays do not: a count that
 * defaults to zero alongside an array that defaults to empty makes the gate agree with itself.
 */
@Serializable
data class BackupManifest(
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
    // Legacy manifests omit this key; the default 0 matches an empty discoveredNodes array.
    val discoveredNodeCount: Int = 0,
) {
    fun validate(envelope: AppBackupEnvelope): Boolean = this ==
        BackupManifest(
            deviceCount = envelope.devices.size,
            contactCount = envelope.contacts.size,
            channelCount = envelope.channels.size,
            messageCount = envelope.messages.size,
            messageRepeatCount = envelope.messageRepeats.size,
            reactionCount = envelope.reactions.size,
            roomMessageCount = envelope.roomMessages.size,
            remoteNodeSessionCount = envelope.remoteNodeSessions.size,
            savedTracePathCount = envelope.savedTracePaths.size,
            blockedChannelSenderCount = envelope.blockedChannelSenders.size,
            nodeStatusSnapshotCount = envelope.nodeStatusSnapshots.size,
            discoveredNodeCount = envelope.discoveredNodes.size,
        )
}
