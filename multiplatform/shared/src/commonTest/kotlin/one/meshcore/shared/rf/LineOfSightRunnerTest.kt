package one.meshcore.shared.rf

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.FakeGeoHttpClient
import one.meshcore.shared.map.GeoHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineOfSightRunnerTest {
    @Test
    fun withoutElevationServiceUsesFlatProfile() = runTest {
        val inputs = sampleInputs()
        val run = runLineOfSightAnalysis(inputs, elevationService = null)
        assertFalse(run.usedTerrainElevation)
        assertTrue(run.profile.all { it.elevation == 0.0 })
        assertTrue(run.analysis.distanceMeters > 0.0)
    }

    @Test
    fun terrainServiceMarksUsedTerrainElevation() = runTest {
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                // Match Open-Meteo: one elevation per latitude in the query.
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(2)
                val elevations = List(count) { 100.0 + it }
                GeoHttpResponse(200, """{"elevation":[${elevations.joinToString(",")}]}""")
            },
        )
        val run = runLineOfSightAnalysis(sampleInputs(), service)
        assertTrue(run.usedTerrainElevation)
        assertTrue(run.profile.any { it.elevation > 0.0 })
        assertEquals(run.profile.size, run.profile.map { it.distanceFromAMeters }.toSet().size)
    }

    @Test
    fun elevationFailureFallsBackToFlat() = runTest {
        val service = ElevationService(
            FakeGeoHttpClient { _ -> GeoHttpResponse(500, "fail") },
        )
        val run = runLineOfSightAnalysis(sampleInputs(), service)
        assertFalse(run.usedTerrainElevation)
        assertTrue(run.profile.all { it.elevation == 0.0 })
    }

    @Test
    fun shareTextUsesProvidedLabelsNotHardcodedEnglish() {
        val run = runLineOfSightFlat(sampleInputs())
        val labels = LineOfSightShareLabels(
            title = "Sichtverbindung",
            distance = "Entfernung",
            clearance = "Freiraum",
            freeSpaceLoss = "Freiraumdämpfung",
            diffractionLoss = "Beugungsverlust",
            totalLoss = "Gesamt",
            frequency = "Frequenz",
            refraction = "Refraktion",
        )
        val text = formatLineOfSightShareText(
            analysis = run.analysis,
            clearanceLabel = "Frei",
            elevationNote = "Gelände",
            labels = labels,
        )
        assertTrue(text.startsWith("Sichtverbindung"))
        assertTrue(text.contains("Freiraum: Frei"))
        assertTrue(text.contains("Gesamt:"))
        assertTrue(text.contains("Gelände"))
        assertFalse(text.contains("Line of Sight"))
        assertFalse(text.contains("Clearance:"))
        assertFalse(text.contains("Total path loss"))
    }

    private fun sampleInputs(): LineOfSightInputs = LineOfSightInputs(
        pointA = GeoCoordinate(37.0, -122.0),
        pointB = GeoCoordinate(37.05, -122.05),
        heightAMeters = 10.0,
        heightBMeters = 10.0,
        frequencyMHz = 910.525,
        refractionK = 1.33,
    )
}
