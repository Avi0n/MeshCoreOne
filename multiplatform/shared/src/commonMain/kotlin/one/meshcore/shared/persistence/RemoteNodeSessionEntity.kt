package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `RemoteNodeSession` model. Role, permission level, and
 * notification level are stored as raw columns with typed accessors; `notificationLevelRawValue`
 * keeps the `-1` unmigrated sentinel.
 */
@Entity(tableName = "remote_node_session")
data class RemoteNodeSessionEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val roleRawValue: Int,
    val latitude: Double,
    val longitude: Double,
    val isConnected: Boolean,
    val permissionLevelRawValue: Int,
    val unreadCount: Int,
    val notificationLevelRawValue: Int = NotificationLevel.ALL.raw,
    val isPinned: Boolean = false,
    val neighborCount: Int = 0,
    val lastSyncTimestamp: Long = 0,
    val lastConnectedDate: Double? = null,
    val lastBatteryMillivolts: Int? = null,
    val lastUptimeSeconds: Long? = null,
    val lastNoiseFloor: Int? = null,
    val lastRxAirtimeSeconds: Long? = null,
    val lastMessageDate: Double? = null,
)

val RemoteNodeSessionEntity.role: RemoteNodeRole
    get() = RemoteNodeRole.fromRaw(roleRawValue) ?: RemoteNodeRole.REPEATER

val RemoteNodeSessionEntity.permissionLevel: RoomPermissionLevel
    get() = RoomPermissionLevel.fromRaw(permissionLevelRawValue) ?: RoomPermissionLevel.GUEST

val RemoteNodeSessionEntity.notificationLevel: NotificationLevel
    get() = NotificationLevel.fromRaw(notificationLevelRawValue) ?: NotificationLevel.ALL
