package one.meshcore.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import one.meshcore.shared.AppInfo
import one.meshcore.shared.app.AppLifecycleController
import one.meshcore.shared.app.AppStateRuntime
import one.meshcore.shared.app.AppStateStores
import one.meshcore.shared.app.AppStateViewModel
import one.meshcore.shared.app.activate
import one.meshcore.shared.app.onEnterBackground
import one.meshcore.shared.app.onEnterForeground
import one.meshcore.shared.backup.BackupExportClock
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.BackupUserDefaults
import one.meshcore.shared.backup.RoomBackupExporter
import one.meshcore.shared.backup.RoomBackupImporter
import one.meshcore.shared.backup.adoptImportedRadioForOfflineScope
import one.meshcore.shared.backup.adoptRadioAfterCommittedImport
import one.meshcore.shared.backup.backupExportSuccessSummary
import one.meshcore.shared.backup.backupUserDefaultsSnapshot
import one.meshcore.shared.backup.clearAffectedChannelDrafts
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.backup.readBackupDocument
import one.meshcore.shared.backup.readTextDocument
import one.meshcore.shared.backup.restoreInto
import one.meshcore.shared.backup.suggestedBackupFilename
import one.meshcore.shared.backup.zlibCompress
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.liveMessageStatusEvents
import one.meshcore.shared.chat.ui.ChatThreadDisplayInputs
import one.meshcore.shared.chat.ui.ChatThreadEnvironment
import one.meshcore.shared.chat.ui.ChatThreadGraphInputs
import one.meshcore.shared.chat.ui.ChatThreadLifecycleInputs
import one.meshcore.shared.chat.ui.ChatThreadPeopleInputs
import one.meshcore.shared.chat.ui.ChatThreadShareInputs
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.connectionIntentSummary
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.disconnectBeforeForget
import one.meshcore.shared.connection.lastDisconnectDiagnostic
import one.meshcore.shared.connection.refreshDeviceSettings
import one.meshcore.shared.connection.setAppActive
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.contact.removeLocalContact
import one.meshcore.shared.contact.ui.QrCodeEncoder
import one.meshcore.shared.contact.unfavoritedNodeCount
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.KtorGeoHttpClient
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.DeepLinkLookups
import one.meshcore.shared.navigation.DeepLinkRouter
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.PendingExternalUrl
import one.meshcore.shared.notification.StoredNotificationPreferences
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.persistence.RoomDebugLogStore
import one.meshcore.shared.persistence.RoomSavedTracePathStore
import one.meshcore.shared.persistence.RoomUnreadCountsStore
import one.meshcore.shared.persistence.deleteDeviceRow
import one.meshcore.shared.persistence.demoteDeviceToGhost
import one.meshcore.shared.persistence.deviceLastConnected
import one.meshcore.shared.persistence.deviceMaxChannels
import one.meshcore.shared.persistence.deviceMaxContacts
import one.meshcore.shared.persistence.devicePublicKey
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.purgeRadioPartition
import one.meshcore.shared.prefs.DataStorePreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.settings.AppliedRadioPresetStore
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.settings.ConnectionDiagnostics
import one.meshcore.shared.settings.DebugLogBuffer
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogExport
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogSource
import one.meshcore.shared.settings.OfflineDataAccess
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.PlatformDiagnostics
import one.meshcore.shared.settings.exportRetainedDebugLog
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.tools.configure
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.ui.ChatDestination
import one.meshcore.shared.ui.ChatThreadBinder
import one.meshcore.shared.ui.CoachMarkStore
import one.meshcore.shared.ui.InertBleScanController
import one.meshcore.shared.ui.InertConnectionActions
import one.meshcore.shared.ui.InertPermissionController
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionUiState
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.ShellMaintenanceInputs
import one.meshcore.shared.ui.ShellPreferenceInputs
import one.meshcore.shared.ui.ShellRadioInputs
import one.meshcore.shared.ui.ShellServiceInputs
import one.meshcore.shared.ui.ShellSessionInputs
import one.meshcore.shared.ui.ShellSharingInputs
import one.meshcore.shared.whatsnew.WhatsNewStore
import org.jetbrains.compose.resources.getString
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime

