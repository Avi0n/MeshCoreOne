package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.SelfInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RadioSettingReadsTest {
    @Test
    fun aCancelledSelfInfoReadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readReportedSelfInfo(warnings::add) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aSelfInfoReadReturnsTheSample() = runTest {
        val sample = sampleSelfInfo()
        val warnings = mutableListOf<String>()
        val reading = readReportedSelfInfo(warnings::add) { sample }
        assertSame(sample, reading)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aFailedSelfInfoReadIsLoggedAndSkipped() = runTest {
        val warnings = mutableListOf<String>()
        val reading = readReportedSelfInfo(warnings::add) { error("radio down") }
        assertNull(reading)
        assertEquals(listOf("$SELF_INFO_READ_FAILED: radio down"), warnings)
        assertEquals("Self info read failed", SELF_INFO_READ_FAILED)
    }

    @Test
    fun aSelfInfoReadErrorPropagates() = runTest {
        val warnings = mutableListOf<String>()
        val probe = SelfInfoReadProbeError()
        val thrown = assertFailsWith<SelfInfoReadProbeError> {
            readReportedSelfInfo(warnings::add) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCancelledAutoAddReadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readAutoAddConfigOrStored(warnings::add, STORED_POLICY) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anAutoAddReadReturnsTheRadioPolicy() = runTest {
        val live = AutoAddConfig(bitmask = LIVE_BITMASK, maxHops = LIVE_MAX_HOPS)
        val warnings = mutableListOf<String>()
        val reading = readAutoAddConfigOrStored(warnings::add, STORED_POLICY) { live }
        assertSame(live, reading)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aFailedAutoAddReadIsLoggedAndKeepsTheStoredPolicy() = runTest {
        val warnings = mutableListOf<String>()
        val reading = readAutoAddConfigOrStored(warnings::add, STORED_POLICY) { error("radio down") }
        assertSame(STORED_POLICY, reading)
        assertEquals(listOf("$AUTO_ADD_CONFIG_READ_FAILED: radio down"), warnings)
        assertEquals("Auto-add config read failed", AUTO_ADD_CONFIG_READ_FAILED)
    }

    @Test
    fun anAutoAddReadErrorPropagates() = runTest {
        val warnings = mutableListOf<String>()
        val probe = AutoAddReadProbeError()
        val thrown = assertFailsWith<AutoAddReadProbeError> {
            readAutoAddConfigOrStored(warnings::add, STORED_POLICY) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private fun sampleSelfInfo(): SelfInfo = SelfInfo(
    advertisementType = SAMPLE_ADVERT_TYPE,
    txPower = SAMPLE_TX_POWER,
    maxTxPower = SAMPLE_TX_POWER,
    publicKey = byteArrayOf(1),
    latitude = 0.0,
    longitude = 0.0,
    multiAcks = 0,
    advertisementLocationPolicy = 0,
    telemetryModeEnvironment = 0,
    telemetryModeLocation = 0,
    telemetryModeBase = 0,
    manualAddContacts = false,
    radioFrequency = SAMPLE_FREQUENCY_MHZ,
    radioBandwidth = SAMPLE_BANDWIDTH_KHZ,
    radioSpreadingFactor = SAMPLE_SPREADING_FACTOR,
    radioCodingRate = SAMPLE_CODING_RATE,
    name = SAMPLE_NAME,
)

private class SelfInfoReadProbeError : Error("probe")

private class AutoAddReadProbeError : Error("probe")

private val STORED_POLICY = AutoAddConfig(bitmask = STORED_BITMASK, maxHops = STORED_MAX_HOPS)

private const val SAMPLE_ADVERT_TYPE = 1
private const val SAMPLE_TX_POWER = 22
private const val SAMPLE_FREQUENCY_MHZ = 915.0
private const val SAMPLE_BANDWIDTH_KHZ = 250.0
private const val SAMPLE_SPREADING_FACTOR = 7
private const val SAMPLE_CODING_RATE = 5
private const val SAMPLE_NAME = "Ridge"
private const val STORED_BITMASK = 1
private const val STORED_MAX_HOPS = 2
private const val LIVE_BITMASK = 3
private const val LIVE_MAX_HOPS = 4
