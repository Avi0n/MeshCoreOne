package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.contact.DEFAULT_HASH_SIZE
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_select_setting
import one.meshcore.shared.ui.PlaceholderPane
import org.jetbrains.compose.resources.stringResource

/** The selected settings page. The hub list and the empty wide pane stay on [SettingsHubScreen]. */
@Composable
internal fun SettingsHubDetail(
    selected: SettingsDestination,
    inputs: SettingsHubDetailInputs,
    innerPadding: PaddingValues,
    onSelect: (SettingsDestination?) -> Unit,
) {
    when (selected) {
        SettingsDestination.DEVICE_INFO,
        SettingsDestination.PUBLIC_KEY,
        SettingsDestination.RADIO,
        SettingsDestination.PRESET_LOCATION,
        SettingsDestination.CONNECTION,
        SettingsDestination.LOCATION,
        SettingsDestination.ADVANCED,
        -> SettingsHubDetailDevice(selected, inputs, innerPadding, onSelect)
        SettingsDestination.REGION_MANAGEMENT,
        SettingsDestination.CONFIG_EXPORT,
        SettingsDestination.CONFIG_IMPORT,
        SettingsDestination.TRUSTED_CONTACTS,
        SettingsDestination.BACKUP,
        SettingsDestination.APPEARANCE,
        SettingsDestination.SUPPORT,
        SettingsDestination.DANGER_ZONE,
        SettingsDestination.FEEDBACK,
        SettingsDestination.DIAGNOSTICS,
        -> SettingsHubDetailTools(selected, inputs, innerPadding, onSelect)
        SettingsDestination.CHATS,
        SettingsDestination.LANGUAGE,
        SettingsDestination.TRANSLATE_INTO,
        SettingsDestination.BLOCKED_CONTACTS,
        SettingsDestination.BLOCKED_CHANNEL_SENDERS,
        SettingsDestination.NOTIFICATIONS,
        SettingsDestination.MAPS,
        SettingsDestination.OFFLINE_MAPS,
        -> SettingsHubDetailPreferences(selected, inputs, innerPadding, onSelect)
    }
}

