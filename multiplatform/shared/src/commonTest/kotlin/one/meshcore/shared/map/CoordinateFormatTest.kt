package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CoordinateFormatTest {
    @Test
    fun aPairUsesSixFractionalDigitsAndNeverScientificNotation() {
        assertEquals("37.334900, -122.009020", formatCoordinatePair(37.3349, -122.00902))
        assertEquals("0.000001, -0.000001", formatCoordinatePair(0.000001, -0.000001))
        assertFalse(formatCoordinatePair(1e-7, -1e-7).contains("E", ignoreCase = true))
        assertEquals("0.000000, -0.000000", formatCoordinatePair(1e-7, -1e-7))
    }

    @Test
    fun fiveDigitsStayPaddedAndKeepASignOnARoundedZero() {
        assertEquals("37.33490", formatFixedFraction(37.3349, fractionDigits = 5))
        assertEquals("-122.00902", formatFixedFraction(-122.00902, fractionDigits = 5))
        assertEquals("-0.00000", formatFixedFraction(-1e-7, fractionDigits = 5))
    }

    @Test
    fun roundedFiveDropsTrailingZeros() {
        assertEquals("37.7749", formatRounded5(37.7749))
        assertEquals("1.0", formatRounded5(1.0))
        assertEquals("-122.4194", formatRounded5(-122.4194))
    }
}
