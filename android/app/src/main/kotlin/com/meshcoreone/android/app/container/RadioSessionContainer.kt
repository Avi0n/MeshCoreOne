// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.AppSession
import com.meshcoreone.android.app.state.BatteryServices
import com.meshcoreone.android.app.state.MessageEventSources
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.LifecycleStage
import com.meshcoreone.android.core.contracts.domain.MessagingChannelQuery
import com.meshcoreone.android.core.contracts.domain.MonitoringOptions
import com.meshcoreone.android.core.contracts.domain.OutgoingChannelReactionIndexer
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.TeardownIssue
import com.meshcoreone.android.core.contracts.domain.TeardownReport
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.FactoryOwnership
import com.meshcoreone.android.core.runtime.RuntimeServiceInputs
import com.meshcoreone.android.core.runtime.RuntimeServices
import com.meshcoreone.android.core.runtime.RuntimeSyncResult
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ContactCleanupCoordinator
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ResolvedFloodScope
import com.meshcoreone.android.core.services.device.DeviceService
import com.meshcoreone.android.core.services.device.DeviceSettingsServiceFactory
import com.meshcoreone.android.core.services.device.DeviceSettingsServices
import com.meshcoreone.android.core.services.device.SettingsService
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.diagnostics.RxLogService
import com.meshcoreone.android.core.services.diagnostics.RxLogServiceStore
import com.meshcoreone.android.core.services.diagnostics.persistentRxLogDiagnostics
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessagePollingService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.notifications.NotificationActionHandler
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.notifications.NotificationUnreadCounting
import com.meshcoreone.android.core.services.reactions.HeardRepeatsService
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.remote.BinaryProtocolService
import com.meshcoreone.android.core.services.remote.NodeConfigService
import com.meshcoreone.android.core.services.remote.NodeSnapshotService
import com.meshcoreone.android.core.services.remote.RemoteNodeService
import com.meshcoreone.android.core.services.remote.RepeaterAdminService
import com.meshcoreone.android.core.services.remote.RoomAdminService
import com.meshcoreone.android.core.services.remote.RoomServerService
import com.meshcoreone.android.core.services.remote.asBinaryProtocolSessionPort
import com.meshcoreone.android.core.services.remote.handleBLEDisconnection
import com.meshcoreone.android.core.services.remote.handleBLEReconnection
import com.meshcoreone.android.core.services.remote.asRemoteNodeSessionPort
import com.meshcoreone.android.core.services.remote.remoteAdminStore
import com.meshcoreone.android.core.services.remote.remoteNodeStore
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.services.sync.SyncDependencies
import com.meshcoreone.android.core.services.sync.SyncRetryController
import com.meshcoreone.android.core.services.sync.SyncRetryServices
import com.meshcoreone.android.core.services.sync.cancelDiscoveryEventMonitoring
import com.meshcoreone.android.core.services.sync.onDisconnected
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The per-connection service graph (Swift `ServiceContainer`). Built by [RadioSessionContainerFactory] only
 * after the runtime has a session, a resolved radio identity and a persisted device row, so no service exists
 * before its prerequisites. Every monitor, loop and scope it creates hangs off one supervisor job that
 * [tearDown] cancels, and every event stream is finished, so a reconnect or radio switch can neither duplicate
 * a monitor nor leak the previous graph.
 */
