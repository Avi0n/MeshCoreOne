package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The k-factor scales the effective earth radius, so a preset mapped to the wrong number changes whether
 * a path reads as clear without anything looking wrong on screen.
 */
class RefractionPresetTest {
    @Test
    fun theKFactorsMatchTheOnesIosOffers() {
        assertEquals(1.0, RefractionPreset.NONE.kFactor)
        assertEquals(4.0 / 3.0, RefractionPreset.STANDARD.kFactor)
        assertEquals(4.0, RefractionPreset.DUCTING.kFactor)
    }

    @Test
    fun aStoredKFactorSelectsItsOwnPreset() {
        RefractionPreset.entries.forEach { preset ->
            assertEquals(preset, RefractionPreset.forKFactor(preset.kFactor))
        }
    }

    /** Standard's k is a recurring third, so a value round-tripped through text must still match. */
    @Test
    fun aRoundedStandardStillSelectsStandard() {
        assertEquals(RefractionPreset.STANDARD, RefractionPreset.forKFactor(ROUNDED_STANDARD))
    }

    /** A value from an older build or a hand-edited preference lands on the planning default. */
    @Test
    fun anUnknownKFactorFallsBackToStandardRatherThanNothing() {
        assertEquals(RefractionPreset.STANDARD, RefractionPreset.forKFactor(OFF_GRID_K))
    }

    /** iOS captions the profile with two fraction digits, so 4/3 reads as 1.33 rather than 1.3333333. */
    @Test
    fun theCaptionStatesTheKFactorToTwoDigits() {
        assertEquals("k=1.00", formatKFactor(RefractionPreset.NONE.kFactor))
        assertEquals("k=1.33", formatKFactor(RefractionPreset.STANDARD.kFactor))
        assertEquals("k=4.00", formatKFactor(RefractionPreset.DUCTING.kFactor))
    }

    /** iOS shows a fraction digit only when there is one, so 906 does not read as 906.0. */
    @Test
    fun theFootnoteStatesTheFrequencyWithoutATrailingZero() {
        assertEquals("906 MHz", formatFrequencyMHz(WHOLE_MHZ))
        assertEquals("868.5 MHz", formatFrequencyMHz(FRACTIONAL_MHZ))
    }

    @Test
    fun largeFrequencyCaptionsPreserveTheCommittedMagnitude() {
        val scaledBoundary = Long.MAX_VALUE.toDouble() / 10
        for (frequency in listOf(scaledBoundary, 1e20, Double.MAX_VALUE)) {
            val number = formatFrequencyMHz(frequency).removeSuffix(" MHz")
            assertEquals(frequency, parseLosNumber(number))
        }
    }

    @Test
    fun ordinaryFrequencyCaptionsStillRoundToOneDecimal() {
        assertEquals("915.5 MHz", formatFrequencyMHz(915.525))
        assertEquals("915.6 MHz", formatFrequencyMHz(915.56))
        assertEquals("0.1 MHz", formatFrequencyMHz(0.1))
    }

    private companion object {
        const val ROUNDED_STANDARD = 1.333
        const val OFF_GRID_K = 2.5
        const val WHOLE_MHZ = 906.0
        const val FRACTIONAL_MHZ = 868.5
    }
}
