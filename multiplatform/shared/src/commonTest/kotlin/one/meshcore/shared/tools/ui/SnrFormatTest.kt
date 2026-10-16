package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SnrFormatTest {
    @Test
    fun negativeFractionsKeepTheirSign() {
        // The (-1, 0) band is the defect: the whole part is 0, so a truncating format drops the minus
        // and a marginal link reads as a usable one.
        assertEquals("-0.5", formatOneDecimalSigned(-0.5))
        assertEquals("-0.1", formatOneDecimalSigned(-0.1))
        assertEquals("-0.9", formatOneDecimalSigned(-0.9))
    }

    @Test
    fun otherMagnitudesAreUnchanged() {
        assertEquals("0.0", formatOneDecimalSigned(0.0))
        assertEquals("0.5", formatOneDecimalSigned(0.5))
        assertEquals("2.3", formatOneDecimalSigned(2.3))
        assertEquals("-1.5", formatOneDecimalSigned(-1.5))
        assertEquals("-102.7", formatOneDecimalSigned(-102.75))
    }
}
