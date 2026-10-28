// PortedFrom: MC1/State/AppState.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppState+Wiring.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppState+NotificationHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.device.SettingsEvent
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.DraftStore
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.ui.StatusPillState
import com.meshcoreone.android.core.ui.UiText
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** View-environment values a background coordinator refresh reuses when no view is in hand. */
data class ChatEnvSnapshot(
    val themeId: String,
    val isDark: Boolean,
    val isHighContrast: Boolean,
    val contentSizeCategory: String,
)

/** Builds the primer for one refresh/prefetch; the timeline and preview warmer come from the chat feature (WP-307). */
fun interface ChatPrimerFactory {
    fun create(dependencies: ChatTimelinePrimer.Dependencies): ChatTimelinePrimer?
}

/** Everything [AppState] is assembled from. Grouped so tests build it with fakes. */
class AppStateDependencies(
    val connection: AppConnectionPort,
    val scope: CoroutineScope,
    /** The live session graph, or null while disconnected (Swift `connectionManager.services`). */
    val session: () -> AppSession?,
    /** Process-lifetime store once a radio has been paired; null so never-paired browsing stays empty. */
    val processStore: () -> PersistenceStoreProtocol?,
    val draftStore: DraftStore,
    val connectionUi: ConnectionUiState,
    val batteryMonitor: BatteryMonitor,
    val resyncFailure: ResyncFailureRegistry,
    val registryFactory: (PersistenceStoreProtocol) -> ChatCoordinatorRegistry,
    val primerFactory: ChatPrimerFactory = ChatPrimerFactory { null },
    val navigation: NavigationCoordinator = NavigationCoordinator(),
    val messageEventStream: MessageEventStream = MessageEventStream(),
    val platform: AppStatePlatform = AppStatePlatform.NONE,
    val regionStore: RegionSelectionStore? = null,
    val stalePreferences: StaleCleanupPreferences? = null,
    val clock: DeadlineClock = SystemRuntimeClock(),
    val now: () -> Instant = Instant::now,
    val chatEnvInputsFactory: (ChatConversationType?, ChatEnvSnapshot) -> EnvInputs = { _, _ -> EnvInputs.DEFAULT },
    val onFreshPairingCompleted: () -> Unit = {},
    /**
     * Closes the selected chat route when its channel slot changes occupant. [NavigationCoordinator] has no seam
     * that clears only the chats route (WP-302 owns it), so the default pops the top chat of the Chats stack.
     */
    val closeChatRoute: () -> Unit = {},
)

/**
 * App-wide connection/UI state: composes the connection layer, owns the per-connection service wiring and the
 * lifecycle reconciliation (foreground, background, app-state events). Swift's `@MainActor @Observable` class;
 * here the mutable members are lock-confined or flows, and every async path runs on the injected scope.
 */
class AppState(private val deps: AppStateDependencies) : MessageEventHost {
    private val logger: Logger = Logger.getLogger("com.mc1.AppState")
    private val scope = deps.scope
    private val connection = deps.connection
    val connectionUI: ConnectionUiState = deps.connectionUi
    val batteryMonitor: BatteryMonitor = deps.batteryMonitor
    val navigation: NavigationCoordinator = deps.navigation
    val draftStore: DraftStore = deps.draftStore
    val messageEventStream: MessageEventStream = deps.messageEventStream
    val messageEventDispatcher = MessageEventDispatcher(this, messageEventStream, scope)

    private val lock = Any()

    // region Observable counters and references

    private val servicesVersionFlow = MutableStateFlow(0)
    private val contactsVersionFlow = MutableStateFlow(0)
    private val conversationsVersionFlow = MutableStateFlow(0)
    private val sessionStateChangeCountFlow = MutableStateFlow(0)
    private val channelSlotGenerationsFlow = MutableStateFlow<Map<ChatConversationID, Int>>(emptyMap())
    private val regionFlow = MutableStateFlow<RegionSelection?>(null)

