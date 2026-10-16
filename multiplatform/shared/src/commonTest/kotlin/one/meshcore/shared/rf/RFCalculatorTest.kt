package one.meshcore.shared.rf

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit vectors for [RFCalculator], mirroring the Swift `RFCalculatorTests` suite. Tolerances match
 * the Swift `#expect(abs(...) < tol)` bounds; the exact-equality characterization vectors live in
 * [RFPathAnalysisCharacterizationTest].
 */
class RFCalculatorTest {
    // MARK: - Constants

    @Test
    fun speedOfLightConstantIsCorrect() {
        assertEquals(299_792_458.0, RFCalculator.SPEED_OF_LIGHT)
    }

    @Test
    fun earthRadiusConstantIsCorrect() {
        assertEquals(6371.0, RFCalculator.EARTH_RADIUS_KM)
    }

    // MARK: - Wavelength

    @Test
    fun wavelengthAt910MHzIsApproximately0_3294m() {
        assertTrue(abs(RFCalculator.wavelength(910.0) - 0.3294) < 0.001)
    }

    @Test
    fun wavelengthAt2400MHzIsApproximately0_125m() {
        assertTrue(abs(RFCalculator.wavelength(2400.0) - 0.125) < 0.001)
    }

    @Test
    fun wavelengthReturnsZeroForZeroFrequency() {
        assertEquals(0.0, RFCalculator.wavelength(0.0))
    }

    @Test
    fun wavelengthReturnsZeroForNegativeFrequency() {
        assertEquals(0.0, RFCalculator.wavelength(-100.0))
    }

    // MARK: - Fresnel Radius

    @Test
    fun fresnelRadiusAtMidpointIsApproximately22_23m() {
        val radius = RFCalculator.fresnelRadius(910.0, 3000.0, 3000.0)
        assertTrue(abs(radius - 22.23) < 0.5)
    }

    @Test
    fun fresnelRadiusAtQuarterPointIsSmallerThanMidpoint() {
        val totalDistance = 6000.0
        val quarterPoint = totalDistance / 4
        val radiusAtQuarter = RFCalculator.fresnelRadius(910.0, quarterPoint, totalDistance - quarterPoint)
        val radiusAtMidpoint = RFCalculator.fresnelRadius(910.0, 3000.0, 3000.0)
        assertTrue(radiusAtQuarter < radiusAtMidpoint)
        assertTrue(abs(radiusAtQuarter - 19.27) < 0.5)
    }

    @Test
    fun fresnelRadiusIsSymmetric() {
        val radius1 = RFCalculator.fresnelRadius(910.0, 2000.0, 4000.0)
        val radius2 = RFCalculator.fresnelRadius(910.0, 4000.0, 2000.0)
        assertTrue(abs(radius1 - radius2) < 0.001)
    }

    @Test
    fun fresnelRadiusReturnsZeroForInvalidInputs() {
        assertEquals(0.0, RFCalculator.fresnelRadius(0.0, 100.0, 100.0))
        assertEquals(0.0, RFCalculator.fresnelRadius(910.0, 0.0, 100.0))
        assertEquals(0.0, RFCalculator.fresnelRadius(910.0, 100.0, 0.0))
        assertEquals(0.0, RFCalculator.fresnelRadius(-100.0, 100.0, 100.0))
    }

    // MARK: - Earth Bulge

    @Test
    fun earthBulgeAtMidpointWithK0_25IsApproximately2_82m() {
        val bulge = RFCalculator.earthBulge(3000.0, 3000.0, 0.25)
        assertTrue(abs(bulge - 2.82) < 0.05)
    }

    @Test
    fun earthBulgeWithStandardAtmosphereK1_33IsSmaller() {
        val bulgeConservative = RFCalculator.earthBulge(3000.0, 3000.0, 0.25)
        val bulgeStandard = RFCalculator.earthBulge(3000.0, 3000.0, 1.33)
        assertTrue(bulgeStandard < bulgeConservative)
        assertTrue(abs(bulgeStandard - 0.53) < 0.05)
    }

