package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid

/** Local-only channel fields [ChannelStore] updates outside a device frame upsert. */
interface ChannelLocalUpdates {
    /** Sets a channel's last-message date; null clears it so no preview shows. */
    suspend fun updateChannelLastMessage(channelID: Uuid, date: Double?)

    /**
     * Writes both flood-scope backing fields (`floodScopeModeRawValue` + `regionScope`) in one step so a
     * malformed combination cannot persist. Mirrors the Swift `PersistenceStore.setChannelFloodScope`.
     */
    suspend fun updateChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope)

    /** Zeroes a channel's unread count. */
    suspend fun clearChannelUnreadCount(channelID: Uuid)

    /** Zeroes a channel's unread-mention count. */
    suspend fun clearChannelUnreadMentionCount(channelID: Uuid)
}
