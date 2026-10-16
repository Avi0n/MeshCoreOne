package one.meshcore.shared.battery

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The discharge curve's Y axis matches the 0.1 V rounding around a 100 mV pad. */
class BatteryCurveVertexTest {
    @Test
    fun yDomainRoundsThePadToATenthOfAVolt() {
        val domain = batteryCurveYDomain(PREVIEW_OCV)
        assertEquals(3.0, domain.start)
        assertEquals(4.3, domain.endInclusive)
    }

    @Test
    fun aPadBelowZeroRoundsDown() {
        val domain = batteryCurveYDomain(listOf(50))
        assertEquals(-0.1, domain.start, absoluteTolerance = 1e-9)
        assertEquals(0.2, domain.endInclusive, absoluteTolerance = 1e-9)
    }

    @Test
    fun anEmptyCurveUsesTheDefaultDomain() {
        val domain = batteryCurveYDomain(emptyList())
        assertEquals(1.0, domain.start)
        assertEquals(4.5, domain.endInclusive)
        assertTrue(batteryCurveVertices(emptyList()).isEmpty())
    }

    @Test
    fun aConstantCurveKeepsANonZeroSpan() {
        val samples = List(PREVIEW_OCV.size) { CONSTANT_MV }
        val domain = batteryCurveYDomain(samples)
        assertEquals(3.6, domain.start, absoluteTolerance = 1e-9)
        assertEquals(3.8, domain.endInclusive, absoluteTolerance = 1e-9)
        val vertices = batteryCurveVertices(samples)
        assertEquals(PREVIEW_OCV.size, vertices.size)
        assertTrue(vertices.all { abs(it.yFraction - 0.5) < 1e-6 })
    }

    @Test
    fun previewSamplesStayStraightFromEmptyToFull() {
        val vertices = batteryCurveVertices(PREVIEW_OCV)
        assertEquals(PREVIEW_OCV.size, vertices.size)
        assertEquals(List(PREVIEW_OCV.size) { it / 10.0 }, vertices.map { it.xFraction })
        assertEquals(PREVIEW_OCV.last() / MILLIVOLTS_PER_VOLT, vertices.first().volts)
        assertEquals(PREVIEW_OCV.first() / MILLIVOLTS_PER_VOLT, vertices.last().volts)
        assertEquals(0.1 / 1.3, vertices.first().yFraction, absoluteTolerance = 1e-4)
    }

    @Test
    fun percentTicksAndVoltLabelsKeepTheRoundedEnds() {
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), batteryCurveXTicks())
        val hundredths = batteryCurveYTickHundredths(PREVIEW_OCV)
        assertEquals(300L, hundredths.first())
        assertEquals(430L, hundredths.last())
        assertEquals(3.0, hundredths.first() / HUNDREDTHS_PER_VOLT)
        assertEquals(4.3, hundredths.last() / HUNDREDTHS_PER_VOLT)
        assertEquals("3.0", batteryVoltLabel(hundredths.first()))
        assertEquals("4.3", batteryVoltLabel(hundredths.last()))
        assertEquals("-0.1", batteryVoltLabel(-10))
        assertEquals("3.65", batteryVoltLabel(365))
        assertEquals("0", batteryPercentLabel(0.0))
        assertEquals("100", batteryPercentLabel(1.0))
    }

    private companion object {
        const val CONSTANT_MV = 3700
        const val MILLIVOLTS_PER_VOLT = 1000.0
        const val HUNDREDTHS_PER_VOLT = 100.0
        val PREVIEW_OCV = listOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
    }
}