    @Test
    fun earthBulgeIsSymmetric() {
        val bulge1 = RFCalculator.earthBulge(2000.0, 4000.0, 1.0)
        val bulge2 = RFCalculator.earthBulge(4000.0, 2000.0, 1.0)
        assertTrue(abs(bulge1 - bulge2) < 0.001)
    }

    @Test
    fun earthBulgeReturnsZeroForInvalidInputs() {
        assertEquals(0.0, RFCalculator.earthBulge(0.0, 100.0, 1.0))
        assertEquals(0.0, RFCalculator.earthBulge(100.0, 0.0, 1.0))
        assertEquals(0.0, RFCalculator.earthBulge(100.0, 100.0, 0.0))
        assertEquals(0.0, RFCalculator.earthBulge(100.0, 100.0, -1.0))
    }

    // MARK: - Path Loss

    @Test
    fun pathLossIsApproximately107_2dBFor6kmAt910MHz() {
        assertTrue(abs(RFCalculator.pathLoss(6000.0, 910.0) - 107.2) < 0.5)
    }

    @Test
    fun pathLossIncreasesWithDistance() {
        val loss1km = RFCalculator.pathLoss(1000.0, 910.0)
        val loss2km = RFCalculator.pathLoss(2000.0, 910.0)
        val loss4km = RFCalculator.pathLoss(4000.0, 910.0)
        assertTrue(loss2km > loss1km)
        assertTrue(loss4km > loss2km)
        assertTrue(abs((loss2km - loss1km) - 6.02) < 0.1)
        assertTrue(abs((loss4km - loss2km) - 6.02) < 0.1)
    }

    @Test
    fun pathLossIncreasesWithFrequency() {
        val loss400 = RFCalculator.pathLoss(1000.0, 400.0)
        val loss900 = RFCalculator.pathLoss(1000.0, 900.0)
        val loss2400 = RFCalculator.pathLoss(1000.0, 2400.0)
        assertTrue(loss900 > loss400)
        assertTrue(loss2400 > loss900)
    }

    @Test
    fun pathLossReturnsZeroForInvalidInputs() {
        assertEquals(0.0, RFCalculator.pathLoss(0.0, 910.0))
        assertEquals(0.0, RFCalculator.pathLoss(1000.0, 0.0))
        assertEquals(0.0, RFCalculator.pathLoss(-100.0, 910.0))
        assertEquals(0.0, RFCalculator.pathLoss(1000.0, -910.0))
    }

    // MARK: - Diffraction Loss

    @Test
    fun diffractionLossIsZeroForClearLineOfSight() {
        assertEquals(0.0, RFCalculator.diffractionLoss(-50.0, 3000.0, 3000.0, 910.0))
    }

    @Test
    fun diffractionLossIsApproximately6dBForGrazing() {
        assertTrue(abs(RFCalculator.diffractionLoss(0.0, 3000.0, 3000.0, 910.0) - 6.0) < 1.0)
    }

    @Test
    fun diffractionLossIncreasesForBlockedPathVNear1() {
        assertTrue(abs(RFCalculator.diffractionLoss(15.7, 3000.0, 3000.0, 910.0) - 13.9) < 0.5)
    }

    @Test
    fun diffractionLossIsGreaterForLargerObstructions() {
        val lossSmall = RFCalculator.diffractionLoss(5.0, 3000.0, 3000.0, 910.0)
        val lossMedium = RFCalculator.diffractionLoss(15.0, 3000.0, 3000.0, 910.0)
        val lossLarge = RFCalculator.diffractionLoss(30.0, 3000.0, 3000.0, 910.0)
        assertTrue(lossMedium > lossSmall)
        assertTrue(lossLarge > lossMedium)
    }

    @Test
    fun diffractionLossMatchesItuReferenceAtStrongObstructionVApprox2_4() {
        // h ≈ 37.7m gives v ≈ 2.4 for 6km at 910MHz; ITU-R P.526 J(2.4) ≈ 20.5 dB.
        // A wrong-sign polynomial approximation reads ≈ 35 dB here, so this anchors the regime and
        // is the sign-error regression guard.
        val loss = RFCalculator.diffractionLoss(37.7, 3000.0, 3000.0, 910.0)
        assertTrue(abs(loss - 20.5) < 0.7)
        assertTrue(loss < 25.0, "sign-error guard: wrong-sign polynomial would read ~35 dB, got $loss")
    }

