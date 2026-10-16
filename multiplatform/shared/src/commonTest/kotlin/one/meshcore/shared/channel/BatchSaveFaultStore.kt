package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid

/** A channel store that can fail [ChannelStore.batchSaveChannels] and still seed rows through [inner]. */
internal class BatchSaveFaultStore(private val inner: InMemoryChannelStore) : ChannelStore by inner {
    var batchError: Throwable? = null

    suspend fun seedChannel(channel: ChannelDto) = inner.seedChannel(channel)

    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> {
        batchError?.let { throw it }
        return inner.batchSaveChannels(radioID, configured, unconfiguredIndices, pruneBeyond)
    }
}
