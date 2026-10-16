package one.meshcore.shared.app

import one.meshcore.shared.connection.AnsweredRadioRead
import one.meshcore.shared.connection.readAnsweredRadioCommand
import one.meshcore.shared.meshcore.BatteryInfo

/**
 * Reads the companion battery. A thrown failure is logged and comes back null so the last
 * reading stays. Cancellation still stops the caller, and an error still propagates.
 */
internal suspend fun readDeviceBattery(warning: (String) -> Unit, read: suspend () -> BatteryInfo): BatteryInfo? =
    when (val result = readAnsweredRadioCommand(warning, BATTERY_READ_FAILED, read)) {
        AnsweredRadioRead.Failed -> null
        is AnsweredRadioRead.Value -> result.value
    }

internal const val BATTERY_READ_FAILED = "Battery read failed"