    @Test
    fun diffractionLossReturnsZeroForInvalidInputs() {
        assertEquals(0.0, RFCalculator.diffractionLoss(10.0, 0.0, 100.0, 910.0))
        assertEquals(0.0, RFCalculator.diffractionLoss(10.0, 100.0, 0.0, 910.0))
        assertEquals(0.0, RFCalculator.diffractionLoss(10.0, 100.0, 100.0, 0.0))
    }

    // MARK: - Haversine Distance

    @Test
    fun distanceBetweenSameCoordinatesIsZero() {
        val coord = GeoCoordinate(37.7749, -122.4194)
        assertEquals(0.0, RFCalculator.distance(coord, coord))
    }

    @Test
    fun haversineDistanceIsAccurate() {
        val sanFrancisco = GeoCoordinate(37.7749, -122.4194)
        val losAngeles = GeoCoordinate(34.0522, -118.2437)
        assertTrue(abs(RFCalculator.distance(sanFrancisco, losAngeles) - 559_000) < 10_000)
    }

    @Test
    fun haversineDistanceIsSymmetric() {
        val coord1 = GeoCoordinate(37.7749, -122.4194)
        val coord2 = GeoCoordinate(34.0522, -118.2437)
        val distance1 = RFCalculator.distance(coord1, coord2)
        val distance2 = RFCalculator.distance(coord2, coord1)
        assertTrue(abs(distance1 - distance2) < 0.001)
    }

    @Test
    fun distanceAcrossDateLineIsCorrect() {
        val tokyo = GeoCoordinate(35.6762, 139.6503)
        val sanFrancisco = GeoCoordinate(37.7749, -122.4194)
        assertTrue(abs(RFCalculator.distance(tokyo, sanFrancisco) - 8_280_000) < 100_000)
    }

    @Test
    fun shortDistanceIsAccurate() {
        val point1 = GeoCoordinate(37.7749, -122.4194)
        val point2 = GeoCoordinate(37.7839, -122.4194)
        assertTrue(abs(RFCalculator.distance(point1, point2) - 1000) < 100)
    }

    // MARK: - Path Analysis status behavior

    private fun obstructedProfile(
        base: Double,
        obstruction: Double,
        totalDistance: Double,
        count: Int,
    ): List<ElevationSample> {
        val midpoint = count / 2
        return (0 until count).map { i ->
            val fraction = i.toDouble() / (count - 1)
            val distanceFromMid = abs(i - midpoint)
            val peakFactor = maxOf(0.0, 1.0 - distanceFromMid.toDouble() / midpoint)
            ElevationSample(
                coordinate = GeoCoordinate(37.7749 + fraction * 0.01, -122.4194),
                elevation = base + obstruction * peakFactor,
                distanceFromAMeters = fraction * totalDistance,
            )
        }
    }

