package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatThousandthsTest {
    @Test
    fun wholeValuesDropTheFractionAndThousandthsStay() {
        assertEquals("910", formatThousandths(910.0))
        assertEquals("62.5", formatThousandths(62.5))
        assertEquals("910.525", formatThousandths(910.525))
    }

    @Test
    fun presetDetailLineUsesTheSameFrequencyText() {
        val preset = RadioPreset(
            id = "sample",
            name = "Sample",
            region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 910.525,
            bandwidthKHz = 62.5,
            spreadingFactor = 7,
            codingRate = 5,
            availability = PresetAvailability.Countries(setOf("US")),
        )
        assertEquals("910.525 MHz, SF7, CR5", preset.detailLine())
    }
}
