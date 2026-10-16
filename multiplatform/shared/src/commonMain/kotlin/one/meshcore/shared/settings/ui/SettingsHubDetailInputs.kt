package one.meshcore.shared.settings.ui

import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.billing.BillingClient
import one.meshcore.shared.billing.EntitlementReconciler
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.map.CoordinateBounds
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.OfflineMapPack
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.StoredNotificationPreferences
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.TranslationTargetPreferencesStore
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.ui.PermissionUiState

/**
 * Fields the Advanced page reads from [SettingsHubDetailInputs]. Navigation between settings
 * destinations stays on the hub, because only the hub knows the parent route.
 */
internal interface AdvancedSettingsHost {
    val radioSnapshot: () -> RadioDeviceSnapshot?
    val nodesAutoAddSnapshot: () -> NodesAutoAddSnapshot?
    val applyNodesAutoAdd: suspend (NodesAutoAddDraft) -> Unit
    val deviceOtherParamsSnapshot: () -> DeviceOtherParamsSnapshot?
    val setTelemetryModes: suspend (Int, Int, Int) -> Unit
    val setMultiAcks: suspend (Int) -> Unit
    val isDeviceConnected: Boolean
    val staleCleanupThresholdDays: Int
    val onStaleCleanupThresholdChange: (Int) -> Unit
    val lastStaleCleanupSeconds: Long
    val nowSeconds: () -> Long
    val deviceOcvSnapshot: () -> OcvEditorState?
    val updateDeviceOcv: (suspend (OCVPreset, List<Int>) -> Unit)?
    val knownRegions: () -> List<String>
    val defaultFloodScopeName: () -> String?
    val applyDefaultFloodScope: suspend (String?) -> Unit
    val discoverNearbyRegions: suspend () -> RegionDiscoverUiResult
    val regionDiscoverMessage: suspend (RegionDiscoverUiResult) -> String?
    val rebootDevice: (suspend () -> Unit)?
    val onImportPrivateKey: (suspend (ByteArray) -> Unit)?
    val onForgetKeepData: suspend () -> Unit
    val onForgetDeleteData: suspend () -> Unit
    val onFactoryReset: suspend () -> Boolean
    val unfavoritedCount: suspend () -> Int
    val onRemoveUnfavorited: suspend () -> StaleNodeRemovalResult
}

/** Fields the Radio page reads. Preset-location navigation stays on the hub. */
internal interface RadioSettingsHost {
    val radioSnapshot: () -> RadioDeviceSnapshot?
    val regionSelection: () -> RegionSelection?
    val applyRadioPreset: suspend (RadioPreset) -> Unit
    val enableClientRepeat: suspend () -> Unit
    val disableClientRepeat: suspend () -> Unit
    val applyRepeatPreset: suspend (RadioPreset) -> Unit
    val isDeviceConnected: Boolean
    val deviceOtherParamsSnapshot: () -> DeviceOtherParamsSnapshot?
    val setPathHashMode: suspend (Int) -> Unit
    val applyManualRadioParams: suspend (ManualRadioParams) -> Unit
    val locationService: LocationService?
    val setRegionSelection: suspend (RegionSelection) -> Unit
    val resolveRegion: suspend () -> RegionSelection?
    val onOpenAppSettings: () -> Unit
}

/** Fields the Location page reads. The policy fallback is applied by the page. */
internal interface LocationSettingsHost {
    val locationSettingsSnapshot: () -> LocationSettingsSnapshot?
    val setAdvertLocationPolicy: (suspend (AdvertLocationPolicy) -> Unit)?
    val setShareLocationPublicly: suspend (Boolean) -> Unit
    val setDeviceCoordinates: suspend (Double, Double) -> Unit
    val connectedDeviceId: () -> String?
    val deviceLocationPreferences: DeviceLocationPreferences?
    val readDeviceGpsState: (suspend () -> DeviceGpsState)?
    val setDeviceGpsEnabled: (suspend (Boolean) -> Unit)?
    val refreshDeviceSettings: (suspend () -> Unit)?
    val onRequestLocationPermission: () -> Unit
    val onOpenAppSettings: () -> Unit
    val locationService: LocationService?
    val isDeviceConnected: Boolean
}

