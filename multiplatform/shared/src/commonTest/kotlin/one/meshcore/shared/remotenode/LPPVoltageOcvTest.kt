package one.meshcore.shared.remotenode

import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LPPVoltageOcvTest {
    @Test
    fun voltageFloatYieldsOcvPercent() {
        val point = LPPDataPoint(
            channel = 1,
            type = LPPSensorType.VOLTAGE,
            value = LPPValue.FloatValue(3.7),
        )
        val percent = voltageOcvPercent(point, OCVPreset.LI_ION.ocvArray)
        assertEquals(BatteryOcv.percentage(3700, OCVPreset.LI_ION.ocvArray), percent)
    }

    @Test
    fun nonVoltageIsNull() {
        val point = LPPDataPoint(
            channel = 1,
            type = LPPSensorType.TEMPERATURE,
            value = LPPValue.FloatValue(21.0),
        )
        assertNull(voltageOcvPercent(point, OCVPreset.LI_ION.ocvArray))
    }
}