@Composable
private fun SettingsHubDetailDevice(
    selected: SettingsDestination,
    inputs: SettingsHubDetailInputs,
    innerPadding: PaddingValues,
    onSelect: (SettingsDestination?) -> Unit,
) {
    when (selected) {
        SettingsDestination.DEVICE_INFO -> DeviceInfoSettingsScreen(
            host = inputs,
            onOpenPublicKey = { onSelect(SettingsDestination.PUBLIC_KEY) },
            onMaxRetriesExceeded = { onSelect(selected.backParent) },
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.PUBLIC_KEY -> {
            val key = inputs.selfPublicKey()
            if (key != null) {
                PublicKeyScreen(
                    publicKey = key,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                SettingsNoDeviceEmpty(
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }
        SettingsDestination.RADIO -> RadioSettingsScreen(
            host = inputs,
            onOpenPresetLocation = { onSelect(SettingsDestination.PRESET_LOCATION) },
            onMaxRetriesExceeded = { onSelect(selected.backParent) },
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.PRESET_LOCATION -> PresetLocationDestination(
            region = inputs.regionSelection(),
            locationService = inputs.locationService,
            onSetRegionSelection = inputs.setRegionSelection,
            resolveRegion = inputs.resolveRegion,
            onOpenAppSettings = inputs.onOpenAppSettings,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.CONNECTION -> ConnectionSettingsScreen(
            snapshot = inputs.connectionSettingsSnapshot(),
            connectionState = inputs.connectionState,
            onSaveBlePin = inputs.setBlePin,
            onSaveWifi = inputs.updateWifiEndpoint,
            onOpenBatteryOptimization = inputs.onOpenBatteryOptimization,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.LOCATION -> LocationSettingsScreen(
            host = inputs,
            onMaxRetriesExceeded = { onSelect(selected.backParent) },
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.ADVANCED -> AdvancedSettingsScreen(
            host = inputs,
            onOpenTrustedContacts = { onSelect(SettingsDestination.TRUSTED_CONTACTS) },
            onOpenConfigExport = { onSelect(SettingsDestination.CONFIG_EXPORT) },
            onOpenConfigImport = { onSelect(SettingsDestination.CONFIG_IMPORT) },
            onOpenRegionManagement = { onSelect(SettingsDestination.REGION_MANAGEMENT) },
            onLeaveAfterDestructive = { onSelect(null) },
            onMaxRetriesExceeded = { onSelect(selected.backParent) },
            modifier = Modifier.padding(innerPadding),
        )
        else -> error("Unhandled settings destination: $selected")
    }
}

@Composable
private fun SettingsHubDetailTools(
    selected: SettingsDestination,
    inputs: SettingsHubDetailInputs,
    innerPadding: PaddingValues,
    onSelect: (SettingsDestination?) -> Unit,
) {
    when (selected) {
        SettingsDestination.REGION_MANAGEMENT -> RegionManagementScreen(
            knownRegions = inputs.knownRegions(),
            onBack = { onSelect(SettingsDestination.ADVANCED) },
            onRemoveRegion = inputs.removeKnownRegion,
            onAddRegion = inputs.addKnownRegion,
            onDiscover = inputs.discoverNearbyRegions,
            discoverMessage = inputs.regionDiscoverMessage,
            modifier = Modifier.padding(innerPadding),
            showAppBar = false,
        )
        SettingsDestination.CONFIG_EXPORT -> NodeConfigExportScreen(
            nodeConfigService = inputs.nodeConfigService,
            deviceNodeName = { inputs.localNodeName.value },
            onShareJson = inputs.shareNodeConfigJson,
            exportTimestamp = inputs.nodeConfigExportTimestamp,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.CONFIG_IMPORT -> NodeConfigImportScreen(
            nodeConfigService = inputs.nodeConfigService,
            radioId = inputs.currentRadioId,
            deviceNodeName = { inputs.localNodeName.value },
            pickJsonFile = inputs.pickNodeConfigJson ?: { null },
            onImportApplied = { inputs.refreshDeviceSettings?.invoke() },
            workspace = inputs.nodeConfigImportWorkspace,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.TRUSTED_CONTACTS -> TrustedContactsPickerScreen(
            contactService = inputs.contactService(),
            radioId = inputs.currentRadioId(),
            connectionReady = inputs.isDeviceConnected,
            isRadioConnected = { inputs.connectionState.value.isConnected },
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.BACKUP -> {
            val deviceState by inputs.connectionState.collectAsStateWithLifecycle()
            BackupSettingsScreen(
                isRadioConnected = deviceState.isRadioLinked,
                onExportConfirmed = inputs.onExportBackup,
                onImportFile = inputs.onImportBackupFile,
                onImportConfirmed = inputs.onImportBackup,
                workspace = inputs.backupWorkspace,
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.APPEARANCE -> SettingsHubStorePage(
            store = inputs.appearancePreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            AppearanceSettingsScreen(
                store = store,
                entitlements = inputs.entitlementStore,
                onPurchaseMoreThemes = purchaseMoreThemesAction(onSelect),
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.SUPPORT -> SettingsHubSupportPage(inputs, innerPadding)
        SettingsDestination.DANGER_ZONE -> DangerZoneSettingsScreen(
            isDeviceConnected = inputs.isDeviceConnected,
            onForgetKeepData = inputs.onForgetKeepData,
            onForgetDeleteData = inputs.onForgetDeleteData,
            onFactoryReset = inputs.onFactoryReset,
            onLeaveAfterDestructive = { onSelect(null) },
            unfavoritedCount = inputs.unfavoritedCount,
            onRemoveUnfavorited = inputs.onRemoveUnfavorited,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.FEEDBACK -> FeedbackSettingsScreen(
            onOpenUrl = inputs.onOpenUrl,
            onExportLogs = inputs.onExportDebugLogs,
            onClearLogs = inputs.onClearDebugLogs,
            onShareExport = inputs.onShareDebugLogs,
            modifier = Modifier.padding(innerPadding),
        )
        SettingsDestination.DIAGNOSTICS -> DiagnosticsSettingsScreen(
            onExportLogs = inputs.onExportDebugLogs,
            onClearLogs = inputs.onClearDebugLogs,
            onShareExport = inputs.onShareDebugLogs,
            modifier = Modifier.padding(innerPadding),
        )
        else -> error("Unhandled settings destination: $selected")
    }
}

private fun purchaseMoreThemesAction(onSelect: (SettingsDestination?) -> Unit): (() -> Unit)? =
    if (SideloadDistribution.BILLING_ENABLED) {
        { onSelect(SettingsDestination.SUPPORT) }
    } else {
        null
    }

@Composable
private fun SettingsHubSupportPage(inputs: SettingsHubDetailInputs, innerPadding: PaddingValues) {
    if (SideloadDistribution.BILLING_ENABLED) {
        SupportSettingsScreen(
            demoMode = inputs.demoModeStore,
            billing = inputs.billingClient,
            entitlements = inputs.entitlementStore,
            reconciler = inputs.entitlementReconciler,
            onContactSupport = inputs.onContactSupport,
            onRequestRefund = { inputs.onOpenUrl(THEME_BUNDLE_REFUND_URL) },
            modifier = Modifier.padding(innerPadding),
        )
    } else {
        SideloadDonationScreen(
            onOpenUrl = inputs.onOpenUrl,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun SettingsHubDetailPreferences(
    selected: SettingsDestination,
    inputs: SettingsHubDetailInputs,
    innerPadding: PaddingValues,
    onSelect: (SettingsDestination?) -> Unit,
) {
    when (selected) {
        SettingsDestination.CHATS -> SettingsHubStorePage(
            store = inputs.chatDisplayPreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            ChatSettingsScreen(
                store = store,
                onOpenBlockedContacts = { onSelect(SettingsDestination.BLOCKED_CONTACTS) },
                onOpenBlockedChannelSenders = { onSelect(SettingsDestination.BLOCKED_CHANNEL_SENDERS) },
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.LANGUAGE -> SettingsHubStorePage(
            store = inputs.translationTargetPreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            LanguageSettingsScreen(
                store = store,
                onOpenAppLanguage = inputs.onOpenLanguageSettings,
                onOpenTranslateInto = { onSelect(SettingsDestination.TRANSLATE_INTO) },
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.TRANSLATE_INTO -> SettingsHubStorePage(
            store = inputs.translationTargetPreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            TranslateIntoLanguageScreen(
                store = store,
                onBack = { onSelect(SettingsDestination.LANGUAGE) },
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.BLOCKED_CONTACTS ->
            one.meshcore.shared.contact.ui.BlockedContactsScreen(
                contactService = inputs.contactService,
                radioId = inputs.currentRadioId,
                onBack = { onSelect(SettingsDestination.CHATS) },
                onContactTap = { inputs.onBlockedContactTap(it.id.value) },
                nowSeconds = inputs.nowSeconds(),
                hashSize = inputs.deviceHashSize() ?: DEFAULT_HASH_SIZE,
                listInvalidations = inputs.contactListInvalidations,
                modifier = Modifier.padding(innerPadding),
                showAppBar = false,
            )
        SettingsDestination.BLOCKED_CHANNEL_SENDERS ->
            one.meshcore.shared.contact.ui.BlockedChannelSendersScreen(
                service = inputs.blockedChannelSenderService,
                radioId = inputs.currentRadioId,
                onBack = { onSelect(SettingsDestination.CHATS) },
                modifier = Modifier.padding(innerPadding),
                showAppBar = false,
            )
        SettingsDestination.NOTIFICATIONS -> SettingsHubStorePage(
            store = inputs.notificationPreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            NotificationSettingsScreen(
                preferences = store,
                isNotificationsAuthorized = inputs.isNotificationsAuthorized,
                isNotificationPermissionDetermined = inputs.isNotificationPermissionDetermined,
                onRequestNotificationPermission = inputs.onRequestNotificationPermission,
                onOpenSystemNotificationSettings = inputs.onOpenSystemNotificationSettings,
                permissionState = inputs.notificationPermissionState,
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.MAPS -> SettingsHubStorePage(
            store = inputs.mapDisplayPreferences,
            selected = selected,
            innerPadding = innerPadding,
        ) { store ->
            MapsSettingsScreen(
                store = store,
                onOpenOfflineMaps = { onSelect(SettingsDestination.OFFLINE_MAPS) },
                modifier = Modifier.padding(innerPadding),
            )
        }
        SettingsDestination.OFFLINE_MAPS -> OfflineMapsSettingsScreen(
            registry = inputs.offlineMapPackRegistry ?: OfflineMapPackRegistry(),
            mapDisplayPreferences = inputs.mapDisplayPreferences,
            onStartDownload = inputs.startOfflineMapDownload,
            onDeletePack = inputs.deleteOfflineMapPack,
            onPausePack = inputs.pauseOfflineMapPack,
            onResumePack = inputs.resumeOfflineMapPack,
            onRegistryChanged = inputs.onOfflineMapPacksChanged,
            nowEpochSeconds = inputs.nowSeconds,
            loadMapCameraRegion = inputs.loadMapCameraRegion,
            availableBytes = inputs.offlineMapAvailableBytes,
            databaseSizeBytes = inputs.offlineMapDatabaseSizeBytes,
            isNetworkAvailable = inputs.isOfflineMapNetworkAvailable,
            downloadError = inputs.offlineMapDownloadError,
            onDownloadErrorShown = inputs.onOfflineMapDownloadErrorShown,
            modifier = Modifier.padding(innerPadding),
        )
        else -> error("Unhandled settings destination: $selected")
    }
}

/** Renders [content] when the preference store exists. A missing store shows the destination placeholder. */
@Composable
private fun <T : Any> SettingsHubStorePage(
    store: T?,
    selected: SettingsDestination,
    innerPadding: PaddingValues,
    content: @Composable (T) -> Unit,
) {
    if (store != null) {
        content(store)
    } else {
        PlaceholderPane(
            title = stringResource(settingsHubTitleResource(selected)),
            subtitle = stringResource(Res.string.settings_select_setting),
            modifier = Modifier.padding(innerPadding),
        )
    }
}
