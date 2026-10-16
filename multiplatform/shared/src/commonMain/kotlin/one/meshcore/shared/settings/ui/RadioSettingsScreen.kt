package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.regionDisplayName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_advanced_radio_bandwidth
import one.meshcore.shared.resources.settings_advanced_radio_coding_rate
import one.meshcore.shared.resources.settings_advanced_radio_frequency
import one.meshcore.shared.resources.settings_advanced_radio_spreading_factor
import one.meshcore.shared.resources.settings_battery_curve_custom
import one.meshcore.shared.resources.settings_radio_footer
import one.meshcore.shared.resources.settings_radio_region_footer
import one.meshcore.shared.resources.settings_radio_regulations_footer
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioPresets
import one.meshcore.shared.settings.formatThousandths
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Radio: location filter, regional preset picker, path-hash size, and (when the device
 * supports firmware v9+) the client-repeat-mode toggle. Applying a preset calls [onApplyPreset]
 * (session.setRadio in wire units). Manual LoRa editors live on this page.
 */
@Composable
internal fun RadioSettingsScreen(
    host: RadioSettingsHost,
    onOpenPresetLocation: () -> Unit = {},
    /** After the last retry, leave this page. */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot = host.radioSnapshot()
    val region = host.regionSelection()
    val onApplyPreset = host.applyRadioPreset
    val onEnableRepeatMode = host.enableClientRepeat
    val onDisableRepeatMode = host.disableClientRepeat
    val onApplyRepeatPreset = host.applyRepeatPreset
    val isDeviceConnected = host.isDeviceConnected
    val otherParams = host.deviceOtherParamsSnapshot()
    val otherParamsProvider = host.deviceOtherParamsSnapshot
    val onSetPathHashMode = host.setPathHashMode
    val onApplyManualParams = host.applyManualRadioParams
    val locationService = host.locationService
    val onSetRegionSelection = host.setRegionSelection
    val resolveRegion = host.resolveRegion
    val onOpenAppSettings = host.onOpenAppSettings
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    var radioWriteInFlight by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var localSnapshot by remember(snapshot) { mutableStateOf(snapshot) }
    var localOtherParams by remember(otherParams) { mutableStateOf(otherParams) }
    val retryAlert = rememberRetryAlertState()
    var localRegion by remember { mutableStateOf(region) }
    LaunchedEffect(region) { localRegion = region }

    val repeatEnabled = localSnapshot?.clientRepeat == true
    val regionPreset = localSnapshot?.let {
        RadioPresets.resolvedPreset(it, preferredID = it.appliedRadioPresetID, region = localRegion)
    }
    val currentPreset = if (repeatEnabled) {
        localSnapshot?.let { RadioPresets.matchingRepeatPreset(it.frequencyKHz) }
    } else {
        regionPreset
    }
    val selectable = remember(localRegion, regionPreset?.id) {
        RadioPresets.visiblePresets(localRegion, regionPreset?.id)
    }
    val byRegion = remember(selectable) { selectable.groupBy { it.region } }
    val byRepeatSection = remember { RadioPresets.repeatPresets.groupBy { it.repeatSectionHeader } }

    val snap = localSnapshot
    if (snap == null) {
        SettingsNoDeviceEmpty(modifier.preferenceHubEdgeFill())
        return
    }

    Column(modifier.preferenceHubEdgeScroll()) {
        RadioLocationBlock(
            repeatEnabled = repeatEnabled,
            localRegion = localRegion,
            onRegionChange = { localRegion = it },
            locationService = locationService,
            onSetRegionSelection = onSetRegionSelection,
            resolveRegion = resolveRegion,
            onOpenPresetLocation = onOpenPresetLocation,
            onOpenAppSettings = onOpenAppSettings,
        )
        PreferenceHubSection(
            modifier = Modifier.padding(
                top = if (!repeatEnabled) PreferenceHubSectionGap else 0.dp,
            ),
            title = radioPresetSectionTitle(
                currentPreset,
                stringResource(Res.string.settings_battery_curve_custom),
            ),
            applyHorizontalPadding = false,
        ) {
            CurrentParamLines(snap)
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_radio_footer),
            applyHorizontalPadding = false,
        )
        val selectedRegion = localRegion
        if (selectedRegion != null) {
            PreferenceHubFooter(
                formattedStringResource(
                    Res.string.settings_radio_region_footer,
                    regionDisplayName(selectedRegion),
                ),
                applyHorizontalPadding = false,
            )
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_radio_regulations_footer),
            applyHorizontalPadding = false,
        )

        if (radioWriteInFlight) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        RadioRepeatModeRow(
            supported = snap.supportsClientRepeat,
            clientRepeat = snap.clientRepeat,
            radioWriteInFlight = radioWriteInFlight,
            isDeviceConnected = isDeviceConnected,
            currentSnapshot = { localSnapshot },
            onEnableRepeatMode = onEnableRepeatMode,
            onDisableRepeatMode = onDisableRepeatMode,
            onInFlight = { radioWriteInFlight = it },
            onError = { errorMessage = it },
            onSnapshot = { localSnapshot = it },
            settingsErrorText = settingsErrorText,
            retryAlert = retryAlert,
            onMaxRetriesExceeded = onMaxRetriesExceeded,
        )
        RadioPresetSections(
            repeatEnabled = repeatEnabled,
            byRepeatSection = byRepeatSection,
            byRegion = byRegion,
            currentPresetId = currentPreset?.id,
            isDeviceConnected = isDeviceConnected,
            radioWriteInFlight = radioWriteInFlight,
            onSelect = { preset ->
                launchRadioPresetApply(
                    scope,
                    RadioPresetWrite(
                        preset = preset,
                        repeatEnabled = repeatEnabled,
                        inFlight = radioWriteInFlight,
                        snapshot = snap,
                        onApplyPreset = onApplyPreset,
                        onApplyRepeatPreset = onApplyRepeatPreset,
                        otherParamsProvider = otherParamsProvider,
                        onInFlight = { radioWriteInFlight = it },
                        onError = { errorMessage = it },
                        onSnapshot = { localSnapshot = it },
                        onOtherParams = { localOtherParams = it },
                        settingsErrorText = settingsErrorText,
                        retryAlert = retryAlert,
                        onMaxRetriesExceeded = onMaxRetriesExceeded,
                    ),
                )
            },
        )

        val pathHashParams = localOtherParams
        if (pathHashParams != null && pathHashParams.supportsPathHashMode) {
            PathHashModeSection(
                snapshot = pathHashParams,
                enabled = isDeviceConnected && !radioWriteInFlight,
                onSetPathHashMode = onSetPathHashMode,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
                onWriteInFlight = { radioWriteInFlight = it },
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
            )
        }

        AdvancedRadioSection(
            snapshot = snap,
            isDeviceConnected = isDeviceConnected,
            radioWriteInFlight = radioWriteInFlight,
            onRadioWriteInFlightChange = { radioWriteInFlight = it },
            onApply = { params ->
                onApplyManualParams(params)
                localSnapshot = radioSnapshotAfterManualApply(snap, params)
            },
            onMaxRetriesExceeded = onMaxRetriesExceeded,
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
        )
    }

    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

