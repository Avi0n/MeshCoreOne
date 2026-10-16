package one.meshcore.shared.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import one.meshcore.shared.resources.settings_advanced_settings_title
import one.meshcore.shared.resources.settings_ble_status_accessibility_hint_connected
import one.meshcore.shared.resources.settings_ble_status_accessibility_hint_disconnected
import one.meshcore.shared.resources.settings_ble_status_accessibility_label
import one.meshcore.shared.resources.settings_ble_status_change_device
import one.meshcore.shared.resources.settings_ble_status_disconnect
import one.meshcore.shared.resources.settings_ble_status_repeat_mode_active
import one.meshcore.shared.resources.settings_ble_status_send_flood_advert
import one.meshcore.shared.resources.settings_ble_status_send_flood_advert_hint
import one.meshcore.shared.resources.settings_ble_status_send_zero_hop_advert
import one.meshcore.shared.resources.settings_ble_status_send_zero_hop_advert_hint
import one.meshcore.shared.resources.settings_ble_status_status_connected
import one.meshcore.shared.resources.settings_ble_status_status_connecting
import one.meshcore.shared.resources.settings_ble_status_status_disconnected
import one.meshcore.shared.resources.settings_ble_status_status_ready
import one.meshcore.shared.resources.settings_ble_status_status_syncing
import one.meshcore.shared.resources.settings_device_connect
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuDivider
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import one.meshcore.shared.ui.menu.McMenuSectionHeader
import org.jetbrains.compose.resources.stringResource

/**
 * Radio status control for top app bar trailing actions (and any shell slot that needs the same menu).
 * Ported from the iOS `BLEStatusIndicatorView` toolbar menu: a single antenna glyph varies color/content
 * by connection state — never swapping between a button and a menu.
 *
 * Icon-only in the bar (battery lives in the dropdown header) so root tabs stay within Material's
 * 1–2 essential trailing actions. Menu: connect when disconnected; change-device, disconnect, and the
 * two advert sends when connected (adverts disabled until [DeviceConnectionState.READY]).
 */
