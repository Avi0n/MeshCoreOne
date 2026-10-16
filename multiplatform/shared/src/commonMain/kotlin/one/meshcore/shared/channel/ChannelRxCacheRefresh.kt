package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** Pushes the persisted channel list into the RX decrypt cache. A cache fault does not fail the caller. */
internal class ChannelRxCacheRefresh(
    private val store: ChannelStore,
    private val rxCache: ChannelListSink,
    private val logger: PersistentLogger?,
) {
    suspend fun fromStore(radioID: Uuid) {
        val channels = runChannelCacheRefresh(
            warning = { line -> logger?.error(line) },
            fallback = { null },
        ) {
            store.fetchChannels(radioID)
        } ?: return
        apply(channels)
    }

    /** iOS `SyncCoordinator.refreshRxLogChannels`. A cache fault is logged and does not fail the sync. */
    suspend fun apply(channels: List<ChannelDto>) {
        runChannelCacheRefresh(
            warning = { line -> logger?.error(line) },
            fallback = {},
        ) {
            rxCache.updateChannels(channels)
        }
    }
}
