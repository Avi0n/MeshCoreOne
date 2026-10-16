package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.chat.translation.platformLanguageDisplayName
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.map.regionDisplayName
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_app_settings_header
import one.meshcore.shared.resources.settings_demo_mode_enabled
import one.meshcore.shared.resources.settings_demo_mode_footer
import one.meshcore.shared.resources.settings_demo_mode_header
import one.meshcore.shared.resources.settings_device_connect
import one.meshcore.shared.resources.settings_device_header
import one.meshcore.shared.resources.settings_device_no_device_connected
import one.meshcore.shared.resources.settings_my_device_header
import one.meshcore.shared.resources.settings_title
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubList
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val DEVICE_DESTINATIONS = listOf(
    SettingsDestination.DEVICE_INFO,
    SettingsDestination.RADIO,
    SettingsDestination.LOCATION,
    SettingsDestination.CONNECTION,
    SettingsDestination.ADVANCED,
)

private val APP_DESTINATIONS = listOf(
    SettingsDestination.NOTIFICATIONS,
    SettingsDestination.CHATS,
    SettingsDestination.LANGUAGE,
    SettingsDestination.APPEARANCE,
    SettingsDestination.MAPS,
    SettingsDestination.BACKUP,
    SettingsDestination.SUPPORT,
)

/** Sideload builds replace the Support purchase row with the About donation link. */
internal fun settingsHubAppDestinations(
    billingEnabled: Boolean = SideloadDistribution.BILLING_ENABLED,
): List<SettingsDestination> =
    if (billingEnabled) APP_DESTINATIONS else APP_DESTINATIONS.filter { it != SettingsDestination.SUPPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubList(
    inputs: SettingsHubDetailInputs,
    selected: SettingsDestination?,
    onSelect: (SettingsDestination?) -> Unit,
    listState: LazyListState,
    onConnectDevice: () -> Unit = {},
    appVersion: String? = null,
    appBuild: String? = null,
    modifier: Modifier = Modifier,
) {
    val nodeName by inputs.localNodeName.collectAsStateWithLifecycle()
    val fallbackThemeId = remember { MutableStateFlow(AppTheme.DEFAULT_ID) }
    val selectedThemeId by (inputs.appearancePreferences?.selectedThemeId ?: fallbackThemeId)
        .collectAsStateWithLifecycle()
    val demoModeStore = inputs.demoModeStore
    val demoUnlockedFallback = remember { MutableStateFlow(false) }
    val demoUnlocked by (demoModeStore?.isUnlocked ?: demoUnlockedFallback).collectAsStateWithLifecycle()
    val radioSnapshot = inputs.radioSnapshot()
    val locationSettingsSnapshot = inputs.locationSettingsSnapshot()
    val connectionSettingsSnapshot = inputs.connectionSettingsSnapshot()
    val region = inputs.regionSelection()

    PreferenceHubList(
        title = stringResource(Res.string.settings_title),
        listState = listState,
        modifier = modifier,
    ) {
        item {
            if (inputs.isDeviceConnected) {
                PreferenceHubSection(title = stringResource(Res.string.settings_my_device_header)) {
                    DEVICE_DESTINATIONS.forEach { dest ->
                        DestinationRow(
                            dest = dest,
                            enabled = true,
                            selected = selected == dest,
                            onSelect = onSelect,
                            nodeName = nodeName,
                            radioSnapshot = radioSnapshot,
                            locationSettingsSnapshot = locationSettingsSnapshot,
                            connectionSettingsSnapshot = connectionSettingsSnapshot,
                            selectedThemeId = selectedThemeId,
                            region = region,
                        )
                    }
                }
            } else {
                PreferenceHubSection(title = stringResource(Res.string.settings_device_header)) {
                    ConnectDeviceRow(onConnectDevice = onConnectDevice)
                }
            }
        }
        item {
            PreferenceHubSection(
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
                title = stringResource(Res.string.settings_app_settings_header),
            ) {
                settingsHubAppDestinations().forEach { dest ->
                    DestinationRow(
                        dest = dest,
                        enabled = true,
                        selected = selected == dest,
                        onSelect = onSelect,
                        nodeName = nodeName,
                        radioSnapshot = radioSnapshot,
                        locationSettingsSnapshot = locationSettingsSnapshot,
                        connectionSettingsSnapshot = connectionSettingsSnapshot,
                        selectedThemeId = selectedThemeId,
                    )
                }
            }
        }
        item {
            AboutSection(
                onOpenUrl = inputs.onOpenUrl,
                onOpenFeedback = { onSelect(SettingsDestination.FEEDBACK) },
                appVersion = appVersion,
                appBuild = appBuild,
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
            )
        }
        // iOS `SettingsListContent` paints Demo Mode on the hub after unlock.
        if (demoModeStore != null && demoUnlocked) {
            item {
                SettingsHubDemoModeSection(
                    demoMode = demoModeStore,
                    modifier = Modifier.padding(top = PreferenceHubSectionGap),
                )
            }
        }
    }
}

/** Opens the post-onboarding connect sheet (iOS `NoDeviceSection`). */
@Composable
private fun ConnectDeviceRow(onConnectDevice: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.settings_device_connect)) },
        supportingContent = {
            Text(
                text = stringResource(Res.string.settings_device_no_device_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = AppIcons.CellTower,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnectDevice),
    )
}