class RadioSessionContainer private constructor(
    override val token: SessionToken,
    private val inputs: RuntimeServiceInputs,
    private val env: SessionEnvironment,
    private val containerJob: Job,
    private val scope: CoroutineScope,
    private val ownership: FactoryOwnership,
) : RuntimeServices, AppSession {
    private val logger: Logger = Logger.getLogger("com.mc1.RadioSessionContainer")
    private val lock = Any()
    private var closed = false
    internal val chatSendQueueProgress: ChatSendQueueProgress = env.chatSendQueueProgress
    private val session: MeshCoreSession = inputs.connection.session
    private val radioId: RadioId = token.radioId
    private val gaps = GapReporter { message -> logger.info(message) }

    internal suspend fun sendToolTrace(tag: UInt, authCode: UInt, flags: UByte, path: Bytes): MessageSentInfo =
        session.sendTrace(tag, authCode, flags, path)

    internal suspend fun sendToolDiscovery(filter: UByte, prefixOnly: Boolean): UInt =
        session.sendNodeDiscoverRequest(filter, prefixOnly)

    internal fun toolEvents(): Flow<MeshEvent> = session.events()

    internal suspend fun applyChannelFloodScope(scope: ResolvedFloodScope) {
        when (scope) {
            ResolvedFloodScope.Unscoped -> session.setFloodScopeUnscoped()
            is ResolvedFloodScope.Scope -> session.setFloodScope(scope.scope)
        }
    }

    override val dataStore: PersistenceStoreProtocol = env.store

    // region Services (constructed in dependency order; every dependency exists before its consumer)

    override val syncCoordinator = SyncCoordinator(scope, env.syncClock, env.logSink)
    override val reactionService = ReactionService()
    val heardRepeatsService = HeardRepeatsService(dataStore)
    override val rxLogService = RxLogService(
        session, RxLogServiceStore.from(dataStore), HeardRepeatProcessingAdapter(heardRepeatsService), scope,
        diagnostics = persistentRxLogDiagnostics(),
    )
    override val notificationService = NotificationService(
        radioId, env.notificationDelivery, env.notificationPreferences, env.appState,
        NotificationUnreadCounting {
            try {
                dataStore.getTotalUnreadCounts(radioId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UnreadCounts(0, 0, 0)
            }
        },
        scope,
    )
    override val remoteNodeService = RemoteNodeService(
        session.asRemoteNodeSessionPort(), remoteNodeStore(dataStore, dataStore),
        SecretPasswordStore(env.passwords, radioId), scope,
    )
    private val coordinatorAdapter = ContactSyncCoordinatorAdapter(syncCoordinator)
    override val contactService = ContactService(
        session, dataStore, dataStore, coordinatorAdapter,
        ContactCleanupCoordinator(
            dataStore, dataStore, coordinatorAdapter, CleanupNotificationsAdapter(notificationService),
            CleanupRemoteSessionsAdapter(radioId, remoteNodeService), radioId,
        ),
        env.contactPreferences,
    )
    override val messageService = MessageService(
        token, inputs.device.publicKey, session, dataStore, inputs.connection.signals, scope,
        reporter = env.messagingReporter,
    )
    override val channelService = ChannelService(session, dataStore, ChannelDecryptionAdapter(rxLogService))
    // The settings/device services validate their generation against the published connection snapshot when they
    // are built, and the runtime publishes CONNECTED only after the factory returns. They are therefore created on
    // first use (app-state wiring and sync both run after publication) and fail fast if reached earlier.
    private var deviceSettingsValue: DeviceSettingsServices? = null
    private val deviceSettings: DeviceSettingsServices
        get() = synchronized(lock) {
            check(!closed) { "Session is closed" }
            deviceSettingsValue ?: DeviceSettingsServiceFactory(dataStore, dataStore, dataStore, env.sessionClock)
                .create(inputs.connection).also { deviceSettingsValue = it }
        }
    override val settingsService: SettingsService get() = deviceSettings.settingsService
    override val deviceService: DeviceService get() = deviceSettings.deviceService
    internal val regionDiscoveryService get() = deviceSettings.regionDiscoveryService
    override val advertisementService = AdvertisementService(session, session, dataStore, scope, appStateProvider = env.appState)
    val messagePollingService = MessagePollingService(token, session, dataStore, inputs.connection.signals, scope)
    val binaryProtocolService = BinaryProtocolService(session.asBinaryProtocolSessionPort(), scope)
    val debugLogBuffer = DebugLogBuffer(dataStore, scope)
    val nodeConfigService = NodeConfigService(
        session, NodeConfigSettingsAdapter { settingsService }, NodeConfigChannelAdapter(channelService), dataStore,
        coordinatorAdapter,
    )
    val nodeSnapshotService = NodeSnapshotService(dataStore)
    private val adminStore = remoteAdminStore(dataStore, dataStore)
    val repeaterAdminService = RepeaterAdminService(session, remoteNodeService, adminStore)
    val roomAdminService = RoomAdminService(remoteNodeService, adminStore)
    val roomServerService = RoomServerService(session, remoteNodeService, adminStore, radioId, scope)
    val chatSendQueueService = ChatSendQueueService(
        token, dataStore, messageService, MessagingChannelQuery { index -> channelService.fetchChannel(index) },
        OutgoingChannelReactionIndexer { id, channelIndex, senderName, text, timestamp ->
            reactionService.indexMessage(id, channelIndex, senderName, text, timestamp)
        },
        inputs.connection.signals, scope,
        reporter = env.messagingReporter,
        progress = chatSendQueueProgress,
    )
    override val notificationActionHandler = NotificationActionHandler(
        dataStore, NotificationMessageSender(messageService), notificationService,
        NotificationRoomReader(roomServerService), ConversationsNotifierAdapter(syncCoordinator),
    )

    suspend fun getStatsRadio() = settingsService.getStatsRadio()

    // endregion

    // region Sync graph

    private val retryServices = SyncRetryServices(syncCoordinator, buildSyncDependencies(), SyncRemoteNodeAdapter(remoteNodeService))
    private val resyncHost = SessionResyncHost(
        inputs.connection.signals, env.runtimeState, env.resyncFailure,
    ) { retryServices.takeIf { !isClosed } }
    private val retryController = SyncRetryController(resyncHost, scope, env.syncClock, env.logSink)

    private fun buildSyncDependencies() = SyncDependencies(
        dataStore = dataStore,
        contactService = contactService,
        channelService = channelService,
        messagePollingService = messagePollingService,
        notificationService = SyncNotificationsAdapter(notificationService),
        reactionService = SyncReactionAdapter(reactionService),
        advertisementService = SyncAdvertisementAdapter(advertisementService),
        rxLogService = SyncRxLogAdapter(rxLogService),
        heardRepeatsService = SyncHeardRepeatsAdapter(heardRepeatsService),
        roomServerService = SyncRoomServerAdapter(roomServerService),
        roomAdminService = SyncRoomAdminAdapter(roomAdminService),
        repeaterAdminService = SyncRepeaterAdminAdapter(repeaterAdminService),
        codecs = syncMessageCodecs(),
        appStateProvider = env.appState,
        startEventMonitoring = { radio, autoFetch -> startEventMonitoring(radio, enableAutoFetch = autoFetch) },
        exportPrivateKey = { settingsService.exportPrivateKey() },
    )

    // endregion

    // region Lifecycle state

    private enum class Monitoring { STOPPED, ACTIVE }

    private val monitoringLock = Mutex()
    private var monitoring = Monitoring.STOPPED
    private var teardown: CompletableDeferred<TeardownReport>? = null

    private val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Whether teardown has started: a closed container accepts no new monitors or services. */
    val isTornDown: Boolean get() = isClosed

    /** Live coroutines under this graph's supervisor job (leak accounting: zero after teardown). */
    val liveJobCount: Int get() = containerJob.descendants()

    private fun Job.descendants(): Int = children.filter { it.isActive }.sumOf { 1 + it.descendants() }

    /** Whether service event listeners are active (Swift `isEventMonitoringActive`). */
    val isEventMonitoringActive: Boolean get() = synchronized(lock) { monitoring == Monitoring.ACTIVE }

    /** Capabilities degraded by core:services visibility, reported once on first use. */
    val reportedGaps: Set<String> get() = gaps.reportedGaps

    /** Test and diagnostics visibility: the resync/channel-retry loops the retry controller owns. */
    val hasResyncLoop: Boolean get() = retryController.resyncTask != null
    val hasChannelRetry: Boolean get() = retryController.channelRetryTask != null

    /**
     * Whether the WiFi heartbeat probe must pause (syncing, resync loop or channel retry pending). The runtime's
     * heartbeat has no hook that reads this yet (WP-303.md C-04), so it is exposed for that seam.
     */
    val shouldPauseWiFiHeartbeatProbe: Boolean get() = retryController.shouldPauseWiFiHeartbeatProbe

    // endregion

    init {
        DebugLogBuffer.shared = debugLogBuffer
        syncCoordinator.setCleanChannelSyncCallback { inputs.callbacks.cleanChannelSync() }
        syncCoordinator.setChannelSyncAttemptedCallback { inputs.callbacks.channelSyncAttempted() }
        env.notificationStrings?.let(notificationService::setStringProvider)
        scope.launch { notificationService.setup() }
        // The cycle-forced upward call into the connection manager: an identity import re-resolves the radio id.
        nodeConfigService.setOnPostIdentityImport { inputs.callbacks.reconcileIdentity() }
    }

    override val batteryServices: BatteryServices = object : BatteryServices {
        override suspend fun getBattery(): BatteryInfo = settingsService.getBattery()
        override suspend fun postLowBatteryNotification(deviceName: String, batteryPercentage: Long) =
            notificationService.postLowBatteryNotification(deviceName, batteryPercentage)
    }

    override fun subscribeMessageEvents(): MessageEventSources = MessageEventSources(
        dataEvents = syncCoordinator.dataEvents(),
        heardRepeats = heardRepeatsService.events(),
        regionUpdates = rxLogService.regionUpdateEvents(),
        remoteNode = remoteNodeService.events(),
        roomServer = roomServerService.events(),
        messageStatus = messageService.statusEvents(),
    )

    // region RuntimeServices

    override suspend fun awaitChannelQueryAccounting() {
        chatSendQueueService.awaitChannelQueryAccounting()
    }

    override suspend fun hydrate() {
        chatSendQueueService.hydrate()
    }

    override suspend fun startMonitoring(options: MonitoringOptions) {
        startEventMonitoring(radioId, options.enableAutoFetch, options.enableAdvertisementMonitoring)
    }

    /**
     * Starts every per-service monitor exactly once. Start and stop serialize on one lock and a second start while
     * active (or after teardown began) is a no-op, so the runtime's `startMonitoring` and the sync coordinator's
     * `startEventMonitoring` can both call it without double-starting any monitor.
     */
    suspend fun startEventMonitoring(
        radio: RadioId,
        enableAutoFetch: Boolean = true,
        enableAdvertisementMonitoring: Boolean = true,
    ): Unit = monitoringLock.withLock {
        if (synchronized(lock) { closed || monitoring != Monitoring.STOPPED }) return@withLock
        heardRepeatsService.configure(radio)
        if (enableAdvertisementMonitoring) advertisementService.startEventMonitoring(radio)
        rxLogService.startEventMonitoring(radio)
        messageService.startEventMonitoring()
        messageService.startAckExpiryChecking()
        remoteNodeService.startEventMonitoring()
        // Always start message event monitoring so handlers are ready for polled messages.
        messagePollingService.startMessageEventMonitoring(radio)
        if (enableAutoFetch) messagePollingService.startAutoFetch(radio)
        pruneOnConnect()
        synchronized(lock) { monitoring = Monitoring.ACTIVE }
    }

    private fun pruneOnConnect() {
        scope.launch {
            try {
                dataStore.pruneDebugLogEntries(Instant.now().minus(DebugLogRetention.window), DebugLogRetention.MAX_ENTRIES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Debug log prune failed: ${failure.message}")
            }
        }
        scope.launch {
            nodeSnapshotService.pruneOldSnapshots(ZonedDateTime.now().minusYears(1).toInstant())
        }
    }

    /** Stops every monitor. In-flight DMs are not failed: the radio re-emits the delivery confirmation on return. */
    suspend fun stopEventMonitoring(): Unit = monitoringLock.withLock {
        if (synchronized(lock) { monitoring != Monitoring.ACTIVE }) return@withLock
        advertisementService.stopEventMonitoring()
        rxLogService.stopEventMonitoring()
        try {
            dataStore.flushPendingRxLogEntries()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "RX log flush on stop failed: ${failure.message}")
        }
        messageService.stopEventMonitoring()
        messageService.stopAckExpiryChecking()
        messagePollingService.stopMessageEventMonitoring()
        remoteNodeService.stopEventMonitoring()
        debugLogBuffer.shutdown()
        synchronized(lock) { monitoring = Monitoring.STOPPED }
    }

    override suspend fun initialSync(forceFullSync: Boolean): RuntimeSyncResult {
        val outcome = resyncHost.beginOutcome()
        val usable = try {
            retryController.performInitialSync(radioId, retryServices, transportType(), forceFullSync = forceFullSync)
        } catch (cancelled: CancellationException) {
            resyncHost.abandonOutcome()
            throw cancelled
        } catch (failure: Exception) {
            return RuntimeSyncResult.Failed(failure)
        }
        if (usable) return RuntimeSyncResult.Usable
        // A resync loop owns the next 3 attempts; wait for how it ends so the runtime promotes (or not) itself.
        retryController.resyncTask?.join()
        if (!outcome.isCompleted) return RuntimeSyncResult.Failed(ConnectionError.NotConnected())
        return when (val result = outcome.await()) {
            ResyncOutcome.Promoted -> RuntimeSyncResult.Usable
            is ResyncOutcome.Exhausted -> RuntimeSyncResult.Failed(result.cause)
        }
    }

    private fun transportType(): TransportType =
        if (inputs.device.connectionMethods.any { it is ConnectionMethod.WiFi }) TransportType.WIFI else TransportType.BLUETOOTH

    /** Room sessions to re-authenticate after the next usable sync (Swift `handleBLEDisconnection`). */
    override suspend fun remoteDisconnected(): Set<UUID> =
        remoteNodeService.handleBLEDisconnection(radioId).map { it.id }.toSet()

    override suspend fun reauthenticate(sessionIds: Set<UUID>) {
        remoteNodeService.handleBLEReconnection(sessionIds.map { EntityKey(radioId, it) }.toSet())
    }

    /** Cancels the resync/retry loops and resets sync state (disconnect path, Swift `ConnectionManager.disconnect`). */
    override suspend fun resetSyncState() {
        retryController.cancelResyncLoop()
        retryController.cancelChannelRetry()
        syncCoordinator.onDisconnected(SyncNotificationsAdapter(notificationService))
    }

    /** Restarts listeners after a health check found them stopped (idempotent while active). */
    override suspend fun ensureListeners() {
        if (!isEventMonitoringActive && !isClosed) startEventMonitoring(radioId)
    }

    override suspend fun tearDown(): TeardownReport {
        val (receipt, claimed) = synchronized(lock) {
            teardown?.let { it to false } ?: CompletableDeferred<TeardownReport>().also {
                teardown = it
                closed = true
            }.let { it to true }
        }
        if (!claimed) return withContext(NonCancellable) { receipt.await() }
        val issues = mutableListOf<TeardownIssue>()
        suspend fun step(stage: LifecycleStage, work: suspend () -> Unit) {
            try {
                withContext(NonCancellable) { work() }
            } catch (failure: Exception) {
                issues += TeardownIssue(stage, failure)
            }
        }
        withContext(NonCancellable) {
            step(LifecycleStage.STOP_SERVICES) { resetSyncState() }
            // Stop the monitors before clearing handlers so the event tasks that read them are cancelled first.
            step(LifecycleStage.STOP_SERVICES) { stopEventMonitoring() }
            nodeConfigService.setOnPostIdentityImport(null)
            step(LifecycleStage.STOP_SERVICES) { messagePollingService.clearMessageHandlers() }
            step(LifecycleStage.STOP_SERVICES) { syncCoordinator.cancelDiscoveryEventMonitoring() }
            // Finishing every stream ends each consumer's collection, releasing the service references they hold.
            step(LifecycleStage.STOP_SERVICES) { syncCoordinator.finishDataEvents() }
            step(LifecycleStage.STOP_SERVICES) { advertisementService.finishEvents() }
            step(LifecycleStage.STOP_SERVICES) { messageService.finishStatusEvents() }
            step(LifecycleStage.STOP_SERVICES) { heardRepeatsService.finishEvents() }
            step(LifecycleStage.STOP_SERVICES) { remoteNodeService.finishEvents() }
            step(LifecycleStage.STOP_SERVICES) { roomServerService.finishEvents() }
            step(LifecycleStage.STOP_SERVICES) { contactService.finishEvents() }
            step(LifecycleStage.STOP_SERVICES) { rxLogService.finishEntryStream() }
            // The forwarders capture the action handler, which holds the notification service back: clear the cycle.
            notificationService.onQuickReply = null
            notificationService.onChannelQuickReply = null
            notificationService.onMarkAsRead = null
            notificationService.onChannelMarkAsRead = null
            notificationService.onRoomMarkAsRead = null
            step(LifecycleStage.STOP_SERVICES) { chatSendQueueService.shutdown().issues.forEach { issues += it } }
            step(LifecycleStage.STOP_SERVICES) { messageService.close().issues.forEach { issues += it } }
            step(LifecycleStage.STOP_SERVICES) { messagePollingService.close().issues.forEach { issues += it } }
            step(LifecycleStage.STOP_SERVICES) { binaryProtocolService.close() }
            step(LifecycleStage.STOP_SERVICES) { remoteNodeService.close() }
            step(LifecycleStage.STOP_SERVICES) { synchronized(lock) { deviceSettingsValue }?.close() }
            restoreDebugLog()
            step(LifecycleStage.STOP_SERVICES) { containerJob.cancelAndJoin() }
        }
        env.onSessionLifecycle.tornDown(this)
        return TeardownReport(issues.snapshot()).also { receipt.complete(it) }
    }

    private fun restoreDebugLog() {
        if (DebugLogBuffer.shared === debugLogBuffer) DebugLogBuffer.shared = env.bootstrapDebugLog
    }

    // endregion

    companion object {
        /**
         * Builds the graph for one generation. Everything allocated is registered with [ownership] first, so a
         * failure partway through construction (or a teardown racing the factory) still releases the scope.
         */
        fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership, env: SessionEnvironment): RadioSessionContainer {
            val parent = inputs.connection.scope.coroutineContext[Job]
            val job = SupervisorJob(parent)
            val scope = CoroutineScope(inputs.connection.scope.coroutineContext + job)
            ownership.own(LifecycleStage.STOP_SERVICES) { job.cancelAndJoin() }
            val container = RadioSessionContainer(inputs.connection.token, inputs, env, job, scope, ownership)
            ownership.register(container)
            env.onSessionLifecycle.created(container)
            return container
        }
    }
}
