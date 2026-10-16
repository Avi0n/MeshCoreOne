package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.HopSnrStats
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceSnrFormatTest {
    @Test
    fun marginalNegativeSnrKeepsItsSign() {
        // The (-1, 0) band is the whole defect: integer division of the tenths gives a whole part of 0
        // and drops the minus, so a marginal link reads as a good one.
        assertEquals("-0.5 dB", formatSnr(-0.5, unit = "dB"))
        assertEquals("-0.1 dB", formatSnr(-0.1, unit = "dB"))
        assertEquals("-0.9 dB", formatSnr(-0.9, unit = "dB"))
    }

    @Test
    fun otherMagnitudesAreUnchanged() {
        assertEquals("0.0 dB", formatSnr(0.0, unit = "dB"))
        assertEquals("2.3 dB", formatSnr(2.3, unit = "dB"))
        assertEquals("-1.5 dB", formatSnr(-1.5, unit = "dB"))
        assertEquals("-12.0 dB", formatSnr(-12.0, unit = "dB"))
    }

    @Test
    fun hopSnrCaptionOmitsTheStartNodeAndUsesLocalizedTemplates() {
        assertEquals(
            null,
            hopSnrCaption(
                isStartNode = true,
                hopSnr = 1.0,
                stats = null,
                snrTemplate = "SNR: %s dB",
                avgTemplate = "Avg SNR: %s dB (%s – %s)",
            ),
        )
        assertEquals(
            "SNR: -0.5 dB",
            hopSnrCaption(
                isStartNode = false,
                hopSnr = -0.5,
                stats = null,
                snrTemplate = "SNR: %s dB",
                avgTemplate = "Avg SNR: %s dB (%s – %s)",
            ),
        )
        assertEquals(
            "Avg SNR: 5.0 dB (2.0 – 8.0)",
            hopSnrCaption(
                isStartNode = false,
                hopSnr = 2.0,
                stats = HopSnrStats(avg = 5.0, min = 2.0, max = 8.0),
                snrTemplate = "SNR: %s dB",
                avgTemplate = "Avg SNR: %s dB (%s – %s)",
            ),
        )
    }

    @Test
    fun formatSnrUsesTheInjectedUnit() {
        assertEquals("-0.5 дБ", formatSnr(-0.5, unit = "дБ"))
    }
}
