package one.meshcore.shared.channel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid

/** Shared channel maps and the one mutex behind [InMemoryChannelStore]. */
internal class InMemoryChannelParts(val newId: () -> Uuid) {
    val mutex = Mutex()
    val channels = mutableMapOf<Uuid, ChannelDto>()
    val messageCounts = mutableMapOf<Int, Int>()
    val deletedMessageSlots = mutableListOf<Int>()
    val localUpdates = InMemoryChannelLocalUpdates(this)
    val records = InMemoryChannelRecords(this)
    val seeds = InMemoryChannelSeeds(this)
    val store: ChannelStore = records
}

/** Last-message, flood-scope, and unread writes for the in-memory channel rows. */
internal class InMemoryChannelLocalUpdates(private val parts: InMemoryChannelParts) : ChannelLocalUpdates {
    override suspend fun updateChannelLastMessage(channelID: Uuid, date: Double?) = parts.mutex.withLock {
        parts.channels[channelID]?.let { parts.channels[channelID] = it.copy(lastMessageDate = date) }
        Unit
    }

    override suspend fun updateChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope) = parts.mutex.withLock {
        val (mode, region) = ChannelFloodScopeStorage.decompose(scope)
        parts.channels[channelID]?.let {
            parts.channels[channelID] = it.copy(floodScopeModeRawValue = mode, regionScope = region)
        }
        Unit
    }

    override suspend fun clearChannelUnreadCount(channelID: Uuid) = parts.mutex.withLock {
        parts.channels[channelID]?.let { parts.channels[channelID] = it.copy(unreadCount = 0) }
        Unit
    }

    override suspend fun clearChannelUnreadMentionCount(channelID: Uuid) = parts.mutex.withLock {
        parts.channels[channelID]?.let { parts.channels[channelID] = it.copy(unreadMentionCount = 0) }
        Unit
    }
}

/** Slot upserts, deletes, and the message cascade. Local field writes stay on [InMemoryChannelLocalUpdates]. */
internal class InMemoryChannelRecords(private val parts: InMemoryChannelParts) :
    ChannelStore,
    ChannelLocalUpdates by parts.localUpdates {
    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = parts.mutex.withLock {
        parts.channels.values.filter { it.radioID == radioID }.sortedBy { it.index }
    }

    override suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto? = parts.mutex.withLock {
        parts.channels.values.firstOrNull { it.radioID == radioID && it.index == index }
    }

    override suspend fun saveChannel(radioID: Uuid, info: ChannelInfo): Uuid = parts.mutex.withLock {
        upsertFrame(radioID, info).id
    }

    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> = parts.mutex.withLock {
        configured.forEach { upsertFrame(radioID, it) }
        for (index in unconfiguredIndices) {
            wipeSlotMessages(index)
            rowAt(radioID, index)?.let { parts.channels.remove(it.id) }
        }
        if (pruneBeyond != null) {
            parts.channels.values
                .filter { it.radioID == radioID && it.index >= pruneBeyond }
                .forEach { row ->
                    wipeSlotMessages(row.index)
                    parts.channels.remove(row.id)
                }
        }
        parts.channels.values.filter { it.radioID == radioID }.sortedBy { it.index }
    }

    override suspend fun deleteChannel(id: Uuid) = parts.mutex.withLock {
        parts.channels.remove(id)?.let { wipeSlotMessages(it.index) }
        Unit
    }

    override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) = parts.mutex.withLock {
        wipeSlotMessages(channelIndex)
    }

    private fun rowAt(radioID: Uuid, index: Int): ChannelDto? =
        parts.channels.values.firstOrNull { it.radioID == radioID && it.index == index }

    /**
     * Insert-or-merge a frame; assigns a fresh id on insert, and preserves local-only fields on a
     * same-secret update.
     */
    private fun upsertFrame(radioID: Uuid, info: ChannelInfo): ChannelDto {
        val existing = rowAt(radioID, info.index)
        val row = if (existing != null) {
            if (existing.secret.contentEquals(info.secret)) {
                existing.copy(name = info.name, secret = info.secret)
            } else {
                wipeSlotMessages(info.index)
                existing.copy(
                    name = info.name,
                    secret = info.secret,
                    unreadCount = 0,
                    unreadMentionCount = 0,
                    lastMessageDate = null,
                )
            }
        } else {
            ChannelDto(
                id = parts.newId(),
                radioID = radioID,
                index = info.index,
                name = info.name,
                secret = info.secret,
                isEnabled = true,
                unreadCount = 0,
            )
        }
        parts.channels[row.id] = row
        return row
    }

    private fun wipeSlotMessages(channelIndex: Int) {
        parts.deletedMessageSlots += channelIndex
        parts.messageCounts[channelIndex] = 0
    }
}

/** Test seeds for channel rows and the per-slot message cascade. */
internal class InMemoryChannelSeeds(private val parts: InMemoryChannelParts) {
    suspend fun seedChannel(dto: ChannelDto) = parts.mutex.withLock {
        parts.channels[dto.id] = dto
        Unit
    }

    suspend fun seedMessages(channelIndex: Int, count: Int) = parts.mutex.withLock {
        parts.messageCounts[channelIndex] = count
    }

    suspend fun remainingMessageCount(channelIndex: Int): Int = parts.mutex.withLock {
        parts.messageCounts[channelIndex] ?: 0
    }

    suspend fun deletedMessagesForSlots(): List<Int> = parts.mutex.withLock { parts.deletedMessageSlots.toList() }
}
