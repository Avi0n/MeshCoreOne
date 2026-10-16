package one.meshcore.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.app.AppLifecycleController
import one.meshcore.shared.app.activate
import one.meshcore.shared.app.onEnterBackground
import one.meshcore.shared.app.onEnterForeground
import one.meshcore.shared.channel.ChannelSlotOccupantChanged
import one.meshcore.shared.chat.applyChannelSlotOccupantChanged
import one.meshcore.shared.chat.shouldCloseOpenChannelChat
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.applyManualRadioParams
import one.meshcore.shared.connection.armBleReconnectIfWanted
import one.meshcore.shared.connection.bleBluetoothStateChangedLine
import one.meshcore.shared.connection.blePoweredOnAttemptingLine
import one.meshcore.shared.connection.clientRepeat
import one.meshcore.shared.connection.isBluetoothPowerOnEdge
import one.meshcore.shared.connection.isConnectionWanted
import one.meshcore.shared.connection.readLastConnectionRadioId
import one.meshcore.shared.connection.setAppActive
import one.meshcore.shared.connection.setMultiAcks
import one.meshcore.shared.connection.setNodeName
import one.meshcore.shared.connection.setPathHashMode
import one.meshcore.shared.connection.setTxPowerVerified
import one.meshcore.shared.contact.ContactStaleNodeRemover
import one.meshcore.shared.contact.StaleNodeCleanupScheduler
import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.contact.StaleNodeRemoving
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.clearChatRouteIfMatching
import one.meshcore.shared.notification.clearDeliveredForActiveConversation
import one.meshcore.shared.persistence.resetAllRemoteNodeSessionConnections
import one.meshcore.shared.persistence.runRoom
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_sender_unknown
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.settings.AppliedRadioPresetStore
import one.meshcore.shared.settings.DebugLogBuffer
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogSource
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.simulator.DemoInlineImageSeeder
import one.meshcore.shared.tools.CliVerifiedWrites
import one.meshcore.shared.tools.RefreshingCliVerifiedWrites
import one.meshcore.shared.tools.ServiceToolsCliLocalBridge
import one.meshcore.shared.tools.loadChannelsForRxLogDecrypt
import one.meshcore.shared.transport.JvmTcpSocket
import one.meshcore.shared.transport.TcpMeshLink
import one.meshcore.shared.transport.bluetoothAdapterEnabled
import one.meshcore.shared.transport.hasBleConnectPermission
import one.meshcore.shared.transport.observeBluetoothAdapterState
import one.meshcore.shared.transport.systemConnectedBleHandles
import org.jetbrains.compose.resources.getString

internal suspend fun AppContainer.onChannelSlotOccupantChanged(radioID: Uuid, slots: Set<Int>) =
    withContext(Dispatchers.Main.immediate) {
        applyChannelSlotOccupantChanged(
            radioID = radioID,
            indices = slots,
            draftEditors = draftEditors,
            registry = offlineData.threadCoordinatorRegistry(radioID),
            generations = channelSlotGenerations,
        )
        val selected = coordinator.chatsSelectedRoute.value
        if (shouldCloseOpenChannelChat(selected, radioID, slots) { lookupStoredChannel(it) }) {
            coordinator.clearChatRouteIfMatching { it == selected }
        }
        invalidateLists()
    }

internal fun AppContainer.buildConnectionManager() = buildConnectionManager { appContext.hasBleConnectPermission() }