    /** Incremented when services change (device switch, reconnect); views reload on it. */
    val servicesVersion: StateFlow<Int> = servicesVersionFlow.asStateFlow()
    val contactsVersion: StateFlow<Int> = contactsVersionFlow.asStateFlow()
    val conversationsVersion: StateFlow<Int> = conversationsVersionFlow.asStateFlow()
    val sessionStateChangeCount: StateFlow<Int> = sessionStateChangeCountFlow.asStateFlow()
    val channelSlotGenerations: StateFlow<Map<ChatConversationID, Int>> = channelSlotGenerationsFlow.asStateFlow()
    val regionSelectionFlow: StateFlow<RegionSelection?> = regionFlow.asStateFlow()

    @Volatile var syncCoordinator: SyncCoordinator? = null
        private set
    private var lastBumpedSession: Any? = null
    private var lastConnectedDeviceIdForCli: UUID? = null
    private var chatRegistry: ChatCoordinatorRegistry? = null
    private var prewarmRefresher: ChatPrewarmRefresher? = null
    @Volatile var lastChatEnvSnapshot: ChatEnvSnapshot? = null
        private set

    // endregion

    // region Per-session jobs (cancelled on disconnect and re-wire)

    private var settingsEventsJob: Job? = null
    private var syncDataEventsJob: Job? = null
    private var advertisementEventsJob: Job? = null
    private var rxLogEventsJob: Job? = null
    private var activeRecoveryFallbackJob: Job? = null
    private var bleTransitionTail: Job? = null

