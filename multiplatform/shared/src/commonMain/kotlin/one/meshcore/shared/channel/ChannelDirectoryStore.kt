package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/** Local channel reads. The radio is not contacted. */
interface ChannelDirectory {
    /** All channels for a radio from the local store, sorted by slot. */
    suspend fun getChannels(radioID: Uuid): List<ChannelDto>

    /** The `(radioID, index)` channel from the local store, or null. */
    suspend fun getChannel(radioID: Uuid, index: Int): ChannelDto?

    /** Channels that have messages (a non-null last-message date), for the chat list. */
    suspend fun getActiveChannels(radioID: Uuid): List<ChannelDto>

    /** Whether the public channel (slot 0) exists locally. */
    suspend fun hasPublicChannel(radioID: Uuid): Boolean
}

internal class ChannelDirectoryStore(private val store: ChannelStore) : ChannelDirectory {
    override suspend fun getChannels(radioID: Uuid): List<ChannelDto> = store.fetchChannels(radioID)

    override suspend fun getChannel(radioID: Uuid, index: Int): ChannelDto? = store.fetchChannel(radioID, index)

    override suspend fun getActiveChannels(radioID: Uuid): List<ChannelDto> =
        store.fetchChannels(radioID).filter { it.lastMessageDate != null }

    override suspend fun hasPublicChannel(radioID: Uuid): Boolean =
        store.fetchChannel(radioID, ChannelSecret.PUBLIC_CHANNEL_SLOT) != null
}