/**
 * The desktop process-scoped object graph, the counterpart of the Android `AppContainer`. It stands up
 * the same navigation coordinator, deep-link router, and app-state lifecycle bridge over a real DataStore
 * and Room database rooted in the user's app-data directory, so the window-focus lifecycle drives the
 * same activate/foreground/background path the Android shell does.
 *
 * The live transport is WiFi-only on the desktop (Windows) build and lands with W15; the session factory
 * is deferred like the Android BLE one and is unreachable without a persisted last device.
 */
class DesktopContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val draftEditorScope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    private val backupWorkspace = one.meshcore.shared.settings.ui.BackupSettingsWorkspace(
        CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
    )

    private val nodeConfigImportWorkspace = one.meshcore.shared.settings.ui.NodeConfigImportWorkspace(
        CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        isDestinationCurrent = {
            coordinator.selectedTab.value == one.meshcore.shared.navigation.AppTab.SETTINGS.index &&
                coordinator.selectedSetting.value == one.meshcore.shared.navigation.SettingsDestination.CONFIG_IMPORT
        },
    )

    private val dataDir: File = File(System.getProperty("user.home"), ".meshcore").apply { mkdirs() }
    private val prefs = DataStorePreferencesStore.atPath(File(dataDir, "meshcore.preferences_pb").absolutePath)
    private val database = meshDatabase(File(dataDir, "mesh.db").absolutePath)
    private val lastConnectionStore = LastConnectionStore(prefs)
    private val intentStore = ConnectionIntentStore(prefs)
    private val connectionUi = ConnectionUiState(appScope)

    // The desktop has no secure keystore, and its transport (WiFi-only) is inert until W15, so remote-node
    // auth is unreachable here; an in-memory store stands in until a desktop secure-credential store lands
    // with W15. It never persists a password to disk in the interim.
    private val nodePasswordStore: NodePasswordStore = InMemoryNodePasswordStore()

    private val connectionManager = ConnectionManager(
        database = database,
        scope = appScope,
        linkFactory = { error("Desktop has no BLE transport") },
        sessionFactory = { _: MeshLink -> error("Desktop session construction lands with W15") },
        nowEpochSeconds = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        ports = ConnectionManagerPorts(
            lastConnectionStore = lastConnectionStore,
            appliedRadioPresetStore = AppliedRadioPresetStore(prefs),
            intentStore = intentStore,
            nodePasswordStore = nodePasswordStore,
            connectionUi = connectionUi,
            threadCoordinatorRegistry = { radioId -> offlineData.threadCoordinatorRegistry(radioId) },
            threadChatStore = { radioId -> offlineData.threadChatStore(radioId) },
            roomNameFallback = { getString(Res.string.contacts_node_kind_room) },
        ),
    )

    val coordinator: NavigationCoordinator = NavigationCoordinator(appScope, prefs)

    private val nodeDiscoveryWorkspace = NodeDiscoveryWorkspace(
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        dependencies = NodeDiscoveryWorkspace.Dependencies(
            sendDiscover = { _, _ -> throw CancellationException() },
            events = { null },
            contactService = { connectionManager.services?.contactService },
            discoveredNodeStore = { null },
            radioId = { connectionManager.radioId },
            isRadioConnected = { false },
            maxContacts = { null },
        ),
    )

    /**
     * The shared shell dependencies for the desktop root. Permissions read as all-granted and the BLE
     * scanner/connection actions are inert: the desktop transport (WiFi-only) lands in W15, so the shell
     * and onboarding render and are navigable while the connect commands stay no-ops for now.
     */
    private val savedTracePathStore = RoomSavedTracePathStore.fromDatabase(database)

    /**
     * The live-first data providers, the same ones the Android container holds. The desktop transport
     * lands with W15, so until then every row this shell shows comes from the offline half: the
     * last-connected radio's partition, restored from a backup or written by an earlier Android session.
     */
    private val offlineData: OfflineDataAccess = OfflineDataAccess(
        database = database,
        scope = appScope,
        lastConnectionStore = lastConnectionStore,
        liveServices = { connectionManager.services },
        nowEpochSeconds = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    ).also { access -> runBlocking { access.refresh() } }

    private val tracePathWorkspace = TracePathWorkspace(
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        dependencies = TracePathWorkspace.Dependencies(
            runner = { null },
            builder = TracePathBuilder.Dependencies(
                contactService = { offlineData.contactService() },
                radioId = { offlineData.currentRadioId() },
            ),
            deviceHashMode = { null },
            deviceLocation = { null },
            savedPathStore = savedTracePathStore,
            recents = RecentHopsStore(prefs),
        ),
    )

    val remoteAdminWorkspaces = RemoteAdminWorkspaces(
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
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
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        elevationService = ElevationService(KtorGeoHttpClient()),
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
        parentScope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        dependencies = NoiseFloorViewModel.Dependencies(
            fetchReading = { null },
            sessionOwner = { null },
            radioId = { offlineData.currentRadioId() },
        ),
    )

    private val appState = AppStateViewModel(
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
    )

    private val _appForeground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The foreground edge an open room conversation restarts its keep-alive on. */
    val appForeground: SharedFlow<Unit> = _appForeground.asSharedFlow()

    private val onboarding = OnboardingViewModel(prefs)

    private val draftStore = DraftStore.loading(
        prefs,
        appScope,
        PersistentLogger(appScope, category = "SettingsService")::warning,
    )
    private val unreadCountsStore = RoomUnreadCountsStore(database)
    private val unreadCount = MutableStateFlow(0)
    private val localNodeName = MutableStateFlow<String?>(null)
    private val selfPublicKeyBytes = MutableStateFlow<ByteArray?>(null)
    private val maxContactsCap = MutableStateFlow<Int?>(null)
    private val maxChannelsCap = MutableStateFlow<Int?>(null)
    private val draftEditors = ChatDraftEditors(draftStore, draftEditorScope)

    private val appearancePreferences = AppearancePreferencesStore(prefs, appScope).also { it.load() }
    private val chatDisplayPreferences = ChatDisplayPreferencesStore(prefs, appScope).also { it.load() }
    private val translationTargetPreferences =
        one.meshcore.shared.settings.TranslationTargetPreferencesStore(prefs, appScope).also { it.load() }
    private val notificationPreferences = StoredNotificationPreferences(prefs) {
        System.currentTimeMillis() / MILLIS_PER_SECOND
    }
    private val whatsNewStore = WhatsNewStore(prefs, appScope).also { it.load() }
    private val mapDisplayPreferences = MapDisplayPreferencesStore(prefs, appScope).also { it.load() }
    private val coachMarkStore = CoachMarkStore(prefs)
    private val debugLogStore = RoomDebugLogStore.fromDatabase(database)
    private val debugLogBuffer = DebugLogBuffer(store = debugLogStore, scope = appScope)
    private val backupLogger = PersistentLogger(appScope, category = "Backup")
    private val backupActions = DesktopBackupActions()
    private val shellActions = DesktopShellActions()

    /**
     * Opens stored DM and channel threads from the last radio's partition. Send stays disconnected
     * until the desktop WiFi transport lands.
     */
    private val chatThreadBinder = ChatThreadBinder(
        resolveDestination = shellActions::resolveChatDestination,
        environment = shellActions::buildChatEnvironment,
    )

    val deps: ShellDependencies = ShellDependencies(
        session = session(),
        services = services(),
        sharing = sharing(),
        preferences = preferences(),
        radio = radio(),
        maintenance = maintenance(),
    )

    private val deepLinkRouter = DeepLinkRouter(
        coordinator = coordinator,
        lookups = object : DeepLinkLookups {
            override suspend fun ownPublicKey(): ByteArray? = selfPublicKeyBytes.value
                ?: lastConnectionStore.deviceId()?.let { devicePublicKey(database, it) }
            override suspend fun existingContactKeyHexes(): Set<String> = emptySet()
            override suspend fun existingChannelSecretHexes(): Set<String> = emptySet()
        },
    )

    val pendingExternalUrl = PendingExternalUrl()

    val lifecycle = AppLifecycleController(
        scope = appScope,
        onActivate = { appState.activate() },
        onForeground = {
            appState.onEnterForeground()
            shellActions.refreshUnread()
            // Backgrounding stopped every room keep-alive; the room still on screen restarts its own.
            _appForeground.emit(Unit)
        },
        onBackground = {
            appState.onEnterBackground()
            draftEditorScope.launch { draftEditors.flushAll() }
        },
        onForegroundChanged = { appState.setAppActive(it) },
        onReady = { pendingExternalUrl.markReady(shellActions::routeExternal) },
    )

    /** Routes a `meshcore://` URL passed on the command line. */
    fun submitDeepLink(url: String) = pendingExternalUrl.submit(url)

    private inner class DesktopBackupActions {
        /**
         * Settings → Backup export: collect Room rows, zlib-compress, and write a `.mc1backup` the
         * user picks. Cancel of the save dialog returns null and leaves the disk unchanged.
         */
        suspend fun exportBackup(): BackupExportSuccessSummary? {
            val json = RoomBackupExporter.exportJson(
                database = database,
                appVersion = APP_VERSION,
                appBuild = APP_BUILD,
                exportDate = System.currentTimeMillis() / MILLIS_PER_SECOND,
                userDefaults = prefs.backupUserDefaultsSnapshot(),
            )
            val compressed = zlibCompress(json.encodeToByteArray())
            val now = LocalDateTime.now()
            val suggested = suggestedBackupFilename(
                BackupExportClock(
                    year = now.year,
                    month = now.monthValue,
                    dayOfMonth = now.dayOfMonth,
                    hour = now.hour,
                    minute = now.minute,
                    second = now.second,
                ),
            )
            val file = DesktopBackupFiles.pickSaveFile(
                title = AppInfo.APP_NAME,
                suggestedName = suggested,
            ) ?: return null
            withContext(Dispatchers.IO) { file.writeBytes(compressed) }
            return backupExportSuccessSummary(file.name, compressed, json)
        }

        suspend fun exportDebugLogsText(): String {
            val text = exportRetainedDebugLog(
                buffer = debugLogBuffer,
                store = debugLogStore,
                nowMillis = System.currentTimeMillis(),
            ) { entries ->
                DebugLogExport.format(
                    entries,
                    connection = connectionDiagnostics(),
                    platform = platformDiagnostics(),
                )
            }
            saveDebugLogExport(text)
            return text
        }

        suspend fun connectionDiagnostics(): ConnectionDiagnostics {
            val deviceId = lastConnectionStore.deviceId()
            return ConnectionDiagnostics(
                state = connectionManager.state.value.toDeviceConnectionState().name,
                intent = connectionManager.connectionIntentSummary(),
                lastDisconnectDiagnostic = connectionManager.lastDisconnectDiagnostic(),
                deviceName = lastConnectionStore.deviceName(),
                deviceId = deviceId?.value,
                lastConnectedEpochSeconds = deviceId?.let { deviceLastConnected(database, it) },
            )
        }

        fun platformDiagnostics(): PlatformDiagnostics = PlatformDiagnostics(
            appVersion = AppInfo.APP_NAME,
            buildNumber = "desktop",
            deviceModel = System.getProperty("os.arch") ?: "unknown",
            osName = System.getProperty("os.name") ?: "Desktop",
            osVersion = System.getProperty("os.version") ?: "unknown",
            exportedAtEpochMillis = System.currentTimeMillis(),
        )

        fun saveDebugLogExport(text: String) {
            appScope.launch {
                val file = DesktopBackupFiles.pickSaveNamedFile(
                    title = AppInfo.APP_NAME,
                    suggestedName = "meshcore-diagnostics.txt",
                ) ?: return@launch
                withContext(Dispatchers.IO) { file.writeText(text) }
            }
        }

        suspend fun clearDebugLogs() {
            debugLogStore.clearDebugLogEntries()
        }

        /**
         * Product import path: pick a `.mc1backup` (or raw JSON) and return uncompressed JSON.
         * Merge waits for the Settings preview confirm.
         */
        suspend fun pickBackupFileJson(): String? {
            val file = DesktopBackupFiles.pickOpenFile(title = AppInfo.APP_NAME) ?: return null
            return withContext(Dispatchers.IO) {
                file.inputStream().use { stream ->
                    readBackupDocument(read = { buffer -> stream.read(buffer) })
                }
            }
        }

        suspend fun importBackupJson(jsonText: String): BackupImportResult {
            val envelope = decodeBackupEnvelope(jsonText)
            val result = RoomBackupImporter.importEnvelope(
                database,
                envelope,
                radioConnected = { connectionManager.state.value.isConnected },
            )
            try {
                restoreBackupUserDefaults(envelope.userDefaults)
            } catch (_: CancellationException) {
                // Import already committed.
            }
            withContext(Dispatchers.Main.immediate) { result.clearAffectedChannelDrafts(draftEditors) }
            adoptRadioAfterCommittedImport {
                adoptImportedRadioForOfflineScope(database, lastConnectionStore, backupLogger::warning)
            }
            offlineData.resetAfterDataRestore()
            shellActions.refreshUnread()
            return result
        }

        suspend fun restoreBackupUserDefaults(userDefaults: BackupUserDefaults?) {
            if (userDefaults == null || userDefaults.restoreInto(prefs, backupLogger::warning).isEmpty()) return
            chatDisplayPreferences.load()
            translationTargetPreferences.load()
            appearancePreferences.load()
            mapDisplayPreferences.load()
        }
    }

    private suspend fun pickNodeConfigJsonText(): String? {
        val file = DesktopBackupFiles.pickOpenFile(title = AppInfo.APP_NAME) ?: return null
        return withContext(Dispatchers.IO) {
            file.inputStream().use { stream ->
                readTextDocument(read = { buffer -> stream.read(buffer) })
            }
        }
    }

    private fun saveNodeConfigJson(json: String, filename: String) {
        appScope.launch {
            val file = DesktopBackupFiles.pickSaveNamedFile(
                title = AppInfo.APP_NAME,
                suggestedName = filename.ifBlank { "meshcore_config.json" },
            ) ?: return@launch
            withContext(Dispatchers.IO) { file.writeText(json) }
        }
    }

    private inner class DesktopShellActions {
        suspend fun resolveChatDestination(route: ChatRoute): ChatDestination? {
            val radioID = offlineData.currentRadioId() ?: return null
            val contacts = offlineData.contactService() ?: return null
            val listStore = offlineData.conversationListStore() ?: return null
            suspend fun direct(directRoute: ChatRoute.Direct): ChatDestination? {
                val contact = contacts.getContactByID(Uuid.normalized(directRoute.contactId)) ?: return null
                val title = contact.nickname?.takeIf { it.isNotBlank() } ?: contact.name
                return ChatDestination(ChatConversationID.dm(radioID, contact.id), title, contact)
            }
            suspend fun channel(channelRoute: ChatRoute.Channel): ChatDestination? {
                val channelID = Uuid.normalized(channelRoute.channelId)
                val channel = listStore.fetchChannels(radioID).firstOrNull { it.id == channelID } ?: return null
                return ChatDestination(
                    ChatConversationID.channel(radioID, channel.index),
                    channel.name,
                    contact = null,
                    channel = channel,
                )
            }
            return when (route) {
                is ChatRoute.Direct -> direct(route)
                is ChatRoute.Channel -> channel(route)
                is ChatRoute.Room -> null
            }
        }

        fun buildChatEnvironment(destination: ChatDestination): ChatThreadEnvironment = ChatThreadEnvironment(
            graph = ChatThreadGraphInputs(
                registry = { offlineData.threadCoordinatorRegistry(destination.conversationID.radioID) },
                store = { offlineData.threadChatStore(destination.conversationID.radioID) },
                sendQueue = {
                    connectionManager.services
                        ?.takeIf { it.radioId == destination.conversationID.radioID && !it.sendQueue.isRetired }
                        ?.sendQueue
                },
                messageService = {
                    connectionManager.services
                        ?.takeIf { it.radioId == destination.conversationID.radioID && !it.sendQueue.isRetired }
                        ?.messageService
                },
                draftEditors = { draftEditors },
                teardownScope = { draftEditorScope },
                currentContact = { destination.contact },
                unreadCount = { destination.contact?.unreadCount ?: 0 },
                localNodeName = {
                    localNodeName.value.takeIf {
                        connectionManager.services?.let {
                            it.radioId == destination.conversationID.radioID && !it.sendQueue.isRetired
                        } == true
                    }
                },
                incomingEvents = { emptyFlow() },
                statusEvents = { emptyFlow() },
            ),
            display = ChatThreadDisplayInputs(
                showIncomingSendTime = { chatDisplayPreferences.state.value.showIncomingSendTime },
                autoPlayGIFs = { chatDisplayPreferences.state.value.autoPlayGIFs },
                replyWithQuote = { chatDisplayPreferences.state.value.replyWithQuote },
                showIncomingPath = { chatDisplayPreferences.state.value.showIncomingPath },
                showIncomingHopCount = { chatDisplayPreferences.state.value.showIncomingHopCount },
                showIncomingRegion = { chatDisplayPreferences.state.value.showIncomingRegion },
                showIncomingHeardCount = { chatDisplayPreferences.state.value.showIncomingHeardCount },
                previewsEnabled = { chatDisplayPreferences.state.value.linkPreviewsEnabled },
                showMapPreviews = { chatDisplayPreferences.state.value.showMapPreviewThumbnails },
            ),
            people = ChatThreadPeopleInputs(
                contactService = { offlineData.graphFor(destination.conversationID.radioID).contactService },
                radioId = { destination.conversationID.radioID },
            ),
            share = ChatThreadShareInputs(
                qrEncode = QrCodeEncoder,
                copyToClipboard = ::copyToClipboard,
                mapDisplayPreferences = mapDisplayPreferences,
            ),
            lifecycle = ChatThreadLifecycleInputs(
                translationTargetPreferences = translationTargetPreferences,
                dataRestoredGeneration = offlineData.dataRestoredGeneration,
            ),
        )

        suspend fun unfavoritedCount(): Int {
            val radio = offlineData.currentRadioId() ?: return 0
            val service = offlineData.contactService() ?: return 0
            return unfavoritedNodeCount(radio, service)
        }

        suspend fun removeUnfavorited(): StaleNodeRemovalResult {
            val radio = offlineData.currentRadioId()
                ?: return StaleNodeRemovalResult(removed = 0, total = 0)
            val service = offlineData.contactService()
                ?: return StaleNodeRemovalResult(removed = 0, total = 0)
            val targets = service.getContacts(radio).filter { !it.isFavorite }
            if (targets.isEmpty()) return StaleNodeRemovalResult(removed = 0, total = 0)
            var removed = 0
            for (target in targets) {
                val ok = runCatching {
                    service.removeLocalContact(contactID = target.id, publicKey = target.publicKey)
                }.isSuccess
                if (ok) removed++
            }
            refreshUnread()
            return StaleNodeRemovalResult(removed = removed, total = targets.size)
        }

        suspend fun forgetDevice(deleteData: Boolean) {
            val radioId = lastConnectionStore.radioId()
            val deviceId = lastConnectionStore.deviceId()
            disconnectBeforeForget(backupLogger::warning) { connectionManager.disconnect() }
            lastConnectionStore.clear()
            offlineData.refresh()
            if (deviceId != null) {
                if (deleteData) {
                    deleteDeviceRow(database, deviceId)
                } else {
                    demoteDeviceToGhost(database, deviceId)
                }
            }
            if (deleteData && radioId != null) {
                purgeRadioPartition(database, radioId)
            }
            refreshUnread()
        }

        fun copyToClipboard(text: String) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }

        fun readClipboardText(): String? = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()

        suspend fun refreshUnread() {
            localNodeName.value = lastConnectionStore.deviceName()
            val deviceId = lastConnectionStore.deviceId()
            selfPublicKeyBytes.value = deviceId?.let { devicePublicKey(database, it) }
            maxContactsCap.value = deviceId?.let { deviceMaxContacts(database, it) }
            maxChannelsCap.value = deviceId?.let { deviceMaxChannels(database, it) }
            val radio = offlineData.currentRadioId()
            unreadCount.value = if (radio == null) {
                0
            } else {
                val counts = unreadCountsStore.totals(radio)
                counts.contacts + counts.channels + counts.rooms
            }
        }

        fun routeExternal(url: String) {
            appScope.launch { deepLinkRouter.routeExternalOpen(url) }
        }
    }

    private fun session(): ShellSessionInputs = ShellSessionInputs(
        coordinator = coordinator,
        onboarding = onboarding,
        initialOnboardingComplete = runBlocking {
            prefs.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING) ?: false
        },
        statusPill = appState.statusPill,
        battery = appState.battery,
        batteryOcvArray = appState.batteryOcvArray,
        connectionState = appState.connectionState,
        connectionAlert = appState.connectionAlert,
        shouldShowConnectPicker = appState.shouldShowConnectPicker,
        consumeConnectPicker = appState::consumeConnectPicker,
        consumeCompanionPairingOnPicker = appState::consumeCompanionPairingOnPicker,
        unreadCount = unreadCount,
        localNodeName = localNodeName,
        permissions = InertPermissionController(
            PermissionUiState(
                granted = OnboardingPermission.entries.toSet(),
                determined = OnboardingPermission.entries.toSet(),
            ),
        ),
        bleScan = InertBleScanController(),
        actions = InertConnectionActions(onDismissAlert = connectionUi::dismissAlert),
        contactService = offlineData.contactService,
        conversationListStore = offlineData.conversationListStore,
        messageStatusEvents = {
            liveMessageStatusEvents { connectionManager.services?.messageService }
        },
        roomServerEvents = {
            connectionManager.services?.roomServerService?.events ?: emptyFlow()
        },
    )
    private fun services(): ShellServiceInputs = ShellServiceInputs(
        appForeground = appForeground,
        currentRadioId = offlineData.currentRadioId,
        repeaterAdminService = { connectionManager.services?.repeaterAdminService },
        nodeSnapshotService = { connectionManager.services?.nodeSnapshotService },
        roomServerService = offlineData.roomServerService,
        nodePasswordStore = { nodePasswordStore },
        chatThread = chatThreadBinder,
        selfPublicKey = { selfPublicKeyBytes.value },
        maxContacts = { maxContactsCap.value },
        maxChannels = { maxChannelsCap.value },
        nowSeconds = { (System.currentTimeMillis() / MILLIS_PER_SECOND).toLong() },
        qrEncode = QrCodeEncoder,
        savedTracePathStore = savedTracePathStore,
        nodeDiscoveryWorkspace = nodeDiscoveryWorkspace,
        tracePathWorkspace = tracePathWorkspace,
        remoteAdminWorkspaces = remoteAdminWorkspaces,
        lineOfSightWorkspace = lineOfSightWorkspace,
        noiseFloorWorkspace = noiseFloorWorkspace,
        elevationService = ElevationService(KtorGeoHttpClient()),
    )
    private fun sharing(): ShellSharingInputs = ShellSharingInputs(
        backupWorkspace = backupWorkspace,
        readClipboard = shellActions::readClipboardText,
        copyToClipboard = shellActions::copyToClipboard,
        shareText = shellActions::copyToClipboard,
        exportBackup = { backupActions.exportBackup() },
        importBackupFile = { backupActions.pickBackupFileJson() },
        importBackup = { json -> backupActions.importBackupJson(json) },
        exportDebugLogs = { backupActions.exportDebugLogsText() },
        clearDebugLogs = { backupActions.clearDebugLogs() },
    )
    private fun preferences(): ShellPreferenceInputs = ShellPreferenceInputs(
        chatDisplayPreferences = chatDisplayPreferences,
        translationTargetPreferences = translationTargetPreferences,
        notificationPreferences = notificationPreferences,
        appearancePreferences = appearancePreferences,
        mapDisplayPreferences = mapDisplayPreferences,
        whatsNewStore = whatsNewStore,
        coachMarkStore = coachMarkStore,
    )
    private fun radio(): ShellRadioInputs = ShellRadioInputs(
        settingsStartupReadsAllowed = appState.settingsStartupReadsAllowed,
        refreshDeviceSettings = { connectionManager.refreshDeviceSettings() },
    )
    private fun maintenance(): ShellMaintenanceInputs = ShellMaintenanceInputs(
        nodeConfigImportWorkspace = nodeConfigImportWorkspace,
        unfavoritedCount = { shellActions.unfavoritedCount() },
        removeUnfavorited = { shellActions.removeUnfavorited() },
        forgetKeepData = { shellActions.forgetDevice(deleteData = false) },
        forgetDeleteData = { shellActions.forgetDevice(deleteData = true) },
        factoryReset = {
            shellActions.forgetDevice(deleteData = true)
            true
        },
        shareNodeConfigJson = { json, filename -> saveNodeConfigJson(json, filename) },
        pickNodeConfigJson = { pickNodeConfigJsonText() },
    )

    init {
        runBlocking { shellActions.refreshUnread() }
        appScope.launch {
            DebugLogBuffer.setShared(debugLogBuffer)
            DebugLogBuffer.record(
                DebugLogEntryDto.of(
                    id = Uuid.normalized(java.util.UUID.randomUUID().toString()),
                    timestamp = System.currentTimeMillis(),
                    level = DebugLogLevel.INFO,
                    source = DebugLogSource("io.pocketmesh.app", "App"),
                    message = "Debug log buffer active",
                ),
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val APP_VERSION = "1.0.0"
        const val APP_BUILD = "1"
    }
}
