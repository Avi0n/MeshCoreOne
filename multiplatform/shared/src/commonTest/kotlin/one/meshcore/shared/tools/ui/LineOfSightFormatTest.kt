package one.meshcore.shared.tools.ui

import one.meshcore.shared.remotenode.MeasurementSystem
import one.meshcore.shared.tools.ADDITIONAL_HEIGHT_STEP_IMPERIAL_M
import one.meshcore.shared.tools.ADDITIONAL_HEIGHT_STEP_M
import one.meshcore.shared.tools.additionalHeightStepMeters
import one.meshcore.shared.tools.formatLosDistanceMeters
import one.meshcore.shared.tools.formatLosHeightMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineOfSightFormatTest {
    @Test
    fun diffractionLossOmitsNegligibleValues() {
        assertNull(formatDiffractionLossDisplay(0.0))
        assertNull(formatDiffractionLossDisplay(0.09))
        assertNull(formatDiffractionLossDisplay(-0.05))
    }

    @Test
    fun diffractionLossPrefixesVisibleValues() {
        assertEquals("+ 8.4 dB", formatDiffractionLossDisplay(8.4))
        assertEquals("+ 0.1 dB", formatDiffractionLossDisplay(0.1))
    }

    @Test
    fun clearancePercentClampsToZeroThroughOneHundred() {
        assertEquals(0, formatClearancePercent(-12.0))
        assertEquals(100, formatClearancePercent(140.0))
        assertEquals(59, formatClearancePercent(59.4))
    }

    @Test
    fun additionalHeightStepIsOneMetreOrOneFoot() {
        assertEquals(ADDITIONAL_HEIGHT_STEP_M, additionalHeightStepMeters(MeasurementSystem.METRIC))
        assertEquals(
            ADDITIONAL_HEIGHT_STEP_IMPERIAL_M,
            additionalHeightStepMeters(MeasurementSystem.IMPERIAL),
        )
    }

    @Test
    fun additionalHeightReadoutUsesLocaleUnits() {
        assertEquals("7.0 m", formatLosHeightMeters(7.0, MeasurementSystem.METRIC))
        assertEquals("23 ft", formatLosHeightMeters(7.0, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun pathDistanceUsesRoadUnits() {
        assertEquals("12.4 km", formatLosDistanceMeters(12_400.0, MeasurementSystem.METRIC))
        assertEquals("7 m", formatLosDistanceMeters(7.0, MeasurementSystem.METRIC))
        assertEquals("7.7 mi", formatLosDistanceMeters(12_400.0, MeasurementSystem.IMPERIAL))
        assertEquals("23 ft", formatLosDistanceMeters(7.0, MeasurementSystem.IMPERIAL))
    }
}
