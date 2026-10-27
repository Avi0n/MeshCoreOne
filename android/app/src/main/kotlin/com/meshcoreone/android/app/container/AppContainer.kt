// PortedFrom: MC1/MC1App.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.state.AccessibilityAnnouncer
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.app.state.AppStateDependencies
import com.meshcoreone.android.app.state.AppStatePlatform
import com.meshcoreone.android.app.state.BatteryMonitor
import com.meshcoreone.android.app.state.ChatPrimerFactory
import com.meshcoreone.android.app.state.ConnectionUiState
import com.meshcoreone.android.app.state.ProcessForegroundState
import com.meshcoreone.android.app.state.RegionSelectionStore
import com.meshcoreone.android.app.state.ResyncFailureRegistry
import com.meshcoreone.android.app.state.StaleCleanupPreferences
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemConnectivityClock
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.bond.BondInspector
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.ContactRemovalPort
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceHostingController
import com.meshcoreone.android.core.connectivity.service.ForegroundServiceStarter
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingIssueReporter
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.runtime.ConnectionObserver
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.ProcessConnectionPreferences
import com.meshcoreone.android.core.runtime.ProcessRuntimeMaintenance
import com.meshcoreone.android.core.runtime.RuntimeClock
import com.meshcoreone.android.core.runtime.RuntimeIssueReporter
import com.meshcoreone.android.core.runtime.RuntimeLinkFactory
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingEvent
import com.meshcoreone.android.core.services.messaging.LoggingMessagingIssueReporter
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.DraftStore
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import com.meshcoreone.android.feature.nodes.deps.Announcer
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.deps.UserFacingMessages
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every process-lifetime collaborator [AppContainer] assembles. Production values come from
 * [AndroidAppContainerFactory]; tests supply recording doubles through the same constructor, so the container logic
 * that is exercised is the production wiring, not a parallel copy.
 */
class AppContainerDependencies(
    val store: PersistenceStoreProtocol,
    val passwords: NodePasswordVault,
    val connectionPreferences: ProcessConnectionPreferences,
    val connectivity: ConnectivityPlatform,
    val linkProbe: SystemLinkProbe,
    val scans: BleScanCoordinator,
    val linkFactory: RuntimeLinkFactory,
    val notificationDelivery: NotificationDeliveryPort,
    val notificationPreferences: NotificationPreferencesPort,
    val contactPreferences: ContactPreferenceFlags,
    val draftStore: DraftStore,
    val chatSendQueueProgress: ChatSendQueueProgress,
    /** The main-thread stand-in app state, UI timers and the chat coordinators run on. */
    val mainScope: CoroutineScope,
    val notificationStrings: NotificationStringProvider? = null,
    val foreground: ProcessForegroundState = ProcessForegroundState(),
    val runtimeClock: RuntimeClock = SystemRuntimeClock(),
    val runtimeContext: CoroutineContext = Dispatchers.Default,
    val sessionConfiguration: SessionConfiguration = SessionConfiguration.DEFAULT,
    val messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    val chatSendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    val knownEndpoints: KnownEndpointStore? = null,
    val bonds: BondInspector? = null,
    val ensureBonded: (suspend (java.util.UUID) -> Unit)? = null,
    val accessibility: AccessibilityAnnouncer = AccessibilityAnnouncer.NONE,
    val platform: AppStatePlatform = AppStatePlatform.NONE,
    val regionStore: RegionSelectionStore? = null,
    val stalePreferences: StaleCleanupPreferences? = null,
    val primerFactory: ChatPrimerFactory = ChatPrimerFactory { null },
    val syncClock: SyncClock = SyncClock.SYSTEM,
    val logSink: SyncLogSink = SyncLogSink.NONE,
    val messagingReporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    val runtimeReporter: RuntimeIssueReporter = RuntimeIssueReporter {},
    val connectivityDiagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    val sessionLifecycle: SessionLifecycleListener = SessionLifecycleListener.NONE,
    /** Starts/stops the single connectedDevice foreground service; null leaves hosting unbound (JVM composition). */
    val hostingStarter: ForegroundServiceStarter? = null,
    val hostEnvironment: HostEnvironment? = null,
    /** Re-reads the system association list (presence `AssociationsChanged`). */
    val refreshAssociations: () -> Unit = {},
    /** Reads the platform permission facts on each foreground edge; null disables revocation handling. */
    val permissionSnapshot: (() -> com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot)? = null,
    /** The companion setup the resumed activity attaches its chooser host to; null when no companion registry exists. */
    val companionSetup: com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService? = null,
    /** Forwards an API 31-32 chooser activity result (request id, result code, data) to the companion gateway. */
    val chooserResult: ((Long, Int, android.content.Intent?) -> Unit)? = null,
    /** Platform pieces for the onboarding flow; null leaves onboarding unbound (the shell shows no fake flow). */
    val onboardingPlatform: com.meshcoreone.android.app.container.onboarding.OnboardingPlatform? = null,
    /** Production pre-advert policy; an unbound composition must not silently skip an enabled refresh. */
    val advertLocation: AppAdvertLocation? = null,
    val offlineMaps: OfflineMapController? = null,
    val nodesMessages: UserFacingMessages = UserFacingMessages { it.message ?: "Operation failed" },
    val nodesAnnouncer: Announcer = Announcer {},
    val nodesPreferences: StringListPreferences = object : StringListPreferences {
        override fun stringList(key: String): List<String>? = null
        override fun setStringList(key: String, value: List<String>) = Unit
    },
    /** Real process/store/RF/elevation adapters for line-of-sight; network terrain remains optional to mesh. */
    val lineOfSight: com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies? = null,
    val themeService: com.meshcoreone.android.core.designsystem.ThemeService? = null,
    val backupEngine: com.meshcoreone.android.feature.settings.app.backup.BackupEngine? = null,
    internal val chatContent: AppChatContent? = null,
    val newBootstrapDebugLog: (CoroutineScope) -> DebugLogBuffer? = { null },
    val runtimeRecoveryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Passive pre-barrier persistence observer; must not block, throw or perform lifecycle work. */
    val onChannelQueryAccounting: ((ChannelQueryAccountingEvent) -> Unit)? = null,
    /** Releases process-owned resources (database, storage) once the runtime has closed. */
    val onClose: suspend () -> Unit = {},
)

