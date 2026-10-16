package one.meshcore.shared.tools.ui

import one.meshcore.shared.contact.mapsGeoUri
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The text a Line of Sight point is copied, shared and opened in a map app as. Getting this wrong sends
 * someone to the wrong place, so the copied text and the map URI are pinned here.
 */
class LineOfSightPointActionsTest {
    @Test
    fun coordinateTextMatchesWhatTheRowShows() {
        assertEquals("52.37021, 4.89516", losCoordinateText(LatLng(52.370216, 4.895168)))
    }

    @Test
    fun coordinateTextKeepsSouthernAndWesternSigns() {
        assertEquals("-33.86880, -151.20930", losCoordinateText(LatLng(-33.8688, -151.2093)))
    }

    @Test
    fun mapsUriCarriesTheCoordinateAndThePointName() {
        val point = LatLng(52.370216, 4.895168)
        assertEquals(
            "geo:52.37022,4.89517?q=52.37022,4.89517(Point A)",
            mapsGeoUri(
                latitude = point.latitude,
                longitude = point.longitude,
                label = "Point A",
            ),
        )
    }

    @Test
    fun relocateDismissSpeaksCloseNotCancel() {
        assertEquals(Res.string.localizable_common_close, losRelocateDismissSpokenResource())
    }
}
