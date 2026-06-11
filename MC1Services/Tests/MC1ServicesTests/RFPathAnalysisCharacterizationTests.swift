import CoreLocation
import Testing
@testable import MC1Services

/// Pins exact `analyzePath`/`analyzePathSegment` outputs for representative
/// terrain profiles so any change to the shared analysis core is caught as a
/// numeric regression, not just a status flip.
@Suite("RF Path Analysis Characterization")
struct RFPathAnalysisCharacterizationTests {

    // MARK: - Profile Builders

    private static func flatProfile(
        elevation: Double,
        totalDistance: Double,
        count: Int
    ) -> [ElevationSample] {
        (0..<count).map { i in
            let fraction = Double(i) / Double(count - 1)
            return ElevationSample(
                coordinate: CLLocationCoordinate2D(latitude: 37.7749 + fraction * 0.01, longitude: -122.4194),
                elevation: elevation,
                distanceFromAMeters: fraction * totalDistance
            )
        }
    }

    /// Triangular mountain centered at the midpoint sample.
    private static func mountainProfile(
        base: Double,
        peak: Double,
        totalDistance: Double,
        count: Int
    ) -> [ElevationSample] {
        let midpoint = count / 2
        return (0..<count).map { i in
            let fraction = Double(i) / Double(count - 1)
            let distanceFromMid = abs(i - midpoint)
            let peakFactor = max(0, 1.0 - Double(distanceFromMid) / Double(midpoint))
            return ElevationSample(
                coordinate: CLLocationCoordinate2D(latitude: 37.7749 + fraction * 0.01, longitude: -122.4194),
                elevation: base + peak * peakFactor,
                distanceFromAMeters: fraction * totalDistance
            )
        }
    }

    /// Deterministic rolling terrain with multiple distinct obstruction regions.
    private static func rollingProfile(totalDistance: Double, count: Int) -> [ElevationSample] {
        (0..<count).map { i in
            let fraction = Double(i) / Double(count - 1)
            let elevation = 120.0 + 35.0 * sin(fraction * 9.0) + 18.0 * cos(fraction * 23.0)
            return ElevationSample(
                coordinate: CLLocationCoordinate2D(latitude: 37.0 + fraction * 0.05, longitude: -122.0),
                elevation: elevation,
                distanceFromAMeters: fraction * totalDistance
            )
        }
    }

    // MARK: - Full Path

    @Test("Flat 6km path at 910MHz pins clear-status outputs")
    func flatPathPinnedOutputs() {
        let result = RFCalculator.analyzePath(
            elevationProfile: Self.flatProfile(elevation: 0, totalDistance: 6000, count: 21),
            pointAHeightMeters: 50,
            pointBHeightMeters: 50,
            frequencyMHz: 910,
            refractionK: 1.0
        )

        #expect(result.distanceMeters == 6000.0)
        #expect(result.freeSpacePathLoss == 107.19385285409474)
        #expect(result.peakDiffractionLoss == 0.0)
        #expect(result.totalPathLoss == 107.19385285409474)
        #expect(result.clearanceStatus == .clear)
        #expect(result.worstClearancePercent == 221.7460578709339)
        #expect(result.obstructionPoints.isEmpty)
    }

    @Test("100m mountain at 6km pins blocked-status outputs")
    func mountainPathPinnedOutputs() throws {
        let result = RFCalculator.analyzePath(
            elevationProfile: Self.mountainProfile(base: 0, peak: 100, totalDistance: 6000, count: 21),
            pointAHeightMeters: 50,
            pointBHeightMeters: 50,
            frequencyMHz: 910,
            refractionK: 1.0
        )

        #expect(result.distanceMeters == 6000.0)
        #expect(result.freeSpacePathLoss == 107.19385285409474)
        #expect(result.peakDiffractionLoss == 29.205445519172578)
        #expect(result.totalPathLoss == 136.39929837326733)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.worstClearancePercent == -228.10082469417358)
        #expect(result.obstructionPoints.count == 13)
        #expect(result.peakObstructionPerRegion.count == 1)

        let worst = try #require(result.worstObstructionPoint)
        #expect(worst.distanceFromAMeters == 3000.0)
        #expect(worst.obstructionHeightMeters == 50.70632553759222)
        #expect(worst.fresnelClearancePercent == -228.10082469417358)

        let first = try #require(result.obstructionPoints.first)
        #expect(first.distanceFromAMeters == 1200.0)
        #expect(first.obstructionHeightMeters == -9.547951655940984)
        #expect(first.fresnelClearancePercent == 53.68895359134259)
    }

    @Test("45m hill with asymmetric heights at 868MHz, k=1.33 pins outputs")
    func asymmetricHillPinnedOutputs() throws {
        let result = RFCalculator.analyzePath(
            elevationProfile: Self.mountainProfile(base: 0, peak: 45, totalDistance: 6000, count: 21),
            pointAHeightMeters: 30,
            pointBHeightMeters: 10,
            frequencyMHz: 868,
            refractionK: 1.33
        )

        #expect(result.distanceMeters == 6000.0)
        #expect(result.freeSpacePathLoss == 106.7834195112027)
        #expect(result.peakDiffractionLoss == 23.667079476774447)
        #expect(result.totalPathLoss == 130.45049898797714)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.worstClearancePercent == -112.16902092433065)
        #expect(result.obstructionPoints.count == 15)
        #expect(result.peakObstructionPerRegion.count == 1)

        let first = try #require(result.obstructionPoints.first)
        #expect(first.distanceFromAMeters == 1200.0)
        #expect(first.obstructionHeightMeters == -7.660114027023294)
        #expect(first.fresnelClearancePercent == 42.067734964659884)
    }

