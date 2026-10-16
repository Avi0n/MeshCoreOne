package one.meshcore.shared.notification

import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.ui.formatTemplate

/**
 * Cached inputs for the radio-status spoken line. Mirrors iOS `StatusQueryIntent.dialogText`:
 * connection rung, best offline-readable name, and last-polled battery — never a radio round-trip.
 */
data class StatusQuerySnapshot(
    val appReady: Boolean,
    val state: DeviceConnectionState,
    val connectedName: String?,
    val offlineName: String?,
    val battery: BatteryInfo?,
    val ocvArray: List<Int>,
)

/** Localized templates for [statusQueryDialogText]. Specifiers are `%s` / `%d` (and positional). */
data class StatusQueryCopies(
    val notReady: String,
    val connectedWithBattery: String,
    val connectedNoBattery: String,
    val connecting: String,
    val disconnectedNamed: String,
    val disconnectedUnknown: String,
    val radioFallbackName: String,
)

/**
 * Builds the spoken radio-status line from cached state only. Connected rungs report the OCV
 * percent (never raw millivolts); a missing or mains-powered battery speaks "no reading", not 0%.
 */
fun statusQueryDialogText(snapshot: StatusQuerySnapshot, copies: StatusQueryCopies): String = when {
    !snapshot.appReady -> copies.notReady
    snapshot.state.isConnected -> {
        val name = spokenRadioName(snapshot.connectedName, snapshot.offlineName, copies.radioFallbackName)
        val battery = snapshot.battery
        if (battery == null || !BatteryDisplay.isBatteryPresent(battery)) {
            formatTemplate(copies.connectedNoBattery, listOf(name))
        } else {
            val percent = BatteryDisplay.percent(battery, snapshot.ocvArray)
            formatTemplate(copies.connectedWithBattery, listOf(name, percent))
        }
    }
    snapshot.state == DeviceConnectionState.CONNECTING -> {
        val name = spokenRadioName(null, snapshot.offlineName, copies.radioFallbackName)
        formatTemplate(copies.connecting, listOf(name))
    }
    else -> {
        val offline = snapshot.offlineName?.trim()?.takeIf { it.isNotEmpty() }
        if (offline != null) {
            formatTemplate(copies.disconnectedNamed, listOf(offline))
        } else {
            copies.disconnectedUnknown
        }
    }
}

private fun spokenRadioName(connectedName: String?, offlineName: String?, fallback: String): String =
    connectedName?.trim()?.takeIf { it.isNotEmpty() }
        ?: offlineName?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallback
