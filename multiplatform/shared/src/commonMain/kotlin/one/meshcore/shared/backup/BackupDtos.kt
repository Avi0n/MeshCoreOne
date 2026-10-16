@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import one.meshcore.shared.model.Base64ByteArraySerializer
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid

// Cross-actor / on-disk DTOs decoded from the backup envelope. Field naming matches the JSON keys.
// Conventions, all matching the Swift producer:
//   * `ByteArray` = Base64 string (file-wide serializer above; kotlinx's default byte-array shape
//     would be a JSON array and is wrong here).
//   * `Uuid`      = uppercase string via UuidSerializer.
//   * dates       = `Double` epoch seconds (Swift `.secondsSince1970`). Double, not Long, so
//     production sub-second timestamps decode without loss; the whole-second fixtures decode too.
//   * `UInt32`-origin fields are `Long` to hold the full unsigned range; smaller ints are `Int`.
// Optional (Swift `decodeIfPresent`) fields are nullable with a `= null` default so a missing key
// decodes to null and a present value (including empty string) is preserved distinctly.

/** Mirrors `DeviceDTO`. */
@Serializable
data class DeviceDto(
    val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val nodeName: String,
    val firmwareVersion: Int,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val maxContacts: Int,
    val maxChannels: Int,
    val frequency: Long,
    val bandwidth: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val latitude: Double,
    val longitude: Double,
    val blePin: Long,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
    val manualAddContacts: Boolean,
    val autoAddConfig: Int,
    val autoAddMaxHops: Int,
    val multiAcks: Int,
    val telemetryModeBase: Int,
    val telemetryModeLoc: Int,
    val telemetryModeEnv: Int,
    val advertLocationPolicy: Int,
    val lastConnected: Double,
    val lastContactSync: Long,
    val isActive: Boolean,
    val connectionMethods: List<ConnectionMethod> = emptyList(),
    val knownRegions: List<String> = emptyList(),
    val defaultFloodScopeName: String? = null,
    val preRepeatFrequency: Long? = null,
    val preRepeatBandwidth: Long? = null,
    val preRepeatSpreadingFactor: Int? = null,
    val preRepeatCodingRate: Int? = null,
    val ocvPreset: String? = null,
    val customOCVArrayString: String? = null,
    val appliedRadioPresetID: String? = null,
)

/** Mirrors `ContactDTO`. `typeRawValue` is a raw `UInt8`, not a typed enum, exactly as in Swift. */
@Serializable
data class ContactDto(
    val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val flags: Int,
    val outPathLength: Int,
    val outPath: ByteArray,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
    val lastHeardTimestamp: Long? = null,
    val isBlocked: Boolean,
    val isMuted: Boolean,
    val isFavorite: Boolean,
    val isPinned: Boolean = false,
    val unreadCount: Int,
    val unreadMentionCount: Int = 0,
    val nickname: String? = null,
    val lastMessageDate: Double? = null,
    val ocvPreset: String? = null,
    val customOCVArrayString: String? = null,
    val avatarImageData: ByteArray? = null,
)

/**
 * Mirrors `ChannelDTO`. The flood scope is split across [floodScopeModeRawValue] + [regionScope];
 * [floodScope] recomposes them and replicates the Swift legacy fallback: when the mode key is absent,
 * a present `regionScope` implies `specific` (yielding [ChannelFloodScope.Region]) and an absent one
 * implies `inherit`. The other late-added keys fall back through plain defaults.
 */
@Serializable
data class ChannelDto(
    val id: Uuid,
    val radioID: Uuid,
    val index: Int,
    val name: String,
    val secret: ByteArray,
    val isEnabled: Boolean,
    val unreadCount: Int,
    val unreadMentionCount: Int = 0,
    val notificationLevel: NotificationLevel = NotificationLevel.ALL,
    val isPinned: Boolean = false,
    val floodScopeModeRawValue: String? = null,
    val regionScope: String? = null,
    val lastMessageDate: Double? = null,
) {
    /**
     * The mode column a row built from this DTO must store. Every write path goes through this rather
     * than the nullable wire field, so a legacy envelope's inferred `specific` is persisted as such
     * instead of collapsing to `inherit` and orphaning [regionScope].
     */
    val resolvedFloodScopeModeRawValue: String
        get() = floodScopeModeRawValue
            ?: if (regionScope != null) {
                ChannelFloodScopeStorage.MODE_SPECIFIC
            } else {
                ChannelFloodScopeStorage.MODE_INHERIT
            }

    val floodScope: ChannelFloodScope
        get() = ChannelFloodScopeStorage.recompose(resolvedFloodScopeModeRawValue, regionScope)
}

/**
 * Mirrors `MessageDTO`. [sortDate] falls back to [createdAt] when the wire lacks the late-added key,
 * matching the Swift `decodeIfPresent(sortDate) ?? createdAt`.
 */
