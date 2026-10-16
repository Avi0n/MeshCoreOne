package one.meshcore.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import one.meshcore.shared.app.AppStateEdges
import one.meshcore.shared.app.AppStateRuntime
import one.meshcore.shared.app.AppStateStores
import one.meshcore.shared.app.AppStateViewModel
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.chat.ActiveConversationSink
import one.meshcore.shared.chat.ChannelSlotGenerationTracker
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.MessageStatusEvent
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.createLinkPreviewCache
import one.meshcore.shared.chat.linkpreview.defaultMalwareDomainFilter
import one.meshcore.shared.chat.liveMessageStatusEvents
import one.meshcore.shared.connection.CompanionDeviceRegistry
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.DevicePlatform
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.NoOpCompanionDeviceRegistry
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.PrefsStaleNodeCleanupPreferences
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.contact.markHeard
import one.meshcore.shared.map.AndroidGeocoder
import one.meshcore.shared.map.AndroidLocationService
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.KtorGeoHttpClient
import one.meshcore.shared.map.MapLibreMapSnapshotRenderer
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.map.RegionResolver
import one.meshcore.shared.map.RegionalAreas
import one.meshcore.shared.map.loadFromPrefs
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.NotificationConversationOpen
import one.meshcore.shared.navigation.PendingConversationOpen
import one.meshcore.shared.navigation.PendingExternalUrl
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationActiveConversationSink
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.NotificationCoordinatorEdges
import one.meshcore.shared.notification.StoredNotificationPreferences
import one.meshcore.shared.notification.UnreadCounts
import one.meshcore.shared.notification.clearActiveConversation
import one.meshcore.shared.notification.setActiveRoom
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomDebugLogStore
import one.meshcore.shared.persistence.RoomDiscoveredNodeStore
import one.meshcore.shared.persistence.RoomRxLogStore
import one.meshcore.shared.persistence.RoomSavedTracePathStore
import one.meshcore.shared.persistence.RoomUnreadCountsStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.DataStorePreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.remotenode.KeystoreNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.room.RoomNotificationSink
import one.meshcore.shared.room.RoomServerEvent
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.settings.DebugLogBuffer
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.OfflineDataAccess
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.ScreenshotChatFixtures
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.tools.ServiceToolsCliRemoteBridge
import one.meshcore.shared.tools.ToolsCliLocalBridge
import one.meshcore.shared.tools.ToolsCliRemoteBridge
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.tools.configure
import one.meshcore.shared.tools.newNoiseFloorReadingId
import one.meshcore.shared.trace.TracePathWaiter
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.transport.KableBleScanController
import one.meshcore.shared.transport.bluetoothAdapterEnabled
import one.meshcore.shared.ui.BleScanController
import one.meshcore.shared.ui.ChatThreadBinder
import one.meshcore.shared.ui.ConnectionActions
import one.meshcore.shared.ui.ConnectionSavedDeviceActions
import one.meshcore.shared.ui.ConnectionSessionActions
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The process-scoped object graph, built once by [MeshCoreApplication] and referenced by both the
 * Activity (which binds its UI to it) and the [ConnectionService] (which keeps the process resident while
 * connected). It is the single source of truth for the live connection: the [AppStateViewModel], the
 * [ConnectionUiState] sub-flag machine, the navigation coordinator, and the onboarding state.
 *
 * Transport factories provide Kable BLE, a TCP/WiFi link over the shared `JvmTcpSocket`, and the
 * JNI-backed `MeshCoreSession`. They are reached only on an actual connect, so a fresh install's
 * lifecycle path still runs without a radio.
 */