internal fun radioPresetSectionTitle(preset: RadioPreset?, custom: String): String = preset?.name ?: custom

internal fun radioSnapshotAfterManualApply(snap: RadioDeviceSnapshot, params: ManualRadioParams): RadioDeviceSnapshot {
    val frequencyKHz = (params.frequencyMHz * KHZ_PER_MHZ).toLong()
    val bandwidthHz = (params.bandwidthKHz * HZ_PER_KHZ).toLong()
    val clientRepeat = params.clientRepeat ?: snap.clientRepeat
    return snap.copy(
        frequencyKHz = frequencyKHz,
        bandwidthHz = bandwidthHz,
        spreadingFactor = params.spreadingFactor,
        codingRate = params.codingRate,
        txPower = params.txPower ?: snap.txPower,
        clientRepeat = clientRepeat,
        appliedRadioPresetID = RadioPresets.retainedAppliedPresetID(
            rf = RadioPresets.RadioPresetRf(
                frequencyKHz = frequencyKHz,
                bandwidthHz = bandwidthHz,
                spreadingFactor = params.spreadingFactor,
                codingRate = params.codingRate,
            ),
            preferredID = snap.appliedRadioPresetID,
            clientRepeat = clientRepeat,
        ),
    )
}

@Composable
private fun CurrentParamLines(snapshot: RadioDeviceSnapshot) {
    val freqMHz = snapshot.frequencyKHz / KHZ_PER_MHZ
    val bwKHz = snapshot.bandwidthHz / HZ_PER_KHZ
    PreferenceHubValueRow(stringResource(Res.string.settings_advanced_radio_frequency), formatThousandths(freqMHz))
    PreferenceHubValueRow(stringResource(Res.string.settings_advanced_radio_bandwidth), formatThousandths(bwKHz))
    PreferenceHubValueRow(
        stringResource(Res.string.settings_advanced_radio_spreading_factor),
        snapshot.spreadingFactor.toString(),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.settings_advanced_radio_coding_rate),
        snapshot.codingRate.toString(),
    )
}

@Composable
internal fun PresetRow(
    preset: RadioPreset,
    selected: Boolean,
    enabled: Boolean,
    isRadioReady: Boolean,
    onSelect: () -> Unit,
) {
    RadioPresetListItem(
        preset = preset,
        selected = selected,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .radioConnectionHint(isRadioReady)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    )
}

private const val KHZ_PER_MHZ = 1000.0
private const val HZ_PER_KHZ = 1000.0
