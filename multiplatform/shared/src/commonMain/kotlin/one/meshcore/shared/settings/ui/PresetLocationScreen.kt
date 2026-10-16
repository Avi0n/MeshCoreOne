package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.regionDisplayName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.onboarding_permissions_location_alert_open_settings
import one.meshcore.shared.resources.onboarding_permissions_location_alert_title
import one.meshcore.shared.resources.onboarding_region_use_my_location
import one.meshcore.shared.resources.settings_radio_preset_location
import one.meshcore.shared.resources.settings_radio_preset_location_denied
import one.meshcore.shared.resources.settings_radio_preset_location_footer
import one.meshcore.shared.resources.settings_radio_preset_location_not_set
import one.meshcore.shared.resources.settings_radio_preset_location_use_my_location_failure
import one.meshcore.shared.resources.settings_radio_preset_location_use_my_location_locating
import one.meshcore.shared.settings.PresetLocationHost
import one.meshcore.shared.settings.PresetLocationPolicy
import one.meshcore.shared.settings.PresetLocationSession
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Radio location filter. Hidden by the parent while Repeat Mode is on. Expands the
 * country/state pickers when location is unauthorized and the place is incomplete; otherwise a row
 * that opens the Location subpage.
 */
@Composable
internal fun PresetLocationSection(
    selection: RegionSelection?,
    authorized: Boolean,
    isResolving: Boolean,
    onSelectionChange: (RegionSelection?) -> Unit,
    onUseMyLocation: () -> Unit,
    onOpenLocationPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = PresetLocationPolicy.shouldExpandOnRadio(authorized, selection)
    val footer = stringResource(Res.string.settings_radio_preset_location_footer)
    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            if (expanded) {
                RegionPickerRows(selection = selection, onSelectionChange = onSelectionChange)
                PresetLocationUseMyLocationButton(
                    isResolving = isResolving,
                    locatingLabel = stringResource(Res.string.settings_radio_preset_location_use_my_location_locating),
                    actionLabel = stringResource(Res.string.onboarding_region_use_my_location),
                    onClick = onUseMyLocation,
                    modifier = Modifier.padding(horizontal = PreferenceHubCardPadding),
                )
            } else {
                val detail = if (selection != null) {
                    regionDisplayName(selection)
                } else {
                    stringResource(Res.string.settings_radio_preset_location_not_set)
                }
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.settings_radio_preset_location)) },
                    supportingContent = {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLocationPage),
                )
            }
        }
        PreferenceHubFooter(footer, applyHorizontalPadding = false)
    }
}

/**
 * Settings → Radio → Location. Country/state write `.manual`; Use my location runs the GPS host.
 */
@Composable
fun PresetLocationScreen(
    selection: RegionSelection?,
    isResolving: Boolean,
    onSelectionChange: (RegionSelection?) -> Unit,
    onUseMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.preferenceHubEdgeScroll()) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            RegionPickerRows(selection = selection, onSelectionChange = onSelectionChange)
            PresetLocationUseMyLocationButton(
                isResolving = isResolving,
                locatingLabel = stringResource(Res.string.settings_radio_preset_location_use_my_location_locating),
                actionLabel = stringResource(Res.string.onboarding_region_use_my_location),
                onClick = onUseMyLocation,
                modifier = Modifier.padding(horizontal = PreferenceHubCardPadding),
            )
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_radio_preset_location_footer),
            applyHorizontalPadding = false,
        )
    }
}

@Composable
internal fun PresetLocationSessionAlerts(
    errorMessage: String?,
    showOpenSettingsAlert: Boolean,
    onDismissError: () -> Unit,
    onDismissOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = onDismissError)
    }
    if (showOpenSettingsAlert) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.onboarding_permissions_location_alert_title),
            message = stringResource(Res.string.settings_radio_preset_location_denied),
            confirmLabel = stringResource(Res.string.onboarding_permissions_location_alert_open_settings),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = {
                onDismissOpenSettings()
                onOpenAppSettings()
            },
            onDismiss = onDismissOpenSettings,
        )
    }
}

/**
 * Nested Settings → Radio → Location page: owns the GPS session for the subpage lifetime.
 */
@Composable
internal fun PresetLocationDestination(
    region: RegionSelection?,
    locationService: LocationService?,
    onSetRegionSelection: suspend (RegionSelection) -> Unit,
    resolveRegion: suspend () -> RegionSelection?,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var localRegion by remember { mutableStateOf(region) }
    LaunchedEffect(region) { localRegion = region }
    val session = remember { PresetLocationSession() }
    val isResolving by session.isResolving.collectAsStateWithLifecycle()
    val errorMessage by session.errorMessage.collectAsStateWithLifecycle()
    val showOpenSettings by session.showOpenSettingsAlert.collectAsStateWithLifecycle()
    val lookupMissMessage = stringResource(Res.string.settings_radio_preset_location_use_my_location_failure)

    fun locationHost(): PresetLocationHost? {
        val service = locationService ?: return null
        return PresetLocationHost(
            locationService = service,
            current = { localRegion },
            setSelection = { next ->
                if (next != null) {
                    localRegion = next
                    scope.launch { onSetRegionSelection(next) }
                }
            },
            resolve = resolveRegion,
            lookupMissMessage = lookupMissMessage,
        )
    }

    LaunchedEffect(locationService, localRegion?.source) {
        locationService?.refreshAuthorization()
        val host = locationHost() ?: return@LaunchedEffect
        session.resolveOnAppear(host)
    }

    PresetLocationScreen(
        selection = localRegion,
        isResolving = isResolving,
        onSelectionChange = { next ->
            if (next != null) {
                localRegion = next
                scope.launch { onSetRegionSelection(next) }
            }
        },
        onUseMyLocation = {
            locationHost()?.let { host ->
                scope.launch { session.useMyLocation(host) }
            }
        },
        modifier = modifier,
    )
    PresetLocationSessionAlerts(
        errorMessage = errorMessage,
        showOpenSettingsAlert = showOpenSettings,
        onDismissError = session::clearError,
        onDismissOpenSettings = session::dismissOpenSettingsAlert,
        onOpenAppSettings = onOpenAppSettings,
    )
}