private fun AppContainer.buildConnectionManager(bluetoothConnectAuthorized: () -> Boolean) = ConnectionManager(
    database = database,
    scope = appScope,
    linkFactory = { deviceId -> buildBleLink(deviceId, reconnect = false, bluetoothConnectAuthorized) },
    sessionFactory = { link -> MeshCoreSession(link) },
    nowEpochSeconds = { System.currentTimeMillis() / AppContainer.MILLIS_PER_SECOND },
    ports = ConnectionManagerPorts(
        reconnectLinkFactory = { deviceId -> buildBleLink(deviceId, reconnect = true, bluetoothConnectAuthorized) },
        systemConnectedHandles = { appContext.systemConnectedBleHandles() },
        wifiLinkFactory = { host, port, heartbeat ->
            TcpMeshLink(
                host = host,
                port = port,
                socketFactory = { h: String, p: Int -> JvmTcpSocket(h, p) },
                scope = appScope,
                options = TcpMeshLink.Options(heartbeat = heartbeat),
            )
        },
        bluetoothAdapterEnabled = { appContext.bluetoothAdapterEnabled() },
        bluetoothConnectAuthorized = bluetoothConnectAuthorized,
        hasSystemPairingRegistry = { companionDevices.isAvailable },
        lastConnectionStore = lastConnectionStore,
        appliedRadioPresetStore = AppliedRadioPresetStore(prefs),
        intentStore = intentStore,
        notifier = notifier,
        reactionNotifier = notificationCoordinator,
        contactDiscoveryNotifier = notificationCoordinator,
        activeConversation = activeConversation,
        nodePasswordStore = nodePasswordStore,
        rxChannelCache = rxLogDecodeCache,
        slotOccupantChanged = ChannelSlotOccupantChanged { radioID, slots ->
            onChannelSlotOccupantChanged(radioID, slots)
        },
        connectionUi = connectionUi,
        onContactsChanged = { onContactsChanged() },
        onConversationsChanged = { invalidateLists() },
        discoveredNodes = discoveredNodeStoreImpl,
        rxLogStore = rxLogStoreImpl,
        debugLogStore = debugLogStore,
        shouldNotifyDiscovery = { !notificationCoordinator.isSuppressingNotifications },
        threadCoordinatorRegistry = { radioId -> offlineData.threadCoordinatorRegistry(radioId) },
        threadChatStore = { radioId -> offlineData.threadChatStore(radioId) },
        warmConversationPreviews = { conversation -> warmClosedConversationPreviews(conversation) },
        chatEnvInputs = { chatPrimeEnvInputs() },
        unknownSenderLabel = { getString(Res.string.chats_message_sender_unknown) },
        roomNameFallback = { getString(Res.string.contacts_node_kind_room) },
    ),
)

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun AppContainer.buildToolsCliLocal() = toolsCliLocal@{
    val session = connectionManager.services?.session ?: return@toolsCliLocal null
    // A CLI command runs in a text console, so the advert refresh may not raise a permission dialog
    // there — the same reason Swift `CLIToolView` passes `allowLocationPrompt: false`.
    ServiceToolsCliLocalBridge(
        session,
        advertSend = { sendSelfAdvert(it, allowLocationPrompt = false) },
        writes = RefreshingCliVerifiedWrites(
            inner = object : CliVerifiedWrites {
                override suspend fun setCoordinates(latitude: Double, longitude: Double) =
                    setDeviceCoordinates(latitude, longitude)

                override suspend fun applyRadioParams(
                    frequencyMHz: Double,
                    bandwidthKHz: Double,
                    spreadingFactor: Int,
                    codingRate: Int,
                    txPower: Int?,
                ) = connectionManager.applyManualRadioParams(
                    ManualRadioParams(
                        frequencyMHz = frequencyMHz,
                        bandwidthKHz = bandwidthKHz,
                        spreadingFactor = spreadingFactor,
                        codingRate = codingRate,
                        txPower = txPower,
                    ),
                )

                override suspend fun setTxPower(power: Int) = connectionManager.setTxPowerVerified(power)

                override suspend fun setName(name: String): String {
                    val adopted = connectionManager.setNodeName(name)
                    localNodeNameFlow.value = adopted
                    return adopted
                }

                override suspend fun setMultiAcks(value: Int) = connectionManager.setMultiAcks(value)

                override suspend fun setPathHashMode(mode: Int) =
                    connectedPathHashMode.applyVerifiedWrite(mode) { connectionManager.setPathHashMode(it) }
            },
            onVerified = { refreshSettingsSnapshotCache(connected = true) },
        ),
    )
}
internal fun AppContainer.buildAppLifecycle() = AppLifecycleController(
    scope = appScope,
    // Auto-reconnect must start the connectedDevice FGS first (RECON-1); otherwise a WiFi/BLE
    // reconnect after process death is silently killable and can throw without CHANGE_WIFI_STATE
    // (mitigated separately) when no BT grant is present.
    onActivate = {
        if (connectionManager.isConnectionWanted()) ConnectionService.start(appContext)
        appState.activate()
    },
    onForeground = {
        if (connectionManager.isConnectionWanted()) ConnectionService.start(appContext)
        appState.onEnterForeground()
        locationService.refreshAuthorization()
        notificationCoordinator.updateBadge()
        notificationCoordinator.clearDeliveredForActiveConversation()
        resumeAllOfflineMapPacks()
        refreshSendMessageShortcuts()
        refreshSendAdvertShortcuts()
        // Backgrounding stopped every room keep-alive; the room still on screen restarts its own.
        appForegroundEvents.emit(Unit)
    },
    onBackground = {
        appState.onEnterBackground()
        appScope.launch { draftEditors.flushAll() }
        appBackgroundEvents.tryEmit(Unit)
        // iOS flushes DebugLogBuffer.shared on background so a killed process
        // does not drop unflushed lines.
        appScope.launch { debugLogBuffer.flush() }
    },
    // A conversation stays claimed while the app is away, so the banner gate has to know the app is
    // no longer on screen; otherwise the thread the user last had open goes silent for the whole
    // background stretch. Mirrors iOS applying that gate only in the foreground willPresent hook.
    onForegroundChanged = {
        notificationCoordinator.isForeground = it
        appState.setAppActive(it)
    },
    // At the tail of launch, install the routing sinks and drain any URL or conversation tap the OS
    // staged pre-ready.
    onReady = {
        pendingExternalUrl.markReady { routeExternal(it) }
        pendingConversationOpen.markReady { openConversationFromNotification(it) }
        pendingContactOpen.markReady { openContactFromNotification(it) }
    },
)
internal fun AppContainer.startStoredChannelDecrypt() {
    storedChannelDecryptJob = appScope.launch {
        val radio = readLastConnectionRadioId(
            readRadioId = { lastConnectionStore.radioId() },
            warning = rxLogLogger::warning,
        ) ?: return@launch
        publishStoredRadioValueIfCurrent(
            seedRadio = radio,
            load = {
                loadChannelsForRxLogDecrypt(
                    fetch = { database.channelDao().listByRadio(radio).map { it.toDto() } },
                    logger = rxLogLogger,
                )
            },
            currentRadio = {
                readLastConnectionRadioId(
                    readRadioId = { lastConnectionStore.radioId() },
                    warning = rxLogLogger::warning,
                )
            },
            publish = { channels ->
                rxLogDecodeCache.updateChannels(channels)
                rxLogStoreImpl.redecrypt(rxLogDecodeCache.snapshot())
            },
        )
    }
    // iOS reconnects on CBCentralManager `.poweredOn`. A user who toggles Bluetooth
    // while the app stays up must not wait out the 30/60/120s backoff.
    var wasEnabled = appContext.bluetoothAdapterEnabled()
    appContext.observeBluetoothAdapterState {
        val enabled = appContext.bluetoothAdapterEnabled()
        if (enabled != wasEnabled) {
            connectionLogger.info(bleBluetoothStateChangedLine(enabled))
        }
        if (isBluetoothPowerOnEdge(wasEnabled, enabled)) {
            val armed = connectionManager.armBleReconnectIfWanted()
            if (armed) {
                connectionManager.state.value.deviceId?.let { handle ->
                    connectionLogger.info(blePoweredOnAttemptingLine(handle))
                }
            }
        }
        wasEnabled = enabled
    }
}

