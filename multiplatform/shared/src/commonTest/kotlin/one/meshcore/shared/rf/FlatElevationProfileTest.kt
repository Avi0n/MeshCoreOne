package one.meshcore.shared.rf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatElevationProfileTest {
    @Test
    fun flatProfileHasEndpointsAndZeroElevation() {
        val a = GeoCoordinate(37.0, -122.0)
        val b = GeoCoordinate(37.1, -122.1)
        val profile = flatElevationProfile(a, b, sampleCount = 5)
        assertEquals(5, profile.size)
        assertEquals(0.0, profile.first().distanceFromAMeters)
        assertTrue(profile.last().distanceFromAMeters > 0.0)
        assertTrue(profile.all { it.elevation == 0.0 })
    }

    @Test
    fun parseAndAnalyzeProducesPositiveDistance() {
        val parsed = parseLineOfSightInputs(
            LineOfSightFormTexts(
                latA = "37.0",
                lonA = "-122.0",
                heightA = "10",
                latB = "37.05",
                lonB = "-122.05",
                heightB = "10",
                frequencyMHz = "910.525",
                refractionK = "1.33",
            ),
        )
        val ok = assertIs<LineOfSightParseResult.Ok>(parsed)
        val result = analyzeLineOfSightFlat(ok.inputs)
        assertTrue(result.distanceMeters > 1000.0)
        assertTrue(result.freeSpacePathLoss > 0.0)
        // Flat sea-level profiles can still show earth-bulge obstruction on longer hops; status is not asserted.
    }

    @Test
    fun parseRejectsBadLatitude() {
        assertIs<LineOfSightParseResult.Invalid>(
            parseLineOfSightInputs(
                LineOfSightFormTexts("999", "0", "2", "0", "0", "2", "900", "1.33"),
            ),
        )
    }

    @Test
    fun parseRejectsNonFiniteRadioFields() {
        // "NaN".toDoubleOrNull() returns NaN, and NaN is not greater than zero.
        val base = LineOfSightFormTexts("37.0", "-122.0", "2", "37.05", "-122.05", "2", "910", "1.33")
        assertIs<LineOfSightParseResult.Invalid>(parseLineOfSightInputs(base.copy(frequencyMHz = "NaN")))
        assertIs<LineOfSightParseResult.Invalid>(parseLineOfSightInputs(base.copy(frequencyMHz = "0")))
        assertIs<LineOfSightParseResult.Invalid>(parseLineOfSightInputs(base.copy(refractionK = "NaN")))
    }
}