/**
 * The process-scoped container (Swift `MC1App`'s long-lived objects): one runtime connection manager, one app
 * state, one process store and the registry through which the per-connection [RadioSessionContainer] is reached.
 * It owns no radio-specific service; those are built by [RadioSessionContainerFactory] each time the runtime
 * connects, and the registry proves each one was torn down.
 */
class AppContainer(private val dependencies: AppContainerDependencies) {
    private val processJob = SupervisorJob()
    private val processScope = CoroutineScope(dependencies.runtimeContext + processJob)
    private val resyncFailureHub = ResyncFailureHub()
    private val observerBridge = ObserverBridge(dependencies.foreground, dependencies.mainScope.coroutineContext)

    val sessions = SessionRegistry()
    val navigation = NavigationCoordinator()
    internal val currentTransport: com.meshcoreone.android.core.model.TransportType?
        get() = connectionManager.snapshot.value.token?.let {
            (dependencies.linkFactory as? AndroidRuntimeLinkFactory)?.transportFor(it)
        }
    internal val processStore get() = dependencies.store
    internal val uiScope = CoroutineScope(dependencies.mainScope.coroutineContext + SupervisorJob(processJob))
    val foreground: ProcessForegroundState = dependencies.foreground
    val bootstrapDebugLog: DebugLogBuffer? = dependencies.newBootstrapDebugLog(processScope)
        // A live process publishes its bootstrap buffer as the process-global one; the inert placeholder never does.
        ?.also { DebugLogBuffer.shared = it }

    private val lastConnection = LastConnectionStore(dependencies.connectionPreferences, dependencies.runtimeClock)
    private val platformAdapter = RuntimePlatformAdapter(dependencies.connectivity)
    private val runtimeState = ManagerRuntimeSyncState({ connectionManager }, processScope)

