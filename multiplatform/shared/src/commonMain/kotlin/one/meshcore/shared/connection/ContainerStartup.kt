package one.meshcore.shared.connection

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.hydrate
import one.meshcore.shared.chat.observeConnectionState
import one.meshcore.shared.chat.startEventMonitoring
import one.meshcore.shared.chat.warmUp
import one.meshcore.shared.contact.AdvertContactDeltaRun
import one.meshcore.shared.contact.AdvertContactSyncOutcome
import one.meshcore.shared.contact.runAdvertContactDelta
import one.meshcore.shared.contact.setDeltaSyncHandler
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.room.setSelfPublicKeyPrefix
import one.meshcore.shared.settings.DebugLogRetention
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.exportPrivateKeyForRxLog

/** Starts and refreshes the collectors and monitors owned by one live service container. */
internal class ContainerStartup(private val host: ServiceContainer) {
    suspend fun start(connectionStates: Flow<DeviceConnectionState>, initialState: DeviceConnectionState) {
        if (host.eventMonitoringActive) return
        // Bind before any RX-log append can process repeats.
        host.heardRepeatsService.configure(radioID = host.radioId)
        host.chatPrewarmCollectJob?.cancel()
        host.chatPrewarmCollectJob = host.scope.launch {
            host.incomingBroadcaster.events().collect { event ->
                when (event) {
                    is IncomingMessageEvent.DirectMessageReceived ->
                        host.chatPrewarmRefresher.noteDirectMessage(event.message.radioID, event.contactID)
                    is IncomingMessageEvent.ChannelMessageReceived ->
                        host.chatPrewarmRefresher.noteChannelMessage(event.message.radioID, event.channelIndex)
                }
            }
        }
        host.syncLogger.info(wiringMessageHandlersLine(host.radioId))
        host.messageService.startEventMonitoring()
        host.messageService.startAckExpiryChecking()
        host.messagePoller.startEventMonitoring()
        host.messagePollingLogger.info(messageEventMonitoringStartedLine(host.radioId))
        host.syncLogger.info(MESSAGE_HANDLERS_WIRED)
        host.contactService.setDeltaSyncHandler(::syncAdvertContacts)
        host.syncLogger.info(startingDiscoveryEventMonitoringLine(host.radioId))
        startConnectRfCollectors()
        host.contactPathCollector.start(host.session.events)
        host.nodeStorageCollector.start(host.session.events)
        host.roomSessionConversationCollector.start()
        host.messageFailedConversationCollector.start()
        host.adoptedDmNotificationCollector.start()
        host.sendQueue.warmUp()
        host.sendQueue.hydrate()
        host.sendQueue.observeConnectionState(initialState, connectionStates)
        // Seeds the room author self-comparison; node and CLI collectors already run from service init.
        host.roomServerService.setSelfPublicKeyPrefix(host.selfPublicKey)
        host.liveSync.refreshBlockedSenders()
        // Legacy channel rows from blocked names (iOS deleteBlockedSenderMessages on every connect).
        host.liveSync.purgeBlockedSenderChannelMessages()
        runConnectPrune {
            host.debugLogStore?.pruneDebugLogEntries(
                olderThanMillis = (host.nowEpochSeconds() * MILLIS_PER_SECOND).toLong() -
                    DebugLogRetention.WINDOW_MS,
                keepCount = DebugLogRetention.MAX_ENTRIES,
            )
        }
        runConnectPrune {
            host.nodeSnapshotService.pruneOldSnapshots(host.nowEpochSeconds() - SNAPSHOT_RETENTION_SECONDS)
        }
        exportRxLogPrivateKey()
        if (host.sendQueue.isRetired) return
        host.eventMonitoringActive = true
    }

    /** Replays persisted RX rows after an identity or partition change. */
    suspend fun startRxLogCatchUp() {
        host.rxLogCatchUpJob?.cancelAndJoin()
        val store = host.rxLogStore ?: return
        val ownerRadioID = host.radioId
        host.rxLogCatchUpJob = host.scope.launch {
            host.backfillMessagesFromRxLog(store.list(ownerRadioID))
        }
    }

    /** Subscribes RX-log persistence and Discover events to this session. */
    private suspend fun startConnectRfCollectors() {
        host.rxLogCollectJob?.cancel()
        host.rxLogCollector?.let { collector ->
            host.rxLogCollectJob = host.scope.launch { collector.collect(host.session.events) }
        }
        host.discoveredNodeCollectJob?.cancel()
        host.discoveredNodeCollector?.let { collector ->
            host.discoveredNodeCollectJob = host.scope.launch { collector.collect(host.session.events) }
        }
        startRxLogCatchUp()
    }

    // The radio row's lastContactSync is the delta watermark. A lower stored stamp is raised; a
    // newer one is left alone.
    private suspend fun syncAdvertContacts(fullRefetch: Boolean): AdvertContactSyncOutcome {
        val watermark = host.database.deviceDao().listByRadio(host.radioId).maxOfOrNull { it.lastContactSync } ?: 0L
        return runAdvertContactDelta(
            AdvertContactDeltaRun(
                claim = host.advertContactSyncClaim,
                watermark = watermark,
                fullRefetch = fullRefetch,
                referenceNowSeconds = host.nowEpochSeconds().toLong(),
                sync = { since ->
                    host.contactService.syncContacts(host.radioId, since = since).lastSyncTimestamp
                },
                persistWatermark = { last ->
                    for (row in host.database.deviceDao().listByRadio(host.radioId)) {
                        if (row.lastContactSync < last) {
                            host.database.deviceDao().upsert(row.copy(lastContactSync = last))
                        }
                    }
                },
            ),
        )
    }

    /** Exports the companion private key early enough for RX-log DM decryption during initial sync. */
    private suspend fun exportRxLogPrivateKey() {
        val cache = host.rxChannelCache as? RxLogDecodeCache ?: return
        exportPrivateKeyForRxLog(
            export = { host.session.exportPrivateKey() },
            logger = host.connectionLogger,
        )?.let { cache.updatePrivateKeyIf(it) { !host.sendQueue.isRetired } }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val SNAPSHOT_RETENTION_SECONDS = 365.0 * 24.0 * 60.0 * 60.0
    }
}
