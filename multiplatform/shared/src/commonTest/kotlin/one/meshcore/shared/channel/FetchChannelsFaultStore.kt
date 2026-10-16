package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/** A channel store that can fail [ChannelStore.fetchChannels] and still seed rows through [inner]. */
internal class FetchChannelsFaultStore(private val inner: InMemoryChannelStore) : ChannelStore by inner {
    var fetchError: Throwable? = null

    suspend fun seedChannel(channel: ChannelDto) = inner.seedChannel(channel)

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> {
        fetchError?.let { throw it }
        return inner.fetchChannels(radioID)
    }
}
