package one.meshcore.shared.rf

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins exact `analyzePath`/`analyzePathSegment` outputs for representative terrain profiles so any
 * change to the shared analysis core is caught as a numeric regression, not just a status flip.
 * Every expected double is asserted with exact equality; a drift of even one ulp is a safety
 * regression for line-of-sight results. Mirrors the Swift `RFPathAnalysisCharacterizationTests`.
 */
class RFPathAnalysisCharacterizationTest {
    // MARK: - Profile Builders

    /** Triangular mountain centered at the midpoint sample. */
    private fun mountainProfile(base: Double, peak: Double, totalDistance: Double, count: Int): List<ElevationSample> {
        val midpoint = count / 2
        return (0 until count).map { i ->
            val fraction = i.toDouble() / (count - 1)
            val distanceFromMid = abs(i - midpoint)
            val peakFactor = maxOf(0.0, 1.0 - distanceFromMid.toDouble() / midpoint)
            ElevationSample(
                coordinate = GeoCoordinate(37.7749 + fraction * 0.01, -122.4194),
                elevation = base + peak * peakFactor,
                distanceFromAMeters = fraction * totalDistance,
            )
        }
    }

    /** Deterministic rolling terrain with multiple distinct obstruction regions. */
    private fun rollingProfile(totalDistance: Double, count: Int): List<ElevationSample> {
        val lastIndex = count - 1
        return (0 until count).map { i ->
            val fraction = i.toDouble() / lastIndex
            val elevation = 120.0 + 35.0 * sin(fraction * 9.0) + 18.0 * cos(fraction * 23.0)
            ElevationSample(
                coordinate = GeoCoordinate(37.0 + fraction * 0.05, -122.0),
                elevation = elevation,
                distanceFromAMeters = fraction * totalDistance,
            )
        }
    }

    // MARK: - Full Path

