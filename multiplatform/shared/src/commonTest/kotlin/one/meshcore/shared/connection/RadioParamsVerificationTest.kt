package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioParamsVerificationTest {
    private val written = LoRaParams(
        frequencyMHz = 906.875,
        bandwidthKHz = 62.5,
        spreadingFactor = 10,
        codingRate = 8,
    )

    @Test
    fun readBackEqualToTheWriteMatches() {
        assertTrue(written.matches(written))
    }

    @Test
    fun doubleConversionNoiseStillMatches() {
        val reported = written.copy(
            frequencyMHz = written.frequencyMHz + 0.0001,
            bandwidthKHz = written.bandwidthKHz - 0.0001,
        )
        assertTrue(written.matches(reported))
    }

    @Test
    fun aRadioThatKeptItsOldFrequencyDoesNotMatch() {
        assertFalse(written.matches(written.copy(frequencyMHz = 915.0)))
    }

    @Test
    fun aRadioThatKeptItsOldBandwidthDoesNotMatch() {
        assertFalse(written.matches(written.copy(bandwidthKHz = 250.0)))
    }

    @Test
    fun aRefusedSpreadingFactorDoesNotMatch() {
        assertFalse(written.matches(written.copy(spreadingFactor = 7)))
    }

    @Test
    fun aRefusedCodingRateDoesNotMatch() {
        assertFalse(written.matches(written.copy(codingRate = 5)))
    }

    @Test
    fun theFailureMessageNamesEveryParameter() {
        val description = written.describe()
        assertTrue(description.contains("906.875"))
        assertTrue(description.contains("62.5"))
        assertTrue(description.contains("sf=10"))
        assertTrue(description.contains("cr=8"))
    }

    @Test
    fun radioParamsDumpMatchesTheIosLine() {
        val reported = written.copy(frequencyMHz = 915.0)
        val line = radioParamsVerificationDump(written, reported)
        assertTrue(line.startsWith("[Radio] Verification failed - expected:"))
        assertTrue(line.contains("freq=906.875MHz"))
        assertTrue(line.contains("bw=62.5kHz"))
        assertTrue(line.contains("sf=10"))
        assertTrue(line.contains("device reports: freq=915.0MHz"))
    }

    @Test
    fun txPowerDumpMatchesTheIosLine() {
        val line = radioTxPowerVerificationDump(22, 14)
        assertEquals(
            "[Radio] TX power verification failed - expected: 22dBm, device reports: 14dBm",
            line,
        )
    }

    @Test
    fun clientRepeatDumpMatchesTheIosLine() {
        val line = radioClientRepeatVerificationDump(expected = true, reported = false)
        assertEquals(
            "[Radio] Client repeat verification failed - expected: true, device reports: false",
            line,
        )
    }
}