@Composable
fun ConnectionChrome(
    connectionState: DeviceConnectionState,
    battery: BatteryInfo?,
    batteryOcvArray: List<Int>,
    nodeName: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onChangeDevice: () -> Unit,
    onSendAdvert: suspend (flood: Boolean) -> SendAdvertShortcutOutcome,
    onAdvancedSettings: () -> Unit,
    modifier: Modifier = Modifier,
    clientRepeat: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var isSendingAdvert by remember { mutableStateOf(false) }
    val sendScope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val sendAdvert: (Boolean) -> Unit = { flood ->
        if (!isSendingAdvert) {
            isSendingAdvert = true
            menuOpen = false
            sendScope.launch {
                try {
                    val outcome = onSendAdvert(flood)
                    if (advertSendSucceeded(outcome)) haptics.success() else haptics.failure()
                } finally {
                    isSendingAdvert = false
                }
            }
        }
    }
    val menuLabel = stringResource(Res.string.settings_ble_status_accessibility_label)
    val statusTitle = stringResource(bleStatusTitleResource(connectionState))
    val hint = stringResource(bleStatusHintResource(connectionState.isConnected))
    val spokenState = "$statusTitle. $hint"

    Box(modifier) {
        AppIconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics {
                contentDescription = menuLabel
                stateDescription = spokenState
            },
        ) {
            AntennaGlyph(connectionState, clientRepeat)
        }
        McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (connectionState.isConnected) {
                ConnectedMenu(
                    nodeName = nodeName,
                    battery = battery,
                    batteryOcvArray = batteryOcvArray,
                    isReady = connectionState == DeviceConnectionState.READY,
                    clientRepeat = clientRepeat,
                    onChangeDevice = {
                        menuOpen = false
                        onChangeDevice()
                    },
                    onDisconnect = {
                        menuOpen = false
                        onDisconnect()
                    },
                    onSendAdvert = sendAdvert,
                    isSendingAdvert = isSendingAdvert,
                    onAdvancedSettings = {
                        menuOpen = false
                        onAdvancedSettings()
                    },
                )
            } else {
                McMenuItem(
                    text = stringResource(Res.string.settings_device_connect),
                    leadingIcon = AppIcons.CellTower,
                    onClick = {
                        menuOpen = false
                        onConnect()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectedMenu(
    nodeName: String?,
    battery: BatteryInfo?,
    batteryOcvArray: List<Int>,
    isReady: Boolean,
    clientRepeat: Boolean,
    onChangeDevice: () -> Unit,
    onDisconnect: () -> Unit,
    onSendAdvert: (flood: Boolean) -> Unit,
    isSendingAdvert: Boolean,
    onAdvancedSettings: () -> Unit,
) {
    if (nodeName != null || battery != null) {
        MenuHeader(nodeName, battery, batteryOcvArray)
        McMenuDivider()
    }
    if (clientRepeat) {
        McMenuSectionHeader(text = stringResource(Res.string.settings_ble_status_repeat_mode_active))
    }
    McMenuItem(
        text = stringResource(Res.string.settings_ble_status_change_device),
        leadingIcon = AppIcons.Sync,
        onClick = onChangeDevice,
    )
    val zeroHopHint = stringResource(Res.string.settings_ble_status_send_zero_hop_advert_hint)
    val floodHint = stringResource(Res.string.settings_ble_status_send_flood_advert_hint)
    val requiresRadio = stringResource(Res.string.localizable_accessibility_requires_radio_connection)
    McMenuItem(
        text = stringResource(Res.string.settings_ble_status_send_zero_hop_advert),
        onClick = { onSendAdvert(false) },
        modifier = Modifier.semantics {
            stateDescription = if (isReady) zeroHopHint else requiresRadio
        },
        leadingIcon = AppIcons.Sensors,
        enabled = advertRowsEnabled(isReady, isSendingAdvert),
    )
    McMenuItem(
        text = stringResource(Res.string.settings_ble_status_send_flood_advert),
        onClick = { onSendAdvert(true) },
        modifier = Modifier.semantics {
            stateDescription = if (isReady) floodHint else requiresRadio
        },
        leadingIcon = AppIcons.CellTower,
        enabled = advertRowsEnabled(isReady, isSendingAdvert),
    )
    McMenuDivider()
    McMenuItem(
        text = stringResource(Res.string.settings_advanced_settings_title),
        leadingIcon = AppIcons.Settings,
        onClick = onAdvancedSettings,
    )
    McMenuDivider()
    McMenuItem(
        text = stringResource(Res.string.settings_ble_status_disconnect),
        leadingIcon = AppIcons.LinkOff,
        role = McMenuRole.Destructive,
        onClick = onDisconnect,
    )
}

@Composable
private fun MenuHeader(nodeName: String?, battery: BatteryInfo?, batteryOcvArray: List<Int>) {
    Row(
        modifier = Modifier.padding(horizontal = MENU_HEADER_H_PADDING, vertical = MENU_HEADER_V_PADDING),
        horizontalArrangement = Arrangement.spacedBy(CHROME_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nodeName != null) {
            Text(text = nodeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (battery != null) {
            // Plain numeric interpolation, not an L10n string: the percent/voltage figures are locale-
            // invariant digits, mirroring the iOS BLEStatusIndicatorView's own inline string interpolation.
            Text(
                text =
                "${BatteryDisplay.percentText(battery, batteryOcvArray)} (${BatteryDisplay.voltageText(battery)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AntennaGlyph(state: DeviceConnectionState, clientRepeat: Boolean) {
    val color = if (clientRepeat) MaterialTheme.colorScheme.tertiary else state.antennaColor()
    // Read the animated alpha inside graphicsLayer so the infinite pulse invalidates only the draw phase;
    // a composition-time read would recompose the glyph every frame for the whole connecting state.
    val pulse = if (state == DeviceConnectionState.CONNECTING) {
        rememberInfiniteTransition(label = "antennaPulse").animateFloat(
            initialValue = PULSE_MIN_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MS, easing = LinearEasing), RepeatMode.Reverse),
            label = "antennaPulseAlpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    Icon(
        imageVector = if (state == DeviceConnectionState.DISCONNECTED) {
            AppIcons.CellTowerOff
        } else {
            AppIcons.CellTower
        },
        contentDescription = null,
        tint = color,
        modifier = Modifier.graphicsLayer { alpha = pulse.value },
    )
}

@Composable
private fun DeviceConnectionState.antennaColor(): Color = when (this) {
    DeviceConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    DeviceConnectionState.CONNECTING,
    DeviceConnectionState.CONNECTED,
    DeviceConnectionState.SYNCING,
    -> MaterialTheme.colorScheme.primary
    DeviceConnectionState.READY -> SAFETY_READY_GREEN
}

// Ready antenna and ready pill. Fixed hex so outdoor glare does not wash out a theme tint.
internal val SAFETY_READY_GREEN = Color(0xFF2E7D32)

private const val PULSE_MIN_ALPHA = 0.3f
private const val PULSE_PERIOD_MS = 700

private fun bleStatusTitleResource(state: DeviceConnectionState) = when (state) {
    DeviceConnectionState.DISCONNECTED -> Res.string.settings_ble_status_status_disconnected
    DeviceConnectionState.CONNECTING -> Res.string.settings_ble_status_status_connecting
    DeviceConnectionState.CONNECTED -> Res.string.settings_ble_status_status_connected
    DeviceConnectionState.SYNCING -> Res.string.settings_ble_status_status_syncing
    DeviceConnectionState.READY -> Res.string.settings_ble_status_status_ready
}

/** Both advert rows stay off while a send is in flight, matching iOS `isSendingAdvert`. */
internal fun advertRowsEnabled(isReady: Boolean, isSendingAdvert: Boolean): Boolean = isReady && !isSendingAdvert

/** Toolbar Send Advert success haptic only when the radio accepted the send. */
internal fun advertSendSucceeded(outcome: SendAdvertShortcutOutcome): Boolean =
    outcome == SendAdvertShortcutOutcome.Sent

/** TalkBack hint: connect-device when idle, options when linked. */
internal fun bleStatusHintResource(isConnected: Boolean) = if (isConnected) {
    Res.string.settings_ble_status_accessibility_hint_connected
} else {
    Res.string.settings_ble_status_accessibility_hint_disconnected
}

private val CHROME_GAP = 8.dp
private val MENU_HEADER_H_PADDING = 12.dp
private val MENU_HEADER_V_PADDING = 8.dp