    @Test
    fun flat6kmPathAt910MHzPinsClearStatusOutputs() {
        val result = RFCalculator.analyzePath(flatProfile(0.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)

        assertEquals(6000.0, result.distanceMeters)
        assertEquals(107.19385285409474, result.freeSpacePathLoss)
        assertEquals(0.0, result.peakDiffractionLoss)
        assertEquals(107.19385285409474, result.totalPathLoss)
        assertEquals(ClearanceStatus.Clear, result.clearanceStatus)
        assertEquals(221.7460578709339, result.worstClearancePercent)
        assertEquals(0, result.obstructionPoints.size)
    }

    @Test
    fun mountain100mAt6kmPinsBlockedStatusOutputs() {
        val result = RFCalculator.analyzePath(mountainProfile(0.0, 100.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)

        assertEquals(6000.0, result.distanceMeters)
        assertEquals(107.19385285409474, result.freeSpacePathLoss)
        assertEquals(23.034080243532884, result.peakDiffractionLoss)
        assertEquals(130.2279330976276, result.totalPathLoss)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(-228.10082469417358, result.worstClearancePercent)
        assertEquals(13, result.obstructionPoints.size)
        assertEquals(1, result.peakObstructionPerRegion.size)

        val worst = assertNotNull(result.worstObstructionPoint)
        assertEquals(3000.0, worst.distanceFromAMeters)
        assertEquals(50.70632553759222, worst.obstructionHeightMeters)
        assertEquals(-228.10082469417358, worst.fresnelClearancePercent)

        val first = result.obstructionPoints.first()
        assertEquals(1200.0, first.distanceFromAMeters)
        assertEquals(-9.547951655940984, first.obstructionHeightMeters)
        assertEquals(53.68895359134259, first.fresnelClearancePercent)
    }

    @Test
    fun hill45mAsymmetricAt868MHzK1_33PinsOutputs() {
        val result = RFCalculator.analyzePath(mountainProfile(0.0, 45.0, 6000.0, 21), 30.0, 10.0, 868.0, 1.33)

        assertEquals(6000.0, result.distanceMeters)
        // Free-space path loss and the total that inherits it sit one unit-in-the-last-place above
        // the Swift/Darwin reference (106.7834195112027 / 123.99483019798764). The cause is the
        // JVM's libm log10 differing from Darwin's by 1 ulp for this argument, not an algorithm
        // difference: Python (Darwin libm) reproduces every vector exactly, and both JVM Math.log10
        // and StrictMath.log10 agree on this value. The gap is at the 14th significant digit
        // (physically identical loss) and is unrelated to the sign-error safety guard. Pinned to the
        // value the Kotlin code actually produces on its JVM/Android target so this stays an exact
        // regression check; every other field below matches Swift bit-for-bit.
        assertEquals(106.78341951120272, result.freeSpacePathLoss)
        assertEquals(17.211410686784944, result.peakDiffractionLoss)
        assertEquals(123.99483019798767, result.totalPathLoss)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(-112.16902092433065, result.worstClearancePercent)
        assertEquals(15, result.obstructionPoints.size)
        assertEquals(1, result.peakObstructionPerRegion.size)

        val first = result.obstructionPoints.first()
        assertEquals(1200.0, first.distanceFromAMeters)
        assertEquals(-7.660114027023294, first.obstructionHeightMeters)
        assertEquals(42.067734964659884, first.fresnelClearancePercent)
    }

    @Test
    fun rolling10kmTerrainPinsMultiRegionOutputs() {
        val result = RFCalculator.analyzePath(rollingProfile(10000.0, 41), 12.0, 7.0, 906.0, 1.33)

        assertEquals(10000.0, result.distanceMeters)
        assertEquals(111.59256395353627, result.freeSpacePathLoss)
        assertEquals(20.388551277139328, result.peakDiffractionLoss)
        assertEquals(131.98111523067558, result.totalPathLoss)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(-166.65278266083646, result.worstClearancePercent)
        assertEquals(18, result.obstructionPoints.size)
        assertEquals(3, result.peakObstructionPerRegion.size)

        val worst = assertNotNull(result.worstObstructionPoint)
        assertEquals(8500.0, worst.distanceFromAMeters)
        assertEquals(34.23055294849772, worst.obstructionHeightMeters)
        assertEquals(-166.65278266083646, worst.fresnelClearancePercent)
    }

    // MARK: - Segments

    @Test
    fun aToRSegmentOverRollingTerrainPinsOutputs() {
        val rolling = rollingProfile(10000.0, 41)
        val result = RFCalculator.analyzePathSegment(rolling.subList(0, 21), 12.0, 25.0, 906.0, 1.33)

        assertEquals(5000.0, result.distanceMeters)
        assertEquals(105.57196404025665, result.freeSpacePathLoss)
        assertEquals(18.929132368423446, result.peakDiffractionLoss)
        assertEquals(124.5010964086801, result.totalPathLoss)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(-139.44495806579053, result.worstClearancePercent)
        assertEquals(12, result.obstructionPoints.size)
        assertEquals(1, result.peakObstructionPerRegion.size)

        val first = result.obstructionPoints.first()
        assertEquals(500.0, first.distanceFromAMeters)
        assertEquals(-4.239257565925641, first.obstructionHeightMeters)
        assertEquals(34.7405983689846, first.fresnelClearancePercent)
    }

    @Test
    fun rToBSegmentKeepsAbsoluteObstructionDistances() {
        val rolling = rollingProfile(10000.0, 41)
        val result = RFCalculator.analyzePathSegment(rolling.subList(20, 41), 25.0, 7.0, 906.0, 1.33)

        assertEquals(5000.0, result.distanceMeters)
        assertEquals(105.57196404025665, result.freeSpacePathLoss)
        assertEquals(22.69158176325522, result.peakDiffractionLoss)
        assertEquals(128.26354580351187, result.totalPathLoss)
        assertEquals(ClearanceStatus.Blocked, result.clearanceStatus)
        assertEquals(-219.11961746223767, result.worstClearancePercent)
        assertEquals(10, result.obstructionPoints.size)
        assertEquals(1, result.peakObstructionPerRegion.size)

        // Obstruction distances stay in full-path coordinates for chart rendering.
        val worst = assertNotNull(result.worstObstructionPoint)
        assertEquals(8250.0, worst.distanceFromAMeters)
        assertEquals(42.51118551351081, worst.obstructionHeightMeters)
        assertEquals(-219.11961746223767, worst.fresnelClearancePercent)

        val first = result.obstructionPoints.first()
        assertEquals(7250.0, first.distanceFromAMeters)
        assertEquals(-6.515142400094021, first.obstructionHeightMeters)
        assertEquals(32.19623258059396, first.fresnelClearancePercent)
    }

    // MARK: - Equivalence

    @Test
    fun fullProfilePassedAsSliceMatchesAnalyzePathExactly() {
        val rolling = rollingProfile(10000.0, 41)
        val viaSegment = RFCalculator.analyzePathSegment(rolling, 12.0, 7.0, 906.0, 1.33)
        val viaPath = RFCalculator.analyzePath(rolling, 12.0, 7.0, 906.0, 1.33)
        assertEquals(viaPath, viaSegment)
    }

    @Test
    fun degenerateInputsReturnZeroedBlockedResult() {
        val empty = RFCalculator.analyzePath(emptyList(), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, empty.clearanceStatus)
        assertEquals(0.0, empty.distanceMeters)
        assertEquals(910.0, empty.frequencyMHz)
        assertEquals(1.0, empty.refractionK)

        val zeroLength = RFCalculator.analyzePathSegment(flatProfile(0.0, 0.0, 3), 50.0, 50.0, 910.0, 1.0)
        assertEquals(ClearanceStatus.Blocked, zeroLength.clearanceStatus)
        assertEquals(0.0, zeroLength.distanceMeters)
    }
}