    private val environment = SessionEnvironment(
        store = dependencies.store,
        processScope = processScope,
        notificationDelivery = dependencies.notificationDelivery,
        notificationPreferences = dependencies.notificationPreferences,
        notificationStrings = dependencies.notificationStrings,
        appState = dependencies.foreground,
        passwords = dependencies.passwords,
        contactPreferences = dependencies.contactPreferences,
        chatSendQueueProgress = dependencies.chatSendQueueProgress,
        runtimeState = runtimeState,
        resyncFailure = resyncFailureHub,
        syncClock = dependencies.syncClock,
        logSink = dependencies.logSink,
        messagingReporter = dependencies.messagingReporter,
        onChannelQueryAccounting = dependencies.onChannelQueryAccounting,
        messageServiceConfig = dependencies.messageServiceConfig,
        chatSendQueueConfig = dependencies.chatSendQueueConfig,
        onSessionLifecycle = dependencies.sessionLifecycle,
        bootstrapDebugLog = bootstrapDebugLog,
    )

    val connectionManager: ConnectionManager = ConnectionManager(
        devices = dependencies.store,
        rooms = dependencies.store,
        contacts = dependencies.store,
        maintenance = object : ProcessRuntimeMaintenance {
            override suspend fun warmUp() = dependencies.store.warmUp()
            // The source applies no per-device defaults at connect; per-device preferences are read lazily.
            override suspend fun initializeDevicePreferences(device: com.meshcoreone.android.core.model.DeviceDTO) = Unit
        },
        lastConnection = lastConnection,
        platform = platformAdapter,
        linkFactory = dependencies.linkFactory,
        serviceFactory = RadioSessionContainerFactory(environment, sessions),
        observer = observerBridge.observer,
        reporter = dependencies.runtimeReporter,
        clock = dependencies.runtimeClock,
        context = dependencies.runtimeContext,
        configuration = dependencies.sessionConfiguration,
        recoveryDispatcher = dependencies.runtimeRecoveryDispatcher,
    )

    val pairing: PairingCoordinator = PairingCoordinator(
        RuntimePairingPort(connectionManager, platformAdapter), dependencies.connectivity.pairing, dependencies.store,
        dependencies.linkProbe, dependencies.scans::stopBleScanning, SystemConnectivityClock(), processScope,
        dependencies.connectivityDiagnostics, dependencies.ensureBonded, dependencies.knownEndpoints, dependencies.bonds,
    )

    internal val deviceAccess = ManagerDeviceAccess(connectionManager)
    internal val deviceEditor = ConnectedDeviceEditor(
        deviceAccess, dependencies.store, dependencies.store,
        { sessions.current?.let { ContactRemoval(it.token.radioId, it) } },
        RadioPresetMatcher { frequency, bandwidth, spreadingFactor, codingRate ->
            RadioPresets.matchingPresets(frequency, bandwidth, spreadingFactor, codingRate).map { it.id }.toSet()
        },
        processScope, { Instant.now() }, dependencies.connectivityDiagnostics,
    )

    val connectionPort = ConnectionManagerAppPort(
        connectionManager, platformAdapter, pairing, lastConnection, deviceEditor, dependencies.scans::stopBleScanning,
    )

    val appState: AppState = AppState(
        AppStateDependencies(
            connection = connectionPort,
            scope = dependencies.mainScope,
            session = { sessions.current },
            processStore = { dependencies.store },
            draftStore = dependencies.draftStore,
            connectionUi = ConnectionUiState(dependencies.mainScope, dependencies.runtimeClock, dependencies.accessibility),
            batteryMonitor = BatteryMonitor(dependencies.mainScope, dependencies.runtimeClock),
            resyncFailure = ResyncFailureRegistry { handler -> resyncFailureHub.handler = handler },
            registryFactory = { store ->
                ChatCoordinatorRegistry(store, dependencies.mainScope)
            },
            primerFactory = dependencies.primerFactory,
            navigation = navigation,
            platform = ContainerAppStatePlatform(dependencies.platform),
            regionStore = dependencies.regionStore,
            stalePreferences = dependencies.stalePreferences,
            clock = dependencies.runtimeClock,
            closeChatRoute = { closeSelectedChatRoute() },
        ),
    )

