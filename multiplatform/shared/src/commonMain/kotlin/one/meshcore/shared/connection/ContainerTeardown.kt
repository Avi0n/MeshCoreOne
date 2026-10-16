package one.meshcore.shared.connection

import kotlinx.coroutines.cancelAndJoin
import one.meshcore.shared.channel.retireChannelSyncOperations
import one.meshcore.shared.chat.finishStatusEvents
import one.meshcore.shared.chat.retire
import one.meshcore.shared.chat.shutdown
import one.meshcore.shared.chat.stopEventMonitoring
import one.meshcore.shared.contact.finishEvents
import one.meshcore.shared.contact.retireContactSyncOperations
import one.meshcore.shared.contact.stopAdvertDeltaSync
import one.meshcore.shared.remotenode.retireConnectionOperations
import one.meshcore.shared.remotenode.stopAllKeepAlives

/** Stops the per-connection collectors and drains when the link ends. */
internal class ContainerTeardown(private val host: ServiceContainer) {
    suspend fun tearDown() {
        host.sendQueue.retire()
        host.contactService.retireContactSyncOperations()
        host.channelService.retireChannelSyncOperations()
        try {
            stopCollectors()
        } finally {
            host.sendQueue.shutdown()
        }
    }

    private suspend fun stopCollectors() {
        host.eventMonitoringActive = false
        val wasFetching = host.autoFetchEnabled
        host.autoFetchEnabled = false
        val fetchStopCancelled = captureSessionStop(host.messagePollingLogger::warning) {
            host.session.stopAutoMessageFetching()
        }
        if (wasFetching) host.messagePollingLogger.info(AUTO_FETCH_STOPPED)
        host.rxLogCollectJob?.cancel()
        host.rxLogCollectJob = null
        host.rxLogCatchUpJob?.cancelAndJoin()
        host.rxLogCatchUpJob = null
        host.discoveredNodeCollectJob?.cancel()
        host.discoveredNodeCollectJob = null
        host.contactPathCollector.stop()
        host.nodeStorageCollector.stop()
        host.contactService.stopAdvertDeltaSync()
        host.roomSessionConversationCollector.stop()
        host.messageFailedConversationCollector.stop()
        host.adoptedDmNotificationCollector.stop()
        host.messageService.stopEventMonitoring()
        // Routine disconnect: leave Room rows `.sent` (do not call stopAndFailAllPending).
        host.messageService.stopAckExpiryChecking()
        host.messagePoller.stopEventMonitoring()
        host.sendQueue.shutdown()
        host.incomingBroadcaster.finish()
        host.chatPrewarmCollectJob?.cancelAndJoin()
        host.chatPrewarmCollectJob = null
        host.chatPrewarmRefresher.cancelPending()
        host.messageService.finishStatusEvents()
        host.contactService.finishEvents()
        // Stop the node/room collectors and keep-alive loops, and release the view-model push handlers, so
        // nothing outlives the link. The services' background sends run on the manager-owned scope.
        host.remoteNodeService.retireConnectionOperations()
        host.remoteNodeService.stopEventMonitoring()
        host.remoteNodeService.stopAllKeepAlives()
        host.nodeCliCoordinator.stopEventMonitoring()
        host.repeaterAdminService.clearHandlers()
        host.roomAdminService.clearHandlers()
        fetchStopCancelled?.let { throw it }
    }
}
