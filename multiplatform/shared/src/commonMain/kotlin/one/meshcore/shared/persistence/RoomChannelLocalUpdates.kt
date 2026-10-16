package one.meshcore.shared.persistence

import one.meshcore.shared.channel.ChannelLocalUpdates
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid

/** Room writes for the local-only fields on a channel row. */
internal class RoomChannelLocalUpdates(private val channelDao: ChannelDao) : ChannelLocalUpdates {
    override suspend fun updateChannelLastMessage(channelID: Uuid, date: Double?) {
        channelDao.getById(channelID)?.let { channelDao.upsertPreservingPin(it.copy(lastMessageDate = date)) }
    }

    override suspend fun updateChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope) {
        val (mode, region) = ChannelFloodScopeStorage.decompose(scope)
        channelDao.getById(channelID)?.let {
            channelDao.upsertPreservingPin(it.copy(floodScopeModeRawValue = mode, regionScope = region))
        }
    }

    override suspend fun clearChannelUnreadCount(channelID: Uuid) {
        channelDao.getById(channelID)?.let { channelDao.upsertPreservingPin(it.copy(unreadCount = 0)) }
    }

    override suspend fun clearChannelUnreadMentionCount(channelID: Uuid) {
        channelDao.getById(channelID)?.let { channelDao.upsertPreservingPin(it.copy(unreadMentionCount = 0)) }
    }
}
