package one.meshcore.shared.remotenode

import one.meshcore.shared.battery.BatteryOcv

private const val MILLIVOLTS_PER_VOLT = 1000.0

/**
 * OCV percent for a live voltage LPP point, or null when the point is not a voltage float.
 * iOS `NodeTelemetryRow` shows this as a caption under the volts.
 */
fun voltageOcvPercent(point: LPPDataPoint, ocvValues: List<Int>): Int? {
    if (point.type != LPPSensorType.VOLTAGE) return null
    val volts = (point.value as? LPPValue.FloatValue)?.value ?: return null
    return BatteryOcv.percentage((volts * MILLIVOLTS_PER_VOLT).toInt(), ocvValues)
}
