package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.connection.bleSignalTier
import one.meshcore.shared.connection.wordResource
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_device_selection_accessibility_device_label
import one.meshcore.shared.resources.settings_device_selection_accessibility_out_of_range_hint
import org.jetbrains.compose.resources.stringResource

/** Host and port for iOS `DeviceSelectionSheet` Edit on a saved WiFi radio, or null when BLE-only. */
internal fun savedWifiEditDraft(device: SavedRadioDevice): Pair<String, Int>? {
    val wifi = device.wifi ?: return null
    return wifi.host to wifi.port
}

/**
 * iOS `DeviceRow.isUnreachable`: a BLE-only saved row with no live advertisement. WiFi rows stay
 * reachable without RSSI.
 */
internal fun savedBleDeviceIsUnreachable(connectsViaWiFi: Boolean, rssi: Int?): Boolean =
    !connectsViaWiFi && rssi == null

/** iOS `isDisabledByBLE`: an unreachable saved BLE row is not selectable. */
internal fun savedBleRowIsEnabled(connecting: Boolean, isConnectable: Boolean, unreachable: Boolean): Boolean =
    !connecting && isConnectable && !unreachable

/** iOS `DeviceRow` VoiceOver hint when a saved BLE radio is held by another app. */
internal fun savedDeviceElsewhereHint(elsewhere: Boolean, hint: String): String? = hint.takeIf { elsewhere }

/** Spoken status and row label for one saved radio. Elsewhere rows speak the other-app phrase alone. */
internal data class SavedDeviceSpeech(val status: String, val label: String)

/** Inputs for [savedDeviceSpeech]. The tier word is already resolved so the decision stays pure. */
internal data class SavedDeviceSpeechInput(
    val elsewhere: Boolean,
    val unreachable: Boolean,
    val deviceName: String,
    val methodLabel: String,
    val elsewhereSpoken: String,
    val outOfRange: String,
    val labelTemplate: String,
    val tierWord: String?,
)

internal fun savedDeviceSpeech(input: SavedDeviceSpeechInput): SavedDeviceSpeech {
    val status = when {
        input.elsewhere -> formatTemplate(input.elsewhereSpoken, listOf(input.deviceName))
        input.unreachable -> input.outOfRange
        input.tierWord != null -> "${input.methodLabel}, ${input.tierWord}"
        else -> input.methodLabel
    }
    val label = if (input.elsewhere) {
        status
    } else {
        formatTemplate(input.labelTemplate, listOf(input.deviceName, status))
    }
    return SavedDeviceSpeech(status, label)
}

/**
 * Spoken name and signal for a live scan row. The bar glyph is the only signal cue and reads as block
 * characters, so the description carries the strength in words. A missing reading means the radio is out
 * of range.
 */
@Composable
internal fun scannedDeviceAccessibilityLabel(name: String, rssi: Int?): String {
    val signalWord = rssi?.let { stringResource(bleSignalTier(it).wordResource()) }
    return scannedDeviceSpokenLabel(
        name = name,
        signalWord = signalWord,
        outOfRange = stringResource(Res.string.settings_device_selection_accessibility_out_of_range_hint),
        template = stringResource(Res.string.settings_device_selection_accessibility_device_label),
    )
}

/** Fills the device-label template with [signalWord], or [outOfRange] when the scan has no reading. */
internal fun scannedDeviceSpokenLabel(name: String, signalWord: String?, outOfRange: String, template: String): String =
    formatTemplate(template, listOf(name, signalWord ?: outOfRange))