/** Fields the Device Info page reads. The public-key destination stays on the hub. */
internal interface DeviceInfoSettingsHost {
    val connectionState: StateFlow<DeviceConnectionState>
    val battery: StateFlow<BatteryInfo?>
    val batteryOcvArray: StateFlow<List<Int>>
    val refreshBattery: suspend () -> Unit
    val localNodeName: StateFlow<String?>
    val selfPublicKey: () -> ByteArray?
    val radioSnapshot: () -> RadioDeviceSnapshot?
    val setNodeName: (suspend (String) -> Unit)?
    val onShareMyContact: (() -> Unit)?
}

/** Values the selected settings page reads. The hub list does not. */
internal data class SettingsHubDetailInputs(
    override val connectionState: StateFlow<DeviceConnectionState>,
    override val battery: StateFlow<BatteryInfo?>,
    override val batteryOcvArray: StateFlow<List<Int>>,
    override val refreshBattery: suspend () -> Unit,
    override val localNodeName: StateFlow<String?>,
    override val selfPublicKey: () -> ByteArray?,
    /** The connected radio's key-prefix width in bytes, sizing the blocked list's row id prefixes. */
    val deviceHashSize: () -> Int?,
    override val radioSnapshot: () -> RadioDeviceSnapshot?,
    override val setNodeName: (suspend (String) -> Unit)?,
    override val onShareMyContact: (() -> Unit)?,
    override val regionSelection: () -> RegionSelection?,
    override val applyRadioPreset: suspend (RadioPreset) -> Unit,
    override val enableClientRepeat: suspend () -> Unit,
    override val disableClientRepeat: suspend () -> Unit,
    override val applyRepeatPreset: suspend (RadioPreset) -> Unit,
    override val isDeviceConnected: Boolean,
    override val deviceOtherParamsSnapshot: () -> DeviceOtherParamsSnapshot?,
    override val setPathHashMode: suspend (Int) -> Unit,
    override val applyManualRadioParams: suspend (ManualRadioParams) -> Unit,
    /**
     * App-wide location manager. Required: without it the node-position picker opens on the default
     * world view instead of where the responder is standing.
     */
    override val locationService: LocationService?,
    override val setRegionSelection: suspend (RegionSelection) -> Unit,
    override val resolveRegion: suspend () -> RegionSelection?,
    /** Opens the OS app-settings page from the Location screen's denied-permission alert. */
    override val onOpenAppSettings: () -> Unit,
    val connectionSettingsSnapshot: () -> ConnectionSettingsSnapshot?,
    val setBlePin: (suspend (Long) -> Unit)?,
    val updateWifiEndpoint: (suspend (String, Int) -> Unit)?,
    val onOpenBatteryOptimization: (() -> Unit)?,
    override val locationSettingsSnapshot: () -> LocationSettingsSnapshot?,
    override val setAdvertLocationPolicy: (suspend (AdvertLocationPolicy) -> Unit)?,
    override val setShareLocationPublicly: suspend (Boolean) -> Unit,
    override val setDeviceCoordinates: suspend (Double, Double) -> Unit,
    override val connectedDeviceId: () -> String?,
    override val deviceLocationPreferences: DeviceLocationPreferences?,
    override val readDeviceGpsState: (suspend () -> DeviceGpsState)?,
    override val setDeviceGpsEnabled: (suspend (Boolean) -> Unit)?,
    override val refreshDeviceSettings: (suspend () -> Unit)?,
    override val onRequestLocationPermission: () -> Unit,
    override val nodesAutoAddSnapshot: () -> NodesAutoAddSnapshot?,
    override val applyNodesAutoAdd: suspend (NodesAutoAddDraft) -> Unit,
    override val setTelemetryModes: suspend (Int, Int, Int) -> Unit,
    override val setMultiAcks: suspend (Int) -> Unit,
    override val staleCleanupThresholdDays: Int,
    override val onStaleCleanupThresholdChange: (Int) -> Unit,
    override val lastStaleCleanupSeconds: Long,
    override val nowSeconds: () -> Long,
    override val deviceOcvSnapshot: () -> OcvEditorState?,
    override val updateDeviceOcv: (suspend (OCVPreset, List<Int>) -> Unit)?,
    override val knownRegions: () -> List<String>,
    override val defaultFloodScopeName: () -> String?,
    override val applyDefaultFloodScope: suspend (String?) -> Unit,
    override val discoverNearbyRegions: suspend () -> RegionDiscoverUiResult,
    override val regionDiscoverMessage: suspend (RegionDiscoverUiResult) -> String?,
    override val rebootDevice: (suspend () -> Unit)?,
    override val onImportPrivateKey: (suspend (ByteArray) -> Unit)?,
    override val onForgetKeepData: suspend () -> Unit,
    override val onForgetDeleteData: suspend () -> Unit,
    override val onFactoryReset: suspend () -> Boolean,
    override val unfavoritedCount: suspend () -> Int,
    override val onRemoveUnfavorited: suspend () -> StaleNodeRemovalResult,
    val removeKnownRegion: suspend (String) -> Unit,
    val addKnownRegion: suspend (String) -> Unit,
    val nodeConfigService: () -> NodeConfigService?,
    val nodeConfigImportWorkspace: NodeConfigImportWorkspace? = null,
    val shareNodeConfigJson: (String, String) -> Unit,
    val nodeConfigExportTimestamp: () -> String,
    val currentRadioId: () -> Uuid?,
    val pickNodeConfigJson: (suspend () -> String?)?,
    val contactService: () -> ContactService?,
    val appearancePreferences: AppearancePreferencesStore?,
    val entitlementStore: EntitlementStore?,
    val demoModeStore: DemoModeStore?,
    val billingClient: BillingClient,
    val entitlementReconciler: EntitlementReconciler?,
    val onContactSupport: (() -> Unit)?,
    val onOpenUrl: (String) -> Unit,
    val onExportDebugLogs: suspend () -> String,
    val onClearDebugLogs: suspend () -> Unit,
    val onShareDebugLogs: (String) -> Unit,
    val chatDisplayPreferences: ChatDisplayPreferencesStore?,
    val translationTargetPreferences: TranslationTargetPreferencesStore?,
    val onOpenLanguageSettings: () -> Unit,
    val onBlockedContactTap: (String) -> Unit,
    val contactListInvalidations: Flow<Unit>,
    val blockedChannelSenderService: () -> BlockedChannelSenderService?,
    val notificationPreferences: StoredNotificationPreferences?,
    val isNotificationsAuthorized: () -> Boolean,
    val isNotificationPermissionDetermined: () -> Boolean,
    val onRequestNotificationPermission: () -> Unit,
    val onOpenSystemNotificationSettings: () -> Unit,
    val notificationPermissionState: StateFlow<PermissionUiState>?,
    val mapDisplayPreferences: MapDisplayPreferencesStore?,
    val offlineMapPackRegistry: OfflineMapPackRegistry?,
    val startOfflineMapDownload: (OfflineMapPack, CoordinateBounds) -> Unit,
    val deleteOfflineMapPack: (String) -> Unit,
    val pauseOfflineMapPack: (String) -> Unit,
    val resumeOfflineMapPack: (String) -> Unit,
    val onOfflineMapPacksChanged: () -> Unit,
    val loadMapCameraRegion: suspend () -> MapCameraRegion?,
    val offlineMapAvailableBytes: () -> Long?,
    /** MapLibre offline db file length for Storage Used; 0 when the file is missing. */
    val offlineMapDatabaseSizeBytes: () -> Long,
    /** Live network route for the offline-maps picker. */
    val isOfflineMapNetworkAvailable: () -> Boolean,
    val offlineMapDownloadError: String?,
    val onOfflineMapDownloadErrorShown: () -> Unit,
    val onExportBackup: suspend () -> BackupExportSuccessSummary?,
    val backupWorkspace: BackupSettingsWorkspace? = null,
    val onImportBackupFile: (suspend () -> String?)?,
    val onImportBackup: suspend (String) -> BackupImportResult?,
) : AdvancedSettingsHost,
    RadioSettingsHost,
    LocationSettingsHost,
    DeviceInfoSettingsHost
