package one.meshcore.shared.contact.ui

import one.meshcore.shared.ui.durationMsVisibleText
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ping success chrome keeps two-decimal SNR; direction is Material arrows, not Unicode. */
class PingResultVisibleTextTest {
    @Test
    fun successPartsUseTwoDecimalSnr() {
        assertEquals(
            PingSuccessVisibleParts(latencyMs = 42, snrThere = "3.50", snrBack = "1.25"),
            pingSuccessVisibleParts(latencyMs = 42, snrThere = 3.5, snrBack = 1.25),
        )
    }

    @Test
    fun successPartsPadSingleDigitFraction() {
        assertEquals(
            PingSuccessVisibleParts(latencyMs = 10, snrThere = "0.00", snrBack = "-0.50"),
            pingSuccessVisibleParts(latencyMs = 10, snrThere = 0.0, snrBack = -0.5),
        )
    }

    @Test
    fun successLatencyUsesTheMsTemplate() {
        assertEquals("42 ms", durationMsVisibleText(42, "%d ms"))
    }

    @Test
    fun successSnrReadingKeepsTheLocalizedUnit() {
        assertEquals("3.50 dB", pingSnrReadingVisibleText("3.50", "dB"))
    }
}