@Composable
private fun DestinationRow(
    dest: SettingsDestination,
    enabled: Boolean,
    selected: Boolean = false,
    onSelect: (SettingsDestination?) -> Unit,
    nodeName: String? = null,
    radioSnapshot: RadioDeviceSnapshot? = null,
    locationSettingsSnapshot: LocationSettingsSnapshot? = null,
    connectionSettingsSnapshot: ConnectionSettingsSnapshot? = null,
    selectedThemeId: String = AppTheme.DEFAULT_ID,
    region: RegionSelection? = null,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    }
    val supportingColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
    }
    ListItem(
        headlineContent = {
            Text(text = destinationTitle(dest, connectionSettingsSnapshot), color = contentColor)
        },
        supportingContent = {
            Text(
                text = destinationSupporting(
                    dest,
                    enabled,
                    nodeName = nodeName,
                    radioSnapshot = radioSnapshot,
                    locationSettingsSnapshot = locationSettingsSnapshot,
                    selectedThemeId = selectedThemeId,
                    region = region,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = supportingColor,
            )
        },
        leadingContent = {
            Icon(
                imageVector = settingsHubDestinationIcon(dest, connectionSettingsSnapshot),
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                },
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(dest) },
    )
}

@Composable
internal fun destinationTitle(
    dest: SettingsDestination,
    connectionSettingsSnapshot: ConnectionSettingsSnapshot? = null,
): String = stringResource(settingsHubTitleResource(dest, connectionSettingsSnapshot?.transportType))

@Composable
private fun destinationSupporting(
    dest: SettingsDestination,
    enabled: Boolean,
    nodeName: String? = null,
    radioSnapshot: RadioDeviceSnapshot? = null,
    locationSettingsSnapshot: LocationSettingsSnapshot? = null,
    selectedThemeId: String = AppTheme.DEFAULT_ID,
    region: RegionSelection? = null,
): String {
    val line = settingsHubSupportingLine(
        dest,
        SettingsHubSupportingContext(
            enabled = enabled,
            nodeName = nodeName,
            radioSnapshot = radioSnapshot,
            locationSettingsSnapshot = locationSettingsSnapshot,
            region = region,
            regionName = region?.let { regionDisplayName(it) },
            appearanceName = appearanceThemeDisplayName(selectedThemeId),
            languageName = platformLanguageDisplayName(
                code = platformPreferredLanguage(),
                inLocale = Locale.current.language,
            ),
        ),
    )
    return when (line) {
        is SettingsHubSupporting.Resource -> stringResource(line.resource)
        is SettingsHubSupporting.Text -> line.value
    }
}

/** WiFi vs Bluetooth glyph on the Settings hub Connection row, matching iOS `SettingsListContent`. */
internal fun connectionHubIcon(transportType: TransportType?): ImageVector = when (transportType) {
    TransportType.WIFI -> AppIcons.Wifi
    TransportType.BLUETOOTH -> AppIcons.Bluetooth
    null -> AppIcons.Sync
}

@Composable
private fun SettingsHubDemoModeSection(demoMode: DemoModeStore, modifier: Modifier = Modifier) {
    val unlocked by demoMode.isUnlocked.collectAsStateWithLifecycle()
    val enabled by demoMode.isEnabled.collectAsStateWithLifecycle()
    if (!unlocked) return
    Column(modifier) {
        PreferenceHubSection(title = stringResource(Res.string.settings_demo_mode_header)) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_demo_mode_enabled),
                checked = enabled,
                onCheckedChange = { demoMode.setEnabled(it) },
            )
        }
        PreferenceHubFooter(stringResource(Res.string.settings_demo_mode_footer))
    }
}

/** iOS Device Info hub `cpu`. */
internal val SETTINGS_HUB_DEVICE_INFO_ICON get() = AppIcons.Memory

/** iOS Radio hub `antenna.radiowaves.left.and.right`. */
internal val SETTINGS_HUB_RADIO_ICON get() = AppIcons.CellTower

/** iOS Advanced hub `gearshape.2`. */
internal val SETTINGS_HUB_ADVANCED_ICON get() = AppIcons.SettingsSelected

/** iOS Backup hub `archivebox.fill`. */
internal val SETTINGS_HUB_BACKUP_ICON get() = AppIcons.Archive

/** iOS Appearance hub `paintpalette`. */
internal val SETTINGS_HUB_APPEARANCE_ICON get() = AppIcons.Palette

private const val DISABLED_ALPHA = 0.38f
