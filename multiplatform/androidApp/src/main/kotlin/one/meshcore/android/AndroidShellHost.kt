package one.meshcore.android

import android.app.Activity
import one.meshcore.shared.connection.CompanionDeviceRegistry
import one.meshcore.shared.contact.ui.QrCodeEncoder
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import one.meshcore.shared.navigation.SendMessageShortcutOutcome
import one.meshcore.shared.notification.removeDeliveredForChannel
import one.meshcore.shared.pathedit.toPathEditDiscoveredNode
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionController
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.ShellMaintenanceInputs
import one.meshcore.shared.ui.ShellPreferenceInputs
import one.meshcore.shared.ui.ShellRadioInputs
import one.meshcore.shared.ui.ShellServiceInputs
import one.meshcore.shared.ui.ShellSessionInputs
import one.meshcore.shared.ui.ShellSharingInputs

/**
 * Builds [ShellDependencies] from the process graph. Each surface is its own function so the
 * activity keeps a short wiring call.
 */
internal class AndroidShellFiles(
    val saveBackupDocument: suspend (ByteArray) -> String?,
    val pickBackupFileJson: suspend () -> String?,
    val pickNodeConfigJsonText: suspend () -> String?,
)

internal class AndroidShellHost(
    private val activity: Activity,
    private val container: AppContainer,
    private val permissions: PermissionController,
    private val companionDevices: CompanionDeviceRegistry,
    private val bridges: AndroidSystemBridges,
    private val files: AndroidShellFiles,
) {
    fun dependencies(onboardingComplete: Boolean): ShellDependencies = ShellDependencies(
        session = session(onboardingComplete),
        services = services(),
        sharing = sharing(),
        preferences = preferences(),
        radio = radio(),
        maintenance = maintenance(),
    )

    private fun session(onboardingComplete: Boolean): ShellSessionInputs = ShellSessionInputs(
        coordinator = container.coordinator,
        onboarding = container.onboarding,
        initialOnboardingComplete = onboardingComplete,
        statusPill = container.appState.statusPill,
        battery = container.appState.battery,
        batteryOcvArray = container.appState.batteryOcvArray,
        refreshBattery = container.appState::refreshBattery,
        connectionState = container.appState.connectionState,
        connectionAlert = container.appState.connectionAlert,
        shouldShowConnectPicker = container.appState.shouldShowConnectPicker,
        consumeConnectPicker = container.appState::consumeConnectPicker,
        consumeCompanionPairingOnPicker = container.appState::consumeCompanionPairingOnPicker,
        unreadCount = container.unreadCount,
        localNodeName = container.localNodeName,
        radioClientRepeat = container.clientRepeat,
        permissions = permissions,
        bleScan = container.bleScan,
        companionDevices = companionDevices,
        actions = container,
        contactService = container.contactService,
        conversationListStore = container.conversationListStore,
        conversationListInvalidations = container.conversationListInvalidations,
        messageStatusEvents = container.messageStatusEvents,
        roomServerEvents = container.roomServerEvents,
        onConversationsChanged = { container.notifyConversationsChanged() },
        contactListInvalidations = container.contactListInvalidations,
        prewarmConversationPreviews = { conversations ->
            container.chatPreviewPrewarmer.prewarmTop(conversations)
        },
        prefetchConversation = { conversation ->
            container.chatTimelinePrimer.prefetch(conversation)
        },
    )

    private fun services(): ShellServiceInputs = ShellServiceInputs(
        appForeground = container.appForeground,
        currentRadioId = container.currentRadioId,
        repeaterAdminService = container.repeaterAdminService,
        nodeSnapshotService = container.nodeSnapshotService,
        roomServerService = container.roomServerService,
        roomAdminService = container.roomAdminService,
        nodePasswordStore = container.nodePasswordStoreProvider,
        chatThread = container.chatThreadBinder,
        channelService = container.channelService,
        clearChannelNotifications = { channelIndex, radioID ->
            container.notificationCoordinator.removeDeliveredForChannel(radioID, channelIndex)
            container.notificationCoordinator.updateBadge()
        },
        updateBadge = { container.notificationCoordinator.updateBadge() },
        roomNotifications = container.roomNotificationSink,
        selfPublicKey = container.selfPublicKey,
        maxContacts = container.maxContacts,
        maxChannels = container.maxChannels,
        nowSeconds = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        qrEncode = QrCodeEncoder,
        contactScanner = { onDecoded, onDenied ->
            QrCameraScanner(
                permissions = permissions,
                onDecoded = onDecoded,
                onPermissionDenied = onDenied,
            )
        },
        channelScanner = { onDecoded, onDenied ->
            QrCameraScanner(
                permissions = permissions,
                onDecoded = onDecoded,
                onPermissionDenied = onDenied,
            )
        },
        blockedChannelSenderService = container.blockedChannelSenderServiceProvider,
        zeroHopPing = container.zeroHopPing,
        requestTelemetry = container.requestTelemetry,
        runTracePath = container.runTracePath,
        savedTracePathStore = container.savedTracePathStore,
        rxLogStore = container.rxLogStore,
        fetchNoiseFloorReading = container.fetchNoiseFloorReading,
        lineOfSightWorkspace = container.lineOfSightWorkspace,
        noiseFloorWorkspace = container.noiseFloorWorkspace,
        sendNodeDiscover = container.sendNodeDiscover,
        nodeDiscoveryWorkspace = container.nodeDiscoveryWorkspace,
        tracePathWorkspace = container.tracePathWorkspace,
        remoteAdminWorkspaces = container.remoteAdminWorkspaces,
        meshEvents = container.meshEvents,
        elevationService = container.elevationService,
        toolsCliRemote = container.toolsCliRemote,
        toolsCliLocal = container.toolsCliLocal,
    )

    private fun sharing(): ShellSharingInputs = ShellSharingInputs(
        backupWorkspace = container.backupWorkspace,
        readClipboard = bridges::readClipboardText,
        copyToClipboard = bridges::copyToClipboard,
        shareText = { text -> bridges.shareText(text) },
        shareTextWithSubject = { text, subject -> bridges.shareText(text, subject) },
        shareDebugLogs = bridges::shareDebugLogFile,
        shareImageBytes = bridges::shareImageBytes,
        exportBackup = { container.exportBackupDocument(files.saveBackupDocument) },
        enqueueSendMessage = { request ->
            container.enqueueSendMessage(request).also { outcome ->
                if (outcome == SendMessageShortcutOutcome.Queued) {
                    container.sendMessageShortcuts.reportUsed(request.targetId)
                }
            }
        },
        sendAdvertShortcut = { flood ->
            container.sendSelfAdvert(flood, allowLocationPrompt = false).also { outcome ->
                if (outcome == SendAdvertShortcutOutcome.Sent) {
                    container.sendAdvertShortcuts.reportUsed(flood)
                }
            }
        },
        importBackupFile = { files.pickBackupFileJson() },
        importBackup = { json -> container.importBackupJson(json) },
        exportDebugLogs = { container.exportDebugLogsText() },
        clearDebugLogs = { container.clearDebugLogs() },
        openAppSettings = bridges::openAppSettings,
        openAppLanguageSettings = bridges::openAppLanguageSettings,
        openBluetoothSettings = bridges::openBluetoothSettings,
        openBatteryOptimizationSettings = bridges::openBatteryOptimizationSettings,
        scanHaptic = bridges::performScanHaptic,
        routeMeshCoreLink = container::submitDeepLink,
        openExternalUrl = container::openExternalUrl,
        presentSystemTranslation = bridges::presentSystemTranslation,
    )

    private fun preferences(): ShellPreferenceInputs = ShellPreferenceInputs(
        supportsSimulatorConnect = false,
        pathEditPreferences = container.pathEditPreferences,
        chatDisplayPreferences = container.chatDisplayPreferences,
        translationTargetPreferences = container.translationTargetPreferences,
        notificationPreferences = container.notificationPreferences,
        appearancePreferences = container.appearancePreferences,
        mapDisplayPreferences = container.mapDisplayPreferences,
        locationService = container.locationService,
        whatsNewStore = container.whatsNewStore,
        coachMarkStore = container.coachMarkStore,
        demoModeStore = container.demoModeStore,
        offlineMapPackRegistry = container.offlineMapPackRegistry,
        onOfflineMapPacksChanged = { container.persistOfflineMapPacks() },
        startOfflineMapDownload = container::startOfflineMapDownload,
        offlineMapError = container.offlineMapError,
        clearOfflineMapError = container::clearOfflineMapError,
        loadMapCameraRegion = container::loadMapCameraRegionForOfflinePicker,
        deleteOfflineMapPack = container::deleteOfflineMapPack,
        pauseOfflineMapPack = container::pauseOfflineMapPack,
        resumeOfflineMapPack = container::resumeOfflineMapPack,
        offlineMapAvailableBytes = container::offlineMapAvailableBytes,
        offlineMapDatabaseSizeBytes = container::offlineMapDatabaseSizeBytes,
        entitlementStore = container.entitlementStore,
        billingClient = container.billingClient,
        entitlementReconciler = container.entitlementReconciler,
        isNetworkOffline = container.isNetworkOffline,
        networkOffline = container.networkAvailability.offline,
    )

    private fun radio(): ShellRadioInputs = ShellRadioInputs(
        discoveredNodeStore = container.discoveredNodeStore,
        radioSnapshot = container.radioSnapshot,
        settingsStartupReadsAllowed = container.appState.settingsStartupReadsAllowed,
        refreshDeviceSettings = container::refreshDeviceSettings,
        nodesAutoAddSnapshot = container.nodesAutoAddSnapshot,
        applyNodesAutoAdd = container::applyNodesAutoAdd,
        regionSelection = container.regionSelection,
        setRegionSelection = container::setRegionSelection,
        resolveRegion = container::resolveRegion,
        applyRadioPreset = container::applyRadioPreset,
        applyManualRadioParams = container::applyManualRadioParams,
        connectionSettingsSnapshot = container.connectionSettingsSnapshot,
        setBlePin = container::setBlePin,
        updateWifiEndpoint = container::updateWifiEndpoint,
        deviceOcvSnapshot = container.deviceOcvSnapshot,
        updateDeviceOcv = container::updateDeviceOcv,
        locationSettingsSnapshot = container.locationSettingsSnapshot,
        setShareLocationPublicly = container::setShareLocationPublicly,
        setAdvertLocationPolicy = container::setAdvertLocationPolicy,
        setDeviceCoordinates = container::setDeviceCoordinates,
        connectedDeviceId = container.connectedDeviceId,
        readDeviceGpsState = container::readDeviceGpsState,
        setDeviceGpsEnabled = container::setDeviceGpsEnabled,
        setNodeName = container::setNodeName,
        deviceHashSize = container.deviceHashSize,
        pathEditDiscoveredNodes = { radioId ->
            container.discoveredNodeStore()?.list(radioId)?.map {
                it.toPathEditDiscoveredNode()
            }
                ?: emptyList()
        },
        deviceOtherParamsSnapshot = container.deviceOtherParamsSnapshot,
        setTelemetryModes = container::setTelemetryModes,
        setMultiAcks = container::setMultiAcks,
        setPathHashMode = container::setPathHashMode,
        knownRegions = container.knownRegions,
        defaultFloodScopeName = container.defaultFloodScopeName,
        applyDefaultFloodScope = container::applyDefaultFloodScope,
        addKnownRegion = container::addKnownRegion,
        removeKnownRegion = container::removeKnownRegion,
        discoverNearbyRegions = container::runRegionDiscoveryForChannelInfo,
        regionDiscoverMessage = container::regionDiscoverStatusMessage,
        rebootDevice = container::rebootDevice,
        enableClientRepeat = container::enableClientRepeat,
        disableClientRepeat = container::disableClientRepeat,
        applyRepeatPreset = container::applyRepeatPreset,
    )

    private fun maintenance(): ShellMaintenanceInputs {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return ShellMaintenanceInputs(
            nodeConfigImportWorkspace = container.nodeConfigImportWorkspace,
            importPrivateKey = container.importPrivateKey,
            staleCleanupThresholdDays = container.staleCleanupThresholdDays,
            onStaleCleanupThresholdChange = container::setStaleCleanupThresholdDays,
            lastStaleCleanupSeconds = container.lastStaleCleanupSeconds,
            unfavoritedCount = container::unfavoritedCount,
            removeUnfavorited = container::removeUnfavorited,
            forgetKeepData = container::forgetKeepData,
            forgetDeleteData = container::forgetDeleteData,
            factoryReset = container::factoryReset,
            nodeConfigService = container.nodeConfigService,
            shareNodeConfigJson = bridges::shareNodeConfigJson,
            pickNodeConfigJson = { files.pickNodeConfigJsonText() },
            nodeConfigExportTimestamp = { localExportTimestamp() },
            isNotificationsAuthorized = container::isNotificationsAuthorized,
            // Never asked is not the same as denied: only the latter needs a trip to system settings.
            isNotificationPermissionDetermined = {
                OnboardingPermission.NOTIFICATIONS in permissions.state.value.determined
            },
            onRequestNotificationPermission = { permissions.request(OnboardingPermission.NOTIFICATIONS) },
            onOpenSystemNotificationSettings = bridges::openSystemNotificationSettings,
            appVersionName = packageInfo.versionName,
            appBuildNumber = packageInfo.appBuildNumber(),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
