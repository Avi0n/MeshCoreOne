package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LastValueCaptionTest {
    @Test
    fun dualSeriesUsesBothLabels() {
        assertEquals(
            "Battery 12, Noise 3 dBm",
            lastValueCaption(12.0, 3.0, "dBm", "Battery", "Noise"),
        )
    }

    @Test
    fun aSingleSeriesOmitsTheMissingSide() {
        assertEquals("12 dBm", lastValueCaption(12.0, null, "dBm", "Battery", "Noise"))
        assertEquals("3 dBm", lastValueCaption(null, 3.0, "dBm", null, "Noise"))
    }

    @Test
    fun aMissingLabelFallsBackToTheValue() {
        assertEquals("12 dBm", lastValueCaption(12.0, 3.0, "dBm", null, "Noise"))
        assertEquals("12 dBm", lastValueCaption(12.0, 3.0, "dBm", "Battery", null))
    }

    @Test
    fun nothingToShowIsBlank() {
        assertEquals("", lastValueCaption(null, null, "dBm", "Battery", "Noise"))
        assertEquals("1", lastValueCaption(1.0, null, "", null, null))
    }
}