    @Test("Rolling 10km terrain pins multi-region outputs")
    func rollingFullPathPinnedOutputs() throws {
        let result = RFCalculator.analyzePath(
            elevationProfile: Self.rollingProfile(totalDistance: 10_000, count: 41),
            pointAHeightMeters: 12,
            pointBHeightMeters: 7,
            frequencyMHz: 906,
            refractionK: 1.33
        )

        #expect(result.distanceMeters == 10_000.0)
        #expect(result.freeSpacePathLoss == 111.59256395353627)
        #expect(result.peakDiffractionLoss == 34.54506727017054)
        #expect(result.totalPathLoss == 146.13763122370682)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.worstClearancePercent == -166.65278266083646)
        #expect(result.obstructionPoints.count == 18)
        #expect(result.peakObstructionPerRegion.count == 3)

        let worst = try #require(result.worstObstructionPoint)
        #expect(worst.distanceFromAMeters == 8500.0)
        #expect(worst.obstructionHeightMeters == 34.23055294849772)
        #expect(worst.fresnelClearancePercent == -166.65278266083646)
    }

    // MARK: - Segments

    @Test("A-to-R segment over rolling terrain pins outputs")
    func segmentARPinnedOutputs() throws {
        let rolling = Self.rollingProfile(totalDistance: 10_000, count: 41)
        let result = RFCalculator.analyzePathSegment(
            elevationProfile: rolling[0...20],
            startHeightMeters: 12,
            endHeightMeters: 25,
            frequencyMHz: 906,
            refractionK: 1.33
        )

        #expect(result.distanceMeters == 5000.0)
        #expect(result.freeSpacePathLoss == 105.57196404025665)
        #expect(result.peakDiffractionLoss == 28.924374694910668)
        #expect(result.totalPathLoss == 134.4963387351673)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.worstClearancePercent == -139.44495806579053)
        #expect(result.obstructionPoints.count == 12)
        #expect(result.peakObstructionPerRegion.count == 1)

        let first = try #require(result.obstructionPoints.first)
        #expect(first.distanceFromAMeters == 500.0)
        #expect(first.obstructionHeightMeters == -4.239257565925641)
        #expect(first.fresnelClearancePercent == 34.7405983689846)
    }

    @Test("R-to-B segment keeps absolute obstruction distances")
    func segmentRBPinnedOutputs() throws {
        let rolling = Self.rollingProfile(totalDistance: 10_000, count: 41)
        let result = RFCalculator.analyzePathSegment(
            elevationProfile: rolling[20...40],
            startHeightMeters: 25,
            endHeightMeters: 7,
            frequencyMHz: 906,
            refractionK: 1.33
        )

        #expect(result.distanceMeters == 5000.0)
        #expect(result.freeSpacePathLoss == 105.57196404025665)
        #expect(result.peakDiffractionLoss == 26.543023748160607)
        #expect(result.totalPathLoss == 132.11498778841724)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.worstClearancePercent == -219.11961746223767)
        #expect(result.obstructionPoints.count == 10)
        #expect(result.peakObstructionPerRegion.count == 1)

        // Obstruction distances stay in full-path coordinates for chart rendering
        let worst = try #require(result.worstObstructionPoint)
        #expect(worst.distanceFromAMeters == 8250.0)
        #expect(worst.obstructionHeightMeters == 42.51118551351081)
        #expect(worst.fresnelClearancePercent == -219.11961746223767)

        let first = try #require(result.obstructionPoints.first)
        #expect(first.distanceFromAMeters == 7250.0)
        #expect(first.obstructionHeightMeters == -6.515142400094021)
        #expect(first.fresnelClearancePercent == 32.19623258059396)
    }

    // MARK: - Equivalence

    @Test("Full profile passed as a slice matches analyzePath exactly")
    func fullSliceMatchesAnalyzePath() {
        let rolling = Self.rollingProfile(totalDistance: 10_000, count: 41)

        let viaSegment = RFCalculator.analyzePathSegment(
            elevationProfile: rolling[...],
            startHeightMeters: 12,
            endHeightMeters: 7,
            frequencyMHz: 906,
            refractionK: 1.33
        )
        let viaPath = RFCalculator.analyzePath(
            elevationProfile: rolling,
            pointAHeightMeters: 12,
            pointBHeightMeters: 7,
            frequencyMHz: 906,
            refractionK: 1.33
        )

        #expect(viaSegment == viaPath)
    }

    @Test("Degenerate inputs return the zeroed blocked result")
    func degenerateInputs() {
        let empty = RFCalculator.analyzePath(
            elevationProfile: [],
            pointAHeightMeters: 50,
            pointBHeightMeters: 50,
            frequencyMHz: 910,
            refractionK: 1.0
        )
        #expect(empty.clearanceStatus == .blocked)
        #expect(empty.distanceMeters == 0)
        #expect(empty.frequencyMHz == 910)
        #expect(empty.refractionK == 1.0)

        let zeroLength = RFCalculator.analyzePathSegment(
            elevationProfile: Self.flatProfile(elevation: 0, totalDistance: 0, count: 3)[...],
            startHeightMeters: 50,
            endHeightMeters: 50,
            frequencyMHz: 910,
            refractionK: 1.0
        )
        #expect(zeroLength.clearanceStatus == .blocked)
        #expect(zeroLength.distanceMeters == 0)
    }
}