    // Every per-session collector is a child of one group job, so the count below also sees a collector that a
    // re-wire replaced but failed to cancel.
    private val sessionGroup = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionGroup)

    /** Test visibility for the per-session collectors (Swift `settingsEventsTask`). */
    val hasSettingsEventsJob: Boolean get() = synchronized(lock) { settingsEventsJob != null }
    val activeSessionJobCount: Int get() = sessionGroup.children.count { it.isActive }

    /** Installs a long-running job in the settings slot (Swift tests assign `settingsEventsTask` directly). */
    fun installSettingsEventsJobForTesting(job: Job) = synchronized(lock) { settingsEventsJob = job }

    // endregion

    private val pairing = PairingFlow(
        connection, connectionUI, scope, deps.clock,
        wireServices = { wireServicesIfConnected() },
        disconnect = { reason -> disconnect(reason) },
        onFreshPairingCompleted = deps.onFreshPairingCompleted,
    )

    val isConfirmingSystemPairingSetup: Boolean get() = pairing.isConfirmingSystemPairingSetup
    val isFreshPairingForegroundRetry: Boolean get() = pairing.isFreshPairingForegroundRetry

    init {
        connectionUI.hasSystemPairingRegistry = connection.hasSystemPairingRegistry
    }

    // region Derived state

    val connectionState: DeviceConnectionState get() = connection.connectionState
    val connectedDevice: DeviceDTO? get() = connection.connectedDevice
    val services: AppSession? get() = deps.session()

    /** Local node name with fallback for display purposes. */
    val localNodeName: String get() = connectedDevice?.nodeName ?: "Me"

    /** Radio id for data access: the connected device's, or the last-connected one for offline browsing. */
    val currentRadioId: RadioId? get() = connectedDevice?.radioId ?: connection.lastConnectedRadioId

    /** Process-lifetime store after a radio has been paired; null until then. */
    val offlineDataStore: PersistenceStoreProtocol?
        get() = if (connection.lastConnectedDeviceId == null) null else deps.processStore()

    /**
     * The current status pill, by priority: failed, syncing, ready, connecting, disconnected, hidden.
     * Truthful by construction: it derives only from the runtime connection state and UI timers.
     */
    val statusPillState: StatusPillState
        get() {
            val ui = connectionUI.state.value
            if (ui.syncFailedPillVisible) return StatusPillState.Failed(UiText.Resource(AppLocalizableStrings.statusPillSyncFailed))
            if (ui.syncActivityCount > 0 || connectionState == DeviceConnectionState.SYNCING) return StatusPillState.Syncing
            if (ui.showReadyToast) return StatusPillState.Ready
            if (connectionState == DeviceConnectionState.CONNECTING) return StatusPillState.Connecting
            if (ui.disconnectedPillVisible) return StatusPillState.Disconnected
            return StatusPillState.Hidden
        }

    /** Whether Settings startup reads should run right now. */
    val canRunSettingsStartupReads: Boolean
        get() = connectionState == DeviceConnectionState.READY ||
            (connectionState == DeviceConnectionState.CONNECTED &&
                connectionUI.currentSyncPhase == com.meshcoreone.android.core.services.sync.SyncPhase.MESSAGES)

    // endregion

    // region Region preference

    var regionSelection: RegionSelection?
        get() = regionFlow.value
        set(value) {
            regionFlow.value = value
            persistRegionSelection(value)
        }

    // One consumer drains the queue so persisted values keep the order they were set in, whatever the scope's dispatcher.
    private val regionWrites = kotlinx.coroutines.channels.Channel<RegionWrite>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private class RegionWrite(val selection: RegionSelection?)

    init {
        val store = deps.regionStore
        if (store != null) {
            scope.launch {
                for (write in regionWrites) {
                    try {
                        store.persist(write.selection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        logger.log(Level.WARNING, "Failed to persist region selection: ${failure.message}")
                    }
                }
            }
        }
    }

    private fun persistRegionSelection(value: RegionSelection?) {
        if (deps.regionStore != null) regionWrites.trySend(RegionWrite(value))
    }

    /** Loads the persisted region without writing it back (Swift `suppressRegionPersist`). */
    suspend fun loadPersistedRegionSelection() {
        val store = deps.regionStore ?: return
        try {
            regionFlow.value = store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Failed to load persisted region selection: ${failure.message}")
        }
    }

    // endregion

    // region Chat coordinator registry and prewarm

    /** Sole lazy factory for the process-lifetime registry; bound to the process store, never rebound on rewire. */
    fun ensureChatCoordinatorRegistry(): ChatCoordinatorRegistry? = synchronized(lock) {
        chatRegistry?.let { return it }
        val store = offlineDataStore ?: return null
        deps.registryFactory(store).also { chatRegistry = it }
    }

    val chatCoordinatorRegistry: ChatCoordinatorRegistry? get() = synchronized(lock) { chatRegistry }

    fun makeChatTimelinePrimerDependencies(): ChatTimelinePrimer.Dependencies = ChatTimelinePrimer.Dependencies(
        registry = ::ensureChatCoordinatorRegistry,
        dataStore = { offlineDataStore },
        reactionService = { services?.reactionService },
        connectedDeviceNodeName = { connectedDevice?.nodeName },
    )

    /** Records the view-environment values the background refresh bakes with and returns the inputs for [conversation]. */
    fun chatEnvInputs(conversation: ChatConversationType?, snapshot: ChatEnvSnapshot): EnvInputs {
        lastChatEnvSnapshot = snapshot
        return deps.chatEnvInputsFactory(conversation, snapshot)
    }

    /**
     * Lazily builds the refresher that re-primes warm coordinators when messages arrive for closed
     * conversations. Every hook resolves through this state at call time, so it stays valid across reconnects.
     */
    fun ensureChatPrewarmRefresher(): ChatPrewarmRefresher = synchronized(lock) {
        prewarmRefresher?.let { return it }
        ChatPrewarmRefresher(
            ChatPrewarmRefresher.Hooks(
                registry = ::ensureChatCoordinatorRegistry,
                dependencies = ::makeChatTimelinePrimerDependencies,
                envInputs = { conversation -> lastChatEnvSnapshot?.let { chatEnvInputs(conversation, it) } },
                isConversationActive = { kind -> isConversationActive(kind) },
                channel = { radioId, index -> offlineDataStore?.let { runCatchingNonCancel { it.fetchChannel(radioId, index) } } },
                contact = { radioId, contactId -> offlineDataStore?.let { store -> runCatchingNonCancel { store.fetchContact(com.meshcoreone.android.core.contracts.domain.EntityKey(radioId, contactId)) } } },
                makePrimer = { dependencies -> deps.primerFactory.create(dependencies) },
            ),
            scope, deps.clock,
        ).also { prewarmRefresher = it }
    }

    private fun isConversationActive(kind: ChatPrewarmRefresher.ConversationKind): Boolean {
        val notifications = services?.notificationService ?: return false
        return when (kind) {
            is ChatPrewarmRefresher.ConversationKind.Dm -> notifications.activeContactID == kind.contact.id
            is ChatPrewarmRefresher.ConversationKind.Channel ->
                notifications.activeChannelIndex == kind.channelIndex && notifications.activeChannelRadioId == kind.radioId
        }
    }

    private suspend inline fun <T> runCatchingNonCancel(block: () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    override fun noteDirectMessageForPrewarm(contact: ContactDTO) = ensureChatPrewarmRefresher().noteDirectMessage(contact)

    override fun noteChannelMessageForPrewarm(radioId: RadioId, channelIndex: UByte) =
        ensureChatPrewarmRefresher().noteChannelMessage(radioId, channelIndex)

    // endregion

    // region Version counters

    /** Bumps the conversations counter and reports the total unread count (Swift Live Activity update). */
    fun refreshConversations() {
        conversationsVersionFlow.update { it + 1 }
        scope.launch {
            val session = services ?: return@launch
            deps.platform.onUnreadCountChanged(totalUnreadCount(session))
        }
    }

    fun bumpContactsVersion() = contactsVersionFlow.update { it + 1 }

    fun bumpServicesVersion() = servicesVersionFlow.update { it + 1 }

    override fun handleSessionStateChange() {
        refreshConversations()
        sessionStateChangeCountFlow.update { it + 1 }
    }

    override suspend fun handleReactionNotification(messageId: UUID) {
        services?.notificationActionHandler?.handleReactionNotification(messageId)
    }

    /** Bumps observer versions after a store-direct backup import so mounted tabs reload. */
    suspend fun notifyDataRestored() {
        contactsVersionFlow.update { it + 1 }
        conversationsVersionFlow.update { it + 1 }
        loadPersistedRegionSelection()
        // Restore is refused while connected, so this cannot race a live session. The registry stays so later opens mint fresh coordinators.
        chatCoordinatorRegistry?.clear()
        bumpServicesVersion()
    }

    /** Drops per-slot drafts, cached coordinators and the selected route when a channel slot changes occupant. */
    fun handleChannelSlotOccupantChanged(radioId: RadioId, indices: Set<UByte>) {
        draftStore.clearChannelDrafts(radioId, indices)
        channelSlotGenerationsFlow.update { current ->
            val next = current.toMutableMap()
            for (index in indices) {
                val id = ChatConversationID.channel(radioId, index)
                chatCoordinatorRegistry?.remove(id)
                next[id] = (next[id] ?: 0) + 1
            }
            next
        }
        val selected = navigation.state.value.chatsSelectedRoute
        if (selected is ChatSelection.Channel && selected.channel.radioId == radioId && selected.channel.index in indices) {
            deps.closeChatRoute()
        }
        refreshConversations()
    }

    // endregion

    // region Observer callbacks from the runtime

    /** `onConnectionReady`/`onConnectionLost`: reconcile the session wiring with the live graph. */
    suspend fun onSessionStateChanged() = wireServicesIfConnected()

    /**
     * Resets the per-session UI state when the graph is gone but this state still believes it is wired. The runtime
     * does not call `onConnectionLost` when a failed connect detaches a live generation (a device switch that fails),
     * so the container also observes the connection state and calls this on every disconnected edge. Idempotent: a
     * state that is already torn down does nothing, so a normal loss is not announced twice.
     */
    suspend fun reconcileSessionLoss() {
        if (syncCoordinator != null && services == null) wireServicesIfConnected()
    }

    fun onLastConnectedDeviceCleared() {
        chatCoordinatorRegistry?.clear()
        refreshConversations()
        bumpServicesVersion()
    }

    suspend fun onAutoReconnectStarted() = deps.platform.onAutoReconnectStarted()

    /** Surfaces the guided re-pair recovery only while active, so a backgrounded failure cannot latch a stale alert. */
    fun handleAuthenticationFailure(deviceId: UUID, isAppActive: Boolean) {
        if (!isAppActive) return
        connectionUI.presentPairingFailure(
            com.meshcoreone.android.core.connectivity.pairing.PairingError.ConnectionFailed(deviceId, AuthenticationFailureCause(), true),
        )
    }

    fun onDeviceSynced() = performStaleNodeCleanup()

    // endregion

    // region Lifecycle

    /** Initialize on app launch. */
    suspend fun initialize() {
        loadPersistedRegionSelection()
        connection.activate()
        connectionUI.updateDisconnectedPillState(
            connectionState, connection.lastConnectedDeviceId, connection.shouldSuppressDisconnectedPill,
        )
    }

    /** Per-session teardown for connection loss and explicit disconnect; the coordinator registry stays. */
    fun tearDownAppStateSessionState() {
        val cancelled = synchronized(lock) {
            listOfNotNull(settingsEventsJob, syncDataEventsJob, advertisementEventsJob, rxLogEventsJob).also {
                settingsEventsJob = null; syncDataEventsJob = null; advertisementEventsJob = null; rxLogEventsJob = null
            }
        }
        cancelled.forEach(Job::cancel)
        messageEventDispatcher.cancelAll()
        navigation.clearPendingLinks()
    }

    /** Wire services-dependent callbacks after a connection (or reset UI state after a loss). */
    suspend fun wireServicesIfConnected() {
        val session = services
        if (session == null) {
            tearDownAppStateSessionState()
            syncCoordinator = null
            connectionUI.handleDisconnect(
                connectionState, connection.lastConnectedDeviceId, connection.shouldSuppressDisconnectedPill,
            )
            batteryMonitor.stop()
            batteryMonitor.clearThresholds()
            deps.platform.onConnectionLost()
            synchronized(lock) { lastBumpedSession = null }
            return
        }

        // The link is up, so drop any pairing-failure alert latched while backgrounded.
        connectionUI.clearPairingFailure()
        // Set before onConnectionEstablished to avoid a race with the first sync-activity event.
        connectionUI.wireCallbacks(session.syncCoordinator, session.advertisementService, session.contactService, deps.resyncFailure)

        // A device switch does not fire onConnectionLost, so reset per-radio UI state here.
        val newDeviceId = connectedDevice?.id
        val oldDeviceId = lastConnectedDeviceIdForCli
        if (newDeviceId != null && oldDeviceId != null && newDeviceId != oldDeviceId) navigation.clearPerRadioSelection()
        lastConnectedDeviceIdForCli = newDeviceId

        syncCoordinator = session.syncCoordinator
        deps.platform.onSessionWired(session, connectedDevice)

        wireSyncDataEvents(session)
        wireSettingsEventStream(session)
        wireDeviceUpdateCallbacks(session)
        messageEventDispatcher.wire(session.subscribeMessageEvents())
        wirePacketCallbacks(session)
        session.channelService.setSlotOccupantChangedHandler { radioId, indices -> handleChannelSlotOccupantChanged(radioId, indices) }

        // Bump only when the container actually changed: connect fires onConnectionReady and then an explicit rewire.
        val changed = synchronized(lock) {
            if (lastBumpedSession !== session) { lastBumpedSession = session; true } else false
        }
        if (changed) {
            servicesVersionFlow.update { it + 1 }
            deps.platform.onSessionChanged()
        }

        session.notificationService.updateBadgeCount()
        configureNotificationHandlers(session)
        batteryMonitor.start(session.batteryServices, connectedDevice)
    }

    private fun wireSyncDataEvents(session: AppSession) {
        val events = session.syncCoordinator.dataEvents()
        replaceJob({ syncDataEventsJob }, { syncDataEventsJob = it }, sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                when (event) {
                    com.meshcoreone.android.core.services.sync.SyncDataEvent.ContactsChanged -> bumpContactsVersion()
                    com.meshcoreone.android.core.services.sync.SyncDataEvent.ConversationsChanged -> refreshConversations()
                    else -> Unit
                }
            }
        })
    }

    private fun wireSettingsEventStream(session: AppSession) {
        val subscription = session.settingsService.events()
        replaceJob({ settingsEventsJob }, { settingsEventsJob = it }, sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                subscription.events.collect { wrapped ->
                    when (val event = wrapped.event) {
                        is SettingsEvent.DeviceUpdated -> connection.updateDevice(event.info, event.appliedRadioPresetID)
                        is SettingsEvent.AutoAddConfigUpdated -> {
                            connection.updateAutoAddConfig(event.config)
                            // Clear the storage-full flag when overwrite-oldest is enabled.
                            if ((event.config.bitmask and AutoAddConfig.OVERWRITE_OLDEST_BIT) != 0.toUByte()) {
                                connectionUI.isNodeStorageFull = false
                            }
                        }
                        is SettingsEvent.ClientRepeatUpdated -> connection.updateClientRepeat(event.enabled)
                        is SettingsEvent.PathHashModeUpdated -> connection.updatePathHashMode(event.mode)
                        is SettingsEvent.AllowedRepeatFreqUpdated -> connection.setAllowedRepeatFreqRanges(event.ranges)
                        is SettingsEvent.DefaultFloodScopeUpdated -> connection.updateDefaultFloodScopeName(event.name)
                    }
                }
            } finally {
                subscription.close()
            }
        })
    }

    private fun wireDeviceUpdateCallbacks(session: AppSession) {
        session.deviceService.setDeviceUpdateCallback { event -> connection.updateDevice(event.event) }

        // ConnectionManager nils the session before teardown yields contactDeletedCleanup; capture the notification service.
        val notifications = session.notificationService
        val events = session.advertisementService.events()
        replaceJob({ advertisementEventsJob }, { advertisementEventsJob = it }, sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                when (event) {
                    AdvertisementEvent.ContactUpdated -> bumpContactsVersion()
                    AdvertisementEvent.ConversationsChanged -> refreshConversations()
                    is AdvertisementEvent.ContactDeletedCleanup -> {
                        notifications.removeDeliveredNotifications(event.contactIDs.toSet())
                        if (event.contactIDs.isNotEmpty()) notifications.updateBadgeCount()
                    }
                    else -> Unit
                }
            }
        })
    }

    /** Every received RF packet refreshes platform freshness and may trigger an overdue battery read. */
    private fun wirePacketCallbacks(session: AppSession) {
        val entries = session.rxLogService.entryStream()
        replaceJob({ rxLogEventsJob }, { rxLogEventsJob = it }, sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            entries.collect {
                deps.platform.onPacketReceived()
                if (deps.platform.hasActiveConnectionActivity()) {
                    batteryMonitor.fetchBatteryIfOverdue(services?.batteryServices, connectedDevice)
                }
            }
        })
        batteryMonitor.onBatteryChanged = { battery -> scope.launch { deps.platform.onBatteryChanged(battery) } }
        val device = connectedDevice
        if (device != null) {
            scope.launch {
                deps.platform.onConnectionReady(device, batteryMonitor.activeBatteryOcvArray(device), totalUnreadCount(session))
            }
        }
    }

    private fun replaceJob(read: () -> Job?, write: (Job?) -> Unit, next: Job) {
        val previous = synchronized(lock) { read().also { write(next) } }
        previous?.cancel()
    }

    /** Configures notification interaction handlers once services are available (installed on every wire, idempotent). */
    private fun configureNotificationHandlers(session: AppSession) {
        deps.platform.configureNotificationNavigation(session) { connectedDevice }
        val handler = session.notificationActionHandler
        handler.configure(
            isConnectionReady = { connectionState == DeviceConnectionState.READY },
            localNodeName = { connectedDevice?.nodeName },
        )
        val notifications = session.notificationService
        notifications.onQuickReply = { contact, text -> handler.handleQuickReply(contact, text) }
        notifications.onChannelQuickReply = { radioId, index, text -> handler.handleChannelQuickReply(radioId, index, text) }
        notifications.onMarkAsRead = { contact, messageId -> handler.handleMarkAsRead(contact, messageId) }
        notifications.onChannelMarkAsRead = { radioId, index, messageId -> handler.handleChannelMarkAsRead(radioId, index, messageId) }
        notifications.onRoomMarkAsRead = { sessionKey, messageId -> handler.handleRoomMarkAsRead(sessionKey, messageId) }
    }

    suspend fun totalUnreadCount(session: AppSession): Int {
        val radioId = currentRadioId ?: return 0
        return try {
            val counts = session.dataStore.getTotalUnreadCounts(radioId)
            (counts.contacts + counts.channels + counts.rooms).toInt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            0
        }
    }

    // endregion

    // region Stale node cleanup

    /** Runs automatic cleanup of stale non-favorite nodes when a threshold is configured; [force] skips the 3 h cooldown. */
    fun performStaleNodeCleanup(force: Boolean = false): Job? {
        val preferences = deps.stalePreferences ?: return null
        return scope.launch {
            val threshold = preferences.thresholdDays()
            if (threshold <= 0) return@launch
            if (!force) {
                val last = preferences.lastRun()
                if (last != null && Duration.between(last, deps.now()) < STALE_CLEANUP_COOLDOWN) {
                    logger.fine("Stale node cleanup skipped: cooldown not expired")
                    return@launch
                }
            }
            try {
                val result = connection.removeStaleNodes(threshold)
                preferences.recordRun(deps.now())
                logger.info("Stale node cleanup: removed ${result.removed} of ${result.total} nodes older than $threshold days")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Stale node cleanup failed: ${failure.message}")
            }
        }
    }

    // endregion

    // region Foreground / background reconciliation

    private enum class BleTransition { ENTER_BACKGROUND, BECOME_ACTIVE }

    /** Test seams: replace the runtime lifecycle calls to assert ordering (Swift DEBUG overrides). */
    @Volatile var bleEnterBackgroundOverride: (suspend () -> Unit)? = null
    @Volatile var bleBecomeActiveOverride: (suspend () -> Unit)? = null

    /**
     * Enqueues a BLE lifecycle transition behind every earlier one so background/foreground hooks stay ordered
     * even when the app flips rapidly. The returned job is never cancelled by app state (cancelling a tail would
     * break the serialization guarantee).
     */
    private fun enqueueBleTransition(transition: BleTransition): Job {
        return synchronized(lock) {
            val prior = bleTransitionTail
            val next = scope.launch(start = CoroutineStart.LAZY) {
                prior?.join()
                when (transition) {
                    BleTransition.ENTER_BACKGROUND ->
                        bleEnterBackgroundOverride?.invoke() ?: connection.appDidEnterBackground()
                    BleTransition.BECOME_ACTIVE ->
                        bleBecomeActiveOverride?.invoke() ?: connection.appDidBecomeActive()
                }
            }
            bleTransitionTail = next
            next.start()
            next
        }
    }

    /** Called when the app enters background. */
    fun handleEnterBackground() {
        synchronized(lock) { activeRecoveryFallbackJob?.cancel(); activeRecoveryFallbackJob = null }
        deps.platform.onEnterBackground()
        // Keep battery polling alive when a live connection surface is visible.
        if (!deps.platform.hasActiveConnectionActivity()) batteryMonitor.stop()
        val session = services
        if (session != null) scope.launch { session.remoteNodeService.stopAllKeepAlives() }
        enqueueBleTransition(BleTransition.ENTER_BACKGROUND)
    }

    /** Called when the app returns to foreground; reconciles transport, sync and battery state in order. */
    suspend fun handleReturnToForeground() {
        yield()
        deps.platform.flushDebugLog()
        services?.notificationService?.updateBadgeCount()

        // Reconcile transport state first so any stale "connected" state is cleaned up via the loss path.
        connection.checkWiFiConnectionHealth()
        enqueueBleTransition(BleTransition.BECOME_ACTIVE).join()

        services?.advertisementService?.handleReturnToForeground()
        deps.platform.onReturnToForeground()

        if (connectionState == DeviceConnectionState.READY) {
            try {
                services?.messageService?.checkExpiredAcks()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "checkExpiredAcks failed: ${failure.message}")
            }
        }

        // Trigger resync if sync failed while connected.
        connection.checkSyncHealth()

        services?.let { session ->
            batteryMonitor.checkMissedBatteryThreshold(connectedDevice, session.batteryServices)
            batteryMonitor.startRefreshLoop(session.batteryServices, connectedDevice)
        }
        deps.platform.resumeOfflineMaps()
    }

    /** Called when the scene becomes active. */
    fun handleBecameActive() {
        // Clear the auth-failure latch so a still-invalid bond re-surfaces from the foreground reconnect.
        connection.clearSurfacedAuthenticationFailure()
        if (connectionUI.shouldCompleteFreshPairingOnForeground) {
            scope.launch { pairing.resumeFreshPairingIfNeeded() }
            return
        }
        scope.launch { pairing.handleBecameActive() }

        val fallback = scope.launch {
            deps.clock.sleep(ACTIVE_RECOVERY_DELAY)
            if (!isActive) return@launch
            if (connection.shouldDeferOpportunisticReconnect || connectionUI.pendingSystemPairingSetup != null ||
                connectionUI.queuedSystemPairingSetup != null
            ) return@launch
            if (connectionState != DeviceConnectionState.DISCONNECTED || connection.lastConnectedDeviceId == null) return@launch
            logger.info("[BLE] Active fallback: disconnected after activation, running foreground reconciliation")
            handleReturnToForeground()
        }
        val previous = synchronized(lock) { activeRecoveryFallbackJob.also { activeRecoveryFallbackJob = fallback } }
        previous?.cancel()
    }

    // endregion

    // region Device actions

    fun startDeviceScan(): Job = pairing.startDeviceScan()
    fun handleDeviceSelectionSheetDismissed() = pairing.handleDeviceSelectionSheetDismissed()
    fun cancelSystemPairingSetup() = pairing.cancelSystemPairingSetup()
    fun handleSystemPairingSetupSheetDismissed() = pairing.handleSystemPairingSetupSheetDismissed()
    fun confirmSystemPairingSetup(): Job? = pairing.confirmSystemPairingSetup()
    suspend fun startDeviceScanAndAwait() = pairing.startDeviceScanAndAwait()
    fun removeFailedPairingAndRetry(): Job? = pairing.removeFailedPairingAndRetry()
    suspend fun retryFailedPairingConnect() = pairing.retryFailedPairingConnect()

    /** Disconnect from the device; explicit disconnect runs the same per-session teardown the loss path performs. */
    suspend fun disconnect(reason: RuntimeDisconnectReason = RuntimeDisconnectReason.USER_INITIATED) {
        connection.disconnect(reason)
        deps.platform.onDisconnectRequested()
        tearDownAppStateSessionState()
    }

    /** Connect via WiFi/TCP. */
    suspend fun connectViaWiFi(host: String, port: UShort, forceFullSync: Boolean = false) {
        connectionUI.hideDisconnectedPill()
        connection.connectViaWiFi(host, port, forceFullSync)
        wireServicesIfConnected()
    }

    // endregion

    /** Cancels every collaborator job; the process owner calls it at shutdown. */
    fun shutdown() {
        tearDownAppStateSessionState()
        prewarmRefresher?.cancelAll()
        batteryMonitor.stop()
        connectionUI.close()
        synchronized(lock) { activeRecoveryFallbackJob?.cancel(); activeRecoveryFallbackJob = null }
    }

    private class AuthenticationFailureCause : Exception("Authentication failed")

    companion object {
        val STALE_CLEANUP_COOLDOWN: Duration = Duration.ofHours(3)
        val ACTIVE_RECOVERY_DELAY = 1.seconds
    }
}