    /** Process-scoped map data binding; the feature owns rendering and never reaches into the service graph. */
    val mapFeature by lazy(::createMapFeatureDependencies)
    val nodesFeature by lazy(::createNodesFeatureDependencies)
    val remoteNodesFeature by lazy(::createRemoteNodesFeatureDependencies)
    val chatsFeature by lazy { AppChatsFeature(this) }
    val themeService get() = dependencies.themeService
    val backupEngine get() = dependencies.backupEngine
    internal val chatContent get() = dependencies.chatContent
    internal val notificationPreferences get() = dependencies.notificationPreferences
    internal val settingsRegionResolver get() = dependencies.onboardingPlatform?.regionResolver
    internal val advertLocation get() = dependencies.advertLocation
    var settingsFeature: AppSettingsFeature? = null
        internal set
    internal val nodesMessages get() = dependencies.nodesMessages
    internal val nodesAnnouncer get() = dependencies.nodesAnnouncer
    internal val nodesPreferences get() = dependencies.nodesPreferences
    val lineOfSight = dependencies.lineOfSight
    internal val offlineMaps: OfflineMapController
        get() = checkNotNull(dependencies.offlineMaps) { "Production map dependencies were not installed" }

    /**
     * Foreground-service hosting and companion-presence routing, present only when the platform supplies a service
     * starter and environment. Holds the service while a live connection is wanted and the app is visible.
     */
    val hostBinding: ConnectedDeviceHostBinding? = dependencies.hostingStarter?.let { starter ->
        dependencies.hostEnvironment?.let { environment ->
            ConnectedDeviceHostBinding(
                connectionManager.snapshot, foreground.foregroundFlow, { connectionManager.snapshot.value },
                ConnectedDeviceHostingController(starter), environment, PresenceRouting(), processScope,
                onFailure = { failure -> dependencies.connectivityDiagnostics.report("hosting", failure) },
            )
        }
    }

    /** Onboarding ports bound to this container's services; null without an [AppContainerDependencies.onboardingPlatform]. */
    val onboarding: com.meshcoreone.android.app.container.onboarding.AppOnboarding? by lazy {
        dependencies.onboardingPlatform?.let {
            com.meshcoreone.android.app.container.onboarding.AppOnboardingFactory.create(this, it, dependencies.mainScope)
        }
    }

    /** The companion setup service (for `setChooserHost` and the chooser result), when a registry exists. */
    val companionSetup = dependencies.companionSetup

    /** Forwards a chooser activity result to the platform gateway; a no-op without a companion registry. */
    fun onChooserResult(requestId: Long, resultCode: Int, data: android.content.Intent?) {
        dependencies.chooserResult?.invoke(requestId, resultCode, data)
    }

    private val revocationGuard: PermissionRevocationGuard? = dependencies.permissionSnapshot?.let { read ->
        PermissionRevocationGuard(
            read,
            object : TransportTeardown {
                override val liveTransport: LiveTransport
                    get() {
                        val methods = connectionManager.connectedDevice?.connectionMethods ?: return LiveTransport.NONE
                        if (!connectionManager.connectionState.isConnected) return LiveTransport.NONE
                        return if (methods.isNotEmpty() && methods.all { it is com.meshcoreone.android.core.model.ConnectionMethod.WiFi }) {
                            LiveTransport.LAN
                        } else LiveTransport.BLUETOOTH
                    }

                override suspend fun disconnectTransport() = connectionManager.disconnectTransport()
            },
        ) { revoked -> dependencies.connectivityDiagnostics.report("permissions.revoked.$revoked", null) }
    }

    init {
        observerBridge.bind(appState, connectionPort)
    }

    /** Starts the process: binds hosting, loads persisted state and activates the runtime (restores the last connection). */
    fun start(): Job {
        hostBinding?.start()
        watchDisconnects()
        watchPermissions()
        return dependencies.mainScope.launch { appState.initialize() }
    }

    /** Reconciles app state on every disconnected edge, covering failures the runtime reports no loss callback for. */
    private fun watchDisconnects() {
        dependencies.mainScope.launch {
            connectionManager.snapshot.map { it.state }.distinctUntilChanged()
                .filter { it == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED }
                .collect { appState.reconcileSessionLoss() }
        }
    }

    /** Checks permission revocation on each foreground edge (the first read becomes the baseline). */
    private fun watchPermissions() {
        val guard = revocationGuard ?: return
        dependencies.mainScope.launch {
            foreground.foregroundFlow.filter { it }.collect {
                try {
                    guard.check()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    dependencies.connectivityDiagnostics.report("permissions.check", failure)
                }
            }
        }
    }