class AppContainer private constructor(
    context: Context,
    sessionActions: AppConnectionSessionActions,
    savedDeviceActions: AppConnectionSavedDeviceActions,
) : ConnectionActions,
    ConnectionSessionActions by sessionActions,
    ConnectionSavedDeviceActions by savedDeviceActions {
    internal val appContext = context.applicationContext.also {
        sessionActions.host = this@AppContainer
        savedDeviceActions.host = this@AppContainer
    }
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val ioDispatcher = Dispatchers.IO

    internal val prefs = DataStorePreferencesStore.atPath(
        appContext.filesDir.resolve("meshcore.preferences_pb").absolutePath,
    )

    /** The app-lifetime key-value store, exposed for the shell path editor's recent-hops persistence. */
    val pathEditPreferences: PreferencesStore get() = prefs

    internal val recentEmojisStore = one.meshcore.shared.chat.reactions.RecentEmojisStore(prefs)
    internal val frequentEmojisStore = one.meshcore.shared.chat.reactions.RecentEmojisStore(
        prefs,
        maxRecent = one.meshcore.shared.chat.reactions.RecentEmojisStore.MAX_FREQUENT,
        prefsKey = one.meshcore.shared.chat.reactions.RecentEmojisStore.FREQUENT_PREFS_KEY,
        emptyFallback = emptyList(),
    )

    /** Live chat display toggles (Settings → Chats); seeds from prefs on construction. */
    val chatDisplayPreferences = one.meshcore.shared.settings.ChatDisplayPreferencesStore(prefs, appScope).also {
        it.load()
    }

    val translationTargetPreferences =
        one.meshcore.shared.settings.TranslationTargetPreferencesStore(prefs, appScope).also { it.load() }

    /** Process-lifetime chat map thumbnail resolution cache (shared across threads). */
    val mapSnapshotStore = MapSnapshotStore(
        renderer = MapLibreMapSnapshotRenderer(appContext),
        scope = appScope,
    )

    /** Process-lifetime inline-image aspect ratios (shared across threads). */
    val inlineImageDimensions = one.meshcore.shared.chat.InlineImageDimensionsStore(
        one.meshcore.shared.chat.FileInlineImageDimensionsPersister(
            java.io.File(appContext.filesDir, "InlineImageDimensions.json"),
        ),
    )

    internal val staleCleanupPrefs = PrefsStaleNodeCleanupPreferences(prefs)
    internal val staleCleanupSettings = StaleCleanupSettingsState(staleCleanupPrefs, appScope)
    internal val staleCleanupThresholdChanges = SupersedingTaskLauncher(appScope)
    val staleCleanupThresholdDays: () -> Int = { staleCleanupSettings.thresholdDays }
    val lastStaleCleanupSeconds: () -> Long = { staleCleanupSettings.lastRunSeconds }

    /** Live map-offline flag. iOS `OfflineMapService.isNetworkAvailable` from `NWPathMonitor`. */
    val networkAvailability = NetworkAvailabilityMonitor(appContext).also { it.start() }

    /** True when there is no usable network route (map offline badge). */
    val isNetworkOffline: () -> Boolean = { networkAvailability.offline.value }

    /** Tools CLI remote login/passthrough over the live connection services. */
    val toolsCliRemote: () -> ToolsCliRemoteBridge? = toolsCliRemote@{
        val services = connectionManager.services ?: return@toolsCliRemote null
        ServiceToolsCliRemoteBridge(
            radioIdProvider = { connectionManager.radioId },
            remoteNodeService = services.remoteNodeService,
            nodeCliCoordinator = services.nodeCliCoordinator,
            passwordStore = nodePasswordStore,
        )
    }

    /** Tools CLI local radio commands (clock/ver/board/advert/get/set/…) over the live session. */
    val toolsCliLocal: () -> ToolsCliLocalBridge? = buildToolsCliLocal()

    /** Appearance (light/dark/system + selected theme id). */
    val appearancePreferences = one.meshcore.shared.theme.AppearancePreferencesStore(prefs, appScope).also {
        it.load()
    }

    /** Map display toggles (discovered pins + basemap appearance) for Settings → Maps and the Map tab. */
    val mapDisplayPreferences = one.meshcore.shared.map.MapDisplayPreferencesStore(prefs, appScope).also {
        it.load()
    }

    /** What's New post-upgrade sheet baseline. */
    val whatsNewStore = one.meshcore.shared.whatsnew.WhatsNewStore(prefs, appScope)

    /** One-time coach marks (device menu tip, etc.). */
    val coachMarkStore = one.meshcore.shared.ui.CoachMarkStore(prefs)

    /**
     * Owned-entitlement flags. Fed by the entitlement reconciler from Play purchases (grant/revoke on
     * reconcile) plus local demo-mode grants; read by Appearance theme gating and the Support screen.
     */
    val entitlementStore = one.meshcore.shared.billing.EntitlementStore()

    /**
     * Foreground activity for Play Billing sheets. [MainActivity] sets this while visible.
     * Null outside the activity lifecycle — purchase launch then fails closed.
     */
    @Volatile
    var billingActivity: android.app.Activity? = null

    /**
     * Companion Device Manager registry. [MainActivity] replaces the no-op with the
     * activity-bound implementation so forget/delete can drop the OS association and
     * Settings unpair can demote the Device row.
     */
    @Volatile
    var companionDevices: CompanionDeviceRegistry = NoOpCompanionDeviceRegistry
        set(value) {
            field = value
            watchCompanionAssociationRemovals()
        }

    internal var companionRemovalJob: Job? = null

    val backupWorkspace = one.meshcore.shared.settings.ui.BackupSettingsWorkspace(appScope)
    val backupPickWaiter = DocumentPickWaiter<android.net.Uri>()
    val backupCreateWaiter = DocumentPickWaiter<android.net.Uri>()
    val nodeConfigImportWorkspace = one.meshcore.shared.settings.ui.NodeConfigImportWorkspace(appScope) {
        coordinator.selectedTab.value == one.meshcore.shared.navigation.AppTab.SETTINGS.index &&
            coordinator.selectedSetting.value == one.meshcore.shared.navigation.SettingsDestination.CONFIG_IMPORT
    }
    internal val nodeConfigPickWaiter = NodeConfigDocumentPickWaiter<android.net.Uri>()

    @Volatile
    var leftoverAvatarBytes: ByteArray? = null

    /**
     * Sideload builds do not talk to Play. A later Play build turns billing on and constructs
     * PlayBillingClient, which needs [billingActivity] for launchPurchase.
     */
    val billingClient: one.meshcore.shared.billing.BillingClient =
        if (one.meshcore.shared.billing.SideloadDistribution.BILLING_ENABLED) {
            one.meshcore.shared.billing.PlayBillingClient(appContext, ioDispatcher) { billingActivity }
        } else {
            one.meshcore.shared.billing.NoOpBillingClient()
        }

    /**
     * Raises the system location permission prompt. Only an activity can ask, so this stays a no-op
     * until one attaches; a user who declined during onboarding can then still grant it from the Map
     * tab rather than being sent to system Settings.
     */
    @Volatile
    var requestLocationPermission: () -> Unit = {}

    /**
     * The single copy-to-clipboard seam. [MainActivity] wires this to the activity-scoped
     * `AndroidSystemBridges.copyToClipboard`, which fires the success haptic (iOS `sensoryFeedback(.success)`
     * on copy). The app-scoped default keeps copy working before the activity attaches, minus the haptic.
     */
    @Volatile
    var systemCopyToClipboard: (String) -> Unit = { text ->
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(AndroidSystemBridges.CLIP_LABEL, text))
    }

    /**
     * System Translation overlay when in-bubble ML Kit fails. [MainActivity] wires this to
     * [AndroidSystemBridges.presentSystemTranslation]; the default is a no-op until an activity attaches.
     */
    @Volatile
    var systemPresentSystemTranslation: (String) -> Unit = {}

    /**
     * The single grant/acknowledge/consume/revoke lifecycle over [billingClient] and
     * [entitlementStore]. Injected into the Support screen so every reconcile/apply serializes
     * through one instance's lock.
     */
    val entitlementReconciler =
        one.meshcore.shared.billing.EntitlementReconciler(entitlementStore, billingClient)
    internal val entitlementReconciliation = SupersedingTaskLauncher(appScope)

    init {
        // Fulfil out-of-band deliveries (Ask-to-Buy approvals) without a full reconcile.
        appScope.launch {
            billingClient.purchaseUpdates.collect { purchases -> entitlementReconciler.apply(purchases) }
        }
        reconcileEntitlements()
    }

    /** Demo mode for Support settings / fixtures. Display flags only; it grants no entitlement. */
    val demoModeStore = one.meshcore.shared.settings.DemoModeStore(prefs, appScope).also {
        it.load()
        whatsNewStore.evaluateStoredInstallState()
    }

    /** Offline map pack metadata + MapLibre OfflineManager downloads. */
    val offlineMapPackRegistry = one.meshcore.shared.map.OfflineMapPackRegistry().also { registry ->
        appScope.launch {
            registry.loadFromPrefs(prefs)
        }
    }
    internal val offlineMapPackPersistence = SupersedingTaskLauncher(appScope)
    internal val offlineMapErrorReporting = SupersedingTaskLauncher(appScope)

    internal val offlineMapTileDownloader: one.meshcore.shared.map.OfflineMapTileDownloader =
        one.meshcore.shared.map.MapLibreOfflineMapTileDownloader(appContext)

    internal val offlineMapErrorState = MutableStateFlow<String?>(null)

    /**
     * The last offline-pack download failure, or null. iOS surfaces the same value in an alert
     * (`OfflineMapSettingsView.swift:44-50`); discarding it leaves a failed pack sitting at
     * "Downloading" with a frozen bar, so someone preparing for an outage cannot tell it never landed.
     */
    val offlineMapError: StateFlow<String?> = offlineMapErrorState.asStateFlow()

    /** Live session identity import for Settings → Device Info. Null when disconnected. */
    val importPrivateKey: (suspend (ByteArray) -> Unit)? = buildImportPrivateKey()

    /** Zero-hop contact ping over the live session (sendTrace + TraceData), at the radio's hash mode. */
    val zeroHopPing: suspend (ContactDto) -> ContactPingResult = zeroHopPing@{ contact ->
        val session = connectionManager.services?.session
            ?: return@zeroHopPing ContactPingResult.NoResponse
        contactZeroHopPing(
            contact = contact,
            session = session,
            pathHashMode = connectedPathHashMode.traceHashMode(),
            nowMs = { System.currentTimeMillis() },
            onHeard = {
                connectionManager.services?.contactService?.markHeard(contact.radioID, contact.publicKey)
            },
        )
    }

    /** Live telemetry for chat contact detail (NodeTelemetryView parity). */
    val requestTelemetry: suspend (
        ByteArray,
    ) -> one.meshcore.shared.meshcore.TelemetryResponse = buildRequestTelemetry()

    /** Tools → Trace Path over the live session (sendTrace + TraceData). */
    private val tracePathWaiter = TracePathWaiter(
        TracePathWaiter.Dependencies(
            events = { connectionManager.services?.session?.events },
            radioId = { currentRadioId() },
        ),
    )
    val runTracePath: suspend (ByteArray, Int, Int) -> TraceResult = buildRunTracePath(tracePathWaiter)

    /**
     * Tools → Noise Floor sample via `getStatsRadio`. Only a missing session reads as null; a radio that
     * will not answer must throw, because the poller tells the operator "no radio" and "cannot read it"
     * apart and a swallowed failure reports a live-but-mute radio as disconnected.
     */
    val fetchNoiseFloorReading: suspend () -> NoiseFloorReading? = fetchNoise@{
        val session = connectionManager.services?.session as? MeshCoreSession ?: return@fetchNoise null
        val stats = session.getStatsRadio()
        NoiseFloorReading(
            id = newNoiseFloorReadingId(),
            timestampEpochMs = System.currentTimeMillis(),
            noiseFloor = stats.noiseFloor,
            lastRssi = stats.lastRSSI,
            lastSnr = stats.lastSNR,
        )
    }

    /**
     * Tools → Node Discovery active scan. Returns the correlation tag for matching discover responses.
     * A session retired during name preload cancels the send.
     */
    val sendNodeDiscover: suspend (Int, Boolean) -> Long = sendDiscover@{ filter, prefixOnly ->
        val session = connectionManager.services?.session
            ?: throw kotlinx.coroutines.CancellationException()
        session.sendNodeDiscoverRequest(filter = filter, prefixOnly = prefixOnly)
    }

    /** Live mesh events for active discovery response correlation. */
    val meshEvents: () -> kotlinx.coroutines.flow.Flow<one.meshcore.shared.meshcore.MeshEvent>? = {
        connectionManager.services?.session?.events
    }

    /** Tools → Line of Sight terrain elevation (Open-Meteo). */
    val elevationService: ElevationService = ElevationService(KtorGeoHttpClient())

    internal val database = meshDatabase(appContext, ioDispatcher)

    /**
     * Shared RX-log decrypt/region cache. Declared before the store so hydrate can re-derive
     * persisted rows from secrets already loaded from Room.
     */
    internal val rxLogDecodeCache = RxLogDecodeCache()

    /**
     * Tools → RX Log, Room-backed so the list survives process death and reloads on first use. It is
     * declared after [database] because it reads from it. [ServiceContainer.start] appends from
     * MeshEvent.RxLogData.
     */
    internal val rxLogStoreImpl: RxLogStore = RoomRxLogStore.fromDatabase(
        RoomRxLogStore.Args(
            database = database,
            scope = appScope,
            decodeSnapshot = { rxLogDecodeCache.snapshot() },
            logger = PersistentLogger(appScope, category = "RxLogService"),
        ),
    )
    val rxLogStore: RxLogStore get() = rxLogStoreImpl

    /**
     * Received-packet rate (per minute) for the foreground-service notification, the Live Activity
     * RX-freshness readout. Recomputed on each RX-log append and on a decay tick so the shade drops the
     * rate toward zero when packets stop (iOS `LiveActivityManager` packet window + decay timer).
     */
    val packetsPerMinute: StateFlow<Int> = combine(
        rxLogStoreImpl.snapshot,
        flow {
            while (true) {
                emit(Unit)
                delay(PACKET_RATE_TICK_MS)
            }
        },
    ) { entries, _ ->
        one.meshcore.shared.tools.PacketRate.packetsPerMinute(
            timestampsMs = entries.map { it.receivedAtEpochMs },
            nowMs = System.currentTimeMillis(),
        )
    }.stateIn(appScope, SharingStarted.Eagerly, 0)

    /**
     * Discover list, Room-backed so passively-discovered nodes survive an app restart and export in a
     * backup (iOS `DiscoveredNode`). [one.meshcore.shared.contact.DiscoveredNodeEventCollector] upserts
     * from NewContact/Advertisement when [ServiceContainer.start] runs.
     * [RoomDiscoveredNodeStore.refresh] primes the published snapshot from persisted rows at startup.
     */
    internal val discoveredNodeStoreImpl: RoomDiscoveredNodeStore =
        RoomDiscoveredNodeStore(database.discoveredNodeDao()).also { store ->
            appScope.launch { store.refresh() }
        }
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { discoveredNodeStoreImpl }

    /**
     * Tools → Trace Path saved paths (Room). Process-lifetime so lists survive process death and are
     * not tied to a live radio connection graph.
     */
    val savedTracePathStore = RoomSavedTracePathStore.fromDatabase(database)

    /**
     * Process-lifetime link-preview cache (Room + Ktor). Shared across chat threads; per-thread
     * [one.meshcore.shared.chat.linkpreview.LinkPreviewController] instances drive fetch state.
     */
    internal val linkPreviewSettings = linkPreviewSettings(chatDisplayPreferences)
    internal val linkPreviewCache: LinkPreviewCache = createLinkPreviewCache(
        database = database,
        settings = linkPreviewSettings,
        scope = appScope,
        now = { System.currentTimeMillis() / 1000.0 },
    )
    internal val malwareDomainFilter: MalwareDomainFilter = defaultMalwareDomainFilter(
        cacheDirectory = appContext.filesDir.resolve("malware"),
        preferences = prefs,
        scope = appScope,
    )

    /** Process-wide debug log store (Room-backed; survives process death). */
    internal val debugLogStore = RoomDebugLogStore.fromDatabase(database)

    /** Process-wide buffer that flushes into [debugLogStore]; assigned as [DebugLogBuffer] shared. */
    internal val debugLogBuffer = DebugLogBuffer(store = debugLogStore, scope = appScope)

    init {
        startDebugLogBuffer()
    }

    val lastConnectionStore = LastConnectionStore(prefs)
    internal val intentStore = ConnectionIntentStore(prefs)

    // Explicit type for the same reason the notification coordinator carries one: it is handed to
    // [connectionManager], which sits in an inference cycle that cannot resolve on its own.
    val connectionUi: ConnectionUiState = ConnectionUiState(appScope)

    /** The conversation currently on screen; the open thread sets it, ingest + notifier read it. */
    val activeConversation = ActiveConversationState()

    /**
     * The notification brain. The per-connection ingest pipeline takes [notifier] as its
     * `IngestNotifier`, the one place ingest connects to notifications. Authorization re-reads the live
     * POST_NOTIFICATIONS grant on each post so a grant mid-session takes effect without a rebuild.
     */
    val notificationPreferences = StoredNotificationPreferences(prefs) {
        System.currentTimeMillis() / MILLIS_PER_SECOND
    }

    /**
     * Hot signal for Chats/Nodes list reload after ingest, block, or contact mutations (iOS
     * SyncCoordinator version bumps). Extra capacity so a burst of ACKs never blocks the emitter.
     */
    internal val listInvalidations = MutableSharedFlow<Unit>(
        extraBufferCapacity = LIST_INVALIDATION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Observed by [ConversationListRoot] to reload when conversations change off-screen. */
    val conversationListInvalidations: SharedFlow<Unit> = listInvalidations.asSharedFlow()

    /**
     * Observed by the Nodes list to reload after an app-side contact mutation (Add Contact, QR scan,
     * Discover "Add", node-config import). Those write through `notifyContactsChanged` without a device
     * push, so the list has no other signal that the table changed.
     */
    val contactListInvalidations: SharedFlow<Unit> = listInvalidations.asSharedFlow()

    /** The badge's unread source, read per recompute so it always reflects the live Room rows. */
    internal val unreadCountsStore = RoomUnreadCountsStore(database)

    // Explicit type: the unread-source closure reads [connectionManager], which in turn takes this
    // coordinator, and inference cannot resolve that cycle on its own.
    val notificationCoordinator: NotificationCoordinator = NotificationCoordinator(
        poster = AndroidNotificationPoster(appContext),
        activeConversation = activeConversation,
        scope = appScope,
        now = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        edges = NotificationCoordinatorEdges(
            preferences = notificationPreferences,
            isAuthorized = { isNotificationsAuthorized() },
            // Last-radio partition when disconnected, matching iOS `currentRadioID` (live else last).
            unreadCounts = {
                awaitInitialOfflineData()
                currentRadioId()?.let { unreadCountsStore.totals(it) } ?: UnreadCounts.Zero
            },
            onConversationsChanged = { invalidateLists() },
        ),
    )

    /** The ingest seam's notifier, wired into the per-connection pipeline through [ConnectionManager]. */
    val notifier: IngestNotifier get() = notificationCoordinator

    /**
     * The app-lifetime encrypted node-password store (the iOS keychain analogue): AES-256-GCM under an
     * Android Keystore key, ciphertext persisted in [prefs]. Shared across connections and injected into
     * each per-connection service graph.
     */
    val nodePasswordStore: NodePasswordStore = KeystoreNodePasswordStore(prefs, ioDispatcher)

    /**
     * Snapshot of the connected radio's app-owned region list and default flood-scope name (Device row).
     * Refreshed at Connected (iOS `connectedDevice` at `onConnectionReady`) so an open inherit channel
     * can push the region key before Ready drains the send queue. Ready refreshes again after persist.
     * The default name is a StateFlow so a late Device-row read still re-pushes.
     */
    internal val connectedKnownRegions = AtomicReference(emptyList<String>())
    internal val connectedDefaultFloodScopeName = MutableStateFlow<String?>(null)
    internal val connectedSupportsUnscopedFloodSend = AtomicReference(false)
    internal val floodScopePublicationGate = FloodScopePublicationGate()

    /**
     * The connected radio's routing path-hash mode, cached from the Device row on Ready so the path
     * editor and the zero-hop ping read it without a suspend call at each use.
     */
    internal val connectedPathHashMode = ConnectedPathHashMode()

    /**
     * Device-row snapshots Settings composition reads. Filled off the main thread on connect/Ready
     * and after writes so the hub list never `runBlocking`s Room.
     */
    internal val settingsSnapshots = DeviceSettingsSnapshotCache()

    /**
     * BLE write pacing per transport handle, in milliseconds. The radio's model — and so its chip family
     * — only arrives in the handshake, after the link has already been built, so the pacing a handle
     * needs is remembered from its last connection and applied when that handle is next linked. A handle
     * with no stored model paces at the [DevicePlatform.UNKNOWN] rate, the ESP32-safe value, exactly as
     * an unrecognized model does on iOS.
     */
    internal val bleWritePacingByHandle = ConcurrentHashMap<String, Long>()

    internal val channelSlotGenerations = ChannelSlotGenerationTracker()

    /**
     * Bound after [chatPreviewPrewarmer] exists; arrival-time prime reads the live value.
     * Mirrors iOS `ChatPrewarmRefresher` constructing a primer with the process-lifetime cache.
     */
    internal var warmClosedConversationPreviews: suspend (Conversation) -> Unit = {}

    internal val connectionManager = buildConnectionManager()

    internal val rxLogLogger = PersistentLogger(appScope, category = "RxLogService")

    internal var storedChannelDecryptJob: Job? = null

    init {
        startStoredChannelDecrypt()
    }

    // Stateless read/delete adapter for the scheduled stale-node pass; the radio-backed removeContact runs
    // through the live ContactService, so a throwaway store here only reads the same Room partition.
    internal val staleCleanupContactStore = RoomContactStore(database)

    /**
     * Automatic stale-node cleanup (iOS `AppState.performStaleNodeCleanup`): threshold-gated, 3-hour
     * cooldown, favorite-exempt. Triggered on each sync-complete (radio Ready) and forced on a threshold
     * change. The remover reads the live connection each run; a disconnected run is a no-op zero result.
     */
    internal val staleNodeCleanupScheduler = buildStaleNodeCleanupScheduler()

    internal val connectionLogger = PersistentLogger(appScope, category = "ConnectionManager")
    internal val settingsLogger = PersistentLogger(appScope, category = "SettingsService")

    // Declared ahead of the app state, which owns the staged-navigation teardown a disconnect or a
    // radio switch runs. The explicit type keeps the initializer out of the container's inference cycle.
    val coordinator: NavigationCoordinator = NavigationCoordinator(appScope, prefs)

    val nodeDiscoveryWorkspace = NodeDiscoveryWorkspace(
        scope = appScope,
        dependencies = NodeDiscoveryWorkspace.Dependencies(
            sendDiscover = sendNodeDiscover,
            events = meshEvents,
            contactService = { connectionManager.services?.contactService },
            discoveredNodeStore = discoveredNodeStore,
            radioId = { connectionManager.radioId },
            isRadioConnected = { connectionManager.services != null },
            maxContacts = { connectionManager.services?.maxContacts },
        ),
    )

    val tracePathWorkspace: TracePathWorkspace = buildTracePathWorkspace(tracePathWaiter)

    val remoteAdminWorkspaces = RemoteAdminWorkspaces(
        scope = appScope,
        dependencies = RemoteAdminWorkspaces.Dependencies(
            repeaterAdminService = { connectionManager.services?.repeaterAdminService },
            roomAdminService = { connectionManager.services?.roomAdminService },
            contactService = { offlineData.contactService() },
            nodeSnapshotService = { connectionManager.services?.nodeSnapshotService },
            radioId = { offlineData.currentRadioId() },
            isRadioConnected = { connectionManager.services != null },
        ),
    )

    val lineOfSightWorkspace = LineOfSightViewModel(
        scope = appScope,
        elevationService = elevationService,
        fetchContacts = { radio ->
            val service = offlineData.contactService()
            val contacts = service?.getContacts(radio) ?: emptyList()
            if (service !== offlineData.contactService() || radio != offlineData.currentRadioId()) {
                throw kotlinx.coroutines.CancellationException("Line of Sight contact owner replaced")
            }
            contacts
        },
    ).also { it.configure(radioId = { offlineData.currentRadioId() }) }

    val noiseFloorWorkspace = NoiseFloorViewModel(
        parentScope = appScope,
        dependencies = NoiseFloorViewModel.Dependencies(
            fetchReading = {
                if (connectionManager.state.value is one.meshcore.shared.connection.ConnectionState.Ready) {
                    fetchNoiseFloorReading
                } else {
                    null
                }
            },
            sessionOwner = { connectionManager.services?.session?.events },
            radioId = { offlineData.currentRadioId() },
        ),
    )

    val appState = AppStateViewModel(
        connectionManager = connectionManager,
        stores = AppStateStores(
            lastConnectionStore = lastConnectionStore,
            intentStore = intentStore,
            prefs = prefs,
            database = database,
        ),
        scope = appScope,
        runtime = AppStateRuntime(
            connectionUi = connectionUi,
            navigation = coordinator,
            nodeDiscoveryWorkspace = nodeDiscoveryWorkspace,
            tracePathWorkspace = tracePathWorkspace,
            remoteAdminWorkspaces = remoteAdminWorkspaces,
        ),
        edges = AppStateEdges(
            batteryNotifier = notificationCoordinator,
            bluetoothAdapterEnabled = { appContext.bluetoothAdapterEnabled() },
        ),
    )

    internal val appForegroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The foreground edge an open room conversation restarts its keep-alive on. */
    val appForeground: SharedFlow<Unit> = appForegroundEvents.asSharedFlow()

    internal val appBackgroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The background edge an open composer flushes its draft on. */
    val appBackground: SharedFlow<Unit> = appBackgroundEvents.asSharedFlow()

    init {
        startRadioSessionObservers()
    }

    /** Device-row known regions for Settings → Advanced → Default Flood Scope / Manage Regions. */
    val knownRegions: () -> List<String> = { connectedKnownRegions.get() }

    /** The connected device's persisted default flood-scope name; `null` means disabled. */
    val defaultFloodScopeName: () -> String? = { connectedDefaultFloodScopeName.value }

    /** The live radio's blocked-channel-sender service, or `null` when disconnected. */
    val blockedChannelSenderServiceProvider: () -> BlockedChannelSenderService? =
        { connectionManager.services?.blockedChannelSenderService }

    val onboarding = OnboardingViewModel(prefs)

    val bleScan: BleScanController = KableBleScanController(appScope, appContext)

    internal val localNodeNameFlow = MutableStateFlow<String?>(null)

    /** The connected radio's name for the BLE menu header, or `null` when disconnected. */
    val localNodeName: StateFlow<String?> = localNodeNameFlow

    internal val clientRepeatFlow = MutableStateFlow(false)

    /** Whether the connected radio is running in client-repeat mode, or `false` when disconnected. */
    val clientRepeat: StateFlow<Boolean> = clientRepeatFlow

    /**
     * Unread badge count, rendered as the count on the ongoing connection notification. Android has no
     * portable numeric launcher badge, so that notification is the only surface this reaches.
     */
    val unreadCount: StateFlow<Int> = notificationCoordinator.badgeCount

    /** App-lifetime chat drafts (they survive reconnects), loaded once from preferences. */
    internal val draftStore = DraftStore.loading(prefs, appScope, settingsLogger::warning)
    internal val draftEditors = ChatDraftEditors(draftStore, appScope)

    /** True after demo/screenshot mode has seeded fixture rows (and remains on until both flags clear). */
    internal val fixturesActive: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** The live-first data providers every list, map and thread screen reads. */
    internal val offlineData: OfflineDataAccess = OfflineDataAccess(
        database = database,
        scope = appScope,
        lastConnectionStore = lastConnectionStore,
        liveServices = { connectionManager.services },
        nowEpochSeconds = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        fixtureRadioId = { ScreenshotChatFixtures.RADIO_ID.takeIf { fixturesActive.value } },
    )

    /**
     * Starts the persisted partition read during process setup without blocking the Application main
     * thread. UI and background entry points await it before consulting the synchronous provider lambdas.
     */
    private val initialOfflineDataRefresh = appScope.async { offlineData.refresh() }

    internal suspend fun awaitInitialOfflineData() = initialOfflineDataRefresh.await()

    /** Contact service: the live radio when connected, else the last-connected radio's stored rows. */
    val contactService: () -> ContactService? = offlineData.contactService

    /** Live node-config export/import service; null when no radio connection. */
    val nodeConfigService: () -> one.meshcore.shared.settings.NodeConfigService? = {
        if (connectionManager.state.value is one.meshcore.shared.connection.ConnectionState.Ready) {
            connectionManager.services?.nodeConfigService
        } else {
            null
        }
    }

    /** Conversation list store: the live radio when connected, else the last-connected radio's stored rows. */
    val conversationListStore: () -> ConversationListStore? = offlineData.conversationListStore

    /**
     * Live DM/channel status events for the chats list's failed-send badges. Re-read on each
     * collection so a reconnect binds the new [MessageService].
     */
    val messageStatusEvents: () -> Flow<MessageStatusEvent> = {
        liveMessageStatusEvents { connectionManager.services?.messageService }
    }

    /** Live room-server events for the chats list's failed-send badges. */
    val roomServerEvents: () -> Flow<RoomServerEvent> = {
        connectionManager.services?.roomServerService?.events ?: emptyFlow()
    }

    /** The live radio's channel service, or `null` when disconnected. Read by the channel create/join flows. */
    val channelService: () -> ChannelService? = { connectionManager.services?.channelService }

    /** The data partition to read: the live radio's, else the demo one, else the last-connected radio's. */
    val currentRadioId: () -> Uuid? = offlineData.currentRadioId

    /**
     * Warms inline-image and link-preview dimensions for chats on the list, matching
     * iOS `prewarmTopConversations`. Tap uses [chatTimelinePrimer].
     */
    val chatPreviewPrewarmer = one.meshcore.shared.chat.ChatPreviewPrewarmer(
        store = offlineData.chatStore,
        radioId = currentRadioId,
        prefetcher = {
            one.meshcore.shared.chat.InlineImagePrefetcher(linkPreviewCache, inlineImageDimensions)
        },
        previewsEnabled = { chatDisplayPreferences.state.value.linkPreviewsEnabled },
        shouldAutoResolve = { isChannel -> linkPreviewCache.shouldAutoResolve(isChannel) },
    )

    /**
     * Loads page 0 of a closed chat into its shared coordinator before navigation, matching iOS
     * `prefetchConversation` / `ChatTimelinePrimer`.
     */
    val chatTimelinePrimer = buildChatTimelinePrimer()

    internal val sendMessageShortcuts = SendMessageShortcutPublisher(appContext)
    internal val sendAdvertShortcuts = SendAdvertShortcutPublisher(appContext)
    internal val sendMessageShortcutRefresh = SupersedingTaskLauncher(appScope)
    internal val sendAdvertShortcutRefresh = SupersedingTaskLauncher(appScope)

    /** The live radio's repeater-admin service, or `null` when disconnected. Drives the node auth/admin UI. */
    val repeaterAdminService: () -> RepeaterAdminService? = { connectionManager.services?.repeaterAdminService }

    /** Room-backed status/telemetry snapshot history for the connected radio. */
    val nodeSnapshotService: () -> one.meshcore.shared.remotenode.NodeSnapshotService? =
        { connectionManager.services?.nodeSnapshotService }

    /** Room-server service: the live radio when connected, else the last-connected radio's stored rooms. */
    val roomServerService: () -> RoomServerService? = offlineData.roomServerService
    val roomAdminService: () -> one.meshcore.shared.room.RoomAdminService? =
        { connectionManager.services?.roomAdminService }

    init {
        startPreviewAndDemoFixtures()
    }

    /** The app-lifetime node-password store, exposed to the node auth sheet's saved-password load/clear. */
    val nodePasswordStoreProvider: () -> NodePasswordStore? = { nodePasswordStore }

    /** The connected radio's own public key, for the "Share My Contact" QR; `null` when disconnected. */
    val selfPublicKey: () -> ByteArray? = { connectionManager.services?.selfPublicKey }

    /** The connected radio's contact-table cap, or `null` when disconnected. */
    val maxContacts: () -> Int? = { connectionManager.services?.maxContacts }

    /** The connected radio's channel-slot cap, or `null` when disconnected. */
    val maxChannels: () -> Int? = { connectionManager.services?.maxChannels }

    /**
     * Live LoRa parameters from the connected Device row (kHz / Hz units). `null` when disconnected or
     * the row is not yet persisted. Reads the in-memory cache; Settings composition is on the main thread.
     */
    val radioSnapshot: () -> RadioDeviceSnapshot? = { settingsSnapshots.radio }

    /** User region selection for radio preset filtering; `null` when unset. */
    val regionSelection: () -> RegionSelection? = { settingsSnapshots.region }

    val locationService = AndroidLocationService(
        context = appContext,
        scope = appScope,
        requestSystemPermission = { requestLocationPermission() },
        hasAskedLocation = { locationPermissionAsked(appContext) },
    )
    internal val regionResolver = RegionResolver(
        location = locationService,
        geocoder = AndroidGeocoder(appContext, ioDispatcher),
        regionalAreas = RegionalAreas,
    )

    /** Auto-add policy for Settings → Advanced → Nodes. */
    val nodesAutoAddSnapshot: () -> NodesAutoAddSnapshot? = { settingsSnapshots.nodesAutoAdd }

    /** Telemetry / multi-ack / path-hash read model for Settings → Advanced. */
    val deviceOtherParamsSnapshot: () -> DeviceOtherParamsSnapshot? = { settingsSnapshots.otherParams }

    /**
     * The connected radio's routing hop width in bytes for the contact path editor; `null` when
     * disconnected, which leaves the editor on its 1-byte default. Mirrors iOS `connectedDevice?.hashSize`.
     */
    val deviceHashSize: () -> Int? = { connectedPathHashMode.routingHopBytes() }

    /** Connection settings read model for Settings → Connection. */
    val connectionSettingsSnapshot: () -> ConnectionSettingsSnapshot? = { settingsSnapshots.connection }

    /** Local device OCV curve for Settings → Advanced. */
    val deviceOcvSnapshot: () -> OcvEditorState? = { settingsSnapshots.ocv }

    /** Location settings read model for Settings → Location. */
    val locationSettingsSnapshot: () -> LocationSettingsSnapshot? = { settingsSnapshots.location }

    /** The connected Device row's id, scoping the per-radio location preferences. */
    val connectedDeviceId: () -> String? = { settingsSnapshots.deviceRowId }

    /**
     * Refreshes the advertised position before every self-advert. Reads the same per-radio preferences
     * Settings → Location writes, so the keys stay the ones iOS `DevicePreferenceStore` uses.
     */
    internal val selfAdvertSender = buildSelfAdvertSender()

    // Adapts the notification brain to the thread's set/clear sink so an open thread suppresses its own
    // unread bump and banner. The release runs on the app scope because the thread's own scope is already
    // cancelled when it disposes, and it stands down when a newer conversation has taken the slot.
    internal val activeConversationSink: ActiveConversationSink =
        NotificationActiveConversationSink(notificationCoordinator, appScope)

    // The room counterpart of activeConversationSink. The release runs on the app scope because the room
    // screen's own scope is already cancelled when it disposes, and it stands down when a newer room has
    // taken the slot, the guard iOS applies in `RoomConversationView.onDisappear`.
    val roomNotificationSink: RoomNotificationSink = object : RoomNotificationSink {
        override suspend fun setActiveRoom(sessionID: Uuid) = notificationCoordinator.setActiveRoom(sessionID)

        override fun clearActiveRoomAsync(sessionID: Uuid) {
            appScope.launch {
                if (activeConversation.activeRoomSessionID() != sessionID) return@launch
                notificationCoordinator.clearActiveConversation()
            }
        }
    }

    /** Binds a pushed chat route to the live per-connection graph for the shell's chat detail. */
    val chatThreadBinder = ChatThreadBinder(
        resolveDestination = { resolveChatDestination(it) },
        environment = { buildChatEnvironment(it) },
    )

    // Resolves the identity-hex route against the connected data layer (or offline fixtures): a DM keys on
    // the contact's local id, a channel resolves its row by id to recover the firmware slot. Rooms are
    // hosted by RoomDetailPane (ChatRoute.Room), not this binder.

    // Store-backed lookups so already-known contacts/channels open without a redundant join/add sheet.
    // The live connection, the demo fixtures or the last-connected radio supply the partition via
    // [contactService] / [conversationListStore], so a link opens the same way while disconnected.
    internal val deepLinkRouter = buildDeepLinkRouter()

    val pendingExternalUrl = PendingExternalUrl()

    /** Staging for a notification-tap conversation open; drained once launch settles (cold-launch taps). */
    internal val pendingConversationOpen = PendingConversationOpen<NotificationConversationOpen>()

    /** Staging for a new-contact notification tap opening the contact's detail; drained once launch settles. */
    internal val pendingContactOpen = PendingConversationOpen<String>()
    internal val notificationOpenRouting = SupersedingTaskLauncher(appScope)

    val lifecycle = buildAppLifecycle()

    init {
        startConnectionHeaderMirror()
    }

    companion object {
        internal const val MILLIS_PER_SECOND = 1000.0
        internal const val MV_PER_VOLT = 1000.0
        internal const val FACTORY_RESET_REBOOT_GRACE_MS = 1_000L
        internal const val LIST_INVALIDATION_BUFFER = 64

        // Decay cadence for the notification packet-rate readout (iOS LiveActivityManager decay timer).
        internal const val PACKET_RATE_TICK_MS = 15_000L

        operator fun invoke(context: Context): AppContainer {
            val sessionActions = AppConnectionSessionActions()
            val savedDeviceActions = AppConnectionSavedDeviceActions()
            return AppContainer(context, sessionActions, savedDeviceActions)
        }
    }
}
