// AndroidOnly: WP-303 Builds the production AppContainer from the Android context.
package com.meshcoreone.android.app.container

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.meshcoreone.android.app.state.ProcessForegroundState
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemConnectivityClock
import com.meshcoreone.android.core.connectivity.SystemLinkAdopter
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.CompanionPairingService
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.bond.BondingCoordinator
import com.meshcoreone.android.core.connectivity.bond.PlatformBondInspector
import com.meshcoreone.android.core.connectivity.permissions.AndroidPermissionState
import com.meshcoreone.android.core.connectivity.platform.AndroidBleScanGateway
import com.meshcoreone.android.core.connectivity.platform.AndroidBondGateway
import com.meshcoreone.android.core.connectivity.platform.AndroidCompanionDeviceGateway
import com.meshcoreone.android.core.connectivity.platform.AndroidSystemLinkProbe
import com.meshcoreone.android.core.connectivity.service.AndroidForegroundServiceStarter
import com.meshcoreone.android.core.ble.AndroidGattFacade
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.app.content.AndroidGeocoderAdapter
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.app.container.onboarding.AndroidOnboardingPermissionFacts
import com.meshcoreone.android.app.container.onboarding.OnboardingPlatform
import com.meshcoreone.android.app.container.onboarding.WriteBehindOnboardingFlags
import com.meshcoreone.android.app.container.onboarding.appSettingsOpener
import com.meshcoreone.android.core.services.content.LocationService
import com.meshcoreone.android.core.services.content.RegionResolver
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.core.services.rendering.DraftStore
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import com.meshcoreone.android.platform.notifications.messaging.AndroidNotificationStringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.meshcoreone.android.core.maps.MapLibreOfflineBackend
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.OfflineLayer
import com.meshcoreone.android.core.maps.OfflineLayerPolicy
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.maps.UnavailableOfflineMapBackend
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.nodes.deps.Announcer
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.UserFacingMessages

/**
 * The production composition of [AppContainer]. Implemented platform services are bound here; remaining unavailable
 * roles and their owners are listed in WP-303.md.
 */