    private inner class PresenceRouting : PresenceEffects {
        override suspend fun reconnect(deviceId: java.util.UUID) = connectionPort.connect(deviceId, forceReconnect = false)
        override fun refreshAssociations() = dependencies.refreshAssociations()
        override suspend fun forgetAssociation(deviceId: java.util.UUID) =
            pairing.devicePairingDidRemoveDevice(dependencies.connectivity.pairing, deviceId)
        override fun permissionRevoked(capability: Capability) =
            dependencies.connectivityDiagnostics.report("presence.permissionRevoked.$capability", null)
        override fun deferredUntilUnlock(deviceId: java.util.UUID) =
            dependencies.connectivityDiagnostics.report("presence.deferredUntilUnlock", null)
    }

    /**
     * `NavigationCoordinator` has no seam that clears only the chats route, so this pops the top chat of the Chats
     * stack when it is the selected one (see WP-303.md coordinator note).
     */
    private fun closeSelectedChatRoute() {
        val state = navigation.state.value
        val top = state.stacks[AppTab.CHATS]?.lastOrNull()?.destination
        if (state.selectedTab == AppTab.CHATS && top is NavigationDestination.Chat &&
            top.selection == state.chatsSelectedRoute && state.chatsSelectedRoute is ChatSelection.Channel
        ) navigation.back()
    }

    /** Process shutdown: closes the runtime (tearing down any live graph), then every process resource. */
    suspend fun close() {
        withContext(NonCancellable) {
            hostBinding?.close()
            appState.shutdown()
            connectionManager.close()
            processJob.cancelAndJoin()
            dependencies.onClose()
        }
    }

    private class ContactRemoval(private val radioId: RadioId, private val session: com.meshcoreone.android.app.state.AppSession) :
        ContactRemovalPort {
        override suspend fun removeContact(radioId: RadioId, publicKey: Bytes) = session.contactService.removeContact(radioId, publicKey)
        override suspend fun removeLocalContact(contactId: java.util.UUID, publicKey: Bytes) =
            session.contactService.removeLocalContact(EntityKey(radioId, contactId), publicKey)
    }
}

/** Holds the resync-failure hook ConnectionUiState installs, and fires it from the session's retry loop. */
internal class ResyncFailureHub : ResyncFailureSignal {
    @Volatile var handler: (() -> Unit)? = null
    override fun resyncFailed() { handler?.invoke() }
}

/** Adds the process-global debug-log flush to the platform hooks app state calls. */
private class ContainerAppStatePlatform(private val delegate: AppStatePlatform) : AppStatePlatform by delegate {
    override suspend fun flushDebugLog() {
        DebugLogBuffer.shared?.flush()
        delegate.flushDebugLog()
    }
}

/**
 * Routes the runtime's observer callbacks to app state on the main stand-in. The bridge exists before app state
 * (the runtime needs its observer at construction), so [bind] installs the targets afterwards; no callback can
 * fire before the first connect, which only starts after the container is built.
 */
internal class ObserverBridge(private val foreground: ProcessForegroundState, private val mainContext: CoroutineContext) {
    @Volatile private var appState: AppState? = null
    @Volatile private var port: ConnectionManagerAppPort? = null
    private val scope = CoroutineScope(mainContext + SupervisorJob())

    fun bind(appState: AppState, port: ConnectionManagerAppPort) {
        this.appState = appState
        this.port = port
    }

    private suspend fun onMain(block: suspend (AppState, ConnectionManagerAppPort) -> Unit) {
        val state = appState ?: return
        val connection = port ?: return
        withContext(mainContext) { block(state, connection) }
    }

    val observer = ConnectionObserver(
        onServicesAvailable = { onMain { state, connection -> connection.refreshLastConnection(); state.onSessionStateChanged() } },
        onConnectionLost = { onMain { state, connection -> connection.refreshLastConnection(); state.onSessionStateChanged() } },
        onAutoReconnectStarted = { onMain { state, _ -> state.onAutoReconnectStarted() } },
        onDeviceSynced = { onMain { state, _ -> state.onDeviceSynced() } },
        onAuthenticationFailure = { id -> scope.launch { appState?.handleAuthenticationFailure(id, foreground.isForeground) } },
        onLastDeviceCleared = { scope.launch { port?.refreshLastConnection(); appState?.onLastConnectedDeviceCleared() } },
    )
}
