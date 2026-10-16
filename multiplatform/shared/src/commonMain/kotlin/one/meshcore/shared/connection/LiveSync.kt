package one.meshcore.shared.connection

import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.channel.ChannelSyncResult
import one.meshcore.shared.channel.ChannelSyncRetry
import one.meshcore.shared.chat.pathdetail.HeardRepeatEcho
import one.meshcore.shared.tools.RxLogEntry

/** Live monitors and the post-sync backlog drain for one connection. */
internal class LiveSync(private val host: ServiceContainer) {
    /**
     * Restarts a live pipeline the foreground health check found dead. Event monitors come back
     * first; auto-fetch only when [enableAutoFetch] is true so a Syncing radio does not lose
     * backlog to a second drain. Mirrors iOS `reconcileConnectedBLEListeners`.
     */
    suspend fun reconcileLiveListeners(
        connectionStates: Flow<DeviceConnectionState>,
        initialState: DeviceConnectionState,
        enableAutoFetch: Boolean,
    ) {
        if (!host.eventMonitoringActive) {
            host.start(connectionStates, initialState)
        }
        if (enableAutoFetch) startAutoMessageFetching()
    }

    /**
     * Forwards a Tools → RX Log row into [host.heardRepeatsService]. Safe to call for every append;
     * non-matching payloads return null without writes.
     */
    suspend fun processRxLogEntryForRepeats(entry: RxLogEntry): Int? =
        host.heardRepeatsService.processForRepeats(HeardRepeatEcho.fromRxLogEntry(entry))

    /** Drains the device's queued backlog through the ingest pipeline; the caller invokes it post-sync. */
    suspend fun pollBacklog(): Int = host.messagePoller.pollAllMessages()

    /**
     * Enables the session's own `messagesWaiting` drain.
     *
     * Must run only after [pollBacklog] has returned. The session's drain pops messages off the radio
     * itself and delivers each one solely as an event, which [one.meshcore.shared.chat.MessagePoller]
     * discards while its own backlog drain holds the gate — so two drains running together take inbound
     * messages off the radio and drop them. Mirrors the Swift `startAutoFetch` placement at the end of the
     * sync window.
     */
    suspend fun startAutoMessageFetching() {
        if (host.autoFetchEnabled) return
        host.session.startAutoMessageFetching()
        host.autoFetchEnabled = true
        host.messagePollingLogger.info(autoFetchStartedLine(host.radioId))
    }

    /**
     * Stops the session drain without clearing [host.isAutoFetching], so a later [resumeAutoMessageFetching]
     * can re-arm it. Used around a resync backlog poll so the two drains never run together. Mirrors iOS
     * `pauseAutoFetch`.
     */
    suspend fun pauseAutoMessageFetching() {
        if (!host.autoFetchEnabled) return
        host.session.stopAutoMessageFetching()
        host.messagePollingLogger.info(PAUSING_AUTO_FETCH_FOR_RESYNC)
    }

    /**
     * Re-arms the session drain after [pauseAutoMessageFetching]. A no-op when auto-fetch was never
     * started. Mirrors iOS `resumeAutoFetch`.
     */
    suspend fun resumeAutoMessageFetching() {
        if (!host.autoFetchEnabled) return
        host.session.startAutoMessageFetching()
        host.messagePollingLogger.info(RESUMING_AUTO_FETCH_AFTER_RESYNC)
    }

    /**
     * Pulls channel slots from the radio into Room (secrets + names), retrying the slots whose failures a
     * retry could recover. Skips when the device reports zero channel capacity. Mirrors the channels phase
     * of iOS `SyncCoordinator.performFullSync`, retry included.
     */
    suspend fun syncChannelsFromDevice(usePipelinedRead: Boolean = true): ChannelSyncResult? {
        if (host.maxChannels <= 0) return null
        return ChannelSyncRetry.sync(
            host.channelService,
            host.radioId,
            host.maxChannels,
            usePipelinedRead,
            logger = host.syncLogger,
        )
    }

    /**
     * Re-reads only [indices], the slots a sync pass could not read, without replaying contacts or messages.
     * Whatever answers is upserted at the slot it was read from; nothing is pruned, so a slot still down
     * keeps whatever row it already had. Mirrors iOS `SyncCoordinator.retryChannels`, which the manager's
     * channel-only retry drives after a partial channel phase.
     */
    suspend fun retryChannelSlots(indices: List<Int>): ChannelSyncResult =
        host.channelService.retryFailedChannels(host.radioId, indices)

    /** Refreshes the blocked-sender cache from the stored blocked contacts and blocked channel senders. */
    suspend fun refreshBlockedSenders() {
        host.blockedNamesRefresher.refresh(host.radioId)
    }

    /**
     * Deletes channel messages from every name currently in the blocked-sender cache. Call after
     * [refreshBlockedSenders] on connect. Mirrors iOS `SyncCoordinator.deleteBlockedSenderMessages`.
     */
    suspend fun purgeBlockedSenderChannelMessages() {
        val names = host.blockedSenders.snapshot()
        if (names.isEmpty()) return
        host.blockedChannelSenderService.purgeExistingMessagesForBlockedNames(host.radioId, names)
    }
}