object AndroidAppContainerFactory {
    suspend fun create(application: Application, mainScope: CoroutineScope, foreground: ProcessForegroundState): AppContainer {
        val cleanup = ArrayDeque<suspend () -> Unit>()
        suspend fun closeResources() {
            var failure: Exception? = null
            while (cleanup.isNotEmpty()) {
                try { cleanup.removeFirst().invoke() }
                catch (problem: Exception) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }
        try {
        val storage = MeshCoreStorage.get(application)
        val themeService = com.meshcoreone.android.core.designsystem.ThemeService.create(storage.preferences, mainScope)
        cleanup.addFirst { themeService.close() }
        // Demo mode reads its flags synchronously: bind the loaded snapshot before the first `shared` access.
        DemoModeManager.installStandardDefaults(DataStoreDemoModeDefaults.load(storage.preferences, mainScope))
        val storeScope = CoroutineScope(SupervisorJob(mainScope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.IO)
        cleanup.addFirst { storeScope.cancel() }
        val store = openRoomDatabaseOnIo(
            openDatabase = { MeshCoreDatabase.open(application) },
            transferOwnership = { RoomPersistenceStore(it, storeScope) },
        )
        cleanup.addFirst { store.close() }
        val diagnostics = ConnectivityDiagnostics { operation, failure ->
            java.util.logging.Logger.getLogger("com.mc1.Connectivity").log(java.util.logging.Level.WARNING, operation, failure)
        }
        val clock = SystemConnectivityClock()
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        val offlineMaps = OfflineMapController(
            backend = if (MapLibreRuntime.isSupported) {
                MapLibreOfflineBackend(application, MapLibreOpenFreeMap.STYLE_URI, mainScope)
            } else {
                UnavailableOfflineMapBackend
            },
            policies = mapOf(
                OfflineLayer.BASE to OfflineLayerPolicy(
                    layer = OfflineLayer.BASE,
                    maxDownloadZoom = OfflineLayer.BASE.maxZoom,
                    attribution = MapLibreOpenFreeMap.attribution,
                ),
            ),
            availableBytes = { application.filesDir.usableSpace },
            networkAvailable = {
                connectivityManager.activeNetwork
                    ?.let(connectivityManager::getNetworkCapabilities)
                    ?.let {
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } == true
            },
        )

        val companionGateway = AndroidCompanionDeviceGateway(application, diagnostics)
        val companionScope = CoroutineScope(SupervisorJob(mainScope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        cleanup.addFirst { companionScope.cancel() }
        val companionService = CompanionSetupService(
            companionGateway, companionScope, clock, diagnostics, records = SharedPreferencesAssociationRecords(application),
        )
        val pairing: DevicePairingService = if (companionGateway.isSupported) {
            CompanionPairingService(companionService, diagnostics)
        } else {
            BluetoothScanPairingService()
        }
        val probe = AndroidSystemLinkProbe(application, diagnostics)
        val scanCoordinator = BleScanCoordinator(AndroidBleScanGateway(application, diagnostics), diagnostics)
        val endpoints = SharedPreferencesKnownEndpoints(application)
        val linkFactory = AndroidRuntimeLinkFactory({ handle -> AndroidGattFacade(application, handle) })
        val inspector = BleLinkInspector(probe, linkFactory::currentBleLink) {
            com.meshcoreone.android.app.platform.bluetoothAvailability(application)
        }
        val connectivity = ConnectivityPlatform(pairing, inspector, endpoints, SystemLinkAdopter { false })

        val bondGateway = AndroidBondGateway(application, diagnostics)
        val bonding = BondingCoordinator(bondGateway, clock)
        val addresses = java.util.concurrent.ConcurrentHashMap<java.util.UUID, String>()
        val holder = java.util.concurrent.atomic.AtomicReference<AppContainer>()
        val contentHttp = com.meshcoreone.android.app.content.OkHttpContentFetching()
        cleanup.addFirst { contentHttp.close() }
        val contentScope = CoroutineScope(mainScope.coroutineContext + SupervisorJob(mainScope.coroutineContext[kotlinx.coroutines.Job]))
        cleanup.addFirst { contentScope.cancel() }
        val chatContent = AppChatContent(application, store, contentHttp,
            com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource.create(storage.preferences, contentScope),
            storage.preferences, storage.preferences.snapshot(), contentScope)
        cleanup.addFirst { chatContent.close() }
        val notificationDelivery = AndroidMessagingNotificationDelivery(application, mainScope) { response ->
            val current = holder.get() ?: return@AndroidMessagingNotificationDelivery false
            val session = current.sessions.current ?: return@AndroidMessagingNotificationDelivery false
            if (current.appState.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY) {
                return@AndroidMessagingNotificationDelivery false
            }
            session.notificationService.didReceive(response)
            true
        }
        val environment = AndroidHostEnvironment(
            application,
            reconnecting = { holder.get()?.connectionManager?.reconnectionCoordinator?.reconnectingDeviceId != null },
            lastDevice = { holder.get()?.connectionPort?.lastConnectedDeviceId },
            lan = {
                val current = holder.get()
                usesLanHosting(current?.currentTransport, current?.connectionManager?.connectedDevice?.connectionMethods.orEmpty())
            },
        )
        val locationProducing = LocationManagerLocationProducing(application)
        cleanup.addFirst { locationProducing.close() }
        val onboardingPlatform = OnboardingPlatform(
            flags = WriteBehindOnboardingFlags.load(storage.preferences, mainScope),
            permissionFacts = AndroidOnboardingPermissionFacts(application),
            regionResolver = RegionResolver(LocationService(locationProducing), AndroidGeocoderAdapter(application)),
            locationReporter = locationProducing,
            scans = scanCoordinator,
            openAppSettings = appSettingsOpener(application),
        )
        val chatSendQueueProgress = ChatSendQueueProgress()
        val container = AppContainer(
            AppContainerDependencies(
                store = store,
                passwords = SecretStoreVault(storage.secrets),
                connectionPreferences = DataStoreConnectionPreferences(storage.preferences),
                connectivity = connectivity,
                linkProbe = probe,
                scans = scanCoordinator,
                linkFactory = linkFactory,
                chatSendQueueProgress = chatSendQueueProgress,
                hostingStarter = AndroidForegroundServiceStarter(application),
                hostEnvironment = environment,
                ensureBonded = { deviceId ->
                    connectivity.endpointFor(deviceId)?.address?.let { address ->
                        addresses[deviceId] = address
                        bonding.ensureBonded(address)
                    }
                },
                bonds = PlatformBondInspector(android.os.Build.VERSION.SDK_INT, bondGateway) { addresses[it] },
                permissionSnapshot = AndroidPermissionState(application)::snapshot,
                connectivityDiagnostics = diagnostics,
                companionSetup = companionService.takeIf { companionGateway.isSupported },
                chooserResult = companionGateway::onChooserResult,
                refreshAssociations = { (pairing as? CompanionPairingService)?.let { companionService.refreshAssociations() } },
                notificationDelivery = notificationDelivery,
                notificationPreferences = storage.notificationPreferences(),
                notificationStrings = AndroidNotificationStringProvider(application),
                contactPreferences = SharedPreferencesContactFlags(application),
                draftStore = DraftStore(SharedPreferencesDraftDefaults(application)),
                mainScope = mainScope,
                onboardingPlatform = onboardingPlatform,
                offlineMaps = offlineMaps,
                nodesMessages = UserFacingMessages { failure ->
                    UiErrorMapper().message(failure).resolve(application.resources)
                },
                nodesAnnouncer = Announcer { message ->
                    announceForAccessibility(application, message.resolve(application))
                },
                nodesPreferences = SharedPreferencesStringLists(application),
                lineOfSight = createLineOfSightFeatureDependencies(application, contentHttp) { holder.get()?.appState },
                themeService = themeService,
                chatContent = chatContent,
                backupEngine = AppBackupFeature(
                    com.meshcoreone.android.core.data.backup.AppBackupService(
                        storage.backupPreferences,
                        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "",
                        application.packageManager.getPackageInfo(application.packageName, 0).longVersionCode.toString(),
                    ),
                    store,
                    restored = { result ->
                        val current = checkNotNull(holder.get()) { "Restore requires the initialized process container" }
                        result.channelSlotsAffectedByImport.forEach { (radio, slots) ->
                            current.appState.handleChannelSlotOccupantChanged(
                                com.meshcoreone.android.core.model.RadioId(radio), slots.map { it.toUByte() }.toSet(),
                            )
                        }
                        if (result.hasRestoredChanges) {
                            current.appState.bumpContactsVersion()
                            current.appState.refreshConversations()
                            current.appState.notifyDataRestored()
                        }
                    },
                ),
                foreground = foreground,
                knownEndpoints = endpoints,
                regionStore = DataStoreRegionSelectionStore(storage.preferences),
                stalePreferences = DataStoreStaleCleanupPreferences(storage.preferences),
                newBootstrapDebugLog = { scope -> com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer(store, scope) },
                sessionLifecycle = object : SessionLifecycleListener {
                    override fun created(container: RadioSessionContainer) {
                        notificationDelivery.retryPendingActions()
                    }
                },
                onClose = {
                    // The Room database is process-owned (`RoomPersistenceStore.close` flushes and seals the store only);
                    // it closes with the process, and this module cannot reference `RoomDatabase.close`.
                    closeResources()
                },
            ),
        )
        holder.set(container)
        try {
            container.settingsFeature = AppSettingsFeature.create(container, storage)
            notificationDelivery.retryPendingActions()
            return container
        } catch (failure: Exception) {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { container.close() } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        } catch (failure: Exception) {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { closeResources() } }
            catch (closing: Exception) { failure.addSuppressed(closing) }
            throw failure
        }
    }
}

internal suspend fun <Owner> openRoomDatabaseOnIo(
    openDatabase: () -> MeshCoreDatabase,
    transferOwnership: (MeshCoreDatabase) -> Owner,
): Owner {
    var opened: MeshCoreDatabase? = null
    var ownershipTransferred = false
    var primaryFailure: Throwable? = null
    try {
        val database = withContext(Dispatchers.IO) {
            openDatabase().also { opened = it }
        }
        currentCoroutineContext().ensureActive()
        val owner = transferOwnership(database)
        ownershipTransferred = true
        return owner
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        if (!ownershipTransferred) {
            try {
                withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
            } catch (cleanup: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }
}

private fun announceForAccessibility(context: Context, text: String) {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    if (!manager.isEnabled) return
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    event.packageName = context.packageName
    event.className = AndroidAppContainerFactory::class.java.name
    event.text.add(text)
    manager.sendAccessibilityEvent(event)
}

private fun NodesMessage.resolve(context: Context): String = when (this) {
    is NodesMessage.Text -> value
    is NodesMessage.Joined -> parts.joinToString(separator) { it.resolve(context) }
    is NodesMessage.Resource -> context.getString(
        id,
        *args.map { if (it is NodesMessage) it.resolve(context) else it }.toTypedArray(),
    )
}
