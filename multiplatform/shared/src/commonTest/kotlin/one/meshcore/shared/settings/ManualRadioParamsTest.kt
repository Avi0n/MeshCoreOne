package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManualRadioParamsTest {
    @Test
    fun parsesValidUsPresetFields() {
        val result = parseManualRadioParams(
            ManualRadioFormTexts("910.525", "62.5", "7", "5", "22"),
            maxTxPower = 22,
        )
        val ok = assertIs<ManualRadioParseResult.Ok>(result)
        assertEquals(910.525, ok.params.frequencyMHz)
        assertEquals(62.5, ok.params.bandwidthKHz)
        assertEquals(7, ok.params.spreadingFactor)
        assertEquals(5, ok.params.codingRate)
        assertEquals(22, ok.params.txPower)
    }

    @Test
    fun rejectsEmptyTxPower() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("869.618", "62.5", "8", "8", "")),
        )
    }

    @Test
    fun rejectsOutOfRangeSf() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "4", "5", "10")),
        )
    }

    @Test
    fun rejectsNonNumericFrequency() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("abc", "62.5", "7", "5", "10")),
        )
    }

    @Test
    fun rejectsNonFiniteFrequency() {
        // "NaN".toDoubleOrNull() returns NaN, which is outside the firmware range.
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("NaN", "62.5", "7", "5", "10")),
        )
    }

    @Test
    fun acceptsFrequenciesAboveTheOldNarrowerCap() {
        // The firmware range is 150-2500 MHz; a prior local cap of 960 MHz rejected valid 2.4 GHz
        // configs (and would have rejected the top of the 900 MHz US band too).
        val result = parseManualRadioParams(ManualRadioFormTexts("2400.0", "62.5", "7", "5", "20"))
        assertIs<ManualRadioParseResult.Ok>(result)
    }

    @Test
    fun rejectsFrequencyAboveTheFirmwareRange() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("2500.1", "62.5", "7", "5", "20")),
        )
    }

    @Test
    fun snapshotSeedsFormTexts() {
        val snap = RadioDeviceSnapshot(
            frequencyKHz = 910_525L,
            bandwidthHz = 62_500L,
            spreadingFactor = 7,
            codingRate = 5,
            txPower = 14,
        )
        val form = snap.toFormTexts()
        assertEquals("910.525", form.frequencyMHz)
        assertEquals("62.5", form.bandwidthKHz)
        assertEquals("7", form.spreadingFactor)
        assertEquals("5", form.codingRate)
        assertEquals("14", form.txPower)
    }

    @Test
    fun seedsBandwidthFromTheNearestStandardOption() {
        // Firmware stores bandwidth as a float and can report 7.8 kHz back as 7799 Hz; the raw value
        // would leave the picker with nothing selected.
        val snap = RadioDeviceSnapshot(
            frequencyKHz = 869_525L,
            bandwidthHz = 7_799L,
            spreadingFactor = 11,
            codingRate = 5,
        )
        assertEquals("7.8", snap.toFormTexts().bandwidthKHz)
    }

    @Test
    fun rejectsTxPowerAboveTheDeviceReportedCeiling() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "23"), maxTxPower = 22),
        )
        assertIs<ManualRadioParseResult.Ok>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "22"), maxTxPower = 22),
        )
    }

    @Test
    fun rejectsTxPowerTheRadioCannotProduceEvenWhenUnderTheOldFixedCeiling() {
        // A radio rated 14 dBm used to accept anything up to a hardcoded 22.
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "20"), maxTxPower = 14),
        )
    }

    @Test
    fun rejectsTxPowerBelowTheProtocolFloor() {
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "-10"), maxTxPower = 22),
        )
        assertIs<ManualRadioParseResult.Ok>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "-9"), maxTxPower = 22),
        )
    }

    @Test
    fun rejectsBandwidthOutsideTheOptionSet() {
        // 100 kHz is inside the old 0-500 kHz check but is not a bandwidth the mesh uses.
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "100", "7", "5", "20")),
        )
        assertIs<ManualRadioParseResult.Invalid>(
            parseManualRadioParams(ManualRadioFormTexts("910.525", "0.5", "7", "5", "20")),
        )
    }

    @Test
    fun acceptsEveryListedBandwidth() {
        RadioOptions.bandwidthsKHz.forEach { bandwidth ->
            assertIs<ManualRadioParseResult.Ok>(
                parseManualRadioParams(ManualRadioFormTexts("910.525", bandwidth.toString(), "7", "5", "20")),
                "bandwidth $bandwidth should be accepted",
            )
        }
    }

    @Test
    fun carriesTheRepeatModeFlagThrough() {
        val result = parseManualRadioParams(
            ManualRadioFormTexts("910.525", "62.5", "7", "5", "20"),
            clientRepeat = true,
        )
        val ok = assertIs<ManualRadioParseResult.Ok>(result)
        assertEquals(true, ok.params.clientRepeat)
        assertEquals(
            null,
            assertIs<ManualRadioParseResult.Ok>(
                parseManualRadioParams(ManualRadioFormTexts("910.525", "62.5", "7", "5", "20")),
            ).params.clientRepeat,
        )
    }

    @Test
    fun snapsAnOffBandFrequencyToTheNearestRepeatPreset() {
        val snapped = repeatModeSnappedFrequencyMHz(910.525)
        assertEquals(RadioPresets.nearestRepeatPreset(910_525L)?.frequencyMHz, snapped)
        assertTrue(RadioPresets.matchingRepeatPreset((snapped * 1000).toLong()) != null)
    }

    @Test
    fun leavesAnExactRepeatFrequencyAlone() {
        val repeat = RadioPresets.repeatPresets.first()
        assertEquals(repeat.frequencyMHz, repeatModeSnappedFrequencyMHz(repeat.frequencyMHz))
    }

    @Test
    fun restoresTheFrequencySavedBeforeRepeatMode() {
        val snap = RadioDeviceSnapshot(
            frequencyKHz = 869_495L,
            bandwidthHz = 62_500L,
            spreadingFactor = 11,
            codingRate = 5,
            clientRepeat = true,
            preRepeatFrequencyKHz = 910_525L,
        )
        assertEquals(910.525, snap.preRepeatFrequencyMHz())
        assertEquals(869.495, snap.copy(preRepeatFrequencyKHz = null).preRepeatFrequencyMHz())
    }

    @Test
    fun radioSettingsModifiedIgnoresAnUneditedForm() {
        val seeded = ManualRadioFormTexts("910.525", "62.5", "7", "5", "22")
        assertFalse(
            advancedRadioSettingsModified(
                current = seeded,
                seeded = seeded,
                clientRepeat = false,
                snapshotClientRepeat = false,
                supportsClientRepeat = true,
            ),
        )
    }

    @Test
    fun radioSettingsModifiedWhenFrequencyOrRepeatChanges() {
        val seeded = ManualRadioFormTexts("910.525", "62.5", "7", "5", "22")
        assertTrue(
            advancedRadioSettingsModified(
                current = seeded.copy(frequencyMHz = "869.618"),
                seeded = seeded,
                clientRepeat = false,
                snapshotClientRepeat = false,
                supportsClientRepeat = true,
            ),
        )
        assertTrue(
            advancedRadioSettingsModified(
                current = seeded,
                seeded = seeded,
                clientRepeat = true,
                snapshotClientRepeat = false,
                supportsClientRepeat = true,
            ),
        )
        assertFalse(
            advancedRadioSettingsModified(
                current = seeded,
                seeded = seeded,
                clientRepeat = true,
                snapshotClientRepeat = false,
                supportsClientRepeat = false,
            ),
        )
    }

    @Test
    fun canApplyRequiresConnectionChangeAndIdle() {
        assertTrue(advancedSettingsCanApply(true, true, false, false))
        assertFalse(advancedSettingsCanApply(false, true, false, false))
        assertFalse(advancedSettingsCanApply(true, false, false, false))
        assertFalse(advancedSettingsCanApply(true, true, true, false))
        assertFalse(advancedSettingsCanApply(true, true, false, true))
    }
}
