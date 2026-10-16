package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.BatteryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadDeviceBatteryTest {
    @Test
    fun aCancelledBatteryReadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readDeviceBattery(warnings::add) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aBatteryReadReturnsTheSample() = runTest {
        val sample = BatteryInfo(level = SAMPLE_MILLIVOLTS)
        val warnings = mutableListOf<String>()
        val reading = readDeviceBattery(warnings::add) { sample }
        assertSame(sample, reading)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aFailedBatteryReadIsLoggedAndSkipped() = runTest {
        val warnings = mutableListOf<String>()
        val reading = readDeviceBattery(warnings::add) { error("radio down") }
        assertNull(reading)
        assertEquals(listOf("$BATTERY_READ_FAILED: radio down"), warnings)
        assertEquals("Battery read failed", BATTERY_READ_FAILED)
    }

    @Test
    fun aBatteryReadErrorPropagates() = runTest {
        val warnings = mutableListOf<String>()
        val probe = BatteryReadProbeError()
        val thrown = assertFailsWith<BatteryReadProbeError> {
            readDeviceBattery(warnings::add) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class BatteryReadProbeError : Error("probe")

private const val SAMPLE_MILLIVOLTS = 3700
