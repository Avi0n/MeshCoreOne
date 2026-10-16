package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Picker writes for [RegionSelection.afterChoosingCountry] and
 * [RegionSelection.afterChoosingSubdivision]: a re-tap is a no-op, a new country drops leftover
 * state/county, and a subdivision change drops county.
 */
class RegionSelectionPickerTest {
    @Test
    fun afterChoosingCountrySkipsAReTapAndOtherwiseWritesAManualCountry() {
        val current = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            countyKey = "los angeles",
            source = RegionSource.LOCATION,
        )
        assertNull(RegionSelection.afterChoosingCountry("US", current = current))
        assertEquals(
            RegionSelection(countryCode = "PT", source = RegionSource.MANUAL),
            RegionSelection.afterChoosingCountry("PT", current = current),
        )
        assertEquals(
            RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            RegionSelection.afterChoosingCountry("US", current = null),
        )
    }

    @Test
    fun afterChoosingSubdivisionSkipsAReTapDropsCountyOnChangeAndNeedsACountry() {
        val current = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            countyKey = "los angeles",
            source = RegionSource.LOCATION,
        )
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", current = current))
        assertEquals(
            RegionSelection(
                countryCode = "US",
                administrativeAreaCode = "US-TX",
                source = RegionSource.MANUAL,
            ),
            RegionSelection.afterChoosingSubdivision("US-TX", current = current),
        )
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", current = null))
    }
}
