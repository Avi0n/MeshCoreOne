package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_device_header
import one.meshcore.shared.resources.settings_device_info_build_date
import one.meshcore.shared.resources.settings_device_info_capabilities_header
import one.meshcore.shared.resources.settings_device_info_connection_header
import one.meshcore.shared.resources.settings_device_info_default_manufacturer
import one.meshcore.shared.resources.settings_device_info_firmware_header
import one.meshcore.shared.resources.settings_device_info_firmware_version
import one.meshcore.shared.resources.settings_device_info_firmware_version_format
import one.meshcore.shared.resources.settings_device_info_manufacturer
import one.meshcore.shared.resources.settings_device_info_max_channels
import one.meshcore.shared.resources.settings_device_info_max_nodes
import one.meshcore.shared.resources.settings_device_info_max_tx_power
import one.meshcore.shared.resources.settings_device_info_share_contact
import one.meshcore.shared.resources.settings_device_info_tx_power_format
import one.meshcore.shared.resources.settings_device_info_unknown
import one.meshcore.shared.resources.settings_device_no_device_connected
import one.meshcore.shared.resources.settings_node_footer
import one.meshcore.shared.resources.settings_node_header
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Device Info: identity header, node name, public-key row, battery/storage.
 * The public-key page is a nested destination matching iOS `PublicKeyView`. Identity
 * import/regenerate lives on Advanced, matching iOS `DeviceIdentitySection`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceInfoSettingsScreen(
    host: DeviceInfoSettingsHost,
    onOpenPublicKey: (() -> Unit)? = null,
    /** After the last retry, leave this page. */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val connectionState = host.connectionState
    val battery = host.battery
    val batteryOcvArray = host.batteryOcvArray
    val localNodeName = host.localNodeName
    val selfPublicKey = host.selfPublicKey
    val radioSnapshot = host.radioSnapshot
    val onSetNodeName = host.setNodeName
    val onShareContact = host.onShareMyContact
    val onRefreshBattery = host.refreshBattery
    val state by connectionState.collectAsStateWithLifecycle()
    val batteryInfo by battery.collectAsStateWithLifecycle()
    val ocvArray by batteryOcvArray.collectAsStateWithLifecycle()
    val nodeName by localNodeName.collectAsStateWithLifecycle()
    val ready = state == DeviceConnectionState.READY
    val scope = rememberCoroutineScope()
    var refreshingBattery by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready) onRefreshBattery()
    }

    if (!state.isConnected) {
        SettingsNoDeviceEmpty(modifier.preferenceHubEdgeFill())
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshingBattery,
        onRefresh = {
            scope.launch {
                refreshingBattery = true
                try {
                    onRefreshBattery()
                } finally {
                    refreshingBattery = false
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.preferenceHubScroll()) {
            val displayName = nodeName?.takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.settings_device_no_device_connected)
            val manufacturer = radioSnapshot()?.manufacturerName?.takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.settings_device_info_default_manufacturer)

            PreferenceHubSection(title = stringResource(Res.string.settings_device_header)) {
                DeviceIdentityHeader(nodeName = displayName, manufacturer = manufacturer)
            }

            PreferenceHubSection(
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
                title = stringResource(Res.string.settings_node_header),
            ) {
                NodeNameRow(
                    nodeName = displayName,
                    onSetNodeName = onSetNodeName.takeIf { ready },
                    onMaxRetriesExceeded = onMaxRetriesExceeded,
                )
                PublicKeyRow(
                    enabled = onOpenPublicKey != null && selfPublicKey() != null,
                    onOpen = { onOpenPublicKey?.invoke() },
                )
                if (onShareContact != null) {
                    OutlinedButton(
                        onClick = onShareContact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PreferenceHubCardPadding,
                                end = PreferenceHubCardPadding,
                                bottom = PreferenceHubCardPadding,
                            ),
                    ) {
                        Icon(
                            imageVector = AppIcons.Share,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        )
                        Text(stringResource(Res.string.settings_device_info_share_contact))
                    }
                }
            }
            PreferenceHubFooter(stringResource(Res.string.settings_node_footer))

            PreferenceHubSection(
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
                title = stringResource(Res.string.settings_device_info_connection_header),
            ) {
                ConnectionStatusRow()
            }

            DevicePowerStorageSection(
                batteryInfo = batteryInfo,
                ocvArray = ocvArray,
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
            )

            radioSnapshot()?.let { radio ->
                PreferenceHubSection(
                    modifier = Modifier.padding(top = PreferenceHubSectionGap),
                    title = stringResource(Res.string.settings_device_info_firmware_header),
                ) {
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_firmware_version),
                        radio.firmwareVersionString.ifEmpty {
                            formattedStringResource(
                                Res.string.settings_device_info_firmware_version_format,
                                radio.firmwareVersion,
                            )
                        },
                        icon = AppIcons.Memory,
                    )
                    val unknown = stringResource(Res.string.settings_device_info_unknown)
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_build_date),
                        radio.buildDate.ifEmpty { unknown },
                        icon = AppIcons.CalendarMonth,
                    )
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_manufacturer),
                        radio.manufacturerName.ifEmpty { unknown },
                        icon = AppIcons.Business,
                    )
                }
                PreferenceHubSection(
                    modifier = Modifier.padding(top = PreferenceHubSectionGap),
                    title = stringResource(Res.string.settings_device_info_capabilities_header),
                ) {
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_max_nodes),
                        radio.maxContacts.toString(),
                        icon = AppIcons.Group,
                    )
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_max_channels),
                        radio.maxChannels.toString(),
                        icon = AppIcons.Groups,
                    )
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_max_tx_power),
                        formattedStringResource(Res.string.settings_device_info_tx_power_format, radio.maxTxPower),
                        icon = AppIcons.Bolt,
                    )
                }
            }
        }
    }
}