internal suspend fun <T> publishStoredRadioValueIfCurrent(
    seedRadio: Uuid,
    load: suspend () -> T?,
    currentRadio: suspend () -> Uuid?,
    publish: suspend (T) -> Unit,
): Boolean {
    val value = load() ?: return false
    if (currentRadio() != seedRadio) return false
    publish(value)
    return true
}

internal fun AppContainer.startDebugLogBuffer() {
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
        runStartupStep(settingsLogger::warning, DEMO_SEED_FAILED, fallback = {}) {
            DemoInlineImageSeeder.seed(linkPreviewCache, inlineImageDimensions)
        }
        // Live room/repeater logins do not survive process death
        // (iOS ServiceContainer.resetRemoteNodeConnections).
        runRoom(settingsLogger::warning, REMOTE_SESSION_RESET_FAILED, fallback = {}) {
            resetAllRemoteNodeSessionConnections(database)
        }
    }
}
internal fun AppContainer.startConnectionHeaderMirror() {
    // Keep the BLE-menu header name in step with the live connection: set it from the last-connection
    // store while a link is up, then clear it on disconnect.
    appScope.launch {
        appState.connectionState.collectLatest { state ->
            runStartupStepsIndependently(
                connectionLogger::warning,
                CONNECTION_HEADER_REFRESH_FAILED,
                {
                    // A connect stamps a new last-connected radioID; the offline partition has to follow it,
                    // or a later disconnect would serve the previous radio's rows.
                    offlineData.refresh()
                },
                { refreshSendMessageShortcuts() },
                { refreshSendAdvertShortcuts() },
                { RadioTileService.requestRefresh(appContext) },
                {
                    clientRepeatFlow.value = if (state.isConnected) connectionManager.clientRepeat() else false
                },
                { refreshSettingsSnapshotCache(connected = state.isConnected) },
                {
                    DebugLogBuffer.record(
                        DebugLogEntryDto.of(
                            id = Uuid.normalized(java.util.UUID.randomUUID().toString()),
                            timestamp = System.currentTimeMillis(),
                            level = DebugLogLevel.INFO,
                            source = DebugLogSource("io.pocketmesh.app", "Connection"),
                            message = "connectionState=$state",
                        ),
                    )
                },
            )
        }
    }
}
internal fun AppContainer.buildStaleNodeCleanupScheduler() = StaleNodeCleanupScheduler(
    preferences = staleCleanupPrefs,
    remover = StaleNodeRemoving { olderThanDays ->
        val radio = connectionManager.radioId
        val service = connectionManager.services?.contactService
        if (radio == null || service == null) {
            StaleNodeRemovalResult(removed = 0, total = 0)
        } else {
            ContactStaleNodeRemover(
                radioID = radio,
                contactStore = staleCleanupContactStore,
                contactService = service,
                nowSeconds = { (System.currentTimeMillis() / AppContainer.MILLIS_PER_SECOND).toLong() },
            ).removeStaleNodes(olderThanDays)
        }
    },
    nowSeconds = { (System.currentTimeMillis() / AppContainer.MILLIS_PER_SECOND).toLong() },
)

private const val DEMO_SEED_FAILED = "Demo seed failed"
private const val REMOTE_SESSION_RESET_FAILED = "Remote session reset failed"
private const val CONNECTION_HEADER_REFRESH_FAILED = "Connection header refresh failed"
