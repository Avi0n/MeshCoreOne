package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.map.LocationAuthorizationStatus
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.isAuthorized
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_radio_preset_location_use_my_location_failure
import one.meshcore.shared.resources.settings_radio_repeat_mode
import one.meshcore.shared.resources.settings_radio_repeat_mode_accessibility_hint
import one.meshcore.shared.resources.settings_radio_repeat_mode_confirm_enable
import one.meshcore.shared.resources.settings_radio_repeat_mode_confirm_message
import one.meshcore.shared.resources.settings_radio_repeat_mode_confirm_title
import one.meshcore.shared.resources.settings_radio_repeat_mode_footer
import one.meshcore.shared.settings.PresetLocationHost
import one.meshcore.shared.settings.PresetLocationSession
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioRegion
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

private data class RadioLocationBindings(
    val current: () -> RegionSelection?,
    val onRegionChange: (RegionSelection) -> Unit,
    val onSetRegionSelection: suspend (RegionSelection) -> Unit,
    val resolveRegion: suspend () -> RegionSelection?,
    val lookupMissMessage: String,
)

private fun radioLocationHost(
    locationService: LocationService?,
    scope: CoroutineScope,
    bindings: RadioLocationBindings,
): PresetLocationHost? {
    val service = locationService ?: return null
    return PresetLocationHost(
        locationService = service,
        current = bindings.current,
        setSelection = { next ->
            if (next != null) {
                bindings.onRegionChange(next)
                scope.launch { bindings.onSetRegionSelection(next) }
            }
        },
        resolve = bindings.resolveRegion,
        lookupMissMessage = bindings.lookupMissMessage,
    )
}

internal data class RadioRepeatWrite(
    val enabled: Boolean,
    val inFlight: Boolean,
    val currentSnapshot: () -> RadioDeviceSnapshot?,
    val onEnable: suspend () -> Unit,
    val onDisable: suspend () -> Unit,
    val onInFlight: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    val onSnapshot: (RadioDeviceSnapshot?) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchRadioRepeatMode(scope: CoroutineScope, request: RadioRepeatWrite) {
    if (request.inFlight) return
    request.onInFlight(true)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = request.settingsWriteReport { launchRadioRepeatMode(scope, request) },
        onFinished = { request.onInFlight(false) },
    ) {
        if (request.enabled) request.onEnable() else request.onDisable()
        request.onSnapshot(request.currentSnapshot()?.copy(clientRepeat = request.enabled))
    }
}

@Composable
internal fun RadioLocationBlock(
    repeatEnabled: Boolean,
    localRegion: RegionSelection?,
    onRegionChange: (RegionSelection) -> Unit,
    locationService: LocationService?,
    onSetRegionSelection: suspend (RegionSelection) -> Unit,
    resolveRegion: suspend () -> RegionSelection?,
    onOpenPresetLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val locationSession = remember { PresetLocationSession() }
    val isResolvingLocation by locationSession.isResolving.collectAsStateWithLifecycle()
    val locationError by locationSession.errorMessage.collectAsStateWithLifecycle()
    val showOpenSettings by locationSession.showOpenSettingsAlert.collectAsStateWithLifecycle()
    val idleAuth = remember {
        MutableStateFlow(LocationAuthorizationStatus.NOT_DETERMINED)
    }
    val authStatus by (locationService?.authorizationStatus ?: idleAuth).collectAsStateWithLifecycle()
    val locationAuthorized = authStatus.isAuthorized
    val lookupMissMessage = stringResource(Res.string.settings_radio_preset_location_use_my_location_failure)
    val bindings = RadioLocationBindings(
        current = { localRegion },
        onRegionChange = onRegionChange,
        onSetRegionSelection = onSetRegionSelection,
        resolveRegion = resolveRegion,
        lookupMissMessage = lookupMissMessage,
    )
    LaunchedEffect(locationAuthorized, localRegion?.source, locationService) {
        locationService?.refreshAuthorization()
        val host = radioLocationHost(locationService, scope, bindings) ?: return@LaunchedEffect
        locationSession.resolveOnAppear(host)
    }
    if (!repeatEnabled) {
        PresetLocationSection(
            selection = localRegion,
            authorized = locationAuthorized,
            isResolving = isResolvingLocation,
            onSelectionChange = { next ->
                radioLocationHost(locationService, scope, bindings)?.setSelection(next)
            },
            onUseMyLocation = {
                radioLocationHost(locationService, scope, bindings)?.let { host ->
                    scope.launch { locationSession.useMyLocation(host) }
                }
            },
            onOpenLocationPage = onOpenPresetLocation,
        )
    }
    PresetLocationSessionAlerts(
        errorMessage = locationError,
        showOpenSettingsAlert = showOpenSettings,
        onDismissError = locationSession::clearError,
        onDismissOpenSettings = locationSession::dismissOpenSettingsAlert,
        onOpenAppSettings = onOpenAppSettings,
    )
}

