package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * iOS `CurrentReadingSection` paints the number as `.largeTitle` and `dBm` as a `.title3`
 * secondary subtitle, not a concatenated `"N dBm"` line.
 */
class NoiseFloorCurrentReadingVisibleTest {
    @Test
    fun valueLineIsTheSignedNumberOnly() {
        assertEquals("-100", noiseFloorCurrentValueText(-100))
        assertFalse(noiseFloorCurrentValueText(-100).contains("dBm"))
    }
}
