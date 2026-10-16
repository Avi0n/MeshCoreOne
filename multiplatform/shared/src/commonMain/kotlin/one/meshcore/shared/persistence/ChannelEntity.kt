package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `Channel` model. Notification level is the raw
 * [notificationLevelRawValue] (with the `-1` unmigrated sentinel preserved), and the flood scope is
 * the two raw columns [floodScopeModeRawValue] + [regionScope], forwarded verbatim so a pre-migration
 * row survives without passing through the normalizing accessor.
 */
@Entity(tableName = "channel")
data class ChannelEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val index: Int,
    val name: String,
    val secret: ByteArray,
    val isEnabled: Boolean,
    val unreadCount: Int,
    val unreadMentionCount: Int = 0,
    val notificationLevelRawValue: Int = NotificationLevel.ALL.raw,
    val isPinned: Boolean = false,
    val floodScopeModeRawValue: String = ChannelFloodScopeStorage.MODE_INHERIT,
    val regionScope: String? = null,
    val lastMessageDate: Double? = null,
)

/** Typed notification level; the `-1` unmigrated sentinel and unknown raws fall back to `ALL`. */
val ChannelEntity.notificationLevel: NotificationLevel
    get() = NotificationLevel.fromRaw(notificationLevelRawValue) ?: NotificationLevel.ALL

/** Typed flood-scope preference recomposed from the two raw storage columns. */
val ChannelEntity.floodScope: ChannelFloodScope
    get() = ChannelFloodScopeStorage.recompose(floodScopeModeRawValue, regionScope)
