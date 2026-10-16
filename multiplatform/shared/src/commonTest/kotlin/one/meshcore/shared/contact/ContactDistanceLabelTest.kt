package one.meshcore.shared.contact

import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ports the iOS `ContactRowView.distanceToContact`: an abbreviated road distance next to a located row,
 * and no label when either the user location or the contact coordinate is missing. The contact list uses
 * the same metric/imperial split as the map scale.
 */
class ContactDistanceLabelTest {
    private val here = UserLocation(latitude = 37.7749, longitude = -122.4194)

    private fun located(latitude: Double, longitude: Double) = contactDto(
        id = ContactTestIds.fresh(),
        radioID = ContactTestIds.RADIO,
    ).copy(latitude = latitude, longitude = longitude)

    @Test
    fun formatsSubKilometerInMeters() {
        assertEquals("850 m", formatRoadDistance(850.4, MeasurementSystem.METRIC))
    }

    @Test
    fun formatsKilometersWithOneDecimal() {
        assertEquals("1.2 km", formatRoadDistance(1_234.0, MeasurementSystem.METRIC))
    }

    @Test
    fun formatsImperialMiles() {
        assertEquals("0.5 mi", formatRoadDistance(850.4, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun distanceLabelPresentForLocatedContactWithUserLocation() {
        val north = located(latitude = 37.7875, longitude = -122.4194)
        assertEquals("1.4 km", north.distanceLabel(here, MeasurementSystem.METRIC))
        assertEquals("0.9 mi", north.distanceLabel(here, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun distanceLabelUsesInjectedLengthSymbols() {
        val north = located(latitude = 37.7875, longitude = -122.4194)
        val cyrillic = LocalizedLengthSymbols(
            kilometer = "км",
            meter = "м",
            mile = "мили",
            foot = "фут",
        )
        assertEquals("1.4 км", north.distanceLabel(here, MeasurementSystem.METRIC, cyrillic))
        assertEquals("0.9 мили", north.distanceLabel(here, MeasurementSystem.IMPERIAL, cyrillic))
    }

    @Test
    fun distanceLabelNullWhenNoUserLocation() {
        assertNull(located(latitude = 37.79, longitude = -122.40).distanceLabel(null))
    }

    @Test
    fun distanceLabelNullWhenContactHasNoCoordinate() {
        // The default (0,0) coordinate is treated as "no location", so no distance is shown.
        assertNull(located(latitude = 0.0, longitude = 0.0).distanceLabel(here))
    }
}