    @Test
    fun clearPathWithFlatTerrainReturnsClearStatus() {
        val result = RFCalculator.analyzePath(flatProfile(0.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Clear, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 80)
        assertTrue(result.obstructionPoints.isEmpty())
        assertEquals(6000.0, result.distanceMeters)
        assertEquals(6.0, result.distanceKm)
    }

    @Test
    fun clearPathHasOnlyFsplNoDiffractionLoss() {
        val result = RFCalculator.analyzePath(flatProfile(0.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
        assertTrue(abs(result.freeSpacePathLoss - 107.2) < 1.0)
        assertEquals(0.0, result.peakDiffractionLoss)
        assertEquals(result.freeSpacePathLoss, result.totalPathLoss)
    }

    @Test
    fun blockedPathWith100mMountainReturnsBlockedStatus() {
        val result = RFCalculator.analyzePath(obstructedProfile(0.0, 100.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertTrue(result.worstClearancePercent < 0)
        assertTrue(result.obstructionPoints.isNotEmpty())
    }

    @Test
    fun blockedPathHasSignificantDiffractionLoss() {
        val result = RFCalculator.analyzePath(obstructedProfile(0.0, 100.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
        assertTrue(result.peakDiffractionLoss > 10)
        assertTrue(result.totalPathLoss > result.freeSpacePathLoss)
    }

    @Test
    fun partialObstructionPathReturnsPartialObstructionStatus() {
        val result = RFCalculator.analyzePath(obstructedProfile(0.0, 45.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.PartialObstruction, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 0)
        assertTrue(result.worstClearancePercent < 60)
        assertTrue(result.obstructionPoints.isNotEmpty())
    }

    @Test
    fun emptyProfileReturnsBlockedStatus() {
        val result = RFCalculator.analyzePath(emptyList(), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(0.0, result.distanceMeters)
    }

    @Test
    fun singleSampleProfileReturnsBlockedStatus() {
        val sample = ElevationSample(GeoCoordinate(37.7749, -122.4194), 0.0, 0.0)
        val result = RFCalculator.analyzePath(listOf(sample), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
    }

    @Test
    fun zeroTotalDistanceProfileReturnsBlockedStatus() {
        val samples = listOf(
            ElevationSample(GeoCoordinate(37.7749, -122.4194), 0.0, 0.0),
            ElevationSample(GeoCoordinate(37.7749, -122.4194), 0.0, 0.0),
        )
        val result = RFCalculator.analyzePath(samples, 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(0.0, result.distanceMeters)
    }

    @Test
    fun asymmetricAntennaHeightsAreHandledCorrectly() {
        val result = RFCalculator.analyzePath(flatProfile(0.0, 6000.0, 21), 100.0, 20.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Clear, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 80)
    }

    @Test
    fun customKFactorAffectsEarthBulge() {
        val profile = obstructedProfile(0.0, 40.0, 6000.0, 21)
        val conservative = RFCalculator.analyzePath(profile, 50.0, 50.0, 910.0, 0.25)
        val standard = RFCalculator.analyzePath(profile, 50.0, 50.0, 910.0, 1.33)
        assertTrue(standard.worstClearancePercent > conservative.worstClearancePercent)
    }

    // MARK: - Result field echoes

    @Test
    fun resultEchoesFrequency() {
        val profile = listOf(
            ElevationSample(GeoCoordinate(37.7749, -122.4194), 0.0, 0.0),
            ElevationSample(GeoCoordinate(37.7849, -122.4194), 0.0, 1000.0),
        )
        val result = RFCalculator.analyzePath(profile, 50.0, 50.0, 915.0, 1.33)
        assertEquals(915.0, result.frequencyMHz)
    }

    @Test
    fun resultEchoesKFactor() {
        val profile = listOf(
            ElevationSample(GeoCoordinate(37.7749, -122.4194), 0.0, 0.0),
            ElevationSample(GeoCoordinate(37.7849, -122.4194), 0.0, 1000.0),
        )
        val result = RFCalculator.analyzePath(profile, 50.0, 50.0, 906.0, 1.33)
        assertEquals(1.33, result.refractionK)
    }

    // MARK: - Segment analysis

    @Test
    fun analyzePathSegmentWorksWithSubList() {
        val samples = (0..10).map { i ->
            ElevationSample(GeoCoordinate(37.0 + i * 0.01, -122.0), 100.0, i * 1000.0)
        }
        val result = RFCalculator.analyzePathSegment(samples.subList(0, 6), 50.0, 50.0, 906.0, 1.0)
        assertEquals(5000.0, result.distanceMeters)
        assertEquals(ClearanceStatus.Clear, result.clearanceStatus)
    }

    @Test
    fun analyzePathSegmentHandlesOffsetSlice() {
        val samples = (0..10).map { i ->
            ElevationSample(GeoCoordinate(37.0 + i * 0.01, -122.0), 100.0, i * 1000.0)
        }
        val result = RFCalculator.analyzePathSegment(samples.subList(5, 11), 10.0, 10.0, 906.0, 1.0)
        assertEquals(5000.0, result.distanceMeters)
    }
}