@Serializable
data class MessageDto(
    val id: Uuid,
    val radioID: Uuid,
    val text: String,
    val timestamp: Long,
    val createdAt: Double,
    val direction: MessageDirection,
    val status: MessageStatus,
    val textType: TextType,
    val pathLength: Int,
    val isRead: Boolean,
    val heardRepeats: Int,
    val sendCount: Int,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    val linkPreviewFetched: Boolean,
    val containsSelfMention: Boolean,
    val mentionSeen: Boolean,
    val failureSeen: Boolean = false,
    val timestampCorrected: Boolean,
    val contactID: Uuid? = null,
    val channelIndex: Int? = null,
    @SerialName("sortDate") val sortDateRaw: Double? = null,
    val ackCode: Long? = null,
    val snr: Double? = null,
    val pathNodes: ByteArray? = null,
    val senderKeyPrefix: ByteArray? = null,
    val senderNodeName: String? = null,
    val replyToID: Uuid? = null,
    val roundTripTime: Long? = null,
    val deduplicationKey: String? = null,
    val linkPreviewURL: String? = null,
    val linkPreviewTitle: String? = null,
    val linkPreviewImageData: ByteArray? = null,
    val linkPreviewIconData: ByteArray? = null,
    val senderTimestamp: Long? = null,
    val reactionSummary: String? = null,
    val routeType: RouteType? = null,
    val regionScope: String? = null,
    val regionScopeMatches: List<String> = emptyList(),
) {
    /** Send-time ordering key; defaults to [createdAt] for rows written before the column existed. */
    val sortDate: Double get() = sortDateRaw ?: createdAt

    val isChannelMessage: Boolean get() = channelIndex != null
}

/** Mirrors `MessageRepeatDTO`. */
@Serializable
data class MessageRepeatDto(
    val id: Uuid,
    val messageID: Uuid,
    val receivedAt: Double,
    val pathNodes: ByteArray,
    val pathLength: Int = 0,
    val snr: Double? = null,
    val rssi: Int? = null,
    val rxLogEntryID: Uuid? = null,
)

/** Mirrors `ReactionDTO`. `messageHash` is an 8-char hex string, not binary. */
@Serializable
data class ReactionDto(
    val id: Uuid,
    val messageID: Uuid,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val receivedAt: Double,
    val radioID: Uuid,
    val channelIndex: Int? = null,
    val contactID: Uuid? = null,
)

/** Mirrors `RoomMessageDTO`. `statusRawValue` is a raw `Int`, not a typed enum, as in Swift. */
@Serializable
data class RoomMessageDto(
    val id: Uuid,
    val sessionID: Uuid,
    val authorKeyPrefix: ByteArray,
    val text: String,
    val timestamp: Long,
    val createdAt: Double,
    val isFromSelf: Boolean,
    val deduplicationKey: String,
    val statusRawValue: Int,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    val failureSeen: Boolean = false,
    val authorName: String? = null,
    val ackCode: Long? = null,
    val roundTripTime: Long? = null,
)

/** Mirrors `RemoteNodeSessionDTO`. */
@Serializable
data class RemoteNodeSessionDto(
    val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val role: RemoteNodeRole,
    val latitude: Double,
    val longitude: Double,
    val isConnected: Boolean,
    val permissionLevel: RoomPermissionLevel,
    val unreadCount: Int,
    val notificationLevel: NotificationLevel,
    val isPinned: Boolean = false,
    val neighborCount: Int,
    val lastSyncTimestamp: Long,
    val lastConnectedDate: Double? = null,
    val lastBatteryMillivolts: Int? = null,
    val lastUptimeSeconds: Long? = null,
    val lastNoiseFloor: Int? = null,
    val lastRxAirtimeSeconds: Long? = null,
    val lastMessageDate: Double? = null,
)

/** Mirrors `SavedTracePathDTO`. */
@Serializable
data class SavedTracePathDto(
    val id: Uuid,
    val radioID: Uuid,
    val name: String,
    val pathBytes: ByteArray,
    val hashSize: Int = 1,
    val createdDate: Double,
    val runs: List<TracePathRunDto> = emptyList(),
)

/** Mirrors `TracePathRunDTO`. */
@Serializable
data class TracePathRunDto(
    val id: Uuid,
    val date: Double,
    val success: Boolean,
    val roundTripMs: Int,
    val hopsSNR: List<Double> = emptyList(),
)

/** Mirrors `BlockedChannelSenderDTO`. */
@Serializable
data class BlockedChannelSenderDto(val id: Uuid, val name: String, val radioID: Uuid, val dateBlocked: Double)

/** Mirrors `NodeStatusSnapshotDTO`. */
@Serializable
data class NodeStatusSnapshotDto(
    val id: Uuid,
    val timestamp: Double,
    val nodePublicKey: ByteArray,
    val batteryMillivolts: Int? = null,
    val lastSNR: Double? = null,
    val lastRSSI: Int? = null,
    val noiseFloor: Int? = null,
    val uptimeSeconds: Long? = null,
    val rxAirtimeSeconds: Long? = null,
    val packetsSent: Long? = null,
    val packetsReceived: Long? = null,
    val receiveErrors: Long? = null,
    val sentDirect: Long? = null,
    val sentFlood: Long? = null,
    val receivedDirect: Long? = null,
    val receivedFlood: Long? = null,
    val directDuplicates: Long? = null,
    val floodDuplicates: Long? = null,
    val postedCount: Int? = null,
    val postPushCount: Int? = null,
    val neighborSnapshots: List<NeighborSnapshotEntry>? = null,
    val telemetryEntries: List<TelemetrySnapshotEntry>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
)

/** Mirrors `NeighborSnapshotEntry`. */
@Serializable
data class NeighborSnapshotEntry(val publicKeyPrefix: ByteArray, val snr: Double, val secondsAgo: Int)

/** Mirrors `TelemetrySnapshotEntry`. */
@Serializable
data class TelemetrySnapshotEntry(val channel: Int, val type: String, val value: Double)

/** Mirrors `DiscoveredNodeDTO` (the passive Discover list, late-added to the envelope). */
@Serializable
data class DiscoveredNodeDto(
    val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val lastHeard: Double,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val outPathLength: Int,
    val outPath: ByteArray,
    val inboundHopCount: Int? = null,
    val inboundHopAdvertTimestamp: Long? = null,
)