@Composable
internal fun RadioRepeatModeRow(
    supported: Boolean,
    clientRepeat: Boolean,
    radioWriteInFlight: Boolean,
    isDeviceConnected: Boolean,
    currentSnapshot: () -> RadioDeviceSnapshot?,
    onEnableRepeatMode: suspend () -> Unit,
    onDisableRepeatMode: suspend () -> Unit,
    onInFlight: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onSnapshot: (RadioDeviceSnapshot?) -> Unit,
    settingsErrorText: (Throwable) -> String,
    retryAlert: RetryAlertState,
    onMaxRetriesExceeded: () -> Unit,
) {
    if (!supported) return
    var showRepeatConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writeRepeat = { enabled: Boolean ->
        launchRadioRepeatMode(
            scope,
            RadioRepeatWrite(
                enabled = enabled,
                inFlight = radioWriteInFlight,
                currentSnapshot = currentSnapshot,
                onEnable = onEnableRepeatMode,
                onDisable = onDisableRepeatMode,
                onInFlight = onInFlight,
                onError = onError,
                onSnapshot = onSnapshot,
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }
    PreferenceHubSection(
        modifier = Modifier.padding(top = PreferenceHubSectionGap),
        applyHorizontalPadding = false,
    ) {
        SettingsToggleRow(
            label = stringResource(Res.string.settings_radio_repeat_mode),
            checked = clientRepeat,
            onCheckedChange = { newValue ->
                if (newValue) {
                    showRepeatConfirm = true
                } else {
                    writeRepeat(false)
                }
            },
            supporting = stringResource(Res.string.settings_radio_repeat_mode_footer),
            enabled = !radioWriteInFlight,
            isRadioReady = isDeviceConnected,
            accessibilityHint = stringResource(Res.string.settings_radio_repeat_mode_accessibility_hint),
            modifier = Modifier.radioConnectionHint(isDeviceConnected),
        )
    }
    if (showRepeatConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_radio_repeat_mode_confirm_title),
            message = stringResource(Res.string.settings_radio_repeat_mode_confirm_message),
            confirmLabel = stringResource(Res.string.settings_radio_repeat_mode_confirm_enable),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                showRepeatConfirm = false
                writeRepeat(true)
            },
            onDismiss = { showRepeatConfirm = false },
        )
    }
}

@Composable
internal fun RadioPresetSections(
    repeatEnabled: Boolean,
    byRepeatSection: Map<String?, List<RadioPreset>>,
    byRegion: Map<RadioRegion, List<RadioPreset>>,
    currentPresetId: String?,
    isDeviceConnected: Boolean,
    radioWriteInFlight: Boolean,
    onSelect: (RadioPreset) -> Unit,
) {
    val enabled = isDeviceConnected && !radioWriteInFlight
    if (repeatEnabled) {
        for ((header, presets) in byRepeatSection) {
            PresetSection(
                title = header,
                presets = presets,
                currentPresetId = currentPresetId,
                enabled = enabled,
                isDeviceConnected = isDeviceConnected,
                onSelect = onSelect,
            )
        }
    } else {
        for (regionEntry in RadioRegion.entries) {
            val presets = byRegion[regionEntry].orEmpty()
            if (presets.isEmpty()) continue
            PresetSection(
                title = regionEntry.displayName,
                presets = presets,
                currentPresetId = currentPresetId,
                enabled = enabled,
                isDeviceConnected = isDeviceConnected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PresetSection(
    title: String?,
    presets: List<RadioPreset>,
    currentPresetId: String?,
    enabled: Boolean,
    isDeviceConnected: Boolean,
    onSelect: (RadioPreset) -> Unit,
) {
    PreferenceHubSection(
        modifier = Modifier.padding(top = PreferenceHubSectionGap),
        title = title,
        applyHorizontalPadding = false,
    ) {
        for (preset in presets) {
            PresetRow(
                preset = preset,
                selected = currentPresetId == preset.id,
                enabled = enabled,
                isRadioReady = isDeviceConnected,
                onSelect = { if (currentPresetId != preset.id) onSelect(preset) },
            )
        }
    }
}
